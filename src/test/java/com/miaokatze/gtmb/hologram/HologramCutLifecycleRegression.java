package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityComparator;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Native callbacks in a bounded world stub; this does not establish a ticked redstone circuit or crash recovery. */
public final class HologramCutLifecycleRegression {

    private HologramCutLifecycleRegression() {}

    public static void main(String[] args) throws Exception {
        if (MinecraftTestBootstrap.enter(HologramCutLifecycleRegression.class, args)) run();
    }

    public static void run() {
        try {
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
            CallbackWorld world = (CallbackWorld) unsafe.allocateInstance(CallbackWorld.class);
            world.blocks = new HashMap<>();
            world.metadata = new HashMap<>();
            world.notifications = new ArrayList<>();
            comparator(world);
            nativeHalfRemoval(world);
            admission(world);
        } catch (Exception failure) {
            throw new AssertionError("cut lifecycle and native pair admission", failure);
        }
    }

    private static void comparator(CallbackWorld world) {
        for (Block block : new Block[] { Blocks.unpowered_comparator, Blocks.powered_comparator }) {
            for (int direction = 0; direction < 4; direction++) {
                world.clear();
                world.put(0, 64, 0, block, direction | 4);
                TileEntityComparator original = new TileEntityComparator();
                original.setOutputSignal(11);
                world.tile = original;
                block.onBlockAdded(world, 0, 64, 0);
                SimpleAssert.that(world.tile != original, "native comparator added callback replaces a restored tile");
                SimpleAssert.eq(
                    0,
                    ((TileEntityComparator) world.tile).getOutputSignal(),
                    "native replacement resets output cache");
                List<String> expected = new ArrayList<>(world.notifications);
                world.notifications.clear();
                world.tile = original;
                world.tileSets = 0;
                HologramCutLifecycle.added(world, block, 0, 64, 0, original);
                SimpleAssert.that(world.tile == original, "restoration retains the attached native comparator tile");
                SimpleAssert.eq(11, original.getOutputSignal(), "restoration does not reset comparator output cache");
                SimpleAssert.eq(0, world.tileSets, "restored comparator does not invoke placement tile factory");
                SimpleAssert.that(
                    expected.equals(world.notifications),
                    "restored comparator preserves exact native diode output notifications");
                SimpleAssert.eq(
                    2,
                    world.notifications.size(),
                    "native diode emits direct and surrounding output notifications");
            }
        }
        world.clear();
        world.put(0, 64, 0, Blocks.unpowered_comparator, 0);
        HologramCutLifecycle.added(world, Blocks.unpowered_comparator, 0, 64, 0, null);
        SimpleAssert.that(
            world.tile instanceof TileEntityComparator,
            "a comparator without a restored tile keeps native initialization");
        SimpleAssert.eq(1, world.tileSets, "ordinary native placement factory still runs");
    }

    private static void nativeHalfRemoval(CallbackWorld world) {
        // Invoke actual native neighbor callbacks after the selected other half has been removed.
        for (Block block : new Block[] { Blocks.wooden_door, Blocks.iron_door, Blocks.double_plant }) {
            world.clear();
            world.put(0, 65, 0, block, 8);
            block.onNeighborBlockChange(world, 0, 65, 0, block);
            SimpleAssert.that(
                world.getBlock(0, 65, 0) == Blocks.air,
                "native upper half removes itself without selected lower partner");
            SimpleAssert.eq(1, world.blockWrites, "native callback can write the unselected companion cell");
        }
        for (int direction = 0; direction < 4; direction++) {
            world.clear();
            world.put(0, 64, 0, Blocks.bed, direction | 8);
            Blocks.bed.onNeighborBlockChange(world, 0, 64, 0, Blocks.bed);
            SimpleAssert
                .that(world.getBlock(0, 64, 0) == Blocks.air, "native bed head removes itself without selected foot");
        }
    }

