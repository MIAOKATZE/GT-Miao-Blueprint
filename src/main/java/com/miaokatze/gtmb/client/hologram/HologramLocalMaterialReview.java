// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.math.BigInteger;
import java.util.HashMap;
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
    private final boolean sealedWholeTarget;
    final HologramNativeTargetReview hatchDelta;
    private int targetCount, unaccounted;
    private NBTTagCompound theory, visible = new NBTTagCompound();
    private ItemStack[] observed;
    private int observedSlot = -1;

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings) {
        this(source, settings, null);
    }

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings, HologramSelection selectedMaterials) {
        this(source, settings, selectedMaterials, false);
    }

    HologramLocalMaterialReview(HologramState source, NBTTagCompound settings, HologramSelection selectedMaterials,
        boolean sealedWholeTarget) {
        this.source = source;
        this.sealedWholeTarget = sealedWholeTarget;
        this.settings = (NBTTagCompound) settings.copy();
        selected = settings.hasKey("selected", 3) ? settings.getInteger("selected") : -1;
        scope = settings.getInteger("scope");
        layer = settings.getInteger("layer");
        selection = this.settings.getIntArray("selectedIndices");
        hatchDelta = source.data.getBoolean("localStandardController")
            ? new HologramNativeTargetReview(source, this.settings)
            : null;
        if (hatchDelta != null) {
            theory = hatchDelta.theory;
            targetCount = hatchDelta.exchanges + hatchDelta.turns + hatchDelta.removals;
            visible = stockRows(theory, null, -1);
            return;
        }
        if (source.nativeViewport != null) {
            var result = scope == 2 && selection.length > 1 && selectedMaterials != null
                ? selectedMaterials.materialResult(source)
                : source.nativeViewport.material(scope, layer, selected, selection);
            if (result != null) {
                theory = result.theory();
                targetCount = result.targets();
                unaccounted = theory.getInteger("unaccounted");
            }
        } else if (source.localMaterialIndex != null && source.localMaterialIndex.current()) {
            HologramLocalMaterialIndex.Result result = source.localMaterialIndex.select(
                source.cells,
                scope,
                layer,
                selected,
                selection,
                scope == 0 || selectedMaterials == null ? null : selectedMaterials.materialResult(source));
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
        if (hatchDelta != null) return !closed && source.cells == state.cells
            && source.session.equals(state.session)
            && source.data.getString("generation")
                .equals(state.data.getString("generation"))
            && !state.data.getBoolean("targetClosed")
            && source.data.getLong("nativeTargetRevision") == state.data.getLong("nativeTargetRevision")
            && source.data.getInteger("nativeAction") == state.data.getInteger("nativeAction")
            && (state.data.getBoolean("nativeTargetReady")
                || state.data.getInteger("nativeAction") == 3 && state.data.getLong("nativeObservationToken") > 0);
        if (source.nativeViewport != null)
            return !closed && source == state && this.layer == layer && source.nativeViewport.currentProviders();
        return !closed && source.localMaterialIndex == state.localMaterialIndex
            && state.localMaterialIndex != null
            && state.localMaterialIndex.current()
            && (sealedWholeTarget || !state.data.getBoolean("capturePending"))
            && sameScope(state, layer);
    }

    boolean wholeLedgerStillSelected(NBTTagCompound active) {
        return sealedWholeTarget && settings.equals(active);
    }

    private boolean sameScope(HologramState state, int layer) {
        return source.session.equals(state.session) && source.data.getString("generation")
            .equals(state.data.getString("generation"))
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
        return !closed && theory != null;
    }

    NBTTagCompound visible() {
        return visible;
    }

    String scopeLabel() {
        return scope == 2 ? projectorOr("native.ui.target_materials_in_the_selected_scope", "已选范围的目标材料")
            : projectorOr("native.ui.target_materials_in_the_current_scope", "当前范围的目标材料");
    }

    String status() {
        if (hatchDelta != null) return hatchDelta.status();
        if (!ready() && source.nativeViewport != null)
            return projectorOr("native.ui.this_scope_is_not_calculated_it_cannot", "此范围尚未核算，不能作为零用量");
        if (!ready())
            return projectorOr("native.ui.this_source_has_not_supplied_material_totals", "当前来源尚未提供材料汇总，请重新读取结构");
        String status = targetCount + (" " + projectorOr("native.ui.target_cells", "个目标格"));
        if (unaccounted > 0)
            status += " · " + unaccounted + (" " + projectorOr("native.ui.uncalculated_cells", "格未核算"));
        if (theory.getBoolean("alternatives") || theory.getBoolean("cacheReviewRequired")) status += (" " + projectorOr(
            "native.ui.alternatives_or_device_caches_require_construction_verification",
            "· 替代料或设备缓存待施工核验"));
        return status;
    }

    boolean empty() {
        return ready() && targetCount == 0;
    }

    void tick(ItemStack[] inventory, int protectedSlot) {
        refresh(inventory, protectedSlot);
    }

    void refresh(ItemStack[] inventory, int protectedSlot) {
        if (closed || theory == null) return;
        if (!settings.getBoolean("materialMain")) inventory = null;
        if (visible.hasKey("rows", 9) && observedSlot == protectedSlot
            && sameInventory(observed, inventory, protectedSlot)) return;
        observedSlot = protectedSlot;
        observed = copyInventory(inventory, protectedSlot);
        visible = stockRows(theory, observed, protectedSlot);
    }

    static HologramControllerMaterialCounts.Contribution contribution(HologramState state, HologramState.Cell cell) {
        int x = state.data.getInteger("x"), y = state.data.getInteger("y"), z = state.data.getInteger("z");
        if (cell.excluded || !cell.inScope
            || cell.anchor
            || "minecraft:air".equals(cell.wantId)
            || cell.x == x && cell.y == y && cell.z == z
            || HologramControllerMaterialCounts.sourceWater(cell.capturedNativeTarget(), x, y, z))
            return HologramControllerMaterialCounts.EXCLUDED;
        return HologramControllerMaterialCounts
            .material(cell.targetStack, cell.missingBlock || cell.iconOnly || cell.block(false) == null);
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
