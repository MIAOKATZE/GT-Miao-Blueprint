// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.client.hologram.HologramClient;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;

/** Real bounded queue/codec and client consume path; no rendered UI, game instance or second transport. */
public final class HologramNativeSiteSnapshotRegression {

    private static final List<IMessage> SENT = new ArrayList<>();

    private HologramNativeSiteSnapshotRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramNativeSiteSnapshotRegression.class, args)) return;
        Thread.currentThread()
            .setName("Server thread");
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        Field channel = field(HologramNetwork.class, "channel");
        Object previousChannel = channel.get(null);
        var previousReceiver = (HologramNetwork.StockNativeReplies) field(HologramNetwork.class, "stockNativeReplies")
            .get(null);
        try {
            HologramNetwork.clearServerQueues();
            channel.set(null, unsafe.allocateInstance(RecordingWire.class));
            NBTTagCompound owned = serverQueue(unsafe);
            NBTTagCompound decoded = HologramSnapshotCodec.decode(HologramSnapshotCodec.compressPacked(owned));
            SimpleAssert.that(
                decoded != owned && decoded.getTagList("cells", 10)
                    .tagCount() == 128,
                "existing snapshot codec reconstructs the complete standard cell root");
            clientConsume(unsafe, decoded);
            System.out
                .println("NATIVE_SITE_SNAPSHOT_PASS cells=128 sharedQueue=true borrowedDecodedRoot=true exactAck=true");
        } finally {
            HologramNetwork.clearServerQueues();
            HologramNetwork.setStockNativeReplies(previousReceiver);
            channel.set(null, previousChannel);
            SENT.clear();
        }
    }

    private static NBTTagCompound serverQueue(sun.misc.Unsafe unsafe) throws Exception {
        var world = HologramPlacementFixtureWorld.create(unsafe);
        var player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "SiteSnapshotTransport");
        HologramWorkspace.publishActionEndpoint(player, false);
        var endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
        SimpleAssert.that(endpoint != null, "fixture owns the actual current server endpoint");
        var advertisement = new NBTTagCompound();
        advertisement.setInteger("clientSnapshotFlow", 1);
        HologramNetwork.snapshotFlowAction(player, advertisement);
        var key = new Key(UUID.randomUUID(), 1);
        var observation = new ServerObservation(236, 0, 64, 0, Stage.FORMED, 7, "");
        var root = new NBTTagCompound();
        // Native receipts deliberately do not become editor/workspace snapshots.
        root.setString("generation", "not-an-editor-generation");
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 128; i++) {
            var cell = new NBTTagCompound();
            cell.setInteger("dx", i);
            cell.setString("id", "minecraft:stone");
            cell.setString("wantId", "minecraft:stone");
            cells.appendTag(cell);
        }
        root.setTag("cells", cells);
        var send = HologramNetwork.class
            .getDeclaredMethod("sendStockNativeReply", HologramWorkspace.ActionEndpoint.class, Reply.class);
        send.setAccessible(true);
        try {
            int smallBefore = SENT.size();
            send.invoke(null, endpoint, new Reply(key, Phase.CAPABLE, 0, "", observation, root));
            SimpleAssert.eq(smallBefore, SENT.size(), "a full site reply never enters the small State packet codec");
            SimpleAssert.that(
                root.getTagList("cells", 10) == cells && root.getString("session")
                    .equals(
                        key.request()
                            .toString()),
                "server transfer retains its owned cell list and uses the existing request identity");
            Map<?, ?> outgoing = (Map<?, ?>) field(HologramNetwork.class, "outgoing").get(null);
            Object box = outgoing.get(player.getUniqueID());
            var queue = (java.util.Deque<?>) field(box.getClass(), "snapshots").get(box);
            Object queued = queue.peekFirst();
            SimpleAssert.that(
                field(queued.getClass(), "owned").get(queued) == root,
                "the existing bounded outbox takes ownership of the original full DTO");
            var valid = (BooleanSupplier) field(queued.getClass(), "valid").get(queued);
            SimpleAssert.that(valid.getAsBoolean(), "encoder validity follows the current server endpoint");
            send.invoke(null, endpoint, new Reply(key, Phase.ACTIVE, 2, "working"));
            SimpleAssert.that(
                SENT.size() == smallBefore + 1 && SENT.get(SENT.size() - 1) instanceof HologramNetwork.State,
                "ordinary small native receipts retain their current State path");
            HologramWorkspace.revokeActionEndpoint(player);
            SimpleAssert.that(!valid.getAsBoolean(), "endpoint revocation invalidates pending site encoding");
            return root;
        } finally {
            HologramWorkspace.revokeActionEndpoint(player);
            HologramNetwork.clearServerQueues();
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static void clientConsume(sun.misc.Unsafe unsafe, NBTTagCompound root) throws Exception {
        var client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        var player = (EntityClientPlayerMP) unsafe.allocateInstance(EntityClientPlayerMP.class);
        var connection = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
        field(EntityClientPlayerMP.class, "sendQueue").set(player, connection);
        client.thePlayer = player;
        Field instance = field(Minecraft.class, "theMinecraft");
        Object previousClient = instance.get(null);
        Map<Field, Object> saved = new LinkedHashMap<>();
        for (String name : new String[] { "assemblyConnection", "snapshotFlowGeneration", "snapshotFlowReceiving",
            "snapshotFlowConsumed", "snapshotFlowLegacy", "snapshotFlowSentAt", "snapshotFlowSuccess" }) {
            Field changed = field(HologramClient.class, name);
            saved.put(changed, changed.get(null));
        }
        var received = new AtomicReference<Reply>();
        var from = new AtomicReference<Object>();
        HologramNetwork.setStockNativeReplies((reply, inbound) -> {
            received.set(reply);
            from.set(inbound);
        });
        instance.set(null, client);
        try {
            field(HologramClient.class, "assemblyConnection").set(null, connection);
            field(HologramClient.class, "snapshotFlowGeneration").setLong(null, 19);
            field(HologramClient.class, "snapshotFlowLegacy").setBoolean(null, false);
            consume(root, 77);
            SimpleAssert.that(
                received.get() != null && received.get()
                    .siteSnapshot() == root
                    && received.get()
                        .siteSnapshot()
                        .getTagList("cells", 10) == root.getTagList("cells", 10)
                    && from.get() == connection,
                "typed native delivery borrows the decoded root and cells without a second full copy");
            SimpleAssert.eq(
                77L,
                field(HologramClient.class, "snapshotFlowConsumed").getLong(null),
                "typed delivery is acknowledged before the workspace identity filter can discard it");
            SimpleAssert.that(
                field(HologramClient.class, "snapshotFlowSuccess").getBoolean(null),
                "successful native delivery consumes one exact existing flow credit");
            received.set(null);
            field(HologramClient.class, "assemblyConnection").set(null, new Object());
            consume(root, 78);
            SimpleAssert.that(
                received.get() == null && field(HologramClient.class, "snapshotFlowConsumed").getLong(null) == 78,
                "stale connection is not delivered but its decoded transport is retired");
            field(HologramClient.class, "assemblyConnection").set(null, connection);
            String session = root.getString("session");
            root.setString(
                "session",
                UUID.randomUUID()
                    .toString());
            SimpleAssert.that(
                !HologramNetwork.dispatchStockNativeSiteSnapshot(root, connection),
                "snapshot request identity must match its small native receipt");
            root.setString("session", session);
            HologramNetwork
                .setStockNativeReplies((reply, inbound) -> { throw new IllegalStateException("observer failed"); });
            try {
                consume(root, 79);
                throw new AssertionError("observer exception must remain visible");
            } catch (InvocationTargetException expected) {
                SimpleAssert.that(
                    expected.getCause() instanceof IllegalStateException
                        && field(HologramClient.class, "snapshotFlowConsumed").getLong(null) == 79,
                    "consumer failure still releases the existing transport credit in finally");
            }
        } finally {
            for (var entry : saved.entrySet()) entry.getKey()
                .set(null, entry.getValue());
            instance.set(null, previousClient);
        }
    }

    private static void consume(NBTTagCompound root, long sequence) throws Exception {
        field(HologramClient.class, "snapshotFlowReceiving").setLong(null, sequence);
        field(HologramClient.class, "snapshotFlowConsumed").setLong(null, -1);
        var result = Class.forName("com.miaokatze.gtmb.client.hologram.HologramSnapshotInbox$Result");
        var constructor = result.getDeclaredConstructor(Object.class, Throwable.class, long.class);
        constructor.setAccessible(true);
        var install = HologramClient.class.getDeclaredMethod("installDecoded", result);
        install.setAccessible(true);
        install.invoke(null, constructor.newInstance(root, null, sequence));
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static final class RecordingWire extends SimpleNetworkWrapper {

        private RecordingWire() {
            super("unused_site_snapshot_fixture");
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP player) {
            SENT.add(message);
        }

        @Override
        public void sendToServer(IMessage message) {
            SENT.add(message);
        }
    }
}
