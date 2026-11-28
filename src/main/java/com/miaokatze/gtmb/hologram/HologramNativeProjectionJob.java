// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
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
final class HologramNativeProjectionJob implements HologramConstructionJobs.Source {

    enum State {
        ACTIVE,
        COMPLETE,
        STOPPED,
        RECOVERY_PENDING
    }

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
    private final HologramSession session;
    private final Projection intent;
    private final HologramStockNativeEntry.Submission submission;
    private final BaseMetaTileEntity controller;
    private final MTEEnhancedMultiBlockBase<?> machine;
    private final ExtendedFacing facing;
    private HologramNativeTargetPlan target;
    private final int main;
    private final long started = System.nanoTime();
    static final String LIMITED_SCOPE_DETAIL = "按本次原生已知范围处理；更早参数丢失的范围外残留无法识别。";
    private boolean initialized, scopeFallback;
    private HologramMaterials.Context stock, recovery;
    private List<HologramNativeTargetPlan.Cell> work = java.util.Collections.emptyList();
    private final Map<HologramBlueprintResources.ExactKey, ItemStack> funding = new LinkedHashMap<>();
    private final HologramNativeConstruction.ProjectionCache placements = new HologramNativeConstruction.ProjectionCache();
    private final boolean creative;
    private int next, fundCursor;
    private boolean anchorRemoved;
    private java.util.Iterator<ItemStack> fundRows;

