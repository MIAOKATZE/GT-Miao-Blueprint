// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;

import com.github.bsideup.jabel.Desugar;

/** Read-only view from the existing lease-owned integrated server END tick. */
public final class HologramPressureReviewAuthority {

    private HologramPressureReviewAuthority() {}

    /** Inspect only the exact locally prepared bytes, before any fragment may cross the fence. */
    public static int verifyV2(byte[] bytes, byte[] hash, UUID id, int[] anchor, int expectedCells) throws IOException {
        if (bytes.length < 2 || bytes.length > HologramPlacementUpload.MAX_COMPRESSED
            || bytes[0] != (byte) 0x1f || bytes[1] != (byte) 0x8b
            || !MessageDigest.isEqual(hash, HologramPlacementUpload.digest(bytes)))
            throw new IOException("Not the exact production placement GZIP envelope");
        int expanded = HologramPlacementUpload.MAX_EXPANDED;
        NBTTagCompound tag = HologramSnapshotCodec.decode(bytes, expanded, 4L * expanded);
        if (!tag.hasKey("placementWireVersion", 3) || tag.getInteger("placementWireVersion") != 2
            || !"projectedPlacement".equals(tag.getString("action")) || tag.hasKey("materialSources"))
            throw new IOException("Only the original Advanced v2 review with the owned material policy may be sent");
        HologramPlacementRequest.Header header = HologramPlacementRequest.header(tag);
        if (header.direct() || !header.id().equals(id.toString())
            || header.x() != anchor[0] || header.y() != anchor[1] || header.z() != anchor[2])
            throw new IOException("Foreign Advanced v2 request");
        int fields = tag.getIntArray("placementOperations").length;
        if (fields != expectedCells * 4) throw new IOException("Wrong Advanced v2 operation count");
        return fields / 4;
    }

    @Desugar
    public record Sample(String session, String request, long revision, long nonce, int job, boolean cancelled,
        boolean quoted, int cells, boolean ready, boolean unchanged, boolean aeActive, boolean preparing) {}

    public static Sample sample(WorldServer world, EntityPlayerMP actor, int slot, ItemStack tool, NBTTagList stock,
        int[] anchor) {
        if (actor == null || actor.worldObj != world || actor.dimension != 0 || actor.isDead
            || actor.capabilities.isCreativeMode || !actor.capabilities.allowEdit
            || actor.inventory.currentItem != slot || actor.getHeldItem() != tool || tool == null
            || HologramMaterials.read(tool).me || !HologramNetwork.progressConnected(actor))
            throw new IllegalStateException("Pressure server actor or material policy changed");
        boolean unchanged = stock.equals(actor.inventory.writeToNBT(new NBTTagList()));
        boolean ae = HologramAeCraftingOrders.active(actor);
        boolean preparing = HologramPlacementUpload.active(actor) || HologramProjectedPlacementService.preparing(actor);
        HologramSession session = HologramWorkspace.find(actor.getUniqueID());
        if (session == null) return new Sample("", "", -1, -1, 0, false, false, 0,
            true, unchanged, ae, preparing);
        if (session.player != actor || session.slot != slot || session.boundTool != tool
            || session.materialSettings.me || session.x != anchor[0] || session.y != anchor[1] || session.z != anchor[2])
            throw new IllegalStateException("Foreign pressure review authority");
        return new Sample(session.id, session.executionRequestId, session.planRevision, session.placementReviewNonce,
            session.job, session.cancelled, session.placementReviewIssued && session.blueprintAwaitingConfirmation
                && session.materialQuote.getBoolean("complete"), session.capture == null ? 0 : session.capture.cells.size(),
            session.executionStart == null, unchanged, ae, preparing);
    }
}
