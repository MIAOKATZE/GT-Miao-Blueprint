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

    static Job submit(NBTTagCompound owned, Executor executor) {
        return submit(owned, executor, written -> {});
    }

    /** A deterministic checkpoint observer keeps the regression on the real codec and executor. */
    static Job submit(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint) {
        Job job = new Job(owned, executor, checkpoint);
        try {
            executor.execute(job);
            return job;
        } catch (RejectedExecutionException full) {
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

        Job(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint) {
            this.owned = owned;
            this.executor = executor;
            this.checkpoint = checkpoint;
        }

        synchronized void cancel() {
            cancelled = true;
            if (executor instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) executor).remove(this);
            owned = null;
            bytes = null;
            hash = null;
        }

        public void run() {
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
                        });
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
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.SNAPSHOT_COMPRESS, started);
                done = true;
            }
        }
    }
}
