package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual ephemeral source ingress/owner callback/result retirement, without creating an author Session. */
final class HologramAuthorReviewTransportRegression {

    private HologramAuthorReviewTransportRegression() {}

    static void run() throws Exception {
        correlation();
        compilePayloadDoesNotDuplicateSource();
        selectedCellQuery();
        EntityPlayerMP player = HologramLibraryPreparationRegression.connectedPlayer("AuthorReviewOwner");
        long baseline = HologramProcessGovernor.retainedBytes();
        Map<String, Long> cacheBaseline = cacheCharges();
        ThreadPoolExecutor io = (ThreadPoolExecutor) get(HologramAuthorReviewService.class, "IO");
        AuthorDocument frozen = source();
        byte[] draft = AuthorDocumentIO.writeDraft(frozen, HologramAuthorReviewProtocol.limits());
        NBTTagCompound body = new NBTTagCompound();
        body.setByteArray("draft", draft);
        body.setTag(
            "policy",
            HologramBlueprintProcessing.defaults("self", 0)
                .write());
        byte[] bytes = HologramAuthorReviewProtocol.encode(body);
        NBTTagCompound proof = proof(frozen, bytes, 101);
        AtomicInteger callbacks = new AtomicInteger();
        Thread owner = Thread.currentThread();
        try (GTMBExtensions.Registration legacy = NativePlacement
            .register("gtmb_test:transport-owner", new NativePlacement.Adapter() {

                public boolean supports(Block block) {
                    SimpleAssert.that(Thread.currentThread() == owner, "review provider never called on transport IO");
                    callbacks.incrementAndGet();
                    return block == Blocks.stone;
                }

                public TileEntity create(Block block, int metadata, NBTTagCompound tag, World world) {
                    return new TileEntity();
                }

                public java.util.List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
                    return Collections.singletonList(new ItemStack(Blocks.stone));
                }
            })) {
            HologramAuthorReviewService.receive(player, proof);
            SimpleAssert
                .that(HologramAuthorReviewService.admitted(player, proof), "authenticated review ticket admitted");
            NBTTagCompound stale = (NBTTagCompound) proof.copy();
            stale.setLong("planRevision", frozen.revision() + 1);
            SimpleAssert.that(
                !HologramAuthorReviewService.admitted(player, stale),
                "changed source revision cannot reuse ticket");
            HologramAuthorReviewService
                .receive(player, HologramAuthorReviewProtocol.packet(stale, "authorReviewCancel"));
            SimpleAssert.that(
                HologramAuthorReviewService.admitted(player, proof),
                "late cancellation cannot retire current source");
            upload(player, proof, bytes);
            barrier(io);
            SimpleAssert.eq(0, callbacks.get(), "source decode worker does not call providers");
            ownerCallbacks();
            barrier(io);
            Object ticket = active().get(player.getUniqueID());
            SimpleAssert
                .that(ticket != null && get(ticket, "error") == null, "read-only review completes without error");
            byte[] output = (byte[]) get(ticket, "output");
            SimpleAssert.that(output != null && output.length > 0, "review returns bounded report bytes");
            SimpleAssert.that(
                HologramAuthorReviewProtocol.decode(output)
                    .hasKey("report", 10),
                "report returned before policy choices");
            SimpleAssert.that(callbacks.get() > 0, "real legacy provider called by owner review");
            SimpleAssert.that(
                HologramWorkspace.find(player.getUniqueID()) == null,
                "review never creates Session or world authority");
            SimpleAssert.that(
                Arrays.equals(draft, AuthorDocumentIO.writeDraft(frozen, HologramAuthorReviewProtocol.limits())),
                "review retains exact source, unknown fields, origins and obligations");
            Map<String, Long> cacheDelta = cacheCharges();
            for (Map.Entry<String, Long> entry : cacheBaseline.entrySet())
                cacheDelta.merge(entry.getKey(), -entry.getValue(), Long::sum);
            cacheDelta.values()
                .removeIf(value -> value == 0L);
            long cacheGrowth = 0;
            for (Map.Entry<String, Long> entry : cacheDelta.entrySet()) {
                SimpleAssert.that(
                    "nbt.exact-cache".equals(entry.getKey()) || "analysis.domain-cache".equals(entry.getKey()),
                    "only identified native analysis caches may change during explicit review: " + cacheDelta);
                cacheGrowth += entry.getValue();
            }
            System.out.println("Author review named cache charge delta: " + cacheDelta);
            long beforeCancel = HologramProcessGovernor.retainedBytes();
            HologramAuthorReviewService
                .receive(player, HologramAuthorReviewProtocol.packet(proof, "authorReviewCancel"));
            long retiredBaseline = HologramProcessGovernor.retainedBytes();
            SimpleAssert.eq(
                beforeCancel - HologramAuthorReviewProtocol.RETAINED_BYTES,
                retiredBaseline,
                "result cancellation releases the exact 128 MiB ticket reservation");
            SimpleAssert.eq(
                0,
                ((AtomicInteger) get(ticket, "references")).get(),
                "result cancellation retires every ticket and worker reference");
            SimpleAssert.eq(
                baseline + cacheGrowth,
                retiredBaseline,
                "remaining process charge is exactly identified native cache ownership, not a review lease");

            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            io.execute(() -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
            });
            SimpleAssert.that(started.await(10, TimeUnit.SECONDS), "fixture worker barrier reached");
            try {
                NBTTagCompound cancelled = proof(frozen, bytes, 102);
                HologramAuthorReviewService.receive(player, cancelled);
                upload(player, cancelled, bytes);
                HologramAuthorReviewService
                    .receive(player, HologramAuthorReviewProtocol.packet(cancelled, "authorReviewCancel"));
                SimpleAssert.that(active().isEmpty(), "cancel retires exact ticket immediately");
                SimpleAssert.that(
                    HologramProcessGovernor.retainedBytes() > retiredBaseline,
                    "queued source bytes keep lease until worker retires");
            } finally {
                release.countDown();
            }
            barrier(io);
            SimpleAssert.eq(
                retiredBaseline,
                HologramProcessGovernor.retainedBytes(),
                "cancelled worker closes retained lease exactly once");
            int calls = callbacks.get();
            ownerCallbacks();
            SimpleAssert.eq(calls, callbacks.get(), "cancelled source never reaches legacy callback");

