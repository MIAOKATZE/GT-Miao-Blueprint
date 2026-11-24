// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Captures the player's loaded region on the client tick, then prepares a detached local draft. */
public final class HologramLocalAreaCapture implements AutoCloseable {

    private final Thread owner = Thread.currentThread();
    private final World world;
    private final int dimension;
    private final int minX, minY, minZ;
    private final ItemStack tool;
    private final HologramAreaBlockCursor cursor;
    private final HologramPresets.Preset draft = new HologramPresets.Preset();
    private final HologramAreaPreparation preparation = new HologramAreaPreparation();
    private final Map<String, HologramPresets.Cell> positions = new HashMap<>();
    private final Map<String, HologramPresets.Cell> palette = new LinkedHashMap<>();
    private final List<TileEntity> controllers = new ArrayList<>();
    private final Set<UUID> seenFrames = new HashSet<>();
    private final List<HologramAreaSelection.Region> regions;
    private int structureIndex, rowIndex;
    private long expandedNbt;
    private Chunk chunk;
    private HologramLocalControllerCapture structureCapture;
    private NBTTagList structureRows;
    private HologramPresets.Structure structure;
    private boolean closed;

    public HologramLocalAreaCapture(World world, int dimension, ItemStack tool, HologramAreaSelection selection)
        throws IOException {
        if (world == null || !world.isRemote) throw new IllegalArgumentException("Client world required");
        this.world = world;
        this.dimension = dimension;
        this.tool = tool == null ? null : tool.copy();
        int[] bounds = selection.bounds();
        minX = bounds[0];
        minY = bounds[1];
        minZ = bounds[2];
        if (!HologramWorldFlow.worldPosition(minX, minY, minZ, world.getHeight())
            || !HologramWorldFlow.worldPosition(bounds[3], bounds[4], bounds[5], world.getHeight()))
            throw new IOException("区域超出世界坐标边界");
        regions = selection.resolvedRegions();
        cursor = new HologramAreaBlockCursor(regions);
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
        if (!readStructures(budget, deadline)) return null;
        if (!palette.isEmpty()) {
            draft.palette.addAll(palette.values());
            palette.clear();
        }
        if (!preparation.captureLocalShells(draft, budget, deadline) || !preparation.start(draft)
            || !preparation.ready()) return null;
        HologramLocalBlueprintDocument result = HologramLocalBlueprintDocument
            .fromCapture(preparation.result(), dimension, minX, minY, minZ);
        close();
        return result;
    }

    public int progress() {
        if (!cursor.done()) return (int) (cursor.completed() * 100 / cursor.total());
        if (!controllers.isEmpty()) return structureIndex * 100 / controllers.size();
        HologramAreaPreparation.Progress current = preparation.progress();
        return current.total > 0 ? (int) (current.done * 100 / current.total) : 0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        preparation.cancel();
        positions.clear();
        controllers.clear();
        if (structureCapture != null) structureCapture.close();
        structureCapture = null;
        structureRows = null;
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
        HologramBlueprintLimits.require((long) draft.cells.size() + 1);
        int meta = chunk.getBlockMetadata(x & 15, y, z & 15);
        TileEntity tile = world.getTileEntity(x, y, z);
        NBTTagCompound data = tile == null ? null : HologramNativeCapture.capture(tile);
        ItemStack item = HologramArea.serverSample(block, meta, tile, data);
        HologramPresets.Cell cell = item == null ? new HologramPresets.Cell()
            : HologramPresets.cell(item, x - minX, y - minY, z - minZ, -1, false);
        cell.x = x - minX;
        cell.y = y - minY;
        cell.z = z - minZ;
        cell.block = Block.blockRegistry.getNameForObject(block);
        cell.metadata = meta;
        if (tile instanceof IGregTechTileEntity gt) cell.facing = HologramBlockIdentity.front(gt);
        if (item != null && item.hasTagCompound()) {
            HologramBlueprintNbt.Encoded encoded = HologramBlueprintNbt
                .encodeCapturedWithSize(block, item.getTagCompound());
            cell.itemNbt = encoded.value;
            expandedNbt += encoded.expandedBytes;
        }
        if (data != null) {
            HologramBlueprintNbt.Encoded encoded = HologramAeBlueprint.supports(block, data)
                ? HologramBlueprintNbt.encodeCanonicalWithSize(block, data)
                : HologramBlueprintNbt.encodeCapturedWithSize(block, data);
            cell.tileNbt = encoded.value;
            expandedNbt += encoded.expandedBytes;
        }
        if (expandedNbt > 64L * 1024 * 1024) throw new IOException("区域设备 NBT 超过 64MiB 上限，请拆分选区");
        if (HologramLocalControllerCapture.supports(tile)) {
            cell.controller = true;
            controllers.add(tile);
        }
        positions.put(position(x, y, z), cell);
        draft.cells.add(cell);
        if (item != null) palette
            .computeIfAbsent(cell.item + ":" + cell.damage, ignored -> HologramPresets.cell(item, 0, 0, 0, -1, false));
    }

