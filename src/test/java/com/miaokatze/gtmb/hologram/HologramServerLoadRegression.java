// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.ICrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Actual codec counters, bounded scheduling, fairness and retained publication results; no world writes. */
public final class HologramServerLoadRegression {

    private HologramServerLoadRegression() {}

    public static void run() {
        boolean previous = HologramRuntimeMetrics.isEnabled();
        try {
            loadStageTrace();
            metricsAndCodecs();
            admission();
            publication();
            productionJobDeltas();
            projectorIdentityInventoryLifecycle();
            fairRounds();
            controllerRequestBudget();
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

    private static void projectorIdentityInventoryLifecycle() throws ReflectiveOperationException {
        Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) access.get(null);
        var world = HologramPlacementFixtureWorld.create(unsafe);
        EntityPlayerMP player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "InventoryIdentity");
        Channel original = player.playerNetServerHandler.netManager.channel();
        var channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        var fakeChannel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        FakePlayer fake = (FakePlayer) unsafe.allocateInstance(FakePlayer.class);
        try {
            Item registered = registeredIdentityFixture();
            ItemStack tool = new ItemStack(registered);
            player.inventory.mainInventory[0] = tool;
            ItemStack other = new ItemStack(registered);
            player.inventory.mainInventory[1] = other;
            var preferences = new HologramMaterials.Settings();
            preferences.priority = 1;
            preferences.me = false;
            HologramMaterials.write(tool, preferences);
            NBTTagCompound configured = (NBTTagCompound) tool.getTagCompound()
                .copy();

            var reentry = new AtomicReference<Runnable>();
            NetworkManager network = new NetworkManager(false) {

                @Override
                public INetHandler getNetHandler() {
                    Runnable callback = reentry.getAndSet(null);
                    if (callback != null) callback.run();
                    return super.getNetHandler();
                }
            };
            Field channelField = NetworkManager.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            channelField.set(network, channel);
            new NetHandlerPlayServer(null, network, player);
            player.inventory.decrementAnimations();
            SimpleAssert.eq(configured, tool.getTagCompound(), "a live socket alone can't initialize before login");
            new HologramLifecycle().loggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
            var login = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
            SimpleAssert.that(
                login != null && HologramWorkspace.constructionGate(login) != null,
                "the production login event establishes authority without any GTMB action");

            // Exercise the real vanilla inventory tick and normal hotbar differential slot producer.
            ContainerPlayer window = new ContainerPlayer(player.inventory, false, player);
            List<S2FPacketSetSlot> deltas = new ArrayList<>();
            window.addCraftingToCrafters(new ICrafting() {

                @Override
                public void sendContainerAndContentsToPlayer(Container container, List<ItemStack> items) {}

                @Override
                public void sendSlotContents(Container container, int slot, ItemStack stack) {
                    deltas.add(new S2FPacketSetSlot(container.windowId, slot, stack));
                }

                @Override
                public void sendProgressBarUpdate(Container container, int channel, int value) {}
            });
            deltas.clear();
            player.inventory.decrementAnimations();
            window.detectAndSendChanges();
            String id = tool.getTagCompound()
                .getString(HologramSessionKeys.TOOL_ID);
            SimpleAssert.that(
                HologramNativeCompactStart.canonical(id) && !other.hasTagCompound(),
                "only the actual selected fresh projector acquires canonical server identity");
            NBTTagCompound retained = (NBTTagCompound) tool.getTagCompound()
                .copy();
            retained.removeTag(HologramSessionKeys.TOOL_ID);
            SimpleAssert.eq(configured, retained, "initializing an identity preserves all source preferences");
            SimpleAssert.eq(1, deltas.size(), "vanilla container emits one held hotbar slot delta");
            assertVanillaToolIdentity(deltas.get(0), registered, id);
            player.inventory.decrementAnimations();
            window.detectAndSendChanges();
            SimpleAssert.eq(
                id,
                tool.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID),
                "a second real inventory tick does not remint");
            SimpleAssert.eq(1, deltas.size(), "stable identity causes no repeat vanilla slot delta");

            // The transport getter can reenter a mod callback; even a new blank selected stack is distinct.
            ItemStack old = new ItemStack(registered), successor = new ItemStack(registered);
            player.inventory.mainInventory[0] = old;
            reentry.set(() -> player.inventory.mainInventory[0] = successor);
            player.inventory.decrementAnimations();
            SimpleAssert.that(
                reentry.get() == null && !old.hasTagCompound() && !successor.hasTagCompound(),
                "a swap inside a server transport callback never binds the stale item or the replacement");
            player.inventory.decrementAnimations();
            SimpleAssert.that(
                HologramNativeCompactStart.canonical(
                    successor.getTagCompound()
                        .getString(HologramSessionKeys.TOOL_ID))
                    && !old.hasTagCompound(),
                "the replacement can acquire its own identity on its own real inventory tick");

            ItemStack mutated = new ItemStack(registered);
            Item changedType = new ItemNekoHologramProjector();
            player.inventory.mainInventory[0] = mutated;
            reentry.set(() -> mutated.func_150996_a(changedType));
            player.inventory.decrementAnimations();
            SimpleAssert.that(
                mutated.getItem() == changedType && !mutated.hasTagCompound(),
                "a changed item identity on the same stack cannot inherit the old type proof");

            ItemStack changedScope = new ItemStack(registered);
            player.inventory.mainInventory[0] = changedScope;
            // Reenter specifically during the second transport check, after the original scope looked current.
            reentry.set(() -> reentry.set(() -> HologramWorkspace.publishActionDimension(player)));
            player.inventory.decrementAnimations();
            SimpleAssert.that(
                !changedScope.hasTagCompound() && !HologramWorkspace.currentActionEndpoint(login)
                    && HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler) != null,
                "same-object lifecycle rotation during the transport proof never initializes the old observation");

