package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramBlueprintLimits;
import com.miaokatze.gtmb.hologram.HologramSnapshotRows;

/** Immutable client snapshot; all amounts and construction outcomes come from the server. */
final class HologramState {

    final HologramAuthorProjection authorProjection;
    final NBTTagCompound data;
    final String session;
    final List<Cell> cells;
    final List<Material> materials;
    final HologramRegionCache regions;
    final Object identity = new Object();
    final Object parentIdentity;
    final int[] changedIndices;
    final int parsedCells;
    private final boolean fullBaseline;
    List<Cell> changedPreviousCells = Collections.emptyList();
    boolean topologyChanged, modelOnly;
    final java.util.Map<String, List<Integer>> structureMembers;
    int minY = 0, maxY = 0;
    private int minWorldX, maxWorldX, minWorldY, maxWorldY, minWorldZ, maxWorldZ;

    HologramState(NBTTagCompound source) {
        authorProjection = null;
        fullBaseline = fullBaseline(source);
        parentIdentity = null;
        changedIndices = new int[0];
        data = metadata(source);
        session = data.getString("session");
        minWorldX = maxWorldX = data.getInteger("x");
        minWorldY = maxWorldY = data.getInteger("y");
        minWorldZ = maxWorldZ = data.getInteger("z");
        List<Cell> parsed = new ArrayList<>();
        NBTTagList list = source.getTagList("cells", 10);
        if (!HologramBlueprintLimits.accepts(list.tagCount()))
            throw new IllegalArgumentException("Blueprint cell count exceeds limit");
        parsedCells = list.tagCount();
        for (int i = 0; i < list.tagCount(); i++) {
            Cell cell = new Cell(i, list.getCompoundTagAt(i));
            parsed.add(cell);
            minWorldX = Math.min(minWorldX, cell.x);
            maxWorldX = Math.max(maxWorldX, cell.x);
            minWorldY = Math.min(minWorldY, cell.y);
            maxWorldY = Math.max(maxWorldY, cell.y);
            minWorldZ = Math.min(minWorldZ, cell.z);
            maxWorldZ = Math.max(maxWorldZ, cell.z);
            if (i == 0) {
                minY = cell.dy;
                maxY = cell.dy;
            }
            minY = Math.min(minY, cell.dy);
            maxY = Math.max(maxY, cell.dy);
        }
        cells = new CellPages(parsed);
        regions = new HologramRegionCache(cells);
        structureMembers = memberships(cells);
        materials = readMaterials(data);
    }

    private HologramState(Preparation ready) {
        authorProjection = null;
        fullBaseline = ready.fullBaseline;
        parentIdentity = null;
        changedIndices = new int[0];
        data = ready.data;
        session = data.getString("session");
        parsedCells = ready.cellPosition;
        cells = new CellPages(ready.pages.toArray(new Cell[ready.pages.size()][]), parsedCells);
        regions = ready.regions.finish();
        structureMembers = Collections.unmodifiableMap(ready.members);
        materials = Collections.unmodifiableList(ready.materials);
        minY = ready.minY;
        maxY = ready.maxY;
        minWorldX = ready.minWorldX;
        maxWorldX = ready.maxWorldX;
        minWorldY = ready.minWorldY;
        maxWorldY = ready.maxWorldY;
        minWorldZ = ready.minWorldZ;
        maxWorldZ = ready.maxWorldZ;
    }

