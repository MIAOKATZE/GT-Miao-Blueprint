// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintBytes.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Both game migration and the standalone CLI call this registry-free converter. Never regenerates machines. */
public final class NativeBlueprintMigration {

    private static final List<String> ELEMENT = Arrays
        .asList("block", "metadata", "item", "damage", "count", "defaultItem", "defaultDamage");
    private static final List<String> META = Arrays.asList("id", "name", "authorName", "authorUuid", "createdAt");
    private static final Set<String> CELL = new HashSet<>(
        Arrays.asList(
            "x",
            "y",
            "z",
            "block",
            "metadata",
            "item",
            "damage",
            "count",
            "defaultItem",
            "defaultDamage",
            "tileNbt",
            "itemNbt",
            "facing",
            "prompt",
            "excluded",
            "controller",
            "structureId",
            "structureIds",
            "allowedTargets",
            "structureRole",
            "structureFamily",
            "structureHint",
            "structureCandidatesComplete",
            "structureHatchFacings"));
    private static final Set<String> DOCUMENT = new HashSet<>(
        Arrays.asList(
            "id",
            "name",
            "authorName",
            "authorUuid",
            "createdAt",
            "cells",
            "palette",
            "structures",
            "hintAreas",
            "schema",
            "nativeBlueprint",
            "fullyVoucherCovered",
            "aePolicyVersion",
            "aeIssues",
            "deidentificationVersion",
            "deidentificationLevel",
            "deidentificationChosen",
            "processingNbt",
            "semanticNbt",
            "quantumGroupingNbt",
            "sourceItemFrames",
            "sourceItemFramesTruncated",
            "retainNbt",
            "retainCovers",
            "retainedNbtKeys",
            "prompt",
            "sourceMod",
            "sourceClass",
            "modVersions",
            "controllerType",
            "controllerId",
            "controllerFacing"));
    private final BooleanSupplier current;
    private final TreeMap<Key, Integer> payloadIds = new TreeMap<>();
    private final List<byte[]> payloadByOldId = new ArrayList<>();
    private final Map<String, Integer> decodedNbt = new HashMap<>();
    private long totalNbt;
    private final Map<Integer, Integer> payloadAliases = new HashMap<>();
    private boolean unknown;

    private NativeBlueprintMigration(BooleanSupplier current) {
        this.current = current;
    }

    public static Result convert(byte[] source, int member, BooleanSupplier current) throws IOException {
        return new NativeBlueprintMigration(current).convert(source, member);
    }

    public static Result convert(byte[] source) throws IOException {
        return convert(source, -1, () -> true);
    }

    @FunctionalInterface
    public interface AuthoredCell {

        JsonObject at(int index) throws IOException;
    }

    /**
     * Authored literal data only. A nonnegative group identifies identical non-coordinate fields;
     * integer x/y/z are all present. Negative values always use the full per-cell codec.
     */
    public interface IndexedAuthoredCell extends AuthoredCell {

        int descriptor(int index);

        int coordinate(int index, int axis);
    }

    /** Captured data enters directly as canonical header + row provider. No intermediate JSON document. */
    public static Result author(JsonObject header, JsonObject envelope, int count, AuthoredCell reader,
        BooleanSupplier current) throws IOException {
        need(count > 0 && count <= NativeBlueprintLimits.CELLS, "native authored cell budget");
        return new NativeBlueprintMigration(current).encode(
            header,
            null,
            envelope,
            new JsonObject(),
            false,
            new JsonArray(),
            new JsonArray(),
            null,
            count,
            reader::at,
            reader instanceof IndexedAuthoredCell i ? i : null);
    }

    private void check() throws IOException {
        need(current.getAsBoolean(), "conversion cancelled");
    }

    public static final class Result {

        public final byte[] bytes;
        public final long cells;
        public final Map<Integer, Integer> sectionBytes;

        Result(byte[] bytes, long n, Map<Integer, Integer> s) {
            this.bytes = bytes;
            cells = n;
            sectionBytes = java.util.Collections.unmodifiableMap(s);
        }
    }

