package com.miaokatze.gtmb.util;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.ForgeChunkManager;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** One opt-in chunk callback shared by isolated probes and the configured agent test lab. */
public final class ProbeChunkTickets {

    private static boolean registered;

    private ProbeChunkTickets() {}

    public static synchronized void ensureRegistered() {
        if (registered) return;
        if (GTMiaoBlueprint.instance == null)
            throw new IllegalStateException("Probe tickets require the loaded GTMB mod instance");
        ForgeChunkManager.setForcedChunkLoadingCallback(GTMiaoBlueprint.instance, (tickets, loadedWorld) -> {
            for (ForgeChunkManager.Ticket ticket : tickets) {
                NBTTagCompound marker = ticket.getModData();
                if (marker.getBoolean("gtmbEnergyProbe") || marker.getBoolean("gtmbNetworkProbe")
                    || marker.getBoolean("gtmbPreviewSourceProbe")
                    || marker.getBoolean("gtmbAeCutDataProbe")
                    || marker.getBoolean("gtmbTestLab")) ForgeChunkManager.releaseTicket(ticket);
            }
        });
        registered = true;
    }
}
