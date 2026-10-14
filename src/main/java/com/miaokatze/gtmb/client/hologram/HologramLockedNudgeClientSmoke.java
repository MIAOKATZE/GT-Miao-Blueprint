package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Drives real keyboard state and independently published server coordinates through the Socket fixture. */
public final class HologramLockedNudgeClientSmoke {

    interface Keys {

        void set(int key, boolean pressed) throws Exception;
    }

    interface Receipts {

        NBTTagCompound read() throws Exception;
    }

    interface Capture {

        void save(String name) throws Exception;
    }

    private final Keys keys;
    private final Receipts receipts;
    private final Capture capture;
    private final String session;
    private final int[] original;
    private final int placed, stock;
    private final float oldYaw;
    private final long started = System.nanoTime();
    private final List<Step> steps = new ArrayList<>();
    private int next, phase, heldClientTicks, uiTicks;
    private int[] expected;
    private long sentTick, previousNonce, releasedTick, uiRevision, uiNonce;
    private int heldStartZ;
    private boolean uiStarted, uiDone, heldChecked, rapidChecked, copiedBefore;
    private long rapidNonce, rapidTick;
    private static final int[] ARROW_KEYS = { Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT,
        Keyboard.KEY_RIGHT };
    private final boolean[] desiredArrows = new boolean[4];
    private boolean desiredAlt;
    private long sampledTick = -1;
    private Exception samplingFailure;
    private boolean samplingActive = true;

