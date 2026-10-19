package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.client.C17PacketCustomPayload;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Actual vanilla packet boundary, exact large geometry and durable processing presets. No world/GUI claims. */
public final class HologramLargeAttachmentRegression {

    private HologramLargeAttachmentRegression() {}

    public static void run() {
        try {
            fragmentedPicture();
            largeGeometry();
            savedPictureDefinitions();
            presets();
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static HologramTextBox picture(int side) {
        HologramTextBox value = new HologramTextBox();
        value.id = HologramPictureBox.PREFIX + "11111111-1111-1111-1111-111111111111";
        value.picture = new HologramPictureBox();
        HologramPictureBox p = value.picture;
        p.filename = "current.png";
        p.sourceWidth = p.sourceHeight = p.originalWidth = p.originalHeight = p.width = p.height = side;
        p.keepAspect = false;
        p.source = new int[side * side];
        Arrays.fill(p.source, 0xffdddddd);
        return value;
    }

    private static void fragmentedPicture() throws Exception {
        HologramTextBox value = picture(250);
        Random random = new Random(250);
        for (int i = 0; i < value.picture.source.length; i++)
            value.picture.source[i] = 0xff000000 | random.nextInt(1 << 24);
        NBTTagCompound action = new NBTTagCompound();
        action.setString("op", "placeTextBox");
        action.setString("session", "11111111-1111-1111-1111-111111111111");
        action.setLong("uiSequence", 12);
        action.setTag("textBox", value.write());
        ByteBuf legacy = Unpooled.buffer();
        try {
            legacy.writeByte(0);
            new HologramNetwork.Action(action).toBytes(legacy);
            byte[] oversized = new byte[legacy.readableBytes()];
            legacy.readBytes(oversized);
            SimpleAssert.that(oversized.length > 32767, "250x250 source reproduces the log's vanilla C17 limit");
            try {
                new C17PacketCustomPayload("gtmb", oversized);
                throw new AssertionError("vanilla C17 unexpectedly admitted legacy picture");
            } catch (IllegalArgumentException expected) {}
        } finally {
            legacy.release();
        }
        byte[] bytes = CompressedStreamTools.compress(action);
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes);
        HologramAttachmentUpload.Assembly assembly = new HologramAttachmentUpload.Assembly(9, bytes.length, digest);
        byte[] complete = null;
        int fragments = 0, largest = 0;
        for (int offset = 0; offset < bytes.length; offset += HologramAttachmentUpload.CHUNK_BYTES) {
            byte[] part = Arrays
                .copyOfRange(bytes, offset, Math.min(bytes.length, offset + HologramAttachmentUpload.CHUNK_BYTES));
            HologramAttachmentUpload.Fragment packet = new HologramAttachmentUpload.Fragment(
                action.getString("session"),
                12,
                9,
                bytes.length,
                fragments,
                digest,
                part);
            ByteBuf wire = Unpooled.buffer();
            try {
                wire.writeByte(8);
                packet.toBytes(wire);
                byte[] payload = new byte[wire.readableBytes()];
                wire.getBytes(0, payload);
                new C17PacketCustomPayload("gtmb", payload);
                largest = Math.max(largest, payload.length);
                wire.readByte();
                HologramAttachmentUpload.Fragment decoded = new HologramAttachmentUpload.Fragment();
                decoded.fromBytes(wire);
                complete = assembly
                    .accept(decoded.sequence, decoded.total, decoded.index, decoded.digest, decoded.data);
            } finally {
                wire.release();
            }
            fragments++;
        }
        SimpleAssert.that(
            fragments > 1 && largest < 32767 && Arrays.equals(bytes, complete),
            "bounded fragments reconstruct exact original source");
        SimpleAssert.eq(
            action,
            HologramSnapshotCodec
                .decode(complete, HologramAttachmentUpload.MAX_BYTES, 4L * HologramAttachmentUpload.MAX_BYTES),
            "fragmented NBT retains every source pixel and request identity");
        try {
            assembly.accept(9, bytes.length, 0, digest, new byte[HologramAttachmentUpload.CHUNK_BYTES]);
            throw new AssertionError("completed assembly accepted a duplicate");
        } catch (IOException expected) {}
        try {
            new HologramAttachmentUpload.Assembly(9, HologramAttachmentUpload.MAX_BYTES + 1, digest);
            throw new AssertionError("oversized upload allocated");
        } catch (IllegalArgumentException expected) {}
        HologramAttachmentUpload.Assembly fresh = new HologramAttachmentUpload.Assembly(9, bytes.length, digest);
        try {
            fresh.accept(9, bytes.length, 1, digest, new byte[HologramAttachmentUpload.CHUNK_BYTES]);
            throw new AssertionError("out-of-order fragment admitted");
        } catch (IOException expected) {}
        byte[] changed = digest.clone();
        changed[0] ^= 1;
        try {
            fresh.accept(9, bytes.length, 0, changed, new byte[HologramAttachmentUpload.CHUNK_BYTES]);
            throw new AssertionError("different digest mixed into upload");
        } catch (IOException expected) {}
        System.out.println(
            "GTMB_LARGE_ATTACHMENT_WIRE legacyBytes=" + bytes.length
                + " fragments="
                + fragments
                + " largestVanillaPayload="
                + largest);
    }

    private static void largeGeometry() throws Exception {
        HologramTextBox value = picture(1024);
        List<HologramTextBox.Cell> cells = value.generate();
        SimpleAssert.eq(4096, cells.size(), "1024-square thin image retains all 4096 native hosts");
        long volume = 0;
        for (HologramTextBox.Cell cell : cells) {
            SimpleAssert.eq(7, cell.boxes.length, "uniform thin host packs to one cuboid");
            volume += (long) (cell.boxes[4] - cell.boxes[1]) * (cell.boxes[5] - cell.boxes[2])
                * (cell.boxes[6] - cell.boxes[3]);
        }
        SimpleAssert.that(volume == 1024L * 1024, "maximum output is never silently downsampled or truncated");
        String definition = HologramBlueprintNbt.encodeCaptured(value.write());
        SimpleAssert.that(
            HologramBlueprintNbt.expandedBytes(definition) > 4 * 1024 * 1024,
            "maximum embedded ARGB source crosses old 4MiB boundary");
        SimpleAssert.eq(value.write(), HologramBlueprintNbt.decode(definition), "maximum embedded source roundtrips");
        HologramTextBox scaled = picture(1);
        scaled.pixelX = scaled.pixelY = 32;
        scaled.thickness = 16;
        cells = scaled.generate();
        SimpleAssert.eq(4, cells.size(), "one 32-unit pixel spans four blocks without voxel expansion");
        for (HologramTextBox.Cell cell : cells)
            SimpleAssert.that(cell.fullBlock, "scaled solid volume collapses to ordinary blocks");
        for (int invalid : new int[] { 0, 17, 33, 1025 }) {
            scaled.pixelX = scaled.pixelY = invalid;
            try {
                scaled.validate();
                throw new AssertionError("invalid pixel size admitted: " + invalid);
            } catch (IOException expected) {}
        }
        scaled.pixelX = scaled.pixelY = 32;
        HologramAttachmentGeometry.Job job = HologramAttachmentGeometry.submit(scaled);
        scaled.picture.source[0] = 0xff963430;
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!job.done && System.nanoTime() < deadline) Thread.sleep(2);
        SimpleAssert.that(
            job.done && job.failure == null && job.cells.size() == 4,
            "detached worker produces complete geometry and payloads");
        SimpleAssert.eq(0, job.cells.get(0).metadata[0], "worker owns an isolated source snapshot");
        SimpleAssert.that(!job.payloadSizes.isEmpty(), "server receives prevalidated expanded payload budgets");
        HologramMicroTiles overlap = new HologramMicroTiles();
        overlap.addBox(0, 0, 0, 16, 16, 16, "minecraft:wool", 0, 0xffffff);
        overlap.addBox(0, 0, 0, 16, 16, 16, "minecraft:wool", 14, 0xffffff);
        SimpleAssert.that(
            overlap.finish(0, 0, 0)
                .get(0).fullBlock,
            "fully occluded paints do not defeat complete-cube packing");
    }

