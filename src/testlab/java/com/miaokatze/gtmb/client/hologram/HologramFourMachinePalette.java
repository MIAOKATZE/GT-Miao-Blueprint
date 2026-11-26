// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramFourMachineSpec;

import gregtech.api.casing.Casings;

/** Independent finite .265 machine oracle. It never reads the GTMB target's texture fields. */
final class HologramFourMachinePalette {

    private static final String[][] EXTRUDER_SHAPE = { { "         ", "         ", "     CCC~", "     CCCC" },
        { "A ABBBBBA", "AAA     A", "A ABBBBBA", "DDDDDDDDA" }, { "AAA     A", "AAA     A", "AAA     A", "DDDDDDDDA" },
        { "A ABBBBBA", "AAA     A", "A ABBBBBA", "DDDDDDDDA" } };

    private HologramFourMachinePalette() {}

    static int texture(HologramFourMachineSpec spec, ExtendedFacing facing, int dx, int dy, int dz,
        NBTTagCompound input) {
        switch (spec) {
            case EBF:
                return constant(spec, "CASING_INDEX", 11);
            case FLUID_EXTRACTOR:
                return constant(spec, "CASING_INDEX", 48);
            case PRECISE_ASSEMBLER:
                int channel = input.getCompoundTag("channels")
                    .hasKey("unit_casing", 3)
                        ? input.getCompoundTag("channels")
                            .getInteger("unit_casing")
                        : Math.max(1, input.getInteger("main"));
                net.minecraft.block.Block casing = channel == 1 ? goodgenerator.loader.Loaders.impreciseUnitCasing
                    : goodgenerator.loader.Loaders.preciseUnitCasing;
                int meta = channel == 1 ? 0 : Math.min(3, channel - 2);
                Integer tier = goodgenerator.blocks.tileEntity.MTEPreciseAssembler.getCasingBlockTier(casing, meta);
                require(
                    tier != null && tier >= -1 && tier <= 3,
                    "Exact registered precise unit casing tier unavailable");
                return constant(spec, "CASING_INDEX", 1541) + tier;
            case EXTRUDER:
                int[] xyz = new int[3];
                for (int c = 0; c < EXTRUDER_SHAPE.length; c++) for (int b = 0; b < EXTRUDER_SHAPE[c].length; b++)
                    for (int a = 0; a < EXTRUDER_SHAPE[c][b].length(); a++) {
                        facing.getWorldOffset(new int[] { a - 8, b - 2, c }, xyz);
                        if (xyz[0] != dx || xyz[1] != dy || xyz[2] != dz) continue;
                        char element = EXTRUDER_SHAPE[c][b].charAt(a);
                        if (element == 'A') return Casings.TensionResistantMachineCasing.textureId;
                        if (element == 'C') return Casings.ExtruderMachineCasing.textureId;
                        throw new IllegalStateException(
                            "Extruder hatch outside exact .265 A/C slots: " + dx + "," + dy + "," + dz + " " + element);
                    }
                throw new IllegalStateException("Extruder hatch escaped native shape");
            default:
                throw new IllegalStateException("Unreviewed palette machine");
        }
    }

    private static int constant(HologramFourMachineSpec spec, String name, int expected) {
        try {
            java.lang.reflect.Field field = Class.forName(spec.className)
                .getDeclaredField(name);
            field.setAccessible(true);
            int actual = field.getInt(null);
            require(actual == expected, "Actual native casing constant differs from reviewed .265");
            return actual;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Exact native palette constant unavailable", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
