package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramCutRequestRegression {

    private HologramCutRequestRegression() {}

    public static void run() {
        exitActivityOwnership();
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
        for (String message : new String[] { "Original cut retained; save recovery pending", "剪切操作待恢复" }) {
            notice.operationResult(message, "recovery_pending");
            SimpleAssert.eq(message, notice.message(), "structured pending-recovery ACK displays its actual message");
            notice.directoryRecovered();
            SimpleAssert.eq("", notice.message(), "authoritative directory recovery clears a pending-recovery notice");
        }
        notice.operationResult("剪切操作待恢复", "ordinary_failure");
        notice.directoryRecovered();
        SimpleAssert.eq("剪切操作待恢复", notice.message(), "notice recovery uses a protocol code, not localized text");
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

    private static void exitActivityOwnership() {
        for (String action : new String[] { "cut", "paste", "undo" }) {
            HologramCutRequests requests = new HologramCutRequests();
            requests.observeHeld(true);
            requests.begin("directory", "list", "", 0);
            requests.begin("write", action, "entry", 0);
            requests.begin("old-preview", "preview", "entry", 0);
            requests.exitActivity();
            SimpleAssert.that(requests.writing(), "Escape retains the authoritative " + action + " outcome");
            SimpleAssert.that(requests.active("list"), "Escape retains a pending directory read");
            SimpleAssert.that(!requests.observeHeld(true), "Escape does not trigger another equip refresh");
            SimpleAssert.that(!requests.active("preview"), "Escape retires the local preview request");
            requests.begin("new-preview", "preview", "entry", 1);
            SimpleAssert.that(requests.acknowledge("old-preview") == null, "old preview cannot revive after reentry");
            HologramCutRequests.Request write = requests.acknowledge("write");
            SimpleAssert.that(
                write != null && !requests.currentActivity(write),
                "late " + action + " ACK updates its authoritative outcome without changing a new local activity");
            SimpleAssert.that(!requests.writing(), "late write ACK releases ownership without replay");
            SimpleAssert.that(
                requests.currentActivity(requests.acknowledge("new-preview")),
                "a fresh preview still belongs to the new activity");
            SimpleAssert.that(!requests.busy(), "retired preview cannot keep controls busy");

            requests.begin("uncertain", action, "entry", 0);
            requests.exitActivity();
            List<HologramCutRequests.Request> expired = requests.expire(HologramCutRequests.WRITE_TIMEOUT_NS);
            SimpleAssert.eq(
                1L,
                expired.stream()
                    .filter(HologramCutRequests.Request::writesWorld)
                    .count(),
                "Escape preserves exactly one write timeout for authoritative reconciliation");
            requests.begin("verify", "list", "uncertain", HologramCutRequests.WRITE_TIMEOUT_NS);
            HologramCutRequests.Request reconciled = requests.reconcile("uncertain");
            SimpleAssert.that(
                reconciled != null && !requests.currentActivity(reconciled),
                "late reconciliation cannot cancel or restart a newer local stage");
            SimpleAssert.that(!requests.writing(), "reconciliation releases the original write without resubmission");
        }
    }
}
