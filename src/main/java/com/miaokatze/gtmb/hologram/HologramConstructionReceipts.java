// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;

/** Geometry-free replay ledger and receipts for the currently registered construction protocols. */
final class HologramConstructionReceipts {

    private static final int RECENT_LIMIT = 1024;
    private static final Set<Request> RECENT = new LinkedHashSet<>();

    private HologramConstructionReceipts() {}

    static boolean consumed(EntityPlayerMP player, String id) {
        return RECENT.contains(new Request(player.getUniqueID(), id));
    }

    static void clear() {
        RECENT.clear();
    }

    static void retire(EntityPlayerMP player, String id) {
        if (RECENT.size() >= RECENT_LIMIT && !consumed(player, id)) {
            Iterator<Request> entries = RECENT.iterator();
            entries.next();
            entries.remove();
        }
        RECENT.add(new Request(player.getUniqueID(), id));
    }

    @Desugar
    private record Request(UUID player, String id) {}

    static void reply(EntityPlayerMP player, String id, boolean accepted, String reason) {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("placementRequestId", id);
        receipt.setBoolean("placementRequestAccepted", accepted);
        if (reason != null) receipt.setString("errorMessage", reason);
        HologramNetwork.sendState(player, receipt);
    }

    static void cancel(EntityPlayerMP player, String id) {
        if (!HologramNativeCompactStart.canonical(id)) return;
        retire(player, id);
        HologramSession published = HologramWorkspace.find(player.getUniqueID());
        if (published != null && published.player == player
            && published.job == 0
            && !id.isEmpty()
            && id.equals(published.executionRequestId)) {
            NBTTagCompound cancel = new NBTTagCompound();
            cancel.setString("session", published.id);
            cancel.setLong("planRevision", published.planRevision);
            HologramWorldPlacementWorkflow.cancelWorldPlacement(player, cancel);
        }
    }
}
