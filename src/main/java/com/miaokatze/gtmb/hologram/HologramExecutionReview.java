// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

/** An operation session accepts review decisions and job controls, and carries no editable blueprint. */
final class HologramExecutionReview {

    private HologramExecutionReview() {}

    private enum Decision {

        CHECK_MATERIALS,
        ORDER_MATERIALS,
        CANCEL_ORDER,
        VOUCHER,
        CONFIGURE_DEVICE_STATE,
        OPEN_NETWORKS,
        CLOSE_NETWORKS,
        BIND_NETWORK,
        PLACE,
        CANCEL_REVIEW,
        CLOSE,
        JOB_CONTROL;

        static Decision of(String op) {
            return switch (op) {
                case "refreshMaterialQuote" -> CHECK_MATERIALS;
                case "orderMissingMaterials" -> ORDER_MATERIALS;
                case "cancelMaterialOrder" -> CANCEL_ORDER;
                case "setDecorativeVoucher" -> VOUCHER;
                case "configurePlacementNbt" -> CONFIGURE_DEVICE_STATE;
                case "openNetworkBindings" -> OPEN_NETWORKS;
                case "closeNetworkBindings" -> CLOSE_NETWORKS;
                case "bindBlueprintNetwork" -> BIND_NETWORK;
                case "confirmBlueprintPlacement" -> PLACE;
                case "cancelBlueprintPlacement" -> CANCEL_REVIEW;
                case "close" -> CLOSE;
                case "pause", "resume", "cancel" -> JOB_CONTROL;
                default -> null;
            };
        }
    }

    static boolean handle(HologramSession s, NBTTagCompound request, String op) {
        Decision decision = Decision.of(op);
        if (decision == Decision.JOB_CONTROL) return s.job != 1 && s.job != 2;
        if (decision == null) {
            s.status = "请在客户端蓝图工作区调整施工内容";
            HologramSnapshots.sendUi(s);
            return true;
        }
        if (decision == Decision.CLOSE) {
            HologramProjectorWorkflow.libraryCloseUi(s);
            s.forcePlacementReady = false;
            HologramSnapshots.sendUi(s);
            return true;
        }
        if (decision == Decision.CHECK_MATERIALS || decision == Decision.ORDER_MATERIALS
            || decision == Decision.CANCEL_ORDER
            || decision == Decision.VOUCHER) {
            HologramMaterialWorkflow.action(s, request, op);
            return true;
        }
        try {
            boolean revisionRequired = decision != Decision.OPEN_NETWORKS && decision != Decision.CLOSE_NETWORKS;
            if (!HologramSessionPolicy.valid(s) || s.job != 0
                || !s.blueprintAwaitingConfirmation
                || revisionRequired
                    && (!request.hasKey("planRevision", 4) || request.getLong("planRevision") != s.planRevision))
                throw new IOException("施工审核已变化，请重新核对后确认");
            if (HologramAeCraftingOrders.active(s.player)) throw new IOException("请等待 AE 缺料订单处理完成");
            switch (decision) {
                case CONFIGURE_DEVICE_STATE -> {
                    s.placementNbtKeys = request.hasKey("all", 1)
                        ? request.getBoolean("all") ? null
                            : HologramAuthoringQueries.selectedNbtKeys(request.getTagList("keys", 8))
                        : HologramSessionPolicy.placementKeys(s);
                    if (request.hasKey("retainCovers", 1)) s.placementRetainCovers = request.getBoolean("retainCovers");
                    s.placementNbtCustomized = true;
                    refresh(s, "NBT 条目已更新，正在重新核对施工材料");
                }
                case OPEN_NETWORKS -> s.networkBindingRequested = true;
                case CLOSE_NETWORKS -> s.networkBindingRequested = false;
                case BIND_NETWORK -> {
                    String group = request.getString("group");
                    if (!s.executionNetworks.containsKey(group) || !request.hasKey("slot", 3))
                        throw new IOException("当前施工没有指定量子网络组或目标终端");
                    s.networkBindings.select(s.player, group, request.getInteger("slot"));
                    s.networkBindingRequested = true;
                    refresh(s, "量子网络组已选择目标终端，正在重新核对施工材料");
                }
                case PLACE -> {
                    for (String key : new String[] { "forceExplicit", "allowEmptyCache", "useDecorationVoucher" })
                        if (request.hasKey(key) && !request.hasKey(key, 1)) throw new IOException("施工确认选项格式无效");
                    HologramMaterialWorkflow.validateConfirmation(s, request);
                    if (s.executionStart != null || HologramDeferredPlanRefresh.blocked(s)
                        || HologramSessionPolicy.blockedByAreaImport(s)
                        || !HologramBuildPreparation.aeReady(s)) break;
                    s.executionStart = new Start(s, request);
                    s.status = "正在重新核对现场和施工范围";
                }
                case CANCEL_REVIEW -> {
                    HologramWorldPlacementWorkflow.cancelExecution(s);
                    return true;
                }
                default -> throw new IllegalStateException("Unexpected placement decision " + decision);
            }
        } catch (IOException | RuntimeException invalid) {
            s.status = HologramAePolicy.fallback(invalid.getMessage());
        }
        HologramSnapshots.sendUi(s);
        return true;
    }

