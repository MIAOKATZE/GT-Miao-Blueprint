// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.MAX_SLICE_CELLS;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Variant;

/** Local plain-v1 producer. Never owns a full operation list, sends a packet, or interprets transfer ACK as placement. */
public final class HologramProjectedLayerEncoder {

    /** Mirrors the server's intentionally narrow projected placement reach envelope, not a permission grant. */
    public static final int MAX_OFFSET = 256;
    // Fit the first placement batch to the server's per-tick inspection ceiling; later frames keep throughput.
    private static final int FIRST_SLICE_CELLS = 32;
    private static final int LEVELS = 4096, HASH_BYTES = HologramProjectedLayerWire.HASH_BYTES;

    public enum Plain {
        STONE("minecraft:stone"), COBBLESTONE("minecraft:cobblestone"), GLASS("minecraft:glass");

        private final String id;

        Plain(String id) {
            this.id = id;
        }

        public static Plain find(String id) {
            if (id == null) return null;
            return switch (id) {
                case "minecraft:stone" -> STONE;
                case "minecraft:cobblestone" -> COBBLESTONE;
                case "minecraft:glass" -> GLASS;
                default -> null;
            };
        }
    }

    /** Cursors traverse the existing immutable source, without collecting or sorting its cells into a second list. */
    public interface Source {

        Cursor open(boolean reverse) throws IOException;

        int cells();

        int count(Plain kind);
    }

    public interface Cursor {

        boolean next() throws IOException;

        int x();

        int y();

        int z();

        Plain kind();
    }

    /**
     * Only a proof about the exact local captured bytes, never a server admission. Integrate this while the existing
     * display reader owns both the immutable input and its decoded rows, and after its strict storage gate on use.
     */
    public static final class Evidence {

        private final byte[] exact;
        private final int[] counts;
        private final int cells;

        private Evidence(byte[] exact, int[] counts, int cells) {
            this.exact = exact;
            this.counts = counts;
            this.cells = cells;
        }

        public boolean sameCapture(byte[] bytes) {
            return exact == bytes;
        }

        public int cells() {
            return cells;
        }

        public int count(Plain kind) {
            return counts[kind.ordinal()];
        }
    }

    /** Piggyback the existing compact display pass. Every row ordinal must be observed exactly once or proof fails. */
    public static final class EvidenceBuilder {

        private final byte[] exact;
        private final HologramBlueprintCompactCodec.DisplayRows rows;
        private final Plain[] dictionary;
        private final int paletteKinds;
        private final int[] counts = new int[Plain.values().length];
        private int seen;
        private boolean invalid, finished;

        private EvidenceBuilder(byte[] exact, HologramBlueprintCompactCodec.DisplayRows rows, Plain[] dictionary,
            int paletteKinds) {
            this.exact = exact;
            this.rows = rows;
            this.dictionary = dictionary;
            this.paletteKinds = paletteKinds;
        }

        public void row(int index, int materialIndex) {
            if (invalid || finished) return;
            if (seen >= rows.cells() || index != seen || materialIndex < 0 || materialIndex >= dictionary.length
                || rows.materialIndex(index) != materialIndex || dictionary[materialIndex] == null) {
                invalid = true;
                return;
            }
            counts[dictionary[materialIndex].ordinal()]++;
            seen++;
        }

        public Evidence finish() {
            if (invalid || finished || seen == 0 || seen != rows.cells()) return null;
            finished = true;
            for (Plain kind : Plain.values())
                if ((paletteKinds & 1 << kind.ordinal()) != 0 && counts[kind.ordinal()] == 0) return null;
            try {
                // The display projection may recover from historical syntax. Construction evidence may not.
                HologramBlueprintPreviewBinding.validateStoredSyntax(exact);
            } catch (IOException invalid) {
                return null;
            }
            return new Evidence(exact, counts.clone(), seen);
        }
    }

    private HologramProjectedLayerEncoder() {}

