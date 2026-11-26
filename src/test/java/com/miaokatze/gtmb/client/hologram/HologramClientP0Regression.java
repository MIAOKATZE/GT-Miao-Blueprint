// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.miaokatze.gtmb.hologram.HologramLocalControllerCapture;
import com.miaokatze.gtmb.hologram.HologramSnapshotAssembly;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Calls the production bind/snapshot/reopen and saturated receive paths with a detached Minecraft owner. */
public final class HologramClientP0Regression {

    private HologramClientP0Regression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramClientP0Regression.class, args)) return;
        run();
    }

    public static void run() {
        try {
            Field singleton = null;
            for (Field candidate : Minecraft.class.getDeclaredFields())
                if (candidate.getType() == Minecraft.class && Modifier.isStatic(candidate.getModifiers()))
                    singleton = candidate;
            if (singleton == null) throw new NoSuchFieldException("Minecraft singleton");
            singleton.setAccessible(true);
            Object previous = singleton.get(null);
            HologramState previousState = HologramClient.state;
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            Minecraft minecraft = (Minecraft) unsafe.allocateInstance(new DetachedLoader().minecraftClass());
            try {
                singleton.set(null, minecraft);
                localControllerHeader();
                firstHeldToolIdentity();
                localControllerSignals();
                actualCaptureLifecycle();
                nonblockingReceive(unsafe, minecraft);
            } finally {
                HologramBackgroundTasks.cancelled("p0-D");
                HologramClient.state = previousState;
                singleton.set(null, previous);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("client P0 production lifecycle", failure);
        }
    }

    private static void firstHeldToolIdentity() {
        ItemStack original = new ItemStack(Items.stick);
        ItemStack synced = original.copy();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("gtitHologramToolId", "6bd50ebc-8119-4ff9-9f00-39567ea77212");
        synced.setTagCompound(tag);
        SimpleAssert.that(
            HologramScreen.sameToolWithFirstIdentity(original, synced),
            "normal server assignment of the first held tool ID must not dismiss the native structure");
        synced.getTagCompound()
            .setBoolean("changed", true);
        SimpleAssert.that(
            !HologramScreen.sameToolWithFirstIdentity(original, synced),
            "a different stored target or configuration must not be adopted as the same tool");
        synced.getTagCompound()
            .removeTag("changed");
        synced.getTagCompound()
            .setString("gtitHologramToolId", "not a canonical tool ID");
        SimpleAssert.that(
            !HologramScreen.sameToolWithFirstIdentity(original, synced),
            "unrecognized identities cannot become construction authority");
    }

    private static void localControllerSignals() throws ReflectiveOperationException {
        ItemStack projector = new ItemStack(Items.stick, 1);
        ChannelDataAccessor.setChannelData(projector, "coil", 4);
        ChannelDataAccessor.setChannelData(projector, "width", 8);
        projector.getTagCompound()
            .setInteger("gtitHologramMain", 3);
        projector.getTagCompound()
            .setString("gtitHologramDraft", "server owned");
        NBTTagCompound initial = HologramLocalControllerCapture.toolSettings(projector);
        SimpleAssert.eq(1, initial.getInteger("main"), "fresh workbook ignores the old workspace master signal");
        SimpleAssert.eq(
            4,
            initial.getCompoundTag("channels")
                .getInteger("coil"),
            "native child signal is shown");
        NBTTagCompound active = (NBTTagCompound) initial.copy();
        active.setInteger("main", 3);
        active.setBoolean("mainExplicit", true);
        active.getCompoundTag("channels")
            .removeTag("coil");
        ChannelDataAccessor.setChannelData(projector, "coil", 5);
        ChannelDataAccessor.setChannelData(projector, "width", 9);
        ChannelDataAccessor.setChannelData(projector, "glass", 2);
        HologramScreen
            .rebaseControllerChannels(active, initial, HologramLocalControllerCapture.toolSettings(projector));
        Method make = HologramLocalControllerCapture.class
            .getDeclaredMethod("previewTrigger", ItemStack.class, NBTTagCompound.class);
        make.setAccessible(true);
        ItemStack preview = (ItemStack) make.invoke(null, projector, active);
        SimpleAssert.that(preview.getItem() == projector.getItem(), "standard preview keeps actual trigger item");
        SimpleAssert.eq(3, preview.stackSize, "the copied trigger receives the selected master signal");
        SimpleAssert.eq(3, ChannelDataAccessor.getChannelData(preview, "coil"), "cleared child inherits master");
        SimpleAssert.eq(9, ChannelDataAccessor.getChannelData(preview, "width"), "rescan reads newer inherited values");
        SimpleAssert.eq(2, ChannelDataAccessor.getChannelData(preview, "glass"), "new live tool channel is included");
        SimpleAssert.eq(5, ChannelDataAccessor.getChannelData(projector, "coil"), "preview did not edit held item");
        SimpleAssert.that(
            !preview.getTagCompound()
                .hasKey("gtitHologramDraft"),
            "server draft data stays out of trigger");
    }

    private static void localControllerHeader() {
        TileEntity source = new TileEntity() {

            @Override
            public net.minecraft.block.Block getBlockType() {
                return Blocks.furnace;
            }
        };
        source.xCoord = 17;
        source.yCoord = 62;
        source.zCoord = -8;
        HologramLocalControllerCapture task = new HologramLocalControllerCapture(source, 4, 2);
        NBTTagCompound opening = task.opening();
        SimpleAssert
            .that(task.readyCapabilities() == null, "an unopened native declaration cannot claim resolved channels");
        SimpleAssert.that(opening.getBoolean("localStandardController"), "native workbench recognizes local source");
        SimpleAssert.that(opening.getBoolean("capturePending"), "local geometry generation temporarily gates edits");
        SimpleAssert.eq("projection", opening.getString("editorWorkspace"), "preserve original structure workspace");
        SimpleAssert.eq(-8, opening.getInteger("z"), "display follows the source controller anchor");
        SimpleAssert.eq(
            gregtech.api.enums.GTValues.VN.length - 1,
            opening.getInteger("hatchTierMax"),
            "the local preset selector displays the real GT voltage range without a server query");
        SimpleAssert.that(
            !opening.getBoolean("presetAvailable"),
            "the tier catalogue alone does not prove authoritative declared quotas");
        nativeControllerActionContract();
        SimpleAssert.that(
            !opening.hasKey("nativeStage") && !opening.hasKey("nativeObservationToken"),
            "a client-only geometry header cannot invent server formation evidence");
        for (String serverField : new String[] { "controllerOpenId", "controllerOpening", "snapshotPending",
            "planRevision", "placementReviewNonce", "toolId" })
            SimpleAssert.that(!opening.hasKey(serverField), "local preview cannot invent " + serverField);
        NBTTagCompound unavailable = task.unavailable("local fixture unsupported");
        SimpleAssert.that(unavailable.getBoolean("localStructureUnavailable"), "unavailable stays in the local view");
        SimpleAssert.that(!unavailable.getBoolean("capturePending"), "unavailable result retires the local task");
        SimpleAssert
            .eq(opening.getString("session"), unavailable.getString("session"), "unavailable stays in workbench");
    }

    private static void nativeControllerActionContract() {
        HologramNativeControllerState state = new HologramNativeControllerState();
        state.targetReady();
        for (Operation action : Operation.values()) SimpleAssert.that(
            !state.blocker(action)
                .isEmpty(),
            "geometry alone never authorizes " + action);
        long revision = state.targetRevision();
        SimpleAssert.that(
            state.observe(new ServerObservation(236, 0, 64, 0, Stage.CONTROLLER_ONLY, 1, revision, "")),
            "an exact server observation binds the current target");
        actions(state, Operation.BUILD);
        SimpleAssert.eq("仅控制器", state.statusCaption(), "server observation replaces the opening progress caption");
        state.observe(new ServerObservation(236, 0, 64, 0, Stage.UNFORMED, 2, revision, ""));
        SimpleAssert.eq(Operation.REPAIR, state.action(), "unfinished structures default to repair");
        actions(state, Operation.REPAIR, Operation.DEMOLISH);
        state.observe(new ServerObservation(236, 0, 64, 0, Stage.FORMED, 3, revision, ""));
        SimpleAssert.eq(Operation.REPLACE, state.action(), "formed structures default to replacement");
        actions(state, Operation.REPLACE, Operation.DEMOLISH);
        SimpleAssert.eq(
            "结构已成型",
            state.statusCaption(),
            "formation status uses server evidence rather than client active/idle flags");
        state.targetChanged();
        SimpleAssert
            .that(state.targetRevision() > revision, "changing configuration advances the complete target revision");
        SimpleAssert.that(
            !state.observe(new ServerObservation(236, 0, 64, 0, Stage.FORMED, 4, revision, "")),
            "late server observations cannot authorize a newer target");
        for (Operation action : Operation.values()) SimpleAssert.that(
            !state.blocker(action)
                .isEmpty(),
            "invalidated world evidence cannot authorize " + action);
        state.targetFailed("Fixture invalid optional preset");
        state.observe(new ServerObservation(236, 0, 64, 0, Stage.FORMED, 5, state.targetRevision(), ""));
        actions(state, Operation.DEMOLISH);
        SimpleAssert
            .that(state.select(Operation.DEMOLISH), "a bad desired preset does not block whole-machine demolition");
        state.targetReady();
        actions(state, Operation.REPLACE, Operation.DEMOLISH);
        NBTTagCompound metadata = state.metadata();
        SimpleAssert
            .eq("FORMED", metadata.getString("nativeStage"), "published formation comes from the server observation");
        SimpleAssert.eq(
            Operation.DEMOLISH.ordinal(),
            metadata.getInteger("nativeAction"),
            "the selected action is independent of target configuration");
        SimpleAssert.eq(
            5L,
            metadata.getLong("nativeObservationToken"),
            "the published state retains its exact observation token");
    }

    private static void actions(HologramNativeControllerState state, Operation... allowed) {
        var expected = java.util.Arrays.asList(allowed);
        for (Operation action : Operation.values()) SimpleAssert.eq(
            expected.contains(action),
            state.blocker(action)
                .isEmpty(),
            "native action matrix for " + action);
    }

    private static void actualCaptureLifecycle() {
        SimpleAssert.that(!HologramBackgroundTasks.busy(), "capture fixture starts without stealing an active owner");
        HologramClient.state = state("p0-A", 9, 1, false);
        HologramBackgroundTasks.command("p0-B", 1, "scan");
        HologramBackgroundTasks.snapshot(state("p0-A", 10, 1, false));
        SimpleAssert.that(HologramBackgroundTasks.busy(), "old A completion cannot finish a B command");
        publish("p0-B", 1, 1, false);
        SimpleAssert.that(HologramBackgroundTasks.succeeded(), "A to B accepts B revision 1 despite A revision 9");
        HologramBackgroundTasks.command("p0-C", 1, "scan");
        publish("p0-C", 1, 1, false);
        SimpleAssert.that(HologramBackgroundTasks.succeeded(), "B to C independently accepts C revision 1");

        HologramBackgroundTasks.command("p0-C", 2, "scan");
        HologramBackgroundTasks.snapshot(state("p0-C", 1, 1, false));
        SimpleAssert.that(HologramBackgroundTasks.busy(), "same-session previous command receipt cannot finish rescan");
        publish("p0-C", 1, 2, true);
        SimpleAssert.that(HologramBackgroundTasks.busy(), "capturePending retains the same-session owner");
        publish("p0-C", 2, 2, false);
        SimpleAssert.that(HologramBackgroundTasks.succeeded(), "same-session rescan requires a newer revision");
        HologramBackgroundTasks.command("p0-C", 3, "scan");
        publish("p0-C", 2, 3, false);
        SimpleAssert.that(
            !HologramBackgroundTasks.busy() && !HologramBackgroundTasks.succeeded(),
            "equal revision cannot become success through a weakened greater-than-or-equal gate");

        HologramBackgroundTasks.command("p0-C", 4, "scan");
        long token = HologramBackgroundTasks.token();
        HologramBackgroundTasks.detach();
        SimpleAssert.that(HologramBackgroundTasks.reopen(), "actual reopen attaches a fresh business screen");
        SimpleAssert.that(
            HologramBackgroundTasks.token() == token && !HologramBackgroundTasks.detached(),
            "reopen preserves the exact worker and revision baseline");
        HologramBackgroundTasks.cancel();
        long cancellation = HologramBackgroundTasks.commandSequence("p0-C");
        SimpleAssert
            .that(cancellation > 4 && HologramBackgroundTasks.busy(), "cancel waits for its actual newer receipt");
        publish("p0-C", 3, 4, false);
        SimpleAssert.that(HologramBackgroundTasks.busy(), "late pre-cancel success cannot end cancellation");
        HologramBackgroundTasks.cancelled("p0-C");
        SimpleAssert.that(
            !HologramBackgroundTasks.busy() && !HologramBackgroundTasks.succeeded(),
            "authoritative cancellation is terminal without a success notification");
        HologramBackgroundTasks.command("p0-D", 1, "scan");
        HologramBackgroundTasks.snapshot(state("p0-C", 4, cancellation, false));
        SimpleAssert.that(HologramBackgroundTasks.busy(), "old cancelled session cannot finish reopened capture");
        publish("p0-D", 1, 1, false);
        SimpleAssert
            .that(HologramBackgroundTasks.succeeded(), "new capture after cancel receives its own revision baseline");
    }

    private static void publish(String session, long revision, long command, boolean pending) {
        HologramClient.state = state(session, revision, command, pending);
        HologramBackgroundTasks.snapshot(HologramClient.state);
    }

    private static HologramState state(String session, long revision, long command, boolean pending) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", session);
        data.setString("generation", session);
        data.setLong("planRevision", revision);
        data.setLong("uiSequence", command);
        data.setBoolean("supported", true);
        data.setBoolean("capturePending", pending);
        data.setTag("cells", new NBTTagList());
        return new HologramState(data);
    }

    private static void nonblockingReceive(sun.misc.Unsafe unsafe, Minecraft minecraft)
        throws ReflectiveOperationException {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HologramSnapshotInbox<NBTTagCompound> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("decoder gate timed out");
            return new NBTTagCompound();
        });
        Field inboxField = field(HologramClient.class, "snapshotInbox");
        Object inboxBase = unsafe.staticFieldBase(inboxField);
        long inboxOffset = unsafe.staticFieldOffset(inboxField);
        Object previousInbox = unsafe.getObject(inboxBase, inboxOffset);
        Field assemblyWorld = field(HologramClient.class, "assemblyWorld");
        Field assemblyConnection = field(HologramClient.class, "assemblyConnection");
        Field assembly = field(HologramClient.class, "assembly");
        Object previousWorld = assemblyWorld.get(null), previousConnection = assemblyConnection.get(null),
            previousAssembly = assembly.get(null);
        ExecutorService owner = Executors.newSingleThreadExecutor();
        try {
            minecraft.theWorld = (WorldClient) unsafe.allocateInstance(WorldClient.class);
            HologramClient.state = null;
            assemblyWorld.set(null, minecraft.theWorld);
            assemblyConnection.set(null, null);
            assembly.set(null, new HologramSnapshotAssembly());
            unsafe.putObject(inboxBase, inboxOffset, inbox);
            inbox.offer(new byte[] { 1, 0, 0 }, 100);
            SimpleAssert.that(entered.await(5, TimeUnit.SECONDS), "decoder is actually held before saturating receive");
            NBTTagCompound ack = new NBTTagCompound();
            ack.setString("phase", "ACK");
            inbox.offerValue(ack);
            Method receive = HologramClient.class.getDeclaredMethod("receive", NBTTagCompound.class);
            receive.setAccessible(true);
            NBTTagCompound fragment = new NBTTagCompound();
            fragment.setBoolean("transportFragment", true);
            fragment.setLong("transportSequence", 101);
            fragment.setInteger("transportTotal", 1);
            fragment.setByteArray("transportBytes", new byte[] { 1 });
            owner.submit(() -> {
                try {
                    receive.invoke(null, fragment);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            })
                .get(1, TimeUnit.SECONDS);
            SimpleAssert.that(release.getCount() == 1, "actual receive returns while decoder remains blocked");
            SimpleAssert
                .that(!inbox.offerValue(new NBTTagCompound()), "saturation never grows or clears the accepted FIFO");
            release.countDown();
            inbox.awaitNext();
            SimpleAssert
                .eq("ACK", inbox.poll().value.getString("phase"), "previously admitted ACK survives saturation");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
            | InterruptedException failure) {
            throw new AssertionError("client receive waited on its decoder", failure);
        } finally {
            release.countDown();
            owner.shutdownNow();
            inbox.close();
            unsafe.putObject(inboxBase, inboxOffset, previousInbox);
            assemblyWorld.set(null, previousWorld);
            assemblyConnection.set(null, previousConnection);
            assembly.set(null, previousAssembly);
            minecraft.theWorld = null;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    /** Only presentation delivery is replaced; the production task and HologramScreen constructor still run. */
    private static final class DetachedLoader extends ClassLoader {

        DetachedLoader() {
            super(HologramClientP0Regression.class.getClassLoader());
        }

        Class<?> minecraftClass() {
            String name = "com/miaokatze/gtmb/client/hologram/P0DetachedMinecraft";
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "net/minecraft/client/Minecraft", null);
            MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "displayGuiScreen",
                "(Lnet/minecraft/client/gui/GuiScreen;)V",
                null,
                null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitFieldInsn(
                Opcodes.PUTFIELD,
                "net/minecraft/client/Minecraft",
                "currentScreen",
                "Lnet/minecraft/client/gui/GuiScreen;");
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(2, 2);
            method.visitEnd();
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
