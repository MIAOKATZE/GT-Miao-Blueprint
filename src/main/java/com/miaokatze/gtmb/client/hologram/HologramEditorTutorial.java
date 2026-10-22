package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.screen.viewport.TransformationMatrix;
import com.cleanroommc.modularui.widget.WidgetTree;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.miaokatze.gtmb.client.hologram.HologramEditorLessons.Action;
import com.miaokatze.gtmb.client.hologram.HologramEditorLessons.Operation;
import com.miaokatze.gtmb.client.hologram.HologramEditorLessons.Step;

/** Native teaching graphs are separate from the paused real graph; every demonstration command has a local sink. */
final class HologramEditorTutorial implements HologramEditorTutorialOverlay.View, HologramEditorLessonStage.State {

    private static final int BASE = HologramEditorControlIds.TUTORIAL;
    private static final long MOVE_NS = 850_000_000L, PRESS_NS = 180_000_000L, REST_NS = 360_000_000L;
    private static final long HOVER_NS = 1_200_000_000L;
    private final HologramUiModules ui;
    private HologramUiModules sandbox;
    private List<Step> steps = Collections.emptyList();
    private List<Action> actions = Collections.emptyList();
    private final List<NBTTagCompound> checkpoints = new ArrayList<>();
    private String topic = "", requestedTopic = "", lastAutoTopic = "", unavailable = "";
    private String sourceFingerprint = "";
    private int step, actionIndex;
    private long actionStarted, targetWaitingSince;
    private boolean replay, queuedNext, pressed, actionApplied, actionPending;
    private long playbackVersion;
    private double pointerX, pointerY, fromX, fromY, targetX, targetY, motionX, motionY;
    private int[] actionBounds;
    private NBTTagCompound dragStart;
    private NBTTagCompound pendingNativeScroll;
    private boolean pendingMaterialRestore;
    private static final String[] CHECKPOINT_SCROLLS = { "editor_native_tool_rail_list",
        "editor_native_properties_list", "editor_native_selection_list", "editor_native_structure_list",
        "editor_native_materials_list", "blueprint_save_content", "native_blueprint_settings", "library_content",
        "library_rows", "editor_native_view_workspace_list", "config.channels.viewport", "config.options.viewport",
        "config.candidates.viewport", "config.presets.viewport", "config.sources.viewport", "config.channels.rows",
        "config.options.rows", "config.candidates.rows" };
    private int cornerCount, cutStage, worldOffsetX, worldOffsetZ;
    private boolean cutStored, cutConsumed;
    private long isolatedCommands;
    private Runnable completionAction;
    private IPanelHandler sceneHandler, overlayHandler, materialHandler;
    private ModularPanel scenePanel, overlayPanel;
    private HologramEditorTutorialOverlay overlay;
    private final HologramEditorMaterialLesson materialLesson = new HologramEditorMaterialLesson();
    private final HologramEditorLessonStage worldStage = new HologramEditorLessonStage();
    private HologramEditorCutListLesson cutListLesson;

    HologramEditorTutorial(HologramUiModules ui) {
        this.ui = ui;
    }

    private HologramEditorPreferences preferences() {
        return HologramEditorPreferences.get(ui.host.minecraft().mcDataDir);
    }

    @Override
    public boolean active() {
        return sandbox != null;
    }

    boolean blockInput() {
        return active();
    }

    HologramUiModules renderUi() {
        return active() ? sandbox : ui;
    }

    boolean navigationContains(int x, int y, int button) {
        return overlay != null && overlay.navigationContains(x, y, button);
    }

    private String currentTopic() {
        switch (ui.workspaces.mode()) {
            case CUT:
                return "cut";
            case BLUEPRINT:
                return "blueprint";
            default:
                return "projection";
        }
    }

    void tick() {
        if (active()) {
            if (!ui.session.isTutorial() && !ready()) closeWithoutProgress();
            else refreshNativeGraph();
            return;
        }
        if (ui.session.isTutorial()) return;
        if (!requestedTopic.isEmpty()) {
            if (ready()) begin(requestedTopic, replay);
            return;
        }
        String current = currentTopic();
        if (!current.equals(lastAutoTopic) && ready() && !preferences().seen(current)) begin(current, false);
    }

    private boolean ready() {
        NBTTagCompound state = ui.session.state().data;
        return ui.shell.built() && !HologramBackgroundTasks.busy()
            && ui.session.pendingSequence() < 0
            && ui.session.progress() == null
            && ui.session.editable()
            && ui.preview.gizmo()
                .axis() < 0
            && !ui.preview.boxSelecting()
            && !state.getBoolean("snapshotPending")
            && !state.getBoolean("placementValidating")
            && !state.getBoolean("attachmentPending")
            && !ui.library.saveGuidanceOpen()
            && !ui.review.materialDialogOpen()
            && !ui.review.confirmation();
    }

    void start(String topic, boolean replay) {
        if (ui.session.isTutorial() || active()) return;
        this.replay = replay;
        requestedTopic = topic;
        if (ready()) begin(topic, replay);
        else ui.session.notice("当前操作完成后开始教程；后台业务继续。");
    }

    void startDetached(String topic, boolean replay, Runnable returnToBrowser) {
        if (active()) return;
        if (!ui.session.isTutorial()) throw new IllegalStateException("Detached tutorials require an isolated host");
        completionAction = returnToBrowser;
        begin(topic, replay);
    }

