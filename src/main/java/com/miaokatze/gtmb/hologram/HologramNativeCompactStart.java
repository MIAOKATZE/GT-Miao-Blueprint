// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.api.StructureSources;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** One explicit native intent owns capture then ready admission, never a second player queue or a quote. */
final class HologramNativeCompactStart {

    private static final long LIFETIME = 30_000_000_000L;

    private final HologramSession session, previous;
    private final String id;
    private final long started = System.nanoTime(), sourceRevision = StructureSources.revision();
    private final int facing;
    private final boolean ordinary, creative;
    private final HologramNativeReadyPlacement.Receipt receipt;
    private boolean collecting, admitting;

    private HologramNativeCompactStart(HologramSession s, HologramSession previous, String id,
        HologramNativeReadyPlacement.Receipt receipt, boolean ordinary) {
        session = s;
        this.previous = previous;
        this.id = id;
        this.receipt = receipt;
        facing = s.facing;
        this.ordinary = ordinary;
        creative = s.player.capabilities.isCreativeMode;
    }

    static void receive(EntityPlayerMP p, NBTTagCompound request) {
        receive(p, request, (player, id, accepted, reason) -> {
            if (!accepted) {
                HologramProjectedPlacementService.reply(player, id, false, reason);
                return;
            }
            HologramSession s = HologramWorkspace.find(player.getUniqueID());
            if (s == null || s.player != player || !id.equals(s.nativeCompactRequestId)) return;
            NBTTagCompound state = new NBTTagCompound();
            state.setString("placementRequestId", id);
            state.setBoolean("placementRequestAccepted", true);
            state.setBoolean("nativeCompactStarted", true);
            state.setInteger("slot", s.slot);
            state.setString("toolId", s.toolId);
            HologramNetwork.sendState(player, state);
        });
    }

    static void receive(EntityPlayerMP p, NBTTagCompound r, HologramNativeReadyPlacement.Receipt receipt) {
        String id = r.getString("placementRequestId");
        if (!canonical(id) || HologramProjectedPlacementService.consumed(p, id)) return;
        HologramProjectedPlacementService.retire(p, id);
        try {
            prepare(p, r, id, receipt);
        } catch (RuntimeException | LinkageError failed) {
            HologramSession current = HologramWorkspace.find(p.getUniqueID());
            String detail = "原生补建准备失败：" + HologramAePolicy.fallback(failed.getMessage());
            if (current != null && current.nativeCompactStart != null && current.nativeCompactStart.id.equals(id))
                current.nativeCompactStart.finish(false, detail);
            else receipt.send(p, id, false, detail);
        }
    }

    private static void prepare(EntityPlayerMP p, NBTTagCompound r, String id,
        HologramNativeReadyPlacement.Receipt receipt) {
        boolean ordinary = HologramCompactNativeRequest.ordinary(r);
        if ((!ordinary && !HologramCompactNativeRequest.enabled()) || !HologramCompactNativeRequest.valid(r)) {
            receipt.send(p, id, false, "紧凑原生放置未启用或选项不受支持。");
            return;
        }
        HologramSession previous = HologramWorkspace.find(p.getUniqueID());
        if (previous != null
            && (previous.player != p || !HologramSessionPolicy.valid(previous)
                || !HologramEditorWorkspaceRegistry.idle(previous)
                || previous.nativeReadyPlacement != null
                || previous.nativeCompactStart != null
                || previous.presentationTransition != null)
            || HologramProjectedPlacementService.preparing(p)
            || HologramPlacementUpload.active(p)
            || HologramDemolition.active(p)
            || HologramAeCraftingOrders.active(p)
            || p.isDead
            || p.inventory.currentItem != r.getInteger("slot")
            || !p.capabilities.allowEdit
            || (!ordinary && p.capabilities.isCreativeMode)
            || !isHeldProjector(p)) {
            receipt.send(p, id, false, "当前投影仪或工作台正在处理其他操作。");
            return;
        }
        int x = r.getInteger("x"), y = r.getInteger("y"), z = r.getInteger("z");
        if (!HologramWorldFlow.worldPosition(x, y, z, p.worldObj.getHeight()) || !p.worldObj.blockExists(x, y, z)
            || p.getDistanceSq(x + .5, y + .5, z + .5) > 64
            || !(p.worldObj.getTileEntity(x, y, z) instanceof IGregTechTileEntity nativeTile)
            || nativeTile.getMetaTileID() != r.getInteger("controller")
            || !(nativeTile
                .getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>unit)
            || unit.getExtendedFacing()
                .ordinal() != r.getInteger("facing")) {
            receipt.send(p, id, false, "现场原生控制器或朝向已变化。");
            return;
        }
        var source = ordinary ? null : StructureSources.compactNative(unit.getClass());
        var channels = r.getCompoundTag("channels");
        if ((!ordinary && (source == null || !source.accepts(r.getInteger("main"), channels)))
            || HologramConstructionJournal.pending(p)) {
            receipt.send(p, id, false, "未认证此原生定义、信道或当前施工恢复状态。");
            return;
        }
        HologramSession s = new HologramSession(p, x, y, z, true, r.getInteger("side"));
        s.main = r.getInteger("main");
        s.mainExplicit = r.getBoolean("mainExplicit");
        s.channels = (NBTTagCompound) channels.copy();
        s.noHatches = r.getBoolean("noHatches");
        s.materialSettings.main = r.getBoolean("materialMain");
        s.materialSettings.containers = r.getBoolean("materialContainers");
        s.materialSettings.me = r.getBoolean("materialMe");
        s.materialSettings.priority = r.getInteger("materialPriority");
        // The attempt only observes current stock; only the admitted job may flush pending recovery into inventory.
        s.materialContext = new HologramMaterials.Context(p, s.materialSettings, s.slot);
        s.nativeCompactSource = source;
        s.nativeCompactStart = new HologramNativeCompactStart(s, previous, id, receipt, ordinary);
        if (!HologramWorkspace.reserveCompactNative(previous, s)) {
            s.nativeCompactStart = null;
            receipt.send(p, id, false, "原生放置工作台已变化。");
        }
    }

