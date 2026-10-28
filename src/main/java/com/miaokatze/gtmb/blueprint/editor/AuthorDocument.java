package com.miaokatze.gtmb.blueprint.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Immutable, source-authoritative local draft. No registry, world, native NBT or server receipt lives here.
 * JSON containers are owned privately; exact encoded payload strings and untouched records are shared.
 */
public final class AuthorDocument {

    static final int PAGE_SIZE = 64;
    final String id;
    final long revision, nextId;
    final long retainedBytes, retainedNodes;
    final int count, pendingPayloadPositions;
    final AuthorDocumentIO.Limits limits;
    final JsonObject envelope, header;
    final boolean wrapped, hadCells, hadStructures;
    final AuthorTree<Long, Cell[]> pages;
    final AuthorTree<Position, Long> positions;
    final AuthorTree<String, Group> groups;
    final List<String> groupOrder;
    final Obligation obligations;
    final String readOnlyReason;

    AuthorDocument(String id, long revision, long nextId, int count, AuthorDocumentIO.Limits limits, long retainedBytes,
        long retainedNodes, int pendingPayloadPositions, JsonObject envelope, JsonObject header, boolean wrapped,
        boolean hadCells, boolean hadStructures, AuthorTree<Long, Cell[]> pages, AuthorTree<Position, Long> positions,
        AuthorTree<String, Group> groups, List<String> groupOrder, Obligation obligations, String readOnlyReason) {
        this.retainedBytes = retainedBytes;
        this.retainedNodes = retainedNodes;
        this.pendingPayloadPositions = pendingPayloadPositions;
        this.id = id;
        this.revision = revision;
        this.nextId = nextId;
        this.count = count;
        this.limits = limits;
        this.envelope = envelope;
        this.header = header;
        this.wrapped = wrapped;
        this.hadCells = hadCells;
        this.hadStructures = hadStructures;
        this.pages = pages;
        this.positions = positions;
        this.groups = groups;
        this.groupOrder = Collections.unmodifiableList(groupOrder);
        this.obligations = obligations;
        this.readOnlyReason = readOnlyReason;
    }

    /** Save As snapshot: independent document identity, structurally shared immutable content and provenance. */
    public AuthorDocument forkCopy() {
        return copyWithIdentity(
            java.util.UUID.randomUUID()
                .toString());
    }

    /** Explicit Save As/overwrite boundary; never changes the active editor or its history. */
    public AuthorDocument copyWithIdentity(String documentId) {
        if (documentId == null || !java.util.UUID.fromString(documentId)
            .toString()
            .equalsIgnoreCase(documentId)) throw new IllegalArgumentException("Invalid document identity");
        return new AuthorDocument(
            documentId,
            revision,
            nextId,
            count,
            limits,
            retainedBytes,
            retainedNodes,
            pendingPayloadPositions,
            envelope,
            header,
            wrapped,
            hadCells,
            hadStructures,
            pages,
            positions,
            groups,
            groupOrder,
            obligations,
            readOnlyReason);
    }

    public String documentId() {
        return id;
    }

    public long revision() {
        return revision;
    }

    public int cellCount() {
        return count;
    }

    public String title() {
        String name = string(header, "name");
        return name == null ? "" : name;
    }

    public boolean sameStructures(AuthorDocument other) {
        return other != null && groups == other.groups;
    }

    public boolean sameHeader(AuthorDocument other) {
        return other != null && header == other.header;
    }

    public boolean editable() {
        return readOnlyReason == null;
    }

    public String diagnostic() {
        return readOnlyReason == null ? "" : readOnlyReason;
    }

    public boolean hasPendingObligations() {
        return obligations != null;
    }

    public boolean hasPendingSpatialObligations() {
        return pendingPayloadPositions > 0 || (obligations != null && obligations.spatial);
    }

    public int obligationCount() {
        return obligations == null ? 0 : obligations.count;
    }

    public Cell cell(long id) {
        if (id < 1) return null;
        Cell[] page = pages.get(id / PAGE_SIZE);
        return page == null ? null : page[(int) (id % PAGE_SIZE)];
    }

