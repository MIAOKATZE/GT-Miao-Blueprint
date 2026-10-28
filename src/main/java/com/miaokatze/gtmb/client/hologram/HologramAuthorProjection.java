package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/**
 * Disposable, one-way document render owner. Source records are never reconstructed from this view.
 * Cold geometry uses direct typed cells; only visible native consumers decode bounded opaque payloads.
 * Dense render slots are independent of durable cell IDs: deletion moves at most the old tail slot.
 */
final class HologramAuthorProjection implements AutoCloseable {

    private static final int MAX_QUEUED = 512, MAX_DEPENDENCIES = 65536;
    private static final long MAX_RESIDENT = 32L * 1024 * 1024;
    private final Thread owner = Thread.currentThread();
    private AuthorDocument document, metadataDocument;
    private HologramState view;
    private final List<Long> ids = new ArrayList<>();
    private final Map<Long, Integer> slots = new HashMap<>();
    private final Set<Long> queued = new LinkedHashSet<>();
    private final LinkedHashMap<Long, Long> loaded = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<Long, Long> budgetDeferred = new LinkedHashMap<>();
    private final Map<HologramRegionCache.Key, Set<HologramRegionCache.Key>> readers = new HashMap<>();
    private long resident;
    private int dependencyCount;
    private boolean dependencyOverflow, closed;

    HologramAuthorProjection(AuthorDocument document) {
        this.document = document;
        Map<Integer, HologramState.Cell> initial = new LinkedHashMap<>();
        for (AuthorDocument.Cell cell : document.cells()) {
            int index = ids.size();
            ids.add(cell.id());
            slots.put(cell.id(), index);
            initial.put(index, new HologramState.Cell(index, cell));
        }
        view = new HologramState(this, metadata(), null, ids.size(), initial);
    }

    String documentId() {
        return document.documentId();
    }

    HologramState view() {
        check();
        return view;
    }

    long cellId(int index) {
        return index < 0 || index >= ids.size() ? -1 : ids.get(index);
    }

    int index(long id) {
        return slots.getOrDefault(id, -1);
    }

    HologramState update(AuthorEditor.Change change) {
        check();
        if (change.before() != document) throw new IllegalArgumentException("Stale document render change");
        document = change.after();
        Map<Integer, HologramState.Cell> replacements = new LinkedHashMap<>();
        List<Long> removals = new ArrayList<>(change.removedIds());
        removals.sort((left, right) -> Integer.compare(index(right), index(left)));
        for (long id : removals) {
            Integer removed = slots.remove(id);
            if (removed == null) continue;
            retire(id);
            int tail = ids.size() - 1;
            long moved = ids.remove(tail);
            replacements.remove(tail);
            if (removed != tail) {
                ids.set(removed, moved);
                slots.put(moved, removed);
                replacements.put(removed, new HologramState.Cell(removed, document.cell(moved)));
                retire(moved);
            }
        }
        for (long id : change.changedIds()) {
            AuthorDocument.Cell cell = document.cell(id);
            if (cell == null) continue;
            Integer index = slots.get(id);
            if (index == null) {
                index = ids.size();
                ids.add(id);
                slots.put(id, index);
            }
            retire(id);
            replacements.put(index, new HologramState.Cell(index, cell));
        }
        publish(metadata(), replacements);
        return view;
    }