    HologramLockedNudgeClientSmoke(Minecraft mc, Keys keys, Receipts receipts, Capture capture) throws Exception {
        this.keys = keys;
        this.receipts = receipts;
        this.capture = capture;
        NBTTagCompound state = HologramClient.state.data;
        session = state.getString("session");
        original = anchor(state);
        oldYaw = mc.thePlayer.rotationYaw;
        NBTTagCompound receipt = receipts.read();
        placed = receipt.getInteger("blueprintPlaced");
        stock = receipt.getInteger("blueprintStock");
        for (int key : ARROW_KEYS) setKey(key, false);
        setKey(Keyboard.KEY_LMENU, false);
        sentTick = mc.theWorld.getTotalWorldTime();
        for (int yaw : new int[] { 0, 90, 180, 270 }) {
            int[][] forward = { { 0, 0, 1 }, { -1, 0, 0 }, { 0, 0, -1 }, { 1, 0, 0 } };
            int[] offset = forward[yaw / 90];
            steps.add(new Step(Keyboard.KEY_UP, false, yaw, offset[0], 0, offset[2]));
            steps.add(new Step(Keyboard.KEY_DOWN, false, yaw, -offset[0], 0, -offset[2]));
        }
        steps.add(new Step(Keyboard.KEY_LEFT, false, 0, 1, 0, 0));
        steps.add(new Step(Keyboard.KEY_RIGHT, false, 0, -1, 0, 0));
        steps.add(new Step(Keyboard.KEY_UP, true, 0, 0, 1, 0));
        steps.add(new Step(Keyboard.KEY_DOWN, true, 0, 0, -1, 0));
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    /** Display.poll overwrites injected LWJGL state between frames; apply it before the production END listener. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void sampleKeys(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !samplingActive || uiDone) return;
        try {
            for (int i = 0; i < ARROW_KEYS.length; i++) keys.set(ARROW_KEYS[i], desiredArrows[i]);
            keys.set(Keyboard.KEY_LMENU, desiredAlt);
            Minecraft mc = Minecraft.getMinecraft();
            sampledTick = mc.theWorld == null ? -1 : mc.theWorld.getTotalWorldTime();
        } catch (Exception failure) {
            samplingFailure = failure;
        }
    }

    private void setKey(int key, boolean pressed) throws Exception {
        for (int i = 0; i < ARROW_KEYS.length; i++) if (ARROW_KEYS[i] == key) desiredArrows[i] = pressed;
        if (key == Keyboard.KEY_LMENU) desiredAlt = pressed;
        keys.set(key, pressed);
    }

    boolean worldTick(Minecraft mc) throws Exception {
        try {
            return worldTickActive(mc);
        } catch (Exception | Error failure) {
            stopSampling();
            throw failure;
        }
    }

    private boolean worldTickActive(Minecraft mc) throws Exception {
        if (samplingFailure != null)
            throw new AssertionError("nudge key sampler failed " + diagnostics(mc), samplingFailure);
        if (System.nanoTime() - started > 30000000000L)
            throw new AssertionError("locked arrow Socket regression exceeded thirty seconds " + diagnostics(mc));
        NBTTagCompound state = HologramClient.state.data, receipt = receipts.read();
        if (mc.currentScreen != null || !session.equals(state.getString("session"))
            || !state.getBoolean("virtualLocked")
            || state.getBoolean("moving")
            || state.getInteger("job") != 0)
            throw new AssertionError("arrow nudge left its locked unpaid world context");
        if (receipt.getInteger("blueprintPlaced") != placed || receipt.getInteger("blueprintStock") != stock)
            throw new AssertionError("arrow preview changed paid world or materials");
        if (!copiedBefore) {
            if (!copyRawReceipt("v008-locked-nudge-before.nbt")) return false;
            copiedBefore = true;
        }
        long tick = mc.theWorld.getTotalWorldTime();
        if (phase == 0) {
            if (tick - sentTick < 4) return false;
            if (next == steps.size()) {
                if (heldChecked) {
                    phase = 5;
                    return false;
                }
                heldStartZ = state.getInteger("z");
                previousNonce = state.getLong("placementReviewNonce");
                mc.thePlayer.rotationYaw = 0;
                setKey(Keyboard.KEY_UP, true);
                sentTick = tick;
                phase = 2;
                return false;
            }
            Step step = steps.get(next);
            int[] before = anchor(state);
            expected = new int[] { before[0] + step.dx, before[1] + step.dy, before[2] + step.dz };
            previousNonce = state.getLong("placementReviewNonce");
            mc.thePlayer.rotationYaw = step.yaw;
            setKey(Keyboard.KEY_LMENU, step.alt);
            setKey(step.key, true);
            sentTick = tick;
            phase = 1;
            return false;
        }
        if (phase == 1) {
            Step step = steps.get(next);
            setKey(step.key, false);
            setKey(Keyboard.KEY_LMENU, false);
            if (state.getLong("placementReviewNonce") == previousNonce) return false;
            assertAnchor(expected, state, "camera yaw=" + step.yaw + " key=" + step.key + " Alt=" + step.alt);
            NBTTagCompound independent = receipt.getCompoundTag("blueprintSession");
            if (independent.getLong("placementReviewNonce") < state.getLong("placementReviewNonce")) return false;
            if (independent.getBoolean("placementReviewIssued") || independent.getBoolean("forcePlacementReady"))
                throw new AssertionError("nudge retained an old material confirmation capability");
            assertAnchor(expected, receipt, "independent server anchor");
            if (!independent.getBoolean("virtualLocked") || independent.getInteger("job") != 0)
                throw new AssertionError("server arrow changed lock or started a job");
            System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS v008 real arrow one voxel yaw=" + step.yaw
                    + " Alt="
                    + step.alt
                    + " target="
                    + java.util.Arrays.toString(expected));
            next++;
            phase = 0;
            return false;
        }
        if (phase == 2) {
            heldClientTicks++;
            if (tick - sentTick < 16 || heldClientTicks < 16) return false;
            setKey(Keyboard.KEY_UP, false);
            releasedTick = tick;
            phase = 3;
            return false;
        }
        if (phase == 3) {
            if (tick - releasedTick < 6) return false;
            int moved = state.getInteger("z") - heldStartZ;
            if (moved < 2 || moved > 1 + (releasedTick - sentTick) / 4)
                throw new AssertionError("held arrow lacks bounded real repeat: " + moved);
            if (state.getInteger("x") != original[0] || state.getInteger("y") != original[1])
                throw new AssertionError("held horizontal arrow changed another axis");
            expected = anchor(state);
            previousNonce = state.getLong("placementReviewNonce");
            for (int i = 0; i < moved; i++) steps.add(new Step(Keyboard.KEY_DOWN, false, 0, 0, 0, -1));
            heldChecked = true;
            phase = 4;
            releasedTick = tick;
            return false;
        }
        if (phase == 4) {
            if (tick - releasedTick < 6) return false;
            assertAnchor(expected, state, "released arrow stays stable");
            if (state.getLong("placementReviewNonce") != previousNonce)
                throw new AssertionError("released arrow kept repeating");
            phase = 5;
            return false;
        }
        if (phase == 5) {
            if (next < steps.size()) {
                phase = 0;
                return false;
            }
            assertAnchor(original, state, "all arrow tests return to the original unpaid anchor");
            if (!rapidChecked) {
                rapidNonce = state.getLong("placementReviewNonce");
                rapidTick = tick;
                mc.thePlayer.rotationYaw = 0;
                setKey(Keyboard.KEY_UP, true);
                phase = 6;
                return false;
            }
            mc.thePlayer.rotationYaw = oldYaw;
            if (HologramRenderer.lastWorldGeometry == 0 || HologramRenderer.lastWorldPlacementOutlineColor != 0x66d9ef)
                return false;
            if (!copyRawReceipt("v008-locked-nudge-after.nbt")) return false;
            capture.save("v008-locked-arrow-horizontal-vertical-repeat-no-consumption");
            return true;
        }
        if (phase == 6) {
            setKey(Keyboard.KEY_UP, false);
            setKey(Keyboard.KEY_RIGHT, true);
            phase = 7;
            return false;
        }
        if (phase == 7) {
            setKey(Keyboard.KEY_RIGHT, false);
            phase = 8;
            return false;
        }
        if (phase == 8) {
            if (tick - rapidTick > 12)
                throw new AssertionError("rapid different-arrow short presses were lost or stalled");
            if (state.getLong("placementReviewNonce") < rapidNonce + 2) return false;
            assertAnchor(
                new int[] { original[0] - 1, original[1], original[2] + 1 },
                state,
                "adjacent Up/Right short presses each produce one queued step");
            if (state.getLong("placementReviewNonce") != rapidNonce + 2)
                throw new AssertionError("released adjacent arrows produced extra steps");
            steps.add(new Step(Keyboard.KEY_DOWN, false, 0, 0, 0, -1));
            steps.add(new Step(Keyboard.KEY_LEFT, false, 0, 1, 0, 0));
            rapidChecked = true;
            phase = 0;
            System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS v008 rapid adjacent Up/Right short presses queued once each");
            return false;
        }
        return false;
    }

    boolean uiTick(Minecraft mc) throws Exception {
        try {
            return uiTickActive(mc);
        } catch (Exception | Error failure) {
            stopSampling();
            throw failure;
        }
    }

    private boolean uiTickActive(Minecraft mc) throws Exception {
        if (uiDone) return true;
        NBTTagCompound state = HologramClient.state.data;
        if (!uiStarted) {
            expected = anchor(state);
            uiRevision = state.getLong("planRevision");
            uiNonce = state.getLong("placementReviewNonce");
            setKey(Keyboard.KEY_UP, true);
            setKey(Keyboard.KEY_LMENU, true);
            uiStarted = true;
            return false;
        }
        if (!(mc.currentScreen instanceof HologramScreen)) throw new AssertionError("UI arrows closed the review");
        assertAnchor(expected, state, "UI arrow remains UI input");
        if (state.getLong("planRevision") != uiRevision || state.getLong("placementReviewNonce") != uiNonce)
            throw new AssertionError("UI arrow changed the server plan");
        if (++uiTicks < 5) return false;
        setKey(Keyboard.KEY_UP, false);
        setKey(Keyboard.KEY_LMENU, false);
        if (!copyRawReceipt("v008-locked-nudge-ui.nbt")) return false;
        uiDone = true;
        stopSampling();
        System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS v008 UI arrows leave locked plan/nonce unchanged");
        return true;
    }

    private void stopSampling() {
        if (!samplingActive) return;
        samplingActive = false;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        try {
            for (int key : ARROW_KEYS) setKey(key, false);
            setKey(Keyboard.KEY_LMENU, false);
        } catch (Exception releaseFailure) {
            samplingFailure = releaseFailure;
        }
    }

    private String diagnostics(Minecraft mc) {
        NBTTagCompound state = HologramClient.state == null ? new NBTTagCompound() : HologramClient.state.data;
        StringBuilder message = new StringBuilder("phase=").append(phase)
            .append(" next=")
            .append(next)
            .append(" worldTick=")
            .append(mc.theWorld == null ? -1 : mc.theWorld.getTotalWorldTime())
            .append(" sampledTick=")
            .append(sampledTick)
            .append(" session=")
            .append(state.getString("session"))
            .append(" revision=")
            .append(state.getLong("planRevision"))
            .append(" nonce=")
            .append(state.getLong("placementReviewNonce"))
            .append(" locked=")
            .append(state.getBoolean("virtualLocked"))
            .append(" moving=")
            .append(state.getBoolean("moving"))
            .append(" job=")
            .append(state.getInteger("job"))
            .append(" anchor=")
            .append(java.util.Arrays.toString(anchor(state)))
            .append(" desired=")
            .append(java.util.Arrays.toString(desiredArrows))
            .append(" Alt=")
            .append(desiredAlt)
            .append(" actualKeys=[");
        for (int key : ARROW_KEYS) message.append(Keyboard.isCreated() && Keyboard.isKeyDown(key))
            .append(',');
        message.append("] actualAlt=")
            .append(Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_LMENU));
        try {
            Object bus = FMLCommonHandler.instance()
                .bus();
            java.lang.reflect.Field listeners = bus.getClass()
                .getDeclaredField("listeners");
            listeners.setAccessible(true);
            for (Object listener : ((java.util.Map<?, ?>) listeners.get(bus)).keySet()) {
                if (!(listener instanceof HologramClient)) continue;
                Object context = observed(listener, "inputContext");
                message.append(" clientContext={worldSame=")
                    .append(observed(context, "world") == mc.theWorld)
                    .append(" itemSame=")
                    .append(
                        observed(context, "item") == mc.thePlayer.getHeldItem()
                            .getItem())
                    .append(" toolUuid=")
                    .append(observed(context, "uuid"))
                    .append(" screenSame=")
                    .append(observed(context, "screen") == mc.currentScreen)
                    .append(" screenChanged=")
                    .append(observed(listener, "inputScreenChanged"))
                    .append(" nudgeSession=")
                    .append(observed(listener, "nudgeInputSession"));
                for (Object key : (Object[]) observed(listener, "nudgeKeys")) message.append(" key{down=")
                    .append(observed(key, "down"))
                    .append(" blocked=")
                    .append(observed(key, "blocked"))
                    .append('}');
                Object dispatch = observed(listener, "nudgeDispatch");
                message.append(" pendingRevision=")
                    .append(observed(dispatch, "pendingRevision"))
                    .append(" pendingTick=")
                    .append(observed(dispatch, "pendingTick"))
                    .append(" lastSentTick=")
                    .append(observed(dispatch, "lastSentTick"))
                    .append(" retries=")
                    .append(observed(dispatch, "retries"))
                    .append(" retryDue=")
                    .append(observed(dispatch, "retryDue"))
                    .append(" inFlight=")
                    .append(observed(dispatch, "inFlight") != null)
                    .append(" queued=")
                    .append(observed(dispatch, "queued") != null)
                    .append('}');
            }
        } catch (ReflectiveOperationException unavailable) {
            message.append(" contextDiagnostics=")
                .append(
                    unavailable.getClass()
                        .getSimpleName());
        }
        return message.toString();
    }

    private static Object observed(Object target, String fieldName) throws ReflectiveOperationException {
        java.lang.reflect.Field field = target.getClass()
            .getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Copy the independent server publication byte-for-byte; never synthesize evidence from client NBT. */
    private static boolean copyRawReceipt(String name) throws java.io.IOException {
        Path directory = Paths.get(System.getProperty("gtmb.hologram.interactionReceiptDir", ""));
        try {
            Files.copy(
                directory.resolve("world-receipt.nbt"),
                directory.resolve(name),
                StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException publicationRace) {
            // The existing stage budget still applies while Windows atomically replaces the publication.
            return false;
        }
    }

    private static int[] anchor(NBTTagCompound state) {
        return new int[] { state.getInteger("x"), state.getInteger("y"), state.getInteger("z") };
    }

    private static void assertAnchor(int[] expected, NBTTagCompound state, String label) {
        int[] actual = state.hasKey("blueprintAnchor") ? state.getIntArray("blueprintAnchor") : anchor(state);
        if (!java.util.Arrays.equals(expected, actual)) throw new AssertionError(
            label + " expected="
                + java.util.Arrays.toString(expected)
                + " actual="
                + java.util.Arrays.toString(actual));
    }

    private static final class Step {

        final int key, dx, dy, dz;
        final boolean alt;
        final float yaw;

        Step(int key, boolean alt, float yaw, int dx, int dy, int dz) {
            this.key = key;
            this.alt = alt;
            this.yaw = yaw;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }
}
