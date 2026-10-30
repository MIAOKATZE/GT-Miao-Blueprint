package com.miaokatze.gtmb.hologram;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.NBTTagCompound;

/** Owns frozen NBT only. No player, world, registry, callback or packet object enters the worker. */
final class HologramSnapshotEncoding {

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4),
        runnable -> {
            Thread thread = new Thread(runnable, "GTMB snapshot encoder");
            thread.setDaemon(true);
            return thread;
        });

    private static Executor executor = WORKER;

    private HologramSnapshotEncoding() {}

    static Job submit(NBTTagCompound owned) {
        return submit(owned, executor);
    }

    static Job submit(NBTTagCompound owned, long transportSequence) {
        return submit(owned, transportSequence, false);
    }

    static Job submit(NBTTagCompound owned, long transportSequence, boolean palette) {
        return submit(owned, executor, written -> {}, transportSequence, palette);
    }

    static Job submit(NBTTagCompound owned, Executor executor) {
        return submit(owned, executor, written -> {});
    }

    /** A deterministic checkpoint observer keeps the regression on the real codec and executor. */
    static Job submit(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint) {
        return submit(owned, executor, checkpoint, -1);
    }

    private static Job submit(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint,
        long transportSequence) {
        return submit(owned, executor, checkpoint, transportSequence, false);
    }

    private static Job submit(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint,
        long transportSequence, boolean palette) {
        Job job = new Job(owned, executor, checkpoint, transportSequence, palette);
        try {
            job.mark(HologramRuntimeMetrics.LoadStage.SERVER_ENCODER_QUEUED, 0);
            executor.execute(job);
            return job;
        } catch (RejectedExecutionException full) {
            job.mark(HologramRuntimeMetrics.LoadStage.SERVER_ENCODER_REJECTED, 0);
            return null;
        }
    }

    static void clear() {
        for (Runnable queued : WORKER.getQueue()) ((Job) queued).cancel();
        WORKER.getQueue()
            .clear();
    }

    static final class Job implements Runnable {

        private NBTTagCompound owned;
        volatile boolean cancelled, done;
        byte[] bytes, hash;
        Exception failure;
        volatile long rawBytes;
        private final Executor executor;
        private final java.util.function.LongConsumer checkpoint;
        private final String traceSession;
        private final long traceRevision, traceRequest, traceTransport;
        private final boolean palette;

        Job(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint, long transportSequence,
            boolean palette) {
            this.owned = owned;
            this.executor = executor;
            this.checkpoint = checkpoint;
            this.palette = palette;
            boolean tracing = HologramRuntimeMetrics.isEnabled() && owned != null;
            traceSession = tracing ? owned.getString("session") : null;
            traceRevision = tracing ? owned.getLong("planRevision") : -1;
            traceRequest = tracing ? owned.getLong("uiSequence") : -1;
            traceTransport = transportSequence;
        }

        private void mark(HologramRuntimeMetrics.LoadStage phase, long count) {
            if (traceSession != null) HologramRuntimeMetrics
                .loadStage(phase, traceSession, traceRevision, traceRequest, traceTransport, count);
        }

        synchronized void cancel() {
            cancelled = true;
            if (executor instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) executor).remove(this);
            owned = null;
            bytes = null;
            hash = null;
        }

        public void run() {
            mark(HologramRuntimeMetrics.LoadStage.SERVER_ENCODER_BEGIN, 0);
            NBTTagCompound frozen;
            synchronized (this) {
                frozen = owned;
                owned = null;
            }
            long started = HologramRuntimeMetrics.start();
            try {
                if (!cancelled && frozen != null) {
                    long[] advancedAt = { System.nanoTime() };
                    byte[] encoded = HologramSnapshotCodec.compress(
                        frozen,
                        () -> !cancelled && System.nanoTime() - advancedAt[0] < TimeUnit.SECONDS.toNanos(120),
                        written -> {
                            rawBytes = written;
                            advancedAt[0] = System.nanoTime();
                            checkpoint.accept(written);
                        },
                        palette);
                    byte[] digest = HologramLibraryProtocol.digest()
                        .digest(encoded);
                    synchronized (this) {
                        if (!cancelled) {
                            bytes = encoded;
                            hash = digest;
                        }
                    }
                }
            } catch (Exception problem) {
                failure = problem;
            } finally {
                mark(
                    cancelled ? HologramRuntimeMetrics.LoadStage.SERVER_ENCODER_CANCELLED
                        : failure == null ? HologramRuntimeMetrics.LoadStage.SERVER_ENCODER_END
                            : HologramRuntimeMetrics.LoadStage.SERVER_ENCODER_FAILED,
                    bytes == null ? 0 : bytes.length);
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.SNAPSHOT_COMPRESS, started);
                done = true;
            }
        }
    }
}
