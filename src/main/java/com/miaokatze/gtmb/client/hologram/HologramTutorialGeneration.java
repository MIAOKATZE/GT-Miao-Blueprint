// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** Retires delayed native callbacks when teaching playback restores or closes its graph. */
final class HologramTutorialGeneration {

    private long generation;

    long current() {
        return generation;
    }

    boolean isCurrent(long expected) {
        return generation == expected;
    }

    void invalidate() {
        generation++;
    }

    Runnable guard(Runnable action) {
        final long expected = generation;
        return () -> { if (isCurrent(expected)) action.run(); };
    }
}
