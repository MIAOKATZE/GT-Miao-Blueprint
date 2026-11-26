// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.util.LinkedHashMap;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation;

/** Full-target estimate from captured world observations. The server owns admission, recovery and net payment. */
final class HologramNativeTargetReview {

    final NBTTagCompound theory = new NBTTagCompound();
    final boolean removal;
    final String blocker = "";
    int exchanges, turns, unchanged, removals, unknown;

    private static final class Group {

        final ItemStack sample;
        long required, recovered;

        Group(ItemStack stack) {
            sample = stack.copy();
            sample.stackSize = 1;
        }
    }

    @Desugar
    private record Key(Item item, int damage, NBTTagCompound tag) {

        static Key of(ItemStack stack) {
            return new Key(
                stack.getItem(),
                stack.getItemDamage(),
                stack.hasTagCompound() ? (NBTTagCompound) stack.getTagCompound()
                    .copy() : null);
        }
    }

    HologramNativeTargetReview(HologramState source, NBTTagCompound settings) {
        removal = source.data.getInteger("nativeAction") == Operation.DEMOLISH.ordinal();
        var groups = new LinkedHashMap<Key, Group>();
        // An installed declaration can contain cells outside a newly resized desired target.
        HologramState observed = removal ? source.controllerView(1) : source;
        for (var cell : observed.cells) {
            if (cell.anchor || cell.excluded) continue;
            if (cell.localObservationMissing()) {
                unknown++;
                continue;
            }
            boolean occupied = cell.block(true) != null && cell.block(true) != Blocks.air;
            var old = cell.actualStack;
            if (removal) {
                if (!occupied) continue;
                removals++;
                if (old == null) unknown++;
                else group(groups, old).recovered++;
                continue;
            }
            var target = cell.targetStack;
            if (target == null) {
                if (occupied && "minecraft:air".equals(cell.wantId)) {
                    removals++;
                    if (old != null) group(groups, old).recovered++;
                } else if (!"minecraft:air".equals(cell.wantId)) unknown++;
                continue;
            }
            boolean same = old != null && old.isItemEqual(target) && ItemStack.areItemStackTagsEqual(old, target);
            if (same) {
                if (cell.targetFacing >= 0 && cell.actualFacing >= 0 && cell.targetFacing != cell.actualFacing) turns++;
                else unchanged++;
                continue;
            }
            exchanges++;
            group(groups, target).required++;
            if (occupied && old != null) group(groups, old).recovered++;
        }
        NBTTagList rows = new NBTTagList();
        for (var group : groups.values()) {
            var row = HologramMaterialQuote.itemRow(group.sample, group.required, 0);
            row.setString(
                "hatchChange",
                group.recovered > 0
                    ? (projectorOr("native.ui.recovery_pending_world_verification", "待现场核验回收：") + " ") + group.recovered
                    : projectorOr("native.ui.calculated_from_the_complete_target", "按当前完整目标核算"));
            rows.appendTag(row);
        }
        theory.setTag("rows", rows);
        theory.setInteger("unaccounted", unknown);
        theory.setBoolean("nativeTargetDelta", true);
    }

    private static Group group(LinkedHashMap<Key, Group> groups, ItemStack stack) {
        return groups.computeIfAbsent(Key.of(stack), unused -> new Group(stack));
    }

    String status() {
        String summary = removal
            ? (projectorOr("native.ui.whole_structure_demolition_preview", "整机拆除预览：") + " ") + removals
                + (" " + projectorOr("native.ui.world_blocks", "个现场方块。"))
            : (projectorOr("native.ui.complete_target_update", "完整目标：需更新") + " ") + exchanges
                + (" " + projectorOr("native.ui.cells_turn", "格，转向") + " ")
                + turns
                + (" " + projectorOr("native.ui.cells_leave", "格，相同") + " ")
                + unchanged
                + (" " + projectorOr("native.ui.matching_cells_unchanged", "格不动。"));
        if (unknown > 0)
            summary += " " + unknown + (" " + projectorOr("native.ui.cells_have_not_been_calculated", "格尚未核算。"));
        return summary
            + (" " + projectorOr("native.ui.the_server_verifies_payment_recovery_and_protection", "实际扣料、回收与保护由服务器核验。"));
    }
}
