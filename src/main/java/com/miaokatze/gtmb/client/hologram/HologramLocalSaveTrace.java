// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Passive, opt-in phase timestamps on one admitted local Save Current task. Not a gameplay or logging channel. */
final class HologramLocalSaveTrace {

    static final String FLAG = "gtmb.hologram.localSaveTrace";
    private static final boolean ENABLED = Boolean.getBoolean(FLAG);

    enum Stage {
        ADMITTED,
        MATERIALIZE_IO_QUEUED, MATERIALIZE_IO_BEGIN, MATERIALIZE_IO_DONE, MATERIALIZE_CLIENT_BEGIN,
        MATERIALIZE_CLIENT_DONE,
        DRAFT_IO_QUEUED, JSON_FREEZE_BEGIN, JSON_READY, CAPTURE_INPUT_READY,
        CAPTURE_CLIENT_BEGIN, CAPTURE_RENDER_QUEUED, RENDER_END_POLL, RENDER_STATE_READY,
        NATIVE_SUBMIT_BEGIN, NATIVE_SUBMIT_CHECKED, GL_READBACK_BEGIN, GL_READBACK_DONE, RENDER_IMAGE_READY,
        PNG_IO_QUEUED, PNG_ENCODE_BEGIN, PNG_READY, BOUND_PAIR_READY,
        PAIR_INPUT_READY, TARGET_BEGIN, TARGET_READY, STAGED_WRITE_BEGIN, STAGED_WRITE_DONE,
        READBACK_READY, INDEX_READY, PAIR_COMMIT_BEGIN, PAIR_COMMIT_DONE,
        ACK_CLIENT_BEGIN, ACK_PUBLISHED
    }

    enum Terminal {
        PUBLISHED, FAILED, CANCELLED_OWNER
    }

    private final LongSupplier clock;
    private final long origin;
    private final long[] marks = new long[Stage.values().length];
    private int lastStage;
    private long lastNanos;
    private Terminal terminal;
    private long terminalAt = -1;

    static HologramLocalSaveTrace begin(boolean saveCurrent) {
        return begin(ENABLED, saveCurrent, System::nanoTime);
    }

    static HologramLocalSaveTrace begin(boolean enabled, boolean saveCurrent, LongSupplier clock) {
        return enabled && saveCurrent ? new HologramLocalSaveTrace(clock) : null;
    }

    private HologramLocalSaveTrace(LongSupplier clock) {
        this.clock = clock;
        origin = clock.getAsLong();
        Arrays.fill(marks, -1);
        marks[Stage.ADMITTED.ordinal()] = 0;
    }

    static void mark(HologramLocalSaveTrace trace, Stage stage) {
        if (trace != null) trace.mark(stage);
    }

    static void end(HologramLocalSaveTrace trace, Terminal outcome) {
        if (trace != null) trace.end(outcome);
    }

    private synchronized void mark(Stage stage) {
        int index = stage.ordinal();
        if (terminal != null || index <= lastStage) return;
        lastNanos = Math.max(lastNanos, clock.getAsLong() - origin);
        marks[index] = lastNanos;
        lastStage = index;
    }

    private synchronized void end(Terminal outcome) {
        if (terminal != null) return;
        terminal = outcome;
        terminalAt = Math.max(lastNanos, clock.getAsLong() - origin);
    }

    /** Detached scalars only. No logical ID, path, names, error detail, or live/client references. */
    synchronized Map<String, Object> snapshot() {
        var phases = new LinkedHashMap<String, Long>();
        for (Stage stage : Stage.values()) {
            long at = marks[stage.ordinal()];
            if (at >= 0) phases.put(stage.name(), at);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", "gtmb.localSaveTrace.v1");
        result.put("stageNanosFromAdmission", phases);
        result.put("lastStage", Stage.values()[lastStage].name());
        result.put("terminal", terminal == null ? "ACTIVE" : terminal.name());
        if (terminalAt >= 0) result.put("terminalNanosFromAdmission", terminalAt);
        return result;
    }
}
