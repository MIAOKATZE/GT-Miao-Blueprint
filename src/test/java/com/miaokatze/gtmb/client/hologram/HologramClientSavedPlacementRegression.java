// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IChatComponent;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramNativeSavedWorldRegression;
import com.miaokatze.gtmb.hologram.HologramNativeSavedWorldRegression.ConnectedWorld;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/**
 * Actual saved-file loader, confirmed-submit entry, worker, registered reply decoder and client tick lifetime.
 * The inbound server receipts are controlled fixture inputs: this tests client wiring, not server payment/world work.
 */
public final class HologramClientSavedPlacementRegression {

    private HologramClientSavedPlacementRegression() {}

    public static void runNativeConnected() throws Exception {
        HologramNativeSavedWorldRegression.withConnectedWorld(3, server -> {
            try (Fixture client = new Fixture()) {
                client.quickCancel(server);
            }
        });
        for (boolean satisfied : new boolean[] { false, true }) {
            HologramNativeSavedWorldRegression.withConnectedWorld(satisfied ? 0 : 2, server -> {
                try (Fixture client = new Fixture()) {
                    client.nativeConnected(server, satisfied);
                }
            });
        }
        for (int terminal : new int[] { 0, 1, 2 }) {
            HologramNativeSavedWorldRegression.withConnectedWorld(2, server -> {
                try (Fixture client = new Fixture()) {
                    client.nativeConnected(server, false, terminal != 2, terminal != 0);
                }
            });
        }
    }

    public static void runNativeRemoteConnected() throws Exception {
        HologramNativeSavedWorldRegression.withConnectedWorld(9, server -> {
            try (Fixture client = new Fixture()) {
                client.nativeRemote(server);
            }
        });
        HologramNativeSavedWorldRegression.withConnectedWorld(0, server -> {
            try (Fixture client = new Fixture()) {
                client.nativeVaried(server);
            }
        });
    }

    private static final class Fixture implements AutoCloseable {

        final sun.misc.Unsafe unsafe;
        final Field singleton;
        final Object previousMinecraft;
        final HeadlessClient client;
        final HeadlessPlayer player;
        final NetHandlerPlayClient connection;
        final HologramLibraryClient owner = new HologramLibraryClient();
        final HologramLocalPlacement placement = HologramLocalPlacement.INSTANCE;
        final List<SavedFields> restore = new ArrayList<>();
        long generation;

        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            singleton = minecraftSingleton();
            previousMinecraft = singleton.get(null);
            SimpleAssert
                .that(!HologramBackgroundTasks.busy() && !placement.active(), "fixture never replaces an active owner");
            restore.add(new SavedFields(HologramBackgroundTasks.class, null));
            restore
                .add(new SavedFields(HologramTaskOwner.class, field(HologramBackgroundTasks.class, "OWNER").get(null)));
            restore.add(
                new SavedFields(
                    HologramOwnedPlacementProgress.class,
                    field(HologramBackgroundTasks.class, "placementProgress").get(null)));
            restore.add(new SavedFields(HologramLocalPlacement.class, placement));
            client = (HeadlessClient) unsafe.allocateInstance(HeadlessClient.class);
            client.tasks = new ConcurrentLinkedQueue<>();
            player = (HeadlessPlayer) unsafe.allocateInstance(HeadlessPlayer.class);
            connection = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
            field(EntityClientPlayerMP.class, "sendQueue").set(player, connection);
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.allowEdit = true;
            ItemStack projector = new ItemStack(new ItemNekoHologramProjector());
            projector.setTagCompound(new NBTTagCompound());
            projector.getTagCompound()
                .setString("gtitHologramToolId", new UUID(44, 55).toString());
            player.inventory.mainInventory[0] = projector;
            client.thePlayer = player;
            client.theWorld = (HeadlessWorld) unsafe.allocateInstance(HeadlessWorld.class);
            singleton.set(null, client);
            field(HologramLibraryClient.class, "libraryWorld").set(owner, client.theWorld);
            field(HologramLibraryClient.class, "libraryConnection").set(owner, connection);
            field(HologramLibraryClient.class, "libraryActor").set(owner, player.getUniqueID());
        }