    @Desugar
    public record Inspection(HologramBlueprintCompactCodec.DisplayRows rows, EvidenceBuilder builder) {}

    /** Replace the existing display decode at its call site; the proof never receives rows decoded from other bytes. */
    public static Inspection inspect(byte[] exact, boolean indexedPrivate) throws IOException {
        HologramBlueprintCompactCodec.DisplayRows rows = HologramBlueprintCompactCodec.displayGeometry(exact);
        return new Inspection(rows, indexedPrivate ? plainEvidence(exact, rows) : null);
    }

    /** Failure leaves preview/edit/save available; no proof exists until the same reader visits all ordinals. */
    private static EvidenceBuilder plainEvidence(byte[] exact, HologramBlueprintCompactCodec.DisplayRows rows) {
        if (!rows.compact() || rows.cells() == 0
            || rows.materials() == 0 || rows.materials() > HologramProjectedLayerWire.MAX_VARIANTS
            || !plainHeader(rows)) return null;
        Plain[] dictionary = new Plain[rows.materials()];
        boolean[] descriptiveOnly = new boolean[dictionary.length];
        int paletteKinds = descriptivePalette(rows, descriptiveOnly);
        if (paletteKinds < 0) return null;
        for (int i = 0; i < dictionary.length; i++) {
            if (descriptiveOnly[i]) continue; // A writer's origin-only item reference cannot describe a placed cell.
            dictionary[i] = descriptor(rows.material(i), false);
            if (dictionary[i] == null) return null;
        }
        return new EvidenceBuilder(exact, rows, dictionary, paletteKinds);
    }

