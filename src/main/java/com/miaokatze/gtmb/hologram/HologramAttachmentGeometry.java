package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Detached geometry only: no world, player, native tile factory or GL calls on the worker. */
public final class HologramAttachmentGeometry {

    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();
    private static final Semaphore JOBS = new Semaphore(5, true);
    // At most five logical jobs, with two queued stages each. CPU contention waits in the governor.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(10),
        action -> {
            Thread thread = new Thread(action, "GTMB attachment geometry");
            thread.setDaemon(true);
            return thread;
        });

    private HologramAttachmentGeometry() {}

    public static final class Job implements AutoCloseable {

        public volatile boolean done, cancelled;
        public volatile String phase = "queued";
        public volatile long completed, total;
        public List<HologramTextBox.Cell> cells = Collections.emptyList();
        public Map<String, Integer> payloadSizes = new HashMap<>();
        public String definition;
        public byte[] picturePixels;
        public Exception failure;
        private Input input;
        private HologramProcessGovernor.Subscription<?> subscription;
        private HologramAttachmentMemory.Owner memory;
        private HologramAttachmentMemory.Retention definitionMemory;
        private HologramPictureBox rasterSource;
        private byte[] raster;
        private int stage;

        private Job(Input input) {
            this.input = input;
        }

        // Kept public for detached progress/cancellation fixtures and callers constructing an inert job.
        public Job() {}

        public synchronized void cancel() {
            cancelled = true;
            if (subscription != null) subscription.cancel();
            if (!done) publish(null, new IOException("附加对象生成已取消"));
            releaseResult();
        }

        @Override
        public void close() {
            cancel();
        }

        synchronized String exportDefinition() throws IOException {
            if (!done || failure != null || definition == null) throw new IOException("附加对象定义尚未就绪");
            HologramAttachmentMemory.export(definition, definitionMemory);
            return definition;
        }

        private synchronized Input beginGeometry() {
            if (done || cancelled || stage != 0) return null;
            stage = 1;
            subscription = null;
            phase = "geometry";
            return input.retain();
        }

        private synchronized void bind(int expectedStage, HologramProcessGovernor.Subscription<?> pending) {
            if (pending == null) return;
            if (done || cancelled || stage != expectedStage) pending.cancel();
            else subscription = pending;
        }

        private synchronized void publish(Result result, Throwable error) {
            if (done) {
                if (result != null) result.close();
                return;
            }
            if (error != null) {
                failure = error instanceof Exception ? (Exception) error : new IOException("附加对象生成失败", error);
            } else {
                cells = result.cells;
                payloadSizes = result.payloadSizes;
                definition = result.definition;
                picturePixels = result.picturePixels;
                memory = result.memory;
                definitionMemory = result.definitionMemory;
                result.disown();
            }
            subscription = null;
            if (input != null) {
                input.close();
                input = null;
                JOBS.release();
            }
            done = true;
        }

        private void releaseResult() {
            cells = Collections.emptyList();
            payloadSizes.clear();
            definition = null;
            picturePixels = null;
            if (memory != null) memory.close();
            memory = null;
            definitionMemory = null;
        }
    }

    public static Job submit(HologramTextBox source) throws IOException {
        return submit(source, Collections.emptyList());
    }

    static Job submit(HologramTextBox source, List<String> retained) throws IOException {
        if (!JOBS.tryAcquire()) throw new IOException("附加对象生成队列已满，请稍后重试");
        HologramProcessGovernor.Lease lease = null;
        Input frozenInput = null;
        Job job = null;
        try {
            source.validate();
            long bytes = 4096L + 2L * source.text.length()
                + source.bitmap.length
                + 4L * source.turns.length
                + (source.picture == null ? 0 : 4L * source.picture.source.length)
                + 64L * retained.size();
            for (String payload : retained) if (payload != null) bytes += 2L * payload.length();
            lease = HologramAttachmentMemory.reserve("attachment frozen input", bytes);
            HologramTextBox frozen = HologramTextBox.read(
                (net.minecraft.nbt.NBTTagCompound) source.write()
                    .copy());
            Input input = new Input(frozen, new ArrayList<>(retained), lease.transfer());
            frozenInput = input;
            lease = null;
            job = new Job(input);
            Job submitted = job;
            if (frozen.picture == null) startGeometry(job, null);
            else {
                job.phase = "raster";
                job.total = frozen.picture.height;
                HologramProcessGovernor.Subscription<HologramAttachmentRaster.Shared> raster = HologramAttachmentRaster
                    .request(frozen.picture, WORKER, (view, error) -> {
                        if (error != null) submitted.publish(null, error);
                        else startGeometry(submitted, view);
                    });
                job.bind(0, raster);
            }
            return job;
        } catch (IOException | RuntimeException | Error failure) {
            if (job != null) {
                job.publish(null, failure);
                return job;
            }
            if (lease != null) lease.close();
            if (frozenInput != null) frozenInput.close();
            JOBS.release();
            throw failure;
        }
    }

    private static void startGeometry(Job job, HologramAttachmentRaster.Handle raster) {
        Input input = job.beginGeometry();
        if (input == null) {
            if (raster != null) raster.close();
            return;
        }
        Geometry producer = null;
        try {
            producer = new Geometry(job, input, raster);
            HologramProcessGovernor.Subscription<Result> pending = HologramProcessGovernor
                .singleFlight(new Object(), 1, producer, WORKER, job::publish);
            job.bind(1, pending);
        } catch (RuntimeException | Error failure) {
            if (producer != null) producer.close();
            else {
                input.close();
                if (raster != null) raster.close();
            }
            job.publish(null, failure);
        }
    }

    private static final class Input implements AutoCloseable {

        private HologramTextBox source;
        private List<String> retained;
        private HologramProcessGovernor.Lease lease;
        private int references = 1;

        Input(HologramTextBox source, List<String> retained, HologramProcessGovernor.Lease lease) {
            this.source = source;
            this.retained = retained;
            this.lease = lease;
        }

        synchronized Input retain() {
            if (references == 0) throw new IllegalStateException("Attachment input released");
            references++;
            return this;
        }

        @Override
        public synchronized void close() {
            if (references == 0 || --references != 0) return;
            source = null;
            retained = null;
            lease.close();
            lease = null;
        }
    }

    private static final class Geometry implements Callable<Result>, AutoCloseable {

        private Job job;
        private Input input;
        private HologramAttachmentRaster.Handle raster;

        Geometry(Job job, Input input, HologramAttachmentRaster.Handle raster) {
            this.job = job;
            this.input = input;
            this.raster = raster;
        }

        @Override
        public Result call() throws IOException {
            HologramAttachmentMemory.Owner memory = new HologramAttachmentMemory.Owner();
            boolean successful = false;
            CURRENT.set(job);
            try (HologramAttachmentMemory.Scope scope = HologramAttachmentMemory.enter(memory)) {
                HologramTextBox frozen = input.source;
                job.rasterSource = frozen.picture;
                job.raster = raster == null ? null : raster.pixels();
                checkpoint("generate", 0, 0);
                List<HologramTextBox.Cell> cells = frozen.generate();
                HologramAttachmentMemory.retainResult(512);
                Result result = new Result(memory, cells);
                if (job.raster != null) {
                    HologramAttachmentMemory.retainResult(32L + job.raster.length);
                    result.picturePixels = job.raster.clone();
                }
                long sourceBytes = 4096L + frozen.bitmap.length
                    + 4L * frozen.turns.length
                    + (frozen.picture == null ? 0 : 4L * frozen.picture.source.length);
                try (HologramProcessGovernor.Lease working = HologramAttachmentMemory
                    .reserve("attachment definition encoding", 65536L + 8L * sourceBytes)) {
                    result.definition = HologramBlueprintNbt.encodeCaptured(frozen.write());
                    HologramProcessGovernor.Lease definition = HologramAttachmentMemory
                        .reserve("attachment definition result", 512L + 2L * result.definition.length());
                    HologramAttachmentMemory.retain(result.definition, result.definitionMemory, definition);
                }
                validate(result);
                successful = true;
                return result;
            } finally {
                job.rasterSource = null;
                job.raster = null;
                CURRENT.remove();
                if (!successful) memory.close();
            }
        }

        private void validate(Result result) throws IOException {
            int count = input.retained.size() + 1 + result.cells.size();
            try (HologramProcessGovernor.Lease references = HologramAttachmentMemory
                .reserve("attachment payload references", 256L + 24L * count)) {
                List<String> payloads = new ArrayList<>(count);
                payloads.addAll(input.retained);
                payloads.add(result.definition);
                for (int i = 0; i < result.cells.size(); i++) {
                    checkpoint("encode", i, result.cells.size());
                    payloads.add(
                        result.cells.get(i)
                            .encodedTile());
                }
                long expanded = 0;
                for (int i = 0; i < payloads.size(); i++) {
                    checkpoint("budget", i, payloads.size());
                    String payload = payloads.get(i);
                    if (payload == null || payload.isEmpty()) continue;
                    Integer size = result.payloadSizes.get(payload);
                    if (size == null) {
                        try (HologramProcessGovernor.Lease working = HologramAttachmentMemory
                            .reserve("attachment expanded scan", 32768L + 2L * payload.length())) {
                            size = HologramBlueprintNbt.expandedBytes(payload);
                        }
                        HologramAttachmentMemory.retainResult(128L + 2L * payload.length());
                        result.payloadSizes.put(payload, size);
                    }
                    expanded += size;
                    if (expanded > 64L * 1024 * 1024) throw new IOException("附加对象细节超出蓝图 64MiB 数据预算，请降低分辨率或简化图片后重试");
                }
            }
        }

        @Override
        public synchronized void close() {
            Job previous = job;
            job = null;
            Input release = input;
            input = null;
            if (release != null) release.close();
            if (raster != null) raster.close();
            raster = null;
            // A queued cancellation drops all frozen references before its runnable is eventually dequeued.
            if (previous != null && previous.cancelled) previous.phase = "cancelled";
        }
    }

    private static final class Result implements AutoCloseable {

        private HologramAttachmentMemory.Owner memory;
        private HologramAttachmentMemory.Retention definitionMemory = new HologramAttachmentMemory.Retention();
        private List<HologramTextBox.Cell> cells;
        private Map<String, Integer> payloadSizes = new HashMap<>();
        private String definition;
        private byte[] picturePixels;

        Result(HologramAttachmentMemory.Owner memory, List<HologramTextBox.Cell> cells) {
            this.memory = memory;
            this.cells = cells;
        }

        void disown() {
            memory = null;
            definitionMemory = null;
            cells = Collections.emptyList();
            payloadSizes = Collections.emptyMap();
            definition = null;
            picturePixels = null;
        }

        @Override
        public void close() {
            if (memory != null) memory.close();
            disown();
        }
    }

    static byte[] picturePixels(HologramPictureBox picture) throws IOException {
        Job job = CURRENT.get();
        checkpoint("raster", 0, picture.height);
        return job != null && job.rasterSource == picture && job.raster != null ? job.raster : picture.rasterize();
    }

    static boolean cancelled() {
        Job job = CURRENT.get();
        return Thread.currentThread()
            .isInterrupted() || job != null && job.cancelled
            || HologramProcessGovernor.currentFlightCancelled();
    }

    static void checkpoint(String phase, long completed, long total) throws IOException {
        checkCancellation();
        Job job = CURRENT.get();
        if (job == null) return;
        job.phase = phase;
        job.completed = completed;
        job.total = total;
    }

    static void checkCancellation() throws IOException {
        if (cancelled()) throw new IOException("附加对象生成已取消");
    }
}
