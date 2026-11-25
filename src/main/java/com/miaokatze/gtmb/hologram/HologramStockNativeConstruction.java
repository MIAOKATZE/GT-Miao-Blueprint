// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.BlueprintSnapshots;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchCokeOven;
import gregtech.common.tileentities.machines.multi.MTECokeOven;

/** Server-owned fixed stock native driver. The authenticated entry must supply explicit prefix consent and the
 * exact saved hatch choice. This is not an implicit fallback for the old whole-structure confirmation. */
final class HologramStockNativeConstruction {

    @FunctionalInterface
    interface PermitAdmission {

        HologramConstructionTaskGate.Permit acquire(HologramConstructionTaskGate gate,
            HologramConstructionTaskGate.Lease lease);
    }

    enum State {
        ACTIVE, COMPLETE, STOPPED, RECOVERY_PENDING
    }

    /** One selected installed #237, not a block list or client-supplied material stack. */
    static final class HatchChoice {

        final int dx, dy, dz, id, tier, facing;

        HatchChoice(int dx, int dy, int dz, int id, int tier, int facing) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.id = id;
            this.tier = tier;
            this.facing = facing;
        }
    }

    private final HologramWorkspace.ActionEndpoint endpoint;
    private final HologramConstructionTaskGate gate;
    private final HologramConstructionTaskGate.Lease lease;
    private final HologramSession session;
    private final HologramStockCokeOvenProfile profile;
    private final HologramMaterials.Settings settings;
    private final InventoryPlayer originalInventory;
    private final ItemStack[] originalSlots;
    private final ItemStack projector, pinnedTool, trigger, casing;
    private final BaseMetaTileEntity controller, hatch;
    private final MTECokeOven machine;
    private final MTEHatchCokeOven hatchMachine;
    private final ExtendedFacing facing;
    private final HologramStructureCursor cursor;
    private final int ax, ay, az, side, hx, hy, hz, main, hatchFacing, priority, recoveryDestination;
    private final long epoch, plan;
    private final boolean mainExplicit, noHatches;
    private int x, y, z, placed, activeEntry = -1;
    private IStructureElement<?> activeElement;
    private boolean position, loaded, advancing;
    private State state = State.ACTIVE;
    private String reason = "";
    private HologramConstructionTaskGate.Permit unsettled;

    private final IStructureWalker<Object> receiver = new IStructureWalker<Object>() {

        @Override
        public boolean visit(IStructureElement<Object> element, World world, int x, int y, int z, int a, int b, int c) {
            locate(x, y, z, true);
            return true;
        }

        @Override
        public boolean blockNotLoaded(IStructureElement<Object> element, World world, int x, int y, int z, int a,
            int b, int c) {
            locate(x, y, z, false);
            return true;
        }
    };

    private HologramStockNativeConstruction(HologramWorkspace.ActionEndpoint endpoint, HologramConstructionTaskGate gate,
        HologramConstructionTaskGate.Lease lease, HologramSession session, HologramStockCokeOvenProfile profile,
        ItemStack projector, ItemStack casing, BaseMetaTileEntity hatch, MTEHatchCokeOven hatchMachine,
        HatchChoice choice) {
        this.endpoint = endpoint;
        this.gate = gate;
        this.lease = lease;
        this.session = session;
        this.profile = profile;
        this.projector = projector;
        pinnedTool = projector.copy();
        this.casing = casing;
        this.hatch = hatch;
        this.hatchMachine = hatchMachine;
        controller = (BaseMetaTileEntity) session.target;
        machine = (MTECokeOven) session.machine;
        facing = machine.getExtendedFacing();
        ax = session.x;
        ay = session.y;
        az = session.z;
        side = session.side;
        hx = ax + choice.dx;
        hy = ay + choice.dy;
        hz = az + choice.dz;
        hatchFacing = choice.facing;
        main = session.main;
        mainExplicit = session.mainExplicit;
        noHatches = session.noHatches;
        settings = session.materialSettings;
        originalInventory = session.player.inventory;
        originalSlots = originalInventory.mainInventory;
        priority = settings.priority;
        recoveryDestination = settings.recoveryDestination;
        epoch = session.editorWorkspaceEpoch;
        plan = session.planRevision;
        trigger = HologramSessionPolicy.trigger(session);
        cursor = new HologramStructureCursor(
            profile.nativeElements(), HologramStockCokeOvenProfile.A, HologramStockCokeOvenProfile.B,
            HologramStockCokeOvenProfile.C);
    }

    static HologramStockNativeConstruction begin(HologramWorkspace.ActionEndpoint endpoint, HologramSession session,
        HologramConstructionTaskGate.Request request, String source, int confirmedMain,
        HologramIncrementalNativePlacement.Consent consent, HatchChoice choice) throws IOException {
        if (consent != HologramIncrementalNativePlacement.Consent.PLACE_SAFE_CELLS_WITH_AVAILABLE_STOCK)
            throw refusal("没有当前原生逐格补建的明确部分施工确认。");
        HologramConstructionTaskGate gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null || session == null || endpoint.player != session.player || request == null
            || request.kind != HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE)
            throw refusal("没有当前原生逐格补建的明确确认。");
        if (!HologramStockCokeOvenProfile.SOURCE_ID.equals(source))
            throw refusal("当前控制器没有已审核的原生逐格补建配置。");
        boolean busy = HologramProjectedLayerService.competingWork(endpoint.player);
        boolean recovery = HologramProjectedLayerService.hasKnownRecovery(endpoint.player);
        var admission = gate.acquire(endpoint.constructionScope, request, busy, recovery);
        if (admission.decision != HologramConstructionTaskGate.Decision.GRANTED)
            throw refusal("本次原生施工未获得独占许可：" + admission.decision);
        boolean started = false;
        try {
            var job = openOwned(endpoint, gate, admission.lease, session, confirmedMain, choice,
                HologramStockNativeEntry.Funding.EXPLICIT_AVAILABLE_STOCK);
            started = true;
            return job;
        } finally {
            if (!started) gate.finish(admission.lease, HologramConstructionTaskGate.End.FAILED);
        }
    }

    /** The actual stock adapter already acquired the exact authority before doing any admission reads. */
    static HologramStockNativeConstruction openOwned(HologramWorkspace.ActionEndpoint endpoint,
        HologramConstructionTaskGate gate, HologramConstructionTaskGate.Lease lease, HologramSession session,
        int confirmedMain, HatchChoice choice, HologramStockNativeEntry.Funding funding) throws IOException {
        if (lease == null || session == null || funding == null || gate != HologramWorkspace.constructionGate(endpoint)
            || lease.scope != endpoint.constructionScope || session.player != endpoint.player
            || lease.request.kind != HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE
            || gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE)
            throw refusal("没有当前原生逐格补建许可。");
        if (!ready(session) || session.main != confirmedMain || choice == null
            || choice.id != HologramStockCokeOvenProfile.HATCH || choice.tier != HologramStockCokeOvenProfile.HATCH_TIER
            || choice.facing < 0 || choice.facing >= 6)
            throw refusal("当前工作台或施工设置不支持逐格补建；请确认原背包和已安装的 ULV 焦炉仓室。");
        var profile = HologramStockCokeOvenProfile.inspectRegistered();
        var tool = HologramSessionPolicy.currentTool(session);
        if (tool == null || tool.getTagCompound().hasKey(HologramSessionKeys.DRAFT)
            || profile == null || !profile.acceptsSettings(confirmedMain, session.channels)
            || !profile.acceptsController((BaseMetaTileEntity) session.target)
            || !profile.shellAt(session.machine.getExtendedFacing(), choice.dx, choice.dy, choice.dz))
            throw refusal("原生声明、投影仪设置或所选焦炉仓室不再适用。");
        World world = endpoint.world;
        int hx = session.x + choice.dx, hy = session.y + choice.dy, hz = session.z + choice.dz;
        if (!HologramWorldFlow.worldPosition(hx, hy, hz, world.getHeight()) || !world.blockExists(hx, hy, hz))
            throw refusal("所选仓室所在区块未加载。");
        TileEntity candidate = world.getTileEntity(hx, hy, hz);
        ItemStack material = profile.casingItem();
        if (candidate == null || candidate.getClass() != BaseMetaTileEntity.class || material == null)
            throw refusal("未找到确切的已安装焦炉仓室或原生焦炉砖。");
        var installed = (BaseMetaTileEntity) candidate;
        var meta = installed.getMetaTileEntity();
        if (meta == null || meta.getClass() != MTEHatchCokeOven.class)
            throw refusal("所选位置不是已安装的焦炉仓室。");
        var job = new HologramStockNativeConstruction(endpoint, gate, lease, session, profile, tool,
            material, installed, (MTEHatchCokeOven) meta, choice);
        if (!job.current(true))
            throw refusal("所选焦炉仓室、朝向、结构声明或投影仪已更改。");
        if (funding == HologramStockNativeEntry.Funding.REQUIRE_FULL_BOUND && !hasMaximumStock(session.player, session.slot, material))
            throw refusal("普通逐格补建须在背包含有 24 块焦炉砖；未开始施工。");
        return job;
    }

    /** At most 25 shell rows, less the exact installed hatch proved by begin. No world or quote scan. */
    static boolean hasMaximumStock(net.minecraft.entity.player.EntityPlayerMP player, int protectedSlot,
        ItemStack casing) {
        if (player == null || casing == null || casing.hasTagCompound()) return false;
        var inventory = player.inventory;
        ItemStack[] slots = inventory.mainInventory;
        int need = 24;
        for (int slot = 0; slot < slots.length; slot++) {
            ItemStack stack = slots[slot];
            if (slot == protectedSlot || stack == null || stack.stackSize <= 0 || stack.hasTagCompound()
                || !HologramValues.same(stack, casing)) continue;
            need -= Math.min(need, stack.stackSize);
            if (need == 0) return player.inventory == inventory && inventory.mainInventory == slots;
        }
        return false;
    }

    private static IOException refusal(String reason) {
        return new IOException(reason);
    }

    private static boolean ready(HologramSession s) {
        return s.target != null && s.target.getClass() == BaseMetaTileEntity.class && s.machine != null
            && s.machine.getClass() == MTECokeOven.class && s.mode == 0 && s.scope == 0 && !s.virtual && s.job == 0
            && !s.cancelled && !s.hatchDowngrade && s.areaStructureId.isEmpty() && s.capture == null
            && s.planTargets.isEmpty() && s.pins.isEmpty() && s.coordinatePins.isEmpty() && s.facingPins.isEmpty()
            && s.manualPositions.isEmpty() && s.nativeOperationInputs == null && s.pendingAssetBudget.isEmpty()
            && s.cellAssetBudgets.isEmpty() && !s.jobVoucherRequested && s.materialSettings.main
            && !s.materialSettings.containers && !s.materialSettings.me && !s.player.capabilities.isCreativeMode
            && HologramWorkspace.find(s.player.getUniqueID()) == s
            && !HologramProjectedLayerService.competingWork(s.player)
            && !StructureLibAPI.isInstrumentEnabled()
            && !BlueprintSnapshots.hasProcessors(BlueprintSnapshots.Stage.CAPTURE_COMPLETE);
    }

    /** Same server-thread capability and data identities, no world reads or StructureLib construction callbacks. */
    private boolean bound(HologramCapture scratch) {
        var player = session.player;
        var inventory = player.inventory;
        return state == State.ACTIVE && HologramWorkspace.currentActionEndpoint(endpoint)
            && HologramWorkspace.find(endpoint.actor) == session && !player.isDead && player.capabilities.allowEdit
            && withinReach()
            && !player.capabilities.isCreativeMode && session.editorWorkspaceEpoch == epoch && session.planRevision == plan
            && session.capture == scratch && session.job == 0 && !session.cancelled && !session.virtual
            && session.x == ax && session.y == ay && session.z == az && session.side == side
            && session.mode == 0 && session.scope == 0
            && session.target == controller && session.machine == machine && !machine.mMachine
            && stockLinksCurrent()
            && session.main == main && session.mainExplicit == mainExplicit
            && session.channels.hasNoTags() && session.noHatches == noHatches && !session.hatchDowngrade
            && session.materialSettings == settings && settings.main && !settings.containers && !settings.me
            && settings.priority == priority && settings.recoveryDestination == recoveryDestination
            && inventory == originalInventory && inventory.mainInventory == originalSlots
            && session.slot == inventory.currentItem && session.slot >= 0 && session.slot < originalSlots.length
            && inventory.mainInventory[session.slot] == projector
            && ItemStack.areItemStacksEqual(pinnedTool, projector)
            && session.coordinatePins.isEmpty() && session.facingPins.isEmpty() && session.manualPositions.isEmpty()
            && session.nativeOperationInputs == null && session.pendingAssetBudget.isEmpty() && session.cellAssetBudgets.isEmpty()
            && !session.jobVoucherRequested && !StructureLibAPI.isInstrumentEnabled()
            && profile.declarationCurrent();
    }

    private boolean current(boolean checkJournal) {
        if (!bound(null) || gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE
            || !ready(session) || !HologramSessionPolicy.valid(session)
            || checkJournal && HologramProjectedLayerService.hasKnownRecovery(endpoint.player)) return false;
        if (!liveStockCurrent()) return false;
        return bound(null) && gate.query(lease.scope, lease.request).phase == HologramConstructionTaskGate.Phase.ACTIVE
            && ItemStack.areItemStacksEqual(trigger, HologramSessionPolicy.trigger(session));
    }

    private boolean withinReach() {
        // Read current actor coordinates, not a world/adapter callback. The pure post-Forge debit fence must
        // reject even if a callback moves this same actor in this same world, keeping all identity checks valid.
        var player = session.player;
        double dx = player.posX - (ax + .5), dy = player.posY - (ay + .5), dz = player.posZ - (az + .5);
        return dx * dx + dy * dy + dz * dz <= 64;
    }

    private boolean stockLinksCurrent() {
        // Exact stock classes: native getters here are read-only fields or cached meta validity checks.
        return !controller.isInvalid() && !hatch.isInvalid() && controller.getWorldObj() == endpoint.world
            && hatch.getWorldObj() == endpoint.world && controller.xCoord == ax && controller.yCoord == ay
            && controller.zCoord == az && hatch.xCoord == hx && hatch.yCoord == hy && hatch.zCoord == hz
            && controller.getMetaTileID() == HologramStockCokeOvenProfile.CONTROLLER
            && hatch.getMetaTileID() == HologramStockCokeOvenProfile.HATCH
            && controller.getMetaTileEntity() == machine && machine.getBaseMetaTileEntity() == controller
            && hatch.getMetaTileEntity() == hatchMachine && hatchMachine.getBaseMetaTileEntity() == hatch
            && session.facing == facing.ordinal() && machine.getExtendedFacing() == facing
            && hatchMachine.mTier == HologramStockCokeOvenProfile.HATCH_TIER
            && hatch.getFrontFacing() != null && hatch.getFrontFacing().ordinal() == hatchFacing;
    }

    /** Read the original physical cells, not only cached native objects. Forge can replace either base while
     * the old tile remains valid in memory. A probe never loads a missing chunk, and touches only two positions. */
    private boolean liveStockCurrent() {
        World world = endpoint.world;
        return world.blockExists(ax, ay, az) && world.blockExists(hx, hy, hz)
            && world.getBlock(ax, ay, az) == GregTechAPI.sBlockMachines
            && world.getBlock(hx, hy, hz) == GregTechAPI.sBlockMachines
            && world.getTileEntity(ax, ay, az) == controller && world.getTileEntity(hx, hy, hz) == hatch
            && stockLinksCurrent();
    }

    State drain(long deadline, int entries, int commits) {
        return drain(deadline, entries, commits, HologramConstructionTaskGate::beginTransaction);
    }

    State drain(long deadline, int entries, int commits, PermitAdmission admission) {
        if (!gate.onOwnerThread()) throw new IllegalStateException("Native stock construction owner thread");
        if (entries < 1 || commits < 1) throw new IllegalArgumentException("Native stock slice quotas");
        if (state != State.ACTIVE || System.nanoTime() >= deadline) return state;
        if (advancing) throw new IllegalStateException("Native stock construction cannot re-enter a permit");
        advancing = true;
        try {
            if (!current(true)) return stop("原生补建的所有者、配置、仓室或现场变更。");
            int before = placed;
            for (int work = 0; work < Math.min(32, entries) && placed - before < commits
                && System.nanoTime() < deadline; work++) {
                if (activeElement == null) {
                    if (cursor.done() == cursor.total()) return finish();
                    int entry = cursor.done();
                    var element = profile.nativeElements()[entry];
                    position = false;
                    if (!bound(null) || !cursor.consumePinned(
                        endpoint.world, facing, ax, ay, az, receiver, entry, element) || !bound(null))
                        return stop("原生结构声明已改变。");
                    if (!position) continue;
                    if (!loaded || !HologramWorldFlow.worldPosition(x, y, z, endpoint.world.getHeight()))
                        return stop("原生结构所需区块未加载。");
                    activeElement = element;
                    activeEntry = entry;
                }
                if (!nextCell(deadline, admission)) return state;
            }
            return state;
        } catch (RuntimeException | LinkageError failure) {
            return stop("原生现场读取或施工失败，已停止追加格子。");
        } finally {
            advancing = false;
        }
    }

    private void locate(int x, int y, int z, boolean loaded) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.loaded = loaded;
        position = true;
    }

    private boolean nextCell(long deadline, PermitAdmission admission) {
        if (!current(false)) {
            stop("原生补建的所有者、配置、仓室或现场变更。");
            return false;
        }
        var world = endpoint.world;
        if (!world.blockExists(x, y, z)) {
            stop("原生结构所需区块已卸载。");
            return false;
        }
        var existing = world.getBlock(x, y, z);
        var tile = world.getTileEntity(x, y, z);
        if (activeElement == StructureUtility.isAir()) {
            if (existing == Blocks.air && tile == null) return advanceCell();
            stop("焦炉内部必须为空，未继续逐格补建。");
            return false;
        }
        // A non-stock GT base cannot provide the hatch identity; don't call into an unrelated tile.
        if (tile != null && tile.getClass() != BaseMetaTileEntity.class) {
            stop("焦炉存在不匹配的设备，未替换。");
            return false;
        }
        boolean selected = x == hx && y == hy && z == hz;
        var status = profile.classify(
            activeElement, existing, world.getBlockMetadata(x, y, z), tile,
            selected ? HologramStockCokeOvenProfile.HATCH : -1,
            selected ? HologramStockCokeOvenProfile.HATCH_TIER : -1, selected ? hatchFacing : -1);
        if (status == HologramStockCokeOvenProfile.Shell.MATCHED_CASING
            || status == HologramStockCokeOvenProfile.Shell.MATCHED_HATCH) return advanceCell();
        if (status != HologramStockCokeOvenProfile.Shell.READY_CASING) {
            stop("此焦炉格已占用或所选仓室不匹配，未替换。");
            return false;
        }
        var leaf = profile.casingLeaf(activeElement);
        if (leaf == null || !current(false)) {
            stop("原生焦炉声明或现场已变更。");
            return false;
        }
        if (System.nanoTime() >= deadline) return false;
        return transact(leaf, admission);
    }

    private boolean transact(IStructureElement<?> leaf, PermitAdmission admission) {
        var scratch = new HologramCapture(null, trigger);
        var cell = new HologramCapture.Cell(leaf, x, y, z, trigger);
        cell.block = Block.getBlockFromItem(casing.getItem());
        cell.meta = 0;
        cell.stableIndex = 0;
        scratch.cells.add(cell);
        if (!bound(null) || cell.block == null || !ItemStack.areItemStacksEqual(profile.casingItem(), casing)) {
            stop("原生焦炉砖材料或工作台已变更。");
            return false;
        }
        var permit = admission.acquire(gate, lease);
        if (permit == null) {
            // A queued owner STOP can defer the next permit before the control reaches the world thread.
            if (gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE)
                stop("本次逐格补建的许可已撤销。");
            return false;
        }
        boolean settled = false;
        try {
            session.capture = scratch;
            session.pins.put(0, casing);
            session.planTargets.add(casing);
            java.util.function.BooleanSupplier authority = () -> gate.transactionCurrent(permit) && bound(scratch)
                && profile.nativeElements()[activeEntry] == activeElement
                && session.pins.get(0) == casing && session.planTargets.size() == 1
                && session.planTargets.get(0) == casing && gate.transactionCurrent(permit);
            boolean wrote = HologramWorldTransactions.operateScoped(session, cell, 0, authority,
                () -> liveStockCurrent() && authority.getAsBoolean());
            if (session.job == 4 || HologramConstructionJournal.pending(endpoint.player)) return false;
            boolean committed = wrote && "placed".equals(cell.status);
            settled = gate.settle(permit, committed ? HologramConstructionTaskGate.Settlement.COMMITTED
                : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
            if (!settled) return false;
            if (committed) {
                placed++;
                return advanceCell();
            }
            // A refused check, missing stock, contested location or stopped permission is not completion.
            stop("焦炉本格未提交（" + cell.status + "）；已确认的施工前缀仍保留。");
            return false;
        } finally {
            if (session.capture == scratch) {
                session.capture = null;
                if (session.pins.get(0) == casing) session.pins.remove(0);
                if (session.planTargets.size() == 1 && session.planTargets.get(0) == casing) session.planTargets.clear();
            }
            if (!settled) {
                gate.settle(permit, HologramConstructionTaskGate.Settlement.RECOVERY_PENDING);
                unsettled = permit;
                state = State.RECOVERY_PENDING;
                reason = "施工结果待核定，已停止追加格子；请联系管理员 /gtmb recovery。";
            }
        }
    }

    private boolean advanceCell() {
        activeEntry = -1;
        activeElement = null;
        return true;
    }

    private State finish() {
        if (!current(false)) return stop("原生补建的所有者、配置、仓室或现场变更。");
        if (!gate.finish(lease, HologramConstructionTaskGate.End.COMPLETE))
            return stop("施工许可已撤销，未确认完成。");
        state = State.COMPLETE;
        return state;
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
        reason = "已停止逐格补建，已确认的施工前缀仍保留。";
        return true;
    }

    int visited() {
        return cursor.done();
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
