// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Command;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Request;
import com.miaokatze.gtmb.hologram.HologramWorkspace.ActionEndpoint;

import io.netty.util.AttributeKey;

/** Bounded actor/generation-owned stock construction ingress. All mutations remain in Entry and operateScoped. */
final class HologramStockNativeServer {

    @FunctionalInterface
    interface ReplySink {

        void send(ActionEndpoint endpoint, Reply reply);
    }

    @Desugar
    private record Identity(UUID actor, Key request) {}

    @Desugar
    private record Waiting(ActionEndpoint endpoint, Request request, HologramStockNativeEntry.Submission submission,
        String refusal, HologramRequestGate.Lease slot) {

        Identity identity() {
            return new Identity(endpoint.actor, request.key());
        }
    }

    @Desugar
    private record ColdProof(ActionEndpoint endpoint, Confirmation confirmation,
        HologramStockNativeEntry.Submission submitted, long expires) {}

    /** Payload-free watermark for the physical authenticated channel, not the current world/editor generation. */
    private static final class ProofHistory {

        final UUID actor;
        final AtomicLong seenThrough = new AtomicLong();
        long generation;
        Object resetEpoch;
        boolean ready;

        ProofHistory(UUID actor) {
            this.actor = actor;
        }

        boolean advance(long sequence) {
            return seenThrough.getAndAccumulate(sequence, Math::max) < sequence;
        }

        void bind(long generation, Object resetEpoch) {
            if (this.generation == generation && this.resetEpoch == resetEpoch) return;
            this.generation = generation;
            this.resetEpoch = resetEpoch;
            ready = false;
        }
    }

    private static final AttributeKey<ProofHistory> NATIVE_PROOF_HISTORY = new AttributeKey<>(
        "gtmb.hologram.stockNativeProofHistory");

    private static final class Owned {

        final ActionEndpoint endpoint;
        final Identity key;
        final HologramStockNativeEntry entry;
        int reported;

        Owned(ActionEndpoint endpoint, Identity key, HologramStockNativeEntry entry) {
            this.endpoint = endpoint;
            this.key = key;
            this.entry = entry;
        }
    }

    private static final int INGRESS_CAP = 64, RESULT_CAP = 128, PROOF_CAP = 256;
    private static final long COLD_NANOS = TimeUnit.SECONDS.toNanos(10);
    private final ReplySink sink;
    private final HologramRequestGate probeSlots = new HologramRequestGate(1, 32);
    private final HologramRequestGate beginSlots = new HologramRequestGate(1, 32);
    private final HologramRequestGate statusSlots = new HologramRequestGate(2, 32);
    private final HologramRequestGate stopSlots = new HologramRequestGate(1, 32);
    private final ArrayDeque<Waiting> inbound = new ArrayDeque<>(), stops = new ArrayDeque<>();
    private final Set<Identity> stopQueued = new HashSet<>(); // Covered by inbound's monitor.
    private final Map<UUID, ColdProof> coldProofs = new LinkedHashMap<>();
    private final Map<UUID, Owned> active = new LinkedHashMap<>();
    private final Map<Identity, Owned> unresolved = new LinkedHashMap<>();
    private final Map<Identity, Reply> settled = new LinkedHashMap<>();
    private boolean draining, resetRequested;
    private Object proofEpoch = new Object();

    HologramStockNativeServer(ReplySink sink) {
        this.sink = sink;
    }

