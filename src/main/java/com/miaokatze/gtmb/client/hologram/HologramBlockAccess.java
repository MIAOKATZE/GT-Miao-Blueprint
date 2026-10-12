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

    private final Map<String, HologramState.Cell> cells = new HashMap<>();
    private final boolean current;
    private final Map<String, TileEntity> tiles = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private final java.util.Set<String> loaded = new java.util.HashSet<>();
    private HologramRenderWorld renderWorld;

    HologramBlockAccess(HologramState state) {
        this(state, false);
    }

    HologramBlockAccess(HologramState state, boolean current) {
        this.current = current;
        for (HologramState.Cell cell : state.cells) cells.put(key(cell.x, cell.y, cell.z), cell);
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    public Block getBlock(int x, int y, int z) {
        HologramState.Cell cell = cells.get(key(x, y, z));
        Block block = cell == null ? null : cell.block(actual(cell));
        return block == null ? Blocks.air : block;
    }

    public int getBlockMetadata(int x, int y, int z) {
        HologramState.Cell cell = cells.get(key(x, y, z));
        return cell == null ? 0 : actual(cell) ? cell.meta : cell.wantMeta;
    }

    public TileEntity getTileEntity(int x, int y, int z) {
        String key = key(x, y, z);
        if (loaded.contains(key)) return tiles.get(key);
        loaded.add(key);
        HologramState.Cell cell = cells.get(key);
        if (cell == null) return null;
        boolean actual = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        NBTTagCompound display = actual ? cell.actualDisplay : cell.targetDisplay;
        if (!display.hasKey("nativeTile", 10)) return null;
        try {
            if (renderWorld == null) renderWorld = new HologramRenderWorld(this);
            TileEntity tile = HologramNativeTiles.create(
                getBlock(x, y, z),
                getBlockMetadata(x, y, z),
                display.getCompoundTag("nativeTile"),
                renderWorld,
                x,
                y,
                z);
            if (tile != null) tiles.put(key, tile);
            return tile;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
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

    String failure(int x, int y, int z) {
        return failures.getOrDefault(key(x, y, z), "");
    }

    boolean contains(int x, int y, int z) {
        return cells.containsKey(key(x, y, z));
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
