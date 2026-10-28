package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;

/**
 * Explicit native boundary. Runs on the owning game thread, on a frozen source revision, never on a display
 * snapshot. The returned independently processed candidate is NOT a server review, material quote or permission.
 * Failure leaves both the source root and its pending obligations intact.
 */
public final class HologramAuthorPortableCompiler {

    private static final Gson GSON = new Gson();
    private static final Set<String> CELL_FIELDS = knownFields(HologramPresets.Cell.class);
    private static final Set<String> STRUCTURE_FIELDS = knownFields(HologramPresets.Structure.class);
    private static final Set<String> HINT_FIELDS = knownFields(HologramPresets.HintArea.class);

    private HologramAuthorPortableCompiler() {}

    /** Uses durable capture provenance; unknown legacy raw links fail closed rather than being cleared at zero. */
    public static byte[] compile(AuthorDocument document, NBTTagCompound confirmedPolicy) throws IOException {
        requireFrame(document);
        AuthorDocument.SourceFrame frame = document.sourceFrame();
        return compile(document, confirmedPolicy, frame.dimension, frame.x, frame.y, frame.z);
    }

    public static JsonObject settle(AuthorDocument document, NBTTagCompound confirmedPolicy) throws IOException {
        requireFrame(document);
        AuthorDocument.SourceFrame frame = document.sourceFrame();
        return settle(document, confirmedPolicy, frame.dimension, frame.x, frame.y, frame.z);
    }

    private static void requireFrame(AuthorDocument document) throws IOException {
        if (document == null || !document.editable()) throw new IOException("No editable author source");
        if (!document.sourceFrame().known)
            HologramLocalAuthorSemantics.requireSourceFrame(blueprint(document.sourceCopy()), document.sourceFrame());
    }