    private static void savedPictureDefinitions() throws Exception {
        HologramTextBox noise = picture(512);
        Random random = new Random(526);
        for (int i = 0; i < noise.picture.source.length; i++)
            noise.picture.source[i] = 0xff000000 | random.nextInt(1 << 24);
        savedPictureDefinition(noise, "synthetic");
        String originals = System.getenv("GTMB_QA_PICTURES_JSON");
        if (originals == null || originals.isEmpty()) return;
        int index = 0;
        for (String filename : new com.google.gson.Gson().fromJson(originals, String[].class)) {
            Path path = java.nio.file.Paths.get(filename);
            byte[] before = Files.readAllBytes(path);
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(before));
            SimpleAssert.that(image != null, "provided image decodes");
            HologramTextBox value = picture(1);
            HologramPictureBox p = value.picture;
            p.sourceWidth = p.originalWidth = image.getWidth();
            p.sourceHeight = p.originalHeight = image.getHeight();
            p.source = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            savedPictureDefinition(value, "provided-" + ++index);
            SimpleAssert.that(Arrays.equals(before, Files.readAllBytes(path)), "provided image remains unchanged");
        }
    }

    private static void savedPictureDefinition(HologramTextBox value, String label) throws Exception {
        value.picture.keepAspect = true;
        value.picture.resizeShortSide(256);
        value.picture.brightness = 80;
        value.validate();
        String definition = HologramBlueprintNbt.encodeCaptured(value.write());
        SimpleAssert.that(definition.length() > 524288, "source crosses the obsolete attachment save limit");
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = "attachment_save";
        preset.name = "Picture save";
        preset.schema = 2;
        HologramPresets.Structure group = new HologramPresets.Structure();
        group.id = value.id;
        group.textBoxNbt = definition;
        preset.structures.add(group);
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.item = cell.block = "minecraft:wool";
        cell.structureId = group.id;
        preset.cells.add(cell);
        HologramBlueprintVersions.stamp(preset);
        HologramPresets.validate(preset);
        Path directory = Files.createTempDirectory("gtmb-picture-save-");
        try {
            Path file = directory.resolve("picture.json");
            HologramBlueprintSharing
                .write(file, preset, com.miaokatze.gtmb.testutil.BlueprintArtifactTestData.previewPng());
            HologramPresets.Preset restored = HologramBlueprintSharing.read(file);
            SimpleAssert.eq(
                definition,
                restored.structures.get(0).textBoxNbt,
                "paired save and reload preserve the entire editable source definition");
            SimpleAssert.eq(
                value.write(),
                HologramTextWorkflow.definition(restored.structures.get(0)),
                "brightness, output dimensions and original pixels survive save");
            group.id = "not_an_attachment";
            try {
                HologramPresets.validate(preset);
                throw new AssertionError("invalid attachment identity admitted");
            } catch (IOException expected) {}
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
        System.out.println(
            "GTMB_PICTURE_SAVE source=" + label
                + " definitionChars="
                + definition.length()
                + " output="
                + value.picture.width
                + "x"
                + value.picture.height
                + " brightness=80 sourceUnchanged=true");
    }

    private static void presets() throws Exception {
        Path directory = Files.createTempDirectory("gtmb-attachment-presets-");
        Path file = directory.resolve("presets.nbt");
        try {
            HologramTextBox picture = picture(20);
            picture.picture.keepAspect = true;
            picture.picture.resizeShortSide(64);
            picture.picture.filter = 3;
            picture.pixelX = picture.pixelY = 32;
            picture.x = 9;
            NBTTagCompound settings = HologramAttachmentPresets.settings(picture);
            SimpleAssert.that(
                !settings.hasKey("x") && !settings.hasKey("id")
                    && !settings.getCompoundTag("picture")
                        .hasKey("source")
                    && !settings.getCompoundTag("picture")
                        .hasKey("filename"),
                "preset excludes placement, identity and source content");
            HologramAttachmentPresets store = new HologramAttachmentPresets(file);
            HologramAttachmentPresets.Entry entry = store.save(true, "picture recipe", settings);
            HologramTextBox current = picture(40);
            current.picture.sourceHeight = current.picture.originalHeight = current.picture.height = 20;
            current.picture.source = new int[800];
            current.x = -4;
            HologramTextBox applied = HologramAttachmentPresets.apply(current, settings);
            SimpleAssert.eq(128, applied.picture.width, "locked preset follows current image aspect ratio");
            SimpleAssert.eq(64, applied.picture.height, "locked preset retains selected short side");
            SimpleAssert.eq(-4, applied.x, "applying preset preserves position");
            SimpleAssert.that(
                Arrays.equals(current.picture.source, applied.picture.source),
                "applying preset preserves source pixels");
            SimpleAssert.that(
                store.matches(true, HologramAttachmentPresets.settings(applied)) && !store.matches(false, settings),
                "preset recognition is aspect independent and type isolated");
            HologramTextBox text = new HologramTextBox();
            text.text = "retained content";
            text.width = text.height = 1;
            text.bitmap = new byte[] { 1 };
            text.x = 7;
            NBTTagCompound textSettings = HologramAttachmentPresets.settings(text);
            textSettings.setBoolean("bold", true);
            textSettings.setString("font", "dejavu");
            HologramTextBox textApplied = HologramAttachmentPresets.apply(text, textSettings);
            SimpleAssert.eq(text.text, textApplied.text, "font preset never replaces document content");
            SimpleAssert.that(
                textApplied.bold && textApplied.x == 7 && textApplied.font.equals("dejavu"),
                "font settings apply without moving content");
            store.save(false, "font recipe", textSettings);
            store.rename(entry, "renamed picture");
            store = new HologramAttachmentPresets(file);
            SimpleAssert.eq(
                "renamed picture",
                store.rows(true)
                    .get(0).name,
                "rename persists without rewriting font presets");
            store.delete(
                store.rows(true)
                    .get(0));
            SimpleAssert.that(
                new HologramAttachmentPresets(file).rows(true)
                    .isEmpty()
                    && new HologramAttachmentPresets(file).rows(false)
                        .size() == 1,
                "delete persists and preserves the other type");
            byte[] external = { 1, 2, 3 };
            Files.write(file, external);
            try {
                store.save(true, "stale writer", settings);
                throw new AssertionError("concurrent external change overwritten");
            } catch (IOException expected) {}
            SimpleAssert.that(
                Arrays.equals(external, Files.readAllBytes(file)),
                "stale writer preserves externally edited file");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}
