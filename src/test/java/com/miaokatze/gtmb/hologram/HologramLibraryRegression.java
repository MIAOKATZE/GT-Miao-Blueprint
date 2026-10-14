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
        configuration();
        hydration();
        ownershipAndPublishing();
        directoryLayout();
        brokenStorageFailsClosed();
        permissions();
    }

    private static void protocolAndThrottle() throws Exception {
        String id = UUID.randomUUID()
            .toString();
        HologramLibraryProtocol.header(
            id,
            1025,
            HologramLibraryProtocol.hex(
                HologramLibraryProtocol.digest()
                    .digest(new byte[1025])));
        denied(() -> HologramLibraryProtocol.header("../../escape", 1, repeat('a', 64)), "path-like transfer rejected");
        denied(
            () -> HologramLibraryProtocol.header(id, HologramLibraryProtocol.MAX_BYTES + 1, repeat('a', 64)),
            "declared size bounded");
        denied(() -> HologramLibraryProtocol.header(id, 1, "bad"), "hash header bounded");
        HologramLibraryProtocol.chunk(1025, 1024, 1, 1, 1);
        denied(() -> HologramLibraryProtocol.chunk(1025, 0, 0, 1, 1024), "out-of-order chunk rejected");
        denied(() -> HologramLibraryProtocol.chunk(1025, 0, 0, 0, 1025), "oversize chunk rejected");
        denied(() -> HologramLibraryProtocol.chunk(1025, 1024, 1, 1, 2), "last length must match remaining bytes");
        HologramLibraryProtocol.Budget budget = new HologramLibraryProtocol.Budget();
        SimpleAssert.that(!budget.take(1024, 16384, 1), "bucket starts empty");
        int accepted = 0;
        for (int i = 0; i < 64; i++) if (budget.take(1024, 16384, 1000000001L)) accepted++;
        SimpleAssert.eq(1, accepted, "sixty-four players cannot multiply the aggregate bucket");
        SimpleAssert.that(budget.take(1024, 16384, 1062500001L), "16KiB/s replenishes one 1KiB slice in 62.5ms");
        SimpleAssert.that(!budget.take(1024, 16384, 1062500001L), "same instant cannot consume twice");
        HologramLibraryProtocol.FairBudget fair = new HologramLibraryProtocol.FairBudget();
        UUID[] players = { UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() };
        int[] served = new int[players.length];
        for (int tick = 0; tick < 100; tick++) {
            long now = 1L + tick * 50000000L;
            for (int player = 0; player < players.length; player++) {
                // Changing which player polls first cannot lock successful every-other ticks to the same parity.
                int index = (tick + player) % players.length;
                if (fair.take(players[index], 1024, 16384, now)) served[index]++;
            }
        }
        for (int count : served)
            SimpleAssert.that(count >= 8, "every player is served despite tick/bucket phase alignment");
        denied(
            () -> new HologramNetwork.LibraryRequest().fromBytes(
                Unpooled.buffer()
                    .writeInt(8193)),
            "wire length checked before allocation");
        NBTTagCompound bad = HologramLibraryProtocol.request("uploadChunk");
        bad.setByteArray("data", new byte[2048]);
        io.netty.buffer.ByteBuf wire = Unpooled.buffer();
        new HologramNetwork.LibraryRequest(bad).toBytes(wire);
        denied(() -> new HologramNetwork.LibraryRequest().fromBytes(wire), "compressed large chunk rejected");
        NBTTagCompound drop = new NBTTagCompound();
        drop.setString("dropItem", "counterfeit");
        NBTTagList inner = new NBTTagList();
        inner.appendTag(drop);
        NBTTagList outer = new NBTTagList();
        outer.appendTag(inner);
        denied(() -> HologramServerLibrary.rejectDropPayload(outer), "dropItem rejected inside list-of-list");
    }

    private static void configuration() throws Exception {
        Path file = Files.createTempDirectory("gtmb-library-config-")
            .resolve("library.cfg");
        HologramLibraryConfig config = new HologramLibraryConfig(file.toFile());
        SimpleAssert.eq(16, config.view().uploadKiB, "default aggregate upload");
        SimpleAssert.eq(16, config.view().downloadKiB, "default aggregate download");
        SimpleAssert.eq(10, config.view().maxUploads, "default UUID quota");
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
        byte[] bytes = new byte[4097];
        Arrays.fill(bytes, (byte) 42);
        byte[] hash = HologramLibraryProtocol.digest()
            .digest(bytes);
        HologramLibrarySnapshotAssembly assembly = new HologramLibrarySnapshotAssembly();
        byte[] complete = null;
        // Total duration exceeds vanilla's 30s whole-transfer deadline, but every slice is active.
        for (int offset = 0, index = 0; offset < bytes.length; offset += 1024, index++) complete = assembly.accept(
            1,
            bytes.length,
            index,
            Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 1024)),
            hash,
            1L + index * 20000000000L);
        SimpleAssert.that(Arrays.equals(bytes, complete), "slow hydration uses inactivity timeout and verifies hash");
        byte[] wrong = hash.clone();
        wrong[0] ^= 1;
        SimpleAssert.that(
            assembly.accept(2, 1, 0, new byte[] { 42 }, wrong, 1) == null,
            "bad final snapshot hash never published");
        int large = 9 * 1024 * 1024;
        assembly.accept(3, large, 0, new byte[1024], hash, 1);
        assembly.accept(3, large, 1, new byte[1024], hash, 2);
        SimpleAssert.eq(
            2048,
            ((Integer) get(assembly, "offset")).intValue(),
            "snapshot budget is independent of 8MiB file budget");
        assembly.accept(3, large, 2, new byte[1024], hash, HologramLibraryProtocol.TIMEOUT_NANOS + 3);
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
            for (int offset = 0, index = 0; offset < bytes.length; offset += 1024, index++) {
                NBTTagCompound chunk = HologramLibraryProtocol.request("uploadChunk");
                chunk.setString("transfer", transfer);
                chunk.setInteger("index", index);
                chunk.setByteArray("data", Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 1024)));
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
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
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
