// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Projection;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Native construct output is the sole structure definition. No factory/class/profile recognition is used here. */
final class HologramNativeProjectionJob {

    enum State {
        ACTIVE,
        COMPLETE,
        STOPPED,
        RECOVERY_PENDING
    }

    @FunctionalInterface
    interface PermitAdmission {

        HologramConstructionTaskGate.Permit acquire(HologramConstructionTaskGate gate,
            HologramConstructionTaskGate.Lease lease);
    }

    private static final AtomicLong REVISIONS = new AtomicLong();

    private static final class Baseline {

        final java.lang.ref.WeakReference<World> world;
        final int x, y, z, facing, id;
        final HologramNativeTargetPlan plan;

        Baseline(BaseMetaTileEntity controller, HologramNativeTargetPlan plan) {
            world = new java.lang.ref.WeakReference<>(controller.getWorldObj());
            x = controller.xCoord;
            y = controller.yCoord;
            z = controller.zCoord;
            id = controller.getMetaTileID();
            facing = ((MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity()).getExtendedFacing()
                .ordinal();
            this.plan = plan;
        }

        boolean matches(BaseMetaTileEntity controller) {
            return world.get() == controller.getWorldObj() && x == controller.xCoord
                && y == controller.yCoord
                && z == controller.zCoord
                && id == controller.getMetaTileID()
                && controller.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>machine
                && machine.getExtendedFacing()
                    .ordinal() == facing;
        }
    }

    private static final Map<BaseMetaTileEntity, Baseline> BASELINES = new java.util.WeakHashMap<>();
    private static final int BASELINE_CELLS = 1_000_000;

    static synchronized void clearBaselines() {
        BASELINES.clear();
    }

    private final HologramWorkspace.ActionEndpoint endpoint;
    private final HologramConstructionTaskGate gate;
    private final HologramConstructionTaskGate.Lease lease;
    private final HologramSession session;
    private final Projection intent;
    private final BooleanSupplier confirmed;
    private final BaseMetaTileEntity controller;
    private final MTEEnhancedMultiBlockBase<?> machine;
    private final ExtendedFacing facing;
    private final HologramNativeTargetPlan target;
    private final HologramMaterials.Context stock, recovery;
    private final List<HologramNativeTargetPlan.Cell> work;
    private final Map<HologramBlueprintResources.ExactKey, ItemStack> funding = new LinkedHashMap<>();
    private final boolean creative;
    private int next, placed;
    private boolean funded, anchorDone;
    private String reason = "";
    private State state = State.ACTIVE;
    private HologramConstructionTaskGate.Permit unsettled;

