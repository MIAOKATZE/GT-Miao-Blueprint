package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/** Owns server session lifetime and per-player area registries; no public mutable session escapes. */
final class HologramWorkspace {

    private HologramWorkspace() {}

    private static final HologramSnapshotSink SNAPSHOTS = HologramNetwork::sendState;

    static HologramSnapshotSink snapshotSink() {
        return SNAPSHOTS;
    }

    static HologramSession find(UUID player) {
        return SESSIONS.get(player);
    }

    static void activate(UUID player, HologramSession session) {
        if (!player.equals(session.player.getUniqueID())) throw new IllegalArgumentException("Session owner mismatch");
        HologramSession previous = SESSIONS.get(player);
        if (previous != null && previous != session) HologramDeferredPlanRefresh.cancel(previous);
        SESSIONS.put(player, session);
    }

    static HologramSession create(EntityPlayerMP player, int x, int y, int z) {
        return create(player, x, y, z, true, 2);
    }

    static HologramSession create(EntityPlayerMP player, int x, int y, int z, boolean targetMode, int side) {
        HologramSession previous = find(player.getUniqueID());
        if (previous != null && (previous.player != player || !areaSessionOwned(previous)))
            clearAreaSelection(previous);
        return new HologramSession(player, x, y, z, targetMode, side);
    }

    private static final Map<UUID, HologramSession> SESSIONS = new HashMap<>();
    static final Map<UUID, HologramArea> AREAS = new HashMap<>();
    static final Map<UUID, HologramSession> AREA_EDITORS = new HashMap<>();
    static final Map<UUID, Map<String, HologramSession>> AREA_STRUCTURES = new HashMap<>();

    /** Persistent tool markers describe only a currently valid, server-owned selection session. */
    static boolean isAreaSelecting(EntityPlayer player, ItemStack stack) {
        HologramSession s = SESSIONS.get(player.getUniqueID());
        HologramArea area = AREAS.get(player.getUniqueID());
        return s != null && s.player == player
            && !s.cancelled
            && area != null
            && area.selecting
            && stack != null
            && stack.getItem() == s.tool
            && stack.hasTagCompound()
            && s.toolId.equals(
                stack.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID))
            && areaSessionOwned(s);
    }

    static boolean clearAreaMarker(HologramSession s, ItemStack stack) {
        if (stack == null || stack.getItem() != s.tool
            || !stack.hasTagCompound()
            || !s.toolId.equals(
                stack.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID))
            || !stack.getTagCompound()
                .hasKey("gtitHologramAreaSelecting"))
            return false;
        stack.getTagCompound()
            .removeTag("gtitHologramAreaSelecting");
        return true;
    }

    static void clearAreaSelection(HologramSession s) {
        HologramArea area = AREAS.remove(s.player.getUniqueID());
        if (area != null) area.cancelImportWork();
        AREA_EDITORS.remove(s.player.getUniqueID());
        AREA_STRUCTURES.remove(s.player.getUniqueID());
        boolean changed = clearAreaMarker(s, s.boundTool);
        for (ItemStack stack : s.player.inventory.mainInventory) changed |= clearAreaMarker(s, stack);
        for (ItemStack stack : s.player.inventory.armorInventory) changed |= clearAreaMarker(s, stack);
        if (changed) s.player.inventory.markDirty();
    }

    /** A cached editor keeps its planning data but never reuses a retired network nonce. */
    static void activateAreaSession(HologramSession s) {
        HologramDeferredPlanRefresh.suspend(s);
        s.id = UUID.randomUUID()
            .toString();
        s.uiSequence = 0;
        s.actionTick = -1;
        s.actionCount = 0;
    }

    static void clear() {
        HologramNetwork.clearServerQueues();
        for (HologramSession s : SESSIONS.values()) {
            HologramDeferredPlanRefresh.cancel(s);
            clearAreaSelection(s);
        }
        SESSIONS.clear();
        AREAS.clear();
        AREA_EDITORS.clear();
        AREA_STRUCTURES.clear();
    }

    /** Main-thread runtime reset. Completed transactions and durable cut/material journals are not undone. */
    static int restartRuntime(String reason) {
        // Retire old full snapshots before publishing terminal receipts; otherwise a stale DTO could
        // arrive after the cancellation and resurrect its editing/placement context.
        HologramNetwork.clearServerQueues();
        java.util.Set<HologramSession> all = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        all.addAll(SESSIONS.values());
        all.addAll(AREA_EDITORS.values());
        for (Map<String, HologramSession> structures : AREA_STRUCTURES.values()) all.addAll(structures.values());
        for (HologramArea area : AREAS.values()) {
            if (area.staging != null) all.add(area.staging);
            all.addAll(area.declarationSessions.values());
            area.cancelImportWork();
        }
        for (HologramSession s : all) {
            HologramDeferredPlanRefresh.cancel(s);
            HologramSession.cancelCaptureRetry(s);
            s.job = 4;
            s.phase = "STOPPED";
            s.defer = s.stopTick = false;
            s.status = reason;
            s.cancelled = true;
            HologramProjectorWorkflow.libraryCloseUi(s);
            if (SESSIONS.get(s.player.getUniqueID()) == s) {
                HologramSnapshots.sendStoppedJob(s);
                HologramNetwork.sendSessionCancelled(s.player, s.id, reason);
                s.player.addChatMessage(new net.minecraft.util.ChatComponentText(reason));
            }
            clearAreaSelection(s);
        }
        int count = SESSIONS.size();
        SESSIONS.clear();
        AREAS.clear();
        AREA_EDITORS.clear();
        AREA_STRUCTURES.clear();
        return count;
    }

    /** A suspended area belongs to the same live player and physical tool, even while another slot is held. */
    static boolean areaSessionOwned(HologramSession s) {
        if (s == null || s.player.isDead || s.player.worldObj != s.libraryWorld || s.player.dimension != s.dimension)
            return false;
        for (ItemStack stack : s.player.inventory.mainInventory) if (stack != null && stack.getItem() == s.tool
            && stack.hasTagCompound()
            && s.toolId.equals(
                stack.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID)))
            return true;
        return false;
    }

    /** Workspace alone removes/activates sessions. Domain ticks cannot retire another player's session. */
    static void forEachConnected(java.util.function.Consumer<HologramSession> tick) {
        java.util.Iterator<HologramSession> sessions = SESSIONS.values()
            .iterator();
        while (sessions.hasNext()) {
            HologramSession session = sessions.next();
            if (session.player.playerNetServerHandler == null
                || session.player.playerNetServerHandler.netManager == null
                || !session.player.playerNetServerHandler.netManager.isChannelOpen()) {
                HologramSession.clearPending(session);
                HologramSession.cancelCaptureRetry(session);
                HologramDeferredPlanRefresh.cancel(session);
                clearAreaSelection(session);
                sessions.remove();
            } else tick.accept(session);
        }
    }

}
