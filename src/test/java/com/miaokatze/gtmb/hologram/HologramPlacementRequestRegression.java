// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.World;

import com.miaokatze.gtmb.hologram.HologramProjectedPlacementGuardTrace.Reason;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class HologramPlacementRequestRegression {

    private HologramPlacementRequestRegression() {}

    public static void main(String[] args) {
        testDirectIntent();
        System.out.println("Standard direct placement wire regression passed.");
    }

    private static void testDirectIntent() {
        var cell = new HologramProjectedPlacement.Cell(0, 0, 0, "minecraft:cobblestone", 0,
            "minecraft:cobblestone", 0, -1);
        var plan = new HologramProjectedPlacement.Plan(Collections.singletonList(cell),
            Collections.singletonList(new HologramProjectedPlacement.Need("minecraft:cobblestone", 0, 1)));
        UUID id = UUID.randomUUID();
        int[] anchor = { 1, 64, 1 };
        try {
            NBTTagCompound legacy = HologramPlacementRequest.encode(id, anchor, plan);
            if (HologramPlacementRequest.header(legacy).direct()) throw new AssertionError("Legacy is review-only");
            NBTTagCompound direct = HologramPlacementRequest.encodeDirect(id, anchor, plan);
            var restored = reassemble(HologramNetwork.prepareProjectedPlacement(direct, () -> true));
            var header = HologramPlacementRequest.header(restored);
            if (!header.direct() || !header.id().equals(id.toString())
                || HologramPlacementRequest.decode(header, restored, 256).size() != 1)
                throw new AssertionError("Explicit direct intent must cross the same bounded placement upload");
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

    static void run() {
        testDirectIntent();
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
            HologramPlacementUpload.Prepared small = HologramNetwork.prepareProjectedPlacement(tag, () -> true);
            if (small.frameCount() != 1 || !id.equals(small.id))
                throw new AssertionError("A small execution request did not use the same bounded upload");
            NBTTagCompound smallRestored = reassemble(small);
            if (smallRestored.getInteger("clientSnapshotFlow") != HologramSnapshotFlowControl.VERSION
                || tag.hasKey("clientSnapshotFlow") || HologramPlacementRequest.decode(
                    HologramPlacementRequest.header(smallRestored), smallRestored, 256).size() != 2)
                throw new AssertionError("Streamed review lost the normal next-snapshot credit capability or plan");
            expectFailure(() -> HologramNetwork.prepareProjectedPlacement(tag, () -> false));
            try {
                HologramNetwork.sendAction(tag);
                throw new AssertionError("The generic outbound Action still accepts a placement request");
            } catch (IllegalArgumentException expected) {
                // New clients cannot accidentally reintroduce the unsegmented path.
            }
            if (!id.toString()
                .equals(header.id()) || decoded.size() != 2
                || decoded.cell(0).x() != -2
                || decoded.cell(1).z() != -2
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
                + "\"metadata\":0,\"damage\":0,\"structureId\":\"removed-group\"}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            HologramProjectedPlacement.Plan detached = HologramProjectedPlacement
                .compile(detachedCells, new int[] { 100, 64, 200 }, new int[0], 256, () -> true);
            if (detached.cells().size() != 1 || detached.needs().get(0).count() != 1)
                throw new AssertionError("Stored grid operations must be independent of old declaration or group data");
        } catch (IOException | InterruptedException failure) {
            throw new AssertionError(failure);
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
        plan.getTagList("placementMaterials", 10).getCompoundTagAt(0).setInteger("count", cells);
        return plan;
    }

    private static void mediumPlacement(NBTTagCompound seed) throws IOException, InterruptedException {
        int cells = 123_369;
        NBTTagCompound plan = geometry(seed, cells, 1024);
        HologramPlacementUpload.Prepared upload = HologramNetwork.prepareProjectedPlacement(plan, () -> true);
        if (upload.bytes() <= Short.MAX_VALUE || upload.bytes() >= 1024 * 1024)
            throw new AssertionError("Synthetic placement no longer covers the release's unsafe selection gap");
        NBTTagCompound restored = reassemble(upload);
        if (!Arrays.equals(restored.getIntArray("placementOperations"), plan.getIntArray("placementOperations"))
            || !restored.getTag("placementMaterials").equals(plan.getTag("placementMaterials"))
            || !restored.getTag("placementVariants").equals(plan.getTag("placementVariants"))
            || restored.getInteger("clientSnapshotFlow") != HologramSnapshotFlowControl.VERSION
            || HologramPlacementRequest.decode(HologramPlacementRequest.header(restored), restored, 256).size() != cells)
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
            Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
                "player", sun.misc.Unsafe.class, worldType, UUID.class, String.class);
            factory.setAccessible(true);
            UUID actor = UUID.randomUUID();
            player = (EntityPlayerMP) factory.invoke(null, unsafe, world, actor, "PreparationToolFixture");
            foreign = (EntityPlayerMP) factory.invoke(null, unsafe, world, actor, "OtherPreparationFixture");
            player.posX = .5;
            player.posY = 64;
            player.posZ = .5;
            ItemStack original = player.getHeldItem();
            String id = UUID.randomUUID().toString();
            NBTTagCompound identity = new NBTTagCompound();
            identity.setString(HologramSessionKeys.TOOL_ID, id);
            identity.setInteger("gtitHologramMain", 1);
            original.setTagCompound(identity);

            NBTTagCompound tag = HologramPlacementRequest.encode(UUID.randomUUID(), new int[] {0, 64, 0}, plan);
            var header = HologramPlacementRequest.header(tag);
            var upload = HologramNetwork.prepareProjectedPlacement(tag, () -> true);
            Class<?> incomingType = Class.forName(HologramPlacementUpload.class.getName() + "$Incoming");
            var incomingCtor = incomingType.getDeclaredConstructor(
                EntityPlayerMP.class, World.class, NetHandlerPlayServer.class, HologramPlacementUpload.Fragment.class);
            incomingCtor.setAccessible(true);
            var current = incomingType.getDeclaredMethod("current");
            current.setAccessible(true);
            var ownership = incomingType.getDeclaredMethod("ownedBy", EntityPlayerMP.class);
            ownership.setAccessible(true);
            Class<?> jobType = Class.forName(HologramProjectedPlacementService.class.getName() + "$Job");
            var jobCtor = jobType.getDeclaredConstructor(
                EntityPlayerMP.class, HologramPlacementRequest.Header.class, NBTTagCompound.class);
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
            SimpleAssert.that(!Boolean.TRUE.equals(ownership.invoke(incoming, foreign)), "same UUID is not the same player");

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
                assertPreparation(current, incoming, firstRejection, job, false, Reason.WORKSPACE_CHANGED,
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
            wrongId.getTagCompound().setString(HologramSessionKeys.TOOL_ID, UUID.randomUUID().toString());
            ItemStack changedState = detached.copy();
            changedState.getTagCompound().setInteger("gtitHologramMain", 2);
            ItemStack changedCount = detached.copy();
            changedCount.stackSize++;
            ItemStack changedDamage = detached.copy();
            changedDamage.setItemDamage(1);
            ItemStack wrongItem = new ItemStack(Blocks.stone);
            wrongItem.setTagCompound((NBTTagCompound) identity.copy());
            for (ItemStack replaced : Arrays.asList(wrongId, changedState, changedCount, changedDamage, wrongItem)) {
                player.inventory.mainInventory[0] = replaced;
                assertPreparation(current, incoming, firstRejection, job, false, Reason.STACK_REPLACED,
                    "replacement differs in captured identity or tool state");
            }
            original.getTagCompound().setInteger("gtitHologramMain", 2);
            player.inventory.mainInventory[0] = original;
            assertPreparation(current, incoming, firstRejection, job, true, null, "original ref semantics unchanged");
            player.inventory.mainInventory[0] = original.copy();
            assertPreparation(current, incoming, firstRejection, job, false, Reason.STACK_REPLACED,
                "a detached replacement must match the immutable pre-copy capture");

            ItemStack virgin = new ItemStack(original.getItem());
            player.inventory.mainInventory[0] = virgin;
            Object unstampedIncoming = incomingCtor.newInstance(player, world, connection, upload.frame(0));
            Object unstampedJob = jobCtor.newInstance(player, header, tag);
            assertPreparation(current, unstampedIncoming, firstRejection, unstampedJob, true, null, "untagged original");
            player.inventory.mainInventory[0] = virgin.copy();
            assertPreparation(current, unstampedIncoming, firstRejection, unstampedJob, false, Reason.STACK_REPLACED,
                "untagged lookalikes receive no replacement authority");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("real projected preparation guards", failure);
        } finally {
            if (player != null && player.playerNetServerHandler != null) player.playerNetServerHandler.netManager.channel().close();
            if (foreign != null && foreign.playerNetServerHandler != null) foreign.playerNetServerHandler.netManager.channel().close();
        }
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
                prepared.frame(index).toBytes(wire);
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
        expectFailure(() -> HologramNetwork.prepareProjectedPlacement(plan, () -> true));
    }

    private static void segmentedPlacement(UUID id, NBTTagCompound seed) throws IOException, InterruptedException {
        int count = 650_000;
        NBTTagCompound plan = geometry(seed, count, 30_000_000);
        int[] operations = plan.getIntArray("placementOperations");
        HologramPlacementUpload.Prepared prepared = HologramNetwork.prepareProjectedPlacement(plan, () -> true);
        if (prepared.bytes() <= 100 * Short.MAX_VALUE)
            throw new AssertionError("Large placement did not exercise 100× the old safe payload in aggregate");
        byte[] compressed = HologramSnapshotCodec.compress(plan);
        byte[] checksum = HologramPlacementUpload.digest(compressed);
        HologramPlacementUpload.Fragment first = fragment(id, compressed, checksum, 0);
        ByteBuf wire = Unpooled.buffer();
        try {
            first.toBytes(wire);
            if (wire.readableBytes() >= Short.MAX_VALUE) throw new AssertionError("Placement fragment exceeds C17 size");
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
        expectFailure(() -> new HologramPlacementUpload.Assembly(new HologramPlacementUpload.Fragment(
            id, HologramPlacementUpload.MAX_COMPRESSED + 1, 0, checksum, firstData(compressed))));
        if (assembly.accept(first) != null || corrupt.accept(first) != null)
            throw new AssertionError("A partial upload returned the operation payload");
        HologramPlacementUpload.Fragment duplicate = first;
        expectFailure(() -> assembly.accept(duplicate));
        expectFailure(() -> assembly.accept(fragment(id, compressed, checksum, 2)));
        expectFailure(() -> assembly.accept(fragment(UUID.randomUUID(), compressed, checksum, 1)));
        int chunks = (compressed.length + HologramPlacementUpload.CHUNK_BYTES - 1) / HologramPlacementUpload.CHUNK_BYTES;
        for (int index = 1; index < chunks; index++) {
            HologramPlacementUpload.Fragment frame = fragment(id, compressed, checksum, index);
            byte[] result = assembly.accept(frame);
            if ((result != null) != (index == chunks - 1)) throw new AssertionError("Placement finalized on the wrong fragment");
            if (index == chunks - 1) frame.data[0] ^= 1;
            corrupt.accept(frame);
        }
        expectFailure(corrupt::decode);
        NBTTagCompound restored = reassemble(prepared);
        HologramPlacementRequest.Decoded decoded = HologramPlacementRequest.decode(
            HologramPlacementRequest.header(restored), restored, 256);
        if (decoded.size() != count || decoded.cell(count - 1).x() != count - 1
            || decoded.cell(count - 1).z() != operations[4 * (count - 1) + 2])
            throw new AssertionError("The segmented plan changed its operations");

        UUID falseOwner = UUID.randomUUID();
        HologramPlacementUpload.Assembly mismatched = new HologramPlacementUpload.Assembly(fragment(falseOwner, compressed, checksum, 0));
        for (int index = 0; index < chunks; index++) mismatched.accept(fragment(falseOwner, compressed, checksum, index));
        expectFailure(mismatched::decode);

        NBTTagCompound unrelated = (NBTTagCompound) seed.copy();
        unrelated.setString("action", "checkPlacementMaterials");
        byte[] wrongAction = HologramSnapshotCodec.compress(unrelated);
        HologramPlacementUpload.Fragment wrong = fragment(id, wrongAction, HologramPlacementUpload.digest(wrongAction), 0);
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
        return new HologramPlacementUpload.Fragment(id, encoded.length, index, checksum, Arrays.copyOfRange(encoded, start, end));
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
            0, 0, 0, "gregtech:gt.blockmachines", 0, "gregtech:gt.blockmachines", 12, 3, true, state, "");
        HologramProjectedPlacement.Plan devicePlan = new HologramProjectedPlacement.Plan(
            Collections.singletonList(device),
            Collections.singletonList(new HologramProjectedPlacement.Need(device.item(), device.damage(), 1)));
        NBTTagCompound request = HologramPlacementRequest.encode(id, new int[] { 10, 64, 20 }, devicePlan);
        HologramPlacementRequest.Header header = HologramPlacementRequest.header(request);
        HologramProjectedPlacement.Cell decoded = HologramPlacementRequest.decode(header, request, 256).cell(0);
        if (!decoded.controller() || !payload.equals(HologramBlueprintNbt.decodeData(decoded.tile())))
            throw new AssertionError("Device operation state did not survive the compact payload palette");
        request.getTagList("placementVariants", 10).getCompoundTagAt(0).setByteArray("device", new byte[] { 1 });
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
        HologramPlacementRequest.sourceOptions(check).apply(settings);
        if (settings.me || !settings.main || !settings.containers || settings.priority != 2)
            throw new AssertionError("Explicit material sources did not preserve the held-tool defaults for absent fields");
        HologramProjectedPlacement.Plan build = HologramProjectedPlacement.compileDisplayed(displayed, anchor, 256,
            () -> true);
        if (build.cells().size() != 1 || build.cells().get(0).y() != 1 || build.needs().get(0).count() != 1)
            throw new AssertionError("Controller build ignored the selected layer");
        NBTTagCompound placement = HologramPlacementRequest.encode(id, anchor, build, displayed);
        if (!HologramPlacementRequest.header(placement).sources().equals(HologramPlacementRequest.sourceOptions(check)))
            throw new AssertionError("Placement and standalone check disagree about the selected material sources");
        displayed.setInteger("scope", 0);
        HologramProjectedPlacement.Plan all = HologramProjectedPlacement.compileDisplayed(displayed, anchor, 256,
            () -> true);
        if (all.cells().size() != 2 || all.cells().stream().anyMatch(cell -> cell.x() == 0 && cell.y() == 0 && cell.z() == 0))
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
        HologramProjectedPlacement.Plan concrete = HologramProjectedPlacement.compileDisplayed(
            displayed, anchor, 256, () -> true);
        if (concrete.cells().size() != 1 || concrete.cells().get(0).x() != 1 || concrete.cells().get(0).y() != 1
            || concrete.needs().size() != 1 || concrete.needs().get(0).count() != 1)
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
