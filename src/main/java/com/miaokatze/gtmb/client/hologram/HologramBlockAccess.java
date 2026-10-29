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
    private final HologramState state;
    private final boolean current;
    private final Map<String, TileEntity> tiles = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private final java.util.Set<String> loaded = new java.util.HashSet<>();
    private final java.util.Set<String> topologyPrepared = new java.util.HashSet<>();
    private boolean preparingTopology;
    private boolean loadingTile;
    private HologramRenderWorld renderWorld;
    private HologramState.Cell modelReader;
    private final java.util.Deque<TileReadScope> tileReaders = new java.util.ArrayDeque<>();
    private boolean modelIncomplete;

    HologramBlockAccess(HologramState state) {
        this(state, false);
    }

    HologramBlockAccess(HologramState state, boolean current) {
        this.state = state;
        this.current = current;
        cells = state.regions;
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    public Block getBlock(int x, int y, int z) {
        dependency(false, x, y, z);
        HologramState.Cell cell = cells.at(x, y, z);
        Block block = cell == null ? null : cell.block(actual(cell));
        return block == null ? Blocks.air : block;
    }

    public int getBlockMetadata(int x, int y, int z) {
        dependency(false, x, y, z);
        HologramState.Cell cell = cells.at(x, y, z);
        return cell == null ? 0 : actual(cell) ? cell.meta : cell.wantMeta;
    }

    public TileEntity getTileEntity(int x, int y, int z) {
        dependency(true, x, y, z);
        String key = key(x, y, z);
        HologramState.Cell input = cells.at(x, y, z);
        if (input != null
            && (input.modelPending(current) || HologramSnapshotModelsClient.resumableBudget(state, input))) {
            modelIncomplete = true;
            for (TileReadScope scope : tileReaders) scope.incomplete = true;
            HologramSnapshotModelsClient.requestVisible(state, input, current);
            if (state.authorProjection == null) HologramSnapshotModelsClient.dependency(modelReader, input);
            if (modelReader != null) HologramBlockRender.defer(modelReader, current);
            return null; // Unknown visual data is not a known empty tile and must not enter loaded/failure caches.
        }
        // Native NBT readers can invalidate all six neighbors. A connected picture
        // must not recursively deserialize the entire surface on the Java stack.
        // Only completed neighbors are visible during hydration; unvisited cells
        // remain eligible for their own later render request.
        if (loadingTile) return tiles.get(key);
        // A's renderer may hydrate B before B's own renderer runs. Reads made while loading
        // B or preparing its cached topology must belong to B too: a later cache hit cannot
        // replay those reads. Keep root model/defer state independent from this tile scope.
        TileReadScope scope = input == null ? null : new TileReadScope(input);
        if (scope != null) tileReaders.addLast(scope);
        try {
            if (loaded.contains(key)) {
                TileEntity tile = tiles.get(key);
                if (tile != null) prepareTopology(key, tile);
                return completedTile(key, tile, scope);
            }
            loaded.add(key);
            HologramState.Cell cell = cells.at(x, y, z);
            if (cell == null) return null;
            boolean actual = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
            NBTTagCompound display = actual ? cell.actualDisplay : cell.targetDisplay;
            if (!display.hasKey("nativeTile", 10)) return null;
            if (renderWorld == null) renderWorld = new HologramRenderWorld(this);
            TileEntity tile;
            loadingTile = true;
            try {
                tile = HologramNativeTiles.create(
                    getBlock(x, y, z),
                    getBlockMetadata(x, y, z),
                    display.getCompoundTag("nativeTile"),
                    renderWorld,
                    x,
                    y,
                    z);
            } finally {
                loadingTile = false;
            }
            if (scope != null && scope.incomplete) return completedTile(key, null, scope);
            if (tile != null) {
                tiles.put(key, tile);
                prepareTopology(key, tile);
            }
            return completedTile(key, tile, scope);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // A native reader may dereference an unknown neighbor. This remains retryable
            // incomplete input, not a terminal malformed-tile/factory failure.
            if (scope != null && scope.incomplete) return completedTile(key, null, scope);
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
        } finally {
            if (scope != null) tileReaders.removeLast();
        }
    }

    private TileEntity completedTile(String key, TileEntity tile, TileReadScope scope) {
        if (scope == null || !scope.incomplete) return tile;
        // Do not let a later model's cache hit turn an earlier unknown read into known
        // visual state. Retrying must execute the reader and renew any dropped demand.
        tiles.remove(key);
        loaded.remove(key);
        topologyPrepared.remove(key);
        failures.remove(key);
        return null;
    }

    private static final class TileReadScope {

        final HologramState.Cell cell;
        boolean incomplete;

        TileReadScope(HologramState.Cell cell) {
            this.cell = cell;
        }
    }

    private void dependency(boolean tile, int x, int y, int z) {
        if (state.authorProjection == null) return;
        dependency(modelReader, tile, x, y, z);
        // Existing loading/topology recursion guards bound this stack; graph edges still
        // consume the original shared bound and retain its conservative overflow behavior.
        for (TileReadScope scope : tileReaders) if (scope.cell != modelReader) dependency(scope.cell, tile, x, y, z);
    }

    private void dependency(HologramState.Cell reader, boolean tile, int x, int y, int z) {
        if (tile) state.authorProjection.dependency(reader, x, y, z);
        else state.authorProjection.blockDependency(reader, x, y, z);
    }

    void beginModel(HologramState.Cell cell) {
        modelReader = cell;
        modelIncomplete = false;
    }

    boolean endModel() {
        modelReader = null;
        return !modelIncomplete;
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
        // RenderWorld.blockExists is a source-topology read, including currently empty coordinates.
        dependency(false, x, y, z);
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
