package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** Owns the current projection and author draft; confirmed projection departure retires its memory. */
final class HologramEditorWorkspaceRegistry {

    private static final Map<UUID, Map<String, HologramSession>> WORKSPACES = new HashMap<>();
    private static long nextEpoch;

    private HologramEditorWorkspaceRegistry() {}

    static String mode(HologramSession session) {
        if (session.emptyProjection) return "projection";
        // Imported declarations may reuse a live controller's structure API. Their
        // authoring ownership does not turn them into the independent projection plan.
        return session.target != null && !session.virtual
            && session.areaStructureId.isEmpty()
            && session.areaOverviewOwner == null ? "projection" : "blueprint";
    }

    static void remember(HologramSession session) {
        if (session.cancelled) return;
        Map<String, HologramSession> owned = WORKSPACES
            .computeIfAbsent(session.player.getUniqueID(), id -> new HashMap<>());
        owned.values()
            .removeIf(
                previous -> previous.player != session.player || previous.libraryWorld != session.libraryWorld
                    || !previous.toolId.equals(session.toolId));
        owned.put(mode(session), session);
    }

    static void activated(HologramSession session) {
        session.editorWorkspaceEpoch = ++nextEpoch;
        remember(session);
    }

    static long sequenceFloor(HologramSession session) {
        long sequence = session.uiSequence;
        Map<String, HologramSession> owned = WORKSPACES.get(session.player.getUniqueID());
        if (owned != null)
            for (HologramSession candidate : owned.values()) sequence = Math.max(sequence, candidate.uiSequence);
        return sequence;
    }

    static boolean idle(HologramSession session) {
        return idle(session, false);
    }

    private static boolean idle(HologramSession session, boolean confirmedDiscard) {
        HologramArea area = HologramWorkspace.AREAS.get(session.player.getUniqueID());
        return HologramWorkspace.presentationAllowed(session) && !session.localPlanPending
            && !session.virtualPlacement
            && !session.placementValidating
            && session.attachmentTask == null
            && session.ae.preview == null
            && session.ae.review == null
            && !session.libraryQuickPending
            && (confirmedDiscard || !session.discardConfirmationRequired)
            && (!session.processingWorkflowStarted || session.processingReviewed)
            && (area == null || !area.selecting && !area.importing);
    }

