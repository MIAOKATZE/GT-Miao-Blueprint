package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Cursor and halo contracts only: no Minecraft instance, native model, GL or rendering claims. */
public final class HologramProgressivePreviewRegression {

    private HologramProgressivePreviewRegression() {}

    public static void run() {
        try {
            hintBudget();
            readiness();
            projectionBudget();
            extentProjection();
            localHalo();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("progressive preview cursor contract", failure);
        }
    }

    private static void readiness() throws ReflectiveOperationException {
        NBTTagList rows = new NBTTagList();
        rows.appendTag(cell(0, 0, 0));
        HologramState state = state(rows);
        Class<?> type = nested("PreviewPreparation");
        Object preparation = construct(type, new Class<?>[] { HologramState.class }, state);
        Field activeState = field(HologramRenderer.class, "previewState"),
            activePreparation = field(HologramRenderer.class, "previewPreparation");
        Object previousState = activeState.get(null), previousPreparation = activePreparation.get(null);
        try {
            activeState.set(null, state);
            activePreparation.set(null, preparation);
            field(type, "completed").setInt(preparation, 1);
            SimpleAssert.eq(
                false,
                HologramRenderer.previewMeshReady(state),
                "a complete cheap hint pass is not a complete detailed preview");
            field(type, "completed").setInt(preparation, 2);
            field(type, "detailedCompleted").setInt(preparation, 1);
            ((Map<?, ?>) field(type, "pending").get(preparation)).clear();
            SimpleAssert.eq(
                true,
                HologramRenderer.previewMeshReady(state),
                "two-pass preparation becomes ready after all detail tasks complete");
        } finally {
            activeState.set(null, previousState);
            activePreparation.set(null, previousPreparation);
        }
    }

    @SuppressWarnings("unchecked")
    private static void projectionBudget() throws ReflectiveOperationException {
        Class<?> regionType = nested("PreviewRegion"), cellType = nested("PreviewCell");
        Object region = construct(regionType, new Class<?>[0]);
        List<Object> cells = (List<Object>) field(regionType, "cells").get(region);
        HologramState.Cell cell = new HologramState.Cell(0, cell(0, 0, 0));
        for (int i = 0; i < 300; i++) cells.add(
            construct(
                cellType,
                new Class<?>[] { HologramState.Cell.class, int.class, String.class, boolean.class },
                cell,
                0,
                "",
                false));
        Method project = HologramRenderer.class.getDeclaredMethod(
            "projectPreviewRegion",
            regionType,
            String.class,
            int.class,
            boolean.class,
            boolean.class,
            long.class,
            int.class,
            java.util.function.LongSupplier.class);
        project.setAccessible(true);
        java.util.function.LongSupplier available = () -> 0;
        SimpleAssert.eq(
            1,
            project.invoke(null, region, "camera", 0, false, true, 0L, Integer.MAX_VALUE, available),
            "an expired projection deadline yields after one atomic cell");
        SimpleAssert.eq("", field(regionType, "projectedKey").get(region), "partial projection is not marked complete");
        SimpleAssert.eq(
            299,
            project.invoke(null, region, "camera", 0, false, true, Long.MAX_VALUE, Integer.MAX_VALUE, available),
            "projection consumes available time beyond the former 256-cell frame cap");
        SimpleAssert
            .eq("camera", field(regionType, "projectedKey").get(region), "complete projection binds its camera key");
        SimpleAssert.eq(
            1,
            project.invoke(null, region, "new-camera", 0, false, true, Long.MAX_VALUE, 1, available),
            "camera changes invalidate projected data without losing its source region");
    }

