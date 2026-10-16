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
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/** A separate server-owned cut directory; ordinary blueprint state never enters this controller. */
final class HologramCutClient {

    static final HologramCutClient INSTANCE = new HologramCutClient();
    private final List<NBTTagCompound> entries = new ArrayList<>();
    private final HologramCutRequests requests = new HologramCutRequests();
    private final HologramProgress.Tracker operationProgress = new HologramProgress.Tracker();
    private final HologramWorldFlow.KeyEdge previousKey = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge nextKey = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge rotateKey = new HologramWorldFlow.KeyEdge();
    private Object world;
    private int dimension;
    private boolean loaded, directoryUnavailable, rightDown;
    private String selected = "", previewId = "";
    private final HologramCutNotice status = new HologramCutNotice();
    private HologramState preview;
    private NBTTagCompound rawPreview;
    private Transform pose = new Transform(0, 0, 0, 0);
    private int[] first, second, locked;
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
        return HologramCutInput.interceptsUse(stage, !entries.isEmpty(), shift(), blueprintBusy());
    }

    void tick() {
        Minecraft mc = mc();
        if (!Mouse.isCreated() || !Mouse.isButtonDown(1)) rightDown = false;
        if (world != mc.theWorld || mc.thePlayer != null && dimension != mc.thePlayer.dimension) {
            HologramNetwork.clearCutClientTransport();
            world = mc.theWorld;
            dimension = mc.thePlayer == null ? 0 : mc.thePlayer.dimension;
            entries.clear();
            requests.clear();
            operationProgress.clear();
            loaded = false;
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
            if (active()) cancel();
            closeScreen();
            requests.observeHeld(false);
            return;
        }
        if (requests.observeHeld(true)) refreshDirectory();
        boolean enabled = mc.currentScreen == null && !entries.isEmpty()
            && !blueprintBusy()
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
    }

    private void closeScreen() {
        if (mc().currentScreen instanceof HologramCutModeScreen
            || mc().currentScreen instanceof HologramCutStorageScreen
            || mc().currentScreen instanceof HologramCutConfirmScreen
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
        HologramCutInput.Intent intent = HologramCutInput
            .right(stage, !entries.isEmpty(), shift, alt, blueprintBusy(), cutUnavailable());
        if (HologramCutInput.requiresCutReady(intent) && busy()) {
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
        if (busy()) {
            retryCutStorage();
            return;
        }
        HologramCutRawPreview.release();
        rawPreview = null;
        pose = new Transform(0, 0, 0, 0);
        stage = HologramCutInput.Stage.SELECTING;
        first = second = locked = null;
        preview = null;
        previewId = "";
        status.show(text("selection"));
        mc().displayGuiScreen(null);
    }

    private void mark(boolean firstCorner) {
        if (busy()) {
            retryCutStorage();
            return;
        }
        int[] point = HologramClient.cutAnchor(false);
        if (firstCorner) first = point;
        else second = point;
        if (first == null || second == null) return;
        int[] a = first.clone(), b = second.clone();
        mc().displayGuiScreen(
            new HologramCutConfirmScreen(
                text("confirmCut"),
                text("confirmCutDetail") + " " + coordinates(a) + " → " + coordinates(b),
                () -> {
                    if (busy()) {
                        retryCutStorage();
                        return;
                    }
                    NBTTagCompound n = action("cut", "");
                    for (int i = 0; i < 3; i++) {
                        n.setInteger(new String[] { "x1", "y1", "z1" }[i], a[i]);
                        n.setInteger(new String[] { "x2", "y2", "z2" }[i], b[i]);
                    }
                    send(n);
                    stage = HologramCutInput.Stage.IDLE;
                    first = second = null;
                },
                this::cancel));
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
        HologramCutRequests.Request rejected = requests.acknowledge(event.getString("requestId"));
        if (rejected == null) return;
        if (operationProgress.matches(rejected.id)) operationProgress.clear();
        boolean visible = !"list".equals(rejected.action) || !rejected.target.isEmpty()
            || mc().currentScreen instanceof HologramCutStorageScreen;
        if ("list".equals(rejected.action)) {
            directoryUnavailable = true;
            status.directoryFailed(HologramProgressView.phase("busy"));
        } else if (visible) status.show(HologramProgressView.phase("busy"));
    }

    boolean busy() {
        return cutUnavailable() || requests.busy();
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
        if (selected.isEmpty() || busy()) return;
        stage = HologramCutInput.Stage.MOVING;
        locked = null;
        lockPreview(false);
        mc().displayGuiScreen(null);
        if (preview == null || !previewId.equals(selected)) request("preview", selected);
        status.show(text("moving"));
    }

    void undo() {
        if (selected.isEmpty() || busy()) return;
        final String id = selected;
        mc().displayGuiScreen(new HologramCutConfirmScreen(text("confirmUndo"), text("confirmUndoDetail"), () -> {
            if (busy()) retryCutStorage();
            else request("undo", id);
        }, () -> {}));
    }

    private void confirmPaste() {
        if (locked == null || preview == null || !previewId.equals(selected) || busy()) return;
        final String id = selected;
        final int[] at = locked.clone();
        mc().displayGuiScreen(
            new HologramCutConfirmScreen(
                text("confirmPaste"),
                text("confirmPasteDetail") + " " + coordinates(at) + " | " + poseSummary(),
                () -> {
                    if (busy()) {
                        retryCutStorage();
                        return;
                    }
                    NBTTagCompound n = action("paste", id);
                    n.setInteger("originX", at[0]);
                    n.setInteger("originY", at[1]);
                    n.setInteger("originZ", at[2]);
                    pose.write(n);
                    send(n);
                },
                this::cancel,
                this::openTransform));
    }

    boolean escape(GuiOpenEvent event) {
        if (!(event.gui instanceof GuiIngameMenu) || !active()) return false;
        cancel();
        event.setCanceled(true);
        return true;
    }

    void cancel() {
        HologramCutRawPreview.release();
        rawPreview = null;
        pose = new Transform(0, 0, 0, 0);
        stage = HologramCutInput.Stage.IDLE;
        first = second = locked = null;
        preview = null;
        previewId = "";
    }

    int[] anchor() {
        return locked == null ? HologramClient.cutAnchor(true) : locked.clone();
    }

    private void lockPreview(boolean locked) {
        if (preview == null) return;
        NBTTagCompound data = (NBTTagCompound) preview.data.copy();
        data.setBoolean("virtualLocked", locked);
        preview = new HologramState(data);
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
            directoryUnavailable = false;
            entries.clear();
            NBTTagList list = n.getTagList("entries", 10);
            for (int i = 0; i < list.tagCount(); i++) entries.add(
                (NBTTagCompound) list.getCompoundTagAt(i)
                    .copy());
            entries.sort((a, b) -> Long.compare(a.getLong("sequence"), b.getLong("sequence")));
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
                if (completed && ("paste".equals(resolved.action) || "undo".equals(resolved.action))) cancel();
            }
        }
        if ("list".equals(op) && !reply.target.isEmpty()
            && !requests.unresolvedWrite()
                .isEmpty())
            status.directoryFailed(n.getBoolean("success") ? text("syncTimeout") : n.getString("message"));
        if (n.getBoolean("success")) {
            if ("paste".equals(op) || "undo".equals(op)) cancel();
            if (n.hasKey("preview") && selected.equals(n.getString("id"))) {
                previewId = selected;
                rawPreview = detachedPreview(n.getCompoundTag("preview"));
                HologramCutRawPreview.update(rawPreview);
                preview = new HologramState(new NBTTagCompound());
                lockPreview(stage == HologramCutInput.Stage.LOCKED);
            }
        }
        if (!"list".equals(op) && !"preview".equals(op) && mc().thePlayer != null)
            mc().thePlayer.addChatMessage(new ChatComponentText(status()));
    }

    static NBTTagCompound detachedPreview(NBTTagCompound source) {
        return (NBTTagCompound) source.copy();
    }

    boolean guide(List<String> lines) {
        if (!held() || blueprintBusy() && !active()) return false;
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
            NBTTagCompound data = new NBTTagCompound();
            if (first != null && second != null) data
                .setIntArray("areaPoints", new int[] { first[0], first[1], first[2], second[0], second[1], second[2] });
            else if (first != null || second != null) data.setIntArray("areaPoints", first == null ? second : first);
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
