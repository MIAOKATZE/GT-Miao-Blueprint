package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramCutContextRegression {

    private HologramCutContextRegression() {}

    private static void ingressOwnership() {
        HologramCutService.Ingress ingress = new HologramCutService.Ingress(2, 3);
        Object owner = new Object(), connection = new Object(), otherOwner = new Object(), reconnect = new Object();
        String id = UUID.randomUUID()
            .toString();
        HologramCutService.Ingress.Lease first = ingress.acquire(owner, connection, id, true);
        HologramCutService.Ingress.Lease duplicate = ingress.acquire(owner, connection, id, true);
        try {
            SimpleAssert
                .that(ingress.pending(owner, connection, id), "accepted queued writes are pending before preparation");
            SimpleAssert
                .that(!ingress.pending(otherOwner, connection, id), "another owner cannot inherit a live write");
            SimpleAssert
                .that(!ingress.pending(owner, reconnect, id), "a new connection cannot inherit the old execution");
            SimpleAssert.that(
                !ingress.pending(
                    owner,
                    connection,
                    UUID.randomUUID()
                        .toString()),
                "correlation uses exact request id");
            SimpleAssert.that(
                ingress.acquire(owner, connection, "rejected", true) == null,
                "per-owner admission remains bounded");
            SimpleAssert
                .that(!ingress.pending(owner, connection, "rejected"), "rejected ingress never appears executable");
            HologramCutService.Ingress.Lease read = ingress.acquire(otherOwner, connection, "directory", false);
            try {
                SimpleAssert.that(
                    !ingress.pending(otherOwner, connection, "directory"),
                    "directory reads do not imply a pending world write");
                SimpleAssert.that(
                    ingress.acquire(new Object(), connection, "global-rejected", true) == null,
                    "global admission remains bounded");
            } finally {
                read.close();
            }
            first.close();
            first.close();
            SimpleAssert.that(
                ingress.pending(owner, connection, id),
                "one duplicate retirement cannot retire another live request");
            duplicate.close();
            SimpleAssert
                .that(!ingress.pending(owner, connection, id), "terminal or disconnected work retires exact ownership");
            HologramCutService.Ingress.Lease obsolete = ingress.acquire(owner, connection, id, true);
            ingress.clear();
            SimpleAssert.that(
                !obsolete.active() && !ingress.pending(owner, connection, id),
                "runtime reset cancels queued and staged ownership");
            HologramCutService.Ingress.Lease current = ingress.acquire(owner, connection, id, true);
            try {
                obsolete.close();
                SimpleAssert.that(
                    current.active() && ingress.pending(owner, connection, id),
                    "old generation retirement cannot erase new same-id work");
            } finally {
                current.close();
            }
            for (String action : new String[] { "cut", "paste", "undo" })
                SimpleAssert.that(HologramCutService.writesWorld(action), "track every accepted world-write kind");
            for (String action : new String[] { "list", "preview", "openWorkspace", "unknown" }) SimpleAssert.that(
                !HologramCutService.writesWorld(action),
                "read and invalid kinds cannot create write uncertainty");
        } finally {
            first.close();
            duplicate.close();
            ingress.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static void playerReplacementAndEnqueueFailures() {
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            java.lang.reflect.Field ingressField = HologramCutService.class.getDeclaredField("queued");
            ingressField.setAccessible(true);
            HologramCutService.Ingress ingress = (HologramCutService.Ingress) ingressField.get(null);
            SimpleAssert.that(
                HologramCutService.currentPlayer(fixture.oldPlayer, fixture.connection),
                "same live actor/handler/connection is an executable owner");
            SimpleAssert.that(
                !HologramCutService.currentPlayer(fixture.oldPlayer, new Object()),
                "a replacement connection cannot execute admitted work");
            fixture.oldPlayer.isDead = true;
            SimpleAssert.that(
                !HologramCutService.currentPlayer(fixture.oldPlayer, fixture.connection),
                "death alone retires actor eligibility before respawn");
            fixture.oldPlayer.isDead = false;
            for (int destination : new int[] { 0, 7 }) {
                String id = UUID.randomUUID()
                    .toString();
                fixture.handler.playerEntity = fixture.oldPlayer;
                fixture.oldPlayer.dimension = 0;
                NBTTagCompound request = request(id);
                java.util.List<Runnable> scheduled = new java.util.ArrayList<>();
                HologramCutService.receive(fixture.oldPlayer, request, scheduled::add);
                SimpleAssert.eq(1, scheduled.size(), "successful enqueue transfers exact ingress ownership");
                SimpleAssert.that(
                    ingress.pending(fixture.oldPlayer, fixture.connection, id),
                    "queued original write remains live until owner retirement");
                fixture.handler.playerEntity = fixture.newPlayer;
                fixture.newPlayer.dimension = destination;
                fixture.newPlayer.worldObj = destination == 0 ? fixture.originalWorld : fixture.otherWorld;
                fixture.oldPlayer.dimension = destination; // Vanilla may update this without replacing old worldObj.
                SimpleAssert.that(
                    !HologramCutService.currentPlayer(fixture.oldPlayer, fixture.connection),
                    "handler ownership, not just open connection/world/tool, rejects replaced actor");
                SimpleAssert.that(
                    HologramCutService.currentPlayer(fixture.newPlayer, fixture.connection),
                    "respawn actor owns the same still-open connection");
                NBTTagCompound state = new NBTTagCompound(), response = new NBTTagCompound();
                SimpleAssert.that(
                    HologramCutService.reportRequestResult(
                        response,
                        state,
                        id,
                        ingress.pending(fixture.newPlayer, fixture.connection, id))
                        && !response.getBoolean("requestCompleted"),
                    "new-player absence is safe because the old queued actor cannot execute");
                HologramCutService.Ingress.Lease fresh = ingress
                    .acquire(fixture.newPlayer, fixture.connection, id, true);
                try {
                    fixture.oldPlayer.heldReads = 0;
                    scheduled.remove(0)
                        .run(); // Must stop before any tool/native access on the detached actor.
                    SimpleAssert
                        .eq(0, fixture.oldPlayer.heldReads, "obsolete queued actor never reaches tool/world dispatch");
                    SimpleAssert.that(
                        !ingress.pending(fixture.oldPlayer, fixture.connection, id),
                        "obsolete queued actor retires its original request");
                    SimpleAssert.that(
                        fresh.active() && ingress.pending(fixture.newPlayer, fixture.connection, id),
                        "old actor retirement cannot erase new-player same-ID ownership");
                } finally {
                    fresh.close();
                }
                SimpleAssert.that(
                    HologramCutService.reportRequestResult(
                        new NBTTagCompound(),
                        state,
                        id,
                        ingress.pending(fixture.newPlayer, fixture.connection, id)),
                    "retired new owner has a terminal absence result");

                // Put a private prepared entry at the real continuation boundary; it must retire before step/native
                // work.
                fixture.handler.playerEntity = fixture.oldPlayer;
                HologramCutService.Ingress.Lease staged = ingress
                    .acquire(fixture.oldPlayer, fixture.connection, id, true);
                java.lang.reflect.Field preparationsField = HologramCutService.class.getDeclaredField("preparing");
                preparationsField.setAccessible(true);
                java.util.ArrayDeque<Object> preparations = (java.util.ArrayDeque<Object>) preparationsField.get(null);
                SimpleAssert.that(preparations.isEmpty(), "isolated Cut continuation fixture owns an empty queue");
                NBTTagCompound entry = new NBTTagCompound(), cell = new NBTTagCompound();
                entry.setInteger("sizeX", 1);
                entry.setInteger("sizeY", 1);
                entry.setInteger("sizeZ", 1);
                cell.setInteger("x", 0);
                cell.setInteger("y", 0);
                cell.setInteger("z", 0);
                NBTTagList cells = new NBTTagList();
                cells.appendTag(cell);
                entry.setTag("cells", cells);
                NBTTagCompound original = (NBTTagCompound) entry.copy();
                Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramCutService$Preparation");
                java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(
                    net.minecraft.entity.player.EntityPlayerMP.class,
                    NBTTagCompound.class,
                    net.minecraft.world.World.class,
                    HologramCutService.Ingress.Lease.class,
                    NBTTagCompound.class,
                    com.miaokatze.gtmb.api.CutTransforms.Transform.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class);
                constructor.setAccessible(true);
                Object work = constructor.newInstance(
                    fixture.oldPlayer,
                    request,
                    fixture.originalWorld,
                    staged,
                    entry,
                    new com.miaokatze.gtmb.api.CutTransforms.Transform(0, 0, 0, 0),
                    0,
                    0,
                    0,
                    0);
                try {
                    preparations.addLast(work);
                    fixture.handler.playerEntity = fixture.newPlayer;
                    // Check identity alone as well as the real dead-old-player case; neither may advance.
                    fixture.oldPlayer.isDead = destination != 0;
                    fixture.oldPlayer.heldReads = 0;
                    HologramCutService.tick();
                    SimpleAssert
                        .eq(0, fixture.oldPlayer.heldReads, "obsolete preparation never reaches tool/world dispatch");
                    java.lang.reflect.Field transform = type.getDeclaredField("task");
                    transform.setAccessible(true);
                    SimpleAssert.that(
                        !staged.active() && !preparations.contains(work) && transform.get(work) == null,
                        "respawned preparation retires without transforming or entering native execution");
                    SimpleAssert
                        .that(entry.equals(original), "retirement does not consume or modify original raw payload");
                } finally {
                    preparations.remove(work);
                    staged.close();
                    fixture.oldPlayer.isDead = false;
                }
            }
            fixture.handler.playerEntity = fixture.oldPlayer;
            String id = UUID.randomUUID()
                .toString();
            NBTTagCompound throwing = new NBTTagCompound() {

                @Override
                public net.minecraft.nbt.NBTBase copy() {
                    throw new IllegalStateException("copy-injected");
                }
            };
            throwing.setString("action", "paste");
            throwing.setString("requestId", id);
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    HologramCutService.receive(
                        fixture.oldPlayer,
                        throwing,
                        ignored -> { throw new AssertionError("throwing copy must not reach enqueue"); });
                    throw new AssertionError("copy fault must propagate");
                } catch (IllegalStateException expected) {
                    SimpleAssert.eq("copy-injected", expected.getMessage(), "observe only the injected copy failure");
                }
                SimpleAssert.that(
                    !ingress.pending(fixture.oldPlayer, fixture.connection, id),
                    "copy failure retires write marker and does not exhaust per-owner admission");
            }
            java.util.List<Runnable> retained = new java.util.ArrayList<>();
            try {
                HologramCutService.receive(fixture.oldPlayer, request(id), runnable -> {
                    retained.add(runnable);
                    throw new IllegalStateException("enqueue-injected");
                });
                throw new AssertionError("enqueue fault must propagate");
            } catch (IllegalStateException expected) {
                SimpleAssert.eq("enqueue-injected", expected.getMessage(), "observe only injected enqueue failure");
            }
            SimpleAssert.that(
                !ingress.pending(fixture.oldPlayer, fixture.connection, id),
                "enqueue failure retires accepted ownership");
            retained.remove(0)
                .run(); // Even an enqueue implementation that retains then throws cannot execute it.
            HologramCutService.receive(fixture.oldPlayer, request(id), retained::add);
            SimpleAssert.eq(1, retained.size(), "same original ID can be admitted after pre-enqueue failure");
            SimpleAssert.that(
                ingress.pending(fixture.oldPlayer, fixture.connection, id),
                "successful enqueue retains ownership until its runnable retires");
            fixture.channel.close();
            SimpleAssert.that(
                !HologramCutService.currentPlayer(fixture.oldPlayer, fixture.connection),
                "closed connection fails the common executable-owner fence");
            retained.remove(0)
                .run();
            SimpleAssert.that(
                !ingress.pending(fixture.oldPlayer, fixture.connection, id),
                "disconnected queued work retires without an outcome or world access");
        } catch (Exception failure) {
            throw new AssertionError("Cut actor and enqueue ownership", failure);
        }
    }

    private static NBTTagCompound request(String id) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "paste");
        request.setString("requestId", id);
        return request;
    }

    private static final class ConnectionFixture implements AutoCloseable {

        final CutActor oldPlayer, newPlayer;
        final net.minecraft.world.WorldServer originalWorld, otherWorld;
        final net.minecraft.network.NetworkManager connection = new net.minecraft.network.NetworkManager(false);
        final io.netty.channel.embedded.EmbeddedChannel channel = new io.netty.channel.embedded.EmbeddedChannel(
            new io.netty.channel.ChannelInboundHandlerAdapter());
        final net.minecraft.network.NetHandlerPlayServer handler;

        ConnectionFixture() throws Exception {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            oldPlayer = (CutActor) unsafe.allocateInstance(CutActor.class);
            newPlayer = (CutActor) unsafe.allocateInstance(CutActor.class);
            originalWorld = (net.minecraft.world.WorldServer) unsafe
                .allocateInstance(net.minecraft.world.WorldServer.class);
            otherWorld = (net.minecraft.world.WorldServer) unsafe
                .allocateInstance(net.minecraft.world.WorldServer.class);
            oldPlayer.worldObj = newPlayer.worldObj = originalWorld;
            oldPlayer.inventory = new net.minecraft.entity.player.InventoryPlayer(oldPlayer);
            newPlayer.inventory = new net.minecraft.entity.player.InventoryPlayer(newPlayer);
            net.minecraft.item.Item projector = new com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector();
            oldPlayer.inventory.mainInventory[0] = new net.minecraft.item.ItemStack(projector);
            newPlayer.inventory.mainInventory[0] = oldPlayer.inventory.mainInventory[0].copy();
            for (java.lang.reflect.Field field : net.minecraft.network.NetworkManager.class.getDeclaredFields())
                if (field.getType() == io.netty.channel.Channel.class) {
                    field.setAccessible(true);
                    field.set(connection, channel);
                }
            handler = new net.minecraft.network.NetHandlerPlayServer(null, connection, oldPlayer);
            newPlayer.playerNetServerHandler = handler;
        }

        @SuppressWarnings("unchecked")
        public void close() throws Exception {
            try {
                java.lang.reflect.Field ingressField = HologramCutService.class.getDeclaredField("queued");
                ingressField.setAccessible(true);
                HologramCutService.Ingress ingress = (HologramCutService.Ingress) ingressField.get(null);
                java.lang.reflect.Field writesField = HologramCutService.Ingress.class.getDeclaredField("writes");
                writesField.setAccessible(true);
                java.lang.reflect.Field ownerField = HologramCutService.Ingress.Lease.class.getDeclaredField("owner");
                ownerField.setAccessible(true);
                synchronized (ingress) {
                    for (HologramCutService.Ingress.Lease lease : new java.util.ArrayList<>(
                        (java.util.Set<HologramCutService.Ingress.Lease>) writesField.get(ingress))) {
                        Object owner = ownerField.get(lease);
                        if (owner == oldPlayer || owner == newPlayer) lease.close();
                    }
                }
            } finally {
                channel.close();
            }
        }
    }

    private static final class CutActor extends net.minecraft.entity.player.EntityPlayerMP {

        int heldReads;

        private CutActor() {
            super(null, null, null, null);
        }

        @Override
        public net.minecraft.item.ItemStack getHeldItem() {
            heldReads++;
            return super.getHeldItem();
        }
    }

    public static void run() {
        ingressOwnership();
        playerReplacementAndEnqueueFailures();
        for (boolean sameWorld : new boolean[] { false, true }) for (boolean held : new boolean[] { false, true }) {
            SimpleAssert.that(
                HologramCutService.acceptsContext("list", sameWorld, held),
                "owner directory read survives inventory/world sync races");
            for (String operation : new String[] { "cut", "paste", "undo", "preview" }) SimpleAssert.eq(
                sameWorld && held,
                HologramCutService.acceptsContext(operation, sameWorld, held),
                "write/raw-preview permissions remain bound to world and held projector: " + operation);
        }
        String completed = UUID.randomUUID()
            .toString(),
            absent = UUID.randomUUID()
                .toString();
        NBTTagCompound durable = new NBTTagCompound(), record = new NBTTagCompound();
        record.setString("id", completed);
        NBTTagList records = new NBTTagList();
        records.appendTag(record);
        durable.setTag("requests", records);
        NBTTagCompound before = (NBTTagCompound) durable.copy();
        try {
            NBTTagCompound response = new NBTTagCompound();
            HologramCutService.reportRequestResult(response, durable, completed);
            SimpleAssert.eq(completed, response.getString("reconciledRequestId"), "report the exact original request");
            SimpleAssert.that(response.getBoolean("requestCompleted"), "durable completion resolves a lost write ACK");
            HologramCutService.reportRequestResult(response, durable, absent);
            SimpleAssert.that(!response.getBoolean("requestCompleted"), "a dropped request is not reported complete");
            NBTTagCompound pending = new NBTTagCompound();
            pending.setBoolean("success", true);
            pending.setBoolean("requestCompleted", false);
            SimpleAssert.that(
                !HologramCutService.reportRequestResult(pending, durable, absent, true),
                "a live queued or staged paste has no terminal absence proof");
            SimpleAssert
                .eq(absent, pending.getString("reconciledRequestId"), "nonterminal answer retains exact correlation");
            SimpleAssert.that(
                !pending.getBoolean("success") && !pending.hasKey("requestCompleted"),
                "old clients cannot accept pending as a successful negative directory reconciliation");
            SimpleAssert.eq("request_pending", pending.getString("code"), "pending has a structured nonterminal code");
            NBTTagCompound terminal = new NBTTagCompound();
            SimpleAssert.that(
                HologramCutService.reportRequestResult(terminal, durable, completed, true)
                    && terminal.getBoolean("requestCompleted"),
                "durable completion wins over retiring live ownership");
            SimpleAssert.that(
                HologramCutService.reportRequestResult(terminal, durable, absent, false)
                    && !terminal.getBoolean("requestCompleted"),
                "retired absent write can finally resolve as not executed");
            SimpleAssert.that(before.equals(durable), "checking outcomes never changes durable requests or entries");
        } catch (IOException failure) {
            throw new AssertionError("canonical request result lookup", failure);
        }
    }
}
