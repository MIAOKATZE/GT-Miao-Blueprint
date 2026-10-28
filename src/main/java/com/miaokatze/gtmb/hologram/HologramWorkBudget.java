package com.miaokatze.gtmb.hologram;

import com.miaokatze.miaokatzecore.testkit.Deadline;

/** Cooperative server-thread checkpoints; an individual native callback/transaction always finishes atomically. */
final class HologramWorkBudget {

    static final long TICK_NANOS = configuredNanos("gtmb.hologram.tickNanos", 4_000_000L, 5_000_000L);
    static final long SLICE_NANOS = configuredNanos("gtmb.hologram.sliceNanos", 2_000_000L, TICK_NANOS);
    static final long TICK_WORK_TARGET_NANOS = 40_000_000L;
    static final long MIN_PROGRESS_NANOS = 1_000_000L;
    private static Thread owner;
    private static long tickDeadline, quotaDeadline;
    private static java.util.function.LongSupplier clock;
    private static Frame parent;
    private static Thread serverTickOwner;
    private static volatile Thread serverOwner;
    private static long serverTickStarted;
    private static long serverTickSequence;

    private HologramWorkBudget() {}

    static void begin(long deadline) {
        begin(deadline, System::nanoTime);
    }

    static void begin(long deadline, java.util.function.LongSupplier time) {
        parent = owner == Thread.currentThread() ? new Frame(tickDeadline, quotaDeadline, clock, parent) : null;
        owner = Thread.currentThread();
        tickDeadline = quotaDeadline = deadline;
        clock = time;
    }

    static void end() {
        if (parent != null) {
            tickDeadline = parent.tick;
            quotaDeadline = parent.quota;
            clock = parent.clock;
            parent = parent.parent;
            return;
        }
        owner = null;
        clock = null;
    }

    static void startServerTick() {
        serverTickOwner = Thread.currentThread();
        serverOwner = serverTickOwner;
        serverTickStarted = System.nanoTime();
        if (++serverTickSequence == 0) serverTickSequence++;
    }

    static void endServerTick() {
        serverTickOwner = null;
    }

    /** Captured only by the actual server tick hook, valid for scheduled callbacks outside the budget window. */
    static boolean isServerOwnerThread() {
        return serverOwner == Thread.currentThread();
    }

    static boolean isOwnerThread() {
        return owner == Thread.currentThread() || serverTickOwner == Thread.currentThread();
    }

    /** Zero outside an actual server tick, so standalone continuation callers can retry immediately. */
    static long currentServerTick() {
        return serverTickOwner == Thread.currentThread() ? serverTickSequence : 0;
    }

    /** Use spare server time up to 40 ms from START, leaving roughly 10 ms for later END listeners. */
    static long tickNanos() {
        return serverTickOwner == Thread.currentThread() ? tickNanos(serverTickStarted, System.nanoTime()) : TICK_NANOS;
    }

    static long tickNanos(long started, long now) {
        Deadline spare = Deadline.startingAt(started, TICK_WORK_TARGET_NANOS);
        if (!spare.hasTimeRemaining(now)) return MIN_PROGRESS_NANOS;
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

    private static long configuredNanos(String property, long fallback, long maximum) {
        try {
            return Math.max(100_000L, Math.min(maximum, Long.parseLong(System.getProperty(property, ""))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class Frame {

        final long tick, quota;
        final java.util.function.LongSupplier clock;
        final Frame parent;

        Frame(long tick, long quota, java.util.function.LongSupplier clock, Frame parent) {
            this.tick = tick;
            this.quota = quota;
            this.clock = clock;
            this.parent = parent;
        }
    }
}
