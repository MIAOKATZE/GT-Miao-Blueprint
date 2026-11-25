// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Admission;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Decision;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.End;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Kind;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Lease;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Permit;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Phase;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Request;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Scope;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Settlement;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** State contract only; nothing binds this fixture to a network, a world, or the construction journal. */
public final class HologramConstructionTaskGateRegression {

    private HologramConstructionTaskGateRegression() {}

    public static void run() throws InterruptedException {
        replayAndCancellation();
        recoveryAcrossReconnect();
        administrativeReset();
        competingJobsAndShutdown();
    }

    private static void replayAndCancellation() {
        HologramConstructionTaskGate gate = new HologramConstructionTaskGate(3, 2, 1);
        UUID actor = UUID.randomUUID();
        Object player = new Object(), world = new Object(), connection = new Object();
        Scope scope = gate.bind(null, actor, player, world, connection);
        Scope other = gate.bind(null, UUID.randomUUID(), new Object(), world, new Object());
        require(scope.matches(actor, player, world, connection)
            && !scope.matches(actor, player, new Object(), connection),
            "world, player and connection are exact object identities");
        Request first = request(1, Kind.INCREMENTAL_NATIVE);
        Lease lease = granted(gate, scope, first);
        Permit permit = gate.beginTransaction(lease);
        require(permit != null && gate.transactionCurrent(permit) && gate.beginTransaction(lease) == null
            && !gate.finish(lease, End.COMPLETE), "one in-flight transaction, no early close");
        Admission duplicate = gate.acquire(scope, new Request(first.id, first.sequence, first.kind), false, false);
        require(duplicate.decision == Decision.ACTIVE_REPLAY && duplicate.lease == null
            && !gate.cancel(other, first)
            && !gate.cancel(scope, new Request(first.id, first.sequence, Kind.PROJECTED_LAYER)),
            "exact replay does not give a driver; wrong actor or lane cannot cancel");
        require(gate.cancel(scope, first) && gate.busy(actor)
            && gate.query(scope, first).phase == Phase.STOPPING
            && !gate.transactionCurrent(permit) && gate.beginTransaction(lease) == null
            && !gate.finish(lease, End.FAILED), "cancel fences next write but retains this permit");
        require(gate.settle(permit, Settlement.COMMITTED) && !gate.settle(permit, Settlement.COMMITTED)
            && !gate.busy(actor), "one exact commit closes requested cancellation");
        Admission replay = gate.acquire(scope, first, false, false);
        require(replay.decision == Decision.TERMINAL_REPLAY && replay.lease == null
            && replay.outcome.end == End.CANCELLED && replay.outcome.committedTransactions == 1
            && gate.query(other, first).phase == Phase.UNSEEN, "terminal outcome is scoped, with completed work intact");

        Request second = request(2, Kind.PROJECTED_LAYER);
        Lease next = granted(gate, scope, second);
        require(!gate.finish(lease, End.COMPLETE) && gate.finish(next, End.COMPLETE),
            "old scheduled close cannot retire a successor");
        require(gate.query(scope, first).phase == Phase.RETIRED_UNKNOWN
            && gate.acquire(scope, first, false, false).decision == Decision.RETIRED_UNKNOWN,
            "terminal cache eviction never permits a repeat world operation");
        require(gate.bind(scope, actor, player, world, connection) == scope
            && gate.acquire(scope, first, false, false).decision == Decision.RETIRED_UNKNOWN,
            "rebinding the same authenticated endpoint does not reset its replay watermark");
    }

    private static void recoveryAcrossReconnect() {
        HologramConstructionTaskGate gate = new HologramConstructionTaskGate(2, 2, 3);
        UUID actor = UUID.randomUUID();
        Object world = new Object();
        Scope old = gate.bind(null, actor, new Object(), world, new Object());
        Request first = request(1, Kind.LEGACY_NATIVE);
        Lease lease = granted(gate, old, first);
        Permit permit = gate.beginTransaction(lease);
        require(gate.settle(permit, Settlement.RECOVERY_PENDING) && gate.busy(actor)
            && gate.query(old, first).phase == Phase.RECOVERY_PENDING
            && !gate.finish(lease, End.FAILED), "uncertain world/payment retains exact settlement ownership");
        Scope successor = gate.bind(old, actor, new Object(), world, new Object());
        require(successor != null && successor.generation > old.generation
            && !gate.revoke(old) && gate.bind(old, actor, new Object(), world, new Object()) == null
            && gate.query(old, first).phase == Phase.STALE_SCOPE
            && !gate.cancel(successor, first)
            && gate.beginTransaction(lease) == null, "a new connection cannot operate or cancel the old exact job");
        Request whilePending = request(1, Kind.PROJECTED_LAYER);
        require(gate.acquire(successor, whilePending, false, false).decision == Decision.RECOVERY_PENDING
            && gate.busy(actor), "unsettled recovery is sticky even without the external pending hint");
        require(gate.settle(permit, Settlement.ROLLED_BACK) && !gate.busy(actor),
            "settling old durable recovery releases only that exact permit");
        require(gate.acquire(successor, whilePending, false, false).decision == Decision.RETIRED_UNKNOWN,
            "a blocked start cannot spring into execution via a delayed retry");
        require(gate.acquire(successor, request(2, Kind.PROJECTED_LAYER), false, true).decision
            == Decision.RECOVERY_PENDING, "authoritative journal on new load is independently required");
        Lease fresh = granted(gate, successor, request(3, Kind.PROJECTED_LAYER));
        require(gate.finish(fresh, End.COMPLETE), "fresh explicit admission after resolution");
    }

