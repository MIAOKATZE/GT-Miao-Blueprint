package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.opengl.GL11;

/**
 * Deterministic client-only teaching. A second module graph owns every demonstrated change;
 * the real module graph remains the recipient of authoritative snapshots and background work.
 * Only the four navigation controls escape the sandbox's rendering pass.
 */
final class HologramEditorTutorial {

    private static final int BASE = HologramEditorControlIds.TUTORIAL;
    private static final long MOVE_NS = 850_000_000L, PRESS_NS = 180_000_000L, REST_NS = 360_000_000L;
    private final HologramUiModules ui;
    private HologramUiModules sandbox;
    private final List<Step> steps = new ArrayList<>();
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private List<Action> actions = new ArrayList<>();
    private String topic = "", requestedTopic = "", lastAutoTopic = "";
    private int step, actionIndex;
    private long actionStarted;
    private boolean replay, queuedNext, pressed, actionApplied;
    private double pointerX = 340, pointerY = 170, fromX, fromY, targetX, targetY;
    private NBTTagCompound dragStart;
    private double dragProgress;
    private int cornerCount, cutStage;
    private boolean cutStored, cutConsumed;
    private long isolatedCommands;
    private final HologramEditorMaterialLesson materialLesson = new HologramEditorMaterialLesson();
    private Runnable completionAction;
    private final HologramEditorLessonStage worldStage = new HologramEditorLessonStage();
    private int worldOffsetX, worldOffsetZ;
    private double motionX, motionY;
    private String unavailable = "";
    private boolean dragAllowed;

    HologramEditorTutorial(HologramUiModules ui) {
        this.ui = ui;
    }

    private HologramEditorPreferences preferences() {
        return HologramEditorPreferences.get(ui.host.minecraft().mcDataDir);
    }

    boolean active() {
        return sandbox != null;
    }

    boolean blockInput() {
        return active();
    }

    HologramUiModules renderUi() {
        return active() ? sandbox : ui;
    }

    /** Read-only diagnostics used by the existing native probe bridge. */
    NBTTagCompound diagnosticState() {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("active", active());
        result.setString("topic", topic);
        result.setInteger("step", step);
        result.setInteger("stepCount", steps.size());
        result.setInteger("action", actionIndex);
        result.setInteger("actionCount", actions.size());
        result.setBoolean("queuedNext", queuedNext);
        result.setBoolean("pressed", pressed);
        result.setDouble("pointerX", pointerX);
        result.setDouble("pointerY", pointerY);
        result.setLong("isolatedCommands", isolatedCommands);
        result.setLong("realSessionSequence", ui.session.sequence());
        result.setBoolean("cutStored", cutStored);
        result.setBoolean("cutConsumed", cutConsumed);
        result.setInteger("cornerCount", cornerCount);
        result.setString("unavailable", unavailable);
        return result;
    }

    private String currentTopic() {
        return ui.session.state().data.getBoolean("virtual") || ui.library.blueprintTab() ? "blueprint" : "projection";
    }

    void tick() {
        if (ui.session.isTutorial()) return;
        if (active()) {
            // A new real task takes precedence. Never suppress ACKs, file workers or their controls.
            if (!ready()) closeWithoutProgress();
            return;
        }
        if (!requestedTopic.isEmpty()) {
            if (ready()) begin(requestedTopic, replay);
            return;
        }
        String current = currentTopic();
        if (!current.equals(lastAutoTopic) && ready() && !preferences().seen(current)) {
            lastAutoTopic = current;
            begin(current, false);
        }
    }

    private boolean ready() {
        if (HologramBackgroundTasks.busy() || ui.session.pendingSequence() >= 0
            || ui.session.progress() != null
            || !ui.session.editable()
            || ui.preview.gizmo()
                .axis() >= 0
            || ui.preview.boxSelecting()) return false;
        NBTTagCompound state = ui.session.state().data;
        return !state.getBoolean("snapshotPending") && !state.getBoolean("placementValidating")
            && !state.getBoolean("attachmentPending")
            && !ui.library.saveGuidanceOpen()
            && !ui.review.materialDialogOpen()
            && !ui.review.confirmation();
    }

    void start(String topic, boolean replay) {
        if (ui.session.isTutorial() || active()) return;
        if (!knownTopic(topic)) throw new IllegalArgumentException("Unknown editor lesson: " + topic);
        this.replay = replay;
        requestedTopic = topic;
        if (ready()) begin(topic, replay);
        else ui.session.notice("当前操作完成后开始教程；后台任务与文件处理继续。");
    }

    /** A Cut browser may own a detached host; finishing returns to the same suspended browser object. */
    void startDetached(String topic, boolean replay, Runnable returnToBrowser) {
        if (active()) return;
        if (!ui.session.isTutorial())
            throw new IllegalStateException("Detached tutorials require an isolated host session");
        completionAction = returnToBrowser;
        begin(topic, replay);
    }

