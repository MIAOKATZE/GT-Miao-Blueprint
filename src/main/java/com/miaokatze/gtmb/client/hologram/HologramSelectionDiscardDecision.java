package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;

/** One answer belongs to one captured selection; dismissing a question has no selection side effects. */
final class HologramSelectionDiscardDecision {

    private final BooleanSupplier valid;
    private final Runnable close, remember, discard;
    private boolean finished;

    HologramSelectionDiscardDecision(BooleanSupplier valid, Runnable close, Runnable remember, Runnable discard) {
        this.valid = valid;
        this.close = close;
        this.remember = remember;
        this.discard = discard;
    }

    void answer(boolean accepted, boolean skipPrompt) {
        if (finished) return;
        boolean admitted = accepted && valid.getAsBoolean();
        finished = true;
        close.run();
        if (!admitted) return;
        if (skipPrompt) remember.run();
        discard.run();
    }

    /** The hint is an alternative answer: return to editing after removing exactly one latest input. */
    void deleteLatest(Runnable delete) {
        if (finished) return;
        boolean admitted = valid.getAsBoolean();
        finished = true;
        close.run();
        if (admitted) delete.run();
    }

    void retire() {
        finished = true;
    }
}
