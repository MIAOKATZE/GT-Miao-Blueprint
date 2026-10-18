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
            lifecycle();
            diagnosticLimits();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Job lifecycle", failure);
        }
    }

    private static void lifecycle() throws ReflectiveOperationException {
        Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) access.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
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
            for (int i = 0; i < 3; i++) {
                HologramCapture.Cell cell = new HologramCapture.Cell(null, i, 64, 0);
                cell.block = Blocks.air;
                cell.anchor = true;
                s.capture.cells.add(cell);
                s.work.add(i);
                s.expectedBlocks.add(Blocks.air);
                s.expectedMeta.add(0);
                s.expectedTiles.add(null);
                s.expectedFacings.add(-1);
            }
            s.job = 1;
            s.stopTick = true;
            s.libraryUiActive = true;
            s.planRevision = 11;
            s.uiSequence = 7;
            s.placementReviewNonce = 9;
            HologramWorkspace.activate(player.getUniqueID(), s);
            Method tick = HologramWorkspaceScheduler.class.getDeclaredMethod("tickSession", HologramSession.class);
            tick.setAccessible(true);
            player.inventory.currentItem = 1;
            SimpleAssert.that(!HologramSessionPolicy.valid(s), "changed hotbar cannot admit another command");
            tick.invoke(null, s);
            SimpleAssert.eq(1, s.cursor, "real scheduler executes admitted work after a hotbar selection change");
            SimpleAssert.eq(1, s.job, "selected-slot change does not stop background construction");
            player.posX = 160;
            SimpleAssert.that(
                !HologramSessionPolicy.previewReach(player, s.x, s.y, s.z) && HologramSessionPolicy.jobValid(s),
                "locked large blueprint job survives leaving the positioning reach");
            SimpleAssert
                .eq(s, HologramProjectorWorkflow.uiSession(player), "opening workspace preserves distant running job");
            s.stopTick = true;
            tick.invoke(null, s);
            SimpleAssert.eq(2, s.cursor, "actual scheduler keeps working after player walks around a large blueprint");
            SimpleAssert.that(s.libraryUiActive, "scheduler keeps the active job's UI capability owner");
            SimpleAssert.eq(
                s.toolId,
                HologramSessionPolicy.trigger(s)
                    .getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID),
                "permission/native trigger retains the original tool identity");
            player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            tick.invoke(null, s);
            SimpleAssert.eq(4, s.job, "replacement of the actual admitted tool stops the job");
            SimpleAssert.eq(2, s.cursor, "stopping never executes a remaining cell");
            SimpleAssert.that(!HologramSnapshotPreparation.pending(s), "invalid context cannot wait on a full DTO");
            NBTTagCompound envelope = WIRE.get(WIRE.size() - 1);
            NBTTagCompound terminal = envelope.getCompoundTag("progress");
            SimpleAssert.that(envelope.getBoolean("progressOnly"), "terminal uses the existing direct transport path");
            SimpleAssert.eq(4, terminal.getInteger("job"), "stopped outcome reaches the actual transport receiver");
            SimpleAssert.eq("stopped", terminal.getString("outcome"), "partial cursor is explicitly terminal");
            SimpleAssert.eq(2L, terminal.getLong("done"), "terminal preserves attempted count");
            SimpleAssert.eq(3L, terminal.getLong("total"), "terminal preserves original work total");
            SimpleAssert.eq(7L, terminal.getLong("uiSequence"), "terminal preserves the admitted command sequence");
            SimpleAssert.eq(11L, terminal.getLong("planRevision"), "terminal preserves its plan revision");
            net.minecraft.command.ICommandSender denied = (net.minecraft.command.ICommandSender) java.lang.reflect.Proxy
                .newProxyInstance(
                    HologramJobLifecycleRegression.class.getClassLoader(),
                    new Class<?>[] { net.minecraft.command.ICommandSender.class },
                    (proxy, method, args) -> method.getName()
                        .equals("canCommandSenderUseCommand") ? false : null);
            boolean permissionRejected = false;
            try {
                new HologramCommand().processCommand(denied, new String[] { "restart" });
            } catch (net.minecraft.command.CommandException expected) {
                permissionRejected = true;
            }
            SimpleAssert.that(permissionRejected, "restart enforces administrator permission at the command boundary");
            SimpleAssert.that(
                new HologramCommand().addTabCompletionOptions(denied, new String[] { "r" })
                    .isEmpty(),
                "unauthorized senders cannot discover administrative completion");
            net.minecraft.command.ICommandSender allowed = (net.minecraft.command.ICommandSender) java.lang.reflect.Proxy
                .newProxyInstance(
                    HologramJobLifecycleRegression.class.getClassLoader(),
                    new Class<?>[] { net.minecraft.command.ICommandSender.class },
                    (proxy, method, args) -> method.getName()
                        .equals("canCommandSenderUseCommand") ? true : null);
            HologramCommand command = new HologramCommand();
            SimpleAssert.eq(
                java.util.Collections.singletonList("restart"),
                command.addTabCompletionOptions(allowed, new String[] { "re" }),
                "partial subcommand completes restart without executing it");
            SimpleAssert.that(
                command.addTabCompletionOptions(allowed, new String[] { "restart", "" })
                    .isEmpty(),
                "restart has no positional arguments to complete");
            SimpleAssert.eq(s, HologramWorkspace.find(player.getUniqueID()), "denied restart cannot retire sessions");
            int wireBeforeRestart = WIRE.size();
            SimpleAssert
                .eq(1, HologramWorkspace.restartRuntime("fixture runtime restarted"), "runtime reset retires session");
            SimpleAssert.eq(null, HologramWorkspace.find(player.getUniqueID()), "old session nonce cannot be reused");
            SimpleAssert.that(s.cancelled && s.job == 4 && s.cursor == 2, "restart preserves committed job progress");
            SimpleAssert.that(WIRE.size() > wireBeforeRestart, "restart publishes a terminal receipt");
            NBTTagCompound cancelled = WIRE.get(WIRE.size() - 1);
            SimpleAssert
                .that(cancelled.getBoolean("cancelled"), "restart explicitly retires client editing/background work");
            SimpleAssert.eq(s.id, cancelled.getString("session"), "cancellation binds the retired session");
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
