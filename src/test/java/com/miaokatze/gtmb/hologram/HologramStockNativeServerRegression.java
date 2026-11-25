// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Command;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.GregTechAPI;
import io.netty.buffer.Unpooled;

/** Through the actual Forge Action decoder/handler and the real stock cursor/escrow, never a synthetic placement. */
final class HologramStockNativeServerRegression {

    private HologramStockNativeServerRegression() {}

    static void run(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world, EntityPlayerMP player,
        HologramSession former, HologramWorkspace.ActionEndpoint endpoint,
        HologramStockNativeConstruction.HatchChoice hatch, Item item, int[] first) throws ReflectiveOperationException {
        var factory = MessageContext.class.getDeclaredConstructor(INetHandler.class, Side.class);
        factory.setAccessible(true);
        MessageContext context = factory.newInstance(endpoint.connection, Side.SERVER);
        var handler = new HologramNetwork.ServerHandler();
        var channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object oldChannel = channel.get(null);
        var wire = (RecordingChannel) unsafe.allocateInstance(RecordingChannel.class);
        wire.player = player;
        wire.replies = new ArrayList<>();
        channel.set(null, wire);
        try {
            int[] next = new int[3];
            former.machine.getExtendedFacing().getWorldOffset(new int[] { 0, -1, 0 }, next);
            int x = first[0], y = 64 + first[1], z = first[2];
            int x2 = next[0], y2 = 64 + next[1], z2 = next[2];
            var stock = player.inventory.mainInventory;
            stock[1] = new ItemStack(item, 24, 0);
            UUID realTool = UUID.fromString(stock[former.slot].getTagCompound().getString(HologramSessionKeys.TOOL_ID));
            long generation = endpoint.constructionScope.generation;
            var rememberedProbe = verifyWarmAdmission(handler, context, wire, player, former, hatch, world,
                realTool, generation);
            world.setBlock(x, y, z, Blocks.air, 0, 3);
            world.setBlock(x2, y2, z2, Blocks.air, 0, 3);
            int writes = world.writes().size();
            var stopped = new Key(UUID.randomUUID(), 30, generation);
            var wrongEpoch = new Key(UUID.randomUUID(), 31, generation + 1);
            var wrongTool = new Key(UUID.randomUUID(), 32, generation);
            var unexpected = new Key(UUID.randomUUID(), 33, generation);
            var real = new Key(UUID.randomUUID(), 34, generation);
            var finishing = new Key(UUID.randomUUID(), 35, generation);

            saturateQueries(handler, context, generation);
            send(handler, context, confirm(stopped, realTool, player.dimension, former, hatch));
            send(handler, context, HologramStockNativeWire.control(stopped, Command.STOP));
            tick();
            SimpleAssert.that(wire.has(stopped, Phase.STOPPED, 0) && !wire.has(stopped, Phase.ACCEPTED, 0)
                && world.writes().size() == writes && stock[1].stackSize == 24
                && HologramWorkspace.find(endpoint.actor) == null,
                "queued STOP tombstones known confirmation before any temporary session, write or debit");
            send(handler, context, confirm(stopped, realTool, player.dimension, former, hatch));
            tick();
            send(handler, context, confirm(wrongEpoch, realTool, player.dimension, former, hatch));
            send(handler, context, confirm(wrongTool, UUID.randomUUID(), player.dimension, former, hatch));
            var incompatible = confirm(unexpected, realTool, player.dimension, former, hatch);
            incompatible.setString("action", "nativeOrdinaryPlace");
            incompatible.setTag("cells", new NBTTagList());
            send(handler, context, incompatible);
            tick();
            SimpleAssert.that(wire.has(wrongTool, Phase.REFUSED, 0) && wire.none(wrongEpoch) && wire.none(unexpected)
                && world.writes().size() == writes && stock[1].stackSize == 24,
                "old scope, forged tool and nested/mixed legacy action cannot enter any placement route");

            var eventField = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object busBase = unsafe.staticFieldBase(eventField);
            long busOffset = unsafe.staticFieldOffset(eventField);
            EventBus previous = MinecraftForge.EVENT_BUS;
            int[] notified = { 0 };
            Block casing = GregTechAPI.sBlockCasings12;
            unsafe.putObjectVolatile(busBase, busOffset, new EventBus() {
                @Override
                public boolean post(Event event) {
                    if (event instanceof BlockEvent.PlaceEvent place && place.world == world && place.player == player
                        && place.x == x && place.y == y && place.z == z && world.getBlock(x, y, z) == casing
                        && notified[0] == 0) {
                        notified[0]++;
                        SimpleAssert.eq(24, stock[1].stackSize, "real callback sees no speculative debit");
                        var scratch = HologramWorkspace.find(endpoint.actor);
                        SimpleAssert.that(scratch != null && scratch.stockNativePrivate && scratch.capture != null,
                            "the actual transaction scratch is private, even while receiving a Forge callback");
                        HologramService.openSettings(player);
                        HologramService.openAir(player);
                        HologramService.openWorld(player, x, y, z, 2);
                        var obsoleteUi = new NBTTagCompound();
                        obsoleteUi.setString("op", "requestFullSnapshot");
                        obsoleteUi.setString("session", scratch.id);
                        obsoleteUi.setLong("editorWorkspaceEpoch", scratch.editorWorkspaceEpoch);
                        HologramActionDispatcher.handle(player, obsoleteUi);
                        HologramNetwork.dispatchWorkspaceAction(player, obsoleteUi);
                        var area = new NBTTagCompound();
                        area.setString("action", "beginAreaSelection");
                        HologramNetwork.dispatchWorkspaceAction(player, area);
                        SimpleAssert.that(HologramWorkspace.find(endpoint.actor) == scratch && !scratch.open
                            && !HologramWorkspace.AREAS.containsKey(endpoint.actor),
                            "real generic Area/State routing cannot borrow, mutate or publish the private stock seat");
                        saturateQueries(handler, context, generation);
                        send(handler, context, HologramStockNativeWire.control(real, Command.STOP));
                    }
                    return false;
                }
            });
            try {
                send(handler, context, confirm(real, realTool, player.dimension, former, hatch));
                tick();
            } finally {
                unsafe.putObjectVolatile(busBase, busOffset, previous);
            }
            SimpleAssert.that(notified[0] == 1 && world.writes().size() == writes + 1 && stock[1].stackSize == 23
                && world.getBlock(x, y, z) == casing && world.getBlock(x2, y2, z2) == Blocks.air
                && wire.has(real, Phase.ACCEPTED, 0) && wire.has(real, Phase.ACTIVE, 1),
                "the issued real Forge permit settles atomically; callback STOP beats saturated STATUS and blocks next cell");
            tick();
            send(handler, context, HologramStockNativeWire.control(real, Command.STATUS));
            tick();
            SimpleAssert.that(wire.has(real, Phase.STOPPED, 1) && world.writes().size() == writes + 1
                && HologramWorkspace.find(endpoint.actor) == null, "owner status reports exact persisted one-cell prefix");

            stock[1] = new ItemStack(item, 24, 0);
            send(handler, context, confirm(finishing, realTool, player.dimension, former, hatch));
            for (int i = 0; i < 4 && !wire.has(finishing, Phase.COMPLETE, 1); i++) tick();
            SimpleAssert.that(wire.has(finishing, Phase.COMPLETE, 1)
                && world.getBlock(x2, y2, z2) == casing && stock[1].stackSize == 23
                && !HologramConstructionJournal.pending(player) && HologramWorkspace.find(endpoint.actor) == null,
                "new explicit fully-funded short confirmation reaches actual second write and exact debit without old quote");

            verifyInventoryAndPolicy(handler, context, wire, player, former, hatch, world, x, y, z, item,
                generation, realTool);
            verifyColdProof(handler, context, wire, player, former, hatch, world, item, generation, realTool);
            verifyExpiredReceipts(handler, context, wire, endpoint, former, hatch, finishing, realTool);
            verifySameChannelLifetime(unsafe, handler, context, wire, player, former, hatch, world, endpoint,
                rememberedProbe, realTool);
            com.miaokatze.gtmb.client.hologram.HologramStockNativeClientRegression.run();
        } finally {
            channel.set(null, oldChannel);
        }
    }

