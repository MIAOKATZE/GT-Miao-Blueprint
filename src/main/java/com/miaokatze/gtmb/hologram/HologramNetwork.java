package com.miaokatze.gtmb.hologram;

import java.io.IOException;
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
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

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
    private static volatile java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.NetworkManager> authorMaterialReceiver;
    private static volatile java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.NetworkManager> transportReceiver;
    private static volatile java.util.function.BiConsumer<HologramSnapshotAssembly.Complete, Long> frameReceiver;
    private static volatile Consumer<NBTTagCompound> interactionSender;
    private static volatile Consumer<NBTTagCompound> libraryReceiver;
    private static volatile Consumer<NBTTagCompound> libraryProbeReceiver;
    private static volatile Consumer<NBTTagCompound> cutReceiver;
    private static volatile java.util.function.BiConsumer<NBTTagCompound, AutoCloseable> ownedCutReceiver;
    private static volatile int cutServerControlVersion;
    private static volatile Consumer<NBTTagCompound> progressReceiver;
    private static final AtomicLong progressSequence = new AtomicLong();
    private static final Object cutTransportLock = new Object();
    private static HologramSnapshotAssembly cutAssembly = new HologramSnapshotAssembly();
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
    private static final Map<UUID, EntityPlayerMP> cutControlPeers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_BYTES = 1048576;
    private static final AtomicLong snapshotSequence = new AtomicLong();
    private static final Map<UUID, Outbox> outgoing = new LinkedHashMap<>();
    private static final Object libraryTransportLock = new Object();
    private static HologramLibrarySnapshotAssembly librarySnapshots = new HologramLibrarySnapshotAssembly();
    private static net.minecraft.network.NetworkManager libraryConnection;
    private static int publicSnapshotCursor;
    private static final Map<UUID, TransportPeer> transportPeers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final HologramTransportBudget transportBudget = new HologramTransportBudget(
        1024 * 1024 + 256,
        HologramSnapshotAssembly.CHUNK_BYTES + 128,
        3_000_000L);
    private static boolean cutFirst;
    private static int dataLane;
    private static final int DISPLAY_CHUNK_BYTES = 64 * 1024;
    private static final Map<UUID, DisplayOutbox> displayOutgoing = new LinkedHashMap<>();
    private static final HologramRequestGate actionGate = new HologramRequestGate(16, 256);
    private static final HologramRequestGate receiptGate = new HologramRequestGate(4, 128);

    private HologramNetwork() {}

    static void clearServerQueues() {
        HologramAttachmentUpload.clearServer();
        actionGate.clear();
        receiptGate.clear();
        for (Outbox box : outgoing.values()) retire(box);
        outgoing.clear();
        for (DisplayOutbox box : displayOutgoing.values()) box.close();
        displayOutgoing.clear();
        HologramSnapshotEncoding.clear();
        HologramTransportPageLayout.clear();
        HologramSnapshotDetails.clear();
        HologramSnapshotModels.clear();
        HologramSnapshotResponseLease.clear();
        HologramSnapshotPreparation.clear();
        for (CutOutbox box : cutOutgoing.values()) retireCut(box);
        cutOutgoing.clear();
        cutControlPeers.clear();
        transportPeers.clear();
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

    public static void init() {
        if (channel != null) return;
        HologramResourceWorld.init();
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
        channel.registerMessage(DisplayFragmentHandler.class, DisplayFragment.class, 10, Side.CLIENT);
        HologramAttachmentUpload.init(channel);
        HologramLibraryFacade.init();
        FMLCommonHandler.instance()
            .bus()
            .register(new TransportTicks());
    }

    public static void setAuthorMaterialCatalogReceiver(
        java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.NetworkManager> value) {
        authorMaterialReceiver = value;
    }

    static void sendAuthorMaterialCatalog(EntityPlayerMP player, NBTTagCompound result) {
        if (channel == null || !progressConnected(player)) return;
        NBTTagCompound envelope = new NBTTagCompound();
        envelope.setTag("authorMaterialCatalog", result);
        channel.sendTo(new State(envelope), player);
    }

    public static void setClientReceiver(Consumer<NBTTagCompound> value) {
        receiver = value;
        transportReceiver = null;
        frameReceiver = null;
        resetLibrarySnapshots();
    }

    /** The built-in client takes ownership of the admitted complete frame without a second assembly/copy. */
    public static void setClientFrameReceiver(
        java.util.function.BiConsumer<HologramSnapshotAssembly.Complete, Long> value) {
        frameReceiver = value;
    }

    /** Ordinary fragments carry the packet context's origin instead of inferring it after a reconnect. */
    public static void setClientTransportReceiver(
        java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.NetworkManager> value) {
        transportReceiver = value;
    }

    static void receiveTransportFragment(NBTTagCompound envelope, net.minecraft.network.NetworkManager connection) {
        java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.NetworkManager> transport = transportReceiver;
        if (transport != null) transport.accept(envelope, connection);
        else {
            Consumer<NBTTagCompound> current = receiver;
            if (current != null) current.accept(envelope);
        }
    }

    public static void clearLibraryClientTransport() {
        resetLibrarySnapshots();
        HologramAttachmentUpload.clearClient();
    }

    private static void resetLibrarySnapshots() {
        synchronized (libraryTransportLock) {
            librarySnapshots.reset();
            libraryConnection = null;
        }
    }

    /** A completed frame keeps this assembler's original connection even after the next login. */
    private static HologramLibrarySnapshotAssembly librarySnapshots(net.minecraft.network.NetworkManager connection) {
        if (libraryConnection != connection) {
            librarySnapshots.reset();
            libraryConnection = connection;
            librarySnapshots = new HologramLibrarySnapshotAssembly(
                sequence -> snapshotInboxReleased(sequence, connection));
        }
        return librarySnapshots;
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

    private static void receiveAdmissionFailure(String scope, long sequence) {
        receiveAdmissionFailure(
            scope,
            sequence,
            "The shared snapshot byte budget is full; synchronize or retry the current view.");
    }

    private static void receiveAdmissionFailure(String scope, long sequence, String detail) {
        NBTTagCompound progress = new NBTTagCompound();
        progress.setString("scope", scope);
        progress.setString("phase", "transport.retry");
        progress.setString("detail", detail);
        progress.setLong("sequence", sequence);
        progress.setLong("transportSequence", sequence);
        progress.setBoolean("complete", false);
        observeProgress(progress);
        com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
            .warn("Shared snapshot ingress is full for " + scope + " sequence=" + sequence);
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
        if (channel == null) return;
        if ("placeTextBox".equals(tag.getString("op"))) HologramAttachmentUpload.send(tag);
        else channel.sendToServer(new Action(tag));
    }

    /** Returning an inbox slot never executes a world command; old servers safely ignore this extra receipt flag. */
    public static void snapshotInboxReleased(long sequence) {
        if (sequence < 0 || channel == null) return;
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("op", "ackTransportSegments");
        receipt.setBoolean("inboxConsumed", true);
        receipt.setLong("transportSequence", sequence);
        sendAction(receipt);
    }

    /** Never use sendToServer here: it resolves the current connection at disposal time. */
    private static void snapshotInboxReleased(long sequence, net.minecraft.network.NetworkManager connection) {
        if (sequence < 0 || channel == null || connection == null || !connection.isChannelOpen()) return;
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("op", "ackTransportSegments");
        receipt.setBoolean("inboxConsumed", true);
        receipt.setLong("transportSequence", sequence);
        connection.scheduleOutboundPacket(channel.getPacketFrom(new Action(receipt)));
    }

    /** Only contention can recover unchanged bytes; a frame larger than either fixed limit cannot. */
    public static boolean snapshotAdmissionCanRetry(long compressedBytes) {
        return compressedBytes > 0 && compressedBytes <= HologramSnapshotAssembly.MAX_BYTES
            && compressedBytes <= HologramProcessGovernor.retainedByteLimit();
    }

    /** Admission failure has no decoded session. Ask only the original sender to resolve its exact sent frame. */
    public static boolean requestSnapshotAdmissionRecovery(net.minecraft.network.NetworkManager connection,
        long sequence, long compressedBytes) {
        if (sequence < 0 || !snapshotAdmissionCanRetry(compressedBytes)
            || channel == null
            || connection == null
            || !connection.isChannelOpen()) return false;
        NBTTagCompound recovery = new NBTTagCompound();
        recovery.setString("op", "requestFullSnapshot");
        recovery.setBoolean("recoverTransportSequence", true);
        recovery.setLong("transportSequence", sequence);
        connection.scheduleOutboundPacket(channel.getPacketFrom(new Action(recovery)));
        return true;
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

    public static void setCutReceiver(Consumer<NBTTagCompound> value) {
        synchronized (cutTransportLock) {
            cutReceiver = value;
            ownedCutReceiver = null;
            cutServerControlVersion = 0;
            cutAssembly.clear();
            cutAssembly = new HologramSnapshotAssembly();
            cutClientGeneration.incrementAndGet();
            for (Runnable queued : cutDecoder.getQueue())
                if (queued instanceof CutDecode) ((CutDecode) queued).complete.close();
            cutDecoder.getQueue()
                .clear();
            HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH, 0);
        }
    }

    public static void setOwnedCutReceiver(java.util.function.BiConsumer<NBTTagCompound, AutoCloseable> value) {
        synchronized (cutTransportLock) {
            setCutReceiver(value == null ? null : state -> value.accept(state, () -> {}));
            ownedCutReceiver = value;
        }
    }

    public static void clearAttachmentClientTransport() {
        HologramAttachmentUpload.clearClient();
    }

    public static void clearCutClientTransport() {
        synchronized (cutTransportLock) {
            cutAssembly.clear();
            cutAssembly = new HologramSnapshotAssembly();
            cutClientGeneration.incrementAndGet();
            for (Runnable queued : cutDecoder.getQueue())
                if (queued instanceof CutDecode) ((CutDecode) queued).complete.close();
            cutDecoder.getQueue()
                .clear();
            HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH, 0);
        }
    }

    public static void sendCutAction(NBTTagCompound tag) {
        if (channel != null) channel.sendToServer(new CutAction(tag));
    }

    static void sendCutState(EntityPlayerMP player, NBTTagCompound tag) {
        if (channel == null) return;
        tag = (NBTTagCompound) tag.copy();
        tag.setInteger("serverCutControlProtocol", 1);
        // Cut replies are independently correlated by requestId, so a committed world receipt need not
        // wait for an unrelated display preview. Old peers keep the original complete-frame protocol.
        if (cutControlPeers.get(player.getUniqueID()) == player && !tag.hasKey("preview", 10)) {
            NBTTagCompound control = new NBTTagCompound();
            control.setTag("cutControl", tag.copy());
            channel.sendTo(new State(control), player);
            return;
        }
        CutOutbox box = cutOutgoing.get(player.getUniqueID());
        if (box == null || box.player != player) {
            if (box != null) retireCut(box);
            box = new CutOutbox(player);
            cutOutgoing.put(player.getUniqueID(), box);
        }
        if (cutControlPeers.get(player.getUniqueID()) != player) {
            // Keep the old complete codec and its existing byte ceiling for peers without the new control lane.
            try {
                byte[] encoded = compressSnapshot(tag);
                if (box.pendingBytes + encoded.length > HologramSnapshotAssembly.MAX_BYTES)
                    throw new IOException("cut transport queue full");
                CutSnapshot legacy = new CutSnapshot(snapshotSequence.incrementAndGet(), null);
                legacy.bytes = encoded;
                box.snapshots.addLast(legacy);
                box.pendingBytes += encoded.length;
            } catch (IOException failure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut snapshot could not be queued", failure);
            }
            return;
        }
        // A queued display for the same request is replaceable; committed receipts are never coalesced.
        if (tag.hasKey("preview", 10)) {
            Iterator<CutSnapshot> queued = box.snapshots.iterator();
            while (queued.hasNext()) {
                CutSnapshot previous = queued.next();
                if (previous.offset == 0 && previous.owned != null
                    && previous.owned.hasKey("preview", 10)
                    && previous.owned.getString("requestId")
                        .equals(tag.getString("requestId"))) {
                    previous.cancel();
                    queued.remove();
                }
            }
        }
        while (box.snapshots.size() >= 4) {
            // This lane contains new-client previews only; world receipts have already used cutControl above.
            CutSnapshot expired = box.snapshots.removeFirst();
            NBTTagCompound failure = new NBTTagCompound();
            failure.setString("requestId", expired.requestId);
            failure.setBoolean("success", false);
            failure.setString("code", "preview_replaced");
            failure.setString("message", "The previous cut preview was replaced; retry the current view.");
            if (expired.bytes != null) box.pendingBytes -= expired.bytes.length;
            expired.cancel();
            sendCutState(player, failure);
        }
        box.snapshots.addLast(new CutSnapshot(snapshotSequence.incrementAndGet(), (NBTTagCompound) tag.copy()));
    }

    private static void retireCut(CutOutbox box) {
        for (CutSnapshot snapshot : box.snapshots) snapshot.cancel();
        box.snapshots.clear();
    }

    private static final class CutOutbox {

        final EntityPlayerMP player;
        final Deque<CutSnapshot> snapshots = new ArrayDeque<>();
        long pendingBytes;

        CutOutbox(EntityPlayerMP player) {
            this.player = player;
        }
    }

    private static final class CutSnapshot {

        final long sequence;
        final String requestId;
        NBTTagCompound owned;
        HologramSnapshotEncoding.Job encoding;
        byte[] bytes;
        HologramSnapshotBytes output;
        int offset, index;

        CutSnapshot(long sequence, NBTTagCompound owned) {
            this.sequence = sequence;
            this.owned = owned;
            requestId = owned == null ? "" : owned.getString("requestId");
        }

        void cancel() {
            if (encoding != null) encoding.cancel();
            owned = null;
            bytes = null;
            if (output != null) output.close();
            output = null;
        }
    }

    /** A small, versioned request budget, separate from blueprint uploads and snapshots. */
    public static final class CutAction implements IMessage {

        NBTTagCompound tag = new NBTTagCompound();
        boolean controlProtocol;

        public CutAction() {}

        CutAction(NBTTagCompound tag) {
            this.tag = (NBTTagCompound) tag.copy();
            if (cutServerControlVersion == 1) this.tag.setInteger("clientCutControlProtocol", 1);
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
                controlProtocol = tag.getInteger("clientCutControlProtocol") == 1;
                tag.removeTag("clientCutControlProtocol");
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
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (message.controlProtocol) cutControlPeers.put(player.getUniqueID(), player);
            HologramCutService.receive(player, message.tag);
            return null;
        }
    }

    public static final class CutFragment implements IMessage {

        private Fragment fragment = new Fragment();

        public CutFragment() {}

        CutFragment(long sequence, int total, int index, byte[] bytes) {
            fragment = new Fragment(sequence, total, index, bytes);
        }

        public void fromBytes(ByteBuf buf) {
            fragment.metricFamily = HologramRuntimeMetrics.Packet.CUT_FRAGMENT;
            fragment.fromBytes(buf);
        }

        public void toBytes(ByteBuf buf) {
            fragment.metricFamily = HologramRuntimeMetrics.Packet.CUT_FRAGMENT;
            fragment.toBytes(buf);
        }
    }

    private static final class CutDecode implements Runnable {

        final HologramSnapshotAssembly.Complete complete;
        final Consumer<NBTTagCompound> receiver;
        final long generation;
        final java.util.function.BiConsumer<NBTTagCompound, AutoCloseable> ownedReceiver;

        CutDecode(HologramSnapshotAssembly.Complete complete, Consumer<NBTTagCompound> receiver, long generation) {
            this.complete = complete;
            this.receiver = receiver;
            this.generation = generation;
            ownedReceiver = ownedCutReceiver;
        }

        public void run() {
            HologramSnapshotCodec.OwnedSnapshot decoded = null;
            try {
                HologramProcessGovernor.Lease cpu = null;
                while (generation == cutClientGeneration.get() && cpu == null) {
                    cpu = HologramProcessGovernor.tryHeavyCpu("cut.snapshot.decode");
                    if (cpu == null) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
                }
                if (cpu == null) return;
                try (HologramProcessGovernor.Lease permit = cpu) {
                    decoded = HologramSnapshotCodec
                        .decodeOwned(complete.bytes, () -> generation == cutClientGeneration.get());
                }
                synchronized (cutTransportLock) {
                    if (generation == cutClientGeneration.get() && receiver == cutReceiver) {
                        observeCutCapabilities(decoded.value);
                        if (ownedReceiver != null) {
                            ownedReceiver.accept(decoded.value, decoded);
                            decoded = null;
                        } else receiver.accept(decoded.value);
                    }
                }
            } catch (java.util.concurrent.CancellationException retired) {
                // Retired display/connection generations never publish into the current cut owner.
            } catch (IOException failure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut snapshot decode failed", failure);
            } finally {
                if (decoded != null) decoded.close();
                complete.close();
                HologramRuntimeMetrics.gauge(
                    HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH,
                    cutDecoder.getQueue()
                        .size());
            }
        }
    }

    static void observeCutCapabilities(NBTTagCompound state) {
        if (state.getInteger("serverCutControlProtocol") == 1) cutServerControlVersion = 1;
    }

    public static final class CutFragmentHandler implements IMessageHandler<CutFragment, IMessage> {

        public IMessage onMessage(CutFragment message, MessageContext context) {
            CutDecode decode;
            synchronized (cutTransportLock) {
                Fragment fragment = message.fragment;
                observeFragment(
                    "cut",
                    fragment.sequence,
                    fragment.total,
                    fragment.index,
                    fragment.bytes.length,
                    HologramSnapshotAssembly.CHUNK_BYTES);
                HologramSnapshotAssembly.Complete complete;
                try {
                    complete = cutAssembly.acceptReserved(
                        fragment.sequence,
                        fragment.total,
                        fragment.index,
                        fragment.bytes,
                        System.nanoTime());
                } catch (HologramSnapshotAssembly.AdmissionException full) {
                    receiveAdmissionFailure("cut", fragment.sequence);
                    return null;
                }
                if (complete == null) return null;
                if (cutReceiver == null) {
                    complete.close();
                    return null;
                }
                decode = new CutDecode(complete, cutReceiver, cutClientGeneration.get());
            }
            try {
                cutDecoder.execute(decode);
            } catch (java.util.concurrent.RejectedExecutionException full) {
                // Bounded network-side backpressure, never discard a committed cut/paste receipt.
                HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.CUT_DECODER_REJECTED, 1);
                boolean interrupted = false;
                for (;;) {
                    try {
                        cutDecoder.getQueue()
                            .put(decode);
                        break;
                    } catch (InterruptedException problem) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread()
                    .interrupt();
            }
            HologramRuntimeMetrics.gauge(
                HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH,
                cutDecoder.getQueue()
                    .size());
            return null;
        }
    }

    public static void setLibraryReceiver(Consumer<NBTTagCompound> value) {
        libraryReceiver = value;
    }

    public static void setLibraryProbeReceiver(Consumer<NBTTagCompound> value) {
        if (Boolean.getBoolean("gtmb.hologram.librarySocketSmoke")) libraryProbeReceiver = value;
    }

    public static void sendLibrary(NBTTagCompound tag) {
        if (channel != null) channel.sendToServer(new LibraryRequest(tag));
    }

    static void sendDetail(EntityPlayerMP player, NBTTagCompound tag) {
        sendDisplay(player, tag, null);
    }

    static void sendDetail(EntityPlayerMP player, NBTTagCompound owned, AutoCloseable reservation) {
        sendDisplay(player, owned, reservation);
    }

    static void sendModel(EntityPlayerMP player, NBTTagCompound owned, AutoCloseable reservation) {
        sendDisplay(player, owned, reservation);
    }

    private static NBTTagCompound displayHeader(NBTTagCompound body) {
        NBTTagCompound header = new NBTTagCompound();
        for (String key : new String[] { "detailOnly", "modelOnly", "session", "planRevision", "detailGeneration",
            "modelGeneration", "requestId", "domain", "offset", "cell", "modelIds" })
            if (body.hasKey(key)) header.setTag(
                key,
                body.getTag(key)
                    .copy());
        return header;
    }

    private static void displayFailure(EntityPlayerMP player, NBTTagCompound header, String code) {
        NBTTagCompound failure = displayHeader(header);
        failure.setString(failure.getBoolean("modelOnly") ? "modelError" : "detailError", code);
        if (channel != null && progressConnected(player)) channel.sendTo(new State(failure), player);
    }

    private static void sendDisplay(EntityPlayerMP player, NBTTagCompound owned, AutoCloseable reservation) {
        if (channel == null || !progressConnected(player)) {
            closeReservation(reservation);
            return;
        }
        int total = 0;
        for (DisplayOutbox current : displayOutgoing.values()) total += current.rows.size();
        DisplayOutbox box = displayOutgoing.get(player.getUniqueID());
        if (box == null || box.player != player || box.world != player.worldObj) {
            if (box != null) box.close();
            box = new DisplayOutbox(player);
            displayOutgoing.put(player.getUniqueID(), box);
        }
        if (total >= 32 || box.rows.size() >= 4) {
            closeReservation(reservation);
            displayFailure(player, owned, "busy");
            return;
        }
        box.rows.addLast(new DisplaySnapshot(snapshotSequence.incrementAndGet(), owned, reservation));
    }

    private static final class DisplayOutbox implements AutoCloseable {

        final EntityPlayerMP player;
        final net.minecraft.world.World world;
        final Deque<DisplaySnapshot> rows = new ArrayDeque<>();

        DisplayOutbox(EntityPlayerMP player) {
            this.player = player;
            world = player.worldObj;
        }

        public void close() {
            TransportPeer peer = transportPeers.get(player.getUniqueID());
            for (DisplaySnapshot row : rows) {
                if (row.offset == 0 && peer != null) peer.inbox.release(row.sequence);
                row.close();
            }
            rows.clear();
        }
    }

    private static final class DisplaySnapshot implements AutoCloseable {

        final long sequence;
        NBTTagCompound owned, header;
        AutoCloseable input;
        HologramSnapshotEncoding.Job encoding;
        HologramSnapshotBytes output, headerBytes;
        int offset, index;

        DisplaySnapshot(long sequence, NBTTagCompound owned, AutoCloseable input) {
            this.sequence = sequence;
            this.owned = owned;
            this.input = input;
            header = displayHeader(owned);
        }

        public void close() {
            if (encoding != null) {
                encoding.cancel();
                encoding.retireSource(input);
            } else closeReservation(input);
            input = null;
            owned = null;
            if (output != null) output.close();
            output = null;
            if (headerBytes != null) headerBytes.close();
            headerBytes = null;
        }
    }

    private static void closeReservation(AutoCloseable reservation) {
        if (reservation == null) return;
        try {
            reservation.close();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void sendLibraryReply(EntityPlayerMP player, NBTTagCompound tag) {
        if (Boolean.getBoolean("gtmb.hologram.librarySocketSmoke"))
            HologramLibrarySocketServerProbe.observe(player, tag);
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
            Consumer<NBTTagCompound> current = libraryReceiver;
            if (current != null) current.accept(message.tag);
            Consumer<NBTTagCompound> probe = libraryProbeReceiver;
            if (probe != null) probe.accept((NBTTagCompound) message.tag.copy());
            return null;
        }
    }

    public static void sendState(EntityPlayerMP player, NBTTagCompound tag) {
        if (channel == null) return;
        // Configuration replies have no workspace nonce and must not replace its snapshot outbox.
        if (tag.getBoolean("voucherEditor")) {
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
            || !box.session.equals(session)
            || cancelled
            || owner != null && box.owner != owner) {
            if (box != null) retire(box);
            box = new Outbox(player, session, owner);
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
        completeOwnedState(ticket, owned, valid, ready, null);
    }

    static void completeOwnedState(SnapshotTicket ticket, NBTTagCompound owned,
        java.util.function.BooleanSupplier valid, Runnable ready, HologramTransportPageLayout layout) {
        completeOwnedState(ticket, owned, valid, ready, layout, null);
    }

    static void completeOwnedState(SnapshotTicket ticket, NBTTagCompound owned,
        java.util.function.BooleanSupplier valid, Runnable ready, HologramTransportPageLayout layout,
        HologramSnapshotModels.Source models) {
        if (!ticket.current()) {
            if (layout != null) layout.close();
            if (models != null) models.close();
            return;
        }
        ticket.snapshot.layout = layout;
        ticket.snapshot.models = models;
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
        if (ticket.box.peer.supportsSegments && progressConnected(ticket.box.player)) {
            sendState(ticket.box.player, queuedSnapshotProgress(ticket.box.session, ticket.snapshot.sequence, owned));
        }
    }

    static NBTTagCompound queuedSnapshotProgress(String session, long sequence, NBTTagCompound owned) {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("progressOnly", true);
        NBTTagCompound progress = new NBTTagCompound();
        progress.setString("scope", "server");
        progress.setString("phase", "snapshot.queued");
        progress.setString("detail", "Snapshot queued; encoding and transfer are pending.");
        progress.setString("session", session);
        progress.setLong("transportSequence", sequence);
        progress.setLong("uiSequence", owned.getLong("uiSequence"));
        progress.setLong("planRevision", owned.getLong("planRevision"));
        progress.setLong("clientTaskToken", owned.getLong("blueprintClientTaskToken"));
        progress.setBoolean("complete", false);
        receipt.setTag("progress", progress);
        return receipt;
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
        return !state.getBoolean("detailOnly") && !state.hasKey("cells", 9)
            && !"cellDelta".equals(state.getString("snapshotKind"))
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

        long encodingBytes() {
            return snapshot.encoding == null ? 0 : snapshot.encoding.rawBytes;
        }

        boolean current() {
            return outgoing.get(box.player.getUniqueID()) == box && box.snapshots.contains(snapshot);
        }
    }

    private static void retire(Outbox box) {
        for (OutgoingSnapshot snapshot : box.snapshots) {
            if (snapshot.offset == 0) box.peer.inbox.release(snapshot.sequence);
            if (snapshot.encoding != null) {
                snapshot.encoding.cancel();
                snapshot.encoding.retireSource(snapshot.sourceReservation);
            } else closeReservation(snapshot.sourceReservation);
            snapshot.sourceReservation = null;
            snapshot.owned = null;
            snapshot.valid = null;
            snapshot.ready = null;
            snapshot.bytes = null;
            if (snapshot.output != null) snapshot.output.close();
            snapshot.output = null;
            if (snapshot.layout != null) snapshot.layout.close();
            snapshot.layout = null;
            if (snapshot.models != null) snapshot.models.close();
            snapshot.models = null;
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

    /** Admission/preparation failure is a small control, not a replacement snapshot or a presentation rollback. */
    static void sendPresetActivationFailure(EntityPlayerMP player, String session, long uiSequence, long revision,
        long clientTask, String message) {
        if (channel == null || !progressConnected(player)) return;
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("transportError", true);
        state.setString("session", session);
        state.setLong("uiSequence", uiSequence);
        state.setLong("planRevision", revision);
        state.setLong("clientTaskToken", clientTask);
        state.setString("errorCode", "preset.activation");
        state.setString("errorMessage", message);
        channel.sendTo(new State(state), player);
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
            if (clientTask > 0) state.setLong("clientTaskToken", clientTask);
            if (uiSequence != Long.MIN_VALUE) state.setLong("uiSequence", uiSequence);
            state.setString("errorCode", code);
            state.setString("errorMessage", transportErrorMessage(code));
            channel.sendTo(new State(state), player);
        }
    }

    private static String transportErrorMessage(String code) {
        if ("snapshot.metadata.capacity".equals(code))
            return "Snapshot metadata exceeds the working-memory budget; the previous preview was retained.";
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
                    .submit(snapshot.owned, box.peer.supportsSegments ? box.peer.segments : null, snapshot.layout);
                if (snapshot.encoding != null) snapshot.layout = null;
                continue;
            }
            HologramSnapshotEncoding.Job encoding = snapshot.encoding;
            if (!encoding.done) {
                encoding.resume();
                continue;
            }
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
            snapshot.output = encoding.takeOutput();
            snapshot.bytes = snapshot.output.bytes;
            if (encoding.segmentHashes != null) box.peer.segments.offered(encoding.segmentHashes);
            snapshot.libraryHash = snapshot.libraryInitial ? encoding.hash : null;
            box.pendingBytes += snapshot.bytes.length;
            if (snapshot.owned.hasKey("cells", 9)) HologramRuntimeMetrics
                .add(HologramRuntimeMetrics.Counter.FULL_CELLS_COMPRESSED_BYTES, snapshot.bytes.length);
            if (snapshot.models != null) {
                snapshot.models.publish();
                snapshot.models = null;
            }
            snapshot.ready.run();
            HologramWorkspace.commitPresentation(box.owner, snapshot.owned);
            snapshot.ready = null;
            closeReservation(snapshot.sourceReservation);
            snapshot.sourceReservation = null;
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

    private static boolean writable(EntityPlayerMP player) {
        if (!progressConnected(player)) return false;
        io.netty.channel.Channel connection = player.playerNetServerHandler.netManager.channel();
        return connection == null || connection.isWritable();
    }

    private static boolean sendData(EntityPlayerMP player, int length, java.util.function.Supplier<IMessage> message) {
        HologramProcessGovernor.Lease lease = HologramProcessGovernor.tryRetain("snapshot.wire", 3L * length + 256);
        if (lease == null) return false;
        try {
            IMessage packet = message.get();
            if (channel.getClass() != SimpleNetworkWrapper.class
                || player.playerNetServerHandler.netManager.channel() == null) {
                channel.sendTo(packet, player);
                lease.close();
            } else {
                lease.transfer();
                player.playerNetServerHandler.netManager.scheduleOutboundPacket(
                    channel.getPacketFrom(packet),
                    (io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<? super Void>>) future -> lease
                        .close());
            }
            return true;
        } catch (RuntimeException | Error failed) {
            lease.close();
            throw failed;
        }
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
        final TransportPeer peer;
        final Deque<OutgoingSnapshot> snapshots = new ArrayDeque<>();
        long pendingBytes;

        Outbox(EntityPlayerMP player, String session, HologramSession owner) {
            this.player = player;
            world = player.worldObj;
            dimension = player.dimension;
            this.session = session;
            this.owner = owner;
            peer = transportPeer(player, session);
        }
    }

    static final class InboxWindow {

        private final java.util.Set<Long> frames = new java.util.HashSet<>();
        volatile boolean enabled;

        synchronized boolean start(long sequence) {
            if (!enabled || frames.contains(sequence)) return true;
            if (frames.size() == 2) return false;
            frames.add(sequence);
            return true;
        }

        synchronized void release(long sequence) {
            frames.remove(sequence);
        }
    }

    /** Exact sent-frame correlation survives credit disposal, but never survives a connection. */
    static final class SnapshotRecoveryWindow {

        private final Map<Long, String> sent = new LinkedHashMap<>();
        private String pending;
        private long lastRecoveryTick = Long.MIN_VALUE;

        void sent(long sequence, String session) {
            if (sequence < 0 || session == null || session.isEmpty()) return;
            if (sent.size() >= 64 && !sent.containsKey(sequence)) sent.remove(
                sent.keySet()
                    .iterator()
                    .next());
            sent.put(sequence, session);
        }

        boolean request(long sequence, String currentSession) {
            String session = sent.remove(sequence);
            if (session == null || !session.equals(currentSession)) return false;
            pending = session;
            return true;
        }

        boolean poll(String session, long tick) {
            if (pending == null) return false;
            if (!pending.equals(session)) {
                pending = null;
                return false;
            }
            if (lastRecoveryTick != Long.MIN_VALUE && tick - lastRecoveryTick < 20) return false;
            pending = null;
            lastRecoveryTick = tick;
            return true;
        }
    }

    private static final class TransportPeer {

        volatile EntityPlayerMP player;
        final net.minecraft.network.NetworkManager connection;
        final HologramSnapshotSegments.Peer segments = new HologramSnapshotSegments.Peer();
        final InboxWindow inbox = new InboxWindow();
        final SnapshotRecoveryWindow recovery = new SnapshotRecoveryWindow();
        String session = "";
        boolean supportsSegments, supportsCompactRows, supportsAuthorDocuments;

        TransportPeer(EntityPlayerMP player) {
            this.player = player;
            connection = player.playerNetServerHandler == null ? null : player.playerNetServerHandler.netManager;
        }
    }

    static boolean segmentedPeer(EntityPlayerMP player) {
        TransportPeer peer = player == null ? null : transportPeers.get(player.getUniqueID());
        return peer != null && peer.player == player && peer.supportsSegments;
    }

    static boolean compactSnapshotPeer(EntityPlayerMP player) {
        TransportPeer peer = player == null ? null : transportPeers.get(player.getUniqueID());
        return peer != null && peer.player == player && peer.supportsSegments && peer.supportsCompactRows;
    }

    static boolean localAuthorPeer(EntityPlayerMP player) {
        if (player == null || player.getUniqueID() == null) return false;
        TransportPeer peer = transportPeers.get(player.getUniqueID());
        return peer != null && peer.player == player
            && peer.supportsAuthorDocuments
            && player.playerNetServerHandler != null
            && peer.connection == player.playerNetServerHandler.netManager;
    }

    private static TransportPeer transportPeer(EntityPlayerMP player, String session) {
        TransportPeer peer = transportPeers.get(player.getUniqueID());
        if (peer == null || peer.player != player) {
            net.minecraft.network.NetworkManager connection = player.playerNetServerHandler == null ? null
                : player.playerNetServerHandler.netManager;
            if (peer != null && connection != null && peer.connection == connection) peer.player = player;
            else {
                peer = new TransportPeer(player);
                transportPeers.put(player.getUniqueID(), peer);
            }
        }
        if (!peer.session.equals(session)) {
            peer.segments.clear();
            peer.session = session;
        }
        return peer;
    }

    private static final class OutgoingSnapshot {

        final long sequence;
        byte[] bytes, libraryHash;
        HologramSnapshotBytes output;
        HologramTransportPageLayout layout;
        HologramSnapshotModels.Source models;
        AutoCloseable sourceReservation;
        boolean libraryInitial, failed;
        long advancedAt = System.nanoTime(), observedRawBytes;
        NBTTagCompound owned;
        java.util.function.BooleanSupplier valid;
        Runnable ready;
        HologramSnapshotEncoding.Job encoding;
        int offset, index;
        boolean queueNotified;

        OutgoingSnapshot(long sequence) {
            this.sequence = sequence;
        }

        void initialize(NBTTagCompound owned, java.util.function.BooleanSupplier valid, Runnable ready) {
            this.owned = owned;
            advancedAt = System.nanoTime();
            this.valid = valid;
            this.ready = ready;
            libraryInitial = owned.getBoolean("libraryInitialSnapshot");
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
            HologramAttachmentUpload.tick();
            HologramSnapshotPreparation.pruneRetired();
            HologramSnapshotDetails.tick();
            tickEncoding();
            transportPeers.values()
                .removeIf(peer -> !progressConnected(peer.player));
            cutControlPeers.values()
                .removeIf(player -> !progressConnected(player));
            for (TransportPeer peer : transportPeers.values()) {
                HologramSession owner = HologramWorkspace.find(peer.player.getUniqueID());
                if (owner == null || owner.player != peer.player
                    || owner.cancelled
                    || peer.player.playerNetServerHandler.netManager != peer.connection) {
                    peer.recovery.poll("", 0);
                    continue;
                }
                if (peer.recovery.poll(owner.id, peer.player.worldObj.getTotalWorldTime())) {
                    owner.localEdits.invalidate();
                    HologramSnapshots.send(owner);
                }
            }
            transportBudget.begin(System.nanoTime());
            cutFirst = !cutFirst;
            rotate(outgoing);
            rotate(cutOutgoing);
            rotate(displayOutgoing);
            int first = dataLane++ % 3;
            for (int lane = 0; lane < 3; lane++) {
                int current = (first + lane) % 3;
                if (current == 0) tickOrdinary();
                else if (current == 1) tickCuts();
                else tickDisplays();
            }
            HologramSnapshotModels.prune();
        }

        private void tickDisplays() {
            java.util.Iterator<DisplayOutbox> boxes = displayOutgoing.values()
                .iterator();
            while (boxes.hasNext()) {
                DisplayOutbox box = boxes.next();
                if (!progressConnected(box.player) || box.world != box.player.worldObj) {
                    box.close();
                    boxes.remove();
                    continue;
                }
                DisplaySnapshot row = box.rows.peekFirst();
                if (row == null) {
                    boxes.remove();
                    continue;
                }
                if (row.output == null) {
                    if (row.encoding == null) row.encoding = HologramSnapshotEncoding.submit(row.owned);
                    if (row.encoding == null) continue;
                    if (!row.encoding.done) {
                        row.encoding.resume();
                        continue;
                    }
                    if (row.encoding.failure != null || row.encoding.cancelled || row.encoding.bytes == null) {
                        displayFailure(box.player, row.header, "busy");
                        row.close();
                        box.rows.removeFirst();
                        continue;
                    }
                    row.output = row.encoding.takeOutput();
                    closeReservation(row.input);
                    row.input = null;
                    row.owned = null;
                    try {
                        row.headerBytes = HologramSnapshotCodec.compressOwned(row.header, () -> true, n -> {});
                    } catch (IOException busy) {
                        displayFailure(box.player, row.header, "busy");
                        row.close();
                        box.rows.removeFirst();
                        continue;
                    }
                    if (row.headerBytes.bytes.length > 8192) {
                        displayFailure(box.player, row.header, "header");
                        row.close();
                        box.rows.removeFirst();
                        continue;
                    }
                }
                int length = Math.min(DISPLAY_CHUNK_BYTES, row.output.bytes.length - row.offset);
                TransportPeer peer = transportPeers.get(box.player.getUniqueID());
                if (row.offset == 0 && peer != null && !peer.inbox.start(row.sequence)) continue;
                if (!writable(box.player) || !transportBudget.tryAcquire(
                    box.player.getUniqueID(),
                    length + row.headerBytes.bytes.length + 24,
                    System.nanoTime())) continue;
                DisplaySnapshot frame = row;
                if (!sendData(
                    box.player,
                    length + row.headerBytes.bytes.length + 24,
                    () -> new DisplayFragment(
                        frame.sequence,
                        frame.output.bytes.length,
                        frame.index,
                        Arrays.copyOfRange(frame.output.bytes, frame.offset, frame.offset + length),
                        frame.headerBytes.bytes)))
                    continue;
                row.offset += length;
                row.index++;
                if (row.offset == row.output.bytes.length) {
                    row.close();
                    box.rows.removeFirst();
                }
            }
        }

        private <T> void rotate(Map<UUID, T> queue) {
            if (queue.size() < 2) return;
            UUID first = queue.keySet()
                .iterator()
                .next();
            T value = queue.remove(first);
            queue.put(first, value);
        }

        private void tickCuts() {
            Iterator<CutOutbox> cuts = cutOutgoing.values()
                .iterator();
            while (cuts.hasNext()) {
                CutOutbox box = cuts.next();
                if (box.player.playerNetServerHandler == null || box.player.playerNetServerHandler.netManager == null
                    || !box.player.playerNetServerHandler.netManager.isChannelOpen()) {
                    retireCut(box);
                    cuts.remove();
                    continue;
                }
                CutSnapshot snapshot = box.snapshots.peekFirst();
                if (snapshot == null) {
                    cuts.remove();
                    continue;
                }
                if (snapshot.bytes == null) {
                    if (snapshot.encoding == null) snapshot.encoding = HologramSnapshotEncoding.submit(snapshot.owned);
                    if (snapshot.encoding == null || !snapshot.encoding.done) continue;
                    if (snapshot.encoding.failure != null || snapshot.encoding.cancelled
                        || snapshot.encoding.bytes == null) {
                        // A preview can fail independently; retain request correlation for the explicit failure.
                        NBTTagCompound failure = new NBTTagCompound();
                        failure.setString("requestId", snapshot.owned.getString("requestId"));
                        failure.setBoolean("success", false);
                        failure.setString("code", "transport_failed");
                        failure.setString(
                            "message",
                            "Cut view encoding failed; synchronize to retrieve the preserved world result.");
                        snapshot.cancel();
                        box.snapshots.removeFirst();
                        if (cutControlPeers.get(box.player.getUniqueID()) == box.player)
                            sendCutState(box.player, failure);
                        else box.snapshots.addFirst(new CutSnapshot(snapshotSequence.incrementAndGet(), failure));
                        continue;
                    }
                    snapshot.output = snapshot.encoding.takeOutput();
                    snapshot.bytes = snapshot.output.bytes;
                    snapshot.owned = null;
                    box.pendingBytes += snapshot.bytes.length;
                }
                int end = Math.min(snapshot.bytes.length, snapshot.offset + HologramSnapshotAssembly.CHUNK_BYTES);
                if (!writable(box.player) || !transportBudget
                    .tryAcquire(box.player.getUniqueID(), end - snapshot.offset + 20, System.nanoTime())) continue;
                CutSnapshot current = snapshot;
                if (!sendData(
                    box.player,
                    end - snapshot.offset + 20,
                    () -> new CutFragment(
                        current.sequence,
                        current.bytes.length,
                        current.index,
                        Arrays.copyOfRange(current.bytes, current.offset, end))))
                    continue;
                snapshot.index++;
                snapshot.offset = end;
                if (end == snapshot.bytes.length) {
                    box.pendingBytes -= snapshot.bytes.length;
                    snapshot.bytes = null;
                    if (snapshot.output != null) snapshot.output.close();
                    snapshot.output = null;
                    box.snapshots.removeFirst();
                    if (box.snapshots.isEmpty()) cuts.remove();
                }
            }
        }

        private void tickOrdinary() {
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
                    if (first.offset == 0 && !box.peer.inbox.start(first.sequence)) continue;
                    int length = Math.min(HologramLibraryProtocol.CHUNK_BYTES, first.bytes.length - first.offset);
                    if (!writable(box.player)
                        || !transportBudget.tryAcquire(box.player.getUniqueID(), length + 56, System.nanoTime()))
                        continue;
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
                    OutgoingSnapshot current = first;
                    if (!sendData(
                        box.player,
                        length + 56,
                        () -> new LibrarySnapshotFragment(
                            current.sequence,
                            current.bytes.length,
                            current.index,
                            current.libraryHash,
                            Arrays.copyOfRange(current.bytes, current.offset, current.offset + length))))
                        continue;
                    if (first.offset == 0) box.peer.recovery.sent(first.sequence, box.session);
                    first.index++;
                    first.offset += length;
                    if (first.offset == first.bytes.length) {
                        HologramLibraryFacade.cancelDownloadAllowance(HologramLibraryFacade.actorId(box.player));
                        box.pendingBytes -= first.bytes.length;
                        first.bytes = null;
                        if (first.output != null) first.output.close();
                        first.output = null;
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
                    retire(box);
                    iterator.remove();
                    continue;
                }
                OutgoingSnapshot snapshot = box.snapshots.peekFirst();
                if (snapshot == null) {
                    iterator.remove();
                    continue;
                }
                if (snapshot.bytes == null || snapshot.libraryInitial) continue;
                if (snapshot.offset == 0 && !box.peer.inbox.start(snapshot.sequence)) continue;
                int end = Math.min(snapshot.bytes.length, snapshot.offset + HologramSnapshotAssembly.CHUNK_BYTES);
                if (!writable(box.player) || !transportBudget
                    .tryAcquire(box.player.getUniqueID(), end - snapshot.offset + 20, System.nanoTime())) continue;
                OutgoingSnapshot current = snapshot;
                if (!sendData(
                    box.player,
                    end - snapshot.offset + 20,
                    () -> new Fragment(
                        current.sequence,
                        current.bytes.length,
                        current.index,
                        Arrays.copyOfRange(current.bytes, current.offset, end))))
                    continue;
                if (snapshot.offset == 0) box.peer.recovery.sent(snapshot.sequence, box.session);
                snapshot.index++;
                snapshot.offset = end;
                if (end == snapshot.bytes.length) {
                    box.pendingBytes -= snapshot.bytes.length;
                    snapshot.bytes = null;
                    if (snapshot.output != null) snapshot.output.close();
                    snapshot.output = null;
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

    /** The handler and regression harness share the actual rejection/recovery boundary. No frame is queued here. */
    static HologramSnapshotAssembly.Complete acceptLibrarySnapshot(net.minecraft.network.NetworkManager connection,
        LibrarySnapshotFragment message) {
        if (connection == null || !connection.isChannelOpen()) return null;
        try {
            synchronized (libraryTransportLock) {
                return librarySnapshots(connection).acceptReserved(
                    message.sequence,
                    message.total,
                    message.index,
                    message.bytes,
                    message.hash,
                    System.nanoTime());
            }
        } catch (HologramSnapshotAssembly.AdmissionException full) {
            boolean retryable = snapshotAdmissionCanRetry(message.total);
            if (retryable) requestSnapshotAdmissionRecovery(connection, message.sequence, message.total);
            receiveAdmissionFailure(
                "snapshot",
                message.sequence,
                retryable
                    ? "The shared snapshot byte budget is full; recovery requested. On an older server, reopen the view to retry."
                    : "This snapshot exceeds the configured receive budget; reduce its size or increase that budget before retrying.");
            return null;
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
            HologramSnapshotAssembly.Complete admitted = acceptLibrarySnapshot(
                context.getClientHandler()
                    .getNetworkManager(),
                message);
            if (admitted == null) return null;
            java.util.function.BiConsumer<HologramSnapshotAssembly.Complete, Long> frames = frameReceiver;
            if (frames != null) {
                frames.accept(admitted, message.sequence);
                return null;
            }
            // External/old receiver implementations retain their original fragment-envelope API.
            byte[] complete = admitted.bytes;
            admitted.close();
            Consumer<NBTTagCompound> current = receiver;
            try {
                if (current == null) return null;
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
            } finally {
                admitted.close();
            }
            return null;
        }
    }

    public static final class DisplayFragment implements IMessage {

        private Fragment fragment = new Fragment();
        private byte[] header;

        public DisplayFragment() {
            fragment.chunkBytes = DISPLAY_CHUNK_BYTES;
        }

        DisplayFragment(long sequence, int total, int index, byte[] bytes, byte[] header) {
            fragment = new Fragment(sequence, total, index, bytes);
            fragment.chunkBytes = DISPLAY_CHUNK_BYTES;
            this.header = header;
        }

        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() < 24) throw new IllegalArgumentException("display fragment header");
            int length = buf.readInt();
            if (length < 1 || length > 8192 || length > buf.readableBytes() - 20)
                throw new IllegalArgumentException("display fragment header budget");
            header = new byte[length];
            buf.readBytes(header);
            fragment.fromBytes(buf);
        }

        public void toBytes(ByteBuf buf) {
            buf.writeInt(header.length);
            buf.writeBytes(header);
            fragment.toBytes(buf);
        }
    }

    public static final class DisplayFragmentHandler implements IMessageHandler<DisplayFragment, IMessage> {

        public IMessage onMessage(DisplayFragment message, MessageContext context) {
            Consumer<NBTTagCompound> current = receiver;
            if (current == null) return null;
            try {
                NBTTagCompound header = HologramSnapshotCodec.decode(message.header, 65536, 262144);
                Fragment frame = message.fragment;
                NBTTagCompound envelope = new NBTTagCompound();
                envelope.setBoolean("transportFragment", true);
                envelope.setBoolean("transportDisplay", true);
                envelope.setTag("transportHeader", header);
                envelope.setLong("transportSequence", frame.sequence);
                envelope.setInteger("transportTotal", frame.total);
                envelope.setInteger("transportIndex", frame.index);
                envelope.setByteArray("transportBytes", frame.bytes);
                current.accept(envelope);
            } catch (IOException malformed) {
                throw new IllegalArgumentException("display fragment metadata", malformed);
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
        private int chunkBytes = HologramSnapshotAssembly.CHUNK_BYTES;

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
                || index >= (total - 1) / chunkBytes + 1
                || length <= 0
                || length > chunkBytes
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
            net.minecraft.network.NetworkManager connection = ctx.getClientHandler()
                .getNetworkManager();
            synchronized (libraryTransportLock) {
                if (!connection.isChannelOpen()) return null;
                librarySnapshots(connection).supersede(msg.sequence);
            }
            if (receiver != null || transportReceiver != null) {
                NBTTagCompound envelope = new NBTTagCompound();
                envelope.setBoolean("transportFragment", true);
                envelope.setLong("transportSequence", msg.sequence);
                envelope.setInteger("transportTotal", msg.total);
                envelope.setInteger("transportIndex", msg.index);
                envelope.setByteArray("transportBytes", msg.bytes);
                receiveTransportFragment(envelope, connection);
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

    public static final class Action extends Packet {

        @Override
        protected int expandedBudget() {
            return HologramAeProtocol.MAX_STACK_AGGREGATE;
        }

        public Action() {}

        Action(NBTTagCompound tag) {
            super(tag);
            this.tag.setInteger("clientSnapshotProtocol", 1);
            this.tag.setInteger("clientAuthorDocuments", 1);
            this.tag.setInteger(
                "clientSegmentProtocol",
                "false".equals(System.getProperty("gtmb.segment.transport")) ? 0 : HologramSnapshotSegments.VERSION);
            this.tag
                .setInteger("clientSnapshotRows", "false".equals(System.getProperty("gtmb.segment.transport")) ? 0 : 1);
            this.tag.setInteger("clientSnapshotInbox", frameReceiver == null ? 0 : 2);
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
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if ("ackTransportSegments".equals(msg.tag.getString("op")) && msg.tag.getBoolean("inboxConsumed")) {
                TransportPeer peer = transportPeers.get(player.getUniqueID());
                if (peer != null && peer.connection == ctx.getServerHandler().netManager && peer.inbox.enabled)
                    peer.inbox.release(msg.tag.getLong("transportSequence"));
                return null;
            }
            // Preserve an independent lane for disk receipts; different capabilities are never coalesced.
            boolean receipt = "ackBlueprintSaved".equals(msg.tag.getString("op"));
            HologramRequestGate gate = receipt ? receiptGate : actionGate;
            HologramRequestGate.Lease lease = gate.acquire(player);
            if (lease == null) {
                HologramBusyReplies.reject(player, msg.tag, HologramBusyReplies.Kind.ACTION);
                return null;
            }
            HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.ACTION_DEPTH, actionGate.size());
            HologramRuntimeMetrics.highWater(HologramRuntimeMetrics.Counter.ACTION_HIGH_WATER, actionGate.size());
            // Packet decompression is already bounded. Large index selections must not be rejected
            // because their textual representation exceeds an unrelated string budget.
            net.minecraft.world.World world = player.worldObj;
            net.minecraft.network.NetworkManager connection = ctx.getServerHandler().netManager;
            ServerTaskScheduler.scheduleServerTask(
                () -> runQueuedAction(
                    player,
                    world,
                    lease,
                    () -> { if (player.playerNetServerHandler.netManager == connection) dispatch(player, msg.tag); }));
            return null;
        }
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

    private static void dispatch(EntityPlayerMP player, NBTTagCompound tag) {
        if (HologramAuthorMaterialCatalog.OPERATION.equals(tag.getString("op"))
            || HologramAuthorMaterialCatalog.SETTINGS_OPERATION.equals(tag.getString("op"))) {
            HologramAuthorMaterialCatalog.query(player, tag);
            return;
        }
        TransportPeer peer = transportPeers.get(player.getUniqueID());
        if (peer == null || peer.player != player) peer = transportPeer(player, "");
        if (tag.getInteger("clientSegmentProtocol") == HologramSnapshotSegments.VERSION
            && !"false".equals(System.getProperty("gtmb.segment.transport"))) peer.supportsSegments = true;
        if (peer.supportsSegments && tag.getInteger("clientSnapshotRows") == 1) peer.supportsCompactRows = true;
        if (tag.getInteger("clientSnapshotInbox") == 2) peer.inbox.enabled = true;
        if (tag.getInteger("clientAuthorDocuments") == 1) peer.supportsAuthorDocuments = true;
        if ("requestFullSnapshot".equals(tag.getString("op")) && tag.getBoolean("recoverTransportSequence")) {
            HologramSession owner = HologramWorkspace.find(player.getUniqueID());
            if (owner != null && owner.player == player
                && !owner.cancelled
                && peer.connection == player.playerNetServerHandler.netManager
                && tag.hasKey("transportSequence", 4))
                peer.recovery.request(tag.getLong("transportSequence"), owner.id);
            return;
        }
        if ("requestSnapshotModel".equals(tag.getString("op"))) {
            HologramSession owner = HologramWorkspace.find(player.getUniqueID());
            if (owner != null && owner.player == player
                && owner.id.equals(tag.getString("session"))
                && peer.supportsSegments) HologramSnapshotModels.request(owner, tag);
            return;
        }
        if ("requestSnapshotDetail".equals(tag.getString("op"))) {
            HologramSession owner = HologramWorkspace.find(player.getUniqueID());
            if (owner != null && owner.player == player
                && owner.id.equals(tag.getString("session"))
                && peer.supportsSegments) HologramSnapshotDetails.request(owner, tag);
            return;
        }
        if ("ackTransportSegments".equals(tag.getString("op"))) {
            if (peer.session.equals(tag.getString("session"))) peer.segments.acknowledge(tag.getTagList("segments", 8));
            return;
        }
        if (tag.getBoolean("resetTransportSegments") && peer.session.equals(tag.getString("session")))
            peer.segments.clear();
        String action = tag.getString("action");
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
            if (msg.tag.hasKey("authorMaterialCatalog", 10)) {
                java.util.function.BiConsumer<NBTTagCompound, net.minecraft.network.NetworkManager> current = authorMaterialReceiver;
                if (current != null && ctx != null) current.accept(
                    msg.tag.getCompoundTag("authorMaterialCatalog"),
                    ctx.getClientHandler()
                        .getNetworkManager());
                return null;
            }
            if (msg.tag.hasKey("cutControl", 10)) {
                observeCutCapabilities(msg.tag.getCompoundTag("cutControl"));
                Consumer<NBTTagCompound> current = cutReceiver;
                if (current != null) current.accept(msg.tag.getCompoundTag("cutControl"));
                return null;
            }
            if (msg.tag.getBoolean("progressOnly")) {
                observeProgress(msg.tag.getCompoundTag("progress"));
                return null;
            }
            if (msg.tag.hasKey("progress", 10)) observeProgress(msg.tag.getCompoundTag("progress"));
            if (msg.tag.getBoolean("cancelled") || msg.tag.getBoolean("transportError")) {
                synchronized (libraryTransportLock) {
                    if (ctx != null && libraryConnection == ctx.getClientHandler()
                        .getNetworkManager()) librarySnapshots.clear();
                }
            }
            Consumer<NBTTagCompound> current = receiver;
            if (current != null) current.accept(msg.tag);
            return null;
        }
    }
}
