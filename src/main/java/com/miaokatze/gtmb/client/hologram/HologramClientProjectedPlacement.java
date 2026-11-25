// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtmb.client.hologram.HologramLibraryClient.PlainPlacementCandidate;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.CommitReceipt;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Outcome;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Phase;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Ticket;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Prepared;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Control;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reply;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Request;

/** The default confirmed private-file route. One owned worker, one frame in flight, no legacy geometry fallback. */
public final class HologramClientProjectedPlacement {

    private static final long RETRY = TimeUnit.SECONDS.toNanos(2), PROBE_LIMIT = TimeUnit.SECONDS.toNanos(8);
    private static final long ASSEMBLY_LIMIT = TimeUnit.SECONDS.toNanos(60), CONTACT_LIMIT = TimeUnit.SECONDS.toNanos(25);
    private static final long STOP_LIMIT = TimeUnit.SECONDS.toNanos(12), STOP_CONTROL = 1;
    private static volatile Wire wire;
    private static volatile Task active;

    private HologramClientProjectedPlacement() {}

    /** Bridge to one shared, registered GTMB play channel; it is never installed by a preview. */
    public interface Wire {

        /** One monotonic client construction-intent source shared with the other registered construction senders. */
        long nextIntentSequence();

        /** The only pre-admission message: exact construction intent and supported scope, no file or geometry. */
        boolean probe(UUID request, long intentSequence);

        /** Return false if not enqueued. An identical message remains safe to retry while the lease is current. */
        boolean send(Request request);
    }

    public static void install(Wire transport) {
        wire = transport;
        HologramProjectedLayerTransport.setClientReceiver(transport == null ? null
            : HologramClientProjectedPlacement::networkReceipt);
    }

    /** Invoked by the registered no-geometry capability receipt handler using its actual inbound play handler. */
    public static void negotiated(UUID request, long intentSequence, int version, long scopeGeneration,
        Object inboundHandler) {
        Task job = active;
        if (job != null && job.matches(request, intentSequence, inboundHandler)
            && version == HologramProjectedLayerTransport.VERSION && scopeGeneration > 0)
            job.negotiation.compareAndSet(0, scopeGeneration);
    }

    private static void networkReceipt(Reply reply, Object inboundHandler) {
        Task job = active;
        if (job == null || !scoped(job.source.request, job.intent, job.scope, job.connection, reply, inboundHandler))
            return;
        // Network threads publish at most one owned status. A newer valid credit's successor is only sent after our
        // next frame; ordinary progress can be coalesced, and a terminal is never evicted by delayed progress.
        job.inbox.accumulateAndGet(reply, HologramClientProjectedPlacement::newer);
    }

    static boolean scoped(UUID request, long intent, long scope, Object handler, Reply reply, Object inboundHandler) {
        return reply != null && request.equals(reply.request) && intent == reply.intentSequence && scope > 0
            && scope == reply.scopeGeneration && handler == inboundHandler;
    }

    static Reply newer(Reply previous, Reply next) {
        if (previous == null) return next;
        if (next == null || previous.sequence > next.sequence
            || previous.sequence == next.sequence && previous.controlSequence > next.controlSequence
            || terminal(previous) && !terminal(next)) return previous;
        if (previous.sequence == next.sequence && previous.controlSequence == next.controlSequence
            && previous.acknowledgement() != null && next.acknowledgement() == null && !terminal(next)) return previous;
        return next;
    }

    static boolean transportRegistered() {
        return wire != null;
    }

    static void logMissingTransport() {
        com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn(
            "No registered sliced placement transport; confirmed local placement refused before any payload.");
    }

    static boolean start(HologramLibraryClient owner, PlainPlacementCandidate source, Minecraft client) {
        Wire transport = wire;
        if (transport == null) {
            logMissingTransport();
            return false;
        }
        if (active != null || source == null || !source.currentBeforeAdmission(owner, client)) return false;
        long intent;
        try {
            intent = transport.nextIntentSequence();
        } catch (RuntimeException | LinkageError disconnected) {
            return false;
        }
        if (intent < 1) return false;
        Task job = new Task(owner, source, client, transport, intent, System.nanoTime());
        job.ticket = HologramBackgroundTasks.beginOwnedPlacement(source.request, "施工", job::requestStop);
        if (job.ticket == null) return false;
        active = job; // Probe may be acknowledged only against this captured play handler.
        job.probe(job.startedAt);
        owner.projectedWork(() -> {
            Prepared prepared = source.prepare(owner, job.observed::set);
            if (!source.workerCurrent(owner)) {
                prepared.close();
                return;
            }
            byte[] manifest = prepared.takeManifest();
            owner.projectedMain(() -> job.prepared(prepared, manifest));
        }, failure -> job.localFailure("无法准备锁定范围，未开始施工"));
        return true;
    }

