package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Virtual blueprint positioning, rotation and world placement confirmation. */
final class HologramWorldPlacementWorkflow {

    private HologramWorldPlacementWorkflow() {}

    static void openVirtual(EntityPlayerMP player, int x, int y, int z, int side, String presetId) {
        HologramSession previous = HologramWorkspace.find(player.getUniqueID());
        if (previous != null && previous.player == player && HologramSessionPolicy.blockedByAreaImport(previous)) {
            HologramSnapshots.send(previous);
            return;
        }
        if (previous != null && previous.player == player && (previous.job == 1 || previous.job == 2)) {
            previous.status = "请先取消当前施工任务";
            HologramSnapshots.send(previous);
            return;
        }
        HologramSession s = HologramWorkspace.create(player, x, y, z, false, side);
        s.virtual = true;
        s.virtualPlacement = false;
        s.blueprintAwaitingConfirmation = false;
        s.open = true;
        HologramWorkspace.activate(player.getUniqueID(), s);
        if (!HologramSessionPolicy.valid(s) || !player.worldObj.blockExists(x, y, z)) {
            s.cancelled = true;
            s.status = "虚拟锚点未加载、超出高度或距离限制";
            HologramSnapshots.send(s);
            return;
        }
        s.presetBrowseForPlacement = true;
        s.status = "请选择蓝图并点击放置；预览位置后右击确定，再核对材料并确认施工";
        HologramSnapshots.send(s);
    }

    /** Last selection belongs to the held logical tool; shortcut loading uses the ordinary validated preset path. */
    static void quickPlaceLastBlueprint(EntityPlayerMP player, NBTTagCompound request) {
        if (HologramLibraryFacade.enabled() && !HologramLibraryService.connectedProjector(player)) return;
        HologramSession previous = HologramProjectorWorkflow.uiSession(player);
        if (!HologramSessionPolicy.valid(previous)) return;
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (previous.job == 1 || previous.job == 2
            || area != null && (area.selecting || area.importing)
            || previous.virtual && previous.virtualPlacement
                && previous.job != 3
                && previous.job != 4
                && previous.job != 5) {
            previous.status = "请先完成当前框选或放置，再快捷放置蓝图";
            HologramPresetWorkflow.presetGuidance(previous, previous.status);
            HologramSnapshots.send(previous);
            return;
        }
        String id = HologramSessionPolicy.selectedPreset(previous);
        if (HologramLibraryFacade.enabled() && !request.getString("publicPresetId")
            .isEmpty()) {
            try {
                id = HologramLibraryService.publicId(request.getString("publicPresetId"));
                if (HologramLibraryFacade.get(player, id) == null) throw new java.io.IOException("上次选择的服务器蓝图已删除或尚未加载");
            } catch (java.io.IOException invalid) {
                previous.status = "快捷放置失败：" + invalid.getMessage();
                HologramPresetWorkflow.presetGuidance(previous, previous.status);
                HologramSnapshots.send(previous);
                return;
            }
        }
        if (id.isEmpty()) {
            previous.status = "尚未选择有效蓝图，或上次选择已删除；普通右键打开蓝图库重新选择后可用 Alt + 右键快捷放置";
            HologramPresetWorkflow.presetGuidance(previous, previous.status);
            HologramSnapshots.send(previous);
            return;
        }
        int x = request.getInteger("x"), y = request.getInteger("y"), z = request.getInteger("z");
        if (!request.hasKey("x", 3) || !request.hasKey("y", 3)
            || !request.hasKey("z", 3)
            || !request.hasKey("side", 3)
            || request.getInteger("side") < 0
            || request.getInteger("side") > 5
            || !HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
            || !HologramSessionPolicy.previewReach(player, x, y, z)
            || !player.worldObj.blockExists(x, y, z)) {
            previous.status = "快捷放置位置未加载或超出范围；请靠近目标后重试";
            HologramPresetWorkflow.presetGuidance(previous, previous.status);
            HologramSnapshots.send(previous);
            return;
        }
        try {
            if (HologramLibraryFacade.get(player, id) == null) throw new java.io.IOException("蓝图不存在");
        } catch (java.io.IOException unavailable) {
            previous.status = "快捷放置失败：" + unavailable.getMessage();
            HologramPresetWorkflow.presetGuidance(previous, previous.status);
            HologramSnapshots.send(previous);
            return;
        }
        if (!HologramAuthoringWorkflow.allowBlueprintDiscard(previous, request, "choosePlacementPreset", id)) {
            previous.open = true;
            HologramSnapshots.send(previous);
            return;
        }
        HologramSession placement = HologramWorkspace.create(player, x, y, z, false, request.getInteger("side"));
        HologramLibraryWorkflow.copyLibraryIntent(previous, placement);
        placement.virtual = true;
        HologramPresetWorkflow.loadPreset(placement, id, false);
        if (placement.virtualPreset == null || placement.capture == null
            || placement.capture.incomplete
            || !virtualBoundsValid(placement)
            || !beginVirtualPlacement(placement, x, y, z, placement.side)) {
            previous.status = "上次选择的蓝图不可快捷放置：" + placement.status + "；普通右键打开蓝图库重新选择";
            HologramPresetWorkflow.presetGuidance(previous, previous.status);
            HologramSnapshots.send(previous);
            return;
        }
        HologramWorkspace.clearAreaSelection(previous);
        placement.openPurpose = "blueprintPlacement";
        HologramWorkspace.activate(player.getUniqueID(), placement);
        HologramSnapshots.send(placement);
    }

