// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Command;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Request;
import com.miaokatze.gtmb.hologram.HologramWorkspace.ActionEndpoint;

/** Bounded transport inbox, sliced observations and replies. World-changing work belongs to ConstructionJobs. */
final class HologramStockNativeServer {

    @FunctionalInterface
    interface ReplySink {

        void send(ActionEndpoint endpoint, Reply reply);
    }

    private static final class Waiting implements AutoCloseable {

        final ActionEndpoint endpoint;
        final Request request;
        final HologramRequestGate.Lease slot;
        HologramNativeProjectionJob.Observation observation;

        Waiting(ActionEndpoint endpoint, Request request, HologramRequestGate.Lease slot) {
            this.endpoint = endpoint;
            this.request = request;
            this.slot = slot;
        }

        public void close() {
            try {
                if (observation != null) {
                    observation.close();
                    observation = null;
                }
            } finally {
                slot.close();
            }
        }
    }

    @com.github.bsideup.jabel.Desugar
    private record Identity(UUID actor, Key request) {}

    @com.github.bsideup.jabel.Desugar
    private record Owned(ActionEndpoint endpoint, Key key, HologramStockNativeEntry entry) {}

    private final ReplySink sink;
    private final HologramRequestGate requests = new HologramRequestGate(2, 64),
        controls = new HologramRequestGate(2, 64);
    private final ArrayDeque<Waiting> inbox = new ArrayDeque<>(), stops = new ArrayDeque<>();
    private final Map<UUID, Owned> reporting = new LinkedHashMap<>();
    private final Map<Identity, Reply> results = new LinkedHashMap<>();
    private boolean draining;

    HologramStockNativeServer(ReplySink sink) {
        this.sink = sink;
    }

    boolean enqueue(ActionEndpoint endpoint, Request request) {
        if (request == null || endpoint == null || !HologramWorkspace.currentActionEndpoint(endpoint)) return false;
        boolean stop = request.command() == Command.STOP;
        var slot = (stop ? controls : requests).acquire(endpoint.connection);
        if (slot == null) return false;
        synchronized (inbox) {
            if ((stop ? stops : inbox).size() >= 64) {
                slot.close();
                return false;
            }
            (stop ? stops : inbox).addLast(new Waiting(endpoint, request, slot));
        }
        return true;
    }

    void tick(long deadline) {
        if (draining) return;
        draining = true;
        try {
            for (int work = 0; work < 64 && (work == 0 || System.nanoTime() < deadline); work++) {
                Waiting waiting;
                synchronized (inbox) {
                    waiting = !stops.isEmpty() ? stops.pollFirst() : inbox.pollFirst();
                }
                if (waiting == null) break;
                boolean complete = true;
                try {
                    if (waiting.slot.active() && HologramWorkspace.currentActionEndpoint(waiting.endpoint))
                        complete = dispatch(waiting, deadline);
                } finally {
                    if (complete) waiting.close();
                    else if (waiting.slot.active() && HologramWorkspace.currentActionEndpoint(waiting.endpoint)) {
                        synchronized (inbox) {
                            inbox.addLast(waiting);
                        }
                    } else waiting.close();
                }
            }
            for (Owned owned : new ArrayList<>(reporting.values())) {
                var state = owned.entry.state();
                var phase = state == HologramNativeProjectionJob.State.COMPLETE ? Phase.COMPLETE
                    : state == HologramNativeProjectionJob.State.RECOVERY_PENDING ? Phase.RECOVERY_PENDING
                        : state == HologramNativeProjectionJob.State.STOPPED ? Phase.STOPPED : Phase.ACTIVE;
                Reply reply = new Reply(owned.key, phase, owned.entry.placed(), owned.entry.reason());
                if (phase != Phase.ACTIVE) {
                    reporting.remove(owned.endpoint.actor, owned);
                    remember(owned.endpoint, reply);
                } else emit(owned.endpoint, reply);
            }
        } finally {
            draining = false;
        }
    }

