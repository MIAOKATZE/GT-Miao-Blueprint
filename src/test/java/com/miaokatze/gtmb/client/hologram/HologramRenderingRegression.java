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
        standardLineBatchBoundary();
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
            HologramUiModules ui = new HologramUiModules(headlessRenderingHost());
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
                completedPresentationAdmission(ui, state, constructor);
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

    private static java.lang.reflect.Field field(Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static HologramUiHost headlessRenderingHost() throws ReflectiveOperationException {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        net.minecraft.client.Minecraft client = (net.minecraft.client.Minecraft) unsafe
            .allocateInstance(net.minecraft.client.Minecraft.class);
        HologramScreen parent = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
        // A real host boundary with a detached inactive client. held("air") observes currentScreen != parent before
        // physical Display/Keyboard polling; no global Minecraft singleton or native renderer is installed.
        return (HologramUiHost) java.lang.reflect.Proxy.newProxyInstance(
            HologramUiHost.class.getClassLoader(),
            new Class<?>[] { HologramUiHost.class },
            (proxy, method, arguments) -> switch (method.getName()) {
            case "minecraft" -> client;
            case "parent" -> parent;
            case "screenWidth" -> HologramScreenLayout.WIDTH;
            case "screenHeight" -> HologramScreenLayout.HEIGHT;
            default -> throw new AssertionError("headless rendering admission requested " + method.getName());
            });
    }

    /**
     * Headless control fixtures explicitly supply the new actual-presentation precondition; no GL success is claimed.
     */
    static Runnable completePresentation(HologramUiModules ui) throws ReflectiveOperationException {
        String[] names = { "previewPreparation", "previewState", "presentedState", "presentedRequest",
            "projectedRequest", "previewSubmitted", "displayedState" };
        Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) saved[i] = field(HologramRenderer.class, names[i]).get(null);
        HologramState state = ui.session.state();
        Class<?> preparationType = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation"),
            projectionType = Class.forName(HologramRenderer.class.getName() + "$PreviewProjection"),
            requestType = Class.forName(HologramRenderer.class.getName() + "$PreviewRequest");
        java.lang.reflect.Constructor<?> preparationConstructor = preparationType
            .getDeclaredConstructor(HologramState.class, boolean.class),
            projectionConstructor = projectionType.getDeclaredConstructor(HologramState.class, String.class),
            requestConstructor = requestType.getDeclaredConstructors()[0];
        preparationConstructor.setAccessible(true);
        projectionConstructor.setAccessible(true);
        requestConstructor.setAccessible(true);
        Object preparation = preparationConstructor.newInstance(state, false),
            projection = projectionConstructor.newInstance(state, "headless-presented");
        field(preparationType, "completed").setInt(preparation, state.cells.size());
        field(preparationType, "completeGeometryPresented").setBoolean(preparation, true);
        field(projectionType, "published").setInt(projection, state.cells.size());
        field(preparationType, "projection").set(preparation, projection);
        com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates area = ui.canvas.viewport();
        Object request = requestConstructor.newInstance(
            state,
            (int) area.x(),
            (int) area.y(),
            (int) area.width(),
            (int) area.height(),
            ui.preview.yaw(),
            ui.preview.pitch(),
            ui.preview.zoom(),
            0d,
            0d,
            0d,
            Integer.MAX_VALUE,
            0,
            false,
            false);
        field(HologramRenderer.class, "previewPreparation").set(null, preparation);
        for (String name : new String[] { "previewState", "presentedState", "displayedState" })
            field(HologramRenderer.class, name).set(null, state);
        for (String name : new String[] { "projectedRequest", "presentedRequest" })
            field(HologramRenderer.class, name).set(null, request);
        field(HologramRenderer.class, "previewSubmitted").setBoolean(null, true);
        return () -> {
            try {
                for (int i = 0; i < names.length; i++) field(HologramRenderer.class, names[i]).set(null, saved[i]);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("restore headless presentation", failure);
            }
        };
    }

    private static void completedPresentationAdmission(HologramUiModules ui, HologramState state,
        java.lang.reflect.Constructor<?> preparationConstructor) throws ReflectiveOperationException {
        String[] fields = { "previewState", "presentedState", "presentedRequest", "projectedRequest", "previewKey",
            "previewSubmitted", "previewPreparation" };
        Object[] saved = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) saved[i] = field(HologramRenderer.class, fields[i]).get(null);
        try {
            Object preparation = preparationConstructor.newInstance(state, false);
            Class<?> type = preparation.getClass();
            Class<?> projectionType = Class.forName(HologramRenderer.class.getName() + "$PreviewProjection");
            java.lang.reflect.Constructor<?> projectionConstructor = projectionType
                .getDeclaredConstructor(HologramState.class, String.class);
            projectionConstructor.setAccessible(true);
            Object projection = projectionConstructor.newInstance(state, "previous-pose");
            field(type, "completed").setInt(preparation, state.cells.size());
            field(projectionType, "published").setInt(projection, state.cells.size());
            field(type, "projection").set(preparation, projection);
            field(HologramRenderer.class, "previewPreparation").set(null, preparation);
            field(HologramRenderer.class, "previewState").set(null, state);
            field(HologramRenderer.class, "presentedState").set(null, state);
            field(HologramRenderer.class, "previewKey").set(null, "previous-pose");
            field(HologramRenderer.class, "previewSubmitted").setBoolean(null, true);
            Class<?> requestType = Class.forName(HologramRenderer.class.getName() + "$PreviewRequest");
            java.lang.reflect.Constructor<?> requestConstructor = requestType.getDeclaredConstructors()[0];
            requestConstructor.setAccessible(true);
            com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates area = ui.canvas.viewport();
            Object request = requestConstructor.newInstance(
                state,
                (int) area.x(),
                (int) area.y(),
                (int) area.width(),
                (int) area.height(),
                ui.preview.yaw(),
                ui.preview.pitch(),
                ui.preview.zoom(),
                0d,
                0d,
                0d,
                Integer.MAX_VALUE,
                0,
                false,
                false);
            field(HologramRenderer.class, "presentedRequest").set(null, request);
            field(HologramRenderer.class, "projectedRequest").set(null, request);
            java.lang.reflect.Method cameraOnly = HologramRenderer.class.getDeclaredMethod(
                "preparedCameraOnly",
                HologramState.class,
                String.class,
                int.class,
                int.class,
                boolean.class);
            cameraOnly.setAccessible(true);
            SimpleAssert.that(
                !(boolean) cameraOnly.invoke(null, state, "next-pose", Integer.MAX_VALUE, 0, false),
                "merely reaching N in a cold/dirty cursor cannot select synchronous camera presentation");
            field(type, "completeGeometryPresented").setBoolean(preparation, true);
            SimpleAssert.that(
                (boolean) cameraOnly.invoke(null, state, "next-pose", Integer.MAX_VALUE, 0, false),
                "a previously completely drawn preparation admits the next camera before it is presented");
            SimpleAssert.that(
                !(boolean) cameraOnly.invoke(null, state, "next-pose", 0, 0, false),
                "a new layer is preparation invalidation, not a camera-only change");
            SimpleAssert.that(
                !(boolean) cameraOnly.invoke(null, state, "next-pose", Integer.MAX_VALUE, 1, false),
                "a new view is not a camera-only change");
            SimpleAssert.that(
                !(boolean) cameraOnly.invoke(null, state, "next-pose", Integer.MAX_VALUE, 0, true),
                "a visibility change is not a camera-only change");
            SimpleAssert.that(
                !(boolean) cameraOnly
                    .invoke(null, new HologramState(state.fullData()), "next-pose", Integer.MAX_VALUE, 0, false),
                "another snapshot cannot borrow the old complete geometry stamp");
            SimpleAssert.that(ui.preview.previewInteractive(), "the exact complete presentation admits editing");
            ui.preview.scrollNativeScene(1);
            SimpleAssert.that(
                !ui.preview.previewInteractive() && ui.preview.previewNavigable(),
                "wheel changes desired immediately: editing closes before the next draw but navigation stays open");
            ui.preview.scrollNativeScene(1);
            SimpleAssert.that(
                ui.preview.zoom() > (double) field(requestType, "zoom").get(request),
                "a second wheel is accepted without waiting for a replacement presentation");
            int startX = (int) area.x() + 40, startY = (int) area.y() + 40;
            SimpleAssert.that(
                ui.preview.consumeViewportRightMouse(startX, startY, 1),
                "right navigation starts while the newly desired zoom is still unpresented");
            SimpleAssert.that(
                ui.preview.cameraGestureActive(),
                "an owned navigation gesture suppresses cold captures even before its next integer pointer move");
            ui.preview.mouseClickMove(startX + 8, startY, 1, 10);
            double firstYaw = ui.preview.yaw();
            ui.preview.mouseClickMove(startX + 16, startY, 1, 20);
            SimpleAssert.that(
                ui.preview.yaw() < firstYaw,
                "successive navigation moves are not blocked by the strict edit stamp");
            ui.preview.mouseMovedOrUp(startX + 16, startY, 1);
            SimpleAssert.that(
                !ui.preview.cameraGestureActive(),
                "releasing navigation immediately restores capture admission without a timer or pose delay");
            HologramGizmo gizmo = (HologramGizmo) field(HologramUiPreview.class, "gizmo").get(ui.preview);
            HologramGizmo textGizmo = (HologramGizmo) field(HologramUiTextBoxes.class, "gizmo").get(ui.textBoxes);
            gizmo.begin(0, 1, 1);
            textGizmo.begin(0, 1, 1);
            ui.preview.mouseMovedOrUp(40, 40, 0);
            SimpleAssert.eq(-1, gizmo.axis(), "stale release cancels the block gizmo without committing a move");
            SimpleAssert.eq(-1, textGizmo.axis(), "stale release also cancels the independent text capture");
            field(HologramRenderer.class, "presentedRequest").set(null, null);
            SimpleAssert.that(!ui.preview.previewInteractive(), "a closed/unsubmitted scene cannot admit edits");
        } finally {
            for (int i = 0; i < fields.length; i++) field(HologramRenderer.class, fields[i]).set(null, saved[i]);
        }
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
                completeCameraProjection(material, materials);
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
                java.lang.reflect.Method drawWithoutCapture = submissionClass
                    .getDeclaredMethod("draw", Tessellator.class, boolean.class);
                drawWithoutCapture.setAccessible(true);
                Object deferred = submissionConstructor.newInstance(faces, 2);
                RecordingTessellator deferredTess = new RecordingTessellator();
                drawWithoutCapture.invoke(deferred, deferredTess, false);
                drawWithoutCapture.invoke(deferred, deferredTess, false);
                SimpleAssert.eq(
                    16400,
                    deferredTess.vertices.size(),
                    "stationary poses within a gesture still emit all ordered layered commands exactly once");
                SimpleAssert.that(
                    !field(submissionClass, "unsupported").getBoolean(deferred),
                    "gesture admission skips capture itself rather than causing permanent unsupported fallback");
                SimpleAssert.eq(
                    0,
                    field(submissionClass, "retainedInts").getInt(deferred),
                    "deferred cold capture retains no raw slice ints");
                drawWithoutCapture.invoke(deferred, deferredTess, true);
                SimpleAssert.that(
                    field(submissionClass, "unsupported").getBoolean(deferred),
                    "ending the gesture reopens actual capture, whose existing foreign-tessellator fallback is retained");
                SimpleAssert.eq(
                    24600,
                    deferredTess.vertices.size(),
                    "reopened unsupported capture still executes each original command once");
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

    @SuppressWarnings("unchecked")
    private static void completeCameraProjection(HologramTextures.Material material,
        java.util.Map<HologramState.Cell, HologramTextures.Material[]> materials) throws ReflectiveOperationException {
        // The same prepared region inputs drive both a bounded cursor and the camera-only complete cursor. No GL draw
        // or copied projection algorithm is involved; the native candidate checks the actual screen submission.
        NBTTagCompound source = boundarySource("prepared-camera");
        NBTTagList rows = new NBTTagList();
        // Interleaved input indices deliberately differ from region/cell generation order.
        for (int[] point : new int[][] { { 16, 0 }, { 14, 1 }, { 16, 1 }, { 15, 0 }, { 16, 2 }, { 14, 0 }, { 14, 2 },
            { 15, 2 } }) rows.appendTag(boundaryCell(point[0], 0, point[1], "minecraft:glass"));
        source.setTag("cells", rows);
        HologramState state = new HologramState(source);
        boolean[] missingIcon = { false };
        TextureAtlasSprite diagnosticIcon = new TextureAtlasSprite("fixture:diagnostic") {

            @Override
            public String getIconName() {
                return missingIcon[0] ? "missingno" : "fixture:diagnostic";
            }
        };
        List<HologramTextures.Sample>[] diagnosticSides = new List[6];
        for (int side = 0; side < 6; side++)
            diagnosticSides[side] = Collections.singletonList(new HologramTextures.Sample(diagnosticIcon, 0xffffff));
        HologramTextures.Material diagnosticMaterial = new HologramTextures.Material(
            diagnosticSides,
            diagnosticSides,
            material.geometry,
            false,
            false,
            false,
            false);
        HologramTextures.Material partialMaterial = new HologramTextures.Material(
            diagnosticSides,
            diagnosticSides,
            HologramGeometry.pipe(.5f, (1 << 2) | (1 << 5), 1),
            false,
            false,
            true,
            true);
        for (HologramState.Cell cell : state.cells) materials.put(
            cell,
            new HologramTextures.Material[] { cell.index == 7 ? partialMaterial : diagnosticMaterial,
                cell.index == 7 ? partialMaterial : diagnosticMaterial });
        String[] names = { "previewPreparation", "previewState", "cachedFaces", "cachedOutlines", "cachedMarkers",
            "projectedFrame", "previewSubmission", "hoverFaces" };
        Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) saved[i] = field(HologramRenderer.class, names[i]).get(null);
        java.util.Map<Object, Object> regions = (java.util.Map<Object, Object>) field(
            HologramRenderer.class,
            "previewRegions").get(null);
        java.util.Map<Object, Object> savedRegions = new java.util.LinkedHashMap<>(regions);
        String[] listNames = { "nativePreviewCells", "linkedPreviewCells", "visibleCells" };
        List<?>[] lists = new List<?>[listNames.length];
        for (int i = 0; i < lists.length; i++)
            lists[i] = new java.util.ArrayList<>((List<?>) field(HologramRenderer.class, listNames[i]).get(null));
        java.util.BitSet published = (java.util.BitSet) field(HologramRenderer.class, "publishedPreviewCells")
            .get(null), savedPublished = (java.util.BitSet) published.clone();
        try {
            java.lang.reflect.Method prepareRegion = HologramRenderer.class.getDeclaredMethod(
                "previewRegion",
                HologramState.class,
                List.class,
                int.class,
                int.class,
                boolean.class);
            prepareRegion.setAccessible(true);
            Class<?> preparationType = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation"),
                projectionType = Class.forName(HologramRenderer.class.getName() + "$PreviewProjection");
            java.lang.reflect.Constructor<?> preparationConstructor = preparationType
                .getDeclaredConstructor(HologramState.class, boolean.class);
            java.lang.reflect.Constructor<?> reusedProjectionConstructor = projectionType
                .getDeclaredConstructor(HologramState.class, String.class, projectionType);
            preparationConstructor.setAccessible(true);
            reusedProjectionConstructor.setAccessible(true);
            java.lang.reflect.Method advance = projectionType
                .getDeclaredMethod("advance", boolean.class, int.class, long.class),
                publish = projectionType.getDeclaredMethod("publish");
            advance.setAccessible(true);
            publish.setAccessible(true);
            regions.clear();
            for (java.util.Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry : state.regions.regions
                .entrySet())
                regions.put(
                    entry.getKey(),
                    prepareRegion.invoke(null, state, entry.getValue(), Integer.MAX_VALUE, 0, false));
            Object preparation = preparationConstructor.newInstance(state, false);
            field(preparationType, "completed").setInt(preparation, state.cells.size());
            field(HologramRenderer.class, "previewPreparation").set(null, preparation);
            Object previousProjection = null;
            for (double yaw : new double[] { 0, .4, -.8, 2.1 }) {
                List<double[]> reference = null;
                java.util.Set<Object> ownedFaces = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (int path = 0; path < 3; path++) {
                    // Revisit the reference pose after a genuinely different pose mutated every owned output.
                    double currentYaw = path == 1 ? yaw + .13 : yaw;
                    missingIcon[0] = path == 1;
                    String key = yaw + ":" + path;
                    field(HologramRenderer.class, "projectedFrame")
                        .set(null, new HologramEditorCameraFrame(0, 0, 380, 321, currentYaw, .4, 1, 15, 0, 1, 6));
                    for (String name : new String[] { "cachedFaces", "cachedOutlines", "cachedMarkers" })
                        field(HologramRenderer.class, name).set(null, new java.util.ArrayList<>());
                    for (String name : listNames) ((List<?>) field(HologramRenderer.class, name).get(null)).clear();
                    published.clear();
                    Object projection = reusedProjectionConstructor.newInstance(state, key, previousProjection);
                    if (previousProjection != null) SimpleAssert.that(
                        field(projectionType, "faces").get(projection)
                            == field(projectionType, "faces").get(previousProjection),
                        "retired cursor output capacity is reused without a new aggregate backing array");
                    field(preparationType, "projection").set(preparation, projection);
                    if (path == 0) {
                        int steps = 0;
                        while (field(projectionType, "published").getInt(projection) < state.cells.size()
                            && steps++ < 100) {
                            advance.invoke(projection, false, 1, Long.MAX_VALUE);
                            publish.invoke(projection);
                        }
                    } else {
                        advance.invoke(projection, false, Integer.MAX_VALUE, Long.MAX_VALUE);
                        publish.invoke(projection);
                    }
                    SimpleAssert.eq(
                        state.cells.size(),
                        field(projectionType, "published").getInt(projection),
                        "the prepared camera path publishes every source cell in its single advance/publish");
                    List<?> faces = (List<?>) field(HologramRenderer.class, "cachedFaces").get(null);
                    SimpleAssert.that(!faces.isEmpty(), "concave transparent multi-region fixture has real faces");
                    if (path == 0) {
                        reference = new java.util.ArrayList<>();
                        for (Object face : faces) reference.add(faceSnapshot(face));
                        ownedFaces.addAll(faces);
                        if (yaw == 0) {
                            boolean groupedTie = false;
                            for (int at = 1; at < reference.size(); at++)
                                if (reference.get(at - 1)[2] == reference.get(at)[2]
                                    && reference.get(at - 1)[0] > reference.get(at)[0]) groupedTie = true;
                            SimpleAssert.that(
                                groupedTie,
                                "equal-depth fixture distinguishes canonical region order from global cell-index sorting");
                        }
                    } else {
                        SimpleAssert.that(
                            faces == field(projectionType, "faces").get(projection),
                            "complete publication transfers the sorted cursor output without a second copy");
                    }
                    if (path == 2) {
                        SimpleAssert.eq(reference.size(), faces.size(), "complete projection keeps the same coverage");
                        for (int at = 0; at < faces.size(); at++) {
                            Object actual = faces.get(at);
                            SimpleAssert.that(
                                java.util.Arrays.equals(reference.get(at), faceSnapshot(actual)),
                                "deep numeric oracle preserves cell/side/depth/order/vertices after another camera");
                            SimpleAssert.that(
                                ownedFaces.contains(actual),
                                "revisiting a prepared camera reuses its actual Face outputs");
                        }
                    }
                    for (Object outline : (List<?>) field(HologramRenderer.class, "cachedOutlines").get(null))
                        SimpleAssert.eq(
                            !missingIcon[0],
                            field(outline.getClass(), "fadeDefault").get(outline),
                            "mutable IIcon diagnostics are reevaluated even when owned Faces are reused");
                    previousProjection = projection;
                }
            }
        } finally {
            for (HologramState.Cell cell : state.cells) materials.remove(cell);
            regions.clear();
            regions.putAll(savedRegions);
            for (int i = 0; i < names.length; i++) field(HologramRenderer.class, names[i]).set(null, saved[i]);
            for (int i = 0; i < listNames.length; i++) {
                List<Object> list = (List<Object>) field(HologramRenderer.class, listNames[i]).get(null);
                list.clear();
                list.addAll(lists[i]);
            }
            published.clear();
            published.or(savedPublished);
        }
    }

    /** Values only: retaining Face references here would turn the camera round trip into a false self-comparison. */
    private static double[] faceSnapshot(Object face) throws ReflectiveOperationException {
        Class<?> type = face.getClass();
        double[] result = new double[27];
        result[0] = ((HologramState.Cell) field(type, "cell").get(face)).index;
        result[1] = field(type, "side").getInt(face);
        result[2] = field(type, "depth").getDouble(face);
        double[][] points = (double[][]) field(type, "points").get(face);
        for (int vertex = 0; vertex < points.length; vertex++)
            for (int axis = 0; axis < 3; axis++) result[3 + vertex * 3 + axis] = points[vertex][axis];
        return result;
    }

    private static void standardLineBatchBoundary() {
        Tessellator standard = new Tessellator();
        int limit = HologramRenderer.previewLineBatchLimit(standard);
        SimpleAssert.eq(16368, limit, "only the exact eight-int vanilla layout uses the larger line batch");
        SimpleAssert.eq(
            4092,
            HologramRenderer.previewLineBatchLimit(new RecordingTessellator()),
            "foreign tessellators retain their existing conservative cap");
        standard.startDrawing(org.lwjgl.opengl.GL11.GL_LINES);
        standard.setColorRGBA_F(.2f, .7f, .8f, .45f);
        for (int vertex = 0; vertex < limit; vertex++) standard.addVertex(vertex, 0, .15);
        try {
            int used = field(Tessellator.class, "rawBufferIndex").getInt(standard),
                capacity = ((int[]) field(Tessellator.class, "rawBuffer").get(standard)).length;
            SimpleAssert.eq(limit * 8, used, "real vanilla line vertices retain their eight-int layout");
            SimpleAssert.that(
                used + 32 <= capacity && capacity <= 0x20000,
                "a real full line batch fits addVertex headroom without crossing the shrinking-buffer threshold");
            SimpleAssert.that(limit % 24 == 0, "the line cap preserves complete boxes and edge pairs");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("standard line batch boundary", failure);
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