    private static void administrativeReset() {
        HologramConstructionTaskGate gate = new HologramConstructionTaskGate(2, 2, 4);
        Scope scope = gate.bind(null, UUID.randomUUID(), new Object(), new Object(), new Object());
        Request started = request(1, Kind.INCREMENTAL_NATIVE);
        Lease lease = granted(gate, scope, started);
        require(gate.settle(gate.beginTransaction(lease), Settlement.COMMITTED), "one transaction really committed");
        Permit pending = gate.beginTransaction(lease);
        gate.stopActiveJobs();
        require(gate.scopeCurrent(scope) && gate.busy(scope.actor) && !gate.transactionCurrent(pending)
            && gate.query(scope, started).phase == Phase.STOPPING,
            "runtime reset keeps the endpoint but stops future writes without erasing in-flight evidence");
        require(gate.settle(pending, Settlement.RECOVERY_PENDING), "uncertainty still owns the exact permit");
        require(gate.settle(pending, Settlement.ROLLED_BACK), "explicit proof settles the retained permit");
        Admission retry = gate.acquire(scope, started, false, false);
        require(retry.decision == Decision.TERMINAL_REPLAY && retry.outcome.end == End.STOPPED_FOR_RECOVERY
            && retry.outcome.committedTransactions == 1 && retry.lease == null,
            "administrative reset never replays an old start and reports only proven partial work");
        require(gate.finish(granted(gate, scope, request(2, Kind.PROJECTED_LAYER)), End.COMPLETE),
            "a fresh explicit intent may run after exact settlement");
    }

    private static void competingJobsAndShutdown() throws InterruptedException {
        HologramConstructionTaskGate gate = new HologramConstructionTaskGate(2, 1, 1);
        Scope a = gate.bind(null, UUID.randomUUID(), new Object(), new Object(), new Object());
        Scope b = gate.bind(null, UUID.randomUUID(), new Object(), new Object(), new Object());
        require(gate.bind(null, UUID.randomUUID(), new Object(), new Object(), new Object()) == null,
            "bound authenticated endpoint capacity");
        Request oldBusy = request(1, Kind.PROJECTED_LAYER);
        require(gate.acquire(a, oldBusy, true, false).decision == Decision.BUSY && !gate.busy(a.actor)
            && gate.acquire(a, oldBusy, false, false).decision == Decision.RETIRED_UNKNOWN,
            "reciprocal legacy blocker creates no lease and is not retried implicitly");
        Request beforeShutdown = request(2, Kind.DEMOLITION);
        Lease lease = granted(gate, a, beforeShutdown);
        require(gate.acquire(a, request(3, Kind.AE_ORDER), false, false).decision == Decision.BUSY
            && gate.acquire(b, request(1, Kind.CURRENT_DRAFT), false, false).decision == Decision.CAPACITY,
            "same-actor conflicts and global concurrency bound are distinct");
        AtomicReference<Throwable> offThread = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try {
                gate.beginTransaction(lease);
            } catch (Throwable invalid) {
                offThread.set(invalid);
            }
        });
        wrongThread.start();
        wrongThread.join();
        require(offThread.get() instanceof IllegalStateException, "only logical server thread may operate the gate");
        Permit permit = gate.beginTransaction(lease);
        require(permit != null && !gate.shutdown() && !gate.stoppedAndDrained() && gate.busy(a.actor)
            && !gate.transactionCurrent(permit) && !gate.finish(lease, End.FAILED) && gate.beginTransaction(lease) == null
            && gate.acquire(a, request(4, Kind.LEGACY_PROJECTED), false, false).decision == Decision.STALE_SCOPE,
            "shutdown blocks all new work without erasing unfinished settlement");
        require(gate.settle(permit, Settlement.ROLLED_BACK) && gate.stoppedAndDrained() && gate.shutdown()
            && !gate.busy(a.actor), "shutdown publishes drain only after exact paired settlement");
    }

    private static Lease granted(HologramConstructionTaskGate gate, Scope scope, Request request) {
        Admission admission = gate.acquire(scope, request, false, false);
        require(admission.decision == Decision.GRANTED && admission.lease != null, "fresh admission");
        return admission.lease;
    }

    private static Request request(long sequence, Kind kind) {
        return new Request(UUID.randomUUID(), sequence, kind);
    }

    private static void require(boolean condition, String detail) {
        SimpleAssert.that(condition, detail);
    }
}