    /** UI-only decorations never acquire a server sequence, detail manifest, receipt or source authority. */
    HologramState refreshMetadata(NBTTagCompound changes) {
        check();
        NBTTagCompound data = new NBTTagCompound();
        for (Object name : view.data.func_150296_c()) {
            String key = (String) name;
            data.setTag(key, view.data.getTag(key));
        }
        boolean modified = false;
        for (String key : new String[] { "title", "presetName", "blueprintName", "draft", "dirty", "blueprintDirty",
            "localDraftPath", "selectedIndices", "selected", "libraryLocalId", "selectedPresetId", "blueprintSaved",
            "editCanUndo", "editCanRedo", "editCanPaste", "editUndoCount", "editRedoCount", "activeStructure",
            "authorDiagnostic", "processing", "processingPolicy", "processingPresets", "capabilities", "main",
            "mainExplicit", "channels", "noHatches", "facing", "blueprintProcessingRequired",
            "blueprintProcessingComplete", "materialChoices", "materialCataloguePending", "materialCatalogueStatus",
            "materialMain", "materialContainers", "materialMe", "materialPriority", "networkBindings", "mode", "scope",
            "layer", "hatchFacings", "candidateRoles", "candidateTiers" }) {
            if (changes.hasKey(key) && !java.util.Objects.equals(view.data.getTag(key), changes.getTag(key))) {
                data.setTag(
                    key,
                    changes.getTag(key)
                        .copy());
                modified = true;
            }
        }
        if (modified) publish(data, Collections.emptyMap());
        return view;
    }

    void request(HologramState state, HologramState.Cell cell) {
        check();
        if (state.authorProjection != this || index(cell.nativeModelId) != cell.index) return;
        HologramState.Cell latest = view.cells.get(cell.index);
        if (!latest.modelPending(false) && !latest.modelPending(true)) {
            loaded.get(cell.nativeModelId);
            return;
        }
        if (queued.size() < MAX_QUEUED) queued.add(cell.nativeModelId);
    }

    /** Called on the owning client thread. One payload/native preparation is an indivisible bounded operation. */
    boolean advance() {
        check();
        if (queued.isEmpty() && budgetDeferred.isEmpty()) return false;
        long deadline = System.nanoTime() + 2_000_000L;
        Map<Integer, HologramState.Cell> replacements = new LinkedHashMap<>();
        // Rotate a bounded set of deferred cells. Recovery does not repeatedly decode a known-too-large demand.
        int retries = Math.min(8, budgetDeferred.size());
        for (int retry = 0; retry < retries && System.nanoTime() < deadline; retry++) {
            Iterator<Map.Entry<Long, Long>> waiting = budgetDeferred.entrySet()
                .iterator();
            Map.Entry<Long, Long> first = waiting.next();
            long id = first.getKey(), need = first.getValue();
            waiting.remove();
            int slot = index(id);
            if (slot < 0) continue;
            if (HologramRenderer.authorCellVisible(view, view.cells.get(slot)) && queued.size() < MAX_QUEUED
                && makeRoom(need, replacements)) queued.add(id);
            else budgetDeferred.put(id, need);
        }
        int work = 0;
        while (!queued.isEmpty() && work < 4 && (work == 0 || System.nanoTime() < deadline)) {
            Iterator<Long> iterator = queued.iterator();
            long id = iterator.next();
            iterator.remove();
            int index = index(id);
            if (index < 0) continue;
            AuthorDocument.Cell source = document.cell(id);
            HologramState.Cell cold = new HologramState.Cell(index, source);
            NBTTagCompound row = cold.tag();
            long charge = 8192;
            try {
                row.setInteger("targetFacing", source.facing());
                row.setString("prompt", source.prompt() == null ? "" : source.prompt());
                row.setString("previewItem", source.item() == null ? "" : source.item());
                row.setInteger("previewDamage", source.damage());
                Decoded tile = decode(source.tileNbt()), item = decode(source.itemNbt());
                charge += tile.charge + item.charge;
                if (!makeRoom(charge, replacements)) {
                    budgetDeferred.put(id, charge);
                    throw new NativeBudget();
                }
                if (tile.value != null) row.setTag("previewTile", tile.value);
                if (item.value != null) row.setTag("previewItemTag", item.value);
                HologramBlueprintPreview.completeCell(row);
                row.setTag("actualDisplay", row.getCompoundTag("targetDisplay"));
                if (row.hasKey("targetStack")) row.setTag("actualStack", row.getTag("targetStack"));
            } catch (IOException | RuntimeException failure) {
                // An unsupported native preview is explicit terminal shell, never a source/document error.
                row = cold.tag();
                row.setString("sourceWarning", "Native preview unavailable: " + failure.getMessage());
                row.setString("renderKind", failure instanceof NativeBudget ? "localBudget" : "localFallback");
                row.setString("status", "unsupported");
                charge = 8192;
            }
            row.setInteger("nativeModelPending", 0);
            // Unsupported data is terminal for this revision; resident pressure remains a resumable budget shell.
            if (!"localFallback".equals(row.getString("renderKind"))
                && !"localBudget".equals(row.getString("renderKind"))) {
                Long old = loaded.put(id, charge);
                resident += charge - (old == null ? 0 : old);
            }
            replacements.put(index, new HologramState.Cell(index, row));
            work++;
        }
        if (replacements.isEmpty()) return false;
        publish(view.data, replacements);
        return true;
    }

