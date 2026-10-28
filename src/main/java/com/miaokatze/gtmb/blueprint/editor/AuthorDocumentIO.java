package com.miaokatze.gtmb.blueprint.editor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Bounded structural I/O, deliberately not a native NBT parser or a portable-export certificate. */
public final class AuthorDocumentIO {

    public static final String DRAFT_FORMAT = "gtmb-author-draft";
    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .disableHtmlEscaping()
        .create();

    private AuthorDocumentIO() {}

    public static final class Limits {

        public final int maxBytes, maxCells, maxDepth, maxNodes, maxStringChars, maxCoordinate, maxObligations;

        public Limits(int maxBytes, int maxCells, int maxDepth, int maxNodes, int maxStringChars, int maxCoordinate,
            int maxObligations) {
            if (maxBytes < 1 || maxCells < 1
                || maxDepth < 1
                || maxDepth > 128
                || maxNodes < 1
                || maxStringChars < 1
                || maxCoordinate < 1
                || maxObligations < 1) throw new IllegalArgumentException("Invalid document limits");
            this.maxBytes = maxBytes;
            this.maxCells = maxCells;
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
            this.maxStringChars = maxStringChars;
            this.maxCoordinate = maxCoordinate;
            this.maxObligations = maxObligations;
        }

        public static Limits defaults() {
            // Attachment textBoxNbt is not a compact NBT-dictionary entry. Preserve the legacy native
            // compressed 8 MiB envelope (Base64 upper bound); aggregate JSON/native decode limits stay separate.
            int maxEncodedNativeNbt = ((8 * 1024 * 1024 + 2) / 3) * 4;
            return new Limits(64 * 1024 * 1024, 999_999, 32, 16_000_000, maxEncodedNativeNbt, 30_000_000, 4096);
        }
    }

    public static AuthorDocument read(byte[] bytes, Limits limits) throws IOException {
        if (bytes == null || bytes.length > limits.maxBytes) throw new IOException("Document byte budget exceeded");
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (JsonReader reader = new JsonReader(
            new java.io.InputStreamReader(new java.io.ByteArrayInputStream(bytes), decoder))) {
            reader.setLenient(false);
            JsonElement root = readValue(reader, limits, 0, new long[] { 0 });
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON content");
            OwnedTree admitted = new OwnedTree(limits, false);
            admitted.visit(root, 0);
            return importOwned(object(root, "document"), limits, admitted);
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Invalid document JSON", e);
        }
    }

    /** One bounded defensive container copy; immutable scalar/payload values are shared, never serialized/reparsed. */
    public static AuthorDocument readExpanded(JsonObject expanded, Limits limits) throws IOException {
        OwnedTree admitted = new OwnedTree(limits, true);
        JsonObject owned = object(admitted.visit(expanded, 0), "document");
        return importOwned(owned, limits, admitted);
    }

    static JsonObject boundedCopy(JsonObject object, Limits limits) throws IOException {
        return object(new OwnedTree(limits, true).visit(object, 5), "patch");
    }

    public static byte[] writeDraft(AuthorDocument document, Limits limits) throws IOException {
        requireDraftBudget(
            limits,
            document.retainedBytes,
            document.retainedNodes,
            document.count,
            document.obligations);
        int capacity = (int) Math.min(
            limits.maxBytes,
            document.retainedBytes + 1024L
                + document.count * 60L
                + (document.obligations == null ? 0 : document.obligations.encodedBytes));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(capacity);
        try (com.google.gson.stream.JsonWriter writer = new com.google.gson.stream.JsonWriter(
            new OutputStreamWriter(bounded(bytes, limits.maxBytes), StandardCharsets.UTF_8))) {
            writer.setSerializeNulls(true);
            writer.setHtmlSafe(false);
            writer.beginObject();
            writer.name("format")
                .value(DRAFT_FORMAT);
            writer.name("version")
                .value(1);
            writer.name("documentId")
                .value(document.id);
            writer.name("revision")
                .value(document.revision);
            writer.name("nextCellId")
                .value(document.nextId);
            writer.name("payloadOrigins")
                .beginArray();
            for (AuthorDocument.Cell cell : document.cells()) writer.beginArray()
                .value(cell.payloadX())
                .value(cell.payloadY())
                .value(cell.payloadZ())
                .endArray();
            writer.endArray();
            writer.name("cellIds")
                .beginArray();
            for (AuthorDocument.Cell cell : document.cells()) writer.value(cell.id);
            writer.endArray();
            writer.name("source");
            writeSource(writer, document);
            writer.name("obligations")
                .beginArray();
            List<JsonObject> obligations = new ArrayList<>();
            for (AuthorDocument.Obligation p = document.obligations; p != null; p = p.previous)
                obligations.add(p.source);
            for (int i = obligations.size() - 1; i >= 0; i--) GSON.toJson(obligations.get(i), writer);
            writer.endArray()
                .endObject();
        } catch (com.google.gson.JsonIOException e) {
            throw new IOException("Cannot encode bounded draft", e);
        }
        return bytes.toByteArray();
    }

