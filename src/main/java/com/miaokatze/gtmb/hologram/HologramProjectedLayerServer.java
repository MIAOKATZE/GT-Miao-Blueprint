// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Admission;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.End;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Lease;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Permit;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerService.Status;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Key;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Operation;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Phase;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reason;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reply;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Request;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramWorkspace.ActionEndpoint;

/**
 * Server-owned ingress, transfer credit and geometry-free receipts. The transport is not registered here.
 * This plain lane uses the tool-selected inventory, native backpack adapters and authenticated wireless ME.
 * Its bounded plain block profile does not claim support for large mixed blueprint metadata.
 */
final class HologramProjectedLayerServer implements AutoCloseable {

    private static final int QUEUE_PER_LANE = 16, MAX_INGRESS_PER_TICK = 8, MAX_STOPS_PER_TICK = 8, MAX_REPLAYS = 128;
    private final Thread owner = Thread.currentThread();
    private final HologramRequestGate data = new HologramRequestGate(HologramProjectedLayerWire.WINDOW, QUEUE_PER_LANE * 4);
    private final HologramRequestGate control = new HologramRequestGate(1, QUEUE_PER_LANE);
    private final HologramRequestGate stop = new HologramRequestGate(1, QUEUE_PER_LANE);
    // Both queues use the same monitor. A bounded stop dispatcher is allowed to overtake DATA.
    private final ArrayDeque<Ingress> inbox = new ArrayDeque<>();
    private final ArrayDeque<Ingress> stopInbox = new ArrayDeque<>();
    private final Map<UUID, Job> active = new LinkedHashMap<>();
    private final Map<ReplayKey, Reply> replays = new LinkedHashMap<>();
    // A journal uncertainty retains exactly its gate+permit, not a slice or a reply claiming termination.
    private final Map<UUID, Job> unresolved = new LinkedHashMap<>();
    private final BiConsumer<ActionEndpoint, Reply> sink;
    private final BiPredicate<ActionEndpoint, Key> authorize;
    private boolean closed, ticking;
    private int cursor;

    HologramProjectedLayerServer(BiConsumer<ActionEndpoint, Reply> sink, BiPredicate<ActionEndpoint, Key> authorize) {
        this.sink = java.util.Objects.requireNonNull(sink);
        this.authorize = java.util.Objects.requireNonNull(authorize);
    }

    private static final class Ingress {

        final Request request;
        final ActionEndpoint endpoint;
        final HologramRequestGate.Lease queue;
        final HologramSessionPolicy.PreparationTool submittedTool;
        final int submittedSlot;

        Ingress(Request request, ActionEndpoint endpoint, HologramRequestGate.Lease queue) {
            this.request = request;
            this.endpoint = endpoint;
            this.queue = queue;
            boolean begin = request.operation == Operation.BEGIN;
            submittedSlot = begin ? endpoint.player.inventory.currentItem : -1;
            submittedTool = begin ? new HologramSessionPolicy.PreparationTool(endpoint.player.getHeldItem()) : null;
        }
    }

    private static final class ReplayKey {

        final UUID actor;
        final Key key;

        ReplayKey(ActionEndpoint endpoint, Key key) {
            actor = endpoint.actor;
            this.key = key;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ReplayKey result && actor.equals(result.actor) && key.equals(result.key);
        }

        @Override
        public int hashCode() {
            return actor.hashCode() * 31 + key.hashCode();
        }
    }

    private final class Job implements HologramProjectedLayerService.ScopedBoundary {

        final ActionEndpoint endpoint;
        final HologramConstructionTaskGate gate;
        final Lease lease;
        final Key key;
        HologramProjectedLayerService service;
        Permit unresolved;
        int creditIndex = -1;
        long lastControl;
        Operation lastControlOperation;
        Reply lastSent;

        Job(ActionEndpoint endpoint, HologramConstructionTaskGate gate, Lease lease, Key key) {
            this.endpoint = endpoint;
            this.gate = gate;
            this.lease = lease;
            this.key = key;
        }