            NBTTagCompound malformed = proof(frozen, bytes, 103);
            HologramAuthorReviewService.receive(player, malformed);
            NBTTagCompound gap = HologramAuthorReviewProtocol.packet(malformed, "authorReviewChunk");
            gap.setInteger("index", 1);
            gap.setByteArray(
                "data",
                Arrays.copyOfRange(bytes, 0, Math.min(bytes.length, HologramLibraryProtocol.CHUNK_BYTES)));
            HologramAuthorReviewService.receive(player, gap);
            SimpleAssert.that(active().isEmpty(), "out-of-order review upload fails closed");
            SimpleAssert.eq(retiredBaseline, HologramProcessGovernor.retainedBytes(), "malformed upload closes quota");

            NBTTagCompound toolChanged = proof(frozen, bytes, 104);
            HologramAuthorReviewService.receive(player, toolChanged);
            player.inventory.currentItem = 1;
            HologramAuthorReviewService.tick();
            SimpleAssert.that(active().isEmpty(), "tool change retires independent review capability");
            SimpleAssert.eq(retiredBaseline, HologramProcessGovernor.retainedBytes(), "tool retirement closes quota");
        } finally {
            HologramAuthorReviewService.clear();
            barrier(io);
            com.miaokatze.gtmb.util.ServerTaskScheduler.clear();
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    /** Attribute cache residency by the actual transferred lease owner, without clearing or collecting caches. */
    private static Map<String, Long> cacheCharges() throws Exception {
        Map<String, Long> charges = new java.util.TreeMap<>();
        Map<?, ?> owners = (Map<?, ?>) get(HologramRetainedCache.class, "OWNERS");
        Object[] snapshot;
        synchronized (owners) {
            snapshot = owners.values()
                .toArray();
        }
        for (Object owner : snapshot) synchronized (owner) {
            for (Object leaseObject : (java.util.Set<?>) get(owner, "leases")) {
                HologramProcessGovernor.Lease lease = (HologramProcessGovernor.Lease) leaseObject;
                charges.merge(lease.owner(), lease.bytes(), Long::sum);
            }
        }
        return charges;
    }

    private static void compilePayloadDoesNotDuplicateSource() throws Exception {
        java.lang.reflect.Constructor<HologramAuthorServerReview.Result> constructor = HologramAuthorServerReview.Result.class
            .getDeclaredConstructor(NBTTagCompound.class, JsonObject.class, byte[].class);
        constructor.setAccessible(true);
        JsonObject source = new JsonObject();
        source.addProperty("sourceOnlyField", "must-not-be-serialized-twice");
        byte[] compiled = new byte[] { 1, 2, 3 };
        NBTTagCompound result = HologramAuthorReviewService
            .resultPayload(constructor.newInstance(new NBTTagCompound(), source, compiled), null);
        SimpleAssert.that(!result.hasKey("source"), "compiled response omits duplicate source JSON");
        SimpleAssert.that(Arrays.equals(compiled, result.getByteArray("compiled")), "compiled bytes remain exact");
        NBTTagCompound processed = HologramAuthorReviewService
            .resultPayload(constructor.newInstance(new NBTTagCompound(), source, null), null);
        SimpleAssert.that(processed.hasKey("source", 7), "process response still returns its resolved source");
    }

    private static void selectedCellQuery() throws Exception {
        AuthorDocument frozen = source();
        JsonObject expanded = frozen.sourceCopy();
        JsonObject neighbor = frozen.cell(1L)
            .sourceCopy();
        neighbor.addProperty("x", 9);
        neighbor.addProperty("opaqueNeighborPayload", String.join("", Collections.nCopies(10000, "not-selected")));
        expanded.getAsJsonArray("cells")
            .add(neighbor);
        com.google.gson.JsonArray memberships = new com.google.gson.JsonArray();
        memberships.add(new com.google.gson.JsonPrimitive("left"));
        memberships.add(new com.google.gson.JsonPrimitive("right"));
        expanded.getAsJsonArray("cells")
            .get(0)
            .getAsJsonObject()
            .add("structureIds", memberships);
        com.google.gson.JsonArray structures = new com.google.gson.JsonArray();
        for (String id : new String[] { "left", "right", "not-selected" }) {
            JsonObject structure = new JsonObject();
            structure.addProperty("id", id);
            structure.addProperty("enabled", true);
            structure.addProperty("futureStructureField", "opaque:" + id);
            structures.add(structure);
        }
        expanded.add("structures", structures);
        JsonObject opaqueHeader = new JsonObject();
        opaqueHeader.addProperty("unknownNumberLexeme", 3);
        expanded.add("futureInspectorContext", opaqueHeader);
        AuthorDocument large = AuthorDocumentIO.readExpanded(expanded, HologramAuthorReviewProtocol.limits());
        byte[] selected = HologramAuthorReviewProtocol.selectedCellSource(large, 1L);
        AuthorDocument query = AuthorDocumentIO.read(selected, HologramAuthorReviewProtocol.limits());
        SimpleAssert.eq(1, query.cellCount(), "selected inspector query contains exactly selected cell");
        SimpleAssert.that(selected.length < 10000, "neighbor payload is not uploaded for a selected page");
        SimpleAssert.eq(
            large.cell(1L)
                .sourceCopy(),
            query.cell(1L)
                .sourceCopy(),
            "selected raw cell remains exact");
        SimpleAssert
            .eq(large.headerCopy(), query.headerCopy(), "inspector header context retained without neighboring cells");
        SimpleAssert.eq(
            2,
            query.structures()
                .size(),
            "only both referenced shared memberships are included");
        SimpleAssert.eq(
            large.cell(1L)
                .memberships(),
            query.cell(1L)
                .memberships(),
            "shared memberships remain exact");
        for (JsonObject structure : query.structures())
            SimpleAssert.that(structure.has("futureStructureField"), "unknown referenced structure fields retained");
        SimpleAssert.eq(2, large.cellCount(), "building a bounded query never mutates author source");
    }

    private static void correlation() {
        NBTTagCompound proof = new NBTTagCompound();
        proof.setString(
            "transfer",
            UUID.randomUUID()
                .toString());
        proof.setString("documentId", "document");
        proof.setLong("planRevision", 0);
        proof.setLong("clientTaskToken", 9);
        proof.setString("sourceHash", String.join("", Collections.nCopies(64, "a")));
        proof.setString("intent", "inspect");
        SimpleAssert.that(HologramAuthorReviewProtocol.matches(proof, proof), "exact review identity accepted");
        for (String key : new String[] { "transfer", "documentId", "planRevision", "clientTaskToken", "sourceHash",
            "intent" }) {
            NBTTagCompound absent = (NBTTagCompound) proof.copy();
            absent.removeTag(key);
            SimpleAssert
                .that(!HologramAuthorReviewProtocol.matches(proof, absent), "missing review fence rejected: " + key);
        }
    }

    private static AuthorDocument source() throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = "transport-fixture";
        preset.schema = 2;
        preset.name = "review";
        preset.controllerType = "area";
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        cell.block = "minecraft:stone";
        NBTTagCompound tile = new NBTTagCompound();
        tile.setString("id", "extension_fixture");
        tile.setInteger("custom", 7);
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(tile);
        preset.cells.add(cell);
        JsonObject source = new Gson().toJsonTree(preset)
            .getAsJsonObject();
        source.addProperty("futureOpaqueHeader", "retain-me");
        AuthorEditor editor = new AuthorEditor(
            AuthorDocumentIO.readExpanded(source, HologramAuthorReviewProtocol.limits()),
            8);
        editor.select(Collections.singleton(1L));
        editor.move(2, 0, 0);
        return editor.current();
    }

    private static NBTTagCompound proof(AuthorDocument source, byte[] bytes, long task) throws Exception {
        NBTTagCompound proof = HologramLibraryProtocol.request("authorReviewBegin");
        proof.setString(
            "transfer",
            UUID.randomUUID()
                .toString());
        proof.setString("documentId", source.documentId());
        proof.setLong("planRevision", source.revision());
        proof.setLong("clientTaskToken", task);
        proof.setString("sourceHash", HologramBlueprintArtifact.hash(bytes));
        proof.setInteger("total", bytes.length);
        proof.setString("intent", "inspect");
        return proof;
    }

    private static void upload(EntityPlayerMP player, NBTTagCompound proof, byte[] bytes) {
        for (int offset = 0, index = 0; offset < bytes.length; offset += HologramLibraryProtocol.CHUNK_BYTES, index++) {
            int end = Math.min(bytes.length, offset + HologramLibraryProtocol.CHUNK_BYTES);
            NBTTagCompound chunk = HologramAuthorReviewProtocol.packet(proof, "authorReviewChunk");
            chunk.setInteger("index", index);
            chunk.setByteArray("data", Arrays.copyOfRange(bytes, offset, end));
            chunk.setBoolean("last", end == bytes.length);
            HologramAuthorReviewService.receive(player, chunk);
        }
    }

    private static void barrier(ThreadPoolExecutor io) throws Exception {
        io.submit(() -> {})
            .get(10, TimeUnit.SECONDS);
    }

    private static void ownerCallbacks() {
        HologramWorkBudget.startServerTick();
        com.miaokatze.gtmb.util.ServerTaskScheduler.INSTANCE.onServerTick(
            new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
        HologramWorkBudget.endServerTick();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ?> active() throws Exception {
        return (Map<UUID, ?>) get(HologramAuthorReviewService.class, "ACTIVE");
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = (target instanceof Class ? (Class<?>) target : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class ? null : target);
    }
}
