package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Confirmed, server-owned area demolition. Every selected upper layer finishes before the next lower layer. */
public final class HologramDemolition {

    public static final DropListener DROPS = new DropListener();
    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
    private static final Map<UUID, Delivery> DELIVERIES = new LinkedHashMap<>();
    private static final ThreadLocal<DropScope> CAPTURE = new ThreadLocal<>();
    private static int nextJob;
    private static int nextDelivery;

    private HologramDemolition() {}

    /** Called on the server thread only, after the area's confirmation has been authenticated. */
    public static void start(EntityPlayerMP player, HologramAreaSelection selection) throws IOException {
        if (!connected(player) || player.isDead
            || !(player.worldObj instanceof WorldServer)
            || !player.capabilities.allowEdit
            || !ItemNekoHologramProjector.isProjector(player.getHeldItem())) throw new IOException("请持有猫猫蓝图工具后重试拆除");
        if (active(player)) throw new IOException("拆除正在进行，请先等待完成或取消");
        if (JOBS.size() >= 64) throw new IOException("拆除任务已满，请稍后重试");
        if (HologramAeCraftingOrders.active(player) || busy(HologramWorkspace.find(player.getUniqueID()))
            || busy(HologramWorkspace.AREA_EDITORS.get(player.getUniqueID())))
            throw new IOException("请先完成或取消当前施工、导入或保存任务");
        Map<String, HologramSession> structures = HologramWorkspace.AREA_STRUCTURES.get(player.getUniqueID());
        if (structures != null) for (HologramSession session : structures.values())
            if (busy(session)) throw new IOException("请先完成或取消当前施工、导入或保存任务");
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (area != null && area.importing) throw new IOException("请先完成或取消区域导入");
        selection.bounds();
        Cursor cursor = new Cursor(selection.resolvedRegions(), player.worldObj.getHeight());
        JOBS.put(player.getUniqueID(), new Job(player, cursor));
    }

    private static boolean busy(HologramSession session) {
        return session != null && !session.cancelled
            && (session.job == 1 || session.job == 2
                || session.capturePending
                || !session.blueprintSaveReceipt.isEmpty());
    }

    public static boolean active(EntityPlayerMP player) {
        Job job = JOBS.get(player.getUniqueID());
        return job != null && job.player == player;
    }

    public static void cancel(EntityPlayerMP player) {
        Job job = JOBS.get(player.getUniqueID());
        if (job != null && job.player == player) finish(job, "拆除已取消");
    }

    /**
     * Server stop/runtime reset never resumes work or creates offline drops. Committed receipts belong to the player.
     */
    public static void clear() {
        JOBS.clear();
        DELIVERIES.clear();
        nextJob = nextDelivery = 0;
    }

    public static void tick() {
        deliverPending();
        if (JOBS.isEmpty()) return;
        List<Job> round = new ArrayList<>(JOBS.values());
        int start = Math.floorMod(nextJob, round.size()), visited = 0;
        long deadline = System.nanoTime() + 5_000_000L;
        while (visited < round.size() && (visited == 0 || System.nanoTime() < deadline)) {
            Job job = round.get((start + visited++) % round.size());
            if (JOBS.get(job.player.getUniqueID()) == job)
                advance(job, Math.min(deadline, System.nanoTime() + 1_000_000L));
        }
        nextJob = (start + visited) % round.size();
    }