    private boolean readStructures(int budget, long deadline) throws IOException {
        if (structureIndex >= controllers.size()) return true;
        TileEntity tile = controllers.get(structureIndex);
        if (structureCapture == null) {
            int side = tile instanceof IGregTechTileEntity gt && gt.getFrontFacing() != null ? gt.getFrontFacing()
                .ordinal() : 1;
            NBTTagCompound settings = HologramLocalControllerCapture.toolSettings(tool);
            structureCapture = new HologramLocalControllerCapture(tile, dimension, side, tool, settings, "");
            structure = controller(tile, settings);
        }
        if (structureRows == null) {
            try {
                NBTTagCompound snapshot = structureCapture.step(budget, Math.max(1, deadline - System.nanoTime()));
                if (snapshot == null) return false;
                structure.name = snapshot.getString("title");
                structureRows = snapshot.getTagList("cells", 10);
                rowIndex = 0;
            } catch (IOException unavailable) {
                // Incomplete declaration data must not delete real cells captured in the region.
                structureCapture.close();
                structureRows = new NBTTagList();
                rowIndex = 0;
            }
        }
        for (int work = 0; rowIndex < structureRows.tagCount() && slice(work, budget, deadline); work++)
            bind(structureRows.getCompoundTagAt(rowIndex++), structure.id);
        if (rowIndex < structureRows.tagCount()) return false;
        HologramPresets.Cell anchor = positions.get(structure.id);
        if (anchor != null) addMembership(anchor, structure.id);
        draft.structures.add(structure);
        structureIndex++;
        structureCapture = null;
        structureRows = null;
        structure = null;
        return structureIndex == controllers.size();
    }

    private HologramPresets.Structure controller(TileEntity tile, NBTTagCompound settings) {
        HologramPresets.Structure saved = new HologramPresets.Structure();
        saved.id = position(tile.xCoord, tile.yCoord, tile.zCoord);
        saved.x = tile.xCoord - minX;
        saved.y = tile.yCoord - minY;
        saved.z = tile.zCoord - minZ;
        Object controller = tile;
        if (tile instanceof IGregTechTileEntity gt) {
            saved.controllerId = gt.getMetaTileID();
            if (gt.getMetaTileEntity() != null) controller = gt.getMetaTileEntity();
        }
        saved.name = controller.getClass()
            .getSimpleName();
        saved.sourceClass = controller.getClass()
            .getName();
        saved.sourceMod = HologramSessionPolicy.sourceMod(controller.getClass());
        saved.main = Math.max(1, settings.getInteger("main"));
        saved.mainExplicit = settings.getBoolean("mainExplicit");
        saved.noHatches = settings.getBoolean("noHatches");
        NBTTagCompound channels = settings.getCompoundTag("channels");
        for (Object key : channels.func_150296_c())
            saved.channels.put(String.valueOf(key), channels.getInteger(String.valueOf(key)));
        return saved;
    }

    private void bind(NBTTagCompound row, String id) {
        HologramPresets.Cell cell = positions
            .get(position(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")));
        if (cell == null || cell.controller) return;
        addMembership(cell, id);
        if (cell.structureRole == null && !row.getString("role")
            .isEmpty()) cell.structureRole = row.getString("role");
        if (cell.structureFamily == null && !row.getString("family")
            .isEmpty()) cell.structureFamily = row.getString("family");
        if (cell.structureHint == null && !row.getString("prompt")
            .isEmpty()) cell.structureHint = row.getString("prompt");
        if (row.hasKey("candidateComplete", 1)) cell.structureCandidatesComplete = row.getBoolean("candidateComplete")
            && !Boolean.FALSE.equals(cell.structureCandidatesComplete);
        NBTTagList candidates = row.getTagList("candidates", 10);
        if (candidates.tagCount() == 0) return;
        List<ItemStack> stacks = new ArrayList<>(candidates.tagCount());
        for (int i = 0; i < candidates.tagCount(); i++) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(i));
            if (stack != null) stacks.add(stack);
        }
        List<HologramPresets.Target> targets = HologramBlueprintData.savedTargets(stacks);
        if (cell.allowedTargets == null) cell.allowedTargets = targets;
        else cell.allowedTargets.removeIf(
            old -> targets.stream()
                .noneMatch(next -> next.item.equals(old.item) && next.damage == old.damage));
    }

    private static void addMembership(HologramPresets.Cell cell, String id) {
        if (cell.structureIds == null) cell.structureIds = new ArrayList<>();
        if (!cell.structureIds.contains(id)) cell.structureIds.add(id);
        if (cell.structureId == null) cell.structureId = id;
    }

    private static boolean slice(int work, int budget, long deadline) {
        return work < budget && (work == 0 || System.nanoTime() < deadline);
    }

    private static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