        @Override
        public boolean current() {
            return deliverable() && gate.query(lease.scope, lease.request).phase == HologramConstructionTaskGate.Phase.ACTIVE;
        }

        boolean deliverable() {
            return HologramWorkspace.currentActionEndpoint(endpoint);
        }

        @Override
        public HologramProjectedLayerService.Settlement transact(HologramSession transaction,
            HologramCapture.Cell cell, BooleanSupplier currentInputs) {
            if (!current() || !currentInputs.getAsBoolean())
                return HologramProjectedLayerService.Settlement.OWNER_CHANGED;
            Permit permit;
            // Linearize only the next permit against exact STOP enqueue. Never hold this monitor during
            // Forge callbacks, world reads/writes, payment or journal I/O. An existing permit stays atomic.
            synchronized (inbox) {
                if (stopWaitingLocked(endpoint, key, lastControl))
                    return HologramProjectedLayerService.Settlement.DEFERRED;
                permit = gate.beginTransaction(lease);
            }
            if (permit == null) return HologramProjectedLayerService.Settlement.OWNER_CHANGED;
            boolean pending = true;
            try {
                boolean applied = HologramWorldTransactions.operateScopedSelected(
                    transaction, cell, 0, () -> gate.transactionCurrent(permit) && currentInputs.getAsBoolean());
                if (transaction.job == 4 || HologramConstructionJournal.pending(transaction.player))
                    return HologramProjectedLayerService.Settlement.RECOVERY_PENDING;
                if (applied && "placed".equals(cell.status)) {
                    if (!gate.settle(permit, HologramConstructionTaskGate.Settlement.COMMITTED))
                        throw new IllegalStateException("Lost projected construction permit on commit");
                    pending = false;
                    return HologramProjectedLayerService.Settlement.COMMITTED;
                }
                if (!gate.settle(permit, HologramConstructionTaskGate.Settlement.ROLLED_BACK))
                    throw new IllegalStateException("Lost projected construction permit on rollback");
                pending = false;
                return current() ? HologramProjectedLayerService.Settlement.ROLLED_BACK
                    : HologramProjectedLayerService.Settlement.OWNER_CHANGED;
            } finally {
                // Unexpected exceptions cannot release the permit by presuming rollback.
                if (pending) {
                    gate.settle(permit, HologramConstructionTaskGate.Settlement.RECOVERY_PENDING);
                    unresolved = permit;
                }
            }
        }
    }

    /** Actual Forge ingress calls this with its sender, not a player id or tool chosen by the message. */
    void enqueue(Request request, NetHandlerPlayServer sender) {
        if (request == null || request.key == null) return;
        ActionEndpoint endpoint = HologramWorkspace.captureActionEndpoint(sender);
        if (endpoint == null || endpoint.constructionScope == null
            || request.key.generation != endpoint.constructionScope.generation) return;
        HologramRequestGate gate = request.operation == Operation.STOP ? stop : request.isControl() ? control : data;
        HologramRequestGate.Lease slot = gate.acquire(endpoint.connection);
        if (slot == null) return; // Explicitly retriable by exact request id; never grants credit.
        boolean queued = false;
        try {
            Ingress item = new Ingress(request, endpoint, slot);
            synchronized (inbox) {
                if (closed || !slot.active()) return;
                (request.operation == Operation.STOP ? stopInbox : inbox).addLast(item);
                queued = true;
            }
        } catch (RuntimeException | LinkageError invalid) {
            // An interrupted inventory/connection read cannot admit this request.
        } finally {
            if (!queued) slot.close();
        }
    }

