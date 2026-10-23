package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Synthetic host-cell boundaries and exact sparse scans; no private artifacts or million-object fixtures. */
public final class HologramBlueprintLimitsRegression {

    private HologramBlueprintLimitsRegression() {}

    public static void run() {
        try {
            exactBoundary();
            draftCounts();
            orderedMask();
            distantBoxes();
            sparseSections();
            missingChunk();
        } catch (IOException failure) {
            throw new AssertionError("blueprint actual-cell limits", failure);
        }
    }

    private static void exactBoundary() throws IOException {
        SimpleAssert.eq(999999, HologramCapture.LIMIT, "capture uses strict less-than-one-million host count");
        SimpleAssert.eq(HologramCapture.LIMIT, HologramBlueprintCompactCodec.MAX_CELLS, "disk and editor share cap");
        HologramBlueprintLimits.require(999999);
        SimpleAssert.that(HologramBlueprintLimits.accepts(999999), "999999 recorded hosts are admitted");
        SimpleAssert.that(!HologramBlueprintLimits.accepts(1000000), "1000000 recorded hosts are rejected");
        SimpleAssert.that(!HologramBlueprintLimits.accepts(-1), "negative protocol counts are rejected");
        SimpleAssert.that(!HologramBlueprintLimits.accepts(Long.MAX_VALUE), "wide arithmetic never wraps into cap");
        try {
            HologramBlueprintLimits.require(1000000);
            throw new AssertionError("million-cell count was admitted");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("一百万"),
                "rejection states the actual-cell rule");
        }
        HologramPresets.Preset oversized = new HologramPresets.Preset();
        oversized.schema = 2;
        oversized.id = "oversized-synthetic";
        oversized.name = "synthetic count admission";
        oversized.cells = new AbstractList<HologramPresets.Cell>() {

            @Override
            public HologramPresets.Cell get(int index) {
                throw new AssertionError("oversized metadata must reject before accessing any cell");
            }

            @Override
            public int size() {
                return 1000000;
            }
        };
        try {
            HologramPresets.validate(oversized);
            throw new AssertionError("saved blueprint admission accepted one million cells");
        } catch (IOException expected) {}
        try {
            HologramBlueprintCompactCodec.encode(oversized, (done, total) -> {});
            throw new AssertionError("compact writer accepted one million cells");
        } catch (IOException expected) {}
    }

    private static void orderedMask() throws IOException {
        HologramAreaSelection selection = new HologramAreaSelection();
        selection.regions.add(box(-18, 64, -1, -14, 66, 1, "add"));
        selection.regions.add(box(-17, 64, -1, -15, 65, 1, "subtract"));
        selection.regions.add(box(-16, 64, 0, -16, 64, 0, "add"));
        NBTTagCompound before = selection.snapshot();
        HologramAreaBlockCursor cursor = new HologramAreaBlockCursor(selection.resolvedRegions());
        Set<String> visited = new HashSet<>();
        while (!cursor.done()) if (cursor.step((x, y, z) -> false))
            SimpleAssert.that(visited.add(key(cursor.x, cursor.y, cursor.z)), "disjoint masks never duplicate hosts");
        for (int y = 64; y <= 66; y++) for (int z = -1; z <= 1; z++) for (int x = -18; x <= -14; x++) {
            boolean selected = false;
            for (HologramAreaSelection.Region region : selection.regions)
                if (region.contains(x, y, z)) selected = !"subtract".equals(region.mode);
            SimpleAssert.that(selected == visited.contains(key(x, y, z)), "ordered subtraction and re-add stay exact");
        }
        SimpleAssert.that(before.equals(selection.snapshot()), "resolving import leaves UI selection/history intact");
        SimpleAssert.that(cursor.completed() == visited.size() && cursor.total() == visited.size(), "exact progress");
        selection.regions.add(box(-18, 64, -1, -14, 66, 1, "subtract"));
        SimpleAssert.that(
            selection.resolvedRegions()
                .isEmpty(),
            "complete subtraction has no import boxes");
        try {
            new HologramAreaBlockCursor(selection.resolvedRegions());
            throw new AssertionError("empty import mask was admitted");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("没有"),
                "empty mask reports a clear error");
        }
    }

    private static void draftCounts() {
        for (String field : Arrays.asList("automatic", "edits", "blueprintAnnotations")) {
            SimpleAssert.that(
                HologramDraftPersistence.countsValid(countOnlyDraft(field, 999999)),
                "draft count boundary admits 999999 without allocating rows");
            SimpleAssert.that(
                !HologramDraftPersistence.countsValid(countOnlyDraft(field, 1000000)),
                "oversized draft is rejected as a whole instead of truncating retained rows");
        }
    }

    private static NBTTagCompound countOnlyDraft(String field, int count) {
        return new NBTTagCompound() {

            @Override
            public NBTTagList getTagList(String name, int type) {
                return new NBTTagList() {

                    @Override
                    public int tagCount() {
                        return name.equals(field) ? count : 0;
                    }
                };
            }
        };
    }

    private static void distantBoxes() throws IOException {
        List<HologramAreaSelection.Region> boxes = Arrays
            .asList(box(-2000000, 64, 0, -2000000, 64, 0, "add"), box(2000000, 64, 0, 2000000, 64, 0, "add"));
        HologramAreaBlockCursor cursor = new HologramAreaBlockCursor(boxes);
        int[] reads = { 0 };
        Set<String> hosts = new HashSet<>();
        while (!cursor.done()) if (cursor.step((cx, sy, cz) -> {
            reads[0]++;
            SimpleAssert.that(cx == -125000 || cx == 125000, "unselected gap chunks are never read");
            return false;
        })) hosts.add(key(cursor.x, cursor.y, cursor.z));
        SimpleAssert.eq(2, reads[0], "multi-million-cell envelope needs only the two selected sections");
        SimpleAssert.eq(2, hosts.size(), "distant actual hosts remain intact");
        SimpleAssert.that(cursor.total() == 2 && cursor.completed() == 2, "progress excludes unselected gap");
    }

    private static void sparseSections() throws IOException {
        HologramAreaBlockCursor cursor = new HologramAreaBlockCursor(
            Arrays.asList(box(0, 64, 0, 1023, 65, 1023, "add")));
        int[] sectionChecks = { 0 };
        HologramAreaBlockCursor.Sections source = (cx, sy, cz) -> {
            sectionChecks[0]++;
            return cx != 0 || cz != 0;
        };
        SimpleAssert.that(cursor.step(source) && !cursor.done(), "one step cannot synchronously scan a huge box");
        int candidates = 1, actualHosts = 1, steps = 1;
        while (!cursor.done()) {
            steps++;
            if (cursor.step(source)) {
                candidates++;
                if (cursor.x == 15 && cursor.y == 65 && cursor.z == 15) actualHosts++;
            }
        }
        SimpleAssert.eq(2, actualHosts, "synthetic sparse structure records only its two nonair hosts");
        SimpleAssert.eq(512, candidates, "null/empty sections never enumerate their air coordinates");
        SimpleAssert.eq(4096, sectionChecks[0], "each selected section is checked once");
        SimpleAssert.eq(4607, steps, "skipping empty sections charges bounded work instead of hidden loops");
        SimpleAssert.that(cursor.total() == 2097152 && cursor.completed() == cursor.total(), "large sparse progress");
    }

    private static void missingChunk() throws IOException {
        HologramAreaBlockCursor cursor = new HologramAreaBlockCursor(Arrays.asList(box(32, 64, 0, 32, 64, 0, "add")));
        try {
            cursor.step((x, y, z) -> { throw new IOException("unloaded chunk; no loading callback"); });
            throw new AssertionError("missing selected chunk was accepted");
        } catch (IOException expected) {
            SimpleAssert.that(cursor.completed() == 0, "missing selected chunk cannot advance or publish partial data");
        }
    }

    private static HologramAreaSelection.Region box(int x, int y, int z, int xx, int yy, int zz, String mode) {
        return new HologramAreaSelection.Region(new int[] { x, y, z }, new int[] { xx, yy, zz }, mode);
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