    /** Direct document publication. No wire rows, sequence, ACK, transport codec or source reconstruction. */
    HologramState(HologramAuthorProjection owner, NBTTagCompound metadata, HologramState base, int size,
        java.util.Map<Integer, Cell> replacements) {
        authorProjection = owner;
        data = metadata;
        session = owner.documentId();
        fullBaseline = true;
        parentIdentity = base == null ? null : base.identity;
        parsedCells = replacements.size();
        changedIndices = new int[replacements.size()];
        int cursor = 0;
        List<Cell> before = new ArrayList<>();
        topologyChanged = base == null || size != base.cells.size();
        if (base != null) for (int i = size; i < base.cells.size(); i++) before.add(base.cells.get(i));
        for (java.util.Map.Entry<Integer, Cell> entry : replacements.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= size) throw new IllegalArgumentException("Document render slot");
            Cell next = entry.getValue();
            if (base != null && index < base.cells.size()) {
                Cell old = base.cells.get(index);
                before.add(old);
                topologyChanged |= old.x != next.x || old.y != next.y || old.z != next.z;
            }
            changedIndices[cursor++] = index;
        }
        cells = base != null && size == base.cells.size() && replacements.isEmpty() ? base.cells
            : new DocumentCells(base == null ? null : (DocumentCells) base.cells, size, replacements);
        changedPreviousCells = Collections.unmodifiableList(before);
        regions = base != null && replacements.isEmpty() && before.isEmpty() ? base.regions
            : HologramRegionCache.document(base == null ? null : base.regions, cells, changedIndices, before);
        structureMembers = documentMemberships(base, cells, changedIndices, before);
        materials = Collections.emptyList();
        int[] bounds = regions.bounds();
        minY = minWorldY = cells.isEmpty() ? 0 : bounds[1];
        maxY = maxWorldY = cells.isEmpty() ? 0 : bounds[4];
        minWorldX = cells.isEmpty() ? 0 : bounds[0];
        minWorldZ = cells.isEmpty() ? 0 : bounds[2];
        maxWorldX = cells.isEmpty() ? 0 : bounds[3];
        maxWorldZ = cells.isEmpty() ? 0 : bounds[5];
    }

    /** Metadata decorators share geometry; preserve the incoming publication's renderer and selection obligations. */
    private HologramState(HologramState decorated, HologramState publication) {
        authorProjection = decorated.authorProjection;
        data = decorated.data;
        session = decorated.session;
        cells = decorated.cells;
        materials = decorated.materials;
        regions = decorated.regions;
        structureMembers = decorated.structureMembers;
        parentIdentity = publication.parentIdentity;
        changedIndices = publication.changedIndices;
        changedPreviousCells = publication.changedPreviousCells;
        topologyChanged = publication.topologyChanged;
        parsedCells = publication.parsedCells;
        fullBaseline = publication.fullBaseline;
        minY = decorated.minY;
        maxY = decorated.maxY;
        minWorldX = decorated.minWorldX;
        maxWorldX = decorated.maxWorldX;
        minWorldY = decorated.minWorldY;
        maxWorldY = decorated.maxWorldY;
        minWorldZ = decorated.minWorldZ;
        maxWorldZ = decorated.maxWorldZ;
    }

    /** One bounded early view shares completed cells and owns its small spatial index. */
    private HologramState(Preparation ready, int count) {
        authorProjection = null;
        fullBaseline = false;
        parentIdentity = null;
        changedIndices = new int[0];
        data = metadata(ready.data, false);
        data.setBoolean("displayPartial", true);
        session = data.getString("session");
        parsedCells = count;
        List<Cell> prefix = new ArrayList<>(count);
        for (int i = 0; i < count; i++) prefix.add(ready.pages.get(i >> 8)[i & 255]);
        cells = new CellPages(prefix);
        regions = new HologramRegionCache(cells);
        structureMembers = memberships(cells);
        materials = Collections.emptyList();
        Cell first = cells.get(0);
        minY = maxY = first.dy;
        minWorldX = maxWorldX = first.x;
        minWorldY = maxWorldY = first.y;
        minWorldZ = maxWorldZ = first.z;
        for (Cell cell : cells) {
            minY = Math.min(minY, cell.dy);
            maxY = Math.max(maxY, cell.dy);
            minWorldX = Math.min(minWorldX, cell.x);
            maxWorldX = Math.max(maxWorldX, cell.x);
            minWorldY = Math.min(minWorldY, cell.y);
            maxWorldY = Math.max(maxWorldY, cell.y);
            minWorldZ = Math.min(minWorldZ, cell.z);
            maxWorldZ = Math.max(maxWorldZ, cell.z);
        }
    }

    /** Registry/item resolution and spatial indexing share a cooperative client-thread budget. */
    static final class Preparation {

        private final Thread owner = Thread.currentThread();
        private final NBTTagCompound data;
        private final NBTTagList rows, materialRows;
        private final List<Cell[]> pages = new ArrayList<>();
        private final HologramRegionCache.Builder regions = new HologramRegionCache.Builder();
        private final java.util.Map<String, List<Integer>> members = new java.util.LinkedHashMap<>();
        private final List<Material> materials = new ArrayList<>();
        private final java.util.Map<NBTTagCompound, Cell> descriptors = new java.util.IdentityHashMap<>();
        private java.util.Iterator<java.util.Map.Entry<String, List<Integer>>> memberIterator;
        private int stage, cellPosition, materialPosition, memberPosition;
        private int minY, maxY, minWorldX, maxWorldX, minWorldY, maxWorldY, minWorldZ, maxWorldZ;
        private HologramState result;
        private final boolean displayRows;
        private final boolean fullBaseline;
        int lastStepWork;

        /** Ownership of the decoded tree transfers to this preparation; no published state mutates it. */
        Preparation(NBTTagCompound source) {
            this(source, false);
        }

        Preparation(NBTTagCompound source, boolean displayRows) {
            this.displayRows = displayRows;
            fullBaseline = fullBaseline(source);
            data = metadata(source, false);
            rows = source.getTagList("cells", 10);
            if (!HologramBlueprintLimits.accepts(rows.tagCount()))
                throw new IllegalArgumentException("Blueprint cell count exceeds limit");
            materialRows = source.getTagList("materials", 10);
            minWorldX = maxWorldX = data.getInteger("x");
            minWorldY = maxWorldY = data.getInteger("y");
            minWorldZ = maxWorldZ = data.getInteger("z");
        }

        boolean step(int maxWork, long maxNanos) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Client preparation thread changed");
            if (maxWork < 1 || maxNanos < 1) throw new IllegalArgumentException("Client preparation budget");
            long started = System.nanoTime();
            lastStepWork = 0;
            while (stage < 4 && lastStepWork < maxWork
                && (lastStepWork == 0 || System.nanoTime() - started < maxNanos)) {
                if (stage == 0) {
                    if (cellPosition == rows.tagCount()) {
                        stage++;
                        continue;
                    }
                    NBTTagCompound row = rows.getCompoundTagAt(cellPosition);
                    if (displayRows) try {
                        HologramBlueprintPreview.completeCell(row);
                    } catch (java.io.IOException invalid) {
                        throw new IllegalArgumentException(invalid.getMessage(), invalid);
                    }
                    NBTTagCompound descriptor = HologramSnapshotRows.descriptor(row);
                    Cell cell = descriptor == null ? new Cell(cellPosition, row, true)
                        : new Cell(
                            cellPosition,
                            row,
                            descriptors.computeIfAbsent(descriptor, key -> new Cell(0, key, true)));
                    if ((cellPosition & 255) == 0) pages.add(new Cell[Math.min(256, rows.tagCount() - cellPosition)]);
                    pages.get(cellPosition >> 8)[cellPosition & 255] = cell;
                    regions.add(cell);
                    for (String id : membershipIds(cell)) members.computeIfAbsent(id, unused -> new ArrayList<>())
                        .add(cell.index);
                    minWorldX = Math.min(minWorldX, cell.x);
                    maxWorldX = Math.max(maxWorldX, cell.x);
                    minWorldY = Math.min(minWorldY, cell.y);
                    maxWorldY = Math.max(maxWorldY, cell.y);
                    minWorldZ = Math.min(minWorldZ, cell.z);
                    maxWorldZ = Math.max(maxWorldZ, cell.z);
                    if (cellPosition == 0) minY = maxY = cell.dy;
                    minY = Math.min(minY, cell.dy);
                    maxY = Math.max(maxY, cell.dy);
                    cellPosition++;
                } else if (stage == 1) {
                    if (!regions.sealNext()) {
                        stage++;
                        continue;
                    }
                } else if (stage == 2) {
                    if (materialPosition == materialRows.tagCount()) {
                        stage++;
                        continue;
                    }
                    materials.add(new Material(materialRows.getCompoundTagAt(materialPosition++)));
                } else {
                    if (memberIterator == null) memberIterator = members.entrySet()
                        .iterator();
                    if (!memberIterator.hasNext()) {
                        stage++;
                        continue;
                    }
                    java.util.Map.Entry<String, List<Integer>> entry = memberIterator.next();
                    entry.setValue(Collections.unmodifiableList(entry.getValue()));
                    memberPosition++;
                }
                lastStepWork++;
            }
            return stage == 4;
        }

        String phase() {
            return stage == 0 ? "snapshot.cells"
                : stage == 1 ? "snapshot.regions" : stage == 2 ? "snapshot.materials" : "snapshot.memberships";
        }

        int completed() {
            return stage == 0 ? cellPosition
                : stage == 1 ? regions.result.size() : stage == 2 ? materialPosition : memberPosition;
        }

        int total() {
            return stage == 0 ? rows.tagCount()
                : stage == 1 ? regions.pending.size() : stage == 2 ? materialRows.tagCount() : members.size();
        }

        String unit() {
            return stage == 0 ? "cells" : stage == 1 ? "regions" : stage == 2 ? "materials" : "structures";
        }

        HologramState result() {
            if (stage != 4) throw new IllegalStateException("Client snapshot is incomplete");
            if (result == null) result = new HologramState(this);
            return result;
        }

        HologramState firstDisplay() {
            return cellPosition == 0 ? null : new HologramState(this, Math.min(256, cellPosition));
        }
    }

    private HologramState(HologramState base, NBTTagCompound patch) {
        this(base, patch, false);
    }

    private HologramState(HologramState base, NBTTagCompound patch, boolean decoration) {
        authorProjection = base.authorProjection;
        fullBaseline = decoration && base.fullBaseline;
        parentIdentity = base.identity;
        data = new NBTTagCompound();
        // Published metadata values are read-only. Share unchanged values; copy only incoming replacements.
        for (Object name : base.data.func_150296_c()) {
            String key = (String) name;
            data.setTag(key, base.data.getTag(key));
        }
        NBTTagCompound updates = metadata(patch);
        for (Object name : updates.func_150296_c()) {
            String key = (String) name;
            data.setTag(key, updates.getTag(key));
        }
        if (patch.hasKey("processingPatch", 10) || patch.hasKey("processingConfirmedStages", 9)) {
            NBTTagCompound processing = shallow(data.getCompoundTag("processing"));
            NBTTagCompound replacements = patch.getCompoundTag("processingPatch");
            for (Object name : replacements.func_150296_c()) {
                String key = (String) name;
                processing.setTag(
                    key,
                    replacements.getTag(key)
                        .copy());
            }
            if (patch.hasKey("processingConfirmedStages", 9)) {
                NBTTagList stages = patch.getTagList("processingConfirmedStages", 8);
                if (stages.tagCount() > 6)
                    throw new IllegalArgumentException("Processing confirmation patch exceeds budget");
                NBTTagCompound policy = shallow(processing.getCompoundTag("policy"));
                policy.setTag("confirmedStages", stages.copy());
                processing.setTag("policy", policy);
                if (data.hasKey("processingPolicy", 10)) {
                    NBTTagCompound topPolicy = shallow(data.getCompoundTag("processingPolicy"));
                    topPolicy.setTag("confirmedStages", stages.copy());
                    data.setTag("processingPolicy", topPolicy);
                }
            }
            data.setTag("processing", processing);
        }
        session = base.session;
        NBTTagList rows = patch.getTagList("cells", 10);
        parsedCells = rows.tagCount();
        changedIndices = new int[parsedCells];
        topologyChanged = patch.getBoolean("topology");
        int nextSize = topologyChanged ? patch.getInteger("cellCount") : base.cells.size();
        CellPages pages = parsedCells == 0 && nextSize == base.cells.size() ? null
            : new CellPages((CellPages) base.cells, nextSize);
        List<Cell> before = new ArrayList<>();
        for (int index = nextSize; index < base.cells.size(); index++) before.add(base.cells.get(index));
        java.util.Set<Integer> indices = new java.util.HashSet<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            int index = row.getInteger("index");
            if (!row.hasKey("index", 3) || index < 0 || index >= nextSize || !indices.add(index))
                throw new IllegalArgumentException("Invalid partial cell index");
            Cell old = index < base.cells.size() ? base.cells.get(index) : null;
            if (old != null) before.add(old);
            if (row.getBoolean("annotationOnly")) {
                if (old == null) throw new IllegalArgumentException("Appended annotation has no baseline cell");
                NBTTagCompound annotations = row;
                row = old.modelSource == null ? old.tag() : old.modelSource.tag();
                row.setString("prompt", annotations.getString("prompt"));
                row.setBoolean("excluded", annotations.getBoolean("excluded"));
            }
            Cell cell = new Cell(index, row);
            // Topology-changing operations require a full snapshot with a new spatial index.
            if (!topologyChanged && (cell.x != old.x || cell.y != old.y
                || cell.z != old.z
                || cell.dx != old.dx
                || cell.dy != old.dy
                || cell.dz != old.dz)) throw new IllegalArgumentException("Partial snapshot changed cell coordinates");
            pages.replace(index, cell);
            changedIndices[i] = index;
        }
        for (int index = base.cells.size(); index < nextSize; index++)
            if (!indices.contains(index)) throw new IllegalArgumentException("Missing appended topology cell");
        changedPreviousCells = Collections.unmodifiableList(before);
        cells = pages == null ? base.cells : pages;
        regions = before.isEmpty() && changedIndices.length == 0 ? base.regions
            : new HologramRegionCache(base.regions, cells, changedIndices, before, topologyChanged);
        if (topologyChanged) for (int index : changedIndices) {
            Cell cell = cells.get(index);
            if (regions.at(cell.x, cell.y, cell.z) != cell)
                throw new IllegalArgumentException("Duplicate topology coordinates");
        }
        structureMembers = memberships(base, cells, changedIndices, before);
        minY = base.minY;
        maxY = base.maxY;
        minWorldX = base.minWorldX;
        maxWorldX = base.maxWorldX;
        minWorldY = base.minWorldY;
        maxWorldY = base.maxWorldY;
        minWorldZ = base.minWorldZ;
        maxWorldZ = base.maxWorldZ;
        if (topologyChanged) {
            int[] bounds = regions.bounds();
            minWorldX = Math.min(data.getInteger("x"), bounds[0]);
            minWorldY = Math.min(data.getInteger("y"), bounds[1]);
            minWorldZ = Math.min(data.getInteger("z"), bounds[2]);
            maxWorldX = Math.max(data.getInteger("x"), bounds[3]);
            maxWorldY = Math.max(data.getInteger("y"), bounds[4]);
            maxWorldZ = Math.max(data.getInteger("z"), bounds[5]);
            minY = cells.isEmpty() ? 0 : bounds[1] - data.getInteger("y");
            maxY = cells.isEmpty() ? 0 : bounds[4] - data.getInteger("y");
        }
        materials = patch.hasKey("materials") ? readMaterials(data) : base.materials;
    }

    /** Appearance publications share the exact authority metadata object, selection and material receipts. */
    private HologramState(HologramState latest, ModelPreparation models) {
        authorProjection = latest.authorProjection;
        fullBaseline = latest.fullBaseline;
        parentIdentity = latest.identity;
        data = latest.data;
        session = latest.session;
        modelOnly = true;
        parsedCells = models.changed.size();
        changedIndices = new int[parsedCells];
        for (int i = 0; i < parsedCells; i++) changedIndices[i] = models.changed.get(i);
        changedPreviousCells = Collections.unmodifiableList(models.before);
        cells = models.pages;
        regions = HologramRegionCache
            .models(latest.regions, cells, changedIndices, changedPreviousCells, models.leases);
        structureMembers = latest.structureMembers;
        materials = latest.materials;
        minY = latest.minY;
        maxY = latest.maxY;
        minWorldX = latest.minWorldX;
        maxWorldX = latest.maxWorldX;
        minWorldY = latest.minWorldY;
        maxWorldY = latest.maxWorldY;
        minWorldZ = latest.minWorldZ;
        maxWorldZ = latest.maxWorldZ;
    }

    static final class ModelRow {

        final int index;
        final long id;
        final NBTTagCompound actual, target;
        final boolean evict;

        ModelRow(int index, long id, NBTTagCompound actual, NBTTagCompound target, boolean evict) {
            this.index = index;
            this.id = id;
            this.actual = actual;
            this.target = target;
            this.evict = evict;
        }
    }

    /** Client-owner cursor. No native model or tile factory runs while these immutable rows are attached. */
    static final class ModelPreparation implements AutoCloseable {

        private final HologramState base;
        private final List<ModelRow> rows;
        private final CellPages pages;
        private final List<Integer> changed = new ArrayList<>();
        private final List<Cell> before = new ArrayList<>();
        private final List<com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease> leases = new ArrayList<>();
        private int position;
        private boolean published;

        ModelPreparation(HologramState base, List<ModelRow> rows) {
            if (rows.size() > 32) throw new IllegalArgumentException("Model page exceeds row budget");
            this.base = base;
            this.rows = rows;
            pages = new CellPages((CellPages) base.cells, base.cells.size());
        }

        boolean step(int limit, long nanos) {
            long deadline = System.nanoTime() + nanos;
            int work = 0;
            while (position < rows.size() && work < limit && (work == 0 || System.nanoTime() < deadline)) {
                ModelRow row = rows.get(position++);
                work++;
                if (row.index < 0 || row.index >= base.cells.size()) continue;
                Cell old = base.cells.get(row.index);
                if (old.nativeModelId != row.id || row.id <= 0) continue;
                int mask = (row.actual == null ? 0 : 1) | (row.target == null ? 0 : 2);
                if (row.evict ? old.modelSource == null : (old.nativeModelPending & mask) == 0) continue;
                com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease cellLease = null;
                if (!row.evict) {
                    NBTTagCompound actual = row.actual == null ? old.actualDisplay.getCompoundTag("nativeTile")
                        : row.actual;
                    NBTTagCompound target = row.target == null ? old.targetDisplay.getCompoundTag("nativeTile")
                        : row.target;
                    long bytes = 4096L + com.miaokatze.gtmb.hologram.HologramSnapshotCodec.retainedEstimate(actual)
                        + com.miaokatze.gtmb.hologram.HologramSnapshotCodec.retainedEstimate(target);
                    cellLease = reserveModel("snapshot.modelCell", bytes);
                }
                try {
                    int page = row.index >> 8;
                    if (!pages.copied.contains(page)) {
                        com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease pageLease = reserveModel(
                            "snapshot.modelCellPage",
                            512L + pages.pages[page].length * 8L);
                        leases.add(pageLease);
                        // replace() clones only this one logical page, after its reservation.
                        pages.replace(row.index, old);
                    }
                    Cell next = row.evict ? old.modelSource : new Cell(old, row.actual, row.target, mask);
                    if (cellLease != null) {
                        leases.add(cellLease);
                    }
                    pages.replace(row.index, next);
                    changed.add(row.index);
                    before.add(old);
                } catch (RuntimeException | Error failure) {
                    if (cellLease != null) cellLease.close();
                    throw failure;
                }
            }
            return position == rows.size();
        }

        HologramState result(HologramState latest) {
            if (position != rows.size()) throw new IllegalStateException("Model page incomplete");
            if (!base.session.equals(latest.session) || base.cells != latest.cells) return null;
            HologramState result = changed.isEmpty() ? latest : new HologramState(latest, this);
            return result;
        }

        void published() {
            published = true;
            for (com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease lease : leases) lease.close();
            leases.clear();
        }

        private static com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease reserveModel(String name, long bytes) {
            com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease lease = com.miaokatze.gtmb.hologram.HologramProcessGovernor
                .tryRetain(name, bytes);
            if (lease == null) throw new IllegalStateException("Model memory quota busy");
            return lease;
        }

        @Override
        public void close() {
            if (!published)
                for (com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease lease : leases) lease.close();
            leases.clear();
        }
    }

    /** A delta never copies or reparses the complete cells NBT tree. */
    static HologramState apply(HologramState base, NBTTagCompound source) {
        if (source.getBoolean("areaSelectionOnly")) return applyAreaSelection(base, source);
        if (!source.getBoolean("partial")) return new HologramState(source).retainAreaSelection(base);
        if (!source.hasKey("baseRevision", 4) || !source.hasKey("planRevision", 4)
            || !source.hasKey("cellCount", 3)
            || base == null
            || base.data.getBoolean("areaSelectionShell")
            || !base.session.equals(source.getString("session"))
            || !base.data.getString("generation")
                .equals(source.getString("generation"))
            || source.getLong("baseRevision") != base.data.getLong("planRevision")
            || source.getLong("planRevision") < source.getLong("baseRevision")
            || source.getBoolean("topology")
                && (!base.data.getBoolean("virtual") || base.data.getBoolean("virtualPlacement"))
            || source.getBoolean("topology") && source.getLong("planRevision") == source.getLong("baseRevision")
            || (source.getBoolean("topology")
                ? !source.hasKey("baseCellCount", 3) || source.getInteger("baseCellCount") != base.cells.size()
                    || !HologramBlueprintLimits.accepts(source.getInteger("cellCount"))
                    || (long) source.getInteger("cellCount") > (long) base.cells.size() + source.getTagList("cells", 10)
                        .tagCount()
                : source.getInteger("cellCount") != base.cells.size()))
            throw new IllegalArgumentException("Partial snapshot base mismatch");
        return new HologramState(base, source);
    }

    /** A selection receipt carries no geometry baseline, material plan, or construction acknowledgement. */
    private static HologramState applyAreaSelection(HologramState base, NBTTagCompound receipt) {
        boolean same = base != null && base.session.equals(receipt.getString("session"));
        if (same && receipt.getLong("areaSelectionSequence") <= base.data.getLong("areaSelectionSequence")) return base;
        NBTTagCompound changes = metadata(receipt);
        changes.setTag("areaSelectionReceipt", metadata(receipt));
        boolean shell = !same || base.data.getBoolean("areaSelectionShell")
            || base.data.getLong("planRevision") != receipt.getLong("planRevision");
        if (receipt.getBoolean("workspaceUiOnly")) {
            changes.setBoolean("capturePending", receipt.getBoolean("workspaceCapturePending"));
            if (receipt.getBoolean("workspaceCapturePending")) shell = true;
            else if (!receipt.getBoolean("workspaceHasCapture")) shell = same && !base.cells.isEmpty();
        }
        changes.setBoolean("areaSelectionShell", shell);
        changes.setBoolean("snapshotPending", receipt.getBoolean("workspaceUiOnly") && shell);
        return same ? new HologramState(base, changes) : new HologramState(changes);
    }

    /** A delayed full baseline can install its geometry without rolling back a newer point receipt. */
    HologramState retainAreaSelection(HologramState previous) {
        if (previous == null || !session.equals(previous.session)
            || data.getLong("planRevision") != previous.data.getLong("planRevision")
            || data.getLong("areaSelectionSequence") >= previous.data.getLong("areaSelectionSequence")
            || !previous.data.hasKey("areaSelectionReceipt", 10)) return this;
        NBTTagCompound latest = metadata(previous.data.getCompoundTag("areaSelectionReceipt"));
        // A receipt clears replayed hints on its own view; the eventual full still owns its undelivered one-shots.
        latest.removeTag("hints");
        latest.removeTag("libraryInitialSnapshot");
        if (latest.getBoolean("workspaceUiOnly"))
            latest.setBoolean("capturePending", latest.getBoolean("workspaceCapturePending"));
        latest.setTag("areaSelectionReceipt", previous.data.getCompoundTag("areaSelectionReceipt"));
        boolean shell = latest.getBoolean("workspaceUiOnly") && (latest.getBoolean("workspaceCapturePending")
            || !latest.getBoolean("workspaceHasCapture") && !cells.isEmpty());
        latest.setBoolean("areaSelectionShell", shell);
        latest.setBoolean("snapshotPending", shell);
        return withMetadata(latest);
    }

    boolean follows(HologramState base) {
        return base != null && parentIdentity == base.identity;
    }

    /** Provenance of this publication, including an explicitly transmitted empty full baseline. */
    boolean completeBaseline() {
        return fullBaseline;
    }

    private static boolean fullBaseline(NBTTagCompound source) {
        return source.hasKey("cells", 9) && !source.getBoolean("partial")
            && !source.getBoolean("areaSelectionOnly")
            && !source.getBoolean("displayPartial")
            && !source.getBoolean("areaSelectionShell");
    }

    /** Explicit export for diagnostics/fixtures. Interactive updates never materialize the whole cells tree. */
    NBTTagCompound fullData() {
        NBTTagCompound result = (NBTTagCompound) data.copy();
        NBTTagList rows = new NBTTagList();
        for (Cell cell : cells) rows.appendTag(cell.tag());
        result.setTag("cells", rows);
        return result;
    }

    HologramState withMetadata(NBTTagCompound changes) {
        return new HologramState(this, changes, true);
    }

    /** Called once at installation, after decorating this incoming wire snapshot and before rendering it. */
    HologramState withPublication(HologramState publication) {
        if (!session.equals(publication.session) || cells != publication.cells
            || regions != publication.regions
            || !data.getString("generation")
                .equals(publication.data.getString("generation")))
            throw new IllegalArgumentException("Metadata decorator changed publication geometry");
        return this == publication ? this : new HologramState(this, publication);
    }

    private static NBTTagCompound metadata(NBTTagCompound source) {
        return metadata(source, true);
    }

    private static NBTTagCompound shallow(NBTTagCompound source) {
        NBTTagCompound result = new NBTTagCompound();
        for (Object name : source.func_150296_c()) {
            String key = (String) name;
            result.setTag(key, source.getTag(key));
        }
        return result;
    }

    private static NBTTagCompound metadata(NBTTagCompound source, boolean copy) {
        NBTTagCompound result = new NBTTagCompound();
        for (Object name : source.func_150296_c()) {
            String key = (String) name;
            if (!key.equals("cells") && !key.equals("partial")
                && !key.equals("areaSelectionOnly")
                && !key.equals("baseRevision")
                && !key.equals("processingPatch")
                && !key.equals("processingConfirmedStages"))
                result.setTag(
                    key,
                    copy ? source.getTag(key)
                        .copy() : source.getTag(key));
        }
        return result;
    }

    private static List<Material> readMaterials(NBTTagCompound source) {
        List<Material> result = new ArrayList<>();
        NBTTagList list = source.getTagList("materials", 10);
        for (int i = 0; i < list.tagCount(); i++) result.add(new Material(list.getCompoundTagAt(i)));
        return Collections.unmodifiableList(result);
    }

    private static java.util.Map<String, List<Integer>> memberships(List<Cell> cells) {
        java.util.Map<String, List<Integer>> result = new java.util.LinkedHashMap<>();
        for (Cell cell : cells)
            for (String id : membershipIds(cell)) result.computeIfAbsent(id, unused -> new ArrayList<>())
                .add(cell.index);
        result.replaceAll((id, members) -> Collections.unmodifiableList(members));
        return Collections.unmodifiableMap(result);
    }

    private static List<String> membershipIds(Cell cell) {
        return cell.structureIds.isEmpty() ? Collections.singletonList("") : cell.structureIds;
    }

    private static java.util.Map<String, List<Integer>> memberships(HologramState base, List<Cell> cells, int[] changed,
        List<Cell> before) {
        java.util.Map<String, List<Integer>> replacements = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, Cell> oldCells = new java.util.HashMap<>();
        for (Cell old : before) oldCells.put(old.index, old);
        for (Cell old : before) {
            if (old.index < cells.size() && membershipIds(old).equals(membershipIds(cells.get(old.index)))) continue;
            for (String id : membershipIds(old)) replacements
                .computeIfAbsent(
                    id,
                    unused -> new ArrayList<>(base.structureMembers.getOrDefault(id, Collections.emptyList())))
                .remove(Integer.valueOf(old.index));
        }
        for (int index : changed) {
            Cell old = oldCells.get(index);
            if (old != null && membershipIds(old).equals(membershipIds(cells.get(index)))) continue;
            for (String id : membershipIds(cells.get(index))) replacements
                .computeIfAbsent(
                    id,
                    unused -> new ArrayList<>(base.structureMembers.getOrDefault(id, Collections.emptyList())))
                .add(index);
        }
        if (replacements.isEmpty()) return base.structureMembers;
        java.util.Map<String, List<Integer>> result = new java.util.LinkedHashMap<>(base.structureMembers);
        replacements.forEach((id, members) -> {
            Collections.sort(members);
            if (members.isEmpty()) result.remove(id);
            else result.put(id, Collections.unmodifiableList(members));
        });
        return Collections.unmodifiableMap(result);
    }

    /** Local page directories are persistent too: no N/256 directory copy on a single-cell edit. */
    private static final class DocumentCells extends java.util.AbstractList<Cell> implements java.util.RandomAccess {

        final HologramDocumentIndex<Integer, Cell[]> pages;
        final int size;

        DocumentCells(DocumentCells base, int size, java.util.Map<Integer, Cell> replacements) {
            this.size = size;
            HologramDocumentIndex<Integer, Cell[]> next = base == null ? new HologramDocumentIndex<>(Integer::compare)
                : base.pages;
            if (base != null)
                for (int page = (size + 255) >> 8; page < (base.size + 255) >> 8; page++) next = next.with(page, null);
            java.util.Map<Integer, Cell[]> dirty = new java.util.LinkedHashMap<>();
            if (base != null && size < base.size && (size & 255) != 0) {
                int page = size >> 8;
                Cell[] trimmed = base.pages.get(page)
                    .clone();
                java.util.Arrays.fill(trimmed, size & 255, 256, null);
                dirty.put(page, trimmed);
            }
            for (java.util.Map.Entry<Integer, Cell> entry : replacements.entrySet()) {
                int page = entry.getKey() >> 8;
                Cell[] values = dirty.get(page);
                if (values == null) {
                    Cell[] old = base == null ? null : base.pages.get(page);
                    values = old == null ? new Cell[256] : old.clone();
                    dirty.put(page, values);
                }
                values[entry.getKey() & 255] = entry.getValue();
            }
            for (java.util.Map.Entry<Integer, Cell[]> page : dirty.entrySet())
                next = next.with(page.getKey(), page.getValue());
            pages = next;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Cell get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            return pages.get(index >> 8)[index & 255];
        }
    }

    private static final class DocumentMembers extends java.util.AbstractList<Integer> {

        final HologramDocumentIndex<Integer, Integer> indices;

        DocumentMembers() {
            indices = new HologramDocumentIndex<>(Integer::compare);
        }

        DocumentMembers(HologramDocumentIndex<Integer, Integer> indices) {
            this.indices = indices;
        }

        DocumentMembers with(int index, boolean present) {
            return new DocumentMembers(indices.with(index, present ? index : null));
        }

        @Override
        public int size() {
            return indices.size();
        }

        @Override
        public Integer get(int index) {
            return indices.at(index);
        }
    }

    private static java.util.Map<String, List<Integer>> documentMemberships(HologramState base, List<Cell> cells,
        int[] changed, List<Cell> before) {
        @SuppressWarnings("unchecked")
        HologramDocumentIndex<String, List<Integer>> result = base == null
            ? new HologramDocumentIndex<>(String::compareTo)
            : (HologramDocumentIndex<String, List<Integer>>) base.structureMembers;
        java.util.Map<Integer, Cell> oldCells = new java.util.HashMap<>();
        for (Cell old : before) {
            oldCells.put(old.index, old);
            if (old.index < cells.size() && membershipIds(old).equals(membershipIds(cells.get(old.index)))) continue;
            for (String id : membershipIds(old)) {
                DocumentMembers members = ((DocumentMembers) result.get(id)).with(old.index, false);
                result = result.with(id, members.isEmpty() ? null : members);
            }
        }
        for (int index : changed) {
            Cell old = oldCells.get(index);
            if (old != null && membershipIds(old).equals(membershipIds(cells.get(index)))) continue;
            for (String id : membershipIds(cells.get(index))) {
                DocumentMembers members = (DocumentMembers) result.get(id);
                if (members == null) members = new DocumentMembers();
                result = result.with(id, members.with(index, true));
            }
        }
        return result;
    }

    /** Copy on write pages keep stable server indices without copying N cell references for a one-cell edit. */
    private static final class CellPages extends java.util.AbstractList<Cell> implements java.util.RandomAccess {

        final Cell[][] pages;
        final int size;
        final java.util.Set<Integer> copied = new java.util.HashSet<>();

        CellPages(List<Cell> source) {
            size = source.size();
            pages = new Cell[(size + 255) >> 8][];
            for (int page = 0; page < pages.length; page++) {
                pages[page] = new Cell[Math.min(256, size - (page << 8))];
                for (int i = 0; i < pages[page].length; i++) pages[page][i] = source.get((page << 8) + i);
            }
        }

        CellPages(CellPages base, int nextSize) {
            size = nextSize;
            pages = java.util.Arrays.copyOf(base.pages, (nextSize + 255) >> 8);
            for (int page = 0; page < pages.length; page++) {
                int length = Math.min(256, size - (page << 8));
                if (pages[page] == null) {
                    pages[page] = new Cell[length];
                    copied.add(page);
                } else if (pages[page].length != length) {
                    pages[page] = java.util.Arrays.copyOf(pages[page], length);
                    copied.add(page);
                }
            }
        }

        CellPages(Cell[][] pages, int size) {
            this.pages = pages;
            this.size = size;
        }

        void replace(int index, Cell cell) {
            int page = index >> 8;
            if (copied.add(page)) pages[page] = pages[page].clone();
            pages[page][index & 255] = cell;
        }

        @Override
        public Cell get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            return pages[index >> 8][index & 255];
        }

        @Override
        public int size() {
            return size;
        }
    }

    /** Visibility follows the full structure bounds, allowing viewers near remote ends of megastructures. */
    double distanceSquared(double x, double y, double z) {
        double dx = Math.max(Math.max(minWorldX - x, x - (maxWorldX + 1.0)), 0);
        double dy = Math.max(Math.max(minWorldY - y, y - (maxWorldY + 1.0)), 0);
        double dz = Math.max(Math.max(minWorldZ - z, z - (maxWorldZ + 1.0)), 0);
        return dx * dx + dy * dy + dz * dz;
    }

    static final class Cell {

        private final NBTTagCompound raw;
        private final ItemStack localStack;

        final int index, x, y, z, dx, dy, dz, meta, wantMeta, chosenChoice, hatchFacing, actualFacing, targetFacing;
        final String id, wantId, status, pinScope, role, family, renderKind, operation, exactIdentity;
        final String prompt, structureId, sourceMod, sourceVersion, sourceClass, sourceWarning;
        final String networkGroup;
        final NBTTagCompound specialHint;
        final List<String> structureIds;
        final boolean anchor, iconOnly, excluded, missingBlock, sourceMissing, inScope;
        final boolean candidateComplete;
        final String candidateBoundary;
        final ItemStack actualStack, targetStack;
        final NBTTagCompound actualDisplay, targetDisplay;
        final int nativeModelPending;
        final long nativeModelId;
        final Cell modelSource;
        final String[] hintIcons;
        final int hintIndex;
        final int[] hintTint;
        final List<ItemStack> candidates;

        /** Cold local document geometry; native payloads stay opaque until a visible consumer requests them. */
        Cell(int index, com.miaokatze.gtmb.blueprint.editor.AuthorDocument.Cell source) {
            raw = null;
            this.index = index;
            x = dx = source.x();
            y = dy = source.y();
            z = dz = source.z();
            meta = wantMeta = source.metadata();
            id = wantId = source.block() == null ? "" : source.block();
            chosenChoice = hatchFacing = -1;
            actualFacing = targetFacing = source.facing();
            status = "planned";
            pinScope = family = renderKind = operation = exactIdentity = "";
            role = source.controller() ? "templateController" : "";
            prompt = source.prompt() == null ? "" : source.prompt();
            sourceMod = sourceVersion = sourceClass = sourceWarning = networkGroup = "";
            structureIds = Collections.unmodifiableList(new ArrayList<>(source.memberships()));
            structureId = structureIds.isEmpty() ? "" : structureIds.get(0);
            specialHint = new NBTTagCompound();
            anchor = false; // Saved template controllers are not protected physical-world anchors.
            iconOnly = sourceMissing = inScope = candidateComplete = false;
            excluded = source.excluded();
            Block block = Block.getBlockFromName(wantId);
            missingBlock = !wantId.isEmpty() && !"minecraft:air".equals(wantId) && block == null;
            candidateBoundary = "Local source candidates have not been checked against native declarations";
            localStack = simpleLocalStack(source, block);
            actualStack = localStack == null ? null : localStack.copy();
            targetStack = localStack == null ? null : localStack.copy();
            actualDisplay = targetDisplay = new NBTTagCompound();
            if (localStack != null) {
                actualDisplay.setInteger("color", -1);
                if (targetFacing >= 0) actualDisplay.setInteger("facing", targetFacing);
            }
            // Local identities never leave the owner. Both views use the same source payload.
            nativeModelId = source.id();
            nativeModelPending = localStack == null && (source.tileNbt() != null && !source.tileNbt()
                .isEmpty() || source.itemNbt() != null
                    && !source.itemNbt()
                        .isEmpty()
                || source.item() != null && !source.item()
                    .isEmpty()) ? 3 : 0;
            modelSource = null;
            hintIcons = new String[] { "", "", "", "", "", "" };
            hintIndex = -1;
            hintTint = new int[0];
            candidates = Collections.emptyList();
        }

        /** Plain block/item facts need no opaque NBT decode or per-cell native resident admission. */
        private static ItemStack simpleLocalStack(com.miaokatze.gtmb.blueprint.editor.AuthorDocument.Cell source,
            Block block) {
            boolean chisel = HologramChiselNativeRender.selected(block);
            if (block == null || block == net.minecraft.init.Blocks.air
                || !chisel && (block.getRenderType() != 0 || !block.renderAsNormalBlock()
                    || HologramNativeTiles.supported(block))
                || block.hasTileEntity(source.metadata())
                || source.tileNbt() != null && !source.tileNbt()
                    .isEmpty()
                || source.itemNbt() != null && !source.itemNbt()
                    .isEmpty()
                || source.item() == null
                || source.item()
                    .isEmpty())
                return null;
            Object item = Item.itemRegistry.getObject(source.item());
            if (!(item instanceof ItemBlock) || item instanceof gregtech.common.blocks.ItemMachines
                || Block.getBlockFromItem((Item) item) != block
                || ((Item) item).getMetadata(source.damage()) != source.metadata()) return null;
            return new ItemStack((Item) item, 1, source.damage());
        }

        Cell(int index, NBTTagCompound n) {
            this(index, n, false);
        }

        /**
         * Transfer a freshly prepared local row and its whole tree; the caller must retain no mutable aliases.
         * Displays remain read-only owned data, exports copy, and mutable ItemStacks are independently decoded.
         */
        static Cell takeOwnedLocalRow(int index, NBTTagCompound row) {
            return new Cell(index, row, true);
        }

        /** Display preparation owns its row tree, so publishing it needs no second NBT tree. */
        private Cell(int index, NBTTagCompound n, boolean owned) {
            localStack = null;
            raw = owned ? n : (NBTTagCompound) n.copy();
            n = HologramSnapshotRows.resolve(raw);
            hintIcons = new String[6];
            candidates = new ArrayList<>();
            nativeModelPending = n.getInteger("nativeModelPending") & 3;
            nativeModelId = n.getLong("nativeModelId");
            modelSource = null;
            this.index = index;
            x = n.getInteger("x");
            y = n.getInteger("y");
            z = n.getInteger("z");
            dx = n.getInteger("dx");
            dy = n.getInteger("dy");
            dz = n.getInteger("dz");
            meta = n.getInteger("meta");
            wantMeta = n.getInteger("wantMeta");
            id = n.getString("id");
            wantId = n.getString("wantId");
            status = n.getString("status");
            chosenChoice = n.hasKey("chosenChoice") ? n.getInteger("chosenChoice") : -1;
            hatchFacing = n.hasKey("hatchFacing") ? n.getInteger("hatchFacing") : -1;
            actualFacing = n.hasKey("actualFacing") ? n.getInteger("actualFacing") : -1;
            targetFacing = n.hasKey("targetFacing") ? n.getInteger("targetFacing") : hatchFacing;
            candidateComplete = n.getBoolean("candidateComplete");
            candidateBoundary = n.getString("candidateBoundary");
            pinScope = n.getString("pinScope");
            role = n.getString("role");
            family = n.getString("family");
            exactIdentity = n.getString("exactIdentity");
            renderKind = n.getString("renderKind");
            operation = n.getString("operation");
            inScope = n.getBoolean("inScope");
            prompt = n.getString("prompt");
            networkGroup = n.getString("networkGroup");
            specialHint = (NBTTagCompound) n.getCompoundTag("specialHint")
                .copy();
            structureId = n.getString("structureId");
            List<String> memberships = new ArrayList<>();
            NBTTagList membershipTags = n.getTagList("structureIds", 8);
            for (int i = 0; i < membershipTags.tagCount(); i++) {
                String member = membershipTags.getStringTagAt(i);
                if (!member.isEmpty() && !memberships.contains(member)) memberships.add(member);
            }
            if (memberships.isEmpty() && !structureId.isEmpty()) memberships.add(structureId);
            structureIds = Collections.unmodifiableList(memberships);
            sourceMod = n.getString("sourceMod");
            sourceVersion = n.getString("sourceVersion");
            sourceClass = n.getString("sourceClass");
            sourceWarning = n.getString("sourceWarning");
            sourceMissing = n.getBoolean("sourceMissing");
            excluded = n.getBoolean("excluded");
            missingBlock = n.getBoolean("missingBlock")
                || !wantId.isEmpty() && !"minecraft:air".equals(wantId) && Block.getBlockFromName(wantId) == null;
            anchor = n.getBoolean("anchor");
            iconOnly = n.getBoolean("iconOnly");
            actualStack = readCellStack(n.getCompoundTag("actualStack"));
            targetStack = readCellStack(n.getCompoundTag("targetStack"));
            actualDisplay = owned ? n.getCompoundTag("actualDisplay")
                : (NBTTagCompound) n.getCompoundTag("actualDisplay")
                    .copy();
            targetDisplay = owned ? n.getCompoundTag("targetDisplay")
                : (NBTTagCompound) n.getCompoundTag("targetDisplay")
                    .copy();
            hintIndex = n.hasKey("hintIndex") ? n.getInteger("hintIndex") : -1;
            hintTint = n.getIntArray("hintTint");
            NBTTagList icons = n.getTagList("hintIcons", 8);
            for (int i = 0; i < 6; i++) hintIcons[i] = i < icons.tagCount() ? icons.getStringTagAt(i) : "";
            NBTTagList list = n.getTagList("candidates", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                ItemStack stack = readCellStack(list.getCompoundTagAt(i));
                if (stack != null) candidates.add(stack);
            }
        }

        private static ItemStack readCellStack(NBTTagCompound tag) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(tag);
            // Vanilla readFromNBT aliases "tag". Mutable ItemStacks must not mutate a shared row palette.
            if (stack != null && stack.hasTagCompound()) stack.setTagCompound(
                (NBTTagCompound) stack.getTagCompound()
                    .copy());
            return stack;
        }

        /** A page descriptor resolves registry fields once; positions and mutable ItemStacks belong to each cell. */
        private Cell(int index, NBTTagCompound row, Cell source) {
            localStack = source.localStack;
            raw = row;
            this.index = index;
            int[] position = HologramSnapshotRows.position(row);
            x = position[0];
            y = position[1];
            z = position[2];
            dx = position[3];
            dy = position[4];
            dz = position[5];
            meta = source.meta;
            wantMeta = source.wantMeta;
            chosenChoice = source.chosenChoice;
            hatchFacing = source.hatchFacing;
            actualFacing = source.actualFacing;
            targetFacing = source.targetFacing;
            id = source.id;
            wantId = source.wantId;
            status = source.status;
            pinScope = source.pinScope;
            role = source.role;
            family = source.family;
            renderKind = source.renderKind;
            operation = source.operation;
            exactIdentity = source.exactIdentity;
            prompt = source.prompt;
            structureId = source.structureId;
            sourceMod = source.sourceMod;
            sourceVersion = source.sourceVersion;
            sourceClass = source.sourceClass;
            sourceWarning = source.sourceWarning;
            networkGroup = source.networkGroup;
            specialHint = source.specialHint;
            structureIds = source.structureIds;
            anchor = source.anchor;
            iconOnly = source.iconOnly;
            excluded = source.excluded;
            missingBlock = source.missingBlock;
            sourceMissing = source.sourceMissing;
            inScope = source.inScope;
            candidateComplete = source.candidateComplete;
            candidateBoundary = source.candidateBoundary;
            actualStack = source.actualStack == null ? null : source.actualStack.copy();
            targetStack = source.targetStack == null ? null : source.targetStack.copy();
            actualDisplay = source.actualDisplay;
            targetDisplay = source.targetDisplay;
            nativeModelPending = source.nativeModelPending;
            nativeModelId = source.nativeModelId;
            modelSource = null;
            hintIcons = source.hintIcons;
            hintIndex = source.hintIndex;
            hintTint = source.hintTint;
            if (source.candidates.isEmpty()) candidates = Collections.emptyList();
            else {
                candidates = new ArrayList<>(source.candidates.size());
                for (ItemStack candidate : source.candidates) candidates.add(candidate.copy());
            }
        }

        private Cell(Cell source, NBTTagCompound actual, NBTTagCompound target, int mask) {
            localStack = source.localStack;
            index = source.index;
            x = source.x;
            y = source.y;
            z = source.z;
            dx = source.dx;
            dy = source.dy;
            dz = source.dz;
            meta = source.meta;
            wantMeta = source.wantMeta;
            chosenChoice = source.chosenChoice;
            hatchFacing = source.hatchFacing;
            actualFacing = source.actualFacing;
            targetFacing = source.targetFacing;
            id = source.id;
            wantId = source.wantId;
            status = source.status;
            pinScope = source.pinScope;
            role = source.role;
            family = source.family;
            renderKind = source.renderKind;
            operation = source.operation;
            exactIdentity = source.exactIdentity;
            prompt = source.prompt;
            structureId = source.structureId;
            sourceMod = source.sourceMod;
            sourceVersion = source.sourceVersion;
            sourceClass = source.sourceClass;
            sourceWarning = source.sourceWarning;
            networkGroup = source.networkGroup;
            specialHint = source.specialHint;
            structureIds = source.structureIds;
            anchor = source.anchor;
            iconOnly = source.iconOnly;
            excluded = source.excluded;
            missingBlock = source.missingBlock;
            sourceMissing = source.sourceMissing;
            inScope = source.inScope;
            candidateComplete = source.candidateComplete;
            candidateBoundary = source.candidateBoundary;
            actualStack = source.actualStack;
            targetStack = source.targetStack;
            hintIcons = source.hintIcons;
            hintIndex = source.hintIndex;
            hintTint = source.hintTint;
            candidates = source.candidates;
            nativeModelId = source.nativeModelId;
            modelSource = source.modelSource == null ? source : source.modelSource;
            nativeModelPending = source.nativeModelPending & ~mask;
            actualDisplay = modelDisplay(source.actualDisplay, actual);
            targetDisplay = modelDisplay(source.targetDisplay, target);
            raw = shallow(source.raw);
            raw.setTag("actualDisplay", actualDisplay);
            raw.setTag("targetDisplay", targetDisplay);
            raw.setInteger("nativeModelPending", nativeModelPending);
        }

        private static NBTTagCompound modelDisplay(NBTTagCompound source, NBTTagCompound nativeTile) {
            if (nativeTile == null) return source;
            NBTTagCompound result = shallow(source);
            result.setTag("nativeTile", nativeTile);
            return result;
        }

        boolean modelPending(boolean current) {
            current = current || anchor || status.equals("protected") && actualStack != null;
            return (nativeModelPending & (current ? 1 : 2)) != 0;
        }

        Block block(boolean current) {
            return Block.getBlockFromName(current ? id : wantId);
        }

        NBTTagCompound tag() {
            if (raw != null) return HologramSnapshotRows.export(raw);
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", x);
            row.setInteger("y", y);
            row.setInteger("z", z);
            row.setInteger("dx", dx);
            row.setInteger("dy", dy);
            row.setInteger("dz", dz);
            row.setString("id", id);
            row.setString("wantId", wantId);
            row.setInteger("meta", meta);
            row.setInteger("wantMeta", wantMeta);
            row.setString("status", status);
            row.setBoolean("excluded", excluded);
            row.setString("prompt", prompt);
            row.setBoolean("anchor", anchor);
            row.setString("role", role);
            row.setInteger("targetFacing", targetFacing);
            row.setInteger("actualFacing", actualFacing);
            row.setString("candidateBoundary", candidateBoundary);
            row.setString("structureId", structureId);
            NBTTagList ids = new NBTTagList();
            for (String member : structureIds) ids.appendTag(new net.minecraft.nbt.NBTTagString(member));
            row.setTag("structureIds", ids);
            row.setLong("nativeModelId", nativeModelId);
            row.setInteger("nativeModelPending", nativeModelPending);
            if (localStack != null) {
                row.setTag("actualStack", localStack.writeToNBT(new NBTTagCompound()));
                row.setTag("targetStack", localStack.writeToNBT(new NBTTagCompound()));
                NBTTagCompound display = new NBTTagCompound();
                display.setInteger("color", -1);
                if (targetFacing >= 0) display.setInteger("facing", targetFacing);
                row.setTag("actualDisplay", display);
                row.setTag("targetDisplay", display.copy());
            }
            return row;
        }

        boolean belongsToStructure(String id) {
            return !id.isEmpty() && structureIds.contains(id);
        }

        boolean quantumNetworkHint() {
            return "quantumNetwork".equals(specialHint.getString("type")) && networkGroup.matches("q[1-9][0-9]*")
                && networkGroup.equals(specialHint.getString("group"));
        }

        boolean completed() {
            return status.equals("placed") || status.equals("replaced") || status.equals("removed");
        }

        int textureColor() {
            if (iconOnly && hintTint.length >= 3)
                return ((hintTint[0] & 255) << 16) | ((hintTint[1] & 255) << 8) | (hintTint[2] & 255);
            return color();
        }

        int color() {
            if (excluded) return 0x71848c;
            if (missingBlock) return 0xff52cf;
            if (sourceMissing) return 0x71848c;
            if (status.equals("protected")) return 0xe4b85a;
            if (status.equals("unsupported")) return 0xb68ede;
            if (status.equals("missing")) return 0xe6747a;
            if (status.equals("satisfied") || completed()) return 0x78d7a1;
            return 0x69d7ed;
        }
    }

    static final class Material {

        final ItemStack stack;
        final int required, available, mainAvailable, controllerAvailable;

        Material(NBTTagCompound n) {
            stack = ItemStack.loadItemStackFromNBT(n);
            required = n.getInteger("required");
            available = n.getInteger("available");
            mainAvailable = n.getInteger("mainAvailable");
            controllerAvailable = n.getInteger("controllerAvailable");
        }
    }
}
