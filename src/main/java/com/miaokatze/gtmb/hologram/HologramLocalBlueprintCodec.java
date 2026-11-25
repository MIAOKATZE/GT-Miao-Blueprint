// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramPresets.Cell;
import com.miaokatze.gtmb.hologram.HologramPresets.HintArea;
import com.miaokatze.gtmb.hologram.HologramPresets.Preset;
import com.miaokatze.gtmb.hologram.HologramPresets.Structure;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Projects the editable grid from portable JSON. Source metadata travels as JSON without interpretation. */
final class HologramLocalBlueprintCodec {

    private static final Gson MODEL = new Gson();
    private static final Set<String> HEADER = fields(
        "id",
        "name",
        "authorName",
        "authorUuid",
        "createdAt",
        "schema",
        "retainNbt",
        "retainCovers",
        "retainedNbtKeys",
        "prompt",
        "controllerFacing",
        "deidentificationLevel");
    private static final Set<String> CELL = fields(
        "x",
        "y",
        "z",
        "block",
        "metadata",
        "item",
        "damage",
        "count",
        "tileNbt",
        "itemNbt",
        "facing",
        "prompt",
        "excluded",
        "controller",
        "structureId",
        "structureIds",
        "defaultItem",
        "defaultDamage",
        "allowedTargets",
        "structureRole",
        "structureFamily",
        "structureHint",
        "structureCandidatesComplete",
        "structureHatchFacings");
    private static final Set<String> STRUCTURE = fields(
        "id",
        "name",
        "sourceClass",
        "sourceMod",
        "x",
        "y",
        "z",
        "enabled",
        "textBoxNbt",
        "controllerId",
        "main",
        "mainExplicit",
        "noHatches",
        "channels");
    private static final Set<String> HINT = fields("x1", "y1", "z1", "x2", "y2", "z2", "prompt");
    private static final Set<String> ROWS = fields("cells", "palette", "structures", "hintAreas");
    private static final Set<String> PROCESSED = fields(
        "processingNbt",
        "quantumGroupingNbt",
        "semanticNbt",
        "deidentificationVersion",
        "deidentificationChosen",
        "aePolicyVersion",
        "aeIssues",
        "fullyVoucherCovered",
        "sourceItemFrames",
        "sourceItemFramesTruncated");

    private final Map<Object, JsonObject> opaque = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private volatile JsonObject envelope;
    private boolean explicitRetention = true;

    @com.github.bsideup.jabel.Desugar
    record Loaded(Preset document, CompactLayout layout) {}

    @com.github.bsideup.jabel.Desugar
    record CompactLayout(HologramBlueprintCompactCodec.DisplayRows rows, Cell[] materials, int[] occurrences,
        int[] bounds) {}

    Loaded read(byte[] bytes, BooleanSupplier current) throws IOException {
        return read(bytes, current, false);
    }

