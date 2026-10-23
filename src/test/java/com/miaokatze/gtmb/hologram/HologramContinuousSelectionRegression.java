package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** World selection history and polygon compilation use the same bounded geometry as server import. */
final class HologramContinuousSelectionRegression {

    static void run() {
        try {
            pendingDeletion();
            completedDeletion();
            rejectedPolygonDeletion();
            HologramAreaSelection area = new HologramAreaSelection();
            HologramAreaSelection camera = new HologramAreaSelection();
            camera.worldPoint(0, 0, 64, 0);
            camera.worldPoint(0, 2, 66, 2);
            camera.worldPoint(0, 1, 64, 0);
            camera.worldPoint(0, 3, 66, 2);
            SimpleAssert.that(
                camera.complete() && !camera.contains(0, 64, 0) && camera.contains(3, 66, 2),
                "isometric left clicks alternate editable corners after the initial pair");
            camera.undoRegion();
            camera.worldPoint(0, 4, 66, 2);
            SimpleAssert.that(camera.contains(4, 66, 2), "corner alternation is restored by local undo");
            area.toggleMode("add");
            SimpleAssert.eq(
                "replace",
                area.snapshot()
                    .getString("areaInputMode"),
                "add requires a complete first selection");
            area.worldPoint(1, 0, 60, 0);
            area.toggleMode("subtract");
            SimpleAssert.eq(
                "replace",
                area.snapshot()
                    .getString("areaInputMode"),
                "a single corner cannot arm subtraction");
            area.worldPoint(2, 2, 62, 2);
            area.toggleMode("add");
            area.worldPoint(1, 4, 60, 0);
            area.worldPoint(2, 5, 62, 2);
            SimpleAssert.that(
                area.contains(0, 60, 0) && area.contains(5, 62, 2),
                "Z mode creates another box using plain clicks");
            area.toggleMode("add");
            SimpleAssert.eq(
                "replace",
                area.snapshot()
                    .getString("areaInputMode"),
                "repeated Z disables additive mode");
            NBTTagCompound previous = area.snapshot();
            area.toggleMode("subtract");
            area.undoRegion();
            SimpleAssert.that(previous.equals(area.snapshot()), "mode toggles belong to the geometry history");
            area.redoRegion();
            SimpleAssert.eq(
                "subtract",
                area.snapshot()
                    .getString("areaInputMode"),
                "Ctrl Y restores a mode toggle");
            area.worldPoint(1, 1, 60, 1);
            area.worldPoint(2, 4, 62, 1);
            boolean[] mask = mask(area);
            area.mergeRegions();
            sameMask(mask, area);
            for (HologramAreaSelection.Region region : area.regions)
                SimpleAssert.that(!"subtract".equals(region.mode), "merge resolves every negative region");
            for (int i = 0; i < area.regions.size(); i++) for (int j = i + 1; j < area.regions.size(); j++) {
                HologramAreaSelection.Region a = area.regions.get(i), b = area.regions.get(j);
                SimpleAssert.that(
                    a.maxX < b.minX || b.maxX < a.minX
                        || a.maxY < b.minY
                        || b.maxY < a.minY
                        || a.maxZ < b.minZ
                        || b.maxZ < a.minZ,
                    "merged positives never overlap");
            }
            area.undoRegion();
            sameMask(mask, area);
            area.redoRegion();
            sameMask(mask, area);

            HologramAreaSelection degenerate = new HologramAreaSelection();
            degenerate.beginContinuous(false);
            degenerate.continuousPoint(0, 64, 0);
            degenerate.continuousPoint(1, 64, 1);
            degenerate.continuousPoint(2, 64, 2);
            NBTTagCompound pendingLine = degenerate.snapshot();
            boolean rejected = false;
            try {
                degenerate.continuousPoint(0, 64, 0);
            } catch (IOException expected) {
                rejected = true;
            }
            SimpleAssert.that(
                rejected && pendingLine.equals(degenerate.snapshot()),
                "three collinear vertices cannot close and retain the complete pending line");

            HologramAreaSelection polygon = new HologramAreaSelection();
            polygon.beginContinuous(false);
            polygon.continuousPoint(0, 64, 0);
            polygon.continuousPoint(4, 99, 0);
            polygon.continuousPoint(4, 64, 1);
            polygon.continuousPoint(1, 64, 1);
            polygon.continuousPoint(1, 64, 4);
            polygon.continuousPoint(0, 64, 4);
            polygon.continuousPoint(0, 64, 0);
            polygon.continuousPoint(20, 66, 20);
            polygon.continuousPoint(20, 62, 20);
            polygon.undoRegion();
            SimpleAssert.eq(
                66,
                polygon.snapshot()
                    .getInteger("areaPolygonHeight"),
                "height can be adjusted repeatedly and undone");
            polygon.redoRegion();
            polygon.confirmContinuous();
            SimpleAssert.that(
                !polygon.continuousActive() && polygon.contains(0, 62, 3)
                    && polygon.contains(4, 64, 0)
                    && !polygon.contains(3, 64, 3)
                    && !polygon.contains(0, 65, 0),
                "concave polygon uses the first point plane and final target height");
            HologramAreaSelection imported = new HologramAreaSelection();
            imported.readSnapshot(polygon.snapshot());
            SimpleAssert.that(
                imported.contains(4, 64, 0) && !imported.contains(3, 64, 3),
                "polygon survives existing region wire protocol");
            polygon.undoRegion();
            SimpleAssert.that(
                polygon.continuousActive() && polygon.snapshot()
                    .getBoolean("areaPolygonClosed"),
                "undo V restores editable closed polygon");
            polygon.redoRegion();
            polygon.beginContinuous(true);
            polygon.continuousPoint(0, 62, 0);
            polygon.continuousPoint(1, 62, 0);
            polygon.continuousPoint(1, 62, 1);
            polygon.continuousPoint(0, 62, 0);
            polygon.continuousPoint(0, 64, 0);
            polygon.confirmContinuous();
            SimpleAssert.that(
                !polygon.contains(0, 64, 0) && polygon.contains(0, 64, 4),
                "Shift C subtracts its polygon without losing the existing selection");
            polygon.beginContinuous(false);
            polygon.continuousPoint(10, 64, 10);
            polygon.beginContinuous(false);
            SimpleAssert.eq(
                0,
                polygon.snapshot()
                    .getIntArray("areaPolygon").length,
                "C while drawing clears only the pending line");
            polygon.undoRegion();
            SimpleAssert.eq(
                3,
                polygon.snapshot()
                    .getIntArray("areaPolygon").length,
                "line restart is reversible");
        } catch (IOException failure) {
            throw new AssertionError("continuous selection geometry", failure);
        }
    }

