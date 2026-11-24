// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** Screen-owned input intents run after MUI input dispatch and retire on suspension. */
final class HologramUiDeferredAction {

    private Runnable pending, escape, escapeFollowup;
    private boolean pendingNativeCommand, dispatchingEscape;

    void offer(Runnable action) {
        tryOffer(action);
    }

    /** A playback producer must keep retrying when a player's input already owns this tick. */
    boolean tryOffer(Runnable action) {
        return tryOffer(action, false);
    }

    void offerNativeCommand(Runnable action) {
        tryOffer(action, true);
    }

    private boolean tryOffer(Runnable action, boolean nativeCommand) {
        if (dispatchingEscape) {
            // The current modal may itself ask to defer its close; never execute that inline or lose it to
            // an unrelated already accepted callback parked in the ordinary slot.
            if (escapeFollowup != null) return false;
            escapeFollowup = action;
            return true;
        }
        if (pending != null || escape != null || escapeFollowup != null) return false;
        pending = action;
        pendingNativeCommand = nativeCommand;
        return true;
    }

    void offerEscape(Runnable action) {
        // A repeat before the visible layer closes is still the same dismissal.
        if (dispatchingEscape || escape != null || escapeFollowup != null) return;
        if (pendingNativeCommand) {
            pending = null;
            pendingNativeCommand = false;
        }
        escape = action;
    }

    void run(boolean active) {
        if (!active) {
            clear();
            return;
        }
        if (escape != null || escapeFollowup != null) {
            Runnable action = escape != null ? escape : escapeFollowup;
            escape = escapeFollowup = null;
            dispatchingEscape = true;
            try {
                action.run();
            } finally {
                dispatchingEscape = false;
            }
            return;
        }
        Runnable action = pending;
        pending = null;
        pendingNativeCommand = false;
        if (action != null) action.run();
    }

    void clear() {
        pending = escape = escapeFollowup = null;
        pendingNativeCommand = false;
    }
}