    /** One shared tick deadline, bounded ingress/frame and fair rotating service across admitted owners. */
    void tick(long deadline) {
        requireOwner();
        if (ticking || closed) return;
        ticking = true;
        try {
            long now = System.nanoTime();
            long ingressDeadline = deadline == Long.MAX_VALUE ? deadline : now + Math.max(0, deadline - now) / 2;
            int queued, priority;
            synchronized (inbox) {
                queued = Math.min(inbox.size(), MAX_INGRESS_PER_TICK);
                priority = Math.min(stopInbox.size(), MAX_STOPS_PER_TICK);
            }
            for (int count = 0; count < priority && System.nanoTime() < ingressDeadline; count++) {
                Ingress item;
                synchronized (inbox) {
                    item = stopInbox.pollFirst();
                }
                if (item == null) break;
                try {
                    if (item.queue.active()) dispatch(item);
                } finally {
                    item.queue.close();
                }
            }
            Set<ActionEndpoint> controlThisTick = new HashSet<>();
            for (int count = 0; count < queued && System.nanoTime() < ingressDeadline; count++) {
                Ingress item;
                synchronized (inbox) {
                    item = inbox.pollFirst();
                }
                if (item == null) break;
                boolean deferred = false;
                try {
                    if (item.queue.active() && (!item.request.isControl() || controlThisTick.add(item.endpoint))) {
                        Job action = active.get(item.endpoint.actor);
                        long seen = action != null && action.endpoint == item.endpoint
                            && action.key.equals(item.request.key) ? action.lastControl : 0;
                        if (stopWaiting(item.endpoint, item.request.key, seen)) {
                            synchronized (inbox) {
                                inbox.addLast(item);
                            }
                            deferred = true;
                        } else dispatch(item);
                    }
                } finally {
                    if (!deferred) item.queue.close();
                }
            }
            ArrayList<Job> jobs = new ArrayList<>(active.values());
            int size = jobs.size();
            if (size == 0) return;
            int start = cursor % size, inspected = 0;
            while (inspected < size && System.nanoTime() < deadline) {
                Job job = jobs.get((start + inspected++) % size);
                if (stopWaiting(job.endpoint, job.key, job.lastControl)) continue;
                if (!job.current()) job.service.retire();
                else job.service.step(deadline, HologramWorkBudget.serverTickSerial());
                publish(job, false);
            }
            cursor = (start + Math.max(1, inspected)) % size;
        } finally {
            ticking = false;
        }
    }

    private boolean stopWaiting(ActionEndpoint endpoint, Key key, long seen) {
        synchronized (inbox) {
            return stopWaitingLocked(endpoint, key, seen);
        }
    }

    private boolean stopWaitingLocked(ActionEndpoint endpoint, Key key, long seen) {
        for (Ingress pending : stopInbox) if (pending.endpoint == endpoint && pending.request.key.equals(key)
            && pending.request.controlSequence > seen) return true;
        return false;
    }

