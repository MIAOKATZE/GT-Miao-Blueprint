package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramProgressRegression {

    private HologramProgressRegression() {}

    public static void run() {
        HologramProgress.Tracker tracker = new HologramProgress.Tracker();
        HologramProgress value = tracker.update("save1", "prepare", 0, 0, "", false, 10);
        SimpleAssert.eq(-1, value.percent(), "unknown work never invents a percentage");
        value = tracker.update("save1", "download", 50, 100, "bytes", false, 20);
        SimpleAssert.eq(50, value.percent(), "measured bytes report actual completed fraction");
        SimpleAssert.eq(10L, value.startedAt, "phase transitions retain the original wait start");
        value = tracker.update("save1", "download", 100, 100, "bytes", false, 30);
        SimpleAssert.eq(99, value.percent(), "all bytes without the completion event do not claim completion");
        tracker.finish(40);
        value = tracker.view(40);
        SimpleAssert.eq(100, value.percent(), "an actual phase completion can report100");
        SimpleAssert
            .eq(0L, value.elapsedSeconds(3000000000L), "completed duration stops at the completion observation");
        SimpleAssert.that(tracker.view(2000000041L) == null, "completed indicators expire without becoming busy");
        value = tracker.update("save1", "prepare", 0, 0, "", false, 50);
        SimpleAssert
            .eq(50L, value.startedAt, "a new operation after completion resets its timer even with a reused key");
        value = tracker.update("save2", "download", -1, Long.MAX_VALUE, "bytes", false, 60);
        tracker.finish("save1", 65);
        SimpleAssert.that(!tracker.view(65).complete, "an earlier callback cannot finish the next operation");
        SimpleAssert.eq(0, value.percent(), "negative observations cannot move the bar backwards past zero");
        value = tracker.update("save2", "download", Long.MAX_VALUE / 2, Long.MAX_VALUE, "bytes", false, 70);
        SimpleAssert.eq(49, value.percent(), "large counters remain exact without integer overflow or float rounding");
        value = tracker.update("save2", "job", 5, 10, "cells", true, 80);
        SimpleAssert.eq(50, value.percent(), "a terminal partial observation is not full completion");
        tracker.clear();
        SimpleAssert.that(tracker.view(100) == null, "cancel/error clears progress rather than showing100");

        HologramClientProgress.clear();
        HologramClientProgress.receive(event("sessionA", 100, 10, "requestA", "running"));
        HologramClientProgress.receive(event("sessionB", 1, 1, "requestB", "running"));
        SimpleAssert.that(
            HologramClientProgress.view("server", "sessionB") != null,
            "a new session can start with a lower progress sequence");
        HologramClientProgress.receive(event("sessionA", 101, 10, "requestA", "completed"));
        HologramClientProgress.receive(event("sessionA", 102, 10, "requestA", "rejected"));
        SimpleAssert.that(
            HologramClientProgress.view("server", "sessionB") != null,
            "late completion or denial from a retired session cannot finish the new session");
        HologramClientProgress.receive(event("sessionB", 1, 2, "requestB2", "running"));
        HologramClientProgress.receive(event("sessionB", 200, 1, "requestB", "rejected"));
        HologramClientProgress.receive(event("sessionB", 200, 1, "requestB2", "rejected"));
        HologramClientProgress.receive(event("sessionB", 201, 2, "different", "rejected"));
        SimpleAssert.that(
            HologramClientProgress.view("server", "sessionB") != null,
            "denials must match both the command and request identity");
        NBTTagCompound stale = new NBTTagCompound();
        stale.setString("session", "sessionB");
        stale.setLong("uiSequence", 1);
        HologramClientProgress.snapshot(stale);
        SimpleAssert.that(
            !HologramClientProgress.view("server", "sessionB").complete,
            "an old complete snapshot cannot finish a newer capture wait");
        HologramClientProgress.receive(event("sessionB", 202, 2, "requestB2", "rejected"));
        SimpleAssert.that(
            HologramClientProgress.view("server", "sessionB") == null,
            "an exact denial clears only its own indicator");
        HologramClientProgress.receive(event("sessionB", 2, 3, "requestB3", "running"));
        HologramClientProgress.receive(event("sessionB", 1000, 2, "requestB2", "completed"));
        SimpleAssert.that(
            !HologramClientProgress.view("server", "sessionB").complete,
            "a higher progress counter cannot override an older command identity");
        HologramClientProgress.receive(event("sessionB", 3, 3, "requestB3", "partial"));
        SimpleAssert.that(
            HologramClientProgress.view("server", "sessionB") == null,
            "a partial/stopped job is not displayed as successful100");

        NBTTagCompound download = new NBTTagCompound();
        download.setString("scope", "snapshot");
        download.setString("phase", "download");
        download.setString("unit", "bytes");
        download.setLong("sequence", 10);
        download.setLong("done", 100);
        download.setLong("total", 100);
        download.setBoolean("complete", true);
        HologramClientProgress.receive(download);
        SimpleAssert.eq(
            99,
            HologramClientProgress.view("snapshot", "")
                .percent(),
            "received bytes do not report a fully decoded snapshot");
        HologramClientProgress.decoding(10);
        SimpleAssert.eq(
            "decode",
            HologramClientProgress.view("snapshot", "").phase,
            "the actual admitted decode has its own unquantified phase");
        download.setLong("sequence", 11);
        HologramClientProgress.receive(download);
        HologramClientProgress.decoding(11);
        HologramClientProgress.decoded(10, true);
        SimpleAssert.that(
            !HologramClientProgress.view("snapshot", "").complete,
            "an earlier decoder cannot complete the next transfer");
        HologramClientProgress.decoded(11, true);
        SimpleAssert.eq(
            100,
            HologramClientProgress.view("snapshot", "")
                .percent(),
            "only actual decode completion finishes snapshot progress");
        HologramClientProgress.clear();
        saveReceiptBusyResolution();
    }

    private static void saveReceiptBusyResolution() {
        NBTTagCompound issued = new NBTTagCompound();
        for (String field : new String[] { "saveReceipt", "session", "localId", "jsonSha256", "previewSha256" })
            issued.setString(field, field + "-issued");
        issued.setLong("planRevision", 4);
        issued.setInteger("artifactVersion", com.miaokatze.gtmb.hologram.HologramBlueprintArtifact.VERSION);
        issued.setString("op", "ackBlueprintSaved");
        HologramLibraryClient.SaveReceiptResolution resolution = new HologramLibraryClient.SaveReceiptResolution();
        for (String field : new String[] { "saveReceipt", "session", "localId", "jsonSha256", "previewSha256" }) {
            NBTTagCompound other = (NBTTagCompound) issued.copy();
            other.setString(field, "other");
            SimpleAssert
                .that(!resolution.begin(issued, other), "busy cannot cancel a different save identity: " + field);
        }
        NBTTagCompound stale = (NBTTagCompound) issued.copy();
        stale.setLong("planRevision", 3);
        SimpleAssert.that(!resolution.begin(issued, stale), "an old revision denial cannot resolve a new pair");
        SimpleAssert
            .that(!resolution.requested(), "unrelated admission failures leave staged save resolution untouched");
        SimpleAssert
            .that(resolution.begin(issued, issued), "the exact admitted receipt starts authoritative resolution");
        SimpleAssert.that(!resolution.begin(issued, issued), "duplicate busy events never resend cancellation");
        SimpleAssert
            .that(resolution.requested(), "busy requests resolution instead of fabricating a commit or rejection");
    }

    private static NBTTagCompound event(String session, long sequence, long command, String request, String outcome) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("scope", "server");
        n.setString("phase", "capture");
        n.setString("session", session);
        n.setString("requestId", request);
        n.setLong("sequence", sequence);
        n.setLong("uiSequence", command);
        n.setLong("done", 1);
        n.setLong("total", 10);
        n.setString("unit", "cells");
        n.setString("outcome", outcome);
        n.setBoolean("complete", "completed".equals(outcome));
        return n;
    }
}
