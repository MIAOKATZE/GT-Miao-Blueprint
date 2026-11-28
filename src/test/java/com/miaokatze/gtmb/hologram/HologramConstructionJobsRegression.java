// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.threads.RunnableMachineUpdate;

/** Common queue contracts: request ownership, cancellation, controller ordering and terminal native updates. */
public final class HologramConstructionJobsRegression {

    private HologramConstructionJobsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramConstructionJobsRegression.class, args)) return;
        run();
        System.out.println("HologramConstructionJobsRegression PASS");
    }

    public static void run() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var world = HologramPlacementFixtureWorld.create(unsafe);
            var player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "UnifiedConstruction");
            var session = new HologramSession(player, 0, 64, 0, false, 1, false);
            try {
                var source = new Source(
                    session,
                    java.util.Arrays.asList(
                        new HologramConstructionJobs.Target(0, 64, 0, null, true, true),
                        new HologramConstructionJobs.Target(1, 64, 0, null, true, false),
                        new HologramConstructionJobs.Target(2, 64, 0, null, true, false)));
                var request = UUID.randomUUID();
                var first = HologramConstructionJobs.start(request, 1, source);
                SimpleAssert.that(
                    !HologramConstructionJobs.cancel(player, UUID.randomUUID()),
                    "another request cannot cancel this job");
                HologramConstructionJobs.tick(Long.MAX_VALUE);
                SimpleAssert.eq(
                    java.util.Arrays.asList(1, 2, 0),
                    source.order,
                    "controllers follow all body targets in the common queue");
                SimpleAssert.that(
                    first.end == HologramConstructionJobs.End.COMPLETE && source.finishes == 1,
                    "one completed job has exactly one terminal publication");
                try {
                    HologramConstructionJobs.start(request, 1, new Source(session, Collections.emptyList()));
                    throw new AssertionError("a retired request restarted construction");
                } catch (IOException expected) {}
                var cancelled = new Source(session, Collections.emptyList());
                cancelled.waiting = true;
                var second = HologramConstructionJobs.start(UUID.randomUUID(), 2, cancelled);
                HologramConstructionJobs.tick(Long.MAX_VALUE);
                SimpleAssert.that(
                    HologramConstructionJobs.cancel(player, second.request),
                    "the exact accepted request cancels");
                SimpleAssert.that(
                    second.end == HologramConstructionJobs.End.CANCELLED && cancelled.finishes == 1,
                    "cancel removes the common job once, without a paused continuation");
                var changed = new Source(session, Collections.emptyList());
                var third = HologramConstructionJobs.start(UUID.randomUUID(), 3, changed);
                changed.valid = false;
                HologramConstructionJobs.tick(Long.MAX_VALUE);
                SimpleAssert.that(
                    third.end == HologramConstructionJobs.End.CANCELLED && changed.finishes == 1,
                    "source invalidation terminates before any native operation");
                updates(session);
                HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                    capturedSource(unsafe);
                    try {
                        legacyFormationBoundary(unsafe);
                    } catch (IOException | ReflectiveOperationException failure) {
                        throw new AssertionError("legacy native formation boundary", failure);
                    }
                    controllerCallbacks(unsafe);
                    admissionClose(unsafe);
                });
            } finally {
                HologramConstructionJobs.stopAll("fixture cleanup");
                player.playerNetServerHandler.netManager.channel()
                    .close();
            }
        } catch (Exception failure) {
            throw new AssertionError("unified construction jobs", failure);
        }
    }

    private static void admissionClose(sun.misc.Unsafe unsafe) {
        try {
            var world = HologramPlacementFixtureWorld.create(unsafe);
            var fake = (net.minecraftforge.common.util.FakePlayer) unsafe
                .allocateInstance(net.minecraftforge.common.util.FakePlayer.class);
            UUID actor = UUID.randomUUID();
            for (Field identity : net.minecraft.entity.Entity.class.getDeclaredFields())
                if (identity.getType() == UUID.class) {
                    identity.setAccessible(true);
                    identity.set(fake, actor);
                }
            for (Field profile : net.minecraft.entity.player.EntityPlayer.class.getDeclaredFields())
                if (profile.getType() == com.mojang.authlib.GameProfile.class) {
                    profile.setAccessible(true);
                    profile.set(fake, new com.mojang.authlib.GameProfile(actor, "DisconnectedBuilder"));
                }
            fake.worldObj = world;
            fake.posY = 64;
            fake.inventory = new net.minecraft.entity.player.InventoryPlayer(fake);
            fake.inventory.mainInventory[0] = new net.minecraft.item.ItemStack(
                new com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector());
            fake.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
            fake.capabilities.allowEdit = true;
            fake.inventoryContainer = new net.minecraft.inventory.ContainerPlayer(fake.inventory, false, fake);
            fake.openContainer = fake.inventoryContainer;
            var seat = new HologramSession(fake, 0, 64, 0, false, 1);
            var source = new Source(
                seat,
                Collections.singletonList(new HologramConstructionJobs.Target(0, 64, 0, null, true, false)));
            var job = HologramConstructionJobs.start(UUID.randomUUID(), 0, source);
            SimpleAssert.that(
                fake.playerNetServerHandler == null && job.current(),
                "a native FakePlayer with containers but no connection is admitted without inventing a network GUI");
            job.step(Long.MAX_VALUE);
            SimpleAssert.that(
                job.end == HologramConstructionJobs.End.COMPLETE && source.finishes == 1,
                "the disconnected actor reaches the real common scheduler and one terminal result");

            var player = closePlayer(unsafe, world, "AdmissionCallbacks");
            try {
                var liveSeat = new HologramSession(player, 0, 64, 0, false, 1);
                var cancelledSource = new Source(liveSeat, Collections.emptyList());
                player.closeAction = () -> {
                    var accepted = HologramConstructionJobs.find(player);
                    SimpleAssert.that(
                        accepted != null && accepted.executing,
                        "native close observes this accepted but non-executable job");
                    cancelledSource.onCurrent = () -> HologramConstructionJobs.cancel(player, accepted.request);
                    HologramConstructionJobs.tick(Long.MAX_VALUE);
                    SimpleAssert.eq(
                        0,
                        cancelledSource.prepares,
                        "a tick reentered from native close cannot prepare or mutate this job");
                };
                UUID cancelledRequest = UUID.randomUUID();
                var cancelled = HologramConstructionJobs.start(cancelledRequest, 100, cancelledSource);
                SimpleAssert.that(
                    cancelled.end == HologramConstructionJobs.End.CANCELLED && !HologramConstructionJobs.active(player)
                        && cancelledSource.finishes == 1
                        && cancelledSource.prepares == 0,
                    "cancellation reentered from the current-source callback is terminal before start returns");
                try {
                    HologramConstructionJobs
                        .start(cancelledRequest, 100, new Source(liveSeat, Collections.emptyList()));
                    throw new AssertionError("the cancelled admission request was replayed");
                } catch (IOException expected) {}
                SimpleAssert.eq(1, player.closes, "replayed acceptance never closes another GUI or starts work");

                var failedSource = new Source(liveSeat, Collections.emptyList());
                var successor = new Source(liveSeat, Collections.emptyList());
                successor.waiting = true;
                final HologramConstructionJobs.Job[] replacement = { null };
                player.failClose = true;
                failedSource.onFinish = () -> {
                    player.failClose = false;
                    try {
                        replacement[0] = HologramConstructionJobs.start(UUID.randomUUID(), 102, successor);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                };
                var failed = HologramConstructionJobs.start(UUID.randomUUID(), 101, failedSource);
                SimpleAssert.that(
                    failed.end == HologramConstructionJobs.End.FAILED && failedSource.finishes == 1
                        && failedSource.prepares == 0
                        && HologramConstructionJobs.find(player) == replacement[0]
                        && replacement[0] != failed
                        && !replacement[0].terminal,
                    "close failure retires only this job and cannot erase a successor installed by its terminal callback");
                HologramConstructionJobs.cancel(player, replacement[0].request);
            } finally {
                var active = HologramConstructionJobs.find(player);
                if (active != null) HologramConstructionJobs.cancel(player, active.request);
                player.playerNetServerHandler.netManager.channel()
                    .close();
            }
        } catch (Exception failure) {
            throw new AssertionError("native close admission boundary", failure);
        }
    }

    static ClosePlayer closePlayer(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world, String name)
        throws ReflectiveOperationException {
        var factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
            "player",
            sun.misc.Unsafe.class,
            HologramServiceEditRegression.ReadOnlyWorld.class,
            UUID.class,
            String.class,
            Class.class);
        factory.setAccessible(true);
        var player = (ClosePlayer) factory.invoke(null, unsafe, world, UUID.randomUUID(), name, ClosePlayer.class);
        player.posY = 64;
        player.capabilities.allowEdit = true;
        player.inventoryContainer = new net.minecraft.inventory.ContainerPlayer(player.inventory, false, player);
        player.openContainer = player.inventoryContainer;
        return player;
    }

    static final class ClosePlayer extends net.minecraft.entity.player.EntityPlayerMP {

        int closes;
        boolean failClose;
        Runnable closeAction;

        private ClosePlayer() {
            super(null, null, null, null);
        }

        @Override
        public void closeScreen() {
            closes++;
            Runnable callback = closeAction;
            closeAction = null;
            if (callback != null) callback.run();
            if (failClose) throw new IllegalStateException("fixture native close failure");
        }
    }

    private static void capturedSource(sun.misc.Unsafe unsafe) {
        try {
            var world = HologramPlacementFixtureWorld.create(unsafe);
            world.seed(2, 64, 0, new ClearingBlock(), 0);
            world.seed(3, 64, 0, net.minecraft.init.Blocks.dirt, 0);
            world.seed(4, 64, 0, new ClearingBlock(), 0);
            var player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "ReviewedSource");
            player.posY = 64;
            player.capabilities.allowEdit = true;
            player.capabilities.isCreativeMode = false;
            player.inventory.mainInventory[1] = new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone, 3);
            var s = new HologramSession(player, 0, 64, 0, false, 1);
            s.virtual = s.virtualPlacement = s.virtualLocked = true;
            s.virtualPreset = new HologramPresets.Preset();
            s.virtualPreset.schema = 2;
            s.materialSettings.main = true;
            s.materialSettings.containers = s.materialSettings.me = false;
            s.capture = new HologramCapture(null, new net.minecraft.item.ItemStack(net.minecraft.init.Items.feather));
            var cell = new HologramCapture.Cell(null, 1, 64, 0);
            cell.block = net.minecraft.init.Blocks.stone;
            cell.userPosition = true;
            cell.blueprintBlock = cell.blueprintItem = "minecraft:stone";
            s.capture.cells.add(cell);
            s.planTargets.add(new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone));
            s.work.add(0);
            try {
                HologramPlacementJobs.begin(s, new net.minecraft.nbt.NBTTagCompound());
                var job = HologramConstructionJobs.find(player);
                SimpleAssert.that(
                    job != null && job.session == s,
                    "reviewed capture enters the sole production scheduler: " + s.status);
                player.inventory.currentItem = 2;
                world.arm();
                HologramConstructionJobs.tick(Long.MAX_VALUE);
                SimpleAssert.that(
                    job.end == HologramConstructionJobs.End.COMPLETE && s.job == 3
                        && world.getBlock(1, 64, 0) == net.minecraft.init.Blocks.stone
                        && player.inventory.mainInventory[1].stackSize == 2
                        && player.inventory.currentItem == 2,
                    "reviewed source uses the native writer once after switching hands, preserving the selected slot");
                player.inventory.currentItem = 0;
                var clear = savedAir(player, 2, 1);
                int beforeRetiredMode = world.writes()
                    .size();
                HologramPlacementJobs.begin(clear, new net.minecraft.nbt.NBTTagCompound());
                SimpleAssert.that(
                    HologramConstructionJobs.find(player) == null && clear.job == 0
                        && clear.status.contains("蓝图仅支持建造")
                        && world.writes()
                            .size() == beforeRetiredMode
                        && world.getBlock(2, 64, 0) instanceof ClearingBlock
                        && world.getBlock(3, 64, 0) == net.minecraft.init.Blocks.dirt
                        && player.inventory.mainInventory[1].stackSize == 2,
                    "saved blueprint replacement mode is refused without world writes or payment");
                player.inventory.currentItem = 0;
                var ignored = savedAir(player, 3, 0);
                int writes = world.writes()
                    .size();
                HologramPlacementJobs.begin(ignored, new net.minecraft.nbt.NBTTagCompound());
                var ignoring = HologramConstructionJobs.find(player);
                SimpleAssert
                    .that(ignoring != null, "mode-zero saved geometry is accepted without adding a clearing mode");
                HologramConstructionJobs.tick(Long.MAX_VALUE);
                SimpleAssert.that(
                    ignoring.end == HologramConstructionJobs.End.COMPLETE && ignoring.completed == 0
                        && world.writes()
                            .size() == writes
                        && world.getBlock(3, 64, 0) == net.minecraft.init.Blocks.dirt,
                    "saved mode-zero air remains geometry and does not remove the existing block");
                var nativeAir = new HologramSession(player, 0, 64, 0, false, 1, false);
                nativeAir.materialSettings.main = true;
                nativeAir.materialSettings.containers = nativeAir.materialSettings.me = false;
                nativeAir.capture = new HologramCapture(
                    null,
                    new net.minecraft.item.ItemStack(net.minecraft.init.Items.feather));
                var required = new HologramCapture.Cell(
                    com.gtnewhorizon.structurelib.structure.StructureUtility.isAir(),
                    4,
                    64,
                    0);
                required.block = net.minecraft.init.Blocks.air;
                nativeAir.capture.cells.add(required);
                nativeAir.planTargets.add(null);
                required.unknown = true;
                SimpleAssert.eq(
                    "protected",
                    HologramPlanPolicy.classify(nativeAir, required, 0),
                    "unknown declarations cannot acquire clearing authority from an air hint");
                required.unknown = false;
                HologramResourcePlanner.refreshPlan(nativeAir);
                SimpleAssert.eq(
                    "blocked",
                    nativeAir.planStatus.get(0),
                    "native required-air build conflicts with an occupied coordinate");
                int beforeRequiredAir = world.writes()
                    .size();
                HologramPlacementJobs.start(nativeAir, new net.minecraft.nbt.NBTTagCompound());
                SimpleAssert.that(
                    HologramConstructionJobs.find(player) == null && nativeAir.job == 0
                        && world.getBlock(4, 64, 0) instanceof ClearingBlock
                        && world.getBlock(3, 64, 0) == net.minecraft.init.Blocks.dirt
                        && world.writes()
                            .size() == beforeRequiredAir
                        && player.inventory.mainInventory[1].stackSize == 2,
                    "required-air build refuses clearing and preserves world blocks and payment");
            } finally {
                var job = HologramConstructionJobs.find(player);
                if (job != null) HologramConstructionJobs.cancel(player, job.request);
                player.playerNetServerHandler.netManager.channel()
                    .close();
            }
        } catch (Exception failure) {
            throw new AssertionError("reviewed capture source", failure);
        }
    }

    private static void legacyFormationBoundary(sun.misc.Unsafe unsafe)
        throws IOException, ReflectiveOperationException {
        var world = HologramPlacementFixtureWorld.create(unsafe);
        var player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "LegacyFormationGate");
        player.posY = 64;
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[1] = new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone, 3);
        var session = controllerSession(unsafe, world, player);
        try {
            for (boolean formed : new boolean[] { false, true }) {
                session.machine.mMachine = formed;
                for (int mode : new int[] { 0, 1, 2 }) {
                    session.mode = mode;
                    boolean allowed = mode == 0 ? !formed : formed;
                    SimpleAssert.eq(
                        allowed,
                        HologramPlanPolicy.legacyOperationRefusal(session)
                            .isEmpty(),
                        "legacy capture follows native cached formation: " + formed + "/" + mode);
                    if (allowed) continue;
                    int writes = world.writes()
                        .size();
                    HologramBuildPreparation.directBuild(session, true);
                    SimpleAssert.that(
                        !session.status.isEmpty() && !session.buildAfterCapture,
                        "direct legacy entry refuses before scanning");
                    HologramBuildPreparation.reviewedBuild(session, new net.minecraft.nbt.NBTTagCompound());
                    SimpleAssert.that(
                        !HologramPlanPolicy.constructionAllowed(session),
                        "legacy final admission cannot bypass native formation");
                    HologramPlacementJobs.begin(session, new net.minecraft.nbt.NBTTagCompound());
                    SimpleAssert.that(
                        HologramConstructionJobs.find(player) == null && session.job == 0
                            && world.writes()
                                .size() == writes
                            && player.inventory.mainInventory[1].stackSize == 3,
                        "legacy job begin refuses without writing or payment: formed=" + formed
                            + ", mode="
                            + mode
                            + ", status="
                            + session.status);
                }
            }
            var machine = session.machine;
            session.machine = null;
            for (int mode : new int[] { 0, 1, 2 }) {
                session.mode = mode;
                SimpleAssert.eq(
                    mode == 0,
                    HologramPlanPolicy.legacyOperationRefusal(session)
                        .isEmpty(),
                    "missing native machine state authorizes only build");
            }
            session.machine = machine;
            var sourceType = Class.forName("com.miaokatze.gtmb.hologram.HologramPlacementJobs$CapturedSource");
            var constructor = sourceType.getDeclaredConstructor(HologramSession.class);
            constructor.setAccessible(true);
            var cell = new HologramConstructionJobs.Target(1, 64, 0, null, true, false);
            for (int mode : new int[] { 0, 1, 2 }) {
                session.mode = mode;
                boolean admittedFormation = mode != 0;
                machine.mMachine = admittedFormation;
                var source = (HologramConstructionJobs.Source) constructor.newInstance(session);
                source.before(cell);
                source.committed(cell, false);
                machine.mMachine = !admittedFormation;
                try {
                    source.before(cell);
                    throw new AssertionError("matched rows consumed the formation gate for mode " + mode);
                } catch (IOException expected) {}
                machine.mMachine = admittedFormation;
                source.before(cell);
                source.committed(cell, true);
                machine.mMachine = !admittedFormation;
                source.before(cell);
                SimpleAssert.that(
                    source.current(),
                    "own committed changes may alter formation while identity and permissions remain checked: mode="
                        + mode
                        + ", formed="
                        + machine.mMachine
                        + ", status="
                        + session.status);
            }
            session.virtual = true;
            session.mode = 0;
            machine.mMachine = true;
            SimpleAssert.that(
                HologramPlanPolicy.legacyOperationRefusal(session)
                    .isEmpty(),
                "blueprint build never inherits controller formation gating");
        } finally {
            var live = HologramConstructionJobs.find(player);
            if (live != null) HologramConstructionJobs.cancel(player, live.request);
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static void controllerCallbacks(sun.misc.Unsafe unsafe) {
        String thread = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            Field bus = net.minecraftforge.common.MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object base = unsafe.staticFieldBase(bus);
            long offset = unsafe.staticFieldOffset(bus);
            var previous = net.minecraftforge.common.MinecraftForge.EVENT_BUS;
            for (int mutation = 0; mutation < 3; mutation++) {
                var world = HologramPlacementFixtureWorld.create(unsafe);
                var player = HologramLibraryServiceRegression
                    .placementPlayer(unsafe, world, "ControllerBinding" + mutation);
                player.posY = 64;
                player.capabilities.allowEdit = true;
                player.inventory.mainInventory[1] = new net.minecraft.item.ItemStack(
                    net.minecraft.init.Blocks.stone,
                    3);
                var s = controllerSession(unsafe, world, player);
                for (int x = 1; x <= 2; x++) {
                    var cell = new HologramCapture.Cell(
                        com.gtnewhorizon.structurelib.structure.StructureUtility
                            .ofBlock(net.minecraft.init.Blocks.stone, 0),
                        x,
                        64,
                        0);
                    cell.block = net.minecraft.init.Blocks.stone;
                    s.capture.cells.add(cell);
                    s.planTargets.add(new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone));
                    s.work.add(x - 1);
                }
                HologramPlacementJobs.begin(s, new net.minecraft.nbt.NBTTagCompound());
                var job = HologramConstructionJobs.find(player);
                SimpleAssert.that(job != null, "physical source admits its original controller: " + s.status);
                int change = mutation;
                int[] callbacks = { 0 };
                unsafe.putObjectVolatile(base, offset, new cpw.mods.fml.common.eventhandler.EventBus() {

                    @Override
                    public boolean post(cpw.mods.fml.common.eventhandler.Event event) {
                        if (event instanceof net.minecraftforge.event.world.BlockEvent.PlaceEvent place
                            && place.player == player) {
                            callbacks[0]++;
                            try {
                                if (change == 0) world.occupants.remove("0,64,0");
                                else if (change == 1) {
                                    Field facing = gregtech.api.metatileentity.BaseMetaTileEntity.class
                                        .getDeclaredField("mFacing");
                                    facing.setAccessible(true);
                                    facing.set(s.target, net.minecraftforge.common.util.ForgeDirection.EAST);
                                } else {
                                    Field allow = s.machine.getClass()
                                        .getDeclaredField("allow");
                                    allow.setAccessible(true);
                                    allow.setBoolean(s.machine, false);
                                }
                            } catch (ReflectiveOperationException error) {
                                throw new AssertionError(error);
                            }
                        }
                        return false;
                    }
                });
                try {
                    world.arm();
                    HologramConstructionJobs.tick(Long.MAX_VALUE);
                    SimpleAssert.that(
                        callbacks[0] == 1 && job.end == HologramConstructionJobs.End.CANCELLED
                            && world.getBlock(1, 64, 0) == net.minecraft.init.Blocks.air
                            && world.getBlock(2, 64, 0) == net.minecraft.init.Blocks.air
                            && player.inventory.mainInventory[1].stackSize == 3,
                        "controller removal, facing change and native access revocation stop later cells and refund the candidate: "
                            + change
                            + " / "
                            + s.status);
                } finally {
                    unsafe.putObjectVolatile(base, offset, previous);
                    var live = HologramConstructionJobs.find(player);
                    if (live != null) HologramConstructionJobs.cancel(player, live.request);
                    player.playerNetServerHandler.netManager.channel()
                        .close();
                }
            }
        } catch (Exception failure) {
            throw new AssertionError("physical controller callback binding", failure);
        } finally {
            Thread.currentThread()
                .setName(thread);
        }
    }

    private static HologramSession controllerSession(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world,
        net.minecraft.entity.player.EntityPlayerMP player) throws ReflectiveOperationException {
        var tileType = Class
            .forName("com.miaokatze.gtmb.hologram.HologramNativeControllerAccessRegression$PrivateController");
        var machineType = Class
            .forName("com.miaokatze.gtmb.hologram.HologramNativeControllerAccessRegression$AccessMachine");
        var tile = (gregtech.api.metatileentity.BaseMetaTileEntity) unsafe.allocateInstance(tileType);
        var machine = (gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>) unsafe
            .allocateInstance(machineType);
        tile.setWorldObj(world);
        tile.yCoord = 64;
        Field front = gregtech.api.metatileentity.BaseMetaTileEntity.class.getDeclaredField("mFacing");
        front.setAccessible(true);
        front.set(tile, net.minecraftforge.common.util.ForgeDirection.NORTH);
        Field orientation = gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase.class
            .getDeclaredField("mExtendedFacing");
        orientation.setAccessible(true);
        orientation.set(machine, com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.DEFAULT);
        Field allow = machineType.getDeclaredField("allow");
        allow.setAccessible(true);
        allow.setBoolean(machine, true);
        machine.setBaseMetaTileEntity(tile);
        world.occupants = new java.util.HashMap<>();
        world.occupants.put("0,64,0", tile);
        world.seed(0, 64, 0, net.minecraft.init.Blocks.stone, 0);
        var session = new HologramSession(player, 0, 64, 0, true, 1);
        session.materialSettings.main = true;
        session.materialSettings.containers = session.materialSettings.me = false;
        session.capture = new HologramCapture(
            machine,
            new net.minecraft.item.ItemStack(net.minecraft.init.Items.feather));
        return session;
    }

    private static HologramSession savedAir(net.minecraft.entity.player.EntityPlayerMP player, int x, int mode) {
        var session = new HologramSession(player, 0, 64, 0, false, 1, false);
        session.mode = mode;
        session.virtual = session.virtualPlacement = session.virtualLocked = true;
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.materialSettings.main = true;
        session.materialSettings.containers = session.materialSettings.me = false;
        session.capture = new HologramCapture(null, new net.minecraft.item.ItemStack(net.minecraft.init.Items.feather));
        var cell = new HologramCapture.Cell(null, x, 64, 0);
        cell.block = net.minecraft.init.Blocks.air;
        cell.blueprintBlock = "minecraft:air";
        cell.userPosition = true;
        session.capture.cells.add(cell);
        session.planTargets.add(null);
        session.work.add(0);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static void updates(HologramSession session) throws Exception {
        Field handlers = RunnableMachineUpdate.class.getDeclaredField("perWorldHandler");
        handlers.setAccessible(true);
        Map<net.minecraft.world.World, RunnableMachineUpdate> nativeQueue = (Map<net.minecraft.world.World, RunnableMachineUpdate>) handlers
            .get(null);
        var saved = new java.util.HashMap<>(nativeQueue);
        boolean global = RunnableMachineUpdate.isEnabled(), thread = RunnableMachineUpdate.isCurrentThreadEnabled();
        try {
            nativeQueue.clear();
            RunnableMachineUpdate.setEnabled(true);
            RunnableMachineUpdate.setCurrentThreadEnabled(true);
            var source = new Source(session, Collections.emptyList());
            source.waiting = true;
            var job = HologramConstructionJobs.start(UUID.randomUUID(), 4, source);
            try (var scope = job.updates.open()) {
                HologramMachineUpdateBatch.suppressDuplicate(session.player.worldObj, 1, 64, 0);
            }
            HologramConstructionJobs.tick(Long.MAX_VALUE);
            try (var scope = job.updates.open()) {
                HologramMachineUpdateBatch.suppressDuplicate(session.player.worldObj, 2, 64, 0);
            }
            HologramConstructionJobs.tick(Long.MAX_VALUE);
            SimpleAssert
                .that(nativeQueue.isEmpty(), "native requests from multiple slices remain job-local until termination");
            SimpleAssert.that(
                RunnableMachineUpdate.isCurrentThreadEnabled(),
                "every yielded slice restores the original thread switch");
            HologramConstructionJobs.cancel(session.player, job.request);
            SimpleAssert.that(
                nativeQueue.containsKey(session.player.worldObj),
                "cancellation flushes the accumulated native requests");
            nativeQueue.clear();
            var failure = new Source(session, Collections.emptyList());
            failure.fail = true;
            var failed = HologramConstructionJobs.start(UUID.randomUUID(), 5, failure);
            try (var scope = failed.updates.open()) {
                HologramMachineUpdateBatch.suppressDuplicate(session.player.worldObj, 3, 64, 0);
            }
            HologramConstructionJobs.tick(Long.MAX_VALUE);
            SimpleAssert.that(
                failed.end == HologramConstructionJobs.End.FAILED && nativeQueue.containsKey(session.player.worldObj),
                "exceptions flush actual native requests through the same terminal boundary");
            SimpleAssert.that(
                RunnableMachineUpdate.isCurrentThreadEnabled(),
                "exception cleanup restores the native thread switch");
            nativeQueue.clear();
            RunnableMachineUpdate.setCurrentThreadEnabled(false);
            var disabled = new HologramMachineUpdateBatch.Updates();
            try (var scope = disabled.open()) {
                HologramMachineUpdateBatch.suppressDuplicate(session.player.worldObj, 8, 64, 0);
                disabled.finish();
            }
            SimpleAssert.that(
                !RunnableMachineUpdate.isCurrentThreadEnabled() && nativeQueue.isEmpty(),
                "an originally disabled caller is never forced on");
            RunnableMachineUpdate.setCurrentThreadEnabled(true);
            RunnableMachineUpdate.setMachineUpdateValues(session.player.worldObj, 9, 64, 0);
            SimpleAssert.that(
                nativeQueue.containsKey(session.player.worldObj),
                "unrelated native updates outside the scope remain immediate");
            nativeQueue.clear();
            var preparation = new Source(session, Collections.emptyList());
            preparation.emitUpdates = true;
            preparation.waiting = true;
            var preparing = HologramConstructionJobs.start(UUID.randomUUID(), 6, preparation);
            HologramConstructionJobs.tick(Long.MAX_VALUE);
            SimpleAssert.that(
                nativeQueue.containsKey(session.player.worldObj),
                "source preparation remains outside construction suppression and retains native update behavior");
            nativeQueue.clear();
            HologramConstructionJobs.cancel(session.player, preparing.request);
            SimpleAssert
                .that(nativeQueue.isEmpty(), "preparation requests were not captured and re-flushed at terminal");
        } finally {
            nativeQueue.clear();
            nativeQueue.putAll(saved);
            RunnableMachineUpdate.setEnabled(global);
            RunnableMachineUpdate.setCurrentThreadEnabled(thread);
        }
    }

    /** A native fixture block without loot/food/chunk services; harvest ordering/drop capture has its own tests. */
    private static final class ClearingBlock extends net.minecraft.block.Block {

        ClearingBlock() {
            super(net.minecraft.block.material.Material.rock);
            setHardness(1);
            setHarvestLevel("pickaxe", 0);
        }

        @Override
        public void harvestBlock(net.minecraft.world.World world, net.minecraft.entity.player.EntityPlayer player,
            int x, int y, int z, int metadata) {}

        @Override
        public java.util.ArrayList<net.minecraft.item.ItemStack> getDrops(net.minecraft.world.World world, int x, int y,
            int z, int metadata, int fortune) {
            return new java.util.ArrayList<>();
        }
    }

    private static final class Source implements HologramConstructionJobs.Source {

        final HologramSession session;
        final List<HologramConstructionJobs.Target> cells;
        final List<Integer> order = new ArrayList<>();
        int cursor, finishes, prepares;
        boolean waiting, emitUpdates, fail, valid = true;
        Runnable onCurrent, onFinish;

        Source(HologramSession session, List<HologramConstructionJobs.Target> cells) {
            this.session = session;
            this.cells = cells;
        }

        public HologramSession session() {
            return session;
        }

        public HologramStockNativeWire.Operation operation() {
            return HologramStockNativeWire.Operation.DEMOLISH;
        }

        public boolean current() {
            Runnable callback = onCurrent;
            onCurrent = null;
            if (callback != null) callback.run();
            return valid;
        }

        public boolean prepare(long deadline) throws IOException {
            prepares++;
            if (emitUpdates && !HologramMachineUpdateBatch.suppressDuplicate(session.player.worldObj, prepares, 64, 0))
                RunnableMachineUpdate.setMachineUpdateValues(session.player.worldObj, prepares, 64, 0);
            if (fail) throw new IOException("fixture preparation failed");
            return !waiting;
        }

        public HologramConstructionJobs.Target next(long deadline) {
            return cells.get(cursor++);
        }

        public boolean exhausted() {
            return cursor == cells.size();
        }

        public long total() {
            return cells.size();
        }

        public void committed(HologramConstructionJobs.Target cell, boolean changed) {
            order.add(cell.x);
        }

        public void finished(HologramConstructionJobs.Job job) {
            finishes++;
            if (onFinish != null) onFinish.run();
        }
    }
}
