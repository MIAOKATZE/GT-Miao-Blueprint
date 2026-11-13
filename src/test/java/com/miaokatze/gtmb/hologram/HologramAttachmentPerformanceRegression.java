// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Isolated JVM evidence. No game, network throughput, TPS or native-world timing claim. */
public final class HologramAttachmentPerformanceRegression {

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAttachmentPerformanceRegression.class, args)) return;
        try {
            run();
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    public static void run() throws Exception {
        // Warm registry/codec initialization before the paired observation.
        await(HologramAttachmentGeometry.submit(text(16, 1)));
        compare("png-1024", picture(1024, false));
        compare("png-noise-256", picture(256, true));
        compare("text-1024x128", text(1024, 128));
        backgroundEdges();
        repeatedPayloadBudget();
        boundedPreparation();
        overloadAndRecovery();
        decodingReservation();
    }

    private static HologramTextBox picture(int side, boolean noise) throws IOException {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(32728);
        for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) image.setRGB(
            x,
            y,
            noise ? 0xff000000 | random.nextInt(1 << 24) : ((x / 16 + y / 16) & 1) == 0 ? 0xffdddddd : 0xff963430);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SimpleAssert.that(ImageIO.write(image, "png", bytes), "performance fixture is a real encoded PNG");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        HologramTextBox value = new HologramTextBox();
        value.id = HologramPictureBox.PREFIX + "11111111-1111-1111-1111-111111111111";
        HologramPictureBox p = value.picture = new HologramPictureBox();
        p.filename = "performance.png";
        p.originalWidth = p.sourceWidth = p.width = side;
        p.originalHeight = p.sourceHeight = p.height = side;
        p.shortSide = side;
        p.source = decoded.getRGB(0, 0, side, side, null, 0, side);
        decoded.flush();
        image.flush();
        value.validate();
        System.out.println("GTMB_ATTACHMENT_INPUT pngBytes=" + bytes.size() + " pixels=" + p.source.length);
        return value;
    }

    private static HologramTextBox text(int width, int height) {
        HologramTextBox value = new HologramTextBox();
        value.id = HologramTextBox.PREFIX + "11111111-1111-1111-1111-111111111111";
        value.width = width;
        value.height = height;
        value.bitmap = new byte[(width * height + 7) / 8];
        Arrays.fill(value.bitmap, (byte) 255);
        return value;
    }

    /** The old worker stages are replayed explicitly, retaining both raster passes and re-inflation. */
    private static void compare(String label, HologramTextBox source) throws Exception {
        NBTTagCompound before = (NBTTagCompound) source.write()
            .copy();
        long started = System.nanoTime();
        HologramTextBox frozen = HologramTextBox.read(
            (NBTTagCompound) source.write()
                .copy());
        List<HologramTextBox.Cell> expected = frozen.generate();
        byte[] pixels = frozen.picture == null ? null : frozen.picture.rasterize();
        String definition = HologramBlueprintNbt.encodeCaptured(frozen.write());
        Map<String, Integer> oldSizes = new HashMap<>();
        oldSizes.put(definition, HologramBlueprintNbt.expandedBytes(definition));
        for (HologramTextBox.Cell cell : expected) {
            String payload = cell.encodedTile();
            if (!oldSizes.containsKey(payload)) oldSizes.put(payload, HologramBlueprintNbt.expandedBytes(payload));
        }
        long oldNanos = System.nanoTime() - started;
        started = System.nanoTime();
        HologramAttachmentGeometry.Job job = HologramAttachmentGeometry.submit(source);
        long submitNanos = System.nanoTime() - started;
        await(job);
        long newNanos = System.nanoTime() - started;
        SimpleAssert.eq(expected.size(), job.cells.size(), "host count unchanged " + label);
        SimpleAssert.eq(definition, job.definition, "portable definition is byte exact " + label);
        SimpleAssert.eq(oldSizes, job.payloadSizes, "encoder sizes equal independent bounded re-inflation " + label);
        SimpleAssert.that(Arrays.equals(pixels, job.picturePixels), "preview palette equals geometry palette " + label);
        for (int i = 0; i < expected.size(); i++) {
            HologramTextBox.Cell a = expected.get(i), b = job.cells.get(i);
            SimpleAssert.that(
                a.x == b.x && a.y == b.y
                    && a.z == b.z
                    && a.fullBlock == b.fullBlock
                    && Arrays.equals(a.boxes, b.boxes)
                    && Arrays.equals(a.materials, b.materials)
                    && Arrays.equals(a.metadata, b.metadata)
                    && Arrays.equals(a.colors, b.colors),
                "all material geometry remains exact " + label + " " + i);
        }
        SimpleAssert.eq(before, source.write(), "success preserves source draft " + label);
        System.out.println(
            "GTMB_ATTACHMENT_PERF lane=isolated-jvm input=" + label
                + " hosts="
                + job.cells.size()
                + " oldNs="
                + oldNanos
                + " newNs="
                + newNanos
                + " submitNs="
                + submitNanos
                + " palettePasses="
                + (source.picture == null ? "0->0" : "2->1")
                + " generatedPayloadInflations="
                + oldSizes.size()
                + "->0");
    }

    private static void backgroundEdges() throws Exception {
        for (int[] size : new int[][] { { 1, 1 }, { 1, 9 }, { 9, 1 }, { 32, 32 } }) {
            HologramTextBox value = picture(32, false);
            HologramPictureBox p = value.picture;
            p.originalWidth = p.sourceWidth = p.width = size[0];
            p.originalHeight = p.sourceHeight = p.height = size[1];
            p.keepAspect = false;
            p.source = new int[size[0] * size[1]];
            Arrays.fill(p.source, 0xffdddddd);
            p.removeBackground = true;
            byte[] result = p.rasterize();
            SimpleAssert.that(Arrays.equals(new byte[result.length], result), "complete connected background removed");
            if (size[0] > 2 && size[1] > 2) {
                p.source[size[0] + 1] = 0xff963430;
                result = p.rasterize();
                SimpleAssert.that(result[size[0] + 1] != 0, "interior foreground survives border-only traversal");
            }
        }
    }

    private static void overloadAndRecovery() throws Exception {
        java.lang.reflect.Field field = HologramAttachmentGeometry.class.getDeclaredField("WORKER");
        field.setAccessible(true);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HologramTextBox source = text(16, 16);
        NBTTagCompound before = (NBTTagCompound) source.write()
            .copy();
        java.util.ArrayList<HologramAttachmentGeometry.Job> queued = new java.util.ArrayList<>();
        worker.execute(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
            }
        });
        try {
            SimpleAssert.that(entered.await(5, TimeUnit.SECONDS), "worker blocker actually entered");
            for (int i = 0; i < 4; i++) queued.add(HologramAttachmentGeometry.submit(source));
            try {
                HologramAttachmentGeometry.submit(source);
                throw new AssertionError("full queue admitted unbounded attachment work");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("队列已满"),
                    "overload is an explicit retryable result");
            }
            for (HologramAttachmentGeometry.Job job : queued) job.cancel();
            SimpleAssert.eq(before, source.write(), "overload and cancellation retain exact draft");
        } finally {
            release.countDown();
        }
        for (HologramAttachmentGeometry.Job job : queued) {
            awaitDone(job);
            SimpleAssert.that(job.failure != null && job.cells.isEmpty(), "cancelled queued result cannot install");
        }
        await(HologramAttachmentGeometry.submit(source));
        SimpleAssert.eq(before, source.write(), "retry after drain preserves all source data");
        System.out.println("GTMB_ATTACHMENT_OVERLOAD queue=4 rejected=1 cancelled=4 recovered=true draftExact=true");
    }

    private static void repeatedPayloadBudget() throws Exception {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setByteArray("source", new byte[4 * 1024 * 1024]);
        String encoded = HologramBlueprintNbt.encodeCaptured(payload);
        HologramTextBox source = text(1, 1);
        NBTTagCompound before = (NBTTagCompound) source.write()
            .copy();
        HologramAttachmentGeometry.Job job = HologramAttachmentGeometry
            .submit(source, java.util.Collections.nCopies(16, encoded));
        awaitDone(job);
        SimpleAssert.that(
            job.failure instanceof IOException && job.failure.getMessage()
                .contains("64MiB"),
            "every repeated retained payload still counts against total expanded budget");
        SimpleAssert.that(job.cells.isEmpty(), "budget failure publishes no geometry");
        SimpleAssert.eq(before, source.write(), "budget rejection preserves source");
    }

    @SuppressWarnings("unchecked")
    private static void decodingReservation() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) unsafe
            .allocateInstance(net.minecraft.entity.player.EntityPlayerMP.class);
        java.util.UUID id = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");
        for (java.lang.reflect.Field field : net.minecraft.entity.Entity.class.getDeclaredFields())
            if (field.getType() == java.util.UUID.class) {
                field.setAccessible(true);
                field.set(player, id);
            }
        for (java.lang.reflect.Field field : net.minecraft.entity.player.EntityPlayer.class.getDeclaredFields())
            if (field.getType() == com.mojang.authlib.GameProfile.class) {
                field.setAccessible(true);
                field.set(player, new com.mojang.authlib.GameProfile(id, "AttachmentReservation"));
            }
        HologramSession session = (HologramSession) unsafe.allocateInstance(HologramSession.class);
        java.lang.reflect.Field playerField = HologramSession.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(session, player);
        session.id = id.toString();
        java.lang.reflect.Field incomingField = HologramAttachmentUpload.class.getDeclaredField("INCOMING");
        incomingField.setAccessible(true);
        Map<java.util.UUID, Object> incoming = (Map<java.util.UUID, Object>) incomingField.get(null);
        java.lang.reflect.Field reserved = HologramAttachmentUpload.class.getDeclaredField("reservedBytes");
        reserved.setAccessible(true);
        SimpleAssert.that(incoming.isEmpty() && reserved.getInt(null) == 0, "isolated upload accounting starts empty");
        Class<?> incomingType = Class.forName(HologramAttachmentUpload.class.getName() + "$Incoming");
        java.lang.reflect.Constructor<?> constructor = incomingType.getDeclaredConstructor(
            net.minecraft.entity.player.EntityPlayerMP.class,
            net.minecraft.world.World.class,
            HologramAttachmentUpload.Fragment.class);
        constructor.setAccessible(true);
        java.lang.reflect.Field decoding = incomingType.getDeclaredField("decoding");
        decoding.setAccessible(true);
        java.lang.reflect.Method release = HologramAttachmentUpload.class
            .getDeclaredMethod("release", java.util.UUID.class, incomingType);
        release.setAccessible(true);
        try {
            HologramAttachmentUpload.Fragment fragment = new HologramAttachmentUpload.Fragment(
                session.id,
                1,
                1,
                HologramAttachmentUpload.MAX_BYTES,
                0,
                new byte[32],
                new byte[HologramAttachmentUpload.CHUNK_BYTES]);
            Object old = constructor.newInstance(player, null, fragment);
            decoding.setBoolean(old, true);
            incoming.put(id, old);
            reserved.setInt(null, HologramAttachmentUpload.MAX_BYTES);
            SimpleAssert.that(HologramAttachmentUpload.pending(session), "decoder retains admission ownership");
            Object replacement = constructor.newInstance(player, null, fragment);
            incoming.put(id, replacement);
            release.invoke(null, id, old);
            SimpleAssert.that(
                incoming.get(id) == replacement && reserved.getInt(null) == fragment.total,
                "late decode completion cannot release a replacement upload or its reservation");
            decoding.setBoolean(replacement, true);
            SimpleAssert.that(HologramAttachmentUpload.pending(session), "pending includes final dispatch interval");
            release.invoke(null, id, replacement);
            release.invoke(null, id, replacement);
            SimpleAssert.that(
                !HologramAttachmentUpload.pending(session) && reserved.getInt(null) == 0,
                "dispatch completion releases exactly once and enables retry");
            NBTTagCompound cancel = new NBTTagCompound();
            cancel.setString("attachmentSession", session.id);
            cancel.setLong("attachmentUiSequence", 1);
            cancel.setLong("attachmentSequence", 1);
            cancel.setLong("attachmentPlanRevision", 0);
            cancel.setLong("uiSequence", 2);
            cancel.setLong("textRequest", 7);
            Object partial = constructor.newInstance(player, null, fragment);
            incoming.put(id, partial);
            reserved.setInt(null, fragment.total);
            cancel.setLong("attachmentSequence", 2);
            SimpleAssert.that(
                !HologramAttachmentUpload.cancel(session, cancel) && HologramAttachmentUpload.pending(session),
                "wrong generation cannot cancel current upload");
            cancel.setLong("attachmentSequence", 1);
            cancel.setLong("attachmentPlanRevision", 1);
            SimpleAssert.that(!HologramAttachmentUpload.cancel(session, cancel), "wrong revision cannot cancel upload");
            cancel.setLong("attachmentPlanRevision", 0);
            SimpleAssert.that(
                HologramAttachmentUpload.cancel(session, cancel) && !HologramAttachmentUpload.pending(session)
                    && reserved.getInt(null) == 0,
                "partial upload cancellation releases immediately instead of waiting thirty seconds");
            Object decodingUpload = constructor.newInstance(player, null, fragment);
            decoding.setBoolean(decodingUpload, true);
            incoming.put(id, decodingUpload);
            reserved.setInt(null, fragment.total);
            SimpleAssert.that(
                HologramAttachmentUpload.cancel(session, cancel) && !HologramAttachmentUpload.pending(session)
                    && reserved.getInt(null) == fragment.total,
                "cancelled decoder loses commit authority but retains its global byte reservation");
            HologramAttachmentUpload.clearServer();
            SimpleAssert.that(
                incoming.get(id) == decodingUpload && reserved.getInt(null) == fragment.total,
                "server teardown cannot release a still-running decoder reservation");
            java.lang.reflect.Field decoded = incomingType.getDeclaredField("decoded");
            decoded.setAccessible(true);
            decoded.setBoolean(decodingUpload, true);
            HologramAttachmentUpload.clearServer();
            SimpleAssert.that(
                incoming.isEmpty() && reserved.getInt(null) == 0,
                "completed cancelled decoder is reclaimable after teardown");
            HologramTextWorkflow.Task task = (HologramTextWorkflow.Task) unsafe
                .allocateInstance(HologramTextWorkflow.Task.class);
            setTask(task, "revision", 0L);
            setTask(task, "uiSequence", 1L);
            setTask(task, "uploadSequence", 1L);
            setTask(task, "request", 7L);
            SimpleAssert
                .that(task.matchesCancellation(cancel), "exact upload cancellation also matches geometry continuation");
            cancel.setLong("textRequest", 8);
            SimpleAssert
                .that(!task.matchesCancellation(cancel), "old cancellation cannot remove a new geometry request");
        } finally {
            HologramAttachmentUpload.clearServer();
        }
        System.out.println(
            "GTMB_ATTACHMENT_UPLOAD_ACCOUNTING decodingReserved=true staleCompletionIgnored=true releasedExactlyOnce=true");
    }

    /** Exercise the actual preparation continuation with 102400 retained positions, without native world claims. */
    private static void boundedPreparation() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        HologramTextWorkflow.Task task = (HologramTextWorkflow.Task) unsafe
            .allocateInstance(HologramTextWorkflow.Task.class);
        HologramTextBox source = text(1, 1);
        HologramCapture capture = new HologramCapture(null, null);
        for (int i = 0; i < 102400; i++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(null, i, 64, 0);
            HologramBlueprintData.setMemberships(cell, java.util.Collections.singletonList(source.id));
            capture.cells.add(cell);
        }
        java.util.Set<String> removed = new java.util.HashSet<>();
        setTask(task, "text", source);
        setTask(task, "capture", capture);
        setTask(task, "preset", new HologramPresets.Preset());
        setTask(task, "retained", new java.util.ArrayList<>());
        setTask(task, "remove", removed);
        java.lang.reflect.Method prepare = HologramTextWorkflow.Task.class.getDeclaredMethod("prepare", long.class);
        prepare.setAccessible(true);
        int calls = 0, maxUnits = 0;
        long maxNanos = 0;
        while (getTask(task, "geometry") == null) {
            int oldCount = removed.size();
            long started = System.nanoTime();
            prepare.invoke(task, started + 2_000_000L);
            maxNanos = Math.max(maxNanos, System.nanoTime() - started);
            maxUnits = Math.max(maxUnits, removed.size() - oldCount);
            calls++;
            SimpleAssert.that(removed.size() - oldCount <= 256, "main-thread preparation yields within item budget");
        }
        await((HologramAttachmentGeometry.Job) getTask(task, "geometry"));
        SimpleAssert.eq(capture.cells.size(), removed.size(), "all old attachment positions are staged for removal");
        SimpleAssert.eq(102400, capture.cells.size(), "private preparation cannot delete published geometry");
        System.out.println(
            "GTMB_ATTACHMENT_PREPARATION lane=isolated-jvm positions=" + capture.cells.size()
                + " calls="
                + calls
                + " maxUnits="
                + maxUnits
                + " maxNs="
                + maxNanos
                + " atomicPublishedSourceRetained=true");
    }

    private static Object getTask(HologramTextWorkflow.Task task, String name) throws Exception {
        java.lang.reflect.Field field = HologramTextWorkflow.Task.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(task);
    }

    private static void setTask(HologramTextWorkflow.Task task, String name, Object value) throws Exception {
        java.lang.reflect.Field field = HologramTextWorkflow.Task.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(task, value);
    }

    private static void await(HologramAttachmentGeometry.Job job) throws Exception {
        awaitDone(job);
        if (job.failure != null) throw job.failure;
    }

    private static void awaitDone(HologramAttachmentGeometry.Job job) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!job.done && System.nanoTime() < deadline) Thread.sleep(1);
        SimpleAssert.that(job.done, "bounded detached job completes");
    }
}
