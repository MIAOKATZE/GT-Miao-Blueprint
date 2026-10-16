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
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.miaokatze.gtmb.api.CutTransforms.Transform;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
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
    private long cancellationPackets;
    private NBTTagCompound browserWorldBefore, browserPoseBefore;
    private IWidget guiTarget;
    private GuiScreen guiTargetScreen;
    private int[] guiTargetPoint;
    private long guiMoveRender;
    private String guiTargetName = "";
    private long heartbeatAt;
    private String connectionFailure = "";
    private volatile boolean ordinaryArtifactReady;
    private volatile String ordinarySeedFailure = "", ordinaryLocalId = "";
    private boolean ordinaryPolicyOpenNeeded, ordinaryLibrarySwitchNeeded;
    private long ordinaryPolicyTx, ordinaryAppliedTx;
    private long markAimRender, raycastEvidenceAt;

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
            HologramRuntimeMetrics.setEnabled(true);
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
                        .startsWith("target HWND verified SendInput"),
                145000000000L));
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
        add("release-remembers-cut-mode-without-starting-selection", mc -> {
            if (mc.currentScreen != null) return false;
            require(
                client().cutMode() && field(client(), "stage") == HologramCutInput.Stage.IDLE
                    && !client().selecting()
                    && !(Boolean) field(client(), "cutSession")
                    && field(client(), "preview") == null
                    && field(client(), "rawPreview") == null
                    && client().entries()
                        .isEmpty(),
                "choosing CUT enters the remembered idle mode with an empty cache");
            return true;
        });
        clickWorld("initial-cut-mode-ordinary-right-opens-empty-storage", 1);
        waitScreen("initial-cut-mode-real-empty-storage", HologramCutStorageScreen.class);
        add("initial-empty-storage-has-no-fabricated-preview", mc -> {
            require(
                client().entries()
                    .isEmpty() && browser(mc).renderer() == null,
                "ordinary right exposes the actual empty cache without a fabricated raw structure");
            return true;
        });
        shot("cut-mode-initial-empty-cache-native-list");
        tap("initial-empty-storage-Escape", KeyEvent.VK_ESCAPE);
        add("initial-empty-storage-Escape-keeps-idle-cut-mode", mc -> {
            if (mc.currentScreen != null || client().syncingDirectory()) return false;
            require(
                client().cutMode() && field(client(), "stage") == HologramCutInput.Stage.IDLE
                    && client().entries()
                        .isEmpty(),
                "Escape closes the empty list while retaining CUT mode");
            cancellationPackets = cutPacketsSent();
            return true;
        });
        hold("cut-mode-empty-cache-hold-shift", KeyEvent.VK_SHIFT, true);
        clickWorld("cut-mode-empty-cache-shift-right-only-notice", 1);
        add("cut-mode-empty-cache-never-enters-selection-or-dialog", mc -> {
            require(
                mc.currentScreen == null && client().cutMode()
                    && field(client(), "stage") == HologramCutInput.Stage.IDLE
                    && !client().selecting()
                    && field(client(), "preview") == null
                    && field(client(), "rawPreview") == null
                    && client().entries()
                        .isEmpty()
                    && cutPacketsSent() == cancellationPackets
                    && HologramCutClient.text("emptyPreview")
                        .equals(client().status()),
                "CUT Shift-right with an empty cache only displays the actual empty-cache notice and emits no CutAction");
            return true;
        });
        hold("cut-mode-empty-cache-release-shift", KeyEvent.VK_SHIFT, false);
        beginCutSelection("first-cut-explicit");
        mark("first-cut-left-first-corner", 48, 80, 64, 0);
        shot("cut-first-corner-selection");
        mark("first-cut-right-second-corner-real-marker", 49, 81, 64, 1);
        waitScreen("first-cut-name-required", HologramCutNameScreen.class);
        add("capture-name-cancel-codec-baseline", mc -> {
            cancellationPackets = cutPacketsSent();
            return true;
        });
        button("native-name-cancel-button", 0);
        add("name-cancel-emits-zero-cut-packets", mc -> {
            if (mc.currentScreen != null) return false;
            require(cutPacketsSent() == cancellationPackets, "cancel button emitted no encoded CutAction");
            require(
                client().entries()
                    .isEmpty() && !client().selecting(),
                "cancel preserved empty server queue");
            return true;
        });
        repeatFirstSelection("name-escape");
        add("capture-name-escape-codec-baseline", mc -> {
            cancellationPackets = cutPacketsSent();
            return true;
        });
        tap("native-name-escape", KeyEvent.VK_ESCAPE);
        add("name-escape-emits-zero-cut-packets", mc -> {
            if (mc.currentScreen != null) return false;
            require(cutPacketsSent() == cancellationPackets, "Escape emitted no encoded CutAction");
            require(
                client().entries()
                    .isEmpty() && !client().selecting(),
                "Escape preserved empty server queue");
            return true;
        });
        repeatFirstSelection("name-save");
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
        tap("cut-success-first-Escape-returns-to-world", KeyEvent.VK_ESCAPE);
        add("cut-success-Escape-preserves-cached-entry", mc -> {
            require(
                mc.currentScreen == null && !client().active()
                    && client().entries()
                        .size() == 1
                    && firstId.equals(client().selected()),
                "first post-cut Escape leaves the real cached entry available");
            return true;
        });
        hold("cut-mode-cached-place-hold-shift", KeyEvent.VK_SHIFT, true);
        clickWorld("cut-mode-cached-shift-right-starts-placement", 1);
        hold("cut-mode-cached-place-release-shift", KeyEvent.VK_SHIFT, false);
        moving("cut-mode-cached-placement-actual-preview-ack");
        add("cut-mode-cached-placement-preserves-entry", mc -> {
            require(
                firstId.equals(client().selected()) && client().entries()
                    .size() == 1,
                "CUT Shift-right starts preview of the actual cached ID without consuming it");
            return true;
        });
        tap("cut-mode-cached-placement-Escape", KeyEvent.VK_ESCAPE);
        add(
            "cut-mode-cached-placement-Escape-keeps-cache",
            mc -> mc.currentScreen == null && !client().active()
                && client().entries()
                    .size() == 1);
        clickWorld("cut-mode-ordinary-right-keeps-list-entry", 1);
        waitScreen("cut-mode-explicit-cache-list", HologramCutStorageScreen.class);
        button("cut-mode-explicit-cache-list-close", 0);
        hold("hold-shift-for-second-cut", KeyEvent.VK_SHIFT, true);
        hold("hold-alt-for-second-cut", KeyEvent.VK_ALT, true);
        clickWorld("queued-shift-alt-right-new-selection", 1);
        hold("release-alt-after-selection", KeyEvent.VK_ALT, false);
        hold("release-shift-after-selection", KeyEvent.VK_SHIFT, false);
        add("queued-new-cut-selection-active", mc -> mc.currentScreen == null && client().selecting());
        mark("second-cut-left-first-corner", 52, 80, 64, 0);
        mark("second-cut-right-second-corner-real-marker", 53, 81, 64, 1);
        waitScreen("ordinary-save-keeps-second-name-prompt", HologramCutNameScreen.class);
        button("save-second-cut-and-explicitly-disable-name-prompt", 2);
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
        add("capture-world-preview-before-readonly-browser", mc -> {
            browserWorldBefore = HologramCutRawPreview.snapshot();
            browserPoseBefore = new NBTTagCompound();
            ((Transform) field(client(), "pose")).write(browserPoseBefore);
            return true;
        });
        browserReady("directory-first-entry-complete-isolated-raw-render", true);
        add("directory-has-no-delete-control", mc -> {
            for (IWidget b : buttons(mc.currentScreen)) {
                String name = b.getName();
                require(
                    name != null && (name.startsWith("cut_entry_") || name.equals("cut_storage_close")
                        || name.equals("cut_storage_place")
                        || name.equals("cut_storage_undo")
                        || name.equals("cut_storage_sync")),
                    "allowlisted actual native directory controls: " + name);
            }
            return true;
        });
        shot("cut-storage-two-entries-no-delete");
        hold("hold-alt-for-directory-cycle", KeyEvent.VK_ALT, true);
        hold("native-alt-d-next-down", KeyEvent.VK_D, true);
        hold("native-alt-d-next-up", KeyEvent.VK_D, false);
        add("native-alt-d-selected-second", mc -> secondId.equals(browser(mc).state.selected()));
        browserReady("directory-second-entry-complete-isolated-raw-render", false);
        shot("cut-storage-alt-d-second");
        hold("native-alt-a-previous-down", KeyEvent.VK_A, true);
        hold("native-alt-a-previous-up", KeyEvent.VK_A, false);
        add("native-alt-a-selected-first", mc -> firstId.equals(browser(mc).state.selected()));
        browserReady("directory-returned-first-entry-complete-isolated-raw-render", true);
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
        add("placement-review-has-only-cancel-confirm-skip", mc -> {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (IWidget widget : buttons(mc.currentScreen)) names.add(widget.getName());
            require(
                names.equals(
                    new java.util.HashSet<>(java.util.Arrays.asList("cut_cancel", "cut_confirm", "cut_confirm_skip"))),
                "placement review has exactly the three requested native actions");
            require(
                !(Boolean) field(client(), "skipPlacementPrompt"),
                "naming preference does not disable placement review");
            cancellationPackets = cutPacketsSent();
            return true;
        });
        button("native-cancel-placement-review", 0);
        add("placement-cancel-emits-zero-cut-packets", mc -> {
            if (mc.currentScreen != null) return false;
            require(
                cutPacketsSent() == cancellationPackets && client().entries()
                    .size() == 2 && !client().active(),
                "placement Cancel writes no CutAction and preserves both cached entries");
            return true;
        });
        clickWorld("reopen-directory-after-placement-cancel", 1);
        waitScreen("directory-after-placement-cancel", HologramCutStorageScreen.class);
        button("restart-first-placement-after-review-cancel", 1);
        moving("placement-review-retry-raw-ack");
        mark("placement-review-retry-lock", 56, 79, 64, 1);
        clickWorld("placement-review-retry-second-right", 1);
        waitScreen("placement-review-retry-confirmation", HologramCutConfirmScreen.class);
        shot("cut-paste-confirmation");
        button("native-confirm-paste", 1);
        add("paste-success-consumes-first-retains-second", mc -> {
            if (client().busy() || client().entries()
                .size() != 1 || HologramV011Evidence.read("cut-paste-consumed-original-nbt-retained.nbt") == null)
                return false;
            require(secondId.equals(client().selected()), "FIFO next selected after successful consumption");
            require(
                !(Boolean) field(client(), "skipPlacementPrompt"),
                "ordinary confirmation retains the future placement prompt");
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
        placementSkipSteps();
        ordinaryBlueprintSteps();
        add("write-complete-real-input-client-proof", mc -> {
            NBTTagCompound n = snapshot(mc);
            n.setBoolean("passed", true);
            n.setBoolean("actualNettyClient", true);
            n.setBoolean("syntheticSnapshotsUsed", false);
            n.setBoolean("serviceSessionsCreatedByProbe", false);
            n.setBoolean("ordinaryBlueprintGuiAndNettyPassed", true);
            n.setTag("ordinaryClient", HologramV011Evidence.read("ordinary-client-complete.nbt"));
            n.setString(
                "inputMethod",
                "Target HWND verified SendInput mouse; PostMessage keys and SendInput modifiers; actual per-action ACKs and native LWJGL/Forge consumption");
            n.setBoolean("targetWindowMessages", true);
            n.setBoolean("targetHwndVerifiedSendInputMouse", true);
            n.setBoolean("globalShiftOnly", false);
            if (lastOsAck != null) n.setTag("lastActualInputBridgeAck", lastOsAck.copy());
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
        beginCutSelection("composite");
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
        button("composite-confirm-transformed-paste-and-disable-future-review", 2);
        add(
            "composite-real-paste-consumed-independent-world",
            mc -> !client().busy() && client().entries()
                .isEmpty() && HologramV011Evidence.read("cut-composite-pose-paste-consumed.nbt") != null);
        add("placement-skip-ack-is-independent-and-persisted", mc -> {
            require(
                (Boolean) field(client(), "skipPlacementPrompt") && (Boolean) field(client(), "skipNamePrompt"),
                "server ACK mirrors the two independently selected preferences");
            require(
                HologramV011Evidence.read("cut-composite-pose-paste-consumed.nbt")
                    .getCompoundTag("actualDisk")
                    .getBoolean("skipPlacementPrompt"),
                "placement preference is durable with the actual paste");
            return true;
        });
        beginCutSelection("recut");
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

    /** Separate final fixture: the previous composite still returns through a genuine undo. */
    private void placementSkipSteps() {
        add(
            "placement-skip-independent-one-cell-fixture-ready",
            mc -> HologramV011Evidence.read("cut-placement-skip-server-ready.nbt") != null);
        beginCutSelection("placement-skip-cut");
        mark("placement-skip-one-cell-first-corner", 84, 80, 64, 0);
        mark("placement-skip-one-cell-second-corner", 84, 80, 64, 1);
        waitScreen("placement-skip-one-cell-cut-confirmation", HologramCutConfirmScreen.class);
        button("placement-skip-confirm-real-one-cell-cut", 1);
        add(
            "placement-skip-real-cut-ack",
            mc -> !client().busy() && client().entries()
                .size() == 1 && HologramV011Evidence.read("cut-placement-skip-source-cut.nbt") != null);
        clickWorld("placement-skip-open-storage", 1);
        waitScreen("placement-skip-storage", HologramCutStorageScreen.class);
        button("placement-skip-start-world-preview", 1);
        moving("placement-skip-world-preview-ack");
        add("placement-skip-capture-lock-packet-baseline", mc -> {
            require((Boolean) field(client(), "skipPlacementPrompt"), "server-owned placement skip remains selected");
            cancellationPackets = cutPacketsSent();
            return true;
        });
        mark("placement-skip-first-right-locks-only", 88, 79, 64, 1);
        add("placement-skip-lock-zero-write-proof", mc -> {
            if (field(client(), "stage") != HologramCutInput.Stage.LOCKED) return false;
            require(
                mc.currentScreen == null && cutPacketsSent() == cancellationPackets
                    && client().entries()
                        .size() == 1,
                "skip preference still requires a separate second right press; lock sends no CutAction");
            require(
                java.util.Arrays.equals(client().anchor(), new int[] { 88, 80, 64 }),
                "independent skip target locked");
            HologramV011Evidence.write("cut-placement-skip-client-locked.nbt", snapshot(mc));
            return true;
        });
        add(
            "placement-skip-server-witnessed-lock-without-write",
            mc -> HologramV011Evidence.read("cut-placement-skip-lock-no-write.nbt") != null);
        shot("cut-placement-skip-locked-before-second-right");
        clickWorld("placement-skip-separate-second-right-submits-without-dialog", 1);
        add("placement-skip-real-paste-ack-no-dialog", mc -> {
            if (client().busy() || !client().entries()
                .isEmpty() || HologramV011Evidence.read("cut-placement-skip-second-right-pasted.nbt") == null)
                return false;
            require(
                mc.currentScreen == null && cutPacketsSent() == cancellationPackets + 1,
                "second right sends exactly one genuine paste CutAction without a review dialog");
            return true;
        });
        shot("cut-placement-skip-real-paste-complete");
    }

    /** An ordinary creative placement after every cut assertion; fixture artifact creation is not a user save test. */
    private void ordinaryBlueprintSteps() {
        add("ordinary-request-owned-native-fixture-preparation", mc -> {
            require(
                mc.currentScreen == null && client().entries()
                    .isEmpty(),
                "all cut cases finish before ordinary setup");
            HologramV011Evidence.write("ordinary-client-prepare.nbt", snapshot(mc));
            return true;
        });
        add("ordinary-native-server-source-and-artifact-prepare", mc -> {
            NBTTagCompound ready = HologramV011Evidence.read("ordinary-server-ready.nbt");
            if (ready == null || mc.thePlayer.getDistanceSq(100.5, 80, 67.5) > 2) return false;
            prepareOrdinaryArtifact(mc, ready.getString("blueprintJson"));
            return true;
        });
        add("ordinary-real-native-PNG-and-client-library-worker-complete", mc -> {
            require(
                ordinarySeedFailure.isEmpty(),
                "ordinary fixture artifact preparation failed: " + ordinarySeedFailure);
            return ordinaryArtifactReady;
        });
        hold("ordinary-switch-hold-control", KeyEvent.VK_CONTROL, true);
        hold("ordinary-switch-hold-shift", KeyEvent.VK_SHIFT, true);
        clickWorld("ordinary-switch-open-real-mode-panel", 1);
        waitScreen("ordinary-real-mode-panel", HologramCutModeScreen.class);
        add("ordinary-right-cycle-to-blueprint", mc -> {
            require(
                ((HologramCutInput.Switcher) field(mc.currentScreen, "choice")).cut(),
                "CUT remains selected before explicit cycle");
            click(mc, mc.currentScreen.width / 2, mc.currentScreen.height / 2, 1);
            return true;
        });
        add(
            "ordinary-blueprint-mode-selected",
            mc -> mc.currentScreen instanceof HologramCutModeScreen
                && !((HologramCutInput.Switcher) field(mc.currentScreen, "choice")).cut());
        hold("ordinary-switch-release-shift", KeyEvent.VK_SHIFT, false);
        hold("ordinary-switch-release-control", KeyEvent.VK_CONTROL, false);
        add(
            "ordinary-blueprint-area-choice-arrives",
            mc -> mc.currentScreen == null && !client().cutMode()
                && HologramClient.state != null
                && HologramClient.state.data.getBoolean("areaSelecting"));
        tap("ordinary-Escape-cancels-unused-area-selection", KeyEvent.VK_ESCAPE);
        add(
            "ordinary-area-cancel-actual-ACK",
            mc -> mc.currentScreen == null && HologramClient.state != null
                && !HologramClient.state.data.getBoolean("areaSelecting"));
        add("ordinary-aim-empty-air-for-workstation", mc -> {
            aim = null;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -80;
            mc.entityRenderer.getMouseOver(1.0F);
            require(
                mc.objectMouseOver == null
                    || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK,
                "ordinary air use must not import another controller");
            return true;
        });
        clickWorld("ordinary-open-real-workstation", 1);
        waitScreen("ordinary-actual-MUI-workstation", HologramScreen.class);
        button("ordinary-open-blueprint-library", 149);
        add("ordinary-native-library-tab", mc -> {
            if (!(mc.currentScreen instanceof HologramScreen)) return false;
            ordinaryLibrarySwitchNeeded = ((HologramScreen) mc.currentScreen).ui.library.publicLibrary();
            return true;
        });
        button("ordinary-show-client-library-if-needed", 201, () -> ordinaryLibrarySwitchNeeded);
        add("ordinary-owned-artifact-listed", mc -> {
            if (!(mc.currentScreen instanceof HologramScreen) || HologramLibraryClient.INSTANCE.busy()) return false;
            NBTTagList rows = HologramLibraryClient.INSTANCE.rows(false);
            require(
                !((HologramScreen) mc.currentScreen).ui.library.publicLibrary(),
                "real client library tab required");
            require(
                rows.tagCount() == 1 && ordinaryLocalId.equals(
                    rows.getCompoundTagAt(0)
                        .getString("id")),
                "isolated client contains exactly this prepared native artifact");
            return true;
        });
        button("ordinary-select-real-local-artifact-row", 14000);
        add(
            "ordinary-library-selection-is-real",
            mc -> ordinaryLocalId.equals(HologramLibraryClient.INSTANCE.selected(false)));
        button("ordinary-native-place-selected-blueprint", 141);
        add(
            "ordinary-real-private-upload-and-moving-snapshot",
            mc -> mc.currentScreen == null && HologramClient.state != null
                && HologramClient.state.data.getBoolean("virtualPlacement")
                && HologramClient.state.data.getBoolean("moving")
                && ordinaryLocalId.equals(HologramClient.state.data.getString("libraryLocalId")));
        mark("ordinary-real-world-lock-at-support", 100, 79, 64, 1);
        add("ordinary-lock-ACK-keeps-two-cell-ghost", mc -> {
            HologramState state = HologramClient.state;
            return mc.currentScreen == null && state != null
                && state.data.getBoolean("virtualLocked")
                && state.data.getInteger("x") == 100
                && state.data.getInteger("y") == 80
                && state.data.getInteger("z") == 64
                && state.cells.size() == 2;
        });
        clickWorld("ordinary-second-right-opens-material-review", 1);
        waitScreen("ordinary-real-material-review", HologramScreen.class);
        add("ordinary-review-before-manual-policy", mc -> {
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            if (screen.ui.session.pendingSequence() >= 0
                || HologramV011Evidence.read("ordinary-server-before-policy.nbt") == null) return false;
            require(
                screen.ui.review.confirmation() && screen.ui.review.placementReviewActive(),
                "actual ordinary review is open");
            require(
                !screen.ui.session.state().data.getBoolean("placementDeidentificationChosen"),
                "new fixture has no remembered placing-player choice");
            ordinaryPolicyOpenNeeded = !screen.ui.identity.deidentificationDrawer();
            return true;
        });
        button("ordinary-open-native-deidentification-drawer-if-needed", 211, () -> ordinaryPolicyOpenNeeded);
        add("ordinary-capture-manual-policy-network-baseline", mc -> {
            require(
                ((HologramScreen) mc.currentScreen).ui.identity.deidentificationDrawer(),
                "actual identity drawer required");
            require(
                (Integer) field(((HologramScreen) mc.currentScreen).ui.identity, "deidentificationLevel") == 1,
                "native drawer proposes actual level one");
            ordinaryPolicyTx = ordinaryActionPackets();
            return true;
        });
        button("ordinary-native-apply-level-one", 214);
        add("ordinary-real-manual-policy-ACK", mc -> {
            if (!(mc.currentScreen instanceof HologramScreen)
                || HologramV011Evidence.read("ordinary-server-policy-applied.nbt") == null) return false;
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            NBTTagCompound state = screen.ui.session.state().data;
            if (screen.ui.session.pendingSequence() >= 0 || !state.getBoolean("placementDeidentificationChosen"))
                return false;
            ordinaryAppliedTx = ordinaryActionPackets();
            require(
                ordinaryAppliedTx == ordinaryPolicyTx + 1 && state.getInteger("placementDeidentificationLevel") == 1
                    && state.getInteger("job") == 0
                    && screen.ui.review.confirmation(),
                "one real apply click sends exactly one action and returns an editable unbuilt review");
            return true;
        });
        add("ordinary-repeated-real-snapshots-do-not-reconfigure", mc -> {
            NBTTagCompound repeated = HologramV011Evidence.read("ordinary-server-repeated-snapshots.nbt");
            if (repeated == null || !(mc.currentScreen instanceof HologramScreen)) return false;
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            NBTTagCompound state = screen.ui.session.state().data;
            if (state.getLong("serverTick") < repeated.getLong("worldTick")) return false;
            require(
                ordinaryActionPackets() == ordinaryAppliedTx && screen.ui.session.pendingSequence() < 0
                    && state.getLong("planRevision") == repeated.getLong("planRevision")
                    && state.getLong("placementReviewNonce") == repeated.getLong("placementReviewNonce")
                    && screen.ui.review.confirmation(),
                "three unmodified Netty snapshots preserve the actual review without another configuration round trip");
            NBTTagCompound proof = snapshot(mc);
            proof.setTag("actualOrdinaryState", state.copy());
            proof.setLong("applyActionPackets", ordinaryAppliedTx - ordinaryPolicyTx);
            proof.setLong("additionalActionsAfterRepeatedSnapshots", ordinaryActionPackets() - ordinaryAppliedTx);
            proof.setTag("serverRepeatedSnapshots", repeated);
            HologramV011Evidence.write("ordinary-client-policy-and-repeats.nbt", proof);
            return true;
        });
        shot("ordinary-native-reviewed-policy-applied");
        button("ordinary-native-confirm-actual-world-placement", 55);
        add("ordinary-real-world-and-client-terminal-completion", mc -> {
            NBTTagCompound server = HologramV011Evidence.read("ordinary-server-world-complete.nbt");
            if (server == null || !(mc.currentScreen instanceof HologramScreen)) return false;
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            NBTTagCompound state = screen.ui.session.state().data;
            if (screen.ui.session.pendingSequence() >= 0 || state.getInteger("job") != 3) return false;
            require(
                state.getInteger("completed") == 2 && ordinaryActionPackets() == ordinaryAppliedTx + 1
                    && server.getBoolean("actualWorldCompletion")
                    && state.getString("session")
                        .equals(server.getString("session")),
                "actual confirmation is the sole next action and the final Netty DTO agrees with independently inspected world tiles");
            String visible = screen.ui.review.completionSummary();
            require(
                !visible.isEmpty() && visible.contains(HologramUiText.reason(state.getString("status")))
                    && screen.ui.session.progress() == null,
                "actual visible footer reports world completion with no lingering ACK progress");
            NBTTagCompound proof = snapshot(mc);
            proof.setTag("actualOrdinaryState", state.copy());
            proof.setTag("serverActualWorld", server);
            proof.setString("visibleCompletionSummary", visible);
            proof.setBoolean("actualGuiAndNetty", true);
            proof.setBoolean("serviceSessionsCreatedByProbe", false);
            proof.setBoolean("userSaveWorkflowClaim", false);
            HologramV011Evidence.write("ordinary-client-complete.nbt", proof);
            return true;
        });
        shot("ordinary-native-actual-world-job-complete");
    }

    private static long ordinaryActionPackets() {
        return HologramRuntimeMetrics.snapshot()
            .getCompoundTag("network")
            .getCompoundTag("action")
            .getLong("c2sTxPackets");
    }

    private void prepareOrdinaryArtifact(Minecraft mc, String json) throws Exception {
        Path game = mc.mcDataDir.toPath()
            .toRealPath();
        require(
            game.startsWith(
                HologramV011Evidence.directory()
                    .getParent()
                    .resolve("client")
                    .toRealPath()),
            "ordinary fixture artifact must stay inside the owned isolated client");
        final byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final java.util.concurrent.ThreadPoolExecutor worker = (java.util.concurrent.ThreadPoolExecutor) field(
            HologramLibraryClient.INSTANCE,
            "worker");
        worker.execute(() -> {
            try {
                com.miaokatze.gtmb.hologram.HologramLibraryFacade.validateNativeJson(bytes);
                NBTTagCompound nativeSnapshot = HologramBlueprintPreview.snapshot(bytes);
                HologramBlueprintPreview
                    .capture(nativeSnapshot, () -> !done && mc.theWorld != null, image -> worker.execute(() -> {
                        try {
                            java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
                            require(ImageIO.write(image, "png", encoded), "native preview PNG encoder");
                            HologramClientLibrary store = new HologramClientLibrary(game);
                            // The isolated runtime may be reused after a failed test; retire only this exact generated
                            // fixture.
                            for (HologramClientLibrary.Entry entry : store.list())
                                if ("Native GUI AE2FC and GT32765".equals(entry.name)) store.delete(entry.id);
                            ordinaryLocalId = store.save(bytes, encoded.toByteArray(), null);
                            NBTTagCompound proof = new NBTTagCompound();
                            proof.setString("localId", ordinaryLocalId);
                            proof.setBoolean("fixturePreparationOnly", true);
                            proof.setBoolean("userSaveWorkflowExercised", false);
                            proof.setBoolean("nativePreviewCallbackCompleted", true);
                            proof.setBoolean("productionLibraryWorker", true);
                            proof.setInteger("pngWidth", image.getWidth());
                            proof.setInteger("pngHeight", image.getHeight());
                            proof.setString(
                                "nativePngSha256",
                                com.miaokatze.gtmb.hologram.HologramBlueprintArtifact.hash(encoded.toByteArray()));
                            proof.setString(
                                "sourceJsonSha256",
                                com.miaokatze.gtmb.hologram.HologramBlueprintArtifact.hash(bytes));
                            proof.setString(
                                "isolatedArtifactJsonPath",
                                store.savedPath(ordinaryLocalId)
                                    .toString());
                            proof.setTag("nativeCaptureWitness", HologramBlueprintPreview.lastCaptureWitness());
                            HologramV011Evidence.write("ordinary-client-artifact-prepared.nbt", proof);
                            ordinaryArtifactReady = true;
                        } catch (Throwable failure) {
                            ordinarySeedFailure = failure.toString();
                        }
                    }), failure -> ordinarySeedFailure = failure.toString());
            } catch (Throwable failure) {
                ordinarySeedFailure = failure.toString();
            }
        });
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
            // The authenticated preview protocol appends the requested pose even for the identity transform.
            // Build the independent expected envelope from the real disk entry; never call the production transform.
            NBTTagCompound expected = (NBTTagCompound) server.copy();
            for (String key : new String[] { "rotation", "turnX", "turnZ", "mirror" }) {
                require(!server.hasKey(key), "fresh composite disk entry has no preview-only pose field " + key);
                require(
                    raw.hasKey(key, 3) && raw.getInteger(key) == 0,
                    "original preview appends the exact identity-pose int " + key);
                expected.setInteger(key, 0);
            }
            result.setTag("independentOriginalDiskEntry", server.copy());
            result.setTag("independentExpectedPreview", expected.copy());
            result.setString("independentExpectedPreviewSha256", HologramCutRawPreview.digest(expected));
            result.setBoolean("completeExpectedPreviewMatches", expected.equals(raw));
            HologramV011Evidence.write("cut-fullraw-original-comparison.nbt", result);
            require(
                expected.equals(raw) && HologramCutRawPreview.digest(expected)
                    .equals(stats.getString("rawEntrySha256")),
                "all complete server queue fields reach full raw renderer unchanged, plus exactly four identity-pose ints");
            result.setString("independentOriginalSha256", HologramCutRawPreview.digest(server));
        }
        World detached = (World) field(staticField(HologramCutRawPreview.class, "WORLD"), "world");
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
            observeConnectionAndHeartbeat(mc, now);
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

    /** Observes actual client state only, before startup/input waits can hide a failed handshake. */
    private void observeConnectionAndHeartbeat(Minecraft mc, long now) throws Exception {
        GuiScreen current = mc.currentScreen;
        String screenName = current == null ? "world"
            : current.getClass()
                .getName();
        boolean errorScreen = current instanceof GuiDisconnected || current instanceof GuiErrorScreen
            || "cpw.mods.fml.client.GuiModsMissingForServer".equals(screenName);
        if (step > 0 && errorScreen) {
            StringBuilder reason = new StringBuilder(screenName);
            NBTTagList fields = new NBTTagList();
            // Field types survive SRG remapping. Do not inspect GuiScreen/Minecraft or account fields.
            for (Class<?> type = current.getClass(); type != null
                && type != GuiScreen.class; type = type.getSuperclass()) {
                for (Field member : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(member.getModifiers())) continue;
                    Class<?> fieldType = member.getType();
                    if (!IChatComponent.class.isAssignableFrom(fieldType) && fieldType != String.class
                        && !Iterable.class.isAssignableFrom(fieldType)
                        && !java.util.Map.class.isAssignableFrom(fieldType)) continue;
                    member.setAccessible(true);
                    Object value = member.get(current);
                    if (value == null) continue;
                    String actual = value instanceof IChatComponent ? ((IChatComponent) value).getUnformattedText()
                        : String.valueOf(value);
                    reason.append('\n')
                        .append(member.getName())
                        .append('=')
                        .append(actual);
                    NBTTagCompound row = new NBTTagCompound();
                    row.setString("declaringClass", type.getName());
                    row.setString("field", member.getName());
                    row.setString("actualText", diagnosticText(actual));
                    row.setBoolean("textTruncated", actual.length() > 16000);
                    fields.appendTag(row);
                }
            }
            connectionFailure = reason.toString();
            NBTTagCompound proof = heartbeat(mc, now);
            proof.setBoolean("passed", false);
            proof.setTag("actualErrorGuiFields", fields);
            proof.setString("actualConnectionReason", diagnosticText(connectionFailure));
            proof.setBoolean("reasonTruncatedInNbt", connectionFailure.length() > 16000);
            proof.setString("fullReasonFile", "cut-client-connection-error.txt");
            HologramV011Evidence.write("cut-client-connection-error.nbt", proof);
            HologramV011Evidence.marker("cut-client-connection-error.txt", connectionFailure);
            throw new IllegalStateException("actual connection error GUI: " + diagnosticText(connectionFailure));
        }
        if (heartbeatAt == 0 || now - heartbeatAt >= 5000000000L) {
            NBTTagCompound proof = heartbeat(mc, now);
            HologramV011Evidence.write("cut-client-heartbeat.nbt", proof);
            System.out.println(
                "[GTMB-CUT-NATIVE-CLIENT] HEARTBEAT step=" + step
                    + " phase="
                    + proof.getString("phase")
                    + " screen="
                    + screenName
                    + " world="
                    + (mc.theWorld != null)
                    + " player="
                    + (mc.thePlayer != null)
                    + " render="
                    + renderSerial);
            heartbeatAt = now;
        }
    }

    private NBTTagCompound heartbeat(Minecraft mc, long now) {
        NBTTagCompound proof = new NBTTagCompound();
        proof.setString("scope", "read-only actual client connection and probe phase observation");
        proof.setInteger("step", step);
        proof.setString("stepLabel", step < steps.size() ? steps.get(step).label : "complete");
        proof.setString(
            "phase",
            step == 0 ? "waiting-menu" : step == 1 ? "waiting-real-player-and-directory" : "active");
        proof.setString(
            "screen",
            mc.currentScreen == null ? "world"
                : mc.currentScreen.getClass()
                    .getName());
        proof.setBoolean("worldPresent", mc.theWorld != null);
        proof.setBoolean("playerPresent", mc.thePlayer != null);
        proof.setBoolean("netHandlerPresent", mc.getNetHandler() != null);
        proof.setLong("worldTick", mc.theWorld == null ? -1 : mc.theWorld.getTotalWorldTime());
        proof.setLong("elapsedMillis", (now - started) / 1000000L);
        proof.setLong("stepElapsedMillis", (now - stepStarted) / 1000000L);
        proof.setLong("naturalRenderSerial", renderSerial);
        proof.setBoolean("osInputPending", osPending);
        proof.setString("osInputOperation", osOperation == null ? "" : osOperation);
        return proof;
    }

    private static String diagnosticText(String text) {
        return text.length() <= 16000 ? text : text.substring(0, 16000) + " [truncated; full reason in text file]";
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
        add(label + "-actual-client-block-arrived", mc -> {
            if (mc.currentScreen != null) return false;
            aim = new int[] { x, y, z };
            writeRaycastWaitEvidence(mc);
            if (!realMarkTargetReady(mc, x, y, z)) return false;
            aim(mc);
            markAimRender = renderSerial;
            return true;
        });
        add(label + "-natural-native-ray-ready", mc -> {
            if (mc.currentScreen != null || !realMarkTargetReady(mc, x, y, z)) {
                writeRaycastWaitEvidence(mc);
                return false;
            }
            if (renderSerial < markAimRender + 2) return false;
            aim(mc);
            MovingObjectPosition hit = mc.objectMouseOver;
            writeRaycastWaitEvidence(mc);
            return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.blockX == x
                && hit.blockY == y
                && hit.blockZ == z;
        });
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

    private static boolean realMarkTargetReady(Minecraft mc, int x, int y, int z) {
        if (mc.theWorld == null || !mc.theWorld.blockExists(x, y, z)) return false;
        net.minecraft.world.chunk.Chunk chunk = mc.theWorld.getChunkFromChunkCoords(x >> 4, z >> 4);
        return chunk != null && !(chunk instanceof net.minecraft.world.chunk.EmptyChunk)
            && chunk.isChunkLoaded
            && chunk.xPosition == (x >> 4)
            && chunk.zPosition == (z >> 4)
            && !mc.theWorld.isAirBlock(x, y, z);
    }

    private void writeRaycastWaitEvidence(Minecraft mc) throws Exception {
        long now = System.nanoTime();
        if (now < raycastEvidenceAt) return;
        raycastEvidenceAt = now + 1000000000L;
        HologramV011Evidence.write("cut-raycast-last.nbt", snapshot(mc));
    }

    private void aim(Minecraft mc) {
        net.minecraft.util.Vec3 eyes = mc.thePlayer.getPosition(1.0F);
        double dx = aim[0] + .5 - eyes.xCoord, dz = aim[2] + .5 - eyes.zCoord;
        // Aim just inside the top face of the placement support so its adjacent cell is exactly y+1.
        double targetY = aim[1] == 79 ? aim[1] + .99 : aim[1] + .5;
        double dy = targetY - eyes.yCoord;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
        mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
        mc.entityRenderer.getMouseOver(1.0F);
    }

    private void button(String label, int id) {
        button(label, id, () -> true);
    }

    private void button(String label, int id, java.util.function.BooleanSupplier needed) {
        add(label + "-native-pointer-move", mc -> {
            if (!needed.getAsBoolean()) return true;
            if (mc.currentScreen == null || client().busy()) return false;
            String target = controlName(mc.currentScreen, id);
            for (IWidget button : buttons(mc.currentScreen)) {
                if (target.equals(button.getName()) && button.isEnabled()
                    && button.areAncestorsEnabled()
                    && button.isValid()
                    && button.getArea().width > 0
                    && button.getArea().height > 0) {
                    guiTarget = button;
                    guiTargetScreen = mc.currentScreen;
                    guiTargetName = target;
                    moveToGuiTarget(mc);
                    return true;
                }
            }
            return false;
        });
        add(label + "-natural-native-hover", mc -> {
            if (!needed.getAsBoolean()) return true;
            require(
                mc.currentScreen == guiTargetScreen && guiTarget.isValid(),
                "native pointer target retains its actual screen/widget identity");
            int[] current = screenCenter(guiTarget);
            if (!java.util.Arrays.equals(current, guiTargetPoint)) {
                moveToGuiTarget(mc);
                return false;
            }
            if (renderSerial < guiMoveRender + 2) return false;
            boolean hovered = false;
            NBTTagList actual = new NBTTagList();
            for (LocatedWidget located : ((IMuiScreen) mc.currentScreen).getScreen()
                .getMainPanel()
                .getHovering()) {
                IWidget widget = located.getElement();
                if (widget == guiTarget) hovered = true;
                NBTTagCompound row = new NBTTagCompound();
                row.setString(
                    "class",
                    widget == null ? "null"
                        : widget.getClass()
                            .getName());
                row.setString("name", widget == null || widget.getName() == null ? "" : widget.getName());
                actual.appendTag(row);
            }
            NBTTagCompound proof = snapshot(mc);
            proof.setTag("actualMUIHovering", actual);
            proof.setBoolean("targetInActualHovering", hovered);
            proof.setLong("moveRenderSerial", guiMoveRender);
            proof.setLong("observedRenderSerial", renderSerial);
            HologramV011Evidence.write(label + "-native-hover.nbt", proof);
            require(hovered, "real OS pointer and natural MUI hit testing reach " + guiTargetName);
            return true;
        });
        add(label, mc -> {
            if (!needed.getAsBoolean()) return true;
            require(
                mc.currentScreen == guiTargetScreen && guiTarget.isValid(),
                "native target remains live before separate mouse-down");
            click(mc, guiTargetPoint[0], guiTargetPoint[1], 0);
            return true;
        });
    }

    private void moveToGuiTarget(Minecraft mc) throws Exception {
        guiTargetPoint = screenCenter(guiTarget);
        guiMoveRender = renderSerial;
        osInput(
            "mouse-move",
            0,
            guiTargetPoint[0] * mc.displayWidth / mc.currentScreen.width,
            guiTargetPoint[1] * mc.displayHeight / mc.currentScreen.height);
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
        osAckDeadline = System.nanoTime() + ("focus".equals(operation) ? 135000000000L : 5000000000L);
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
        if ("mouse-move".equals(osOperation)) guiMoveRender = renderSerial;
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

    private static List<IWidget> buttons(GuiScreen screen) {
        require(screen instanceof IMuiScreen, "actual MUI2 wrapper required for native pointer input");
        List<IWidget> pending = new ArrayList<>(), result = new ArrayList<>();
        pending.add(
            ((IMuiScreen) screen).getScreen()
                .getMainPanel());
        for (int i = 0; i < pending.size(); i++) {
            IWidget widget = pending.get(i);
            pending.addAll(widget.getChildren());
            if (widget instanceof ButtonWidget) result.add(widget);
        }
        return result;
    }

    private static int[] screenCenter(IWidget widget) {
        com.cleanroommc.modularui.screen.viewport.TransformationMatrix transform = LocatedWidget.of(widget)
            .getTransformationMatrix();
        float x = widget.getArea().width / 2f, y = widget.getArea().height / 2f;
        return new int[] { transform.transformX(x, y), transform.transformY(x, y) };
    }

    private static String controlName(GuiScreen screen, int id) {
        if (screen instanceof HologramScreen) return "projector_control_" + id;
        if (screen instanceof HologramCutNameScreen)
            return new String[] { "cut_name_cancel", "cut_name_save", "cut_name_skip" }[id];
        if (screen instanceof HologramCutConfirmScreen)
            return new String[] { "cut_cancel", "cut_confirm", "cut_confirm_skip" }[id];
        if (screen instanceof HologramCutTransformScreen) return "cut_pose_" + id;
        if (screen instanceof HologramCutStorageScreen)
            return "cut_storage_" + new String[] { "close", "place", "undo", "sync" }[id];
        throw new AssertionError(
            "Unknown native control screen " + screen.getClass()
                .getName());
    }

    private static long cutPacketsSent() {
        require(HologramRuntimeMetrics.isEnabled(), "real codec TX observation enabled");
        return HologramRuntimeMetrics.snapshot()
            .getCompoundTag("network")
            .getCompoundTag("cutAction")
            .getLong("c2sTxPackets");
    }

    private void repeatFirstSelection(String label) {
        beginCutSelection(label);
        mark(label + "-first-corner", 48, 80, 64, 0);
        mark(label + "-second-corner", 49, 81, 64, 1);
        waitScreen(label + "-name", HologramCutNameScreen.class);
    }

    /** CUT mode's explicit new-selection gesture remains distinct from cached Shift-right placement. */
    private void beginCutSelection(String label) {
        hold(label + "-hold-shift", KeyEvent.VK_SHIFT, true);
        hold(label + "-hold-alt", KeyEvent.VK_ALT, true);
        clickWorld(label + "-explicit-new-cut-selection", 1);
        hold(label + "-release-alt", KeyEvent.VK_ALT, false);
        hold(label + "-release-shift", KeyEvent.VK_SHIFT, false);
        add(label + "-selection-active", mc -> mc.currentScreen == null && client().selecting());
    }

    private static HologramCutStoragePreview browser(Minecraft mc) throws Exception {
        return (HologramCutStoragePreview) field(mc.currentScreen, "preview");
    }

    private void browserReady(String label, boolean first) {
        add(label, mc -> {
            if (!(mc.currentScreen instanceof HologramCutStorageScreen)) return false;
            HologramCutStoragePreview owner = browser(mc);
            HologramCutRawPreviewSession renderer = owner.renderer();
            if (renderer == null || renderer.inspection()
                .getLong("frames") < 3
                || renderer.inspection()
                    .getInteger("pendingVisibleCells") != 0)
                return false;
            String expected = first ? firstId : secondId;
            require(
                expected.equals(
                    owner.raw()
                        .getString("id")),
                "actual raw browser response matches selected row");
            require(
                renderer != staticField(HologramCutRawPreview.class, "WORLD"),
                "browser owns an independent real render session");
            require(
                HologramCutRawPreview.snapshot()
                    .equals(browserWorldBefore),
                "row changes preserve the complete world preview source");
            NBTTagCompound pose = new NBTTagCompound();
            ((Transform) field(client(), "pose")).write(pose);
            require(
                pose.equals(browserPoseBefore) && firstId.equals(client().selected()),
                "readonly row selection does not change world placement identity or pose");
            require(
                renderer.inspection()
                    .getInteger("failureCount") == 0
                    && renderer.inspection()
                        .getLong("staticRendered") > 0
                    && renderer.inspection()
                        .getLong("ghostStateChecks") > 0
                    && renderer.inspection()
                        .getLong("ghostStateMismatches") == 0,
                "native browser models rendered and restored caller GL state");
            require(
                renderer.snapshot()
                    .equals(owner.raw()),
                "complete raw source remains intact after actual GUI rendering");
            World detached = (World) field(renderer, "world");
            require(
                detached != null && detached != mc.theWorld
                    && detached.isRemote
                    && detached.loadedTileEntityList.isEmpty()
                    && detached.getTotalWorldTime() == 0,
                "browser detached native world is unticked and unregistered");
            NBTTagCompound proof = snapshot(mc);
            proof.setTag("browserRaw", renderer.snapshot());
            proof.setTag("browserInspection", renderer.inspection());
            proof.setTag("worldPreviewBefore", browserWorldBefore.copy());
            proof.setTag("worldPreviewAfter", HologramCutRawPreview.snapshot());
            proof.setBoolean("independentRenderSession", true);
            HologramV011Evidence.write(label + ".nbt", proof);
            return true;
        });
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
        if (!connectionFailure.isEmpty()) n.setString("actualConnectionReason", diagnosticText(connectionFailure));
        NBTTagCompound poseProof = new NBTTagCompound();
        ((Transform) field(client(), "pose")).write(poseProof);
        n.setTag("pose", poseProof);
        if (compositeId != null) n.setString("compositeId", compositeId);
        n.setTag("rawInspection", HologramCutRawPreview.inspection());
        n.setBoolean("busy", client().busy());
        n.setString("pendingNativeGuiTarget", guiTargetName);
        if (guiTargetPoint != null) n.setIntArray("pendingNativeGuiScreenPoint", guiTargetPoint);
        if (mc.currentScreen instanceof IMuiScreen) {
            com.cleanroommc.modularui.screen.viewport.ModularGuiContext context = ((IMuiScreen) mc.currentScreen)
                .getScreen()
                .getContext();
            n.setIntArray("actualMUIAbsoluteMouse", new int[] { context.getAbsMouseX(), context.getAbsMouseY() });
        }
        n.setLong(
            "cutActionCodecTxPackets",
            HologramRuntimeMetrics.snapshot()
                .getCompoundTag("network")
                .getCompoundTag("cutAction")
                .getLong("c2sTxPackets"));
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
                n.setBoolean("actualAimBlockExists", mc.theWorld.blockExists(aim[0], aim[1], aim[2]));
                net.minecraft.world.chunk.Chunk chunk = mc.theWorld.getChunkFromChunkCoords(aim[0] >> 4, aim[2] >> 4);
                n.setString(
                    "actualAimChunkClass",
                    chunk == null ? "null"
                        : chunk.getClass()
                            .getName());
                n.setBoolean("actualAimChunkLoaded", chunk != null && chunk.isChunkLoaded);
                if (chunk != null)
                    n.setIntArray("actualAimChunkCoordinates", new int[] { chunk.xPosition, chunk.zPosition });
                TileEntity tile = mc.theWorld.getTileEntity(aim[0], aim[1], aim[2]);
                n.setString(
                    "actualAimTileClass",
                    tile == null ? "null"
                        : tile.getClass()
                            .getName());
                n.setBoolean("actualAimReadyForRaycast", realMarkTargetReady(mc, aim[0], aim[1], aim[2]));
            }
        }
        n.setString("actualRaycastType", mc.objectMouseOver == null ? "null" : mc.objectMouseOver.typeOfHit.toString());
        if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK)
            n.setIntArray(
                "actualRaycastHit",
                new int[] { mc.objectMouseOver.blockX, mc.objectMouseOver.blockY, mc.objectMouseOver.blockZ,
                    mc.objectMouseOver.sideHit });
        if (mc.currentScreen instanceof IMuiScreen) {
            NBTTagList controls = new NBTTagList();
            for (IWidget b : buttons(mc.currentScreen)) {
                NBTTagCompound c = new NBTTagCompound();
                c.setString(
                    "widgetClass",
                    b.getClass()
                        .getName());
                c.setString("name", b.getName() == null ? "" : b.getName());
                c.setBoolean("enabled", b.isEnabled() && b.areAncestorsEnabled());
                c.setInteger("x", b.getArea().x);
                c.setInteger("y", b.getArea().y);
                c.setInteger("w", b.getArea().width);
                c.setInteger("h", b.getArea().height);
                c.setInteger("relativeX", b.getArea().rx);
                c.setInteger("relativeY", b.getArea().ry);
                c.setIntArray("actualScreenCenter", screenCenter(b));
                c.setString(
                    "actualViewportMatrix",
                    LocatedWidget.of(b)
                        .getTransformationMatrix()
                        .getMatrix()
                        .toString());
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