    /** Ordinary saves also write one item-only origin declaration per used material, separate from geometry. */
    private static int descriptivePalette(HologramBlueprintCompactCodec.DisplayRows rows, boolean[] descriptiveOnly) {
        JsonElement value = rows.blueprint.get("palette");
        if (value == null || value.isJsonNull()) return 0;
        if (!value.isJsonArray() || value.getAsJsonArray().size() > Plain.values().length) return -1;
        int kinds = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            // The compact reader already validated each reference against this exact material dictionary.
            JsonArray reference = element.getAsJsonArray();
            int index = reference.get(0).getAsInt();
            JsonObject origin = reference.get(1).getAsJsonObject();
            Plain kind = descriptor(rows.material(index), true);
            if (kind == null || descriptiveOnly[index] || !only(origin, "x,y,z")
                || !integer(origin, "x", 0, false) || !integer(origin, "y", 0, false)
                || !integer(origin, "z", 0, false)) return -1;
            int mask = 1 << kind.ordinal();
            if ((kinds & mask) != 0) return -1;
            descriptiveOnly[index] = true;
            kinds |= mask;
        }
        return kinds;
    }

    private static boolean plainHeader(HologramBlueprintCompactCodec.DisplayRows rows) {
        if (!only(rows.root, "schema,encoding,blueprint,materials,nbt,previewBinding")
            || !integer(rows.root, "schema", 2, true) || !integer(rows.blueprint, "schema", 2, true)) return false;
        JsonElement nbt = rows.root.get("nbt");
        if (nbt == null || !nbt.isJsonArray() || nbt.getAsJsonArray().size() > 1) return false;
        for (JsonElement payload : nbt.getAsJsonArray())
            if (!string(payload) || !payload.getAsString().isEmpty()) return false;
        JsonObject b = rows.blueprint;
        if (!only(b, "schema,id,name,authorName,authorUuid,createdAt,nativeBlueprint,sourceMod,sourceClass,"
            + "modVersions,prompt,retainNbt,retainCovers,fullyVoucherCovered,aePolicyVersion,deidentificationVersion,"
            + "deidentificationLevel,deidentificationChosen,controllerId,controllerType,controllerFacing,"
            + "processingNbt,semanticNbt,quantumGroupingNbt,sourceItemFrames,sourceItemFramesTruncated,"
            + "aeIssues,hintAreas,structures,cells,palette")) return false;
        for (String key : new String[] { "sourceItemFrames", "aeIssues", "hintAreas", "structures" })
            if (!absentOrEmptyArray(b, key)) return false;
        JsonElement controllerType = b.get("controllerType");
        boolean area = string(controllerType) && "area".equals(controllerType.getAsString());
        return absentOrEmpty(b, "processingNbt") && absentOrEmpty(b, "semanticNbt")
            && absentOrEmpty(b, "quantumGroupingNbt") && (area || absentOrEmpty(b, "controllerType"))
            && (absent(b, "controllerFacing") || integer(b, "controllerFacing", -1, true))
            && integer(b, "controllerId", -1, false) && optionalBoolean(b, "fullyVoucherCovered", false)
            && optionalBoolean(b, "sourceItemFramesTruncated", false)
            && optionalBooleanType(b, "retainNbt") && optionalBooleanType(b, "retainCovers");
    }

    private static Plain descriptor(JsonObject d, boolean palette) {
        String scalar = "item,metadata,damage,count,facing,controller,excluded,defaultDamage";
        if (!only(d, palette ? scalar : scalar + ",block,tileNbt,itemNbt,defaultItem,prompt,structureId,structureIds")
            || !string(d.get("item"))) return null;
        String name = d.get("item").getAsString();
        Plain result = Plain.find(name);
        if (result == null || !integer(d, "metadata", 0, false) || !integer(d, "damage", 0, false)
            || !integer(d, "defaultDamage", 0, false) || !integer(d, "count", 1, false)
            || !integer(d, "facing", -1, false) || !optionalBoolean(d, "controller", false)
            || !optionalBoolean(d, "excluded", false)) return null;
        if (palette) return result;
        JsonElement block = d.get("block");
        if (!string(block) || !name.equals(block.getAsString()) || !absentOrEmpty(d, "itemNbt")
            || !absentOrEmpty(d, "tileNbt") || !absentOrEmpty(d, "structureId")
            || !absentOrEmptyArray(d, "structureIds")) return null;
        JsonElement fallback = d.get("defaultItem");
        if (fallback != null && !fallback.isJsonNull()
            && (!string(fallback) || !fallback.getAsString().isEmpty() && !name.equals(fallback.getAsString())))
            return null;
        return result;
    }

    private static boolean only(JsonObject d, String names) {
        String members = "," + names + ",";
        for (java.util.Map.Entry<String, JsonElement> entry : d.entrySet()) {
            String key = entry.getKey();
            if (key.isEmpty() || key.indexOf(',') >= 0 || !members.contains("," + key + ",")) return false;
        }
        return true;
    }

    private static boolean absent(JsonObject d, String key) {
        JsonElement v = d.get(key);
        return v == null || v.isJsonNull();
    }

    private static boolean absentOrEmpty(JsonObject d, String key) {
        JsonElement v = d.get(key);
        return absent(d, key) || string(v) && v.getAsString().isEmpty();
    }

    private static boolean absentOrEmptyArray(JsonObject d, String key) {
        JsonElement v = d.get(key);
        return absent(d, key) || v.isJsonArray() && v.getAsJsonArray().size() == 0;
    }

    private static boolean string(JsonElement v) {
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
    }

    private static boolean integer(JsonObject d, String key, int expected, boolean required) {
        JsonElement v = d.get(key);
        return absent(d, key) ? !required : v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()
            && Integer.toString(expected).equals(v.getAsString());
    }

    private static boolean optionalBooleanType(JsonObject d, String key) {
        JsonElement v = d.get(key);
        return absent(d, key) || v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
    }

    private static boolean optionalBoolean(JsonObject d, String key, boolean expected) {
        return optionalBooleanType(d, key) && (absent(d, key) || d.get(key).getAsBoolean() == expected);
    }

    /** Immutable source only. Reports actual source rows visited on the first pass. Does not touch Minecraft state. */
    public static Prepared prepare(UUID request, int x, int y, int z, Source source, BooleanSupplier current,
        LongConsumer observed) throws IOException {
        checkpoint(current);
        Plain[] kinds = Plain.values();
        if (request == null || source == null || source.cells() < 1
            || source.cells() > HologramProjectedLayerWire.MAX_CELLS) throw invalid();
        int[] byY = new int[LEVELS], counts = new int[kinds.length];
        int[] low = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
        int[] high = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        Cursor cursor = source.open(false);
        int cells = 0, lastX = 0, lastY = 0, lastZ = 0;
        while (cursor.next()) {
            checkpoint(current);
            int a = cursor.x(), b = cursor.y(), c = cursor.z();
            Plain kind = cursor.kind();
            if (kind == null || !near(a, x) || !near(b, y) || !near(c, z) || b < 0 || b >= LEVELS
                || cells != 0 && HologramProjectedLayerWire.compare(lastY, lastZ, lastX, b, c, a) >= 0
                || ++cells > source.cells()) throw invalid();
            lastX = a;
            lastY = b;
            lastZ = c;
            counts[kind.ordinal()]++;
            byY[b]++;
            low[0] = Math.min(low[0], a);
            low[1] = Math.min(low[1], b);
            low[2] = Math.min(low[2], c);
            high[0] = Math.max(high[0], a);
            high[1] = Math.max(high[1], b);
            high[2] = Math.max(high[2], c);
            if (observed != null && (cells & 255) == 0) observed.accept(cells);
        }
        if (cells != source.cells()) throw invalid();
        if (observed != null) observed.accept(cells);
        List<Variant> palette = new ArrayList<>(kinds.length);
        int[] variant = new int[kinds.length];
        Arrays.fill(variant, -1);
        for (Plain kind : kinds) {
            int count = counts[kind.ordinal()];
            if (count != source.count(kind)) throw invalid();
            if (count == 0) continue;
            variant[kind.ordinal()] = palette.size();
            palette.add(new Variant(kind.id, 0, kind.id, 0, -1, false, "", "", count));
        }
        int first = Math.min(FIRST_SLICE_CELLS, byY[low[1]]);
        int slices = 1;
        for (int level = low[1]; level <= high[1]; level++) {
            int afterPrefix = byY[level] - (level == low[1] ? first : 0);
            slices += (afterPrefix + MAX_SLICE_CELLS - 1) / MAX_SLICE_CELLS;
        }
        if (slices < 1 || slices > HologramProjectedLayerService.MAX_SLICES) throw invalid();
        Header header = new Header(request, x, y, z, low[0], low[1], low[2], high[0], high[1], high[2], cells,
            slices, palette);
        byte[] checks = new byte[slices * HASH_BYTES];
        byte[] next = HologramProjectedLayerWire.end();
        cursor = source.open(true);
        int seq = slices;
        for (int at = high[1]; at >= low[1]; at--) {
            int left = byY[at], prefix = at == low[1] ? first : 0;
            while (left > 0) {
                checkpoint(current);
                int count = left == prefix ? prefix : (left - prefix - 1) % MAX_SLICE_CELLS + 1;
                byte[] bytes = encode(header, cursor, --seq, at, count, variant, next, true, current);
                next = HologramProjectedLayerWire.commitment(header, bytes);
                System.arraycopy(next, 0, checks, seq * HASH_BYTES, HASH_BYTES);
                left -= count;
            }
        }
        if (seq != 0 || cursor.next()) throw invalid();
        checkpoint(current);
        return new Prepared(source, header, byY, variant, checks, HologramProjectedLayerWire.encodeManifest(
            new Manifest(header, next)));
    }

    private static boolean near(int value, int anchor) {
        return Math.abs((long) value - anchor) <= MAX_OFFSET;
    }

    private static byte[] encode(Header header, Cursor cursor, int seq, int y, int count, int[] variant, byte[] next,
        boolean reverse, BooleanSupplier current) throws IOException {
        return HologramProjectedLayerWire.encodeSlice(slice(
            header, cursor, seq, y, count, variant, next, reverse, current));
    }

    private static Slice slice(Header header, Cursor cursor, int seq, int y, int count, int[] variant, byte[] next,
        boolean reverse, BooleanSupplier current) throws IOException {
        int[] cells = new int[count * 3];
        for (int i = 0; i < count; i++) {
            checkpoint(current);
            if (!cursor.next() || cursor.y() != y || cursor.kind() == null) throw invalid();
            int id = variant[cursor.kind().ordinal()];
            if (id < 0) throw invalid();
            int offset = (reverse ? count - 1 - i : i) * 3;
            cells[offset] = cursor.x();
            cells[offset + 1] = cursor.z();
            cells[offset + 2] = id;
        }
        return new Slice(header.request, seq, y, cells, next);
    }

    @Desugar
    public record Credit(boolean accepted, boolean transferComplete, boolean aborted, byte[] nextFrame) {}

    /** Worker-owner only. No frame is generated absent one accepted credit for this exact request. */
    public static final class Prepared {

        private Source source;
        private final Header header;
        private final int[] byY, variant;
        private byte[] checks, manifest;
        private final HologramProjectedLayerPlan.Sender sender;
        private Cursor cursor;
        private int y, at, next;
        private boolean closed;

        private Prepared(Source source, Header header, int[] byY, int[] variant, byte[] checks, byte[] manifest) {
            this.source = source;
            this.header = header;
            this.byY = byY;
            this.variant = variant;
            this.checks = checks;
            this.manifest = manifest;
            sender = new HologramProjectedLayerPlan.Sender(header);
            y = header.minY;
        }

        /** Transfers the only manifest buffer, not an owner request permission. */
        public byte[] takeManifest() {
            byte[] bytes = manifest;
            manifest = null;
            return bytes;
        }

        public Credit onAck(byte[] encoded, BooleanSupplier current) throws IOException {
            if (closed || manifest != null) return new Credit(false, false, false, null);
            try {
                checkpoint(current);
                Ack ack = HologramProjectedLayerWire.decodeAck(encoded);
                if (!sender.accept(ack)) return new Credit(false, false, false, null);
                if (ack.complete || ack.aborted) {
                    close();
                    return new Credit(true, ack.complete, ack.aborted, null);
                }
                if (ack.credit != 1 || ack.nextSlice != next) return new Credit(true, false, false, null);
                if (cursor == null) cursor = source.open(false);
                while (y <= header.maxY && at == byY[y]) {
                    y++;
                    at = 0;
                }
                if (y > header.maxY || next >= header.slices) throw invalid();
                int n = Math.min(byY[y] - at, next == 0 ? FIRST_SLICE_CELLS : MAX_SLICE_CELLS);
                byte[] tail = next + 1 == header.slices ? HologramProjectedLayerWire.end()
                    : hash(next + 1);
                Slice slice = slice(header, cursor, next, y, n, variant, tail, false, current);
                byte[] bytes = HologramProjectedLayerWire.encodeSlice(slice);
                if (!MessageDigest.isEqual(hash(next), HologramProjectedLayerWire.commitment(header, bytes))
                    || next + 1 == header.slices && cursor.next() || !sender.claim(slice)) throw invalid();
                checkpoint(current);
                at += n;
                next++;
                return new Credit(true, false, false, bytes);
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }

        private byte[] hash(int index) {
            return Arrays.copyOfRange(checks, index * HASH_BYTES, (index + 1) * HASH_BYTES);
        }

        public void close() {
            closed = true;
            source = null;
            cursor = null;
            checks = null;
            manifest = null;
        }
    }

    private static void checkpoint(BooleanSupplier current) throws InterruptedIOException {
        if (current == null || !current.getAsBoolean()) throw new InterruptedIOException("分层施工准备已取消");
    }

    private static IOException invalid() {
        return new IOException("当前蓝图没有完整安全的普通分层施工数据，未开始。");
    }
}
