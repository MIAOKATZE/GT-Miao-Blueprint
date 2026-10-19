package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.netty.buffer.ByteBuf;

/** Attachment source data is fragmented below the vanilla C17 limit; only a complete verified request is dispatched. */
public final class HologramAttachmentUpload {

    public static final int CHUNK_BYTES = 16 * 1024, MAX_BYTES = 8 * 1024 * 1024;
    private static final int GLOBAL_BYTES = 32 * 1024 * 1024;
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(30);
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final ThreadPoolExecutor ENCODER = worker("GTMB attachment upload encoder", 2);
    private static final ThreadPoolExecutor DECODER = worker("GTMB attachment upload decoder", 4);
    private static final HologramRequestGate FRAGMENT_GATE = new HologramRequestGate(8, 128);
    private static final Map<UUID, Incoming> INCOMING = new HashMap<>();
    private static SimpleNetworkWrapper channel;
    private static volatile Outgoing outgoing;
    private static volatile String clientProgress = "";
    private static int reservedBytes;
    public static volatile long fragmentsSent, largestFragment;

    private HologramAttachmentUpload() {}

    private static ThreadPoolExecutor worker(String name, int depth) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(depth), task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    static void init(SimpleNetworkWrapper wrapper) {
        channel = wrapper;
    }

    public static String clientProgress() {
        return clientProgress;
    }