    static HologramState detachedState() {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "__gtmb_detached_tutorial_host__");
        data.setInteger("y", 64);
        return sample(new HologramState(data), "cut");
    }

    private static boolean knownTopic(String id) {
        return id.equals("projection") || id.equals("blueprint")
            || id.equals("cut")
            || id.equals("structure")
            || id.equals("box-fps")
            || id.equals("box-iso")
            || id.equals("cut-fps")
            || id.equals("cut-iso");
    }

    private void begin(String id, boolean replay) {
        requestedTopic = "";
        topic = id;
        if (id.equals("blueprint") || id.equals("projection")) lastAutoTopic = id;
        this.replay = replay;
        ui.host.parent()
            .beginTutorialFields();
        try {
            sandbox = new HologramUiModules(ui.host);
            sandbox.session.initializeIsolated(sample(ui.session.state(), id), this::isolatedCommand);
            // Deliberately do not call router.initGui: it opens/refreshes the production library singleton.
            sandbox.canvas.resize();
            sandbox.config.initializeFields();
            sandbox.position.initializeFields();
            sandbox.library.initializeFields();
            sandbox.annotations.initializeFields();
            sandbox.bindings.initializeFields();
            sandbox.library.blueprintTab(id.equals("blueprint"));
            sandbox.library.libraryDrawer(id.equals("blueprint"));
            sandbox.preview.yaw(Math.toRadians(38));
            sandbox.preview.pitch(Math.toRadians(25));
            isolatedCommands = 0;
            cornerCount = cutStage = 0;
            worldOffsetX = worldOffsetZ = 0;
            cutStored = cutConsumed = false;
            step = 0;
            queuedNext = false;
            checkpoints.clear();
            buildCourse(id);
            checkpoints.add(capture());
            playStep();
        } catch (RuntimeException failure) {
            closeWithoutProgress();
            throw failure;
        }
    }

    void closeWithoutProgress() {
        requestedTopic = "";
        ui.host.parent()
            .endTutorialFields();
        if (!active()) return;
        sandbox = null;
        actions.clear();
        steps.clear();
        checkpoints.clear();
        dragStart = null;
        unavailable = "";
        materialLesson.close();
        HologramEditorTutorialBlur.dispose();
        pressed = queuedNext = false;
        // The next frame renders the real snapshot again; no world/cut/ghost static state was touched.
    }

    private void finish(boolean completed) {
        String completedTopic = topic;
        boolean record = !replay;
        Runnable returnToBrowser = completionAction;
        completionAction = null;
        closeWithoutProgress();
        if (record) preferences().finish(completedTopic, completed);
        if (returnToBrowser != null) ui.host.parent()
            .deferAction(returnToBrowser);
    }

    boolean activate(int id) {
        if (!active()) {
            if (id != BASE) return false;
            start(currentTopic(), true);
            return true;
        }
        if (id == BASE + 1 && step > 0) {
            step--;
            restore(checkpoints.get(step));
            queuedNext = false;
            playStep();
        } else if (id == BASE + 2) {
            if (actionIndex < actions.size()) queuedNext = true;
            else nextStep();
        } else if (id == BASE + 3) finish(false);
        else if (id == BASE + 4) {
            step = 0;
            restore(checkpoints.get(0));
            queuedNext = false;
            playStep();
        }
        return true; // Every other native/legacy control is intentionally swallowed.
    }

    private void nextStep() {
        queuedNext = false;
        if (step + 1 >= steps.size()) {
            finish(true);
            return;
        }
        step++;
        Checkpoint beginning = capture();
        if (checkpoints.size() > step) checkpoints.set(step, beginning);
        else checkpoints.add(beginning);
        while (checkpoints.size() > step + 1) checkpoints.remove(checkpoints.size() - 1);
        playStep();
    }

    private void playStep() {
        actions = steps.get(step).actions;
        actionIndex = 0;
        actionStarted = 0;
        pressed = actionApplied = false;
        dragStart = null;
        unavailable = sandbox.session.state().data.getBoolean("tutorialPartialSample") ? "当前快照过大，仅显示有界局部；本例不宣称已识别完整机器。"
            : "";
    }

    private void animate(long now) {
        if (actionIndex >= actions.size()) {
            if (queuedNext) nextStep();
            return;
        }
        Action action = actions.get(actionIndex);
        if (actionStarted == 0) {
            actionStarted = now;
            fromX = pointerX;
            fromY = pointerY;
            HologramUiCanvas.Control control = findControl(action.control);
            if (action.control >= 0 && (control == null || !control.enabled))
                unavailable = "当前快照无此可用控件或候选；请在具备对应能力的结构中查看。";
            int width = sandbox.canvas.layout()
                .width(),
                height = sandbox.canvas.layout()
                    .height();
            targetX = control == null ? action.x + (width - 680) / 2d : control.x + control.w / 2d;
            targetY = control == null ? action.y + (height - 340) / 2d : control.y + control.h / 2d;
            if (control == null && (action.operation == 5 || action.operation == 6)) {
                targetX = sandbox.canvas.layout()
                    .rightPanelX() + 80;
                targetY = action.y;
            }
            if (isWorldCourse() && (action.operation == 9 || action.operation == 10 || action.drag)) {
                double[] point = worldStage.point(
                    action.operation == 10 ? 4 : action.drag ? 1.675 : 0,
                    0,
                    action.operation == 10 ? 3 : action.drag ? 1.375 : 0);
                if (point != null) {
                    targetX = point[0];
                    targetY = point[1];
                }
            }
            motionX = action.dx;
            motionY = action.dy;
            dragAllowed = !action.drag || isWorldCourse() || sandbox.preview.canMoveSelection();
            if (!dragAllowed) unavailable = "当前选区不满足真实几何编辑门禁；保留原形，不演示不可用的搬移。";
            if (action.drag) {
                if (isWorldCourse()) {
                    double[] end = worldStage.point(4.675, 0, 3.375);
                    if (end != null) {
                        motionX = end[0] - targetX;
                        motionY = end[1] - targetY;
                    }
                } else if (sandbox.preview.canMoveSelection()) {
                    HologramGizmo gizmo = sandbox.preview.gizmo();
                    if (action.operation == 31) {
                        double[][] plane = gizmo.plane(HologramGizmo.XZ);
                        targetX = targetY = 0;
                        for (double[] point : plane) {
                            targetX += point[0] / 4;
                            targetY += point[1] / 4;
                        }
                    } else {
                        double[] end = gizmo.end(0);
                        targetX = end[0];
                        targetY = end[1];
                    }
                    double[] origin = HologramRenderer.project(0, 0, 0);
                    double[] end = HologramRenderer.project(3, 0, action.operation == 31 ? 2 : 0);
                    motionX = end[0] - origin[0];
                    motionY = end[1] - origin[1];
                }
            }
            actionApplied = false;
        }
        long elapsed = Math.max(0, now - actionStarted);
        double p = Math.min(1, elapsed / (double) MOVE_NS);
        double smooth = p * p * (3 - 2 * p);
        pointerX = fromX + (targetX - fromX) * smooth;
        pointerY = fromY + (targetY - fromY) * smooth;
        HologramUiCanvas.Control clickTarget = action.control < 0 ? null : findControl(action.control);
        boolean validTarget = action.control < 0 || clickTarget != null && clickTarget.enabled;
        pressed = validTarget && dragAllowed
            && elapsed >= MOVE_NS
            && elapsed < MOVE_NS + PRESS_NS + (action.drag ? MOVE_NS : 0);
        if (elapsed >= MOVE_NS && !actionApplied) {
            actionApplied = true;
            if (action.drag && dragAllowed) {
                dragStart = snapshot(sandbox.session.state());
                if (!isWorldCourse()) sandbox.preview.gizmo()
                    .begin(
                        action.operation == 31 ? HologramGizmo.XZ : 0,
                        (int) Math.round(targetX),
                        (int) Math.round(targetY));
            } else apply(action.operation, action.control);
        }
        if (action.drag && dragAllowed && actionApplied) {
            dragProgress = Math.max(0, Math.min(1, (elapsed - MOVE_NS) / (double) MOVE_NS));
            pointerX = targetX + motionX * dragProgress;
            pointerY = targetY + motionY * dragProgress;
            if (isWorldCourse()) {
                worldOffsetX = (int) Math.round(3 * dragProgress);
                worldOffsetZ = action.operation == 31 ? (int) Math.round(2 * dragProgress) : 0;
            } else sandbox.preview.gizmo()
                .update((int) Math.round(pointerX), (int) Math.round(pointerY));
        }
        long duration = MOVE_NS + PRESS_NS + REST_NS + (action.drag ? MOVE_NS : 0);
        if (elapsed >= duration) {
            if (action.drag && dragAllowed && !isWorldCourse()) {
                dragGeometry(action.operation);
                sandbox.preview.gizmo()
                    .cancel();
            }
            pressed = false;
            actionIndex++;
            actionStarted = 0;
            dragStart = null;
        }
    }

    void drawScreen(int mouseX, int mouseY, float ticks) {
        if (!active()) return;
        try {
            sandbox.canvas.beginFrame(mouseX, mouseY);
            // No sandbox updateScreen call: production tickers can refresh global library/file services.
            sandbox.router.drawScreen(-10000, -10000, ticks);
            animate(System.nanoTime());
            if (!active()) {
                if (!ui.session.isTutorial()) {
                    ui.canvas.beginFrame(mouseX, mouseY);
                    ui.router.drawScreen(mouseX, mouseY, ticks);
                }
                return;
            }
            ui.host.parent()
                .paintTutorialFields();
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glTranslatef(sandbox.canvas.originX(), sandbox.canvas.originY(), 0);
                GL11.glScalef(sandbox.canvas.scale(), sandbox.canvas.scale(), 1);
                // Domain controls are painted here, leaving only navigation in the native hit tree.
                List<HologramUiCanvas.Control> domain = new ArrayList<>(sandbox.canvas.controls());
                for (HologramUiCanvas.Control control : domain) paintControl(control);
                sandbox.canvas.controls()
                    .clear();
                sandbox.canvas.tooltip(null);
                drawLessonStage();
                materialLesson.draw(sandbox);
                drawFocus();
                drawPointer();
                drawNavigation(sandbox.canvas.localX(mouseX), sandbox.canvas.localY(mouseY));
            } finally {
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        } catch (RuntimeException failure) {
            closeWithoutProgress();
            ui.session.notice("教程已安全返回原工作区：" + (failure.getMessage() == null ? "演示无法继续" : failure.getMessage()));
        }
    }

    private void paintControl(HologramUiCanvas.Control control) {
        HologramEditorTheme.button(control.enabled, false, false)
            .draw(control.x, control.y, control.w, control.h);
        sandbox.canvas.text(
            control.label,
            control.x + 3,
            control.y + Math.max(2, (control.h - 8) / 2),
            control.w - 6,
            control.enabled ? HologramEditorTheme.BUTTON_TEXT : HologramEditorTheme.BUTTON_MUTED);
    }

    private void drawFocus() {
        int w = sandbox.canvas.layout()
            .width(),
            h = sandbox.canvas.layout()
                .height();
        Step current = steps.get(step);
        int x = current.focus == 1 ? sandbox.canvas.layout()
            .rightPanelX() : 12;
        int y = 55, fw = current.focus == 1 ? 220 : Math.max(220, w - 254), fh = Math.max(80, h - 182);
        boolean modalVisible = sandbox.config.drawer() || sandbox.config.hatchDrawer()
            || sandbox.config.presetDrawer()
            || sandbox.config.sourceDrawer()
            || !sandbox.config.optionChannel()
                .isEmpty()
            || sandbox.annotations.promptDrawer()
            || sandbox.nbt.nbtDrawer()
            || sandbox.bindings.networkDrawer();
        if (current.focus == 2 && modalVisible) {
            x = (w - 680) / 2 + 157;
            y = (h - 340) / 2 + 58;
            fw = 248;
            fh = Math.min(235, Math.max(80, h - y - 127));
        }
        if (materialLesson.visible()) {
            int[] bounds = materialLesson.bounds(w, h);
            x = bounds[0];
            y = bounds[1];
            fw = bounds[2];
            fh = bounds[3];
        }
        x = Math.max(0, Math.min(w - fw, x));
        HologramEditorTutorialBlur.drawOutside(w, h, x, y, fw, fh);
        Gui.drawRect(0, 0, w, y, 0x592b2927);
        Gui.drawRect(0, y, x, y + fh, 0x592b2927);
        Gui.drawRect(x + fw, y, w, y + fh, 0x592b2927);
        Gui.drawRect(0, y + fh, w, h, 0x592b2927);
        HologramEditorTheme.drawGuidanceFrame(x, y, fw, fh, HologramUiAccent.INFORMATION, true);
        if (!materialLesson.visible()) {
            sandbox.canvas.skin("panel", x + 6, y + fh - 33, fw - 12, 29);
            sandbox.canvas.text(current.title, x + 12, y + fh - 29, fw - 24, HologramEditorTheme.TITLE);
            sandbox.canvas.text("示例预演 · 仅下方四个按钮可操作", x + 12, y + fh - 16, fw - 24, HologramEditorTheme.MUTED);
        }
    }

    private void drawPointer() {
        int x = (int) Math.round(pointerX), y = (int) Math.round(pointerY);
        // Deliberately pixel aligned, with a discrete pressed halo instead of a blurred cursor.
        if (pressed) {
            Gui.drawRect(x - 5, y - 5, x + 9, y + 9, 0x78eab85f);
            Gui.drawRect(x - 3, y - 3, x + 7, y + 7, 0x70fff0be);
        }
        for (int row = 0; row < 12; row++) {
            int width = Math.min(row / 2 + 1, 6);
            Gui.drawRect(x - 1, y + row - 1, x + width + 1, y + row + 1, 0xff30231a);
            Gui.drawRect(x, y + row, x + width, y + row + 1, 0xffffe7b4);
        }
    }

    private void drawNavigation(int mx, int my) {
        int w = sandbox.canvas.layout()
            .width(),
            h = sandbox.canvas.layout()
                .height();
        int x = (w - 476) / 2, y = h - 106;
        int textWidth = Math.min(w - 32, 620);
        centeredLessonText(
            (step + 1) + " / " + steps.size() + " · " + steps.get(step).title,
            w / 2,
            h - 112,
            textWidth,
            0xfff4dcaf);
        List<String> lines = sandbox.host.font()
            .listFormattedStringToWidth(steps.get(step).description, textWidth);
        for (int i = 0; i < Math.min(2, lines.size()); i++)
            centeredLessonText(lines.get(i), w / 2, h - 97 + i * 11, textWidth, 0xffe8c99d);
        String next = queuedNext ? "动作完成后继续" : step + 1 == steps.size() ? "完成教程" : "下一步";
        if (!unavailable.isEmpty()) {
            List<String> reasons = sandbox.host.font()
                .listFormattedStringToWidth(unavailable, textWidth);
            for (int i = 0; i < Math.min(2, reasons.size()); i++)
                centeredLessonText(reasons.get(i), w / 2, h - 74 + i * 11, textWidth, 0xfff0b888);
        }
        sandbox.canvas.button(BASE + 1, x + 10, y + 72, 107, 23, "上一步", step > 0, mx, my);
        sandbox.canvas.button(BASE + 2, x + 126, y + 72, 107, 23, next, true, mx, my);
        sandbox.canvas.button(BASE + 3, x + 242, y + 72, 107, 23, "跳过", true, mx, my);
        sandbox.canvas.button(BASE + 4, x + 358, y + 72, 107, 23, "从头开始", true, mx, my);
        HologramEditorTheme.drawGuidanceFrame(x + 124, y + 70, 111, 27, HologramUiAccent.NEXT, true);
        centeredLessonText(
            queuedNext ? "已排队，当前动作完成后继续" : "请点击下方「" + next + "」；示例控件不可操作",
            w / 2,
            h - 49,
            textWidth,
            0xffffdda4);
        drawNextClickCue(x + 126, y + 72, 107);
    }

    private void centeredLessonText(String text, int center, int y, int width, int color) {
        String visible = sandbox.host.font()
            .trimStringToWidth(text, width);
        sandbox.host.font()
            .drawStringWithShadow(
                visible,
                center - sandbox.host.font()
                    .getStringWidth(visible) / 2,
                y,
                color);
    }

    /** An expanding pixel click halo around Next, with no automatic navigation action. */
    private void drawNextClickCue(int x, int y, int width) {
        if (queuedNext) return;
        int phase = (int) ((System.nanoTime() / 180_000_000L) % 8);
        if (phase < 3 || phase > 6) return;
        int radius = phase - 2, color = phase == 6 ? 0x99ffd692 : 0xffffd692;
        int left = x - radius, right = x + width + radius, top = y - radius, bottom = y + 23 + radius;
        Gui.drawRect(left, top, left + 7, top + 1, color);
        Gui.drawRect(right - 7, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, left + 7, bottom, color);
        Gui.drawRect(right - 7, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, top + 5, color);
        Gui.drawRect(right - 1, top, right, top + 5, color);
        Gui.drawRect(left, bottom - 5, left + 1, bottom, color);
        Gui.drawRect(right - 1, bottom - 5, right, bottom, color);
    }

    private HologramUiCanvas.Control findControl(int id) {
        if (id < 0) return null;
        for (HologramUiCanvas.Control control : sandbox.canvas.controls()) if (control.id == id) return control;
        return null;
    }

    private void apply(int operation, int control) {
        if (operation == 0 && control >= 0) {
            HologramUiCanvas.Control target = findControl(control);
            if (target == null || !target.enabled) return;
        }
        switch (operation) {
            case 1: // Select a real typed cell, with all its candidate/device metadata intact.
                int selected = preferredCell();
                sandbox.preview.selection()
                    .click(selected, false);
                sandbox.preview.selected(selected);
                break;
            case 2:
                String structure = sandbox.config.selectedStructureId();
                if (structure.isEmpty()) structure = sandbox.session.state().data.getString("tutorialStructureId");
                if (!structure.isEmpty()) {
                    sandbox.preview.selection()
                        .structure(sandbox.session.state(), structure, false);
                    sandbox.preview.selected(
                        sandbox.preview.selection()
                            .first());
                } else unavailable = "当前示例没有已识别的来源结构；保留独立选格，不伪造整机成员。";
                break;
            case 3:
                sandbox.preview.moveHandles(true);
                break;
            case 4:
                closePanels();
                break;
            case 5:
                sandbox.library.blueprintTab(true);
                sandbox.library.libraryDrawer(true);
                break;
            case 6:
                sandbox.library.libraryDrawer(false);
                break;
            case 7:
                NBTTagList capabilities = sandbox.config.capabilities();
                for (int i = 0; i < capabilities.tagCount(); i++) {
                    NBTTagCompound capability = capabilities.getCompoundTagAt(i);
                    if (!capability.getBoolean("editable") || capability.getString("id")
                        .equals("_hatch_candidate")) continue;
                    sandbox.config.openOptionControl(2000 + i);
                    sandbox.config.optionValueField()
                        .setText(Integer.toString(Math.max(1, capability.getInteger("current") + 1)));
                    break;
                }
                if (sandbox.config.optionChannel()
                    .isEmpty()) unavailable = "当前示例未声明可编辑能力；不生成通用等级或虚构参数。";
                break;
            case 8:
                if (!sandbox.config.optionChannel()
                    .isEmpty()) sandbox.config.activateControl(68);
                break;
            case 9:
                cornerCount = 1;
                break;
            case 10:
                cornerCount = 2;
                sandbox.preview.selection()
                    .box(sandbox.session.state(), 0, sandbox.session.state().cells.size() - 1, false);
                sandbox.preview.selected(
                    sandbox.preview.selection()
                        .first());
                break;
            case 11:
                cutStage = 1;
                break;
            case 12:
                cutStored = true;
                cutConsumed = false;
                cornerCount = 0;
                cutStage = 2;
                break;
            case 13:
                cutStage = 3;
                break;
            case 14:
                cutStage = 4;
                break;
            case 15:
                cutStored = false;
                cutConsumed = true;
                cutStage = 5;
                break;
            case 16:
                cutStage = 2;
                break;
            case 17:
                sandbox.preview.pitch(Math.toRadians(35));
                sandbox.preview.yaw(Math.toRadians(45));
                break;
            case 18:
                sandbox.preview.pitch(Math.toRadians(12));
                sandbox.preview.yaw(Math.toRadians(25));
                break;
            case 19:
                sandbox.annotations.promptDrawer(true);
                break;
            case 20:
                sandbox.nbt.openNbtEditor(false);
                break;
            case 21:
                sandbox.bindings.networkDrawer(true);
                break;
            case 22:
                sandbox.config.sourceDrawer(true);
                break;
            case 23:
                closePanels();
                materialLesson.show(sandbox.session.state());
                break;
            default:
                // An explicit allowlist prevents local preset/file or global library actions.
                if (control == 60 || control == 62
                    || control == 94
                    || control == 95
                    || control == 96
                    || control == 97
                    || control == 98
                    || control == 99
                    || control == 102
                    || control == 105
                    || control == 106
                    || control == 107
                    || control == 78
                    || control == 88
                    || control == 163
                    || control == 165
                    || control == 1710
                    || control == 1711
                    || control >= 1700 && control <= 1703) sandbox.config.activateControl(control);
                else if (control >= 10000 && control < 14000) sandbox.preview.pinCandidateControl(control);
                else if (control >= 14000 && control < 15000) sandbox.library.selectServerPresetRow(control);
                break;
        }
    }

    private int preferredCell() {
        for (HologramState.Cell cell : sandbox.session.state().cells)
            if (!cell.candidates.isEmpty() && !cell.anchor) return cell.index;
        return sandbox.session.state().cells.isEmpty() ? -1 : 0;
    }

    private void closePanels() {
        materialLesson.close();
        sandbox.config.drawer(false);
        sandbox.config.hatchDrawer(false);
        sandbox.config.presetDrawer(false);
        sandbox.config.sourceDrawer(false);
        sandbox.config.optionChannel("");
        sandbox.preview.inventory(false);
        sandbox.annotations.promptDrawer(false);
        sandbox.nbt.nbtDrawer(false);
        sandbox.bindings.networkDrawer(false);
        sandbox.review.confirmation(false);
    }

    /** The only command receiver for the detached graph. No call to production updateState/send. */
    private void isolatedCommand(String op, NBTTagCompound request) {
        isolatedCommands++;
        NBTTagCompound data = snapshot(sandbox.session.state());
        if (op.equals("selectStructure")) data.setString("activeStructure", request.getString("structureId"));
        else if (op.equals("configure")) {
            if (request.getBoolean("applyHatchPreset") && data.getBoolean("virtual")) {
                sandbox.session.notice("作者蓝图不支持整机仓室预设；此入口用于实体结构的声明配额。");
                return;
            }
            for (String key : new String[] { "main", "channels", "mode", "hatchTier", "hatchDowngrade", "materialMain",
                "materialContainers", "materialMe", "materialPriority" })
                if (request.hasKey(key)) data.setTag(
                    key,
                    request.getTag(key)
                        .copy());
            NBTTagList capabilities = data.getTagList("capabilities", 10);
            for (int i = 0; i < capabilities.tagCount(); i++) {
                NBTTagCompound capability = capabilities.getCompoundTagAt(i);
                String id = capability.getString("id");
                if (id.equals("main")) capability.setInteger("current", data.getInteger("main"));
                else if (data.getCompoundTag("channels")
                    .hasKey(id)) {
                        capability.setInteger(
                            "current",
                            data.getCompoundTag("channels")
                                .getInteger(id));
                        capability.setBoolean("explicit", true);
                    }
            }
            // No invented allocation: automatic devices require the actual server planner and machine declarations.
            if (request.getBoolean("applyHatchPreset")) sandbox.session.notice("此处演示生成入口；真实目标由服务端声明配额与库存分配，教程不伪造结果。");
        } else if (op.equals("pin") || op.equals("hatchFacing") || op.equals("clearPin")) {
            int selected = sandbox.preview.selected();
            NBTTagList cells = data.getTagList("cells", 10);
            if (selected >= 0 && selected < cells.tagCount()) {
                NBTTagCompound cell = cells.getCompoundTagAt(selected);
                if (op.equals("hatchFacing")) cell.setInteger("hatchFacing", request.getInteger("facing"));
                else if (op.equals("pin")) {
                    int choice = request.getInteger("choiceindex");
                    HologramState.Cell original = sandbox.session.state().cells.get(selected);
                    if (choice >= 0 && choice < original.candidates.size()) {
                        net.minecraft.item.ItemStack target = original.candidates.get(choice);
                        NBTTagCompound stack = new NBTTagCompound();
                        target.writeToNBT(stack);
                        cell.setTag("targetStack", stack);
                        cell.setInteger("chosenChoice", choice);
                    }
                } else cell.setInteger("chosenChoice", -1);
            }
        } else if (op.equals("editPreset")) {
            data.setString("virtualPresetName", "隔离示例 · " + request.getString("presetId"));
            // Two independent fixture rows share actual typed devices but have distinct local geometry.
            if (request.getString("presetId")
                .endsWith("1")) {
                NBTTagList rows = data.getTagList("cells", 10);
                for (int i = 0; i < rows.tagCount(); i++) {
                    NBTTagCompound row = rows.getCompoundTagAt(i);
                    row.setInteger("dy", row.getInteger("dy") + 1);
                    row.setInteger("y", row.getInteger("y") + 1);
                }
            }
        }
        // All other commands (including build/save/cut/import/network) terminate here without side effects.
        sandbox.session.isolatedSnapshot(new HologramState(data));
    }

    private void dragGeometry(int operation) {
        if (dragStart == null) return;
        NBTTagCompound data = (NBTTagCompound) dragStart.copy();
        NBTTagList cells = data.getTagList("cells", 10);
        int[] offset = sandbox.preview.gizmo()
            .offset();
        int dx = offset[0], dz = offset[2];
        int[] selected = sandbox.preview.selection()
            .indices();
        for (int index : selected) if (index >= 0 && index < cells.tagCount()) {
            NBTTagCompound original = cells.getCompoundTagAt(index);
            NBTTagCompound moved = operation == 32 ? (NBTTagCompound) original.copy() : original;
            moved.setInteger("x", moved.getInteger("x") + dx);
            moved.setInteger("dx", moved.getInteger("dx") + dx);
            moved.setInteger("z", moved.getInteger("z") + dz);
            moved.setInteger("dz", moved.getInteger("dz") + dz);
            if (operation == 32) {
                String originalId = moved.getString("structureId");
                if (!originalId.isEmpty()) moved.setString("structureId", originalId + "-tutorial-copy");
                NBTTagList memberships = moved.getTagList("structureIds", 8), copies = new NBTTagList();
                for (int member = 0; member < memberships.tagCount(); member++) copies.appendTag(
                    new net.minecraft.nbt.NBTTagString(memberships.getStringTagAt(member) + "-tutorial-copy"));
                moved.setTag("structureIds", copies);
                cells.appendTag(moved);
            }
        }
        if (operation == 32) {
            NBTTagList structures = data.getTagList("structures", 10);
            java.util.Set<Integer> chosen = new java.util.HashSet<>();
            for (int index : selected) chosen.add(index);
            int originals = structures.tagCount();
            for (int i = 0; i < originals; i++) {
                NBTTagCompound copy = (NBTTagCompound) structures.getCompoundTagAt(i)
                    .copy();
                String originalId = copy.getString("id");
                List<Integer> originalMembers = sandbox.session.state().structureMembers.get(originalId);
                if (originalMembers == null || !chosen.containsAll(originalMembers)) continue;
                copy.setString("id", originalId + "-tutorial-copy");
                structures.appendTag(copy);
            }
        }
        sandbox.session.isolatedSnapshot(new HologramState(data));
    }

    private Checkpoint capture() {
        return new Checkpoint(
            snapshot(sandbox.session.state()),
            sandbox.preview.backgroundUiState(),
            sandbox.config.backgroundUiState(),
            sandbox.library.libraryDrawer(),
            sandbox.library.blueprintTab(),
            sandbox.preview.moveHandles(),
            cornerCount,
            cutStage,
            cutStored,
            cutConsumed,
            sandbox.annotations.promptDrawer(),
            sandbox.nbt.nbtDrawer(),
            sandbox.bindings.networkDrawer(),
            materialLesson.visible(),
            pointerX,
            pointerY,
            worldOffsetX,
            worldOffsetZ);
    }

    private void restore(Checkpoint checkpoint) {
        closePanels();
        sandbox.session.isolatedSnapshot(new HologramState(checkpoint.state));
        sandbox.preview.restoreBackgroundUiState(checkpoint.preview);
        sandbox.config.restoreBackgroundUiState(checkpoint.configuration);
        sandbox.library.libraryDrawer(checkpoint.library);
        sandbox.library.blueprintTab(checkpoint.blueprints);
        sandbox.preview.moveHandles(checkpoint.handles);
        cornerCount = checkpoint.corners;
        cutStage = checkpoint.cut;
        cutStored = checkpoint.stored;
        cutConsumed = checkpoint.consumed;
        sandbox.annotations.promptDrawer(checkpoint.prompt);
        if (checkpoint.nbt) sandbox.nbt.openNbtEditor(false);
        sandbox.bindings.networkDrawer(checkpoint.network);
        if (checkpoint.material) materialLesson.show(sandbox.session.state());
        pointerX = checkpoint.pointerX;
        pointerY = checkpoint.pointerY;
        worldOffsetX = checkpoint.worldX;
        worldOffsetZ = checkpoint.worldZ;
    }

    private static NBTTagCompound snapshot(HologramState state) {
        NBTTagCompound data = (NBTTagCompound) state.data.copy();
        NBTTagList cells = new NBTTagList();
        for (HologramState.Cell cell : state.cells) cells.appendTag(cell.tag());
        data.setTag("cells", cells);
        return data;
    }

    private static HologramState sample(HologramState source, String topic) {
        NBTTagCompound data = (NBTTagCompound) source.data.copy();
        data.setString("session", "__gtmb_tutorial__");
        data.setString("title", "隔离教学场景 · 不提交真实操作");
        data.setString("status", "教学数据仅在此会话内存中变化");
        data.setString("activeStructure", "");
        data.setString("openPurpose", "");
        data.setInteger("job", 0);
        data.setLong("uiSequence", 0);
        for (String key : new String[] { "areaImporting", "snapshotPending", "placementValidating", "attachmentPending",
            "capturePending", "areaSelectionShell", "targetClosed", "blueprintAwaitingConfirmation",
            "discardConfirmationRequired", "presetBrowseForPlacement", "virtualPlacement", "blueprintPreview" })
            data.setBoolean(key, false);
        for (String key : new String[] { "discardTargetAction", "textPlacementAck", "processing", "processingPatch",
            "aeReview", "networkBindingNavigation" }) data.removeTag(key);
        data.setBoolean("virtual", topic.equals("blueprint"));
        data.setBoolean("geometryEditable", topic.equals("blueprint"));
        data.setBoolean("supported", source.data.getBoolean("supported"));
        data.setBoolean("open", true);
        data.setBoolean("interactive", true);
        NBTTagList cells = new NBTTagList();
        // Prefer a complete small source structure over truncating a large machine's membership.
        List<Integer> members = null;
        if (source.cells.size() > 256) {
            for (java.util.Map.Entry<String, List<Integer>> entry : source.structureMembers.entrySet())
                if (!entry.getValue()
                    .isEmpty()
                    && entry.getValue()
                        .size() <= 256) {
                            members = entry.getValue();
                            data.setString("tutorialStructureId", entry.getKey());
                            break;
                        }
        }
        java.util.Set<Integer> included = new java.util.HashSet<>();
        if (members != null) {
            for (int index : members) {
                cells.appendTag(
                    source.cells.get(index)
                        .tag());
                included.add(index);
            }
        } else for (int i = 0; i < Math.min(256, source.cells.size()); i++) {
            cells.appendTag(
                source.cells.get(i)
                    .tag());
            included.add(i);
        }
        if (source.cells.size() > 256) {
            NBTTagList structures = data.getTagList("structures", 10), complete = new NBTTagList();
            for (int i = 0; i < structures.tagCount(); i++) {
                NBTTagCompound structure = structures.getCompoundTagAt(i);
                List<Integer> all = source.structureMembers.get(structure.getString("id"));
                if (all != null && included.containsAll(all)) complete.appendTag(structure.copy());
            }
            data.setTag("structures", complete);
            data.setBoolean("tutorialPartialSample", members == null);
        }
        if (cells.tagCount() == 0) for (int x = 0; x < 4; x++) for (int z = 0; z < 3; z++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", x);
            cell.setInteger("y", 64);
            cell.setInteger("z", z);
            cell.setInteger("dx", x);
            cell.setInteger("dy", 0);
            cell.setInteger("dz", z);
            cell.setString("id", "minecraft:stone");
            cell.setString("wantId", "minecraft:stone");
            cell.setString("status", "satisfied");
            cell.setBoolean("inScope", true);
            cells.appendTag(cell);
        }
        data.setTag("cells", cells);
        NBTTagList presets = new NBTTagList();
        for (int i = 0; i < 2; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", "tutorial-sample-" + i);
            row.setString("name", i == 0 ? "教学示例 · 当前结构" : "教学示例 · 独立草稿");
            row.setInteger("cells", cells.tagCount());
            presets.appendTag(row);
        }
        data.setTag("presets", presets);
        data.setString("virtualPresetName", "教学示例");
        return new HologramState(data);
    }

    private void drawLessonStage() {
        if (!(topic.equals("cut") || topic.startsWith("cut-") || topic.startsWith("box-"))) return;
        worldStage.draw(
            sandbox,
            topic.endsWith("iso"),
            cornerCount,
            sandbox.preview.moveHandles(),
            topic.startsWith("cut"),
            cutStage,
            worldOffsetX,
            worldOffsetZ);
        int w = sandbox.canvas.layout()
            .width();
        int x = 20, y = 61;
        sandbox.canvas.skin("panel", x, y, Math.min(370, w - 260), 61);
        sandbox.canvas.text(
            (topic.endsWith("iso") ? "等距" : "普通世界") + "操作预演 · 独立示例",
            x + 7,
            y + 6,
            350,
            HologramEditorTheme.TITLE);
        sandbox.canvas.text(
            "角点 " + cornerCount + "/2" + (cutStored ? " · 私有暂存：教学条目" : cutConsumed ? " · 原条目已消费" : " · 未提交"),
            x + 7,
            y + 21,
            350,
            HologramEditorTheme.TEXT);
        String stage = cutStage == 1 ? "确认来源与名称后保存剪切"
            : cutStage == 2 ? "独立暂存；退出保留未消费条目"
                : cutStage == 3 ? "移动目标预览，点击锁定"
                    : cutStage == 4 ? "目标已锁定，下一次点击打开确认" : cutStage == 5 ? "示例放置完成；原条目消费" : "先定第一角，再定第二角";
        sandbox.canvas.text(stage, x + 7, y + 37, 350, HologramEditorTheme.MUTED);
    }

    private boolean isWorldCourse() {
        return topic.equals("cut") || topic.startsWith("cut-") || topic.startsWith("box-");
    }

    private void add(String title, String description, int focus, Action... actions) {
        steps.add(new Step(title, description, focus, java.util.Arrays.asList(actions)));
    }

    private static Action click(int id) {
        return new Action(0, id, 300, 160, false, 0, 0);
    }

    private static Action local(int operation, int x, int y) {
        return new Action(operation, -1, x, y, false, 0, 0);
    }

    private static Action drag(int operation, int dx, int dy) {
        return new Action(operation, -1, 280, 165, true, dx, dy);
    }

    private void buildCourse(String id) {
        steps.clear();
        if (id.startsWith("box-") || id.startsWith("cut-")) {
            buildWorldCourse(id);
            return;
        }
        if (id.equals("cut")) {
            add("剪切 · 独立私有暂存", "剪切列表与蓝图库分开。教学条目只在内存中，不读取或改动你的真实暂存。", 0, local(12, 220, 150));
            add("选择条目与只读预览", "选择暂存只查看原样数据；不进入蓝图处理、保存库或图文编辑。", 0, local(1, 230, 170));
            add("取出放置与位置锁定", "先取出示例，移动目标再锁定；锁定后再次点击才打开确认。", 0, local(13, 260, 180), local(14, 290, 180));
            add("Esc 保留未消费条目", "取消放置保留暂存；成功放置才消费原条目。撤回原处使用独立撤回流程。", 0, local(16, 260, 160));
            add("返回原工作区", "完成或跳过会恢复真实画面、相机、选区、输入草稿和布局。教学没有生产剪切请求。", 0, local(1, 220, 180));
            return;
        }
        if (id.equals("structure")) {
            buildStructureCourse();
            return;
        }
        if (id.equals("projection")) {
            add("投影 · 控制器锚定", "投影围绕真实控制器规划；示例复制了当前已发布快照，教学不会移动控制器或修改世界。", 0, local(1, 230, 170));
            add("进入来源结构", "点轮廓整选结构，再打开结构编辑；成员配置按来源结构保存，不能改成全图一份等级。", 1, local(2, 240, 160), click(163));
            add(
                "声明参数与具名信道",
                "参数按机器声明显示；主值、线圈或材质等级与仓室电压不同。应用只更新隔离示例。",
                2,
                click(60),
                local(7, 298, 215),
                local(8, 290, 260));
            add(
                "具体设备与候选边界",
                "类型与等级只是筛选；设备目标保留精确 ItemStack/NBT、合法位置和当前朝向。",
                2,
                local(4, 385, 68),
                local(1, 250, 180),
                click(95),
                click(10000));
            add("基础仓室与命名搭配", "实体结构可按声明配额生成预设，保留手选、现场仓室和最低壳体；这不是全机统一电压。", 2, click(96), click(94), click(105));
            add("自定义搭配与撤自动", "搭配保存具体设备及数量；命名、覆盖、删除是本机预设。教程只展示控件，不写入本机搭配文件。", 2, click(1711));
            add(
                "材料来源与真实施工",
                "背包、物品容器、ME 来源各有权限；配置目标后仍须材料核验与唯一施工确认。教程不施工。",
                2,
                click(96),
                local(22, 440, 250),
                local(23, 290, 170));
            add("投影课程完成", "真实工作区一直保留；完成后回到原画面。运行中的施工与后台文件任务始终优先。", 0, local(4, 385, 68));
            return;
        }
        add("第 1 章 · 蓝图库与草稿", "固定右侧库与中央预览共用工作区；客户端与服务器来源独立。此列表使用独立教学行。", 1, local(5, 550, 100));
        add("单点即时预览", "单击一条库记录更新中央草稿；列表保持位置，不反复开关窗口。未保存内容沿用原有切换提示。", 1, click(14001));
        add(
            "第 2 章 · 精确选格",
            "选择一格查看真实候选；Ctrl 增减选择，" + ui.tools.commands()
                .shortcut("sameType") + " 在来源结构内扩展同类，点轮廓整选结构。",
            0,
            local(6, 440, 80),
            local(1, 240, 170));
        add(
            "完整结构与六个手柄",
            ui.tools.commands()
                .shortcut("moveHandles") + " 显示 X/Y/Z 三轴及 XY/XZ/YZ 三平面；相机旋转不会改变世界坐标轴。",
            0,
            local(2, 240, 170),
            local(3, 280, 165));
        add("连续轴向移动", "指针连续移动，模型实时整格吸附；松开后只保留隔离示例中的结果。", 0, drag(30, 110, -12));
        add("双轴平面移动", "拖 XZ 同时改变 X 与 Z，XY/YZ 同理；真实玩家草稿、撤销栈与相机保持原样。", 0, drag(31, -75, 35));
        add("Ctrl 起拖复制", "复制意图在按下时确定；原处保留，副本连续跟随指针。教学副本不发送到服务端。", 0, drag(32, 90, 15));
        add("第 3 章 · 每结构声明参数", "先进入来源成员，再修改其能力参数与具名信道；不同机器不共享一份通用 tier/count。", 2, click(163), click(60));
        add("主值输入与应用", "编辑字段只是草稿；点击应用才提交当前结构。教程分流到独立会话，不触发服务端重采集。", 2, local(7, 298, 215), local(8, 290, 260));
        add(
            "具体设备候选与朝向",
            "保持 ItemStack/NBT、合法候选、默认壳及六面朝向；类型和电压不取代具体设备身份。",
            2,
            local(4, 385, 68),
            local(1, 240, 170),
            click(95),
            click(10000));
        add("作者蓝图与实体仓室预设", "作者蓝图允许逐格目标与成员参数；当前服务端不支持给 virtual 套整机仓室配额。完整入口在实体结构课。", 2, click(96), click(94));
        add("材料来源独立保存", "主背包、背包容器和携带的 ME 终端是三个独立来源；ME 仍需绑定、电量、距离与权限。", 2, click(96), local(22, 440, 250));
        add("第 4 章 · 文字和图片", "空稿生成可直接本地提交；非空稿新增先以 ghost 选位，同 ID 重编辑沿用原图文机制。教程不读本地图片。", 0, local(4, 385, 68));
        add("图文选位与明确确认", "用轴线、平面或六面转向调整图文；未确认的 ghost 与真实草稿分开，退出教程不会遗留对象。", 0, local(3, 280, 165), drag(31, 60, -20));
        add("第 5 章 · 注释与区域", "提示词、提示区、排除区按对象保存；说明文字本身不代表已经执行世界过滤。", 2, local(19, 300, 180));
        add(
            "NBT 与具体设备数据",
            "JSON 可解析不等于设备字段有效；配置材料与设备本体分别核算。教程不执行 NBT 导入或写盘。",
            2,
            local(4, 385, 68),
            local(20, 290, 160));
        add("量子组和网络绑定", "类群、信道和绑定策略独立审阅；预演不连接真实网络，不修改世界网络身份。", 2, local(4, 385, 68), local(21, 290, 160));
        add("第 6 章 · 处理倾向", "自用与分享是处理策略，不是仓室预设。处理前检查来源、结构参数、去标识与网络保留规则。", 1, local(4, 385, 68), local(5, 550, 100));
        add("保存和覆盖边界", "简单另存填写名称后保存；覆盖现有条目需明确选择目标。教学展示入口，不创建蓝图库文件。", 1, local(5, 550, 100));
        add("未保存内容与一次提示", "从库切换编辑来源时保留原 dirty guard；取消保留原稿，放弃当前编辑不删除已保存文件。", 1, local(5, 550, 130));
        add(
            "第 7 章 · 世界位置先锁定",
            "真实放置先关闭编辑器并在世界移动 ghost、锁定位置。这里从已锁定位置解释材料预检，不提供 XYZ 表单。",
            0,
            local(6, 440, 80),
            local(23, 290, 170));
        add("材料核验与唯一确认", "需求、已有、缺失与配置材料都由服务端核对；缺料时按原有规则处理。教程没有库存校验或施工结果。", 1, local(23, 290, 170));
        add("返回蓝图库与完成", "列表、字段、草稿、相机与选区恢复到进入教程前。后台业务一直在真实会话继续。", 1, local(4, 385, 68), local(5, 550, 100));
    }

    private void buildStructureCourse() {
        add(
            "结构操作 · 控制器入口",
            "实体来源锚定控制器；先选轮廓，再进入该来源结构，参数按来源成员独立保存。",
            0,
            local(1, 240, 170),
            local(2, 240, 170),
            click(163));
        add(
            "声明主值与具名信道",
            "沿用机器实际 capability 控件；没有声明的参数不伪造为通用等级。字段输入后应用。",
            2,
            click(60),
            local(7, 298, 215),
            local(8, 290, 260));
        add(
            "逐格具体设备",
            "类型、电压筛选后仍选具体设备；保留候选范围、设备 NBT、默认壳、合法朝向及来源数量。",
            2,
            local(4, 385, 68),
            local(1, 240, 170),
            click(95),
            click(10000));
        add("实体仓室配额与本机搭配", "基础配额或自定义精确组合都保留手选和现场设备。命名、覆盖、删除不等于保存游戏蓝图。", 2, click(96), click(94), click(1711));
        add("撤自动、返回与施工边界", "撤自动预设保留手选和实体仓室；关窗不撤已接受配置。返回后仍须材料核验，作者蓝图不能套整机预设。", 1, click(96), click(165));
    }

    private void buildWorldCourse(String id) {
        boolean iso = id.endsWith("iso"), cut = id.startsWith("cut-");
        add(
            (iso ? "等距" : "普通世界") + (cut ? "剪切" : "框选") + " · 进入模式",
            iso ? "从已启动的框选/剪切上下文按默认 V 进入等距；本课程只动独立教学选区。" : "Shift + 右键打开模式选择，松 Shift 只选择模式；不会代替最终提交。",
            0,
            local(iso ? 17 : 18, 220, 170));
        add("设置第一角", iso ? "按住 Ctrl + 左键设置第一角；角点属于世界坐标，与相机方向无关。" : "左键设置来源第一角；先确定选区，再进行后续确认。", 0, local(9, 210, 170));
        add("设置第二角", iso ? "Ctrl + 右键设置第二角；松 Ctrl 后才能使用提交手势。" : "右键设置第二角；两角围成包含边界的完整体积。", 0, local(10, 330, 200));
        add("手柄与整格调整", "六个面与 XZ 平面按当前编辑器手柄调整；指针连续移动，选区仍按整数世界格吸附。", 0, local(3, 280, 165), drag(31, 75, -30));
        if (!cut) {
            add(
                "提交框选与返回",
                iso ? "松 Ctrl 后 Shift + 左键提交；普通世界对应另一次 Shift + 右键。预演不导入或覆盖真实蓝图。"
                    : "另按 Shift + 右键提交框选；松 Shift 选模式与提交框选是两个动作。预演不执行导入。",
                0,
                local(11, 290, 170));
            return;
        }
        add("来源确认、名称与暂存", "核对两角和来源，命名后明确确认。确认前来源保持原状；教学只创建独立内存条目。", 0, local(11, 280, 170), local(12, 300, 210));
        add(
            "取出、锁定与 Esc",
            iso ? "等距左键锁定，再次左键打开确认；Esc 保留未消费条目，允许重新取出。" : "普通世界右键锁定，再次右键打开确认；Esc 保留未消费条目，允许重新取出。",
            0,
            local(13, 270, 170),
            local(14, 300, 180),
            local(16, 280, 180));
        add(
            "确认放置与消费条目",
            "重新取出并锁定后，在确认窗用左键点击确认。成功放置才消费原条目；未消费条目可独立撤回原处。",
            0,
            local(13, 270, 170),
            local(14, 300, 180),
            local(15, 300, 210));
    }

    private static final class Action {

        final int operation, control, x, y, dx, dy;
        final boolean drag;

        Action(int operation, int control, int x, int y, boolean drag, int dx, int dy) {
            this.operation = operation;
            this.control = control;
            this.x = x;
            this.y = y;
            this.drag = drag;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static final class Step {

        final String title, description;
        final int focus;
        final List<Action> actions;

        Step(String title, String description, int focus, List<Action> actions) {
            this.title = title;
            this.description = description;
            this.focus = focus;
            this.actions = actions;
        }
    }

    private static final class Checkpoint {

        final NBTTagCompound state, preview, configuration;
        final boolean library, blueprints, handles, stored, consumed, prompt, nbt, network, material;
        final int corners, cut, worldX, worldZ;
        final double pointerX, pointerY;

        Checkpoint(NBTTagCompound state, NBTTagCompound preview, NBTTagCompound configuration, boolean library,
            boolean blueprints, boolean handles, int corners, int cut, boolean stored, boolean consumed, boolean prompt,
            boolean nbt, boolean network, boolean material, double pointerX, double pointerY, int worldX, int worldZ) {
            this.state = state;
            this.preview = preview;
            this.configuration = configuration;
            this.library = library;
            this.blueprints = blueprints;
            this.handles = handles;
            this.corners = corners;
            this.cut = cut;
            this.stored = stored;
            this.consumed = consumed;
            this.prompt = prompt;
            this.nbt = nbt;
            this.network = network;
            this.material = material;
            this.pointerX = pointerX;
            this.pointerY = pointerY;
            this.worldX = worldX;
            this.worldZ = worldZ;
        }
    }
}
