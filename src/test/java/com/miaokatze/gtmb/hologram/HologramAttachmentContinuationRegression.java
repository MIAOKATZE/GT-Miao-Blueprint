package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual raster -> geometry continuations, shared cancellation and allocation-failure ownership. */
public final class HologramAttachmentContinuationRegression {

    private HologramAttachmentContinuationRegression() {}

    public static void run() throws Exception {
        sharedRunningProducer();
        cancelAllQueued();
        evictionRetainsBorrowedView();
        resultOwnerExport();
        packingQuota();
        encodingQuota();
    }

    private static void resultOwnerExport() throws Exception {
        long baseline = HologramProcessGovernor.retainedBytes();
        HologramAttachmentMemory.Owner temporary = new HologramAttachmentMemory.Owner();
        HologramAttachmentMemory.Retention published = new HologramAttachmentMemory.Retention();
        Object actualOwner = new Object();
        try (HologramAttachmentMemory.Scope scope = HologramAttachmentMemory.enter(temporary)) {
            HologramAttachmentMemory
                .retain(actualOwner, published, HologramAttachmentMemory.reserve("attachment export fixture", 4096));
            HologramAttachmentMemory.export(actualOwner, published);
        }
        temporary.close();
        SimpleAssert.eq(
            baseline + 4096,
            HologramProcessGovernor.retainedBytes(),
            "publication keeps its bytes after the temporary job closes");
        published.close();
        published.close();
        SimpleAssert
            .eq(baseline, HologramProcessGovernor.retainedBytes(), "final published owner releases exactly once");
    }

