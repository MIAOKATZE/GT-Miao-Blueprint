package com.miaokatze.gtmb.client.hologram;

import java.util.function.Supplier;

/** Passive placement preview updates independently of the modifier required to commit a placement click. */
final class HologramPlacementPointer {

    private int[] anchor;

    int[] preview(boolean navigating, Supplier<int[]> target) {
        if (!navigating) {
            int[] next = target.get();
            anchor = next == null ? null : next.clone();
        }
        return anchor == null ? null : anchor.clone();
    }

    void clear() {
        anchor = null;
    }

    static boolean allowsClick(boolean cameraActive, boolean placement, boolean control) {
        return !cameraActive || !placement || control;
    }
}