    private static boolean isHeldProjector(EntityPlayerMP p) {
        return com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(p.getHeldItem());
    }

    static boolean ordinary(HologramSession s) {
        return s.nativeCompactStart != null && s.nativeCompactStart.ordinary;
    }

    static boolean canonical(String id) {
        try {
            return id.length() == 36 && UUID.fromString(id)
                .toString()
                .equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean tick(HologramSession s) {
        HologramNativeCompactStart task = s.nativeCompactStart;
        if (task == null) return false;
        if (!task.current()) {
            task.finish(false, "原生控制器、投影仪或声明已变化。");
            return true;
        }
        if (task.admitting) {
            HologramNativeReadyPlacement.tick(
                s,
                256,
                HologramWorkBudget.deadline(HologramWorkBudget.SLICE_NANOS),
                System::nanoTime,
                (p, id, accepted, reason) -> task.finish(accepted, reason));
            return true;
        }
        HologramControllerCapture.collect(s, !task.collecting);
        task.collecting = true;
        if (!s.capturePending) {
            if (task.ordinary && (s.capture == null || s.capture.incomplete || !s.captureError.isEmpty())) {
                task.finish(false, !s.captureError.isEmpty() ? s.captureError
                    : s.capture == null || s.capture.failure.isEmpty() ? "原生结构采集未完成" : s.capture.failure);
            } else if (task.ordinary && !s.capture.readyPlacementSource(s)) {
                task.finish(false, "当前原生结构使用了需要独立审核的提供器或现场规则，未开始补建");
            } else if (task.ordinary && s.planMissing > 0) {
                task.finish(false, "所选来源缺少 " + s.planMissing + " 件材料，未开始补建");
            } else if (!HologramNativeReadyPlacement.admitPrepared(s, task.id)) {
                task.finish(false, "服务端未能准备支持的现场或背包材料计划。");
            } else task.admitting = true;
        }
        return true;
    }

    private boolean current() {
        HologramSession s = session;
        return System.nanoTime() - started < LIFETIME && s.job == 0
            && !s.cancelled
            && s.machine != null
            && HologramWorkspace.find(s.player.getUniqueID()) == s
            && HologramSessionPolicy.valid(s)
            && s.player.capabilities.allowEdit
            && s.player.capabilities.isCreativeMode == creative
            && s.machine.getExtendedFacing()
                .ordinal() == facing
            && s.captureError.isEmpty()
            && StructureSources.revision() == sourceRevision
            && (ordinary || StructureSources.compactNative(s.machine.getClass()) == s.nativeCompactSource)
            && s.player.getDistanceSq(s.x + .5, s.y + .5, s.z + .5) <= 64;
    }

    private void finish(boolean accepted, String reason) {
        HologramSession s = session;
        if (s.nativeCompactStart != this) return;
        s.nativeCompactStart = null;
        s.nativeReadyPlacement = null;
        if (accepted) {
            s.nativeCompactRequestId = id;
            HologramWorkspace.commitCompactNative(previous, s);
        } else {
            HologramSession.cancelCaptureRetry(s);
            HologramWorkspace.rollbackCompactNative(previous, s);
        }
        receipt.send(s.player, id, accepted, reason);
    }

    static boolean cancel(EntityPlayerMP p, String id) {
        HologramSession s = HologramWorkspace.find(p.getUniqueID());
        if (s == null || s.player != p || s.nativeCompactStart == null || !s.nativeCompactStart.id.equals(id))
            return false;
        s.nativeCompactStart.finish(false, "原生放置已取消。");
        return true;
    }

    static void cancel(HologramSession s) {
        if (s.nativeCompactStart != null) s.nativeCompactStart.finish(false, "原生放置工作台已关闭。");
    }
}
