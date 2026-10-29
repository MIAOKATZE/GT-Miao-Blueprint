package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Full-source scheduling contracts only: no native vertex, texture, GL, or elapsed-time acceptance claim. */
public final class HologramAuthorRegionFairnessRegression {

    private HologramAuthorRegionFairnessRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorRegionFairnessRegression.class, args)) return;
        run();
        System.out.println("HologramAuthorRegionFairnessRegression PASS");
    }

    public static void run() throws Exception {
        allSourceRegionsReceiveHints();
        allSourceRegionsProduceVisibleCheapOutput();
        finiteRegionSlices();
        finalCheapPublish();
        modelPublicationStreamMustNotStarveUnchangedDetail();
        repeatedNativeWakeRepairsCompletedRegionOnce();
        modelReorderingRetainsExactVisibleCells();
        boundaryAppearanceRepairsOnlyAdjacentShellCell();
        distinctNativeWakesPreserveSameRegionProjection();
        dirtyProjectionPrefixCannotStarveFarRegion();
        sourcePublicationDoesNotCountAsModelReadiness();
        sourceEditsRetirePendingRepairsAndTailSlots();
        publicationsPreserveOtherCursors();
        everyPartialTaskCounts();
        linearSparseRegionScheduling();
    }

    private static void allSourceRegionsReceiveHints() throws Exception {
        try (Fixture fixture = new Fixture(41, 1024, false)) {
            // A clipped camera must not shrink the source denominator or monopolize its cheap queue.
            field(HologramRenderer.class, "projectedFrame")
                .set(null, new HologramEditorCameraFrame(0, 0, 100, 100, 0, 0, 1, 0, 0, 0, 16));
            for (int frame = 0; frame < 41; frame++) fixture.step(3);
            SimpleAssert.eq(
                41,
                fixture.regions()
                    .size(),
                "41 source regions receive cheap work within 41 bounded frames");
            SimpleAssert
                .eq(0, fixture.count("detailedCompleted"), "no native pass precedes full-source cheap coverage");
            for (Object task : fixture.pending()
                .values())
                SimpleAssert.eq(4, integer(task, "cell"), "every region retains its own exact partial cheap cursor");
            SimpleAssert
                .eq(false, fixture.call("hintsReady"), "visited or cached regions are not finished cheap passes");
            // Hidden-layer fixture advances production cursors without native or texture callbacks.
            fixture.drain();
            SimpleAssert.eq(41 * 1024, fixture.count("detailedCompleted"), "all source detail cursors finish");
            SimpleAssert.eq(2 * 41 * 1024, fixture.count("completed"), "each cell is accounted once in each pass");
            SimpleAssert.eq(
                41,
                fixture.regions()
                    .size(),
                "complete output retains the entire source region set");
        }
    }

    private static void allSourceRegionsProduceVisibleCheapOutput() throws Exception {
        try (Fixture fixture = new Fixture(41, 1024, false)) {
            int total = 41 * 1024, slice = 128;
            for (int frame = 0; frame < total / slice; frame++) {
                AtomicLong clock = new AtomicLong();
                // Admit exactly 128 cheap cells, including the last frame, without crossing into native detail.
                fixture.advance.invoke(
                    fixture.preparation,
                    Integer.MAX_VALUE,
                    0,
                    false,
                    (long) slice - 1,
                    (LongSupplier) clock::getAndIncrement);
                SimpleAssert
                    .eq(0, fixture.count("detailedCompleted"), "visible cheap slices never admit native detail");
                boolean published = (Boolean) fixture.call("publishProgress");
                if (frame == total / slice - 2) SimpleAssert.eq(
                    32768,
                    fixture.count("published"),
                    "last visible cheap slice remains below the next geometric milestone");
                if (frame == total / slice - 1)
                    SimpleAssert.eq(true, published, "full visible cheap output forces the final publication");
            }
            SimpleAssert
                .eq(total, fixture.count("published"), "all 41984 visible cheap cells reach the publication boundary");
            SimpleAssert.eq(
                41,
                fixture.pending()
                    .size(),
                "every native region remains pending after visible cheap preparation");
            SimpleAssert.eq(
                41,
                fixture.regions()
                    .size(),
                "output covers the full source-region denominator");
            for (HologramRegionCache.Key key : fixture.projection.view().regions.regions.keySet()) {
                Object region = fixture.regions()
                    .get(key);
                SimpleAssert.that(region != null, "each source region owns visible cheap output");
                SimpleAssert.eq(
                    1024,
                    ((Collection<?>) field(region.getClass(), "visible").get(region)).size(),
                    "visible preparation retains every source cell in this region");
                Collection<?> previewCells = (Collection<?>) field(region.getClass(), "cells").get(region);
                SimpleAssert.eq(
                    1024,
                    previewCells.size(),
                    "cheap PreviewCell output covers every cell, not just a visited cursor");
                SimpleAssert.eq(
                    true,
                    field(region.getClass(), "hintsComplete").getBoolean(region),
                    "every region finishes its cheap output");
                SimpleAssert.eq(
                    false,
                    field(region.getClass(), "complete").getBoolean(region),
                    "cheap output does not claim detailed completion");
                for (Object cell : previewCells) SimpleAssert.eq(
                    false,
                    field(cell.getClass(), "detailed").getBoolean(cell),
                    "visible hints remain explicitly non-native");
            }
            SimpleAssert.that(
                ((Set<?>) field(HologramAuthorProjection.class, "queued").get(fixture.projection)).isEmpty(),
                "cheap visible output does not request native model decoding");
        }
    }

    private static void finiteRegionSlices() throws Exception {
        try (Fixture fixture = new Fixture(2, 4096, false)) {
            int slice = field(HologramRenderer.class, "PREVIEW_REGION_SLICE").getInt(null);
            fixture.step(slice + 1);
            Object[] tasks = fixture.pending()
                .values()
                .toArray();
            SimpleAssert.eq(
                2,
                fixture.regions()
                    .size(),
                "a long frame still gives the second region a finite turn");
            SimpleAssert.eq(slice, integer(tasks[0], "cell"), "first region yields at the bounded region slice");
            SimpleAssert.eq(2, integer(tasks[1], "cell"), "next region receives the remaining deadline allowance");
            SimpleAssert.eq(0, fixture.count("detailedCompleted"), "slice rotation never promotes unfinished hints");
        }
    }

    private static void finalCheapPublish() throws Exception {
        try (Fixture fixture = new Fixture(3, 32, false)) {
            for (int cell = 0; cell < 96; cell++) {
                fixture.step(0);
                boolean published = (Boolean) fixture.call("publishProgress");
                if (cell == 94)
                    SimpleAssert.eq(64, fixture.count("published"), "geometric publication avoids every-cell rebuilds");
                if (cell == 95) SimpleAssert
                    .eq(true, published, "last cheap cell forces publication below the next doubling milestone");
            }
            SimpleAssert.eq(96, fixture.count("published"), "full cheap source count is published");
            SimpleAssert
                .eq(0, fixture.count("detailedCompleted"), "cheap publication is separate from native progress");
            SimpleAssert.eq(
                3,
                fixture.pending()
                    .size(),
                "cheap completion leaves every native pass pending");
            SimpleAssert
                .eq(false, fixture.call("publishProgress"), "unchanged cheap milestone is not published repeatedly");
            SimpleAssert.eq(
                false,
                HologramRenderer.previewMeshReady(fixture.projection.view()),
                "cheap completion is not native readiness");
            fixture.drain();
            SimpleAssert.eq(true, fixture.call("publishProgress"), "final detailed pass also forces publication");
        }
    }

    /**
     * Exercise real author decode failure -> model-only publication -> renderer invalidation -> scheduler.
     * The detail layer is deliberately hidden to isolate scheduling from native/GL timing; the cheap pass
     * produces visible production PreviewCells. No task, queue, progress counter, or update result is mocked.
     */
    private static void modelPublicationStreamMustNotStarveUnchangedDetail() throws Exception {
        try (Fixture fixture = new Fixture(5, 64, true)) {
            for (int cell = 0; cell < 320; cell++) {
                AtomicLong clock = new AtomicLong();
                fixture.advance.invoke(
                    fixture.preparation,
                    Integer.MAX_VALUE,
                    0,
                    false,
                    0L,
                    (LongSupplier) clock::getAndIncrement);
            }
            SimpleAssert.eq(true, fixture.call("hintsReady"), "all source hints exist before native replies");
            fixture.step(3);
            HologramRegionCache.Key stableKey = new HologramRegionCache.Key(0, 0, 0);
            Object stable = fixture.pending()
                .get(stableKey);
            SimpleAssert.eq(4, integer(stable, "cell"), "unchanged detail starts with a real partial cursor");
            for (int index = 0; index < 64; index++) {
                HologramState before = fixture.projection.view();
                fixture.projection.request(before, before.cells.get(4 * 64 + index));
                SimpleAssert.eq(true, fixture.projection.advance(() -> 0), "one real model result publishes");
                HologramState after = fixture.projection.view();
                SimpleAssert.that(after != before && after.modelOnly, "the stream consists of genuine model deltas");
                SimpleAssert.that(
                    field(fixture.type, "state").get(fixture.preparation) == after,
                    "production publication advances the preparation owner without recreating it");
                SimpleAssert.that(
                    fixture.pending()
                        .get(stableKey) == stable,
                    "distant publications do not replace the unchanged partial task");
                fixture.step(0);
            }
            SimpleAssert.that(
                integer(stable, "cell") > 4,
                "unchanged native detail must advance during model publications, not only after replies stop");
            fixture.drain();
            SimpleAssert.eq(640, fixture.count("completed"), "finite publications converge to exact two-pass counts");
            SimpleAssert.eq(320, fixture.count("detailedCompleted"), "all source detail completes after the stream");
        }
    }

    private static void publicationsPreserveOtherCursors() throws Exception {
        for (boolean refining : new boolean[] { false, true }) try (Fixture fixture = new Fixture(5, 64, true)) {
            if (refining) for (int cell = 0; cell < 320; cell++) fixture.step(0);
            fixture.step(3);
            HologramRegionCache.Key stableKey = new HologramRegionCache.Key(0, 0, 0);
            Object stable = fixture.pending()
                .get(stableKey);
            int previous = integer(stable, "cell");
            // Distinct invalid payloads publish real terminal model results in the far region. Their finite stream
            // must neither replace unrelated tasks nor erase their partial work. No fixture decodes a real asset.
            for (int index = 0; index < 64; index++) {
                HologramState before = fixture.projection.view();
                fixture.projection.request(before, before.cells.get(4 * 64 + index));
                fixture.projection.advance(() -> 0);
                SimpleAssert
                    .that(fixture.projection.view() != before, "each explicit model attempt publishes a new snapshot");
                SimpleAssert.that(
                    fixture.pending()
                        .get(stableKey) == stable,
                    "far model publications preserve unrelated task identity");
                SimpleAssert.eq(
                    previous,
                    integer(stable, "cell"),
                    "publication alone cannot rewind the unrelated cheap or detail cursor");
                fixture.step(0);
                int current = integer(stable, "cell");
                SimpleAssert.that(
                    current >= previous,
                    "unaffected cheap or detail cursor is monotonic during dirty publications");
                previous = current;
            }
            if (refining) {
                SimpleAssert.that(previous > 4, "unchanged detail advances while distant model publications continue");
                SimpleAssert.that(
                    fixture.count("detailedCompleted") > 4,
                    "appearance repairs do not globally preempt already started detail work");
            } else SimpleAssert
                .that(previous > 4, "unchanged regions make cheap progress throughout the publication stream");
            fixture.drain();
            SimpleAssert
                .eq(640, fixture.count("completed"), "finite model changes converge to exactly two full source passes");
            SimpleAssert
                .eq(320, fixture.count("detailedCompleted"), "all detail cursors complete after finite invalidations");
            for (Object region : fixture.regions()
                .values())
                SimpleAssert.eq(
                    true,
                    field(region.getClass(), "complete").getBoolean(region),
                    "every source region has final detail output");
        }
    }

    private static void repeatedNativeWakeRepairsCompletedRegionOnce() throws Exception {
        try (Fixture fixture = new Fixture(2, 8, true)) {
            fixture.drain();
            fixture.call("publishProgress");
            HologramState state = fixture.projection.view();
            Set<HologramState.Cell> changed = java.util.Collections.singleton(state.cells.get(0));
            for (int event = 0; event < 32; event++) HologramRenderer.nativePreparationCellsChanged(state, changed);
            SimpleAssert.eq(false, fixture.call("hintsReady"), "completed-region hole is not ready cheap coverage");
            SimpleAssert.eq(
                false,
                field(fixture.type, "allHintsPresented").getBoolean(fixture.preparation),
                "invalidated hints cannot remain marked presented");
            SimpleAssert.eq(15, fixture.count("detailedCompleted"), "duplicate wake events invalidate one cell once");
            Object task = fixture.pending()
                .get(new HologramRegionCache.Key(0, 0, 0));
            SimpleAssert.eq(8, integer(task, "cell"), "completed region retains its exhausted base cursor");
            SimpleAssert.eq(
                1,
                ((Set<?>) field(task.getClass(), "revisit").get(task)).size(),
                "repair queue is bounded by affected cells, not readiness event count");
            SimpleAssert.eq(
                false,
                field(
                    field(task.getClass(), "detail").get(task)
                        .getClass(),
                    "complete").getBoolean(field(task.getClass(), "detail").get(task)),
                "pending repair cannot claim complete");
            fixture.step(0);
            SimpleAssert
                .eq(16, fixture.count("detailedCompleted"), "one hidden-cell repair restores exact detail count");
            SimpleAssert.that(
                fixture.pending()
                    .isEmpty(),
                "one-cell wake never schedules a full region rebuild");
            SimpleAssert.eq(
                false,
                field(fixture.type, "allHintsPresented").getBoolean(fixture.preparation),
                "repaired hints wait for their actual publication boundary");
            SimpleAssert.eq(true, fixture.call("publishProgress"), "repaired region publishes anew");
            SimpleAssert.eq(
                true,
                field(fixture.type, "allHintsPresented").getBoolean(fixture.preparation),
                "only republished repaired hints become presented");
        }
    }

    private static void modelReorderingRetainsExactVisibleCells() throws Exception {
        try (Fixture fixture = new Fixture(1, 8, true)) {
            for (int cell = 0; cell < 11; cell++) fixture.visibleStep(0);
            HologramState before = fixture.projection.view();
            fixture.projection.request(before, before.cells.get(6));
            fixture.projection.advance(() -> 0);
            HologramState after = fixture.projection.view();
            SimpleAssert.that(after.modelOnly, "real readiness publication retains source topology");
            for (int step = 0; step < 32 && !fixture.pending()
                .isEmpty(); step++) fixture.visibleStep(0);
            SimpleAssert.that(
                fixture.pending()
                    .isEmpty(),
                "base and repair cursors converge after region list reorder");
            SimpleAssert.eq(8, fixture.count("detailedCompleted"), "revisited cells count only once");
            Object region = fixture.regions()
                .get(new HologramRegionCache.Key(0, 0, 0));
            Set<Integer> seen = new java.util.HashSet<>();
            for (Object value : (Collection<?>) field(region.getClass(), "visible").get(region)) {
                HologramState.Cell cell = (HologramState.Cell) value;
                SimpleAssert.that(seen.add(cell.index), "repair output contains no duplicate stable slots");
                SimpleAssert
                    .that(cell == after.cells.get(cell.index), "every repaired output uses the current immutable cell");
            }
            SimpleAssert.eq(8, seen.size(), "reordering skips no visible source cell");
            SimpleAssert.eq(4, integer(region, "unresolved"), "old unresolved outputs are replaced, not accumulated");
        }
    }

    private static void boundaryAppearanceRepairsOnlyAdjacentShellCell() throws Exception {
        try (Fixture fixture = new Fixture(2, 16, true, 16)) {
            HologramState cold = fixture.projection.view();
            for (int index = 0; index < 32; index++)
                if (index != 15) fixture.projection.request(cold, cold.cells.get(index));
            fixture.projection.advance(() -> 0);
            fixture.visibleDrain();
            SimpleAssert.eq(32, fixture.count("detailedCompleted"), "visible fixture reaches every detail cell");
            HologramState before = fixture.projection.view();
            fixture.projection.request(before, before.cells.get(15));
            fixture.projection.advance(() -> 0);
            Object neighbor = fixture.pending()
                .get(new HologramRegionCache.Key(1, 0, 0));
            SimpleAssert.that(neighbor != null, "boundary appearance invalidates the adjacent shell consumer");
            Set<?> repairs = (Set<?>) field(neighbor.getClass(), "revisit").get(neighbor);
            SimpleAssert.eq(
                java.util.Collections.singleton(16),
                repairs,
                "halo shell repair does not reset every neighboring-region detail cell");
            Object region = field(neighbor.getClass(), "detail").get(neighbor);
            SimpleAssert.eq(
                false,
                field(region.getClass(), "hintsComplete").getBoolean(region),
                "removed stale shell geometry blocks picking until its repair is presented");
            for (Object value : (Collection<?>) field(region.getClass(), "visible").get(region)) SimpleAssert.that(
                ((HologramState.Cell) value).index != 16,
                "the old exposed-face consumer is absent until refreshed");
            fixture.visibleDrain();
            SimpleAssert.eq(64, fixture.count("completed"), "neighbor and native repairs restore exact progress");
            SimpleAssert.eq(
                0,
                HologramRenderer.previewMeshTotal(fixture.projection.view())
                    - HologramRenderer.previewMeshCompleted(fixture.projection.view()),
                "terminal fallback cells do not retain obsolete unresolved counts");
        }
    }

    @SuppressWarnings("unchecked")
    private static void dirtyProjectionPrefixCannotStarveFarRegion() throws Exception {
        try (Fixture fixture = new Fixture(2, 8, true)) {
            for (int cell = 0; cell < 16; cell++) fixture.visibleStep(0);
            HologramState state = fixture.projection.view();
            java.util.List<HologramTextures.Sample>[] sides = new java.util.List[6], covers = new java.util.List[6];
            for (int side = 0; side < 6; side++) sides[side] = covers[side] = java.util.Collections.emptyList();
            HologramTextures.Material material = new HologramTextures.Material(
                sides,
                covers,
                HologramGeometry.cube(),
                false,
                false,
                false,
                false);
            Map<net.minecraft.block.Block, Map<Integer, HologramTextures.Material>> materials = (Map<net.minecraft.block.Block, Map<Integer, HologramTextures.Material>>) field(
                HologramLightPreview.class,
                "MATERIALS").get(null);
            Map<Integer, HologramTextures.Material> byMeta = new java.util.HashMap<>();
            byMeta.put(0, material);
            materials.put(net.minecraft.init.Blocks.stone, byMeta);
            field(HologramRenderer.class, "projectedFrame")
                .set(null, new HologramEditorCameraFrame(0, 0, 500, 500, 0, 0, 1, 16, 0, 0, 64));
            Method project = HologramRenderer.class.getDeclaredMethod(
                "projectPreviewRegions",
                java.util.List.class,
                HologramState.class,
                String.class,
                int.class,
                boolean.class,
                boolean.class,
                long.class,
                LongSupplier.class);
            project.setAccessible(true);
            java.util.List<?> ordered = new java.util.ArrayList<>(
                fixture.regions()
                    .values());
            for (int frame = 0; frame < 16; frame++) {
                // Same-state actual readiness wakes invalidate the prefix through the production repair path.
                HologramRenderer
                    .nativePreparationCellsChanged(state, java.util.Collections.singleton(state.cells.get(0)));
                // A repaired first-visible cell can reverse the presentation depth order each frame.
                if (frame > 0) java.util.Collections.reverse(ordered);
                AtomicLong clock = new AtomicLong();
                project.invoke(
                    null,
                    ordered,
                    state,
                    "fixed-camera",
                    63,
                    false,
                    true,
                    0L,
                    (LongSupplier) clock::getAndIncrement);
            }
            Object far = fixture.regions()
                .get(new HologramRegionCache.Key(2, 0, 0));
            SimpleAssert.eq(
                8,
                integer(far, "projectedCells"),
                "a repeatedly invalidated prefix cannot consume every projection admission");
            SimpleAssert.eq(
                "fixed-camera",
                field(far.getClass(), "projectedKey").get(far),
                "the far region completes real CPU face projection while prefix repairs continue");
        }
    }

    @SuppressWarnings("unchecked")
    private static void distinctNativeWakesPreserveSameRegionProjection() throws Exception {
        try (Fixture fixture = new Fixture(1, 8, true)) {
            HologramState cold = fixture.projection.view();
            for (HologramState.Cell cell : cold.cells) fixture.projection.request(cold, cell);
            fixture.projection.advance(() -> 0);
            fixture.visibleDrain();
            HologramState state = fixture.projection.view();
            java.util.List<HologramTextures.Sample>[] sides = new java.util.List[6], covers = new java.util.List[6];
            for (int side = 0; side < 6; side++) sides[side] = covers[side] = java.util.Collections.emptyList();
            HologramTextures.Material material = new HologramTextures.Material(
                sides,
                covers,
                HologramGeometry.cube(),
                false,
                false,
                false,
                false);
            Map<net.minecraft.block.Block, Map<Integer, HologramTextures.Material>> materials = (Map<net.minecraft.block.Block, Map<Integer, HologramTextures.Material>>) field(
                HologramLightPreview.class,
                "MATERIALS").get(null);
            Map<Integer, HologramTextures.Material> byMeta = new java.util.HashMap<>();
            byMeta.put(0, material);
            materials.put(net.minecraft.init.Blocks.stone, byMeta);
            field(HologramRenderer.class, "projectedFrame")
                .set(null, new HologramEditorCameraFrame(0, 0, 500, 500, 0, 0, 1, 4, 0, 0, 16));
            Method project = HologramRenderer.class.getDeclaredMethod(
                "projectPreviewRegions",
                java.util.List.class,
                HologramState.class,
                String.class,
                int.class,
                boolean.class,
                boolean.class,
                long.class,
                LongSupplier.class);
            project.setAccessible(true);
            java.util.List<?> ordered = new java.util.ArrayList<>(
                fixture.regions()
                    .values());
            Object region = ordered.get(0);
            project.invoke(null, ordered, state, "same-camera", 63, false, true, 0L, (LongSupplier) () -> 0);
            Object firstOutline = ((java.util.List<?>) field(region.getClass(), "outlines").get(region)).get(0);
            // Controlled notifications enter the actual readiness -> repair -> projection path. The native bridge
            // has its own exact-consumer regression; this CPU fixture makes no native-renderer/GL claim.
            for (int index = 2; index < 8; index++) {
                HologramRenderer
                    .nativePreparationCellsChanged(state, java.util.Collections.singleton(state.cells.get(index)));
                for (Object outline : (Collection<?>) field(region.getClass(), "outlines").get(region))
                    SimpleAssert.that(
                        ((HologramState.Cell) field(outline.getClass(), "cell").get(outline)).index != index,
                        "an affected slot's obsolete projected output is removed before repair");
                fixture.visibleStep(0);
                fixture.call("publishProgress");
                project.invoke(null, ordered, state, "same-camera", 63, false, true, 0L, (LongSupplier) () -> 0);
                Collection<?> outlines = (Collection<?>) field(region.getClass(), "outlines").get(region);
                SimpleAssert.that(
                    outlines.contains(firstOutline),
                    "same-camera repair preserves unchanged already-projected output identity");
                boolean secondProjected = false;
                for (Object outline : outlines)
                    secondProjected |= ((HologramState.Cell) field(outline.getClass(), "cell").get(outline)).index == 1;
                SimpleAssert.that(
                    secondProjected,
                    "distinct later native consumers cannot repeatedly reproject the unchanged prefix");
            }
            project
                .invoke(null, ordered, state, "same-camera", 63, false, true, Long.MAX_VALUE, (LongSupplier) () -> 0);
            SimpleAssert
                .eq(8, integer(region, "projectedCells"), "finite distinct repairs finish exact projection count");
            Object oldAffectedOutline = null;
            for (Object outline : (Collection<?>) field(region.getClass(), "outlines").get(region))
                if (((HologramState.Cell) field(outline.getClass(), "cell").get(outline)).index == 2)
                    oldAffectedOutline = outline;
            SimpleAssert.that(oldAffectedOutline != null, "the next wake targets genuinely projected output");
            HologramRenderer.nativePreparationCellsChanged(state, java.util.Collections.singleton(state.cells.get(2)));
            Collection<?> retainedOutlines = (Collection<?>) field(region.getClass(), "outlines").get(region);
            SimpleAssert.that(
                !retainedOutlines.contains(oldAffectedOutline),
                "a fully projected affected outline is removed before any repair work");
            SimpleAssert.that(
                retainedOutlines.contains(firstOutline),
                "removing a projected neighbor still retains unchanged outline identity");
            SimpleAssert.eq(7, integer(region, "projectedCells"), "only the affected projected slot loses readiness");
            fixture.visibleStep(0);
            project.invoke(null, ordered, state, "same-camera", 63, false, true, 0L, (LongSupplier) () -> 0);
            SimpleAssert.eq(8, integer(region, "projectedCells"), "one repair projection restores exact readiness");
            SimpleAssert.that(
                !((Collection<?>) field(region.getClass(), "outlines").get(region)).contains(oldAffectedOutline),
                "repaired output cannot resurrect the obsolete outline object");
            Set<Integer> projectedSlots = new java.util.HashSet<>();
            for (Object outline : (Collection<?>) field(region.getClass(), "outlines").get(region)) {
                HologramState.Cell cell = (HologramState.Cell) field(outline.getClass(), "cell").get(outline);
                SimpleAssert.that(projectedSlots.add(cell.index), "repair projection never duplicates an outline slot");
                SimpleAssert
                    .that(cell == state.cells.get(cell.index), "repaired projection uses current cell identity");
            }
            SimpleAssert.eq(8, projectedSlots.size(), "all repaired projection slots remain represented");
            project.invoke(null, ordered, state, "new-camera", 63, false, true, 0L, (LongSupplier) () -> 0);
            SimpleAssert
                .eq(1, integer(region, "projectedCells"), "a different camera still restarts projection honestly");
            SimpleAssert.that(
                !((Collection<?>) field(region.getClass(), "outlines").get(region)).contains(firstOutline),
                "changed-camera output cannot reuse old projected coordinates");
        }
    }

    private static void sourceEditsRetirePendingRepairsAndTailSlots() throws Exception {
        // Isolate move, delete, and move-then-delete without draining in between the two source transactions.
        for (int scenario = 0; scenario < 3; scenario++) try (Fixture fixture = new Fixture(3, 8, true)) {
            com.miaokatze.gtmb.blueprint.editor.AuthorDocument document = (com.miaokatze.gtmb.blueprint.editor.AuthorDocument) field(
                HologramAuthorProjection.class,
                "document").get(fixture.projection);
            com.miaokatze.gtmb.blueprint.editor.AuthorEditor editor = new com.miaokatze.gtmb.blueprint.editor.AuthorEditor(
                document,
                8);
            fixture.visibleDrain();
            HologramState before = fixture.projection.view();
            long firstId = fixture.projection.cellId(0), tailId = fixture.projection.cellId(23);
            Set<HologramState.Cell> wakes = new java.util.HashSet<>();
            wakes.add(before.cells.get(0));
            wakes.add(before.cells.get(16));
            HologramRenderer.nativePreparationCellsChanged(before, wakes);
            SimpleAssert.eq(
                2,
                fixture.pending()
                    .size(),
                "two source regions have real pending appearance repairs");
            editor.select(java.util.Collections.singleton(firstId));
            if (scenario != 1) {
                fixture.projection.update(editor.move(48, 0, 0));
                SimpleAssert.eq(48, fixture.projection.view().cells.get(0).x, "source move relocates the stable slot");
            }
            if (scenario != 0) {
                fixture.projection.update(editor.delete());
                SimpleAssert
                    .eq(tailId, fixture.projection.cellId(0), "delete swaps the cross-region tail into slot zero");
                SimpleAssert.eq(-1L, fixture.projection.cellId(23), "old tail slot is retired, not queued for repair");
            }
            fixture.visibleDrain();
            HologramState after = fixture.projection.view();
            int total = scenario == 0 ? 24 : 23;
            SimpleAssert
                .eq(2 * total, fixture.count("completed"), "source reset repairs restore exact two-pass accounting");
            SimpleAssert.eq(total, fixture.count("detailedCompleted"), "source reset counts each current slot once");
            Set<Integer> seen = new java.util.HashSet<>();
            for (Object region : fixture.regions()
                .values()) {
                SimpleAssert.eq(
                    true,
                    field(region.getClass(), "complete").getBoolean(region),
                    "all surviving source regions complete after edits during repair");
                for (Object value : (Collection<?>) field(region.getClass(), "visible").get(region)) {
                    HologramState.Cell cell = (HologramState.Cell) value;
                    SimpleAssert.that(
                        cell.index < total && seen.add(cell.index),
                        "tail swap creates no stale or duplicate slot");
                    SimpleAssert
                        .that(cell == after.cells.get(cell.index), "source edit output references the current cell");
                }
            }
            SimpleAssert.eq(total, seen.size(), "move/delete pending repairs skip no current visible cell");
            SimpleAssert
                .eq(null, after.regions.at(0, 0, 0), "old source coordinate has no retained cell after the edit");
        }
    }

    private static void sourcePublicationDoesNotCountAsModelReadiness() throws Exception {
        try (Fixture fixture = new Fixture(1, 8, true)) {
            com.miaokatze.gtmb.blueprint.editor.AuthorDocument document = (com.miaokatze.gtmb.blueprint.editor.AuthorDocument) field(
                HologramAuthorProjection.class,
                "document").get(fixture.projection);
            com.miaokatze.gtmb.blueprint.editor.AuthorEditor editor = new com.miaokatze.gtmb.blueprint.editor.AuthorEditor(
                document,
                8);
            HologramState cold = fixture.projection.view();
            fixture.projection.request(cold, cold.cells.get(0));
            fixture.projection.advance(() -> 0);
            long publications = HologramRenderer.previewWorkInspection()
                .getLong("modelPublications");
            SimpleAssert.eq(1L, publications, "actual native readiness is counted as one model publication");
            JsonObject patch = new JsonObject();
            patch.addProperty("metadata", 1);
            fixture.projection.update(editor.patchCell(fixture.projection.cellId(0), patch));
            SimpleAssert.eq(false, fixture.projection.view().modelOnly, "real source patch is not a readiness update");
            SimpleAssert.eq(
                publications,
                HologramRenderer.previewWorkInspection()
                    .getLong("modelPublications"),
                "source edits do not inflate model readiness publication diagnostics");
        }
    }

    private static void everyPartialTaskCounts() throws Exception {
        try (Fixture fixture = new Fixture(3, 8, false)) {
            for (int i = 0; i < 24; i++) fixture.step(0);
            // Give several inactive/rotated tasks unresolved output, not just the formerly active head.
            Object[] tasks = fixture.pending()
                .values()
                .toArray();
            Object first = field(tasks[0].getClass(), "detail").get(tasks[0]);
            Object second = field(tasks[1].getClass(), "detail").get(tasks[1]);
            field(first.getClass(), "unresolved").setInt(first, 2);
            field(second.getClass(), "budgetLimited").setInt(second, 3);
            field(fixture.type, "detailedCompleted").setInt(fixture.preparation, 7);
            SimpleAssert.eq(null, field(fixture.type, "task").get(fixture.preparation), "yielded tasks are not active");
            SimpleAssert.eq(
                2,
                HologramRenderer.previewMeshCompleted(fixture.projection.view()),
                "all pending task failures reduce resolved progress");
            fixture.pending()
                .clear();
            field(fixture.type, "completed").setInt(fixture.preparation, 7);
            field(fixture.type, "detailedCompleted").setInt(fixture.preparation, 0);
            SimpleAssert.eq(
                false,
                fixture.call("hintsReady"),
                "an empty pending subset cannot replace the full-source denominator");
        }
    }

    private static void linearSparseRegionScheduling() throws Exception {
        int count = 4096;
        try (Fixture fixture = new Fixture(count, 1, false)) {
            CountingTasks measured = new CountingTasks(fixture.pending());
            // Instrument the actual production queue, not elapsed time or a replica of the scheduling algorithm.
            field(fixture.type, "pending").set(fixture.preparation, measured);
            Set<?> cheap = (Set<?>) field(fixture.type, "cheapPending").get(fixture.preparation);
            SimpleAssert.eq(count, cheap.size(), "cheap queue includes every sparse source region");
            for (int region = 0; region < count; region++) fixture.step(0);
            SimpleAssert.eq(0, cheap.size(), "completed hints leave no stale cheap queue entries");
            SimpleAssert.eq(0, measured.inspected, "cheap selection never scans the full pending queue");
            Method update = fixture.type
                .getDeclaredMethod("update", HologramState.class, HologramState.class, Set.class);
            update.setAccessible(true);
            HologramState state = fixture.projection.view();
            HologramRegionCache.Key last = new HologramRegionCache.Key(2 * (count - 1), 0, 0);
            for (int publication = 0; publication < 32; publication++) {
                update.invoke(fixture.preparation, state, state, java.util.Collections.singleton(last));
                SimpleAssert
                    .eq(1, cheap.size(), "one dirty region re-enters exactly once amid thousands of detail tasks");
                fixture.step(0);
                SimpleAssert.eq(0, cheap.size(), "repeated dirty cheap completions do not strand duplicate entries");
            }
            SimpleAssert
                .eq(0, measured.inspected, "a sparse cheap tail is selected without scanning detail predecessors");
            fixture.drain();
            SimpleAssert.eq(
                count,
                measured.inspected,
                "detail scheduling inspects exactly one task per source region, not R squared");
            SimpleAssert
                .eq(2 * count, fixture.count("completed"), "dirty sparse queue converges to exact full-source counts");
        }
    }

    private static final class CountingTasks extends LinkedHashMap<HologramRegionCache.Key, Object> {

        int inspected;

        CountingTasks(Map<HologramRegionCache.Key, Object> tasks) {
            super(tasks);
        }

        @Override
        public Collection<Object> values() {
            Collection<Object> values = super.values();
            return new AbstractCollection<Object>() {

                @Override
                public Iterator<Object> iterator() {
                    Iterator<Object> iterator = values.iterator();
                    return new Iterator<Object>() {

                        @Override
                        public boolean hasNext() {
                            return iterator.hasNext();
                        }

                        @Override
                        public Object next() {
                            inspected++;
                            return iterator.next();
                        }
                    };
                }

                @Override
                public int size() {
                    return values.size();
                }
            };
        }
    }

    private static int integer(Object target, String name) throws Exception {
        return field(target.getClass(), name).getInt(target);
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Fixture implements AutoCloseable {

        final HologramAuthorProjection projection;
        final Class<?> type;
        final Object preparation;
        final Method advance;

        Fixture(int regions, int cellsPerRegion, boolean invalidPayload) throws Exception {
            this(regions, cellsPerRegion, invalidPayload, 32);
        }

        Fixture(int regions, int cellsPerRegion, boolean invalidPayload, int spacing) throws Exception {
            HologramRenderer.release();
            JsonArray rows = new JsonArray();
            for (int region = 0; region < regions; region++) for (int index = 0; index < cellsPerRegion; index++) {
                JsonObject row = new JsonObject();
                row.addProperty("x", region * spacing + (index & 15));
                row.addProperty("y", index >>> 8);
                row.addProperty("z", index >>> 4 & 15);
                row.addProperty("block", "minecraft:stone");
                if (invalidPayload) row.addProperty("tileNbt", "explicit-invalid-native-fixture");
                rows.add(row);
            }
            JsonObject source = new JsonObject();
            source.addProperty("schema", 2);
            source.add("cells", rows);
            projection = new HologramAuthorProjection(
                AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()));
            type = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation");
            Constructor<?> constructor = type.getDeclaredConstructor(HologramState.class);
            constructor.setAccessible(true);
            preparation = constructor.newInstance(projection.view());
            field(HologramRenderer.class, "previewPreparation").set(null, preparation);
            field(HologramRenderer.class, "visibleState").set(null, projection.view());
            advance = type
                .getDeclaredMethod("advance", int.class, int.class, boolean.class, long.class, LongSupplier.class);
            advance.setAccessible(true);
        }

        void step(int deadline) throws Exception {
            AtomicLong clock = new AtomicLong();
            advance.invoke(
                preparation,
                Integer.MIN_VALUE,
                0,
                false,
                (long) deadline,
                (LongSupplier) clock::getAndIncrement);
        }

        void visibleStep(int deadline) throws Exception {
            AtomicLong clock = new AtomicLong();
            advance.invoke(
                preparation,
                Integer.MAX_VALUE,
                0,
                false,
                (long) deadline,
                (LongSupplier) clock::getAndIncrement);
        }

        void visibleDrain() throws Exception {
            for (int pass = 0; pass < 2; pass++)
                advance.invoke(preparation, Integer.MAX_VALUE, 0, false, Long.MAX_VALUE, (LongSupplier) () -> 0);
            SimpleAssert.that(pending().isEmpty(), "available time drains visible base and repair work");
        }

        void drain() throws Exception {
            advance.invoke(preparation, Integer.MIN_VALUE, 0, false, Long.MAX_VALUE, (LongSupplier) () -> 0);
            advance.invoke(preparation, Integer.MIN_VALUE, 0, false, Long.MAX_VALUE, (LongSupplier) () -> 0);
            SimpleAssert.that(pending().isEmpty(), "available time drains every source region after finite changes");
        }

        int count(String name) throws Exception {
            return field(type, name).getInt(preparation);
        }

        Object call(String name) throws Exception {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(preparation);
        }

        @SuppressWarnings("unchecked")
        Map<HologramRegionCache.Key, Object> pending() throws Exception {
            return (Map<HologramRegionCache.Key, Object>) field(type, "pending").get(preparation);
        }

        Map<?, ?> regions() throws Exception {
            return (Map<?, ?>) field(HologramRenderer.class, "previewRegions").get(null);
        }

        @Override
        public void close() {
            projection.close();
            HologramRenderer.release();
        }
    }
}
