// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Reviewed fixture identities. A live registry check is mandatory before placing any controller. */
public enum HologramFourMachineSpec {

    EBF("ebf", 1000, "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace", 3, 4, 3),
    EXTRUDER("extruder", 15549, "gregtech.common.tileentities.machines.multi.MTEIndustrialExtruder", 9, 4, 4),
    FLUID_EXTRACTOR("fluid-extractor", 2730, "gregtech.common.tileentities.machines.multi.MTELargeFluidExtractor", 5, 9,
        5),
    PRECISE_ASSEMBLER("precise-assembler", 32018, "goodgenerator.blocks.tileEntity.MTEPreciseAssembler", 9, 5, 5);

    public static final int MAX_TARGET_CELLS = 512;
    public static final int MAX_SEEDED_ITEMS = 8192;
    public final String key, className;
    public final int controllerId, width, height, depth;

    HologramFourMachineSpec(String key, int id, String type, int width, int height, int depth) {
        this.key = key;
        controllerId = id;
        className = type;
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    public ItemStack registeredController() {
        IMetaTileEntity machine = controllerId > 0 && controllerId < GregTechAPI.METATILEENTITIES.length
            ? GregTechAPI.METATILEENTITIES[controllerId]
            : null;
        require(
            machine != null && className.equals(
                machine.getClass()
                    .getName()),
            "Actual controller registration differs for " + key);
        ItemStack item = machine.getStackForm(1);
        require(
            item != null && item.getItem() instanceof ItemMachines && item.getItemDamage() == controllerId,
            "Actual registered controller item differs for " + key);
        return item.copy();
    }

    public List<ExtendedFacing> orientations() {
        return this == EXTRUDER || this == PRECISE_ASSEMBLER
            ? Arrays.asList(ExtendedFacing.NORTH_NORMAL_NONE, ExtendedFacing.EAST_NORMAL_NONE)
            : Collections.singletonList(ExtendedFacing.NORTH_NORMAL_NONE);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
