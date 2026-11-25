// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/** Both handlers are common-side classes; the client installs its own queued receiver. */
public final class HologramNetwork {

    private static SimpleNetworkWrapper channel;
    private static volatile Consumer<NBTTagCompound> receiver;
    private static volatile Consumer<NBTTagCompound> interactionSender;
    private static volatile java.util.function.BiConsumer<NBTTagCompound, Object> libraryReceiver;
    private static volatile Consumer<NBTTagCompound> libraryProbeReceiver;
    private static volatile java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.INetHandler> cutReceiver;
    private static volatile Consumer<NBTTagCompound> progressReceiver;
    private static final AtomicLong progressSequence = new AtomicLong();
    private static final Object cutTransportLock = new Object();
    private static long cutReplySequence = -1;
    private static net.minecraft.network.INetHandler cutInbound;
    private static final AtomicLong cutClientGeneration = new AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor cutDecoder = new java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(4),
        task -> {
            Thread thread = new Thread(task, "GTMB cut snapshot decoder");
            thread.setDaemon(true);
            return thread;
        });
    private static final Map<UUID, CutOutbox> cutOutgoing = new LinkedHashMap<>();
    private static final int MAX_BYTES = 1048576;
    private static final AtomicLong snapshotSequence = new AtomicLong();
    private static final Map<UUID, Outbox> outgoing = new LinkedHashMap<>();
    private static final HologramLibrarySnapshotAssembly librarySnapshots = new HologramLibrarySnapshotAssembly();
    private static int publicSnapshotCursor;
    private static final HologramRequestGate actionGate = new HologramRequestGate(16, 256);
    private static final HologramRequestGate receiptGate = new HologramRequestGate(4, 128);
    private static final HologramRequestGate snapshotControlGate = new HologramRequestGate(2, 256);
    private static final HologramRequestGate retiredPlacementGate = new HologramRequestGate(1, 32);
    private static final HologramRequestGate projectedLayerProbeGate = new HologramRequestGate(1, 32);
    private static final AtomicLong constructionIntentSequence = new AtomicLong();
    private static final long PROJECTED_LAYER_TICKET_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final int MAX_PROJECTED_LAYER_TICKETS = 256;
    private static final String LAYER_VERSION = "ordinaryLayerProbe", LAYER_REQUEST = "ordinaryLayerRequest";
    private static final String LAYER_INTENT = "ordinaryLayerIntent", LAYER_SCOPE = "ordinaryLayerGeneration";
    private static final Map<UUID, ProjectedLayerTicket> projectedLayerTickets = new LinkedHashMap<>();
    private static HologramProjectedLayerServer projectedLayerServer;
    private static volatile ProjectedLayerNegotiation projectedLayerNegotiation;
    private static final HologramStockNativeServer stockNativeServer =
        new HologramStockNativeServer(HologramNetwork::sendStockNativeReply);
    private static volatile StockNativeReplies stockNativeReplies;
    private static final String RETIRED_PLACEMENT_REPLY = "retiredWholePlacement";
    private static final String RETIRED_PLACEMENT_REASON = "旧版整份施工上传已停用，未开始施工。请使用两端支持的新版施工入口。";
    private static final String RETIRED_MATERIAL_CHECK_REASON = "旧版服务器材料核对已停用；请在本地查看材料清单。";
    private static final Map<UUID, SnapshotFlow> snapshotFlows = new LinkedHashMap<>();
    private static final AtomicLong snapshotFlowGeneration = new AtomicLong();

    private HologramNetwork() {}

    static void controllerOpenUnavailable(EntityPlayerMP player, long id) {
        if (channel == null || !progressConnected(player)) return;
        NBTTagCompound state = new NBTTagCompound();
        state.setLong("controllerOpenUnavailable", id);
        channel.sendTo(new State(state), player);
    }

    static void clearServerQueues() {
        stockNativeServer.reset();
        HologramAttachmentUpload.clearServer();
        retiredPlacementGate.clear();
        projectedLayerProbeGate.clear();
        // On an in-process reset keep the intent watermark; an old already-issued ticket is no longer usable.
        for (ProjectedLayerTicket ticket : projectedLayerTickets.values()) ticket.claimed = true;
        actionGate.clear();
        receiptGate.clear();
        snapshotControlGate.clear();
        snapshotFlows.clear();
        for (Outbox box : outgoing.values()) retire(box);
        outgoing.clear();
        HologramSnapshotEncoding.clear();
        HologramSnapshotPreparation.clear();
        cutOutgoing.clear();
        HologramBusyReplies.clear();
    }

    /** A reset must retire the client even when the snapshot encoder or its outbox is stalled. */
    static void sendSessionCancelled(EntityPlayerMP player, String session, String reason) {
        if (channel == null || !progressConnected(player)) return;
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", session);
        state.setBoolean("cancelled", true);
        state.setString("status", reason);
        channel.sendTo(new State(state), player);
    }

    static void sendControllerProjectionRetired(HologramSession projection, String reason) {
        if (channel == null || !progressConnected(projection.player)) return;
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", projection.id);
        state.setBoolean("cancelled", true);
        state.setBoolean("controllerProjectionRetired", true);
        state.setLong("controllerOpenId", projection.controllerOpenId);
        state.setString("status", reason);
        channel.sendTo(new State(state), projection.player);
    }

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("gtmb_hologram");
        channel.registerMessage(ServerHandler.class, Action.class, 0, Side.SERVER);
        channel.registerMessage(ClientHandler.class, State.class, 1, Side.CLIENT);
        channel.registerMessage(FragmentHandler.class, Fragment.class, 2, Side.CLIENT);
        channel.registerMessage(LibraryServerHandler.class, LibraryRequest.class, 3, Side.SERVER);
        channel.registerMessage(LibraryClientHandler.class, LibraryReply.class, 4, Side.CLIENT);
        channel.registerMessage(LibrarySnapshotHandler.class, LibrarySnapshotFragment.class, 5, Side.CLIENT);
        channel.registerMessage(CutServerHandler.class, CutAction.class, 6, Side.SERVER);
        channel.registerMessage(CutFragmentHandler.class, CutFragment.class, 7, Side.CLIENT);
        channel.registerMessage(
            HologramAttachmentUpload.Handler.class,
            HologramAttachmentUpload.Fragment.class,
            8,
            Side.SERVER);
        channel.registerMessage(
            HologramAttachmentUpload.AckHandler.class,
            HologramAttachmentUpload.Ack.class,
            9,
            Side.CLIENT);
        channel.registerMessage(
            HologramPlacementUpload.Handler.class,
            HologramPlacementUpload.RetiredFrame.class,
            10,
            Side.SERVER);
        channel.registerMessage(
            HologramPlacementUpload.AckHandler.class,
            HologramPlacementUpload.Ack.class,
            11,
            Side.CLIENT);
        channel.registerMessage(
            HologramProjectedLayerTransport.ServerHandler.class,
            HologramProjectedLayerTransport.Request.class,
            12,
            Side.SERVER);
        channel.registerMessage(
            HologramProjectedLayerTransport.ClientHandler.class,
            HologramProjectedLayerTransport.Reply.class,
            13,
            Side.CLIENT);
        HologramAttachmentUpload.init(channel);
        HologramLibraryFacade.init();
        FMLCommonHandler.instance()
            .bus()
            .register(new TransportTicks());
    }

    @FunctionalInterface
    public interface ProjectedLayerNegotiation {

        void accept(UUID request, long intent, int version, long scope, Object inboundHandler);
    }

    /** Installed on normal client init only; opening a preview never sends or negotiates. */
    public static void setProjectedLayerNegotiation(ProjectedLayerNegotiation receiver) {
        projectedLayerNegotiation = receiver;
    }

    public static long nextConstructionIntentSequence() {
        long previous;
        do {
            previous = constructionIntentSequence.get();
            if (previous == Long.MAX_VALUE) return 0;
        } while (!constructionIntentSequence.compareAndSet(previous, previous + 1));
        return previous + 1;
    }

    /** A confirmed attempt may ask a legacy peer safely, without trying an unknown message discriminator. */
    public static boolean sendProjectedLayerProbe(UUID request, long intent) {
        SimpleNetworkWrapper wire = channel;
        if (wire == null || !nonzero(request) || intent < 1) return false;
        Action probe = new Action(); // No snapshot capability flags, local geometry, or workspace identifiers.
        probe.tag.setString("action", "serverRules");
        probe.tag.setInteger(LAYER_VERSION, HologramProjectedLayerTransport.VERSION);
        probe.tag.setString(LAYER_REQUEST, request.toString());
        probe.tag.setLong(LAYER_INTENT, intent);
        wire.sendToServer(probe);
        return true;
    }

    /** Only the client's matching negotiation receipt should unlock a typed request; exceptions remain uncertain. */
    public static boolean sendProjectedLayerRequest(HologramProjectedLayerTransport.Request request) {
        SimpleNetworkWrapper wire = channel;
        if (wire == null || request == null) return false;
        wire.sendToServer(request);
        return true;
    }

    @FunctionalInterface
    public interface StockNativeReplies {

        void accept(HologramStockNativeWire.Reply reply, Object inboundHandler);
    }

    /** Client installs an exact owner receiver later. Neither local opening nor material inspection calls a writer. */
    public static void setStockNativeReplies(StockNativeReplies receiver) {
        stockNativeReplies = receiver;
    }

    public static boolean sendStockNativeConfirmation(HologramStockNativeWire.Confirmation confirmation) {
        return sendStockNativeAction(HologramStockNativeWire.confirm(confirmation));
    }

    public static boolean sendStockNativeProbe(HologramStockNativeWire.Confirmation confirmation) {
        return sendStockNativeAction(HologramStockNativeWire.probe(confirmation));
    }

    public static boolean sendStockNativeAfterProbe(HologramStockNativeWire.Confirmation confirmation) {
        return sendStockNativeAction(HologramStockNativeWire.confirmAfterProbe(confirmation));
    }

    public static boolean sendStockNativeControl(HologramStockNativeWire.Key key, HologramStockNativeWire.Command command) {
        return sendStockNativeAction(HologramStockNativeWire.control(key, command));
    }

    private static boolean sendStockNativeAction(NBTTagCompound tag) {
        SimpleNetworkWrapper wire = channel;
        if (wire == null) return false;
        Action packet = new Action();
        packet.tag = tag;
        wire.sendToServer(packet);
        return true;
    }

    private static void sendStockNativeReply(HologramWorkspace.ActionEndpoint endpoint,
        HologramStockNativeWire.Reply reply) {
        SimpleNetworkWrapper wire = channel;
        if (wire != null && HologramWorkspace.currentActionEndpoint(endpoint))
            wire.sendTo(new State(HologramStockNativeWire.writeReply(reply)), endpoint.player);
    }

    static void tickStockNativeServer(long deadline) {
        stockNativeServer.tick(deadline);
    }

    static boolean stockNativeOwns(HologramSession session) {
        return stockNativeServer.owns(session);
    }

    private static boolean nonzero(UUID request) {
        return request != null && (request.getMostSignificantBits() != 0 || request.getLeastSignificantBits() != 0);
    }

    private static UUID projectedLayerIdentity(NBTTagCompound tag, boolean reply) {
        if (tag.func_150296_c().size() != (reply ? 5 : 4)
            || !(reply ? tag.hasKey("serverRulesOnly", 1) && tag.getBoolean("serverRulesOnly")
                : tag.hasKey("action", 8) && "serverRules".equals(tag.getString("action")))
            || !tag.hasKey(LAYER_VERSION, 3)
            || tag.getInteger(LAYER_VERSION) != HologramProjectedLayerTransport.VERSION
            || !tag.hasKey(LAYER_REQUEST, 8) || !tag.hasKey(LAYER_INTENT, 4) || tag.getLong(LAYER_INTENT) < 1
            || reply && (!tag.hasKey(LAYER_SCOPE, 4) || tag.getLong(LAYER_SCOPE) < 1)) return null;
        String id = tag.getString(LAYER_REQUEST);
        if (!HologramNativeCompactStart.canonical(id)) return null;
        UUID request = UUID.fromString(id);
        return nonzero(request) ? request : null;
    }

    /** Current authenticated connection's last attempted proof; kept through expiry/consumption to prevent remint. */
    private static final class ProjectedLayerTicket {

        final WeakReference<NetHandlerPlayServer> sender;
        final HologramProjectedLayerTransport.Key key;
        final long issuedAt;
        final ProjectedLayerTool tool;
        boolean claimed;

        ProjectedLayerTicket(HologramWorkspace.ActionEndpoint endpoint, HologramProjectedLayerTransport.Key key,
            ProjectedLayerTool tool, long now) {
            sender = new WeakReference<>(endpoint.connection);
            this.key = key;
            issuedAt = now;
            this.tool = tool;
        }

        boolean usable(long now) {
            return tool != null && !claimed && now - issuedAt < PROJECTED_LAYER_TICKET_NANOS;
        }
    }

    /** Exact server-side held projector at authenticated ingress; no copy or traversal of tool NBT. */
    private static final class ProjectedLayerTool {

        final int slot;
        final Item item;
        final String toolId;
        final WeakReference<ItemStack> original;

        private ProjectedLayerTool(int slot, ItemStack held, String id) {
            this.slot = slot;
            item = held.getItem();
            toolId = id;
            original = id.isEmpty() ? new WeakReference<>(held) : null;
        }

        static ProjectedLayerTool capture(EntityPlayerMP player) {
            int slot = player.inventory.currentItem;
            ItemStack held = player.getHeldItem();
            if (!ItemNekoHologramProjector.isProjector(held)) return null;
            String id = projectedToolId(held);
            return id.isEmpty() || HologramNativeCompactStart.canonical(id)
                ? new ProjectedLayerTool(slot, held, id) : null;
        }

        boolean current(EntityPlayerMP player) {
            ItemStack held = player.getHeldItem();
            return held != null && player.inventory.currentItem == slot && held.getItem() == item
                && toolId.equals(projectedToolId(held)) && (original == null || original.get() == held);
        }
    }

    private static String projectedToolId(ItemStack held) {
        return held.hasTagCompound() ? held.getTagCompound().getString(HologramSessionKeys.TOOL_ID) : "";
    }

    private static void queueProjectedLayerProbe(NBTTagCompound tag, MessageContext context) {
        UUID request = projectedLayerIdentity(tag, false);
        if (request == null || context == null) return;
        HologramWorkspace.ActionEndpoint endpoint =
            HologramWorkspace.captureActionEndpoint(context.getServerHandler());
        if (endpoint == null || endpoint.constructionScope == null) return;
        ProjectedLayerTool submitted = ProjectedLayerTool.capture(endpoint.player);
        if (submitted == null) return;
        HologramRequestGate.Lease lease = projectedLayerProbeGate.acquire(endpoint.connection);
        if (lease == null) return;
        long intent = tag.getLong(LAYER_INTENT);
        ServerTaskScheduler.scheduleServerTask(() -> runQueuedAction(endpoint, lease,
            () -> issueProjectedLayerTicket(endpoint, request, intent, submitted)));
    }

    private static void issueProjectedLayerTicket(HologramWorkspace.ActionEndpoint endpoint, UUID request,
        long intent, ProjectedLayerTool submitted) {
        SimpleNetworkWrapper wire = channel;
        HologramConstructionTaskGate gate = HologramWorkspace.constructionGate(endpoint);
        EntityPlayerMP player = endpoint.player;
        if (wire == null || gate == null) return;
        boolean eligible = !player.isDead && player.capabilities.allowEdit && submitted.current(player);
        var key = new HologramProjectedLayerTransport.Key(request, intent, endpoint.constructionScope.generation);
        if (gate.query(endpoint.constructionScope, key.gateRequest()).phase != HologramConstructionTaskGate.Phase.UNSEEN)
            return;
        long now = System.nanoTime();
        ProjectedLayerTicket previous = projectedLayerTickets.get(endpoint.actor);
        if (previous != null && previous.sender.get() == endpoint.connection && intent <= previous.key.intent) {
            if (!previous.key.equals(key) || !previous.usable(now)) return;
            if (!eligible || !previous.tool.current(player)) {
                previous.claimed = true;
                return;
            }
            // A lost capability receipt may be retried, but it never renews the same proof or transfers it to a new scope.
        } else {
            if (previous == null && projectedLayerTickets.size() >= MAX_PROJECTED_LAYER_TICKETS) {
                pruneProjectedLayerTickets();
                if (projectedLayerTickets.size() >= MAX_PROJECTED_LAYER_TICKETS) return;
            }
            // A refused ingress advances only its replay watermark, with no usable ticket or positive reply.
            projectedLayerTickets.put(endpoint.actor, new ProjectedLayerTicket(endpoint, key, eligible ? submitted : null, now));
            if (!eligible) return;
        }
        if (projectedLayerServer == null) {
            projectedLayerServer = new HologramProjectedLayerServer(
                HologramNetwork::sendProjectedLayerReply, HologramNetwork::consumeProjectedLayerTicket);
            HologramProjectedLayerTransport.installServer(projectedLayerServer);
        }
        NBTTagCompound proof = new NBTTagCompound();
        proof.setBoolean("serverRulesOnly", true); // Old clients discard it without requesting new geometry.
        proof.setInteger(LAYER_VERSION, HologramProjectedLayerTransport.VERSION);
        proof.setString(LAYER_REQUEST, request.toString());
        proof.setLong(LAYER_INTENT, intent);
        proof.setLong(LAYER_SCOPE, endpoint.constructionScope.generation);
        wire.sendTo(new State(proof), player);
    }

    private static boolean consumeProjectedLayerTicket(HologramWorkspace.ActionEndpoint endpoint,
        HologramProjectedLayerTransport.Key key) {
        if (HologramWorkspace.constructionGate(endpoint) == null) return false;
        ProjectedLayerTicket ticket = projectedLayerTickets.get(endpoint.actor);
        if (ticket == null || ticket.sender.get() != endpoint.connection || !ticket.key.equals(key)
            || !ticket.usable(System.nanoTime())) return false;
        ticket.claimed = true; // Even a now-incompatible exact attempt must not start after a later tool swap.
        return ticket.tool.current(endpoint.player);
    }

    private static void sendProjectedLayerReply(HologramWorkspace.ActionEndpoint endpoint,
        HologramProjectedLayerTransport.Reply reply) {
        SimpleNetworkWrapper wire = channel;
        if (wire != null && HologramWorkspace.currentActionEndpoint(endpoint)) wire.sendTo(reply, endpoint.player);
    }

    private static void pruneProjectedLayerTickets() {
        projectedLayerTickets.values().removeIf(ticket -> {
            NetHandlerPlayServer sender = ticket.sender.get();
            return sender == null || sender.netManager == null || !sender.netManager.isChannelOpen();
        });
    }

    static void tickProjectedLayerServer(long deadline) {
        HologramProjectedLayerServer server = projectedLayerServer;
        if (server != null) server.tick(deadline);
    }

    /** Normal logical server stop, not a client event. Pending permits remain held by the shared gate. */
    public static void closeProjectedLayerServer() {
        HologramProjectedLayerTransport.installServer(null);
        HologramProjectedLayerServer server = projectedLayerServer;
        projectedLayerServer = null;
        projectedLayerProbeGate.clear();
        projectedLayerTickets.clear();
        if (server != null) server.close();
    }

    public static void setClientReceiver(Consumer<NBTTagCompound> value) {
        receiver = value;
        librarySnapshots.reset();
    }

    public static void clearLibraryClientTransport() {
        librarySnapshots.reset();
        HologramAttachmentUpload.clearClient();
    }

    public static void setClientInteractionSender(Consumer<NBTTagCompound> value) {
        interactionSender = value;
    }

    /** Read-only transport observations. Client installation owns main-thread/world/connection fencing. */
    public static void setProgressReceiver(Consumer<NBTTagCompound> value) {
        progressReceiver = value;
    }

    private static void observeProgress(NBTTagCompound progress) {
        Consumer<NBTTagCompound> current = progressReceiver;
        if (current != null) current.accept((NBTTagCompound) progress.copy());
    }

    private static void observeFragment(String scope, long sequence, int total, int index, int bytes, int chunkBytes) {
        if (progressReceiver == null) return;
        NBTTagCompound progress = new NBTTagCompound();
        progress.setString("scope", scope);
        progress.setString("phase", "download");
        progress.setLong("sequence", sequence);
        progress.setLong("done", Math.min(total, (long) index * chunkBytes + bytes));
        progress.setLong("total", total);
        progress.setString("unit", "bytes");
        progress.setBoolean("complete", (long) index * chunkBytes + bytes == total);
        observeProgress(progress);
    }

    /** Invoked only on the logical client; common item code never links keyboard/client classes. */
    public static void clientUse(int x, int y, int z, int side, boolean air) {
        Consumer<NBTTagCompound> sender = interactionSender;
        if (sender == null) return;
        NBTTagCompound input = new NBTTagCompound();
        input.setInteger("x", x);
        input.setInteger("y", y);
        input.setInteger("z", z);
        input.setInteger("side", side);
        input.setBoolean("air", air);
        sender.accept(input);
    }

    public static void sendAction(NBTTagCompound tag) {
        String action = tag.getString("action");
        if ("projectedPlacement".equals(action)) throw new IllegalArgumentException(RETIRED_PLACEMENT_REASON);
        if ("checkPlacementMaterials".equals(action)) throw new IllegalArgumentException(RETIRED_MATERIAL_CHECK_REASON);
        if (channel == null) return;
        if ("placeTextBox".equals(tag.getString("op"))) HologramAttachmentUpload.send(tag);
        else channel.sendToServer(new Action(tag));
    }

    /** Retained for source compatibility; never serializes a whole construction payload. */
    public static HologramPlacementUpload.Prepared prepareProjectedPlacement(NBTTagCompound tag,
        java.util.function.BooleanSupplier current) throws IOException {
        throw new IOException(RETIRED_PLACEMENT_REASON);
    }

    public static void sendPreparedPlacement(HologramPlacementUpload.Prepared prepared,
        java.util.function.BooleanSupplier current) throws IOException {
        throw new IOException(RETIRED_PLACEMENT_REASON);
    }

    public static void discardPreparedPlacement(java.util.UUID id) {}

    /** A legacy peer gets a small owned incompatibility; this path admits no edit, snapshot, transfer or worker. */
    static void refuseRetiredPlacement(HologramWorkspace.ActionEndpoint endpoint, String id) {
        refuseRetiredPlacement(endpoint, id, RETIRED_PLACEMENT_REASON);
    }

    private static void refuseRetiredPlacement(HologramWorkspace.ActionEndpoint endpoint, String id, String reason) {
        if (endpoint == null || !HologramNativeCompactStart.canonical(id)) return;
        HologramRequestGate.Lease lease = retiredPlacementGate.acquire(endpoint.connection);
        if (lease == null) return;
        ServerTaskScheduler.scheduleServerTask(() -> runQueuedAction(endpoint, lease, () -> {
            // Do not mark the ID consumed or end another generation's legitimate native task.
            if (HologramProjectedPlacementService.consumed(endpoint.player, id)) return;
            NBTTagCompound reply = new NBTTagCompound();
            reply.setString("placementRequestId", id);
            reply.setBoolean("placementRequestAccepted", false);
            reply.setString("errorMessage", reason);
            reply.setBoolean(RETIRED_PLACEMENT_REPLY, true);
            sendState(endpoint.player, reply);
        }));
    }

    static void clientActionFailure(NBTTagCompound action, String reason) {
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("transportError", true);
        state.setString("session", action.getString("session"));
        state.setLong("uiSequence", action.getLong("uiSequence"));
        state.setString("errorCode", "attachment.upload");
        state.setString("errorMessage", reason == null ? "附加数据上传失败，请重新确认" : reason);
        Consumer<NBTTagCompound> client = receiver;
        if (client != null) client.accept(state);
    }

    /** Called on the server thread after complete bounded decoding; late uploads cannot revive cancelled work. */
    static void receiveUploadedAction(EntityPlayerMP player, net.minecraft.world.World world, NBTTagCompound tag) {
        HologramSession current = HologramWorkspace.find(player.getUniqueID());
        if (current == null || current.player != player
            || !current.id.equals(tag.getString("session"))
            || tag.getLong("uiSequence") < current.uiSequence) return;
        HologramRequestGate.Lease lease = actionGate.acquire(player);
        if (lease == null) {
            HologramBusyReplies.reject(player, tag, HologramBusyReplies.Kind.ACTION);
            return;
        }
        runQueuedAction(player, world, lease, () -> dispatch(player, tag));
    }

    public static void setCutReceiver(java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.INetHandler> value) {
        synchronized (cutTransportLock) {
            cutReceiver = value;
            cutReplySequence = -1;
            cutInbound = null;
            cutClientGeneration.incrementAndGet();
            cutDecoder.getQueue()
                .clear();
            HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH, 0);
        }
    }

    public static void clearAttachmentClientTransport() {
        HologramAttachmentUpload.clearClient();
    }

    public static void clearCutClientTransport() {
        synchronized (cutTransportLock) {
            cutReplySequence = -1;
            cutInbound = null;
            cutClientGeneration.incrementAndGet();
            cutDecoder.getQueue()
                .clear();
            HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH, 0);
        }
    }

    public static void sendCutAction(NBTTagCompound tag) {
        if (channel != null) channel.sendToServer(new CutAction(tag));
    }

    static void sendCutState(HologramWorkspace.ActionEndpoint endpoint, NBTTagCompound tag) {
        if (channel == null || !HologramWorkspace.currentActionEndpoint(endpoint)) return;
        try {
            byte[] bytes = HologramCutReplyCodec.encode(tag);
            CutOutbox box = cutOutgoing.get(endpoint.actor);
            if (box == null || box.endpoint != endpoint) {
                box = new CutOutbox(endpoint);
                cutOutgoing.put(endpoint.actor, box);
            }
            if (box.pendingBytes + bytes.length > HologramSnapshotAssembly.MAX_BYTES)
                throw new IOException("cut transport queue full");
            box.snapshots.addLast(new CutSnapshot(snapshotSequence.incrementAndGet(), bytes));
            box.pendingBytes += bytes.length;
        } catch (IOException failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut metadata reply could not be queued", failure);
        }
    }

    private static final class CutOutbox {

        final HologramWorkspace.ActionEndpoint endpoint;
        final Deque<CutSnapshot> snapshots = new ArrayDeque<>();
        long pendingBytes;

        CutOutbox(HologramWorkspace.ActionEndpoint endpoint) {
            this.endpoint = endpoint;
        }
    }

    private static final class CutSnapshot {

        final long sequence;
        final byte[] bytes;

        CutSnapshot(long sequence, byte[] bytes) {
            this.sequence = sequence;
            this.bytes = bytes;
        }
    }

    /** A small, versioned request budget, separate from blueprint uploads and snapshots. */
    public static final class CutAction implements IMessage {

        NBTTagCompound tag = new NBTTagCompound();

        public CutAction() {}

        CutAction(NBTTagCompound tag) {
            this.tag = (NBTTagCompound) tag.copy();
        }

        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() < 8 || buf.readInt() != HologramCutProtocol.VERSION)
                throw new IllegalArgumentException("cut request version");
            int length = buf.readInt();
            if (length <= 0 || length > HologramCutProtocol.REQUEST_BYTES || length != buf.readableBytes())
                throw new IllegalArgumentException("cut request budget");
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            try {
                tag = HologramSnapshotCodec
                    .decode(bytes, HologramCutProtocol.REQUEST_BYTES, 4L * HologramCutProtocol.REQUEST_BYTES);
                HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.CUT_ACTION, true, false, bytes.length + 8);
            } catch (IOException failure) {
                throw new IllegalArgumentException("cut NBT", failure);
            }
        }

        public void toBytes(ByteBuf buf) {
            try {
                byte[] bytes = CompressedStreamTools.compress(tag);
                if (bytes.length > HologramCutProtocol.REQUEST_BYTES)
                    throw new IllegalArgumentException("cut request budget");
                buf.writeInt(HologramCutProtocol.VERSION);
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
                HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.CUT_ACTION, true, true, bytes.length + 8);
            } catch (IOException failure) {
                throw new IllegalArgumentException("cut NBT", failure);
            }
        }
    }

    public static final class CutServerHandler implements IMessageHandler<CutAction, IMessage> {

        public IMessage onMessage(CutAction message, MessageContext context) {
            HologramCutService.receive(
                HologramWorkspace.captureActionEndpoint(context.getServerHandler()), message.tag);
            return null;
        }
    }

    /** One versioned metadata reply, still under the original discriminator and header layout. */
    public static final class CutFragment implements IMessage {

        private long sequence;
        private byte[] bytes;

        public CutFragment() {}

        CutFragment(long serial, int total, int index, byte[] bytes) {
            if (index != 0 || total != bytes.length || !HologramCutReplyCodec.fitsFrame(total))
                throw new IllegalArgumentException("Cut metadata frame budget");
            sequence = HologramCutReplyCodec.markSequence(serial);
            this.bytes = bytes;
        }

        boolean admitted() {
            return bytes != null;
        }

        NBTTagCompound decodeReply() throws IOException {
            if (!admitted()) throw new IOException("No admitted Cut metadata frame");
            return HologramCutReplyCodec.decode(bytes);
        }

        public void fromBytes(ByteBuf buf) {
            bytes = null;
            if (buf.readableBytes() >= HologramCutReplyCodec.HEADER_BYTES) {
                long next = buf.readLong();
                int total = buf.readInt(), index = buf.readInt(), length = buf.readInt();
                if (HologramCutReplyCodec.marked(next) && index == 0 && total == length
                    && HologramCutReplyCodec.fitsFrame(length) && length == buf.readableBytes()) {
                    sequence = next;
                    bytes = new byte[length];
                    buf.readBytes(bytes);
                    HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.CUT_FRAGMENT, false, false,
                        bytes.length + HologramCutReplyCodec.HEADER_BYTES);
                    return;
                }
            }
            // Unsupported old format: no payload array, assembly, decoder job or guessed request result.
            buf.skipBytes(buf.readableBytes());
        }

        public void toBytes(ByteBuf buf) {
            if (!admitted()) throw new IllegalStateException("No Cut metadata reply");
            buf.writeLong(sequence);
            buf.writeInt(bytes.length);
            buf.writeInt(0);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.CUT_FRAGMENT, false, true,
                bytes.length + HologramCutReplyCodec.HEADER_BYTES);
        }
    }

    public static final class CutFragmentHandler implements IMessageHandler<CutFragment, IMessage> {

        public IMessage onMessage(CutFragment message, MessageContext context) {
            if (!message.admitted()) return null;
            synchronized (cutTransportLock) {
                net.minecraft.network.INetHandler inbound = context.netHandler;
                if (inbound == null) return null;
                if (cutInbound != inbound) {
                    // No fragment or decoded reply may be adopted into another play handler's assembly.
                    cutInbound = inbound;
                    cutReplySequence = -1;
                    cutClientGeneration.incrementAndGet();
                    cutDecoder.getQueue().clear();
                }
                if (message.sequence <= cutReplySequence) return null;
                cutReplySequence = message.sequence;
                observeFragment("cut", message.sequence, message.bytes.length, 0, message.bytes.length,
                    HologramCutReplyCodec.MAX_COMPRESSED_BYTES);
                java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.INetHandler> current = cutReceiver;
                long generation = cutClientGeneration.get();
                if (current != null) try {
                    cutDecoder.execute(() -> {
                        try {
                            NBTTagCompound state = message.decodeReply();
                            synchronized (cutTransportLock) {
                                if (generation == cutClientGeneration.get() && current == cutReceiver)
                                    current.accept(state, inbound);
                            }
                        } catch (IOException failure) {
                            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut metadata reply rejected", failure);
                        } finally {
                            HologramRuntimeMetrics.gauge(
                                HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH,
                                cutDecoder.getQueue()
                                    .size());
                        }
                    });
                    HologramRuntimeMetrics.gauge(
                        HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH,
                        cutDecoder.getQueue()
                            .size());
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.CUT_DECODER_REJECTED, 1);
                    com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut metadata decoder queue is full", full);
                }
            }
            return null;
        }
    }

    public static void setLibraryReceiver(java.util.function.BiConsumer<NBTTagCompound, Object> value) {
        libraryReceiver = value;
    }

    public static void setLibraryProbeReceiver(Consumer<NBTTagCompound> value) {
        if (Boolean.getBoolean("gtmb.hologram.librarySocketSmoke")) libraryProbeReceiver = value;
    }

    public static void sendLibrary(NBTTagCompound tag) {
        if (channel != null) channel.sendToServer(new LibraryRequest(tag));
    }

    static void sendLibraryReply(EntityPlayerMP player, NBTTagCompound tag) {
        if (Boolean.getBoolean("gtmb.hologram.librarySocketSmoke")) com.miaokatze.gtmb.main.OptionalTestLab
            .call("com.miaokatze.gtmb.hologram.HologramLibrarySocketServerProbe", "observe", player, tag);
        if (channel != null) channel.sendTo(new LibraryReply(tag), player);
    }

    /** Separate small packet budget. Untrusted byte-array lengths are checked before any allocation. */
    private abstract static class LibraryPacket implements IMessage {

        NBTTagCompound tag = new NBTTagCompound();

        LibraryPacket() {}

        LibraryPacket(NBTTagCompound tag) {
            this.tag = (NBTTagCompound) tag.copy();
        }

        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() < 8) throw new IllegalArgumentException("library header");
            if (buf.readInt() != HologramLibraryProtocol.VERSION)
                throw new IllegalArgumentException("Unsupported library protocol version");
            int length = buf.readInt();
            if (length <= 0 || length > HologramLibraryProtocol.PACKET_BYTES || length != buf.readableBytes())
                throw new IllegalArgumentException("library packet budget");
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            try {
                tag = HologramSnapshotCodec.decode(
                    bytes,
                    HologramLibraryProtocol.EXPANDED_PACKET_BYTES,
                    4L * HologramLibraryProtocol.EXPANDED_PACKET_BYTES);
                if (tag.getByteArray("data").length > HologramLibraryProtocol.CHUNK_BYTES)
                    throw new IllegalArgumentException("library chunk budget");
                HologramRuntimeMetrics.packet(
                    this instanceof LibraryRequest ? HologramRuntimeMetrics.Packet.LIBRARY_REQUEST
                        : HologramRuntimeMetrics.Packet.LIBRARY_REPLY,
                    this instanceof LibraryRequest,
                    false,
                    bytes.length + 8);
            } catch (IOException failure) {
                throw new IllegalArgumentException("library NBT", failure);
            }
        }

        public void toBytes(ByteBuf buf) {
            try {
                byte[] bytes = CompressedStreamTools.compress(tag);
                if (bytes.length > HologramLibraryProtocol.PACKET_BYTES)
                    throw new IllegalArgumentException("library packet budget");
                buf.writeInt(HologramLibraryProtocol.VERSION);
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
                HologramRuntimeMetrics.packet(
                    this instanceof LibraryRequest ? HologramRuntimeMetrics.Packet.LIBRARY_REQUEST
                        : HologramRuntimeMetrics.Packet.LIBRARY_REPLY,
                    this instanceof LibraryRequest,
                    true,
                    bytes.length + 8);
            } catch (IOException failure) {
                throw new IllegalArgumentException("library NBT", failure);
            }
        }
    }

    public static final class LibraryRequest extends LibraryPacket {

        public LibraryRequest() {}

        LibraryRequest(NBTTagCompound tag) {
            super(tag);
        }
    }

    public static final class LibraryReply extends LibraryPacket {

        public LibraryReply() {}

        LibraryReply(NBTTagCompound tag) {
            super(tag);
        }
    }

    public static final class LibraryServerHandler implements IMessageHandler<LibraryRequest, IMessage> {

        public IMessage onMessage(LibraryRequest message, MessageContext context) {
            HologramLibraryFacade.receive(context.getServerHandler().playerEntity, message.tag);
            return null;
        }
    }

    public static final class LibraryClientHandler implements IMessageHandler<LibraryReply, IMessage> {

        public IMessage onMessage(LibraryReply message, MessageContext context) {
            java.util.function.BiConsumer<NBTTagCompound, Object> current = libraryReceiver;
            if (current != null) current.accept(message.tag, context.netHandler);
            Consumer<NBTTagCompound> probe = libraryProbeReceiver;
            if (probe != null) probe.accept((NBTTagCompound) message.tag.copy());
            return null;
        }
    }

    public static void sendState(EntityPlayerMP player, NBTTagCompound tag) {
        if (channel == null) return;
        // Standalone replies have no workspace nonce and must not replace its snapshot outbox.
        if (tag.getBoolean("voucherEditor") || tag.hasKey("placementRequestId", 8)) {
            if (progressConnected(player)) channel.sendTo(new State(tag), player);
            return;
        }
        if (tag.getBoolean("progressOnly")) {
            if (!progressConnected(player)) return;
            NBTTagCompound event = (NBTTagCompound) tag.copy();
            event.getCompoundTag("progress")
                .setLong("sequence", progressSequence.incrementAndGet());
            channel.sendTo(new State(event), player);
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.PROGRESS_SENT, 1);
            if (event.getCompoundTag("progress")
                .getBoolean("jobDelta")) {
                HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.JOB_DELTA_PACKETS, 1);
                HologramRuntimeMetrics.add(
                    HologramRuntimeMetrics.Counter.JOB_DELTA_CELLS,
                    event.getCompoundTag("progress")
                        .getIntArray("ackIndices").length);
            }
            return;
        }
        if (tag.getBoolean("capturePending")) return;
        // Public callers retain ownership; the internal prepared-full path explicitly transfers it.
        Outbox current = outgoing.get(player.getUniqueID());
        if (current != null && current.owner != null
            && current.session.equals(tag.getString("session"))
            && tag.hasKey("planRevision", 4)
            && tag.getLong("planRevision") < current.owner.planRevision) return;
        SnapshotTicket ticket = reserveState(
            player,
            tag.getString("session"),
            tag.getBoolean("cancelled"),
            null,
            tag.hasKey("uiSequence", 4) ? tag.getLong("uiSequence") : Long.MIN_VALUE);
        if (ticket != null) completeOwnedState(ticket, (NBTTagCompound) tag.copy(), () -> true, () -> {});
    }

    /** Reserve FIFO position before building a full DTO, so later ACKs cannot cross its baseline. */
    static SnapshotTicket reserveState(EntityPlayerMP player, String session, boolean cancelled) {
        return reserveState(player, session, cancelled, null, Long.MIN_VALUE);
    }

    /** A staged Area editor can retain its wire session nonce while replacing the server owner object. */
    static SnapshotTicket reserveOwnedState(HologramSession owner) {
        if (HologramWorkspace.find(owner.player.getUniqueID()) != owner) return null;
        return reserveState(owner.player, owner.id, owner.cancelled, owner, owner.uiSequence);
    }

    private static SnapshotTicket reserveState(EntityPlayerMP player, String session, boolean cancelled,
        HologramSession owner, long uiSequence) {
        if (channel == null) return null;
        Outbox box = outgoing.get(player.getUniqueID());
        if (box == null || box.player != player
            || !box.flow.current(player)
            || !box.session.equals(session)
            || cancelled
            || owner != null && box.owner != owner) {
            if (box != null) retire(box);
            box = new Outbox(player, session, owner, snapshotFlow(player));
            outgoing.put(player.getUniqueID(), box);
        }
        int queued = 0;
        for (Outbox current : outgoing.values()) queued += current.snapshots.size();
        if (queued >= 32 || box.snapshots.size() >= 8) {
            outgoing.remove(player.getUniqueID());
            for (OutgoingSnapshot queuedSnapshot : box.snapshots) queuedSnapshot.failed = true;
            retire(box);
            transportFailure(
                player,
                session,
                uiSequence,
                "snapshot.queue",
                owner == null ? 0 : owner.blueprintClientTaskToken,
                new IllegalStateException("snapshot preparation queue full"));
            return null;
        }
        OutgoingSnapshot snapshot = new OutgoingSnapshot(snapshotSequence.incrementAndGet());
        box.snapshots.addLast(snapshot);
        return new SnapshotTicket(box, snapshot);
    }

    /** The caller relinquishes every mutable reference in owned until the main-thread ready callback. */
    static void completeOwnedState(SnapshotTicket ticket, NBTTagCompound owned,
        java.util.function.BooleanSupplier valid, Runnable ready) {
        if (!ticket.current()) return;
        if (owned.hasKey("progress", 10)) owned.getCompoundTag("progress")
            .setLong("sequence", progressSequence.incrementAndGet());
        OutgoingSnapshot previous = null;
        for (OutgoingSnapshot queued : ticket.box.snapshots) {
            if (queued == ticket.snapshot) break;
            previous = queued;
        }
        if (previous != null && previous.encoding == null
            && previous.owned != null
            && canCoalesceMetadata(previous.owned, owned)) {
            ticket.box.snapshots.remove(previous);
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.SNAPSHOT_COALESCED, 1);
        }
        ticket.snapshot.initialize(owned, valid, ready);
    }

    /** Full baselines, deltas and ACKs represent ordered effects and must never replace each other. */
    static boolean canCoalesceMetadata(NBTTagCompound previous, NBTTagCompound next) {
        return metadataOnly(previous) && metadataOnly(next)
            && previous.getString("session")
                .equals(next.getString("session"))
            && previous.getLong("uiSequence") == next.getLong("uiSequence")
            && previous.getLong("batchSeq") == next.getLong("batchSeq")
            && previous.getLong("planRevision") == next.getLong("planRevision")
            && previous.getString("phase")
                .equals(next.getString("phase"))
            && previous.getInteger("job") == next.getInteger("job");
    }

    private static boolean metadataOnly(NBTTagCompound state) {
        return !state.hasKey("cells", 9) && !"cellDelta".equals(state.getString("snapshotKind"))
            && !state.getBoolean("open")
            && !state.getBoolean("hints")
            && !state.getBoolean("settingsOnly")
            && !state.getBoolean("cancelled")
            && !state.getBoolean("libraryInitialSnapshot")
            && state.getIntArray("ackIndices").length == 0
            && state.getIntArray("ack").length == 0;
    }

    static void cancelState(SnapshotTicket ticket) {
        if (ticket != null && ticket.current()) {
            outgoing.remove(ticket.box.player.getUniqueID());
            retire(ticket.box);
        }
    }

    static final class SnapshotTicket {

        final Outbox box;
        final OutgoingSnapshot snapshot;

        SnapshotTicket(Outbox box, OutgoingSnapshot snapshot) {
            this.box = box;
            this.snapshot = snapshot;
        }

        boolean failed() {
            return snapshot.failed;
        }

        long sequence() {
            return snapshot.sequence;
        }

        long encodingBytes() {
            return snapshot.encoding == null ? 0 : snapshot.encoding.rawBytes;
        }

        boolean current() {
            return outgoing.get(box.player.getUniqueID()) == box && box.snapshots.contains(snapshot);
        }
    }

    private static void retire(Outbox box) {
        for (OutgoingSnapshot snapshot : box.snapshots) {
            if (snapshot.encoding != null) snapshot.encoding.cancel();
            snapshot.owned = null;
            snapshot.valid = null;
            snapshot.ready = null;
            snapshot.bytes = null;
            snapshot.libraryHash = null;
        }
        if (box.snapshots.peekFirst() != null && box.snapshots.peekFirst().libraryInitial)
            HologramLibraryFacade.cancelDownloadAllowance(HologramLibraryFacade.actorId(box.player));
        box.snapshots.clear();
    }

    /** Retire only this provisional target's transport before restoring its former display owner. */
    static void retirePresentationState(HologramSession target) {
        Outbox box = outgoing.get(target.player.getUniqueID());
        if (box != null && box.owner == target && box.player == target.player) {
            outgoing.remove(target.player.getUniqueID());
            retire(box);
        }
    }

    static void transportFailure(EntityPlayerMP player, String session, long uiSequence, String code,
        Exception failure) {
        transportFailure(player, session, uiSequence, code, 0, failure);
    }

    static void transportFailure(EntityPlayerMP player, String session, long uiSequence, String code, long clientTask,
        Exception failure) {
        HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.SNAPSHOT_DROPPED, 1);
        com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
            .warn("Hologram " + code + " failed for " + session + " uiSequence=" + uiSequence, failure);
        HologramSession restored = HologramWorkspace.rollbackPresentation(player, session, uiSequence);
        if (restored != null) {
            session = restored.id;
            uiSequence = restored.uiSequence;
            clientTask = 0;
        }
        if (channel != null && progressConnected(player)) {
            NBTTagCompound state = new NBTTagCompound();
            state.setBoolean("transportError", true);
            state.setString("session", session);
            HologramSession failed = HologramWorkspace.find(player.getUniqueID());
            if (failed != null && failed.id.equals(session)) state.setLong("controllerOpenId", failed.controllerOpenId);
            if (clientTask > 0) state.setLong("clientTaskToken", clientTask);
            if (uiSequence != Long.MIN_VALUE) state.setLong("uiSequence", uiSequence);
            state.setString("errorCode", code);
            state.setString("errorMessage", transportErrorMessage(code));
            channel.sendTo(new State(state), player);
        }
    }

    private static String transportErrorMessage(String code) {
        if ("capture.prepare".equals(code)) return "结构采集未完成；保留上次完整预览，请重新读取控制器。";
        if ("snapshot.prepare".equals(code)) return "Snapshot preparation failed; the previous preview was retained.";
        if ("snapshot.encode".equals(code)) return "Snapshot encoding failed; the previous preview was retained.";
        if ("snapshot.queue".equals(code)) return "Snapshot queue is full; the previous preview was retained.";
        return "Snapshot exceeds the transfer limit; the previous preview was retained.";
    }

    /** Existing transport tick consumes worker results; workers never execute callbacks. */
    private static void tickEncoding() {
        Iterator<Outbox> boxes = outgoing.values()
            .iterator();
        while (boxes.hasNext()) {
            Outbox box = boxes.next();
            if (box.player.worldObj != box.world || box.player.dimension != box.dimension
                || !progressConnected(box.player)) {
                retire(box);
                boxes.remove();
                continue;
            }
            OutgoingSnapshot snapshot = box.snapshots.peekFirst();
            if (snapshot == null || snapshot.bytes != null || snapshot.owned == null) continue;
            if (!snapshot.valid.getAsBoolean()) {
                retire(box);
                boxes.remove();
                continue;
            }
            long rawBytes = snapshot.encoding == null ? 0 : snapshot.encoding.rawBytes;
            if (rawBytes > snapshot.observedRawBytes) {
                snapshot.observedRawBytes = rawBytes;
                snapshot.advancedAt = System.nanoTime();
            }
            if (System.nanoTime() - snapshot.advancedAt > TimeUnit.SECONDS.toNanos(120)) {
                long failedTask = snapshot.owned.getLong("blueprintClientTaskToken");
                long failedUi = snapshot.owned.hasKey("uiSequence", 4) ? snapshot.owned.getLong("uiSequence")
                    : Long.MIN_VALUE;
                snapshot.failed = true;
                retire(box);
                boxes.remove();
                transportFailure(
                    box.player,
                    box.session,
                    failedUi,
                    "snapshot.encode",
                    failedTask,
                    new IOException("snapshot preparation or encoding timed out"));
                continue;
            }
            if (snapshot.encoding == null) {
                snapshot.encoding = HologramSnapshotEncoding
                    .submit(snapshot.owned, snapshot.sequence, box.owner != null && box.owner.snapshotPaletteProtocol);
                continue;
            }
            HologramSnapshotEncoding.Job encoding = snapshot.encoding;
            if (!encoding.done) continue;
            if (encoding.failure != null || encoding.cancelled
                || encoding.bytes == null
                || box.pendingBytes + encoding.bytes.length > HologramSnapshotAssembly.MAX_BYTES) {
                Exception failure = encoding.failure != null ? encoding.failure
                    : new IllegalArgumentException("hologram queued snapshot byte budget");
                long failedUi = snapshot.owned.hasKey("uiSequence", 4) ? snapshot.owned.getLong("uiSequence")
                    : Long.MIN_VALUE;
                String failureCode = failure.getMessage() != null && failure.getMessage()
                    .contains("budget") ? "snapshot.budget" : "snapshot.encode";
                long failedTask = snapshot.owned.getLong("blueprintClientTaskToken");
                snapshot.failed = true;
                retire(box);
                boxes.remove();
                transportFailure(box.player, box.session, failedUi, failureCode, failedTask, failure);
                continue;
            }
            snapshot.bytes = encoding.bytes;
            snapshot.libraryHash = snapshot.libraryInitial ? encoding.hash : null;
            box.pendingBytes += snapshot.bytes.length;
            if (snapshot.owned.hasKey("cells", 9)) HologramRuntimeMetrics
                .add(HologramRuntimeMetrics.Counter.FULL_CELLS_COMPRESSED_BYTES, snapshot.bytes.length);
            snapshot.ready.run();
            HologramWorkspace.commitPresentation(box.owner, snapshot.owned);
            snapshot.ready = null;
            snapshot.owned = null;
            snapshot.valid = null;
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.SNAPSHOT_QUEUED, 1);
        }
    }

    /** Optional observations have no delivery target for a FakePlayer or a disconnected peer. */
    static boolean progressConnected(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager != null
            && player.playerNetServerHandler.netManager.isChannelOpen();
    }

    private static byte[] compressSnapshot(NBTTagCompound tag) throws IOException {
        long started = HologramRuntimeMetrics.start();
        try {
            return HologramSnapshotCodec.compress(tag);
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.SNAPSHOT_COMPRESS, started);
        }
    }

    private static final class Outbox {

        final EntityPlayerMP player;
        final net.minecraft.world.World world;
        final int dimension;
        final String session;
        final HologramSession owner;
        final SnapshotFlow flow;
        final Deque<OutgoingSnapshot> snapshots = new ArrayDeque<>();
        long pendingBytes;

        Outbox(EntityPlayerMP player, String session, HologramSession owner, SnapshotFlow flow) {
            this.player = player;
            world = player.worldObj;
            dimension = player.dimension;
            this.session = session;
            this.owner = owner;
            this.flow = flow;
        }
    }

    private static final class SnapshotFlow {

        final EntityPlayerMP player;
        final Object world, connection;
        final int dimension;
        final HologramSnapshotFlowControl credit;
        String session = "";
        String consumedSession = "";
        long uiSequence = Long.MIN_VALUE, clientTask;
        boolean consumedSuccess;

        SnapshotFlow(EntityPlayerMP player) {
            this.player = player;
            world = player.worldObj;
            connection = player.playerNetServerHandler;
            dimension = player.dimension;
            credit = new HologramSnapshotFlowControl(
                snapshotFlowGeneration.incrementAndGet(),
                player.worldObj.getTotalWorldTime());
        }

        boolean current(EntityPlayerMP player) {
            return this.player == player && world == player.worldObj
                && connection == player.playerNetServerHandler
                && dimension == player.dimension;
        }

        boolean begin(Outbox box, OutgoingSnapshot snapshot) {
            if (!current(box.player)
                || !credit.begin(snapshot.sequence, snapshot.bytes.length, player.worldObj.getTotalWorldTime()))
                return false;
            session = box.session;
            uiSequence = snapshot.uiSequence;
            clientTask = snapshot.clientTask;
            return true;
        }
    }

    private static SnapshotFlow snapshotFlow(EntityPlayerMP player) {
        SnapshotFlow flow = snapshotFlows.get(player.getUniqueID());
        if (flow == null || !flow.current(player)) {
            flow = new SnapshotFlow(player);
            snapshotFlows.put(player.getUniqueID(), flow);
            snapshotFlowReply(flow, "offer", -1, true);
        }
        return flow;
    }

    /** Progress with no scope/phase is explicitly ignored by older clients, rather than installed as geometry. */
    private static void snapshotFlowReply(SnapshotFlow flow, String status, long sequence, boolean success) {
        if (channel == null || !progressConnected(flow.player)) return;
        NBTTagCompound event = new NBTTagCompound();
        event.setInteger("snapshotFlowVersion", HologramSnapshotFlowControl.VERSION);
        event.setLong("snapshotFlowGeneration", flow.credit.generation);
        event.setString("snapshotFlowStatus", status);
        event.setLong("transportSequence", sequence);
        event.setBoolean("ready", flow.credit.ready());
        event.setBoolean("success", success);
        event.setString("session", sequence < 0 ? flow.session : flow.consumedSession);
        NBTTagCompound packet = new NBTTagCompound();
        packet.setBoolean("progressOnly", true);
        packet.setTag("progress", event);
        channel.sendTo(new State(packet), flow.player);
    }

    /** Main-thread control lane; no workspace command or edit sequence is changed by credit messages. */
    static boolean snapshotFlowAction(EntityPlayerMP player, NBTTagCompound action) {
        boolean control = "snapshotFlow".equals(action.getString("op"))
            || "snapshotConsumed".equals(action.getString("op"));
        if (!control && action.getInteger("clientSnapshotFlow") != HologramSnapshotFlowControl.VERSION) return false;
        SnapshotFlow flow = snapshotFlow(player);
        if (action.hasKey("clientSnapshotFlow", 3)
            && action.getInteger("clientSnapshotFlow") == HologramSnapshotFlowControl.VERSION) flow.credit.capable();
        if (!control) return false;
        long generation = action.getLong("snapshotFlowGeneration");
        if ("snapshotFlow".equals(action.getString("op"))) {
            boolean accepted = action.hasKey("snapshotFlowGeneration", 4)
                && flow.credit.negotiate(generation, action.getBoolean("ready"));
            snapshotFlowReply(flow, accepted ? "accepted" : "legacy", -1, accepted);
            return true;
        }
        long sequence = action.getLong("transportSequence");
        if (!action.hasKey("snapshotFlowGeneration", 4) || !action.hasKey("transportSequence", 4)) return true;
        if (flow.credit.consume(generation, sequence)) {
            boolean success = action.getBoolean("success");
            flow.consumedSession = flow.session;
            flow.consumedSuccess = success;
            if (!success && channel != null && progressConnected(player)) {
                NBTTagCompound failure = new NBTTagCompound();
                failure.setBoolean("transportError", true);
                failure.setString("session", flow.session);
                if (flow.uiSequence != Long.MIN_VALUE) failure.setLong("uiSequence", flow.uiSequence);
                if (flow.clientTask > 0) failure.setLong("clientTaskToken", flow.clientTask);
                failure.setString("errorCode", "snapshot.decode");
                channel.sendTo(new State(failure), player);
            }
            snapshotFlowReply(flow, "consumed", sequence, success);
        } else if (flow.credit.consumed(generation, sequence))
            snapshotFlowReply(flow, "consumed", sequence, flow.consumedSuccess);
        return true;
    }

    private static final class OutgoingSnapshot {

        final long sequence;
        byte[] bytes, libraryHash;
        boolean libraryInitial, failed;
        long advancedAt = System.nanoTime(), observedRawBytes;
        NBTTagCompound owned;
        java.util.function.BooleanSupplier valid;
        Runnable ready;
        HologramSnapshotEncoding.Job encoding;
        int offset, index;
        boolean queueNotified;
        String traceSession;
        long traceRevision, traceRequest;
        long uiSequence = Long.MIN_VALUE, clientTask;

        OutgoingSnapshot(long sequence) {
            this.sequence = sequence;
        }

        void initialize(NBTTagCompound owned, java.util.function.BooleanSupplier valid, Runnable ready) {
            this.owned = owned;
            advancedAt = System.nanoTime();
            this.valid = valid;
            this.ready = ready;
            libraryInitial = owned.getBoolean("libraryInitialSnapshot");
            uiSequence = owned.hasKey("uiSequence", 4) ? owned.getLong("uiSequence") : Long.MIN_VALUE;
            clientTask = owned.getLong("blueprintClientTaskToken");
            if (HologramRuntimeMetrics.isEnabled()) {
                traceSession = owned.getString("session");
                traceRevision = owned.getLong("planRevision");
                traceRequest = owned.getLong("uiSequence");
            }
        }
    }

    /** Main-thread FIFO: one bounded fragment per player and tick, including every ACK snapshot. */
    public static final class TransportTicks {

        @SubscribeEvent
        public void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) HologramAttachmentUpload.clientTick();
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            long started = HologramRuntimeMetrics.start();
            try {
                HologramBusyReplies.flush();
                tickTransfers();
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.TRANSPORT_TICK, started);
            }
        }

        private void tickTransfers() {
            snapshotFlows.values()
                .removeIf(flow -> !flow.current(flow.player) || !progressConnected(flow.player));
            HologramAttachmentUpload.tick();
            HologramSnapshotPreparation.pruneRetired();
            tickEncoding();
            Iterator<CutOutbox> cuts = cutOutgoing.values()
                .iterator();
            while (cuts.hasNext()) {
                CutOutbox box = cuts.next();
                if (!HologramWorkspace.currentActionEndpoint(box.endpoint)) {
                    cuts.remove();
                    continue;
                }
                CutSnapshot snapshot = box.snapshots.peekFirst();
                if (snapshot == null) {
                    cuts.remove();
                    continue;
                }
                channel.sendTo(new CutFragment(snapshot.sequence, snapshot.bytes.length, 0, snapshot.bytes),
                    box.endpoint.player);
                box.pendingBytes -= snapshot.bytes.length;
                box.snapshots.removeFirst();
                if (box.snapshots.isEmpty()) cuts.remove();
            }
            // Rotate only the initial file-like hydration queues; normal interaction snapshots retain their FIFO.
            java.util.List<Outbox> initial = new java.util.ArrayList<>();
            for (Outbox box : outgoing.values()) {
                OutgoingSnapshot first = box.snapshots.peekFirst();
                if (first != null && first.bytes != null && first.libraryInitial) initial.add(box);
            }
            if (!initial.isEmpty()) {
                int start = publicSnapshotCursor++ % initial.size();
                for (int n = 0; n < initial.size(); n++) {
                    Outbox box = initial.get((start + n) % initial.size());
                    OutgoingSnapshot first = box.snapshots.peekFirst();
                    if (!connected(box) || first == null) continue;
                    if (first.offset == 0 && !box.flow.begin(box, first)) continue;
                    int length = Math.min(HologramLibraryProtocol.CHUNK_BYTES, first.bytes.length - first.offset);
                    if (!HologramLibraryFacade
                        .consumeDownloadAllowance(HologramLibraryFacade.actorId(box.player), length)) {
                        if (!first.queueNotified
                            && !HologramLibraryFacade.downloadWaiting(HologramLibraryFacade.actorId(box.player))) {
                            first.queueNotified = true;
                            NBTTagCompound queued = HologramLibraryProtocol.reply("queued");
                            queued.setString(
                                "message",
                                "Server download queue is busy; waiting for a fair transfer slot");
                            sendLibraryReply(box.player, queued);
                        }
                        continue;
                    }
                    channel.sendTo(
                        new LibrarySnapshotFragment(
                            first.sequence,
                            first.bytes.length,
                            first.index++,
                            first.libraryHash,
                            Arrays.copyOfRange(first.bytes, first.offset, first.offset + length)),
                        box.player);
                    first.offset += length;
                    if (first.offset == first.bytes.length) {
                        HologramLibraryFacade.cancelDownloadAllowance(HologramLibraryFacade.actorId(box.player));
                        box.pendingBytes -= first.bytes.length;
                        box.snapshots.removeFirst();
                    }
                }
            }
            Iterator<Outbox> iterator = outgoing.values()
                .iterator();
            while (iterator.hasNext()) {
                Outbox box = iterator.next();
                if (box.player.worldObj != box.world || box.player.dimension != box.dimension
                    || box.player.playerNetServerHandler == null
                    || box.player.playerNetServerHandler.netManager == null
                    || !box.player.playerNetServerHandler.netManager.isChannelOpen()) {
                    if (box.snapshots.peekFirst() != null && box.snapshots.peekFirst().libraryInitial)
                        HologramLibraryFacade.cancelDownloadAllowance(HologramLibraryFacade.actorId(box.player));
                    iterator.remove();
                    continue;
                }
                OutgoingSnapshot snapshot = box.snapshots.peekFirst();
                if (snapshot == null) {
                    iterator.remove();
                    continue;
                }
                if (snapshot.bytes == null || snapshot.libraryInitial) continue;
                if (snapshot.offset == 0 && !box.flow.begin(box, snapshot)) continue;
                int end = Math.min(snapshot.bytes.length, snapshot.offset + HologramSnapshotAssembly.CHUNK_BYTES);
                if (snapshot.offset == 0 && snapshot.traceSession != null) HologramRuntimeMetrics.loadStage(
                    HologramRuntimeMetrics.LoadStage.SERVER_FRAGMENT_FIRST,
                    snapshot.traceSession,
                    snapshot.traceRevision,
                    snapshot.traceRequest,
                    snapshot.sequence,
                    snapshot.bytes.length);
                channel.sendTo(
                    new Fragment(
                        snapshot.sequence,
                        snapshot.bytes.length,
                        snapshot.index++,
                        Arrays.copyOfRange(snapshot.bytes, snapshot.offset, end)),
                    box.player);
                snapshot.offset = end;
                if (end == snapshot.bytes.length) {
                    if (snapshot.traceSession != null) HologramRuntimeMetrics.loadStage(
                        HologramRuntimeMetrics.LoadStage.SERVER_FRAGMENT_LAST,
                        snapshot.traceSession,
                        snapshot.traceRevision,
                        snapshot.traceRequest,
                        snapshot.sequence,
                        snapshot.bytes.length);
                    box.pendingBytes -= snapshot.bytes.length;
                    box.snapshots.removeFirst();
                    if (box.snapshots.isEmpty()) iterator.remove();
                }
            }
        }

        private boolean connected(Outbox box) {
            return box.player.worldObj == box.world && box.player.dimension == box.dimension
                && box.player.playerNetServerHandler != null
                && box.player.playerNetServerHandler.netManager != null
                && box.player.playerNetServerHandler.netManager.isChannelOpen();
        }
    }

    /** Dedicated library initialization uses the same versioned, bounded slices as file transfers. */
    public static final class LibrarySnapshotFragment implements IMessage {

        private long sequence;
        private int total, index;
        private byte[] bytes, hash;

        public LibrarySnapshotFragment() {}

        LibrarySnapshotFragment(long sequence, int total, int index, byte[] hash, byte[] bytes) {
            this.sequence = sequence;
            this.total = total;
            this.index = index;
            this.hash = hash;
            this.bytes = bytes;
        }

        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() < 56) throw new IllegalArgumentException("library snapshot header");
            if (buf.readInt() != HologramLibraryProtocol.VERSION)
                throw new IllegalArgumentException("Unsupported library snapshot protocol version");
            sequence = buf.readLong();
            total = buf.readInt();
            index = buf.readInt();
            hash = new byte[32];
            buf.readBytes(hash);
            int length = buf.readInt();
            int offset = index < 0 || index > HologramSnapshotAssembly.MAX_BYTES / HologramLibraryProtocol.CHUNK_BYTES
                ? -1
                : index * HologramLibraryProtocol.CHUNK_BYTES;
            if (sequence < 0 || total <= 0
                || total > HologramSnapshotAssembly.MAX_BYTES
                || offset < 0
                || offset >= total
                || length <= 0
                || length > HologramLibraryProtocol.CHUNK_BYTES
                || length != Math.min(HologramLibraryProtocol.CHUNK_BYTES, total - offset)
                || length != buf.readableBytes()) throw new IllegalArgumentException("library snapshot budget");
            bytes = new byte[length];
            buf.readBytes(bytes);
            HologramRuntimeMetrics
                .packet(HologramRuntimeMetrics.Packet.LIBRARY_SNAPSHOT, false, false, bytes.length + 56);
        }

        public void toBytes(ByteBuf buf) {
            buf.writeInt(HologramLibraryProtocol.VERSION);
            buf.writeLong(sequence);
            buf.writeInt(total);
            buf.writeInt(index);
            buf.writeBytes(hash);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            HologramRuntimeMetrics
                .packet(HologramRuntimeMetrics.Packet.LIBRARY_SNAPSHOT, false, true, bytes.length + 56);
        }
    }

    public static final class LibrarySnapshotHandler implements IMessageHandler<LibrarySnapshotFragment, IMessage> {

        public IMessage onMessage(LibrarySnapshotFragment message, MessageContext context) {
            observeFragment(
                "snapshot",
                message.sequence,
                message.total,
                message.index,
                message.bytes.length,
                HologramLibraryProtocol.CHUNK_BYTES);
            byte[] complete = librarySnapshots
                .accept(message.sequence, message.total, message.index, message.bytes, message.hash, System.nanoTime());
            Consumer<NBTTagCompound> current = receiver;
            if (complete != null && current != null) {
                for (int offset = 0, index = 0; offset
                    < complete.length; offset += HologramSnapshotAssembly.CHUNK_BYTES, index++) {
                    NBTTagCompound envelope = new NBTTagCompound();
                    envelope.setBoolean("transportFragment", true);
                    envelope.setLong("transportSequence", message.sequence);
                    envelope.setInteger("transportTotal", complete.length);
                    envelope.setInteger("transportIndex", index);
                    envelope.setByteArray(
                        "transportBytes",
                        Arrays.copyOfRange(
                            complete,
                            offset,
                            Math.min(complete.length, offset + HologramSnapshotAssembly.CHUNK_BYTES)));
                    current.accept(envelope);
                }
            }
            return null;
        }
    }

    /** Raw compressed slices stay below both the Forge packet and per-NBT limits. */
    public static final class Fragment implements IMessage {

        private long sequence;
        private int total, index;
        private byte[] bytes;
        private HologramRuntimeMetrics.Packet metricFamily = HologramRuntimeMetrics.Packet.FRAGMENT;

        public Fragment() {}

        Fragment(long sequence, int total, int index, byte[] bytes) {
            this.sequence = sequence;
            this.total = total;
            this.index = index;
            this.bytes = bytes;
        }

        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() < 20) throw new IllegalArgumentException("hologram fragment header");
            sequence = buf.readLong();
            total = buf.readInt();
            index = buf.readInt();
            int length = buf.readInt();
            if (sequence < 0 || total <= 0
                || total > HologramSnapshotAssembly.MAX_BYTES
                || index < 0
                || index >= (total - 1) / HologramSnapshotAssembly.CHUNK_BYTES + 1
                || length <= 0
                || length > HologramSnapshotAssembly.CHUNK_BYTES
                || length != buf.readableBytes()) throw new IllegalArgumentException("hologram fragment size");
            bytes = new byte[length];
            buf.readBytes(bytes);
            HologramRuntimeMetrics.packet(metricFamily, false, false, bytes.length + 20);
        }

        public void toBytes(ByteBuf buf) {
            buf.writeLong(sequence);
            buf.writeInt(total);
            buf.writeInt(index);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            HologramRuntimeMetrics.packet(metricFamily, false, true, bytes.length + 20);
        }
    }

    public static final class FragmentHandler implements IMessageHandler<Fragment, IMessage> {

        public IMessage onMessage(Fragment msg, MessageContext ctx) {
            observeFragment(
                "snapshot",
                msg.sequence,
                msg.total,
                msg.index,
                msg.bytes.length,
                HologramSnapshotAssembly.CHUNK_BYTES);
            librarySnapshots.supersede(msg.sequence);
            Consumer<NBTTagCompound> current = receiver;
            if (current != null) {
                NBTTagCompound envelope = new NBTTagCompound();
                envelope.setBoolean("transportFragment", true);
                envelope.setLong("transportSequence", msg.sequence);
                envelope.setInteger("transportTotal", msg.total);
                envelope.setInteger("transportIndex", msg.index);
                envelope.setByteArray("transportBytes", msg.bytes);
                current.accept(envelope);
            }
            return null;
        }
    }

    public static class Packet implements IMessage {

        NBTTagCompound tag = new NBTTagCompound();

        public Packet() {}

        Packet(NBTTagCompound tag) {
            this.tag = (NBTTagCompound) tag.copy();
        }

        protected int expandedBudget() {
            return 16 * MAX_BYTES;
        }

        public void fromBytes(ByteBuf buf) {
            int length = buf.readInt();
            if (length < 0 || length > MAX_BYTES || length > buf.readableBytes())
                throw new IllegalArgumentException("hologram packet size");
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            try {
                tag = HologramSnapshotCodec.decode(bytes, expandedBudget(), 4L * expandedBudget());
                HologramRuntimeMetrics.packet(
                    this instanceof Action ? HologramRuntimeMetrics.Packet.ACTION : HologramRuntimeMetrics.Packet.STATE,
                    this instanceof Action,
                    false,
                    bytes.length + 4);
            } catch (IOException e) {
                throw new IllegalArgumentException("hologram NBT", e);
            }
        }

        public void toBytes(ByteBuf buf) {
            try {
                byte[] bytes = CompressedStreamTools.compress(tag);
                if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("hologram packet size");
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
                HologramRuntimeMetrics.packet(
                    this instanceof Action ? HologramRuntimeMetrics.Packet.ACTION : HologramRuntimeMetrics.Packet.STATE,
                    this instanceof Action,
                    true,
                    bytes.length + 4);
                if (this instanceof State && tag.getBoolean("progressOnly")
                    && tag.getCompoundTag("progress")
                        .getBoolean("jobDelta"))
                    HologramRuntimeMetrics
                        .add(HologramRuntimeMetrics.Counter.JOB_DELTA_ENCODED_BYTES, bytes.length + 4);
            } catch (IOException e) {
                throw new IllegalArgumentException("hologram NBT", e);
            }
        }
    }

    private static void markSnapshotCapabilities(NBTTagCompound tag) {
        tag.setInteger("clientSnapshotProtocol", 1);
        tag.setInteger("clientSnapshotPalette", HologramSnapshotPalette.VERSION);
        tag.setInteger("clientSnapshotFlow", HologramSnapshotFlowControl.VERSION);
    }

    public static final class Action extends Packet {

        @Override
        protected int expandedBudget() {
            return HologramAeProtocol.MAX_STACK_AGGREGATE;
        }

        public Action() {}

        Action(NBTTagCompound tag) {
            super(tag);
            markSnapshotCapabilities(this.tag);
        }
    }

    public static final class State extends Packet {

        public State() {}

        State(NBTTagCompound tag) {
            super(tag);
        }
    }

    public static final class ServerHandler implements IMessageHandler<Action, IMessage> {

        public IMessage onMessage(Action msg, MessageContext ctx) {
            // Retired control packets from older clients must never enter workspace command dispatch.
            String action = msg.tag.getString("action");
            if (HologramStockNativeWire.recognizes(msg.tag)) {
                HologramStockNativeWire.Request request = HologramStockNativeWire.readRequest(msg.tag);
                if (request != null && ctx != null)
                    stockNativeServer.enqueue(HologramWorkspace.captureActionEndpoint(ctx.getServerHandler()), request);
                return null; // Exact marker never falls through to old construction or library actions.
            }
            if ("serverRules".equals(action)) {
                queueProjectedLayerProbe(msg.tag, ctx);
                return null;
            }
            HologramWorkspace.ActionEndpoint endpoint = HologramWorkspace.captureActionEndpoint(ctx.getServerHandler());
            if (endpoint == null) return null;
            if ("projectedPlacement".equals(action) || "checkPlacementMaterials".equals(action)) {
                if (msg.tag.hasKey("placementRequestId", 8)) refuseRetiredPlacement(
                    endpoint, msg.tag.getString("placementRequestId"),
                    "projectedPlacement".equals(action) ? RETIRED_PLACEMENT_REASON : RETIRED_MATERIAL_CHECK_REASON);
                return null;
            }
            EntityPlayerMP player = endpoint.player;
            // Preserve an independent lane for disk receipts; different capabilities are never coalesced.
            boolean receipt = "ackBlueprintSaved".equals(msg.tag.getString("op"))
                || "cancelProjectedPlacement".equals(msg.tag.getString("action"));
            boolean snapshotControl = "snapshotFlow".equals(msg.tag.getString("op"))
                || "snapshotConsumed".equals(msg.tag.getString("op"));
            HologramRequestGate gate = snapshotControl ? snapshotControlGate : receipt ? receiptGate : actionGate;
            HologramRequestGate.Lease lease = gate.acquire(player);
            boolean compactNative = HologramCompactNativeRequest.ordinary(msg.tag)
                || "useBlock".equals(msg.tag.getString("action")) && msg.tag.hasKey(HologramCompactNativeRequest.FLAG);
            String compactId = compactNative ? msg.tag.getString("placementRequestId") : "";
            if (lease == null) {
                if (compactNative && HologramNativeCompactStart.canonical(compactId))
                    HologramProjectedPlacementService.reply(player, compactId, false, "服务器正忙，原生放置未开始。");
                else if (!snapshotControl) HologramBusyReplies.reject(player, msg.tag, HologramBusyReplies.Kind.ACTION);
                return null;
            }
            HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.ACTION_DEPTH, actionGate.size());
            HologramRuntimeMetrics.highWater(HologramRuntimeMetrics.Counter.ACTION_HIGH_WATER, actionGate.size());
            // Packet decompression is already bounded. Large index selections must not be rejected
            // because their textual representation exceeds an unrelated string budget.
            HologramSessionPolicy.PreparationTool submittedTool = compactNative
                ? new HologramSessionPolicy.PreparationTool(player.getHeldItem())
                : null;
            ServerTaskScheduler.scheduleServerTask(() -> runQueuedAction(endpoint, lease, () -> {
                if (compactNative && !submittedTool.matches(player.getHeldItem())) {
                    if (HologramNativeCompactStart.canonical(compactId)
                        && !HologramProjectedPlacementService.consumed(player, compactId)) {
                        HologramProjectedPlacementService.retire(player, compactId);
                        HologramProjectedPlacementService.reply(player, compactId, false, "手持投影仪已变化。");
                    }
                    return;
                }
                dispatchWorkspaceAction(player, msg.tag);
            }));
            return null;
        }
    }

    /** Direct Actions use the exact authenticated ingress generation, even after a round trip back to the same world. */
    static void runQueuedAction(HologramWorkspace.ActionEndpoint endpoint, HologramRequestGate.Lease lease,
        Runnable action) {
        runQueuedAction(endpoint.player, endpoint.world, lease, () -> {
            if (HologramWorkspace.currentActionEndpoint(endpoint)) action.run();
        });
    }

    /** Admission belongs to the original live world; queued input cannot act after a connection/world transition. */
    static void runQueuedAction(EntityPlayerMP player, net.minecraft.world.World world, HologramRequestGate.Lease lease,
        Runnable action) {
        try {
            if (lease == null || !lease.active()
                || world == null
                || player.worldObj != world
                || !progressConnected(player)) return;
            action.run();
        } finally {
            if (lease != null) lease.close();
            HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.ACTION_DEPTH, actionGate.size());
        }
    }

    /** Generic owner-thread Action only; stock/normal layer and library ingress never come through here. */
    static void dispatchWorkspaceAction(EntityPlayerMP player, NBTTagCompound tag) {
        if (HologramWorkspace.stockNativePrivate(player)) return;
        HologramControllerOpen.dispatch(player, tag, () -> dispatch(player, tag));
    }

    private static void dispatch(EntityPlayerMP player, NBTTagCompound tag) {
        String action = tag.getString("action");
        if ("projectedPlacement".equals(action) || "checkPlacementMaterials".equals(action)
            || snapshotFlowAction(player, tag)) return;
        if ("cancelProjectedPlacement".equals(action)) {
            String requestId = tag.getString("placementRequestId");
            if (!HologramNativeCompactStart.cancel(player, requestId)
                && !HologramNativeReadyPlacement.cancel(player, requestId))
                HologramProjectedPlacementService.cancel(player, requestId);
            return;
        }
        if (HologramCompactNativeRequest.ordinary(tag)
            || "useBlock".equals(action) && tag.hasKey(HologramCompactNativeRequest.FLAG)) {
            HologramNativeCompactStart.receive(player, tag);
            return;
        }
        if ("useBlock".equals(action) && tag.hasKey(HologramNativeReadyPlacement.FLAG)) {
            HologramNativeReadyPlacement.receive(player, tag);
            return;
        }
        if (HologramDemolition.active(player) && !action.isEmpty()) {
            HologramBusyReplies.reject(player, tag, HologramBusyReplies.Kind.ACTION);
            player.addChatMessage(new net.minecraft.util.ChatComponentText("拆除正在进行，请等待完成或按 Esc 取消"));
            return;
        }
        if ("voucherEditor".equals(action)) {
            HologramVoucherEditor.handle(player, tag);
            return;
        }
        if (HologramAeCraftingOrders.active(player) && !(action.isEmpty() || "useAir".equals(action)
            || "useBlock".equals(action)
            || "openSettings".equals(action))) {
            HologramSession current = HologramWorkspace.find(player.getUniqueID());
            if (current != null) {
                current.status = "AE 缺料订单正在后台处理；请先等待完成或取消订单";
                HologramSnapshots.sendUi(current);
            }
            return;
        }
        if ("confirmVirtualPosition".equals(action)) {
            HologramService.confirmWorldPosition(player, tag);
            return;
        }
        if ("cancelVirtualPlacement".equals(action) && tag.hasKey("positionLockSequence", 4)) {
            HologramService.cancelWorldPlacement(player, tag);
            return;
        }
        if ("cancelAreaSelection".equals(action)) {
            HologramService.cancelAreaSelection(player, tag.getString("session"));
            return;
        }
        if ("beginAreaSelection".equals(action) || "finishAreaSelection".equals(action)
            || "areaPoint".equals(action)
            || "unlockVirtualPosition".equals(action)
            || "nudgeVirtualPosition".equals(action)
            || "quickPlaceLastBlueprint".equals(action)
            || "cancelVirtualPlacement".equals(action)) {
            if (!com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem()))
                return;
            if ("beginAreaSelection".equals(action)) HologramService.beginAreaSelection(player);
            else if ("finishAreaSelection".equals(action)) HologramService.finishAreaSelection(player, tag);
            else if ("unlockVirtualPosition".equals(action)) {
                HologramService.unlockWorldPosition(player, tag);
            } else if ("nudgeVirtualPosition".equals(action)) {
                HologramService.nudgeWorldPosition(player, tag);
            } else if ("quickPlaceLastBlueprint".equals(action)) {
                HologramService.quickPlaceLastBlueprint(player, tag);
            } else if ("cancelVirtualPlacement".equals(action)) HologramService.cancelWorldPlacement(player, tag);
            else HologramService.worldAreaPoint(player, tag);
            return;
        }
        if ("useBlock".equals(action) || "useAir".equals(action)) {
            if (!com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem()))
                return;
            if ("useAir".equals(action)) HologramService.openAir(player);
            else {
                int x = tag.getInteger("x"), y = tag.getInteger("y"), z = tag.getInteger("z");
                int side = tag.getInteger("side");
                if (side < 0 || side > 5
                    || !player.worldObj.blockExists(x, y, z)
                    || player.getDistanceSq(x + .5, y + .5, z + .5) > 64) return;
                HologramService.openWorld(player, x, y, z, side);
            }
            return;
        }
        if ("openSettings".equals(action)) {
            if (com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem()))
                HologramService.openSettings(player);
            return;
        }
        HologramService.handle(player, tag);
    }

    public static final class ClientHandler implements IMessageHandler<State, IMessage> {

        public IMessage onMessage(State msg, MessageContext ctx) {
            // Older servers send this sessionless login envelope; it is not a workspace snapshot.
            // New clients never send a whole placement. Its legacy refusal cannot finish any live native request.
            if (HologramStockNativeWire.recognizes(msg.tag)) {
                StockNativeReplies receiver = stockNativeReplies;
                HologramStockNativeWire.Reply reply = receiver == null ? null : HologramStockNativeWire.readReply(msg.tag);
                if (reply != null && ctx != null) receiver.accept(reply, ctx.netHandler);
                return null;
            }
            if (msg.tag.getBoolean("serverRulesOnly")) {
                ProjectedLayerNegotiation negotiation = projectedLayerNegotiation;
                UUID request = negotiation == null ? null : projectedLayerIdentity(msg.tag, true);
                if (request != null && ctx != null) negotiation.accept(
                    request, msg.tag.getLong(LAYER_INTENT), msg.tag.getInteger(LAYER_VERSION),
                    msg.tag.getLong(LAYER_SCOPE), ctx.netHandler);
                return null;
            }
            if (msg.tag.getBoolean(RETIRED_PLACEMENT_REPLY)) return null;
            if (msg.tag.getBoolean("progressOnly")) {
                observeProgress(msg.tag.getCompoundTag("progress"));
                return null;
            }
            if (msg.tag.hasKey("progress", 10)) observeProgress(msg.tag.getCompoundTag("progress"));
            if (msg.tag.getBoolean("cancelled") && !msg.tag.getBoolean("controllerProjectionRetired")
                || msg.tag.getBoolean("transportError")) librarySnapshots.clear();
            Consumer<NBTTagCompound> current = receiver;
            if (current != null) current.accept(msg.tag);
            return null;
        }
    }
}
