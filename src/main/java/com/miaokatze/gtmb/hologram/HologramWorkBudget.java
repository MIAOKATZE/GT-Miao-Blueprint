package com.miaokatze.gtmb.hologram;

/** Cooperative server-thread checkpoints; an individual native callback/transaction always finishes atomically. */
final class HologramWorkBudget {

    static final long TICK_NANOS = 10_000_000L;
    static final long SLICE_NANOS = 5_000_000L;
    static final long TICK_WORK_TARGET_NANOS = 40_000_000L;
    static final long MIN_PROGRESS_NANOS = 1_000_000L;
    private static Thread owner;
    private static long tickDeadline, quotaDeadline;
    private static java.util.function.LongSupplier clock;
    private static Thread serverTickOwner;
    private static long serverTickStarted;

    private HologramWorkBudget() {}

    static void begin(long deadline) {
        begin(deadline, System::nanoTime);
    }

    static void begin(long deadline, java.util.function.LongSupplier time) {
        owner = Thread.currentThread();
        tickDeadline = quotaDeadline = deadline;
        clock = time;
    }

    static void end() {
        owner = null;
        clock = null;
    }

    static void startServerTick() {
        serverTickOwner = Thread.currentThread();
        serverTickStarted = System.nanoTime();
    }

    static void endServerTick() {
        serverTickOwner = null;
    }

    /** Use spare server time up to 40 ms from START, leaving roughly 10 ms for later END listeners. */
    static long tickNanos() {
        return serverTickOwner == Thread.currentThread() ? tickNanos(serverTickStarted, System.nanoTime()) : TICK_NANOS;
    }

    static long tickNanos(long started, long now) {
        return Math.max(MIN_PROGRESS_NANOS, TICK_WORK_TARGET_NANOS - Math.max(0L, now - started));
    }

    static void quota(long deadline) {
        quotaDeadline = Math.min(deadline, tickDeadline);
    }

    /** Budgeted continuations share their assigned quota; standalone callers retain their original slice. */
    static long remainingNanos(long fallback) {
        return owner == Thread.currentThread() ? Math.max(0L, quotaDeadline - clock.getAsLong()) : fallback;
    }

    static long deadline(long sliceNanos) {
        boolean scheduled = owner == Thread.currentThread();
        long deadline = (scheduled ? clock.getAsLong() : System.nanoTime()) + sliceNanos;
        return scheduled ? Math.min(deadline, quotaDeadline) : deadline;
    }

    /**
     * Standalone/native callers retain capture's original callback budget; only scheduled continuation shares a tick.
     */
    static long captureDeadline(long originalDeadline) {
        return owner == Thread.currentThread() ? Math.min(originalDeadline, deadline(SLICE_NANOS)) : originalDeadline;
    }
}
