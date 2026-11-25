// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;
import java.util.function.BiConsumer;

import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Dedicated, uncompressed plain-layer protocol. Registration and peer negotiation are deliberately external. */
public final class HologramProjectedLayerTransport {

    public static final int VERSION = 1;
    static final int MAX_BEGIN = 2 * 1024; // At most three supported vanilla material rows; never a full plan.
    static final int KEY_BYTES = 32, REQUEST_FIXED = 2 + KEY_BYTES;
    static final int REPLY_FIXED = 1 + KEY_BYTES + 2 * 8 + 2 + 8 * 4 + 1;
    private static volatile HologramProjectedLayerServer server;
    private static volatile BiConsumer<Reply, Object> client;

    private HologramProjectedLayerTransport() {}

    public enum Control {
        STATUS, PAUSE, RESUME, STOP
    }

    public enum Phase {
        AWAITING_SLICE, VALIDATING_SLICE, PLACING, PAUSED, COMPLETE, STOPPED, REJECTED, RECOVERY_PENDING
    }

    public enum Reason {
        NONE, CANCELLED, TIMEOUT, OWNER_CHANGED, INVALID_SLICE, WORLD_CHANGED, BLOCKED, MISSING_STOCK,
        RECOVERY_PENDING, UNSUPPORTED_TRANSACTION, ADMISSION_REFUSED, BUSY, RETIRED_UNKNOWN
    }

    enum Operation {
        BEGIN, SLICE, STATUS, PAUSE, RESUME, STOP
    }

    static final class Key {

        final UUID id;
        final long intent, generation;

        Key(UUID id, long intent, long generation) {
            if (id == null || id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0 || intent < 1
                || generation < 1)
                throw new IllegalArgumentException("Invalid layer intent");
            this.id = id;
            this.intent = intent;
            this.generation = generation;
        }

        HologramConstructionTaskGate.Request gateRequest() {
            return new HologramConstructionTaskGate.Request(
                id, intent, HologramConstructionTaskGate.Kind.PROJECTED_LAYER);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && intent == key.intent && generation == key.generation
                && id.equals(key.id);
        }

        @Override
        public int hashCode() {
            return (id.hashCode() * 31 + Long.hashCode(intent)) * 31 + Long.hashCode(generation);
        }
    }

    public static Request begin(UUID id, long intentSequence, long scopeGeneration, byte[] manifest) {
        return data(new Key(id, intentSequence, scopeGeneration), Operation.BEGIN, manifest, MAX_BEGIN);
    }

    public static Request slice(UUID id, long intentSequence, long scopeGeneration, byte[] slice) {
        return data(new Key(id, intentSequence, scopeGeneration), Operation.SLICE, slice,
            HologramProjectedLayerWire.MAX_SLICE_BYTES);
    }

    private static Request data(Key key, Operation operation, byte[] value, int max) {
        if (value == null || value.length == 0 || value.length > max)
            throw new IllegalArgumentException("Invalid layer message size");
        return new Request(key, operation, 0, value.clone());
    }

    public static Request control(UUID id, long intentSequence, long scopeGeneration, long controlSequence,
        Control control) {
        if (control == null || controlSequence < 1) throw new IllegalArgumentException("Invalid layer control");
        return new Request(new Key(id, intentSequence, scopeGeneration), Operation.valueOf(control.name()),
            controlSequence, null);
    }

    public static final class Request implements IMessage {

        Key key;
        Operation operation;
        long controlSequence;
        byte[] body;

        public Request() {}

        private Request(Key key, Operation operation, long controlSequence, byte[] body) {
            this.key = key;
            this.operation = operation;
            this.controlSequence = controlSequence;
            this.body = body;
        }

        boolean isControl() {
            return operation != Operation.BEGIN && operation != Operation.SLICE;
        }

