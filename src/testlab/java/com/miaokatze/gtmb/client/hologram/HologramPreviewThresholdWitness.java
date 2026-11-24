// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

/** Measures a single real-pane action without polling renderer caches for readiness. */
final class HologramPreviewThresholdWitness {

    static final long WORKBENCH_NANOS = 10_000_000L;
    static final long BLUEPRINT_FIRST_NANOS = 20_000_000L;
    static final long BLUEPRINT_COMPLETE_NANOS = 200_000_000L;
    static final long SOLAR_COMPLETE_NANOS = 50_000_000L;

    private final int expectedCells;
    private long started, lastDraw;
    private Map<String, Object> first, complete;
    private long firstAt, completeAt;

    HologramPreviewThresholdWitness(int expectedCells) {
        this.expectedCells = expectedCells;
    }

    static boolean enabled() {
        return Boolean.getBoolean("gtmb.testlab.requirePreviewThresholds");
    }

    void start(long at) {
        started = at;
    }

    void observe(HologramState expected, MiaoVisonLiteRenderer.MaterialPresentation drawn) {
        if (started == 0 || expected == null
            || drawn == null
            || drawn.state() != expected
            || drawn.drawnAtNanos() < started
            || drawn.serial() <= lastDraw
            || !drawn.bodyMaterial()) return;
        lastDraw = drawn.serial();
        if (first == null) {
            firstAt = drawn.drawnAtNanos();
            first = frame(drawn);
        }
        if (complete == null && drawn.complete()
            && !expected.data.getBoolean("displayPartial")
            && (expectedCells <= 0 || drawn.submittedCells() == expectedCells)
            && drawn.submittedCells() == expected.cells.size()) {
            completeAt = drawn.drawnAtNanos();
            complete = frame(drawn);
        }
    }

    boolean sawMaterial() {
        return first != null;
    }

    boolean sawComplete() {
        return complete != null;
    }

    long firstNanos() {
        return firstAt - started;
    }

    long completeNanos() {
        return completeAt - started;
    }

    Map<String, Object> receipt(long firstLimit, long completeLimit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpoint", "actual body material submission in the exact selected pane");
        result.put("expectedCells", expectedCells);
        if (first != null) result.put("first", first);
        if (complete != null) result.put("complete", complete);
        if (firstLimit > 0) {
            result.put("firstLimitNanosExclusive", firstLimit);
            result.put("firstPassed", first != null && firstNanos() < firstLimit);
        }
        if (completeLimit > 0) {
            result.put("completeLimitNanosExclusive", completeLimit);
            result.put("completePassed", complete != null && completeNanos() < completeLimit);
        }
        return result;
    }

    private Map<String, Object> frame(MiaoVisonLiteRenderer.MaterialPresentation drawn) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elapsedNanos", drawn.drawnAtNanos() - started);
        result.put("serial", drawn.serial());
        result.put("session", drawn.state().session);
        result.put("generation", drawn.state().data.getString("generation"));
        result.put("submittedCells", drawn.submittedCells());
        result.put("full", drawn.complete());
        return result;
    }
}
