// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.hologram.HologramStaticMaterials;

/** Main-thread estimate for a frozen local controller projection. No material source or network access. */
final class HologramLocalMaterialReview {

    private static final int CELLS_PER_TICK = 128;
    private static final long SLICE_NANOS = 2_000_000L;

    private final HologramState source;
    private final NBTTagCompound settings;
    private final int selected, scope, x, y, z;
    private final Set<Integer> selection = new HashSet<>();
    private final String family;
    private HologramStaticMaterials.Builder demand = new HologramStaticMaterials.Builder();
    private int cursor, targetCount, unaccounted;
    private NBTTagCompound theory, visible = new NBTTagCompound();
    private ItemStack[] observed;
    private int observedSlot = -1;

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings) {
        this.source = source;
        this.settings = (NBTTagCompound) settings.copy();
        selected = settings.hasKey("selected", 3) ? settings.getInteger("selected") : -1;
        scope = settings.getInteger("scope");
        x = source.data.getInteger("x");
        y = source.data.getInteger("y");
        z = source.data.getInteger("z");
        for (int index : settings.getIntArray("selectedIndices")) selection.add(index);
        family = selected >= 0 && selected < source.cells.size() ? source.cells.get(selected).family : "";
    }

    boolean current(HologramState state, int layer) {
        return source.cells == state.cells && source.session.equals(state.session)
            && source.data.getLong("planRevision") == state.data.getLong("planRevision")
            && source.data.getInteger("mode") == state.data.getInteger("mode")
            && source.data.getString("activeStructure").equals(state.data.getString("activeStructure"))
            && !state.data.getBoolean("localPlanPending") && !state.data.getBoolean("targetClosed")
            && settings.getInteger("layer") == layer;
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
        if (!ready()) return "正在本地统计目标材料 " + cursor + " / " + source.cells.size();
        String status = targetCount + " 个目标格";
        if (unaccounted > 0) status += " · " + unaccounted + " 格未核算";
        if (theory.getBoolean("alternatives") || theory.getBoolean("cacheReviewRequired"))
            status += " · 替代料或设备缓存待施工核验";
        return status;
    }

    boolean empty() {
        return ready() && targetCount == 0;
    }

    void tick(ItemStack[] inventory, int protectedSlot) {
        if (theory == null) {
            long deadline = System.nanoTime() + SLICE_NANOS;
            int stepped = 0;
            while (cursor < source.cells.size() && stepped < CELLS_PER_TICK
                && (stepped == 0 || System.nanoTime() < deadline)) {
                collect(source.cells.get(cursor), cursor);
                cursor++;
                stepped++;
            }
            if (cursor < source.cells.size()) return;
            theory = demand.snapshot();
            demand = null;
        }
        refresh(inventory, protectedSlot);
    }

    void refresh(ItemStack[] inventory, int protectedSlot) {
        if (theory == null) return;
        if (!settings.getBoolean("materialMain")) inventory = null;
        if (visible.hasKey("rows", 9) && observedSlot == protectedSlot && sameInventory(observed, inventory, protectedSlot))
            return;
        observedSlot = protectedSlot;
        observed = copyInventory(inventory, protectedSlot);
        visible = stockRows(theory, observed, protectedSlot);
    }

    private boolean includes(HologramState.Cell cell, int index) {
        if (cell.excluded || !cell.inScope) return false;
        return switch (scope) {
            case 0 -> true;
            case 1 -> cell.dy == settings.getInteger("layer");
            case 2 -> selection.isEmpty() ? selected == index : selection.contains(index);
            case 3 -> !family.isEmpty() && family.equals(cell.family);
            default -> false;
        };
    }

    private void collect(HologramState.Cell cell, int index) {
        if (!includes(cell, index) || cell.anchor || "minecraft:air".equals(cell.wantId)) return;
        if (cell.x == x && cell.y == y && cell.z == z) return;
        HologramControllerTarget.Cell nativeTarget = cell.capturedNativeTarget();
        if (nativeTarget != null && nativeTarget.kind() == HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER
            && nativeTarget.x() == cell.x && nativeTarget.y() == cell.y && nativeTarget.z() == cell.z
            && cell.block(false) == Blocks.water && cell.wantMeta == 0
            && cell.x - x == cell.dx && cell.y - y == cell.dy && cell.z - z == cell.dz) return;
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
            ItemStack item = HologramMaterialQuote.readItem(row);
            if (item == null) continue;
            long available = stock.getOrDefault(ItemIdentity.of(item), 0L);
            String required = HologramMaterialReview.amount(row, "required");
            row.setBoolean("availableKnown", true);
            row.setLong("available", available);
            row.setString("missingExact", new BigInteger(required).subtract(BigInteger.valueOf(available))
                .max(BigInteger.ZERO).toString());
        }
        return visible;
    }
}
