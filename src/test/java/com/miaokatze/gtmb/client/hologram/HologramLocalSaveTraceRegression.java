// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.Stage;
import com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.Terminal;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Save request trace behavior without Minecraft, GL, disk I/O, sleeps or real-clock assertions. */
public final class HologramLocalSaveTraceRegression {

    private HologramLocalSaveTraceRegression() {}

    public static void run() {
        SimpleAssert.eq(null, HologramLocalSaveTrace.begin(false, true, () -> {
            throw new AssertionError("Disabled trace read the clock");
        }), "disabled path creates no trace");
        SimpleAssert.eq(null, HologramLocalSaveTrace.begin(true, false, () -> {
            throw new AssertionError("Unrelated save read the clock");
        }), "no trace for Save As or unrelated private writes");
        HologramLocalSaveTrace.mark(null, Stage.JSON_READY);
        HologramLocalSaveTrace.end(null, Terminal.FAILED);

        AtomicLong clock = new AtomicLong(17);
        var a = HologramLocalSaveTrace.begin(true, true, clock::get);
        at(a, clock, Stage.MATERIALIZE_IO_QUEUED, 19);
        at(a, clock, Stage.MATERIALIZE_IO_BEGIN, 22);
        at(a, clock, Stage.MATERIALIZE_IO_DONE, 59);
        clock.set(100);
        var b = HologramLocalSaveTrace.begin(true, true, clock::get);
        at(a, clock, Stage.MATERIALIZE_CLIENT_BEGIN, 101);
        at(a, clock, Stage.MATERIALIZE_CLIENT_DONE, 102);
        at(b, clock, Stage.DRAFT_IO_QUEUED, 103); // same JVM clock, different origin/owner
        at(b, clock, Stage.JSON_FREEZE_BEGIN, 105);
        at(b, clock, Stage.JSON_READY, 104); // injected backwards tick is clamped
        at(b, clock, Stage.JSON_READY, 500); // duplicate cannot overwrite original sample
        at(b, clock, Stage.MATERIALIZE_IO_QUEUED, 600); // stale callback cannot fill an already-passed stage
        var active = b.snapshot();
        SimpleAssert.eq("ACTIVE", active.get("terminal"), "partial trace does not report an outcome");
        SimpleAssert.eq(5L, stages(active).get("JSON_READY"), "monotonic time despite test clock");
        SimpleAssert.that(!stages(active).containsKey("MATERIALIZE_IO_QUEUED"), "no false materialization for eager arm");
        HologramLocalSaveTrace.end(b, Terminal.CANCELLED_OWNER);
        at(b, clock, Stage.ACK_PUBLISHED, 700);
        HologramLocalSaveTrace.end(b, Terminal.PUBLISHED);
        var canceled = b.snapshot();
        SimpleAssert.eq("CANCELLED_OWNER", canceled.get("terminal"), "late work cannot convert gone owner to success");
        SimpleAssert.eq(500L, canceled.get("terminalNanosFromAdmission"), "duration follows exact admitted task");
        SimpleAssert.eq("JSON_READY", canceled.get("lastStage"), "canceled work has no invented later timing");
        SimpleAssert.eq("ACTIVE", active.get("terminal"), "snapshots are detached");

        at(a, clock, Stage.PAIR_COMMIT_DONE, 800);
        var committed = a.snapshot();
        SimpleAssert.eq("ACTIVE", committed.get("terminal"), "disk commit is not the client ACK");
        at(a, clock, Stage.ACK_CLIENT_BEGIN, 810);
        at(a, clock, Stage.ACK_PUBLISHED, 820);
        HologramLocalSaveTrace.end(a, Terminal.PUBLISHED);
        HologramLocalSaveTrace.end(a, Terminal.FAILED);
        var published = a.snapshot();
        SimpleAssert.eq("PUBLISHED", published.get("terminal"), "first owned terminal is stable");
        SimpleAssert.eq(783L, stages(published).get("PAIR_COMMIT_DONE"), "same clock across independent tasks");
        SimpleAssert.eq(803L, stages(published).get("ACK_PUBLISHED"), "true terminal publication, not disk completion");
        SimpleAssert.eq(5, published.size(), "fixed receipt schema without identity or data");
        SimpleAssert.eq(4, active.size(), "partial receipt schema");
        SimpleAssert.that(stages(published).size() <= Stage.values().length, "receipt bounded by stages");
        System.out.println("GTMB opt-in request-owned local Save phase trace contract PASS");
    }

    private static Map<?, ?> stages(Map<String, Object> receipt) {
        return (Map<?, ?>) receipt.get("stageNanosFromAdmission");
    }

    private static void at(HologramLocalSaveTrace trace, AtomicLong clock, Stage stage, long now) {
        clock.set(now);
        HologramLocalSaveTrace.mark(trace, stage);
    }
}
