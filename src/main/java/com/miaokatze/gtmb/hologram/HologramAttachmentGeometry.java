package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Detached geometry only: no world, player, native tile factory or GL calls on the worker. */
public final class HologramAttachmentGeometry {

    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4),
        action -> {
            Thread thread = new Thread(action, "GTMB attachment geometry");
            thread.setDaemon(true);
            return thread;
        });

    private HologramAttachmentGeometry() {}

    public static final class Job {

        public volatile boolean done, cancelled;
        public volatile String phase = "queued";
        public volatile long completed, total;
        public List<HologramTextBox.Cell> cells = Collections.emptyList();
        public Map<String, Integer> payloadSizes = new HashMap<>();
        public String definition;
        public byte[] picturePixels;
        public Exception failure;

        public void cancel() {
            cancelled = true;
        }
    }

    public static Job submit(HologramTextBox source) throws IOException {
        return submit(source, Collections.emptyList());
    }

    static Job submit(HologramTextBox source, List<String> retained) throws IOException {
        HologramTextBox frozen = HologramTextBox.read(
            (net.minecraft.nbt.NBTTagCompound) source.write()
                .copy());
        List<String> payloads = new ArrayList<>(retained);
        Job job = new Job();
        try {
            WORKER.execute(() -> {
                CURRENT.set(job);
                try {
                    checkpoint("generate", 0, 0);
                    job.cells = frozen.generate();
                    if (frozen.picture != null) job.picturePixels = frozen.picture.rasterize();
                    job.definition = HologramBlueprintNbt.encodeCaptured(frozen.write());
                    payloads.add(job.definition);
                    for (int i = 0; i < job.cells.size(); i++) {
                        checkpoint("encode", i, job.cells.size());
                        payloads.add(
                            job.cells.get(i)
                                .encodedTile());
                    }
                    long expanded = 0;
                    for (int i = 0; i < payloads.size(); i++) {
                        checkpoint("budget", i, payloads.size());
                        String payload = payloads.get(i);
                        if (payload == null || payload.isEmpty()) continue;
                        Integer size = job.payloadSizes.get(payload);
                        if (size == null) {
                            size = HologramBlueprintNbt.expandedBytes(payload);
                            job.payloadSizes.put(payload, size);
                        }
                        expanded += size;
                        if (expanded > 64L * 1024 * 1024) throw new IOException("附加对象细节超出蓝图 64MiB 数据预算，请降低分辨率或简化图片后重试");
                    }
                } catch (Exception failure) {
                    job.failure = failure;
                    job.cells = Collections.emptyList();
                    job.payloadSizes.clear();
                } finally {
                    CURRENT.remove();
                    job.done = true;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            throw new IOException("附加对象生成队列已满，请稍后重试", busy);
        }
        return job;
    }

    static void checkpoint(String phase, long completed, long total) throws IOException {
        if (Thread.currentThread()
            .isInterrupted()) throw new IOException("附加对象生成已取消");
        Job job = CURRENT.get();
        if (job == null) return;
        if (job.cancelled) throw new IOException("附加对象生成已取消");
        job.phase = phase;
        job.completed = completed;
        job.total = total;
    }
}
