package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldInfo;

/** Single detached cell for audited persistence/drop callbacks, on either logical side. */
final class HologramResourceWorld extends World {

    // Forge adds these exact, unmapped fields in the patched 1.7.10 World source.
    private static final Field SAVE_CACHE = worldCacheField("s_savehandler", ISaveHandler.class);
    private static final Field MAP_CACHE = worldCacheField("s_mapStorage", MapStorage.class);

    private final Block block;
    private final int metadata;
    private boolean declarations;
    TileEntity tile;

    /** A detached controller may inspect any declaration coordinate; every other cell is read-only air. */
    static HologramResourceWorld declarations(Block block, int metadata) {
        // GT's alignment setter must keep its native dummy-world branch and never publish a real-world packet.
        gregtech.api.GregTechAPI.addDummyWorld(HologramResourceWorld.class);
        HologramResourceWorld world = create(block, metadata);
        world.declarations = true;
        return world;
    }

    /** Main-thread callers retain their own in-memory storage without disturbing Forge's live shared cache. */
    static synchronized HologramResourceWorld create(Block block, int metadata) {
        try {
            Object previousSave = SAVE_CACHE.get(null);
            Object previousMap = MAP_CACHE.get(null);
            try {
                HologramResourceWorld created = new HologramResourceWorld(block, metadata);
                HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.MATERIAL_WORLD_CREATED, 1);
                return created;
            } finally {
                try {
                    SAVE_CACHE.set(null, previousSave);
                } finally {
                    MAP_CACHE.set(null, previousMap);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot preserve Forge World storage cache", failure);
        }
    }

    private static Field worldCacheField(String name, Class<?> type) {
        try {
            Field field = World.class.getDeclaredField(name);
            if (field.getType() != type || !Modifier.isStatic(field.getModifiers()))
                throw new IllegalStateException("Unexpected Forge World storage field " + name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Missing audited Forge World storage field " + name, failure);
        }
    }

    private HologramResourceWorld(Block block, int metadata) {
        super(
            new MemorySaveHandler(),
            "GTMB material inspection",
            new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
            new WorldProviderSurface(),
            new Profiler());
        this.block = block;
        this.metadata = metadata;
        // Native server readers (notably EnderIO) must not enter client-only Minecraft branches.
        isRemote = false;
    }

    /** Common-side persistence stub. No directory, chunk, player or map file can be opened. */
    private static final class MemorySaveHandler implements ISaveHandler {

        private final WorldInfo info;

        MemorySaveHandler() {
            info = new WorldInfo(
                new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
                "GTMB material inspection");
            info.setServerInitialized(true);
        }

        @Override
        public WorldInfo loadWorldInfo() {
            return info;
        }

        @Override
        public void checkSessionLock() {}

        @Override
        public IChunkLoader getChunkLoader(WorldProvider provider) {
            return null;
        }

        @Override
        public void saveWorldInfoWithPlayer(WorldInfo info, NBTTagCompound player) {}

        @Override
        public void saveWorldInfo(WorldInfo info) {}

        @Override
        public IPlayerFileData getSaveHandler() {
            return null;
        }

        @Override
        public void flush() {}

        @Override
        public File getWorldDirectory() {
            return null;
        }

        @Override
        public File getMapFileFromName(String name) {
            return null;
        }

        @Override
        public String getWorldDirectoryName() {
            return "GTMB detached materials";
        }
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return (IChunkProvider) Proxy.newProxyInstance(
            IChunkProvider.class.getClassLoader(),
            new Class<?>[] { IChunkProvider.class },
            (proxy, method, args) -> {
                Class<?> result = method.getReturnType();
                if (result == boolean.class) return false;
                if (result == int.class) return 0;
                if (result == String.class) return "GTMB detached materials";
                return null;
            });
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
        if (declarations) return atTile(x, y, z) ? block : Blocks.air;
        return blockExists(x, y, z) ? block : Blocks.air;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        if (declarations) return atTile(x, y, z) ? metadata : 0;
        return blockExists(x, y, z) ? metadata : 0;
    }

    @Override
    public int getBlockLightOpacity(int x, int y, int z) {
        // Reporting parts query neighboring opacity while their native reader attaches them. World's default
        // implementation dereferences a real Chunk, which this read-only, single-cell world never provides.
        return getBlock(x, y, z).getLightOpacity(this, x, y, z);
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        if (declarations) return atTile(x, y, z) ? tile : null;
        return blockExists(x, y, z) ? tile : null;
    }

    @Override
    public boolean blockExists(int x, int y, int z) {
        if (declarations) return true;
        return tile == null ? x == 0 && y == 64 && z == 0 : x == tile.xCoord && y == tile.yCoord && z == tile.zCoord;
    }

    private boolean atTile(int x, int y, int z) {
        return tile != null && x == tile.xCoord && y == tile.yCoord && z == tile.zCoord;
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return BiomeGenBase.plains;
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
        return false;
    }

    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
        return false;
    }

    @Override
    public void setTileEntity(int x, int y, int z, TileEntity tile) {}

    @Override
    public void removeTileEntity(int x, int y, int z) {}

    @Override
    public void markBlockForUpdate(int x, int y, int z) {}

    @Override
    public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {}

    @Override
    public void notifyBlockChange(int x, int y, int z, Block block) {}

    @Override
    public void notifyBlocksOfNeighborChange(int x, int y, int z, Block block) {}

    @Override
    public void notifyBlockOfNeighborChange(int x, int y, int z, Block block) {}

    @Override
    public boolean spawnEntityInWorld(Entity entity) {
        return false;
    }

    @Override
    public void scheduleBlockUpdate(int x, int y, int z, Block block, int delay) {}

    @Override
    public boolean updateLightByType(EnumSkyBlock type, int x, int y, int z) {
        return false;
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}
}