    static void showCurrent() {
        Task job = active;
        if (job != null) HologramBackgroundTasks.showOwnedPlacement(job.ticket);
    }

    static boolean stopCurrent() {
        Task job = active;
        return job != null && job.requestStop();
    }

    static String contextLostText(Ticket ticket) {
        Task job = active;
        return ticket != null && job != null && job.ticket == ticket && !job.beginMayHaveQueued
            ? "施工来源已离开，未发送施工数据"
            : "原世界或连接已离开，服务器施工结果未知";
    }

    static void tick(Minecraft client) {
        Task job = active;
        if (job != null) job.tick(client, System.nanoTime());
    }

    private static boolean terminal(Reply receipt) {
        return switch (receipt.phase) {
            case COMPLETE, STOPPED, REJECTED, RECOVERY_PENDING -> true;
            default -> false;
        };
    }

    private static final class Task {

        final HologramLibraryClient owner;
        final PlainPlacementCandidate source;
        final Wire wire;
        final Object connection, world, player;
        final long intent, startedAt;
        final AtomicLong negotiation = new AtomicLong(), observed = new AtomicLong();
        final AtomicReference<Reply> inbox = new AtomicReference<>();
        volatile long scope;
        Ticket ticket;
        Prepared prepared;
        byte[] manifest;
        Request inFlight;
        long lastProbe, lastSend, lastContact, stopAt, lastStop, lastSequence = -1, shownRows;
        int nextOrdinal, inFlightOrdinal = -1, placed, satisfied, total;
        boolean beginAttempted, beginMayHaveQueued, admitted, computing, stopAcknowledged, executing;
        volatile boolean stopping;
        String toolId;

        Task(HologramLibraryClient owner, PlainPlacementCandidate source, Minecraft client, Wire wire, long intent,
            long now) {
            this.owner = owner;
            this.source = source;
            this.wire = wire;
            connection = client.getNetHandler();
            world = client.theWorld;
            player = client.thePlayer;
            this.intent = intent;
            startedAt = lastContact = now;
            toolId = source.toolId;
        }

        boolean matches(UUID request, long sequence, Object handler) {
            return source.request.equals(request) && sequence == intent && handler == connection;
        }

        boolean belongs(Minecraft mc) {
            return mc != null && mc.getNetHandler() == connection && mc.theWorld == world && mc.thePlayer == player;
        }

        boolean current(Minecraft mc) {
            if (!belongs(mc) || !source.workerCurrent(owner) || wire != HologramClientProjectedPlacement.wire) return false;
            if (!admitted) return source.currentBeforeAdmission(owner, mc);
            return source.currentAfterAdmission(owner, mc, toolId);
        }

        boolean requestStop() {
            if (active != this || stopping) return false;
            stopping = true;
            stopAt = System.nanoTime();
            return true;
        }

        void probe(long now) {
            lastProbe = now;
            try {
                wire.probe(source.request, intent);
            } catch (RuntimeException | LinkageError notConnected) {
                // No Begin has been attempted. The bounded probe timeout reports refusal and never falls back.
            }
        }

        void prepared(Prepared encoding, byte[] header) {
            if (active != this || stopping || !source.workerCurrent(owner)) return;
            prepared = encoding;
            manifest = header;
        }

