package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

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
            changedReadinessDoesNotLeakAcrossProjectionKeys();
            sameCameraInvalidationResamplesAppearance();
        } finally {
            HologramRenderer.release();
            field(HologramTextures.class, "reloadInstalled").setBoolean(null, reload);
        }
    }

    private static void exercise(HologramEditorCameraFrame camera, boolean nativeReady, boolean partial, boolean cheap,
        boolean empty, boolean current, boolean protectedCell) throws Exception {
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
    }

    private static void changedReadinessDoesNotLeakAcrossProjectionKeys() throws Exception {
        HologramState.Cell cell = cell(false, false);
        HologramTextures.Material material = material(false);
        seed(cell, material, material);
        Object region = region(cell, true);
        CountingResolver resolver = new CountingResolver(false, material);
        project(region, "waiting", false, resolver);
        SimpleAssert.eq(0, list(region, "nativeCells").size(), "deferred/failed false remains non-native in this unit");
        resolver.ready = true;
        project(region, "ready", false, resolver);
        SimpleAssert.eq(1, list(region, "nativeCells").size(), "later ready result is not hidden by a false cache");
        resolver.ready = false;
        project(region, "failed", false, resolver);
        SimpleAssert.eq(0, list(region, "nativeCells").size(), "later false result does not retain native output");
        SimpleAssert.eq(3, resolver.readyCalls, "each projection unit independently samples readiness once");
        SimpleAssert.eq(3, resolver.materialCalls, "each projection unit samples material once");
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
        for (int side = 0; side < 6; side++) sides[side] = covers[side] = Collections.emptyList();
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
            63,
            current,
            true,
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
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
