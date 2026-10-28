package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real publication/dependency paths; synthetic cached regions, no GL or native geometry claims. */
public final class HologramTypedDependencyInvalidationRegression {

    private HologramTypedDependencyInvalidationRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramTypedDependencyInvalidationRegression.class, args)) return;
        run();
        System.out.println("HologramTypedDependencyInvalidationRegression PASS");
    }

    public static void run() throws Exception {
        staticReadsDoNotAmplifyModelPublication();
        tileReadsRetainTransitiveInvalidation();
        unknownTilesAreNeverKnownEmpty(false);
        unknownTilesAreNeverKnownEmpty(true);
        overflowRemainsConservative(false);
        overflowRemainsConservative(true);
        graphsShareOriginalBound();
        protectedReadersUseActualFamily();
        presenceReadsFollowSourceTopologyOnly();
    }

    private static void presenceReadsFollowSourceTopologyOnly() throws Exception {
        AuthorEditor editor = editor();
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current());
            CachedRegions cached = new CachedRegions(projection.view())) {
            HologramState before = projection.view();
            HologramBlockAccess access = new HologramBlockAccess(before);
            access.beginModel(before.cells.get(2));
            SimpleAssert.eq(true, access.contains(15, 0, 0), "remote source coordinate exists");
            SimpleAssert.eq(false, access.contains(1000, 0, 0), "remote empty coordinate is a presence dependency too");
            SimpleAssert.eq(true, access.endModel(), "source presence is independent of opaque appearance");
            SimpleAssert.eq(0, graph(projection, "readers").size(), "presence queries never pin native payloads");
            SimpleAssert
                .eq(2, graph(projection, "blockReaders").size(), "present and absent queries enter source graph");
            projection.request(before, before.cells.get(0));
            projection.advance(() -> 0);
            cached.present(2, "model-only publication preserves a remote presence reader");

            cached.populate(projection.view());
            long sourceId = projection.cellId(0);
            editor.select(Collections.singleton(sourceId));
            projection.update(editor.move(985, 0, 0));
            cached.absent(2, "moving source invalidates remote presence reader outside local halo");

            cached.populate(projection.view());
            access = new HologramBlockAccess(projection.view());
            access.beginModel(projection.view().cells.get(2));
            SimpleAssert.eq(true, access.contains(1000, 0, 0), "moved source presence is observable");
            access.endModel();
            editor.select(Collections.singleton(sourceId));
            projection.update(editor.delete());
            cached.absent(2, "removing source invalidates remote presence reader");

            cached.populate(projection.view());
            access = new HologramBlockAccess(projection.view());
            access.beginModel(projection.view().cells.get(2));
            SimpleAssert.eq(false, access.contains(1000, 0, 0), "removed coordinate is absent");
            access.endModel();
            JsonObject added = new JsonObject();
            added.addProperty("x", 1000);
            added.addProperty("y", 0);
            added.addProperty("z", 0);
            added.addProperty("block", "minecraft:stone");
            projection.update(editor.addCell(added));
            cached.absent(2, "adding source invalidates prior empty-coordinate presence reader");
        }
    }

    private static void staticReadsDoNotAmplifyModelPublication() throws Exception {
        AuthorEditor editor = editor();
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current());
            CachedRegions cached = new CachedRegions(projection.view())) {
            HologramState before = projection.view();
            HologramBlockAccess access = new HologramBlockAccess(before);
            for (int reader = 2; reader < 5; reader++) {
                HologramState.Cell input = before.cells.get(reader == 2 ? 0 : reader - 1);
                access.beginModel(before.cells.get(reader));
                access.getBlock(input.x, input.y, input.z);
                access.getBlockMetadata(input.x, input.y, input.z);
                SimpleAssert.eq(true, access.endModel(), "cold block/meta are known without appearance");
            }
            SimpleAssert.eq(0, graph(projection, "readers").size(), "static reads never enter tile graph");
            SimpleAssert.eq(3, graph(projection, "blockReaders").size(), "block/meta graph retains source readers");
            projection.request(before, before.cells.get(0));
            projection.advance(() -> 0);
            HologramState after = projection.view();
            SimpleAssert.eq(true, after.modelOnly, "appearance fallback does not change block/meta source");
            SimpleAssert.eq(
                1,
                HologramRegionCache.changedRegions(after)
                    .size(),
                "graph seed is exact changed region");
            cached.absent(0, "own appearance region retires");
            cached.absent(1, "neighbor halo retires for readiness/occlusion changes");
            for (int cell = 2; cell < 5; cell++) cached.present(cell, "static-only remote chain survives");

            // Source metadata changes still invalidate through the entire static dependency chain.
            cached.populate(after);
            JsonObject patch = new JsonObject();
            patch.addProperty("metadata", 1);
            projection.update(editor.patchCell(projection.cellId(0), patch));
            SimpleAssert.eq(false, projection.view().modelOnly, "source metadata publication is not model-only");
            for (int cell = 0; cell < 5; cell++) cached.absent(cell, "source edit preserves conservative invalidation");

            // Reads of absent coordinates survive too: adding/moving source topology can fill that coordinate.
            cached.populate(projection.view());
            HologramState.Cell reader = projection.view().cells.get(4);
            access = new HologramBlockAccess(projection.view());
            access.beginModel(reader);
            access.getBlock(1000, 0, 0);
            access.endModel();
            editor.select(Collections.singleton(projection.cellId(0)));
            projection.update(editor.move(985, 0, 0));
            cached.absent(4, "empty-coordinate block dependency invalidates on topology arrival");
        }
    }

    private static void tileReadsRetainTransitiveInvalidation() throws Exception {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor().current());
            CachedRegions cached = new CachedRegions(projection.view())) {
            HologramState before = projection.view();
            projection.dependency(before.cells.get(2), before.cells.get(0).x, 0, 0);
            projection.dependency(before.cells.get(3), before.cells.get(2).x, 0, 0);
            projection.blockDependency(before.cells.get(4), before.cells.get(3).x, 0, 0);
            projection.request(before, before.cells.get(0));
            projection.advance(() -> 0);
            cached.absent(2, "direct tile reader retires");
            cached.absent(3, "transitive tile/topology reader retires");
            cached.present(4, "tile invalidation does not cross into static-only graph");
        }
    }

    private static void unknownTilesAreNeverKnownEmpty(boolean budget) throws Exception {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor().current())) {
            HologramState state = projection.view();
            if (budget) {
                NBTTagCompound row = state.cells.get(0)
                    .tag();
                row.setInteger("nativeModelPending", 0);
                row.setString("renderKind", "localBudget");
                state = new HologramState(
                    projection,
                    state.data,
                    state,
                    state.cells.size(),
                    Collections.singletonMap(0, HologramState.Cell.takeOwnedLocalRow(0, row)));
            }
            HologramState.Cell input = state.cells.get(0), reader = state.cells.get(2);
            SimpleAssert.eq(
                false,
                input.block(false)
                    .hasTileEntity(input.wantMeta),
                "non-tile host fixture");
            HologramBlockAccess access = new HologramBlockAccess(state);
            access.beginModel(reader);
            try {
                SimpleAssert.eq(null, access.getTileEntity(input.x, input.y, input.z), "unknown tile unavailable");
                SimpleAssert.eq(false, access.endModel(), "even non-tile host pending payload must defer");
                SimpleAssert.eq(
                    0,
                    ((Set<?>) field(HologramBlockAccess.class, "loaded").get(access)).size(),
                    "unknown cannot enter known-empty tile cache");
                SimpleAssert.eq(
                    0,
                    ((Map<?, ?>) field(HologramBlockAccess.class, "failures").get(access)).size(),
                    "unknown cannot become terminal failure");
                SimpleAssert.eq(true, HologramBlockRender.deferred(reader, false), "native reader is deferred");
                SimpleAssert.eq(1, graph(projection, "readers").size(), "actual unknown read records tile dependency");
            } finally {
                ((Map<?, ?>) field(HologramBlockRender.class, "DEFERRED").get(null)).remove(reader);
            }
        }
    }

    private static void overflowRemainsConservative(boolean tile) throws Exception {
        AuthorEditor editor = editor();
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current());
            CachedRegions cached = new CachedRegions(projection.view())) {
            HologramState before = projection.view();
            // Put the production counter at its boundary; the real insertion enforces the bounded overflow rule.
            field(HologramAuthorProjection.class, tile ? "dependencyCount" : "blockDependencyCount")
                .setInt(projection, 65536);
            if (tile) projection.dependency(before.cells.get(4), 15, 0, 0);
            else projection.blockDependency(before.cells.get(4), 15, 0, 0);
            Field overflow = field(
                HologramAuthorProjection.class,
                tile ? "dependencyOverflow" : "blockDependencyOverflow");
            SimpleAssert.eq(true, overflow.getBoolean(projection), "edge overflow enters explicit conservative mode");
            SimpleAssert
                .eq(0, graph(projection, tile ? "readers" : "blockReaders").size(), "overflow drops bounded graph");
            projection.request(before, before.cells.get(0));
            projection.advance(() -> 0);
            if (tile) {
                for (int cell = 0; cell < 5; cell++) cached.absent(cell, "unknown tile dependencies retire owner");
                SimpleAssert.eq(false, overflow.getBoolean(projection), "retired owner permits fresh bounded graph");
            } else {
                cached.present(4, "static overflow does not poison pure-model publication");
                SimpleAssert
                    .eq(true, overflow.getBoolean(projection), "irrelevant overflow retained until source edit");
                JsonObject patch = new JsonObject();
                patch.addProperty("metadata", 1);
                projection.update(editor.patchCell(projection.cellId(0), patch));
                for (int cell = 0; cell < 5; cell++) cached.absent(cell, "source edit retires unknown static readers");
                SimpleAssert.eq(false, overflow.getBoolean(projection), "source retirement resets overflow");
            }
        }
    }

    private static void protectedReadersUseActualFamily() throws Exception {
        HologramNativeRenderRegistry.Adapter adapter = block -> block == Blocks.stone;
        HologramNativeRenderRegistry.register(adapter);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor().current())) {
            HologramState source = projection.view();
            NBTTagCompound row = source.cells.get(1)
                .tag();
            row.setString("status", "protected");
            row.setString("wantId", "minecraft:dirt");
            row.setTag("actualStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
            HologramState state = new HologramState(
                projection,
                source.data,
                source,
                source.cells.size(),
                Collections.singletonMap(1, HologramState.Cell.takeOwnedLocalRow(1, row)));
            SimpleAssert.eq(
                false,
                HologramSnapshotModelsClient.ready(state, state.cells.get(1), false),
                "protected actual native family retains its unknown-neighbor readiness gate in target view");
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static void graphsShareOriginalBound() throws Exception {
        for (boolean tile : new boolean[] { false, true }) {
            try (HologramAuthorProjection projection = new HologramAuthorProjection(editor().current())) {
                // Capacity already consumed by the other graph must not permit a second 65,536-edge cache.
                field(HologramAuthorProjection.class, tile ? "blockDependencyCount" : "dependencyCount")
                    .setInt(projection, 65536);
                HologramState.Cell reader = projection.view().cells.get(4);
                if (tile) projection.dependency(reader, 15, 0, 0);
                else projection.blockDependency(reader, 15, 0, 0);
                SimpleAssert.eq(
                    true,
                    field(HologramAuthorProjection.class, tile ? "dependencyOverflow" : "blockDependencyOverflow")
                        .getBoolean(projection),
                    "both dependency kinds share the original edge budget");
                SimpleAssert.eq(
                    0,
                    graph(projection, tile ? "readers" : "blockReaders").size(),
                    "cross-graph capacity denial still uses conservative overflow");
            }
        }
    }

    private static Map<?, ?> graph(HologramAuthorProjection projection, String name) throws Exception {
        return (Map<?, ?>) field(HologramAuthorProjection.class, name).get(projection);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static AuthorEditor editor() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        for (int x : new int[] { 15, 16, 72, 136, 200 }) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", x);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            if (x == 15) cell.addProperty("tileNbt", "not-base64!");
            cells.add(cell);
        }
        root.add("cells", cells);
        return new AuthorEditor(AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults()), 8);
    }

    private static final class CachedRegions implements AutoCloseable {

        final Field visible = field(HologramRenderer.class, "visibleState");
        final Object savedVisible = visible.get(null);
        final Map<HologramRegionCache.Key, Object> regions;
        final Map<HologramRegionCache.Key, Object> saved;
        final Constructor<?> constructor;
        final HologramState initial;

        @SuppressWarnings("unchecked")
        CachedRegions(HologramState state) throws Exception {
            initial = state;
            regions = (Map<HologramRegionCache.Key, Object>) field(HologramRenderer.class, "previewRegions").get(null);
            saved = new LinkedHashMap<>(regions);
            constructor = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion")
                .getDeclaredConstructor();
            constructor.setAccessible(true);
            populate(state);
        }

        void populate(HologramState state) throws Exception {
            visible.set(null, state);
            regions.clear();
            for (HologramRegionCache.Key key : state.regions.regions.keySet())
                regions.put(key, constructor.newInstance());
        }

        void absent(int cell, String message) {
            SimpleAssert.eq(false, regions.containsKey(HologramRegionCache.key(initial.cells.get(cell))), message);
        }

        void present(int cell, String message) {
            SimpleAssert.eq(true, regions.containsKey(HologramRegionCache.key(initial.cells.get(cell))), message);
        }

        public void close() throws Exception {
            visible.set(null, savedVisible);
            regions.clear();
            regions.putAll(saved);
        }
    }
}
