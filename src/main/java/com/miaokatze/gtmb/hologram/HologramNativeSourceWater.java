// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.util.GTUtility;

/** An explicit native air fallback proves that this water is optional structure content. */
final class HologramNativeSourceWater {

    // Presentation only. Construction requires the separately owned typed capture row, never this tag.
    private static final String DISPLAY = "nativeSourceWaterPreview";
    private static final String WATER_ID = "minecraft:water";

    private HologramNativeSourceWater() {}

    static boolean optionalWater(Block block) {
        if (block == Blocks.water || block == Blocks.flowing_water) return true;
        return block != null && block != Blocks.air && GTUtility.isWater(block);
    }

    static boolean source(Block block, int metadata) {
        return block == Blocks.water && metadata == 0;
    }

    static boolean missingFromDetachedPreview(Block block) {
        // Do not replace or recolor an occupied native preview position, including flowing water.
        return block == Blocks.air;
    }

    static void describe(NBTTagCompound row) {
        row.setBoolean(DISPLAY, true);
    }

    static boolean previewRow(NBTTagCompound row) {
        return row.getBoolean(DISPLAY) && sourceRow(row);
    }

    static boolean matches(HologramControllerTarget.Cell captured, NBTTagCompound row) {
        return captured != null && captured.kind() == HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER
            && source(captured.targetBlock(), captured.wantMeta())
            && captured.targetStack() == null
            && sourceRow(row)
            && row.hasKey("x", 3)
            && row.hasKey("y", 3)
            && row.hasKey("z", 3)
            && row.hasKey("dx", 3)
            && row.hasKey("dy", 3)
            && row.hasKey("dz", 3)
            && captured.x() == row.getInteger("x")
            && captured.y() == row.getInteger("y")
            && captured.z() == row.getInteger("z")
            && captured.dx() == row.getInteger("dx")
            && captured.dy() == row.getInteger("dy")
            && captured.dz() == row.getInteger("dz");
    }

    private static boolean sourceRow(NBTTagCompound row) {
        return WATER_ID.equals(row.getString("wantId")) && row.hasKey("wantMeta", 3)
            && row.getInteger("wantMeta") == 0
            && !row.hasKey("targetStack")
            && !row.getBoolean("anchor")
            && !row.getBoolean("missingBlock")
            && !row.getBoolean("iconOnly");
    }
}