        void quickCancel(ConnectedWorld server) throws Exception {
            Path root = Files.createTempDirectory("gtmb-quick-saved-");
            var store = new HologramClientLibrary(root);
            Files.createDirectories(store.directory());
            String id = "local_12345678-1234-1234-1234-123456789012";
            server.saveNative(store.directory(), id, 3);
            field(HologramLibraryClient.class, "store").set(owner, store);
            store.rememberLocal("", id);
            var worker = (ThreadPoolExecutor) field(HologramLibraryClient.class, "worker").get(owner);
            var gate = new java.util.concurrent.CountDownLatch(1);
            try {
                worker.execute(() -> {
                    try {
                        gate.await(4, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread()
                            .interrupt();
                    }
                });
                SimpleAssert.that(
                    owner.quickPlaceLast(new NBTTagCompound()) && owner.quickInProgress(),
                    "production recent shortcut acquired the real read task");
                SimpleAssert
                    .that(owner.cancelQuickPlacement() && !owner.quickInProgress(), "escape retires real quick read");
                gate.countDown();
                worker.submit(() -> {})
                    .get(5, TimeUnit.SECONDS);
                client.drain();
                SimpleAssert.that(
                    !placement.active() && !HologramBackgroundTasks.busy()
                        && !owner.cancelQuickPlacement()
                        && server.writes() == 0
                        && server.stock() == 3,
                    "late IO from cancelled shortcut did not open, place or charge");
                // After a cancellation, the legitimate next request is not inherited or suppressed.
                SimpleAssert.that(owner.quickPlaceLast(new NBTTagCompound()), "new shortcut accepted");
                worker.submit(() -> {})
                    .get(5, TimeUnit.SECONDS);
                client.drain();
                SimpleAssert.that(
                    placement.active() || owner.quickInProgress(),
                    "current saved preview progresses independently of cancelled generation");
                owner.cancelQuickPlacement();
                placement.clear();
                client.drain();
            } finally {
                gate.countDown();
                owner.cancelQuickPlacement();
                try (var paths = Files.walk(root)) {
                    for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                        Files.deleteIfExists(path);
                }
            }
        }

        /** No GL wrapper: exercise the actual frozen-ticket button predicate and deferred voucher action. */
        boolean savedVoucherChoice(HologramLocalPlacement.Submission frozen) throws Exception {
            var screen = (HologramLibraryMaterialReviewScreen) unsafe
                .allocateInstance(HologramLibraryMaterialReviewScreen.class);
            var deferred = new HologramUiDeferredAction();
            field(HologramMuiScreen.class, "deferredAction").set(screen, deferred);
            field(HologramLibraryMaterialReviewScreen.class, "ticket")
                .set(screen, new HologramLibraryMaterialReviewScreen.Ticket(placement, frozen, client, screen));
            var config = HologramLibraryClient.INSTANCE.config();
            var before = config.getTag("nativeDecorationVoucher");
            var prev = client.currentScreen;
            try {
                client.currentScreen = screen;
                field(HologramLibraryMaterialReviewScreen.class, "availability")
                    .set(screen, HologramLibraryClient.PlainAvailability.BUSY);
                config.setBoolean("nativeDecorationVoucher", true);
                SimpleAssert.that(
                    screen.localVoucherAvailable() && !screen.localSubmitAvailable(),
                    "payment choice is independent of construction readiness");
                config.setBoolean("nativeDecorationVoucher", false);
                SimpleAssert.that(
                    !screen.localVoucherAvailable() && screen.localVoucherBlocker()
                        .contains("停用"),
                    "server switch is authoritative");
                config.setBoolean("nativeDecorationVoucher", true);
                screen.localAction(LocalAction.VOUCHER);
                deferred.run(true);
                SimpleAssert.that(screen.localVoucherSelected(), "formal voucher action selected");
                player.capabilities.isCreativeMode = true;
                SimpleAssert.that(!screen.localVoucherSelected(), "creative never forwards a stale voucher selection");
                player.capabilities.isCreativeMode = false;
                return screen.localVoucherSelected();
            } finally {
                if (before == null) config.removeTag("nativeDecorationVoucher");
                else config.setTag("nativeDecorationVoucher", before);
                player.capabilities.isCreativeMode = false;
                client.currentScreen = prev;
            }
        }

        void nativeRemote(ConnectedWorld server) throws Exception {
            var actor = server.nativeActor();
            actor.getHeldItem()
                .getTagCompound()
                .setString("gtitHologramToolId", HologramLibraryClient.projectorId(player.getHeldItem()));
            com.miaokatze.gtmb.hologram.HologramMaterials
                .write(player.getHeldItem(), com.miaokatze.gtmb.hologram.HologramMaterials.read(actor.getHeldItem()));
            player.fixtureId = actor.getUniqueID();
            player.fixtureName = actor.getCommandSenderName();
            field(HologramLibraryClient.class, "libraryActor").set(owner, player.fixtureId);
            ((NBTTagCompound) field(HologramLibraryClient.class, "config").get(owner))
                .setInteger("nativeSavedProtocol", 1);
            var root = Files.createTempDirectory("gtmb-native-remote-full-");
            var lib = new HologramClientLibrary(root);
            var prior = field(HologramClientRemoteNativePlacement.class, "wire").get(null);
            String id = "local_12345678-1234-1234-1234-123456789012";
            Files.createDirectories(lib.directory());
            var remoteRoot = Files.createDirectory(root.resolve("dedicated-world"));
            try (var bridge = server
                .remote(remoteRoot, m -> HologramClientRemoteNativePlacement.receive(m, connection))) {
                server.saveMultiFrameNative(lib.directory(), id);
                var src = owner.readSavedDisplaySource(id, lib.savedDisplayPath(id), false, 0, 0);
                int expected = Math.toIntExact(Files.size(src.nativeReceipt.source));
                HologramClientRemoteNativePlacement.install(bridge::send);
                server.arm();
                long totalSource = expected;
                actor.posX = 160; // Remote BEGIN and continued world execution exceed positioning reach.
                for (int round = 0; round < 7; round++) {
                    if (round == 2) {
                        var old = src;
                        var def = old.state.nativeViewport.draft.revision()
                            .source();
                        placement.install(old);
                        server.replaceNative(lib.directory(), id, 5);
                        src = owner.readSavedDisplaySource(id, lib.savedDisplayPath(id), false, 0, 0);
                        var pinned = def.spatial()
                            .atPosition(3, 1, 0);
                        SimpleAssert.that(pinned != null, "old projected source stays usable after gallery eviction");
                        placement.clear();
                        try {
                            def.retain();
                            throw new AssertionError(
                                "retired gallery source still open after last presentation released");
                        } catch (IllegalStateException released) {}
                        SimpleAssert.that(
                            !src.nativeReceipt.physicalHash.equals(old.nativeReceipt.physicalHash),
                            "same logical id edits have distinct source revisions");
                        totalSource += Files.size(src.nativeReceipt.source);
                    }
                    if (round == 6) {
                        server.replaceNative(lib.directory(), id, 7);
                        src = owner.readSavedDisplaySource(id, lib.savedDisplayPath(id), false, 0, 0);
                    }
                    int progressBefore = bridge.progress, needsBefore = bridge.needs;
                    boolean superseded = false, newProjection = false;
                    HologramState newerDisplay = null;
                    Object newerLease = null;
                    if (round == 4) actor.capabilities.allowEdit = false;
                    if (round == 5) actor.capabilities.allowEdit = true;

                    placement.install(src);
                    field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
                    field(HologramLocalPlacement.class, "anchor").set(placement, new int[] { 8, 64, 8 + round });
                    int[] requestedBindings = round == 0 ? new int[] { 2, 7 * 64 + 3 } : new int[0];
                    var frozen = placement.freeze(HologramLocalPlacement.reviewOptions());
                    var confirmed = HologramLibraryMaterialReviewScreen
                        .paymentChoice(frozen, round == 0 && savedVoucherChoice(frozen), requestedBindings);
                    SimpleAssert.that(
                        confirmed != null && !HologramLibraryMaterialReviewScreen
                            .dispatchRefused(LocalAction.SUBMIT, () -> confirmed, owner::prepareLocalPlacement),
                        "real remote saved-source material submit is enabled");
                    field(HologramLocalPlacement.class, "submitting").setBoolean(placement, true);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (HologramBackgroundTasks.busy() && System.nanoTime() < deadline) {
                        client.drain();
                        server.serverQueue();
                        bridge.tick();
                        if (round == 6 && !superseded && bridge.needs > needsBefore) {
                            server.replaceNative(lib.directory(), id, 9);
                            superseded = true;
                        }
                        HologramClientRemoteNativePlacement.tick(client);
                        if (round == 3 && bridge.progress > progressBefore) HologramBackgroundTasks.cancel();
                        server.serverQueue();
                        server.nativeWorld();
                        bridge.tick();
                        if ((round == 1 && bridge.complete == 2 || round == 3 && bridge.cancelled == 1)
                            && !newProjection) {
                            placement.install(src);
                            field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
                            field(HologramLocalPlacement.class, "anchor").set(placement, new int[] { 21, 64, 21 });
                            field(HologramLocalPlacement.class, "submitting").setBoolean(placement, true);
                            newerDisplay = placement.display();
                            newerLease = field(HologramLocalPlacement.class, "nativeLease").get(placement);
                            newProjection = true;
                        }
                        HologramClientRemoteNativePlacement.tick(client);
                        Thread.sleep(1);
                    }
                    server.serverQueue();
                    bridge.tick();
                    client.drain();
                    int jobs = Math.min(round + 1, 3);
                    SimpleAssert.that(
                        !HologramBackgroundTasks.busy()
                            && placement.active() == (round == 1 || round >= 3 && round <= 5)
                            && server.writes() == jobs * 3
                            && server.stock() == 9 - jobs * 3
                            && !server.pendingPayment(),
                        "remote button → cache → server transaction → HUD (round " + round
                            + "): "
                            + HologramBackgroundTasks.status());
                    if (round == 1 || round == 3) SimpleAssert.that(
                        newProjection && placement.display() == newerDisplay
                            && placement.locked()
                            && placement.submitting()
                            && !field(newerLease.getClass(), "released").getBoolean(newerLease),
                        "late remote " + (round == 1 ? "COMPLETE" : "CANCELLED")
                            + " cannot clear, unlock or retry the newly installed projection");
                    else if (round >= 3) SimpleAssert.that(
                        !placement.submitting(),
                        "current remote terminal either retires cancellation or keeps failure retryable");
                    if (round < 3) SimpleAssert.that(
                        server.at(7, 64, 8 + round, Blocks.stone) && server.at(9, 64, 8 + round, Blocks.stone)
                            && server.at(round == 2 ? 13 : 11, 65, 8 + round, Blocks.stone)
                            && (round != 2 || server.at(11, 65, 10, Blocks.air)),
                        "edited same-id same-count file builds B's exact coordinates, not A");
                    if (round >= 3) SimpleAssert.that(
                        server.at(7, 64, 8 + round, Blocks.air) && bridge.cancelled == (round == 6 ? 2 : 1)
                            && bridge.failed == Math.min(2, round - 3),
                        "round " + round
                            + " cancellation="
                            + bridge.cancelled
                            + " failures="
                            + bridge.failed
                            + "; refusals neither debit nor place");
                    SimpleAssert.that(
                        bridge.begins == round + 1 && bridge.bytes == totalSource
                            && bridge.needs >= 1
                            && bridge.complete == jobs,
                        "cold transfer exact native source only; same actor hot transfer zero, no PNG/old frame");
                    if (round == 0) {
                        SimpleAssert.that(
                            expected > 2 * 24576 && bridge.frames == (expected + 24575) / 24576,
                            "multi-frame saved source crosses actual FML handler");
                        bridge.checkVoucher(true); // Stone is not decorative: same exact stock debits without a
                                                   // voucher.
                        bridge.checkBindings(requestedBindings);
                        bridge.replayLast();
                    }
                }
                // Reopen the currently saved generation. This time the REAL material-page submit must be
                // admitted by both the client and the dedicated handler despite no stock and no selected providers.
                for (boolean serverCreative : new boolean[] { false, true }) {
                    player.capabilities.isCreativeMode = true;
                    actor.capabilities.isCreativeMode = serverCreative;
                    var noSources = com.miaokatze.gtmb.hologram.HologramMaterials.read(actor.getHeldItem());
                    noSources.main = noSources.containers = noSources.me = false;
                    com.miaokatze.gtmb.hologram.HologramMaterials.write(actor.getHeldItem(), noSources);
                    com.miaokatze.gtmb.hologram.HologramMaterials.write(player.getHeldItem(), noSources);
                    placement.install(owner.readSavedDisplaySource(id, lib.savedDisplayPath(id), false, 0, 0));
                    field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
                    field(HologramLocalPlacement.class, "anchor").set(placement, new int[] { 9, 64, 8 });
                    var free = HologramLibraryMaterialReviewScreen
                        .paymentChoice(placement.freeze(HologramLocalPlacement.reviewOptions()), true);
                    SimpleAssert.that(
                        !HologramLibraryMaterialReviewScreen
                            .dispatchRefused(LocalAction.SUBMIT, () -> free, owner::prepareLocalPlacement),
                        "creative material-page submit uses the same dedicated protocol");
                    field(HologramLocalPlacement.class, "submitting").setBoolean(placement, true);
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (HologramBackgroundTasks.busy() && System.nanoTime() < until) {
                        client.drain();
                        server.serverQueue();
                        bridge.tick();
                        HologramClientRemoteNativePlacement.tick(client);
                        server.serverQueue();
                        server.nativeWorld();
                        bridge.tick();
                        HologramClientRemoteNativePlacement.tick(client);
                        Thread.sleep(1);
                    }
                    server.serverQueue();
                    bridge.tick();
                    client.drain();
                    SimpleAssert.that(
                        !HologramBackgroundTasks.busy() && server.stock() == 0
                            && !server.pendingPayment()
                            && bridge.complete == (serverCreative ? 4 : 3)
                            && server.writes() == (serverCreative ? 12 : 9)
                            && server.at(8, 64, 8, serverCreative ? Blocks.stone : Blocks.air)
                            && server.at(10, 64, 8, serverCreative ? Blocks.stone : Blocks.air),
                        "only authentic server creative can build without providers or vouchers; world and balances verified: "
                            + serverCreative
                            + " "
                            + HologramBackgroundTasks.status());
                }
                System.out.println(
                    "[GTMB-NATIVE-REMOTE] creative=3 real placements zero stock/zero voucher; server survival rejects mismatched client mode");
                System.out.println(
                    "[GTMB-NATIVE-REMOTE] coldSourceBytes=" + expected
                        + " hotSourceBytes=0 FML-server-handler terminal-sink-fault recovered editedSameIdLayoutVerified actualWrites=9 actualDebits=9 commits=3 cancels=1 authorizationRefusals=1 shortageRefusals=1 staleDuringMissNoBody=1");
            } finally {
                field(HologramClientRemoteNativePlacement.class, "wire").set(null, prior);
                try (var files = Files.walk(root)) {
                    for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                        Files.deleteIfExists(path);
                }
            }
        }

        void nativeVaried(ConnectedWorld server) throws Exception {
            var actor = server.nativeActor();
            var held = player.getHeldItem();
            actor.getHeldItem()
                .getTagCompound()
                .setString("gtitHologramToolId", HologramLibraryClient.projectorId(held));
            com.miaokatze.gtmb.hologram.HologramMaterials
                .write(held, com.miaokatze.gtmb.hologram.HologramMaterials.read(actor.getHeldItem()));
            player.fixtureId = actor.getUniqueID();
            player.fixtureName = actor.getCommandSenderName();
            field(HologramLibraryClient.class, "libraryActor").set(owner, player.fixtureId);
            ((NBTTagCompound) field(HologramLibraryClient.class, "config").get(owner))
                .setInteger("nativeSavedProtocol", 1);
            var root = Files.createTempDirectory("gtmb-native-varied-");
            var lib = new HologramClientLibrary(root);
            var prior = field(HologramClientRemoteNativePlacement.class, "wire").get(null);
            String id = "local_12345678-1234-1234-1234-123456789012";
            Files.createDirectories(lib.directory());
            var remoteRoot = Files.createDirectory(root.resolve("dedicated-world"));
            try (var bridge = server
                .remote(remoteRoot, m -> HologramClientRemoteNativePlacement.receive(m, connection))) {
                server.saveVariedLegacy(lib.directory(), id);
                var old = lib.directory()
                    .resolve(id + ".json");
                var oldBytes = Files.readAllBytes(old);
                HologramClientRemoteNativePlacement.install(bridge::send);
                server.arm();
                var legacy = owner.readSavedDisplaySource(id, lib.savedDisplayPath(id), false, 0, 0);
                // Legacy can still be opened. Even a manually staged projection cannot submit its old bytes.
                if (legacy.state == null) {
                    var parsed = new HologramState.Preparation(legacy.snapshot, true, legacy.compactCells);
                    while (!parsed.step(4096, Long.MAX_VALUE)) {}
                    legacy.state = parsed.result();
                }
                placement.install(legacy);
                field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
                field(HologramLocalPlacement.class, "anchor").set(placement, new int[] { 8, 64, 8 });
                var refused = placement.freeze(HologramLocalPlacement.reviewOptions());
                SimpleAssert.that(
                    refused != null && !owner.prepareLocalPlacement(refused, true)
                        && bridge.begins == 0
                        && server.writes() == 0,
                    "old source cannot submit or emit a construction packet");
                placement.clear();
                SimpleAssert.that(
                    lib.commitSelectedNativeUpgrade(id, () -> true)
                        .contains("已转换") && java.util.Arrays.equals(oldBytes, Files.readAllBytes(old)),
                    "formal converter atomically saves native without touching old file");
                var src = owner.readSavedDisplaySource(id, lib.savedDisplayPath(id), false, 0, 0);
                placement.install(src);
                field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
                field(HologramLocalPlacement.class, "anchor").set(placement, new int[] { 8, 64, 8 });
                var confirmed = placement.freeze(HologramLocalPlacement.reviewOptions());
                SimpleAssert.that(
                    confirmed != null && !HologramLibraryMaterialReviewScreen
                        .dispatchRefused(LocalAction.SUBMIT, () -> confirmed, owner::prepareLocalPlacement),
                    "formal material confirmation accepts non-whitelist native targets");
                field(HologramLocalPlacement.class, "submitting").setBoolean(placement, true);
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (HologramBackgroundTasks.busy() && System.nanoTime() < end) {
                    client.drain();
                    server.serverQueue();
                    bridge.tick();
                    HologramClientRemoteNativePlacement.tick(client);
                    server.serverQueue();
                    server.nativeWorld();
                    bridge.tick();
                    HologramClientRemoteNativePlacement.tick(client);
                    Thread.sleep(1);
                }
                SimpleAssert.that(
                    bridge.complete == 1 && !HologramBackgroundTasks.busy()
                        && !placement.active()
                        && server.writes() == 2
                        && server.variedPlaced(8, 64, 8)
                        && server.variedExactlyPaid(),
                    "wool metadata and registry-backed fixture mod shell both wrote and paid exactly: "
                        + HologramBackgroundTasks.status());
                System.out.println(
                    "[GTMB-NATIVE-VARIED] formal explicit legacy migration; wool:14 and registered ItemBlock subclass fixture meta12 placed; meta11 stock unchanged");
            } finally {
                field(HologramClientRemoteNativePlacement.class, "wire").set(null, prior);
                try (var files = Files.walk(root)) {
                    for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                        Files.deleteIfExists(path);
                }
            }
        }

        void nativeConnected(ConnectedWorld server, boolean satisfied) throws Exception {
            nativeConnected(server, satisfied, false, false);
        }

        void nativeConnected(ConnectedWorld server, boolean satisfied, boolean cancel, boolean replaceProjection)
            throws Exception {
            var actor = server.nativeActor();
            String tool = HologramLibraryClient.projectorId(player.getHeldItem());
            actor.getHeldItem()
                .getTagCompound()
                .setString("gtitHologramToolId", tool);
            com.miaokatze.gtmb.hologram.HologramMaterials
                .write(player.getHeldItem(), com.miaokatze.gtmb.hologram.HologramMaterials.read(actor.getHeldItem()));
            player.fixtureId = actor.getUniqueID();
            player.fixtureName = actor.getCommandSenderName();
            field(HologramLibraryClient.class, "libraryActor").set(owner, player.fixtureId);
            var integrated = (net.minecraft.server.integrated.IntegratedServer) unsafe
                .allocateInstance(net.minecraft.server.integrated.IntegratedServer.class);
            var players = (net.minecraft.server.integrated.IntegratedPlayerList) unsafe
                .allocateInstance(net.minecraft.server.integrated.IntegratedPlayerList.class);
            field(net.minecraft.server.management.ServerConfigurationManager.class, "playerEntityList")
                .set(players, new ArrayList<>(java.util.Collections.singletonList(actor)));
            integrated.func_152361_a(players);
            integrated.setServerOwner(player.fixtureName);
            field(net.minecraft.server.MinecraftServer.class, "serverRunning").setBoolean(integrated, true);
            field(Minecraft.class, "theIntegratedServer").set(client, integrated);
            field(Minecraft.class, "integratedServerIsRunning").setBoolean(client, true);
            Path dir = Files.createTempDirectory("gtmb-native-connected-");
            var oldWire = field(HologramClientRemoteNativePlacement.class, "wire").get(null);
            var sends = new java.util.concurrent.atomic.AtomicInteger();
            HologramClientRemoteNativePlacement.install(m -> sends.incrementAndGet());
            try {
                var library = new HologramClientLibrary(dir);
                String id = "local_12345678-1234-1234-1234-123456789012";
                Files.createDirectories(library.directory());
                server.saveNative(library.directory(), id, 257);
                var src = owner.readSavedDisplaySource(id, library.savedDisplayPath(id), false, 0, 0);
                placement.install(src);
                Object originalLease = field(HologramLocalPlacement.class, "nativeLease").get(placement);
                field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
                field(HologramLocalPlacement.class, "anchor").set(placement, new int[] { 8, 64, 8 });
                server.seedStone(7, 64, 8);
                if (satisfied) {
                    server.seedStone(9, 64, 8);
                    server.seedStone(265, 65, 8);
                }
                server.arm();
                var confirmed = placement.freeze(HologramLocalPlacement.reviewOptions());
                SimpleAssert.that(
                    confirmed != null && !HologramLibraryMaterialReviewScreen
                        .dispatchRefused(LocalAction.SUBMIT, () -> confirmed, owner::prepareLocalPlacement),
                    "positive native material submit");
                field(HologramLocalPlacement.class, "submitting").setBoolean(placement, true);
                boolean cancelled = false, replaced = false;
                HologramState newerDisplay = null;
                Object newerLease = null;
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (HologramBackgroundTasks.busy() && System.nanoTime() < end) {
                    client.drain();
                    server.serverQueue();
                    Object active = field(HologramClientNativePlacement.class, "active").get(null);
                    if (active != null) {
                        Object attempt = field(active.getClass(), "attempt").get(active);
                        if (field(attempt.getClass(), "executionAccepted").getBoolean(attempt)) {
                            if (cancel && !cancelled) {
                                HologramBackgroundTasks.cancel();
                                cancelled = true;
                            }
                            if (replaceProjection && !replaced) {
                                placement.install(src);
                                field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
                                field(HologramLocalPlacement.class, "anchor").set(placement, new int[] { 21, 64, 21 });
                                field(HologramLocalPlacement.class, "submitting").setBoolean(placement, true);
                                newerDisplay = placement.display();
                                newerLease = field(HologramLocalPlacement.class, "nativeLease").get(placement);
                                replaced = true;
                            }
                        }
                    }
                    server.nativeWorld();
                    HologramClientNativePlacement.tick(client);
                    Thread.sleep(1);
                }
                SimpleAssert.that(
                    !HologramBackgroundTasks.busy() && placement.active() == replaceProjection
                        && sends.get() == 0
                        && player.closedScreens == (cancel || replaceProjection ? 0 : 1)
                        && server.stock() == (cancel ? 2 : 0)
                        && server.writes() == (cancel || satisfied ? 0 : 2)
                        && server.at(265, 65, 8, cancel ? Blocks.air : Blocks.stone)
                        && server.at(9, 64, 8, cancel ? Blocks.air : Blocks.stone)
                        && !server.pendingPayment(),
                    "native submit→authoritative settlement→HUD, current cancellation and stale terminal ownership; "
                        + HologramBackgroundTasks.status());
                SimpleAssert.that(!cancel || cancelled, "cancellation came from the actual admitted task's HUD action");
                SimpleAssert.that(
                    field(originalLease.getClass(), "released").getBoolean(originalLease),
                    "the retired original presentation releases its native lease");
                HologramClientNativePlacement.tick(client);
                placement.tick();
                if (replaceProjection) SimpleAssert.that(
                    replaced && placement.display() == newerDisplay
                        && placement.locked()
                        && placement.submitting()
                        && !field(newerLease.getClass(), "released").getBoolean(newerLease),
                    "late or repeated integrated terminal never clears, unlocks or retries the new projection");
                else SimpleAssert.that(
                    !placement.active() && !placement.submitting(),
                    "a repeated terminal and a held projector never revive a cancelled or completed projection");
            } finally {
                field(HologramClientRemoteNativePlacement.class, "wire").set(null, oldWire);
                com.miaokatze.gtmb.hologram.HologramNativeLocalConstruction.stopServer();
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                        Files.deleteIfExists(path);
                }
            }
        }

