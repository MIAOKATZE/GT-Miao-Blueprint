// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Explicit native review choices, validated against the fresh capture before any planning or execution. */
public final class HologramNativeOperationChoices {

    public static final String KEY = "nativeHatchChoices";
    public static final int LIMIT = 64;
    private static final String FAMILY_SCOPE = "手选仓室不能使用同族范围，请选择具体位置或整个结构。";
    private final Map<String, Choice> requested = new LinkedHashMap<>();
    private final Map<Integer, Applied> staged = new LinkedHashMap<>();
    private String problem;

    private static final class Choice {
        final int meta, facing;

        Choice(int meta, int facing) {
            this.meta = meta;
            this.facing = facing;
        }
    }

    private static final class Applied {
        final HologramCapture.Cell cell;
        final ItemStack item;
        final int facing;

        Applied(HologramCapture.Cell cell, ItemStack item, int facing) {
            this.cell = cell;
            this.item = item;
            this.facing = facing;
        }
    }

    public static void requirePositionScope(int scope) throws IOException {
        if (scope == 3) throw new IOException(FAMILY_SCOPE);
    }

    static HologramNativeOperationChoices read(HologramSession s, NBTTagCompound request) throws IOException {
        HologramNativeOperationChoices choices = new HologramNativeOperationChoices();
        if (!request.hasKey(KEY)) return choices;
        if (!request.hasKey(KEY, 9)) throw new IOException("原生仓室选择格式无效。");
        NBTTagList rows = (NBTTagList) request.getTag(KEY);
        if (rows.tagCount() > LIMIT || rows.tagCount() == 0 || rows.func_150303_d() != 10
            || request.getInteger("mode") == 2 || request.getBoolean("noHatches"))
            throw new IOException("此模式、设置或数量不支持手选仓室。");
        requirePositionScope(request.getInteger("scope"));
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.func_150296_c().size() != 3 || !row.hasKey("xyz", 11) || !row.hasKey("hatch", 3)
                || !row.hasKey("facing", 3)) throw new IOException("原生仓室选择格式无效。");
            int[] at = row.getIntArray("xyz");
            int meta = row.getInteger("hatch"), facing = row.getInteger("facing");
            if (at.length != 3 || !HologramWorldFlow.worldPosition(at[0], at[1], at[2], s.libraryWorld.getHeight())
                || meta < 0 || meta >= GregTechAPI.METATILEENTITIES.length
                || !(GregTechAPI.METATILEENTITIES[meta] instanceof MTEHatch) || facing < -1 || facing > 5)
                throw new IOException("原生仓室位置、类型或朝向无效。");
            String key = at[0] + "," + at[1] + "," + at[2];
            if (choices.requested.putIfAbsent(key, new Choice(meta, facing)) != null)
                throw new IOException("原生仓室位置重复。");
        }
        return choices;
    }

    boolean hasHatches() {
        return !requested.isEmpty();
    }

    void inspect(HologramSession s, HologramCapture.Cell cell, int index, String position) {
        Choice desired = requested.get(position);
        if (desired == null || problem != null) return;
        ItemStack item = new ItemStack(GregTechAPI.sBlockMachines, 1, desired.meta);
        if (cell.anchor || cell.userPosition || cell.element == null || item.getItem() == null
            || !(item.getItem() instanceof ItemMachines) || !HologramCellPolicy.acceptableSample(s, cell, item)
            || HologramCellPolicy.hatchElement(s, cell, item, true) == null) {
            problem = "手选仓室不符合当前原生结构位置。";
            return;
        }
        if (desired.facing >= 0) {
            boolean legal = false;
            for (int side : HologramCellPolicy.legalFacings(s, cell, item)) legal |= side == desired.facing;
            if (!legal) {
                problem = "手选仓室朝向不符合当前原生结构。";
                return;
            }
        }
        staged.put(index, new Applied(cell, item, desired.facing));
    }

    String finish(HologramSession s) {
        if (s.scope == 3 && hasHatches()) return FAMILY_SCOPE;
        if (s.scope >= 2 && (s.selectedIndices.size() != s.scanSelectedPositions.size()
            || s.scanSelectedPosition != null && s.selected < 0))
            return "原生施工选择已变化，请重新选择完整范围。";
        if (problem != null) return problem;
        if (staged.size() != requested.size()) return "有手选仓室位置已消失，未应用本次选择。";
        for (Map.Entry<Integer, Applied> row : staged.entrySet())
            if (!HologramSessionPolicy.inScope(s, row.getValue().cell, row.getKey()))
                return "手选仓室不在本次施工范围内。";
        for (Map.Entry<Integer, Applied> row : staged.entrySet()) {
            Applied choice = row.getValue();
            String position = HologramValues.position(choice.cell);
            s.pins.put(row.getKey(), choice.item);
            s.coordinatePins.put(position, choice.item.copy());
            if (choice.facing < 0) s.facingPins.remove(position);
            else s.facingPins.put(position, choice.facing);
        }
        return null;
    }
}
