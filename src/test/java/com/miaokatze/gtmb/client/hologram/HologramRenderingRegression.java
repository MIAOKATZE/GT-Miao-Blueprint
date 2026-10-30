package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Validates front-face identity without a GL context or mutable GT prototypes. */
public final class HologramRenderingRegression {

    private HologramRenderingRegression() {}

    public static void run() {
        previewOutlineDensity();
        completePreviewMaterial();
        occupancyBoundary();
        sourceSwitchFence();
        cameraNavigationWhilePreparing();
        orderedPreviewBatches();
        for (int front = 0; front < 6; front++) {
            boolean[] seen = new boolean[6];
            for (int side = 0; side < 6; side++) {
                int source = HologramTextures.inventorySide(front, side);
                SimpleAssert.that(source >= 0 && source < 6, "every world face resolves to an inventory face");
                SimpleAssert.that(!seen[source], "orientation never duplicates or loses an inventory face");
                seen[source] = true;
            }
            SimpleAssert.eq(
                4,
                HologramTextures.inventorySide(front, front),
                "GT inventory WEST front follows the actual world front");
            SimpleAssert
                .eq(5, HologramTextures.inventorySide(front, front ^ 1), "inventory rear remains opposite the front");
        }
        for (int side = 0; side < 6; side++) {
            SimpleAssert
                .eq(side, HologramTextures.inventorySide(-1, side), "old snapshots without orientation remain safe");
        }
        NBTTagCompound cellData = new NBTTagCompound();
        cellData.setInteger("actualFacing", 3);
        cellData.setInteger("targetFacing", 5);
        cellData.setInteger("hatchFacing", 2);
        HologramState.Cell cell = new HologramState.Cell(0, cellData);
        SimpleAssert.eq(3, cell.actualFacing, "current preview retains the actual front");
        SimpleAssert.eq(5, cell.targetFacing, "target preview retains its independent planned front");
    }

    private static void previewOutlineDensity() {
        SimpleAssert.eq(
            0f,
            HologramRenderer.previewOutlineDensity(1, 1, 1),
            "subpixel default grids do not cover their material");
        SimpleAssert
            .eq(0f, HologramRenderer.previewOutlineDensity(2, 9, 9), "two physical pixels is the hidden-grid boundary");
        SimpleAssert.eq(
            .5f,
            HologramRenderer.previewOutlineDensity(4, 9, 9),
            "four physical pixels gives half default wire opacity");
        SimpleAssert.eq(
            1f,
            HologramRenderer.previewOutlineDensity(6, 9, 9),
            "six physical pixels restores the existing close view");
        SimpleAssert.eq(
            1f,
            HologramRenderer.previewOutlineDensity(8, 0, 8),
            "an exactly head-on axis does not suppress a readable top view");
        float small = HologramRenderer.previewOutlineDensity(3, 4, 5);
        float enlarged = HologramRenderer.previewOutlineDensity(6, 8, 10);
        SimpleAssert
            .that(small > 0 && enlarged > small, "zoom or framebuffer scaling restores the same cached grid gradually");
        SimpleAssert.eq(
            0f,
            HologramRenderer.previewOutlineAlpha(false, false, false, true, 0),
            "dense ordinary material wireframes are skipped");
        SimpleAssert.eq(
            .45f,
            HologramRenderer.previewOutlineAlpha(false, false, false, true, 1),
            "close-view ordinary opacity is unchanged");
        SimpleAssert.eq(
            1f,
            HologramRenderer.previewOutlineAlpha(true, false, false, true, 0),
            "chosen cells keep their full highlight at every zoom");
        SimpleAssert.eq(
            1f,
            HologramRenderer.previewOutlineAlpha(false, true, false, true, 0),
            "related cells keep their full highlight at every zoom");
        SimpleAssert.eq(
            .18f,
            HologramRenderer.previewOutlineAlpha(false, false, true, true, 0),
            "explicit air retains its original opacity");
        SimpleAssert.eq(
            .45f,
            HologramRenderer.previewOutlineAlpha(false, false, false, false, 0),
            "unknown or diagnostic models retain their original wireframe");
    }

