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

    static boolean isPending(HologramSession s) {
        return s.buildTask != null;
    }

    static void cancel(HologramSession s) {
        if (s.buildTask == null) return;
        s.buildTask.cancel();
        s.buildTask = null;
        s.forcePlacementReady = false;
        if (s.job != 1 && s.job != 2) s.phase = "IDLE";
    }

    static String phase(HologramSession s) {
        return s.buildTask == null ? "build.idle" : s.buildTask.phase();
    }

    static long completed(HologramSession s) {
        return s.buildTask == null ? 0 : s.buildTask.completed();
    }

    static long total(HologramSession s) {
        return s.buildTask == null ? 0 : s.buildTask.total();
    }

    static void tickBuild(HologramSession s) {
        boolean pending = isPending(s);
        tickBuild(s, HologramPlacementJobs.BUDGET, HologramWorkBudget.remainingNanos(HologramWorkBudget.SLICE_NANOS));
        if (pending && !isPending(s) && !s.cancelled && HologramSessionPolicy.valid(s))
            HologramSnapshotPreparation.start(s);
        HologramSnapshots.sendProgress(s);
    }

    /** Deterministic port uses the same admission lifecycle without transport side effects. */
    static void tickBuild(HologramSession s, int maxWork, long maxNanos) {
        HologramBuildTask task = s.buildTask;
        if (task == null) return;
        boolean done;
        long measured = HologramRuntimeMetrics.start();
        try {
            done = task.step(s, maxWork, maxNanos);
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.BUILD_PREPARE, measured);
        }
        if (!done) return;
        if (s.buildTask != task) return;
        s.buildTask = null;
        if (s.job != 1 && s.job != 2) s.phase = "IDLE";
    }
}
