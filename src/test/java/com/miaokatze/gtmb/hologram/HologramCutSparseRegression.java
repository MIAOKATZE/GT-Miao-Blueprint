package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Pure geometry/grouping/continuation evidence; this does not assert native world lifecycle or durability. */
public final class HologramCutSparseRegression {

    private HologramCutSparseRegression() {}

    public static void run() {
        try {
            orderedRuns();
            sparseHaloAndGroups();
            continuations();
        } catch (Exception failure) {
            throw new AssertionError("sparse cut preparation", failure);
        }
    }

    private static void orderedRuns() throws IOException {
        Random random = new Random(717);
        for (int attempt = 0; attempt < 60; attempt++) {
            HologramAreaSelection selection = new HologramAreaSelection();
            selection.regions
                .add(new HologramAreaSelection.Region(new int[] { -9, 4, -11 }, new int[] { 6, 8, 4 }, "add"));
            for (int i = 0; i < 20; i++) {
                int[] a = { random.nextInt(22) - 12, random.nextInt(9) + 2, random.nextInt(22) - 14 };
                int[] b = { random.nextInt(22) - 12, random.nextInt(9) + 2, random.nextInt(22) - 14 };
                selection.regions.add(new HologramAreaSelection.Region(a, b, i % 3 == 0 ? "subtract" : "add"));
            }
            BitSet expected = new BitSet();
            for (int y = 0; y < 5; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                if (selection.contains(x - 9, y + 4, z - 11)) expected.set((y * 16 + z) * 16 + x);
            HologramCutSelection.Builder builder = new HologramCutSelection.Builder(selection, -9, 4, -11, 16, 5, 16);
            int slices = 0;
            while (!builder.step(1)) slices++;
            if (expected.isEmpty()) {
                try {
                    builder.result();
                    throw new AssertionError("empty constructive selection must fail");
                } catch (IOException expectedEmpty) {}
            } else
                SimpleAssert.that(expected.equals(builder.result()), "ordered add/subtract runs equal cell membership");
            SimpleAssert.that(slices < 21 * 5 * 16, "continuation counts intersecting region rows, not bbox cells");
        }
    }

    private static void sparseHaloAndGroups() throws IOException {
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell(0, 0, 0));
        cells.appendTag(cell(255, 0, 255));
        cells.appendTag(cell(0, 7, 0));
        HologramCutCellGroups groups = new HologramCutCellGroups(cells, -16, -16);
        SimpleAssert.eq(2, groups.chunks.size(), "distant selected cells touch only two chunks");
        SimpleAssert.eq(
            2,
            groups.chunks.get(HologramCutChunks.key(-1, -1))
                .size(),
            "negative chunk groups include air");
        int total = 0;
        for (Map.Entry<Long, List<NBTTagCompound>> group : groups.chunks.entrySet()) total += group.getValue()
            .size();
        SimpleAssert.eq(3, total, "each selected cell belongs to exactly one group");
        Set<Long> halo = HologramCutChunks.lightNeighbors(cells, -16, -16);
        SimpleAssert.eq(50, halo.size(), "two disjoint complete chunk radius-17 halos are a union");
        SimpleAssert.that(halo.contains(HologramCutChunks.key(-3, -3)), "negative radius-17 corner is real");
        SimpleAssert
            .that(halo.contains(HologramCutChunks.key(16, 16)), "new-section untouched columns retain their halo");
        SimpleAssert.that(!halo.contains(HologramCutChunks.key(6, 6)), "large sparse bbox gap stays unloaded");
        HologramAreaSelection selection = new HologramAreaSelection();
        selection.regions
            .add(new HologramAreaSelection.Region(new int[] { -16, 1, -16 }, new int[] { -16, 1, -16 }, "add"));
        selection.regions
            .add(new HologramAreaSelection.Region(new int[] { 239, 1, 239 }, new int[] { 239, 1, 239 }, "add"));
        HologramCutSelection.Builder sparse = new HologramCutSelection.Builder(selection, -16, 1, -16, 256, 1, 256);
        SimpleAssert.that(!sparse.step(1) && sparse.step(1), "two remote points require exactly two row slices");
        SimpleAssert.eq(
            2,
            sparse.result()
                .cardinality(),
            "sparse cursor retains exactly both selected points");
    }