    private static void writeSource(com.google.gson.stream.JsonWriter writer, AuthorDocument document)
        throws IOException {
        if (document.wrapped) {
            writer.beginObject();
            for (Map.Entry<String, JsonElement> field : document.envelope.entrySet()) {
                writer.name(field.getKey());
                GSON.toJson(field.getValue(), writer);
            }
            writer.name("blueprint");
        }
        writer.beginObject();
        for (Map.Entry<String, JsonElement> field : document.header.entrySet()) {
            if (field.getKey()
                .equals("cells") && (document.hadCells || document.count > 0)) continue;
            if (field.getKey()
                .equals("structures") && (document.hadStructures || !document.groupOrder.isEmpty())) continue;
            writer.name(field.getKey());
            GSON.toJson(field.getValue(), writer);
        }
        if (document.hadCells || document.count > 0) {
            writer.name("cells")
                .beginArray();
            for (AuthorDocument.Cell cell : document.cells()) GSON.toJson(cell.source, writer);
            writer.endArray();
        }
        if (document.hadStructures || !document.groupOrder.isEmpty()) {
            writer.name("structures")
                .beginArray();
            for (String id : document.groupOrder) GSON.toJson(document.groups.get(id).source, writer);
            writer.endArray();
        }
        writer.endObject();
        if (document.wrapped) writer.endObject();
    }

    private static OutputStream bounded(ByteArrayOutputStream bytes, int limit) {
        return new OutputStream() {

            public void write(int value) throws IOException {
                if (bytes.size() >= limit) throw new IOException("Document byte budget exceeded");
                bytes.write(value);
            }

            public void write(byte[] value, int offset, int length) throws IOException {
                if ((long) bytes.size() + length > limit) throw new IOException("Document byte budget exceeded");
                bytes.write(value, offset, length);
            }
        };
    }

