// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.miaokatzecore.api.camera.CameraBasis;
import com.miaokatze.miaokatzecore.api.camera.CameraIntersections;
import com.miaokatze.miaokatzecore.api.camera.CameraPose;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;
import com.miaokatze.miaokatzecore.api.camera.OrthographicProjection;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** One editor viewport's immutable camera, in blueprint-relative block and root logical UI coordinates. */
final class HologramEditorCameraFrame {

    final ViewportCoordinates coordinates;
    final CameraPose pose;
    final CameraBasis basis;
    final OrthographicProjection projection;
    final double scale;
    private final double centerX, centerY;

    HologramEditorCameraFrame(int x, int y, int width, int height, double yaw, double pitch, double zoom,
        double anchorX, double anchorY, double anchorZ, double extent) {
        coordinates = new ViewportCoordinates(x, y, width, height);
        // Legacy GUI yaw has the opposite sign to Core/OpenGL Ry; pitch has the same sign.
        pose = new CameraPose(anchorX, anchorY, anchorZ, -Math.toDegrees(yaw), Math.toDegrees(pitch));
        basis = new CameraBasis(pose);
        scale = Math.min(width, height) * .72 / extent * zoom;
        double depth = extent * 4 + 1;
        projection = new OrthographicProjection(width / scale / 2, height / scale / 2, depth, .05, depth * 2);
        centerX = x + width / 2.0;
        centerY = y + height / 2.0;
    }

    /** Existing face arrays are the only per-vertex allocation; Core's orthonormal basis is reused. */
    double[] project(double x, double y, double z) {
        double[] result = new double[3];
        project(x, y, z, result);
        return result;
    }

    /** Writes the same projection into a render-thread-owned vertex; no Core object or temporary array is needed. */
    void project(double x, double y, double z, double[] result) {
        x -= pose.anchorX();
        y -= pose.anchorY();
        z -= pose.anchorZ();
        result[0] = centerX + (basis.rightX() * x + basis.rightZ() * z) * scale;
        result[1] = centerY - (basis.upX() * x + basis.upY() * y + basis.upZ() * z) * scale;
        result[2] = -(basis.forwardX() * x + basis.forwardY() * y + basis.forwardZ() * z);
    }

    /** Projects the four vertical edges in the same corner order and arithmetic as individual vertices. */
    void projectVoxel(double x, double y, double z, double[][] points) {
        double left = x - .5 - pose.anchorX(), right = x + .5 - pose.anchorX();
        double bottom = y - .5 - pose.anchorY(), top = y + .5 - pose.anchorY();
        double near = z - .5 - pose.anchorZ(), far = z + .5 - pose.anchorZ();
        double bottomUp = basis.upY() * bottom, topUp = basis.upY() * top;
        double bottomDepth = basis.forwardY() * bottom, topDepth = basis.forwardY() * top;
        projectVerticalEdge(left, near, bottomUp, topUp, bottomDepth, topDepth, points[0], points[3]);
        projectVerticalEdge(right, near, bottomUp, topUp, bottomDepth, topDepth, points[1], points[2]);
        projectVerticalEdge(left, far, bottomUp, topUp, bottomDepth, topDepth, points[4], points[7]);
        projectVerticalEdge(right, far, bottomUp, topUp, bottomDepth, topDepth, points[5], points[6]);
    }

    private void projectVerticalEdge(double x, double z, double bottomUp, double topUp, double bottomDepth,
        double topDepth, double[] bottom, double[] top) {
        double localUpX = basis.upX() * x, localUpZ = basis.upZ() * z;
        double localDepthX = basis.forwardX() * x, localDepthZ = basis.forwardZ() * z;
        double screenX = centerX + (basis.rightX() * x + basis.rightZ() * z) * scale;
        bottom[0] = top[0] = screenX;
        bottom[1] = centerY - (localUpX + bottomUp + localUpZ) * scale;
        top[1] = centerY - (localUpX + topUp + localUpZ) * scale;
        bottom[2] = -(localDepthX + bottomDepth + localDepthZ);
        top[2] = -(localDepthX + topDepth + localDepthZ);
    }

    double[] projectScreen(double x, double y, double z) {
        x -= pose.anchorX();
        y -= pose.anchorY();
        z -= pose.anchorZ();
        return new double[] { centerX + (basis.rightX() * x + basis.rightZ() * z) * scale,
            centerY - (basis.upX() * x + basis.upY() * y + basis.upZ() * z) * scale };
    }

    /** Placement uses an infinite parallel line, preserving the old unclipped horizontal-plane contract. */
    double[] horizontalPlane(double screenX, double screenY, double relativeY) {
        if (!coordinates.contains(screenX, screenY) || Math.abs(basis.forwardY()) < .05) return null;
        CameraRay ray = basis.ray(projection, coordinates.ndcX(screenX), coordinates.ndcY(screenY));
        double distance = CameraIntersections.plane(ray, 0, 1, 0, relativeY, 0, Double.MAX_VALUE);
        if (Double.isNaN(distance)) {
            // A chosen editing slice may be behind the nominal near plane; depth clipping is a draw policy.
            ray = new CameraRay(
                ray.originX(),
                ray.originY(),
                ray.originZ(),
                -ray.directionX(),
                -ray.directionY(),
                -ray.directionZ());
            distance = CameraIntersections.plane(ray, 0, 1, 0, relativeY, 0, Double.MAX_VALUE);
        }
        if (Double.isNaN(distance)) return null;
        return new double[] { ray.originX() + distance * ray.directionX(), relativeY,
            ray.originZ() + distance * ray.directionZ() };
    }
}
