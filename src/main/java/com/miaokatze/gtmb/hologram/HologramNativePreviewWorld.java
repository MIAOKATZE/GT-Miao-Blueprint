// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;

import gregtech.api.GregTechAPI;

/** Observes native preview placements in memory without chunks, lifecycle events, ticks, or a renderer. */
final class HologramNativePreviewWorld extends HologramResourceWorld {

    @Desugar
    record Placement(int x, int y, int z, Block block, int meta, TileEntity tile) {}

    @Desugar
    record Position(int x, int y, int z) {}

    private static final int CHUNK_LIMIT = 30_000_000 >> 4;
    private final Thread owner = Thread.currentThread();
    private final Map<Position, Placement> placements = new LinkedHashMap<>();
    private final Position anchor;
    private final Block controllerBlock;
    private final int controllerMeta;
    private boolean recording = true;
    // The browser counts creation of the detached controller as the first world write.
    private boolean changed = true;

    private HologramNativePreviewWorld(TileEntity controller, Block block, int metadata) {
        super(block, metadata);
        anchor = new Position(controller.xCoord, controller.yCoord, controller.zCoord);
        controllerBlock = block;
        controllerMeta = metadata;
        tile = controller;
    }

    static HologramNativePreviewWorld open(TileEntity detached) {
        if (!(detached.getWorldObj() instanceof HologramResourceWorld source) || source instanceof HologramNativePreviewWorld
            || source.getTileEntity(detached.xCoord, detached.yCoord, detached.zCoord) != detached)
            throw new IllegalArgumentException("LOCAL_NATIVE_PREVIEW_REQUIRES_DETACHED_CONTROLLER");
        Block block = source.getBlock(detached.xCoord, detached.yCoord, detached.zCoord);
        int metadata = source.getBlockMetadata(detached.xCoord, detached.yCoord, detached.zCoord);
        GregTechAPI.addDummyWorld(HologramNativePreviewWorld.class);
        return create(() -> new HologramNativePreviewWorld(detached, block, metadata));
    }

    Iterator<Placement> placements() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_SCOPE");
        // Iterate the finished footprint even when candidate queries call back into the world.
        recording = false;
        return placements.values().iterator();
    }

    boolean takeChanged() {
        boolean result = changed;
        changed = false;
        return result;
    }

    @Override
    boolean previewChunkExists(int x, int z) {
        return x >= -CHUNK_LIMIT && x < CHUNK_LIMIT && z >= -CHUNK_LIMIT && z < CHUNK_LIMIT;
    }

    @Override
    public boolean blockExists(int x, int y, int z) {
        return y >= 0 && y < getHeight() && previewChunkExists(x >> 4, z >> 4);
    }

    @Override
    public boolean canBlockSeeTheSky(int x, int y, int z) {
        // BlockRenderer's detached preview keeps geometry outside its empty chunks and their height maps.
        return y >= 0;
    }

    @Override
    public boolean checkNoEntityCollision(AxisAlignedBB bounds, Entity excluded) {
        return true;
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        Position position = new Position(x, y, z);
        if (position.equals(anchor)) return controllerBlock;
        Placement placement = placements.get(position);
        return placement == null ? Blocks.air : placement.block;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        Position position = new Position(x, y, z);
        if (position.equals(anchor)) return controllerMeta;
        Placement placement = placements.get(position);
        return placement == null ? 0 : placement.meta;
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        Position position = new Position(x, y, z);
        if (position.equals(anchor)) return tile;
        Placement placement = placements.get(position);
        return placement == null ? null : placement.tile;
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
        Position position = writable(x, y, z);
        if (position.equals(anchor)) return false;
        Placement previous = placements.get(position);
        if (block == Blocks.air) {
            if (previous == null) return false;
            placements.remove(position);
            changed = true;
            return true;
        }
        if (previous != null && previous.block == block && previous.meta == metadata) return true;
        if (previous == null && placements.size() >= HologramBlueprintLimits.MAX_CELLS - 1)
            throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
        TileEntity next = block.hasTileEntity(metadata) ? block.createTileEntity(this, metadata) : null;
        put(position, block, metadata, next);
        return true;
    }

    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
        Position position = writable(x, y, z);
        Placement old = placements.get(position);
        if (old == null) return false;
        if (old.meta != metadata) put(position, old.block, metadata, old.tile);
        return true;
    }

    @Override
    public void setTileEntity(int x, int y, int z, TileEntity next) {
        Position position = writable(x, y, z);
        Placement old = placements.get(position);
        if (old != null && old.tile != next) put(position, old.block, old.meta, next);
    }

    @Override
    public void removeTileEntity(int x, int y, int z) {
        setTileEntity(x, y, z, null);
    }

    private Position writable(int x, int y, int z) {
        if (!recording || Thread.currentThread() != owner) throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_SCOPE");
        if (!blockExists(x, y, z)) throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_OUT_OF_BOUNDS");
        return new Position(x, y, z);
    }

    private void put(Position position, Block block, int metadata, TileEntity next) {
        if (next != null) {
            World current = next.getWorldObj();
            if (current != null && current != this) throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_FOREIGN_TILE");
            next.setWorldObj(this);
            next.xCoord = position.x;
            next.yCoord = position.y;
            next.zCoord = position.z;
            next.blockMetadata = metadata;
        }
        placements.put(position, new Placement(position.x, position.y, position.z, block, metadata, next));
        changed = true;
    }
}
