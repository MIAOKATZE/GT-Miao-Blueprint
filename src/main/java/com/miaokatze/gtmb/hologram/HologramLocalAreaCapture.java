// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;


/** Captures the player's loaded region on the client tick, then prepares a detached local draft. */
public final class HologramLocalAreaCapture implements AutoCloseable {

    private final Thread owner = Thread.currentThread();
    private final World world;
    private final int dimension;
    private final int minX, minY, minZ;
    private final HologramAreaBlockCursor cursor;
    private final HologramPresets.Preset draft = new HologramPresets.Preset();
    private final HologramAreaPreparation preparation = new HologramAreaPreparation();
    private final HologramLiteralAreaWriter writer = new HologramLiteralAreaWriter();
    private final Set<UUID> seenFrames = new HashSet<>();
    private final List<HologramAreaSelection.Region> regions;
    private Chunk chunk;
    private boolean closed, completed;
    private int shown;

    public HologramLocalAreaCapture(World world, int dimension, HologramAreaSelection selection)
        throws IOException {
        if (world == null || !world.isRemote) throw new IllegalArgumentException("Client world required");
        this.world = world;
        this.dimension = dimension;
        int[] bounds = selection.bounds();
        minX = bounds[0];
        minY = bounds[1];
        minZ = bounds[2];
        if (!HologramWorldFlow.worldPosition(minX, minY, minZ, world.getHeight())
            || !HologramWorldFlow.worldPosition(bounds[3], bounds[4], bounds[5], world.getHeight()))
            throw new IOException("区域超出世界坐标边界");
        regions = selection.resolvedRegions();
        cursor = new HologramAreaBlockCursor(regions);
        draft.cells = new HologramLiteralCaptureRows();
        draft.schema = 2;
        draft.name = "施工区域";
        draft.controllerType = "area";
    }

    public HologramLocalBlueprintDocument step(int budget, long nanos) throws IOException {
        if (Thread.currentThread() != owner || closed || budget <= 0 || nanos <= 0)
            throw new IllegalStateException("Inactive local area capture");
        long deadline = System.nanoTime() + nanos;
        for (int work = 0; !cursor.done() && slice(work, budget, deadline); work++)
            if (cursor.step(this::emptySection)) readCell();
        if (!cursor.done()) return null;
        chunk = null;
        if (draft.cells.isEmpty()) throw new IOException("区域内没有可导入的方块");
        if (!preparation.captureLocalShells(draft, budget, deadline) || !preparation.start(draft)
            || !preparation.ready()) return null;
        HologramLocalBlueprintDocument result = HologramLocalBlueprintDocument
            .fromCapture(preparation.result(), dimension, minX, minY, minZ);
        completed = true;
        close();
        return result;
    }

    public int progress() {
        if (completed) return 100;
        if (!cursor.done()) return (int)(cursor.completed()*80 / cursor.total());
        HologramAreaPreparation.Progress current=preparation.progress();
        int phase=current.total>0?(int)Math.min(19,current.done*19/current.total):0;
        shown=Math.max(shown,80+phase);return shown;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        preparation.cancel();
        chunk = null;
    }

    private boolean emptySection(int chunkX, int sectionY, int chunkZ) throws IOException {
        if (!world.getChunkProvider()
            .chunkExists(chunkX, chunkZ)) throw new IOException("区域包含未加载的客户端区块，请靠近后重试");
        chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
        if (chunk == null || chunk instanceof EmptyChunk) throw new IOException("区域包含未加载的客户端区块，请靠近后重试");
        readFrames(sectionY);
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        return sectionY >= sections.length || sections[sectionY] == null || sections[sectionY].isEmpty();
    }

    private void readFrames(int sectionY) {
        if (sectionY >= chunk.entityLists.length || Boolean.TRUE.equals(draft.sourceItemFramesTruncated)) return;
        for (Object entity : chunk.entityLists[sectionY]) {
            if (!(entity instanceof EntityItemFrame frame) || frame.isDead) continue;
            int x = MathHelper.floor_double(frame.posX), y = MathHelper.floor_double(frame.posY),
                z = MathHelper.floor_double(frame.posZ);
            if (regions.stream()
                .noneMatch(region -> region.contains(x, y, z)) || !seenFrames.add(frame.getUniqueID())) continue;
            if (seenFrames.size() > 1024) {
                draft.sourceItemFramesTruncated = true;
                break;
            }
            HologramPresets.FrameNote note = new HologramPresets.FrameNote();
            note.x = x - minX;
            note.y = y - minY;
            note.z = z - minZ;
            note.facing = frame.hangingDirection;
            note.rotation = frame.getRotation();
            ItemStack displayed = frame.getDisplayedItem();
            if (displayed != null && displayed.getItem() != null) {
                note.item = Item.itemRegistry.getNameForObject(displayed.getItem());
                note.damage = displayed.getItemDamage();
            }
            if (draft.sourceItemFrames == null) draft.sourceItemFrames = new ArrayList<>();
            draft.sourceItemFrames.add(note);
        }
    }

    private void readCell() throws IOException {
        int x = cursor.x, y = cursor.y, z = cursor.z;
        Block block = chunk.getBlock(x & 15, y, z & 15);
        if (block == Blocks.air) return;
        int meta = chunk.getBlockMetadata(x & 15, y, z & 15);
        TileEntity tile = world.getTileEntity(x, y, z);
        writer.append(draft, block, meta, tile, x - minX, y - minY, z - minZ);
    }

    private static boolean slice(int work, int budget, long deadline) {
        return work < budget && (work == 0 || System.nanoTime() < deadline);
    }

}
