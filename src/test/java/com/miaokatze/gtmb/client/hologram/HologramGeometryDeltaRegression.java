package com.miaokatze.gtmb.client.hologram;

import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Topology publication must preserve distant geometry and invalidate both ends, including diagonal neighbors. */
public final class HologramGeometryDeltaRegression {

    private HologramGeometryDeltaRegression() {}

    public static void run() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "geometry");
        source.setString("generation", "geometry");
        source.setLong("planRevision", 7);
        source.setBoolean("virtual", true);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(cell(15, 15, 15, "moved"));
        rows.appendTag(cell(128, 2, 2, "retained"));
        rows.appendTag(cell(-129, 2, 2, "tail"));
        source.setTag("cells", rows);
        HologramState base = new HologramState(source);
        Object distant = base.regions.regions.get(new HologramRegionCache.Key(8, 0, 0));
        NBTTagCompound move = patch(base, 3);
        NBTTagCompound moved = cell(48, 16, 16, "moved");
        moved.setInteger("index", 0);
        rows = new NBTTagList();
        rows.appendTag(moved);
        move.setTag("cells", rows);
        move.setInteger("selected", 0);
        move.setIntArray("selectedIndices", new int[] { 0 });
        HologramState next = HologramState.apply(base, move);
        SimpleAssert.eq(1, next.parsedCells, "move parses exactly its changed row");
        SimpleAssert.that(next.cells.get(1) == base.cells.get(1), "unaffected cells retain identity");
        SimpleAssert.that(
            next.regions.regions.get(new HologramRegionCache.Key(8, 0, 0)) == distant,
            "move keeps unrelated region geometry");
        SimpleAssert.eq(null, next.regions.at(15, 15, 15), "old coordinate is removed");
        SimpleAssert.eq(0, next.regions.at(48, 16, 16).index, "new coordinate retains selected index");
        Set<HologramRegionCache.Key> dirty = HologramRegionCache.affected(next);
        SimpleAssert.that(
            dirty.contains(new HologramRegionCache.Key(0, 0, 0))
                && dirty.contains(new HologramRegionCache.Key(1, 1, 1)),
            "start corner includes diagonal halo");
        SimpleAssert.that(
            dirty.contains(new HologramRegionCache.Key(2, 0, 0))
                && dirty.contains(new HologramRegionCache.Key(3, 1, 1)),
            "destination includes its diagonal halo");
        SimpleAssert.that(!dirty.contains(new HologramRegionCache.Key(8, 0, 0)), "distant mesh is not dirty");
        rendererRegionReuse(base, next);
        HologramRenderer.lastSnapshotDirtyRegions = -1;
        HologramRenderer.snapshotChanged(base, next);
        SimpleAssert.eq(
            dirty.size(),
            HologramRenderer.lastSnapshotDirtyRegions,
            "renderer accepts topology through its local invalidation path");
        metadataPublication(base, next, dirty);

        NBTTagCompound deletion = patch(next, 2);
        NBTTagCompound tail = next.cells.get(2)
            .tag();
        tail.setInteger("index", 0);
        rows = new NBTTagList();
        rows.appendTag(tail);
        deletion.setTag("cells", rows);
        HologramState removed = HologramState.apply(next, deletion);
        SimpleAssert.eq(2, removed.cells.size(), "deletion truncates the old tail");
        SimpleAssert.eq(1, removed.parsedCells, "only the tail filling the hole is decoded");
        SimpleAssert.eq(0, removed.regions.at(-129, 2, 2).index, "tail index is remapped atomically");
        SimpleAssert.eq(null, removed.regions.at(48, 16, 16), "deleted location disappears");
        SimpleAssert.eq(null, removed.structureMembers.get("moved"), "deleted structure membership is retired");
        SimpleAssert.eq(
            Integer.valueOf(0),
            removed.structureMembers.get("tail")
                .get(0),
            "tail membership uses new index");
        SimpleAssert.that(removed.cells.get(1) == base.cells.get(1), "deletion keeps unrelated cells");

        NBTTagCompound collision = patch(base, 3);
        NBTTagCompound duplicate = cell(128, 2, 2, "bad");
        duplicate.setInteger("index", 0);
        rows = new NBTTagList();
        rows.appendTag(duplicate);
        collision.setTag("cells", rows);
        reject(base, collision, "changed cell cannot cover an untouched coordinate");
        SimpleAssert.eq(1, base.regions.at(128, 2, 2).index, "rejected patch leaves its baseline intact");
        NBTTagCompound missing = patch(base, 4);
        reject(base, missing, "appended geometry must have an explicit row");
        NBTTagCompound stale = patch(base, 2);
        stale.setInteger("baseCellCount", 2);
        reject(base, stale, "count mismatch cannot reinterpret old indices");

        NBTTagCompound truncate = patch(removed, 1);
        rows = new NBTTagList();
        NBTTagCompound keep = removed.cells.get(1)
            .tag();
        keep.setInteger("index", 0);
        rows.appendTag(keep);
        truncate.setTag("cells", rows);
        HologramState single = HologramState.apply(removed, truncate);
        SimpleAssert.eq(128, single.regions.bounds()[0], "shrinking negative extent recomputes bounds");
        HologramState empty = HologramState.apply(single, patch(single, 0));
        SimpleAssert.eq(0, empty.cells.size(), "all geometry may be deleted from an editable draft");
        SimpleAssert.eq(0, empty.regions.regions.size(), "empty regions are removed");
        SimpleAssert.eq(0, empty.minY, "empty draft has a finite layer range");
        SimpleAssert.eq(0, empty.maxY, "empty draft has a finite upper layer");
    }

    @SuppressWarnings("unchecked")
    private static void rendererRegionReuse(HologramState base, HologramState next) {
        try {
            java.lang.reflect.Field cacheField = HologramRenderer.class.getDeclaredField("previewRegions"),
                visibleField = HologramRenderer.class.getDeclaredField("visibleState"),
                preparationField = HologramRenderer.class.getDeclaredField("previewPreparation");
            cacheField.setAccessible(true);
            visibleField.setAccessible(true);
            preparationField.setAccessible(true);
            java.util.Map<HologramRegionCache.Key, Object> cache = (java.util.Map<HologramRegionCache.Key, Object>) cacheField
                .get(null);
            java.util.Map<HologramRegionCache.Key, Object> saved = new java.util.LinkedHashMap<>(cache);
            Object visible = visibleField.get(null), preparation = preparationField.get(null);
            Class<?> regionType = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
            java.lang.reflect.Constructor<?> constructor = regionType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object retained = constructor.newInstance(), changed = constructor.newInstance();
            java.lang.reflect.Field complete = regionType.getDeclaredField("complete"),
                prepared = regionType.getDeclaredField("prepared");
            complete.setAccessible(true);
            prepared.setAccessible(true);
            complete.setBoolean(retained, true);
            prepared.setInt(retained, 1);
            HologramRegionCache.Key distant = new HologramRegionCache.Key(8, 0, 0);
            try {
                cache.clear();
                cache.put(distant, retained);
                cache.put(new HologramRegionCache.Key(0, 0, 0), changed);
                visibleField.set(null, base);
                HologramRenderer.snapshotChanged(base, next);
                SimpleAssert.that(
                    cache.get(distant) == retained,
                    "actual renderer invalidation retains a completed distant region and its prepared geometry");
                SimpleAssert
                    .eq(1, prepared.getInt(retained), "a local patch does not restart the distant native preparation");
                SimpleAssert.that(
                    !cache.containsKey(new HologramRegionCache.Key(0, 0, 0)),
                    "the changed endpoint is retired before the new render cursor runs");
                Object cursor = preparationField.get(null);
                java.lang.reflect.Field state = cursor.getClass()
                    .getDeclaredField("state"),
                    boundary = cursor.getClass()
                        .getDeclaredField("boundary");
                state.setAccessible(true);
                boundary.setAccessible(true);
                SimpleAssert.that(state.get(cursor) == next, "a patch cursor owns the exact new snapshot identity");
                SimpleAssert
                    .eq(null, boundary.get(cursor), "a local patch does not rescan the entire occupancy boundary");
                java.lang.reflect.Field completePresentation = cursor.getClass()
                    .getDeclaredField("completeGeometryPresented");
                completePresentation.setAccessible(true);
                SimpleAssert.that(
                    !completePresentation.getBoolean(cursor),
                    "a dirty patch cannot inherit the prior revision's camera-only complete presentation stamp");
            } finally {
                cache.clear();
                cache.putAll(saved);
                visibleField.set(null, visible);
                preparationField.set(null, preparation);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("progressive renderer delta ownership", failure);
        }
    }

    private static void metadataPublication(HologramState base, HologramState publication,
        Set<HologramRegionCache.Key> dirty) {
        NBTTagCompound changes = new NBTTagCompound();
        changes.setString("status", "decorated");
        HologramState decorated = publication.withMetadata(changes);
        SimpleAssert.that(
            !decorated.topologyChanged && decorated.changedIndices.length == 0,
            "an independent metadata-only event does not replay old topology");
        HologramState restored = decorated.withPublication(publication);
        SimpleAssert.that(
            restored.follows(base),
            "installation restores the original wire baseline through metadata decorators");
        SimpleAssert.that(
            restored.topologyChanged && restored.parsedCells == 1,
            "installation preserves topology and changed-row provenance");
        SimpleAssert
            .eq(dirty, HologramRegionCache.affected(restored), "decorated publication keeps both endpoint halos");
        SimpleAssert
            .eq("decorated", restored.data.getString("status"), "decorated metadata survives publication restore");
        SimpleAssert
            .eq(0, restored.data.getIntArray("selectedIndices")[0], "authoritative selection survives decoration");
        SimpleAssert.that(
            restored.cells == publication.cells && restored.regions == publication.regions,
            "publication restore reparses no cells or spatial regions");
        HologramRenderer.lastSnapshotDirtyRegions = -1;
        HologramRenderer.snapshotChanged(base, restored);
        SimpleAssert.eq(
            dirty.size(),
            HologramRenderer.lastSnapshotDirtyRegions,
            "decorated topology still reaches local renderer invalidation");
        try {
            base.withPublication(publication);
            throw new AssertionError("different geometry cannot claim another publication's changes");
        } catch (IllegalArgumentException expected) {}

        HologramJobVisuals.bind(null);
        NBTTagCompound terminal = new NBTTagCompound();
        terminal.setBoolean("jobDelta", true);
        terminal.setString("session", decorated.session);
        terminal.setLong("planRevision", decorated.data.getLong("planRevision"));
        terminal.setLong("placementReviewNonce", decorated.data.getLong("placementReviewNonce"));
        terminal.setLong("batchSeq", 1);
        terminal.setLong("sequence", 10);
        terminal.setInteger("job", 4);
        terminal.setString("status", "terminal decoration");
        HologramJobVisuals.accept(decorated, terminal);
        HologramState stopped = HologramJobVisuals.metadata(decorated);
        SimpleAssert.that(
            stopped != decorated && stopped.parsedCells == 0,
            "real JobVisuals metadata applies its separate terminal patch");
        HologramState installed = stopped.withPublication(publication);
        SimpleAssert.that(
            installed.follows(base) && installed.topologyChanged,
            "terminal apply decorators preserve the incoming topology at installation");
        SimpleAssert.eq("terminal decoration", installed.data.getString("status"), "terminal fields remain decorated");
        SimpleAssert
            .eq(dirty, HologramRegionCache.affected(installed), "terminal decoration retains old and new halos");
        HologramJobVisuals.bind(null);
    }

    private static NBTTagCompound cell(int x, int y, int z, String member) {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        cell.setInteger("dx", x);
        cell.setInteger("y", y);
        cell.setInteger("dy", y);
        cell.setInteger("z", z);
        cell.setInteger("dz", z);
        cell.setString("structureId", member);
        return cell;
    }

    private static NBTTagCompound patch(HologramState base, int count) {
        NBTTagCompound patch = new NBTTagCompound();
        patch.setBoolean("partial", true);
        patch.setBoolean("topology", true);
        patch.setString("session", base.session);
        patch.setString("generation", base.data.getString("generation"));
        patch.setLong("baseRevision", base.data.getLong("planRevision"));
        patch.setLong("planRevision", base.data.getLong("planRevision") + 1);
        patch.setInteger("baseCellCount", base.cells.size());
        patch.setInteger("cellCount", count);
        return patch;
    }

    private static void reject(HologramState base, NBTTagCompound patch, String message) {
        try {
            HologramState.apply(base, patch);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {}
    }
}