    private void dispatch(Ingress item) {
        Request request = item.request;
        ActionEndpoint endpoint = item.endpoint;
        HologramConstructionTaskGate gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null) return;
        ReplayKey replay = new ReplayKey(endpoint, request.key);
        Reply previous = replays.get(replay);
        if (previous != null) {
            send(endpoint, previous);
            return;
        }
        Job job = active.get(endpoint.actor);
        if (job != null && job.endpoint == endpoint && job.key.equals(request.key)) {
            if (!job.current()) return; // Normal tick retires it; a query or packet cannot drive retirement.
            switch (request.operation) {
                case SLICE -> offer(job, request);
                case BEGIN -> readCurrent(job, true);
                case STATUS -> readCurrent(job, false);
                case PAUSE, RESUME, STOP -> control(job, request);
            }
            return;
        }
        if (request.operation == Operation.BEGIN) begin(item, gate, replay);
        else if (request.operation == Operation.STATUS)
            send(endpoint, rejection(request.key, Reason.RETIRED_UNKNOWN));
        else if (request.operation == Operation.STOP) stopBeforeBegin(item, gate, replay);
    }

    private void control(Job job, Request request) {
        if (request.controlSequence < job.lastControl) return;
        if (request.controlSequence == job.lastControl) {
            if (request.operation == job.lastControlOperation) readCurrent(job, false);
            return;
        }
        try {
            if (request.operation == Operation.STOP
                && !job.gate.cancel(job.lease.scope, job.lease.request)) return;
            job.lastControl = request.controlSequence;
            job.lastControlOperation = request.operation;
            if (request.operation == Operation.STOP) job.service.cancel(job.endpoint.player);
            else if (request.operation == Operation.PAUSE) job.service.pause(job.endpoint.player);
            else if (request.operation == Operation.RESUME) job.service.resume(job.endpoint.player);
        } catch (RuntimeException | LinkageError invalid) {
            job.service.failUnsupported();
        }
        publish(job, true);
    }

    /** Priority STOP may overtake only an exact, already-buffered authorized Begin, not invent an intent. */
    private void stopBeforeBegin(Ingress stopped, HologramConstructionTaskGate gate, ReplayKey replay) {
        Key key = stopped.request.key;
        boolean found = false;
        synchronized (inbox) {
            for (Ingress queued : inbox) {
                if (queued.endpoint == stopped.endpoint && queued.request.operation == Operation.BEGIN
                    && queued.request.key.equals(key)) {
                    found = true;
                    break;
                }
            }
        }
        if (!found || gate.query(stopped.endpoint.constructionScope, key.gateRequest()).phase
            != HologramConstructionTaskGate.Phase.UNSEEN || !authorized(stopped.endpoint, key)) return;
        // An intentionally denied queued start consumes its watermark without granting a construction lease.
        var decision = gate.acquire(stopped.endpoint.constructionScope, key.gateRequest(), true, false).decision;
        if (decision == HologramConstructionTaskGate.Decision.BUSY
            || decision == HologramConstructionTaskGate.Decision.RECOVERY_PENDING)
            remember(replay, rejection(key, 0, stopped.request.controlSequence, Reason.CANCELLED), stopped.endpoint);
    }

    private boolean authorized(ActionEndpoint endpoint, Key key) {
        try {
            return authorize.test(endpoint, key);
        } catch (RuntimeException | LinkageError failed) {
            return false;
        }
    }

    private void begin(Ingress item, HologramConstructionTaskGate gate, ReplayKey replay) {
        ActionEndpoint endpoint = item.endpoint;
        EntityPlayerMP player = endpoint.player;
        Key key = item.request.key;
        if (!authorized(endpoint, key)) {
            // The central one-use proof may already be consumed for a prior result evicted from this scalar cache.
            boolean unseen = gate.query(endpoint.constructionScope, key.gateRequest()).phase
                == HologramConstructionTaskGate.Phase.UNSEEN;
            send(endpoint, rejection(key, unseen ? Reason.ADMISSION_REFUSED : Reason.RETIRED_UNKNOWN));
            return;
        }
        boolean competing = false, pending = true;
        try {
            competing = active.containsKey(endpoint.actor) || HologramProjectedLayerService.competingWork(player);
            pending = unresolved.containsKey(endpoint.actor) || HologramProjectedLayerService.hasKnownRecovery(player);
        } catch (RuntimeException | LinkageError invalid) {
            // A failed owner/journal read must never turn this exact sequence into a deferred new start.
        }
        if (HologramWorkspace.constructionGate(endpoint) != gate) return;
        Admission admission = gate.acquire(endpoint.constructionScope, key.gateRequest(), competing, pending);
        if (admission.lease == null) {
            Reason reason = switch (admission.decision) {
                case BUSY, CAPACITY -> Reason.BUSY;
                case RECOVERY_PENDING -> Reason.RECOVERY_PENDING;
                default -> Reason.RETIRED_UNKNOWN;
            };
            // The gate consumes denied start sequence numbers; keep first denial truthful on exact replay.
            if (admission.decision != HologramConstructionTaskGate.Decision.ACTIVE_REPLAY)
                remember(replay, rejection(key, reason), endpoint);
            return;
        }
        Job job = new Job(endpoint, gate, admission.lease, key);
        try {
            if (player.inventory.currentItem != item.submittedSlot || !item.submittedTool.matches(player.getHeldItem())
                || !ItemNekoHologramProjector.isProjector(player.getHeldItem()) || !job.current()) {
                gate.finish(job.lease, End.FAILED);
                remember(replay, rejection(key, Reason.OWNER_CHANGED), endpoint);
                return;
            }
            Manifest manifest = HologramProjectedLayerWire.decodeManifest(item.request.body);
            if (!key.id.equals(manifest.header.request)) throw HologramProjectedLayerWire.malformed();
            job.service = HologramProjectedLayerService.beginScoped(player, manifest, job);
        } catch (IOException | RuntimeException | LinkageError invalid) {
            gate.finish(job.lease, End.FAILED);
            Reason reason = invalid instanceof HologramProjectedLayerService.AdmissionFailure refusal
                ? Reason.valueOf(refusal.reason.name()) : Reason.ADMISSION_REFUSED;
            remember(replay, rejection(key, reason), endpoint);
            return;
        }
        active.put(endpoint.actor, job);
        publish(job, true); // Only the successfully admitted and fully stocked path can create first credit.
    }

    private void offer(Job job, Request request) {
        // Read only the fixed sequence prefix to gate decoding; the service verifies the entire bounded slice once.
        if (request.body.length < 24) return;
        int submitted = ByteBuffer.wrap(request.body).getInt(20);
        Status status = job.service.status();
        boolean credited = submitted == status.acceptedSlices && submitted < job.creditIndex;
        boolean replay = submitted >= Math.max(0, status.settledSlices - 1) && submitted < status.acceptedSlices;
        if (!credited && !replay) {
            readCurrent(job, false);
            return;
        }
        try {
            HologramProjectedLayerPlan.Offer result = job.service.offer(job.endpoint.player, request.body);
            if (result == HologramProjectedLayerPlan.Offer.REPLAY) {
                readCurrent(job, true);
                return;
            }
        } catch (IOException | RuntimeException | LinkageError invalid) {
            // Preserve an exact service reason if already stopped; otherwise even a provider callback fails closed.
            job.service.failUnsupported();
        }
        publish(job, true);
    }

    /** A retry can repeat an already issued receipt; STATUS itself cannot re-check stock, grant credit, or stop. */
    private void readCurrent(Job job, boolean repeatIssuedCredit) {
        if (!job.current()) return;
        Status status = job.service.status();
        if (terminal(status)) return;
        try {
            Reply issued = job.lastSent;
            send(job.endpoint, repeatIssuedCredit && issued != null && issued.sequence == status.sequence
                && issued.controlSequence == job.lastControl ? issued : receipt(job, status, null));
        } catch (IOException invalid) {
            // A malformed observation must not act or claim an unproven result.
        }
    }

    private static boolean terminal(Status status) {
        return status.phase == HologramProjectedLayerService.Phase.COMPLETE
            || status.phase == HologramProjectedLayerService.Phase.STOPPED;
    }

    private void publish(Job job, boolean force) {
        Status status = job.service.status();
        if (!terminal(status) && !job.current()) {
            job.service.retire();
            status = job.service.status();
        }
        if (!force && job.lastSent != null && status.sequence == job.lastSent.sequence) return;
        try {
            Ack ack = stopWaiting(job.endpoint, job.key, job.lastControl) ? null : switch (status.phase) {
                case AWAITING_SLICE, VALIDATING_SLICE, PLACING, PAUSED, COMPLETE, STOPPED -> job.service.transferAck();
                default -> null;
            };
            status = job.service.status(); // A credit recheck after state observation can discover lost material.
            if (terminal(status)) {
                finish(job, status, ack);
                return;
            }
            if (!job.current()) return;
            Reply reply = receipt(job, status, ack);
            if (send(job.endpoint, reply) && job.current()) {
                job.lastSent = reply;
                if (ack != null && ack.credit == 1) job.creditIndex = ack.nextSlice + HologramProjectedLayerWire.WINDOW;
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            // A credit recheck/provider failure must not strand the client forever before any observable result.
            if (active.get(job.endpoint.actor) == job) {
                try {
                    finish(job, job.service.failUnsupported(), null);
                } catch (IOException invalid) {
                    // No fabricated success or credit. The bounded exact STATUS path returns unknown if not cached.
                }
            }
        }
    }

    private void finish(Job job, Status status, Ack ack) throws IOException {
        boolean complete = status.phase == HologramProjectedLayerService.Phase.COMPLETE;
        if (job.unresolved != null) unresolved.put(job.endpoint.actor, job);
        else job.gate.finish(job.lease, complete ? End.COMPLETE : End.FAILED);
        if (!active.remove(job.endpoint.actor, job) || !job.deliverable()) return;
        var view = job.gate.query(job.lease.scope, job.lease.request);
        boolean authoritative = view.committedTransactions == status.placed && (job.unresolved != null
            ? view.phase == HologramConstructionTaskGate.Phase.RECOVERY_PENDING
            : view.outcome != null && complete == (view.outcome.end == End.COMPLETE)
                && (status.reason != HologramProjectedLayerService.Reason.CANCELLED
                    || view.outcome.end == End.CANCELLED && job.lastControlOperation == Operation.STOP));
        if (!authoritative) {
            remember(new ReplayKey(job.endpoint, job.key), rejection(job.key, status.sequence, job.lastControl,
                Reason.RETIRED_UNKNOWN), job.endpoint);
            return;
        }
        remember(new ReplayKey(job.endpoint, job.key), receipt(job, status, ack), job.endpoint);
    }

    private static Reply receipt(Job job, Status status, Ack ack) throws IOException {
        Phase phase = status.reason == HologramProjectedLayerService.Reason.RECOVERY_PENDING || job.unresolved != null
            ? Phase.RECOVERY_PENDING : Phase.valueOf(status.phase.name());
        Reason reason = phase == Phase.RECOVERY_PENDING ? Reason.RECOVERY_PENDING
            : Reason.valueOf(status.reason.name());
        return new Reply(job.key, status.sequence, job.lastControl, phase, reason, status.acceptedSlices,
            status.settledSlices, status.totalSlices, status.placed, status.alreadySatisfied, status.totalCells,
            status.validatedInSlice, status.sliceCells, ack == null ? null : HologramProjectedLayerWire.encodeAck(ack));
    }

    private static Reply rejection(Key key, Reason reason) {
        return rejection(key, 0, 0, reason);
    }

    private static Reply rejection(Key key, long sequence, long controlSequence, Reason reason) {
        try {
            return new Reply(key, sequence, controlSequence, Phase.REJECTED, reason, 0, 0, 0, 0, 0, 0, 0, 0, null);
        } catch (IOException impossible) {
            throw new IllegalStateException("Zero-count layer rejection", impossible);
        }
    }

    private void remember(ReplayKey key, Reply reply, ActionEndpoint endpoint) {
        if (!HologramWorkspace.currentActionEndpoint(endpoint)) return;
        replays.put(key, reply);
        while (replays.size() > MAX_REPLAYS) replays.remove(replays.keySet().iterator().next());
        send(endpoint, reply);
    }

    private boolean send(ActionEndpoint endpoint, Reply reply) {
        if (!HologramWorkspace.currentActionEndpoint(endpoint)) return false;
        try {
            sink.accept(endpoint, reply);
            return true;
        } catch (RuntimeException | LinkageError notDelivered) {
            return false;
        }
    }

    @Override
    public void close() {
        requireOwner();
        if (ticking) throw new IllegalStateException("Projected layer server tick in flight");
        synchronized (inbox) {
            closed = true;
            inbox.clear();
            stopInbox.clear();
            data.clear();
            control.clear();
            stop.clear();
        }
        for (Job job : active.values()) {
            job.service.retire();
            // Exact unsettled permits are never returned as success or released by an adapter close.
            job.gate.finish(job.lease, End.FAILED);
        }
        active.clear();
        replays.clear();
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Projected layer server owner thread");
    }
}
