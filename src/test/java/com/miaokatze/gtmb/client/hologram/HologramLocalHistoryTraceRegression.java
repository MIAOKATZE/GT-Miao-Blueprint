// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.miaokatze.gtmb.client.hologram.HologramLocalHistoryTrace.Stage;
import com.miaokatze.gtmb.client.hologram.HologramLocalHistoryTrace.Stop;
import com.miaokatze.gtmb.client.hologram.HologramLocalHistoryTrace.Terminal;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramLocalHistoryTraceRegression {

    private HologramLocalHistoryTraceRegression() {}

    public static void run() {
        var now = new AtomicLong(1_000);
        var source = new Object();
        var draft = new Object();
        var saved = new HologramLocalHistoryTrace("editUndo", 2, 3, 1_000, now::incrementAndGet);
        SimpleAssert.that(
            HologramGiantHistoryTiming.optional(false, true) == null
                && HologramGiantHistoryTiming.optional(true, false) == null,
            "disabled or different profile never arms");
        SimpleAssert.that(
            !HologramLocalHistoryTrace.isHistory("editCellFlags") && HologramLocalHistoryTrace.isHistory("editRedo"),
            "only the two real history routes");
        SimpleAssert.that(!saved.admit("editRedo", 2, 3, source, source, draft, draft, true), "wrong action");
        SimpleAssert.that(!saved.admit("editUndo", 1, 3, source, source, draft, draft, true), "stale source revision");
        SimpleAssert.that(!saved.admit("editUndo", 2, 4, source, source, draft, draft, true), "foreign sequence");
        SimpleAssert.that(!saved.admit("editUndo", 2, 3, source, new Object(), draft, draft, true), "foreign source");
        SimpleAssert.that(!saved.admit("editUndo", 2, 3, source, source, draft, new Object(), true), "foreign draft");
        SimpleAssert.that(!saved.admit("editUndo", 2, 3, source, source, draft, draft, false), "lost owner");
        SimpleAssert.that(
            saved.admit("editUndo", 2, 3, source, source, draft, draft, true)
                && !saved.admit("editUndo", 2, 3, source, source, draft, draft, true),
            "exact admission consumed once");
        for (Stage stage : Stage.values())
            if (stage != Stage.ACCEPTED && stage.ordinal() <= Stage.PREPARATION_READY.ordinal())
                HologramLocalHistoryTrace.mark(saved, stage);
        now.set(1_080);
        saved.slice(1_040, 1_043, 512, 512, 512, false);
        saved.slice(1_050, 1_060, 15, 17, 512, false);
        saved.slice(1_070, 1_080, 0, 0, 512, true);
        for (Stage stage : Stage.values())
            if (stage.ordinal() > Stage.PREPARATION_READY.ordinal()) HologramLocalHistoryTrace.mark(saved, stage);
        HologramLocalHistoryTrace.end(saved, Terminal.PUBLISHED);
        saved.slice(1_081, 1_083, 10, 10, 512, true); // closed receipt is immutable
        SimpleAssert.that(saved.complete(527) && !saved.complete(526), "exact row count, complete once");
        Map<?, ?> gui = (Map<?, ?>) saved.snapshot()
            .get("guiPreparation");
        SimpleAssert.eq(527, gui.get("cellsPrepared"), "row aggregate");
        SimpleAssert.eq(2, gui.get("nonCellWorkUnits"), "other aggregate");
        SimpleAssert.eq(23L, gui.get("stepNanos"), "pure slice time");
        SimpleAssert.eq(17L, gui.get("betweenSliceNanos"), "scheduling intervals separate from work");
        SimpleAssert.eq(1, gui.get("workCapSlices"), "one capped slice");
        SimpleAssert.eq(1, gui.get("timeBudgetSlices"), "one time slice");
        SimpleAssert.eq(1, gui.get("completeSlices"), "one completed slice even with zero new rows");
        SimpleAssert.eq(Stop.COMPLETE, HologramLocalHistoryTrace.stop(true, 512, 512), "completion wins precedence");
        SimpleAssert.eq(Stop.WORK_CAP, HologramLocalHistoryTrace.stop(false, 512, 512), "cap before time predicate");
        var cancelled = new HologramLocalHistoryTrace("editRedo", 5, 6, 1_000, now::incrementAndGet);
        HologramLocalHistoryTrace.end(cancelled, Terminal.CANCELLED_OWNER);
        HologramLocalHistoryTrace.end(cancelled, Terminal.PUBLISHED);
        SimpleAssert.that(
            !cancelled.admit("editRedo", 5, 6, source, source, draft, draft, true) && !cancelled.complete(0),
            "stale owner cannot renew or manufacture publication");
        SimpleAssert.eq(
            "CANCELLED_OWNER",
            cancelled.snapshot()
                .get("terminal"),
            "first terminal immutable");
        SimpleAssert.that(
            HologramGiantHistoryTiming.actualFrame(source, source, 4, 4, 1, 7, 8)
                && !HologramGiantHistoryTiming.actualFrame(source, draft, 4, 4, 1, 7, 8)
                && !HologramGiantHistoryTiming.actualFrame(source, source, 4, 3, 1, 7, 8)
                && !HologramGiantHistoryTiming.actualFrame(source, source, 4, 4, 1, 9, 8),
            "no unrelated state, previous serial, or unsynchronized post frame");
        var fast = new HologramLocalHistoryTrace("editRedo", 5, 6, 1_000, now::incrementAndGet);
        SimpleAssert.that(fast.admit("editRedo", 5, 6, source, source, draft, draft, true), "certificate arm");
        for (Stage stage : Stage.values())
            if (stage.ordinal() > Stage.ACCEPTED.ordinal() && stage.ordinal() <= Stage.RESTORE_END.ordinal())
                HologramLocalHistoryTrace.mark(fast, stage);
        long queued = now.incrementAndGet(), begin = now.incrementAndGet(), patchBegin = now.incrementAndGet(),
            patchEnd = now.incrementAndGet();
        fast.certified(false, 527, queued, begin, patchBegin, patchEnd);
        HologramLocalHistoryTrace.mark(fast, Stage.PUBLISH_BEGIN);
        HologramLocalHistoryTrace.mark(fast, Stage.PUBLISH_END);
        HologramLocalHistoryTrace.end(fast, Terminal.PUBLISHED);
        SimpleAssert.that(
            fast.completeCertified(527, false) && !fast.completeCertified(527, true)
                && !fast.completeCertified(526, false)
                && !fast.complete(527),
            "certified delta retains exact kind and rows and never claims a full preparation");
        SimpleAssert.eq(
            "CELL_PROMPT",
            fast.snapshot()
                .get("certifiedDelta"),
            "separate certificate receipt");
        System.out.println(
            "GTMB giant history timing PASS: one owned request, exact slices, cancellation and actual frame fences");
    }
}