    private Result convert(byte[] source, int member) throws IOException {
        check();
        NativeBlueprintJson.LegacyInput input = new NativeBlueprintJson.LegacyInput(source);
        JsonObject root = input.root, doc, envelope = new JsonObject(), legacy = new JsonObject();
        boolean compact = root.has("encoding");
        if (compact) need(
            root.get("encoding")
                .isJsonPrimitive()
                && root.get("encoding")
                    .getAsString()
                    .equals("gtmb-dictionary-1"),
            "legacy encoding");
        if (root.has("presets")) {
            need(
                !root.has("blueprint") && root.get("presets")
                    .isJsonArray(),
                "ambiguous collection");
            JsonArray a = root.getAsJsonArray("presets");
            need(member >= 0 && member < a.size(), "collection requires explicit member");
            doc = asObject(a.get(member), "collection member");
            legacy.addProperty("shape", "collection-member");
            legacy.addProperty("collectionOrdinal", member);
        } else if (root.has("blueprint")) {
            need(member < 0, "member on single blueprint");
            doc = asObject(root.get("blueprint"), "blueprint");
            legacy.addProperty("shape", "blueprint");
        } else {
            need(member < 0, "member on direct blueprint");
            doc = root;
            legacy.addProperty("shape", "direct");
        }
        if (doc != root) {
            for (Map.Entry<String, JsonElement> e : root.entrySet())
                if (!Arrays.asList("blueprint", "presets", "previewBinding", "schema")
                    .contains(e.getKey())
                    && (!compact || !Arrays.asList("materials", "nbt", "encoding")
                        .contains(e.getKey())))
                    envelope.add(e.getKey(), e.getValue());
            if (root.has("schema")) legacy.add("schema", root.get("schema"));
        }
        if (compact) legacy.add("encoding", root.get("encoding"));
        if (root.has("previewBinding")) legacy.add("previewBinding", root.get("previewBinding"));
        JsonArray materials = compact ? asArray(root.get("materials"), "materials") : new JsonArray(),
            nbts = compact ? asArray(root.get("nbt"), "nbt") : new JsonArray();
        need(
            doc.has("cells") && doc.get("cells")
                .isJsonArray(),
            "cells array required");
        NativeBlueprintJson.Rows cells = input.tables.get(doc);
        need(cells != null, "cells table");
        return encode(
            doc,
            root,
            envelope,
            legacy,
            compact,
            materials,
            nbts,
            hash(source),
            -1,
            index -> cells.hasNext() ? cells.next() : null);
    }

    @FunctionalInterface
    private interface CellInput {

        JsonElement at(int index) throws IOException;
    }

    private Result encode(JsonObject doc, JsonObject root, JsonObject envelope, JsonObject legacy, boolean compact,
        JsonArray materials, JsonArray nbts, String sourceHash, int expected, CellInput input) throws IOException {
        return encode(doc, root, envelope, legacy, compact, materials, nbts, sourceHash, expected, input, null);
    }

