package com.miaokatze.gtmb.client.hologram;

/** Keeps an already opened review on screen; this presentation state never authorizes construction. */
final class HologramPlacementReviewContext {

    private String session = "";
    private long nonce;

    void accept(String session, long nonce, boolean open, boolean lockedPlacement, boolean awaitingConfirmation) {
        if (!open || !lockedPlacement || !awaitingConfirmation || nonce <= 0 || session.isEmpty()) return;
        this.session = session;
        this.nonce = nonce;
    }

    boolean retain(String session, long nonce, boolean lockedPlacement, boolean moving) {
        return this.nonce > 0 && this.session.equals(session) && nonce >= this.nonce && lockedPlacement && !moving;
    }
}
