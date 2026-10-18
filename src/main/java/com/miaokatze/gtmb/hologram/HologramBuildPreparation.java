package com.miaokatze.gtmb.hologram;

import net.minecraft.nbt.NBTTagCompound;

import tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods;

/** Internal domain boundary. */
final class HologramBuildPreparation {

    private HologramBuildPreparation() {}

    static void nativeBuild(HologramSession s) {
        if (HologramDeferredPlanRefresh.blocked(s)) return;
        if (HologramSessionPolicy.blockedByAreaImport(s)) return;
        if (s.mode != 0) {
            s.status = "原版搭建只用于补建模式";
            return;
        }
        // Native constructors can write outside the verified differential transaction.
        // Route complete captures through that transaction; never guess from partial hints.
        if (s.capture == null || s.capture.incomplete || s.capture.pending || s.capturePending) {
            s.status = "结构采集不完整，无法确认位置是否被遮挡，未施工";
            return;
        }
        HologramBuildPreparation.directBuild(s, false);
    }

    static void directBuild(HologramSession s) {
        directBuild(s, true);
    }

    static void directBuild(HologramSession s, boolean rescan) {
        directBuild(s, rescan, null);
    }

    private static void directBuild(HologramSession s, boolean rescan, NBTTagCompound reviewedAction) {
        if (HologramDeferredPlanRefresh.blocked(s)) return;
        if (HologramSessionPolicy.blockedByAreaImport(s)) return;
        if (!HologramBuildPreparation.aeReady(s)) return;
        if (s.virtual && (!s.virtualPlacement || !s.virtualLocked || s.mode == 2)) {
            s.status = s.mode == 2 ? "虚拟预设不支持拆除" : !s.virtualPlacement ? "请在蓝图库点击放置，再右击确定位置并核对材料" : "请先右击确定位置，再在界面确认施工";
            return;
        }
        if (rescan) HologramBuildPreparation.scan(s);
        if (s.capturePending) {
            s.buildAfterCapture = true;
            s.status = "正在采集完整结构，完成后将按当前规划施工";
            return;
        }
        if (s.capture == null || s.capture.incomplete || s.capturePending) {
            HologramSession.cancelCaptureRetry(s);
            if (s.mode == 0) HologramBuildPreparation.nativeBuild(s);
            else s.status = "替换和拆除需要完整结构采集";
            return;
        }
        HologramPlacementJobs.start(s, reviewedAction);
    }