    private Result encode(JsonObject doc, JsonObject root, JsonObject envelope, JsonObject legacy, boolean compact,
        JsonArray materials, JsonArray nbts, String sourceHash, int expected, CellInput input,
        IndexedAuthoredCell indexed) throws IOException {
        check();
        boolean[] usedMat = new boolean[materials.size()], usedNbt = new boolean[nbts.size()];
        int count = 0;
        List<Row> rows = new ArrayList<>();
        TreeMap<Key, Integer> elementIds = new TreeMap<>(), extraIds = new TreeMap<>();
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX, maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        Map<Integer, GeometryAtom> repeated = indexed == null ? null : new HashMap<>();
        while (expected < 0 || count < expected) {
            check();
            int descriptor = indexed == null ? -1 : indexed.descriptor(count);
            GeometryAtom hit = descriptor < 0 ? null : repeated.get(descriptor);
            if (hit != null) {
                int i = count++, x = indexed.coordinate(i, 0), y = indexed.coordinate(i, 1),
                    z = indexed.coordinate(i, 2);
                rows.add(new Row(x, y, z, i, hit.el, hit.ex));
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
                continue;
            }
            JsonElement incoming = input.at(count);
            if (incoming == null) {
                need(expected < 0, "incomplete authored geometry");
                break;
            }
            need(count < NativeBlueprintLimits.CELLS, "native authored cell budget");
            int i = count++;
            JsonObject c = resolveCell(incoming, compact, materials, nbts, usedMat, usedNbt);
            unknown |= !NativeBlueprintSemantics.legacyCellKnown(c);
            int x = coordinate(c, "x"), y = coordinate(c, "y"), z = coordinate(c, "z");
            JsonObject e = new JsonObject(), extra = new JsonObject();
            for (Map.Entry<String, JsonElement> f : c.entrySet())
                if (ELEMENT.contains(f.getKey())) e.add(f.getKey(), f.getValue());
                else if (!Arrays.asList("x", "y", "z")
                    .contains(f.getKey())) extra.add(f.getKey(), f.getValue());
            // Preserve exceptional axis presence/-0 in a migration-only wrapper, never interpreted as a placement
            // property.
            JsonObject syntax = new JsonObject();
            int mask = 0, negative = 0, bit = 1;
            for (String axis : Arrays.asList("x", "y", "z")) {
                if (c.has(axis)) {
                    mask |= bit;
                    if (c.get(axis)
                        .toString()
                        .equals("-0")) negative |= bit;
                }
                bit <<= 1;
            }
            syntax.addProperty("presentMask", mask);
            syntax.addProperty("negativeZeroMask", negative);
            JsonObject w = wrap(extra, Arrays.asList("/tileNbt", "/itemNbt"));
            if (mask != 7 || negative != 0) w.add("coordinateSource", syntax);
            byte kind = (byte) (e.has("block") && e.get("block")
                .isJsonPrimitive()
                && e.getAsJsonPrimitive("block")
                    .isString()
                && e.get("block")
                    .getAsString()
                    .equals("minecraft:air") ? 1 : 0);
            byte[] j = NativeBlueprintJson.encode(e), eb = new byte[j.length + 1];
            eb[0] = kind;
            System.arraycopy(j, 0, eb, 1, j.length);
            Key ek = new Key(eb), wk = new Key(NativeBlueprintJson.encode(w));
            ek = intern(elementIds, ek);
            wk = intern(extraIds, wk);
            if (descriptor >= 0) repeated.put(descriptor, new GeometryAtom(ek, wk));
            rows.add(new Row(x, y, z, i, ek, wk));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        unknown |= !envelope.entrySet()
            .isEmpty();
        for (Map.Entry<String, JsonElement> e : doc.entrySet())
            unknown |= !DOCUMENT.contains(e.getKey()) && !(doc == root && mechanic(e.getKey(), compact));
        JsonObject metaFields = new JsonObject(), other = new JsonObject(), regions = new JsonObject();
        for (Map.Entry<String, JsonElement> e : doc.entrySet())
            if (META.contains(e.getKey())) metaFields.add(e.getKey(), e.getValue());
            else if (e.getKey()
                .equals("structures")
                || e.getKey()
                    .equals("hintAreas"))
                regions.add(e.getKey(), e.getValue());
            else if (!e.getKey()
                .equals("cells") && !(doc == root && mechanic(e.getKey(), compact)))
                other.add(e.getKey(), e.getValue());
        // Legacy palette rows share compact descriptors but are never occupied geometry.
        if (other.has("palette") && other.get("palette")
            .isJsonArray()) {
            JsonArray a = other.getAsJsonArray("palette"), p = new JsonArray();
            for (JsonElement e : a) {
                if (!compact && !e.isJsonObject()) {
                    p.add(e);
                    continue;
                }
                JsonObject c = resolveCell(e, compact, materials, nbts, usedMat, usedNbt);
                p.add(c);
            }
            other.add("palette", p);
        }
        resolveDocumentNbt(other, regions, compact, nbts, usedNbt);
        unknown |= !NativeBlueprintSemantics.legacyKnown(metaFields, regions, other);
        List<String> regionPointers = new ArrayList<>();
        if (regions.has("structures") && regions.get("structures")
            .isJsonArray()) {
            Set<String> ids = new HashSet<>();
            JsonArray a = regions.getAsJsonArray("structures");
            for (int i = 0; i < a.size(); i++) if (a.get(i)
                .isJsonObject()) {
                    JsonObject r = a.get(i)
                        .getAsJsonObject();
                    if (r.has("id") && !r.get("id")
                        .isJsonNull()) need(
                            ids.add(
                                r.get("id")
                                    .toString()),
                            "duplicate structure id");
                    regionPointers.add("/structures/" + i + "/semanticNbt");
                    regionPointers.add("/structures/" + i + "/textBoxNbt");
                }
        }
        JsonObject regionWrap = wrap(regions, regionPointers);
        JsonObject unusedMat = new JsonObject();
        addUnused(legacy, "unusedMaterials", materials, usedMat);
        addUnused(legacy, "unusedNbt", nbts, usedNbt);
        if (unknown && compact) {
            JsonObject order = new JsonObject();
            order.add("materials", materials);
            order.add("nbt", nbts);
            legacy.add("dictionaryOrder", order);
        }
        JsonObject extrasDoc = new JsonObject();
        extrasDoc.add("document", other);
        extrasDoc.add("envelope", envelope);
        extrasDoc.add("legacyEnvelope", legacy);
        List<String> docPointers = new ArrayList<>(
            Arrays.asList("/document/processingNbt", "/document/semanticNbt", "/document/quantumGroupingNbt"));
        if (other.has("palette") && other.get("palette")
            .isJsonArray())
            for (int i = 0; i < other.getAsJsonArray("palette")
                .size(); i++) {
                    docPointers.add("/document/palette/" + i + "/tileNbt");
                    docPointers.add("/document/palette/" + i + "/itemNbt");
                }
        JsonObject documentWrap = wrap(extrasDoc, docPointers);
        JsonObject meta = new JsonObject(), migration = new JsonObject();
        meta.add("fields", metaFields);
        if (sourceHash != null) {
            migration.addProperty("format", compact ? "gtmb-dictionary-1" : "expanded-json");
            migration.addProperty("sourceSha256", sourceHash);
            migration.add("legacyContentIdentity", JsonNull.INSTANCE);
            migration.addProperty("unknownSemantics", unknown);
            meta.add("migration", migration);
        }
        return emit(
            rows,
            elementIds,
            extraIds,
            meta,
            regionWrap,
            documentWrap,
            payloadByOldId.size(),
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ,
            0,
            0,
            0);
    }

    // Shared by the native geometry editor and the author/migration writer. This emits native sections, not a legacy
    // document.
    private Result emit(List<Row> rows, TreeMap<Key, Integer> elementIds, TreeMap<Key, Integer> extraIds,
        JsonObject meta, JsonObject regionWrap, JsonObject documentWrap, int oldPayloadCount, int minX, int minY,
        int minZ, int maxX, int maxY, int maxZ, int ax, int ay, int az) throws IOException {
        int count = rows.size();
        // Payload IDs are finalized after all three ownership scopes have been visited.
        int[] remap = new int[oldPayloadCount + 1];
        int id = 0;
        for (Map.Entry<Key, Integer> e : payloadIds.entrySet()) {
            remap[e.getValue()] = ++id;
        }
        for (var alias : payloadAliases.entrySet()) remap[alias.getKey()] = remap[alias.getValue()];
        TreeMap<Key, Integer> finalExtras = new TreeMap<>();
        Map<Key, Key> extraMapping = new HashMap<>();
        for (Key k : extraIds.keySet()) {
            JsonObject w = NativeBlueprintJson.parse(k.data)
                .getAsJsonObject();
            remap(w, remap);
            Key v = new Key(NativeBlueprintJson.encode(w));
            finalExtras.put(v, 0);
            extraMapping.put(k, v);
        }
        for (Row row : rows) row.extra = extraMapping.get(row.extra);
        remap(regionWrap, remap);
        remap(documentWrap, remap);
        int ei = 0;
        for (Map.Entry<Key, Integer> e : elementIds.entrySet()) e.setValue(++ei);
        Key empty = new Key(NativeBlueprintJson.encode(NativeBlueprintDefinition.emptyWrap()));
        finalExtras.remove(empty);
        int wi = 0;
        for (Map.Entry<Key, Integer> e : finalExtras.entrySet()) e.setValue(++wi);
        rows.sort((a, b) -> {
            int v = Integer.compare(a.y, b.y);
            if (v == 0) v = Integer.compare(a.z, b.z);
            return v == 0 ? Integer.compare(a.x, b.x) : v;
        });
        for (int i = 1; i < rows.size(); i++) need(
            !rows.get(i)
                .samePosition(rows.get(i - 1)),
            "duplicate cell position");
        TreeMap<Integer, byte[]> sections = new TreeMap<>();
        sections.put(1, NativeBlueprintJson.encode(meta));
        Out elements = new Out();
        elements.var(elementIds.size());
        for (Key e : elementIds.keySet()) {
            elements.u8(e.data[0]);
            elements.blob(Arrays.copyOfRange(e.data, 1, e.data.length));
        }
        sections.put(2, elements.toByteArray());
        Geometry geo = geometry(
            rows,
            elementIds,
            count == 0 ? 0 : minX,
            count == 0 ? 0 : minY,
            count == 0 ? 0 : minZ,
            count == 0 ? 0 : (long) maxX - minX + 1,
            count == 0 ? 0 : (long) maxY - minY + 1,
            count == 0 ? 0 : (long) maxZ - minZ + 1);
        java.nio.ByteBuffer pivot = java.nio.ByteBuffer.wrap(geo.index)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        pivot.position(24);
        pivot.putInt(ax)
            .putInt(ay)
            .putInt(az);
        sections.put(3, geo.index);
        sections.put(4, geo.bytes);
        if (!payloadIds.isEmpty()) {
            Out pi = new Out(), pb = new Out();
            pi.u32(payloadIds.size());
            pi.u32(0);
            for (Key k : payloadIds.keySet()) {
                byte[] raw = k.data, gzip = NativeBlueprintNbt.gzip(raw);
                boolean zipped = gzip.length + 16 <= raw.length;
                byte[] b = zipped ? gzip : raw;
                pi.u64(pb.size());
                pi.u32(b.length);
                pi.u32(raw.length);
                pi.u8(1);
                pi.u8(zipped ? 1 : 0);
                pi.u16(0);
                pi.u32(0);
                pb.bytes(b);
            }
            sections.put(5, pi.toByteArray());
            sections.put(6, pb.toByteArray());
        }
        if (!finalExtras.isEmpty()) {
            Out ex = new Out();
            ex.var(finalExtras.size());
            for (Key k : finalExtras.keySet()) ex.blob(k.data);
            Map<Integer, Map<Key, Integer>> frequencies = new TreeMap<>();
            for (Row r : rows) frequencies.computeIfAbsent(elementIds.get(r.element), k -> new TreeMap<>())
                .merge(r.extra, 1, Integer::sum);
            Map<Integer, Key> defaults = new TreeMap<>();
            for (Map.Entry<Integer, Map<Key, Integer>> e : frequencies.entrySet()) {
                Key best = null;
                int n = -1;
                for (Map.Entry<Key, Integer> f : e.getValue()
                    .entrySet()) if (f.getValue() > n) {
                        best = f.getKey();
                        n = f.getValue();
                    }
                if (!best.equals(empty)) defaults.put(e.getKey(), best);
            }
            ex.var(defaults.size());
            for (Map.Entry<Integer, Key> e : defaults.entrySet()) {
                ex.var(e.getKey());
                ex.var(finalExtras.get(e.getValue()));
            }
            int no = 0;
            for (Row r : rows) if (!r.extra.equals(defaults.getOrDefault(elementIds.get(r.element), empty))) no++;
            ex.var(no);
            for (Row r : rows) if (!r.extra.equals(defaults.getOrDefault(elementIds.get(r.element), empty))) {
                ex.i32(r.x);
                ex.i32(r.y);
                ex.i32(r.z);
                ex.var(r.extra.equals(empty) ? 0 : finalExtras.get(r.extra));
            }
            sections.put(7, ex.toByteArray());
        }
        if (regionWrap != null && !regionWrap.getAsJsonObject("fields")
            .entrySet()
            .isEmpty()) sections.put(8, NativeBlueprintJson.encode(regionWrap));
        sections.put(9, NativeBlueprintJson.encode(documentWrap));
        if (unknown) {
            int[] order = new int[count];
            for (int i = 0; i < count; i++) order[rows.get(i).original] = i;
            Out o = new Out();
            o.var(count);
            for (int p : order) o.var(p);
            sections.put(10, o.toByteArray());
        }
        check();
        return pack(sections, count);
    }

    @FunctionalInterface
    interface GeometryInput {

        void produce(GeometryRows sink) throws IOException;
    }

    static final class GeometryAtom {

        final Key el, ex;

        GeometryAtom(Key el, Key ex) {
            this.el = el;
            this.ex = ex;
        }
    }

    static final class GeometryRows {

        final Map<Key, Integer> newPayloads = new HashMap<>();
        final Map<Long, GeometryAtom> turnedAtoms = new HashMap<>();
        boolean turn;
        final List<Row> rows = new ArrayList<>();
        final TreeMap<Key, Integer> elements = new TreeMap<>(), extras = new TreeMap<>();
        final int[] min = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE },
            max = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        final Map<Integer, Key> existing = new HashMap<>();
        final Map<Long, Key> wrappers = new HashMap<>();
        final List<byte[]> imported = new ArrayList<>();
        private final Map<NativeBlueprintDefinition, Map<Integer, Integer>> importIds = new java.util.IdentityHashMap<>();
        private final Map<NativeBlueprintDefinition, Map<Integer, Key>> externalElements = new java.util.IdentityHashMap<>();
        private final Map<NativeBlueprintEdits, Map<Long, Key>> externalWrappers = new java.util.IdentityHashMap<>();
        private final NativeBlueprintEdits edits;

        GeometryRows(NativeBlueprintEdits edits) {
            this.edits = edits;
        }

        void add(int x, int y, int z, NativeBlueprintEdits from, long descriptor, int rank, boolean rewritten)
            throws IOException {
            need(rows.size() < NativeBlueprintLimits.CELLS, "native geometry edit limit");
            boolean sameSource = from.source() == edits.source();
            var pool = sameSource ? existing : externalElements.computeIfAbsent(from.source(), k -> new HashMap<>());
            int e = NativeBlueprintSpatialView.element(descriptor);
            Key ek = pool.get(e);
            if (ek == null) {
                ek = intern(
                    elements,
                    new Key(
                        from.source()
                            .encodedElement(e)));
                pool.put(e, ek);
            }
            long variant = descriptor | (rewritten ? Long.MIN_VALUE : 0);
            boolean custom = from.changed(rank);
            var forms = from == edits ? wrappers : externalWrappers.computeIfAbsent(from, k -> new HashMap<>());
            Key wk = custom ? null : forms.get(variant);
            if (wk == null) {
                JsonObject w = from.wrapper(rank, descriptor);
                if (rewritten) w.remove("coordinateSource");
                if (!sameSource && w.has("nbt")) {
                    var ids = importIds.computeIfAbsent(from.source(), k -> new HashMap<>());
                    JsonArray rewrittenRefs = new JsonArray();
                    for (JsonElement ref : w.getAsJsonArray("nbt")) {
                        JsonArray pair = ref.getAsJsonArray();
                        int key = pair.get(1)
                            .getAsInt();
                        Integer importedId = ids.get(key);
                        if (importedId == null) {
                            imported.add(
                                from.source()
                                    .payload(key));
                            importedId = edits.source()
                                .payloadCount() + imported.size();
                            ids.put(key, importedId);
                        }
                        JsonArray newPair = new JsonArray();
                        newPair.add(pair.get(0));
                        newPair.add(new JsonPrimitive(importedId));
                        rewrittenRefs.add(newPair);
                    }
                    w.add("nbt", rewrittenRefs);
                }
                wk = intern(extras, new Key(NativeBlueprintJson.encode(w)));
                if (!custom) forms.put(variant, wk);
            }
            addAtom(x, y, z, ek, wk);
        }

        void addAtom(int x, int y, int z, GeometryAtom atom) throws IOException {
            addAtom(x, y, z, atom.el, atom.ex);
        }

        void addAtom(int x, int y, int z, Key ek, Key wk) throws IOException {
            need(rows.size() < NativeBlueprintLimits.CELLS, "native geometry edit limit");
            rows.add(new Row(x, y, z, rows.size(), ek, wk));
            min[0] = Math.min(min[0], x);
            max[0] = Math.max(max[0], x);
            min[1] = Math.min(min[1], y);
            max[1] = Math.max(max[1], y);
            min[2] = Math.min(min[2], z);
            max[2] = Math.max(max[2], z);
        }

        GeometryAtom turned(long descriptor, JsonObject old, JsonObject delta) throws IOException {
            JsonObject w = NativeBlueprintJson.copy(old);
            w.remove("coordinateSource");
            JsonObject e = edits.source()
                .element(NativeBlueprintSpatialView.element(descriptor));
            if (delta.has("metadata")) e.add("metadata", delta.get("metadata"));
            if (delta.has("facing")) w.getAsJsonObject("fields")
                .add("facing", delta.get("facing"));
            if (delta.has("tileNbt") || delta.has("itemNbt")) {
                TreeMap<String, Integer> pointers = new TreeMap<>();
                if (w.has("nbt")) for (var r : w.getAsJsonArray("nbt")) {
                    var pair = r.getAsJsonArray();
                    pointers.put(
                        pair.get(0)
                            .getAsString(),
                        pair.get(1)
                            .getAsInt());
                }
                for (String field : new String[] { "tileNbt", "itemNbt" }) if (delta.has(field)) {
                    w.getAsJsonObject("fields")
                        .remove(field);
                    pointers.remove("/" + field);
                    JsonElement encoded = delta.get(field);
                    if (encoded != null && !encoded.isJsonNull()
                        && !encoded.getAsString()
                            .isEmpty()) {
                        byte[] raw = NativeBlueprintNbt.gunzip(
                            Base64.getDecoder()
                                .decode(encoded.getAsString()),
                            -1);
                        NativeBlueprintNbt.validate(raw);
                        Key k = new Key(raw);
                        Integer id = newPayloads.get(k);
                        if (id == null) {
                            imported.add(raw);
                            id = edits.source()
                                .payloadCount() + imported.size();
                            newPayloads.put(k, id);
                        }
                        pointers.put("/" + field, id);
                    } else if (encoded != null) w.getAsJsonObject("fields")
                        .add(field, encoded);
                }
                w.remove("nbt");
                if (!pointers.isEmpty()) {
                    JsonArray refs = new JsonArray();
                    for (var ref : pointers.entrySet()) {
                        JsonArray r = new JsonArray();
                        r.add(new JsonPrimitive(ref.getKey()));
                        r.add(new JsonPrimitive(ref.getValue()));
                        refs.add(r);
                    }
                    w.add("nbt", refs);
                }
            }
            byte[] json = NativeBlueprintJson.encode(e), element = new byte[json.length + 1];
            System.arraycopy(json, 0, element, 1, json.length);
            element[0] = edits.source()
                .encodedElement(NativeBlueprintSpatialView.element(descriptor))[0];
            return new GeometryAtom(
                intern(elements, new Key(element)),
                intern(extras, new Key(NativeBlueprintJson.encode(w))));
        }
    }

