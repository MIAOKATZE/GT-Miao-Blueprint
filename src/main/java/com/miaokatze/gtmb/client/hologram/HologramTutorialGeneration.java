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
