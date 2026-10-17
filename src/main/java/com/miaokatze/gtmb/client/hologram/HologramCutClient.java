package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.settings.KeyBinding;
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
import com.miaokatze.gtmb.hologram.HologramCutPlacementPreference;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/** A separate server-owned cut directory; ordinary blueprint state never enters this controller. */
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
    private Object world;
    private int dimension;
    private boolean loaded, directoryUnavailable, rightDown;
    private boolean skipNamePrompt;
    private boolean skipPlacementPrompt;
    private boolean cutMode, cutSession;
    private String selected = "", previewId = "";
    private final HologramCutNotice status = new HologramCutNotice();
    private HologramState preview;
    private NBTTagCompound rawPreview;
    private Transform pose = new Transform(0, 0, 0, 0);
    private int[] locked;
    private com.miaokatze.gtmb.hologram.HologramAreaSelection selection;
    private final HologramSelectionInput selectionInput = new HologramSelectionInput();
    private HologramCutInput.Stage stage = HologramCutInput.Stage.IDLE;

    private HologramCutClient() {}

    void install() {
        HologramNetwork.setCutReceiver(nbt -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            Object receivedWorld = minecraft.theWorld, connection = minecraft.getNetHandler();
            if (receivedWorld == null || connection == null) return;
            minecraft.func_152344_a(
                () -> {
                    if (receivedWorld == minecraft.theWorld && connection == minecraft.getNetHandler()) receive(nbt);
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

    boolean selecting() {
        return stage == HologramCutInput.Stage.SELECTING;
    }

    private boolean blueprintBusy() {
        return HologramClient.state != null && HologramCutInput.blueprintBusy(HologramClient.state.data);
    }

    boolean interceptsUse() {
        return HologramCutInput.interceptsUse(stage, shift(), blueprintBusy(), cutMode);
    }

    void tick() {
        Minecraft mc = mc();
        if (!Mouse.isCreated() || !Mouse.isButtonDown(1)) rightDown = false;
        if (world != mc.theWorld || mc.thePlayer != null && dimension != mc.thePlayer.dimension) {
            HologramCutStoragePreview.invalidate();
            HologramNetwork.clearCutClientTransport();
            world = mc.theWorld;
            dimension = mc.thePlayer == null ? 0 : mc.thePlayer.dimension;
            entries.clear();
            requests.clear();
            operationProgress.clear();
            loaded = false;
            skipNamePrompt = false;
            skipPlacementPrompt = false;
            cutMode = false;
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
                status.show(text("checkingResult"));
                request("list", expired.id);
            } else if ("preview".equals(expired.action)) status.show(text("requestTimeout"));
            else if ("list".equals(expired.action)) {
                directoryUnavailable = true;
                status.directoryFailed(text("syncTimeout"));
            }
        }
        if (!held()) {
            if (HologramCutInput.cancelsActivity(stage, cutSession, rawPreview != null || preview != null)) cancel();
            closeScreen();
            requests.observeHeld(false);
            return;
        }
        if (requests.observeHeld(true)) refreshDirectory();
        boolean enabled = mc.currentScreen == null && cutMode
            && !entries.isEmpty()
            && !blueprintBusy()
            && !HologramBackgroundTasks.busy()
            && stage != HologramCutInput.Stage.SELECTING;
        boolean previous = previousKey.sample(alt() && key(Keyboard.KEY_A), !enabled);
        boolean next = nextKey.sample(alt() && key(Keyboard.KEY_D), !enabled);
        if (enabled && (previous || next)) cycle(previous ? -1 : 1);
        boolean poseEnabled = mc.currentScreen == null
            && (stage == HologramCutInput.Stage.MOVING || stage == HologramCutInput.Stage.LOCKED)
            && !busy();
        if (rotateKey.sample(key(Keyboard.KEY_R), !poseEnabled)) {
            if (alt()) openTransform();
            else rotate(key(Keyboard.KEY_LCONTROL) || key(Keyboard.KEY_RCONTROL) ? 0 : 1, 1);
        }
        selectionInput.tick(selection, selecting() && held() && !busy() && !blueprintBusy());
        lockedNudge();
    }

    private void lockedNudge() {
        Minecraft mc = mc();
        boolean enabled = mc.currentScreen == null && held()
            && stage == HologramCutInput.Stage.LOCKED
            && locked != null
            && preview != null
            && !busy()
            && !blueprintBusy();
        int[] keys = { Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT, Keyboard.KEY_W,
            Keyboard.KEY_S };
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
        if (!held() || mc().currentScreen != null) return false;
        if (event.dwheel != 0 && aiming() && alt()) return false; // shared distance handler owns the wheel
        boolean intercept = event.button == 1 && interceptsUse() || event.button == 0 && active();
        if (!intercept) return false;
        event.setCanceled(true);
        KeyBinding.setKeyBindState(mc().gameSettings.keyBindUseItem.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc().gameSettings.keyBindAttack.getKeyCode(), false);
        if (!event.buttonstate) return true;
        if (event.button == 0) {
            if (selecting()) mark(true);
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
            cutMode,
            key(Keyboard.KEY_LCONTROL) || key(Keyboard.KEY_RCONTROL));
        if (!HologramCutInput.allowsWorldAction(intent, HologramBackgroundTasks.busy())) return;
        if (HologramCutInput.requiresCutReady(intent)
            && (intent == HologramCutInput.Intent.SELECT ? cutUnavailable() : busy())) {
            retryCutStorage();
            return;
        }
        switch (intent) {
            case SWITCHER:
                mc().displayGuiScreen(new HologramCutModeScreen());
                break;
            case SELECT:
                beginSelection();
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
                mc().displayGuiScreen(new HologramCutStorageScreen());
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
                locked = HologramClient.cutAnchor(true);
                nudgeContext++;
                stage = HologramCutInput.Stage.LOCKED;
                lockPreview(true);
                status.show(text("locked"));
                break;
            case CONFIRM:
                confirmPaste();
                break;
            default:
                break;
        }
    }

    void beginSelection() {
        if (HologramBackgroundTasks.busy() || blueprintBusy()) return;
        cutMode = true;
        continuous.enter();
        resetSelection();
        mc().displayGuiScreen(null);
    }

    private void resetSelection() {
        requests.exitActivity();
        operationProgress.clear();
        HologramCutRawPreview.release();
        rawPreview = null;
        pose = new Transform(0, 0, 0, 0);
        stage = HologramCutInput.Stage.SELECTING;
        cutSession = true;
        selection = new com.miaokatze.gtmb.hologram.HologramAreaSelection();
        locked = null;
        preview = null;
        previewId = "";
        nudgeContext++;
        status.show(text("selection"));
    }

    private void mark(boolean firstCorner) {
        if (busy()) {
            retryCutStorage();
            return;
        }
        int[] point = HologramClient.cutAnchor(false);
        try {
            selection.point(
                firstCorner ? 1 : 2,
                point[0],
                point[1],
                point[2],
                HologramClient.areaSelectionMode(HologramClient.controlHeld(), shift()));
        } catch (java.io.IOException invalid) {
            status.show(invalid.getMessage());
        }
    }

    /** A complete pair remains editable until a separate Shift-right confirmation. */
    private void confirmSelection() {
        final NBTTagCompound chosen = selection.snapshot();
        final int[] bounds;
        try {
            if (selection.regionSnapshot()
                .tagCount() == 0) {
                status.show(HologramClient.text("worldGuideSelectionBoth", "Left: point 1 · Right: point 2"));
                return;
            }
            bounds = selection.bounds();
        } catch (java.io.IOException invalid) {
            status.show(invalid.getMessage());
            return;
        }
        String detail = text("confirmCutDetail") + " "
            + coordinates(new int[] { bounds[0], bounds[1], bounds[2] })
            + " → "
            + coordinates(new int[] { bounds[3], bounds[4], bounds[5] });
        if (skipNamePrompt) mc().displayGuiScreen(
            new HologramCutConfirmScreen(
                text("confirmCut"),
                detail,
                () -> submitCut(chosen, bounds, "", false),
                () -> {}));
        else mc().displayGuiScreen(
            new HologramCutNameScreen(detail, (name, skip) -> submitCut(chosen, bounds, name, skip), () -> {}));
    }

    private void submitCut(NBTTagCompound chosen, int[] bounds, String name, boolean skipPrompt) {
        if (!held() || !selecting() || selection == null || !chosen.equals(selection.snapshot())) return;
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

    boolean selectionWheel(int wheel) {
        if (!selecting() || !held() || mc().currentScreen != null || selection == null) return false;
        selection.cycle(wheel > 0 ? -1 : 1);
        return true;
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
        if (operationProgress.matches(rejected.id)) operationProgress.clear();
        boolean visible = !"list".equals(rejected.action) || !rejected.target.isEmpty()
            || mc().currentScreen instanceof HologramCutStorageScreen;
        if ("list".equals(rejected.action)) {
            directoryUnavailable = true;
            status.directoryFailed(HologramProgressView.phase("busy"));
        } else if (visible) status.show(HologramProgressView.phase("busy"));
    }

    boolean busy() {
        return HologramBackgroundTasks.busy() || cutUnavailable() || requests.busy();
    }

    private boolean cutUnavailable() {
        return !loaded || directoryUnavailable || requests.writing();
    }

    boolean syncingDirectory() {
        return requests.active("list");
    }

    void refreshDirectory() {
        if (!requests.active("list")) request("list", requests.unresolvedWrite());
    }

    private void retryCutStorage() {
        status.directoryFailed(text("syncRequired"));
        // An in-flight write keeps its original timeout and identity; never resend it.
        if (!requests.busy() || !requests.unresolvedWrite()
            .isEmpty()) refreshDirectory();
        mc().displayGuiScreen(new HologramCutStorageScreen());
    }

    void select(String id) {
        if (selected.equals(id)) return;
        selected = id;
        pose = new Transform(0, 0, 0, 0);
        HologramCutRawPreview.release();
        rawPreview = null;
        preview = null;
        previewId = "";
        locked = null;
        nudgeContext++;
        if (stage == HologramCutInput.Stage.MOVING || stage == HologramCutInput.Stage.LOCKED) {
            stage = HologramCutInput.Stage.MOVING;
            request("preview", selected);
        }
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
        if (busy()) return;
        if (selected.isEmpty() || entries.isEmpty()) {
            status.show(text("emptyPreview"));
            return;
        }
        cutMode = true;
        continuous.exit();
        requests.exitActivity();
        operationProgress.clear();
        cutSession = true;
        stage = HologramCutInput.Stage.MOVING;
        locked = null;
        lockPreview(false);
        mc().displayGuiScreen(null);
        if (preview == null || !previewId.equals(selected)) request("preview", selected);
        status.show(text("moving"));
    }

    /** Browsing never changes the world preview; an explicit storage action starts it once. */
    void beginStoragePlacement(String id) {
        if (!held() || busy() || !containsEntry(id)) return;
        stage = HologramCutInput.Stage.IDLE;
        select(id); // IDLE suppresses select's in-world cycle preview request.
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
        if (!held() || busy() || !containsEntry(id)) return;
        mc().displayGuiScreen(new HologramCutConfirmScreen(text("confirmUndo"), text("confirmUndoDetail"), () -> {
            if (busy()) retryCutStorage();
            else request("undo", id);
        }, () -> {}));
    }

    private void confirmPaste() {
        if (!held() || stage != HologramCutInput.Stage.LOCKED
            || locked == null
            || preview == null
            || !previewId.equals(selected)
            || busy()) return;
        final String id = selected;
        final int[] at = locked.clone();
        final Transform placementPose = pose;
        // Only the second right press, after the preview was explicitly locked, reaches this branch.
        if (skipPlacementPrompt) {
            submitPaste(id, at, placementPose, false);
            return;
        }
        mc().displayGuiScreen(
            new HologramCutConfirmScreen(
                text("confirmPaste"),
                text("confirmPasteDetail") + " " + coordinates(at) + " | " + poseSummary(),
                () -> submitPaste(id, at, placementPose, false),
                this::cancel,
                () -> submitPaste(id, at, placementPose, true)));
    }

    private void submitPaste(String id, int[] at, Transform placementPose, boolean skipPrompt) {
        if (!held() || stage != HologramCutInput.Stage.LOCKED
            || !id.equals(selected)
            || !id.equals(previewId)
            || preview == null
            || !java.util.Arrays.equals(locked, at)) return;
        if (busy()) {
            retryCutStorage();
            return;
        }
        NBTTagCompound n = action("paste", id);
        n.setInteger("originX", at[0]);
        n.setInteger("originY", at[1]);
        n.setInteger("originZ", at[2]);
        placementPose.write(n);
        if (skipPrompt) n.setBoolean(HologramCutPlacementPreference.SKIP_PROMPT, true);
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
        if (!HologramCutInput.exitsMode(stage, cutMode, cutSession, rawPreview != null || preview != null))
            return false;
        exitCutMode();
        return true;
    }

    /** Escape leaves the tool inactive without issuing a world action or forgetting an in-flight write. */
    void exitCutMode() {
        chooseBlueprintMode();
        closeScreen();
        status.show(text("exited"));
    }

    boolean cutMode() {
        return cutMode;
    }

    void chooseCutMode() {
        cancel();
        cutMode = true;
        beginSelection();
    }

    void chooseBlueprintMode() {
        cutMode = false;
        cancel();
    }

    void cancel() {
        continuous.exit();
        requests.exitActivity();
        operationProgress.clear();
        nudgeContext++;
        HologramCutRawPreview.release();
        rawPreview = null;
        pose = new Transform(0, 0, 0, 0);
        stage = HologramCutInput.Stage.IDLE;
        cutSession = false;
        selection = null;
        locked = null;
        preview = null;
        previewId = "";
    }

    int[] anchor() {
        return locked == null ? HologramClient.cutAnchor(true) : locked.clone();
    }

    private void lockPreview(boolean locked) {
        if (preview == null) return;
        NBTTagCompound data = new NBTTagCompound();
        data.setBoolean("virtualLocked", locked);
        preview = preview.withMetadata(data);
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
        if ("preview".equals(op)) pose.write(n);
        send(n);
    }

    private void send(NBTTagCompound n) {
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
        if (mc().theWorld != world || mc().thePlayer == null) return;
        if (HologramCutStoragePreview.receive(n)) return;
        HologramCutRequests.Request reply = requests.acknowledge(n.getString("requestId"));
        if (reply == null) return; // old world/connection ACK cannot revive a preview
        String op = reply.action;
        boolean recoveryPending = "recovery_pending".equals(n.getString("code"));
        boolean acceptedDirectory = n.getBoolean("success") && n.hasKey("entries", 9) && !recoveryPending;
        if (recoveryPending || !n.getBoolean("success") && reply.writesWorld()) directoryUnavailable = true;
        if (n.getBoolean("success")) {
            operationProgress.finish(reply.id, System.nanoTime());
        } else if (operationProgress.matches(reply.id)) operationProgress.clear();
        if (acceptedDirectory) {
            loaded = true;
            // This choice is one-way until leaving the save; a delayed older directory cannot undo it.
            skipNamePrompt = skipNamePrompt || HologramCutNaming.skipsPrompt(n);
            skipPlacementPrompt = skipPlacementPrompt || HologramCutPlacementPreference.skipsPrompt(n);
            directoryUnavailable = false;
            entries.clear();
            NBTTagList list = n.getTagList("entries", 10);
            for (int i = 0; i < list.tagCount(); i++) entries.add(
                (NBTTagCompound) list.getCompoundTagAt(i)
                    .copy());
            entries.sort((a, b) -> Long.compare(a.getLong("sequence"), b.getLong("sequence")));
            HologramCutStoragePreview.directoryChanged(entries);
            status.directoryRecovered();
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
        if (!"list".equals(op)) status.operationResult(n.getString("message"), n.getString("code"));
        if ("list".equals(op) && !reply.target.isEmpty()
            && reply.target.equals(n.getString("reconciledRequestId"))
            && acceptedDirectory) {
            HologramCutRequests.Request resolved = requests.reconcile(reply.target);
            if (resolved != null) {
                boolean completed = n.getBoolean("requestCompleted");
                status.show(text(completed ? "resultConfirmed" : "notExecuted"));
                if ("cut".equals(resolved.action)) finishCut(resolved.id, completed);
                if (completed && requests.currentActivity(resolved)
                    && ("paste".equals(resolved.action) || "undo".equals(resolved.action))) cancel();
            }
        }
        if ("list".equals(op) && !reply.target.isEmpty()
            && !requests.unresolvedWrite()
                .isEmpty())
            status.directoryFailed(n.getBoolean("success") ? text("syncTimeout") : n.getString("message"));
        if (n.getBoolean("success")) {
            if (requests.currentActivity(reply) && ("paste".equals(op) || "undo".equals(op))) cancel();
            if (requests.currentActivity(reply)
                && (stage == HologramCutInput.Stage.MOVING || stage == HologramCutInput.Stage.LOCKED)
                && n.hasKey("preview")
                && selected.equals(n.getString("id"))) {
                previewId = selected;
                rawPreview = detachedPreview(n.getCompoundTag("preview"));
                HologramCutRawPreview.update(rawPreview);
                preview = new HologramState(new NBTTagCompound());
                lockPreview(stage == HologramCutInput.Stage.LOCKED);
            }
        }
        if ("cut".equals(op)) finishCut(reply.id, n.getBoolean("success"));
        if (!"list".equals(op) && !"preview".equals(op)
            && (!"cut".equals(op) || !n.getBoolean("success"))
            && mc().thePlayer != null) mc().thePlayer.addChatMessage(new ChatComponentText(status()));
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

    boolean guide(List<String> lines) {
        if (!held() || !cutMode && !active() || blueprintBusy() && !active()) return false;
        if (cutMode) lines.add(text("cutModeHint"));
        if (active()) lines.add(
            text(
                stage == HologramCutInput.Stage.SELECTING ? "selection"
                    : stage == HologramCutInput.Stage.MOVING ? "moving" : "locked"));
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
            lines.add(
                HologramClient
                    .text("worldGuideSelectionEdit", "Left/right: adjust corners · Shift+Alt+right: more boxes"));
            lines.add(
                HologramClient.text(
                    "worldGuideSelectionKeys",
                    "Arrows: move · Alt+Up/Down: vertical · Scroll: current box · Delete: remove · Ctrl+Z: undo"));
        }
        if (active() && !selecting()) {
            lines.add(poseSummary());
            lines.add("R: Y +90 | Ctrl+R: X +90 | Alt+R: " + text("transform"));
            if (rawPreview != null && rawPreview.getTagList("transformUnknown", 8)
                .tagCount() > 0)
                lines.add(
                    text("transformUnknown") + " ("
                        + rawPreview.getTagList("transformUnknown", 8)
                            .tagCount()
                        + ")");
            List<String> diagnostics = HologramCutRawPreview.diagnostics();
            if (!diagnostics.isEmpty()) lines.add(diagnostics.get(0) + " (" + diagnostics.size() + ")");
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
        } else if (rawPreview != null && previewId.equals(selected)) {
            int[] at = anchor();
            HologramCutRawPreview.render(at[0], at[1], at[2], ticks);
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
        if (busy() || selected.isEmpty()) return;
        pose = next;
        preview = null;
        rawPreview = null;
        previewId = "";
        HologramCutRawPreview.release();
        request("preview", selected);
    }

    String poseSummary() {
        String dimensions = rawPreview == null ? "..."
            : rawPreview.getInteger("sizeX") + " × "
                + rawPreview.getInteger("sizeY")
                + " × "
                + rawPreview.getInteger("sizeZ");
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

    static String coordinates(int[] at) {
        return at[0] + ", " + at[1] + ", " + at[2];
    }

    static String text(String key) {
        return HologramClient.text("cut." + key, key);
    }
}
