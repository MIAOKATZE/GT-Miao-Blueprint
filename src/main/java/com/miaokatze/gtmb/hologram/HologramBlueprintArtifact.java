package com.miaokatze.gtmb.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** A bounded JSON/PNG artifact. Disk files retain their normal, independently usable formats. */
public final class HologramBlueprintArtifact {

    public static final int VERSION = 1;
    public static final int WIDTH = 640;
    public static final int HEIGHT = 640;
    public static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PNG_BYTES = 4 * 1024 * 1024;
    public static final int HEADER_BYTES = 92;
    public static final int MAX_BYTES = HEADER_BYTES + MAX_JSON_BYTES + MAX_PNG_BYTES;
    public static final String SOURCE_KEY = "gtmb.sourceJsonSha256";

    private static final int MAX_PNG_CHUNKS = 4096;
    private static final byte[] MAGIC = { 'G', 'T', 'M', 'B', 'A', 'R', 'T', 0 };
    private static final byte[] PNG_SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
    private static final int[] PASS_X = { 0, 4, 0, 2, 0, 1, 0 };
    private static final int[] PASS_Y = { 0, 0, 4, 0, 2, 0, 1 };
    private static final int[] PASS_DX = { 8, 8, 4, 4, 2, 2, 1 };
    private static final int[] PASS_DY = { 8, 8, 8, 4, 4, 2, 2 };
    private static final Map<Path, PairWrite> PENDING_WRITES = new HashMap<>();

    private HologramBlueprintArtifact() {}

    public static final class Artifact {

        public final byte[] json;
        public final byte[] png;
        public final int width = WIDTH;
        public final int height = HEIGHT;
        public final String jsonSha256;
        public final String pngSha256;

        private Artifact(byte[] json, byte[] png) {
            this.json = json;
            this.png = png;
            jsonSha256 = hash(json);
            pngSha256 = hash(png);
        }
    }

    private enum Outcome {
        OPEN,
        COMMITTED,
        ROLLED_BACK
    }

    /** A completed disk write whose bounded recovery files remain until the logical index accepts it. */
    public static final class PairWrite {

        public final Path path;
        public final String jsonSha256;
        public final String pngSha256;

        private final Path oldJson, oldPng;
        private final PairWrite previousOwner;
        private volatile Outcome outcome = Outcome.OPEN;

        private PairWrite(Path path, byte[] json, byte[] png, Path oldJson, Path oldPng, PairWrite previousOwner) {
            this.path = path;
            jsonSha256 = hash(json);
            pngSha256 = hash(png);
            this.oldJson = oldJson;
            this.oldPng = oldPng;
            this.previousOwner = previousOwner;
        }

        /** Finalizes acceptance without changing either artifact member. Repeated acceptance is harmless. */
        public synchronized void commit() throws IOException {
            synchronized (HologramBlueprintArtifact.class) {
                if (outcome == Outcome.ROLLED_BACK) throw new IOException("Pending blueprint write was rolled back");
                outcome = Outcome.COMMITTED;
                if (PENDING_WRITES.get(path) == this) PENDING_WRITES.remove(path);
                removeBackups();
            }
        }

        /** Restores the exact previous members only while this receipt still owns the actual new pair. */
        public synchronized void rollback() throws IOException {
            synchronized (HologramBlueprintArtifact.class) {
                if (outcome == Outcome.COMMITTED) throw new IOException("Pending blueprint write was committed");
                if (outcome == Outcome.ROLLED_BACK) {
                    removeBackups();
                    return;
                }
                if (PENDING_WRITES.get(path) != this)
                    throw new IOException("Pending blueprint write no longer owns the artifact pair");
                Artifact actual;
                try {
                    actual = readPair(path);
                } catch (IOException | RuntimeException failure) {
                    throw new IOException("Pending blueprint artifact pair changed before rollback", failure);
                }
                if (!jsonSha256.equals(actual.jsonSha256) || !pngSha256.equals(actual.pngSha256))
                    throw new IOException("Pending blueprint artifact digests changed before rollback");
                IOException failure = new IOException("Pending blueprint artifact rollback failed");
                boolean restored = restoreKeepingBackup(path, oldJson, MAX_JSON_BYTES, failure)
                    & restoreKeepingBackup(previewPath(path), oldPng, MAX_PNG_BYTES, failure);
                if (!restored) throw failure;
                outcome = Outcome.ROLLED_BACK;
                if (previousOwner != null && previousOwner.outcome == Outcome.OPEN)
                    PENDING_WRITES.put(path, previousOwner);
                else PENDING_WRITES.remove(path);
                removeBackups();
            }
        }

