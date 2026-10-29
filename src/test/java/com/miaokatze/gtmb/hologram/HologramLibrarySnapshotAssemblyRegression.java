package com.miaokatze.gtmb.hologram;

import java.util.Arrays;

/** Hash verification and inactivity expiry retain atomic hydration while transferring the admitted buffer. */
public final class HologramLibrarySnapshotAssemblyRegression {

    public static void main(String[] args) {
        run();
        System.out.println("HologramLibrarySnapshotAssemblyRegression passed");
    }

    public static void run() {
        connectionOwnedCredit();
        idleExpiry();
        originAdmission();
        byte[] source = new byte[HologramLibraryProtocol.CHUNK_BYTES + 3];
        Arrays.fill(source, (byte) 7);
        byte[] hash = HologramLibraryProtocol.digest()
            .digest(source);
        byte[] first = Arrays.copyOf(source, HologramLibraryProtocol.CHUNK_BYTES);
        byte[] last = Arrays.copyOfRange(source, first.length, source.length);
        HologramLibrarySnapshotAssembly assembly = new HologramLibrarySnapshotAssembly();
        check(assembly.acceptReserved(1, source.length, 0, first, hash, 0) == null, "partial hydration invisible");
        HologramSnapshotAssembly.Complete complete = assembly.acceptReserved(1, source.length, 1, last, hash, 1);
        check(
            complete != null && Arrays.equals(source, complete.bytes),
            "single admitted buffer contains all verified bytes");
        check(
            HologramTransportMemory.reserve(HologramSnapshotAssembly.MAX_BYTES) == null,
            "hydration retains its reservation after assembly");
        complete.close();
        assembly.acceptReserved(2, source.length, 0, first, hash, 0);
        check(
            assembly.acceptReserved(2, source.length, 1, last, hash, HologramLibraryProtocol.TIMEOUT_NANOS + 1) == null,
            "idle timeout never publishes a partial frame");
        byte[] wrong = hash.clone();
        wrong[0]++;
        assembly.acceptReserved(3, source.length, 0, first, wrong, 0);
        check(
            assembly.acceptReserved(3, source.length, 1, last, wrong, 1) == null,
            "whole-frame digest mismatch rejected");
        HologramTransportMemory.Lease all = HologramTransportMemory.reserve(HologramSnapshotAssembly.MAX_BYTES);
        check(all != null, "expiry and digest failure release admission");
        all.close();
    }

    private static void idleExpiry() {
        java.util.List<Long> released = new java.util.ArrayList<>();
        HologramLibrarySnapshotAssembly assembly = new HologramLibrarySnapshotAssembly(released::add);
        byte[] first = new byte[HologramLibraryProtocol.CHUNK_BYTES];
        byte[] last = new byte[] { 1 };
        byte[] whole = new byte[first.length + 1];
        whole[first.length] = 1;
        byte[] hash = HologramLibraryProtocol.digest()
            .digest(whole);
        long before = HologramProcessGovernor.retainedBytes();
        assembly.acceptReserved(1, whole.length, 0, first, hash, 0);
        check(HologramProcessGovernor.retainedBytes() == before + whole.length, "partial frame owns announced bytes");
        assembly.expire(HologramLibraryProtocol.TIMEOUT_NANOS + 1);
        assembly.expire(HologramLibraryProtocol.TIMEOUT_NANOS + 2);
        check(
            HologramProcessGovernor.retainedBytes() == before && released.size() == 1,
            "idle tick with no incoming packet releases exactly one byte reservation and credit");
        check(
            assembly.acceptReserved(1, whole.length, 0, first, hash, 1) == null,
            "expired sequence cannot restart its reservation");
        assembly.acceptReserved(2, whole.length, 0, first, hash, 2);
        HologramSnapshotAssembly.Complete complete = assembly.acceptReserved(2, whole.length, 1, last, hash, 3);
        assembly.expire(HologramLibraryProtocol.TIMEOUT_NANOS + 4);
        check(complete != null && released.size() == 1, "expiry does not dispose transferred complete ownership");
        complete.close();
        check(
            released.size() == 2 && HologramProcessGovernor.retainedBytes() == before,
            "complete owner returns its credit once");
    }