    private static Confirmation verifyWarmAdmission(HologramNetwork.ServerHandler handler, MessageContext context,
        RecordingChannel wire, EntityPlayerMP player, HologramSession former,
        HologramStockNativeConstruction.HatchChoice hatch, HologramPlacementFixtureWorld world,
        UUID tool, long generation) throws ReflectiveOperationException {
        int writes = world.writes().size();
        var early = declaration(new Key(UUID.randomUUID(), 21, generation), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.confirm(early));
        tick();
        SimpleAssert.that(wire.has(early.key(), Phase.NEEDS_PROOF, 0) && world.writes().size() == writes,
            "the shared current generation without native proof cannot warm or start native");

        Key refusedNonce = new Key(UUID.randomUUID(), 22, 0);
        var refused = declaration(refusedNonce, UUID.randomUUID(), player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.probe(refused));
        tick();
        SimpleAssert.that(wire.has(refused.forGeneration(generation).key(), Phase.REFUSED, 0),
            "authenticated probe with a different tool is refused");
        int beforeRetry = wire.replies.size();
        send(handler, context, HologramStockNativeWire.probe(declaration(refusedNonce, tool, player.dimension, former, hatch)));
        tick();
        SimpleAssert.eq(beforeRetry, wire.replies.size(), "refused nonce cannot remint after the tool matches");

        var onlyProbe = declaration(new Key(UUID.randomUUID(), 23, 0), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.probe(onlyProbe));
        tick();
        var onlyReady = onlyProbe.forGeneration(generation);
        SimpleAssert.that(wire.has(onlyReady.key(), Phase.CAPABLE, 0), "native probe reports exactly its own capability");
        send(handler, context, HologramStockNativeWire.confirm(onlyReady));
        tick();
        SimpleAssert.that(wire.has(onlyReady.key(), Phase.UNKNOWN, -1) && world.writes().size() == writes,
            "CAPABLE alone cannot convert into a plain BEGIN with the same nonce");

        var lost = declaration(new Key(UUID.randomUUID(), 24, 0), tool, player.dimension, former, hatch);
        var allowed = declaration(new Key(UUID.randomUUID(), 25, 0), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.probe(lost));
        tick();
        send(handler, context, HologramStockNativeWire.probe(allowed));
        tick();
        send(handler, context, HologramStockNativeWire.confirmAfterProbe(lost.forGeneration(generation)));
        tick();
        SimpleAssert.that(wire.has(lost.forGeneration(generation).key(), Phase.UNKNOWN, -1)
            && world.writes().size() == writes, "superseded lost proof cannot reclaim its earlier channel nonce");
        var matched = allowed.forGeneration(generation);
        send(handler, context, HologramStockNativeWire.confirmAfterProbe(matched));
        for (int i = 0; i < 4 && !wire.has(matched.key(), Phase.COMPLETE, 0); i++) tick();
        SimpleAssert.that(wire.has(matched.key(), Phase.ACCEPTED, 0) && wire.has(matched.key(), Phase.COMPLETE, 0)
            && world.writes().size() == writes, "genuine exact first owned action establishes warm ready on matched shell");

        var expiry = declaration(new Key(UUID.randomUUID(), 26, 0), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.probe(expiry));
        tick();
        var serviceField = HologramNetwork.class.getDeclaredField("stockNativeServer");
        serviceField.setAccessible(true);
        var expire = HologramStockNativeServer.class.getDeclaredMethod("expireColdProofs", long.class);
        expire.setAccessible(true);
        expire.invoke(serviceField.get(null), Long.MAX_VALUE);
        int replies = wire.replies.size();
        send(handler, context, HologramStockNativeWire.probe(expiry));
        tick();
        SimpleAssert.eq(replies, wire.replies.size(), "real expiry evicts proof body but old nonce still cannot remint");
        send(handler, context, HologramStockNativeWire.confirmAfterProbe(expiry.forGeneration(generation)));
        tick();
        SimpleAssert.that(wire.has(expiry.forGeneration(generation).key(), Phase.REFUSED, 0)
            && world.writes().size() == writes, "expired body cannot be reconstructed from its retained nonce");
        return expiry;
    }