    /** Evict only offscreen native data, with bounded traversal; visible details never thrash each other. */
    private boolean makeRoom(long need, Map<Integer, HologramState.Cell> replacements) {
        if (need > MAX_RESIDENT) return false;
        int checks = Math.min(32, loaded.size());
        for (int check = 0; resident + need > MAX_RESIDENT && check < checks; check++) {
            Iterator<Map.Entry<Long, Long>> entries = loaded.entrySet()
                .iterator();
            Map.Entry<Long, Long> entry = entries.next();
            long id = entry.getKey(), charge = entry.getValue();
            int slot = index(id);
            if (slot >= 0 && retainedVisible(view.cells.get(slot))) {
                loaded.get(id); // Advance the bounded eviction cursor without invalidating a visible native model.
            } else {
                entries.remove();
                resident -= charge;
                if (slot >= 0) replacements.put(slot, new HologramState.Cell(slot, document.cell(id)));
            }
        }
        return resident + need <= MAX_RESIDENT;
    }

    private boolean retainedVisible(HologramState.Cell cell) {
        if (HologramRenderer.authorCellVisible(view, cell)) return true;
        // Unknown dependencies must invalidate native output, not permanently pin every decoded payload.
        // Any eviction while overflowed is published with whole-owner invalidation before another draw.
        if (dependencyOverflow) return false;
        int checked = 0;
        for (HologramRegionCache.Key reader : readers
            .getOrDefault(HologramRegionCache.key(cell), Collections.emptySet())) {
            if (++checked > 64) {
                readers.clear();
                dependencyOverflow = true;
                return false; // Same conservative retirement rule when this bounded query cannot prove eviction safe.
            }
            if (HologramRenderer.authorRegionVisible(view, reader)) return true;
        }
        return false;
    }

    private static final class NativeBudget extends IOException {

        NativeBudget() {
            super("Native preview resident budget; retrying when visible capacity is available");
        }
    }

    /** Persist all native reads (including empty coordinates), not only pending tile dependencies. */
    void dependency(HologramState.Cell reader, int x, int y, int z) {
        if (reader == null || dependencyOverflow) return;
        HologramRegionCache.Key input = new HologramRegionCache.Key(x >> 4, y >> 4, z >> 4);
        HologramRegionCache.Key output = HologramRegionCache.key(reader);
        Set<HologramRegionCache.Key> set = readers.computeIfAbsent(input, ignored -> new HashSet<>());
        if (set.add(output) && ++dependencyCount > MAX_DEPENDENCIES) {
            readers.clear();
            dependencyOverflow = true;
        }
    }

