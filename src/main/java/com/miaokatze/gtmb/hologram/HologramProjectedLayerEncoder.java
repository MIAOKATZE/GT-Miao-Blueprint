// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.MAX_SLICE_CELLS;

import java.io.IOException;
import java.io.InterruptedIOException;
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
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.FrozenDraft;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Variant;

/** Local plain-v1 producer. Never owns a full operation list, sends a packet, or interprets transfer ACK as placement. */
public final class HologramProjectedLayerEncoder {

    /** Mirrors the server's intentionally narrow projected placement reach envelope, not a permission grant. */
    public static final int MAX_OFFSET = 256;
    // A short first wire frame reduces initial staging latency; it is not a placement or inspection quota.
    private static final int FIRST_SLICE_CELLS = 32;
    private static final int LEVELS = 4096, AXIS_WIDTH = 2 * MAX_OFFSET + 1;

    public enum Plain {
        STONE("minecraft:stone"), COBBLESTONE("minecraft:cobblestone"), GLASS("minecraft:glass"),
        STONEBRICK("minecraft:stonebrick"), QUARTZ_BLOCK("minecraft:quartz_block"), GLOWSTONE("minecraft:glowstone");

        private final String id;

        Plain(String id) {
            this.id = id;
        }

        public String registry() {
            return id;
        }

        public static Plain find(String id) {
            if (id == null) return null;
            return switch (id) {
                case "minecraft:stone" -> STONE;
                case "minecraft:cobblestone" -> COBBLESTONE;
                case "minecraft:glass" -> GLASS;
                case "minecraft:stonebrick" -> STONEBRICK;
                case "minecraft:quartz_block" -> QUARTZ_BLOCK;
                case "minecraft:glowstone" -> GLOWSTONE;
                default -> null;
            };
        }
    }

    /** Cursors traverse the existing immutable source, without collecting or sorting its cells into a second list. */
    public interface Source {

        Cursor open(boolean reverse) throws IOException;

        Layout layout();
    }

    /** Bounded immutable source facts, not a second set of geometry or an admission decision. */
    public static final class Layout {

        private final int[] low, high, byY, counts;

        Layout(int[] low, int[] high, int[] byY, int[] counts) {
            this.low = low.clone();
            this.high = high.clone();
            this.byY = byY.clone();
            this.counts = counts.clone();
        }

        public int atY(int worldY) {
            if (worldY < low[1] || worldY > high[1]) return 0;
            return byY[worldY - low[1]];
        }
    }

    public interface Cursor {

        boolean next() throws IOException;

        int x();

        int y();

        int z();

        Plain kind();
    }

    /**
     * Only facts about an exact local immutable capture, never a server admission. The existing display reader owns
     * both the capture and its decoded rows; an unchanged capture object must accompany the facts on use.
     */
    public static final class Evidence {

        private final Object exact;
        private final int[] counts, low, high;
        private final int[][] axes;
        private final int cells;

        private Evidence(Object exact, int[] counts, int[] low, int[] high, int[][] axes, int cells) {
            this.exact = exact;
            this.counts = counts;
            this.low = low;
            this.high = high;
            this.axes = axes;
            this.cells = cells;
        }

        public boolean sameCapture(Object source) {
            return exact == source;
        }

        public int cells() {
            return cells;
        }

        public int count(Plain kind) {
            return counts[kind.ordinal()];
        }

        public boolean matchesBounds(int[] extents) {
            return extents != null && extents.length == 6 && extents[0] == low[0] && extents[1] == low[1]
                && extents[2] == low[2] && extents[3] == high[0] && extents[4] == high[1] && extents[5] == high[2];
        }

        /** Map the already captured axis distributions to the clicked pose in at most 513 entries. */
        public Layout layout(int[] anchor, int[] worldAxis, int[] sign) throws IOException {
            if (anchor == null || worldAxis == null || sign == null
                || anchor.length != 3 || worldAxis.length != 3 || sign.length != 3) throw invalid();
            int[] worldLow = new int[3], worldHigh = new int[3];
            int axesSeen = 0, vertical = -1;
            for (int axis = 0; axis < 3; axis++) {
                int out = worldAxis[axis], direction = sign[axis];
                if (out < 0 || out >= 3 || (axesSeen & 1 << out) != 0 || direction != -1 && direction != 1)
                    throw invalid();
                axesSeen |= 1 << out;
                if (out == 1) vertical = axis;
                long lower = (long) anchor[out] + (direction == 1 ? low[axis] : -high[axis]);
                long upper = (long) anchor[out] + (direction == 1 ? high[axis] : -low[axis]);
                if (lower < Integer.MIN_VALUE || upper > Integer.MAX_VALUE) throw invalid();
                worldLow[out] = (int) lower;
                worldHigh[out] = (int) upper;
            }
            int[] byY = new int[high[vertical] - low[vertical] + 1];
            for (int coordinate = low[vertical]; coordinate <= high[vertical]; coordinate++) {
                int y = sign[vertical] == 1 ? coordinate - low[vertical] : high[vertical] - coordinate;
                byY[y] = axes[vertical][coordinate + MAX_OFFSET];
            }
            return new Layout(worldLow, worldHigh, byY, counts);
        }
    }