    /** Retains typed raw payloads and original metadata. Drop only payloads no remaining field refers to. */
    static Result editGeometry(NativeBlueprintEdits edits, GeometryInput visit, BooleanSupplier current)
        throws IOException {
        NativeBlueprintDefinition src = edits.source();
        need(src.allowsGeometryMutation(), "unsupported geometry semantics");
        NativeBlueprintMigration codec = new NativeBlueprintMigration(current);
        GeometryRows sink = new GeometryRows(edits);
        visit.produce(sink);
        codec.check();
        need(!sink.rows.isEmpty(), "cannot remove every blueprint cell");
        JsonObject doc = edits.documentWrapper(), regions = edits.regionWrapper();
        if (regions == null) regions = NativeBlueprintDefinition.emptyWrap();
        Set<Integer> refs = new HashSet<>();
        collectRefs(doc, refs);
        collectRefs(regions, refs);
        for (Key k : sink.extras.keySet()) collectRefs(
            NativeBlueprintJson.parse(k.data)
                .getAsJsonObject(),
            refs);
        for (int id : refs) {
            codec.check();
            Key k = new Key(
                id <= src.payloadCount() ? src.payload(id) : sink.imported.get(id - src.payloadCount() - 1));
            Integer first = codec.payloadIds.putIfAbsent(k, id);
            if (first != null) codec.payloadAliases.put(id, first);
        }
        var f = src.frame();
        return codec.emit(
            sink.rows,
            sink.elements,
            sink.extras,
            edits.metadataWrapper(),
            regions,
            doc,
            src.payloadCount() + sink.imported.size(),
            sink.min[0],
            sink.min[1],
            sink.min[2],
            sink.max[0],
            sink.max[1],
            sink.max[2],
            sink.turn ? Math.negateExact(f.anchorZ) : f.anchorX,
            f.anchorY,
            sink.turn ? f.anchorX : f.anchorZ);
    }

