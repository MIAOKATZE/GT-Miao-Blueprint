package com.miaokatze.gtmb.hologram;

/** Starts reviewed bounded jobs; transactions remain the sole per-cell world operation boundary. */
final class HologramPlacementJobs {

    private HologramPlacementJobs() {}

    static final int BUDGET = 4096;

    static void updateJobCounts(HologramSession s) {
        s.jobMissingCells = s.jobProtectedCells = s.jobUnsupportedCells = 0;
        for (int i = 0; i < s.cursor && i < s.work.size(); i++) {
            String status = s.capture.cells.get(s.work.get(i)).status;
            if ("missing".equals(status)) s.jobMissingCells++;
            else if ("protected".equals(status) || "blocked".equals(status)) s.jobProtectedCells++;
            else if ("unsupported".equals(status)) s.jobUnsupportedCells++;
        }
        s.jobSkippedCells = Math.max(0, s.cursor - s.completed);
    }

    /** A reviewed request must keep the same world differential; resource availability is checked again. */

    static long spatialWorkOrder(HologramSession s) {
        if (!s.virtual || s.work.size() < 2) return 0;
        // Old saved blueprints also inherited Population's controller-last editor order. Order physical
        // work by integer coordinates, treating controllers exactly like their surrounding blocks.
        long started = System.nanoTime();
        HologramWorkOrderTask ordering = new HologramWorkOrderTask(s.work, (a, b) -> compareWork(s, a, b));
        while (!ordering.done()) ordering.advance();
        return System.nanoTime() - started;
    }

    static int compareWork(HologramSession s, int left, int right) {
        HologramCapture.Cell a = s.capture.cells.get(left), b = s.capture.cells.get(right);
        int order = Integer.compare(a.y, b.y);
        if (order == 0) order = Integer.compare(a.z, b.z);
        return order == 0 ? Integer.compare(a.x, b.x) : order;
    }

    static void notifyCompletedControllers(HologramSession s) {
        for (int index : s.work) notifyCompletedController(s, index);
    }