    static void send(NBTTagCompound action) {
        long generation = GENERATION.incrementAndGet();
        NBTTagCompound frozen = (NBTTagCompound) action.copy();
        frozen.setInteger("clientSnapshotProtocol", 1);
        outgoing = null;
        clientProgress = "准备图片/文字数据…";
        try {
            ENCODER.execute(() -> {
                try {
                    byte[] bytes = HologramSnapshotCodec
                        .compress(frozen, () -> GENERATION.get() == generation, n -> {});
                    if (bytes.length > MAX_BYTES) throw new IOException("附加源数据超过 8MiB，请减小图片");
                    Outgoing result = new Outgoing(
                        frozen.getString("session"),
                        frozen.getLong("uiSequence"),
                        generation,
                        bytes,
                        sha(bytes));
                    if (GENERATION.get() == generation) outgoing = result;
                } catch (Exception failure) {
                    if (GENERATION.get() == generation) {
                        clientProgress = "";
                        HologramNetwork.clientActionFailure(frozen, failure.getMessage());
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            clientProgress = "";
            HologramNetwork.clientActionFailure(frozen, "附加数据编码队列已满，请重试");
        }
    }

    static void clientTick() {
        Outgoing current = outgoing;
        if (current == null || channel == null || current.generation != GENERATION.get()) return;
        if (System.nanoTime() - current.lastAcknowledgedAt > TIMEOUT) {
            if (outgoing == current) outgoing = null;
            clientProgress = "";
            NBTTagCompound request = new NBTTagCompound();
            request.setString("session", current.session);
            request.setLong("uiSequence", current.ui);
            HologramNetwork.clientActionFailure(request, "附加数据上传长时间没有响应，请重新确认");
            return;
        }
        for (int count = 0; count < 4 && current.offset < current.bytes.length
            && current.offset / CHUNK_BYTES - current.acknowledged < 4; count++) {
            int end = Math.min(current.offset + CHUNK_BYTES, current.bytes.length);
            Fragment fragment = new Fragment(
                current.session,
                current.ui,
                current.generation,
                current.bytes.length,
                current.offset / CHUNK_BYTES,
                current.digest,
                Arrays.copyOfRange(current.bytes, current.offset, end));
            channel.sendToServer(fragment);
            fragmentsSent++;
            largestFragment = Math.max(largestFragment, fragment.data.length + 128L);
            current.offset = end;
        }
        clientProgress = "上传 " + current.offset + "/" + current.bytes.length;
        if (current.acknowledged == (current.bytes.length - 1) / CHUNK_BYTES + 1 && outgoing == current) {
            outgoing = null;
            clientProgress = "";
        }
    }

    static void clearClient() {
        GENERATION.incrementAndGet();
        outgoing = null;
        clientProgress = "";
    }

    static void clearServer() {
        INCOMING.clear();
        reservedBytes = 0;
        FRAGMENT_GATE.clear();
    }

    static void tick() {
        long now = System.nanoTime();
        Iterator<Incoming> entries = INCOMING.values()
            .iterator();
        while (entries.hasNext()) {
            Incoming incoming = entries.next();
            if (!HologramNetwork.progressConnected(incoming.player) || incoming.player.worldObj != incoming.world
                || now - incoming.updated > TIMEOUT) {
                reservedBytes -= incoming.assembly.bytes.length;
                entries.remove();
                if (HologramNetwork.progressConnected(incoming.player))
                    fail(incoming.player, incoming.first, new IOException("附加数据上传已超时或世界发生变化，请重新确认"));
            }
        }
    }

    private static void receive(EntityPlayerMP player, World world, Fragment fragment) {
        if (player.worldObj != world || !HologramNetwork.progressConnected(player)) return;
        HologramSession session = HologramWorkspace.find(player.getUniqueID());
        if (session == null || !session.id.equals(fragment.session) || fragment.ui < session.uiSequence) return;
        Incoming previous = INCOMING.get(player.getUniqueID());
        try {
            if (previous == null || previous.first.sequence != fragment.sequence) {
                if (fragment.index != 0) return;
                if (previous != null) {
                    reservedBytes -= previous.assembly.bytes.length;
                    INCOMING.remove(player.getUniqueID());
                }
                if (INCOMING.size() >= 16 || fragment.total > GLOBAL_BYTES - reservedBytes)
                    throw new IOException("附加数据上传队列已满，请稍后重试");
                previous = new Incoming(player, world, fragment);
                INCOMING.put(player.getUniqueID(), previous);
                reservedBytes += fragment.total;
            }
            if (previous.player != player || previous.world != world
                || previous.first.ui != fragment.ui
                || !previous.first.session.equals(fragment.session)) throw new IOException("附加数据会话不一致");
            byte[] complete = previous.assembly
                .accept(fragment.sequence, fragment.total, fragment.index, fragment.digest, fragment.data);
            previous.updated = System.nanoTime();
            channel.sendTo(new Ack(fragment.sequence, fragment.index + 1), player);
            if (complete == null) return;
            INCOMING.remove(player.getUniqueID());
            reservedBytes -= complete.length;
            DECODER.execute(() -> {
                try {
                    if (!MessageDigest.isEqual(sha(complete), fragment.digest)) throw new IOException("附加数据校验失败");
                    NBTTagCompound tag = HologramSnapshotCodec.decode(complete, MAX_BYTES, 4L * MAX_BYTES);
                    if (!"placeTextBox".equals(tag.getString("op")) || !tag.hasKey("textBox", 10)
                        || !tag.getString("session")
                            .equals(fragment.session)
                        || tag.getLong("uiSequence") != fragment.ui) throw new IOException("附加数据请求身份无效");
                    ServerTaskScheduler
                        .scheduleServerTask(() -> HologramNetwork.receiveUploadedAction(player, world, tag));
                } catch (Exception failure) {
                    ServerTaskScheduler.scheduleServerTask(() -> fail(player, fragment, failure));
                }
            });
        } catch (Exception failure) {
            Incoming removed = INCOMING.remove(player.getUniqueID());
            if (removed != null) reservedBytes -= removed.assembly.bytes.length;
            fail(player, fragment, failure);
        }
    }

    private static void fail(EntityPlayerMP player, Fragment first, Exception failure) {
        HologramNetwork.transportFailure(player, first.session, first.ui, "attachment.upload", failure);
    }

    private static byte[] sha(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes);
    }

    /** An exact ordered assembly, independently testable without Minecraft connections. */
    public static final class Assembly {

        final long sequence;
        final byte[] bytes, digest;
        int next, offset;

        public Assembly(long sequence, int total, byte[] digest) {
            if (sequence < 0 || total < 1 || total > MAX_BYTES || digest.length != 32)
                throw new IllegalArgumentException("attachment assembly budget");
            this.sequence = sequence;
            this.bytes = new byte[total];
            this.digest = digest.clone();
        }

        public byte[] accept(long sequence, int total, int index, byte[] digest, byte[] data) throws IOException {
            int expected = Math.min(CHUNK_BYTES, bytes.length - offset);
            if (this.sequence != sequence || total != bytes.length
                || index != next
                || expected <= 0
                || data.length != expected
                || !MessageDigest.isEqual(this.digest, digest)) throw new IOException("附加数据分块顺序或大小无效");
            System.arraycopy(data, 0, bytes, offset, data.length);
            offset += data.length;
            next++;
            return offset == bytes.length ? bytes : null;
        }
    }

    private static final class Outgoing {

        final String session;
        final long ui, generation;
        final byte[] bytes, digest;
        int offset;
        volatile int acknowledged;
        volatile long lastAcknowledgedAt = System.nanoTime();

        Outgoing(String session, long ui, long generation, byte[] bytes, byte[] digest) {
            this.session = session;
            this.ui = ui;
            this.generation = generation;
            this.bytes = bytes;
            this.digest = digest;
        }
    }

    private static final class Incoming {

        final EntityPlayerMP player;
        final World world;
        final Fragment first;
        final Assembly assembly;
        long updated = System.nanoTime();

        Incoming(EntityPlayerMP player, World world, Fragment first) {
            this.player = player;
            this.world = world;
            this.first = first;
            assembly = new Assembly(first.sequence, first.total, first.digest);
        }
    }

    public static final class Fragment implements IMessage {

        String session;
        long ui, sequence;
        int total, index;
        byte[] digest, data;

        public Fragment() {}

        Fragment(String session, long ui, long sequence, int total, int index, byte[] digest, byte[] data) {
            this.session = session;
            this.ui = ui;
            this.sequence = sequence;
            this.total = total;
            this.index = index;
            this.digest = digest;
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            int length = buffer.readUnsignedByte();
            if (length > 64 || buffer.readableBytes() < length + 56)
                throw new IllegalArgumentException("attachment header");
            byte[] identity = new byte[length];
            buffer.readBytes(identity);
            session = new String(identity, StandardCharsets.UTF_8);
            ui = buffer.readLong();
            sequence = buffer.readLong();
            total = buffer.readInt();
            index = buffer.readInt();
            digest = new byte[32];
            buffer.readBytes(digest);
            int size = buffer.readableBytes();
            if (total < 1 || total > MAX_BYTES || index < 0 || size < 1 || size > CHUNK_BYTES)
                throw new IllegalArgumentException("attachment fragment budget");
            data = new byte[size];
            buffer.readBytes(data);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            byte[] identity = session.getBytes(StandardCharsets.UTF_8);
            if (identity.length > 64 || data.length > CHUNK_BYTES)
                throw new IllegalArgumentException("attachment fragment budget");
            buffer.writeByte(identity.length);
            buffer.writeBytes(identity);
            buffer.writeLong(ui);
            buffer.writeLong(sequence);
            buffer.writeInt(total);
            buffer.writeInt(index);
            buffer.writeBytes(digest);
            buffer.writeBytes(data);
        }
    }

    public static final class Handler implements IMessageHandler<Fragment, IMessage> {

        @Override
        public IMessage onMessage(Fragment fragment, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            World world = player.worldObj;
            HologramRequestGate.Lease lease = FRAGMENT_GATE.acquire(player);
            if (lease == null) return null;
            ServerTaskScheduler.scheduleServerTask(() -> {
                try {
                    receive(player, world, fragment);
                } finally {
                    lease.close();
                }
            });
            return null;
        }
    }

    public static final class Ack implements IMessage {

        long sequence;
        int next;

        public Ack() {}

        Ack(long sequence, int next) {
            this.sequence = sequence;
            this.next = next;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            sequence = buffer.readLong();
            next = buffer.readInt();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(sequence);
            buffer.writeInt(next);
        }
    }

    public static final class AckHandler implements IMessageHandler<Ack, IMessage> {

        @Override
        public IMessage onMessage(Ack ack, MessageContext context) {
            Outgoing current = outgoing;
            if (current != null && current.generation == ack.sequence
                && ack.next >= current.acknowledged
                && ack.next <= (current.bytes.length - 1) / CHUNK_BYTES + 1) {
                if (ack.next > current.acknowledged) current.lastAcknowledgedAt = System.nanoTime();
                current.acknowledged = ack.next;
            }
            return null;
        }
    }
}
