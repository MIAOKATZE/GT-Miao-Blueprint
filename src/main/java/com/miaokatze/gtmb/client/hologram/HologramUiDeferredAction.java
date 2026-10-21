package com.miaokatze.gtmb.client.hologram;

/** One input intent per screen; screen suspension retires it before any later reopen. */
final class HologramUiDeferredAction {

    private Runnable pending;

    void offer(Runnable action) {
        if (pending == null) pending = action;
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
