// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;
import com.miaokatze.gtmb.hologram.HologramStockNativeConstruction.PermitAdmission;
import com.miaokatze.gtmb.hologram.HologramStockNativeConstruction.State;

/** Closed server-thread driver for the qualified stock #1000. No existing packet or UI routes here. */
final class HologramStockEbfConstruction {

    private final HologramWorkspace.ActionEndpoint endpoint;
    private final HologramConstructionTaskGate gate;
    private final HologramConstructionTaskGate.Lease lease;
    private final HologramSession session;
    private final HologramStockEbfProfile.Qualified site;
    private final HologramStructureCursor cursor;
    private final ExtendedFacing facing;
    private final HologramMaterials.Settings settings;
    private final InventoryPlayer inventory;
    private final ItemStack[] mainInventory;
    private final ItemStack projector, pinnedTool, trigger;
    private final NBTTagCompound channels;
    private final long epoch, plan;
    private final int ax, ay, az, side, slot, main, priority, recoveryDestination;
    private final boolean mainExplicit, noHatches;
    private int cx, cy, cz, activeRow = -1, placed;
    private boolean positioned, loaded, advancing;
    private State state = State.ACTIVE;
    private String reason = "";
    private HologramConstructionTaskGate.Permit unresolved;

    private final IStructureWalker<Object> receiver = new IStructureWalker<Object>() {

        @Override
        public boolean visit(IStructureElement<Object> element, World world, int x, int y, int z, int a, int b, int c) {
            locate(x, y, z, true);
            return true;
        }

        @Override
        public boolean blockNotLoaded(IStructureElement<Object> element, World world, int x, int y, int z,
            int a, int b, int c) {
            locate(x, y, z, false);
            return true;
        }
    };

    HologramStockEbfConstruction(HologramWorkspace.ActionEndpoint endpoint, HologramConstructionTaskGate gate,
        HologramConstructionTaskGate.Lease lease, HologramSession session, HologramStockEbfProfile.Qualified site) {
        this.endpoint = endpoint;
        this.gate = gate;
        this.lease = lease;
        this.session = session;
        this.site = site;
        cursor = site.cursor();
        facing = session.machine.getExtendedFacing();
        settings = session.materialSettings;
        inventory = session.player.inventory;
        mainInventory = inventory.mainInventory;
        projector = session.boundTool;
        pinnedTool = projector.copy();
        trigger = HologramSessionPolicy.trigger(session);
        channels = (NBTTagCompound) session.channels.copy();
        epoch = session.editorWorkspaceEpoch;
        plan = session.planRevision;
        ax = session.x;
        ay = session.y;
        az = session.z;
        side = session.side;
        slot = session.slot;
        main = session.main;
        mainExplicit = session.mainExplicit;
        noHatches = session.noHatches;
        priority = settings.priority;
        recoveryDestination = settings.recoveryDestination;
    }

    boolean admissionCurrent() {
        return current(true) && site.fullyFunded(inventory, slot, settings) && site.armWriter() && bound(null) && permitted();
    }

    private boolean permitted() {
        return gate.query(lease.scope, lease.request).phase == HologramConstructionTaskGate.Phase.ACTIVE;
    }

    private boolean transportCurrent() {
        // An isOpen implementation can reenter. Never call it after a final raw world witness.
        return HologramWorkspace.ebfActionCurrentPure(endpoint, gate)
            && HologramWorkspace.currentActionEndpoint(endpoint) && HologramWorkspace.ebfActionCurrentPure(endpoint, gate);
    }

    private boolean bound(HologramCapture capture) {
        var actor = endpoint.player;
        var area = HologramWorkspace.AREAS.get(endpoint.actor);
        double dx = actor.posX - (ax + .5), dy = actor.posY - (ay + .5), dz = actor.posZ - (az + .5);
        return state == State.ACTIVE && HologramWorkspace.ebfActionCurrentPure(endpoint, gate)
            && HologramWorkspace.find(endpoint.actor) == session && session.stockNativePrivate
            && (area == null || !area.selecting && !area.importing)
            && !session.blueprintDirty && session.blueprintSaveReceipt.isEmpty()
            && session.currentPlacementTask == null && session.executionStart == null
            && session.nativeReadyPlacement == null && session.nativeCompactStart == null
            && session.presentationTransition == null
            && !actor.isDead && actor.capabilities.allowEdit && !actor.capabilities.isCreativeMode
            && dx * dx + dy * dy + dz * dz <= 64
            && session.editorWorkspaceEpoch == epoch && session.planRevision == plan && session.capture == capture
            && session.x == ax && session.y == ay && session.z == az && session.side == side && session.slot == slot
            && session.facing == facing.ordinal() && session.mode == 0 && session.scope == 0 && session.job == 0
            && !session.virtual && !session.cancelled && !session.hatchDowngrade && !session.localPlanPending
            && session.areaStructureId.isEmpty() && session.main == main && session.mainExplicit == mainExplicit
            && session.noHatches == noHatches && channels.equals(session.channels)
            && site.boundSession(session) && site.linksCurrent()
            && session.coordinatePins.isEmpty() && session.facingPins.isEmpty() && session.manualPositions.isEmpty()
            && session.nativeOperationInputs == null && session.pendingAssetBudget.isEmpty()
            && session.cellAssetBudgets.isEmpty() && !session.jobVoucherRequested && !StructureLibAPI.isInstrumentEnabled()
            && session.materialSettings == settings && settings.main && !settings.containers && !settings.me
            && settings.priority == priority && priority == 0 && settings.recoveryDestination == recoveryDestination
            && actor.inventory == inventory && inventory.mainInventory == mainInventory && inventory.currentItem == slot
            && slot >= 0 && slot < mainInventory.length && mainInventory[slot] == projector
            && ItemStack.areItemStacksEqual(projector, pinnedTool)
            && (capture != null || session.pins.isEmpty() && session.planTargets.isEmpty());
    }

