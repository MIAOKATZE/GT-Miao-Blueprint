package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Owner/session/revision-bound library transfer and save receipt admission. */
final class HologramLibraryWorkflow {

    private HologramLibraryWorkflow() {}

    /** One-time capture/source handoff, not a reviewed export or a saved-file receipt. */
    static void acquireAuthorSource(HologramSession s, NBTTagCompound request) {
        try {
            if (HologramEditorWorkspaceRegistry.physicalProjection(s) || s.virtualPlacement
                || !request.hasKey("session", 8)
                || !s.id.equals(request.getString("session"))
                || !request.hasKey("planRevision", 4)
                || request.getLong("planRevision") != s.planRevision
                || request.getLong("clientTaskToken") <= 0
                || s.capture == null
                || s.capture.incomplete
                || s.capturePending
                || s.localPlanPending
                || s.job == 1
                || s.job == 2
                || s.attachmentTask != null
                || HologramSessionPolicy.blockedByAreaImport(s)
                || !s.blueprintSaveReceipt.isEmpty()) throw new java.io.IOException("作者来源尚未完成或已变化，请等待采集完成后重试");
            HologramSourceTransfer.start(s, request.getLong("clientTaskToken"));
        } catch (java.io.IOException | RuntimeException failure) {
            HologramLibraryFacade
                .error(s.player, "作者来源传输失败：" + failure.getMessage(), request.getLong("clientTaskToken"));
        }
    }

    /** Retires only the active draft. Saved artifacts and the player's world are not changed. */
    static void discardBlueprint(HologramSession s, NBTTagCompound request) {
        if (!request.getBoolean("confirmDiscard") || !request.hasKey("planRevision", 4)
            || request.getLong("planRevision") != s.planRevision
            || !HologramSessionPolicy.valid(s)
            || s.job == 1
            || s.job == 2
            || s.capturePending
            || s.localPlanPending
            || HologramSessionPolicy.blockedByAreaImport(s)
            || !s.blueprintSaveReceipt.isEmpty()) {
            s.status = "蓝图状态已变化，请等待当前任务完成后重新确认放弃";
            HologramSnapshots.send(s);
            return;
        }
        if (HologramEditorWorkspaceRegistry.physicalProjection(s)) {
            if (!HologramEditorWorkspaceRegistry.allowProjectionSource(s, request, "discardBlueprint", "")) {
                HologramSnapshots.send(s);
                return;
            }
            HologramSession blank = new HologramSession(s.player, s.x, s.y, s.z, false, s.side);
            blank.virtual = true;
            blank.open = true;
            blank.libraryUiActive = true;
            blank.blueprintSource = "discarded";
            blank.openPurpose = "blueprintBrowse";
            blank.status = "可重新选择蓝图或框选导入。已保存蓝图保留";
            HologramWorkspace.PresentationTransition transition = HologramWorkspace.preparePresentation(s, blank);
            if (transition != null && HologramWorkspace.activatePresentation(transition)) HologramSnapshots.send(blank);
            return;
        }
        net.minecraft.item.ItemStack tool = HologramSessionPolicy.currentTool(s);
        if (tool != null && tool.hasTagCompound()) {
            tool.getTagCompound()
                .removeTag(HologramSessionKeys.SELECTED_PRESET);
            HologramDraftPersistence.clearDraft(s);
            s.player.inventory.markDirty();
        }
        HologramAuthoringWorkflow.clearSourceSwitch(s);
        HologramWorkspace.clearAreaSelection(s);
        HologramSession next = HologramWorkspace.create(s.player, s.x, s.y, s.z, false, s.side);
        next.open = true;
        next.libraryUiActive = true;
        next.blueprintPreview = true;
        next.blueprintSource = "discarded";
        next.openPurpose = "blueprintBrowse";
        next.status = "已放弃当前蓝图预设；可重新选择蓝图或框选导入。已保存蓝图保留";
        HologramWorkspace.activate(s.player.getUniqueID(), next);
        HologramSnapshots.send(next);
    }

