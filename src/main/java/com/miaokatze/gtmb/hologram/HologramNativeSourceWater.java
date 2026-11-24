// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.util.GTStructureUtility;
import gregtech.common.tileentities.machines.multi.MTEIndustrialChemicalBath;
import gregtech.common.tileentities.machines.multi.MTEOreWashingPlant;

/** The reviewed GT5U machine can supply this target itself. It is never an item placement operation. */
final class HologramNativeSourceWater {

    // Presentation only. Construction requires the separately owned typed capture row, never this tag.
    private static final String DISPLAY = "nativeSourceWaterPreview";
    private static final String WATER_ID = "minecraft:water";

    private HologramNativeSourceWater() {}

    static boolean reviewedController(Class<?> type) {
        // GT5U 5.09.54.265: both controllers use the same slot in checkMachine and fillStructureWithWater.
        // Other machines use the same water/air chain without proving a matching native generator.
        return type == MTEOreWashingPlant.class || type == MTEIndustrialChemicalBath.class;
    }

    static boolean reviewedSlot(IStructureElement<?> element) {
        if (!(element instanceof IStructureElementChain<?>chain)) return false;
        try {
            IStructureElement<?>[] children = HologramDeclarationWrappers.nativeChainChildren(chain);
            if (children == null || children.length != 2 || children[1] != StructureUtility.isAir()) return false;
            Class<?> water = children[0].getClass();
            Method factory = HologramDeclarationWrappers.factory(water);
            if (factory == null || factory.getDeclaringClass() != GTStructureUtility.class
                || !"ofAnyWater".equals(factory.getName())
                || factory.getParameterCount() != 1
                || factory.getParameterTypes()[0] != boolean.class) return false;
            // The exact dependency stores the invocation argument in this final field, not in its class identity.
            Field allowFlowing = water.getDeclaredField("val$allowFlowing");
            if (allowFlowing.getType() != boolean.class) return false;
            allowFlowing.setAccessible(true);
            return !allowFlowing.getBoolean(children[0]);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
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