        @Override
        public void fromBytes(ByteBuf in) {
            key = null;
            body = null;
            controlSequence = 0;
            if (in.readableBytes() < REQUEST_FIXED || in.readUnsignedByte() != VERSION) return;
            int name = in.readUnsignedByte();
            if (name >= Operation.values().length) return;
            operation = Operation.values()[name];
            try {
                Key decoded = readKey(in);
                if (isControl()) {
                    if (in.readableBytes() != 8 || (controlSequence = in.readLong()) < 1) return;
                } else {
                    int length = in.readableBytes();
                    int max = operation == Operation.BEGIN ? MAX_BEGIN : HologramProjectedLayerWire.MAX_SLICE_BYTES;
                    if (length < 1 || length > max) return;
                    body = new byte[length];
                    in.readBytes(body);
                }
                key = decoded;
            } catch (IllegalArgumentException malformed) {
                // Invalid envelopes are never admitted to the server inbox.
            }
        }

        @Override
        public void toBytes(ByteBuf out) {
            out.writeByte(VERSION);
            out.writeByte(operation.ordinal());
            writeKey(out, key);
            if (isControl()) out.writeLong(controlSequence);
            else out.writeBytes(body);
        }
    }

    /**
     * Geometry-free status (84 bytes, at most 113 bytes with transfer ACK). COMPLETE is emitted only after the
     * server paired world/payment commit, not from cumulative transfer ACK. An all-satisfied result has placed=0.
     */
    public static final class Reply implements IMessage {

        public UUID request;
        /** Independent status and accepted mutating-control sequences. STATUS never advances either. */
        public long intentSequence, scopeGeneration, sequence, controlSequence;
        public Phase phase;
        public Reason reason;
        public int acceptedSlices, settledSlices, totalSlices, placed, alreadySatisfied, totalCells;
        public int validatedInSlice, sliceCells;
        private byte[] ack;

        public Reply() {}

        Reply(Key key, long sequence, long controlSequence, Phase phase, Reason reason, int acceptedSlices,
            int settledSlices, int totalSlices, int placed, int alreadySatisfied, int totalCells, int validatedInSlice,
            int sliceCells, byte[] ack) throws IOException {
            request = key.id;
            intentSequence = key.intent;
            scopeGeneration = key.generation;
            this.sequence = sequence;
            this.controlSequence = controlSequence;
            this.phase = phase;
            this.reason = reason;
            this.acceptedSlices = acceptedSlices;
            this.settledSlices = settledSlices;
            this.totalSlices = totalSlices;
            this.placed = placed;
            this.alreadySatisfied = alreadySatisfied;
            this.totalCells = totalCells;
            this.validatedInSlice = validatedInSlice;
            this.sliceCells = sliceCells;
            this.ack = ack;
            validate();
        }

        public byte[] acknowledgement() {
            return ack == null ? null : ack.clone();
        }

        private void validate() throws IOException {
            if (sequence < 0 || controlSequence < 0 || settledSlices < 0 || acceptedSlices < settledSlices
                || acceptedSlices - settledSlices > 1 || totalSlices < acceptedSlices
                || totalSlices > HologramProjectedLayerService.MAX_SLICES || placed < 0 || alreadySatisfied < 0
                || (long) placed + alreadySatisfied > totalCells || validatedInSlice < 0
                || validatedInSlice > sliceCells || sliceCells > HologramProjectedLayerWire.MAX_SLICE_CELLS
                || (phase == Phase.REJECTED ? totalCells != 0 || totalSlices != 0 || ack != null
                    : totalCells < 1 || totalSlices < 1 || totalCells > HologramProjectedLayerWire.MAX_CELLS
                        || !HologramProjectedLayerWire.fits(totalCells, totalSlices))
                || (phase == Phase.STOPPED || phase == Phase.REJECTED || phase == Phase.RECOVERY_PENDING)
                    != (reason != Reason.NONE)
                || phase == Phase.COMPLETE && (settledSlices != totalSlices || placed + alreadySatisfied != totalCells))
                throw HologramProjectedLayerWire.malformed();
            if (ack != null) {
                Ack value = HologramProjectedLayerWire.decodeAck(ack);
                if (!request.equals(value.request) || value.nextSlice != settledSlices
                    || value.settledCells > placed + alreadySatisfied
                    || value.credit > 0 && phase != Phase.AWAITING_SLICE
                    || value.complete != (phase == Phase.COMPLETE)
                    || value.aborted != (phase == Phase.STOPPED || phase == Phase.RECOVERY_PENDING))
                    throw HologramProjectedLayerWire.malformed();
            }
        }

