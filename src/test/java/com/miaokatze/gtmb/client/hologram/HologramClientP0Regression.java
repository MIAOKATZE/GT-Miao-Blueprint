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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.miaokatze.gtmb.hologram.HologramSnapshotAssembly;
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