    private static void verifySameChannelLifetime(sun.misc.Unsafe unsafe, HologramNetwork.ServerHandler handler,
        MessageContext context, RecordingChannel wire, EntityPlayerMP player, HologramSession former,
        HologramStockNativeConstruction.HatchChoice hatch, HologramPlacementFixtureWorld world,
        HologramWorkspace.ActionEndpoint initial, Confirmation replay, UUID tool) throws ReflectiveOperationException {
        int writes = world.writes().size(), initialDimension = player.dimension;
        var channel = initial.connection.netManager.channel();
        var serviceField = HologramNetwork.class.getDeclaredField("stockNativeServer");
        serviceField.setAccessible(true);
        var slotsField = HologramStockNativeServer.class.getDeclaredField("probeSlots");
        slotsField.setAccessible(true);
        var probeSlots = (HologramRequestGate) slotsField.get(serviceField.get(null));
        var queuedOnInitial = declaration(new Key(UUID.randomUUID(), 42, 0), tool, initialDimension, former, hatch);
        int beforeRotation = wire.replies.size();
        send(handler, context, HologramStockNativeWire.probe(queuedOnInitial));
        SimpleAssert.eq(1, probeSlots.size(), "initial endpoint admitted the actual probe before any server tick");
        var away = HologramPlacementFixtureWorld.create(unsafe);
        away.provider.dimensionId = initialDimension + 1;
        player.worldObj = away;
        player.dimension = away.provider.dimensionId;
        HologramWorkspace.publishActionDimension(player);
        var intermediate = HologramWorkspace.captureActionEndpoint(initial.connection);
        SimpleAssert.that(intermediate != null && intermediate.world == away
            && intermediate.constructionScope.generation != initial.constructionScope.generation,
            "the same real physical channel entered a different live world scope");
        player.worldObj = world;
        player.dimension = initialDimension;
        HologramWorkspace.publishActionDimension(player);
        var current = HologramWorkspace.captureActionEndpoint(initial.connection);
        SimpleAssert.that(current != null && current.world == initial.world && current.connection == initial.connection
            && current.connection.netManager.channel() == channel
            && initial.constructionScope.generation != current.constructionScope.generation,
            "same physical Netty channel has a newly published world scope after two transitions");
        long generation = current.constructionScope.generation;
        tick();
        SimpleAssert.eq(beforeRotation, wire.replies.size(), "rotating before the queued probe runs emits no reply");
        send(handler, context, HologramStockNativeWire.probe(queuedOnInitial));
        tick();
        SimpleAssert.eq(beforeRotation, wire.replies.size(),
            "authenticated queued PROBE dropped before dispatch cannot remint in the successor scope");
        send(handler, context, HologramStockNativeWire.probe(replay));
        tick();
        SimpleAssert.eq(beforeRotation, wire.replies.size(),
            "processed zero-gen old PROBE cannot remint after A→B→A on the same socket");

        var unready = declaration(new Key(UUID.randomUUID(), 43, generation), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.confirm(unready));
        tick();
        SimpleAssert.that(wire.has(unready.key(), Phase.NEEDS_PROOF, 0), "warm ready is native generation-bound");
        player.inventory.mainInventory[1].stackSize = 24;
        var next = declaration(new Key(UUID.randomUUID(), 44, 0), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.probe(next));
        tick();
        send(handler, context, HologramStockNativeWire.confirmAfterProbe(next.forGeneration(generation)));
        for (int i = 0; i < 4 && !wire.has(next.forGeneration(generation).key(), Phase.COMPLETE, 0); i++) tick();
        SimpleAssert.that(wire.has(next.forGeneration(generation).key(), Phase.COMPLETE, 0),
            "a fresh higher native nonce may establish the new scope explicitly");
        var pending = declaration(new Key(UUID.randomUUID(), 45, 0), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.probe(pending));
        tick();
        SimpleAssert.that(wire.has(pending.forGeneration(generation).key(), Phase.CAPABLE, 0),
            "original authority has a pending proof before the in-process reset");

        var queuedAtReset = declaration(new Key(UUID.randomUUID(), 46, 0), tool, player.dimension, former, hatch);
        int beforeResetReplay = wire.replies.size();
        send(handler, context, HologramStockNativeWire.probe(queuedAtReset));
        SimpleAssert.eq(1, probeSlots.size(), "same endpoint has an actual admitted probe to discard at reset");
        HologramNetwork.clearServerQueues(); // Neither tick nor dispatch the newest probe before resetting.
        tick();
        SimpleAssert.eq(beforeResetReplay, wire.replies.size(), "in-process reset drops the queued proof without a reply");
        send(handler, context, HologramStockNativeWire.probe(queuedAtReset));
        tick();
        SimpleAssert.eq(beforeResetReplay, wire.replies.size(),
            "in-process reset cannot forget the queued nonce that was never dispatched");
        send(handler, context, HologramStockNativeWire.probe(pending));
        tick();
        SimpleAssert.eq(beforeResetReplay, wire.replies.size(),
            "in-process reset cannot remint a dispatched pending nonce on the same socket");
        var staleWarm = declaration(new Key(UUID.randomUUID(), 47, generation), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.confirm(staleWarm));
        tick();
        SimpleAssert.that(wire.has(staleWarm.key(), Phase.NEEDS_PROOF, 0),
            "reset invalidates native warm permission, never the connection's monotone high-water");
        var fresh = declaration(new Key(UUID.randomUUID(), 48, 0), tool, player.dimension, former, hatch);
        send(handler, context, HologramStockNativeWire.probe(fresh));
        tick();
        SimpleAssert.that(wire.has(fresh.forGeneration(generation).key(), Phase.CAPABLE, 0)
            && world.writes().size() == writes && player.inventory.mainInventory[1].stackSize == 24
            && HologramWorkspace.find(initial.actor) == null,
            "a new nonce still gets only a capability receipt, with no world, stock or workspace change");
    }

