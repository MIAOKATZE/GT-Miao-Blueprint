package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;

/**
 * Explicit, selected-record native operations for the local author. This is not admission, a world permission,
 * or a source document: callers atomically install the returned records into their document revision.
 */
public final class HologramLocalAuthorSemantics {

    private static final Gson GSON = new Gson();
    private static final int MAX_SELECTED = 8192;
    private static final long PAGE_BYTES = 256L * 1024;

    private HologramLocalAuthorSemantics() {}

    /** Explicit legacy-template migration; never use the DTO serialization as the source document. */
    public static JsonObject upgradeLegacy(JsonObject source) throws IOException {
        HologramPresets.Preset before = preset(source);
        if (before.schema != 1) return copy(source).getAsJsonObject();
        HologramPresets.Preset after = HologramPresets.upgradeLegacy(before);
        if (after.structures.isEmpty()) {
            for (HologramPresets.Cell controller : after.cells) {
                if (!controller.controller) continue;
                HologramPresets.Structure structure = new HologramPresets.Structure();
                structure.id = java.util.UUID.randomUUID()
                    .toString();
                structure.name = after.name == null ? "Template" : after.name;
                structure.sourceClass = after.sourceClass;
                structure.sourceMod = after.sourceMod;
                structure.controllerId = after.controllerId;
                structure.x = controller.x;
                structure.y = controller.y;
                structure.z = controller.z;
                structure.main = 1;
                after.structures.add(structure);
                for (HologramPresets.Cell member : after.cells) {
                    member.structureId = structure.id;
                    member.structureIds = new ArrayList<>(java.util.Collections.singletonList(structure.id));
                }
                break;
            }
        }
        return delta(source, GSON.toJsonTree(before), GSON.toJsonTree(after)).getAsJsonObject();
    }

    /** Expensive semantic work is an explicit action, never part of opening or saving a local draft. */
    public static JsonObject process(JsonObject source, NBTTagCompound policy, int dimension, int x, int y, int z)
        throws IOException {
        HologramPresets.Preset before = preset(source);
        JsonElement knownBefore = GSON.toJsonTree(before);
        HologramPresets.Preset after = HologramBlueprintProcessing
            .processDetached(before, HologramBlueprintProcessing.Policy.read(policy), dimension, x, y, z);
        after = HologramAePolicy.sanitizeOwned(
            after,
            java.util.Collections.emptyMap(),
            java.util.Collections.emptyMap(),
            HologramAePolicy.PreparationProgress.NONE).preset;
        HologramBlueprintProcessing.enforcePolicy(after);
        return delta(source, knownBefore, GSON.toJsonTree(after)).getAsJsonObject();
    }

    /** Existing native whole-blueprint rotation, invoked only by an explicit author command. */
    public static JsonObject rotateBlueprint(JsonObject source) throws IOException {
        HologramPresets.Preset detached = preset(source);
        JsonElement before = GSON.toJsonTree(detached);
        HologramWorldPlacementWorkflow.turnSavedPreset(detached);
        return delta(source, before, GSON.toJsonTree(detached)).getAsJsonObject();
    }

    public static JsonObject configureQuantumGroups(JsonObject source, NBTTagList groups) throws IOException {
        HologramPresets.Preset input = preset(source);
        JsonElement before = GSON.toJsonTree(input);
        HologramPresets.Preset result = HologramQuantumGroups.configure(input, groups);
        HologramBlueprintProcessing.invalidate(result, "quantum");
        return delta(source, before, GSON.toJsonTree(result)).getAsJsonObject();
    }

    public static NBTTagList processingPresets() throws IOException {
        return HologramProcessingPresets.labels();
    }

    /** Existing choices or unconfirmed defaults from metadata only, never an approval. */
    public static NBTTagCompound policyChoices(JsonObject header) throws IOException {
        return HologramBlueprintProcessing.policy(preset(header))
            .write();
    }

