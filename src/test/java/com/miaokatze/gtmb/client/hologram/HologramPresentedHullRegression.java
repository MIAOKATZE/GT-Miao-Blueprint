package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual cheap preparation/projection and draw masks, without GL or native model preparation. */
public final class HologramPresentedHullRegression {

    private HologramPresentedHullRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramPresentedHullRegression.class, args)) return;
        run();
        System.out.println("HologramPresentedHullRegression PASS");
    }

    @SuppressWarnings("unchecked")
    public static void run() throws Exception {
        Map<HologramRegionCache.Key, Object> live = (Map<HologramRegionCache.Key, Object>) field(
            HologramRenderer.class,
            "previewRegions").get(null);
        Map<HologramRegionCache.Key, Object> saved = new LinkedHashMap<>(live);
        Field frame = field(HologramRenderer.class, "projectedFrame");
        Object previousFrame = frame.get(null);
        int[] move = (int[]) field(HologramRenderer.class, "moveOffset").get(null);
        int[] previousMove = move.clone();
        try {
            frame.set(null, new HologramEditorCameraFrame(0, 0, 800, 600, 25, 20, 1, 0, 0, 0, 100));
            live.clear();
            HologramState dense = state(1, 3);
            Object region = prepare(dense, dense.cells, Integer.MAX_VALUE, 0);
            live.put(HologramRegionCache.key(dense.cells.get(0)), region);
            project(region, "camera", Integer.MAX_VALUE);
            certify(dense, "camera");
            int faces = 0, edges = 0;
            for (Object outline : list(region, "outlines")) {
                faces += Integer.bitCount(drawFaces(outline));
                edges += Integer.bitCount(edges(outline, false));
            }
            SimpleAssert.eq(27, list(region, "outlines").size(), "all source/pick outline records retained");
            SimpleAssert.eq(162, list(region, "faces").size(), "all six pick faces remain retained");
            SimpleAssert.eq(54, faces, "3-cube volume draws only certified outer faces");
            SimpleAssert.eq(180, edges, "only edges incident to outer faces emitted, not 324 volume edges");
            Object center = outline(region, 2, 2, 2);
            SimpleAssert.eq(0, edges(center, false), "buried ordinary cell emits no lines");
            SimpleAssert.eq(4095, edges(center, true), "selection and related emphasis restore all twelve edges");
            for (int axis = 0; axis < 3; axis++) {
                move[axis] = 1;
                SimpleAssert.eq(63, drawFaces(center), "moving restores faces without rebuilding pick cache");
                SimpleAssert.eq(4095, edges(center, false), "moving restores all outlines");
                move[axis] = 0;
            }
            field(region.getClass(), "projectedKey").set(region, "");
            certify(dense, "camera");
            SimpleAssert.eq(63, drawFaces(center), "same-region partial repair cannot certify its shell");
            project(region, "camera", Integer.MAX_VALUE);
            certify(dense, "new-camera");
            SimpleAssert.eq(63, drawFaces(center), "old-camera output never certifies new-camera occlusion");

            live.clear();
            HologramState crossing = state(14, 3);
            for (Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry : crossing.regions.regions
                .entrySet()) live.put(entry.getKey(), prepare(crossing, entry.getValue(), Integer.MAX_VALUE, 0));
            Object left = live.get(new HologramRegionCache.Key(0, 0, 0));
            Object right = live.get(new HologramRegionCache.Key(1, 0, 0));
            project(left, "camera", Integer.MAX_VALUE);
            project(right, "camera", 1);
            certify(crossing, "camera");
            Object boundary = outline(left, 15, 2, 2);
            SimpleAssert.eq(32, drawFaces(boundary), "unpresented cross-region neighbor restores +X boundary only");
            SimpleAssert.eq(4, Integer.bitCount(edges(boundary, false)), "restored boundary emits its four edges");
            project(right, "camera", Integer.MAX_VALUE);
            certify(crossing, "camera");
            SimpleAssert.eq(0, drawFaces(boundary), "presented neighbor region permits buried boundary culling");
            frame.set(null, new HologramEditorCameraFrame(0, 0, 800, 600, 0, 0, 1, 0, 8, 8, 16));
            project(left, "clipped", Integer.MAX_VALUE);
            project(right, "clipped", Integer.MAX_VALUE);
            certify(crossing, "clipped", true);
            boundary = outline(left, 15, 2, 2);
            SimpleAssert.eq(32, drawFaces(boundary), "budgeted offscreen neighbor is not a presented occluder");
            frame.set(null, new HologramEditorCameraFrame(0, 0, 800, 600, 0, 0, 1, 16, 8, 8, 16));
            certify(crossing, "panned", true);
            SimpleAssert.eq(63, drawFaces(boundary), "new budgeted camera rejects retained old-camera projection");
            project(left, "panned", Integer.MAX_VALUE);
            certify(crossing, "panned", true);
            boundary = outline(left, 15, 2, 2);
            SimpleAssert.eq(32, drawFaces(boundary), "new-camera own shell waits for neighbor reprojection");
            project(right, "panned", Integer.MAX_VALUE);
            certify(crossing, "panned", true);
            SimpleAssert
                .eq(0, drawFaces(boundary), "revealed neighbor permits culling only after current-camera projection");
            project(left, "camera", Integer.MAX_VALUE);
            project(right, "camera", Integer.MAX_VALUE);
            certify(crossing, "camera");
            boundary = outline(left, 15, 2, 2);

            Object unrelated = construct(nested("PreviewRegion"));
            live.put(new HologramRegionCache.Key(2, 0, 0), unrelated);
            certify(crossing, "camera");
            SimpleAssert.eq(0, drawFaces(boundary), "unrelated incomplete region does not globally disable masks");
            field(right.getClass(), "projectedKey").set(right, "");
            certify(crossing, "camera");
            SimpleAssert.eq(32, drawFaces(boundary), "neighbor repair immediately restores boundary faces");
            live.remove(new HologramRegionCache.Key(1, 0, 0));
            certify(crossing, "camera");
            SimpleAssert.eq(32, drawFaces(boundary), "missing neighbor publication remains conservative");

            realNativeRepair(crossing, live);

            for (Block unknown : new Block[] { Blocks.glass, Blocks.stone_slab, Blocks.chest }) {
                live.clear();
                NBTTagCompound modified = source(1, 3);
                Method row = HologramCheapOcclusionRegression.class
                    .getDeclaredMethod("row", int.class, int.class, int.class, Block.class);
                row.setAccessible(true);
                modified.getTagList("cells", 10)
                    .func_150304_a(13, (NBTTagCompound) row.invoke(null, 2, 2, 2, unknown));
                HologramState conservative = new HologramState(modified);
                Object unknownRegion = prepare(conservative, conservative.cells, Integer.MAX_VALUE, 0);
                live.put(new HologramRegionCache.Key(0, 0, 0), unknownRegion);
                project(unknownRegion, "camera", Integer.MAX_VALUE);
                certify(conservative, "camera");
                SimpleAssert.eq(
                    63,
                    drawFaces(outline(unknownRegion, 2, 2, 2)),
                    "transparent/partial/native cells retain all provisional surfaces");
                SimpleAssert.eq(
                    4095,
                    edges(outline(unknownRegion, 2, 2, 2), false),
                    "unknown geometry retains full provisional outline");
                SimpleAssert.eq(
                    true,
                    (drawFaces(outline(unknownRegion, 1, 2, 2)) & 32) != 0,
                    "unknown neighbor never hides ordinary cell boundary");
            }
            for (String marker : new String[] { "prompt", "role", "nativeModelPending" }) {
                live.clear();
                NBTTagCompound modified = source(1, 3);
                NBTTagCompound changed = modified.getTagList("cells", 10)
                    .getCompoundTagAt(13);
                if ("nativeModelPending".equals(marker)) changed.setInteger(marker, 2);
                else changed.setString(marker, "role".equals(marker) ? "controller" : "visible prompt");
                HologramState conservative = new HologramState(modified);
                Object marked = prepare(conservative, conservative.cells, Integer.MAX_VALUE, 0);
                live.put(new HologramRegionCache.Key(0, 0, 0), marked);
                project(marked, "camera", Integer.MAX_VALUE);
                certify(conservative, "camera");
                SimpleAssert
                    .eq(4095, edges(outline(marked, 2, 2, 2), false), "role/prompt/pending cells retain full outline");
            }

            for (int sideCase = 0; sideCase < 3; sideCase++) {
                live.clear();
                NBTTagCompound mixedSource = source(1, 3);
                NBTTagCompound neighbor = mixedSource.getTagList("cells", 10)
                    .getCompoundTagAt(22);
                neighbor.setString("wantId", "minecraft:glass");
                neighbor.setTag(
                    "targetStack",
                    new net.minecraft.item.ItemStack(Blocks.glass).writeToNBT(new NBTTagCompound()));
                if (sideCase == 2) neighbor.setString("status", "protected");
                HologramState mixed = new HologramState(mixedSource);
                boolean current = sideCase == 1;
                Object mixedRegion = prepare(mixed, mixed.cells, Integer.MAX_VALUE, current ? 1 : 0, current);
                live.put(new HologramRegionCache.Key(0, 0, 0), mixedRegion);
                project(mixedRegion, "effective-side", Integer.MAX_VALUE, current);
                certify(mixed, "effective-side");
                SimpleAssert.eq(
                    sideCase == 0 ? 32 : 0,
                    drawFaces(outline(mixedRegion, 2, 2, 2)),
                    "target transparent cannot occlude; actual and protected actual opaque sides can");
            }

            live.clear();
            HologramState large = state(1, 30);
            for (Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry : large.regions.regions
                .entrySet()) {
                Object part = prepare(large, entry.getValue(), Integer.MAX_VALUE, 0);
                live.put(entry.getKey(), part);
                project(part, "large", Integer.MAX_VALUE);
            }
            certify(large, "large");
            int largeFaces = 0, largeEdges = 0, retained = 0;
            for (Object part : live.values()) for (Object face : list(part, "outlines")) {
                retained++;
                largeFaces += Integer.bitCount(drawFaces(face));
                largeEdges += Integer.bitCount(edges(face, false));
            }
            SimpleAssert.eq(27000, retained, "30-cube volume retains every provisional source/pick record");
            SimpleAssert.eq(5400, largeFaces, "30-cube shell quad work scales with surface, not 162000 volume faces");
            SimpleAssert.eq(21240, largeEdges, "30-cube exterior grid emits 21240 edges, not 324000 volume edges");
            stableRepairCompaction(large);

            live.clear();
            Object layer = prepare(dense, dense.cells, 2, 0);
            live.put(new HologramRegionCache.Key(0, 0, 0), layer);
            project(layer, "layer", Integer.MAX_VALUE);
            certify(dense, "layer");
            SimpleAssert.eq(3, drawFaces(outline(layer, 2, 2, 2)), "hidden layers cannot occlude top/bottom");
        } finally {
            live.clear();
            live.putAll(saved);
            frame.set(null, previousFrame);
            System.arraycopy(previousMove, 0, move, 0, move.length);
        }
    }

    private static void realNativeRepair(HologramState state, Map<HologramRegionCache.Key, Object> live)
        throws Exception {
        Field active = field(HologramRenderer.class, "previewPreparation");
        Field preview = field(HologramRenderer.class, "previewState");
        Object previous = active.get(null), previousPreview = preview.get(null);
        try {
            live.clear();
            Constructor<?> constructor = nested("PreviewPreparation").getDeclaredConstructor(HologramState.class);
            constructor.setAccessible(true);
            Object preparation = constructor.newInstance(state);
            active.set(null, preparation);
            Method advance = preparation.getClass()
                .getDeclaredMethod("advance", int.class, int.class, boolean.class, long.class, LongSupplier.class);
            advance.setAccessible(true);
            for (int index = 0; index < state.cells.size(); index++)
                advance.invoke(preparation, Integer.MAX_VALUE, 0, false, 0L, (LongSupplier) () -> 1L);
            SimpleAssert.eq(
                0,
                field(preparation.getClass(), "detailedCompleted").getInt(preparation),
                "production cheap admission must not claim any detailed completion");
            for (Object part : live.values()) project(part, "repair", Integer.MAX_VALUE);
            certify(state, "repair", true);
            Object left = live.get(new HologramRegionCache.Key(0, 0, 0));
            Object right = live.get(new HologramRegionCache.Key(1, 0, 0));
            Object boundary = outline(left, 15, 2, 2), old = outline(right, 16, 2, 2);
            HologramState.Cell changed = (HologramState.Cell) field(old.getClass(), "cell").get(old);
            SimpleAssert.eq(0, drawFaces(boundary), "production cheap publication certifies its completed shell");
            HologramRenderer.nativePreparationCellsChanged(state, Collections.singleton(changed));
            SimpleAssert.eq(
                false,
                list(right, "outlines").contains(old),
                "production native wake retires obsolete outline identity");
            certify(state, "repair", true);
            SimpleAssert
                .eq(32, drawFaces(boundary), "production repair revokes neighbor certificate before reprojection");
            project(right, "repair", Integer.MAX_VALUE);
            certify(state, "repair", true);
            Object replacement = outline(right, 16, 2, 2);
            SimpleAssert.eq(false, old == replacement, "repair projects a replacement rather than stale outline");
            SimpleAssert.eq(
                changed,
                field(replacement.getClass(), "cell").get(replacement),
                "same-state native wake retains exact immutable source identity");
            SimpleAssert.eq(63, drawFaces(replacement), "refreshCheap replacement conservatively retains all faces");
            SimpleAssert
                .eq(0, drawFaces(boundary), "unaffected boundary may recull only after repaired neighbor presentation");
        } finally {
            active.set(null, previous);
            preview.set(null, previousPreview);
        }
    }

    @SuppressWarnings("unchecked")
    private static void stableRepairCompaction(HologramState state) throws Exception {
        Object region = construct(nested("PreviewRegion"));
        List<Object> cells = (List<Object>) field(region.getClass(), "cells").get(region);
        Constructor<?> constructor = nested("PreviewCell").getDeclaredConstructor(
            HologramState.Cell.class,
            int.class,
            String.class,
            boolean.class,
            int.class,
            int.class);
        constructor.setAccessible(true);
        java.util.Set<Integer> removed = new java.util.HashSet<>();
        for (int index = 0; index < 4096; index++) {
            int pending = index % 2 == 0 ? 1 : 2;
            if (index == 4095) pending |= 4;
            cells.add(constructor.newInstance(state.cells.get(index), 63, "", false, 63, pending));
            if (index % 2 != 0) removed.add(index);
        }
        field(region.getClass(), "unresolved").setInt(region, 2048);
        field(region.getClass(), "budgetLimited").setInt(region, 2048);
        field(region.getClass(), "deferredVisibility").setBoolean(region, true);
        Method remove = region.getClass()
            .getDeclaredMethod("remove", java.util.Set.class);
        remove.setAccessible(true);
        remove.invoke(region, removed);
        SimpleAssert.eq(2048, cells.size(), "bulk repair retains exactly the unmodified cells");
        SimpleAssert
            .eq(2048, field(region.getClass(), "unresolved").getInt(region), "retained unresolved count unchanged");
        SimpleAssert.eq(
            0,
            field(region.getClass(), "budgetLimited").getInt(region),
            "removed budget count decremented exactly once");
        SimpleAssert.eq(
            false,
            field(region.getClass(), "deferredVisibility").getBoolean(region),
            "removed last deferred cell clears deferred flag");
        for (int index = 0; index < cells.size(); index++) {
            HologramState.Cell source = (HologramState.Cell) field(
                cells.get(index)
                    .getClass(),
                "cell").get(cells.get(index));
            SimpleAssert.eq(index * 2, source.index, "stable compaction preserves survivor admission order");
        }
        remove.invoke(region, removed);
        SimpleAssert.eq(
            2048,
            field(region.getClass(), "unresolved").getInt(region),
            "repeated removal cannot decrement survivor counters");
    }

    private static HologramState state(int startX, int size) throws Exception {
        return new HologramState(source(startX, size));
    }

    private static NBTTagCompound source(int startX, int size) throws Exception {
        NBTTagList rows = new NBTTagList();
        Method row = HologramCheapOcclusionRegression.class
            .getDeclaredMethod("row", int.class, int.class, int.class, Block.class);
        row.setAccessible(true);
        for (int x = startX; x < startX + size; x++) for (int y = 1; y <= size; y++)
            for (int z = 1; z <= size; z++) rows.appendTag((NBTTagCompound) row.invoke(null, x, y, z, Blocks.stone));
        NBTTagCompound data = new NBTTagCompound();
        data.setTag("cells", rows);
        return data;
    }

    private static Object prepare(HologramState state, List<HologramState.Cell> cells, int layer, int view)
        throws Exception {
        return prepare(state, cells, layer, view, false);
    }

    private static Object prepare(HologramState state, List<HologramState.Cell> cells, int layer, int view,
        boolean current) throws Exception {
        Object region = construct(nested("PreviewRegion"));
        Method prepare = HologramRenderer.class.getDeclaredMethod(
            "previewCell",
            HologramState.class,
            region.getClass(),
            HologramState.Cell.class,
            int.class,
            int.class,
            boolean.class,
            boolean.class,
            boolean.class);
        prepare.setAccessible(true);
        for (HologramState.Cell cell : cells)
            prepare.invoke(null, state, region, cell, layer, view, current, false, true);
        field(region.getClass(), "hintsComplete").setBoolean(region, true);
        return region;
    }

    private static void project(Object region, String key, int max) throws Exception {
        project(region, key, max, false);
    }

    @SuppressWarnings("unchecked")
    private static void project(Object region, String key, int max, boolean current) throws Exception {
        List<HologramTextures.Sample>[] sides = (List<HologramTextures.Sample>[]) new List<?>[6];
        for (int side = 0; side < 6; side++) sides[side] = Collections.emptyList();
        HologramTextures.Material cube = new HologramTextures.Material(
            sides,
            sides,
            HologramGeometry.cube(),
            false,
            false,
            false,
            false);
        HologramRenderer.PreviewAppearanceResolver resolver = new HologramRenderer.PreviewAppearanceResolver() {

            public boolean nativeReady(HologramState.Cell cell, boolean current) {
                throw new AssertionError("cheap projection prepared native model");
            }

            public HologramTextures.Material material(HologramState.Cell cell, boolean current, boolean detailed) {
                return cube;
            }
        };
        Method project = HologramRenderer.class.getDeclaredMethod(
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
        project.setAccessible(true);
        project.invoke(null, region, key, 63, current, true, Long.MAX_VALUE, max, (LongSupplier) () -> 0, resolver);
    }

    private static void certify(HologramState state, String key) throws Exception {
        certify(state, key, false);
    }

    private static void certify(HologramState state, String key, boolean budgeted) throws Exception {
        Method certify = HologramRenderer.class
            .getDeclaredMethod("updatePresentedOcclusion", HologramState.class, String.class, boolean.class);
        certify.setAccessible(true);
        certify.invoke(null, state, key, budgeted);
    }

    private static int drawFaces(Object face) throws Exception {
        Method method = face.getClass()
            .getDeclaredMethod("drawFaces");
        method.setAccessible(true);
        return (Integer) method.invoke(face);
    }

    private static int edges(Object face, boolean emphasized) throws Exception {
        Method method = HologramRenderer.class.getDeclaredMethod("previewOutlineEdges", face.getClass(), boolean.class);
        method.setAccessible(true);
        return (Integer) method.invoke(null, face, emphasized);
    }

    private static Object outline(Object region, int x, int y, int z) throws Exception {
        for (Object face : list(region, "outlines")) {
            HologramState.Cell cell = (HologramState.Cell) field(face.getClass(), "cell").get(face);
            if (cell.x == x && cell.y == y && cell.z == z) return face;
        }
        throw new AssertionError("missing outline");
    }

    private static Class<?> nested(String name) throws Exception {
        return Class.forName(HologramRenderer.class.getName() + "$" + name);
    }

    private static Object construct(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static List<?> list(Object owner, String name) throws Exception {
        return (List<?>) field(owner.getClass(), name).get(owner);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