    private static AuthorDocument importOwned(JsonObject root, Limits limits, OwnedTree admitted) throws IOException {
        JsonObject source = root;
        String id = UUID.randomUUID()
            .toString();
        long revision = 0, nextId = 1;
        JsonArray ids = null, origins = null;
        AuthorDocument.Obligation obligations = null;
        JsonElement format = root.get("format");
        boolean draft = format != null && format.isJsonPrimitive()
            && format.getAsJsonPrimitive()
                .isString()
            && DRAFT_FORMAT.equals(format.getAsString());
        if (draft) {
            if (number(root, "version", -1) != 1) throw new IOException("Unsupported draft version");
            // Unknown envelope fields cannot silently disappear on re-save.
            for (Map.Entry<String, JsonElement> field : root.entrySet()) {
                String key = field.getKey();
                if (!key.equals("format") && !key.equals("version")
                    && !key.equals("documentId")
                    && !key.equals("revision")
                    && !key.equals("nextCellId")
                    && !key.equals("cellIds")
                    && !key.equals("source")
                    && !key.equals("obligations")
                    && !key.equals("payloadOrigins")) throw new IOException("Unsupported draft envelope field: " + key);
            }
            id = text(root, "documentId", true);
            try {
                if (!UUID.fromString(id)
                    .toString()
                    .equalsIgnoreCase(id)) throw new IllegalArgumentException("Noncanonical UUID");
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid document identity", e);
            }
            revision = number(root, "revision", -1);
            nextId = number(root, "nextCellId", -1);
            if (revision < 0 || revision == Long.MAX_VALUE || nextId < 1 || nextId == Long.MAX_VALUE)
                throw new IOException("Invalid draft identity counters");
            ids = array(root.get("cellIds"), "cellIds");
            origins = array(root.get("payloadOrigins"), "payloadOrigins");
            source = object(root.get("source"), "source");
            JsonArray pending = array(root.get("obligations"), "obligations");
            if (pending.size() > limits.maxObligations) throw new IOException("Pending obligation budget exceeded");
            for (JsonElement row : pending) {
                JsonObject obligation = object(row, "obligation");
                if (text(obligation, "kind", true).isEmpty()) throw new IOException("Missing obligation kind");
                long sourceRevision = number(obligation, "sourceRevision", -1);
                if (sourceRevision < 0 || sourceRevision > revision)
                    throw new IOException("Invalid obligation source revision");
                for (JsonElement cell : array(obligation.get("cellIds"), "obligation cell IDs"))
                    if (exactLong(cell, "obligation cell ID") < 1) throw new IOException("Invalid obligation cell ID");
                integer(obligation, "dx", 0);
                integer(obligation, "dy", 0);
                integer(obligation, "dz", 0);
                obligations = new AuthorDocument.Obligation(obligation, obligations);
            }
        }
        if (source.has("encoding")) throw new IOException("Expand compact encoding through the format adapter first");
        if (source.has("presets")) throw new IOException("Choose one blueprint from the collection before editing");
        TreeStats sourceStats = admitted.containers.get(source);
        if (sourceStats.depth + 2 > limits.maxDepth) throw new IOException("JSON structure budget exceeded");
        long retainedNodes = sourceStats.nodes, retainedBytes = sourceStats.bytes;
        boolean wrapped = source.has("blueprint");
        JsonObject header = wrapped ? object(source.remove("blueprint"), "blueprint") : source;
        checkStringFields(header, "name", "prompt", "processingNbt", "quantumGroupingNbt", "sourceMod", "sourceClass");
        int schema = integer(header, "schema", 1);
        if (schema != 1 && schema != 2) throw new IOException("Unsupported blueprint schema");
        JsonElement cellValue = header.get("cells"), groupValue = header.get("structures");
        boolean hadCells = cellValue != null && !cellValue.isJsonNull();
        boolean hadStructures = groupValue != null && !groupValue.isJsonNull();
        JsonArray cells = hadCells ? array(header.remove("cells"), "cells") : new JsonArray();
        JsonArray structures = hadStructures ? array(header.remove("structures"), "structures") : new JsonArray();
        if (cells.size() > limits.maxCells) throw new IOException("Cell count budget exceeded");
        if (origins != null && origins.size() != cells.size()) throw new IOException("Payload origin count mismatch");
        if (ids != null && ids.size() != cells.size()) throw new IOException("Cell identity count mismatch");
        Map<String, JsonObject> groupSources = new LinkedHashMap<>();
        Map<String, Set<Long>> members = new LinkedHashMap<>();
        for (JsonElement row : structures) {
            JsonObject group = object(row, "structure");
            String key = text(group, "id", true);
            if (key.isEmpty() || groupSources.put(key, group) != null)
                throw new IOException("Duplicate or empty structure ID");
            position(group, limits);
            checkStringFields(group, "textBoxNbt", "name");
            members.put(key, new LinkedHashSet<>());
        }
        AuthorTree<Long, AuthorDocument.Cell[]> pages = new AuthorTree<>();
        List<AuthorTree.Entry<AuthorDocument.Position, Long>> positionEntries = new ArrayList<>(cells.size());
        Map<Long, AuthorDocument.Cell[]> initialPages = new LinkedHashMap<>();
        long maximum = 0;
        int pendingPayloadPositions = 0;
        for (int i = 0; i < cells.size(); i++) {
            long cellId = ids == null ? i + 1L : exactLong(ids.get(i), "cell ID");
            if (cellId < 1 || cellId == Long.MAX_VALUE) throw new IOException("Invalid/duplicate cell ID");
            if (ids != null && cellId <= maximum) throw new IOException("Draft cell IDs must retain source order");
            maximum = Math.max(maximum, cellId);
            JsonObject raw = object(cells.get(i), "cell");
            AuthorDocument.Cell cell = cell(cellId, raw, limits, admitted.containers.get(raw));
            if (origins != null) {
                JsonArray origin = array(origins.get(i), "payload origin");
                if (origin.size() != 3) throw new IOException("Invalid payload origin");
                JsonObject coordinate = new JsonObject();
                coordinate.add("x", origin.get(0));
                coordinate.add("y", origin.get(1));
                coordinate.add("z", origin.get(2));
                cell = cell.withPayloadOrigin(position(coordinate, limits));
            }
            if (cell.payloadPositionPending()) pendingPayloadPositions++;
            positionEntries.add(new AuthorTree.Entry<>(cell.position, cellId));
            AuthorDocument.Cell[] page = initialPages.get(cellId / AuthorDocument.PAGE_SIZE);
            if (page == null) {
                page = new AuthorDocument.Cell[AuthorDocument.PAGE_SIZE];
                initialPages.put(cellId / AuthorDocument.PAGE_SIZE, page);
            }
            page[(int) (cellId % AuthorDocument.PAGE_SIZE)] = cell;
            for (String group : cell.memberships) {
                Set<Long> set = members.get(group);
                if (set == null) throw new IOException("Unknown structure membership: " + group);
                set.add(cellId);
            }
        }
        java.util.Collections.sort(positionEntries, (a, b) -> a.key.compareTo(b.key));
        for (int i = 1; i < positionEntries.size(); i++)
            if (positionEntries.get(i - 1).key.compareTo(positionEntries.get(i).key) == 0)
                throw new IOException("Duplicate cell position");
        AuthorTree<AuthorDocument.Position, Long> positions = AuthorTree.fromSorted(positionEntries);
        List<AuthorTree.Entry<Long, AuthorDocument.Cell[]>> pageEntries = new ArrayList<>(initialPages.size());
        for (Map.Entry<Long, AuthorDocument.Cell[]> page : initialPages.entrySet())
            pageEntries.add(new AuthorTree.Entry<>(page.getKey(), page.getValue()));
        pages = AuthorTree.fromSorted(pageEntries);
        AuthorTree<String, AuthorDocument.Group> groups = new AuthorTree<>();
        for (String key : groupSources.keySet())
            groups = groups.put(key, new AuthorDocument.Group(groupSources.get(key), members.get(key)));
        if (ids == null) nextId = maximum + 1;
        else if (nextId <= maximum) throw new IOException("Draft cell identity counter reused");
        requireDraftBudget(limits, retainedBytes, retainedNodes, cells.size(), obligations);
        return new AuthorDocument(
            id,
            revision,
            nextId,
            cells.size(),
            limits,
            retainedBytes,
            retainedNodes,
            pendingPayloadPositions,
            wrapped ? source : new JsonObject(),
            header,
            wrapped,
            hadCells,
            hadStructures,
            pages,
            positions,
            groups,
            new ArrayList<>(groupSources.keySet()),
            obligations,
            schema == 1 ? "Legacy controller template: use the explicit schema-1 conversion workflow before editing"
                : null);
    }

