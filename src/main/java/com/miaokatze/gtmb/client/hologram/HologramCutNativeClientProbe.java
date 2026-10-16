package com.miaokatze.gtmb.client.hologram;

import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import com.miaokatze.gtmb.api.CutTransforms.Transform;
import com.miaokatze.gtmb.hologram.HologramV011Evidence;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Target-window messages and real Shift, native LWJGL input, camera raycast, Netty ACKs and later END pixels. */
public final class HologramCutNativeClientProbe {

    private interface Action {

        boolean run(Minecraft mc) throws Exception;
    }

    private static final class Step {

        final String label;
        final Action action;
        final long timeout;

        Step(String label, Action action) {
            this(label, action, 90000000000L);
        }

        Step(String label, Action action, long timeout) {
            this.label = label;
            this.action = action;
            this.timeout = timeout;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private final NBTTagList actions = new NBTTagList(), frames = new NBTTagList(), nativeInputs = new NBTTagList();
    private final long started = System.nanoTime();
    private final long startupBudgetNanos = Math
        .max(60L, Math.min(3600L, Long.getLong("gtmb.hologram.nativeStartupSeconds", 1800L))) * 1000000000L;
    private long executionStarted;
    private long stepStarted = started, settleUntil, releaseAt, renderSerial, captureAfter;
    private int step, buttonMask, pointerActions, keyboardActions;
    private int releaseKey, awaitedMouseButton;
    private long keyReleaseAt, awaitedMouseDeadline;
    private boolean keyDownWitness, awaitWorldMouse;
    private long osSequence, osAckDeadline;
    private boolean osPending;
    private String osOperation;
    private int osKey;
    private NBTTagCompound lastOsAck;
    private boolean done, failing;
    private long failureCaptureDeadline;
    private String failureText;
    private NBTTagCompound lastNativeMouse;
    private int[] aim;
    private String firstId, secondId, captureLabel;
    private GuiScreen captureScreen;
    private String compositeId;

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.cutNativeClient")) return;
        HologramCutNativeClientProbe probe = new HologramCutNativeClientProbe();
        FMLCommonHandler.instance()
            .bus()
            .register(probe);
        MinecraftForge.EVENT_BUS.register(probe);
    }

