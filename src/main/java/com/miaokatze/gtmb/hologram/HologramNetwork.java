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
    private static volatile Consumer<NBTTagCompound> interactionSender;
    private static volatile Consumer<NBTTagCompound> libraryReceiver;
    private static volatile Consumer<NBTTagCompound> libraryProbeReceiver;
    private static volatile Consumer<NBTTagCompound> cutReceiver;
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
    private static final int MAX_BYTES = 1048576;
    private static final AtomicLong snapshotSequence = new AtomicLong();
    private static final Map<UUID, Outbox> outgoing = new LinkedHashMap<>();
    private static final HologramLibrarySnapshotAssembly librarySnapshots = new HologramLibrarySnapshotAssembly();
    private static int publicSnapshotCursor;
    private static final HologramRequestGate actionGate = new HologramRequestGate(16, 256);
    private static final HologramRequestGate receiptGate = new HologramRequestGate(4, 128);

    private HologramNetwork() {}

    static void clearServerQueues() {
        actionGate.clear();
        receiptGate.clear();
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
        HologramLibraryFacade.init();
        FMLCommonHandler.instance()
            .bus()
            .register(new TransportTicks());
    }

    public static void setClientReceiver(Consumer<NBTTagCompound> value) {
        receiver = value;
        librarySnapshots.reset();
    }

    public static void clearLibraryClientTransport() {
        librarySnapshots.reset();
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
        if (channel != null) channel.sendToServer(new Action(tag));
    }

    public static void setCutReceiver(Consumer<NBTTagCompound> value) {
        synchronized (cutTransportLock) {
            cutReceiver = value;
            cutAssembly = new HologramSnapshotAssembly();
            cutClientGeneration.incrementAndGet();
            cutDecoder.getQueue()
                .clear();
            HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.CUT_DECODER_DEPTH, 0);
        }
    }

    public static void clearCutClientTransport() {
        synchronized (cutTransportLock) {
            cutAssembly = new HologramSnapshotAssembly();
            cutClientGeneration.incrementAndGet();
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
        try {
            byte[] bytes = compressSnapshot(tag);
            CutOutbox box = cutOutgoing.get(player.getUniqueID());
            if (box == null || box.player != player) {
                box = new CutOutbox(player);
                cutOutgoing.put(player.getUniqueID(), box);
            }
            if (box.pendingBytes + bytes.length > HologramSnapshotAssembly.MAX_BYTES)
                throw new IOException("cut transport queue full");
            box.snapshots.addLast(new CutSnapshot(snapshotSequence.incrementAndGet(), bytes));
            box.pendingBytes += bytes.length;
        } catch (IOException failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut snapshot could not be queued", failure);
        }
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
        final byte[] bytes;
        int offset, index;

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
            HologramCutService.receive(context.getServerHandler().playerEntity, message.tag);
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

    public static final class CutFragmentHandler implements IMessageHandler<CutFragment, IMessage> {

        public IMessage onMessage(CutFragment message, MessageContext context) {
            synchronized (cutTransportLock) {
                Fragment fragment = message.fragment;
                observeFragment(
                    "cut",
                    fragment.sequence,
                    fragment.total,
                    fragment.index,
                    fragment.bytes.length,
                    HologramSnapshotAssembly.CHUNK_BYTES);
                byte[] bytes = cutAssembly
                    .accept(fragment.sequence, fragment.total, fragment.index, fragment.bytes, System.nanoTime());
                Consumer<NBTTagCompound> current = cutReceiver;
                long generation = cutClientGeneration.get();
                if (bytes != null && current != null) try {
                    cutDecoder.execute(() -> {
                        try {
                            NBTTagCompound state = HologramSnapshotCodec.decode(bytes);
                            synchronized (cutTransportLock) {
                                if (generation == cutClientGeneration.get() && current == cutReceiver)
                                    current.accept(state);
                            }
                        } catch (IOException failure) {
                            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut snapshot decode failed", failure);
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
                    com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut decoder queue is full", full);
                }
            }
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
            if (clientTask > 0) state.setLong("clientTaskToken", clientTask);
            if (uiSequence != Long.MIN_VALUE) state.setLong("uiSequence", uiSequence);
            state.setString("errorCode", code);
            state.setString("errorMessage", transportErrorMessage(code));
            channel.sendTo(new State(state), player);
        }
    }

    private static String transportErrorMessage(String code) {
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
                snapshot.encoding = HologramSnapshotEncoding.submit(snapshot.owned);
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
        final Deque<OutgoingSnapshot> snapshots = new ArrayDeque<>();
        long pendingBytes;

        Outbox(EntityPlayerMP player, String session, HologramSession owner) {
            this.player = player;
            world = player.worldObj;
            dimension = player.dimension;
            this.session = session;
            this.owner = owner;
        }
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
            HologramSnapshotPreparation.pruneRetired();
            tickEncoding();
            Iterator<CutOutbox> cuts = cutOutgoing.values()
                .iterator();
            while (cuts.hasNext()) {
                CutOutbox box = cuts.next();
                if (box.player.playerNetServerHandler == null || box.player.playerNetServerHandler.netManager == null
                    || !box.player.playerNetServerHandler.netManager.isChannelOpen()) {
                    cuts.remove();
                    continue;
                }
                CutSnapshot snapshot = box.snapshots.peekFirst();
                if (snapshot == null) {
                    cuts.remove();
                    continue;
                }
                int end = Math.min(snapshot.bytes.length, snapshot.offset + HologramSnapshotAssembly.CHUNK_BYTES);
                channel.sendTo(
                    new CutFragment(
                        snapshot.sequence,
                        snapshot.bytes.length,
                        snapshot.index++,
                        Arrays.copyOfRange(snapshot.bytes, snapshot.offset, end)),
                    box.player);
                snapshot.offset = end;
                if (end == snapshot.bytes.length) {
                    box.pendingBytes -= snapshot.bytes.length;
                    box.snapshots.removeFirst();
                    if (box.snapshots.isEmpty()) cuts.remove();
                }
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
                int end = Math.min(snapshot.bytes.length, snapshot.offset + HologramSnapshotAssembly.CHUNK_BYTES);
                channel.sendTo(
                    new Fragment(
                        snapshot.sequence,
                        snapshot.bytes.length,
                        snapshot.index++,
                        Arrays.copyOfRange(snapshot.bytes, snapshot.offset, end)),
                    box.player);
                snapshot.offset = end;
                if (end == snapshot.bytes.length) {
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

    public static final class Action extends Packet {

        @Override
        protected int expandedBudget() {
            return HologramAeProtocol.MAX_STACK_AGGREGATE;
        }

        public Action() {}

        Action(NBTTagCompound tag) {
            super(tag);
            this.tag.setInteger("clientSnapshotProtocol", 1);
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
            ServerTaskScheduler
                .scheduleServerTask(() -> runQueuedAction(player, world, lease, () -> dispatch(player, msg.tag)));
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
        String action = tag.getString("action");
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
            if (msg.tag.getBoolean("progressOnly")) {
                observeProgress(msg.tag.getCompoundTag("progress"));
                return null;
            }
            if (msg.tag.hasKey("progress", 10)) observeProgress(msg.tag.getCompoundTag("progress"));
            if (msg.tag.getBoolean("cancelled") || msg.tag.getBoolean("transportError")) librarySnapshots.clear();
            Consumer<NBTTagCompound> current = receiver;
            if (current != null) current.accept(msg.tag);
            return null;
        }
    }
}
