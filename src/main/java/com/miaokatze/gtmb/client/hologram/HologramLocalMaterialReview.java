// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.math.BigInteger;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramControllerMaterialCounts;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;

/** Reads capture-time material contributions; only detached inventory comparison occurs in this pane. */
final class HologramLocalMaterialReview {

    private final HologramState source;
    private final NBTTagCompound settings;
    private final int selected, scope, layer;
    private final int[] selection;
    private final HologramControllerMaterialCounts captured;
    private List<HologramLibraryMaterialDescription.Row> description;
    private int targetCount, unaccounted;
    private NBTTagCompound theory, visible = new NBTTagCompound();
    private ItemStack[] observed;
    private int observedSlot = -1;

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings) {
        this(source, settings, false);
    }

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings, boolean detailed) {
        this(source, settings, detailed, null);
    }

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings, boolean detailed,
        HologramSelection selectedMaterials) {
        this.source = source;
        this.settings = (NBTTagCompound) settings.copy();
        selected = settings.hasKey("selected", 3) ? settings.getInteger("selected") : -1;
        scope = settings.getInteger("scope");
        layer = settings.getInteger("layer");
        selection = this.settings.getIntArray("selectedIndices");
        captured = detailed && !source.data.getBoolean("capturePending") ? null : capturedCounts();
        if (captured != null) description = description(captured, null);
        else if (source.localMaterialIndex != null && source.localMaterialIndex.current()) {
            HologramLocalMaterialIndex.Result result = source.localMaterialIndex
                .select(source.cells, scope, layer, selected, selection,
                    selectedMaterials == null ? null : selectedMaterials.materialResult(source));
            if (result == null) return;
            theory = result.theory();
            targetCount = result.targets();
            unaccounted = theory.getInteger("unaccounted");
        }
    }

    private boolean closed;

    void close() {
        closed = true;
    }

    boolean current(HologramState state, int layer) {
        return !closed && (captured == null ? source.localMaterialIndex == state.localMaterialIndex
            && state.localMaterialIndex != null && state.localMaterialIndex.current() && !state.data.getBoolean("capturePending")
            : source.controllerMaterialCounts == state.controllerMaterialCounts) && source.session.equals(state.session)
            && source.data.getString("generation").equals(state.data.getString("generation"))
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
        return !closed && (captured != null || theory != null);
    }

    List<HologramLibraryMaterialDescription.Row> description() {
        return description;
    }

    NBTTagCompound visible() {
        return visible;
    }

    String scopeLabel() {
        if (captured != null) return scope == 2 ? "已选范围 · 原生目标基材" : "当前结构 · 原生目标基材";
        return scope == 2 ? "已选范围的目标材料" : "当前范围的目标材料";
    }

    String status() {
        if (captured != null) return captured.total() + " 个目标格"
            + (captured.unlisted() == 0 ? "" : " · " + captured.unlisted() + " 格基材未列明")
            + " · 施工用量未核算";
        if (!ready()) return "当前来源尚未提供材料汇总，请重新读取结构";
        String status = targetCount + " 个目标格";
        if (unaccounted > 0) status += " · " + unaccounted + " 格未核算";
        if (theory.getBoolean("alternatives") || theory.getBoolean("cacheReviewRequired")) status += " · 替代料或设备缓存待施工核验";
        return status;
    }

    boolean empty() {
        return ready() && (captured == null ? targetCount : captured.total()) == 0;
    }

    void tick(ItemStack[] inventory, int protectedSlot) {
        refresh(inventory, protectedSlot);
    }

    void refresh(ItemStack[] inventory, int protectedSlot) {
        if (closed) return;
        if (captured == null && theory == null) return;
        if (!settings.getBoolean("materialMain")) inventory = null;
        if ((captured != null || visible.hasKey("rows", 9)) && observedSlot == protectedSlot
            && sameInventory(observed, inventory, protectedSlot)) return;
        observedSlot = protectedSlot;
        observed = copyInventory(inventory, protectedSlot);
        if (captured != null) description = description(captured, stockItems(observed, protectedSlot));
        else visible = stockRows(theory, observed, protectedSlot);
    }

    private HologramControllerMaterialCounts capturedCounts() {
        if (source.controllerMaterialCounts == null) return null;
        if (scope == 0) return source.controllerMaterialCounts;
        // Single-cell inspection stays immediate. Wider scoped reviews use the existing exact path, never whole counts.
        if (scope != 2 || selection.length > 1) return null;
        int index = selection.length == 0 ? selected : selection[0];
        if (index < 0 || index >= source.cells.size()) return null;
        HologramControllerMaterialCounts.Builder one = new HologramControllerMaterialCounts.Builder();
        one.add(contribution(source, source.cells.get(index)));
        return one.seal();
    }

    static HologramControllerMaterialCounts.Contribution contribution(HologramState state, HologramState.Cell cell) {
        int x = state.data.getInteger("x"), y = state.data.getInteger("y"), z = state.data.getInteger("z");
        if (cell.excluded || !cell.inScope || cell.anchor || "minecraft:air".equals(cell.wantId)
            || cell.x == x && cell.y == y && cell.z == z
            || HologramControllerMaterialCounts.sourceWater(cell.capturedNativeTarget(), x, y, z))
            return HologramControllerMaterialCounts.EXCLUDED;
        return HologramControllerMaterialCounts.material(
            cell.targetStack,
            cell.missingBlock || cell.iconOnly || cell.block(false) == null);
    }

    @Desugar
    private record BackpackKey(Item item, int damage) {}

    private static Map<BackpackKey, Long> stockItems(ItemStack[] inventory, int protectedSlot) {
        if (inventory == null || protectedSlot < 0 || protectedSlot >= inventory.length) return null;
        Map<BackpackKey, Long> stock = new HashMap<>();
        try {
            for (int i = 0; i < inventory.length; i++) {
                ItemStack stack = inventory[i];
                if (i != protectedSlot && stack != null && stack.getItem() != null && stack.stackSize > 0
                    && !stack.hasTagCompound())
                    stock.merge(new BackpackKey(stack.getItem(), stack.getItemDamage()), (long) stack.stackSize, Long::sum);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            return null; // A failed stock observation is never an observed zero.
        }
        return stock;
    }

    private static List<HologramLibraryMaterialDescription.Row> description(HologramControllerMaterialCounts counts,
        Map<BackpackKey, Long> stock) {
        List<HologramControllerMaterialCounts.Row> rows = counts.rows();
        return new AbstractList<>() {

            @Override
            public HologramLibraryMaterialDescription.Row get(int index) {
                var row = rows.get(index);
                var identity = row.identity();
                Long available = stock == null ? null
                    : stock.getOrDefault(new BackpackKey(identity.item(), identity.damage()), 0L);
                return new HologramLibraryMaterialDescription.Row(
                    new HologramLibraryMaterialForecast.Identity(identity.registry(), identity.damage()),
                    row.cells(), available);
            }

            @Override
            public int size() {
                return rows.size();
            }
        };
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