    public static byte[] compile(AuthorDocument document, NBTTagCompound confirmedPolicy, int dimension, int originX,
        int originY, int originZ) throws IOException {
        if (document != null && document.sourceFrame().known) {
            AuthorDocument.SourceFrame frame = document.sourceFrame();
            dimension = frame.dimension;
            originX = frame.x;
            originY = frame.y;
            originZ = frame.z;
        }
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            if (document == null || !document.editable()) throw new IOException("No editable author source");
            if (confirmedPolicy == null) throw new IOException("请先明确完成当前文档的处理阶段");
            JsonObject root = settle(document, confirmedPolicy, dimension, originX, originY, originZ);
            JsonObject raw = blueprint(root);
            // Old serialized receipts are input history only. Fresh processing is always performed against this exact
            // settled source. The policy must have been explicitly confirmed; no stages are manufactured here.
            JsonObject processed = HologramLocalAuthorSemantics
                .process(raw, confirmedPolicy, dimension, originX, originY, originZ);
            HologramPresets.Preset candidate = HologramLocalAuthorSemantics.preset(processed);
            JsonElement beforeStamp = GSON.toJsonTree(candidate);
            // A local document identity is not actor attribution or a server admission receipt.
            if (candidate.id == null || candidate.id.isEmpty()) candidate.id = document.documentId();
            HologramBlueprintVersions.stampSaved(candidate);
            HologramPresets.validate(candidate);
            if (!HologramBlueprintProcessing.complete(candidate)) throw new IOException("候选处理尚未完成");
            processed = HologramLocalAuthorSemantics.delta(processed, beforeStamp, GSON.toJsonTree(candidate))
                .getAsJsonObject();
            if (!root.has("blueprint")) {
                root = new JsonObject();
                root.addProperty("schema", 2);
            }
            root.add("blueprint", processed);
            root.remove("previewBinding"); // A fresh PNG binds the new exact bytes below, never an old thumbnail
                                           // receipt.
            return HologramBlueprintSharing.encodeBounded(GSON, root, HologramBlueprintArtifact.MAX_JSON_BYTES);
        }
    }

    /** Whole-orientation commands affect every source record even when no translation is pending. */
    public static void requireKnownTransformExtensions(AuthorDocument document) throws IOException {
        if (document == null || !document.editable()) throw new IOException("No editable author source");
        for (AuthorDocument.Cell cell : document.cells())
            rejectExtensions(cell.sourceCopy(), HologramPresets.Cell.class, "rotated cell " + cell.id());
        for (JsonObject structure : document.structures())
            rejectExtensions(structure, HologramPresets.Structure.class, "rotated structure");
        JsonElement hints = document.headerValue("hintAreas");
        if (hints != null && !hints.isJsonNull()) {
            if (!hints.isJsonArray()) throw new IOException("Invalid rotated hint-area records");
            for (JsonElement hint : hints.getAsJsonArray()) {
                if (!hint.isJsonObject()) throw new IOException("Invalid rotated hint-area record");
                rejectExtensions(hint.getAsJsonObject(), HologramPresets.HintArea.class, "rotated hint area");
            }
        }
    }

    /** Also usable before an explicit native rotation/processing operation; does not install or clear a draft. */
    public static JsonObject settle(AuthorDocument document, NBTTagCompound confirmedPolicy, int dimension, int originX,
        int originY, int originZ) throws IOException {
        if (document != null && document.sourceFrame().known) {
            AuthorDocument.SourceFrame frame = document.sourceFrame();
            dimension = frame.dimension;
            originX = frame.x;
            originY = frame.y;
            originZ = frame.z;
        }
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            JsonObject root = document.sourceCopy(), raw = blueprint(root);
            HologramPresets.Preset candidate = HologramLocalAuthorSemantics.preset(raw);
            if (candidate.schema != 2) throw new IOException("Explicit template migration is required");
            List<AuthorDocument.Cell> cells = new ArrayList<>();
            Map<String, Integer> finalPositions = new HashMap<>();
            Map<Vector, List<HologramPresets.Cell>> nativeGroups = new LinkedHashMap<>();
            Set<Long> copied = new HashSet<>();
            Set<String> transformedStructures = new HashSet<>();
            Set<String> enabledStructures = new HashSet<>();
            for (HologramPresets.Structure structure : candidate.structures)
                if (structure.enabled) enabledStructures.add(structure.id);
            boolean geometry = false;
            long lastRevision = -1;
            for (JsonObject obligation : document.obligations()) {
                String kind = obligation.get("kind")
                    .getAsString();
                long revision = obligation.get("sourceRevision")
                    .getAsLong();
                if (revision < lastRevision || revision >= document.revision())
                    throw new IOException("Invalid pending obligation order");
                lastRevision = revision;
                if (!("translate".equals(kind) || "copy".equals(kind)
                    || "paste".equals(kind)
                    || "descriptor-edit".equals(kind)
                    || "shared-membership-split".equals(kind)))
                    throw new IOException("Unsupported pending operation: " + kind);
                if (!"descriptor-edit".equals(kind)) geometry = true;
                if ("copy".equals(kind) || "paste".equals(kind) || "shared-membership-split".equals(kind))
                    for (JsonElement id : obligation.getAsJsonArray("cellIds")) copied.add(id.getAsLong());
            }
            int index = 0;
            for (AuthorDocument.Cell source : document.cells()) {
                cells.add(source);
                if (eligibleEndpoint(source, enabledStructures))
                    finalPositions.put(key(source.x(), source.y(), source.z()), index);
                HologramPresets.Cell cell = candidate.cells.get(index++);
                boolean moved = moved(source);
                geometry |= moved;
                if (moved || copied.contains(source.id())) {
                    rejectExtensions(source.sourceCopy(), HologramPresets.Cell.class, "cell " + source.id());
                    transformedStructures.addAll(source.memberships());
                    NBTTagCompound item = HologramBlueprintNbt.decode(cell.itemNbt);
                    NBTTagCompound tile = HologramBlueprintNbt.decode(cell.tileNbt);
                    if (copied.contains(source.id()) && (item != null && item.hasKey(HologramRecovery.TAG)
                        || tile != null && tile.hasKey(HologramRecovery.TAG))) throw new IOException("封存设备不能复制真实库存");
                    HologramPresets.Cell check = HologramLocalAuthorSemantics.cell(source.sourceCopy());
                    check.x = source.payloadX();
                    check.y = source.payloadY();
                    check.z = source.payloadZ();
                    if (tile != null && "LittleTilesTileEntity".equals(tile.getString("id")))
                        nativeGroups.computeIfAbsent(shift(source), ignored -> new ArrayList<>())
                            .add(check);
                }
            }
            EndpointIndex origins = geometry ? new EndpointIndex(cells, enabledStructures) : null;
            for (JsonObject structure : document.structures())
                if (structure.has("id") && transformedStructures.contains(
                    structure.get("id")
                        .getAsString()))
                    rejectExtensions(structure, HologramPresets.Structure.class, "transformed structure");
            // Translation does not imply any orientation change. Validate only actual inter-cell LittleTiles
            // relationships; unrelated native orientation schemas and opaque data are not rotated or rewritten.
            for (List<HologramPresets.Cell> group : nativeGroups.values()) validateLittleTranslation(group);
            HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.Policy.read(confirmedPolicy);
            JsonArray resolvedRows = new JsonArray();
            try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
                for (int i = 0; i < cells.size(); i++) {
                    AuthorDocument.Cell source = cells.get(i);
                    // Endpoint dependencies matter too: a stationary host can reference a moved/copied cell. Discover
                    // across the frozen source once at this explicit boundary, never on an ordinary local edit.
                    if (!geometry) {
                        resolvedRows.add(source.sourceCopy());
                        continue;
                    }
                    HologramPresets.Cell cell = candidate.cells.get(i);
                    JsonObject sourceRow = source.sourceCopy();
                    HologramPresets.Preset single = new HologramPresets.Preset();
                    single.schema = 2;
                    HologramPresets.Cell basis = HologramLocalAuthorSemantics.cell(sourceRow);
                    basis.x = source.payloadX();
                    basis.y = source.payloadY();
                    basis.z = source.payloadZ();
                    single.cells.add(basis);
                    List<HologramCoordinatePolicies.Relation> relations = HologramCoordinatePolicies
                        .inspect(single, dimension, originX, originY, originZ, facts);
                    for (HologramCoordinatePolicies.Relation relation : relations) {
                        Integer target = null;
                        // Foreign-dimension native links cannot refer to any local endpoint, including an ambiguous
                        // copied origin. Filter them before local coordinate conversion or endpoint lookup.
                        boolean localDimension = relation.targetDimension == dimension
                            || !HologramNativeHubData.adapter(relation.adapter);
                        if (localDimension && !"clear".equals(relation.previousMode)) {
                            int targetX, targetY, targetZ;
                            try {
                                targetX = Math.subtractExact(relation.targetX, originX);
                                targetY = Math.subtractExact(relation.targetY, originY);
                                targetZ = Math.subtractExact(relation.targetZ, originZ);
                            } catch (ArithmeticException overflow) {
                                throw new IOException("Coordinate relation overflow", overflow);
                            }
                            // Absolute bindings keep their exact world endpoint, not a copied payload origin.
                            target = "absolute".equals(relation.previousMode)
                                ? finalPositions.get(key(targetX, targetY, targetZ))
                                : origins.target(targetX, targetY, targetZ, source);
                        }
                        relation.internal = target != null;
                        if (relation.internal && !"absolute".equals(relation.previousMode)
                            && !"clear".equals(relation.previousMode)) {
                            AuthorDocument.Cell endpoint = cells.get(target);
                            relation.targetX = add(originX, endpoint.x());
                            relation.targetY = add(originY, endpoint.y());
                            relation.targetZ = add(originZ, endpoint.z());
                        }
                        relation.x = cell.x;
                        relation.y = cell.y;
                        relation.z = cell.z;
                        relation.id = key(cell.x, cell.y, cell.z) + ":"
                            + relation.payload
                            + ":"
                            + relation.adapter
                            + ":"
                            + relation.slot;
                        relation.dx = subtract(relation.targetX, add(originX, cell.x));
                        relation.dy = subtract(relation.targetY, add(originY, cell.y));
                        relation.dz = subtract(relation.targetZ, add(originZ, cell.z));
                    }
                    single.cells.clear();
                    single.cells.add(cell);
                    HologramCoordinatePolicies.apply(single, relations, policy, facts);
                    JsonObject updated = HologramLocalAuthorSemantics
                        .delta(
                            sourceRow,
                            GSON.toJsonTree(HologramLocalAuthorSemantics.cell(sourceRow)),
                            GSON.toJsonTree(cell))
                        .getAsJsonObject();
                    resolvedRows.add(updated);
                }
            }
            raw.add("cells", resolvedRows);
            // Text/image definitions are native encoded records, not decorative geometry. The existing adapter owns
            // the structure identity/origin override; all other known and unknown definition fields survive.
            if (geometry && raw.has("structures")) {
                JsonArray structures = raw.getAsJsonArray("structures");
                for (int i = 0; i < candidate.structures.size(); i++) {
                    HologramPresets.Structure structure = candidate.structures.get(i);
                    if (structure.textBoxNbt == null || structure.textBoxNbt.isEmpty()) continue;
                    NBTTagCompound old = HologramBlueprintNbt.decode(structure.textBoxNbt);
                    NBTTagCompound resolved = HologramTextWorkflow.definition(structure);
                    if (!old.equals(resolved)) structures.get(i)
                        .getAsJsonObject()
                        .addProperty("textBoxNbt", HologramBlueprintNbt.encodeCanonical(resolved));
                }
            }
            return root;
        }
    }

    private static void validateLittleTranslation(List<HologramPresets.Cell> cells) throws IOException {
        // Reuse the native adapter's bounded voxel/corner index. Values are rotated corners, unused here;
        // keys are the exact original native voxel identities and let translation preserve encoded references.
        Map<String, int[]> references = HologramBlockTransformLittleTiles.presetReferences(cells, false);
        for (HologramPresets.Cell cell : cells) {
            NBTTagCompound root = HologramBlueprintNbt.decode(cell.tileNbt);
            int count = bounded(root.getInteger("tilesCount"));
            for (int i = 0; i < count; i++) {
                NBTTagCompound tile = root.getCompoundTag("t" + i);
                if (!tile.getBoolean("isStructure")) continue;
                if (tile.getBoolean("main")) {
                    int members = bounded(tile.getInteger("count"));
                    for (int member = 0; member < members; member++)
                        littleReference(cell, tile, "i" + member, references);
                } else littleReference(cell, tile, "", references);
            }
        }
    }

    private static void littleReference(HologramPresets.Cell cell, NBTTagCompound tile, String prefix,
        Map<String, int[]> references) throws IOException {
        if (tile.hasKey(prefix + "coX"))
            throw new IOException("Legacy LittleTiles absolute reference requires recapture; draft retained");
        String target = key(
            add(cell.x, tile.getInteger(prefix + "coordX")),
            add(cell.y, tile.getInteger(prefix + "coordY")),
            add(cell.z, tile.getInteger(prefix + "coordZ"))) + ":"
            + key(tile.getInteger(prefix + "posx"), tile.getInteger(prefix + "posy"), tile.getInteger(prefix + "posz"));
        if (!references.containsKey(target))
            throw new IOException("Move/copy the complete LittleTiles reference graph; draft retained");
    }

    private static int bounded(int count) throws IOException {
        if (count < 0 || count > 65536) throw new IOException("LittleTiles entry count exceeds blueprint bound");
        return count;
    }

    private static boolean eligibleEndpoint(AuthorDocument.Cell cell, Set<String> enabledStructures) {
        return !cell.excluded() && (cell.memberships()
            .isEmpty() || !Collections.disjoint(cell.memberships(), enabledStructures));
    }

    /** One source pass; lookup never rescans copies or retains the source list. */
    static final class EndpointIndex {

        private final Map<Vector, Endpoints> origins = new HashMap<>();

        EndpointIndex(List<AuthorDocument.Cell> cells, Set<String> enabledStructures) {
            int index = 0;
            for (AuthorDocument.Cell cell : cells) {
                if (eligibleEndpoint(cell, enabledStructures)) {
                    Endpoints endpoints = origins.computeIfAbsent(
                        new Vector(cell.payloadX(), cell.payloadY(), cell.payloadZ()),
                        ignored -> new Endpoints());
                    endpoints.add(shift(cell), index);
                }
                index++;
            }
        }

        Integer target(int x, int y, int z, AuthorDocument.Cell host) throws IOException {
            Endpoints endpoints = origins.get(new Vector(x, y, z));
            if (endpoints == null) return null;
            // A sole endpoint is selected whether or not its translation matches the host.
            if (endpoints.byShift == null) return endpoints.sole;
            Integer match = endpoints.byShift.get(shift(host));
            if (match != null) {
                if (match < 0) throw new IOException("Ambiguous copied coordinate endpoint");
                return match;
            }
            throw new IOException("Ambiguous coordinate endpoint after copy; original draft retained");
        }
    }

    private static final class Endpoints {

        private Map<Vector, Integer> byShift;
        private Vector soleShift;
        private int sole = -1;

        void add(Vector shift, int index) {
            if (sole < 0) {
                sole = index;
                soleShift = shift;
                return;
            }
            // Most origins are not copied; avoid a second HashMap and table for every ordinary source cell.
            if (byShift == null) {
                byShift = new HashMap<>();
                byShift.put(soleShift, sole);
                soleShift = null;
            }
            // A duplicate matching shift is ambiguous; duplicates at unrelated shifts do not block a match.
            byShift.merge(shift, index, (previous, added) -> -1);
        }
    }

    private static final class Vector {

        final long x, y, z;

        Vector(long x, long y, long z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Vector)) return false;
            Vector vector = (Vector) other;
            return x == vector.x && y == vector.y && z == vector.z;
        }

        @Override
        public int hashCode() {
            return (Long.hashCode(x) * 31 + Long.hashCode(y)) * 31 + Long.hashCode(z);
        }
    }

    private static Set<String> knownFields(Class<?> type) {
        Set<String> known = new HashSet<>();
        for (Field field : type.getDeclaredFields())
            if (!Modifier.isTransient(field.getModifiers()) && !Modifier.isStatic(field.getModifiers()))
                known.add(field.getName());
        // Local declaration completeness is a nonspatial UI fact, never a portable/server authority.
        if (type == HologramPresets.Cell.class) known.add("authorDeclarationComplete");
        return Collections.unmodifiableSet(known);
    }

    private static void rejectExtensions(JsonObject source, Class<?> type, String context) throws IOException {
        Set<String> known;
        if (type == HologramPresets.Cell.class) known = CELL_FIELDS;
        else if (type == HologramPresets.Structure.class) known = STRUCTURE_FIELDS;
        else if (type == HologramPresets.HintArea.class) known = HINT_FIELDS;
        else throw new IOException("Unsupported transformed record type: " + type.getName());
        for (Map.Entry<String, JsonElement> entry : source.entrySet())
            if (!known.contains(entry.getKey())) throw new IOException(
                "Unsupported transformed extension " + context + "/" + entry.getKey() + "; source retained");
    }

    private static JsonObject blueprint(JsonObject source) {
        return source.has("blueprint") ? source.getAsJsonObject("blueprint") : source;
    }

    private static boolean moved(AuthorDocument.Cell cell) {
        return cell.x() != cell.payloadX() || cell.y() != cell.payloadY() || cell.z() != cell.payloadZ();
    }

    private static Vector shift(AuthorDocument.Cell cell) {
        return new Vector(
            (long) cell.x() - cell.payloadX(),
            (long) cell.y() - cell.payloadY(),
            (long) cell.z() - cell.payloadZ());
    }

    private static String key(long x, long y, long z) {
        return x + "," + y + "," + z;
    }

    private static int add(int a, int b) throws IOException {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new IOException("Coordinate overflow", e);
        }
    }

    private static int subtract(int a, int b) throws IOException {
        try {
            return Math.subtractExact(a, b);
        } catch (ArithmeticException e) {
            throw new IOException("Coordinate overflow", e);
        }
    }
}