    Loaded read(byte[] bytes, BooleanSupplier current, boolean stored) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > HologramBlueprintLimits.LOCAL_FILE_BYTES)
            throw new IOException("蓝图文件大小超出范围");
        String text = StandardCharsets.UTF_8.newDecoder()
            .decode(ByteBuffer.wrap(bytes))
            .toString();
        HologramBlueprintCompactCodec.LocalDocument input = HologramBlueprintCompactCodec
            .localDocument(text, current, stored);
        HologramBlueprintCompactCodec.DisplayRows compact = input.packed();
        JsonObject root = input.root();
        try {
            JsonObject blueprint = HologramBlueprintCompactCodec.documentBlueprint(root);
            explicitRetention = blueprint.has("retainNbt");
            int envelopeSchema = number(root, "schema", 2);
            if (envelopeSchema < 1 || envelopeSchema > 2 || root.has("presets")) throw new IOException("蓝图分享文件格式无效");
            Set<String> envelopeFields = compact == null ? fields("schema", "blueprint", "previewBinding")
                : fields("schema", "blueprint", "previewBinding", "encoding", "materials", "nbt");
            envelope = blueprint == root ? new JsonObject()
                : remaining(root, envelopeFields, java.util.Collections.emptySet());
            Set<String> ignored = blueprint == root
                ? fields("cells", "palette", "structures", "hintAreas", "previewBinding")
                : ROWS;
            Preset result = readFields(blueprint, HEADER, ignored, Preset.class);
            int schema = number(blueprint, "schema", 1);
            if (schema != 1 && schema != 2) throw new IOException("蓝图条目格式无效");
            result.schema = schema;
            CompactLayout layout = null;
            if (compact == null) readExpandedCells(result, blueprint);
            else layout = readCompactLayout(result, compact, current);
            Set<String> structures = new HashSet<>();
            for (JsonElement row : rows(blueprint, "structures", 256)) {
                JsonObject entry = object(row);
                boundedPosition(entry, "x", "y", "z");
                Structure structure = readFields(entry, STRUCTURE, java.util.Collections.emptySet(), Structure.class);
                if (structure.id == null || !structures.add(structure.id)) throw new IOException("蓝图结构标识无效");
                result.structures.add(structure);
            }
            for (JsonElement row : rows(blueprint, "hintAreas", 256)) {
                JsonObject entry = object(row);
                boundedPosition(entry, "x1", "y1", "z1");
                boundedPosition(entry, "x2", "y2", "z2");
                result.hintAreas.add(readFields(entry, HINT, java.util.Collections.emptySet(), HintArea.class));
            }
            if (stored && !input.compact()) throw new HologramCompactRequiredException();
            return new Loaded(result, layout);
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图编辑数据格式无效", invalid);
        }
    }

    private void readExpandedCells(Preset result, JsonObject blueprint) throws IOException {
        Set<Position> positions = new HashSet<>();
        for (JsonElement row : rows(blueprint, "cells", HologramBlueprintLimits.MAX_CELLS))
            addCell(result, positions, readExpandedCell(object(row)));
        for (JsonElement row : rows(blueprint, "palette", 4096)) result.palette.add(readCell(object(row)));
    }

    private CompactLayout readCompactLayout(Preset result, HologramBlueprintCompactCodec.DisplayRows compact,
        BooleanSupplier current) throws IOException {
        int count = compact.cells();
        LongOpenHashSet positions = new LongOpenHashSet(count);
        Cell[] materials = new Cell[compact.materials()];
        int[] occurrences = new int[materials.length];
        int[] bounds = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE };
        for (int row = 0; row < count; row++) {
            if ((row & 255) == 0 && !current.getAsBoolean()) throw new IOException("蓝图读取已取消");
            int material = compact.materialIndex(row);
            occurrences[material]++;
            if (materials[material] == null) materials[material] = readCell(compact.material(material));
            int x = compact.coordinate(row, 0), y = compact.coordinate(row, 1), z = compact.coordinate(row, 2);
            boundedPosition(x, y, z);
            // The checked document bounds fit signed X/Z in 27 bits and signed Y in 10 bits.
            long position = (x & 0x7ffffffL) << 37 | (z & 0x7ffffffL) << 10 | (y & 0x3ffL);
            if (!positions.add(position)) throw new IOException("蓝图格坐标重复");
            bounds[0] = Math.min(bounds[0], x);
            bounds[1] = Math.min(bounds[1], y);
            bounds[2] = Math.min(bounds[2], z);
            bounds[3] = Math.max(bounds[3], x);
            bounds[4] = Math.max(bounds[4], y);
            bounds[5] = Math.max(bounds[5], z);
        }
        for (JsonElement row : rows(compact.blueprint, "palette", 4096))
            result.palette.add(readCell(compact.paletteCell(row)));
        return new CompactLayout(compact, materials, occurrences, bounds);
    }

    void materialize(Loaded source, BooleanSupplier current) throws IOException {
        if (source.layout() != null) source.document().cells = detachedCells(source.layout(), current);
    }

    List<Cell> detachedCells(CompactLayout layout, BooleanSupplier current) throws IOException {
        HologramBlueprintCompactCodec.DisplayRows rows = layout.rows();
        int count = rows.cells();
        java.util.ArrayList<Cell> cells = new java.util.ArrayList<>(count);
        JsonObject[] extensions = new JsonObject[layout.materials().length];
        for (int material = 0; material < extensions.length; material++)
            if (layout.materials()[material] != null) extensions[material] = opaque.get(layout.materials()[material]);
        for (int row = 0; row < count; row++) {
            if ((row & 255) == 0 && !current.getAsBoolean()) throw new IOException("蓝图读取已取消");
            int material = rows.materialIndex(row);
            Cell cell = rowCopy(layout, row, material);
            if (extensions[material] != null) opaque.put(cell, extensions[material]);
            cells.add(cell);
        }
        return cells;
    }

    /** Only the selected ordinal needs a disposable cell for metadata inspection. */
    static Cell rowCopy(CompactLayout layout, int ordinal) {
        return rowCopy(
            layout,
            ordinal,
            layout.rows()
                .materialIndex(ordinal));
    }

    private static Cell rowCopy(CompactLayout layout, int ordinal, int material) {
        var rows = layout.rows();
        Cell cell = HologramSnapshotMetadataSource.copyCell(layout.materials()[material]);
        cell.x = rows.coordinate(ordinal, 0);
        cell.y = rows.coordinate(ordinal, 1);
        cell.z = rows.coordinate(ordinal, 2);
        return cell;
    }

    private static void addCell(Preset result, Set<Position> positions, Cell cell) throws IOException {
        if (!positions.add(new Position(cell.x, cell.y, cell.z))) throw new IOException("蓝图格坐标重复");
        result.cells.add(cell);
    }

    private Cell readExpandedCell(JsonObject row) throws IOException {
        Cell cell = new Cell();
        cell.x = number(row, "x", 0);
        cell.y = number(row, "y", 0);
        cell.z = number(row, "z", 0);
        boundedPosition(cell.x, cell.y, cell.z);
        cell.metadata = number(row, "metadata", 0);
        cell.damage = number(row, "damage", 0);
        cell.count = number(row, "count", cell.count);
        cell.facing = number(row, "facing", cell.facing);
        cell.defaultDamage = number(row, "defaultDamage", 0);
        JsonObject retained = null;
        for (Map.Entry<String, JsonElement> entry : row.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            switch (key) {
                case "x", "y", "z", "metadata", "damage", "count", "facing", "defaultDamage" -> {
                    // Already checked in the legacy error order, before any Gson coercions.
                }
                case "item", "block", "defaultItem", "tileNbt", "itemNbt", "prompt", "structureId", "structureRole", "structureFamily", "structureHint" -> {
                    if (value.isJsonNull()) continue;
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
                        .isString()) return readGsonCell(row);
                    String text = value.getAsString();
                    switch (key) {
                        case "item" -> cell.item = text;
                        case "block" -> cell.block = text;
                        case "defaultItem" -> cell.defaultItem = text;
                        case "tileNbt" -> cell.tileNbt = text;
                        case "itemNbt" -> cell.itemNbt = text;
                        case "prompt" -> cell.prompt = text;
                        case "structureId" -> cell.structureId = text;
                        case "structureRole" -> cell.structureRole = text;
                        case "structureFamily" -> cell.structureFamily = text;
                        case "structureHint" -> cell.structureHint = text;
                        default -> {
                            return readGsonCell(row);
                        }
                    }
                }
                case "excluded", "controller", "structureCandidatesComplete" -> {
                    if (value.isJsonNull()) continue;
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
                        .isBoolean()) return readGsonCell(row);
                    boolean flag = value.getAsBoolean();
                    switch (key) {
                        case "excluded" -> cell.excluded = flag;
                        case "controller" -> cell.controller = flag;
                        case "structureCandidatesComplete" -> cell.structureCandidatesComplete = flag;
                        default -> {
                            return readGsonCell(row);
                        }
                    }
                }
                case "structureIds" -> {
                    if (value.isJsonNull()) continue;
                    if (!value.isJsonArray() || value.getAsJsonArray()
                        .size() != 0) return readGsonCell(row);
                    // A serialized empty list is still an independently mutable list, not null.
                    cell.structureIds = new ArrayList<>();
                }
                case "structureHatchFacings" -> {
                    if (!value.isJsonNull()) return readGsonCell(row);
                }
                case "allowedTargets" -> {
                    // The old mapper also preserves the raw array/null and future nested target data.
                    return readGsonCell(row);
                }
                default -> {
                    // A newly whitelisted field opts into Gson until the scalar path explicitly supports it.
                    if (CELL.contains(key)) return readGsonCell(row);
                    if (retained == null) retained = new JsonObject();
                    retained.add(key, value);
                }
            }
        }
        if (retained != null) opaque.put(cell, retained);
        return cell;
    }

    private Cell readCell(JsonObject row) throws IOException {
        boundedPosition(row, "x", "y", "z");
        for (String field : new String[] { "metadata", "damage", "count", "facing", "defaultDamage" })
            number(row, field, 0);
        return readGsonCell(row);
    }

    private Cell readGsonCell(JsonObject row) {
        Cell cell = readFields(row, CELL, java.util.Collections.emptySet(), Cell.class);
        // Keep future metadata inside stored targets even though the DTO only exposes material identity.
        if (row.has("allowedTargets")) opaque.computeIfAbsent(cell, unused -> new JsonObject())
            .add("allowedTargets", row.get("allowedTargets"));
        return cell;
    }

    private <T> T readFields(JsonObject row, Set<String> keys, Set<String> ignored, Class<T> type) {
        JsonObject projected = new JsonObject();
        for (String key : keys) if (row.has(key)) projected.add(key, row.get(key));
        T result = MODEL.fromJson(projected, type);
        JsonObject retained = remaining(row, keys, ignored);
        if (!retained.entrySet()
            .isEmpty()) opaque.put(result, retained);
        return result;
    }

    boolean retainedMaterialPolicy(Preset source) {
        JsonObject retained = opaque.get(source);
        return retained != null
            && (!emptyString(retained.get("processingNbt")) || !emptyString(retained.get("semanticNbt"))
                || !emptyString(retained.get("quantumGroupingNbt")));
    }

    private static String stringValue(JsonElement value) {
        return value != null && value.isJsonPrimitive()
            && value.getAsJsonPrimitive()
                .isString() ? value.getAsString() : null;
    }

    private static boolean emptyString(JsonElement value) {
        return value == null || value.isJsonNull() || "".equals(stringValue(value));
    }

    void copied(Object from, Object to) {
        JsonObject retained = opaque.get(from);
        if (retained != null) opaque.put(to, retained);
    }

    /** The native refresh owns the replacement target list; other portable extensions remain attached. */
    void replacedNativeTargets(Cell cell) {
        JsonObject retained = opaque.get(cell);
        if (retained == null || !retained.has("allowedTargets")) return;
        JsonObject extensions = remaining(retained, fields("allowedTargets"), java.util.Collections.emptySet());
        if (extensions.entrySet()
            .isEmpty()) opaque.remove(cell);
        else opaque.put(cell, extensions);
    }

    void adoptSaved(HologramLocalBlueprintCodec saved) {
        envelope = saved.envelope;
        opaque.putAll(saved.opaque);
    }

    boolean explicitRetention() {
        return explicitRetention;
    }

    @com.github.bsideup.jabel.Desugar
    record SaveIdentity(int level, boolean chosen) {}

    /**
     * Read only the supported identity scalars. Processed fields remain opaque until genuine reprocessing.
     * An unrecognized historical/future header stays on the unmodified legacy Save path, never a new policy.
     */
    SaveIdentity saveIdentity(Preset source) {
        JsonObject retained = opaque.get(source);
        int version = knownVersion(
            retained,
            "deidentificationVersion",
            source.deidentificationVersion,
            HologramDeidentification.VERSION);
        int ae = knownVersion(retained, "aePolicyVersion", source.aePolicyVersion, HologramAePolicy.VERSION);
        if (version < 0 || ae < 0 || source.deidentificationLevel < 0 || source.deidentificationLevel > 10) return null;
        JsonElement chosen = retained == null ? null : retained.get("deidentificationChosen");
        if (chosen != null && (!chosen.isJsonPrimitive() || !chosen.getAsJsonPrimitive()
            .isBoolean())) return null;
        return new SaveIdentity(
            HologramDeidentification.level(source.deidentificationLevel, version, ae),
            chosen == null ? source.deidentificationChosen : chosen.getAsBoolean());
    }

    private static int knownVersion(JsonObject retained, String key, int fallback, int max) {
        JsonElement value = retained == null ? null : retained.get(key);
        if (value == null) return fallback >= 0 && fallback <= max ? fallback : -1;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
            .isNumber()) return -1;
        for (int i = 0; i <= max; i++) if (Integer.toString(i)
            .equals(value.getAsString())) return i;
        return -1;
    }

    void processingMetadata(Preset original, Preset source) {
        JsonObject retained = opaque.get(original);
        if (retained == null) return;
        JsonObject projected = new JsonObject();
        for (String key : PROCESSED) if (retained.has(key)) projected.add(key, retained.get(key));
        Preset stored = MODEL.fromJson(projected, Preset.class);
        if (projected.has("processingNbt")) source.processingNbt = stored.processingNbt;
        if (projected.has("quantumGroupingNbt")) source.quantumGroupingNbt = stored.quantumGroupingNbt;
        if (projected.has("semanticNbt")) source.semanticNbt = stored.semanticNbt;
        if (projected.has("deidentificationVersion")) source.deidentificationVersion = stored.deidentificationVersion;
        if (projected.has("deidentificationChosen")) source.deidentificationChosen = stored.deidentificationChosen;
        if (projected.has("aePolicyVersion")) source.aePolicyVersion = stored.aePolicyVersion;
        if (projected.has("aeIssues")) source.aeIssues = stored.aeIssues;
        if (projected.has("fullyVoucherCovered")) source.fullyVoucherCovered = stored.fullyVoucherCovered;
        if (projected.has("sourceItemFrames")) source.sourceItemFrames = stored.sourceItemFrames;
        if (projected.has("sourceItemFramesTruncated"))
            source.sourceItemFramesTruncated = stored.sourceItemFramesTruncated;
    }

    void acceptProcessed(Preset before, Preset after) {
        JsonObject retained = opaque.get(before);
        if (retained != null) opaque.put(after, remaining(retained, PROCESSED, java.util.Collections.emptySet()));
        for (int i = 0; i < before.cells.size() && i < after.cells.size(); i++)
            copied(before.cells.get(i), after.cells.get(i));
        for (int i = 0; i < before.palette.size() && i < after.palette.size(); i++)
            copied(before.palette.get(i), after.palette.get(i));
        for (int i = 0; i < before.structures.size() && i < after.structures.size(); i++)
            copied(before.structures.get(i), after.structures.get(i));
        for (HintArea hint : after.hintAreas) for (HintArea original : before.hintAreas) {
            if (hint.x1 == original.x1 && hint.y1 == original.y1
                && hint.z1 == original.z1
                && hint.x2 == original.x2
                && hint.y2 == original.y2
                && hint.z2 == original.z2) copied(original, hint);
        }
    }

    boolean hasOriginalVersion(Preset preset) {
        JsonObject retained = opaque.get(preset);
        return preset.nativeBlueprint != null || retained != null && retained.has("nativeBlueprint")
            && !retained.get("nativeBlueprint")
                .isJsonNull();
    }

    com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintMigration.Result writeNative(Preset preset, CompactLayout geometry,
        java.util.function.BooleanSupplier current) throws IOException {
        JsonObject header = HologramBlueprintCompactCodec.metadata(preset);
        merge(preset, header);
        header.add("structures", rows(preset.structures));
        header.add("hintAreas", rows(preset.hintAreas));
        header.add("palette", rows(preset.palette));
        JsonObject extensions = new JsonObject();
        if (envelope != null) for (Map.Entry<String, JsonElement> entry : envelope.entrySet()) {
            switch (entry.getKey()) {
                case "schema", "blueprint", "presets", "previewBinding", "encoding", "materials", "nbt" -> {}
                default -> extensions.add(entry.getKey(), entry.getValue());
            }
        }
        if (geometry == null && preset.cells instanceof HologramLiteralCaptureRows packed) {
            return com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintMigration.author(
                header,
                extensions,
                packed.size(),
                new com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintMigration.IndexedAuthoredCell() {

                    public int descriptor(int rank) {
                        return packed.descriptor(rank);
                    }

                    public int coordinate(int rank, int axis) {
                        return packed.coordinate(rank, axis);
                    }

                    public JsonObject at(int rank) {
                        return row(packed.get(rank));
                    }
                },
                current);
        }
        return com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintMigration.author(
            header,
            extensions,
            geometry == null ? preset.cells.size()
                : geometry.rows()
                    .cells(),
            rank -> {
                if (geometry == null) return row(preset.cells.get(rank));
                var layout = geometry.rows();
                JsonObject target = row(geometry.materials()[layout.materialIndex(rank)]);
                target.addProperty("x", layout.coordinate(rank, 0));
                target.addProperty("y", layout.coordinate(rank, 1));
                target.addProperty("z", layout.coordinate(rank, 2));
                return target;
            },
            current);
    }

    private JsonObject row(Object entry) {
        JsonObject result = MODEL.toJsonTree(entry)
            .getAsJsonObject();
        merge(entry, result);
        return result;
    }

    byte[] write(Preset preset, CompactLayout geometry) throws IOException {
        if (geometry == null) return write(preset);
        JsonObject header = HologramBlueprintCompactCodec.metadata(preset);
        merge(preset, header);
        header.add("structures", rows(preset.structures));
        header.add("hintAreas", rows(preset.hintAreas));
        return HologramBlueprintCompactCodec.writeLocalPacked(
            envelope == null ? new JsonObject() : envelope,
            header,
            geometry.rows(),
            rows(preset.palette));
    }

    byte[] write(Preset preset) throws IOException {
        JsonObject blueprint = HologramBlueprintCompactCodec.metadata(preset);
        merge(preset, blueprint);
        blueprint.add("cells", rows(preset.cells));
        blueprint.add("palette", rows(preset.palette));
        blueprint.add("structures", rows(preset.structures));
        blueprint.add("hintAreas", rows(preset.hintAreas));
        JsonObject output = new JsonObject();
        JsonObject sourceEnvelope = envelope;
        if (sourceEnvelope != null) for (Map.Entry<String, JsonElement> field : sourceEnvelope.entrySet())
            output.add(field.getKey(), field.getValue());
        output.addProperty("schema", 2);
        output.add("blueprint", blueprint);
        return HologramBlueprintCompactCodec.encodeLocalDocument(output);
    }

    private JsonArray rows(Iterable<?> entries) {
        JsonArray rows = new JsonArray();
        for (Object entry : entries) {
            rows.add(row(entry));
        }
        return rows;
    }

    private void merge(Object entry, JsonObject output) {
        JsonObject retained = opaque.get(entry);
        if (retained != null) for (Map.Entry<String, JsonElement> field : retained.entrySet()) {
            if ("nativeBlueprint".equals(field.getKey()) && field.getValue()
                .isJsonNull() && output.has(field.getKey())) continue;
            output.add(field.getKey(), field.getValue());
        }
    }

    private static JsonObject remaining(JsonObject source, Set<String> known, Set<String> ignored) {
        JsonObject retained = new JsonObject();
        for (Map.Entry<String, JsonElement> field : source.entrySet())
            if (!known.contains(field.getKey()) && !ignored.contains(field.getKey()))
                retained.add(field.getKey(), field.getValue());
        return retained;
    }

    private static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("蓝图条目必须是 JSON 对象");
        return value.getAsJsonObject();
    }

    private static JsonArray rows(JsonObject value, String name, int limit) throws IOException {
        JsonElement found = value.get(name);
        if (found == null || found.isJsonNull()) return new JsonArray();
        if (!found.isJsonArray() || found.getAsJsonArray()
            .size() > limit) throw new IOException("蓝图列表超出上限");
        return found.getAsJsonArray();
    }

    private static int number(JsonObject row, String key, int fallback) throws IOException {
        JsonElement value = row.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
            .isNumber()) throw new IOException("蓝图整数无效");
        String text = value.getAsString();
        int first = text.startsWith("-") ? 1 : 0;
        int digits = text.length() - first;
        if (digits < 1 || digits > 10) throw new IOException("蓝图整数无效");
        for (int index = first; index < text.length(); index++) {
            char digit = text.charAt(index);
            if (digit < '0' || digit > '9') throw new IOException("蓝图整数无效");
        }
        return Integer.parseInt(text);
    }

    private static void boundedPosition(JsonObject row, String x, String y, String z) throws IOException {
        boundedPosition(number(row, x, 0), number(row, y, 0), number(row, z, 0));
    }

    private static void boundedPosition(int x, int y, int z) throws IOException {
        if (Math.abs((long) x) > 60000000 || Math.abs((long) y) > 256 || Math.abs((long) z) > 60000000)
            throw new IOException("蓝图坐标超出范围");
    }

    private static Set<String> fields(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    @com.github.bsideup.jabel.Desugar
    private record Position(int x, int y, int z) {}
}
