// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramPreviewHatchFacings;

import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Local controller planning edits expressed as sparse changes to the existing projection. */
final class HologramLocalControllerEdits {

    private HologramLocalControllerEdits() {}

    static boolean handles(String operation) {
        return switch (operation) {
            case "pin", "sourcePin", "customPin", "restoreCell", "clearPin", "hatchFacing", "hatchPreset",
                "clearHatchPreset" -> true;
            default -> false;
        };
    }

    static HologramState apply(HologramState base, HologramState initial, String operation, NBTTagCompound request)
        throws IOException {
        if (initial == null || base.cells.size() != initial.cells.size() || base.data.getBoolean("capturePending"))
            throw new IOException("请先等待本地结构采集完成");
        if (base.data.getInteger("mode") == 2) throw new IOException("拆除模式不接受建造目标");
        int primary = request.hasKey("selected", 3) ? request.getInteger("selected") : -1;
        boolean tierPreset = "hatchPreset".equals(operation), clearPreset = "clearHatchPreset".equals(operation);
        boolean restore = "restoreCell".equals(operation) || "clearPin".equals(operation);
        Set<Integer> indices = new LinkedHashSet<>();
        if (tierPreset || clearPreset) {
            for (HologramState.Cell cell : base.cells)
                if (!cell.anchor && !cell.candidates.isEmpty()) indices.add(cell.index);
        } else {
            if (primary < 0 || primary >= base.cells.size()) throw new IOException("请先选中结构格");
            indices.add(primary);
            if (!"hatchFacing".equals(operation) && !"pin".equals(operation))
                for (int index : request.getIntArray("selectedIndices"))
                    if (index >= 0 && index < base.cells.size()) indices.add(index);
        }
        ItemStack picked = ItemStack.loadItemStackFromNBT(request.getCompoundTag("expectedSample"));
        NBTTagList rows = new NBTTagList();
        for (int index : indices) {
            HologramState.Cell current = base.cells.get(index);
            if (current.anchor) continue;
            NBTTagCompound row = current.tag(), original = initial.cells.get(index).tag();
            if (restore || clearPreset && "preset".equals(row.getString("pinScope"))) {
                row = original;
            } else if ("hatchFacing".equals(operation)) {
                int facing = request.getInteger("facing");
                if (!request.hasKey("facing", 3) || facing < -1 || facing > 5 || current.targetStack == null
                    || !(current.targetStack.getItem() instanceof ItemMachines)
                    || facing >= 0 && !allowsFacing(row, facing))
                    throw new IOException("该结构格不支持所选方向");
                setFacing(row, original, current.targetStack, facing);
            } else if (tierPreset) {
                if ("manual".equals(row.getString("pinScope"))) continue;
                int[] tiers = row.getIntArray("candidateTiers");
                int tier = request.getInteger("tier"), choice = -1;
                int offered = nativeCandidateCount(row, current.candidates.size());
                for (int i = 0; i < tiers.length && i < offered; i++) {
                    if (tiers[i] == tier || request.getBoolean("downgrade") && tiers[i] >= 0 && tiers[i] < tier
                        && (choice == -1 || tiers[i] > tiers[choice])) choice = i;
                    if (tiers[i] == tier) break;
                }
                if (choice < 0) continue;
                pin(row, original, current.candidates.get(choice), choice, "preset");
            } else if ("pin".equals(operation)) {
                int choice = request.hasKey("choiceindex", 3) ? request.getInteger("choiceindex")
                    : request.getInteger("choice");
                if (choice < 0 || choice >= current.candidates.size()) throw new IOException("方块候选已变化");
                pin(row, original, current.candidates.get(choice), choice, "manual");
            } else if (!clearPreset) {
                pin(row, original, picked, -1, "manual");
            } else continue;
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
        if (tierPreset || clearPreset) {
            patch.setBoolean("presetActive", tierPreset);
            if (tierPreset) patch.setInteger("hatchTier", request.getInteger("tier"));
        }
        patch.setString("status", "已更新本地结构规划");
        return HologramState.apply(base, patch);
    }

    private static int nativeCandidateCount(NBTTagCompound row, int size) {
        if (!row.hasKey("nativeCandidateCount")) return size;
        if (!row.hasKey("nativeCandidateCount", 3) || row.getTagList("candidates", 10).tagCount() != size) return 0;
        int count = row.getInteger("nativeCandidateCount");
        return count >= 0 && count <= size ? count : 0;
    }

    private static void pin(NBTTagCompound row, NBTTagCompound original, ItemStack sample, int choice, String scope)
        throws IOException {
        if (sample == null || sample.getItem() == null) throw new IOException("样本已不可用");
        Block block = sample.getItem() instanceof ItemMachines ? GregTechAPI.sBlockMachines
            : Block.getBlockFromItem(sample.getItem());
        if (block == null || block == Blocks.air) throw new IOException("库存样本不是可放置方块");
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
        return Arrays.stream(row.getIntArray("hatchFacings")).anyMatch(direction -> direction == facing);
    }

    private static void setFacing(NBTTagCompound row, NBTTagCompound original, ItemStack target, int override) {
        boolean originalTarget = sameTarget(original, target);
        row.setInteger("hatchFacing", override);
        row.setBoolean("hasFacingPin", override >= 0);
        if (override < 0) {
            row.setInteger("targetFacing", originalTarget ? original.getInteger("targetFacing") : -1);
            if (originalTarget && original.hasKey("targetDisplay", 10))
                row.setTag("targetDisplay", original.getCompoundTag("targetDisplay").copy());
            else row.removeTag("targetDisplay");
        } else {
            row.setInteger("targetFacing", override);
            NBTTagCompound display = row.getCompoundTag("targetDisplay");
            display.setInteger("facing", override);
            row.setTag("targetDisplay", display);
        }
    }
}
