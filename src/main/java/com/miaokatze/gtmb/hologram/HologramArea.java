// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.BlockFrameBox;

/** Server-owned region selection and resumable read-only world import. */
final class HologramArea extends HologramAreaSelection {

    boolean selecting, imported, importing;
    int x, y, z, minX, minY, minZ, maxX, maxY, maxZ;
    HologramPresets.Preset blueprint;
    final Map<String, Structure> structures = new LinkedHashMap<>();
    final Map<String, java.util.LinkedHashSet<String>> members = new java.util.HashMap<>();
    final java.util.Set<String> edited = new java.util.HashSet<>();
    String importPhase = "blocks";
    HologramAreaPreparation preparation;
    HologramSession staging;
    Map<String, HologramSession> declarationSessions = new java.util.HashMap<>();
    HologramBlueprintProjection.Population population;
    HologramResourcePlanner.PlanTask planTask;
    private HologramLiteralAreaWriter writer;
    private HologramAreaBlockCursor blockCursor;
    private final java.util.Set<java.util.UUID> seenFrames = new java.util.HashSet<>();
    String active = "";

    public boolean contains(int xx, int yy, int zz) {
        if (regions.isEmpty()) return xx >= minX && xx <= maxX && yy >= minY && yy <= maxY && zz >= minZ && zz <= maxZ;
        boolean selected = false;
        for (Region region : regions) if (region.contains(xx, yy, zz)) selected = !"subtract".equals(region.mode);
        return selected;
    }

    static final class Structure {

        final String id;
        final int x, y, z;
        final MTEEnhancedMultiBlockBase<?> machine;
        boolean enabled = true;
        HologramCapture capture;