        @Override
        public void fromBytes(ByteBuf in) {
            request = null;
            ack = null;
            int length = in.readableBytes();
            if ((length != REPLY_FIXED && length != REPLY_FIXED + HologramProjectedLayerWire.ACK_BYTES)
                || in.readUnsignedByte() != VERSION) return;
            try {
                Key key = readKey(in);
                intentSequence = key.intent;
                scopeGeneration = key.generation;
                sequence = in.readLong();
                controlSequence = in.readLong();
                int p = in.readUnsignedByte(), r = in.readUnsignedByte();
                if (p >= Phase.values().length || r >= Reason.values().length) return;
                phase = Phase.values()[p];
                reason = Reason.values()[r];
                acceptedSlices = in.readInt();
                settledSlices = in.readInt();
                totalSlices = in.readInt();
                placed = in.readInt();
                alreadySatisfied = in.readInt();
                totalCells = in.readInt();
                validatedInSlice = in.readInt();
                sliceCells = in.readInt();
                int ackBytes = in.readUnsignedByte();
                if (ackBytes != in.readableBytes()) return;
                if (ackBytes != 0) {
                    ack = new byte[ackBytes];
                    in.readBytes(ack);
                }
                request = key.id;
                validate();
            } catch (IOException | IllegalArgumentException malformed) {
                request = null;
                ack = null;
            }
        }

        @Override
        public void toBytes(ByteBuf out) {
            out.writeByte(VERSION);
            writeKey(out, new Key(request, intentSequence, scopeGeneration));
            out.writeLong(sequence);
            out.writeLong(controlSequence);
            out.writeByte(phase.ordinal());
            out.writeByte(reason.ordinal());
            out.writeInt(acceptedSlices);
            out.writeInt(settledSlices);
            out.writeInt(totalSlices);
            out.writeInt(placed);
            out.writeInt(alreadySatisfied);
            out.writeInt(totalCells);
            out.writeInt(validatedInSlice);
            out.writeInt(sliceCells);
            out.writeByte(ack == null ? 0 : ack.length);
            if (ack != null) out.writeBytes(ack);
        }
    }

    static void installServer(HologramProjectedLayerServer value) {
        server = value;
    }

    /** Receiver must queue on the client thread and recheck the exact inbound play handler plus local intent there. */
    public static void setClientReceiver(BiConsumer<Reply, Object> value) {
        client = value;
    }

    public static final class ServerHandler implements IMessageHandler<Request, IMessage> {

        @Override
        public IMessage onMessage(Request message, MessageContext context) {
            HologramProjectedLayerServer current = server;
            if (current != null) current.enqueue(message, context.getServerHandler());
            return null;
        }
    }

    public static final class ClientHandler implements IMessageHandler<Reply, IMessage> {

        @Override
        public IMessage onMessage(Reply message, MessageContext context) {
            BiConsumer<Reply, Object> current = client;
            if (current != null && message.request != null) current.accept(message, context.netHandler);
            return null;
        }
    }

    private static Key readKey(ByteBuf in) {
        return new Key(new UUID(in.readLong(), in.readLong()), in.readLong(), in.readLong());
    }

    private static void writeKey(ByteBuf out, Key key) {
        out.writeLong(key.id.getMostSignificantBits());
        out.writeLong(key.id.getLeastSignificantBits());
        out.writeLong(key.intent);
        out.writeLong(key.generation);
    }
}
