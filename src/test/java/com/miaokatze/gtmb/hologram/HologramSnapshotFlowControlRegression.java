package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Deterministic protocol credit, legacy downgrade and download-allowance retry boundaries. */
public final class HologramSnapshotFlowControlRegression {

    private static final List<IMessage> WIRE = new ArrayList<>();

    private HologramSnapshotFlowControlRegression() {}

    public static void run() {
        HologramSnapshotFlowControl flow = new HologramSnapshotFlowControl(41, 100);
        SimpleAssert.that(!flow.begin(1, 1, 100), "initial native controller snapshot waits for capability reply");
        SimpleAssert.that(!flow.negotiate(40, true), "another connection generation cannot grant credit");
        flow.capable();
        SimpleAssert.that(!flow.begin(1, 1, 1000), "advertised new client cannot silently time out to legacy");
        flow.negotiate(41, false);
        SimpleAssert.that(!flow.begin(1, 1, 1000), "old active decode can defer the first full byte reservation");
        flow.negotiate(41, true);
        SimpleAssert.that(flow.begin(1, HologramSnapshotAssembly.MAX_BYTES, 1000), "one maximum-size message fits");
        flow.negotiate(41, true);
        SimpleAssert.that(!flow.begin(2, 1, 1001), "repeated ready cannot overlap a second message or its bytes");
        SimpleAssert.that(
            flow.begin(1, HologramSnapshotAssembly.MAX_BYTES, 1002),
            "library allowance refusal can retry the same first fragment without spending another credit");
        SimpleAssert.that(
            !flow.consume(40, 1) && !flow.consume(41, 2),
            "wrong generation and out-of-order ACK do not free credit");
        SimpleAssert.that(flow.consume(41, 1), "actual installed snapshot frees its exact sequence");
        SimpleAssert
            .that(!flow.consume(41, 1) && flow.consumed(41, 1), "duplicate consume is observable but grants no credit");
        SimpleAssert.that(flow.begin(2, 1, 1003), "next ACK or delta follows the installed baseline");
        SimpleAssert
            .that(!flow.consume(41, 1) && !flow.begin(3, 1, 1004), "late old consume cannot release a newer message");
        SimpleAssert.that(flow.consume(41, 2), "newer exact consume restores one credit");

        HologramSnapshotFlowControl legacy = new HologramSnapshotFlowControl(42, 100);
        SimpleAssert.that(!legacy.begin(1, 1, 119), "old client receives a bounded negotiation grace");
        SimpleAssert.that(legacy.begin(1, 1, 120), "old client retains its prior transport after grace");
        SimpleAssert.that(
            !legacy.negotiate(42, true),
            "late handshake cannot overlap in-flight legacy packets with new credit");
        legacy.capable();
        SimpleAssert.that(legacy.begin(2, 1, 121), "a legacy connection stays legacy until reconnect");

        HologramSnapshotFlowControl reconnected = new HologramSnapshotFlowControl(43, 100);
        SimpleAssert.that(!reconnected.consume(42, 1), "reconnect retires all previous connection credit");
        boolean oversized = false;
        try {
            reconnected.begin(1, HologramSnapshotAssembly.MAX_BYTES + 1, 100);
        } catch (IllegalArgumentException expected) {
            oversized = true;
        }
        SimpleAssert.that(oversized, "count credit cannot admit a snapshot outside the decoder byte budget");
        try {
            actualTransportAndLibraryAllowance();
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("snapshot flow transport integration", failure);
        }
    }

