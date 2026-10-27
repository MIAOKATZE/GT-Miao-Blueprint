package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

/** Exact admission continuation. No world transaction is permitted before all stages publish together. */
final class HologramBuildTask {

    private final Thread owner = Thread.currentThread();
    private final HologramCapture capture;
    private final HologramPresets.Preset preset;
    private final World world;
    private final String session, tool, actorName, active;
    private final UUID actor;
    private final long revision, nonce, voucherRevision, providerGeneration, nativeGeneration, localGeneration;
    private final int mode, scope, layer, selected, x, y, z, facing, settings;
    private final boolean voucher, creative;
    private final NBTTagCompound action, reviewedQuote;
    private final int reviewedMissing;
    private HologramMaterialQuote.RecheckTask quote;
    private HologramResourceTask resources;
    private HologramResourcePlanner.ResourceRowsTask caches;
    private HologramWorkOrderTask ordering;
    private HologramNetworkGroupPreparation networks;
    private NBTTagList cacheRows;
    private int stage, cursor, aeStage, blocked, satisfied, protectedCells, unsupported;
    private boolean builtMember, done, partial, compared;
    private Boolean quantumCleared;
    private String reason = "";

    HologramBuildTask(HologramSession s, NBTTagCompound action) {
        capture = s.capture;
        preset = s.virtualPreset;
        world = s.player.worldObj;
        session = s.id;
        tool = s.toolId;
        actor = s.player.getUniqueID();
        actorName = s.player.getCommandSenderName();
        revision = s.planRevision;
        nonce = s.placementReviewNonce;
        localGeneration = s.localPlanGeneration;
        voucherRevision = DecorationVoucherConfig.revision();
        providerGeneration = com.miaokatze.gtmb.api.ResourceContainers.generation();
        nativeGeneration = HologramNativePlacement.generation();
        mode = s.mode;
        scope = s.scope;
        layer = s.layer;
        selected = s.selected;
        x = s.x;
        y = s.y;
        z = s.z;
        facing = s.facing;
        active = s.blueprintActiveStructure;
        voucher = s.jobVoucherRequested;
        creative = s.player.capabilities.isCreativeMode;
        settings = settings(s);
        this.action = action == null ? null : (NBTTagCompound) action.copy();
        reviewedQuote = s.materialQuote;
        reviewedMissing = s.planMissing;
    }

    private static int settings(HologramSession s) {
        HologramMaterials.Settings v = s.materialSettings;
        return (v.main ? 1 : 0) | (v.containers ? 2 : 0)
            | (v.me ? 4 : 0)
            | v.priority << 3
            | v.recoveryDestination << 6;
    }

    boolean current(HologramSession s) {
        return !s.cancelled && session.equals(s.id)
            && capture == s.capture
            && preset == s.virtualPreset
            && world == s.player.worldObj
            && tool.equals(s.toolId)
            && revision == s.planRevision
            && nonce == s.placementReviewNonce
            && localGeneration == s.localPlanGeneration
            && !s.localPlanPending
            && voucherRevision == DecorationVoucherConfig.revision()
            && providerGeneration == com.miaokatze.gtmb.api.ResourceContainers.generation()
            && nativeGeneration == HologramNativePlacement.generation()
            && actor.equals(s.player.getUniqueID())
            && actorName.equals(s.player.getCommandSenderName())
            && mode == s.mode
            && scope == s.scope
            && layer == s.layer
            && selected == s.selected
            && x == s.x
            && y == s.y
            && z == s.z
            && facing == s.facing
            && Objects.equals(active, s.blueprintActiveStructure)
            && voucher == s.jobVoucherRequested
            && creative == s.player.capabilities.isCreativeMode
            && settings == settings(s);
    }

    String phase() {
        if (stage == 10) return networks.phase();
        if (stage == 6 && quote != null) return quote.phase();
        if (stage == 6 && resources != null) return resources.phase();
        return stage == 0 ? "build.prepare"
            : stage == 1 || stage == 2 ? "build.ae"
                : stage == 3 ? "build.world"
                    : stage == 4 ? "build.original"
                        : stage == 5 ? "build.work"
                            : stage == 7 ? "build.review" : stage == 8 ? "build.order" : "build.admit";
    }

