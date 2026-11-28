// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramNativePresetIndex;
import com.miaokatze.gtmb.hologram.HologramPreviewHatchFacings;

import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Local controller planning edits expressed as sparse changes to the existing projection. */
final class HologramLocalControllerEdits {

    private HologramLocalControllerEdits() {}

    static boolean handles(String operation) {
        return switch (operation) {
            case "pin", "sourcePin", "customPin", "restoreCell", "clearPin", "hatchFacing" -> true;
            default -> false;
        };
    }

    static HologramState apply(HologramState base, HologramState initial, String operation, NBTTagCompound request)
        throws IOException {
        return apply(base, initial, operation, request, null);
    }

    static HologramState apply(HologramState base, HologramState initial, String operation, NBTTagCompound request,
        java.util.function.Consumer<HologramState.Cell> onEdit) throws IOException {
        return apply(base, initial, operation, request, cell -> cell, onEdit);
    }

    @FunctionalInterface
    interface DeclarationDetails {

        HologramState.Cell read(HologramState.Cell cell) throws IOException;
    }

    static HologramState apply(HologramState base, HologramState initial, String operation, NBTTagCompound request,
        DeclarationDetails details, java.util.function.Consumer<HologramState.Cell> onEdit) throws IOException {
        if (initial == null || base.cells.size() != initial.cells.size() || base.data.getBoolean("capturePending"))
            throw new IOException(projectorOr("native.ui.wait_for_local_structure_capture_to_finish", "请先等待本地结构采集完成"));
        int primary = request.hasKey("selected", 3) ? request.getInteger("selected") : -1;
        boolean restore = "restoreCell".equals(operation) || "clearPin".equals(operation);
        Set<Integer> indices = new LinkedHashSet<>();
        if (primary < 0 || primary >= base.cells.size())
            throw new IOException(projectorOr("native.ui.select_a_structure_cell_first", "请先选中结构格"));
        indices.add(primary);
        if (!"hatchFacing".equals(operation) && !"pin".equals(operation)) {
            for (int index : request.getIntArray("selectedIndices"))
                if (index >= 0 && index < base.cells.size()) indices.add(index);
        }
        ItemStack picked = ItemStack.loadItemStackFromNBT(request.getCompoundTag("expectedSample"));
        NBTTagList rows = new NBTTagList();
        for (int index : indices) {
            HologramState.Cell current = base.cells.get(index);
            if (current.anchor) continue;
            HologramState.Cell captured = initial.cells.get(index);
            if (!restore) current = details.read(current);
            NBTTagCompound row = current.tag(), original = captured.tag();
            if (restore) {
                row = original;
            } else if ("hatchFacing".equals(operation)) {
                int facing = request.getInteger("facing");
                if (!request.hasKey("facing", 3) || facing < -1
                    || facing > 5
                    || current.targetStack == null
                    || !(current.targetStack.getItem() instanceof ItemMachines)
                    || facing >= 0 && !allowsFacing(row, facing))
                    throw new IOException(
                        projectorOr("native.ui.this_structure_cell_does_not_support_the", "该结构格不支持所选方向"));
                setFacing(row, original, current.targetStack, facing);
            } else if ("pin".equals(operation)) {
                int choice = request.hasKey("choiceindex", 3) ? request.getInteger("choiceindex")
                    : request.getInteger("choice");
                if (choice < 0 || choice >= current.candidates.size())
                    throw new IOException(projectorOr("native.ui.block_candidates_changed", "方块候选已变化"));
                pin(row, original, current.candidates.get(choice), choice, "manual");
            } else {
                pin(row, original, picked, -1, "manual");
            }
            row.setInteger("index", index);
            rows.appendTag(row);
        }
        NBTTagCompound patch = new NBTTagCompound();
        patch.setBoolean("partial", true);
        patch.setString("session", base.session);
        patch.setString("generation", base.data.getString("generation"));
        patch.setInteger("cellCount", base.cells.size());
        patch.setLong("baseRevision", base.data.getLong("planRevision"));
        patch.setLong("planRevision", base.data.getLong("planRevision") + 1);
        patch.setTag("cells", rows);
        patch.setBoolean("nativeBuildSatisfied", false);
        patch.setString("status", projectorOr("native.ui.local_structure_plan_updated", "已更新本地结构规划"));
        HologramState result = HologramState.apply(base, patch);
        if (onEdit != null) for (int i = 0; i < rows.tagCount(); i++) onEdit.accept(
            result.cells.get(
                rows.getCompoundTagAt(i)
                    .getInteger("index")));
        return result;
    }

    static boolean selectedOnly(String operation) {
        return "pin".equals(operation) || "hatchFacing".equals(operation);
    }

