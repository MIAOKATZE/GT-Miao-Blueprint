package com.miaokatze.gtmb.hologram;

/** One connection's optional snapshot credit. Sequence acknowledgements never create extra credit. */
final class HologramSnapshotFlowControl {

    static final int VERSION = 1;
    static final long LEGACY_GRACE_TICKS = 20;
    final long generation;
    private final long offeredAt;
    private boolean capable, negotiated, legacy, ready;
    private long outstanding = -1, consumed = -1;

    HologramSnapshotFlowControl(long generation, long offeredAt) {
        this.generation = generation;
        this.offeredAt = offeredAt;
    }

    void capable() {
        if (!legacy) capable = true;
    }

    boolean negotiate(long generation, boolean ready) {
        if (this.generation != generation || legacy) return false;
        capable = negotiated = true;
        if (outstanding < 0) this.ready |= ready;
        return true;
    }

    boolean begin(long sequence, int bytes, long tick) {
        if (bytes <= 0 || bytes > HologramSnapshotAssembly.MAX_BYTES)
            throw new IllegalArgumentException("snapshot credit byte budget");
        if (!capable && !negotiated && tick - offeredAt >= LEGACY_GRACE_TICKS) legacy = true;
        if (legacy) return true;
        if (outstanding == sequence) return true;
        if (!negotiated || !ready || outstanding >= 0) return false;
        outstanding = sequence;
        ready = false;
        return true;
    }

    boolean consume(long generation, long sequence) {
        if (this.generation != generation || !negotiated || sequence < 0 || outstanding != sequence) return false;
        outstanding = -1;
        consumed = sequence;
        ready = true;
        return true;
    }

    boolean consumed(long generation, long sequence) {
        return this.generation == generation && negotiated && sequence >= 0 && consumed == sequence;
    }

    boolean ready() {
        return ready && outstanding < 0;
    }
}
