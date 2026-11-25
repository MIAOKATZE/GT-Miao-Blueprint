// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

/** Height-group presentation cadence, independent of packet size and of the world transaction implementation. */
final class HologramLayerCadence {

    static final int HEIGHT = 6;
    static final long INTERVAL_TICKS = 10;
    private final int minY;
    private boolean started, settled;
    private long group, lastSettled;

    HologramLayerCadence(int minY) {
        this.minY = minY;
    }

    /** Wait from the previous group’s final settled slice, not from its first write. */
    boolean enter(int y, long tick) {
        long next = Math.floorDiv((long) y - minY, HEIGHT);
        if (started && next < group) throw new IllegalArgumentException("Out-of-order construction height");
        if (!started) {
            started = true;
            group = next;
            return true;
        }
        if (next == group) return true;
        if (!settled) return false;
        // A world clock reset cannot manufacture elapsed time or permanently strand the next group.
        if (tick < lastSettled) {
            lastSettled = tick;
            return false;
        }
        if (tick - lastSettled < INTERVAL_TICKS) return false;
        group = next;
        settled = false;
        return true;
    }

    void settle(long tick) {
        lastSettled = tick;
        settled = true;
    }
}
