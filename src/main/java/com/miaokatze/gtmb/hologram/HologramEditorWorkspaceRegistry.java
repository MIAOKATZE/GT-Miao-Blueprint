package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** Two independently owned server plans; switching presentation never copies or converts a plan. */
final class HologramEditorWorkspaceRegistry {

    private static final Map<UUID, Map<String, HologramSession>> WORKSPACES = new HashMap<>();
    private static long nextEpoch;

    private HologramEditorWorkspaceRegistry() {}

    static String mode(HologramSession session) {
        // Imported declarations may reuse a live controller's structure API. Their
        // authoring ownership does not turn them into the independent projection plan.
        return session.target != null && !session.virtual
            && session.areaStructureId.isEmpty()
            && session.areaOverviewOwner == null ? "projection" : "blueprint";
    }

    static void remember(HologramSession session) {
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
        HologramArea area = HologramWorkspace.AREAS.get(session.player.getUniqueID());
        return HologramWorkspace.presentationAllowed(session) && !session.localPlanPending
            && !session.virtualPlacement
            && !session.placementValidating
            && session.attachmentTask == null
            && session.ae.preview == null
            && session.ae.review == null
            && !session.libraryQuickPending
            && !session.discardConfirmationRequired
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
        if (!request.hasKey("planRevision", 4) || request.getLong("planRevision") != current.planRevision
            || !HologramSessionPolicy.valid(current)
            || !idle(current)) {
            current.status = "当前操作尚未完成或会话已变化；原工作区保留";
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
        HologramWorkspace.PresentationTransition transition = HologramWorkspace.preparePresentation(current, target);
        if (transition == null) {
            current.status = "工作区切换条件已变化；原工作区保留";
            HologramSnapshots.send(current);
            return;
        }
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