    private static void saturateQueries(HologramNetwork.ServerHandler handler, MessageContext context, long generation) {
        send(handler, context, HologramStockNativeWire.control(new Key(UUID.randomUUID(), 111, generation), Command.STATUS));
        send(handler, context, HologramStockNativeWire.control(new Key(UUID.randomUUID(), 112, generation), Command.STATUS));
    }

    private static void verifyInventoryAndPolicy(HologramNetwork.ServerHandler handler, MessageContext context,
        RecordingChannel wire, EntityPlayerMP player, HologramSession former,
        HologramStockNativeConstruction.HatchChoice hatch, HologramPlacementFixtureWorld world, int x, int y, int z,
        Item item, long generation, UUID realTool) {
        var originalInventory = player.inventory;
        var originalSlots = originalInventory.mainInventory;
        var projector = originalSlots[former.slot];
        var savedTag = (NBTTagCompound) projector.getTagCompound().copy();
        world.setBlock(x, y, z, Blocks.air, 0, 3);
        int writes = world.writes().size();
        originalSlots[1] = new ItemStack(item, 24, 0);
        try {
            Key changedArray = new Key(UUID.randomUUID(), 36, generation);
            wire.observe = response -> {
                if (response.key().equals(changedArray) && response.phase() == Phase.ACCEPTED)
                    originalInventory.mainInventory = originalSlots.clone();
            };
            send(handler, context, confirm(changedArray, realTool, player.dimension, former, hatch));
            tick();
            SimpleAssert.that(wire.has(changedArray, Phase.STOPPED, 0)
                && originalInventory.mainInventory != originalSlots, "admitted job stops on backing array substitution");
            originalInventory.mainInventory = originalSlots;

            Key changedInventory = new Key(UUID.randomUUID(), 37, generation);
            var replacement = new InventoryPlayer(player);
            replacement.mainInventory = originalSlots;
            replacement.currentItem = originalInventory.currentItem;
            wire.observe = response -> {
                if (response.key().equals(changedInventory) && response.phase() == Phase.ACCEPTED)
                    player.inventory = replacement;
            };
            send(handler, context, confirm(changedInventory, realTool, player.dimension, former, hatch));
            tick();
            SimpleAssert.that(wire.has(changedInventory, Phase.STOPPED, 0)
                && player.inventory == replacement, "admitted job stops on inventory identity swap with same array and tool");
            player.inventory = originalInventory;
            wire.observe = null;

            var settings = HologramMaterials.read(projector);
            settings.main = false;
            HologramMaterials.write(projector, settings);
            Key noMain = new Key(UUID.randomUUID(), 38, generation);
            send(handler, context, confirm(noMain, realTool, player.dimension, former, hatch));
            tick();
            SimpleAssert.that(wire.has(noMain, Phase.REFUSED, 0) && !HologramMaterials.read(projector).main,
                "confirmed default does not turn main inventory back on for this projector");
            settings.main = true;
            settings.containers = true;
            settings.priority = 1;
            HologramMaterials.write(projector, settings);
            Key containerFirst = new Key(UUID.randomUUID(), 39, generation);
            var requested = confirm(containerFirst, realTool, player.dimension, former, hatch);
            requested.setInteger("priority", 1);
            send(handler, context, requested);
            tick();
            SimpleAssert.that(wire.has(containerFirst, Phase.REFUSED, 0)
                && HologramMaterials.read(projector).priority == 1 && world.writes().size() == writes
                && originalSlots[1].stackSize == 24
                && HologramWorkspace.find(player.getUniqueID()) == null,
                "matching container-first configuration is refused, never silently rewritten to main-only");
        } finally {
            wire.observe = null;
            player.inventory = originalInventory;
            originalInventory.mainInventory = originalSlots;
            projector.setTagCompound(savedTag);
        }
    }

