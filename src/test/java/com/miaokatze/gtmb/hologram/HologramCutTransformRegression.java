package com.miaokatze.gtmb.hologram;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Signed-permutation geometry, detached raw state, known directions and exact extension lifecycle. */
public final class HologramCutTransformRegression {

    private HologramCutTransformRegression() {}

    public static void run() {
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int mirror = 0; mirror < 8; mirror++) {
                CutTransforms.Transform pose = new CutTransforms.Transform(y, x, z, mirror);
                int[] size = pose.size(2, 3, 4);
                Set<String> positions = new HashSet<>();
                for (int a = 0; a < 2; a++) for (int b = 0; b < 3; b++) for (int c = 0; c < 4; c++) {
                    int[] at = pose.point(a, b, c, 2, 3, 4);
                    SimpleAssert.that(
                        at[0] >= 0 && at[0] < size[0] && at[1] >= 0 && at[1] < size[1] && at[2] >= 0 && at[2] < size[2],
                        "pose normalized inside transformed volume");
                    positions.add(at[0] + ":" + at[1] + ":" + at[2]);
                }
                SimpleAssert.eq(24, positions.size(), "every pose is a complete volume permutation");
                Set<Integer> directions = new HashSet<>();
                for (int face = 0; face < 6; face++) directions.add(pose.direction(face));
                SimpleAssert.eq(6, directions.size(), "every pose permutes all native faces");
            }
        NBTTagCompound entry = new NBTTagCompound(), cell = new NBTTagCompound(), tile = new NBTTagCompound();
        entry.setInteger("sizeX", 2);
        entry.setInteger("sizeY", 1);
        entry.setInteger("sizeZ", 1);
        cell.setString("block", "minecraft:chest");
        cell.setInteger("meta", 2);
        tile.setString("id", "Chest");
        tile.setString("owner", "original");
        NBTTagCompound unknown = new NBTTagCompound();
        unknown.setInteger("privateDirection", 47);
        unknown.setString("inventory", "native-full-payload");
        tile.setTag("modPrivate", unknown);
        cell.setTag("tile", tile);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        NBTTagCompound air = new NBTTagCompound();
        air.setString("block", "minecraft:air");
        air.setInteger("x", 1);
        cells.appendTag(air);
        entry.setTag("cells", cells);
        NBTTagCompound original = (NBTTagCompound) entry.copy();
        NBTTagCompound transformed = HologramCutTransforms
            .apply(entry, new CutTransforms.Transform(1, 0, 0, 0), 10, 80, 10);
        SimpleAssert.that(entry.equals(original), "preview transform cannot mutate queue original");
        SimpleAssert.eq(1, transformed.getInteger("sizeX"), "rotated narrow X");
        SimpleAssert.eq(2, transformed.getInteger("sizeZ"), "rotated long Z");
        NBTTagCompound changed = transformed.getTagList("cells", 10)
            .getCompoundTagAt(0);
        SimpleAssert.eq(5, changed.getInteger("meta"), "native NORTH becomes EAST");
        SimpleAssert.that(
            tile.equals(changed.getCompoundTag("tile")),
            "unknown inventory and identity retain exact raw values");
        SimpleAssert.eq(
            2,
            transformed.getTagList("cells", 10)
                .tagCount(),
            "air is transformed too");
        final int[] calls = { 0 };
        CutTransforms.Adapter adapter = new CutTransforms.Adapter() {

            public boolean accepts(CutTransforms.State state) {
                if (state.tile != null) state.tile.setString("owner", "discarded-predicate-write");
                return state.block == Blocks.chest;
            }

            public void transform(CutTransforms.State state, CutTransforms.Transform pose) {
                calls[0]++;
                state.metadata = 4;
            }
        };
        GTMBExtensions.Registration registration = CutTransforms.register("gtmb_test:cut_raw", adapter);
        SimpleAssert.eq("cut-transform", registration.descriptor().kind, "catalog descriptor kind");
        try {
            NBTTagCompound custom = HologramCutTransforms
                .apply(entry, new CutTransforms.Transform(1, 0, 0, 0), 0, 0, 0);
            SimpleAssert.eq(
                4,
                custom.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .getInteger("meta"),
                "community raw adapter executes");
            SimpleAssert.eq(
                "original",
                custom.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .getCompoundTag("tile")
                    .getString("owner"),
                "accepts receives disposable NBT");
        } finally {
            registration.close();
            registration.close();
        }
        HologramCutTransforms.apply(entry, new CutTransforms.Transform(1, 0, 0, 0), 0, 0, 0);
        SimpleAssert.eq(1, calls[0], "closed exact registration no longer runs");
        SimpleAssert.that(entry.equals(original), "all extension operations preserve raw original");
    }
}
