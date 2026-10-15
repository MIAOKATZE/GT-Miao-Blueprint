package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Owner/session/revision-bound library transfer and save receipt admission. */
final class HologramLibraryWorkflow {

    private HologramLibraryWorkflow() {}

    /** Admission is checked at begin and again after the bounded worker finishes decoding. */
    static boolean librarySessionCurrent(EntityPlayerMP player, NBTTagCompound request) {
        if (player == null || request == null) return false;
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        return s != null && s.player == player
            && s.libraryWorld == player.worldObj
            && (request.getBoolean("quickPlace") ? libraryQuickCurrent(s, request) : s.libraryUiActive)
            && !s.cancelled
            && !s.capturePending
            && s.job != 1
            && s.job != 2
            && !HologramSessionPolicy.blockedByAreaImport(s)
            && request.hasKey("session", 8)
            && s.id.equals(request.getString("session"))
            && request.hasKey("planRevision", 4)
            && s.planRevision == request.getLong("planRevision")
            && HologramSessionPolicy.valid(s);
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
        if (previous != null
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
        HologramSession session = HologramWorkspace.create(player, x, y, z, false, side);
        session.virtual = true;
        session.open = false;
        session.libraryQuickToken = token;
        session.libraryQuickPending = true;
        session.libraryQuickBootstrapTick = tick;
        HologramWorkspace.activate(player.getUniqueID(), session);
        NBTTagCompound result = HologramLibraryProtocol.reply("quickBootstrap");
        result.setString("quickToken", token);
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
            if (!pending && s.virtual && s.virtualPlacement && !s.virtualLocked) {
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

    static String libraryActivate(EntityPlayerMP player, NBTTagCompound request, HologramPresets.Preset uploaded)
        throws java.io.IOException {
        if (!HologramLibraryService.sessionCurrent(player, request)) throw new java.io.IOException("蓝图会话已更新，请重新选择蓝图");
        String action = request.getString("action");
        String operation;
        if ("preview".equals(action)) operation = "previewPreset";
        else if ("place".equals(action)) operation = "choosePlacementPreset";
        else if ("edit".equals(action)) operation = "editPreset";
        else throw new java.io.IOException("未知私库蓝图操作");
        HologramAePolicy.Draft reviewed = HologramAePolicy.sanitize(uploaded);
        HologramPresets.validate(reviewed.preset);
        if (!librarySessionCurrent(player, request)) throw new java.io.IOException("蓝图会话已更新");
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (!HologramAuthoringWorkflow.allowBlueprintDiscard(s, request, operation, request.getString("localId"))) {
            HologramSnapshots.send(s);
            throw new java.io.IOException("当前蓝图有未保存修改；请确认放弃后重新选择");
        }
        HologramPresets.Preset staged = reviewed.preset;
        String localId = request.getString("localId");
        if (localId.isEmpty()) localId = uploaded.id == null ? "" : uploaded.id;
        s.libraryLocalId = HologramLibraryService.localId(localId, false);
        s.libraryReplaceLocalId = "";
        staged.id = "private_" + UUID.randomUUID()
            .toString();
        s.libraryPrivatePreset = staged;
        NBTTagCompound activation = new NBTTagCompound();
        activation.setString("op", operation);
        activation.setString("session", s.id);
        activation.setLong("planRevision", s.planRevision);
        activation.setString("presetId", staged.id);
        activation.setBoolean("confirmDiscard", request.getBoolean("confirmDiscard"));
        HologramActionDispatcher.handle(player, activation);
        if (request.getBoolean("quickPlace")) s.libraryQuickPending = false;
        return staged.id;
    }

    static void libraryExportDraft(EntityPlayerMP player, NBTTagCompound request) throws java.io.IOException {
        if (!HologramLibraryFacade.enabled() || !librarySessionCurrent(player, request))
            throw new java.io.IOException("蓝图会话已更新，请重新保存");
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
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
        s.libraryReplaceLocalId = HologramLibraryService.localId(request.getString("replaceLocalId"), true);
        HologramAuthoringWorkflow.beginAeSaveReview(s, preset, "exportDraft", !s.libraryReplaceLocalId.isEmpty());
        HologramSnapshots.send(s);
    }

    static void saveReviewedDraft(HologramSession s, HologramPresets.Preset preset, String operation, boolean update,
        boolean ae) throws java.io.IOException {
        saveReviewedArtifact(s, preset, operation, update, ae, false);
    }

    /** This flag is generated only by the authenticated native-controller template save path. */
    static void saveReviewedArtifact(HologramSession s, HologramPresets.Preset preset, String operation, boolean update,
        boolean ae, boolean nativeTemplate) throws java.io.IOException {
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
        HologramLibraryService.stampAuthor(preset, s.player, !update && !"importBlueprint".equals(operation));
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
            HologramLibraryFacade.downloadDraft(s.player, exported, s.id, s.planRevision, localId, receipt);
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

    /** A successful transfer is not a successful disk write. Only this owner-bound receipt clears the draft. */
    static boolean acknowledgeBlueprintSave(HologramSession s, NBTTagCompound request) {
        if (!librarySaveCurrent(s.player, request) || !HologramLibraryService.connectedProjector(s.player)
            || !s.id.equals(request.getString("session"))
            || s.blueprintSaveReceipt.isEmpty()
            || !s.blueprintSaveReceipt.equals(request.getString("saveReceipt"))
            || !s.blueprintSaveLocalId.equals(request.getString("localId"))
            || !request.hasKey("planRevision", 4)
            || request.getLong("planRevision") != s.blueprintSaveRevision
            || s.planRevision != s.blueprintSaveRevision
            || s.blueprintSaveJsonSha256.isEmpty()
            || !s.blueprintSaveJsonSha256.equals(request.getString("jsonSha256"))
            || s.blueprintSavePreviewSha256.isEmpty()
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
        if (!s.blueprintSaveSelectedPresetId.isEmpty()) HologramSessionPolicy.currentTool(s)
            .getTagCompound()
            .setString(HologramSessionKeys.SELECTED_PRESET, s.blueprintSaveSelectedPresetId);
        s.blueprintSaveSelectedPresetId = "";
        s.blueprintSaveReceipt = "";
        s.blueprintDirty = false;
        s.blueprintSource = "saved";
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
            && s.libraryUiActive
            && HologramWorkspace.areaSessionOwned(s)
            && HologramSessionPolicy.valid(s)
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
