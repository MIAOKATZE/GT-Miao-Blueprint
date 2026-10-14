package com.miaokatze.gtmb.client.hologram;

public final class HologramPreviewCameraRegression {

    private HologramPreviewCameraRegression() {}

    public static void run() {
        HologramPreviewCamera camera = new HologramPreviewCamera();
        camera.move(0, .1, 1, 0, 10);
        near(camera.x, 1, "D moves camera right");
        near(camera.z, 0, "D preserves forward position");
        camera.move(0, .1, -1, 0, 10);
        camera.move(0, .1, 0, 1, 10);
        near(camera.x, 0, "A reverses D");
        near(camera.z, -1, "W moves toward the scene");
        camera.move(0, .1, 0, -1, 10);
        near(camera.z, 0, "S reverses W");
        camera.move(Math.PI / 2, .1, 0, 1, 10);
        near(camera.x, -1, "movement follows the rotated camera");
        camera.reset();
        camera.move(0, .1, 1, 1, 10);
        near(Math.hypot(camera.x, camera.z), 1, "diagonal motion has the same speed");
        camera.reset();
        camera.move(0, 10, 1, 0, 10);
        near(camera.x, 1, "a delayed frame cannot jump across the scene");
        camera.reset();
        near(camera.x, 0, "reset recenters horizontal camera");
        near(camera.z, 0, "reset recenters forward camera");
    }

    private static void near(double actual, double expected, String label) {
        if (Math.abs(actual - expected) > 1e-9) throw new IllegalStateException(label + ": " + actual);
    }
}
