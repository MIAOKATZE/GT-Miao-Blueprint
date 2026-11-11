// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.util.ForgeDirection;

/** A detached client world: tile callbacks can read the snapshot, but cannot change its cells. */
final class HologramRenderWorld extends World {

    private IBlockAccess access;
    private MetadataOverride metadataOverride;

    HologramRenderWorld(HologramBlockAccess access) {
        this((IBlockAccess) access);
    }

    HologramRenderWorld(IBlockAccess access) {
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
        if (metadataOverride != null && metadataOverride.matches(x, y, z) && metadataOverride.metadata != null)
            return metadataOverride.metadata;
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
    public int getBlockLightOpacity(int x, int y, int z) {
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000 || y < 0 || y >= 256) return 0;
        return getBlock(x, y, z).getLightOpacity(this, x, y, z);
    }

    @Override
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) return fallback;
        return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
    }

    @Override
    public boolean blockExists(int x, int y, int z) {
        return access != null
            && (access instanceof HologramBlockAccess ? ((HologramBlockAccess) access).contains(x, y, z)
                : !access.isAirBlock(x, y, z));
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
        return false;
    }

    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
        if (flags == 4 && metadataOverride != null && metadataOverride.matches(x, y, z)) {
            metadataOverride.metadata = meta;
            return true;
        }
        return false;
    }

    /** A reviewed renderer can impersonate material metadata only at its own detached tile position. */
    MetadataOverride metadataOverride(TileEntity tile) {
        if (tile == null || tile.getWorldObj() != this
            || metadataOverride != null
            || getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) != tile)
            throw new IllegalArgumentException("Metadata override requires one matching detached tile");
        metadataOverride = new MetadataOverride(tile.xCoord, tile.yCoord, tile.zCoord);
        return metadataOverride;
    }

    final class MetadataOverride implements HologramNativeRenderRegistry.RenderScope {

        final int x, y, z;
        Integer metadata;

        MetadataOverride(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean matches(int x, int y, int z) {
            return this.x == x && this.y == y && this.z == z;
        }

        @Override
        public IBlockAccess access() {
            return HologramRenderWorld.this;
        }

        @Override
        public void close() {
            metadata = null;
            if (metadataOverride == this) metadataOverride = null;
        }
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
    public boolean func_147451_t(int x, int y, int z) {
        return false;
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}
}