    static void notifyCompletedController(HologramSession s, int index) {
        if (!s.virtual || s.player.worldObj.provider.dimensionId != s.dimension) return;
        HologramCapture.Cell cell = s.capture.cells.get(index);
        if (!"templateController".equals(cell.role)
            || !("placed".equals(cell.status) || "replaced".equals(cell.status))) return;
        try {
            net.minecraft.tileentity.TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            if (tile instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity) {
                gregtech.api.interfaces.metatileentity.IMetaTileEntity machine = ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) tile)
                    .getMetaTileEntity();
                if (machine instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase)
                    machine.onMachineBlockUpdate();
            }
        } catch (RuntimeException | LinkageError failure) {
            HologramPlacementDiagnostics.failure(s, cell, index, "completed-controller-update", failure);
        }
    }

    static void tickJob(HologramSession s) {
        if (s.job != 1) return;
        String invalid = HologramSessionPolicy.jobInvalidReason(s);
        if (!invalid.isEmpty()) {
            // Keep attempted/total counters and unpublished outcomes for the stopped-job receipt.
            // job=4 itself prevents any remaining work from executing.
            s.defer = s.stopTick = false;
            s.placementFinishTask = null;
            s.phase = "STOPPED";
            s.job = 4;
            s.status = "施工已停止：" + invalid + "；已施工方块保留，请重新采集";
            HologramSnapshots.sendStoppedJob(s);
            return;
        }
        if (s.placementFinishTask != null) {
            finish(s, HologramWorkBudget.remainingNanos(HologramWorkBudget.SLICE_NANOS));
            HologramSnapshots.sendJob(s);
            return;
        }
        s.materialContext = new HologramMaterials.Context(s.player, s.materialSettings, s.slot);
        s.materialContext.flushRecovery();
        s.ackIndices.clear();
        s.ackSuccesses.clear();
        long deadline = HologramWorkBudget.deadline(HologramWorkBudget.SLICE_NANOS);
        int attempted = 0;
        while (s.cursor < s.work.size() && attempted < HologramPlacementJobs.BUDGET
            && (attempted == 0 || System.nanoTime() < deadline)) {
            attempted++;
            int index = s.work.get(s.cursor);
            HologramCapture.Cell cell = s.capture.cells.get(index);
            boolean unchanged = HologramPlanPolicy.expectedCurrent(s, index);
            boolean success = unchanged && HologramWorldTransactions.operate(s, cell, index);
            if (!unchanged) {
                cell.status = "protected";
                s.status = "现场已变化，请重新扫描";
            }
            if (s.defer) {
                s.defer = false;
                if (s.deferredIndex != index) {
                    s.deferredIndex = index;
                    s.deferAttempts = 0;
                }
                if (++s.deferAttempts < 3) break;
                cell.status = "unsupported";
                s.status = "原版元素持续要求等待，请重新规划或手动施工";
            }
            s.deferredIndex = -1;
            s.deferAttempts = 0;
            s.cursor++;
            boolean changed = success
                && ("placed".equals(cell.status) || "replaced".equals(cell.status) || "removed".equals(cell.status));
            if (changed) s.completed++;
            else if (!success && "pending".equals(cell.status)) cell.status = "unsupported";
            s.ackIndices.add(index);
            s.ackSuccesses.add(changed ? 1 : 0);
            s.jobPublication.indices.add(index);
            s.jobPublication.successes.add(changed ? 1 : 0);
            s.jobPublication.statuses.add(cell.status);
            if ("missing".equals(cell.status)) s.jobMissingCells++;
            else if ("protected".equals(cell.status) || "blocked".equals(cell.status)) s.jobProtectedCells++;
            else if ("unsupported".equals(cell.status)) s.jobUnsupportedCells++;
            s.ackIndex = index;
            s.ackSuccess = changed;
            if (s.stopTick) {
                s.stopTick = false;
                break;
            }
        }
        s.phase = "ACK";
        s.batchSeq++;
        s.jobSkippedCells = Math.max(0, s.cursor - s.completed);
        if (s.cursor >= s.work.size()) {
            s.placementFinishTask = new HologramPlacementFinishTask(s);
            finish(s, Math.max(0L, deadline - System.nanoTime()));
        }
        // Final states and explicit action ACKs remain immediate; retain every per-cell batch result.
        HologramSnapshots.sendJob(s);

    }

    private static void finish(HologramSession s, long maxNanos) {
        s.phase = "FINALIZING";
        s.status = "施工操作已结束，正在核对结果与通知控制器";
        HologramPlacementFinishTask task = s.placementFinishTask;
        if (!task.current(s)) {
            s.placementFinishTask = null;
            s.job = 4;
            s.phase = "STOPPED";
            s.status = "施工计划已变化，已保留成功施工方块；请重新采集";
        } else if (task.step(s, BUDGET, maxNanos)) {
            task.publish(s);
            s.placementFinishTask = null;
        }
    }

    static void start(HologramSession s) {
        start(s, null);
    }

    static void start(HologramSession s, net.minecraft.nbt.NBTTagCompound reviewedAction) {
        if (HologramDeferredPlanRefresh.blocked(s)) return;
        if (!HologramSessionPolicy.wholeBlueprintPlacement(s) && (s.scope == 2 || s.scope == 3)
            && (s.selected < 0 || s.selected >= s.capture.cells.size())) {
            s.status = "请先选择施工位置";
            return;
        }
        if (!HologramSessionPolicy.wholeBlueprintPlacement(s) && s.scope == 3
            && s.capture.cells.get(s.selected).family.isEmpty()) {
            s.status = "选中格没有同族范围，请使用单格范围";
            return;
        }
        if (!HologramGeometryEdits.requirePlan(s) || HologramSessionPolicy.blockedByAreaImport(s)) return;
        if (s.virtual && (!s.virtualPlacement || !s.virtualLocked || s.mode == 2)) {
            s.status = "请先放置蓝图，右击确定位置并核对材料后施工";
            return;
        }
        if (s.job == 1 || s.job == 2 || s.buildTask != null) return;
        s.forcePlacementReady = false;
        s.job = 0;
        s.buildTask = new HologramBuildTask(s, reviewedAction);
        s.phase = "PREPARING";
        s.status = "正在逐段精确核对现场与材料，完成前不会施工";
    }
}
