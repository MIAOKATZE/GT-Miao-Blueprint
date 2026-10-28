package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;

/** Bounded source upload -> owner-thread pure review -> bounded response. Never creates a Session. */
final class HologramAuthorReviewService {

    private static final ConcurrentHashMap<UUID, Ticket> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
        1,
        1,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(2),
        runnable -> {
            Thread thread = new Thread(runnable, "GTMB-author-review-io");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());

    private HologramAuthorReviewService() {}

    private static final class Ticket {

        final EntityPlayerMP player;
        final Object connection, world, tool;
        final int slot;
        final NBTTagCompound proof;
        final HologramProcessGovernor.Lease lease;
        final java.util.concurrent.atomic.AtomicInteger references = new java.util.concurrent.atomic.AtomicInteger(1);
        AuthorDocument source;
        NBTTagCompound policy;
        boolean selectedCell;
        HologramAuthorServerReview.NativeTask nativeTask;
        HologramLibraryDownload.Assembly input;
        final java.util.ArrayDeque<Chunk> chunks = new java.util.ArrayDeque<>();
        final int total;
        int received, receivedBytes;
        volatile byte[] output;
        volatile String error, outputHash;
        volatile boolean retired;
        final HologramAuthorReviewDeadline deadline;
        int sent;

        Ticket(EntityPlayerMP player, NBTTagCompound begin, HologramProcessGovernor.Lease lease) throws IOException {
            this.player = player;
            connection = player.playerNetServerHandler;
            world = player.worldObj;
            tool = player.getHeldItem();
            slot = player.inventory.currentItem;
            proof = HologramAuthorReviewProtocol.packet(begin, "authorReviewBegin");
            this.lease = lease;
            total = begin.getInteger("total");
            HologramServerLibrary backend = HologramLibraryFacade.backend();
            HologramLibraryConfig.View config = !HologramLibraryFacade.enabled() || backend == null ? null
                : backend.config();
            if (HologramLibraryFacade.enabled() && config == null)
                throw new IOException("Author review bandwidth configuration is unavailable");
            deadline = new HologramAuthorReviewDeadline(
                total,
                config == null ? 1024 * 1024 : config.uploadKiB * 1024,
                config == null ? 1024 * 1024 : config.downloadKiB * 1024,
                System.nanoTime());
            input = new HologramLibraryDownload.Assembly(total);
        }

        boolean current() {
            return !retired && ACTIVE.get(HologramLibraryFacade.actorId(player)) == this
                && connection == player.playerNetServerHandler
                && world == player.worldObj
                && tool == player.getHeldItem()
                && slot == player.inventory.currentItem
                && HologramLibraryService.connectedProjector(player);
        }

        synchronized void close() {
            if (retired) return;
            retired = true;
            source = null;
            policy = null;
            try {
                if (nativeTask != null) nativeTask.close();
            } finally {
                nativeTask = null;
                if (input != null) input.cancel();
                input = null;
                chunks.clear();
                output = null;
                release();
            }
        }

        void release() {
            if (references.decrementAndGet() == 0) lease.close();
        }
    }

    /** Netty admission only reads the exact owner-bound ticket; no native callbacks here. */
    static boolean admitted(EntityPlayerMP player, NBTTagCompound packet) {
        Ticket ticket = ACTIVE.get(HologramLibraryFacade.actorId(player));
        return ticket != null && !ticket.retired
            && ticket.player == player
            && HologramAuthorReviewProtocol.matches(ticket.proof, packet);
    }

