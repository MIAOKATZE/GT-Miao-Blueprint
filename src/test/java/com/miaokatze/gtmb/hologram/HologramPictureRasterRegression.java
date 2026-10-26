package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exact source/parameter reuse and cooperative cancellation during material quantization. */
public final class HologramPictureRasterRegression {

    private HologramPictureRasterRegression() {}

    public static void run() {
        try {
            exactInputs();
            cancelPalette();
            governedJob();
            HologramAttachmentContinuationRegression.run();
        } catch (Exception failure) {
            throw new AssertionError("picture raster contract", failure);
        }
    }

    private static void governedJob() throws Exception {
        HologramTextBox text = new HologramTextBox();
        text.id = HologramPictureBox.PREFIX + "11111111-1111-1111-1111-111111111111";
        text.picture = picture(2);
        HologramAttachmentRaster.clearCache();
        HologramAttachmentGeometry.Job queued;
        try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor.tryHeavyCpu("raster queue regression")) {
            SimpleAssert.that(cpu != null, "test owns the shared CPU permit");
            queued = HologramAttachmentGeometry.submit(text);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (HologramProcessGovernor.queuedHeavyCpu() == 0 && !queued.done && System.nanoTime() < deadline)
                Thread.sleep(1);
            SimpleAssert.that(!queued.done && queued.failure == null, "normal CPU contention remains queued");
        }
        await(queued);
        SimpleAssert.eq(null, queued.failure, "queued job automatically continues when CPU becomes available");
        SimpleAssert.eq(4, queued.picturePixels.length, "result retains the exact material raster");
        SimpleAssert.eq(0, HologramProcessGovernor.activeHeavyCpu(), "completed result does not retain CPU");
        long retained = HologramProcessGovernor.retainedBytes();
        queued.close();
        long released = HologramProcessGovernor.retainedBytes();
        queued.close();
        SimpleAssert.that(released < retained, "final consumer releases compact arrays and encoded results");
        SimpleAssert.eq(released, HologramProcessGovernor.retainedBytes(), "consumer close is idempotent");
        int computed = HologramAttachmentRaster.COMPUTATIONS.get();
        HologramAttachmentGeometry.Job ready = HologramAttachmentGeometry.submit(text);
        await(ready);
        SimpleAssert.eq(null, ready.failure, "exact repeated job reuses its frozen raster");
        ready.close();
        SimpleAssert
            .eq(computed, HologramAttachmentRaster.COMPUTATIONS.get(), "repeated geometry does not rerasterize");
        SimpleAssert.eq(released, HologramProcessGovernor.retainedBytes(), "exact reuse does not accumulate leases");
    }

    private static void await(HologramAttachmentGeometry.Job job) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!job.done && System.nanoTime() < deadline) Thread.sleep(1);
        SimpleAssert.that(job.done, "bounded pure attachment job completes");
    }

    private static HologramPictureBox picture(int side) {
        HologramPictureBox picture = new HologramPictureBox();
        picture.originalWidth = picture.originalHeight = picture.sourceWidth = picture.sourceHeight = side;
        picture.width = picture.height = side;
        picture.keepAspect = false;
        picture.source = new int[side * side];
        Arrays.fill(picture.source, 0xffdddddd);
        return picture;
    }

    private static void exactInputs() throws Exception {
        HologramPictureBox source = picture(2);
        byte[] pixels = source.rasterize();
        HologramAttachmentRaster.clearCache();
        int computed = HologramAttachmentRaster.COMPUTATIONS.get();
        raster(source);
        SimpleAssert.eq(++computed, HologramAttachmentRaster.COMPUTATIONS.get(), "first exact source is produced once");
        HologramPictureBox same = HologramPictureBox.read(
            (net.minecraft.nbt.NBTTagCompound) source.write()
                .copy());
        same.filename = "renamed.png";
        raster(same);
        SimpleAssert
            .eq(computed, HologramAttachmentRaster.COMPUTATIONS.get(), "filename does not change source pixels");
        same.source[0] ^= 1;
        raster(same);
        SimpleAssert
            .eq(++computed, HologramAttachmentRaster.COMPUTATIONS.get(), "every pixel participates in exact reuse");
        same.dither = true;
        raster(same);
        SimpleAssert
            .eq(++computed, HologramAttachmentRaster.COMPUTATIONS.get(), "dither participates in raster identity");
        same.palette = 1;
        raster(same);
        SimpleAssert
            .eq(++computed, HologramAttachmentRaster.COMPUTATIONS.get(), "palette semantics remain independent");
        SimpleAssert.that(Arrays.equals(pixels, source.rasterize()), "identity checks never change source output");
        HologramAttachmentRaster.clearCache();
    }

    private static void raster(HologramPictureBox source) throws Exception {
        HologramPictureBox frozen = HologramPictureBox.read(
            (net.minecraft.nbt.NBTTagCompound) source.write()
                .copy());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<byte[]> result = new AtomicReference<>();
        HologramAttachmentRaster.request(frozen, Runnable::run, (view, error) -> {
            failure.set(error);
            if (view != null) {
                result.set(
                    view.pixels()
                        .clone());
                view.close();
            }
        });
        SimpleAssert.eq(null, failure.get(), "inline producer delivers its view before releasing producer ownership");
        SimpleAssert.that(result.get() != null, "inline completion is installed before the producer starts");
    }

    @SuppressWarnings("unchecked")
    private static void cancelPalette() throws Exception {
        HologramPictureBox picture = picture(1024);
        HologramAttachmentGeometry.Job job = new HologramAttachmentGeometry.Job();
        Field currentField = HologramAttachmentGeometry.class.getDeclaredField("CURRENT");
        currentField.setAccessible(true);
        ThreadLocal<HologramAttachmentGeometry.Job> current = (ThreadLocal<HologramAttachmentGeometry.Job>) currentField
            .get(null);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            current.set(job);
            try {
                picture.rasterize();
                result.set(new AssertionError("cancelled raster completed"));
            } catch (IOException expected) {
                result.set(expected);
            } finally {
                current.remove();
            }
        }, "GTMB raster cancellation regression");
        worker.setDaemon(true);
        worker.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!job.phase.equals("palette") && worker.isAlive() && System.nanoTime() < deadline) Thread.yield();
        SimpleAssert.eq("palette", job.phase, "cancellation reaches the running raster phase");
        job.cancel();
        worker.join(2000);
        SimpleAssert.that(!worker.isAlive(), "running raster cooperatively stops at the next row");
        SimpleAssert.that(result.get() instanceof IOException, "cancelled raster has no success result");
        SimpleAssert.eq(0xffdddddd, picture.source[0], "cancellation retains embedded source pixels");
    }
}