    private static void advance(Job job, long deadline) {
        String invalid = job.invalidReason();
        if (!invalid.isEmpty()) {
            finish(job, "拆除已停止：" + invalid);
            return;
        }
        String stopped = null;
        try {
            for (int work = 0; !job.cursor.done() && work < 256; work++) {
                if (work > 0 && System.nanoTime() >= deadline) break;
                invalid = job.invalidReason();
                if (!invalid.isEmpty()) {
                    stopped = "拆除已停止：" + invalid;
                    break;
                }
                if (!job.cursor.step((cx, sy, cz) -> emptySection(job.world, cx, sy, cz))) continue;
                int x = job.cursor.x, y = job.cursor.y, z = job.cursor.z;
                if (!job.world.blockExists(x, y, z)) throw new IOException("选区区块已卸载，其余方块保留");
                if (job.world.isAirBlock(x, y, z)) continue;
                Removal result = removeBlock(job.player, job.world, x, y, z);
                // Once native removal has run, actual drops are committed. Delivery can never restore the source.
                job.materials.recoverWorldAtPlayer(result.drops);
                if (result.removed) job.removed++;
                else job.protectedCount++;
                if (result.failure != null) {
                    GTMiaoBlueprint.LOG
                        .warn("Native area demolition failed at " + x + "," + y + "," + z, result.failure);
                    stopped = "拆除已停止：原生拆除回调失败，实际已生成物品保留，其余方块未拆";
                    break;
                }
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            GTMiaoBlueprint.LOG.warn("Area demolition stopped", failure);
            stopped = "拆除已停止：" + (failure.getMessage() == null ? "无法安全拆除，其余方块保留" : failure.getMessage());
        }
        try {
            if (deliveryValid(job.player, job.world)) job.materials.flushRecovery();
        } catch (RuntimeException | LinkageError failure) {
            GTMiaoBlueprint.LOG.warn("Area demolition delivery retained in player recovery", failure);
            stopped = "拆除已停止：回收交付失败，未交付物品保留在玩家回收记录中";
        }
        if (stopped != null) finish(job, stopped);
        else if (job.cursor.done()) finish(job, "拆除完成");
    }

    private static boolean emptySection(World world, int chunkX, int sectionY, int chunkZ) throws IOException {
        if (!world.getChunkProvider()
            .chunkExists(chunkX, chunkZ)) throw new IOException("选区包含未加载区块，其余方块保留");
        ExtendedBlockStorage[] sections = world.getChunkFromChunkCoords(chunkX, chunkZ)
            .getBlockStorageArray();
        return sectionY >= sections.length || sections[sectionY] == null || sections[sectionY].isEmpty();
    }

    private static void finish(Job job, String reason) {
        JOBS.remove(job.player.getUniqueID(), job);
        boolean pending = job.materials.pendingWorldRecovery();
        if (pending && connected(job.player) && !job.player.isDead)
            DELIVERIES.put(job.player.getUniqueID(), new Delivery(job.player, job.world, job.materials));
        if (connected(job.player)) job.player.addChatMessage(
            new ChatComponentText(
                reason + "：已拆除 "
                    + job.removed
                    + " 格；跳过 "
                    + job.protectedCount
                    + " 格受保护、不可破坏或拒绝拆除的方块。"
                    + (pending ? "仍有回收物待交付，将继续尝试 AE、背包和脚下掉落。" : "回收物已依次交付 AE、背包或玩家脚下。")));
    }

    /** Reattach only persisted demolition receipts after login/respawn; no world work is restored. */
    public static void resumeRecovery(EntityPlayerMP player) {
        if (!connected(player) || player.isDead || !HologramMaterials.Context.pendingWorldRecovery(player)) return;
        try {
            HologramMaterials.Context materials = recoveryContext(player, player.inventory.currentItem);
            DELIVERIES.put(player.getUniqueID(), new Delivery(player, player.worldObj, materials));
        } catch (RuntimeException | LinkageError failure) {
            GTMiaoBlueprint.LOG.warn("Cannot reattach area demolition recovery; receipts retained", failure);
        }
    }

    private static HologramMaterials.Context recoveryContext(EntityPlayerMP player, int slot) {
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = false;
        settings.recoveryDestination = 1;
        return new HologramMaterials.Context(player, settings, slot);
    }

    private static boolean deliveryValid(EntityPlayerMP player, World world) {
        return connected(player) && !player.isDead && player.worldObj == world && world.playerEntities.contains(player);
    }

    private static void deliverPending() {
        if (DELIVERIES.isEmpty()) return;
        List<Delivery> round = new ArrayList<>(DELIVERIES.values());
        int start = Math.floorMod(nextDelivery, round.size()), visited = 0;
        long deadline = System.nanoTime() + 5_000_000L;
        while (visited < round.size() && (visited == 0 || System.nanoTime() < deadline)) {
            Delivery delivery = round.get((start + visited++) % round.size());
            EntityPlayerMP player = delivery.player;
            if (!connected(player) || player.isDead || !player.worldObj.playerEntities.contains(player)) {
                DELIVERIES.remove(player.getUniqueID(), delivery);
                continue;
            }
            if (player.worldObj != delivery.world) {
                DELIVERIES.remove(player.getUniqueID(), delivery);
                resumeRecovery(player);
                continue;
            }
            try {
                delivery.materials.flushRecovery();
            } catch (RuntimeException | LinkageError failure) {
                DELIVERIES.remove(player.getUniqueID(), delivery);
                GTMiaoBlueprint.LOG.warn("Area demolition recovery delivery failed; receipts retained", failure);
                player.addChatMessage(new ChatComponentText("拆除回收交付失败，剩余物品已保留在玩家回收记录中；重新登录后将继续尝试。"));
            }
            if (!delivery.materials.pendingWorldRecovery()) DELIVERIES.remove(player.getUniqueID(), delivery);
        }
        nextDelivery = (start + visited) % round.size();
    }

    private static final class Delivery {

        final EntityPlayerMP player;
        final World world;
        final HologramMaterials.Context materials;

        Delivery(EntityPlayerMP player, World world, HologramMaterials.Context materials) {
            this.player = player;
            this.world = world;
            this.materials = materials;
        }
    }

    private static boolean connected(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager != null
            && player.playerNetServerHandler.netManager.isChannelOpen();
    }

    static final class Removal {

        final boolean removed;
        final List<ItemStack> drops;
        final Throwable failure;

        Removal(boolean removed, List<ItemStack> drops, Throwable failure) {
            this.removed = removed;
            this.drops = drops;
            this.failure = failure;
        }
    }

    /** No precomputed getDrops: native TE breakBlock and harvestBlock own the actual drops. */
    static Removal removeBlock(EntityPlayerMP player, World world, int x, int y, int z) throws IOException {
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, y, z);
        Block block = world.getBlock(x, y, z);
        int metadata = world.getBlockMetadata(x, y, z);
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!player.capabilities.allowEdit || block.getBlockHardness(world, x, y, z) < 0
            || !world.canMineBlock(player, x, y, z)
            || !player.canPlayerEdit(x, y, z, 1, player.getHeldItem()))
            return new Removal(false, Collections.emptyList(), null);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(x, y, z, world, block, metadata, player);
        if (MinecraftForge.EVENT_BUS.post(event)
            || !HologramWorldTransactions.sameWorldCell(world, cell, block, metadata, tile))
            return new Removal(false, Collections.emptyList(), null);

