package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.GregTechAPI;

/** Registered GT meta-tile schemas only; inventory, rates, filters and consumed-component state remain independent. */
final class HologramNetworkSchemas {

    private HologramNetworkSchemas() {}

    static boolean removeExternalReferences(Block block, int fallbackId, NBTTagCompound payload) {
        if (block != GregTechAPI.sBlockMachines || payload == null) return false;
        int id = payload.hasKey("mID", 99) ? payload.getInteger("mID") : fallbackId;
        if (id < 0 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] == null)
            return false;
        Class<?> type = GregTechAPI.METATILEENTITIES[id].getClass();
        boolean changed = false;
        for (String host : new String[] { "gregtech.common.tileentities.machines.MTEHatchInputBusME",
            "gregtech.common.tileentities.machines.MTEHatchInputME",
            "gregtech.common.tileentities.machines.MTEHatchCraftingInputME",
            "gregtech.common.tileentities.machines.outputme.MTEHatchOutputME",
            "gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME" }) {
            if (is(type, host)) {
                changed |= payload.hasKey("proxy");
                payload.removeTag("proxy");
            }
        }
        if (is(type, "com.miaokatze.gtsr.common.machine.base.MTEFilteredCacheNode")
            || is(type, "com.miaokatze.gtsr.common.machine.base.MTERemoteWorkerNode")
            || is(type, "com.miaokatze.gtsr.common.machine.MTESingularityCompartmentBase")) {
            changed |= payload.hasKey("gtsr.hubPos");
            payload.removeTag("gtsr.hubPos");
        }
        if (is(type, "com.miaokatze.gtsr.common.machine.base.MTEHubArrayBase")
            || is(type, "com.miaokatze.gtsr.common.machine.MTESingularityDrillingHub")) {
            changed |= payload.hasKey("mBoundNodes");
            payload.removeTag("mBoundNodes");
        }
        return changed;
    }

    private static boolean is(Class<?> type, String name) {
        if (type == null) return false;
        if (name.equals(type.getName())) return true;
        for (Class<?> contract : type.getInterfaces()) if (is(contract, name)) return true;
        return is(type.getSuperclass(), name);
    }
}
