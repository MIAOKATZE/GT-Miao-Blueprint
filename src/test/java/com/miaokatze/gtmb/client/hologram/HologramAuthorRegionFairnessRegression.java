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
                SimpleAssert
                    .eq(4, previous, "cheap invalidations defer native work without discarding its partial detail");
                SimpleAssert.eq(
                    4,
                    fixture.count("detailedCompleted"),
                    "retained partial native accounting survives the publication stream");
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
            HologramRenderer.release();
            JsonArray rows = new JsonArray();
            for (int region = 0; region < regions; region++) for (int index = 0; index < cellsPerRegion; index++) {
                JsonObject row = new JsonObject();
                row.addProperty("x", region * 32 + (index & 15));
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
