package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Headless coverage of Ctrl prompts and the exact block mask enclosed by live polygon outlines. */
final class HologramSelectionOutlineRegression {

    private HologramSelectionOutlineRegression() {}

    static void run() {
        HologramSelectionSurfaceRegression.run();
        checkCornerSnapshots();
        NBTTagCompound selection = new NBTTagCompound();
        SimpleAssert.eq(
            "camera.selectionHold",
            HologramWorldGuide.cameraSelectionKey(selection, false),
            "before Ctrl only the start hint is shown");
        SimpleAssert.eq(
            "camera.selectionBoth",
            HologramWorldGuide.cameraSelectionKey(selection, true),
            "Ctrl offers both buttons initially");
        selection.setInteger("areaPendingCorner", 1);
        SimpleAssert.eq(
            "camera.selectionSecond",
            HologramWorldGuide.cameraSelectionKey(selection, true),
            "after left only right remains");
        selection.setInteger("areaPendingCorner", 2);
        SimpleAssert.eq(
            "camera.selectionFirst",
            HologramWorldGuide.cameraSelectionKey(selection, true),
            "after right only left remains");
        SimpleAssert.that(
            !HologramAreaOverlay.showTarget(true, false) && HologramAreaOverlay.showTarget(true, true),
            "the red isometric target appears only while Ctrl is held");
        SimpleAssert.that(HologramAreaOverlay.showTarget(false, false), "ordinary view target behavior is preserved");
        SimpleAssert.eq(
            org.lwjgl.input.Keyboard.KEY_B,
            HologramSelectionInput.continuousKeyCode(),
            "free selection defaults to B");
        SimpleAssert.that(
            !HologramSelectionOutline.exterior(true, true, false, false),
            "two adjacent cells do not leave a coplanar grid seam");
        SimpleAssert
            .that(!HologramSelectionOutline.exterior(true, true, true, true), "fully interior edges are hidden");
        SimpleAssert.that(
            HologramSelectionOutline.exterior(true, true, true, false),
            "concave corners and hole rims keep their actual crease");
        SimpleAssert.that(
            HologramSelectionOutline.exterior(true, false, false, true),
            "diagonally touching blocks keep both silhouettes");
        SimpleAssert.that(
            !HologramSelectionOutline
                .ownsEdge(0, 0, 0, 1, 0, 0, 1, (x, y, z) -> y == 0 && z == 0 && (x == 0 || x == 1)),
            "neighboring cubes share no artificial vertical dividing line");
        checkPolygon(new int[] { 0, 64, 0, 4, 64, 0, 4, 64, 1, 1, 64, 1, 1, 64, 4, 0, 64, 4 });
        checkPolygon(new int[] { -2, 64, -2, 4, 64, 1, 0, 64, 4 });
        List<int[]> live = HologramSelectionOutline
            .polygon(new int[] { 0, 64, 0, 4, 64, 0 }, new int[] { 4, 64, 4 }, false);
        SimpleAssert.that(
            encloses(live, 3, 1) && !encloses(live, 0, 4),
            "the cursor previews a filled region before another vertex is committed");
        for (int[] edge : live) SimpleAssert.that(
            edge[0] == edge[2] || edge[1] == edge[3],
            "live outlines follow block boundaries instead of diagonal center lines");
        try {
            HologramAreaSelection pending = new HologramAreaSelection();
            pending.beginContinuous(false);
            pending.continuousPoint(0, 64, 0);
            pending.continuousPoint(4, 64, 0);
            pending.continuousPoint(4, 64, 4);
            pending.continuousPoint(0, 64, 0);
            NBTTagCompound before = pending.snapshot();
            pending.finishContinuous(new int[] { 4, 64, 4 });
            for (int x = -1; x <= 5; x++) for (int z = -1; z <= 5; z++) SimpleAssert
                .that(encloses(live, x, z) == pending.contains(x, 64, z), "B confirms the cursor's actual live region");
            pending.undoRegion();
            SimpleAssert.that(before.equals(pending.snapshot()), "B confirmation is undone as one gesture");
        } catch (java.io.IOException failure) {
            throw new AssertionError("confirming the live polygon", failure);
        }
    }

