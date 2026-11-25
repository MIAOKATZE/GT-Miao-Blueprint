// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
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
        identityPreviewEnvelope(entry, original);
        vanillaPlacementEncodings();
        strictVanillaLeavesTheLegacyRawPath(entry);
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
        sparsePoses(entry);
    }

    private static void vanillaPlacementEncodings() {
        int no = HologramCutTransformMetadata.UNREPRESENTABLE;
        int[] support = { -1, 5, 4, 3, 2, 1 }, stairs = { 5, 4, 3, 2 };
        Block[] upright = { Blocks.chest, Blocks.trapped_chest, Blocks.furnace, Blocks.lit_furnace, Blocks.ender_chest,
            Blocks.wall_sign };
        Block[] sixFaces = { Blocks.dispenser, Blocks.dropper, Blocks.piston, Blocks.sticky_piston,
            Blocks.piston_head };
        Set<String> tested = new HashSet<>();
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int mirror = 0; mirror < 8; mirror++) {
                CutTransforms.Transform pose = new CutTransforms.Transform(y, x, z, mirror);
                if (!tested.add(java.util.Arrays.toString(pose.matrix()))) continue;
                int up = pose.direction(1);
                boolean vertical = up < 2;
                for (int face = 2; face <= 5; face++) {
                    int next = pose.direction(face);
                    for (Block block : upright) pinned(block, face, pose, up == 1 ? next : no);
                    pinned(Blocks.ladder, face, pose, vertical ? next : no);
                }
                for (int face = 0; face <= 5; face++) {
                    int next = pose.direction(face);
                    for (Block block : sixFaces) pinned(block, face | 8, pose, next | 8);
                    pinned(Blocks.hopper, face | 8, pose, up == 1 && face != 1 ? next | 8 : no);
                }
                for (int encoded = 1; encoded <= 5; encoded++) {
                    int next = pose.direction(support[encoded]);
                    int torch = encoded(support, next, 1, 6, no);
                    for (Block block : new Block[] { Blocks.torch, Blocks.redstone_torch, Blocks.unlit_redstone_torch })
                        pinned(block, encoded, pose, torch);
                    int button = encoded(support, next, 1, 5, no);
                    for (Block block : new Block[] { Blocks.stone_button, Blocks.wooden_button })
                        pinned(block, encoded | 8, pose, vertical && encoded < 5 && button != no ? button | 8 : no);
                }
                for (int meta = 0; meta < 8; meta++) {
                    int heading = encoded(stairs, pose.direction(stairs[meta & 3]), 0, 4, no);
                    int half = (meta & 4) ^ (up == 0 ? 4 : 0);
                    pinned(Blocks.oak_stairs, meta, pose, vertical && heading != no ? heading | half : no);
                }
                for (int heading = 0; heading < 16; heading++) {
                    int result = no;
                    if (up == 1) {
                        // Independent angular oracle for all native 16-way headings.
                        double angle = heading * Math.PI / 8;
                        int[] axisX = pose.vector(1, 0, 0), axisZ = pose.vector(0, 0, 1);
                        double east = -axisX[0] * Math.sin(angle) + axisZ[0] * Math.cos(angle);
                        double south = -axisX[2] * Math.sin(angle) + axisZ[2] * Math.cos(angle);
                        result = Math.floorMod((int) Math.round(Math.atan2(-east, south) * 8 / Math.PI), 16);
                    }
                    pinned(Blocks.standing_sign, heading, pose, result);
                }
                int[] axisFaces = { 1, 5, 3 };
                int[] logForFace = { 0, 0, 8, 8, 4, 4 }, quartzForFace = { 2, 2, 4, 4, 3, 3 };
                for (int axis = 0; axis < axisFaces.length; axis++) {
                    int face = pose.direction(axisFaces[axis]);
                    pinned(Blocks.log, axis * 4 | 3, pose, logForFace[face] | 3);
                    pinned(Blocks.log2, axis * 4 | 1, pose, logForFace[face] | 1);
                    pinned(Blocks.hay_block, axis * 4, pose, logForFace[face]);
                    pinned(Blocks.quartz_block, axis + 2, pose, quartzForFace[face]);
                }
                pinned(Blocks.log, 15, pose, 15);
                pinned(Blocks.log2, 13, pose, 13);
                pinned(Blocks.quartz_block, 0, pose, up == 1 ? 0 : no);
                pinned(Blocks.quartz_block, 1, pose, vertical ? 1 : no);
                pinned(Blocks.air, 0, pose, 0);
                pinned(Blocks.hay_block, 12, pose, no);
                pinned(Blocks.log2, 2, pose, no);
                pinned(Blocks.piston_head, 7, pose, no);
                pinned(Blocks.stone_button, 0, pose, no);
            }
        SimpleAssert.eq(48, tested.size(), "known vanilla matrix covers all signed axis permutations");
    }

    private static int encoded(int[] table, int face, int start, int end, int absent) {
        for (int i = start; i < end; i++) if (table[i] == face) return i;
        return absent;
    }

    private static void pinned(Block block, int meta, CutTransforms.Transform pose, int expected) {
        String id = Block.blockRegistry.getNameForObject(block);
        SimpleAssert.eq(
            expected,
            HologramCutTransformMetadata.placementMetadata(block, id, meta, pose),
            "native placement encoding " + id + ":" + meta + " " + java.util.Arrays.toString(pose.matrix()));
    }

    private static void strictVanillaLeavesTheLegacyRawPath(NBTTagCompound fixture) {
        NBTTagCompound raw = (NBTTagCompound) fixture.copy();
        NBTTagList rows = raw.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound cell = rows.getCompoundTagAt(i);
            for (String key : new String[] { "x", "y", "z" }) cell.setInteger(key, cell.getInteger(key));
        }
        NBTTagCompound original = (NBTTagCompound) raw.copy();
        CutTransforms.Transform pitch = new CutTransforms.Transform(0, 1, 0, 0);
        NBTTagCompound local = HologramCutTransforms.apply(raw, pitch, 0, 80, 0);
        SimpleAssert.eq(
            2,
            local.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .getInteger("meta"),
            "legacy detached preview can keep an unrepresentable old chest encoding for presentation");
        try {
            HologramCutTransforms.applyForPlacement(raw, pitch, 0, 80, 0);
            throw new AssertionError("quarter pitch cannot authorize a real chest placement");
        } catch (java.io.IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("minecraft:chest"),
                "the refusal truthfully identifies the known unrepresentable block");
        }
        try {
            CutTransforms.Transform yawAndMirror = new CutTransforms.Transform(1, 0, 0, 4);
            NBTTagCompound placed = HologramCutTransforms.applyForPlacement(raw, yawAndMirror, 0, 80, 0);
            NBTTagCompound changed = placed.getTagList("cells", 10)
                .getCompoundTagAt(0);
            SimpleAssert
                .eq(4, changed.getInteger("meta"), "legal yaw plus horizontal mirror keeps exact chest heading");
            SimpleAssert.that(
                original.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .getCompoundTag("tile")
                    .equals(changed.getCompoundTag("tile")),
                "pinned vanilla metadata leaves complete native tile NBT alone");
            CutTransforms.Transform effectiveIdentity = new CutTransforms.Transform(2, 2, 2, 0);
            SimpleAssert.that(
                effectiveIdentity.identity() && HologramCutTransforms.apply(raw, effectiveIdentity, 0, 80, 0)
                    .equals(HologramCutTransforms.applyForPlacement(raw, effectiveIdentity, 0, 80, 0)),
                "effective matrix identity bypasses new qualification and retains the exact legacy raw envelope");
        } catch (java.io.IOException unexpected) {
            throw new AssertionError("known legal vanilla pose rejected", unexpected);
        }
        SimpleAssert
            .that(original.equals(raw), "no qualification, rejection or admitted pose mutates the retained entry");
    }

    private static void sparsePoses(NBTTagCompound full) {
        NBTTagCompound sparse = (NBTTagCompound) full.copy();
        sparse.setInteger("sizeX", 4);
        sparse.setInteger("selectionVersion", 1);
        sparse.setInteger("selectedCells", 2);
        NBTTagList cells = sparse.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            cells.getCompoundTagAt(i)
                .setInteger("x", i == 0 ? 0 : 3);
            cells.getCompoundTagAt(i)
                .setInteger("y", 0);
            cells.getCompoundTagAt(i)
                .setInteger("z", 0);
        }
        NBTTagCompound original = (NBTTagCompound) sparse.copy();
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int mirror = 0; mirror < 8; mirror++) {
                CutTransforms.Transform pose = new CutTransforms.Transform(y, x, z, mirror);
                NBTTagCompound transformed = HologramCutTransforms.apply(sparse, pose, 10, 80, 10);
                try {
                    HologramCutEntry.validate(transformed);
                } catch (java.io.IOException invalid) {
                    throw new AssertionError("every transformed sparse mask remains complete and bounded", invalid);
                }
                NBTTagList changed = transformed.getTagList("cells", 10);
                SimpleAssert.eq(2, changed.tagCount(), "poses never fill unselected gaps with air");
                for (int i = 0; i < changed.tagCount(); i++) {
                    int[] expected = pose.point(i == 0 ? 0 : 3, 0, 0, 4, 1, 1);
                    NBTTagCompound cell = changed.getCompoundTagAt(i);
                    SimpleAssert.that(
                        cell.getInteger("x") == expected[0] && cell.getInteger("y") == expected[1]
                            && cell.getInteger("z") == expected[2],
                        "selected cells and mask share one exact pose");
                }
                SimpleAssert.eq(
                    "minecraft:air",
                    changed.getCompoundTagAt(1)
                        .getString("block"),
                    "selected air survives every rotation and mirror");
                SimpleAssert.that(sparse.equals(original), "pose previews never edit the original sparse clipboard");
            }
    }

    private static void identityPreviewEnvelope(NBTTagCompound entry, NBTTagCompound original) {
        NBTTagCompound expected = (NBTTagCompound) original.copy();
        for (String key : new String[] { "rotation", "turnX", "turnZ", "mirror" }) expected.setInteger(key, 0);
        NBTTagCompound preview = HologramCutTransforms
            .apply(entry, new CutTransforms.Transform(0, 0, 0, 0), 10, 80, 10);
        SimpleAssert.that(
            expected.equals(preview),
            "identity preview retains the entire typed raw entry, plus exactly four explicit pose ints");
        SimpleAssert.that(entry.equals(original), "identity pose metadata is never appended to the durable source");
        SimpleAssert.that(!preview.hasKey("transformUnknown"), "identity preview does not invent transform warnings");
        preview.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("tile")
            .setString("owner", "preview-only");
        SimpleAssert
            .that(entry.equals(original), "identity preview cell and nested native payload remain detached copies");
    }
}
