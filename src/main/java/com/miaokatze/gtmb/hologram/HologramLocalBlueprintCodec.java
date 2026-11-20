// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramPresets.Cell;
import com.miaokatze.gtmb.hologram.HologramPresets.HintArea;
import com.miaokatze.gtmb.hologram.HologramPresets.Preset;
import com.miaokatze.gtmb.hologram.HologramPresets.Structure;

/** Projects the editable grid from portable JSON. Source metadata travels as JSON without interpretation. */
final class HologramLocalBlueprintCodec {

    private static final Gson MODEL = new Gson();
    private static final Set<String> HEADER = fields("id", "name", "authorName", "authorUuid", "createdAt", "schema",
        "retainNbt", "retainCovers", "retainedNbtKeys", "prompt", "controllerFacing", "deidentificationLevel");
    private static final Set<String> CELL = fields("x", "y", "z", "block", "metadata", "item", "damage", "count",
        "tileNbt", "itemNbt", "facing", "prompt", "excluded", "controller", "structureId", "structureIds",
        "defaultItem", "defaultDamage", "allowedTargets", "structureRole", "structureFamily", "structureHint",
        "structureCandidatesComplete", "structureHatchFacings");
    private static final Set<String> STRUCTURE = fields("id", "name", "sourceClass", "sourceMod", "x", "y", "z",
        "enabled", "textBoxNbt", "controllerId", "main", "mainExplicit", "noHatches", "channels");
    private static final Set<String> HINT = fields("x1", "y1", "z1", "x2", "y2", "z2", "prompt");
    private static final Set<String> ROWS = fields("cells", "palette", "structures", "hintAreas");
    private static final Set<String> PROCESSED = fields("processingNbt", "quantumGroupingNbt", "semanticNbt",
        "deidentificationVersion", "deidentificationChosen", "aePolicyVersion", "aeIssues", "fullyVoucherCovered",
        "sourceItemFrames", "sourceItemFramesTruncated");

    private final Map<Object, JsonObject> opaque = new WeakHashMap<>();
    private JsonObject envelope;