    private void publish(NBTTagCompound metadata, Map<Integer, HologramState.Cell> replacements) {
        HologramState previous = view;
        view = new HologramState(this, metadata, previous, ids.size(), replacements);
        Set<HologramRegionCache.Key> dirty = HologramRegionCache.affected(view);
        Set<HologramRegionCache.Key> dependencies = new HashSet<>();
        boolean retireUnknownDependencies = dependencyOverflow && !dirty.isEmpty();
        if (retireUnknownDependencies) {
            dependencies.addAll(previous.regions.regions.keySet());
            dependencies.addAll(view.regions.regions.keySet());
        } else {
            List<HologramRegionCache.Key> frontier = new ArrayList<>(dirty);
            for (int i = 0; i < frontier.size(); i++)
                for (HologramRegionCache.Key reader : readers.getOrDefault(frontier.get(i), Collections.emptySet()))
                    if (dependencies.add(reader)) frontier.add(reader);
        }
        HologramRenderer.authorSnapshotChanged(previous, view, dependencies);
        if (retireUnknownDependencies) {
            // All old native readers were retired (or this document is inactive). Rebuild the bounded graph
            // from subsequent native draws; resetting before renderer retirement would allow stale meshes.
            readers.clear();
            dependencyCount = 0;
            dependencyOverflow = false;
        }
    }

    private NBTTagCompound metadata() {
        NBTTagCompound data = new NBTTagCompound();
        if (view != null) for (Object name : view.data.func_150296_c()) {
            String key = (String) name;
            data.setTag(key, view.data.getTag(key));
        }
        data.setBoolean("localAuthor", true);
        data.setBoolean("virtual", true);
        data.setBoolean("virtualLocked", !document.editable());
        data.setString("session", document.documentId());
        data.setString("blueprintName", document.title());
        data.setString("virtualPresetName", document.title());
        data.setString("title", document.title());
        data.setLong("planRevision", document.revision());
        data.setString("authorDiagnostic", document.diagnostic());
        data.setBoolean("draft", true);
        data.setBoolean("processingReady", false);
        NBTTagCompound processing = new NBTTagCompound();
        processing.setString("status", "not_reviewed");
        processing.setString("diagnostic", "Local draft; native/export review has not been performed");
        data.setTag("processing", processing);
        if (metadataDocument == null || !document.sameHeader(metadataDocument)) sourceHeader(data);
        if (metadataDocument != null && document.sameStructures(metadataDocument)) {
            metadataDocument = document;
            return data;
        }
        NBTTagList structures = new NBTTagList();
        for (JsonObject source : document.structures()) {
            NBTTagCompound row = new NBTTagCompound();
            for (String key : new String[] { "id", "name", "sourceMod", "sourceClass" })
                row.setString(key, string(source, key));
            for (String axis : new String[] { "x", "y", "z" }) row.setInteger(axis, integer(source, axis, 0));
            row.setInteger("controllerId", integer(source, "controllerId", -1));
            row.setInteger("main", integer(source, "main", 1));
            row.setBoolean("enabled", bool(source, "enabled", true));
            row.setBoolean("noHatches", bool(source, "noHatches", false));
            structures.appendTag(row);
        }
        data.setTag("structures", structures);
        metadataDocument = document;
        return data;
    }