        @Override
        public void close() throws Exception {
            ThreadPoolExecutor worker = (ThreadPoolExecutor) field(HologramLibraryClient.class, "worker").get(owner);
            field(HologramLibraryClient.class, "generation").setLong(owner, generation + 1);
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
            client.drain();
            placement.clear();
            for (int i = restore.size() - 1; i >= 0; i--) restore.get(i)
                .restore();
            singleton.set(null, previousMinecraft);
        }
    }

    private static final class HeadlessClient extends Minecraft {

        Queue<ListenableFutureTask<?>> tasks;

        private HeadlessClient() {
            super(null, 1, 1, false, false, null, null, null, null, "fixture", null, "fixture");
            throw new AssertionError("Unsafe-only fixture; no client is launched");
        }

        @Override
        public ListenableFuture func_152344_a(Runnable action) {
            ListenableFutureTask<?> task = ListenableFutureTask.create(action, null);
            tasks.add(task);
            return task;
        }

        @Override
        public void displayGuiScreen(GuiScreen screen) {
            currentScreen = screen;
        }

        void drain() throws Exception {
            ListenableFutureTask<?> task;
            while ((task = tasks.poll()) != null) {
                task.run();
                task.get();
            }
        }
    }

    private static final class HeadlessPlayer extends EntityClientPlayerMP {

        int closedScreens;

        @Override
        public void closeScreen() {
            closedScreens++;
            Minecraft.getMinecraft()
                .displayGuiScreen(null);
        }

        int sounds;
        UUID fixtureId;
        String fixtureName;

        @Override
        public String getCommandSenderName() {
            return fixtureName == null ? "Fixture" : fixtureName;
        }

        private HeadlessPlayer() {
            super(null, null, null, null, null);
            throw new AssertionError("Unsafe-only fixture");
        }

        @Override
        public UUID getUniqueID() {
            return fixtureId == null ? new UUID(123, 456) : fixtureId;
        }

        @Override
        public void addChatMessage(IChatComponent component) {}

        @Override
        public void playSound(String name, float volume, float pitch) {
            sounds++;
        }
    }

    private static final class HeadlessWorld extends WorldClient {

        private HeadlessWorld() {
            super(null, null, 0, null, null);
            throw new AssertionError("Unsafe-only fixture");
        }

        @Override
        public int getHeight() {
            return 256;
        }
    }

    /** Restore existing global owner state after an offline fixture, including counters and references. */
    private static final class SavedFields {

        final Object target;
        final List<Field> fields = new ArrayList<>();
        final List<Object> values = new ArrayList<>();

        SavedFields(Class<?> type, Object target) throws Exception {
            this.target = target;
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isFinal(field.getModifiers())
                    || Modifier.isStatic(field.getModifiers()) != (target == null)) continue;
                field.setAccessible(true);
                fields.add(field);
                values.add(field.get(target));
            }
        }

        void restore() throws Exception {
            for (int i = 0; i < fields.size(); i++) fields.get(i)
                .set(target, values.get(i));
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field minecraftSingleton() throws NoSuchFieldException {
        for (Field field : Minecraft.class.getDeclaredFields())
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Minecraft.class) {
                field.setAccessible(true);
                return field;
            }
        throw new NoSuchFieldException("Minecraft singleton");
    }
}