    private static void sharedRunningProducer() throws Exception {
        HologramAttachmentRaster.clearCache();
        HologramTextBox source = picture(512, 0xffdedede);
        HologramAttachmentGeometry.Job first, second;
        int computed = HologramAttachmentRaster.COMPUTATIONS.get();
        try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor.tryHeavyCpu("shared raster fixture")) {
            SimpleAssert.that(cpu != null, "fixture owns CPU before both subscribers join");
            first = HologramAttachmentGeometry.submit(source);
            second = HologramAttachmentGeometry.submit(source);
        }
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (HologramAttachmentRaster.COMPUTATIONS.get() == computed && System.nanoTime() < deadline) Thread.sleep(1);
        first.cancel();
        await(second);
        SimpleAssert.eq(null, second.failure, "cancelling the first subscriber preserves the shared producer");
        SimpleAssert
            .eq(computed + 1, HologramAttachmentRaster.COMPUTATIONS.get(), "concurrent exact raster miss runs once");
        SimpleAssert.eq(512 * 512, second.picturePixels.length, "the surviving subscriber receives every pixel");
        SimpleAssert.that(!second.cells.isEmpty(), "the geometry continuation runs after shared raster completion");
        first.close();
        second.close();
        HologramAttachmentRaster.clearCache();
    }

    private static void cancelAllQueued() throws Exception {
        HologramAttachmentRaster.clearCache();
        long baseline = HologramProcessGovernor.retainedBytes();
        int computed = HologramAttachmentRaster.COMPUTATIONS.get();
        try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor.tryHeavyCpu("cancel queued raster fixture")) {
            SimpleAssert.that(cpu != null, "fixture holds CPU while both subscribers cancel");
            HologramTextBox source = picture(32, 0xff123456);
            HologramAttachmentGeometry.Job first = HologramAttachmentGeometry.submit(source);
            HologramAttachmentGeometry.Job second = HologramAttachmentGeometry.submit(source);
            first.cancel();
            second.cancel();
            SimpleAssert.that(first.done && second.done, "queued cancellation acknowledges immediately");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (HologramProcessGovernor.retainedBytes() != baseline && System.nanoTime() < deadline) Thread.sleep(1);
            SimpleAssert
                .eq(baseline, HologramProcessGovernor.retainedBytes(), "cancelled queued inputs release every lease");
        }
        SimpleAssert.eq(computed, HologramAttachmentRaster.COMPUTATIONS.get(), "fully cancelled work never rasterizes");
    }

    private static void evictionRetainsBorrowedView() throws Exception {
        HologramAttachmentRaster.clearCache();
        long baseline = HologramProcessGovernor.retainedBytes();
        AtomicReference<HologramAttachmentRaster.Handle> first = new AtomicReference<>(),
            second = new AtomicReference<>();
        HologramAttachmentRaster.request(picture(2, 0xffdedede).picture, Runnable::run, (view, error) -> {
            SimpleAssert.eq(null, error, "first inline raster is complete");
            first.set(view);
        });
        byte[] expected = first.get()
            .pixels()
            .clone();
        HologramAttachmentRaster.request(picture(3, 0xff1a1616).picture, Runnable::run, (view, error) -> {
            SimpleAssert.eq(null, error, "second inline raster evicts the first cache entry");
            second.set(view);
        });
        SimpleAssert.that(
            Arrays.equals(
                expected,
                first.get()
                    .pixels()),
            "cache eviction keeps an active subscriber alive");
        long retained = HologramProcessGovernor.retainedBytes();
        first.get()
            .close();
        SimpleAssert
            .that(HologramProcessGovernor.retainedBytes() < retained, "last old view releases its retired cache bytes");
        second.get()
            .close();
        HologramAttachmentRaster.clearCache();
        SimpleAssert.eq(baseline, HologramProcessGovernor.retainedBytes(), "all raster reference owners release once");
    }

    private static void packingQuota() throws Exception {
        long baseline = HologramProcessGovernor.retainedBytes();
        try (HologramMicroTiles tiles = new HologramMicroTiles()) {
            tiles.addBox(0, 0, 0, 16, 16, 16, "minecraft:wool", 0, 0xffffff);
            try (HologramProcessGovernor.Lease occupied = leaveBytes(1800)) {
                try {
                    tiles.finish(0, 0, 0);
                    throw new AssertionError("compact allocation exceeded admitted bytes");
                } catch (IOException expected) {
                    SimpleAssert.that(
                        expected.getMessage()
                            .contains("内存"),
                        "packing quota is an explicit retryable limit");
                }
            }
        }
        SimpleAssert.eq(
            baseline,
            HologramProcessGovernor.retainedBytes(),
            "failed packing frees host, layer and scratch arrays");
    }

    private static void encodingQuota() throws Exception {
        long baseline = HologramProcessGovernor.retainedBytes();
        HologramTextBox.Cell cell = new HologramTextBox.Cell(
            0,
            0,
            0,
            new int[] { 0, 0, 0, 0, 16, 16, 16 },
            new String[] { "minecraft:wool" },
            new int[] { 0 },
            new int[] { 0xffffff },
            true);
        try (HologramProcessGovernor.Lease occupied = leaveBytes(1024)) {
            try {
                cell.encodedTile();
                throw new AssertionError("NBT materialization exceeded admitted bytes");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("内存"),
                    "NBT encoding reserves its envelope before copying");
            }
        } finally {
            cell.close();
        }
        SimpleAssert
            .eq(baseline, HologramProcessGovernor.retainedBytes(), "failed encoding does not publish or retain output");
    }

    private static HologramProcessGovernor.Lease leaveBytes(long available) {
        return HologramProcessGovernor.retain(
            "attachment quota fixture",
            HologramProcessGovernor.retainedByteLimit() - HologramProcessGovernor.retainedBytes() - available);
    }

    private static HologramTextBox picture(int side, int color) {
        HologramTextBox text = new HologramTextBox();
        text.id = HologramPictureBox.PREFIX + "11111111-1111-1111-1111-111111111111";
        text.picture = new HologramPictureBox();
        HologramPictureBox picture = text.picture;
        picture.originalWidth = picture.originalHeight = picture.sourceWidth = picture.sourceHeight = side;
        picture.width = picture.height = side;
        picture.keepAspect = false;
        picture.source = new int[side * side];
        Arrays.fill(picture.source, color);
        return text;
    }

    private static void await(HologramAttachmentGeometry.Job job) throws InterruptedException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!job.done && System.nanoTime() < deadline) Thread.sleep(1);
        SimpleAssert.that(job.done, "bounded attachment continuation completes");
    }
}
