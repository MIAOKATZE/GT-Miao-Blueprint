package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Publication work and native-cache retirement contracts; no GL lists or native geometry claims. */
public final class HologramAuthorPublicationCostRegression {

    private HologramAuthorPublicationCostRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorPublicationCostRegression.class, args)) return;
        run();
        System.out.println("HologramAuthorPublicationCostRegression PASS");
    }

    public static void run() throws Exception {
        denseBatchKeepsOrderingAndIndexes();
        nativeHaloRetention(false);
        nativeHaloRetention(true);
        cachedTileProvenance(false, false, false, 15);
        cachedTileProvenance(true, false, false, 15);
        cachedTileProvenance(false, true, false, 15);
        cachedTileProvenance(false, false, true, 15);
        cachedTileProvenance(false, true, false, 96);
        cachedTileProvenance(false, true, true, 15);
    }

    private static void denseBatchKeepsOrderingAndIndexes() {
        List<HologramState.Cell> initial = new ArrayList<>();
        int[] all = new int[4097];
        for (int i = 0; i < 4096; i++) {
            initial.add(cell(i, i >> 8, (i >> 4) & 15, i & 15));
            all[i] = i;
        }
        initial.add(cell(4096, 128, 0, 0));
        all[4096] = 4096;
        HologramRegionCache base = HologramRegionCache.document(null, initial, all, Collections.emptyList());
        List<HologramState.Cell> next = new ArrayList<>(initial), before = new ArrayList<>();
        int[] changed = new int[2048];
        for (int i = 0; i < changed.length; i++) {
            int index = i * 2;
            HologramState.Cell old = initial.get(index);
            before.add(old);
            next.set(index, cell(index, old.x, old.y, old.z));
            changed[i] = index;
        }
        HologramRegionCache patched = HologramRegionCache.document(base, next, changed, before);
        List<HologramState.Cell> region = patched.regions.get(new HologramRegionCache.Key(0, 0, 0));
        SimpleAssert.eq(4096, region.size(), "dense replacement neither loses nor duplicates cells");
        for (int i = 0; i < 2048; i++) {
            SimpleAssert.that(region.get(i) == initial.get(i * 2 + 1), "old survivors retain order and identity");
            SimpleAssert.that(region.get(2048 + i) == next.get(i * 2), "new rows retain changed-array order");
        }
        for (HologramState.Cell cell : next)
            SimpleAssert.that(patched.at(cell.x, cell.y, cell.z) == cell, "position directory uses exact new rows");
        SimpleAssert.that(base.at(0, 0, 0) == initial.get(0), "old immutable region is unchanged");
        HologramRegionCache.Key far = new HologramRegionCache.Key(8, 0, 0);
        SimpleAssert.that(base.regions.get(far) == patched.regions.get(far), "untouched region remains shared");

        List<HologramState.Cell> full = new ArrayList<>(next);
        int[] fullChanged = new int[4096];
        for (int i = 0; i < fullChanged.length; i++) {
            HologramState.Cell old = next.get(i);
            full.set(i, cell(i, old.x, old.y, old.z));
            fullChanged[i] = i;
        }
        HologramRegionCache fullIndex = HologramRegionCache.document(patched, full, fullChanged, next.subList(0, 4096));
        List<HologramState.Cell> fullRegion = fullIndex.regions.get(new HologramRegionCache.Key(0, 0, 0));
        for (int i = 0; i < fullChanged.length; i++)
            SimpleAssert.that(fullRegion.get(i) == full.get(i), "full dense batch preserves replacement order");

        // A move touches old and new region inputs without disturbing the unrelated region.
        HologramState.Cell moved = cell(0, 16, 0, 0);
        List<HologramState.Cell> moving = new ArrayList<>(next);
        moving.set(0, moved);
        HologramRegionCache movedIndex = HologramRegionCache
            .document(patched, moving, new int[] { 0 }, Collections.singletonList(next.get(0)));
        SimpleAssert.eq(null, movedIndex.at(0, 0, 0), "move removes old coordinate");
        SimpleAssert.that(movedIndex.at(16, 0, 0) == moved, "move inserts new region coordinate");

        // Delete the dense region and compact the surviving tail into render slot zero.
        HologramState.Cell tail = cell(0, 128, 0, 0);
        HologramRegionCache deleted = HologramRegionCache
            .document(patched, Collections.singletonList(tail), new int[] { 0 }, next);
        SimpleAssert.eq(1, deleted.regions.size(), "empty regions are removed after delete/tail compaction");
        SimpleAssert.that(deleted.at(128, 0, 0) == tail, "tail swap uses the new dense slot");
        SimpleAssert.eq(128, deleted.bounds()[0], "bounds shrink to surviving region");
        List<HologramState.Cell> duplicate = new ArrayList<>(next);
        duplicate.set(0, cell(0, next.get(1).x, next.get(1).y, next.get(1).z));
        boolean refused = false;
        try {
            HologramRegionCache.document(patched, duplicate, new int[] { 0 }, Collections.singletonList(next.get(0)));
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        SimpleAssert.that(refused, "duplicate topology remains rejected before publication");
        SimpleAssert.that(patched.at(0, 0, 0) == next.get(0), "failed duplicate does not mutate prior index");
    }

    private static HologramState.Cell cell(int index, int x, int y, int z) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", x);
        row.setInteger("y", y);
        row.setInteger("z", z);
        row.setString("id", "minecraft:stone");
        row.setString("wantId", "minecraft:stone");
        return new HologramState.Cell(index, row);
    }

    private static void nativeHaloRetention(boolean overflow) throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray rows = new JsonArray();
        for (int x : new int[] { 15, 16, 48, 80 }) {
            JsonObject row = new JsonObject();
            row.addProperty("x", x);
            row.addProperty("y", 0);
            row.addProperty("z", 0);
            row.addProperty("block", "minecraft:stone");
            if (x == 15) row.addProperty("tileNbt", "unsupported-fixture-payload");
            rows.add(row);
        }
        source.add("cells", rows);
        AuthorEditor editor = new AuthorEditor(
            AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()),
            8);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current());
            CachedNative cached = new CachedNative(projection.view())) {
            HologramState before = projection.view();
            HologramState.Cell input = before.cells.get(0);
            HologramBlockAccess access = new HologramBlockAccess(before);
            access.beginModel(before.cells.get(1));
            access.getBlock(input.x, input.y, input.z);
            access.getBlockMetadata(input.x, input.y, input.z);
            access.contains(input.x, input.y, input.z);
            access.endModel();
            access.beginModel(before.cells.get(2));
            access.getTileEntity(input.x, input.y, input.z);
            SimpleAssert.eq(false, access.endModel(), "unknown actual tile read retains defer and dependency");
            projection.blockDependency(before.cells.get(3), input.x, input.y, input.z);
            if (overflow) field(HologramAuthorProjection.class, "dependencyOverflow").setBoolean(projection, true);
            projection.request(before, input);
            projection.advance(() -> 0);
            HologramState after = projection.view();
            SimpleAssert.that(after.modelOnly, "test publication changes appearance only");
            cached.nativePresent(before.cells.get(0), false, "changed native region retires");
            cached.nativePresent(
                before.cells.get(1),
                !overflow,
                "halo-only native list survives unless graph overflowed");
            cached.nativePresent(before.cells.get(2), false, "actual unknown tile reader retires");
            cached.nativePresent(
                before.cells.get(3),
                !overflow,
                "static-only native reader survives model-only publication");
            SimpleAssert.that(
                !cached.regions.containsKey(HologramRegionCache.key(before.cells.get(1))),
                "shell halo still retires for readiness and material occlusion");
            SimpleAssert.that(
                field(HologramBlockRender.class, "snapshot").get(null) == after,
                "native cache owner advances despite retained lists");
            if (!overflow) {
                cached.populate(after);
                JsonObject patch = new JsonObject();
                patch.addProperty("metadata", 1);
                projection.update(editor.patchCell(projection.cellId(0), patch));
                for (HologramState.Cell cell : after.cells)
                    cached.nativePresent(cell, false, "source edit retains full halo and both dependency graphs");
            }
        }
    }

    private static void cachedTileProvenance(boolean staticRead, boolean unknown, boolean fail, int inputX)
        throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray rows = new JsonArray();
        for (int x : new int[] { inputX, 16, 48 }) {
            JsonObject row = new JsonObject();
            row.addProperty("x", x);
            row.addProperty("y", 0);
            row.addProperty("z", 0);
            row.addProperty("block", "minecraft:stone");
            rows.add(row);
        }
        source.add("cells", rows);
        AuthorEditor editor = new AuthorEditor(
            AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()),
            8);
        int[] bLoads = { 0 };
        HologramNativeRenderRegistry.Adapter adapter = new HologramNativeRenderRegistry.Adapter() {

            public boolean supports(Block block) {
                return block == Blocks.stone;
            }

            public TileEntity createTile(Block block, int metadata, World isolatedWorld) {
                return new DependencyTile();
            }

            public void loadTile(TileEntity tile, NBTTagCompound payload) {
                DependencyTile visual = (DependencyTile) tile;
                visual.value = payload.getInteger("value");
                if (tile.xCoord != 16) return;
                bLoads[0]++;
                World world = tile.getWorldObj();
                if (staticRead) {
                    visual.value = world.getBlockMetadata(inputX, 0, 0);
                    SimpleAssert.that(world.getBlock(inputX, 0, 0) == Blocks.stone, "cached tile sees source block");
                    SimpleAssert.that(world.blockExists(inputX, 0, 0), "cached tile sees source presence");
                } else {
                    DependencyTile input = (DependencyTile) world.getTileEntity(inputX, 0, 0);
                    visual.value = input == null ? -1 : input.value;
                }
                if (fail) throw new IllegalStateException("synthetic visual reader failed after dependency read");
            }
        };
        HologramNativeRenderRegistry.register(adapter);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current())) {
            Map<Integer, HologramState.Cell> ready = new LinkedHashMap<>();
            ready.put(0, tileRow(projection.view().cells.get(0), 17, unknown));
            ready.put(1, tileRow(projection.view().cells.get(1), 0, false));
            publishModels(projection, ready);
            HologramState before = projection.view();
            try (CachedNative cached = new CachedNative(before)) {
                HologramBlockAccess access = isolatedAccess(before);
                if (!unknown) {
                    DependencyTile input = (DependencyTile) access.getTileEntity(inputX, 0, 0);
                    SimpleAssert.that(input != null && input.value == 17, "prime C through actual isolated hydration");
                }
                HologramState.Cell b = before.cells.get(1), a = before.cells.get(2);
                access.beginModel(a);
                TileEntity firstB = access.getTileEntity(16, 0, 0);
                boolean complete = access.endModel();
                if (fail && !unknown) {
                    SimpleAssert.eq(null, firstB, "failed adapter load publishes no detached tile");
                    SimpleAssert.that(
                        !access.failure(16, 0, 0)
                            .isEmpty(),
                        "failed adapter preserves explicit failure");
                    SimpleAssert.eq(
                        0,
                        ((java.util.Deque<?>) field(HologramBlockAccess.class, "tileReaders").get(access)).size(),
                        "failure pops the cached-tile owner scope");
                    access.beginModel(a);
                    access.getBlock(160, 0, 0);
                    access.endModel();
                    @SuppressWarnings("unchecked")
                    Map<HologramRegionCache.Key, java.util.Set<HologramRegionCache.Key>> sourceReaders = (Map<HologramRegionCache.Key, java.util.Set<HologramRegionCache.Key>>) field(
                        HologramAuthorProjection.class,
                        "blockReaders").get(projection);
                    java.util.Set<HologramRegionCache.Key> later = sourceReaders
                        .get(new HologramRegionCache.Key(10, 0, 0));
                    SimpleAssert.that(
                        later.contains(HologramRegionCache.key(a)) && !later.contains(HologramRegionCache.key(b)),
                        "failed B owner cannot leak into a later unrelated model read");
                    return;
                }
                SimpleAssert.eq(!unknown, complete, "nested unknown still marks the root model incomplete");
                if (unknown) {
                    SimpleAssert.eq(null, firstB, "unknown C cannot publish B as a known cached tile");
                    SimpleAssert.that(HologramBlockRender.deferred(a, false), "root A retains defer marker");
                    SimpleAssert.that(
                        HologramSnapshotModelsClient.ready(before, a, false),
                        "A's local readiness neighborhood does not include its indirect distant C input");
                    assertNoTileCache(access, "16:0:0");
                    @SuppressWarnings("unchecked")
                    java.util.Set<Long> queued = (java.util.Set<Long>) field(HologramAuthorProjection.class, "queued")
                        .get(projection);
                    SimpleAssert
                        .that(queued.contains(before.cells.get(0).nativeModelId), "first unknown read requests C");
                    // Saturate only the queue-capacity boundary; no synthetic entry is ever executed.
                    queued.clear();
                    for (long i = 1; i <= 512; i++) queued.add(-i);
                    access.beginModel(a);
                    SimpleAssert.eq(null, access.getTileEntity(16, 0, 0), "retry A cannot see an incomplete cached B");
                    SimpleAssert
                        .eq(false, access.endModel(), "retry A still defers outside C's local ready neighborhood");
                    assertNoTileCache(access, "16:0:0");
                    SimpleAssert.eq(512, queued.size(), "retry respects the original saturated queue bound");
                    queued.clear();
                    access.beginModel(b);
                    SimpleAssert.eq(null, access.getTileEntity(16, 0, 0), "B's own retry remains unknown");
                    SimpleAssert.eq(false, access.endModel(), "B retry cannot turn a prior unknown into ready");
                    SimpleAssert.that(
                        queued.contains(before.cells.get(0).nativeModelId),
                        "later retry renews C demand after queue capacity returns");
                    SimpleAssert.eq(3, bLoads[0], "each retry makes one bounded native attempt, without recursion");
                    assertNoTileCache(access, "16:0:0");
                } else {
                    SimpleAssert.that(firstB != null, "A hydrates B through the public adapter");
                    access.beginModel(b);
                    SimpleAssert.that(
                        access.getTileEntity(16, 0, 0) == firstB,
                        "B subsequently reads the complete cached tile");
                    SimpleAssert.eq(true, access.endModel(), "complete cached B remains ready");
                    SimpleAssert.eq(1, bLoads[0], "complete cached B does not repeat its dependency-producing load");
                    SimpleAssert.eq(
                        staticRead ? 0 : 17,
                        ((DependencyTile) firstB).value,
                        "B cached visual really consumed C's source or tile fact");
                }
                @SuppressWarnings("unchecked")
                Map<HologramRegionCache.Key, java.util.Set<HologramRegionCache.Key>> graph = (Map<HologramRegionCache.Key, java.util.Set<HologramRegionCache.Key>>) field(
                    HologramAuthorProjection.class,
                    staticRead ? "blockReaders" : "readers").get(projection);
                java.util.Set<HologramRegionCache.Key> readers = graph
                    .get(HologramRegionCache.key(before.cells.get(0)));
                SimpleAssert.that(
                    readers != null && readers.contains(HologramRegionCache.key(b)),
                    "cached B owns the nested dependency, not only the first requesting model A");
                if (staticRead) {
                    JsonObject patch = new JsonObject();
                    patch.addProperty("metadata", 1);
                    projection.update(editor.patchCell(projection.cellId(0), patch));
                } else {
                    publishModels(projection, Collections.singletonMap(0, tileRow(before.cells.get(0), 23, false)));
                    SimpleAssert.that(projection.view().modelOnly, "C refresh is a genuine model-only publication");
                }
                cached.nativePresent(b, false, "C change retires B despite B's later dependency-free cache hit");
                cached.nativePresent(a, false, "C change retires original root A too");
                if (unknown && !fail) {
                    HologramBlockAccess fresh = isolatedAccess(projection.view());
                    DependencyTile readyC = (DependencyTile) fresh.getTileEntity(inputX, 0, 0);
                    SimpleAssert.that(readyC != null && readyC.value == 23, "fresh snapshot hydrates now-ready C");
                    fresh.beginModel(projection.view().cells.get(2));
                    DependencyTile readyB = (DependencyTile) fresh.getTileEntity(16, 0, 0);
                    SimpleAssert
                        .that(readyB != null && readyB.value == 23, "B can recover using complete neighbor data");
                    SimpleAssert.eq(true, fresh.endModel(), "fresh complete inputs allow root A to finish");
                }
                SimpleAssert.eq(
                    0,
                    ((java.util.Deque<?>) field(HologramBlockAccess.class, "tileReaders").get(access)).size(),
                    "cached-tile dependency scope is restored after hydration and cache hits");
            }
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static void assertNoTileCache(HologramBlockAccess access, String key) throws Exception {
        SimpleAssert.that(
            !((Map<?, ?>) field(HologramBlockAccess.class, "tiles").get(access)).containsKey(key),
            "incomplete visual tile is not cached");
        SimpleAssert.that(
            !((java.util.Set<?>) field(HologramBlockAccess.class, "loaded").get(access)).contains(key),
            "incomplete visual retains no loaded-empty marker");
        SimpleAssert.that(
            !((java.util.Set<?>) field(HologramBlockAccess.class, "topologyPrepared").get(access)).contains(key),
            "incomplete topology is not marked prepared");
        SimpleAssert.that(
            !((Map<?, ?>) field(HologramBlockAccess.class, "failures").get(access)).containsKey(key),
            "unknown dependency is not made a terminal failure");
    }

    private static HologramState.Cell tileRow(HologramState.Cell source, int value, boolean pending) {
        NBTTagCompound row = source.tag(), payload = new NBTTagCompound(), display = new NBTTagCompound();
        payload.setInteger("value", value);
        display.setTag("nativeTile", payload);
        row.setTag("actualDisplay", display);
        row.setTag("targetDisplay", display);
        row.setInteger("nativeModelPending", pending ? 3 : 0);
        return HologramState.Cell.takeOwnedLocalRow(source.index, row);
    }

    private static void publishModels(HologramAuthorProjection projection, Map<Integer, HologramState.Cell> rows)
        throws Exception {
        Method publish = HologramAuthorProjection.class
            .getDeclaredMethod("publish", NBTTagCompound.class, Map.class, boolean.class);
        publish.setAccessible(true);
        publish.invoke(projection, projection.view().data, rows, true);
    }

    private static HologramBlockAccess isolatedAccess(HologramState state) throws Exception {
        HologramBlockAccess access = new HologramBlockAccess(state);
        // As in NativePreviewIsolationRegression, avoid requiring a running FML provider in a CPU test.
        // Reads still execute the actual detached-world delegation and public native adapter hydration.
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        HologramRenderWorld world = (HologramRenderWorld) unsafe.allocateInstance(HologramRenderWorld.class);
        world.isRemote = true;
        field(HologramRenderWorld.class, "access").set(world, access);
        field(HologramBlockAccess.class, "renderWorld").set(access, world);
        return access;
    }

    private static final class DependencyTile extends TileEntity {

        int value;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class CachedNative implements AutoCloseable {

        final Map<HologramState.Cell, Object> models;
        final Map<HologramState.Cell, Object> savedModels;
        final Map<HologramState.Cell, Integer> deferred;
        final Map<HologramState.Cell, Integer> savedDeferred;
        final Map<HologramRegionCache.Key, Object> regions;
        final Map<HologramRegionCache.Key, Object> savedRegions;
        final Map<Field, Object> savedFields = new LinkedHashMap<>();
        final Class<?> model;
        final Constructor<?> region;

        @SuppressWarnings("unchecked")
        CachedNative(HologramState state) throws Exception {
            models = (Map<HologramState.Cell, Object>) field(HologramBlockRender.class, "MODELS").get(null);
            savedModels = new IdentityHashMap<>(models);
            deferred = (Map<HologramState.Cell, Integer>) field(HologramBlockRender.class, "DEFERRED").get(null);
            savedDeferred = new IdentityHashMap<>(deferred);
            regions = (Map<HologramRegionCache.Key, Object>) field(HologramRenderer.class, "previewRegions").get(null);
            savedRegions = new LinkedHashMap<>(regions);
            for (String name : new String[] { "snapshot", "target", "actual" }) {
                Field field = field(HologramBlockRender.class, name);
                savedFields.put(field, field.get(null));
            }
            Field visible = field(HologramRenderer.class, "visibleState");
            savedFields.put(visible, visible.get(null));
            model = Class.forName(HologramBlockRender.class.getName() + "$Model");
            region = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion")
                .getDeclaredConstructor();
            region.setAccessible(true);
            populate(state);
        }

        void populate(HologramState state) throws Exception {
            models.clear();
            deferred.clear();
            regions.clear();
            for (HologramState.Cell cell : state.cells) models.put(cell, Array.newInstance(model, 2));
            for (HologramRegionCache.Key key : state.regions.regions.keySet()) regions.put(key, region.newInstance());
            field(HologramBlockRender.class, "snapshot").set(null, state);
            field(HologramRenderer.class, "visibleState").set(null, state);
        }

        void nativePresent(HologramState.Cell cell, boolean expected, String message) {
            SimpleAssert.eq(expected, models.containsKey(cell), message);
        }

        public void close() throws Exception {
            models.clear();
            models.putAll(savedModels);
            deferred.clear();
            deferred.putAll(savedDeferred);
            regions.clear();
            regions.putAll(savedRegions);
            for (Map.Entry<Field, Object> entry : savedFields.entrySet()) entry.getKey()
                .set(null, entry.getValue());
        }
    }
}
