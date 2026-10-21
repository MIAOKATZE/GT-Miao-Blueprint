package com.miaokatze.gtmb.client.hologram;

/** One input intent per screen; screen suspension retires it before any later reopen. */
final class HologramUiDeferredAction {

    private Runnable pending;

    void offer(Runnable action) {
        tryOffer(action);
    }

    /** A playback producer must keep retrying when a player's input already owns this tick. */
    boolean tryOffer(Runnable action) {
        if (pending != null) return false;
        pending = action;
        return true;
    }

    void run(boolean active) {
        Runnable action = pending;
        pending = null;
        if (active && action != null) action.run();
    }

    void clear() {
        pending = null;
    }
}
