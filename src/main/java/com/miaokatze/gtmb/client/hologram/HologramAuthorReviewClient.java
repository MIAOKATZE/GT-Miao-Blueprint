package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.hologram.HologramAuthorReviewProtocol;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramLibraryDownload;
import com.miaokatze.gtmb.hologram.HologramLibraryProtocol;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramProcessGovernor;

/** Explicit legacy-extension review only. Local open/edit/draft-save never send author source. */
final class HologramAuthorReviewClient {

    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
        1,
        1,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(2),
        runnable -> {
            Thread thread = new Thread(runnable, "GTMB-author-review-client");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
    private static Task active;
    private static long sequence;

    private HologramAuthorReviewClient() {}

    private static final class Task {

        final HologramLocalAuthorRuntime owner;
        final AuthorDocument frozen;
        final Object connection, world, tool;
        final int slot;
        final BooleanSupplier condition;
        final Consumer<NBTTagCompound> success;
        final Consumer<Throwable> failure;
        final HologramProcessGovernor.Lease lease;
        final java.util.concurrent.atomic.AtomicInteger references = new java.util.concurrent.atomic.AtomicInteger(1);
        byte[] prepared;
        String preparedHash;
        NBTTagCompound preparedResult;
        final NBTTagCompound proof;
        byte[] outgoing;
        HologramLibraryProtocol.UploadWindow upload;
        HologramLibraryDownload.Assembly incoming;
        String resultHash;
        int sent, acknowledged, resultTotal;
        boolean begun, waiting, decoding;
        volatile boolean retired;
        final long started = System.nanoTime();
        long activity = started, pollAt;

        Task(HologramLocalAuthorRuntime owner, String operation, BooleanSupplier condition,
            Consumer<NBTTagCompound> success, Consumer<Throwable> failure, HologramProcessGovernor.Lease lease) {
            Minecraft mc = Minecraft.getMinecraft();
            this.owner = owner;
            frozen = owner.document();
            connection = mc.getNetHandler();
            world = mc.theWorld;
            tool = mc.thePlayer.getHeldItem();
            slot = mc.thePlayer.inventory.currentItem;
            this.condition = condition;
            this.success = success;
            this.failure = failure;
            this.lease = lease;
            proof = HologramLibraryProtocol.request("authorReviewBegin");
            proof.setString(
                "transfer",
                UUID.randomUUID()
                    .toString());
            proof.setString("documentId", frozen.documentId());
            proof.setLong("planRevision", frozen.revision());
            proof.setLong("clientTaskToken", ++sequence);
            proof.setString("intent", operation);
        }

        void release() {
            if (references.decrementAndGet() == 0) lease.close();
        }

        boolean current() {
            Minecraft mc = Minecraft.getMinecraft();
            return !retired && active == this
                && HologramLocalAuthorRuntime.active() == owner
                && owner.document() == frozen
                && connection == mc.getNetHandler()
                && world == mc.theWorld
                && mc.thePlayer != null
                && tool == mc.thePlayer.getHeldItem()
                && slot == mc.thePlayer.inventory.currentItem
                && condition.getAsBoolean();
        }
    }

    /** All terminal callbacks, including synchronous admission failure, run once on the client owner. */
    static boolean request(HologramLocalAuthorRuntime owner, String operation, NBTTagCompound policy,
        BooleanSupplier current, Consumer<NBTTagCompound> success, Consumer<Throwable> failure) {
        Minecraft mc = Minecraft.getMinecraft();
        if (active != null || owner == null
            || mc.theWorld == null
            || mc.thePlayer == null
            || mc.getNetHandler() == null) {
            failure.accept(new IOException(active != null ? "已有服务端扩展审核；可取消或等待，草稿保留" : "当前离线，服务端专用扩展审核不可用；恢复草稿完整保留"));
            return false;
        }
        HologramProcessGovernor.Lease lease = HologramProcessGovernor
            .tryRetain("author.review.client", 64L * 1024 * 1024);
        if (lease == null) {
            failure.accept(new IOException("扩展审核内存额度繁忙，草稿保留"));
            return false;
        }
        Task task = new Task(owner, operation, current, success, failure, lease);
        active = task;
        NBTTagCompound frozenPolicy = (NBTTagCompound) policy.copy();
        work(task, () -> {
            NBTTagCompound payload = new NBTTagCompound();
            if ("cellNbt".equals(operation) || "candidates".equals(operation)) {
                payload.setByteArray(
                    "selectedCell",
                    HologramAuthorReviewProtocol.selectedCellSource(task.frozen, frozenPolicy.getLong("cellId")));
            } else payload
                .setByteArray("draft", AuthorDocumentIO.writeDraft(task.frozen, HologramAuthorReviewProtocol.limits()));
            payload.setString("documentId", task.frozen.documentId());
            payload.setLong("planRevision", task.frozen.revision());
            payload.setTag("policy", frozenPolicy);
            byte[] encoded = HologramAuthorReviewProtocol.encode(payload, () -> !task.retired);
            String hash = HologramBlueprintArtifact.hash(encoded);
            synchronized (task) {
                if (task.retired) return;
                task.prepared = encoded;
                task.preparedHash = hash;
            }
            main(() -> beginPrepared(task));
        });
        return true;
    }

    static boolean busy(HologramLocalAuthorRuntime owner) {
        return active != null && active.owner == owner;
    }

    static void cancel(HologramLocalAuthorRuntime owner) {
        Task task = active;
        if (task != null && task.owner == owner) finish(task, null, new IOException("扩展审核已取消；原始恢复草稿保留"));
    }

    static void tick() {
        Task task = active;
        if (task == null) return;
        long now = System.nanoTime();
        if (!task.current()) {
            finish(task, null, new IOException("文档、工具或连接已变化，扩展审核已取消；草稿保留"));
            return;
        }
        if (now - task.activity > HologramLibraryProtocol.TIMEOUT_NANOS
            || now - task.started > HologramAuthorReviewProtocol.LIFETIME_NANOS) {
            finish(task, null, new IOException("服务端扩展审核超时或服务器不支持；恢复草稿保留"));
            return;
        }
        if (task.waiting && !task.decoding && now >= task.pollAt) {
            task.waiting = false;
            NBTTagCompound poll = packet(task, "authorReviewPoll");
            poll.setInteger("index", task.incoming == null ? 0 : task.incoming.index());
            HologramNetwork.sendLibrary(poll);
        }
    }

    static boolean receive(NBTTagCompound reply) {
        String op = reply.getString("op");
        if (!HologramAuthorReviewProtocol.operation(op)) return false;
        Task task = active;
        if (task == null || !HologramAuthorReviewProtocol.matches(task.proof, reply)) return true;
        if (!task.current()) {
            finish(task, null, new IOException("过期扩展审核结果已丢弃；草稿保留"));
            return true;
        }
        try {
            task.activity = System.nanoTime();
            if ("authorReviewError".equals(op)) throw new IOException(reply.getString("message"));
            if ("authorReviewReady".equals(op)) {
                if (task.upload.ready(reply)) sendChunks(task);
            } else if ("authorReviewAck".equals(op)) {
                if (!task.upload.acknowledge(reply)) return true;
                task.acknowledged = task.upload.acknowledged();
                if (task.outgoing != null
                    && (long) task.acknowledged * HologramLibraryProtocol.CHUNK_BYTES >= task.outgoing.length) {
                    task.outgoing = null;
                    task.waiting = true;
                    task.pollAt = 0;
                } else sendChunks(task);
            } else if ("authorReviewWaiting".equals(op)) {
                task.waiting = true;
                task.pollAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            } else if ("authorReviewResult".equals(op)) {
                int total = reply.getInteger("total");
                if (total <= 0 || total > HologramAuthorReviewProtocol.MAX_BYTES)
                    throw new IOException("Invalid review response size");
                if (task.incoming == null) {
                    task.resultTotal = total;
                    task.resultHash = reply.getString("resultHash");
                    if (!task.resultHash.matches("[a-f0-9]{64}"))
                        throw new IOException("Invalid review response digest");
                    task.incoming = new HologramLibraryDownload.Assembly(total);
                }
                if (total != task.resultTotal || !task.resultHash.equals(reply.getString("resultHash")))
                    throw new IOException("Review response identity changed");
                task.incoming.append(reply.getInteger("index"), reply.getByteArray("data"));
                if (reply.getBoolean("last")) {
                    byte[] response = task.incoming.finish();
                    task.incoming = null;
                    task.decoding = true;
                    work(task, () -> {
                        if (!HologramBlueprintArtifact.hash(response)
                            .equals(task.resultHash)) throw new IOException("Review response digest mismatch");
                        NBTTagCompound result = HologramAuthorReviewProtocol.decode(response, () -> !task.retired);
                        synchronized (task) {
                            if (task.retired) return;
                            task.preparedResult = result;
                        }
                        main(() -> completePrepared(task));
                    });
                } else if (reply.getBoolean("windowEnd")) {
                    task.waiting = true;
                    task.pollAt = 0;
                }
            }
        } catch (IOException | RuntimeException failed) {
            finish(task, null, failed);
        }
        return true;
    }

    private static void beginPrepared(Task task) {
        if (task.retired) return;
        if (!task.current()) {
            finish(task, null, new IOException("文档或连接已变化，扩展审核已取消；草稿保留"));
            return;
        }
        task.outgoing = task.prepared;
        task.prepared = null;
        task.proof.setString("sourceHash", task.preparedHash);
        task.proof.setInteger("total", task.outgoing.length);
        task.upload = new HologramLibraryProtocol.UploadWindow(task.proof);
        task.begun = true;
        task.activity = System.nanoTime();
        HologramNetwork.sendLibrary(task.proof);
    }

    private static void completePrepared(Task task) {
        if (task.retired) return;
        if (!task.current()) finish(task, null, new IOException("审核结果过期，草稿保留"));
        else finish(task, task.preparedResult, null);
    }

    private static void sendChunks(Task task) {
        while (task.outgoing != null && task.upload.canSend()) {
            int index = task.upload.nextSent();
            int offset = index * HologramLibraryProtocol.CHUNK_BYTES;
            int end = Math.min(task.outgoing.length, offset + HologramLibraryProtocol.CHUNK_BYTES);
            NBTTagCompound chunk = packet(task, "authorReviewChunk");
            chunk.setInteger("index", index);
            chunk.setByteArray("data", java.util.Arrays.copyOfRange(task.outgoing, offset, end));
            chunk.setBoolean("last", end == task.outgoing.length);
            task.upload.sent(index);
            task.sent = index + 1;
            HologramNetwork.sendLibrary(chunk);
        }
    }

    private static NBTTagCompound packet(Task task, String op) {
        return HologramAuthorReviewProtocol.packet(task.proof, op);
    }

    private static void finish(Task task, NBTTagCompound result, Throwable failure) {
        synchronized (task) {
            if (task.retired) return;
            task.retired = true;
            task.prepared = null;
            task.preparedResult = null;
        }
        if (active == task) active = null;
        if (failure != null && task.begun
            && task.connection == Minecraft.getMinecraft()
                .getNetHandler())
            HologramNetwork.sendLibrary(packet(task, "authorReviewCancel"));
        task.outgoing = null;
        if (task.incoming != null) task.incoming.cancel();
        task.incoming = null;
        task.release();
        // Retire first: committing processed source can synchronously cancel an owner's pending review.
        if (failure == null) task.success.accept(result);
        else task.failure.accept(failure);
    }

    private interface Work {

        void run() throws Exception;
    }

    private static void work(Task task, Work work) {
        task.references.incrementAndGet();
        try {
            IO.execute(() -> {
                try {
                    if (!task.retired) work.run();
                } catch (Exception | LinkageError failed) {
                    main(() -> finish(task, null, failed));
                } finally {
                    task.release();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            task.release();
            finish(task, null, full);
        }
    }

    private static void main(Runnable task) {
        Minecraft.getMinecraft()
            .func_152344_a(task);
    }
}