        List<ItemStack> sealed = HologramRecovery.supported(tile) ? HologramRecovery.drops(world, x, y, z) : null;
        NBTTagCompound saved = sealed == null ? null
            : (NBTTagCompound) sealed.get(0)
                .getTagCompound()
                .getCompoundTag(HologramRecovery.TAG)
                .getCompoundTag("tile")
                .copy();
        try (DropScope scope = new DropScope(world)) {
            if (sealed != null) {
                Throwable failure = null;
                try {
                    if (HologramRecovery.removeWithoutDrops(world, x, y, z)) {
                        scope.items.addAll(sealed);
                        return new Removal(true, scope.items, null);
                    }
                } catch (RuntimeException | LinkageError removalFailure) {
                    failure = removalFailure;
                }
                try {
                    HologramRecovery.restore(world, x, y, z, block, metadata, saved);
                } catch (RuntimeException | LinkageError restoreFailure) {
                    if (failure == null) failure = restoreFailure;
                    else if (failure != restoreFailure) failure.addSuppressed(restoreFailure);
                }
                // An uncertain failed restore never authorizes generating the sealed machine as a second copy.
                return new Removal(false, scope.items, failure);
            }
            try {
                block.onBlockHarvested(world, x, y, z, metadata, player);
                if (!HologramWorldTransactions.sameWorldCell(world, cell, block, metadata, tile))
                    return new Removal(world.isAirBlock(x, y, z), scope.items, null);
                if (!block.removedByPlayer(world, player, x, y, z, true)) return new Removal(false, scope.items, null);
                block.onBlockDestroyedByPlayer(world, x, y, z, metadata);
                block.harvestBlock(world, player, x, y, z, metadata);
                return new Removal(
                    !HologramWorldTransactions.sameWorldCell(world, cell, block, metadata, tile),
                    scope.items,
                    null);
            } catch (RuntimeException | LinkageError failure) {
                // Unknown native callbacks may already change inventories, networks or neighbors. Keep that real
                // outcome and the captured drops; restoring a snapshot here could create a second copy.
                return new Removal(
                    !HologramWorldTransactions.sameWorldCell(world, cell, block, metadata, tile),
                    scope.items,
                    failure);
            }
        }
    }

    /** Registered once on Forge's bus. Outside the exact synchronous removal scope it is a no-op. */
    public static final class DropListener {

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void spawned(EntityJoinWorldEvent event) {
            DropScope scope = CAPTURE.get();
            if (scope == null || event.world != scope.world
                || event.isCanceled()
                || !(event.entity instanceof EntityItem)) return;
            EntityItem item = (EntityItem) event.entity;
            ItemStack stack = item.getEntityItem();
            if (stack == null || stack.stackSize <= 0) return;
            scope.items.add(stack.copy());
            event.setCanceled(true);
            // World ignores event cancellation for forceSpawn entities. Leave no live second item in that case.
            ItemStack empty = stack.copy();
            empty.stackSize = 0;
            item.setEntityItemStack(empty);
            item.setDead();
        }
    }

    static final class DropScope implements AutoCloseable {

        final World world;
        final List<ItemStack> items = new ArrayList<>();

        DropScope(World world) {
            if (CAPTURE.get() != null) throw new IllegalStateException("Nested area demolition drop capture");
            this.world = world;
            CAPTURE.set(this);
        }

        @Override
        public void close() {
            CAPTURE.remove();
        }
    }

    private static final class Job {

        final EntityPlayerMP player;
        final World world;
        final int dimension, slot;
        final ItemStack tool;
        final Cursor cursor;
        final HologramMaterials.Context materials;
        long removed, protectedCount;

        Job(EntityPlayerMP player, Cursor cursor) {
            this.player = player;
            world = player.worldObj;
            dimension = player.dimension;
            slot = player.inventory.currentItem;
            tool = player.getHeldItem();
            this.cursor = cursor;
            materials = recoveryContext(player, slot);
        }

        String invalidReason() {
            if (!connected(player)) return "玩家已离线";
            if (player.isDead) return "玩家已死亡";
            if (player.worldObj != world || player.dimension != dimension) return "玩家已切换维度";
            if (!player.capabilities.allowEdit) return "玩家已失去编辑权限";
            return player.inventory.currentItem != slot || player.getHeldItem() != tool ? "请保持持有原猫猫蓝图工具" : "";
        }
    }

    /** Visits disjoint boxes one global Y layer at a time; skips only a clipped empty chunk plane per step. */
    static final class Cursor {

        private final List<HologramAreaSelection.Region> boxes;
        private final int bottom;
        private int layer, boxIndex, chunkX, chunkZ, scanX, scanZ, left, right, back, front;
        private boolean boxStarted, planeStarted;
        int x, y, z;

        Cursor(List<HologramAreaSelection.Region> boxes, int height) throws IOException {
            if (boxes.isEmpty()) throw new IOException("减选后没有可拆除区域");
            this.boxes = new ArrayList<>(boxes);
            int minY = height, maxY = -1;
            for (HologramAreaSelection.Region box : boxes) {
                if (box.minY < 0 || box.maxY >= height
                    || box.minX < -30000000
                    || box.maxX >= 30000000
                    || box.minZ < -30000000
                    || box.maxZ >= 30000000) throw new IOException("拆除区域超出世界边界");
                minY = Math.min(minY, box.minY);
                maxY = Math.max(maxY, box.maxY);
            }
            bottom = minY;
            layer = maxY;
        }

        boolean done() {
            return layer < bottom;
        }

        boolean step(HologramAreaBlockCursor.Sections sections) throws IOException {
            if (done()) return false;
            HologramAreaSelection.Region box = boxes.get(boxIndex);
            if (layer < box.minY || layer > box.maxY) {
                nextBox();
                return false;
            }
            if (!boxStarted) {
                chunkX = box.minX >> 4;
                chunkZ = box.minZ >> 4;
                boxStarted = true;
            }
            if (!planeStarted) {
                left = Math.max(box.minX, chunkX << 4);
                right = Math.min(box.maxX, (chunkX << 4) + 15);
                back = Math.max(box.minZ, chunkZ << 4);
                front = Math.min(box.maxZ, (chunkZ << 4) + 15);
                if (sections.empty(chunkX, layer >> 4, chunkZ)) {
                    nextPlane();
                    return false;
                }
                scanX = left;
                scanZ = back;
                planeStarted = true;
            }
            x = scanX;
            y = layer;
            z = scanZ;
            if (++scanX > right) {
                scanX = left;
                if (++scanZ > front) nextPlane();
            }
            return true;
        }

        private void nextPlane() {
            planeStarted = false;
            HologramAreaSelection.Region box = boxes.get(boxIndex);
            if (++chunkX <= box.maxX >> 4) return;
            chunkX = box.minX >> 4;
            if (++chunkZ > box.maxZ >> 4) nextBox();
        }

        private void nextBox() {
            boxStarted = planeStarted = false;
            if (++boxIndex < boxes.size()) return;
            boxIndex = 0;
            layer--;
        }
    }
}