        private void removeBackups() throws IOException {
            IOException failure = new IOException("Pending blueprint recovery files could not be removed");
            boolean removed = removeRecovery(oldJson, failure) & removeRecovery(oldPng, failure);
            if (!removed) throw failure;
        }
    }

    /** Rebinding changes only the source-digest text chunk; compressed pixel bytes are untouched. */
    public static byte[] bindPreview(byte[] json, byte[] png) throws IOException {
        jsonBounds(json);
        json = json.clone();
        if (png == null || png.length > MAX_PNG_BYTES) throw new IOException("Invalid blueprint PNG size");
        png = png.clone();
        Png info = inspectPng(png);
        String source = hash(json);
        if (source.equals(info.source)) return png.clone();
        if (info.source == null && info.chunks.size() == MAX_PNG_CHUNKS)
            throw new IOException("Bound PNG has too many chunks");
        byte[] text = (SOURCE_KEY + "\0" + source).getBytes(StandardCharsets.ISO_8859_1);
        int size = png.length + text.length + 12;
        for (Chunk chunk : info.chunks) if (chunk.source) size -= chunk.length + 12;
        if (size > MAX_PNG_BYTES) throw new IOException("Bound blueprint PNG exceeds 4 MiB");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
        bytes.write(PNG_SIGNATURE);
        boolean inserted = false;
        for (Chunk chunk : info.chunks) {
            if (chunk.source) continue;
            if (!inserted && "IDAT".equals(chunk.type)) {
                writeChunk(bytes, "tEXt", text);
                inserted = true;
            }
            bytes.write(png, chunk.offset, chunk.length + 12);
        }
        return bytes.toByteArray();
    }

