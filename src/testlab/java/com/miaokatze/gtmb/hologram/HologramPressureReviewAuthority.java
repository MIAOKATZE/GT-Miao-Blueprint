// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;

import com.github.bsideup.jabel.Desugar;

/** Read-only view from the existing lease-owned integrated server END tick. */
public final class HologramPressureReviewAuthority {

    private HologramPressureReviewAuthority() {}

    @Desugar
    public record Sample(String session, String request, long revision, long nonce, int job, boolean cancelled,
        boolean quoted, int cells, boolean ready, boolean unchanged, boolean aeActive, boolean preparing) {}

    public static Sample sample(WorldServer world, EntityPlayerMP actor, int slot, ItemStack tool, NBTTagList stock,
        int[] anchor) {
        if (actor == null || actor.worldObj != world || actor.dimension != 0 || actor.isDead
            || actor.capabilities.isCreativeMode || !actor.capabilities.allowEdit
            || actor.inventory.currentItem != slot || actor.getHeldItem() != tool || tool == null
            || !HologramNetwork.progressConnected(actor)) throw new IllegalStateException("Pressure server actor changed");
        boolean unchanged = stock.equals(actor.inventory.writeToNBT(new NBTTagList()));
        boolean ae = HologramAeCraftingOrders.active(actor);
        boolean preparing = HologramPlacementUpload.active(actor) || HologramProjectedPlacementService.preparing(actor);
        HologramSession session = HologramWorkspace.find(actor.getUniqueID());
        if (session == null) return new Sample("", "", -1, -1, 0, false, false, 0,
            true, unchanged, ae, preparing);
        if (session.player != actor || session.slot != slot || session.boundTool != tool
            || session.x != anchor[0] || session.y != anchor[1] || session.z != anchor[2])
            throw new IllegalStateException("Foreign pressure review authority");
        return new Sample(session.id, session.executionRequestId, session.planRevision, session.placementReviewNonce,
            session.job, session.cancelled, session.placementReviewIssued && session.blueprintAwaitingConfirmation
                && session.materialQuote.getBoolean("complete"), session.capture == null ? 0 : session.capture.cells.size(),
            session.executionStart == null, unchanged, ae, preparing);
    }
}