        void tick(Minecraft mc, long now) {
            if (!belongs(mc) || !source.workerCurrent(owner) || wire != HologramClientProjectedPlacement.wire) {
                localFailure(beginMayHaveQueued ? "原世界或连接已离开，服务器施工结果未知" : "施工来源已变化，本次未开始施工");
                return;
            }
            Reply received = inbox.getAndSet(null);
            if (received != null) receipt(received, mc, now);
            if (active != this) return;
            if (!current(mc)) requestStop(); // A revoked slot/locked source cannot advance the uploader.
            if (stopping) {
                if (!beginMayHaveQueued) {
                    finishLocal(Outcome.CANCELLED, "已取消，未发送施工数据");
                    return;
                }
                if (now - stopAt >= STOP_LIMIT) {
                    finishLocal(Outcome.FAILED, "已请求停止，尚未收到服务器确认；施工结果未知");
                    return;
                }
                stop(now);
                return;
            }
            long rows = observed.get();
            if (!beginAttempted && rows > shownRows) {
                shownRows = rows;
                HologramBackgroundTasks.observeOwnedPlacement(ticket, Phase.PREPARING, rows, rows, source.cells());
            }
            if (scope == 0) {
                scope = negotiation.get();
                if (scope == 0) {
                    if (now - startedAt >= PROBE_LIMIT) localFailure("服务器未确认分片施工协议，未发送施工数据");
                    else if (now - lastProbe >= RETRY) probe(now);
                    return;
                }
            }
            if (!beginAttempted) {
                if (manifest == null) {
                    if (now - startedAt >= ASSEMBLY_LIMIT) localFailure("施工范围准备超时，未发送施工数据");
                    return;
                }
                try {
                    inFlight = HologramProjectedLayerTransport.begin(source.request, intent, scope, manifest);
                } catch (IllegalArgumentException incompatible) {
                    localFailure("施工协议与锁定范围不相容，未发送施工数据");
                    return;
                }
                manifest = null;
                beginAttempted = true;
                lastContact = now;
                HologramBackgroundTasks.observeOwnedPlacement(ticket, Phase.ADMISSION, 0, 0, 0);
                send(now, inFlight);
            } else if (now - lastContact >= CONTACT_LIMIT) {
                if (!beginMayHaveQueued) localFailure("分片施工请求未送出，未开始施工");
                else {
                    requestStop();
                    stop(now);
                }
            } else if (inFlight != null && now - lastSend >= RETRY) send(now, inFlight);
        }

        void send(long now, Request message) {
            lastSend = now;
            boolean begin = message == inFlight && inFlightOrdinal < 0;
            try {
                if (wire.send(message) && begin) beginMayHaveQueued = true;
            } catch (RuntimeException | LinkageError disconnected) {
                // A throw does not establish whether the initial write reached the network queue.
                if (begin) beginMayHaveQueued = true;
            }
        }

        void stop(long now) {
            if (stopAcknowledged || lastStop != 0 && now - lastStop < RETRY) return;
            lastStop = now;
            send(now, HologramProjectedLayerTransport.control(source.request, intent, scope, STOP_CONTROL, Control.STOP));
        }

        void receipt(Reply receipt, Minecraft mc, long now) {
            if (!beginAttempted || !scoped(source.request, intent, scope, connection, receipt, mc.getNetHandler())
                || receipt.sequence < lastSequence) return;
            boolean refused = receipt.phase == HologramProjectedLayerTransport.Phase.REJECTED;
            if (!refused && receipt.totalCells != source.cells()) return;
            lastContact = now;
            beginMayHaveQueued = true;
            if (stopping && receipt.controlSequence == STOP_CONTROL) stopAcknowledged = true;
            if (terminal(receipt)) {
                terminalReceipt(receipt);
                return;
            }
            if (receipt.sequence > lastSequence) {
                lastSequence = receipt.sequence;
                placed = receipt.placed;
                satisfied = receipt.alreadySatisfied;
                total = receipt.totalCells;
            }
            if (!admitted) admitted = true;
            if (toolId.isEmpty()) toolId = source.admittedToolId(mc);
            if (!current(mc)) {
                requestStop();
                return;
            }
            if (inFlight != null && (inFlightOrdinal < 0 || receipt.acceptedSlices > inFlightOrdinal)) inFlight = null;
            boolean hasRun = (long) placed + satisfied > 0
                || receipt.phase == HologramProjectedLayerTransport.Phase.PLACING;
            if (executing || hasRun) {
                executing = true;
                HologramBackgroundTasks.observeOwnedPlacement(
                    ticket, Phase.EXECUTING, receipt.sequence, (long) placed + satisfied, total);
            }
            if (stopping || computing || prepared == null || inFlight != null) return;
            byte[] ack = receipt.acknowledgement();
            if (ack == null || receipt.acceptedSlices != nextOrdinal || receipt.settledSlices != nextOrdinal) return;
            computing = true;
            Prepared encoding = prepared;
            owner.projectedWork(() -> {
                var credit = encoding.onAck(ack, () -> !stopping && source.workerCurrent(owner) && active == this);
                owner.projectedMain(() -> preparedCredit(credit));
            }, failure -> {
                computing = false;
                requestStop();
            });
        }

