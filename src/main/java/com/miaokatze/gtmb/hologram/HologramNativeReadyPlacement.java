// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** Retired protocol refusal only. All real construction uses ConstructionJobs. */
final class HologramNativeReadyPlacement {

    static final String FLAG = "nativeReadyPlace";

    @FunctionalInterface
    interface Receipt {

        void send(EntityPlayerMP player, String id, boolean accepted, String reason);
    }

    private HologramNativeReadyPlacement() {}

    static boolean enabled() {
        return false;
    }

    static boolean admitPrepared(HologramSession s, String id) {
        return false;
    }

    static boolean tick(HologramSession s) {
        return false;
    }

    static boolean tick(HologramSession s, int rows, long deadline, java.util.function.LongSupplier clock,
        Receipt receipt) {
        return false;
    }

    static void cancel(HologramSession s) {}

    static boolean cancel(EntityPlayerMP player, String id) {
        return false;
    }

    long completed() {
        return 0;
    }

    static void receive(EntityPlayerMP player, NBTTagCompound request) {
        HologramNativeCompactStart.receive(player, request);
    }

    static void receive(EntityPlayerMP player, NBTTagCompound request, Receipt receipt) {
        HologramNativeCompactStart.receive(player, request, receipt);
    }
}
