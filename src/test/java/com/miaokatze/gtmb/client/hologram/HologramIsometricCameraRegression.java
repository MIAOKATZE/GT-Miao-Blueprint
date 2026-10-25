package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.api.camera.CameraPose;
import com.miaokatze.miaokatzecore.api.camera.HeightSlice;
import com.miaokatze.miaokatzecore.client.camera.CameraControl;
import com.miaokatze.miaokatzecore.client.camera.CameraControls;
import com.miaokatze.miaokatzecore.client.camera.CameraSettings;
import com.miaokatze.miaokatzecore.client.camera.WorldCameras;

/** Headless adapter contract checks; does not claim to exercise a live OpenGL camera. */
public final class HologramIsometricCameraRegression {

    private HologramIsometricCameraRegression() {}

    public static void main(String[] args) {
        placementPointer();
        selectionGestures();
        HologramSelectionOutlineRegression.run();
        HologramWorldTargetRegression.run();
        HologramLoadedRayTraceRegression.run();
        HologramIsometricCamera camera = HologramIsometricCamera.INSTANCE;
        SimpleAssert.that(!camera.active(), "loading the blueprint client does not acquire a world camera");
        SimpleAssert.eq(WorldCameras.GuiPolicy.RELEASE, camera.guiPolicy(), "any GUI releases the blueprint camera");
        CameraSettings settings = camera.settings();
        SimpleAssert.that(
            settings.navigation()
                .minScale() == .4
                && settings.navigation()
                    .maxScale() == 128,
            "world camera has the GTSWN engineering .4..128 pixels-per-block zoom range");
        SimpleAssert.that(
            settings.input()
                .freeCursor(),
            "world selection uses a free pointer owned and restored by Core");
        SimpleAssert.that(
            !settings.input()
                .consumeHotbarScroll(),
            "Core navigation owns direct wheel zoom while Alt scroll remains a consumer input");
        SimpleAssert.that(
            settings.input()
                .exclusiveKeyboard(),
            "camera blocks non-whitelisted raw key events");
        SimpleAssert.eq(
            WorldCameras.MovementPolicy.ALL,
            settings.input()
                .movement(),
            "camera panning never moves the player");
        SimpleAssert.eq(
            CameraSettings.Hud.Crosshair.HIDDEN,
            settings.hud()
                .crosshair(),
            "the orthographic world does not show vanilla's eye-based crosshair");
        SimpleAssert.that(
            !settings.hud()
                .panelVisible(),
            "camera hides the large generic controls panel");
        SimpleAssert.that(
            settings.hud()
                .sliceBarVisible(),
            "X-ray retains its independent height strip");
        SimpleAssert.that(
            settings.hud()
                .sliceDirectionVisible()
                && settings.hud()
                    .sliceLabelVisible(),
            "the X-ray strip includes a hide-above/below switch and its Y value");
        SimpleAssert.eq(
            1,
            settings.navigation()
                .panButton(),
            "right drag pans without selecting points");
        SimpleAssert.that(
            settings.navigation()
                .panExcludesControl(),
            "Ctrl+right reaches selection instead of panning");
        SimpleAssert.that(
            settings.navigation()
                .rotateExcludesControl(),
            "Ctrl+left reaches selection even when R is held");
        SimpleAssert.eq(
            0,
            settings.navigation()
                .rotateKey(),
            "left drag rotates without a keyboard modifier");
        SimpleAssert.that(
            settings.navigation()
                .rotationClickThreshold() >= 4,
            "short free-selection clicks are separated from camera dragging");
        SimpleAssert.that(
            settings.navigation()
                .wheelSensitivity() >= 50,
            "wheel zoom requests at least fifty-fold sensitivity");
        SimpleAssert.eq(
            CameraSettings.Navigation.WheelDeltaUnit.AUTO,
            settings.navigation()
                .wheelDeltaUnit(),
            "wheel zoom adapts to both classic and lwjgl3ify delta units");
        SimpleAssert.eq(
            CameraSettings.Navigation.WheelZoom.DIRECT_EXCEPT_ALT,
            settings.navigation()
                .wheelZoom(),
            "plain scroll zooms directly");
        for (int key : new int[] { Keyboard.KEY_V, Keyboard.KEY_ESCAPE, Keyboard.KEY_R, Keyboard.KEY_X,
            Keyboard.KEY_LMENU, Keyboard.KEY_RMENU, Keyboard.KEY_C, Keyboard.KEY_Z, Keyboard.KEY_Y,
            Keyboard.KEY_LCONTROL, Keyboard.KEY_RCONTROL, Keyboard.KEY_LSHIFT, Keyboard.KEY_RSHIFT, Keyboard.KEY_DELETE,
            Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT, Keyboard.KEY_W, Keyboard.KEY_E,
            Keyboard.KEY_F3, Keyboard.KEY_NUMPAD5 }) {
            SimpleAssert.that(!camera.allowKey(key), "raw polling keeps native KeyBindings blocked " + key);
            SimpleAssert.that(!camera.allowRawKey(key), "raw polling keeps FML key events blocked " + key);
        }
        CameraControls controls = camera.controls();
        for (CameraControl control : CameraControl.values())
            SimpleAssert.that(controls.supports(control), "blueprint camera supports its advertised Core controls");
        CameraPose before = camera.sample(0, 800, 600).pose;
        controls.panWorld(2, 3, -4);
        controls.rotate(30, 1000);
        controls.zoom(1000);
        WorldCameras.View after = camera.sample(0, 800, 600);
        SimpleAssert.that(
            after.pose.anchorX() == before.anchorX() + 2 && after.pose.anchorY() == before.anchorY() + 3
                && after.pose.anchorZ() == before.anchorZ() - 4,
            "Core pan commands update only the consumer camera request");
        SimpleAssert.that(
            after.pose.pitchDegrees() == settings.navigation()
                .maxPitch(),
            "Core pitch commands respect the blueprint camera's upper bound");
        controls.rotate(0, -2000);
        SimpleAssert.that(
            camera.sample(0, 800, 600).pose.pitchDegrees() == settings.navigation()
                .minPitch(),
            "Core pitch commands respect the blueprint camera's lower bound");
        SimpleAssert.that(
            800 / (2 * after.projection.halfWidth()) == settings.navigation()
                .maxScale(),
            "zoom commands retain the configured maximum scale");
        SimpleAssert.that(camera.settings() == settings, "view commands reuse the immutable settings instance");
        controls.zoom(1e-12);
        SimpleAssert.that(
            800 / (2 * camera.sample(0, 800, 600).projection.halfWidth()) == .4,
            "zoom-out commands reach the shared engineering minimum without changing the default fit");
        camera.toggleSlice();
        HeightSlice defaultSlice = camera.settings()
            .slice();
        SimpleAssert.that(
            defaultSlice.enabled() && defaultSlice.featherWidth() == 1 && defaultSlice.featheredCaps(),
            "X starts X-ray with one block of feather coverage including cap faces");
        SimpleAssert.that(defaultSlice.lightCompensation(), "X-ray opts into local cut-face light compensation");
        camera.toggleSlice();
        SimpleAssert.that(
            !camera.settings()
                .slice()
                .enabled(),
            "X disables the entire X-ray height strip");
        HeightSlice slice = HeightSlice.between(60, 80, 2);
        controls.requestSlice(slice);
        CameraSettings sliced = camera.settings();
        SimpleAssert.that(
            sliced != settings && !settings.slice()
                .enabled(),
            "slice requests publish new settings without mutating an earlier frame's policy");
        SimpleAssert.eq(slice, sliced.slice(), "Core's slice request reaches the consumer's next sample");
        controls.requestSlice(HeightSlice.between(60, 80, 2));
        SimpleAssert.that(camera.settings() == sliced, "equivalent slice requests preserve settings identity");
        controls.requestSlice(HeightSlice.disabled());
        camera.close();
        SimpleAssert.that(!camera.active(), "closing an inactive camera is safe");
        System.out.println("HologramIsometricCameraRegression passed");
    }