    private static void collectRefs(JsonObject wrapper, Set<Integer> refs) {
        if (wrapper.has("nbt")) for (JsonElement pair : wrapper.getAsJsonArray("nbt")) refs.add(
            pair.getAsJsonArray()
                .get(1)
                .getAsInt());
    }

    private static boolean mechanic(String key, boolean compact) {
        return key.equals("previewBinding")
            || compact && (key.equals("encoding") || key.equals("materials") || key.equals("nbt"));
    }

    static Result pack(TreeMap<Integer, byte[]> sections, long cells) throws IOException {
        long size = 64 + 40L * sections.size();
        for (byte[] b : sections.values()) size += b.length;
        need(size <= NativeBlueprintLimits.FILE_BYTES, "native file budget");
        Out o = new Out();
        o.bytes(NativeBlueprintDefinition.MAGIC);
        o.u16(1);
        o.u16(0);
        o.u32(64);
        o.u32(0);
        o.u32(sections.size());
        o.u64(64);
        o.u64(size);
        o.bytes(new byte[24]);
        long at = 64 + 40L * sections.size();
        Map<Integer, Integer> sizes = new TreeMap<>();
        for (Map.Entry<Integer, byte[]> e : sections.entrySet()) {
            int t = e.getKey();
            byte[] b = e.getValue();
            o.u32(t);
            o.u32(t == 3 || t == 4 || t >= 7 ? 3 : 1);
            o.u64(at);
            o.u64(b.length);
            o.u64(b.length);
            o.u32(0);
            o.u32(1);
            at += b.length;
            sizes.put(t, b.length);
        }
        for (byte[] b : sections.values()) o.bytes(b);
        return new Result(o.toByteArray(), cells, sizes);
    }

