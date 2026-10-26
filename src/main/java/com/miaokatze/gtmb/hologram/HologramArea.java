package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
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
    final java.util.Set<String> paletteKeys = new java.util.HashSet<>();
    final java.util.Set<String> edited = new java.util.HashSet<>();
    final List<Structure> discovery = new ArrayList<>();
    int discoveryIndex;
    private java.util.Iterator<Map.Entry<String, HologramCapture.Cell>> declarationMembers;
    private int declarationMemberDone, declarationMemberTotal;
    String importPhase = "blocks";
    HologramAreaPreparation preparation;
    HologramSession staging;
    Map<String, HologramSession> declarationSessions = new java.util.HashMap<>();
    int structureIndex, membershipIndex;
    HologramBlueprintProjection.Population population;
    HologramResourcePlanner.PlanTask planTask;
    long expandedNbt;
    private HologramAreaBlockCursor blockCursor;
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
        HologramAreaSignalInference signalInference;

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
        blueprint.schema = 2;
        blueprint.name = "施工区域";
        blueprint.controllerType = "area";
        structures.clear();
        members.clear();
        discovery.clear();
        paletteKeys.clear();
        edited.clear();
        discoveryIndex = 0;
        declarationMembers = null;
        importPhase = "blocks";
        preparation = null;
        staging = null;
        declarationSessions = new java.util.HashMap<>();
        structureIndex = membershipIndex = 0;
        population = null;
        planTask = null;
        expandedNbt = 0;
        importing = true;
        imported = false;
    }

    /** No world mutation or material extraction occurs during import. */
    boolean tick(EntityPlayerMP player, ItemStack trigger) throws IOException {
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
                if (block != Blocks.air) {
                    HologramBlueprintLimits.require((long) blueprint.cells.size() + 1);
                    NBTTagCompound data = tile == null ? null : HologramNativeCapture.capture(tile);
                    ItemStack sample = serverSample(block, meta, tile, data);
                    HologramPresets.Cell cell = sample == null ? new HologramPresets.Cell()
                        : HologramPresets.cell(sample, x - minX, y - minY, z - minZ, -1, false);
                    cell.x = x - minX;
                    cell.y = y - minY;
                    cell.z = z - minZ;
                    cell.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
                    cell.metadata = meta;
                    if (sample != null && sample.hasTagCompound())
                        cell.itemNbt = HologramBlueprintNbt.encodeCaptured(block, sample.getTagCompound());
                    if (tile != null) {
                        cell.tileNbt = HologramAeBlueprint.supports(block, data)
                            ? HologramBlueprintNbt.encodeCanonical(block, data)
                            : HologramBlueprintNbt.encodeCaptured(block, data);
                    }
                    expandedNbt += HologramBlueprintNbt.expandedBytes(cell.tileNbt)
                        + HologramBlueprintNbt.expandedBytes(cell.itemNbt);
                    if (expandedNbt > 64L * 1024 * 1024) throw new IOException("区域设备 NBT 超过 64MiB 上限，请拆分选区");
                    if (tile instanceof IGregTechTileEntity) {
                        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
                        cell.facing = HologramBlockIdentity.front(gt);
                        if (gt.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase && HologramStructureDiscovery
                            .captureSupported((MTEEnhancedMultiBlockBase<?>) gt.getMetaTileEntity())) {
                            cell.controller = true;
                            Structure structure = new Structure(
                                x,
                                y,
                                z,
                                (MTEEnhancedMultiBlockBase<?>) gt.getMetaTileEntity());
                            structures.put(structure.id, structure);
                            addMember(structure.id, structure.id);
                            discovery.add(structure);
                        }
                    }
                    blueprint.cells.add(cell);
                    if (sample != null
                        && paletteKeys.add(String.valueOf(sample.getItem()) + ":" + sample.getItemDamage()))
                        blueprint.palette.add(HologramPresets.cell(sample, 0, 0, 0, -1, false));
                }
            }
        }
        if (!blockCursor.done()) return false;
        if (discoveryIndex < discovery.size()) {
            Structure structure = discovery.get(discoveryIndex);
            if (declarationMembers != null) {
                importPhase = "declaration_memberships";
                for (int work = 0; declarationMembers.hasNext(); work++) {
                    if (work > 0 && System.nanoTime() >= deadline) break;
                    Map.Entry<String, HologramCapture.Cell> entry = declarationMembers.next();
                    HologramCapture.Cell cell = entry.getValue();
                    if (contains(cell.x, cell.y, cell.z)) addMember(entry.getKey(), structure.id);
                    declarationMemberDone++;
                }
                if (!declarationMembers.hasNext()) {
                    declarationMembers = null;
                    discoveryIndex++;
                }
                return false;
            }
            importPhase = "declarations";
            if (world.getTileEntity(structure.x, structure.y, structure.z) != structure.machine.getBaseMetaTileEntity())
                throw new IOException("控制器在导入期间变化，请重新选择区域");
            if (structure.signalInference == null)
                structure.signalInference = new HologramAreaSignalInference(structure.machine, trigger);
            boolean inferred = structure.signalInference.step(deadline);
            structure.capture = structure.signalInference.capture();
            if (inferred) {
                if (!structure.capture.incomplete) {
                    declarationMembers = structure.capture.positions.entrySet()
                        .iterator();
                    declarationMemberDone = 0;
                    declarationMemberTotal = structure.capture.positions.size();
                } else discoveryIndex++;
            }
            return false;
        }
        if (preparation == null) preparation = new HologramAreaPreparation();
        if ("blocks".equals(importPhase) || "declarations".equals(importPhase)
            || "declaration_memberships".equals(importPhase)) importPhase = "networks";
        if ("networks".equals(importPhase)) {
            if (preparation.captureNetworks(blueprint, Integer.MAX_VALUE, deadline)) {
                importPhase = "canonicalize";
                preparation.start(blueprint);
            }
            return false;
        }
        if ("canonicalize".equals(importPhase)) {
            if (!preparation.start(blueprint)) return false;
            preparation.advance(Integer.MAX_VALUE, deadline);
            if (!preparation.ready()) return false;
            blueprint = preparation.result();
            imported = true;
            // Workflow continues with detached memberships, projection and plan; publication is atomic.
            importPhase = "memberships";
            return true;
        }
        return false;
    }

    void cancelImportWork() {
        if (importing) imported = false;
        importing = false;
        if (discoveryIndex < discovery.size()) {
            Structure current = discovery.get(discoveryIndex);
            if (current.signalInference != null) current.signalInference.cancel();
            HologramCapture capture = current.capture;
            if (capture != null) capture.cancelPending();
        }
        if (preparation != null) preparation.cancel();
        staging = null;
        if (!imported) declarationSessions.clear();
        population = null;
        planTask = null;
        blockCursor = null;
    }

    private static boolean emptySection(World world, int chunkX, int sectionY, int chunkZ) throws IOException {
        if (!world.getChunkProvider()
            .chunkExists(chunkX, chunkZ)) throw new IOException("区域包含未加载区块，导入已停止");
        ExtendedBlockStorage[] sections = world.getChunkFromChunkCoords(chunkX, chunkZ)
            .getBlockStorageArray();
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
        if ("declarations".equals(importPhase)) return discoveryIndex;
        if ("declaration_memberships".equals(importPhase)) return declarationMemberDone;
        if ("networks".equals(importPhase)) return preparation == null ? 0 : preparation.networksDone();
        if ("canonicalize".equals(importPhase)) return preparation == null ? 0 : preparation.progress().done;
        if ("memberships".equals(importPhase)) return (long) structureIndex + membershipIndex;
        if ("projection".equals(importPhase)) return population == null ? 0 : population.completed();
        if ("plan".equals(importPhase)) return planTask == null ? 0 : planTask.completed();
        return 0;
    }

    long importTotal() {
        if ("blocks".equals(importPhase)) return blockCursor == null ? 0 : blockCursor.total();
        if ("declarations".equals(importPhase)) return discovery.size();
        if ("declaration_memberships".equals(importPhase)) return declarationMemberTotal;
        if ("canonicalize".equals(importPhase)) return preparation == null ? 1 : preparation.progress().total;
        if ("projection".equals(importPhase)) return population == null ? 0 : population.total();
        if ("plan".equals(importPhase)) return planTask == null ? 0 : planTask.total();
        if ("memberships".equals(importPhase)) return blueprint.cells.size() + (long) discovery.size();
        return blueprint == null ? 0 : blueprint.cells.size();
    }

    String importUnit() {
        if ("declarations".equals(importPhase)) return "structures";
        if ("memberships".equals(importPhase) || "projection".equals(importPhase)
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
        return current.total <= 0 ? 0 : (int) Math.max(0, Math.min(100, 100D * current.done / current.total));
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

    private static ItemStack serverSample(Block block, int metadata, TileEntity tile, NBTTagCompound data) {
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
        if (HologramNativePlacement.supported(block)) {
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