    private static void verifyColdProof(HologramNetwork.ServerHandler handler, MessageContext context,
        RecordingChannel wire, EntityPlayerMP player, HologramSession former,
        HologramStockNativeConstruction.HatchChoice hatch, HologramPlacementFixtureWorld world,
        Item item, long generation, UUID tool) {
        player.inventory.mainInventory[1] = new ItemStack(item, 24, 0);
        int before = world.writes().size();
        var old = new Key(UUID.randomUUID(), 40, 0);
        var declared = new Confirmation(old, player.dimension, tool, former.x, former.y, former.z,
            former.side, former.slot, former.facing, 1, false, false, 0, hatch.dx, hatch.dy, hatch.dz, hatch.facing);
        var exact = declared.forGeneration(generation);
        send(handler, context, HologramStockNativeWire.probe(declared));
        tick();
        SimpleAssert.that(wire.has(exact.key(), Phase.CAPABLE, 0) && world.writes().size() == before
            && HologramWorkspace.find(player.getUniqueID()) == null
            && player.inventory.mainInventory[1].stackSize == 24, "actual one confirmed cold PROBE never opens or writes");
        int count = wire.replies.size();
        send(handler, context, HologramStockNativeWire.probe(declared));
        tick();
        SimpleAssert.eq(count, wire.replies.size(), "same nonce cannot mint another capability");
        var changed = HologramStockNativeWire.confirmAfterProbe(exact);
        changed.setInteger("hatchFacing", (hatch.facing + 1) % 6);
        send(handler, context, changed);
        tick();
        SimpleAssert.that(wire.has(exact.key(), Phase.REFUSED, 0) && world.writes().size() == before,
            "changed confirmed pose cannot use the nonce");

        var clean = new Confirmation(new Key(UUID.randomUUID(), 41, 0), player.dimension, tool,
            former.x, former.y, former.z, former.side, former.slot, former.facing, 1, false, false, 0,
            hatch.dx, hatch.dy, hatch.dz, hatch.facing);
        send(handler, context, HologramStockNativeWire.probe(clean));
        tick();
        send(handler, context, HologramStockNativeWire.confirmAfterProbe(clean.forGeneration(generation)));
        for (int i = 0; i < 4 && !wire.has(clean.forGeneration(generation).key(), Phase.COMPLETE, 1); i++) tick();
        SimpleAssert.that(wire.has(clean.forGeneration(generation).key(), Phase.COMPLETE, 1)
            && world.writes().size() == before + 1 && player.inventory.mainInventory[1].stackSize == 23,
            "actual same nonce and shape after the cold probe reaches the real normal native write and debit");
    }