    /** The existing native choice protocol owns both model and optional facing. */
    static NBTTagCompound nativeChoice(HologramState.Cell cell) throws IOException {
        ItemStack stack = cell.targetStack;
        boolean installedState = stack != null && cell.actualStack != null
            && stack.isItemEqual(cell.actualStack)
            && ItemStack.areItemStackTagsEqual(stack, cell.actualStack);
        if (stack == null || !(stack.getItem() instanceof ItemMachines) || stack.hasTagCompound() && !installedState)
            throw new IOException(
                projectorOr("native.ui.choose_this_target_through_structure_channels_it", "此目标请通过结构信道选择，不能作为仓室型号提交。"));
        NBTTagCompound choice = new NBTTagCompound();
        choice.setInteger("x", cell.dx);
        choice.setInteger("y", cell.dy);
        choice.setInteger("z", cell.dz);
        choice.setInteger("id", stack.getItemDamage());
        choice.setInteger("facing", cell.hatchFacing);
        return choice;
    }

    /** Reattach intent only for this exact replayed model; target display/NBT remain the native result. */
    static NBTTagCompound nativeFacingRow(HologramState.Cell cell, NBTTagList choices) {
        NBTTagCompound row = cell.tag();
        row.setInteger("hatchFacing", -1);
        row.setBoolean("hasFacingPin", false);
        if (cell.targetStack == null || !(cell.targetStack.getItem() instanceof ItemMachines)) return row;
        for (int i = 0; i < choices.tagCount(); i++) {
            NBTTagCompound choice = choices.getCompoundTagAt(i);
            if (choice.getInteger("x") != cell.dx || choice.getInteger("y") != cell.dy
                || choice.getInteger("z") != cell.dz
                || choice.getInteger("id") != cell.targetStack.getItemDamage()) continue;
            int facing = choice.hasKey("facing", 3) ? choice.getInteger("facing") : -1;
            if (facing >= 0 && facing < 6) {
                row.setInteger("hatchFacing", facing);
                row.setBoolean("hasFacingPin", true);
            }
            break;
        }
        return row;
    }

    /** One already validated local candidate row; never accepts a file/inventory sample or broad scope. */
    static NBTTagCompound selectedRow(HologramState.Cell current, HologramState.Cell initial, String operation,
        NBTTagCompound request) throws IOException {
        if (!selectedOnly(operation) || current.anchor
            || initial.index != current.index
            || !request.hasKey("selected", 3)
            || request.getInteger("selected") != current.index)
            throw new IOException(projectorOr("native.ui.the_selected_structure_cell_changed", "所选结构格已变化"));
        NBTTagCompound row = current.tag(), original = initial.tag();
        if ("pin".equals(operation)) {
            if (!request.hasKey("choiceindex", 3) && !request.hasKey("choice", 3))
                throw new IOException(projectorOr("native.ui.block_candidates_changed", "方块候选已变化"));
            int choice = request.hasKey("choiceindex", 3) ? request.getInteger("choiceindex")
                : request.getInteger("choice");
            if (choice < 0 || choice >= current.candidates.size())
                throw new IOException(projectorOr("native.ui.block_candidates_changed", "方块候选已变化"));
            pin(row, original, current.candidates.get(choice), choice, "manual");
        } else {
            int facing = request.getInteger("facing");
            if (!request.hasKey("facing", 3) || facing < -1
                || facing > 5
                || current.targetStack == null
                || !(current.targetStack.getItem() instanceof ItemMachines)
                || facing >= 0 && !allowsFacing(row, facing))
                throw new IOException(projectorOr("native.ui.this_structure_cell_does_not_support_the", "该结构格不支持所选方向"));
            setFacing(row, original, current.targetStack, facing);
        }
        row.setInteger("index", current.index);
        return row;
    }

    static HologramState applySelectedRows(HologramState base, NBTTagList rows, long revision) {
        if (rows.tagCount() == 0) return base;
        NBTTagCompound patch = new NBTTagCompound();
        patch.setBoolean("partial", true);
        patch.setString("session", base.session);
        patch.setString("generation", base.data.getString("generation"));
        patch.setInteger("cellCount", base.cells.size());
        patch.setLong("baseRevision", base.data.getLong("planRevision"));
        patch.setLong("planRevision", revision);
        patch.setTag("cells", rows);
        return HologramState.apply(base, patch);
    }