    private static void checkCornerSnapshots() {
        try {
            HologramAreaSelection area = new HologramAreaSelection();
            SimpleAssert
                .eq(0, HologramAreaOverlay.cornerMask(area.snapshot()), "empty selection has no corner markers");
            area.worldPoint(2, 4, 64, 4);
            SimpleAssert
                .eq(2, HologramAreaOverlay.cornerMask(area.snapshot()), "right-first input keeps the right label");
            area.worldPoint(1, 0, 64, 0);
            int[] first = area.points();
            SimpleAssert
                .eq(3, HologramAreaOverlay.cornerMask(area.snapshot()), "completed box shows both button labels");
            area.toggleMode("add");
            SimpleAssert.eq(
                3,
                HologramAreaOverlay.cornerMask(area.snapshot()),
                "arming a mode leaves the currently editable corners visible until the next box starts");
            area.worldPoint(1, 8, 64, 8);
            SimpleAssert
                .eq(1, HologramAreaOverlay.cornerMask(area.snapshot()), "new box shows only its pending left corner");
            SimpleAssert.that(
                java.util.Arrays.equals(
                    new int[] { 8, 64, 8 },
                    area.snapshot()
                        .getIntArray("areaPoints")),
                "next-box markers cannot retain the previous box's coordinates");
            area.undoRegion();
            SimpleAssert
                .eq(3, HologramAreaOverlay.cornerMask(area.snapshot()), "undo restores both previous corner labels");
            SimpleAssert
                .that(java.util.Arrays.equals(first, area.points()), "undo restores the prior corner identities");
            area.worldPoint(2, 9, 64, 9);
            SimpleAssert
                .eq(2, HologramAreaOverlay.cornerMask(area.snapshot()), "new right-first box has only its right label");
            area.deleteLatest();
            SimpleAssert
                .eq(3, HologramAreaOverlay.cornerMask(area.snapshot()), "Delete restores previous corner labels");
            SimpleAssert
                .that(java.util.Arrays.equals(first, area.points()), "Delete restores the prior corner identities");
            area.beginNext("subtract");
            SimpleAssert
                .eq(0, HologramAreaOverlay.cornerMask(area.snapshot()), "an empty next-box gesture hides old markers");
            area.undoRegion();
            SimpleAssert
                .eq(3, HologramAreaOverlay.cornerMask(area.snapshot()), "undoing next-box entry restores old markers");
            area.beginContinuous(false);
            SimpleAssert
                .eq(0, HologramAreaOverlay.cornerMask(area.snapshot()), "polygon entry uses its own vertex markers");
        } catch (java.io.IOException failure) {
            throw new AssertionError("ordinary corner overlay snapshot protocol", failure);
        }
    }

    private static void checkPolygon(int[] vertices) {
        try {
            HologramAreaSelection area = new HologramAreaSelection();
            area.beginContinuous(false);
            for (int i = 0; i < vertices.length; i += 3)
                area.continuousPoint(vertices[i], vertices[i + 1], vertices[i + 2]);
            area.continuousPoint(vertices[0], vertices[1], vertices[2]);
            area.finishContinuous(66);
            SimpleAssert.that(!area.continuousActive(), "the second B action closes and confirms the polygon");
            List<int[]> edges = HologramSelectionOutline.polygon(vertices, null, true);
            for (int x = -3; x <= 6; x++) for (int z = -3; z <= 6; z++) SimpleAssert.that(
                encloses(edges, x, z) == area.contains(x, 65, z),
                "visible perimeter encloses exactly the submitted block mask at " + x + "," + z);
            area.undoRegion();
            SimpleAssert.that(area.continuousActive(), "one undo restores the pre-confirmation free selection");
        } catch (java.io.IOException failure) {
            throw new AssertionError("free selection outline", failure);
        }
    }

    private static boolean encloses(List<int[]> edges, int x, int z) {
        boolean inside = false;
        for (int[] edge : edges) if (edge[0] == edge[2] && edge[0] > x + .5
            && z + .5 >= Math.min(edge[1], edge[3])
            && z + .5 < Math.max(edge[1], edge[3])) inside = !inside;
        return inside;
    }
}