    /** Any Netty thread. Endpoint is from MessageContext, never packet identity or an editor workspace. */
    boolean enqueue(ActionEndpoint endpoint, Request request) {
        if (request == null || request.key() == null
            || endpoint == null
            || endpoint.constructionScope == null
            || !HologramWorkspace.currentActionEndpoint(endpoint)) return false;
        boolean probe = request.command() == Command.PROBE;
        long generation = endpoint.constructionScope.generation;
        if (probe ? request.key()
            .generation() != 0
            : request.key()
                .generation() != generation)
            return false;
        boolean begin = request.command()
            .begins();
        boolean stop = request.command() == Command.STOP;
        var gate = probe ? probeSlots : begin ? beginSlots : stop ? stopSlots : statusSlots;
        var slot = gate.acquire(endpoint.connection);
        if (slot == null) return false;
        boolean enqueued = false;
        try {
            Confirmation confirm = request.confirmation();
            ProofHistory reservation = null;
            if (probe) {
                reservation = channelProofHistory(endpoint);
                if (reservation == null || confirm == null
                    || request.key()
                        .sequence() <= reservation.seenThrough.get())
                    return false;
                confirm = confirm.forGeneration(generation);
                request = new Request(Command.PROBE, confirm.key(), confirm);
            }
            var ingress = probe || begin ? HologramStockNativeEntry.inspect(endpoint, confirm) : null;
            var waiting = new Waiting(
                endpoint,
                request,
                ingress == null ? null : ingress.submission(),
                ingress == null ? null : ingress.refusal(),
                slot);
            synchronized (inbound) {
                if (!slot.active() || (!stop && inbound.size() >= INGRESS_CAP)) return false;
                // Burn a bounded authenticated PROBE when queued, even if its endpoint/reset drops it before dispatch.
                if (reservation != null && !reservation.advance(
                    request.key()
                        .sequence()))
                    return false;
                if (stop && !stopQueued.add(waiting.identity())) return false;
                (stop ? stops : inbound).addLast(waiting);
                enqueued = true;
            }
            if (probe || begin) HologramStockActionTrace.event(
                "server.queued",
                request.key(),
                request.command()
                    .name());
            return true;
        } finally {
            if (!enqueued) slot.close();
        }
    }

    /** Called only by the existing server scheduler. One actual tick budget, no forced disk/whole-plan work. */
    void tick(long deadline) {
        tick(deadline, HologramDeclaredNativeConstruction.PlacementWindow.ALL);
    }

    void tick(long deadline, HologramDeclaredNativeConstruction.PlacementWindow placementWindow) {
        if (draining) return;
        draining = true;
        try {
            expireColdProofs(System.nanoTime());
            for (int budget = 0; budget < INGRESS_CAP && !resetRequested && System.nanoTime() < deadline; budget++) {
                Waiting waiting;
                synchronized (inbound) {
                    waiting = stops.pollFirst();
                    if (waiting == null) waiting = inbound.pollFirst();
                }
                if (waiting == null) break;
                try {
                    if (waiting.slot.active() && HologramWorkspace.constructionGate(waiting.endpoint) != null)
                        dispatch(waiting);
                } finally {
                    if (waiting.request.command() == HologramStockNativeWire.Command.STOP) synchronized (inbound) {
                        stopQueued.remove(waiting.identity());
                    }
                    waiting.slot.close();
                }
            }
            // Rotate the checked prefix on budget exhaustion rather than letting the same early actor lead each tick.
            for (Owned job : new ArrayList<>(active.values())) {
                if (resetRequested || System.nanoTime() >= deadline) return;
                if (!active.remove(job.endpoint.actor, job)) continue;
                active.put(job.endpoint.actor, job);
                var state = job.entry.tick(deadline, (gate, lease) -> {
                    synchronized (inbound) {
                        return resetRequested || stopQueued.contains(job.key) ? null : gate.beginTransaction(lease);
                    }
                }, placementWindow);
                if (state == HologramStockNativeConstruction.State.ACTIVE) {
                    if (job.entry.placed() != job.reported) {
                        job.reported = job.entry.placed();
                        emit(job.endpoint, reply(job, Phase.ACTIVE));
                    }
                } else terminal(job, state);
            }
        } finally {
            draining = false;
            if (resetRequested) reset();
        }
    }

