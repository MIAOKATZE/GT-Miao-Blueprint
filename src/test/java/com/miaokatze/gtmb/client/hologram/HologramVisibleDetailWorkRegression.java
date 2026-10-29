package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/**
 * Visible ordinary-cube detail, occlusion and CPU projection contracts, not hidden-layer cursor work.
 * Pre-resolved materials avoid an atlas/resource manager; no GL, texture/UV or elapsed-time acceptance claim.
 */
public final class HologramVisibleDetailWorkRegression {

    private HologramVisibleDetailWorkRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramVisibleDetailWorkRegression.class, args)) return;
        run();
        System.out.println("HologramVisibleDetailWorkRegression PASS");
    }

    public static void run() throws Exception {
        visibleDetailReachesPublishedCubeFaces();
        cameraVisibilityIsRecomputedAfterEachYield();
        workInspectionIsDetachedAndReset();
        currentProjectionGuardsBudgetAndCheapOcclusion();
    }

    private static void currentProjectionGuardsBudgetAndCheapOcclusion() throws Exception {
        try (Fixture fixture = new Fixture(1, 8)) {
            HologramState state = fixture.projection.view();
            fixture.camera(3.5, 8);
            field(HologramRenderer.class, "previewState").set(null, state);
            field(HologramRenderer.class, "previewKey").set(null, "camera");
            field(HologramRenderer.class, "projectionPending").setBoolean(null, false);
            SimpleAssert.eq(
                false,
                HologramRenderer.cheapOcclusionReady(state, "camera"),
                "unvisited source hints cannot occlude even with seeded projection counters");
            fixture.finishCheap();
            SimpleAssert.eq(
                true,
                HologramRenderer.cheapOcclusionReady(state, "camera"),
                "matching complete hints and projection permit conservative draw culling");
            SimpleAssert.eq(
                2_000_000L,
                HologramRenderer.preparationDeadline(state, "camera", 2_000_000L, 0),
                "caught-up projection lends unused half without increasing the shared deadline");
            field(HologramRenderer.class, "projectionPending").setBoolean(null, true);
            SimpleAssert.eq(
                false,
                HologramRenderer.cheapOcclusionReady(state, "camera"),
                "partially projected neighbors never hide cheap faces");
            SimpleAssert.eq(
                1_000_000L,
                HologramRenderer.preparationDeadline(state, "camera", 2_000_000L, 0),
                "pending projection retains its half of the cooperative budget");
            field(HologramRenderer.class, "projectionPending").setBoolean(null, false);
            SimpleAssert.eq(
                false,
                HologramRenderer.cheapOcclusionReady(state, "new-camera"),
                "camera mismatch restores all retained cheap faces");
            SimpleAssert.eq(
                1_000_000L,
                HologramRenderer.preparationDeadline(state, "new-camera", 2_000_000L, 0),
                "new camera retains projection time");
            field(HologramRenderer.class, "previewState").set(null, null);
            SimpleAssert.eq(
                false,
                HologramRenderer.cheapOcclusionReady(state, "camera"),
                "reveal/publication invalidation restores cheap faces immediately");
            SimpleAssert.eq(
                1_000_000L,
                HologramRenderer.preparationDeadline(state, "camera", 2_000_000L, 0),
                "reveal/publication invalidation restores the projection half");
            SimpleAssert.eq(
                500_000L,
                HologramRenderer.preparationDeadline(state, "camera", 500_000L, 0),
                "allocation never exceeds the existing shared deadline");
        }
    }

    private static void workInspectionIsDetachedAndReset() throws Exception {
        HologramRenderer.release();
        String[] fields = { "previewFrames", "previewPreparationNanos", "previewProjectionNanos", "previewDrawNanos" };
        String[] keys = { "frames", "preparationNanos", "projectionNanos", "drawNanos" };
        try {
            for (int i = 0; i < fields.length; i++) field(HologramRenderer.class, fields[i]).setLong(null, i + 11L);
            NBTTagCompound first = HologramRenderer.previewWorkInspection();
            for (String key : keys) first.setLong(key, -1);
            NBTTagCompound second = HologramRenderer.previewWorkInspection();
            for (int i = 0; i < keys.length; i++)
                SimpleAssert.eq(i + 11L, second.getLong(keys[i]), "inspection cannot mutate live work counters");
            SimpleAssert.that(first != second, "each inspection is a detached scalar snapshot");
        } finally {
            HologramRenderer.release();
        }
        NBTTagCompound reset = HologramRenderer.previewWorkInspection();
        for (String key : keys) SimpleAssert.eq(0L, reset.getLong(key), "release resets the phase observation window");
        // Counters were seeded, not driven by fake-clock preparation: no claim about actual frame cadence.
    }

    private static void visibleDetailReachesPublishedCubeFaces() throws Exception {
        int regionCount = 41, cellsPerRegion = 64, total = regionCount * cellsPerRegion;
        try (Fixture fixture = new Fixture(regionCount, cellsPerRegion)) {
            fixture.camera(640, 1400);
            fixture.finishCheap();
            SimpleAssert.eq(total, fixture.count("completed"), "full-source cheap output is separate from detail");
            SimpleAssert.eq(0, fixture.count("detailedCompleted"), "cheap fixture never skips into native detail");
            SimpleAssert.eq(
                regionCount,
                fixture.pending()
                    .size(),
                "all 41 detail regions are initially pending");
            fixture.publish();
            SimpleAssert.eq(false, fixture.publish(), "consumed cheap publication is not a permanent latch");

            boolean partialPublication = false;
            int calls = 0;
            while (!fixture.pending()
                .isEmpty() && calls++ < total) {
                int before = fixture.count("detailedCompleted");
                fixture.step(7);
                int after = fixture.count("detailedCompleted");
                SimpleAssert.that(after > before && after - before <= 8, "visible detail honors deterministic slices");
                boolean published = fixture.publish();
                if (published && after < total) {
                    boolean completedRegion = false;
                    for (Object region : fixture.regions()
                        .values()) completedRegion |= bool(region, "complete");
                    partialPublication |= completedRegion;
                }
            }
            SimpleAssert.that(
                fixture.pending()
                    .isEmpty(),
                "41 visible multi-cell detail regions converge");
            SimpleAssert.that(partialPublication, "completed detail becomes publishable before all-source completion");
            SimpleAssert.eq(total, fixture.count("detailedCompleted"), "each source cell receives real visible detail");
            SimpleAssert.eq(2 * total, fixture.count("completed"), "cheap plus detail is an exact visit count");
            SimpleAssert.eq(false, fixture.publish(), "unchanged completed output does not request another publish");

            int faces = 0;
            for (Object region : fixture.regions()
                .values()) {
                SimpleAssert.eq(true, bool(region, "complete"), "all source regions retain completed detail");
                SimpleAssert.eq(0, integer(region, "unresolved"), "ordinary visible cubes are not unresolved shells");
                SimpleAssert.eq(0, integer(region, "budgetLimited"), "typed cubes do not require opaque admission");
                List<?> output = list(region, "cells");
                SimpleAssert.eq(cellsPerRegion, output.size(), "a one-cell-high slab has exposed faces on every cell");
                for (Object cell : output) SimpleAssert
                    .eq(true, bool(cell, "detailed"), "visible output actually reaches detailed PreviewCell");
                fixture.project(region);
                SimpleAssert.eq(
                    "visible-detail-fixture",
                    field(region.getClass(), "projectedKey").get(region),
                    "CPU projection completes the exact region key");
                SimpleAssert.eq(cellsPerRegion, integer(region, "projectedCells"), "every detailed cell is projected");
                for (Object face : list(region, "faces")) {
                    SimpleAssert.eq(false, bool(face, "nativeModel"), "ordinary cube faces need no native GL model");
                    SimpleAssert.that(
                        field(face.getClass(), "material").get(face) == fixture.material,
                        "projected faces use the detailed resolved material, not cheap placeholders");
                    SimpleAssert.that(
                        field(face.getClass(), "surface").get(face) != null,
                        "projected output contains real cube surfaces");
                    faces++;
                }
            }
            // Each region is an isolated 16 x 4 x 1 slab: top/bottom 128 + perimeter 40 exposed faces.
            SimpleAssert.eq(regionCount * 168, faces, "neighbor occlusion removes internal faces on the visible path");
            SimpleAssert.eq(0, HologramBlockRender.compiled, "ordinary detail never compiles a native display list");
        }
    }

    private static void cameraVisibilityIsRecomputedAfterEachYield() throws Exception {
        try (Fixture fixture = new Fixture(1, 8)) {
            fixture.camera(3.5, 8);
            fixture.finishCheap();
            fixture.publish();
            Object task = fixture.pending()
                .values()
                .iterator()
                .next();
            Object detail = field(task.getClass(), "detail").get(task);
            fixture.step(0);
            SimpleAssert.eq(true, bool(list(detail, "cells").get(0), "detailed"), "first visible slice has detail");

            fixture.camera(1000, 8);
            fixture.step(0);
            SimpleAssert.eq(2, integer(task, "cell"), "camera change preserves the same task cursor");
            SimpleAssert.eq(false, bool(list(detail, "cells").get(1), "detailed"), "next slice rechecks hidden region");
            SimpleAssert.eq(1, integer(detail, "unresolved"), "hidden detail remains explicitly unresolved");
            SimpleAssert.eq(true, bool(detail, "deferredVisibility"), "hidden region is eligible for reveal retry");

            fixture.camera(3.5, 8);
            fixture.step(0);
            SimpleAssert
                .eq(true, bool(list(detail, "cells").get(2), "detailed"), "revealed next slice recomputes visibility");
            fixture.finishDetail();
            HologramRegionCache.Key key = fixture.projection.view().regions.regions.keySet()
                .iterator()
                .next();
            Object deferred = fixture.regions()
                .get(key);
            SimpleAssert.eq(1, integer(deferred, "unresolved"), "cursor completion cannot hide earlier skipped detail");

            // The production camera-reveal branch removes output and invokes this same update endpoint.
            // No actual GUI frame/GL draw is claimed by this explicit endpoint exercise.
            fixture.regions()
                .remove(key);
            fixture.update.invoke(
                fixture.preparation,
                fixture.projection.view(),
                fixture.projection.view(),
                Collections.singleton(key));
            fixture.finishCheap();
            fixture.finishDetail();
            Object rebuilt = fixture.regions()
                .get(key);
            SimpleAssert.eq(0, integer(rebuilt, "unresolved"), "revealed region rebuilds previously hidden detail");
            SimpleAssert.eq(false, bool(rebuilt, "deferredVisibility"), "reveal retry retires its deferred marker");
            for (Object cell : list(rebuilt, "cells"))
                SimpleAssert.eq(true, bool(cell, "detailed"), "all revealed cells finally retain visible detail");
            SimpleAssert
                .eq(8, fixture.count("detailedCompleted"), "retry debits old cursor instead of double counting");
        }
    }

    private static int integer(Object target, String name) throws Exception {
        return field(target.getClass(), name).getInt(target);
    }

    private static boolean bool(Object target, String name) throws Exception {
        return field(target.getClass(), name).getBoolean(target);
    }

    private static List<?> list(Object target, String name) throws Exception {
        return (List<?>) field(target.getClass(), name).get(target);
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Fixture implements AutoCloseable {

        final HologramAuthorProjection projection;
        final HologramTextures.Material material;
        final Class<?> type;
        final Object preparation;
        final Method advance, update, project;
        final boolean oldReloadInstalled;

        @SuppressWarnings("unchecked")
        Fixture(int regions, int cellsPerRegion) throws Exception {
            HologramRenderer.release();
            oldReloadInstalled = field(HologramTextures.class, "reloadInstalled").getBoolean(null);
            field(HologramTextures.class, "reloadInstalled").setBoolean(null, true);
            JsonArray rows = new JsonArray();
            for (int region = 0; region < regions; region++) for (int index = 0; index < cellsPerRegion; index++) {
                JsonObject row = new JsonObject();
                row.addProperty("x", region * 32 + (index & 15));
                row.addProperty("y", 0);
                row.addProperty("z", index >>> 4);
                row.addProperty("block", "minecraft:stone");
                row.addProperty("item", "minecraft:stone");
                rows.add(row);
            }
            JsonObject source = new JsonObject();
            source.addProperty("schema", 2);
            source.add("cells", rows);
            projection = new HologramAuthorProjection(
                AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()));
            HologramState state = projection.view();
            HologramBlockRender.begin(state);
            HologramTextures.begin(state);
            List<HologramTextures.Sample>[] sides = new List[6], covers = new List[6];
            for (int side = 0; side < 6; side++) sides[side] = covers[side] = Collections.emptyList();
            material = new HologramTextures.Material(
                sides,
                covers,
                HologramGeometry.cube(),
                false,
                false,
                false,
                false);
            Map<HologramState.Cell, HologramTextures.Material[]> resolved = (Map<HologramState.Cell, HologramTextures.Material[]>) field(
                HologramTextures.class,
                "RESOLVED").get(null);
            for (HologramState.Cell cell : state.cells) {
                SimpleAssert.eq(false, cell.modelPending(false), "exact vanilla ItemBlock facts are already available");
                resolved.put(cell, new HologramTextures.Material[] { material, material });
            }
            type = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation");
            Constructor<?> constructor = type.getDeclaredConstructor(HologramState.class);
            constructor.setAccessible(true);
            preparation = constructor.newInstance(state);
            field(HologramRenderer.class, "previewPreparation").set(null, preparation);
            field(HologramRenderer.class, "visibleState").set(null, state);
            advance = type
                .getDeclaredMethod("advance", int.class, int.class, boolean.class, long.class, LongSupplier.class);
            advance.setAccessible(true);
            update = type.getDeclaredMethod("update", HologramState.class, HologramState.class, Set.class);
            update.setAccessible(true);
            Class<?> regionType = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
            project = HologramRenderer.class.getDeclaredMethod(
                "projectPreviewRegion",
                regionType,
                String.class,
                int.class,
                boolean.class,
                boolean.class,
                long.class,
                int.class,
                LongSupplier.class);
            project.setAccessible(true);
        }

        void camera(double x, double extent) throws Exception {
            field(HologramRenderer.class, "projectedFrame")
                .set(null, new HologramEditorCameraFrame(0, 0, 800, 600, 0, 0, 1, x, 0, 0, extent));
        }

        void step(int deadline) throws Exception {
            AtomicLong clock = new AtomicLong();
            advance.invoke(
                preparation,
                Integer.MAX_VALUE,
                0,
                false,
                (long) deadline,
                (LongSupplier) clock::getAndIncrement);
        }

        void finishCheap() throws Exception {
            int limit = projection.view().cells.size() + 1;
            while (!(Boolean) call("hintsReady") && limit-- > 0) step(0);
            SimpleAssert.eq(true, call("hintsReady"), "bounded single-cell slices finish all cheap work");
        }

        void finishDetail() throws Exception {
            int limit = projection.view().cells.size() + 1;
            while (!pending().isEmpty() && limit-- > 0) step(0);
            SimpleAssert.that(pending().isEmpty(), "bounded single-cell slices finish visible detail work");
        }

        void project(Object region) throws Exception {
            project.invoke(
                null,
                region,
                "visible-detail-fixture",
                63,
                false,
                true,
                Long.MAX_VALUE,
                Integer.MAX_VALUE,
                (LongSupplier) () -> 0);
        }

        boolean publish() throws Exception {
            return (Boolean) call("publishProgress");
        }

        Object call(String name) throws Exception {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(preparation);
        }

        int count(String name) throws Exception {
            return field(type, name).getInt(preparation);
        }

        @SuppressWarnings("unchecked")
        Map<HologramRegionCache.Key, Object> pending() throws Exception {
            return (Map<HologramRegionCache.Key, Object>) field(type, "pending").get(preparation);
        }

        @SuppressWarnings("unchecked")
        Map<HologramRegionCache.Key, Object> regions() throws Exception {
            return (Map<HologramRegionCache.Key, Object>) field(HologramRenderer.class, "previewRegions").get(null);
        }

        @Override
        public void close() throws Exception {
            try {
                projection.close();
                HologramRenderer.release();
            } finally {
                field(HologramTextures.class, "reloadInstalled").setBoolean(null, oldReloadInstalled);
            }
        }
    }
}
