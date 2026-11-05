package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.hologram.HologramSnapshotPalette;
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
        packedPreparation();
        projectionPreparation();
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
        NBTTagCompound row = new NBTTagCompound(), display = new NBTTagCompound();
        display.setString("unknownNativeField", "kept");
        row.setTag("targetDisplay", display);
        row.setString("unknownField", "kept");
        net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(net.minecraft.init.Items.stick);
        NBTTagCompound itemData = new NBTTagCompound();
        itemData.setString("unknownItemField", "kept");
        stack.setTagCompound(itemData);
        row.setTag("targetStack", stack.writeToNBT(new NBTTagCompound()));
        NBTTagList candidates = new NBTTagList();
        candidates.appendTag(stack.writeToNBT(new NBTTagCompound()));
        row.setTag("candidates", candidates);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        source.setTag("cells", rows);
        HologramState.Preparation task = new HologramState.Preparation(source);
        while (!task.step(1, Long.MAX_VALUE)) {}
        HologramState owned = task.result();
        HologramState.Cell cell = owned.cells.get(0);
        try {
            java.lang.reflect.Field raw = HologramState.Cell.class.getDeclaredField("raw");
            raw.setAccessible(true);
            SimpleAssert.that(raw.get(cell) == row, "network preparation transfers its exclusive decoded row");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        SimpleAssert.that(cell.targetDisplay == display, "owned display remains a read-only snapshot reference");
        cell.targetStack.getTagCompound()
            .setString("unknownItemField", "changed");
        cell.candidates.get(0)
            .getTagCompound()
            .setString("unknownItemField", "candidateChanged");
        SimpleAssert.eq(
            "kept",
            cell.tag()
                .getCompoundTag("targetStack")
                .getCompoundTag("tag")
                .getString("unknownItemField"),
            "mutable item stack NBT cannot change the owned row");
        SimpleAssert.eq(
            "kept",
            cell.tag()
                .getTagList("candidates", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("tag")
                .getString("unknownItemField"),
            "candidate NBT cannot change the owned row");
        NBTTagCompound exported = owned.fullData();
        exported.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("targetDisplay")
            .setString("unknownNativeField", "changed");
        SimpleAssert.eq("kept", cell.targetDisplay.getString("unknownNativeField"), "fullData exports independent NBT");
        NBTTagCompound external = (NBTTagCompound) source.copy();
        HologramState copied = new HologramState(external);
        external.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setString("unknownField", "changed");
        external.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("targetDisplay")
            .setString("unknownNativeField", "changed");
        SimpleAssert.eq(
            "kept",
            copied.cells.get(0)
                .tag()
                .getString("unknownField"),
            "public construction copies rows");
        SimpleAssert.eq(
            "kept",
            copied.cells.get(0).targetDisplay.getString("unknownNativeField"),
            "public construction copies display compounds");
    }

    private static void projectionPreparation() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "structure-hints");
        source.setBoolean("structureProjection", true);
        source.setString("editorWorkspace", "projection");
        source.setBoolean("target", true);
        source.setBoolean("supported", true);
        source.setInteger("hatchTierMax", 4);
        NBTTagCompound descriptor = new NBTTagCompound();
        descriptor.setString("id", "minecraft:glass");
        descriptor.setString("wantId", "minecraft:glass");
        descriptor.setString("role", "projection");
        descriptor.setString("status", "preview");
        descriptor.setInteger("meta", 3);
        descriptor.setInteger("wantMeta", 3);
        descriptor.setBoolean("iconOnly", true);
        descriptor.setIntArray("hintTint", new int[] { 20, 40, 60 });
        NBTTagList icons = new NBTTagList();
        icons.appendTag(new net.minecraft.nbt.NBTTagString("fixture:typed-hint"));
        descriptor.setTag("hintIcons", icons);
        descriptor.setString("family", "coil");
        descriptor.setString("exactIdentity", "native-material-identity");
        descriptor.setInteger("chosenChoice", 0);
        descriptor.setString("pinScope", "cell");
        descriptor.setBoolean("candidateComplete", true);
        NBTTagList candidates = new NBTTagList();
        candidates.appendTag(
            new net.minecraft.item.ItemStack(net.minecraft.init.Items.stick, 1, 1769).writeToNBT(new NBTTagCompound()));
        candidates.appendTag(
            new net.minecraft.item.ItemStack(net.minecraft.init.Items.stick, 1, 1770).writeToNBT(new NBTTagCompound()));
        descriptor.setTag("candidates", candidates);
        NBTTagList candidateRoles = new NBTTagList();
        candidateRoles.appendTag(new net.minecraft.nbt.NBTTagString("input"));
        candidateRoles.appendTag(new net.minecraft.nbt.NBTTagString("energy"));
        descriptor.setTag("candidateRoles", candidateRoles);
        descriptor.setIntArray("candidateTiers", new int[] { 2, 4 });
        descriptor.setIntArray("candidateAvailable", new int[] { 3, 7 });
        descriptor.setBoolean("hasTargetOverride", true);
        descriptor.setBoolean("hasFacingPin", true);
        descriptor.setTag(
            "targetStack",
            candidates.getCompoundTagAt(0)
                .copy());
        descriptor.setInteger("targetFacing", 5);
        descriptor.setIntArray("hatchFacings", new int[] { 2, 5 });
        NBTTagCompound targetDisplay = new NBTTagCompound();
        NBTTagCompound nativeTile = new NBTTagCompound();
        nativeTile.setShort("mID", (short) 1769);
        nativeTile.setByte("mFacing", (byte) 5);
        targetDisplay.setTag("nativeTile", nativeTile);
        descriptor.setTag("targetDisplay", targetDisplay);
        NBTTagCompound quote = new NBTTagCompound();
        quote.setBoolean("complete", true);
        NBTTagList materialRows = new NBTTagList();
        materialRows.appendTag(
            HologramMaterialQuote
                .itemRow(new net.minecraft.item.ItemStack(net.minecraft.init.Items.stick, 1, 1769), 32, 0));
        quote.setTag("rows", materialRows);
        source.setTag("materialQuote", quote);
        source.setBoolean("presetAvailable", true);
        source.setBoolean("presetSupported", true);
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < 32; i++) {
            NBTTagCompound row = (NBTTagCompound) descriptor.copy();
            row.setInteger("x", i - 16);
            row.setInteger("dx", i - 16);
            row.setInteger("y", 64);
            row.setInteger("dy", -2);
            row.setString("prompt", "kept annotation");
            if (i == 31) {
                row.setBoolean("anchor", true);
                row.setBoolean("iconOnly", false);
                row.setInteger("actualFacing", 4);
                NBTTagCompound display = new NBTTagCompound();
                display.setString("nativeAnchorField", "kept");
                row.setTag("actualDisplay", display);
                row.setTag(
                    "actualStack",
                    candidates.getCompoundTagAt(0)
                        .copy());
            }
            rows.appendTag(row);
        }
        source.setTag("cells", rows);
        try {
            NBTTagCompound packed = HologramSnapshotCodec.decode(HologramSnapshotCodec.compressPacked(source));
            SimpleAssert
                .that(HologramSnapshotPalette.isPacked(packed), "projection fixture exercises negotiated hints");
            for (NBTTagCompound input : new NBTTagCompound[] { (NBTTagCompound) source.copy(), packed }) {
                HologramState.Preparation task = new HologramState.Preparation(input);
                while (!task.step(3, Long.MAX_VALUE)) {}
                HologramState state = task.result();
                SimpleAssert.eq(source, state.fullData(), "projection retains opaque Blueprint NBT on export");
                HologramState.Cell hint = state.cells.get(0), anchor = state.cells.get(31);
                SimpleAssert.that(
                    hint.block(false) == net.minecraft.init.Blocks.glass && hint.block(true) == hint.block(false),
                    "typed render identity resolves the same actual and target block");
                SimpleAssert.that(
                    hint.candidates.size() == 2 && hint.candidateComplete
                        && hint.family.equals("coil")
                        && hint.exactIdentity.equals("native-material-identity")
                        && hint.chosenChoice == 0
                        && hint.pinScope.equals("cell"),
                    "structure projection keeps native material candidates, type and selected target editable");
                SimpleAssert
                    .eq(1769, hint.targetStack.getItemDamage(), "target item retains its native device identity");
                SimpleAssert.eq(
                    3,
                    new HologramBlockAccess(state).getBlockMetadata(hint.x, hint.y, hint.z),
                    "render access uses world metadata independently of target item damage");
                SimpleAssert.eq(5, hint.targetFacing, "target facing survives structure preparation");
                SimpleAssert
                    .eq(targetDisplay, hint.targetDisplay, "native target display payload survives preparation");
                SimpleAssert.eq(
                    2,
                    hint.tag()
                        .getIntArray("hatchFacings").length,
                    "legal hatch facings remain available to the editor");
                SimpleAssert.that(
                    state.data.getBoolean("presetAvailable") && state.data.getBoolean("presetSupported"),
                    "structure header keeps preset availability before construction preparation");
                HologramMaterialReview review = new HologramMaterialReview();
                review.update(state.data);
                SimpleAssert.that(
                    review.ready() && review.size() == 1 && "32".equals(review.row(0).required),
                    "structure quote shows native material quantities without construction preparation");
                SimpleAssert.eq("fixture:typed-hint", hint.hintIcons[0], "typed icon hints survive preparation");
                SimpleAssert.eq(40, hint.hintTint[1], "typed tint survives preparation");
                SimpleAssert.eq(-2, hint.dy, "hint geometry retains relative coordinates");
                SimpleAssert.that(
                    state.regions.at(-16, 64, 0) == hint && state.regions.at(15, 64, 0) == anchor,
                    "direct geometry retains negative and positive region indices");
                SimpleAssert.that(anchor.anchor && anchor.actualStack != null, "anchor keeps its native item identity");
                SimpleAssert.eq(4, anchor.actualFacing, "anchor keeps its native facing");
                SimpleAssert
                    .eq("kept", anchor.actualDisplay.getString("nativeAnchorField"), "anchor keeps display NBT");
                SimpleAssert.eq("kept annotation", hint.prompt, "projection annotations remain visible");
                projectionUi(state);
            }
            NBTTagCompound blueprint = (NBTTagCompound) source.copy();
            blueprint.removeTag("structureProjection");
            HologramState.Preparation task = new HologramState.Preparation(blueprint);
            while (!task.step(3, Long.MAX_VALUE)) {}
            HologramState.Cell ordinary = task.result().cells.get(0);
            SimpleAssert.that(
                ordinary.candidates.size() == 2 && ordinary.candidateComplete
                    && ordinary.family.equals("coil")
                    && ordinary.exactIdentity.equals("native-material-identity"),
                "the same legacy Blueprint retains full choice and planning metadata interpretation");
        } catch (java.io.IOException failure) {
            throw new AssertionError("projection hint preparation", failure);
        }
    }

    private static void projectionUi(HologramState snapshot) {
        try {
            HologramUiModules ui = new HologramUiModules(null);
            java.lang.reflect.Field state = HologramUiSession.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(ui.session, snapshot);
            ui.preview.selected(0);
            java.lang.reflect.Method available = HologramEditorShell.class
                .getDeclaredMethod("launcherAvailable", int.class);
            available.setAccessible(true);
            SimpleAssert.that(
                (Boolean) available.invoke(ui.shell, 4) && (Boolean) available.invoke(ui.shell, 8),
                "production material-source and construction launchers remain reachable in structure mode");
            HologramEditorPropertiesPane properties = new HologramEditorPropertiesPane(ui);
            java.lang.reflect.Method preset = HologramEditorPropertiesPane.class.getDeclaredMethod("canHatchPreset");
            preset.setAccessible(true);
            SimpleAssert.that(
                (Boolean) preset.invoke(properties) && ui.preview.canInventoryPin() && ui.preview.hasSelectedPin(),
                "production preset-open, candidate-choice and restore-default predicates admit structure targets");
            SimpleAssert.eq(
                java.util.Arrays.asList("全部类型", "input", "energy"),
                ui.config.hatchRoles(),
                "native role selector reads the complete server role table");
            SimpleAssert.eq(
                java.util.Arrays.asList(2, 4),
                ui.config.availableTiers(),
                "native tier selector reads server tiers before operation preparation");
            SimpleAssert.eq(
                java.util.Arrays.asList(0, 1),
                ui.config.filteredHatches(),
                "native candidate window contains the actual parsed targets");
            SimpleAssert.eq(5, ui.config.nativePresetCount(), "basic preset tiers use the server-declared maximum");
            NBTTagCompound view = ui.config.backgroundUiState();
            view.setBoolean("hatchDrawer", true);
            view.setBoolean("presetDrawer", true);
            view.setInteger("hatchRole", 2);
            view.setInteger("hatchFilterTier", 4);
            view.setInteger("presetTier", 3);
            ui.config.restoreBackgroundUiState(view);
            ui.config.normalizeNativeViewState();
            SimpleAssert.eq(
                java.util.Collections.singletonList(1),
                ui.config.filteredHatches(),
                "native role and tier filters select the matching device rather than an empty projection list");
            NBTTagCompound retained = ui.config.backgroundUiState();
            ui.config.restoreBackgroundUiState(retained);
            ui.config.normalizeNativeViewState();
            SimpleAssert.that(
                ui.config.hatchDrawer() && ui.config.presetDrawer()
                    && ui.config.nativePresetRow(3)
                        .startsWith("✓ ")
                    && ui.config.filteredHatches()
                        .equals(java.util.Collections.singletonList(1)),
                "restored structure presentation retains open editors, selected preset tier and device filters");
            NBTTagCompound demolition = snapshot.fullData();
            demolition.setInteger("mode", 2);
            state.set(ui.session, new HologramState(demolition));
            SimpleAssert.that(
                !(Boolean) preset.invoke(properties) && !ui.preview.canInventoryPin(),
                "the same production predicates still reject target edits during demolition");
            state.set(ui.session, snapshot);
            SimpleAssert.that(
                (Boolean) preset.invoke(properties) && ui.preview.canInventoryPin(),
                "returning to the structure plan restores preset and candidate editing");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("structure production UI predicates", failure);
        }
    }

    private static void packedPreparation() {
        try {
            NBTTagCompound source = new NBTTagCompound();
            source.setString("session", "packed");
            source.setString("generation", "packed");
            source.setLong("planRevision", 10);
            source.setLong("unknownRootLong", Long.MAX_VALUE);
            NBTTagCompound descriptor = new NBTTagCompound(), display = new NBTTagCompound();
            descriptor.setString("wantId", "minecraft:stone");
            descriptor.setString("status", "ready");
            descriptor.setLong("unknownLong", Long.MIN_VALUE);
            descriptor.setByteArray("unknownBytes", new byte[4096]);
            descriptor.setIntArray("hintTint", new int[] { 4, 5, 6 });
            NBTTagList icons = new NBTTagList();
            icons.appendTag(new net.minecraft.nbt.NBTTagString("kept-icon"));
            descriptor.setTag("hintIcons", icons);
            NBTTagList memberships = new NBTTagList();
            memberships.appendTag(new net.minecraft.nbt.NBTTagString("kept-structure"));
            descriptor.setTag("structureIds", memberships);
            display.setString("unknownDisplay", "kept");
            NBTTagCompound nativeTile = new NBTTagCompound();
            nativeTile.setLong("unknownNativeLong", Long.MAX_VALUE);
            display.setTag("nativeTile", nativeTile);
            descriptor.setTag("targetDisplay", display);
            NBTTagList unknownList = new NBTTagList();
            unknownList.appendTag(new net.minecraft.nbt.NBTTagLong(Long.MAX_VALUE));
            descriptor.setTag("unknownList", unknownList);
            net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(net.minecraft.init.Items.stick);
            NBTTagCompound itemTag = new NBTTagCompound();
            itemTag.setString("unknownItemField", "kept");
            stack.setTagCompound(itemTag);
            descriptor.setTag("targetStack", stack.writeToNBT(new NBTTagCompound()));
            NBTTagList candidates = new NBTTagList();
            candidates.appendTag(stack.writeToNBT(new NBTTagCompound()));
            descriptor.setTag("candidates", candidates);
            NBTTagList rows = new NBTTagList();
            for (int i = 0; i < 4; i++) {
                NBTTagCompound row = (NBTTagCompound) descriptor.copy();
                if ((i & 1) == 0) row.setByte("x", (byte) i);
                else row.setLong("x", i);
                row.setShort("y", (short) 64);
                row.setLong("z", 0);
                row.setFloat("dx", i);
                row.setInteger("dy", 0);
                // A missing coordinate is distinct from an explicit zero tag during full export.
                if (i != 0) row.setByte("dz", (byte) 0);
                rows.appendTag(row);
            }
            source.setTag("cells", rows);
            byte[] encoded = HologramSnapshotCodec.compressPacked(source);
            NBTTagCompound decoded;
            try (HologramSnapshotInbox<NBTTagCompound> inbox = new HologramSnapshotInbox<>(
                1,
                encoded.length,
                HologramSnapshotCodec::decode)) {
                SimpleAssert.that(inbox.offer(encoded, 31), "packed snapshot enters the actual bounded client inbox");
                HologramSnapshotInbox.Result<NBTTagCompound> received = inbox.awaitNext();
                SimpleAssert.eq(null, received.failure, "packed snapshot decodes on the pure worker");
                SimpleAssert.eq(31L, received.transportSequence, "opaque handoff retains its transport identity");
                decoded = received.value;
            }
            SimpleAssert
                .that(HologramSnapshotPalette.isPacked(decoded), "real repeated snapshot uses packed transport");
            decoded = (NBTTagCompound) decoded.copy();
            SimpleAssert.that(HologramSnapshotPalette.isPacked(decoded), "opaque NBT copy retains packed provenance");
            descriptorTemplates(source, decoded);
            HologramState publicState = new HologramState(decoded);
            NBTTagCompound external = (NBTTagCompound) decoded.copy();
            HologramState isolated = new HologramState(external);
            HologramSnapshotPalette.descriptor(
                external,
                external.getTagList("cells", 10)
                    .getCompoundTagAt(0))
                .setString("unknownLong", "changedType");
            external.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .setInteger("x", 99);
            SimpleAssert
                .eq(source, isolated.fullData(), "public packed construction isolates dictionary and coordinates");
            HologramState.Preparation task = new HologramState.Preparation(decoded);
            while (!task.step(1, Long.MAX_VALUE)) {}
            HologramState packed = task.result();
            SimpleAssert.eq(source, publicState.fullData(), "public packed construction preserves all NBT types");
            SimpleAssert.eq(
                new HologramState(source).fullData(),
                packed.fullData(),
                "packed preparation equals legacy including unknown fields, lists and coordinate types");
            SimpleAssert.that(
                !packed.data.hasKey(HologramSnapshotPalette.VERSION_KEY)
                    && !packed.data.hasKey(HologramSnapshotPalette.PALETTE_KEY),
                "runtime metadata drops wire internals");
            java.lang.reflect.Field raw = HologramState.Cell.class.getDeclaredField("raw");
            java.lang.reflect.Field coordinates = HologramState.Cell.class.getDeclaredField("coordinates");
            raw.setAccessible(true);
            coordinates.setAccessible(true);
            SimpleAssert.that(
                raw.get(packed.cells.get(0)) == raw.get(packed.cells.get(1)),
                "prepared cells retain the same exact descriptor instead of expanded row copies");
            SimpleAssert.that(
                raw.get(packed.cells.get(0)) == HologramSnapshotPalette.descriptor(
                    decoded,
                    decoded.getTagList("cells", 10)
                        .getCompoundTagAt(0)),
                "preparation transfers its decoded dictionary");
            SimpleAssert.that(
                coordinates.get(packed.cells.get(0)) == decoded.getTagList("cells", 10)
                    .getCompoundTagAt(0),
                "preparation transfers the separate coordinate row");
            SimpleAssert.that(
                !((NBTTagCompound) raw.get(packed.cells.get(0))).hasKey("x"),
                "runtime descriptor remains compact after preparation");
            SimpleAssert.that(
                packed.cells.get(0).targetDisplay == packed.cells.get(1).targetDisplay,
                "read-only native display data stays shared until detached hydration");
            packed.cells.get(0).targetStack.getTagCompound()
                .setString("unknownItemField", "changed");
            packed.cells.get(0).hintTint[0] = 99;
            SimpleAssert.eq(
                "kept",
                packed.cells.get(1).targetStack.getTagCompound()
                    .getString("unknownItemField"),
                "cells never share mutable ItemStack NBT");
            SimpleAssert.eq(4, packed.cells.get(1).hintTint[0], "cells never share mutable tint arrays");
            SimpleAssert.eq(source, packed.fullData(), "mutable render data cannot change descriptor export");
            NBTTagCompound patch = delta(packed, 11), changed = packed.cells.get(0)
                .tag();
            changed.setInteger("index", 0);
            changed.setString("status", "missing");
            NBTTagList replacements = new NBTTagList();
            replacements.appendTag(changed);
            patch.setTag("cells", replacements);
            HologramState next = HologramState.apply(packed, patch);
            changed.getCompoundTag("targetDisplay")
                .setString("unknownDisplay", "changed");
            SimpleAssert.eq(
                "kept",
                next.cells.get(0).targetDisplay.getString("unknownDisplay"),
                "incoming patch keeps external mutation isolated");
            SimpleAssert
                .that(next.cells.get(1) == packed.cells.get(1), "small patch retains neighboring compact cells");
            SimpleAssert.eq(source, packed.fullData(), "patch cannot mutate its descriptor baseline");
            NBTTagCompound annotation = delta(next, 12), annotated = new NBTTagCompound();
            annotated.setInteger("index", 0);
            annotated.setBoolean("annotationOnly", true);
            annotated.setString("prompt", "note");
            NBTTagList annotations = new NBTTagList();
            annotations.appendTag(annotated);
            annotation.setTag("cells", annotations);
            HologramState notes = HologramState.apply(next, annotation);
            SimpleAssert.eq("note", notes.cells.get(0).prompt, "annotation patch materializes only the changed row");
            SimpleAssert.eq("", next.cells.get(0).prompt, "annotation preserves its prior publication");
            SimpleAssert.eq(
                Long.MIN_VALUE,
                notes.cells.get(0)
                    .tag()
                    .getLong("unknownLong"),
                "annotation preserves unknown descriptor fields");
            NBTTagCompound legacy = (NBTTagCompound) source.copy();
            legacy.setInteger(HologramSnapshotPalette.VERSION_KEY, HologramSnapshotPalette.VERSION);
            legacy.setTag(HologramSnapshotPalette.PALETTE_KEY, unknownList.copy());
            NBTTagCompound plain = HologramSnapshotCodec.decode(HologramSnapshotCodec.compressPacked(legacy));
            SimpleAssert.that(!HologramSnapshotPalette.isPacked(plain), "legacy extension collisions stay plain NBT");
            SimpleAssert.eq(
                legacy,
                new HologramState(plain).fullData(),
                "legacy metadata with reserved-looking names is not removed or reinterpreted");
        } catch (java.io.IOException | ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Counts the real field/ItemStack read path, rather than estimating savings from the resulting values. */
    private static void descriptorTemplates(NBTTagCompound source, NBTTagCompound decoded)
        throws java.io.IOException, ReflectiveOperationException {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger absentStacks = new java.util.concurrent.atomic.AtomicInteger();
        NBTTagCompound legacySource = (NBTTagCompound) source.copy();
        NBTTagList rows = legacySource.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++)
            rows.func_150304_a(i, new CountingDescriptor(rows.getCompoundTagAt(i), reads, loads, absentStacks, true));
        HologramState legacy = new HologramState(legacySource);
        SimpleAssert.eq(rows.tagCount(), reads.get(), "legacy reads descriptor fields once per cell");
        SimpleAssert.eq(2 * rows.tagCount(), loads.get(), "legacy loads target and candidate stacks per cell");
        SimpleAssert.eq(0, absentStacks.get(), "absent actual stacks need no empty compound or load");
        reads.set(0);
        loads.set(0);
        NBTTagCompound compact = (NBTTagCompound) decoded.copy();
        NBTTagList palette = compact.getTagList(HologramSnapshotPalette.PALETTE_KEY, 10);
        for (int i = 0; i < palette.tagCount(); i++) palette
            .func_150304_a(i, new CountingDescriptor(palette.getCompoundTagAt(i), reads, loads, absentStacks, true));
        boolean metrics = HologramRuntimeMetrics.isEnabled();
        HologramRuntimeMetrics.setEnabled(true);
        try {
            NBTTagCompound before = HologramRuntimeMetrics.snapshot()
                .getCompoundTag("queues");
            HologramState.Preparation task = new HologramState.Preparation(compact);
            while (!task.step(1, Long.MAX_VALUE)) {}
            HologramState prepared = task.result();
            task.result();
            NBTTagCompound after = HologramRuntimeMetrics.snapshot()
                .getCompoundTag("queues");
            SimpleAssert.eq(
                (long) palette.tagCount(),
                after.getLong("clientDescriptorTemplates") - before.getLong("clientDescriptorTemplates"),
                "completed preparation reports U templates once");
            SimpleAssert.eq(
                (long) rows.tagCount(),
                after.getLong("clientDescriptorCells") - before.getLong("clientDescriptorCells"),
                "completed preparation reports N cells once");
            SimpleAssert.eq(palette.tagCount(), reads.get(), "packed descriptor fields are read U times instead of N");
            SimpleAssert
                .eq(2 * palette.tagCount(), loads.get(), "packed stack NBT parses U times; each cell copies privately");
            SimpleAssert.eq(
                legacy.fullData(),
                prepared.fullData(),
                "templates retain exact legacy export and coordinate types");
            for (int i = 0; i < prepared.cells.size(); i++)
                parsedCellEquals(legacy.cells.get(i), prepared.cells.get(i));
            HologramState.Cell first = prepared.cells.get(0), second = prepared.cells.get(1);
            SimpleAssert.that(
                first.descriptorIdentity() == second.descriptorIdentity() && first.descriptorIdentity() != first,
                "packed cells expose only the shared private template token");
            SimpleAssert.that(first.structureIds == second.structureIds, "immutable memberships share one parsed list");
            first.candidates.get(0)
                .getTagCompound()
                .setString("unknownItemField", "changed");
            first.hintIcons[0] = "changed";
            SimpleAssert.eq(
                "kept",
                second.candidates.get(0)
                    .getTagCompound()
                    .getString("unknownItemField"),
                "candidate stack NBT remains independent between templated cells");
            SimpleAssert.eq("kept-icon", second.hintIcons[0], "hint icon arrays remain per-cell mutable copies");
            SimpleAssert.eq(source, prepared.fullData(), "mutable candidates and arrays cannot alter template export");
            HologramState.Preparation another = new HologramState.Preparation((NBTTagCompound) decoded.copy());
            while (!another.step(1, Long.MAX_VALUE)) {}
            SimpleAssert.that(
                another.result().cells.get(0)
                    .descriptorIdentity() != second.descriptorIdentity(),
                "a fresh preparation never inherits an earlier snapshot template");
            HologramState publicState = new HologramState(decoded);
            SimpleAssert.that(
                publicState.cells.get(0)
                    .descriptorIdentity() == publicState.cells.get(0),
                "public packed construction retains the original independent parsing contract");
            SimpleAssert.that(
                legacy.cells.get(0)
                    .descriptorIdentity() == legacy.cells.get(0),
                "legacy cells use no shared descriptor token");
            java.lang.reflect.Field cache = HologramState.Preparation.class.getDeclaredField("descriptors");
            cache.setAccessible(true);
            SimpleAssert.eq(
                null,
                cache.get(new HologramState.Preparation(source)),
                "legacy preparation allocates no template map");
            NBTTagCompound distinct = (NBTTagCompound) source.copy();
            distinct.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .setString("futureUnknown", "different");
            HologramState.Preparation changed = new HologramState.Preparation(
                HologramSnapshotCodec.decode(HologramSnapshotCodec.compressPacked(distinct)));
            while (!changed.step(1, Long.MAX_VALUE)) {}
            HologramState changedState = changed.result();
            SimpleAssert.that(
                changedState.cells.get(0)
                    .descriptorIdentity()
                    != changedState.cells.get(1)
                        .descriptorIdentity(),
                "different unknown descriptor data cannot share a template token");
            SimpleAssert.eq(distinct, changedState.fullData(), "unknown descriptor differences remain lossless");
        } finally {
            HologramRuntimeMetrics.setEnabled(metrics);
        }
    }

    private static void parsedCellEquals(HologramState.Cell expected, HologramState.Cell actual)
        throws IllegalAccessException {
        for (java.lang.reflect.Field field : HologramState.Cell.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isPrivate(field.getModifiers())) continue;
            field.setAccessible(true);
            Object a = field.get(expected), b = field.get(actual);
            if (field.getType() == net.minecraft.item.ItemStack.class) SimpleAssert.that(
                net.minecraft.item.ItemStack
                    .areItemStacksEqual((net.minecraft.item.ItemStack) a, (net.minecraft.item.ItemStack) b),
                "templated stack matches legacy: " + field.getName());
            else if (a instanceof int[]) SimpleAssert.that(
                java.util.Arrays.equals((int[]) a, (int[]) b),
                "templated array matches legacy: " + field.getName());
            else if (a instanceof String[])
                SimpleAssert.that(java.util.Arrays.equals((String[]) a, (String[]) b), "templated icons match legacy");
            else if ("candidates".equals(field.getName())) {
                SimpleAssert.eq(expected.candidates.size(), actual.candidates.size(), "candidate counts match legacy");
                for (int i = 0; i < expected.candidates.size(); i++) SimpleAssert.that(
                    net.minecraft.item.ItemStack
                        .areItemStacksEqual(expected.candidates.get(i), actual.candidates.get(i)),
                    "templated candidate matches legacy");
            } else SimpleAssert.eq(a, b, "templated field matches legacy: " + field.getName());
        }
    }

    private static final class CountingDescriptor extends NBTTagCompound {

        private final java.util.concurrent.atomic.AtomicInteger reads, loads, absentStacks;

        CountingDescriptor(NBTTagCompound source, java.util.concurrent.atomic.AtomicInteger reads,
            java.util.concurrent.atomic.AtomicInteger loads, java.util.concurrent.atomic.AtomicInteger absentStacks,
            boolean descriptor) {
            this.reads = reads;
            this.loads = loads;
            this.absentStacks = absentStacks;
            for (Object name : source.func_150296_c()) {
                String key = (String) name;
                setTag(key, source.getTag(key));
            }
            if (!descriptor) return;
            for (String key : new String[] { "actualStack", "targetStack" }) if (source.hasKey(key, 10))
                setTag(key, new CountingDescriptor(source.getCompoundTag(key), reads, loads, absentStacks, false));
            if (source.hasKey("candidates", 9)) {
                NBTTagList previous = source.getTagList("candidates", 10), candidates = new NBTTagList();
                for (int i = 0; i < previous.tagCount(); i++) candidates
                    .appendTag(new CountingDescriptor(previous.getCompoundTagAt(i), reads, loads, absentStacks, false));
                setTag("candidates", candidates);
            }
        }

        @Override
        public String getString(String key) {
            if ("wantId".equals(key)) reads.incrementAndGet();
            return super.getString(key);
        }

        @Override
        public short getShort(String key) {
            if ("id".equals(key)) loads.incrementAndGet();
            return super.getShort(key);
        }

        @Override
        public NBTTagCompound getCompoundTag(String key) {
            if ("actualStack".equals(key) && !hasKey(key, 10)) absentStacks.incrementAndGet();
            return super.getCompoundTag(key);
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
