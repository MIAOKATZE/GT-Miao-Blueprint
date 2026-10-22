package com.miaokatze.gtmb.client.hologram;

import java.util.function.Supplier;

/** The proposed placement survives camera navigation until the user explicitly resumes positioning. */
final class HologramPlacementPointer {

    private int[] anchor;

    int[] sample(boolean positioning, Supplier<int[]> target) {
        if (positioning) {
            int[] next = target.get();
            anchor = next == null ? null : next.clone();
        }
        return anchor == null ? null : anchor.clone();
    }

    void clear() {
        anchor = null;
    }
}
