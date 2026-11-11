// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/** Keeps an already opened review on screen; this presentation state never authorizes construction. */
final class HologramPlacementReviewContext {

    private String session = "";
    private long nonce;

    static boolean returnToWorld(NBTTagCompound state) {
        // Terminal jobs keep their placement flags for results; they no longer own world-preview navigation.
        return state.getBoolean("virtual") && state.getBoolean("virtualPlacement")
            && !state.getBoolean("open")
            && HologramWorldFlow.stage(false, true, true, state.getBoolean("virtualLocked"), state.getInteger("job"))
                != HologramWorldFlow.Stage.WORKSPACE;
    }

    void accept(String session, long nonce, boolean open, boolean lockedPlacement, boolean awaitingConfirmation) {
        if (!open || !lockedPlacement || !awaitingConfirmation || nonce <= 0 || session.isEmpty()) return;
        this.session = session;
        this.nonce = nonce;
    }

    boolean retain(String session, long nonce, boolean lockedPlacement, boolean moving) {
        return this.nonce > 0 && this.session.equals(session) && nonce >= this.nonce && lockedPlacement && !moving;
    }
}