    private boolean current(boolean journal) {
        return bound(null) && permitted() && (!journal || !HologramProjectedLayerService.hasKnownRecovery(endpoint.player))
            && site.physicalCurrent() && transportCurrent() && bound(null) && permitted();
    }

    State drain(long deadline, int entries, int commits, PermitAdmission admission) {
        if (!gate.onOwnerThread()) throw new IllegalStateException("Native EBF construction owner thread");
        if (entries < 1 || commits < 1) throw new IllegalArgumentException("Native EBF slice quotas");
        if (state != State.ACTIVE || System.nanoTime() >= deadline) return state;
        if (advancing) throw new IllegalStateException("Native EBF construction cannot re-enter a permit");
        advancing = true;
        try {
            if (!current(true)) return stop("高炉的线圈、仓室、持有工具或现场已变化。");
            int before = placed;
            for (int work = 0; work < Math.min(32, entries) && placed - before < commits
                && System.nanoTime() < deadline; work++) {
                if (activeRow < 0) {
                    if (cursor.done() == cursor.total()) return finish();
                    int index = cursor.done();
                    positioned = false;
                    if (!bound(null) || !cursor.consumePinned(endpoint.world, facing, ax, ay, az, receiver, index,
                        site.element(index)) || !bound(null)) return stop("原生高炉声明已变化。");
                    if (!positioned) continue;
                    if (!loaded) return stop("高炉所需区块未加载。");
                    activeRow = index;
                }
                var row = site.classify(activeRow, cx, cy, cz);
                if (!bound(null) || !permitted() || row == HologramStockEbfProfile.CellState.BLOCKED)
                    return stop("高炉格已占用，或当前已安装设备、核心空位或线圈不匹配；未替换。");
                if (row == HologramStockEbfProfile.CellState.MATCH) {
                    activeRow = -1;
                    continue;
                }
                var prepared = site.prepare(activeRow, cx, cy, cz, trigger);
                if (prepared == null || !current(false)) return stop("原生高炉材料或现场已改变。");
                if (System.nanoTime() >= deadline || !transact(prepared, admission)) return state;
            }
            return state;
        } catch (RuntimeException | LinkageError failure) {
            return stop("原生高炉现场读取或施工失败，已停止追加格子。");
        } finally {
            advancing = false;
        }
    }

    private void locate(int x, int y, int z, boolean loaded) {
        cx = x;
        cy = y;
        cz = z;
        this.loaded = loaded;
        positioned = true;
    }

    private boolean transact(HologramStockEbfProfile.PreparedCell prepared, PermitAdmission admission) {
        var scratch = new HologramCapture(null, trigger);
        scratch.cells.add(prepared.cell);
        if (!bound(null)) {
            stop("原生高炉工作台已变化。");
            return false;
        }
        var permit = admission.acquire(gate, lease);
        if (permit == null) {
            if (!permitted()) stop("本次高炉逐格施工的许可已撤销。");
            return false;
        }
        boolean settled = false;
        try {
            session.capture = scratch;
            session.pins.put(0, prepared.material);
            session.planTargets.add(prepared.material);
            java.util.function.BooleanSupplier authority = () -> gate.transactionCurrent(permit) && bound(scratch)
                && session.pins.get(0) == prepared.material && session.planTargets.size() == 1
                && session.planTargets.get(0) == prepared.material && gate.transactionCurrent(permit);
            boolean result = HologramWorldTransactions.operateScopedEbf(session, prepared, authority,
                () -> site.physicalCurrent() && authority.getAsBoolean(), this::transportCurrent);
            if (session.job == 4 || HologramConstructionJournal.pending(endpoint.player)) return false;
            boolean committed = result && "placed".equals(prepared.cell.status);
            settled = gate.settle(permit, committed ? HologramConstructionTaskGate.Settlement.COMMITTED
                : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
            if (!settled) return false;
            if (committed) {
                placed++;
                activeRow = -1;
                return true;
            }
            stop("高炉本格未提交（" + prepared.cell.status + "）；已确认的施工前缀仍保留。");
            return false;
        } finally {
            if (session.capture == scratch) {
                session.capture = null;
                if (session.pins.get(0) == prepared.material) session.pins.remove(0);
                if (session.planTargets.size() == 1 && session.planTargets.get(0) == prepared.material)
                    session.planTargets.clear();
            }
            if (!settled) {
                gate.settle(permit, HologramConstructionTaskGate.Settlement.RECOVERY_PENDING);
                unresolved = permit;
                state = State.RECOVERY_PENDING;
                reason = "高炉施工结果待核定，已停止追加格子；请联系管理员 /gtmb recovery。";
            }
        }
    }

    private State finish() {
        if (!current(false)) return stop("高炉的线圈、仓室或现场已变化。");
        if (!gate.finish(lease, HologramConstructionTaskGate.End.COMPLETE)) return stop("施工许可已撤销，未确认完成。");
        state = State.COMPLETE;
        return state;
    }

    private State stop(String why) {
        if (state == State.ACTIVE) {
            gate.finish(lease, HologramConstructionTaskGate.End.FAILED);
            state = State.STOPPED;
            reason = why;
        }
        return state;
    }

    boolean cancel() {
        if (state != State.ACTIVE || !gate.cancel(lease.scope, lease.request)) return false;
        state = State.STOPPED;
        reason = "已停止高炉逐格补建，已确认的施工前缀仍保留。";
        return true;
    }

    int placed() {
        return placed;
    }

    String reason() {
        return reason;
    }

    HologramConstructionTaskGate.Permit unsettled() {
        return unresolved;
    }
}
