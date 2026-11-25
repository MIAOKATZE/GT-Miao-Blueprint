// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.World;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Retired whole-placement v4 wire. Only a metadata refusal is registered; the old codec is an offline baseline. */
public final class HologramPlacementUpload {

    static final int CHUNK_BYTES = HologramAttachmentUpload.CHUNK_BYTES;
    static final int MAX_COMPRESSED = 24 * 1024 * 1024;
    static final int MAX_EXPANDED = 32 * 1024 * 1024;
    private static final int WINDOW = 4;
    private static final long IDLE_LIMIT = TimeUnit.SECONDS.toNanos(30), TOTAL_LIMIT = TimeUnit.SECONDS.toNanos(90);

    private HologramPlacementUpload() {}

    static Prepared prepareBaseline(String id, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_COMPRESSED) throw new IOException("施工数据超过上传上限，请拆分施工范围");
        return new Prepared(UUID.fromString(id), bytes, digest(bytes));
    }

    // Transitional compatibility for callers checking old busy/cancel state; this lane can no longer own a job.
    static boolean active(EntityPlayerMP player) {
        return false;
    }

    static void cancel(EntityPlayerMP player, String id) {}

    private static int frames(int total) {
        return (total + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes);
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
            return new Fragment(
                id,
                bytes.length,
                index,
                digest.clone(),
                Arrays.copyOfRange(bytes, start, Math.min(start + CHUNK_BYTES, bytes.length)));
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
        Incoming(EntityPlayerMP player, World world, NetHandlerPlayServer connection, Fragment first)
            throws IOException {
            this.player = player;
            this.world = world;
            this.connection = connection;
            slot = player.inventory.currentItem;
            tool = new HologramSessionPolicy.PreparationTool(player.getHeldItem());
            workspace = HologramWorkspace.find(player.getUniqueID());
            if (workspace != null && (workspace.job == 1 || workspace.job == 2
                || workspace.capturePending
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
            return ownedBy(player) && HologramNetwork.progressConnected(player)
                && player.worldObj == world
                && !player.isDead
                && player.capabilities.allowEdit
                && player.inventory.currentItem == slot
                && tool.matches(held)
                && ItemNekoHologramProjector.isProjector(held)
                && HologramWorkspace.find(player.getUniqueID()) == workspace
                && (workspace == null || workspace.planRevision == revision && workspace.editorWorkspaceEpoch == epoch);
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
            if (!frame.valid() || !id.equals(frame.id)
                || frame.total != bytes.length
                || frame.index != next
                || !MessageDigest.isEqual(digest, frame.digest)) throw malformed();
            System.arraycopy(frame.data, 0, bytes, next * CHUNK_BYTES, frame.data.length);
            return ++next == frames(bytes.length) ? bytes : null;
        }

        NBTTagCompound decode() throws IOException {
            if (next != frames(bytes.length) || !MessageDigest.isEqual(digest, digest(bytes))) throw malformed();
            if (bytes.length < 2 || bytes[0] != (byte) 0x1f || bytes[1] != (byte) 0x8b) throw malformed();
            NBTTagCompound tag = HologramSnapshotCodec.decode(bytes, MAX_EXPANDED, 4L * MAX_EXPANDED);
            if (!"projectedPlacement".equals(tag.getString("action")) || !id.toString()
                .equals(
                    HologramPlacementRequest.header(tag)
                        .id()))
                throw malformed();
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
            return id != null && total > 0
                && total <= MAX_COMPRESSED
                && index >= 0
                && index < frames(total)
                && digest != null
                && digest.length == 32
                && data != null
                && data.length == Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES)
                && HEADER + data.length + C17_DISCRIMINATOR_BYTES < C17_PAYLOAD_LIMIT;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            int size = buffer.readableBytes();
            if (size <= HEADER || size > HEADER + CHUNK_BYTES)
                throw new IllegalArgumentException("placement fragment size");
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
            HologramRuntimeMetrics
                .packet(HologramRuntimeMetrics.Packet.PLACEMENT_FRAGMENT, true, true, HEADER + data.length);
        }
    }

    /** Discriminator 10 remains a safe refusal for older clients. No chunk bytes or digest are allocated. */
    public static final class RetiredFrame implements IMessage {

        private UUID id;
        private int index;

        @Override
        public void fromBytes(ByteBuf buffer) {
            int size = buffer.readableBytes();
            if (size <= Fragment.HEADER || size > Fragment.HEADER + CHUNK_BYTES)
                throw new IllegalArgumentException("placement fragment size");
            id = new UUID(buffer.readLong(), buffer.readLong());
            int total = buffer.readInt();
            index = buffer.readInt();
            if (total <= 0 || total > MAX_COMPRESSED || index < 0 || index >= frames(total)
                || size - Fragment.HEADER != Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES))
                throw new IllegalArgumentException("placement fragment envelope");
            buffer.skipBytes(buffer.readableBytes());
            HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.PLACEMENT_FRAGMENT, true, false, size);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            throw new UnsupportedOperationException("The retired placement discriminator is receive-only");
        }
    }

    public static final class Handler implements IMessageHandler<RetiredFrame, IMessage> {

        @Override
        public IMessage onMessage(RetiredFrame frame, MessageContext context) {
            if (frame.index == 0 && frame.id != null) HologramNetwork.refuseRetiredPlacement(
                HologramWorkspace.captureActionEndpoint(context.getServerHandler()), frame.id.toString());
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
            // Discriminator 11 is reserved for peers with the retired codec; no live sender is waiting for credit.
            return null;
        }
    }
}
