package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Executable task lifetime tests; no source-text matching or GUI implementation mirrors. */
public final class HologramBackgroundTasksRegression {

    private HologramBackgroundTasksRegression() {}

    public static void run() {
        List<String> events = new ArrayList<>();
        HologramTaskOwner owner = new HologramTaskOwner(new HologramTaskOwner.Notifications() {

            @Override
            public void background(String label) {
                events.add("background:" + label);
            }

            @Override
            public void finished(String label, HologramTaskOwner.Result result, String detail) {
                events.add(result + ":" + label + ":" + detail);
            }
        });
        long download = owner.begin("library", "download-1", "download");
        SimpleAssert.that(download > 0 && owner.busy(), "IO owns the projector before receiving its first ACK");
        owner.detach();
        owner.detach();
        SimpleAssert.eq(1, events.size(), "closing twice announces the same background operation once");
        owner.attach();
        SimpleAssert.that(!owner.detached(), "reopen attaches presentation without starting another worker");
        SimpleAssert.eq(download, owner.begin("library", "download-1", "download"), "same request retains its token");
        SimpleAssert.eq("download-1", owner.key(), "reopen retains the original task context identity");
        owner.detach();
        SimpleAssert.eq(1, events.size(), "reopen and close again do not repeat the background announcement");
        SimpleAssert.eq(0L, owner.begin("server", "job-2", "build"), "a second operation cannot steal IO ownership");
        SimpleAssert.that(
            !owner.finish(download + 1, HologramTaskOwner.Result.SUCCESS, "early ACK"),
            "an unrelated acknowledgement cannot finish the download");
        SimpleAssert.that(owner.busy(), "closing UI and receiving an unrelated ACK keep the task running");
        owner.finish(download, HologramTaskOwner.Result.SUCCESS, "disk and server committed");
        SimpleAssert.eq(2, events.size(), "the authoritative completion emits one final notification");
        SimpleAssert.that(
            !owner.finish(download, HologramTaskOwner.Result.SUCCESS, "duplicate snapshot"),
            "repeated completion is ignored");
        SimpleAssert.eq(
            0L,
            owner.begin("library", "download-1", "download"),
            "old running snapshots cannot revive a finished task");
        long build = owner.begin("server", "job-2", "build");
        SimpleAssert.that(build > download, "tokens monotonically distinguish replacement operations");
        SimpleAssert.that(
            !owner.finish(download, HologramTaskOwner.Result.FAILURE, "late old failure"),
            "an old failure cannot unlock a new task");
        SimpleAssert.that(owner.busy(), "new task remains owned after an old terminal receipt");
        SimpleAssert
            .that(owner.advance("server", "job-2", "session-B:21"), "pause advances the accepted command receipt");
        SimpleAssert.eq(build, owner.token(), "pausing keeps the same operation owner");
        SimpleAssert.that(
            !owner.finishReceipt("server", "job-2", HologramTaskOwner.Result.SUCCESS, "old build ACK"),
            "old command completion cannot finish a paused task");
        SimpleAssert
            .that(owner.advance("server", "session-B:21", "session-B:22"), "resume advances to its new uiSequence");
        SimpleAssert.that(
            !owner.finishReceipt("server", "session-B:21", HologramTaskOwner.Result.SUCCESS, "late pause ACK"),
            "late pause receipt cannot complete resumed work");
        SimpleAssert.that(
            owner.advance("server", "session-B:22", "session-B:23"),
            "cancel waits for its exact terminal sequence");
        SimpleAssert.that(
            !owner.finishReceipt("server", "session-B:22", HologramTaskOwner.Result.SUCCESS, "late resume ACK"),
            "late resume completion cannot race the cancellation receipt");
        SimpleAssert.that(
            owner.finishReceipt("server", "session-B:23", HologramTaskOwner.Result.CANCELLED, "explicit cancel"),
            "the actual new cancellation receipt unlocks the owner");
        SimpleAssert.that(
            !owner.busy() && events.get(2)
                .startsWith("CANCELLED:"),
            "explicit cancellation is distinct from detaching the presentation");
        SimpleAssert.eq(
            0L,
            owner.begin("server", "session-B:21", "late paused snapshot"),
            "retired pause aliases cannot reopen cancelled work");
        SimpleAssert.eq(
            0L,
            owner.begin("server", "session-B:22", "late resumed snapshot"),
            "retired resume aliases cannot reacquire the projector");
        long disconnected = owner.begin("server", "job-3", "capture");
        owner.detach();
        owner.finish(disconnected, HologramTaskOwner.Result.FAILURE, "connection closed");
        SimpleAssert.that(
            !owner.busy() && events.get(4)
                .contains("connection closed"),
            "disconnect releases ownership with failure instead of claiming cross-connection persistence");
        owner.finish(disconnected, HologramTaskOwner.Result.SUCCESS, "late success");
        SimpleAssert.eq(5, events.size(), "late completion after disconnect produces no success sound or notification");
        long resumed = owner.begin("server", "session-C:30", "resumed build");
        owner.advance("server", "session-C:30", "session-C:31");
        owner.advance("server", "session-C:31", "session-C:32");
        SimpleAssert
            .that(owner.token() == resumed && owner.busy(), "pause and resume preserve the original active job");
        SimpleAssert.that(
            !owner.finishReceipt("server", "session-C:31", HologramTaskOwner.Result.SUCCESS, "stale"),
            "old paused completion cannot unlock resumed construction");
        SimpleAssert.that(
            owner.finishReceipt("server", "session-C:32", HologramTaskOwner.Result.SUCCESS, "real final job"),
            "the current authoritative completion unlocks resumed construction");
        SimpleAssert.that(!owner.busy() && events.size() == 6, "resumed success announces exactly once");
        long continued = owner.begin("server", "session-D:40", "construction");
        owner.advance("server", "session-D:40", "session-D:41");
        SimpleAssert.that(
            owner.rejectAdvance("server", "session-D:41", "session-D:40"),
            "a rejected pause returns receipt admission to the still-running original job");
        SimpleAssert.that(owner.token() == continued && owner.busy(), "rejected controls do not unlock the projector");
        SimpleAssert.that(
            !owner.finishReceipt("server", "session-D:41", HologramTaskOwner.Result.SUCCESS, "late rejected control"),
            "the rejected sequence cannot later finish the owner");
        SimpleAssert.that(
            owner.finishReceipt("server", "session-D:40", HologramTaskOwner.Result.SUCCESS, "original job finished"),
            "the original job can truthfully complete after its pause was rejected");
    }
}
