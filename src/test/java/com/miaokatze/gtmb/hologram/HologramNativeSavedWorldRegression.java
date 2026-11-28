// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.INetHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Actual encoded Forge ingress, shared lifecycle and serving owner, with an isolated world and reply sink. */
public final class HologramNativeSavedWorldRegression {

    private HologramNativeSavedWorldRegression() {}

    @FunctionalInterface
    private interface IsolatedWork {

        void run(sun.misc.Unsafe unsafe) throws Exception;
    }

    private static boolean installedAeFixture;

    public static void withOfflineRegistries(Runnable work) throws Exception {
        isolated(unsafe -> work.run());
    }

    private static void isolated(IsolatedWork work) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        // This detached suite has no FML mod-discovery phase. Native AE/StructureLib source registries still
        // read Loader's mod map during static initialization; provide the empty discovered-mod set, not a
        // substitute material implementation.
        var names = cpw.mods.fml.common.Loader.class.getDeclaredField("namedMods");
        names.setAccessible(true);
        var loader = cpw.mods.fml.common.Loader.instance();
        Object previousNames = names.get(loader);
        if (previousNames == null) names.set(loader, new HashMap<>());
        if (!installedAeFixture) {
            installedAeFixture = true;
            net.minecraft.launchwrapper.Launch.classLoader
                .registerTransformer(HologramSourceRegistryFixtureTransformer.class.getName());
        }
        appeng.core.AEConfig previousAeConfig = appeng.core.AEConfig.instance;
        if (previousAeConfig == null) {
            var config = (appeng.core.AEConfig) unsafe.allocateInstance(appeng.core.AEConfig.class);
            Field flags = appeng.core.AEConfig.class.getDeclaredField("featureFlags");
            flags.setAccessible(true);
            flags.set(config, java.util.EnumSet.noneOf(appeng.core.features.AEFeature.class));
            config.grinderOres = new String[0];
            appeng.core.AEConfig.instance = config;
        }
        Path old = HologramConstructionJournal.STORAGE_ROOT.get();
        Path root = Files.createTempDirectory("gtmb-projected-server-");
        HologramConstructionJournal.STORAGE_ROOT.set(root);
        try {
            work.run(unsafe);
        } finally {
            appeng.core.AEConfig.instance = previousAeConfig;
            names.set(loader, previousNames);
            if (old == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(old);
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    public static void runNativeLocalPlacement() throws Exception {
        isolated(unsafe -> {
            for (String mode : new String[] { "complete", "permission", "cancel", "changed", "changedBeforeAdmission",
                "changedWhilePreparing", "changedAfterAdmission", "cancelPreparing", "sourceChanged", "creative",
                "creativeRevoked", "creativeWithoutEdit" }) {
                Path root = Files.createTempDirectory("gtmb-native-local-placement-");
                String logical = "local_12345678-1234-1234-1234-123456789012";
                try (Fixture f = new Fixture(unsafe, 5)) {
                    var original = nativeLocalSource(root, logical, 2, null);
                    try (var source = com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.open(root, logical)) {
                        var draft = new HologramNativeBlueprintDraft(
                            new com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintEdits(source));
                        checkNativeMode(mode, f, unsafe, root, logical, original, draft);
                    }
                } finally {
                    HologramNativeLocalConstruction.stopServer();
                    try (Stream<Path> paths = Files.walk(root)) {
                        for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                            Files.deleteIfExists(path);
                    }
                }
            }
            nativeVoucher(unsafe);
        });
    }

    private static void nativeVoucher(sun.misc.Unsafe unsafe) throws Exception {
        var previous = com.miaokatze.gtmb.register.DecorationVoucherRegistry.voucher;
        var voucher = new com.miaokatze.gtmb.common.items.hologram.ItemDecorationVoucher();
        com.miaokatze.gtmb.register.DecorationVoucherRegistry.voucher = voucher;
        try {
            for (String mode : new String[] { "paid", "already", "refused", "missing" }) {
                Path root = Files.createTempDirectory("gtmb-real-voucher-");
                String logical = "local_12345678-1234-1234-1234-123456789012";
                try (Fixture f = new Fixture(unsafe, 4)) {
                    var receipt = nativeSourceJson(
                        root,
                        logical,
                        "{\"schema\":2,\"controllerType\":\"area\",\"id\":\"" + logical
                            + "\",\"cells\":["
                            + "{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:wool\",\"item\":\"minecraft:wool\",\"metadata\":14,\"damage\":14},"
                            + "{\"x\":1,\"y\":0,\"z\":0,\"block\":\"minecraft:wool\",\"item\":\"minecraft:wool\",\"metadata\":11,\"damage\":11},"
                            + "{\"x\":2,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\"}]}",
                        null);
                    try (var source = com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.open(root, logical)) {
                        if (!mode.equals("missing")) f.player.inventory.mainInventory[2] = new ItemStack(voucher, 2);
                        if (mode.equals("already")) {
                            f.world.seed(8, 64, 8, Blocks.wool, 14);
                            f.world.seed(9, 64, 8, Blocks.wool, 11);
                        }
                        String id = new UUID(23, 17).toString();
                        f.player.getHeldItem()
                            .getTagCompound()
                            .setString(HologramSessionKeys.TOOL_ID, id);
                        f.world.arm();
                        f.login();
                        f.player.capabilities.allowEdit = !mode.equals("refused");
                        var draft = new HologramNativeBlueprintDraft(
                            new com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintEdits(source));
                        var ready = HologramNativeLocalConstruction.prepare(draft, receipt, () -> true);
                        var attempt = new HologramNativeLocalConstruction.Attempt(1);
                        HologramNativeLocalConstruction.submit(
                            attempt,
                            ready,
                            () -> f.player,
                            id,
                            f.player.dimension,
                            new int[] { 8, 64, 8 },
                            new int[0],
                            HologramProjectedMainPolicy.settingsKey(HologramMaterials.read(f.player.getHeldItem())),
                            true);
                        com.miaokatze.gtmb.util.ServerTaskScheduler.INSTANCE.onServerTick(
                            new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                                cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
                        HologramNativeLocalConstruction.tick(Long.MAX_VALUE);
                        var result = attempt.result();
                        int remaining = f.player.inventory.mainInventory[2] == null ? 0
                            : f.player.inventory.mainInventory[2].stackSize;
                        if (mode.equals("missing")) {
                            require(
                                result.phase == HologramNativeLocalConstruction.Phase.FAILED && f.stock() == 4
                                    && remaining == 0
                                    && f.world.writes()
                                        .isEmpty(),
                                "missing requested voucher never waives or consumes stock");
                        } else if (mode.equals("refused")) {
                            require(
                                result.phase == HologramNativeLocalConstruction.Phase.FAILED && f.stock() == 4
                                    && remaining == 2
                                    && f.world.writes()
                                        .isEmpty(),
                                "permission denial cannot burn a voucher");
                        } else {
                            require(
                                result.phase == HologramNativeLocalConstruction.Phase.COMPLETE && f.stock() == 3
                                    && f.world.getBlock(8, 64, 8) == Blocks.wool
                                    && f.world.getBlockMetadata(8, 64, 8) == 14
                                    && f.world.getBlock(9, 64, 8) == Blocks.wool
                                    && f.world.getBlockMetadata(9, 64, 8) == 11
                                    && f.world.getBlock(10, 64, 8) == Blocks.stone
                                    && remaining == (mode.equals("already") ? 2 : 1),
                                "native voucher pays for exactly the two eligible shells, never stone or satisfied cells: "
                                    + mode
                                    + " "
                                    + result.phase
                                    + " "
                                    + result.detail
                                    + " stock="
                                    + f.stock()
                                    + " tickets="
                                    + remaining);
                        }
                    }
                } finally {
                    HologramNativeLocalConstruction.stopServer();
                    try (Stream<Path> paths = Files.walk(root)) {
                        for (Path file : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                            Files.deleteIfExists(file);
                    }
                }
            }
        } finally {
            com.miaokatze.gtmb.register.DecorationVoucherRegistry.voucher = previous;
        }
    }

    private static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt nativeLocalSource(Path root,
        String logical, int changed, com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt expected)
        throws IOException {
        String json = "{\"schema\":2,\"blueprint\":{\"schema\":2,\"controllerType\":\"area\",\"id\":\"" + logical
            + "\",\"name\":\"Native local E2E\",\"cells\":["
            + "{\"x\":-1,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\"},"
            + "{\"x\":1,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\"},"
            + "{\"x\":"
            + changed
            + ",\"y\":1,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\"}]}}";
        return nativeSourceJson(root, logical, json, expected);
    }

    private static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt nativeSourceJson(Path root,
        String logical, String json, com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt expected)
        throws IOException {
        byte[] bytes = com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintMigration
            .convert(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).bytes;
        byte[] png = com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCodecRegression.png();
        if (expected == null) {
            var staged = HologramNativeBlueprintStorage
                .prepareAuthoredSave(root, logical, bytes, HologramBlueprintArtifact.hash(bytes), png, () -> true);
            return HologramNativeBlueprintStorage.activate(root, staged, staged.revision, () -> true);
        }
        Path temp = Files.createTempFile("gtmb-native-revision-B-", ".gtnb");
        try {
            Files.write(temp, bytes);
            try (var definition = com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition.open(temp)) {
                var pending = HologramNativeBlueprintStorage
                    .prepareSave(root, logical, definition, png, () -> true, expected, false);
                return HologramNativeBlueprintStorage.activate(root, pending, pending.revision, () -> true);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void checkNativeMode(String mode, Fixture f, sun.misc.Unsafe unsafe, Path root, String logical,
        com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt original, HologramNativeBlueprintDraft draft)
        throws Exception {
        if ("changed".equals(mode)) {
            var replacement = nativeLocalSource(root, logical, 4, original);
            require(
                !replacement.physicalHash.equals(original.physicalHash),
                "same logical id has two distinguishable versions");
            try {
                HologramNativeLocalConstruction.prepare(draft, original, () -> true);
                throw new AssertionError("stale source borrowed current slot");
            } catch (IOException expected) {
                require(
                    expected.getMessage()
                        .contains("重新打开材料页") && f.stock() == 5
                        && f.world.writes()
                            .isEmpty(),
                    "same ID and material totals but different coordinates require explicit fresh review, no effects");
            }
            return;
        }
        String id = new UUID(40, 50).toString();
        f.player.getHeldItem()
            .getTagCompound()
            .setString(HologramSessionKeys.TOOL_ID, id);
        f.world.seed(8, 64, 7, Blocks.stone, 0);
        f.world.arm();
        f.login();
        boolean creative = mode.startsWith("creative");
        f.player.capabilities.allowEdit = !"permission".equals(mode) && !"creativeWithoutEdit".equals(mode);
        f.player.capabilities.isCreativeMode = creative;
        if (creative) {
            var noSources = HologramMaterials.read(f.player.getHeldItem());
            noSources.main = noSources.containers = noSources.me = false;
            HologramMaterials.write(f.player.getHeldItem(), noSources);
        }
        var ready = HologramNativeLocalConstruction.prepare(draft, original, () -> true);
        var attempt = new HologramNativeLocalConstruction.Attempt(1);
        HologramNativeLocalConstruction.submit(
            attempt,
            ready,
            () -> f.player,
            id,
            f.player.dimension,
            new int[] { 8, 64, 8 },
            new int[] { 0 },
            HologramProjectedMainPolicy.savedPlacementKey(HologramMaterials.read(f.player.getHeldItem()), creative),
            creative);
        if ("changedBeforeAdmission".equals(mode)) nativeLocalSource(root, logical, 4, original);
        if ("sourceChanged".equals(mode)) {
            var settings = HologramMaterials.read(f.player.getHeldItem());
            settings.priority = (settings.priority + 1) % 3;
            HologramMaterials.write(f.player.getHeldItem(), settings);
        }
        require(
            f.stock() == 5 && f.world.writes()
                .isEmpty(),
            "in-process enqueue has no authorization/payment");
        com.miaokatze.gtmb.util.ServerTaskScheduler.INSTANCE.onServerTick(
            new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
        if ("permission".equals(mode) || "creativeWithoutEdit".equals(mode)
            || "changedBeforeAdmission".equals(mode)
            || "sourceChanged".equals(mode)) {
            require(
                attempt.result().phase == HologramNativeLocalConstruction.Phase.FAILED && f.stock() == 5
                    && f.world.writes()
                        .isEmpty(),
                "real in-process server denies unprivileged player");
            return;
        }
        var preparing = attempt.result();
        require(
            preparing.phase == HologramNativeLocalConstruction.Phase.PREPARING && preparing.total == 3
                && preparing.placed == 0
                && preparing.satisfied == 0
                && !attempt.admitted()
                && f.stock() == 5
                && f.world.writes()
                    .isEmpty()
                && !HologramConstructionJournal.pending(f.player),
            "enqueue acquires the world lease but performs no descriptor admission, writes or debits");
        HologramNativeLocalConstruction.tick(System.nanoTime() - 1);
        require(
            attempt.result().phase == HologramNativeLocalConstruction.Phase.PREPARING
                && attempt.result().total == preparing.total
                && attempt.result().placed == 0
                && attempt.result().satisfied == 0
                && !attempt.admitted()
                && f.stock() == 5
                && f.world.writes()
                    .isEmpty()
                && !HologramConstructionJournal.pending(f.player),
            "expired world deadline keeps saved descriptor preparation pending without effects");
        if ("changedWhilePreparing".equals(mode) || "cancelPreparing".equals(mode)) {
            boolean stale = "changedWhilePreparing".equals(mode);
            if (stale) nativeLocalSource(root, logical, 4, original);
            else require(attempt.stop(), "preparing job can stop before world mutation");
            HologramNativeLocalConstruction.tick(Long.MAX_VALUE);
            var stopped = attempt.result();
            require(
                stopped.phase
                    == (stale ? HologramNativeLocalConstruction.Phase.FAILED
                        : HologramNativeLocalConstruction.Phase.CANCELLED)
                    && stopped.placed == 0
                    && stopped.satisfied == 0
                    && !attempt.admitted()
                    && f.stock() == 5
                    && f.world.writes()
                        .isEmpty()
                    && !HologramConstructionJournal.pending(f.player),
                "stale or stopped preparation cannot debit or write");
            HologramNativeLocalConstruction.tick(Long.MAX_VALUE);
            require(attempt.result() == stopped && !attempt.stop(), "terminal preparation result does not replay");
            return;
        }
        if ("creativeRevoked".equals(mode)) {
            f.player.capabilities.isCreativeMode = false;
            HologramNativeLocalConstruction.tick(Long.MAX_VALUE);
            require(
                attempt.result().phase == HologramNativeLocalConstruction.Phase.CANCELLED && f.stock() == 5
                    && f.world.writes()
                        .isEmpty()
                    && !HologramConstructionJournal.pending(f.player),
                "a server-side mode change invalidates the free construction lease");
            return;
        }
        boolean stopWrite = "cancel".equals(mode);
        boolean changePinnedSource = "changedAfterAdmission".equals(mode);
        var republished = new java.util.concurrent.atomic.AtomicBoolean();
        Field bus = MinecraftForge.class.getDeclaredField("EVENT_BUS");
        Object base = unsafe.staticFieldBase(bus);
        long offset = unsafe.staticFieldOffset(bus);
        EventBus prior = MinecraftForge.EVENT_BUS;
        if (stopWrite || changePinnedSource) unsafe.putObjectVolatile(base, offset, new EventBus() {

            @Override
            public boolean post(Event event) {
                if (event instanceof BlockEvent.PlaceEvent place && place.player == f.player) {
                    if (stopWrite && place.y == 65) attempt.stop();
                    if (changePinnedSource && republished.compareAndSet(false, true)) {
                        require(
                            attempt.admitted()
                                && attempt.result().phase == HologramNativeLocalConstruction.Phase.EXECUTING,
                            "real write event occurs only after the exact saved revision is admitted");
                        try {
                            nativeLocalSource(root, logical, 4, original);
                        } catch (IOException e) {
                            throw new AssertionError("cannot republish the test source during the running job", e);
                        }
                    }
                }
                return false;
            }
        });
        try {
            HologramNativeLocalConstruction.tick(Long.MAX_VALUE); // Exact same service called from the normal world
                                                                  // tick.
        } finally {
            if (stopWrite || changePinnedSource) unsafe.putObjectVolatile(base, offset, prior);
        }
        var done = attempt.result();
        boolean aborted = stopWrite;
        require(!changePinnedSource || republished.get(), "running job actually encountered a changed saved alias");
        require(
            done.phase
                == (aborted ? HologramNativeLocalConstruction.Phase.CANCELLED
                    : HologramNativeLocalConstruction.Phase.COMPLETE)
                && done.placed == (aborted ? 1 : 2)
                && done.satisfied == 1
                && f.stock() == (creative ? 5 : aborted ? 4 : 3)
                && f.world.getBlock(8, 64, 9) == Blocks.stone
                && f.world.getBlock(8, 64, 7) == Blocks.stone
                && f.world.getBlock(9, 64, 8) == Blocks.air
                && f.world.getBlock(8, 65, 10) == (aborted ? Blocks.air : Blocks.stone)
                && !HologramConstructionJournal.pending(f.player),
            "in-process native placement uses exact effects/readback, prefix settlement, rotated coordinates, no projected receipt");
        int writes = f.world.writes()
            .size();
        HologramNativeLocalConstruction.tick(Long.MAX_VALUE);
        require(
            attempt.result() == done && !attempt.stop()
                && f.world.writes()
                    .size() == writes
                && f.stock() == (creative ? 5 : aborted ? 4 : 3),
            "terminal native placement does not write, pay, or cancel again");
        System.out.println(
            "[GTMB-NATIVE-LOCAL] " + mode
                + " writes="
                + done.placed
                + " paid="
                + (5 - f.stock())
                + " preexisting="
                + done.satisfied
                + " projectedNetworkReplies=0");
    }

    @FunctionalInterface
    public interface ConnectedWork {

        void run(ConnectedWorld world) throws Exception;
    }

    /** Test boundary only: keeps production client request bytes, handler, payment and world transactions intact. */
    public static void withConnectedWorld(int stock, ConnectedWork work) throws Exception {
        isolated(unsafe -> {
            try (Fixture fixture = new Fixture(unsafe, stock)) {
                fixture.login();
                work.run(new ConnectedWorld(fixture));
            }
        });
    }

    public static final class ConnectedWorld {

        private final Fixture fixture;

        private ConnectedWorld(Fixture fixture) {
            this.fixture = fixture;
        }

        public NativeRemoteBridge remote(Path saveRoot,
            java.util.function.Consumer<HologramPrivateNativeWire.Reply> receive) {
            return new NativeRemoteBridge(this, saveRoot, receive);
        }

        public EntityPlayerMP nativeActor() {
            return fixture.player;
        }

        public void stepNative() {
            com.miaokatze.gtmb.util.ServerTaskScheduler.INSTANCE.onServerTick(
                new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                    cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
            HologramNativeLocalConstruction.tick(Long.MAX_VALUE);
        }

        public void replaceNative(Path directory, String logical, int x) throws IOException {
            nativeLocalSource(
                directory,
                logical,
                x,
                com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.receipt(directory, logical));
        }

        public void saveVariedLegacy(Path root, String logical) throws IOException {
            String key = "gtmb:plain_mod_shell_fixture";
            net.minecraft.item.Item item = (net.minecraft.item.Item) net.minecraft.item.Item.itemRegistry
                .getObject(key);
            Object registeredBlock = net.minecraft.block.Block.blockRegistry.getObject(key);
            if (item == null || registeredBlock == null || registeredBlock == Blocks.air) {
                int free = 3930;
                while (net.minecraft.item.Item.getItemById(free) != null
                    || net.minecraft.block.Block.getBlockById(free) != Blocks.air
                        && net.minecraft.block.Block.getBlockById(free) != null)
                    free++;
                var block = new net.minecraft.block.Block(net.minecraft.block.material.Material.rock) {

                    @Override
                    public int damageDropped(int meta) {
                        return meta;
                    }
                }.setBlockName("savedOrdinaryModShell");
                item = new net.minecraft.item.ItemBlock(block) {

                    {
                        setHasSubtypes(true);
                    }

                    @Override
                    public int getMetadata(int damage) {
                        return damage;
                    }
                };
                // A detached test has no active FML mod container. The unprefixed registration is what
                // GameRegistry calls *after* adding the owning namespace in a real runtime.
                try {
                    var cls = cpw.mods.fml.common.registry.GameData.class;
                    var main = cls.getDeclaredMethod("getMain");
                    var b = cls
                        .getDeclaredMethod("registerBlock", net.minecraft.block.Block.class, String.class, int.class);
                    var i = cls
                        .getDeclaredMethod("registerItem", net.minecraft.item.Item.class, String.class, int.class);
                    main.setAccessible(true);
                    b.setAccessible(true);
                    i.setAccessible(true);
                    Object data = main.invoke(null);
                    b.invoke(data, block, key, free);
                    i.invoke(data, item, key, free);
                } catch (ReflectiveOperationException e) {
                    throw new IOException("fixture Forge registry", e);
                }
                require(
                    net.minecraft.block.Block.blockRegistry.getObject(key) == block
                        && net.minecraft.item.Item.itemRegistry.getObject(key) == item,
                    "registered mod shell fixture is discoverable by stable canonical name and not just its numeric id");
            }
            fixture.player.inventory.mainInventory[2] = new ItemStack(Blocks.wool, 2, 14);
            fixture.player.inventory.mainInventory[3] = new ItemStack(item, 2, 11);
            fixture.player.inventory.mainInventory[4] = new ItemStack(item, 2, 12);
            String j = "{\"schema\":2,\"blueprint\":{\"schema\":2,\"controllerType\":\"area\",\"id\":\"" + logical
                + "\",\"name\":\"Color and registered mod fixture\",\"cells\":["
                + "{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:wool\",\"item\":\"minecraft:wool\",\"metadata\":14,\"damage\":14},"
                + "{\"x\":1,\"y\":0,\"z\":0,\"block\":\""
                + key
                + "\",\"item\":\""
                + key
                + "\",\"metadata\":12,\"damage\":12}]}}";
            HologramBlueprintArtifact.writePair(
                root.resolve(logical + ".json"),
                j.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCodecRegression.png());
        }

        public boolean variedPlaced(int x, int y, int z) {
            return fixture.world.getBlock(x, y, z) == Blocks.wool && fixture.world.getBlockMetadata(x, y, z) == 14
                && fixture.world.getBlock(x + 1, y, z)
                    == net.minecraft.block.Block.blockRegistry.getObject("gtmb:plain_mod_shell_fixture")
                && fixture.world.getBlockMetadata(x + 1, y, z) == 12;
        }

        public boolean variedExactlyPaid() {
            return fixture.player.inventory.mainInventory[2].stackSize == 1
                && fixture.player.inventory.mainInventory[3].stackSize == 2
                && fixture.player.inventory.mainInventory[4].stackSize == 1;
        }

        public void serverQueue() {
            com.miaokatze.gtmb.util.ServerTaskScheduler.INSTANCE.onServerTick(
                new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                    cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
        }

        public void nativeWorld() {
            HologramNativeLocalConstruction.tick(Long.MAX_VALUE);
        }

        public void saveMultiFrameNative(Path directory, String logical) throws IOException {
            var json = new StringBuilder("{\"schema\":2,\"controllerType\":\"area\",\"cells\":[");
            for (int[] p : new int[][] { { -1, 0 }, { 1, 0 }, { 3, 1 } }) json.append("{\"x\":")
                .append(p[0])
                .append(",\"y\":")
                .append(p[1])
                .append(",\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\"},");
            // Unique per-coordinate annotations are genuine saved content, not transport padding.
            var rnd = new java.util.Random(2247105);
            for (int i = 0; i < 1800; i++) {
                if (i != 0) json.append(',');
                json.append("{\"x\":")
                    .append(i % 96 + 16)
                    .append(",\"y\":0,\"z\":")
                    .append(i / 96 + 8)
                    .append(
                        ",\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"excluded\":true,\"prompt\":\"")
                    .append(new UUID(rnd.nextLong(), rnd.nextLong()))
                    .append("\"}");
            }
            nativeSourceJson(
                directory,
                logical,
                json.append("]}")
                    .toString(),
                null);
        }

        public void saveNative(Path directory, String logical, int otherX) throws IOException {
            nativeLocalSource(directory, logical, otherX, null);
        }

        public int stock() {
            return fixture.stock();
        }

        public int writes() {
            return fixture.world.writes()
                .size();
        }

        public boolean pendingPayment() {
            return HologramConstructionJournal.pending(fixture.player);
        }

        public boolean at(int x, int y, int z, net.minecraft.block.Block block) {
            return fixture.world.getBlock(x, y, z) == block;
        }

        public void seedStone(int x, int y, int z) {
            fixture.world.seed(x, y, z, Blocks.stone, 0);
        }

        public void arm() {
            fixture.world.arm();
        }
    }

    /** Only the absent socket is replaced. Both registered byte codecs and the authenticated service are exercised. */
    public static final class NativeRemoteBridge implements AutoCloseable {

        final ConnectedWorld world;
        final HologramPrivateNativeServer service;
        private final Field live;
        private final MessageContext incoming;
        private final HologramPrivateNativeWire.Server handler = new HologramPrivateNativeWire.Server();
        boolean firstTerminal = true;
        public int bytes, begins, needs, complete, cancelled, failed, progress, frames;
        HologramPrivateNativeWire.Request lastBegin;

        NativeRemoteBridge(ConnectedWorld world, Path root,
            java.util.function.Consumer<HologramPrivateNativeWire.Reply> receive) {
            this.world = world;
            service = new HologramPrivateNativeServer(root, () -> 5, (actor, reply) -> {
                ByteBuf buf = Unpooled.buffer();
                try {
                    reply.toBytes(buf);
                    var decoded = new HologramPrivateNativeWire.Reply();
                    decoded.fromBytes(buf);
                    require(actor == world.fixture.player, "private receipt goes only to authenticated requester");
                    if (decoded.op == HologramPrivateNativeWire.NEED) needs++;
                    if (decoded.op == HologramPrivateNativeWire.COMPLETE) complete++;
                    if (decoded.op == HologramPrivateNativeWire.PROGRESS) progress++;
                    if (decoded.op == HologramPrivateNativeWire.CANCELLED) cancelled++;
                    if (decoded.op == HologramPrivateNativeWire.FAILED) failed++;
                    receive.accept(decoded);
                    if (firstTerminal && decoded.op == HologramPrivateNativeWire.COMPLETE) {
                        firstTerminal = false;
                        throw new IllegalStateException("fixture: connection vanished during terminal reply");
                    }
                } finally {
                    buf.release();
                }
            });
            try {
                if (live.get(null) != null) throw new AssertionError("fixture must not replace an active service");
                Constructor<MessageContext> ctor = MessageContext.class
                    .getDeclaredConstructor(INetHandler.class, Side.class);
                ctor.setAccessible(true);
                incoming = ctor.newInstance(world.fixture.player.playerNetServerHandler, Side.SERVER);
                live.set(null, service);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        }

        {
            try {
                live = HologramPrivateNativeServer.class.getDeclaredField("live");
                live.setAccessible(true);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        }

        public void send(HologramPrivateNativeWire.Request m) {
            ByteBuf buf = Unpooled.buffer();
            try {
                m.toBytes(buf);
                var req = new HologramPrivateNativeWire.Request();
                req.fromBytes(buf);
                if (req.op == HologramPrivateNativeWire.BEGIN) {
                    begins++;
                    lastBegin = req;
                } else if (req.op == HologramPrivateNativeWire.DATA) {
                    require(needs > 0, "no source body before cache miss");
                    bytes += req.bytes.length;
                    frames++;
                }
                handler.onMessage(req, incoming);
            } finally {
                buf.release();
            }
        }

        public void checkVoucher(boolean expected) {
            require(
                lastBegin != null && lastBegin.voucher == expected,
                "saved review voucher bit survived real BEGIN wire");
        }

        public void checkBindings(int[] expected) {
            require(
                lastBegin != null && java.util.Arrays.equals(lastBegin.bindingSlots, expected),
                "formal material intent sent only group/slot options, not source network credentials");
        }

        public void replayLast() {
            int writes = world.writes(), stock = world.stock(), before = complete;
            service.accept(
                HologramWorkspace.captureActionEndpoint(world.fixture.player.playerNetServerHandler),
                lastBegin);
            require(
                complete == before + 1 && world.writes() == writes && world.stock() == stock,
                "replayed BEGIN reproduces terminal receipt without starting or paying");
            complete--; // count distinct successful tasks, not replayed replies
        }

        public void tick() {
            service.tick();
        }

        public void close() {
            try {
                service.close();
            } finally {
                try {
                    if (live.get(null) == service) live.set(null, null);
                } catch (IllegalAccessException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {

        final HologramPlacementFixtureWorld world;
        final EntityPlayerMP player;

        Fixture(sun.misc.Unsafe unsafe, int stock) throws ReflectiveOperationException {
            world = HologramPlacementFixtureWorld.create(unsafe);
            player = createPlayer(unsafe, world, stock);
        }

        void login() {
            HologramWorkspace.publishActionEndpoint(player, false);
        }

        int stock() {
            ItemStack item = player.inventory.mainInventory[1];
            return item == null ? 0 : item.stackSize;
        }

        public void close() {
            HologramWorkspace.revokeActionEndpoint(player);
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static EntityPlayerMP createPlayer(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world, int stock)
        throws ReflectiveOperationException {
        EntityPlayerMP player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "projected-packet-fix");
        player.posY = 64;
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, stock);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.write(player.getHeldItem(), settings);
        return player;
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
