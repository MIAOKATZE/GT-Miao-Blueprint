package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.util.ForgeDirection;

/** Local raw cut cells, never a registered dimension, ticking world, or wrapper around the live client world. */
final class HologramCutRawPreviewWorld extends World {

    final Map<String, Cell> cells = new LinkedHashMap<>();
    private BiConsumer<Cell, Throwable> failure;
    private final Map<String, Integer> renderMetadata = new LinkedHashMap<>();
    private boolean released;

    HologramCutRawPreviewWorld(NBTTagCompound entry, BiConsumer<Cell, Throwable> failure) {
        super(
            new SaveHandlerMP(),
            "GTMB raw cut preview",
            new WorldProviderSurface(),
            new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
            new Profiler());
        isRemote = true;
        this.failure = failure;
        NBTTagList rows = entry.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            Cell cell = new Cell(rows.getCompoundTagAt(i));
            cells.put(key(cell.x, cell.y, cell.z), cell);
        }
    }

    static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    Cell cell(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return new ChunkProviderClient(this);
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
        Cell cell = cell(x, y, z);
        return cell == null || cell.block == null ? Blocks.air : cell.block;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        Cell cell = cell(x, y, z);
        return renderMetadata.getOrDefault(key(x, y, z), cell == null ? 0 : cell.meta);
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        Cell cell = cell(x, y, z);
        if (released || cell == null || cell.attempted || cell.loading) return cell == null ? null : cell.tile;
        if (cell.block == null || cell.block == Blocks.air) return null;
        if (!cell.raw.hasKey("tile", 10) && !cell.block.hasTileEntity(cell.meta)) return null;
        cell.loading = true;
        try {
            cell.tile = HologramCutRawPreviewFactory.create(this, cell);
            if (cell.tile == null) throw new IllegalStateException("Native tile factory returned null");
        } catch (Throwable invalid) {
            if (invalid instanceof VirtualMachineError) throw (VirtualMachineError) invalid;
            if (invalid instanceof ThreadDeath) throw (ThreadDeath) invalid;
            if (cell.tile != null) cell.tile.setWorldObj(null);
            cell.tile = null;
            failure.accept(cell, invalid);
        } finally {
            cell.loading = false;
            cell.attempted = true;
        }
        return cell.tile;
    }

    @Override
    public boolean blockExists(int x, int y, int z) {
        return cell(x, y, z) != null;
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        return getBlock(x, y, z) == Blocks.air;
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return BiomeGenBase.plains;
    }

    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minimum) {
        return 0xf000f0;
    }

    @Override
    public float getLightBrightness(int x, int y, int z) {
        return 1;
    }

    @Override
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
        return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
        return false;
    }

    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
        // Native cover renderers may temporarily impersonate their skin's metadata. This is a disposable overlay.
        if (flags != 4 || !blockExists(x, y, z)) return false;
        renderMetadata.put(key(x, y, z), meta);
        return true;
    }

    @Override
    public void setTileEntity(int x, int y, int z, TileEntity tile) {}

    @Override
    public void removeTileEntity(int x, int y, int z) {}

    @Override
    public void addTileEntity(TileEntity tile) {}

    @Override
    public void func_147448_a(java.util.Collection<TileEntity> tiles) {}

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
    public boolean func_147451_t(int x, int y, int z) {
        return false;
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}

    void endRender() {
        renderMetadata.clear();
    }

    void release() {
        released = true;
        // invalidate/onChunkUnload dispatch native lifecycle callbacks. None ran on admission, none run on disposal.
        for (Cell cell : cells.values()) {
            if (cell.tile != null) cell.tile.setWorldObj(null);
            cell.tile = null;
        }
        cells.clear();
        renderMetadata.clear();
        failure = (cell, error) -> {};
    }

    static final class Cell {

        final NBTTagCompound raw;
        final int x, y, z, meta;
        final Block block;
        TileEntity tile;
        NBTTagCompound factoryPayload;
        NBTTagList partReaderInputs;
        String nativeClass = "", factoryPayloadKind = "";
        int nativeReaderCalls;
        boolean nativeReaderCompleted;
        boolean attempted, loading, staticFailed, dynamicFailed;

        Cell(NBTTagCompound row) {
            raw = (NBTTagCompound) row.copy();
            x = raw.getInteger("x");
            y = raw.getInteger("y");
            z = raw.getInteger("z");
            meta = raw.getInteger("meta");
            block = Block.getBlockFromName(raw.getString("block"));
        }
    }
}
