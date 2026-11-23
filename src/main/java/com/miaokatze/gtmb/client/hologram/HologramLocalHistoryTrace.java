// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Optional, one-request numeric observations shared by the local file worker and its owning client tick. */
final class HologramLocalHistoryTrace {

    enum Stage {
        ACCEPTED, IO_ENQUEUED, IO_STARTED, RESTORE_BEGIN, RESTORE_END, SNAPSHOT_BEGIN, SNAPSHOT_END,
        CLIENT_ENQUEUED, CLIENT_STARTED, PREPARATION_READY, RESULT_BEGIN, RESULT_END, PUBLISH_BEGIN, PUBLISH_END
    }

    enum Terminal { PUBLISHED, CANCELLED_OWNER, FAILED, NOT_FULL_REBUILD, NOT_ADMITTED, OBSERVATION_ABORTED }

    enum Stop { COMPLETE, WORK_CAP, TIME_BUDGET }

    final String operation;
    final long sourceRevision, sequence;
    private final long press;
    private final LongSupplier clock;
    private final long[] marks = new long[Stage.values().length];
    private final int[] stopped = new int[Stop.values().length];
    private long elapsedNanos, maxSliceNanos, firstSliceStart = -1, lastSliceEnd = -1;
    private int sliceCount, cellRows, totalWork;
    private Terminal terminal;
    private String certifiedKind;
    private int certifiedRows;

    HologramLocalHistoryTrace(String operation, long sourceRevision, long sequence, long press, LongSupplier clock) {
        this.operation = operation;
        this.sourceRevision = sourceRevision;
        this.sequence = sequence;
        this.press = press;
        this.clock = clock;
        Arrays.fill(marks, -1);
    }

    static boolean isHistory(String operation) {
        return "editUndo".equals(operation) || "editRedo".equals(operation);
    }

    synchronized boolean admit(String op, long revision, long uiSequence, Object wantedSource, Object source,
        Object wantedDraft, Object draft, boolean exactOwner) {
        if (terminal != null || marks[Stage.ACCEPTED.ordinal()] >= 0 || !exactOwner
            || wantedSource == null || source != wantedSource || wantedDraft == null || draft != wantedDraft
            || !operation.equals(op) || sourceRevision != revision || sequence != uiSequence) return false;
        marks[Stage.ACCEPTED.ordinal()] = clock.getAsLong() - press;
        return true;
    }

    static void mark(HologramLocalHistoryTrace trace, Stage stage) {
        if (trace != null) trace.mark(stage);
    }

    static void end(HologramLocalHistoryTrace trace, Terminal terminal) {
        if (trace != null) trace.end(terminal);
    }

    private synchronized void mark(Stage stage) {
        if (terminal == null && marks[stage.ordinal()] == -1) marks[stage.ordinal()] = clock.getAsLong() - press;
    }

    private synchronized void end(Terminal value) {
        if (terminal == null) terminal = value;
    }

    long now() {
        return clock.getAsLong();
    }

    static Stop stop(boolean complete, int work, int cap) {
        // The real loop short-circuits COMPLETE before work cap before its elapsed-time check.
        return complete ? Stop.COMPLETE : work == cap ? Stop.WORK_CAP : Stop.TIME_BUDGET;
    }

    synchronized void slice(long start, long end, int rows, int work, int cap, boolean complete) {
        if (terminal != null) return;
        if (firstSliceStart < 0) firstSliceStart = start - press;
        lastSliceEnd = end - press;
        long elapsed = end - start;
        elapsedNanos += elapsed;
        maxSliceNanos = Math.max(maxSliceNanos, elapsed);
        cellRows += rows;
        totalWork += work;
        stopped[stop(complete, work, cap).ordinal()]++;
        sliceCount++;
    }

    synchronized void certified(boolean header, int rows, long queued, long started, long patchBegin, long patchEnd) {
        if (terminal != null || sliceCount != 0 || marks[Stage.SNAPSHOT_BEGIN.ordinal()] >= 0) return;
        certifiedKind = header ? "HEADER_PROMPT" : "CELL_PROMPT";
        certifiedRows = rows;
        marks[Stage.CLIENT_ENQUEUED.ordinal()] = queued - press;
        marks[Stage.CLIENT_STARTED.ordinal()] = started - press;
        marks[Stage.RESULT_BEGIN.ordinal()] = patchBegin - press;
        marks[Stage.RESULT_END.ordinal()] = patchEnd - press;
    }

    synchronized boolean completeCertified(int expectedRows, boolean header) {
        if (terminal != Terminal.PUBLISHED || !(header ? "HEADER_PROMPT" : "CELL_PROMPT").equals(certifiedKind)
            || certifiedRows != expectedRows || sliceCount != 0 || totalWork != 0 || cellRows != 0) return false;
        long last = 0;
        for (Stage stage : Stage.values()) {
            long at = marks[stage.ordinal()];
            if (stage == Stage.SNAPSHOT_BEGIN || stage == Stage.SNAPSHOT_END || stage == Stage.PREPARATION_READY) {
                if (at != -1) return false;
            } else {
                if (at < last) return false;
                last = at;
            }
        }
        return true;
    }

    synchronized boolean complete(int expectedRows) {
        if (terminal != Terminal.PUBLISHED || sliceCount == 0 || cellRows != expectedRows
            || stopped[Stop.COMPLETE.ordinal()] != 1 || totalWork < cellRows) return false;
        long last = 0;
        for (long at : marks) {
            if (at < last) return false;
            last = at;
        }
        return firstSliceStart >= marks[Stage.PREPARATION_READY.ordinal()] && lastSliceEnd >= firstSliceStart
            && lastSliceEnd <= marks[Stage.RESULT_BEGIN.ordinal()];
    }

    /** Detached metadata only, without any source/player/panel identity, path, payload or per-slice samples. */
    synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", operation);
        result.put("sourceRevision", sourceRevision);
        result.put("expectedRevision", sourceRevision + 1);
        result.put("expectedUiSequence", sequence);
        result.put("terminal", terminal == null ? "ACTIVE" : terminal.name());
        if (certifiedKind != null) {
            result.put("certifiedDelta", certifiedKind);
            result.put("retainedRows", certifiedRows);
        }
        var stages = new LinkedHashMap<String, Long>();
        for (Stage s : Stage.values()) if (marks[s.ordinal()] >= 0) stages.put(s.name(), marks[s.ordinal()]);
        result.put("nanosAfterNativePress", stages);
        var gui = new LinkedHashMap<String, Object>();
        result.put("guiPreparation", gui);
        gui.put("slices", sliceCount);
        gui.put("cellsPrepared", cellRows);
        gui.put("nonCellWorkUnits", totalWork - cellRows);
        gui.put("totalWorkUnits", totalWork);
        gui.put("workCapSlices", stopped[Stop.WORK_CAP.ordinal()]);
        gui.put("timeBudgetSlices", stopped[Stop.TIME_BUDGET.ordinal()]);
        gui.put("completeSlices", stopped[Stop.COMPLETE.ordinal()]);
        gui.put("stepNanos", elapsedNanos);
        gui.put("maxStepNanos", maxSliceNanos);
        if (sliceCount > 0) {
            gui.put("firstSliceNanosAfterPress", firstSliceStart);
            gui.put("lastSliceEndNanosAfterPress", lastSliceEnd);
            gui.put("betweenSliceNanos", lastSliceEnd - firstSliceStart - elapsedNanos);
        }
        return result;
    }
}