    private static void placementPointer() {
        HologramPlacementPointer pointer = new HologramPlacementPointer();
        java.util.function.Supplier<int[]> navigation = () -> {
            throw new AssertionError("Camera navigation must not query or replace the world placement target");
        };
        SimpleAssert.that(pointer.sample(false, navigation) == null, "positioning starts only with Ctrl");
        int[] target = { 4, 65, -8 };
        int[] first = pointer.sample(true, () -> target);
        target[0] = first[0] = 99;
        SimpleAssert
            .eq(4, pointer.sample(false, navigation)[0], "releasing Ctrl freezes an owned copy of the placement");
        SimpleAssert.eq(70, pointer.sample(true, () -> new int[] { 5, 70, -9 })[1], "Ctrl resumes live positioning");
        SimpleAssert
            .that(pointer.sample(true, () -> null) == null, "an invalid Ctrl target cannot lock a stale position");
        pointer.sample(true, () -> new int[] { 1, 2, 3 });
        pointer.clear();
        SimpleAssert.that(pointer.sample(false, navigation) == null, "closing the camera retires its placement target");
    }

    private static void selectionGestures() {
        for (boolean shift : new boolean[] { false, true }) for (boolean alt : new boolean[] { false, true }) {
            SimpleAssert.eq(
                HologramSelectionInput.CameraAction.CORNER_ONE,
                HologramSelectionInput.cameraAction(0, true, shift, alt, false, false),
                "Ctrl+left always fixes corner one");
            SimpleAssert.eq(
                HologramSelectionInput.CameraAction.CORNER_TWO,
                HologramSelectionInput.cameraAction(1, true, shift, alt, false, false),
                "Ctrl+right always fixes corner two");
        }
        SimpleAssert.eq(
            HologramSelectionInput.CameraAction.NONE,
            HologramSelectionInput.cameraAction(0, false, false, false, false, false),
            "plain left cannot alter either box corner");
        SimpleAssert.eq(
            HologramSelectionInput.CameraAction.NONE,
            HologramSelectionInput.cameraAction(1, false, false, false, false, false),
            "plain right belongs to camera panning");
        SimpleAssert.eq(
            HologramSelectionInput.CameraAction.CORNER_ONE,
            HologramSelectionInput.cameraAction(0, true, false, false, true, false),
            "Ctrl fixes corner one even when R is held");
        SimpleAssert.eq(
            HologramSelectionInput.CameraAction.NONE,
            HologramSelectionInput.cameraAction(0, false, false, false, true, false),
            "R+left rotates without selecting a corner");
        SimpleAssert.eq(
            HologramSelectionInput.CameraAction.SUBMIT,
            HologramSelectionInput.cameraAction(0, false, true, false, false, false),
            "Shift+left explicitly submits the box selection");
        for (boolean shift : new boolean[] { false, true }) for (boolean alt : new boolean[] { false, true }) {
            SimpleAssert.eq(
                HologramSelectionInput.CameraAction.NONE,
                HologramSelectionInput.cameraAction(0, false, shift, alt, false, true),
                "isometric polygon navigation never adds a vertex, closes or changes height without Ctrl");
            SimpleAssert.eq(
                HologramSelectionInput.CameraAction.CONTINUOUS_POINT,
                HologramSelectionInput.cameraAction(0, true, shift, alt, true, true),
                "Ctrl+left selects polygon vertices and height even when R is held");
        }
        SimpleAssert.eq(
            HologramSelectionInput.CameraAction.NONE,
            HologramSelectionInput.cameraAction(1, true, false, false, false, true),
            "Ctrl+right cannot replace a box corner during polygon entry");
    }
}