    private static void pendingDeletion() throws IOException {
        HologramAreaSelection area = new HologramAreaSelection();
        area.worldPoint(2, 9, 64, 9);
        area.deleteLatest();
        SimpleAssert.eq(0, area.points().length, "Delete clears a pending second corner without completing a box");
        area.worldPoint(1, 0, 64, 0);
        area.worldPoint(2, 4, 66, 4);
        area.beginContinuous(false);
        area.continuousPoint(10, 64, 10);
        area.continuousPoint(12, 64, 10);
        area.continuousPoint(10, 64, 12);
        area.continuousPoint(10, 64, 10);
        area.continuousPoint(10, 70, 10);
        area.deleteLatest();
        NBTTagCompound reopened = area.snapshot();
        SimpleAssert.that(
            area.continuousActive() && reopened.getIntArray("areaPolygon").length == 6
                && !reopened.getBoolean("areaPolygonClosed")
                && !reopened.getBoolean("areaPolygonHeightChosen")
                && area.contains(4, 66, 4),
            "Delete removes the last vertex of a closed/high polygon and preserves committed geometry");
        area.undoRegion();
        SimpleAssert.that(
            area.snapshot().getBoolean("areaPolygonHeightChosen"),
            "undo Delete restores the closed outline and chosen height");
        area.redoRegion();
        area.deleteLatest();
        area.deleteLatest();
        SimpleAssert.that(
            !area.continuousActive() && area.snapshot().getIntArray("areaPolygon").length == 0
                && area.contains(4, 66, 4),
            "removing the final polygon vertex exits pending mode without removing an earlier selection");
        area.beginContinuous(false);
        area.deleteLatest();
        SimpleAssert.that(!area.continuousActive() && area.complete(), "Delete also exits an empty armed polygon");
        area.beginNext("add");
        area.worldPoint(1, 8, 64, 8);
        area.deleteLatest();
        SimpleAssert.that(area.complete() && area.contains(4, 66, 4), "pending half-box deletion restores prior box");
    }

