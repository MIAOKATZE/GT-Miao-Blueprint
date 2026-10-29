package com.miaokatze.gtmb.client.hologram;

import java.util.function.LongSupplier;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Constant-space, render-thread-only draw diagnostics. These are cumulative CPU wall times, including
 * time spent in driver calls, never GPU timings. The renderer owns the release epoch and calls reset
 * when that epoch changes. No source cells, native geometry, or per-frame history are retained here.
 */
final class HologramPreviewDrawWork {

    enum Phase {

        DEMANDS("drawDemands"),
        FACES_AND_HIT("drawFacesAndHit"),
        NATIVE_PLAYBACK("drawNativePlayback"),
        LINKED("drawLinked"),
        OUTLINES("drawOutlines"),
        OVERLAY("drawOverlay");

        final String nanosKey, callsKey;

        Phase(String prefix) {
            nanosKey = prefix + "Nanos";
            callsKey = prefix + "Calls";
        }
    }

    private static final Phase[] PHASES = Phase.values();
    private final LongSupplier clock;
    private final long[] started = new long[PHASES.length];
    private final long[] nanos = new long[PHASES.length];
    private final long[] calls = new long[PHASES.length];
    private final boolean[] active = new boolean[PHASES.length];
    private long facesVisited, quads, lineVertices, nativeCalls, linkedCalls, smokeFrames;
    private boolean smokeActive;

    HologramPreviewDrawWork() {
        this(System::nanoTime);
    }

    HologramPreviewDrawWork(LongSupplier clock) {
        if (clock == null) throw new NullPointerException("clock");
        this.clock = clock;
    }

    /** Coarse phase boundaries only; never call this per face, cell, quad, or vertex. */
    void beginPhase(Phase phase) {
        int index = phase.ordinal();
        // A repeated begin cannot erase an unfinished measurement or inflate its invocation count.
        if (active[index]) return;
        started[index] = clock.getAsLong();
        active[index] = true;
        calls[index] = add(calls[index], 1);
    }

    void endPhase(Phase phase) {
        int index = phase.ordinal();
        if (!active[index]) return;
        long elapsed = clock.getAsLong() - started[index];
        active[index] = false;
        nanos[index] = add(nanos[index], elapsed);
    }

    /** Count the faces actually reached by the draw/picking loop, not the source's cell count. */
    void recordFacesVisited(long count) {
        facesVisited = add(facesVisited, count);
    }

    /** Count submitted sample quads, including layered samples, not merely drawable faces. */
    void recordQuads(long count) {
        quads = add(quads, count);
    }

    /** Count voxel-outline line vertices only, excluding structure/hint overlays; aggregate without clock reads. */
    void recordLineVertices(long count) {
        lineVertices = add(lineVertices, count);
    }

    /** Native draw invocations, not guaranteed visible pixels, lists, or successful native renders. */
    void recordNativeCalls(long count) {
        nativeCalls = add(nativeCalls, count);
    }

    void recordLinkedCalls(long count) {
        linkedCalls = add(linkedCalls, count);
    }

    /** Sample once per frame using the actual smoke frame (raster != null), not a property per cell. */
    void recordSmokeActive(boolean enabled) {
        smokeActive = enabled;
        if (enabled) smokeFrames = add(smokeFrames, 1);
    }

    /** Append only new scalar keys; existing renderer totals and epoch semantics are untouched. */
    void appendInspection(NBTTagCompound result) {
        for (Phase phase : PHASES) {
            int index = phase.ordinal();
            result.setLong(phase.nanosKey, nanos[index]);
            result.setLong(phase.callsKey, calls[index]);
        }
        result.setLong("drawFacesVisited", facesVisited);
        result.setLong("drawQuads", quads);
        result.setLong("drawOutlineLineVertices", lineVertices);
        result.setLong("drawNativeCalls", nativeCalls);
        result.setLong("drawLinkedDrawCalls", linkedCalls);
        result.setBoolean("drawSmokeActive", smokeActive);
        result.setLong("drawSmokeFrames", smokeFrames);
    }

    /** Abandon any open timings so a stale end cannot charge work to the next release epoch. */
    void reset() {
        for (int index = 0; index < PHASES.length; index++) {
            started[index] = nanos[index] = calls[index] = 0;
            active[index] = false;
        }
        facesVisited = quads = lineVertices = nativeCalls = linkedCalls = smokeFrames = 0;
        smokeActive = false;
    }

    private static long add(long value, long increment) {
        if (increment <= 0) return value;
        return increment > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + increment;
    }
}
