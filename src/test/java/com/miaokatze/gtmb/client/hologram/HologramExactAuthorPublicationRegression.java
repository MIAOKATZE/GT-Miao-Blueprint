package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual request/advance/publication bridge, with detached native tiles and zero-GL cache sentinels. */
public final class HologramExactAuthorPublicationRegression {

    private HologramExactAuthorPublicationRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramExactAuthorPublicationRegression.class, args)) return;
        run();
        System.out.println("HologramExactAuthorPublicationRegression PASS");
    }

    @SuppressWarnings("unchecked")
    public static void run() throws Exception {
        int[] positions = { 0, 4, 8, 12, 32, 64, 96 };
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray rows = new JsonArray();
        for (int x : positions) {
            JsonObject row = new JsonObject();
            row.addProperty("x", x);
            row.addProperty("y", 0);
            row.addProperty("z", 0);
            row.addProperty("block", "minecraft:stone");
            NBTTagCompound payload = new NBTTagCompound();
            payload.setInteger("value", x + 1);
            if (x == 64) payload.setInteger("depends", 32);
            row.addProperty("tileNbt", Base64.getEncoder().encodeToString(HologramSnapshotCodec.compress(payload)));
            rows.add(row);
        }
        source.add("cells", rows);
        AuthorEditor editor = new AuthorEditor(
            AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()), 8);
        Map<Integer, Integer> loads = new LinkedHashMap<>();
        HologramNativeRenderRegistry.Adapter adapter = new HologramNativeRenderRegistry.Adapter() {

            public boolean supports(Block block) { return block == Blocks.stone; }
            public boolean requiresTile(Block block, int metadata) { return true; }
            public TileEntity createTile(Block block, int metadata, World world) { return new Probe(); }
            public void loadTile(TileEntity tile, NBTTagCompound payload) {
                loads.put(tile.xCoord, loads.getOrDefault(tile.xCoord, 0) + 1);
                Probe probe = (Probe) tile;
                probe.value = payload.getInteger("value");
                if (payload.hasKey("depends")) {
                    Probe input = (Probe) tile.getWorldObj().getTileEntity(payload.getInteger("depends"), 0, 0);
                    probe.value = input == null ? -1 : input.value;
                }
            }
        };
        HologramNativeRenderRegistry.register(adapter);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current());
            Active active = new Active(projection.view())) {
            // A, the suspended same-region lane L, distant D->E chain, and far control first become real payloads.
            for (int index : new int[] { 0, 3, 4, 5, 6 }) demand(projection, index);
            HologramState before = projection.view();
            Probe a = (Probe) active.target.getTileEntity(0, 0, 0);
            Probe d = (Probe) active.target.getTileEntity(32, 0, 0);
            Probe e = (Probe) active.target.getTileEntity(64, 0, 0);
            Probe far = (Probe) active.target.getTileEntity(96, 0, 0);
            Probe actualA = (Probe) active.actual.getTileEntity(0, 0, 0);
            SimpleAssert.that(a != null && d != null && e != null && far != null, "prepared controls are READY");
            active.actual.prepare(before.cells.get(3), new HologramNativePreparation.Budget(1, 1, () -> 0));
            Object lane = field(HologramBlockAccess.class, "preparation").get(active.actual);
            int laneDepth = ((HologramNativePreparation<?>) lane).depth();
            Object laneTile = ((Map<?, ?>) field(HologramBlockAccess.class, "tiles").get(active.actual)).get("12:0:0");
            SimpleAssert.that(laneDepth > 0 && laneTile != null, "same-region DATA lane suspends before READY");
            active.seed(before);
            Object aModel = active.models.get(before.cells.get(0));
            Object aOutput = active.output(before.cells.get(0));
            int aLoads = loads.get(0);

            // A genuine renderer read of cold B records D->B; E already recorded E->D during its native load.
            active.target.beginModel(before.cells.get(4));
            SimpleAssert.eq(null, active.target.getTileEntity(4, 0, 0), "unknown B cannot be read as READY");
            SimpleAssert.eq(false, active.target.endModel(), "unknown B keeps native output incomplete");
            field(HologramRenderer.class, "projectedFrame").set(null,
                new HologramEditorCameraFrame(0, 0, 100, 100, 0, 0, 1, 64, 0, 0, 16));
            field(HologramRenderer.class, "visibleLayer").setInt(null, Integer.MAX_VALUE);
            field(HologramRenderer.class, "visibleView").setInt(null, 0);
            field(HologramRenderer.class, "visibleMode").setInt(null, 0);
            java.lang.reflect.Method retained = HologramAuthorProjection.class
                .getDeclaredMethod("retainedVisible", HologramState.Cell.class);
            retained.setAccessible(true);
            SimpleAssert.eq(true, retained.invoke(projection, before.cells.get(1)),
                "hidden B remains pinned through hidden D to visible E");
            SimpleAssert.eq(false, retained.invoke(projection, before.cells.get(0)),
                "same-region independent hidden A is not pinned by B's readers");
            field(HologramRenderer.class, "projectedFrame").set(null, null);
            demand(projection, 1);
            HologramState afterB = projection.view();
            SimpleAssert.that(afterB.modelOnly && active.target.owns(afterB, false)
                && active.actual.owns(afterB, true), "real publication keeps both exact-side owners");
            SimpleAssert.that(active.target.getTileEntity(0, 0, 0) == a
                && active.actual.getTileEntity(0, 0, 0) == actualA, "same-region independent A READY survives B");
            SimpleAssert.that(active.models.get(before.cells.get(0)) == aModel,
                "same-region compiled cache identity survives B");
            SimpleAssert.that(active.output(afterB.cells.get(0)) == aOutput,
                "same-region completed detail output survives real B publication");
            SimpleAssert.eq(aLoads, (int) loads.get(0), "B publication never reloads A");
            SimpleAssert.that(active.target.getTileEntity(32, 0, 0) != d
                && active.target.getTileEntity(64, 0, 0) != e, "actual cross-region reader closure D and E rebuilds");
            SimpleAssert.that(!active.models.containsKey(before.cells.get(4))
                && !active.models.containsKey(before.cells.get(5)), "dependent cache handles retire transitively");
            SimpleAssert.that(active.target.getTileEntity(96, 0, 0) == far, "far independent READY survives");
            SimpleAssert.eq(laneDepth, ((HologramNativePreparation<?>) lane).depth(),
                "unrelated same-region continuation retains its exact lane");
            SimpleAssert.that(((Map<?, ?>) field(HologramBlockAccess.class, "tiles").get(active.actual))
                .get("12:0:0") == laneTile, "unrelated same-region DATA object survives");

            Probe b = (Probe) active.target.getTileEntity(4, 0, 0);
            demand(projection, 2);
            HologramState complete = projection.view();
            SimpleAssert.that(active.target.getTileEntity(0, 0, 0) == a
                && active.target.getTileEntity(4, 0, 0) == b, "independent C publication retains A and B");
            SimpleAssert.that(active.output(complete.cells.get(0)) == aOutput,
                "A detail survives sequential B and C publications");
            SimpleAssert.that(active.models.get(before.cells.get(0)) == aModel,
                "A native handle survives sequential B and C publications");
            for (int repeat = 0; repeat < 8; repeat++) {
                for (HologramState.Cell cell : complete.cells) projection.request(complete, cell);
                SimpleAssert.eq(false, projection.advance(() -> 0), "unchanged READY demands produce no publication");
                SimpleAssert.that(projection.view() == complete, "finite work converges to stable immutable view");
            }

            // An absent-coordinate tile read is still source provenance, not permission to keep stale native output.
            active.target.beginModel(complete.cells.get(0));
            active.target.getTileEntity(200, 0, 0);
            active.target.endModel();
            Map<HologramRegionCache.Key, Set<Long>> exact = (Map<HologramRegionCache.Key, Set<Long>>) field(
                HologramAuthorProjection.class, "exactReaders").get(projection);
            SimpleAssert.that(exact.get(new HologramRegionCache.Key(200, 0, 0))
                .contains(complete.cells.get(0).nativeModelId), "absent tile coordinate retains exact provenance");
            editor.select(Collections.singleton(projection.cellId(2)));
            projection.update(editor.move(192, 0, 0));
            SimpleAssert.eq(false, projection.view().modelOnly, "real coordinate/source edit is not model-only");
            SimpleAssert.eq(false, active.models.containsKey(complete.cells.get(0)),
                "source appearance at previously absent coordinate retires its reader conservatively");
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
        boundedQueryKeepsProvenance();
        exactTierStaysBoundedAndFallsBack();
    }

    private static com.miaokatze.gtmb.blueprint.editor.AuthorDocument document(int count, int spacing, boolean far)
        throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        JsonArray rows = new JsonArray();
        for (int index = 0; index < count + (far ? 1 : 0); index++) {
            JsonObject row = new JsonObject();
            row.addProperty("x", index == count ? 4096 : index * spacing);
            row.addProperty("y", 0);
            row.addProperty("z", 0);
            row.addProperty("block", "minecraft:stone");
            row.addProperty("tileNbt", "bounded-test-invalid-payload");
            rows.add(row);
        }
        root.add("cells", rows);
        return AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults());
    }

    private static void boundedQueryKeepsProvenance() throws Exception {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(70, 16, false));
            Active active = new Active(projection.view())) {
            HologramState state = projection.view();
            for (int index = 0; index < 69; index++)
                projection.dependency(state.cells.get(index + 1), state.cells.get(index).x, 0, 0);
            field(HologramRenderer.class, "projectedFrame").set(null,
                new HologramEditorCameraFrame(0, 0, 100, 100, 0, 0, 1, 10000, 0, 0, 16));
            field(HologramRenderer.class, "visibleLayer").setInt(null, Integer.MAX_VALUE);
            java.lang.reflect.Method retained = HologramAuthorProjection.class
                .getDeclaredMethod("retainedVisible", HologramState.Cell.class);
            retained.setAccessible(true);
            for (int repeat = 0; repeat < 8; repeat++) {
                SimpleAssert.eq(true, retained.invoke(projection, state.cells.get(0)),
                    "more than 64 hidden reader edges conservatively retain the candidate");
                SimpleAssert.eq(69, field(HologramAuthorProjection.class, "exactDependencyCount").getInt(projection),
                    "query exhaustion does not destroy and relearn sound provenance");
                SimpleAssert.eq(false, field(HologramAuthorProjection.class, "dependencyOverflow").getBoolean(projection),
                    "query deadline is not actual graph overflow");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void exactTierStaysBoundedAndFallsBack() throws Exception {
        for (long residentLimit : new long[] { 32L << 20, 1L << 20 }) {
            try (HologramAuthorProjection projection = new HologramAuthorProjection(document(257, 1, true), residentLimit);
                Active active = new Active(projection.view())) {
                HologramState state = projection.view();
                // 66,049 real exact edges but only 17x17 coarse edges: do not reuse the old region-edge cap.
                for (int input = 0; input < 257; input++) for (int reader = 0; reader < 257; reader++)
                    projection.dependency(state.cells.get(reader), input, 0, 0);
                boolean boundedFallback = residentLimit == (1L << 20);
                int count = field(HologramAuthorProjection.class, "exactDependencyCount").getInt(projection);
                SimpleAssert.eq(boundedFallback ? 65536 : 66049, count, "exact metadata has its own bounded allowance");
                SimpleAssert.eq(289, field(HologramAuthorProjection.class, "dependencyCount").getInt(projection),
                    "legacy shared graph budget still counts coarse region edges");
                SimpleAssert.eq(false, field(HologramAuthorProjection.class, "dependencyOverflow").getBoolean(projection),
                    "dense exact provenance cannot repeatedly force whole-owner graph overflow");
                Set<HologramRegionCache.Key> coarse = (Set<HologramRegionCache.Key>) field(
                    HologramAuthorProjection.class, "coarseDependencyInputs").get(projection);
                SimpleAssert.eq(boundedFallback, !coarse.isEmpty(), "only exhausted exact inputs degrade to coarse");
                active.seed(state);
                Object far = active.models.get(state.cells.get(257));
                demand(projection, 256);
                SimpleAssert.eq(false, active.models.containsKey(state.cells.get(0)),
                    "exact or coarse provenance still retires actual distant readers");
                SimpleAssert.that(active.models.get(state.cells.get(257)) == far,
                    "bounded fallback does not retire the independent whole owner");
                demand(projection, 255);
                SimpleAssert.eq(count, field(HologramAuthorProjection.class, "exactDependencyCount").getInt(projection),
                    "successive real publications retain provenance rather than clear and relearn it");
                SimpleAssert.eq(boundedFallback, !coarse.isEmpty(), "coarse fallback remains sound across publications");
                HologramState settled = projection.view();
                projection.request(settled, settled.cells.get(256));
                SimpleAssert.eq(false, projection.advance(() -> 0), "unchanged fallback input does not republish");
            }
        }
    }

    private static void demand(HologramAuthorProjection projection, int index) {
        HologramState before = projection.view();
        projection.request(before, before.cells.get(index));
        SimpleAssert.eq(true, projection.advance(() -> 0), "actual request advances and publishes");
    }

    private static final class Probe extends TileEntity { int value; }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static HologramBlockAccess isolated(HologramState state, boolean actual) throws Exception {
        HologramBlockAccess access = new HologramBlockAccess(state, actual);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        HologramRenderWorld world = (HologramRenderWorld) unsafe.allocateInstance(HologramRenderWorld.class);
        world.isRemote = true;
        field(HologramRenderWorld.class, "access").set(world, access);
        field(HologramBlockAccess.class, "renderWorld").set(access, world);
        return access;
    }

    private static final class Active implements AutoCloseable {

        final Map<Field, Object> saved = new LinkedHashMap<>();
        final Map<Map<Object, Object>, Map<Object, Object>> maps = new IdentityHashMap<>();
        final Map<Object, Object> models, regions;
        final HologramBlockAccess target, actual;

        @SuppressWarnings("unchecked")
        Active(HologramState state) throws Exception {
            for (String name : new String[] { "snapshot", "target", "actual", "preparationBudget" }) save(HologramBlockRender.class, name);
            for (String name : new String[] { "visibleState", "previewState", "previewPreparation", "worldState",
                "boundsState", "structureBoundsState", "projectedFrame", "presentedState", "presentedFrame" }) {
                Field f = save(HologramRenderer.class, name);
                f.set(null, null);
            }
            for (String name : new String[] { "visibleLayer", "visibleView", "visibleMode" })
                save(HologramRenderer.class, name);
            models = saveMap(HologramBlockRender.class, "MODELS");
            saveMap(HologramBlockRender.class, "DEFERRED");
            regions = saveMap(HologramRenderer.class, "previewRegions");
            target = isolated(state, false);
            actual = isolated(state, true);
            field(HologramBlockRender.class, "snapshot").set(null, state);
            field(HologramBlockRender.class, "target").set(null, target);
            field(HologramBlockRender.class, "actual").set(null, actual);
            field(HologramRenderer.class, "visibleState").set(null, state);
        }

        Field save(Class<?> type, String name) throws Exception {
            Field f = field(type, name);
            saved.put(f, f.get(null));
            return f;
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> saveMap(Class<?> type, String name) throws Exception {
            Map<Object, Object> map = (Map<Object, Object>) field(type, name).get(null);
            maps.put(map, new LinkedHashMap<>(map));
            map.clear();
            return map;
        }

        @SuppressWarnings("unchecked")
        void seed(HologramState state) throws Exception {
            Class<?> region = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
            Constructor<?> rc = region.getDeclaredConstructor(); rc.setAccessible(true);
            Class<?> cell = Class.forName(HologramRenderer.class.getName() + "$PreviewCell");
            Constructor<?> cc = cell.getDeclaredConstructor(HologramState.Cell.class, int.class, String.class, boolean.class);
            cc.setAccessible(true);
            Class<?> model = Class.forName(HologramBlockRender.class.getName() + "$Model");
            for (Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry : state.regions.regions.entrySet()) {
                Object r = rc.newInstance();
                field(region, "complete").setBoolean(r, true);
                field(region, "hintsComplete").setBoolean(r, true);
                List<Object> cells = (List<Object>) field(region, "cells").get(r);
                List<HologramState.Cell> visible = (List<HologramState.Cell>) field(region, "visible").get(r);
                for (HologramState.Cell c : entry.getValue()) {
                    cells.add(cc.newInstance(c, 63, "", true));
                    visible.add(c);
                    models.put(c, Array.newInstance(model, 2)); // No GL allocation or draw.
                }
                regions.put(entry.getKey(), r);
            }
            Class<?> prep = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation");
            Constructor<?> pc = prep.getDeclaredConstructor(HologramState.class); pc.setAccessible(true);
            field(HologramRenderer.class, "previewPreparation").set(null, pc.newInstance(state));
        }

        Object output(HologramState.Cell cell) throws Exception {
            Object r = regions.get(HologramRegionCache.key(cell));
            if (r == null) return null;
            for (Object output : (List<?>) field(r.getClass(), "cells").get(r))
                if (field(output.getClass(), "cell").get(output) == cell) return output;
            return null;
        }

        public void close() throws Exception {
            for (String name : new String[] { "target", "actual" }) {
                HologramBlockAccess access = (HologramBlockAccess) field(HologramBlockRender.class, name).get(null);
                if (access != null) access.close();
            }
            for (Map.Entry<Map<Object, Object>, Map<Object, Object>> entry : maps.entrySet()) {
                entry.getKey().clear(); entry.getKey().putAll(entry.getValue());
            }
            for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        }
    }
}