    static HologramState replaceNativePreset(HologramState base, HologramState initial, Set<Integer> previous,
        java.util.List<HologramNativePresetIndex.Choice> choices, int tier, boolean enabled) throws IOException {
        if (initial == null || !initial.session.equals(base.session) || initial.cells.size() != base.cells.size())
            throw new IOException(projectorOr("native.ui.the_structure_target_changed", "结构目标已变化"));
        java.util.Map<Integer, NBTTagCompound> changed = new java.util.LinkedHashMap<>();
        for (int index : previous) {
            HologramState.Cell current = base.cells.get(index);
            if (!"preset".equals(current.pinScope)) continue;
            NBTTagCompound row = initial.cells.get(index)
                .tag();
            row.setInteger("index", index);
            changed.put(index, row);
        }
        for (var pick : choices) {
            int i = pick.index(), id = pick.itemId(), direction = pick.facing();
            if (i < 0 || i >= base.cells.size()
                || id < 0
                || id >= GregTechAPI.METATILEENTITIES.length
                || direction < -1
                || direction > 5
                || GregTechAPI.METATILEENTITIES[id] == null)
                throw new IOException(projectorOr("native.ui.invalid_hatch_target", "仓室目标无效"));
            var current = base.cells.get(i);
            if (current.anchor || "manual".equals(current.pinScope)) throw new IOException(
                projectorOr("native.ui.hatch_target_conflicts_with_a_manual_position", "仓室目标与手选位置冲突"));
            NBTTagCompound original = initial.cells.get(i)
                .tag(), row = (NBTTagCompound) original.copy();
            ItemStack stack = GregTechAPI.METATILEENTITIES[id].getStackForm(1);
            if (stack == null || !(stack.getItem() instanceof ItemMachines)
                || stack.hasTagCompound()
                || stack.getItemDamage() != id)
                throw new IOException(projectorOr("native.ui.hatch_identity_changed", "仓室身份已变化"));
            pin(row, original, stack, -1, "preset");
            if (direction >= 0) row.setIntArray("hatchFacings", new int[] { direction });
            setFacing(row, original, stack, direction);
            row.setInteger("index", i);
            changed.put(i, row);
        }
        NBTTagList rows = new NBTTagList();
        for (NBTTagCompound row : changed.values()) rows.appendTag(row);
        HologramState result = applySelectedRows(base, rows, base.data.getLong("planRevision") + 1);
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setBoolean("presetActive", enabled);
        metadata.setBoolean("nativePresetExact", enabled);
        metadata.setBoolean("nativeBuildSatisfied", false);
        metadata.setInteger("hatchTier", enabled ? tier : -1);
        return result.withMetadata(metadata);
    }

    private static void pin(NBTTagCompound row, NBTTagCompound original, ItemStack sample, int choice, String scope)
        throws IOException {
        if (sample == null || sample.getItem() == null)
            throw new IOException(projectorOr("native.ui.the_sample_is_no_longer_available", "样本已不可用"));
        Block block = sample.getItem() instanceof ItemMachines ? GregTechAPI.sBlockMachines
            : Block.getBlockFromItem(sample.getItem());
        if (block == null || block == Blocks.air)
            throw new IOException(projectorOr("native.ui.the_inventory_sample_is_not_a_placeable", "库存样本不是可放置方块"));
        boolean unchanged = sameTarget(row, sample);
        ItemStack target = sample.copy();
        target.stackSize = 1;
        row.setTag("targetStack", target.writeToNBT(new NBTTagCompound()));
        row.setString("wantId", Block.blockRegistry.getNameForObject(block));
        row.setInteger("wantMeta", sample.getItem() instanceof ItemMachines ? 0 : sample.getItemDamage() & 15);
        row.setString("renderKind", sample.getItem() instanceof ItemMachines ? "item" : "cube");
        row.setInteger("chosenChoice", choice);
        row.setString("pinScope", scope);
        row.setBoolean("iconOnly", false);
        row.setBoolean("missingBlock", false);
        if (!unchanged) {
            int[] legal = sameTarget(original, target) ? original.getIntArray("hatchFacings")
                : HologramPreviewHatchFacings.forCandidate(row, target);
            if (legal.length == 0) row.removeTag("hatchFacings");
            else row.setIntArray("hatchFacings", legal);
            setFacing(row, original, target, -1);
        }
    }

    private static boolean sameTarget(NBTTagCompound row, ItemStack target) {
        ItemStack previous = ItemStack.loadItemStackFromNBT(row.getCompoundTag("targetStack"));
        return previous != null && previous.isItemEqual(target) && ItemStack.areItemStackTagsEqual(previous, target);
    }

    private static boolean allowsFacing(NBTTagCompound row, int facing) {
        return Arrays.stream(row.getIntArray("hatchFacings"))
            .anyMatch(direction -> direction == facing);
    }

    private static void setFacing(NBTTagCompound row, NBTTagCompound original, ItemStack target, int override) {
        boolean originalTarget = sameTarget(original, target);
        row.setInteger("hatchFacing", override);
        row.setBoolean("hasFacingPin", override >= 0);
        if (override < 0) {
            row.setInteger("targetFacing", originalTarget ? original.getInteger("targetFacing") : -1);
            if (originalTarget && original.hasKey("targetDisplay", 10)) row.setTag(
                "targetDisplay",
                original.getCompoundTag("targetDisplay")
                    .copy());
            else row.removeTag("targetDisplay");
        } else {
            row.setInteger("targetFacing", override);
            NBTTagCompound display = row.getCompoundTag("targetDisplay");
            display.setInteger("facing", override);
            row.setTag("targetDisplay", display);
        }
    }
}
