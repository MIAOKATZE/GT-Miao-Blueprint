package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.server.management.UserListOps;
import net.minecraft.server.management.UserListOpsEntry;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import io.netty.buffer.Unpooled;

/** Real Forge configuration, NBT packets, disk publishing and explicit OP-entry permissions. */
public final class HologramLibraryRegression {

    private HologramLibraryRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLibraryRegression.class, args)) return;
        run();
        System.out.println("Hologram dedicated library regressions passed");
    }

    public static void run() throws Exception {
        HologramLibraryPreparationRegression.run();
        publicStorageUpgrade();
        boundedUpgradeReport();
        publicExpandedBudgetBeforePublish();
        contentAdmissionWithoutWorkspace();
        integratedPrivateIngress();
        trustedCacheQuotasAndSavedReceipt();
        activationControlAdmission();
        localExecutionConsent();
        contentIdentity();
        trustedCacheIsolation();
        publicPreviewPrivacy();
        publicThumbnailBounds();
        protocolAndThrottle();
        uploadWindowAndMailboxes();
        uploadAdmissionLifecycle();
        downloads();
        downloadReservations();
        taskCorrelation();
        configuration();
        hydration();
        ownershipAndPublishing();
        directoryLayout();
        brokenStorageFailsClosed();
        permissions();
        streamingDraftProgress();
        cancelledServerPreparationReleasesQueue();
    }

    @SuppressWarnings("unchecked")
    private static void integratedPrivateIngress() throws Exception {
        Field global = MinecraftServer.class.getDeclaredField("mcServer");
        global.setAccessible(true);
        Object previous = global.get(null);
        Field queueField = HologramLibraryFacade.class.getDeclaredField("inbound");
        queueField.setAccessible(true);
        java.util.Queue<Object> queue = (java.util.Queue<Object>) queueField.get(null);
        Field pendingField = HologramLibraryFacade.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        Map<UUID, HologramLibraryProtocol.RequestQuota> pending = (Map<UUID, HologramLibraryProtocol.RequestQuota>) pendingField
            .get(null);
        Field quotaField = HologramLibraryFacade.class.getDeclaredField("inboundQuota");
        quotaField.setAccessible(true);
        HologramLibraryProtocol.RequestQuota globalQuota = (HologramLibraryProtocol.RequestQuota) quotaField.get(null);
        SimpleAssert.that(queue.isEmpty(), "integrated ingress starts with no unrelated queued requests");
        EntityPlayerMP actor = player(UUID.randomUUID(), "IntegratedPrivateOwner");
        try {
            global.set(
                null,
                unsafe().allocateInstance(Class.forName("net.minecraft.server.integrated.IntegratedServer")));
            for (String operation : new String[] { "privateBegin", "contentResolve", "cancel" }) {
                NBTTagCompound request = HologramLibraryProtocol.request(operation);
                request.setLong("clientTaskToken", 919);
                HologramLibraryFacade.receive(actor, request);
                Object accepted = queue.poll();
                SimpleAssert.that(accepted != null, "integrated wire admits private operation " + operation);
                NBTTagCompound copy = (NBTTagCompound) get(accepted, "request");
                SimpleAssert.eq(operation, copy.getString("op"), "queued private request preserves its purpose");
                boolean chunk = (Boolean) get(accepted, "chunk");
                HologramLibraryProtocol.RequestQuota quota = (HologramLibraryProtocol.RequestQuota) get(
                    accepted,
                    "quota");
                quota.release(chunk);
                globalQuota.release(chunk);
                pending.remove(HologramLibraryFacade.actorId(actor), quota);
            }
            SimpleAssert.that(
                HologramLibraryFacade.integratedOperation("privateChunk"),
                "integrated transport permits the private chunk operation type");
            NBTTagCompound unadmitted = HologramLibraryProtocol.request("privateChunk");
            unadmitted.setLong("clientTaskToken", 919);
            unadmitted.setString(
                "transfer",
                UUID.randomUUID()
                    .toString());
            unadmitted.setInteger("index", 0);
            unadmitted.setByteArray("data", new byte[] { 1 });
            HologramLibraryFacade.receive(actor, unadmitted);
            SimpleAssert.that(queue.isEmpty(), "integrated chunks without an admitted transfer lease stay rejected");
            SimpleAssert.that(
                !pending.containsKey(HologramLibraryFacade.actorId(actor)),
                "rejected unadmitted chunks retain no ingress quota");
            for (String operation : new String[] { "uploadBegin", "uploadChunk", "configSet", "delete", "download" }) {
                HologramLibraryFacade.receive(actor, HologramLibraryProtocol.request(operation));
                SimpleAssert.that(queue.isEmpty(), "integrated wire keeps public operation disabled: " + operation);
            }
            SimpleAssert.that(
                !HologramLibraryService.sessionCurrent(actor, HologramLibraryProtocol.request("privateBegin")),
                "admitting private transport does not admit an absent projector session");
        } finally {
            global.set(null, previous);
        }
    }

    private static void publicStorageUpgrade() throws Exception {
        Path root = Files.createTempDirectory("gtmb-public-upgrade-");
        Path entries = root.resolve("entries");
        HologramServerLibrary store = new HologramServerLibrary(entries, root.resolve("library.cfg"));
        HologramServerLibrary reloaded = null;
        boolean storeStopped = false;
        try {
            runWorker(store, () -> {});
            UUID uploader = UUID.randomUUID();
            Object publishingPeer = peer(player(uploader, "OriginalPublisher"));
            transfer(store, publishingPeer, HologramServerLibrary.encodeShare(blueprint()), "", false);
            String id = HologramBlueprintFiles.managedPublic(entries)
                .keySet()
                .iterator()
                .next();
            Path named = HologramBlueprintFiles.resolve(entries, id);
            HologramBlueprintArtifact.Artifact first = HologramBlueprintArtifact.readPair(named);
            com.google.gson.JsonObject legacyRoot = HologramBlueprintCompactCodec.parse(first.json);
            legacyRoot.addProperty("futureMetadata", "preserved independently of the current DTO");
            byte[] legacy = new com.google.gson.GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(legacyRoot)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path old = entries.resolve(id + ".json");
            HologramBlueprintArtifact.writePair(old, legacy, first.png);
            HologramBlueprintFiles.forget(entries, id);
            HologramBlueprintArtifact.deletePair(named);
            store.stop();
            storeStopped = true;
            reloaded = new HologramServerLibrary(entries, root.resolve("library.cfg"));
            HologramServerLibrary active = reloaded;
            runWorker(active, () -> {});
            SimpleAssert
                .that(active.get(id) != null, "legacy public logical filenames remain readable without migration");
            NBTTagCompound upgrade = HologramLibraryProtocol.request("upgradeLibrary");
            upgrade.setLong("clientTaskToken", 811);
            Object ordinary = peer(player(UUID.randomUUID(), "OrdinaryReader"));
            denied(
                () -> runWorker(active, () -> handle(active, ordinary, upgrade)),
                "ordinary readers cannot rewrite public originals");
            SimpleAssert.that(
                Arrays.equals(legacy, Files.readAllBytes(old)),
                "denied public upgrade preserves the original bytes");
            Object administrator = peer(player(UUID.randomUUID(), "UpgradeAdministrator"));
            set(administrator, "admin", true);
            runWorker(active, () -> handle(active, administrator, upgrade));
            Path upgraded = HologramBlueprintFiles.resolve(entries, id);
            SimpleAssert.that(
                !old.equals(upgraded) && Files.isRegularFile(upgraded)
                    && !Files.exists(old)
                    && !Files.exists(HologramBlueprintArtifact.previewPath(old)),
                "accepted public upgrade publishes the new pair/index before retiring the legacy pair");
            HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.readPair(upgraded);
            com.google.gson.JsonObject after = HologramBlueprintCompactCodec.parse(actual.json);
            SimpleAssert.eq(
                uploader.toString(),
                after.get("owner")
                    .getAsString(),
                "administrator upgrades preserve uploader authority");
            SimpleAssert.eq(
                "OriginalPublisher",
                after.get("uploadedBy")
                    .getAsString(),
                "uploader metadata is not replaced by the administrator");
            SimpleAssert.eq(
                "preserved independently of the current DTO",
                after.get("futureMetadata")
                    .getAsString(),
                "recompression preserves unknown envelope fields");
            SimpleAssert.eq(
                HologramBlueprintContentHash.of(legacy),
                HologramBlueprintContentHash.of(actual.json),
                "legacy upgrade is lossless for complete blueprint content");
            HologramServerLibrary.UpgradeReport report = (HologramServerLibrary.UpgradeReport) get(
                administrator,
                "upgradeReport");
            SimpleAssert.eq(1, report.upgraded, "successful indexed public migration is reported precisely");
            NBTTagCompound download = HologramLibraryProtocol.request("download");
            download.setString("id", id);
            download.setLong("clientTaskToken", 812);
            runWorker(active, () -> handle(active, ordinary, download));
            SimpleAssert
                .that(get(ordinary, "download") != null, "full download resolves the logical ID after physical rename");
            runWorker(active, () -> invoke(active, "clearDownload", ordinary));
            active.stop();
            reloaded = null;
            reloaded = new HologramServerLibrary(entries, root.resolve("library.cfg"));
            HologramServerLibrary finalStore = reloaded;
            runWorker(finalStore, () -> {});
            SimpleAssert.that(finalStore.get(id) != null, "new indexed public paths survive a server-library restart");
        } finally {
            if (!storeStopped) store.stop();
            if (reloaded != null) reloaded.stop();
        }
    }

    private static void boundedUpgradeReport() throws Exception {
        java.util.List<NBTTagCompound> rows = new java.util.ArrayList<>();
        for (int index = 0; index < 1024; index++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", "public_" + UUID.randomUUID());
            row.setString("state", "skipped");
            row.setString("reason", repeat('\u4e2d', 512));
            rows.add(row);
        }
        HologramServerLibrary.UpgradeReport report = new HologramServerLibrary.UpgradeReport(813, rows, 0, 0, 1024);
        int count = 0;
        for (int page = 0; page < report.pages; page++) {
            NBTTagCompound packet = report.next();
            int size = packet.getTagList("entries", 10)
                .tagCount();
            SimpleAssert.that(size <= HologramLibraryProtocol.PAGE_SIZE, "upgrade report pages remain bounded");
            SimpleAssert.eq(813L, packet.getLong("clientTaskToken"), "all report pages retain their task identity");
            SimpleAssert.that(
                packet.getBoolean("complete") == (page == report.pages - 1),
                "only the final page settles the task");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            CompressedStreamTools.write(packet, new java.io.DataOutputStream(bytes));
            SimpleAssert.that(
                bytes.size() <= HologramLibraryProtocol.EXPANDED_PACKET_BYTES,
                "maximum diagnostic rows fit the expanded packet budget");
            count += size;
        }
        SimpleAssert.eq(1024, count, "large public catalogs report every entry without a burst mailbox overflow");
    }

    private static void publicExpandedBudgetBeforePublish() throws Exception {
        Path root = Files.createTempDirectory("gtmb-public-dto-budget-");
        Path entries = root.resolve("library");
        HologramServerLibrary store = new HologramServerLibrary(entries, root.resolve("library.cfg"));
        try {
            runWorker(store, () -> {});
            set(store, "publicDtoBytes", HologramServerLibrary.MAX_PUBLIC_DTO_BYTES);
            Object actor = peer(player(UUID.randomUUID(), "BudgetPublisher"));
            byte[] json = HologramServerLibrary.encodeShare(blueprint());
            denied(
                () -> transfer(store, actor, json, "", false),
                "expanded DTO cap rejects before public artifact publication");
            SimpleAssert.eq(0L, (Long) get(store, "storedBytes"), "failed publication consumes no stored bytes");
            SimpleAssert.that(
                HologramBlueprintFiles.managedPublic(entries)
                    .isEmpty(),
                "expanded budget failure writes no public original");
        } finally {
            store.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static void contentAdmissionWithoutWorkspace() throws Exception {
        Path root = Files.createTempDirectory("gtmb-pure-admission-");
        HologramServerLibrary store = new HologramServerLibrary(root.resolve("library"), root.resolve("library.cfg"));
        EntityPlayerMP actor = HologramLibraryPreparationRegression.connectedPlayer("ContentOnlyOwner");
        try {
            runWorker(store, () -> {});
            Object peer = peer(actor);
            UUID owner = HologramLibraryFacade.actorId(actor);
            synchronized (store) {
                ((Map<UUID, Object>) get(store, "peers")).put(owner, peer);
            }
            HologramPresets.Preset source = blueprint();
            byte[] compact = HologramServerLibrary.encodeShare(source);
            String contentHash = HologramBlueprintContentHash.of(compact);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                HologramBlueprintArtifact.WIDTH,
                HologramBlueprintArtifact.HEIGHT,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", png);
            byte[] legacy = new com.google.gson.GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(HologramBlueprintCompactCodec.parse(compact))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] artifact = HologramBlueprintArtifact.encode(legacy, png.toByteArray());
            NBTTagCompound request = HologramLibraryProtocol.request("privateBegin");
            request.setString(
                "transfer",
                UUID.randomUUID()
                    .toString());
            request.setLong("clientTaskToken", 711);
            request.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            request.setInteger("total", artifact.length);
            request.setString("hash", HologramBlueprintArtifact.hash(artifact));
            request.setString("jsonHash", contentHash);
            request.setBoolean("contentOnly", true);
            request.setBoolean("hashProbe", true);
            Object before = HologramWorkspace.find(owner);
            NBTTagCompound probe = HologramLibraryProtocol.request("contentResolve");
            probe.setString("transfer", request.getString("transfer"));
            probe.setLong("clientTaskToken", 711);
            probe.setString("jsonHash", contentHash);
            probe.setString("sourceHash", contentHash);
            store.submit(actor, probe, false);
            runWorker(store, () -> {});
            runWorker(store, () -> {});
            SimpleAssert.that(get(peer, "upload") == null, "hash-only miss allocates no upload or fake header");
            SimpleAssert.eq(0L, get(store, "reservedBytes"), "hash-only miss reserves no artifact bytes");
            SimpleAssert.eq(0, get(store, "activeUploads"), "hash-only miss occupies no upload slot");
            try (java.util.stream.Stream<Path> files = Files.list(root.resolve("library"))) {
                SimpleAssert.that(
                    !files.anyMatch(
                        path -> path.getFileName()
                            .toString()
                            .startsWith("incoming-")),
                    "hash-only miss creates no incoming temporary artifact");
            }
            NBTTagCompound needed = HologramServerLibrary.contentUploadReply(probe, contentHash);
            SimpleAssert.eq("needUpload", needed.getString("op"), "only an explicit probe miss requests bytes");
            SimpleAssert.eq(contentHash, needed.getString("contentHash"), "upload need keeps the probed identity");
            SimpleAssert.eq(probe.getString("transfer"), needed.getString("transfer"), "upload need keeps task fence");
            store.submit(actor, request, false);
            runWorker(store, () -> {});
            runWorker(store, () -> {});
            SimpleAssert.that(get(peer, "upload") != null, "UI content miss admits bounded upload without a workspace");
            for (int offset = 0, index = 0; offset
                < artifact.length; offset += HologramLibraryProtocol.CHUNK_BYTES, index++) {
                NBTTagCompound chunk = HologramLibraryProtocol.request("privateChunk");
                chunk.setString("transfer", request.getString("transfer"));
                chunk.setLong("clientTaskToken", 711);
                chunk.setInteger("index", index);
                chunk.setByteArray(
                    "data",
                    Arrays.copyOfRange(
                        artifact,
                        offset,
                        Math.min(artifact.length, offset + HologramLibraryProtocol.CHUNK_BYTES)));
                runWorker(store, () -> handle(store, peer, chunk));
            }
            HologramTrustedBlueprintCache cache = (HologramTrustedBlueprintCache) get(store, "trusted");
            SimpleAssert.that(
                cache.contains(owner, peer, get(peer, "world"), (Long) get(store, "runtimeEpoch"), contentHash),
                "a fully received canonical original becomes reusable content");
            SimpleAssert.that(
                before == HologramWorkspace.find(owner),
                "content admission never creates or replaces author state");
            store.submit(actor, probe, false);
            runWorker(store, () -> {});
            runWorker(store, () -> {});
            SimpleAssert.that(get(peer, "upload") == null, "a hash-only content hit never requests an artifact");
            SimpleAssert.eq(0L, get(store, "reservedBytes"), "a hash-only hit keeps reservations empty");
            store.submit(actor, request, false);
            runWorker(store, () -> {});
            runWorker(store, () -> {});
            SimpleAssert.that(get(peer, "upload") == null, "pure content hit allocates no upload");
            request.setBoolean("contentOnly", false);
            request.setBoolean("admittedOnly", true);
            request.setString("jsonHash", repeat('a', 64));
            request.removeTag("total");
            request.removeTag("hash");
            runWorker(store, () -> handle(store, peer, request));
            SimpleAssert
                .that(get(peer, "upload") == null, "a final missing reference cannot silently upload in the world");
            NBTTagCompound ready = HologramServerLibrary.contentReply(request, contentHash, true, false);
            SimpleAssert.eq("contentReady", ready.getString("op"), "content readiness has its own result kind");
            SimpleAssert.that(
                !ready.hasKey("session") && !ready.hasKey("planRevision") && !ready.hasKey("placementReviewNonce"),
                "content readiness is not a placement or review capability");
        } finally {
            actor.playerNetServerHandler.netManager.channel()
                .close();
            store.stop();
        }
    }

    private static void trustedCacheQuotasAndSavedReceipt() throws Exception {
        SimpleAssert.eq(10, HologramTrustedBlueprintCache.OWNER_ENTRIES, "owner cache holds at most ten sources");
        SimpleAssert
            .eq(100_000_000L, HologramTrustedBlueprintCache.OWNER_BYTES, "owner content quota uses decimal 100 MB");
        HologramTrustedBlueprintCache cache = new HologramTrustedBlueprintCache();
        UUID owner = UUID.randomUUID();
        Object connection = new Object(), save = new Object();
        HologramPresets.Preset source = blueprint();
        for (int index = 0; index < 4; index++) SimpleAssert.that(
            cache.put(owner, connection, save, 1, "bytes" + index, source, 25_000_000),
            "exact quota portions fit");
        cache.put(owner, connection, save, 1, "extra", source, 1);
        SimpleAssert.that(
            !cache.contains(owner, connection, save, 1, "bytes0"),
            "one byte over the aggregate quota evicts the oldest source");
        SimpleAssert.that(
            !cache.put(owner, connection, save, 1, "oversize", source, 100_000_001),
            "single sources above 100 MB cannot be promised ready");
        cache.clear();
        cache.stage(owner, connection, save, 1, "saved", source, 1024, "receipt", "server-original-sha");
        SimpleAssert.that(
            !cache.contains(owner, connection, save, 1, "saved"),
            "uncommitted saved originals do not count as ready");
        SimpleAssert
            .eq("", cache.commitSaved(connection, "receipt", "forged-sha"), "ACKs cannot substitute another original");
        SimpleAssert.eq(
            "",
            cache.commitSaved(new Object(), "receipt", "server-original-sha"),
            "another connection cannot commit a saved source");
        SimpleAssert.eq(
            "saved",
            cache.commitSaved(connection, "receipt", "server-original-sha"),
            "the issued exact-original receipt fills the cache");
        SimpleAssert.that(
            cache.contains(owner, connection, save, 1, "saved"),
            "committed server-owned source is ready without a new upload");
        cache.stage(owner, connection, save, 1, "cancelled", source, 1024, "cancelled-receipt", "sha2");
        cache.discardPending(connection, "cancelled-receipt");
        SimpleAssert.eq(
            "",
            cache.commitSaved(connection, "cancelled-receipt", "sha2"),
            "an ACK with no retained original cannot manufacture trust");
    }

    private static void activationControlAdmission() throws Exception {
        NBTTagCompound request = new NBTTagCompound();
        request.setIntArray("previewPose", new int[] { 0, 1, 0, 1, 0, 1 });
        SimpleAssert
            .eq(6, HologramLibraryWorkflow.activationControls(request).length, "all six-step cube poses can submit");
        request.setString("previewPose", "0");
        denied(() -> HologramLibraryWorkflow.activationControls(request), "pose must be an integer array");
        request.setIntArray("previewPose", new int[] { 0, 0, 0, 0, 0, 0, 0 });
        denied(() -> HologramLibraryWorkflow.activationControls(request), "oversized pose controls are rejected");
        request.setIntArray("previewPose", new int[] { 2 });
        denied(() -> HologramLibraryWorkflow.activationControls(request), "unknown rotation axes are rejected");
        request.setIntArray("previewPose", new int[0]);
        request.setInteger("side", 6);
        denied(() -> HologramLibraryWorkflow.activationControls(request), "malicious placement sides are rejected");
        request.setInteger("side", 1);
        request.setInteger("placementX", 1);
        denied(() -> HologramLibraryWorkflow.activationControls(request), "partial anchor coordinates are rejected");
        SimpleAssert
            .that(!HologramWorldFlow.worldPosition(0, -1, 0, 256), "negative final world height cannot be placed");
        SimpleAssert
            .that(!HologramWorldFlow.worldPosition(30000000, 64, 0, 256), "out-of-world final anchor cannot be placed");
    }

    private static void localExecutionConsent() throws Exception {
        NBTTagCompound accepted = new NBTTagCompound(), quote = new NBTTagCompound();
        accepted.setInteger("version", 1);
        accepted.setBoolean("complete", true);
        NBTTagList budgets = new NBTTagList(), actual = new NBTTagList();
        NBTTagCompound budget = HologramMaterialQuote.itemRow(new ItemStack(Blocks.stone), 10, 0);
        budget.setString("requiredExact", "10");
        budgets.appendTag(budget);
        NBTTagCompound demand = HologramMaterialQuote.itemRow(new ItemStack(Blocks.stone), 8, 8);
        actual.appendTag(demand);
        accepted.setTag("rows", budgets);
        quote.setTag("rows", actual);
        SimpleAssert.that(
            HologramLibraryExecution.withinAcknowledged(accepted, quote, false),
            "smaller exact authoritative demand fits user consent");
        demand.setLong("required", 11);
        SimpleAssert.that(
            !HologramLibraryExecution.withinAcknowledged(accepted, quote, false),
            "new costs require a fresh review");
        demand.setLong("required", 8);
        demand.setLong("missing", 1);
        SimpleAssert.that(
            !HologramLibraryExecution.withinAcknowledged(accepted, quote, false),
            "missing stock cannot auto-start");
        demand.setLong("missing", 0);
        demand.setBoolean("optionalCache", true);
        SimpleAssert.that(
            !HologramLibraryExecution.withinAcknowledged(accepted, quote, false),
            "unpriced optional cache fees require review");
        demand.setBoolean("optionalCache", false);
        NBTTagCompound privateVariant = new NBTTagCompound();
        privateVariant.setString("owner", "another identity");
        demand.getCompoundTag("item")
            .setTag("tag", privateVariant);
        SimpleAssert.that(
            !HologramLibraryExecution.withinAcknowledged(accepted, quote, false),
            "same item with different NBT is a different payable identity");
        demand.getCompoundTag("item")
            .removeTag("tag");
        budget.setString("requiredExact", repeat('9', 129));
        SimpleAssert.that(
            !HologramLibraryExecution.withinAcknowledged(accepted, quote, false),
            "unbounded decimal quantities fail closed");
        budget.setString("requiredExact", "10");
        accepted.setBoolean("complete", false);
        SimpleAssert.that(
            !HologramLibraryExecution.withinAcknowledged(accepted, quote, false),
            "incomplete local bills cannot authorize automatic execution");
    }

    private static void contentIdentity() throws Exception {
        byte[] original = "{\"schema\":2,\"blueprint\":{\"id\":\"local_a\",\"name\":\"A\",\"retainNbt\":true,\"cells\":[{\"itemNbt\":\"owner=A\",\"x\":1}]}}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] renamed = "{ \"blueprint\": {\"cells\":[{\"x\":1,\"itemNbt\":\"owner=A\"}],\"retainNbt\":true,\"name\":\"B\",\"id\":\"public_b\"},\"schema\":1}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HologramBlueprintContentHash.of(original);
        SimpleAssert.eq(
            hash,
            HologramBlueprintContentHash.of(renamed),
            "file IDs, descriptions, envelopes and formatting do not change content identity");
        SimpleAssert.that(
            !hash.equals(
                HologramBlueprintContentHash.of(
                    new String(original, java.nio.charset.StandardCharsets.UTF_8).replace("owner=A", "owner=B")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            "retained owner payload remains part of content identity");
        SimpleAssert.that(
            !hash.equals(
                HologramBlueprintContentHash.of(
                    new String(original, java.nio.charset.StandardCharsets.UTF_8).replace("true", "false")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            "retention policy remains part of content identity");
    }

    private static void trustedCacheIsolation() throws Exception {
        HologramTrustedBlueprintCache cache = new HologramTrustedBlueprintCache();
        UUID owner = UUID.randomUUID();
        Object connection = new Object(), world = new Object();
        HologramPresets.Preset source = blueprint();
        NBTTagCompound originalOwner = new NBTTagCompound();
        originalOwner.setString("mOwnerUuid", "original-template-owner");
        originalOwner.setString("mOwnerName", "Original");
        source.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(originalOwner);
        cache.put(owner, connection, world, 1, "first", source, 1024);
        source.cells.get(0).x = 99;
        HologramPresets.Preset first = cache.get(owner, connection, world, 1, "first");
        SimpleAssert.eq(0, first.cells.get(0).x, "admission stores a detached base");
        first.cells.get(0).x = 55;
        UUID secondActor = UUID.randomUUID();
        NBTTagCompound actorOne = HologramBlueprintNbt
            .forPlacement(first.cells.get(0).tileNbt, owner, "Builder One", 1, 64, 2, true, true);
        NBTTagCompound actorTwo = HologramBlueprintNbt
            .forPlacement(first.cells.get(0).tileNbt, secondActor, "Builder Two", 3, 70, 4, true, true);
        SimpleAssert
            .eq(owner.toString(), actorOne.getString("mOwnerUuid"), "first placement binds its authenticated actor");
        SimpleAssert.eq(
            secondActor.toString(),
            actorTwo.getString("mOwnerUuid"),
            "shared source binds a different placing actor");
        SimpleAssert.eq("Builder Two", actorTwo.getString("mOwnerName"), "runtime actor name accompanies UUID");
        SimpleAssert.eq(
            "original-template-owner",
            HologramBlueprintNbt.decode(cache.get(owner, connection, world, 1, "first").cells.get(0).tileNbt)
                .getString("mOwnerUuid"),
            "late actor binding leaves trusted source ownership unchanged");
        SimpleAssert.eq(
            0,
            cache.get(owner, connection, world, 1, "first").cells.get(0).x,
            "pose mutations never contaminate the cached base");
        SimpleAssert
            .that(cache.get(UUID.randomUUID(), connection, world, 1, "first") == null, "owners never share trust");
        SimpleAssert.that(cache.get(owner, new Object(), world, 1, "first") == null, "reconnect retires trust");
        SimpleAssert
            .that(cache.get(owner, connection, new Object(), 1, "first") == null, "world identity scopes trust");
        SimpleAssert.that(cache.get(owner, connection, world, 2, "first") == null, "runtime epoch scopes trust");
        for (int index = 0; index < HologramTrustedBlueprintCache.OWNER_ENTRIES; index++)
            cache.put(owner, connection, world, 1, "next" + index, blueprint(), 1024);
        SimpleAssert.that(cache.get(owner, connection, world, 1, "first") == null, "per-owner LRU is bounded");
        cache.put(owner, connection, world, 1, "oversize", blueprint(), 128 * 1024 * 1024);
        SimpleAssert
            .that(cache.get(owner, connection, world, 1, "oversize") == null, "oversized bases are not retained");
        cache.retire(connection);
        SimpleAssert.that(cache.get(owner, connection, world, 1, "next9") == null, "disconnect removes owned entries");
    }

    private static void publicPreviewPrivacy() throws Exception {
        HologramPresets.Preset source = blueprint();
        NBTTagCompound original = new NBTTagCompound();
        original.setString("owner", "private actor");
        original.setString("inventory", "private inventory");
        original.setInteger("texturePage", 4);
        original.setInteger("textureIndex", 12);
        NBTTagList covers = new NBTTagList();
        NBTTagCompound cover = new NBTTagCompound();
        cover.setInteger("id", 3);
        cover.setByte("s", (byte) 1);
        cover.setString("data", "private cover configuration");
        covers.appendTag(cover);
        original.setTag("gt.covers", covers);
        source.cells.get(0).tileNbt = HologramBlueprintNbt.encodeData(original);
        NBTTagCompound projected = HologramPublicPreview.project(source);
        NBTTagCompound display = projected.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("targetDisplay");
        SimpleAssert.eq(12, display.getInteger("textureIndex"), "public preview keeps the reviewed casing appearance");
        SimpleAssert.that(
            !projected.toString()
                .contains("private"),
            "public preview omits actor, inventory and cover configuration");
        SimpleAssert.that(
            !projected.hasKey("authorUuid") && !display.hasKey("nativeTile"),
            "public display cannot carry provenance or generic native persistence");
        SimpleAssert
            .eq("private actor", original.getString("owner"), "public projection preserves its original source");
    }

    private static void publicThumbnailBounds() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            HologramBlueprintArtifact.WIDTH,
            HologramBlueprintArtifact.HEIGHT,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream compactPng = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", compactPng);
        java.awt.image.BufferedImage detailed = javax.imageio.ImageIO.read(
            new java.io.ByteArrayInputStream(HologramServerLibrary.thumbnail(compactPng.toByteArray(), () -> true)));
        SimpleAssert.that(
            detailed.getWidth() == 160 && detailed.getHeight() == 160,
            "compressible source retains the clearer thumbnail within the same packet budget");
        java.util.Random random = new java.util.Random(191120L);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt(0x1000000));
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", encoded);
        denied(
            () -> HologramBlueprintArtifact.bindPreview(new byte[] { 1 }, encoded.toByteArray()),
            "arbitrary binary source bytes cannot masquerade as legacy blueprint JSON");
        byte[] json = "{\"schema\":2,\"blueprint\":{\"name\":\"thumbnail_fixture\",\"cells\":[]}}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] source = HologramBlueprintArtifact.bindPreview(json, encoded.toByteArray());
        byte[] thumbnail = HologramServerLibrary.thumbnail(source, () -> true);
        SimpleAssert.that(
            thumbnail.length <= HologramServerLibrary.THUMBNAIL_BYTES,
            "incompressible source pixels produce a single bounded optional thumbnail");
        java.awt.image.BufferedImage small = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumbnail));
        SimpleAssert
            .that(small.getWidth() == 64 && small.getHeight() == 64, "thumbnail bounds both decoded dimensions");
        SimpleAssert.that(
            !new String(thumbnail, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(HologramBlueprintArtifact.SOURCE_KEY),
            "thumbnail exports only pixels without source digest metadata");
        NBTTagCompound reply = HologramLibraryProtocol.reply("previewThumbnail");
        reply.setLong("clientTaskToken", 991);
        reply.setString("publicId", "public_" + UUID.randomUUID());
        reply.setString("sourceHash", repeat('a', 64));
        reply.setLong("sourceRevision", 1);
        reply.setByteArray("thumbnailPng", thumbnail);
        io.netty.buffer.ByteBuf wire = Unpooled.buffer();
        new HologramNetwork.LibraryReply(reply).toBytes(wire);
        SimpleAssert.that(
            wire.readableBytes() < HologramLibraryProtocol.PACKET_BYTES,
            "thumbnail and identity fit the unchanged library packet budget");
        HologramNetwork.LibraryReply decoded = new HologramNetwork.LibraryReply();
        decoded.fromBytes(wire.duplicate());
        SimpleAssert.that(
            Arrays.equals(thumbnail, ((NBTTagCompound) get(decoded, "tag")).getByteArray("thumbnailPng")),
            "optional thumbnail uses the existing bounded library reply codec");
        byte[] oversizedDimensions = source.clone();
        java.nio.ByteBuffer.wrap(oversizedDimensions)
            .putInt(16, 1000000);
        denied(
            () -> HologramServerLibrary.thumbnail(oversizedDimensions, () -> true),
            "source dimensions are rejected before image allocation");
        denied(
            () -> HologramServerLibrary.thumbnail(new byte[HologramBlueprintArtifact.MAX_PNG_BYTES + 1], () -> true),
            "thumbnail source byte limit is independent of compressed image pixels");
        denied(() -> HologramServerLibrary.thumbnail(source, () -> false), "cancelled thumbnail never begins decode");
        int[] checks = { 0 };
        denied(
            () -> HologramServerLibrary.thumbnail(source, () -> ++checks[0] < 2),
            "retired thumbnail stops before encoding or publishing its decoded pixels");
    }

    private static void streamingDraftProgress() throws Exception {
        HologramPresets.Preset source = blueprint();
        HologramBlueprintVersions.stampSaved(source);
        for (int index = 0; index < 1024; index++) {
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.x = index;
            cell.block = "minecraft:stone";
            source.cells.add(cell);
        }
        long total = (long) source.cells.size() + source.palette.size();
        long[] observed = { -1, 0 };
        byte[] encoded = HologramDraftEncoding.encode(source, (done, count) -> {
            SimpleAssert.eq(total, count, "stream total includes both cell collections without another traversal");
            SimpleAssert.that(done >= observed[0] && done <= count, "actual streamed rows progress monotonically");
            observed[0] = done;
            observed[1]++;
        });
        SimpleAssert.eq(total, observed[0], "all actual streamed rows reach completion");
        SimpleAssert.that(observed[1] >= total, "long exports checkpoint as cells serialize");
        com.google.gson.JsonObject envelope = HologramBlueprintCompactCodec.parse(encoded);
        SimpleAssert.eq(
            2,
            envelope.get("schema")
                .getAsInt(),
            "stream retains native share envelope");
        SimpleAssert.eq(
            source.cells.size(),
            envelope.getAsJsonObject("blueprint")
                .getAsJsonArray("cells")
                .size(),
            "stream does not truncate blueprint content");
        SimpleAssert.eq(
            new com.google.gson.Gson().toJsonTree(source),
            envelope.get("blueprint"),
            "stream retains every field and NBT string of the reviewed blueprint");
        denied(
            () -> HologramDraftEncoding
                .encode(source, (done, count) -> { if (done > 128) throw new IOException("retired preparation"); }),
            "retired export stops at the next serialized cell checkpoint");
    }

    @SuppressWarnings("unchecked")
    private static void cancelledServerPreparationReleasesQueue() throws Exception {
        Path directory = Files.createTempDirectory("gtmb-cancel-preparation-");
        HologramServerLibrary store = new HologramServerLibrary(
            directory.resolve("entries"),
            directory.resolve("library.cfg"));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        EntityPlayerMP player = player(UUID.randomUUID(), "CancelPreparation");
        Object peer = peer(player);
        ((java.util.Map<UUID, Object>) get(store, "peers")).put(HologramLibraryFacade.actorId(player), peer);
        java.util.concurrent.ArrayBlockingQueue<Runnable> queue = (java.util.concurrent.ArrayBlockingQueue<Runnable>) get(
            store,
            "tasks");
        HologramServerLibrary.Preparation active = new HologramServerLibrary.Preparation(81);
        HologramServerLibrary.Preparation queued = new HologramServerLibrary.Preparation(81);
        java.util.concurrent.atomic.AtomicBoolean published = new java.util.concurrent.atomic.AtomicBoolean();
        HologramPresets.Preset source = blueprint();
        HologramBlueprintVersions.stampSaved(source);
        source.prompt = repeat('x', 65536);
        active.action = () -> {
            try {
                HologramDraftEncoding.encode(source, new HologramDraftEncoding.Progress() {

                    int checkpoints;

                    public void update(long done, long total) throws IOException {}

                    public void checkpoint() throws IOException {
                        if (++checkpoints == 3) {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
                            } catch (InterruptedException failure) {
                                Thread.currentThread()
                                    .interrupt();
                                throw new IOException(failure);
                            }
                        }
                        if (!active.valid) throw new java.io.InterruptedIOException("retired");
                    }
                });
                published.set(true);
            } catch (IOException expectedCancellation) {}
        };
        try {
            runWorker(store, () -> {});
            set(peer, "preparation", active);
            queue.add(active);
            SimpleAssert.that(entered.await(5, TimeUnit.SECONDS), "real sole library pump enters draft serialization");
            NBTTagCompound cancel = HologramLibraryProtocol.request("cancel");
            cancel.setLong("clientTaskToken", 81);
            store.submit(player, cancel, false);
            SimpleAssert.that(
                !active.valid && get(peer, "preparation") == null,
                "main-thread cancel revokes active encoding without waiting for its blocked IO pump");
            set(peer, "preparation", queued);
            queue.add(queued);
            store.submit(player, cancel, false);
            SimpleAssert.that(
                !queue.contains(queued) && !queued.valid,
                "same cancellation removes queued draft preparation immediately");
            SimpleAssert.that(
                !store.draftTaskCurrent(player, cancel),
                "late post-encoding callback cannot restore cancelled draft capability");
            NBTTagCompound retry = HologramLibraryProtocol.request("exportDraft");
            retry.setLong("clientTaskToken", 82);
            SimpleAssert
                .that(store.draftTaskCurrent(player, retry), "successor task remains admitted on same connection");
            release.countDown();
            runWorker(store, () -> {});
            SimpleAssert.that(!published.get(), "cancelled active draft never publishes after its checkpoint resumes");
        } finally {
            release.countDown();
            store.stop();
        }
    }

    private static void protocolAndThrottle() throws Exception {
        int slice = HologramLibraryProtocol.CHUNK_BYTES;
        int rate = 128 * 1024;
        String id = UUID.randomUUID()
            .toString();
        HologramLibraryProtocol.header(
            id,
            slice + 1,
            HologramLibraryProtocol.hex(
                HologramLibraryProtocol.digest()
                    .digest(new byte[slice + 1])));
        HologramLibraryProtocol.header(id, HologramBlueprintArtifact.MAX_BYTES, repeat('a', 64));
        denied(() -> HologramLibraryProtocol.header("../../escape", 1, repeat('a', 64)), "path-like transfer rejected");
        denied(
            () -> HologramLibraryProtocol.header(id, HologramLibraryProtocol.MAX_BYTES + 1, repeat('a', 64)),
            "declared size bounded");
        denied(() -> HologramLibraryProtocol.header(id, 1, "bad"), "hash header bounded");
        HologramLibraryProtocol.chunk(slice + 1, 0, 0, 0, slice);
        HologramLibraryProtocol.chunk(slice + 1, slice, 1, 1, 1);
        denied(() -> HologramLibraryProtocol.chunk(slice + 1, 0, 0, 1, slice), "out-of-order chunk rejected");
        denied(() -> HologramLibraryProtocol.chunk(slice + 1, 0, 0, 0, slice + 1), "oversize chunk rejected");
        denied(() -> HologramLibraryProtocol.chunk(slice + 1, 0, 0, 0, 1024), "legacy short chunk rejected");
        denied(() -> HologramLibraryProtocol.chunk(slice + 1, slice, 1, 1, 2), "last length matches remaining bytes");
        HologramLibraryProtocol.Budget budget = new HologramLibraryProtocol.Budget();
        SimpleAssert.that(!budget.take(slice, rate, 1), "bucket starts empty");
        int accepted = 0;
        for (int i = 0; i < 64; i++) if (budget.take(slice, rate, 1000000001L)) accepted++;
        SimpleAssert.eq(2, accepted, "sixty-four players share one bounded 32KiB aggregate burst");
        SimpleAssert.that(budget.take(slice, rate, 1125000001L), "128KiB/s replenishes 16KiB in 125ms");
        SimpleAssert.that(!budget.take(slice, rate, 1125000001L), "same instant cannot consume twice");
        HologramLibraryProtocol.Budget sustained = new HologramLibraryProtocol.Budget();
        sustained.take(slice, rate, 1);
        int transferred = 0;
        for (int tick = 1; tick <= 400; tick++) {
            if (sustained.take(slice, rate, 1L + tick * 50000000L)) transferred += slice;
            SimpleAssert.that(
                transferred <= (long) rate * tick / 20,
                "50ms polling never exceeds the empty-start aggregate allowance");
        }
        SimpleAssert.eq(rate * 20, transferred, "50ms polling preserves the remainder and sustains 128KiB/s");
        HologramLibraryProtocol.Budget explicitSlow = new HologramLibraryProtocol.Budget();
        explicitSlow.take(slice, 16 * 1024, 1);
        SimpleAssert.that(!explicitSlow.take(slice, 16 * 1024, 950000001L), "explicit 16KiB/s is not accelerated");
        SimpleAssert
            .that(explicitSlow.take(slice, 16 * 1024, 1000000001L), "explicit 16KiB/s serves one slice per second");
        HologramLibraryProtocol.FairBudget fair = new HologramLibraryProtocol.FairBudget();
        UUID[] players = { UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() };
        int[] served = new int[players.length];
        for (int tick = 0; tick < 100; tick++) {
            long now = 1L + tick * 50000000L;
            for (int player = 0; player < players.length; player++) {
                // Changing which player polls first cannot lock successful every-other ticks to the same parity.
                int index = (tick + player) % players.length;
                if (fair.take(players[index], slice, rate, now)) served[index]++;
            }
        }
        for (int count : served)
            SimpleAssert.that(count >= 8, "every player is served despite tick/bucket phase alignment");
        HologramLibraryProtocol.FairBudget fifo = new HologramLibraryProtocol.FairBudget();
        for (UUID player : players) SimpleAssert.that(!fifo.take(player, slice, rate, 1), "owners join the empty FIFO");
        SimpleAssert.that(!fifo.take(players[3], slice, rate, 125000001L), "later owner cannot jump the FIFO");
        SimpleAssert.that(fifo.take(players[0], slice, rate, 125000001L), "first waiting UUID receives the allowance");
        SimpleAssert
            .that(!fifo.take(players[2], slice, rate, 250000001L), "poll order cannot displace the second UUID");
        SimpleAssert.that(fifo.take(players[1], slice, rate, 250000001L), "second waiting UUID retains its position");
        fifo.cancel(players[2]);
        SimpleAssert.that(fifo.take(players[3], slice, rate, 375000001L), "cancelled UUID releases its FIFO position");
        denied(
            () -> new HologramNetwork.LibraryRequest().fromBytes(
                Unpooled.buffer()
                    .writeInt(HologramLibraryProtocol.VERSION)
                    .writeInt(HologramLibraryProtocol.PACKET_BYTES + 1)),
            "wire length checked before allocation");
        byte[] random = new byte[slice];
        new java.util.Random(731719L).nextBytes(random);
        NBTTagCompound valid = HologramLibraryProtocol.request("uploadChunk");
        valid.setString("transfer", id);
        valid.setInteger("index", 0);
        valid.setByteArray("data", random);
        io.netty.buffer.ByteBuf requestWire = Unpooled.buffer();
        new HologramNetwork.LibraryRequest(valid).toBytes(requestWire);
        SimpleAssert.eq(3, requestWire.getInt(0), "library requests carry explicit protocol version 3");
        SimpleAssert
            .that(requestWire.readableBytes() + 1 < 32767, "incompressible request plus FML discriminator fits C17");
        HologramNetwork.LibraryRequest decoded = new HologramNetwork.LibraryRequest();
        decoded.fromBytes(requestWire.duplicate());
        SimpleAssert.that(
            Arrays.equals(random, ((NBTTagCompound) get(decoded, "tag")).getByteArray("data")),
            "16KiB request survives bounded NBT decode");
        NBTTagCompound reply = HologramLibraryProtocol.reply("downloadChunk");
        reply.setString("transfer", id);
        reply.setInteger("index", 0);
        reply.setByteArray("data", random);
        io.netty.buffer.ByteBuf replyWire = Unpooled.buffer();
        new HologramNetwork.LibraryReply(reply).toBytes(replyWire);
        HologramNetwork.LibraryReply decodedReply = new HologramNetwork.LibraryReply();
        decodedReply.fromBytes(replyWire.duplicate());
        SimpleAssert.that(
            Arrays.equals(random, ((NBTTagCompound) get(decodedReply, "tag")).getByteArray("data")),
            "16KiB reply survives bounded NBT decode");
        byte[] legacy = CompressedStreamTools.compress(HologramLibraryProtocol.request("configGet"));
        denied(
            () -> new HologramNetwork.LibraryRequest().fromBytes(
                Unpooled.buffer()
                    .writeInt(legacy.length)
                    .writeBytes(legacy)),
            "legacy request without a protocol version is rejected");
        denied(
            () -> new HologramNetwork.LibraryReply().fromBytes(
                Unpooled.buffer()
                    .writeInt(legacy.length)
                    .writeBytes(legacy)),
            "legacy reply without a protocol version is rejected");
        io.netty.buffer.ByteBuf wrongVersion = requestWire.copy();
        wrongVersion.setInt(0, 2);
        denied(
            () -> new HologramNetwork.LibraryRequest().fromBytes(wrongVersion),
            "old request protocol version rejected");
        io.netty.buffer.ByteBuf wrongReplyVersion = replyWire.copy();
        wrongReplyVersion.setInt(0, 2);
        denied(
            () -> new HologramNetwork.LibraryReply().fromBytes(wrongReplyVersion),
            "old reply protocol version rejected");
        NBTTagCompound bad = HologramLibraryProtocol.request("uploadChunk");
        bad.setByteArray("data", new byte[slice + 1]);
        io.netty.buffer.ByteBuf wire = Unpooled.buffer();
        new HologramNetwork.LibraryRequest(bad).toBytes(wire);
        denied(() -> new HologramNetwork.LibraryRequest().fromBytes(wire), "compressed large chunk rejected");
        NBTTagCompound expanded = HologramLibraryProtocol.request("uploadChunk");
        expanded.setByteArray("padding", new byte[HologramLibraryProtocol.EXPANDED_PACKET_BYTES]);
        io.netty.buffer.ByteBuf expandedWire = Unpooled.buffer();
        new HologramNetwork.LibraryRequest(expanded).toBytes(expandedWire);
        denied(
            () -> new HologramNetwork.LibraryRequest().fromBytes(expandedWire),
            "compressed expanded-NBT bomb rejected");
        NBTTagCompound drop = new NBTTagCompound();
        drop.setString("dropItem", "counterfeit");
        NBTTagList inner = new NBTTagList();
        inner.appendTag(drop);
        NBTTagList outer = new NBTTagList();
        outer.appendTag(inner);
        denied(() -> HologramServerLibrary.rejectDropPayload(outer), "dropItem rejected inside list-of-list");
    }

    private static void downloads() throws Exception {
        int slice = HologramLibraryProtocol.CHUNK_BYTES;
        byte[] source = new byte[slice * 6 + 731];
        new java.util.Random(190731L).nextBytes(source);
        // Real whole-file compression can reuse data across packet boundaries.
        System.arraycopy(source, 0, source, slice, slice);
        System.arraycopy(source, 0, source, slice * 3, slice);
        HologramLibraryDownload.Encoded payload = HologramLibraryDownload.encode(source);
        SimpleAssert.eq("gzip", payload.encoding, "whole-file compression saves cross-packet repetitions");
        SimpleAssert.that(payload.bytes.length > slice * 2, "fixture exercises multiple complete wire chunks");
        HologramLibraryDownload.Assembly assembly = new HologramLibraryDownload.Assembly(payload.bytes.length);
        byte[] first = Arrays.copyOfRange(payload.bytes, 0, slice);
        denied(() -> assembly.append(1, first), "download gap/out-of-order chunk rejected");
        assembly.append(0, first);
        denied(() -> assembly.append(0, first), "duplicate download chunk rejected");
        denied(assembly::finish, "premature download done rejected");
        int index = 1;
        for (int offset = slice; offset < payload.bytes.length; offset += slice) assembly
            .append(index++, Arrays.copyOfRange(payload.bytes, offset, Math.min(offset + slice, payload.bytes.length)));
        byte[] wire = assembly.finish();
        byte[] decoded = HologramLibraryDownload.decode(wire, payload.encoding, source.length, payload.hash);
        SimpleAssert.that(Arrays.equals(source, decoded), "compressed multi-chunk download preserves exact source");
        denied(assembly::finish, "completed buffer ownership can only be transferred once");
        Path directory = Files.createTempDirectory("gtmb-download-exact-");
        Path file = directory.resolve("download.json");
        try {
            HologramBlueprintSharing.atomicWrite(file, decoded);
            SimpleAssert.that(Arrays.equals(source, Files.readAllBytes(file)), "downloaded file is byte-identical");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
        denied(
            () -> HologramLibraryDownload.decode(wire, "gzip", source.length, repeat('0', 64)),
            "final original SHA mismatch rejected");
        denied(
            () -> HologramLibraryDownload.decode(wire, "gzip", source.length - 1, payload.hash),
            "decoded expansion past declared bound rejected");
        denied(
            () -> HologramLibraryDownload.decode(wire, "gzip", source.length + 1, payload.hash),
            "truncated decoded size rejected");
        byte[] broken = wire.clone();
        broken[broken.length - 5] ^= 1;
        denied(
            () -> HologramLibraryDownload.decode(broken, "gzip", source.length, payload.hash),
            "corrupt gzip trailer rejected");
        denied(
            () -> HologramLibraryDownload
                .decode(Arrays.copyOf(wire, wire.length - 4), "gzip", source.length, payload.hash),
            "truncated gzip rejected");
        String transfer = UUID.randomUUID()
            .toString();
        denied(
            () -> HologramLibraryDownload
                .header(transfer, wire.length, "gzip", HologramLibraryProtocol.MAX_BYTES + 1, payload.hash),
            "decoded allocation bounded before decompression");
        denied(
            () -> HologramLibraryDownload.header(transfer, wire.length, "unknown", source.length, payload.hash),
            "unknown transfer encoding rejected");
        HologramLibraryDownload.Assembly cancelled = new HologramLibraryDownload.Assembly(slice + 1);
        cancelled.append(0, new byte[slice]);
        cancelled.cancel();
        denied(() -> cancelled.append(1, new byte[1]), "cancelled transfer cannot receive later bytes");
        denied(cancelled::finish, "cancelled transfer cannot finish");
        byte[] random = new byte[slice * 2 + 1];
        new java.util.Random(1984L).nextBytes(random);
        HologramLibraryDownload.Encoded identity = HologramLibraryDownload.encode(random);
        SimpleAssert.eq("identity", identity.encoding, "incompressible downloads avoid expansion");
        SimpleAssert.that(
            Arrays.equals(
                random,
                HologramLibraryDownload.decode(identity.bytes, identity.encoding, random.length, identity.hash)),
            "identity fallback preserves bytes and final SHA");
        byte[] maximum = new byte[HologramBlueprintArtifact.MAX_BYTES];
        HologramLibraryDownload.Encoded full = HologramLibraryDownload.encode(maximum);
        SimpleAssert.that(
            Arrays
                .equals(maximum, HologramLibraryDownload.decode(full.bytes, full.encoding, maximum.length, full.hash)),
            "maximum JSON plus PNG artifact survives bounded whole-file decode and final SHA");
    }

    private static void downloadReservations() throws Exception {
        Path directory = Files.createTempDirectory("gtmb-download-reservation-");
        HologramServerLibrary store = new HologramServerLibrary(
            directory.resolve("entries"),
            directory.resolve("library.cfg"));
        try {
            runWorker(store, () -> {});
            Object[] peers = new Object[5];
            for (int i = 0; i < peers.length; i++) peers[i] = peer(player(UUID.randomUUID(), "Download" + i));
            byte[] large = new byte[HologramBlueprintArtifact.MAX_BYTES];
            runWorker(store, () -> {
                queueDownload(store, peers[0], large);
                queueDownload(store, peers[1], large);
            });
            SimpleAssert.eq(
                2L * HologramBlueprintArtifact.MAX_BYTES,
                get(store, "downloadBytes"),
                "two maximum artifacts reserve their full original byte footprint");
            denied(
                () -> runWorker(store, () -> queueDownload(store, peers[2], large)),
                "compression cannot bypass the aggregate original-byte reservation");
            runWorker(store, () -> {
                invoke(store, "clearDownload", peers[0]);
                invoke(store, "clearDownload", peers[1]);
            });
            SimpleAssert.eq(0L, get(store, "downloadBytes"), "cancellation releases original-byte reservation");
            byte[] small = new byte[1024 * 1024];
            runWorker(store, () -> { for (int i = 0; i < 4; i++) queueDownload(store, peers[i], small); });
            denied(
                () -> runWorker(store, () -> queueDownload(store, peers[4], small)),
                "compressed downloads retain the four-active-transfer bound");
            runWorker(store, () -> { for (int i = 0; i < 4; i++) invoke(store, "clearDownload", peers[i]); });
            SimpleAssert.eq(0, get(store, "activeDownloads"), "cancelled transfers release active admission");
            runWorker(store, () -> queueDownload(store, peers[0], small, 92L));
            NBTTagCompound staleCancel = HologramLibraryProtocol.request("cancel");
            staleCancel.setLong("clientTaskToken", 91L);
            runWorker(store, () -> handle(store, peers[0], staleCancel));
            SimpleAssert.that(
                get(peers[0], "download") != null,
                "late empty-transfer cancel cannot abort a successor's real server download");
            staleCancel.setLong("clientTaskToken", 92L);
            runWorker(store, () -> handle(store, peers[0], staleCancel));
            SimpleAssert.that(get(peers[0], "download") == null, "matching task cancel stops its server download");
            SimpleAssert.eq(0L, get(store, "downloadBytes"), "matching task cancel releases original-byte allowance");
            NBTTagCompound begin = HologramLibraryProtocol.request("uploadBegin");
            begin.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            begin.setInteger("total", HologramBlueprintArtifact.MAX_BYTES);
            begin.setString(
                "transfer",
                UUID.randomUUID()
                    .toString());
            begin.setString("hash", repeat('a', 64));
            runWorker(store, () -> {
                handle(store, peers[0], begin);
                handle(store, peers[1], begin);
            });
            SimpleAssert.eq(
                2L * HologramBlueprintArtifact.MAX_BYTES,
                get(store, "reservedBytes"),
                "two maximum upload artifacts are admitted with bounded reservation");
            denied(
                () -> runWorker(store, () -> handle(store, peers[2], begin)),
                "third maximum upload cannot exceed the aggregate reservation");
            runWorker(store, () -> {
                invoke(store, "abort", peers[0]);
                invoke(store, "abort", peers[1]);
            });
            SimpleAssert.eq(0L, get(store, "reservedBytes"), "cancel releases maximum upload reservations");
            SimpleAssert.eq(0, get(store, "activeUploads"), "cancel releases maximum upload admission slots");
        } finally {
            store.stop();
        }
    }

    private static void queueDownload(HologramServerLibrary store, Object peer, byte[] bytes) throws Exception {
        queueDownload(store, peer, bytes, 0L);
    }

    private static void queueDownload(HologramServerLibrary store, Object peer, byte[] bytes, long taskToken)
        throws Exception {
        Method method = HologramServerLibrary.class.getDeclaredMethod(
            "queueDownload",
            peer.getClass(),
            byte[].class,
            String.class,
            NBTTagCompound.class,
            String.class);
        method.setAccessible(true);
        try {
            NBTTagCompound request = HologramLibraryProtocol.request("download");
            request.setLong("clientTaskToken", taskToken);
            method.invoke(store, peer, bytes, "Bounded fixture", request, "");
        } catch (InvocationTargetException failure) {
            throw (Exception) failure.getCause();
        }
    }

    private static void taskCorrelation() throws Exception {
        NBTTagCompound oldHead = HologramLibraryProtocol.reply("downloadBegin");
        oldHead.setString("session", "same-session");
        oldHead.setLong("planRevision", 42);
        oldHead.setLong("clientTaskToken", 91);
        SimpleAssert.that(HologramLibraryDownload.taskMatches(91, oldHead), "current download head is accepted");
        SimpleAssert
            .that(!HologramLibraryDownload.taskMatches(0, oldHead), "cancel rejects a late head before allocation");
        NBTTagCompound newHead = (NBTTagCompound) oldHead.copy();
        newHead.setLong("clientTaskToken", 92);
        SimpleAssert.that(
            !HologramLibraryDownload.taskMatches(92, oldHead),
            "immediate successor in the same session/revision rejects the cancelled task head");
        SimpleAssert.that(HologramLibraryDownload.taskMatches(92, newHead), "successor accepts its own exact head");
        for (String op : new String[] { "error", "downloadDone", "saveCommitted", "saveRejected" }) {
            NBTTagCompound oldReply = (NBTTagCompound) oldHead.copy();
            oldReply.setString("op", op);
            SimpleAssert.that(
                !HologramLibraryDownload.taskMatches(92, oldReply),
                "cancelled task " + op + " cannot occupy or finish its successor");
        }
        HologramSession session = (HologramSession) unsafe().allocateInstance(HologramSession.class);
        NBTTagCompound save = HologramLibraryProtocol.request("saveBlueprintAs");
        save.setLong("clientTaskToken", 91);
        HologramLibraryWorkflow.captureClientTaskToken(session, save);
        SimpleAssert.eq(91L, session.blueprintClientTaskToken, "initial save captures its correlation token");
        NBTTagCompound lateConfirm = HologramLibraryProtocol.request("confirmAeSave");
        lateConfirm.setLong("clientTaskToken", 90);
        HologramLibraryWorkflow.captureClientTaskToken(session, lateConfirm);
        SimpleAssert
            .eq(91L, session.blueprintClientTaskToken, "late AE confirmation cannot replace the reviewed task token");
        save.setLong("clientTaskToken", 92);
        HologramLibraryWorkflow.captureClientTaskToken(session, save);
        SimpleAssert.eq(92L, session.blueprintClientTaskToken, "new save replaces the old task correlation");
    }

    private static NBTTagCompound uploadReply(String transfer, long task, int next) {
        NBTTagCompound reply = HologramLibraryProtocol.reply("chunkAck");
        reply.setString("transfer", transfer);
        reply.setLong("clientTaskToken", task);
        reply.setInteger("index", next - 1);
        reply.setInteger("nextIndex", next);
        return reply;
    }

    private static void uploadWindowAndMailboxes() throws Exception {
        int slice = HologramLibraryProtocol.CHUNK_BYTES;
        String transfer = UUID.randomUUID()
            .toString();
        NBTTagCompound ready = uploadReply(transfer, 71, 0);
        HologramLibraryProtocol.UploadWindow legacy = new HologramLibraryProtocol.UploadWindow(transfer, 71, slice + 1);
        SimpleAssert.that(legacy.ready(ready), "old server ready without optional credit is accepted");
        legacy.sent(0);
        SimpleAssert.that(!legacy.canSend(), "old server retains exactly one outstanding chunk");
        SimpleAssert
            .that(legacy.acknowledge(uploadReply(transfer, 71, 1)), "old server individual ACK opens one credit");
        legacy.sent(1);
        SimpleAssert.that(legacy.complete(uploadReply(transfer, 71, 2)), "final receipt needs no last chunk ACK");
        NBTTagCompound noTransfer = uploadReply(transfer, 71, 2);
        noTransfer.removeTag("transfer");
        SimpleAssert.that(!legacy.complete(noTransfer), "missing transfer cannot complete an otherwise matching task");

        NBTTagCompound badReady = uploadReply(transfer, 71, 0);
        badReady.setString("uploadWindow", "8");
        denied(
            () -> new HologramLibraryProtocol.UploadWindow(transfer, 71, slice).ready(badReady),
            "ready credit must be an NBT Int");
        badReady.setInteger("uploadWindow", 9);
        denied(
            () -> new HologramLibraryProtocol.UploadWindow(transfer, 71, slice).ready(badReady),
            "ready credit cannot exceed the hard window");
        badReady.setInteger("uploadWindow", 8);
        badReady.setInteger("nextIndex", 1);
        denied(
            () -> new HologramLibraryProtocol.UploadWindow(transfer, 71, slice).ready(badReady),
            "partial resume is not silently introduced");
        NBTTagCompound previewBegin = HologramLibraryProtocol.request("previewBegin");
        previewBegin.setString("transfer", transfer);
        previewBegin.setInteger("total", slice);
        previewBegin.setLong("clientTaskToken", 71);
        previewBegin.setLong("clientTaskToken", 92); // Actual previewBegin restores the original save task token.
        HologramLibraryProtocol.UploadWindow preview = new HologramLibraryProtocol.UploadWindow(previewBegin);
        SimpleAssert
            .eq(92L, preview.task(), "sender binds the actual Begin override rather than ambient background state");
        SimpleAssert
            .that(!preview.ready(uploadReply(transfer, 71, 0)), "ambient other task cannot start preview sender");
        SimpleAssert.that(preview.ready(uploadReply(transfer, 92, 0)), "original preview task accepts matching ready");
        preview.sent(0);
        SimpleAssert.that(!preview.complete(uploadReply(transfer, 71, 1)), "old-task final cannot complete preview");
        SimpleAssert
            .that(preview.complete(uploadReply(transfer, 92, 1)), "preview final binds the original task and transfer");

        ready.setInteger("uploadWindow", 8);
        HologramLibraryProtocol.UploadWindow sender = new HologramLibraryProtocol.UploadWindow(
            transfer,
            71,
            20 * slice);
        SimpleAssert.that(sender.ready(ready), "new server advertises bounded credit without a protocol bump");
        while (sender.canSend()) sender.sent(sender.nextSent());
        SimpleAssert.eq(8, sender.nextSent(), "one tick fills exactly eight credits");
        boolean sendRejected = false;
        try {
            sender.sent(9);
        } catch (IllegalStateException expected) {
            sendRejected = true;
        }
        SimpleAssert
            .that(sendRejected && sender.nextSent() == 8, "out-of-order send cannot change the actual sender cursor");
        SimpleAssert.that(!sender.ready(ready), "duplicate ready cannot rewind a live transfer");
        SimpleAssert.that(!sender.acknowledge(uploadReply(transfer, 72, 4)), "other task ACK cannot release credit");
        SimpleAssert.that(
            !sender.acknowledge(
                uploadReply(
                    UUID.randomUUID()
                        .toString(),
                    71,
                    4)),
            "other transfer ACK cannot release credit");
        SimpleAssert.eq(0, sender.acknowledged(), "cross-scope ACKs leave the acknowledgement cursor unchanged");
        SimpleAssert.that(!sender.canSend(), "cross-scope ACKs do not grant send credit");
        denied(() -> sender.acknowledge(uploadReply(transfer, 71, 9)), "future ACK cannot confirm unsent data");
        NBTTagCompound malformed = uploadReply(transfer, 71, 4);
        malformed.setInteger("index", 1);
        denied(() -> sender.acknowledge(malformed), "ACK index must agree with cumulative nextIndex");
        SimpleAssert.that(sender.acknowledge(uploadReply(transfer, 71, 4)), "cumulative ACK releases four credits");
        SimpleAssert
            .that(!sender.acknowledge(uploadReply(transfer, 71, 4)), "duplicate ACK grants no additional credit");
        SimpleAssert.that(!sender.acknowledge(uploadReply(transfer, 71, 2)), "late ACK never moves backward");
        while (sender.canSend()) sender.sent(sender.nextSent());
        SimpleAssert.eq(12, sender.nextSent(), "only newly acknowledged credit is replenished");
        denied(() -> sender.complete(uploadReply(transfer, 71, 20)), "completion cannot skip unsent chunks");
        HologramLibraryProtocol.UploadWindow oldClient = new HologramLibraryProtocol.UploadWindow(
            transfer,
            71,
            slice + 1);
        SimpleAssert.that(oldClient.ready(ready), "new server ready is readable by an individual sender");
        oldClient.sent(0);
        SimpleAssert.that(
            oldClient.acknowledge(uploadReply(transfer, 71, 1)),
            "new server ACK remains valid after just one received chunk");
        oldClient.sent(1);
        SimpleAssert.that(
            oldClient.complete(uploadReply(transfer, 71, 2)),
            "individual sender still receives final completion");

        HologramLibraryProtocol.RequestQuota quota = new HologramLibraryProtocol.RequestQuota();
        SimpleAssert.that(!quota.acquire(true, false), "no data admitted before an actual ready transfer");
        SimpleAssert.eq(0, quota.chunks, "rejected admission consumes no data quota");
        for (int i = 0; i < 8; i++) SimpleAssert.that(quota.acquire(true, true), "admitted data fits its fixed window");
        SimpleAssert.that(!quota.acquire(true, true), "ninth data request is rejected");
        for (int i = 0; i < 4; i++)
            SimpleAssert.that(quota.acquire(false, false), "data cannot consume control reserve");
        SimpleAssert
            .that(!quota.acquire(false, false), "fifth control cannot grow the previous control attack surface");
        for (int i = 0; i < 8; i++) quota.release(true);
        for (int i = 0; i < 4; i++) quota.release(false);
        SimpleAssert.that(quota.empty(), "balanced completion releases both independent scopes");
        denied(() -> quota.release(true), "release underflow is detected");
        SimpleAssert.that(quota.empty(), "underflow rejection does not mutate either quota scope");
        quota.clear();
        HologramLibraryProtocol.RequestQuota global = new HologramLibraryProtocol.RequestQuota(32, 64, 64);
        for (int i = 0; i < 32; i++)
            SimpleAssert.that(global.acquire(true, true), "four active windows fit global data allowance");
        SimpleAssert.that(!global.acquire(true, true), "global data cannot fill the reserved control half");
        SimpleAssert.eq(32, global.chunks, "failed global acquire does not change the scope counter");
        for (int i = 0; i < 32; i++)
            SimpleAssert.that(global.acquire(false, false), "global data reserves half the mailbox for controls");
        SimpleAssert.that(!global.acquire(false, false), "global total remains exactly 64 queued requests");

        HologramLibraryProtocol.RequestQueue queue = new HologramLibraryProtocol.RequestQueue();
        NBTTagCompound data = uploadReply(transfer, 71, 1);
        data.setString("op", "privateChunk");
        SimpleAssert.that(!queue.offer(data, false), "worker mailbox also requires admitted data");
        for (int i = 0; i < 8; i++) SimpleAssert.that(queue.offer(data, true), "worker data window is bounded");
        SimpleAssert.that(!queue.offer(data, true), "worker refuses a ninth data chunk");
        NBTTagCompound cancel = HologramLibraryProtocol.request("cancel");
        SimpleAssert.that(queue.offer(cancel, false), "cancel fits even while data credits are full");
        SimpleAssert.that(queue.pollControl() != null, "low-rate data cannot head-of-line block cancel");
        queue.discard(transfer, 72);
        SimpleAssert.that(queue.peekChunk() != null, "other task revocation leaves this task's data alone");
        queue.discard(transfer, 71);
        SimpleAssert.that(queue.isEmpty(), "revocation purges matching queued data");

        int rate = 1024 * 1024, delivered = 0;
        HologramLibraryProtocol.Budget upload = new HologramLibraryProtocol.Budget(true);
        upload.take(slice, rate, 1);
        for (int tick = 1; tick <= 20; tick++) {
            for (int pass = 0; pass < 4; pass++)
                if (upload.take(slice, rate, 1L + tick * 50000000L)) delivered += slice;
            SimpleAssert
                .that(delivered <= (long) rate * tick / 20, "bounded drain never exceeds administrator aggregate rate");
        }
        SimpleAssert.eq(rate, delivered, "four rounds plus upload burst can sustain 1 MiB/s at 50ms polls");
        HologramLibraryProtocol.Budget low = new HologramLibraryProtocol.Budget(true);
        low.take(slice, 128 * 1024, 1);
        int burst = 0;
        for (int i = 0; i < 8; i++) if (low.take(slice, 128 * 1024, 1000000001L)) burst++;
        SimpleAssert.eq(2, burst, "existing 128 KiB upload retains the original two-chunk burst");
        HologramLibraryProtocol.Budget download = new HologramLibraryProtocol.Budget();
        download.take(slice, rate, 1);
        burst = 0;
        for (int i = 0; i < 8; i++) if (download.take(slice, rate, 50000001L)) burst++;
        SimpleAssert.eq(3, burst, "download drain admits only elapsed byte credit at a high administrator setting");
        HologramLibraryProtocol.FairBudget fair = new HologramLibraryProtocol.FairBudget(true);
        UUID[] owners = { UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() };
        int[] served = new int[4];
        delivered = 0;
        for (int tick = 0; tick <= 200; tick++) {
            long now = 1L + tick * 50000000L;
            for (int pass = 0; pass < 4; pass++) for (int n = 0; n < 4; n++) {
                int owner = (tick + n) % 4;
                if (fair.take(owners[owner], slice, rate, now)) {
                    served[owner]++;
                    delivered += slice;
                }
            }
            SimpleAssert
                .that(delivered <= (long) rate * tick / 20, "multiple owners share one global upload allowance");
        }
        for (int count : served) SimpleAssert.that(count >= 120, "bounded rounds cannot starve any active owner");
    }

    @SuppressWarnings("unchecked")
    private static void uploadAdmissionLifecycle() throws Exception {
        Path root = Files.createTempDirectory("gtmb-upload-window-");
        HologramServerLibrary store = new HologramServerLibrary(root.resolve("library"), root.resolve("library.cfg"));
        try {
            runWorker(store, () -> {});
            EntityPlayerMP actor = player(UUID.randomUUID(), "WindowPublisher");
            Object peer = peer(actor);
            synchronized (store) {
                ((java.util.Map<UUID, Object>) get(store, "peers")).put(HologramLibraryFacade.actorId(actor), peer);
            }
            runWorker(store, () -> {
                String transfer = UUID.randomUUID()
                    .toString();
                NBTTagCompound begin = HologramLibraryProtocol.request("uploadBegin");
                begin.setString("transfer", transfer);
                begin.setLong("clientTaskToken", 31);
                begin.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
                begin.setInteger("total", 12 * HologramLibraryProtocol.CHUNK_BYTES + 1);
                begin.setString("hash", repeat('a', 64));
                handle(store, peer, begin);
                NBTTagCompound chunk = uploadReply(transfer, 31, 1);
                chunk.setString("op", "uploadChunk");
                chunk.setByteArray("data", new byte[HologramLibraryProtocol.CHUNK_BYTES]);
                SimpleAssert.that(store.uploadAdmitted(actor, chunk), "ready publishes actual owner-bound admission");
                chunk.setLong("clientTaskToken", 30);
                SimpleAssert
                    .that(!store.uploadAdmitted(actor, chunk), "old task cannot use the current transfer admission");
                chunk.setLong("clientTaskToken", 31);
                chunk.setString("op", "privateChunk");
                SimpleAssert.that(!store.uploadAdmitted(actor, chunk), "different upload purpose cannot use admission");
                chunk.setString("op", "uploadChunk");
                handle(store, peer, chunk);
                Object partial = get(peer, "upload");
                SimpleAssert.that(
                    (Boolean) get(partial, "ackPending"),
                    "one received old-client chunk schedules an ACK without waiting for a batch");
                invoke(store, "flushUploadAck", peer);
                SimpleAssert
                    .that(!(Boolean) get(partial, "ackPending"), "pump flush publishes old-client individual ACK");
                chunk.setInteger("index", 1);
                handle(store, peer, chunk);
                chunk.setInteger("index", 2);
                handle(store, peer, chunk);
                SimpleAssert.eq(
                    3,
                    (Integer) get(partial, "index"),
                    "each contiguous received chunk advances native upload index once");
                SimpleAssert.that(
                    (Boolean) get(partial, "ackPending"),
                    "multiple data chunks retain one pending cumulative ACK");
                invoke(store, "flushUploadAck", peer);
                SimpleAssert.that(
                    !(Boolean) get(partial, "ackPending"),
                    "one flush consumes the cumulative ACK without changing transfer data");
                for (int i = 0; i < 8; i++) {
                    chunk.setInteger("index", i + 3);
                    store.submit(actor, chunk, false);
                }
                HologramLibraryProtocol.RequestQueue mailbox = (HologramLibraryProtocol.RequestQueue) get(
                    peer,
                    "requests");
                SimpleAssert
                    .that(!mailbox.offer(chunk, true), "real peer data mailbox is full after eight submissions");
                NBTTagCompound cancel = HologramLibraryProtocol.request("cancel");
                cancel.setString("transfer", transfer);
                cancel.setLong("clientTaskToken", 31);
                store.submit(actor, cancel, false);
                NBTTagCompound queuedControl = mailbox.pollControl();
                SimpleAssert.that(queuedControl != null, "real full data mailbox still accepts cancel");
                handle(store, peer, queuedControl);
                SimpleAssert.that(
                    get(peer, "upload") == null && mailbox.isEmpty(),
                    "actual cancel atomically revokes and purges data");
                SimpleAssert.that(!store.uploadAdmitted(actor, chunk), "cancelled transfer has no further admission");
                String replacement = UUID.randomUUID()
                    .toString();
                begin.setString("transfer", replacement);
                begin.setLong("clientTaskToken", 32);
                handle(store, peer, begin);
                handle(store, peer, cancel);
                chunk.setString("transfer", replacement);
                chunk.setLong("clientTaskToken", 32);
                SimpleAssert
                    .that(store.uploadAdmitted(actor, chunk), "late old cancellation cannot revoke replacement task");
                Object current = get(peer, "upload");
                set(current, "lastActivity", System.nanoTime() - HologramLibraryProtocol.TIMEOUT_NANOS - 1);
                Method pump = HologramServerLibrary.class.getDeclaredMethod("pump");
                pump.setAccessible(true);
                pump.invoke(store);
                SimpleAssert.that(
                    get(peer, "upload") == null && !store.uploadAdmitted(actor, chunk),
                    "actual timeout revokes admission");
                SimpleAssert.eq(
                    0,
                    ((Number) get(store, "reservedBytes")).intValue(),
                    "cancel and timeout release every reserved byte");
                SimpleAssert
                    .eq(0, (Integer) get(store, "activeUploads"), "cancel and timeout release every active slot");
                handle(store, peer, begin);
                set(peer, "online", false);
                SimpleAssert
                    .that(!store.uploadAdmitted(actor, chunk), "offline identity immediately loses upload admission");
                pump.invoke(store);
                SimpleAssert.that(get(peer, "upload") == null, "offline pump cleans its active transfer");
            });
        } finally {
            store.stop();
        }
    }

    private static void configuration() throws Exception {
        Path file = Files.createTempDirectory("gtmb-library-config-")
            .resolve("library.cfg");
        HologramLibraryConfig config = new HologramLibraryConfig(file.toFile());
        SimpleAssert.eq(128, config.view().uploadKiB, "fresh default aggregate upload is 128 KiB/s");
        SimpleAssert.eq(512, config.view().downloadKiB, "fresh default aggregate download is 512 KiB/s");
        SimpleAssert.eq(10, config.view().maxUploads, "default UUID quota");
        NBTTagCompound defaults = config.view()
            .tag(true);
        SimpleAssert.eq(128, defaults.getInteger("uploadKiB"), "administrator UI receives new upload default");
        SimpleAssert.eq(512, defaults.getInteger("downloadKiB"), "administrator UI receives download default");
        HologramLibraryConfig freshReopened = new HologramLibraryConfig(file.toFile());
        SimpleAssert.eq(128, freshReopened.view().uploadKiB, "new upload default persists in actual Forge file");
        SimpleAssert.eq(512, freshReopened.view().downloadKiB, "new download default persists");
        Path previousDefaultFile = file.resolveSibling("existing-128.cfg");
        Files.write(
            previousDefaultFile,
            ("serverblueprintlibrary {\n I:uploadKiBPerSecond=128\n I:downloadKiBPerSecond=128\n I:maxUploadsPerPlayer=7\n}\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HologramLibraryConfig previousDefault = new HologramLibraryConfig(previousDefaultFile.toFile());
        SimpleAssert
            .eq(128, previousDefault.view().uploadKiB, "existing 128 is not assumed to be an untouched default");
        HologramLibraryConfig previousReopened = new HologramLibraryConfig(previousDefaultFile.toFile());
        SimpleAssert.eq(128, previousReopened.view().uploadKiB, "existing 128 remains unchanged after reopen");
        SimpleAssert.eq(128, previousReopened.view().downloadKiB, "existing download remains unchanged after reopen");
        SimpleAssert.eq(7, previousReopened.view().maxUploads, "existing quota remains unchanged after reopen");
        previousDefault.update(true, previousDefault.view().revision, 1024, 128, 7);
        HologramLibraryConfig administratorUpdated = new HologramLibraryConfig(previousDefaultFile.toFile());
        SimpleAssert.eq(
            1024,
            administratorUpdated.view().uploadKiB,
            "administrator can explicitly upgrade existing upload to 1 MiB/s");
        SimpleAssert
            .eq(128, administratorUpdated.view().downloadKiB, "explicit upload upgrade preserves chosen download");
        Path missingUploadFile = file.resolveSibling("missing-upload.cfg");
        Files.write(
            missingUploadFile,
            ("serverblueprintlibrary {\n I:downloadKiBPerSecond=16\n I:maxUploadsPerPlayer=7\n}\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HologramLibraryConfig missingUpload = new HologramLibraryConfig(missingUploadFile.toFile());
        SimpleAssert.eq(128, missingUpload.view().uploadKiB, "missing upload key uses new upload default");
        SimpleAssert.eq(16, missingUpload.view().downloadKiB, "missing upload key does not migrate existing download");
        SimpleAssert.eq(7, missingUpload.view().maxUploads, "missing upload key does not migrate existing quota");
        Path legacyFile = file.resolveSibling("existing-16.cfg");
        Files.write(
            legacyFile,
            ("serverblueprintlibrary {\n I:uploadKiBPerSecond=16\n I:downloadKiBPerSecond=16\n I:maxUploadsPerPlayer=7\n}\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HologramLibraryConfig legacyConfig = new HologramLibraryConfig(legacyFile.toFile());
        SimpleAssert.eq(128, legacyConfig.view().uploadKiB, "unversioned legacy pair migrates upload");
        SimpleAssert.eq(512, legacyConfig.view().downloadKiB, "unversioned legacy pair migrates download");
        HologramLibraryConfig legacyReopened = new HologramLibraryConfig(legacyFile.toFile());
        SimpleAssert.eq(128, legacyReopened.view().uploadKiB, "migrated upload persists after reopen");
        SimpleAssert.eq(512, legacyReopened.view().downloadKiB, "migrated download persists after reopen");
        SimpleAssert.eq(7, legacyReopened.view().maxUploads, "existing quota remains unchanged");
        legacyReopened.update(true, legacyReopened.view().revision, 16, 16, 7);
        HologramLibraryConfig explicitSlow = new HologramLibraryConfig(legacyFile.toFile());
        SimpleAssert.eq(16, explicitSlow.view().uploadKiB, "versioned explicit slow upload remains configurable");
        SimpleAssert.eq(16, explicitSlow.view().downloadKiB, "migration never repeats over an administrator choice");
        HologramLibraryProtocol.Budget download = new HologramLibraryProtocol.Budget();
        int slice = HologramLibraryProtocol.CHUNK_BYTES, transferred = 0;
        download.take(slice, 512 * 1024, 1);
        for (int tick = 1; tick <= 400; tick++) for (int pass = 0; pass < 4; pass++)
            if (download.take(slice, 512 * 1024, 1L + tick * 50000000L)) transferred += slice;
        SimpleAssert.eq(20 * 512 * 1024, transferred, "fair multi-slice pump sustains configured 512 KiB/s");
        denied(() -> config.update(false, 1, 20, 30, 2), "OP3/read-only cannot change config");
        config.update(true, 1, 20, 30, 2);
        denied(() -> config.update(true, 1, 16, 16, 10), "stale GUI revision cannot overwrite command changes");
        HologramLibraryConfig reopened = new HologramLibraryConfig(file.toFile());
        SimpleAssert.eq(20, reopened.view().uploadKiB, "Forge file persists upload");
        SimpleAssert.eq(30, reopened.view().downloadKiB, "Forge file persists download");
        SimpleAssert.eq(2, reopened.view().maxUploads, "Forge file persists quota");
        HologramLibraryConfig.View before = config.view();
        Files.delete(file);
        Files.createDirectory(file);
        Path untouched = file.resolve("keep.txt");
        byte[] marker = "existing target must survive failed save".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(untouched, marker);
        denied(
            () -> config.update(true, before.revision, 17, 19, 11),
            "directory target rejects save instead of falsely ACKing success");
        SimpleAssert.that(config.view() == before, "failed save preserves current DTO and revision");
        SimpleAssert.eq(20, config.view().uploadKiB, "failed save preserves upload bandwidth");
        SimpleAssert.eq(30, config.view().downloadKiB, "failed save preserves download bandwidth");
        SimpleAssert.eq(2, config.view().maxUploads, "failed save preserves quota");
        SimpleAssert.that(
            Arrays.equals(marker, Files.readAllBytes(untouched)),
            "failed save preserves the existing target directory contents");
        denied(
            () -> new HologramLibraryConfig(file.toFile()),
            "directory target fails constructor without overwriting native storage");
    }

    private static void hydration() throws Exception {
        int slice = HologramLibraryProtocol.CHUNK_BYTES;
        byte[] bytes = new byte[4 * slice + 1];
        Arrays.fill(bytes, (byte) 42);
        byte[] hash = HologramLibraryProtocol.digest()
            .digest(bytes);
        HologramLibrarySnapshotAssembly assembly = new HologramLibrarySnapshotAssembly();
        byte[] complete = null;
        // Total duration exceeds vanilla's 30s whole-transfer deadline, but every slice is active.
        for (int offset = 0, index = 0; offset < bytes.length; offset += slice, index++) {
            byte[] chunk = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + slice));
            io.netty.buffer.ByteBuf wire = Unpooled.buffer();
            new HologramNetwork.LibrarySnapshotFragment(1, bytes.length, index, hash, chunk).toBytes(wire);
            SimpleAssert.eq(3, wire.getInt(0), "hydration carries explicit protocol version 3");
            SimpleAssert
                .that(wire.readableBytes() + 1 < 32767, "bounded hydration fragment fits the smaller C17 limit too");
            HologramNetwork.LibrarySnapshotFragment decoded = new HologramNetwork.LibrarySnapshotFragment();
            decoded.fromBytes(wire);
            complete = assembly.accept(
                1,
                (Integer) get(decoded, "total"),
                (Integer) get(decoded, "index"),
                (byte[]) get(decoded, "bytes"),
                (byte[]) get(decoded, "hash"),
                1L + index * 20000000000L);
        }
        SimpleAssert.that(Arrays.equals(bytes, complete), "slow hydration uses inactivity timeout and verifies hash");
        io.netty.buffer.ByteBuf legacyWire = Unpooled.buffer()
            .writeLong(1L)
            .writeInt(slice)
            .writeInt(0)
            .writeBytes(hash)
            .writeInt(slice)
            .writeBytes(new byte[slice]);
        denied(
            () -> new HologramNetwork.LibrarySnapshotFragment().fromBytes(legacyWire),
            "legacy hydration without a protocol version is rejected");
        io.netty.buffer.ByteBuf oldVersion = Unpooled.buffer();
        new HologramNetwork.LibrarySnapshotFragment(1, slice, 0, hash, new byte[slice]).toBytes(oldVersion);
        oldVersion.setInt(0, 1);
        denied(
            () -> new HologramNetwork.LibrarySnapshotFragment().fromBytes(oldVersion),
            "old hydration protocol version is rejected");
        io.netty.buffer.ByteBuf oversized = Unpooled.buffer();
        new HologramNetwork.LibrarySnapshotFragment(1, slice + 1, 0, hash, new byte[slice + 1]).toBytes(oversized);
        denied(
            () -> new HologramNetwork.LibrarySnapshotFragment().fromBytes(oversized),
            "hydration chunk remains bounded before payload allocation");
        byte[] wrong = hash.clone();
        wrong[0] ^= 1;
        SimpleAssert.that(
            assembly.accept(2, 1, 0, new byte[] { 42 }, wrong, 1) == null,
            "bad final snapshot hash never published");
        int large = 9 * 1024 * 1024;
        assembly.accept(3, large, 0, new byte[slice], hash, 1);
        assembly.accept(3, large, 1, new byte[slice], hash, 2);
        SimpleAssert.eq(
            2 * slice,
            ((Integer) get(assembly, "offset")).intValue(),
            "snapshot budget is independent of the JSON artifact budget");
        assembly.accept(3, large, 2, new byte[slice], hash, HologramLibraryProtocol.TIMEOUT_NANOS + 3);
        SimpleAssert.eq(0, ((Integer) get(assembly, "offset")).intValue(), "inactive hydration releases its assembly");
        assembly.reset();
    }

    private static void ownershipAndPublishing() throws Exception {
        Path build = java.nio.file.Paths.get("build");
        Files.createDirectories(build);
        Path directory = Files.createTempDirectory(build, "gtmb-library-store-");
        HologramServerLibrary store = new HologramServerLibrary(
            directory.resolve("entries"),
            directory.resolve("library.cfg"));
        try {
            runWorker(store, () -> store.changeConfig(true, 1, 16, 16, 1));
            EntityPlayerMP owner = player(UUID.randomUUID(), "VerifiedPublisher");
            EntityPlayerMP other = player(UUID.randomUUID(), "DifferentPublisher");
            Object ownPeer = peer(owner), otherPeer = peer(other);
            HologramPresets.Preset blueprint = blueprint();
            byte[] bytes = HologramServerLibrary.encodeShare(blueprint);
            transfer(store, ownPeer, bytes, "", false);
            String savedId = HologramBlueprintFiles.managedPublic(directory.resolve("entries"))
                .keySet()
                .iterator()
                .next();
            Path savedPath = HologramBlueprintFiles.resolve(directory.resolve("entries"), savedId);
            SimpleAssert.that(savedId.startsWith("public_"), "server chooses the public ID");
            SimpleAssert.that(
                Files.isRegularFile(HologramBlueprintArtifact.previewPath(savedPath)),
                "dedicated publish stores a same-basename true PNG");
            SimpleAssert.eq(
                "OriginalCreator",
                store.get(savedId).authorName,
                "author provenance is retained independently of uploader");
            transfer(store, ownPeer, bytes, savedId, false);
            SimpleAssert.that(store.get(savedId) != null, "updating owned entry works when quota is full");
            denied(() -> transfer(store, ownPeer, bytes, "", false), "new upload rejected at quota");
            denied(
                () -> transfer(store, otherPeer, bytes, savedId, false),
                "other player cannot replace uploaded entry");
            NBTTagCompound delete = HologramLibraryProtocol.request("delete");
            delete.setString("id", savedId);
            denied(() -> runWorker(store, () -> handle(store, otherPeer, delete)), "other player cannot delete");
            SimpleAssert.that(store.get(savedId) != null, "denied deletion preserves entry");
            runtimeRestartPreservesCommittedLibrary(store, savedPath, savedId);
            runWorker(store, () -> handle(store, ownPeer, delete));
            SimpleAssert.that(store.get(savedId) == null, "owner can delete own upload");
            SimpleAssert.that(
                !Files.exists(HologramBlueprintArtifact.previewPath(savedPath)),
                "owner deletion clears the corresponding preview");
            denied(() -> transfer(store, ownPeer, bytes, "", true), "wrong transfer hash is rejected");
            SimpleAssert.that(
                HologramBlueprintFiles.managedPublic(directory.resolve("entries"))
                    .isEmpty(),
                "failed hash never publishes an entry");
        } finally {
            store.stop();
        }
    }

    private static void runtimeRestartPreservesCommittedLibrary(HologramServerLibrary store, Path artifact, String id)
        throws Exception {
        byte[] before = Files.readAllBytes(artifact);
        Object published = get(store, "published"), worker = get(store, "worker");
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean obsolete = new java.util.concurrent.atomic.AtomicBoolean();
        ((java.util.concurrent.Executor) worker).execute(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread()
                    .interrupt();
            }
        });
        try {
            SimpleAssert.that(entered.await(10, TimeUnit.SECONDS), "the real library worker is held before reset");
            SimpleAssert.that(store.enqueue(() -> obsolete.set(true)), "old generation callback is admitted");
            store.restartRuntime();
            SimpleAssert.that(get(store, "worker") == worker, "runtime reset retains a single serialized writer");
            SimpleAssert.that(get(store, "published") == published, "runtime reset retains the authoritative catalog");
            release.countDown();
            runWorker(store, () -> {});
            SimpleAssert.that(!obsolete.get(), "retired queued work cannot run after runtime reset");
            SimpleAssert.that(
                store.get(id) != null && Arrays.equals(before, Files.readAllBytes(artifact)),
                "new work is admitted while committed blueprint bytes remain unchanged");
        } finally {
            release.countDown();
        }
    }

    private static void permissions() throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        Field global = MinecraftServer.class.getDeclaredField("mcServer");
        global.setAccessible(true);
        Object previous = global.get(null);
        try {
            DedicatedServer server = (DedicatedServer) unsafe.allocateInstance(DedicatedServer.class);
            ServerConfigurationManager manager = (ServerConfigurationManager) unsafe
                .allocateInstance(DedicatedPlayerList.class);
            UserListOps ops = new UserListOps(
                Files.createTempDirectory("gtmb-library-ops-")
                    .resolve("ops.json")
                    .toFile());
            set(server, "serverConfigManager", manager);
            set(manager, "ops", ops);
            set(manager, "mcServer", server);
            // commandsAllowedForAll must not turn a missing explicit OP entry into an administrator.
            set(manager, "commandsAllowedForAll", true);
            global.set(null, server);
            EntityPlayerMP player = player(UUID.randomUUID(), "OperatorFixture");
            set(player, "mcServer", server);
            ops.func_152687_a(new UserListOpsEntry(player.getGameProfile(), 3));
            SimpleAssert.that(!HologramLibraryFacade.isAdmin(player), "actual OP3 is denied");
            HologramLibraryCommand command = new HologramLibraryCommand();
            SimpleAssert.that(!command.canCommandSenderUseCommand(player), "actual OP3 cannot run admin command");
            ops.func_152687_a(new UserListOpsEntry(player.getGameProfile(), 4));
            SimpleAssert.that(HologramLibraryFacade.isAdmin(player), "actual OP4 is accepted");
            SimpleAssert.that(command.canCommandSenderUseCommand(player), "actual OP4 can run admin command");
            SimpleAssert
                .that(command.canCommandSenderUseCommand(server), "real dedicated console is level 4 and allowed");
            ops.func_152684_c(player.getGameProfile());
            SimpleAssert
                .that(!HologramLibraryFacade.isAdmin(player), "commandsAllowedForAll cannot grant public admin");
            Class<?> integrated = Class.forName("net.minecraft.server.integrated.IntegratedServer");
            global.set(null, unsafe.allocateInstance(integrated));
            SimpleAssert.that(!HologramLibraryFacade.enabled(), "integrated server disables the public library");
            SimpleAssert
                .that(!HologramLibraryFacade.isAdmin(player), "integrated server rejects admin regardless of OP");
            SimpleAssert.that(
                !command.canCommandSenderUseCommand(server),
                "integrated context disables even console public-library commands");
        } finally {
            global.set(null, previous);
        }
    }

    private static void directoryLayout() throws Exception {
        Path root = Files.createTempDirectory("gtmb-server-blueprint-directory-");
        Path directory = Files.createDirectories(root.resolve("MiaoBlueprints"));
        Path configuration = root.resolve("config/gtmb/server-blueprint-library.cfg");
        Path oldDirectory = Files.createDirectories(root.resolve("config/gtmb/server-blueprints"));
        String oldId = "public_" + UUID.randomUUID();
        byte[] oldBytes = "old path must not be read".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path oldFile = oldDirectory.resolve(oldId + ".json");
        Files.write(oldFile, oldBytes);
        Files.write(directory.resolve("local_" + UUID.randomUUID() + ".json"), oldBytes);
        Files.write(directory.resolve("hologram-presets.json"), oldBytes);
        Files.write(directory.resolve("shared_fixture.json"), oldBytes);
        HologramServerLibrary store = new HologramServerLibrary(directory, configuration);
        HologramServerLibrary restarted = null;
        boolean storeStopped = false;
        try {
            runWorker(store, () -> {});
            SimpleAssert.that(store.available(), "only server public filenames are loaded from the new directory");
            SimpleAssert.that(store.get(oldId) == null, "old dedicated directory is not scanned or migrated");
            EntityPlayerMP owner = player(UUID.randomUUID(), "DirectoryPublisher");
            byte[] bytes = HologramServerLibrary.encodeShare(blueprint());
            transfer(store, peer(owner), bytes, "", false);
            String savedId = HologramBlueprintFiles.managedPublic(directory)
                .keySet()
                .iterator()
                .next();
            Path stored = HologramBlueprintFiles.resolve(directory, savedId);
            SimpleAssert.that(
                Files.isRegularFile(stored) && stored.getParent()
                    .equals(directory.toAbsolutePath()),
                "dedicated upload writes the exact top-level library directory");
            SimpleAssert.that(
                stored.getFileName()
                    .toString()
                    .matches("PublicFixture_[a-f0-9]{64}\\.json"),
                "public physical filenames carry the blueprint name and full semantic hash");
            SimpleAssert.that(Files.isRegularFile(configuration), "server limits and settings remain in config/gtmb");
            store.stop();
            storeStopped = true;
            restarted = new HologramServerLibrary(directory, configuration);
            HologramServerLibrary reloaded = restarted;
            runWorker(reloaded, () -> {});
            SimpleAssert.that(reloaded.get(savedId) != null, "new dedicated files reload after worker restart");
            SimpleAssert.that(reloaded.get(oldId) == null, "restart continues to ignore the old directory");
            Object otherPeer = peer(player(UUID.randomUUID(), "DirectoryOtherPublisher"));
            denied(
                () -> transfer(reloaded, otherPeer, bytes, savedId, false),
                "new storage location retains the original upload ownership boundary");
            SimpleAssert.that(
                Arrays.equals(oldBytes, Files.readAllBytes(oldFile)),
                "dedicated new saves preserve old blueprint files without migration");
        } finally {
            if (!storeStopped) store.stop();
            if (restarted != null) restarted.stop();
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void brokenStorageFailsClosed() throws Exception {
        Path directory = Files.createTempDirectory("gtmb-library-broken-");
        Path entries = Files.createDirectories(directory.resolve("entries"));
        Path broken = entries.resolve("public_" + UUID.randomUUID() + ".json");
        byte[] original = "{broken-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(broken, original);
        HologramServerLibrary store = new HologramServerLibrary(entries, directory.resolve("library.cfg"));
        try {
            // A worker barrier observes the real startup attempt, without waiting on the disabled request pump.
            java.util.concurrent.ScheduledThreadPoolExecutor worker = (java.util.concurrent.ScheduledThreadPoolExecutor) get(
                store,
                "worker");
            worker.submit(() -> {})
                .get(10, TimeUnit.SECONDS);
            SimpleAssert.that(!store.available(), "corrupt native envelope keeps library closed");
            SimpleAssert.that(
                store.unavailableMessage()
                    .contains("operator must repair storage"),
                "startup failure is immediately actionable");
            NBTTagCompound status = store.statusTag(true);
            SimpleAssert.that(
                status.getBoolean("readOnly") && !status.getBoolean("admin"),
                "even OP4 receives a read-only failed-library DTO");
            SimpleAssert.that(
                !status.getString("libraryError")
                    .isEmpty(),
                "failed-library DTO includes visible error");
            denied(() -> store.get("public_" + UUID.randomUUID()), "failed startup denies blueprint reads immediately");
            denied(() -> store.changeConfig(true, 1, 32, 32, 20), "failed startup denies queued config writes");
            denied(
                () -> store.prepareDraft(
                    player(UUID.randomUUID(), "FailedStoreActor"),
                    HologramLibraryProtocol.request("exportDraft"),
                    blueprint()),
                "failed startup rejects draft submission immediately");
            SimpleAssert.that(
                !store.enqueue(() -> { throw new AssertionError("closed worker task ran"); }),
                "failed startup rejects worker queue admission");
            SimpleAssert.that(
                Arrays.equals(original, Files.readAllBytes(broken)),
                "fail-closed startup preserves damaged native file for repair");
        } finally {
            store.stop();
        }
    }

    private static HologramPresets.Preset blueprint() {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = "local_" + UUID.randomUUID();
        preset.name = "PublicFixture";
        preset.controllerType = "area";
        preset.authorName = "OriginalCreator";
        preset.authorUuid = UUID.randomUUID()
            .toString();
        preset.createdAt = 1234;
        try {
            HologramBlueprintVersions.stamp(preset);
        } catch (IOException invalid) {
            throw new IllegalStateException(invalid);
        }
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        cell.block = "minecraft:stone";
        preset.cells.add(cell);
        return preset;
    }

    private static void transfer(HologramServerLibrary store, Object peer, byte[] bytes, String replace,
        boolean wrongHash) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            HologramBlueprintArtifact.WIDTH,
            HologramBlueprintArtifact.HEIGHT,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        bytes = HologramBlueprintArtifact.encode(bytes, png.toByteArray());
        String transfer = UUID.randomUUID()
            .toString();
        NBTTagCompound begin = HologramLibraryProtocol.request("uploadBegin");
        begin.setString("transfer", transfer);
        begin.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        begin.setInteger("total", bytes.length);
        begin.setString(
            "hash",
            wrongHash ? repeat('a', 64)
                : HologramLibraryProtocol.hex(
                    HologramLibraryProtocol.digest()
                        .digest(bytes)));
        begin.setString("replaceId", replace);
        runWorker(store, () -> handle(store, peer, begin));
        try {
            for (int offset = 0, index = 0; offset
                < bytes.length; offset += HologramLibraryProtocol.CHUNK_BYTES, index++) {
                NBTTagCompound chunk = HologramLibraryProtocol.request("uploadChunk");
                chunk.setString("transfer", transfer);
                chunk.setInteger("index", index);
                chunk.setByteArray(
                    "data",
                    Arrays.copyOfRange(
                        bytes,
                        offset,
                        Math.min(bytes.length, offset + HologramLibraryProtocol.CHUNK_BYTES)));
                runWorker(store, () -> handle(store, peer, chunk));
            }
        } catch (Exception failure) {
            runWorker(store, () -> invoke(store, "abort", peer));
            throw failure;
        }
    }

    private static Object peer(EntityPlayerMP player) throws Exception {
        Class<?> type = Class.forName(HologramServerLibrary.class.getName() + "$Peer");
        Constructor<?> constructor = type.getDeclaredConstructor(EntityPlayerMP.class);
        constructor.setAccessible(true);
        return constructor.newInstance(player);
    }

    private static EntityPlayerMP player(UUID uuid, String name) throws Exception {
        EntityPlayerMP player = (EntityPlayerMP) unsafe().allocateInstance(EntityPlayerMP.class);
        set(player, "entityUniqueID", uuid);
        for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
            field.setAccessible(true);
            field.set(player, new GameProfile(uuid, name));
            return player;
        }
        throw new AssertionError("GameProfile field missing");
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static void handle(HologramServerLibrary store, Object peer, NBTTagCompound request) throws Exception {
        Method method = HologramServerLibrary.class.getDeclaredMethod("handle", peer.getClass(), NBTTagCompound.class);
        method.setAccessible(true);
        try {
            method.invoke(store, peer, request);
        } catch (InvocationTargetException failure) {
            throw (Exception) failure.getCause();
        }
    }

    private static void invoke(HologramServerLibrary store, String name, Object peer) throws Exception {
        Method method = HologramServerLibrary.class.getDeclaredMethod(name, peer.getClass());
        method.setAccessible(true);
        method.invoke(store, peer);
    }

    private static void runWorker(HologramServerLibrary store, Checked work) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (!store.enqueue(() -> {
            try {
                work.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        })) throw new AssertionError("worker enqueue failed");
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw (Exception) failure.getCause();
        }
    }

    private static Object get(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(object, value);
                return;
            } catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private interface Checked {

        void run() throws Exception;
    }

    private static void denied(Checked work, String message) throws Exception {
        boolean rejected = false;
        try {
            work.run();
        } catch (IOException | IllegalArgumentException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, message);
    }
}