    /** Common row facts for exact captured files and copy-on-write drafts. Never stores a second coordinate list. */
    private static final class AxisFacts {

        final int[] counts = new int[Plain.values().length];
        final int[] low = { MAX_OFFSET, MAX_OFFSET, MAX_OFFSET }, high = { -MAX_OFFSET, -MAX_OFFSET, -MAX_OFFSET };
        private final int[][] axes = new int[3][AXIS_WIDTH];
        int seen;

        boolean row(int x, int y, int z, Plain kind) {
            if (Math.abs((long) x) > MAX_OFFSET || Math.abs((long) y) > MAX_OFFSET
                || Math.abs((long) z) > MAX_OFFSET) return false;
            axis(0, x);
            axis(1, y);
            axis(2, z);
            counts[kind.ordinal()]++;
            seen++;
            return true;
        }

        private void axis(int axis, int coordinate) {
            axes[axis][coordinate + MAX_OFFSET]++;
            if (coordinate < low[axis]) low[axis] = coordinate;
            if (coordinate > high[axis]) high[axis] = coordinate;
        }

        Evidence evidence(Object exact) {
            return new Evidence(exact, counts, low, high, axes, seen);
        }
    }

    /** Piggyback the existing compact display pass. Every row ordinal must be observed exactly once or proof fails. */
    public static final class EvidenceBuilder {

        private final byte[] exact;
        private final HologramBlueprintCompactCodec.DisplayRows rows;
        private final Plain[] dictionary;
        private final int paletteKinds;
        private final boolean localSource;
        private final AxisFacts facts = new AxisFacts();
        private boolean invalid, finished;

        private EvidenceBuilder(byte[] exact, HologramBlueprintCompactCodec.DisplayRows rows, Plain[] dictionary,
            int paletteKinds, boolean localSource) {
            this.exact = exact;
            this.rows = rows;
            this.dictionary = dictionary;
            this.paletteKinds = paletteKinds;
            this.localSource = localSource;
        }

        public void row(int index, int materialIndex) {
            if (invalid || finished) return;
            if (facts.seen >= rows.cells() || index != facts.seen || materialIndex < 0 || materialIndex >= dictionary.length
                || rows.materialIndex(index) != materialIndex || dictionary[materialIndex] == null) {
                invalid = true;
                return;
            }
            invalid = !facts.row(rows.coordinate(index, 0), rows.coordinate(index, 1),
                rows.coordinate(index, 2), dictionary[materialIndex]);
        }

        public Evidence finish() {
            if (invalid || finished || facts.seen == 0 || facts.seen != rows.cells()) return null;
            finished = true;
            for (Plain kind : Plain.values())
                if ((paletteKinds & 1 << kind.ordinal()) != 0 && facts.counts[kind.ordinal()] == 0) return null;
            // The display projection may recover from historical syntax. Construction evidence may not.
            switch (rows.storedSyntax) {
                case INVALID -> { return null; }
                case UNCHECKED -> {
                    try {
                        if (localSource) HologramBlueprintPreviewBinding.validateLocalStoredSyntax(exact);
                        else HologramBlueprintPreviewBinding.validateStoredSyntax(exact);
                    } catch (IOException invalid) {
                        return null;
                    }
                }
                default -> {} // Strict local literal rows and retained-key syntax shared the existing reader.
            }
            return facts.evidence(exact);
        }
    }

    /** Raw eligibility and row ownership come only from the actual locked copy-on-write draft visitor. */
    public static DraftEvidenceBuilder inspectDraft(FrozenDraft draft) {
        if (draft == null || draft.cellCount() < 1 || !draft.plainPlacementEligible()) return null;
        int paletteKinds = draft.plainPlacementPaletteMask();
        return paletteKinds < 0 ? null : new DraftEvidenceBuilder(draft, paletteKinds);
    }

    public static final class DraftEvidenceBuilder {

        private final FrozenDraft owner;
        private final int paletteKinds;
        private final AxisFacts facts = new AxisFacts();
        private boolean invalid, finished;

