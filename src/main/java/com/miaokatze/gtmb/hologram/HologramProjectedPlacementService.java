// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Turns an explicit material-review request into the existing server-owned placement transaction. */
final class HologramProjectedPlacementService {

    private static final int MAX_PENDING = 8;
    private static final int RECENT_LIMIT = 1024;
    private static final Map<UUID, Job> PENDING = new LinkedHashMap<>();
    private static final Set<String> RECENT = new LinkedHashSet<>();

    private HologramProjectedPlacementService() {}

    static void receive(EntityPlayerMP player, NBTTagCompound tag) {
        String id = tag.getString("placementRequestId");
        try {
            HologramPlacementRequest.Header header = HologramPlacementRequest.header(tag);
            if (RECENT.contains(id)) return;
            UUID owner = player.getUniqueID();
            Job pending = PENDING.get(owner);
            if (pending != null && pending.header.id()
                .equals(id)) return;
            if (pending != null || PENDING.size() >= MAX_PENDING) throw new IOException("已有施工核对任务，请稍后重试");
            Job job = new Job(player, header, tag);
            job.requireCurrent();
            if (!HologramAreaPreparation.execute(job.decoding)) throw new IOException("施工准备队列繁忙，请稍后重试");
            PENDING.put(owner, job);
        } catch (IOException invalid) {
            if (id.length() == 36) reply(player, id, false, invalid.getMessage());
        }
    }

    static void cancel(EntityPlayerMP player, String id) {
        Job job = PENDING.get(player.getUniqueID());
        if (job != null && job.player == player
            && job.header.id()
                .equals(id)) {
            forget(player);
            return;
        }
        HologramSession published = HologramWorkspace.find(player.getUniqueID());
        if (published != null && published.player == player
            && published.job == 0
            && published.virtualPreset != null
            && id.length() == 36
            && ("operation_" + id).equals(published.virtualPreset.id)) {
            NBTTagCompound cancel = new NBTTagCompound();
            cancel.setString("session", published.id);
            cancel.setLong("planRevision", published.planRevision);
            HologramWorldPlacementWorkflow.cancelWorldPlacement(player, cancel);
        }
    }

    static void forget(EntityPlayerMP player) {
        Job job = PENDING.remove(player.getUniqueID());
        if (job != null) HologramAreaPreparation.cancel(job.decoding);
    }

    static void clear() {
        PENDING.values()
            .forEach(job -> HologramAreaPreparation.cancel(job.decoding));
        PENDING.clear();
        RECENT.clear();
    }

