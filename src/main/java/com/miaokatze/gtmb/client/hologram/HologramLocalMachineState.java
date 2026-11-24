// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Method;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;

import com.github.bsideup.jabel.Desugar;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Read only fields carried by the machine's normal client updates. */
@Desugar
record HologramLocalMachineState(Operation operation, int tier) {

    private static final String SOLAR = "com.miaokatze.gtsr.common.machine.MTELargeSolarOverpressureArray";
    private static final ClassValue<Method> SYNCED_TIER = new ClassValue<>() {

        @Override
        protected Method computeValue(Class<?> type) {
            if (!SOLAR.equals(type.getName())) return null;
            try {
                return type.getMethod("getTier");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return null;
            }
        }
    };

    enum Operation {

        UNKNOWN("Unknown"),
        ACTIVE("Active"),
        IDLE("Idle"),
        DISABLED("Disabled");

        private final String key;

        Operation(String key) {
            this.key = "gtmb.hologram.localMachine" + key;
        }
    }

    static final HologramLocalMachineState UNKNOWN = new HologramLocalMachineState(Operation.UNKNOWN, 0);

    static HologramLocalMachineState read(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity gt) || !gt.isClientSide() || tile.isInvalid() || !gt.canAccessData())
            return UNKNOWN;
        IMetaTileEntity machine = gt.getMetaTileEntity();
        if (machine == null) return UNKNOWN;
        Operation operation = gt.isActive() ? Operation.ACTIVE
            : gt.isAllowedToWork() ? Operation.IDLE : Operation.DISABLED;
        return new HologramLocalMachineState(operation, syncedTier(machine));
    }

    private static int syncedTier(IMetaTileEntity machine) {
        Method getter = SYNCED_TIER.get(machine.getClass());
        if (getter != null) try {
            // GTSR Solar's onValueUpdate publishes this value in two ordinary GT machine update bits.
            if (getter.invoke(machine) instanceof Integer value && value >= 1 && value <= 3) return value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // This optional integration has no client-reported tier for this machine/version.
        }
        return 0;
    }

    String caption() {
        String status = StatCollector.translateToLocal(operation.key);
        return tier > 0
            ? status + " · " + StatCollector.translateToLocalFormatted("gtmb.hologram.localMachineTier", tier)
            : status;
    }
}