    private JsonObject wrap(JsonObject fields, List<String> paths) throws IOException {
        JsonObject w = new JsonObject();
        w.add("fields", fields);
        JsonArray refs = new JsonArray();
        paths.sort(
            (a, b) -> compare(
                NativeBlueprintJson.encode(new JsonPrimitive(a)),
                NativeBlueprintJson.encode(new JsonPrimitive(b))));
        for (String path : paths) {
            JsonObject p;
            try {
                p = NativeBlueprintDefinition.pointerParent(fields, path);
            } catch (IOException absent) {
                continue;
            }
            String key = NativeBlueprintDefinition.pointerKey(path);
            JsonElement v = p.get(key);
            if (v == null || v.isJsonNull()
                || !v.isJsonPrimitive()
                || !v.getAsJsonPrimitive()
                    .isString()
                || v.getAsString()
                    .isEmpty())
                continue;
            String text = v.getAsString();
            Integer id = decodedNbt.get(text);
            if (id == null) {
                byte[] compressed;
                try {
                    compressed = Base64.getDecoder()
                        .decode(text);
                } catch (IllegalArgumentException ex) {
                    throw bad("invalid base64 at " + path);
                }
                byte[] raw = NativeBlueprintNbt.gunzip(compressed, -1);
                NativeBlueprintNbt.validate(raw);
                Key k = new Key(raw);
                id = payloadIds.get(k);
                if (id == null) {
                    id = payloadByOldId.size() + 1;
                    payloadIds.put(k, id);
                    payloadByOldId.add(raw);
                    totalNbt += raw.length;
                    need(totalNbt <= NativeBlueprintLimits.NBT_TOTAL, "NBT total");
                }
                decodedNbt.put(text, id);
            }
            p.remove(key);
            JsonArray pair = new JsonArray();
            pair.add(new JsonPrimitive(path));
            pair.add(new JsonPrimitive(id));
            refs.add(pair);
        }
        if (refs.size() > 0) w.add("nbt", refs);
        return w;
    }

