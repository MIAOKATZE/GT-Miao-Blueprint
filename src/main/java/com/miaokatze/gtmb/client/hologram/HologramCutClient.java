// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.MouseEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.miaokatze.gtmb.api.CutTransforms.Transform;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramCutNaming;
import com.miaokatze.gtmb.hologram.HologramCutProtocol;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/** Cut storage and demolition share local selection input; ordinary blueprint state stays separate. */
final class HologramCutClient {

    static final HologramCutClient INSTANCE = new HologramCutClient();
    private final List<NBTTagCompound> entries = new ArrayList<>();
    private final HologramCutRequests requests = new HologramCutRequests();
    private final HologramCutContinuous continuous = new HologramCutContinuous();
    private final HologramCutNudge nudge = new HologramCutNudge();
    private long nudgeContext;
    private final HologramProgress.Tracker operationProgress = new HologramProgress.Tracker();
    private final HologramWorldFlow.KeyEdge previousKey = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge nextKey = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge rotateKey = new HologramWorldFlow.KeyEdge();
    private Object world, connection, actor;
    private UUID boundsCapability;
    private String pendingRecovery = "", pendingRecoveryOp = "";
    private int dimension;
    private boolean loaded, directoryUnavailable, rightDown;
    private boolean skipNamePrompt;
    private boolean cutMode, demolitionMode, cutSession;
    private String selected = "";
    private final HologramCutNotice status = new HologramCutNotice();
    private static final String UNAVAILABLE_BOUNDS = "剪切条目缺少已确认的版本或范围；暂存未改动。";
    private static final String INVALID_BOUNDS = "放置范围超出世界边界；暂存未改动。";
    private Transform pose = new Transform(0, 0, 0, 0);
    private ClipBounds lockedBounds;
    private int[] locked;
    private com.miaokatze.gtmb.hologram.HologramAreaSelection selection;
    private HologramCutConfirmScreen demolitionConfirmation;
    private int demolitionDestination;
    private String demolitionRequest = "";
    private long demolitionPollAt;
    private final HologramSelectionInput selectionInput = new HologramSelectionInput();
    private HologramCutInput.Stage stage = HologramCutInput.Stage.IDLE;

    private HologramCutClient() {}

