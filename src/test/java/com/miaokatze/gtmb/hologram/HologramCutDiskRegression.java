package com.miaokatze.gtmb.hologram;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

import cpw.mods.fml.relauncher.ReflectionHelper;

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
            world.provider.hasNoSky = true;
            world.chunks = new HashMap<>();
            world.loadedTileEntityList = new ArrayList<>();
            DirectSaveLoader writer = new DirectSaveLoader(directory.toFile());
            world.theChunkProviderServer = new FixtureProvider(world, writer);
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
            IOException mismatch = rejected(
                () -> saved.verifyCell(-2, 200, 19, cell, false, tile),
                "stale disk metadata");
            SimpleAssert.that(
                mismatch.getMessage()
                    .contains("[-2,200,19]")
                    && mismatch.getMessage()
                        .contains("expected=minecraft:chest:4")
                    && mismatch.getMessage()
                        .contains("actual=minecraft:chest:5"),
                "failed disk verification identifies the coordinate and exact expected/actual block metadata");
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
            initializedMetadata(world, reader);
            // RegionFileCache owns open handles. Do not globally clear other tests' or a running world's cache.
            directory.toFile()
                .deleteOnExit();
        } catch (Exception failure) {
            throw new AssertionError("native cut disk verification", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void initializedMetadata(FixtureWorld world, HologramCutWorld.DiskReader reader) throws Exception {
        String id = "gtmb_test_cut_metadata_on_ready";
        Map<String, Class<?>> types = (Map<String, Class<?>>) ReflectionHelper
            .findField(TileEntity.class, "nameToClassMap", "field_145855_i")
            .get(null);
        Map<Class<?>, String> names = (Map<Class<?>, String>) ReflectionHelper
            .findField(TileEntity.class, "classToNameMap", "field_145853_j")
            .get(null);
        Class<?> previousType = types.put(id, MetadataReadyTile.class);
        String previousName = names.put(MetadataReadyTile.class, id);
        try {
            NBTTagCompound entry = entry();
            entry.setInteger("sizeX", 2);
            NBTTagList cells = entry.getTagList("cells", 10);
            cells.appendTag(
                cells.getCompoundTagAt(0)
                    .copy());
            for (int index = 0; index < 2; index++) {
                NBTTagCompound cell = cells.getCompoundTagAt(index);
                cell.setInteger("x", index);
                cell.setInteger("y", 0);
                cell.setInteger("z", 0);
                cell.setInteger("meta", 12 + index);
                NBTTagCompound payload = new NBTTagCompound();
                payload.setString("id", id);
                payload.setString("customName", "original metadata fixture " + index);
                cell.setTag("tile", payload);
            }
            NBTTagCompound original = (NBTTagCompound) entry.copy();
            MetadataReadyTile.readyCalls = 0;
            HologramCutWorld.apply(world, entry, -2, 200, 19, false);
            SimpleAssert.eq(2, MetadataReadyTile.readyCalls, "both native readiness callbacks actually ran");
            for (int index = 0; index < 2; index++) {
                SimpleAssert.eq(
                    12 + index,
                    world.getBlockMetadata(-2 + index, 200, 19),
                    "raw metadata wins after all native readiness callbacks");
                SimpleAssert.eq(
                    12 + index,
                    world.getTileEntity(-2 + index, 200, 19)
                        .getBlockMetadata(),
                    "tile metadata cache reflects restored raw state");
            }
            SimpleAssert.that(original.equals(entry), "native readiness does not rewrite the durable raw input");
            // Keep this short-lived harness free of vanilla's permanent non-daemon file I/O thread.
            world.theChunkProviderServer.currentChunkLoader.saveChunk(world, world.getChunkFromChunkCoords(-1, 1));
            HologramCutWorld.SavedChunk saved = reader.read(world, -1, 1);
            for (int index = 0; index < 2; index++) {
                NBTTagCompound expected = new NBTTagCompound();
                world.getTileEntity(-2 + index, 200, 19)
                    .writeToNBT(expected);
                saved.verifyCell(-2 + index, 200, 19, cells.getCompoundTagAt(index), false, expected);
            }
        } finally {
            if (previousType == null) types.remove(id);
            else types.put(id, previousType);
            if (previousName == null) names.remove(MetadataReadyTile.class);
            else names.put(MetadataReadyTile.class, previousName);
        }
    }

    /** Native readiness may update both this host and an already initialized neighbor. */
    public static final class MetadataReadyTile extends appeng.tile.AEBaseTile {

        static int readyCalls;

        @Override
        public void onReady() {
            SimpleAssert.that(
                getWorldObj().getTileEntity(-2, 200, 19) instanceof MetadataReadyTile
                    && getWorldObj().getTileEntity(-1, 200, 19) instanceof MetadataReadyTile,
                "the complete raw structure is attached before native readiness");
            Chunk chunk = getWorldObj().getChunkFromChunkCoords(-1, 1);
            ExtendedBlockStorage section = chunk.getBlockStorageArray()[12];
            section.setExtBlockMetadata(xCoord & 15, 8, 3, 8 + (xCoord + 2));
            updateContainingBlockInfo();
            getBlockMetadata(); // Cache the native state so final restoration must also invalidate this cache.
            section.setExtBlockMetadata(14, 8, 3, 8);
            readyCalls++;
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

    private static IOException rejected(Checked action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IOException expected) {
            return expected;
        }
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

        @Override
        public boolean func_147451_t(int x, int y, int z) {
            return false;
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}
    }

    private static final class FixtureProvider extends ChunkProviderServer {

        private final FixtureWorld fixture;

        FixtureProvider(FixtureWorld world, DirectSaveLoader writer) {
            super(world, writer, null);
            fixture = world;
        }

        @Override
        public Chunk loadChunk(int x, int z) {
            return fixture.chunks.computeIfAbsent(key(x, z), ignored -> new Chunk(fixture, x, z));
        }
    }
}
