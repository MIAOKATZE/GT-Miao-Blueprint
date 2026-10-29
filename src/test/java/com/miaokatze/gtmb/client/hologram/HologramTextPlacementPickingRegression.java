package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramTextBox;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** Real attachment-placement mouse routing over controlled CPU scene output; no GL or live world. */
public final class HologramTextPlacementPickingRegression {

    private HologramTextPlacementPickingRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramTextPlacementPickingRegression.class, args)) return;
        run();
        System.out.println("HologramTextPlacementPickingRegression PASS");
    }

    public static void run() throws Exception {
        HologramRenderer.release();
        boolean reload = field(HologramTextures.class, "reloadInstalled").getBoolean(null);
        field(HologramTextures.class, "reloadInstalled").setBoolean(null, true);
        try {
            HologramState state = state();
            HologramUiModules ui = new HologramUiModules(null);
            set(ui.session, "state", state);
            HologramTextBox draft = new HologramTextBox();
            set(ui.textBoxes, "draft", draft);
            HologramEditorCameraFrame camera = camera();
            setRenderer("projectedFrame", camera);
            setRenderer("presentedFrame", camera);
            setRenderer("presentedState", state);
            setRenderer("previewKey", "placement-camera");
            Object firstRegion = readyRegions(state);
            seedMaterials(state);
            List<Object> faces = cachedFaces();
            Object near = face(state.cells.get(0), 10), far = face(state.cells.get(1), -5);
            faces.add(near);
            faces.add(far); // Coarse region order is deliberately not global face-depth order.
            try (HologramUiCanvas.NativeSceneSpace ignored = ui.canvas
                .nativeSceneSpace(800, 600, new ViewportCoordinates(0, 0, 800, 600))) {
                resetDraft(ui, draft);
                SimpleAssert
                    .eq(true, ui.textBoxes.mouse(400, 300, 0), "actual placement mouse handler consumes ready click");
                assertPosition(draft, 0, 0, 1, "nearest face wins even when farther region is last");
                SimpleAssert
                    .eq(0, HologramRenderer.pickAdjacentVoxel(400, 300)[0], "direct adjacent picker shares depth rule");
                SimpleAssert.eq(
                    0,
                    HologramRenderer.pick(400, 300),
                    "adjacent placement agrees with ordinary nearest-face pick");
                Object distantRegion = previewRegions().get(HologramRegionCache.key(state.cells.get(1)));
                set(distantRegion, "projectedKey", "incomplete-distant-camera");
                SimpleAssert.that(
                    HologramRenderer.placementPickingReady(state, 400, 300),
                    "an unrelated incomplete region must not globally disable ready local placement");
                set(distantRegion, "projectedKey", "placement-camera");

                set(firstRegion, "projectedKey", "incomplete-camera");
                resetDraft(ui, draft);
                SimpleAssert.that(
                    !HologramRenderer.placementPickingReady(state, 400, 300),
                    "overlapping incomplete region blocks placement");
                SimpleAssert.that(
                    HologramRenderer.pickAdjacentVoxel(400, 300) == null,
                    "direct adjacent picker cannot hit behind incomplete output");
                ui.textBoxes.mouse(400, 300, 0);
                assertUnchanged(ui, draft, "blocked face cannot reposition attachment");
                set(ui.textBoxes, "chosen", true);
                ui.textBoxes.mouse(400, 300, 0);
                assertPosition(draft, 9, 7, 11, "blocked click preserves an already chosen draft too");
                SimpleAssert.eq(
                    true,
                    field(HologramUiTextBoxes.class, "chosen").getBoolean(ui.textBoxes),
                    "blocked click does not clear prior chosen state");
                resetDraft(ui, draft);
                faces.clear();
                SimpleAssert.that(
                    HologramRenderer.pickEmptyVoxel(state, 400, 300, draft.y, false) != null,
                    "fixture has a valid plane fallback that would bypass an adjacent-only null guard");
                ui.textBoxes.mouse(400, 300, 0);
                assertUnchanged(ui, draft, "blocked null hit cannot fall through to valid horizontal plane");

                set(firstRegion, "projectedKey", "placement-camera");
                faces.add(near);
                faces.add(far);
                set(ui.session, "state", state());
                resetDraft(ui, draft);
                ui.textBoxes.mouse(400, 300, 0);
                assertUnchanged(ui, draft, "unpresented owner cannot consume old cached face coordinates");
                set(ui.session, "state", state);
                setRenderer("projectedFrame", camera());
                ui.textBoxes.mouse(400, 300, 0);
                assertUnchanged(ui, draft, "unpresented camera cannot consume old cached face coordinates");
                setRenderer("projectedFrame", camera);
                setRenderer("presentedFrame", null);
                ui.textBoxes.mouse(400, 300, 0);
                assertUnchanged(ui, draft, "invalid presentation cannot consume cached coordinates");
                setRenderer("presentedFrame", camera);
                SimpleAssert.that(
                    !HologramRenderer.placementPickingReady(state, -1, 300),
                    "pointer outside the presented viewport is not placement ready");

                faces.clear();
                resetDraft(ui, draft);
                int[] blank = HologramRenderer.pickEmptyVoxel(state, 400, 300, draft.y, false);
                SimpleAssert.that(blank != null, "ready blank-space plane intersection remains available");
                ui.textBoxes.mouse(400, 300, 0);
                assertPosition(
                    draft,
                    blank[0],
                    blank[1],
                    blank[2],
                    "ready blank-space placement retains plane fallback");
                SimpleAssert.eq(
                    true,
                    field(HologramUiTextBoxes.class, "chosen").getBoolean(ui.textBoxes),
                    "ready blank-space click chooses the draft position");
            }
        } finally {
            HologramRenderer.release();
            field(HologramTextures.class, "reloadInstalled").setBoolean(null, reload);
        }
    }

    private static HologramEditorCameraFrame camera() {
        return new HologramEditorCameraFrame(0, 0, 800, 600, 0, .7, 1, 7.5, 7.5, 7.5, 40);
    }

    private static HologramState state() {
        NBTTagCompound source = new NBTTagCompound();
        NBTTagList cells = new NBTTagList();
        for (int x : new int[] { 0, 32 }) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", x);
            cell.setInteger("dx", x);
            cell.setString("id", "minecraft:stone");
            cell.setString("wantId", "minecraft:stone");
            cells.appendTag(cell);
        }
        source.setTag("cells", cells);
        return new HologramState(source);
    }

    @SuppressWarnings("unchecked")
    private static Object readyRegions(HologramState state) throws Exception {
        Map<HologramRegionCache.Key, Object> regions = previewRegions();
        Class<?> type = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object first = null;
        for (HologramRegionCache.Key key : state.regions.regions.keySet()) {
            Object region = constructor.newInstance();
            set(region, "hintsComplete", true);
            set(region, "projectedKey", "placement-camera");
            regions.put(key, region);
            if (key.equals(HologramRegionCache.key(state.cells.get(0)))) first = region;
        }
        return first;
    }

    @SuppressWarnings("unchecked")
    private static Map<HologramRegionCache.Key, Object> previewRegions() throws Exception {
        return (Map<HologramRegionCache.Key, Object>) field(HologramRenderer.class, "previewRegions").get(null);
    }

    @SuppressWarnings("unchecked")
    private static void seedMaterials(HologramState state) throws Exception {
        List<HologramTextures.Sample>[] sides = new List[6], covers = new List[6];
        for (int side = 0; side < 6; side++) sides[side] = covers[side] = Collections.emptyList();
        HologramTextures.Material material = new HologramTextures.Material(
            sides,
            covers,
            HologramGeometry.cube(),
            false,
            false,
            false,
            false);
        Map<HologramState.Cell, HologramTextures.Material[]> resolved = (Map<HologramState.Cell, HologramTextures.Material[]>) field(
            HologramTextures.class,
            "RESOLVED").get(null);
        for (HologramState.Cell cell : state.cells)
            resolved.put(cell, new HologramTextures.Material[] { material, material });
    }

    private static Object face(HologramState.Cell cell, double depth) throws Exception {
        double[][] points = { { 390, 290, depth }, { 410, 290, depth }, { 410, 310, depth }, { 390, 310, depth },
            { 390, 290, depth }, { 410, 290, depth }, { 410, 310, depth }, { 390, 310, depth } };
        Class<?> type = Class.forName(HologramRenderer.class.getName() + "$Face");
        Constructor<?> constructor = type
            .getDeclaredConstructor(HologramState.Cell.class, double[][].class, int.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(cell, points, 3, false);
    }

    private static void resetDraft(HologramUiModules ui, HologramTextBox draft) throws Exception {
        draft.x = 9;
        draft.y = 7;
        draft.z = 11;
        set(ui.textBoxes, "chosen", false);
    }

    private static void assertUnchanged(HologramUiModules ui, HologramTextBox draft, String message) throws Exception {
        assertPosition(draft, 9, 7, 11, message);
        SimpleAssert.eq(
            false,
            field(HologramUiTextBoxes.class, "chosen").getBoolean(ui.textBoxes),
            message + " chosen unchanged");
    }

    private static void assertPosition(HologramTextBox draft, int x, int y, int z, String message) {
        SimpleAssert.eq(x, draft.x, message + " x");
        SimpleAssert.eq(y, draft.y, message + " y");
        SimpleAssert.eq(z, draft.z, message + " z");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> cachedFaces() throws Exception {
        return (List<Object>) field(HologramRenderer.class, "cachedFaces").get(null);
    }

    private static void setRenderer(String name, Object value) throws Exception {
        field(HologramRenderer.class, name).set(null, value);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        field(owner.getClass(), name).set(owner, value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
