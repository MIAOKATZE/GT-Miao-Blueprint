package com.miaokatze.gtmb.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real PNG and filesystem regressions; no Minecraft bootstrap or graphics context is needed. */
public final class HologramBlueprintArtifactRegression {

    private static final byte[] SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };

    private HologramBlueprintArtifactRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("Hologram blueprint artifact regressions passed");
    }

    public static void run() {
        try {
            byte[] png = image();
            exactBytesAndBinding(png);
            wireDescriptor(png);
            malformedPng(png);
            validEncodingVariants();
            boundedExpansion();
            pairTransactions(png);
            pendingTransactions(png);
        } catch (Exception failure) {
            throw new AssertionError("blueprint artifact regression", failure);
        }
    }

    private static byte[] image() throws IOException {
        BufferedImage image = new BufferedImage(640, 640, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) image.setRGB(
            x,
            y,
            (x > 90 && x < 550 && y > 70 && y < 570 ? 0xff000000 : 0) | x * 255 / 640 << 16
                | y * 255 / 640 << 8
                | 0x35);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SimpleAssert.that(ImageIO.write(image, "png", bytes), "real PNG encoder is available");
        image.flush();
        return bytes.toByteArray();
    }

    private static byte[] json(String id) {
        return (" \n{\"schema\":2,\"id\":\"" + id + "\",\"name\":\"\u732b\"}\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void exactBytesAndBinding(byte[] png) throws Exception {
        byte[] json = json("private_fixture"), original = json.clone();
        HologramBlueprintArtifact.validatePng(png);
        byte[] bound = HologramBlueprintArtifact.bindPreview(json, png);
        SimpleAssert.that(Arrays.equals(original, json), "binding does not rewrite JSON or caller bytes");
        SimpleAssert.that(Arrays.equals(idat(png), idat(bound)), "binding retains exact compressed pixels");
        SimpleAssert.that(
            Arrays.equals(bound, HologramBlueprintArtifact.bindPreview(json, bound)),
            "binding matching JSON is idempotent");
        HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact
            .decode(HologramBlueprintArtifact.encode(json, png));
        SimpleAssert.that(Arrays.equals(original, artifact.json), "wire preserves JSON UTF-8 and whitespace exactly");
        SimpleAssert.that(Arrays.equals(bound, artifact.png), "wire preserves the complete bound PNG");
        SimpleAssert.eq(640, artifact.width, "descriptor width");
        SimpleAssert.eq(640, artifact.height, "descriptor height");
        SimpleAssert
            .eq(HologramBlueprintArtifact.hash(original), artifact.jsonSha256, "JSON digest exposes exact bytes");
        SimpleAssert.eq(HologramBlueprintArtifact.hash(bound), artifact.pngSha256, "PNG digest exposes exact bytes");
        byte[] publicJson = json("public_fixture");
        byte[] rebound = HologramBlueprintArtifact.bindPreview(publicJson, bound);
        SimpleAssert.that(!Arrays.equals(bound, rebound), "public identity rebinding changes source digest");
        SimpleAssert.that(Arrays.equals(idat(bound), idat(rebound)), "public rebinding retains exact captured pixels");
        SimpleAssert.that(
            Arrays.equals(publicJson, HologramBlueprintArtifact.decode(wire(publicJson, rebound)).json),
            "public identity uses its actual serialized digest");
        expectIo(
            () -> HologramBlueprintArtifact.decode(wire(publicJson, bound)),
            "old private digest rejects public JSON");
    }

    private static void wireDescriptor(byte[] png) throws Exception {
        byte[] json = json("descriptor"), wire = HologramBlueprintArtifact.encode(json, png);
        expectIo(() -> HologramBlueprintArtifact.decode(json), "old raw JSON wire format is rejected");
        expectIo(
            () -> HologramBlueprintArtifact.decode(Arrays.copyOf(wire, wire.length - 1)),
            "truncated wire rejected");
        expectIo(
            () -> HologramBlueprintArtifact.decode(Arrays.copyOf(wire, wire.length + 1)),
            "appended wire rejected");
        for (int offset : new int[] { 0, 11, 15, 19, 23, 27, 28, 60, 92, wire.length - 1 }) {
            byte[] corrupt = wire.clone();
            corrupt[offset] ^= 1;
            expectIo(
                () -> HologramBlueprintArtifact.decode(corrupt),
                "descriptor/payload alteration rejected at " + offset);
        }
        byte[] tooLarge = new byte[HologramBlueprintArtifact.MAX_JSON_BYTES + 1];
        expectIo(() -> HologramBlueprintArtifact.encode(tooLarge, png), "JSON budget checked before serialization");
        expectIo(() -> HologramBlueprintArtifact.encode(new byte[0], png), "missing JSON rejected");
        expectIo(() -> HologramBlueprintArtifact.encode(json, null), "missing PNG rejected");
        byte[] exactLimit = new byte[HologramBlueprintArtifact.MAX_JSON_BYTES];
        Arrays.fill(exactLimit, (byte) ' ');
        exactLimit[0] = '{';
        exactLimit[1] = '}';
        SimpleAssert.that(
            Arrays.equals(
                exactLimit,
                HologramBlueprintArtifact.decode(HologramBlueprintArtifact.encode(exactLimit, png)).json),
            "exact JSON budget is accepted and remains byte exact");
        Path directory = Files.createTempDirectory("gtmb-artifact-capacity-");
        Path file = directory.resolve("capacity.json");
        try {
            HologramBlueprintArtifact.writePair(file, exactLimit, png);
            HologramBlueprintArtifact.Artifact restored = HologramBlueprintArtifact.readPair(file);
            SimpleAssert.that(Arrays.equals(exactLimit, restored.json), "exact 32MiB JSON pair reloads byte exact");
            SimpleAssert.eq(
                HologramBlueprintArtifact.hash(exactLimit),
                restored.jsonSha256,
                "full-capacity disk pair retains the exact JSON digest");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(HologramBlueprintArtifact.previewPath(file));
            Files.deleteIfExists(directory);
        }
        byte[] bound = HologramBlueprintArtifact.bindPreview(json, png);
        expectIo(() -> HologramBlueprintArtifact.decode(wire(json, png)), "valid unbound PNG cannot enter wire");
        byte[] duplicate = insert(bound, "tEXt", text(HologramBlueprintArtifact.hash(json)));
        expectIo(() -> HologramBlueprintArtifact.decode(wire(json, duplicate)), "duplicate source binding rejected");
    }

    private static void malformedPng(byte[] png) throws Exception {
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(new byte[HologramBlueprintArtifact.MAX_PNG_BYTES + 1]),
            "PNG byte budget checked before chunk parsing");
        expectIo(() -> HologramBlueprintArtifact.validatePng(new byte[100]), "non-PNG bytes rejected");
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(Arrays.copyOf(png, png.length + 1)),
            "appended PNG rejected");
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(Arrays.copyOf(png, png.length - 3)),
            "truncated PNG rejected");
        byte[] crc = png.clone();
        crc[crc.length - 1] ^= 1;
        expectIo(() -> HologramBlueprintArtifact.validatePng(crc), "IEND CRC checked");
        byte[] header = chunkData(png, "IHDR");
        header[0] = 0x7f;
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(replace(png, "IHDR", header)),
            "CRC-correct oversized dimensions rejected before image allocation");
        byte[] length = png.clone();
        length[8] = 0x7f;
        expectIo(() -> HologramBlueprintArtifact.validatePng(length), "huge chunk length rejected before allocation");
        expectIo(() -> HologramBlueprintArtifact.validatePng(replace(png, "IDAT", null)), "missing IDAT rejected");
        expectIo(() -> HologramBlueprintArtifact.validatePng(replace(png, "IEND", null)), "missing IEND rejected");
        byte[] data = idat(png);
        data[data.length / 2] ^= 0x40;
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(replace(png, "IDAT", data)),
            "corrupt zlib rejected even with corrected chunk CRC");
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(insert(png, "tEXt", new byte[] { 0, 'x' })),
            "malformed text keyword rejected");
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(
                insert(png, "tEXt", text("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))),
            "malformed source digest rejected");
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(insert(png, "zTXt", new byte[] { 'x', 0, 0, 1 })),
            "compressed metadata cannot inflate outside pixel budget");
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(insert(png, "ABCD", new byte[0])),
            "unknown critical chunk rejected");
    }

    private static void boundedExpansion() throws Exception {
        int rawLength = 640 * (1 + 640 * 4);
        byte[] raw = new byte[rawLength];
        HologramBlueprintArtifact.validatePng(rawPng(raw, 6, 8, null));
        byte[] filter = raw.clone();
        filter[0] = 5;
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(rawPng(filter, 6, 8, null)),
            "invalid PNG filter rejected");
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(rawPng(new byte[rawLength - 1], 6, 8, null)),
            "short inflated scanlines rejected");
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(rawPng(new byte[rawLength + 1024 * 1024], 6, 8, null)),
            "overexpanding IDAT stops after exact image budget");
        byte[] png = rawPng(raw, 6, 8, null), data = idat(png), extra = deflate(new byte[64]);
        byte[] concatenated = Arrays.copyOf(data, data.length + extra.length);
        System.arraycopy(extra, 0, concatenated, data.length, extra.length);
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(replace(png, "IDAT", concatenated)),
            "second zlib stream hidden inside IDAT rejected");
        byte[] indexed = new byte[640 * (1 + 640 / 8)];
        for (int row = 0; row < 640; row++) Arrays.fill(indexed, row * 81 + 1, (row + 1) * 81, (byte) 255);
        expectIo(
            () -> HologramBlueprintArtifact.validatePng(rawPng(indexed, 3, 1, new byte[] { 0, 0, 0 })),
            "palette pixels must reference an existing entry");
    }

    private static void validEncodingVariants() throws Exception {
        HologramBlueprintArtifact.validatePng(rawPng(new byte[640 * (1 + 640 * 8)], 6, 16, null));
        HologramBlueprintArtifact.validatePng(rawPng(new byte[640 * 81], 3, 1, new byte[] { 0, 0, 0 }));
        BufferedImage image = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png")
            .next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
            image.flush();
        }
        byte[] png = bytes.toByteArray();
        SimpleAssert.eq(1, (int) chunkData(png, "IHDR")[12], "real PNG encoder produces Adam7 fixture");
        HologramBlueprintArtifact.validatePng(png);
    }

    private static void pairTransactions(byte[] png) throws Exception {
        Path directory = Files.createTempDirectory("gtmb-artifact-regression-");
        Path file = directory.resolve("fixture.json"), preview = HologramBlueprintArtifact.previewPath(file);
        byte[] original = json("first"), replacement = json("second");
        try {
            HologramBlueprintArtifact.writePair(file, original, png);
            SimpleAssert
                .that(Arrays.equals(original, Files.readAllBytes(file)), "disk JSON has no wrapper or reserialization");
            HologramBlueprintArtifact.Artifact first = HologramBlueprintArtifact.readPair(file);
            SimpleAssert.that(Arrays.equals(first.json, original), "JSON/PNG disk pair loads");
            members(directory, 2, "initial save creates exactly one JSON and one PNG");
            HologramBlueprintArtifact.writePair(file, replacement, first.png);
            SimpleAssert.that(
                Arrays.equals(replacement, HologramBlueprintArtifact.readPair(file).json),
                "overwrite updates both same-basename members");
            members(directory, 2, "overwrite leaves no extra PNG or staging files");
            byte[] savedJson = Files.readAllBytes(file), savedPng = Files.readAllBytes(preview);
            expectIo(
                () -> HologramBlueprintArtifact.writePair(file, original, new byte[10]),
                "invalid overwrite rejected before touching prior pair");
            pairEquals(file, savedJson, savedPng, "invalid overwrite preserves prior pair");
            expectIo(() -> HologramBlueprintArtifact.writePair(file, original, png, (from, to) -> {
                if (to.equals(
                    file.toAbsolutePath()
                        .normalize()))
                    throw new IOException("injected JSON replace failure");
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }), "failure after PNG replace triggers rollback");
            pairEquals(file, savedJson, savedPng, "failed second-member replace restores both exact prior files");
            members(directory, 2, "failed overwrite cleans staging files");
            AtomicInteger checks = new AtomicInteger();
            expectIo(
                () -> HologramBlueprintArtifact.writePair(file, original, png, () -> checks.incrementAndGet() <= 3),
                "source revision changing after PNG replacement cancels commit");
            pairEquals(file, savedJson, savedPng, "stale revision after first member restores prior pair");
            members(directory, 2, "cancelled first-member commit cleans staging files");
            AtomicInteger finalChecks = new AtomicInteger();
            expectIo(
                () -> HologramBlueprintArtifact
                    .writePair(file, original, png, () -> finalChecks.incrementAndGet() <= 4),
                "source revision changing after JSON replacement cancels commit");
            pairEquals(file, savedJson, savedPng, "stale revision after both members restores prior pair");
            members(directory, 2, "cancelled complete commit cleans staging files");
            expectIo(() -> HologramBlueprintArtifact.deletePair(file, (from, to) -> {
                if (from.equals(
                    preview.toAbsolutePath()
                        .normalize()))
                    throw new IOException("injected PNG delete failure");
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }), "failure after JSON staged out triggers delete rollback");
            pairEquals(file, savedJson, savedPng, "failed second-member deletion restores exact prior pair");
            members(directory, 2, "failed deletion cleans staging files");
            Files.write(file, original);
            expectIo(
                () -> HologramBlueprintArtifact.readPair(file),
                "mixed generations rejected by JSON source digest");
            Files.write(file, savedJson);
            Files.delete(preview);
            expectIo(() -> HologramBlueprintArtifact.readPair(file), "old JSON-only artifact is not silently accepted");
            Files.createDirectory(preview);
            expectIo(
                () -> HologramBlueprintArtifact.writePair(file, original, png),
                "PNG directory prevents overwrite");
            expectIo(() -> HologramBlueprintArtifact.deletePair(file), "PNG directory prevents partial deletion");
            SimpleAssert
                .that(Arrays.equals(savedJson, Files.readAllBytes(file)), "PNG path failure preserves previous JSON");
            Files.delete(preview);
            HologramBlueprintArtifact.writePair(file, replacement, png);
            Files.delete(file);
            expectIo(() -> HologramBlueprintArtifact.readPair(file), "PNG-only artifact rejected");
            HologramBlueprintArtifact.deletePair(file);
            members(directory, 0, "delete removes incomplete artifact member too");
            expectIo(() -> HologramBlueprintArtifact.writePair(file, original, png, (from, to) -> {
                if (to.equals(
                    file.toAbsolutePath()
                        .normalize()))
                    throw new IOException("injected new JSON failure");
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }), "initial save second-member failure rejected");
            members(directory, 0, "failed initial save leaves neither member nor staging files");
            HologramBlueprintArtifact.writePair(file, original, png);
            HologramBlueprintArtifact.deletePair(file);
            members(directory, 0, "delete removes both complete members");
            HologramBlueprintArtifact.deletePair(file);
            members(directory, 0, "deleting absent pair is idempotent");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
                for (Path path : (Iterable<Path>) paths::iterator) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void pairEquals(Path file, byte[] json, byte[] png, String label) throws IOException {
        SimpleAssert.that(Arrays.equals(json, Files.readAllBytes(file)), label + " JSON");
        SimpleAssert
            .that(Arrays.equals(png, Files.readAllBytes(HologramBlueprintArtifact.previewPath(file))), label + " PNG");
        HologramBlueprintArtifact.readPair(file);
    }

    private static void pendingTransactions(byte[] png) throws Exception {
        Path directory = Files.createTempDirectory("gtmb-pending-artifact-regression-");
        Path file = directory.resolve("fixture.json"), preview = HologramBlueprintArtifact.previewPath(file);
        byte[] original = json("before_index"), replacement = json("pending_index"), newer = json("newer_index");
        try {
            HologramBlueprintArtifact.writePair(file, original, png);
            byte[] savedJson = Files.readAllBytes(file), savedPng = Files.readAllBytes(preview);
            HologramBlueprintArtifact.PairWrite pending = HologramBlueprintArtifact
                .writePairPending(file, replacement, png, () -> true);
            HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.readPair(file);
            SimpleAssert.eq(
                file.toAbsolutePath()
                    .normalize(),
                pending.path,
                "pending receipt exposes canonical path");
            SimpleAssert.eq(actual.jsonSha256, pending.jsonSha256, "pending receipt owns exact new JSON digest");
            SimpleAssert.eq(actual.pngSha256, pending.pngSha256, "pending receipt owns exact new PNG digest");
            members(directory, 4, "pending overwrite retains two bounded recovery files");
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                SimpleAssert.eq(
                    1L,
                    files.filter(
                        path -> path.getFileName()
                            .toString()
                            .endsWith(".png"))
                        .count(),
                    "pending overwrite creates no additional PNG artifact");
            }
            try {
                throw new IOException("simulated logical index commit failure after disk ACK");
            } catch (IOException failedIndex) {
                pending.rollback();
            }
            pairEquals(file, savedJson, savedPng, "failed logical index restores the exact previous pair");
            pending.rollback();
            expectIo(pending::commit, "rolled-back pending receipt cannot be committed later");
            members(directory, 2, "index failure rollback cleans its recovery files");

            HologramBlueprintArtifact.PairWrite accepted = HologramBlueprintArtifact
                .writePairPending(file, replacement, png, () -> true);
            accepted.commit();
            accepted.commit();
            expectIo(accepted::rollback, "accepted pending receipt cannot roll back later");
            SimpleAssert.that(
                Arrays.equals(replacement, HologramBlueprintArtifact.readPair(file).json),
                "logical success leaves accepted new pair");
            members(directory, 2, "logical success commit removes recovery files");

            HologramBlueprintArtifact.PairWrite stale = HologramBlueprintArtifact
                .writePairPending(file, original, png, () -> true);
            HologramBlueprintArtifact.writePair(file, newer, png);
            byte[] newerPng = Files.readAllBytes(preview);
            expectIo(stale::rollback, "out-of-order failure cannot overwrite newer independent save");
            pairEquals(file, newer, newerPng, "stale receipt preserves newer independent pair");
            stale.commit();
            members(directory, 2, "closing stale receipt removes only its own recovery files");

            HologramBlueprintArtifact.PairWrite sameBytes = HologramBlueprintArtifact
                .writePairPending(file, replacement, png, () -> true);
            HologramBlueprintArtifact.writePair(file, replacement, png);
            expectIo(sameBytes::rollback, "newer save owns pair even when exact bytes match older receipt");
            SimpleAssert.that(
                Arrays.equals(replacement, HologramBlueprintArtifact.readPair(file).json),
                "ownership guard retains same-byte newer independent save");
            sameBytes.commit();

            HologramBlueprintArtifact.PairWrite first = HologramBlueprintArtifact
                .writePairPending(file, original, png, () -> true);
            HologramBlueprintArtifact.PairWrite second = HologramBlueprintArtifact
                .writePairPending(file, newer, png, () -> true);
            expectIo(first::rollback, "older nested receipt cannot roll back current pending pair");
            second.rollback();
            SimpleAssert.that(
                Arrays.equals(original, HologramBlueprintArtifact.readPair(file).json),
                "newest pending rollback restores previous pending pair");
            first.rollback();
            SimpleAssert.that(
                Arrays.equals(replacement, HologramBlueprintArtifact.readPair(file).json),
                "previous pending owner can then restore its own exact previous pair");
            HologramBlueprintArtifact.writePair(file, newer, png);
            first.rollback();
            SimpleAssert.that(
                Arrays.equals(newer, HologramBlueprintArtifact.readPair(file).json),
                "duplicate completed rollback cannot overwrite a later save");
            members(directory, 2, "nested pending rollbacks clean all recovery files");

            HologramBlueprintArtifact.PairWrite changed = HologramBlueprintArtifact
                .writePairPending(file, replacement, png, () -> true);
            byte[] pendingJson = Files.readAllBytes(file), pendingPng = Files.readAllBytes(preview);
            Files.write(file, original);
            expectIo(changed::rollback, "changed actual JSON source digest rejects rollback");
            SimpleAssert.that(
                Arrays.equals(original, Files.readAllBytes(file)),
                "changed actual pair is never overwritten by failed receipt validation");
            members(directory, 4, "failed rollback validation retains exact recovery files");
            Files.write(file, pendingJson);
            byte[] alteredPreview = insert(pendingPng, "tEXt", new byte[] { 'x', 0, 'y' });
            Files.write(preview, alteredPreview);
            expectIo(changed::rollback, "valid actual preview with a different PNG digest rejects rollback");
            SimpleAssert.that(
                Arrays.equals(alteredPreview, Files.readAllBytes(preview)),
                "receipt digest validation never overwrites a changed valid preview");
            Files.write(preview, pendingPng);
            changed.rollback();
            pairEquals(file, newer, newerPng, "matching actual pair allows exact pending rollback");
            expectIo(
                () -> HologramBlueprintArtifact.writePairPending(
                    file,
                    replacement,
                    insert(png, "tEXt", text("malformed-source-hash")),
                    () -> true),
                "pending ownership rejects malformed source hash before disk changes");
            pairEquals(file, newer, newerPng, "malformed pending source leaves existing artifact untouched");

            for (int cutoff = 0; cutoff < 5; cutoff++) {
                final int limit = cutoff;
                AtomicInteger checks = new AtomicInteger();
                expectIo(
                    () -> HologramBlueprintArtifact
                        .writePairPending(file, replacement, png, () -> checks.incrementAndGet() <= limit),
                    "pending source fence cancels checkpoint " + limit);
                pairEquals(file, newer, newerPng, "pending source fence retains exact old pair at checkpoint " + limit);
            }
            members(directory, 2, "failed pending writes retain no successful receipt or staging files");
            HologramBlueprintArtifact.deletePair(file);
            HologramBlueprintArtifact.PairWrite created = HologramBlueprintArtifact
                .writePairPending(file, replacement, png, () -> true);
            created.rollback();
            created.rollback();
            members(directory, 0, "new-file logical failure removes both newly created members");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
                for (Path path : (Iterable<Path>) paths::iterator) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void members(Path directory, long expected, String label) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            SimpleAssert.eq(expected, files.count(), label);
        }
    }

    private static byte[] text(String digest) {
        return (HologramBlueprintArtifact.SOURCE_KEY + "\0" + digest).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] wire(byte[] json, byte[] png) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(new byte[] { 'G', 'T', 'M', 'B', 'A', 'R', 'T', 0 });
            out.writeInt(1);
            out.writeInt(640);
            out.writeInt(640);
            out.writeInt(json.length);
            out.writeInt(png.length);
            out.write(
                MessageDigest.getInstance("SHA-256")
                    .digest(json));
            out.write(
                MessageDigest.getInstance("SHA-256")
                    .digest(png));
            out.write(json);
            out.write(png);
        }
        return bytes.toByteArray();
    }

    private static byte[] rawPng(byte[] raw, int color, int depth, byte[] palette) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(SIGNATURE);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(header);
        out.writeInt(640);
        out.writeInt(640);
        out.write(new byte[] { (byte) depth, (byte) color, 0, 0, 0 });
        chunk(bytes, "IHDR", header.toByteArray());
        if (palette != null) chunk(bytes, "PLTE", palette);
        chunk(bytes, "IDAT", deflate(raw));
        chunk(bytes, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
            output.write(raw);
        }
        return bytes.toByteArray();
    }

    private static byte[] idat(byte[] png) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int offset = 8; offset < png.length;) {
            int length = integer(png, offset);
            if ("IDAT".equals(type(png, offset))) bytes.write(png, offset + 8, length);
            offset += length + 12;
        }
        return bytes.toByteArray();
    }

    private static byte[] chunkData(byte[] png, String type) {
        for (int offset = 8; offset < png.length;) {
            int length = integer(png, offset);
            if (type.equals(type(png, offset))) return Arrays.copyOfRange(png, offset + 8, offset + 8 + length);
            offset += length + 12;
        }
        throw new AssertionError("missing fixture chunk " + type);
    }

    private static byte[] replace(byte[] png, String type, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(SIGNATURE);
        boolean written = false;
        for (int offset = 8; offset < png.length;) {
            int length = integer(png, offset);
            if (type.equals(type(png, offset))) {
                if (!written && data != null) chunk(bytes, type, data);
                written = true;
            } else bytes.write(png, offset, length + 12);
            offset += length + 12;
        }
        return bytes.toByteArray();
    }

    private static byte[] insert(byte[] png, String type, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(SIGNATURE);
        boolean inserted = false;
        for (int offset = 8; offset < png.length;) {
            int length = integer(png, offset);
            if (!inserted && "IDAT".equals(type(png, offset))) {
                chunk(bytes, type, data);
                inserted = true;
            }
            bytes.write(png, offset, length + 12);
            offset += length + 12;
        }
        return bytes.toByteArray();
    }

    private static String type(byte[] png, int offset) {
        return new String(png, offset + 4, 4, StandardCharsets.ISO_8859_1);
    }

    private static int integer(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
            | (bytes[offset + 2] & 255) << 8
            | bytes[offset + 3] & 255;
    }

    private static void chunk(ByteArrayOutputStream bytes, String type, byte[] data) throws IOException {
        DataOutputStream output = new DataOutputStream(bytes);
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        output.writeInt(data.length);
        output.write(name);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        output.writeInt((int) crc.getValue());
    }

    private interface IoAction {

        void run() throws Exception;
    }

    private static void expectIo(IoAction action, String label) throws Exception {
        boolean rejected = false;
        try {
            action.run();
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, label);
    }
}
