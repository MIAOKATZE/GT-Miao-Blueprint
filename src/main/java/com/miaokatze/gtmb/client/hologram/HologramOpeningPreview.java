// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.miaokatzecore.api.camera.CameraBasis;
import com.miaokatze.miaokatzecore.api.camera.CameraPose;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** A local open owns a bounded read-only view and a camera, never the editor's installed selection. */
final class HologramOpeningPreview {

    @Desugar
    record Pose(double yaw, double pitch, double zoom, double x, double y, double z) {}

    @Desugar
    private record Presented(ViewportCoordinates area, long resources, double gridScale, double extent) {}

    final HologramState state;
    private final double initialYaw;
    private final HologramPreviewCamera pan = new HologramPreviewCamera();
    private double yaw, pitch, zoom;
    private Presented presented;
    private boolean showedPrefix;
    private int button = -1;
    private int startX, startY, previousX, previousY;
    private long motion;
    private boolean rotated;

    HologramOpeningPreview(HologramState state, double initialYaw) {
        this.state = state;
        this.initialYaw = initialYaw;
        reset();
    }

    void observe(HologramState drawn, ViewportCoordinates area, long resources, double gridScale, double extent) {
        if (drawn != state || area == null || gridScale <= 0 || !Double.isFinite(gridScale) || extent <= 0
            || !Double.isFinite(extent)) return;
        if (presented != null && !navigable(drawn, area, resources)) cancelDrag();
        showedPrefix = true;
        presented = new Presented(area, resources, gridScale, extent);
    }

    void invalidate() {
        presented = null;
        cancelDrag();
    }

    boolean navigable(HologramState drawn, ViewportCoordinates area, long resources) {
        // Briefly rebuilding the same prefix need not strand the current pointer. An old
        // source or a changed resource/area still needs its own successful draw.
        if (presented == null || (drawn != null && drawn != state) || area == null || presented.resources != resources)
            return false;
        ViewportCoordinates before = presented.area;
        return before.x() == area.x() && before.y() == area.y()
            && before.width() == area.width()
            && before.height() == area.height();
    }

    Pose pose() {
        return new Pose(yaw, pitch, zoom, pan.x, pan.y, pan.z);
    }

    Pose forInstalled(HologramState installed) {
        if (!showedPrefix || installed.data.getBoolean("displayPartial")
            || !state.session.equals(installed.session)
            || !state.data.getString("generation").equals(installed.data.getString("generation"))
            || !state.data.getString("libraryLocalId").equals(installed.data.getString("libraryLocalId"))
            || state.data.getLong("planRevision") != installed.data.getLong("planRevision")
            || state.minY != installed.minY
            || state.maxY != installed.maxY
            || state.data.getInteger("x") != installed.data.getInteger("x")
            || state.data.getInteger("z") != installed.data.getInteger("z")
            || !Arrays.equals(state.regions.bounds(), installed.regions.bounds())) return null;
        return pose();
    }

    void reset() {
        yaw = initialYaw;
        pitch = Math.atan(1 / Math.sqrt(2));
        zoom = 1;
        pan.reset();
    }

    void fixed(double offset, double pitch) {
        yaw = initialYaw + offset;
        this.pitch = pitch;
    }

    void rotateQuarter() {
        yaw += Math.PI / 2;
    }

    void scroll(int wheel) {
        zoom = HologramPreviewCamera.clampZoom(zoom * (wheel > 0 ? 1.1 : 1 / 1.1));
    }

    void move(double seconds, int right, int forward) {
        if (presented != null) pan.move(yaw, seconds, right, forward, Math.max(2, presented.extent * .6) / zoom);
    }

    boolean press(int x, int y, int button) {
        if (presented == null || (button != 1 && button != 2)) return false;
        this.button = button;
        startX = previousX = x;
        startY = previousY = y;
        motion = 0;
        rotated = false;
        return true;
    }

    void drag(int x, int y, int button) {
        if (presented == null || this.button != button || button < 0) return;
        int dx = x - previousX, dy = y - previousY;
        previousX = x;
        previousY = y;
        motion += Math.abs((long) dx) + Math.abs((long) dy);
        if (button == 1) {
            if (!rotated) {
                if (motion < 4) return;
                rotated = true;
                dx = x - startX;
                dy = y - startY;
            }
            yaw -= dx * .012;
            pitch = Math.max(-1.35, Math.min(1.35, pitch + dy * .012));
        } else {
            CameraBasis basis = new CameraBasis(new CameraPose(0, 0, 0, -Math.toDegrees(yaw), Math.toDegrees(pitch)));
            double scale = presented.gridScale;
            pan.x += (-dx * basis.rightX() + dy * basis.upX()) / scale;
            pan.y += (-dx * basis.rightY() + dy * basis.upY()) / scale;
            pan.z += (-dx * basis.rightZ() + dy * basis.upZ()) / scale;
        }
    }

    void release(int x, int y, int button) {
        if (this.button != button) return;
        drag(x, y, button);
        // A short right release here is only a camera release. It can never open the editor context menu.
        cancelDrag();
    }

    void cancelDrag() {
        button = -1;
    }
}
