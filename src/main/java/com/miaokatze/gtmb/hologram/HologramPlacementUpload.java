// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.World;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.netty.buffer.ByteBuf;

/** Flow-controlled transport for every projected placement review. */
public final class HologramPlacementUpload {

    static final int CHUNK_BYTES = HologramAttachmentUpload.CHUNK_BYTES;
    static final int MAX_COMPRESSED = 24 * 1024 * 1024;
    static final int MAX_EXPANDED = 32 * 1024 * 1024;
    private static final int GLOBAL_BYTES = 64 * 1024 * 1024;
    private static final int WINDOW = 4, MAX_UPLOADS = 8;
    private static final long IDLE_LIMIT = TimeUnit.SECONDS.toNanos(30), TOTAL_LIMIT = TimeUnit.SECONDS.toNanos(90);
    private static final HologramRequestGate GATE = new HologramRequestGate(8, 128);
    private static final Map<UUID, Incoming> INCOMING = new LinkedHashMap<>();
    private static final AtomicReference<Outgoing> OUTGOING = new AtomicReference<>();
    private static SimpleNetworkWrapper channel;
    private static int reserved;

    private HologramPlacementUpload() {}

    static void init(SimpleNetworkWrapper wrapper) {
        channel = wrapper;
    }

    static Prepared prepare(String id, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_COMPRESSED) throw new IOException("施工数据超过上传上限，请拆分施工范围");
        return new Prepared(UUID.fromString(id), bytes, digest(bytes));
    }

    static void send(Prepared prepared, BooleanSupplier valid) throws IOException {
        Outgoing active = OUTGOING.get();
        if (active != null && active.context.current())
            throw new IOException("另一个施工清单正在上传，请稍后重试");
        ClientContext context = new ClientContext();
        if (context.player == null || context.world == null || context.connection == null)
            throw new IOException("施工上传缺少当前客户端连接");
        if (!valid.getAsBoolean()) throw new IOException("施工来源已变化，请重新确认");
        OUTGOING.set(new Outgoing(prepared, context, valid));
    }

    static void clientTick() {
        Outgoing current = OUTGOING.get();
        if (current == null || channel == null) return;
        if (!current.context.current()) {
            OUTGOING.compareAndSet(current, null);
            return;
        }
        if (!current.valid.getAsBoolean()) {
            failClient(current, "施工来源或投影仪已变化，请重新确认");
            return;
        }
        if (current.window.expired(System.nanoTime())) {
            failClient(current, current.window.acknowledged() == 0
                ? "服务器未确认施工上传，确认两端 GTMB 版本一致后重试"
                : "施工清单上传超时，请重试");
            return;
        }
        for (int sent = 0; sent < WINDOW && OUTGOING.get() == current; sent++) {
            int index = current.window.nextFrame();
            if (index < 0) break;
            try {
                channel.sendToServer(current.prepared.frame(index));
            } catch (RuntimeException unavailable) {
                failClient(current, "施工清单传输失败，请重试");
                return;
            }
        }
        if (current.window.complete()) OUTGOING.compareAndSet(current, null);
    }

    private static void failClient(Outgoing current, String reason) {
        if (!OUTGOING.compareAndSet(current, null)) return;
        NBTTagCompound cancel = new NBTTagCompound();
        cancel.setString("action", "cancelProjectedPlacement");
        String id = current.prepared.id.toString();
        cancel.setString("placementRequestId", id);
        try {
            HologramNetwork.sendAction(cancel);
        } catch (RuntimeException disconnected) {
            // The connection/world fence also expires any unfinished server upload.
        }
        HologramNetwork.clientPlacementFailure(id, reason);
    }

    static void finishedClient(String id) {
        Outgoing current = OUTGOING.get();
        if (current != null && current.prepared.id.toString().equals(id)) OUTGOING.compareAndSet(current, null);
    }

    static void clearClient() {
        OUTGOING.set(null);
    }

    static boolean active(EntityPlayerMP player) {
        Incoming incoming = INCOMING.get(player.getUniqueID());
        return incoming != null && incoming.ownedBy(player) && !incoming.cancelled;
    }

    static void cancel(EntityPlayerMP player, String id) {
        Incoming incoming = INCOMING.get(player.getUniqueID());
        if (incoming == null || !incoming.ownedBy(player) || !incoming.id.toString().equals(id)) return;
        incoming.cancelled = true;
        if (incoming.finished()) release(player.getUniqueID());
    }

    static void clearServer() {
        Iterator<Incoming> uploads = INCOMING.values().iterator();
        while (uploads.hasNext()) {
            Incoming incoming = uploads.next();
            incoming.cancelled = true;
            if (incoming.finished()) {
                reserved -= incoming.assembly.bytes.length;
                uploads.remove();
            }
        }
        GATE.clear();
    }

    private static void receive(EntityPlayerMP player, World world, NetHandlerPlayServer connection, Fragment frame) {
        if (!HologramNetwork.progressConnected(player) || player.worldObj != world
            || player.playerNetServerHandler != connection) return;
        UUID owner = player.getUniqueID();
        String id = frame.id.toString();
        if (HologramProjectedPlacementService.consumed(player, id)) return;
        Incoming incoming = INCOMING.get(owner);
        if (incoming != null && (!incoming.ownedBy(player) || !incoming.id.equals(frame.id))) {
            if (frame.index == 0) HologramProjectedPlacementService.reply(player, id, false, "已有施工清单正在上传");
            return;
        }
        try {
            if (incoming == null) {
                if (frame.index != 0) throw new IOException("缺少施工清单的首个分片");
                if (INCOMING.size() >= MAX_UPLOADS || frame.total > GLOBAL_BYTES - reserved
                    || HologramProjectedPlacementService.preparing(player) || HologramDemolition.active(player)
                    || HologramAeCraftingOrders.active(player))
                    throw new IOException("施工上传队列繁忙，请稍后重试");
                if (player.isDead || !player.capabilities.allowEdit
                    || !ItemNekoHologramProjector.isProjector(player.getHeldItem()))
                    throw new IOException("施工上传需要当前世界中的投影仪");
                incoming = new Incoming(player, world, connection, frame);
                INCOMING.put(owner, incoming);
                reserved += frame.total;
            }
            if (incoming.cancelled || incoming.decoding != null) return;
            if (!incoming.current()) throw new IOException("施工上传的工具、世界或工作区已变化，请重新确认");
            byte[] complete = incoming.assembly.accept(frame);
            incoming.updated = System.nanoTime();
            if (complete != null) {
                Incoming original = incoming;
                FutureTask<NBTTagCompound> work = new FutureTask<>(() -> {
                    if (original.cancelled) throw new IOException("施工上传已取消");
                    return original.assembly.decode();
                });
                if (!HologramAreaPreparation.execute(work)) throw new IOException("施工解码队列繁忙，请稍后重试");
                incoming.decoding = work;
            }
            channel.sendTo(new Ack(frame.id, frame.index + 1), player);
        } catch (IOException | RuntimeException invalid) {
            if (incoming != null) {
                incoming.cancelled = true;
                if (incoming.finished()) release(owner);
            }
            HologramProjectedPlacementService.retire(player, id);
            HologramProjectedPlacementService.reply(player, id, false, HologramAePolicy.fallback(invalid.getMessage()));
        }
    }

    static void tick() {
        long now = System.nanoTime();
        Iterator<Incoming> uploads = INCOMING.values().iterator();
        while (uploads.hasNext()) {
            Incoming incoming = uploads.next();
            if (!incoming.cancelled && (!incoming.current() || now - incoming.started > TOTAL_LIMIT
                || now - incoming.updated > IDLE_LIMIT)) {
                incoming.cancelled = true;
                String id = incoming.id.toString();
                HologramProjectedPlacementService.retire(incoming.player, id);
                HologramProjectedPlacementService.reply(incoming.player, id, false, "施工上传超时或上下文已变化，请重新确认");
            }
            if (incoming.finished() && (incoming.cancelled || incoming.decoding != null)) {
                uploads.remove();
                reserved -= incoming.assembly.bytes.length;
                if (incoming.cancelled) continue;
                try {
                    HologramNetwork.receiveUploadedPlacement(incoming.player, incoming.decoding.get());
                } catch (ExecutionException invalid) {
                    String id = incoming.id.toString();
                    HologramProjectedPlacementService.retire(incoming.player, id);
                    HologramProjectedPlacementService.reply(incoming.player, id, false,
                        HologramAePolicy.fallback(invalid.getCause().getMessage()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void release(UUID owner) {
        Incoming removed = INCOMING.remove(owner);
        if (removed != null) reserved -= removed.assembly.bytes.length;
    }

    private static int frames(int total) {
        return (total + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class Prepared {

        final UUID id;
        private final byte[] bytes, digest;

        private Prepared(UUID id, byte[] bytes, byte[] digest) {
            this.id = id;
            this.bytes = bytes;
            this.digest = digest;
        }

        int bytes() {
            return bytes.length;
        }

        int frameCount() {
            return frames(bytes.length);
        }

        Fragment frame(int index) {
            if (index < 0 || index >= frameCount()) throw new IllegalArgumentException("placement frame index");
            int start = index * CHUNK_BYTES;
            return new Fragment(id, bytes.length, index, digest.clone(),
                Arrays.copyOfRange(bytes, start, Math.min(start + CHUNK_BYTES, bytes.length)));
        }
    }

    private static final class ClientContext {

        final Object player, world, connection;

        ClientContext() {
            Minecraft minecraft = Minecraft.getMinecraft();
            player = minecraft.thePlayer;
            world = minecraft.theWorld;
            connection = minecraft.getNetHandler();
        }

        boolean current() {
            Minecraft minecraft = Minecraft.getMinecraft();
            return minecraft.thePlayer == player && minecraft.theWorld == world && minecraft.getNetHandler() == connection;
        }
    }

    private static final class Outgoing {

        final Prepared prepared;
        final ClientContext context;
        final BooleanSupplier valid;
        final SendWindow window;

        Outgoing(Prepared prepared, ClientContext context, BooleanSupplier valid) {
            this.prepared = prepared;
            this.context = context;
            this.valid = valid;
            window = new SendWindow(prepared.id, context.connection, prepared.frameCount(), System.nanoTime());
        }
    }

    /** Per-request/connection credits. Old, unsent and late acknowledgements never renew the deadline. */
    static final class SendWindow {

        final UUID id;
        final Object connection;
        final int frames;
        final long started;
        private long lastAcknowledged;
        private int acknowledged, next;

        SendWindow(UUID id, Object connection, int frames, long now) {
            this.id = id;
            this.connection = connection;
            this.frames = frames;
            started = lastAcknowledged = now;
        }

        synchronized int nextFrame() {
            return next < frames && next - acknowledged < WINDOW ? next++ : -1;
        }

        synchronized void acknowledge(UUID request, Object peer, int after, long now) {
            if (id.equals(request) && connection == peer && after > acknowledged && after <= next && !expired(now)) {
                lastAcknowledged = now;
                acknowledged = after;
            }
        }

        synchronized int acknowledged() {
            return acknowledged;
        }

        synchronized boolean complete() {
            return acknowledged == frames;
        }

        synchronized boolean expired(long now) {
            return now - lastAcknowledged > IDLE_LIMIT || now - started > TOTAL_LIMIT;
        }
    }

    private static final class Incoming {

        final EntityPlayerMP player;
        final World world;
        final NetHandlerPlayServer connection;
        final int slot;
        final HologramSessionPolicy.PreparationTool tool;
        final HologramSession workspace;
        final long revision, epoch;
        final UUID id;
        final Assembly assembly;
        final long started = System.nanoTime();
        long updated = started;
        volatile boolean cancelled;
        FutureTask<NBTTagCompound> decoding;

        Incoming(EntityPlayerMP player, World world, NetHandlerPlayServer connection, Fragment first) throws IOException {
            this.player = player;
            this.world = world;
            this.connection = connection;
            slot = player.inventory.currentItem;
            tool = new HologramSessionPolicy.PreparationTool(player.getHeldItem());
            workspace = HologramWorkspace.find(player.getUniqueID());
            if (workspace != null && (workspace.job == 1 || workspace.job == 2 || workspace.capturePending
                || HologramAuthoringWorkflow.blueprintHasUnsavedChanges(workspace)))
                throw new IOException("请先处理当前施工、采集或未保存的服务器草稿");
            revision = workspace == null ? -1 : workspace.planRevision;
            epoch = workspace == null ? -1 : workspace.editorWorkspaceEpoch;
            id = first.id;
            assembly = new Assembly(first);
        }

        boolean ownedBy(EntityPlayerMP owner) {
            return player == owner && connection == owner.playerNetServerHandler;
        }

        boolean current() {
            ItemStack held = player.getHeldItem();
            return ownedBy(player) && HologramNetwork.progressConnected(player) && player.worldObj == world
                && !player.isDead && player.capabilities.allowEdit && player.inventory.currentItem == slot
                && tool.matches(held) && ItemNekoHologramProjector.isProjector(held)
                && HologramWorkspace.find(player.getUniqueID()) == workspace
                && (workspace == null || workspace.planRevision == revision && workspace.editorWorkspaceEpoch == epoch);
        }

        boolean finished() {
            return decoding == null || decoding.isDone();
        }
    }

    /** Exact chunk count, ordering, byte range, identity and digest, independent of the network and world. */
    static final class Assembly {

        private final UUID id;
        private final byte[] bytes, digest;
        private int next;

        Assembly(Fragment first) throws IOException {
            if (!first.valid() || first.index != 0) throw malformed();
            id = first.id;
            bytes = new byte[first.total];
            digest = first.digest.clone();
        }

        byte[] accept(Fragment frame) throws IOException {
            if (!frame.valid() || !id.equals(frame.id) || frame.total != bytes.length
                || frame.index != next || !MessageDigest.isEqual(digest, frame.digest)) throw malformed();
            System.arraycopy(frame.data, 0, bytes, next * CHUNK_BYTES, frame.data.length);
            return ++next == frames(bytes.length) ? bytes : null;
        }

        NBTTagCompound decode() throws IOException {
            if (next != frames(bytes.length) || !MessageDigest.isEqual(digest, digest(bytes))) throw malformed();
            if (bytes.length < 2 || bytes[0] != (byte) 0x1f || bytes[1] != (byte) 0x8b) throw malformed();
            NBTTagCompound tag = HologramSnapshotCodec.decode(bytes, MAX_EXPANDED, 4L * MAX_EXPANDED);
            if (!"projectedPlacement".equals(tag.getString("action")) || !id.toString().equals(
                HologramPlacementRequest.header(tag).id())) throw malformed();
            return tag;
        }
    }

    private static IOException malformed() {
        return new IOException("施工清单分片顺序、大小或身份无效");
    }

    public static final class Fragment implements IMessage {

        private static final int HEADER = 16 + 4 + 4 + 32;
        // SimpleImpl writes one discriminator byte in the C17 payload before Fragment.toBytes.
        private static final int C17_DISCRIMINATOR_BYTES = 1;
        private static final int C17_PAYLOAD_LIMIT = Short.MAX_VALUE;
        UUID id;
        int total, index;
        byte[] digest, data;

        public Fragment() {}

        Fragment(UUID id, int total, int index, byte[] digest, byte[] data) {
            this.id = id;
            this.total = total;
            this.index = index;
            this.digest = digest;
            this.data = data;
        }

        private boolean valid() {
            return id != null && total > 0 && total <= MAX_COMPRESSED && index >= 0 && index < frames(total)
                && digest != null && digest.length == 32 && data != null
                && data.length == Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES)
                && HEADER + data.length + C17_DISCRIMINATOR_BYTES < C17_PAYLOAD_LIMIT;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            int size = buffer.readableBytes();
            if (size <= HEADER || size > HEADER + CHUNK_BYTES) throw new IllegalArgumentException("placement fragment size");
            id = new UUID(buffer.readLong(), buffer.readLong());
            total = buffer.readInt();
            index = buffer.readInt();
            digest = new byte[32];
            buffer.readBytes(digest);
            data = new byte[size - HEADER];
            buffer.readBytes(data);
            if (!valid()) throw new IllegalArgumentException("placement fragment envelope");
            HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.PLACEMENT_FRAGMENT, true, false, size);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            if (!valid()) throw new IllegalArgumentException("placement fragment envelope");
            buffer.writeLong(id.getMostSignificantBits());
            buffer.writeLong(id.getLeastSignificantBits());
            buffer.writeInt(total);
            buffer.writeInt(index);
            buffer.writeBytes(digest);
            buffer.writeBytes(data);
            HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.PLACEMENT_FRAGMENT, true, true, HEADER + data.length);
        }
    }

    public static final class Handler implements IMessageHandler<Fragment, IMessage> {

        @Override
        public IMessage onMessage(Fragment frame, MessageContext context) {
            NetHandlerPlayServer connection = context.getServerHandler();
            EntityPlayerMP player = connection.playerEntity;
            World world = player.worldObj;
            HologramRequestGate.Lease lease = GATE.acquire(connection);
            if (lease != null) ServerTaskScheduler.scheduleServerTask(() -> HologramNetwork.runQueuedAction(
                player, world, lease, () -> receive(player, world, connection, frame)));
            return null;
        }
    }

    public static final class Ack implements IMessage {

        UUID id;
        int next;

        public Ack() {}

        Ack(UUID id, int next) {
            this.id = id;
            this.next = next;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() != 20) throw new IllegalArgumentException("placement acknowledgement size");
            id = new UUID(buffer.readLong(), buffer.readLong());
            next = buffer.readInt();
            HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.PLACEMENT_ACK, false, false, 20);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(id.getMostSignificantBits());
            buffer.writeLong(id.getLeastSignificantBits());
            buffer.writeInt(next);
            HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.PLACEMENT_ACK, false, true, 20);
        }
    }

    public static final class AckHandler implements IMessageHandler<Ack, IMessage> {

        @Override
        public IMessage onMessage(Ack ack, MessageContext context) {
            Outgoing current = OUTGOING.get();
            if (current != null) current.window.acknowledge(ack.id, context.netHandler, ack.next, System.nanoTime());
            return null;
        }
    }
}
