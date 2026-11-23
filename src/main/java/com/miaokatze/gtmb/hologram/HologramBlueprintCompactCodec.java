// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

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
import com.google.gson.stream.JsonToken;
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

    /** Catalog hint from the root encoding member only. Not a substitute for validating the document on use. */
    public static boolean isCompactEncoding(JsonElement marker) throws IOException {
        if (marker == null) return false;
        if (!marker.isJsonPrimitive() || !marker.getAsJsonPrimitive().isString()
            || !ENCODING.equals(marker.getAsString())) throw new IOException("不支持的蓝图存储编码");
        return true;
    }

    /** Exact storage classification. Invalid/unsupported envelopes fail rather than being treated as old files. */
    public static boolean isCompact(byte[] bytes, int limit) throws IOException {
        return storageDocument(bytes, limit).has("encoding");
    }

    /** Validates the compact dictionary without expanding a referenced cell. Also accepts bare historic presets. */
    static JsonObject storageDocument(byte[] bytes, int limit) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > limit || limit < 1
            || limit > HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES) throw new IOException("Blueprint JSON byte budget");
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        HologramBlueprintSharing.boundedJson(text, limit);
        HologramBlueprintPreviewBinding.validate(text); // reject duplicate keys before a tree can discard a value
        JsonObject root = parseBounded(text, limit, false);
        storageShape(root);
        return root;
    }

    private static boolean storageShape(JsonObject root) throws IOException {
        boolean compact = isCompactEncoding(root.get("encoding"));
        if (root.has("blueprint") && root.has("presets")) throw new IOException("蓝图容器不明确");
        storageSchema(root);
        if (root.has("presets")) {
            for (JsonElement row : array(root.get("presets"))) storageMember(object(row), compact);
        } else storageMember(documentBlueprint(root), compact);
        return compact;
    }

    static void requireRecompressibleEnvelope(JsonObject root) throws IOException {
        if (root.has("materials") || root.has("nbt"))
            throw new IOException("蓝图含紧凑格式保留字段，不能无损转换");
    }

    private static void storageSchema(JsonObject object) throws IOException {
        JsonElement schema = object.get("schema");
        if (schema != null && (!schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
            || !("1".equals(schema.getAsString()) || "2".equals(schema.getAsString()))))
            throw new IOException("不支持的蓝图 schema");
    }

    private static void storageMember(JsonObject blueprint, boolean compact) throws IOException {
        storageSchema(blueprint);
        array(blueprint.get("cells"));
        if (!compact) {
            validateLegacyRows(blueprint);
            for (String key : new String[] { "cells", "palette" }) {
                JsonElement rows = blueprint.get(key);
                if (rows != null && !rows.isJsonNull()) for (JsonElement row : array(rows)) object(row);
            }
        }
    }

    /** A validated local display view retaining material references and their separate coordinates. */
    public static DisplayRows displayRows(byte[] bytes) throws IOException {
        int limit = HologramBlueprintArtifact.MAX_JSON_BYTES;
        if (bytes == null || bytes.length == 0 || bytes.length > limit)
            throw new IOException("Blueprint JSON byte budget");
        JsonObject root = parse(new String(bytes, StandardCharsets.UTF_8), limit, false);
        return new DisplayRows(root, root.has("encoding") ? array(root.get("materials")) : null);
    }

    /** Decode compact preview geometry without constructing a JSON tree for each cell. */
    public static DisplayRows displayGeometry(byte[] bytes) throws IOException {
        int limit = HologramBlueprintArtifact.MAX_JSON_BYTES;
        if (bytes == null || bytes.length == 0 || bytes.length > limit)
            throw new IOException("Blueprint JSON byte budget");
        String json = new String(bytes, StandardCharsets.UTF_8);
        HologramBlueprintSharing.boundedJson(json, limit);
        DisplayRows packed = packedLayout(json, limit, () -> true);
        if (packed != null) {
            storageShape(packed.root);
            return packed;
        }
        JsonObject root = parse(json, limit, false);
        if (root.has("encoding")) storageShape(root);
        return new DisplayRows(root, root.has("encoding") ? array(root.get("materials")) : null);
    }

    @com.github.bsideup.jabel.Desugar
    record LocalDocument(JsonObject root, DisplayRows packed, boolean compact) {}

    static LocalDocument localDocument(String json, BooleanSupplier current) throws IOException {
        return localDocument(json, current, false);
    }

    static LocalDocument localDocument(String json, BooleanSupplier current, boolean stored) throws IOException {
        int limit = HologramBlueprintArtifact.MAX_JSON_BYTES;
        HologramBlueprintSharing.boundedJson(json, limit);
        DisplayRows packed = packedLayout(json, limit, current);
        // On a miss the general reader takes the same immutable text, already bounded in this call.
        if (packed != null) return new LocalDocument(packed.root, packed, true);
        JsonObject original = parseObject(json);
        boolean compact = stored ? storageShape(original) : original.has("encoding");
        return new LocalDocument(parseBounded(original, limit, true), null, compact);
    }

    private static DisplayRows packedLayout(String json, int limit, BooleanSupplier current) throws IOException {
        PackedSlice fast = PackedGeometryScanner.scan(json, current);
        if (fast != null) {
            DisplayRows parsed = tryPackedDisplay(fast.projected(), limit, fast.rows(), current);
            if (parsed != null) return parsed;
        }
        return tryPackedDisplay(json, limit, null, current);
    }

    private static DisplayRows tryPackedDisplay(String json, int limit, PackedRows prepared, BooleanSupplier current)
        throws InterruptedIOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            TypeAdapter<JsonElement> values = GSON.getAdapter(JsonElement.class);
            JsonObject root = new JsonObject();
            PackedRows rows = prepared == null ? new PackedRows() : prepared;
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (root.has(key)) return null;
                if (key.equals("cells") || key.equals("presets")) return null;
                if (!key.equals("blueprint")) {
                    root.add(key, values.read(reader));
                    continue;
                }
                JsonObject blueprint = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String field = reader.nextName();
                    if (blueprint.has(field)) return null;
                    if (field.equals("cells")) {
                        if (prepared == null) rows.read(reader, current);
                        else {
                            reader.beginArray();
                            if (reader.hasNext()) return null;
                            reader.endArray();
                        }
                        blueprint.add(field, new JsonArray());
                    } else blueprint.add(field, values.read(reader));
                }
                reader.endObject();
                root.add(key, blueprint);
            }
            reader.endObject();
            if (!root.has("blueprint") || !new JsonPrimitive(ENCODING).equals(root.get("encoding"))
                || reader.peek() != JsonToken.END_DOCUMENT) return null;
            checkCurrent(current);
            validateCompact(root, limit, false, rows);
            return new DisplayRows(root, array(root.get("materials")), rows);
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException unsupported) {
            // The general reader preserves historical and noncanonical JSON forms.
            return null;
        }
    }

    /** Narrow fast path for literal coordinate arrays. Other JSON forms still use the existing admission. */
    private static final class PackedGeometryScanner {

        private final String source;
        private int cursor;

        private PackedGeometryScanner(String source, int from) {
            this.source = source;
            cursor = from;
        }

        static PackedSlice scan(String json, BooleanSupplier current) throws InterruptedIOException {
            if (json.length() < 16384) return null;
            int blueprint = -1, depth = 0, blueprintDepth = -1, checkpoint = 0;
            for (int i = 0; i < json.length();) {
                if (i >= checkpoint) {
                    checkCurrent(current);
                    checkpoint = i + 4096;
                }
                char c = json.charAt(i);
                if (c == '"') {
                    int start = i++;
                    while (i < json.length()) {
                        char ch = json.charAt(i++);
                        if (ch == '\\') i++;
                        else if (ch == '"') break;
                    }
                    if (i > json.length()) return null;
                    boolean header = depth == 1 && i - start == 11 && json.startsWith("\"blueprint\"", start);
                    boolean cells = depth == blueprintDepth && i - start == 7 && json.startsWith("\"cells\"", start);
                    if (!header && !cells) continue;
                    int colon = whitespace(json, i);
                    if (colon == json.length() || json.charAt(colon) != ':') continue;
                    int value = whitespace(json, colon + 1);
                    if (value == json.length()) return null;
                    if (header && json.charAt(value) == '{') blueprint = value;
                    if (cells && json.charAt(value) == '[') {
                        PackedGeometryScanner scanner = new PackedGeometryScanner(json, value);
                        try {
                            PackedRows rows = scanner.read(current);
                            String projected = json.substring(0, value + 1) + json.substring(scanner.cursor - 1);
                            return new PackedSlice(rows, projected);
                        } catch (UnsupportedPackedGeometry ignored) {
                            return null;
                        }
                    }
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                    if (i == blueprint) blueprintDepth = depth;
                } else if (c == '}' || c == ']') {
                    if (depth == blueprintDepth) blueprintDepth = -1;
                    depth--;
                }
                i++;
            }
            return null;
        }

        private PackedRows read(BooleanSupplier current) throws InterruptedIOException, UnsupportedPackedGeometry {
            PackedRows rows = new PackedRows();
            expect('[');
            if (take(']')) return rows;
            do {
                if ((rows.size & 255) == 0) checkCurrent(current);
                if (rows.size >= MAX_CELLS) throw unsupported();
                int offset = rows.offset();
                int[] chunk = rows.nextChunk(offset);
                expect('[');
                chunk[offset] = integer(false);
                expect(',');
                expect('{');
                int axes = 0, coordinateBytes = 2;
                if (!take('}')) {
                    do {
                        expect('"');
                        if (cursor + 1 >= source.length() || source.charAt(cursor + 1) != '"') throw unsupported();
                        int axis = source.charAt(cursor) - 'x';
                        if (axis < 0 || axis > 2 || (axes & 1 << axis) != 0) throw unsupported();
                        cursor += 2;
                        expect(':');
                        int start = whitespace(source, cursor);
                        chunk[offset + 1 + axis] = integer(true);
                        coordinateBytes += 4 + cursor - start + (axes == 0 ? 0 : 1);
                        axes |= 1 << axis;
                    } while (take(','));
                    expect('}');
                }
                expect(']');
                chunk[offset + 4] = coordinateBytes;
                rows.size++;
            } while (take(','));
            expect(']');
            return rows;
        }

        private int integer(boolean signed) throws UnsupportedPackedGeometry {
            cursor = whitespace(source, cursor);
            boolean negative = signed && cursor < source.length() && source.charAt(cursor) == '-';
            if (negative) cursor++;
            if (cursor == source.length()) throw unsupported();
            char first = source.charAt(cursor);
            if (first < '0' || first > '9') throw unsupported();
            int start = cursor;
            long value = 0, limit = negative ? 2147483648L : Integer.MAX_VALUE;
            while (cursor < source.length()) {
                char ch = source.charAt(cursor);
                if (ch < '0' || ch > '9') break;
                if (first == '0' && cursor != start) throw unsupported();
                value = 10 * value + ch - '0';
                if (value > limit) throw unsupported();
                cursor++;
            }
            return (int) (negative ? -value : value);
        }

        private void expect(char expected) throws UnsupportedPackedGeometry {
            if (!take(expected)) throw unsupported();
        }

        private boolean take(char expected) {
            cursor = whitespace(source, cursor);
            if (cursor == source.length() || source.charAt(cursor) != expected) return false;
            cursor++;
            return true;
        }

        private static int whitespace(String source, int index) {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
                index++;
            }
            return index;
        }

        private static UnsupportedPackedGeometry unsupported() {
            return new UnsupportedPackedGeometry();
        }

        private static final class UnsupportedPackedGeometry extends Exception {

            UnsupportedPackedGeometry() {
                super(null, null, false, false);
            }
        }
    }

    @com.github.bsideup.jabel.Desugar
    private record PackedSlice(PackedRows rows, String projected) {}

    private static void checkCurrent(BooleanSupplier current) throws InterruptedIOException {
        if (!current.getAsBoolean()) throw new InterruptedIOException("Blueprint read cancelled");
    }

    private static final class PackedRows {

        private static final int ROWS_PER_CHUNK = 4096;
        private static final int FIELDS = 5;
        private final List<int[]> chunks = new ArrayList<>();
        private int size;

        private int offset() {
            return (size % ROWS_PER_CHUNK) * FIELDS;
        }

        private int[] nextChunk(int offset) {
            if (offset == 0) chunks.add(new int[ROWS_PER_CHUNK * FIELDS]);
            return chunks.get(chunks.size() - 1);
        }

        private void read(JsonReader reader, BooleanSupplier current) throws IOException {
            reader.beginArray();
            while (reader.hasNext()) {
                if ((size & 255) == 0) checkCurrent(current);
                if (size >= MAX_CELLS) throw new IOException("Blueprint cell count budget");
                int offset = offset();
                int[] chunk = nextChunk(offset);
                reader.beginArray();
                String material = number(reader);
                if (!decimalDigits(material, 0) || material.length() > 1 && material.charAt(0) == '0')
                    throw new IOException("Invalid blueprint dictionary reference");
                chunk[offset] = Integer.parseInt(material);
                reader.beginObject();
                int axes = 0, coordinateBytes = 2;
                while (reader.hasNext()) {
                    int axis = switch (reader.nextName()) {
                        case "x" -> 0;
                        case "y" -> 1;
                        case "z" -> 2;
                        default -> throw new IOException("Invalid compact coordinate field");
                    };
                    int bit = 1 << axis;
                    if ((axes & bit) != 0) throw new IOException("Duplicate compact coordinate");
                    String coordinate = number(reader);
                    if (!decimalDigits(coordinate, coordinate.startsWith("-") ? 1 : 0))
                        throw new IOException("Invalid compact coordinate");
                    chunk[offset + 1 + axis] = Integer.parseInt(coordinate);
                    coordinateBytes += 4 + coordinate.length() + (axes == 0 ? 0 : 1);
                    axes |= bit;
                }
                reader.endObject();
                reader.endArray();
                chunk[offset + 4] = coordinateBytes;
                size++;
            }
            reader.endArray();
        }

        private static String number(JsonReader reader) throws IOException {
            if (reader.peek() != JsonToken.NUMBER) throw new IOException("Expected compact integer");
            return reader.nextString();
        }

        private int get(int row, int field) {
            return chunks.get(row / ROWS_PER_CHUNK)[(row % ROWS_PER_CHUNK) * FIELDS + field];
        }

        private long expandedBytes(long[] sizes) throws IOException {
            long bytes = 2 + Math.max(0, size - 1);
            for (int row = 0; row < size; row++) {
                int ref = get(row, 0), coordinates = get(row, 4);
                if (ref >= sizes.length) throw new IOException("Blueprint dictionary reference out of range");
                boolean empty = coordinates == 2 || sizes[ref] == 2;
                bytes += sizes[ref] + coordinates - (empty ? 2 : 1);
                if (bytes > HologramBlueprintArtifact.MAX_JSON_BYTES)
                    throw new IOException("Expanded blueprint byte budget");
            }
            return bytes;
        }
    }

    /** A single historic preset can also be the root of a portable file. */
    public static JsonObject documentBlueprint(JsonObject root) throws IOException {
        if (root.has("blueprint")) return object(root.get("blueprint"));
        if (root.has("cells") && !root.has("presets")) return root;
        throw new IOException("Missing blueprint document");
    }

    /** Publish the stored blueprint content in a portable envelope without catalog ownership fields. */
    static byte[] publicShare(byte[] stored) throws IOException {
        int limit = HologramBlueprintArtifact.MAX_JSON_BYTES;
        JsonObject root = parse(new String(stored, StandardCharsets.UTF_8), limit, false);
        root.remove("owner");
        root.remove("uploadedBy");
        root.remove("previewBinding");
        root.addProperty("schema", 2);
        return HologramBlueprintSharing.encodeBounded(GSON, root, limit);
    }

    /** Placement admission expects a share envelope. Historic storage bytes remain unchanged on disk. */
    public static byte[] portableDocument(byte[] stored) throws IOException {
        int limit = HologramBlueprintArtifact.MAX_JSON_BYTES;
        if (stored == null || stored.length == 0 || stored.length > limit)
            throw new IOException("Blueprint JSON byte budget");
        JsonObject root = parse(new String(stored, StandardCharsets.UTF_8), limit, false);
        JsonObject blueprint = documentBlueprint(root);
        if (blueprint == root) {
            root = new JsonObject();
            root.add("blueprint", blueprint);
        } else if (root.has("schema") && "2".equals(root.get("schema").toString())) return stored;
        root.remove("previewBinding");
        root.addProperty("schema", 2);
        return HologramBlueprintSharing.encodeBounded(GSON, root, limit);
    }

    public static JsonObject readLegacyCatalog(java.nio.file.Path path) throws IOException {
        return readStoredCatalog(path).root();
    }

    @com.github.bsideup.jabel.Desugar
    public record StoredDocument(JsonObject root, boolean compact) {}

    public static StoredDocument readStoredCatalog(java.nio.file.Path path) throws IOException {
        int limit = HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES;
        StoredDocument source = parseStored(HologramBlueprintSharing.readBytes(path, limit), limit);
        array(source.root().get("presets"));
        return source;
    }

    /** Keep the encoding of the exact bytes, not the expanded tree returned by the general reader. */
    static StoredDocument parseStored(byte[] bytes, int limit) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > limit || limit < 1
            || limit > HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES) throw new IOException("Blueprint JSON byte budget");
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        HologramBlueprintSharing.boundedJson(text, limit);
        HologramBlueprintPreviewBinding.validate(text);
        JsonObject original = parseObject(text);
        boolean compact = storageShape(original);
        return new StoredDocument(parseBounded(original, limit, true), compact);
    }

    public static byte[] legacyCatalogMember(JsonObject expandedCatalog, JsonObject member) throws IOException {
        return legacyCatalogMember(new StoredDocument(expandedCatalog, false), member);
    }

    public static byte[] legacyCatalogMember(StoredDocument source, JsonObject member) throws IOException {
        JsonObject catalog = source.root();
        JsonObject root = new JsonObject();
        for (Map.Entry<String, JsonElement> field : catalog.entrySet()) {
            String name = field.getKey();
            if (!name.equals("schema") && !name.equals("presets")) root.add(name, field.getValue());
        }
        root.addProperty("schema", 2);
        root.add("blueprint", cloneObject(member));
        // Rewrapping a collection member for browse/export must not compact an expanded source implicitly.
        return source.compact() ? encodeLocalDocument(root)
            : HologramBlueprintSharing.encodeBounded(GSON, root, HologramBlueprintArtifact.MAX_JSON_BYTES);
    }

    public static final class DisplayRows {

        public final JsonObject blueprint;
        final JsonObject root;
        private final JsonArray rows, materials;
        private final PackedRows packed;

        private DisplayRows(JsonObject root, JsonArray materials) throws IOException {
            this(root, materials, null);
        }

        private DisplayRows(JsonObject root, JsonArray materials, PackedRows packed) throws IOException {
            this.root = root;
            blueprint = documentBlueprint(root);
            this.materials = materials;
            // Only a display projection substitutes NBT strings. Portable/public envelopes must retain references.
            if (materials != null) {
                JsonArray nbt = array(root.get("nbt"));
                resolveNbt(blueprint, nbt);
                for (JsonElement descriptor : materials) resolveNbt(descriptor.getAsJsonObject(), nbt);
            }
            this.packed = packed;
            rows = array(blueprint.get("cells"));
        }

        public boolean compact() {
            return materials != null;
        }

        public int cells() {
            return packed == null ? rows.size() : packed.size;
        }

        public int materials() {
            return materials == null ? 0 : materials.size();
        }

        private JsonObject position(int index) {
            JsonElement row = rows.get(index);
            return compact() ? row.getAsJsonArray()
                .get(1)
                .getAsJsonObject() : row.getAsJsonObject();
        }

        public int coordinate(int row, int axis) {
            if (packed != null) return packed.get(row, axis + 1);
            JsonElement coordinate = position(row).get(axis == 0 ? "x" : axis == 1 ? "y" : "z");
            return coordinate == null ? 0 : coordinate.getAsInt();
        }

        public int materialIndex(int row) {
            if (packed != null) return packed.get(row, 0);
            return rows.get(row)
                .getAsJsonArray()
                .get(0)
                .getAsInt();
        }

        public JsonObject material(int index) {
            return materials.get(index)
                .getAsJsonObject();
        }

        JsonObject paletteCell(JsonElement reference) {
            JsonArray row = reference.getAsJsonArray();
            JsonObject cell = cloneObject(material(row.get(0).getAsInt()));
            for (Map.Entry<String, JsonElement> coordinate : row.get(1).getAsJsonObject().entrySet())
                cell.add(coordinate.getKey(), coordinate.getValue());
            return cell;
        }
    }

    /** Storage-only migration. Preserve every JSON field and NBT string without model/policy conversion. */
    public static byte[] recompress(byte[] bytes, int limit) throws IOException {
        return recompress(bytes, limit, () -> true);
    }

    public static byte[] recompress(byte[] bytes, int limit, java.util.function.BooleanSupplier current)
        throws IOException {
        compressionCurrent(current);
        JsonObject original = parse(bytes, limit);
        return recompressExpanded(original, limit, current);
    }

    static byte[] recompressExpanded(JsonObject original, int limit, BooleanSupplier current) throws IOException {
        compressionCurrent(current);
        byte[] result = compactExpanded(cloneObject(original), limit, current);
        if (!original.equals(parse(result, limit))) throw new IOException("Blueprint compression changed JSON content");
        compressionCurrent(current);
        return result;
    }

    /** Encode an owned local JSON projection without validating or interpreting opaque source fields. */
    static byte[] encodeLocalDocument(JsonObject root) throws IOException {
        int limit = HologramBlueprintArtifact.MAX_JSON_BYTES;
        if (measure(root) > limit || depth(root) > 32) throw new IOException("Expanded blueprint byte or depth budget");
        if (root.has("materials") || root.has("nbt"))
            return HologramBlueprintSharing.encodeBounded(GSON, root, limit);
        return compactExpanded(root, limit, () -> true);
    }

    private static byte[] compactExpanded(JsonObject root, int limit, java.util.function.BooleanSupplier current)
        throws IOException {
        List<JsonObject> blueprints = new ArrayList<>();
        if (root.has("blueprint") && !root.has("presets")) blueprints.add(object(root.get("blueprint")));
        else if (root.has("presets") && !root.has("blueprint")) {
            for (JsonElement row : array(root.get("presets"))) blueprints.add(object(row));
        } else throw new IOException("Missing or ambiguous blueprint container");
        // These names belong to the compact envelope. Never silently replace legacy extension fields.
        requireRecompressibleEnvelope(root);
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
        return HologramBlueprintSharing.encodeBounded(GSON, root, limit, () -> compressionCurrent(current));
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
        return parse(text, limit, true);
    }

    private static JsonObject parse(String text, int limit, boolean expand) throws IOException {
        HologramBlueprintSharing.boundedJson(text, limit);
        return parseBounded(text, limit, expand);
    }

    private static JsonObject parseBounded(String text, int limit, boolean expand) throws IOException {
        return parseBounded(parseObject(text), limit, expand);
    }

    private static JsonObject parseObject(String text) throws IOException {
        try {
            JsonElement value = new JsonParser().parse(text);
            if (!value.isJsonObject()) throw new IOException("Blueprint JSON root must be an object");
            return value.getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid compact blueprint JSON", invalid);
        }
    }

    private static JsonObject parseBounded(JsonObject root, int limit, boolean expand) throws IOException {
        try {
            if (!root.has("encoding")) {
                List<JsonObject> legacy = new ArrayList<>();
                if (root.has("blueprint")) legacy.add(object(root.get("blueprint")));
                else if (root.has("presets")) {
                    JsonArray presets = array(root.get("presets"));
                    if (presets.size() > 32) throw new IOException("Blueprint collection entry budget");
                    for (JsonElement preset : presets) legacy.add(object(preset));
                }
                for (JsonObject blueprint : legacy) {
                    validateLegacyRows(blueprint);
                    if (limit > HologramBlueprintArtifact.MAX_JSON_BYTES
                        && measure(blueprint) > HologramBlueprintArtifact.MAX_JSON_BYTES)
                        throw new IOException("Expanded blueprint byte budget");
                }
                return root;
            }
            return validateCompact(root, limit, expand, null);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid compact blueprint JSON", invalid);
        }
    }

    /** Structural checks for a selected expanded member, including a newly wrapped bare legacy member. */
    static void validateLegacyRows(JsonObject blueprint) throws IOException {
        for (String field : new String[] { "cells", "palette" }) {
            JsonElement rows = blueprint.get(field);
            if (rows != null && !rows.isJsonNull()
                && array(rows).size() > (field.equals("palette") ? 4096 : MAX_CELLS))
                throw new IOException("Blueprint cell count budget");
        }
    }

    private static JsonObject validateCompact(JsonObject root, int limit, boolean expand, PackedRows packed)
        throws IOException {
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
            long size = expandedSize(blueprint, materials, nbtSizes, sizes, packed);
            if (size > HologramBlueprintArtifact.MAX_JSON_BYTES)
                throw new IOException("Expanded blueprint byte budget");
            total += size;
            if (total > limit) throw new IOException("Expanded blueprint collection byte budget");
        }
        if (!expand) return root;
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
    }

    private static long expandedSize(JsonObject blueprint, JsonArray materials, long[] nbtSizes, long[] sizes,
        PackedRows packed) throws IOException {
        long size = 2;
        int fields = 0;
        for (Map.Entry<String, JsonElement> entry : blueprint.entrySet()) {
            if (fields++ > 0) size++;
            size += measure(new JsonPrimitive(entry.getKey())) + 1;
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (key.equals("cells") && packed != null) size += packed.expandedBytes(sizes);
            else if ((key.equals("cells") || key.equals("palette")) && !value.isJsonNull()) {
                JsonArray rows = array(value);
                if (rows.size() > (key.equals("palette") ? 4096 : MAX_CELLS))
                    throw new IOException("Blueprint cell count budget");
                size += 2;
                for (int i = 0; i < rows.size(); i++) {
                    JsonArray row = array(rows.get(i));
                    if (row.size() != 2) throw new IOException("Invalid blueprint cell reference");
                    int ref = index(row.get(0), materials.size());
                    JsonObject coordinates = object(row.get(1));
                    int coordinateSize = 2;
                    for (Map.Entry<String, JsonElement> coordinate : coordinates.entrySet()) {
                        String axis = coordinate.getKey();
                        if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z"))
                            throw new IOException("Invalid compact coordinate field");
                        JsonElement number = coordinate.getValue();
                        if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive()
                            .isNumber()) throw new IOException("Invalid compact coordinate");
                        String text = number.getAsString();
                        if (!decimalDigits(text, text.startsWith("-") ? 1 : 0))
                            throw new IOException("Invalid compact coordinate");
                        try {
                            Integer.parseInt(text);
                        } catch (NumberFormatException invalid) {
                            throw new IOException("Compact coordinate range", invalid);
                        }
                        if (coordinateSize > 2) coordinateSize++;
                        coordinateSize += 4 + text.length(); // One quoted axis character, colon and numeric token.
                    }
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
                .isNumber())
            throw new IOException("Invalid blueprint dictionary reference");
        String text = value.getAsString();
        if (text.length() > 10 || !decimalDigits(text, 0) || text.length() > 1 && text.charAt(0) == '0')
            throw new IOException("Invalid blueprint dictionary reference");
        long index = Long.parseLong(text);
        if (index >= count) throw new IOException("Blueprint dictionary reference out of range");
        return (int) index;
    }

    private static boolean decimalDigits(String text, int start) {
        if (start >= text.length()) return false;
        for (int i = start; i < text.length(); i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        return true;
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
