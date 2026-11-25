// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.world.World;

import io.netty.channel.Channel;

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

    // Forge's logical-server lifecycle alone publishes/revokes endpoint generations. Network ingress only reads them.
    private static final Map<UUID, ActionEndpoint> ACTION_ENDPOINTS = new ConcurrentHashMap<>();
    // This dormant runtime cannot be activated until competing legacy mutation roots observe its busy/recovery barrier.
    private static volatile HologramConstructionTaskGate constructionRuntime;

    /** Object identity is a generation: going A -> B -> A does not make the first A current again. */
    static final class ActionEndpoint {

        final UUID actor;
        final EntityPlayerMP player;
        final World world;
        final NetHandlerPlayServer connection;
        final HologramConstructionTaskGate.Scope constructionScope;
        private final HologramConstructionTaskGate constructionGate;
        private final NetworkManager network;
        private final Channel channel;
        private final int dimension;
        // The handler keeps this entity alive until 1.7.10 finishes transferring to the respawned player.
        private final WeakReference<EntityPlayerMP> provisionalPredecessor;

        private ActionEndpoint(UUID actor, EntityPlayerMP player, World world, NetHandlerPlayServer connection,
            NetworkManager network, Channel channel, WeakReference<EntityPlayerMP> provisionalPredecessor,
            HologramConstructionTaskGate constructionGate, HologramConstructionTaskGate.Scope constructionScope) {
            this.actor = actor;
            this.player = player;
            this.world = world;
            this.connection = connection;
            this.network = network;
            this.channel = channel;
            this.provisionalPredecessor = provisionalPredecessor;
            this.constructionGate = constructionGate;
            this.constructionScope = constructionScope;
            dimension = player.dimension;
        }

        private boolean transportCurrent() {
            return connection.playerEntity == player && player.playerNetServerHandler == connection
                && connection.netManager == network
                && network.getNetHandler() == connection
                && network.channel() == channel
                && channel.isOpen();
        }
    }

    /** Server-thread only. In 1.7.10 the respawn event precedes assigning the new entity to the existing handler. */
    static void publishActionEndpoint(EntityPlayerMP player, boolean respawn) {
        publishActionEndpoint(player, respawn, false);
    }

    /** A real dimension event is a new generation even when an earlier listener has already moved the player back. */
    static void publishActionDimension(EntityPlayerMP player) {
        publishActionEndpoint(player, false, true);
    }

    private static void publishActionEndpoint(EntityPlayerMP player, boolean respawn, boolean dimensionChange) {
        NetHandlerPlayServer connection = player.playerNetServerHandler;
        World world = player.worldObj;
        if (world == null || world.isRemote || connection == null || connection.netManager == null) return;
        NetworkManager network = connection.netManager;
        Channel channel = network.channel();
        if (channel == null || !channel.isOpen() || network.getNetHandler() != connection) return;
        UUID actor = player.getUniqueID();
        ActionEndpoint previous = ACTION_ENDPOINTS.get(actor);
        boolean sameTransport = previous != null && previous.connection == connection
            && previous.network == network
            && previous.channel == channel;
        WeakReference<EntityPlayerMP> predecessor = sameTransport ? previous.provisionalPredecessor : null;
        EntityPlayerMP installed = connection.playerEntity;
        boolean transferPending = installed != null && predecessor != null && predecessor.get() == installed;
        WeakReference<EntityPlayerMP> provisional = null;
        if (installed != player) {
            if (respawn && installed != null && sameTransport && previous.player == installed)
                provisional = new WeakReference<>(installed);
            else if (dimensionChange && transferPending && previous.player == player) provisional = predecessor;
            else return;
        } else if (transferPending && previous.player != player) {
            // An event for the old installed entity cannot overwrite the already-published successor.
            return;
        }
        if (!dimensionChange && sameTransport
            && previous.player == player
            && previous.world == world
            && previous.dimension == player.dimension) return;
        HologramConstructionTaskGate gate = openConstructionRuntime();
        HologramConstructionTaskGate.Scope expected = previous != null && previous.constructionGate == gate
            ? previous.constructionScope
            : null;
        // Do not use the bare handler as the generation: all transport/world references can match after a round trip.
        HologramConstructionTaskGate.Scope scope = gate == null ? null
            : gate.bind(expected, actor, player, world, new Object());
        if (expected != null && scope == null) gate.revoke(expected);
        ACTION_ENDPOINTS.put(
            actor,
            new ActionEndpoint(actor, player, world, connection, network, channel, provisional, gate, scope));
    }

    static void revokeActionEndpoint(EntityPlayerMP player) {
        UUID actor = player.getUniqueID();
        ActionEndpoint previous = ACTION_ENDPOINTS.get(actor);
        if (previous != null && previous.player == player
            && previous.connection == player.playerNetServerHandler
            && previous.network == previous.connection.netManager
            && previous.channel == previous.network.channel()
            && ACTION_ENDPOINTS.remove(actor, previous)
            && previous.constructionScope != null
            && previous.constructionGate.onOwnerThread()) previous.constructionGate.revoke(previous.constructionScope);
    }

    private static synchronized HologramConstructionTaskGate openConstructionRuntime() {
        HologramConstructionTaskGate gate = constructionRuntime;
        if (gate == null || gate.stoppedAndDrained()) {
            gate = new HologramConstructionTaskGate(256, 16, 256);
            constructionRuntime = gate;
        }
        return gate.onOwnerThread() ? gate : null;
    }

    private static synchronized void stopConstructionRuntime() {
        HologramConstructionTaskGate gate = constructionRuntime;
        // If the old runtime still has an uncertain permit, it remains the owner of that evidence. A new
        // server thread cannot replace it on login just because no job appears in the legacy workspace.
        if (gate != null && gate.onOwnerThread() && gate.shutdown()) constructionRuntime = null;
    }

    /**
     * Server-thread lookup for explicitly scoped construction only. Never binds on ingress, reads a journal,
     * or grants authority to a stale endpoint. Keep the original gate and permit for later exact settlement.
     */
    static HologramConstructionTaskGate constructionGate(ActionEndpoint endpoint) {
        if (endpoint == null || endpoint.constructionScope == null
            || !endpoint.constructionGate.onOwnerThread()
            || !currentActionEndpoint(endpoint)
            || !endpoint.constructionGate.scopeCurrent(endpoint.constructionScope)) return null;
        return endpoint.constructionGate;
    }

    /** No scope binding here: old authorities must respect the same actor barrier, even after a reconnect. */
    static String legacyGateRefusal(UUID actor) {
        HologramConstructionTaskGate gate = constructionRuntime;
        if (gate == null) return null;
        if (!gate.onOwnerThread()) return gate.stoppedAndDrained() ? null : "上一施工运行时尚有结果待核定，未开始新操作";
        return gate.busy(actor) ? "已有施工任务或待核定的施工结果，未开始新操作" : null;
    }

    static String legacyConstructionRefusal(EntityPlayerMP player) {
        UUID actor = player.getUniqueID();
        String reason = legacyGateRefusal(actor);
        if (reason != null) return reason;
        ActionEndpoint exact = ACTION_ENDPOINTS.get(actor);
        // Detached test/offline callers do not create a scope. Live requests check the existing authoritative journal.
        return exact != null && exact.player == player
            && currentActionEndpoint(exact)
            && HologramConstructionJournal.pending(player) ? "有施工凭据待核定，未开始新操作" : null;
    }

    static boolean refuseLegacyConstruction(HologramSession s, net.minecraft.nbt.NBTTagCompound request) {
        String reason = legacyConstructionRefusal(s.player);
        if (reason == null) return false;
        net.minecraft.nbt.NBTTagCompound progress = new net.minecraft.nbt.NBTTagCompound();
        progress.setString("scope", "server");
        progress.setString("phase", "transport.busy");
        progress.setString("outcome", "rejected");
        progress.setString("code", "busy");
        progress.setString("session", s.id);
        String operation = request.getString("op");
        progress.setString("op", operation.substring(0, Math.min(32, operation.length())));
        if (request.hasKey("uiSequence", 4)) progress.setLong("uiSequence", request.getLong("uiSequence"));
        progress.setString("message", reason);
        net.minecraft.nbt.NBTTagCompound state = new net.minecraft.nbt.NBTTagCompound();
        state.setBoolean("progressOnly", true);
        state.setTag("progress", progress);
        HologramNetwork.sendState(s.player, state);
        return true;
    }

    /** Immutable proof captured from the context's actual handler, never from NBT or the workspace's active session. */
    static ActionEndpoint captureActionEndpoint(NetHandlerPlayServer sender) {
        if (sender == null) return null;
        EntityPlayerMP player = sender.playerEntity;
        if (player == null) return null;
        ActionEndpoint endpoint = ACTION_ENDPOINTS.get(player.getUniqueID());
        return endpoint != null && endpoint.player == player
            && endpoint.connection == sender
            && endpoint.transportCurrent() ? endpoint : null;
    }

    static boolean currentActionEndpoint(ActionEndpoint endpoint) {
        return endpoint != null && ACTION_ENDPOINTS.get(endpoint.actor) == endpoint
            && endpoint.player.worldObj == endpoint.world
            && endpoint.player.dimension == endpoint.dimension
            && endpoint.transportCurrent();
    }

    private static final ClassValue<Boolean> EBF_RECEIPT_GETTER = new ClassValue<>() {

        @Override
        protected Boolean computeValue(Class<?> player) {
            try {
                // The existing scoped ledger calls getEntityData when retiring its receipt. In EBF's
                // final pure phase this must remain Forge Entity's normal stored-compound accessor.
                return player.getMethod("getEntityData")
                    .getDeclaringClass() == Entity.class;
            } catch (NoSuchMethodException | SecurityException | LinkageError unknown) {
                return false;
            }
        }
    };

    /** EBF's callback-free tail after live transport. Exact NetworkManager getters are just field reads. */
    static boolean ebfActionCurrentPure(ActionEndpoint endpoint, HologramConstructionTaskGate gate) {
        return endpoint != null && ACTION_ENDPOINTS.get(endpoint.actor) == endpoint
            && endpoint.player.worldObj == endpoint.world
            && endpoint.player.dimension == endpoint.dimension
            && endpoint.player.playerNetServerHandler == endpoint.connection
            && endpoint.connection.playerEntity == endpoint.player
            && endpoint.connection.netManager == endpoint.network
            && endpoint.network.getClass() == NetworkManager.class
            && endpoint.network.getNetHandler() == endpoint.connection
            && endpoint.network.channel() == endpoint.channel
            && EBF_RECEIPT_GETTER.get(endpoint.player.getClass())
            && gate != null
            && gate == endpoint.constructionGate
            && gate == constructionRuntime
            && gate.onOwnerThread()
            && endpoint.constructionScope != null
            && gate.scopeCurrent(endpoint.constructionScope);
    }

    /** Cut precommit tail: only owned fields and in-memory gate state, never transport or journal callbacks. */
    static boolean cutActionCurrentPure(ActionEndpoint endpoint) {
        if (endpoint == null || ACTION_ENDPOINTS.get(endpoint.actor) != endpoint
            || endpoint.player.worldObj != endpoint.world
            || endpoint.player.dimension != endpoint.dimension
            || endpoint.player.playerNetServerHandler != endpoint.connection
            || endpoint.connection.playerEntity != endpoint.player
            || endpoint.connection.netManager != endpoint.network) return false;
        HologramConstructionTaskGate gate = endpoint.constructionGate;
        return gate != null && gate == constructionRuntime
            && gate.onOwnerThread()
            && endpoint.constructionScope != null
            && gate.scopeCurrent(endpoint.constructionScope)
            && !gate.busy(endpoint.actor);
    }

    static void activate(UUID player, HologramSession session) {
        if (!player.equals(session.player.getUniqueID())) throw new IllegalArgumentException("Session owner mismatch");
        HologramSession previous = SESSIONS.get(player);
        if (previous != null && !previous.stockNativePrivate) HologramEditorWorkspaceRegistry.remember(previous);
        HologramSession projection = HologramEditorWorkspaceRegistry.retained(player, "projection");
        if (projection != null && projection != session
            && HologramEditorWorkspaceRegistry.physicalProjection(projection)) {
            HologramEditorWorkspaceRegistry.discardProjection(projection);
            HologramNetwork.sendControllerProjectionRetired(projection, "");
        }
        if (previous != null && previous != session) {
            HologramNativeCompactStart.cancel(previous);
            HologramNativeReadyPlacement.cancel(previous);
            previous.presentationTransition = null;
            HologramDeferredPlanRefresh.cancel(previous);
            if (previous.capturePending) {
                HologramControllerCapture.stop(previous, "结构采集已停止：已切换目标", null);
            } else HologramSession.cancelCaptureRetry(previous);
        }
        SESSIONS.put(player, session);
        HologramEditorWorkspaceRegistry.activated(session);
    }

    static void pruneRemovedController(EntityPlayerMP player) {
        HologramSession projection = HologramEditorWorkspaceRegistry.retained(player.getUniqueID(), "projection");
        if (projection == null || projection.player != player
            || projection.libraryWorld != player.worldObj
            || !HologramEditorWorkspaceRegistry.physicalProjection(projection)
            || !HologramSessionPolicy.controllerReplaced(projection)) return;
        retireFieldProjection(projection, "原控制器已拆除或更换，现场规划已清除");
    }

    static void retireFieldProjection(HologramSession projection, String reason) {
        HologramNativeCompactStart.cancel(projection);
        HologramNativeReadyPlacement.cancel(projection);
        HologramAeCraftingOrders.cancelForSession(projection, "现场已失效，AE 缺料订单已取消");
        if (projection.job == 1 || projection.job == 2) {
            projection.job = 4;
            projection.phase = "STOPPED";
            projection.status = "施工已停止：" + reason + "；已施工方块保留";
            HologramSnapshots.sendStoppedJob(projection);
        }
        HologramEditorWorkspaceRegistry.discardProjection(projection);
        SESSIONS.remove(projection.player.getUniqueID(), projection);
        HologramNetwork.sendControllerProjectionRetired(projection, reason);
    }

    /** A preset preview is provisional until its first complete baseline is accepted by the encoder. */
    static final class PresentationTransition {

        final HologramSession origin, target;
        final String originId, targetId, selected;
        final long sequence, sourceTask, targetTask;
        final long originEditorEpoch;
        final boolean hadSelection;
        boolean discardProjection;

        PresentationTransition(HologramSession origin, HologramSession target) {
            this.origin = origin;
            this.target = target;
            target.snapshotPaletteProtocol = origin.snapshotPaletteProtocol;
            originId = origin.id;
            targetId = target.id;
            sequence = origin.uiSequence;
            originEditorEpoch = origin.editorWorkspaceEpoch;
            sourceTask = origin.blueprintClientTaskToken;
            targetTask = target.blueprintClientTaskToken;
            discardProjection = HologramEditorWorkspaceRegistry.physicalProjection(origin)
                && (origin.projectionDiscardApproved || origin.projectionAreaImportDiscard);
            ItemStack held = HologramSessionPolicy.currentTool(origin);
            hadSelection = held != null && held.getTagCompound()
                .hasKey(HologramSessionKeys.SELECTED_PRESET, 8);
            selected = hadSelection ? held.getTagCompound()
                .getString(HologramSessionKeys.SELECTED_PRESET) : "";
        }

        boolean matchesTarget(EntityPlayerMP player, String session, long sequence) {
            return find(player.getUniqueID()) == target && target.player == player
                && origin.player == player
                && targetId.equals(target.id)
                && targetId.equals(session)
                && originId.equals(origin.id)
                && this.sequence == sequence
                && target.uiSequence == sequence
                && origin.uiSequence == sequence
                && target.blueprintClientTaskToken == targetTask
                && origin.blueprintClientTaskToken == sourceTask
                && !origin.cancelled
                && !target.cancelled
                && player.worldObj == origin.libraryWorld
                && target.libraryWorld == origin.libraryWorld
                && player.dimension == origin.dimension
                && target.dimension == origin.dimension
                && target.tool == origin.tool
                && target.slot == origin.slot
                && target.toolId.equals(origin.toolId)
                && HologramSessionPolicy.currentTool(target) != null;
        }
    }

    static boolean presentationAllowed(HologramSession session) {
        return session.job != 1 && session.job != 2
            && !session.capturePending
            && session.blueprintSaveReceipt.isEmpty()
            && !HologramAeCraftingOrders.active(session.player)
            && !HologramDemolition.active(session.player);
    }

    static boolean reserveCompactNative(HologramSession previous, HologramSession target) {
        var owner = target.player.getUniqueID();
        if (find(owner) != previous || HologramSessionPolicy.currentTool(target) == null) return false;
        // Capabilities remain private until the new task actually admits a job.
        SESSIONS.put(owner, target);
        return true;
    }

    /**
     * Atomic on the existing server owner thread. Do not use callback-capable held/world getters between
     * verifying the exact former seat (including revisions) and replacing it. This is not a presentation switch.
     */
    static boolean stockNativePrivate(EntityPlayerMP player) {
        HologramSession current = find(player.getUniqueID());
        return current != null && current.player == player && current.stockNativePrivate;
    }

    static boolean reserveStockNative(ActionEndpoint endpoint, HologramSession previous, long epoch, long plan,
        HologramSession target) {
        if (constructionGate(endpoint) == null || target == null
            || !target.stockNativePrivate
            || target.player != endpoint.player
            || target.libraryWorld != endpoint.world
            || target.player.isDead
            || !target.player.capabilities.allowEdit) return false;
        var inventory = target.player.inventory;
        var slots = inventory.mainInventory;
        int slot = target.slot;
        if (slot != inventory.currentItem || slot < 0
            || slot >= slots.length
            || slots[slot] != target.boundTool
            || target.boundTool == null
            || !target.boundTool.hasTagCompound()
            || !target.toolId.equals(
                target.boundTool.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID)))
            return false;
        if (SESSIONS.get(endpoint.actor) != previous
            || previous != null && (previous.editorWorkspaceEpoch != epoch || previous.planRevision != plan))
            return false;
        SESSIONS.put(endpoint.actor, target);
        return true;
    }

    static void rollbackCompactNative(HologramSession previous, HologramSession target) {
        var owner = target.player.getUniqueID();
        if (find(owner) != target) return;
        if (previous != null && previous.player == target.player
            && previous.libraryWorld == target.player.worldObj
            && !target.player.isDead) SESSIONS.put(owner, previous);
        else SESSIONS.remove(owner, target);
    }

    static void commitCompactNative(HologramSession previous, HologramSession target) {
        if (find(target.player.getUniqueID()) != target) return;
        if (previous != null) HologramEditorWorkspaceRegistry.remember(previous);
        HologramEditorWorkspaceRegistry.activated(target);
    }

    static PresentationTransition preparePresentation(HologramSession origin, HologramSession target) {
        return find(origin.player.getUniqueID()) == origin && presentationAllowed(origin)
            && target.player == origin.player
            && target.libraryWorld == origin.libraryWorld
            && target.dimension == origin.dimension
            && target.tool == origin.tool
            && target.slot == origin.slot
            && target.toolId.equals(origin.toolId)
            && HologramSessionPolicy.currentTool(origin) != null ? new PresentationTransition(origin, target) : null;
    }

    /** Suspend, rather than mark a half-built original plan complete. No inventory or geometry is traversed. */
    static boolean activatePresentation(PresentationTransition transition) {
        HologramSession origin = transition.origin, target = transition.target;
        if (find(origin.player.getUniqueID()) != origin || !presentationAllowed(origin)
            || !origin.id.equals(transition.originId)
            || origin.uiSequence != transition.sequence) return false;
        HologramNativeReadyPlacement.cancel(origin);
        HologramDeferredPlanRefresh.suspend(origin);
        origin.presentationTransition = null;
        target.uiSequence = transition.sequence;
        target.presentationTransition = transition;
        SESSIONS.put(origin.player.getUniqueID(), target);
        HologramEditorWorkspaceRegistry.remember(origin);
        HologramEditorWorkspaceRegistry.activated(target);
        return true;
    }

    static void commitPresentation(HologramSession target, net.minecraft.nbt.NBTTagCompound baseline) {
        if (target == null || target.presentationTransition == null
            || !baseline.hasKey("cells", 9)
            || !baseline.hasKey("editorWorkspaceEpoch", 4)
            || baseline.getLong("editorWorkspaceEpoch") != target.editorWorkspaceEpoch
            || baseline.getBoolean("partial")) return;
        PresentationTransition transition = target.presentationTransition;
        if (transition.matchesTarget(target.player, baseline.getString("session"), baseline.getLong("uiSequence"))) {
            target.presentationTransition = null;
            if (transition.discardProjection) HologramEditorWorkspaceRegistry.discardProjection(transition.origin);
        }
    }

    /** Only a still-current, unpublished display switch may restore the exact former server route. */
    static HologramSession rollbackPresentation(EntityPlayerMP player, String session, long sequence) {
        HologramSession target = find(player.getUniqueID());
        PresentationTransition transition = target == null ? null : target.presentationTransition;
        if (transition == null || !transition.matchesTarget(player, session, sequence)
            || !presentationAllowed(target)
            || !presentationAllowed(transition.origin)) return null;
        if (HologramEditorWorkspaceRegistry.physicalProjection(transition.origin)
            && HologramSessionPolicy.controllerReplaced(transition.origin)) {
            pruneRemovedController(player);
            return null;
        }
        HologramNetwork.retirePresentationState(target);
        HologramSnapshotPreparation.cancel(target);
        target.presentationTransition = null;
        HologramSession origin = transition.origin;
        activate(player.getUniqueID(), origin);
        // The target never published a baseline. The client still owns the exact
        // origin snapshot and its action epoch, so rollback restores that capability.
        origin.editorWorkspaceEpoch = transition.originEditorEpoch;
        ItemStack held = HologramSessionPolicy.currentTool(origin);
        if (transition.hadSelection) held.getTagCompound()
            .setString(HologramSessionKeys.SELECTED_PRESET, transition.selected);
        else held.getTagCompound()
            .removeTag(HologramSessionKeys.SELECTED_PRESET);
        player.inventory.markDirty();
        return origin;
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
        ACTION_ENDPOINTS.clear();
        stopConstructionRuntime();
        HologramDemolition.clear();
        HologramEditorWorkspaceRegistry.clear();
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
        HologramConstructionTaskGate gate = constructionRuntime;
        if (gate != null && gate.onOwnerThread()) gate.stopActiveJobs();
        HologramDemolition.clear();
        HologramEditorWorkspaceRegistry.clear();
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
            // Its exact StockNativeServer/permit owns settlement and receipt. Never publish or change it here.
            if (s.stockNativePrivate) continue;
            HologramNativeReadyPlacement.cancel(s);
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
            if (session.player.worldObj != session.libraryWorld || session.player.dimension != session.dimension
                || session.player.isDead
                || !areaSessionOwned(session)) HologramEditorWorkspaceRegistry.forget(session.player.getUniqueID());
            if (session.player.playerNetServerHandler == null
                || session.player.playerNetServerHandler.netManager == null
                || !session.player.playerNetServerHandler.netManager.isChannelOpen()) {
                HologramSession.clearPending(session);
                HologramSession.cancelCaptureRetry(session);
                HologramDeferredPlanRefresh.cancel(session);
                clearAreaSelection(session);
                HologramEditorWorkspaceRegistry.forget(session.player.getUniqueID());
                sessions.remove();
            } else tick.accept(session);
        }
    }

}