    void install() {
        HologramNetwork.setCutReceiver((nbt, inbound) -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            Object receivedWorld = minecraft.theWorld, connection = minecraft.getNetHandler();
            Object receivedActor = minecraft.thePlayer;
            if (receivedWorld == null || receivedActor == null || connection == null || inbound != connection) return;
            minecraft.func_152344_a(() -> {
                if (receivedWorld == minecraft.theWorld && connection == minecraft.getNetHandler()
                    && receivedActor == minecraft.thePlayer) receive(nbt);
            });
        });
    }

    static boolean shift() {
        return key(Keyboard.KEY_LSHIFT) || key(Keyboard.KEY_RSHIFT);
    }

    static boolean alt() {
        return key(Keyboard.KEY_LMENU) || key(Keyboard.KEY_RMENU);
    }

    private static boolean key(int key) {
        return Keyboard.isCreated() && Keyboard.isKeyDown(key);
    }

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    private boolean held() {
        return mc().theWorld != null && mc().thePlayer != null
            && ItemNekoHologramProjector.isProjector(mc().thePlayer.getHeldItem());
    }

    boolean active() {
        return stage != HologramCutInput.Stage.IDLE;
    }

    boolean aiming() {
        return stage == HologramCutInput.Stage.SELECTING || stage == HologramCutInput.Stage.MOVING;
    }

    boolean cameraContext() {
        return held() && (active() || demolitionMode && cutSession);
    }

    /** Only this session's explicit demolition confirmation may keep its world camera. */
    boolean keepsCamera(net.minecraft.client.gui.GuiScreen screen) {
        return screen != null && screen == demolitionConfirmation
            && demolitionMode
            && cutSession
            && selecting()
            && held();
    }

    boolean selecting() {
        return stage == HologramCutInput.Stage.SELECTING;
    }

    boolean continuousActive() {
        return held() && selecting() && selection != null && selection.continuousActive();
    }

    boolean confirmContinuous() {
        if (!continuousActive()) return false;
        int closeRejections = selection.polygonCloseRejections();
        try {
            selection.confirmContinuous();
        } catch (java.io.IOException invalid) {
            if (selection.polygonCloseRejections() != closeRejections) HologramAreaOverlay.flashPolygonStart();
            status.show(invalid.getMessage());
        }
        return true;
    }

    private boolean blueprintBusy() {
        return HologramClient.blueprintInputBusy();
    }

    boolean interceptsUse() {
        return HologramCutInput.interceptsUse(stage, shift(), blueprintBusy(), cutMode || demolitionMode);
    }

    void tick() {
        Minecraft mc = mc();
        if (!HologramClient.worldInputFocused() || !Mouse.isCreated() || !Mouse.isButtonDown(1)) rightDown = false;
        if (world != mc.theWorld || connection != mc.getNetHandler()
            || actor != mc.thePlayer
            || mc.thePlayer != null && dimension != mc.thePlayer.dimension) {
            HologramCutStoragePreview.invalidate();
            HologramNetwork.clearCutClientTransport();
            world = mc.theWorld;
            connection = mc.getNetHandler();
            actor = mc.thePlayer;
            boundsCapability = null;
            pendingRecovery = "";
            pendingRecoveryOp = "";
            dimension = mc.thePlayer == null ? 0 : mc.thePlayer.dimension;
            entries.clear();
            requests.clear();
            operationProgress.clear();
            loaded = false;
            skipNamePrompt = false;
            cutMode = false;
            demolitionMode = false;
            directoryUnavailable = false;
            status.show("");
            selected = "";
            rightDown = false;
            cancel();
            closeScreen();
        }
        for (HologramCutRequests.Request expired : requests.expire(System.nanoTime())) {
            if (operationProgress.matches(expired.id)) operationProgress.clear();
            if (expired.writesWorld()) {
                status.show(text("demolish".equals(expired.action) ? "demolitionChecking" : "checkingResult"));
                if ("demolish".equals(expired.action)) request("demolitionStatus", expired.id);
                else refreshDirectory(); // A timeout may look, but never retry a pending mutation.
            } else if ("list".equals(expired.action)) {
                directoryUnavailable = true;
                status.directoryFailed(text("syncTimeout"));
            } else if ("demolitionStatus".equals(expired.action)) {
                if (expired.target.equals(demolitionRequest)) demolitionRequest = "";
                status.show(text("demolitionUnknown"));
            }
        }
        if (!held()) {
            if (HologramCutInput.cancelsActivity(stage, cutSession, lockedBounds != null)) cancel();
            closeScreen();
            requests.observeStorageContext(false);
            return;
        }
        // Recover an overlapping local cut left by older input routing without touching its server-owned queue.
        if (!demolitionMode && blueprintBusy()
            && HologramCutInput.exitsMode(stage, cutMode || demolitionMode, cutSession, lockedBounds != null)) {
            chooseBlueprintMode();
            closeScreen();
        }
        // Carrying the projector in blueprint mode never opens the remote cut directory.
        if (requests.observeStorageContext(cutMode)) refreshDirectory();
        pollDemolition(System.nanoTime());
        boolean cameraReady = HologramClient.worldInputFocused()
            && !HologramIsometricCamera.INSTANCE.blocksWorldInput();
        boolean enabled = cameraReady && mc.currentScreen == null
            && cutMode
            && !entries.isEmpty()
            && !blueprintBusy()
            && !HologramBackgroundTasks.busy()
            && stage != HologramCutInput.Stage.SELECTING;
        boolean previous = previousKey.sample(alt() && key(Keyboard.KEY_A), !enabled);
        boolean next = nextKey.sample(alt() && key(Keyboard.KEY_D), !enabled);
        if (enabled && (previous || next)) cycle(previous ? -1 : 1);
        boolean poseEnabled = cameraReady && !HologramIsometricCamera.INSTANCE.active()
            && mc.currentScreen == null
            && (stage == HologramCutInput.Stage.MOVING || stage == HologramCutInput.Stage.LOCKED)
            && !busy();
        if (rotateKey.sample(key(Keyboard.KEY_R), !poseEnabled)) {
            if (alt()) openTransform();
            else rotate(key(Keyboard.KEY_LCONTROL) || key(Keyboard.KEY_RCONTROL) ? 0 : 1, 1);
        }
        selectionInput.tick(selection, cameraReady && selecting() && held() && !busy() && !blueprintBusy());
        lockedNudge();
    }

    private void pollDemolition(long now) {
        if (demolitionRequest.isEmpty() || requests.active("demolitionStatus") || now < demolitionPollAt) return;
        demolitionPollAt = Long.MAX_VALUE;
        request("demolitionStatus", demolitionRequest);
    }

    private void lockedNudge() {
        Minecraft mc = mc();
        boolean enabled = HologramClient.worldInputFocused() && held()
            && !HologramIsometricCamera.INSTANCE.blocksWorldInput()
            && stage == HologramCutInput.Stage.LOCKED
            && locked != null
            && lockedBounds != null
            && !busy()
            && !blueprintBusy();
        int[] keys = { Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT };
        boolean[] pressed = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) pressed[i] = key(keys[i]);
        net.minecraft.entity.EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        locked = nudge.sample(
            locked,
            enabled,
            nudgeContext,
            pressed,
            alt(),
            camera == null ? 0 : camera.rotationYaw,
            mc.theWorld == null ? 0 : mc.theWorld.getTotalWorldTime(),
            mc.theWorld == null ? 256 : mc.theWorld.getHeight());
    }

    private void closeScreen() {
        if (mc().currentScreen instanceof HologramCutModeScreen
            || mc().currentScreen instanceof HologramCutStorageScreen
            || mc().currentScreen instanceof HologramCutConfirmScreen
            || mc().currentScreen instanceof HologramCutNameScreen
            || mc().currentScreen instanceof HologramCutTransformScreen) mc().displayGuiScreen(null);
    }

    /** Cancel mouse events before Minecraft updates attack/use bindings. */
    boolean mouse(MouseEvent event) {
        if (event.button == 1 && !event.buttonstate) rightDown = false;
        if (!held() || !HologramClient.worldInputFocused() || blueprintBusy()) return false;
        if (event.dwheel != 0 && aiming() && alt()) return false; // shared distance handler owns the wheel
        boolean intercept = event.button == 1 && interceptsUse() || event.button == 0 && cameraContext();
        if (!intercept) return false;
        event.setCanceled(true);
        KeyBinding.setKeyBindState(mc().gameSettings.keyBindUseItem.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc().gameSettings.keyBindAttack.getKeyCode(), false);
        if (!event.buttonstate) return true;
        if (!HologramClient.cameraPlacementClickAllowed()) return true;
        if (HologramIsometricCamera.INSTANCE.active() && selecting()) {
            HologramSelectionInput.CameraAction action = HologramSelectionInput.cameraAction(
                event.button,
                HologramClient.controlHeld(),
                shift(),
                alt(),
                key(Keyboard.KEY_R),
                continuousActive());
            if (action == HologramSelectionInput.CameraAction.CORNER_ONE
                || action == HologramSelectionInput.CameraAction.CONTINUOUS_POINT) mark(true);
            else if (action == HologramSelectionInput.CameraAction.CORNER_TWO) mark(false);
            else if (action == HologramSelectionInput.CameraAction.SUBMIT) confirmSelection();
            return true;
        }
        if (event.button == 0) {
            if (HologramIsometricCamera.INSTANCE.active() && !continuousActive()) {
                if (selecting() && !shift()) mark(true);
                else right(shift(), false);
            } else if (selecting()) mark(true);
            return true;
        }
        if (rightDown) return true;
        rightDown = true;
        right(shift(), alt());
        return true;
    }

    private void right(boolean shift, boolean alt) {
        HologramCutInput.Intent intent = HologramCutInput.right(
            stage,
            shift,
            alt,
            blueprintBusy(),
            mode(),
            key(Keyboard.KEY_LCONTROL) || key(Keyboard.KEY_RCONTROL));
        if (!HologramCutInput.allowsWorldAction(intent, HologramBackgroundTasks.busy())) return;
        if (HologramCutInput.requiresCutReady(intent)
            && (demolitionMode ? busy() : intent == HologramCutInput.Intent.SELECT ? cutUnavailable() : busy())) {
            if (demolitionMode) status.show(text("waiting"));
            else retryCutStorage();
            return;
        }
        switch (intent) {
            case SWITCHER:
                mc().displayGuiScreen(new HologramCutModeScreen());
                break;
            case SELECT:
                if (demolitionMode) beginDemolitionSelection();
                else beginSelection();
                break;
            case MORE:
                try {
                    selection.beginNext(HologramClient.controlHeld() ? "subtract" : "add");
                } catch (java.io.IOException invalid) {
                    status.show(invalid.getMessage());
                }
                break;
            case CONFIRM_SELECTION:
                confirmSelection();
                break;
            case STORAGE:
                refreshDirectory();
                openStorageWorkspace();
                break;
            case PREVIEW:
                beginPreview();
                break;
            case UNDO:
                undo();
                break;
            case MARK:
                mark(false);
                break;
            case LOCK:
                ClipBounds bounds = clipboardBounds(selected);
                if (bounds == null) {
                    status.show(UNAVAILABLE_BOUNDS);
                    break;
                }
                int[] target = HologramClient.cutAnchor(true);
                if (target == null) {
                    HologramClient.invalidCameraTarget();
                    break;
                }
                if (bounds.boxAt(pose, target) == null) {
                    status.show(INVALID_BOUNDS);
                    break;
                }
                locked = target;
                lockedBounds = bounds;
                nudgeContext++;
                stage = HologramCutInput.Stage.LOCKED;
                status.show(text("locked"));
                break;
            case CONFIRM:
                confirmPaste();
                break;
            default:
                break;
        }
    }

    boolean cameraClick(int button) {
        if (!cameraContext() || mc().currentScreen != null || blueprintBusy()) return false;
        if (busy()) return true;
        if (selecting()) {
            HologramSelectionInput.CameraAction action = HologramSelectionInput
                .cameraAction(button, HologramClient.controlHeld(), shift(), alt(), false, continuousActive());
            if (action == HologramSelectionInput.CameraAction.CORNER_ONE
                || action == HologramSelectionInput.CameraAction.CONTINUOUS_POINT) mark(true);
            else if (action == HologramSelectionInput.CameraAction.CORNER_TWO) mark(false);
            else if (action == HologramSelectionInput.CameraAction.SUBMIT) confirmSelection();
        } else if (button == 0) right(shift(), false);
        return true;
    }

    void beginSelection() {
        if (HologramBackgroundTasks.busy() || blueprintBusy()) return;
        HologramClient.leaveControllerProjection();
        cutMode = true;
        demolitionMode = false;
        continuous.enter();
        resetSelection();
        mc().displayGuiScreen(null);
    }

    private void beginDemolitionSelection() {
        if (HologramBackgroundTasks.busy() || blueprintBusy() || requests.writing()) return;
        HologramClient.leaveControllerProjection();
        cutMode = false;
        demolitionMode = true;
        continuous.enter();
        resetSelection();
        mc().displayGuiScreen(null);
    }

    private void resetSelection() {
        requests.exitActivity();
        operationProgress.clear();
        pose = new Transform(0, 0, 0, 0);
        stage = HologramCutInput.Stage.SELECTING;
        cutSession = true;
        selection = new com.miaokatze.gtmb.hologram.HologramAreaSelection();
        demolitionConfirmation = null;
        locked = null;
        lockedBounds = null;
        nudgeContext++;
        status.show(text(demolitionMode ? "demolitionSelection" : "selection"));
    }

    private void mark(boolean firstCorner) {
        if (busy()) {
            if (demolitionMode) status.show(text("waiting"));
            else retryCutStorage();
            return;
        }
        int[] point = HologramClient.cutAnchor(false);
        if (point == null) {
            HologramClient.invalidCameraTarget();
            return;
        }
        int closeRejections = selection.polygonCloseRejections();
        try {
            if (selection.continuousActive()) {
                if (firstCorner) selection.continuousPoint(point[0], point[1], point[2]);
                return;
            }
            selection.worldPoint(firstCorner ? 1 : 2, point[0], point[1], point[2]);
        } catch (java.io.IOException invalid) {
            if (selection.polygonCloseRejections() != closeRejections) HologramAreaOverlay.flashPolygonStart();
            status.show(invalid.getMessage());
        }
    }

    /** A complete pair remains editable until a separate Shift-right confirmation. */
    private void confirmSelection() {
        if (selection.continuousActive()) return;
        final NBTTagCompound chosen = selection.snapshot();
        final int[] bounds;
        try {
            if (selection.regionSnapshot()
                .tagCount() == 0) {
                if (demolitionMode) status.show(text("demolitionEmpty"));
                else {
                    refreshDirectory();
                    openStorageWorkspace();
                }
                return;
            }
            bounds = selection.bounds();
        } catch (java.io.IOException invalid) {
            status.show(invalid.getMessage());
            return;
        }
        String from = coordinates(new int[] { bounds[0], bounds[1], bounds[2] });
        String to = coordinates(new int[] { bounds[3], bounds[4], bounds[5] });
        if (demolitionMode) {
            String range = text("confirmDemolitionRange") + "\n"
                + String.format(java.util.Locale.ROOT, text("confirmDemolitionFrom"), from)
                + "\n"
                + String.format(java.util.Locale.ROOT, text("confirmDemolitionTo"), to);
            demolitionConfirmation = HologramCutConfirmScreen.demolition(
                range,
                demolitionDestination,
                destination -> submitDemolition(chosen, destination),
                () -> demolitionConfirmation = null);
            mc().displayGuiScreen(demolitionConfirmation);
            return;
        }
        ToolProof proof = captureTool();
        if (proof == null) return;
        String detail = text("confirmCutDetail") + " " + from + " → " + to;
        if (skipNamePrompt) mc().displayGuiScreen(
            new HologramCutConfirmScreen(
                text("confirmCut"),
                detail,
                () -> submitCut(chosen, bounds, "", false, proof),
                () -> {}));
        else mc().displayGuiScreen(
            new HologramCutNameScreen(detail, (name, skip) -> submitCut(chosen, bounds, name, skip, proof), () -> {}));
    }

    private void submitCut(NBTTagCompound chosen, int[] bounds, String name, boolean skipPrompt, ToolProof proof) {
        if (!toolCurrent(proof) || !selecting() || selection == null || !chosen.equals(selection.snapshot())) return;
        if (busy()) {
            retryCutStorage();
            return;
        }
        NBTTagCompound n = action("cut", "");
        n.setString("name", name);
        n.setTag("areaSelection", chosen);
        if (skipPrompt) n.setBoolean(HologramCutNaming.SKIP_PROMPT, true);
        for (int i = 0; i < 3; i++) {
            n.setInteger(new String[] { "x1", "y1", "z1" }[i], bounds[i]);
            n.setInteger(new String[] { "x2", "y2", "z2" }[i], bounds[i + 3]);
        }
        continuous.submitted(n.getString("requestId"));
        send(n);
        stage = HologramCutInput.Stage.IDLE;
        selection = null;
    }

    private void submitDemolition(NBTTagCompound chosen, int destination) {
        if (!held() || !demolitionMode
            || !selecting()
            || selection == null
            || !chosen.equals(selection.snapshot())
            || busy()) return;
        NBTTagCompound n = action("demolish", "");
        n.setTag("areaSelection", chosen);
        n.setInteger("demolitionDestination", destination);
        demolitionDestination = destination;
        continuous.submitted(n.getString("requestId"));
        send(n);
        demolitionConfirmation = null;
    }

    NBTTagCompound selectionSnapshot() {
        return selecting() && held() && selection != null ? selection.snapshot() : null;
    }

    List<NBTTagCompound> entries() {
        return java.util.Collections.unmodifiableList(entries);
    }

    String selected() {
        return selected;
    }

    String status() {
        return status.message();
    }

    String cameraStatus() {
        HologramProgress progress = progress(false);
        if (progress != null) return HologramProgressView.summary(progress, System.nanoTime());
        String message = status();
        return message.equals(text("selection")) || message.equals(text("demolitionSelection"))
            || message.equals(text("moving"))
            || message.equals(text("locked")) ? "" : message;
    }

    HologramProgress progress(boolean includeDirectory) {
        HologramCutRequests.Request current = requests.current(includeDirectory);
        if (current == null) return operationProgress.view(System.nanoTime());
        HologramProgress transport = HologramClientProgress.view("cut", "");
        if (transport != null && !transport.complete) return transport;
        String phase = "list".equals(current.action) && !current.target.isEmpty() ? "cut.verify"
            : "cut." + current.action;
        if (!operationProgress.matches(current.id))
            operationProgress.update(current.id, phase, 0, 0, "", false, current.sentAt);
        return operationProgress.update(current.id, phase, 0, 0, "", false, System.nanoTime());
    }

    void transportRejected(NBTTagCompound event) {
        if (HologramCutStoragePreview.receive(event)) return;
        HologramCutRequests.Request rejected = requests.acknowledge(event.getString("requestId"));
        if (rejected == null) return;
        if ("cut".equals(rejected.action)) continuous.completed(rejected.id, false);
        if ("demolish".equals(rejected.action)) continuous.completed(rejected.id, false);
        if ("demolitionStatus".equals(rejected.action) && rejected.target.equals(demolitionRequest))
            demolitionRequest = "";
        if (operationProgress.matches(rejected.id)) operationProgress.clear();
        boolean visible = !"list".equals(rejected.action) || !rejected.target.isEmpty()
            || mc().currentScreen instanceof HologramCutStorageScreen;
        if ("list".equals(rejected.action)) {
            directoryUnavailable = true;
            status.directoryFailed(HologramProgressView.phase("busy"));
        } else if (visible) status.show(HologramProgressView.phase("busy"));
    }

    boolean busy() {
        if (demolitionMode) return HologramBackgroundTasks.busy() || !demolitionRequest.isEmpty()
            || requests.writing()
            || requests.active("demolitionCancel")
            || requests.active("demolitionStatus");
        return HologramBackgroundTasks.busy() || cutUnavailable() || requests.busy();
    }

    /** A read-only manager may open before directory sync; world transactions still own their boundary. */
    boolean workspaceSwitchBusy() {
        return HologramBackgroundTasks.busy() || requests.writing();
    }

    private boolean cutUnavailable() {
        return !loaded || directoryUnavailable || requests.writing();
    }

    boolean syncingDirectory() {
        return requests.active("list");
    }

    void refreshDirectory() {
        String demolition = requests.unresolvedDemolition();
        if (!demolition.isEmpty()) {
            if (!requests.active("demolitionStatus")) request("demolitionStatus", demolition);
            return;
        }
        if (!requests.active("list")) request("list", "");
    }

    /** Only existing explicit Sync buttons can ask to continue a known earlier operation. */
    void retryDirectory() {
        if (!requests.unresolvedDemolition()
            .isEmpty()) {
            refreshDirectory();
            return;
        }
        if (requests.active("list")) return;
        String known = requests.unresolvedWrite();
        if (known.isEmpty()) known = pendingRecovery;
        if (known.isEmpty()) refreshDirectory();
        else if (requireBoundsPeer()) request("list", known);
    }

    private boolean currentPeer() {
        Minecraft mc = mc();
        return world != null && world == mc.theWorld
            && connection != null
            && connection == mc.getNetHandler()
            && actor != null
            && actor == mc.thePlayer
            && dimension == mc.thePlayer.dimension;
    }

    private boolean requireBoundsPeer() {
        if (currentPeer() && boundsCapability != null) return true;
        status.show(loaded ? "此服务器未确认支持范围剪切；仅可查看暂存，未执行操作。" : "请先同步支持范围剪切的暂存列表；未执行操作。");
        return false;
    }

    static UUID acceptedBoundsProof(String operation, boolean acceptedDirectory, NBTTagCompound reply) {
        return "list".equals(operation) && acceptedDirectory ? HologramCutProtocol.boundsProof(reply) : null;
    }

    private void retryCutStorage() {
        status.directoryFailed(text("syncRequired"));
        // An in-flight write keeps its original timeout and identity; never resend it.
        if (!requests.busy() || !requests.unresolvedWrite()
            .isEmpty()) refreshDirectory();
        openStorageWorkspace();
    }

    /** Use the existing stock pane and local editor root; a server reply never opens a screen. */
    void openStorageWorkspace() {
        if (!held() || !currentPeer()) {
            status.show("请在当前连接持有投影仪，再打开剪切暂存。");
            return;
        }
        Minecraft minecraft = mc();
        if (minecraft.currentScreen instanceof HologramScreen screen) {
            screen.ui.workspaces.tryActivateCut();
            return;
        }
        if (workspaceSwitchBusy() || blueprintBusy()) {
            status.show("请先完成当前操作，再打开剪切暂存；草稿和选区保留。");
            return;
        }
        if (minecraft.currentScreen != null) {
            status.show("请先关闭当前界面，再打开剪切暂存。");
            return;
        }
        HologramScreen editor = HologramScreen
            .localWorkspace(minecraft.thePlayer.getHeldItem(), minecraft.thePlayer.dimension);
        if (editor.ui.workspaces.tryActivateCut()) editor.openFrom(null);
    }

    void select(String id) {
        if (selected.equals(id)) return;
        selected = id;
        pose = new Transform(0, 0, 0, 0);
        lockedBounds = null;
        locked = null;
        nudgeContext++;
        if (stage == HologramCutInput.Stage.LOCKED) stage = HologramCutInput.Stage.MOVING;
    }

    void cycle(int delta) {
        if (entries.isEmpty() || busy()) return;
        int index = 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i)
            .getString("id")
            .equals(selected)) index = i;
        select(
            entries.get((index + delta + entries.size()) % entries.size())
                .getString("id"));
    }

    void beginPreview() {
        if (!requireBoundsPeer() || busy() || blueprintBusy() || HologramBackgroundTasks.busy()) return;
        if (selected.isEmpty() || entries.isEmpty()) {
            status.show(text("emptyPreview"));
            return;
        }
        if (clipboardBounds(selected) == null) {
            status.show(UNAVAILABLE_BOUNDS);
            return;
        }
        cutMode = true;
        demolitionMode = false;
        continuous.exit();
        requests.exitActivity();
        operationProgress.clear();
        cutSession = true;
        stage = HologramCutInput.Stage.MOVING;
        locked = null;
        lockedBounds = null;
        mc().displayGuiScreen(null);
        status.show(text("moving"));
    }

    /** Browsing never changes the world preview; an explicit storage action starts it once. */
    void beginStoragePlacement(String id) {
        if (!held() || !requireBoundsPeer()
            || busy()
            || blueprintBusy()
            || HologramBackgroundTasks.busy()
            || !containsEntry(id)) return;
        stage = HologramCutInput.Stage.IDLE;
        select(id);
        beginPreview();
    }

    private boolean containsEntry(String id) {
        for (NBTTagCompound entry : entries) if (entry.getString("id")
            .equals(id)) return true;
        return false;
    }

    void undo() {
        undo(selected);
    }

    void undo(String id) {
        UndoProof confirmation = armStorageUndo(id);
        if (confirmation == null) return;
        mc().displayGuiScreen(
            new HologramCutConfirmScreen(
                text("confirmUndo"),
                text("confirmUndoDetail"),
                () -> requestStorageUndo(confirmation),
                () -> {}));
    }

    /** Editor controls share the tool's held-item, directory and unresolved-write gates. */
    boolean canUseStorageEntry(String id) {
        return held() && !busy() && containsEntry(id);
    }

    static final class UndoProof {

        final ToolProof tool;
        final ClipBounds bounds;

        private UndoProof(ToolProof tool, ClipBounds bounds) {
            this.tool = tool;
            this.bounds = bounds;
        }
    }

    UndoProof armStorageUndo(String id) {
        ToolProof tool = captureTool();
        if (tool == null || busy()) return null;
        ClipBounds bounds = clipboardBounds(id);
        if (bounds == null) {
            status.show(UNAVAILABLE_BOUNDS);
            return null;
        }
        return new UndoProof(tool, bounds);
    }

    /** Final existing confirmation: exact original tool, connection and selected row must all survive. */
    void requestStorageUndo(UndoProof proof) {
        if (proof == null || !toolCurrent(proof.tool)) return;
        if (!proof.bounds.sameEntry(clipboardBounds(proof.bounds.id))) {
            status.show(UNAVAILABLE_BOUNDS);
            return;
        }
        if (busy()) {
            status.directoryFailed(text("syncRequired"));
            if (!requests.busy() || !requests.unresolvedWrite()
                .isEmpty()) refreshDirectory();
            return;
        }
        NBTTagCompound n = action("undo", proof.bounds.id);
        n.setLong("entrySequence", proof.bounds.sequence);
        send(n);
    }

    static final class ToolProof {

        private final Object world, handler, actor, stack, item;
        private final UUID token;
        private final int slot, dimension;

        ToolProof(Object world, Object handler, Object actor, Object stack, Object item, UUID token, int slot,
            int dimension) {
            this.world = world;
            this.handler = handler;
            this.actor = actor;
            this.stack = stack;
            this.item = item;
            this.token = token;
            this.slot = slot;
            this.dimension = dimension;
        }

        boolean matches(Object actualWorld, Object actualHandler, Object actualActor, Object actualStack,
            Object actualItem, UUID actualToken, int actualSlot, int actualDimension) {
            return world != null && world == actualWorld
                && handler != null
                && handler == actualHandler
                && actor != null
                && actor == actualActor
                && stack != null
                && stack == actualStack
                && item != null
                && item == actualItem
                && token != null
                && token.equals(actualToken)
                && slot == actualSlot
                && dimension == actualDimension;
        }
    }

    private ToolProof captureTool() {
        if (!held() || !requireBoundsPeer()) return null;
        Minecraft mc = mc();
        ItemStack stack = mc.thePlayer.getHeldItem();
        return new ToolProof(
            mc.theWorld,
            mc.getNetHandler(),
            mc.thePlayer,
            stack,
            stack.getItem(),
            boundsCapability,
            mc.thePlayer.inventory.currentItem,
            mc.thePlayer.dimension);
    }

    private boolean toolCurrent(ToolProof proof) {
        if (proof != null && held() && requireBoundsPeer()) {
            Minecraft mc = mc();
            ItemStack stack = mc.thePlayer.getHeldItem();
            if (proof.matches(
                mc.theWorld,
                mc.getNetHandler(),
                mc.thePlayer,
                stack,
                stack.getItem(),
                boundsCapability,
                mc.thePlayer.inventory.currentItem,
                mc.thePlayer.dimension)) return true;
        }
        status.show("手持投影仪或剪切确认已变化；暂存未改动，请重新确认。");
        return false;
    }

    private void confirmPaste() {
        if (!held() || !requireBoundsPeer() || stage != HologramCutInput.Stage.LOCKED || locked == null || busy())
            return;
        final ClipBounds bounds = lockedBounds;
        if (bounds == null || !bounds.sameEntry(clipboardBounds(selected))) {
            status.show(UNAVAILABLE_BOUNDS);
            return;
        }
        final int[] at = locked.clone();
        final Transform placementPose = pose;
        if (bounds.boxAt(placementPose, at) == null) {
            status.show(INVALID_BOUNDS);
            return;
        }
        ToolProof proof = captureTool();
        if (proof == null) return;
        // A prior geometry-preview skip preference is not consent for bounds-only placement.
        mc().displayGuiScreen(
            new HologramCutConfirmScreen(
                text("confirmPaste"),
                "这里只显示范围。服务器会在放置前核对实际方块与权限。 " + coordinates(at) + " | " + poseSummary(),
                () -> submitPaste(bounds, at, placementPose, proof),
                this::cancel));
    }

    private void submitPaste(ClipBounds bounds, int[] at, Transform placementPose, ToolProof proof) {
        if (!toolCurrent(proof)) return;
        if (stage != HologramCutInput.Stage.LOCKED || pose != placementPose
            || !Arrays.equals(locked, at)
            || !bounds.sameEntry(lockedBounds)
            || !bounds.sameEntry(clipboardBounds(selected))
            || bounds.boxAt(placementPose, at) == null) {
            status.show(UNAVAILABLE_BOUNDS);
            return;
        }
        if (busy()) {
            retryCutStorage();
            return;
        }
        NBTTagCompound n = action("paste", bounds.id);
        n.setLong("entrySequence", bounds.sequence);
        n.setInteger("originX", at[0]);
        n.setInteger("originY", at[1]);
        n.setInteger("originZ", at[2]);
        placementPose.write(n);
        send(n);
    }

    boolean escape(GuiOpenEvent event) {
        if (!(event.gui instanceof GuiIngameMenu) || !exitLocalMode()) return false;
        event.setCanceled(true);
        return true;
    }

    /** FML's actual world key event also covers Escape paths that bypass opening the vanilla pause screen. */
    boolean keyInput() {
        if (!Keyboard.isCreated() || Keyboard.getEventKey() != Keyboard.KEY_ESCAPE || !Keyboard.getEventKeyState())
            return false;
        if (mc().currentScreen != null && !(mc().currentScreen instanceof GuiIngameMenu)) return false;
        if (!exitLocalMode()) return false;
        if (mc().currentScreen instanceof GuiIngameMenu) mc().displayGuiScreen(null);
        return true;
    }

    private boolean exitLocalMode() {
        if (!HologramCutInput.exitsMode(stage, cutMode || demolitionMode, cutSession, lockedBounds != null))
            return false;
        exitCutMode();
        return true;
    }

    /** Escape retires local input and cancels remaining demolition while retaining submitted write outcomes. */
    void exitCutMode() {
        if (demolitionMode && held()) request("demolitionCancel", "");
        chooseBlueprintMode();
        closeScreen();
        status.show(text("exited"));
    }

    boolean cutMode() {
        return cutMode;
    }

    HologramCutInput.Mode mode() {
        return demolitionMode ? HologramCutInput.Mode.DEMOLITION
            : cutMode ? HologramCutInput.Mode.CUT : HologramCutInput.Mode.BLUEPRINT;
    }

    void chooseCutMode() {
        if (blueprintBusy() || HologramBackgroundTasks.busy()) return;
        cancel();
        beginSelection();
    }

    void chooseBlueprintMode() {
        cutMode = false;
        demolitionMode = false;
        cancel();
    }

    void chooseDemolitionMode() {
        if (blueprintBusy() || HologramBackgroundTasks.busy() || requests.writing()) return;
        cancel();
        beginDemolitionSelection();
    }

    void cancel() {
        continuous.exit();
        requests.exitActivity();
        operationProgress.clear();
        nudgeContext++;
        pose = new Transform(0, 0, 0, 0);
        stage = HologramCutInput.Stage.IDLE;
        cutSession = false;
        selection = null;
        demolitionConfirmation = null;
        demolitionDestination = 0;
        demolitionRequest = "";
        demolitionPollAt = 0;
        locked = null;
        lockedBounds = null;
    }

    int[] anchor() {
        return locked == null ? HologramClient.cutAnchor(true) : locked.clone();
    }

    private NBTTagCompound action(String op, String id) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("action", op);
        n.setString("id", id);
        n.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        return n;
    }

    private void request(String op, String id) {
        NBTTagCompound n = action(op, id);
        send(n);
    }

    static boolean prepareStorageControl(NBTTagCompound request) {
        String op = request.getString("action");
        if ("openWorkspace".equals(op) || "preview".equals(op)) return false;
        HologramCutProtocol.markPassiveList(request);
        return true;
    }

    private void send(NBTTagCompound n) {
        if (!prepareStorageControl(n)) return;
        String op = n.getString("action");
        if ("cut".equals(op) || "paste".equals(op) || "undo".equals(op)) {
            if (!requireBoundsPeer()) return;
            HologramCutProtocol.writeBoundsProof(n, boundsCapability);
        }
        long now = System.nanoTime();
        requests.begin(n.getString("requestId"), n.getString("action"), n.getString("id"), now);
        if (!"list".equals(n.getString("action")) || !n.getString("id")
            .isEmpty() || mc().currentScreen instanceof HologramCutStorageScreen)
            operationProgress.update(
                n.getString("requestId"),
                "list".equals(n.getString("action")) && !n.getString("id")
                    .isEmpty() ? "cut.verify" : "cut." + n.getString("action"),
                0,
                0,
                "",
                false,
                now);
        if (!"list".equals(n.getString("action"))) status.show(text("waiting"));
        HologramNetwork.sendCutAction(n);
    }

    private void receive(NBTTagCompound n) {
        if (!currentPeer()) return;
        if (HologramCutStoragePreview.receive(n)) return;
        HologramCutRequests.Request reply = requests.acknowledge(n.getString("requestId"));
        if (reply == null) return; // old world/connection ACK cannot revive a preview
        String op = reply.action;
        if ("demolish".equals(op) || "demolitionCancel".equals(op) || "demolitionStatus".equals(op)) {
            receiveDemolition(n, reply);
            return;
        }
        boolean recoveryPending = "recovery_pending".equals(n.getString("code"));
        boolean acceptedDirectory = n.getBoolean("success") && n.hasKey("entries", 9) && !recoveryPending;
        if ("list".equals(op) && acceptedDirectory) boundsCapability = acceptedBoundsProof(op, true, n);
        if ("bounds_protocol_required".equals(n.getString("code"))) boundsCapability = null;
        if (recoveryPending && reply.writesWorld() && boundsCapability != null) {
            pendingRecovery = reply.id;
            pendingRecoveryOp = op;
        }
        if (recoveryPending || !n.getBoolean("success") && reply.writesWorld()) directoryUnavailable = true;
        if (n.getBoolean("success")) {
            operationProgress.finish(reply.id, System.nanoTime());
        } else if (operationProgress.matches(reply.id)) operationProgress.clear();
        if (acceptedDirectory) {
            loaded = true;
            // This choice is one-way until leaving the save; a delayed older directory cannot undo it.
            skipNamePrompt = skipNamePrompt || HologramCutNaming.skipsPrompt(n);
            directoryUnavailable = false;
            entries.clear();
            NBTTagList list = n.getTagList("entries", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound summary = directoryRow(list.getCompoundTagAt(i));
                if (summary != null) entries.add(summary);
            }
            entries.sort((a, b) -> Long.compare(a.getLong("sequence"), b.getLong("sequence")));
            HologramCutStoragePreview.directoryChanged(entries);
            status.directoryRecovered();
            if ("list".equals(op) && boundsCapability == null) status.show("此服务器未确认支持范围剪切；仅可查看暂存。");
            if ("list".equals(op) && reply.target.isEmpty()
                && (!requests.unresolvedWrite()
                    .isEmpty() || !pendingRecovery.isEmpty()))
                status.show("结果尚未核定；请手动同步暂存以核对原操作。");
        }
        if ("list".equals(op) && !acceptedDirectory) {
            directoryUnavailable = true;
            status.directoryFailed(
                n.getString("message")
                    .isEmpty() ? text("syncTimeout") : n.getString("message"));
        }
        boolean found = false;
        for (NBTTagCompound entry : entries) if (selected.equals(entry.getString("id"))) found = true;
        if (!found) selected = entries.isEmpty() ? ""
            : entries.get(0)
                .getString("id");
        if (acceptedDirectory && lockedBounds != null && !lockedBounds.sameEntry(clipboardBounds(selected))) {
            locked = null;
            lockedBounds = null;
            nudgeContext++;
            if (stage == HologramCutInput.Stage.LOCKED) stage = HologramCutInput.Stage.MOVING;
        }
        if (!"list".equals(op)) status.operationResult(n.getString("message"), n.getString("code"));
        if ("list".equals(op) && !reply.target.isEmpty()
            && reply.target.equals(n.getString("reconciledRequestId"))
            && acceptedDirectory) {
            HologramCutRequests.Request resolved = requests.reconcile(reply.target);
            if (resolved != null || reply.target.equals(pendingRecovery)) {
                boolean completed = n.getBoolean("requestCompleted");
                String operation = resolved == null ? pendingRecoveryOp : resolved.action;
                status.show(text(completed ? "resultConfirmed" : "notExecuted"));
                if ("cut".equals(operation)) finishCut(reply.target, completed);
                if (completed && resolved != null
                    && requests.currentActivity(resolved)
                    && ("paste".equals(operation) || "undo".equals(operation))) cancel();
                if (reply.target.equals(pendingRecovery)) {
                    pendingRecovery = "";
                    pendingRecoveryOp = "";
                }
            }
        }
        if ("list".equals(op) && !reply.target.isEmpty()
            && !requests.unresolvedWrite()
                .isEmpty())
            status.directoryFailed(n.getBoolean("success") ? text("syncTimeout") : n.getString("message"));
        if (n.getBoolean("success") && requests.currentActivity(reply) && ("paste".equals(op) || "undo".equals(op)))
            cancel();
        if ("cut".equals(op)) finishCut(reply.id, n.getBoolean("success"));
        if (!"list".equals(op) && !"preview".equals(op)
            && (!"cut".equals(op) || !n.getBoolean("success"))
            && mc().thePlayer != null) mc().thePlayer.addChatMessage(new ChatComponentText(status()));
    }

    private void receiveDemolition(NBTTagCompound n, HologramCutRequests.Request reply) {
        if (operationProgress.matches(reply.id)) operationProgress.clear();
        String message = n.getString("message");
        boolean accepted = n.getBoolean("success");
        String submitted = "demolish".equals(reply.action) ? reply.id : "";
        if ("demolitionStatus".equals(reply.action)) {
            if (!accepted || !reply.target.equals(n.getString("reconciledRequestId"))) {
                if (reply.target.equals(demolitionRequest)) demolitionRequest = "";
                status.show(message);
                return;
            }
            HologramCutRequests.Request reconciled = requests.reconcile(reply.target);
            if (reconciled != null && "demolish".equals(reconciled.action)) submitted = reconciled.id;
            else if (reply.target.equals(demolitionRequest)) submitted = demolitionRequest;
            accepted = n.getBoolean("requestCompleted");
        }
        if (!submitted.isEmpty()) {
            if (accepted && n.getBoolean("demolitionActive") && held() && demolitionMode && cutSession) {
                demolitionRequest = submitted;
                demolitionPollAt = System.nanoTime() + 1_000_000_000L;
                stage = HologramCutInput.Stage.IDLE;
                selection = null;
            } else {
                demolitionRequest = "";
                boolean resume = continuous.completed(submitted, accepted);
                if (resume && held() && demolitionMode && !blueprintBusy() && !HologramBackgroundTasks.busy())
                    resetSelection();
            }
        } else if ("demolitionCancel".equals(reply.action) && accepted) {
            demolitionRequest = "";
        }
        status.show(message);
        if (!message.isEmpty() && !"demolitionStatus".equals(reply.action) && mc().thePlayer != null)
            mc().thePlayer.addChatMessage(new ChatComponentText(message));
    }

    private void finishCut(String requestId, boolean success) {
        boolean resume = continuous.completed(requestId, success);
        if (!success) return;
        String result = status();
        if (resume && held() && cutMode && !blueprintBusy() && !HologramBackgroundTasks.busy()) resetSelection();
        status.show((result.isEmpty() ? "" : result + " · ") + text("savedHint"));
        if (mc().thePlayer != null) mc().thePlayer.addChatMessage(new ChatComponentText(status()));
    }

    static NBTTagCompound detachedPreview(NBTTagCompound source) {
        return (NBTTagCompound) source.copy();
    }

    /** A blueprint-mode hint may inspect the last accepted player directory, never trigger its refresh. */
    void idleGuide(List<String> lines) {
        if (!held() || world != mc().theWorld
            || dimension != mc().thePlayer.dimension
            || cutMode
            || demolitionMode
            || active()) return;
        if (!busy()) lines.add(HologramClient.text("worldGuideCut", "Shift+Alt+right-click: begin cut selection"));
        if (!busy() && !entries.isEmpty()) lines.add(
            String.format(
                java.util.Locale.ROOT,
                HologramClient.text(
                    "worldGuideStoredCuts",
                    "Your stored cuts: %d · Choose Cut mode, then open storage to place / undo"),
                entries.size()));
    }

    boolean guide(List<String> lines) {
        if (!held() || !cutMode && !demolitionMode && !active() || blueprintBusy() && !active()) return false;
        if (demolitionMode) {
            lines.add(text("demolitionModeHint"));
            if (selecting()) {
                lines.add(
                    text(
                        HologramIsometricCamera.INSTANCE.active() ? "demolitionSelection.camera"
                            : "demolitionSelection"));
                HologramWorldGuide.selectionGuide(lines, selection.snapshot());
            } else lines.add(text(busy() ? "demolitionWaiting" : "demolitionIdle"));
            if (!status().isEmpty() && !lines.contains(status())) lines.add(status());
            return true;
        }
        if (HologramIsometricCamera.INSTANCE.active()) {
            if (selecting()) HologramWorldGuide.selectionGuide(lines, selection.snapshot());
            else lines.add(text(stage == HologramCutInput.Stage.MOVING ? "moving.camera" : "locked.camera"));
            return true;
        }
        if (cutMode) lines.add(text("cutModeHint"));
        if (active()) lines.add(
            text(
                (stage == HologramCutInput.Stage.SELECTING ? "selection"
                    : stage == HologramCutInput.Stage.MOVING ? "moving" : "locked")
                    + (HologramIsometricCamera.INSTANCE.active() ? ".camera" : "")));
        else lines.add(text(entries.isEmpty() ? "idle" : busy() ? "recoveryGuide" : "queued"));
        if (!entries.isEmpty()) {
            int index = 0;
            for (int i = 0; i < entries.size(); i++) if (selected.equals(
                entries.get(i)
                    .getString("id")))
                index = i;
            lines.add(text("queue") + " " + (index + 1) + " / " + entries.size() + " · " + text("cycle"));
        }
        if (aiming()) lines.add(text("target") + " " + coordinates(HologramClient.cutAnchor(!selecting())));
        if (selecting()) {
            HologramWorldGuide.selectionGuide(lines, selection.snapshot());
        }
        if (active() && !selecting()) {
            lines.add(poseSummary());
            if (!HologramIsometricCamera.INSTANCE.active())
                lines.add("R: Y +90 | Ctrl+R: X +90 | Alt+R: " + text("transform"));
            lines.add("仅显示范围；确认后由服务器核对实际方块与权限。");
        }
        if (!status().isEmpty() && !lines.contains(status())) lines.add(status());
        HologramProgress progress = progress(false);
        if (progress != null) lines.add(HologramProgressView.summary(progress, System.nanoTime()));
        return true;
    }

    boolean render(float ticks) {
        if (!active() || !held()) return false;
        if (selecting()) {
            NBTTagCompound data = selection.snapshot();
            HologramAreaOverlay.render(new HologramState(data), ticks, HologramClient.cutAnchor(false));
        } else {
            ClipBounds bounds = clipboardBounds(selected);
            int[] box = bounds == null ? null : bounds.boxAt(pose, anchor());
            if (box != null) {
                NBTTagCompound data = new NBTTagCompound();
                data.setIntArray("areaPoints", box);
                HologramAreaOverlay.render(new HologramState(data), ticks, null);
            }
        }
        return true;
    }

    void openTransform() {
        if (!active() || selecting() || busy()) return;
        mc().displayGuiScreen(new HologramCutTransformScreen());
    }

    void rotate(int axis, int delta) {
        changePose(
            new Transform(
                pose.rotation + (axis == 1 ? delta : 0),
                pose.turnX + (axis == 0 ? delta : 0),
                pose.turnZ + (axis == 2 ? delta : 0),
                pose.mirror));
    }

    void mirror(int axis) {
        changePose(new Transform(pose.rotation, pose.turnX, pose.turnZ, pose.mirror ^ (1 << axis)));
    }

    void face(int face) {
        changePose(facingPose(face, pose.mirror));
    }

    static Transform facingPose(int face, int mirror) {
        // Source-local Z reflection reverses NORTH before the rotations orient it toward the chosen face.
        int y = face == 2 ? 0 : face == 3 ? 2 : face == 4 ? 3 : face == 5 ? 1 : 0;
        int x = face == 0 ? 3 : face == 1 ? 1 : 0;
        if ((mirror & 4) != 0) {
            if (face == 0 || face == 1) x += 2;
            else y += 2;
        }
        return new Transform(y, x, 0, mirror);
    }

    void resetPose() {
        changePose(new Transform(0, 0, 0, 0));
    }

    private void changePose(Transform next) {
        if (busy() || clipboardBounds(selected) == null) return;
        pose = next;
    }

    String poseSummary() {
        ClipBounds bounds = clipboardBounds(selected);
        int[] size = bounds == null ? null : pose.size(bounds.sx, bounds.sy, bounds.sz);
        String dimensions = size == null ? "..." : size[0] + " × " + size[1] + " × " + size[2];
        return "X " + pose.turnX * 90
            + "°  Y "
            + pose.rotation * 90
            + "°  Z "
            + pose.turnZ * 90
            + "° | "
            + text("mirror")
            + " "
            + ((pose.mirror & 1) != 0 ? "X" : "")
            + ((pose.mirror & 2) != 0 ? "Y" : "")
            + ((pose.mirror & 4) != 0 ? "Z" : "")
            + " | "
            + dimensions;
    }

    ClipBounds clipboardBounds(String id) {
        for (NBTTagCompound row : entries) if (row.getString("id")
            .equals(id)) return ClipBounds.read(row);
        return null;
    }

    private static final String[] DIRECTORY_INTS = { "x", "y", "z", "dimension", "sizeX", "sizeY", "sizeZ", "blocks",
        "selectedCells" };

    static NBTTagCompound directoryRow(NBTTagCompound input) {
        if (!input.hasKey("id", 8)) return null;
        String id = input.getString("id");
        if (id.length() != 36) return null;
        try {
            if (!UUID.fromString(id)
                .toString()
                .equals(id)) return null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", id);
        if (input.hasKey("name", 8)) try {
            row.setString("name", HologramCutNaming.checkedName(input.getString("name")));
        } catch (IOException invalid) {
            // Old or malformed names do not prevent recovery through the authoritative directory.
        }
        for (String key : DIRECTORY_INTS) if (input.hasKey(key, 3)) row.setInteger(key, input.getInteger(key));
        if (input.hasKey("sequence", 4) && input.getLong("sequence") > 0)
            row.setLong("sequence", input.getLong("sequence"));
        return row;
    }

    static final class ClipBounds {

        final String id;
        final long sequence;
        final int x, y, z, dimension, sx, sy, sz;

        private ClipBounds(NBTTagCompound row) {
            id = row.getString("id");
            sequence = row.getLong("sequence");
            x = row.getInteger("x");
            y = row.getInteger("y");
            z = row.getInteger("z");
            dimension = row.getInteger("dimension");
            sx = row.getInteger("sizeX");
            sy = row.getInteger("sizeY");
            sz = row.getInteger("sizeZ");
        }

        static ClipBounds read(NBTTagCompound row) {
            if (row == null || !row.hasKey("sequence", 4) || row.getLong("sequence") <= 0) return null;
            for (String key : new String[] { "x", "y", "z", "dimension", "sizeX", "sizeY", "sizeZ" })
                if (!row.hasKey(key, 3)) return null;
            ClipBounds bounds = new ClipBounds(row);
            return fits(bounds.x, bounds.y, bounds.z, bounds.sx, bounds.sy, bounds.sz) ? bounds : null;
        }

        boolean sameEntry(ClipBounds other) {
            return other != null && id.equals(other.id)
                && sequence == other.sequence
                && x == other.x
                && y == other.y
                && z == other.z
                && dimension == other.dimension
                && sx == other.sx
                && sy == other.sy
                && sz == other.sz;
        }

        int[] boxAt(Transform pose, int[] at) {
            if (at == null || at.length != 3) return null;
            int[] size = pose.size(sx, sy, sz);
            if (!fits(at[0], at[1], at[2], size[0], size[1], size[2])) return null;
            return new int[] { at[0], at[1], at[2], at[0] + size[0] - 1, at[1] + size[1] - 1, at[2] + size[2] - 1 };
        }

        private static boolean fits(int x, int y, int z, int sx, int sy, int sz) {
            return sx > 0 && sy > 0
                && sz > 0
                && sx <= HologramCutProtocol.MAX_SIDE
                && sy <= HologramCutProtocol.MAX_SIDE
                && sz <= HologramCutProtocol.MAX_SIDE
                && (long) sx * sy * sz <= HologramCutProtocol.MAX_CELLS
                && x >= -30000000
                && z >= -30000000
                && y >= 0
                && (long) x + sx <= 30000000
                && (long) z + sz <= 30000000
                && (long) y + sy <= 256;
        }
    }

    static String coordinates(int[] at) {
        if (at == null)
            return HologramClient.text("camera.noTarget", "No valid displayed target / target chunk is not loaded");
        return at[0] + ", " + at[1] + ", " + at[2];
    }

    static String text(String key) {
        return HologramClient.text("cut." + key, key);
    }
}