    private HologramCutNativeClientProbe() {
        add("connect-explicit-isolated-loopback", mc -> {
            if (!(mc.currentScreen instanceof GuiMainMenu) && !(mc.currentScreen != null && mc.currentScreen.getClass()
                .getName()
                .equals("lumien.custommainmenu.gui.GuiCustom"))) return false;
            String address = System.getProperty("gtmb.hologram.cutNativeAddress", "");
            require(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "isolated loopback address required");
            require(
                !Files.exists(
                    HologramV011Evidence.directory()
                        .resolve("cut-client-final.nbt")),
                "fresh receipts");
            mc.gameSettings.pauseOnLostFocus = false;
            cpw.mods.fml.client.FMLClientHandler.instance()
                .setupServerList();
            cpw.mods.fml.client.FMLClientHandler.instance()
                .connectToServer(mc.currentScreen, new ServerData("GTMB v0.1.4 native cut", address));
            return true;
        });
        add("real-player-equipped-and-empty-directory-ack", mc -> {
            NBTTagCompound ready = HologramV011Evidence.read("cut-server-ready.nbt");
            if (ready == null || mc.theWorld == null
                || mc.thePlayer == null
                || client().busy()
                || mc.thePlayer.getHeldItem() == null
                || !(Boolean) field(client(), "loaded")) return false;
            require(
                ready.getString("actor")
                    .equals(
                        mc.thePlayer.getUniqueID()
                            .toString()),
                "actual Netty actor");
            require(
                client().entries()
                    .isEmpty(),
                "fresh server directory");
            return true;
        });
        add("bind-target-window-client-coordinates", mc -> {
            if (mc.currentScreen != null) return false;
            osInput("focus", 0, mc.displayWidth / 2, mc.displayHeight / 2);
            return true;
        });
        steps.add(
            new Step(
                "target-window-binding-acknowledged",
                mc -> mc.currentScreen == null && lastOsAck != null
                    && lastOsAck.getString("input_method")
                        .startsWith("target HWND PostMessage"),
                3000000000L));
        hold("hold-physical-shift", KeyEvent.VK_SHIFT, true);
        clickWorld("shift-right-opens-mode-switcher", 1);
        waitScreen("actual-mode-switcher-default", HologramCutModeScreen.class);
        shot("cut-mode-switcher-blueprint-default");
        add("still-holding-shift-right-cycles-cut", mc -> {
            click(mc, mc.currentScreen.width / 2, mc.currentScreen.height / 2, 1);
            return true;
        });
        add("actual-switcher-cut-selected", mc -> {
            if (!(mc.currentScreen instanceof HologramCutModeScreen)) return false;
            return ((HologramCutInput.Switcher) field(mc.currentScreen, "choice")).cut();
        });
        shot("cut-mode-switcher-cut-selected");
        hold("physical-shift-release-commits-cut", KeyEvent.VK_SHIFT, false);
        add("release-entered-selection-once", mc -> mc.currentScreen == null && client().selecting());
        mark("first-cut-left-first-corner", 48, 80, 64, 0);
        shot("cut-first-corner-selection");
        mark("first-cut-right-second-corner-real-marker", 49, 81, 64, 1);
        waitScreen("first-cut-confirmation-required", HologramCutConfirmScreen.class);
        add("confirmation-before-mutation", mc -> {
            require(
                client().entries()
                    .isEmpty(),
                "cut confirmation has not mutated queue");
            return true;
        });
        shot("cut-selection-confirmation");
        button("confirm-first-cut-through-native-pointer", 1);
        add("first-cut-normal-netty-ack-and-independent-disk", mc -> {
            if (client().busy() || client().entries()
                .size() != 1 || HologramV011Evidence.read("cut-first-cut-disk-and-air.nbt") == null) return false;
            firstId = client().entries()
                .get(0)
                .getString("id");
            require(firstId.equals(client().selected()), "first FIFO item selected");
            return true;
        });
        hold("hold-shift-for-second-cut", KeyEvent.VK_SHIFT, true);
        hold("hold-alt-for-second-cut", KeyEvent.VK_ALT, true);
        clickWorld("queued-shift-alt-right-new-selection", 1);
        hold("release-alt-after-selection", KeyEvent.VK_ALT, false);
        hold("release-shift-after-selection", KeyEvent.VK_SHIFT, false);
        add("queued-new-cut-selection-active", mc -> mc.currentScreen == null && client().selecting());
        mark("second-cut-left-first-corner", 52, 80, 64, 0);
        mark("second-cut-right-second-corner-real-marker", 53, 81, 64, 1);
        waitScreen("second-cut-confirmation-required", HologramCutConfirmScreen.class);
        button("confirm-second-cut-native-pointer", 1);
        add("two-genuine-server-entries-fifo", mc -> {
            if (client().busy() || client().entries()
                .size() != 2 || HologramV011Evidence.read("cut-second-cut-fifo-disk-and-air.nbt") == null) return false;
            secondId = client().entries()
                .get(1)
                .getString("id");
            require(
                firstId.equals(
                    client().entries()
                        .get(0)
                        .getString("id"))
                    && client().entries()
                        .get(0)
                        .getLong("sequence")
                        < client().entries()
                            .get(1)
                            .getLong("sequence"),
                "real FIFO ordering");
            return true;
        });
        clickWorld("ordinary-right-opens-server-directory", 1);
        waitScreen("directory-with-two-server-entries", HologramCutStorageScreen.class);
        add("directory-has-no-delete-control", mc -> {
            for (Object raw : buttons(mc.currentScreen)) {
                GuiButton b = (GuiButton) raw;
                require(b.id == 0 || b.id == 1 || b.id == 2 || b.id >= 100, "allowlisted directory controls");
                require(
                    !b.displayString.contains("删除") && !b.displayString.toLowerCase(java.util.Locale.ROOT)
                        .contains("delete"),
                    "no delete control");
            }
            return true;
        });
        shot("cut-storage-two-entries-no-delete");
        hold("hold-alt-for-directory-cycle", KeyEvent.VK_ALT, true);
        hold("native-alt-d-next-down", KeyEvent.VK_D, true);
        hold("native-alt-d-next-up", KeyEvent.VK_D, false);
        add("native-alt-d-selected-second", mc -> secondId.equals(client().selected()));
        shot("cut-storage-alt-d-second");
        hold("native-alt-a-previous-down", KeyEvent.VK_A, true);
        hold("native-alt-a-previous-up", KeyEvent.VK_A, false);
        add("native-alt-a-selected-first", mc -> firstId.equals(client().selected()));
        hold("release-alt-after-native-cycle", KeyEvent.VK_ALT, false);
        button("native-directory-place-first", 1);
        moving("server-preview-before-escape");
        shot("cut-moving-preview");
        tap("native-escape-cancels-preview", KeyEvent.VK_ESCAPE);
        add("escape-leaves-two-server-entries", mc -> {
            if (client().active() || mc.currentScreen != null) return false;
            require(
                client().entries()
                    .size() == 2 && firstId.equals(client().selected()),
                "Escape does not consume entry");
            return true;
        });
        clickWorld("reopen-directory-after-escape", 1);
        waitScreen("directory-preserved-after-escape", HologramCutStorageScreen.class);
        shot("cut-storage-escape-preserved");
        button("place-first-again-after-cancel", 1);
        moving("second-real-preview-ack");
        mark("native-world-right-lock-target", 56, 79, 64, 1);
        add("first-right-only-locks", mc -> {
            if (field(client(), "stage") != HologramCutInput.Stage.LOCKED) return false;
            require(
                mc.currentScreen == null && java.util.Arrays.equals(client().anchor(), new int[] { 56, 80, 64 }),
                "first native right locks requested anchor");
            require(
                client().entries()
                    .size() == 2,
                "lock does not consume");
            return true;
        });
        shot("cut-locked-preview");
        clickWorld("separate-second-right-opens-paste-confirmation", 1);
        waitScreen("paste-confirmation-before-world-write", HologramCutConfirmScreen.class);
        shot("cut-paste-confirmation");
        button("native-confirm-paste", 1);
        add("paste-success-consumes-first-retains-second", mc -> {
            if (client().busy() || client().entries()
                .size() != 1 || HologramV011Evidence.read("cut-paste-consumed-original-nbt-retained.nbt") == null)
                return false;
            require(secondId.equals(client().selected()), "FIFO next selected after successful consumption");
            return true;
        });
        clickWorld("directory-for-original-position-undo", 1);
        waitScreen("directory-one-remaining", HologramCutStorageScreen.class);
        button("native-open-undo-confirmation", 2);
        waitScreen("undo-confirmation-required", HologramCutConfirmScreen.class);
        shot("cut-undo-confirmation");
        tap("escape-cancels-undo-confirmation", KeyEvent.VK_ESCAPE);
        add("undo-escape-preserves-entry", mc -> {
            if (mc.currentScreen != null) return false;
            require(
                client().entries()
                    .size() == 1 && secondId.equals(client().selected()),
                "cancel undo does not consume");
            return true;
        });
        clickWorld("reopen-directory-to-confirm-undo", 1);
        waitScreen("directory-after-undo-cancel", HologramCutStorageScreen.class);
        button("open-undo-again", 2);
        waitScreen("undo-confirmation-again", HologramCutConfirmScreen.class);
        button("native-confirm-original-position-undo", 1);
        add(
            "real-undo-ack-and-disk-consumption",
            mc -> !client().busy() && client().entries()
                .isEmpty() && HologramV011Evidence.read("cut-undo-restores-original-and-consumes.nbt") != null);
        compositeSteps();
        add("write-complete-real-input-client-proof", mc -> {
            NBTTagCompound n = snapshot(mc);
            n.setBoolean("passed", true);
            n.setBoolean("actualNettyClient", true);
            n.setBoolean("syntheticSnapshotsUsed", false);
            n.setBoolean("serviceSessionsCreatedByProbe", false);
            n.setString(
                "inputMethod",
                "Target HWND window messages plus real global Shift SendInput only; native LWJGL and Forge consumption");
            n.setBoolean("targetWindowMessages", true);
            n.setBoolean("globalShiftOnly", true);
            n.setBoolean("physicalPointerCoverage", false);
            n.setString(
                "aimingMethod",
                "player camera yaw/pitch followed by native EntityRenderer.getMouseOver; physical block corner markers");
            n.setInteger("pointerActions", pointerActions);
            n.setInteger("keyboardActions", keyboardActions);
            n.setTag("nativeInputEvents", nativeInputs.copy());
            n.setTag("actions", actions.copy());
            n.setTag("frames", frames.copy());
            HologramV011Evidence.write("cut-client-final.nbt", n);
            HologramV011Evidence.marker(
                "cut-client-pass.txt",
                "PASS target HWND messages and real Shift switcher/two corners/FIFO/AltAD/Esc/lock/paste/undo and END pixels");
            done = true;
            return true;
        });
    }

