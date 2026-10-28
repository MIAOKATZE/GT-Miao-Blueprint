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
        preparationOwnership();
        compactPreparation();
        regionInvalidation();
        areaSelectionReceipts(state);
        localCatalogReceipts();
    }

    private static void localCatalogReceipts() {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("session", "empty-workspace");
        receipt.setString("generation", "empty-workspace");
        receipt.setBoolean("areaSelectionOnly", true);
        receipt.setBoolean("workspaceUiOnly", true);
        receipt.setBoolean("open", true);
        receipt.setLong("areaSelectionSequence", 1);
        HologramState shell = HologramState.apply(null, receipt);
        SimpleAssert.eq(
            0,
            shell.data.getTagList("presets", 10)
                .tagCount(),
            "new UI shell has no cached catalog");

        NBTTagCompound refreshed = (NBTTagCompound) receipt.copy();
        refreshed.setBoolean("open", false);
        refreshed.setLong("areaSelectionSequence", 2);
        NBTTagList labels = new NBTTagList();
        NBTTagCompound saved = new NBTTagCompound();
        saved.setString("id", "saved-before-world-reload");
        labels.appendTag(saved);
        refreshed.setTag("presets", labels);
        HologramState listed = HologramState.apply(shell, refreshed);
        SimpleAssert.eq(
            labels,
            listed.data.getTagList("presets", 10),
            "explicit catalog refresh fills an empty shell through the real lightweight merge");
        SimpleAssert.that(
            listed.cells == shell.cells && listed.parsedCells == 0 && !listed.data.getBoolean("snapshotPending"),
            "catalog labels do not request or reparse world geometry");

        receipt.setLong("areaSelectionSequence", 3);
        receipt.setString("status", "蓝图库刷新失败：fixture read error");
        HologramState failed = HologramState.apply(listed, receipt);
        SimpleAssert.eq(
            labels,
            failed.data.getTagList("presets", 10),
            "a later refresh failure without labels preserves the last known catalog");
        refreshed.setLong("areaSelectionSequence", 4);
        refreshed.setTag("presets", new NBTTagList());
        SimpleAssert.eq(
            0,
            HologramState.apply(failed, refreshed).data.getTagList("presets", 10)
                .tagCount(),
            "a successful explicitly empty catalog remains distinguishable from refresh failure");
    }

    private static void areaSelectionReceipts(HologramState full) {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("areaSelectionOnly", true);
        receipt.setString("session", full.session);
        receipt.setString("generation", full.session);
        receipt.setLong("planRevision", full.data.getLong("planRevision"));
        receipt.setLong("areaSelectionSequence", 1);
        receipt.setBoolean("areaSelecting", true);
        receipt.setBoolean("open", false);
        receipt.setIntArray("areaPoints", new int[] { 3, 64, 4 });
        HologramState selected = HologramState.apply(full, receipt);
        SimpleAssert.that(
            selected.cells == full.cells && selected.regions == full.regions
                && selected.materials == full.materials
                && selected.structureMembers == full.structureMembers,
            "point receipts retain all cell, NBT, material and spatial objects");
        SimpleAssert.eq(0, selected.parsedCells, "point receipts parse zero cells regardless of structure size");
        SimpleAssert
            .that(!selected.data.getBoolean("areaSelectionShell"), "a real baseline remains editable after points");
        NBTTagCompound uiReceipt = (NBTTagCompound) receipt.copy();
        uiReceipt.setLong("areaSelectionSequence", 2);
        uiReceipt.setBoolean("workspaceUiOnly", true);
        uiReceipt.setBoolean("workspaceHasCapture", true);
        uiReceipt.setBoolean("open", true);
        HologramState reopened = HologramState.apply(selected, uiReceipt);
        SimpleAssert.that(
            reopened.cells == full.cells && !reopened.data.getBoolean("snapshotPending"),
            "reopening a known blueprint needs no new geometry or material preparation");
        HologramState openingShell = HologramState.apply(null, uiReceipt);
        SimpleAssert.that(
            openingShell.data.getBoolean("open") && openingShell.data.getBoolean("snapshotPending"),
            "unknown blueprint opens its UI immediately with an honest pending baseline");
        uiReceipt.setBoolean("workspaceHasCapture", false);
        HologramState emptyWorkspace = HologramState.apply(null, uiReceipt);
        SimpleAssert.that(
            !emptyWorkspace.data.getBoolean("snapshotPending") && !emptyWorkspace.data.getBoolean("areaSelectionShell"),
            "an empty tool opens without scheduling unnecessary full structure metadata");
        SimpleAssert.that(
            HologramState.apply(selected, uiReceipt).data.getBoolean("snapshotPending"),
            "a geometry change cannot silently reuse a nonempty baseline as an empty workspace");
        uiReceipt.setBoolean("workspaceCapturePending", true);
        HologramState capturing = HologramState.apply(null, uiReceipt);
        SimpleAssert.that(
            capturing.data.getBoolean("snapshotPending") && capturing.data.getBoolean("areaSelectionShell"),
            "an unfinished capture with no cells yet cannot masquerade as a ready empty tool");
        HologramState pendingCapture = HologramState.apply(selected, uiReceipt);
        SimpleAssert.that(
            HologramState.apply(pendingCapture, full.fullData()).data.getBoolean("snapshotPending"),
            "an older full baseline cannot consume a newer capture opening intent or mark that capture ready");
        uiReceipt.setBoolean("workspaceCapturePending", false);
        HologramState shell = HologramState.apply(null, receipt);
        SimpleAssert.that(
            shell.cells.isEmpty() && shell.data.getBoolean("areaSelectionShell"),
            "first point receipt establishes only an explicitly noneditable selection shell");
        rejects(
            shell,
            delta(shell, shell.data.getLong("planRevision")),
            "selection shell cannot stand in for full baseline");
        NBTTagCompound staleFull = full.fullData();
        staleFull.setBoolean("areaSelecting", false);
        staleFull.setBoolean("open", true);
        HologramState late = HologramState.apply(selected, staleFull);
        SimpleAssert.that(
            late.cells.size() == full.cells.size() && late.data.getBoolean("areaSelecting")
                && !late.data.getBoolean("open")
                && late.data.getIntArray("areaPoints")[0] == 3,
            "delayed full geometry cannot undo a newer corner or reopen the UI");
        HologramState.Preparation preparation = new HologramState.Preparation(staleFull);
        while (!preparation.step(4096, Long.MAX_VALUE)) {}
        SimpleAssert.that(
            preparation.result()
                .retainAreaSelection(selected).data.getBoolean("areaSelecting"),
            "cooperative full preparation retains a newer corner receipt as well");
        receipt.setLong("areaSelectionSequence", 2);
        receipt.setLong("planRevision", full.data.getLong("planRevision") + 1);
        receipt.setBoolean("areaSelecting", false);
        receipt.setBoolean("areaImporting", true);
        HologramState importing = HologramState.apply(selected, receipt);
        SimpleAssert
            .that(importing.data.getBoolean("areaSelectionShell"), "new import revision cannot authorize old geometry");
        NBTTagCompound completed = full.fullData();
        completed.setLong("planRevision", receipt.getLong("planRevision"));
        completed.setLong("areaSelectionSequence", 2);
        HologramState restored = HologramState.apply(importing, completed);
        SimpleAssert.that(
            !restored.data.getBoolean("areaSelectionShell") && !restored.data.getBoolean("areaImporting"),
            "matching final full replaces the shell and owns geometry again");
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
        NBTTagCompound annotation = delta(renamed, renamed.data.getLong("planRevision"));
        NBTTagCompound annotationRow = new NBTTagCompound();
        annotationRow.setInteger("index", 15);
        annotationRow.setBoolean("annotationOnly", true);
        annotationRow.setString("prompt", "new author prompt");
        annotationRow.setBoolean("excluded", false);
        NBTTagList annotationRows = new NBTTagList();
        annotationRows.appendTag(annotationRow);
        annotation.setTag("cells", annotationRows);
        HologramState annotated = HologramState.apply(renamed, annotation);
        SimpleAssert.eq(
            "new author prompt",
            annotated.cells.get(15).prompt,
            "annotation patch changes only its named cell text");
        SimpleAssert.eq(7, annotated.cells.get(15).wantMeta, "annotation preserves the installed target identity");
        SimpleAssert.eq(
            renamed.cells.get(15).x,
            annotated.cells.get(15).x,
            "annotation preserves the installed cell coordinates");
        SimpleAssert.that(
            annotated.cells.get(5000) == renamed.cells.get(5000) && annotated.materials == renamed.materials,
            "annotation reuses unrelated geometry and unchanged quotes");
        SimpleAssert.that(
            !"new author prompt".equals(renamed.cells.get(15).prompt),
            "annotation patch cannot mutate its published base");
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

    private static void preparationOwnership() {
        NBTTagCompound source = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        CopyCountedRow row = new CopyCountedRow();
        row.setInteger("x", 7);
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", 123);
        row.setTag("targetDisplay", display);
        rows.appendTag(row);
        source.setTag("cells", rows);
        HologramState.Preparation task = new HologramState.Preparation(source);
        while (!task.step(1, Long.MAX_VALUE)) {}
        HologramState transferred = task.result();
        SimpleAssert.eq(0, row.copies, "preparation takes ownership without copying an already decoded row");
        NBTTagCompound exported = transferred.cells.get(0)
            .tag();
        exported.setInteger("x", 99);
        exported.getCompoundTag("targetDisplay")
            .setInteger("color", 999);
        SimpleAssert.eq(7, transferred.cells.get(0).x, "exported row edits cannot alter prepared geometry");
        SimpleAssert.eq(
            123,
            transferred.cells.get(0).targetDisplay.getInteger("color"),
            "exported native display edits cannot alter the owned snapshot");
        HologramState borrowed = new HologramState(source);
        row.setInteger("x", 88);
        display.setInteger("color", 888);
        SimpleAssert.eq(
            7,
            borrowed.cells.get(0)
                .tag()
                .getInteger("x"),
            "borrowed constructors still isolate caller rows");
        SimpleAssert.eq(
            123,
            borrowed.cells.get(0).targetDisplay.getInteger("color"),
            "borrowed constructors still isolate caller native display payloads");
    }

    private static final class CopyCountedRow extends NBTTagCompound {

        int copies;

        @Override
        public net.minecraft.nbt.NBTBase copy() {
            copies++;
            return super.copy();
        }
    }

    private static void compactPreparation() {
        NBTTagCompound full = new NBTTagCompound();
        full.setString("session", "compact-preparation");
        NBTTagList rows = new NBTTagList();
        net.minecraft.item.ItemStack item = new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setInteger("config", 3);
        item.setTagCompound(payload);
        for (int index = 0; index < 2; index++) {
            NBTTagCompound row = new NBTTagCompound();
            for (String axis : new String[] { "x", "y", "z", "dx", "dy", "dz" })
                row.setInteger(axis, axis.endsWith("x") ? 7 + index * 1024 : 0);
            row.setString("wantId", "minecraft:stone");
            row.setString("structureId", "group");
            row.setLong("nativeModelId", 7);
            row.setInteger("nativeModelPending", 1);
            row.setTag("targetStack", item.writeToNBT(new NBTTagCompound()));
            NBTTagList candidates = new NBTTagList();
            candidates.appendTag(item.writeToNBT(new NBTTagCompound()));
            row.setTag("candidates", candidates);
            rows.appendTag(row);
        }
        full.setTag("cells", rows);
        HologramState legacy = new HologramState(full);
        NBTTagCompound packed = com.miaokatze.gtmb.hologram.HologramSnapshotRowsRegression.compactForTest(full);
        HologramState.Preparation preparation = new HologramState.Preparation(packed);
        while (!preparation.step(1, Long.MAX_VALUE)) {}
        HologramState compact = preparation.result();
        SimpleAssert.eq(legacy.fullData(), compact.fullData(), "compact preparation restores every original row field");
        SimpleAssert.eq(1031, compact.cells.get(1).x, "shared descriptors preserve distant cell coordinates");
        SimpleAssert.eq(1, compact.cells.get(1).index, "shared descriptors preserve selection indices");
        SimpleAssert.eq(
            2,
            compact.structureMembers.get("group")
                .size(),
            "shared descriptors preserve memberships");
        compact.cells.get(0).targetStack.getTagCompound()
            .setInteger("config", 99);
        compact.cells.get(0).candidates.get(0)
            .getTagCompound()
            .setInteger("config", 98);
        SimpleAssert.eq(
            3,
            compact.cells.get(1).targetStack.getTagCompound()
                .getInteger("config"),
            "mutable target stacks are isolated between cells sharing a descriptor");
        SimpleAssert.eq(
            3,
            compact.cells.get(1).candidates.get(0)
                .getTagCompound()
                .getInteger("config"),
            "mutable candidate stacks are isolated between cells sharing a descriptor");
        SimpleAssert
            .eq(legacy.fullData(), compact.fullData(), "mutable stack edits cannot change retained raw palette data");
        NBTTagCompound nativeTile = new NBTTagCompound();
        nativeTile.setString("id", "native-overlay");
        try (HologramState.ModelPreparation model = new HologramState.ModelPreparation(
            compact,
            java.util.Collections.singletonList(new HologramState.ModelRow(0, 7, nativeTile, null, false)))) {
            SimpleAssert.that(model.step(1, Long.MAX_VALUE), "compact model overlay prepares within its bounded work");
            HologramState modeled = model.result(compact);
            NBTTagCompound expected = legacy.cells.get(0)
                .tag();
            NBTTagCompound display = new NBTTagCompound();
            display.setTag("nativeTile", nativeTile);
            expected.setTag("actualDisplay", display);
            expected.setTag("targetDisplay", new NBTTagCompound());
            expected.setInteger("nativeModelPending", 0);
            SimpleAssert.eq(
                expected,
                modeled.cells.get(0)
                    .tag(),
                "model overlays export complete exact compact rows");
            SimpleAssert.eq(
                legacy.cells.get(1)
                    .tag(),
                modeled.cells.get(1)
                    .tag(),
                "other compact cells retain their raw data");
            model.published();
        }
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