        private DraftEvidenceBuilder(FrozenDraft owner, int paletteKinds) {
            this.owner = owner;
            this.paletteKinds = paletteKinds;
        }

        void reject() {
            invalid = true;
        }

        void row(FrozenDraft draft, int x, int y, int z, String block, int metadata, String item, int damage,
            int facing, String tileNbt, String itemNbt, int count, boolean controller) {
            if (invalid || finished) return;
            Plain kind = Plain.find(item);
            invalid = draft != owner || facts.seen >= owner.cellCount() || kind == null || !item.equals(block)
                || metadata != 0 || damage != 0 || facing != -1 || controller || count != 1
                || tileNbt == null || !tileNbt.isEmpty() || itemNbt == null || !itemNbt.isEmpty()
                || !facts.row(x, y, z, kind);
        }

        public Evidence finish() {
            if (invalid || finished || facts.seen == 0 || facts.seen != owner.cellCount()) return null;
            finished = true;
            for (Plain kind : Plain.values())
                if ((paletteKinds & 1 << kind.ordinal()) != 0 && facts.counts[kind.ordinal()] == 0) return null;
            return facts.evidence(owner);
        }
    }

    private HologramProjectedLayerEncoder() {}

    @Desugar
    public record Inspection(HologramBlueprintCompactCodec.DisplayRows rows, EvidenceBuilder builder) {}

    /** Replace the existing display decode at its call site; the proof never receives rows decoded from other bytes. */
    public static Inspection inspect(byte[] exact, boolean indexedPrivate) throws IOException {
        HologramBlueprintCompactCodec.DisplayRows rows = HologramBlueprintCompactCodec.displayGeometry(exact);
        return new Inspection(rows, indexedPrivate ? plainEvidence(exact, rows, false) : null);
    }

    /** Local files may contain more logical cells; the indexed-source proof gate is unchanged. */
    public static Inspection inspectLocal(byte[] exact, boolean indexedPrivate) throws IOException {
        HologramBlueprintCompactCodec.DisplayRows rows = HologramBlueprintCompactCodec.localDisplayGeometry(exact, indexedPrivate);
        return new Inspection(rows, indexedPrivate ? plainEvidence(exact, rows, true) : null);
    }