    /** Explicit global retention inspector. Deduplicates exact descriptors and never runs from ordinary edits. */
    public static NBTTagCompound globalNbtDetails(AuthorDocument document) throws IOException {
        Map<String, NBTTagCompound> roots = new java.util.LinkedHashMap<>();
        Set<String> paths = new LinkedHashSet<>();
        Set<List<String>> seen = new java.util.HashSet<>();
        try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
            for (AuthorDocument.Cell source : document.cells()) {
                if ((source.tileNbt() == null || source.tileNbt()
                    .isEmpty()) && (source.itemNbt() == null
                        || source.itemNbt()
                            .isEmpty()))
                    continue;
                if (!seen.add(java.util.Arrays.asList(source.block(), source.tileNbt(), source.itemNbt()))) continue;
                NBTTagList entries = HologramBlueprintProcessing.nbtEntries(cell(source.sourceCopy()), facts);
                for (int i = 0; i < entries.tagCount(); i++) {
                    NBTTagCompound entry = entries.getCompoundTagAt(i);
                    String path = entry.getString("path"), key = entry.getString("key");
                    paths.add(path);
                    if (path.indexOf('/', path.indexOf('/') + 1) < 0) {
                        NBTTagCompound previous = roots.get(key);
                        if (previous == null) {
                            previous = (NBTTagCompound) entry.copy();
                            previous.setBoolean("editable", false); // Global choices are retention, not value edits.
                            roots.put(key, previous);
                        } else if (entry.getBoolean("protected")) previous.setBoolean("protected", true);
                    }
                    if (roots.size() > 256 || paths.size() > 2048)
                        throw new IOException("全局 NBT 路径超过有界视图，请选择具体方块检查；源数据保留");
                }
            }
        }
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList entries = new NBTTagList();
        for (NBTTagCompound entry : roots.values()) entries.appendTag(entry);
        result.setTag("nbtEntries", entries);
        result.setTag("nbtKeys", HologramBlueprintProcessing.strings(new ArrayList<>(roots.keySet())));
        result.setTag("nbtPaths", HologramBlueprintProcessing.strings(new ArrayList<>(paths)));
        result.setBoolean("complete", true);
        return result;
    }

    /** Explicit path view reads this selected payload once, rather than re-decoding for every detail page. */
    public static List<String> selectedNbtPaths(JsonObject sourceCell) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
            NBTTagList entries = HologramBlueprintProcessing.nbtEntries(cell(sourceCell), facts);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                String path = entry.getString("path");
                if (!entry.getBoolean("protected") && !path.isEmpty()) paths.add(path);
            }
        }
        return new ArrayList<>(paths);
    }

    /** Instance-local policy library; does not write a server's policy library in multiplayer. */
    public static NBTTagCompound processingPreset(String operation, NBTTagCompound request) throws IOException {
        String id = request.getString("presetId");
        if ("loadProcessingPreset".equals(operation)) {
            HologramProcessingPresets.Entry entry = HologramProcessingPresets.get(id);
            if (entry == null) throw new IOException("处理流程预设不存在");
            NBTTagCompound policy = entry.policy.write();
            policy.setTag("confirmedStages", new NBTTagList());
            return policy;
        }
        if ("deleteProcessingPreset".equals(operation)) {
            HologramProcessingPresets.delete(id);
            return new NBTTagCompound();
        }
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.Policy
            .read(request.getCompoundTag("policy"));
        policy.resourceDecisions.clear();
        policy.cellNbtOverrides.clear();
        if ("saveProcessingPreset".equals(operation))
            return HologramProcessingPresets.save(request.getString("name"), policy)
                .write();
        if ("updateProcessingPreset".equals(operation))
            return HologramProcessingPresets.update(id, request.getString("name"), policy)
                .write();
        throw new IOException("Unknown local processing preset operation");
    }

    /** Caller caches by exact document revision and chosen policy; this must not run from a render loop. */
    public static NBTTagCompound processingState(JsonObject source, NBTTagCompound policy, int dimension, int x, int y,
        int z) throws IOException {
        HologramPresets.Preset input = preset(source);
        HologramBlueprintProcessing.Policy selected = policy == null ? HologramBlueprintProcessing.policy(input)
            : HologramBlueprintProcessing.Policy.read(policy);
        return HologramBlueprintProcessing.state(input, selected, dimension, x, y, z);
    }

    /** Rotate disposable selected native values. The caller owns the atomic geometric/structure transaction. */
    public static List<JsonObject> rotateCells(List<JsonObject> sourceCells, boolean vertical,
        Map<String, int[]> references) throws IOException {
        if (sourceCells == null || sourceCells.isEmpty() || sourceCells.size() > MAX_SELECTED)
            throw new IOException("Invalid native rotation selection");
        List<JsonObject> result = new ArrayList<>(sourceCells.size());
        for (JsonObject source : sourceCells) {
            HologramPresets.Cell input = cell(source);
            HologramPresets.Cell rotated = cell(source);
            HologramBlockTransforms.turn(rotated, vertical, references, true);
            result.add(delta(source, GSON.toJsonTree(input), GSON.toJsonTree(rotated)).getAsJsonObject());
        }
        return result;
    }

    /** Only the requested cell's payload is expanded, and only when the inspector is opened. */
    public static NBTTagCompound inspect(JsonObject sourceCell, int displayIndex, int offset, int limit)
        throws IOException {
        return inspect(sourceCell, new JsonObject(), displayIndex, offset, limit);
    }

    /** Header excludes cells: inspecting a selection must not materialize the entire document. */
    public static NBTTagCompound inspect(JsonObject sourceCell, JsonObject header, int displayIndex, int offset,
        int limit) throws IOException {
        if (displayIndex < 0 || offset < 0 || limit < 1 || limit > 128)
            throw new IOException("Invalid local NBT detail page");
        HologramPresets.Cell cell = cell(sourceCell);
        HologramPresets.Preset metadata = preset(header);
        List<String> selected = metadata.retainNbt ? metadata.retainedNbtKeys : java.util.Collections.emptyList();
        List<String> ignored = java.util.Collections.emptyList();
        NBTTagCompound receipt = HologramBlueprintNbt.decode(metadata.processingNbt);
        if (receipt != null && receipt.hasKey("policy", 10)) {
            HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.Policy
                .read(receipt.getCompoundTag("policy"));
            for (HologramBlueprintProcessing.CellNbtOverride choice : policy.cellNbtOverrides)
                if (choice.x == cell.x && choice.y == cell.y && choice.z == cell.z) {
                    selected = choice.all ? null : choice.keys;
                    ignored = choice.ignoredPaths;
                    break;
                }
        }
        try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
            NBTTagCompound page = HologramBlueprintProcessing.nbtEntryPage(
                cell,
                facts,
                offset,
                limit,
                PAGE_BYTES,
                selected == null ? null : new LinkedHashSet<>(selected));
            page.setInteger("index", displayIndex);
            page.setInteger("offset", offset);
            page.setBoolean("all", selected == null);
            page.setTag(
                "keys",
                HologramBlueprintProcessing.strings(selected == null ? java.util.Collections.emptyList() : selected));
            page.setTag("ignoredPaths", HologramBlueprintProcessing.strings(ignored));
            page.setTag("entries", page.getTagList("rows", 10));
            page.removeTag("rows");
            return page;
        }
    }

    /**
     * Reuses the existing typed configuration allowlist, not a generic asset/NBT setter. No input is mutated;
     * failures return no partial edit. Unknown JSON members and untouched payload strings remain unchanged.
     */
    public static List<JsonObject> editNbt(List<JsonObject> selectedSourceCells, NBTTagList changes)
        throws IOException {
        if (selectedSourceCells == null || selectedSourceCells.isEmpty() || selectedSourceCells.size() > MAX_SELECTED)
            throw new IOException("请选择需要编辑 NBT 的方块");
        if (changes == null || changes.tagCount() < 1 || changes.tagCount() > 128)
            throw new IOException("NBT 修改条目数量无效");
        HologramPresets.Preset selected = new HologramPresets.Preset();
        selected.schema = 2;
        int[] indices = new int[selectedSourceCells.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
            selected.cells.add(cell(selectedSourceCells.get(i)));
        }
        for (int i = 0; i < changes.tagCount(); i++) {
            NBTTagCompound change = changes.getCompoundTagAt(i);
            HologramBlueprintProcessing.editNbtValue(
                selected,
                indices,
                change.getString("path"),
                change.getString("type"),
                change.getString("value"));
        }
        List<JsonObject> result = new ArrayList<>(indices.length);
        for (int i = 0; i < indices.length; i++) {
            JsonObject record = copy(selectedSourceCells.get(i)).getAsJsonObject();
            HologramPresets.Cell edited = selected.cells.get(i);
            replacePayload(record, "tileNbt", edited.tileNbt);
            replacePayload(record, "itemNbt", edited.itemNbt);
            result.add(record);
        }
        return result;
    }

    static HologramPresets.Cell cell(JsonObject source) throws IOException {
        if (source == null) throw new IOException("Missing source cell");
        try {
            return GSON.fromJson(source, HologramPresets.Cell.class);
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid selected source cell", malformed);
        }
    }

    static HologramPresets.Preset preset(JsonObject source) throws IOException {
        if (source == null) throw new IOException("Missing source blueprint");
        try {
            HologramPresets.Preset result = GSON.fromJson(source, HologramPresets.Preset.class);
            if (result.cells == null || result.palette == null) throw new IOException("Missing blueprint records");
            return result;
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid source blueprint", malformed);
        }
    }

    /** Apply only fields actually changed by a known semantic adapter; unknown source members survive. */
    static JsonElement delta(JsonElement source, JsonElement before, JsonElement after) throws IOException {
        return delta(source, before, after, "");
    }

    private static JsonElement delta(JsonElement source, JsonElement before, JsonElement after, String field)
        throws IOException {
        if (java.util.Objects.equals(before, after)) return copy(source);
        if (before != null && after != null
            && before.isJsonObject()
            && after.isJsonObject()
            && source != null
            && source.isJsonObject()) {
            JsonObject result = copy(source).getAsJsonObject();
            JsonObject oldObject = before.getAsJsonObject(), newObject = after.getAsJsonObject();
            Set<String> keys = new LinkedHashSet<>();
            for (Map.Entry<String, JsonElement> entry : oldObject.entrySet()) keys.add(entry.getKey());
            for (Map.Entry<String, JsonElement> entry : newObject.entrySet()) keys.add(entry.getKey());
            for (String key : keys) {
                if (java.util.Objects.equals(oldObject.get(key), newObject.get(key))) continue;
                if (!newObject.has(key)) result.remove(key);
                else result.add(key, delta(result.get(key), oldObject.get(key), newObject.get(key), key));
            }
            return result;
        }
        if (before != null && after != null
            && source != null
            && before.isJsonArray()
            && after.isJsonArray()
            && source.isJsonArray()) {
            JsonArray oldRows = before.getAsJsonArray(), newRows = after.getAsJsonArray(),
                raw = source.getAsJsonArray();
            // Native transforms preserve source order for geometry and bounds. Other record arrays (issues,
            // declarations, structures) can be replaced/reordered and must match identity, not just length.
            if (oldRows.size() == newRows.size() && raw.size() == oldRows.size()
                && ("cells".equals(field) || "palette".equals(field) || "hintAreas".equals(field))) {
                JsonArray result = new JsonArray();
                for (int i = 0; i < oldRows.size(); i++) result.add(delta(raw.get(i), oldRows.get(i), newRows.get(i)));
                return result;
            }
            // Generated hints and report arrays can grow/shrink. Preserve raw extensions on unchanged retained
            // rows instead of serializing those rows through a lossy typed DTO. Equal duplicate rows stay ordered.
            Map<JsonElement, java.util.ArrayDeque<Integer>> retained = new java.util.HashMap<>();
            Map<String, java.util.ArrayDeque<Integer>> identities = new java.util.HashMap<>();
            for (int i = 0; i < oldRows.size(); i++) {
                retained.computeIfAbsent(oldRows.get(i), ignored -> new java.util.ArrayDeque<>())
                    .addLast(i);
                String identity = rowIdentity(oldRows.get(i));
                if (identity != null) identities.computeIfAbsent(identity, ignored -> new java.util.ArrayDeque<>())
                    .addLast(i);
            }
            Set<Integer> consumed = new java.util.HashSet<>();
            JsonArray result = new JsonArray();
            for (JsonElement row : newRows) {
                java.util.ArrayDeque<Integer> matches = retained.get(row);
                Integer index = nextMatch(matches, consumed);
                if (index == null) index = nextMatch(identities.get(rowIdentity(row)), consumed);
                if (index != null) consumed.add(index);
                result.add(
                    index != null && index < raw.size() ? delta(raw.get(index), oldRows.get(index), row) : copy(row));
            }
            return result;
        }
        return copy(after);
    }

    private static Integer nextMatch(java.util.ArrayDeque<Integer> matches, Set<Integer> consumed) {
        while (matches != null && !matches.isEmpty()) {
            Integer index = matches.removeFirst();
            if (!consumed.contains(index)) return index;
        }
        return null;
    }

    private static String rowIdentity(JsonElement row) {
        if (row == null || !row.isJsonObject()) return null;
        JsonObject object = row.getAsJsonObject();
        if (object.has("id") && !object.get("id")
            .isJsonNull()) return "id:" + object.get("id");
        if (object.has("item") && object.has("damage"))
            return "item:" + object.get("item") + ":" + object.get("damage");
        if (object.has("x1") && object
            .has("y1") && object.has("z1") && object.has("x2") && object.has("y2") && object.has("z2"))
            return "box:" + object.get("x1")
                + ":"
                + object.get("y1")
                + ":"
                + object.get("z1")
                + ":"
                + object.get("x2")
                + ":"
                + object.get("y2")
                + ":"
                + object.get("z2");
        if (object.has("x") && object.has("y") && object.has("z")) return "pos:" + object.get(
            "x") + ":" + object.get("y") + ":" + object.get("z") + (object.has("path") ? ":" + object.get("path") : "");
        return null;
    }

    private static void replacePayload(JsonObject record, String key, String value) {
        JsonElement previous = record.get(key);
        // Preserve the distinction between a missing field and an explicit JSON null.
        if (value == null && (previous == null || previous.isJsonNull())) return;
        record.addProperty(key, value);
    }

    static JsonElement copy(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value;
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) array.add(copy(item));
            return array;
        }
        JsonObject object = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject()
            .entrySet()) object.add(entry.getKey(), copy(entry.getValue()));
        return object;
    }
}
