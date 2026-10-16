package com.miaokatze.gtmb.hologram;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.ThreadedFileIOBase;

/** Direct section replacement deliberately bypasses Block.breakBlock, placement hooks, and inventory drops. */
final class HologramCutWorld {

    private HologramCutWorld() {}

    /** FMP registers a loading carrier, so its own factory must build the live composite. No payload is rewritten. */
    static TileEntity createTile(WorldServer world, NBTTagCompound tag) throws IOException {
        if (!"savedMultipart".equals(tag.getString("id"))) return TileEntity.createAndLoadEntity(tag);
        try {
            Object loader = Class.forName("codechicken.multipart.handler.MultipartSaveLoad$")
                .getField("MODULE$")
                .get(null);
            java.lang.reflect.Method getter = loader.getClass()
                .getMethod("loadingWorld");
            java.lang.reflect.Method setter = loader.getClass()
                .getMethod("loadingWorld_$eq", net.minecraft.world.World.class);
            Object previous = getter.invoke(loader);
            try {
                return (TileEntity) Class.forName("codechicken.multipart.MultipartHelper")
                    .getMethod("createTileFromNBT", net.minecraft.world.World.class, NBTTagCompound.class)
                    .invoke(null, world, tag);
            } finally {
                setter.invoke(loader, previous);
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("原生 multipart 工厂不可用，完整剪切记录已保留", failure);
        }
    }

    static void loadMultipart(TileEntity tile) throws ReflectiveOperationException {
        tile.getClass()
            .getMethod("onChunkLoad")
            .invoke(tile);
    }

    static void describeMultipart(WorldServer world, TileEntity tile) throws ReflectiveOperationException {
        Class.forName("codechicken.multipart.MultipartHelper")
            .getMethod("sendDescPacket", net.minecraft.world.World.class, TileEntity.class)
            .invoke(null, world, tile);
    }

    /** Resume ordinary native scheduling/neighbor physics only after the raw transaction is durable. */
    static void notifyCommitted(WorldServer world, NBTTagCompound entry, int ox, int oy, int oz, boolean air) {
        NBTTagList cells = entry.getTagList("cells", 10);
        if (!air) for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            Block block = Block.getBlockFromName(cell.getString("block"));
            if (block == null || block == Blocks.air || world.getBlock(x, y, z) != block) continue;
            try {
                block.onBlockAdded(world, x, y, z);
            } catch (RuntimeException failure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Native post-cut placement callback failed", failure);
            }
        }
        // Chest and similar placement callbacks choose a default orientation. The captured orientation wins.
        if (!air) for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            if (world.getBlock(x, y, z) == Block.getBlockFromName(cell.getString("block"))) {
                Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
                ExtendedBlockStorage section = chunk.getBlockStorageArray()[y >> 4];
                if (section != null) section.setExtBlockMetadata(x & 15, y & 15, z & 15, cell.getInteger("meta"));
                TileEntity tile = world.getTileEntity(x, y, z);
                if (tile != null) tile.updateContainingBlockInfo();
                chunk.setChunkModified();
            }
        }
        Set<Chunk> chunks = new LinkedHashSet<>();
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            try {
                Block block = world.getBlock(x, y, z);
                world.markBlockForUpdate(x, y, z);
                world.notifyBlockChange(x, y, z, block);
                if (block.hasComparatorInputOverride()) world.func_147453_f(x, y, z, block);
                if (!air && "savedMultipart".equals(
                    cell.getCompoundTag("tile")
                        .getString("id"))) {
                    TileEntity tile = world.getTileEntity(x, y, z);
                    if (tile != null) describeMultipart(world, tile);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Native post-cut neighbor callback failed", failure);
            }
            chunks.add(world.getChunkFromChunkCoords(x >> 4, z >> 4));
        }
        // Keep the newly scheduled native block ticks with the ordinary world save, without replaying a committed move.
        for (Chunk chunk : chunks) try {
            world.theChunkProviderServer.currentChunkLoader.saveChunk(world, chunk);
        } catch (Exception failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .warn("Post-cut physics save deferred to ordinary world save", failure);
        }
    }

    static void apply(WorldServer world, NBTTagCompound entry, int ox, int oy, int oz, boolean air) throws IOException {
        HologramCutEntry
            .bounds(ox, oy, oz, entry.getInteger("sizeX"), entry.getInteger("sizeY"), entry.getInteger("sizeZ"));
        if (!(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader))
            throw new IOException("剪切需要可验证存盘的 Anvil 世界");
        NBTTagList cells = entry.getTagList("cells", 10);
        if (cells.tagCount()
            != HologramCutEntry.volume(entry.getInteger("sizeX"), entry.getInteger("sizeY"), entry.getInteger("sizeZ")))
            throw new IOException("剪切记录不完整");
        List<TileEntity> tiles = new ArrayList<>();
        // Read every original NBT before touching the destination. Only root xyz are relocated.
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (Block.getBlockFromName(cell.getString("block")) == null) throw new IOException("原方块模组未加载");
            TileEntity tile = null;
            if (!air && cell.hasKey("tile", 10)) {
                tile = createTile(
                    world,
                    HologramCutEntry.relocatedTile(
                        cell,
                        ox + cell.getInteger("x"),
                        oy + cell.getInteger("y"),
                        oz + cell.getInteger("z")));
                if (tile == null) throw new IOException("原方块实体无法读取，记录已保留");
            }
            tiles.add(tile);
        }
        Set<Chunk> chunks = new LinkedHashSet<>();
        // Detach all inventories before changing any block, including replaced destination inventories.
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
            TileEntity previous = world.getTileEntity(x, y, z);
            chunk.removeTileEntity(x & 15, y, z & 15);
            if (previous != null) world.loadedTileEntityList.remove(previous);
            chunks.add(chunk);
        }
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
            ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
            if (sections[y >> 4] == null)
                sections[y >> 4] = new ExtendedBlockStorage((y >> 4) << 4, !world.provider.hasNoSky);
            sections[y >> 4].func_150818_a(
                x & 15,
                y & 15,
                z & 15,
                air ? Blocks.air : Block.getBlockFromName(cell.getString("block")));
            sections[y >> 4].setExtBlockMetadata(x & 15, y & 15, z & 15, air ? 0 : cell.getInteger("meta"));
        }
        for (TileEntity tile : tiles) if (tile != null) {
            tile.setWorldObj(world);
            world.getChunkFromChunkCoords(tile.xCoord >> 4, tile.zCoord >> 4)
                .addTileEntity(tile);
        }
        // Native loading must finish before serialization: AE defers its saved node NBT until onReady.
        // All blocks/tiles are attached first, so each part sees the complete restored structure.
        for (int i = 0; i < tiles.size(); i++) {
            TileEntity tile = tiles.get(i);
            if (tile == null) continue;
            try {
                if ("savedMultipart".equals(
                    cells.getCompoundTagAt(i)
                        .getCompoundTag("tile")
                        .getString("id")))
                    loadMultipart(tile);
                else if (tile instanceof appeng.tile.AEBaseTile) ((appeng.tile.AEBaseTile) tile).onReady();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                throw new IOException("原生方块实体加载失败，完整剪切记录已保留", failure);
            }
        }
        for (Chunk chunk : chunks) {
            chunk.generateSkylightMap();
            chunk.setChunkModified();
        }
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            world.func_147451_t(x, y, z);
            world.markBlockForUpdate(x, y, z);
        }
    }

    /**
     * I/O verification only: native initialization may legitimately change runtime identities such as AE grid IDs.
     * Verify disk against a detached snapshot of the initialized live tile, never approve/filter the source payload.
     */
    static void saveAndVerify(WorldServer world, NBTTagCompound entry, int ox, int oy, int oz, boolean air)
        throws Exception {
        AnvilChunkLoader loader = (AnvilChunkLoader) world.theChunkProviderServer.currentChunkLoader;
        Set<Chunk> chunks = new LinkedHashSet<>();
        NBTTagList cells = entry.getTagList("cells", 10);
        Map<Long, NBTTagCompound> expectedTiles = new LinkedHashMap<>();
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            TileEntity live = world.getTileEntity(x, y, z);
            if (live != null) {
                NBTTagCompound saved = new NBTTagCompound();
                live.writeToNBT(saved);
                expectedTiles.put(tileKey(x, y, z), (NBTTagCompound) saved.copy());
            }
            chunks
                .add(world.getChunkFromChunkCoords((ox + cell.getInteger("x")) >> 4, (oz + cell.getInteger("z")) >> 4));
        }
        for (Chunk chunk : chunks) loader.saveChunk(world, chunk);
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        Map<Long, SavedChunk> disk = new LinkedHashMap<>();
        Set<String> regions = new LinkedHashSet<>();
        DiskReader reader = new DiskReader(loader.chunkSaveLocation);
        for (Chunk chunk : chunks) {
            SavedChunk saved = reader.read(world, chunk.xPosition, chunk.zPosition);
            disk.put(key(chunk.xPosition, chunk.zPosition), saved);
            regions.add("r." + (chunk.xPosition >> 5) + "." + (chunk.zPosition >> 5) + ".mca");
        }
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            disk.get(key(x >> 4, z >> 4))
                .verifyCell(x, y, z, cell, air, expectedTiles.get(tileKey(x, y, z)));
        }
        for (String name : regions) try (RandomAccessFile region = new RandomAccessFile(
            new java.io.File(new java.io.File(loader.chunkSaveLocation, "region"), name),
            "rw")) {
                region.getFD()
                    .sync();
            }
    }

    /** Native floating-point writers canonicalize NaN; 1.7 tag.equals rejects its own valid roundtrip. */
    static boolean sameNbt(net.minecraft.nbt.NBTBase first, net.minecraft.nbt.NBTBase second) {
        if (first == second) return true;
        if (first == null || second == null || first.getId() != second.getId()) return false;
        if (first instanceof net.minecraft.nbt.NBTTagFloat)
            return Float.floatToIntBits(((net.minecraft.nbt.NBTTagFloat) first).func_150288_h())
                == Float.floatToIntBits(((net.minecraft.nbt.NBTTagFloat) second).func_150288_h());
        if (first instanceof net.minecraft.nbt.NBTTagDouble)
            return Double.doubleToLongBits(((net.minecraft.nbt.NBTTagDouble) first).func_150286_g())
                == Double.doubleToLongBits(((net.minecraft.nbt.NBTTagDouble) second).func_150286_g());
        if (first instanceof NBTTagCompound) {
            NBTTagCompound a = (NBTTagCompound) first, b = (NBTTagCompound) second;
            if (!a.func_150296_c()
                .equals(b.func_150296_c())) return false;
            for (Object key : a.func_150296_c())
                if (!sameNbt(a.getTag((String) key), b.getTag((String) key))) return false;
            return true;
        }
        if (first instanceof NBTTagList) {
            NBTTagList a = (NBTTagList) first.copy(), b = (NBTTagList) second.copy();
            if (a.tagCount() != b.tagCount()) return false;
            for (int index = a.tagCount() - 1; index >= 0; index--)
                if (!sameNbt(a.removeTag(index), b.removeTag(index))) return false;
            return true;
        }
        return first.equals(second);
    }

    /** Raw tile bytes and native block decoding must describe the same on-disk chunk. */
    static final class SavedChunk {

        final Chunk blocks;
        final NBTTagCompound level;
        private final Map<Long, NBTTagCompound> tiles = new LinkedHashMap<>();

        SavedChunk(Chunk blocks, NBTTagCompound level) {
            this.blocks = blocks;
            this.level = level;
            NBTTagList savedTiles = level.getTagList("TileEntities", 10);
            for (int t = 0; t < savedTiles.tagCount(); t++) {
                NBTTagCompound tile = savedTiles.getCompoundTagAt(t);
                tiles.put(tileKey(tile.getInteger("x"), tile.getInteger("y"), tile.getInteger("z")), tile);
            }
        }

        void verifyCell(int x, int y, int z, NBTTagCompound cell, boolean air, NBTTagCompound expectedTile)
            throws IOException {
            Block expectedBlock = air ? Blocks.air : Block.getBlockFromName(cell.getString("block"));
            if (blocks.getBlock(x & 15, y, z & 15) != expectedBlock
                || blocks.getBlockMetadata(x & 15, y, z & 15) != (air ? 0 : cell.getInteger("meta")))
                throw new IOException("剪切世界方块未完整存盘");
            NBTTagCompound tile = tiles.get(tileKey(x, y, z));
            if (expectedTile == null) {
                if (tile != null) throw new IOException("剪切世界实体未完整存盘");
            } else if (!sameNbt(expectedTile, tile)) throw new IOException("方块实体存盘读回与本次世界快照不一致，记录已保留");
        }
    }

    /**
     * Read real region bytes, then use Forge's block-only native decoder. A fresh loader has no queued save NBT to
     * mistake for disk. The inherited decoder includes installed storage codecs (for example extended IDs/metadata),
     * but does not call loadEntities, fire ChunkDataEvent.Load, or attach this detached chunk to the world.
     */
    static final class DiskReader extends AnvilChunkLoader {

        DiskReader(java.io.File directory) {
            super(directory);
        }

        SavedChunk read(WorldServer world, int x, int z) throws IOException {
            NBTTagCompound root;
            try (DataInputStream input = RegionFileCache.getChunkInputStream(chunkSaveLocation, x, z)) {
                if (input == null) throw new IOException("剪切世界存盘失败");
                root = CompressedStreamTools.read(input);
            }
            NBTTagCompound level = root.getCompoundTag("Level");
            // Reject relocation before the native loader can repair a mismatched chunk in memory.
            if (!root.hasKey("Level", 10) || !level.hasKey("Sections", 9)
                || !level.hasKey("xPos", 3)
                || !level.hasKey("zPos", 3)
                || level.getInteger("xPos") != x
                || level.getInteger("zPos") != z) throw new IOException("剪切世界存盘区块坐标或数据无效，记录已保留");
            // Native data-fixers may edit their input. Preserve the exact raw disk tile NBT for comparison.
            Chunk blocks = super.checkedReadChunkFromNBT(world, x, z, (NBTTagCompound) root.copy());
            if (blocks == null || !blocks.isAtLocation(x, z)) throw new IOException("剪切世界存盘区块无法原生读回，记录已保留");
            return new SavedChunk(blocks, level);
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static long tileKey(int x, int y, int z) {
        return ((x & 0x3ffffffL) << 34) | ((z & 0x3ffffffL) << 8) | (y & 255L);
    }
}