    private static void originAdmission() {
        RecordingConnection old = new RecordingConnection(), current = new RecordingConnection();
        java.util.concurrent.atomic.AtomicReference<net.minecraft.network.NetworkManager> origin = new java.util.concurrent.atomic.AtomicReference<>(
            current);
        HologramNetwork.setClientOriginFrameReceiver(
            (frame, sequence, connection) -> frame.close(),
            connection -> connection == origin.get());
        byte[] source = new byte[HologramLibraryProtocol.CHUNK_BYTES + 1];
        byte[] hash = HologramLibraryProtocol.digest()
            .digest(source);
        byte[] first = Arrays.copyOf(source, HologramLibraryProtocol.CHUNK_BYTES);
        try {
            check(
                HologramNetwork.acceptLibrarySnapshot(
                    current,
                    new HologramNetwork.LibrarySnapshotFragment(11, source.length, 0, hash, first)) == null,
                "current connection owns partial library assembly");
            check(
                HologramNetwork.acceptLibrarySnapshot(
                    old,
                    new HologramNetwork.LibrarySnapshotFragment(99, source.length, 0, hash, first)) == null,
                "open but retired origin is rejected at actual library admission");
            HologramNetwork.receiveSnapshotFragment(new HologramNetwork.Fragment(100, 1, 0, new byte[] { 0 }), old);
            HologramSnapshotAssembly.Complete complete = HologramNetwork.acceptLibrarySnapshot(
                current,
                new HologramNetwork.LibrarySnapshotFragment(11, source.length, 1, hash, new byte[] { 0 }));
            check(complete != null, "rejected old origin cannot reset the new partial assembler");
            complete.close();
            java.util.concurrent.atomic.AtomicInteger receives = new java.util.concurrent.atomic.AtomicInteger();
            HologramNetwork.setClientTransportReceiver((frame, connection) -> receives.incrementAndGet());
            HologramNetwork.receiveTransportFragment(new net.minecraft.nbt.NBTTagCompound(), old);
            check(receives.get() == 0, "origin-aware ordinary/display ingress rejects stale origin before dispatch");
            HologramNetwork.receiveTransportFragment(new net.minecraft.nbt.NBTTagCompound(), current);
            check(receives.get() == 1, "current origin still reaches transport consumer");
            try {
                byte[] header = HologramSnapshotCodec.compress(new net.minecraft.nbt.NBTTagCompound());
                HologramNetwork.DisplayFragment display = new HologramNetwork.DisplayFragment(
                    12,
                    1,
                    0,
                    new byte[] { 0 },
                    header);
                HologramNetwork.receiveDisplayFragment(display, old);
                check(receives.get() == 1, "actual display handler boundary rejects old packet origin");
                HologramNetwork.receiveDisplayFragment(display, current);
                check(receives.get() == 2, "actual display handler delivers current packet origin");
            } catch (java.io.IOException failed) {
                throw new IllegalStateException(failed);
            }
            java.util.concurrent.atomic.AtomicInteger frames = new java.util.concurrent.atomic.AtomicInteger();
            HologramNetwork.setClientOriginFrameReceiver((frame, sequence, connection) -> {
                check(connection == current, "owned library handoff carries exact packet connection");
                frames.incrementAndGet();
                frame.close();
            }, connection -> connection == origin.get());
            byte[] single = new byte[] { 9 };
            HologramNetwork.LibrarySnapshotFragment completePacket = new HologramNetwork.LibrarySnapshotFragment(
                13,
                1,
                0,
                HologramLibraryProtocol.digest()
                    .digest(single),
                single);
            HologramNetwork.receiveLibrarySnapshot(completePacket, old);
            check(frames.get() == 0, "actual library handler cannot relabel old frame with current epoch");
            HologramNetwork.receiveLibrarySnapshot(completePacket, current);
            check(frames.get() == 1, "actual library handler retains current-origin delivery");
        } finally {
            HologramNetwork.setClientReceiver(null);
        }
    }