    HologramNativeProjectionJob(HologramWorkspace.ActionEndpoint endpoint, HologramConstructionTaskGate gate,
        HologramConstructionTaskGate.Lease lease, HologramSession session, Projection intent, int main,
        int encodedFacing, boolean creative, BooleanSupplier confirmed) throws IOException {
        this.endpoint = endpoint;
        this.gate = gate;
        this.lease = lease;
        this.session = session;
        this.intent = intent;
        this.confirmed = confirmed;
        this.creative = creative;
        controller = controller(endpoint, intent, session.x, session.y, session.z, encodedFacing);
        machine = (MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity();
        facing = machine.getExtendedFacing();
        HologramNativeTargetPlan cached;
        synchronized (HologramNativeProjectionJob.class) {
            cached = cachedBaseline(controller);
        }
        target = intent.operation() == Operation.DEMOLISH
            ? cached != null ? cached : compileBaseline(intent, main, facing)
            : compile(intent, main, facing);
        if (intent.operation() != Operation.DEMOLISH && !target.targetDigest()
            .equals(intent.nativeTargetDigest())) throw new IOException("客户端投影与服务器原生目标不同，请重新读取后确认。");
        HologramNativeTargetPlan baseline = baseline(controller, target);
        Stage stage = stage(endpoint.world, controller, baseline);
        if (!allows(intent.operation(), stage)) throw new IOException("当前结构状态不能执行此操作：" + stage);
        if (controller.isActive()) throw new IOException("请先停止正在运行的控制器，再执行结构操作。");
        stock = new HologramMaterials.Context(endpoint.player, session.materialSettings, session.slot);
        session.materialContext = stock;
        recovery = HologramDemolition.recoveryContext(endpoint.player, session.slot, intent.destination());
        var union = new LinkedHashMap<String, HologramNativeTargetPlan.Cell>();
        for (var cell : baseline.cells()) union.put(key(cell), cell);
        if (intent.operation() != Operation.DEMOLISH) {
            // Old-only positions are cleared after a native size/channel change; adjacent coordinates never enter.
            for (var cell : target.cells()) union.put(key(cell), cell);
        }
        work = new ArrayList<>(union.values());
        work.removeIf(cell -> cell.dx() == 0 && cell.dy() == 0 && cell.dz() == 0);
        work.sort(
            Comparator.comparingInt(HologramNativeTargetPlan.Cell::dy)
                .reversed()
                .thenComparingInt(HologramNativeTargetPlan.Cell::dz)
                .thenComparingInt(HologramNativeTargetPlan.Cell::dx));
        if (intent.operation() != Operation.DEMOLISH) {
            var targets = new LinkedHashMap<String, HologramNativeTargetPlan.Cell>();
            for (var cell : target.cells()) targets.put(key(cell), cell);
            for (int i = 0; i < work.size(); i++) {
                var before = work.get(i);
                var wanted = targets.get(key(before));
                if (wanted == null) work.set(i, air(before));
            }
        }
        if (!current()) throw new IOException("原生目标准备期间控制器、访问权限或工具已变化。");
    }

    private static HologramNativeTargetPlan.Cell air(HologramNativeTargetPlan.Cell before) {
        return new HologramNativeTargetPlan.Cell(
            before.dx(),
            before.dy(),
            before.dz(),
            Blocks.air,
            0,
            null,
            -1,
            new NBTTagCompound(),
            new NBTTagCompound(),
            before.element(),
            before.trigger(),
            true);
    }

    static ServerObservation observe(HologramWorkspace.ActionEndpoint endpoint, Projection intent, int main,
        int encodedFacing, int x, int y, int z) throws IOException {
        var controller = controller(endpoint, intent, x, y, z, encodedFacing);
        var machine = (MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity();
        HologramNativeTargetPlan cached;
        synchronized (HologramNativeProjectionJob.class) {
            cached = cachedBaseline(controller);
        }
        // World observation is independent of a newly edited, possibly incomplete preset.
        var target = cached != null ? cached : compileBaseline(intent, main, machine.getExtendedFacing());
        var baseline = baseline(controller, target);
        var stage = stage(endpoint.world, controller, baseline);
        if (controller(endpoint, intent, x, y, z, encodedFacing) != controller)
            throw new IOException("原生观察期间控制器或访问权限已变化。");
        return new ServerObservation(
            intent.controller(),
            x,
            y,
            z,
            stage,
            REVISIONS.incrementAndGet(),
            intent.targetRevision(),
            controller.isActive() ? "控制器正在运行，请先停止。" : "");
    }

    private static HologramNativeTargetPlan compileBaseline(Projection intent, int main, ExtendedFacing facing)
        throws IOException {
        var channels = new NBTTagCompound();
        intent.channels()
            .forEach(channels::setInteger);
        var trigger = HologramNativeTargetPlan.canonicalTrigger(main, channels);
        var result = HologramNativeTargetPlan.compileFootprint(intent.controller(), facing, trigger);
        if (result == null || !result.complete())
            throw new IOException(result == null ? "原生控制器未提供可读取的结构范围。" : result.failure());
        return result;
    }

    private static HologramNativeTargetPlan compile(Projection intent, int main, ExtendedFacing facing)
        throws IOException {
        var trigger = new ItemStack(Items.feather, main);
        intent.channels()
            .forEach((name, value) -> ChannelDataAccessor.setChannelData(trigger, name, value));
        var config = intent.configuration();
        var choices = new NBTTagList();
        for (var choice : intent.hatches()) {
            var row = new NBTTagCompound();
            row.setInteger("x", choice.dx());
            row.setInteger("y", choice.dy());
            row.setInteger("z", choice.dz());
            row.setInteger("id", choice.id());
            row.setInteger("facing", choice.facing());
            choices.appendTag(row);
        }
        config.setTag("nativeHatchChoices", choices);
        var result = HologramNativeTargetPlan.compile(intent.controller(), facing, trigger, config);
        if (result == null || !result.complete())
            throw new IOException(result == null ? "原生投影未返回目标。" : result.failure());
        return result;
    }

    private static BaseMetaTileEntity controller(HologramWorkspace.ActionEndpoint endpoint, Projection intent, int x,
        int y, int z, int facing) throws IOException {
        if (!HologramWorkspace.currentActionEndpoint(endpoint) || !endpoint.world.blockExists(x, y, z)
            || endpoint.player.getDistanceSq(x + .5, y + .5, z + .5) > 64
            || endpoint.world.getBlock(x, y, z) != GregTechAPI.sBlockMachines
            || !(endpoint.world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity tile)
            || tile.isInvalid()
            || tile.getMetaTileID() != intent.controller()
            || !(tile.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>machine)
            || machine.getExtendedFacing()
                .ordinal() != facing)
            throw new IOException("原生控制器、位置或朝向已变化。");
        if (!controllerAccessAllowed(endpoint.player, tile, machine))
            throw new IOException("你没有访问此控制器的权限，请由机主解除锁定或授权后重试。");
        if (endpoint.world.getTileEntity(x, y, z) != tile || tile.isInvalid()
            || tile.getMetaTileEntity() != machine
            || tile.getMetaTileID() != intent.controller()
            || machine.getExtendedFacing()
                .ordinal() != facing)
            throw new IOException("权限核对期间控制器已变化，请重新读取。");
        return tile;
    }

    /** The same native access rules used by the preset quote, independent of operator/creative privileges. */
    static boolean controllerAccessAllowed(EntityPlayerMP player, BaseMetaTileEntity tile,
        MTEEnhancedMultiBlockBase<?> machine) {
        if (player == null || player.isDead
            || player.capabilities == null
            || !player.capabilities.allowEdit
            || tile == null
            || tile.isInvalid()
            || machine == null
            || tile.getMetaTileEntity() != machine
            || machine.getBaseMetaTileEntity() != tile
            || !machine.isAccessAllowed(player)) return false;
        if (tile.privateAccess()) {
            var actor = player.getUniqueID();
            boolean ownerId = actor != null && actor.equals(tile.getOwnerUuid());
            boolean ownerName = player.getCommandSenderName() != null && player.getCommandSenderName()
                .equals(tile.getOwnerName());
            if (!ownerId && !ownerName) return false;
        }
        return !tile.isInvalid() && tile.getMetaTileEntity() == machine && machine.getBaseMetaTileEntity() == tile;
    }

    private static synchronized HologramNativeTargetPlan baseline(BaseMetaTileEntity controller,
        HologramNativeTargetPlan target) {
        BASELINES.entrySet()
            .removeIf(
                entry -> entry.getKey()
                    .isInvalid());
        var old = cachedBaseline(controller);
        if (old != null) return old;
        int retained = BASELINES.values()
            .stream()
            .mapToInt(
                saved -> saved.plan.cells()
                    .size())
            .sum();
        while (!BASELINES.isEmpty() && (BASELINES.size() >= 128 || retained + target.cells()
            .size() > BASELINE_CELLS)) {
            var entry = BASELINES.entrySet()
                .iterator()
                .next();
            retained -= entry.getValue().plan.cells()
                .size();
            BASELINES.remove(entry.getKey());
        }
        if (target.cells()
            .size() <= BASELINE_CELLS) BASELINES.put(controller, new Baseline(controller, target));
        return target;
    }

    private static synchronized HologramNativeTargetPlan cachedBaseline(BaseMetaTileEntity controller) {
        var saved = BASELINES.get(controller);
        if (saved == null) return null;
        if (controller.isInvalid() || !saved.matches(controller)) {
            BASELINES.remove(controller);
            return null;
        }
        return saved.plan;
    }

    private static Stage stage(World world, BaseMetaTileEntity controller, HologramNativeTargetPlan plan)
        throws IOException {
        boolean occupied = false;
        for (var cell : plan.cells()) {
            if (cell.dx() == 0 && cell.dy() == 0 && cell.dz() == 0) continue;
            int x = controller.xCoord + cell.dx(), y = controller.yCoord + cell.dy(), z = controller.zCoord + cell.dz();
            if (!world.blockExists(x, y, z)) throw new IOException("当前结构范围包含未加载区块。");
            occupied |= world.getBlock(x, y, z) != Blocks.air || world.getTileEntity(x, y, z) != null;
        }
        var machine = (MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity();
        machine.checkStructure(true, controller);
        return machine.mMachine ? Stage.FORMED : occupied ? Stage.UNFORMED : Stage.CONTROLLER_ONLY;
    }

    private static boolean allows(Operation operation, Stage stage) {
        return operation == Operation.BUILD ? stage == Stage.CONTROLLER_ONLY
            : operation == Operation.REPAIR ? stage == Stage.UNFORMED
                : operation == Operation.REPLACE ? stage == Stage.FORMED
                    : stage == Stage.UNFORMED || stage == Stage.FORMED;
    }

    private boolean executionCurrent() {
        return state == State.ACTIVE && confirmed.getAsBoolean()
            && HologramWorkspace.currentActionEndpoint(endpoint)
            && endpoint.player.worldObj == endpoint.world
            && HologramWorkspace.find(endpoint.actor) == session
            && endpoint.player.capabilities.isCreativeMode == creative
            && !endpoint.player.isDead
            && endpoint.player.capabilities.allowEdit;
    }

    private boolean current() {
        return executionCurrent() && !controller.isInvalid()
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == controller
            && endpoint.world.getBlock(session.x, session.y, session.z) == GregTechAPI.sBlockMachines
            && controller.getMetaTileID() == intent.controller()
            && controller.getMetaTileEntity() == machine
            && machine.getExtendedFacing() == facing
            && !controller.isActive()
            && controllerAccessAllowed(endpoint.player, controller, machine)
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == controller
            && controller.getMetaTileEntity() == machine
            && controller.getMetaTileID() == intent.controller()
            && machine.getExtendedFacing() == facing;
    }

    /** Used only after the successful native removal of the final whole-structure controller. */
    private boolean removalCurrent(boolean anchor) {
        return current() || anchor && intent.operation() == Operation.DEMOLISH
            && next >= work.size()
            && !anchorDone
            && executionCurrent()
            && endpoint.world.blockExists(session.x, session.y, session.z)
            && endpoint.world.getBlock(session.x, session.y, session.z) == Blocks.air
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == null;
    }

    private static String key(HologramNativeTargetPlan.Cell cell) {
        return cell.dx() + "," + cell.dy() + "," + cell.dz();
    }

    private boolean same(HologramNativeTargetPlan.Cell cell) {
        int x = session.x + cell.dx(), y = session.y + cell.dy(), z = session.z + cell.dz();
        return HologramWorldTransactions.nativeProjectionStateMatches(endpoint.world, x, y, z, cell);
    }

    private void fund() throws IOException {
        for (var cell : work) {
            int x = session.x + cell.dx(), y = session.y + cell.dy(), z = session.z + cell.dz();
            if (!endpoint.world.blockExists(x, y, z)) throw new IOException("施工范围包含未加载区块。");
            if (intent.operation() == Operation.DEMOLISH || cell.requiredAir()
                || cell.material() == null
                || HologramWorldTransactions.nativeProjectionIdentity(endpoint.world, x, y, z, cell)) continue;
            ItemStack item = cell.material();
            var id = HologramValues.stackKey(item);
            var aggregate = funding.get(id);
            if (aggregate == null) {
                aggregate = item.copy();
                aggregate.stackSize = 0;
                funding.put(id, aggregate);
            }
            aggregate.stackSize = Math.addExact(aggregate.stackSize, 1);
        }
        if (!creative) for (var item : funding.values())
            if (stock.count(item, item.stackSize) < item.stackSize) throw new IOException("所选材料来源不足，未开始修改结构。");
        if (intent.operation() != Operation.BUILD) recovery.requireDemolitionDestination(intent.destination());
        funded = true;
    }

    State drain(long deadline, PermitAdmission admission) {
        if (state != State.ACTIVE) return state;
        try (var updates = HologramMachineUpdateBatch.open()) {
            return drainBatch(deadline, admission);
        }
    }

    private State drainBatch(long deadline, PermitAdmission admission) {
        try {
            if (!current() || HologramConstructionAuthority.hasKnownRecovery(endpoint.player))
                return stop("原生结构、工具或恢复状态已变化。");
            if (!funded) fund();
            while (System.nanoTime() < deadline) {
                if (next >= work.size()) {
                    if (intent.operation() == Operation.DEMOLISH && !anchorDone) {
                        if (!remove(session.x, session.y, session.z, admission, true)) return state;
                        anchorDone = true;
                    }
                    if (intent.operation() != Operation.DEMOLISH) {
                        machine.checkStructure(true, controller);
                        synchronized (HologramNativeProjectionJob.class) {
                            BASELINES.remove(controller);
                            baseline(controller, target);
                        }
                    } else synchronized (HologramNativeProjectionJob.class) {
                        BASELINES.remove(controller);
                    }
                    if (!gate.finish(lease, HologramConstructionTaskGate.End.COMPLETE)) return stop("任务许可已变化。");
                    state = State.COMPLETE;
                    return state;
                }
                var cell = work.get(next);
                int x = session.x + cell.dx(), y = session.y + cell.dy(), z = session.z + cell.dz();
                if (!current() || !endpoint.world.blockExists(x, y, z)) return stop("施工期间控制器或区块已变化。");
                if (intent.operation() == Operation.DEMOLISH || cell.requiredAir()) {
                    if (!remove(x, y, z, admission, false)) return state;
                    next++;
                    continue;
                }
                if (same(cell)) {
                    next++;
                    continue;
                }
                if (HologramWorldTransactions.nativeProjectionIdentity(endpoint.world, x, y, z, cell)
                    && endpoint.world.getTileEntity(x, y, z) != null) {
                    var permit = admission.acquire(gate, lease);
                    if (permit == null) return state;
                    boolean done = HologramWorldTransactions
                        .synchronizeNativeProjection(session, cell, () -> current() && gate.transactionCurrent(permit));
                    if (!settle(permit, done)) return state;
                    placed++;
                    next++;
                    continue;
                }
                if (endpoint.world.getBlock(x, y, z) != Blocks.air || endpoint.world.getTileEntity(x, y, z) != null) {
                    if (intent.operation() == Operation.BUILD) return stop("新建范围已出现方块，请重新读取后补建。");
                    if (!remove(x, y, z, admission, false)) return state;
                    // Natural removal is already committed with its actual drops. A later placement failure may
                    // leave this cell empty; it must never resurrect the old block after delivering those drops.
                    continue;
                }
                var permit = admission.acquire(gate, lease);
                if (permit == null) return state;
                boolean done = HologramWorldTransactions
                    .placeNativeProjection(session, cell, () -> current() && gate.transactionCurrent(permit));
                if (!settle(permit, done)) return state;
                placed++;
                next++;
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            return stop(failure.getMessage() == null ? "原生结构操作异常，已保留实际完成结果。" : failure.getMessage());
        }
        return state;
    }

    private boolean settle(HologramConstructionTaskGate.Permit permit, boolean success) {
        if (HologramConstructionJournal.pending(endpoint.player)) {
            gate.settle(permit, HologramConstructionTaskGate.Settlement.RECOVERY_PENDING);
            unsettled = permit;
            state = State.RECOVERY_PENDING;
            reason = "原生操作结果待核定，未继续执行。";
            return false;
        }
        if (!gate.settle(
            permit,
            success ? HologramConstructionTaskGate.Settlement.COMMITTED
                : HologramConstructionTaskGate.Settlement.ROLLED_BACK))
            return false;
        if (!success) {
            stop(session.status.isEmpty() ? "本格未提交，已停止。" : session.status);
            return false;
        }
        return true;
    }

    private boolean remove(int x, int y, int z, PermitAdmission admission, boolean anchor) throws IOException {
        World world = endpoint.world;
        if (world.getBlock(x, y, z) == Blocks.air && world.getTileEntity(x, y, z) == null) return true;
        recovery.requireDemolitionDestination(intent.destination());
        if (recovery.recoveryBacklogged()) return stopRemoval("回收队列尚未交付，未继续拆除。");
        Block block = world.getBlock(x, y, z);
        int metadata = world.getBlockMetadata(x, y, z);
        TileEntity tile = world.getTileEntity(x, y, z);
        var permit = admission.acquire(gate, lease);
        if (permit == null) return false;
        boolean settled = false;
        try {
            var result = HologramDemolition.removeBlock(
                endpoint.player,
                world,
                x,
                y,
                z,
                () -> current() && gate.transactionCurrent(permit)
                    && world.getBlock(x, y, z) == block
                    && world.getBlockMetadata(x, y, z) == metadata
                    && world.getTileEntity(x, y, z) == tile,
                () -> removalCurrent(anchor) && gate.transactionCurrent(permit));
            recovery.recoverWorldAtPlayer(result.drops, intent.destination());
            boolean removed = result.removed && world.getBlock(x, y, z) == Blocks.air
                && world.getTileEntity(x, y, z) == null;
            boolean committed = result.removed || !result.drops.isEmpty();
            settled = gate.settle(
                permit,
                committed ? HologramConstructionTaskGate.Settlement.COMMITTED
                    : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
            if (!settled) return false;
            if (committed) recovery.flushRecovery();
            if (!removed || result.failure != null || recovery.pendingWorldRecovery()) {
                HologramDemolition.resumeRecovery(endpoint.player);
                return stopRemoval("原生采收或交付停止；实际掉落已保留，不恢复源块或重复投递。");
            }
            if (intent.operation() == Operation.DEMOLISH || anchor) placed++;
            return true;
        } finally {
            if (!settled) {
                gate.settle(permit, HologramConstructionTaskGate.Settlement.RECOVERY_PENDING);
                unsettled = permit;
                state = State.RECOVERY_PENDING;
                reason = "原生采收结果待核定，未重放、恢复源块或重复发放掉落。";
            }
        }

    }

    private boolean stopRemoval(String detail) {
        stop(detail);
        return false;
    }

    private State stop(String detail) {
        if (state == State.ACTIVE) {
            gate.finish(lease, HologramConstructionTaskGate.End.FAILED);
            state = State.STOPPED;
            reason = detail;
        }
        return state;
    }

    boolean cancel() {
        if (state != State.ACTIVE || !gate.cancel(lease.scope, lease.request)) return false;
        state = State.STOPPED;
        reason = "已停止；实际提交的放置、拆除与回收保留。";
        return true;
    }

    int placed() {
        return placed;
    }

    String reason() {
        return reason;
    }

    HologramConstructionTaskGate.Permit unsettled() {
        return unsettled;
    }
}
