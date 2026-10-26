package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Actual codec counters, bounded scheduling, fairness and retained publication results; no world writes. */
public final class HologramServerLoadRegression {

    private HologramServerLoadRegression() {}

    public static void run() {
        boolean previous = HologramRuntimeMetrics.isEnabled();
        try {
            metricsAndCodecs();
            admission();
            publication();
            productionJobDeltas();
            fairRounds();
            adaptiveTickSlack();
            taskScheduling();
            callbackPendingCapacity();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Server load regression", failure);
        } finally {
            HologramRuntimeMetrics.setEnabled(false);
            HologramRuntimeMetrics.reset();
            HologramRuntimeMetrics.setEnabled(previous);
        }
    }

    private static void metricsAndCodecs() {
        HologramRuntimeMetrics.setEnabled(false);
        HologramRuntimeMetrics.reset();
        SimpleAssert.eq(0L, HologramRuntimeMetrics.start(), "disabled collection does not take a timer sample");
        HologramRuntimeMetrics.packet(HologramRuntimeMetrics.Packet.ACTION, true, true, 42);
        HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.ACTION_REJECTED, 1);
        SimpleAssert.eq(0L, network("action").getLong("c2sTxPackets"), "disabled packet instrumentation is inert");
        HologramRuntimeMetrics.setEnabled(true);
        NBTTagCompound request = new NBTTagCompound();
        request.setString("op", "fixture");
        roundTrip(new HologramNetwork.Action(request), new HologramNetwork.Action(), "action", "c2s");
        roundTrip(new HologramNetwork.State(request), new HologramNetwork.State(), "state", "s2c");
        roundTrip(
            new HologramNetwork.LibraryRequest(request),
            new HologramNetwork.LibraryRequest(),
            "libraryRequest",
            "c2s");
        roundTrip(new HologramNetwork.LibraryReply(request), new HologramNetwork.LibraryReply(), "libraryReply", "s2c");
        roundTrip(new HologramNetwork.CutAction(request), new HologramNetwork.CutAction(), "cutAction", "c2s");
        byte[] bytes = { 1, 2, 3 };
        roundTrip(new HologramNetwork.Fragment(1, 3, 0, bytes), new HologramNetwork.Fragment(), "fragment", "s2c");
        roundTrip(
            new HologramNetwork.CutFragment(1, 3, 0, bytes),
            new HologramNetwork.CutFragment(),
            "cutFragment",
            "s2c");
        roundTrip(
            new HologramNetwork.LibrarySnapshotFragment(1, 3, 0, new byte[32], bytes),
            new HologramNetwork.LibrarySnapshotFragment(),
            "librarySnapshot",
            "s2c");
        SimpleAssert
            .eq(1L, network("fragment").getLong("s2cTxPackets"), "cut fragments are not counted as ordinary fragments");
        NBTTagCompound detached = HologramRuntimeMetrics.snapshot();
        detached.getCompoundTag("network")
            .getCompoundTag("action")
            .setLong("c2sTxPackets", 900);
        SimpleAssert.eq(1L, network("action").getLong("c2sTxPackets"), "diagnostics snapshot cannot mutate counters");
        HologramRuntimeMetrics.highWater(HologramRuntimeMetrics.Counter.ACTION_HIGH_WATER, 8);
        HologramRuntimeMetrics.highWater(HologramRuntimeMetrics.Counter.ACTION_HIGH_WATER, 3);
        SimpleAssert.eq(
            8L,
            HologramRuntimeMetrics.snapshot()
                .getCompoundTag("queues")
                .getLong("actionHighWater"),
            "queue high-water mark does not decrease");
    }

    private static NBTTagCompound network(String family) {
        return HologramRuntimeMetrics.snapshot()
            .getCompoundTag("network")
            .getCompoundTag(family);
    }

    private static void roundTrip(IMessage encoded, IMessage decoded, String family, String direction) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            encoded.toBytes(buffer);
            int bytes = buffer.readableBytes();
            decoded.fromBytes(buffer);
            SimpleAssert.eq(0, buffer.readableBytes(), "codec consumes complete " + family + " frame");
            NBTTagCompound row = network(family);
            SimpleAssert.eq(1L, row.getLong(direction + "TxPackets"), "actual codec TX count " + family);
            SimpleAssert.eq(1L, row.getLong(direction + "RxPackets"), "actual codec RX count " + family);
            SimpleAssert.eq((long) bytes, row.getLong(direction + "TxBytes"), "actual encoded byte count " + family);
            SimpleAssert.eq((long) bytes, row.getLong(direction + "RxBytes"), "actual decoded byte count " + family);
        } finally {
            buffer.release();
        }
    }

    private static void admission() {
        HologramRequestGate gate = new HologramRequestGate(2, 3);
        Object first = new Object(), second = new Object();
        SimpleAssert.that(gate.enter(first) && gate.enter(first), "peer owns two queued requests");
        SimpleAssert.that(!gate.enter(first), "per-peer admission precedes scheduling");
        SimpleAssert.that(gate.enter(second) && !gate.enter(new Object()), "global admission bounds many peers");
        gate.leave(first);
        SimpleAssert.that(gate.enter(second), "retired request releases global capacity");
        gate.leave(first);
        gate.leave(first);
        SimpleAssert.eq(2, gate.size(), "duplicate release cannot underflow or remove another peer");
        gate.clear();
        SimpleAssert.eq(0, gate.size(), "server-stop clears outstanding connection identities");
    }

    private static void publication() {
        HologramJobPublication publication = new HologramJobPublication();
        SimpleAssert.that(publication.due(10, 3, 7), "first work publication is immediate");
        publication.published(10, 3, 7);
        publication.indices.addAll(Arrays.asList(2, 9));
        publication.successes.addAll(Arrays.asList(1, 0));
        SimpleAssert
            .that(!publication.due(11, 3, 7), "unchanged intermediate publication is deferred before full build");
        SimpleAssert.eq(Arrays.asList(2, 9), publication.indices, "deferred work retains every cell result");
        SimpleAssert
            .eq(Arrays.asList(1, 0), publication.successes, "deferred work retains success and failure separately");
        SimpleAssert.that(publication.due(11, 4, 7), "different revision cannot be merged");
        SimpleAssert.that(publication.due(11, 3, 8), "different action sequence cannot be merged");
        SimpleAssert.that(publication.due(15, 3, 7), "five-tick publication deadline is bounded");
        publication.published(15, 3, 7);
        SimpleAssert
            .that(publication.indices.isEmpty() && publication.successes.isEmpty(), "published results retire once");
    }

    private static void fairRounds() throws ReflectiveOperationException {
        Field cursor = HologramWorkspaceScheduler.class.getDeclaredField("nextSession");
        cursor.setAccessible(true);
        int previous = cursor.getInt(null);
        try {
            SimpleAssert.eq(
                123L,
                HologramWorkBudget.captureDeadline(123L),
                "standalone capture retains its original callback deadline");
            HologramWorkBudget.begin(42L);
            try {
                SimpleAssert.eq(
                    42L,
                    HologramWorkBudget.captureDeadline(Long.MAX_VALUE),
                    "scheduled capture continuation shares the global deadline");
            } finally {
                HologramWorkBudget.end();
            }
            SimpleAssert.eq(
                123L,
                HologramWorkBudget.captureDeadline(123L),
                "ending a work round cannot leak its deadline into standalone native callers");
            cursor.setInt(null, 0);
            AtomicLong clock = new AtomicLong();
            List<String> visited = new ArrayList<>();
            for (int tick = 0; tick < 3; tick++)
                HologramWorkspaceScheduler.runRound(Arrays.asList("a", "b", "c"), session -> {
                    visited.add(session);
                    clock.addAndGet(6);
                }, clock::get, 10);
            SimpleAssert.eq(
                Arrays.asList("a", "b", "c", "a", "b", "c"),
                visited,
                "one global deadline rotates the next peer fairly across ticks");
            visited.clear();
            HologramWorkspaceScheduler.runRound(Arrays.asList("a", "b", "c"), session -> {
                visited.add(session);
                clock.addAndGet(100);
            }, clock::get, 10);
            SimpleAssert
                .eq(1, visited.size(), "one slow atomic callback does not grant every peer another full budget");
        } finally {
            cursor.setInt(null, previous);
        }
    }

    private static void adaptiveTickSlack() throws ReflectiveOperationException {
        final long ms = 1_000_000L;
        SimpleAssert
            .eq(40 * ms, HologramWorkBudget.tickNanos(100 * ms, 100 * ms), "idle tick uses the available server slack");
        SimpleAssert.eq(
            15 * ms,
            HologramWorkBudget.tickNanos(100 * ms, 125 * ms),
            "world work reduces the shared allowance rather than imposing a fixed four-ms cap");
        SimpleAssert.eq(
            ms,
            HologramWorkBudget.tickNanos(100 * ms, 160 * ms),
            "busy tick retains only a minimum progress slice");
        Field cursor = HologramWorkspaceScheduler.class.getDeclaredField("nextSession");
        cursor.setAccessible(true);
        int previous = cursor.getInt(null);
        try {
            cursor.setInt(null, 0);
            int cappedTicks = preparationTicks(HologramWorkBudget.TICK_NANOS, 20 * ms);
            int slackTicks = preparationTicks(HologramWorkBudget.tickNanos(0, 0), 20 * ms);
            SimpleAssert.eq(5, cappedTicks, "the former four-ms cap spreads twenty-ms preparation over five ticks");
            SimpleAssert.eq(1, slackTicks, "idle preparation completes in one real scheduler round");
            System.out
                .println("snapshot preparation scheduler: formerCapTicks=" + cappedTicks + " spareTicks=" + slackTicks);

            cursor.setInt(null, 0);
            AtomicLong clock = new AtomicLong();
            List<String> order = new ArrayList<>();
            List<Long> quotas = new ArrayList<>();
            HologramWorkspaceScheduler.runRound(Arrays.asList("idle", "a", "b"), session -> {
                order.add(session);
                if (!"idle".equals(session)) clock.addAndGet(HologramWorkBudget.remainingNanos(0));
            }, session -> {
                order.add(session + "+");
                long quota = HologramWorkBudget.remainingNanos(0);
                quotas.add(quota);
                clock.addAndGet(quota);
            }, session -> !"idle".equals(session), clock::get, 40 * ms);
            SimpleAssert.eq(
                Arrays.asList("idle", "a", "b"),
                order.subList(0, 3),
                "each peer gets its first slice before extra work");
            for (long quota : quotas) SimpleAssert.that(
                quota <= HologramWorkBudget.SLICE_NANOS,
                "each continuation retains a bounded slice even when explicit round budget is larger");
            SimpleAssert
                .eq(40 * ms, clock.get(), "idle server spends the available tick instead of stopping after five ms");

            cursor.setInt(null, 0);
            clock.set(0);
            order.clear();
            for (int tick = 0; tick < 3; tick++)
                HologramWorkspaceScheduler.runRound(Arrays.asList("a", "b", "c"), session -> {
                    order.add(session);
                    SimpleAssert.eq(
                        ms,
                        HologramWorkBudget.remainingNanos(0),
                        "busy peer is clipped to the shared one-ms quota");
                    clock.addAndGet(2 * ms); // One native callback may atomically exceed its soft deadline.
                },
                    session -> { throw new AssertionError("busy tick granted an extra continuation"); },
                    session -> true,
                    clock::get,
                    ms);
            SimpleAssert.eq(
                Arrays.asList("a", "b", "c"),
                order,
                "busy atomic work rotates peers across ticks without multiplying their budgets");

            cursor.setInt(null, 0);
            clock.set(0);
            order.clear();
            HologramWorkspaceScheduler.runRound(
                Arrays.asList("waiting", "job"),
                order::add,
                session -> { throw new AssertionError("worker waiting or a once-per-tick job was run twice"); },
                session -> false,
                clock::get,
                40 * ms);
            SimpleAssert.eq(
                Arrays.asList("waiting", "job"),
                order,
                "unavailable workers and jobs are polled once and release spare time");
            SimpleAssert.eq(0L, HologramWorkBudget.remainingNanos(0), "assigned quota cannot escape its work round");
        } finally {
            cursor.setInt(null, previous);
            HologramWorkBudget.end();
        }
    }

    private static int preparationTicks(long budget, long workNanos) {
        AtomicLong clock = new AtomicLong();
        AtomicLong remaining = new AtomicLong(workNanos);
        java.util.function.Consumer<String> prepare = session -> {
            long work = Math.min(remaining.get(), HologramWorkBudget.remainingNanos(0));
            SimpleAssert.that(work <= HologramWorkBudget.SLICE_NANOS, "preparation keeps its bounded two-ms slice");
            remaining.addAndGet(-work);
            clock.addAndGet(work);
        };
        int ticks = 0;
        while (remaining.get() > 0 && ticks < 100) {
            HologramWorkspaceScheduler.runRound(
                java.util.Collections.singletonList("snapshot"),
                prepare,
                prepare,
                session -> remaining.get() > 0,
                clock::get,
                budget);
            ticks++;
        }
        SimpleAssert.eq(0L, remaining.get(), "bounded scheduler preparation eventually completes");
        return ticks;
    }

    private static void productionJobDeltas() throws ReflectiveOperationException {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        java.lang.reflect.Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
            "player",
            sun.misc.Unsafe.class,
            HologramServiceEditRegression.ReadOnlyWorld.class,
            java.util.UUID.class,
            String.class);
        factory.setAccessible(true);
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) factory
            .invoke(null, unsafe, world, java.util.UUID.randomUUID(), "LoadFixture");
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previous = channel.get(null);
        try {
            SimpleAssert
                .that(HologramNetwork.progressConnected(player), "open embedded connection passes progress guard only");
            queuedActionContext(player, world, unsafe);
            SimpleAssert
                .that(!HologramNetwork.progressConnected(null), "missing player has no progress delivery target");
            net.minecraft.entity.player.EntityPlayerMP detached = (net.minecraft.entity.player.EntityPlayerMP) unsafe
                .allocateInstance(net.minecraft.entity.player.EntityPlayerMP.class);
            SimpleAssert.that(
                !HologramNetwork.progressConnected(detached),
                "FakePlayer without handler has no progress target");
            detached.playerNetServerHandler = (net.minecraft.network.NetHandlerPlayServer) unsafe
                .allocateInstance(net.minecraft.network.NetHandlerPlayServer.class);
            SimpleAssert
                .that(!HologramNetwork.progressConnected(detached), "handler without manager has no progress target");
            NBTTagCompound observation = new NBTTagCompound();
            observation.setBoolean("progressOnly", true);
            observation.setTag("progress", new NBTTagCompound());
            long sent = HologramRuntimeMetrics.snapshot()
                .getCompoundTag("queues")
                .getLong("progressSent");
            HologramNetwork.sendState(detached, observation);
            SimpleAssert.eq(
                sent,
                HologramRuntimeMetrics.snapshot()
                    .getCompoundTag("queues")
                    .getLong("progressSent"),
                "disconnected observation is not counted as a successful send");
            // Actual production DTO/send branch, deliberately no sockets. Native Netty stress is a separate run.
            channel.set(null, null);
            HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
            session.job = 1;
            session.planRevision = 11;
            session.placementReviewNonce = 7;
            session.batchSeq = 2;
            session.cursor = 2;
            session.completed = 1;
            session.capture = new HologramCapture(
                null,
                new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone));
            for (int i = 0; i < 32000; i++) session.work.add(i);
            session.jobPublication.indices.addAll(Arrays.asList(0, 9));
            session.jobPublication.successes.addAll(Arrays.asList(1, 0));
            session.jobPublication.statuses.addAll(Arrays.asList("placed", "missing"));
            NBTTagCompound small = HologramSnapshots.jobDelta(session);
            int smallBytes = encodedSize(small);
            for (int i = 0; i < 32000; i++) session.capture.cells.add(new HologramCapture.Cell(null, i, 64, 0));
            NBTTagCompound large = HologramSnapshots.jobDelta(session);
            SimpleAssert.eq(
                smallBytes,
                encodedSize(large),
                "job delta bytes depend on changed results, not 32000 captured cells");
            SimpleAssert.that(
                !large.hasKey("cells") && !large.hasKey("materials")
                    && !large.hasKey("channels")
                    && !large.hasKey("processing"),
                "visual delta contains no editable structure, quote or author authority");
            SimpleAssert.eq(2, large.getIntArray("ackIndices").length, "delta retains every changed result");
            SimpleAssert.eq(
                "missing",
                large.getTagList("ackStatuses", 8)
                    .getStringTagAt(1),
                "failure status is explicit");
            long builds = HologramRuntimeMetrics.snapshot()
                .getCompoundTag("activity")
                .getCompoundTag("snapshotBuild")
                .getLong("calls");
            HologramSnapshots.sendJob(session);
            SimpleAssert.eq(
                builds,
                HologramRuntimeMetrics.snapshot()
                    .getCompoundTag("activity")
                    .getCompoundTag("snapshotBuild")
                    .getLong("calls"),
                "production running sendJob never calls full snapshot builder");
            SimpleAssert
                .that(session.jobPublication.indices.isEmpty(), "emitted delta retires pending visual results once");
            session.jobPublication.indices.add(11);
            session.jobPublication.successes.add(1);
            session.jobPublication.statuses.add("placed");
            HologramSnapshots.sendJob(session);
            SimpleAssert.eq(1, session.jobPublication.indices.size(), "within cadence sendJob retains unsent results");
            session.capture = null;
            session.work.clear();
            session.job = 3;
            HologramSnapshots.sendJob(session);
            SimpleAssert.eq(
                builds + 1,
                HologramRuntimeMetrics.snapshot()
                    .getCompoundTag("activity")
                    .getCompoundTag("snapshotBuild")
                    .getLong("calls"),
                "terminal sendJob still takes actual complete snapshot path");
            player.playerNetServerHandler.netManager.channel()
                .close();
            SimpleAssert
                .that(!HologramNetwork.progressConnected(player), "closed embedded connection fails progress guard");
        } finally {
            channel.set(null, previous);
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static void queuedActionContext(net.minecraft.entity.player.EntityPlayerMP player,
        HologramServiceEditRegression.ReadOnlyWorld world, sun.misc.Unsafe unsafe) throws InstantiationException {
        HologramRequestGate gate = new HologramRequestGate(1, 1);
        int[] dispatched = { 0 };
        Runnable action = () -> dispatched[0]++;
        HologramRequestGate.Lease lease = gate.acquire(player);
        SimpleAssert.that(lease != null, "queued action owns its admission slot");
        HologramNetwork.runQueuedAction(player, world, lease, action);
        SimpleAssert.eq(1, dispatched[0], "live connection in its original world dispatches normally");
        SimpleAssert.eq(0, gate.size(), "normal action releases its admission slot");
        net.minecraft.network.NetHandlerPlayServer handler = player.playerNetServerHandler;
        try {
            lease = gate.acquire(player);
            SimpleAssert.that(lease != null, "world-changing request is queued before transition");
            player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            HologramNetwork.runQueuedAction(player, world, lease, action);
            SimpleAssert.eq(1, dispatched[0], "old-world input cannot dispatch after a world transition");
            SimpleAssert.eq(0, gate.size(), "world transition still releases admission capacity");
            player.worldObj = world;
            lease = gate.acquire(player);
            SimpleAssert.that(lease != null, "disconnecting request is queued before disconnect");
            player.playerNetServerHandler = null;
            HologramNetwork.runQueuedAction(player, world, lease, action);
            SimpleAssert.eq(1, dispatched[0], "disconnected input cannot create a workspace or invoke an action");
            SimpleAssert.eq(0, gate.size(), "disconnected input releases its admission capacity");
            player.playerNetServerHandler = handler;
            lease = gate.acquire(player);
            SimpleAssert.that(lease != null, "throwing action is admitted after reconnect");
            try {
                HologramNetwork.runQueuedAction(
                    player,
                    world,
                    lease,
                    () -> { throw new IllegalStateException("fixture dispatch failure"); });
                throw new AssertionError("dispatch failure must remain visible to the server scheduler");
            } catch (IllegalStateException expected) {
                SimpleAssert.eq(0, gate.size(), "throwing action also releases its admission capacity");
            }
            HologramRequestGate.Lease retired = gate.acquire(player);
            gate.clear();
            HologramRequestGate.Lease current = gate.acquire(player);
            HologramNetwork.runQueuedAction(player, world, retired, action);
            SimpleAssert.eq(1, dispatched[0], "restart rejects old queued input");
            SimpleAssert.eq(1, gate.size(), "retired input cannot release a new generation's slot");
            HologramNetwork.runQueuedAction(player, world, current, action);
            SimpleAssert.eq(2, dispatched[0], "new input dispatches after restart");
            SimpleAssert.eq(0, gate.size(), "new input releases only its own admission");
        } finally {
            player.worldObj = world;
            player.playerNetServerHandler = handler;
            gate.clear();
        }
    }

    private static int encodedSize(NBTTagCompound progress) {
        NBTTagCompound envelope = new NBTTagCompound();
        envelope.setBoolean("progressOnly", true);
        envelope.setTag("progress", progress);
        ByteBuf buffer = Unpooled.buffer();
        try {
            new HologramNetwork.State(envelope).toBytes(buffer);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static void taskScheduling() throws ReflectiveOperationException {
        Field field = ServerTaskScheduler.class.getDeclaredField("SERVER_TASKS");
        field.setAccessible(true);
        Queue<Runnable> queue = (Queue<Runnable>) field.get(null);
        List<Runnable> previous = new ArrayList<>();
        Runnable task;
        while ((task = queue.poll()) != null) previous.add(task);
        try {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                final int index = i;
                ServerTaskScheduler.scheduleServerTask(() -> order.add(index));
            }
            ServerTaskScheduler.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
            SimpleAssert.that(order.isEmpty(), "common scheduler ignores non-END server phase");
            ServerTaskScheduler.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            SimpleAssert.that(
                !order.isEmpty() && order.size() <= 64 && !queue.isEmpty(),
                "one server tick never drains an arbitrary queue in one unbounded loop");
            for (int tick = 0; tick < 100 && !queue.isEmpty(); tick++)
                ServerTaskScheduler.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            SimpleAssert.eq(100, order.size(), "deferred tasks all execute in later bounded rounds");
            for (int i = 0; i < 100; i++) SimpleAssert.eq(i, order.get(i), "FIFO survives budget boundaries");
        } finally {
            queue.clear();
            queue.addAll(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static void callbackPendingCapacity() throws ReflectiveOperationException {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        java.lang.reflect.Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
            "player",
            sun.misc.Unsafe.class,
            HologramServiceEditRegression.ReadOnlyWorld.class,
            java.util.UUID.class,
            String.class);
        factory.setAccessible(true);
        List<net.minecraft.entity.player.EntityPlayerMP> peers = new ArrayList<>();
        Field queueField = ServerTaskScheduler.class.getDeclaredField("SERVER_TASKS");
        queueField.setAccessible(true);
        Queue<Runnable> queue = (Queue<Runnable>) queueField.get(null);
        List<Runnable> saved = new ArrayList<>();
        Runnable pending;
        while ((pending = queue.poll()) != null) saved.add(pending);
        Field gateField = HologramLibraryFacade.class.getDeclaredField("callbacks");
        gateField.setAccessible(true);
        HologramRequestGate gate = (HologramRequestGate) gateField.get(null);
        Field busyField = HologramBusyReplies.class.getDeclaredField("pending");
        busyField.setAccessible(true);
        java.util.Map<Object, Object> busy = (java.util.Map<Object, Object>) busyField.get(null);
        java.util.Map<Object, Object> savedBusy = new java.util.IdentityHashMap<>(busy);
        try {
            SimpleAssert.eq(0, gate.size(), "isolated callback regression starts without other producers");
            busy.clear();
            for (int i = 0; i < 9; i++) peers.add(
                (net.minecraft.entity.player.EntityPlayerMP) factory
                    .invoke(null, unsafe, world, java.util.UUID.randomUUID(), "CallbackFixture" + i));
            NBTTagCompound request = new NBTTagCompound();
            request.setString("session", "callback-capacity");
            request.setLong("planRevision", 7);
            java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
            int admitted = 0, rejected = 0;
            // Producer remains faster than consumer across rounds: no per-tick reset may grow the pending queue.
            for (int round = 0; round < 1000; round++) {
                for (net.minecraft.entity.player.EntityPlayerMP peer : peers)
                    for (int attempt = 0; attempt < 3; attempt++) {
                        if (HologramLibraryFacade.scheduleLibraryCallback(peer, request, completed::incrementAndGet))
                            admitted++;
                        else rejected++;
                    }
                SimpleAssert.that(
                    gate.size() <= 16 && queue.size() <= 16,
                    "continuous producer backlog remains within pending capacity across scheduler rounds");
                queue.remove()
                    .run();
            }
            while ((pending = queue.poll()) != null) pending.run();
            SimpleAssert.eq(admitted, completed.get(), "every admitted callback executes exactly once");
            SimpleAssert
                .that(rejected > 0 && gate.size() == 0, "overflow is rejected rather than retained in another queue");
            boolean submission = HologramLibraryFacade.scheduleLibraryCallback(
                peers.get(0),
                request,
                () -> {},
                task -> { throw new java.util.concurrent.RejectedExecutionException("injected submission failure"); });
            SimpleAssert.that(!submission && gate.size() == 0, "failed scheduler submission releases its lease");
            HologramLibraryFacade.scheduleLibraryCallback(
                peers.get(0),
                request,
                () -> { throw new IllegalStateException("injected callback failure"); });
            try {
                queue.remove()
                    .run();
                throw new AssertionError("Callback failure was hidden");
            } catch (IllegalStateException expected) {}
            SimpleAssert.eq(0, gate.size(), "throwing callback releases pending capacity in finally");
            int beforeDisconnect = completed.get();
            HologramLibraryFacade.scheduleLibraryCallback(peers.get(1), request, completed::incrementAndGet);
            peers.get(1).playerNetServerHandler.netManager.channel()
                .close();
            queue.remove()
                .run();
            SimpleAssert.eq(beforeDisconnect, completed.get(), "disconnected callback cannot mutate a retired session");
            SimpleAssert.eq(0, gate.size(), "disconnect still releases its queued slot");
            java.util.concurrent.atomic.AtomicInteger retired = new java.util.concurrent.atomic.AtomicInteger();
            HologramLibraryFacade.scheduleLibraryCallback(peers.get(0), request, retired::incrementAndGet);
            Runnable oldEpoch = queue.remove();
            gate.clear();
            HologramLibraryFacade.scheduleLibraryCallback(peers.get(0), request, completed::incrementAndGet);
            oldEpoch.run();
            SimpleAssert.eq(0, retired.get(), "old lifecycle callback cannot run after clear");
            SimpleAssert.eq(1, gate.size(), "old lifecycle release cannot decrement the new lease for the same actor");
            queue.remove()
                .run();
            SimpleAssert.eq(0, gate.size(), "new lifecycle callback owns and releases its own capacity");
        } finally {
            while ((pending = queue.poll()) != null) pending.run();
            gate.clear();
            queue.addAll(saved);
            busy.clear();
            busy.putAll(savedBusy);
            for (net.minecraft.entity.player.EntityPlayerMP peer : peers)
                peer.playerNetServerHandler.netManager.channel()
                    .close();
        }
    }
}
