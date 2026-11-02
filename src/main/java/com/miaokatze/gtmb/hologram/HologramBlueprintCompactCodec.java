package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/** Lossless disk JSON dictionaries. References have fixed positions and cannot form recursive graphs. */
public final class HologramBlueprintCompactCodec {

    private static final String ENCODING = "gtmb-dictionary-1";
    static final int MAX_DICTIONARY = 65536;
    static final int MAX_CELLS = HologramBlueprintLimits.MAX_CELLS;
    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .create();
    private static final Gson MODEL = new GsonBuilder().create();
    private static final Gson METADATA = new GsonBuilder().addSerializationExclusionStrategy(new ExclusionStrategy() {

        @Override
        public boolean shouldSkipField(FieldAttributes field) {
            return field.getDeclaringClass() == HologramPresets.Preset.class && (field.getName()
                .equals("cells")
                || field.getName()
                    .equals("palette"));
        }

        @Override
        public boolean shouldSkipClass(Class<?> type) {
            return false;
        }
    })
        .create();

    private HologramBlueprintCompactCodec() {}

    /** Pure structural admission, shared by preview and semantic identity. Does not validate game objects. */
    public static JsonObject parse(byte[] bytes) throws IOException {
        return parse(bytes, HologramBlueprintArtifact.MAX_JSON_BYTES);
    }

    public static JsonObject parse(byte[] bytes, int limit) throws IOException {
        if (bytes == null || limit < 1
            || limit > HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES
            || bytes.length > limit) throw new IOException("Blueprint JSON byte budget");
        return parse(new String(bytes, StandardCharsets.UTF_8), limit);
    }

    /** Storage-only migration. Preserve every JSON field and NBT string without model/policy conversion. */
    public static byte[] recompress(byte[] bytes, int limit) throws IOException {
        return recompress(bytes, limit, () -> true);
    }

    public static byte[] recompress(byte[] bytes, int limit, java.util.function.BooleanSupplier current)
        throws IOException {
        compressionCurrent(current);
        JsonObject original = parse(bytes, limit);
        JsonObject root = cloneObject(original);
        List<JsonObject> blueprints = new ArrayList<>();
        if (root.has("blueprint") && !root.has("presets")) blueprints.add(object(root.get("blueprint")));
        else if (root.has("presets") && !root.has("blueprint")) {
            for (JsonElement row : array(root.get("presets"))) blueprints.add(object(row));
        } else throw new IOException("Missing or ambiguous blueprint container");
        // These names belong to the compact envelope. Never silently replace legacy extension fields.
        if (root.has("materials") || root.has("nbt")) throw new IOException("Reserved compact envelope fields");
        Map<String, Integer> materialIds = new LinkedHashMap<>(), nbtIds = new LinkedHashMap<>();
        JsonArray materials = new JsonArray(), nbt = new JsonArray();
        for (JsonObject blueprint : blueprints) {
            internRawNbt(blueprint, nbtIds, nbt);
            for (String field : new String[] { "cells", "palette" }) {
                JsonElement rows = blueprint.get(field);
                if (rows == null || rows.isJsonNull()) continue;
                JsonArray compressed = new JsonArray();
                for (JsonElement row : array(rows)) {
                    compressionCurrent(current);
                    JsonObject descriptor = cloneObject(object(row)), coordinates = new JsonObject();
                    for (String coordinate : new String[] { "x", "y", "z" })
                        if (descriptor.has(coordinate)) coordinates.add(coordinate, descriptor.remove(coordinate));
                    internRawNbt(descriptor, nbtIds, nbt);
                    String key = descriptor.toString();
                    Integer id = materialIds.get(key);
                    if (id == null) {
                        id = materialIds.size();
                        if (id >= MAX_DICTIONARY) throw new IOException("Blueprint material dictionary budget");
                        materialIds.put(key, id);
                        materials.add(descriptor);
                    }
                    JsonArray reference = new JsonArray();
                    reference.add(new JsonPrimitive(id));
                    reference.add(coordinates);
                    compressed.add(reference);
                }
                blueprint.add(field, compressed);
            }
        }
        root.addProperty("encoding", ENCODING);
        root.add("materials", materials);
        root.add("nbt", nbt);
        byte[] result = HologramBlueprintSharing.encodeBounded(GSON, root, limit, () -> compressionCurrent(current));
        if (!original.equals(parse(result, limit))) throw new IOException("Blueprint compression changed JSON content");
        compressionCurrent(current);
        return result;
    }