    private void compositeSteps() {
        add(
            "actual-native-composite-fixture-ready",
            mc -> HologramV011Evidence.read("cut-composite-server-ready.nbt") != null);
        hold("composite-hold-shift", KeyEvent.VK_SHIFT, true);
        clickWorld("composite-shift-right-switcher", 1);
        waitScreen("composite-mode-screen", HologramCutModeScreen.class);
        add("composite-cycle-cut-mode", mc -> {
            click(mc, mc.currentScreen.width / 2, mc.currentScreen.height / 2, 1);
            return true;
        });
        hold("composite-release-shift", KeyEvent.VK_SHIFT, false);
        add("composite-selection-active", mc -> mc.currentScreen == null && client().selecting());
        mark("composite-real-GT-first-corner", 64, 80, 64, 0);
        mark("composite-real-marker-second-corner", 69, 81, 64, 1);
        waitScreen("composite-cut-confirmation", HologramCutConfirmScreen.class);
        button("composite-confirm-cut", 1);
        add("composite-real-cut-ack-and-original-disk", mc -> {
            if (client().busy() || client().entries()
                .size() != 1 || HologramV011Evidence.read("cut-composite-cut-raw-disk-and-air.nbt") == null)
                return false;
            compositeId = client().selected();
            return true;
        });
        clickWorld("composite-open-storage", 1);
        waitScreen("composite-storage", HologramCutStorageScreen.class);
        button("composite-preview-original", 1);
        moving("composite-fullraw-ack");
        mark("composite-lock-real-support", 72, 79, 64, 1);
        add("composite-center-native-camera", mc -> {
            require(
                java.util.Arrays.equals(client().anchor(), new int[] { 72, 80, 64 })
                    && field(client(), "stage") == HologramCutInput.Stage.LOCKED,
                "real composite target locked at exact adjacent top cell");
            aim = new int[] { 74, 80, 64 };
            aim(mc);
            return true;
        });
        add("composite-fullraw-native-visible-and-original-intact", mc -> {
            if (HologramCutRawPreview.inspection()
                .getLong("frames") < 5) return false;
            NBTTagCompound proof = rawEvidence(mc, true);
            HologramV011Evidence.write("cut-fullraw-native-original-proof.nbt", proof);
            return true;
        });
        shot("cut-fullraw-native-original");
        tap("composite-R-Y90", KeyEvent.VK_R);
        poseWait("composite-R-ack-Y90", 1, 0, 0, 0);
        hold("composite-Ctrl-down", KeyEvent.VK_CONTROL, true);
        tap("composite-Ctrl-R-X90", KeyEvent.VK_R);
        hold("composite-Ctrl-up", KeyEvent.VK_CONTROL, false);
        poseWait("composite-Ctrl-R-ack-X90", 1, 1, 0, 0);
        hold("composite-Alt-panel-down", KeyEvent.VK_ALT, true);
        tap("composite-Alt-R-panel", KeyEvent.VK_R);
        hold("composite-Alt-panel-up", KeyEvent.VK_ALT, false);
        waitScreen("composite-transform-panel-open", HologramCutTransformScreen.class);
        shot("cut-transform-panel-controls");
        poseButton("composite-X-minus", 10, 1, 0, 0, 0);
        poseButton("composite-Z-minus", 14, 1, 0, 3, 0);
        poseButton("composite-Z-plus", 15, 1, 0, 0, 0);
        poseButton("composite-mirror-X", 20, 1, 0, 0, 1);
        poseButton("composite-mirror-Y", 21, 1, 0, 0, 3);
        poseButton("composite-mirror-Z", 22, 1, 0, 0, 7);
        shot("cut-transform-three-mirrors");
        poseButton("composite-reset-all-pose", 40, 0, 0, 0, 0);
        poseButton("composite-direction-DOWN", 30, 0, 3, 0, 0);
        poseButton("composite-direction-UP", 31, 0, 1, 0, 0);
        poseButton("composite-direction-NORTH", 32, 0, 0, 0, 0);
        poseButton("composite-direction-SOUTH", 33, 2, 0, 0, 0);
        poseButton("composite-direction-WEST", 34, 3, 0, 0, 0);
        poseButton("composite-direction-EAST", 35, 1, 0, 0, 0);
        shot("cut-transform-direction-EAST");
        poseButton("composite-reset-before-final-pose", 40, 0, 0, 0, 0);
        poseButton("composite-final-Y90", 13, 1, 0, 0, 0);
        poseButton("composite-final-mirror-X", 20, 1, 0, 0, 1);
        button("composite-done-transform-panel", 0);
        add("composite-known-transformed-raw-volume", mc -> {
            if (mc.currentScreen != null || client().busy()
                || HologramCutRawPreview.inspection()
                    .getLong("frames") < 3)
                return false;
            NBTTagCompound raw = HologramCutRawPreview.snapshot();
            require(
                raw.getInteger("sizeX") == 1 && raw.getInteger("sizeY") == 2 && raw.getInteger("sizeZ") == 6,
                "Y90 and source-X mirror normalizes known 6x2x1 volume to 1x2x6");
            HologramV011Evidence.write("cut-fullraw-native-transformed-proof.nbt", rawEvidence(mc, false));
            return true;
        });
        shot("cut-fullraw-native-transformed");
        clickWorld("composite-second-right-paste-review", 1);
        waitScreen("composite-transformed-paste-confirmation", HologramCutConfirmScreen.class);
        shot("cut-transform-paste-confirmation");
        button("composite-confirm-transformed-paste", 1);
        add(
            "composite-real-paste-consumed-independent-world",
            mc -> !client().busy() && client().entries()
                .isEmpty() && HologramV011Evidence.read("cut-composite-pose-paste-consumed.nbt") != null);
        hold("recut-hold-shift", KeyEvent.VK_SHIFT, true);
        clickWorld("recut-native-switcher", 1);
        waitScreen("recut-mode-screen", HologramCutModeScreen.class);
        add("recut-select-cut-mode", mc -> {
            click(mc, mc.currentScreen.width / 2, mc.currentScreen.height / 2, 1);
            return true;
        });
        hold("recut-release-shift", KeyEvent.VK_SHIFT, false);
        add("recut-selection-active", mc -> mc.currentScreen == null && client().selecting());
        mark("recut-real-transformed-marker-corner", 72, 81, 64, 0);
        mark("recut-real-transformed-GT-corner", 72, 80, 69, 1);
        waitScreen("recut-confirmation", HologramCutConfirmScreen.class);
        button("recut-confirm-real-world", 1);
        add(
            "recut-nondefault-source-disk-ack",
            mc -> !client().busy() && client().entries()
                .size() == 1 && HologramV011Evidence.read("cut-composite-recut-nondefault-pose.nbt") != null);
        clickWorld("recut-open-storage", 1);
        waitScreen("recut-storage", HologramCutStorageScreen.class);
        button("recut-preview-other-pose", 1);
        moving("recut-preview-ack");
        tap("recut-R-modify-display-pose", KeyEvent.VK_R);
        poseWait("recut-other-preview-pose-ack", 1, 0, 0, 0);
        shot("cut-recut-different-preview-pose");
        tap("recut-Esc-cancels-display-only", KeyEvent.VK_ESCAPE);
        add(
            "recut-cancel-retains-source-entry",
            mc -> !client().active() && mc.currentScreen == null
                && client().entries()
                    .size() == 1);
        hold("recut-Alt-undo-down", KeyEvent.VK_ALT, true);
        clickWorld("recut-native-Alt-right-undo", 1);
        hold("recut-Alt-undo-up", KeyEvent.VK_ALT, false);
        waitScreen("recut-original-pose-undo-confirmation", HologramCutConfirmScreen.class);
        shot("cut-recut-original-pose-undo");
        button("recut-confirm-original-pose-undo", 1);
        add(
            "recut-undo-original-pose-consumed",
            mc -> !client().busy() && client().entries()
                .isEmpty() && HologramV011Evidence.read("cut-composite-undo-original-pose-consumed.nbt") != null);
    }