    static void tick() {
        long deadline = HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(2_000_000L));
        Iterator<Job> pending = PENDING.values()
            .iterator();
        while (pending.hasNext() && System.nanoTime() < deadline) {
            Job job = pending.next();
            try {
                if (!job.step(deadline)) continue;
                remember(job.header.id());
                pending.remove();
            } catch (IOException | ExecutionException failure) {
                HologramAreaPreparation.cancel(job.decoding);
                Throwable reason = failure instanceof ExecutionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
                reply(job.player, job.header.id(), false, reason.getMessage());
                pending.remove();
            } catch (RuntimeException | LinkageError failure) {
                HologramAreaPreparation.cancel(job.decoding);
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Projected placement preparation failed", failure);
                reply(job.player, job.header.id(), false, "施工准备失败，未进行扣料或建造");
                pending.remove();
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                return;
            }
        }
    }

    private static void remember(String id) {
        if (RECENT.size() >= RECENT_LIMIT) {
            Iterator<String> entries = RECENT.iterator();
            entries.next();
            entries.remove();
        }
        RECENT.add(id);
    }

    static void reply(EntityPlayerMP player, String id, boolean accepted, String reason) {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("placementRequestId", id);
        receipt.setBoolean("placementRequestAccepted", accepted);
        if (reason != null) receipt.setString("errorMessage", reason);
        HologramNetwork.sendState(player, receipt);
    }

    private static final class Job {

        private final EntityPlayerMP player;
        private final World world;
        private final int toolSlot;
        private final ItemStack tool;
        private final HologramSession previous;
        private final long previousRevision, previousWorkspace;
        private final HologramPlacementRequest.Header header;
        private final long started = System.nanoTime();
        private final FutureTask<HologramPlacementRequest.Decoded> decoding;
        private final Set<Long> positions = new HashSet<>();
        private HologramPlacementRequest.Decoded decoded;
        private HologramSession session;
        private HologramBlueprintProjection.Population population;
        private int variant, operation;

        private Job(EntityPlayerMP player, HologramPlacementRequest.Header header, NBTTagCompound tag) {
            this.player = player;
            this.header = header;
            world = player.worldObj;
            toolSlot = player.inventory.currentItem;
            tool = player.getHeldItem();
            previous = HologramWorkspace.find(player.getUniqueID());
            previousRevision = previous == null ? -1 : previous.planRevision;
            previousWorkspace = previous == null ? -1 : previous.editorWorkspaceEpoch;
            int height = world.getHeight();
            decoding = new FutureTask<>(() -> HologramPlacementRequest.decode(header, tag, height));
        }

        private void requireCurrent() throws IOException {
            if (System.nanoTime() - started > 90_000_000_000L) throw new IOException("施工准备超时，请缩小施工选区后重试");
            if (!HologramNetwork.progressConnected(player) || player.worldObj != world
                || player.inventory.currentItem != toolSlot
                || player.getHeldItem() != tool
                || !ItemNekoHologramProjector.isProjector(tool)
                || !player.capabilities.allowEdit
                || HologramWorkspace.find(player.getUniqueID()) != previous
                || !HologramWorldFlow.worldPosition(header.x(), header.y(), header.z(), world.getHeight())
                || !HologramSessionPolicy.previewReach(player, header.x(), header.y(), header.z())
                || !world.blockExists(header.x(), header.y(), header.z())) throw new IOException("施工工具、位置或会话已变化，请重新确认");
            HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
            if (HologramDemolition.active(player) || HologramAeCraftingOrders.active(player)
                || area != null && (area.selecting || area.importing)
                || previous != null
                    && (previous.planRevision != previousRevision || previous.editorWorkspaceEpoch != previousWorkspace
                        || previous.job == 1
                        || previous.job == 2
                        || previous.capturePending
                        || HologramAuthoringWorkflow.blueprintHasUnsavedChanges(previous)))
                throw new IOException("请先处理当前施工、采集或未保存的服务器草稿");
        }

        private boolean step(long deadline) throws IOException, ExecutionException, InterruptedException {
            requireCurrent();
            if (decoded == null) {
                if (!decoding.isDone()) return false;
                decoded = decoding.get();
            }
            while (variant < decoded.variants()
                .size() && System.nanoTime() < deadline) validate(
                    decoded.variants()
                        .get(variant++));
            if (variant < decoded.variants()
                .size()) return false;
            if (session == null) {
                session = new HologramSession(player, header.x(), header.y(), header.z(), false, 1);
                session.virtual = true;
                session.virtualPlacement = true;
                session.virtualLocked = true;
                session.virtualPreset = new HologramPresets.Preset();
                session.virtualPreset.schema = 2;
                session.virtualPreset.id = "operation_" + header.id();
                session.virtualPreset.name = "当前施工";
                session.virtualPreset.retainNbt = false;
                session.virtualPreset.retainCovers = false;
            }
            while (operation < decoded.size() && System.nanoTime() < deadline) {
                HologramPresets.Cell cell = decoded.cell(operation++);
                long x = header.x() + cell.x, y = header.y() + cell.y, z = header.z() + cell.z;
                long position = ((x & 0x3ffffffL) << 38) | ((z & 0x3ffffffL) << 12) | y;
                if (!positions.add(position)) throw new IOException("施工指令包含重复目标位置");
                session.virtualPreset.cells.add(cell);
            }
            if (operation < decoded.size()) return false;
            if (population == null) population = new HologramBlueprintProjection.Population(session);
            if (System.nanoTime() >= deadline || !population.step(2048, deadline)) return false;
            if (session.capture.incomplete) throw new IOException("当前服务器无法执行选中的方块放置指令");
            requireCurrent();
            HologramWorkspace.activate(player.getUniqueID(), session);
            if (!HologramProjectorWorkflow.openPlacementReview(session, player))
                throw new IOException("材料核对未能启动，请重新确认");
            reply(player, header.id(), true, null);
            return true;
        }

        private static void validate(HologramPlacementRequest.Variant variant) throws IOException {
            Object registered = net.minecraft.item.Item.itemRegistry.getObject(variant.item());
            Object target = Block.blockRegistry.getObject(variant.block());
            if (!(registered instanceof ItemBlock item) || !(target instanceof Block block)
                || block == Blocks.air
                || Block.getBlockFromItem(item) != block) throw new IOException("服务器无法识别施工方块或耗材");
            ItemStack stack = new ItemStack(item, 1, variant.damage());
            if (item instanceof ItemMachines) {
                if (block == GregTechAPI.sBlockMachines && ItemMachines.getMetaTileEntity(stack) != null
                    && HologramCellPolicy.virtualFrontAllowed(stack, variant.facing())) return;
                throw new IOException("GT 设备或施工朝向无效");
            }
            int itemMetadata = item.getMetadata(variant.damage());
            if (variant.facing() != -1 || itemMetadata < 0
                || itemMetadata > 15
                || block.hasTileEntity(variant.metadata())
                || block.hasTileEntity(itemMetadata)) throw new IOException("该设备需要原生放置流程");
            HologramPresets.validateMaterialVariant(block, variant.metadata(), stack);
        }
    }
}
