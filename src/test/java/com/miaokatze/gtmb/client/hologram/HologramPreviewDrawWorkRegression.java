package com.miaokatze.gtmb.client.hologram;

import java.util.function.LongSupplier;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Deterministic scalar contracts only; no OpenGL work or machine-speed assertions. */
public final class HologramPreviewDrawWorkRegression {

    private HologramPreviewDrawWorkRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramPreviewDrawWorkRegression.class, args)) return;
        run();
        System.out.println("HologramPreviewDrawWorkRegression PASS");
    }

    public static void run() {
        inspectionKeysAreDistinct();
        noWorkAndDetachedInspection();
        phasesUseOnlyBoundaryClocks();
        exactCountsAndSmoke();
        resetAndIsolation();
        saturatingCountersAndClockWrap();
    }

    private static void inspectionKeysAreDistinct() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (HologramPreviewDrawWork.Phase phase : HologramPreviewDrawWork.Phase.values()) {
            SimpleAssert.that(keys.add(phase.nanosKey), "unique phase timing key " + phase.nanosKey);
            SimpleAssert.that(keys.add(phase.callsKey), "unique phase invocation key " + phase.callsKey);
        }
        for (String key : new String[] { "drawFacesVisited", "drawQuads", "drawOutlineLineVertices", "drawNativeCalls",
            "drawLinkedDrawCalls", "drawSmokeActive", "drawSmokeFrames" }) {
            SimpleAssert.that(keys.add(key), "work counters must not overwrite phase keys: " + key);
        }
    }

    private static void noWorkAndDetachedInspection() {
        FakeClock clock = new FakeClock();
        HologramPreviewDrawWork work = new HologramPreviewDrawWork(clock);
        NBTTagCompound first = snapshot(work);
        for (HologramPreviewDrawWork.Phase phase : HologramPreviewDrawWork.Phase.values()) {
            check(first, phase.nanosKey, 0);
            check(first, phase.callsKey, 0);
            work.endPhase(phase);
        }
        for (String key : new String[] { "drawFacesVisited", "drawQuads", "drawOutlineLineVertices", "drawNativeCalls",
            "drawLinkedDrawCalls", "drawSmokeFrames" }) check(first, key, 0);
        SimpleAssert.that(!first.getBoolean("drawSmokeActive"), "no work means no smoke frame");
        first.setLong("drawQuads", 100);
        check(snapshot(work), "drawQuads", 0);
        NBTTagCompound existing = new NBTTagCompound();
        existing.setLong("releaseEpoch", 7);
        existing.setLong("drawNanos", 11);
        existing.setLong("frames", 13);
        work.appendInspection(existing);
        check(existing, "releaseEpoch", 7);
        check(existing, "drawNanos", 11);
        check(existing, "frames", 13);
        work.reset();
        SimpleAssert.eq(0, clock.reads, "inspection, reset and unmatched end do not read the clock");
    }

    private static void phasesUseOnlyBoundaryClocks() {
        FakeClock clock = new FakeClock();
        HologramPreviewDrawWork work = new HologramPreviewDrawWork(clock);
        int phases = 0;
        for (HologramPreviewDrawWork.Phase phase : HologramPreviewDrawWork.Phase.values()) {
            work.beginPhase(phase);
            work.beginPhase(phase);
            clock.now += ++phases;
            work.recordFacesVisited(4);
            work.recordQuads(3);
            work.recordLineVertices(24);
            work.recordNativeCalls(1);
            work.recordLinkedCalls(1);
            work.endPhase(phase);
            work.endPhase(phase);
            check(snapshot(work), phase.nanosKey, phases);
            check(snapshot(work), phase.callsKey, 1);
        }
        SimpleAssert.eq(phases * 2, clock.reads, "exactly two clock reads per coarse phase");
        check(snapshot(work), "drawLinkedCalls", 1);
        check(snapshot(work), "drawLinkedDrawCalls", phases);
        work.beginPhase(HologramPreviewDrawWork.Phase.DEMANDS);
        clock.now += 10;
        work.endPhase(HologramPreviewDrawWork.Phase.DEMANDS);
        check(snapshot(work), "drawDemandsNanos", 11);
        check(snapshot(work), "drawDemandsCalls", 2);
    }

    private static void exactCountsAndSmoke() {
        FakeClock clock = new FakeClock();
        HologramPreviewDrawWork work = new HologramPreviewDrawWork(clock);
        work.recordFacesVisited(7);
        work.recordFacesVisited(2);
        work.recordQuads(3);
        work.recordQuads(2);
        work.recordLineVertices(24);
        work.recordLineVertices(2);
        work.recordNativeCalls(4);
        work.recordLinkedCalls(2);
        work.recordSmokeActive(true);
        work.recordSmokeActive(true);
        NBTTagCompound enabled = snapshot(work);
        check(enabled, "drawFacesVisited", 9);
        check(enabled, "drawQuads", 5);
        check(enabled, "drawOutlineLineVertices", 26);
        check(enabled, "drawNativeCalls", 4);
        check(enabled, "drawLinkedDrawCalls", 2);
        check(enabled, "drawSmokeFrames", 2);
        SimpleAssert.that(enabled.getBoolean("drawSmokeActive"), "actual latest frame used smoke capture");
        work.recordSmokeActive(false);
        NBTTagCompound disabled = snapshot(work);
        check(disabled, "drawSmokeFrames", 2);
        SimpleAssert.that(!disabled.getBoolean("drawSmokeActive"), "latest frame can turn smoke off");
        SimpleAssert.that(enabled.getBoolean("drawSmokeActive"), "older inspection is detached");
        SimpleAssert.eq(0, clock.reads, "all count updates avoid clock reads");
    }

    private static void resetAndIsolation() {
        FakeClock clock = new FakeClock();
        HologramPreviewDrawWork work = new HologramPreviewDrawWork(clock);
        HologramPreviewDrawWork other = new HologramPreviewDrawWork(clock);
        other.recordQuads(23);
        work.recordFacesVisited(7);
        work.recordQuads(9);
        work.recordLineVertices(24);
        work.recordNativeCalls(1);
        work.recordLinkedCalls(1);
        work.recordSmokeActive(true);
        for (HologramPreviewDrawWork.Phase phase : HologramPreviewDrawWork.Phase.values()) work.beginPhase(phase);
        work.reset();
        int reads = clock.reads;
        clock.now += 100;
        for (HologramPreviewDrawWork.Phase phase : HologramPreviewDrawWork.Phase.values()) work.endPhase(phase);
        SimpleAssert.eq(reads, clock.reads, "stale phase ends cannot enter the next epoch");
        NBTTagCompound reset = snapshot(work);
        NBTTagCompound empty = snapshot(new HologramPreviewDrawWork(clock));
        SimpleAssert.eq(empty, reset, "release resets every scalar and open phase");
        check(snapshot(other), "drawQuads", 23);
        work.beginPhase(HologramPreviewDrawWork.Phase.OVERLAY);
        clock.now += 6;
        work.endPhase(HologramPreviewDrawWork.Phase.OVERLAY);
        check(snapshot(work), "drawOverlayNanos", 6);
        check(snapshot(work), "drawOverlayCalls", 1);
    }

    private static void saturatingCountersAndClockWrap() {
        FakeClock clock = new FakeClock();
        HologramPreviewDrawWork work = new HologramPreviewDrawWork(clock);
        for (long count : new long[] { -1, 0, Long.MAX_VALUE, 1 }) {
            work.recordFacesVisited(count);
            work.recordQuads(count);
            work.recordLineVertices(count);
            work.recordNativeCalls(count);
            work.recordLinkedCalls(count);
        }
        NBTTagCompound full = snapshot(work);
        for (String key : new String[] { "drawFacesVisited", "drawQuads", "drawOutlineLineVertices", "drawNativeCalls",
            "drawLinkedDrawCalls" }) check(full, key, Long.MAX_VALUE);
        work.beginPhase(HologramPreviewDrawWork.Phase.DEMANDS);
        clock.now = Long.MAX_VALUE;
        work.endPhase(HologramPreviewDrawWork.Phase.DEMANDS);
        work.beginPhase(HologramPreviewDrawWork.Phase.DEMANDS);
        clock.now = Long.MIN_VALUE + 4;
        work.endPhase(HologramPreviewDrawWork.Phase.DEMANDS);
        check(snapshot(work), "drawDemandsNanos", Long.MAX_VALUE);
        clock.now = Long.MAX_VALUE - 2;
        work.beginPhase(HologramPreviewDrawWork.Phase.FACES_AND_HIT);
        clock.now = Long.MIN_VALUE + 2;
        work.endPhase(HologramPreviewDrawWork.Phase.FACES_AND_HIT);
        check(snapshot(work), "drawFacesAndHitNanos", 5);
        clock.now = 10;
        work.beginPhase(HologramPreviewDrawWork.Phase.OUTLINES);
        clock.now = 5;
        work.endPhase(HologramPreviewDrawWork.Phase.OUTLINES);
        check(snapshot(work), "drawOutlinesNanos", 0);
    }

    private static NBTTagCompound snapshot(HologramPreviewDrawWork work) {
        NBTTagCompound result = new NBTTagCompound();
        work.appendInspection(result);
        return result;
    }

    private static void check(NBTTagCompound value, String key, long expected) {
        SimpleAssert.eq(expected, value.getLong(key), "draw work scalar " + key);
    }

    private static final class FakeClock implements LongSupplier {

        long now;
        int reads;

        @Override
        public long getAsLong() {
            reads++;
            return now;
        }
    }
}
