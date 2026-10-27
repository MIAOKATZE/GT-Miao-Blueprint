package com.miaokatze.gtmb.hologram;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.nbt.NBTTagCompound;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Real existing packet types carry negotiated control replies independently of display-frame decoding. */
public final class HologramTransportControlRegression {

    public static void main(String[] args) {
        run();
        System.out.println("HologramTransportControlRegression passed");
    }

    public static void run() {
        String old = System.getProperty("gtmb.segment.transport");
        ByteBuf wire = Unpooled.buffer();
        AtomicReference<NBTTagCompound> cut = new AtomicReference<>(), ordinary = new AtomicReference<>();
        try {
            System.clearProperty("gtmb.segment.transport");
            NBTTagCompound identity = new NBTTagCompound();
            identity.setLong("uiSequence", 37);
            identity.setLong("planRevision", 11);
            identity.setLong("blueprintClientTaskToken", 93);
            NBTTagCompound queued = HologramNetwork.queuedSnapshotProgress("receipt-session", 71, identity);
            new HologramNetwork.State(queued).toBytes(wire);
            HologramNetwork.State queuedDecoded = new HologramNetwork.State();
            queuedDecoded.fromBytes(wire);
            check(queued.equals(queuedDecoded.tag), "queued identity survives the existing control wire codec");
            NBTTagCompound correlation = queuedDecoded.tag.getCompoundTag("progress");
            check(
                correlation.getLong("transportSequence") == 71 && correlation.getLong("uiSequence") == 37
                    && correlation.getLong("planRevision") == 11
                    && correlation.getLong("clientTaskToken") == 93
                    && "receipt-session".equals(correlation.getString("session")),
                "decode failures can correlate only the exact queued command and transport frame");
            check(
                !identity.hasKey("transportSequence"),
                "queuing receipt metadata does not mutate the authoritative payload");
            wire.clear();
            NBTTagCompound source = new NBTTagCompound();
            source.setString("session", "session");
            new HologramNetwork.Action(source).toBytes(wire);
            HologramNetwork.Action action = new HologramNetwork.Action();
            action.fromBytes(wire);
            check(action.tag.getInteger("clientSnapshotProtocol") == 1, "existing delta protocol remains exactly 1");
            check(
                action.tag.getInteger("clientSegmentProtocol") == HologramSnapshotSegments.VERSION,
                "segment capability is a separate negotiated version");
            check(!source.hasKey("clientSegmentProtocol"), "advertising capability does not mutate the caller");
            wire.clear();
            System.setProperty("gtmb.segment.transport", "false");
            new HologramNetwork.Action(source).toBytes(wire);
            action = new HologramNetwork.Action();
            action.fromBytes(wire);
            check(
                action.tag.getInteger("clientSegmentProtocol") == 0,
                "fallback switch retains the old complete wire codec");
            wire.clear();
            new HologramNetwork.CutAction(source).toBytes(wire);
            HologramNetwork.CutAction legacyCut = new HologramNetwork.CutAction();
            legacyCut.fromBytes(wire);
            check(
                !legacyCut.controlProtocol && legacyCut.tag.equals(source),
                "old server receives only the old accepted request keys");
            wire.clear();
            NBTTagCompound capabilities = new NBTTagCompound();
            capabilities.setInteger("serverCutControlProtocol", 1);
            HologramNetwork.observeCutCapabilities(capabilities);
            new HologramNetwork.CutAction(source).toBytes(wire);
            HologramNetwork.CutAction cutAction = new HologramNetwork.CutAction();
            cutAction.fromBytes(wire);
            check(
                cutAction.controlProtocol && !cutAction.tag.hasKey("clientCutControlProtocol"),
                "cut control lane is explicitly advertised");
            wire.clear();
            NBTTagCompound result = new NBTTagCompound();
            result.setString("requestId", "world-operation-receipt");
            result.setBoolean("success", true);
            NBTTagCompound envelope = new NBTTagCompound();
            envelope.setTag("cutControl", result);
            new HologramNetwork.State(envelope).toBytes(wire);
            HologramNetwork.State decoded = new HologramNetwork.State();
            decoded.fromBytes(wire);
            HologramNetwork.setClientReceiver(ordinary::set);
            HologramNetwork.setCutReceiver(cut::set);
            new HologramNetwork.ClientHandler().onMessage(decoded, null);
            check(result.equals(cut.get()), "requestId and committed success reach the cut owner directly");
            check(
                ordinary.get() == null,
                "a cut world receipt cannot enter or wait behind the ordinary snapshot inbox");
        } finally {
            wire.release();
            HologramNetwork.setClientReceiver(null);
            HologramNetwork.setCutReceiver(null);
            if (old == null) System.clearProperty("gtmb.segment.transport");
            else System.setProperty("gtmb.segment.transport", old);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
