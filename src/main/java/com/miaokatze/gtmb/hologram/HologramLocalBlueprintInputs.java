// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.common.blocks.ItemMachines;

/** Creates editable local documents from the workspace's own blueprint or controller input. */
public final class HologramLocalBlueprintInputs {

    private HologramLocalBlueprintInputs() {}

    public static HologramLocalBlueprintDocument empty() {
        HologramPresets.Preset draft = new HologramPresets.Preset();
        draft.name = "未命名蓝图";
        draft.controllerType = "area";
        return HologramLocalBlueprintDocument.fromCapture(draft);
    }

    public static HologramLocalBlueprintDocument controller(NBTTagCompound source) throws IOException {
        HologramPresets.Preset draft = new HologramPresets.Preset();
        draft.name = source.getString("title");
        draft.controllerType = "area";
        if (source.hasKey("facing", 3)) {
            int facing = source.getInteger("facing");
            if (facing >= 0 && facing < ExtendedFacing.values().length) draft.controllerFacing = facing;
        }
        int x = source.getInteger("x"), y = source.getInteger("y"), z = source.getInteger("z");
        String id = x + "," + y + "," + z;
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.id = id;
        structure.name = draft.name;
        structure.sourceClass = emptyAsNull(source.getString("sourceClass"));
        structure.sourceMod = emptyAsNull(source.getString("sourceMod"));
        structure.main = Math.max(1, source.getInteger("main"));
        structure.mainExplicit = source.getBoolean("mainExplicit");
        structure.noHatches = source.getBoolean("noHatches");
        NBTTagCompound channels = source.getCompoundTag("channels");
        for (Object key : channels.func_150296_c()) {
            String channel = String.valueOf(key);
            structure.channels.put(channel, channels.getInteger(channel));
        }
        NBTTagList rows = source.getTagList("cells", 10);
        HologramBlueprintLimits.require(rows.tagCount());
        Map<String, HologramPresets.Cell> palette = new LinkedHashMap<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            ItemStack material = ItemStack.loadItemStackFromNBT(row.getCompoundTag("targetStack"));
            if (material == null && row.getString("wantId").isEmpty()) continue;
            HologramPresets.Cell cell = material == null ? new HologramPresets.Cell()
                : HologramPresets.cell(material, row.getInteger("dx"), row.getInteger("dy"), row.getInteger("dz"), -1, false);
            cell.x = row.getInteger("dx");
            cell.y = row.getInteger("dy");
            cell.z = row.getInteger("dz");
            cell.block = emptyAsNull(row.getString("wantId"));
            cell.metadata = row.getInteger("wantMeta");
            cell.facing = row.hasKey("targetFacing", 3) ? row.getInteger("targetFacing") : -1;
            cell.controller = row.getBoolean("anchor");
            cell.excluded = row.getBoolean("excluded") || row.getBoolean("iconOnly") || row.getBoolean("missingBlock");
            cell.structureRole = emptyAsNull(row.getString("role"));
            cell.structureFamily = emptyAsNull(row.getString("family"));
            cell.structureHint = emptyAsNull(row.getString("prompt"));
            if (row.hasKey("candidateComplete", 1))
                cell.structureCandidatesComplete = row.getBoolean("candidateComplete");
            if (row.hasKey("hatchFacings", 11)) cell.structureHatchFacings = row.getIntArray("hatchFacings");
            cell.structureId = id;
            if (cell.controller && material != null && material.getItem() instanceof ItemMachines)
                structure.controllerId = material.getItemDamage();
            NBTTagCompound display = row.getCompoundTag("targetDisplay");
            Block block = cell.block();
            if (block != null && display.hasKey("nativeTile", 10))
                cell.tileNbt = HologramBlueprintNbt.encodeCapturedWithSize(block, display.getCompoundTag("nativeTile")).value;
            if (block != null && material != null && material.hasTagCompound())
                cell.itemNbt = HologramBlueprintNbt.encodeCapturedWithSize(block, material.getTagCompound()).value;
            NBTTagList candidates = row.getTagList("candidates", 10);
            List<ItemStack> allowed = new ArrayList<>(candidates.tagCount());
            for (int c = 0; c < candidates.tagCount(); c++) {
                ItemStack candidate = ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(c));
                if (candidate != null) allowed.add(candidate);
            }
            if (!allowed.isEmpty()) cell.allowedTargets = HologramBlueprintData.savedTargets(allowed);
            draft.cells.add(cell);
            if (material != null) palette.computeIfAbsent(
                cell.item + ":" + cell.damage,
                ignored -> HologramPresets.cell(material, 0, 0, 0, -1, false));
        }
        draft.palette.addAll(palette.values());
        draft.structures.add(structure);
        return HologramLocalBlueprintDocument.fromCapture(draft, source.getInteger("dimension"), x, y, z);
    }

    private static String emptyAsNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