    private void poseWait(String label, int y, int x, int z, int mirror) {
        add(label, mc -> {
            Transform pose = (Transform) field(client(), "pose");
            return !client().busy() && field(client(), "rawPreview") != null
                && pose.rotation == y
                && pose.turnX == x
                && pose.turnZ == z
                && pose.mirror == mirror;
        });
    }

    private void poseButton(String label, int id, int y, int x, int z, int mirror) {
        button(label, id);
        poseWait(label + "-server-preview-ack", y, x, z, mirror);
    }

    private NBTTagCompound rawEvidence(Minecraft mc, boolean original) throws Exception {
        NBTTagCompound stats = HologramCutRawPreview.inspection(), raw = HologramCutRawPreview.snapshot();
        require(
            stats.getInteger("cells") == 12 && stats.getInteger("tilePayloads") == 4
                && stats.getInteger("loadedTiles") == 4
                && stats.getInteger("multipartParts") == 3,
            "actual complete GT/AE/FMP/chest detached native factories loaded");
        require(
            stats.getLong("staticAttempts") > 0 && stats.getLong("staticRendered") > 0
                && stats.getLong("tesrAttempts") > 0
                && stats.getLong("tesrRendered") > 0,
            "actual native static and TESR render callbacks succeeded");
        require(
            stats.getInteger("failureCount") == 0 && stats.getInteger("pendingVisibleCells") == 0,
            "composite native preview has no placeholder failures or pending visible cells");
        require(
            "native-offscreen-depth-copy-tinted-alpha-composite".equals(stats.getString("ghostMethod"))
                && stats.getFloat("ghostOpacity") > 0
                && stats.getFloat("ghostOpacity") < .5f
                && stats.getLong("ghostComposites") > 0
                && stats.getLong("ghostDepthCopies") > 0
                && stats.getLong("ghostOutlineCells") > 0
                && stats.getString("ghostFailure")
                    .isEmpty()
                && stats.getString("ghostRenderFailure")
                    .isEmpty()
                && stats.getLong("ghostInheritedGlErrors") == 0
                && stats.getLong("ghostDepthCopyGlErrors") == 0,
            "complete native models use a separate tinted translucent layer and independent world outlines");
        require(
            stats.getLong("ghostPixelChecks") > 0 && stats.getLong("ghostPixelMismatches") == 0
                && stats.getLong("ghostVisibleBackgroundPixels") > 0
                && stats.getLong("ghostSceneDepthChecks") > 0
                && stats.getLong("ghostSceneDepthMismatches") == 0
                && stats.getLong("ghostDepthCopyChecks") > 0
                && stats.getLong("ghostDepthCopyMismatches") == 0
                && stats.getString("ghostSceneDepthBeforeSha256")
                    .equals(stats.getString("ghostCopiedSceneDepthSha256"))
                && stats.getString("ghostSceneDepthBeforeSha256")
                    .equals(stats.getString("ghostSceneDepthAfterSha256")),
            "actual before/native/after pixels match alpha blend with visible background and unchanged full scene depth");
        require(
            stats.getLong("ghostStateChecks") > 0 && stats.getLong("ghostStateMismatches") == 0,
            "actual complete preview restores caller GL blend/depth/color/matrices/textures/lightmap/FBO state");
        require(
            HologramCutRawPreview.digest(raw)
                .equals(stats.getString("rawEntrySha256")),
            "renderer original canonical typed digest remains exact");
        NBTTagCompound result = snapshot(mc);
        result.setTag("inspection", stats);
        result.setTag("rawEntry", raw.copy());
        if (original) {
            NBTTagCompound server = HologramV011Evidence.read("cut-composite-cut-raw-disk-and-air.nbt")
                .getCompoundTag("actualDisk")
                .getTagList("entries", 10)
                .getCompoundTagAt(0);
            require(server.equals(raw), "all complete server queue fields reach full raw renderer without filtering");
            result.setString("independentOriginalSha256", HologramCutRawPreview.digest(server));
        }
        World detached = (World) staticField(HologramCutRawPreview.class, "world");
        require(
            detached != null && detached != mc.theWorld
                && detached.isRemote
                && detached.getClass()
                    .getName()
                    .endsWith("HologramCutRawPreviewWorld")
                && detached.loadedTileEntityList.isEmpty()
                && detached.getTotalWorldTime() == 0,
            "preview is an unregistered detached unticked world rather than the live client world");
        NBTTagList inputs = new NBTTagList();
        for (int i = 0; i < raw.getTagList("cells", 10)
            .tagCount(); i++) {
            NBTTagCompound cell = raw.getTagList("cells", 10)
                .getCompoundTagAt(i);
            if (!cell.hasKey("tile", 10)) continue;
            int x = cell.getInteger("x"), y = cell.getInteger("y"), z = cell.getInteger("z");
            NBTTagCompound actual = HologramCutRawPreview.inspectionCell(x, y, z);
            require(
                actual.getBoolean("factoryInputCaptured") && actual.getBoolean("nativeReaderCompleted")
                    && actual.getBoolean("worldBoundToPreview")
                    && actual.getCompoundTag("sourceCell")
                        .equals(cell),
                "actual native reader receives the complete original cell and binds only to detached world");
            NBTTagCompound expected = (NBTTagCompound) cell.getCompoundTag("tile")
                .copy();
            expected.setInteger("x", x);
            expected.setInteger("y", y);
            expected.setInteger("z", z);
            require(
                expected.equals(actual.getCompoundTag("factoryPayload")),
                "native factory input preserves every field except root XYZ relocation");
            TileEntity tile = detached.getTileEntity(x, y, z);
            require(
                tile != null && tile.getWorldObj() == detached && !mc.theWorld.loadedTileEntityList.contains(tile),
                "actual native preview tile is not registered in real client ticking tiles");
            actual.setTag("actualNativeTileAfterRender", tileTag(tile));
            inputs.appendTag(actual);
        }
        result.setTag("nativeReaderContexts", inputs);
        result.setBoolean("nativeCodeSandboxClaim", false);
        result.setLong("detachedWorldTime", detached.getTotalWorldTime());
        return result;
    }