    static void receive(EntityPlayerMP player, NBTTagCompound request) {
        String op = request.getString("op");
        UUID owner = HologramLibraryFacade.actorId(player);
        Ticket ticket = ACTIVE.get(owner);
        try {
            if ("authorReviewBegin".equals(op)) {
                if (!HologramLibraryService.contentCurrent(player, request))
                    throw new IOException("Author review requires the connected projector owner");
                if (ticket != null || ACTIVE.size() >= HologramAuthorReviewDeadline.MAX_CONTENDERS)
                    throw new IOException("Author review queue is busy; retry with recovery draft retained");
                HologramLibraryProtocol.header(
                    request.getString("transfer"),
                    request.getInteger("total"),
                    request.getString("sourceHash"));
                if (request.getInteger("total") > HologramAuthorReviewProtocol.MAX_BYTES
                    || !HologramAuthorReviewProtocol.intent(request.getString("intent"))
                    || request.getLong("planRevision") < 0
                    || !request.getString("documentId")
                        .matches("[A-Za-z0-9_-]{1,128}"))
                    throw new IOException("Invalid author review identity/intent/budget");
                HologramProcessGovernor.Lease lease = HologramProcessGovernor
                    .tryRetain("author.review", HologramAuthorReviewProtocol.RETAINED_BYTES);
                if (lease == null) throw new IOException("Author review memory quota is busy");
                try {
                    ticket = new Ticket(player, request, lease);
                } catch (IOException | RuntimeException failed) {
                    lease.close();
                    throw failed;
                }
                ACTIVE.put(owner, ticket);
                NBTTagCompound ready = send(ticket, "authorReviewReady", false);
                ticket.deadline.advertise(ready);
                ready.setInteger("nextIndex", 0);
                ready.setInteger("uploadWindow", HologramLibraryProtocol.MAX_UPLOAD_WINDOW);
                HologramNetwork.sendLibraryReply(player, ready);
                return;
            }
            if (ticket == null || ticket.player != player
                || !HologramAuthorReviewProtocol.matches(ticket.proof, request)) return;
            if ("authorReviewCancel".equals(op)) {
                retire(ticket);
                return;
            }
            if (!ticket.current()) {
                retire(ticket);
                return;
            }
            String timeout = ticket.deadline.timeout(System.nanoTime());
            if (timeout != null) throw new IOException(timeout + "; recovery source retained on client");
            if ("authorReviewChunk".equals(op)) {
                if (ticket.input == null) throw new IOException("Author review upload already completed");
                byte[] data = request.getByteArray("data");
                HologramLibraryProtocol.chunk(
                    ticket.total,
                    ticket.receivedBytes,
                    ticket.received,
                    request.getInteger("index"),
                    data.length);
                if (ticket.chunks.size() >= HologramLibraryProtocol.MAX_UPLOAD_WINDOW
                    || request.getBoolean("last") != (ticket.receivedBytes + data.length == ticket.total))
                    throw new IOException("Author review upload credit/end marker exceeded");
                ticket.chunks.addLast(new Chunk(ticket.received++, data, request.getBoolean("last")));
                ticket.receivedBytes += data.length;
                drain(ticket);
            } else if ("authorReviewPoll".equals(op)) {
                if (ticket.error != null) {
                    fail(ticket, ticket.error);
                    return;
                }
                if (ticket.output == null) {
                    send(ticket, "authorReviewWaiting");
                    return;
                }
                int expected = request.getInteger("index");
                if (expected != ticket.sent) throw new IOException("Author review response sequence changed");
                byte[] response = ticket.output;
                for (int credit = 0; credit < HologramLibraryProtocol.MAX_UPLOAD_WINDOW; credit++) {
                    int offset = ticket.sent * HologramLibraryProtocol.CHUNK_BYTES;
                    if (offset >= response.length) {
                        retire(ticket);
                        return;
                    }
                    int count = Math.min(HologramLibraryProtocol.CHUNK_BYTES, response.length - offset);
                    if (!HologramLibraryFacade.consumeDownloadAllowance(owner, count)) {
                        send(ticket, "authorReviewWaiting");
                        return;
                    }
                    NBTTagCompound reply = send(ticket, "authorReviewResult", false);
                    reply.setInteger("index", ticket.sent);
                    reply.setInteger("total", response.length);
                    reply.setString("resultHash", ticket.outputHash);
                    reply.setByteArray("data", java.util.Arrays.copyOfRange(response, offset, offset + count));
                    reply.setBoolean("last", offset + count == response.length);
                    reply.setBoolean("windowEnd", credit + 1 == HologramLibraryProtocol.MAX_UPLOAD_WINDOW);
                    ticket.sent++;
                    ticket.deadline.progress(System.nanoTime());
                    HologramNetwork.sendLibraryReply(player, reply);
                    if (offset + count == response.length) {
                        retire(ticket);
                        return;
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            // A rejected new ticket must never cancel an existing owner's different task.
            if (ticket != null && HologramAuthorReviewProtocol.matches(ticket.proof, request))
                fail(ticket, failure.getMessage());
            else error(player, request, failure.getMessage());
        }
    }

    private static final class Chunk {

        final int index;
        final byte[] data;
        final boolean last;

        Chunk(int index, byte[] data, boolean last) {
            this.index = index;
            this.data = data;
            this.last = last;
        }
    }

    /** Fixed upload credit is retained until shared configured bandwidth admits each queued chunk. */
    private static void drain(Ticket ticket) throws IOException {
        while (!ticket.chunks.isEmpty()) {
            Chunk chunk = ticket.chunks.peekFirst();
            HologramServerLibrary backend = HologramLibraryFacade.backend();
            if (HologramLibraryFacade.enabled() && (backend == null
                || !backend.consumeReviewUpload(HologramLibraryFacade.actorId(ticket.player), chunk.data.length)))
                return;
            ticket.chunks.removeFirst();
            ticket.input.append(chunk.index, chunk.data);
            ticket.deadline.progress(System.nanoTime());
            NBTTagCompound ack = send(ticket, "authorReviewAck", false);
            ack.setInteger("nextIndex", ticket.input.index());
            ack.setInteger("index", ticket.input.index() - 1);
            HologramNetwork.sendLibraryReply(ticket.player, ack);
            if (chunk.last) {
                byte[] uploaded = ticket.input.finish();
                ticket.input = null;
                ticket.deadline.computing(System.nanoTime());
                enqueue(ticket, () -> prepare(ticket, uploaded));
            }
        }
    }

    private static void prepare(Ticket ticket, byte[] bytes) throws Exception {
        if (!HologramBlueprintArtifact.hash(bytes)
            .equals(ticket.proof.getString("sourceHash")))
            throw new IOException("Author review source digest mismatch");
        NBTTagCompound payload = HologramAuthorReviewProtocol.decode(bytes, () -> !ticket.retired);
        boolean selectedCell = ("cellNbt".equals(ticket.proof.getString("intent"))
            || "candidates".equals(ticket.proof.getString("intent"))) && payload.hasKey("selectedCell", 7);
        AuthorDocument source = AuthorDocumentIO
            .read(payload.getByteArray(selectedCell ? "selectedCell" : "draft"), HologramAuthorReviewProtocol.limits());
        if (selectedCell) {
            if (source.cellCount() != 1 || !payload.getString("documentId")
                .equals(ticket.proof.getString("documentId"))
                || !payload.hasKey("planRevision", 4)
                || payload.getLong("planRevision") != ticket.proof.getLong("planRevision"))
                throw new IOException("Author selected-cell query identity mismatch");
        } else if (!source.documentId()
            .equals(ticket.proof.getString("documentId")) || source.revision() != ticket.proof.getLong("planRevision"))
            throw new IOException("Author review recovery source identity mismatch");
        NBTTagCompound policy = payload.getCompoundTag("policy");
        synchronized (ticket) {
            if (ticket.retired) return;
            ticket.source = source;
            ticket.policy = policy;
            ticket.selectedCell = selectedCell;
        }
        boolean queued = HologramLibraryFacade
            .scheduleLibraryCallback(ticket.player, ticket.proof, () -> review(ticket));
        if (!queued) ticket.error = "Author review callback queue is busy";
    }

    private static void review(Ticket ticket) {
        if (!ticket.current()) {
            retire(ticket);
            return;
        }
        try {
            String intent = ticket.proof.getString("intent");
            if ("structure".equals(intent) || "structureInspect".equals(intent)
                || "configureCells".equals(intent)
                || "attachment".equals(intent)) {
                ticket.nativeTask = "attachment".equals(intent)
                    ? HologramAuthorAttachmentReview.start(ticket.source, ticket.policy)
                    : "configureCells".equals(intent)
                        ? HologramAuthorServerReview.startConfiguration(ticket.source, ticket.policy)
                        : HologramAuthorServerReview
                            .startStructure(ticket.source, intent, ticket.policy, ticket.player.getHeldItem());
                ticket.source = null;
                ticket.policy = null;
                return;
            }
            HologramAuthorServerReview.Result reviewed = ticket.selectedCell ? null
                : HologramAuthorServerReview.review(ticket.source, ticket.proof.getString("intent"), ticket.policy);
            NBTTagCompound selectedReport = null;
            if (ticket.selectedCell) {
                com.google.gson.JsonObject selected = ticket.source.cells()
                    .iterator()
                    .next()
                    .sourceCopy();
                selectedReport = "candidates".equals(intent) ? HologramAuthorServerReview.reviewCandidates(selected)
                    : HologramAuthorServerReview.reviewCell(
                        selected,
                        ticket.source.headerCopy(),
                        ticket.policy.getInteger("index"),
                        ticket.policy.getInteger("offset"));
            }
            ticket.source = null;
            ticket.policy = null;
            if (!ticket.current()) {
                retire(ticket);
                return;
            }
            encodeResult(ticket, reviewed, selectedReport);
        } catch (IOException | RuntimeException | LinkageError failed) {
            ticket.error = reason(failed.getMessage());
        }
    }

    private static void encodeResult(Ticket ticket, HologramAuthorServerReview.Result reviewed,
        NBTTagCompound selectedReport) {
        enqueue(ticket, () -> {
            NBTTagCompound result = resultPayload(reviewed, selectedReport);
            byte[] encoded = HologramAuthorReviewProtocol.encode(result, () -> !ticket.retired);
            if (!ticket.retired) {
                ticket.deadline.result(encoded.length, System.nanoTime());
                ticket.outputHash = HologramBlueprintArtifact.hash(encoded);
                ticket.output = encoded;
            }
        });
    }

    /** Compile consumers need only exact compiled bytes; do not duplicate the same source in the wire payload. */
    static NBTTagCompound resultPayload(HologramAuthorServerReview.Result reviewed, NBTTagCompound selectedReport) {
        NBTTagCompound result = new NBTTagCompound();
        if (selectedReport != null) result.setTag("report", selectedReport);
        if (reviewed != null) {
            if (reviewed.report != null) result.setTag("report", reviewed.report);
            if (reviewed.source != null && reviewed.compiled == null) result.setByteArray(
                "source",
                reviewed.source.toString()
                    .getBytes(StandardCharsets.UTF_8));
            if (reviewed.compiled != null) result.setByteArray("compiled", reviewed.compiled);
        }
        return result;
    }

    private interface Work {

        void run() throws Exception;
    }

    private static void enqueue(Ticket ticket, Work work) {
        ticket.references.incrementAndGet();
        try {
            IO.execute(() -> {
                try {
                    if (!ticket.retired) work.run();
                } catch (Exception | LinkageError failed) {
                    if (!ticket.retired) ticket.error = reason(failed.getMessage());
                } finally {
                    ticket.release();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            ticket.release();
            ticket.error = "Author review IO queue is busy";
        }
    }

    private static NBTTagCompound send(Ticket ticket, String op, boolean send) {
        NBTTagCompound packet = HologramAuthorReviewProtocol.packet(ticket.proof, op);
        packet.setBoolean("library", true);
        byte[] output = ticket.output;
        if (output != null) packet.setInteger("reviewResultBytes", output.length);
        if (send) HologramNetwork.sendLibraryReply(ticket.player, packet);
        return packet;
    }

    private static void send(Ticket ticket, String op) {
        send(ticket, op, true);
    }

    private static String reason(String reason) {
        return reason == null ? "Author review failed" : reason.substring(0, Math.min(256, reason.length()));
    }

    private static void error(EntityPlayerMP player, NBTTagCompound proof, String reason) {
        NBTTagCompound reply = HologramAuthorReviewProtocol.packet(proof, "authorReviewError");
        reply.setString("message", reason(reason));
        HologramNetwork.sendLibraryReply(player, reply);
    }

    private static void fail(Ticket ticket, String reason) {
        error(ticket.player, ticket.proof, reason);
        retire(ticket);
    }

    private static void retire(Ticket ticket) {
        ACTIVE.remove(HologramLibraryFacade.actorId(ticket.player), ticket);
        ticket.close();
    }

    static void tick() {
        long now = System.nanoTime();
        for (Ticket ticket : ACTIVE.values()) {
            if (!ticket.current()) retire(ticket);
            else if (ticket.deadline.expired(now))
                fail(ticket, ticket.deadline.timeout(now) + "; recovery source retained on client");
            else try {
                HologramServerLibrary backend = HologramLibraryFacade.backend();
                HologramLibraryConfig.View config = !HologramLibraryFacade.enabled() || backend == null ? null
                    : backend.config();
                if (HologramLibraryFacade.enabled() && config == null || !ticket.deadline.ratesMatch(
                    config == null ? 1024 * 1024 : config.uploadKiB * 1024,
                    config == null ? 1024 * 1024 : config.downloadKiB * 1024))
                    throw new IOException(
                        "Author review bandwidth configuration changed; retry with the new size/rate budget");
                drain(ticket);
                if (ticket.nativeTask != null && ticket.nativeTask.step()) {
                    HologramAuthorServerReview.Result result = ticket.nativeTask.result();
                    ticket.nativeTask.close();
                    ticket.nativeTask = null;
                    encodeResult(ticket, result, null);
                }
            } catch (IOException | RuntimeException | LinkageError failed) {
                fail(ticket, failed.getMessage());
            }
        }
    }

    static void clear() {
        for (Ticket ticket : ACTIVE.values()) retire(ticket);
    }
}
