package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Full cell indexing and visibility at the remote end of a structure. */
public final class HologramLargeStateRegression {

    private HologramLargeStateRegression() {}

    public static void run() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "large");
        source.setString("generation", "large");
        source.setLong("planRevision", 10);
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 6000; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", i);
            cell.setInteger("y", 64);
            cell.setInteger("z", 0);
            cells.appendTag(cell);
        }
        source.setTag("cells", cells);
        HologramState state = new HologramState(source);
        SimpleAssert.eq(6000, state.cells.size(), "large snapshot keeps cells beyond the old 4096 cutoff");
        SimpleAssert.eq(5999, state.cells.get(5999).index, "editing retains complete server index order");
        SimpleAssert.that(state.distanceSquared(5999.5, 64.5, .5) == 0, "remote structure end remains visible");
        SimpleAssert.that(state.distanceSquared(6100, 64.5, .5) > 4096, "far outside structure stays culled");
        incremental(state);
        preparation(state);
        regionInvalidation();
    }

    private static void incremental(HologramState state) {
        NBTTagCompound patch = delta(state, 11);
        NBTTagCompound row = state.cells.get(15)
            .tag();
        row.setInteger("index", 15);
        row.setInteger("wantMeta", 7);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        patch.setTag("cells", rows);
        HologramState next = HologramState.apply(state, patch);
        SimpleAssert.eq(1, next.parsedCells, "one-cell edit parses exactly one cell among 6000");
        SimpleAssert.eq(6000, next.cells.size(), "delta keeps the complete editing index");
        SimpleAssert.that(next.cells.get(5000) == state.cells.get(5000), "untouched cells retain object identity");
        SimpleAssert.that(next.cells.get(15) != state.cells.get(15), "changed cell gets a new detached value");
        SimpleAssert.eq(7, next.cells.get(15).wantMeta, "partial update installs the authoritative cell");
        SimpleAssert.eq(0, state.cells.get(15).wantMeta, "copy-on-write update cannot mutate its base");
        HologramRegionCache.Key remote = HologramRegionCache.key(state.cells.get(5000));
        SimpleAssert.that(
            next.regions.regions.get(remote) == state.regions.regions.get(remote),
            "remote region geometry keeps its immutable input list");
        SimpleAssert.that(next.regions.at(15, 64, 0) == next.cells.get(15), "spatial queries see the replacement cell");
        SimpleAssert.eq(
            7,
            new HologramBlockAccess(next).getBlockMetadata(15, 64, 0),
            "native access observes changed metadata through the shared spatial index");
        SimpleAssert.that(
            next.structureMembers == state.structureMembers,
            "ordinary cell edits retain the complete module membership index");
        SimpleAssert.that(
            HologramRegionCache.affected(next)
                .size() <= 8,
            "single cell dirties only regions intersecting its one-cell neighbor halo");
        SimpleAssert
            .that(!next.data.hasKey("cells"), "metadata updates cannot accidentally deep-copy the full cells tree");
        SimpleAssert.eq(
            6000,
            next.fullData()
                .getTagList("cells", 10)
                .tagCount(),
            "explicit diagnostic exports still include every cell");
        NBTTagCompound exported = next.cells.get(15)
            .tag();
        exported.setInteger("wantMeta", 99);
        SimpleAssert.eq(
            7,
            next.cells.get(15)
                .tag()
                .getInteger("wantMeta"),
            "cell editor receives a detached NBT value");

        NBTTagCompound metadata = delta(next, 11);
        metadata.setString("draftName", "renamed");
        HologramState renamed = HologramState.apply(next, metadata);
        SimpleAssert.eq(0, renamed.parsedCells, "metadata-only acknowledgement parses zero cells");
        SimpleAssert.that(
            renamed.cells == next.cells && renamed.regions == next.regions,
            "metadata-only acknowledgement reuses cell pages and every region");
        SimpleAssert.eq("renamed", renamed.data.getString("draftName"), "lightweight metadata still advances");
        rejects(renamed, patch, "repeated stale delta cannot apply on a different revision");
        NBTTagCompound mismatch = delta(renamed, 12);
        mismatch.setString("generation", "other");
        rejects(renamed, mismatch, "generation mismatch requires full recovery");
        mismatch = delta(renamed, 12);
        mismatch.setInteger("cellCount", 1);
        rejects(renamed, mismatch, "index topology mismatch requires full recovery");
        mismatch = delta(renamed, 12);
        row = renamed.cells.get(15)
            .tag();
        row.setInteger("index", 15);
        row.setInteger("x", 16);
        rows = new NBTTagList();
        rows.appendTag(row);
        mismatch.setTag("cells", rows);
        rejects(renamed, mismatch, "coordinate movement cannot masquerade as a stable-index delta");

        HologramSelection selection = new HologramSelection();
        selection.click(15, false);
        selection.retain(renamed.cells.size());
        SimpleAssert.that(selection.contains(15), "local selection survives metadata and regional edits");

        NBTTagCompound metadataSource = new NBTTagCompound(), processing = new NBTTagCompound();
        processing.setBoolean("complete", true);
        processing.setTag("relations", new NBTTagList());
        NBTTagCompound policy = new NBTTagCompound();
        policy.setTag("cellOverrides", new NBTTagList());
        NBTTagList confirmedStages = new NBTTagList();
        confirmedStages.appendTag(new net.minecraft.nbt.NBTTagString("first"));
        confirmedStages.appendTag(new net.minecraft.nbt.NBTTagString("second"));
        policy.setTag("confirmedStages", confirmedStages);
        processing.setTag("policy", policy);
        metadataSource.setTag("processingPolicy", policy);
        metadataSource.setTag("processing", processing);
        HologramState processed = renamed.withMetadata(metadataSource);
        NBTTagCompound policyPatch = delta(processed, 12), processingPatch = new NBTTagCompound();
        processingPatch.setBoolean("complete", false);
        policyPatch.setTag("processingPatch", processingPatch);
        NBTTagList confirmations = new NBTTagList();
        confirmations.appendTag(new net.minecraft.nbt.NBTTagString("first"));
        policyPatch.setTag("processingConfirmedStages", confirmations);
        HologramState invalidated = HologramState.apply(processed, policyPatch);
        SimpleAssert.that(!invalidated.data.hasKey("processingPatch"), "wire patches do not leak into public metadata");
        SimpleAssert.that(
            processed.data.getCompoundTag("processing")
                .getBoolean("complete")
                && !invalidated.data.getCompoundTag("processing")
                    .getBoolean("complete"),
            "bounded processing policy replaces only requested nested values");
        SimpleAssert.that(
            processed.data.getCompoundTag("processing")
                .getTag("relations")
                == invalidated.data.getCompoundTag("processing")
                    .getTag("relations"),
            "processing patch shares the unchanged relation index instead of copying it");
        SimpleAssert
            .that(!invalidated.data.hasKey("processingConfirmedStages"), "confirmation wire patches remain internal");
        for (boolean nested : new boolean[] { true, false }) {
            NBTTagCompound originalPolicy = nested ? processed.data.getCompoundTag("processing")
                .getCompoundTag("policy") : processed.data.getCompoundTag("processingPolicy");
            NBTTagCompound updatedPolicy = nested ? invalidated.data.getCompoundTag("processing")
                .getCompoundTag("policy") : invalidated.data.getCompoundTag("processingPolicy");
            SimpleAssert.eq(
                2,
                originalPolicy.getTagList("confirmedStages", 8)
                    .tagCount(),
                "confirmation patch cannot mutate its policy base");
            SimpleAssert.eq(
                1,
                updatedPolicy.getTagList("confirmedStages", 8)
                    .tagCount(),
                "nested and top-level confirmations update together");
            SimpleAssert.that(
                originalPolicy.getTag("cellOverrides") == updatedPolicy.getTag("cellOverrides"),
                "confirmation patch shares every unchanged per-cell override list");
        }
    }

    private static NBTTagCompound delta(HologramState state, long revision) {
        NBTTagCompound patch = new NBTTagCompound();
        patch.setBoolean("partial", true);
        patch.setString("session", state.session);
        patch.setString("generation", state.data.getString("generation"));
        patch.setLong("baseRevision", state.data.getLong("planRevision"));
        patch.setLong("planRevision", revision);
        patch.setInteger("cellCount", state.cells.size());
        return patch;
    }

    private static void regionInvalidation() {
        java.util.Set<HologramRegionCache.Key> interior = affectedAt(5, 5, 5);
        SimpleAssert.eq(1, interior.size(), "interior edit invalidates exactly its own region");
        SimpleAssert.that(
            interior.contains(new HologramRegionCache.Key(0, 0, 0)),
            "interior region is retained in the dirty set");
        java.util.Set<HologramRegionCache.Key> boundary = affectedAt(15, 5, 5);
        SimpleAssert.eq(2, boundary.size(), "single-axis boundary edit invalidates exactly two regions");
        SimpleAssert.that(
            boundary.contains(new HologramRegionCache.Key(1, 0, 0)),
            "boundary region includes its adjacent region");
        java.util.Set<HologramRegionCache.Key> corner = affectedAt(15, 15, 15);
        SimpleAssert.eq(8, corner.size(), "corner neighbor halo intersects eight regions, not 27");
        SimpleAssert.that(
            corner.contains(new HologramRegionCache.Key(1, 1, 1)),
            "corner edit invalidates the diagonal region used by connected texture reads");
        SimpleAssert
            .that(!corner.contains(new HologramRegionCache.Key(2, 2, 2)), "distant regions retain their geometry");
        java.util.Set<HologramRegionCache.Key> negative = affectedAt(-16, -16, -16);
        SimpleAssert.eq(8, negative.size(), "negative-coordinate corner obeys the same eight-region bound");
        SimpleAssert.that(
            negative.contains(new HologramRegionCache.Key(-2, -2, -2)),
            "negative diagonal region is invalidated");
    }

    private static java.util.Set<HologramRegionCache.Key> affectedAt(int x, int y, int z) {
        NBTTagCompound source = new NBTTagCompound(), cell = new NBTTagCompound();
        source.setString("session", "region-invalidation");
        source.setString("generation", "region-invalidation");
        source.setLong("planRevision", 0);
        cell.setInteger("x", x);
        cell.setInteger("y", y);
        cell.setInteger("z", z);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(cell);
        source.setTag("cells", rows);
        HologramState base = new HologramState(source);
        NBTTagCompound patch = delta(base, 1), replacement = base.cells.get(0)
            .tag();
        replacement.setInteger("index", 0);
        replacement.setInteger("wantMeta", 1);
        rows = new NBTTagList();
        rows.appendTag(replacement);
        patch.setTag("cells", rows);
        return HologramRegionCache.affected(HologramState.apply(base, patch));
    }

    private static void preparation(HologramState source) {
        HologramState.Preparation task = new HologramState.Preparation(source.fullData());
        java.util.Set<String> phases = new java.util.HashSet<>();
        int steps = 0;
        boolean done;
        do {
            phases.add(task.phase());
            done = task.step(13, Long.MAX_VALUE);
            SimpleAssert
                .that(task.lastStepWork <= 13, "first full snapshot obeys the work cap while parsing and indexing");
            SimpleAssert.that(task.completed() <= task.total(), "preparation progress never exceeds actual phase work");
            if (++steps > 10000) throw new AssertionError("Snapshot preparation did not finish");
        } while (!done);
        HologramState result = task.result();
        SimpleAssert.that(steps > 400 && phases.contains("snapshot.regions"), "6000 cells publish over bounded steps");
        SimpleAssert
            .eq(source.fullData(), result.fullData(), "bounded first-full preparation preserves the entire snapshot");
        SimpleAssert
            .that(result.regions.at(5999, 64, 0) == result.cells.get(5999), "bounded indexing retains remote cells");
        HologramState.Preparation timed = new HologramState.Preparation(source.fullData());
        timed.step(4096, 1);
        SimpleAssert.eq(1, timed.lastStepWork, "expired soft time budget yields after the current cell operation");
        final boolean[] rejected = { false };
        Thread other = new Thread(() -> {
            try {
                timed.step(1, 1);
            } catch (IllegalStateException expected) {
                rejected[0] = true;
            }
        });
        other.start();
        try {
            other.join();
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
        SimpleAssert.that(rejected[0], "registry preparation refuses a different thread");
    }

    private static void rejects(HologramState state, NBTTagCompound patch, String message) {
        boolean rejected = false;
        try {
            HologramState.apply(state, patch);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, message);
    }
}