        void preparedCredit(com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Credit credit) {
            computing = false;
            Minecraft mc = Minecraft.getMinecraft();
            if (active != this || stopping || !current(mc)) return;
            if (credit.nextFrame() == null) return; // ACK completion and no-credit status cannot complete a job.
            try {
                inFlight = HologramProjectedLayerTransport.slice(source.request, intent, scope, credit.nextFrame());
            } catch (IllegalArgumentException incompatible) {
                requestStop();
                return;
            }
            inFlightOrdinal = nextOrdinal++;
            send(System.nanoTime(), inFlight);
        }

        void terminalReceipt(Reply nativeResult) {
            boolean passed = false;
            String detail = detail(nativeResult);
            switch (nativeResult.phase) {
                case COMPLETE -> {
                    Outcome result = nativeResult.placed > 0 ? Outcome.SUCCESS : Outcome.NO_WORK;
                    passed = HologramBackgroundTasks.acceptOwnedPlacementCommit(ticket,
                        new CommitReceipt(source.request, nativeResult.sequence, result, nativeResult.placed,
                            nativeResult.alreadySatisfied, nativeResult.totalCells),
                        detail);
                }
                case REJECTED, STOPPED, RECOVERY_PENDING -> {
                    boolean cancelled = nativeResult.reason == HologramProjectedLayerTransport.Reason.CANCELLED;
                    passed = HologramBackgroundTasks.finishOwnedPlacement(ticket, nativeResult.sequence,
                        cancelled ? Outcome.CANCELLED : Outcome.FAILED, nativeResult.placed,
                        nativeResult.alreadySatisfied, nativeResult.totalCells, detail);
                }
                default -> {}
            }
            if (passed) release(nativeResult.phase == HologramProjectedLayerTransport.Phase.COMPLETE);
        }

        void localFailure(String text) {
            if (active == this) finishLocal(Outcome.FAILED, text);
        }

        void finishLocal(Outcome outcome, String text) {
            HologramBackgroundTasks.finishOwnedPlacement(
                ticket, Math.max(0, lastSequence + 1), outcome, placed, satisfied, total, text);
            release(false);
        }

        void release(boolean completed) {
            if (active != this) return;
            active = null;
            inbox.set(null);
            prepared = null;
            manifest = null;
            inFlight = null;
            owner.projectedPlacementEnded(source.request, completed);
        }
    }

    static String detail(Reply result) {
        if (result.phase == HologramProjectedLayerTransport.Phase.COMPLETE)
            return result.placed == 0 ? "目标已满足，无需放置" : "施工完成，已放置 " + result.placed + " 格";
        String progress = result.placed > 0 ? "；已放置 " + result.placed + " 格" : "";
        return switch (result.reason) {
            case CANCELLED -> result.phase == HologramProjectedLayerTransport.Phase.REJECTED
                ? "已取消，未开始施工" : "服务器已停止施工" + progress;
            case MISSING_STOCK -> "材料不足，未继续施工" + progress;
            case RECOVERY_PENDING -> "已停止继续施工；有操作等待核定" + progress;
            case RETIRED_UNKNOWN -> "服务器未能确认本次施工结果";
            case OWNER_CHANGED -> "原施工连接、位置或投影仪已变化" + progress;
            case BUSY -> "服务器已有施工任务，本次未开始";
            case ADMISSION_REFUSED -> "服务器未接受当前施工条件，本次未开始";
            case BLOCKED, WORLD_CHANGED -> "目标现场已变化，未继续施工" + progress;
            default -> "服务器未继续施工" + progress;
        };
    }
}
