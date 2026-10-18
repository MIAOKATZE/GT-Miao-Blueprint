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
        s.work.sort((left, right) -> {
            HologramCapture.Cell a = s.capture.cells.get(left), b = s.capture.cells.get(right);
            int order = Integer.compare(a.y, b.y);
            if (order == 0) order = Integer.compare(a.z, b.z);
            return order == 0 ? Integer.compare(a.x, b.x) : order;
        });
        return System.nanoTime() - started;
    }

    static void notifyCompletedControllers(HologramSession s) {
        if (!s.virtual || s.player.worldObj.provider.dimensionId != s.dimension) return;
        for (int index : s.work) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (!"templateController".equals(cell.role)
                || !("placed".equals(cell.status) || "replaced".equals(cell.status))) continue;
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
    }

    static void tickJob(HologramSession s) {
        if (s.job != 1) return;
        String invalid = HologramSessionPolicy.jobInvalidReason(s);
        if (!invalid.isEmpty()) {
            // Keep attempted/total counters and unpublished outcomes for the stopped-job receipt.
            // job=4 itself prevents any remaining work from executing.
            s.defer = s.stopTick = false;
            s.phase = "STOPPED";
            s.job = 4;
            s.status = "施工已停止：" + invalid + "；已施工方块保留，请重新采集";
            HologramSnapshots.sendStoppedJob(s);
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
            notifyCompletedControllers(s);
            int unresolved = 0;
            for (int i = 0; i < s.capture.cells.size(); i++)
                if (HologramSessionPolicy.inScope(s, s.capture.cells.get(i), i) && !s.capture.cells.get(i).anchor) {
                    String status = s.capture.cells.get(i).status;
                    if (!"satisfied".equals(status) && !"placed".equals(status)
                        && !"replaced".equals(status)
                        && !"removed".equals(status)) unresolved++;
                }
            s.job = unresolved == 0 ? 3 : 5;
            s.status = s.jobPartialPlacement
                ? "部分放置结束：已放置 " + s.completed
                    + " 格；缺料跳过 "
                    + s.jobMissingCells
                    + " 格；保护或占位跳过 "
                    + s.jobProtectedCells
                    + " 格；不支持跳过 "
                    + s.jobUnsupportedCells
                    + " 格"
                : unresolved == 0 ? "范围内施工完成：已完成 " + s.completed + " 格（未执行机器成型检查）"
                    : "部分完成：已完成 " + s.completed
                        + " 格；缺料跳过 "
                        + s.jobMissingCells
                        + " 格；其他未完成 "
                        + (unresolved - s.jobMissingCells)
                        + " 格";
        }
        // Final states and explicit action ACKs remain immediate; retain every per-cell batch result.
        HologramSnapshots.sendJob(s);

    }

    static void start(HologramSession s) {
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
        HologramResourcePlanner.refreshResources(s);
        if (!HologramPlanPolicy.constructionAllowed(s)) return;
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (HologramSessionPolicy.inScope(s, cell, i) && "unsupported".equals(cell.status)
                && !cell.resourceError.isEmpty()) {
                s.status = "蓝图资源数据无法安全解析，未施工：" + cell.resourceError;
                return;
            }
        }
        if (!s.planRecoveryFits) {
            s.status = s.blueprintResourceMissing.isEmpty() ? "背包无法容纳回收，请整理后重试"
                : "蓝图资源校验失败，未施工：" + s.blueprintResourceMissing;
            return;
        }
        if (s.planMissing > 0 && (!s.virtual || !s.forcePlacementReady)) {
            s.forcePlacementReady = s.virtual;
            s.status = s.virtual
                ? (s.blueprintResourceMissing.isEmpty() ? "蓝图材料不足" : s.blueprintResourceMissing)
                    + "；再次点击施工将只放置现有材料，权限与占位保护仍有效"
                : "施工材料不足，请补齐后重试";
            return;
        }
        boolean partial = s.virtual && s.planMissing > 0 && s.forcePlacementReady;
        s.forcePlacementReady = false;
        s.work.clear();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            if (!HologramSessionPolicy.ignoredPlacementAir(s, s.capture.cells.get(i), i)
                && HologramSessionPolicy.inScope(s, s.capture.cells.get(i), i)
                && "pending".equals(s.planStatus.get(i))) s.work.add(i);
        }
        spatialWorkOrder(s);
        if (s.work.isEmpty()) {
            int satisfied = 0, protectedCells = 0, unsupported = 0;
            String reason = "";
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                if (HologramSessionPolicy.ignoredPlacementAir(s, cell, i)) continue;
                if (!HologramSessionPolicy.inScope(s, cell, i) || cell.anchor) continue;
                String status = s.planStatus.get(i);
                if ("satisfied".equals(status)) satisfied++;
                else {
                    if ("unsupported".equals(status)) unsupported++;
                    else protectedCells++;
                    if (reason.isEmpty()) reason = HologramPlanPolicy.reason(s, cell, i);
                }
            }
            s.status = protectedCells == 0 && unsupported == 0 && satisfied > 0
                ? "目标范围已满足，无需施工；未放置方块、未扣材料（" + satisfied + " 格）"
                : "没有可安全施工的位置：已满足 " + satisfied
                    + " 格，保护或占位 "
                    + protectedCells
                    + " 格，不支持 "
                    + unsupported
                    + " 格"
                    + (reason.isEmpty() ? "" : "；" + reason);
            return;
        }
        s.replacementAuthorized = s.mode == 1;
        s.cursor = 0;
        s.completed = 0;
        s.job = 1;
        HologramSession.resetJobCounts(s, partial);
        s.phase = "RUNNING";
        s.status = "正在直接施工";

    }
}
