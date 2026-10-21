package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Per-screen presentation geometry. Blueprint coordinates and server state never depend on dock placement. */
final class HologramEditorLayout {

    private double width = 680, height = 382;
    private float scale = 1;
    private double panelX = Double.NaN, panelY = 64;
    private double dragX, dragY;
    private boolean dragging, collapsed, positioned;

    void resize(int screenWidth, int screenHeight) {
        scale = Math.min(1f, Math.min(Math.max(1, screenWidth) / 680f, Math.max(1, screenHeight) / 382f));
        width = Math.max(680, screenWidth / (double) scale);
        height = Math.max(382, screenHeight / (double) scale);
        if (!positioned || Double.isNaN(panelX)) panelX = rightPanelX();
        clampPanel();
    }

    float scale() {
        return scale;
    }

    int width() {
        return (int) Math.ceil(width);
    }

    int height() {
        return (int) Math.ceil(height);
    }

    int rightPanelX() {
        return Math.max(8, width() - 228);
    }

    int panelTop() {
        return 64;
    }

    boolean collapsed() {
        return collapsed;
    }

    boolean dragging() {
        return dragging;
    }

    ViewportCoordinates viewport() {
        return new ViewportCoordinates(8, 54, Math.max(1, width - 16), Math.max(1, height - 92));
    }

    CanvasTransform configuration() {
        return new CanvasTransform(280, 60, panelX, panelY, 1);
    }

    CanvasTransform modal() {
        return new CanvasTransform(0, 0, (width - 680) / 2, (height - 340) / 2, 1);
    }

    boolean panelContains(double x, double y) {
        return x >= panelX && x < panelX + 232 && y >= panelY && y < panelY + (collapsed ? 18 : 254);
    }

    boolean press(double x, double y, int button) {
        if (button != 0 || x < panelX || x >= panelX + 232 || y < panelY || y >= panelY + 18) return false;
        if (x >= panelX + 212) {
            collapsed = !collapsed;
            return true;
        }
        dragging = true;
        dragX = x - panelX;
        dragY = y - panelY;
        return true;
    }

    boolean drag(double x, double y) {
        if (!dragging) return false;
        positioned = true;
        panelX = x - dragX;
        panelY = y - dragY;
        clampPanel();
        return true;
    }

    void release() {
        dragging = false;
    }

    private void clampPanel() {
        panelX = Math.max(0, Math.min(width - 232, panelX));
        panelY = Math.max(54, Math.min(height - 38 - (collapsed ? 18 : 254), panelY));
    }
}
