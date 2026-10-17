package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
        protocolAndThrottle();
        downloads();
        downloadReservations();
        taskCorrelation();
        configuration();
        hydration();
        ownershipAndPublishing();
        directoryLayout();
        brokenStorageFailsClosed();
        permissions();
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

    private static void configuration() throws Exception {
        Path file = Files.createTempDirectory("gtmb-library-config-")
            .resolve("library.cfg");
        HologramLibraryConfig config = new HologramLibraryConfig(file.toFile());
        SimpleAssert.eq(1024, config.view().uploadKiB, "fresh default aggregate upload is 1 MiB/s");
        SimpleAssert.eq(128, config.view().downloadKiB, "fresh default aggregate download");
        SimpleAssert.eq(10, config.view().maxUploads, "default UUID quota");
        NBTTagCompound defaults = config.view()
            .tag(true);
        SimpleAssert.eq(1024, defaults.getInteger("uploadKiB"), "administrator UI receives new upload default");
        SimpleAssert.eq(128, defaults.getInteger("downloadKiB"), "administrator UI retains download default");
        HologramLibraryConfig freshReopened = new HologramLibraryConfig(file.toFile());
        SimpleAssert.eq(1024, freshReopened.view().uploadKiB, "new upload default persists in actual Forge file");
        SimpleAssert.eq(128, freshReopened.view().downloadKiB, "new file keeps existing download behavior");
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
        SimpleAssert.eq(1024, missingUpload.view().uploadKiB, "missing upload key uses new upload default");
        SimpleAssert.eq(16, missingUpload.view().downloadKiB, "missing upload key does not migrate existing download");
        SimpleAssert.eq(7, missingUpload.view().maxUploads, "missing upload key does not migrate existing quota");
        Path legacyFile = file.resolveSibling("existing-16.cfg");
        Files.write(
            legacyFile,
            ("serverblueprintlibrary {\n I:uploadKiBPerSecond=16\n I:downloadKiBPerSecond=16\n I:maxUploadsPerPlayer=7\n}\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HologramLibraryConfig legacyConfig = new HologramLibraryConfig(legacyFile.toFile());
        SimpleAssert.eq(16, legacyConfig.view().uploadKiB, "existing explicit upload 16 is retained");
        SimpleAssert.eq(16, legacyConfig.view().downloadKiB, "existing explicit download 16 is retained");
        HologramLibraryConfig legacyReopened = new HologramLibraryConfig(legacyFile.toFile());
        SimpleAssert.eq(16, legacyReopened.view().uploadKiB, "persisting existing config does not migrate its upload");
        SimpleAssert
            .eq(16, legacyReopened.view().downloadKiB, "persisting existing config does not migrate its download");
        SimpleAssert.eq(7, legacyReopened.view().maxUploads, "existing quota remains unchanged");
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
        Path directory = Files.createTempDirectory("gtmb-library-store-");
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
            String savedId;
            try (java.nio.file.DirectoryStream<Path> files = Files
                .newDirectoryStream(directory.resolve("entries"), "public_*.json")) {
                savedId = files.iterator()
                    .next()
                    .getFileName()
                    .toString()
                    .replace(".json", "");
            }
            SimpleAssert.that(savedId.startsWith("public_"), "server chooses the public ID");
            SimpleAssert.that(
                Files.isRegularFile(directory.resolve("entries/" + savedId + ".png")),
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
            runWorker(store, () -> handle(store, ownPeer, delete));
            SimpleAssert.that(store.get(savedId) == null, "owner can delete own upload");
            SimpleAssert.that(
                !Files.exists(directory.resolve("entries/" + savedId + ".png")),
                "owner deletion clears the corresponding preview");
            denied(() -> transfer(store, ownPeer, bytes, "", true), "wrong transfer hash is rejected");
            try (java.nio.file.DirectoryStream<Path> files = Files
                .newDirectoryStream(directory.resolve("entries"), "public_*.json")) {
                SimpleAssert.that(
                    !files.iterator()
                        .hasNext(),
                    "failed hash never publishes an entry");
            }
        } finally {
            store.stop();
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
            String savedId;
            try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory, "public_*.json")) {
                savedId = files.iterator()
                    .next()
                    .getFileName()
                    .toString()
                    .replace(".json", "");
            }
            SimpleAssert.that(
                Files.isRegularFile(root.resolve("MiaoBlueprints/" + savedId + ".json")),
                "dedicated upload writes the exact new top-level directory");
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
