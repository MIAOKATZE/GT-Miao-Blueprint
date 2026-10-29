package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.hologram.HologramProcessGovernor;

/**
 * Disposable, one-way document render owner. Source records are never reconstructed from this view.
 * Cold geometry uses direct typed cells; only visible native consumers decode bounded opaque payloads.
 * Dense render slots are independent of durable cell IDs: deletion moves at most the old tail slot.
 */
final class HologramAuthorProjection implements AutoCloseable {

    private static final int MAX_QUEUED = 512, MAX_DEPENDENCIES = 65536;
    private final long maxResident;
    private final Thread owner = Thread.currentThread();
    private AuthorDocument document, metadataDocument;
    private HologramState view;
    private final List<Long> ids = new ArrayList<>();
    private final Map<Long, Integer> slots = new HashMap<>();
    private final Set<Long> queued = new LinkedHashSet<>();
    private final LinkedHashMap<Long, Long> loaded = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<Long, Long> budgetDeferred = new LinkedHashMap<>();
    // Transient source-tree working requirements are not final owned-row resident charges.
    private final Map<Long, Long> decodeDeferred = new HashMap<>();
    // Conservative retention decisions, bounded by resident IDs; invalidated by camera/source/dependency changes.
    private final Map<Long, Long> budgetPins = new HashMap<>();
    private long pinnedResident, budgetAdmissionChecks, budgetCapacityDenials, nativeDecodePasses;
    private final Map<HologramRegionCache.Key, Set<HologramRegionCache.Key>> readers = new HashMap<>();
    // Absolute input coordinates -> stable author cell IDs; region edges remain the source-edit fallback.
    private final Map<HologramRegionCache.Key, Set<Long>> exactReaders = new HashMap<>();
    private final Set<HologramRegionCache.Key> coarseDependencyInputs = new HashSet<>();
    private int exactDependencyCount;
    private final Map<HologramRegionCache.Key, Set<HologramRegionCache.Key>> blockReaders = new HashMap<>();
    private long resident;
    private int dependencyCount, blockDependencyCount;
    private boolean dependencyOverflow, blockDependencyOverflow, closed;

    HologramAuthorProjection(AuthorDocument document) {
        this(document, HologramAuthorNativeBudget.configuredBytes());
    }

