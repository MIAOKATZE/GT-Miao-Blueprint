package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Runs real reservation, worker codec, result acceptance and fragment drain with no sockets or world writes. */
public final class HologramSnapshotPublishRegression {

    private static final List<IMessage> WIRE = new ArrayList<>();

    private HologramSnapshotPublishRegression() {}

    public static void run() {
        try {
            budgets();
            encoder();
            fifoAndCancellation();
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Snapshot publication", failure);
        }
    }

    private static void budgets() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger cells = new AtomicInteger();
        int worked = HologramSnapshotPreparation.runSlice(() -> {
            cells.incrementAndGet();
            clock.addAndGet(2);
        }, () -> cells.get() == 100, 256, 5, clock::get);
        SimpleAssert.eq(3, worked, "real preparation checkpoint stops at fake-clock deadline");
        worked = HologramSnapshotPreparation
            .runSlice(cells::incrementAndGet, () -> cells.get() == 100, 7, Long.MAX_VALUE, clock::get);
        SimpleAssert.eq(7, worked, "count ceiling bounds a fast session as well as elapsed time");
        SimpleAssert.eq(10, cells.get(), "continuation keeps completed rows instead of rescanning them");
        NBTTagCompound full = state("full");
        full.setTag("cells", new NBTTagList());
        NBTTagCompound delta = state("delta");
        delta.setString("snapshotKind", "cellDelta");
        NBTTagCompound ack = state("ack");
        ack.setIntArray("ackIndices", new int[] { 1 });
        NBTTagCompound metadata = state("metadata");
        SimpleAssert
            .that(!HologramNetwork.canCoalesceMetadata(full, metadata), "metadata cannot replace a full baseline");
        SimpleAssert.that(!HologramNetwork.canCoalesceMetadata(delta, delta), "successive deltas are ordered effects");
        SimpleAssert.that(!HologramNetwork.canCoalesceMetadata(ack, metadata), "metadata cannot replace an ACK");
        SimpleAssert
            .that(HologramNetwork.canCoalesceMetadata(metadata, metadata), "same-context metadata retains coalescing");
    }

    private static void encoder() throws java.io.IOException {
        Queue<Runnable> work = new ArrayDeque<>();
        NBTTagCompound tag = state("encoded");
        HologramSnapshotEncoding.Job job = HologramSnapshotEncoding.submit(tag, work::add);
        SimpleAssert.that(!job.done && job.bytes == null, "submission performs no compression or callback");
        work.remove()
            .run();
        SimpleAssert.that(job.done && job.failure == null, "worker publishes a complete result");
        SimpleAssert.eq(
            "encoded",
            HologramSnapshotCodec.decode(job.bytes)
                .getString("marker"),
            "actual codec result roundtrips");
        SimpleAssert.eq(32, job.hash.length, "library digest is produced with encoded bytes");
        HologramSnapshotEncoding.Job cancelled = HologramSnapshotEncoding.submit(tag, work::add);
        cancelled.cancel();
        work.remove()
            .run();
        SimpleAssert.that(cancelled.done && cancelled.bytes == null, "cancelled work cannot publish a result");
        SimpleAssert.eq(
            null,
            HologramSnapshotEncoding.submit(tag, command -> { throw new RejectedExecutionException("fixture full"); }),
            "bounded worker rejection keeps ownership on caller for retry");
        NBTTagCompound tooLarge = state("oversized");
        tooLarge.setByteArray("payload", new byte[128 * 1024 * 1024]);
        HologramSnapshotEncoding.Job limited = HologramSnapshotEncoding.submit(tooLarge, Runnable::run);
        SimpleAssert.that(
            limited.done && limited.failure != null && limited.bytes == null,
            "production codec enforces expanded byte budget inside worker");
    }

    private static void fifoAndCancellation() throws ReflectiveOperationException, java.io.IOException {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
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
        EntityPlayerMP player = (EntityPlayerMP) factory
            .invoke(null, unsafe, world, UUID.randomUUID(), "SnapshotFixture");
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Field executor = HologramSnapshotEncoding.class.getDeclaredField("executor");
        executor.setAccessible(true);
        Object previousChannel = channel.get(null), previousExecutor = executor.get(null);
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger accepted = new AtomicInteger();
        try {
            HologramNetwork.clearServerQueues();
            WIRE.clear();
            channel.set(null, unsafe.allocateInstance(RecordingChannel.class));
            executor.set(null, (Executor) work::add);
            HologramNetwork.SnapshotTicket full = HologramNetwork.reserveState(player, "fixture", false);
            NBTTagCompound delta = state("delta");
            delta.setString("snapshotKind", "cellDelta");
            HologramNetwork.sendState(player, delta);
            delta.setString("marker", "mutated caller state");
            NBTTagCompound ack = state("ack");
            ack.setIntArray("ackIndices", new int[] { 2 });
            HologramNetwork.sendState(player, ack);
            tick();
            SimpleAssert.eq(0, work.size(), "unbuilt full placeholder blocks later delta encoding");
            SimpleAssert.eq(0, WIRE.size(), "small ACK cannot overtake full preparation");
            NBTTagCompound baseline = state("full");
            baseline.setTag("cells", new NBTTagList());
            HologramNetwork.completeOwnedState(full, baseline, () -> true, accepted::incrementAndGet);
            tick();
            SimpleAssert.eq(1, work.size(), "only head snapshot is submitted to the worker");
            SimpleAssert.eq(0, accepted.get(), "baseline cache is not ready merely on worker submission");
            work.remove()
                .run();
            tick();
            SimpleAssert.eq(1, accepted.get(), "main transport tick accepts completed baseline");
            tick();
            work.remove()
                .run();
            tick();
            tick();
            work.remove()
                .run();
            tick();
            SimpleAssert.eq(3, WIRE.size(), "one small fragment per snapshot is delivered in FIFO order");
            SimpleAssert.eq("full", decode(WIRE.get(0)).getString("marker"), "full baseline arrives first");
            SimpleAssert.eq("delta", decode(WIRE.get(1)).getString("marker"), "public send preserves copy semantics");
            SimpleAssert.eq("ack", decode(WIRE.get(2)).getString("marker"), "ACK arrives after its baseline and delta");

            WIRE.clear();
            AtomicBoolean valid = new AtomicBoolean(true);
            HologramNetwork.SnapshotTicket stale = HologramNetwork.reserveState(player, "fixture", false);
            HologramNetwork.completeOwnedState(stale, state("stale"), valid::get, accepted::incrementAndGet);
            HologramNetwork.sendState(player, delta);
            tick();
            Runnable oldWorker = work.remove();
            valid.set(false);
            oldWorker.run();
            tick();
            SimpleAssert.that(!stale.current(), "invalid generation retires the complete dependent chain");
            SimpleAssert.eq(0, WIRE.size(), "stale worker result and dependent delta never enter wire");
            SimpleAssert.eq(0, work.size(), "dependent delta is never encoded after baseline cancellation");
            SimpleAssert.eq(1, accepted.get(), "stale worker cannot mark baseline cache ready");

            NBTTagCompound large = state("fragmented");
            byte[] random = new byte[2 * HologramSnapshotAssembly.CHUNK_BYTES + 23];
            new java.util.Random(721).nextBytes(random);
            large.setByteArray("payload", random);
            HologramNetwork.SnapshotTicket fragmented = HologramNetwork.reserveState(player, "fixture", false);
            HologramNetwork.completeOwnedState(fragmented, large, () -> true, () -> {});
            tick();
            work.remove()
                .run();
            while (fragmented.current()) {
                int before = WIRE.size();
                tick();
                SimpleAssert.eq(before + 1, WIRE.size(), "transport emits one bounded fragment per player and tick");
            }
            HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();
            byte[] assembled = null;
            for (IMessage message : WIRE) {
                ByteBuf buffer = Unpooled.buffer();
                try {
                    message.toBytes(buffer);
                    long sequence = buffer.readLong();
                    int total = buffer.readInt(), index = buffer.readInt(), length = buffer.readInt();
                    SimpleAssert.that(
                        length <= HologramSnapshotAssembly.CHUNK_BYTES,
                        "compressed fragment obeys production byte budget");
                    byte[] piece = new byte[length];
                    buffer.readBytes(piece);
                    assembled = assembly.accept(sequence, total, index, piece, System.nanoTime());
                } finally {
                    buffer.release();
                }
            }
            SimpleAssert.eq(
                "fragmented",
                HologramSnapshotCodec.decode(assembled)
                    .getString("marker"),
                "actual fragmented snapshot roundtrips after budgeted drain");
            WIRE.clear();
            HologramNetwork.SnapshotTicket stopped = HologramNetwork.reserveState(player, "fixture", false);
            HologramNetwork.completeOwnedState(stopped, state("stopped"), () -> true, accepted::incrementAndGet);
            tick();
            Runnable stoppedWorker = work.remove();
            HologramNetwork.clearServerQueues();
            stoppedWorker.run();
            tick();
            SimpleAssert.that(!stopped.current() && WIRE.isEmpty(), "server stop discards an in-flight worker result");
            SimpleAssert.eq(1, accepted.get(), "server stop cannot publish a late ready callback");

            retiredPreparations(player, work, accepted);
            failedPlanReopen(player, work);
            for (int i = 0; i < 8; i++) SimpleAssert.that(
                HologramNetwork.reserveState(player, "fixture", false) != null,
                "bounded pending slots accept requests up to player limit");
            SimpleAssert.eq(
                null,
                HologramNetwork.reserveState(player, "fixture", false),
                "pending DTO queue rejects overflow before accepting more NBT");
            HologramNetwork.clearServerQueues();
            SimpleAssert.eq(0, work.size(), "clear does not schedule additional worker work");
        } finally {
            HologramNetwork.clearServerQueues();
            channel.set(null, previousChannel);
            executor.set(null, previousExecutor);
            WIRE.clear();
        }
    }

    /** Exercise lifecycle cleanup through the real transport hook while WorkspaceScheduler never visits the task. */
    @SuppressWarnings("unchecked")
    private static void retiredPreparations(EntityPlayerMP player, Queue<Runnable> work, AtomicInteger accepted)
        throws ReflectiveOperationException {
        Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        java.util.Map<UUID, HologramSession> sessions = (java.util.Map<UUID, HologramSession>) sessionsField.get(null);
        HologramSession previous = sessions.get(player.getUniqueID());
        net.minecraft.network.NetHandlerPlayServer handler = player.playerNetServerHandler;
        try {
            for (boolean disconnect : new boolean[] { true, false }) {
                HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
                HologramWorkspace.activate(player.getUniqueID(), session);
                HologramSnapshotPreparation.start(session);
                SimpleAssert.that(
                    HologramSnapshotPreparation.tick(session),
                    "actual budget task builds its first metadata header");
                Field tasksField = HologramSnapshotPreparation.class.getDeclaredField("TASKS");
                tasksField.setAccessible(true);
                Object task = ((java.util.Map<?, ?>) tasksField.get(null)).get(session);
                Field stateField = task.getClass()
                    .getDeclaredField("state");
                stateField.setAccessible(true);
                NBTTagCompound owned = (NBTTagCompound) stateField.get(task);
                SimpleAssert.that(owned != null, "lifecycle fixture retains a real prepared metadata payload");
                Field ticketField = task.getClass()
                    .getDeclaredField("ticket");
                ticketField.setAccessible(true);
                HologramNetwork.SnapshotTicket ticket = (HologramNetwork.SnapshotTicket) ticketField.get(task);
                HologramNetwork.completeOwnedState(ticket, owned, () -> true, accepted::incrementAndGet);
                tick();
                Runnable worker = work.remove();
                WIRE.clear();
                if (disconnect) player.playerNetServerHandler = null;
                else HologramWorkspace.activate(player.getUniqueID(), new HologramSession(player, 0, 64, 0, false, 2));
                tick();
                SimpleAssert.that(
                    !HologramSnapshotPreparation.pending(session),
                    "retired session releases preparation slot without another workspace tick");
                SimpleAssert.eq(null, stateField.get(task), "retired task releases its owned full NBT reference");
                Field workerOwned = HologramSnapshotEncoding.Job.class.getDeclaredField("owned");
                workerOwned.setAccessible(true);
                SimpleAssert
                    .eq(null, workerOwned.get(worker), "cancelled queued encoder immediately releases owned NBT");
                SimpleAssert.that(!ticket.current(), "retired session invalidates the entire reserved baseline chain");
                player.playerNetServerHandler = handler;
                worker.run();
                tick();
                SimpleAssert
                    .eq(0, WIRE.size(), "disconnected or replaced preparation cannot publish a late worker result");
                SimpleAssert.eq(1, accepted.get(), "retired preparation never invokes the ready callback");
            }
        } finally {
            player.playerNetServerHandler = handler;
            if (previous == null) sessions.remove(player.getUniqueID());
            else sessions.put(player.getUniqueID(), previous);
            HologramSnapshotPreparation.pruneRetired();
            WIRE.clear();
        }
    }

    /** Error -> UI close/reopen -> safe draft full -> edited retry -> one complete budgeted full. */
    @SuppressWarnings("unchecked")
    private static void failedPlanReopen(EntityPlayerMP player, Queue<Runnable> work)
        throws ReflectiveOperationException, java.io.IOException {
        Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        java.util.Map<UUID, HologramSession> sessions = (java.util.Map<UUID, HologramSession>) sessionsField.get(null);
        HologramSession previous = sessions.get(player.getUniqueID());
        java.lang.reflect.Method factory = HologramResourcePlannerRegression.class
            .getDeclaredMethod("session", EntityPlayerMP.class, int.class);
        factory.setAccessible(true);
        HologramSession s = (HologramSession) factory.invoke(null, player, 3);
        s.virtualPreset.schema = 2;
        s.virtualPreset.id = "snapshot_error_reopen";
        s.virtualPreset.name = "Snapshot error reopen";
        s.virtualPreset.controllerType = "";
        s.virtualPreset.sourceMod = "minecraft";
        s.virtualPreset.sourceClass = "";
        s.virtualPreset.prompt = "";
        s.virtualPreset.authorName = "SnapshotFixture";
        s.virtualPreset.authorUuid = player.getUniqueID()
            .toString();
        HologramPresets.validate(s.virtualPreset);
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            s.capture.positions.put(HologramValues.position(cell), cell);
            s.cellIndices.put(cell, i);
            cell.stableIndex = i;
        }
        s.indexedCapture = s.capture;
        s.cellDeltaProtocol = true;
        s.selected = 0;
        s.materialSettings.me = false;
        s.materialSettings.containers = false;
        s.materialContext = new HologramMaterials.Context(player, s.materialSettings);
        HologramWorkspace.activate(player.getUniqueID(), s);
        try {
            int invalidIndex = s.capture.cells.size() + 1;
            s.allocationsFrozen = false;
            s.autoTargets.put(invalidIndex, new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone));
            HologramDeferredPlanRefresh.request(s, java.util.Collections.singleton(0));
            HologramDeferredPlanRefresh.tick(s, 100, 1_000_000_000L, HologramLocalPlanRefresh::completed);
            SimpleAssert.that(
                s.localPlanPending && !s.localPlanError.isEmpty(),
                "real deferred planner failure remains fenced");
            s.pins.put(0, new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.dirt));
            s.planTargets.add(new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.diamond_block));
            s.planFacings.put(0, 5);
            s.planMissing = 98765;
            s.blueprintResources = new HologramBlueprintResources.Plan();
            s.blueprintResources.items.add(new net.minecraft.item.ItemStack(net.minecraft.init.Items.diamond, 63));
            HologramProjectorWorkflow.libraryCloseUi(s);
            s.open = true;
            s.openPurpose = "blueprintEdit";
            WIRE.clear();
            HologramSnapshots.send(s);
            drainPublication(s, work);
            NBTTagCompound draft = latestFull();
            SimpleAssert.that(
                draft.getBoolean("open") && draft.getBoolean("localPlanPending")
                    && draft.getBoolean("planIncomplete")
                    && draft.getBoolean("quotesIncomplete"),
                "failed planning can reopen the UI with an explicitly incomplete draft baseline");
            SimpleAssert.eq(
                s.localPlanError,
                draft.getString("localPlanError"),
                "reopened draft retains the actual planning error");
            SimpleAssert.eq(
                0,
                draft.getTagList("materials", 10)
                    .tagCount(),
                "draft full contains no partial shell material quote");
            SimpleAssert.eq(
                0,
                draft.getTagList("blueprintResources", 10)
                    .tagCount(),
                "draft full contains no partial native resource quote");
            SimpleAssert.eq(0, draft.getInteger("missing"), "draft full cannot leak a half-calculated shortage count");
            NBTTagList rows = draft.getTagList("cells", 10);
            SimpleAssert.eq(3, rows.tagCount(), "safe draft full still contains every authoring geometry row");
            for (int i = 0; i < rows.tagCount(); i++) {
                SimpleAssert.eq(
                    "planning",
                    rows.getCompoundTagAt(i)
                        .getString("status"),
                    "every draft cell is explicitly pending");
                SimpleAssert.eq(
                    s.localPlanError,
                    rows.getCompoundTagAt(i)
                        .getString("reason"),
                    "every draft cell carries the error");
                SimpleAssert.that(
                    !rows.getCompoundTagAt(i)
                        .hasKey("candidateAvailable"),
                    "draft candidates contain no availability quote");
            }
            SimpleAssert.eq(
                "minecraft:dirt",
                rows.getCompoundTagAt(0)
                    .getString("wantId"),
                "draft preview uses edited authoring pin rather than partial plan target");
            SimpleAssert.eq(
                -1,
                rows.getCompoundTagAt(0)
                    .getInteger("targetFacing"),
                "draft preview omits half-calculated automatic facing");
            SimpleAssert.eq(
                -1,
                s.processingSource.cells.get(0).facing,
                "draft processing projection omits half-calculated facing");
            SimpleAssert.that(
                HologramDeferredPlanRefresh.blocksAction(s, "build")
                    && HologramDeferredPlanRefresh.blocksAction(s, "saveBlueprintAs"),
                "draft reopening never enables incomplete-plan consumers");
            s.autoTargets.remove(invalidIndex);
            s.pins.put(0, new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.cobblestone));
            s.processingSource = null;
            s.processingPolicy = null;
            HologramDeferredPlanRefresh.request(s, java.util.Collections.singleton(0));
            WIRE.clear();
            drainPublication(s, work);
            NBTTagCompound complete = latestFull();
            SimpleAssert.that(
                !complete.getBoolean("localPlanPending") && !complete.getBoolean("planIncomplete")
                    && !complete.getBoolean("quotesIncomplete"),
                "successful retry publishes a complete full baseline and clears inherited incomplete flags");
            for (int i = 0; i < complete.getTagList("cells", 10)
                .tagCount(); i++)
                SimpleAssert.that(
                    !"planning".equals(
                        complete.getTagList("cells", 10)
                            .getCompoundTagAt(i)
                            .getString("status")),
                    "successful full restores every previously planning cell, including unchanged cells");
            SimpleAssert.eq(
                "minecraft:cobblestone",
                complete.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .getString("wantId"),
                "successful retry full contains the latest edit");
        } finally {
            HologramSnapshotPreparation.cancel(s);
            HologramDeferredPlanRefresh.cancel(s);
            if (previous == null) sessions.remove(player.getUniqueID());
            else sessions.put(player.getUniqueID(), previous);
            HologramNetwork.clearServerQueues();
            WIRE.clear();
        }
    }

    private static void drainPublication(HologramSession s, Queue<Runnable> work) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            HologramWorkspaceScheduler.tick();
            while (!work.isEmpty()) work.remove()
                .run();
            tick();
            if (System.nanoTime() >= deadline)
                throw new AssertionError("Budgeted full publication did not finish: " + s.localPlanError);
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        } while (s.localPlanError.isEmpty() && s.localPlanPending || HologramSnapshotPreparation.pending(s)
            || !work.isEmpty());
        tick();
    }

    private static NBTTagCompound latestFull() throws java.io.IOException {
        NBTTagCompound full = null;
        for (IMessage packet : WIRE) if (packet instanceof HologramNetwork.Fragment) {
            NBTTagCompound state = decode(packet);
            if (state.hasKey("cells", 9) && !state.getBoolean("partial")) full = state;
        }
        SimpleAssert.that(full != null, "actual transport emits a complete full snapshot");
        return full;
    }

    private static NBTTagCompound state(String marker) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("session", "fixture");
        tag.setString("marker", marker);
        tag.setLong("planRevision", 1);
        return tag;
    }

    private static void tick() {
        new HologramNetwork.TransportTicks().tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
    }

    private static NBTTagCompound decode(IMessage message) throws java.io.IOException {
        ByteBuf buffer = Unpooled.buffer();
        try {
            message.toBytes(buffer);
            buffer.readLong();
            int total = buffer.readInt();
            SimpleAssert.eq(0, buffer.readInt(), "small fixture needs only the first fragment");
            int length = buffer.readInt();
            SimpleAssert.eq(total, length, "wire fragment contains the complete compressed fixture");
            byte[] bytes = new byte[length];
            buffer.readBytes(bytes);
            return HologramSnapshotCodec.decode(bytes);
        } finally {
            buffer.release();
        }
    }

    private static final class RecordingChannel extends SimpleNetworkWrapper {

        private RecordingChannel() {
            super("snapshot_fixture");
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP player) {
            WIRE.add(message);
        }
    }
}