    private static void continuations() throws IOException {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setInteger("sizeX", 3);
        entry.setInteger("sizeY", 1);
        entry.setInteger("sizeZ", 1);
        entry.setString("name", "");
        NBTTagCompound extension = new NBTTagCompound();
        extension.setString("owner", "extension-state");
        entry.setTag("futureExtension", extension);
        NBTTagList cells = new NBTTagList();
        for (int x = 0; x < 3; x++) cells.appendTag(cell(x, 0, 0));
        entry.setTag("cells", cells);
        NBTTagCompound baseline = (NBTTagCompound) entry.copy();
        HologramCutEntry.Validation validation = new HologramCutEntry.Validation(entry);
        SimpleAssert.that(!validation.step(1, Long.MAX_VALUE), "validation yields before full admission");
        SimpleAssert.that(
            !validation.step(1, Long.MAX_VALUE) && validation.step(1, Long.MAX_VALUE),
            "validation resumes its cursor");
        HologramCutTransforms.Task task = new HologramCutTransforms.Task(
            entry,
            new CutTransforms.Transform(0, 0, 0, 0),
            0,
            0,
            0,
            0);
        SimpleAssert.that(!task.step(1, Long.MAX_VALUE), "transform yields after one owned cell");
        try {
            task.result();
            throw new AssertionError("partial transform cannot publish");
        } catch (IllegalStateException expectedIncomplete) {}
        while (!task.step(1, Long.MAX_VALUE)) {}
        SimpleAssert.that(baseline.equals(entry), "sliced transform preserves the source raw entry");
        SimpleAssert.eq(
            extension,
            task.result()
                .getCompoundTag("futureExtension"),
            "unknown header fields remain intact");
        task.result()
            .getCompoundTag("futureExtension")
            .setString("owner", "changed-copy");
        SimpleAssert
            .eq("extension-state", extension.getString("owner"), "extension headers are detached from the raw source");
        SimpleAssert.that(
            task.result()
                .getString("name")
                .isEmpty(),
            "pure transform preserves empty header values");
        task.result()
            .getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setInteger("meta", 7);
        SimpleAssert.eq(
            0,
            cells.getCompoundTagAt(0)
                .getInteger("meta"),
            "published result has independent cells");
        HologramCutTransforms.Task stale = new HologramCutTransforms.Task(
            entry,
            new CutTransforms.Transform(0, 0, 0, 0),
            0,
            0,
            0,
            0);
        Runnable remove = HologramCutTransforms.install(new CutTransforms.Adapter() {

            public boolean accepts(CutTransforms.State state) {
                return false;
            }

            public void transform(CutTransforms.State state, CutTransforms.Transform transform) {}
        });
        try {
            stale.step(1, Long.MAX_VALUE);
            throw new AssertionError("changed raw adapter generation must invalidate in-flight result");
        } catch (IllegalStateException expectedChanged) {} finally {
            remove.run();
        }
        HologramCutTransforms.Task staleLegacy = new HologramCutTransforms.Task(
            entry,
            new CutTransforms.Transform(0, 0, 0, 0),
            0,
            0,
            0,
            0);
        try (AutoCloseable registration = HologramBlockTransformApi.register(new HologramBlockTransformApi.Adapter() {

            public boolean accepts(HologramBlockTransformApi.State state) {
                return false;
            }

            public void rotate(HologramBlockTransformApi.State state, boolean vertical) {}
        })) {
            try {
                staleLegacy.step(1, Long.MAX_VALUE);
                throw new AssertionError("changed legacy adapter generation must invalidate in-flight result");
            } catch (IllegalStateException expectedChanged) {}
        } catch (Exception failure) {
            throw new AssertionError("legacy transform fence", failure);
        }
        NBTTagCompound duplicate = (NBTTagCompound) entry.copy();
        duplicate.getTagList("cells", 10)
            .getCompoundTagAt(2)
            .setInteger("x", 0);
        HologramCutEntry.Validation invalid = new HologramCutEntry.Validation(duplicate);
        SimpleAssert.that(!invalid.step(2, Long.MAX_VALUE), "duplicate beyond first slice remains unadmitted");
        try {
            invalid.step(1, Long.MAX_VALUE);
            throw new AssertionError("cross-slice duplicate cell must fail");
        } catch (IOException expectedDuplicate) {}
    }

    private static NBTTagCompound cell(int x, int y, int z) {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        cell.setInteger("y", y);
        cell.setInteger("z", z);
        cell.setInteger("meta", 0);
        cell.setString("block", "minecraft:air");
        return cell;
    }
}