    static void refreshPresetLibrary(HologramSession s) {
        if (!s.blueprintSaveReceipt.isEmpty() || s.capturePending
            || s.job == 1
            || s.job == 2
            || HologramSessionPolicy.blockedByAreaImport(s)) return;
        net.minecraft.nbt.NBTTagList labels = null;
        if (!HologramLibraryFacade.enabled()) try {
            HologramPresets.refreshLocalArtifacts();
            labels = HologramPresets.labels();
            s.status = "蓝图库已重新读取本地文件";
        } catch (java.io.IOException failure) {
            s.status = "蓝图库刷新失败：" + failure.getMessage();
        }
        HologramSnapshots.sendUi(s, labels);
    }

    /** Correlates a disposable client task; owner/session/receipt checks remain the authorization boundary. */
    static void captureClientTaskToken(HologramSession s, NBTTagCompound request) {
        String op = request.getString("op");
        if (op.startsWith("saveBlueprint") || "savePreset".equals(op)
            || "updatePreset".equals(op)
            || "exportDraft".equals(op)) s.blueprintClientTaskToken = Math.max(0L, request.getLong("clientTaskToken"));
    }

    /** Admission is checked at begin and again after the bounded worker finishes decoding. */
    static boolean librarySessionCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return librarySessionCurrent(player, request, false);
    }

    /** Only an already admitted activation may pause with its original tool in another held slot. */
    static boolean libraryActivationCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return librarySessionCurrent(player, request, true);
    }

    private static boolean librarySessionCurrent(EntityPlayerMP player, NBTTagCompound request, boolean activation) {
        if (player == null || request == null) return false;
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        return s != null && s.player == player
            && s.libraryWorld == player.worldObj
            && ("authorSource".equals(request.getString("op"))
                ? !HologramEditorWorkspaceRegistry.physicalProjection(s) && !s.virtualPlacement
                : request.getBoolean("quickPlace") ? libraryQuickCurrent(s, request) : s.libraryUiActive)
            && !s.cancelled
            && !s.capturePending
            && !s.localPlanPending
            && s.job != 1
            && s.job != 2
            && !HologramSessionPolicy.blockedByAreaImport(s)
            && request.hasKey("session", 8)
            && s.id.equals(request.getString("session"))
            && request.hasKey("planRevision", 4)
            && s.planRevision == request.getLong("planRevision")
            && (activation ? HologramSessionPolicy.activationValid(s) : HologramSessionPolicy.valid(s));
    }

    static boolean libraryQuickCurrent(HologramSession s, NBTTagCompound request) {
        return s.libraryQuickPending && !s.libraryQuickToken.isEmpty()
            && s.libraryQuickToken.equals(request.getString("quickToken"))
            && "place".equals(request.getString("action"));
    }

    /** An authenticated Alt gesture receives a fresh upload capability without publishing an open UI state. */
    static NBTTagCompound libraryQuickBootstrap(EntityPlayerMP player, NBTTagCompound request)
        throws java.io.IOException {
        String token = HologramLibraryService.quickToken(request.getString("quickToken"));
        HologramSession previous = HologramWorkspace.find(player.getUniqueID());
        if (previous != null && !HologramEditorWorkspaceRegistry.physicalProjection(previous)
            && !HologramAuthoringWorkflow.allowBlueprintDiscard(previous, request, "choosePlacementPreset", ""))
            throw new java.io.IOException("当前蓝图有未保存修改；请打开蓝图库明确确认放弃后再快捷放置");
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (previous != null && (previous.job == 1 || previous.job == 2 || previous.capturePending)
            || area != null && (area.selecting || area.importing)
            || previous != null && previous.virtual
                && previous.virtualPlacement
                && previous.job != 3
                && previous.job != 4
                && previous.job != 5)
            throw new java.io.IOException("请先完成当前框选、采集或放置，再快捷放置蓝图");
        long tick = player.worldObj.getTotalWorldTime();
        if (previous != null && previous.libraryQuickBootstrapTick != Long.MIN_VALUE
            && tick >= previous.libraryQuickBootstrapTick
            && tick - previous.libraryQuickBootstrapTick < 10) throw new java.io.IOException("快捷放置请求过快，请稍后重试");
        int x = request.getInteger("x"), y = request.getInteger("y"), z = request.getInteger("z");
        int side = request.getInteger("side");
        if (!request.hasKey("x", 3) || !request.hasKey("y", 3)
            || !request.hasKey("z", 3)
            || !request.hasKey("side", 3)
            || side < 0
            || side > 5
            || !HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
            || !HologramSessionPolicy.previewReach(player, x, y, z)
            || !player.worldObj.blockExists(x, y, z)) throw new java.io.IOException("快捷放置位置未加载或超出范围");
        boolean projectionSource = previous != null && HologramEditorWorkspaceRegistry.physicalProjection(previous);
        HologramSession session = projectionSource ? previous : HologramWorkspace.create(player, x, y, z, false, side);
        if (!projectionSource) {
            session.virtual = true;
            session.open = false;
        }
        session.libraryQuickToken = token;
        session.libraryQuickClientTaskToken = request.getLong("clientTaskToken");
        session.libraryQuickPending = true;
        session.libraryQuickBootstrapTick = tick;
        if (!projectionSource) HologramWorkspace.activate(player.getUniqueID(), session);
        NBTTagCompound result = HologramLibraryProtocol.reply("quickBootstrap");
        result.setString("quickToken", token);
        result.setLong("clientTaskToken", session.libraryQuickClientTaskToken);
        result.setString("session", session.id);
        result.setLong("planRevision", session.planRevision);
        return result;
    }

    static void libraryQuickCancel(EntityPlayerMP player, String token) {
        if (player == null || token == null || token.isEmpty()) return;
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s != null && s.player == player && token.equals(s.libraryQuickToken)) {
            boolean pending = s.libraryQuickPending;
            s.libraryQuickPending = false;
            s.libraryQuickToken = "";
            HologramProjectorWorkflow.libraryCloseUi(s);
            // Escape can race the final chunk. Revoke both the pending upload and an already-created
            // unlocked placement, using the new server session/revision rather than stale client state.
            if (!pending && s.virtual
                && s.virtualPlacement
                && (!s.virtualLocked || s.libraryQuickClientTaskToken > 0)) {
                NBTTagCompound cancel = new NBTTagCompound();
                cancel.setString("session", s.id);
                cancel.setLong("planRevision", s.planRevision);
                HologramWorldPlacementWorkflow.cancelWorldPlacement(player, cancel);
            }
        }
    }

    static HologramPresets.Preset libraryPrivatePreset(EntityPlayerMP player, String id) {
        if (player == null || id == null || !id.startsWith("private_")) return null;
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s == null || s.player != player
            || s.libraryWorld != player.worldObj
            || !s.libraryUiActive && !s.libraryQuickPending
            || s.cancelled
            || !HologramSessionPolicy.valid(s)
            || s.libraryPrivatePreset == null
            || !id.equals(s.libraryPrivatePreset.id)) return null;
        return HologramPresets.copy(s.libraryPrivatePreset);
    }

    static void copyLibraryIntent(HologramSession previous, HologramSession next) {
        next.libraryPrivatePreset = previous.libraryPrivatePreset == null ? null
            : HologramPresets.copy(previous.libraryPrivatePreset);
        next.libraryLocalId = previous.libraryLocalId;
        next.libraryReplaceLocalId = previous.libraryReplaceLocalId;
    }

    static void libraryActivate(EntityPlayerMP player, NBTTagCompound request, HologramPresets.Preset uploaded,
        HologramLibraryFacade.ActivationCompletion completion) throws java.io.IOException {
        if (!completion.current() || !HologramLibraryService.sessionCurrent(player, request))
            throw new java.io.IOException("蓝图会话已更新，请重新选择蓝图");
        NBTTagCompound frozen = (NBTTagCompound) request.copy();
        String action = frozen.getString("action");
        String operation;
        if ("preview".equals(action)) operation = "previewPreset";
        else if ("place".equals(action)) operation = "choosePlacementPreset";
        else if ("edit".equals(action)) operation = "editPreset";
        else throw new java.io.IOException("未知私库蓝图操作");
        int[] pose = activationControls(frozen);
        HologramSession origin = HologramWorkspace.find(player.getUniqueID());
        if (HologramEditorWorkspaceRegistry.physicalProjection(origin) && !origin.projectionDiscardApproved) {
            String previousNonce = origin.pendingSourceAction.getString("sourceSwitchNonce");
            HologramEditorWorkspaceRegistry
                .allowProjectionSource(origin, frozen, "projectionLibraryActivate", frozen.getString("localId"));
            if (!previousNonce.equals(origin.pendingSourceAction.getString("sourceSwitchNonce")))
                origin.projectionSourcePreset = uploaded;
            String nonce = origin.pendingSourceAction.getString("sourceSwitchNonce");
            if (nonce.isEmpty()) throw new java.io.IOException("投影来源确认未能开始，请重试");
            completion.waitingForConfirmation(
                () -> origin.discardConfirmationRequired
                    && nonce.equals(origin.pendingSourceAction.getString("sourceSwitchNonce"))
                    && origin.projectionSourcePreset == uploaded);
            HologramSnapshots.send(origin);
            // The original receipt remains pending until explicit confirmation actually activates the candidate.
            return;
        }
        if (!HologramAuthoringWorkflow.allowBlueprintDiscard(origin, frozen, operation, frozen.getString("localId"))) {
            HologramSnapshots.send(origin);
            throw new java.io.IOException("当前蓝图有未保存修改；请确认放弃后重新选择");
        }
        completion.waitingForConfirmation(null);
        String localId = frozen.getString("localId");
        if (localId.isEmpty()) localId = uploaded.id == null ? "" : uploaded.id;
        localId = HologramLibraryService.localId(localId, false);
        boolean placement = "place".equals(action);
        boolean quickLock = frozen.getBoolean("quickPlace") && frozen.getLong("clientTaskToken") > 0;
        if (quickLock && !placement) throw new java.io.IOException("Invalid quick placement action");
        int x = origin.target != null || origin.virtual ? origin.x
            : net.minecraft.util.MathHelper.floor_double(player.posX);
        int y = origin.target != null || origin.virtual ? origin.y
            : HologramValues
                .clamp(net.minecraft.util.MathHelper.floor_double(player.posY), 0, player.worldObj.getHeight() - 1);
        int z = origin.target != null || origin.virtual ? origin.z
            : net.minecraft.util.MathHelper.floor_double(player.posZ);
        int side = origin.side;
        if (quickLock) {
            if (frozen.hasKey("placementX", 3)) {
                x = frozen.getInteger("placementX");
                y = frozen.getInteger("placementY");
                z = frozen.getInteger("placementZ");
            }
            if (frozen.hasKey("side", 3)) side = frozen.getInteger("side");
        }
        if (!HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
            || !player.worldObj.blockExists(x, y, z)
            || player.getDistanceSq(x + .5, y + .5, z + .5) > 4096
            || quickLock && !HologramSessionPolicy.previewReach(player, x, y, z))
            throw new java.io.IOException("蓝图锚点未加载或超出范围");
        String integratedId = !HologramLibraryFacade.enabled() ? frozen.getString("integratedPresetId") : "";
        String selectedId = integratedId.isEmpty() ? "private_" + UUID.randomUUID()
            .toString() : integratedId;
        uploaded.id = selectedId;
        HologramSession candidate = HologramWorkspace.create(player, x, y, z, false, side);
        candidate.virtual = true;
        candidate.virtualPlacement = placement;
        candidate.blueprintPreview = "preview".equals(action);
        candidate.openPurpose = placement ? "blueprintPlacement"
            : candidate.blueprintPreview ? "blueprintBrowse" : "blueprintEdit";
        candidate.uiSequence = origin.uiSequence;
        candidate.blueprintClientTaskToken = frozen.getLong("clientTaskToken") > 0 ? frozen.getLong("clientTaskToken")
            : origin.blueprintClientTaskToken;
        candidate.libraryLocalId = integratedId.isEmpty() ? localId : "";
        candidate.libraryReplaceLocalId = "";
        candidate.libraryPrivatePreset = integratedId.isEmpty() ? uploaded : null;
        candidate.libraryQuickToken = origin.libraryQuickToken;
        candidate.libraryQuickClientTaskToken = frozen.getLong("clientTaskToken");
        boolean sourcePlacement = origin.virtualPlacement;
        HologramPresetPosePreparation posePreparation = new HologramPresetPosePreparation(
            pose,
            quickLock,
            sourcePlacement && !HologramEditorWorkspaceRegistry.physicalProjection(origin));
        HologramPresetActivation
            .begin(origin, candidate, uploaded, selectedId, new HologramPresetActivation.Preparation() {

                @Override
                public boolean current() {
                    return completion.current() && libraryActivationCurrent(player, frozen)
                        && origin.virtualPlacement == sourcePlacement
                        && (!quickLock
                            || HologramSessionPolicy.previewReach(player, candidate.x, candidate.y, candidate.z))
                        && player.worldObj.blockExists(candidate.x, candidate.y, candidate.z);
                }

                @Override
                public boolean step(HologramSession preparing, int maxWork, long maxNanos) throws java.io.IOException {
                    return posePreparation.step(preparing, maxWork, maxNanos);
                }
            }, new HologramPresetActivation.Completion() {

                @Override
                public void completed(HologramSession active) {
                    try {
                        if (quickLock) HologramLibraryExecution.submitOrReview(active, frozen);
                        HologramSnapshots.send(active);
                        completion.completed(active.virtualPreset.id);
                    } catch (RuntimeException failure) {
                        completion.failed("蓝图已打开，但后续放置审核失败：" + failure.getMessage());
                    }
                }

                @Override
                public void failed(String reason) {
                    completion.failed(reason);
                }
            });
    }

    static int[] activationControls(NBTTagCompound request) throws java.io.IOException {
        if (request.hasKey("previewPose") && !request.hasKey("previewPose", 11))
            throw new java.io.IOException("Invalid preview orientation");
        int[] pose = request.getIntArray("previewPose");
        if (pose.length > HologramPreviewPose.MAX_RECIPE_LENGTH)
            throw new java.io.IOException("Invalid preview orientation");
        for (int axis : pose)
            if (axis != 0 && axis != 1) throw new java.io.IOException("Invalid preview orientation axis");
        if (request.hasKey("side")
            && (!request.hasKey("side", 3) || request.getInteger("side") < 0 || request.getInteger("side") > 5))
            throw new java.io.IOException("Invalid placement side");
        if (request.hasKey("placementX") || request.hasKey("placementY") || request.hasKey("placementZ")) {
            for (String coordinate : new String[] { "placementX", "placementY", "placementZ" })
                if (!request.hasKey(coordinate, 3)) throw new java.io.IOException("Invalid placement anchor type");
        }
        return pose;
    }

    static void libraryExportDraft(EntityPlayerMP player, NBTTagCompound request) throws java.io.IOException {
        if (!HologramLibraryFacade.enabled() || !librarySessionCurrent(player, request))
            throw new java.io.IOException("蓝图会话已更新，请重新保存");
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        boolean current = request.getBoolean("saveCurrent");
        s.blueprintOperation = current ? "saveBlueprint" : "exportDraft";
        s.blueprintOperationSuccess = false;
        captureClientTaskToken(s, request);
        HologramAuthoringWorkflow.validateProcessedSaveOptions(s, request);
        if (s.capture == null || s.capture.incomplete) throw new java.io.IOException("请先完整载入蓝图蓝图");
        String name = request.getString("name");
        if (name.trim()
            .isEmpty() && s.virtualPreset != null) name = s.virtualPreset.name;
        HologramPresets.Preset preset = HologramBlueprintProjection.blueprint(s, name);
        if (request.hasKey("retainNbt")) preset.retainNbt = request.getBoolean("retainNbt");
        if (request.hasKey("retainCovers")) preset.retainCovers = request.getBoolean("retainCovers");
        if (request.hasKey("retainAllNbt")) preset.retainedNbtKeys = request.getBoolean("retainAllNbt") ? null
            : HologramAuthoringQueries.selectedNbtKeys(request.getTagList("retainedNbtKeys", 8));
        if (request.hasKey("deidentificationLevel", 3)) {
            if (!request.hasKey("planRevision", 4) || request.getLong("planRevision") != s.planRevision)
                throw new java.io.IOException("保存计划已变化，请重新核对去标识等级");
            preset = HologramDeidentification.apply(preset, request.getInteger("deidentificationLevel"));
            preset.deidentificationChosen = true;
            s.deidentificationChosen = true;
        }
        s.libraryReplaceLocalId = current ? currentBlueprintSaveId(s, true)
            : HologramLibraryService.localId(request.getString("replaceLocalId"), true);
        HologramAuthoringWorkflow.beginAeSaveReview(
            s,
            preset,
            current ? "saveBlueprint" : "exportDraft",
            !s.libraryReplaceLocalId.isEmpty());
        HologramSnapshots.send(s);
    }

    /** A current save can replace only the loaded editor source, never a tool/list selection. */
    static String currentBlueprintSaveId(HologramSession s, boolean clientLibrary) throws java.io.IOException {
        if (!savedBlueprintWorkspace(s) || s.virtualPreset.id == null || s.virtualPreset.id.isEmpty()) return "";
        String id = s.virtualPreset.id;
        if (clientLibrary) {
            if (!id.startsWith("private_") || s.libraryPrivatePreset == null
                || !id.equals(s.libraryPrivatePreset.id)
                || s.libraryLocalId.isEmpty()) return "";
            return HologramLibraryService.localId(s.libraryLocalId, true);
        }
        if (id.startsWith("public_") || id.startsWith("private_")) return "";
        return HologramPresets.get(id) == null ? "" : id;
    }

    /** Opening intent is consumed by snapshots; the server's editor/source ownership persists. */
    static boolean savedBlueprintWorkspace(HologramSession s) {
        return s.virtual && !s.virtualPlacement
            && !s.cancelled
            && !s.settingsOnly
            && "blueprint".equals(HologramEditorWorkspaceRegistry.mode(s))
            && "saved".equals(s.blueprintSource)
            && s.virtualPreset != null;
    }

    static void saveReviewedDraft(HologramSession s, HologramPresets.Preset preset, String operation, boolean update,
        boolean ae) throws java.io.IOException {
        saveReviewedArtifact(s, preset, operation, update, ae, false);
    }

    /** This flag is generated only by the authenticated native-controller template save path. */
    static void saveReviewedArtifact(HologramSession s, HologramPresets.Preset preset, String operation, boolean update,
        boolean ae, boolean nativeTemplate) throws java.io.IOException {
        HologramVoucherCoverage.begin(s, preset, operation, update, ae, nativeTemplate);
    }

    static void finishReviewedArtifact(HologramSession s, HologramPresets.Preset preset, String operation,
        boolean update, boolean ae, boolean nativeTemplate) throws java.io.IOException {
        if (HologramDeferredPlanRefresh.blocked(s)) throw new java.io.IOException(s.status);
        if (nativeTemplate) {
            if (!s.processingReviewed || s.virtual
                || s.capture == null
                || s.capture.incomplete
                || s.capturePending
                || s.machine == null
                || !(s.target instanceof IGregTechTileEntity)
                || preset.schema != 1
                || preset.controllerId != ((IGregTechTileEntity) s.target).getMetaTileID()
                || !preset.controllerType.equals(HologramSessionPolicy.controllerType(s))
                || !"savePreset".equals(operation)
                || update) throw new java.io.IOException("原生控制器模板保存来源已变化");
            HologramPresets.validate(preset);
        } else HologramAuthoringWorkflow.requireBlueprintProcessing(s, preset);
        boolean clientLibrary = HologramLibraryFacade.enabled();
        if (clientLibrary && (!s.libraryUiActive || s.cancelled
            || s.libraryWorld != s.player.worldObj
            || !HologramLibraryService.connectedProjector(s.player)
            || !HologramSessionPolicy.valid(s))) throw new java.io.IOException("保存会话已关闭或工具已更换，请重新审核");
        stampSavedAuthor(s, preset, operation, update);
        HologramBlueprintVersions.stampSaved(preset);
        if (!clientLibrary) {
            preset = HologramPresets.prepareSaved(preset, update);
            s.blueprintSavePrepared = HologramPresets.copy(preset);
            s.blueprintSaveUpdate = update;
        }
        String localId = clientLibrary ? s.libraryReplaceLocalId : "local_" + preset.id;
        if (localId.isEmpty() && update) localId = s.libraryLocalId;
        if (localId.isEmpty()) localId = "local_" + UUID.randomUUID()
            .toString();
        HologramPresets.Preset exported = HologramPresets.copy(preset);
        if (clientLibrary) exported.id = localId;
        String receipt = UUID.randomUUID()
            .toString();
        s.blueprintSaveReceipt = receipt;
        s.blueprintSaveLocalId = localId;
        s.blueprintSaveRevision = s.planRevision;
        s.blueprintSaveJsonSha256 = s.blueprintSavePreviewSha256 = "";
        try {
            HologramLibraryFacade
                .downloadDraft(s.player, exported, s.id, s.planRevision, localId, receipt, s.blueprintClientTaskToken);
        } catch (java.io.IOException | RuntimeException failure) {
            s.blueprintSaveReceipt = "";
            s.blueprintSaveJsonSha256 = s.blueprintSavePreviewSha256 = "";
            s.blueprintSavePrepared = null;
            s.blueprintSaveSelectedPresetId = "";
            s.blueprintDirty = true;
            throw failure;
        }
        if (clientLibrary) {
            // The placement draft keeps a server-owned, owner/session-bound identity. The local file ID
            // only names the client's private file and never grants access to another player's draft.
            preset.id = "private_" + UUID.randomUUID()
                .toString();
            s.libraryPrivatePreset = preset;
            s.libraryLocalId = localId;
            s.libraryReplaceLocalId = "";
        }
        s.blueprintSaveSelectedPresetId = preset.id;
        if (s.virtual && !"importBlueprint".equals(operation))
            HologramAuthoringWorkflow.publishSavedBlueprint(s, preset);
        else s.blueprintSavedId = preset.id;
        s.ae.review = null;
        s.blueprintOperation = operation;
        s.blueprintOperationSuccess = false;
        s.blueprintDirty = true;
        HologramAuthoringWorkflow.clearSourceSwitch(s);
        s.status = "蓝图已审核，正在保存 JSON 与真实结构预览 PNG";
        if (ae) s.status += "；存储元件内容未保存，源物品未改变；AE 身份在施工时重建，样板、卡及空元件需付料。";
    }

    static void stampSavedAuthor(HologramSession s, HologramPresets.Preset preset, String operation, boolean update) {
        HologramLibraryService.stampAuthor(
            preset,
            s.player,
            !update && !"importBlueprint".equals(operation)
                && !("saveBlueprint".equals(operation) && "saved".equals(s.blueprintSource)));
    }

    /** A successful transfer is not a successful disk write. Only this owner-bound receipt clears the draft. */
    static boolean acknowledgeBlueprintSave(HologramSession s, NBTTagCompound request) {
        if (!HologramLibraryService.saveCurrent(s.player, request) || !s.id.equals(request.getString("session"))
            || s.blueprintSaveReceipt.isEmpty()
            || !s.blueprintSaveReceipt.equals(request.getString("saveReceipt"))
            || !s.blueprintSaveLocalId.equals(request.getString("localId"))
            || !request.hasKey("planRevision", 4)
            || request.getLong("planRevision") != s.blueprintSaveRevision
            || s.planRevision != s.blueprintSaveRevision
            || s.blueprintSaveJsonSha256.isEmpty()
            || !s.blueprintSaveJsonSha256.equals(
                request.hasKey("reviewedJsonSha256", 8) ? request.getString("reviewedJsonSha256")
                    : request.getString("jsonSha256")))
            return false;
        if (request.getBoolean("localSaveReceipt")) {
            if (!HologramLibraryFacade.localSaveWritten(s.player, request)) return false;
            s.blueprintSavePreviewSha256 = request.getString("previewSha256");
        } else if (s.blueprintSavePreviewSha256.isEmpty()
            || !s.blueprintSavePreviewSha256.equals(request.getString("previewSha256"))) return false;
        if (s.blueprintSavePrepared != null) {
            try {
                HologramPresets.commitSaved(s.blueprintSavePrepared, s.blueprintSaveUpdate);
                s.blueprintSavePrepared = null;
            } catch (java.io.IOException | RuntimeException failure) {
                s.status = "蓝图库索引保存失败：" + failure.getMessage();
                HologramLibraryFacade.savedResult(s.player, request, false, s.status);
                s.blueprintSaveReceipt = "";
                s.blueprintSavePrepared = null;
                s.blueprintSaveSelectedPresetId = "";
                return true;
            }
        }
        if (!s.blueprintSaveSelectedPresetId.isEmpty()) HologramSessionPolicy.jobTool(s)
            .getTagCompound()
            .setString(HologramSessionKeys.SELECTED_PRESET, s.blueprintSaveSelectedPresetId);
        s.player.inventory.markDirty();
        s.blueprintSaveSelectedPresetId = "";
        s.blueprintSaveReceipt = "";
        s.blueprintDirty = false;
        s.blueprintSource = "saved";
        HologramBlueprintRotation.committed(s);
        s.blueprintOperationSuccess = true;
        HologramAuthoringWorkflow.clearSourceSwitch(s);
        s.status = "客户端已校验并完成原子保存，蓝图修改已保存";
        HologramLibraryFacade.savedResult(s.player, request, true, s.status);
        return true;
    }

    /** Capability shared by native preview upload and the exact-byte disk acknowledgement. */
    static boolean librarySaveCurrent(EntityPlayerMP player, NBTTagCompound request) {
        if (player == null || request == null) return false;
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        return s != null && s.player == player
            && !s.cancelled
            && HologramWorkspace.areaSessionOwned(s)
            && HologramSessionPolicy.saveValid(s)
            && s.id.equals(request.getString("session"))
            && request.hasKey("planRevision", 4)
            && s.planRevision == request.getLong("planRevision")
            && s.blueprintSaveRevision == s.planRevision
            && !s.blueprintSaveReceipt.isEmpty()
            && s.blueprintSaveReceipt.equals(request.getString("saveReceipt"))
            && s.blueprintSaveLocalId.equals(
                request.hasKey("replaceLocalId", 8) ? request.getString("replaceLocalId")
                    : request.getString("localId"));
    }

    static boolean libraryDraftPrepared(EntityPlayerMP player, NBTTagCompound request, String hash) {
        if (!librarySaveCurrent(player, request) || !hash.matches("[a-f0-9]{64}")) return false;
        HologramWorkspace.find(player.getUniqueID()).blueprintSaveJsonSha256 = hash;
        return true;
    }

    static boolean libraryPreviewVerified(EntityPlayerMP player, NBTTagCompound request, String jsonHash,
        String previewHash) {
        if (!librarySaveCurrent(player, request) || !previewHash.matches("[a-f0-9]{64}")) return false;
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (!jsonHash.equals(s.blueprintSaveJsonSha256)) return false;
        s.blueprintSavePreviewSha256 = previewHash;
        return true;
    }

    static void librarySaveCancelled(EntityPlayerMP player, NBTTagCompound request) {
        if (!librarySaveCurrent(player, request)) return;
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        s.blueprintSaveReceipt = "";
        s.blueprintSaveJsonSha256 = s.blueprintSavePreviewSha256 = "";
        s.blueprintSavePrepared = null;
        s.blueprintSaveSelectedPresetId = "";
        s.blueprintDirty = true;
        s.status = "蓝图保存已取消，未保存修改保留";
        HologramSnapshots.send(s);
    }
}
