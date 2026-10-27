package com.miaokatze.gtmb.hologram;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One synchronous owner operation; diagnostics never authorize mutation or durability. Fixed-size counters only:
 * no request/player identifiers, coordinates, names, paths, NBT or exception text. Disabled unless explicitly enabled.
 */
final class HologramCutDiagnostics implements AutoCloseable {

    enum Phase {
        JOURNAL_LOAD,
        CAPTURE,
        JOURNAL_ENCODE,
        JOURNAL_WRITE,
        JOURNAL_SYNC,
        JOURNAL_REPLACE,
        NATIVE_APPLY,
        SAVE_PREPARE,
        NATIVE_SAVE,
        GLOBAL_IO_WAIT,
        DISK_READBACK,
        DISK_COMPARE,
        REGION_SYNC,
        COMMIT_NOTIFY
    }

    private static final AtomicLong NEXT = new AtomicLong();
    static final HologramCutDiagnostics QUIET = new HologramCutDiagnostics(null, System::nanoTime);
    private static final Scope NO_SCOPE = new Scope(null, null, 0, 0);
    private final Consumer<String> sink;
    private final LongSupplier clock;
    private final long id;
    private final long[] nanos, calls, units;
    private boolean completed, closed;

    static HologramCutDiagnostics begin() {
        return Boolean.getBoolean("gtmb.cutPhaseDiagnostics")
            ? new HologramCutDiagnostics(message -> com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info(message), System::nanoTime)
            : QUIET;
    }

    HologramCutDiagnostics(Consumer<String> sink, LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
        id = sink == null ? 0 : NEXT.incrementAndGet();
        nanos = sink == null ? null : new long[Phase.values().length];
        calls = sink == null ? null : new long[Phase.values().length];
        units = sink == null ? null : new long[Phase.values().length];
    }

    /**
     * Units: bytes for journal load/write/sync/replace; cells for apply/compare; chunks for prepare/save/readback;
     * region files for region sync; operation invocations for capture, encode, global wait and notification.
     */
    Scope phase(Phase phase, long count) {
        return sink == null ? NO_SCOPE : new Scope(this, phase, Math.max(0, count), clock.getAsLong());
    }

    void completed() {
        if (sink != null) completed = true;
    }

    @Override
    public void close() {
        if (sink == null || closed) return;
        closed = true;
        StringBuilder summary = new StringBuilder("Cut phases operation=").append(id)
            .append(" completed=").append(completed);
        for (Phase phase : Phase.values()) {
            int index = phase.ordinal();
            if (calls[index] != 0) summary.append(' ').append(phase.name()).append("[calls=").append(calls[index])
                .append(",units=").append(units[index]).append(",ns=").append(nanos[index]).append(']');
        }
        // Observability must not turn a committed edit into an apparent transaction failure.
        try {
            sink.accept(summary.toString());
        } catch (RuntimeException ignored) {}
    }

    static final class Scope implements AutoCloseable {

        private HologramCutDiagnostics owner;
        private final Phase phase;
        private final long count, start;

        private Scope(HologramCutDiagnostics owner, Phase phase, long count, long start) {
            this.owner = owner;
            this.phase = phase;
            this.count = count;
            this.start = start;
        }

        @Override
        public void close() {
            if (owner == null) return;
            int index = phase.ordinal();
            owner.nanos[index] += Math.max(0, owner.clock.getAsLong() - start);
            owner.calls[index]++;
            owner.units[index] += count;
            owner = null;
        }
    }
}