    public Cell cellAt(int x, int y, int z) {
        Long id = positions.get(new Position(x, y, z));
        return id == null ? null : cell(id);
    }

    public Iterable<Cell> cells() {
        return () -> new Iterator<Cell>() {

            final Iterator<AuthorTree.Entry<Long, Cell[]>> directory = pages.iterator();
            Cell[] page;
            int index;
            Cell next = advance();

            private Cell advance() {
                while (true) {
                    if (page != null) while (index < page.length) {
                        Cell cell = page[index++];
                        if (cell != null) return cell;
                    }
                    if (!directory.hasNext()) return null;
                    page = directory.next().value;
                    index = 0;
                }
            }

            public boolean hasNext() {
                return next != null;
            }

            public Cell next() {
                if (next == null) throw new NoSuchElementException();
                Cell result = next;
                next = advance();
                return result;
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Boundary-only full source materialization; never used to execute a local edit. */
    public JsonObject sourceCopy() {
        JsonObject blueprint = AuthorDocumentIO.copy(header)
            .getAsJsonObject();
        if (hadCells || count > 0) {
            JsonArray rows = new JsonArray();
            for (Cell cell : cells()) rows.add(cell.sourceCopy());
            blueprint.add("cells", rows);
        }
        if (hadStructures || !groupOrder.isEmpty()) {
            JsonArray structures = new JsonArray();
            for (String id : groupOrder) structures.add(AuthorDocumentIO.copy(groups.get(id).source));
            blueprint.add("structures", structures);
        }
        if (!wrapped) return blueprint;
        JsonObject root = AuthorDocumentIO.copy(envelope)
            .getAsJsonObject();
        root.add("blueprint", blueprint);
        return root;
    }

    public JsonObject headerCopy() {
        return AuthorDocumentIO.copy(header)
            .getAsJsonObject();
    }

    public JsonElement headerValue(String key) {
        return AuthorDocumentIO.copy(header.get(key));
    }

    public List<JsonObject> obligations() {
        List<JsonObject> rows = new ArrayList<>();
        for (Obligation p = obligations; p != null; p = p.previous) rows.add(
            AuthorDocumentIO.copy(p.source)
                .getAsJsonObject());
        Collections.reverse(rows);
        return Collections.unmodifiableList(rows);
    }

    public List<JsonObject> structures() {
        List<JsonObject> result = new ArrayList<>();
        for (String id : groupOrder) result.add(
            AuthorDocumentIO.copy(groups.get(id).source)
                .getAsJsonObject());
        return Collections.unmodifiableList(result);
    }

    AuthorDocument withRevision(long value) {
        return withRevision(value, nextId);
    }

    AuthorDocument withRevision(long value, long minimumNextId) {
        return new AuthorDocument(
            id,
            value,
            Math.max(nextId, minimumNextId),
            count,
            limits,
            retainedBytes,
            retainedNodes,
            pendingPayloadPositions,
            envelope,
            header,
            wrapped,
            hadCells,
            hadStructures,
            pages,
            positions,
            groups,
            groupOrder,
            obligations,
            readOnlyReason);
    }

    static String string(JsonObject source, String field) {
        JsonElement e = source.get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    public static final class Cell {

        final long id;
        final JsonObject source;
        final Position position, payloadOrigin;
        final long encodedBytes, nodes;
        final Set<String> memberships;

        Cell(long id, JsonObject source, Position position, Set<String> memberships) {
            this(id, source, position, memberships, position);
        }

        Cell(long id, JsonObject source, Position position, Set<String> memberships, Position payloadOrigin) {
            this(
                id,
                source,
                position,
                memberships,
                payloadOrigin,
                AuthorDocumentIO.measure(source),
                AuthorDocumentIO.nodes(source));
        }

        Cell(long id, JsonObject source, Position position, Set<String> memberships, Position payloadOrigin, long bytes,
            long nodes) {
            this.payloadOrigin = payloadOrigin;
            this.id = id;
            this.source = source;
            this.position = position;
            this.encodedBytes = bytes;
            this.nodes = nodes;
            this.memberships = memberships.isEmpty() ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(memberships));
        }

        private Cell(Cell cell, Position origin) {
            id = cell.id;
            source = cell.source;
            position = cell.position;
            memberships = cell.memberships;
            encodedBytes = cell.encodedBytes;
            nodes = cell.nodes;
            payloadOrigin = origin;
        }

        Cell withPayloadOrigin(Position origin) {
            return payloadOrigin.equals(origin) ? this : new Cell(this, origin);
        }

        public int payloadX() {
            return payloadOrigin.x;
        }

        public int payloadY() {
            return payloadOrigin.y;
        }

        public int payloadZ() {
            return payloadOrigin.z;
        }

        public boolean payloadPositionPending() {
            return !position.equals(payloadOrigin);
        }

        public long id() {
            return id;
        }

        public int x() {
            return position.x;
        }

        public int y() {
            return position.y;
        }

        public int z() {
            return position.z;
        }

        public String stringField(String field) {
            JsonElement value = source.get(field);
            return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive()
                    .isString() ? value.getAsString() : null;
        }

        public int intField(String field, int fallback) {
            try {
                return AuthorDocumentIO.integer(source, field, fallback);
            } catch (java.io.IOException e) {
                return fallback;
            }
        }

        public boolean booleanField(String field) {
            JsonElement value = source.get(field);
            return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive()
                    .isBoolean()
                && value.getAsBoolean();
        }

        public String prompt() {
            return stringField("prompt");
        }

        public int facing() {
            return intField("facing", -1);
        }

        public boolean controller() {
            return booleanField("controller");
        }

        public String block() {
            return string(source, "block");
        }

        public String item() {
            return string(source, "item");
        }

        public int metadata() {
            return integer("metadata", 0);
        }

        public int damage() {
            return integer("damage", 0);
        }

        public String tileNbt() {
            return string(source, "tileNbt");
        }

        public String itemNbt() {
            return string(source, "itemNbt");
        }

        public boolean excluded() {
            return source.has("excluded") && source.get("excluded")
                .getAsBoolean();
        }

        public Set<String> memberships() {
            return memberships;
        }

        public JsonObject sourceCopy() {
            return AuthorDocumentIO.copy(source)
                .getAsJsonObject();
        }

        private int integer(String key, int fallback) {
            return source.has(key) ? source.get(key)
                .getAsInt() : fallback;
        }
    }

    static final class Position implements Comparable<Position> {

        final int x, y, z;

        Position(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int compareTo(Position p) {
            int c = Integer.compare(x, p.x);
            if (c != 0) return c;
            c = Integer.compare(y, p.y);
            return c != 0 ? c : Integer.compare(z, p.z);
        }

        @Override
        public boolean equals(Object p) {
            return p instanceof Position && compareTo((Position) p) == 0;
        }

        @Override
        public int hashCode() {
            return ((x * 31) + y) * 31 + z;
        }
    }

    static final class Group {

        final JsonObject source;
        final Set<Long> members;

        Group(JsonObject source, Set<Long> members) {
            this.source = source;
            this.members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
        }
    }

    static final class Obligation {

        final JsonObject source;
        final Obligation previous;
        final int count;
        final long encodedBytes, nodes;
        final boolean spatial;

        Obligation(JsonObject source, Obligation previous) {
            this.source = source;
            this.previous = previous;
            count = previous == null ? 1 : previous.count + 1;
            // Unknown future obligations remain conservatively pending; old descriptor annotations are nonspatial.
            String kind = AuthorDocument.string(source, "kind");
            spatial = !"descriptor-edit".equals(kind) || (previous != null && previous.spatial);
            encodedBytes = AuthorDocumentIO.measure(source) + 1 + (previous == null ? 0 : previous.encodedBytes);
            nodes = AuthorDocumentIO.nodes(source) + (previous == null ? 0 : previous.nodes);
        }
    }
}