    Preset read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > HologramBlueprintArtifact.MAX_JSON_BYTES)
            throw new IOException("蓝图文件大小超出范围");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        JsonObject root = HologramBlueprintCompactCodec.parse(text, HologramBlueprintArtifact.MAX_JSON_BYTES);
        try {
            JsonObject blueprint = HologramBlueprintCompactCodec.documentBlueprint(root);
            int envelopeSchema = number(root, "schema", 2);
            if (envelopeSchema < 1 || envelopeSchema > 2 || root.has("presets"))
                throw new IOException("蓝图分享文件格式无效");
            envelope = blueprint == root ? new JsonObject()
                : remaining(root, fields("schema", "blueprint", "previewBinding"), java.util.Collections.emptySet());
            Set<String> ignored = blueprint == root ? fields("cells", "palette", "structures", "hintAreas", "previewBinding")
                : ROWS;
            Preset result = readFields(blueprint, HEADER, ignored, Preset.class);
            int schema = number(blueprint, "schema", 1);
            if (schema != 1 && schema != 2) throw new IOException("蓝图条目格式无效");
            result.schema = schema;
            Set<Position> positions = new HashSet<>();
            for (JsonElement row : rows(blueprint, "cells", HologramBlueprintLimits.MAX_CELLS)) {
                Cell cell = readCell(object(row));
                if (!positions.add(new Position(cell.x, cell.y, cell.z))) throw new IOException("蓝图格坐标重复");
                result.cells.add(cell);
            }
            for (JsonElement row : rows(blueprint, "palette", 4096)) result.palette.add(readCell(object(row)));
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
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图编辑数据格式无效", invalid);
        }
    }

    private Cell readCell(JsonObject row) throws IOException {
        boundedPosition(row, "x", "y", "z");
        for (String field : new String[] { "metadata", "damage", "count", "facing", "defaultDamage" })
            number(row, field, 0);
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
        if (!retained.entrySet().isEmpty()) opaque.put(result, retained);
        return result;
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
        if (extensions.entrySet().isEmpty()) opaque.remove(cell);
        else opaque.put(cell, extensions);
    }

    void adoptSaved(HologramLocalBlueprintCodec saved) {
        envelope = saved.envelope;
        opaque.putAll(saved.opaque);
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
        if (projected.has("sourceItemFramesTruncated")) source.sourceItemFramesTruncated = stored.sourceItemFramesTruncated;
    }

    void acceptProcessed(Preset before, Preset after) {
        JsonObject retained = opaque.get(before);
        if (retained != null) opaque.put(after, remaining(retained, PROCESSED, java.util.Collections.emptySet()));
        for (int i = 0; i < before.cells.size() && i < after.cells.size(); i++) copied(before.cells.get(i), after.cells.get(i));
        for (int i = 0; i < before.palette.size() && i < after.palette.size(); i++) copied(before.palette.get(i), after.palette.get(i));
        for (int i = 0; i < before.structures.size() && i < after.structures.size(); i++)
            copied(before.structures.get(i), after.structures.get(i));
        for (HintArea hint : after.hintAreas) for (HintArea original : before.hintAreas) {
            if (hint.x1 == original.x1 && hint.y1 == original.y1 && hint.z1 == original.z1 && hint.x2 == original.x2
                && hint.y2 == original.y2 && hint.z2 == original.z2) copied(original, hint);
        }
    }

    boolean hasOriginalVersion(Preset preset) {
        JsonObject retained = opaque.get(preset);
        return preset.nativeBlueprint != null || retained != null && retained.has("nativeBlueprint")
            && !retained.get("nativeBlueprint").isJsonNull();
    }

    byte[] write(Preset preset) throws IOException {
        JsonObject blueprint = HologramBlueprintCompactCodec.metadata(preset);
        merge(preset, blueprint);
        blueprint.add("cells", rows(preset.cells));
        blueprint.add("palette", rows(preset.palette));
        blueprint.add("structures", rows(preset.structures));
        blueprint.add("hintAreas", rows(preset.hintAreas));
        JsonObject output = new JsonObject();
        if (envelope != null)
            for (Map.Entry<String, JsonElement> field : envelope.entrySet()) output.add(field.getKey(), field.getValue());
        output.addProperty("schema", 2);
        output.add("blueprint", blueprint);
        return HologramBlueprintCompactCodec.encodeLocalDocument(output);
    }

    private JsonArray rows(Iterable<?> entries) {
        JsonArray rows = new JsonArray();
        for (Object entry : entries) {
            JsonObject row = MODEL.toJsonTree(entry).getAsJsonObject();
            merge(entry, row);
            rows.add(row);
        }
        return rows;
    }

    private void merge(Object entry, JsonObject output) {
        JsonObject retained = opaque.get(entry);
        if (retained != null)
            for (Map.Entry<String, JsonElement> field : retained.entrySet()) {
                if ("nativeBlueprint".equals(field.getKey()) && field.getValue().isJsonNull()
                    && output.has(field.getKey())) continue;
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
        if (!found.isJsonArray() || found.getAsJsonArray().size() > limit)
            throw new IOException("蓝图列表超出上限");
        return found.getAsJsonArray();
    }

    private static int number(JsonObject row, String key, int fallback) throws IOException {
        JsonElement value = row.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
            || !value.getAsString().matches("-?[0-9]{1,10}")) throw new IOException("蓝图整数无效");
        return Integer.parseInt(value.getAsString());
    }

    private static void boundedPosition(JsonObject row, String x, String y, String z) throws IOException {
        if (Math.abs((long) number(row, x, 0)) > 60000000
            || Math.abs((long) number(row, y, 0)) > 256
            || Math.abs((long) number(row, z, 0)) > 60000000) throw new IOException("蓝图坐标超出范围");
    }

    private static Set<String> fields(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    @com.github.bsideup.jabel.Desugar
    private record Position(int x, int y, int z) {}
}
