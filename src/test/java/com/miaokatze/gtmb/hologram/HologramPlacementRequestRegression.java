// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Real rejection/negotiation and mutual-gate regression. No retired encoder, planner or worker is retained. */
final class HologramPlacementRequestRegression {

    private HologramPlacementRequestRegression() {}

    static void run() {
        NBTTagCompound oldRequest = new NBTTagCompound();
        oldRequest.setString("action", "projectedPlacement");
        oldRequest.setString(
            "placementRequestId",
            UUID.randomUUID()
                .toString());
        oldRequest.setInteger("placementWireVersion", 4);
        oldRequest.setIntArray("placementOperations", new int[] { 0, 64, 0, 0 });
        try {
            retiredProductionIngress(oldRequest);
            for (String retired : new String[] { "projectedPlacement", "checkPlacementMaterials" }) {
                NBTTagCompound action = new NBTTagCompound();
                action.setString("action", retired);
                try {
                    HologramNetwork.sendAction(action);
                    throw new AssertionError("obsolete action accepted by production sender");
                } catch (IllegalArgumentException expected) {}
            }
        } catch (IOException failure) {
            throw new AssertionError("retired placement ingress", failure);
        }
    }

    /** Exercise the real ingress handlers, not a test-only dispatch or the old offline materializer. */
    private static void retiredProductionIngress(NBTTagCompound original) throws IOException {
        Field channel = null;
        Object before = null;
        EntityPlayerMP player = null;
        try {
            Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) access.get(null);
            Class<HologramServiceEditRegression.ReadOnlyWorld> type = HologramServiceEditRegression.ReadOnlyWorld.class;
            var world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe.allocateInstance(type);
            Method factory = HologramLibraryServiceRegression.class
                .getDeclaredMethod("player", sun.misc.Unsafe.class, type, UUID.class, String.class);
            factory.setAccessible(true);
            player = (EntityPlayerMP) factory.invoke(null, unsafe, world, UUID.randomUUID(), "RetiredPlacement");
            var contextFactory = MessageContext.class.getDeclaredConstructor(INetHandler.class, Side.class);
            contextFactory.setAccessible(true);
            MessageContext context = contextFactory.newInstance(player.playerNetServerHandler, Side.SERVER);
            channel = HologramNetwork.class.getDeclaredField("channel");
            channel.setAccessible(true);
            before = channel.get(null);
            RetiredReceiptChannel captured = new RetiredReceiptChannel();
            channel.set(null, captured);
            long reads = worldReads(world);
            UUID actionId = UUID.randomUUID();
            var whole = (NBTTagCompound) original.copy();
            whole.setString("placementRequestId", actionId.toString());
            whole.setString("op", "snapshotFlow");
            var actions = new HologramNetwork.ServerHandler();
            var packet = retiredAction(whole);
            actions.onMessage(packet, context);
            placementTick();
            SimpleAssert.eq(0, captured.sends, "unpublished endpoint has no refusal authority");
            HologramWorkspace.publishActionEndpoint(player, false);
            actions.onMessage(packet, context);
            placementTick();
            captured.assertRefusal(player, actionId, 1);
            var quote = (NBTTagCompound) whole.copy();
            UUID quoteId = UUID.randomUUID();
            quote.setString("action", "checkPlacementMaterials");
            quote.setString("placementRequestId", quoteId.toString());
            actions.onMessage(retiredAction(quote), context);
            placementTick();
            captured.assertRefusal(player, quoteId, 2);
            SimpleAssert.eq(reads, worldReads(world), "retired actions do not inspect the world");
            SimpleAssert.that(
                HologramWorkspace.find(player.getUniqueID()) == null
                    && !HologramConstructionReceipts.consumed(player, actionId.toString()),
                "retired action cannot install a session, queue work or claim an ID");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Retired placement production ingress fixture", failure);
        } finally {
            if (player != null) {
                HologramWorkspace.revokeActionEndpoint(player);
                player.playerNetServerHandler.netManager.channel()
                    .close();
            }
            if (channel != null) try {
                channel.set(null, before);
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static HologramNetwork.Action retiredAction(NBTTagCompound whole) {
        ByteBuf wire = Unpooled.buffer();
        try {
            new HologramNetwork.Action(whole).toBytes(wire);
            HologramNetwork.Action packet = new HologramNetwork.Action();
            packet.fromBytes(wire);
            SimpleAssert.eq(0, wire.readableBytes(), "old generic Action envelope uses its real bounded codec");
            return packet;
        } finally {
            wire.release();
        }
    }

    private static void placementTick() {
        ServerTaskScheduler.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
    }

    private static final class RetiredReceiptChannel extends SimpleNetworkWrapper {

        private EntityPlayerMP recipient;
        private NBTTagCompound receipt;
        private IMessage outbound;
        private int sends;

        RetiredReceiptChannel() {
            super("gtmb_retired_test");
        }

        @Override
        public void sendToServer(IMessage message) {
            outbound = message;
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP player) {
            SimpleAssert.that(message instanceof HologramNetwork.State, "ingress emits only a standalone tiny State");
            receipt = ((HologramNetwork.State) message).tag;
            recipient = player;
            sends++;
        }

        private void assertRefusal(EntityPlayerMP owner, UUID request, int count) {
            SimpleAssert.that(
                recipient == owner && sends == count
                    && request.toString()
                        .equals(receipt.getString("placementRequestId"))
                    && receipt.hasKey("placementRequestAccepted", 1)
                    && !receipt.getBoolean("placementRequestAccepted")
                    && receipt.getBoolean("retiredWholePlacement")
                    && receipt.hasKey("errorMessage", 8)
                    && receipt.func_150296_c()
                        .size() == 4,
                "the old peer receives one exact failure with no snapshot, geometry, transfer credit, or progress");
        }
    }

    private static long worldReads(HologramServiceEditRegression.ReadOnlyWorld world) {
        return world.loadReads + world.blockReads + world.metaReads + world.tileReads;
    }
}
