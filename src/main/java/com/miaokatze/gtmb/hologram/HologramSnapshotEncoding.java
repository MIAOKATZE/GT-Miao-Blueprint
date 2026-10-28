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

    static Job submit(NBTTagCompound owned, HologramSnapshotSegments.Peer peer) {
        return submit(owned, peer, null);
    }

    static Job submit(NBTTagCompound owned, HologramSnapshotSegments.Peer peer, HologramTransportPageLayout layout) {
        return submit(owned, executor, written -> {}, peer == null ? null : peer.baseline(), layout);
    }

    static Job submit(NBTTagCompound owned, Executor executor) {
        return submit(owned, executor, written -> {});
    }

    /** A deterministic checkpoint observer keeps the regression on the real codec and executor. */
    static Job submit(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint) {
        return submit(owned, executor, checkpoint, null, null);
    }

    private static Job submit(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint,
        java.util.Set<String> confirmedSegments, HologramTransportPageLayout layout) {
        Job job = new Job(owned, executor, checkpoint, confirmedSegments, layout);
        try {
            executor.execute(job);
            return job;
        } catch (RejectedExecutionException full) {
            return null;
        }
    }

    static void clear() {
        HologramSnapshotSegmentEncoding.clear();
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
        private final java.util.Set<String> confirmedSegments;
        java.util.List<String> segmentHashes;
        private HologramTransportPageLayout layout;
        private HologramSnapshotBytes output;
        private HologramProcessGovernor.Subscription<?> waiting;
        private boolean running, queued = true;
        private AutoCloseable retiredSource;

        Job(NBTTagCompound owned, Executor executor, java.util.function.LongConsumer checkpoint,
            java.util.Set<String> confirmedSegments, HologramTransportPageLayout layout) {
            this.layout = layout;
            this.owned = owned;
            this.executor = executor;
            this.checkpoint = checkpoint;
            this.confirmedSegments = confirmedSegments;
        }

        synchronized void cancel() {
            cancelled = true;
            if (executor instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) executor).remove(this);
            owned = null;
            bytes = null;
            hash = null;
            if (waiting != null) waiting.close();
            waiting = null;
            if (output != null) output.close();
            output = null;
            if (!running && layout != null) layout.close();
            if (!running) layout = null;
        }

        synchronized void retireSource(AutoCloseable source) {
            if (source == null) return;
            if (running) retiredSource = source;
            else closeSource(source);
        }

        private void closeSource(AutoCloseable source) {
            try {
                source.close();
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        }

        synchronized HologramSnapshotBytes takeOutput() {
            HologramSnapshotBytes value = output;
            output = null;
            bytes = null;
            return value;
        }

        synchronized void resume() {
            if (cancelled || done
                || running
                || queued
                || waiting == null
                || !waiting.future()
                    .isDone())
                return;
            try {
                HologramProcessGovernor.Subscription<?> completed = waiting;
                waiting = null;
                queued = true;
                try {
                    executor.execute(this);
                    completed.close();
                } catch (RejectedExecutionException full) {
                    queued = false;
                    waiting = completed;
                }
            } catch (RejectedExecutionException full) {
                queued = false;
            }
        }

        public void run() {
            NBTTagCompound frozen;
            synchronized (this) {
                queued = false;
                running = true;
                frozen = owned;
            }
            long started = HologramRuntimeMetrics.start();
            try {
                if (!cancelled && frozen != null) {
                    long[] advancedAt = { System.nanoTime() };
                    java.util.function.BooleanSupplier current = () -> !cancelled
                        && System.nanoTime() - advancedAt[0] < TimeUnit.SECONDS.toNanos(120);
                    java.util.function.LongConsumer progress = written -> {
                        rawBytes = written;
                        advancedAt[0] = System.nanoTime();
                        checkpoint.accept(written);
                    };
                    HologramSnapshotBytes encoded;
                    boolean compactRows = HologramSnapshotRows.compactSnapshot(frozen);
                    if (compactRows && (confirmedSegments == null || layout == null))
                        throw new java.io.IOException("compact snapshot requires negotiated segmented transport");
                    if (confirmedSegments != null && layout != null
                        && frozen.hasKey("cells", 9)
                        && (!frozen.getBoolean("libraryInitialSnapshot") || compactRows)) {
                        try {
                            HologramSnapshotSegmentEncoding.Result segmented = HologramSnapshotSegmentEncoding
                                .encode(frozen, layout, confirmedSegments, current, progress);
                            encoded = segmented.bytes;
                            segmentHashes = segmented.hashes;
                        } catch (HologramSnapshotSegmentEncoding.LegacyFallback overhead) {
                            if (compactRows) throw new java.io.IOException("compact snapshot expanded byte budget");
                            try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor
                                .acquireHeavyCpu("snapshot.encode")) {
                                encoded = HologramSnapshotCodec.compressOwned(frozen, current, progress);
                            }
                        }
                    } else try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor
                        .acquireHeavyCpu("snapshot.encode")) {
                            encoded = HologramSnapshotCodec.compressOwned(frozen, current, progress);
                        }
                    byte[] digest = null;
                    if (segmentHashes == null && !frozen.getBoolean("detailOnly") && !frozen.getBoolean("modelOnly"))
                        try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor
                            .acquireHeavyCpu("snapshot.digest")) {
                                digest = HologramLibraryProtocol.digest()
                                    .digest(encoded.bytes);
                            }
                    synchronized (this) {
                        if (!cancelled) {
                            output = encoded;
                            bytes = encoded.bytes;
                            hash = digest;
                        } else {
                            encoded.close();
                        }
                    }
                }
            } catch (HologramSnapshotSegmentEncoding.PendingPage pending) {
                synchronized (this) {
                    waiting = pending.subscription;
                }
            } catch (Exception problem) {
                failure = problem;
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.SNAPSHOT_COMPRESS, started);
                synchronized (this) {
                    running = false;
                    done = waiting == null || cancelled;
                    if (done) {
                        owned = null;
                        if (retiredSource != null) closeSource(retiredSource);
                        retiredSource = null;
                        if (layout != null) layout.close();
                        layout = null;
                    }
                }
            }
        }
    }
}