    static void reviewedBuild(HologramSession s, NBTTagCompound action) {
        if (HologramDeferredPlanRefresh.blocked(s)) return;
        if (s.job == 1 || s.job == 2) return;
        if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision) {
            s.forcePlacementReady = false;
            s.status = "确认计划已更新，请重新核对材料与位置";
            return;
        }
        if (s.virtual && (!s.id.equals(action.getString("session")) || !action.hasKey("placementReviewNonce", 4)
            || action.getLong("placementReviewNonce") != s.placementReviewNonce)) {
            s.forcePlacementReady = false;
            s.status = "材料确认已更新，请重新核对当前计划后确认";
            return;
        }
        if (s.capture == null || s.capture.incomplete || s.capture.pending || s.capturePending) {
            s.forcePlacementReady = false;
            s.status = "请等待完整结构采集，再核对材料确认施工";
            return;
        }
        if (!HologramPlanPolicy.planCurrent(s)) {
            s.forcePlacementReady = false;
            HologramResourcePlanner.refreshPlan(s);
            s.status = "现场已变化，请重新核对更新后的材料与位置";
            return;
        }
        // Consent belongs to this reviewed plan, never to a previous failed packet.
        s.forcePlacementReady = false;
        directBuild(s, false, action);
    }

    static void calibrateCurrent(HologramSession s) {
        if (!HologramPlanPolicy.structureBuilt(s)) {
            s.status = "原机尚未报告结构成型，请恢复默认或等待原机检查";
            return;
        }
        if (s.machine instanceof MTEForgeOfGods) {
            s.main = HologramValues.clamp(
                ((MTEForgeOfGods) s.machine).getData()
                    .getRingAmount(),
                1,
                3);
            s.mainExplicit = true;
        }
        s.calibratePending = true;
        scan(s);
    }

    static void scan(HologramSession s) {
        scan(s, true);
    }

    static void scan(HologramSession s, boolean resetAttempts) {
        HologramControllerCapture.collect(s, resetAttempts);
        if (s.buildAfterCapture && !s.capturePending && s.capture != null) {
            s.buildAfterCapture = false;
            if (!s.capture.incomplete) HologramBuildPreparation.directBuild(s, false);
            else s.status = "采集失败，未执行施工";
        }
    }

    static void tickPending(HologramSession s) {

        if (HologramVirtualStructureEditing.pending(s)) {
            HologramVirtualStructureEditing.advance(s);
            return;
        }

        if (!HologramSessionPolicy.valid(s) || s.machine == null
            || s.machine.getExtendedFacing()
                .ordinal() != s.retryFacing
            || !HologramControllerCapture.captureConfigurationCurrent(s)) {
            HologramSession.cancelCaptureRetry(s);
            HologramSession.clearPending(s);
            s.job = 4;
            s.status = "工具、目标、控制器朝向或配置已变化，自动采集已停止";
            HologramSnapshots.send(s);
        } else if (s.player.worldObj.getTotalWorldTime() >= s.captureRetryDue) {
            scan(s, false);
            HologramSnapshots.send(s);
        }
        return;

    }

    static boolean aeReady(HologramSession s) {
        if (!s.virtual || s.virtualPreset == null || s.virtualPreset.schema < 2 || s.capture == null) return true;
        try {
            if (HologramDeidentification.level(s.virtualPreset) == 0)
                for (HologramCapture.Cell cell : s.capture.cells) {
                    if (HologramSessionPolicy.ignoredPlacementAir(s, cell, cell.stableIndex)) continue;
                    if (HologramSessionPolicy.inScope(s, cell, HologramSessionPolicy.cellIndex(s, cell))
                        && HologramCellPolicy.foreignBlueprintOwner(s, cell)) {
                        s.status = "0 级蓝图包含其他玩家所有权；请主动选择至少 1 级去标识，再按当前玩家身份放置";
                        return false;
                    }
                }
            if (HologramAuthoringWorkflow.ensureNetworkGroups(s)) {
                s.networkBindingRequested = true;
                s.status = "已生成量子设备分组；分享图请选择背包内已绑定目标网络的量子终端，再确认材料";
                return false;
            }
            for (HologramAePolicy.Issue issue : s.virtualPreset.aeIssues) {
                if (!"blocker".equals(issue.severity)) continue;
                HologramCapture.Cell cell = s.capture.positions
                    .get((s.x + issue.x) + "," + (s.y + issue.y) + "," + (s.z + issue.z));
                if (cell != null && !cell.excluded && HologramSessionPolicy.membershipEnabled(s, cell)) {
                    s.status = "AE 蓝图仍有未明确处理的净化问题；请在预览中清除对应标记，再核对施工计划";
                    return false;
                }
            }
            for (HologramCapture.Cell cell : s.capture.cells) {
                if (HologramSessionPolicy.ignoredPlacementAir(s, cell, cell.stableIndex)) continue;
                if (!cell.excluded && HologramSessionPolicy.membershipEnabled(s, cell)) {
                    try {
                        HologramNetworkBindings
                            .validate(HologramBlueprintNbt.decode(cell.tileNbt), s.player, s.networkBindings);
                    } catch (java.io.IOException missingBinding) {
                        s.networkBindingRequested = true;
                        s.status = HologramAePolicy.fallback(missingBinding.getMessage());
                        return false;
                    }
                }
                if (!HologramAePolicy.handles(cell.block, cell.tileNbt) || cell.excluded
                    || !HologramSessionPolicy.membershipEnabled(s, cell)) continue;
                if (HologramAeBlueprint
                    .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt)).blocking) {
                    s.status = "AE 蓝图仍有阻止施工的问题，请打开相关方块预览并删除或替换不安全标记";
                    return false;
                }
            }
            return true;
        } catch (java.io.IOException | RuntimeException invalid) {
            s.status = "AE 蓝图净化报告无效，保持蓝图；请重新审查后施工";
            return false;
        }
    }
}
