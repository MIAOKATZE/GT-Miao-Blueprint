package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.List;

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