    /** Source admission reserves durable identity/provenance overhead, not only bare JSON bytes. */
    static void requireDraftBudget(Limits limits, long sourceBytes, long sourceNodes, int count,
        AuthorDocument.Obligation obligations) throws IOException {
        long overheadBytes = 1024L + count * 60L + (obligations == null ? 0 : obligations.encodedBytes);
        long overheadNodes = 128L + count * 5L + (obligations == null ? 0 : obligations.nodes);
        if (sourceBytes + overheadBytes > limits.maxBytes) throw new IOException("Draft logical byte budget exceeded");
        if (sourceNodes + overheadNodes > limits.maxNodes) throw new IOException("Draft logical node budget exceeded");
    }

    static AuthorDocument.Cell cell(long id, JsonObject raw, Limits limits) throws IOException {
        return cell(id, raw, limits, null);
    }

    private static AuthorDocument.Cell cell(long id, JsonObject raw, Limits limits, TreeStats stats)
        throws IOException {
        AuthorDocument.Position position = position(raw, limits);
        checkStringFields(raw, "block", "item", "defaultItem", "tileNbt", "itemNbt", "structureId", "prompt");
        for (String key : new String[] { "metadata", "damage", "defaultDamage", "count", "facing" })
            if (raw.has(key)) integer(raw, key, 0);
        for (String key : new String[] { "excluded", "controller" }) if (raw.has(key) && (!raw.get(key)
            .isJsonPrimitive()
            || !raw.get(key)
                .getAsJsonPrimitive()
                .isBoolean()))
            throw new IOException("Invalid boolean: " + key);
        Set<String> groups = new LinkedHashSet<>();
        JsonElement list = raw.get("structureIds");
        if (list != null && !list.isJsonNull()) {
            for (JsonElement item : array(list, "structureIds")) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive()
                    .isString()
                    || item.getAsString()
                        .isEmpty()
                    || !groups.add(item.getAsString())) throw new IOException("Invalid structure membership");
            }
        }
        if (groups.isEmpty()) {
            String group = text(raw, "structureId", false);
            if (group != null && !group.isEmpty()) groups.add(group);
        }
        return stats == null ? new AuthorDocument.Cell(id, raw, position, groups)
            : new AuthorDocument.Cell(id, raw, position, groups, position, stats.bytes, stats.nodes);
    }

    static AuthorDocument.Position position(JsonObject source, Limits limits) throws IOException {
        int x = integer(source, "x", 0), y = integer(source, "y", 0), z = integer(source, "z", 0);
        if (Math.abs((long) x) > limits.maxCoordinate || Math.abs((long) y) > limits.maxCoordinate
            || Math.abs((long) z) > limits.maxCoordinate) throw new IOException("Document coordinate budget exceeded");
        return new AuthorDocument.Position(x, y, z);
    }

    private static void checkStringFields(JsonObject object, String... keys) throws IOException {
        for (String key : keys) text(object, key, false);
    }

    static String text(JsonObject object, String key, boolean required) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            if (required) throw new IOException("Missing " + key);
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
            .isString()) throw new IOException("Invalid string: " + key);
        return value.getAsString();
    }

    static int integer(JsonObject source, String key, int fallback) throws IOException {
        long value = number(source, key, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IOException("Integer overflow: " + key);
        return (int) value;
    }

    static long number(JsonObject source, String key, long fallback) throws IOException {
        return source.has(key) ? exactLong(source.get(key), key) : fallback;
    }

    private static long exactLong(JsonElement e, String key) throws IOException {
        if (e == null || !e.isJsonPrimitive()
            || !e.getAsJsonPrimitive()
                .isNumber())
            throw new IOException("Invalid integer: " + key);
        Number number = e.getAsNumber();
        if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long)
            return number.longValue();
        String value = e.getAsString();
        try {
            if (value.indexOf('.') < 0 && value.indexOf('e') < 0 && value.indexOf('E') < 0)
                return Long.parseLong(value);
            return new BigDecimal(value).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IOException("Invalid integer: " + key, ex);
        }
    }

    private static JsonObject object(JsonElement e, String name) throws IOException {
        if (e == null || !e.isJsonObject()) throw new IOException("Expected object: " + name);
        return e.getAsJsonObject();
    }

    private static JsonArray array(JsonElement e, String name) throws IOException {
        if (e == null || !e.isJsonArray()) throw new IOException("Expected array: " + name);
        return e.getAsJsonArray();
    }

    static JsonElement copy(JsonElement e) {
        if (e == null || e.isJsonNull()) return JsonNull.INSTANCE;
        if (e.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> field : e.getAsJsonObject()
                .entrySet()) result.add(field.getKey(), copy(field.getValue()));
            return result;
        }
        if (e.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement value : e.getAsJsonArray()) result.add(copy(value));
            return result;
        }
        return e; // JsonPrimitive is immutable, including the exact encoded NBT string.
    }

    private static JsonElement readValue(JsonReader r, Limits limits, int depth, long[] nodes) throws IOException {
        if (depth > limits.maxDepth || ++nodes[0] > limits.maxNodes)
            throw new IOException("JSON structure budget exceeded");
        switch (r.peek()) {
            case BEGIN_OBJECT:
                r.beginObject();
                JsonObject object = new JsonObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    stringBudget(key, limits);
                    if (object.has(key)) throw new IOException("Duplicate JSON field: " + key);
                    object.add(key, readValue(r, limits, depth + 1, nodes));
                }
                r.endObject();
                return object;
            case BEGIN_ARRAY:
                r.beginArray();
                JsonArray array = new JsonArray();
                while (r.hasNext()) array.add(readValue(r, limits, depth + 1, nodes));
                r.endArray();
                return array;
            case STRING:
                String text = r.nextString();
                stringBudget(text, limits);
                return new JsonPrimitive(text);
            case NUMBER:
                String number = r.nextString();
                stringBudget(number, limits);
                try {
                    return new JsonPrimitive(new BigDecimal(number));
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid JSON number", e);
                }
            case BOOLEAN:
                return new JsonPrimitive(r.nextBoolean());
            case NULL:
                r.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new IOException("Expected JSON value");
        }
    }

    static void stringBudget(String text, Limits limits) throws IOException {
        if (text.length() > limits.maxStringChars) throw new IOException("JSON string budget exceeded");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))
                    throw new IOException("JSON string contains an isolated Unicode surrogate");
                i++;
            } else if (Character.isLowSurrogate(c))
                throw new IOException("JSON string contains an isolated Unicode surrogate");
        }
    }

    private static final java.util.regex.Pattern JSON_NUMBER = java.util.regex.Pattern
        .compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    private static final class TreeStats {

        final long bytes, nodes;
        final int depth;

        TreeStats(long bytes, long nodes, int depth) {
            this.bytes = bytes;
            this.nodes = nodes;
            this.depth = depth;
        }
    }

    /** One traversal validates, counts logical occurrences and, when needed, detaches mutable containers. */
    private static final class OwnedTree {

        final Limits limits;
        final boolean copying;
        final java.util.IdentityHashMap<JsonElement, TreeStats> containers = new java.util.IdentityHashMap<>();
        final Map<String, Long> keyBytes = new java.util.HashMap<>();
        long bytes, nodes;
        int lastDepth;

        OwnedTree(Limits limits, boolean copying) {
            this.limits = limits;
            this.copying = copying;
        }

        void addBytes(long value) throws IOException {
            bytes += value;
            if (bytes > limits.maxBytes) throw new IOException("Document byte budget exceeded");
        }

        JsonElement visit(JsonElement source, int depth) throws IOException {
            if (source == null) throw new IOException("Missing JSON value");
            if (depth > limits.maxDepth || nodes >= limits.maxNodes)
                throw new IOException("JSON structure budget exceeded");
            long startBytes = bytes, startNodes = nodes;
            nodes++;
            int height = 0;
            if (source.isJsonObject()) {
                JsonObject result = copying ? new JsonObject() : source.getAsJsonObject();
                addBytes(2);
                boolean first = true;
                for (Map.Entry<String, JsonElement> field : source.getAsJsonObject()
                    .entrySet()) {
                    String key = field.getKey();
                    Long length = keyBytes.get(key);
                    if (length == null) {
                        stringBudget(key, limits);
                        length = quotedBytes(key);
                        keyBytes.put(key, length);
                    }
                    addBytes(length + (first ? 1 : 2));
                    first = false;
                    JsonElement child = visit(field.getValue(), depth + 1);
                    height = Math.max(height, lastDepth + 1);
                    if (copying) result.add(key, child);
                }
                lastDepth = height;
                containers.put(result, new TreeStats(bytes - startBytes, nodes - startNodes, height));
                return result;
            }
            if (source.isJsonArray()) {
                JsonArray result = copying ? new JsonArray() : source.getAsJsonArray();
                addBytes(2);
                boolean first = true;
                for (JsonElement value : source.getAsJsonArray()) {
                    if (!first) addBytes(1);
                    first = false;
                    JsonElement child = visit(value, depth + 1);
                    height = Math.max(height, lastDepth + 1);
                    if (copying) result.add(child);
                }
                lastDepth = height;
                containers.put(result, new TreeStats(bytes - startBytes, nodes - startNodes, height));
                return result;
            }
            lastDepth = 0;
            if (source.isJsonNull()) {
                addBytes(4);
                return JsonNull.INSTANCE;
            }
            JsonPrimitive primitive = source.getAsJsonPrimitive();
            if (primitive.isString()) {
                String value = primitive.getAsString();
                stringBudget(value, limits);
                addBytes(quotedBytes(value));
                return primitive;
            }
            if (primitive.isBoolean()) {
                addBytes(primitive.getAsBoolean() ? 4 : 5);
                return primitive;
            }
            Number number = primitive.getAsNumber();
            String value = number.toString();
            stringBudget(value, limits);
            if (copying && !plainInteger(value)) {
                if (!JSON_NUMBER.matcher(value)
                    .matches()) throw new IOException("Invalid JSON number");
                // Decimal exponents must remain in the strict byte-reader's BigDecimal domain.
                if (number.getClass() != BigDecimal.class) try {
                    new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid JSON number", e);
                }
            }
            Class<?> type = number.getClass();
            if (type == Byte.class || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == BigDecimal.class
                || type == java.math.BigInteger.class
                || type.getName()
                    .equals("com.google.gson.internal.LazilyParsedNumber")) {
                addBytes(value.length());
                return primitive;
            }
            // Gson permits mutable Number implementations (e.g. AtomicInteger); freeze those instead of aliasing.
            BigDecimal frozen = new BigDecimal(value);
            addBytes(
                frozen.toString()
                    .length());
            return new JsonPrimitive(frozen);
        }
    }

    private static boolean plainInteger(String text) {
        int length = text.length(), start = length > 0 && text.charAt(0) == '-' ? 1 : 0;
        if (start == length) return false;
        if (text.charAt(start) == '0') return start + 1 == length;
        if (text.charAt(start) < '1' || text.charAt(start) > '9') return false;
        for (int i = start + 1; i < length; i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        return true;
    }

    /** Exact UTF-8 length emitted by JsonWriter with HTML escaping disabled; no serialized string staging. */
    static long quotedBytes(String value) {
        long bytes = 2;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') bytes += 2;
            else if (c <= 0x1f || c == '\u2028' || c == '\u2029') bytes += 6;
            else if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    bytes += 4;
                    i++;
                } else bytes++; // OutputStreamWriter UTF-8 replacement byte for an isolated surrogate.
            } else if (Character.isLowSurrogate(c)) bytes++;
            else bytes += 3;
        }
        return bytes;
    }

    static long nodes(JsonElement value) {
        long count = 1;
        if (value.isJsonObject()) for (Map.Entry<String, JsonElement> field : value.getAsJsonObject()
            .entrySet()) count += nodes(field.getValue());
        else if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) count += nodes(child);
        return count;
    }

    static long measure(JsonElement source) {
        if (source == null || source.isJsonNull()) return 4;
        if (source.isJsonObject()) {
            long bytes = 2;
            boolean first = true;
            for (Map.Entry<String, JsonElement> field : source.getAsJsonObject()
                .entrySet()) {
                bytes += quotedBytes(field.getKey()) + 1 + measure(field.getValue()) + (first ? 0 : 1);
                first = false;
            }
            return bytes;
        }
        if (source.isJsonArray()) {
            long bytes = 2;
            boolean first = true;
            for (JsonElement value : source.getAsJsonArray()) {
                bytes += measure(value) + (first ? 0 : 1);
                first = false;
            }
            return bytes;
        }
        JsonPrimitive value = source.getAsJsonPrimitive();
        if (value.isString()) return quotedBytes(value.getAsString());
        return value.isBoolean() ? (value.getAsBoolean() ? 4 : 5)
            : value.getAsString()
                .length();
    }
}