            ItemStack malformed = new ItemStack(registered);
            malformed.setTagCompound(new NBTTagCompound());
            malformed.getTagCompound()
                .setInteger(HologramSessionKeys.TOOL_ID, 7);
            player.inventory.mainInventory[0] = malformed;
            player.inventory.decrementAnimations();
            SimpleAssert.eq(
                7,
                malformed.getTagCompound()
                    .getInteger(HologramSessionKeys.TOOL_ID),
                "unexpected existing identity tags are left for normal admission to refuse");
            player.inventory.mainInventory[0] = old;
            world.isRemote = true;
            player.inventory.decrementAnimations();
            world.isRemote = false;
            SimpleAssert.that(!old.hasTagCompound(), "a client-side tick cannot mint the server identity");

            Field fakeId = Entity.class.getDeclaredField("entityUniqueID");
            fakeId.setAccessible(true);
            fakeId.set(fake, UUID.randomUUID());
            fake.worldObj = world;
            fake.inventory = new InventoryPlayer(fake);
            fake.inventory.mainInventory[0] = new ItemStack(registered);
            NetworkManager fakeNetwork = new NetworkManager(false);
            channelField.set(fakeNetwork, fakeChannel);
            new NetHandlerPlayServer(null, fakeNetwork, fake);
            HologramWorkspace.publishActionEndpoint(fake, false);
            fake.inventory.decrementAnimations();
            SimpleAssert.that(
                !fake.inventory.mainInventory[0].hasTagCompound(),
                "even an attached FakePlayer does not initialize a selected projector");
            HologramWorkspace.revokeActionEndpoint(player);
            player.inventory.decrementAnimations();
            SimpleAssert
                .that(!old.hasTagCompound(), "retiring login with its socket still open prevents initialization");
            new HologramLifecycle().loggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
            channel.close();
            player.inventory.decrementAnimations();
            SimpleAssert.that(!old.hasTagCompound(), "a closed endpoint does not initialize a held item");
        } finally {
            world.isRemote = false;
            HologramWorkspace.revokeActionEndpoint(player);
            if (fake.getUniqueID() != null) HologramWorkspace.revokeActionEndpoint(fake);
            fakeChannel.close();
            channel.close();
            original.close();
        }
    }

    private static Item registeredIdentityFixture() {
        String key = "gtmb:projector_identity_inventory_fixture";
        Item item = (Item) Item.itemRegistry.getObject(key);
        if (item == null) {
            int id = 30000;
            while (id < 32000 && Item.getItemById(id) != null) id++;
            SimpleAssert.that(id < 32000, "an available non-block fixture item ID is required");
            item = new ItemNekoHologramProjector();
            Item.itemRegistry.addObject(id, key, item);
            SimpleAssert.that(Item.getItemById(id) == item, "fixture participates in real vanilla registry decoding");
        }
        SimpleAssert.that(item instanceof ItemNekoHologramProjector, "exact isolated projector fixture type");
        return item;
    }

    private static void assertVanillaToolIdentity(S2FPacketSetSlot delta, Item registered, String identity)
        throws ReflectiveOperationException {
        Field contents = S2FPacketSetSlot.class.getDeclaredField("field_149178_c");
        contents.setAccessible(true);
        ItemStack received = vanillaSlotRoundTrip(delta, contents);
        SimpleAssert.that(
            received != null && received.getItem() == registered,
            "normal vanilla slot deserialization resolves the actual registered projector");
        SimpleAssert.eq(
            identity,
            received.getTagCompound()
                .getString(HologramSessionKeys.TOOL_ID),
            "vanilla transfers the exact identity without a GTMB message");
        SimpleAssert.that(
            vanillaSlotRoundTrip(new S2FPacketSetSlot(0, 36, null), contents) == null,
            "the same vanilla path also decodes the actual empty-slot form");
    }

    private static ItemStack vanillaSlotRoundTrip(S2FPacketSetSlot packet, Field contents)
        throws IllegalAccessException {
        PacketBuffer bytes = new PacketBuffer(Unpooled.buffer());
        try {
            packet.writePacketData(bytes);
            SimpleAssert.eq(0, (int) bytes.getUnsignedByte(0), "ordinary survival inventory window");
            SimpleAssert.eq(36, bytes.getUnsignedShort(1), "selected slot maps to the vanilla hotbar slot");
            S2FPacketSetSlot decoded = new S2FPacketSetSlot();
            decoded.readPacketData(bytes);
            SimpleAssert.that(!bytes.isReadable(), "actual vanilla reader consumes the whole slot DTO");
            return (ItemStack) contents.get(decoded);
        } catch (IOException failure) {
            throw new AssertionError("vanilla held projector slot wire format", failure);
        } finally {
            bytes.release();
        }
    }

    private static void loadStageTrace() {
        HologramRuntimeMetrics.setEnabled(false);
        HologramRuntimeMetrics.reset();
        HologramRuntimeMetrics.loadStage(HologramRuntimeMetrics.LoadStage.CLIENT_INSTALL, (NBTTagCompound) null, 1, 1);
        SimpleAssert.eq(
            0,
            HologramRuntimeMetrics.snapshot()
                .getTagList("loadStages", 10)
                .tagCount(),
            "disabled tracing returns before accessing diagnostic input");
        HologramRuntimeMetrics.setEnabled(true);
        for (int i = 0; i < 270; i++) HologramRuntimeMetrics
            .loadStage(HologramRuntimeMetrics.LoadStage.CLIENT_INSTALL, "new-session", 8, 9, i, i, "old-session", 7);
        NBTTagCompound captured = HologramRuntimeMetrics.snapshot();
        NBTTagList rows = captured.getTagList("loadStages", 10);
        SimpleAssert.eq(256, rows.tagCount(), "load traces retain a fixed number of small records");
        SimpleAssert.eq(14L, captured.getLong("loadTraceDropped"), "trace overflow retires only the oldest records");
        SimpleAssert.eq(
            14L,
            rows.getCompoundTagAt(0)
                .getLong("transportSequence"),
            "trace order survives bounded eviction");
        NBTTagCompound last = rows.getCompoundTagAt(255);
        SimpleAssert.eq(
            "old-session",
            last.getString("previousSession"),
            "installed/displayed predecessor identity stays separate");
        SimpleAssert.eq(8L, last.getLong("revision"), "exact source revision is retained");
        SimpleAssert.eq(9L, last.getLong("requestSequence"), "request identity is retained independently of transport");
        SimpleAssert.that(
            !captured.getString("loadClockId")
                .isEmpty(),
            "a clock domain accompanies local timestamps");
        last.setString("session", "changed reader");
        SimpleAssert.eq(
            "new-session",
            HologramRuntimeMetrics.snapshot()
                .getTagList("loadStages", 10)
                .getCompoundTagAt(255)
                .getString("session"),
            "trace readers own detached NBT records");
        HologramRuntimeMetrics.reset();
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "encoding-session");
        source.setLong("planRevision", 12);
        source.setLong("uiSequence", 13);
        HologramSnapshotEncoding.Job job = HologramSnapshotEncoding.submit(source, Runnable::run);
        SimpleAssert
            .that(job != null && job.done && job.failure == null, "the real synchronous codec fixture completes");
        rows = HologramRuntimeMetrics.snapshot()
            .getTagList("loadStages", 10);
        SimpleAssert.eq(
            "SERVER_ENCODER_QUEUED",
            rows.getCompoundTagAt(0)
                .getString("stage"),
            "queue timestamp precedes executor entry");
        SimpleAssert.eq(
            "SERVER_ENCODER_BEGIN",
            rows.getCompoundTagAt(1)
                .getString("stage"),
            "encoder execution has its own boundary");
        SimpleAssert.eq(
            "SERVER_ENCODER_END",
            rows.getCompoundTagAt(2)
                .getString("stage"),
            "encoder completion is not conflated with queue admission");
        SimpleAssert.eq(
            "encoding-session",
            rows.getCompoundTagAt(2)
                .getString("session"),
            "worker timing uses frozen source identity");
        SimpleAssert.eq(
            12L,
            rows.getCompoundTagAt(2)
                .getLong("revision"),
            "worker timing keeps the encoded revision");
        SimpleAssert.eq(
            (long) job.bytes.length,
            rows.getCompoundTagAt(2)
                .getLong("count"),
            "codec timing reports actual compressed bytes");
        HologramRuntimeMetrics.setEnabled(false);
        HologramRuntimeMetrics.reset();
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

    private static void controllerRequestBudget() throws ReflectiveOperationException {
        final long ms = 1_000_000L;
        AtomicLong clock = new AtomicLong(10 * ms);
        SimpleAssert.that(
            !HologramWorkBudget.beginCaptureRequest(clock::get),
            "detached controller calls cannot acquire a game tick quota");
        Field started = HologramWorkBudget.class.getDeclaredField("serverTickStarted");
        started.setAccessible(true);
        HologramWorkBudget.startServerTick();
        started.setLong(null, 0L);
        try {
            SimpleAssert.that(
                HologramWorkBudget.beginCaptureRequest(clock::get),
                "first game controller request acquires the existing cooperative budget");
            try {
                SimpleAssert.eq(5 * ms, HologramWorkBudget.remainingNanos(100 * ms), "first request gets one slice");
                SimpleAssert.eq(
                    15 * ms,
                    HologramWorkBudget.captureDeadline(110 * ms),
                    "resumable walking clips the original native deadline after discovery");
                clock.set(16 * ms);
                SimpleAssert.eq(
                    0L,
                    HologramWorkBudget.remainingNanos(5 * ms),
                    "atomic discovery consuming the slice leaves no new finish/planning budget");
                SimpleAssert.that(
                    !HologramWorkBudget.beginCaptureRequest(clock::get),
                    "nested controller work cannot reset an existing request or continuation quota");
                SimpleAssert.eq(0L, HologramWorkBudget.remainingNanos(5 * ms), "nested admission grants no extra work");
            } finally {
                HologramWorkBudget.end();
            }
            clock.set(39 * ms);
            SimpleAssert
                .that(HologramWorkBudget.beginCaptureRequest(clock::get), "later request acquires its own scope");
            try {
                SimpleAssert.eq(
                    5 * ms,
                    HologramWorkBudget.remainingNanos(100 * ms),
                    "request honors the 5 ms minimum tick slack");
            } finally {
                HologramWorkBudget.end();
            }
            HologramWorkBudget.begin(42 * ms, clock::get);
            try {
                SimpleAssert.that(
                    !HologramWorkBudget.beginCaptureRequest(clock::get),
                    "workspace continuation keeps its previously assigned quota");
                SimpleAssert.eq(3 * ms, HologramWorkBudget.remainingNanos(100 * ms), "workspace quota remains intact");
            } finally {
                HologramWorkBudget.end();
            }
        } finally {
            HologramWorkBudget.endServerTick();
        }
        SimpleAssert.that(!HologramWorkBudget.beginCaptureRequest(clock::get), "game scope ends with the server tick");
        SimpleAssert
            .eq(100 * ms, HologramWorkBudget.captureDeadline(100 * ms), "standalone callback contract remains 100 ms");
    }

    private static void adaptiveTickSlack() throws ReflectiveOperationException {
        final long ms = 1_000_000L;
        SimpleAssert.eq(
            40 * ms,
            HologramWorkBudget.tickNanos(100 * ms, 100 * ms),
            "idle tick exposes spare time through 40 ms");
        SimpleAssert
            .eq(15 * ms, HologramWorkBudget.tickNanos(100 * ms, 125 * ms), "world work reduces spare tick time");
        SimpleAssert.eq(
            5 * ms,
            HologramWorkBudget.tickNanos(100 * ms, 160 * ms),
            "busy tick retains only a minimum progress slice");
        Field cursor = HologramWorkspaceScheduler.class.getDeclaredField("nextSession");
        cursor.setAccessible(true);
        int previous = cursor.getInt(null);
        try {
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
                Arrays.asList("idle", "a", "b", "a+", "b+"),
                order,
                "each peer gets its first slice before extra work");
            SimpleAssert.eq(
                Arrays.asList(15 * ms, 15 * ms),
                quotas,
                "active peers fairly share spare time without reserving idle quotas");
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
            authenticatedActionContext(player, world, factory, unsafe);
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

    private static void authenticatedActionContext(EntityPlayerMP player,
        HologramServiceEditRegression.ReadOnlyWorld world, Method factory, sun.misc.Unsafe unsafe)
        throws ReflectiveOperationException {
        NetHandlerPlayServer connection = player.playerNetServerHandler;
        NetworkManager network = connection.netManager;
        Channel initial = network.channel();
        int originalDimension = player.dimension;
        Field channelField = null;
        for (Field candidate : NetworkManager.class.getDeclaredFields()) if (candidate.getType() == Channel.class) {
            channelField = candidate;
            channelField.setAccessible(true);
        }
        if (channelField == null) throw new AssertionError("Missing NetworkManager channel field");
        Channel replacement = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        HologramRequestGate queue = new HologramRequestGate(1, 1);
        HologramLifecycle lifecycle = new HologramLifecycle();
        EntityPlayerMP respawned = null;
        HologramConstructionTaskGate taskGate = null;
        HologramConstructionTaskGate.Admission oldJob = null;
        HologramConstructionTaskGate.Permit uncertain = null;
        int[] dispatched = { 0 };
        Runnable dispatch = () -> dispatched[0]++;
        try {
            SimpleAssert.that(
                HologramWorkspace.captureActionEndpoint(connection) == null,
                "ingress cannot mint an unpublished authentication generation");
            HologramWorkspace.publishActionEndpoint(player, false);
            HologramWorkspace.ActionEndpoint first = HologramWorkspace.captureActionEndpoint(connection);
            SimpleAssert.that(
                first != null && first.player == player && first.connection == connection,
                "the actual authenticated handler captures the initial published endpoint");
            HologramWorkspace.publishActionEndpoint(player, false);
            SimpleAssert.that(
                first == HologramWorkspace.captureActionEndpoint(connection),
                "a repeated unchanged lifecycle notification cannot reset the generation");
            taskGate = HologramWorkspace.constructionGate(first);
            SimpleAssert.that(
                taskGate != null && first.constructionScope != null,
                "trusted lifecycle binds the construction scope without any construction packet");
            HologramConstructionTaskGate.Request oldIntent = new HologramConstructionTaskGate.Request(
                java.util.UUID.randomUUID(),
                1,
                HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE);
            oldJob = taskGate.acquire(first.constructionScope, oldIntent, false, false);
            SimpleAssert.that(
                oldJob.decision == HologramConstructionTaskGate.Decision.GRANTED,
                "the exact authenticated endpoint can hold a scope lease");
            uncertain = taskGate.beginTransaction(oldJob.lease);
            SimpleAssert.that(taskGate.transactionCurrent(uncertain), "one exact permit owns the next mutation");

            HologramRequestGate.Lease oldWorld = queue.acquire(connection);
            player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            player.dimension = originalDimension + 1;
            lifecycle.changedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(player, originalDimension, player.dimension));
            HologramWorkspace.ActionEndpoint otherWorld = HologramWorkspace.captureActionEndpoint(connection);
            SimpleAssert.that(otherWorld != null && otherWorld != first, "the destination is immediately usable");
            SimpleAssert.that(
                HologramWorkspace.constructionGate(first) == null
                    && HologramWorkspace.constructionGate(otherWorld) == taskGate
                    && otherWorld.constructionScope != first.constructionScope
                    && !taskGate.transactionCurrent(uncertain)
                    && !taskGate.revoke(first.constructionScope)
                    && taskGate.settle(uncertain, HologramConstructionTaskGate.Settlement.RECOVERY_PENDING),
                "real lifecycle rotation fences old work but retains its exact unfinished permit");
            player.worldObj = world;
            player.dimension = originalDimension;
            lifecycle.changedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(player, originalDimension + 1, originalDimension));
            HologramWorkspace.ActionEndpoint returned = HologramWorkspace.captureActionEndpoint(connection);
            SimpleAssert.that(
                returned != null && returned != first && returned != otherWorld,
                "a round trip to the same World instance uses a new lifecycle generation");
            HologramNetwork.runQueuedAction(first, oldWorld, dispatch);
            SimpleAssert.eq(0, dispatched[0], "same handler and exact World object cannot revive a retired Action");
            SimpleAssert.eq(0, queue.size(), "retired generation releases just its pending admission");
            HologramRequestGate.Lease beforeNestedReturn = queue.acquire(connection);
            player.worldObj = otherWorld.world;
            player.dimension = originalDimension + 1;
            // A prior synchronous listener on A -> B transfers back before GTMB sees the outer event.
            player.worldObj = world;
            player.dimension = originalDimension;
            lifecycle.changedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(player, originalDimension + 1, originalDimension));
            HologramWorkspace.ActionEndpoint nestedReturn = HologramWorkspace.captureActionEndpoint(connection);
            SimpleAssert.that(
                nestedReturn != null && nestedReturn != returned
                    && nestedReturn.constructionScope.generation > returned.constructionScope.generation,
                "nested return retires both prior endpoint and construction scope without seeing the departure");
            HologramConstructionTaskGate.Request denied = new HologramConstructionTaskGate.Request(
                java.util.UUID.randomUUID(),
                1,
                HologramConstructionTaskGate.Kind.PROJECTED_LAYER);
            SimpleAssert.that(
                taskGate.acquire(nestedReturn.constructionScope, denied, false, false).decision
                    == HologramConstructionTaskGate.Decision.RECOVERY_PENDING && taskGate.busy(player.getUniqueID()),
                "a later lifecycle cannot bypass uncertainty even without a positive journal hint");
            SimpleAssert.that(
                taskGate.settle(uncertain, HologramConstructionTaskGate.Settlement.ROLLED_BACK)
                    && !taskGate.busy(player.getUniqueID())
                    && taskGate.acquire(nestedReturn.constructionScope, denied, false, false).decision
                        == HologramConstructionTaskGate.Decision.RETIRED_UNKNOWN,
                "only exact authoritative settlement releases the old hold; a refused request is never revived");
            lifecycle.changedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(player, originalDimension, originalDimension + 1));
            lifecycle.changedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(player, originalDimension, originalDimension));
            SimpleAssert.that(
                nestedReturn == HologramWorkspace.captureActionEndpoint(connection),
                "the stale outer destination and same-dimension duplicate do not rotate the current generation");
            HologramNetwork.runQueuedAction(returned, beforeNestedReturn, dispatch);
            SimpleAssert
                .eq(0, dispatched[0], "nested dimension return cannot revive an Action queued before departure");
            HologramNetwork.runQueuedAction(nestedReturn, queue.acquire(connection), dispatch);
            SimpleAssert.eq(1, dispatched[0], "fresh action after actual dimension publication dispatches");

            HologramRequestGate.Lease oldChannel = queue.acquire(connection);
            channelField.set(network, replacement);
            SimpleAssert.that(
                HologramWorkspace.captureActionEndpoint(connection) == null,
                "an open replacement channel is not the context previously authenticated");
            HologramWorkspace.publishActionEndpoint(player, false);
            HologramWorkspace.ActionEndpoint replaced = HologramWorkspace.captureActionEndpoint(connection);
            SimpleAssert
                .that(replaced != null && replaced != nestedReturn, "only lifecycle can publish the changed transport");
            HologramNetwork.runQueuedAction(nestedReturn, oldChannel, dispatch);
            SimpleAssert.eq(1, dispatched[0], "a queued Action cannot redirect to a newly open channel");

            respawned = (EntityPlayerMP) factory.invoke(null, unsafe, world, player.getUniqueID(), "RespawnedFixture");
            respawned.playerNetServerHandler.netManager.channel()
                .close();
            respawned.playerNetServerHandler = connection;
            respawned.dimension = originalDimension;
            HologramRequestGate.Lease beforeRespawn = queue.acquire(connection);
            HologramWorkspace.publishActionEndpoint(respawned, true);
            SimpleAssert.that(
                HologramWorkspace.captureActionEndpoint(connection) == null,
                "the stock 1.7.10 respawn event retires the old player before handler assignment");
            // Later Forge listeners can move the exact successor before vanilla updates handler.playerEntity.
            respawned.worldObj = otherWorld.world;
            respawned.dimension = originalDimension + 1;
            lifecycle.changedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(respawned, originalDimension, respawned.dimension));
            lifecycle.changedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(player, originalDimension + 1, originalDimension));
            SimpleAssert.that(
                HologramWorkspace.captureActionEndpoint(connection) == null,
                "refreshing the provisional destination neither opens ingress nor lets the predecessor return");
            connection.playerEntity = respawned;
            HologramWorkspace.ActionEndpoint successor = HologramWorkspace.captureActionEndpoint(connection);
            SimpleAssert.that(
                successor != null && successor.player == respawned
                    && successor.world == otherWorld.world
                    && HologramWorkspace.currentActionEndpoint(successor),
                "after the handler transfer, normal ingress uses the provisional successor's final destination");
            HologramWorkspace.revokeActionEndpoint(player);
            SimpleAssert.that(
                successor == HologramWorkspace.captureActionEndpoint(connection)
                    && HologramWorkspace.constructionGate(successor) == taskGate
                    && !taskGate.revoke(replaced.constructionScope),
                "late retirement for the old entity cannot remove the successor's endpoint or construction scope");
            HologramNetwork.runQueuedAction(replaced, beforeRespawn, dispatch);
            SimpleAssert.eq(1, dispatched[0], "the old entity cannot act after authenticated respawn");
            HologramNetwork.runQueuedAction(successor, queue.acquire(connection), dispatch);
            SimpleAssert.eq(2, dispatched[0], "the newly authenticated player can act normally");
            HologramWorkspace.revokeActionEndpoint(respawned);
            SimpleAssert.that(
                HologramWorkspace.captureActionEndpoint(connection) == null
                    && HologramWorkspace.constructionGate(successor) == null
                    && !taskGate.scopeCurrent(successor.constructionScope),
                "logout revokes the exact scope and delayed packets never create a new authenticated generation");
        } finally {
            // Pure test permits have no game or journal side effects, even after an earlier assertion.
            if (taskGate != null) {
                if (uncertain != null) taskGate.settle(uncertain, HologramConstructionTaskGate.Settlement.ROLLED_BACK);
                if (oldJob != null && oldJob.lease != null)
                    taskGate.finish(oldJob.lease, HologramConstructionTaskGate.End.FAILED);
            }
            if (respawned != null) HologramWorkspace.revokeActionEndpoint(respawned);
            HologramWorkspace.revokeActionEndpoint(player);
            player.worldObj = world;
            player.dimension = originalDimension;
            connection.playerEntity = player;
            channelField.set(network, initial);
            replacement.close();
            queue.clear();
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
