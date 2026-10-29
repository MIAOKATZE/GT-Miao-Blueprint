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
        headerlessRecovery();
        inboxCapabilityAdvertisement();
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
            check(
                action.tag.getInteger("clientSnapshotRows") == 1,
                "compact rows require a separate explicit capability");
            check(!source.hasKey("clientSnapshotRows"), "row capability does not mutate the caller's request");
            wire.clear();
            System.setProperty("gtmb.segment.transport", "false");
            new HologramNetwork.Action(source).toBytes(wire);
            action = new HologramNetwork.Action();
            action.fromBytes(wire);
            check(
                action.tag.getInteger("clientSegmentProtocol") == 0,
                "fallback switch retains the old complete wire codec");
            check(action.tag.getInteger("clientSnapshotRows") == 0, "the fallback switch disables compact rows too");
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
            AtomicReference<net.minecraft.network.NetworkManager> origin = new AtomicReference<>();
            net.minecraft.network.NetworkManager oldConnection = new net.minecraft.network.NetworkManager(true);
            net.minecraft.network.NetworkManager replacement = new net.minecraft.network.NetworkManager(true);
            HologramNetwork.setClientTransportReceiver((fragment, connection) -> {
                ordinary.set(fragment);
                origin.set(connection);
            });
            NBTTagCompound fragment = new NBTTagCompound();
            fragment.setLong("transportSequence", 91);
            HologramNetwork.receiveTransportFragment(fragment, oldConnection);
            check(
                origin.get() == oldConnection && origin.get() != replacement,
                "ordinary transport receiver keeps packet-context origin instead of a replacement connection");
            check(ordinary.get() == fragment, "origin-aware ingress keeps the original fragment without another copy");
            HologramNetwork.setClientTransportReceiver(null);
            ordinary.set(null);
            HologramNetwork.receiveTransportFragment(fragment, oldConnection);
            check(ordinary.get() == fragment, "external legacy Consumer receiver remains a supported fallback");
        } finally {
            wire.release();
            HologramNetwork.setClientReceiver(null);
            HologramNetwork.setCutReceiver(null);
            if (old == null) System.clearProperty("gtmb.segment.transport");
            else System.setProperty("gtmb.segment.transport", old);
        }
    }

    private static void inboxCapabilityAdvertisement() {
        try {
            HologramNetwork.setClientReceiver(value -> {});
            assertInboxAdvertisement(0, "Consumer-only peer does not negotiate owned inbox credits");
            HologramNetwork
                .setClientOriginFrameReceiver((frame, sequence, connection) -> frame.close(), connection -> true);
            assertInboxAdvertisement(2, "origin-owned receiver negotiates the actual two-frame server window");
            HologramNetwork.setClientFrameReceiver((frame, sequence) -> frame.close());
            assertInboxAdvertisement(2, "legacy owned-frame receiver keeps the same credit protocol");
            HologramNetwork.setClientReceiver(null);
            assertInboxAdvertisement(0, "absent receivers cannot advertise owned-frame credit support");
        } finally {
            HologramNetwork.setClientReceiver(null);
        }
    }

    private static void assertInboxAdvertisement(int expected, String message) {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "inbox-capability");
        HologramNetwork.Action action = new HologramNetwork.Action(source);
        check(action.tag.getInteger("clientSnapshotInbox") == expected, message + " (constructor)");
        check(!source.hasKey("clientSnapshotInbox"), "capability advertisement preserves caller ownership");
        ByteBuf wire = Unpooled.buffer();
        try {
            action.toBytes(wire);
            HologramNetwork.Action decoded = new HologramNetwork.Action();
            decoded.fromBytes(wire);
            check(decoded.tag.getInteger("clientSnapshotInbox") == expected, message + " (real wire round trip)");
        } finally {
            wire.release();
        }
    }

    private static void headerlessRecovery() {
        HologramNetwork.SnapshotRecoveryWindow first = new HologramNetwork.SnapshotRecoveryWindow();
        first.sent(71, "initial-session-not-yet-known-to-client");
        check(!first.request(72, "initial-session-not-yet-known-to-client"), "unsent sequence cannot advance recovery");
        check(
            first.request(71, "initial-session-not-yet-known-to-client"),
            "initial baseline needs no client session guess");
        check(
            first.poll("initial-session-not-yet-known-to-client", 100),
            "authenticated server identity resolves recovery");
        check(
            !first.request(71, "initial-session-not-yet-known-to-client"),
            "duplicate rejected frame cannot repeat recovery");
        first.sent(72, "initial-session-not-yet-known-to-client");
        check(first.request(72, "initial-session-not-yet-known-to-client"), "later exact frame may request recovery");
        check(!first.poll("initial-session-not-yet-known-to-client", 101), "recovery remains rate bounded");
        check(
            first.poll("initial-session-not-yet-known-to-client", 120),
            "rate limited recovery is deferred, not dropped");
        first.sent(73, "old-session");
        check(!first.request(73, "new-session"), "old session frame cannot resynchronize an unrelated current view");
        first.sent(74, "old-session");
        check(first.request(74, "old-session"), "request captured before view replacement");
        check(!first.poll("new-session", 140), "pending recovery retires when its owning session changes");
        HologramNetwork.SnapshotRecoveryWindow reconnect = new HologramNetwork.SnapshotRecoveryWindow();
        check(
            !reconnect.request(71, "initial-session-not-yet-known-to-client"),
            "new connection has no old sent-frame authority");
        reconnect.sent(71, "new-connection-session");
        check(
            reconnect.request(71, "new-connection-session"),
            "sequence collision belongs only to the new connection's own frame");
        check(reconnect.poll("new-connection-session", 0), "new connection has an independent recovery clock");
        for (int i = 0; i < 100; i++) first.sent(1000 + i, "bounded");
        check(
            !first.request(1000, "bounded") && first.request(1099, "bounded"),
            "sent correlation retains only a bounded recent window");
        HologramNetwork.InboxWindow inbox = new HologramNetwork.InboxWindow();
        inbox.enabled = true;
        check(inbox.start(80) && inbox.start(81) && !inbox.start(82), "ordinary credit stays at two frames");
        first.sent(80, "credit-released");
        inbox.release(80);
        check(first.request(80, "credit-released"), "disposal ACK cannot erase recovery provenance");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