    private static void compressionCurrent(java.util.function.BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw new IOException("蓝图升级已取消");
    }

    private static void internRawNbt(JsonObject object, Map<String, Integer> ids, JsonArray dictionary)
        throws IOException {
        for (String field : new String[] { "tileNbt", "itemNbt", "processingNbt", "quantumGroupingNbt",
            "semanticNbt" }) {
            JsonElement value = object.get(field);
            if (value == null || value.isJsonNull()) continue;
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
                .isString()) throw new IOException("Invalid blueprint NBT text");
            String text = value.getAsString();
            if (text.length() > 2 * 1024 * 1024) throw new IOException("Blueprint NBT text budget");
            Integer id = ids.get(text);
            if (id == null) {
                id = ids.size();
                if (id >= MAX_DICTIONARY) throw new IOException("Blueprint NBT dictionary budget");
                ids.put(text, id);
                dictionary.add(new JsonPrimitive(text));
            }
            object.addProperty(field, id);
        }
    }

    static JsonObject parse(String text, int limit) throws IOException {
        HologramBlueprintSharing.boundedJson(text, limit);
        try {
            JsonElement value = new JsonParser().parse(text);
            if (!value.isJsonObject()) throw new IOException("Blueprint JSON root must be an object");
            JsonObject root = value.getAsJsonObject();
            if (!root.has("encoding")) {
                List<JsonObject> legacy = new ArrayList<>();
                if (root.has("blueprint")) legacy.add(object(root.get("blueprint")));
                else if (root.has("presets")) {
                    JsonArray presets = array(root.get("presets"));
                    if (presets.size() > 32) throw new IOException("Blueprint collection entry budget");
                    for (JsonElement preset : presets) legacy.add(object(preset));
                }
                for (JsonObject blueprint : legacy) {
                    for (String field : new String[] { "cells", "palette" }) {
                        JsonElement rows = blueprint.get(field);
                        if (rows != null && !rows.isJsonNull()
                            && array(rows).size() > (field.equals("palette") ? 4096 : MAX_CELLS))
                            throw new IOException("Blueprint cell count budget");
                    }
                    if (limit > HologramBlueprintArtifact.MAX_JSON_BYTES
                        && measure(blueprint) > HologramBlueprintArtifact.MAX_JSON_BYTES)
                        throw new IOException("Expanded blueprint byte budget");
                }
                return root;
            }
            JsonElement encoding = root.get("encoding");
            if (!encoding.isJsonPrimitive() || !encoding.getAsJsonPrimitive()
                .isString() || !ENCODING.equals(encoding.getAsString()))
                throw new IOException("Unsupported blueprint encoding");
            JsonArray materials = array(root.get("materials")), nbt = array(root.get("nbt"));
            if (materials.size() > MAX_DICTIONARY || nbt.size() > MAX_DICTIONARY)
                throw new IOException("Blueprint dictionary budget");
            long[] nbtSizes = new long[nbt.size()];
            for (int i = 0; i < nbt.size(); i++) {
                JsonElement item = nbt.get(i);
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive()
                    .isString()
                    || item.getAsString()
                        .length() > 2 * 1024 * 1024)
                    throw new IOException("Invalid blueprint NBT dictionary");
                nbtSizes[i] = measure(item);
            }
            long[] sizes = new long[materials.size()];
            for (int i = 0; i < materials.size(); i++) {
                JsonObject material = object(materials.get(i));
                if (material.has("x") || material.has("y") || material.has("z"))
                    throw new IOException("Position in blueprint material dictionary");
                if (depth(material) > 29) throw new IOException("Expanded blueprint depth budget");
                sizes[i] = objectSize(material, nbtSizes);
            }
            if (root.has("blueprint") == root.has("presets"))
                throw new IOException("Ambiguous compact blueprint container");
            List<JsonObject> blueprints = new ArrayList<>();
            if (root.has("blueprint")) blueprints.add(object(root.get("blueprint")));
            else {
                JsonArray presets = array(root.get("presets"));
                if (presets.size() > 32) throw new IOException("Blueprint collection entry budget");
                for (JsonElement preset : presets) blueprints.add(object(preset));
            }
            long total = 2;
            for (Map.Entry<String, JsonElement> field : root.entrySet()) {
                String key = field.getKey();
                if (key.equals("encoding") || key.equals("materials") || key.equals("nbt")) continue;
                total += measure(new JsonPrimitive(key)) + 2;
                if (key.equals("presets")) total += 2 + Math.max(0, blueprints.size() - 1);
                else if (!key.equals("blueprint")) total += measure(field.getValue());
            }
            // Complete preflight before copying a single referenced cell or NBT string.
            for (JsonObject blueprint : blueprints) {
                long size = expandedSize(blueprint, materials, nbtSizes, sizes);
                if (size > HologramBlueprintArtifact.MAX_JSON_BYTES)
                    throw new IOException("Expanded blueprint byte budget");
                total += size;
                if (total > limit) throw new IOException("Expanded blueprint collection byte budget");
            }
            for (JsonObject blueprint : blueprints) {
                resolveNbt(blueprint, nbt);
                for (String field : new String[] { "cells", "palette" }) {
                    JsonElement rows = blueprint.get(field);
                    if (rows == null || rows.isJsonNull()) continue;
                    JsonArray expanded = new JsonArray();
                    for (JsonElement row : rows.getAsJsonArray()) {
                        JsonArray ref = row.getAsJsonArray();
                        JsonObject cell = cloneObject(
                            materials.get(index(ref.get(0), materials.size()))
                                .getAsJsonObject());
                        resolveNbt(cell, nbt);
                        for (Map.Entry<String, JsonElement> coordinate : ref.get(1)
                            .getAsJsonObject()
                            .entrySet()) cell.add(coordinate.getKey(), coordinate.getValue());
                        expanded.add(cell);
                    }
                    blueprint.add(field, expanded);
                }
            }
            root.remove("encoding");
            root.remove("materials");
            root.remove("nbt");
            return root;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid compact blueprint JSON", invalid);
        }
    }

    private static long expandedSize(JsonObject blueprint, JsonArray materials, long[] nbtSizes, long[] sizes)
        throws IOException {
        long size = 2;
        int fields = 0;
        for (Map.Entry<String, JsonElement> entry : blueprint.entrySet()) {
            if (fields++ > 0) size++;
            size += measure(new JsonPrimitive(entry.getKey())) + 1;
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if ((key.equals("cells") || key.equals("palette")) && !value.isJsonNull()) {
                JsonArray rows = array(value);
                if (rows.size() > (key.equals("palette") ? 4096 : MAX_CELLS))
                    throw new IOException("Blueprint cell count budget");
                size += 2;
                for (int i = 0; i < rows.size(); i++) {
                    JsonArray row = array(rows.get(i));
                    if (row.size() != 2) throw new IOException("Invalid blueprint cell reference");
                    int ref = index(row.get(0), materials.size());
                    JsonObject coordinates = object(row.get(1));
                    for (Map.Entry<String, JsonElement> coordinate : coordinates.entrySet()) {
                        if (!coordinate.getKey()
                            .matches("[xyz]")) throw new IOException("Invalid compact coordinate field");
                        JsonElement number = coordinate.getValue();
                        if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive()
                            .isNumber()
                            || !number.getAsString()
                                .matches("-?[0-9]+"))
                            throw new IOException("Invalid compact coordinate");
                        try {
                            Integer.parseInt(number.getAsString());
                        } catch (NumberFormatException invalid) {
                            throw new IOException("Compact coordinate range", invalid);
                        }
                    }
                    long coordinateSize = measure(coordinates);
                    boolean a = materials.get(ref)
                        .getAsJsonObject()
                        .entrySet()
                        .isEmpty();
                    boolean b = coordinates.entrySet()
                        .isEmpty();
                    size += sizes[ref] + coordinateSize - (a || b ? 2 : 1) + (i > 0 ? 1 : 0);
                    if (size > HologramBlueprintArtifact.MAX_JSON_BYTES)
                        throw new IOException("Expanded blueprint byte budget");
                }
            } else
                size += nbtField(key) && !value.isJsonNull() ? nbtSizes[index(value, nbtSizes.length)] : measure(value);
        }
        return size;
    }

    private static long objectSize(JsonObject object, long[] nbtSizes) throws IOException {
        long size = 2;
        int fields = 0;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (fields++ > 0) size++;
            size += measure(new JsonPrimitive(entry.getKey())) + 1;
            JsonElement value = entry.getValue();
            size += nbtField(entry.getKey()) && !value.isJsonNull() ? nbtSizes[index(value, nbtSizes.length)]
                : measure(value);
        }
        return size;
    }

    private static boolean nbtField(String key) {
        return key.equals("tileNbt") || key.equals("itemNbt")
            || key.equals("processingNbt")
            || key.equals("semanticNbt")
            || key.equals("quantumGroupingNbt");
    }

    private static void resolveNbt(JsonObject object, JsonArray nbt) throws IOException {
        for (String key : new String[] { "tileNbt", "itemNbt", "processingNbt", "quantumGroupingNbt", "semanticNbt" }) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull()) object.add(key, nbt.get(index(value, nbt.size())));
        }
    }

    private static int index(JsonElement value, int count) throws IOException {
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isNumber()
            || !value.getAsString()
                .matches("0|[1-9][0-9]{0,9}"))
            throw new IOException("Invalid blueprint dictionary reference");
        long index = Long.parseLong(value.getAsString());
        if (index >= count) throw new IOException("Blueprint dictionary reference out of range");
        return (int) index;
    }

    private static JsonArray array(JsonElement value) throws IOException {
        if (value == null || !value.isJsonArray()) throw new IOException("Expected blueprint array");
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("Expected blueprint object");
        return value.getAsJsonObject();
    }

    /** Expanded logical UTF-8 size of an admitted DTO, without validation or an entire blueprint tree. */
    static long expandedBytes(HologramPresets.Preset preset) throws IOException {
        if (preset.cells != null) HologramBlueprintLimits.require(preset.cells.size());
        JsonObject metadata = metadata(preset);
        long size = measure(metadata);
        boolean fields = !metadata.entrySet()
            .isEmpty();
        for (String key : new String[] { "cells", "palette" }) {
            List<HologramPresets.Cell> cells = key.equals("cells") ? preset.cells : preset.palette;
            if (cells == null) continue; // Default Gson omits null model fields.
            size += measure(new JsonPrimitive(key)) + 3 + (fields ? 1 : 0);
            fields = true;
            for (int i = 0; i < cells.size(); i++) {
                size += measure(MODEL.toJsonTree(cells.get(i))) + (i > 0 ? 1 : 0);
            }
        }
        return size;
    }

    static JsonObject metadata(HologramPresets.Preset preset) {
        return METADATA.toJsonTree(preset)
            .getAsJsonObject();
    }

    private static int depth(JsonElement value) {
        int depth = 0;
        if (value.isJsonObject()) for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject()
            .entrySet()) depth = Math.max(depth, depth(entry.getValue()));
        else if (value.isJsonArray())
            for (JsonElement entry : value.getAsJsonArray()) depth = Math.max(depth, depth(entry));
        return value.isJsonObject() || value.isJsonArray() ? depth + 1 : 0;
    }

    private static JsonObject cloneObject(JsonObject value) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : value.entrySet())
            result.add(entry.getKey(), cloneValue(entry.getValue()));
        return result;
    }

    private static JsonElement cloneValue(JsonElement value) {
        if (value.isJsonObject()) return cloneObject(value.getAsJsonObject());
        if (value.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) copy.add(cloneValue(child));
            return copy;
        }
        return value; // JSON primitives and null are immutable.
    }

    // Gson's own escaping determines the exact expanded UTF-8 budget; no expanded string is allocated.
    private static long measure(JsonElement value) throws IOException {
        final long[] count = { 0 };
        java.io.Writer writer = new java.io.Writer() {

            @Override
            public void write(char[] chars, int offset, int length) throws IOException {
                for (int i = offset; i < offset + length; i++) {
                    char c = chars[i];
                    count[0] += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isLowSurrogate(c) ? 1 : 3;
                }
                if (count[0] > HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES)
                    throw new IOException("Expanded JSON byte budget");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        GSON.toJson(value, writer);
        return count[0];
    }

    static byte[] encode(HologramPresets.Preset blueprint, HologramDraftEncoding.Progress progress) throws IOException {
        return encode(java.util.Collections.singletonList(blueprint), false, null, progress);
    }

    static byte[] encodeLibrary(List<HologramPresets.Preset> presets) throws IOException {
        return encode(presets, true, null, (done, total) -> {});
    }

    static byte[] encodeEnvelope(HologramPresets.Preset blueprint, String owner, String uploadedBy) throws IOException {
        JsonObject fields = new JsonObject();
        fields.addProperty("owner", owner);
        fields.addProperty("uploadedBy", uploadedBy);
        return encode(java.util.Collections.singletonList(blueprint), false, fields, (done, total) -> {});
    }

    private static byte[] encode(List<HologramPresets.Preset> presets, boolean collection, JsonObject extra,
        HologramDraftEncoding.Progress progress) throws IOException {
        long total = 0;
        if (presets.size() > 32) throw new IOException("Blueprint collection entry budget");
        for (HologramPresets.Preset preset : presets) total += (long) preset.cells.size() + preset.palette.size();
        final long all = total;
        JsonObject rootMetadata = new JsonObject();
        rootMetadata.addProperty("schema", extra == null ? 2 : 1);
        if (extra != null) for (Map.Entry<String, JsonElement> field : extra.entrySet())
            rootMetadata.add(field.getKey(), field.getValue());
        rootMetadata.add(
            collection ? "presets" : "blueprint",
            collection ? new JsonArray() : com.google.gson.JsonNull.INSTANCE);
        final long overhead = measure(rootMetadata) - (collection ? 0 : 4) + Math.max(0, presets.size() - 1) + 64;
        progress.update(0, total);
        Gson writer = new GsonBuilder().registerTypeAdapter(Encoded.class, new TypeAdapter<Encoded>() {

            final Map<String, Integer> materialIds = new LinkedHashMap<>(), nbtIds = new LinkedHashMap<>();
            final List<JsonObject> materials = new ArrayList<>();
            long done, expandedTotal = overhead;

            @Override
            public Encoded read(JsonReader input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(JsonWriter output, Encoded ignored) throws IOException {
                output.beginObject()
                    .name("schema")
                    .value(extra == null ? 2 : 1)
                    .name("encoding")
                    .value(ENCODING);
                if (extra != null) for (Map.Entry<String, JsonElement> field : extra.entrySet()) {
                    output.name(field.getKey());
                    GSON.toJson(field.getValue(), output);
                }
                output.name(collection ? "presets" : "blueprint");
                if (collection) output.beginArray();
                for (HologramPresets.Preset preset : presets) writePreset(output, preset);
                if (collection) output.endArray();
                output.name("materials")
                    .beginArray();
                for (JsonObject material : materials) {
                    progress.checkpoint();
                    GSON.toJson(material, output);
                }
                output.endArray()
                    .name("nbt")
                    .beginArray();
                for (String nbt : nbtIds.keySet()) {
                    progress.checkpoint();
                    output.value(nbt);
                }
                output.endArray()
                    .endObject();
            }

            private void internNbt(JsonObject object) throws IOException {
                for (String field : new String[] { "tileNbt", "itemNbt", "processingNbt", "quantumGroupingNbt",
                    "semanticNbt" }) {
                    JsonElement value = object.get(field);
                    if (value == null || value.isJsonNull()) continue;
                    String text = value.getAsString();
                    if (text.length() > 2 * 1024 * 1024) throw new IOException("Blueprint NBT text budget");
                    Integer id = nbtIds.get(text);
                    if (id == null) {
                        id = nbtIds.size();
                        if (id >= MAX_DICTIONARY) throw new IOException("Blueprint NBT dictionary budget");
                        nbtIds.put(text, id);
                    }
                    object.addProperty(field, id);
                }
            }

            private void writePreset(JsonWriter output, HologramPresets.Preset preset) throws IOException {
                if (preset.cells.size() > MAX_CELLS || preset.palette.size() > 4096)
                    throw new IOException("Blueprint cell count budget");
                JsonObject metadata = metadata(preset);
                // Check logical expansion as rows are visited, never serialize a full uncompressed blueprint.
                long expanded = measure(metadata);
                internNbt(metadata);
                output.beginObject();
                for (Map.Entry<String, JsonElement> field : metadata.entrySet()) {
                    output.name(field.getKey());
                    GSON.toJson(field.getValue(), output);
                }
                for (String field : new String[] { "cells", "palette" }) {
                    List<HologramPresets.Cell> rows = field.equals("cells") ? preset.cells : preset.palette;
                    expanded += field.length() + 6;
                    output.name(field)
                        .beginArray();
                    for (HologramPresets.Cell cell : rows) {
                        progress.checkpoint();
                        JsonObject descriptor = MODEL.toJsonTree(cell)
                            .getAsJsonObject();
                        expanded += measure(descriptor) + 1;
                        if (expanded > HologramBlueprintArtifact.MAX_JSON_BYTES)
                            throw new IOException("Expanded blueprint byte budget");
                        JsonObject coordinates = new JsonObject();
                        for (String coordinate : new String[] { "x", "y", "z" })
                            if (descriptor.has(coordinate)) coordinates.add(coordinate, descriptor.remove(coordinate));
                        internNbt(descriptor);
                        String key = descriptor.toString();
                        Integer id = materialIds.get(key);
                        if (id == null) {
                            id = materials.size();
                            if (id >= MAX_DICTIONARY) throw new IOException("Blueprint material dictionary budget");
                            materialIds.put(key, id);
                            materials.add(descriptor);
                        }
                        output.beginArray()
                            .value(id);
                        GSON.toJson(coordinates, output);
                        output.endArray();
                        progress.update(++done, all);
                    }
                    output.endArray();
                }
                expandedTotal += expanded;
                if (expandedTotal > (collection ? HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES
                    : HologramBlueprintArtifact.MAX_JSON_BYTES))
                    throw new IOException("Expanded blueprint collection byte budget");
                output.endObject();
            }
        })
            .create();
        byte[] bytes = HologramBlueprintSharing.encodeBounded(
            writer,
            new Encoded(),
            collection ? HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES : HologramBlueprintArtifact.MAX_JSON_BYTES,
            progress::checkpoint);
        progress.update(total, total);
        return bytes;
    }

    private static final class Encoded {
    }
}
