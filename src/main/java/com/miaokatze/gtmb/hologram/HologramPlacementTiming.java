// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** Opt-in diagnostics. Absolute monotonic timestamps are comparable only within one JVM. */
public final class HologramPlacementTiming {

    private static final boolean ENABLED = Boolean.getBoolean("gtmb.hologram.placementTiming");
    private static final ThreadLocal<Click> CLICK = new ThreadLocal<>();

    public static final class Click implements AutoCloseable {

        private final Click previous;
        private final long at;

        private Click(Click previous, long at) {
            this.previous = previous;
            this.at = at;
        }

        @Override
        public void close() {
            if (previous == null) CLICK.remove();
            else CLICK.set(previous);
        }
    }

    public static long clickNanos() {
        return ENABLED ? System.nanoTime() : 0;
    }

    public static Click clientClick() {
        return clientClick(clickNanos());
    }

    public static Click clientClick(long at) {
        if (!ENABLED) return null;
        Click current = new Click(CLICK.get(), at);
        CLICK.set(current);
        return current;
    }

    /** Bind the actual accepted button activation to its request, without carrying data over the wire. */
    public static void clientRequest(String kind, UUID request) {
        if (!ENABLED) return;
        Click click = CLICK.get();
        if (click != null) logClient(kind, request, "click", click.at);
        logClient(kind, request, "request", System.nanoTime());
    }

    public static void clientPhase(String kind, UUID request, String phase) {
        if (ENABLED) logClient(kind, request, phase, System.nanoTime());
    }

    private static void logClient(String kind, UUID request, String phase, long at) {
        GTMiaoBlueprint.LOG
            .info("GTMB placement client kind={} request={} phase={} monotonicNs={}", kind, request, phase, at);
    }

    private final String kind;
    private final UUID request;
    private final long admitted;
    private long first, last;
    private int writes;
    private boolean finished;

    private HologramPlacementTiming(String kind, UUID request) {
        this.kind = kind;
        this.request = request;
        admitted = System.nanoTime();
    }

    static HologramPlacementTiming open(String kind, UUID request) {
        return ENABLED ? new HologramPlacementTiming(kind, request) : null;
    }

    void written() {
        long now = System.nanoTime();
        if (writes++ == 0) first = now;
        last = now;
    }

    void finish(String phase, int placed) {
        if (finished) return;
        finished = true;
        GTMiaoBlueprint.LOG.info(
            "GTMB placement server kind={} request={} phase={} placed={} measuredWrites={} admittedNs={} firstWriteNs={} lastWriteNs={} terminalNs={}",
            kind,
            request,
            phase,
            placed,
            writes,
            admitted,
            writes == 0 ? "-" : first,
            writes == 0 ? "-" : last,
            System.nanoTime());
    }
}
