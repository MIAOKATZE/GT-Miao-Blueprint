package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    private static final int MAX_BYTES = 1048576;
    private static final AtomicLong snapshotSequence = new AtomicLong();
    private static final Map<UUID, Outbox> outgoing = new LinkedHashMap<>();
    private static final HologramLibrarySnapshotAssembly librarySnapshots = new HologramLibrarySnapshotAssembly();
    private static int publicSnapshotCursor;

    private HologramNetwork() {}

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("gtmb_hologram");
        channel.registerMessage(ServerHandler.class, Action.class, 0, Side.SERVER);
        channel.registerMessage(ClientHandler.class, State.class, 1, Side.CLIENT);
        channel.registerMessage(FragmentHandler.class, Fragment.class, 2, Side.CLIENT);
        channel.registerMessage(LibraryServerHandler.class, LibraryRequest.class, 3, Side.SERVER);
        channel.registerMessage(LibraryClientHandler.class, LibraryReply.class, 4, Side.CLIENT);
        channel.registerMessage(LibrarySnapshotHandler.class, LibrarySnapshotFragment.class, 5, Side.CLIENT);
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
            if (buf.readableBytes() < 4) throw new IllegalArgumentException("library header");
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
            } catch (IOException failure) {
                throw new IllegalArgumentException("library NBT", failure);
            }
        }

        public void toBytes(ByteBuf buf) {
            try {
                byte[] bytes = CompressedStreamTools.compress(tag);
                if (bytes.length > HologramLibraryProtocol.PACKET_BYTES)
                    throw new IllegalArgumentException("library packet budget");
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
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
        if (tag.getBoolean("capturePending")) return;
        try {
            byte[] bytes = HologramSnapshotCodec.compress(tag);
            if (bytes.length > HologramSnapshotAssembly.MAX_BYTES)
                throw new IllegalArgumentException("hologram snapshot byte budget");
            Outbox box = outgoing.get(player.getUniqueID());
            String session = tag.getString("session");
            if (box == null || box.player != player || !box.session.equals(session) || tag.getBoolean("cancelled")) {
                if (box != null && box.snapshots.peekFirst() != null && box.snapshots.peekFirst().libraryInitial)
                    HologramLibraryFacade.cancelDownloadAllowance(HologramLibraryFacade.actorId(box.player));
                box = new Outbox(player, session);
                outgoing.put(player.getUniqueID(), box);
            }
            OutgoingSnapshot previous = box.snapshots.peekLast();
            boolean replace = previous != null && previous.canReplace(tag);
            long replacedBytes = replace ? previous.bytes.length : 0;
            if (box.pendingBytes - replacedBytes + bytes.length > HologramSnapshotAssembly.MAX_BYTES)
                throw new IllegalArgumentException("hologram queued snapshot byte budget");
            if (replace) {
                box.snapshots.removeLast();
                box.pendingBytes -= previous.bytes.length;
            }
            box.snapshots.addLast(new OutgoingSnapshot(snapshotSequence.incrementAndGet(), bytes, tag));
            box.pendingBytes += bytes.length;
        } catch (IOException | RuntimeException e) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Hologram snapshot could not be queued", e);
            NBTTagCompound failure = new NBTTagCompound();
            failure.setBoolean("transportError", true);
            failure.setString("session", tag.getString("session"));
            channel.sendTo(new State(failure), player);
        }
    }

    private static final class Outbox {

        final EntityPlayerMP player;
        final net.minecraft.world.World world;
        final int dimension;
        final String session;
        final Deque<OutgoingSnapshot> snapshots = new ArrayDeque<>();
        long pendingBytes;

        Outbox(EntityPlayerMP player, String session) {
            this.player = player;
            world = player.worldObj;
            dimension = player.dimension;
            this.session = session;
        }
    }

    private static final class OutgoingSnapshot {

        final long sequence;
        final byte[] bytes;
        final long uiSequence, batchSequence, revision;
        final String phase;
        final int job;
        final boolean opening;
        final boolean libraryInitial;
        final byte[] libraryHash;
        int offset, index;
        boolean queueNotified;

        OutgoingSnapshot(long sequence, byte[] bytes, NBTTagCompound tag) {
            this.sequence = sequence;
            this.bytes = bytes;
            uiSequence = tag.getLong("uiSequence");
            batchSequence = tag.getLong("batchSeq");
            revision = tag.getLong("planRevision");
            phase = tag.getString("phase");
            job = tag.getInteger("job");
            opening = tag.getBoolean("open") || tag.getBoolean("hints") || tag.getBoolean("settingsOnly");
            libraryInitial = tag.getBoolean("libraryInitialSnapshot");
            libraryHash = libraryInitial ? HologramLibraryProtocol.digest()
                .digest(bytes) : null;
        }

        boolean canReplace(NBTTagCompound tag) {
            return offset == 0 && !opening
                && !tag.getBoolean("open")
                && !tag.getBoolean("hints")
                && !tag.getBoolean("settingsOnly")
                && uiSequence == tag.getLong("uiSequence")
                && batchSequence == tag.getLong("batchSeq")
                && revision == tag.getLong("planRevision")
                && phase.equals(tag.getString("phase"))
                && job == tag.getInteger("job");
        }
    }

    /** Main-thread FIFO: one bounded fragment per player and tick, including every ACK snapshot. */
    public static final class TransportTicks {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            // Rotate only the initial file-like hydration queues; normal interaction snapshots retain their FIFO.
            java.util.List<Outbox> initial = new java.util.ArrayList<>();
            for (Outbox box : outgoing.values()) {
                OutgoingSnapshot first = box.snapshots.peekFirst();
                if (first != null && first.libraryInitial) initial.add(box);
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
                if (snapshot.libraryInitial) continue;
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

    /** Dedicated library initialization uses 1 KiB slices while the legacy snapshot framing stays compatible. */
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
            if (buf.readableBytes() < 52) throw new IllegalArgumentException("library snapshot header");
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
        }

        public void toBytes(ByteBuf buf) {
            buf.writeLong(sequence);
            buf.writeInt(total);
            buf.writeInt(index);
            buf.writeBytes(hash);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    public static final class LibrarySnapshotHandler implements IMessageHandler<LibrarySnapshotFragment, IMessage> {

        public IMessage onMessage(LibrarySnapshotFragment message, MessageContext context) {
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
        }

        public void toBytes(ByteBuf buf) {
            buf.writeLong(sequence);
            buf.writeInt(total);
            buf.writeInt(index);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    public static final class FragmentHandler implements IMessageHandler<Fragment, IMessage> {

        public IMessage onMessage(Fragment msg, MessageContext ctx) {
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
            // Packet decompression is already bounded. Large index selections must not be rejected
            // because their textual representation exceeds an unrelated string budget.
            ServerTaskScheduler.scheduleServerTask(() -> dispatch(player, msg.tag));
            return null;
        }
    }

    private static void dispatch(EntityPlayerMP player, NBTTagCompound tag) {
        String action = tag.getString("action");
        if ("beginAreaSelection".equals(action) || "finishAreaSelection".equals(action)
            || "cancelAreaSelection".equals(action)
            || "areaPoint".equals(action)
            || "confirmVirtualPosition".equals(action)
            || "unlockVirtualPosition".equals(action)
            || "nudgeVirtualPosition".equals(action)
            || "quickPlaceLastBlueprint".equals(action)
            || "cancelVirtualPlacement".equals(action)) {
            if (!com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem()))
                return;
            if ("beginAreaSelection".equals(action)) HologramService.beginAreaSelection(player);
            else if ("finishAreaSelection".equals(action)) HologramService.finishAreaSelection(player, tag);
            else if ("cancelAreaSelection".equals(action))
                HologramService.cancelAreaSelection(player, tag.getString("session"));
            else if ("confirmVirtualPosition".equals(action)) {
                HologramService.confirmWorldPosition(player, tag);
            } else if ("unlockVirtualPosition".equals(action)) {
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
            if (msg.tag.getBoolean("cancelled") || msg.tag.getBoolean("transportError")) librarySnapshots.clear();
            Consumer<NBTTagCompound> current = receiver;
            if (current != null) current.accept(msg.tag);
            return null;
        }
    }
}
