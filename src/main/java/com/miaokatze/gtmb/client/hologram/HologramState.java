package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramBlueprintLimits;

/** Immutable client snapshot; all amounts and construction outcomes come from the server. */
final class HologramState {

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
    boolean topologyChanged;
    final java.util.Map<String, List<Integer>> structureMembers;
    int minY = 0, maxY = 0;
    private int minWorldX, maxWorldX, minWorldY, maxWorldY, minWorldZ, maxWorldZ;

    HologramState(NBTTagCompound source) {
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

    /** Metadata decorators share geometry; preserve the incoming publication's renderer and selection obligations. */
    private HologramState(HologramState decorated, HologramState publication) {
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
                    Cell cell = new Cell(cellPosition, row, displayRows);
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
                row = old.tag();
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
        final String[] hintIcons = new String[6];
        final int hintIndex;
        final int[] hintTint;
        final List<ItemStack> candidates = new ArrayList<>();

        Cell(int index, NBTTagCompound n) {
            this(index, n, false);
        }

        /** Display preparation owns its row tree, so publishing it needs no second NBT tree. */
        private Cell(int index, NBTTagCompound n, boolean owned) {
            raw = owned ? n : (NBTTagCompound) n.copy();
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
            actualStack = ItemStack.loadItemStackFromNBT(n.getCompoundTag("actualStack"));
            targetStack = ItemStack.loadItemStackFromNBT(n.getCompoundTag("targetStack"));
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
                ItemStack stack = ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(i));
                if (stack != null) candidates.add(stack);
            }
        }

        Block block(boolean current) {
            return Block.getBlockFromName(current ? id : wantId);
        }

        NBTTagCompound tag() {
            return (NBTTagCompound) raw.copy();
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