    static boolean virtualBoundsValid(HologramSession s) {
        for (HologramCapture.Cell cell : s.capture.cells) {
            if (HologramSessionPolicy.ignoredPlacementAir(s, cell, cell.stableIndex)) continue;
            if (!HologramWorldFlow.worldPosition(cell.x, cell.y, cell.z, s.player.worldObj.getHeight())) {
                s.status = "蓝图结构范围超出世界边界或高度";
                return false;
            }
        }
        return true;
    }

    static void lockVirtual(EntityPlayerMP player, int x, int y, int z, int side) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s != null && s.player == player && HologramSessionPolicy.blockedByAreaImport(s)) {
            HologramSnapshots.send(s);
            return;
        }
        if (s == null || s.player != player
            || s.cancelled
            || !s.virtual
            || !s.virtualPlacement
            || s.virtualPreset == null
            || !HologramSessionPolicy.valid(s)
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || s.virtualLocked
            || s.capture == null
            || s.capture.incomplete
            || s.capture.pending
            || s.capturePending) return;
        if (!moveVirtual(s, x, y, z, side)) {
            s.status = "该位置或结构范围不合法，预览与蓝图保留；请调整位置后重试";
            HologramProjectorWorkflow.libraryCloseUi(s);
            HologramSnapshots.send(s);
            return;
        }
        s.virtualLocked = true;
        HologramSessionPolicy.clearPlacementSelection(s);
        HologramProjectorWorkflow.libraryCloseUi(s);
        s.blueprintAwaitingConfirmation = true;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        HologramResourcePlanner.refreshPlan(s);
        s.openPurpose = "";
        s.placementReviewNonce++;
        s.status = "位置已锁定；Shift + 右键取消锁定，普通右键打开材料与 NBT 放置确认";
        HologramSnapshots.send(s);
    }

    static void confirmWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s == null || s.player != player || !s.id.equals(request.getString("session"))) {
            rejectPositionLock(player, request, "位置锁定未执行；当前会话已变化，请重新打开蓝图");
            return;
        }
        // A retried delivery acknowledges the same visual intent; it cannot become the second world click.
        if (s.virtualLocked && request.hasKey("uiSequence", 4)
            && request.getLong("uiSequence") == s.uiSequence
            && HologramSessionPolicy.valid(s)) {
            HologramSnapshots.send(s);
            return;
        }
        if (HologramSessionPolicy.blockedByAreaImport(s) || s.cancelled
            || !s.virtual
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem())
            || !s.virtualPlacement
            || s.virtualLocked
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || !HologramSessionPolicy.valid(s)
            || request.hasKey("uiSequence", 4) && request.getLong("uiSequence") <= s.uiSequence) {
            rejectPositionLock(player, request, "位置锁定未执行；当前会话或放置状态已变化，请重新确定位置");
            return;
        }
        if (request.hasKey("uiSequence", 4)) s.uiSequence = request.getLong("uiSequence");
        String failure = null;
        int x = request.getInteger("x"), y = request.getInteger("y"), z = request.getInteger("z");
        if (!request.hasKey("planRevision", 4) || s.planRevision != request.getLong("planRevision"))
            failure = "蓝图计划已更新，预览与蓝图保留；请再次确定位置";
        else if (!request.hasKey("x", 3) || !request.hasKey("y", 3)
            || !request.hasKey("z", 3)
            || !request.hasKey("side", 3)
            || request.getInteger("side") < 0
            || request.getInteger("side") > 5) failure = "位置数据不完整，预览与蓝图保留；请重新确定位置";
        else if (!HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight()))
            failure = "目标超出世界边界或高度，预览与蓝图保留；请调整位置";
        else if (!HologramSessionPolicy.previewReach(player, x, y, z)) failure = "目标超出放置距离，预览与蓝图保留；请靠近目标或缩短滚轮距离";
        else if (!player.worldObj.blockExists(x, y, z)) failure = "目标区块尚未加载，预览与蓝图保留；请靠近后重试";
        else if (s.capture == null || s.capture.incomplete || s.capture.pending || s.capturePending)
            failure = "蓝图采集尚未完整，预览与蓝图保留；请等待后重试";
        if (failure != null) {
            s.status = failure;
            rejectPositionLock(player, request, failure);
            HologramProjectorWorkflow.libraryCloseUi(s);
            HologramSnapshots.send(s);
            return;
        }
        try {
            lockVirtual(
                player,
                request.getInteger("x"),
                request.getInteger("y"),
                request.getInteger("z"),
                request.getInteger("side"));
            if (!s.virtualLocked) rejectPositionLock(player, request, s.status);
        } catch (RuntimeException failureDuringLock) {
            s.virtualLocked = false;
            s.blueprintAwaitingConfirmation = false;
            s.placementReviewIssued = false;
            s.forcePlacementReady = false;
            s.placementReviewNonce++;
            s.status = "位置核对失败，预览与蓝图保留；请重新确定位置";
            rejectPositionLock(player, request, s.status);
            HologramNetwork.transportFailure(player, s.id, s.uiSequence, "positionLock", failureDuringLock);
        }
    }

    private static void rejectPositionLock(EntityPlayerMP player, NBTTagCompound request, String message) {
        NBTTagCompound rejected = new NBTTagCompound();
        rejected.setString("scope", "server");
        rejected.setString("session", request.getString("session"));
        if (request.hasKey("uiSequence", 4)) rejected.setLong("uiSequence", request.getLong("uiSequence"));
        if (request.hasKey("planRevision", 4)) rejected.setLong("planRevision", request.getLong("planRevision"));
        rejected.setString(
            "action",
            request.getString("action")
                .isEmpty() ? "confirmVirtualPosition" : request.getString("action"));
        rejected.setString("phase", "request.rejected");
        rejected.setString("outcome", "rejected");
        rejected.setString("status", message);
        NBTTagCompound envelope = new NBTTagCompound();
        envelope.setBoolean("progressOnly", true);
        envelope.setTag("progress", rejected);
        HologramWorkspace.snapshotSink()
            .send(player, envelope);
    }

    /** Unlock invalidates every prior material confirmation capability while retaining the editable draft. */
    static void unlockWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s == null || s.player != player
            || s.cancelled
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || s.planRevision != request.getLong("planRevision")
            || !HologramSessionPolicy.valid(s)
            || !s.virtual
            || !s.virtualPlacement
            || !s.virtualLocked
            || s.virtualPreset == null
            || s.job == 1
            || s.job == 2
            || s.job == 4) return;
        if (HologramSessionPolicy.blockedByAreaImport(s)) {
            HologramSnapshots.send(s);
            return;
        }
        s.virtualLocked = false;
        s.blueprintAwaitingConfirmation = false;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        s.openPurpose = "";
        s.placementReviewNonce++;
        s.ae.invalidateReviews();
        HologramResourcePlanner.refreshPlan(s);
        HologramProjectorWorkflow.libraryCloseUi(s);
        s.status = "已取消位置锁定；移动鼠标调整预览，右键重新锁定，Esc 取消放置";
        HologramSnapshots.send(s);
    }

    /** Allow two adjacent presses without assuming client and server ticks share a phase. */
    static boolean acceptNudgeRate(HologramSession s, long tick) {
        if (s.positionNudgeRefillTick == Long.MIN_VALUE || tick < s.positionNudgeTick) {
            s.positionNudgeRefillTick = tick;
            s.positionNudgeCredits = 2;
        } else {
            long elapsed = tick - s.positionNudgeRefillTick;
            if (elapsed >= HologramWorldFlow.NUDGE_REPEAT_INTERVAL) {
                long refill = elapsed / HologramWorldFlow.NUDGE_REPEAT_INTERVAL;
                s.positionNudgeCredits = Math.min(2, s.positionNudgeCredits + (int) Math.min(2, refill));
                s.positionNudgeRefillTick = tick - elapsed % HologramWorldFlow.NUDGE_REPEAT_INTERVAL;
            }
        }
        if (s.positionNudgeCredits == 0) return false;
        s.positionNudgeCredits--;
        s.positionNudgeTick = tick;
        return true;
    }

    /** Adjust only a locked, unpaid preview; every previous construction and AE review capability expires. */
    static void nudgeWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s == null || s.player != player
            || s.cancelled
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || s.planRevision != request.getLong("planRevision")
            || !HologramSessionPolicy.valid(s)
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem())
            || !s.virtual
            || !s.virtualPlacement
            || !s.virtualLocked
            || s.job != 0
            || s.virtualPreset == null
            || s.capture == null
            || s.capture.incomplete
            || s.capture.pending
            || s.capturePending
            || HologramSessionPolicy.blockedByAreaImport(s)
            || !request.hasKey("dx", 3)
            || !request.hasKey("dy", 3)
            || !request.hasKey("dz", 3)) return;
        int dx = request.getInteger("dx"), dy = request.getInteger("dy"), dz = request.getInteger("dz");
        if (!HologramWorldFlow.unitNudge(dx, dy, dz)) return;
        long tick = player.worldObj.getTotalWorldTime();
        if (!acceptNudgeRate(s, tick)) return;
        int x = s.x + dx, y = s.y + dy, z = s.z + dz;
        boolean allowed = HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
            && HologramSessionPolicy.previewReach(player, x, y, z)
            && player.worldObj.blockExists(x, y, z);
        if (allowed) for (HologramCapture.Cell cell : s.capture.cells) {
            long xx = (long) cell.x + dx, yy = (long) cell.y + dy, zz = (long) cell.z + dz;
            if (xx < -30000000L || xx >= 30000000L
                || yy < 0
                || yy >= player.worldObj.getHeight()
                || zz < -30000000L
                || zz >= 30000000L
                || !player.worldObj.blockExists((int) xx, (int) yy, (int) zz)) {
                allowed = false;
                break;
            }
        }
        if (!allowed || !moveVirtual(s, x, y, z, s.side)) {
            HologramProjectorWorkflow.libraryCloseUi(s);
            s.status = "微调位置超出距离、世界边界或已加载区域；原锁定位置与蓝图保留";
            HologramSnapshots.send(s);
            return;
        }
        s.blueprintAwaitingConfirmation = true;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        s.openPurpose = "";
        s.placementReviewNonce++;
        s.ae.invalidateReviews();
        HologramProjectorWorkflow.libraryCloseUi(s);
        s.status = "锁定位置已微调一格；箭头水平微调，Alt + 上下调整高度；右键重新核对材料与 NBT";
        HologramSnapshots.send(s);
    }

    /** Escape exits to the world, revokes placement authority and retains the draft without changing paid blocks. */
    static void cancelWorldPlacement(EntityPlayerMP player, NBTTagCompound request) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        boolean cancellingLock = s != null && request.hasKey("positionLockSequence", 4)
            && request.getLong("positionLockSequence") > 0
            && request.getLong("positionLockSequence") == s.uiSequence
            && request.hasKey("planRevision", 4)
            && request.getLong("planRevision") <= s.planRevision;
        if (s == null || s.player != player
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || s.planRevision != request.getLong("planRevision") && !cancellingLock
            || !HologramSessionPolicy.valid(s) && !(cancellingLock && !player.isDead && player.dimension == s.dimension)
            || s.cancelled
            || !s.virtual
            || !s.virtualPlacement
            || s.job == 1
            || s.job == 2
            || s.job == 4) {
            if (request.hasKey("positionLockSequence", 4)) {
                NBTTagCompound proof = new NBTTagCompound();
                proof.setString("session", request.getString("session"));
                proof.setString("action", "cancelVirtualPlacement");
                proof.setLong("uiSequence", request.getLong("positionLockSequence"));
                rejectPositionLock(player, proof, "取消放置未执行；当前会话或施工状态已变化");
            }
            return;
        }
        boolean expireCapability = s.virtualLocked || s.placementReviewIssued || s.forcePlacementReady;
        s.virtualPlacement = false;
        s.virtualLocked = false;
        if (HologramSessionPolicy.authoring(s)) HologramResourcePlanner.clearAuthoringQuotes(s);
        s.blueprintAwaitingConfirmation = false;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        s.openPurpose = "";
        if (expireCapability) s.placementReviewNonce++;
        s.ae.invalidateReviews();
        s.blueprintPreview = true;
        s.presetBrowseForPlacement = false;
        HologramProjectorWorkflow.libraryCloseUi(s);
        s.status = "放置已取消，蓝图蓝图保留；右键可重新打开工作台";
        HologramSnapshots.send(s);
    }

    /** Rebase the in-memory draft without publishing a temporary lock or discarding edits. */
    static boolean beginVirtualPlacement(HologramSession s, int x, int y, int z, int side) {
        boolean previousPlacement = s.virtualPlacement;
        s.virtualPlacement = true;
        boolean moved = false;
        try {
            moved = moveVirtual(s, x, y, z, side);
            if (!moved) return false;
        } finally {
            if (!moved) s.virtualPlacement = previousPlacement;
        }
        s.virtualLocked = false;
        s.blueprintAwaitingConfirmation = false;
        s.forcePlacementReady = false;
        HologramProjectorWorkflow.libraryCloseUi(s);
        s.settingsOnly = false;
        s.placementReviewIssued = false;
        s.status = "移动鼠标预览位置；右键锁定位置，再次右键核对材料与 NBT，Esc 取消放置";
        return true;
    }

    /** Rebase the in-memory draft without publishing a temporary lock or discarding edits. */
    static boolean moveVirtual(HologramSession s, int x, int y, int z, int side) {
        if (HologramSessionPolicy.blockedByAreaImport(s)) return false;
        EntityPlayerMP player = s.player;
        if (s.capture == null || !HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
            || !HologramSessionPolicy.previewReach(player, x, y, z)
            || !player.worldObj.blockExists(x, y, z)) return false;
        if (s.x != x || s.y != y || s.z != z) {
            if (!transformVirtual(s, x, y, z, false, false)) return false;
            s.x = x;
            s.y = y;
            s.z = z;
        }
        s.side = side;
        HologramResourcePlanner.refreshPlan(s);
        return true;
    }

    /** A complete, unlocked placement may rotate; never writes world or preset storage. */
    static boolean rotateVirtual(HologramSession s, boolean vertical) {
        if (HologramSessionPolicy.blockedByAreaImport(s)) return false;
        if (s.cancelled || !s.virtual
            || !s.virtualPlacement
            || s.virtualLocked
            || s.virtualPreset == null
            || s.capture == null
            || s.capture.incomplete
            || s.capture.pending
            || s.capturePending
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || !HologramSessionPolicy.valid(s)
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(s.player.getHeldItem()))
            return false;
        String beforeStatus = s.status;
        if (!transformVirtual(s, s.x, s.y, s.z, true, vertical)) {
            s.status = java.util.Objects.equals(beforeStatus, s.status) ? "旋转被拒绝：坐标、控制器姿态或仓室方向不合法；原目标保留"
                : "旋转被拒绝：" + s.status + "；原目标保留";
            return false;
        }
        HologramResourcePlanner.freezeAllocations(s);
        HologramResourcePlanner.refreshPlan(s);
        s.status = vertical ? "虚拟结构已垂直旋转 90°；右击确定位置并核对材料" : "虚拟结构已水平旋转 90°；右击确定位置并核对材料";
        return true;
    }

    /** Stage every coordinate and front before committing; stable cell order keeps all indexed targets selected. */
    static boolean transformVirtual(HologramSession s, int x, int y, int z, boolean rotate, boolean vertical) {
        return transformVirtual(s, x, y, z, rotate, vertical, true);
    }

    /** Intermediate template turns retain schema checks; physical pose rules apply to the final orientation. */
    static boolean transformVirtual(HologramSession s, int x, int y, int z, boolean rotate, boolean vertical,
        boolean validateFinalPose) {
        HologramPresets.Preset preset = s.virtualPreset;
        ExtendedFacing controller = null;
        if (rotate) {
            preset = HologramPresets.copy(preset);
            Map<String, int[]> littleReferences;
            try {
                littleReferences = HologramBlockTransformLittleTiles.presetReferences(preset.cells, vertical);
            } catch (java.io.IOException invalid) {
                return false;
            }
            controller = HologramVirtualTransform.alignment(HologramCellPolicy.virtualControllerFacing(s), vertical);
            gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = preset.controllerId < 0
                || preset.controllerId >= GregTechAPI.METATILEENTITIES.length ? null
                    : GregTechAPI.METATILEENTITIES[preset.controllerId];
            if (validateFinalPose && (preset.schema < 2 || preset.controllerId >= 0)
                && (!(prototype instanceof MTEEnhancedMultiBlockBase)
                    || !((MTEEnhancedMultiBlockBase<?>) prototype).getAlignmentLimits()
                        .isNewExtendedFacingValid(controller)))
                return false;
            preset.controllerFacing = controller.ordinal();
            Map<String, Integer> currentFacings = new HashMap<>();
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                String key = HologramValues.position(cell);
                Integer front = s.facingPins.getOrDefault(key, s.planFacings.get(i));
                if (front != null && !s.virtualEdited.contains(key)) currentFacings.put(key, front);
            }
            for (HologramPresets.Cell saved : preset.cells) {
                int front = currentFacings
                    .getOrDefault((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z), saved.facing);
                if (preset.schema >= 2) {
                    try {
                        HologramBlockTransforms.turn(saved, vertical, littleReferences);
                        saved.tileNbt = HologramCoordinatePolicies.rotatePayload(saved.tileNbt, vertical);
                        saved.itemNbt = HologramCoordinatePolicies.rotatePayload(saved.itemNbt, vertical);
                        HologramBlockTransforms.nativeSample(saved);
                    } catch (java.io.IOException invalid) {
                        s.status = invalid.getMessage();
                        return false;
                    }
                }
                int[] offset = HologramVirtualTransform.turn(saved.x, saved.y, saved.z, vertical);
                saved.x = offset[0];
                saved.y = offset[1];
                saved.z = offset[2];
                saved.facing = HologramBlockTransforms.isPipe(saved.stack()) ? -1
                    : saved.controller && preset.schema < 2 ? controller.getDirection()
                        .ordinal() : HologramVirtualTransform.front(front, vertical);
                if (validateFinalPose && saved.stack() != null
                    && !saved.missing()
                    && !HologramCellPolicy.virtualFrontAllowed(saved.stack(), saved.facing)) return false;
            }
        }
        if (rotate) for (HologramAePolicy.Issue issue : preset.aeIssues) {
            if ("policy".equals(issue.path)) continue;
            int[] location = HologramVirtualTransform.turn(issue.x, issue.y, issue.z, vertical);
            issue.x = location[0];
            issue.y = location[1];
            issue.z = location[2];
            issue.path = HologramAeBlueprint.rotateIssuePath(issue.path, vertical);
        }
        if (rotate) for (HologramPresets.Structure saved : preset.structures) {
            int[] coordinate = HologramVirtualTransform.turn(saved.x, saved.y, saved.z, vertical);
            saved.x = coordinate[0];
            saved.y = coordinate[1];
            saved.z = coordinate[2];
        }
        if (rotate) for (HologramPresets.HintArea hint : preset.hintAreas) {
            int[] a = HologramVirtualTransform.turn(hint.x1, hint.y1, hint.z1, vertical);
            int[] b = HologramVirtualTransform.turn(hint.x2, hint.y2, hint.z2, vertical);
            hint.x1 = Math.min(a[0], b[0]);
            hint.x2 = Math.max(a[0], b[0]);
            hint.y1 = Math.min(a[1], b[1]);
            hint.y2 = Math.max(a[1], b[1]);
            hint.z1 = Math.min(a[2], b[2]);
            hint.z2 = Math.max(a[2], b[2]);
        }
        HologramCapture capture = new HologramCapture(s.capture.context, s.capture.trigger);
        capture.incomplete = s.capture.incomplete;
        capture.pending = s.capture.pending;
        capture.failure = s.capture.failure;
        capture.channelReport = s.capture.channelReport;
        Map<String, String> keys = new HashMap<>();
        Map<String, Integer> facings = new HashMap<>();
        Map<Integer, ItemStack> nativeTargets = new HashMap<>();
        Map<String, int[]> littleReferences = null;
        if (rotate && preset.schema >= 2) {
            try {
                littleReferences = HologramBlockTransformLittleTiles.captureReferences(s.capture.cells, vertical);
            } catch (java.io.IOException invalid) {
                return false;
            }
        }
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell old = s.capture.cells.get(i);
            int[] offset = new int[] { old.x - s.x, old.y - s.y, old.z - s.z };
            if (rotate) offset = HologramVirtualTransform.turn(offset[0], offset[1], offset[2], vertical);
            long xx = (long) x + offset[0], yy = (long) y + offset[1], zz = (long) z + offset[2];
            if (xx < Integer.MIN_VALUE || xx > Integer.MAX_VALUE
                || yy < Integer.MIN_VALUE
                || yy > Integer.MAX_VALUE
                || zz < Integer.MIN_VALUE
                || zz > Integer.MAX_VALUE) return false;
            if (validateFinalPose && (xx < -30000000L || xx >= 30000000L
                || yy < 0
                || yy >= s.player.worldObj.getHeight()
                || zz < -30000000L
                || zz >= 30000000L)) return false;
            HologramCapture.Cell cell = HologramBlueprintData.blueprintCell(old, (int) xx, (int) yy, (int) zz);
            if (rotate && preset.schema >= 2) {
                try {
                    HologramBlockTransforms.turn(cell, vertical, littleReferences, old.x, old.y, old.z);
                    cell.tileNbt = HologramCoordinatePolicies.rotatePayload(cell.tileNbt, vertical);
                    cell.itemNbt = HologramCoordinatePolicies.rotatePayload(cell.itemNbt, vertical);
                    ItemStack sample = HologramBlockTransforms
                        .nativeSample(cell, HologramCellPolicy.targetFor(s, old, i));
                    if (sample != null) nativeTargets.put(i, sample);
                } catch (java.io.IOException invalid) {
                    s.status = invalid.getMessage();
                    return false;
                }
            }
            String key = HologramValues.position(cell);
            keys.put(HologramValues.position(old), key);
            capture.cells.add(cell);
            capture.positions.put(key, cell);
            Integer front = s.facingPins.get(HologramValues.position(old));
            if (front == null && rotate) front = s.planFacings.get(i);
            if (HologramBlockTransforms.isPipe(HologramCellPolicy.targetFor(s, old, i))) front = null;
            if (front != null) {
                if (rotate)
                    front = "templateController".equals(old.role) && preset.schema < 2 ? controller.getDirection()
                        .ordinal() : HologramVirtualTransform.front(front, vertical);
                if (validateFinalPose && HologramCellPolicy.targetFor(s, old, i) != null
                    && !HologramCellPolicy.virtualFrontAllowed(HologramCellPolicy.targetFor(s, old, i), front))
                    return false;
                facings.put(key, front);
            }
        }
        // No mutation above this line: a rejected turn leaves cells, pins, template and revision untouched.
        HologramValues.rekey(s.coordinatePins, keys);
        HologramValues.rekey(s.frozenAutoTargets, keys);
        for (Map.Entry<Integer, ItemStack> entry : nativeTargets.entrySet()) {
            int index = entry.getKey();
            HologramCapture.Cell cell = capture.cells.get(index);
            String key = HologramValues.position(cell);
            ItemStack sample = entry.getValue();
            if (s.pins.containsKey(index) || cell.userPosition && !cell.excluded) {
                s.pins.put(index, sample.copy());
                s.coordinatePins.put(key, sample.copy());
            } else if (s.coordinatePins.containsKey(key)) s.coordinatePins.put(key, sample.copy());
            if (s.autoTargets.containsKey(index)) s.autoTargets.put(index, sample.copy());
            if (s.frozenAutoTargets.containsKey(key)) s.frozenAutoTargets.put(key, sample.copy());
            if (index < s.planTargets.size()) s.planTargets.set(index, sample.copy());
        }
        HologramValues.rekey(s.virtualEdited, keys);
        HologramValues.rekey(s.suppressedAuto, keys);
        java.util.Set<String> manualKeys = new java.util.HashSet<>(s.manualPositions.keySet());
        s.manualPositions.clear();
        for (String old : manualKeys) {
            HologramCapture.Cell cell = capture.positions.get(keys.get(old));
            if (cell != null) s.manualPositions.put(HologramValues.position(cell), cell);
        }
        s.facingPins.clear();
        s.facingPins.putAll(facings);
        s.capture = capture;
        s.virtualPreset = preset;
        s.decorLedger = new HologramDecorMaterials.Ledger();
        if (rotate) s.facing = controller.ordinal();
        s.cellIndices.clear();
        s.indexedCapture = null;
        // A whole placement turn/rebase establishes a new preset baseline; discard history tied to its old baseline.
        s.undoEdits.clear();
        s.redoEdits.clear();
        return true;
    }

    static void immediateVirtual(EntityPlayerMP player, int x, int y, int z, int side, String presetId) {
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("x", x);
        request.setInteger("y", y);
        request.setInteger("z", z);
        request.setInteger("side", side);
        quickPlaceLastBlueprint(player, request);
    }
}
