package com.miaokatze.gtmb.hologram;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.ChunkProviderServer;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real native chunk serialization/readback; stale disk and changed payloads must never authorize a commit. */
public final class HologramCutDiskRegression {

    private HologramCutDiskRegression() {}

    public static void run() {
        try {
            Path directory = Files.createTempDirectory("gtmb-cut-native-disk");
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
            FixtureWorld world = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
            Field provider = World.class.getDeclaredField("provider");
            provider.setAccessible(true);
            provider.set(world, new WorldProviderSurface());
            world.chunks = new HashMap<>();
            world.theChunkProviderServer = (ChunkProviderServer) unsafe.allocateInstance(ChunkProviderServer.class);
            DirectSaveLoader writer = new DirectSaveLoader(directory.toFile());
            world.theChunkProviderServer.currentChunkLoader = writer;
            Chunk chunk = new Chunk(world, -1, 1);
            world.chunks.put(key(-1, 1), chunk);
            ExtendedBlockStorage section = new ExtendedBlockStorage(192, true);
            chunk.getBlockStorageArray()[12] = section;
            section.func_150818_a(14, 8, 3, Blocks.chest);
            section.setExtBlockMetadata(14, 8, 3, 5);
            TileEntityChest chest = new TileEntityChest();
            chest.xCoord = -2;
            chest.yCoord = 200;
            chest.zCoord = 19;
            ItemStack diamonds = new ItemStack(Items.diamond, 7);
            NBTTagCompound nativeItem = new NBTTagCompound();
            nativeItem.setString("nativeOwner", "retained-original");
            nativeItem.setDouble("nativeNaN", Double.NaN);
            diamonds.setTagCompound(nativeItem);
            chest.setInventorySlotContents(4, diamonds);
            chest.setWorldObj(world);
            chunk.chunkTileEntityMap.put(new ChunkPosition(14, 200, 3), chest);
            NBTTagCompound entry = entry();
            writer.saveChunk(world, chunk);
            HologramCutWorld.DiskReader reader = new HologramCutWorld.DiskReader(directory.toFile());
            HologramCutWorld.SavedChunk saved = reader.read(world, -1, 1);
            SimpleAssert.that(saved.blocks != chunk, "disk decoder returns a detached native chunk");
            SimpleAssert.that(saved.blocks.getBlock(14, 200, 3) == Blocks.chest, "native block roundtrip");
            SimpleAssert.eq(5, saved.blocks.getBlockMetadata(14, 200, 3), "native metadata roundtrip");
            SimpleAssert
                .that(saved.blocks.chunkTileEntityMap.isEmpty(), "verification does not instantiate saved tiles");
            SimpleAssert.that(!saved.blocks.isChunkLoaded, "verification does not install a second live chunk");
            SimpleAssert.that(world.getTileEntity(-2, 200, 19) == chest, "live native tile identity is untouched");
            NBTTagCompound tile = new NBTTagCompound();
            chest.writeToNBT(tile);
            NBTTagCompound cell = entry.getTagList("cells", 10)
                .getCompoundTagAt(0);
            saved.verifyCell(-2, 200, 19, cell, false, tile);
            SimpleAssert.that(
                HologramCutWorld.sameNbt(
                    tile,
                    saved.level.getTagList("TileEntities", 10)
                        .getCompoundTagAt(0)),
                "raw native inventory, owner and NaN are read back without loading or filtering");

            // Matching live bytes cannot authorize a commit when the real region still has the old state.
            cell.setInteger("meta", 4);
            section.setExtBlockMetadata(14, 8, 3, 4);
            rejected(() -> saved.verifyCell(-2, 200, 19, cell, false, tile), "stale disk metadata");
            cell.setInteger("meta", 5);
            section.setExtBlockMetadata(14, 8, 3, 5);
            NBTTagCompound changedTile = (NBTTagCompound) tile.copy();
            changedTile.getTagList("Items", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("tag")
                .setString("nativeOwner", "unsaved-changed-owner");
            rejected(() -> saved.verifyCell(-2, 200, 19, cell, false, changedTile), "stale raw native tile");
            rejected(() -> saved.verifyCell(-2, 200, 19, cell, false, null), "unexpected remaining disk tile");
            rejected(() -> saved.verifyCell(-2, 200, 19, cell, true, null), "uncleared source on disk");
            cell.setString("block", "minecraft:stone");
            rejected(() -> saved.verifyCell(-2, 200, 19, cell, false, null), "stale disk block");
            cell.setString("block", "minecraft:chest");
            saved.verifyCell(-2, 200, 19, cell, false, tile);
            rejected(() -> reader.read(world, 0, 1), "missing region chunk");

            NBTTagCompound root;
            try (DataInputStream input = RegionFileCache.getChunkInputStream(directory.toFile(), -1, 1)) {
                root = CompressedStreamTools.read(input);
            }
            root.getCompoundTag("Level")
                .setInteger("xPos", 0);
            try (DataOutputStream output = RegionFileCache.getChunkOutputStream(directory.toFile(), -1, 1)) {
                CompressedStreamTools.write(root, output);
            }
            rejected(() -> reader.read(world, -1, 1), "wrong disk coordinates cannot be silently relocated");
            root.getCompoundTag("Level")
                .setInteger("xPos", -1);
            root.getCompoundTag("Level")
                .setTag("TileEntities", new NBTTagList());
            try (DataOutputStream output = RegionFileCache.getChunkOutputStream(directory.toFile(), -1, 1)) {
                CompressedStreamTools.write(root, output);
            }
            HologramCutWorld.SavedChunk missingTile = reader.read(world, -1, 1);
            rejected(() -> missingTile.verifyCell(-2, 200, 19, cell, false, tile), "missing native inventory on disk");
            // RegionFileCache owns open handles. Do not globally clear other tests' or a running world's cache.
            directory.toFile()
                .deleteOnExit();
        } catch (Exception failure) {
            throw new AssertionError("native cut disk verification", failure);
        }
    }

    private static NBTTagCompound entry() {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setInteger("sizeX", 1);
        entry.setInteger("sizeY", 1);
        entry.setInteger("sizeZ", 1);
        NBTTagCompound cell = new NBTTagCompound();
        cell.setString("block", "minecraft:chest");
        cell.setInteger("meta", 5);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        entry.setTag("cells", cells);
        return entry;
    }

    private static void rejected(Checked action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IOException expected) {}
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private interface Checked {

        void run() throws Exception;
    }

    /** Keep the native writer but replace its async queue with synchronous region I/O in this short-lived harness. */
    private static final class DirectSaveLoader extends AnvilChunkLoader {

        DirectSaveLoader(java.io.File directory) {
            super(directory);
        }

        @Override
        protected void addChunkToPending(ChunkCoordIntPair coordinate, NBTTagCompound root) {
            try (DataOutputStream output = RegionFileCache
                .getChunkOutputStream(chunkSaveLocation, coordinate.chunkXPos, coordinate.chunkZPos)) {
                CompressedStreamTools.write(root, output);
            } catch (IOException failure) {
                throw new IllegalStateException("native cut regression disk write", failure);
            }
        }
    }

    private static final class FixtureWorld extends WorldServer {

        Map<Long, Chunk> chunks;

        private FixtureWorld() {
            super(null, null, "cut disk fixture", 0, null, null);
        }

        @Override
        public void checkSessionLock() {}

        @Override
        public long getTotalWorldTime() {
            return 0;
        }

        @Override
        public List<NextTickListEntry> getPendingBlockUpdates(Chunk chunk, boolean remove) {
            return null;
        }

        @Override
        public Chunk getChunkFromChunkCoords(int x, int z) {
            Chunk chunk = chunks.get(key(x, z));
            if (chunk == null) throw new AssertionError("verification may only use fixture chunks");
            return chunk;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return getChunkFromChunkCoords(x >> 4, z >> 4).chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return getChunkFromChunkCoords(x >> 4, z >> 4).getBlock(x & 15, y, z & 15);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return getChunkFromChunkCoords(x >> 4, z >> 4).getBlockMetadata(x & 15, y, z & 15);
        }
    }
}