    private static void verifyExpiredReceipts(HologramNetwork.ServerHandler handler, MessageContext context,
        RecordingChannel wire, HologramWorkspace.ActionEndpoint endpoint, HologramSession former,
        HologramStockNativeConstruction.HatchChoice hatch, Key completed, UUID realTool) throws ReflectiveOperationException {
        var serviceField = HologramNetwork.class.getDeclaredField("stockNativeServer");
        serviceField.setAccessible(true);
        var detailCache = HologramStockNativeServer.class.getDeclaredField("settled");
        detailCache.setAccessible(true);
        // The fixture expires the detail cache deterministically; the real gate still retains committed count.
        ((Map<?, ?>) detailCache.get(serviceField.get(null))).clear();
        send(handler, context, HologramStockNativeWire.control(completed, Command.STATUS));
        tick();
        Reply exact = wire.replies.get(wire.replies.size() - 1);
        SimpleAssert.that(exact.key().equals(completed) && exact.phase() == Phase.COMPLETE && exact.placed() == 1,
            "after detailed result expiry, only the exact gate settlement can supply known progress");
        var gate = HologramWorkspace.constructionGate(endpoint);
        for (long sequence = 1000; sequence < 1300; sequence++) {
            var request = new Key(UUID.randomUUID(), sequence, completed.generation()).gateRequest();
            var admission = gate.acquire(endpoint.constructionScope, request, false, false);
            if (admission.lease == null || !gate.finish(admission.lease, HologramConstructionTaskGate.End.FAILED))
                throw new AssertionError("test expires real bounded gate outcomes without any world operation");
        }
        send(handler, context, confirm(completed, realTool, endpoint.player.dimension, former, hatch));
        tick();
        Reply expired = wire.replies.get(wire.replies.size() - 1);
        SimpleAssert.that(expired.key().equals(completed) && expired.phase() == Phase.UNKNOWN && expired.placed() == -1,
            "old BEGIN after both bounded caches expire is unknown, never a false refusal or count of zero");
    }

