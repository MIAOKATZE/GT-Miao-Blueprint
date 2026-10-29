package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the real CPU projection loop with counted appearance queries; no GL/native timing claim. */
public final class HologramProjectionAppearanceRegression {

    private HologramProjectionAppearanceRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramProjectionAppearanceRegression.class, args)) return;
        run();
        System.out.println("HologramProjectionAppearanceRegression PASS");
    }

    public static void run() throws Exception {
        HologramRenderer.release();
        boolean reload = field(HologramTextures.class, "reloadInstalled").getBoolean(null);
        field(HologramTextures.class, "reloadInstalled").setBoolean(null, true);
        try {
            HologramEditorCameraFrame camera = new HologramEditorCameraFrame(0, 0, 800, 600, .7, .5, 1, 0, 0, 0, 8);
            field(HologramRenderer.class, "projectedFrame").set(null, camera);
            exercise(camera, false, false, false, false, false, false);
            exercise(camera, true, false, false, false, false, false);
            exercise(camera, false, true, false, false, false, false);
            exercise(camera, false, false, true, false, false, false);
            exercise(camera, false, false, false, true, false, false);
            exercise(camera, false, false, false, false, true, false);
            exercise(camera, false, false, false, false, false, true);
            anchorAndConstructorContracts();
            changedReadinessRequiresContentInvalidation();
            sameCameraInvalidationResamplesAppearance();
            cameraWithoutAdmissionRetainsResolvedAndPendingFifo();
        } finally {
            HologramRenderer.release();
            field(HologramTextures.class, "reloadInstalled").setBoolean(null, reload);
        }
    }

    private static void exercise(HologramEditorCameraFrame camera, boolean nativeReady, boolean partial, boolean cheap,
        boolean empty, boolean current, boolean protectedCell) throws Exception {
        field(HologramRenderer.class, "projectedFrame").set(null, camera);
        HologramState.Cell cell = cell(empty, protectedCell);
        HologramTextures.Material target = material(partial), actual = material(false);
        seed(cell, target, actual);
        CountingResolver resolver = new CountingResolver(nativeReady, target);
        Object region = region(cell, !cheap);
        project(region, "appearance", current, resolver);
        SimpleAssert.eq(cheap ? 0 : 1, resolver.readyCalls, "one native readiness query per detailed projection unit");
        SimpleAssert.eq(1, resolver.materialCalls, "one material query covers outline and all surface Faces");
        boolean emptyFace = empty && !current;
        HologramTextures.Material expected = cheap ? target : current || protectedCell ? actual : target;
        List<?> faces = list(region, "faces"), outlines = list(region, "outlines");
        boolean nativeOutput = !cheap && nativeReady;
        SimpleAssert
            .eq(nativeOutput ? 6 : expected.geometry.size(), faces.size(), "all surface/pick faces are preserved");
        SimpleAssert.eq(1, outlines.size(), "one exact voxel outline is preserved");
        SimpleAssert.eq(nativeOutput ? 1 : 0, list(region, "nativeCells").size(), "native membership is consistent");
        verifyFace(outlines.get(0), cell, expected, emptyFace, nativeOutput && !emptyFace, false);
        for (Object face : faces) {
            verifyFace(face, cell, expected, emptyFace, nativeOutput && !emptyFace, cheap);
            HologramGeometry.Surface surface = (HologramGeometry.Surface) field(face.getClass(), "surface").get(face);
            SimpleAssert.eq(
                nativeOutput,
                surface == null,
                "native picking uses voxel faces; material geometry retains surfaces");
            double[][] points = (double[][]) field(face.getClass(), "points").get(face);
            for (int vertex = 0; vertex < 8; vertex++) {
                double[] local = surface == null ? cubeVertex(vertex) : surface.vertices[vertex];
                double[] expectedPoint = camera.project(cell.dx + local[0], cell.dy + local[1], cell.dz + local[2]);
                for (int axis = 0; axis < 3; axis++) SimpleAssert.that(
                    Math.abs(expectedPoint[axis] - points[vertex][axis]) < 1e-10,
                    "frozen appearance does not change exact cube/partial surface projection");
            }
        }
        int readyCalls = resolver.readyCalls, materialCalls = resolver.materialCalls;
        project(region, "appearance", current, resolver);
        SimpleAssert.eq(readyCalls, resolver.readyCalls, "completed key never resolves cells twice");
        SimpleAssert.eq(materialCalls, resolver.materialCalls, "completed key reuses projected appearance");
        retainedCameraMotion(region, cell, expected, emptyFace, nativeOutput, cheap, current, resolver);
        field(HologramRenderer.class, "projectedFrame").set(null, camera);
    }

    private static void retainedCameraMotion(Object region, HologramState.Cell cell, HologramTextures.Material expected,
        boolean empty, boolean nativeOutput, boolean cheap, boolean current, CountingResolver resolver)
        throws Exception {
        int readyCalls = resolver.readyCalls, materialCalls = resolver.materialCalls;
        for (int frame = 0; frame < 120; frame++) {
            double yaw = .17 + frame * .073, pitch = -.55 + frame % 19 * .061, zoom = .7 + frame % 11 * .09;
            double ax = frame * .01, ay = -.25, az = .4;
            HologramEditorCameraFrame camera = new HologramEditorCameraFrame(
                11,
                17,
                723,
                511,
                yaw,
                pitch,
                zoom,
                ax,
                ay,
                az,
                8);
            field(HologramRenderer.class, "projectedFrame").set(null, camera);
            double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
            // Independent old GUI depth gradients determine which outward normals face the camera.
            double[] depthGradient = { sy * cp, sp, cy * cp };
            int facing = (depthGradient[1] < -1e-8 ? 1 : depthGradient[1] > 1e-8 ? 2 : 0)
                | (depthGradient[2] < -1e-8 ? 4 : depthGradient[2] > 1e-8 ? 8 : 0)
                | (depthGradient[0] < -1e-8 ? 16 : depthGradient[0] > 1e-8 ? 32 : 0);
            project(region, "retained-camera-" + frame, current, resolver, facing, 0);
            int expectedFaces = nativeOutput ? Integer.bitCount(facing) : 0;
            if (!nativeOutput) for (HologramGeometry.Surface surface : expected.geometry)
                if ((facing & 1 << surface.side) != 0) expectedFaces++;
            SimpleAssert
                .eq(expectedFaces, list(region, "faces").size(), "turning reveals exact retained material surfaces");
            SimpleAssert.eq(1, list(region, "outlines").size(), "zero-admission camera frame retains outline coverage");
            for (Object face : list(region, "faces")) {
                verifyFace(face, cell, expected, empty, nativeOutput && !empty, cheap);
                int side = field(face.getClass(), "side").getInt(face);
                HologramGeometry.Surface surface = (HologramGeometry.Surface) field(face.getClass(), "surface")
                    .get(face);
                double[][] points = (double[][]) field(face.getClass(), "points").get(face);
                for (int vertex = 0; vertex < 8; vertex++) {
                    double[] local = surface == null ? cubeVertex(vertex) : surface.vertices[vertex];
                    double x = cell.dx + local[0] - ax, y = cell.dy + local[1] - ay, z = cell.dz + local[2] - az;
                    double rx = x * cy - z * sy, rz = x * sy + z * cy;
                    double scale = 511 * .72 / 8 * zoom;
                    double[] independent = { 11 + 723 / 2.0 + rx * scale, 17 + 511 / 2.0 - (y * cp - rz * sp) * scale,
                        y * sp + rz * cp };
                    for (int axis = 0; axis < 3; axis++) SimpleAssert.that(
                        Math.abs(independent[axis] - points[vertex][axis]) < 1e-8,
                        "retained partial/native/actual coordinates agree with independent current-camera equations");
                }
                if (!empty) {
                    Method samples = face.getClass()
                        .getDeclaredMethod("samples");
                    samples.setAccessible(true);
                    Object actualSamples = samples.invoke(face);
                    List<HologramTextures.Sample> expectedSamples = surface == null ? expected.sides[side]
                        : surface.coverSide >= 0 ? expected.covers[surface.coverSide] : expected.sides[surface.side];
                    SimpleAssert.that(
                        actualSamples == expectedSamples,
                        "camera keeps exact per-side/cover sample layers and tint ownership");
                    SimpleAssert
                        .that(!expectedSamples.isEmpty(), "sample preservation is not vacuous empty-material coverage");
                    SimpleAssert.eq(
                        surface != null && surface.coverSide >= 0 ? 0x708090 + surface.coverSide : 0x102030 + side,
                        expectedSamples.get(0).color,
                        "retained side/cover tint remains unchanged");
                }
            }
            SimpleAssert
                .eq(readyCalls, resolver.readyCalls, "zero-admission camera motion cannot call native readiness");
            SimpleAssert.eq(
                materialCalls,
                resolver.materialCalls,
                "zero-admission camera motion cannot call material providers");
        }
    }

    private static void changedReadinessRequiresContentInvalidation() throws Exception {
        HologramState.Cell cell = cell(false, false);
        HologramTextures.Material material = material(false);
        seed(cell, material, material);
        Object region = region(cell, true);
        CountingResolver resolver = new CountingResolver(false, material);
        project(region, "waiting", false, resolver);
        SimpleAssert.eq(0, list(region, "nativeCells").size(), "deferred/failed false remains non-native in this unit");
        resolver.ready = true;
        project(region, "camera-only", false, resolver);
        SimpleAssert.eq(1, resolver.readyCalls, "camera changes do not poll native readiness");
        Method invalidate = region.getClass()
            .getDeclaredMethod("invalidateProjection");
        invalidate.setAccessible(true);
        invalidate.invoke(region); // Model lifecycle notification, independent of the requested camera.
        project(region, "ready", false, resolver);
        SimpleAssert.eq(1, list(region, "nativeCells").size(), "later ready result is not hidden by a false cache");
        resolver.ready = false;
        invalidate.invoke(region);
        project(region, "failed", false, resolver);
        SimpleAssert.eq(0, list(region, "nativeCells").size(), "later false result does not retain native output");
        SimpleAssert.eq(3, resolver.readyCalls, "each content readiness revision independently samples readiness once");
        SimpleAssert.eq(3, resolver.materialCalls, "each content revision samples material once");
    }

    private static void anchorAndConstructorContracts() throws Exception {
        HologramState.Cell cell = cell(true, false, true);
        HologramTextures.Material target = material(true), actual = material(false);
        seed(cell, target, actual);
        Object region = region(cell, true);
        CountingResolver resolver = new CountingResolver(false, target);
        project(region, "anchor", false, resolver);
        SimpleAssert.eq(1, resolver.readyCalls, "anchor readiness is sampled once");
        SimpleAssert.eq(1, resolver.materialCalls, "anchor material is sampled once");
        Object projected = list(region, "faces").get(0);
        verifyFace(projected, cell, actual, false, false, false);
        double[][] points = (double[][]) field(projected.getClass(), "points").get(projected);
        // Keep all pre-existing constructor signatures available to current tests/CPU consumers.
        Class<?> face = projected.getClass();
        Class<?>[][] signatures = { { HologramState.Cell.class, double[][].class, int.class, boolean.class },
            { HologramState.Cell.class, double[][].class, int.class, boolean.class, HologramGeometry.Surface.class },
            { HologramState.Cell.class, double[][].class, int.class, boolean.class, HologramGeometry.Surface.class,
                boolean.class },
            { HologramState.Cell.class, double[][].class, int.class, boolean.class, HologramGeometry.Surface.class,
                boolean.class, boolean.class } };
        Object[][] arguments = { { cell, points, 0, false }, { cell, points, 0, false, null },
            { cell, points, 0, false, null, true }, { cell, points, 0, false, null, true, false } };
        for (int index = 0; index < signatures.length; index++) {
            Constructor<?> constructor = face.getDeclaredConstructor(signatures[index]);
            constructor.setAccessible(true);
            verifyFace(constructor.newInstance(arguments[index]), cell, actual, false, false, false);
        }
    }

    private static void sameCameraInvalidationResamplesAppearance() throws Exception {
        HologramState.Cell cell = cell(false, false);
        Object region = region(cell, true);
        Object camera = field(HologramRenderer.class, "projectedFrame").get(null);
        CountingResolver resolver = new CountingResolver(false, material(false));
        Method invalidate = region.getClass()
            .getDeclaredMethod("invalidateProjection");
        invalidate.setAccessible(true);
        boolean[] readiness = { false, true, false };
        for (int step = 0; step < readiness.length; step++) {
            HologramTextures.Material refreshed = material(step == 2);
            seed(cell, refreshed, refreshed);
            resolver.ready = readiness[step];
            if (step > 0) invalidate.invoke(region);
            project(region, "fixed-camera", false, resolver);
            SimpleAssert.that(
                field(HologramRenderer.class, "projectedFrame").get(null) == camera,
                "native readiness wake does not require a new camera frame");
            SimpleAssert.eq(
                "fixed-camera",
                field(region.getClass(), "projectedKey").get(region),
                "same-camera wake republishes the exact unchanged camera key");
            SimpleAssert.eq(step + 1, resolver.readyCalls, "same-key invalidation samples native readiness once again");
            SimpleAssert
                .eq(step + 1, resolver.materialCalls, "same-key invalidation samples refreshed material once again");
            SimpleAssert.eq(
                readiness[step] ? 1 : 0,
                list(region, "nativeCells").size(),
                "same-key false/true/false transitions never retain stale native membership");
            SimpleAssert.eq(
                readiness[step] ? 6 : refreshed.geometry.size(),
                list(region, "faces").size(),
                "same-key material changes replace rather than append stale surface geometry");
            verifyFace(list(region, "outlines").get(0), cell, refreshed, false, readiness[step], false);
            for (Object face : list(region, "faces")) verifyFace(face, cell, refreshed, false, readiness[step], false);
            project(region, "fixed-camera", false, resolver);
            SimpleAssert.eq(step + 1, resolver.readyCalls, "unchanged completed key does not resample readiness");
            SimpleAssert.eq(step + 1, resolver.materialCalls, "unchanged completed key does not resample material");
        }
    }

    private static void cameraWithoutAdmissionRetainsResolvedAndPendingFifo() throws Exception {
        HologramState.Cell first = cell(false, false);
        NBTTagCompound row = first.tag();
        row.setInteger("dx", 5);
        HologramState.Cell second = new HologramState.Cell(1, row);
        HologramTextures.Material material = material(true);
        seed(first, material, material);
        seed(second, material, material);
        Object region = region(first, true), secondRegion = region(second, true);
        mutableList(region, "cells").add(list(secondRegion, "cells").get(0));
        CountingResolver resolver = new CountingResolver(false, material);
        project(region, "one-admitted", false, resolver, 63, 1);
        SimpleAssert.eq(1, list(region, "outlines").size(), "only the first pending content slot is admitted");
        int calls = resolver.readyCalls, materials = resolver.materialCalls;
        HologramEditorCameraFrame camera = new HologramEditorCameraFrame(0, 0, 800, 600, -.8, -.4, 1.4, 1, 0, 0, 8);
        field(HologramRenderer.class, "projectedFrame").set(null, camera);
        project(region, "zero-admission-new-camera", false, resolver, 63, 0);
        SimpleAssert.eq(calls, resolver.readyCalls, "maxCells zero cannot resolve another native appearance");
        SimpleAssert.eq(materials, resolver.materialCalls, "maxCells zero cannot resolve another material");
        SimpleAssert.eq(1, list(region, "outlines").size(), "retained output is not blanked while pending work yields");
        SimpleAssert
            .eq("", field(region.getClass(), "projectedKey").get(region), "unresolved FIFO prevents false completion");
        Map<?, ?> todo = (Map<?, ?>) field(region.getClass(), "projectionTodo").get(region);
        SimpleAssert.eq(
            Collections.singletonList(1),
            new java.util.ArrayList<>(todo.keySet()),
            "unresolved source FIFO survives camera change exactly");
        double[][] points = (double[][]) field(
            list(region, "outlines").get(0)
                .getClass(),
            "points").get(list(region, "outlines").get(0));
        double[] expected = camera.project(first.dx - .5, first.dy - .5, first.dz - .5);
        for (int axis = 0; axis < 3; axis++) SimpleAssert.that(
            Math.abs(points[0][axis] - expected[axis]) < 1e-8,
            "the retained subset follows the new camera even without any content admission");
        project(region, "zero-admission-new-camera", false, resolver, 63, 1);
        SimpleAssert.eq(2, list(region, "outlines").size(), "next admitted slice completes the original pending slot");
        SimpleAssert.eq(calls + 1, resolver.readyCalls, "already resolved slot is never charged twice");
        SimpleAssert.eq(
            "zero-admission-new-camera",
            field(region.getClass(), "projectedKey").get(region),
            "completion binds current camera only after FIFO drains");
    }

    private static void verifyFace(Object face, HologramState.Cell cell, HologramTextures.Material material,
        boolean empty, boolean nativeModel, boolean cheapHidden) throws Exception {
        SimpleAssert.that(field(face.getClass(), "cell").get(face) == cell, "face keeps exact cell identity");
        SimpleAssert.eq(empty, field(face.getClass(), "empty").getBoolean(face), "empty contract unchanged");
        SimpleAssert
            .eq(nativeModel, field(face.getClass(), "nativeModel").getBoolean(face), "native contract unchanged");
        SimpleAssert.that(
            field(face.getClass(), "material").get(face) == (empty ? null : material),
            "material identity unchanged");
        SimpleAssert.eq(
            cheapHidden,
            field(face.getClass(), "cheapHidden").getBoolean(face),
            "cheap culling certificate unchanged");
    }

    private static HologramState.Cell cell(boolean empty, boolean protectedCell) {
        return cell(empty, protectedCell, false);
    }

    private static HologramState.Cell cell(boolean empty, boolean protectedCell, boolean anchor) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", "minecraft:stone");
        row.setString("wantId", empty ? "minecraft:air" : "minecraft:stone");
        row.setString("status", protectedCell ? "protected" : "pending");
        row.setBoolean("anchor", anchor);
        row.setInteger("dx", 2);
        row.setInteger("dy", 1);
        row.setInteger("dz", -3);
        if (protectedCell) {
            NBTTagCompound stack = new NBTTagCompound();
            new ItemStack(Blocks.stone).writeToNBT(stack);
            row.setTag("actualStack", stack);
        }
        return new HologramState.Cell(0, row);
    }

    @SuppressWarnings("unchecked")
    private static HologramTextures.Material material(boolean partial) {
        List<HologramTextures.Sample>[] sides = new List[6], covers = new List[6];
        for (int side = 0; side < 6; side++) {
            sides[side] = java.util.Arrays.asList(
                new HologramTextures.Sample(new TextureAtlasSprite("side-" + side) {}, 0x102030 + side),
                new HologramTextures.Sample(new TextureAtlasSprite("overlay-" + side) {}, 0x405060 + side));
            covers[side] = Collections.singletonList(
                new HologramTextures.Sample(new TextureAtlasSprite("cover-" + side) {}, 0x708090 + side));
        }
        return new HologramTextures.Material(
            sides,
            covers,
            partial ? HologramGeometry.pipe(.5f, 1, 4) : HologramGeometry.cube(),
            false,
            false,
            partial,
            partial);
    }

    @SuppressWarnings("unchecked")
    private static void seed(HologramState.Cell cell, HologramTextures.Material target,
        HologramTextures.Material actual) throws Exception {
        ((Map<HologramState.Cell, HologramTextures.Material[]>) field(HologramTextures.class, "RESOLVED").get(null))
            .put(cell, new HologramTextures.Material[] { target, actual });
    }

    private static Object region(HologramState.Cell cell, boolean detailed) throws Exception {
        Class<?> regionType = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
        Constructor<?> constructor = regionType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object region = constructor.newInstance();
        Class<?> cellType = Class.forName(HologramRenderer.class.getName() + "$PreviewCell");
        Constructor<?> cellConstructor = cellType
            .getDeclaredConstructor(HologramState.Cell.class, int.class, String.class, boolean.class, int.class);
        cellConstructor.setAccessible(true);
        mutableList(region, "cells").add(cellConstructor.newInstance(cell, 63, "", detailed, 0));
        return region;
    }

    private static void project(Object region, String key, boolean current, CountingResolver resolver)
        throws Exception {
        project(region, key, current, resolver, 63, Integer.MAX_VALUE);
    }

    private static void project(Object region, String key, boolean current, CountingResolver resolver, int facing,
        int maxCells) throws Exception {
        Method method = HologramRenderer.class.getDeclaredMethod(
            "projectPreviewRegion",
            region.getClass(),
            String.class,
            int.class,
            boolean.class,
            boolean.class,
            long.class,
            int.class,
            LongSupplier.class,
            HologramRenderer.PreviewAppearanceResolver.class);
        method.setAccessible(true);
        method.invoke(
            null,
            region,
            key,
            facing,
            current,
            true,
            Long.MAX_VALUE,
            maxCells,
            (LongSupplier) () -> 0,
            resolver);
    }

    private static double[] cubeVertex(int index) {
        double[][] vertices = { { -.5, -.5, -.5 }, { .5, -.5, -.5 }, { .5, .5, -.5 }, { -.5, .5, -.5 },
            { -.5, -.5, .5 }, { .5, -.5, .5 }, { .5, .5, .5 }, { -.5, .5, .5 } };
        return vertices[index];
    }

    private static List<?> list(Object owner, String name) throws Exception {
        return (List<?>) field(owner.getClass(), name).get(owner);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mutableList(Object owner, String name) throws Exception {
        return (List<Object>) field(owner.getClass(), name).get(owner);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class CountingResolver implements HologramRenderer.PreviewAppearanceResolver {

        boolean ready;
        int readyCalls, materialCalls;
        final HologramTextures.Material cheap;

        CountingResolver(boolean ready, HologramTextures.Material cheap) {
            this.ready = ready;
            this.cheap = cheap;
        }

        public boolean nativeReady(HologramState.Cell cell, boolean current) {
            readyCalls++;
            return ready;
        }

        public HologramTextures.Material material(HologramState.Cell cell, boolean current, boolean detailed) {
            materialCalls++;
            return detailed ? HologramTextures.material(cell, current) : cheap;
        }
    }
}
