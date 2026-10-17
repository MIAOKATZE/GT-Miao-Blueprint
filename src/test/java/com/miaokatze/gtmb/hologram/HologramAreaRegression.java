package com.miaokatze.gtmb.hologram;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** The same authoritative point route accepts either first mark and locks modifiers at that mark. */
public final class HologramAreaRegression {

    private HologramAreaRegression() {}

    public static void run() {
        HologramAreaPreparationRegression.run();
        HologramSnapshotMetadataRegression.run();
        try {
            HologramArea area = new HologramArea();
            area.point(2, 3, 64, 3, "replace");
            SimpleAssert.that(
                area.first == null && area.second != null && area.regions.isEmpty() && area.points().length == 3,
                "right corner can be marked before any left corner without publishing a complete box");
            area.point(1, 0, 64, 0, "add");
            SimpleAssert.eq("replace", area.regions.get(0).mode, "the first right mark locks replacement mode");
            SimpleAssert
                .that(area.contains(0, 64, 0) && area.contains(3, 64, 3), "both reversed corners are inclusive");
            area.point(2, 7, 64, 3, "add");
            area.point(2, 6, 64, 3, "subtract");
            SimpleAssert.eq("add", area.pointMode, "re-marking the pending right corner preserves its locked mode");
            area.point(1, 4, 64, 0, "replace");
            SimpleAssert.that(
                area.regions.size() == 2 && area.contains(0, 64, 0) && area.contains(6, 64, 3),
                "right-first addition preserves the existing region and uses the latest pending position");
            area.point(2, 2, 64, 2, "subtract");
            area.point(1, 1, 64, 1, "add");
            SimpleAssert.that(
                !area.contains(1, 64, 1) && area.contains(0, 64, 0) && area.contains(6, 64, 3),
                "right-first subtraction locks at the first mark and removes only its intersection");
            area.undoRegion();
            SimpleAssert.that(
                area.contains(1, 64, 1) && area.regions.size() == 2 && area.first == null && area.second == null,
                "undo restores the composite mask and clears both completed marks");
            HologramArea pending = new HologramArea();
            pending.point(2, 8, 64, 8, "add");
            pending.undoRegion();
            SimpleAssert.that(pending.points().length == 0, "undo also clears a lone right-first mark");
            HologramAreaSelection local = new HologramAreaSelection();
            local.readSnapshot(area.snapshot());
            SimpleAssert.that(
                java.util.Arrays.equals(area.bounds(), local.bounds()),
                "pure client selection roundtrips the same final import bounds");
            HologramArea imported = new HologramArea();
            imported.readSnapshot(local.snapshot());
            SimpleAssert.that(
                !imported.contains(1, 64, 1) == !area.contains(1, 64, 1)
                    && imported.contains(6, 64, 3) == area.contains(6, 64, 3),
                "final server geometry preserves composite membership exactly");
            int[] bounds = area.bounds();
            SimpleAssert.that(
                bounds[0] == 0 && bounds[1] == 64
                    && bounds[2] == 0
                    && bounds[3] == 6
                    && bounds[4] == 64
                    && bounds[5] == 3,
                "reversed marks retain the positive import bounds");
        } catch (java.io.IOException failure) {
            throw new AssertionError("authoritative reversed region points", failure);
        }
    }
}