        Structure(int x, int y, int z, MTEEnhancedMultiBlockBase<?> machine) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.machine = machine;
            id = x + "," + y + "," + z;
        }
    }

    void begin(World world) throws IOException {
        cancelImportWork();
        int[] bounds = bounds();
        minX = bounds[0];
        minY = bounds[1];
        minZ = bounds[2];
        maxX = bounds[3];
        maxY = bounds[4];
        maxZ = bounds[5];
        if (minY < 0 || maxY >= world.getHeight()) throw new IOException("区域高度无效");
        if (minX < -30000000 || maxX >= 30000000 || minZ < -30000000 || maxZ >= 30000000)
            throw new IOException("区域超出世界坐标边界");
        blockCursor = new HologramAreaBlockCursor(resolvedRegions());
        x = minX;
        y = minY;
        z = minZ;
        blueprint = new HologramPresets.Preset();
        blueprint.cells = new HologramLiteralCaptureRows(true);
        blueprint.schema = 2;
        blueprint.name = "施工区域";
        blueprint.controllerType = "area";
        seenFrames.clear();
        structures.clear();
        members.clear();
        edited.clear();
        importPhase = "blocks";
        preparation = null;
        staging = null;
        declarationSessions = new java.util.HashMap<>();
        population = null;
        planTask = null;
        writer = new HologramLiteralAreaWriter();
        importing = true;
        imported = false;
    }

    /** No world mutation or material extraction occurs during import. */
    boolean tick(EntityPlayerMP player) throws IOException {
        if (imported) return true;
        World world = player.worldObj;
        long deadline = HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(HologramWorkBudget.SLICE_NANOS));
        for (int work = 0; !blockCursor.done() && work < 4096; work++) {
            if (work > 0 && System.nanoTime() >= deadline) break;
            if (!blockCursor.step((cx, sy, cz) -> emptySection(world, cx, sy, cz))) continue;
            x = blockCursor.x;
            y = blockCursor.y;
            z = blockCursor.z;
            if (contains(x, y, z)) {
                if (!world.blockExists(x, y, z)) throw new IOException("区域包含未加载区块，导入已停止");
                Block block = world.getBlock(x, y, z);
                int meta = world.getBlockMetadata(x, y, z);
                TileEntity tile = world.getTileEntity(x, y, z);
                if (block != Blocks.air)
                    writer.append(blueprint, block, meta, tile, x - minX, y - minY, z - minZ);
            }
        }
        if (!blockCursor.done()) return false;
        if (preparation == null) preparation = new HologramAreaPreparation();
        if ("blocks".equals(importPhase)) importPhase = "networks";
        if ("networks".equals(importPhase)) {
            if (preparation.captureNetworks(blueprint, Integer.MAX_VALUE, deadline)) {
                importPhase = "canonicalize";
                preparation.start(blueprint);
            }
            return false;
        }
        if ("canonicalize".equals(importPhase)) {
            if (!preparation.start(blueprint) || !preparation.ready()) return false;
            blueprint = preparation.result();
            imported = true;
            if (blueprint.sourceItemFrames != null && !blueprint.sourceItemFrames.isEmpty()) {
                net.minecraft.util.IChatComponent report = new net.minecraft.util.ChatComponentTranslation(
                    "gtmb.area.source_frames",
                    blueprint.sourceItemFrames.size()
                        + (Boolean.TRUE.equals(blueprint.sourceItemFramesTruncated) ? "+" : ""));
                int shown = 0;
                for (HologramPresets.FrameNote frame : blueprint.sourceItemFrames) {
                    if (frame.item == null || !(Item.itemRegistry.getObject(frame.item) instanceof Item item)) continue;
                    report.appendText(shown == 0 ? " " : ", ");
                    report.appendSibling(new ItemStack(item, 1, frame.damage).func_151000_E());
                    if (++shown == 3) break;
                }
                player.addChatMessage(report);
            }
            // The workflow takes ownership of the literal snapshot and prepares its projection.
            importPhase = "captured";
            return true;
        }
        return false;
    }

    void cancelImportWork() {
        if (importing) imported = false;
        importing = false;
        if (preparation != null) preparation.cancel();
        staging = null;
        if (!imported) declarationSessions.clear();
        population = null;
        planTask = null;
        blockCursor = null;
    }

    private boolean emptySection(World world, int chunkX, int sectionY, int chunkZ) throws IOException {
        if (!world.getChunkProvider()
            .chunkExists(chunkX, chunkZ)) throw new IOException("区域包含未加载区块，导入已停止");
        net.minecraft.world.chunk.Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
        if (sectionY >= 0 && sectionY < chunk.entityLists.length
            && !Boolean.TRUE.equals(blueprint.sourceItemFramesTruncated)) {
            for (Object entity : chunk.entityLists[sectionY]) {
                if (!(entity instanceof net.minecraft.entity.item.EntityItemFrame frame) || frame.isDead) continue;
                int fx = net.minecraft.util.MathHelper.floor_double(frame.posX),
                    fy = net.minecraft.util.MathHelper.floor_double(frame.posY),
                    fz = net.minecraft.util.MathHelper.floor_double(frame.posZ);
                if (!contains(fx, fy, fz) || !seenFrames.add(frame.getUniqueID())) continue;
                if (seenFrames.size() > 1024) {
                    blueprint.sourceItemFramesTruncated = true;
                    break;
                }
                HologramPresets.FrameNote note = new HologramPresets.FrameNote();
                note.x = fx - minX;
                note.y = fy - minY;
                note.z = fz - minZ;
                note.facing = frame.hangingDirection;
                note.rotation = frame.getRotation();
                ItemStack displayed = frame.getDisplayedItem();
                if (displayed != null && displayed.getItem() != null) {
                    note.item = Item.itemRegistry.getNameForObject(displayed.getItem());
                    note.damage = displayed.getItemDamage();
                }
                if (blueprint.sourceItemFrames == null) blueprint.sourceItemFrames = new ArrayList<>();
                blueprint.sourceItemFrames.add(note);
            }
        }
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        return sectionY >= sections.length || sections[sectionY] == null || sections[sectionY].isEmpty();
    }

    /** Current import phase; no source-world reads are needed for the progress display. */
    String importStage() {
        return importPhase;
    }

    long importDone() {
        if ("blocks".equals(importPhase)) {
            return blockCursor == null ? 0 : blockCursor.completed();
        }
        if ("networks".equals(importPhase)) return preparation == null ? 0 : preparation.networksDone();
        if ("canonicalize".equals(importPhase)) return preparation == null ? 0 : preparation.progress().done;
        if ("projection".equals(importPhase)) return population == null ? 0 : population.completed();
        if ("plan".equals(importPhase)) return planTask == null ? 0 : planTask.completed();
        return 0;
    }

    long importTotal() {
        if ("blocks".equals(importPhase)) return blockCursor == null ? 0 : blockCursor.total();
        if ("canonicalize".equals(importPhase)) return preparation == null ? 1 : preparation.progress().total;
        if ("projection".equals(importPhase)) return population == null ? 0 : population.total();
        if ("plan".equals(importPhase)) return planTask == null ? 0 : planTask.total();
        if ("networks".equals(importPhase) && blueprint != null && blueprint.cells instanceof HologramLiteralCaptureRows packed)
            return packed.sensitive().size();
        return blueprint == null ? 0 : blueprint.cells.size();
    }

    String importUnit() {
        if ("projection".equals(importPhase)
            || "plan".equals(importPhase)
            || "canonicalize".equals(importPhase)) return "entries";
        return "cells";
    }

    HologramAreaPreparation.Progress progressSnapshot() {
        if ("canonicalize".equals(importPhase) && preparation != null) return preparation.progress();
        return new HologramAreaPreparation.Progress(importDetail(), importDone(), importTotal());
    }

    String importDetail() {
        if ("canonicalize".equals(importPhase) && preparation != null) return preparation.progress().phase;
        if ("plan".equals(importPhase) && planTask != null) return planTask.phase();
        return "";
    }

    /** Progress belongs to the current phase, with no estimated weights between unrelated operations. */
    int importProgress() {
        HologramAreaPreparation.Progress current = progressSnapshot();
        return current.total <= 0 ? 0 : (int) Math.max(0, Math.min(importing ? 99 : 100, 100D * current.done / current.total));
    }

    /** Inventory identity is read without client-only pick hooks or harvesting callbacks. */
    static ItemStack serverSample(Block block, int metadata, TileEntity tile) {
        boolean ae = HologramAeBlueprint.supports(block) || (block != null && block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart"));
        try {
            return serverSample(
                block,
                metadata,
                tile,
                !ae || tile == null ? null : HologramNativeCapture.capture(tile));
        } catch (IOException unsupported) {
            return null;
        }
    }

    static ItemStack serverSample(Block block, int metadata, TileEntity tile, NBTTagCompound data) {
        if (tile instanceof IGregTechTileEntity)
            return new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) tile).getMetaTileID());
        if (block instanceof BlockFrameBox) return ((BlockFrameBox) block).getStackForm(1, metadata);
        if (HologramAeBlueprint.supports(block, data)) {
            try {
                return HologramAeBlueprint.sampleIdentity(block, metadata, data);
            } catch (IOException unsupported) {
                return null;
            }
        }
        if (HologramNativePlacement.supported(block, data)) {
            try {
                return HologramNativePlacement.sample(block, metadata, tile);
            } catch (IOException unsupported) {
                // Preserve its real block and complete tile NBT as an unsupported draft cell.
                return null;
            }
        }
        try {
            return HologramBlockIdentity.materialStack(block, HologramPresets.materialDamage(block, metadata));
        } catch (IOException unsupported) {
            // Retain the world cell as unsupported instead of publishing a different payable item variant.
            return null;
        }
    }

    String member(int xx, int yy, int zz) {
        List<String> ids = memberships(xx, yy, zz);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    void addMember(String position, String id) {
        // Controllers retain their own structure; shared declared walls may belong to several.
        if (structures.containsKey(position) && !position.equals(id)) return;
        members.computeIfAbsent(position, ignored -> new java.util.LinkedHashSet<>())
            .add(id);
    }

    List<String> memberships(int xx, int yy, int zz) {
        java.util.Set<String> ids = members.get(xx + "," + yy + "," + zz);
        return ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }

    NBTTagList labels() {
        NBTTagList list = new NBTTagList();
        for (Structure structure : structures.values()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", structure.id);
            row.setInteger("x", structure.x);
            row.setInteger("y", structure.y);
            row.setInteger("z", structure.z);
            row.setString("name", structure.machine.getLocalName());
            row.setBoolean("enabled", structure.enabled);
            row.setBoolean("formed", structure.machine.mMachine);
            row.setBoolean("declared", structure.capture != null && !structure.capture.incomplete);
            list.appendTag(row);
        }
        return list;
    }

}
