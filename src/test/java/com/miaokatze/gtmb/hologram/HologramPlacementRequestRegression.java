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
        oldRequest.setString("placementRequestId", UUID.randomUUID().toString());
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
            UUID actionId = UUID.randomUUID(), fragmentId = UUID.randomUUID(), staleId = UUID.randomUUID();
            NBTTagCompound whole = (NBTTagCompound) original.copy();
            whole.setString("placementRequestId", actionId.toString());
            whole.setString("op", "snapshotFlow"); // Refusal must precede any snapshot-capability handling.
            HologramNetwork.Action packet = retiredAction(whole);
            HologramNetwork.ServerHandler actions = new HologramNetwork.ServerHandler();
            HologramRetiredPlacementPackets.Handler fragments = new HologramRetiredPlacementPackets.Handler();
            HologramRetiredPlacementPackets.RetiredFrame first = retiredFrame(fragmentId, 0);
            HologramRetiredPlacementPackets.RetiredFrame suffix = retiredFrame(fragmentId, 1);
            actions.onMessage(packet, context);
            fragments.onMessage(first, context);
            placementTick();
            SimpleAssert.eq(0, captured.sends, "an unpublished endpoint cannot start or receive a refusal");

            HologramWorkspace.publishActionEndpoint(player, false);
            actions.onMessage(packet, context);
            fragments.onMessage(first, context); // Same exact endpoint: only one identity-only reply may queue.
            placementTick();
            captured.assertRefusal(player, actionId, 1);
            fragments.onMessage(suffix, context);
            placementTick();
            SimpleAssert.eq(1, captured.sends, "suffixes do not allocate a job or amplify replies");
            fragments.onMessage(first, context);
            placementTick();
            captured.assertRefusal(player, fragmentId, 2);

            fragments.onMessage(retiredFrame(staleId, 0), context);
            HologramWorkspace.publishActionDimension(player);
            placementTick();
            SimpleAssert.eq(2, captured.sends, "refusal cannot redirect after an authenticated endpoint transition");
            HologramConstructionReceipts.retire(player, staleId.toString());
            fragments.onMessage(retiredFrame(staleId, 0), context);
            placementTick();
            SimpleAssert.eq(2, captured.sends, "a request ID already owned by a native lane is not failed by old frames");
            SimpleAssert.eq(reads, worldReads(world), "old wire rejection never checks world geometry");
            SimpleAssert.that(HologramWorkspace.find(player.getUniqueID()) == null
                && !HologramConstructionReceipts.consumed(player, actionId.toString())
                && !HologramConstructionReceipts.consumed(player, fragmentId.toString()),
                "refusal never starts an upload, reserves an ID, installs a workspace or queues a placement job");
            liveLayerProbe(player, context, captured);
            UUID retiredQuoteId = UUID.randomUUID();
            NBTTagCompound retiredQuote = (NBTTagCompound) whole.copy();
            retiredQuote.setString("action", "checkPlacementMaterials");
            retiredQuote.setString("placementRequestId", retiredQuoteId.toString());
            actions.onMessage(retiredAction(retiredQuote), context);
            placementTick();
            captured.assertRefusal(player, retiredQuoteId, 5);
            SimpleAssert.eq(reads, worldReads(world), "ordinary negotiation and retired quote never inspect geometry");
            reciprocalLegacyRefusal(player, world, captured);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Retired placement production ingress fixture", failure);
        } finally {
            HologramNetwork.closeProjectedLayerServer();
            if (player != null) {
                HologramWorkspace.revokeActionEndpoint(player);
                player.playerNetServerHandler.netManager.channel().close();
            }
            if (channel != null) try {
                channel.set(null, before);
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static void liveLayerProbe(EntityPlayerMP player, MessageContext context, RetiredReceiptChannel captured)
        throws ReflectiveOperationException {
        HologramNetwork.ServerHandler ingress = new HologramNetwork.ServerHandler();
        NBTTagCompound oldNoOp = new NBTTagCompound();
        oldNoOp.setString("action", "serverRules");
        ingress.onMessage(retiredAction(oldNoOp), context);
        placementTick();
        SimpleAssert.eq(2, captured.sends, "the old bare rules probe stays a no-op before generic dispatch");

        long swappedIntent = HologramNetwork.nextConstructionIntentSequence();
        SimpleAssert.that(HologramNetwork.sendProjectedLayerProbe(UUID.randomUUID(), swappedIntent),
            "a live actual-confirm probe can enter the authenticated queue before a slot change");
        HologramNetwork.Action swapped = (HologramNetwork.Action) captured.outbound;
        ingress.onMessage(swapped, context);
        int originalSlot = player.inventory.currentItem;
        try {
            player.inventory.currentItem = originalSlot == 0 ? 1 : 0;
            placementTick();
        } finally {
            player.inventory.currentItem = originalSlot;
        }
        ingress.onMessage(swapped, context);
        placementTick();
        SimpleAssert.eq(2, captured.sends,
            "a queued probe cannot mint against the replacement projector and returning to the old slot cannot remint it");

        UUID id = UUID.randomUUID();
        long firstIntent = HologramNetwork.nextConstructionIntentSequence();
        SimpleAssert.that(HologramNetwork.sendProjectedLayerProbe(id, firstIntent)
            && captured.outbound instanceof HologramNetwork.Action,
            "production sends the confirmation probe over the prior known Action discriminator");
        NBTTagCompound probe = ((HologramNetwork.Action) captured.outbound).tag;
        SimpleAssert.that(probe.func_150296_c().size() == 4 && !probe.hasKey("clientSnapshotFlow")
            && "serverRules".equals(probe.getString("action")),
            "confirmation probe contains no geometry or local-view capability fields");

        NBTTagCompound piggyback = (NBTTagCompound) probe.copy();
        piggyback.setIntArray("placementOperations", new int[] { 1, 64, 1, 0 });
        HologramNetwork.Action unrelated = new HologramNetwork.Action();
        unrelated.tag = piggyback;
        ingress.onMessage(unrelated, context);
        placementTick();
        SimpleAssert.eq(2, captured.sends, "unknown fields are not accepted as an actual-confirm probe");

        HologramNetwork.Action exact = (HologramNetwork.Action) captured.outbound;
        ingress.onMessage(exact, context);
        placementTick();
        HologramWorkspace.ActionEndpoint endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
        long generation = endpoint.constructionScope.generation;
        NBTTagCompound proof = captured.receipt;
        SimpleAssert.that(captured.recipient == player && captured.sends == 3 && proof.func_150296_c().size() == 5
            && proof.getBoolean("serverRulesOnly") && proof.getInteger("ordinaryLayerProbe") == HologramProjectedLayerTransport.VERSION
            && id.toString().equals(proof.getString("ordinaryLayerRequest"))
            && proof.getLong("ordinaryLayerIntent") == firstIntent && proof.getLong("ordinaryLayerGeneration") == generation,
            "owned proof is tiny and binds the actual lifecycle scope");

        Field clientReceiver = HologramNetwork.class.getDeclaredField("projectedLayerNegotiation");
        clientReceiver.setAccessible(true);
        var before = (HologramNetwork.ProjectedLayerNegotiation) clientReceiver.get(null);
        int[] negotiated = { 0 };
        try {
            HologramNetwork.setProjectedLayerNegotiation((request, intent, version, scope, inbound) -> {
                SimpleAssert.that(id.equals(request) && intent == firstIntent && version == HologramProjectedLayerTransport.VERSION && scope == generation
                    && inbound == context.netHandler, "probe conveys the actual inbound play-handler identity");
                negotiated[0]++;
            });
            NBTTagCompound oldReply = new NBTTagCompound();
            oldReply.setBoolean("serverRulesOnly", true);
            new HologramNetwork.ClientHandler().onMessage(new HologramNetwork.State(oldReply), context);
            SimpleAssert.eq(0, negotiated[0], "old generic rules response cannot unlock typed packet transmission");
            NBTTagCompound obsoleteProof = (NBTTagCompound) proof.copy();
            obsoleteProof.setInteger("ordinaryLayerProbe", HologramProjectedLayerTransport.VERSION - 1);
            new HologramNetwork.ClientHandler().onMessage(new HologramNetwork.State(obsoleteProof), context);
            SimpleAssert.eq(0, negotiated[0], "old-version proof cannot unlock the modern bounded-window transport");
            new HologramNetwork.ClientHandler().onMessage(new HologramNetwork.State(proof), context);
            SimpleAssert.eq(1, negotiated[0], "the exact production receipt reaches normal client negotiation");
        } finally {
            HologramNetwork.setProjectedLayerNegotiation(before);
        }

        Method claim = HologramNetwork.class.getDeclaredMethod(
            "consumeProjectedLayerTicket", HologramWorkspace.ActionEndpoint.class, HologramProjectedLayerTransport.Key.class);
        claim.setAccessible(true);
        var key = new HologramProjectedLayerTransport.Key(id, firstIntent, generation);
        var wrong = new HologramProjectedLayerTransport.Key(UUID.randomUUID(), firstIntent, generation);
        SimpleAssert.that(!Boolean.TRUE.equals(claim.invoke(null, endpoint, wrong))
            && Boolean.TRUE.equals(claim.invoke(null, endpoint, key))
            && !Boolean.TRUE.equals(claim.invoke(null, endpoint, key)),
            "actual server BEGIN authorizer requires an issued exact ticket and consumes it once");
        ingress.onMessage(exact, context);
        HologramWorkspace.publishActionDimension(player);
        placementTick();
        ingress.onMessage(exact, context);
        placementTick();
        SimpleAssert.eq(3, captured.sends, "late old probe cannot renew across an exact endpoint transition");

        long secondIntent = HologramNetwork.nextConstructionIntentSequence();
        UUID second = UUID.randomUUID();
        SimpleAssert.that(secondIntent > firstIntent && HologramNetwork.sendProjectedLayerProbe(second, secondIntent),
            "a new explicit confirmation may negotiate a new intent");
        ingress.onMessage((HologramNetwork.Action) captured.outbound, context);
        placementTick();
        HologramWorkspace.ActionEndpoint current = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
        var secondKey = new HologramProjectedLayerTransport.Key(second, secondIntent, current.constructionScope.generation);
        int previousSlot = player.inventory.currentItem;
        try {
            player.inventory.currentItem = previousSlot == 0 ? 1 : 0;
            SimpleAssert.that(!Boolean.TRUE.equals(claim.invoke(null, current, secondKey)),
                "the probe's exact server-held slot must still agree at the new BEGIN");
        } finally {
            player.inventory.currentItem = previousSlot;
        }
        SimpleAssert.that(!Boolean.TRUE.equals(claim.invoke(null, current, secondKey)) && captured.sends == 4,
            "returning to the original tool cannot resurrect a refused begin");
    }

    private static void reciprocalLegacyRefusal(EntityPlayerMP player,
        HologramServiceEditRegression.ReadOnlyWorld world, RetiredReceiptChannel captured)
        throws ReflectiveOperationException {
        HologramWorkspace.ActionEndpoint endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
        HologramConstructionTaskGate gate = HologramWorkspace.constructionGate(endpoint);
        HologramConstructionTaskGate.Request scoped = new HologramConstructionTaskGate.Request(
            UUID.randomUUID(), 10, HologramConstructionTaskGate.Kind.PROJECTED_LAYER);
        HologramConstructionTaskGate.Admission admitted = gate.acquire(endpoint.constructionScope, scoped, false, false);
        SimpleAssert.that(admitted.lease != null, "fixture owns a real scoped lease before testing legacy competitors");
        HologramSession origin = null;
        try {
            origin = new HologramSession(player, 0, 64, 0, false, 2);
            HologramWorkspace.activate(player.getUniqueID(), origin);
            long before = worldReads(world);
            for (String op : new String[] { "build", "start", "native", "confirmBlueprintPlacement",
                "placeCurrentBlueprint", "orderMissingMaterials" }) {
                NBTTagCompound action = new NBTTagCompound();
                action.setString("session", origin.id);
                action.setString("op", op);
                HologramActionDispatcher.handle(player, action);
                NBTTagCompound status = captured.receipt;
                SimpleAssert.that(status.getBoolean("progressOnly") && status.func_150296_c().size() == 2
                    && "transport.busy".equals(status.getCompoundTag("progress").getString("phase"))
                    && op.equals(status.getCompoundTag("progress").getString("op")),
                    "legacy action " + op + " returns only its small scoped refusal");
            }
            NBTTagCompound nativePacket = new NBTTagCompound();
            nativePacket.setString("placementRequestId", UUID.randomUUID().toString());
            String[] readyRefusal = { null };
            HologramNativeReadyPlacement.receive(player, nativePacket,
                (owner, request, accepted, reason) -> readyRefusal[0] = accepted ? null : reason);
            SimpleAssert.that(readyRefusal[0] != null && readyRefusal[0].contains("已有施工"),
                "the independently reachable ready-native lane is fenced before admitting or scanning");
            for (String operation : new String[] { "paste", "openWorkspace" }) {
                NBTTagCompound cut = new NBTTagCompound();
                cut.setString("action", operation);
                cut.setString("requestId", UUID.randomUUID().toString());
                if ("paste".equals(operation)) {
                    // Valid wire shape, deliberately no clipboard file: the active scope must win before storage access.
                    cut.setString("id", origin.id);
                    cut.setLong("entrySequence", 1L);
                    cut.setInteger("originX", origin.x);
                    cut.setInteger("originY", origin.y);
                    cut.setInteger("originZ", origin.z);
                    new com.miaokatze.gtmb.api.CutTransforms.Transform(0, 0, 0, 0).write(cut);
                }
                NBTTagCompound receipt = HologramCutService.execute(player, cut);
                SimpleAssert.that("construction_busy".equals(receipt.getString("code")) && !receipt.hasKey("entries")
                    && !receipt.hasKey("preview") && HologramWorkspace.find(player.getUniqueID()) == origin,
                    "Cut " + operation + " cannot recover world mutations, replace the origin, or send a directory");
            }
            try {
                HologramDemolition.start(player, null);
                throw new AssertionError("A legacy demolition must refuse before resolving its selection");
            } catch (IOException refused) {
                SimpleAssert.that(refused.getMessage().contains("已有施工"), "demolition sees the common gate");
            }
            String order = HologramAeCraftingOrders.submit(player, null, null);
            SimpleAssert.that(order.contains("已有施工") && !HologramAeCraftingOrders.active(player),
                "AE's actual direct submit cannot touch a grid or parse its quote while the scoped lease is live");
            SimpleAssert.eq(before, worldReads(world), "competing legacy starts do not run world probes");
            SimpleAssert.that(origin.job == 0 && origin.currentPlacementTask == null
                && origin.nativeReadyPlacement == null && !HologramDemolition.active(player)
                && HologramWorkspace.find(player.getUniqueID()) == origin && gate.scopeCurrent(endpoint.constructionScope),
                "the legacy refusals leave the original scope and workspace intact");
        } finally {
            gate.cancel(endpoint.constructionScope, scoped);
            if (origin != null) {
                origin.cancelled = true;
                Field sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
                sessions.setAccessible(true);
                ((java.util.Map<?, ?>) sessions.get(null)).remove(player.getUniqueID(), origin);
                HologramEditorWorkspaceRegistry.forget(player.getUniqueID());
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

    private static HologramRetiredPlacementPackets.RetiredFrame retiredFrame(UUID request, int index) {
        int chunk = HologramRetiredPlacementPackets.CHUNK_BYTES;
        ByteBuf wire = Unpooled.buffer();
        try {
            // Immutable framing fixture, not a production sender or full-plan codec.
            wire.writeLong(request.getMostSignificantBits());
            wire.writeLong(request.getLeastSignificantBits());
            wire.writeInt(chunk + 1);
            wire.writeInt(index);
            wire.writeZero(32);
            wire.writeZero(index == 0 ? chunk : 1);
            var frame = new HologramRetiredPlacementPackets.RetiredFrame();
            frame.fromBytes(wire);
            SimpleAssert.eq(0, wire.readableBytes(), "obsolete framing is consumed without retaining geometry");
            return frame;
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
            SimpleAssert.that(recipient == owner && sends == count
                && request.toString().equals(receipt.getString("placementRequestId"))
                && receipt.hasKey("placementRequestAccepted", 1) && !receipt.getBoolean("placementRequestAccepted")
                && receipt.getBoolean("retiredWholePlacement") && receipt.hasKey("errorMessage", 8)
                && receipt.func_150296_c().size() == 4,
                "the old peer receives one exact failure with no snapshot, geometry, transfer credit, or progress");
        }
    }

    private static long worldReads(HologramServiceEditRegression.ReadOnlyWorld world) {
        return world.loadReads + world.blockReads + world.metaReads + world.tileReads;
    }
}
