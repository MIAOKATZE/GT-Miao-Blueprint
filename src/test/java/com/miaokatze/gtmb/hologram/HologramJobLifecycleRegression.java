// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;

/** Actual scheduler/job/transport path with read-only anchor cells; native GT placement is a separate contract. */
public final class HologramJobLifecycleRegression {

    private static final List<NBTTagCompound> WIRE = new ArrayList<>();

    private HologramJobLifecycleRegression() {}

    public static void run() {
        try {
            HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                try {
                    lifecycle();
                    closeDuringAdmission();
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            });
            diagnosticLimits();
        } catch (Exception failure) {
            throw new AssertionError("Job lifecycle", failure);
        }
    }

    private static void lifecycle() throws ReflectiveOperationException {
        Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) access.get(null);
        var world = HologramPlacementFixtureWorld.create(unsafe);
        Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
            "player",
            sun.misc.Unsafe.class,
            HologramServiceEditRegression.ReadOnlyWorld.class,
            UUID.class,
            String.class);
        factory.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) factory.invoke(null, unsafe, world, UUID.randomUUID(), "JobFixture");
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previousChannel = channel.get(null);
        try {
            WIRE.clear();
            channel.set(null, unsafe.allocateInstance(RecordingChannel.class));
            HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
            s.virtual = true;
            s.virtualPreset = new HologramPresets.Preset();
            s.virtualPreset.schema = 2;
            s.virtualPlacement = s.virtualLocked = true;
            s.materialSettings.containers = s.materialSettings.me = false;
            s.capture = new HologramCapture(null, player.getHeldItem());
            player.posX = 160;
            player.posY = 64;
            SimpleAssert.that(
                !HologramSessionPolicy.previewReach(player, s.x, s.y, s.z) && HologramSessionPolicy.valid(s)
                    && HologramSessionPolicy.saveValid(s),
                "locked blueprint admits commands and reviewed saves beyond the old anchor radii");
            world.unloaded = true;
            SimpleAssert.that(
                !HologramSessionPolicy.valid(s) && !HologramSessionPolicy.jobValid(s),
                "unloaded locked anchors remain invalid at any distance");
            world.unloaded = false;
            player.dimension++;
            SimpleAssert.that(
                !HologramSessionPolicy.valid(s) && !HologramSessionPolicy.jobValid(s),
                "cross-dimension commands and continuations remain invalid");
            player.dimension--;
            player.worldObj = HologramPlacementFixtureWorld.create(unsafe);
            SimpleAssert.that(
                !HologramSessionPolicy.valid(s) && !HologramSessionPolicy.jobValid(s),
                "same-dimension replacement worlds do not inherit an admitted anchor");
            player.worldObj = world;
            var controller = new net.minecraft.tileentity.TileEntity();
            world.occupant = controller;
            s.virtual = false;
            s.target = controller;
            SimpleAssert.that(HologramSessionPolicy.valid(s), "a loaded identical controller opens beyond 64 blocks");
            world.occupant = new net.minecraft.tileentity.TileEntity();
            SimpleAssert
                .that(!HologramSessionPolicy.valid(s), "controller replacement still invalidates remote commands");
            world.occupant = null;
            s.target = null;
            s.virtual = true;
            player.capabilities.allowEdit = true;
            player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 8);
            for (int i = 0; i < 4; i++) {
                HologramCapture.Cell cell = new HologramCapture.Cell(null, i, 64, 0);
                cell.block = Blocks.stone;
                cell.userPosition = true;
                cell.blueprintBlock = cell.blueprintItem = "minecraft:stone";
                s.capture.cells.add(cell);
                s.work.add(i);
                s.planTargets.add(new ItemStack(Blocks.stone));
                s.expectedBlocks.add(Blocks.air);
                s.expectedMeta.add(0);
                s.expectedTiles.add(null);
                s.expectedFacings.add(-1);
            }
            s.libraryUiActive = true;
            s.planRevision = 11;
            s.uiSequence = 7;
            s.placementReviewNonce = 9;
            HologramWorkspace.activate(player.getUniqueID(), s);
            HologramPlacementJobs.begin(s, new NBTTagCompound());
            var job = HologramConstructionJobs.find(player);
            SimpleAssert.that(
                job != null && job.session == s,
                "actual reviewed source is admitted before changing the hotbar: " + s.status);
            ItemStack originalTool = player.inventory.mainInventory[0];
            ItemStack replacementTool = new ItemStack(Blocks.stone);
            player.inventory.currentItem = 1;
            SimpleAssert.that(!HologramSessionPolicy.valid(s), "changed hotbar cannot admit another command");
            Field eventBus = net.minecraftforge.common.MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object base = unsafe.staticFieldBase(eventBus);
            long offset = unsafe.staticFieldOffset(eventBus);
            var previousBus = net.minecraftforge.common.MinecraftForge.EVENT_BUS;
            int[] placements = { 0 };
            unsafe.putObjectVolatile(base, offset, new cpw.mods.fml.common.eventhandler.EventBus() {

                @Override
                public boolean post(cpw.mods.fml.common.eventhandler.Event event) {
                    if (event instanceof net.minecraftforge.event.world.BlockEvent.PlaceEvent place
                        && place.player == player) {
                        placements[0]++;
                        if (placements[0] == 2) {
                            player.posX = 320;
                            SimpleAssert.that(
                                !HologramSessionPolicy.previewReach(player, s.x, s.y, s.z)
                                    && HologramSessionPolicy.jobValid(s),
                                "admitted saved work survives leaving positioning reach");
                            SimpleAssert.eq(
                                s,
                                HologramProjectorWorkflow.uiSession(player),
                                "opening UI retains the distant running job");
                            SimpleAssert.eq(
                                s.toolId,
                                HologramSessionPolicy.trigger(s)
                                    .getTagCompound()
                                    .getString(HologramSessionKeys.TOOL_ID),
                                "native permission trigger remains the original tool after switching hands");
                        }
                        if (placements[0] == 3) player.inventory.mainInventory[0] = replacementTool;
                    }
                    return false;
                }
            });
            try {
                world.arm();
                HologramConstructionJobs.tick(Long.MAX_VALUE);
            } finally {
                unsafe.putObjectVolatile(base, offset, previousBus);
            }
            SimpleAssert.that(
                job.end == HologramConstructionJobs.End.CANCELLED && s.job == 4
                    && s.cursor == 3
                    && s.completed == 2
                    && placements[0] == 3,
                "real common job keeps its committed prefix and stops on original-tool replacement: end=" + job.end
                    + ", job="
                    + s.job
                    + ", cursor="
                    + s.cursor
                    + ", completed="
                    + s.completed
                    + ", callbacks="
                    + placements[0]
                    + ", blocked="
                    + job.blocked
                    + ", status="
                    + s.status
                    + ", reason="
                    + job.reason
                    + ", stock="
                    + (player.inventory.mainInventory[1] == null ? 0 : player.inventory.mainInventory[1].stackSize)
                    + ", world="
                    + world.getBlock(0, 64, 0)
                    + "/"
                    + world.getBlock(1, 64, 0)
                    + "/"
                    + world.getBlock(2, 64, 0)
                    + "/"
                    + world.getBlock(3, 64, 0)
                    + ", journal="
                    + HologramConstructionJournal.pending(player)
                    + ", scopeReason="
                    + HologramConstructionJournal.owner(player)
                        .getCompoundTag(HologramConstructionJournal.KEY)
                        .getString("scopeUncertainReason")
                    + ", snapshotFlags="
                    + world.captureBlockSnapshots
                    + "/"
                    + world.restoringBlockSnapshots
                    + ", writes="
                    + world.writes());
            SimpleAssert.that(
                world.getBlock(0, 64, 0) == Blocks.stone && world.getBlock(1, 64, 0) == Blocks.stone
                    && world.getBlock(2, 64, 0) == Blocks.air
                    && world.getBlock(3, 64, 0) == Blocks.air
                    && player.inventory.mainInventory[1].stackSize == 6,
                "changing selected slot/walking permits two real writes; invalidating original tool refunds the candidate and never reaches the tail");
            SimpleAssert.that(
                !HologramConstructionJournal.pending(player),
                "known candidate rollback settles its journal without undoing the committed prefix");
            SimpleAssert.that(
                player.inventory.mainInventory[0] == replacementTool,
                "compensation leaves the replacement tool slot untouched");
            SimpleAssert.that(!HologramSnapshotPreparation.pending(s), "invalid context cannot wait on a full DTO");
            NBTTagCompound envelope = WIRE.get(WIRE.size() - 1), terminal = envelope.getCompoundTag("progress");
            SimpleAssert.that(envelope.getBoolean("progressOnly"), "terminal uses the existing direct transport");
            SimpleAssert.eq(4, terminal.getInteger("job"), "actual receiver sees the stopped job");
            SimpleAssert.eq("stopped", terminal.getString("outcome"), "attempted prefix is explicitly terminal");
            SimpleAssert.eq(3L, terminal.getLong("done"), "terminal preserves actual attempted candidates");
            SimpleAssert.eq(4L, terminal.getLong("total"), "terminal preserves original work total");
            SimpleAssert.eq(7L, terminal.getLong("uiSequence"), "terminal preserves admitted command sequence");
            SimpleAssert.eq(11L, terminal.getLong("planRevision"), "terminal preserves admitted plan revision");
            HologramCommand command = new HologramCommand();
            SimpleAssert.eq(4, command.getRequiredPermissionLevel(), "all administrative subcommands require OP4");
            for (int permission : new int[] { 0, 3 }) {
                net.minecraft.command.ICommandSender unauthorized = sender(permission);
                SimpleAssert.that(
                    !command.canCommandSenderUseCommand(unauthorized),
                    "ordinary and OP3 senders fail the actual OP4 command gate");
                for (String subcommand : new String[] { "restart", "voucher", "recovery" }) {
                    boolean permissionRejected = false;
                    try {
                        command.processCommand(
                            unauthorized,
                            "recovery".equals(subcommand) ? new String[] { subcommand, "fixture-player" }
                                : new String[] { subcommand });
                    } catch (net.minecraft.command.CommandException expected) {
                        permissionRejected = "需要管理员权限（等级 4）".equals(expected.getMessage());
                    }
                    SimpleAssert.that(
                        permissionRejected,
                        "ordinary and OP3 senders cannot execute administrative " + subcommand);
                }
                for (String prefix : new String[] { "", "re", "recovery" }) SimpleAssert.that(
                    command.addTabCompletionOptions(unauthorized, new String[] { prefix })
                        .isEmpty(),
                    "ordinary and OP3 senders cannot discover any administrative completion");
            }
            net.minecraft.command.ICommandSender allowed = sender(4);
            SimpleAssert.eq(
                java.util.Arrays.asList("restart", "recovery"),
                command.addTabCompletionOptions(allowed, new String[] { "re" }),
                "OP4 discovers both allowed re-prefixed actions without executing either");
            SimpleAssert.eq(
                java.util.Collections.singletonList("restart"),
                command.addTabCompletionOptions(allowed, new String[] { "rest" }),
                "unambiguous restart prefix retains its existing completion contract");
            SimpleAssert.eq(
                java.util.Collections.singletonList("recovery"),
                command.addTabCompletionOptions(allowed, new String[] { "reco" }),
                "OP4 can discover the new recovery action without looking up or mutating a player");
            SimpleAssert.that(
                command.addTabCompletionOptions(allowed, new String[] { "restart", "" })
                    .isEmpty(),
                "restart has no positional arguments to complete");
            SimpleAssert.eq(s, HologramWorkspace.find(player.getUniqueID()), "denied restart cannot retire sessions");
            int wireBeforeRestart = WIRE.size();
            SimpleAssert
                .eq(1, HologramWorkspace.restartRuntime("fixture runtime restarted"), "runtime reset retires session");
            SimpleAssert.eq(null, HologramWorkspace.find(player.getUniqueID()), "old session nonce cannot be reused");
            SimpleAssert.that(s.cancelled && s.job == 4 && s.cursor == 3, "restart preserves committed job progress");
            SimpleAssert.that(WIRE.size() > wireBeforeRestart, "restart publishes a terminal receipt");
            NBTTagCompound cancelled = WIRE.get(WIRE.size() - 1);
            SimpleAssert
                .that(cancelled.getBoolean("cancelled"), "restart explicitly retires client editing/background work");
            SimpleAssert.eq(s.id, cancelled.getString("session"), "cancellation binds the retired session");
            player.inventory.mainInventory[0] = originalTool;
            player.inventory.currentItem = 0;
            HologramSession replacement = new HologramSession(player, 0, 64, 0, false, 2);
            HologramWorkspace.activate(player.getUniqueID(), replacement);
            SimpleAssert
                .eq(replacement, HologramWorkspace.find(player.getUniqueID()), "new work can open after restart");
        } finally {
            HologramWorkspace.clear();
            channel.set(null, previousChannel);
            player.playerNetServerHandler.netManager.channel()
                .close();
            WIRE.clear();
        }
    }

    private static void closeDuringAdmission() throws ReflectiveOperationException {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) singleton.get(null);
        for (int mode = 0; mode < 4; mode++) {
            var world = HologramPlacementFixtureWorld.create(unsafe);
            var player = HologramConstructionJobsRegression.closePlayer(unsafe, world, "CloseLifecycle" + mode);
            player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 3);
            var s = new HologramSession(player, 0, 64, 0, false, 1);
            s.virtual = s.virtualPlacement = s.virtualLocked = true;
            s.virtualPreset = new HologramPresets.Preset();
            s.virtualPreset.schema = 2;
            s.materialSettings.main = true;
            s.materialSettings.containers = s.materialSettings.me = false;
            s.capture = new HologramCapture(null, player.getHeldItem());
            var cell = new HologramCapture.Cell(null, 1, 64, 0);
            cell.block = Blocks.stone;
            cell.userPosition = true;
            cell.blueprintBlock = cell.blueprintItem = "minecraft:stone";
            s.capture.cells.add(cell);
            s.planTargets.add(new ItemStack(Blocks.stone));
            s.work.add(0);
            int change = mode;
            final HologramConstructionJobs.Job[] admitted = { null };
            player.failClose = mode == 1;
            player.closeAction = () -> {
                admitted[0] = HologramConstructionJobs.find(player);
                SimpleAssert.that(
                    admitted[0] != null && admitted[0].executing,
                    "close follows registration and precedes executable work");
                HologramConstructionJobs.tick(Long.MAX_VALUE);
                SimpleAssert.that(
                    world.writes()
                        .isEmpty() && player.inventory.mainInventory[1].stackSize == 3,
                    "native close cannot reenter placement or payment before acceptance completes");
                if (change == 2) HologramConstructionJobs.cancel(player, admitted[0].request);
                if (change == 3) player.inventory.mainInventory[0] = new ItemStack(Blocks.dirt);
            };
            try {
                world.arm();
                HologramPlacementJobs.begin(s, new NBTTagCompound());
                SimpleAssert.eq(1, player.closes, "a connected admitted player receives one native close call");
                if (mode == 0) {
                    SimpleAssert.that(
                        HologramConstructionJobs.find(player) == admitted[0] && s.job == 1,
                        "a normal close retains the accepted runnable source");
                    admitted[0].step(Long.MAX_VALUE);
                    SimpleAssert.that(
                        admitted[0].end == HologramConstructionJobs.End.COMPLETE
                            && world.getBlock(1, 64, 0) == Blocks.stone
                            && player.inventory.mainInventory[1].stackSize == 2,
                        "world placement and one debit start only after the close boundary returns");
                } else {
                    SimpleAssert.that(
                        admitted[0].terminal && !HologramConstructionJobs.active(player)
                            && s.job == 4
                            && admitted[0].end == (mode == 1 ? HologramConstructionJobs.End.FAILED
                                : HologramConstructionJobs.End.CANCELLED)
                            && world.writes()
                                .isEmpty()
                            && player.inventory.mainInventory[1].stackSize == 3
                            && !HologramConstructionJournal.pending(player),
                        "close failure, reentrant cancellation or tool replacement cannot be reset to RUNNING by the reviewed caller: "
                            + mode
                            + " / "
                            + s.status);
                }
            } finally {
                var active = HologramConstructionJobs.find(player);
                if (active != null) HologramConstructionJobs.cancel(player, active.request);
                player.playerNetServerHandler.netManager.channel()
                    .close();
            }
        }
    }

    private static net.minecraft.command.ICommandSender sender(int permission) {
        return (net.minecraft.command.ICommandSender) java.lang.reflect.Proxy.newProxyInstance(
            HologramJobLifecycleRegression.class.getClassLoader(),
            new Class<?>[] { net.minecraft.command.ICommandSender.class },
            (proxy, method, arguments) -> method.getName()
                .equals("canCommandSenderUseCommand") ? permission >= (Integer) arguments[0] : null);
    }

    private static void diagnosticLimits() {
        HologramPlacementDiagnostics.Limits limits = new HologramPlacementDiagnostics.Limits();
        for (int kind = 0; kind < 16; kind++) {
            for (int sample = 0; sample < 3; sample++)
                SimpleAssert.that(limits.admit("class" + kind), "bounded sample");
            SimpleAssert.that(!limits.admit("class" + kind), "same failure cannot spam every cell");
        }
        SimpleAssert.that(!limits.admit("overflow"), "distinct failures cannot grow per-job diagnostics unboundedly");
        limits.clear();
        SimpleAssert.that(limits.admit("overflow"), "a new job owns a fresh diagnostic budget");
    }

    private static final class RecordingChannel extends SimpleNetworkWrapper {

        private RecordingChannel() {
            super("job_lifecycle_fixture");
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP player) {
            if (message instanceof HologramNetwork.State)
                WIRE.add((NBTTagCompound) ((HologramNetwork.State) message).tag.copy());
        }
    }
}