    static HologramState detachedState() {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "__gtmb_detached_tutorial_host__");
        data.setInteger("y", 64);
        return new HologramState(data);
    }

    private void begin(String id, boolean replay) {
        requestedTopic = "";
        topic = id;
        this.replay = replay;
        if (id.equals("projection") || id.equals("blueprint") || id.equals("cut")) lastAutoTopic = id;
        try {
            ui.shell.pausePresentation();
            sandbox = new HologramUiModules(ui.host);
            sandbox.session.initializeIsolated(sample(id), this::isolatedCommand);
            sourceFingerprint = HologramCutRawPreviewSession.digest(snapshot(sandbox.session.state()));
            if (worldCourse()) worldStage.initialize(sandbox.session.state());
            try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                .fieldGraph(sandbox)) {
                sandbox.config.initializeFields();
                sandbox.position.initializeFields();
                sandbox.library.initializeFields();
                sandbox.annotations.initializeFields();
                sandbox.bindings.initializeFields();
            }
            sandbox.library.blueprintTab(id.equals("blueprint"));
            sandbox.library.libraryDrawer(id.equals("blueprint"));
            sandbox.preview.yaw(Math.toRadians(38));
            sandbox.preview.pitch(Math.toRadians(25));
            cornerCount = cutStage = worldOffsetX = worldOffsetZ = 0;
            cutStored = cutConsumed = false;
            if (id.equals("cut")) cutListLesson = new HologramEditorCutListLesson();
            isolatedCommands = 0;
            step = actionIndex = 0;
            pointerX = ui.host.screenWidth() / 2d;
            pointerY = ui.host.screenHeight() / 2d;
            steps = HologramEditorLessons.course(id, ui.tools.commands(), sandbox.session.state());
            checkpoints.clear();
            overlay = new HologramEditorTutorialOverlay(sandbox, this);
            sceneHandler = IPanelHandler.simple(
                ui.shell.host() != null ? ui.shell.host()
                    : ui.host.parent()
                        .panel(),
                (parent, player) -> buildScenePanel(),
                true);
            sceneHandler.openPanel();
            overlayHandler = IPanelHandler.simple(scenePanel, (parent, player) -> {
                overlayPanel = overlay.build();
                return overlayPanel;
            }, true);
            overlayHandler.openPanel();
            if (!id.equals("cut")) sandbox.shell.selectDockPage(id.equals("blueprint") ? 2 : 0);
            checkpoints.add(capture());
            playStep();
        } catch (RuntimeException failure) {
            abort(failure);
        }
    }

    private ModularPanel buildScenePanel() {
        scenePanel = new Dialog<Void>(sandbox.panelName("gtmb_tutorial_scene")).setDisablePanelsBelow(false)
            .setCloseOnOutOfBoundsClick(false)
            .size(ui.host.screenWidth(), ui.host.screenHeight())
            .pos(0, 0)
            .background(IDrawable.NONE)
            .disableThemeBackground(true);
        if (worldCourse()) sandbox.shell.sceneOverride(worldStage.nativeWidget(sandbox, this));
        try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
            .fieldGraph(sandbox)) {
            if (cutListLesson != null) {
                sandbox.shell.cutOverride(cutListLesson.prepareWorkspace(sandbox));
                sandbox.workspaces.activateCut();
                if (!sandbox.workspaces.isCut())
                    throw new IllegalStateException("Isolated cut workspace was not admitted");
            }
            scenePanel.child(sandbox.shell.build(scenePanel));
        }
        scenePanel.onUpdateListener(panel -> {
            if (panel.getArea().width != ui.host.screenWidth() || panel.getArea().height != ui.host.screenHeight())
                panel.size(ui.host.screenWidth(), ui.host.screenHeight())
                    .scheduleResize();
        });
        return scenePanel;
    }

    void closeWithoutProgress() {
        HologramUiModules closing = sandbox;
        sandbox = null;
        requestedTopic = "";
        completionAction = null;
        actions = Collections.emptyList();
        steps = Collections.emptyList();
        checkpoints.clear();
        actionIndex = 0;
        actionStarted = targetWaitingSince = 0;
        dragStart = null;
        pendingNativeScroll = null;
        pendingMaterialRestore = false;
        actionBounds = null;
        unavailable = "";
        pressed = queuedNext = actionApplied = actionPending = false;
        playbackVersion++;
        try {
            if (closing != null) cleanup("pointer capture", closing.preview::releasePointerCapture);
            if (cutListLesson != null) {
                cleanup("cut list preview", cutListLesson::close);
                cutListLesson = null;
            }
            cleanup("material panel", materialLesson::close);
            cleanup("native teaching scene", worldStage::close);
            cleanup("tutorial panels", this::closeOwnedPanels);
        } finally {
            try {
                cleanup("real native graph", ui.shell::resumePresentation);
            } finally {
                if (closing != null) {
                    cleanup(
                        "field graph",
                        () -> ui.host.parent()
                            .releaseFieldGraph(closing));
                    cleanup("backdrop texture", HologramEditorTutorialBlur::dispose);
                }
            }
        }
        overlay = null;
    }

    private void closeOwnedPanels() {
        if (scenePanel != null && scenePanel.isValid()) {
            com.cleanroommc.modularui.screen.PanelManager manager = ui.host.parent()
                .getScreen()
                .getPanelManager();
            List<ModularPanel> panels = new ArrayList<>(manager.getOpenPanels());
            for (ModularPanel panel : panels) if (manager.isSubPanelOf(panel, scenePanel))
                cleanup("sample panel " + panel.getName(), panel::closeIfOpen);
        }
        if (overlayHandler != null) cleanup("overlay handler", () -> {
            overlayHandler.closePanel();
            overlayHandler.deleteCachedPanel();
        });
        if (materialHandler != null) cleanup("material handler", () -> {
            materialHandler.closePanel();
            materialHandler.deleteCachedPanel();
        });
        if (sceneHandler != null) cleanup("scene handler", () -> {
            sceneHandler.closePanel();
            sceneHandler.deleteCachedPanel();
        });
        overlayHandler = materialHandler = sceneHandler = null;
        scenePanel = overlayPanel = null;
    }

    private void cleanup(String part, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Tutorial cleanup failed at " + part, failure);
        }
    }

    private void abort(RuntimeException failure) {
        Runnable returnToBrowser = completionAction;
        closeWithoutProgress();
        try {
            ui.session.notice("教程已返回原工作区：" + (failure.getMessage() == null ? "演示无法继续" : failure.getMessage()));
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Native tutorial aborted", failure);
        } finally {
            if (returnToBrowser != null) ui.host.parent()
                .deferAction(returnToBrowser);
        }
    }

    private void finish(boolean completed) {
        String completedTopic = topic;
        boolean record = !replay;
        Runnable returnToBrowser = completionAction;
        closeWithoutProgress();
        try {
            if (record) cleanup("tutorial preference", () -> preferences().finish(completedTopic, completed));
        } finally {
            if (returnToBrowser != null) ui.host.parent()
                .deferAction(returnToBrowser);
        }
    }

    boolean activate(int id) {
        if (!active()) {
            if (id != BASE) return false;
            start(currentTopic(), true);
            return true;
        }
        try {
            if (id == BASE + 1 && step > 0) {
                step--;
                restore(checkpoints.get(step));
                playStep();
            } else if (id == BASE + 2) {
                if (actionIndex < actions.size()) queuedNext = true;
                else nextStep();
            } else if (id == BASE + 3) finish(false);
            else if (id == BASE + 4) {
                step = 0;
                restore(checkpoints.get(0));
                playStep();
            }
        } catch (RuntimeException failure) {
            abort(failure);
        }
        return true;
    }

    @Override
    public void navigate(int offset) {
        activate(BASE + offset);
    }

    @Override
    public boolean canPrevious() {
        return active() && step > 0;
    }

    @Override
    public boolean queuedNext() {
        return queuedNext;
    }

    @Override
    public String nextLabel() {
        return queuedNext ? "已排队" : step + 1 == steps.size() ? "完成教程" : "下一步";
    }

    private void playStep() {
        actions = steps.get(step).actions;
        actionIndex = 0;
        actionStarted = targetWaitingSince = 0;
        pressed = actionApplied = queuedNext = actionPending = false;
        playbackVersion++;
        actionBounds = null;
        dragStart = null;
        unavailable = sandbox.session.state().data.getBoolean("tutorialPartialSample") ? "当前大图只取有界局部；不把它当作完整机器。" : "";
    }

    private void nextStep() {
        queuedNext = false;
        if (step + 1 >= steps.size()) {
            finish(true);
            return;
        }
        step++;
        NBTTagCompound checkpoint = capture();
        if (checkpoints.size() > step) checkpoints.set(step, checkpoint);
        else checkpoints.add(checkpoint);
        while (checkpoints.size() > step + 1) checkpoints.remove(checkpoints.size() - 1);
        playStep();
    }

    private void refreshNativeGraph() {
        if (!active()) return;
        try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
            .fieldGraph(sandbox)) {
            sandbox.shell.tick();
        }
        if (overlayPanel != null && overlayPanel.isOpen()) ui.host.parent()
            .getScreen()
            .getPanelManager()
            .pushToTop(overlayPanel);
    }

    @Override
    public void frame(long now) {
        if (!active()) return;
        try {
            if (pendingMaterialRestore) {
                if (scenePanel.getContext()
                    .getScreen()
                    .getPanelManager()
                    .isPanelOpen(sandbox.panelName(HologramEditorMaterialLesson.PANEL_NAME))) return;
                if (!actionPending) {
                    final long version = playbackVersion;
                    actionPending = ui.host.parent()
                        .tryDeferAction(() -> {
                            if (!active() || playbackVersion != version) return;
                            try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                                .fieldGraph(sandbox)) {
                                apply(newMaterialAction());
                                pendingMaterialRestore = false;
                                actionPending = false;
                            } catch (RuntimeException failure) {
                                abort(failure);
                            }
                        });
                }
                return;
            }
            // The native graph has now laid out its restored page/groups; clamp against its actual current scroll area.
            if (pendingNativeScroll != null) {
                if (restoreNativeScroll(pendingNativeScroll)) pendingNativeScroll = null;
                return;
            }
            if (actionIndex >= actions.size()) {
                if (queuedNext && ui.host.parent()
                    .tryDeferAction(() -> { if (active()) nextStep(); })) queuedNext = false;
                return;
            }
            animate(now);
        } catch (RuntimeException failure) {
            abort(failure);
        }
    }

    /** Compatibility entry; all rendering now belongs to native scene and overlay widget trees. */
    void drawScreen(int mouseX, int mouseY, float ticks) {}

    private void animate(long now) {
        if (!worldCourse() && !unavailable.isEmpty()) {
            // An unavailable prerequisite must not demonstrate dependent controls behind its still-open window.
            actionIndex = actions.size();
            actionBounds = null;
            pressed = queuedNext = false;
            return;
        }
        Action action = actions.get(actionIndex);
        if (actionStarted == 0) {
            if (groupSatisfied(action)) {
                actionIndex++;
                actionBounds = null;
                return;
            }
            int[] region = target(action);
            if (region == null || action.drag() && action.operation != Operation.CUT_LIST_ORBIT
                && !worldCourse()
                && !sandbox.preview.canMoveSelection()) {
                if (targetWaitingSince == 0) targetWaitingSince = now;
                if (now - targetWaitingSince < 650_000_000L) return;
                if (action.operation == Operation.HOVER) {
                    if (!actionPending) {
                        final long version = playbackVersion;
                        actionPending = ui.host.parent()
                            .tryDeferAction(
                                () -> { if (active() && playbackVersion == version) omitUnavailableHoverStep(); });
                    }
                    return;
                }
                unavailable = "能力或选区前提未满足";
                actionIndex++;
                targetWaitingSince = 0;
                actionBounds = null;
                return;
            }
            targetWaitingSince = 0;
            actionBounds = region;
            fromX = pointerX;
            fromY = pointerY;
            targetX = region[0] + region[2] / 2d;
            targetY = region[1] + region[3] / 2d;
            motionX = motionY = 0;
            if (action.drag()) prepareDrag(action);
            actionApplied = false;
            actionStarted = now;
        }
        long elapsed = Math.max(0, now - actionStarted);
        double p = Math.min(1, elapsed / (double) MOVE_NS), smooth = p * p * (3 - 2 * p);
        pointerX = fromX + (targetX - fromX) * smooth;
        pointerY = fromY + (targetY - fromY) * smooth;
        boolean hover = action.hover();
        pressed = !hover && elapsed >= MOVE_NS && elapsed < MOVE_NS + PRESS_NS + (action.drag() ? MOVE_NS : 0);
        if (hover && elapsed >= MOVE_NS) actionApplied = true;
        if (!hover && elapsed >= MOVE_NS && !actionApplied && !actionPending) {
            final long version = playbackVersion;
            final int index = actionIndex;
            actionPending = ui.host.parent()
                .tryDeferAction(() -> {
                    if (!active() || playbackVersion != version || actionIndex != index) return;
                    try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                        .fieldGraph(sandbox)) {
                        if (action.drag()) beginDrag(action);
                        else apply(action);
                        actionApplied = true;
                        actionPending = false;
                    } catch (RuntimeException failure) {
                        abort(failure);
                    }
                });
        }
        if (action.drag() && actionApplied) {
            double drag = Math.max(0, Math.min(1, (elapsed - MOVE_NS) / (double) MOVE_NS));
            pointerX = targetX + motionX * drag;
            pointerY = targetY + motionY * drag;
            if (action.operation == Operation.CUT_LIST_ORBIT) {
                cutListLesson.orbit((float) (motionX * drag), (float) (motionY * drag));
            } else if (worldCourse()) {
                worldOffsetX = (int) Math.round(3 * drag);
                worldOffsetZ = (int) Math.round(2 * drag);
                worldStage.geometry.expand(worldOffsetX, worldOffsetZ);
            } else {
                int[] local = sandbox.shell.sceneLocalPoint((int) Math.round(pointerX), (int) Math.round(pointerY));
                if (local != null) sandbox.preview.gizmo()
                    .update(local[0], local[1]);
            }
        }
        long hold = hover ? HOVER_NS : PRESS_NS + REST_NS + (action.drag() ? MOVE_NS : 0);
        if (actionApplied && elapsed >= MOVE_NS + hold) {
            if (action.drag() && action.operation != Operation.CUT_LIST_ORBIT && !worldCourse()) commitDrag();
            if (action.operation != Operation.CLOSE_PANEL || sandbox.shell.targetBounds("modal") == null) actionIndex++;
            actionStarted = 0;
            pressed = false;
            dragStart = null;
            actionBounds = null;
        }
    }

    private int[] target(Action action) {
        if (cutListLesson != null) {
            if (action.operation == Operation.CUT_LIST_ORBIT) {
                int[] bounds = sandbox.shell.targetBounds(action.target);
                return bounds == null ? null
                    : around(new int[] { bounds[0] + bounds[2] / 2, bounds[1] + bounds[3] / 2 });
            }
            if (action.operation == Operation.HOVER) return sandbox.shell.targetBounds(action.target);
        }
        if (action.operation == Operation.HOVER || action.operation == Operation.OPEN_TOOL)
            return hoverToolBounds(action.target);
        if (action.operation == Operation.HANDLES) return hoverToolBounds("moveHandles");
        String nativeTarget = action.operation == Operation.OPEN_CAPABILITY ? capabilityTarget()
            : action.control >= 0 ? "control." + resolvedControlId(action) : action.target;
        if (nativeTarget.isEmpty()) return null;
        if (sandbox.shell.tutorialTargetClipped(nativeTarget)) {
            final long version = playbackVersion;
            ui.host.parent()
                .tryDeferAction(
                    () -> {
                        if (active() && playbackVersion == version) sandbox.shell.revealTutorialTarget(nativeTarget);
                    });
            return null;
        }
        if (action.operation == Operation.HOVER_NATIVE) return sandbox.shell.presentationBounds(nativeTarget);
        if (action.operation == Operation.OPEN_CAPABILITY || action.operation == Operation.TYPE_CAPABILITY
            || action.operation == Operation.SELECT_CANDIDATE) return sandbox.shell.targetBounds(nativeTarget);
        if (action.control >= 0) return sandbox.shell.controlBounds(resolvedControlId(action));
        if (action.operation == Operation.APPLY_CAPABILITY) return sandbox.shell.controlBounds(68);
        if (worldCourse() && (action.operation == Operation.FIRST_CORNER || action.operation == Operation.SECOND_CORNER
            || action.operation == Operation.WORLD_MOVE_XZ)) {
            double[] point = worldStage.cornerPoint(action.operation != Operation.FIRST_CORNER);
            if (point != null) return around(point);
        }
        if (action.drag()) {
            HologramGizmo gizmo = sandbox.preview.gizmo();
            if (action.operation == Operation.MOVE_XZ) {
                double[] middle = new double[2];
                for (double[] point : gizmo.plane(HologramGizmo.XZ)) {
                    middle[0] += point[0] / 4;
                    middle[1] += point[1] / 4;
                }
                return around(sandbox.shell.sceneToScreen((int) Math.round(middle[0]), (int) Math.round(middle[1])));
            }
            double[] point = gizmo.end(0);
            return around(sandbox.shell.sceneToScreen((int) Math.round(point[0]), (int) Math.round(point[1])));
        }
        if (action.operation == Operation.SELECT_CELL || action.operation == Operation.SELECT_STRUCTURE) {
            int index = preferredCell();
            if (index < 0) return null;
            HologramState.Cell cell = sandbox.session.state().cells.get(index);
            if (worldCourse()) return around(worldStage.point(cell.dx + .5, cell.dy + .5, cell.dz + .5));
            double[] point = HologramRenderer.project(cell.dx + .5, cell.dy + .5, cell.dz + .5);
            return around(sandbox.shell.sceneToScreen((int) Math.round(point[0]), (int) Math.round(point[1])));
        }
        return sandbox.shell.targetBounds(action.target);
    }

    private int[] hoverToolBounds(String command) {
        int index = sandbox.tools.commands()
            .index(command);
        if (index < 0) return null;
        IWidget button = WidgetTree.findFirstWithNameNullable(
            sandbox.shell.root(),
            "editor_native_tool_" + (HologramEditorControlIds.TOOLS + index));
        ListWidget<?, ?> rail = WidgetTree
            .findFirstWithNameNullable(sandbox.shell.root(), "editor_native_tool_rail_list", ListWidget.class);
        if (button == null || rail == null || !button.isValid() || !rail.isValid()) return null;
        int[] bounds = sandbox.shell.nativeWidgetBounds(button);
        int[] footer = overlay.footerBounds();
        if (bounds != null && bounds[3] >= button.getArea().height
            && (footer == null || bounds[1] + bounds[3] < footer[1])) return bounds;
        if (targetWaitingSince != 0 && System.nanoTime() - targetWaitingSince >= 650_000_000L) return null;
        final long version = playbackVersion;
        ui.host.parent()
            .tryDeferAction(() -> {
                if (!active() || playbackVersion != version || !button.isValid() || !rail.isValid()) return;
                int[] viewport = sandbox.shell.nativeWidgetBounds(rail);
                if (viewport == null) return;
                TransformationMatrix matrix = LocatedWidget.of(button)
                    .getTransformationMatrix();
                int top = Math.round(matrix.transformY(0, 0));
                int bottom = Math.round(matrix.transformY(button.getArea().width, button.getArea().height));
                int visibleBottom = viewport[1] + viewport[3];
                int[] currentFooter = overlay.footerBounds();
                if (currentFooter != null) visibleBottom = Math.min(visibleBottom, currentFooter[1] - 6);
                int delta = top < viewport[1] ? top - viewport[1] : Math.max(0, bottom - visibleBottom);
                if (delta != 0 && rail.getScrollData() != null) rail.getScrollData()
                    .scrollTo(
                        rail.getScrollArea(),
                        rail.getScrollData()
                            .getScroll() + delta);
            });
        return null;
    }

    private String capabilityTarget() {
        NBTTagList caps = sandbox.config.capabilities();
        int offset = sandbox.config.drawerOffset();
        for (int slot = 0; slot < 4 && offset + slot < caps.tagCount(); slot++) {
            NBTTagCompound cap = caps.getCompoundTagAt(offset + slot);
            if (cap.getBoolean("editable") && !"_hatch_candidate".equals(cap.getString("id")))
                return "config.capability.row." + slot;
        }
        return "";
    }

    private void typeCapabilityValue() {
        HologramMuiTextField field = sandbox.config.optionValueField();
        if (field == null || !sandbox.shell.activateTarget("config.option.value")
            || !field.widget()
                .isFocused()) {
            unavailable = "原生输入框尚未取得焦点，未修改参数。";
            return;
        }
        NBTTagCompound cap = sandbox.config.capability(sandbox.config.optionChannel());
        if (cap == null || !cap.getBoolean("editable")) {
            unavailable = "此能力当前不可编辑，保留原值。";
            return;
        }
        String value = Long.toString(Math.max(1, Math.min(Integer.MAX_VALUE, (long) cap.getInteger("current") + 1)));
        field.setCursorPositionEnd();
        field.setSelectionPos(0);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            field.widget()
                .onKeyPressed(character, org.lwjgl.input.Keyboard.KEY_NONE);
            // lwjgl3ify delivers printable characters through its separate native text event.
            if (com.cleanroommc.modularui.ModularUI.Mods.LWJGL3IFY.isLoaded()) field.widget()
                .onTextInput(new me.eigenraven.lwjgl3ify.api.InputEvents.TextEvent(String.valueOf(character)));
        }
        if (!value.equals(field.getText())) unavailable = "输入框未接受该值，保留原生校验结果。";
    }

    /** A clipped or unavailable tool is omitted rather than pointed at an unrelated viewport region. */
    private void omitUnavailableHoverStep() {
        List<Step> visibleSteps = new ArrayList<>(steps);
        visibleSteps.remove(step);
        steps = Collections.unmodifiableList(visibleSteps);
        while (checkpoints.size() > step) checkpoints.remove(checkpoints.size() - 1);
        if (step >= steps.size()) {
            finish(true);
            return;
        }
        checkpoints.add(capture());
        playStep();
    }

    private static int[] around(double[] point) {
        return point == null ? null
            : new int[] { (int) Math.round(point[0]) - 5, (int) Math.round(point[1]) - 5, 10, 10 };
    }

    private static int[] around(int[] point) {
        return point == null ? null : new int[] { point[0] - 5, point[1] - 5, 10, 10 };
    }

    private void prepareDrag(Action action) {
        if (action.operation == Operation.CUT_LIST_ORBIT) {
            int[] bounds = sandbox.shell.targetBounds(action.target);
            motionX = bounds == null ? 0 : Math.min(48, bounds[2] * .25);
            motionY = bounds == null ? 0 : -Math.min(20, bounds[3] * .15);
            return;
        }
        if (worldCourse()) {
            int[] end = worldStage.geometry.corner(true);
            double[] point = worldStage.point(end[0] + 3.5, end[1] + .5, end[2] + 2.5);
            if (point != null) {
                motionX = point[0] - targetX;
                motionY = point[1] - targetY;
            }
        } else {
            double[] from = HologramRenderer.project(0, 0, 0),
                to = HologramRenderer.project(3, 0, action.operation == Operation.MOVE_XZ ? 2 : 0);
            int[] screenFrom = sandbox.shell.sceneToScreen((int) Math.round(from[0]), (int) Math.round(from[1]));
            int[] screenTo = sandbox.shell.sceneToScreen((int) Math.round(to[0]), (int) Math.round(to[1]));
            if (screenFrom != null && screenTo != null) {
                motionX = screenTo[0] - screenFrom[0];
                motionY = screenTo[1] - screenFrom[1];
            }
        }
    }

    private void beginDrag(Action action) {
        if (action.operation == Operation.CUT_LIST_ORBIT) {
            cutListLesson.beginOrbit();
            return;
        }
        if (worldCourse()) return;
        if (action.operation == Operation.COPY_X) cloneSelection();
        dragStart = snapshot(sandbox.session.state());
        int[] local = sandbox.shell.sceneLocalPoint((int) Math.round(targetX), (int) Math.round(targetY));
        if (local == null) return;
        sandbox.preview.gizmo()
            .begin(action.operation == Operation.MOVE_XZ ? HologramGizmo.XZ : 0, local[0], local[1]);
    }

    private void commitDrag() {
        if (dragStart == null) return;
        NBTTagCompound data = (NBTTagCompound) dragStart.copy();
        int[] offset = sandbox.preview.gizmo()
            .offset();
        java.util.Set<String> moved = completeSources(
            sandbox.session.state(),
            sandbox.preview.selection()
                .indices());
        NBTTagList cells = data.getTagList("cells", 10);
        for (int index : sandbox.preview.selection()
            .indices()) if (index >= 0 && index < cells.tagCount()) {
                NBTTagCompound cell = cells.getCompoundTagAt(index);
                for (int axis = 0; axis < 3; axis++) {
                    String key = new String[] { "x", "y", "z" }[axis];
                    cell.setInteger(key, cell.getInteger(key) + offset[axis]);
                    cell.setInteger("d" + key, cell.getInteger("d" + key) + offset[axis]);
                }
            }
        NBTTagList structures = data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            NBTTagCompound structure = structures.getCompoundTagAt(i);
            if (moved.contains(structure.getString("id"))) for (int axis = 0; axis < 3; axis++) {
                String key = new String[] { "x", "y", "z" }[axis];
                structure.setInteger(key, structure.getInteger(key) + offset[axis]);
            }
        }
        sandbox.session.isolatedSnapshot(new HologramState(data));
        sandbox.preview.gizmo()
            .cancel();
    }

    private static java.util.Set<String> completeSources(HologramState state, int[] selected) {
        java.util.Set<Integer> chosen = new java.util.HashSet<>();
        for (int index : selected) if (index >= 0 && index < state.cells.size()) chosen.add(index);
        java.util.Set<String> complete = new java.util.HashSet<>();
        state.structureMembers
            .forEach((id, members) -> { if (!members.isEmpty() && chosen.containsAll(members)) complete.add(id); });
        return complete;
    }

    private void cloneSelection() {
        HologramState state = sandbox.session.state();
        NBTTagCompound data = snapshot(state);
        NBTTagList cells = data.getTagList("cells", 10), structures = data.getTagList("structures", 10);
        int[] selected = sandbox.preview.selection()
            .indices(), copied = new int[selected.length];
        java.util.Set<String> complete = completeSources(state, selected), known = new java.util.HashSet<>();
        java.util.Map<String, String> copies = new java.util.HashMap<>();
        for (int i = 0; i < structures.tagCount(); i++) known.add(
            structures.getCompoundTagAt(i)
                .getString("id"));
        int count = structures.tagCount();
        NBTTagCompound cached = data.getCompoundTag("tutorialMemberMetadata");
        String active = data.getString("activeStructure");
        if (!active.isEmpty()) cached.setTag(active, sourceMetadata(data));
        for (int i = 0; i < count; i++) {
            NBTTagCompound original = structures.getCompoundTagAt(i);
            String id = original.getString("id");
            if (!complete.contains(id)) continue;
            String copyId = id + "-tutorial-copy";
            if (!known.add(copyId)) throw new IllegalStateException("Duplicate tutorial source copy");
            NBTTagCompound copy = (NBTTagCompound) original.copy();
            copy.setString("id", copyId);
            copy.setString(
                "tutorialObservedSourceId",
                original.hasKey("tutorialObservedSourceId", 8) ? original.getString("tutorialObservedSourceId") : id);
            structures.appendTag(copy);
            copies.put(id, copyId);
            if (cached.hasKey(id, 10)) cached.setTag(
                copyId,
                cached.getCompoundTag(id)
                    .copy());
        }
        data.setTag("tutorialMemberMetadata", cached);
        for (int i = 0; i < selected.length; i++) {
            NBTTagCompound copy = (NBTTagCompound) cells.getCompoundTagAt(selected[i])
                .copy();
            HologramState.Cell original = state.cells.get(selected[i]);
            NBTTagList memberships = new NBTTagList();
            for (String id : original.structureIds)
                if (copies.containsKey(id)) memberships.appendTag(new NBTTagString(copies.get(id)));
            String primary = copies.get(original.structureId);
            copy.setString(
                "structureId",
                primary != null ? primary : memberships.tagCount() > 0 ? memberships.getStringTagAt(0) : "");
            copy.setTag("structureIds", memberships);
            copied[i] = cells.tagCount();
            cells.appendTag(copy);
        }
        sandbox.session.isolatedSnapshot(new HologramState(data));
        sandbox.preview.selection()
            .load(copied, -1, cells.tagCount());
        sandbox.preview.selected(
            sandbox.preview.selection()
                .first());
    }

    private int resolvedControlId(Action action) {
        return action.control == 220 && !sandbox.shell.controlEnabled(220) && sandbox.shell.controlEnabled(297) ? 297
            : action.control;
    }

    private void apply(Action action) {
        switch (action.operation) {
            case CUT_LIST_CLICK:
                if (!sandbox.shell.activateTarget(action.target)) unavailable = "此列表控件当前不可用；未执行替代操作。";
                break;
            case CONTROL:
                int id = resolvedControlId(action);
                if (sandbox.shell.controlEnabled(id)) sandbox.shell.activateTarget("control." + id);
                break;
            case OPEN_TOOL:
                sandbox.shell.activateTarget(
                    "control." + (HologramEditorControlIds.TOOLS + sandbox.tools.commands()
                        .index(action.target)));
                break;
            case OPEN_CAPABILITY:
                sandbox.shell.activateTarget(capabilityTarget());
                break;
            case SELECT_CANDIDATE:
                sandbox.shell.activateTarget(action.target);
                break;
            case SELECT_CELL:
                int index = preferredCell();
                sandbox.preview.selection()
                    .click(index, false);
                sandbox.preview.selected(index);
                break;
            case SELECT_STRUCTURE:
                String structure = sandbox.config.selectedStructureId();
                if (structure.isEmpty()) structure = sandbox.session.state().data.getString("tutorialStructureId");
                if (structure.isEmpty()) {
                    unavailable = "来源结构前提未满足";
                    break;
                }
                sandbox.preview.selection()
                    .structure(sandbox.session.state(), structure, false);
                sandbox.preview.selected(
                    sandbox.preview.selection()
                        .first());
                break;
            case HANDLES:
                sandbox.shell.activateTarget(
                    "control." + (HologramEditorControlIds.TOOLS + sandbox.tools.commands()
                        .index("moveHandles")));
                break;
            case CLOSE_PANEL:
                sandbox.shell.activateTarget("modal.close");
                break;
            case CLOSE_MATERIAL:
                sandbox.shell.activateTarget(action.target);
                break;
            case LIBRARY:
            case EDITOR:
            case SETTINGS:
            case OPEN_DOCK:
                if (!groupSatisfied(action) && !sandbox.shell.activateTarget(action.target))
                    unavailable = "工作台入口当前不可见或不可用；保留原页面。";
                break;
            case CLOSE_TARGET:
            case OPEN_TARGET:
            case CLOSE_SOURCE:
            case OPEN_SOURCE:
            case OPEN_MATERIAL:
                if (!groupSatisfied(action)) sandbox.shell.activateTarget(action.target);
                break;
            case TYPE_CAPABILITY:
                typeCapabilityValue();
                break;
            case APPLY_CAPABILITY:
                if (!sandbox.config.optionChannel()
                    .isEmpty()) sandbox.shell.activateTarget("control.68");
                break;
            case PROMPT:
                sandbox.shell.activateTarget(action.target);
                break;
            case MATERIALS:
                closeMaterialLesson();
                materialLesson.show(sandbox.session.state());
                if (materialHandler == null) materialHandler = IPanelHandler
                    .simple(scenePanel, (parent, player) -> materialLesson.build(sandbox), true);
                materialHandler.openPanel();
                break;
            case WORLD_MODE:
                break;
            case FIRST_CORNER:
                worldStage.geometry.choose(false);
                cornerCount = worldStage.geometry.corners();
                break;
            case SECOND_CORNER:
                worldStage.geometry.choose(true);
                cornerCount = worldStage.geometry.corners();
                break;
            case BOX_SUBMIT:
                worldStage.geometry.submit();
                sandbox.session.notice("已读取教学选区内 " + worldStage.geometry.count() + " 格电力高炉规划；仅保留于教学内存。");
                break;
            case CUT_SOURCE_REVIEW:
                worldStage.geometry.requireSelection();
                cutStage = 1;
                break;
            case CUT_STORE:
                worldStage.geometry.store();
                cutStored = worldStage.geometry.stored();
                cutConsumed = worldStage.geometry.consumed();
                cornerCount = worldStage.geometry.corners();
                cutStage = 2;
                break;
            case CUT_TAKE:
                worldStage.geometry.take();
                cutStage = 3;
                break;
            case CUT_LOCK:
                worldStage.geometry.lock();
                cutStage = 4;
                break;
            case CUT_CANCEL:
                worldStage.geometry.cancel();
                cutStage = 2;
                break;
            case CUT_PLACE:
                worldStage.geometry.place();
                cutStored = worldStage.geometry.stored();
                cutConsumed = worldStage.geometry.consumed();
                cutStage = 5;
                break;
            default:
                break;
        }
    }

    private void closeMaterialLesson() {
        materialLesson.close();
        if (materialHandler != null) {
            materialHandler.closePanel();
            materialHandler.deleteCachedPanel();
        }
    }

    private void closePanels() {
        closeMaterialLesson();
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
        if (sandbox.processing.processingFlow() != null) sandbox.processing.activateProcessing(234);
    }

    private NBTTagCompound capture() {
        NBTTagCompound result = new NBTTagCompound();
        if (worldCourse()) result.setTag("teachingGeometry", worldStage.geometry.snapshot());
        if (cutListLesson != null) result.setTag("cutList", cutListLesson.snapshot());
        result.setTag("state", snapshot(sandbox.session.state()));
        result.setTag("preview", sandbox.preview.backgroundUiState());
        result.setTag("config", sandbox.config.backgroundUiState());
        result.setTag("library", sandbox.library.backgroundUiState());
        result.setTag("processing", sandbox.processing.backgroundUiState());
        result.setBoolean("viewWorkspace", sandbox.tools.viewWorkspaceOpen());
        NBTTagCompound presentation = new NBTTagCompound();
        presentation.setInteger("dockPage", sandbox.shell.dockPage());
        presentation.setBoolean("dockExpanded", sandbox.shell.dockExpanded());
        for (String name : CHECKPOINT_SCROLLS) {
            ListWidget<?, ?> list = checkpointList(name);
            if (list != null && list.isValid() && list.getScrollData() != null) presentation.setInteger(
                name,
                list.getScrollData()
                    .getScroll());
        }
        result.setTag("nativePresentation", presentation);
        result.setBoolean("targetExpanded", sandbox.shell.propertiesGroupExpanded("target"));
        result.setBoolean("sourceExpanded", sandbox.shell.propertiesGroupExpanded("source"));
        result.setBoolean("materialExpanded", sandbox.shell.propertiesGroupExpanded("material"));
        result.setBoolean("handles", sandbox.preview.moveHandles());
        result.setBoolean("prompt", sandbox.annotations.promptDrawer());
        result.setBoolean("nbt", sandbox.nbt.nbtDrawer());
        result.setBoolean("network", sandbox.bindings.networkDrawer());
        result.setBoolean("material", materialLesson.visible());
        result.setInteger("corners", cornerCount);
        result.setInteger("cutStage", cutStage);
        result.setBoolean("stored", cutStored);
        result.setBoolean("consumed", cutConsumed);
        result.setInteger("worldX", worldOffsetX);
        result.setInteger("worldZ", worldOffsetZ);
        result.setDouble("pointerX", pointerX);
        result.setDouble("pointerY", pointerY);
        return result;
    }

    private void restore(NBTTagCompound checkpoint) {
        if (worldCourse() && checkpoint.hasKey("teachingGeometry", 10))
            worldStage.geometry.restore(checkpoint.getCompoundTag("teachingGeometry"));
        if (cutListLesson != null) cutListLesson.restore(checkpoint.getCompoundTag("cutList"));
        try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
            .fieldGraph(sandbox)) {
            closePanels();
            sandbox.session.isolatedSnapshot(new HologramState(checkpoint.getCompoundTag("state")));
            sandbox.preview.restoreBackgroundUiState(checkpoint.getCompoundTag("preview"));
            sandbox.config.restoreBackgroundUiState(checkpoint.getCompoundTag("config"));
            sandbox.library.restoreBackgroundUiState(checkpoint.getCompoundTag("library"));
            sandbox.processing.restoreBackgroundUiState(checkpoint.getCompoundTag("processing"));
            sandbox.preview.moveHandles(checkpoint.getBoolean("handles"));
            sandbox.annotations.promptDrawer(checkpoint.getBoolean("prompt"));
            if (checkpoint.getBoolean("nbt")) sandbox.nbt.openNbtEditor(false);
            sandbox.bindings.networkDrawer(checkpoint.getBoolean("network"));
            pendingMaterialRestore = checkpoint.getBoolean("material");
        }
        cornerCount = checkpoint.getInteger("corners");
        cutStage = checkpoint.getInteger("cutStage");
        cutStored = checkpoint.getBoolean("stored");
        cutConsumed = checkpoint.getBoolean("consumed");
        worldOffsetX = checkpoint.getInteger("worldX");
        worldOffsetZ = checkpoint.getInteger("worldZ");
        pointerX = checkpoint.getDouble("pointerX");
        pointerY = checkpoint.getDouble("pointerY");
        NBTTagCompound presentation = checkpoint.getCompoundTag("nativePresentation");
        sandbox.shell.selectDockPage(
            checkpoint.hasKey("nativePresentation", 10) ? presentation.getInteger("dockPage")
                : sandbox.library.libraryDrawer() ? 2 : 0);
        if (checkpoint.hasKey("nativePresentation", 10))
            sandbox.shell.setDockExpanded(presentation.getBoolean("dockExpanded"));
        refreshNativeGraph();
        sandbox.shell.restorePropertiesGroups(
            checkpoint.getBoolean("targetExpanded"),
            checkpoint.getBoolean("sourceExpanded"),
            checkpoint.getBoolean("materialExpanded"));
        sandbox.tools.restoreViewWorkspace(checkpoint.getBoolean("viewWorkspace"));
        pendingNativeScroll = checkpoint.hasKey("nativePresentation", 10) ? (NBTTagCompound) presentation.copy() : null;
        if (pendingNativeScroll != null)
            pendingNativeScroll.setBoolean("viewWorkspace", checkpoint.getBoolean("viewWorkspace"));
    }

    private ListWidget<?, ?> checkpointList(String name) {
        if (name.startsWith("config.channels.") && !sandbox.config.drawer()
            || name.startsWith("config.options.") && sandbox.config.optionChannel()
                .isEmpty()
            || name.startsWith("config.candidates.") && !sandbox.config.hatchDrawer()
            || name.startsWith("config.presets.") && !sandbox.config.presetDrawer()
            || name.startsWith("config.sources.") && !sandbox.config.sourceDrawer()
            || name.equals("editor_native_view_workspace_list") && !sandbox.tools.viewWorkspaceOpen()) return null;
        ListWidget<?, ?> found = WidgetTree.findFirstWithNameNullable(sandbox.shell.root(), name, ListWidget.class);
        if (found != null) return found;
        if (scenePanel == null || !scenePanel.isValid()) return null;
        com.cleanroommc.modularui.screen.PanelManager manager = scenePanel.getContext()
            .getScreen()
            .getPanelManager();
        for (ModularPanel panel : manager.getOpenPanels()) if (manager.isSubPanelOf(panel, scenePanel)) {
            found = WidgetTree.findFirstWithNameNullable(panel, name, ListWidget.class);
            if (found != null) return found;
        }
        return null;
    }

    private boolean restoreNativeScroll(NBTTagCompound presentation) {
        if (!sandbox.tools.restoreViewWorkspace(presentation.getBoolean("viewWorkspace"))) return false;
        boolean ready = true;
        for (String name : CHECKPOINT_SCROLLS) if (presentation.hasKey(name, 3)) {
            ListWidget<?, ?> list = checkpointList(name);
            if (list != null && list.isValid() && list.getScrollData() != null) list.getScrollData()
                .scrollTo(list.getScrollArea(), presentation.getInteger(name));
            else ready = false;
        }
        return ready;
    }

    private boolean groupSatisfied(Action action) {
        switch (action.operation) {
            case LIBRARY:
                return sandbox.shell.libraryPageSelected();
            case EDITOR:
                return dockVisible(0);
            case SETTINGS:
                return dockVisible(1);
            case OPEN_DOCK:
                if ("header.save".equals(action.target)) return dockVisible(7);
                if (action.target.startsWith("launcher."))
                    return dockVisible(Integer.parseInt(action.target.substring("launcher.".length())));
                return false;
            case CLOSE_PANEL:
                return sandbox.shell.targetBounds("modal") == null;
            case CLOSE_MATERIAL:
                return !materialLesson.visible();
            case HANDLES:
                return sandbox.preview.moveHandles();
            case CLOSE_TARGET:
                return dockVisible(0) && !sandbox.shell.propertiesGroupExpanded("target");
            case OPEN_TARGET:
                return dockVisible(0) && sandbox.shell.propertiesGroupExpanded("target");
            case CLOSE_SOURCE:
                return dockVisible(3) && !sandbox.shell.propertiesGroupExpanded("source");
            case OPEN_SOURCE:
                return dockVisible(3) && sandbox.shell.propertiesGroupExpanded("source");
            case OPEN_MATERIAL:
                return dockVisible(4) && sandbox.shell.propertiesGroupExpanded("material");
            default:
                return false;
        }
    }

    private boolean dockVisible(int page) {
        return sandbox.shell.dockExpanded() && sandbox.shell.dockPage() == page;
    }

    private Action newMaterialAction() {
        for (Step item : HologramEditorLessons.course("blueprint", ui.tools.commands()))
            for (Action action : item.actions) if (action.operation == Operation.MATERIALS) return action;
        throw new IllegalStateException("Missing material lesson action");
    }

    @Override
    public void drawFocus(ModularGuiContext context, int width, int height) {
        if (!active()) return;
        int[] focus = actionBounds;
        if (actionIndex >= actions.size() && unavailable.isEmpty()) {
            int[] lessonFocus = sandbox.shell.targetBounds(steps.get(step).focus);
            if (lessonFocus != null) focus = lessonFocus;
        }
        if (materialLesson.visible()) focus = materialLesson.bounds(width, height);
        if (focus == null) focus = sandbox.shell.targetBounds(steps.get(step).focus);
        if (focus == null) focus = sandbox.shell.targetBounds("modal");
        if (focus == null) focus = sandbox.shell.targetBounds("viewport");
        int[] footer = overlay.footerBounds();
        if (focus != null) {
            // Native widget bounds and the normal viewport remain unchanged by the floating navigation.
            HologramEditorTutorialBlur.drawOutside(width, height, focus[0], focus[1], focus[2], focus[3]);
            int x = focus[0], y = focus[1], right = x + focus[2], bottom = y + focus[3];
            Gui.drawRect(0, 0, width, y, 0x33626262);
            Gui.drawRect(0, y, x, bottom, 0x33626262);
            Gui.drawRect(right, y, width, bottom, 0x33626262);
            Gui.drawRect(0, bottom, width, height, 0x33626262);
            HologramEditorTheme.drawGuidanceFrame(x, y, focus[2], focus[3], HologramUiAccent.INFORMATION, true);
            String title = (step + 1) + "/" + steps.size() + " " + steps.get(step).title;
            int[] viewport = sandbox.shell.targetBounds("viewport");
            int maxLabelWidth = viewport == null ? 0 : Math.max(0, viewport[2] - 16);
            String visibleTitle = ui.host.font()
                .trimStringToWidth(title, Math.max(0, maxLabelWidth - 10));
            int[] surface = cutListLesson != null ? sandbox.shell.targetBounds("cut.browser")
                : materialLesson.visible() ? focus : sandbox.shell.targetBounds("modal");
            int[] label = focusLabelBounds(
                focus,
                viewport,
                footer,
                surface,
                new int[] { (int) Math.round(pointerX) - 5, (int) Math.round(pointerY) - 5, 17, 22 },
                ui.host.font()
                    .getStringWidth(visibleTitle) + 10,
                13);
            if (label == null && viewport != null) {
                // A viewport-wide focus has no outside margin. Keep its short title between the top controls,
                // or immediately below them when the viewport is too narrow for that central gap.
                int centralWidth = Math.max(0, viewport[2] - 312);
                int labelY = viewport[1] + (centralWidth >= 48 ? 9 : 32);
                int availableWidth = centralWidth >= 48 ? centralWidth : Math.max(0, viewport[2] - 16);
                visibleTitle = ui.host.font()
                    .trimStringToWidth(title, Math.max(0, availableWidth - 10));
                int labelWidth = ui.host.font()
                    .getStringWidth(visibleTitle) + 10;
                label = new int[] { viewport[0] + (viewport[2] - labelWidth) / 2, labelY, labelWidth, 13 };
            }
            if (label != null && !visibleTitle.isEmpty()) {
                Gui.drawRect(label[0], label[1], label[0] + label[2], label[1] + label[3], 0xe65b412a);
                ui.host.font()
                    .drawString(visibleTitle, label[0] + 5, label[1] + 2, 0xfff1d3a5);
            }
        }
        drawPointer();
    }

    /** Keep the caption in the real scene margin, outside the target and above the independent footer controls. */
    private static int[] focusLabelBounds(int[] focus, int[] viewport, int[] footer, int[] surface, int[] pointer,
        int labelWidth, int labelHeight) {
        if (viewport == null) return null;
        int left = viewport[0] + 6, top = viewport[1] + 6;
        int right = viewport[0] + viewport[2] - 6, bottom = viewport[1] + viewport[3] - 6;
        if (footer != null) bottom = Math.min(bottom, footer[1] - 6);
        if (right - left < labelWidth || bottom - top < labelHeight) return null;
        int fx = focus[0], fy = focus[1], fr = fx + focus[2], fb = fy + focus[3];
        int[] outside = surface == null ? focus : surface;
        int ox = outside[0], oy = outside[1], or = ox + outside[2], ob = oy + outside[3];
        int[][] candidates = { { ox - labelWidth - 8, oy + (outside[3] - labelHeight) / 2 },
            { or + 8, oy + (outside[3] - labelHeight) / 2 }, { ox, oy - labelHeight - 5 }, { ox, ob + 5 },
            { left, top }, { right - labelWidth, top }, { left, bottom - labelHeight },
            { right - labelWidth, bottom - labelHeight } };
        int[] best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int[] candidate : candidates) {
            int x = Math.max(left, Math.min(right - labelWidth, candidate[0]));
            int y = Math.max(top, Math.min(bottom - labelHeight, candidate[1]));
            if (x < fr + 3 && x + labelWidth > fx - 3 && y < fb + 3 && y + labelHeight > fy - 3) continue;
            if (surface != null && x < or + 3 && x + labelWidth > ox - 3 && y < ob + 3 && y + labelHeight > oy - 3)
                continue;
            if (x < pointer[0] + pointer[2] && x + labelWidth > pointer[0]
                && y < pointer[1] + pointer[3]
                && y + labelHeight > pointer[1]) continue;
            int dx = Math.max(0, Math.max(fx - x - labelWidth, x - fr));
            int dy = Math.max(0, Math.max(fy - y - labelHeight, y - fb));
            long distance = (long) dx * dx + (long) dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new int[] { x, y, labelWidth, labelHeight };
            }
        }
        return best;
    }

    private void drawPointer() {
        int x = (int) Math.round(pointerX), y = (int) Math.round(pointerY);
        if (pressed) {
            Gui.drawRect(x - 4, y - 4, x + 8, y + 8, 0x70eab85f);
        }
        for (int row = 0; row < 12; row++) {
            int width = Math.min(row / 2 + 1, 6);
            Gui.drawRect(x - 1, y + row - 1, x + width + 1, y + row + 1, 0xff30231a);
            Gui.drawRect(x, y + row, x + width, y + row + 1, 0xffffe7b4);
        }
    }

    private boolean worldCourse() {
        return topic.startsWith("cut-") || topic.startsWith("box-");
    }

    @Override
    public boolean isometric() {
        return topic.endsWith("iso");
    }

    @Override
    public boolean handles() {
        return sandbox != null && sandbox.preview.moveHandles();
    }

    @Override
    public boolean cut() {
        return topic.startsWith("cut");
    }

    @Override
    public int corners() {
        return cornerCount;
    }

    @Override
    public int cutStage() {
        return cutStage;
    }

    @Override
    public int offsetX() {
        return worldOffsetX;
    }

    @Override
    public int offsetZ() {
        return worldOffsetZ;
    }

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
        result.setString("unavailable", unavailable);
        if (active()) {
            HologramState model = sandbox.session.state();
            NBTTagCompound engine = HologramRenderer.lessonInspection(model);
            engine.setString("renderer", "HologramRenderer.preview");
            engine.setString(
                "sourceId",
                topic.equals("blueprint") ? "author-selected-details-full" : "ebf-source-selected-details");
            engine.setString("sourceClass", "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace");
            engine.setString("contentSha256", HologramCutRawPreviewSession.digest(snapshot(model)));
            engine.setString("sourceSha256", sourceFingerprint);
            engine.setInteger("modelCellCount", model.cells.size());
            engine.setInteger(
                "selectedCellCount",
                sandbox.preview.selection()
                    .size());
            engine.setLong("renderGeneration", sandbox.canvas.renderGeneration());
            engine.setInteger("dockPage", sandbox.shell.dockPage());
            engine.setBoolean("dockExpanded", sandbox.shell.dockExpanded());
            engine.setBoolean("libraryExpanded", sandbox.shell.libraryPageSelected());
            engine.setBoolean("isolatedCommandSink", sandbox.session.isTutorial());
            result.setTag("lessonEngine", engine);
        }
        if (active() && worldCourse()) {
            NBTTagCompound geometry = worldStage.geometry.snapshot();
            result.setInteger(
                "nativeTeachingCells",
                geometry.getCompoundTag("scene")
                    .getTagList("cells", 10)
                    .tagCount());
            result.setInteger("selectedTeachingCells", worldStage.geometry.count());
            result.setInteger(
                "capturedTeachingCells",
                geometry.getCompoundTag("capturedSelection")
                    .getTagList("cells", 10)
                    .tagCount());
            result.setBoolean("teachingClipboardPresent", worldStage.geometry.stored());
            result.setBoolean("teachingClipboardConsumed", worldStage.geometry.consumed());
            result.setString("teachingRenderer", "HologramCutRawPreviewSession.renderLocal");
            NBTTagCompound renderer = worldStage.inspection();
            result.setTag("teachingRenderInspection", renderer);
            NBTTagCompound engine = result.getCompoundTag("lessonEngine");
            engine.setString("renderer", "HologramCutRawPreviewSession.renderLocal");
            engine.setString("sourceSha256", HologramCutRawPreviewSession.digest(geometry.getCompoundTag("source")));
            engine.setString("sceneSha256", HologramCutRawPreviewSession.digest(geometry.getCompoundTag("scene")));
            engine.setString("contentSha256", engine.getString("sceneSha256"));
            engine.setBoolean("worldMutation", false);
            engine.setBoolean("playerMutation", false);
            engine.setTag("rawRenderer", renderer.copy());
            engine.setLong("renderedCellCount", renderer.getLong("staticRendered"));
            engine.setBoolean("snapshotPresented", renderer.getBoolean("snapshotPresented"));
            engine.setInteger(
                "modelCellCount",
                geometry.getCompoundTag("scene")
                    .getTagList("cells", 10)
                    .tagCount());
            engine.setInteger("selectedCellCount", worldStage.geometry.count());
            engine.setInteger(
                "sourceCellCount",
                geometry.getCompoundTag("source")
                    .getTagList("cells", 10)
                    .tagCount());
            engine.setIntArray("selectionFirst", geometry.getIntArray("first"));
            engine.setIntArray("selectionSecond", geometry.getIntArray("second"));
            engine.setBoolean("placementLocked", geometry.getBoolean("locked"));
            engine.setBoolean("placementPreview", geometry.getBoolean("placing"));
            if (geometry.hasKey("clipboard", 10)) engine.setString(
                "clipboardSha256",
                HologramCutRawPreviewSession.digest(geometry.getCompoundTag("clipboard")));
        }
        result.setBoolean("cutStored", cutStored);
        result.setBoolean("cutConsumed", cutConsumed);
        result.setInteger("cornerCount", cornerCount);
        if (cutListLesson != null) {
            result.setTag("cutList", cutListLesson.snapshot());
            NBTTagCompound engine = result.getCompoundTag("lessonEngine");
            engine.setString("renderer", "HologramCutRawPreviewSession.renderLocal");
            NBTTagCompound renderer = cutListLesson.renderer()
                .inspection();
            engine.setTag("rawRenderer", renderer);
            engine.setLong("renderedCellCount", renderer.getLong("staticRendered"));
            engine.setBoolean("snapshotPresented", renderer.getLong("frames") > 0);
            engine.setString("contentSha256", HologramCutRawPreviewSession.digest(cutListLesson.raw()));
        }
        return result;
    }

    private int preferredCell() {
        HologramState state = sandbox.session.state();
        String source = state.data.getString("activeStructure");
        int current = sandbox.preview.selected();
        if (current >= 0 && current < state.cells.size()
            && candidateCell(state.cells.get(current))
            && (source.isEmpty() || state.cells.get(current)
                .belongsToStructure(source)))
            return current;
        int recorded = state.data.getInteger("selected");
        if (recorded >= 0 && recorded < state.cells.size()
            && candidateCell(state.cells.get(recorded))
            && (source.isEmpty() || state.cells.get(recorded)
                .belongsToStructure(source)))
            return recorded;
        for (HologramState.Cell cell : state.cells)
            if (candidateCell(cell) && (source.isEmpty() || cell.belongsToStructure(source))) return cell.index;
        for (HologramState.Cell cell : state.cells)
            if (!cell.anchor && !annotationCell(cell) && (source.isEmpty() || cell.belongsToStructure(source)))
                return cell.index;
        return !source.isEmpty() || state.cells.isEmpty() ? -1 : 0;
    }

    private static boolean candidateCell(HologramState.Cell cell) {
        return !cell.candidates.isEmpty() && !cell.anchor && !annotationCell(cell);
    }

    private static boolean annotationCell(HologramState.Cell cell) {
        return cell.structureId.startsWith("gtmb_text_") || cell.structureId.startsWith("gtmb_picture_");
    }

    private static final String[] SOURCE_METADATA = { "main", "mainExplicit", "noHatches", "channels", "capabilities" };

    private static NBTTagCompound sourceMetadata(NBTTagCompound data) {
        NBTTagCompound result = new NBTTagCompound();
        for (String key : SOURCE_METADATA) if (data.hasKey(key)) result.setTag(
            key,
            data.getTag(key)
                .copy());
        return result;
    }

    private static String observedSourceId(NBTTagCompound data, String id) {
        NBTTagList structures = data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            NBTTagCompound structure = structures.getCompoundTagAt(i);
            if (id.equals(structure.getString("id")) && structure.hasKey("tutorialObservedSourceId", 8))
                return structure.getString("tutorialObservedSourceId");
        }
        return id;
    }

    private void selectObservedSource(NBTTagCompound data, String id) {
        String previous = data.getString("activeStructure");
        if (previous.equals(id)) return;
        NBTTagCompound cached = data.getCompoundTag("tutorialMemberMetadata");
        if (id.isEmpty()) {
            cached.setTag(previous, sourceMetadata(data));
            NBTTagCompound overview = data.getCompoundTag("tutorialOverviewMetadata");
            for (String key : SOURCE_METADATA) {
                data.removeTag(key);
                if (overview.hasKey(key)) data.setTag(
                    key,
                    overview.getTag(key)
                        .copy());
            }
        } else {
            NBTTagCompound recorded, observedOverview;
            try {
                recorded = HologramEditorTutorialSamples.sameSourceMetadata(observedSourceId(data, id));
                observedOverview = HologramEditorTutorialSamples.overviewMetadata();
            } catch (java.io.IOException unavailable) {
                sandbox.session.notice("这个成员没有匹配的真实来源参数：" + unavailable.getMessage());
                return;
            }
            if (previous.isEmpty()) data.setTag(
                "tutorialOverviewMetadata",
                data.hasKey("tutorialOverviewMetadata", 10) ? sourceMetadata(data) : sourceMetadata(observedOverview));
            else cached.setTag(previous, sourceMetadata(data));
            NBTTagCompound member = cached.hasKey(id, 10) ? cached.getCompoundTag(id) : sourceMetadata(recorded);
            for (String key : SOURCE_METADATA) {
                data.removeTag(key);
                if (member.hasKey(key)) data.setTag(
                    key,
                    member.getTag(key)
                        .copy());
            }
        }
        data.setTag("tutorialMemberMetadata", cached);
        data.setString("activeStructure", id);
    }

    private void isolatedCommand(String op, NBTTagCompound request) {
        isolatedCommands++;
        NBTTagCompound data = snapshot(sandbox.session.state());
        if (op.equals("selectStructure")) selectObservedSource(data, request.getString("structureId"));
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
            // Both rows are explicitly labelled copies of the same recorded geometry.

        }
        // All other commands (including build/save/cut/import/network) terminate here without side effects.
        sandbox.session.isolatedSnapshot(new HologramState(data));
    }

    private static NBTTagCompound snapshot(HologramState state) {
        NBTTagCompound data = (NBTTagCompound) state.data.copy();
        NBTTagList cells = new NBTTagList();
        for (HologramState.Cell cell : state.cells) cells.appendTag(cell.tag());
        data.setTag("cells", cells);
        return data;
    }

    private static HologramState sample(String topic) {
        NBTTagCompound data;
        try {
            HologramEditorTutorialSamples.Kind kind = topic.equals("blueprint")
                ? HologramEditorTutorialSamples.Kind.AUTHOR
                : HologramEditorTutorialSamples.Kind.PHYSICAL;
            data = HologramEditorTutorialSamples.snapshot(kind);
            data.setString("tutorialStructureId", HologramEditorTutorialSamples.primaryStructure(kind));
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("真实教学样例校验失败：" + unavailable.getMessage(), unavailable);
        }
        data.setString("session", "__gtmb_tutorial__");
        data.setString("title", "电力高炉 · 真实采集规划教学");
        data.setString("status", "仅在教学会话内存中演示，不提交真实操作");
        data.setString("activeStructure", "");
        data.setString("openPurpose", topic.equals("blueprint") ? "blueprintEdit" : "");
        data.setString("editorWorkspace", topic.equals("blueprint") ? "blueprint" : "projection");
        data.setInteger("job", 0);
        data.setLong("uiSequence", 0);
        for (String key : new String[] { "areaImporting", "snapshotPending", "placementValidating", "attachmentPending",
            "capturePending", "areaSelectionShell", "targetClosed", "blueprintAwaitingConfirmation",
            "discardConfirmationRequired", "presetBrowseForPlacement", "virtualPlacement", "blueprintPreview" })
            data.setBoolean(key, false);
        for (String key : new String[] { "discardTargetAction", "textPlacementAck", "processingPatch", "aeReview",
            "networkBindingNavigation" }) data.removeTag(key);
        data.setBoolean("open", true);
        data.setBoolean("interactive", true);
        NBTTagList presets = new NBTTagList();
        for (int i = 0; i < 2; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", "tutorial-sample-" + i);
            row.setString("name", i == 0 ? "真实作者样例" : "同一快照 · 隔离副本");
            row.setInteger(
                "cells",
                data.getTagList("cells", 10)
                    .tagCount());
            presets.appendTag(row);
        }
        data.setTag("presets", presets);
        data.setString("virtualPresetName", "真实教学样例");
        return new HologramState(data);
    }
}
