package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.entity.Entity;
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

/** A detached client world: tile callbacks can read the snapshot, but cannot change its cells. */
final class HologramRenderWorld extends World {

    private HologramBlockAccess access;

    HologramRenderWorld(HologramBlockAccess access) {
        super(
            new SaveHandlerMP(),
            "GTMB render snapshot",
            new WorldProviderSurface(),
            new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
            new Profiler());
        this.access = access;
        isRemote = true;
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
        return access == null ? net.minecraft.init.Blocks.air : access.getBlock(x, y, z);
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        return access == null ? 0 : access.getBlockMetadata(x, y, z);
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return access == null ? null : access.getTileEntity(x, y, z);
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
    public boolean blockExists(int x, int y, int z) {
        return access != null && access.contains(x, y, z);
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
        return false;
    }

    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
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
