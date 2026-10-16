package com.miaokatze.gtmb.hologram;

/** Cooperative server-thread checkpoints; an individual native callback/transaction always finishes atomically. */
final class HologramWorkBudget {

    static final long TICK_NANOS = 10_000_000L;
    static final long SLICE_NANOS = 5_000_000L;
    private static Thread owner;
    private static long tickDeadline;

    private HologramWorkBudget() {}

    static void begin(long deadline) {
        owner = Thread.currentThread();
        tickDeadline = deadline;
    }

    static void end() {
        owner = null;
    }

    static long deadline(long sliceNanos) {
        long deadline = System.nanoTime() + sliceNanos;
        return owner == Thread.currentThread() ? Math.min(deadline, tickDeadline) : deadline;
    }

    /**
     * Standalone/native callers retain capture's original callback budget; only scheduled continuation shares a tick.
     */
    static long captureDeadline(long originalDeadline) {
        return owner == Thread.currentThread() ? Math.min(originalDeadline, deadline(SLICE_NANOS)) : originalDeadline;
    }
}