    /** The descriptor binds the exact JSON bytes and the bound PNG, including its dimensions. */
    public static byte[] encode(byte[] json, byte[] png) throws IOException {
        jsonBounds(json);
        json = json.clone();
        byte[] bound = bindPreview(json, png);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_BYTES + json.length + bound.length);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(WIDTH);
            output.writeInt(HEIGHT);
            output.writeInt(json.length);
            output.writeInt(bound.length);
            output.write(digest(json));
            output.write(digest(bound));
            output.write(json);
            output.write(bound);
        }
        return bytes.toByteArray();
    }

    public static Artifact decode(byte[] bundle) throws IOException {
        if (bundle == null || bundle.length < HEADER_BYTES || bundle.length > MAX_BYTES)
            throw new IOException("Invalid blueprint artifact size");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bundle))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(MAGIC, magic) || input.readInt() != VERSION)
                throw new IOException("Unsupported blueprint artifact format/version");
            if (input.readInt() != WIDTH || input.readInt() != HEIGHT)
                throw new IOException("Invalid blueprint artifact preview dimensions");
            int jsonLength = input.readInt(), pngLength = input.readInt();
            if (jsonLength <= 0 || jsonLength > MAX_JSON_BYTES
                || pngLength <= 0
                || pngLength > MAX_PNG_BYTES
                || (long) HEADER_BYTES + jsonLength + pngLength != bundle.length)
                throw new IOException("Invalid blueprint artifact lengths");
            byte[] jsonDigest = new byte[32], pngDigest = new byte[32];
            input.readFully(jsonDigest);
            input.readFully(pngDigest);
            byte[] json = new byte[jsonLength], png = new byte[pngLength];
            input.readFully(json);
            input.readFully(png);
            if (!MessageDigest.isEqual(jsonDigest, digest(json)) || !MessageDigest.isEqual(pngDigest, digest(png)))
                throw new IOException("Blueprint artifact digest mismatch");
            requireSource(json, inspectPng(png));
            return new Artifact(json, png);
        }
    }

    /** Validate PNG framing, CRCs, bounded zlib expansion, scanlines and a complete PNG image decode. */
    public static void validatePng(byte[] png) throws IOException {
        inspectPng(png);
    }

    public static Path previewPath(Path jsonPath) throws IOException {
        if (jsonPath == null || jsonPath.getFileName() == null) throw new IOException("Missing blueprint JSON path");
        String name = jsonPath.getFileName()
            .toString();
        if (name.length() <= 5 || !name.toLowerCase(Locale.ROOT)
            .endsWith(".json")) throw new IOException("Blueprint artifact path must end in .json");
        return jsonPath.resolveSibling(name.substring(0, name.length() - 5) + ".png");
    }

    public static synchronized Artifact readPair(Path jsonPath) throws IOException {
        Path pngPath = previewPath(jsonPath);
        byte[] json = readBounded(jsonPath, MAX_JSON_BYTES);
        byte[] png = readBounded(pngPath, MAX_PNG_BYTES);
        jsonBounds(json);
        requireSource(json, inspectPng(png));
        return new Artifact(json, png);
    }

    public static synchronized void writePair(Path jsonPath, byte[] json, byte[] png) throws IOException {
        writePair(jsonPath, json, png, () -> true, HologramBlueprintArtifact::atomicReplace, false);
    }

    /** A revision fence is checked throughout commit; cancellation restores the previous pair. */
    public static synchronized void writePair(Path jsonPath, byte[] json, byte[] png, BooleanSupplier stillCurrent)
        throws IOException {
        writePair(jsonPath, json, png, stillCurrent, HologramBlueprintArtifact::atomicReplace, false);
    }

    /** Keeps bounded old-member backups until the caller commits or rolls back the logical index outcome. */
    public static synchronized PairWrite writePairPending(Path jsonPath, byte[] json, byte[] png,
        BooleanSupplier stillCurrent) throws IOException {
        return writePair(jsonPath, json, png, stillCurrent, HologramBlueprintArtifact::atomicReplace, true);
    }

    /** Package-private move injection exercises rollback after one member has already been replaced. */
    interface MoveOperation {

        void move(Path from, Path to) throws IOException;
    }

    static synchronized void writePair(Path jsonPath, byte[] json, byte[] png, MoveOperation move) throws IOException {
        writePair(jsonPath, json, png, () -> true, move, false);
    }

    private static PairWrite writePair(Path jsonPath, byte[] json, byte[] png, BooleanSupplier stillCurrent,
        MoveOperation move, boolean pending) throws IOException {
        requireCurrent(stillCurrent);
        jsonBounds(json);
        json = json.clone();
        byte[] bound = bindPreview(json, png);
        Path jsonFile = absolute(jsonPath), pngFile = previewPath(jsonFile), parent = jsonFile.getParent();
        Files.createDirectories(parent);
        regularOrAbsent(jsonFile);
        regularOrAbsent(pngFile);
        Path oldJson = null, oldPng = null, newJson = null, newPng = null;
        boolean touched = false, recovered = true, retainBackups = false;
        PairWrite receipt = null;
        try {
            oldJson = backup(jsonFile, MAX_JSON_BYTES);
            oldPng = backup(pngFile, MAX_PNG_BYTES);
            requireCurrent(stillCurrent);
            newJson = stage(parent, json);
            newPng = stage(parent, bound);
            requireCurrent(stillCurrent);
            touched = true;
            move.move(newPng, pngFile);
            requireCurrent(stillCurrent);
            move.move(newJson, jsonFile);
            requireCurrent(stillCurrent);
            if (pending) {
                receipt = new PairWrite(jsonFile, json, bound, oldJson, oldPng, PENDING_WRITES.get(jsonFile));
                PENDING_WRITES.put(jsonFile, receipt);
                retainBackups = true;
            } else PENDING_WRITES.remove(jsonFile);
        } catch (IOException | RuntimeException failure) {
            if (touched) recovered = restore(jsonFile, oldJson, failure) & restore(pngFile, oldPng, failure);
            throw failure;
        } finally {
            cleanup(newJson);
            cleanup(newPng);
            // Preserve recovery files if the filesystem itself prevents rollback.
            if (recovered && !retainBackups) {
                cleanup(oldJson);
                cleanup(oldPng);
            }
        }
        return receipt;
    }

    public static synchronized void deletePair(Path jsonPath) throws IOException {
        deletePair(jsonPath, HologramBlueprintArtifact::atomicReplace);
    }

    static synchronized void deletePair(Path jsonPath, MoveOperation move) throws IOException {
        Path jsonFile = absolute(jsonPath), pngFile = previewPath(jsonFile);
        regularOrAbsent(jsonFile);
        regularOrAbsent(pngFile);
        Path oldJson = null, oldPng = null;
        boolean movedJson = false, movedPng = false, recovered = true;
        try {
            if (Files.exists(jsonFile, LinkOption.NOFOLLOW_LINKS)) {
                oldJson = Files.createTempFile(jsonFile.getParent(), "gtmb-artifact-", ".tmp");
                move.move(jsonFile, oldJson);
                movedJson = true;
            }
            if (Files.exists(pngFile, LinkOption.NOFOLLOW_LINKS)) {
                oldPng = Files.createTempFile(pngFile.getParent(), "gtmb-artifact-", ".tmp");
                move.move(pngFile, oldPng);
                movedPng = true;
            }
            PENDING_WRITES.remove(jsonFile);
        } catch (IOException | RuntimeException failure) {
            if (movedJson) recovered &= restore(jsonFile, oldJson, failure);
            if (movedPng) recovered &= restore(pngFile, oldPng, failure);
            throw failure;
        } finally {
            if (recovered) {
                cleanup(oldJson);
                cleanup(oldPng);
            }
        }
    }

    public static String hash(byte[] bytes) {
        byte[] digest = digest(bytes);
        char[] result = new char[digest.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            result[2 * i] = digits[(digest[i] & 255) >>> 4];
            result[2 * i + 1] = digits[digest[i] & 15];
        }
        return new String(result);
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void jsonBounds(byte[] json) throws IOException {
        if (json == null || json.length == 0 || json.length > MAX_JSON_BYTES)
            throw new IOException("Blueprint JSON must contain 1 byte through 8 MiB");
    }

    private static void requireCurrent(BooleanSupplier stillCurrent) throws IOException {
        if (stillCurrent == null || !stillCurrent.getAsBoolean())
            throw new IOException("Blueprint artifact operation is no longer current");
    }

    private static void requireSource(byte[] json, Png png) throws IOException {
        if (!hash(json).equals(png.source)) throw new IOException("Blueprint PNG is missing its matching JSON digest");
    }

    private static final class Chunk {

        final int offset, length;
        final String type;
        boolean source;

        Chunk(int offset, int length, String type) {
            this.offset = offset;
            this.length = length;
            this.type = type;
        }
    }

    private static final class Png {

        final List<Chunk> chunks = new ArrayList<>();
        int depth, color, interlace, palette;
        String source;
    }

    private static Png inspectPng(byte[] png) throws IOException {
        if (png == null || png.length < 45 || png.length > MAX_PNG_BYTES)
            throw new IOException("Invalid blueprint PNG size");
        for (int i = 0; i < PNG_SIGNATURE.length; i++)
            if (png[i] != PNG_SIGNATURE[i]) throw new IOException("Blueprint preview must be a PNG");
        Png result = new Png();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Set<String> unique = new HashSet<>();
        boolean idat = false, endedIdat = false, end = false;
        int offset = PNG_SIGNATURE.length;
        while (offset < png.length) {
            if (png.length - offset < 12) throw new IOException("Truncated PNG chunk");
            int length = integer(png, offset);
            if (length < 0 || length > png.length - offset - 12) throw new IOException("Invalid PNG chunk length");
            int data = offset + 8;
            for (int i = offset + 4; i < data; i++) {
                int letter = png[i] & 255;
                if (!(letter >= 'A' && letter <= 'Z' || letter >= 'a' && letter <= 'z'))
                    throw new IOException("Invalid PNG chunk type");
            }
            if ((png[offset + 6] & 32) != 0) throw new IOException("Invalid PNG reserved chunk bit");
            CRC32 crc = new CRC32();
            crc.update(png, offset + 4, length + 4);
            if (crc.getValue() != (integer(png, data + length) & 0xffffffffL))
                throw new IOException("PNG chunk CRC mismatch");
            String type = new String(png, offset + 4, 4, StandardCharsets.ISO_8859_1);
            Chunk chunk = new Chunk(offset, length, type);
            result.chunks.add(chunk);
            if (result.chunks.size() > MAX_PNG_CHUNKS) throw new IOException("PNG has too many chunks");
            if (result.chunks.size() == 1 && !"IHDR".equals(type)) throw new IOException("PNG is missing initial IHDR");
            if (idat && !"IDAT".equals(type)) endedIdat = true;
            switch (type) {
                case "IHDR":
                    if (result.chunks.size() != 1 || length != 13
                        || integer(png, data) != WIDTH
                        || integer(png, data + 4) != HEIGHT)
                        throw new IOException("Blueprint PNG must be exactly 640 by 640");
                    result.depth = png[data + 8] & 255;
                    result.color = png[data + 9] & 255;
                    result.interlace = png[data + 12] & 255;
                    if (!validDepth(result.color, result.depth) || png[data + 10] != 0
                        || png[data + 11] != 0
                        || result.interlace > 1) throw new IOException("Unsupported PNG image header");
                    break;
                case "PLTE":
                    if (idat || result.palette != 0
                        || result.color == 0
                        || result.color == 4
                        || unique.contains("tRNS")
                        || unique.contains("bKGD")
                        || unique.contains("hIST")
                        || length == 0
                        || length > 768
                        || length % 3 != 0) throw new IOException("Invalid PNG palette");
                    result.palette = length / 3;
                    if (result.color == 3 && result.palette > 1 << result.depth)
                        throw new IOException("PNG palette exceeds bit depth");
                    break;
                case "IDAT":
                    if (endedIdat || result.color == 3 && result.palette == 0)
                        throw new IOException("Invalid PNG image data order");
                    idat = true;
                    compressed.write(png, data, length);
                    break;
                case "IEND":
                    if (length != 0 || !idat || offset + 12 != png.length)
                        throw new IOException("Invalid PNG end or appended data");
                    end = true;
                    break;
                case "tEXt":
                    int separator = textKeyword(png, data, length);
                    for (int i = separator + 1; i < data + length; i++)
                        if (png[i] == 0) throw new IOException("Invalid PNG text value");
                    if (SOURCE_KEY.equals(new String(png, data, separator - data, StandardCharsets.ISO_8859_1))) {
                        if (result.source != null) throw new IOException("Duplicate PNG source digest");
                        result.source = new String(
                            png,
                            separator + 1,
                            data + length - separator - 1,
                            StandardCharsets.ISO_8859_1);
                        if (!result.source.matches("[a-f0-9]{64}")) throw new IOException("Invalid PNG source digest");
                        chunk.source = true;
                    }
                    break;
                default:
                    // Compressed metadata and unknown chunks are deliberately outside this preview format.
                    ancillary(png, data, length, type, result, idat, unique);
                    break;
            }
            offset += length + 12;
            if (end) break;
        }
        if (!end || compressed.size() == 0) throw new IOException("Incomplete PNG image");
        inflate(result, compressed.toByteArray());
        decodeImage(png);
        return result;
    }

    private static boolean validDepth(int color, int depth) {
        if (color == 0) return depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
        if (color == 3) return depth == 1 || depth == 2 || depth == 4 || depth == 8;
        return (color == 2 || color == 4 || color == 6) && (depth == 8 || depth == 16);
    }

    private static int channels(int color) {
        return color == 2 ? 3 : color == 4 ? 2 : color == 6 ? 4 : 1;
    }

    private static int textKeyword(byte[] png, int data, int length) throws IOException {
        int separator = data;
        while (separator < data + length && png[separator] != 0) {
            int value = png[separator] & 255;
            if (!(value >= 32 && value <= 126 || value >= 161)
                || value == 32 && (separator == data || png[separator - 1] == 32))
                throw new IOException("Invalid PNG text keyword");
            separator++;
        }
        if (separator == data || separator == data + length || separator - data > 79 || png[separator - 1] == 32)
            throw new IOException("Invalid PNG text keyword length");
        return separator;
    }

    private static void ancillary(byte[] png, int data, int length, String type, Png info, boolean idat,
        Set<String> unique) throws IOException {
        if (!unique.add(type) || idat && !"tIME".equals(type)) throw new IOException("Invalid PNG metadata order");
        boolean valid;
        switch (type) {
            case "gAMA":
                valid = length == 4 && integer(png, data) != 0;
                break;
            case "cHRM":
                valid = length == 32;
                break;
            case "sRGB":
                valid = length == 1 && (png[data] & 255) <= 3;
                break;
            case "pHYs":
                valid = length == 9 && (png[data + 8] & 255) <= 1;
                break;
            case "tIME":
                valid = length == 7 && (png[data + 2] & 255) >= 1
                    && (png[data + 2] & 255) <= 12
                    && (png[data + 3] & 255) >= 1
                    && (png[data + 3] & 255) <= 31
                    && (png[data + 4] & 255) <= 23
                    && (png[data + 5] & 255) <= 59
                    && (png[data + 6] & 255) <= 60;
                break;
            case "tRNS":
                valid = info.color == 0 && length == 2 || info.color == 2 && length == 6
                    || info.color == 3 && info.palette > 0 && length > 0 && length <= info.palette;
                if (valid && info.color != 3) valid = samplesWithinDepth(png, data, length, info.depth);
                break;
            case "bKGD":
                valid = info.color == 3 ? length == 1 && info.palette > 0 && (png[data] & 255) < info.palette
                    : length == (info.color == 0 || info.color == 4 ? 2 : 6);
                if (valid && info.color != 3) valid = samplesWithinDepth(png, data, length, info.depth);
                break;
            case "hIST":
                valid = info.palette > 0 && length == 2 * info.palette;
                break;
            case "sBIT":
                valid = length == (info.color == 3 ? 3 : channels(info.color));
                for (int i = 0; valid && i < length; i++)
                    valid = (png[data + i] & 255) > 0 && (png[data + i] & 255) <= (info.color == 3 ? 8 : info.depth);
                break;
            default:
                throw new IOException("Unsupported PNG preview chunk: " + type);
        }
        if (!valid) throw new IOException("Invalid PNG metadata: " + type);
        if (("gAMA".equals(type) || "cHRM".equals(type) || "sRGB".equals(type) || "sBIT".equals(type))
            && info.palette != 0) throw new IOException("PNG color metadata follows palette");
    }

    private static boolean samplesWithinDepth(byte[] png, int data, int length, int depth) {
        int maximum = (1 << depth) - 1;
        for (int i = 0; i < length; i += 2) {
            int sample = (png[data + i] & 255) << 8 | png[data + i + 1] & 255;
            if (sample > maximum) return false;
        }
        return true;
    }

    private static void inflate(Png info, byte[] compressed) throws IOException {
        int passes = info.interlace == 0 ? 1 : 7;
        int bits = channels(info.color) * info.depth, expected = 0;
        for (int pass = 0; pass < passes; pass++) {
            int width = passSize(
                WIDTH,
                info.interlace == 0 ? 0 : PASS_X[pass],
                info.interlace == 0 ? 1 : PASS_DX[pass]);
            int height = passSize(
                HEIGHT,
                info.interlace == 0 ? 0 : PASS_Y[pass],
                info.interlace == 0 ? 1 : PASS_DY[pass]);
            if (width > 0 && height > 0) expected += height * (1 + (width * bits + 7) / 8);
        }
        byte[] raw = new byte[expected];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int offset = 0;
            while (offset < raw.length) {
                int count = inflater.inflate(raw, offset, raw.length - offset);
                if (count == 0) throw new IOException("Truncated or invalid PNG zlib stream");
                offset += count;
            }
            if (inflater.inflate(new byte[1]) != 0 || !inflater.finished() || inflater.getRemaining() != 0)
                throw new IOException("PNG zlib stream exceeds image or has appended data");
        } catch (DataFormatException invalid) {
            throw new IOException("Invalid PNG zlib stream", invalid);
        } finally {
            inflater.end();
        }
        int offset = 0, pixelBytes = Math.max(1, (bits + 7) / 8);
        for (int pass = 0; pass < passes; pass++) {
            int width = passSize(
                WIDTH,
                info.interlace == 0 ? 0 : PASS_X[pass],
                info.interlace == 0 ? 1 : PASS_DX[pass]);
            int height = passSize(
                HEIGHT,
                info.interlace == 0 ? 0 : PASS_Y[pass],
                info.interlace == 0 ? 1 : PASS_DY[pass]);
            int rowBytes = (width * bits + 7) / 8, previous = -1;
            if (width == 0 || height == 0) continue;
            for (int row = 0; row < height; row++) {
                int filter = raw[offset++] & 255;
                if (filter > 4) throw new IOException("Invalid PNG scanline filter");
                for (int x = 0; x < rowBytes; x++) {
                    int left = x < pixelBytes ? 0 : raw[offset + x - pixelBytes] & 255;
                    int up = previous < 0 ? 0 : raw[previous + x] & 255;
                    int upperLeft = previous < 0 || x < pixelBytes ? 0 : raw[previous + x - pixelBytes] & 255;
                    int predictor = filter == 1 ? left
                        : filter == 2 ? up
                            : filter == 3 ? (left + up) / 2 : filter == 4 ? paeth(left, up, upperLeft) : 0;
                    raw[offset + x] = (byte) ((raw[offset + x] & 255) + predictor);
                }
                if (info.color == 3) for (int x = 0; x < width; x++) {
                    int bit = x * info.depth;
                    int index = (raw[offset + bit / 8] & 255) >>> (8 - info.depth - bit % 8) & (1 << info.depth) - 1;
                    if (index >= info.palette) throw new IOException("PNG pixel refers to missing palette entry");
                }
                previous = offset;
                offset += rowBytes;
            }
        }
    }

    private static int passSize(int size, int start, int stride) {
        return size <= start ? 0 : (size - start + stride - 1) / stride;
    }

    private static int paeth(int left, int up, int upperLeft) {
        int p = left + up - upperLeft, a = Math.abs(p - left), b = Math.abs(p - up), c = Math.abs(p - upperLeft);
        return a <= b && a <= c ? left : b <= c ? up : upperLeft;
    }

    private static void decodeImage(byte[] png) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        if (!readers.hasNext()) throw new IOException("No PNG decoder available");
        ImageReader reader = readers.next();
        final boolean[] warning = { false };
        reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            reader.setInput(input, true, true);
            if (reader.getWidth(0) != WIDTH || reader.getHeight(0) != HEIGHT)
                throw new IOException("PNG decoder dimensions differ from descriptor");
            BufferedImage image = reader.read(0);
            if (warning[0] || image == null || image.getWidth() != WIDTH || image.getHeight() != HEIGHT)
                throw new IOException("Incomplete or invalid PNG image");
            image.flush();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid PNG image", invalid);
        } finally {
            reader.dispose();
        }
    }

    private static int integer(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
            | (bytes[offset + 2] & 255) << 8
            | bytes[offset + 3] & 255;
    }

    private static void writeChunk(ByteArrayOutputStream bytes, String type, byte[] data) throws IOException {
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

    private static Path absolute(Path path) throws IOException {
        previewPath(path);
        return path.toAbsolutePath()
            .normalize();
    }

    private static void regularOrAbsent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Blueprint artifact member is not a regular file: " + path.getFileName());
    }

    private static byte[] readBounded(Path path, int limit) throws IOException {
        regularOrAbsent(path);
        if (Files.size(path) > limit) throw new IOException("Blueprint artifact member exceeds byte limit");
        try (InputStream input = Files.newInputStream(path)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > limit - bytes.size()) throw new IOException("Blueprint artifact member exceeds byte limit");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static Path stage(Path parent, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(parent, "gtmb-artifact-", ".tmp");
        try {
            Files.write(temp, bytes);
            return temp;
        } catch (IOException | RuntimeException failure) {
            cleanup(temp);
            throw failure;
        }
    }

    private static Path backup(Path path, int limit) throws IOException {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? stage(path.getParent(), readBounded(path, limit)) : null;
    }

    private static void atomicReplace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean restore(Path target, Path backup, Throwable original) {
        try {
            if (backup == null) Files.deleteIfExists(target);
            else atomicReplace(backup, target);
            return true;
        } catch (IOException | RuntimeException failure) {
            original.addSuppressed(failure);
            return false;
        }
    }

    private static boolean restoreKeepingBackup(Path target, Path backup, int limit, Throwable original) {
        Path staged = null;
        try {
            regularOrAbsent(target);
            if (backup == null) Files.deleteIfExists(target);
            else {
                staged = stage(target.getParent(), readBounded(backup, limit));
                atomicReplace(staged, target);
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            original.addSuppressed(failure);
            return false;
        } finally {
            cleanup(staged);
        }
    }

    private static boolean removeRecovery(Path backup, Throwable original) {
        if (backup == null) return true;
        try {
            Files.deleteIfExists(backup);
            return true;
        } catch (IOException | RuntimeException failure) {
            original.addSuppressed(failure);
            return false;
        }
    }

    private static void cleanup(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // A committed pair is valid even if an unrelated temporary file cannot be removed.
        }
    }
}
