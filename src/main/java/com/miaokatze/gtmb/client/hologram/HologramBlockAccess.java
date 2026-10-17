package com.miaokatze.gtmb.client.hologram;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

/** Isolated, bounded render snapshot. Never exposes a live world or a live tile entity to renderers. */
final class HologramBlockAccess implements IBlockAccess {

    private final HologramRegionCache cells;
    private final boolean current;
    private final Map<String, TileEntity> tiles = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private final java.util.Set<String> loaded = new java.util.HashSet<>();
    private final java.util.Set<String> topologyPrepared = new java.util.HashSet<>();
    private boolean preparingTopology;
    private HologramRenderWorld renderWorld;

    HologramBlockAccess(HologramState state) {
        this(state, false);
    }

    HologramBlockAccess(HologramState state, boolean current) {
        this.current = current;
        cells = state.regions;
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    public Block getBlock(int x, int y, int z) {
        HologramState.Cell cell = cells.at(x, y, z);
        Block block = cell == null ? null : cell.block(actual(cell));
        return block == null ? Blocks.air : block;
    }

    public int getBlockMetadata(int x, int y, int z) {
        HologramState.Cell cell = cells.at(x, y, z);
        return cell == null ? 0 : actual(cell) ? cell.meta : cell.wantMeta;
    }

    public TileEntity getTileEntity(int x, int y, int z) {
        String key = key(x, y, z);
        try {
            if (loaded.contains(key)) {
                TileEntity tile = tiles.get(key);
                if (tile != null) prepareTopology(key, tile);
                return tile;
            }
            loaded.add(key);
            HologramState.Cell cell = cells.at(x, y, z);
            if (cell == null) return null;
            boolean actual = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
            NBTTagCompound display = actual ? cell.actualDisplay : cell.targetDisplay;
            if (!display.hasKey("nativeTile", 10)) return null;
            if (renderWorld == null) renderWorld = new HologramRenderWorld(this);
            TileEntity tile = HologramNativeTiles.create(
                getBlock(x, y, z),
                getBlockMetadata(x, y, z),
                display.getCompoundTag("nativeTile"),
                renderWorld,
                x,
                y,
                z);
            if (tile != null) {
                tiles.put(key, tile);
                prepareTopology(key, tile);
            }
            return tile;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            tiles.remove(key);
            topologyPrepared.remove(key);
            if (Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke")) unavailable.printStackTrace();
            Throwable cause = unavailable;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            failures.put(
                key,
                cause.getClass()
                    .getSimpleName() + ": "
                    + cause.getMessage());
            return null;
        }
    }

    /** Hydrate at most immediate neighbors, then prepare each cached tile when it is requested for rendering. */
    private void prepareTopology(String key, TileEntity tile) throws ReflectiveOperationException {
        if (preparingTopology || topologyPrepared.contains(key)) return;
        // Ordinary native hosts must remain independent of optional AE client class loading.
        String host = getBlock(tile.xCoord, tile.yCoord, tile.zCoord).getClass()
            .getName();
        boolean cable = host.equals("appeng.block.networking.BlockCableBus");
        if (host.equals("codechicken.multipart.BlockMultipart")) for (Object part : (java.util.List<?>) tile.getClass()
            .getMethod("jPartList")
            .invoke(tile))
            if (part.getClass()
                .getName()
                .equals("appeng.fmp.CableBusPart")) cable = true;
        if (cable) {
            preparingTopology = true;
            try {
                HologramAeNativeRender.prepareTopology(tile, this);
            } finally {
                preparingTopology = false;
            }
        }
        topologyPrepared.add(key);
    }

    String failure(int x, int y, int z) {
        return failures.getOrDefault(key(x, y, z), "");
    }

    boolean contains(int x, int y, int z) {
        return cells.at(x, y, z) != null;
    }

    private boolean actual(HologramState.Cell cell) {
        return current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
    }

    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minimum) {
        return 0xf000f0;
    }

    public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
        return 0;
    }

    public boolean isAirBlock(int x, int y, int z) {
        return getBlock(x, y, z) == Blocks.air;
    }

    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return BiomeGenBase.plains;
    }

    public int getHeight() {
        return 256;
    }

    public boolean extendedLevelsInChunkCache() {
        return false;
    }

    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
        return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
    }
}
