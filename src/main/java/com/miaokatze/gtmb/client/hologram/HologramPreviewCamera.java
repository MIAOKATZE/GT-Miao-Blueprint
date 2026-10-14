package com.miaokatze.gtmb.client.hologram;

/** Camera translation in block units; moving a preview never changes the blueprint origin. */
final class HologramPreviewCamera {

    double x, z;

    void move(double yaw, double seconds, double right, double forward, double speed) {
        if (!Double.isFinite(seconds) || seconds <= 0 || !Double.isFinite(speed) || speed <= 0) return;
        double length = Math.hypot(right, forward);
        if (length == 0) return;
        double distance = Math.min(seconds, .1) * speed / Math.max(1, length);
        x += (right * Math.cos(yaw) - forward * Math.sin(yaw)) * distance;
        z += (-right * Math.sin(yaw) - forward * Math.cos(yaw)) * distance;
    }

    void reset() {
        x = z = 0;
    }
}
