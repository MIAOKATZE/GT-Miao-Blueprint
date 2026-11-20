// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramBlueprintLimits;
import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.gtmb.hologram.HologramSnapshotPalette;

import gregtech.common.blocks.ItemMachines;

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
    private final Object controllerTargetIdentity;
    final List<Cell> plainDisplayExamples;
    List<Cell> changedPreviousCells = Collections.emptyList();
    boolean topologyChanged;
    final java.util.Map<String, List<Integer>> structureMembers;
    int minY = 0, maxY = 0;
    private int minWorldX, maxWorldX, minWorldY, maxWorldY, minWorldZ, maxWorldZ;

    HologramState(NBTTagCompound source) {
        fullBaseline = fullBaseline(source);
        controllerTargetIdentity = null;
        plainDisplayExamples = Collections.emptyList();
        parentIdentity = null;
        changedIndices = new int[0];
        data = metadata(source);
        session = data.getString("session");
        minWorldX = maxWorldX = data.getInteger("x");
        minWorldY = maxWorldY = data.getInteger("y");
        minWorldZ = maxWorldZ = data.getInteger("z");
        List<Cell> parsed = new ArrayList<>();
        NBTTagList list = source.getTagList("cells", 10);
        boolean packed = HologramSnapshotPalette.isPacked(source);
        if (packed) try {
            HologramSnapshotPalette.validate(source);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
        NBTTagList palette = packed ? (NBTTagList) source.getTagList(HologramSnapshotPalette.PALETTE_KEY, 10)
            .copy() : null;
        if (!HologramBlueprintLimits.accepts(list.tagCount()))
            throw new IllegalArgumentException("Blueprint cell count exceeds limit");
        parsedCells = list.tagCount();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound row = list.getCompoundTagAt(i);
            Cell cell = packed
                ? new Cell(
                    i,
                    palette.getCompoundTagAt(row.getInteger(HologramSnapshotPalette.REFERENCE_KEY)),
                    (NBTTagCompound) row.copy(),
                    true)
                : new Cell(i, row);
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
        controllerTargetIdentity = null;
        plainDisplayExamples = Collections.unmodifiableList(ready.plainDisplayExamples);
        parentIdentity = null;
        changedIndices = new int[0];
        data = ready.data;
        session = data.getString("session");
        parsedCells = ready.cellPosition;
        cells = new CellPages(ready.pages.toArray(new Cell[ready.pages.size()][]), parsedCells);
        regions = ready.regions.finish();
        boolean compact = ready.anonymousCompact;
        structureMembers = compact ? anonymousMembers(parsedCells) : Collections.unmodifiableMap(ready.members);
        materials = Collections.unmodifiableList(ready.materials);
        if (compact && parsedCells != 0) {
            int[] bounds = regions.bounds();
            minY = bounds[1];
            maxY = bounds[4];
            minWorldX = Math.min(ready.minWorldX, bounds[0]);
            maxWorldX = Math.max(ready.maxWorldX, bounds[3]);
            minWorldY = Math.min(ready.minWorldY, bounds[1]);
            maxWorldY = Math.max(ready.maxWorldY, bounds[4]);
            minWorldZ = Math.min(ready.minWorldZ, bounds[2]);
            maxWorldZ = Math.max(ready.maxWorldZ, bounds[5]);
        } else {
            minY = ready.minY;
            maxY = ready.maxY;
            minWorldX = ready.minWorldX;
            maxWorldX = ready.maxWorldX;
            minWorldY = ready.minWorldY;
            maxWorldY = ready.maxWorldY;
            minWorldZ = ready.minWorldZ;
            maxWorldZ = ready.maxWorldZ;
        }
    }

    private HologramState(ControllerDetails ready) {
        HologramState geometry = ready.geometry;
        data = ready.data;
        session = geometry.session;
        cells = ready.enriched;
        regions = geometry.regions.withCells(cells);
        materials = geometry.materials;
        structureMembers = geometry.structureMembers;
        plainDisplayExamples = geometry.plainDisplayExamples;
        parentIdentity = geometry.identity;
        controllerTargetIdentity = ready.samePresentation ? geometry.identity : null;
        changedIndices = new int[0];
        parsedCells = ready.cursor;
        fullBaseline = geometry.fullBaseline;
        minY = geometry.minY;
        maxY = geometry.maxY;
        minWorldX = geometry.minWorldX;
        minWorldY = geometry.minWorldY;
        minWorldZ = geometry.minWorldZ;
        maxWorldX = geometry.maxWorldX;
        maxWorldY = geometry.maxWorldY;
        maxWorldZ = geometry.maxWorldZ;
    }

    boolean reusesControllerGeometry(HologramState geometry) {
        return geometry != null && controllerTargetIdentity == geometry.identity;
    }

    /** Adds editor details to a complete target without decoding its spatial, model or world data again. */
    static final class ControllerDetails {

        private static final java.util.Set<String> DETAIL_FIELDS = new java.util.HashSet<>(Arrays.asList(
            "index", "role", "candidates", "candidateRoles", "candidateTiers", "chosenChoice", "candidateComplete",
            "candidateBoundary"));
        private static final java.util.Set<String> NON_VISUAL_METADATA = new java.util.HashSet<>(Arrays.asList(
            "capturePending", "projectionDataComplete", "capabilities", "structureDescription", "status"));
        private final Thread owner = Thread.currentThread();
        private final HologramState geometry;
        private final NBTTagCompound data;
        private final NBTTagList rows;
        private final CellPages enriched;
        private boolean samePresentation = true;
        private int cursor;

        ControllerDetails(HologramState geometry, NBTTagCompound details) {
            rows = details.getTagList("cells", 10);
            if (!geometry.data.getBoolean("localStandardController") || !geometry.data.getBoolean("capturePending")
                || !details.getBoolean("projectionDataComplete") || details.getBoolean("capturePending")
                || !geometry.session.equals(details.getString("session"))
                || !geometry.data.getString("generation").equals(details.getString("generation"))
                || rows.tagCount() != geometry.cells.size()) throw new IllegalArgumentException("Stale controller details");
            this.geometry = geometry;
            enriched = new CellPages((CellPages) geometry.cells, geometry.cells.size());
            NBTTagCompound updates = metadata(details, false);
            data = shallow(geometry.data);
            for (Object name : updates.func_150296_c()) {
                String key = (String) name;
                NBTBase value = updates.getTag(key);
                if (!NON_VISUAL_METADATA.contains(key) && !java.util.Objects.equals(value, data.getTag(key)))
                    samePresentation = false;
                data.setTag(key, value);
            }
        }

        boolean step(int maxRows, long maxNanos) {
            if (Thread.currentThread() != owner || maxRows < 1 || maxNanos < 1)
                throw new IllegalArgumentException("Invalid controller detail budget");
            long started = System.nanoTime();
            int processed = 0;
            while (cursor < rows.tagCount() && processed < maxRows
                && (processed == 0 || System.nanoTime() - started < maxNanos)) {
                NBTTagCompound row = rows.getCompoundTagAt(cursor);
                if (!DETAIL_FIELDS.containsAll(row.func_150296_c())
                    || row.hasKey("index") && (!row.hasKey("index", 3) || row.getInteger("index") != cursor))
                    throw new IllegalArgumentException("Invalid controller detail row");
                if (!row.hasNoTags()) {
                    Cell target = geometry.cells.get(cursor);
                    Cell cell = new Cell(target, row);
                    if (!target.role.equals(cell.role) && !("block".equals(target.role) && "hatch".equals(cell.role)))
                        samePresentation = false;
                    enriched.replace(cursor, cell);
                }
                cursor++;
                processed++;
            }
            return cursor == rows.tagCount();
        }

        HologramState result() {
            if (cursor != rows.tagCount()) throw new IllegalStateException("Controller details still pending");
            return new HologramState(this);
        }
    }

    /** Metadata decorators share geometry; preserve the incoming publication's renderer and selection obligations. */
    private HologramState(HologramState decorated, HologramState publication) {
        data = decorated.data;
        session = decorated.session;
        cells = decorated.cells;
        plainDisplayExamples = decorated.plainDisplayExamples;
        materials = decorated.materials;
        regions = decorated.regions;
        structureMembers = decorated.structureMembers;
        parentIdentity = publication.parentIdentity;
        changedIndices = publication.changedIndices;
        changedPreviousCells = publication.changedPreviousCells;
        topologyChanged = publication.topologyChanged;
        parsedCells = publication.parsedCells;
        fullBaseline = publication.fullBaseline;
        controllerTargetIdentity = publication.controllerTargetIdentity;
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
        controllerTargetIdentity = null;
        plainDisplayExamples = Collections.emptyList();
        parentIdentity = null;
        changedIndices = new int[0];
        data = metadata(ready.data, false);
        data.setBoolean("displayPartial", true);
        session = data.getString("session");
        parsedCells = count;
        List<Cell> prefix = new ArrayList<>(count);
        for (int i = 0; i < count; i++) prefix.add(ready.pages.get(i >> 8)[i & 255]);
        cells = new CellPages(prefix);
        HologramRegionCache partialRegions = new HologramRegionCache(cells);
        int[] wholeBounds = ready.compactCells == null ? null : ready.compactCells.bounds();
        regions = wholeBounds == null ? partialRegions : partialRegions.withBounds(wholeBounds);
        structureMembers = ready.anonymousCompact ? anonymousMembers(count) : memberships(cells);
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
        if (wholeBounds != null) {
            minWorldX = wholeBounds[0];
            minY = minWorldY = wholeBounds[1];
            minWorldZ = wholeBounds[2];
            maxWorldX = wholeBounds[3];
            maxY = maxWorldY = wholeBounds[4];
            maxWorldZ = wholeBounds[5];
        }
    }

    /** Registry/item resolution and spatial indexing share a cooperative client-thread budget. */
    static final class Preparation {

        private static final String[] COORDINATES = { "x", "y", "z", "dx", "dy", "dz" };
        private static final List<String> DISPLAY_ROW_KEYS = Arrays.asList(
            "x",
            "y",
            "z",
            "dx",
            "dy",
            "dz",
            "wantId",
            "wantMeta",
            "targetFacing",
            "previewItem",
            "previewDamage");

        private final Thread owner = Thread.currentThread();
        private final NBTTagCompound data;
        private final NBTTagList rows, materialRows;
        private final List<Cell[]> pages = new ArrayList<>();
        private final HologramRegionCache.Builder regions;
        private final java.util.Map<String, List<Integer>> members = new java.util.LinkedHashMap<>();
        private final List<Material> materials = new ArrayList<>();
        private java.util.Iterator<java.util.Map.Entry<String, List<Integer>>> memberIterator;
        private int stage, cellPosition, materialPosition, memberPosition;
        private int minY, maxY, minWorldX, maxWorldX, minWorldY, maxWorldY, minWorldZ, maxWorldZ;
        private HologramState result;
        private final boolean displayRows;
        private final boolean anonymousCompact;
        private final HologramBlueprintPreview.CompactCells compactCells;
        private final List<HologramControllerTarget.Cell> controllerCells;
        private final DescriptorTemplate[] compactMaterials;
        private final boolean[] seenCompactMaterials;
        private int compactDescriptorCursor;
        private boolean detachedIndex;
        private final int cellCount;
        private final NBTTagCompound source;
        private final boolean packed;
        private final boolean projection;
        private final java.util.IdentityHashMap<NBTTagCompound, DescriptorTemplate> descriptors;
        private final Map<DisplayMaterial, DescriptorTemplate> displayDescriptors;
        private final List<Cell> plainDisplayExamples = new ArrayList<>();
        private final boolean fullBaseline;
        int lastStepWork;

        /** Ownership of the decoded tree transfers to this preparation; no published state mutates it. */
        Preparation(NBTTagCompound source) {
            this(source, false);
        }

        Preparation(NBTTagCompound source, boolean displayRows) {
            this(source, displayRows, null);
        }

        Preparation(NBTTagCompound source, boolean displayRows, HologramBlueprintPreview.CompactCells compactCells) {
            this(source, displayRows, compactCells, null);
        }

        Preparation(HologramControllerTarget.Projection target) {
            this(target.metadata(), false, null, target.cells());
        }

        private Preparation(NBTTagCompound source, boolean displayRows, HologramBlueprintPreview.CompactCells compactCells,
            List<HologramControllerTarget.Cell> controllerCells) {
            this.source = source;
            packed = HologramSnapshotPalette.isPacked(source);
            projection = source.getBoolean("structureProjection");
            descriptors = packed ? new java.util.IdentityHashMap<>() : null;
            displayDescriptors = displayRows && !packed && compactCells == null ? new HashMap<>() : null;
            this.displayRows = displayRows;
            this.compactCells = compactCells;
            anonymousCompact = compactCells != null && !compactCells.editorRows();
            this.controllerCells = controllerCells;
            regions = new HologramRegionCache.Builder(compactCells != null || controllerCells != null);
            compactMaterials = compactCells == null ? null : new DescriptorTemplate[compactCells.materialCount()];
            seenCompactMaterials = compactCells == null ? null : new boolean[compactCells.materialCount()];
            fullBaseline = controllerCells != null || compactCells != null || fullBaseline(source);
            data = metadata(source, false);
            rows = source.getTagList("cells", 10);
            cellCount = controllerCells != null ? controllerCells.size()
                : compactCells == null ? rows.tagCount() : compactCells.size();
            if (!HologramBlueprintLimits.accepts(cellCount))
                throw new IllegalArgumentException("Blueprint cell count exceeds limit");
            materialRows = source.getTagList("materials", 10);
            minWorldX = maxWorldX = data.getInteger("x");
            minWorldY = maxWorldY = data.getInteger("y");
            minWorldZ = maxWorldZ = data.getInteger("z");
        }

        boolean step(int maxWork, long maxNanos) {
            checkClientOwner();
            return stepOwned(maxWork, maxNanos);
        }

        private void checkClientOwner() {
            if (Thread.currentThread() != owner || detachedIndex)
                throw new IllegalStateException("Client preparation no longer owns the snapshot");
        }

        private boolean stepOwned(int maxWork, long maxNanos) {
            if (maxWork < 1 || maxNanos < 1) throw new IllegalArgumentException("Client preparation budget");
            long started = System.nanoTime();
            lastStepWork = 0;
            while (stage < 4 && lastStepWork < maxWork
                && (maxNanos == Long.MAX_VALUE || lastStepWork == 0 || System.nanoTime() - started < maxNanos)) {
                if (stage == 0) {
                    if (cellPosition == cellCount) {
                        stage++;
                        continue;
                    }
                    Cell cell = controllerCells != null ? new Cell(cellPosition, controllerCells.get(cellPosition))
                        : compactCells == null ? prepareCell(rows.getCompoundTagAt(cellPosition)) : prepareCompactCell();
                    if ((cellPosition & 255) == 0) pages.add(new Cell[Math.min(256, cellCount - cellPosition)]);
                    pages.get(cellPosition >> 8)[cellPosition & 255] = cell;
                    regions.add(cell);
                    if (!anonymousCompact) {
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
                    }
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

        boolean canPrepareCompactIndex() {
            return compactCells != null && cellCount >= 32_768 && compactMaterials.length <= 64
                && materialRows.tagCount() == 0;
        }

        /** Resolve the renderer's mod-facing descriptors here. Only detached row assembly goes to the worker. */
        DetachedIndex prepareCompactIndex(int prefixRows, long budgetNanos) {
            checkClientOwner();
            if (!canPrepareCompactIndex() || prefixRows < 1 || budgetNanos < 1)
                throw new IllegalArgumentException("Invalid compact index preparation");
            long started = System.nanoTime();
            while (compactDescriptorCursor < compactMaterials.length) {
                if (compactCells.definedMaterial(compactDescriptorCursor)) compactDescriptor(compactDescriptorCursor);
                compactDescriptorCursor++;
                if (System.nanoTime() - started >= budgetNanos) return null;
            }
            int missingPrefix = Math.min(cellCount, prefixRows) - cellPosition;
            if (missingPrefix > 0) {
                long remaining = budgetNanos - (System.nanoTime() - started);
                if (remaining <= 0) return null;
                stepOwned(missingPrefix, remaining);
                if (cellPosition < Math.min(cellCount, prefixRows)) return null;
            }
            HologramState first = firstDisplay();
            detachedIndex = true;
            return new DetachedIndex(this, first);
        }

        static final class DetachedIndex {

            private final Preparation preparation;
            private final HologramState firstDisplay;
            private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();

            private DetachedIndex(Preparation preparation, HologramState firstDisplay) {
                this.preparation = preparation;
                this.firstDisplay = firstDisplay;
            }

            HologramState firstDisplay() {
                return firstDisplay;
            }

            HologramState finish(java.util.function.BooleanSupplier current) {
                if (!claimed.compareAndSet(false, true)) throw new IllegalStateException("Index already claimed");
                while (current.getAsBoolean())
                    if (preparation.stepOwned(4096, Long.MAX_VALUE))
                        return current.getAsBoolean() ? preparation.result() : null;
                return null;
            }
        }

        private Cell prepareCell(NBTTagCompound row) {
            if (displayRows) try {
                Cell shared = displayDescriptors == null ? null : plainDisplayCell(row);
                if (shared != null) return shared;
                HologramBlueprintPreview.completeCell(row);
            } catch (java.io.IOException invalid) {
                throw new IllegalArgumentException(invalid.getMessage(), invalid);
            }
            return packed ? new Cell(cellPosition, descriptor(row), row)
                : new Cell(cellPosition, row, null, true, projection);
        }

        private Cell prepareCompactCell() {
            int material = compactCells.coordinate(cellPosition, 3);
            DescriptorTemplate template = compactDescriptor(material);
            int x = compactCells.coordinate(cellPosition, 0);
            int y = compactCells.coordinate(cellPosition, 1);
            int z = compactCells.coordinate(cellPosition, 2);
            Cell cell = new Cell(cellPosition, template, null, true, x, y, z, x, y, z);
            if (!seenCompactMaterials[material]) {
                seenCompactMaterials[material] = true;
                if (template.compactPlain) plainDisplayExamples.add(cell);
            }
            return cell;
        }

        private DescriptorTemplate compactDescriptor(int material) {
            DescriptorTemplate template = compactMaterials[material];
            if (template == null) {
                if (detachedIndex) throw new IllegalArgumentException("Missing frozen blueprint descriptor");
                NBTTagCompound definition = compactCells.copyMaterial(material);
                boolean withoutPayload = !definition.hasKey("previewTile", 10)
                    && !definition.hasKey("previewItemTag", 10);
                try {
                    HologramBlueprintPreview.completeCell(definition);
                } catch (java.io.IOException invalid) {
                    throw new IllegalArgumentException(invalid.getMessage(), invalid);
                }
                template = new DescriptorTemplate(definition, true, anonymousCompact && withoutPayload);
                compactMaterials[material] = template;
            }
            return template;
        }

        /** Share only the known detached source rows with no tile/item payload or additional cell data. */
        private Cell plainDisplayCell(NBTTagCompound row) throws java.io.IOException {
            java.util.Set<?> keys = row.func_150296_c();
            if (keys.size() != DISPLAY_ROW_KEYS.size() || !keys.containsAll(DISPLAY_ROW_KEYS)
                || !row.hasKey("wantMeta", 3)
                || !row.hasKey("targetFacing", 3)) return null;
            DisplayMaterial material = new DisplayMaterial(row);
            DescriptorTemplate template = displayDescriptors.get(material);
            boolean newTemplate = false;
            if (template == null && displayDescriptors.size() < 4096 && !displayDescriptors.containsKey(material)) {
                NBTTagCompound descriptor = new NBTTagCompound();
                descriptor.setString("wantId", material.block);
                descriptor.setInteger("wantMeta", material.metadata);
                descriptor.setInteger("targetFacing", material.facing);
                descriptor.setString("previewItem", material.item);
                descriptor.setInteger("previewDamage", material.damage);
                HologramBlueprintPreview.completeCell(descriptor);
                DescriptorTemplate candidate = new DescriptorTemplate(descriptor, true);
                if (candidate.plainTarget()) {
                    template = candidate;
                    newTemplate = true;
                }
                displayDescriptors.put(material, template);
            }
            if (template == null) return null;
            NBTTagCompound position = new NBTTagCompound();
            for (String axis : COORDINATES) position.setTag(axis, row.getTag(axis));
            Cell cell = new Cell(cellPosition, template, position, true);
            if (newTemplate) plainDisplayExamples.add(cell);
            return cell;
        }

        private DescriptorTemplate descriptor(NBTTagCompound row) {
            NBTTagCompound raw = HologramSnapshotPalette.descriptor(source, row);
            DescriptorTemplate template = descriptors.get(raw);
            if (template == null) {
                template = new DescriptorTemplate(raw, projection);
                descriptors.put(raw, template);
            }
            return template;
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
            return stage == 0 ? cellCount
                : stage == 1 ? regions.pending.size() : stage == 2 ? materialRows.tagCount() : members.size();
        }

        String unit() {
            return stage == 0 ? "cells" : stage == 1 ? "regions" : stage == 2 ? "materials" : "structures";
        }

        HologramState result() {
            if (stage != 4) throw new IllegalStateException("Client snapshot is incomplete");
            if (result == null) {
                result = new HologramState(this);
                if (packed) HologramRuntimeMetrics.clientDescriptorsPrepared(descriptors.size(), rows.tagCount());
            }
            return result;
        }

        HologramState firstDisplay() {
            return cellPosition == 0 ? null : new HologramState(this, Math.min(256, cellPosition));
        }
    }

    private static final class DisplayMaterial {

        final String block, item;
        final int metadata, facing, damage;

        DisplayMaterial(NBTTagCompound row) {
            block = row.getString("wantId");
            item = row.getString("previewItem");
            metadata = row.getInteger("wantMeta");
            facing = row.getInteger("targetFacing");
            damage = row.getInteger("previewDamage");
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof DisplayMaterial)) return false;
            DisplayMaterial value = (DisplayMaterial) other;
            return metadata == value.metadata && facing == value.facing
                && damage == value.damage
                && block.equals(value.block)
                && item.equals(value.item);
        }

        @Override
        public int hashCode() {
            int result = 31 * block.hashCode() + item.hashCode();
            result = 31 * result + metadata;
            result = 31 * result + facing;
            return 31 * result + damage;
        }
    }

    private HologramState(HologramState base, NBTTagCompound patch) {
        this(base, patch, false);
    }

    private HologramState(HologramState base, NBTTagCompound patch, boolean decoration) {
        fullBaseline = decoration && base.fullBaseline;
        controllerTargetIdentity = null;
        plainDisplayExamples = decoration ? base.plainDisplayExamples : Collections.emptyList();
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

    static boolean fullBaseline(NBTTagCompound source) {
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
        boolean packed = HologramSnapshotPalette.isPacked(source);
        for (Object name : source.func_150296_c()) {
            String key = (String) name;
            if (!key.equals("cells") && !key.equals("partial")
                && !key.equals("areaSelectionOnly")
                && !key.equals("baseRevision")
                && !key.equals("processingPatch")
                && !key.equals("processingConfirmedStages")
                && !(packed && HologramSnapshotPalette.metadataKey(key)))
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

    /** Compact library display rows have one implicit group and use their accepted row order as cell indices. */
    private static Map<String, List<Integer>> anonymousMembers(int count) {
        return count == 0 ? Collections.emptyMap() : Collections.singletonMap("", new CellIndexRange(count));
    }

    private static final class CellIndexRange extends java.util.AbstractList<Integer>
        implements java.util.RandomAccess {

        private final int size;

        CellIndexRange(int size) {
            this.size = size;
        }

        @Override
        public Integer get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            return index;
        }

        @Override
        public int size() {
            return size;
        }
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

    /** The unpublished prototype is sealed by ownership: no caller receives its stacks, arrays or raw NBT. */
    private static final class DescriptorTemplate {

        private final Cell values;
        private final ItemStack actualStack, targetStack;
        private final boolean compactPlain;

        DescriptorTemplate(NBTTagCompound raw, boolean resolvedBlocks) {
            this(raw, resolvedBlocks, false);
        }

        DescriptorTemplate(NBTTagCompound raw, boolean resolvedBlocks, boolean compactWithoutPayload) {
            values = new Cell(-1, raw, null, true, resolvedBlocks);
            actualStack = values.actualStack;
            targetStack = values.targetStack;
            compactPlain = compactWithoutPayload && plainTarget();
        }

        boolean plainTarget() {
            return !values.targetDisplay.hasKey("nativeTile", 10)
                && (targetStack == null || !(targetStack.getItem() instanceof ItemMachines));
        }
    }

    static final class Cell {

        private static final int[] EMPTY_HINT_TINT = new int[0];
        private static final String[] EMPTY_HINT_ICONS = { "", "", "", "", "", "" };
        private static final NBTTagCompound EMPTY_DISPLAY = new NBTTagCompound();
        private final NBTTagCompound raw;
        private final HologramControllerTarget.Cell controllerCell;
        private final NBTTagCompound controllerDetails;
        private final NBTTagCompound coordinates;
        private final DescriptorTemplate descriptorTemplate;
        private final boolean resolvedBlocks;
        private final Block actualBlock, targetBlock;

        final int index, x, y, z, dx, dy, dz, meta, wantMeta, chosenChoice, hatchFacing, actualFacing, targetFacing;
        final String id, wantId, status, pinScope, role, family, renderKind, operation, exactIdentity;
        final String prompt, structureId, sourceMod, sourceVersion, sourceClass, sourceWarning;
        final String networkGroup;
        private final NBTTagCompound specialHint;
        final List<String> structureIds;
        final boolean anchor, iconOnly, excluded, missingBlock, sourceMissing, inScope;
        final boolean candidateComplete;
        final String candidateBoundary;
        final ItemStack actualStack, targetStack;
        private NBTTagCompound actualDisplay, targetDisplay;
        final String[] hintIcons;
        final int hintIndex;
        final int[] hintTint;
        final List<ItemStack> candidates;

        Cell(int index, NBTTagCompound n) {
            this(index, n, false);
        }

        /** Preparation owns its row tree; display compounds are read-only until native hydration copies them. */
        private Cell(int index, NBTTagCompound n, boolean owned) {
            this(index, n, null, owned);
        }

        /** Packed snapshots retain one read-only descriptor and a small coordinate row, never a merged row. */
        private Cell(int index, NBTTagCompound n, NBTTagCompound positions, boolean owned) {
            this(index, n, positions, owned, false);
        }

        /** Structure projections and prepared local descriptors retain their cached render identity. */
        private Cell(int index, NBTTagCompound n, NBTTagCompound positions, boolean owned, boolean resolvedBlocks) {
            raw = owned ? n : (NBTTagCompound) n.copy();
            controllerCell = null;
            controllerDetails = null;
            coordinates = positions;
            descriptorTemplate = null;
            this.resolvedBlocks = resolvedBlocks;
            this.index = index;
            NBTTagCompound location = positions == null ? n : positions;
            x = location.getInteger("x");
            y = location.getInteger("y");
            z = location.getInteger("z");
            dx = location.getInteger("dx");
            dy = location.getInteger("dy");
            dz = location.getInteger("dz");
            meta = n.getInteger("meta");
            wantMeta = n.getInteger("wantMeta");
            id = n.getString("id");
            wantId = n.getString("wantId");
            actualBlock = resolvedBlocks ? Block.getBlockFromName(id) : null;
            targetBlock = resolvedBlocks ? id.equals(wantId) ? actualBlock : Block.getBlockFromName(wantId) : null;
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
            specialHint = owned ? n.getCompoundTag("specialHint")
                : (NBTTagCompound) n.getCompoundTag("specialHint")
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
            missingBlock = n.getBoolean("missingBlock") || !wantId.isEmpty() && !"minecraft:air".equals(wantId)
                && (resolvedBlocks ? targetBlock : Block.getBlockFromName(wantId)) == null;
            anchor = n.getBoolean("anchor");
            iconOnly = n.getBoolean("iconOnly");
            actualStack = n.hasKey("actualStack", 10) ? readStack(n.getCompoundTag("actualStack")) : null;
            targetStack = n.hasKey("targetStack", 10) ? readStack(n.getCompoundTag("targetStack")) : null;
            actualDisplay = owned ? n.getCompoundTag("actualDisplay")
                : (NBTTagCompound) n.getCompoundTag("actualDisplay")
                    .copy();
            targetDisplay = owned ? n.getCompoundTag("targetDisplay")
                : (NBTTagCompound) n.getCompoundTag("targetDisplay")
                    .copy();
            hintIndex = n.hasKey("hintIndex") ? n.getInteger("hintIndex") : -1;
            int[] tint = n.getIntArray("hintTint");
            hintTint = tint.length == 0 ? tint : tint.clone();
            NBTTagList icons = n.getTagList("hintIcons", 8);
            hintIcons = new String[6];
            for (int i = 0; i < 6; i++) hintIcons[i] = i < icons.tagCount() ? icons.getStringTagAt(i) : "";
            NBTTagList list = n.getTagList("candidates", 10);
            candidates = new ArrayList<>();
            for (int i = 0; i < list.tagCount(); i++) {
                ItemStack stack = readStack(list.getCompoundTagAt(i));
                if (stack != null) candidates.add(stack);
            }
        }

        private Cell(int index, DescriptorTemplate template, NBTTagCompound positions) {
            this(index, template, positions, false);
        }

        /** Shared parsing retains each cell's independently mutable values. */
        private Cell(int index, DescriptorTemplate template, NBTTagCompound positions, boolean local) {
            this(
                index,
                template,
                positions,
                local,
                positions.getInteger("x"),
                positions.getInteger("y"),
                positions.getInteger("z"),
                positions.getInteger("dx"),
                positions.getInteger("dy"),
                positions.getInteger("dz"));
        }

        private Cell(int index, DescriptorTemplate template, NBTTagCompound positions, boolean local, int x, int y,
            int z, int dx, int dy, int dz) {
            Cell value = template.values;
            boolean compactPlain = template.compactPlain;
            raw = value.raw;
            controllerCell = null;
            controllerDetails = null;
            coordinates = positions;
            descriptorTemplate = template;
            resolvedBlocks = value.resolvedBlocks;
            actualBlock = value.actualBlock;
            targetBlock = value.targetBlock;
            this.index = index;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            meta = value.meta;
            wantMeta = value.wantMeta;
            chosenChoice = value.chosenChoice;
            hatchFacing = value.hatchFacing;
            actualFacing = value.actualFacing;
            targetFacing = value.targetFacing;
            id = value.id;
            wantId = value.wantId;
            status = value.status;
            pinScope = value.pinScope;
            role = value.role;
            family = value.family;
            renderKind = value.renderKind;
            operation = value.operation;
            exactIdentity = value.exactIdentity;
            prompt = value.prompt;
            structureId = value.structureId;
            sourceMod = value.sourceMod;
            sourceVersion = value.sourceVersion;
            sourceClass = value.sourceClass;
            sourceWarning = value.sourceWarning;
            networkGroup = value.networkGroup;
            specialHint = compactPlain ? null : local ? (NBTTagCompound) value.specialHint.copy() : value.specialHint;
            structureIds = value.structureIds;
            anchor = value.anchor;
            iconOnly = value.iconOnly;
            excluded = value.excluded;
            missingBlock = value.missingBlock;
            sourceMissing = value.sourceMissing;
            inScope = value.inScope;
            candidateComplete = value.candidateComplete;
            candidateBoundary = value.candidateBoundary;
            actualStack = template.actualStack == null ? null : template.actualStack.copy();
            targetStack = template.targetStack == null ? null : template.targetStack.copy();
            actualDisplay = compactPlain ? null
                : local ? (NBTTagCompound) value.actualDisplay.copy() : value.actualDisplay;
            targetDisplay = compactPlain ? null
                : local ? (NBTTagCompound) value.targetDisplay.copy() : value.targetDisplay;
            hintIndex = value.hintIndex;
            hintTint = compactPlain ? EMPTY_HINT_TINT : value.hintTint.clone();
            hintIcons = compactPlain ? null : value.hintIcons.clone();
            candidates = compactPlain ? Collections.emptyList() : new ArrayList<>();
            if (!compactPlain) for (ItemStack candidate : value.candidates) candidates.add(candidate.copy());
        }

        private Cell(Cell target, NBTTagCompound details) {
            raw = target.raw;
            controllerCell = target.controllerCell;
            controllerDetails = details;
            coordinates = target.coordinates;
            descriptorTemplate = target.descriptorTemplate;
            resolvedBlocks = target.resolvedBlocks;
            actualBlock = target.actualBlock;
            targetBlock = target.targetBlock;
            index = target.index;
            x = target.x;
            y = target.y;
            z = target.z;
            dx = target.dx;
            dy = target.dy;
            dz = target.dz;
            meta = target.meta;
            wantMeta = target.wantMeta;
            chosenChoice = details.hasKey("chosenChoice", 3) ? details.getInteger("chosenChoice") : target.chosenChoice;
            hatchFacing = target.hatchFacing;
            actualFacing = target.actualFacing;
            targetFacing = target.targetFacing;
            id = target.id;
            wantId = target.wantId;
            status = target.status;
            pinScope = target.pinScope;
            role = details.hasKey("role", 8) ? details.getString("role") : target.role;
            family = target.family;
            renderKind = target.renderKind;
            operation = target.operation;
            exactIdentity = target.exactIdentity;
            prompt = target.prompt;
            structureId = target.structureId;
            sourceMod = target.sourceMod;
            sourceVersion = target.sourceVersion;
            sourceClass = target.sourceClass;
            sourceWarning = target.sourceWarning;
            networkGroup = target.networkGroup;
            specialHint = target.specialHint;
            structureIds = target.structureIds;
            anchor = target.anchor;
            iconOnly = target.iconOnly;
            excluded = target.excluded;
            missingBlock = target.missingBlock;
            sourceMissing = target.sourceMissing;
            inScope = target.inScope;
            candidateComplete = details.hasKey("candidateComplete", 1) ? details.getBoolean("candidateComplete")
                : target.candidateComplete;
            candidateBoundary = details.hasKey("candidateBoundary", 8) ? details.getString("candidateBoundary")
                : target.candidateBoundary;
            actualStack = target.actualStack;
            targetStack = target.targetStack;
            actualDisplay = target.actualDisplay;
            targetDisplay = target.targetDisplay;
            hintIndex = target.hintIndex;
            hintTint = target.hintTint;
            hintIcons = target.hintIcons;
            NBTTagList options = details.getTagList("candidates", 10);
            if (options.tagCount() == 0) candidates = Collections.emptyList();
            else {
                List<ItemStack> parsed = new ArrayList<>(options.tagCount());
                for (int i = 0; i < options.tagCount(); i++) {
                    ItemStack stack = readStack(options.getCompoundTagAt(i));
                    if (stack != null) parsed.add(stack);
                }
                candidates = Collections.unmodifiableList(parsed);
            }
        }

        private Cell(int index, HologramControllerTarget.Cell target) {
            raw = null;
            controllerCell = target;
            controllerDetails = null;
            coordinates = null;
            descriptorTemplate = null;
            resolvedBlocks = true;
            this.index = index;
            x = target.x();
            y = target.y();
            z = target.z();
            dx = target.dx();
            dy = target.dy();
            dz = target.dz();
            meta = target.meta();
            wantMeta = target.wantMeta();
            id = target.id();
            wantId = target.wantId();
            actualBlock = target.actualBlock();
            targetBlock = target.targetBlock();
            status = target.status();
            chosenChoice = -1;
            hatchFacing = -1;
            actualFacing = target.actualFacing();
            targetFacing = target.targetFacing();
            candidateComplete = false;
            candidateBoundary = "";
            pinScope = "";
            role = target.role();
            family = target.family();
            exactIdentity = "";
            renderKind = target.renderKind();
            operation = "";
            inScope = true;
            prompt = target.prompt();
            networkGroup = "";
            specialHint = null;
            structureId = "";
            structureIds = Collections.emptyList();
            sourceMod = "";
            sourceVersion = "";
            sourceClass = "";
            sourceWarning = "";
            sourceMissing = false;
            excluded = false;
            missingBlock = target.missingBlock() || !wantId.isEmpty() && !"minecraft:air".equals(wantId)
                && targetBlock == null;
            anchor = target.anchor();
            iconOnly = target.iconOnly();
            actualStack = target.actualStack();
            targetStack = target.targetStack();
            actualDisplay = target.actualDisplay();
            targetDisplay = target.targetDisplay();
            hintIndex = target.hintIndex();
            hintTint = target.hintTint() == null ? EMPTY_HINT_TINT : target.hintTint();
            if (target.hintIcons() == null) hintIcons = EMPTY_HINT_ICONS;
            else {
                hintIcons = new String[6];
                for (int i = 0; i < hintIcons.length; i++) {
                    String icon = i < target.hintIcons().length ? target.hintIcons()[i] : null;
                    hintIcons[i] = icon == null ? "" : icon;
                }
            }
            candidates = Collections.emptyList();
        }

        /** Exact within this preparation only; the token exposes neither the descriptor nor its private prototype. */
        Object descriptorIdentity() {
            return descriptorTemplate == null ? this : descriptorTemplate;
        }

        private NBTTagCompound displayValue(boolean actual) {
            NBTTagCompound owned = actual ? actualDisplay : targetDisplay;
            if (owned != null) return owned;
            if (descriptorTemplate == null) return EMPTY_DISPLAY;
            Cell prototype = descriptorTemplate.values;
            return actual ? prototype.actualDisplay : prototype.targetDisplay;
        }

        boolean hasDisplayTag(boolean actual, String key, int type) {
            return displayValue(actual).hasKey(key, type);
        }

        int displayInteger(boolean actual, String key, int fallback) {
            NBTTagCompound value = displayValue(actual);
            return value.hasKey(key) ? value.getInteger(key) : fallback;
        }

        /** Exposed local display NBT remains independently mutable. Only scalar reads use the private prototype. */
        NBTTagCompound display(boolean actual) {
            NBTTagCompound owned = actual ? actualDisplay : targetDisplay;
            if (owned == null) {
                owned = (NBTTagCompound) displayValue(actual).copy();
                if (actual) actualDisplay = owned;
                else targetDisplay = owned;
            }
            return owned;
        }

        String specialHint(String key) {
            return specialHint == null ? "" : specialHint.getString(key);
        }

        boolean localObservationMissing() {
            return controllerCell == null ? raw.getBoolean("localObservationMissing") : controllerCell.localObservationMissing();
        }

        String hintIcon(int side) {
            return hintIcons == null ? "" : hintIcons[side];
        }

        String hintIconKey() {
            return hintIcons == null ? "[, , , , , ]" : java.util.Arrays.toString(hintIcons);
        }

        private static ItemStack readStack(NBTTagCompound source) {
            // Vanilla borrows the nested "tag" compound. Mutable inventory/render stacks must not own snapshot NBT.
            ItemStack stack = ItemStack.loadItemStackFromNBT(source);
            if (stack != null && stack.hasTagCompound()) stack.setTagCompound(
                (NBTTagCompound) stack.getTagCompound()
                    .copy());
            return stack;
        }

        Block block(boolean current) {
            return resolvedBlocks ? current ? actualBlock : targetBlock : Block.getBlockFromName(current ? id : wantId);
        }

        NBTTagCompound tag() {
            NBTTagCompound result = controllerCell == null ? (NBTTagCompound) raw.copy() : controllerCell.tag();
            if (controllerDetails != null) for (Object name : controllerDetails.func_150296_c()) {
                String key = (String) name;
                if (!"index".equals(key)) result.setTag(key, controllerDetails.getTag(key).copy());
            }
            if (coordinates != null) for (Object name : coordinates.func_150296_c()) {
                String key = (String) name;
                if (HologramSnapshotPalette.coordinateKey(key)) result.setTag(
                    key,
                    coordinates.getTag(key)
                        .copy());
            }
            else if (descriptorTemplate != null) {
                // Compact local sources materialize position NBT only if an existing consumer asks for a row.
                result.setInteger("x", x);
                result.setInteger("y", y);
                result.setInteger("z", z);
                result.setInteger("dx", dx);
                result.setInteger("dy", dy);
                result.setInteger("dz", dz);
            }
            return result;
        }

        boolean belongsToStructure(String id) {
            return !id.isEmpty() && structureIds.contains(id);
        }

        boolean quantumNetworkHint() {
            return "quantumNetwork".equals(specialHint("type")) && networkGroup.matches("q[1-9][0-9]*")
                && networkGroup.equals(specialHint("group"));
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