    private static void actualTransportAndLibraryAllowance() throws ReflectiveOperationException, java.io.IOException {
        Field unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        java.lang.reflect.Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
            "player",
            sun.misc.Unsafe.class,
            HologramServiceEditRegression.ReadOnlyWorld.class,
            UUID.class,
            String.class);
        factory.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) factory.invoke(null, unsafe, world, UUID.randomUUID(), "FlowFixture");
        Field channel = field(HologramNetwork.class, "channel"),
            executor = field(HologramSnapshotEncoding.class, "executor"),
            library = field(HologramLibraryFacade.class, "library");
        Field server = null;
        for (Field candidate : MinecraftServer.class.getDeclaredFields())
            if (candidate.getType() == MinecraftServer.class
                && java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) server = candidate;
        if (server == null) throw new NoSuchFieldException("server singleton");
        server.setAccessible(true);
        Object oldChannel = channel.get(null), oldExecutor = executor.get(null), oldLibrary = library.get(null),
            oldServer = server.get(null);
        Queue<Runnable> work = new ArrayDeque<>();
        try {
            HologramNetwork.clearServerQueues();
            WIRE.clear();
            channel.set(null, unsafe.allocateInstance(RecordingChannel.class));
            executor.set(null, (Executor) work::add);
            NBTTagCompound advertisement = new NBTTagCompound();
            advertisement.setInteger("clientSnapshotFlow", 1);
            HologramNetwork.snapshotFlowAction(player, advertisement);
            NBTTagCompound probe = ((HologramNetwork.State) WIRE.get(0)).tag.getCompoundTag("progress");
            SimpleAssert.that(
                probe.getString("scope")
                    .isEmpty()
                    && probe.getString("phase")
                        .isEmpty(),
                "actual capability offer is an ignored legacy progress observation");
            long generation = probe.getLong("snapshotFlowGeneration");
            NBTTagCompound hello = control("snapshotFlow", generation);
            hello.setBoolean("ready", true);
            HologramNetwork.snapshotFlowAction(player, hello);

            NBTTagCompound full = state("full");
            full.setBoolean("libraryInitialSnapshot", true);
            full.setTag("cells", new NBTTagList());
            HologramNetwork.SnapshotTicket first = HologramNetwork.reserveState(player, "flow-fixture", false);
            HologramNetwork.completeOwnedState(first, full, () -> true, () -> {});
            tick();
            work.remove()
                .run();
            // An initializing dedicated library refuses the real download allowance, then becomes available.
            server.set(null, unsafe.allocateInstance(DedicatedServer.class));
            library.set(null, null);
            tick();
            SimpleAssert.eq(
                0,
                fragments(HologramNetwork.LibrarySnapshotFragment.class),
                "library allowance refusal sends no fragment after reserving its credit");
            server.set(null, oldServer);
            tick();
            SimpleAssert.eq(
                1,
                fragments(HologramNetwork.LibrarySnapshotFragment.class),
                "actual library transport resumes the same credited message when allowance returns");

            NBTTagCompound delta = state("delta");
            delta.setString("snapshotKind", "cellDelta");
            HologramNetwork.SnapshotTicket second = HologramNetwork.reserveState(player, "flow-fixture", false);
            HologramNetwork.completeOwnedState(second, delta, () -> true, () -> {});
            NBTTagCompound ack = state("ACK");
            ack.setIntArray("ackIndices", new int[] { 2 });
            HologramNetwork.sendState(player, ack);
            tick();
            work.remove()
                .run();
            tick();
            SimpleAssert.eq(
                0,
                fragments(HologramNetwork.Fragment.class),
                "an encoded delta cannot overtake a full snapshot awaiting client installation");
            consume(player, generation, first.sequence());
            tick();
            SimpleAssert.eq("delta", lastOrdinary().getString("marker"), "actual delta follows the full consume");
            tick();
            work.remove()
                .run();
            tick();
            SimpleAssert.eq(1, fragments(HologramNetwork.Fragment.class), "ACK remains queued behind unconsumed delta");
            consume(player, generation, first.sequence());
            consume(player, generation, second.sequence() + 1);
            HologramNetwork.snapshotFlowAction(player, hello);
            tick();
            SimpleAssert.eq(
                1,
                fragments(HologramNetwork.Fragment.class),
                "duplicate consume, future ACK and repeated ready do not enlarge credit");
            consume(player, generation, second.sequence());
            tick();
            SimpleAssert.eq("ACK", lastOrdinary().getString("marker"), "actual ordered ACK survives bounded credit");
        } finally {
            HologramNetwork.clearServerQueues();
            server.set(null, oldServer);
            library.set(null, oldLibrary);
            executor.set(null, oldExecutor);
            channel.set(null, oldChannel);
            WIRE.clear();
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static NBTTagCompound state(String marker) {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", "flow-fixture");
        state.setString("marker", marker);
        state.setLong("uiSequence", 7);
        state.setLong("planRevision", 1);
        return state;
    }

    private static NBTTagCompound control(String operation, long generation) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("op", operation);
        request.setInteger("clientSnapshotFlow", 1);
        request.setLong("snapshotFlowGeneration", generation);
        return request;
    }

    private static void consume(EntityPlayerMP player, long generation, long sequence) {
        NBTTagCompound request = control("snapshotConsumed", generation);
        request.setLong("transportSequence", sequence);
        request.setBoolean("success", true);
        HologramNetwork.snapshotFlowAction(player, request);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static int fragments(Class<?> type) {
        return (int) WIRE.stream()
            .filter(type::isInstance)
            .count();
    }

    private static NBTTagCompound lastOrdinary() throws java.io.IOException {
        IMessage packet = WIRE.stream()
            .filter(HologramNetwork.Fragment.class::isInstance)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("expected a transmitted snapshot fragment"));
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes);
            bytes.readLong();
            int total = bytes.readInt();
            SimpleAssert.eq(0, bytes.readInt(), "small fixture occupies one actual fragment");
            SimpleAssert.eq(total, bytes.readInt(), "actual fragment contains all compressed fixture bytes");
            byte[] encoded = new byte[total];
            bytes.readBytes(encoded);
            return HologramSnapshotCodec.decode(encoded);
        } finally {
            bytes.release();
        }
    }

    private static void tick() {
        new HologramNetwork.TransportTicks().tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
    }

    private static final class RecordingChannel extends SimpleNetworkWrapper {

        private RecordingChannel() {
            super("flow_fixture");
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP player) {
            WIRE.add(message);
        }
    }
}
