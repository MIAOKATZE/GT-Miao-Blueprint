package com.miaokatze.gtmb.hologram;

import java.util.Arrays;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Local selection transitions are independent of capture size, networking and blueprint processing. */
public final class HologramAreaRegression {

    private HologramAreaRegression() {}

    public static void run() {
        HologramAreaPreparationRegression.run();
        HologramSnapshotMetadataRegression.run();
        try {
            HologramArea area = new HologramArea();
            area.point(2, 3, 64, 3, "replace");
            SimpleAssert.that(
                area.first == null && area.second != null && area.regions.isEmpty(),
                "right corner can be marked first without a complete box");
            area.point(1, 0, 64, 0, "add");
            SimpleAssert.eq("replace", area.regions.get(0).mode, "the first mark locks the mode");
            SimpleAssert.that(
                area.complete() && area.contains(0, 64, 0) && area.contains(3, 64, 3),
                "either corner order completes an inclusive current box");
            area.undoRegion();
            SimpleAssert.that(
                area.first == null && area.second != null && area.regions.isEmpty(),
                "undoing point 1 preserves the earlier point 2");
            area.undoRegion();
            SimpleAssert.that(area.points().length == 0, "a second undo removes only the earlier point 2");
            area.point(1, 0, 64, 0, "replace");
            area.point(2, 3, 64, 3, "replace");
            area.point(1, 1, 64, 0, "replace");
            SimpleAssert.that(
                area.regions.size() == 1 && !area.contains(0, 64, 0),
                "plain left edits point 1 of the completed current box");
            area.point(2, 4, 64, 3, "replace");
            SimpleAssert.that(
                area.regions.size() == 1 && area.contains(4, 64, 3),
                "plain right edits point 2 of the completed current box");
            area.undoRegion();
            SimpleAssert.eq(3, area.second[0], "undo restores the exact preceding corner position");
            area.beginNext("add");
            SimpleAssert.that(
                area.addingRegion() && area.points().length == 0 && area.regions.size() == 1,
                "one more-box gesture preserves previous boxes and clears only the new pair");
            area.undoRegion();
            SimpleAssert.that(
                area.complete() && area.currentRegion() == 0,
                "undo of the more-box gesture restores the previous current box");
            area.beginNext("add");
            area.point(2, 7, 64, 3, "replace");
            area.point(1, 5, 64, 0, "replace");
            SimpleAssert.that(
                area.regions.size() == 2 && area.currentRegion() == 1 && area.contains(6, 64, 3),
                "ordinary clicks complete the armed additional box and select the latest box");
            area.move(0, 1, 0, 256);
            SimpleAssert.that(
                area.contains(6, 65, 3) && !area.contains(6, 64, 3) && area.contains(1, 64, 0),
                "moving the current box preserves every other box");
            area.undoRegion();
            SimpleAssert.that(area.contains(6, 64, 3) && area.second[1] == 64, "moving a box is one reversible input");
            area.cycle(-1);
            SimpleAssert.eq(0, area.currentRegion(), "wheel selects the earlier box");
            SimpleAssert
                .that(area.first[0] == 1 && area.second[0] == 3, "cycling restores the original corner identities");
            area.deleteCurrent();
            SimpleAssert.that(
                area.regions.size() == 1 && !area.contains(1, 64, 0) && area.contains(6, 64, 3),
                "Delete removes only the current box");
            area.undoRegion();
            SimpleAssert.that(
                area.regions.size() == 2 && area.currentRegion() == 0,
                "Delete undo restores geometry and the current box");
            HologramAreaSelection local = new HologramAreaSelection();
            local.readSnapshot(area.snapshot());
            local.cycle(1);
            SimpleAssert.that(
                local.points()[0] == 5 && local.points()[3] == 7,
                "bounded snapshots retain corner identities of noncurrent boxes");
            local.point(2, 6, 64, 3, "replace");
            SimpleAssert
                .that(local.contains(6, 64, 3) && !local.contains(7, 64, 3), "a restored current box stays editable");
            local.point(2, 2, 64, 2, "subtract");
            local.point(1, 1, 64, 1, "replace");
            SimpleAssert.that(
                !local.contains(1, 64, 1) && local.contains(1, 64, 0),
                "the explicit modifier shortcut adds an ordered subtraction mask");
            local.undoRegion();
            SimpleAssert.that(
                local.snapshot()
                    .getInteger("areaPendingCorner") == 2 && local.contains(1, 64, 1),
                "undo subtraction completion retains its first-marked corner and restores the mask");
            local.undoRegion();
            HologramArea imported = new HologramArea();
            imported.readSnapshot(local.snapshot());
            SimpleAssert.that(
                Arrays.equals(local.bounds(), imported.bounds()) && imported.contains(6, 64, 3),
                "final server geometry preserves the local composite selection");
            local.move(0, -65, 0, 256);
            SimpleAssert.that(local.points()[1] == 64, "movement cannot put either corner outside world height");
        } catch (java.io.IOException failure) {
            throw new AssertionError("selection state transitions", failure);
        }
    }
}
