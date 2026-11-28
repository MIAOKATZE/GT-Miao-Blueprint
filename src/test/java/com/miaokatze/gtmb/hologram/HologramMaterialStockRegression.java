// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.Unpooled;

/** Both registered Forge handlers and the real actor stock provider; no fabricated placement quote. */
public final class HologramMaterialStockRegression {

    public static void main(String[] args) {
        int status = 0;
        try {
            if (com.miaokatze.gtmb.testutil.MinecraftTestBootstrap.enter(HologramMaterialStockRegression.class, args)) {
                run();
                System.out.println("PASS carried material stock wire");
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            status = 1;
        }
        System.exit(status);
    }

    static void run() throws Exception {
        for (int destination = 0; destination <= 2; destination++) {
            var settings = HologramMaterialStockWire.decode(7 | destination << 5);
            SimpleAssert.that(
                settings != null && settings.main
                    && settings.containers
                    && settings.me
                    && settings.recoveryDestination == destination
                    && HologramProjectedMainPolicy.structurePlacementKey(settings, true) == (7 | destination << 5),
                "all explicit destinations round-trip without changing source mask bits");
        }
        SimpleAssert.that(
            HologramMaterialStockWire.decode(7 | 3 << 5) == null,
            "undefined recovery destination stays rejected");
        HologramNativeSavedWorldRegression.withConnectedWorld(6, world -> {
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var actor = world.nativeActor();
            var ctor = MessageContext.class.getDeclaredConstructor(INetHandler.class, Side.class);
            ctor.setAccessible(true);
            var context = ctor.newInstance(actor.playerNetServerHandler, Side.SERVER);
            var field = HologramNetwork.class.getDeclaredField("channel");
            field.setAccessible(true);
            var previous = field.get(null);
            var receiver = HologramMaterialStockWire.class.getDeclaredField("receiver");
            receiver.setAccessible(true);
            var previousReceiver = receiver.get(null);
            var wire = (Loopback) unsafe.allocateInstance(Loopback.class);
            wire.context = context;
            wire.actor = actor;
            actor.getHeldItem()
                .getTagCompound()
                .setString(
                    HologramSessionKeys.TOOL_ID,
                    UUID.randomUUID()
                        .toString());
            var originalTool = actor.getHeldItem()
                .copy();
            var originalSeat = HologramWorkspace.find(actor.getUniqueID());
            String tool = actor.getHeldItem()
                .getTagCompound()
                .getString(HologramSessionKeys.TOOL_ID);
            NBTTagList items = new NBTTagList();
            var item = HologramMaterialQuote.itemRow(new ItemStack(Blocks.stone), 9, 999);
            item.setLong("missing", Long.MAX_VALUE); // Client claims are deliberately ignored.
            items.appendTag(item);
            try {
                field.set(null, wire);
                HologramMaterialStockWire.listen((reply, from) -> wire.last = reply);
                SimpleAssert.that(wire.ask(1, tool, 1, "stock", items), "source review uses registered request route");
                var counted = wire.last.getTagList("rows", 10)
                    .getCompoundTagAt(0);
                SimpleAssert.that(
                    wire.last.getBoolean("stockKnown") && counted.getLong("available") == 6
                        && counted.getLong("missing") == 3,
                    "real server stock replaces client availability and missing claims");
                SimpleAssert.that(
                    wire.ask(2, tool, 0, "stock", items) && wire.last.getTagList("rows", 10)
                        .getCompoundTagAt(0)
                        .getLong("available") == 0,
                    "disabled sources are never opened and read as zero selected stock");
                SimpleAssert.that(
                    wire.ask(
                        3,
                        UUID.randomUUID()
                            .toString(),
                        1,
                        "stock",
                        items) && !wire.last.getBoolean("stockKnown"),
                    "foreign projector cannot observe this actor's stock");
                SimpleAssert.that(
                    wire.ask(4, tool, 1, "order", items) && "stock.enable_ae".equals(wire.last.getString("statusKey"))
                        && !HologramAeCraftingOrders.active(actor),
                    "AE-disabled request cannot create a crafting order");
                SimpleAssert.that(
                    wire.ask(4, tool, 1, "stock", items) && wire.last == null,
                    "duplicate request cannot requery or reorder");
                wire.smuggle = true;
                SimpleAssert.that(
                    wire.ask(5, tool, 1, "stock", items) && wire.last == null,
                    "mixed source and construction envelope is rejected before dispatch");
                SimpleAssert.that(
                    world.stock() == 6 && world.writes() == 0
                        && !world.pendingPayment()
                        && HologramWorkspace.find(actor.getUniqueID()) == originalSeat
                        && ItemStack.areItemStacksEqual(originalTool, actor.getHeldItem()),
                    "querying or rejecting an order cannot debit, place, mutate the tool, or acquire a workspace");
                wire.smuggle = false;
                SimpleAssert.that(
                    wire.ask(6, tool, 2 | 8, "sources", new NBTTagList())
                        && wire.last.getBoolean("preferencesAccepted"),
                    "explicit source configuration receives its own acknowledgement");
                var selected = HologramMaterials.read(actor.getHeldItem());
                SimpleAssert.that(
                    !selected.main && selected.containers
                        && !selected.me
                        && selected.priority == 1
                        && world.stock() == 6
                        && world.writes() == 0,
                    "local blueprint source preferences persist on the real held tool without touching stock or world");
                SimpleAssert.that(
                    wire.ask(7, tool, 1, "sources", new NBTTagList())
                        && HologramMaterials.read(actor.getHeldItem()).main
                        && !HologramMaterials.read(actor.getHeldItem()).containers,
                    "subsequent saved construction reads the restored authoritative source selection");
            } finally {
                field.set(null, previous);
                receiver.set(null, previousReceiver);
                HologramMaterialStockWire.reset();
            }
        });
    }

    private static final class Loopback extends SimpleNetworkWrapper {

        MessageContext context;
        EntityPlayerMP actor;
        NBTTagCompound last;
        boolean smuggle;

        Loopback() {
            super("gtmb_carried_material_fixture");
        }

        boolean ask(long id, String tool, int mask, String operation, NBTTagList rows) {
            last = null;
            boolean sent = HologramMaterialStockWire
                .request(id, tool, actor.inventory.currentItem, actor.dimension, mask, operation, rows, "");
            com.miaokatze.gtmb.util.ServerTaskScheduler.INSTANCE.onServerTick(
                new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                    cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
            return sent;
        }

        @Override
        public void sendToServer(IMessage message) {
            if (smuggle) ((HologramNetwork.Action) message).tag.setBoolean("build", true);
            var request = new HologramNetwork.Action();
            roundTrip(message, request);
            if (!smuggle) {
                SimpleAssert
                    .that(HologramMaterialStockWire.valid(request.tag), "valid stock request envelope: " + request.tag);
                SimpleAssert.that(
                    HologramWorkspace.captureActionEndpoint(context.getServerHandler()) != null,
                    "live stock actor endpoint");
            }
            new HologramNetwork.ServerHandler().onMessage(request, context);
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP owner) {
            SimpleAssert.that(owner == actor, "quote returns to the exact play endpoint");
            var reply = new HologramNetwork.State();
            roundTrip(message, reply);
            new HologramNetwork.ClientHandler().onMessage(reply, context);
        }

        private static void roundTrip(IMessage sent, IMessage received) {
            var buffer = Unpooled.buffer();
            try {
                sent.toBytes(buffer);
                received.fromBytes(buffer);
            } finally {
                buffer.release();
            }
        }
    }
}
