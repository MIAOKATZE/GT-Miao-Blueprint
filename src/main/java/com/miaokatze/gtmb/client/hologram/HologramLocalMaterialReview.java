// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.FutureTask;
import java.util.function.Function;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.hologram.HologramStaticMaterials;

/** Frozen scope planning off-thread and native resource/inventory reads on the owning client thread. */
final class HologramLocalMaterialReview {

    private static final int CELLS_PER_TICK = 128;
    private static final long SLICE_NANOS = 2_000_000L;

    private final HologramState source;
    private final NBTTagCompound settings;
    private final int selected, scope, layer, x, y, z;
    private final int[] selection;
    private final Block sourceWater;
    private FutureTask<Plan> planning;
    private final PriorityQueue<Unbatched> pending = new PriorityQueue<>(Comparator.comparingInt(Unbatched::index));
    private volatile int scanned;
    private Plan plan;
    private int nextWork, settled;
    private boolean failed;
    private final String family;
    private HologramStaticMaterials.Builder demand = new HologramStaticMaterials.Builder();
    private int targetCount, unaccounted;
    private NBTTagCompound theory, visible = new NBTTagCompound();
    private ItemStack[] observed;
    private int observedSlot = -1;

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings) {
        this(source, settings, ForkJoinPool.commonPool());
    }

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings, Executor executor) {
        this.source = source;
        this.settings = (NBTTagCompound) settings.copy();
        selected = settings.hasKey("selected", 3) ? settings.getInteger("selected") : -1;
        scope = settings.getInteger("scope");
        layer = settings.getInteger("layer");
        x = source.data.getInteger("x");
        y = source.data.getInteger("y");
        z = source.data.getInteger("z");
        selection = this.settings.getIntArray("selectedIndices");
        sourceWater = Blocks.water;
        family = selected >= 0 && selected < source.cells.size() ? source.cells.get(selected).family : "";
        planning = new FutureTask<>(this::prepare);
        executor.execute(planning);
    }

    void close() {
        if (planning != null) planning.cancel(true);
        planning = null;
    }

    boolean current(HologramState state, int layer) {
        return source.cells == state.cells && source.session.equals(state.session)
            && source.data.getLong("planRevision") == state.data.getLong("planRevision")
            && source.data.getInteger("mode") == state.data.getInteger("mode")
            && source.data.getString("activeStructure")
                .equals(state.data.getString("activeStructure"))
            && !state.data.getBoolean("localPlanPending")
            && !state.data.getBoolean("targetClosed")
            && this.layer == layer;
    }

    NBTTagCompound settings() {
        return (NBTTagCompound) settings.copy();
    }

    boolean ready() {
        return theory != null;
    }

    NBTTagCompound visible() {
        return visible;
    }

    String scopeLabel() {
        return scope == 2 ? "已选范围的目标材料" : "当前范围的目标材料";
    }

    String status() {
        if (failed) return "本地材料统计未完成，请重新打开材料窗口";
        if (!ready()) return plan == null ? "正在整理本地材料范围 " + scanned + " / " + source.cells.size()
            : "正在核对本地目标材料 " + settled + " / " + plan.cells;
        String status = targetCount + " 个目标格";
        if (unaccounted > 0) status += " · " + unaccounted + " 格未核算";
        if (theory.getBoolean("alternatives") || theory.getBoolean("cacheReviewRequired")) status += " · 替代料或设备缓存待施工核验";
        return status;
    }

    boolean empty() {
        return ready() && targetCount == 0;
    }

    void tick(ItemStack[] inventory, int protectedSlot) {
        if (theory == null) {
            if (plan == null) {
                if (failed || planning == null || planning.isCancelled() || !planning.isDone()) return;
                try {
                    plan = planning.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failed = true;
                    return;
                } catch (ExecutionException failure) {
                    failed = true;
                    return;
                } finally {
                    planning = null;
                }
            }
            long deadline = System.nanoTime() + SLICE_NANOS;
            int stepped = 0;
            while ((nextWork < plan.work.size() || !pending.isEmpty()) && stepped < CELLS_PER_TICK
                && (stepped == 0 || System.nanoTime() < deadline)) {
                Work work = nextWork < plan.work.size() ? plan.work.get(nextWork) : null;
                Unbatched earlier = pending.peek();
                if (earlier != null && (work == null || earlier.index() < work.first)) {
                    pending.remove();
                    collect(source.cells.get(earlier.index()));
                    if (++earlier.cursor < earlier.work.repeats) pending.add(earlier);
                    settled++;
                } else {
                    nextWork++;
                    HologramState.Cell cell = source.cells.get(work.first);
                    if (work.repeats > 0 && collectRepeated(cell, work.repeats + 1)) settled += work.repeats + 1;
                    else {
                        collect(cell);
                        if (work.repeats > 0) pending.add(new Unbatched(work));
                        settled++;
                    }
                }
                stepped++;
            }
            if (nextWork < plan.work.size() || !pending.isEmpty()) return;
            theory = demand.snapshot();
            demand = null;
            plan = null;
        }
        refresh(inventory, protectedSlot);
    }

    void refresh(ItemStack[] inventory, int protectedSlot) {
        if (theory == null) return;
        if (!settings.getBoolean("materialMain")) inventory = null;
        if (visible.hasKey("rows", 9) && observedSlot == protectedSlot
            && sameInventory(observed, inventory, protectedSlot)) return;
        observedSlot = protectedSlot;
        observed = copyInventory(inventory, protectedSlot);
        visible = stockRows(theory, observed, protectedSlot);
    }

    private Plan prepare() {
        Set<Integer> chosen = new HashSet<>();
        if (scope == 2) for (int index : selection) {
            if (Thread.currentThread().isInterrupted()) return null;
            chosen.add(index);
        }
        List<Work> work = new ArrayList<>();
        Map<PlainKey, Work> plain = new HashMap<>();
        int cells = 0;
        for (int index = 0; index < source.cells.size(); index++) {
            if ((index & 1023) == 0) {
                if (Thread.currentThread().isInterrupted()) return null;
                scanned = index;
            }
            HologramState.Cell cell = source.cells.get(index);
            if (!includes(cell, index, chosen)) continue;
            cells++;
            HologramControllerTarget.Cell nativeTarget = cell.capturedNativeTarget();
            if (nativeTarget != null && nativeTarget.targetBlock() != null && cell.plainMaterialItem != null
                && !cell.missingBlock && !cell.iconOnly) {
                PlainKey key = new PlainKey(
                    nativeTarget.targetBlock(),
                    cell.wantMeta,
                    cell.plainMaterialItem,
                    cell.plainMaterialDamage);
                Work existing = plain.get(key);
                if (existing != null) existing.add(index);
                else {
                    Work first = new Work(index);
                    plain.put(key, first);
                    work.add(first);
                }
            } else work.add(new Work(index));
        }
        scanned = source.cells.size();
        return new Plan(work, cells);
    }

    private boolean includes(HologramState.Cell cell, int index, Set<Integer> chosen) {
        if (cell.excluded || !cell.inScope || cell.anchor || "minecraft:air".equals(cell.wantId)) return false;
        if (cell.x == x && cell.y == y && cell.z == z) return false;
        HologramControllerTarget.Cell nativeTarget = cell.capturedNativeTarget();
        if (nativeTarget != null && nativeTarget.kind() == HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER
            && nativeTarget.x() == cell.x
            && nativeTarget.y() == cell.y
            && nativeTarget.z() == cell.z
            && nativeTarget.targetBlock() == sourceWater
            && cell.wantMeta == 0
            && cell.x - x == cell.dx
            && cell.y - y == cell.dy
            && cell.z - z == cell.dz) return false;
        return switch (scope) {
            case 0 -> true;
            case 1 -> cell.dy == layer;
            case 2 -> chosen.isEmpty() ? selected == index : chosen.contains(index);
            case 3 -> !family.isEmpty() && family.equals(cell.family);
            default -> false;
        };
    }

    private boolean collectRepeated(HologramState.Cell cell, int count) {
        ItemStack target = cell.targetStack;
        if (target == null || target.hasTagCompound() || target.getItem() != cell.plainMaterialItem
            || target.getItemDamage() != cell.plainMaterialDamage) return false;
        try {
            if (!demand.addRepeatedPlain(cell.block(false), cell.wantMeta, target, count)) return false;
            targetCount += count;
            return true;
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private void collect(HologramState.Cell cell) {
        targetCount++;
        if (cell.targetStack == null || cell.missingBlock || cell.iconOnly) {
            unknown("部分格缺少可识别的目标材料");
            return;
        }
        try {
            // A native target provides the item payload, not a retained tile payload. Display NBT is not payment data.
            ItemStack target = cell.targetStack.copy();
            demand.add(cell.block(false), cell.wantMeta, target, null, target.getTagCompound());
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            unknown("部分目标的材料身份或原生资源无法在本机核算");
        }
    }

    @Desugar
    private record PlainKey(Block block, int meta, Item item, int damage) {

        @Override
        public boolean equals(Object other) {
            return other instanceof PlainKey key && block == key.block && item == key.item
                && meta == key.meta && damage == key.damage;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * System.identityHashCode(block) + meta) + System.identityHashCode(item)) + damage;
        }
    }

    @Desugar
    private record Plan(List<Work> work, int cells) {}

    private static final class Work {
        final int first;
        int[] indices;
        int repeats;

        Work(int first) {
            this.first = first;
        }

        void add(int index) {
            if (indices == null) indices = new int[8];
            else if (repeats == indices.length) indices = Arrays.copyOf(indices, repeats * 2);
            indices[repeats++] = index;
        }
    }

    /** If a provider prevents batching, visit repeated positions in their original global order. */
    private static final class Unbatched {
        final Work work;
        int cursor;

        Unbatched(Work work) {
            this.work = work;
        }

        int index() {
            return work.indices[cursor];
        }
    }

    private void unknown(String detail) {
        unaccounted++;
        demand.unaccounted(detail);
    }

    private static boolean sameInventory(ItemStack[] old, ItemStack[] current, int protectedSlot) {
        if (old == null || current == null) return old == current;
        if (old.length != current.length) return false;
        for (int i = 0; i < old.length; i++)
            if (i != protectedSlot && !ItemStack.areItemStacksEqual(old[i], current[i])) return false;
        return true;
    }

    private static ItemStack[] copyInventory(ItemStack[] inventory, int protectedSlot) {
        if (inventory == null) return null;
        ItemStack[] copy = new ItemStack[inventory.length];
        for (int i = 0; i < inventory.length; i++)
            if (i != protectedSlot && inventory[i] != null) copy[i] = inventory[i].copy();
        return copy;
    }

    @Desugar
    private record ItemIdentity(Item item, int damage, NBTTagCompound tag) {

        static ItemIdentity of(ItemStack stack) {
            return new ItemIdentity(stack.getItem(), stack.getItemDamage(), stack.getTagCompound());
        }
    }

    /** Detached exact counts only. An unavailable/disabled source is not measured as zero. */
    static NBTTagCompound stockRows(NBTTagCompound theory, ItemStack[] inventory, int protectedSlot) {
        return stockRows(theory, inventory, protectedSlot, HologramMaterialQuote::readItem);
    }

    /** Same exact local stock arithmetic for rows that already have a client-owned item identity. */
    static NBTTagCompound stockRows(NBTTagCompound theory, ItemStack[] inventory, int protectedSlot,
        Function<NBTTagCompound, ItemStack> rowIdentity) {
        NBTTagCompound visible = (NBTTagCompound) theory.copy();
        visible.removeTag("complete");
        visible.setBoolean("localEstimate", true);
        Map<ItemIdentity, Long> stock = new HashMap<>();
        if (inventory != null) for (int i = 0; i < inventory.length; i++) {
            ItemStack stack = inventory[i];
            if (i != protectedSlot && stack != null && stack.getItem() != null && stack.stackSize > 0)
                stock.merge(ItemIdentity.of(stack), (long) stack.stackSize, Long::sum);
        }
        NBTTagList rows = visible.getTagList("rows", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            row.setBoolean("availableKnown", false);
            if (inventory == null || !"item".equals(row.getString("kind"))) continue;
            ItemStack item = rowIdentity.apply(row);
            if (item == null) continue;
            long available = stock.getOrDefault(ItemIdentity.of(item), 0L);
            String required = HologramMaterialReview.amount(row, "required");
            row.setBoolean("availableKnown", true);
            row.setLong("available", available);
            row.setString(
                "missingExact",
                new BigInteger(required).subtract(BigInteger.valueOf(available))
                    .max(BigInteger.ZERO)
                    .toString());
        }
        return visible;
    }
}
