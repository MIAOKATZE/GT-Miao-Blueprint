package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual saved-preset copy/projection/planner continuation and server activation lifecycle, not a timing model. */
public final class HologramPresetActivationRegression {

    private HologramPresetActivationRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramPresetActivationRegression.class, args)) return;
        run();
        System.out.println("Hologram saved preset activation regressions passed");
    }

    public static void run() {
        try {
            EntityPlayerMP player = player();
            publication(player);
            cancellation(player);
            waitingAndSupersession(player);
            staleFailurePreservesNewerContext(player);
            directFailureControl();
        } catch (Throwable failure) {
            throw new AssertionError("saved preset activation", failure);
        } finally {
            HologramWorkspace.clear();
        }
    }

    private static void staleFailurePreservesNewerContext(EntityPlayerMP player) {
        HologramSession origin = origin(player);
        HologramPresetActivation.Completion old = HologramPresetWorkflow.presentationCompletion(origin, false);
        origin.uiSequence++;
        origin.planRevision++;
        origin.blueprintClientTaskToken++;
        origin.status = "newer request remains pending";
        old.failed("retired activation cancelled");
        SimpleAssert.eq("newer request remains pending", origin.status,
            "retired failure cannot overwrite status or send a UI acknowledgement with the newer request identity");
        SimpleAssert.that(HologramWorkspace.find(player.getUniqueID()) == origin,
            "retired failure cannot rollback the newer source route");
    }

    @SuppressWarnings("unchecked")
    private static void directFailureControl() throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld)
            unsafe.allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        java.lang.reflect.Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod("player",
            sun.misc.Unsafe.class, HologramServiceEditRegression.ReadOnlyWorld.class, java.util.UUID.class, String.class);
        factory.setAccessible(true);
        EntityPlayerMP connected = (EntityPlayerMP) factory.invoke(null, unsafe, world, new java.util.UUID(432, 433), "activation-wire");
        connected.posY = 64;
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previous = channel.get(null);
        Field wireField = HologramSnapshotPublishRegression.class.getDeclaredField("WIRE");
        wireField.setAccessible(true);
        java.util.List<cpw.mods.fml.common.network.simpleimpl.IMessage> wire =
            (java.util.List<cpw.mods.fml.common.network.simpleimpl.IMessage>) wireField.get(null);
        try {
            HologramNetwork.clearServerQueues();
            wire.clear();
            channel.set(null, unsafe.allocateInstance(Class.forName(
                "com.miaokatze.gtmb.hologram.HologramSnapshotPublishRegression$RecordingChannel")));
            HologramSession origin = origin(connected);
            HologramCapture baseline = origin.capture;
            HologramNetwork.SnapshotTicket blocked = HologramNetwork.reserveOwnedState(origin);
            SimpleAssert.that(blocked != null && blocked.current(), "old full snapshot holds a real unbuilt FIFO reservation");
            HologramNetwork.sendPresetActivationFailure(connected, origin.id, 91, 14, 77, "candidate rejected");
            SimpleAssert.eq(1, wire.size(), "activation terminal control bypasses the old blocked full-snapshot FIFO");
            SimpleAssert.that(wire.get(0) instanceof HologramNetwork.State, "failure is a direct small control, not a queued fragment");
            net.minecraft.nbt.NBTTagCompound failure = ((HologramNetwork.State) wire.get(0)).tag;
            SimpleAssert.eq(origin.id, failure.getString("session"), "failure keeps exact source session");
            SimpleAssert.eq(91L, failure.getLong("uiSequence"), "failure keeps exact UI request");
            SimpleAssert.eq(14L, failure.getLong("planRevision"), "failure keeps exact source revision");
            SimpleAssert.eq(77L, failure.getLong("clientTaskToken"), "failure cannot borrow a newer background task token");
            SimpleAssert.that(blocked.current() && HologramWorkspace.find(connected.getUniqueID()) == origin
                && origin.capture == baseline, "failure control does not cancel the old FIFO baseline or rollback its editor");
            HologramSession preparing = candidate(connected, "teardown", 32);
            SimpleAssert.that(HologramPresetActivation.begin(origin, preparing, preparing.virtualPreset,
                preparing.virtualPreset.id, null, HologramPresetWorkflow.presentationCompletion(origin, false)),
                "teardown fixture uses the production presentation failure callback");
            wire.clear();
            HologramWorkspace.clear();
            Field outgoing = HologramNetwork.class.getDeclaredField("outgoing");
            outgoing.setAccessible(true);
            SimpleAssert.that(((java.util.Map<?, ?>) outgoing.get(null)).isEmpty(),
                "Workspace clear cancellation must not recreate the old snapshot outbox");
            SimpleAssert.that(!HologramSnapshotPreparation.pending(origin)
                && !HologramSnapshotPreparation.pending(preparing) && !HologramPresetActivation.pending(origin),
                "Workspace clear leaves no candidate or DTO preparation work");
            SimpleAssert.that(HologramWorkspace.find(connected.getUniqueID()) == null && !blocked.current()
                && origin.capture == baseline, "teardown retires the route/reservation without mutating baseline geometry");
            int terminals = 0;
            for (cpw.mods.fml.common.network.simpleimpl.IMessage message : wire)
                if (message instanceof HologramNetwork.State
                    && ((HologramNetwork.State) message).tag.getBoolean("transportError")) {
                    terminals++;
                    net.minecraft.nbt.NBTTagCompound terminal = ((HologramNetwork.State) message).tag;
                    SimpleAssert.eq(origin.id, terminal.getString("session"), "teardown error keeps exact cancelled session");
                    SimpleAssert.eq(origin.uiSequence, terminal.getLong("uiSequence"), "teardown error keeps exact cancelled UI");
                    SimpleAssert.eq(origin.blueprintClientTaskToken, terminal.getLong("clientTaskToken"),
                        "teardown error cannot acknowledge a newer client task");
                }
            SimpleAssert.eq(1, terminals, "teardown emits exactly one direct terminal error");
            int afterClear = wire.size();
            HologramWorkspace.clear();
            HologramPresetActivation.cancel(origin, "repeated teardown");
            SimpleAssert.eq(afterClear, wire.size(), "repeated teardown cannot replay the terminal callback");
        } finally {
            HologramNetwork.clearServerQueues();
            channel.set(null, previous);
            wire.clear();
            connected.playerNetServerHandler.netManager.channel().close();
        }
    }

    private static void publication(EntityPlayerMP player) {
        HologramSession origin = origin(player), candidate = candidate(player, "large", 1024);
        HologramCapture oldCapture = origin.capture;
        long oldRevision = origin.planRevision;
        Receipt receipt = new Receipt();
        SimpleAssert.that(begin(origin, candidate, null, receipt), "saved-preset candidate is admitted");
        SimpleAssert.that(HologramWorkspace.find(player.getUniqueID()) == origin, "admission leaves the old route published");
        SimpleAssert.that(candidate.capture == null && candidate.processingSource == null, "admission does not traverse projection/copy");
        HologramPresetActivation.tick(origin, 0, Long.MAX_VALUE);
        HologramPresetActivation.tick(origin, 7, 0);
        SimpleAssert.that(candidate.processingSource == null, "zero work and time cannot consume DTO records");
        int slices = 0;
        while (HologramPresetActivation.pending(origin)) {
            SimpleAssert.that(HologramWorkspace.find(player.getUniqueID()) == origin, "every incomplete phase retains old route");
            SimpleAssert.eq("old", HologramSessionPolicy.selectedPreset(origin), "selection is not changed before activation");
            HologramPresetActivation.tick(origin, 7, Long.MAX_VALUE);
            SimpleAssert.that(++slices < 20000, "real staged activation terminates");
        }
        SimpleAssert.that(slices > 500, "large saved preset genuinely traverses many bounded slices");
        SimpleAssert.eq(1, receipt.completed, "exactly one actual server activation receipt");
        SimpleAssert.eq(0, receipt.failed, "successful activation cannot also fail");
        SimpleAssert.that(receipt.ready == candidate && HologramWorkspace.find(player.getUniqueID()) == candidate,
            "completion is delivered only after candidate is active");
        SimpleAssert.eq(1024, candidate.capture.cells.size(), "complete projection exists before activation");
        SimpleAssert.eq(1024, candidate.planTargets.size(), "complete plan exists before activation");
        SimpleAssert.that(candidate.processingSource != candidate.virtualPreset
            && candidate.processingSource.cells.get(0) != candidate.virtualPreset.cells.get(0), "processing input has independent mutable DTO ownership");
        SimpleAssert.that(origin.capture == oldCapture && origin.planRevision == oldRevision, "old editor data is untouched");
        SimpleAssert.that(candidate.presentationTransition != null && candidate.presentationTransition.origin == origin,
            "snapshot publication retains the existing rollback contract; activation is not client ACK");
        SimpleAssert.eq("large", HologramSessionPolicy.selectedPreset(candidate), "selection changes with actual activation");
        HologramPresetActivation.tick(origin, 7, Long.MAX_VALUE);
        SimpleAssert.eq(1, receipt.completed, "retired continuation cannot publish twice");
    }

    private static void cancellation(EntityPlayerMP player) {
        for (String fence : new String[] { "revision", "ui", "token", "cancel", "world", "preparation", "error", "replacement" }) {
            HologramSession origin = origin(player), candidate = candidate(player, fence, 32);
            Receipt receipt = new Receipt();
            boolean[] current = { true };
            HologramPresetActivation.Preparation prepare = new HologramPresetActivation.Preparation() {
                @Override
                public boolean current() { return current[0]; }

                @Override
                public boolean step(HologramSession ignored, int maxWork, long maxNanos) throws java.io.IOException {
                    if ("error".equals(fence)) throw new java.io.IOException("injected candidate rejection");
                    return true;
                }
            };
            SimpleAssert.that(begin(origin, candidate, prepare, receipt), "fenced candidate admitted: " + fence);
            HologramPresetActivation.tick(origin, 3, Long.MAX_VALUE);
            net.minecraft.world.World beforeWorld = player.worldObj;
            if ("revision".equals(fence)) origin.planRevision++;
            if ("ui".equals(fence)) origin.uiSequence++;
            if ("token".equals(fence)) origin.blueprintClientTaskToken++;
            if ("cancel".equals(fence)) HologramPresetActivation.cancel(origin, "explicit cancel");
            if ("world".equals(fence)) player.worldObj = null;
            if ("preparation".equals(fence)) current[0] = false;
            if ("replacement".equals(fence)) HologramWorkspace.activate(player.getUniqueID(), candidate(player, "replacement", 0));
            try {
                int slices = 0;
                while (HologramPresetActivation.pending(origin)) {
                    HologramPresetActivation.tick(origin, 7, Long.MAX_VALUE);
                    SimpleAssert.that(++slices < 1000, "rejected candidate terminates: " + fence);
                }
            } finally {
                player.worldObj = beforeWorld;
            }
            SimpleAssert.eq(0, receipt.completed, "fenced candidate never succeeds: " + fence);
            SimpleAssert.eq(1, receipt.failed, "fenced candidate gets exactly one terminal failure: " + fence);
            SimpleAssert.that(HologramWorkspace.find(player.getUniqueID()) != candidate, "half-built candidate is never active: " + fence);
            SimpleAssert.eq("old", HologramSessionPolicy.selectedPreset(origin), "failed candidate does not replace tool selection: " + fence);
            HologramPresetActivation.cancel(origin, "duplicate cancellation");
            HologramPresetActivation.tick(origin, 7, Long.MAX_VALUE);
            SimpleAssert.eq(1, receipt.failed, "terminal callback is not replayed: " + fence);
        }
    }

    private static void waitingAndSupersession(EntityPlayerMP player) {
        HologramSession origin = origin(player), first = candidate(player, "first", 32);
        Receipt old = new Receipt(), latest = new Receipt();
        SimpleAssert.that(begin(origin, first, null, old), "first activation admitted");
        player.inventory.currentItem = 1;
        HologramPresetActivation.tick(origin, 7, Long.MAX_VALUE);
        SimpleAssert.that(HologramPresetActivation.pending(origin) && !HologramPresetActivation.canAdvance(origin),
            "temporarily different held tool reports waiting and is excluded from idle continuation");
        SimpleAssert.eq(0, old.failed, "temporary tool wait is not an arbitrary timeout failure");
        player.inventory.currentItem = 0;
        SimpleAssert.that(HologramPresetActivation.canAdvance(origin), "returning original tool resumes existing candidate");
        HologramSession second = candidate(player, "second", 32);
        SimpleAssert.that(begin(origin, second, null, latest), "latest activation supersedes the old task");
        SimpleAssert.eq(1, old.failed, "superseded request receives one terminal cancellation");
        int slices = 0;
        while (HologramPresetActivation.pending(origin)) {
            HologramPresetActivation.tick(origin, 7, Long.MAX_VALUE);
            SimpleAssert.that(++slices < 1000, "latest activation completes after resume");
        }
        SimpleAssert.eq(0, old.completed, "retired candidate is never published");
        SimpleAssert.eq(1, latest.completed, "only the latest candidate publishes");
    }

    private static boolean begin(HologramSession origin, HologramSession candidate,
        HologramPresetActivation.Preparation prepare, Receipt receipt) {
        return HologramPresetActivation.begin(origin, candidate, candidate.virtualPreset, candidate.virtualPreset.id,
            prepare, receipt);
    }

    private static HologramSession origin(EntityPlayerMP player) {
        player.inventory.currentItem = 0;
        HologramSession origin = candidate(player, "old", 1);
        origin.capture = new HologramCapture(null, player.getHeldItem());
        origin.capture.cells.add(new HologramCapture.Cell(null, 0, 64, 0));
        HologramWorkspace.activate(player.getUniqueID(), origin);
        player.getHeldItem().getTagCompound().setString(HologramSessionKeys.SELECTED_PRESET, "old");
        return origin;
    }

    private static HologramSession candidate(EntityPlayerMP player, String id, int cells) {
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
        s.virtual = true;
        s.openPurpose = "blueprintEdit";
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.schema = 2;
        s.virtualPreset.id = id;
        s.virtualPreset.name = id;
        s.virtualPreset.controllerType = "area";
        s.materialSettings.main = s.materialSettings.me = s.materialSettings.containers = false;
        for (int i = 0; i < cells; i++)
            s.virtualPreset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), i % 32, 0, i / 32, -1, false));
        return s;
    }

    private static EntityPlayerMP player() throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.isCreativeMode = true;
        player.capabilities.allowEdit = true;
        player.posY = 64;
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        java.util.UUID identity = new java.util.UUID(430, 431);
        for (Field member : net.minecraft.entity.Entity.class.getDeclaredFields())
            if (member.getType() == java.util.UUID.class && !Modifier.isStatic(member.getModifiers())) {
                member.setAccessible(true);
                member.set(player, identity);
            }
        for (Field member : net.minecraft.entity.player.EntityPlayer.class.getDeclaredFields())
            if (member.getType() == com.mojang.authlib.GameProfile.class) {
                member.setAccessible(true);
                member.set(player, new com.mojang.authlib.GameProfile(identity, "activation"));
            }
        return player;
    }

    private static final class Receipt implements HologramPresetActivation.Completion {
        int completed, failed;
        HologramSession ready;

        @Override
        public void completed(HologramSession candidate) {
            completed++;
            ready = candidate;
            SimpleAssert.that(HologramWorkspace.find(candidate.player.getUniqueID()) == candidate,
                "success callback observes the active route");
        }

        @Override
        public void failed(String reason) {
            failed++;
            SimpleAssert.that(reason != null && !reason.isEmpty(), "terminal failure includes an actionable reason");
        }
    }
}