    private void dispatch(Waiting waiting) {
        ActionEndpoint endpoint = waiting.endpoint;
        Identity identity = waiting.identity();
        if (waiting.request.command() == Command.PROBE) {
            probe(waiting);
            return;
        }
        Owned current = active.get(endpoint.actor);
        boolean match = current != null && current.endpoint == endpoint && current.key.equals(identity);
        Reply previous = settled.get(identity);
        if (previous != null) {
            emit(endpoint, previous);
            return;
        }
        Owned pending = unresolved.get(identity);
        if (pending != null) {
            if (pending.endpoint == endpoint) emit(endpoint, reply(pending, Phase.RECOVERY_PENDING));
            return;
        }
        var gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null) return;
        var known = gate.query(endpoint.constructionScope, identity.request.gateRequest());
        if (known.phase == HologramConstructionTaskGate.Phase.TERMINAL) {
            var outcome = known.outcome;
            Phase phase = outcome.end == HologramConstructionTaskGate.End.COMPLETE ? Phase.COMPLETE : Phase.STOPPED;
            emit(endpoint, new Reply(identity.request, phase, (int) outcome.committedTransactions, "施工结果已定；详细回执已过期。"));
            return;
        }
        if (known.phase == HologramConstructionTaskGate.Phase.RETIRED_UNKNOWN
            || !match && known.phase != HologramConstructionTaskGate.Phase.UNSEEN) {
            unknown(endpoint, identity);
            return;
        }
        switch (waiting.request.command()) {
            case BEGIN, BEGIN_AFTER_PROBE -> {
                if (match) emit(endpoint, reply(current, Phase.ACTIVE));
                else start(waiting);
            }
            case PROBE -> { /* handled before task dispatch */ }
            case STOP -> {
                if (match && current.entry.stop(endpoint, identity.request.gateRequest()))
                    terminal(current, HologramStockNativeConstruction.State.STOPPED);
                else if (!match && cancelBufferedBegin(waiting))
                    tombstone(waiting.endpoint, identity, Phase.STOPPED, "已取消，未开始施工。");
                else unknown(endpoint, identity);
            }
            case STATUS -> {
                if (match) emit(endpoint, reply(current, Phase.ACTIVE));
                else unknown(endpoint, identity);
            }
        }
    }

    /** Netty may inspect/reserve the watermark; only the owner thread may bind readiness. */
    private ProofHistory channelProofHistory(ActionEndpoint endpoint) {
        if (!HologramWorkspace.currentActionEndpoint(endpoint)) return null;
        var channel = endpoint.connection.netManager.channel();
        if (channel == null || !channel.isOpen() || !HologramWorkspace.currentActionEndpoint(endpoint)) return null;
        var attribute = channel.attr(NATIVE_PROOF_HISTORY);
        ProofHistory history = attribute.get();
        if (history == null) {
            history = new ProofHistory(endpoint.actor);
            ProofHistory existing = attribute.setIfAbsent(history);
            if (existing != null) history = existing;
        }
        return history.actor.equals(endpoint.actor) ? history : null;
    }

    private ProofHistory proofHistory(ActionEndpoint endpoint) {
        ProofHistory history = channelProofHistory(endpoint);
        if (history != null) history.bind(endpoint.constructionScope.generation, proofEpoch);
        return history;
    }

    private void expireColdProofs(long now) {
        // Proofs are inserted in deadline order; completed or replaced entries are explicitly removed.
        for (var iterator = coldProofs.values()
            .iterator(); iterator.hasNext();) {
            if (iterator.next().expires > now) break;
            iterator.remove();
        }
    }

    private void probe(Waiting waiting) {
        var endpoint = waiting.endpoint;
        var key = waiting.request.key();
        ProofHistory history = proofHistory(endpoint);
        if (history == null || key.sequence() != history.seenThrough.get()) return;
        coldProofs.remove(endpoint.actor);
        var gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null || gate.query(endpoint.constructionScope, key.gateRequest()).phase
            != HologramConstructionTaskGate.Phase.UNSEEN) return;
        String refusal = waiting.submission == null ? waiting.refusal : waiting.submission.refusal();
        if (refusal != null) {
            HologramStockActionTrace.event("server.probe_refused", key, refusal);
            emit(endpoint, new Reply(key, Phase.REFUSED, 0, refusal));
            return;
        }
        long now = System.nanoTime();
        expireColdProofs(now);
        if (coldProofs.size() >= PROOF_CAP || !HologramWorkspace.currentActionEndpoint(endpoint)) return;
        coldProofs.put(
            endpoint.actor,
            new ColdProof(endpoint, waiting.request.confirmation(), waiting.submission, now + COLD_NANOS));
        emit(endpoint, new Reply(key, Phase.CAPABLE, 0, ""));
    }

    /** STOP may overtake only the exact authenticated buffered BEGIN, not future or different intentions. */
    private boolean cancelBufferedBegin(Waiting stop) {
        Waiting removed = null;
        synchronized (inbound) {
            for (var iterator = inbound.iterator(); iterator.hasNext();) {
                Waiting queued = iterator.next();
                if (queued.endpoint == stop.endpoint && queued.request.command()
                    .begins()
                    && queued.submission != null
                    && queued.identity()
                        .equals(stop.identity())) {
                    iterator.remove();
                    removed = queued;
                    break;
                }
            }
        }
        if (removed == null) return false;
        removed.slot.close();
        return true;
    }

    private void unknown(ActionEndpoint endpoint, Identity key) {
        emit(endpoint, new Reply(key.request, Phase.UNKNOWN, -1, "没有当前可核实的原生逐格施工结果。"));
    }

    private void tombstone(ActionEndpoint endpoint, Identity key, Phase phase, String reason) {
        // Consume the exact sequence, but never issue a material/world permit for an unstarted intent.
        ProofHistory history = proofHistory(endpoint);
        var gate = HologramWorkspace.constructionGate(endpoint);
        if (history == null || gate == null) return;
        history.advance(key.request.sequence());
        ColdProof proof = coldProofs.get(endpoint.actor);
        if (proof != null && proof.endpoint == endpoint
            && proof.confirmation.key()
                .sequence() <= key.request.sequence())
            coldProofs.remove(endpoint.actor, proof);
        gate.acquire(endpoint.constructionScope, key.request.gateRequest(), true, false);
        remember(endpoint, key, new Reply(key.request, phase, 0, reason));
    }

    private void start(Waiting waiting) {
        Identity key = waiting.identity();
        Owned other = active.get(waiting.endpoint.actor);
        if (other != null && !HologramWorkspace.currentActionEndpoint(other.endpoint)) {
            var retired = other.entry.tick(Long.MAX_VALUE, (current, lease) -> null);
            if (retired != HologramStockNativeConstruction.State.ACTIVE) terminal(other, retired);
        }
        ProofHistory history = proofHistory(waiting.endpoint);
        if (history == null) return;
        long sequence = key.request.sequence();
        boolean cold = waiting.request.command() == Command.BEGIN_AFTER_PROBE;
        HologramStockNativeEntry.Submission submission = waiting.submission;
        String refusal = waiting.refusal;
        long seen = history.seenThrough.get();
        if (cold ? sequence < seen : !history.advance(sequence)) {
            unknown(waiting.endpoint, key);
            return;
        }
        if (cold) {
            ColdProof proof = coldProofs.get(waiting.endpoint.actor);
            if (sequence != seen || proof == null
                || proof.endpoint != waiting.endpoint
                || proof.expires <= System.nanoTime()
                || !proof.confirmation.equals(waiting.request.confirmation())) {
                tombstone(waiting.endpoint, key, Phase.REFUSED, "首次短请求回执已失效；未开始施工。");
                return;
            }
            coldProofs.remove(waiting.endpoint.actor, proof); // Hand off this exact confirmation once, even on refusal.
            if (submission != null) {
                // Ingress must not be able to substitute a newer capability/configuration for the cold proof.
                submission = proof.submitted;
                refusal = submission.refusal();
            }
        } else {
            coldProofs.remove(waiting.endpoint.actor);
            if (!history.ready) {
                tombstone(waiting.endpoint, key, Phase.NEEDS_PROOF, "此服务器会话未完成原生确认；未开始施工。");
                return;
            }
        }
        boolean stopped;
        synchronized (inbound) {
            stopped = stopQueued.contains(key);
        }
        boolean anotherOwner = active.containsKey(waiting.endpoint.actor);
        if (stopped && submission != null) {
            tombstone(waiting.endpoint, key, Phase.STOPPED, "已取消，未开始施工。");
            return;
        }
        if (submission == null || refusal != null || anotherOwner) {
            tombstone(waiting.endpoint, key, Phase.REFUSED, anotherOwner ? "当前另有原生施工任务。" : refusal);
            return;
        }
        HologramStockNativeEntry entry;
        try {
            entry = HologramStockNativeEntry.begin(submission);
        } catch (IOException failure) {
            remember(waiting.endpoint, key, new Reply(key.request, Phase.REFUSED, 0, failure.getMessage()));
            return;
        } catch (RuntimeException | LinkageError blocked) {
            remember(waiting.endpoint, key, new Reply(key.request, Phase.REFUSED, 0, "原生现场或配置无法核对；未开始施工。"));
            return;
        }
        if (cold && !resetRequested && HologramWorkspace.currentActionEndpoint(waiting.endpoint)) history.ready = true;
        var job = new Owned(waiting.endpoint, key, entry);
        active.put(waiting.endpoint.actor, job);
        // A STOP arriving during native admission cannot reach a permit before this queue check.
        synchronized (inbound) {
            stopped = stopQueued.contains(key);
        }
        if (stopped && entry.stop(waiting.endpoint, key.request.gateRequest()))
            terminal(job, HologramStockNativeConstruction.State.STOPPED);
        else emit(waiting.endpoint, reply(job, Phase.ACCEPTED));
    }

    private void terminal(Owned job, HologramStockNativeConstruction.State state) {
        active.remove(job.endpoint.actor, job);
        if (state == HologramStockNativeConstruction.State.RECOVERY_PENDING) {
            // Retain the original authority/evidence even after disconnect or an in-process display reset.
            unresolved.put(job.key, job);
            emit(job.endpoint, reply(job, Phase.RECOVERY_PENDING));
            return;
        }
        remember(
            job.endpoint,
            job.key,
            reply(job, state == HologramStockNativeConstruction.State.COMPLETE ? Phase.COMPLETE : Phase.STOPPED));
    }

    private static Reply reply(Owned job, Phase phase) {
        return new Reply(job.key.request, phase, job.entry.placed(), job.entry.reason());
    }

    private void remember(ActionEndpoint endpoint, Identity key, Reply reply) {
        settled.put(key, reply);
        while (settled.size() > RESULT_CAP) settled.remove(
            settled.keySet()
                .iterator()
                .next());
        emit(endpoint, reply);
    }

    private void emit(ActionEndpoint endpoint, Reply reply) {
        if (!HologramWorkspace.currentActionEndpoint(endpoint)) return;
        try {
            sink.send(endpoint, reply);
            if (reply.phase() != Phase.ACTIVE) HologramStockActionTrace.event(
                "server.reply",
                reply.key(),
                reply.phase()
                    .name());
        } catch (RuntimeException | LinkageError unavailable) {
            // Status is not transaction authority. The same owner can query a settled/active result.
        }
    }

    boolean owns(HologramSession session) {
        Owned job = active.get(session.player.getUniqueID());
        if (job != null && job.entry.owns(session)) return true;
        for (Owned pending : unresolved.values()) if (pending.entry.owns(session)) return true;
        return false;
    }

    /** Old owner thread only; discard queued controls, not an unresolved permit. */
    void reset() {
        if (draining) {
            resetRequested = true;
            return;
        }
        resetRequested = false;
        proofEpoch = new Object(); // Invalidate warm/pending authorizations, never the channel lifetime watermark.
        synchronized (inbound) {
            for (Waiting waiting : inbound) waiting.slot.close();
            for (Waiting stopping : stops) stopping.slot.close();
            inbound.clear();
            stops.clear();
            stopQueued.clear();
        }
        for (Owned job : new ArrayList<>(active.values())) {
            if (job.entry.stop(job.endpoint, job.key.request.gateRequest())) {
                terminal(job, HologramStockNativeConstruction.State.STOPPED);
            } else if (!HologramWorkspace.currentActionEndpoint(job.endpoint)) {
                var state = job.entry.tick(Long.MAX_VALUE, (current, lease) -> null);
                if (state != HologramStockNativeConstruction.State.ACTIVE) terminal(job, state);
            }
        }
        coldProofs.clear();
        settled.clear();
    }
}
