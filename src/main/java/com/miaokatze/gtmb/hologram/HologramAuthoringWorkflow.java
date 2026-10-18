package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;

/** Blueprint authoring, processing choices and source-switch/save review state transitions. */
final class HologramAuthoringWorkflow {

    private HologramAuthoringWorkflow() {}

    /** Dedicated mark-only actions are dispatched before material flush/recovery and ordinary cell selection. */
    static boolean aeAction(HologramSession s, NBTTagCompound action, String op) {
        if (!("aePreviewOpen".equals(op) || "aePreviewEdit".equals(op) || "aePreviewClose".equals(op))) return false;
        long seq = action.getLong("editSeq");
        HologramEditState rollback = null;
        long originalRevision = s.planRevision;
        HologramAeProtocol.Preview originalPreview = s.ae.preview;
        HologramAeProtocol.Review originalReview = s.ae.review;
        try {
            if ("aePreviewClose".equals(op)) {
                if (s.ae.preview != null && s.ae.preview.token.equals(action.getString("token"))) s.ae.preview = null;
                return true;
            }
            if (!s.virtual || s.virtualPreset == null
                || s.capture == null
                || s.capture.incomplete
                || s.capturePending
                || s.capture.pending
                || s.job == 1
                || s.job == 2
                || s.job == 4) throw new java.io.IOException("AE preview requires a complete editable blueprint");
            if (!action.hasKey("x", 3) || !action.hasKey("y", 3)
                || !action.hasKey("z", 3)
                || !action.hasKey("structureId", 8)) throw new java.io.IOException("AE preview coordinate is missing");
            String active = s.blueprintActiveStructure;
            if (!active.equals(action.getString("structureId")))
                throw new java.io.IOException("AE preview structure changed");
            HologramCapture.Cell cell = s.capture.positions
                .get(action.getInteger("x") + "," + action.getInteger("y") + "," + action.getInteger("z"));
            if (cell == null || !HologramSessionPolicy.editableStructureCell(s, cell)
                || !HologramSessionPolicy.membershipEnabled(s, cell)
                || cell.excluded
                || !HologramAePolicy.handles(cell.block, cell.tileNbt))
                throw new java.io.IOException("AE preview cell is not editable");
            int index = HologramSessionPolicy.cellIndex(s, cell);
            ItemStack target = HologramCellPolicy.targetFor(s, cell, index);
            // The server draft's independent shell identity authorizes editing, even while its contents block
            // placement.
            if (target == null
                ? cell.blueprintBlock.isEmpty()
                    || !cell.blueprintBlock.equals(String.valueOf(Block.blockRegistry.getNameForObject(cell.block)))
                : (!cell.blueprintItem.isEmpty()
                    ? !cell.blueprintItem.equals(String.valueOf(Item.itemRegistry.getNameForObject(target.getItem())))
                        || cell.blueprintDamage != target.getItemDamage()
                    : Block.getBlockFromItem(target.getItem()) != cell.block))
                throw new java.io.IOException("AE target block family changed");
            HologramAeProtocol.Preview preview = s.ae.preview;
            if ("aePreviewEdit".equals(op) && preview != null
                && preview.current(
                    s.id,
                    s.toolId,
                    active,
                    s.planRevision,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.block,
                    cell.meta,
                    cell.tileNbt)
                && preview.replay(action)) {
                s.ae.ack = (NBTTagCompound) preview.lastAck.copy();
                return true;
            }
            if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision)
                throw new java.io.IOException("AE preview revision changed");
            HologramAeBlueprint.Analysis analysis = HologramAeBlueprint
                .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
            if ("aePreviewOpen".equals(op)) {
                s.ae.preview = new HologramAeProtocol.Preview(
                    s.id,
                    s.toolId,
                    active,
                    s.planRevision,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.block,
                    cell.meta,
                    cell.tileNbt);
                s.ae.ack = null;
                return true;
            }
            HologramAeProtocol.validateEdit(action, s.ae, s.player.worldObj.getTotalWorldTime());
            if (preview == null || !preview.token.equals(action.getString("token"))
                || !preview.current(
                    s.id,
                    s.toolId,
                    active,
                    s.planRevision,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.block,
                    cell.meta,
                    cell.tileNbt)
                || seq <= preview.lastSeq) throw new java.io.IOException("AE preview token/sequence changed");
            HologramAeBlueprint.Slot slot = null;
            for (HologramAeBlueprint.Slot candidate : analysis.slots)
                if (candidate.id.equals(action.getString("slotId"))) slot = candidate;
            if (slot == null || ("SET".equals(action.getString("mutation")) && !slot.enabled))
                throw new java.io.IOException("AE slot is invalid or disabled");
            ItemStack marked = HologramAeProtocol.candidate(action, slot);
            HologramAeBlueprint.Analysis updated = HologramAeBlueprint
                .updateSlot(cell.block, cell.meta, analysis.payload(), slot.id, marked);
            boolean rebindShell = marked == null && "readonly".equals(slot.kind)
                && (slot.id.startsWith("def:") || slot.id.matches("@part=\\d+/def:.*"));
            ItemStack remainingShell = rebindShell
                ? HologramAeBlueprint.sampleIdentity(cell.block, cell.meta, updated.payload())
                : null;
            if (rebindShell && remainingShell == null
                && cell.block.getClass()
                    .getName()
                    .equals("codechicken.multipart.BlockMultipart")
                && HologramAeBlueprint.withoutAeMultipart(updated.payload())
                    .getTagList("parts", 10)
                    .tagCount() > 0)
                throw new java.io.IOException(
                    "Remaining non-AE multipart identity cannot be rebound safely; replace this draft cell explicitly");
            List<HologramAePolicy.Issue> nextIssues = HologramAePolicy.resolveAfterMutation(
                s.virtualPreset.aeIssues,
                cell.x - s.x,
                cell.y - s.y,
                cell.z - s.z,
                slot.id,
                analysis,
                updated);
            String payload = HologramBlueprintNbt.encodeCanonical(cell.block, updated.payload());
            // Serialize/validate everything before replacing the single server-owned draft payload.
            HologramAeProtocol.bytes(updated.payload(), HologramAeProtocol.MAX_STACK_AGGREGATE);
            preview.snapshot(updated);
            HologramEditState before = new HologramEditState(s);
            rollback = before;
            cell.tileNbt = payload == null ? "" : payload;
            cell.itemNbt = "";
            cell.resourceFingerprint = "";
            cell.resourcePlanKey = null;
            if (rebindShell) rebindAeShell(s, cell, index, remainingShell);
            s.virtualPreset.aeIssues = nextIssues;
            s.virtualEdited.add(HologramValues.position(cell));
            HologramResourcePlanner.refreshPlan(s);
            s.ae.preview = rebindShell && remainingShell == null ? null : preview;
            s.ae.result(seq, true, "ae.edit.accepted", s.planRevision);
            preview.remember(action, s.ae.ack, s.planRevision, cell.tileNbt);
            HologramBlueprintEditor.remember(s.undoEdits, before);
            s.redoEdits.clear();
            rollback = null;
            s.forcePlacementReady = false;
            s.placementReviewNonce++;
            s.status = "AE 蓝图标记已更新；未移动真实物品，材料仅在施工时扣除。请重新核对材料与保存报告。";
        } catch (java.io.IOException | RuntimeException invalid) {
            if (rollback != null) {
                rollback.apply(s);
                try {
                    HologramResourcePlanner.refreshPlan(s);
                } catch (RuntimeException failedRollbackPlanning) {
                    // Original draft is restored; incomplete planning stays blocked until an explicit rescan.
                    s.capture.incomplete = true;
                }
                s.planRevision = originalRevision;
                s.ae.preview = originalPreview;
                s.ae.review = originalReview;
            }
            if ("aePreviewEdit".equals(op)) s.ae.result(seq, false, "ae.edit.rejected", s.planRevision);
            s.status = "AE 蓝图操作被拒绝：" + HologramAePolicy.fallback(invalid.getMessage()) + "；原标记保留";
        }
        return true;
    }

    /** Rebind only a server-derived surviving native shell; never reinterpret a part item as a block. */
    static void rebindAeShell(HologramSession s, HologramCapture.Cell cell, int index, ItemStack sample) {
        String key = HologramValues.position(cell);
        s.allocationsFrozen = true;
        cell.candidates = null;
        cell.candidateComplete = false;
        cell.resourceFingerprint = "";
        cell.resourcePlanKey = null;
        cell.resourcePlan = null;
        cell.targetDisplay = new NBTTagCompound();
        if (sample == null) {
            // Native hosts without a part are air. Preserve group membership and never clear the live world.
            cell.block = Blocks.air;
            cell.meta = 0;
            cell.blueprintBlock = "minecraft:air";
            cell.blueprintItem = "";
            cell.blueprintDamage = 0;
            cell.unknown = false;
            cell.excluded = true;
            cell.userPosition = true;
            cell.tileNbt = "";
            cell.itemNbt = "";
            cell.structureTargets = new ArrayList<>();
            s.pins.remove(index);
            s.coordinatePins.remove(key);
            s.autoTargets.remove(index);
            s.frozenAutoTargets.remove(key);
            s.facingPins.remove(key);
            s.suppressedAuto.add(key);
            if (index < s.planTargets.size()) s.planTargets.set(index, null);
            return;
        }
        cell.blueprintBlock = String.valueOf(Block.blockRegistry.getNameForObject(cell.block));
        cell.blueprintItem = String.valueOf(Item.itemRegistry.getNameForObject(sample.getItem()));
        cell.blueprintDamage = sample.getItemDamage();
        cell.unknown = false;
        // A native host's declaration is its stored shell identity. Do not add unrelated client choices.
        if (cell.structureTargets != null)
            cell.structureTargets = new ArrayList<>(Collections.singletonList(sample.copy()));
        s.pins.put(index, sample.copy());
        s.coordinatePins.put(key, sample.copy());
        if (s.autoTargets.containsKey(index)) s.autoTargets.put(index, sample.copy());
        if (s.frozenAutoTargets.containsKey(key)) s.frozenAutoTargets.put(key, sample.copy());
        if (index < s.planTargets.size()) s.planTargets.set(index, sample.copy());
    }

    static void beginAeSaveReview(HologramSession s, HologramPresets.Preset source, String operation, boolean update)
        throws java.io.IOException {
        requireBlueprintProcessing(s, source);
        if (source.id == null || source.id.isEmpty()) source.id = java.util.UUID.randomUUID()
            .toString();
        HologramAePolicy.Draft draft = HologramAePolicy.sanitize(source);
        HologramBlueprintProcessing.enforcePolicy(draft.preset);
        HologramPresets.validate(draft.preset);
        boolean containsAe = false;
        for (HologramPresets.Cell cell : draft.preset.cells) {
            NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
            if (HologramAeBlueprint.supports(cell.block(), payload)
                || payload != null && payload.hasKey(HologramNetworkBindings.GROUP)) {
                containsAe = true;
                break;
            }
        }
        if (!containsAe && !draft.blocking) {
            HologramBlueprintMetadata.captureVersions(draft.preset);
            HologramLibraryWorkflow.saveReviewedDraft(s, draft.preset, operation, update, false);
            return;
        }
        // One report covers all cells, including disabled/hidden slots; costs never reserve/extract here.
        HologramBlueprintResources.Plan materials = HologramAePolicy.materials(draft.preset);
        HologramBlueprintMetadata.captureVersions(draft.preset);
        s.ae.review = new HologramAeProtocol.Review(
            s.id,
            s.toolId,
            s.planRevision,
            operation,
            update,
            draft,
            materials.entries(HologramMaterialAccess.materialSource(s)));
        s.blueprintOperation = operation;
        s.blueprintOperationSuccess = false;
        s.status = "保存前请核对完整报告：将清空存储元件内容、重建 AE 网络与安全身份；样板、升级卡及空元件在施工时需要材料。明确继续后才保存净化蓝图。";
    }

    static void confirmAeSave(HologramSession s, NBTTagCompound action) throws java.io.IOException {
        HologramAeProtocol.Review review = s.ae.review;
        if (review == null) throw new java.io.IOException("请先核对当前保存报告");
        HologramPresets.Preset preset = review.approve(action, s.id, s.toolId, s.planRevision);
        HologramLibraryWorkflow.saveReviewedDraft(s, preset, review.operation, review.update, true);
    }

    static void deidentifyNetworkDraft(HologramSession s) throws java.io.IOException {
        if (!s.virtual || s.virtualPreset == null || s.capture == null)
            throw new java.io.IOException("Load an editable blueprint before clearing network identities");
        deidentifyDraft(s, Math.max(1, HologramDeidentification.level(s.virtualPreset)));
    }

    static void deidentifyDraft(HologramSession s, int level) throws java.io.IOException {
        if (!s.virtual || s.virtualPreset == null || s.capture == null) throw new java.io.IOException("请先载入可编辑蓝图");
        HologramAePolicy.Draft sanitized = HologramAePolicy.sanitize(
            HologramDeidentification.apply(HologramBlueprintProjection.blueprint(s, s.virtualPreset.name), level));
        HologramPresets.validate(sanitized.preset);
        HologramEditState before = new HologramEditState(s);
        Map<HologramCapture.Cell, Integer> indices = new java.util.IdentityHashMap<>();
        for (int i = 0; i < s.capture.cells.size(); i++) indices.put(s.capture.cells.get(i), i);
        for (HologramPresets.Cell saved : sanitized.preset.cells) {
            HologramCapture.Cell cell = s.capture.positions
                .get((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z));
            if (cell == null) continue;
            if (saved.item != null && (!saved.item.equals(cell.blueprintItem) || saved.damage != cell.blueprintDamage)
                && HologramNativePlacement.supported(cell.block)
                && !saved.excluded) rebindAeShell(s, cell, indices.get(cell), saved.stack());
            cell.tileNbt = saved.tileNbt == null ? "" : saved.tileNbt;
            cell.itemNbt = saved.itemNbt == null ? "" : saved.itemNbt;
            cell.excluded = saved.excluded;
            cell.resourceFingerprint = "";
            cell.resourcePlanKey = null;
            s.virtualEdited.add(HologramValues.position(cell));
        }
        s.virtualPreset = sanitized.preset;
        s.networkBindings.clear();
        s.ae.invalidate();
        HologramBlueprintEditor.remember(s.undoEdits, before);
        s.redoEdits.clear();
        HologramResourcePlanner.refreshPlan(s);
        if (s.virtualPlacement && s.virtualLocked && s.placementReviewIssued) s.placementReviewNonce++;
    }

    /** Repeated review controls reuse the published result; they do not authorize another planning pass. */
    static boolean unchangedBlueprintAction(HologramSession s, NBTTagCompound action, String op) {
        if (!s.virtual || s.virtualPreset == null || s.capture == null || s.localPlanPending) return false;
        if ("removeBlueprintObstructions".equals(op)) {
            if (!s.virtualPlacement || !s.virtualLocked
                || !s.blueprintAwaitingConfirmation
                || !s.placementReviewIssued
                || !s.id.equals(action.getString("session"))
                || action.getLong("planRevision") != s.planRevision
                || action.getLong("placementReviewNonce") != s.placementReviewNonce
                || !s.localEdits.knownNoBlockers(s)) return false;
            s.status = "当前已审核范围没有需要移除的占位方块";
            return true;
        }
        if ("configurePlacementDeidentification".equals(op) || "deidentifyBlueprint".equals(op)) {
            int requested = action.getInteger("level");
            if (requested < 0 || requested > 10
                || !action.hasKey("planRevision", 4)
                || action.getLong("planRevision") < 0
                || action.getLong("planRevision") > s.planRevision) return false;
            boolean chosen = "configurePlacementDeidentification".equals(op)
                ? s.placementDeidentificationChosen
                    && s.placementDeidentificationLevel == HologramDeidentification.level(s.virtualPreset)
                : s.deidentificationChosen && s.virtualPreset.deidentificationChosen;
            if (!chosen || requested > HologramDeidentification.level(s.virtualPreset)) return false;
            return true;
        }
        if (!("configurePlacementNbt".equals(op) || "configureBlueprintNbt".equals(op))
            || action.hasKey("planRevision", 4) && action.getLong("planRevision") != s.planRevision) return false;
        boolean placement = "configurePlacementNbt".equals(op);
        List<String> current = placement ? HologramSessionPolicy.placementKeys(s) : s.retainedNbtKeys;
        List<String> requested;
        try {
            requested = action.hasKey("all") ? action.getBoolean("all") ? null
                : HologramAuthoringQueries.selectedNbtKeys(action.getTagList("keys", 8)) : current;
        } catch (java.io.IOException invalid) {
            return false;
        }
        boolean sameKeys = current == null || requested == null ? current == requested
            : new java.util.HashSet<>(current).equals(new java.util.HashSet<>(requested));
        if (!sameKeys || placement && action.hasKey("retainCovers")
            && action.getBoolean("retainCovers") != HologramSessionPolicy.placementRetainCovers(s)) return false;
        s.status = "NBT 保留设置未变化，已沿用当前材料审核";
        return true;
    }

    static boolean blueprintAction(HologramSession s, NBTTagCompound action, String op) {
        if (unchangedBlueprintAction(s, action, op)) return true;
        String dependency = blueprintEditDependency(op);
        boolean indexedAnnotations = false;
        try {
            if ("deidentifyBlueprint".equals(op) || "configurePlacementDeidentification".equals(op)) {
                int level = HologramDeidentification.checked(action.getInteger("level"));
                if ("configurePlacementDeidentification".equals(op) && s.virtual
                    && s.capture != null
                    && s.virtualPreset != null
                    && action.hasKey("planRevision", 4)
                    && action.getLong("planRevision") >= 0
                    && action.getLong("planRevision") <= s.planRevision
                    && s.placementDeidentificationChosen
                    && s.placementDeidentificationLevel == HologramDeidentification.level(s.virtualPreset)
                    && s.placementDeidentificationLevel >= level) {
                    // A retry must preserve the real refusal/completion status as well as review authority.
                    return true;
                }
                if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision)
                    throw new java.io.IOException("蓝图已更新，请重新核对去标识等级");
                deidentifyDraft(s, level);
                s.virtualPreset.deidentificationChosen = true;
                s.deidentificationChosen = true;
                if ("configurePlacementDeidentification".equals(op)) {
                    s.placementDeidentificationChosen = true;
                    s.placementDeidentificationLevel = HologramDeidentification.level(s.virtualPreset);
                }
                s.status = "去标识等级 " + HologramDeidentification.level(s.virtualPreset) + " 已应用；物品类型、子类型、数量与配方数据保留";
            } else if ("removeBlueprintObstructions".equals(op)) {
                HologramWorldTransactions.removeBlueprintObstructions(s, action);
            } else if ("excludeBlueprintBlockers".equals(op)) {
                HologramBlueprintEditor.excludeBlueprintBlockers(s, action);
            } else if ("openNetworkBindings".equals(op)) {
                HologramAuthoringWorkflow.ensureNetworkGroups(s);
                s.networkBindingRequested = true;
            } else if ("closeNetworkBindings".equals(op)) {
                s.networkBindingRequested = false;
            } else if ("bindBlueprintNetwork".equals(op)) {
                if (!s.virtual || s.virtualPreset == null || !action.hasKey("slot", 3))
                    throw new java.io.IOException("Load a blueprint before binding its quantum groups");
                String group = action.getString("group");
                boolean present = false;
                for (HologramCapture.Cell cell : s.capture.cells) {
                    NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
                    present |= payload != null && payload.hasKey(HologramNetworkBindings.GROUP)
                        && group.equals(HologramNetworkBindings.group(payload));
                    if (payload != null && payload.hasKey(HologramNetworkBindings.GROUP)
                        && group.equals(HologramNetworkBindings.group(payload))
                        && ("preserve".equals(payload.getString(HologramNetworkBindings.MODE))
                            || payload.hasKey(HologramNetworkBindings.ORIGINAL)))
                        throw new java.io.IOException("私用蓝图保持原量子网络，不能改绑终端；请先按分享流程重新处理");
                }
                if (!present) throw new java.io.IOException("Unknown blueprint quantum group");
                s.networkBindings.select(s.player, group, action.getInteger("slot"));
                s.networkBindingRequested = true;
                s.ae.invalidate();
                HologramResourcePlanner.refreshPlan(s);
                s.status = "量子网络组 " + group.substring(1) + " 已选择目标终端；施工前将再次核对背包物品与目标锚点";
            } else if ("editBlueprintNetworkHint".equals(op)) {
                if (!s.virtual || s.virtualPreset == null || s.capture == null)
                    throw new java.io.IOException("Load an editable blueprint before editing a group hint");
                HologramPresets.Preset edited = HologramBlueprintProjection.blueprint(s, s.virtualPreset.name);
                HologramNetworkBindings.editHint(edited, action.getString("group"), action.getString("hint"));
                HologramPresets.validate(edited);
                HologramEditState before = new HologramEditState(s);
                for (HologramPresets.Cell saved : edited.cells) {
                    HologramCapture.Cell cell = s.capture.positions
                        .get((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z));
                    if (cell != null) {
                        cell.tileNbt = saved.tileNbt == null ? "" : saved.tileNbt;
                        cell.resourceFingerprint = "";
                        cell.resourcePlanKey = null;
                    }
                }
                s.virtualPreset = edited;
                s.ae.invalidate();
                HologramBlueprintEditor.remember(s.undoEdits, before);
                s.redoEdits.clear();
                HologramResourcePlanner.refreshPlan(s);
                s.status = "量子网络组提示已同步到同组全部设备；请保存蓝图";
            } else if ("resetBlueprintNetworks".equals(op)) {
                deidentifyNetworkDraft(s);
                s.status = "蓝图网络身份与外部坐标已清除；卡、空元件和编码样板标记保留，量子设备按原网络分组提示";
            } else if ("confirmAeSave".equals(op)) {
                confirmAeSave(s, action);
            } else if ("cancelAeSave".equals(op)) {
                s.ae.review = null;
                s.status = "保存已取消，蓝图与原蓝图文件保留";
            } else if ("cancelBlueprintPlacement".equals(op)) {
                s.blueprintAwaitingConfirmation = false;
                s.virtualPlacement = false;
                s.presetBrowseForPlacement = false;
                s.blueprintPreview = true;
                s.open = true;
                s.status = "已取消放置确认，蓝图仍可编辑";
            } else if ("confirmBlueprintPlacement".equals(op)) {
                if (!s.virtual || s.virtualPreset == null
                    || !s.virtualPlacement
                    || !s.virtualLocked
                    || !s.blueprintAwaitingConfirmation
                    || !s.placementReviewIssued
                    || !s.id.equals(action.getString("session"))) {
                    s.status = "请先放置蓝图，右击确定位置并查看材料";
                } else if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision) {
                    s.status = "材料计划已更新，请重新核对并确认";
                } else {
                    HologramMaterialWorkflow.validateConfirmation(s, action);
                    HologramBuildPreparation.reviewedBuild(s, action);
                    if (s.job == 1) s.blueprintAwaitingConfirmation = false;
                    s.open = true;
                }
            } else if ("configureBlueprintNbt".equals(op) || "configurePlacementNbt".equals(op)) {
                List<String> keys = action.hasKey("all")
                    ? action.getBoolean("all") ? null
                        : HologramAuthoringQueries.selectedNbtKeys(action.getTagList("keys", 8))
                    : "configurePlacementNbt".equals(op) ? HologramSessionPolicy.placementKeys(s) : s.retainedNbtKeys;
                if ("configurePlacementNbt".equals(op)) {
                    s.placementNbtCustomized = true;
                    s.placementNbtKeys = keys;
                    if (action.hasKey("retainCovers")) s.placementRetainCovers = action.getBoolean("retainCovers");
                } else {
                    s.retainedNbtKeys = keys;
                    if (s.virtualPreset != null) s.virtualPreset.retainedNbtKeys = keys;
                }
                HologramResourcePlanner.refreshPlan(s);
                s.status = "NBT 条目已更新；取消资源条目会保留其余配置";
            } else if ("orderMissingMaterials".equals(op) || "cancelMaterialOrder".equals(op)
                || "setDecorativeVoucher".equals(op)
                || "refreshMaterialQuote".equals(op)) {
                    HologramMaterialWorkflow.action(s, action, op);
                } else if ("exportDraft".equals(op)) {
                    HologramLibraryWorkflow.libraryExportDraft(s.player, action);
                } else if ("saveBlueprint".equals(op) || "saveBlueprintAs".equals(op)
                    || "updatePreset".equals(op)
                    || "saveBlueprintReplace".equals(op)) {
                        s.blueprintOperation = op;
                        s.blueprintOperationSuccess = false;
                        validateProcessedSaveOptions(s, action);
                        String name = action.getString("name");
                        if (name.trim()
                            .isEmpty() && s.virtualPreset != null) name = s.virtualPreset.name;
                        HologramPresets.Preset preset = HologramBlueprintProjection.blueprint(s, name);
                        if (action.hasKey("retainNbt")) preset.retainNbt = action.getBoolean("retainNbt");
                        if (action.hasKey("retainCovers")) preset.retainCovers = action.getBoolean("retainCovers");
                        if (action.hasKey("retainAllNbt"))
                            preset.retainedNbtKeys = action.getBoolean("retainAllNbt") ? null
                                : HologramAuthoringQueries.selectedNbtKeys(action.getTagList("retainedNbtKeys", 8));
                        if (action.hasKey("deidentificationLevel", 3)) {
                            preset = HologramDeidentification.apply(preset, action.getInteger("deidentificationLevel"));
                            preset.deidentificationChosen = true;
                            s.deidentificationChosen = true;
                        }
                        boolean update = !"saveBlueprintAs".equals(op) && preset.id != null && !preset.id.isEmpty();
                        if ("saveBlueprintReplace".equals(op)) {
                            if (!action.getBoolean("confirmOverwrite")) throw new java.io.IOException("覆盖保存必须明确确认目标蓝图");
                            String targetId = action.getString("presetId");
                            if (HologramLibraryFacade.enabled()) {
                                s.libraryReplaceLocalId = HologramLibraryService.localId(targetId, false);
                            } else {
                                if (HologramPresets.get(targetId) == null) throw new java.io.IOException("覆盖目标不存在");
                                preset.id = targetId;
                            }
                            update = true;
                        }
                        if ("updatePreset".equals(op) && !update) throw new java.io.IOException("请先保存蓝图，或使用另存为");
                        if (preset.id == null || preset.id.isEmpty()) preset.id = java.util.UUID.randomUUID()
                            .toString();
                        beginAeSaveReview(s, preset, op, update);
                    } else if ("deletePreset".equals(op)) {
                        s.blueprintOperation = op;
                        s.blueprintOperationSuccess = false;
                        String id = action.getString("presetId");
                        if (HologramLibraryFacade.enabled()) throw new java.io.IOException("请在客户端私库或服务器公库界面删除蓝图");
                        HologramPresets.delete(id);
                        if (id.equals(HologramSessionPolicy.selectedPreset(s))) HologramSessionPolicy.currentTool(s)
                            .getTagCompound()
                            .removeTag(HologramSessionKeys.SELECTED_PRESET);
                        if (s.virtualPreset != null && id.equals(s.virtualPreset.id)) s.virtualPreset.id = null;
                        if (id.equals(s.blueprintSavedId)) s.blueprintSavedId = "";
                        s.blueprintOperationSuccess = true;
                        s.status = "蓝图已从库中删除；当前编辑内容保留，可另存为";
                    } else if ("editBlueprintPrompt".equals(op)) {
                        s.blueprintPrompt = HologramSessionPolicy.boundedPrompt(action.getString("prompt"));
                        if (s.virtualPreset != null) s.virtualPreset.prompt = s.blueprintPrompt;
                        s.status = "蓝图整体提示已更新；请保存修改";
                    } else if ("editCellFlags".equals(op) || "editHintArea".equals(op)) {
                        if (s.capture == null) throw new java.io.IOException("请先载入区域或结构");
                        int[] indices = HologramSessionPolicy.editIndices(s, action);
                        java.util.Set<Integer> dirty = new java.util.LinkedHashSet<>();
                        boolean excludedChanged = false;
                        String prompt = HologramSessionPolicy.boundedPrompt(action.getString("prompt"));
                        for (int index : indices) if (index >= 0 && index < s.capture.cells.size()) {
                            HologramCapture.Cell cell = s.capture.cells.get(index);
                            if (action.hasKey("excluded")) {
                                excludedChanged |= cell.excluded != action.getBoolean("excluded");
                                cell.excluded = action.getBoolean("excluded");
                            }
                            if ("editCellFlags".equals(op) && action.hasKey("prompt")) cell.prompt = prompt;
                            if ("editCellFlags".equals(op) || action.hasKey("excluded")) dirty.add(index);
                        }
                        indexedAnnotations = HologramLocalPlanRefresh.annotations(s, dirty);
                        if (!indexedAnnotations && !dirty.isEmpty() && s.virtualPreset != null) {
                            for (HologramPresets.Cell saved : s.virtualPreset.cells) {
                                HologramCapture.Cell cell = s.capture.positions
                                    .get((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z));
                                if (cell == null) continue;
                                saved.prompt = cell.prompt;
                                saved.excluded = cell.excluded;
                            }
                        }
                        if ("editHintArea".equals(op)) {
                            HologramPresets.HintArea hint = new HologramPresets.HintArea();
                            hint.prompt = prompt;
                            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                            for (int index : indices) if (index >= 0 && index < s.capture.cells.size()) {
                                HologramCapture.Cell cell = s.capture.cells.get(index);
                                minX = Math.min(minX, cell.x - s.x);
                                maxX = Math.max(maxX, cell.x - s.x);
                                minY = Math.min(minY, cell.y - s.y);
                                maxY = Math.max(maxY, cell.y - s.y);
                                minZ = Math.min(minZ, cell.z - s.z);
                                maxZ = Math.max(maxZ, cell.z - s.z);
                            }
                            if (minX != Integer.MAX_VALUE) {
                                hint.x1 = minX;
                                hint.y1 = minY;
                                hint.z1 = minZ;
                                hint.x2 = maxX;
                                hint.y2 = maxY;
                                hint.z2 = maxZ;
                                List<HologramPresets.HintArea> hints = s.virtualPreset == null ? s.blueprintHintAreas
                                    : s.virtualPreset.hintAreas;
                                HologramBlueprintData.updateHintArea(hints, hint);
                            }
                        }
                        dependency = excludedChanged ? "geometry" : "prompt";
                        if (excludedChanged) HologramBlueprintEditor.refreshEdited(s, dirty);
                        s.status = "排除与提示已更新；保存蓝图后保留";
                    } else if ("importArea".equals(op)) {
                        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
                        if (area == null) throw new java.io.IOException("请先选择区域");
                        HologramAreaWorkflow.startAreaImport(s, area);
                    } else if ("structureState".equals(op)) {
                        if (s.virtualPreset != null) {
                            for (HologramPresets.Structure saved : s.virtualPreset.structures)
                                if (saved.id.equals(action.getString("structureId")))
                                    saved.enabled = action.getBoolean("enabled");
                        }
                        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
                        if (s.virtual && area != null
                            && s.areaOverviewOwner != area
                            && s.virtualPreset != area.blueprint) area = null;
                        HologramArea.Structure structure = area == null ? null
                            : area.structures.get(action.getString("structureId"));
                        if (structure == null && s.virtualPreset == null) throw new java.io.IOException("结构不存在");
                        if (structure != null) structure.enabled = action.getBoolean("enabled");
                        HologramResourcePlanner.refreshPlan(s);
                        s.status = action.getBoolean("enabled") ? "结构施工已启用" : "结构施工已停用";
                    } else if ("exportBlueprint".equals(op)) {
                        HologramPresets.Preset preset = HologramLibraryFacade
                            .get(s.player, action.getString("presetId"));
                        if (preset == null) throw new java.io.IOException("请先保存并选择蓝图");
                        if (HologramLibraryFacade.enabled()) {
                            if (!HologramLibraryWorkflow.librarySessionCurrent(s.player, action))
                                throw new java.io.IOException("蓝图会话已更新，请重新导出");
                            HologramLibraryFacade.downloadDraft(
                                s.player,
                                preset,
                                s.id,
                                s.planRevision,
                                "local_" + UUID.randomUUID()
                                    .toString());
                            s.status = "蓝图正在传输到客户端私库";
                            return true;
                        }
                        HologramPresets.exportBlueprint(action.getString("fileId"), preset);
                        s.status = "蓝图已导出到服务器分享目录";
                    } else if ("importBlueprint".equals(op)) {
                        if (HologramLibraryFacade.enabled()) throw new java.io.IOException("请从客户端私库选择并上传蓝图");
                        HologramPresets.Preset preset = HologramPresets.importBlueprint(action.getString("fileId"));
                        s.virtual = true;
                        s.virtualPlacement = false;
                        s.target = null;
                        s.machine = null;
                        s.context = null;
                        s.virtualPreset = preset;
                        s.processingSource = HologramPresets.copy(preset);
                        s.processingPolicy = HologramBlueprintProcessing.policy(preset);
                        s.processingReviewed = false;
                        s.processingWorkflowStarted = false;
                        s.blueprintDirty = true;
                        s.blueprintSource = "imported";
                        s.openPurpose = "blueprintEdit";
                        HologramBlueprintProjection.populateVirtual(s);
                        s.status = "蓝图已导入为作者蓝图；请完成处理流程后保存";
                    } else if ("calibrateBlueprintVersions".equals(op)) {
                        if (s.virtualPreset == null) throw new java.io.IOException("请先打开蓝图编辑");
                        HologramBlueprintMetadata.captureVersions(s.virtualPreset);
                        s.status = "蓝图模组版本已校准；请保存修改";
                    } else if ("editMove".equals(op)) {
                        HologramBlueprintEditor.moveBlueprintCells(s, action);
                    } else return false;
            if (dependency != null) blueprintEdited(s, dependency, indexedAnnotations);
        } catch (java.io.IOException | RuntimeException failure) {
            s.status = "蓝图操作失败：" + failure.getMessage();
        }
        return true;
    }

    static boolean changesBlueprintSource(String op) {
        return "editPreset".equals(op) || "previewPreset".equals(op)
            || "choosePlacementPreset".equals(op)
            || "resetDraft".equals(op)
            || "importBlueprint".equals(op);
    }

    static boolean allowBlueprintDiscard(HologramSession s, NBTTagCompound action, String op, String id) {
        boolean dirty = blueprintHasUnsavedChanges(s);
        if (!dirty || action.getBoolean("confirmDiscard")) {
            clearSourceSwitch(s);
            return true;
        }
        clearSourceSwitch(s);
        s.discardConfirmationRequired = true;
        s.discardTargetOp = op;
        s.discardTargetId = id == null ? "" : id;
        s.status = "当前蓝图有未保存修改；请明确确认放弃修改后再切换蓝图";
        return false;
    }

    static boolean blueprintHasUnsavedChanges(HologramSession s) {
        return s.blueprintDirty || s.capture != null && !s.virtualPlacement && s.blueprintSource.isEmpty();
    }

    static void clearSourceSwitch(HologramSession s) {
        s.pendingSourceAction = new NBTTagCompound();
        s.pendingSourceTarget = null;
        s.pendingSourceContext = null;
        s.pendingSourceArea = null;
        s.pendingSourceRegions = new NBTTagList();
        s.pendingSourcePoints = new int[0];
        s.pendingSourceTool = null;
        s.pendingSourceToolId = "";
        s.discardConfirmationRequired = false;
        s.discardTargetOp = "";
        s.discardTargetId = "";
    }

    static void cancelSourceSwitch(HologramSession s, NBTTagCompound request) {
        String nonce = s.pendingSourceAction.getString("sourceSwitchNonce");
        if (!nonce.isEmpty() && !nonce.equals(request.getString("sourceSwitchNonce"))) return;
        clearSourceSwitch(s);
        s.status = "已取消切换来源，当前蓝图和未保存修改保留";
    }

    static void requestSourceSwitch(HologramSession s, NBTTagCompound next, TileEntity target, Object context,
        HologramArea area) {
        next.setString("session", s.id);
        next.setLong("planRevision", s.planRevision);
        next.setString(
            "sourceSwitchNonce",
            UUID.randomUUID()
                .toString());
        s.pendingSourceAction = (NBTTagCompound) next.copy();
        s.pendingSourceTarget = target;
        s.pendingSourceContext = context;
        s.pendingSourceArea = area;
        s.pendingSourceRegions = area == null ? new NBTTagList()
            : (NBTTagList) area.regionSnapshot()
                .copy();
        s.pendingSourcePoints = area == null ? new int[0]
            : area.points()
                .clone();
        ItemStack held = s.player.getHeldItem();
        s.pendingSourceTool = held == null ? null : held.getItem();
        s.pendingSourceToolId = held == null || !held.hasTagCompound() ? ""
            : held.getTagCompound()
                .getString(HologramSessionKeys.TOOL_ID);
        s.pendingSourceSlot = s.player.inventory.currentItem;
        s.discardConfirmationRequired = true;
        s.discardTargetOp = next.getString("op");
        s.discardTargetId = next.getString("presetId");
        s.open = true;
        s.cancelled = false;
        s.settingsOnly = false;
        s.status = "当前蓝图有未保存修改；请明确确认放弃后再导入新的来源";
    }

    static boolean sourceSwitchConfirmed(HologramSession s, NBTTagCompound request) {
        NBTTagCompound pending = s.pendingSourceAction;
        ItemStack held = s.player.getHeldItem();
        return s.discardConfirmationRequired && request.getBoolean("confirmDiscard")
            && HologramWorkspace.areaSessionOwned(s)
            && held != null
            && held.getItem() == s.pendingSourceTool
            && held.hasTagCompound()
            && s.pendingSourceToolId.equals(
                held.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID))
            && s.player.inventory.currentItem == s.pendingSourceSlot
            && s.id.equals(request.getString("session"))
            && s.libraryWorld == s.player.worldObj
            && !pending.getString("sourceSwitchNonce")
                .isEmpty()
            && pending.getString("sourceSwitchNonce")
                .equals(request.getString("sourceSwitchNonce"))
            && pending.getString("op")
                .equals(request.getString("op"))
            && request.hasKey("planRevision", 4)
            && request.getLong("planRevision") == s.planRevision
            && pending.getLong("planRevision") == s.planRevision;
    }

    static boolean sourceSwitchAreaCurrent(HologramSession s) {
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        return area != null && area == s.pendingSourceArea
            && !area.importing
            && s.pendingSourceRegions.equals(area.regionSnapshot())
            && java.util.Arrays.equals(s.pendingSourcePoints, area.points());
    }

    static void confirmSourceSwitch(HologramSession s, NBTTagCompound request) {
        NBTTagCompound pending = s.pendingSourceAction;
        if (!sourceSwitchConfirmed(s, request)) {
            s.status = "来源确认已变化，请重新选择来源；当前蓝图保留";
            HologramSnapshots.send(s);
            return;
        }
        boolean controller = "openController".equals(pending.getString("op"));
        int x = pending.getInteger("x"), y = pending.getInteger("y"), z = pending.getInteger("z");
        if (controller) {
            if (!HologramWorldFlow.worldPosition(x, y, z, s.player.worldObj.getHeight())
                || !HologramSessionPolicy.previewReach(s.player, x, y, z)
                || !s.player.worldObj.blockExists(x, y, z)) {
                s.status = "控制器来源、位置或可用范围已变化；当前蓝图保留";
                HologramSnapshots.send(s);
                return;
            }
            TileEntity target = s.player.worldObj.getTileEntity(x, y, z);
            Object context = HologramStructureDiscovery.context(target);
            if (target == null || target != s.pendingSourceTarget || context != s.pendingSourceContext) {
                s.status = "控制器来源已变化；当前蓝图保留";
                HologramSnapshots.send(s);
                return;
            }
        } else {
            if (!sourceSwitchAreaCurrent(s)) {
                s.status = "选区已变化，请重新完成框选；当前蓝图保留";
                HologramSnapshots.send(s);
                return;
            }
        }
        HologramArea area = s.pendingSourceArea;
        int side = pending.getInteger("side");
        boolean originalDirty = s.blueprintDirty;
        String originalSource = s.blueprintSource;
        s.blueprintDirty = false;
        s.blueprintSource = "discarded";
        if (controller) {
            clearSourceSwitch(s);
            s.blueprintSaveReceipt = "";
            HologramProjectorWorkflow.open(s.player, x, y, z, side);
        } else {
            if (HologramAreaWorkflow.startAreaImport(s, area)) {
                s.areaImportPreviousDirty = originalDirty;
                s.areaImportPreviousSource = originalSource;
                s.blueprintSaveReceipt = "";
            } else {
                s.blueprintDirty = originalDirty;
                s.blueprintSource = originalSource;
            }
            clearSourceSwitch(s);
            HologramSnapshots.send(s);
        }
    }

    static String blueprintEditDependency(String op) {
        if ("editBlueprintPrompt".equals(op) || "editHintArea".equals(op) || "editBlueprintNetworkHint".equals(op))
            return "prompt";
        if ("configureBlueprintNbt".equals(op) || "configureBlueprintCellNbt".equals(op)) return "nbt";
        if ("deidentifyBlueprint".equals(op) || "resetBlueprintNetworks".equals(op)) return "identity";
        if ("editCellFlags".equals(op) || "editMove".equals(op)
            || "excludeBlueprintBlockers".equals(op)
            || "toggleBlueprintStructure".equals(op)
            || "calibrateBlueprintVersions".equals(op)) return "geometry";
        return null;
    }

    static void blueprintEdited(HologramSession s, String dependency) {
        blueprintEdited(s, dependency, false);
    }

    private static void blueprintEdited(HologramSession s, String dependency, boolean indexedAnnotations) {
        s.blueprintDirty = true;
        clearSourceSwitch(s);
        s.blueprintSaveReceipt = "";
        s.processingReviewed = false;
        s.decorLedger = new HologramDecorMaterials.Ledger();
        try {
            boolean annotations = indexedAnnotations
                || "prompt".equals(dependency) && HologramLocalPlanRefresh.annotations(s, Collections.emptySet());
            HologramBlueprintProcessing.Policy policy = annotations ? s.processingPolicy : null;
            if (s.virtualPreset != null) HologramBlueprintProcessing.invalidate(s.virtualPreset, dependency, policy);
            if (annotations) {
                // Only author annotations changed. Keep raw NBT, identity policy and AE issue ownership intact.
                HologramPresets.Preset source = s.processingSource;
                source.prompt = s.virtualPreset == null ? s.blueprintPrompt : s.virtualPreset.prompt;
                List<HologramPresets.HintArea> hints = s.virtualPreset == null ? s.blueprintHintAreas
                    : s.virtualPreset.hintAreas;
                if (source.hintAreas != hints) {
                    source.hintAreas.clear();
                    source.hintAreas.addAll(hints);
                }
                if (source != s.virtualPreset) HologramBlueprintProcessing.invalidate(source, dependency, policy);
                s.processingPolicy = policy == null ? HologramBlueprintProcessing.policy(source) : policy;
                s.ae.invalidateReviews();
                return;
            }
            HologramPresets.Preset current = HologramBlueprintProjection
                .blueprint(s, s.virtualPreset == null ? "未命名蓝图" : s.virtualPreset.name);
            if (s.processingSource != null && ("prompt".equals(dependency) || "geometry".equals(dependency))) {
                Map<String, HologramPresets.Cell> baseline = new HashMap<>();
                for (HologramPresets.Cell cell : s.processingSource.cells)
                    baseline.put(cell.x + "," + cell.y + "," + cell.z, cell);
                for (HologramPresets.Cell cell : current.cells) {
                    HologramPresets.Cell raw = baseline.get(cell.x + "," + cell.y + "," + cell.z);
                    if (raw != null && java.util.Objects.equals(raw.item, cell.item)
                        && raw.damage == cell.damage
                        && java.util.Objects.equals(raw.block, cell.block)) {
                        cell.tileNbt = raw.tileNbt;
                        cell.itemNbt = raw.itemNbt;
                    }
                }
                current.deidentificationLevel = s.processingSource.deidentificationLevel;
                current.deidentificationVersion = s.processingSource.deidentificationVersion;
                current.deidentificationChosen = s.processingSource.deidentificationChosen;
                current.aePolicyVersion = s.processingSource.aePolicyVersion;
                current.aeIssues = HologramAePolicy.copyIssues(s.processingSource.aeIssues);
            }
            s.processingSource = current;
            HologramBlueprintProcessing.invalidate(s.processingSource, dependency);
            s.processingPolicy = HologramBlueprintProcessing.policy(s.processingSource);
        } catch (java.io.IOException failure) {
            s.processingSource = null;
        }
        s.ae.invalidateReviews();
    }

    static void requireBlueprintProcessing(HologramSession s, HologramPresets.Preset source)
        throws java.io.IOException {
        if (!s.processingReviewed
            && HologramBlueprintProcessing.requiredStages(source, s.dimension, s.x, s.y, s.z) == 0) {
            HologramBlueprintProcessing.Policy automatic = HologramBlueprintProcessing.defaults("self", 0);
            automatic.autoHints = "none";
            automatic.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
            source.processingNbt = HologramBlueprintProcessing
                .process(source, automatic, s.dimension, s.x, s.y, s.z).processingNbt;
            s.processingPolicy = automatic;
            s.processingReviewed = true;
        }
        if (!s.processingReviewed || !HologramBlueprintProcessing.complete(source))
            throw new java.io.IOException("请先完成预设、身份、量子、坐标、NBT 与提示六步处理，再保存作者蓝图");
    }

    /** Storage validation requires an ID; an author draft has no persistent identity until the actual save. */
    static void validateAuthorDraft(HologramPresets.Preset draft) throws java.io.IOException {
        String originalId = draft.id;
        if (draft.id == null || draft.id.isEmpty()) draft.id = "draft";
        try {
            HologramPresets.validate(draft);
        } finally {
            draft.id = originalId;
        }
    }

    static void validateProcessedSaveOptions(HologramSession s, NBTTagCompound request) throws java.io.IOException {
        if (s.virtualPreset == null) throw new java.io.IOException("请先完整载入蓝图，再保存作者蓝图");
        if (!s.processingReviewed) requireBlueprintProcessing(s, s.virtualPreset);
        HologramPresets.Preset reviewed = s.virtualPreset;
        if (request.hasKey("retainNbt") && request.getBoolean("retainNbt") != reviewed.retainNbt
            || request.hasKey("retainCovers") && request.getBoolean("retainCovers") != reviewed.retainCovers
            || request.hasKey("deidentificationLevel", 3)
                && request.getInteger("deidentificationLevel") != HologramDeidentification.level(reviewed))
            throw new java.io.IOException("保存选项与已审核处理结果不同，请返回处理流程重新核对");
        if (request.hasKey("retainAllNbt")) {
            List<String> keys = request.getBoolean("retainAllNbt") ? null
                : HologramAuthoringQueries.selectedNbtKeys(request.getTagList("retainedNbtKeys", 8));
            if (!java.util.Objects.equals(keys, reviewed.retainedNbtKeys))
                throw new java.io.IOException("NBT 选择已变化，请返回处理流程重新核对");
        }
    }

    static boolean processingAction(HologramSession s, NBTTagCompound action, String op) {
        if (!("beginBlueprintReprocessing".equals(op) || "processBlueprint".equals(op)
            || "saveProcessingPreset".equals(op)
            || "loadProcessingPreset".equals(op)
            || "updateProcessingPreset".equals(op)
            || "deleteProcessingPreset".equals(op)
            || "configureBlueprintCellNbt".equals(op)
            || "configureBlueprintQuantumGroups".equals(op)
            || "editBlueprintCellNbt".equals(op))) return false;
        s.processingPresetOperation = op;
        s.processingPresetOperationSuccess = false;
        try {
            if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision)
                throw new java.io.IOException("蓝图计划已更新，请重新核对处理目标");
            if ("beginBlueprintReprocessing".equals(op)) {
                if (s.capture == null || s.capture.incomplete
                    || s.capturePending
                    || s.localPlanPending
                    || s.virtualPlacement
                    || !s.blueprintSaveReceipt.isEmpty()) throw new java.io.IOException("请等待当前蓝图编辑与保存完成后再重新进行预设");
                HologramPresets.Preset current = HologramBlueprintProjection
                    .blueprint(s, s.virtualPreset == null ? "未命名蓝图" : s.virtualPreset.name);
                HologramBlueprintProcessing.Policy policy = s.processingPolicy == null
                    ? HologramBlueprintProcessing.policy(current)
                    : HologramBlueprintProcessing.Policy.read(s.processingPolicy.write());
                policy.confirmedStages.clear();
                policy.resourceDecisions.clear();
                policy.coordinateOverrides.clear();
                policy.cellNbtOverrides.clear();
                s.processingSource = current;
                s.processingPolicy = policy;
                s.processingReviewed = false;
                s.processingWorkflowStarted = true;
                s.blueprintDirty = true;
                s.ae.invalidateReviews();
                clearSourceSwitch(s);
                s.status = "已按当前修改准备六步蓝图预设；已移除的数据不会恢复";
            } else if ("saveProcessingPreset".equals(op)) {
                HologramProcessingPresets
                    .save(action.getString("name"), reusableProcessingPolicy(action.getCompoundTag("policy")));
                s.status = "处理流程预设已保存";
            } else if ("updateProcessingPreset".equals(op)) {
                HologramProcessingPresets.update(
                    action.getString("presetId"),
                    action.getString("name"),
                    reusableProcessingPolicy(action.getCompoundTag("policy")));
                s.status = "处理流程预设已更新";
            } else if ("deleteProcessingPreset".equals(op)) {
                HologramProcessingPresets.delete(action.getString("presetId"));
                s.status = "处理流程预设已删除";
            } else if ("loadProcessingPreset".equals(op)) {
                s.blueprintSaveReceipt = "";
                HologramProcessingPresets.Entry entry = HologramProcessingPresets.get(action.getString("presetId"));
                if (entry == null) throw new java.io.IOException("处理流程预设不存在");
                NBTTagCompound selected = entry.policy.write();
                selected.setTag("confirmedStages", new NBTTagList());
                s.processingPolicy = HologramBlueprintProcessing.Policy.read(selected);
                s.processingWorkflowStarted = true;
                s.selectedProcessingPresetId = entry.id;
                s.processingReviewed = false;
                s.blueprintDirty = true;
                s.status = "处理预设已选定；请依次审核全部步骤";
            } else if ("configureBlueprintQuantumGroups".equals(op)) {
                if (!action.hasKey("groups", 9)) throw new java.io.IOException("量子网络分类缺少完整类群列表");
                HologramPresets.Preset edited = HologramQuantumGroups
                    .configure(HologramAuthoringQueries.processingSource(s), (NBTTagList) action.getTag("groups"));
                HologramBlueprintProcessing.invalidate(edited, "quantum");
                s.processingSource = edited;
                // Keep the current workflow choice; classification does not choose self/share or confirm Next.
                if (s.processingPolicy == null) s.processingPolicy = HologramBlueprintProcessing.policy(edited);
                while (s.processingPolicy.confirmedStages.size() > 2)
                    s.processingPolicy.confirmedStages.remove(s.processingPolicy.confirmedStages.size() - 1);
                s.processingReviewed = false;
                s.blueprintDirty = true;
                s.blueprintSaveReceipt = "";
                publishSavedBlueprint(s, HologramPresets.copy(edited));
                s.ae.invalidate();
                HologramResourcePlanner.refreshPlan(s);
                s.status = "本蓝图全部量子节点已分配到命名类群；请继续确认量子处理步骤";
            } else if ("configureBlueprintCellNbt".equals(op)) {
                s.blueprintSaveReceipt = "";
                configureBlueprintCellNbt(s, action);
            } else if ("editBlueprintCellNbt".equals(op)) {
                s.blueprintSaveReceipt = "";
                HologramPresets.Preset edited = HologramPresets.copy(HologramAuthoringQueries.processingSource(s));
                int[] indices = rawCellIndices(s, edited, action);
                NBTTagList changes = action.getTagList("changes", 10);
                if (changes.tagCount() == 0 || changes.tagCount() > 128) throw new java.io.IOException("NBT 修改条目数量无效");
                for (int i = 0; i < changes.tagCount(); i++) {
                    NBTTagCompound change = changes.getCompoundTagAt(i);
                    HologramBlueprintProcessing.editNbtValue(
                        edited,
                        indices,
                        change.getString("path"),
                        change.getString("type"),
                        change.getString("value"));
                }
                validateAuthorDraft(edited);
                HologramBlueprintProcessing.invalidate(edited, "nbt");
                s.processingSource = edited;
                s.processingPolicy = HologramBlueprintProcessing.policy(edited);
                s.processingReviewed = false;
                s.blueprintDirty = true;
                publishSavedBlueprint(s, HologramPresets.copy(edited));
                HologramResourcePlanner.refreshPlan(s);
                s.status = "方块 NBT 配置已修改；请重新审核 NBT 与提示步骤后保存";
            } else {
                s.blueprintSaveReceipt = "";
                HologramPresets.Preset raw = HologramAuthoringQueries.processingSource(s);
                HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.Policy
                    .read(action.getCompoundTag("policy"));
                HologramPresets.Preset processed = HologramBlueprintProcessing
                    .process(raw, policy, s.dimension, s.x, s.y, s.z);
                HologramAePolicy.Draft reviewed = HologramAePolicy.sanitize(processed);
                HologramBlueprintProcessing.enforcePolicy(reviewed.preset);
                validateAuthorDraft(reviewed.preset);
                s.virtual = true;
                s.virtualPlacement = false;
                s.target = null;
                s.machine = null;
                s.context = null;
                s.virtualPreset = reviewed.preset;
                s.retainedNbtKeys = reviewed.preset.retainedNbtKeys;
                s.processingPolicy = policy;
                s.processingReviewed = true;
                s.processingWorkflowStarted = true;
                s.blueprintDirty = true;
                s.blueprintPreview = true;
                s.openPurpose = "blueprintEdit";
                s.blueprintSource = s.blueprintSource.isEmpty() ? "processedDraft" : s.blueprintSource;
                s.ae.invalidate();
                HologramBlueprintProjection.populateVirtual(s);
                s.status = "蓝图处理流程已完成；请核对报告后保存";
            }
            if ("loadProcessingPreset".equals(op) || "configureBlueprintCellNbt".equals(op)
                || "configureBlueprintQuantumGroups".equals(op)
                || "editBlueprintCellNbt".equals(op)
                || "processBlueprint".equals(op)) clearSourceSwitch(s);
            s.processingPresetOperationSuccess = true;
        } catch (java.io.IOException | RuntimeException failure) {
            s.status = "蓝图处理失败：" + failure.getMessage();
        }
        return true;
    }

    private static HologramBlueprintProcessing.Policy reusableProcessingPolicy(NBTTagCompound policy)
        throws java.io.IOException {
        NBTTagCompound reusable = (NBTTagCompound) policy.copy();
        reusable.removeTag("resourceDecisions");
        return HologramBlueprintProcessing.Policy.read(reusable);
    }

    static int[] rawCellIndices(HologramSession s, HologramPresets.Preset raw, NBTTagCompound action)
        throws java.io.IOException {
        String active = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
        if (action.hasKey("structureId", 8) && !active.equals(action.getString("structureId")))
            throw new java.io.IOException("结构选择已变化，请重新选择 NBT 编辑格");
        int[] selected = action.hasKey("indices", 11) ? action.getIntArray("indices")
            : action.hasKey("selected", 3) ? new int[] { action.getInteger("selected") }
                : HologramSessionPolicy.editIndices(s, action);
        if (selected.length == 0 || selected.length > 8192) throw new java.io.IOException("请先选择 NBT 编辑格");
        int[] indices = new int[selected.length];
        Map<String, Integer> rawPositions = new HashMap<>();
        for (int i = 0; i < raw.cells.size(); i++) {
            HologramPresets.Cell cell = raw.cells.get(i);
            rawPositions.put(cell.x + "," + cell.y + "," + cell.z, i);
        }
        for (int n = 0; n < selected.length; n++) {
            int index = selected[n];
            if (index < 0 || index >= s.capture.cells.size()) throw new java.io.IOException("NBT 方块索引已失效");
            HologramCapture.Cell captured = s.capture.cells.get(index);
            if (!HologramSessionPolicy.editableStructureCell(s, captured)) throw new java.io.IOException("所选格不属于当前结构");
            indices[n] = rawPositions
                .getOrDefault((captured.x - s.x) + "," + (captured.y - s.y) + "," + (captured.z - s.z), -1);
            if (indices[n] < 0) throw new java.io.IOException("NBT 原始格已变化，请重新载入处理来源");
        }
        return indices;
    }

    static void configureBlueprintCellNbt(HologramSession s, NBTTagCompound action) throws java.io.IOException {
        HologramPresets.Preset raw = HologramAuthoringQueries.processingSource(s);
        if (action.hasKey("structureId", 8) && !action.getString("structureId")
            .equals(s.blueprintActiveStructure)) throw new java.io.IOException("结构选择已变化，请重新选择 NBT 编辑格");
        int[] indices = action.hasKey("indices", 11) ? action.getIntArray("indices")
            : action.hasKey("selected", 3) ? new int[] { action.getInteger("selected") }
                : HologramSessionPolicy.editIndices(s, action);
        if (indices.length == 0 || indices.length > 8192) throw new java.io.IOException("请先选择至多8192个 NBT 编辑格");
        NBTTagCompound policy = (s.processingPolicy == null ? HologramBlueprintProcessing.policy(raw)
            : s.processingPolicy).write();
        NBTTagList overrides = policy.getTagList("cellNbtOverrides", 10);
        Map<String, NBTTagCompound> byPosition = new LinkedHashMap<>();
        for (int i = 0; i < overrides.tagCount(); i++) {
            NBTTagCompound row = overrides.getCompoundTagAt(i);
            byPosition.put(row.getInteger("x") + "," + row.getInteger("y") + "," + row.getInteger("z"), row);
        }
        for (int index : indices) {
            if (index < 0 || index >= s.capture.cells.size()) throw new java.io.IOException("NBT 方块索引已失效");
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (!HologramSessionPolicy.editableStructureCell(s, cell)) throw new java.io.IOException("所选格不属于当前结构");
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", cell.x - s.x);
            row.setInteger("y", cell.y - s.y);
            row.setInteger("z", cell.z - s.z);
            row.setBoolean("all", action.getBoolean("all"));
            row.setTag(
                "keys",
                HologramSessionPolicy.nbtKeys(HologramAuthoringQueries.selectedNbtKeys(action.getTagList("keys", 8))));
            row.setTag(
                "ignoredPaths",
                action.getTagList("ignoredPaths", 8)
                    .copy());
            byPosition.put((cell.x - s.x) + "," + (cell.y - s.y) + "," + (cell.z - s.z), row);
        }
        if (byPosition.size() > 8192) throw new java.io.IOException("方块 NBT 策略已达8192个上限");
        overrides = new NBTTagList();
        for (NBTTagCompound row : byPosition.values()) overrides.appendTag(row);
        policy.setTag("cellNbtOverrides", overrides);
        NBTTagList stages = new NBTTagList();
        NBTTagList original = policy.getTagList("confirmedStages", 8);
        for (int i = 0; i < original.tagCount(); i++) {
            String stage = original.getStringTagAt(i);
            if ("nbt".equals(stage) || "hints".equals(stage)) break;
            stages.appendTag(new NBTTagString(stage));
        }
        policy.setTag("confirmedStages", stages);
        s.processingPolicy = HologramBlueprintProcessing.Policy.read(policy);
        s.processingReviewed = false;
        s.blueprintDirty = true;
        s.status = "方块 NBT 策略已更新；请完成 NBT 与提示步骤后重新处理";
    }

    /** Keep a live area overview attached after storage publishes a detached saved snapshot. */
    static void publishSavedBlueprint(HologramSession s, HologramPresets.Preset preset) {
        if (s.capture != null) for (HologramPresets.Cell saved : preset.cells) {
            HologramCapture.Cell cell = s.capture.positions
                .get((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z));
            if (cell == null) continue;
            if (saved.item != null && (!saved.item.equals(cell.blueprintItem) || saved.damage != cell.blueprintDamage)
                && HologramNativePlacement.supported(cell.block)
                && !saved.excluded) rebindAeShell(s, cell, HologramSessionPolicy.cellIndex(s, cell), saved.stack());
            cell.tileNbt = saved.tileNbt == null ? "" : saved.tileNbt;
            cell.itemNbt = saved.itemNbt == null ? "" : saved.itemNbt;
            cell.excluded = saved.excluded;
            cell.resourceFingerprint = "";
            cell.resourcePlanKey = null;
        }
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null && area.imported && (s.areaOverviewOwner == area || s.virtualPreset == area.blueprint)) {
            s.areaOverviewOwner = area;
            area.blueprint = preset;
            HologramSession overview = HologramWorkspace.AREA_EDITORS.get(s.player.getUniqueID());
            if (overview != null && overview.areaOverviewOwner == area) overview.virtualPreset = preset;
        }
        s.virtualPreset = preset;
    }

    static boolean ensureNetworkGroups(HologramSession s) throws java.io.IOException {
        if (!s.virtual || s.virtualPreset == null || s.capture == null) return false;
        if (HologramBlueprintProcessing.quantumCleared(s.virtualPreset)) return false;
        for (HologramCapture.Cell cell : s.capture.cells) {
            NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
            if (payload != null && (payload.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)
                || HologramNetworkBindings.node(cell.block) && !payload.hasKey(HologramNetworkBindings.GROUP))) {
                HologramAuthoringWorkflow.deidentifyNetworkDraft(s);
                return true;
            }
        }
        return false;
    }
}