    /** Failure leaves preview/edit/save available; no proof exists until the same reader visits all ordinals. */
    private static EvidenceBuilder plainEvidence(byte[] exact, HologramBlueprintCompactCodec.DisplayRows rows,
        boolean localSource) {
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
        return new EvidenceBuilder(exact, rows, dictionary, paletteKinds, localSource);
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

    /** Use captured layout facts; preparation never visits geometry before admission. */
    public static Prepared prepare(UUID request, int x, int y, int z, Source source, BooleanSupplier current,
        LongConsumer observed) throws IOException {
        checkpoint(current);
        if (request == null || source == null) throw invalid();
        Layout layout = source.layout();
        if (layout == null || layout.low.length != 3 || layout.high.length != 3
            || layout.counts.length != Plain.values().length) throw invalid();
        int[] low = layout.low, high = layout.high;
        int[] anchor = { x, y, z };
        for (int axis = 0; axis < 3; axis++)
            if (low[axis] > high[axis] || !near(low[axis], anchor[axis]) || !near(high[axis], anchor[axis]))
                throw invalid();
        if (low[1] < 0 || high[1] >= LEVELS || layout.byY.length != high[1] - low[1] + 1
            || layout.atY(low[1]) <= 0 || layout.atY(high[1]) <= 0) throw invalid();
        long total = 0, columns = (long) (high[0] - low[0] + 1) * (high[2] - low[2] + 1);
        for (int count : layout.byY) {
            if (count < 0 || count > columns) throw invalid();
            total += count;
        }
        if (total < 1 || total > HologramProjectedLayerWire.MAX_CELLS) throw invalid();
        int cells = (int) total;
        Plain[] kinds = Plain.values();
        List<Variant> palette = new ArrayList<>(kinds.length);
        int[] variant = new int[kinds.length];
        Arrays.fill(variant, -1);
        for (Plain kind : kinds) {
            int count = layout.counts[kind.ordinal()];
            if (count < 0 || count > cells) throw invalid();
            if (count == 0) continue;
            variant[kind.ordinal()] = palette.size();
            palette.add(new Variant(kind.id, 0, kind.id, 0, -1, false, "", "", count));
        }
        int first = Math.min(FIRST_SLICE_CELLS, layout.atY(low[1]));
        int slices = 1;
        for (int level = low[1]; level <= high[1]; level++) {
            int afterPrefix = layout.atY(level) - (level == low[1] ? first : 0);
            slices += (afterPrefix + MAX_SLICE_CELLS - 1) / MAX_SLICE_CELLS;
        }
        if (slices < 1 || slices > HologramProjectedLayerService.MAX_SLICES) throw invalid();
        Header header = new Header(request, x, y, z, low[0], low[1], low[2], high[0], high[1], high[2], cells,
            slices, palette);
        // Layout and aggregate quantities came from the same immutable decode. No geometry prepass.
        checkpoint(current);
        return new Prepared(source, header, layout, variant, observed,
            HologramProjectedLayerWire.encodeManifest(new Manifest(header)));
    }

    private static boolean near(int value, int anchor) {
        return Math.abs((long) value - anchor) <= MAX_OFFSET;
    }

    /** Reconfirm exact layout and ordered coordinates while the only forward stream is packed. */
    private static final class PackingProof {

        private final Header header;
        private final int[] remaining;
        private final LongConsumer observed;
        private int seen, lastX, lastY, lastZ;
        private int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        PackingProof(Header header, int[] counts, LongConsumer observed) {
            this.header = header;
            remaining = counts.clone();
            this.observed = observed;
        }

        void row(int x, int y, int z, Plain kind) throws IOException {
            if (x < header.minX || x > header.maxX || z < header.minZ || z > header.maxZ
                || seen != 0 && HologramProjectedLayerWire.compare(lastY, lastZ, lastX, y, z, x) >= 0
                || --remaining[kind.ordinal()] < 0) throw invalid();
            lastX = x;
            lastY = y;
            lastZ = z;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
            if ((++seen & 255) == 0 && observed != null) observed.accept(seen);
        }

        void complete() throws IOException {
            if (seen != header.cells || minX != header.minX || maxX != header.maxX
                || minZ != header.minZ || maxZ != header.maxZ) throw invalid();
            for (int count : remaining) if (count != 0) throw invalid();
            if (observed != null) observed.accept(seen);
        }
    }

    private static Slice slice(Header header, Cursor cursor, int seq, int y, int count, int[] variant,
        BooleanSupplier current, PackingProof proof) throws IOException {
        int[] cells = new int[count * 3];
        for (int i = 0; i < count; i++) {
            checkpoint(current);
            if (!cursor.next() || cursor.y() != y) throw invalid();
            Plain kind = cursor.kind();
            if (kind == null) throw invalid();
            int id = variant[kind.ordinal()];
            if (id < 0) throw invalid();
            int x = cursor.x(), z = cursor.z();
            if (proof != null) proof.row(x, y, z, kind);
            int offset = i * 3;
            cells[offset] = x;
            cells[offset + 1] = z;
            cells[offset + 2] = id;
        }
        return new Slice(header.request, seq, y, cells);
    }

    @Desugar
    public record Credit(boolean accepted, boolean transferComplete, boolean aborted, byte[] nextFrame) {}

    /** Worker-owner only. Frames are generated only inside the accepted bounded window for this exact request. */
    public static final class Prepared {

        private Source source;
        private final Header header;
        private final Layout layout;
        private final int[] variant;
        private byte[] manifest;
        private final PackingProof proof;
        private final HologramProjectedLayerPlan.Sender sender;
        private Cursor cursor;
        private int y, at, next;
        private boolean closed;

        private Prepared(Source source, Header header, Layout layout, int[] variant, LongConsumer observed, byte[] manifest) {
            this.source = source;
            this.header = header;
            this.layout = layout;
            this.variant = variant;
            proof = new PackingProof(header, layout.counts, observed);
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
                if (!sender.canSend()) return new Credit(true, false, false, null);
                if (cursor == null) cursor = source.open(false);
                while (y <= header.maxY && at == layout.atY(y)) {
                    y++;
                    at = 0;
                }
                if (y > header.maxY || next >= header.slices) throw invalid();
                int n = Math.min(layout.atY(y) - at, next == 0 ? FIRST_SLICE_CELLS : MAX_SLICE_CELLS);
                Slice slice = slice(header, cursor, next, y, n, variant, current, proof);
                byte[] bytes = HologramProjectedLayerWire.encodeSlice(slice);
                if (next + 1 == header.slices) {
                    if (cursor.next()) throw invalid();
                    proof.complete();
                }
                if (!sender.claim(slice)) throw invalid();
                checkpoint(current);
                at += n;
                next++;
                return new Credit(true, false, false, bytes);
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }

        public void close() {
            closed = true;
            source = null;
            cursor = null;
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