    HologramNativeProjectionJob(HologramWorkspace.ActionEndpoint endpoint, HologramSession session, Projection intent,
        int main, int encodedFacing, boolean creative, HologramStockNativeEntry.Submission submission)
        throws IOException {
        this.endpoint = endpoint;
        this.session = session;
        this.intent = intent;
        this.submission = submission;
        this.creative = creative;
        this.main = main;
        controller = controller(endpoint, intent, session.x, session.y, session.z, encodedFacing);
        machine = (MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity();
        facing = machine.getExtendedFacing();
        if (!current()) throw new IOException("原生目标准备期间控制器、访问权限或工具已变化。");
    }

    /** Native simulation/check callbacks are indivisible; the owning queue checks cancellation between boundaries. */
    private void initialize() throws IOException {
        HologramNativeTargetPlan cached;
        synchronized (HologramNativeProjectionJob.class) {
            cached = cachedBaseline(controller);
        }
        scopeFallback = cached == null && intent.operation() != Operation.BUILD;
        // A cache is optional source data, never a prerequisite or authorization for BEGIN.
        HologramNativeTargetPlan baseline = cached != null ? cached : compileBaseline(intent, main, facing);
        target = intent.operation() == Operation.DEMOLISH ? baseline : compile(intent, main, facing);
        if (!current()) throw new IOException("施工上下文已变化。");
        if (intent.operation() != Operation.DEMOLISH && !target.targetDigest()
            .equals(intent.nativeTargetDigest())) throw new IOException("客户端投影与服务器原生目标不同，请重新读取后确认。");
        Stage stage = stage(endpoint.world, controller, baseline);
        if (!current()) throw new IOException("施工上下文已变化。");
        if (!allows(intent.operation(), stage)) throw new IOException("当前结构状态不能执行此操作：" + stage);
        baseline(controller, baseline);
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
        if (!current()) throw new IOException("施工上下文已变化。");
        initialized = true;
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
        return new ServerObservation(intent.controller(), x, y, z, stage, intent.targetRevision(), "");
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
        return submission.current();
    }

    public boolean current() {
        if (anchorRemoved && intent.operation() == Operation.DEMOLISH) return executionCurrent();
        return executionCurrent() && !controller.isInvalid()
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == controller
            && endpoint.world.getBlock(session.x, session.y, session.z) == GregTechAPI.sBlockMachines
            && controller.getMetaTileID() == intent.controller()
            && controller.getMetaTileEntity() == machine
            && machine.getExtendedFacing() == facing
            && controllerAccessAllowed(endpoint.player, controller, machine)
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == controller
            && controller.getMetaTileEntity() == machine
            && controller.getMetaTileID() == intent.controller()
            && machine.getExtendedFacing() == facing
            && endpoint.player.worldObj == endpoint.world
            && endpoint.player.dimension == session.dimension
            && submission.contextRefusal() == null;
    }

    /** Used only after the successful native removal of the final whole-structure controller. */
    private boolean removalCurrent(boolean anchor) {
        return current() || anchor && intent.operation() == Operation.DEMOLISH
            && next >= work.size()
            && executionCurrent()
            && endpoint.world.blockExists(session.x, session.y, session.z)
            && endpoint.world.getBlock(session.x, session.y, session.z) == Blocks.air
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == null;
    }

    private static String key(HologramNativeTargetPlan.Cell cell) {
        return cell.dx() + "," + cell.dy() + "," + cell.dz();
    }

    public HologramSession session() {
        return session;
    }

    public Operation operation() {
        return intent.operation();
    }

    public int destination() {
        return intent.destination();
    }

    public HologramMaterials.Context recoveryContext() {
        return recovery;
    }

    public long startedAt() {
        return started;
    }

    public long total() {
        return initialized ? work.size() + (intent.operation() == Operation.DEMOLISH ? 1 : 0) : 0;
    }

    public boolean exhausted() {
        return next >= total();
    }

    public boolean removalCurrent(HologramConstructionJobs.Target cell) {
        return removalCurrent(cell.x == session.x && cell.y == session.y && cell.z == session.z);
    }

    public boolean prepare(long deadline) throws IOException {
        if (!initialized) {
            if (System.nanoTime() >= deadline) return false;
            initialize();
        }
        while (fundCursor < work.size() && System.nanoTime() < deadline) {
            if (!current()) throw new IOException("施工上下文已变化。");
            var cell = work.get(fundCursor++);
            int x = session.x + cell.dx(), y = session.y + cell.dy(), z = session.z + cell.dz();
            if (!endpoint.world.blockExists(x, y, z)) throw new IOException("施工范围包含未加载区块。");
            if (intent.operation() == Operation.DEMOLISH || cell.requiredAir()
                || cell.material() == null
                || HologramNativeConstruction.matches(session, placements.get(cell), x, y, z)) continue;
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
        if (fundCursor < work.size()) return false;
        if (fundRows == null) fundRows = funding.values()
            .iterator();
        while (fundRows.hasNext() && System.nanoTime() < deadline) {
            var item = fundRows.next();
            if (!creative && stock.count(item, item.stackSize) < item.stackSize)
                throw new IOException("所选材料来源不足，未开始修改结构。");
            if (!current()) throw new IOException("施工上下文已变化。");
        }
        if (fundRows.hasNext()) return false;
        if (!creative && intent.operation() != Operation.BUILD)
            recovery.requireDemolitionDestination(intent.destination());
        return true;
    }

    public HologramConstructionJobs.Target next(long deadline) throws IOException {
        if (next >= work.size()) {
            if (intent.operation() != Operation.DEMOLISH || next++ > work.size()) return null;
            return new HologramConstructionJobs.Target(session.x, session.y, session.z, null, true, true);
        }
        var cell = work.get(next++);
        var prepared = cell.requiredAir() || intent.operation() == Operation.DEMOLISH ? null : placements.get(cell);
        boolean controllerCell = prepared != null
            && prepared.sample.getItem() instanceof gregtech.common.blocks.ItemMachines
            && gregtech.common.blocks.ItemMachines
                .getMetaTileEntity(prepared.sample) instanceof MTEEnhancedMultiBlockBase<?>;
        return new HologramConstructionJobs.Target(
            session.x + cell.dx(),
            session.y + cell.dy(),
            session.z + cell.dz(),
            prepared,
            cell.requiredAir(),
            controllerCell);
    }

    public void committed(HologramConstructionJobs.Target cell, boolean changed) {
        if (intent.operation() == Operation.DEMOLISH && cell.x == session.x
            && cell.y == session.y
            && cell.z == session.z) anchorRemoved = true;
    }

    public void finished(HologramConstructionJobs.Job job) {
        if (scopeFallback) job.reason = job.reason.isEmpty() || "施工完成。".equals(job.reason) ? LIMITED_SCOPE_DETAIL
            : job.reason + " " + LIMITED_SCOPE_DETAIL;
        if (job.end != HologramConstructionJobs.End.COMPLETE) return;
        if (intent.operation() != Operation.DEMOLISH && !controller.isInvalid())
            machine.checkStructure(true, controller);
        synchronized (HologramNativeProjectionJob.class) {
            BASELINES.remove(controller);
            if (intent.operation() != Operation.DEMOLISH && !controller.isInvalid()) baseline(controller, target);
        }
    }
}
