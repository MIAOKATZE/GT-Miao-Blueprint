package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.testutil.SimpleAssert;
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
        WorldCameras.Options options = camera.options();
        SimpleAssert.that(options.freeCursor, "world selection uses a free pointer owned and restored by Core");
        SimpleAssert
            .that(!options.consumeHotbarScroll, "plain scroll retains the hotbar and Alt scroll retains distance");
        SimpleAssert.eq(WorldCameras.MovementPolicy.ALL, options.movement, "camera panning never moves the player");
        for (int key : new int[] { Keyboard.KEY_R, Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT,
            Keyboard.KEY_RIGHT, Keyboard.KEY_Z, Keyboard.KEY_DELETE, Keyboard.KEY_LCONTROL, Keyboard.KEY_LMENU })
            SimpleAssert.that(camera.allowKey(key), "camera preserves blueprint key input " + key);
        camera.close();
        SimpleAssert.that(!camera.active(), "closing an inactive camera is safe");
        System.out.println("HologramIsometricCameraRegression passed");
    }
}