    private static void refresh(HologramSession s, String status) {
        s.forcePlacementReady = false;
        s.placementReviewIssued = false;
        s.placementReviewNonce++;
        s.placementValidating = true;
        HologramDeferredPlanRefresh.request(s, java.util.Collections.emptySet());
        s.status = status;
    }

    static boolean tick(HologramSession s) {
        Start start = s.executionStart;
        if (start == null) return false;
        try {
            if (!start.step(s, HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(2_000_000L)))) return true;
        } catch (IOException | RuntimeException | LinkageError invalid) {
            s.status = HologramAePolicy.fallback(invalid.getMessage());
        }
        s.executionStart = null;
        if (s.job != 1) s.work.clear();
        HologramSnapshots.sendUi(s);
        return true;
    }

    /** Only the admitted, unchanged physical controller is exempt from ordinary native PLACE work. */
    static boolean retainedPhysicalAnchor(HologramSession s, HologramCapture.Cell cell) {
        return retainedPhysicalAnchor(s, cell, false);
    }

    static boolean retainedPhysicalAnchor(HologramSession s, HologramCapture.Cell cell, boolean readyOnly) {
        if (s.virtual || s.mode != 0
            || s.target == null
            || s.machine == null
            || s.capture == null
            || s.capture.projectionOnly
            || s.capture.pending
            || s.capture.incomplete
            || !HologramEditorWorkspaceRegistry.physicalProjection(s)
            || HologramWorkspace.find(s.player.getUniqueID()) != s
            || !cell.anchor
            || cell.x != s.x
            || cell.y != s.y
            || cell.z != s.z
            || s.machine.getExtendedFacing()
                .ordinal() != s.facing
            || !HologramSessionPolicy.valid(s)) return false;
        int index = cell.stableIndex;
        return index >= 0 && index < s.capture.cells.size()
            && s.capture.cells.get(index) == cell
            && index < s.expectedTiles.size()
            && s.expectedTiles.get(index) == s.target
            && index < s.expectedBlocks.size()
            && index < s.expectedMeta.size()
            && index < s.expectedFacings.size()
            && HologramPlanPolicy.expectedCurrent(s, index)
            && (readyOnly ? s.capture.readyPlacementCurrent(s)
                : s.capture.sourceCurrent(HologramSessionPolicy.trigger(s)));
    }

    static final class Start {

        private final NBTTagCompound approval = new NBTTagCompound();
        private final HologramPlanClassifier classifier;
        private final HologramCapture capture;
        private final HologramPlacementAdmissionSweep work;
        private final long started = System.nanoTime();
        private final boolean creative;
        private int blocked, protectedCells, unsupported, satisfied;

        Start(HologramSession s, NBTTagCompound request) {
            classifier = new HologramPlanClassifier(s);
            capture = s.capture;
            work = new HologramPlacementAdmissionSweep(capture.cells.size());
            creative = s.player.capabilities.isCreativeMode;
            approval.setString("session", s.id);
            approval.setLong("planRevision", s.planRevision);
            approval.setLong("placementReviewNonce", s.placementReviewNonce);
            for (String key : new String[] { "forceExplicit", "allowEmptyCache", "useDecorationVoucher" })
                if (request.hasKey(key, 1)) approval.setBoolean(key, request.getBoolean(key));
            s.work.clear();
        }

        long completed(HologramSession s) {
            return (long) work.visited() + s.work.size();
        }

        private void requireCurrent(HologramSession s) throws IOException {
            String scopedRefusal = HologramWorkspace.legacyConstructionRefusal(s.player);
            if (scopedRefusal != null) throw new IOException(scopedRefusal);
            if (System.nanoTime() - started > 90_000_000_000L || !HologramSessionPolicy.valid(s)
                || !s.player.capabilities.allowEdit
                || s.player.capabilities.isCreativeMode != creative
                || s.cancelled
                || s.job != 0
                || s.capture != capture
                || s.localPlanPending
                || s.planRevision != approval.getLong("planRevision")
                || s.placementReviewNonce != approval.getLong("placementReviewNonce"))
                throw new IOException("施工复核已变化，请重新核对后确认");
        }

        boolean step(HologramSession s, long deadline) throws IOException {
            requireCurrent(s);
            var slice = new HologramPlacementAdmissionSweep.Slice(Integer.MAX_VALUE, deadline, System::nanoTime);
            HologramPlacementAdmissionSweep.Progress progress = work.inspect(slice, index -> {
                HologramCapture.Cell cell = capture.cells.get(index);
                if (!HologramPlanPolicy.expectedCurrent(s, index)) {
                    refresh(s, "现场或区块加载状态已变化，正在重新核对施工范围");
                    return false;
                }
                String status = s.planStatus.get(index);
                if (!creative) {
                    String current = classifier.classify(
                        s,
                        cell,
                        index,
                        true,
                        s.expectedBlocks.get(index),
                        s.expectedMeta.get(index),
                        s.expectedTiles.get(index));
                    if ("blocked".equals(current)) blocked++;
                }
                if ("unsupported".equals(status) && !cell.resourceError.isEmpty())
                    throw new IOException("施工设备状态无法安全解析：" + cell.resourceError);
                switch (status) {
                    case "pending" -> {
                        work.stage(index, cell.x, cell.y, cell.z);
                    }
                    case "satisfied" -> satisfied++;
                    case "unsupported" -> unsupported++;
                    default -> protectedCells++;
                }
                return true;
            });
            if (progress == HologramPlacementAdmissionSweep.Progress.RETIRED) return true;
            if (progress == HologramPlacementAdmissionSweep.Progress.PENDING) return false;
            if (blocked > 0) throw new IOException("选定范围有 " + blocked + " 格被占用，请清空后重试");
            if (work.isEmpty()) {
                s.status = "没有需要且可安全施工的位置。已满足 " + satisfied
                    + " 格，保护或占位 "
                    + protectedCells
                    + " 格，不支持 "
                    + unsupported
                    + " 格";
                return true;
            }
            if (!work.drain(slice, s.work::add)) return false;
            if (!HologramBuildPreparation.aeReady(s)) return true;
            HologramMaterialWorkflow.validateConfirmation(s,approval);
            if (HologramPlacementJobs.approvedMaterials(s,approval)) {
                HologramPlacementJobs.begin(s, approval);
                if (s.job == 1) s.blueprintAwaitingConfirmation = false;
            }
            return true;
        }
    }

    static void writeNetworks(HologramSession s, NBTTagCompound state) {
        if (s.executionNetworks.isEmpty()) return;
        try {
            HologramNetworkBindings.SnapshotTask task = new HologramNetworkBindings.SnapshotTask(
                s.player,
                s.networkBindings);
            for (NBTTagCompound descriptor : s.executionNetworks.values()) task.add(descriptor);
            while (!task.step(128, Long.MAX_VALUE)) {}
            state.setTag("networkBindings", task.result);
            state.setBoolean("networkBindingRequested", s.networkBindingRequested);
        } catch (IOException | RuntimeException invalid) {
            state.setString("networkBindingError", HologramAePolicy.fallback(invalid.getMessage()));
        }
    }
}