    /** Only declared UI values are projected; version compatibility remains explicitly unreviewed. */
    private void sourceHeader(NBTTagCompound data) {
        JsonElement prompt = document.headerValue("prompt"), retain = document.headerValue("retainNbt"),
            covers = document.headerValue("retainCovers"), keys = document.headerValue("retainedNbtKeys");
        boolean retainNbt = retain == null || retain.isJsonNull()
            || !retain.isJsonPrimitive()
            || !retain.getAsJsonPrimitive()
                .isBoolean()
            || retain.getAsBoolean();
        data.setString("blueprintPrompt", prompt != null && prompt.isJsonPrimitive() ? prompt.getAsString() : "");
        data.setBoolean("blueprintRetainNbt", retainNbt);
        data.setBoolean(
            "blueprintRetainCovers",
            covers == null || covers.isJsonNull()
                || !covers.isJsonPrimitive()
                || !covers.getAsJsonPrimitive()
                    .isBoolean()
                || covers.getAsBoolean());
        data.setBoolean("blueprintNbtAll", retainNbt && (keys == null || keys.isJsonNull()));
        NBTTagList retainedKeys = new NBTTagList();
        if (keys != null && keys.isJsonArray())
            for (JsonElement key : keys.getAsJsonArray()) if (key.isJsonPrimitive() && key.getAsJsonPrimitive()
                .isString()) retainedKeys.appendTag(new net.minecraft.nbt.NBTTagString(key.getAsString()));
        data.setTag("blueprintNbtKeys", retainedKeys);
        NBTTagList hints = new NBTTagList();
        JsonElement sourceHints = document.headerValue("hintAreas");
        if (sourceHints != null && sourceHints.isJsonArray()) for (JsonElement value : sourceHints.getAsJsonArray()) {
            if (!value.isJsonObject()) continue;
            JsonObject hint = value.getAsJsonObject();
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray(
                "bounds",
                new int[] { integer(hint, "x1", 0), integer(hint, "y1", 0), integer(hint, "z1", 0),
                    integer(hint, "x2", 0), integer(hint, "y2", 0), integer(hint, "z2", 0) });
            row.setString("prompt", string(hint, "prompt"));
            hints.appendTag(row);
        }
        data.setTag("hintAreas", hints);
        NBTTagList versions = new NBTTagList();
        JsonElement sourceVersions = document.headerValue("modVersions");
        if (sourceVersions != null && sourceVersions.isJsonObject())
            for (Map.Entry<String, JsonElement> source : sourceVersions.getAsJsonObject()
                .entrySet()) {
                    if (!source.getValue()
                        .isJsonPrimitive()) continue;
                    NBTTagCompound row = new NBTTagCompound();
                    row.setString("modId", source.getKey());
                    row.setString(
                        "saved",
                        source.getValue()
                            .getAsString());
                    row.setString("state", "unreviewed");
                    versions.appendTag(row);
                }
        data.setTag("modVersions", versions);
    }

    private void retire(long id) {
        queued.remove(id);
        budgetDeferred.remove(id);
        Long charge = loaded.remove(id);
        if (charge != null) resident -= charge;
    }

    private void check() {
        if (closed || Thread.currentThread() != owner)
            throw new IllegalStateException("Document render owner unavailable");
    }

    @Override
    public void close() {
        check();
        closed = true;
        queued.clear();
        loaded.clear();
        budgetDeferred.clear();
        readers.clear();
        slots.clear();
        ids.clear();
        resident = 0;
        HologramRenderer.releaseAuthor(this);
        view = null;
        document = metadataDocument = null;
    }

    private static final class Decoded {

        final NBTTagCompound value;
        final long charge;

        Decoded(NBTTagCompound value, long charge) {
            this.value = value;
            this.charge = charge;
        }
    }

    private static Decoded decode(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) return new Decoded(null, 0);
        if (encoded.length() > ((1024 * 1024 + 2) / 3) * 4) throw new IOException("Native preview encoded budget");
        byte[] bytes = Base64.getDecoder()
            .decode(encoded);
        if (bytes.length > 1024 * 1024) throw new IOException("Native preview compressed budget");
        // The codec enforces expanded bytes, structural depth, and pre-allocation NBT accounting.
        NBTTagCompound value = HologramSnapshotCodec.decodeBounded(bytes, 256 * 1024, 2L * 1024 * 1024);
        return new Decoded(value, 4L * HologramSnapshotCodec.retainedEstimate(value));
    }

    private static String string(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static int integer(JsonObject source, String key, int fallback) {
        JsonElement value = source.get(key);
        try {
            return value == null || !value.isJsonPrimitive() ? fallback : value.getAsInt();
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject source, String key, boolean fallback) {
        JsonElement value = source.get(key);
        return value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isBoolean() ? fallback : value.getAsBoolean();
    }
}
