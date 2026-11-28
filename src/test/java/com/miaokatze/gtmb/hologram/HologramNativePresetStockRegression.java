// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import gregtech.api.GregTechAPI;
import io.netty.buffer.Unpooled;

/** Exercises the public quote, both Forge handlers, real held source settings and simulation-only actor inventory. */
final class HologramNativePresetStockRegression {

    private HologramNativePresetStockRegression() {}

    static void run(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world, EntityPlayerMP player,
        HologramSession source, MessageContext context, UUID tool) throws ReflectiveOperationException {
        var channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        var before = channel.get(null);
        var receiver = HologramNativePresetStock.class.getDeclaredField("receiver");
        receiver.setAccessible(true);
        Object beforeReceiver = receiver.get(null);
        var wire = (QuoteChannel) unsafe.allocateInstance(QuoteChannel.class);
        wire.context = context;
        wire.player = player;
        int id = gregtech.api.enums.MetaTileEntityIDs.COKE_OVEN_HATCH.ID;
        int[] hatch = { id };
        int sources = HologramProjectedMainPolicy.settingsKey(HologramMaterials.read(player.getHeldItem()));
        var previous = player.inventory.mainInventory[2];
        var stack = new ItemStack(GregTechAPI.sBlockMachines, 7, id);
        int writes = world.writes()
            .size();
        var previousSeat = HologramWorkspace.find(player.getUniqueID());
        double originalX = player.posX;
        try {
            player.posX = source.x + 160;
            SimpleAssert.that(
                !HologramSessionPolicy.previewReach(player, source.x, source.y, source.z),
                "real quote starts outside both the old 8/64-block anchor gates");
            player.inventory.mainInventory[2] = stack;
            channel.set(null, wire);
            HologramNativePresetStock.setReceiver((reply, inbound) -> {
                SimpleAssert.that(inbound == context.netHandler, "quote keeps the exact play connection");
                wire.last = reply;
            });
            SimpleAssert.that(wire.ask(1, tool, source, sources, hatch), "real projector sends the compact quote");
            SimpleAssert.that(
                wire.last != null && wire.last.id() == 1
                    && wire.last.reason()
                        .isEmpty()
                    && wire.last.ids()[0] == id
                    && wire.last.counts()[0] == 7,
                "only the selected bare GT hatch is counted");
            SimpleAssert.that(
                wire.ask(2, UUID.randomUUID(), source, sources, hatch) && wire.last.reason()
                    .equals("owner"),
                "wrong projector cannot query");
            SimpleAssert.that(
                wire.ask(3, tool, source, (sources & ~24) | 24, hatch) && wire.last.reason()
                    .equals("sources"),
                "an invalid source priority is rejected before querying providers");
            SimpleAssert.that(
                wire.ask(
                    4,
                    tool,
                    source,
                    sources,
                    new int[] { gregtech.api.enums.MetaTileEntityIDs.COKE_OVEN_CONTROLLER.ID })
                    && wire.last.reason()
                        .equals("invalid"),
                "an ordinary MTE cannot be used as a stock oracle");
            SimpleAssert.that(
                wire.ask(4, tool, source, sources, hatch) && wire.last.reason()
                    .equals("invalid"),
                "stale sequence cannot trigger another count");
            SimpleAssert.that(
                !wire.ask(5, tool, source, sources, new int[] { id, id })
                    && !wire.ask(5, tool, source, sources, new int[HologramNativePresetStock.MAX_IDS + 1]),
                "duplicate or oversized ID sets never go on the wire");
            wire.inject = true;
            SimpleAssert.that(
                wire.ask(6, tool, source, sources, hatch) && wire.last == null,
                "mixed quote/snapshot is consumed without dispatching a workspace action");
            SimpleAssert.that(
                world.writes()
                    .size() == writes && player.inventory.mainInventory[2] == stack
                    && stack.stackSize == 7
                    && HologramWorkspace.find(player.getUniqueID()) == previousSeat,
                "reads never debit, place, create a session or replace the current workspace");
        } finally {
            player.posX = originalX;
            player.inventory.mainInventory[2] = previous;
            channel.set(null, before);
            receiver.set(null, beforeReceiver);
            HologramNativePresetStock.reset();
        }
    }

    private static final class QuoteChannel extends SimpleNetworkWrapper {

        MessageContext context;
        EntityPlayerMP player;
        HologramNativePresetStock.Reply last;
        boolean inject;

        QuoteChannel() {
            super("gtmb_preset_quote_fixture");
        }

        boolean ask(long id, UUID tool, HologramSession source, int sources, int[] hatches) {
            last = null;
            boolean sent = HologramNativePresetStock.request(
                id,
                tool,
                player.dimension,
                source.x,
                source.y,
                source.z,
                gregtech.api.enums.MetaTileEntityIDs.COKE_OVEN_CONTROLLER.ID,
                source.machine.getExtendedFacing()
                    .ordinal(),
                source.slot,
                sources,
                hatches);
            ServerTaskScheduler.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            return sent;
        }

        @Override
        public void sendToServer(IMessage message) {
            var action = (HologramNetwork.Action) message;
            if (inject) action.tag.setTag("geometry", new NBTTagCompound());
            var request = new HologramNetwork.Action();
            roundTrip(action, request);
            new HologramNetwork.ServerHandler().onMessage(request, context);
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP owner) {
            SimpleAssert.that(owner == player && message instanceof HologramNetwork.State, "exact owner receipt");
            var reply = new HologramNetwork.State();
            roundTrip(message, reply);
            SimpleAssert.that(!reply.tag.hasKey("cells") && !reply.tag.hasKey("session"), "no geometry or workspace");
            new HologramNetwork.ClientHandler().onMessage(reply, context);
        }

        private static void roundTrip(IMessage sent, IMessage received) {
            var bytes = Unpooled.buffer();
            try {
                sent.toBytes(bytes);
                SimpleAssert.that(bytes.readableBytes() < 512, "the one-type quote is a small standalone frame");
                received.fromBytes(bytes);
            } finally {
                bytes.release();
            }
        }
    }
}