    long completed() {
        if (stage == 10) return networks.completed();
        if (stage == 8 && ordering != null) return ordering.completedWork();
        if (resources != null && stage == 6) return resources.completed();
        return quote != null && stage == 6 ? quote.completed() : cursor;
    }

    long total() {
        if (stage == 10) return networks.total();
        if (stage == 8 && ordering != null) return ordering.totalWork();
        if (resources != null && stage == 6) return resources.total();
        return quote != null && stage == 6 ? quote.total() : capture.cells.size();
    }

    boolean step(HologramSession s, int maxWork, long maxNanos) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Build admission requires owner thread");
        if (!current(s) || !HologramSessionPolicy.valid(s)) {
            reject(s, "确认期间计划、玩家、工具或目标已变化，请重新核对材料与位置");
            return true;
        }
        HologramPlanBudget budget = new HologramPlanBudget(maxWork, maxNanos);
        try {
            while (!done && budget.take()) {
                advance(s);
                if (networks != null && networks.waiting()) break;
            }
        } catch (IOException | RuntimeException | LinkageError invalid) {
            reject(s, "施工检查未完成，请重新核对：" + HologramAePolicy.fallback(invalid.getMessage()));
        }
        return done;
    }

    private void reject(HologramSession s, String status) {
        cancel();
        done = true;
        s.forcePlacementReady = false;
        s.status = status;
        s.phase = "IDLE";
    }

    private void next() {
        cursor = 0;
        stage++;
    }

    private boolean ae() {
        return preset != null && preset.schema >= 2;
    }

    void cancel() {
        if (networks != null) networks.cancel();
    }

    private void advance(HologramSession s) throws IOException {
        if (stage == 10) {
            if (!current(s)) {
                reject(s, "材料确认已更新，请重新核对");
                return;
            }
            networks.advance(s);
            if (networks.done()) done = true;
        } else if (stage == 0) {
            // Releasing previous work is also bounded; a large previous job must not hide in List.clear().
            if (!s.work.isEmpty()) {
                s.work.remove(s.work.size() - 1);
                return;
            }
            if (capture == null || capture.incomplete
                || capture.pending
                || s.capturePending
                || s.expectedBlocks.size() != capture.cells.size()
                || s.expectedMeta.size() != capture.cells.size()
                || s.expectedTiles.size() != capture.cells.size()
                || s.expectedFacings.size() != capture.cells.size()
                || s.planStatus.size() != capture.cells.size()) {
                reject(s, "请等待完整结构与精确材料计划，再确认施工");
                return;
            }
            next();
        } else if (stage == 1) {
            if (!ae()) {
                next();
                return;
            }
            if (aeStage == 0) {
                if (HologramDeidentification.level(preset) != 0 || cursor == capture.cells.size()) {
                    aeStage++;
                    cursor = 0;
                    return;
                }
                HologramCapture.Cell cell = capture.cells.get(cursor++);
                if (!HologramSessionPolicy.ignoredPlacementAir(s, cell, cell.stableIndex)
                    && HologramSessionPolicy.inScope(s, cell, cell.stableIndex)
                    && HologramCellPolicy.foreignBlueprintOwner(s, cell))
                    reject(s, "0 级蓝图包含其他玩家所有权；请主动选择至少 1 级去标识，再按当前玩家身份放置");
                return;
            }
            if (aeStage == 1) {
                if (quantumCleared == null) quantumCleared = HologramBlueprintProcessing.quantumCleared(preset);
                if (quantumCleared || cursor == capture.cells.size()) {
                    aeStage++;
                    cursor = 0;
                    return;
                }
                HologramCapture.Cell cell = capture.cells.get(cursor++);
                NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
                if (payload != null && (payload.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)
                    || HologramNetworkBindings.node(cell.block) && !payload.hasKey(HologramNetworkBindings.GROUP))) {
                    networks = new HologramNetworkGroupPreparation(s);
                    stage = 10;
                    s.status = "正在自动生成量子设备分组，完成后将更新材料确认";
                }
                return;
            }
            if (cursor == capture.cells.size()) {
                next();
                return;
            }
            HologramCapture.Cell cell = capture.cells.get(cursor++);
            if (HologramSessionPolicy.ignoredPlacementAir(s, cell, cell.stableIndex)) return;
            NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
            if (!cell.excluded && HologramSessionPolicy.membershipEnabled(s, cell)) {
                try {
                    HologramNetworkBindings.validate(payload, s.player, s.networkBindings);
                } catch (IOException missingBinding) {
                    s.networkBindingRequested = true;
                    throw missingBinding;
                }
                if (HologramAePolicy.handles(cell.block, cell.tileNbt)
                    && HologramAeBlueprint.analyze(cell.block, cell.meta, payload).blocking)
                    reject(s, "AE 蓝图仍有阻止施工的问题，请打开相关方块预览并删除或替换不安全标记");
            }
        } else if (stage == 2) {
            if (!ae() || cursor == preset.aeIssues.size()) {
                next();
                return;
            }
            HologramAePolicy.Issue issue = preset.aeIssues.get(cursor++);
            if (!"blocker".equals(issue.severity)) return;
            HologramCapture.Cell cell = capture.positions
                .get((s.x + issue.x) + "," + (s.y + issue.y) + "," + (s.z + issue.z));
            if (cell != null && !cell.excluded && HologramSessionPolicy.membershipEnabled(s, cell))
                reject(s, "AE 蓝图仍有未明确处理的净化问题；请在预览中清除对应标记，再核对施工计划");
        } else if (stage == 3) {
            if (action == null || cursor == capture.cells.size()) {
                next();
                return;
            }
            int index = cursor++;
            if (HologramSessionPolicy.inScope(s, capture.cells.get(index), index)
                && !HologramPlanPolicy.expectedCurrent(s, index)) {
                reject(s, "现场已变化，请重新核对更新后的材料与位置");
                if (s.virtual && preset != null && preset.schema >= 2)
                    HologramDeferredPlanRefresh.request(s, Collections.emptySet());
            }
        } else if (stage == 4) {
            if (mode != 1) {
                next();
                return;
            }
            if (s.virtual || s.machine == null || s.target == null) {
                reject(s, "替换需要绑定真实控制器且原结构完整构建，请先补建或重新扫描");
                return;
            }
            if (cursor < capture.cells.size()) {
                int state = HologramPlanPolicy.replacementCellBuilt(s, capture.cells.get(cursor++));
                if (state < 0) reject(s, "替换需要原结构完整构建，请先补建或重新扫描");
                builtMember |= state > 0;
            } else if (!builtMember) reject(s, "替换需要原结构完整构建，请先补建或重新扫描");
            else next();
        } else if (stage == 5) {
            if (cursor < capture.cells.size()) {
                int index = cursor++;
                HologramCapture.Cell cell = capture.cells.get(index);
                if (HologramSessionPolicy.ignoredPlacementAir(s, cell, index)
                    || !HologramSessionPolicy.inScope(s, cell, index)) return;
                if (mode == 0 && !creative && "blocked".equals(HologramPlanPolicy.classify(s, cell, index))) blocked++;
                if ("unsupported".equals(cell.status) && !cell.resourceError.isEmpty())
                    throw new IOException("蓝图资源数据无法安全解析，未施工：" + cell.resourceError);
                String status = s.planStatus.get(index);
                if ("pending".equals(status)) s.work.add(index);
                else if (!cell.anchor) {
                    if ("satisfied".equals(status)) satisfied++;
                    else {
                        if ("unsupported".equals(status)) unsupported++;
                        else protectedCells++;
                        if (reason.isEmpty()) reason = HologramPlanPolicy.reason(s, cell, index);
                    }
                }
            } else if (blocked > 0) reject(s, "选定范围有 " + blocked + " 格结构占位被遮挡，已整体拒绝补建，请清空后重试");
            else {
                if (reviewedQuote != null && reviewedQuote.getBoolean("complete")
                    && reviewedQuote.getLong("planRevision") == revision
                    && session.equals(reviewedQuote.getString("session")))
                    quote = new HologramMaterialQuote.RecheckTask(s);
                else resources = new HologramResourceTask(s, new HologramPlanClassifier(s));
                s.materialContext = new HologramMaterials.Context(s.player, s.materialSettings, s.slot);
                next();
            }
        } else if (stage == 6) {
            if (quote != null && !quote.done()) quote.advance();
            else if (resources != null && !resources.done()) resources.advance();
            else {
                if (!current(s)) {
                    reject(s, "材料确认已更新，请重新核对");
                    return;
                }
                if (quote != null) quote.publish();
                else if (s.planMissing > reviewedMissing) {
                    HologramMaterialWorkflow.updatedReview(s, "可用材料减少，缺料方案已更新；请重新核对材料后确认");
                    done = true;
                    return;
                }
                next();
            }
        } else if (stage == 7) {
            if (!compared && quote != null
                && cursor < s.materialQuote.getTagList("rows", 10)
                    .tagCount()) {
                NBTTagCompound row = s.materialQuote.getTagList("rows", 10)
                    .getCompoundTagAt(cursor);
                NBTTagList before = reviewedQuote.getTagList("rows", 10);
                int index = cursor++;
                if (!row.getBoolean("optionalCache") && (!creative || "essentia".equals(row.getString("kind")))
                    && (index >= before.tagCount()
                        || HologramMaterialWorkflow.missingIncreased(row, before.getCompoundTagAt(index)))) {
                    HologramMaterialWorkflow.updatedReview(s, "可用材料减少，缺料方案已更新；请重新核对材料后确认");
                    done = true;
                }
                return;
            }
            compared = true;
            if (s.virtual && action != null) {
                if (caches == null) caches = HologramResourcePlanner.beginCacheResourceRows(s);
                if (!caches.step(1, Long.MAX_VALUE)) return;
                if (cacheRows == null) {
                    cacheRows = caches.result();
                    cursor = 0;
                }
                if (cursor < cacheRows.tagCount()) {
                    if (cacheRows.getCompoundTagAt(cursor++)
                        .getBoolean("emptyFallback") && !action.getBoolean("allowEmptyCache")) {
                        HologramMaterialWorkflow.updatedReview(s, "设备缓存报价已变化，本次将空缓存放置；请核对后确认");
                        done = true;
                    }
                    return;
                }
            }
            s.forcePlacementReady = s.virtual && action != null
                && reviewedMissing > 0
                && action.getBoolean("forceExplicit");
            if (!s.planRecoveryFits) {
                reject(
                    s,
                    s.blueprintResourceMissing.isEmpty() ? "背包无法容纳回收，请整理后重试"
                        : "蓝图资源校验失败，未施工：" + s.blueprintResourceMissing);
                return;
            }
            if (s.planMissing > 0 && (!s.virtual || !s.forcePlacementReady)) {
                reject(
                    s,
                    s.virtual
                        ? (s.blueprintResourceMissing.isEmpty() ? "蓝图材料不足" : s.blueprintResourceMissing)
                            + "；请核对仅放已有材料的方案后明确确认，权限与占位保护仍有效"
                        : "施工材料不足，请补齐后重试");
                return;
            }
            partial = s.virtual && s.planMissing > 0 && s.forcePlacementReady;
            s.forcePlacementReady = false;
            ordering = s.virtual
                ? new HologramWorkOrderTask(s.work, (a, b) -> HologramPlacementJobs.compareWork(s, a, b))
                : null;
            next();
        } else if (stage == 8) {
            if (ordering != null && !ordering.done()) ordering.advance();
            else next();
        } else {
            if (s.work.isEmpty()) {
                s.status = protectedCells == 0 && unsupported == 0 && satisfied > 0
                    ? "目标范围已满足，无需施工；未放置方块、未扣材料（" + satisfied + " 格）"
                    : "没有可安全施工的位置：已满足 " + satisfied
                        + " 格，保护或占位 "
                        + protectedCells
                        + " 格，不支持 "
                        + unsupported
                        + " 格"
                        + (reason.isEmpty() ? "" : "；" + reason);
                done = true;
                return;
            }
            if (action != null && s.virtual) HologramMaterialWorkflow.validateConfirmation(s, action);
            if (!current(s) || !HologramSessionPolicy.valid(s)) {
                reject(s, "确认已过期，请重新核对施工计划");
                return;
            }
            s.replacementAuthorized = mode == 1;
            s.cursor = s.completed = 0;
            s.placementFinishTask = null;
            HologramSession.resetJobCounts(s, partial);
            s.job = 1;
            if (s.virtual) {
                s.placementReviewNonce++;
                if (s.materialQuote != null) s.materialQuote.setLong("placementReviewNonce", s.placementReviewNonce);
                s.blueprintAwaitingConfirmation = false;
            }
            s.phase = "RUNNING";
            s.status = "正在直接施工";
            done = true;
        }
    }
}