    private static void remap(JsonObject wrapper, int[] remap) {
        if (wrapper.has("nbt")) {
            JsonArray refs = new JsonArray();
            for (JsonElement e : wrapper.getAsJsonArray("nbt")) {
                JsonArray a = e.getAsJsonArray(), p = new JsonArray();
                p.add(a.get(0));
                p.add(
                    new JsonPrimitive(
                        remap[a.get(1)
                            .getAsInt()]));
                refs.add(p);
            }
            wrapper.add("nbt", refs);
        }
    }

    private static int coordinate(JsonObject c, String a) throws IOException {
        if (!c.has(a)) return 0;
        return NativeBlueprintDefinition.integer(c.get(a));
    }

    private static JsonObject resolveCell(JsonElement value, boolean compact, JsonArray materials, JsonArray nbt,
        boolean[] um, boolean[] un) throws IOException {
        JsonObject out;
        if (!compact) out = asObject(value, "cell");
        else {
            JsonArray pair = asArray(value, "compact cell");
            need(pair.size() == 2, "compact pair");
            int id = NativeBlueprintDefinition.integer(pair.get(0));
            need(id >= 0 && id < materials.size(), "descriptor reference");
            um[id] = true;
            out = NativeBlueprintJson.copy(asObject(materials.get(id), "descriptor"));
            JsonObject xyz = asObject(pair.get(1), "coordinates");
            for (Map.Entry<String, JsonElement> e : xyz.entrySet()) {
                need(!out.has(e.getKey()), "coordinate/descriptor collision " + e.getKey());
                out.add(e.getKey(), e.getValue());
            }
            for (String key : Arrays.asList("tileNbt", "itemNbt")) resolveRef(out, key, nbt, un);
        }
        return out;
    }

    private static void resolveDocumentNbt(JsonObject doc, JsonObject regions, boolean compact, JsonArray nbt,
        boolean[] used) throws IOException {
        if (!compact) return;
        for (String k : Arrays.asList("processingNbt", "semanticNbt", "quantumGroupingNbt"))
            resolveRef(doc, k, nbt, used);
        if (regions.has("structures") && regions.get("structures")
            .isJsonArray())
            for (JsonElement e : regions.getAsJsonArray("structures"))
                if (e.isJsonObject()) for (String k : Arrays.asList("semanticNbt", "textBoxNbt"))
                    resolveRef(e.getAsJsonObject(), k, nbt, used);
    }

    private static void resolveRef(JsonObject o, String key, JsonArray nbt, boolean[] used) throws IOException {
        JsonElement v = o.get(key);
        if (v != null && !v.isJsonNull()
            && v.isJsonPrimitive()
            && v.getAsJsonPrimitive()
                .isNumber()) {
            int id = NativeBlueprintDefinition.integer(v);
            need(id >= 0 && id < nbt.size(), "NBT reference " + key);
            used[id] = true;
            o.add(key, nbt.get(id));
        }
    }