    private static void admission(CallbackWorld world) throws Exception {
        for (String block : new String[] { "minecraft:wooden_door", "minecraft:iron_door", "minecraft:double_plant" }) {
            NBTTagCompound lower = cell(0, 0, 0, block, 0), upper = cell(0, 1, 0, block, 8);
            HologramCutPairs.validateEntry(entry(lower, upper));
            HologramCutPairs.validatePose(entry(lower, upper), new CutTransforms.Transform(1, 0, 0, 1));
            rejected(
                () -> HologramCutPairs.validatePose(entry(lower, upper), new CutTransforms.Transform(0, 2, 0, 0)),
                "pair inversion is refused before raw best-effort transforms");
            rejected(
                () -> HologramCutPairs.validateEntry(entry(lower)),
                "selected lower half without upper is refused");
            rejected(
                () -> HologramCutPairs.validateEntry(entry(upper)),
                "selected upper half without lower is refused");
            rejected(
                () -> HologramCutPairs.validateEntry(entry(lower, cell(0, 1, 0, "minecraft:air", 0))),
                "selected air cannot stand in for the real companion");
            rejected(
                () -> HologramCutPairs.validateEntry(entry(lower, cell(0, 2, 0, block, 8))),
                "a selection mask gap does not grant access to the missing companion");
            rejected(
                () -> HologramCutPairs.validateEntry(entry(lower, cell(0, 1, 0, block, 0))),
                "two lower halves are not a complete native pair");
            world.clear();
            Block nativeBlock = Block.getBlockFromName(block);
            world.put(0, 64, 0, nativeBlock, 0);
            world.put(0, 65, 0, nativeBlock, 8);
            NBTTagCompound air = cell(0, 0, 0, "minecraft:air", 0);
            rejected(
                () -> HologramCutPairs.validateDestination(world, entry(air), 0, 64, 0),
                "selected air cannot overwrite half a destination native vertical pair");
            HologramCutPairs.validateDestination(world, entry(air, cell(0, 1, 0, "minecraft:air", 0)), 0, 64, 0);
            SimpleAssert.eq(0, world.blockWrites, "pair admission performs no world writes");
        }
        int[][] offsets = { { 0, 1 }, { -1, 0 }, { 0, -1 }, { 1, 0 } };
        for (int direction = 0; direction < 4; direction++) {
            int dx = offsets[direction][0], dz = offsets[direction][1];
            NBTTagCompound foot = cell(1, 0, 1, "minecraft:bed", direction);
            NBTTagCompound head = cell(1 + dx, 0, 1 + dz, "minecraft:bed", direction | 8);
            HologramCutPairs.validateEntry(entry(foot, head));
            HologramCutPairs.validatePose(entry(foot, head), new CutTransforms.Transform(1, 0, 0, 0));
            rejected(
                () -> HologramCutPairs.validatePose(entry(foot, head), new CutTransforms.Transform(0, 1, 0, 0)),
                "a sideways bed is refused before raw best-effort transforms");
            rejected(
                () -> HologramCutPairs.validateEntry(entry(foot)),
                "bed foot requires its native facing companion");
            rejected(
                () -> HologramCutPairs.validateEntry(entry(head)),
                "bed head requires its native facing companion");
            NBTTagCompound wrongFacing = (NBTTagCompound) head.copy();
            wrongFacing.setInteger("meta", ((direction + 1) & 3) | 8);
            rejected(
                () -> HologramCutPairs.validateEntry(entry(foot, wrongFacing)),
                "bed halves must agree on their native direction");
            world.clear();
            world.put(1, 64, 1, Blocks.bed, direction);
            world.put(1 + dx, 64, 1 + dz, Blocks.bed, direction | 8);
            NBTTagCompound air = cell(1, 0, 1, "minecraft:air", 0);
            rejected(
                () -> HologramCutPairs.validateDestination(world, entry(air), 0, 64, 0),
                "destination bed foot cannot leave a head outside the mask");
            HologramCutPairs
                .validateDestination(world, entry(air, cell(1 + dx, 0, 1 + dz, "minecraft:air", 0)), 0, 64, 0);
            SimpleAssert.eq(0, world.blockWrites, "destination bed admission performs no world writes");
        }
        HologramCutPairs.validateEntry(entry(cell(0, 0, 0, "minecraft:chest", 2)));
        NBTTagCompound air = cell(0, 0, 0, "minecraft:air", 0);
        world.clear();
        world.put(0, 64, 0, Blocks.chest, 2);
        world.put(1, 64, 0, Blocks.chest, 2);
        HologramCutPairs.validateDestination(world, entry(air), 0, 64, 0);
    }

    private static NBTTagCompound cell(int x, int y, int z, String block, int metadata) {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        cell.setInteger("y", y);
        cell.setInteger("z", z);
        cell.setString("block", block);
        cell.setInteger("meta", metadata);
        return cell;
    }

    private static NBTTagCompound entry(NBTTagCompound... selected) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setInteger("sizeX", 3);
        entry.setInteger("sizeY", 3);
        entry.setInteger("sizeZ", 3);
        entry.setInteger("selectionVersion", 1);
        entry.setInteger("selectedCells", selected.length);
        NBTTagList cells = new NBTTagList();
        for (NBTTagCompound cell : selected) cells.appendTag(cell);
        entry.setTag("cells", cells);
        return entry;
    }

    private static void rejected(Checked action, String label) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(label);
    }

    private interface Checked {

        void run() throws Exception;
    }

    private static final class CallbackWorld extends World {

        Map<String, Block> blocks;
        Map<String, Integer> metadata;
        List<String> notifications;
        TileEntity tile;
        int tileSets, blockWrites;

        private CallbackWorld() {
            super(null, "cut lifecycle stub", (net.minecraft.world.WorldSettings) null, null, null);
        }

        void clear() {
            blocks.clear();
            metadata.clear();
            notifications.clear();
            tile = null;
            tileSets = 0;
            blockWrites = 0;
        }

        void put(int x, int y, int z, Block block, int meta) {
            blocks.put(key(x, y, z), block);
            metadata.put(key(x, y, z), meta);
        }

        private String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            return null;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), Blocks.air);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return metadata.getOrDefault(key(x, y, z), 0);
        }

        @Override
        public boolean setBlockToAir(int x, int y, int z) {
            return setBlock(x, y, z, Blocks.air, 0, 3);
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            put(x, y, z, block, meta);
            blockWrites++;
            return true;
        }

        @Override
        public void notifyBlockOfNeighborChange(int x, int y, int z, Block block) {
            notifications.add("one:" + key(x, y, z));
        }

        @Override
        public void notifyBlocksOfNeighborChange(int x, int y, int z, Block block, int side) {
            notifications.add("around:" + key(x, y, z) + ":" + side);
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity value) {
            tile = value;
            tileSets++;
        }
    }
}
