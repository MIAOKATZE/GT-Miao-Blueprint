package com.miaokatze.gtmb.hologram;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.common.blocks.ItemMachines;

/** Authenticated, rate-limited protocol dispatch into explicit domain workflows. */
final class HologramActionDispatcher {

    private HologramActionDispatcher() {}

    static void handle(EntityPlayerMP player, NBTTagCompound action) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s == null || s.player != player || s.cancelled || !s.id.equals(action.getString("session"))) return;
        if (action.hasKey("editorWorkspaceEpoch") && (!action.hasKey("editorWorkspaceEpoch", 4)
            || action.getLong("editorWorkspaceEpoch") != s.editorWorkspaceEpoch)) return;
        // Each action gets fresh material connections only if it actually queries or changes materials.
        s.materialContext = null;
        if (action.getInteger("clientSnapshotProtocol") == 1) s.cellDeltaProtocol = true;
        if ("requestFullSnapshot".equals(action.getString("op"))) {
            long tick = player.worldObj.getTotalWorldTime();
            if (s.snapshotResyncTick == Long.MIN_VALUE || tick - s.snapshotResyncTick >= 20) {
                s.snapshotResyncTick = tick;
                s.localEdits.invalidate();
                HologramSnapshots.send(s);
            }
            return;
        }
        if ("ackBlueprintSaved".equals(action.getString("op"))) {
            if (HologramLibraryWorkflow.acknowledgeBlueprintSave(s, action)) HologramSnapshots.send(s);
            return;
        }
        if (s.attachmentTask != null) {
            String operation = action.getString("op");
            if ("cancelAttachment".equals(operation) || "cancel".equals(operation)
                || "clearWorkspace".equals(operation)) {
                s.attachmentTask.cancel("附加对象处理已取消，原工作区保留");
                if ("cancelAttachment".equals(operation)) return;
            } else if (!"close".equals(operation)) {
                HologramSnapshots.sendUi(s);
                return;
            }
        }
        // Closing changes presentation only; it must not invalidate an unchanged data snapshot.
        if (action.hasKey("uiSequence") && !"close".equals(action.getString("op"))
            && !HologramMaterialWorkflow.lightweight(action.getString("op")))
            s.uiSequence = action.getLong("uiSequence");
        long now = player.worldObj.getTotalWorldTime();
        if (s.actionTick != now) {
            s.actionTick = now;
            s.actionCount = 0;
        }
        if (s.actionCount++ >= 8) return;
        String op = action.getString("op");
        if ("switchEditorWorkspace".equals(op)) {
            HologramEditorWorkspaceRegistry.switchWorkspace(s, action);
            return;
        }
        if ("placeTextBox".equals(op)) {
            HologramTextWorkflow.apply(s, action);
            return;
        }
        if ("clearWorkspace".equals(op) && HologramSessionPolicy.valid(s)
            && s.job != 1
            && s.job != 2
            && !HologramAeCraftingOrders.active(player)) {
            HologramDeferredPlanRefresh.cancel(s);
            HologramSession.cancelCaptureRetry(s);
            HologramWorkspace.clearAreaSelection(s);
            if (player.getHeldItem() != null && player.getHeldItem()
                .hasTagCompound()) {
                player.getHeldItem()
                    .getTagCompound()
                    .removeTag(HologramSessionKeys.DRAFT);
                player.getHeldItem()
                    .getTagCompound()
                    .removeTag(HologramSessionKeys.SELECTED_PRESET);
            }
            s.cancelled = true;
            HologramSession empty = HologramWorkspace
                .create(player, (int) player.posX, (int) player.posY, (int) player.posZ, false, 1);
            empty.virtual = true;
            empty.open = true;
            empty.openPurpose = "workspaceClear";
            empty.workspaceTab = s.workspaceTab;
            empty.status = "工作区已清空，可框选区域、载入蓝图或新建文字";
            HologramWorkspace.activate(player.getUniqueID(), empty);
            HologramSnapshots.send(empty);
            return;
        }
        if (HologramMaterialWorkflow.action(s, action, op)) return;
        HologramLibraryWorkflow.captureClientTaskToken(s, action);
        if ("refreshPresetLibrary".equals(op)) {
            if (HologramSessionPolicy.valid(s)) HologramLibraryWorkflow.refreshPresetLibrary(s);
            return;
        }
        if ("discardBlueprint".equals(op)) {
            HologramLibraryWorkflow.discardBlueprint(s, action);
            return;
        }
        if ("cancelSourceSwitch".equals(op)) {
            if (HologramWorkspace.areaSessionOwned(s)) HologramAuthoringWorkflow.cancelSourceSwitch(s, action);
            HologramSnapshots.send(s);
            return;
        }
        if ("openController".equals(op) || "importArea".equals(op) && action.hasKey("sourceSwitchNonce", 8)) {
            HologramAuthoringWorkflow.confirmSourceSwitch(s, action);
            return;
        }
        if ("close".equals(op)) HologramAuthoringWorkflow.clearSourceSwitch(s);
        boolean runningControl = (s.job == 1 || s.job == 2 || HologramAeCraftingOrders.active(player))
            && ("close".equals(op) || "pause".equals(op) || "resume".equals(op) || "cancel".equals(op));
        boolean closingSave = "close".equals(op) && !s.blueprintSaveReceipt.isEmpty();
        if (!(closingSave ? HologramSessionPolicy.saveValid(s)
            : runningControl ? HologramSessionPolicy.jobValid(s) : HologramSessionPolicy.valid(s))) {
            if (!HologramWorkspace.areaSessionOwned(s)) HologramWorkspace.clearAreaSelection(s);
            s.job = 4;
            HologramSession.cancelCaptureRetry(s);
            HologramSession.clearPending(s);
            s.status = "目标、维度、距离或持有工具已变化";
            HologramSnapshots.send(s);
            return;
        }
        if (HologramDeferredPlanRefresh.blocksAction(s, op)) {
            HologramSnapshots.send(s);
            return;
        }
        if ("editDelete".equals(op)) {
            try {
                HologramBlueprintEditor.deleteBlueprintCells(s, action);
            } catch (java.io.IOException failure) {
                s.status = "蓝图删除被拒绝：" + failure.getMessage();
            }
            HologramSnapshots.send(s);
            return;
        }
        if (HologramAuthoringWorkflow.changesBlueprintSource(op)
            && !HologramAuthoringWorkflow.allowBlueprintDiscard(s, action, op, action.getString("presetId"))) {
            HologramSnapshots.send(s);
            return;
        }
        if ("undoAreaSelection".equals(op)) {
            HologramAreaWorkflow.undoAreaSelection(player, action);
            return;
        }
        if (HologramSessionPolicy.blockedByAreaImport(s)) {
            if ("close".equals(op) || "cancel".equals(op) || "areaCancel".equals(op))
                HologramAreaWorkflow.cancelAreaImport(s);
            else if ("cancelAeSave".equals(op)) s.ae.review = null;
            else if ("aePreviewClose".equals(op)) s.ae.preview = null;
            else if ("aePreviewEdit".equals(op))
                s.ae.result(action.getLong("editSeq"), false, "ae.import.busy", s.planRevision);
            // Publish the read-only old draft without AE edits, recovery flush, disk writes or construction.
            HologramSnapshots.send(s);
            return;
        }
        if ("close".equals(op)) {
            if (closingSave) {
                s.open = false;
                s.libraryUiActive = false;
                HologramSnapshots.sendUi(s);
                return;
            }
            HologramAuthoringWorkflow.clearSourceSwitch(s);
            s.libraryQuickToken = "";
            HologramProjectorWorkflow.dismissPresetBrowse(s);
            HologramProjectorWorkflow.libraryCloseUi(s);
            s.settingsOnly = false;
            s.forcePlacementReady = false;
            if (!runningControl) HologramDraftPersistence.persist(s);
            HologramSnapshots.sendUi(s);
            return;
        }
        if (!("confirmAeSave".equals(op) || "cancelAeSave".equals(op)
            || "aePreviewOpen".equals(op)
            || "aePreviewEdit".equals(op)
            || "aePreviewClose".equals(op)
            || "close".equals(op))) s.ae.invalidateReviews();
        if (HologramAuthoringWorkflow.aeAction(s, action, op)) {
            if ("aePreviewEdit".equals(op) && s.ae.ack != null && s.ae.ack.getBoolean("accepted"))
                HologramAuthoringWorkflow.blueprintEdited(s, "nbt");
            HologramSnapshots.send(s);
            return;
        }
        if (s.job != 1 && s.job != 2 && HologramAuthoringWorkflow.processingAction(s, action, op)) {
            HologramDraftPersistence.persist(s);
            HologramSnapshots.send(s);
            return;
        }
        boolean construction = "build".equals(op) || "start".equals(op)
            || "native".equals(op)
            || "confirmBlueprintPlacement".equals(op);
        if (construction && s.virtual && (!s.virtualPlacement || !s.virtualLocked || s.mode == 2)) {
            s.forcePlacementReady = false;
            s.status = s.mode == 2 ? "虚拟预设不支持拆除" : !s.virtualPlacement ? "请在蓝图库点击放置，再右击确定位置并核对材料" : "请先右击确定位置，再在界面确认施工";
            HologramSnapshots.send(s);
            return;
        }
        if (construction && (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision)) {
            s.forcePlacementReady = false;
            s.status = "确认计划已更新，请重新核对材料与位置";
            HologramSnapshots.send(s);
            return;
        }
        if (construction && s.virtual && s.blueprintAwaitingConfirmation && !"confirmBlueprintPlacement".equals(op)) {
            s.status = "请先在材料界面确认本次放置";
            HologramSnapshots.send(s);
            return;
        }
        if (construction && s.virtual && s.blueprintAwaitingConfirmation && !s.placementReviewIssued) {
            s.status = "位置已锁定，请再次右键打开材料与 NBT 确认界面";
            HologramSnapshots.send(s);
            return;
        }
        if (s.job != 1 && s.job != 2
            && !"selectStructure".equals(op)
            && !"configure".equals(op)
            && (HologramBlueprintEditor.isPlanningEdit(op) || "build".equals(op)
                || "start".equals(op)
                || "scan".equals(op))) {
            String active = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
            if (action.hasKey("structureId", 8) && !active.equals(action.getString("structureId"))) {
                s.status = "结构选择已变化，请重新选择编辑目标";
                HologramSnapshots.send(s);
                return;
            }
            if (action.hasKey("selected", 3)) {
                int selected = action.getInteger("selected");
                if (selected < -1 || (s.capture != null && selected >= s.capture.cells.size())) return;
                s.selected = selected;
            }
            if (action.hasKey("selectedIndices", 11)) {
                s.selectedIndices.clear();
                for (int index : action.getIntArray("selectedIndices"))
                    if (s.capture != null && index >= 0 && index < s.capture.cells.size()) s.selectedIndices.add(index);
            }
        }
        if (s.job != 1 && s.job != 2 && HologramAuthoringWorkflow.unchangedBlueprintAction(s, action, op)) {
            HologramSnapshots.sendUi(s);
            return;
        }
        if (!"build".equals(op) && !"start".equals(op) && !"confirmBlueprintPlacement".equals(op))
            s.forcePlacementReady = false;
        if (HologramBlueprintEditor.isPlanningEdit(op) && !"hatchPreset".equals(op)
            && !"clearHatchPreset".equals(op)
            && s.capture != null
            && s.selected >= 0
            && s.selected < s.capture.cells.size()
            && !HologramSessionPolicy.editableStructureCell(s, s.capture.cells.get(s.selected))) {
            s.status = "该格属于其他结构，请先切换结构";
            HologramSnapshots.send(s);
            return;
        }
        if ("rotateCell".equals(op) && HologramTextWorkflow.rotateSelected(s, action)) return;
        HologramEditState beforeEdit = HologramBlueprintEditor.isPlanningEdit(op) && !"editCopy".equals(op)
            && s.job != 1
            && s.job != 2
                ? new HologramEditState(
                    s,
                    HologramLocalPlanRefresh.sparseOperation(s, op) ? HologramLocalPlanRefresh.selected(s, action)
                        : null)
                : null;
        if (s.job != 1 && s.job != 2 && ("hatchPreset".equals(op) || "clearHatchPreset".equals(op))) {
            s.allocationsFrozen = false;
            s.frozenAutoTargets.clear();
        }
        if (s.virtual && beforeEdit != null
            && !HologramBlueprintEditor.isCellEdit(op)
            && !"hatchPreset".equals(op)
            && !"clearHatchPreset".equals(op)
            && s.capture != null) {
            int selected = action.hasKey("selected", 3) ? action.getInteger("selected") : s.selected;
            if (selected >= 0 && selected < s.capture.cells.size())
                s.virtualEdited.add(HologramValues.position(s.capture.cells.get(selected)));
        }
        if (s.job != 1 && s.job != 2 && "selectStructure".equals(op)) {
            if (action.hasKey("virtualOnly") && (!action.hasKey("virtualOnly", 1) || action.getBoolean("virtualOnly")))
                HologramAreaWorkflow.selectVirtualStructure(s, action);
            else s = HologramAreaWorkflow.selectAreaStructure(s, action.getString("structureId"));
            HologramSnapshots.send(s);
            return;
        }
        if (s.job != 1 && s.job != 2 && "previewPreset".equals(op)) {
            HologramSession previewSource = s;
            String previewId = action.getString("presetId");
            s = HologramPresetWorkflow.editPreset(s, previewId);
            if (s != previewSource && s.virtualPreset != null && previewId.equals(s.virtualPreset.id))
                s.openPurpose = "blueprintBrowse";
            s.blueprintPreview = true;
            s.open = true;
            HologramSnapshots.send(s);
            return;
        }
        if (s.job != 1 && s.job != 2 && HologramAuthoringWorkflow.blueprintAction(s, action, op)) {
            HologramDraftPersistence.persist(s);
            HologramSnapshots.send(s);
            return;
        }
        if ("settings".equals(op) && s.job != 1 && s.job != 2) {
            HologramActionDispatcher.applySettings(s, action);
        } else if ("savePreset".equals(op) && s.job != 1 && s.job != 2) {
            HologramPresetWorkflow.savePreset(s, action.getString("name"));
        } else if ("loadPreset".equals(op) && s.job != 1 && s.job != 2) {
            HologramPresetWorkflow.loadPreset(s, action.getString("presetId"));
        } else if ("choosePlacementPreset".equals(op) && s.job != 1 && s.job != 2) {
            s.virtual = true;
            HologramSessionPolicy.clearPlacementSelection(s);
            HologramPresetWorkflow.loadPreset(s, action.getString("presetId"), false);
            if (s.virtualPreset != null && action.getString("presetId")
                .equals(s.virtualPreset.id)) {
                s.virtualPlacement = true;
                s.target = null;
                s.machine = null;
                s.context = null;
                s.blueprintAwaitingConfirmation = false;
                s.placementReviewIssued = false;
                s.virtualLocked = false;
                s.forcePlacementReady = false;
                HologramProjectorWorkflow.libraryCloseUi(s);
                s.openPurpose = "blueprintPlacement";
                s.status = "移动鼠标预览位置；右键锁定，再次右键核对材料与 NBT，Esc 取消放置";
            }
        } else if ("dismissPresetBrowse".equals(op)) {
            HologramProjectorWorkflow.dismissPresetBrowse(s);
            HologramProjectorWorkflow.libraryCloseUi(s);
        } else if ("editPreset".equals(op) && s.job != 1 && s.job != 2) {
            s = HologramPresetWorkflow.editPreset(s, action.getString("presetId"));
        } else if ("updatePreset".equals(op) && s.job != 1 && s.job != 2) {
            HologramPresetWorkflow.saveVirtualPreset(s, action.getString("name"), true);
        } else if ("resetDraft".equals(op) && s.job != 1 && s.job != 2) {
            HologramActionDispatcher.resetDraft(s);
        } else if ("calibrateCurrent".equals(op) && s.job != 1 && s.job != 2) {
            HologramBuildPreparation.calibrateCurrent(s);
        } else if ("rotateVirtual".equals(op)) {
            HologramWorldPlacementWorkflow.rotateVirtual(s, action.getBoolean("vertical"));
        } else if ("lockVirtual".equals(op)) {
            HologramWorldPlacementWorkflow.lockVirtual(player, s.x, s.y, s.z, s.side);
        } else if (HologramBlueprintEditor.isCellEdit(op) || "editUndo".equals(op) || "editRedo".equals(op)) {
            HologramBlueprintEditor.edit(s, action, op);
        } else if ("pause".equals(op) && s.job == 1) {
            s.job = 2;
            s.status = "已暂停";
        } else if ("cancel".equals(op)) {
            HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
            if (area != null) area.importing = false;
            s.job = 4;
            HologramSession.cancelCaptureRetry(s);
            HologramSession.clearPending(s);
            s.status = "已取消";
        } else if ("resume".equals(op) && s.job == 2) {
            if (s.mode == 1 && !HologramPlanPolicy.replacementSnapshotCurrent(s)) {
                s.status = "原结构现场已变化，替换保持暂停，请取消后重新扫描";
                HologramSnapshots.send(s);
                return;
            }
            s.job = 1;
            s.status = "继续施工";
        } else if ("scan".equals(op) && s.job != 1 && s.job != 2) HologramBuildPreparation.scan(s);
        else if ("configure".equals(op) && s.job != 1 && s.job != 2) {
            if (!configure(s, action)) {
                HologramSnapshots.send(s);
                return;
            }
        } else if ("build".equals(op) && s.job != 1 && s.job != 2) {
            HologramBuildPreparation.reviewedBuild(s, action);
        } else if ("removePosition".equals(op) && s.job != 1 && s.job != 2) {
            int index = action.getInteger("selected");
            if (s.capture != null && index >= 0 && index < s.capture.cells.size()) {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                s.coordinatePins.remove(HologramValues.position(cell));
                s.manualPositions.remove(HologramValues.position(cell));
                s.pins.remove(index);
                if (s.autoTargets.containsKey(index))
                    s.suppressedAuto.add(HologramValues.position(s.capture.cells.get(index)));
                HologramBuildPreparation.scan(s);
                s.status = "位置覆盖已清除，已恢复结构默认目标";
            }
        } else if ("addPosition".equals(op) && s.job != 1 && s.job != 2) {
            HologramBlueprintEditor.addPosition(s, action);
        } else if ("start".equals(op) && s.job != 1 && s.job != 2) {
            if (s.virtual && (!s.virtualPlacement || !s.virtualLocked || s.mode == 2))
                s.status = "请先放置蓝图并右击确定位置，虚拟预设不能拆除";
            else if (s.capturePending) s.status = "正在采集完整结构，请等待自动重试回执";
            else if (s.capture == null || s.capture.incomplete) s.status = "该机器无法完整安全采集，请使用原版提示或搭建";
            else if (!HologramSessionPolicy.wholeBlueprintPlacement(s) && (s.scope == 2 || s.scope == 3)
                && (s.selected < 0 || s.selected >= s.capture.cells.size())) s.status = "请先选择施工范围的结构格";
            else if (!HologramSessionPolicy.wholeBlueprintPlacement(s) && s.scope == 3
                && s.capture.cells.get(s.selected).family.isEmpty()) s.status = "选中格没有安全同族适配，请使用单格范围";
            else if (!s.virtual && s.machine.getExtendedFacing()
                .ordinal() != s.facing) {
                    HologramBuildPreparation.scan(s);
                    s.status = "控制器朝向已变化，请重新确认计划";
                } else if (action.getLong("planRevision") != s.planRevision) s.status = "确认计划已过期，请重新核对差分";
            else if (!HologramPlanPolicy.planCurrent(s)) {
                HologramResourcePlanner.refreshPlan(s);
                s.status = "现场已变化，请重新确认更新后的差分";
            } else {
                if (!HologramPlanPolicy.constructionAllowed(s)) {
                    HologramSnapshots.send(s);
                    return;
                }
                HologramResourcePlanner.refreshResources(s);
                if (s.planMissing > 0 || !s.planRecoveryFits) {
                    s.status = s.planMissing > 0 ? "整份计划材料不足，请补齐后重新确认" : "背包无法容纳预计回收，请整理后重新确认";
                    HologramSnapshots.send(s);
                    return;
                }
                s.cursor = 0;
                s.completed = 0;
                s.defer = false;
                s.stopTick = false;
                HologramSession.clearPending(s);
                s.job = 1;
                s.replacementAuthorized = s.mode == 1;
                s.work.clear();
                for (int i = 0; i < s.capture.cells.size(); i++) {
                    if (HologramSessionPolicy.inScope(s, s.capture.cells.get(i), i)
                        && "pending".equals(s.planStatus.get(i))) s.work.add(i);
                }
                HologramPlacementJobs.spatialWorkOrder(s);
                s.phase = "RUNNING";
                HologramSession.resetJobCounts(s, false);
                s.status = "正在直接施工";
            }
        } else if ("native".equals(op) && s.job != 1 && s.job != 2) {
            if (s.mode == 0) HologramBuildPreparation.reviewedBuild(s, action);
            else s.status = "原版搭建只用于建造模式";
        } else if ("hints".equals(op)) {
            s.hints = true;
            s.status = "显示原版结构提示";
        } else if (("customPin".equals(op) || "clearPin".equals(op)) && s.job != 1 && s.job != 2) {
            int index = action.getInteger("selected");
            if (s.capturePending || s.capture == null || index < 0 || index >= s.capture.cells.size())
                s.status = "请先选择完整采集中的结构格";
            else if ("clearPin".equals(op)) {
                HologramBlueprintEditor.edit(s, action, "restoreCell");
            } else {
                int slot = action.getInteger("inventorySlot");
                ItemStack sample = slot < 0 || slot >= player.inventory.mainInventory.length ? null
                    : player.inventory.mainInventory[slot];
                HologramCapture.Cell cell = s.capture.cells.get(index);
                if (s.mode == 2) s.status = "拆除模式不接受建造目标，请先切换模式";
                else if (sample == null || sample.stackSize < 1 || cell.anchor) s.status = "库存样本或目标格无效";
                else if (!HologramCellPolicy.acceptableSample(s, cell, sample)) s.status = "该库存物品不是可放置方块或有效 GT 机器";
                else {
                    ItemStack pin = sample.copy();
                    pin.stackSize = 1;
                    s.selected = index;
                    s.scope = 2;
                    if (pin.getItem() instanceof ItemMachines) s.noHatches = false;
                    HologramCellPolicy.setBlueprintTarget(s, cell, pin);
                    s.pins.put(index, pin);
                    s.coordinatePins.put(HologramValues.position(cell), pin.copy());
                    HologramBlueprintEditor.refreshEdited(s, java.util.Collections.singleton(index));
                    s.status = "库存样本已设为目标；点击施工才扣材料";
                }
            }
        } else if ("sourcePin".equals(op) && s.job != 1 && s.job != 2) {
            int index = action.getInteger("selected"), choice = action.getInteger("choice");
            if (s.capturePending || s.capture == null
                || s.capture.incomplete
                || index < 0
                || index >= s.capture.cells.size()
                || choice < 0
                || choice >= s.materialChoices.size()) {
                s.status = "来源目录或目标位置已失效，请重新选择";
            } else {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                ItemStack pin = s.materialChoices.get(choice)
                    .copy();
                pin.stackSize = 1;
                if (s.mode == 2) s.status = "拆除模式不接受建造目标";
                else if (!HologramCellPolicy.acceptableSample(s, cell, pin)) s.status = "该来源物品不是有效放置目标";
                else if (!HologramMaterialAccess.materialSource(s)
                    .takeOne(pin, true)) s.status = "来源物品已不可用，请刷新后重选";
                else {
                    s.selected = index;
                    s.scope = 2;
                    if (pin.getItem() instanceof ItemMachines) s.noHatches = false;
                    HologramCellPolicy.setBlueprintTarget(s, cell, pin);
                    s.pins.put(index, pin);
                    s.coordinatePins.put(HologramValues.position(cell), pin.copy());
                    HologramBlueprintEditor.refreshEdited(s, java.util.Collections.singleton(index));
                    s.status = "来源物品已设为目标；点击施工才扣材料";
                }
            }
        } else if ("hatchPreset".equals(op) && s.job != 1 && s.job != 2) {
            int tier = action.getInteger("tier");
            if (tier < 0 || tier >= gregtech.api.enums.GTValues.VN.length || s.capture == null || s.capture.incomplete)
                s.status = "电压等级或完整结构无效";
            else {
                s.hatchTier = tier;
                s.hatchDowngrade = action.getBoolean("downgrade");
                s.presetEnabled = true;
                s.presetPaused = false;
                s.scope = 0;
                s.noHatches = false;
                s.suppressedAuto.clear();
                for (HologramCapture.Cell cell : s.capture.cells) cell.candidates = null;
                HologramResourcePlanner.refreshPlan(s);
                s.status = s.presetReason;
            }
        } else if ("clearHatchPreset".equals(op) && s.job != 1 && s.job != 2) {
            s.presetEnabled = false;
            s.presetPaused = true;
            s.autoTargets.clear();
            s.suppressedAuto.clear();
            HologramResourcePlanner.refreshPlan(s);
            s.status = "整机预设已撤销，已有仓室与手选目标保留";
        } else if ("hatchFacing".equals(op) && s.job != 1 && s.job != 2) {
            int index = action.getInteger("selected"), direction = action.getInteger("facing");
            if (s.capture == null || index < 0 || index >= s.capture.cells.size()) s.status = "请先选择仓室格";
            else {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                boolean legal = direction == -1;
                for (int allowed : HologramCellPolicy
                    .legalFacings(s, cell, HologramCellPolicy.targetFor(s, cell, index)))
                    if (allowed == direction) legal = true;
                if (cell.anchor || !legal) s.status = "控制器受保护，或该仓室不支持所选方向";
                else {
                    HologramResourcePlanner.freezeAllocations(s);
                    if (direction == -1) s.facingPins.remove(HologramValues.position(cell));
                    else s.facingPins.put(HologramValues.position(cell), direction);
                    HologramBlueprintEditor.refreshEdited(s, java.util.Collections.singleton(index));
                    s.status = direction == -1 ? "已恢复自动朝向" : "仓室朝向已指定，施工时生效";
                }
            }
        } else if ("pin".equals(op) && s.job != 1 && s.job != 2) {
            if (s.capturePending) {
                s.status = "正在采集中，请等待完整结构后选择具体目标";
                HologramSnapshots.send(s);
                return;
            }
            int index = action.getInteger("selected"),
                choice = action.hasKey("choiceindex") ? action.getInteger("choiceindex") : action.getInteger("choice");
            if (s.capture != null && index >= 0 && index < s.capture.cells.size()) {
                List<ItemStack> options = HologramCellPolicy.candidates(s, s.capture.cells.get(index));
                if (choice >= 0 && choice < options.size()) {
                    HologramCapture.Cell cell = s.capture.cells.get(index);
                    ItemStack chosen = options.get(choice);
                    if (cell.anchor) s.status = "控制器始终受保护";
                    else if (s.mode == 2) s.status = "拆除模式不接受建造目标";
                    else {
                        try {
                            HologramBlueprintEditor.pinSelection(s, action, index, chosen);
                            s.status = "目标已指定；点击施工才会扣材料并放置";
                        } catch (java.io.IOException invalid) {
                            s.status = invalid.getMessage();
                        }
                    }
                }
            }
        } else if (s.job == 2 && ("scan".equals(op) || "configure".equals(op) || "native".equals(op)))
            s.status = "暂停任务保留进度；请先取消再改变配置或重新扫描";
        if (s.job != 1 && s.job != 2
            && ("configure".equals(op) || "customPin".equals(
                op) || "sourcePin".equals(op) || "pin".equals(op) || "addPosition".equals(op) || "clearPin".equals(op)))
            HologramMaterialAccess.refreshMaterialChoices(s);
        if (beforeEdit != null && !beforeEdit.sameAs(beforeEdit.current(s))) {
            HologramBlueprintEditor.remember(s.undoEdits, beforeEdit);
            s.redoEdits.clear();
            if (beforeEdit.sparseIndices == null || !HologramLocalPlanRefresh.editedSource(s, beforeEdit.sparseIndices))
                HologramAuthoringWorkflow.blueprintEdited(s, "geometry");
        }
        HologramDraftPersistence.persist(s);
        HologramSnapshots.send(s);
    }

    /** Selection and scope changes refresh the diff without redistributing existing automatic targets. */
    static boolean configure(HologramSession s, NBTTagCompound action) {
        String activeStructure = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
        if (action.hasKey("structureId", 8) && !activeStructure.equals(action.getString("structureId"))) {
            s.status = "结构选择已变化，请重新打开参数面板";
            return false;
        }
        if (s.virtual && action.getInteger("mode") == 2) {
            s.status = "虚拟预设不支持拆除；请先建立实体控制器";
            return false;
        }
        int requestedFacing = action.getInteger("facing");
        if (!action.hasKey("main", 3) || action.getInteger("main") < 1
            || !HologramSessionPolicy.validChannels(action.getCompoundTag("channels"))
            || (s.capabilities != null
                && !s.capabilities.validConfiguration(action.getInteger("main"), action.getCompoundTag("channels")))
            || action.getInteger("mode") < 0
            || action.getInteger("mode") > 2
            || action.getInteger("scope") < 0
            || action.getInteger("scope") > 3
            || action.getInteger("layer") < -128
            || action.getInteger("layer") > 128
            || (s.machine != null && requestedFacing != s.machine.getExtendedFacing()
                .ordinal())) {
            s.status = "参数无效，配置未修改";
            return false;
        }
        java.util.Map<Integer, ItemStack> hatchTargets = null;
        if (action.hasKey("hatchProfile", 10) || action.hasKey("applyHatchPreset", 1)) {
            if (action.getInteger("main") != s.main || !action.getCompoundTag("channels")
                .equals(s.channels)
                || action.getInteger("mode") != s.mode
                || s.capture == null
                || s.capture.incomplete
                || s.capturePending) {
                s.status = "结构参数已变化，请重新打开仓室预设后应用";
                return false;
            }
            int tier = action.getInteger("hatchTier");
            if (tier < 0 || tier >= gregtech.api.enums.GTValues.VN.length || s.virtual || s.mode == 2) {
                s.status = "此模式或仓室等级不能应用预设";
                return false;
            }
            if (action.hasKey("hatchProfile", 10)) {
                try {
                    hatchTargets = HologramHatchProfiles.plan(s, action.getCompoundTag("hatchProfile"));
                } catch (java.io.IOException invalid) {
                    s.status = "仓室搭配未应用：" + invalid.getMessage();
                    return false;
                }
            } else if (!s.presetAvailable) {
                s.status = "此结构没有完整声明的仓室配额，不能生成基础预设";
                return false;
            }
        }
        boolean geometryChanged = s.main != action.getInteger("main")
            || !s.channels.equals(action.getCompoundTag("channels"))
            || s.noHatches != action.getBoolean("noHatches")
            || (s.machine != null && s.facing != s.machine.getExtendedFacing()
                .ordinal());
        if (geometryChanged && s.virtual
            && !HologramVirtualStructureEditing.prepare(
                s,
                action.getInteger("main"),
                action.getCompoundTag("channels"),
                action.getBoolean("noHatches")))
            return false;
        if (geometryChanged) {
            s.allocationsFrozen = false;
            s.frozenAutoTargets.clear();
        }
        if (s.main != action.getInteger("main")) s.mainExplicit = true;
        s.main = action.getInteger("main");
        s.channels = HologramSessionPolicy.boundedChannels(action.getCompoundTag("channels"));
        s.noHatches = action.getBoolean("noHatches");
        HologramBlueprintData.saveStructureConfiguration(s);
        if (s.virtual && !s.blueprintActiveStructure.isEmpty())
            HologramBlueprintData.loadStructureConfiguration(s, s.blueprintActiveStructure);
        if (action.hasKey("materialMain")) s.materialSettings.main = action.getBoolean("materialMain");
        if (action.hasKey("materialContainers"))
            s.materialSettings.containers = action.getBoolean("materialContainers");
        if (action.hasKey("materialMe")) s.materialSettings.me = action.getBoolean("materialMe");
        if (action.hasKey("materialPriority"))
            s.materialSettings.priority = HologramValues.clamp(action.getInteger("materialPriority"), 0, 2);
        s.materialContext = null;
        s.mode = HologramValues.clamp(action.getInteger("mode"), 0, 2);
        s.scope = HologramValues.clamp(action.getInteger("scope"), 0, 3);
        s.layer = HologramValues.clamp(action.getInteger("layer"), -128, 128);
        s.selected = action.getInteger("selected");
        if (action.hasKey("hatchProfile", 10) || action.getBoolean("applyHatchPreset")) {
            s.hatchTier = action.getInteger("hatchTier");
            s.hatchDowngrade = action.getBoolean("hatchDowngrade");
            s.presetEnabled = true;
            s.presetPaused = false;
            s.noHatches = false;
            s.scope = 0;
            s.suppressedAuto.clear();
            s.autoTargets.clear();
            s.frozenAutoTargets.clear();
            s.allocationsFrozen = hatchTargets != null;
            for (HologramCapture.Cell cell : s.capture.cells) cell.candidates = null;
            if (hatchTargets != null) {
                hatchTargets.forEach(
                    (index, target) -> s.frozenAutoTargets
                        .put(HologramValues.position(s.capture.cells.get(index)), target.copy()));
            }
        }
        if (action.hasKey("selectedIndices", 11)) {
            s.selectedIndices.clear();
            for (int index : action.getIntArray("selectedIndices"))
                if (s.capture != null && index >= 0 && index < s.capture.cells.size()) s.selectedIndices.add(index);
        }
        if (geometryChanged || s.capturePending) HologramBuildPreparation.scan(s);
        else {
            s.job = 0;
            HologramResourcePlanner.refreshPlan(s);
        }
        if (hatchTargets != null) {
            s.presetSupported = true;
            s.presetReason = "自定义仓室搭配已应用；施工时仍核对权限、材料与现场";
            s.status = s.presetReason;
        }
        if (!action.getBoolean("applyHatchPreset") || s.presetSupported)
            s.configurationAcceptedSequence = action.getLong("uiSequence");
        if (action.getBoolean("applyHatchPreset")) s.status = s.presetReason;
        return true;
    }

    static void resetDraft(HologramSession s) {
        HologramAuthoringWorkflow.clearSourceSwitch(s);
        s.processingSource = null;
        s.processingPolicy = null;
        s.processingReviewed = false;
        s.processingWorkflowStarted = false;
        s.blueprintDirty = true;
        s.decorLedger = new HologramDecorMaterials.Ledger();
        HologramDraftPersistence.clearDraft(s);
        if (s.virtual) HologramBlueprintProjection.populateVirtual(s);
        else HologramBuildPreparation.scan(s);
        s.status = "已恢复默认规划；现场方块、设备和库存保持现状";
    }

    static void applySettings(HologramSession s, NBTTagCompound action) {
        if (action.hasKey("materialMain")) s.materialSettings.main = action.getBoolean("materialMain");
        if (action.hasKey("materialContainers"))
            s.materialSettings.containers = action.getBoolean("materialContainers");
        if (action.hasKey("materialMe")) s.materialSettings.me = action.getBoolean("materialMe");
        if (action.hasKey("materialPriority"))
            s.materialSettings.priority = HologramValues.clamp(action.getInteger("materialPriority"), 0, 2);
        if (action.hasKey("hatchDowngrade")) s.hatchDowngrade = action.getBoolean("hatchDowngrade");
        if (action.hasKey("recoveryDestination")) s.materialSettings.recoveryDestination = HologramValues
            .clamp(action.getInteger("recoveryDestination"), 0, 1);
        if (action.hasKey("selectedPresetId")) try {
            String id = action.getString("selectedPresetId");
            if (!id.isEmpty() && HologramLibraryFacade.get(s.player, id) == null)
                throw new java.io.IOException("预设不存在");
            boolean changed = !id.equals(HologramSessionPolicy.selectedPreset(s));
            HologramSessionPolicy.currentTool(s)
                .getTagCompound()
                .setString(HologramSessionKeys.SELECTED_PRESET, id);
            if (changed && !id.isEmpty()) HologramPresetWorkflow.presetGuidance(s, "蓝图已选定；点击放置进入位置预览，或打开蓝图继续编辑。");
        } catch (java.io.IOException invalid) {
            s.status = "预设选择未保存：" + invalid.getMessage();
            return;
        }
        ItemStack tool = HologramSessionPolicy.currentTool(s);
        if (tool != null && tool.hasTagCompound()
            && tool.getTagCompound()
                .hasKey(HologramSessionKeys.DRAFT, 10)) {
            NBTTagCompound draft = HologramDraftPersistence.storedDraft(s, tool);
            HologramDraftPersistence.updateDraftSettings(draft, s.materialSettings, s.hatchDowngrade);
            if (draft.hasKey("controllerType", 8)) HologramDraftStorage.get(s.player.worldObj)
                .put(
                    HologramDraftPersistence.legacyDraftKey(s) + ":"
                        + draft.getInteger("dimension")
                        + ":"
                        + draft.getInteger("x")
                        + ":"
                        + draft.getInteger("y")
                        + ":"
                        + draft.getInteger("z")
                        + ":"
                        + draft.getString("controllerType"),
                    draft);
        }
        s.materialContext = null;
        if (s.capture != null) HologramResourcePlanner.refreshPlan(s);
        s.status = "设置已保存到当前投影仪";
    }
}
