package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Marker collision and hit bounds use the native viewport origin rather than the old design canvas. */
public final class HologramEditorAnnotationOverlayRegression {

    private HologramEditorAnnotationOverlayRegression() {}

    public static void run() {
        List<HologramAnnotationMarkers.Marker> shown = new ArrayList<>();
        HologramAnnotationMarkers.Marker first = marker(10, 7);
        SimpleAssert.that(
            HologramEditorAnnotationOverlay.place(first, shown, 64, 50),
            "top-local marker remains visible above old design-canvas Y");
        shown.add(first);
        HologramAnnotationMarkers.Marker second = marker(10, 7);
        SimpleAssert.that(HologramEditorAnnotationOverlay.place(second, shown, 64, 50), "collision can shift down");
        SimpleAssert.eq(22, second.y, "collision separates native marker icons by fifteen pixels");
        shown.add(second);
        HologramAnnotationMarkers.Marker third = marker(10, 7);
        SimpleAssert.that(HologramEditorAnnotationOverlay.place(third, shown, 64, 50), "third marker can shift twice");
        SimpleAssert.eq(37, third.y, "all earlier visible markers participate in collision placement");
        shown.add(third);
        SimpleAssert.that(
            !HologramEditorAnnotationOverlay.place(marker(10, 7), shown, 64, 50),
            "collision cannot push the complete hit target below its viewport");
        List<HologramAnnotationMarkers.Marker> empty = new ArrayList<>();
        SimpleAssert.that(!HologramEditorAnnotationOverlay.place(marker(5, 20), empty, 64, 50), "left icon is clipped");
        SimpleAssert
            .that(!HologramEditorAnnotationOverlay.place(marker(57, 20), empty, 64, 50), "right icon is clipped");
        SimpleAssert.that(!HologramEditorAnnotationOverlay.place(marker(20, 6), empty, 64, 50), "top icon is clipped");
        SimpleAssert
            .that(!HologramEditorAnnotationOverlay.place(marker(20, 44), empty, 64, 50), "bottom hit is clipped");
        HologramAnnotationMarkers.Marker edge = marker(56, 43);
        SimpleAssert
            .that(HologramEditorAnnotationOverlay.place(edge, empty, 64, 50), "exact right/bottom fit is valid");
        SimpleAssert.that(edge.contains(50, 37) && edge.contains(62, 49), "complete established hit box is retained");
        SimpleAssert.that(!edge.contains(63, 49), "established right hit edge remains exclusive");
    }

    private static HologramAnnotationMarkers.Marker marker(int x, int y) {
        HologramAnnotationMarkers.Marker marker = new HologramAnnotationMarkers.Marker("test", "title", "prompt", null);
        marker.x = x;
        marker.y = y;
        return marker;
    }
}
