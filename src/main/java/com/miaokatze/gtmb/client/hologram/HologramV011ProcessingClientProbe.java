package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.hologram.HologramV011Evidence;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Real six-stage author modal clicks, server ACK and exact END screenshots; shared by dev and normal fixtures. */
public final class HologramV011ProcessingClientProbe {

    private static HologramV011ProcessingClientProbe instance;
    private int stage, step;
    private long lastGeneration = -1, started;
    private volatile boolean complete;
    private NBTTagCompound capture;
    private HologramScreen captureScreen;
    private Throwable failure;
    private long nativeMouseEvents;
    private int lastMouseControl = -1;
    private int modalEndFrames, saveStep;
    private long saveStarted, saveGeneration = -1, nativeKeyEvents;
    private NBTTagCompound saveReview;
    private NBTTagCompound fullReviewBefore, fullReviewAfter;
    private NBTTagCompound keyboardWitness;

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.followupProductionFlow")
            && !Boolean.getBoolean("gtmb.hologram.followupWorkbenchClient")
            && !Boolean.getBoolean("gtmb.hologram.productionPreviewSmoke")) return;
        instance = new HologramV011ProcessingClientProbe();
        FMLCommonHandler.instance()
            .bus()
            .register(instance);
    }

    /** Called only when the owning fixture has an actual network-opened raw author screen. */
    public static boolean drive(Minecraft mc) throws Exception {
        HologramV011Evidence.require(instance != null, "processing GUI probe was installed");
        return instance.advance(mc);
    }

    static boolean completed() {
        return instance != null && instance.complete;
    }

    /** Save uses the same real author controls and AE report as a player, then waits for the pair owner ACK. */
    static boolean save(Minecraft mc, String name) throws Exception {
        require(instance != null && instance.complete, "all twelve processing END frames precede author Save");
        return instance.advanceSave(mc, name);
    }

    private boolean advanceSave(Minecraft mc, String name) throws Exception {
        if (failure != null) throw new IllegalStateException("actual processing GUI probe failed", failure);
        if (saveStarted == 0) saveStarted = System.nanoTime();
        require(System.nanoTime() - saveStarted < 180000000000L, "actual author Save/pair owner ACK timeout");
        if (capture != null || !(mc.currentScreen instanceof HologramScreen) || HologramClient.state == null)
            return false;
        HologramScreen screen = (HologramScreen) mc.currentScreen;
        NBTTagCompound ui = screen.smokeLibraryUiState();
        long generation = ui.getLong("renderGeneration");
        if (generation <= saveGeneration || screen.smokePending() || ui.getBoolean("busy")) return false;
        if (saveStep == 0) {
            if (!ui.getBoolean("library")) {
                if (!controlEnabled(ui, 149)) return false;
                click(screen, 149);
                saveGeneration = generation;
                return false;
            }
            if (!controlEnabled(ui, 140) || !displayedSource(screen, ui)) return false;
            NBTTagCompound data = HologramClient.state.data;
            require(
                data.getBoolean("blueprintProcessingComplete") && data.getBoolean("blueprintRetainNbt")
                    && data.getBoolean("blueprintRetainCovers")
                    && data.getBoolean("blueprintNbtAll"),
                "native author Save preserves the reviewed full NBT and cover choices");

            HologramMuiTextField target = (HologramMuiTextField) ((Object) HologramUiDiagnostics
                .readDiagnosticField(screen, "presetNameField"));
            require(target != null, "real author name field exists");

            float scale = ((Float) HologramUiDiagnostics.readDiagnosticField(screen, "scale"));
            int x = ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, "originX"))
                + Math.round((target.xPosition + 4) * scale);
            int y = ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, "originY"))
                + Math.round((target.yPosition + 7) * scale);
            HologramMuiProbeInput.click(screen, x, y, 0);
            nativeMouseEvents++;
            require(target.isFocused(), "native mouse focuses actual author name field");
            ControlKey control = controlKey();
            keyboardWitness = control.witness;
            try {
                control.set(true);
                HologramMuiProbeInput.key(screen, '\u0001', Keyboard.KEY_A);
                require(
                    target.getText()
                        .equals(target.getSelectedText()),
                    "native Ctrl+A selects author name");
                control.set(false);
                for (char character : name.toCharArray()) {
                    HologramMuiProbeInput.key(screen, character, 0);
                }
                nativeKeyEvents += name.length() + 1;
            } finally {
                control.restore();
            }
            require(name.equals(target.getText()), "native keyboard enters exact author name");
            click(screen, 140);
            saveStep = 1;
            saveGeneration = generation;
            return false;
        }
        if (saveStep == 1) {
            if (!ui.getBoolean("aeReview")) return false;
            if (control(ui, 181) == null) return false;
            saveReview = (NBTTagCompound) HologramClient.state.data.getCompoundTag("aeSaveReview")
                .copy();
            require(
                !saveReview.getString("transactionId")
                    .isEmpty(),
                "actual AE review nonce exists");
            click(screen, 181);
            saveStep = 2;
            saveGeneration = generation;
            return false;
        }
        NBTTagCompound data = HologramClient.state.data;
        if (ui.getBoolean("aeReview") || data.getBoolean("blueprintSavePending")
            || data.getBoolean("blueprintDirty")
            || !data.getBoolean("blueprintSaved")
            || !data.getBoolean("blueprintOperationSuccess")) return false;
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("passed", true);
        receipt.setBoolean("actualNettyClient", true);
        receipt.setBoolean("nativeAuthorSave", true);
        receipt.setBoolean("nativeAeReviewConfirmation", true);
        receipt.setInteger("processingEndFrames", modalEndFrames);
        receipt.setLong("nativeMouseEvents", nativeMouseEvents);
        receipt.setLong("nativeKeyEvents", nativeKeyEvents);
        receipt.setTag("actualKeyboardState", keyboardWitness.copy());
        receipt.setTag("actualConfirmedAeReview", saveReview.copy());
        receipt.setTag("actualSnapshot", data.copy());
        receipt.setTag("actualUi", ui.copy());
        HologramV011Evidence.write("processing-client-save-final.nbt", receipt);
        return true;
    }

    private boolean advance(Minecraft mc) throws Exception {
        if (failure != null) throw new IllegalStateException("actual processing GUI probe failed", failure);
        if (complete) return true;
        if (started == 0) started = System.nanoTime();
        require(System.nanoTime() - started < 90000000000L, "six-stage actual GUI/ACK timeout");
        if (capture != null || !(mc.currentScreen instanceof HologramScreen) || HologramClient.state == null)
            return false;
        HologramScreen screen = (HologramScreen) mc.currentScreen;
        NBTTagCompound ui = screen.smokeLibraryUiState();
        long generation = ui.getLong("renderGeneration");
        if (generation <= 0) return false;
        if (generation <= lastGeneration || screen.smokePending()) return false;
        if (!ui.getBoolean("processingFlow") && stage == 0 && step == 0) {
            NBTTagCompound source = HologramClient.state.data;
            if (source.getBoolean("capturePending") || source.getBoolean("areaImporting")
                || HologramClient.state.cells.isEmpty()
                || !displayedSource(screen, ui)) return false;
            if (!ui.getBoolean("blueprintTab") || !ui.getBoolean("library")) {
                if (!controlEnabled(ui, 149)) return false;
                click(screen, 149);
                lastGeneration = generation;
                return false;
            }
            require(
                HologramClient.state.data.getBoolean("blueprintProcessingRequired"),
                "raw author blueprint requires processing");
            if (!controlEnabled(ui, 220)) return false;
            click(screen, 220);
            lastGeneration = generation;
            return false;
        }
        if (stage < 6) {
            require(
                ui.getBoolean("processingFlow") && ui.getInteger("flowStage") == stage,
                "actual expected wizard stage " + stage);
            if (stage == 0 && step == 0 && fullReviewAfter == null) {
                require(
                    !ui.getBoolean("flowFullReview") && confirmations(screen) == 0,
                    "fresh author wizard defaults to content-based processing without manual confirmations");
                int requiredStages = ui.getInteger("flowRequiredStages");
                require(
                    requiredStages >= 0 && requiredStages <= 63,
                    "adaptive wizard observes the bounded server-required stage mask");
                fullReviewBefore = (NBTTagCompound) ui.copy();
                click(screen, 296);
                fullReviewAfter = screen.smokeLibraryUiState();
                require(
                    fullReviewAfter.getBoolean("flowFullReview") && fullReviewAfter.getInteger("flowStage") == 0
                        && !fullReviewAfter.getBoolean("flowChosen")
                        && fullReviewAfter.getInteger("flowRequiredStages") == requiredStages
                        && confirmations(screen) == 0,
                    "native visible full-review toggle requires all six manual choices from the first stage");
                lastGeneration = generation;
                return false;
            }
            require(ui.getBoolean("flowFullReview"), "all six actual author stages retain manual full review");
            if (step == 0) {
                require(confirmations(screen) == stage, "each stage still requires its own manual Next confirmation");
                if (stage == 0) {
                    require(
                        !ui.getBoolean("flowChosen"),
                        "untouched first stage has no available preset or tendency choice");
                    click(screen, 233);
                    require(confirmations(screen) == 0, "share tendency provides choices without confirming a stage");
                    click(screen, 232);
                    require(confirmations(screen) == 0, "self tendency provides choices without confirming a stage");
                }
                capture(screen, "processing-stage-" + stage + "-unconfirmed", generation);
                step = 1;
            } else if (step == 1) {
                int[] choices = { 235, 241, 260, 270, 280, 290 };
                click(screen, choices[stage]);
                require(
                    screen.smokeLibraryUiState()
                        .getBoolean("flowChosen"),
                    "explicit native mouse choice confirms this stage only");
                capture(screen, "processing-stage-" + stage + "-explicit-choice", generation);
                step = 2;
            } else {
                click(screen, 231);
                stage++;
                step = 0;
            }
            lastGeneration = generation;
            return false;
        }
        if (!HologramClient.state.data.getBoolean("blueprintProcessingComplete")) return false;
        require(
            !ui.getBoolean("processingFlow") && !screen.smokePending(),
            "normal Netty process operation received server ACK");
        NBTTagCompound receipt = new NBTTagCompound();
        require(modalEndFrames == 12, "six manual stages each have both actual END captures");
        receipt.setBoolean("passed", true);
        receipt.setBoolean("actualNettyClient", true);
        receipt.setBoolean("syntheticSnapshotsUsed", false);
        receipt.setInteger("explicitStages", 6);
        receipt.setInteger("processingEndFrames", modalEndFrames);
        receipt.setInteger("nativeFullReviewControl", 296);
        receipt.setTag("actualFullReviewBefore", fullReviewBefore.copy());
        receipt.setTag("actualFullReviewAfter", fullReviewAfter.copy());
        receipt.setTag("actualSnapshot", HologramClient.state.data.copy());
        receipt.setTag("actualUi", ui.copy());
        HologramV011Evidence.write("processing-client-final.nbt", receipt);
        complete = true;
        return true;
    }

    private void capture(HologramScreen screen, String label, long generation) {
        captureScreen = screen;
        capture = new NBTTagCompound();
        capture.setString("label", label);
        capture.setLong("requestedAfterGeneration", generation);
        capture.setString("session", HologramClient.state.session);
        capture.setLong("planRevision", HologramClient.state.data.getLong("planRevision"));
        capture.setInteger(
            "expectedFlowStage",
            screen.smokeLibraryUiState()
                .getInteger("flowStage"));
        capture.setBoolean(
            "expectedFlowChosen",
            screen.smokeLibraryUiState()
                .getBoolean("flowChosen"));
        capture.setLong("nativeMouseEvents", nativeMouseEvents);
        capture.setInteger("lastMouseControl", lastMouseControl);
        capture.setBoolean("expectedFlowFullReview", true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || capture == null || failure != null) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.currentScreen != captureScreen || HologramClient.state == null
                || !HologramClient.state.session.equals(capture.getString("session"))) return;
            NBTTagCompound ui = captureScreen.smokeLibraryUiState();
            if (ui.getLong("renderGeneration") <= capture.getLong("requestedAfterGeneration")) return;
            require(
                ui.getInteger("flowStage") == capture.getInteger("expectedFlowStage")
                    && ui.getBoolean("flowChosen") == capture.getBoolean("expectedFlowChosen")
                    && ui.getBoolean("flowFullReview") == capture.getBoolean("expectedFlowFullReview"),
                "actual END frame still shows requested modal state");
            String name = capture.getString("label") + ".png";
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            Path png = mc.mcDataDir.toPath()
                .resolve("screenshots")
                .resolve(name);
            require(Files.isRegularFile(png) && Files.size(png) > 0, "actual modal framebuffer PNG exists");
            Files.copy(
                png,
                HologramV011Evidence.directory()
                    .resolve(name),
                StandardCopyOption.REPLACE_EXISTING);
            NBTTagCompound receipt = (NBTTagCompound) capture.copy();
            receipt.setBoolean("passed", true);
            receipt.setString("capturePhase", "RenderTick.END");
            receipt.setLong("actualRenderGeneration", ui.getLong("renderGeneration"));
            receipt.setString("png", name);
            receipt.setString(
                "pngSha256",
                hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(png))));
            receipt.setString(
                "snapshotSha256",
                hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(CompressedStreamTools.compress(HologramClient.state.data))));
            receipt.setTag("actualUi", ui.copy());
            receipt.setTag("actualSnapshot", HologramClient.state.data.copy());
            require(
                HologramWorldGuide.lastRenderWitness() == null,
                "world gesture guide is absent while author workbench is open");
            HologramV011Evidence.write(capture.getString("label") + ".nbt", receipt);
            modalEndFrames++;
            capture = null;
        } catch (Throwable problem) {
            failure = problem;
            problem.printStackTrace();
        }
    }

    /** Select only the actual LWJGL2 state buffer or the inspected lwjgl3ify 3.0.37 SDL delegate. */
    private static ControlKey controlKey() throws Exception {
        Field field;
        int index = Keyboard.KEY_LCONTROL;
        String mapping = "LWJGL2 key code";
        try {
            field = Keyboard.class.getDeclaredField("keyDownBuffer");
        } catch (NoSuchFieldException modernDelegate) {
            ClassLoader loader = Keyboard.class.getClassLoader();
            Class<?> shim = Class.forName("org.lwjglx.input.Keyboard", false, loader);
            require(
                shim.getField("KEY_LCONTROL")
                    .getInt(null) == Keyboard.KEY_LCONTROL,
                "SDL shim uses the native LWJGL2 control key code");
            field = shim.getField("sdlKeyPressedArray");
            Method translate = Class.forName("org.lwjglx.input.KeyCodes", false, loader)
                .getMethod("lwjglToSdlScancode", int.class);
            require(
                Modifier.isStatic(translate.getModifiers()) && translate.getReturnType() == int.class,
                "known SDL key mapping is a static integer function");
            index = (Integer) translate.invoke(null, Keyboard.KEY_LCONTROL);
            int expected = Class.forName("org.lwjgl.sdl.SDLScancode", false, loader)
                .getField("SDL_SCANCODE_LCTRL")
                .getInt(null);
            require(index == expected && index == 224, "known SDL shim maps left Control to scan code 224");
            mapping = "org.lwjglx.input.KeyCodes.lwjglToSdlScancode";
        }
        require(
            Modifier.isStatic(field.getModifiers()) && field.getType() == ByteBuffer.class,
            "known native keyboard state is a static ByteBuffer");
        field.setAccessible(true);
        Object state = field.get(null);
        require(state instanceof ByteBuffer, "actual keyboard state buffer is initialized");
        ByteBuffer keys = (ByteBuffer) state;
        require(
            !keys.isReadOnly() && index > 0 && index < keys.limit(),
            "native control index is inside the writable current keyboard state");
        return new ControlKey(
            keys,
            index,
            field.getDeclaringClass()
                .getName() + "."
                + field.getName(),
            mapping);
    }

    private static final class ControlKey {

        private final ByteBuffer keys;
        private final int index;
        private final byte previous;
        private final NBTTagCompound witness = new NBTTagCompound();

        private ControlKey(ByteBuffer keys, int index, String field, String mapping) {
            this.keys = keys;
            this.index = index;
            previous = keys.get(index);
            require(
                Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) == (previous != 0),
                "native Keyboard reads the selected control state");
            witness.setString("stateField", field);
            witness.setString("indexMapping", mapping);
            witness.setInteger("stateIndex", index);
            witness.setInteger("bufferLimit", keys.limit());
            witness.setBoolean("controlBefore", previous != 0);
        }

        private void set(boolean pressed) {
            keys.put(index, (byte) (pressed ? 1 : 0));
            require(
                Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) == pressed,
                "native Keyboard observes the requested control press or release");
            witness.setBoolean(pressed ? "nativeControlPressed" : "nativeControlReleased", true);
        }

        private void restore() {
            keys.put(index, previous);
            require(
                Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) == (previous != 0),
                "native Keyboard control state restored after text input");
            witness.setBoolean("nativeControlRestored", true);
        }
    }

    private static NBTTagCompound control(NBTTagCompound ui, int id) {
        NBTTagList controls = ui.getTagList("drawnControls", 10);
        for (int i = 0; i < controls.tagCount(); i++) {
            NBTTagCompound control = controls.getCompoundTagAt(i);
            if (control.getInteger("id") == id) return control;
        }
        return null;
    }

    private static boolean controlEnabled(NBTTagCompound ui, int id) {
        NBTTagCompound control = control(ui, id);
        return control != null && control.getBoolean("enabled");
    }

    /** A decoded ACK can arrive between frames; only the native preview binds the current displayed snapshot. */
    private static boolean displayedSource(HologramScreen screen, NBTTagCompound ui) throws Exception {

        Field renderedState = HologramRenderer.class.getDeclaredField("previewState");

        renderedState.setAccessible(true);
        HologramState source = HologramClient.state;
        return source != null && ((Object) HologramUiDiagnostics.readDiagnosticField(screen, "state")) == source
            && renderedState.get(null) == source
            && source.session.equals(ui.getString("session"))
            && source.data.getLong("planRevision") == ui.getLong("planRevision");
    }

    private void click(HologramScreen screen, int id) throws Exception {
        NBTTagCompound ui = screen.smokeLibraryUiState();
        if (!controlEnabled(ui, id)) {
            NBTTagCompound diagnostic = new NBTTagCompound();
            diagnostic.setBoolean("passed", false);
            diagnostic.setInteger("requestedControl", id);
            diagnostic.setInteger("flowStage", stage);
            diagnostic.setInteger("flowStep", step);
            diagnostic.setInteger("saveStep", saveStep);
            diagnostic.setLong("lastRenderGeneration", lastGeneration);
            diagnostic.setBoolean("displayedSourceMatches", displayedSource(screen, ui));
            diagnostic.setTag("actualUi", ui.copy());
            if (HologramClient.state != null) diagnostic.setTag("actualSnapshot", HologramClient.state.data.copy());
            HologramV011Evidence.write("processing-client-control-failure.nbt", diagnostic);
            throw new IllegalStateException(
                "Actual author control " + id
                    + " is absent or disabled; library="
                    + ui.getBoolean("library")
                    + ", blueprintTab="
                    + ui.getBoolean("blueprintTab")
                    + ", generation="
                    + ui.getLong("renderGeneration"));
        }
        int[] point = screen.smokeControlPoint(id);
        HologramMuiProbeInput.click(screen, point[0], point[1], 0);
        nativeMouseEvents++;
        lastMouseControl = id;
    }

    private static int confirmations(HologramScreen screen) throws Exception {

        HologramProcessingFlow flow = (HologramProcessingFlow) ((Object) HologramUiDiagnostics
            .readDiagnosticField(screen, "processingFlow"));
        return flow == null ? 6
            : flow.policy()
                .getTagList("confirmedStages", 8)
                .tagCount();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }
}