    /** A Cut browser opens a server-owned presentation without replacing a retained author draft. */
    static HologramSession openCutWorkspace(EntityPlayerMP player) {
        if (player == null || player.isDead
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem()))
            return null;
        HologramSession active = HologramWorkspace.find(player.getUniqueID());
        if (active != null && active.player == player && !idle(active)) return null;
        // A world entry needs a complete snapshot; its preparation enforces tickValid().
        // An already open root can browse Cut locally without re-encoding its read-only plan.
        if (cutOwned(active, player) && HologramSessionPolicy.tickValid(active)) return active;
        boolean preserveBlueprint = cutOwned(active, player) && "blueprint".equals(mode(active));
        Map<String, HologramSession> owned = WORKSPACES.get(player.getUniqueID());
        if (owned != null) for (String domain : new String[] { "blueprint", "projection" }) {
            HologramSession retained = owned.get(domain);
            if (cutOwned(retained, player)) {
                if ("blueprint".equals(domain)) preserveBlueprint = true;
                if (!HologramSessionPolicy.tickValid(retained)) continue;
                if (!idle(retained)) return null;
                HologramWorkspace.activate(player.getUniqueID(), retained);
                return retained;
            }
        }
        if (preserveBlueprint) return null;
        HologramSession created = HologramWorkspace.create(
            player,
            net.minecraft.util.MathHelper.floor_double(player.posX),
            net.minecraft.util.MathHelper.floor_double(player.posY),
            net.minecraft.util.MathHelper.floor_double(player.posZ),
            false,
            1);
        created.virtual = true;
        created.workspaceTab = "blueprint";
        created.blueprintSource = "author";
        if (!HologramSessionPolicy.valid(created)) return null;
        HologramWorkspace.activate(player.getUniqueID(), created);
        return created;
    }

    private static boolean cutOwned(HologramSession session, EntityPlayerMP player) {
        return session != null && session.player == player
            && !session.cancelled
            && session.libraryWorld == player.worldObj
            && session.dimension == player.dimension
            && player.getHeldItem() != null
            && player.getHeldItem()
                .getItem() == session.tool
            && player.getHeldItem()
                .hasTagCompound()
            && session.toolId.equals(
                player.getHeldItem()
                    .getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID));
    }

    static void switchWorkspace(HologramSession current, NBTTagCompound request) {
        String requested = request.getString("editorWorkspace");
        if (!("projection".equals(requested) || "blueprint".equals(requested))) return;
        if (requested.equals(mode(current))) {
            HologramSnapshots.send(current);
            return;
        }
        boolean discard = "projection".equals(mode(current)) && !current.emptyProjection
            && "blueprint".equals(requested);
        boolean confirmed = discard && discardConfirmed(current, request);
        if (!request.hasKey("planRevision", 4) || request.getLong("planRevision") != current.planRevision
            || !request.hasKey("editorWorkspaceEpoch", 4)
            || request.getLong("editorWorkspaceEpoch") != current.editorWorkspaceEpoch
            || !HologramSessionPolicy.valid(current)
            || !idle(current, confirmed)) {
            current.status = "当前操作尚未完成或会话已变化；原工作区保留";
            HologramSnapshots.send(current);
            return;
        }
        if (discard && !confirmed) {
            HologramAuthoringWorkflow.clearSourceSwitch(current);
            NBTTagCompound proposal = new NBTTagCompound();
            proposal.setString("op", "switchEditorWorkspace");
            proposal.setString("session", current.id);
            proposal.setString("editorWorkspace", "blueprint");
            proposal.setLong("editorWorkspaceEpoch", current.editorWorkspaceEpoch);
            proposal.setLong("planRevision", current.planRevision);
            proposal.setString(
                "sourceSwitchNonce",
                UUID.randomUUID()
                    .toString());
            current.pendingSourceAction = proposal;
            current.discardConfirmationRequired = true;
            current.discardTargetOp = "switchEditorWorkspace";
            current.status = "打开蓝图将丢弃当前投影及投影记忆；请确认是否继续";
            HologramSnapshots.send(current);
            return;
        }
        remember(current);
        HologramSession target = WORKSPACES.get(current.player.getUniqueID())
            .get(requested);
        if (target == null && "blueprint".equals(requested)) {
            target = HologramWorkspace.create(current.player, current.x, current.y, current.z, false, current.side);
            target.virtual = true;
            target.workspaceTab = "blueprint";
            target.blueprintSource = "author";
            target.status = "独立蓝图工作区；可载入蓝图、采集区域或添加图文";
        }
        if ("projection".equals(requested) && (target == null || target.cancelled
            || target.player != current.player
            || !HologramSessionPolicy.valid(target))) {
            target = HologramWorkspace.create(current.player, current.x, current.y, current.z, false, current.side);
            target.virtual = true;
            target.emptyProjection = true;
            target.workspaceTab = "structure";
            target.status = "右击控制器开启投影模式";
        }
        if (target == null || target.player != current.player
            || target.cancelled
            || !target.toolId.equals(current.toolId)
            || !HologramSessionPolicy.valid(target)
            || !idle(target)) {
            current.status = "projection".equals(requested) ? "请先右键真实控制器开启投影工作区" : "目标工作区暂不可用；原工作区保留";
            HologramSnapshots.send(current);
            return;
        }
        if (target.uiSequence > current.uiSequence) {
            // Do not change an in-flight command's exact ACK identity. The full source ACK
            // publishes the sequence floor; a fresh request can then use the newer sequence.
            current.status = "目标工作区回执序号较新；请等待同步后重试";
            HologramSnapshots.send(current);
            return;
        }
        current.open = true;
        HologramWorkspace.PresentationTransition transition = HologramWorkspace.preparePresentation(current, target);
        if (transition == null) {
            current.status = "工作区切换条件已变化；原工作区保留";
            HologramSnapshots.send(current);
            return;
        }
        transition.discardProjection = discard;
        // Encoding and queue failures use the existing provisional-presentation rollback.
        HologramNetwork.retirePresentationState(current);
        HologramSnapshotPreparation.cancel(current);
        if (!HologramWorkspace.activatePresentation(transition)) {
            HologramSnapshots.send(current);
            return;
        }
        target.localEdits.invalidate();
        target.open = true;
        target.openPurpose = "editorWorkspaceSwitch";
        HologramSnapshots.send(target);
    }

    static boolean discardConfirmed(HologramSession current, NBTTagCompound request) {
        NBTTagCompound pending = current.pendingSourceAction;
        return current.discardConfirmationRequired && request.getBoolean("confirmDiscard")
            && "switchEditorWorkspace".equals(pending.getString("op"))
            && "blueprint".equals(pending.getString("editorWorkspace"))
            && "blueprint".equals(request.getString("editorWorkspace"))
            && current.id.equals(pending.getString("session"))
            && !pending.getString("sourceSwitchNonce")
                .isEmpty()
            && pending.getString("sourceSwitchNonce")
                .equals(request.getString("sourceSwitchNonce"))
            && pending.hasKey("planRevision", 4)
            && pending.getLong("planRevision") == current.planRevision
            && request.hasKey("planRevision", 4)
            && request.getLong("planRevision") == current.planRevision
            && pending.getLong("editorWorkspaceEpoch") == current.editorWorkspaceEpoch;
    }

    static boolean physicalProjection(HologramSession source) {
        return !source.emptyProjection && "projection".equals(mode(source));
    }

    /** Freeze the complete destination once. Confirmation replays this server-owned intent, not client arguments. */
    static boolean allowProjectionSource(HologramSession source, NBTTagCompound action, String operation, String id) {
        if (!physicalProjection(source) || "resetDraft".equals(operation)) return true;
        if (source.projectionDiscardApproved) return true;
        if (!idle(source) || !HologramSessionPolicy.valid(source)) return false;
        HologramAuthoringWorkflow.clearSourceSwitch(source);
        NBTTagCompound frozen = (NBTTagCompound) action.copy();
        frozen.setString("projectionOriginalOp", action.getString("op"));
        frozen.setString("op", operation);
        frozen.setString("presetId", id == null ? "" : id);
        NBTTagCompound proposal = new NBTTagCompound();
        proposal.setString("op", "confirmProjectionSource");
        proposal.setString("session", source.id);
        proposal.setLong("editorWorkspaceEpoch", source.editorWorkspaceEpoch);
        proposal.setLong("planRevision", source.planRevision);
        proposal.setString(
            "sourceSwitchNonce",
            UUID.randomUUID()
                .toString());
        proposal.setTag("projectionAction", frozen);
        source.pendingSourceAction = proposal;
        source.discardConfirmationRequired = true;
        source.discardTargetOp = "confirmProjectionSource";
        source.discardTargetId = id == null ? "" : id;
        source.open = true;
        source.status = "打开蓝图将丢弃当前投影及投影记忆；请确认是否继续";
        return false;
    }

    static void confirmProjectionSource(HologramSession source, NBTTagCompound request) {
        NBTTagCompound pending = source.pendingSourceAction;
        if (!physicalProjection(source) || !source.discardConfirmationRequired
            || !"confirmProjectionSource".equals(pending.getString("op"))
            || !request.getBoolean("confirmDiscard")
            || !request.hasKey("planRevision", 4)
            || request.getLong("planRevision") != source.planRevision
            || !request.hasKey("editorWorkspaceEpoch", 4)
            || request.getLong("editorWorkspaceEpoch") != source.editorWorkspaceEpoch
            || pending.getLong("planRevision") != source.planRevision
            || pending.getLong("editorWorkspaceEpoch") != source.editorWorkspaceEpoch
            || pending.getString("sourceSwitchNonce")
                .isEmpty()
            || !pending.getString("sourceSwitchNonce")
                .equals(request.getString("sourceSwitchNonce"))
            || !HologramSessionPolicy.valid(source)
            || !idle(source, true)) {
            HologramSnapshots.send(source);
            return;
        }
        NBTTagCompound frozen = (NBTTagCompound) pending.getCompoundTag("projectionAction")
            .copy();
        frozen.setString("session", source.id);
        frozen.setLong("editorWorkspaceEpoch", source.editorWorkspaceEpoch);
        frozen.setLong("planRevision", source.planRevision);
        frozen.setLong("uiSequence", source.uiSequence);
        frozen.setBoolean("confirmDiscard", true);
        HologramPresets.Preset oldPrivate = source.libraryPrivatePreset;
        String oldLocal = source.libraryLocalId, oldReplace = source.libraryReplaceLocalId;
        source.projectionDiscardApproved = true;
        source.open = true;
        try {
            if ("projectionLibraryActivate".equals(frozen.getString("op"))) {
                frozen.setString("op", frozen.getString("projectionOriginalOp"));
                HologramLibraryFacade.resumePrivate(source.player, frozen, source.projectionSourcePreset);
            } else if ("projectionQuickPlace".equals(frozen.getString("op"))) {
                HologramWorldPlacementWorkflow.quickPlaceLastBlueprint(source.player, frozen);
            } else HologramActionDispatcher.handle(source.player, frozen);
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            HologramSession active = HologramWorkspace.find(source.player.getUniqueID());
            if (active != source && active != null)
                HologramWorkspace.rollbackPresentation(source.player, active.id, source.uiSequence);
            source.status = "蓝图打开失败：" + failure.getMessage();
            HologramSnapshots.send(source);
        } finally {
            source.projectionDiscardApproved = false;
            source.libraryPrivatePreset = oldPrivate;
            source.libraryLocalId = oldLocal;
            source.libraryReplaceLocalId = oldReplace;
        }
    }

    /** Called only after the full target baseline commits; rollback retains the source unchanged. */
    static void discardProjection(HologramSession source) {
        Map<String, HologramSession> owned = WORKSPACES.get(source.player.getUniqueID());
        if (owned != null && owned.get("projection") == source) owned.remove("projection");
        HologramDraftPersistence.forgetProjection(source);
        HologramAuthoringWorkflow.clearSourceSwitch(source);
        HologramDeferredPlanRefresh.cancel(source);
        HologramSnapshotPreparation.cancel(source);
        HologramSession.cancelCaptureRetry(source);
        HologramNetwork.retirePresentationState(source);
        source.cancelled = true;
    }

    static void forget(UUID owner) {
        Map<String, HologramSession> owned = WORKSPACES.remove(owner);
        if (owned != null) for (HologramSession session : owned.values()) {
            HologramDeferredPlanRefresh.cancel(session);
            HologramSnapshotPreparation.cancel(session);
            if (HologramWorkspace.find(owner) != session) HologramSession.cancelCaptureRetry(session);
        }
    }

    static void clear() {
        for (UUID owner : new java.util.ArrayList<>(WORKSPACES.keySet())) forget(owner);
    }
}