    private static NBTTagCompound tileTag(TileEntity tile) {
        NBTTagCompound n = new NBTTagCompound();
        tile.writeToNBT(n);
        return n;
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (done) {
            if (mc.theWorld == null) mc.shutdown();
            return;
        }
        if (failing) {
            if (System.nanoTime() >= failureCaptureDeadline) finishFailure(mc);
            return;
        }
        try {
            long now = System.nanoTime();
            if (pollOsInput(now)) return;
            if (releaseKey != 0) {
                if (nativeKeyDown(releaseKey)) keyDownWitness = true;
                if (now >= keyReleaseAt) {
                    require(keyDownWitness, "physical key down was not sampled by LWJGL before release " + releaseKey);
                    NBTTagCompound witness = snapshot(mc);
                    witness.setString("eventKind", "LWJGL key down sampled across actual tick before Win32 release");
                    witness.setInteger("awtKey", releaseKey);
                    nativeInputs.appendTag(witness);
                    osInput("key-up", releaseKey, 0, 0);
                    releaseKey = 0;
                    settleUntil = now + 250000000L;
                }
                return;
            }
            if (buttonMask != 0) {
                if (now >= releaseAt) {
                    osInput("mouse-up", buttonMask - 1, 0, 0);
                    buttonMask = 0;
                    settleUntil = now + 250000000L;
                }
                return;
            }
            if (awaitWorldMouse) {
                require(
                    now < awaitedMouseDeadline,
                    "Win32 world button never arrived as a native Forge MouseEvent " + awaitedMouseButton);
                return;
            }
            if (now < settleUntil || captureLabel != null) return;
            if (step <= 1) {
                require(now - started < startupBudgetNanos, "native cut menu/world startup timeout");
            } else {
                require(now - executionStarted < 600000000000L, "ten minute native cut timeout");
                require(now - stepStarted < steps.get(step).timeout, "step timeout " + steps.get(step).label);
            }
            require(
                !Files.exists(
                    HologramV011Evidence.directory()
                        .resolve("cut-server-fail.txt")),
                "paired server did not fail");
            if (!steps.get(step).action.run(mc)) return;
            if (step == 1) executionStarted = System.nanoTime();
            NBTTagCompound action = snapshot(mc);
            action.setString("label", steps.get(step).label);
            actions.appendTag(action);
            HologramV011Evidence.write("cut-client-live.nbt", action);
            System.out.println("[GTMB-CUT-NATIVE-CLIENT] step=" + step + " " + steps.get(step).label);
            step++;
            stepStarted = System.nanoTime();
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            renderSerial++;
            if (aim != null && mc.theWorld != null && mc.thePlayer != null && mc.currentScreen == null) aim(mc);
            if (captureLabel == null || renderSerial <= captureAfter + 1) return;
            if (!failing)
                require(mc.currentScreen == captureScreen, "later END capture retains requested actual screen");
            String name = captureLabel + ".png";
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            Path target = HologramV011Evidence.directory()
                .resolve(name);
            Files.copy(
                mc.mcDataDir.toPath()
                    .resolve("screenshots")
                    .resolve(name),
                target,
                StandardCopyOption.REPLACE_EXISTING);
            BufferedImage image = ImageIO.read(target.toFile());
            require(
                image != null && image.getWidth() == mc.displayWidth && image.getHeight() == mc.displayHeight,
                "native framebuffer dimensions");
            NBTTagCompound n = snapshot(mc);
            n.setBoolean("passed", !failing);
            n.setString(
                "requestedScreen",
                captureScreen == null ? "world"
                    : captureScreen.getClass()
                        .getName());
            n.setString("capturePhase", "RenderTick.END");
            n.setLong("requestRenderSerial", captureAfter);
            n.setLong("actualRenderSerial", renderSerial);
            n.setString("png", name);
            n.setString("pngSha256", hash(Files.readAllBytes(target)));
            HologramV011Evidence.write(captureLabel + ".nbt", n);
            frames.appendTag(new NBTTagString(captureLabel + ".nbt"));
            captureLabel = null;
            if (failing) finishFailure(mc);
        } catch (Throwable failure) {
            if (failing) {
                failure.printStackTrace();
                finishFailure(mc);
            } else fail(mc, failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void nativeMouse(MouseEvent event) {
        if (done || event.button < 0) return;
        try {
            if (awaitWorldMouse && event.button == awaitedMouseButton && event.buttonstate) awaitWorldMouse = false;
            NBTTagCompound input = snapshot(Minecraft.getMinecraft());
            input.setString("eventKind", "native Forge MouseEvent after production handlers");
            input.setInteger("button", event.button);
            input.setBoolean("buttonState", event.buttonstate);
            input.setBoolean("canceled", event.isCanceled());
            input.setInteger("eventX", event.x);
            input.setInteger("eventY", event.y);
            lastNativeMouse = new NBTTagCompound();
            lastNativeMouse.setInteger("button", event.button);
            lastNativeMouse.setBoolean("buttonState", event.buttonstate);
            lastNativeMouse.setBoolean("canceled", event.isCanceled());
            lastNativeMouse.setInteger("eventX", event.x);
            lastNativeMouse.setInteger("eventY", event.y);
            lastNativeMouse.setBoolean("shift", HologramCutClient.shift());
            lastNativeMouse.setString("screen", input.getString("screen"));
            if (nativeInputs.tagCount() < 256) nativeInputs.appendTag(input.copy());
            HologramV011Evidence.write("cut-client-native-input.nbt", input);
        } catch (Exception evidence) {
            evidence.printStackTrace();
        }
    }

    @SubscribeEvent
    public void nativeKey(cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent event) {
        if (done || !Keyboard.isCreated()) return;
        try {
            NBTTagCompound input = snapshot(Minecraft.getMinecraft());
            input.setString("eventKind", "native FML KeyInputEvent");
            input.setInteger("key", Keyboard.getEventKey());
            input.setBoolean("keyState", Keyboard.getEventKeyState());
            if (nativeInputs.tagCount() < 256) nativeInputs.appendTag(input);
        } catch (Exception evidence) {
            evidence.printStackTrace();
        }
    }

    private void add(String label, Action action) {
        steps.add(new Step(label, action));
    }

    private void waitScreen(String label, Class<?> type) {
        steps.add(new Step(label, mc -> type.isInstance(mc.currentScreen), 7000000000L));
    }

    private void hold(String label, int key, boolean down) {
        add(label, mc -> {
            osInput(down ? "key-down" : "key-up", key, 0, 0);
            keyboardActions++;
            settleUntil = System.nanoTime() + 300000000L;
            return true;
        });
        steps.add(new Step("sampled-" + label, mc -> nativeKeyDown(key) == down, 3000000000L));
    }

    private static boolean nativeKeyDown(int awtKey) {
        if (!Keyboard.isCreated()) return false;
        switch (awtKey) {
            case KeyEvent.VK_SHIFT:
                return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            case KeyEvent.VK_ALT:
                return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
            case KeyEvent.VK_A:
                return Keyboard.isKeyDown(Keyboard.KEY_A);
            case KeyEvent.VK_D:
                return Keyboard.isKeyDown(Keyboard.KEY_D);
            case KeyEvent.VK_ESCAPE:
                return Keyboard.isKeyDown(Keyboard.KEY_ESCAPE);
            case KeyEvent.VK_R:
                return Keyboard.isKeyDown(Keyboard.KEY_R);
            case KeyEvent.VK_CONTROL:
                return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
            default:
                throw new IllegalArgumentException("Unmapped native sample key " + awtKey);
        }
    }

    private void tap(String label, int key) {
        add(label, mc -> {
            releaseKey = key;
            keyDownWitness = false;
            keyReleaseAt = Long.MAX_VALUE;
            osInput("key-down", key, 0, 0);
            keyboardActions++;
            return true;
        });
    }

    private void clickWorld(String label, int button) {
        add(label, mc -> {
            if (mc.currentScreen != null) return false;
            click(mc, 0, 0, button);
            return true;
        });
    }

    private void mark(String label, int x, int y, int z, int button) {
        add(label, mc -> {
            if (mc.currentScreen != null) return false;
            aim = new int[] { x, y, z };
            aim(mc);
            MovingObjectPosition hit = mc.objectMouseOver;
            HologramV011Evidence.write("cut-raycast-last.nbt", snapshot(mc));
            require(
                hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && hit.blockX == x
                    && hit.blockY == y
                    && hit.blockZ == z,
                "native raycast reaches real physical corner " + x
                    + ","
                    + y
                    + ","
                    + z
                    + "; actor="
                    + mc.thePlayer.posX
                    + ","
                    + mc.thePlayer.posY
                    + ","
                    + mc.thePlayer.posZ
                    + "; actual="
                    + (hit == null ? "null" : hit.typeOfHit + ":" + hit.blockX + "," + hit.blockY + "," + hit.blockZ));
            click(mc, 0, 0, button);
            return true;
        });
    }

    private void aim(Minecraft mc) {
        net.minecraft.util.Vec3 eyes = mc.thePlayer.getPosition(1.0F);
        double dx = aim[0] + .5 - eyes.xCoord, dz = aim[2] + .5 - eyes.zCoord;
        // Aim just inside the top face of the placement support so its adjacent cell is exactly y+1.
        double targetY = (aim[0] == 56 || aim[0] == 72) && aim[1] == 79 ? 79.99 : aim[1] + .5;
        double dy = targetY - eyes.yCoord;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
        mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
        mc.entityRenderer.getMouseOver(1.0F);
    }

    private void button(String label, int id) {
        add(label, mc -> {
            if (mc.currentScreen == null || client().busy()) return false;
            for (Object raw : buttons(mc.currentScreen)) {
                GuiButton button = (GuiButton) raw;
                if (button.id == id && button.enabled && button.visible) {
                    click(mc, button.xPosition + button.width / 2, button.yPosition + button.height / 2, 0);
                    return true;
                }
            }
            return false;
        });
    }

    private void moving(String label) {
        add(
            label,
            mc -> mc.currentScreen == null && !client().busy()
                && field(client(), "stage") == HologramCutInput.Stage.MOVING
                && field(client(), "preview") != null);
    }

    private void shot(String label) {
        add(label, mc -> {
            captureLabel = label;
            captureScreen = mc.currentScreen;
            captureAfter = renderSerial;
            return true;
        });
    }

    private void click(Minecraft mc, int x, int y, int button) throws Exception {
        int clientX, clientY;
        if (mc.currentScreen == null) {
            clientX = mc.displayWidth / 2;
            clientY = mc.displayHeight / 2;
            awaitWorldMouse = true;
            awaitedMouseButton = button;
            awaitedMouseDeadline = Long.MAX_VALUE;
        } else {
            clientX = x * mc.displayWidth / mc.currentScreen.width;
            clientY = y * mc.displayHeight / mc.currentScreen.height;
        }
        buttonMask = button + 1;
        releaseAt = Long.MAX_VALUE;
        osInput("mouse-down", button, clientX, clientY);
        pointerActions++;
    }

    private void osInput(String operation, int key, int x, int y) throws Exception {
        require(!osPending, "only one OS handshake may be outstanding");
        long pid = Long.parseLong(
            java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getName()
                .split("@")[0]);
        String text = "version=2\nsequence=" + (++osSequence)
            + "\npid="
            + pid
            + "\noperation="
            + operation
            + "\nkey="
            + key
            + "\nx="
            + x
            + "\ny="
            + y
            + "\nwidth="
            + Minecraft.getMinecraft().displayWidth
            + "\nheight="
            + Minecraft.getMinecraft().displayHeight
            + "\n";
        Path root = HologramV011Evidence.directory();
        Path target = root.resolve("cut-os-input-request-" + osSequence + ".properties");
        Path temporary = root.resolve("cut-os-input-request-" + osSequence + ".writing");
        require(!Files.exists(target) && !Files.exists(temporary), "never reuse an OS input request sequence");
        Files.write(temporary, text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        long publishDeadline = System.nanoTime() + 2000000000L;
        int publishRetries = 0;
        while (true) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                break;
            } catch (java.nio.file.AccessDeniedException transientSharingViolation) {
                if (System.nanoTime() >= publishDeadline) throw transientSharingViolation;
                publishRetries++;
                Thread.sleep(10L);
            }
        }
        if (publishRetries > 0) System.out.println(
            "[GTMB-CUT-INPUT] atomic request publish sequence=" + osSequence
                + " AccessDenied retries="
                + publishRetries);
        osPending = true;
        osOperation = operation;
        osKey = key;
        osAckDeadline = System.nanoTime() + 5000000000L;
    }

    private boolean pollOsInput(long now) throws Exception {
        if (!osPending) return false;
        require(now < osAckDeadline, "external Win32 input bridge did not acknowledge sequence " + osSequence);
        Path file = HologramV011Evidence.directory()
            .resolve("cut-os-input-ack-" + osSequence + ".properties");
        if (!Files.isRegularFile(file)) return true;
        Properties ack = new Properties();
        try (java.io.InputStream input = Files.newInputStream(file)) {
            ack.load(input);
        }
        if (!Long.toString(osSequence)
            .equals(ack.getProperty("sequence"))) return true;
        lastOsAck = new NBTTagCompound();
        for (String key : ack.stringPropertyNames()) lastOsAck.setString(key, ack.getProperty(key));
        HologramV011Evidence.write("cut-os-input-ack-" + osSequence + ".nbt", lastOsAck);
        require(
            "true".equals(ack.getProperty("success")),
            "Win32 input rejected: " + ack.getProperty("failure", "unknown"));
        osPending = false;
        if ("mouse-down".equals(osOperation)) {
            releaseAt = now + 150000000L;
            if (awaitWorldMouse) awaitedMouseDeadline = now + 3000000000L;
        }
        if ("key-down".equals(osOperation) && releaseKey == osKey) keyReleaseAt = now + 150000000L;
        return false;
    }

    private static HologramCutClient client() {
        return HologramCutClient.INSTANCE;
    }

    private static List<?> buttons(GuiScreen screen) throws Exception {
        Field field = GuiScreen.class.getDeclaredField("buttonList");
        field.setAccessible(true);
        return (List<?>) field.get(screen);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private NBTTagCompound snapshot(Minecraft mc) throws Exception {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("releaseVersion", "0.1.4");
        n.setString(
            "screen",
            mc.currentScreen == null ? "world"
                : mc.currentScreen.getClass()
                    .getName());
        n.setString("stage", field(client(), "stage").toString());
        n.setString("selected", client().selected());
        n.setString("status", client().status());
        NBTTagCompound poseProof = new NBTTagCompound();
        ((Transform) field(client(), "pose")).write(poseProof);
        n.setTag("pose", poseProof);
        if (compositeId != null) n.setString("compositeId", compositeId);
        n.setTag("rawInspection", HologramCutRawPreview.inspection());
        n.setBoolean("busy", client().busy());
        n.setBoolean("windowActive", Display.isActive());
        n.setBoolean("inGameHasFocus", mc.inGameHasFocus);
        n.setInteger("displayX", Display.getX());
        n.setInteger("displayY", Display.getY());
        n.setBoolean("keyboardCreated", Keyboard.isCreated());
        n.setBoolean("shift", HologramCutClient.shift());
        n.setBoolean("alt", HologramCutClient.alt());
        n.setBoolean("keyA", Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_A));
        n.setBoolean("keyD", Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_D));
        n.setBoolean("keyEscape", Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_ESCAPE));
        n.setBoolean("keyR", Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_R));
        n.setBoolean(
            "control",
            Keyboard.isCreated()
                && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)));
        n.setBoolean("mouseCreated", Mouse.isCreated());
        if (Mouse.isCreated()) {
            n.setBoolean("mouseGrabbed", Mouse.isGrabbed());
            n.setBoolean("nativeRightDown", Mouse.isButtonDown(1));
            n.setBoolean("nativeLeftDown", Mouse.isButtonDown(0));
            n.setInteger("mouseX", Mouse.getX());
            n.setInteger("mouseY", Mouse.getY());
        }
        java.awt.PointerInfo pointer = java.awt.MouseInfo.getPointerInfo();
        if (pointer != null) {
            n.setInteger("osPointerX", pointer.getLocation().x);
            n.setInteger("osPointerY", pointer.getLocation().y);
        }
        n.setBoolean("cutLoaded", (Boolean) field(client(), "loaded"));
        n.setBoolean("cutRightDown", (Boolean) field(client(), "rightDown"));
        n.setBoolean("cutInterceptsUse", client().interceptsUse());
        n.setBoolean("awaitWorldMouseEvent", awaitWorldMouse);
        n.setInteger("awaitedMouseButton", awaitedMouseButton);
        n.setInteger("pendingKeyRelease", releaseKey);
        n.setBoolean("pendingKeyDownWitness", keyDownWitness);
        n.setLong("osInputSequence", osSequence);
        n.setBoolean("osInputPending", osPending);
        n.setString("osInputOperation", osOperation == null ? "" : osOperation);
        if (lastOsAck != null) n.setTag("lastWin32Ack", lastOsAck.copy());
        n.setInteger("nativeInputCount", nativeInputs.tagCount());
        if (lastNativeMouse != null) n.setTag("lastNativeMouse", lastNativeMouse.copy());
        n.setBoolean("hologramStatePresent", HologramClient.state != null);
        if (HologramClient.state != null) {
            NBTTagCompound data = HologramClient.state.data;
            n.setBoolean("areaSelecting", data.getBoolean("areaSelecting"));
            n.setBoolean("areaImporting", data.getBoolean("areaImporting"));
            n.setBoolean("virtualPlacement", data.getBoolean("virtualPlacement"));
            n.setInteger("blueprintJob", data.getInteger("job"));
        }
        if (mc.thePlayer != null) {
            n.setDouble("actualPlayerX", mc.thePlayer.posX);
            n.setDouble("actualPlayerY", mc.thePlayer.posY);
            n.setDouble("actualPlayerZ", mc.thePlayer.posZ);
            n.setDouble("actualMotionX", mc.thePlayer.motionX);
            n.setDouble("actualMotionY", mc.thePlayer.motionY);
            n.setDouble("actualMotionZ", mc.thePlayer.motionZ);
            n.setFloat("actualYaw", mc.thePlayer.rotationYaw);
            n.setFloat("actualPitch", mc.thePlayer.rotationPitch);
            n.setFloat("actualFallDistance", mc.thePlayer.fallDistance);
            n.setBoolean("actualOnGround", mc.thePlayer.onGround);
            n.setBoolean("actualFlying", mc.thePlayer.capabilities.isFlying);
            n.setBoolean("actualAllowFlying", mc.thePlayer.capabilities.allowFlying);
            net.minecraft.util.Vec3 eye = mc.thePlayer.getPosition(1.0F);
            n.setDouble("actualEyeX", eye.xCoord);
            n.setDouble("actualEyeY", eye.yCoord);
            n.setDouble("actualEyeZ", eye.zCoord);
            n.setInteger("heldSlot", mc.thePlayer.inventory.currentItem);
            n.setBoolean(
                "heldProjector",
                com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector
                    .isProjector(mc.thePlayer.getHeldItem()));
            if (mc.thePlayer.getHeldItem() != null) n.setTag(
                "actualHeldTool",
                mc.thePlayer.getHeldItem()
                    .writeToNBT(new NBTTagCompound()));
        }
        n.setLong("worldTick", mc.theWorld == null ? -1 : mc.theWorld.getTotalWorldTime());
        NBTTagList entries = new NBTTagList();
        for (NBTTagCompound entry : client().entries()) entries.appendTag(entry.copy());
        n.setTag("actualServerEntries", entries);
        if (aim != null) {
            n.setIntArray("cameraAimTarget", aim);
            if (mc.theWorld != null) {
                n.setString(
                    "actualAimBlock",
                    String.valueOf(
                        net.minecraft.block.Block.blockRegistry
                            .getNameForObject(mc.theWorld.getBlock(aim[0], aim[1], aim[2]))));
                n.setInteger("actualAimMetadata", mc.theWorld.getBlockMetadata(aim[0], aim[1], aim[2]));
            }
        }
        n.setString("actualRaycastType", mc.objectMouseOver == null ? "null" : mc.objectMouseOver.typeOfHit.toString());
        if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK)
            n.setIntArray(
                "actualRaycastHit",
                new int[] { mc.objectMouseOver.blockX, mc.objectMouseOver.blockY, mc.objectMouseOver.blockZ,
                    mc.objectMouseOver.sideHit });
        if (mc.currentScreen != null) {
            NBTTagList controls = new NBTTagList();
            for (Object raw : buttons(mc.currentScreen)) {
                GuiButton b = (GuiButton) raw;
                NBTTagCompound c = new NBTTagCompound();
                c.setInteger("id", b.id);
                c.setString("text", b.displayString);
                c.setBoolean("enabled", b.enabled);
                c.setInteger("x", b.xPosition);
                c.setInteger("y", b.yPosition);
                c.setInteger("w", b.width);
                c.setInteger("h", b.height);
                controls.appendTag(c);
            }
            n.setTag("actualControls", controls);
        }
        return n;
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    private void fail(Minecraft mc, Throwable failure) {
        if (failing || done) return;
        failure.printStackTrace();
        failing = true;
        failureText = failure.toString();
        captureLabel = "cut-client-failure-frame";
        captureScreen = mc.currentScreen;
        captureAfter = renderSerial;
        failureCaptureDeadline = System.nanoTime() + 3000000000L;
        try {
            NBTTagCompound n = snapshot(mc);
            n.setBoolean("passed", false);
            n.setString("failure", failure.toString());
            n.setInteger("step", step);
            n.setString("stepLabel", step < steps.size() ? steps.get(step).label : "complete");
            n.setTag("actions", actions.copy());
            n.setTag("nativeInputEvents", nativeInputs.copy());
            HologramV011Evidence.write("cut-client-failure.nbt", n);
        } catch (Throwable evidence) {
            failure.addSuppressed(evidence);
        }
        releaseInputs();
        // Wait for a later real END framebuffer before exposing the marker that stops the paired server.
    }

    private void finishFailure(Minecraft mc) {
        if (done) return;
        try {
            HologramV011Evidence.marker("cut-client-fail.txt", "FAIL " + failureText);
        } catch (Exception evidence) {
            evidence.printStackTrace();
        }
        done = true;
        mc.shutdown();
    }

    private void releaseInputs() {
        try {
            osPending = false;
            releaseKey = 0;
            buttonMask = 0;
            osInput("release-all", 0, 0, 0);
        } catch (Exception evidence) {
            evidence.printStackTrace();
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("native cut client: " + message);
    }
}