    private boolean dispatch(Waiting waiting, long deadline) {
        if (waiting.observation != null) return advanceObservation(waiting, deadline);
        var endpoint = waiting.endpoint;
        var request = waiting.request;
        var key = request.key();
        var known = results.get(new Identity(endpoint.actor, key));
        if (known != null) {
            emit(endpoint, known);
            return true;
        }
        var owned = reporting.get(endpoint.actor);
        boolean exact = owned != null && owned.endpoint == endpoint && owned.key.equals(key);
        if (request.command() == Command.STATUS) {
            emit(
                endpoint,
                exact ? new Reply(key, Phase.ACTIVE, owned.entry.placed(), owned.entry.reason())
                    : new Reply(key, Phase.UNKNOWN, -1, "没有当前可核实的施工结果。"));
            return true;
        }
        if (request.command() == Command.STOP) {
            if (exact) owned.entry.stop(endpoint, key);
            else {
                // A cancel may overtake only its exact buffered construction or observation.
                synchronized (inbox) {
                    for (var iterator = inbox.iterator(); iterator.hasNext();) {
                        var queued = iterator.next();
                        if (queued.endpoint == endpoint
                            && (queued.request.command() == Command.BEGIN || queued.request.command() == Command.PROBE)
                            && queued.request.key()
                                .equals(key)) {
                            iterator.remove();
                            queued.close();
                            if (queued.request.command() == Command.BEGIN) {
                                HologramConstructionJobs.retire(endpoint.connection, key.sequence());
                                remember(endpoint, new Reply(key, Phase.STOPPED, 0, "施工已取消，未开始执行。"));
                            } else emit(endpoint, new Reply(key, Phase.STOPPED, 0, "原生观察已取消。"));
                            return true;
                        }
                    }
                }
                emit(endpoint, new Reply(key, Phase.UNKNOWN, -1, "没有当前可核实的施工结果。"));
            }
            return true;
        }
        if (request.command() == Command.BEGIN && exact) {
            emit(endpoint, new Reply(key, Phase.ACTIVE, owned.entry.placed(), owned.entry.reason()));
            return true;
        }
        var ingress = HologramStockNativeEntry.inspect(endpoint, request.confirmation());
        if (ingress.submission() == null) {
            emit(endpoint, new Reply(key, Phase.REFUSED, 0, ingress.refusal()));
            return true;
        }
        if (request.command() == Command.PROBE) {
            try {
                waiting.observation = HologramStockNativeEntry.beginObservation(ingress.submission());
                return advanceObservation(waiting, deadline);
            } catch (IOException | RuntimeException | LinkageError failure) {
                emit(endpoint, new Reply(key, Phase.REFUSED, 0, detail(failure)));
                return true;
            }
        }
        if (!HologramConstructionJobs.retire(endpoint.connection, key.sequence())) {
            emit(endpoint, new Reply(key, Phase.UNKNOWN, -1, "施工请求已处理，未重复执行。"));
            return true;
        }
        try {
            var entry = HologramStockNativeEntry.begin(ingress.submission());
            reporting.put(endpoint.actor, new Owned(endpoint, key, entry));
            emit(endpoint, new Reply(key, Phase.ACCEPTED, 0, ""));
        } catch (IOException | RuntimeException | LinkageError failure) {
            remember(endpoint, new Reply(key, Phase.REFUSED, 0, detail(failure)));
        }
        return true;
    }

    private boolean advanceObservation(Waiting waiting, long deadline) {
        try {
            if (!waiting.observation.advance(deadline)) return false;
            if (!waiting.slot.active()) return true;
            emit(
                waiting.endpoint,
                new Reply(waiting.request.key(), Phase.CAPABLE, 0, "", waiting.observation.result()));
        } catch (IOException | RuntimeException | LinkageError failure) {
            emit(waiting.endpoint, new Reply(waiting.request.key(), Phase.REFUSED, 0, detail(failure)));
        }
        return true;
    }

    private static String detail(Throwable failure) {
        return failure.getMessage() == null ? "无法开始施工。" : failure.getMessage();
    }

    private void remember(ActionEndpoint endpoint, Reply reply) {
        results.put(new Identity(endpoint.actor, reply.key()), reply);
        while (results.size() > 128) results.remove(
            results.keySet()
                .iterator()
                .next());
        emit(endpoint, reply);
    }

    private void emit(ActionEndpoint endpoint, Reply reply) {
        if (!HologramWorkspace.currentActionEndpoint(endpoint)) return;
        try {
            sink.send(endpoint, reply);
        } catch (RuntimeException | LinkageError unavailable) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.debug("Construction reply unavailable", unavailable);
        }
    }

    boolean owns(HologramSession session) {
        var job = HologramConstructionJobs.find(session.player);
        return job != null && job.session == session;
    }

    void reset() {
        HologramNativeProjectionJob.clearBaselines();
        for (var owned : reporting.values()) owned.entry.stop(owned.endpoint, owned.key);
        reporting.clear();
        results.clear();
        synchronized (inbox) {
            for (var waiting : inbox) waiting.close();
            for (var waiting : stops) waiting.close();
            inbox.clear();
            stops.clear();
        }
        requests.clear();
        controls.clear();
    }
}
