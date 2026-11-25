// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Receive-only compatibility rejection. No geometry allocation, encoder, assembly or construction state. */
public final class HologramRetiredPlacementPackets {

    static final int CHUNK_BYTES = HologramAttachmentUpload.CHUNK_BYTES;
    private static final int HEADER = 16 + 4 + 4 + 32;
    private static final int MAX_COMPRESSED = 24 * 1024 * 1024;

    private HologramRetiredPlacementPackets() {}

    public static final class RetiredFrame implements IMessage {
        private UUID id;
        private int index;

        @Override
        public void fromBytes(ByteBuf buffer) {
            int size = buffer.readableBytes();
            if (size <= HEADER || size > HEADER + CHUNK_BYTES)
                throw new IllegalArgumentException("placement fragment size");
            id = new UUID(buffer.readLong(), buffer.readLong());
            int total = buffer.readInt();
            index = buffer.readInt();
            int frames = total > 0 && total <= MAX_COMPRESSED ? (total + CHUNK_BYTES - 1) / CHUNK_BYTES : 0;
            if (frames == 0 || index < 0 || index >= frames
                || size - HEADER != Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES))
                throw new IllegalArgumentException("placement fragment envelope");
            buffer.skipBytes(buffer.readableBytes());
            HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.PLACEMENT_FRAGMENT, true, false, size);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            throw new UnsupportedOperationException("Obsolete placement frame is receive-only");
        }
    }

    public static final class Handler implements IMessageHandler<RetiredFrame, IMessage> {
        @Override
        public IMessage onMessage(RetiredFrame frame, MessageContext context) {
            if (frame.index == 0 && frame.id != null && context != null)
                HologramNetwork.refuseRetiredPlacement(
                    HologramWorkspace.captureActionEndpoint(context.getServerHandler()), frame.id.toString());
            return null;
        }
    }

    public static final class Ack implements IMessage {
        @Override
        public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() != 20) throw new IllegalArgumentException("placement acknowledgement size");
            buffer.skipBytes(20);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            throw new UnsupportedOperationException("Obsolete placement acknowledgement is receive-only");
        }
    }

    public static final class AckHandler implements IMessageHandler<Ack, IMessage> {
        @Override
        public IMessage onMessage(Ack ack, MessageContext context) {
            return null;
        }
    }
}