    private static void occupancyBoundary() {
        HologramSelectionOutline.Occupancy one = (x, y, z) -> x == 0 && y == 0 && z == 0;
        SimpleAssert
            .eq(4095, HologramRenderer.boundaryEdges(0, 0, 0, one), "a lone occupied voxel has its twelve real edges");
        HologramSelectionOutline.Occupancy pair = (x, y, z) -> (x == 0 || x == 1) && y == 0 && z == 0;
        SimpleAssert.that(
            (HologramRenderer.boundaryEdges(0, 0, 0, pair) & 1 << 1) == 0,
            "coplanar neighboring voxels do not add the shared vertical seam");
        HologramSelectionOutline.Occupancy ring = (x, y,
            z) -> y == 0 && x >= 0 && x <= 2 && z >= 0 && z <= 2 && (x != 1 || z != 1);
        SimpleAssert.that(
            (HologramRenderer.boundaryEdges(0, 0, 0, ring) & 1 << 5) != 0,
            "an inner concave corner around a hole survives instead of becoming a bounding box");
        int original = 0, rotated = 0;
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) if (ring.contains(x, 0, z)) {
            original += Integer.bitCount(HologramRenderer.boundaryEdges(x, 0, z, ring));
            rotated += Integer
                .bitCount(HologramRenderer.boundaryEdges(-z, 0, x, (rx, ry, rz) -> ring.contains(rz, ry, -rx)));
        }
        SimpleAssert.eq(original, rotated, "quarter turns preserve all hole and outer boundary segments");
        NBTTagCompound source = boundarySource("boundary");
        NBTTagList cells = new NBTTagList();
        cells.appendTag(boundaryCell(15, 0, 0, "minecraft:glass"));
        cells.appendTag(boundaryCell(16, 0, 0, "minecraft:stone"));
        source.setTag("cells", cells);
        HologramRenderer.PreviewBoundary boundary = new HologramRenderer.PreviewBoundary(new HologramState(source));
        int steps = 0;
        while (!boundary.complete() && steps++ < 20) boundary.advance(Integer.MAX_VALUE, 0, false, 1, Long.MAX_VALUE);
        SimpleAssert
            .that(boundary.complete() && steps > 2, "the real occupancy cursor completes through bounded steps");
        SimpleAssert.eq(
            2,
            boundary.cells.size(),
            "both transparent and solid occupied cells survive across a 16-cube boundary");
        SimpleAssert.eq(2, boundary.occupancy.size(), "the boundary uses the existing spatial region identities");
        SimpleAssert
            .eq(0, boundary.projected, "building real occupancy never needs projection, textures or a native model");
    }

    private static void sourceSwitchFence() {
        HologramState state = new HologramState(boundarySource("old"));
        Object world = new Object();
        HologramUiLibrary.LibraryNavigation navigation = new HologramUiLibrary.LibraryNavigation(
            state,
            world,
            "new",
            false,
            true,
            "blueprintEdit",
            0);
        SimpleAssert.that(
            HologramUiPreview.sourceSwitchWaiting(navigation, state, world, true, true, -1),
            "an actual library source transfer pauses its old scene");
        SimpleAssert.that(
            !HologramUiPreview.sourceSwitchWaiting(null, state, world, true, true, 9),
            "an unrelated pending edit does not pause or flash the current scene");
        SimpleAssert.that(
            !HologramUiPreview.sourceSwitchWaiting(navigation, state, world, true, false, -1),
            "a failed or completed wait immediately restores the same source");
        SimpleAssert.that(
            !HologramUiPreview.sourceSwitchWaiting(navigation, state, new Object(), true, true, 9),
            "a stale world cannot retain a scene wait");
        SimpleAssert.that(
            !HologramUiPreview
                .sourceSwitchWaiting(navigation, new HologramState(boundarySource("new")), world, true, true, 9),
            "a new installed session cannot inherit the old wait");
        navigation.cancel();
        SimpleAssert.that(
            !HologramUiPreview.sourceSwitchWaiting(navigation, state, world, true, true, 9),
            "cancelled source navigation cannot freeze a later scene");
    }

    private static void cameraNavigationWhilePreparing() {
        try {
            NBTTagCompound source = boundarySource("camera-navigation");
            NBTTagList cells = new NBTTagList();
            cells.appendTag(boundaryCell(0, 0, 0, "minecraft:stone"));
            source.setTag("cells", cells);
            HologramState state = new HologramState(source);
            HologramUiModules ui = new HologramUiModules(null);
            java.lang.reflect.Field session = HologramUiSession.class.getDeclaredField("state"),
                teaching = HologramUiSession.class.getDeclaredField("tutorialSink"),
                displayed = HologramRenderer.class.getDeclaredField("displayedState"),
                preparing = HologramRenderer.class.getDeclaredField("previewPreparation");
            session.setAccessible(true);
            teaching.setAccessible(true);
            displayed.setAccessible(true);
            preparing.setAccessible(true);
            session.set(ui.session, state);
            teaching
                .set(ui.session, (java.util.function.BiConsumer<String, NBTTagCompound>) (operation, request) -> {});
            Class<?> cursor = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation");
            java.lang.reflect.Constructor<?> constructor = cursor
                .getDeclaredConstructor(HologramState.class, boolean.class);
            constructor.setAccessible(true);
            Object previousDisplay = displayed.get(null), previousPreparation = preparing.get(null);
            try {
                // The scene has submitted this identity's light outline while its actual model cursor is incomplete.
                displayed.set(null, state);
                preparing.set(null, constructor.newInstance(state, true));
                SimpleAssert.that(
                    !ui.preview.previewInteractive() && !ui.shell.sceneActive(),
                    "model reprojection keeps picking and geometry command context unavailable");
                SimpleAssert.that(
                    ui.preview.previewNavigable() && ui.shell.sceneNavigable(),
                    "a submitted outline remains available for camera navigation while preparing");
                double zoom = ui.preview.zoom();
                ui.preview.scrollNativeScene(1);
                SimpleAssert
                    .that(ui.preview.zoom() > zoom, "the actual scene-wheel workflow zooms during reprojection");
                SimpleAssert.that(
                    ui.tools.commands()
                        .available("cameraForward"),
                    "camera key commands share the navigation admission instead of edit readiness");
                SimpleAssert.that(
                    !ui.tools.commands()
                        .commandContextAvailable(),
                    "allowing navigation does not reopen edit commands");
                ui.review.confirmation(true);
                zoom = ui.preview.zoom();
                ui.preview.scrollNativeScene(1);
                SimpleAssert.eq(zoom, ui.preview.zoom(), "a modal review still blocks camera wheel input");
                ui.review.confirmation(false);
                displayed.set(null, new HologramState(boundarySource("other-source")));
                ui.preview.scrollNativeScene(1);
                SimpleAssert
                    .eq(zoom, ui.preview.zoom(), "another submitted source cannot authorize the current scene camera");
            } finally {
                displayed.set(null, previousDisplay);
                preparing.set(null, previousPreparation);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("progressive scene camera navigation", failure);
        }
    }

    private static NBTTagCompound boundarySource(String session) {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", session);
        source.setString("generation", session);
        source.setBoolean("virtual", true);
        source.setLong("planRevision", 1);
        return source;
    }

    private static NBTTagCompound boundaryCell(int x, int y, int z, String block) {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        cell.setInteger("dx", x);
        cell.setInteger("y", y);
        cell.setInteger("dy", y);
        cell.setInteger("z", z);
        cell.setInteger("dz", z);
        cell.setString("wantId", block);
        return cell;
    }

    @SuppressWarnings("unchecked")
    private static void orderedPreviewBatches() {
        try {
            java.lang.reflect.Constructor<TextureAtlasSprite> iconConstructor = TextureAtlasSprite.class
                .getDeclaredConstructor(String.class);
            iconConstructor.setAccessible(true);
            TextureAtlasSprite icon = iconConstructor.newInstance("fixture:static");
            icon.setIconWidth(16);
            icon.setIconHeight(16);
            icon.initSprite(64, 64, 16, 16, false);
            SimpleAssert.that(HologramRenderer.stablePreviewIcon(icon), "exact static atlas UVs permit slice reuse");
            SimpleAssert.that(
                !HologramRenderer.stablePreviewIcon(new TextureAtlasSprite("fixture:custom") {}),
                "custom sprite subclasses keep their live UV callbacks");
            TextureAtlasSprite animated = iconConstructor.newInstance("fixture:animated");
            animated.setFramesTextureData(java.util.Arrays.asList(new int[1][], new int[1][]));
            SimpleAssert
                .that(!HologramRenderer.stablePreviewIcon(animated), "multi-frame sprites retain live commands");
            List<HologramTextures.Sample>[] sides = new List[6];
            for (int side = 0; side < 6; side++) sides[side] = java.util.Arrays
                .asList(new HologramTextures.Sample(icon, 0xff0000), new HologramTextures.Sample(icon, 0x00ff00));
            HologramTextures.Material material = new HologramTextures.Material(
                sides,
                sides,
                HologramGeometry.cube(),
                false,
                false,
                false,
                false);
            HologramState.Cell cell = new HologramState.Cell(0, boundaryCell(0, 0, 0, "minecraft:stone"));
            java.lang.reflect.Field resolved = HologramTextures.class.getDeclaredField("RESOLVED"),
                listener = HologramTextures.class.getDeclaredField("reloadInstalled"),
                nativeSnapshot = HologramBlockRender.class.getDeclaredField("snapshot");
            resolved.setAccessible(true);
            listener.setAccessible(true);
            nativeSnapshot.setAccessible(true);
            java.util.Map<HologramState.Cell, HologramTextures.Material[]> materials = (java.util.Map<HologramState.Cell, HologramTextures.Material[]>) resolved
                .get(null);
            boolean installed = listener.getBoolean(null);
            Object previousNative = nativeSnapshot.get(null);
            HologramSelection previousSelection = HologramRenderer.selection;
            int[] previousOffset = HologramRenderer.moveOffset;
            listener.setBoolean(null, true);
            nativeSnapshot.set(null, null);
            materials.put(cell, new HologramTextures.Material[] { material, material });
            try {
                Class<?> faceClass = Class.forName(HologramRenderer.class.getName() + "$Face");
                java.lang.reflect.Constructor<?> faceConstructor = faceClass.getDeclaredConstructor(
                    HologramState.Cell.class,
                    double[][].class,
                    int.class,
                    boolean.class,
                    HologramGeometry.Surface.class);
                faceConstructor.setAccessible(true);
                List<Object> faces = new java.util.ArrayList<>();
                for (int face = 0; face < 1025; face++) {
                    double[][] points = new double[8][3];
                    for (int vertex = 0; vertex < 8; vertex++)
                        points[vertex] = new double[] { face * 10 + vertex, vertex, face };
                    faces.add(faceConstructor.newInstance(cell, points, 1, false, material.geometry.get(1)));
                }
                Class<?> submissionClass = Class.forName(HologramRenderer.class.getName() + "$PreviewSubmission");
                java.lang.reflect.Constructor<?> submissionConstructor = submissionClass
                    .getDeclaredConstructor(List.class, double.class);
                java.lang.reflect.Method draw = submissionClass.getDeclaredMethod("draw", Tessellator.class);
                submissionConstructor.setAccessible(true);
                draw.setAccessible(true);
                Object submission = submissionConstructor.newInstance(faces, 2);
                RecordingTessellator tess = new RecordingTessellator();
                draw.invoke(submission, tess);
                SimpleAssert.eq(
                    java.util.Arrays.asList(4092, 4092, 16),
                    tess.batchSizes,
                    "real submission partitions complete quads below the vanilla buffer shrink threshold");
                SimpleAssert.eq(8200, tess.vertices.size(), "unsupported capture executes every original vertex once");
                double[] boundary = tess.vertices.get(4092);
                SimpleAssert.eq(5113d, boundary[0], "batch boundary preserves the original face order");
                SimpleAssert
                    .eq(1022.015d, boundary[2], "batch boundary preserves a split face's second UV layer depth");
                SimpleAssert.eq(.96f, (float) boundary[7], "static submission keeps the original translucent alpha");
                SimpleAssert.eq(icon.getMinU(), (float) boundary[3], "cached commands use the actual atlas UV extent");
                SimpleAssert.eq(1f, (float) boundary[6], "second material layer keeps its green tint");
                draw.invoke(submission, tess);
                SimpleAssert.eq(16400, tess.vertices.size(), "unsupported replay does not duplicate or omit commands");
                java.lang.reflect.Method dynamic = HologramRenderer.class.getDeclaredMethod(
                    "drawPreviewQuads",
                    Tessellator.class,
                    List.class,
                    double.class,
                    double.class,
                    double.class);
                dynamic.setAccessible(true);
                HologramRenderer.selection = new HologramSelection();
                HologramRenderer.selection.click(0, false);
                HologramRenderer.moveOffset = new int[] { 1, 0, 0 };
                RecordingTessellator moving = new RecordingTessellator();
                dynamic.invoke(null, moving, faces.subList(0, 1), 2d, 3d, 4d);
                SimpleAssert.eq(8, moving.vertices.size(), "drag fallback keeps all layered quad vertices once");
                SimpleAssert.eq(6d, moving.vertices.get(0)[0], "drag applies the existing projected horizontal shift");
                SimpleAssert.eq(7d, moving.vertices.get(0)[1], "drag applies the existing projected vertical shift");
                SimpleAssert.eq(.38f, (float) moving.vertices.get(0)[7], "drag preserves its original alpha");
                double[][] pickPoints = { { 0, 0, 0 }, { 10, 0, 0 }, { 10, 10, 0 }, { 0, 10, 0 }, { 0, 0, 1 },
                    { 10, 0, 1 }, { 10, 10, 1 }, { 0, 10, 1 } };
                List<Object> pickFaces = new java.util.ArrayList<>();
                pickFaces.add(faceConstructor.newInstance(cell, pickPoints, 2, false, material.geometry.get(2)));
                java.lang.reflect.Method hover = HologramRenderer.class
                    .getDeclaredMethod("previewHovered", List.class, int.class, int.class, boolean.class);
                java.lang.reflect.Method invalidate = HologramRenderer.class
                    .getDeclaredMethod("invalidatePreviewDrawCache");
                hover.setAccessible(true);
                invalidate.setAccessible(true);
                boolean metrics = com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled();
                long scans = HologramRenderer.previewHoverScans;
                com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.setEnabled(true);
                try {
                    invalidate.invoke(null);
                    SimpleAssert.eq(0, hover.invoke(null, pickFaces, 5, 5, true), "real polygon query picks its cell");
                    SimpleAssert
                        .eq(0, hover.invoke(null, pickFaces, 5, 5, true), "stationary hover retains the same hit");
                    SimpleAssert.eq(scans + 1, HologramRenderer.previewHoverScans, "stationary hover scans faces once");
                    SimpleAssert
                        .eq(-1, hover.invoke(null, pickFaces, 15, 5, true), "mouse movement recomputes the hit");
                    SimpleAssert.eq(scans + 2, HologramRenderer.previewHoverScans, "mouse movement triggers one scan");
                    double[][] projected = new double[pickPoints.length][];
                    for (int vertex = 0; vertex < pickPoints.length; vertex++) {
                        projected[vertex] = pickPoints[vertex].clone();
                        projected[vertex][0] += 20;
                    }
                    List<Object> rotated = new java.util.ArrayList<>();
                    rotated.add(faceConstructor.newInstance(cell, projected, 2, false, material.geometry.get(2)));
                    SimpleAssert.eq(
                        -1,
                        hover.invoke(null, rotated, 5, 5, true),
                        "reprojected geometry cannot reuse an old hit");
                    SimpleAssert.eq(scans + 3, HologramRenderer.previewHoverScans, "new projection triggers one scan");
                    SimpleAssert.eq(
                        -1,
                        hover.invoke(null, rotated, 25, 5, false),
                        "viewport/preparation gate never reuses hover");
                    SimpleAssert.eq(0, hover.invoke(null, rotated, 25, 5, true), "reopening picking recomputes hover");
                    SimpleAssert.eq(scans + 4, HologramRenderer.previewHoverScans, "gated query does not scan faces");
                    invalidate.invoke(null);
                    rotated.clear();
                    SimpleAssert
                        .eq(-1, hover.invoke(null, rotated, 25, 5, true), "in-place scene clear cannot retain a hit");
                } finally {
                    invalidate.invoke(null);
                    HologramRenderer.previewHoverScans = scans;
                    com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.setEnabled(metrics);
                }
            } finally {
                materials.remove(cell);
                listener.setBoolean(null, installed);
                nativeSnapshot.set(null, previousNative);
                HologramRenderer.selection = previousSelection;
                HologramRenderer.moveOffset = previousOffset;
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("ordered preview submission", failure);
        }
    }

    private static final class RecordingTessellator extends Tessellator {

        final java.util.List<double[]> vertices = new java.util.ArrayList<>();
        final java.util.List<Integer> batchSizes = new java.util.ArrayList<>();
        int batchStart;
        float red, green, blue, alpha;

        @Override
        public void startDrawing(int mode) {
            SimpleAssert.eq(7, mode, "preview material batches remain quads");
            batchStart = vertices.size();
        }

        @Override
        public int draw() {
            int count = vertices.size() - batchStart;
            SimpleAssert.that(count <= 4092 && count % 4 == 0, "draw boundaries never split a primitive");
            batchSizes.add(count);
            return count;
        }

        @Override
        public void setColorRGBA_F(float red, float green, float blue, float alpha) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        @Override
        public void addVertexWithUV(double x, double y, double z, double u, double v) {
            vertices.add(new double[] { x, y, z, u, v, red, green, alpha });
        }
    }

    @SuppressWarnings("unchecked")
    private static void completePreviewMaterial() {
        List<HologramTextures.Sample>[] sides = new List[6];
        for (int side = 0; side < 6; side++) sides[side] = Collections
            .singletonList(new HologramTextures.Sample(new TextureAtlasSprite("minecraft:stone") {}, 0xffffff));
        HologramTextures.Material material = new HologramTextures.Material(
            sides,
            sides,
            HologramGeometry.cube(),
            false,
            false,
            false,
            false);
        SimpleAssert.that(
            HologramRenderer.completePreviewMaterial(material),
            "all cached real material samples permit density fading");
        SimpleAssert.that(!HologramRenderer.completePreviewMaterial(null), "absent material keeps diagnostic lines");
        SimpleAssert.that(
            !HologramRenderer.completePreviewMaterial(
                new HologramTextures.Material(sides, sides, HologramGeometry.cube(), false, true, false, false)),
            "declared texture fallback keeps diagnostic lines");
        sides[0] = Collections
            .singletonList(new HologramTextures.Sample(new TextureAtlasSprite("minecraft:missingno") {}, 0xffffff));
        SimpleAssert.that(
            !HologramRenderer.completePreviewMaterial(material),
            "an actual missing atlas icon is not proof of a normal material");
        sides[0] = Collections.emptyList();
        SimpleAssert.that(
            !HologramRenderer.completePreviewMaterial(material),
            "unavailable face samples keep diagnostic lines");
    }
}
