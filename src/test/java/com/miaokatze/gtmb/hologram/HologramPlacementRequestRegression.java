// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.World;

import com.miaokatze.gtmb.hologram.HologramProjectedPlacementGuardTrace.Reason;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class HologramPlacementRequestRegression {

    private HologramPlacementRequestRegression() {}

    public static void main(String[] args) {
        testDirectIntent();
        testDirectMaterializationOrder();
        System.out.println("Retired v4 direct placement codec baseline passed (offline only).");
    }

    private static void testDirectIntent() {
        var cell = new HologramProjectedPlacement.Cell(
            0,
            0,
            0,
            "minecraft:cobblestone",
            0,
            "minecraft:cobblestone",
            0,
            -1);
        var plan = new HologramProjectedPlacement.Plan(
            Collections.singletonList(cell),
            Collections.singletonList(new HologramProjectedPlacement.Need("minecraft:cobblestone", 0, 1)));
        UUID id = UUID.randomUUID();
        int[] anchor = { 1, 64, 1 };
        try {
            NBTTagCompound legacy = HologramPlacementRequest.encode(id, anchor, plan);
            if (HologramPlacementRequest.header(legacy)
                .direct()) throw new AssertionError("Legacy is review-only");
            NBTTagCompound direct = HologramPlacementRequest.encodeDirect(id, anchor, plan);
            var restored = reassemble(prepareLegacyBaseline(direct, () -> true));
            var header = HologramPlacementRequest.header(restored);
            if (!header.direct() || !header.id()
                .equals(id.toString())
                || HologramPlacementRequest.decode(header, restored, 256)
                    .size() != 1)
                throw new AssertionError("The retired codec baseline must preserve explicit direct intent");
            NBTTagCompound sources = (NBTTagCompound) direct.copy();
            sources.setTag("materialSources", new NBTTagCompound());
            expectFailure(() -> HologramPlacementRequest.header(sources));
            NBTTagCompound mismatched = (NBTTagCompound) direct.copy();
            mismatched.setInteger("placementWireVersion", 3);
            expectFailure(() -> HologramPlacementRequest.header(mismatched));
            NBTTagCompound oldOp = (NBTTagCompound) direct.copy();
            oldOp.setString("action", "checkPlacementMaterials");
            expectFailure(() -> HologramPlacementRequest.header(oldOp));
            expectFailure(() -> HologramPlacementRequest.materialCheck(oldOp));
        } catch (IOException | InterruptedException failure) {
            throw new AssertionError("Standard direct placement wire regression", failure);
        }
    }

    private static void testDirectMaterializationOrder() {
        int count = 123_369;
        int[] operations = new int[count * 4];
        int[] controllers = { 0, 2048, count - 1 };
        for (int index : controllers) operations[index * 4 + 3] = 1;
        var ordinary = new HologramPlacementRequest.Variant(
            "minecraft:stone", 0, "minecraft:stone", 0, -1, false, "", "");
        var controller = new HologramPlacementRequest.Variant(
            "gregtech:gt.blockmachines", 0, "gregtech:gt.blockmachines", 1, -1, true, "", "");
        try {
            var rows = new HologramPlacementRequest.Decoded(Arrays.asList(ordinary, controller), operations);
            int[] order = HologramProjectedPlacementService.materializationOrder(rows);
            int previous = -1;
            for (int i = 0; i < count - controllers.length; i++) {
                if (order[i] <= previous || rows.cell(order[i]).controller())
                    throw new AssertionError("Decoded ordinary placement order is not stable");
                previous = order[i];
            }
            if (!Arrays.equals(controllers, Arrays.copyOfRange(order, count - controllers.length, count)))
                throw new AssertionError("Controller-last placement order is not stable");
            var plain = new HologramPlacementRequest.Decoded(Collections.singletonList(ordinary), new int[4]);
            if (HologramProjectedPlacementService.materializationOrder(plain) != null)
                throw new AssertionError("All-ordinary plan must materialize directly in source order");
            String id = "55d42698-9957-42b2-9c98-2a51db13d505";
            NBTTagCompound update = HologramProjectedPlacementService
                .preparationProgress(id, "placement.materialize", "", 53, count);
            NBTTagCompound progress = update.getCompoundTag("progress");
            if (!update.getBoolean("progressOnly") || update.hasKey("placementRequestId")
                || update.hasKey("placementRequestAccepted")
                || !id.equals(progress.getString("placementRequestId"))
                || !"projectedPlacement".equals(progress.getString("scope"))
                || progress.getLong("done") != 53
                || progress.getLong("total") != count)
                throw new AssertionError("Scoped preparation progress must never become an acceptance/refusal");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Direct placement materialization regression interrupted", failure);
        }
    }

    static void run() {
        testDirectIntent();
        testDirectMaterializationOrder();
        HologramProjectedPlacement.Cell first = new HologramProjectedPlacement.Cell(
            -2,
            0,
            5,
            "minecraft:stone",
            0,
            "minecraft:stone",
            0,
            -1);
        HologramProjectedPlacement.Cell second = new HologramProjectedPlacement.Cell(
            0,
            2,
            -2,
            "minecraft:stone",
            0,
            "minecraft:stone",
            0,
            -1);
        HologramProjectedPlacement.Plan plan = new HologramProjectedPlacement.Plan(
            Arrays.asList(first, second),
            Collections.singletonList(new HologramProjectedPlacement.Need("minecraft:stone", 0, 2)));
        try {
            serverPreparationToolGuards(plan);
            UUID id = UUID.randomUUID();
            NBTTagCompound tag = HologramPlacementRequest.encode(id, new int[] { 100, 64, 200 }, plan);
            HologramPlacementRequest.Header header = HologramPlacementRequest.header(tag);
            HologramPlacementRequest.Decoded decoded = HologramPlacementRequest.decode(header, tag, 256);
            HologramPlacementUpload.Prepared small = prepareLegacyBaseline(tag, () -> true);
            if (small.frameCount() != 1 || !id.equals(small.id))
                throw new AssertionError("The v4 baseline no longer encodes its one-frame reference");
            NBTTagCompound smallRestored = reassemble(small);
            if (smallRestored.getInteger("clientSnapshotFlow") != HologramSnapshotFlowControl.VERSION
                || tag.hasKey("clientSnapshotFlow")
                || HologramPlacementRequest.decode(HologramPlacementRequest.header(smallRestored), smallRestored, 256)
                    .size() != 2)
                throw new AssertionError("Streamed review lost the normal next-snapshot credit capability or plan");
            expectFailure(() -> prepareLegacyBaseline(tag, () -> false));
            expectFailure(() -> HologramNetwork.prepareProjectedPlacement(tag, () -> {
                throw new AssertionError("Retired production prepare must reject before payload or owner processing");
            }));
            expectFailure(() -> HologramNetwork.sendPreparedPlacement(small, () -> {
                throw new AssertionError("Retired production send must never process or upload a prepared full payload");
            }));
            retiredProductionIngress(tag);
            try {
                HologramNetwork.sendAction(tag);
                throw new AssertionError("The generic outbound Action still accepts a placement request");
            } catch (IllegalArgumentException expected) {
                // New clients cannot accidentally reintroduce the unsegmented path.
            }
            if (!id.toString()
                .equals(header.id()) || decoded.size() != 2
                || decoded.cell(0)
                    .x() != -2
                || decoded.cell(1)
                    .z() != -2
                || !tag.getCompoundTag("blueprint")
                    .hasNoTags()
                || tag.getTagList("placementVariants", 10)
                    .tagCount() != 1)
                throw new AssertionError("Projected operation wire differs from the client plan");

            NBTTagCompound underpaid = (NBTTagCompound) tag.copy();
            underpaid.getTagList("placementMaterials", 10)
                .getCompoundTagAt(0)
                .setInteger("count", 1);
            reject(header, underpaid);
            NBTTagCompound invalidVariant = (NBTTagCompound) tag.copy();
            invalidVariant.getIntArray("placementOperations")[3] = 1;
            reject(header, invalidVariant);
            NBTTagCompound aboveWorld = (NBTTagCompound) tag.copy();
            aboveWorld.getIntArray("placementOperations")[1] = 200;
            reject(header, aboveWorld);
            NBTTagCompound duplicate = (NBTTagCompound) tag.copy();
            int[] operations = duplicate.getIntArray("placementOperations");
            System.arraycopy(operations, 0, operations, 4, 4);
            reject(header, duplicate);
            deviceRoundTrip(id);
            localController(id);
            testOrdinaryEligibility();
            placementWindow(id);
            mediumPlacement(tag);
            segmentedPlacement(id, tag);
            overBudgetPreparation(tag);
            byte[] detachedCells = ("{\"schema\":2,\"blueprint\":{\"schema\":2,\"id\":\"old\",\"name\":\"Old\","
                + "\"structures\":[{\"id\":\"removed-provider\",\"sourceClass\":\"missing.Source\",\"main\":0}],"
                + "\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\","
                + "\"metadata\":0,\"damage\":0,\"structureId\":\"removed-group\"}]}}").getBytes(StandardCharsets.UTF_8);
            HologramProjectedPlacement.Plan detached = HologramProjectedPlacement
                .compile(detachedCells, new int[] { 100, 64, 200 }, new int[0], 256, () -> true);
            if (detached.cells()
                .size() != 1
                || detached.needs()
                    .get(0)
                    .count() != 1)
                throw new AssertionError("Stored grid operations must be independent of old declaration or group data");
        } catch (IOException | InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Historical v4 packer; no production client or server uses the bytes it emits. */
    private static HologramPlacementUpload.Prepared prepareLegacyBaseline(NBTTagCompound tag, BooleanSupplier current)
        throws IOException {
        HologramPlacementRequest.Header header = HologramPlacementRequest.header(tag);
        NBTTagCompound envelope = (NBTTagCompound) tag.copy();
        envelope.setInteger("clientSnapshotProtocol", 1);
        envelope.setInteger("clientSnapshotPalette", HologramSnapshotPalette.VERSION);
        envelope.setInteger("clientSnapshotFlow", HologramSnapshotFlowControl.VERSION);
        byte[] bytes = HologramSnapshotCodec.compress(
            envelope, HologramPlacementUpload.MAX_EXPANDED, HologramPlacementUpload.MAX_COMPRESSED, current);
        if (!current.getAsBoolean()) throw new IOException("Baseline preparation cancelled");
        return HologramPlacementUpload.prepareBaseline(header.id(), bytes);
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
            HologramPlacementUpload.Handler fragments = new HologramPlacementUpload.Handler();
            HologramPlacementUpload.RetiredFrame first = retiredFrame(fragmentId, 0);
            HologramPlacementUpload.RetiredFrame suffix = retiredFrame(fragmentId, 1);
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
            HologramProjectedPlacementService.retire(player, staleId.toString());
            fragments.onMessage(retiredFrame(staleId, 0), context);
            placementTick();
            SimpleAssert.eq(2, captured.sends, "a request ID already owned by a native lane is not failed by old frames");
            SimpleAssert.eq(reads, worldReads(world), "old wire rejection never checks world geometry");
            SimpleAssert.that(HologramWorkspace.find(player.getUniqueID()) == null
                && !HologramProjectedPlacementService.preparing(player) && !HologramPlacementUpload.active(player)
                && !HologramProjectedPlacementService.consumed(player, actionId.toString())
                && !HologramProjectedPlacementService.consumed(player, fragmentId.toString()),
                "refusal never starts an upload, reserves an ID, installs a workspace or queues a placement job");
            liveLayerProbe(player, context, captured);
            UUID retiredQuoteId = UUID.randomUUID();
            NBTTagCompound retiredQuote = (NBTTagCompound) whole.copy();
            retiredQuote.setString("action", "checkPlacementMaterials");
            retiredQuote.setString("placementRequestId", retiredQuoteId.toString());
            actions.onMessage(retiredAction(retiredQuote), context);
            placementTick();
            captured.assertRefusal(player, retiredQuoteId, 5);
            SimpleAssert.that(!HologramProjectedPlacementService.preparing(player),
                "the retired quote wire never enqueues an old server quote");
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
            && proof.getBoolean("serverRulesOnly") && proof.getInteger("ordinaryLayerProbe") == 1
            && id.toString().equals(proof.getString("ordinaryLayerRequest"))
            && proof.getLong("ordinaryLayerIntent") == firstIntent && proof.getLong("ordinaryLayerGeneration") == generation,
            "owned proof is tiny and binds the actual lifecycle scope");

        Field clientReceiver = HologramNetwork.class.getDeclaredField("projectedLayerNegotiation");
        clientReceiver.setAccessible(true);
        var before = (HologramNetwork.ProjectedLayerNegotiation) clientReceiver.get(null);
        int[] negotiated = { 0 };
        try {
            HologramNetwork.setProjectedLayerNegotiation((request, intent, version, scope, inbound) -> {
                SimpleAssert.that(id.equals(request) && intent == firstIntent && version == 1 && scope == generation
                    && inbound == context.netHandler, "probe conveys the actual inbound play-handler identity");
                negotiated[0]++;
            });
            NBTTagCompound oldReply = new NBTTagCompound();
            oldReply.setBoolean("serverRulesOnly", true);
            new HologramNetwork.ClientHandler().onMessage(new HologramNetwork.State(oldReply), context);
            SimpleAssert.eq(0, negotiated[0], "old generic rules response cannot unlock typed packet transmission");
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

    private static HologramPlacementUpload.RetiredFrame retiredFrame(UUID request, int index) {
        int chunk = HologramPlacementUpload.CHUNK_BYTES;
        HologramPlacementUpload.Fragment original = new HologramPlacementUpload.Fragment(
            request, chunk + 1, index, new byte[32], new byte[index == 0 ? chunk : 1]);
        ByteBuf wire = Unpooled.buffer();
        try {
            original.toBytes(wire);
            HologramPlacementUpload.RetiredFrame frame = new HologramPlacementUpload.RetiredFrame();
            frame.fromBytes(wire);
            SimpleAssert.eq(0, wire.readableBytes(), "production retirement consumes old framing without its contents");
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

    private static void placementWindow(UUID id) {
        Object connection = new Object();
        HologramPlacementUpload.SendWindow window = new HologramPlacementUpload.SendWindow(id, connection, 6, 0);
        for (int i = 0; i < 4; i++)
            if (window.nextFrame() != i) throw new AssertionError("Placement did not issue its four credits");
        long second = TimeUnit.SECONDS.toNanos(1);
        window.acknowledge(id, connection, 5, second);
        window.acknowledge(id, new Object(), 4, second);
        window.acknowledge(UUID.randomUUID(), connection, 4, second);
        if (window.nextFrame() != -1 || window.acknowledged() != 0)
            throw new AssertionError("Wrong owner, request or unsent ack released a placement credit");
        window.acknowledge(id, connection, 2, second);
        if (window.nextFrame() != 4 || window.nextFrame() != 5 || window.nextFrame() != -1)
            throw new AssertionError("Placement ack did not release the exact capacity");
        window.acknowledge(id, connection, 2, 29 * second);
        window.acknowledge(id, connection, 6, 32 * second);
        if (!window.expired(32 * second) || window.acknowledged() != 2 || window.complete())
            throw new AssertionError("A duplicate or expired ack revived the placement upload");
        HologramPlacementUpload.SendWindow between = new HologramPlacementUpload.SendWindow(id, connection, 8, 0);
        for (int i = 0; i < 6; i++) {
            if (between.nextFrame() != i) throw new AssertionError("Valid placement credits were lost");
            between.acknowledge(id, connection, i + 1, (i + 1) * 15 * second);
        }
        if (!between.expired(91 * second)) throw new AssertionError("Progress defeated the absolute placement limit");
    }

    private static NBTTagCompound geometry(NBTTagCompound seed, int cells, int spread) {
        NBTTagCompound plan = (NBTTagCompound) seed.copy();
        plan.setIntArray("placementAnchor", new int[] { 0, 0, 0 });
        int[] operations = new int[4 * cells];
        Random random = new Random(123_365);
        for (int i = 0; i < cells; i++) {
            operations[4 * i] = i;
            operations[4 * i + 1] = i & 255;
            operations[4 * i + 2] = random.nextInt(2 * spread) - spread;
        }
        plan.setIntArray("placementOperations", operations);
        plan.getTagList("placementMaterials", 10)
            .getCompoundTagAt(0)
            .setInteger("count", cells);
        return plan;
    }

    private static void mediumPlacement(NBTTagCompound seed) throws IOException, InterruptedException {
        int cells = 123_369;
        NBTTagCompound plan = geometry(seed, cells, 1024);
        HologramPlacementUpload.Prepared upload = prepareLegacyBaseline(plan, () -> true);
        if (upload.bytes() <= Short.MAX_VALUE || upload.bytes() >= 1024 * 1024)
            throw new AssertionError("Synthetic placement no longer covers the release's unsafe selection gap");
        NBTTagCompound restored = reassemble(upload);
        if (!Arrays.equals(restored.getIntArray("placementOperations"), plan.getIntArray("placementOperations"))
            || !restored.getTag("placementMaterials")
                .equals(plan.getTag("placementMaterials"))
            || !restored.getTag("placementVariants")
                .equals(plan.getTag("placementVariants"))
            || restored.getInteger("clientSnapshotFlow") != HologramSnapshotFlowControl.VERSION
            || HologramPlacementRequest.decode(HologramPlacementRequest.header(restored), restored, 256)
                .size() != cells)
            throw new AssertionError("The medium streamed placement could not hand off an exact review request");
    }

    /**
     * The stock C08 handler can assign ItemStack.copyItemStack to the same held slot. Run the real
     * upload-current and projected Job guard branches on that detached stack; never instantiate a world write task.
     */
    private static void serverPreparationToolGuards(HologramProjectedPlacement.Plan plan) throws IOException {
        EntityPlayerMP player = null, foreign = null;
        try {
            Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) access.get(null);
            Class<HologramServiceEditRegression.ReadOnlyWorld> worldType = HologramServiceEditRegression.ReadOnlyWorld.class;
            var world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe.allocateInstance(worldType);
            var otherWorld = (HologramServiceEditRegression.ReadOnlyWorld) unsafe.allocateInstance(worldType);
            Method factory = HologramLibraryServiceRegression.class
                .getDeclaredMethod("player", sun.misc.Unsafe.class, worldType, UUID.class, String.class);
            factory.setAccessible(true);
            UUID actor = UUID.randomUUID();
            player = (EntityPlayerMP) factory.invoke(null, unsafe, world, actor, "PreparationToolFixture");
            foreign = (EntityPlayerMP) factory.invoke(null, unsafe, world, actor, "OtherPreparationFixture");
            player.posX = .5;
            player.posY = 64;
            player.posZ = .5;
            ItemStack original = player.getHeldItem();
            String id = UUID.randomUUID()
                .toString();
            NBTTagCompound identity = new NBTTagCompound();
            identity.setString(HologramSessionKeys.TOOL_ID, id);
            identity.setInteger("gtitHologramMain", 1);
            original.setTagCompound(identity);

            NBTTagCompound tag = HologramPlacementRequest.encode(UUID.randomUUID(), new int[] { 0, 64, 0 }, plan);
            var header = HologramPlacementRequest.header(tag);
            var upload = prepareLegacyBaseline(tag, () -> true);
            Class<?> incomingType = Class.forName(HologramPlacementUpload.class.getName() + "$Incoming");
            var incomingCtor = incomingType.getDeclaredConstructor(
                EntityPlayerMP.class,
                World.class,
                NetHandlerPlayServer.class,
                HologramPlacementUpload.Fragment.class);
            incomingCtor.setAccessible(true);
            var current = incomingType.getDeclaredMethod("current");
            current.setAccessible(true);
            var ownership = incomingType.getDeclaredMethod("ownedBy", EntityPlayerMP.class);
            ownership.setAccessible(true);
            Class<?> jobType = Class.forName(HologramProjectedPlacementService.class.getName() + "$Job");
            var jobCtor = jobType.getDeclaredConstructor(
                EntityPlayerMP.class,
                HologramPlacementRequest.Header.class,
                NBTTagCompound.class);
            jobCtor.setAccessible(true);
            var firstRejection = jobType.getDeclaredMethod("firstRejection");
            firstRejection.setAccessible(true);
            NetHandlerPlayServer connection = player.playerNetServerHandler;
            Object incoming = incomingCtor.newInstance(player, world, connection, upload.frame(0));
            Object job = jobCtor.newInstance(player, header, tag);
            assertPreparation(current, incoming, firstRejection, job, true, null, "original captured slot");
            ItemStack detached = ItemStack.copyItemStack(original);
            SimpleAssert.that(detached != original, "the vanilla use copy changes Java reference");
            player.inventory.mainInventory[0] = detached;
            assertPreparation(current, incoming, firstRejection, job, true, null, "exact canonical held copy");
            SimpleAssert
                .that(!Boolean.TRUE.equals(ownership.invoke(incoming, foreign)), "same UUID is not the same player");

            player.inventory.mainInventory[1] = detached;
            player.inventory.currentItem = 1;
            assertPreparation(current, incoming, firstRejection, job, false, Reason.SLOT_CHANGED, "wrong slot");
            player.inventory.currentItem = 0;
            player.worldObj = otherWorld;
            assertPreparation(current, incoming, firstRejection, job, false, Reason.WORLD_CHANGED, "wrong world");
            player.worldObj = world;
            player.posX = 100;
            SimpleAssert.eq(Reason.ANCHOR_REACH, firstRejection.invoke(job), "copy does not bypass anchor reach");
            player.posX = .5;
            world.unloaded = true;
            SimpleAssert.eq(Reason.ANCHOR_NOT_LOADED, firstRejection.invoke(job), "copy does not bypass loaded anchor");
            world.unloaded = false;
            Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
            sessionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var sessions = (java.util.Map<UUID, HologramSession>) sessionsField.get(null);
            sessions.put(actor, new HologramSession(player, 0, 64, 0, false, 1));
            try {
                assertPreparation(
                    current,
                    incoming,
                    firstRejection,
                    job,
                    false,
                    Reason.WORKSPACE_CHANGED,
                    "changed workspace");
            } finally {
                sessions.remove(actor);
            }
            player.playerNetServerHandler = foreign.playerNetServerHandler;
            SimpleAssert.eq(false, current.invoke(incoming), "another connected handler is not the upload owner");
            player.playerNetServerHandler = null;
            assertPreparation(current, incoming, firstRejection, job, false, Reason.CONNECTION_CLOSED, "disconnected");
            player.playerNetServerHandler = connection;
            player.capabilities.allowEdit = false;
            assertPreparation(current, incoming, firstRejection, job, false, Reason.EDIT_DISABLED, "edit permission");
            player.capabilities.allowEdit = true;

            ItemStack wrongId = detached.copy();
            wrongId.getTagCompound()
                .setString(
                    HologramSessionKeys.TOOL_ID,
                    UUID.randomUUID()
                        .toString());
            ItemStack changedState = detached.copy();
            changedState.getTagCompound()
                .setInteger("gtitHologramMain", 2);
            ItemStack changedCount = detached.copy();
            changedCount.stackSize++;
            ItemStack changedDamage = detached.copy();
            changedDamage.setItemDamage(1);
            ItemStack wrongItem = new ItemStack(Blocks.stone);
            wrongItem.setTagCompound((NBTTagCompound) identity.copy());
            for (ItemStack replaced : Arrays.asList(wrongId, changedState, changedCount, changedDamage, wrongItem)) {
                player.inventory.mainInventory[0] = replaced;
                assertPreparation(
                    current,
                    incoming,
                    firstRejection,
                    job,
                    false,
                    Reason.STACK_REPLACED,
                    "replacement differs in captured identity or tool state");
            }
            original.getTagCompound()
                .setInteger("gtitHologramMain", 2);
            player.inventory.mainInventory[0] = original;
            assertPreparation(current, incoming, firstRejection, job, true, null, "original ref semantics unchanged");
            player.inventory.mainInventory[0] = original.copy();
            assertPreparation(
                current,
                incoming,
                firstRejection,
                job,
                false,
                Reason.STACK_REPLACED,
                "a detached replacement must match the immutable pre-copy capture");

            ItemStack virgin = new ItemStack(original.getItem());
            player.inventory.mainInventory[0] = virgin;
            Object unstampedIncoming = incomingCtor.newInstance(player, world, connection, upload.frame(0));
            Object unstampedJob = jobCtor.newInstance(player, header, tag);
            assertPreparation(
                current,
                unstampedIncoming,
                firstRejection,
                unstampedJob,
                true,
                null,
                "untagged original");
            player.inventory.mainInventory[0] = virgin.copy();
            assertPreparation(
                current,
                unstampedIncoming,
                firstRejection,
                unstampedJob,
                false,
                Reason.STACK_REPLACED,
                "untagged lookalikes receive no replacement authority");
            player.inventory.mainInventory[0] = original;
            detachedDirectAdmission(player, world, sessions, jobType, jobCtor);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("real projected preparation guards", failure);
        } finally {
            if (player != null && player.playerNetServerHandler != null)
                player.playerNetServerHandler.netManager.channel()
                    .close();
            if (foreign != null && foreign.playerNetServerHandler != null)
                foreign.playerNetServerHandler.netManager.channel()
                    .close();
        }
    }

    private static void detachedDirectAdmission(EntityPlayerMP player, World world,
        java.util.Map<UUID, HologramSession> sessions, Class<?> jobType, java.lang.reflect.Constructor<?> constructor)
        throws IOException, ReflectiveOperationException {
        var cell = new HologramProjectedPlacement.Cell(
            0,
            0,
            0,
            "minecraft:cobblestone",
            0,
            "minecraft:cobblestone",
            0,
            -1);
        var plan = new HologramProjectedPlacement.Plan(
            Collections.singletonList(cell),
            Collections.singletonList(new HologramProjectedPlacement.Need("minecraft:cobblestone", 0, 1)));
        UUID request = UUID.randomUUID(), actor = player.getUniqueID();
        int[] anchor = { 0, 64, 0 };
        ItemStack material = new ItemStack(Blocks.cobblestone);
        player.inventory.mainInventory[1] = material;
        HologramMaterials.Settings settings = HologramMaterials.read(player.getHeldItem());
        settings.containers = settings.me = false; // This standalone fixture uses the real main inventory only.
        HologramMaterials.write(player.getHeldItem(), settings);
        Path previous = HologramConstructionJournal.STORAGE_ROOT.get();
        Path root = Files.createTempDirectory("gtmb-detached-direct-admission-");
        HologramConstructionJournal.STORAGE_ROOT.set(root);
        Field revision = DecorationVoucherConfig.class.getDeclaredField("revision");
        revision.setAccessible(true);
        long originalRevision = revision.getLong(null);
        try {
            NBTTagCompound tag = HologramPlacementRequest.encodeDirect(request, anchor, plan);
            Object job = constructor.newInstance(player, HologramPlacementRequest.header(tag), tag);
            Field decoding = jobType.getDeclaredField("decoding");
            decoding.setAccessible(true);
            ((FutureTask<?>) decoding.get(job)).run();
            Method step = jobType.getDeclaredMethod("step", long.class);
            step.setAccessible(true);
            SimpleAssert.eq(null, HologramWorkspace.find(actor), "direct plan starts detached");
            try {
                SimpleAssert.eq(true, step.invoke(job, Long.MAX_VALUE), "real direct admission finishes its start");
            } catch (InvocationTargetException failure) {
                throw new AssertionError(
                    "real detached direct admission must get past the voucher-rules gate",
                    failure.getCause());
            }
            HologramSession s = HologramWorkspace.find(actor);
            SimpleAssert.that(
                s != null && s.executionRequestId.equals(request.toString())
                    && s.job == 1
                    && s.work.equals(Collections.singletonList(0)),
                "only a real Start publishes one running owned cell");
            SimpleAssert.that(
                HologramMaterialQuote.rulesCurrent(s.materialQuote) && s.id.equals(s.materialQuote.getString("session"))
                    && s.materialQuote.getLong("planRevision") == s.planRevision
                    && !s.materialQuote.getBoolean("complete")
                    && !s.materialQuote.getBoolean("useDecorationVoucher")
                    && !s.blueprintAwaitingConfirmation
                    && s.materialQuoteCache == null,
                "direct stamp is current and scoped, never a presented quote or voucher selection");
            SimpleAssert.that(
                world.getBlock(0, 64, 0) == Blocks.air && material.stackSize == 1,
                "admitting a job is not placement or payment");

            // Exercise the actual Start again against the same priced plan, off the live workspace.
            HologramSnapshotPreparation.cancel(s);
            sessions.remove(actor, s);
            s.job = 0;
            // Beginning consumes the original approval nonce; obtain a new frozen witness for each new start.
            var repriced = HologramResourcePlanner.beginDirectPlan(s);
            SimpleAssert.that(repriced.step(Integer.MAX_VALUE, Long.MAX_VALUE), "fixture re-prices the detached plan");
            var frozen = repriced.directDemand();
            HologramMaterialQuote.stampDirectPlan(s);
            HologramExecutionReview.Start stale = new HologramExecutionReview.Start(s, new NBTTagCompound(), frozen);
            revision.setLong(null, originalRevision + 1);
            try {
                stale.step(s, Long.MAX_VALUE);
                throw new AssertionError("changed voucher rules must refuse before direct Start");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("抵用券规则"),
                    "expected changed voucher rules refusal");
            }
            SimpleAssert.eq(0, s.job, "stale rules cannot start a job");
            revision.setLong(null, originalRevision);

            HologramMaterialQuote.stampDirectPlan(s);
            var observed = (HologramServiceEditRegression.ReadOnlyWorld) world;
            long observedReads = worldReads(observed);
            SimpleAssert.that(HologramMaterialWorkflow.recheckDirect(s, frozen), "unmodified current stock is available");
            SimpleAssert.eq(observedReads, worldReads(observed), "final stock check never re-traverses the world");
            player.inventory.mainInventory[1] = null;
            SimpleAssert.eq(
                true,
                new HologramExecutionReview.Start(s, new NBTTagCompound(), frozen).step(s, Long.MAX_VALUE),
                "missing stock ends authoritative startup check");
            SimpleAssert.eq(0, s.job, "a valid stamp cannot authorize missing stock");
            SimpleAssert.eq(Blocks.air, world.getBlock(0, 64, 0), "missing stock changes no block");
            player.inventory.mainInventory[1] = material;
            observed.denyMine = true;
            try {
                new HologramExecutionReview.Start(s, new NBTTagCompound(), frozen).step(s, Long.MAX_VALUE);
                throw new AssertionError("revoked permissions must refuse direct admission");
            } catch (IOException expected) {
                SimpleAssert.that(expected.getMessage().contains("权限"), "expected changed permission refusal");
            } finally {
                observed.denyMine = false;
            }
            SimpleAssert.eq(0, s.job, "revoked permissions cannot start a job");
            exactDirectDemand(player, observed);
        } finally {
            HologramSession active = sessions.remove(actor);
            if (active != null) HologramSnapshotPreparation.cancel(active);
            HologramEditorWorkspaceRegistry.forget(actor);
            revision.setLong(null, originalRevision);
            if (previous == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(previous);
            Files.deleteIfExists(root.resolve("gtmb-construction-recovery"));
            Files.deleteIfExists(root);
        }
    }

    private static long worldReads(HologramServiceEditRegression.ReadOnlyWorld world) {
        return world.loadReads + world.blockReads + world.metaReads + world.tileReads + world.permissionReads;
    }

    private static void exactDirectDemand(EntityPlayerMP player, HologramServiceEditRegression.ReadOnlyWorld world)
        throws IOException {
        ItemStack exact = new ItemStack(Blocks.cobblestone);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("variant", "retained exact component");
        exact.setTagCompound(tag);
        ItemStack wrong = exact.copy();
        wrong.getTagCompound().setString("variant", "different component");
        ItemStack shell = new ItemStack(Blocks.cobblestone);
        ItemStack chest = new ItemStack(Blocks.chest);
        var adapter = new HologramNativePlacement.Adapter() {

            public boolean supports(net.minecraft.block.Block block) {
                return block == Blocks.chest;
            }

            public net.minecraft.tileentity.TileEntity create(net.minecraft.block.Block block, int metadata,
                NBTTagCompound payload, World detachedWorld) {
                return new net.minecraft.tileentity.TileEntityChest();
            }

            public java.util.List<ItemStack> materials(net.minecraft.block.Block block, int metadata,
                net.minecraft.tileentity.TileEntity tile) {
                return Arrays.asList(chest.copy(), exact.copy());
            }
        };
        String provider = "gtmb:regression_direct_exact";
        HologramNativePlacement.register(provider, adapter);
        try {
            player.inventory.mainInventory[1] = shell;
            player.inventory.mainInventory[2] = chest;
            player.inventory.mainInventory[3] = exact;
            var s = new HologramSession(player, 0, 64, 0, false, 1);
            s.virtual = s.virtualPlacement = s.virtualLocked = true;
            s.executionRequestId = UUID.randomUUID().toString();
            s.virtualPreset = new HologramPresets.Preset();
            s.virtualPreset.schema = 2;
            s.virtualPreset.retainNbt = s.virtualPreset.retainCovers = true;
            s.virtualPreset.deidentificationLevel = 1;
            s.placementDeidentificationChosen = true;
            s.capture = new HologramCapture(null, HologramSessionPolicy.trigger(s));
            addExactDirectCell(s, Blocks.cobblestone, shell, "minecraft:cobblestone", "");
            NBTTagCompound payload = new NBTTagCompound();
            payload.setString("id", "Chest");
            addExactDirectCell(s, Blocks.chest, chest, "minecraft:chest", HologramBlueprintNbt.encodeCanonical(payload));
            var task = HologramResourcePlanner.beginDirectPlan(s);
            SimpleAssert.that(task.step(Integer.MAX_VALUE, Long.MAX_VALUE), "native exact demands are planned");
            SimpleAssert.that(s.planMissing == 0 && s.planRecoveryFits, "planner can price both shell and exact component");
            SimpleAssert.eq(1, s.blueprintResources.items.size(), "retained native component is counted once");
            SimpleAssert.that(ItemStack.areItemStackTagsEqual(exact, s.blueprintResources.items.get(0)),
                "planned native bill preserves full component NBT");
            var frozen = task.directDemand();
            HologramMaterialQuote.stampDirectPlan(s);
            long before = worldReads(world);
            SimpleAssert.that(HologramMaterialWorkflow.recheckDirect(s, frozen), "exact component and shell both fit");
            player.inventory.mainInventory[3] = wrong;
            SimpleAssert.that(!HologramMaterialWorkflow.recheckDirect(s, frozen), "same item/damage cannot pay different NBT");
            player.inventory.mainInventory[3] = exact;
            player.inventory.mainInventory[1] = null;
            SimpleAssert.that(!HologramMaterialWorkflow.recheckDirect(s, frozen), "one component cannot also pay its fuzzy shell");
            SimpleAssert.eq(before, worldReads(world), "distinct stock recount performs no extra world reads");
            SimpleAssert.eq(1, exact.stackSize, "stock recount cannot debit the exact component");
            HologramNativePlacement.unregister(provider, adapter);
            SimpleAssert.that(!frozen.current(s), "provider epoch change retires exact demand");
        } finally {
            HologramNativePlacement.unregister(provider, adapter);
            player.inventory.mainInventory[1] = null;
            player.inventory.mainInventory[2] = null;
            player.inventory.mainInventory[3] = null;
        }
    }

    private static void addExactDirectCell(HologramSession s, net.minecraft.block.Block block, ItemStack item,
        String registryId, String tileNbt) {
        int index = s.capture.cells.size();
        var cell = new HologramCapture.Cell(null, index, 64, 0);
        cell.block = block;
        cell.userPosition = true;
        cell.role = "prefab";
        cell.blueprintBlock = cell.blueprintItem = registryId;
        cell.tileNbt = tileNbt;
        cell.stableIndex = index;
        String position = HologramValues.position(cell);
        s.capture.cells.add(cell);
        s.capture.positions.put(position, cell);
        s.pins.put(index, item.copy());
        s.coordinatePins.put(position, item.copy());
    }

    private static void assertPreparation(Method current, Object incoming, Method firstRejection, Object job,
        boolean accepted, Reason reason, String label) throws ReflectiveOperationException {
        SimpleAssert.eq(accepted, current.invoke(incoming), "upload preparation: " + label);
        SimpleAssert.eq(reason, firstRejection.invoke(job), "projected job first rejection: " + label);
    }

    private static NBTTagCompound reassemble(HologramPlacementUpload.Prepared prepared) throws IOException {
        HologramPlacementUpload.Assembly assembly = new HologramPlacementUpload.Assembly(prepared.frame(0));
        ByteBuf wire = Unpooled.buffer();
        try {
            for (int index = 0; index < prepared.frameCount(); index++) {
                wire.clear();
                wire.writeByte(10); // SimpleImpl discriminator included in Forge's complete custom payload length.
                prepared.frame(index)
                    .toBytes(wire);
                if (wire.readableBytes() > 16_441 || wire.readableBytes() >= Short.MAX_VALUE)
                    throw new AssertionError("An emitted placement frame exceeds its C17 envelope");
                wire.skipBytes(1);
                HologramPlacementUpload.Fragment received = new HologramPlacementUpload.Fragment();
                received.fromBytes(wire);
                byte[] progress = assembly.accept(received);
                if ((progress != null) != (index == prepared.frameCount() - 1))
                    throw new AssertionError("Placement finalized on the wrong fragment");
            }
        } finally {
            wire.release();
        }
        return assembly.decode();
    }

    private static void overBudgetPreparation(NBTTagCompound seed) throws IOException {
        NBTTagCompound plan = (NBTTagCompound) seed.copy();
        char[] filler = new char[60_000];
        Arrays.fill(filler, 'a');
        String field = new String(filler);
        for (int i = 0; i <= HologramPlacementUpload.MAX_EXPANDED / filler.length; i++)
            plan.setString("testSize" + i, field);
        expectFailure(() -> prepareLegacyBaseline(plan, () -> true));
    }

    private static void segmentedPlacement(UUID id, NBTTagCompound seed) throws IOException, InterruptedException {
        int count = 650_000;
        NBTTagCompound plan = geometry(seed, count, 30_000_000);
        int[] operations = plan.getIntArray("placementOperations");
        HologramPlacementUpload.Prepared prepared = prepareLegacyBaseline(plan, () -> true);
        if (prepared.bytes() <= 100 * Short.MAX_VALUE)
            throw new AssertionError("Large placement did not exercise 100× the old safe payload in aggregate");
        byte[] compressed = HologramSnapshotCodec.compress(plan);
        byte[] checksum = HologramPlacementUpload.digest(compressed);
        HologramPlacementUpload.Fragment first = fragment(id, compressed, checksum, 0);
        ByteBuf wire = Unpooled.buffer();
        try {
            first.toBytes(wire);
            if (wire.readableBytes() >= Short.MAX_VALUE)
                throw new AssertionError("Placement fragment exceeds C17 size");
            HologramPlacementUpload.Fragment received = new HologramPlacementUpload.Fragment();
            received.fromBytes(wire);
            first = received;
        } finally {
            wire.release();
        }
        HologramPlacementUpload.Assembly assembly = new HologramPlacementUpload.Assembly(first);
        HologramPlacementUpload.Assembly corrupt = new HologramPlacementUpload.Assembly(first);
        expectFailure(assembly::decode);
        expectFailure(() -> new HologramPlacementUpload.Assembly(fragment(id, compressed, checksum, 1)));
        expectFailure(
            () -> new HologramPlacementUpload.Assembly(
                new HologramPlacementUpload.Fragment(
                    id,
                    HologramPlacementUpload.MAX_COMPRESSED + 1,
                    0,
                    checksum,
                    firstData(compressed))));
        if (assembly.accept(first) != null || corrupt.accept(first) != null)
            throw new AssertionError("A partial upload returned the operation payload");
        HologramPlacementUpload.Fragment duplicate = first;
        expectFailure(() -> assembly.accept(duplicate));
        expectFailure(() -> assembly.accept(fragment(id, compressed, checksum, 2)));
        expectFailure(() -> assembly.accept(fragment(UUID.randomUUID(), compressed, checksum, 1)));
        int chunks = (compressed.length + HologramPlacementUpload.CHUNK_BYTES - 1)
            / HologramPlacementUpload.CHUNK_BYTES;
        for (int index = 1; index < chunks; index++) {
            HologramPlacementUpload.Fragment frame = fragment(id, compressed, checksum, index);
            byte[] result = assembly.accept(frame);
            if ((result != null) != (index == chunks - 1))
                throw new AssertionError("Placement finalized on the wrong fragment");
            if (index == chunks - 1) frame.data[0] ^= 1;
            corrupt.accept(frame);
        }
        expectFailure(corrupt::decode);
        NBTTagCompound restored = reassemble(prepared);
        HologramPlacementRequest.Decoded decoded = HologramPlacementRequest
            .decode(HologramPlacementRequest.header(restored), restored, 256);
        if (decoded.size() != count || decoded.cell(count - 1)
            .x() != count - 1
            || decoded.cell(count - 1)
                .z() != operations[4 * (count - 1) + 2])
            throw new AssertionError("The segmented plan changed its operations");

        UUID falseOwner = UUID.randomUUID();
        HologramPlacementUpload.Assembly mismatched = new HologramPlacementUpload.Assembly(
            fragment(falseOwner, compressed, checksum, 0));
        for (int index = 0; index < chunks; index++)
            mismatched.accept(fragment(falseOwner, compressed, checksum, index));
        expectFailure(mismatched::decode);

        NBTTagCompound unrelated = (NBTTagCompound) seed.copy();
        unrelated.setString("action", "checkPlacementMaterials");
        byte[] wrongAction = HologramSnapshotCodec.compress(unrelated);
        HologramPlacementUpload.Fragment wrong = fragment(
            id,
            wrongAction,
            HologramPlacementUpload.digest(wrongAction),
            0);
        HologramPlacementUpload.Assembly wrongKind = new HologramPlacementUpload.Assembly(wrong);
        wrongKind.accept(wrong);
        expectFailure(wrongKind::decode);
    }

    private static byte[] firstData(byte[] encoded) {
        return Arrays.copyOf(encoded, HologramPlacementUpload.CHUNK_BYTES);
    }

    private static HologramPlacementUpload.Fragment fragment(UUID id, byte[] encoded, byte[] checksum, int index) {
        int start = index * HologramPlacementUpload.CHUNK_BYTES;
        int end = Math.min(encoded.length, start + HologramPlacementUpload.CHUNK_BYTES);
        return new HologramPlacementUpload.Fragment(
            id,
            encoded.length,
            index,
            checksum,
            Arrays.copyOfRange(encoded, start, end));
    }

    private static void expectFailure(IoStep step) throws IOException {
        try {
            step.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid segmented placement was accepted");
    }

    @FunctionalInterface
    private interface IoStep {

        void run() throws IOException;
    }

    private static void deviceRoundTrip(UUID id) throws IOException, InterruptedException {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setInteger("mID", 12);
        payload.setString(HologramNetworkBindings.GROUP, "q1");
        String state = HologramBlueprintNbt.encodeCanonical(payload);
        HologramProjectedPlacement.Cell device = new HologramProjectedPlacement.Cell(
            0,
            0,
            0,
            "gregtech:gt.blockmachines",
            0,
            "gregtech:gt.blockmachines",
            12,
            3,
            true,
            state,
            "");
        HologramProjectedPlacement.Plan devicePlan = new HologramProjectedPlacement.Plan(
            Collections.singletonList(device),
            Collections.singletonList(new HologramProjectedPlacement.Need(device.item(), device.damage(), 1)));
        NBTTagCompound request = HologramPlacementRequest.encode(id, new int[] { 10, 64, 20 }, devicePlan);
        HologramPlacementRequest.Header header = HologramPlacementRequest.header(request);
        HologramProjectedPlacement.Cell decoded = HologramPlacementRequest.decode(header, request, 256)
            .cell(0);
        if (!decoded.controller() || !payload.equals(HologramBlueprintNbt.decodeData(decoded.tile())))
            throw new AssertionError("Device operation state did not survive the compact payload palette");
        request.getTagList("placementVariants", 10)
            .getCompoundTagAt(0)
            .setByteArray("device", new byte[] { 1 });
        reject(header, request);
    }

    private static void localController(UUID id) throws IOException {
        NBTTagCompound displayed = new NBTTagCompound();
        displayed.setBoolean("supported", true);
        displayed.setInteger("scope", 1);
        displayed.setInteger("layer", 1);
        displayed.setBoolean("materialMe", false);
        displayed.setInteger("materialPriority", 2);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(displayedRow(10, 64, 0, true));
        cells.appendTag(displayedRow(11, 65, 1, false));
        cells.appendTag(displayedRow(11, 64, 0, false));
        displayed.setTag("cells", cells);
        int[] anchor = { 10, 64, 20 };
        NBTTagCompound check = HologramPlacementRequest.encodeMaterialCheck(id, displayed);
        if (check.hasKey("placementAnchor") || check.hasKey("placementOperations")
            || !Collections.singletonMap(new HologramPlacementRequest.Material("minecraft:stone", 0), 1)
                .equals(HologramPlacementRequest.materialCheck(check)))
            throw new AssertionError("Material inspection carried geometry or ignored the displayed scope");
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        HologramPlacementRequest.sourceOptions(check)
            .apply(settings);
        if (settings.me || !settings.main || !settings.containers || settings.priority != 2) throw new AssertionError(
            "Explicit material sources did not preserve the held-tool defaults for absent fields");
        HologramProjectedPlacement.Plan build = HologramProjectedPlacement
            .compileDisplayed(displayed, anchor, 256, () -> true);
        if (build.cells()
            .size() != 1
            || build.cells()
                .get(0)
                .y() != 1
            || build.needs()
                .get(0)
                .count() != 1)
            throw new AssertionError("Controller build ignored the selected layer");
        NBTTagCompound placement = HologramPlacementRequest.encode(id, anchor, build, displayed);
        if (!HologramPlacementRequest.header(placement)
            .sources()
            .equals(HologramPlacementRequest.sourceOptions(check)))
            throw new AssertionError("Placement and standalone check disagree about the selected material sources");
        displayed.setInteger("scope", 0);
        HologramProjectedPlacement.Plan all = HologramProjectedPlacement
            .compileDisplayed(displayed, anchor, 256, () -> true);
        if (all.cells()
            .size() != 2 || all.cells()
                .stream()
                .anyMatch(cell -> cell.x() == 0 && cell.y() == 0 && cell.z() == 0))
            throw new AssertionError("Controller build included its protected anchor");

        NBTTagCompound hint = displayedRow(12, 64, 0, false);
        hint.setString("wantId", "structurelib:blockhint");
        hint.setBoolean("missingBlock", true);
        hint.setBoolean("iconOnly", true);
        hint.setString("status", "unsupported");
        hint.removeTag("targetStack");
        cells.appendTag(hint);
        rejectUnresolvedDisplayed(displayed, anchor);

        displayed.setInteger("scope", 2);
        displayed.setInteger("selected", 1);
        displayed.setIntArray("selectedIndices", new int[] { 1 });
        HologramProjectedPlacement.Plan concrete = HologramProjectedPlacement
            .compileDisplayed(displayed, anchor, 256, () -> true);
        if (concrete.cells()
            .size() != 1
            || concrete.cells()
                .get(0)
                .x() != 1
            || concrete.cells()
                .get(0)
                .y() != 1
            || concrete.needs()
                .size() != 1
            || concrete.needs()
                .get(0)
                .count() != 1)
            throw new AssertionError("An out-of-scope hint blocked a known target or widened the requested scope");

        displayed.setInteger("selected", 3);
        displayed.setIntArray("selectedIndices", new int[] { 3 });
        rejectUnresolvedDisplayed(displayed, anchor);
    }

    private static void rejectUnresolvedDisplayed(NBTTagCompound displayed, int[] anchor) throws IOException {
        try {
            HologramProjectedPlacement.compileDisplayed(displayed, anchor, 256, () -> true);
        } catch (IOException expected) {
            if (!"所选范围包含未确定材料的结构格，请先明确目标方块".equals(expected.getMessage())) throw expected;
            return;
        }
        throw new AssertionError("An unresolved native hint became a placeable operation");
    }

    private static void testOrdinaryEligibility() throws IOException {
        ItemStack stone = new ItemStack(Blocks.stone);
        HologramExecutionEligibility.validate(Blocks.stone, 0, stone, -1, false, null, null);
        NBTTagCompound authority = new NBTTagCompound();
        authority.setString(HologramNetworkBindings.MODE, "preserve");
        try {
            HologramExecutionEligibility.validate(Blocks.stone, 0, stone, -1, false, null, authority);
        } catch (IOException expected) {
            try {
                HologramExecutionEligibility.validate(Blocks.gold_block, 0, stone, -1, false, null, null);
            } catch (IOException wrongPayment) {
                return;
            }
            throw new AssertionError("An operation accepted a material for the wrong block");
        }
        throw new AssertionError("An operation accepted source-world authority");
    }

    private static NBTTagCompound displayedRow(int x, int y, int dy, boolean anchor) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", x);
        row.setInteger("y", y);
        row.setInteger("z", 20);
        row.setInteger("dy", dy);
        row.setString("wantId", "minecraft:stone");
        row.setBoolean("anchor", anchor);
        row.setTag("targetStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        return row;
    }

    private static void reject(HologramPlacementRequest.Header header, NBTTagCompound tag) throws InterruptedException {
        try {
            HologramPlacementRequest.decode(header, tag, 256);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Malformed projected operation was accepted");
    }
}
