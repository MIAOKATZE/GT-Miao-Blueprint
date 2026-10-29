package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Production preparation/reveal/projection orchestration, with only texture/readiness and clock controlled. */
public final class HologramPreviewCameraLifecycleRegression {

    private HologramPreviewCameraLifecycleRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramPreviewCameraLifecycleRegression.class, args)) return;
        run();
        System.out.println("HologramPreviewCameraLifecycleRegression PASS");
    }

    public static void run() throws Exception {
        continuousMotionRetainsPresentedContentAndCurrentCoordinates();
        detailPublicationRetainsCheapCoverageUntilReplacement();
        currentCameraPickingUsesPublishedGeometry();
        realViewAndSourceChangesRetireRetainedAppearance();
        exactWakeRetiresPositiveAndNegativeAppearance();
        repeatedRevealPreservesFinishedSlotsAndCheapProgress();
    }

    private static void continuousMotionRetainsPresentedContentAndCurrentCoordinates() throws Exception {
        try (Fixture f = new Fixture(3, 8)) {
            // Start cold; no reflected completed regions or synthetic ready/projected counts. Every frame runs
            // the same source/view -> reveal -> preparation -> publication -> region scheduler used by preview.
            for (int frame = 0; frame < 240; frame++) f.frame(frame * .043, .31, 1 + frame % 7 * .03, 36, 100, true);
            SimpleAssert.eq(24, HologramRenderer.previewMeshCompleted(f.state), "detail converges while camera moves");
            f.assertAllPresented();
            int calls = f.appearanceCalls;
            long cheap = f.work("cheapVisitCalls"), detail = f.work("detailVisitCalls");
            for (int frame = 0; frame < 120; frame++) {
                f.frame(frame * .071, -.4 + frame % 17 * .045, .8 + frame % 11 * .04, 36, 100, true);
                f.assertAllPresented();
                f.assertCoordinatesAndFacing();
            }
            SimpleAssert.eq(calls, f.appearanceCalls, "camera-only frames never call appearance/native readiness");
            SimpleAssert.eq(cheap, f.work("cheapVisitCalls"), "camera-only frames never revisit cheap source work");
            SimpleAssert.eq(detail, f.work("detailVisitCalls"), "camera-only frames never restart native detail work");
            SimpleAssert.eq(0, HologramBlockRender.compiled, "ordinary production preparation compiled no native GL");
        }
    }

    private static void detailPublicationRetainsCheapCoverageUntilReplacement() throws Exception {
        try (Fixture f = new Fixture(1, 8)) {
            for (int frame = 0; frame < 8; frame++) f.frame(.2, .3, 1, 4, 24, true);
            SimpleAssert.eq(8, f.presentedSlots(), "cheap shell is fully presented before detail completion");
            boolean observedPendingDetailProjection = false;
            for (int frame = 0; frame < 24; frame++) {
                f.frame(.2 + frame * .03, .3, 1, 4, 24, true);
                SimpleAssert.eq(8, f.presentedSlots(), "detail publication never blanks unchanged cheap coverage");
                if (frame >= 7 && field(HologramRenderer.class, "projectionPending").getBoolean(null))
                    observedPendingDetailProjection = true;
            }
            SimpleAssert.that(
                observedPendingDetailProjection,
                "retained cheap coverage does not fabricate detailed projection completion");
            f.assertAllPresented();
        }
    }

    private static void currentCameraPickingUsesPublishedGeometry() throws Exception {
        try (Fixture f = new Fixture(1, 1)) {
            for (int frame = 0; frame < 8; frame++) f.frame(.3, .37, 1, 0, 8, true);
            for (int frame = 0; frame < 120; frame++) {
                f.frame(.13 + frame * .071, .37, .8 + frame % 9 * .04, -.6 + frame % 13 * .1, 8, true);
                double[] center = f.camera.projectScreen(0, 0, 0);
                int x = (int) Math.round(center[0]), y = (int) Math.round(center[1]);
                SimpleAssert.eq(0, HologramRenderer.pick(x, y), "actual picking tracks the current published camera");
                int[] adjacent = HologramRenderer.pickAdjacentVoxel(x, y);
                SimpleAssert.that(adjacent != null, "current complete presentation permits adjacent placement");
                double[] forward = { f.camera.basis.forwardX(), f.camera.basis.forwardY(), f.camera.basis.forwardZ() };
                int axis = 0;
                for (int candidate = 1; candidate < 3; candidate++)
                    if (Math.abs(forward[candidate]) > Math.abs(forward[axis])) axis = candidate;
                double second = 0;
                for (int candidate = 0; candidate < 3; candidate++)
                    if (candidate != axis) second = Math.max(second, Math.abs(forward[candidate]));
                // Avoid the integer mouse pixel's ambiguity exactly at a cube edge.
                if (Math.abs(forward[axis]) - second > .08)
                    for (int coordinate = 0; coordinate < 3; coordinate++) SimpleAssert.eq(
                        coordinate == axis ? forward[axis] > 0 ? -1 : 1 : 0,
                        adjacent[coordinate],
                        "adjacent voxel follows the current ray's front face, not a previously facing side");
            }
        }
    }

    private static void realViewAndSourceChangesRetireRetainedAppearance() throws Exception {
        try (Fixture f = new Fixture(1, 1)) {
            f.nativeSlots.add(0);
            f.nativeTargetOnly = true;
            for (int frame = 0; frame < 8; frame++) f.frame(.3, .4, 1, 0, 40, true);
            SimpleAssert.that(f.nativePresented(0), "target view has a resolved positive native record");
            for (int frame = 0; frame < 8; frame++) f.frame(.3, .4, 1, 0, 40, true, 1);
            SimpleAssert.that(!f.nativePresented(0), "real target-to-actual view change retires old native membership");
            for (Object face : (List<?>) field(HologramRenderer.class, "cachedFaces").get(null)) SimpleAssert.that(
                field(face.getClass(), "material").get(face) == f.actualMaterial,
                "actual view re-resolves actual-side material rather than retaining target appearance");
            for (int frame = 0; frame < 8; frame++) f.frame(.3, .4, 1, 0, 40, true);
            HologramState.Cell oldCell = f.state.cells.get(0);
            SimpleAssert
                .that(f.nativePresented(0), "target native appearance is prepared again before source replacement");
            f.editor.select(Collections.singleton(f.owner.cellId(0)));
            f.state = f.owner.update(f.editor.move(24, 0, 0));
            f.nativeSlots.clear();
            f.seedMaterials();
            for (int frame = 0; frame < 16; frame++) f.frame(.3 + frame * .02, .4, 1, 12, 48, true);
            SimpleAssert
                .that(!f.nativePresented(0), "real source publication cannot resurrect retired native appearance");
            f.assertAllPresented();
            for (Object outline : (List<?>) field(HologramRenderer.class, "cachedOutlines").get(null)) {
                HologramState.Cell current = (HologramState.Cell) field(outline.getClass(), "cell").get(outline);
                SimpleAssert.that(
                    current != oldCell && current == f.state.cells.get(0),
                    "source move presents only new immutable cell ownership");
                SimpleAssert
                    .eq(24, current.dx, "old source coordinate is not retained as a cheap presentation fallback");
            }
        }
    }

    private static void exactWakeRetiresPositiveAndNegativeAppearance() throws Exception {
        try (Fixture f = new Fixture(1, 8)) {
            f.nativeSlots.add(0); // Controlled native-ready bridge; no fictitious GL list is submitted.
            for (int frame = 0; frame < 80; frame++) f.frame(.4, .3, 1, 4, 24, true);
            SimpleAssert.that(f.nativePresented(0), "resolved positive native readiness is presented");
            f.nativeSlots.remove(0);
            f.nativeSlots.add(1);
            HologramRenderer.nativePreparationCellsChanged(f.state, new HashSet<>(f.state.cells.subList(0, 2)));
            SimpleAssert
                .that(!f.nativePresented(0), "exact retirement removes old native playback before another draw");
            int calls = f.appearanceCalls;
            for (int frame = 0; frame < 16; frame++) f.frame(.5 + frame * .04, .3, 1, 4, 24, true);
            SimpleAssert.that(!f.nativePresented(0), "old positive readiness cannot survive a same-identity wake");
            SimpleAssert.that(f.nativePresented(1), "old negative readiness refreshes after exact same-identity wake");
            SimpleAssert.eq(calls + 4, f.appearanceCalls, "two repaired slots alone re-resolve readiness and material");
            f.assertAllPresented();
        }
    }

    private static void repeatedRevealPreservesFinishedSlotsAndCheapProgress() throws Exception {
        try (Fixture f = new Fixture(1, 8)) {
            // Eight bounded frames create all cheap cells. Finish three detail slots visibly, one hidden,
            // then reveal before completing detail. This produces a mixed real deferred-visibility region.
            for (int frame = 0; frame < 11; frame++) f.frame(.2, .3, 1, 4, 24, true);
            f.frame(.2, .3, 1, 1000, 24, true);
            for (int frame = 0; frame < 4; frame++) f.frame(.2, .3, 1, 4, 24, true);
            long cheap = f.work("cheapVisitCalls");
            SimpleAssert.eq(8L, cheap, "initial cheap work covers the source exactly once");
            long resets = f.work("cursorResets");
            // The real frame reveal hook must enqueue only the skipped slot. No manual region remove/update.
            for (int frame = 0; frame < 40; frame++) f.frame(frame * .08, .3, 1, frame % 2 == 0 ? 4 : 1000, 24, true);
            for (int frame = 0; frame < 16; frame++) f.frame(.4, .3, 1, 4, 24, true);
            SimpleAssert.eq(cheap, f.work("cheapVisitCalls"), "repeated crossings do not rerun cheap preparation");
            SimpleAssert.eq(resets, f.work("cursorResets"), "reveal is not a whole-region cursor reset");
            SimpleAssert.eq(8, HologramRenderer.previewMeshCompleted(f.state), "revealed deferred slots converge");
            f.assertAllPresented();
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture implements AutoCloseable, HologramRenderer.PreviewAppearanceResolver {

        final HologramAuthorProjection owner;
        HologramState state;
        final AuthorEditor editor;
        final HologramTextures.Material material, actualMaterial;
        final Set<Integer> nativeSlots = new HashSet<>();
        final Method project, publish;
        final boolean oldReloadInstalled;
        HologramEditorCameraFrame camera;
        String key;
        int facing, appearanceCalls, frame;
        boolean nativeTargetOnly;

        @SuppressWarnings("unchecked")
        Fixture(int regions, int cells) throws Exception {
            HologramRenderer.release();
            oldReloadInstalled = field(HologramTextures.class, "reloadInstalled").getBoolean(null);
            field(HologramTextures.class, "reloadInstalled").setBoolean(null, true);
            JsonArray rows = new JsonArray();
            for (int region = 0; region < regions; region++) for (int cell = 0; cell < cells; cell++) {
                JsonObject row = new JsonObject();
                row.addProperty("x", region * 32 + cell % 4 * 3);
                row.addProperty("y", 0);
                row.addProperty("z", cell / 4 * 3);
                row.addProperty("block", "minecraft:stone");
                row.addProperty("item", "minecraft:stone");
                rows.add(row);
            }
            JsonObject document = new JsonObject();
            document.addProperty("schema", 2);
            document.add("cells", rows);
            editor = new AuthorEditor(AuthorDocumentIO.readExpanded(document, AuthorDocumentIO.Limits.defaults()), 8);
            owner = new HologramAuthorProjection(editor.current());
            state = owner.view();
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
            actualMaterial = new HologramTextures.Material(
                sides,
                covers,
                HologramGeometry.cube(),
                false,
                false,
                false,
                false);
            seedMaterials();
            Map<Block, Map<Integer, HologramTextures.Material>> light = (Map<Block, Map<Integer, HologramTextures.Material>>) field(
                HologramLightPreview.class,
                "MATERIALS").get(null);
            Map<Integer, HologramTextures.Material> variants = new HashMap<>();
            variants.put(0, material);
            light.put(Blocks.stone, variants);
            project = HologramRenderer.class.getDeclaredMethod(
                "projectPreviewRegions",
                List.class,
                HologramState.class,
                String.class,
                int.class,
                boolean.class,
                boolean.class,
                long.class,
                LongSupplier.class,
                HologramRenderer.PreviewAppearanceResolver.class);
            project.setAccessible(true);
            publish = HologramRenderer.class.getDeclaredMethod(
                "publishPreviewProjection",
                HologramState.class,
                String.class,
                List.class,
                boolean.class);
            publish.setAccessible(true);
        }

        @SuppressWarnings("unchecked")
        void seedMaterials() throws Exception {
            Map<HologramState.Cell, HologramTextures.Material[]> resolved = (Map<HologramState.Cell, HologramTextures.Material[]>) field(
                HologramTextures.class,
                "RESOLVED").get(null);
            for (HologramState.Cell cell : state.cells)
                resolved.put(cell, new HologramTextures.Material[] { material, actualMaterial });
        }

        void frame(double yaw, double pitch, double zoom, double anchor, double extent, boolean advance)
            throws Exception {
            frame(yaw, pitch, zoom, anchor, extent, advance, 0);
        }

        void frame(double yaw, double pitch, double zoom, double anchor, double extent, boolean advance, int view)
            throws Exception {
            camera = new HologramEditorCameraFrame(0, 0, 800, 600, yaw, pitch, zoom, anchor, 0, 0, extent);
            key = yaw + ":" + pitch + ":" + zoom + ":" + anchor + ":" + extent;
            field(HologramRenderer.class, "projectedFrame").set(null, camera);
            HologramRenderer.preparePreviewView(state, Integer.MAX_VALUE, view);
            HologramRenderer.preparePreviewRegions(state);
            if (advance) HologramRenderer.advancePreviewPreparation(Integer.MAX_VALUE, view, view == 1, 0, () -> 0);
            facing = 0;
            int[][] normals = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
            for (int side = 0; side < 6; side++) {
                int[] n = normals[side];
                if (-(n[0] * camera.basis.forwardX() + n[1] * camera.basis.forwardY() + n[2] * camera.basis.forwardZ())
                    > 1e-8) facing |= 1 << side;
            }
            List<?> ordered = new ArrayList<>(regions().values());
            project.invoke(null, ordered, state, key, facing, view == 1, true, 0L, (LongSupplier) () -> 0, this);
            publish.invoke(null, state, key, ordered, true);
            // Controlled draw acknowledgement only: the actual aggregated faces and current camera are used
            // by the production pick/adjacent-placement methods. This makes no GL/raster acceptance claim.
            field(HologramRenderer.class, "presentedFrame").set(null, camera);
            field(HologramRenderer.class, "presentedState").set(null, state);
            frame++;
        }

        public boolean nativeReady(HologramState.Cell cell, boolean current) {
            appearanceCalls++;
            return nativeSlots.contains(cell.index) && (!nativeTargetOnly || !current);
        }

        public HologramTextures.Material material(HologramState.Cell cell, boolean current, boolean detailed) {
            appearanceCalls++;
            return detailed && current ? actualMaterial : material;
        }

        long work(String name) {
            return HologramRenderer.previewWorkInspection()
                .getLong(name);
        }

        @SuppressWarnings("unchecked")
        Map<HologramRegionCache.Key, ?> regions() throws Exception {
            return (Map<HologramRegionCache.Key, ?>) field(HologramRenderer.class, "previewRegions").get(null);
        }

        @SuppressWarnings("unchecked")
        List<?> values(Object object, String name) throws Exception {
            return (List<?>) field(object.getClass(), name).get(object);
        }

        boolean nativePresented(int index) throws Exception {
            for (Object region : regions().values()) for (Object value : values(region, "nativeCells"))
                if (((HologramState.Cell) value).index == index) return true;
            return false;
        }

        int presentedSlots() throws Exception {
            int count = 0;
            for (Object region : regions().values()) count += values(region, "outlines").size();
            return count;
        }

        void assertAllPresented() throws Exception {
            int slots = 0;
            for (Object region : regions().values()) {
                SimpleAssert.eq(
                    key,
                    field(region.getClass(), "projectionWorkKey").get(region),
                    "every visible region uses current camera");
                SimpleAssert.eq(
                    key,
                    field(region.getClass(), "projectedKey").get(region),
                    "completed content stays fully projected through motion");
                slots += values(region, "outlines").size();
            }
            SimpleAssert.eq(
                state.cells.size(),
                slots,
                "all isolated source cells remain presented, not merely a cached subset");
            SimpleAssert.eq(
                slots,
                ((List<?>) field(HologramRenderer.class, "cachedOutlines").get(null)).size(),
                "actual scene publication contains every visible resolved source slot");
        }

        void assertCoordinatesAndFacing() throws Exception {
            Set<Integer> seenSides = new HashSet<>();
            for (Object region : regions().values()) for (Object face : values(region, "faces")) {
                HologramState.Cell cell = (HologramState.Cell) field(face.getClass(), "cell").get(face);
                int side = field(face.getClass(), "side").getInt(face);
                SimpleAssert.that((facing & 1 << side) != 0, "faces follow the current camera, never old facing mask");
                seenSides.add(side);
                double[][] points = (double[][]) field(face.getClass(), "points").get(face);
                double[] expected = camera.project(cell.dx - .5, cell.dy - .5, cell.dz - .5);
                for (int axis = 0; axis < 3; axis++) SimpleAssert.that(
                    Math.abs(points[0][axis] - expected[axis]) < 1e-8,
                    "presented/picking vertices use the current camera, not stale screen coordinates");
            }
            SimpleAssert
                .eq(Integer.bitCount(facing), seenSides.size(), "newly facing sides are retained without callbacks");
        }

        public void close() throws Exception {
            try {
                owner.close();
                HologramRenderer.release();
            } finally {
                field(HologramTextures.class, "reloadInstalled").setBoolean(null, oldReloadInstalled);
            }
        }
    }
}