    private static void hintBudget() throws ReflectiveOperationException {
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < 600; i++) {
            int local = i % 300;
            rows.appendTag(cell((local & 15) + (i / 300) * 32, local >> 8, (local >> 4) & 15));
        }
        HologramState state = state(rows);
        Class<?> type = nested("PreviewPreparation");
        Object preparation = construct(type, new Class<?>[] { HologramState.class }, state);
        Method advance = type.getDeclaredMethod(
            "advance",
            int.class,
            int.class,
            boolean.class,
            long.class,
            java.util.function.LongSupplier.class);
        advance.setAccessible(true);
        java.util.function.LongSupplier available = () -> 0;
        // Hidden-layer rows exercise the production cursor without requesting a Minecraft texture/native callback.
        advance.invoke(preparation, Integer.MIN_VALUE, 0, false, 0L, available);
        SimpleAssert.eq(
            1,
            field(type, "completed").getInt(preparation),
            "an expired preparation deadline yields after one atomic cell");
        java.util.concurrent.atomic.AtomicLong time = new java.util.concurrent.atomic.AtomicLong();
        advance.invoke(
            preparation,
            Integer.MIN_VALUE,
            0,
            false,
            3L,
            (java.util.function.LongSupplier) time::getAndIncrement);
        SimpleAssert.eq(
            5,
            field(type, "completed").getInt(preparation),
            "preparation stops at the deterministic shared deadline rather than counting a fixed page");
        advance.invoke(preparation, Integer.MIN_VALUE, 0, false, Long.MAX_VALUE, available);
        SimpleAssert.eq(
            300,
            field(type, "completed").getInt(preparation),
            "the first full region hint is presented before native detail callbacks");
        SimpleAssert.eq(
            0,
            field(type, "detailedCompleted").getInt(preparation),
            "first hint feedback retains its natural frame boundary");
        Field regions = field(HologramRenderer.class, "previewRegions");
        Map<?, ?> prepared = (Map<?, ?>) regions.get(null);
        Object region = prepared.get(new HologramRegionCache.Key(0, 0, 0));
        SimpleAssert.that(region != null, "first region is available before all detail work");
        advance.invoke(preparation, Integer.MIN_VALUE, 0, false, Long.MAX_VALUE, available);
        SimpleAssert.eq(
            1200,
            field(type, "completed").getInt(preparation),
            "later hint and detail passes consume available time across regions without 256-cell or one-region frame caps");
        SimpleAssert.eq(
            600,
            field(type, "detailedCompleted").getInt(preparation),
            "a complete detailed pass retains every cell");
        SimpleAssert.that(
            ((Map<?, ?>) field(type, "pending").get(preparation)).isEmpty(),
            "all regions complete when the deterministic deadline permits them");
        prepared.clear();
    }

    @SuppressWarnings("unchecked")
    private static void extentProjection() throws ReflectiveOperationException {
        NBTTagList rows = new NBTTagList();
        rows.appendTag(cell(0, 0, 0));
        rows.appendTag(cell(64, 0, 0));
        NBTTagCompound data = new NBTTagCompound();
        data.setBoolean("virtual", true);
        data.setTag("cells", rows);
        HologramState before = new HologramState(data);
        NBTTagCompound patch = new NBTTagCompound();
        patch.setBoolean("partial", true);
        patch.setBoolean("topology", true);
        patch.setLong("baseRevision", 0);
        patch.setLong("planRevision", 1);
        patch.setInteger("baseCellCount", 2);
        patch.setInteger("cellCount", 2);
        NBTTagCompound moved = cell(80, 0, 0);
        moved.setInteger("index", 1);
        NBTTagList changed = new NBTTagList();
        changed.appendTag(moved);
        patch.setTag("cells", changed);
        HologramState after = HologramState.apply(before, patch);
        Class<?> regionType = nested("PreviewRegion");
        Object retained = construct(regionType, new Class<?>[0]);
        field(regionType, "projectedKey").set(retained, "camera");
        field(regionType, "projectionWorkKey").set(retained, "camera");
        Map<HologramRegionCache.Key, Object> regions = (Map<HologramRegionCache.Key, Object>) field(
            HologramRenderer.class,
            "previewRegions").get(null);
        HologramRegionCache.Key origin = new HologramRegionCache.Key(0, 0, 0);
        regions.put(origin, retained);
        try {
            HologramRenderer.snapshotChanged(before, after);
            SimpleAssert.that(regions.get(origin) == retained, "far topology edits retain unchanged region geometry");
            SimpleAssert.eq(
                "",
                field(regionType, "projectionWorkKey").get(retained),
                "changed snapshot extent restarts projection even when camera parameters stay unchanged");
        } finally {
            regions.clear();
        }
    }

    private static void localHalo() throws ReflectiveOperationException {
        NBTTagList rows = new NBTTagList();
        rows.appendTag(cell(-1, 0, 0));
        rows.appendTag(cell(0, 0, 0));
        rows.appendTag(cell(1024, 0, 0));
        HologramState state = state(rows);
        Class<?> type = nested("WorldPreparation");
        Object preparation = construct(type, new Class<?>[] { HologramState.class }, state);
        Map<?, ?> regions = (Map<?, ?>) field(type, "regions").get(preparation);
        Object halo = regions.get(new HologramRegionCache.Key(-1, 0, 0));
        Object local = regions.get(new HologramRegionCache.Key(0, 0, 0));
        field(local.getClass(), "index").setInt(local, 1);
        Class<?> groupType = nested("WorldGroup");
        Object group = construct(groupType, new Class<?>[] { int.class, int.class, int.class }, 0, 0, 0);
        Method ready = type.getDeclaredMethod("ready", groupType);
        ready.setAccessible(true);
        Field worldState = field(HologramRenderer.class, "worldState"),
            worldIndex = field(HologramRenderer.class, "worldIndex");
        Object oldState = worldState.get(null);
        int oldIndex = worldIndex.getInt(null);
        try {
            worldState.set(null, state);
            worldIndex.setInt(null, 1);
            SimpleAssert.eq(false, ready.invoke(preparation, group), "negative-coordinate halo must be complete");
            field(halo.getClass(), "index").setInt(halo, 1);
            worldIndex.setInt(null, 2);
            SimpleAssert
                .eq(true, ready.invoke(preparation, group), "distant occupancy does not delay a complete local halo");
            Object far = regions.get(new HologramRegionCache.Key(64, 0, 0));
            SimpleAssert.eq(0, field(far.getClass(), "index").getInt(far), "far region stays unprepared");
        } finally {
            worldState.set(null, oldState);
            worldIndex.setInt(null, oldIndex);
        }
    }

    private static NBTTagCompound cell(int x, int y, int z) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", x);
        row.setInteger("y", y);
        row.setInteger("z", z);
        row.setString("id", "minecraft:stone");
        row.setString("wantId", "minecraft:stone");
        return row;
    }

    private static HologramState state(NBTTagList rows) {
        NBTTagCompound data = new NBTTagCompound();
        data.setTag("cells", rows);
        return new HologramState(data);
    }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(HologramRenderer.class.getName() + "$" + name);
    }

    private static Object construct(Class<?> type, Class<?>[] parameters, Object... args)
        throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
