package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real persisted pairs and library IO executor; startup has no player/session and never waits for heartbeat. */
public final class HologramLibraryStartupRegression {

    private HologramLibraryStartupRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLibraryStartupRegression.class, args)) return;
        run();
        System.out.println("Hologram persisted library startup regressions passed");
    }

    public static void run() throws Exception {
        Path root = Files.createTempDirectory("gtmb-library-startup-");
        Path directory = root.resolve("MiaoBlueprints"), config = root.resolve("library.cfg");
        HologramServerLibrary store = null;
        try {
            store = new HologramServerLibrary(directory, config);
            barrier(store);
            HologramPresets.Preset source = blueprint();
            // More than one native slice, with otherwise ordinary builtin stone semantics.
            for (int x = 1; x < 300; x++) {
                HologramPresets.Cell cell = HologramPresets.cell(
                    source.cells.get(0)
                        .stack(),
                    x,
                    0,
                    0,
                    -1,
                    false);
                cell.block = "minecraft:stone";
                source.cells.add(cell);
            }
            EntityPlayerMP player = (EntityPlayerMP) fixture(
                "player",
                new Class<?>[] { UUID.class, String.class },
                UUID.randomUUID(),
                "StartupPublisher");
            Object peer = fixture("peer", new Class<?>[] { EntityPlayerMP.class }, player);
            fixture(
                "transfer",
                new Class<?>[] { HologramServerLibrary.class, Object.class, byte[].class, String.class, boolean.class },
                store,
                peer,
                HologramServerLibrary.encodeShare(source),
                "",
                false);
            String id = HologramBlueprintFiles.managedPublic(directory)
                .keySet()
                .iterator()
                .next();
            Path file = HologramBlueprintFiles.resolve(directory, id);
            HologramBlueprintArtifact.Artifact original = HologramBlueprintArtifact.readPair(file);
            String expectedHash = HologramBlueprintContentHash.of(store.get(id));
            stop(store);
            store = new HologramServerLibrary(directory, config);
            barrier(store);
            SimpleAssert.that(store.available(), "builtin persisted reload completes without any owner heartbeat");
            assertIdentity(store, id, expectedHash, original);
            stop(store);
            store = null;

            AtomicInteger calls = new AtomicInteger();
            try (GTMBExtensions.Registration registration = NativePlacement
                .register("gtmb_test:startup_owner", ownerOnly(calls))) {
                store = new HologramServerLibrary(directory, config);
                barrier(store);
                SimpleAssert.that(!store.available(), "external persisted reload waits for owner continuation");
                SimpleAssert.eq(0, calls.get(), "legacy applicability never runs on startup IO before heartbeat");
                SimpleAssert.that(published(store).isEmpty(), "no partially validated catalog is visible");
                // A second real worker barrier completes despite the pending owner: no future wait on IO.
                barrier(store);
                tick(store);
                SimpleAssert
                    .that(calls.get() < source.cells.size(), "schema-2 native validation yields between slices");
                SimpleAssert.that(!store.available(), "one owner slice cannot publish a whole pending catalog");
                finish(store);
                SimpleAssert.that(calls.get() > 0, "owner-only declining adapter is actually invoked");
                assertIdentity(store, id, expectedHash, original);
                stop(store);

                store = new HologramServerLibrary(directory, config);
                barrier(store);
                Object retired = pendingValidation(store);
                store.restartRuntime();
                SimpleAssert.that(!store.available(), "runtime restart leaves unpublished startup unavailable");
                rejectedRetired(retired, calls);
                finish(store);
                assertIdentity(store, id, expectedHash, original);
                stop(store);

                store = new HologramServerLibrary(directory, config);
                barrier(store);
                retired = pendingValidation(store);
                stop(store);
                rejectedRetired(retired, calls);
                tick(store);
                SimpleAssert.that(
                    !store.available() && published(store).isEmpty(),
                    "stop cannot publish a stale owner or worker completion");
                SimpleAssert.that(get(store, "startup") == null, "stop releases pending catalog retention");
                store = null;

                // A valid physical pair with invalid native schema must fail closed, not be skipped/deleted.
                JsonObject invalid = HologramBlueprintCompactCodec.parse(original.json);
                String badId = "public_" + UUID.randomUUID();
                invalid.getAsJsonObject("blueprint")
                    .addProperty("id", badId);
                invalid.getAsJsonObject("blueprint")
                    .getAsJsonArray("cells")
                    .get(0)
                    .getAsJsonObject()
                    .addProperty("count", 0);
                HologramBlueprintArtifact.Artifact bad = HologramBlueprintArtifact.transfer(
                    invalid.toString()
                        .getBytes(StandardCharsets.UTF_8),
                    original);
                Path badFile = directory.resolve(badId + ".json");
                HologramBlueprintArtifact.writePair(badFile, bad.json, bad.png);
                store = new HologramServerLibrary(directory, config);
                barrier(store);
                finishFailure(store);
                SimpleAssert.that(
                    !store.available() && published(store).isEmpty(),
                    "one invalid entry prevents publication of the entire catalog");
                SimpleAssert.that(
                    store.unavailableMessage()
                        .contains("operator must repair storage"),
                    "invalid native entry reports repairable storage failure");
                SimpleAssert.that(
                    Arrays.equals(bad.json, Files.readAllBytes(badFile)),
                    "invalid entry is preserved byte-for-byte for operator repair");
                SimpleAssert.that(
                    Arrays.equals(original.json, Files.readAllBytes(file)),
                    "valid pair is not rewritten by failed startup");
                stop(store);
                store = null;
            }
            builtinRegistrationRaceGuard();
        } finally {
            if (store != null && !worker(store).isShutdown()) stop(store);
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void builtinRegistrationRaceGuard() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GTMBExtensions.Registration registration = null;
        boolean rejected = false;
        try (HologramExtensionExecution.Scope scope = HologramExtensionExecution.builtinLibraryIo()) {
            // Models registration after the fast-path query, including an explicitly client-safe adapter.
            registration = NativePlacement.register("gtmb_test:startup_race", ownerOnly(calls), true);
            HologramServerLibrary.canonical(blueprint());
        } catch (HologramExtensionExecution.ReviewRequired expected) {
            rejected = true;
        } finally {
            if (registration != null) registration.close();
        }
        SimpleAssert.that(rejected, "sticky builtin IO scope rejects registration races before applicability");
        SimpleAssert.eq(0, calls.get(), "client-safe registration still cannot execute on library IO");
    }

    private static NativePlacement.Adapter ownerOnly(AtomicInteger calls) {
        return new NativePlacement.Adapter() {

            public boolean supports(Block block) {
                if (!HologramWorkBudget.isServerOwnerThread())
                    throw new IllegalStateException("Fixture requires the actual server owner");
                calls.incrementAndGet();
                return false;
            }

            public TileEntity create(Block block, int metadata, NBTTagCompound tag, World world) {
                throw new AssertionError("Declining startup adapter cannot create");
            }

            public java.util.List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
                throw new AssertionError("Declining startup adapter cannot plan materials");
            }
        };
    }

    private static void assertIdentity(HologramServerLibrary store, String id, String hash,
        HologramBlueprintArtifact.Artifact original) throws Exception {
        SimpleAssert.that(store.available(), "owner-reviewed persisted catalog becomes available");
        SimpleAssert
            .eq(hash, HologramBlueprintContentHash.of(store.get(id)), "canonical content identity is unchanged");
        Object entry = published(store).get(id);
        SimpleAssert.eq(hash, get(entry, "hash"), "catalog hash matches direct canonical load");
        SimpleAssert.that(
            ((Map<?, ?>) get(store, "contentIndex")).get(hash) == entry,
            "logical and content indices point at the same admitted entry");
        Method stored = HologramServerLibrary.class.getDeclaredMethod("storedArtifact", entry.getClass());
        stored.setAccessible(true);
        HologramBlueprintArtifact.Artifact readback = (HologramBlueprintArtifact.Artifact) stored.invoke(store, entry);
        SimpleAssert.that(
            Arrays.equals(original.json, readback.json) && Arrays.equals(original.png, readback.png),
            "original JSON/PNG readback hashes and bytes survive owner-routed startup");
    }

    private static void rejectedRetired(Object validation, AtomicInteger calls) throws Exception {
        int before = calls.get();
        boolean rejected = false;
        try {
            ((HologramLibraryStartupValidation) validation).advanceOwner();
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "retired epoch rejects its old owner continuation");
        SimpleAssert.eq(before, calls.get(), "retired continuation does not enter native applicability");
    }

    private static Object pendingValidation(HologramServerLibrary store) throws Exception {
        return get(get(get(store, "startup"), "pending"), "validation");
    }

    private static void finish(HologramServerLibrary store) throws Exception {
        drive(store);
        SimpleAssert
            .that(store.available(), "persisted load reaches successful terminal state: " + store.unavailableMessage());
    }

    private static void finishFailure(HologramServerLibrary store) throws Exception {
        drive(store);
        SimpleAssert.that(get(store, "loadFailure") != null, "invalid persisted load reaches failure terminal state");
    }

    private static void drive(HologramServerLibrary store) throws Exception {
        Method advance = HologramServerLibrary.class.getDeclaredMethod("advanceStartup");
        advance.setAccessible(true);
        for (int attempt = 0; attempt < 10000 && !store.available() && get(store, "loadFailure") == null; attempt++) {
            tick(store);
            worker(store).submit(() -> {
                try {
                    advance.invoke(store);
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            })
                .get(30, TimeUnit.SECONDS);
        }
    }

    private static void tick(HologramServerLibrary store) {
        HologramWorkBudget.startServerTick();
        try {
            store.heartbeat();
        } finally {
            HologramWorkBudget.endServerTick();
        }
    }

    private static void barrier(HologramServerLibrary store) throws Exception {
        worker(store).submit(() -> {})
            .get(30, TimeUnit.SECONDS);
    }

    private static void stop(HologramServerLibrary store) throws Exception {
        store.stop();
        SimpleAssert
            .that(worker(store).awaitTermination(30, TimeUnit.SECONDS), "stopped worker retires without owner wait");
    }

    private static ScheduledThreadPoolExecutor worker(HologramServerLibrary store) throws Exception {
        return (ScheduledThreadPoolExecutor) get(store, "worker");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> published(HologramServerLibrary store) throws Exception {
        return (Map<String, ?>) get(store, "published");
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static HologramPresets.Preset blueprint() throws Exception {
        return (HologramPresets.Preset) fixture("blueprint", new Class<?>[0]);
    }

    private static Object fixture(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = HologramLibraryRegression.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception) throw (Exception) wrapped.getCause();
            throw (Error) wrapped.getCause();
        }
    }
}