    private static NBTTagCompound confirm(Key key, UUID tool, int dimension, HologramSession source,
        HologramStockNativeConstruction.HatchChoice hatch) {
        return HologramStockNativeWire.confirm(declaration(key, tool, dimension, source, hatch));
    }

    private static Confirmation declaration(Key key, UUID tool, int dimension, HologramSession source,
        HologramStockNativeConstruction.HatchChoice hatch) {
        return new Confirmation(key, dimension, tool, source.x, source.y, source.z,
            source.side, source.slot, source.facing, 1, false, false, 0, hatch.dx, hatch.dy, hatch.dz, hatch.facing);
    }

    private static void send(HologramNetwork.ServerHandler handler, MessageContext context, NBTTagCompound tag) {
        var actual = new HologramNetwork.Action();
        actual.tag = tag;
        var bytes = Unpooled.buffer();
        try {
            actual.toBytes(bytes);
            SimpleAssert.that(bytes.readableBytes() <= 512, "actual stock Action is a bounded compact frame");
            var received = new HologramNetwork.Action();
            received.fromBytes(bytes);
            SimpleAssert.that(!bytes.isReadable(), "native test crossed the complete Action frame");
            handler.onMessage(received, context);
        } finally {
            bytes.release();
        }
    }

    private static void tick() {
        HologramNetwork.tickStockNativeServer(Long.MAX_VALUE);
    }

    private static final class RecordingChannel extends SimpleNetworkWrapper {

        EntityPlayerMP player;
        List<Reply> replies;
        Consumer<Reply> observe;

        RecordingChannel() {
            super("gtmb_stock_native_regression");
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP target) {
            SimpleAssert.that(target == player && message instanceof HologramNetwork.State,
                "real stock receipt owner, no unrelated message");
            var tag = ((HologramNetwork.State) message).tag;
            Reply reply = HologramStockNativeWire.readReply(tag);
            SimpleAssert.that(reply != null && tag.getBoolean("serverRulesOnly") && !tag.hasKey("cells"),
                "every actual native wire reply is small and sessionless, never any geometry baseline");
            replies.add(reply);
            if (observe != null) observe.accept(reply);
        }

        boolean has(Key key, Phase phase, int placed) {
            return replies.stream().anyMatch(reply -> reply.key().equals(key) && reply.phase() == phase
                && reply.placed() == placed);
        }

        boolean none(Key key) {
            return replies.stream().noneMatch(reply -> reply.key().equals(key));
        }
    }
}
