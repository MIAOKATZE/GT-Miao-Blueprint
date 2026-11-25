// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IChatComponent;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Phase;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reason;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reply;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Request;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Actual saved-file loader, confirmed-submit entry, worker, registered reply decoder and client tick lifetime.
 * The inbound server receipts are controlled fixture inputs: this tests client wiring, not server payment/world work.
 */
public final class HologramClientProjectedPlacementLifecycleRegression {

    private HologramClientProjectedPlacementLifecycleRegression() {}

    public static void run() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.completeThroughRegisteredReceipt();
            fixture.stopThroughRegisteredReceipt();
        }
    }

    private static final class Fixture implements AutoCloseable {

        final sun.misc.Unsafe unsafe;
        final Field singleton;
        final Object previousMinecraft, previousWire, previousReceiver;
        final HeadlessClient client;
        final HeadlessPlayer player;
        final NetHandlerPlayClient connection;
        final HologramLibraryClient owner = new HologramLibraryClient();
        final HologramLocalPlacement placement = HologramLocalPlacement.INSTANCE;
        final RecordingWire wire = new RecordingWire();
        final Path input;
        final List<SavedFields> restore = new ArrayList<>();
        long generation;

        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            singleton = minecraftSingleton();
            previousMinecraft = singleton.get(null);
            previousWire = field(HologramClientProjectedPlacement.class, "wire").get(null);
            previousReceiver = field(HologramProjectedLayerTransport.class, "client").get(null);
            SimpleAssert.that(field(HologramClientProjectedPlacement.class, "active").get(null) == null
                && !HologramBackgroundTasks.busy() && !placement.active(),
                "fixture never replaces an existing placement or task owner");
            restore.add(new SavedFields(HologramBackgroundTasks.class, null));
            restore.add(new SavedFields(HologramTaskOwner.class,
                field(HologramBackgroundTasks.class, "OWNER").get(null)));
            restore.add(new SavedFields(HologramOwnedPlacementProgress.class,
                field(HologramBackgroundTasks.class, "placementProgress").get(null)));
            restore.add(new SavedFields(HologramLocalPlacement.class, placement));
            client = (HeadlessClient) unsafe.allocateInstance(HeadlessClient.class);
            client.tasks = new ConcurrentLinkedQueue<>();
            player = (HeadlessPlayer) unsafe.allocateInstance(HeadlessPlayer.class);
            connection = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
            field(EntityClientPlayerMP.class, "sendQueue").set(player, connection);
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.allowEdit = true;
            ItemStack projector = new ItemStack(new ItemNekoHologramProjector());
            projector.setTagCompound(new NBTTagCompound());
            projector.getTagCompound().setString("gtitHologramToolId", new UUID(44, 55).toString());
            player.inventory.mainInventory[0] = projector;
            client.thePlayer = player;
            client.theWorld = (HeadlessWorld) unsafe.allocateInstance(HeadlessWorld.class);
            singleton.set(null, client);
            field(HologramLibraryClient.class, "libraryWorld").set(owner, client.theWorld);
            field(HologramLibraryClient.class, "libraryConnection").set(owner, connection);
            field(HologramLibraryClient.class, "libraryActor").set(owner, player.getUniqueID());
            HologramClientProjectedPlacement.install(wire);
            StringBuilder json = new StringBuilder("{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\","
                + "\"blueprint\":{\"schema\":2,\"cells\":[");
            for (int y = 0; y < 12; y++) {
                if (y != 0) json.append(',');
                json.append("[0,{\"x\":0,\"y\":").append(y).append(",\"z\":0}]");
            }
            json.append("],\"palette\":[]},\"materials\":[{\"block\":\"minecraft:stone\","
                + "\"item\":\"minecraft:stone\"}],\"nbt\":[]}");
            input = Files.createTempFile("gtmb-client-placement-lifecycle-", ".json");
            Files.write(input, json.toString().getBytes(StandardCharsets.UTF_8));
        }

        void submit() throws Exception {
            wire.sent.clear();
            generation++;
            field(HologramLibraryClient.class, "generation").setLong(owner, generation);
            var source = owner.readSavedDisplaySource("local_00000000-0000-0000-0000-000000000123",
                new HologramClientLibrary.SavedDisplayPath(input, true), false, generation, 0);
            var preparation = new HologramState.Preparation(source.snapshot, true, source.compactCells);
            while (!preparation.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
            source.state = preparation.result();
            SimpleAssert.that(source.plainPlacementEvidence() != null && source.savedMaterials != null,
                "formal saved reader establishes placement evidence and material facts on the same capture");
            var display = HologramLocalPlacement.displayForPlacement(source.state, player.dimension);
            field(HologramLocalPlacement.class, "display").set(placement, display);
            field(HologramLocalPlacement.class, "source").set(placement, source);
            field(HologramLocalPlacement.class, "world").set(placement, client.theWorld);
            field(HologramLocalPlacement.class, "connection").set(placement, connection);
            field(HologramLocalPlacement.class, "player").set(placement, player);
            field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
            field(HologramLocalPlacement.class, "anchor").set(placement, new int[] { 0, 64, 0 });
            var frozen = placement.freeze(HologramLocalPlacement.reviewOptions());
            SimpleAssert.that(frozen != null && owner.plainPlacementAvailability(frozen, client)
                == HologramLibraryClient.PlainAvailability.READY, "same production source/stock/tool predicate enables submit");
            SimpleAssert.that(!owner.prepareLocalPlacement(frozen, false) && wire.probes == generation - 1,
                "opening material review does not negotiate or send construction");
            SimpleAssert.that(!HologramLibraryMaterialReviewScreen.dispatchRefused(
                LocalAction.SUBMIT, () -> frozen, owner::prepareLocalPlacement),
                "existing material-submit dispatcher enters the production confirmed submission method");
            field(HologramLocalPlacement.class, "submitting").setBoolean(placement, true);
            SimpleAssert.that(wire.probes == generation && HologramBackgroundTasks.busy() && wire.sent.isEmpty(),
                "confirmed click creates only an owned request plus capability probe, not an eager whole-file upload");
        }

        void admit() throws Exception {
            HologramClientProjectedPlacement.negotiated(wire.request, wire.intent,
                HologramProjectedLayerTransport.VERSION - 1, 71, connection);
            HologramClientProjectedPlacement.negotiated(wire.request, wire.intent,
                HologramProjectedLayerTransport.VERSION, 71, new Object());
            HologramClientProjectedPlacement.tick(client);
            client.drain();
            SimpleAssert.eq(0, wire.sent.size(), "old protocol or foreign handler never authorizes a BEGIN");
            HologramClientProjectedPlacement.negotiated(wire.request, wire.intent,
                HologramProjectedLayerTransport.VERSION, 71, connection);
            await(() -> wire.count(0) == 1);
            SimpleAssert.eq(0, wire.count(1), "manifest admission alone has sent no placement slices");
            receive(reply(1, Phase.AWAITING_SLICE, Reason.NONE, 0, 0, 0, true), connection);
            await(() -> wire.count(1) == HologramProjectedLayerTransport.FRAME_WINDOW);
            SimpleAssert.eq(8, wire.count(1), "actual single worker sends one bounded eight-frame window");
            SimpleAssert.that(HologramBackgroundTasks.busy() && !HologramBackgroundTasks.succeeded()
                && placement.active(), "sending or accepting geometry is not a completed construction");
        }

        void completeThroughRegisteredReceipt() throws Exception {
            submit();
            admit();
            receive(reply(2, Phase.PLACING, Reason.NONE, 8, 6, 6, true), connection);
            await(() -> wire.count(1) == 12);
            SimpleAssert.eq(12, wire.count(1), "cumulative settled credit refills only the four remaining slices");
            Reply complete = reply(3, Phase.COMPLETE, Reason.NONE, 12, 12, 12, false);
            receive(complete, (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class));
            HologramClientProjectedPlacement.tick(client);
            SimpleAssert.that(placement.active() && HologramBackgroundTasks.busy(),
                "an otherwise matching COMPLETE from a foreign play handler cannot clear the current projection");
            receive(complete, connection);
            HologramClientProjectedPlacement.tick(client);
            SimpleAssert.that(!placement.active() && !placement.submitting()
                && !HologramBackgroundTasks.busy() && HologramBackgroundTasks.succeeded()
                && field(HologramClientProjectedPlacement.class, "active").get(null) == null
                && field(HologramLibraryClient.class, "placementRequest").get(owner) == null,
                "only authenticated terminal receipt clears the actual ghost, request, task and busy state");
            SimpleAssert.eq(1, player.sounds, "one genuine completion notification, not a transfer acknowledgement");
            receive(complete, connection);
            HologramClientProjectedPlacement.tick(client);
            SimpleAssert.eq(1, player.sounds, "replayed terminal does not repeat success or revive rendering");
        }

        void stopThroughRegisteredReceipt() throws Exception {
            submit();
            admit();
            SimpleAssert.that(HologramClientProjectedPlacement.stopCurrent(), "actual owning task accepts cancel");
            HologramClientProjectedPlacement.tick(client);
            SimpleAssert.eq(1, wire.count(5), "actual client tick emits STOP through registered construction transport");
            SimpleAssert.that(HologramBackgroundTasks.busy() && placement.active(),
                "sending STOP cannot pretend the server has stopped");
            Reply stopped = reply(4, Phase.STOPPED, Reason.CANCELLED, 8, 1, 1, false);
            stopped.controlSequence = 1;
            receive(stopped, connection);
            HologramClientProjectedPlacement.tick(client);
            SimpleAssert.that(placement.active() && placement.locked() && !placement.submitting()
                && !HologramBackgroundTasks.busy() && !HologramBackgroundTasks.succeeded()
                && field(HologramClientProjectedPlacement.class, "active").get(null) == null,
                "server STOPPED ends spinner and sender but preserves the locked source for a deliberate retry");
            receive(reply(5, Phase.COMPLETE, Reason.NONE, 12, 12, 12, false), connection);
            HologramClientProjectedPlacement.tick(client);
            SimpleAssert.that(placement.active() && !HologramBackgroundTasks.succeeded() && wire.count(1) == 8,
                "late COMPLETE cannot erase a stopped projection, send more slices or convert cancellation to success");
        }

        Reply reply(long serial, Phase phase, Reason reason, int accepted, int settled, int placed, boolean credit)
            throws Exception {
            Reply reply = new Reply();
            reply.request = wire.request;
            reply.intentSequence = wire.intent;
            reply.scopeGeneration = 71;
            reply.sequence = serial;
            reply.phase = phase;
            reply.reason = reason;
            reply.acceptedSlices = accepted;
            reply.settledSlices = settled;
            reply.totalSlices = reply.totalCells = 12;
            reply.placed = placed;
            Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramProjectedLayerWire$Ack");
            Constructor<?> make = type.getDeclaredConstructor(UUID.class, int.class, int.class, int.class,
                boolean.class, boolean.class);
            make.setAccessible(true);
            Object ack = make.newInstance(wire.request, settled, placed, credit ? 1 : 0,
                phase == Phase.COMPLETE, phase == Phase.STOPPED);
            Class<?> codec = Class.forName("com.miaokatze.gtmb.hologram.HologramProjectedLayerWire");
            Method encode = codec.getDeclaredMethod("encodeAck", type);
            encode.setAccessible(true);
            field(Reply.class, "ack").set(reply, encode.invoke(null, ack));
            return reply;
        }

        void receive(Reply value, NetHandlerPlayClient from) throws Exception {
            ByteBuf encoded = Unpooled.buffer();
            Reply decoded = new Reply();
            try {
                value.toBytes(encoded);
                decoded.fromBytes(encoded);
            } finally { encoded.release(); }
            SimpleAssert.that(decoded.request != null, "controlled fixture reply passed the real production decoder");
            Constructor<MessageContext> make = MessageContext.class.getDeclaredConstructor(
                net.minecraft.network.INetHandler.class, Side.class);
            make.setAccessible(true);
            new HologramProjectedLayerTransport.ClientHandler().onMessage(decoded, make.newInstance(from, Side.CLIENT));
        }

        void await(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                client.drain();
                HologramClientProjectedPlacement.tick(client);
                if (done.getAsBoolean()) return;
                Thread.sleep(1);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("actual owned sender failed to reach expected state: " + HologramBackgroundTasks.status());
        }

        @Override
        public void close() throws Exception {
            ThreadPoolExecutor worker = (ThreadPoolExecutor) field(HologramLibraryClient.class, "worker").get(owner);
            field(HologramClientProjectedPlacement.class, "active").set(null, null);
            field(HologramLibraryClient.class, "generation").setLong(owner, generation + 1);
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
            client.drain();
            placement.clear();
            field(HologramClientProjectedPlacement.class, "wire").set(null, previousWire);
            field(HologramProjectedLayerTransport.class, "client").set(null, previousReceiver);
            for (int i = restore.size() - 1; i >= 0; i--) restore.get(i).restore();
            singleton.set(null, previousMinecraft);
            Files.deleteIfExists(input);
        }
    }

    private static final class RecordingWire implements HologramClientProjectedPlacement.Wire {
        final List<byte[]> sent = new ArrayList<>();
        UUID request;
        long intent;
        int probes;
        @Override public long nextIntentSequence() { return ++intent; }
        @Override public boolean probe(UUID request, long sequence) {
            this.request = request;
            SimpleAssert.eq(intent, sequence, "current request owns the monotonically issued intent");
            probes++;
            return true;
        }
        @Override public boolean send(Request request) {
            ByteBuf encoded = Unpooled.buffer();
            try {
                request.toBytes(encoded);
                byte[] bytes = new byte[encoded.readableBytes()];
                encoded.readBytes(bytes);
                SimpleAssert.eq(HologramProjectedLayerTransport.VERSION, bytes[0] & 255,
                    "all actual emitted messages use the negotiated protocol");
                sent.add(bytes);
                return true;
            } finally { encoded.release(); }
        }
        int count(int operation) {
            int count = 0;
            for (byte[] bytes : sent) if ((bytes[1] & 255) == operation) count++;
            return count;
        }
    }

    private static final class HeadlessClient extends Minecraft {
        Queue<ListenableFutureTask<?>> tasks;
        private HeadlessClient() {
            super(null, 1, 1, false, false, null, null, null, null, "fixture", null, "fixture");
            throw new AssertionError("Unsafe-only fixture; no client is launched");
        }
        @Override public ListenableFuture func_152344_a(Runnable action) {
            ListenableFutureTask<?> task = ListenableFutureTask.create(action, null);
            tasks.add(task);
            return task;
        }
        @Override public void displayGuiScreen(GuiScreen screen) { currentScreen = screen; }
        void drain() throws Exception {
            ListenableFutureTask<?> task;
            while ((task = tasks.poll()) != null) { task.run(); task.get(); }
        }
    }

    private static final class HeadlessPlayer extends EntityClientPlayerMP {
        int sounds;
        private HeadlessPlayer() {
            super(null, null, null, null, null);
            throw new AssertionError("Unsafe-only fixture");
        }
        @Override public UUID getUniqueID() { return new UUID(123, 456); }
        @Override public void addChatMessage(IChatComponent component) {}
        @Override public void playSound(String name, float volume, float pitch) { sounds++; }
    }

    private static final class HeadlessWorld extends WorldClient {
        private HeadlessWorld() { super(null, null, 0, null, null); throw new AssertionError("Unsafe-only fixture"); }
        @Override public int getHeight() { return 256; }
    }

    /** Restore existing global owner state after an offline fixture, including counters and references. */
    private static final class SavedFields {
        final Object target;
        final List<Field> fields = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        SavedFields(Class<?> type, Object target) throws Exception {
            this.target = target;
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isFinal(field.getModifiers()) || Modifier.isStatic(field.getModifiers()) != (target == null)) continue;
                field.setAccessible(true);
                fields.add(field);
                values.add(field.get(target));
            }
        }
        void restore() throws Exception {
            for (int i = 0; i < fields.size(); i++) fields.get(i).set(target, values.get(i));
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field minecraftSingleton() throws NoSuchFieldException {
        for (Field field : Minecraft.class.getDeclaredFields())
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Minecraft.class) {
                field.setAccessible(true);
                return field;
            }
        throw new NoSuchFieldException("Minecraft singleton");
    }
}
