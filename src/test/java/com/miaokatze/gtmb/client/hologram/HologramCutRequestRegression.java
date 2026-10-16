package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramCutRequestRegression {

    private HologramCutRequestRegression() {}

    public static void run() {
        HologramCutRequests requests = new HologramCutRequests();
        SimpleAssert.that(requests.observeHeld(true), "equipping requests one directory sync");
        requests.begin("initial", "list", "", 0);
        SimpleAssert.that(!requests.busy(), "a background directory read never locks editing");
        for (int tick = 0; tick < 10000; tick++)
            SimpleAssert.that(!requests.observeHeld(true), "idle holding never polls the directory again");
        SimpleAssert.eq(
            0,
            requests.expire(HologramCutRequests.READ_TIMEOUT_NS - 1)
                .size(),
            "read keeps its budget");
        List<HologramCutRequests.Request> expired = requests.expire(HologramCutRequests.READ_TIMEOUT_NS);
        SimpleAssert.eq(1, expired.size(), "a silently dropped directory request expires once");
        SimpleAssert.eq("list", expired.get(0).action, "expiry identifies a read, not a world operation");
        SimpleAssert.that(!requests.busy() && !requests.active("list"), "dropped read cannot leave permanent busy");
        SimpleAssert.eq(
            0,
            requests.expire(Long.MAX_VALUE)
                .size(),
            "timeouts never schedule recurring retries");
        SimpleAssert.that(!requests.observeHeld(false), "putting the tool away sends no request");
        SimpleAssert.that(requests.observeHeld(true), "returning to the tool refreshes once");
        requests.begin("retry", "list", "", 1);
        SimpleAssert.that(requests.acknowledge("initial") == null, "an expired old read cannot consume a new request");
        SimpleAssert.eq("list", requests.acknowledge("retry").action, "only the matching read is acknowledged");

        for (String operation : new String[] { "cut", "paste", "undo" }) {
            requests.clear();
            requests.begin("write", operation, "entry", 0);
            SimpleAssert.that(requests.busy(), "actual write awaits its outcome: " + operation);
            expired = requests.expire(HologramCutRequests.WRITE_TIMEOUT_NS);
            SimpleAssert.eq(1, expired.size(), "write timeout is observable exactly once");
            SimpleAssert.that(
                expired.get(0)
                    .writesWorld(),
                "write outcome requires a directory check");
            SimpleAssert.eq("write", requests.unresolvedWrite(), "preserve the original id for reconciliation");
            requests.begin("check", "list", "write", HologramCutRequests.WRITE_TIMEOUT_NS);
            SimpleAssert.that(requests.busy(), "starting a read must not hide an unknown write result");
            SimpleAssert.that(requests.reconcile("different") == null, "an unrelated read cannot resolve a write");
            requests.acknowledge("check");
            SimpleAssert.that(requests.busy(), "a read ACK alone is not proof of the original outcome");
            SimpleAssert.eq(operation, requests.reconcile("write").action, "authoritative result resolves exact write");
            SimpleAssert.that(!requests.busy(), "verified write outcome releases editing");

            requests.begin("late", operation, "entry", 0);
            requests.expire(HologramCutRequests.WRITE_TIMEOUT_NS);
            SimpleAssert.eq(operation, requests.acknowledge("late").action, "a late genuine write ACK is retained");
            SimpleAssert.that(
                requests.unresolvedWrite()
                    .isEmpty(),
                "late ACK removes uncertainty without replay");
        }
        requests.begin("preview", "preview", "entry", 0);
        SimpleAssert.that(requests.busy(), "an explicit preview has a bounded busy state");
        requests.expire(HologramCutRequests.READ_TIMEOUT_NS);
        SimpleAssert.that(!requests.busy(), "a lost preview does not permanently disable controls");
        requests.clear();
        SimpleAssert.that(requests.observeHeld(true), "a new world starts an independent sync lifecycle");
        SimpleAssert.that(requests.acknowledge("preview") == null, "old-world ACK cannot restore cleared state");

        HologramCutNotice notice = new HologramCutNotice();
        notice.directoryFailed("directory timed out");
        notice.directoryRecovered();
        SimpleAssert.eq("", notice.message(), "a successful directory retry clears its stale timeout notice");
        for (String outcome : new String[] { "cut completed", "paste failed", "undo confirmed", "checking write" }) {
            notice.show(outcome);
            notice.directoryRecovered();
            SimpleAssert.eq(outcome, notice.message(), "background sync preserves the actual operation outcome");
        }
        notice.show("preview timed out");
        notice.directoryRecovered();
        SimpleAssert.eq("preview timed out", notice.message(), "a directory read is not proof a preview recovered");
        notice.show("waiting for preview");
        notice.show("preview ready");
        notice.directoryRecovered();
        SimpleAssert.eq("preview ready", notice.message(), "a genuine preview retry replaces its stale error/wait");
        notice.show("waiting for preview");
        notice.show("");
        SimpleAssert.eq("", notice.message(), "a preview ACK without a message also clears the old wait notice");
        notice.directoryFailed("directory failed");
        notice.show("write completed after late ACK");
        notice.directoryRecovered();
        SimpleAssert
            .eq("write completed after late ACK", notice.message(), "late write outcome supersedes sync errors");
    }
}
