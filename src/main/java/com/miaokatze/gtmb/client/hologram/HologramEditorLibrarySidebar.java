package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Presentation-only library dock; collapsing it never changes the current blueprint or its camera. */
final class HologramEditorLibrarySidebar {

    static final int TOGGLE_CONTROL = HologramEditorControlIds.LIBRARY;
    static final int SOURCE_X = 452, SOURCE_Y = 34, WIDTH = 220, HEIGHT = 280;
    static final int VISIBLE_ROWS = 6, ROW_HEIGHT = 19, LIST_TOP = 123;

    private boolean expanded = true;

    boolean expanded() {
        return expanded;
    }

    void expanded(boolean value) {
        expanded = value;
    }

    CanvasTransform transform(HologramEditorLayout layout) {
        return new CanvasTransform(
            SOURCE_X,
            SOURCE_Y,
            expanded ? layout.rightPanelX() : layout.width() - 26,
            layout.panelTop(),
            1);
    }

    boolean contains(HologramEditorLayout layout, double x, double y) {
        CanvasTransform transform = transform(layout);
        double localX = transform.toLocalX(x), localY = transform.toLocalY(y);
        return localX >= SOURCE_X && localX < SOURCE_X + (expanded ? WIDTH : 18)
            && localY >= SOURCE_Y
            && localY < SOURCE_Y + (expanded ? HEIGHT : 18);
    }

    boolean inList(double sourceY) {
        return expanded && sourceY >= LIST_TOP && sourceY < LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT;
    }
}