    private static void connectionOwnedCredit() {
        java.lang.reflect.Field channelField = null;
        Object previousChannel = null;
        HologramSnapshotAssembly.Complete oldFrame = null, newFrame = null;
        try {
            channelField = HologramNetwork.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            previousChannel = channelField.get(null);
            RecordingChannel channel = new RecordingChannel();
            channelField.set(null, channel);
            java.lang.reflect.Method assembler = HologramNetwork.class
                .getDeclaredMethod("librarySnapshots", net.minecraft.network.NetworkManager.class);
            assembler.setAccessible(true);
            RecordingConnection oldConnection = new RecordingConnection(), newConnection = new RecordingConnection();
            byte[] source = new byte[] { 1, 2, 3 };
            byte[] hash = HologramLibraryProtocol.digest()
                .digest(source);
            HologramLibrarySnapshotAssembly oldAssembly = (HologramLibrarySnapshotAssembly) assembler
                .invoke(null, oldConnection);
            oldFrame = oldAssembly.acceptReserved(91, source.length, 0, source, hash, 0);
            HologramLibrarySnapshotAssembly newAssembly = (HologramLibrarySnapshotAssembly) assembler
                .invoke(null, newConnection);
            newFrame = newAssembly.acceptReserved(91, source.length, 0, source, hash, 0);
            check(oldFrame != null && newFrame != null, "reconnect accepts a colliding sequence in its own assembly");
            oldFrame.close();
            oldFrame.close();
            check(
                oldConnection.receipts == 1 && newConnection.receipts == 0,
                "late disposal sends once to the captured connection, never the replacement");
            check(
                channel.last.getLong("transportSequence") == 91 && channel.last.getBoolean("inboxConsumed"),
                "disposal retains exact transport sequence and credit meaning");
            newFrame.close();
            check(newConnection.receipts == 1, "new connection returns only its own frame credit");
            oldAssembly = (HologramLibrarySnapshotAssembly) assembler.invoke(null, oldConnection);
            oldFrame = oldAssembly.acceptReserved(92, source.length, 0, source, hash, 0);
            assembler.invoke(null, newConnection);
            oldConnection.open = false;
            oldFrame.close();
            check(
                oldConnection.receipts == 1 && newConnection.receipts == 1,
                "closed origin suppresses late credit instead of rerouting it");
            admissionRecovery(channel, newConnection);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        } finally {
            if (oldFrame != null) oldFrame.close();
            if (newFrame != null) newFrame.close();
            HologramNetwork.clearLibraryClientTransport();
            if (channelField != null) try {
                channelField.set(null, previousChannel);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static void admissionRecovery(RecordingChannel channel, RecordingConnection connection) {
        byte[] source = new byte[] { 4, 5, 6 };
        byte[] hash = HologramLibraryProtocol.digest()
            .digest(source);
        HologramNetwork.LibrarySnapshotFragment rejected = new HologramNetwork.LibrarySnapshotFragment(
            101,
            source.length,
            0,
            hash,
            source);
        int sent = channel.actions.size();
        long before = HologramProcessGovernor.retainedBytes();
        long available = HologramProcessGovernor.retainedByteLimit() - before;
        try (HologramProcessGovernor.Lease occupied = HologramProcessGovernor
            .tryRetain("fixture.library.admission", available)) {
            check(occupied != null, "fixture owns all remaining shared bytes without allocating another frame");
            check(
                HologramNetwork.acceptLibrarySnapshot(connection, rejected) == null,
                "actual handler boundary rejects transient byte contention");
            check(
                channel.actions.size() == sent + 2,
                "rejection sends disposal then exact recovery without retaining a retry slot");
            net.minecraft.nbt.NBTTagCompound credit = channel.actions.get(sent),
                recovery = channel.actions.get(sent + 1);
            check(
                credit.getBoolean("inboxConsumed") && credit.getLong("transportSequence") == 101,
                "assembly returns only the rejected frame credit");
            check(
                "requestFullSnapshot".equals(recovery.getString("op"))
                    && recovery.getBoolean("recoverTransportSequence")
                    && recovery.getLong("transportSequence") == 101
                    && !recovery.hasKey("session"),
                "initial library admission recovers exact server-owned identity");
            HologramNetwork.acceptLibrarySnapshot(connection, rejected);
            check(
                channel.actions.size() == sent + 2,
                "duplicate or remaining rejected chunks cannot repeat disposal/recovery");
            check(
                HologramProcessGovernor.retainedBytes() == before + available,
                "rejected library frame retains no extra lease");
        }
        HologramSnapshotAssembly.Complete recovered = HologramNetwork.acceptLibrarySnapshot(
            connection,
            new HologramNetwork.LibrarySnapshotFragment(102, source.length, 0, hash, source));
        check(recovered != null, "fresh server recovery sequence is admitted once contention releases");
        recovered.close();
        check(HologramProcessGovernor.retainedBytes() == before, "recovered frame closes its only byte lease");
        sent = channel.actions.size();
        check(
            !HologramNetwork
                .requestSnapshotAdmissionRecovery(connection, 103, HologramProcessGovernor.retainedByteLimit() + 1),
            "deterministic configured-budget oversize never auto-retries");
        check(
            !HologramNetwork
                .requestSnapshotAdmissionRecovery(connection, 103, (long) HologramSnapshotAssembly.MAX_BYTES + 1),
            "deterministic wire-budget oversize never auto-retries");
        check(channel.actions.size() == sent, "impossible frame cannot start an endless recovery loop");
        connection.open = false;
        check(
            !HologramNetwork.requestSnapshotAdmissionRecovery(connection, 104, source.length),
            "closed origin cannot send recovery on another connection");
        check(channel.actions.size() == sent, "closed origin creates no global recovery message");
    }

    private static final class RecordingConnection extends net.minecraft.network.NetworkManager {

        boolean open = true;
        int receipts;

        RecordingConnection() {
            super(true);
        }

        @Override
        public boolean isChannelOpen() {
            return open;
        }

        @Override
        public void scheduleOutboundPacket(net.minecraft.network.Packet packet,
            io.netty.util.concurrent.GenericFutureListener... listeners) {
            receipts++;
        }
    }

    private static final class RecordingChannel extends cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper {

        net.minecraft.nbt.NBTTagCompound last;
        final java.util.List<net.minecraft.nbt.NBTTagCompound> actions = new java.util.ArrayList<>();

        RecordingChannel() {
            super("lib_credit_test");
        }

        @Override
        public net.minecraft.network.Packet getPacketFrom(cpw.mods.fml.common.network.simpleimpl.IMessage message) {
            last = ((HologramNetwork.Action) message).tag;
            actions.add(last);
            return new net.minecraft.network.play.client.C00PacketKeepAlive();
        }

        @Override
        public void sendToServer(cpw.mods.fml.common.network.simpleimpl.IMessage message) {
            throw new AssertionError("library disposal must not resolve the current global connection");
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
