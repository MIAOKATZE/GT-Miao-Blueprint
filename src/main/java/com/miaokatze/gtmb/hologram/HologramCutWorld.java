package com.miaokatze.gtmb.hologram;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkDataEvent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Direct section replacement deliberately bypasses Block.breakBlock, placement hooks, and inventory drops. */
final class HologramCutWorld {

    private HologramCutWorld() {}

    /** FMP registers a loading carrier, so its own factory must build the live composite. No payload is rewritten. */
    static TileEntity createTile(WorldServer world, NBTTagCompound tag) throws IOException {
        if (!"savedMultipart".equals(tag.getString("id"))) return HologramCutTiles.create(
            world,
            world.getBlock(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z")),
            world.getBlockMetadata(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z")),
            tag);
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
                HologramCutLifecycle.added(world, block, x, y, z, attachedTile(world, x, y, z));
            } catch (RuntimeException | LinkageError failure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Native post-cut placement callback failed", failure);
            }
        }
        // Chest and similar placement callbacks choose a default orientation. The captured orientation wins.
        if (!air) for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            if (world.getBlock(x, y, z) == Block.getBlockFromName(cell.getString("block"))) {
                restoreMetadata(world, x, y, z, cell.getInteger("meta"));
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
                    TileEntity tile = attachedTile(world, x, y, z);
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
        HologramCutEntry.validate(entry);
        HologramCutEntry
            .bounds(ox, oy, oz, entry.getInteger("sizeX"), entry.getInteger("sizeY"), entry.getInteger("sizeZ"));
        if (!(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader))
            throw new IOException("剪切需要可验证存盘的 Anvil 世界");
        NBTTagList cells = entry.getTagList("cells", 10);
        HologramCutChunks.loadWithLightNeighbors(world, ox, oz, entry.getInteger("sizeX"), entry.getInteger("sizeZ"));
        List<TileEntity> tiles = new ArrayList<>();
        // Read every original NBT before touching the destination. Only root xyz are relocated.
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (Block.getBlockFromName(cell.getString("block")) == null) throw new IOException("原方块模组未加载");
            TileEntity tile = null;
            if (!air && cell.hasKey("tile", 10)) {
                NBTTagCompound payload = HologramCutEntry.relocatedTile(
                    cell,
                    ox + cell.getInteger("x"),
                    oy + cell.getInteger("y"),
                    oz + cell.getInteger("z"));
                tile = "savedMultipart".equals(payload.getString("id")) ? createTile(world, payload)
                    : HologramCutTiles.create(
                        world,
                        Block.getBlockFromName(cell.getString("block")),
                        cell.getInteger("meta"),
                        payload);
                if (tile == null) throw new IOException("原方块实体无法读取，记录已保留");
            }
            tiles.add(tile);
        }
        HologramCutLighting lighting = new HologramCutLighting();
        Set<TileEntity> previousTiles = Collections.newSetFromMap(new IdentityHashMap<>());
        // Remove every old host from lookup before any native teardown can re-enter a neighboring lookup.
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
            lighting.capture(chunk);
            TileEntity previous = chunk.chunkTileEntityMap.remove(new ChunkPosition(x & 15, y, z & 15));
            if (previous != null) previousTiles.add(previous);
        }
        // Vanilla replaces the block before teardown. Keep the whole selection air while old networks disconnect:
        // a reentrant native getter must not create an empty host from either an old or a new destination block.
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
            ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
            if (sections[y >> 4] == null) continue;
            sections[y >> 4].func_150818_a(x & 15, y & 15, z & 15, Blocks.air);
            sections[y >> 4].setExtBlockMetadata(x & 15, y & 15, z & 15, 0);
        }
        IOException teardownFailure = null;
        try {
            for (TileEntity previous : previousTiles) {
                try {
                    previous.invalidate();
                } catch (RuntimeException | LinkageError failure) {
                    if (teardownFailure == null) teardownFailure = new IOException("原生方块实体拆卸失败，完整剪切记录已保留", failure);
                    else if (teardownFailure.getSuppressed().length < 8) teardownFailure.addSuppressed(failure);
                }
            }
        } finally {
            if (!previousTiles.isEmpty()) world.loadedTileEntityList.removeIf(previousTiles::contains);
        }
        if (teardownFailure != null) throw teardownFailure;
        // Old callbacks have finished before any replacement tile is attached; teardown cannot remove a new host.
        if (!air) for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
            ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
            Block replacement = Block.getBlockFromName(cell.getString("block"));
            if (sections[y >> 4] == null && replacement != Blocks.air) {
                sections[y >> 4] = new ExtendedBlockStorage((y >> 4) << 4, !world.provider.hasNoSky);
                lighting.sectionCreated(chunk, y >> 4);
            }
            if (sections[y >> 4] != null) {
                sections[y >> 4].func_150818_a(x & 15, y & 15, z & 15, replacement);
                sections[y >> 4].setExtBlockMetadata(x & 15, y & 15, z & 15, cell.getInteger("meta"));
            }
            TileEntity tile = tiles.get(i);
            if (tile != null) {
                tile.setWorldObj(world);
                chunk.addTileEntity(tile);
            }
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
                else HologramCutTiles.ready(tile);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                throw new IOException("原生方块实体加载失败，完整剪切记录已保留", failure);
            }
        }
        // Native initialization can rewrite metadata (for example an AE host's transient powered bit).
        // Commit the captured raw state first; ordinary native updates resume after the durable commit.
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            restoreMetadata(
                world,
                ox + cell.getInteger("x"),
                oy + cell.getInteger("y"),
                oz + cell.getInteger("z"),
                air ? 0 : cell.getInteger("meta"));
        }
        lighting.finish(world);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            world.func_147451_t(x, y, z);
            world.markBlockForUpdate(x, y, z);
        }
        lighting.complete();
    }

    private static void restoreMetadata(WorldServer world, int x, int y, int z, int metadata) {
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        ExtendedBlockStorage section = chunk.getBlockStorageArray()[y >> 4];
        if (section != null) section.setExtBlockMetadata(x & 15, y & 15, z & 15, metadata);
        TileEntity tile = attachedTile(world, x, y, z);
        if (tile != null) tile.updateContainingBlockInfo();
        chunk.setChunkModified();
    }

    /**
     * I/O verification only: serialize live tiles once through the installed native chunk writer. Its final payload
     * becomes a detached snapshot, checked against real region bytes after the native I/O queue drains.
     */
    static void saveAndVerify(WorldServer world, NBTTagCompound entry, int ox, int oy, int oz, boolean air)
        throws Exception {
        HologramCutChunks.load(world, ox, oz, entry.getInteger("sizeX"), entry.getInteger("sizeZ"));
        AnvilChunkLoader loader = (AnvilChunkLoader) world.theChunkProviderServer.currentChunkLoader;
        Set<Chunk> chunks = new LinkedHashSet<>();
        NBTTagList cells = entry.getTagList("cells", 10);
        Map<Long, TileEntity> liveTiles = new LinkedHashMap<>();
        boolean diagnostics = Boolean.getBoolean("gtmb.cutDiskDiagnostics");
        Map<Long, NBTTagCompound> before = diagnostics ? new LinkedHashMap<>() : null;
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int x = ox + cell.getInteger("x"), y = oy + cell.getInteger("y"), z = oz + cell.getInteger("z");
            TileEntity live = selectedTile(world, x, y, z, cell, air);
            if (live != null) {
                liveTiles.put(tileKey(x, y, z), live);
                if (diagnostics) {
                    NBTTagCompound saved = new NBTTagCompound();
                    live.writeToNBT(saved);
                    before.put(tileKey(x, y, z), (NBTTagCompound) saved.copy());
                }
            }
            chunks
                .add(world.getChunkFromChunkCoords((ox + cell.getInteger("x")) >> 4, (oz + cell.getInteger("z")) >> 4));
        }
        NativeSaveSnapshot snapshot = new NativeSaveSnapshot(world);
        MinecraftForge.EVENT_BUS.register(snapshot);
        try {
            for (Chunk chunk : chunks) snapshot.save(loader, chunk, liveTiles);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(snapshot);
        }
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
            long position = tileKey(x, y, z);
            TileEntity live = selectedTile(world, x, y, z, cell, air);
            if (live != liveTiles.get(position))
                throw new IOException("剪切方块实体在存盘期间被替换 at [" + x + "," + y + "," + z + "]");
            snapshot.verifyPresence(x, y, z, live);
            SavedChunk saved = disk.get(key(x >> 4, z >> 4));
            try {
                saved.verifyCell(x, y, z, cell, air, snapshot.tile(x, y, z));
            } catch (IOException failure) {
                if (!diagnostics) throw failure;
                throw new IOException(
                    failure.getMessage() + " pre/native="
                        + firstNbtDifference(before.get(position), snapshot.tile(x, y, z))
                        + " pre/disk="
                        + firstNbtDifference(before.get(position), saved.tiles.get(position)),
                    failure);
            }
        }
        for (String name : regions) try (RandomAccessFile region = new RandomAccessFile(
            new java.io.File(new java.io.File(loader.chunkSaveLocation, "region"), name),
            "rw")) {
                region.getFD()
                    .sync();
            }
    }

    static TileEntity selectedTile(WorldServer world, int x, int y, int z, NBTTagCompound cell, boolean air)
        throws IOException {
        // The native getter may create an empty default tile. Verification must observe the attached original.
        TileEntity live = attachedTile(world, x, y, z);
        boolean required = !air && cell.hasKey("tile", 10);
        if (required != (live != null)) throw new IOException(
            "剪切方块实体覆盖不完整 at [" + x
                + ","
                + y
                + ","
                + z
                + "] expectedPresent="
                + required
                + " actualPresent="
                + (live != null));
        if (live != null && (live.getWorldObj() != world || live.xCoord != x
            || live.yCoord != y
            || live.zCoord != z
            || live.isInvalid())) throw new IOException("剪切方块实体世界或坐标无效 at [" + x + "," + y + "," + z + "]");
        return live;
    }

    private static TileEntity attachedTile(WorldServer world, int x, int y, int z) {
        return world.getChunkFromChunkCoords(x >> 4, z >> 4).chunkTileEntityMap
            .get(new ChunkPosition(x & 15, y, z & 15));
    }

    /** Capture this exact native save; missing events or selected live tiles never authorize consumption. */
    public static final class NativeSaveSnapshot {

        private final WorldServer world;
        private final Thread thread = Thread.currentThread();
        private final Map<Long, NBTTagCompound> tiles = new LinkedHashMap<>();
        private final Set<Long> nativePositions = new LinkedHashSet<>();
        private Chunk activeChunk;
        private NBTTagCompound root;

        NativeSaveSnapshot(WorldServer world) {
            this.world = world;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onSave(ChunkDataEvent.Save event) {
            if (Thread.currentThread() == thread && event.world == world && event.getChunk() == activeChunk)
                root = event.getData();
        }

        void save(AnvilChunkLoader loader, Chunk chunk, Map<Long, TileEntity> expected) throws Exception {
            activeChunk = chunk;
            root = null;
            try {
                loader.saveChunk(world, chunk);
                if (root == null) throw new IOException(
                    "剪切原生存盘事件未捕获，记录已保留 at chunk [" + chunk.xPosition + "," + chunk.zPosition + "]");
                // Keep the event's reference until saveChunk returns: later handlers may edit its final payload.
                NBTTagCompound level = root.getCompoundTag("Level");
                if (!root.hasKey("Level", 10) || !level.hasKey("xPos", 3)
                    || !level.hasKey("zPos", 3)
                    || level.getInteger("xPos") != chunk.xPosition
                    || level.getInteger("zPos") != chunk.zPosition) throw new IOException("剪切原生存盘区块坐标无效，记录已保留");
                NBTTagList saved = tileList(level);
                for (int index = 0; index < saved.tagCount(); index++) {
                    NBTTagCompound tile = saved.getCompoundTagAt(index);
                    long key = tileKey(tile.getInteger("x"), tile.getInteger("y"), tile.getInteger("z"));
                    nativePositions.add(key);
                    if (!expected.containsKey(key)) continue;
                    TileEntity live = expected.get(key);
                    if (!tile.hasKey("x", 3) || !tile.hasKey("y", 3)
                        || !tile.hasKey("z", 3)
                        || !tile.hasKey("id", 8)
                        || tile.getString("id")
                            .isEmpty()
                        || tile.getInteger("x") != live.xCoord
                        || tile.getInteger("y") != live.yCoord
                        || tile.getInteger("z") != live.zCoord
                        || (live.xCoord >> 4) != chunk.xPosition
                        || (live.zCoord >> 4) != chunk.zPosition)
                        throw new IOException(
                            "剪切原生存盘实体坐标或类型无效 at [" + live.xCoord + "," + live.yCoord + "," + live.zCoord + "]");
                    if (tiles.put(key, (NBTTagCompound) tile.copy()) != null) throw new IOException(
                        "剪切原生存盘实体坐标重复 at [" + live.xCoord + "," + live.yCoord + "," + live.zCoord + "]");
                }
                for (Map.Entry<Long, TileEntity> entry : expected.entrySet()) {
                    TileEntity live = entry.getValue();
                    if ((live.xCoord >> 4) != chunk.xPosition || (live.zCoord >> 4) != chunk.zPosition) continue;
                    if (live.getWorldObj() != world || live.isInvalid()
                        || attachedTile(world, live.xCoord, live.yCoord, live.zCoord) != live)
                        throw new IOException(
                            "剪切方块实体在存盘期间被替换 at [" + live.xCoord + "," + live.yCoord + "," + live.zCoord + "]");
                    if (!tiles.containsKey(entry.getKey())) throw new IOException(
                        "剪切原生存盘遗漏方块实体 at [" + live.xCoord + "," + live.yCoord + "," + live.zCoord + "]");
                }
            } finally {
                activeChunk = null;
                root = null;
            }
        }

        void verifyPresence(int x, int y, int z, TileEntity live) throws IOException {
            if (nativePositions.contains(tileKey(x, y, z)) != (live != null)) throw new IOException(
                "剪切原生存盘实体覆盖不一致 at [" + x + "," + y + "," + z + "] expectedPresent=" + (live != null));
        }

        NBTTagCompound tile(int x, int y, int z) {
            return tiles.get(tileKey(x, y, z));
        }
    }

    private static NBTTagList tileList(NBTTagCompound level) throws IOException {
        if (!level.hasKey("TileEntities", 9)) throw new IOException("剪切存盘实体列表缺失，记录已保留");
        NBTTagList tiles = (NBTTagList) level.getTag("TileEntities");
        if (tiles.tagCount() > 0 && tiles.func_150303_d() != 10) throw new IOException("剪切存盘实体列表类型无效，记录已保留");
        return tiles;
    }

    /** Bounded structural diagnostics: paths and tag types only, never inventory/identity/string values. */
    static String firstNbtDifference(net.minecraft.nbt.NBTBase expected, net.minecraft.nbt.NBTBase actual) {
        return firstNbtDifference(expected, actual, "$", 0);
    }

    private static String firstNbtDifference(net.minecraft.nbt.NBTBase expected, net.minecraft.nbt.NBTBase actual,
        String path, int depth) {
        if (sameNbt(expected, actual)) return "equal";
        String reason = "value";
        if (expected == null || actual == null) reason = "missing";
        else if (expected.getId() != actual.getId()) reason = "type";
        else if (depth >= 32) reason = "depth-limit";
        else if (expected instanceof NBTTagCompound) {
            NBTTagCompound first = (NBTTagCompound) expected, second = (NBTTagCompound) actual;
            Set<String> keys = new TreeSet<>();
            for (Object key : first.func_150296_c()) keys.add((String) key);
            for (Object key : second.func_150296_c()) keys.add((String) key);
            for (String key : keys) {
                String next = path + "/" + diagnosticKey(key);
                String difference = firstNbtDifference(first.getTag(key), second.getTag(key), next, depth + 1);
                if (!"equal".equals(difference)) return difference;
            }
        } else if (expected instanceof NBTTagList) {
            NBTTagList first = (NBTTagList) expected.copy(), second = (NBTTagList) actual.copy();
            if (first.tagCount() != second.tagCount()) reason = "length";
            else for (int index = 0; first.tagCount() > 0; index++) {
                String difference = firstNbtDifference(
                    first.removeTag(0),
                    second.removeTag(0),
                    path + "[" + index + "]",
                    depth + 1);
                if (!"equal".equals(difference)) return difference;
            }
        }
        return "path=" + (path.length() <= 160 ? path : path.substring(0, 157) + "...")
            + " expectedType="
            + (expected == null ? "missing" : expected.getId())
            + " actualType="
            + (actual == null ? "missing" : actual.getId())
            + " reason="
            + reason;
    }

    private static String diagnosticKey(String key) {
        String bounded = key.length() <= 48 ? key : key.substring(0, 45) + "...";
        return bounded.replaceAll("[^A-Za-z0-9_:.-]", "?");
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
        private final Set<Long> duplicateTiles = new LinkedHashSet<>();

        SavedChunk(Chunk blocks, NBTTagCompound level) throws IOException {
            this.blocks = blocks;
            this.level = level;
            NBTTagList savedTiles = tileList(level);
            for (int t = 0; t < savedTiles.tagCount(); t++) {
                NBTTagCompound tile = savedTiles.getCompoundTagAt(t);
                long key = tileKey(tile.getInteger("x"), tile.getInteger("y"), tile.getInteger("z"));
                if (tiles.put(key, tile) != null) duplicateTiles.add(key);
            }
        }

        void verifyCell(int x, int y, int z, NBTTagCompound cell, boolean air, NBTTagCompound expectedTile)
            throws IOException {
            Block expectedBlock = air ? Blocks.air : Block.getBlockFromName(cell.getString("block"));
            Block actualBlock = blocks.getBlock(x & 15, y, z & 15);
            int expectedMetadata = air ? 0 : cell.getInteger("meta");
            int actualMetadata = blocks.getBlockMetadata(x & 15, y, z & 15);
            if (actualBlock != expectedBlock || actualMetadata != expectedMetadata) throw new IOException(
                "剪切世界方块未完整存盘 at [" + x
                    + ","
                    + y
                    + ","
                    + z
                    + "] expected="
                    + Block.blockRegistry.getNameForObject(expectedBlock)
                    + ":"
                    + expectedMetadata
                    + " actual="
                    + Block.blockRegistry.getNameForObject(actualBlock)
                    + ":"
                    + actualMetadata);
            long position = tileKey(x, y, z);
            if (duplicateTiles.contains(position))
                throw new IOException("剪切存盘实体坐标重复 at [" + x + "," + y + "," + z + "]");
            NBTTagCompound tile = tiles.get(position);
            if (!sameNbt(expectedTile, tile)) throw new IOException(
                "方块实体存盘读回与本次世界快照不一致，记录已保留 at [" + x
                    + ","
                    + y
                    + ","
                    + z
                    + "]"
                    + " native/disk="
                    + firstNbtDifference(expectedTile, tile));
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

    static long tileKey(int x, int y, int z) {
        return ((x & 0x3ffffffL) << 34) | ((z & 0x3ffffffL) << 8) | (y & 255L);
    }
}