    /** Exact policy seam for deterministic admission tests, independent of the test JVM's heap size. */
    HologramAuthorProjection(AuthorDocument document, long residentLimit) {
        if (residentLimit <= 0) throw new IllegalArgumentException("positive native resident limit required");
        maxResident = residentLimit;
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
            "layer", "hatchFacings", "candidateRoles", "candidateTiers", "presets", "blueprintSource", "openPurpose",
            "processingReady" }) {
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
        if ("localBudget".equals(latest.renderKind)) {
            // A visible reader can need a hidden-layer neighbor even when the bounded dependency graph
            // overflowed. Renew demand in the existing queue; advance must admit its known cost before decoding.
            Long need = budgetDeferred.get(cell.nativeModelId);
            if (need != null && !capacityBlocked(need) && !decodeOversize(cell.nativeModelId)
                && queued.size() < MAX_QUEUED) queued.add(cell.nativeModelId);
            return;
        }
        if (!latest.modelPending(false) && !latest.modelPending(true)) {
            loaded.get(cell.nativeModelId);
            return;
        }
        if (queued.size() < MAX_QUEUED) queued.add(cell.nativeModelId);
    }

    /** Called on the owning client thread. One payload/native preparation is an indivisible bounded operation. */
    boolean advance() {
        return advance(System::nanoTime);
    }

    /** Cooperative deadline; the clock seam keeps scheduling contracts independent of machine speed. */
    boolean advance(LongSupplier nanoTime) {
        check();
        if (queued.isEmpty() && budgetDeferred.isEmpty()) return false;
        long deadline = nanoTime.getAsLong() + 2_000_000L;
        Map<Integer, HologramState.Cell> replacements = new LinkedHashMap<>();
        // Rotate a bounded set of deferred cells. Recovery does not repeatedly decode a known-too-large demand.
        int retries = Math.min(8, budgetDeferred.size());
        for (int retry = 0; retry < retries && nanoTime.getAsLong() < deadline; retry++) {
            Iterator<Map.Entry<Long, Long>> waiting = budgetDeferred.entrySet()
                .iterator();
            Map.Entry<Long, Long> first = waiting.next();
            long id = first.getKey(), need = first.getValue();
            waiting.remove();
            int slot = index(id);
            if (slot < 0) continue;
            if (!capacityBlocked(need) && !decodeOversize(id) && retainedVisible(view.cells.get(slot)) && queued.size() < MAX_QUEUED
                && makeRoom(need, replacements))
                queued.add(id);
            // Keep the cost until queued execution: earlier items may consume the capacity just checked.
            budgetDeferred.put(id, need);
        }
        int work = 0;
        // UI ticks must not impose an additional four-cell/80-per-second ceiling when preparation is cheap.
        // Consume at most this bounded demand batch, even if callbacks request more work or a clock stalls.
        int remaining = queued.size();
        while (!queued.isEmpty() && remaining-- > 0 && (work == 0 || nanoTime.getAsLong() < deadline)) {
            Iterator<Long> iterator = queued.iterator();
            long id = iterator.next();
            iterator.remove();
            work++; // Failed known-cost admissions consume the deadline too; they must not spin with work == 0.
            int index = index(id);
            if (index < 0) continue;
            try (DecodeBudget decoding = new DecodeBudget()) {
                Long knownDecode = decodeDeferred.get(id);
                if (knownDecode != null && !decoding.reserve(knownDecode)) {
                    continue; // Retry shared transient admission without decompressing or publishing again.
                }
                Long knownNeed = budgetDeferred.get(id);
                if (knownNeed != null) {
                    if (!makeRoom(knownNeed, replacements)) {
                        continue;
                    }
                    budgetDeferred.remove(id);
                }
                AuthorDocument.Cell source = document.cell(id);
                HologramState.Cell cold = new HologramState.Cell(index, source);
                NBTTagCompound row = cold.tag();
                long charge;
                HologramState.Cell prepared;
                try {
                    row.setInteger("targetFacing", source.facing());
                    row.setString("prompt", source.prompt() == null ? "" : source.prompt());
                    row.setString("previewItem", source.item() == null ? "" : source.item());
                    row.setInteger("previewDamage", source.damage());
                    NBTTagCompound tile = decode(source.tileNbt(), decoding), item = decode(source.itemNbt(), decoding);
                    if (tile != null) row.setTag("previewTile", tile);
                    if (item != null) row.setTag("previewItemTag", item);
                    HologramBlueprintPreview.completeCell(row);
                    row.setTag("actualDisplay", row.getCompoundTag("targetDisplay"));
                    if (row.hasKey("targetStack")) row.setTag("actualStack", row.getTag("targetStack"));
                    charge = residentCharge(row);
                    decodeDeferred.remove(id);
                    if (!makeRoom(charge, replacements)) {
                        budgetDeferred.put(id, charge);
                        throw new NativeBudget();
                    }
                    row.setInteger("nativeModelPending", 0);
                    prepared = HologramState.Cell.takeOwnedLocalRow(index, row);
                } catch (IOException | RuntimeException failure) {
                    if (failure instanceof HologramSnapshotCodec.BusyException) {
                        decodeDeferred.put(id, decoding.required);
                        budgetDeferred.put(id, 0L); // Resident charge remains unknown; do not confuse the two ledgers.
                    } else if (!(failure instanceof NativeBudget)) decodeDeferred.remove(id);
                    // An unsupported native preview is explicit terminal shell, never a source/document error.
                    row = cold.tag();
                    row.setString("sourceWarning", "Native preview unavailable: " + failure.getMessage());
                    row.setString("renderKind", failure instanceof NativeBudget
                        || failure instanceof HologramSnapshotCodec.BusyException ? "localBudget" : "localFallback");
                    row.setString("status", "unsupported");
                    charge = 0;
                    row.setInteger("nativeModelPending", 0);
                    prepared = HologramState.Cell.takeOwnedLocalRow(index, row);
                }
                // Unsupported data is terminal for this revision; resident pressure remains a resumable budget shell.
                if (!"localFallback".equals(row.getString("renderKind"))
                    && !"localBudget".equals(row.getString("renderKind"))) {
                    Long old = loaded.put(id, charge);
                    resident += charge - (old == null ? 0 : old);
                }
                replacements.put(index, prepared);
            }
        }
        if (replacements.isEmpty()) return false;
        publish(view.data, replacements, true);
        return true;
    }

    /**
     * Account for the final owned row, not four copies of the input payload. The local preparation path
     * aliases the two displays and two serialized stacks, while Cell independently copies each mutable
     * stack tag. This is conservative ownership accounting, not a JVM/GL/whole-document heap limit.
     */
    static long residentCharge(NBTTagCompound row) {
        // Cell, two stack shells, arrays/list shells, row map and <=32 scalar/tag/map-entry shells,
        // plus per-cell resident/directory bookkeeping. Extra fields cannot silently outgrow the allowance.
        long bytes = 4096L + 128L * Math.max(
            0,
            row.func_150296_c()
                .size() - 32);
        Set<NBTBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        NBTTagCompound variable = new NBTTagCompound();
        for (Object name : row.func_150296_c()) {
            String key = (String) name;
            NBTBase value = row.getTag(key);
            bytes += 2L * key.length();
            if (value instanceof NBTTagString) {
                // Count variable text even when the immutable String is also referenced by the source.
                bytes += 64L + 2L * ((NBTTagString) value).func_150285_a_()
                    .length();
            } else if (value.getId() >= 7 && seen.add(value)) {
                // Includes displays (nativeTile AND added gt.covers), serialized stack, memberships,
                // arrays and any future non-scalar row field. Identity, never content equality, deduplicates.
                if (value instanceof NBTTagCompound && (key.equals("actualDisplay") || key.equals("targetDisplay")))
                    bytes += 128L + 2L * key.length() + wrappedPayloadCharge((NBTTagCompound) value, "nativeTile");
                else if (value instanceof NBTTagCompound && (key.equals("actualStack") || key.equals("targetStack")))
                    bytes += 128L + 2L * key.length() + wrappedPayloadCharge((NBTTagCompound) value, "tag");
                else variable.setTag(key, value);
            }
        }
        bytes += HologramSnapshotCodec.retainedEstimate(variable);
        for (String key : new String[] { "actualStack", "targetStack" })
            bytes += stackTagCharge(row.getCompoundTag(key));
        // Cell copies this hint tree independently; it does not share the row's hint tree.
        if (row.hasKey("specialHint", 10))
            bytes += HologramSnapshotCodec.retainedEstimate(row.getCompoundTag("specialHint"));
        NBTTagList members = row.getTagList("structureIds", 8);
        bytes += 128L * Math.max(
            members.tagCount(),
            row.getString("structureId")
                .isEmpty() ? 0 : 1);
        NBTTagList candidates = row.getTagList("candidates", 10);
        for (int i = 0; i < candidates.tagCount(); i++) bytes += 128L + stackTagCharge(candidates.getCompoundTagAt(i));
        return bytes;
    }

    /**
     * Display/nativeTile and stack/tag are trusted local envelopes, not additional untrusted NBT depth.
     * Charge their map entries while measuring the already bounded source subtree at its original root.
     * Keeping it in a temporary row wrapper would incorrectly reject valid source depths 511 and 512.
     */
    private static long wrappedPayloadCharge(NBTTagCompound envelope, String payloadKey) {
        if (!envelope.hasKey(payloadKey, 10)) return HologramSnapshotCodec.retainedEstimate(envelope);
        NBTTagCompound shell = new NBTTagCompound();
        for (Object name : envelope.func_150296_c()) {
            String key = (String) name;
            if (!key.equals(payloadKey)) shell.setTag(key, envelope.getTag(key));
        }
        return HologramSnapshotCodec.retainedEstimate(shell) + 128L
            + 2L * payloadKey.length()
            + HologramSnapshotCodec.retainedEstimate(envelope.getCompoundTag(payloadKey));
    }

    private static long stackTagCharge(NBTTagCompound stack) {
        return stack.hasKey("tag", 10) ? HologramSnapshotCodec.retainedEstimate(stack.getCompoundTag("tag")) : 0;
    }

    /** Evict only offscreen native data, with bounded traversal; visible details never thrash each other. */
    private boolean makeRoom(long need, Map<Integer, HologramState.Cell> replacements) {
        if (capacityBlocked(need)) {
            budgetCapacityDenials++;
            return false;
        }
        int checks = Math.min(32, loaded.size());
        for (int check = 0; resident + need > maxResident && check < checks; check++) {
            Iterator<Map.Entry<Long, Long>> entries = loaded.entrySet()
                .iterator();
            Map.Entry<Long, Long> entry = entries.next();
            long id = entry.getKey(), charge = entry.getValue();
            int slot = index(id);
            budgetAdmissionChecks++;
            if (budgetPins.containsKey(id) || slot >= 0 && retainedVisible(view.cells.get(slot))) {
                if (budgetPins.putIfAbsent(id, charge) == null) pinnedResident += charge;
                loaded.get(id); // Advance the bounded eviction cursor without invalidating a visible native model.
            } else {
                entries.remove();
                resident -= charge;
                if (slot >= 0) replacements.put(slot, new HologramState.Cell(slot, document.cell(id)));
            }
        }
        return resident + need <= maxResident;
    }

    private boolean capacityBlocked(long need) {
        return need < 0 || need > maxResident || pinnedResident > maxResident - need;
    }

    private boolean decodeOversize(long id) {
        Long need = decodeDeferred.get(id);
        return need != null && need > maxResident;
    }

    /** Short-lived Core reservations cover both decoded input trees until row ownership has been constructed. */
    private final class DecodeBudget implements AutoCloseable {

        private final List<HologramProcessGovernor.Lease> leases = new ArrayList<>(2);
        private long reserved, required;

        boolean reserve(long bytes) {
            if (bytes > maxResident) return false;
            if (bytes <= reserved) return true;
            HologramProcessGovernor.Lease lease = HologramProcessGovernor.tryRetain("author.nativeDecode", bytes - reserved);
            if (lease == null) return false;
            leases.add(lease);
            reserved = bytes;
            return true;
        }

        boolean admit(long bytes) {
            required += bytes;
            return reserve(required);
        }

        public void close() {
            for (HologramProcessGovernor.Lease lease : leases) lease.close();
            leases.clear();
        }
    }

    /** A real eligibility change wakes known-cost demand; model-only publications alone do not. */
    void budgetVisibilityChanged() {
        check();
        budgetPins.clear();
        pinnedResident = 0;
    }

    /** Constant-size diagnostics: source payloads and per-cell identities are never exported. */
    void budgetDiagnostics(NBTTagCompound result) {
        check();
        result.setLong("nativeResidentLimit", maxResident);
        result.setLong("nativeResidentBytes", resident);
        result.setLong("nativePinnedBytes", pinnedResident);
        result.setInteger("nativeBudgetDeferred", budgetDeferred.size());
        result.setInteger("nativeDecodeBudgetDeferred", decodeDeferred.size());
        result.setInteger("nativeQueued", queued.size());
        result.setLong("nativeBudgetAdmissionChecks", budgetAdmissionChecks);
        result.setLong("nativeBudgetCapacityDenials", budgetCapacityDenials);
        result.setLong("nativeDecodePasses", nativeDecodePasses);
    }

    private boolean retainedVisible(HologramState.Cell cell) {
        if (HologramRenderer.authorCellVisible(view, cell)) return true;
        // Unknown dependencies must invalidate native output, not permanently pin every decoded payload.
        // Any eviction while overflowed is published with whole-owner invalidation before another draw.
        if (dependencyOverflow) return false;
        int[] checked = { 0 };
        Set<Long> visited = new HashSet<>();
        List<HologramRegionCache.Key> frontier = new ArrayList<>();
        frontier.add(new HologramRegionCache.Key(cell.x, cell.y, cell.z));
        for (int at = 0; at < frontier.size(); at++) {
            HologramRegionCache.Key position = frontier.get(at);
            HologramRegionCache.Key region = new HologramRegionCache.Key(position.x >> 4, position.y >> 4, position.z >> 4);
            if (coarseDependencyInputs.contains(region) && retainedRegionVisible(region, checked)) return true;
            for (long reader : exactReaders.getOrDefault(position, Collections.emptySet())) {
                // Query exhaustion is conservative retention, not loss of sound provenance or a new content epoch.
                if (++checked[0] > 64) return true;
                if (!visited.add(reader)) continue;
                int slot = index(reader);
                if (slot < 0) continue;
                HologramState.Cell dependent = view.cells.get(slot);
                if (HologramRenderer.authorCellVisible(view, dependent)) return true;
                frontier.add(new HologramRegionCache.Key(dependent.x, dependent.y, dependent.z));
            }
        }
        return false;
    }

    private boolean retainedRegionVisible(HologramRegionCache.Key input, int[] checked) {
        Set<HologramRegionCache.Key> visited = new HashSet<>();
        List<HologramRegionCache.Key> frontier = new ArrayList<>();
        frontier.add(input);
        for (int at = 0; at < frontier.size(); at++) {
            for (HologramRegionCache.Key reader : readers.getOrDefault(frontier.get(at), Collections.emptySet())) {
                if (++checked[0] > 64) return true;
                if (!visited.add(reader)) continue;
                if (HologramRenderer.authorRegionVisible(view, reader)) return true;
                frontier.add(reader);
            }
        }
        return false;
    }

    /** Bounded auxiliary metadata count, not a byte-precise charge against decoded native payload ownership. */
    private int exactDependencyLimit() {
        return (int) Math.max(65536L, Math.min(1048576L, maxResident / 128L));
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
        boolean regionAdded = readers.computeIfAbsent(input, ignored -> new HashSet<>()).add(output);
        if (regionAdded && ++dependencyCount + blockDependencyCount > MAX_DEPENDENCIES) {
            readers.clear();
            exactReaders.clear();
            coarseDependencyInputs.clear();
            exactDependencyCount = 0;
            dependencyCount = 0;
            dependencyOverflow = true;
            budgetVisibilityChanged();
            return;
        }
        if (regionAdded) budgetVisibilityChanged();
        if (coarseDependencyInputs.contains(input)) return;
        HologramRegionCache.Key coordinate = new HologramRegionCache.Key(x, y, z);
        Set<Long> exact = exactReaders.get(coordinate);
        if (exact != null && exact.contains(reader.nativeModelId)) return;
        if (exactDependencyCount >= exactDependencyLimit()) {
            // Keep the already-sound coarse graph. Only this input region degrades; no repeated owner resets.
            coarseDependencyInputs.add(input);
            budgetVisibilityChanged();
            return;
        }
        if (exact == null) exactReaders.put(coordinate, exact = new HashSet<>());
        exact.add(reader.nativeModelId);
        exactDependencyCount++;
        budgetVisibilityChanged();
    }

    /** Known block/meta reads only depend on source facts, not a neighbor's later opaque NBT publication. */
    void blockDependency(HologramState.Cell reader, int x, int y, int z) {
        if (reader == null || blockDependencyOverflow) return;
        HologramRegionCache.Key input = new HologramRegionCache.Key(x >> 4, y >> 4, z >> 4);
        Set<HologramRegionCache.Key> set = blockReaders.computeIfAbsent(input, ignored -> new HashSet<>());
        if (set.add(HologramRegionCache.key(reader)) && ++blockDependencyCount + dependencyCount > MAX_DEPENDENCIES) {
            blockReaders.clear();
            blockDependencyCount = 0;
            blockDependencyOverflow = true;
        }
    }

    private void publish(NBTTagCompound metadata, Map<Integer, HologramState.Cell> replacements) {
        publish(metadata, replacements, false);
    }

    private void publish(NBTTagCompound metadata, Map<Integer, HologramState.Cell> replacements, boolean modelOnly) {
        HologramState previous = view;
        view = new HologramState(this, metadata, previous, ids.size(), replacements);
        view.modelOnly = modelOnly && !view.topologyChanged;
        for (HologramState.Cell old : view.changedPreviousCells) {
            HologramState.Cell next = old.index < view.cells.size() ? view.cells.get(old.index) : null;
            if (next == null || !old.id.equals(next.id)
                || !old.wantId.equals(next.wantId)
                || old.meta != next.meta
                || old.wantMeta != next.wantMeta
                || old.anchor != next.anchor
                || (old.status.equals("protected") && old.actualStack != null)
                    != (next.status.equals("protected") && next.actualStack != null))
                view.modelOnly = false;
        }
        Set<HologramRegionCache.Key> dirty = view.modelOnly ? HologramRegionCache.changedRegions(view)
            : HologramRegionCache.affected(view);
        Set<HologramRegionCache.Key> dependencies = new HashSet<>();
        boolean retireUnknownDependencies = (dependencyOverflow || !view.modelOnly && blockDependencyOverflow)
            && !dirty.isEmpty();
        if (retireUnknownDependencies) {
            dependencies.addAll(previous.regions.regions.keySet());
            dependencies.addAll(view.regions.regions.keySet());
        } else if (!view.modelOnly) {
            List<HologramRegionCache.Key> frontier = new ArrayList<>(dirty);
            for (int i = 0; i < frontier.size(); i++) {
                for (HologramRegionCache.Key reader : readers.getOrDefault(frontier.get(i), Collections.emptySet()))
                    if (dependencies.add(reader)) frontier.add(reader);
                if (!view.modelOnly) for (HologramRegionCache.Key reader : blockReaders
                    .getOrDefault(frontier.get(i), Collections.emptySet()))
                    if (dependencies.add(reader)) frontier.add(reader);
            }
        }
        Set<Integer> exactDirty = null;
        if (view.modelOnly) {
            exactDirty = new HashSet<>();
            for (int changed : view.changedIndices) exactDirty.add(changed);
            if (retireUnknownDependencies) {
                for (int slot = 0; slot < view.cells.size(); slot++) exactDirty.add(slot);
            } else {
                List<Integer> frontier = new ArrayList<>(exactDirty);
                Set<HologramRegionCache.Key> expandedCoarse = new HashSet<>();
                for (int at = 0; at < frontier.size(); at++) {
                    HologramState.Cell input = view.cells.get(frontier.get(at));
                    HologramRegionCache.Key inputRegion = HologramRegionCache.key(input);
                    if (coarseDependencyInputs.contains(inputRegion) && expandedCoarse.add(inputRegion)) {
                        for (HologramRegionCache.Key region : readers.getOrDefault(inputRegion, Collections.emptySet())) {
                            for (HologramState.Cell reader : view.regions.regions.getOrDefault(region, Collections.emptyList()))
                                if (exactDirty.add(reader.index)) frontier.add(reader.index);
                        }
                    }
                    for (long reader : exactReaders.getOrDefault(
                        new HologramRegionCache.Key(input.x, input.y, input.z), Collections.emptySet())) {
                        int slot = index(reader);
                        if (slot >= 0 && exactDirty.add(slot)) frontier.add(slot);
                    }
                }
            }
            // Group/list consumers still retire by region, but only regions of the exact reader closure.
            for (int slot : exactDirty) dependencies.add(HologramRegionCache.key(view.cells.get(slot)));
        }
        HologramRenderer.authorSnapshotChanged(previous, view, dependencies, exactDirty);
        if (retireUnknownDependencies) {
            // All old native readers were retired (or this document is inactive). Rebuild the bounded graph
            // from subsequent native draws; resetting before renderer retirement would allow stale meshes.
            readers.clear();
            exactReaders.clear();
            coarseDependencyInputs.clear();
            exactDependencyCount = 0;
            dependencyCount = 0;
            dependencyOverflow = false;
            blockReaders.clear();
            blockDependencyCount = 0;
            blockDependencyOverflow = false;
            budgetVisibilityChanged();
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
        budgetVisibilityChanged();
        queued.remove(id);
        budgetDeferred.remove(id);
        decodeDeferred.remove(id);
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
        decodeDeferred.clear();
        budgetPins.clear();
        pinnedResident = 0;
        readers.clear();
        exactReaders.clear();
        coarseDependencyInputs.clear();
        exactDependencyCount = 0;
        blockReaders.clear();
        slots.clear();
        ids.clear();
        resident = 0;
        HologramRenderer.releaseAuthor(this);
        view = null;
        document = metadataDocument = null;
    }

    private NBTTagCompound decode(String encoded, DecodeBudget budget) throws IOException {
        if (encoded == null || encoded.isEmpty()) return null;
        if (encoded.length() > ((HologramSnapshotCodec.BLUEPRINT_NBT_MAX_COMPRESSED + 2) / 3) * 4)
            throw new IOException("Native preview encoded budget");
        byte[] bytes = Base64.getDecoder()
            .decode(encoded);
        if (bytes.length > HologramSnapshotCodec.BLUEPRINT_NBT_MAX_COMPRESSED)
            throw new IOException("Native preview compressed budget");
        // The codec enforces expanded bytes, structural depth, and pre-allocation NBT accounting.
        nativeDecodePasses++;
        return HologramSnapshotCodec.decodeBounded(bytes, HologramSnapshotCodec.BLUEPRINT_NBT_MAX_EXPANDED,
            HologramSnapshotCodec.BLUEPRINT_NBT_MAX_TRACKED_BYTES, budget::admit);
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
