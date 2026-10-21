package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.api.camera.CameraProjection;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;
import com.miaokatze.miaokatzecore.api.camera.ProjectedPoint;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Pure camera regression: no Minecraft, real world, display or native GL context is required. */
public final class HologramEditorCameraFrameRegression {

    private HologramEditorCameraFrameRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        legacyAxesCornersAndNativeMatrix();
        strictIsometricAxes();
        coordinatesAndParallelPlaneRays();
    }

    private static void legacyAxesCornersAndNativeMatrix() {
        double[][] angles = { { 45, 35.264389682754654 }, { 0, 0 }, { 90, 90 }, { -132, -28 }, { 273, 62 } };
        double[][] anchors = { { 0, 0, 0 }, { 123.25, -44.5, -201.75 } };
        for (double[] angle : angles) for (double[] anchor : anchors) for (double zoom : new double[] { .25, 1, 4 }) {
            double yaw = Math.toRadians(angle[0]), pitch = Math.toRadians(angle[1]);
            HologramEditorCameraFrame frame = new HologramEditorCameraFrame(
                17,
                54,
                903,
                577,
                yaw,
                pitch,
                zoom,
                anchor[0],
                anchor[1],
                anchor[2],
                31);
            double[][] points = { { 0, 0, 0 }, { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 }, { -.5, -.5, -.5 },
                { .5, -.5, -.5 }, { .5, .5, -.5 }, { -.5, .5, -.5 }, { -.5, -.5, .5 }, { .5, -.5, .5 }, { .5, .5, .5 },
                { -.5, .5, .5 } };
            for (double[] offset : points) {
                double x = anchor[0] + offset[0], y = anchor[1] + offset[1], z = anchor[2] + offset[2];
                double[] actual = frame.project(x, y, z);
                // Independent old GUI equations catch sign, anchor and root-rect regressions.
                double rx = offset[0] * Math.cos(yaw) - offset[2] * Math.sin(yaw);
                double rz = offset[0] * Math.sin(yaw) + offset[2] * Math.cos(yaw);
                double ry = offset[1] * Math.cos(pitch) - rz * Math.sin(pitch);
                double depth = offset[1] * Math.sin(pitch) + rz * Math.cos(pitch);
                near(17 + 903 / 2.0 + rx * frame.scale, actual[0], "legacy screen X");
                near(54 + 577 / 2.0 - ry * frame.scale, actual[1], "legacy screen Y");
                near(depth, actual[2], "legacy face depth and front-face ordering");
                ProjectedPoint core = CameraProjection.project(frame.pose, frame.projection, x, y, z);
                near(frame.coordinates.screenX(core.ndcX()), actual[0], "Core projection screen X");
                near(frame.coordinates.screenY(core.ndcY()), actual[1], "Core projection screen Y");
                near(
                    frame.projection.depthOffset() - depth,
                    (core.ndcZ() + 1) * .5 * (frame.projection.far() - frame.projection.near())
                        + frame.projection.near(),
                    "Core view distance preserves GUI depth sign");
                // Independent OpenGL postmultiplication: T(center) S(scale,-scale,depth) Rx(pitch) Ry(coreYaw).
                double glYaw = Math.toRadians(frame.pose.yawDegrees());
                double glPitch = Math.toRadians(frame.pose.pitchDegrees());
                double glX = offset[0] * Math.cos(glYaw) + offset[2] * Math.sin(glYaw);
                double glZ = -offset[0] * Math.sin(glYaw) + offset[2] * Math.cos(glYaw);
                double glY = offset[1] * Math.cos(glPitch) - glZ * Math.sin(glPitch);
                double glDepth = offset[1] * Math.sin(glPitch) + glZ * Math.cos(glPitch);
                near(frame.coordinates.screenX(0) + glX * frame.scale, actual[0], "native matrix X");
                near(frame.coordinates.screenY(0) - glY * frame.scale, actual[1], "native matrix Y");
                near(glDepth, actual[2], "native matrix depth");
            }
        }
    }

    private static void strictIsometricAxes() {
        HologramEditorCameraFrame frame = new HologramEditorCameraFrame(
            0,
            0,
            800,
            600,
            Math.PI / 4,
            Math.atan(1 / Math.sqrt(2)),
            1,
            0,
            0,
            0,
            10);
        double[] origin = frame.project(0, 0, 0);
        double length = -1;
        for (double[] axis : new double[][] { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } }) {
            double[] point = frame.project(axis[0], axis[1], axis[2]);
            double actual = Math.hypot(point[0] - origin[0], point[1] - origin[1]);
            if (length >= 0) near(length, actual, "strict isometric axis lengths");
            length = actual;
        }
    }

    private static void coordinatesAndParallelPlaneRays() {
        for (double pitch : new double[] { Math.atan(1 / Math.sqrt(2)), -.7, Math.PI / 2 }) {
            HologramEditorCameraFrame frame = new HologramEditorCameraFrame(
                11,
                47,
                913,
                521,
                .83,
                pitch,
                1.7,
                20.25,
                -3.5,
                -11.75,
                32);
            CanvasTransform canvas = new CanvasTransform(0, 0, 7.5, 13.25, .625);
            for (double[] pointer : new double[][] { { 11, 47 }, { 467.5, 307.5 }, { 923.999, 567.999 } }) {
                // Input is converted from scaled GUI to root logical exactly once, then to NDC.
                double mx = canvas.toLocalX(canvas.toScreenX(pointer[0]));
                double my = canvas.toLocalY(canvas.toScreenY(pointer[1]));
                CameraRay ray = CameraProjection
                    .unprojectRay(frame.pose, frame.projection, frame.coordinates.ndcX(mx), frame.coordinates.ndcY(my));
                near(frame.basis.forwardX(), ray.directionX(), "parallel ray X");
                near(frame.basis.forwardY(), ray.directionY(), "parallel ray Y");
                near(frame.basis.forwardZ(), ray.directionZ(), "parallel ray Z");
                for (double y : new double[] { -100000, -3.5, 0, 7.25, 100000 }) {
                    double[] hit = frame.horizontalPlane(mx, my, y);
                    SimpleAssert.that(hit != null, "unclipped plane intersection supports either direction");
                    near(y, hit[1], "slice Y");
                    double[] screen = frame.project(hit[0], hit[1], hit[2]);
                    near(mx, screen[0], "ray reprojects to clicked X");
                    near(my, screen[1], "ray reprojects to clicked Y");
                    // Old infinite-line inverse independently verifies zoom, pan, anchor and yaw sign.
                    double rx = (mx - frame.coordinates.screenX(0)) / frame.scale;
                    double ry = -(my - frame.coordinates.screenY(0)) / frame.scale;
                    double rz = ((y - frame.pose.anchorY()) * Math.cos(pitch) - ry) / Math.sin(pitch);
                    near(rx * Math.cos(.83) + rz * Math.sin(.83) + frame.pose.anchorX(), hit[0], "old plane X");
                    near(-rx * Math.sin(.83) + rz * Math.cos(.83) + frame.pose.anchorZ(), hit[2], "old plane Z");
                }
            }
            SimpleAssert.that(frame.horizontalPlane(924, 47, 0) == null, "right edge is exclusive");
            SimpleAssert.that(frame.horizontalPlane(11, 568, 0) == null, "bottom edge is exclusive");
            SimpleAssert.that(frame.horizontalPlane(10, 47, 0) == null, "outside viewport rejects picking");
        }
        HologramEditorCameraFrame level = new HologramEditorCameraFrame(0, 0, 100, 100, 0, 0, 1, 0, 0, 0, 10);
        SimpleAssert.that(level.horizontalPlane(50, 50, 0) == null, "near-horizontal slice guard is preserved");
    }

    private static void near(double expected, double actual, String label) {
        SimpleAssert.that(
            Double.isFinite(actual) && Math.abs(expected - actual) <= 1e-8,
            label + ": expected " + expected + ", got " + actual);
    }
}