    private static void completedDeletion() throws IOException {
        HologramAreaSelection area = new HologramAreaSelection();
        area.worldPoint(1, 0, 64, 0);
        area.worldPoint(2, 4, 66, 4);
        area.beginContinuous(true);
        area.continuousPoint(0, 64, 0);
        area.continuousPoint(4, 64, 0);
        area.continuousPoint(0, 64, 4);
        area.finishContinuous(66);
        SimpleAssert.that(
            area.regionSnapshot().tagCount() > 2 && !area.contains(0, 64, 0) && area.contains(4, 64, 4),
            "fixture has a multi-row subtractive polygon over a completed box");
        area.cycle(1);
        area.move(1, 0, 0, 256);
        area.deleteLatest();
        SimpleAssert.that(
            area.regionSnapshot().tagCount() == 1 && area.contains(1, 64, 0) && area.contains(5, 66, 4),
            "Delete removes the entire latest polygon despite cycling to and moving an older box");
        area.undoRegion();
        area.redoRegion();
        SimpleAssert.eq(1, area.regionSnapshot().tagCount(), "redo Delete preserves polygon batch identity");
        area.toggleMode("add");
        area.worldPoint(1, 10, 64, 10);
        area.worldPoint(2, 11, 65, 11);
        area.cycle(-1);
        area.deleteLatest();
        SimpleAssert.that(
            area.regionSnapshot().tagCount() == 1 && !area.contains(10, 64, 10) && area.contains(5, 66, 4),
            "Delete removes the latest completed box instead of the cycled current box");
        area.undoRegion();
        area.mergeRegions();
        area.deleteLatest();
        SimpleAssert.that(
            area.regionSnapshot().tagCount() == 0 && !area.complete(),
            "the resolved merge is deleted as one whole selection");
        area.undoRegion();
        SimpleAssert.that(
            area.contains(5, 66, 4) && area.contains(10, 64, 10),
            "undo restores all disjoint merged regions");
    }

    private static void rejectedPolygonDeletion() throws IOException {
        HologramAreaSelection area = new HologramAreaSelection();
        for (int x = 0; x < 127; x++) {
            if (x != 0) area.beginNext("add");
            area.worldPoint(1, x, 64, 0);
            area.worldPoint(2, x, 64, 0);
        }
        area.beginContinuous(false);
        area.continuousPoint(0, 64, 10);
        area.continuousPoint(4, 64, 10);
        area.continuousPoint(0, 64, 14);
        NBTTagCompound pending = area.snapshot();
        boolean rejected = false;
        try {
            area.finishContinuous(66);
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(
            rejected && pending.equals(area.snapshot()),
            "polygon exceeding the 128-region limit leaves pending input and completed batches intact");
        for (int vertex = 0; vertex < 3; vertex++) area.deleteLatest();
        area.deleteLatest();
        SimpleAssert.that(
            area.regionSnapshot().tagCount() == 126 && !area.contains(126, 64, 0) && area.contains(125, 64, 0),
            "after deleting a rejected polygon's vertices, Delete still removes only the latest completed box");
    }

    private static boolean[] mask(HologramAreaSelection area) {
        boolean[] mask = new boolean[8 * 6 * 6];
        int at = 0;
        for (int x = -1; x < 7; x++)
            for (int y = 59; y < 65; y++) for (int z = -1; z < 5; z++) mask[at++] = area.contains(x, y, z);
        return mask;
    }

    private static void sameMask(boolean[] expected, HologramAreaSelection area) {
        boolean[] actual = mask(area);
        for (int i = 0; i < actual.length; i++)
            SimpleAssert.that(actual[i] == expected[i], "merge and history preserve the exact selected block mask");
    }
}