    private static void addUnused(JsonObject to, String key, JsonArray values, boolean[] used) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < used.length; i++) if (!used[i]) {
            JsonArray pair = new JsonArray();
            pair.add(new JsonPrimitive(i));
            pair.add(values.get(i));
            a.add(pair);
        }
        if (a.size() > 0) to.add(key, a);
    }

    private static JsonObject asObject(JsonElement e, String path) throws IOException {
        need(e != null && e.isJsonObject(), path + " object required");
        return e.getAsJsonObject();
    }

    private static JsonArray asArray(JsonElement e, String path) throws IOException {
        need(e != null && e.isJsonArray(), path + " array required");
        return e.getAsJsonArray();
    }

    private static Key intern(TreeMap<Key, Integer> table, Key candidate) {
        Key old = table.ceilingKey(candidate);
        if (old != null && old.equals(candidate)) return old;
        table.put(candidate, 0);
        return candidate;
    }

    private static final class Key implements Comparable<Key> {

        final byte[] data;

        Key(byte[] b) {
            data = b;
        }

        public int compareTo(Key k) {
            return compare(data, k.data);
        }

        public boolean equals(Object o) {
            return o instanceof Key && compareTo((Key) o) == 0;
        }

        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }

    private static final class Row {

        final int x, y, z, original;
        final Key element;
        Key extra;

        Row(int x, int y, int z, int i, Key e, Key w) {
            this.x = x;
            this.y = y;
            this.z = z;
            original = i;
            element = e;
            extra = w;
        }

        boolean samePosition(Row r) {
            return x == r.x && y == r.y && z == r.z;
        }
    }

    private static final class Geometry {

        byte[] index, bytes;
    }

    private static Geometry geometry(List<Row> rows, Map<Key, Integer> ids, int minX, int minY, int minZ, long sx,
        long sy, long sz) throws IOException {
        need(sx == 0 || sz <= Long.MAX_VALUE / sx, "plane overflow");
        Out geo = new Out();
        List<long[]> layers = new ArrayList<>(), pages = new ArrayList<>();
        int at = 0;
        long totalRuns = 0;
        while (at < rows.size()) {
            int y = rows.get(at).y, firstPage = pages.size();
            long layerCells = 0, layerRuns = 0;
            Out page = new Out();
            long start = -1, cursor = 0, pageCells = 0, pageRuns = 0;
            while (at < rows.size() && rows.get(at).y == y) {
                Row r = rows.get(at);
                long p = ((long) r.z - minZ) * sx + ((long) r.x - minX);
                int element = ids.get(r.element), run = 1;
                while (at + run < rows.size()) {
                    Row next = rows.get(at + run);
                    if (next.y != y || ids.get(next.element) != element
                        || ((long) next.z - minZ) * sx + ((long) next.x - minX) != p + run) break;
                    run++;
                }
                int consumed = 0;
                while (consumed < run) {
                    int len = (int) Math.min(run - consumed, NativeBlueprintLimits.PAGE_CELLS - pageCells);
                    if (len == 0) {
                        pages.add(new long[] { start, cursor, geo.size(), page.size(), pageRuns, pageCells });
                        geo.bytes(page.toByteArray());
                        page = new Out();
                        start = -1;
                        pageCells = pageRuns = 0;
                        continue;
                    }
                    long pos = p + consumed;
                    if (start < 0) {
                        start = cursor = pos;
                    }
                    Out record = new Out();
                    record.var(pos - cursor);
                    record.var(len);
                    record.var(element);
                    if (page.size() + record.size() > NativeBlueprintLimits.PAGE_BYTES) {
                        pages.add(new long[] { start, cursor, geo.size(), page.size(), pageRuns, pageCells });
                        geo.bytes(page.toByteArray());
                        page = new Out();
                        start = -1;
                        pageCells = pageRuns = 0;
                        continue;
                    }
                    page.bytes(record.toByteArray());
                    cursor = pos + len;
                    pageCells += len;
                    pageRuns++;
                    layerCells += len;
                    layerRuns++;
                    consumed += len;
                }
                at += run;
            }
            if (pageCells > 0) {
                pages.add(new long[] { start, cursor, geo.size(), page.size(), pageRuns, pageCells });
                geo.bytes(page.toByteArray());
            }
            layers.add(new long[] { y, firstPage, pages.size() - firstPage, layerRuns, layerCells });
            totalRuns += layerRuns;
        }
        Out index = new Out();
        index.i32(minX);
        index.i32(minY);
        index.i32(minZ);
        index.u32(sx);
        index.u32(sy);
        index.u32(sz);
        index.i32(0);
        index.i32(0);
        index.i32(0);
        index.u32(layers.size());
        index.u32(pages.size());
        index.u32(0);
        index.u64(rows.size());
        index.u64(totalRuns);
        index.u64(80);
        index.u64(80 + 32L * layers.size());
        for (long[] l : layers) {
            index.i32((int) l[0]);
            index.u32(l[1]);
            index.u32(l[2]);
            index.u32(0);
            index.u64(l[3]);
            index.u64(l[4]);
        }
        for (long[] p : pages) {
            index.u64(p[0]);
            index.u64(p[1]);
            index.u64(p[2]);
            index.u32(p[3]);
            index.u32(p[4]);
            index.u32(p[5]);
            index.u32(0);
            index.u64(0);
        }
        need(index.size() <= NativeBlueprintLimits.INDEX_BYTES, "index budget");
        Geometry g = new Geometry();
        g.index = index.toByteArray();
        g.bytes = geo.toByteArray();
        return g;
    }
}
