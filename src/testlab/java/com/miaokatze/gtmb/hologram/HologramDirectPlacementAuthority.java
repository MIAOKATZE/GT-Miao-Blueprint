// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

/** Narrow TestLab boundary: exact one-frame ordinary cobble execution, plus read-only server job identity. */
public final class HologramDirectPlacementAuthority {

    private HologramDirectPlacementAuthority() {}

    public static boolean matches(HologramPlacementUpload.Fragment fragment, UUID request, int[] anchor)
        throws IOException, InterruptedException {
        if (request == null || !request.equals(fragment.id) || fragment.index != 0 || fragment.data == null
            || fragment.total != fragment.data.length || fragment.total > 4096 || anchor == null) return false;
        var assembly = new HologramPlacementUpload.Assembly(fragment);
        byte[] complete = assembly.accept(fragment);
        if (complete == null || !MessageDigest.isEqual(HologramPlacementUpload.digest(complete), fragment.digest))
            return false;
        var envelope = HologramSnapshotCodec.decode(complete, 8192, 64L * 1024);
        var header = HologramPlacementRequest.header(envelope);
        if (!header.direct() || !request.toString().equals(header.id())
            || header.x() != anchor[0] || header.y() != anchor[1] || header.z() != anchor[2]) return false;
        var decoded = HologramPlacementRequest.decode(header, envelope, 256);
        if (decoded.size() != 1) return false;
        var cell = decoded.cell(0);
        return cell.x() == 0 && cell.y() == 0 && cell.z() == 0 && "minecraft:cobblestone".equals(cell.block())
            && "minecraft:cobblestone".equals(cell.item()) && cell.metadata() == 0 && cell.damage() == 0
            && cell.facing() == -1 && !cell.controller() && cell.tile().isEmpty() && cell.itemState().isEmpty();
    }

    public static void main(String[] args) throws Exception {
        UUID id = UUID.randomUUID();
        int[] at = { 5, 64, 5 };
        var block = new HologramProjectedPlacement.Cell(0, 0, 0, "minecraft:cobblestone", 0,
            "minecraft:cobblestone", 0, -1);
        var plan = new HologramProjectedPlacement.Plan(Collections.singletonList(block),
            Collections.singletonList(new HologramProjectedPlacement.Need("minecraft:cobblestone", 0, 1)));
        var direct = HologramNetwork.prepareProjectedPlacement(HologramPlacementRequest.encodeDirect(id, at, plan),
            () -> true);
        var legacy = HologramNetwork.prepareProjectedPlacement(HologramPlacementRequest.encode(id, at, plan), () -> true);
        if (!matches(direct.frame(0), id, at) || matches(legacy.frame(0), id, at)
            || matches(direct.frame(0), UUID.randomUUID(), at)
            || matches(direct.frame(0), id, new int[] { 6, 64, 5 }))
            throw new AssertionError("The positive gate must accept only its exact v4 cobblestone intent");
        System.out.println("Direct TestLab single-fragment gate regression passed.");
    }

    public static String completed(EntityPlayerMP actor, UUID request) {
        if (request == null || HologramAeCraftingOrders.active(actor)) return "";
        HologramSession session = HologramWorkspace.find(actor.getUniqueID());
        return session != null && session.player == actor && request.toString().equals(session.executionRequestId)
            && session.job == 3 && session.completed == 1 ? session.id : "";
    }
}
