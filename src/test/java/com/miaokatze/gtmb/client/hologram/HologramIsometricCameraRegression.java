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
        HologramWorldTargetRegression.run();
        HologramLoadedRayTraceRegression.run();
        HologramIsometricCamera camera = HologramIsometricCamera.INSTANCE;
        SimpleAssert.that(!camera.active(), "loading the blueprint client does not acquire a world camera");
        SimpleAssert.eq(WorldCameras.GuiPolicy.RELEASE, camera.guiPolicy(), "any GUI releases the blueprint camera");
        CameraSettings settings = camera.settings();
        SimpleAssert.that(
            settings.input()
                .freeCursor(),
            "world selection uses a free pointer owned and restored by Core");
        SimpleAssert.that(
            !settings.input()
                .consumeHotbarScroll(),
            "plain scroll retains the hotbar and Alt scroll retains distance");
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
            settings.hud()
                .panelVisible(),
            "Core owns the nonmodal camera controls");
        for (int key : new int[] { Keyboard.KEY_R, Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT,
            Keyboard.KEY_RIGHT, Keyboard.KEY_Z, Keyboard.KEY_DELETE, Keyboard.KEY_LCONTROL, Keyboard.KEY_LMENU })
            SimpleAssert.that(camera.allowKey(key), "camera preserves blueprint key input " + key);
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
}
