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
    private boolean modelIncomplete, renderingModel, closed;
    private String modelFailure = "";
    private HologramState.Cell laneReader;
    private final HologramNativePreparation<HologramState.Cell> preparation = new HologramNativePreparation<>(
        new HologramNativePreparation.Backend<HologramState.Cell>() {

            public HologramNativePreparation.Outcome outcome(HologramState.Cell cell,
                HologramNativePreparation.Stage stage) {
                return HologramBlockAccess.this.outcome(cell, stage);
            }

            public HologramNativePreparation.Attempt<HologramState.Cell> attempt(HologramState.Cell cell,
                HologramNativePreparation.Stage stage) {
                return HologramBlockAccess.this.attempt(cell, stage);
            }

            public void fail(HologramState.Cell cell, String reason) {
                HologramBlockAccess.this.fail(cell, reason);
            }

            public void discard(HologramState.Cell cell) {
                HologramBlockAccess.this.discard(cell);
            }
        });

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
        return tile(x, y, z, HologramNativePreparation.Stage.READY);
    }

    private TileEntity tile(int x, int y, int z, HologramNativePreparation.Stage stage) {
        if (closed) {
            failedRead("native access closed");
            return null;
        }
        dependency(true, x, y, z);
        // An authoritative failed dependency already rejects this attempt. Later reads cannot renew unknown work.
        if (!modelFailure.isEmpty() && (modelReader != null || renderingModel || preparation.inCallback())) return null;
        HologramState.Cell input = cells.at(x, y, z);
        if (input == null) return null;
        if (unknown(input)) {
            incomplete(input, stage, true);
            HologramSnapshotModelsClient.requestVisible(state, input, current);
            if (state.authorProjection == null) {
                HologramSnapshotModelsClient.dependency(modelReader, input);
                for (TileReadScope scope : tileReaders) HologramSnapshotModelsClient.dependency(scope.cell, input);
            }
            return null;
        }
        HologramNativePreparation.Outcome result = outcome(input, stage);
        if (result == HologramNativePreparation.Outcome.WAITING && !preparation.inCallback() && !renderingModel) {
            prepare(input, new HologramNativePreparation.Budget(Long.MAX_VALUE, 32, System::nanoTime));
            result = outcome(input, stage);
        }
        if (result == HologramNativePreparation.Outcome.FAILED) {
            failedRead(failure(x, y, z));
            return null;
        }
        if (result == HologramNativePreparation.Outcome.WAITING) {
            incomplete(input, stage, false);
            if (!preparation.inCallback() && preparation.request(input, stage)) laneReader = modelReader;
            return null;
        }
        return result == HologramNativePreparation.Outcome.ABSENT ? null : tiles.get(key(x, y, z));
    }

    private boolean unknown(HologramState.Cell input) {
        return input.modelPending(current) || HologramSnapshotModelsClient.resumableBudget(state, input);
    }

    private void incomplete(HologramState.Cell input, HologramNativePreparation.Stage stage, boolean appearance) {
        modelIncomplete = true;
        for (TileReadScope scope : tileReaders) {
            scope.incomplete = true;
            scope.appearance |= appearance;
            if (scope.blocker == null) {
                scope.blocker = input;
                scope.stage = stage;
            }
        }
        if (modelReader != null) HologramBlockRender.defer(state, modelReader, current);
    }

    private void failedRead(String reason) {
        modelFailure = reason.isEmpty() ? "native dependency failed" : reason;
        for (TileReadScope scope : tileReaders) scope.dependencyFailure = modelFailure;
    }

    HologramNativePreparation.Outcome outcome(HologramState.Cell cell) {
        return outcome(cell, HologramNativePreparation.Stage.READY);
    }

    private HologramNativePreparation.Outcome outcome(HologramState.Cell cell, HologramNativePreparation.Stage stage) {
        String key = key(cell.x, cell.y, cell.z);
        if (closed) return HologramNativePreparation.Outcome.FAILED;
        if (unknown(cell)) return HologramNativePreparation.Outcome.WAITING;
        if (failures.containsKey(key)) return HologramNativePreparation.Outcome.FAILED;
        if (!loaded.contains(key)) return HologramNativePreparation.Outcome.WAITING;
        if (!tiles.containsKey(key)) return HologramNativePreparation.Outcome.ABSENT;
        return stage == HologramNativePreparation.Stage.DATA || topologyPrepared.contains(key)
            ? HologramNativePreparation.Outcome.READY
            : HologramNativePreparation.Outcome.WAITING;
    }

    HologramNativePreparation.Outcome prepare(HologramState.Cell cell, HologramNativePreparation.Budget budget) {
        if (closed) return HologramNativePreparation.Outcome.FAILED;
        if (preparation.inCallback() || renderingModel) return outcome(cell);
        if (preparation.request(cell, HologramNativePreparation.Stage.READY))
            laneReader = modelReader == null ? cell : modelReader;
        HologramState.Cell savedReader = modelReader;
        boolean savedIncomplete = modelIncomplete;
        String savedFailure = modelFailure;
        modelReader = laneReader;
        modelIncomplete = false;
        modelFailure = "";
        try {
            preparation.advance(budget);
        } finally {
            modelReader = savedReader;
            modelIncomplete = savedIncomplete;
            modelFailure = savedFailure;
            if (!preparation.active()) laneReader = null;
        }
        return outcome(cell);
    }

    private HologramNativePreparation.Attempt<HologramState.Cell> attempt(HologramState.Cell cell,
        HologramNativePreparation.Stage stage) {
        String key = key(cell.x, cell.y, cell.z);
        TileReadScope scope = new TileReadScope(cell);
        tileReaders.addLast(scope);
        try {
            if (unknown(cell)) {
                incomplete(cell, stage, true);
                HologramSnapshotModelsClient.requestVisible(state, cell, current);
            } else if (stage == HologramNativePreparation.Stage.DATA) {
                NBTTagCompound display = actual(cell) ? cell.actualDisplay : cell.targetDisplay;
                if (!HologramNativeTiles.supported(cell.block(actual(cell)))) loaded.add(key);
                else if (!display.hasKey("nativeTile", 10)) {
                    if (required(cell)) scope.failure = "native required tile payload unavailable";
                    else loaded.add(key);
                } else {
                    if (renderWorld == null) renderWorld = new HologramRenderWorld(this);
                    loadingTile = true;
                    TileEntity tile;
                    try {
                        tile = HologramNativeTiles.create(
                            getBlock(cell.x, cell.y, cell.z),
                            getBlockMetadata(cell.x, cell.y, cell.z),
                            display.getCompoundTag("nativeTile"),
                            renderWorld,
                            cell.x,
                            cell.y,
                            cell.z);
                    } finally {
                        loadingTile = false;
                    }
                    if (!scope.incomplete && scope.failure.isEmpty() && scope.dependencyFailure.isEmpty()) {
                        if (tile == null && required(cell)) scope.failure = "native factory returned no required tile";
                        else {
                            loaded.add(key);
                            if (tile != null) tiles.put(key, tile);
                        }
                    }
                }
            } else prepareTopology(key, tiles.get(key));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            if (!scope.incomplete) {
                Throwable cause = unavailable;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                scope.failure = cause.getClass()
                    .getSimpleName() + ": "
                    + cause.getMessage();
            }
        } finally {
            tileReaders.removeLast();
        }
        // Explicit failedRead is authoritative even if this callback also saw an unknown input.
        // Callback exceptions after unknown remain retryable, as recorded separately below.
        if (!scope.dependencyFailure.isEmpty()) {
            fail(cell, scope.dependencyFailure);
            return HologramNativePreparation.Attempt.complete();
        }
        if (scope.incomplete) {
            // DATA survives only a reviewed topology wait for another ready-payload DATA object.
            if (stage == HologramNativePreparation.Stage.DATA || scope.appearance) discard(cell);
            else topologyPrepared.remove(key);
            return new HologramNativePreparation.Attempt<>(scope.blocker, scope.stage, scope.appearance);
        }
        if (!scope.failure.isEmpty()) fail(cell, scope.failure);
        return HologramNativePreparation.Attempt.complete();
    }

    private boolean required(HologramState.Cell cell) {
        Block block = cell.block(actual(cell));
        HologramNativeRenderRegistry.Adapter adapter = HologramNativeRenderRegistry.adapter(block);
        int meta = actual(cell) ? cell.meta : cell.wantMeta;
        return adapter == null ? block.hasTileEntity(meta) : adapter.requiresTile(block, meta);
    }

    private void discard(HologramState.Cell cell) {
        String key = key(cell.x, cell.y, cell.z);
        tiles.remove(key);
        loaded.remove(key);
        topologyPrepared.remove(key);
        failures.remove(key);
    }

    private void fail(HologramState.Cell cell, String reason) {
        discard(cell);
        String key = key(cell.x, cell.y, cell.z);
        loaded.add(key);
        failures.put(key, reason);
    }

    boolean owns(HologramState candidate, boolean side) {
        return !closed && state == candidate && current == side;
    }

    void close() {
        closed = true;
        preparation.close();
        tiles.clear();
        loaded.clear();
        topologyPrepared.clear();
        failures.clear();
        laneReader = null;
    }

    private static final class TileReadScope {

        final HologramState.Cell cell;
        boolean incomplete, appearance;
        HologramState.Cell blocker;
        HologramNativePreparation.Stage stage = HologramNativePreparation.Stage.READY;
        String failure = "", dependencyFailure = "";

        TileReadScope(HologramState.Cell cell) {
            this.cell = cell;
        }
    }

    private void dependency(boolean tile, int x, int y, int z) {
        if (state.authorProjection == null) return;
        dependency(modelReader, tile, x, y, z);
        // The iterative lane permits one native callback at a time. Publication edges still
        // consume the original shared bound and retain its conservative overflow behavior.
        for (TileReadScope scope : tileReaders) if (scope.cell != modelReader) dependency(scope.cell, tile, x, y, z);
    }

    private void dependency(HologramState.Cell reader, boolean tile, int x, int y, int z) {
        if (tile) state.authorProjection.dependency(reader, x, y, z);
        else state.authorProjection.blockDependency(reader, x, y, z);
    }

    void beginModel(HologramState.Cell cell) {
        beginModel(cell, false);
    }

    void beginModel(HologramState.Cell cell, boolean rendering) {
        if (modelReader != null || preparation.inCallback())
            throw new IllegalStateException("nested native model scope");
        modelReader = cell;
        modelIncomplete = false;
        modelFailure = "";
        renderingModel = rendering;
    }

    boolean endModel() {
        modelReader = null;
        renderingModel = false;
        return !modelIncomplete && modelFailure.isEmpty();
    }

    String modelFailure() {
        return modelFailure;
    }

    /** Only the reviewed AE topology resolver receives DATA-ready neighbor access. */
    private void prepareTopology(String key, TileEntity tile) throws ReflectiveOperationException {
        if (tile == null || topologyPrepared.contains(key)) return;
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
                HologramAeNativeRender.prepareTopology(tile, new DataAccess());
            } finally {
                preparingTopology = false;
            }
        }
        topologyPrepared.add(key);
    }

    private final class DataAccess implements IBlockAccess {

        public TileEntity getTileEntity(int x, int y, int z) {
            return tile(x, y, z, HologramNativePreparation.Stage.DATA);
        }

        public Block getBlock(int x, int y, int z) {
            return HologramBlockAccess.this.getBlock(x, y, z);
        }

        public int getBlockMetadata(int x, int y, int z) {
            return HologramBlockAccess.this.getBlockMetadata(x, y, z);
        }

        public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minimum) {
            return 0xf000f0;
        }

        public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
            return 0;
        }

        public boolean isAirBlock(int x, int y, int z) {
            return HologramBlockAccess.this.isAirBlock(x, y, z);
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
            return HologramBlockAccess.this.isSideSolid(x, y, z, side, fallback);
        }
    }

    String failure(int x, int y, int z) {
        return closed ? "native access closed" : failures.getOrDefault(key(x, y, z), "");
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
