// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramLocalControllerCapture;

/** Minecraft screen adapter. Domain state and behavior live in composed workflow modules. */
public final class HologramScreen extends HologramMuiCanvasScreen implements HologramUiHost {

    HologramUiModules ui = new HologramUiModules(this);
    private final HologramEditorWorkspaces.Graphs workspaceGraphs = new HologramEditorWorkspaces.Graphs();
    private final java.util.Set<HologramUiModules> initializedGraphs = java.util.Collections
        .newSetFromMap(new java.util.IdentityHashMap<>());
    private com.cleanroommc.modularui.widget.ParentWidget<?> workspaceMount;
    private boolean websiteConfirmationResumePending;
    private boolean detached, detachedStarted, detachedReturned, detachedReplay;
    private String detachedTopic;
    private Runnable detachedReturn;
    private boolean domainInitialized;
    private long controllerRequest;
    private TileEntity openingController;
    private Object openingConnection;
    private ItemStack openingTool;
    private String openingToolId;
    private int openingSlot;
    private HologramLocalControllerCapture localController;
    private HologramState.Preparation localControllerPreparation;
    private int localControllerSide;
    private boolean localControllerPublished;

    HologramScreen(HologramState state) {
        super("workstation");
        ui.session.initialize(state);
        workspaceGraphs.retain(state, () -> ui);
    }

    HologramScreen(TileEntity tile, int dimension, long request) {
        this(controllerWaitingState(dimension, tile.xCoord, tile.yCoord, tile.zCoord));
        controllerRequest = request;
        rememberControllerContext(tile);
    }

    static HologramScreen localController(TileEntity tile, int dimension, int side) {
        HologramLocalControllerCapture capture = new HologramLocalControllerCapture(tile, dimension, side);
        NBTTagCompound opening = capture.opening();
        opening.setString("status", HologramClient.text("controllerLocalPreparing", "正在读取本地标准结构…"));
        HologramScreen screen = new HologramScreen(new HologramState(opening));
        screen.localController = capture;
        screen.localControllerSide = side;
        screen.rememberControllerContext(tile);
        return screen;
    }

    private void rememberControllerContext(TileEntity tile) {
        Minecraft client = minecraft();
        openingController = tile;
        openingConnection = client.getNetHandler();
        openingTool = client.thePlayer.getHeldItem();
        openingToolId = toolId(openingTool);
        openingSlot = client.thePlayer.inventory.currentItem;
    }

    boolean localControllerStandard() {
        return ui.session.state().data.getBoolean("localStandardController");
    }

    boolean readLiveController() {
        if (!localControllerStandard() || !controllerContextCurrent(false)) {
            ui.session.notice(HologramClient.text("controllerLocalChanged", "控制器或手持投影仪已变化"));
            return false;
        }
        return tryDeferAction(() -> {
            if (minecraft().currentScreen != this || !controllerContextCurrent(false)) return;
            if (!HologramClient.readLiveController(openingController, localControllerSide))
                ui.session.notice(HologramClient.text("controllerLocalChanged", "控制器或手持投影仪已变化"));
        });
    }

    private void advanceLocalController() {
        if (!localControllerStandard() || localControllerPublished || localController == null) return;
        try {
            Minecraft client = minecraft();
            if (!controllerContextCurrent(false)
                || !localController.matches(client.theWorld, client.thePlayer.dimension))
                throw new java.io.IOException("LOCAL_CONTROLLER_CHANGED");
            if (localControllerPreparation == null) {
                NBTTagCompound snapshot = localController.step(2048, 3_000_000L);
                if (snapshot == null) return;
                snapshot.setString("status", HologramClient.text("controllerLocalStandard", "本地标准结构 · 默认参数"));
                localControllerPreparation = new HologramState.Preparation(snapshot);
                return;
            }
            if (!localControllerPreparation.step(2048, 3_000_000L)) return;
            updateState(localControllerPreparation.result());
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .debug("Local controller standard structure unavailable", failure);
            updateState(
                new HologramState(
                    localController.unavailable(HologramClient.text("controllerLocalUnavailable", "此控制器没有可用的本地标准结构"))));
        }
        localControllerPublished = true;
        localController = null;
        localControllerPreparation = null;
    }

    static HologramState controllerWaitingState(int dimension, int x, int y, int z) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("editorWorkspace", "projection");
        data.setString("workspaceTab", "structure");
        data.setString("title", HologramClient.text("controllerOpening", "Reading controller"));
        data.setBoolean("target", true);
        data.setBoolean("controllerOpening", true);
        data.setBoolean("snapshotPending", true);
        data.setBoolean("areaSelectionShell", true);
        data.setInteger("dimension", dimension);
        data.setInteger("x", x);
        data.setInteger("y", y);
        data.setInteger("z", z);
        return new HologramState(data);
    }

    long controllerRequest() {
        return controllerRequest;
    }

    boolean controllerOpeningCurrent() {
        return controllerRequest > 0 && HologramClient.controllerOpen.accepts(controllerRequest)
            && controllerContextCurrent(true);
    }

    private boolean controllerContextCurrent(boolean assignedToolAllowed) {
        Minecraft client = minecraft();
        if (openingController == null || client.thePlayer == null
            || client.theWorld == null
            || client.getNetHandler() != openingConnection
            || client.theWorld != openingController.getWorldObj()
            || client.thePlayer.inventory.currentItem != openingSlot
            || client.theWorld
                .getTileEntity(openingController.xCoord, openingController.yCoord, openingController.zCoord)
                != openingController)
            return false;
        ItemStack held = client.thePlayer.getHeldItem();
        if (!ItemNekoHologramProjector.isProjector(held)) return false;
        String heldId = toolId(held);
        // Opening an unlinked projector assigns its ID server-side and can replace the local stack on sync.
        if (openingToolId.isEmpty()) return held == openingTool || assignedToolAllowed && !heldId.isEmpty();
        return openingToolId.equals(heldId);
    }

    boolean acceptsControllerTool(String serverToolId) {
        String heldId = toolId(minecraft().thePlayer.getHeldItem());
        return heldId.isEmpty() || serverToolId.isEmpty() || heldId.equals(serverToolId);
    }

    private static String toolId(ItemStack stack) {
        return stack == null || !stack.hasTagCompound() ? ""
            : stack.getTagCompound()
                .getString("gtitHologramToolId");
    }

    void controllerOpened() {
        controllerRequest = 0;
        openingController = null;
        openingConnection = null;
        openingTool = null;
        openingToolId = null;
    }

    boolean closeControllerOpening() {
        if (controllerRequest == 0) return false;
        HologramClient.controllerOpen.dismiss(controllerRequest);
        if (minecraft().currentScreen == this) minecraft().displayGuiScreen(null);
        return true;
    }

    private void deferControllerClose() {
        HologramClient.controllerOpen.dismiss(controllerRequest);
        deferAction(this::closeControllerOpening);
    }

    private HologramScreen(String topic, boolean replay, Runnable returnToStorage) {
        super("detached_tutorial");
        if (returnToStorage == null) throw new IllegalArgumentException("A detached tutorial requires a return target");
        detached = true;
        detachedTopic = topic;
        detachedReplay = replay;
        detachedReturn = returnToStorage;
        ui.session.initializeIsolated(HologramEditorTutorial.detachedState(), (operation, request) -> {});
    }

    static HologramScreen detachedTutorial(String topic, boolean replay, Runnable returnToStorage) {
        return new HologramScreen(topic, replay, returnToStorage);
    }

    private void returnFromDetached() {
        if (detachedReturned) return;
        detachedReturned = true;
        detachedReturn.run();
    }

    boolean acceptsLibraryNavigation(HologramState next) {
        return ui.library.acceptsLibraryNavigation(next);
    }

    void updateState(HologramState next) {
        if (!detached && !ui.tutorial.active()
            && HologramEditorWorkspaces.domain(ui.session.state()) != HologramEditorWorkspaces.domain(next)) {
            ui.memory.rememberUi();
            ui.preview.releasePointerCapture();
            ui.tools.closeTransient();
            ui.shell.pauseWorkspace();
            ui.workspaces.acknowledged();
            ui = workspaceGraphs.retain(next, () -> {
                HologramUiModules created = new HologramUiModules(this);
                created.session.initialize(next);
                return created;
            });
            ui.workspaces.domainActivated();
            if (workspaceMount != null) mountWorkspaceGraph();
            ui.shell.resumeWorkspace();
        }
        if (!detached) try (FieldGraph ignored = fieldGraph(ui)) {
            ui.session.updateState(next);
        }
    }

    @Override
    protected boolean buildNativePanel() {
        if (detached) return true;
        if (!domainInitialized) {
            try (FieldGraph ignored = fieldGraph(ui)) {
                ui.router.initGui();
            }
            domainInitialized = true;
        }
        initializedGraphs.add(ui);
        workspaceMount = new com.cleanroommc.modularui.widget.ParentWidget<>().sizeRel(1f);
        workspaceMount.child(ui.shell.build(panel()));
        panel().child(workspaceMount);
        return true;
    }

    private void mountWorkspaceGraph() {
        if (initializedGraphs.add(ui)) {
            try (FieldGraph ignored = fieldGraph(ui)) {
                ui.router.initGui();
                workspaceMount.child(ui.shell.build(panel()));
            }
            workspaceMount.scheduleResize();
        }
    }

    public void initGui() {
        super.initGui();
        // openFrom builds this graph before displayGuiScreen attaches its main panel to MUI.
        // Secondary tutorial panels therefore start from the first valid native tick, never construction.
        if (detached) return;
        if (websiteConfirmationResumePending) websiteConfirmationResumePending = false;
        ui.canvas.resize();
        if (controllerRequest == 0 && !localControllerStandard()) HologramBackgroundTasks.restoreUi(ui);
    }

    void resumeAfterWebsiteConfirmation() {
        websiteConfirmationResumePending = true;
    }

    public boolean doesGuiPauseGame() {
        return ui.router.doesGuiPauseGame();
    }

    protected void onMuiClose() {
        try {
            if (detached) {
                ui.tutorial.closeWithoutProgress();
                endTutorialFields();
            } else if (controllerRequest == 0 && !localControllerStandard() && !ui.session.localDocument())
                ui.router.onGuiClosed();
            else org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        } finally {
            localController = null;
            localControllerPreparation = null;
            // This hook runs on final MUI panel disposal, unlike onGuiClosed's
            // temporary website/NEI suspensions. Dormant graph GPU resources end here.
            java.util.Set<HologramUiModules> graphs = java.util.Collections
                .newSetFromMap(new java.util.IdentityHashMap<>());
            graphs.addAll(workspaceGraphs.values());
            graphs.add(ui);
            for (HologramUiModules graph : graphs) {
                graph.shell.close();
                graph.canvas.layout()
                    .release();
                releaseFieldGraph(graph);
            }
            workspaceGraphs.clear();
            initializedGraphs.clear();
        }
    }

    @Override
    protected void onMuiActionFailure(RuntimeException failure) {
        ui.session.notice(failure.getMessage() == null ? "界面操作失败，请重试" : failure.getMessage());
    }

    @Override
    public void onGuiClosed() {
        ui.tutorial.closeWithoutProgress();
        ui.tools.closeTransient();
        ui.canvas.layout()
            .release();
        ui.preview.releasePointerCapture();
        if (detached) {
            detachedReturned = true;
            endTutorialFields();
            return;
        }
        if (controllerRequest > 0) {
            HologramClient.controllerOpen.dismiss(controllerRequest);
            return;
        }
        if (localControllerStandard() || ui.session.localDocument()) return;
        ui.memory.rememberUi();
        HologramBackgroundTasks.rememberUi(ui);
        if (ui.ae.aePreviewTransition()) ui.ae.aePreviewTransition(false);
    }

    static void rememberBlueprintPlacement(HologramState snapshot) {
        HologramUiMemory.rememberBlueprintPlacement(snapshot);
    }

    boolean retainsPlacementReview(HologramState next) {
        return ui.review.retainsPlacementReview(next);
    }

    public void updateScreen() {
        if (minecraft().currentScreen == this) advanceLocalController();
        if (controllerRequest > 0) {
            if (!controllerOpeningCurrent()) closeControllerOpening();
            else try (FieldGraph ignored = fieldGraph(ui)) {
                ui.shell.tick();
            }
            return;
        }
        if (detached) {
            if (!detachedStarted) {
                if (detachedHostReady()) tryDeferAction(() -> {
                    if (!detachedHostReady() || detachedStarted) return;
                    detachedStarted = true;
                    ui.tutorial.startDetached(detachedTopic, detachedReplay, this::returnFromDetached);
                });
                return;
            }
            ui.tutorial.tick();
            if (detachedStarted && !ui.tutorial.active() && !detachedReturned) deferAction(this::returnFromDetached);
            return;
        }
        try (FieldGraph ignored = fieldGraph(ui)) {
            ui.router.updateScreen();
            ui.shell.tick();
        }
    }

    private boolean detachedHostReady() {
        return !detachedReturned && minecraft().currentScreen == this
            && panel().isValid()
            && getScreen().getPanelManager()
                .isOpen();
    }

    protected void renderScene(int mouseX, int mouseY, float ticks) {
        if (detached && !ui.tutorial.active()) return;
        ui.canvas.beginFrame(mouseX, mouseY);
        canvasTransform(ui.canvas.originX(), ui.canvas.originY(), ui.canvas.scale());
        try {
            ui.router.drawScreen(mouseX, mouseY, ticks);
        } catch (RuntimeException failure) {
            if (!detached) throw failure;
            ui.tutorial.closeWithoutProgress();
            deferAction(this::returnFromDetached);
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .warn("Detached tutorial closed after a presentation failure", failure);
            return;
        }
        if (detached && !ui.tutorial.active()) return;
        for (HologramUiCanvas.Control control : ui.tutorial.renderUi().canvas.controls()) {
            nativeButton(
                control.id,
                control.x,
                control.y,
                control.w,
                control.h,
                control.label,
                control.enabled,
                control.accent,
                control.selected);
            nativeVoucherRibbon(control.id, control.voucherRibbon);
            nativeSceneTooltip(control.id, control.sceneTooltip);
        }
    }

    long saveQuantumGroups(NBTTagList groups, long sourceRevision) {
        return ui.processing.saveQuantumGroups(groups, sourceRevision);
    }

    void quantumGroupsSaved() {
        ui.processing.quantumGroupsSaved();
    }

    boolean hasPendingAeReviewNavigation() {
        return ui.ae.hasPendingAeReviewNavigation();
    }

    NBTTagCompound smokeAeUiState() {
        return ui.diagnostics.smokeAeUiState();
    }

    net.minecraft.nbt.NBTTagList smokeAeWarningMarkers() {
        return ui.diagnostics.smokeAeWarningMarkers();
    }

    String smokeAeReviewGui() {
        return ui.diagnostics.smokeAeReviewGui();
    }

    NBTTagCompound smokeLibraryUiState() {
        return ui.diagnostics.smokeLibraryUiState();
    }

    public void mouseClicked(int x, int y, int button) {
        if (detached) return;
        ui.router.mouseClicked(x, y, button);
    }

    public void mouseClickMove(int x, int y, int button, long elapsed) {
        if (detached) return;
        ui.router.mouseClickMove(x, y, button, elapsed);
    }

    public void mouseMovedOrUp(int x, int y, int button) {
        if (detached) return;
        ui.router.mouseMovedOrUp(x, y, button);
    }

    public void handleMouseInput() {
        if (detached && !ui.tutorial.active()) return;
        super.handleMouseInput();
        // Native panels and the bounded scene widget own wheel routing.
    }

    public void keyTyped(char c, int key) {
        if (detached || controllerRequest > 0 && muiKeyBefore(c, key)) return;
        ui.router.keyTyped(c, key);
    }

    @Override
    protected void activateNative(int id) {
        if (detached) {
            ui.tutorial.activate(id);
            return;
        }
        if (ui.tutorial.activate(id)) return;
        ui.shortcuts.activity();
        ui.memory.initialEditHint(ui.memory.savedEditHint(false));
        ui.memory.rememberGuidance();
        try (FieldGraph ignored = fieldGraph(ui)) {
            ui.router.activate(id);
        }
    }

    @Override
    protected void sceneScroll() {
        ui.router.handleMouseInput();
    }

    @Override
    protected String nativeTooltip(int id, boolean enabled) {
        if (id == HologramEditorControlIds.GUIDE) return "收纳 / 展开智能指引；此偏好会保留到下次打开。";
        if (id == HologramEditorControlIds.GUIDE + 1) return "完整操作说明与八门独立教程。";
        if (id == HologramEditorControlIds.TUTORIAL + 1) return "回到上一步的动画开头。";
        if (id == HologramEditorControlIds.TUTORIAL + 2) return "继续下一步；当前动画会先完整播放。";
        if (id == HologramEditorControlIds.TUTORIAL + 3) return "跳过教程并恢复原工作区。";
        if (id == HologramEditorControlIds.TUTORIAL + 4) return "清空教学进度，从本课程第一步重新播放。";
        String tool = ui.tools.tooltip(id);
        if (tool != null) return tool;
        String help = ui.canvas.controlHelp(id);
        if (help == null) return "";
        return enabled ? help : help + "\n" + ui.canvas.disabledReason(id);
    }

    @Override
    protected String nativeIcon(int id, String label) {
        return id == HologramEditorControlIds.GUIDE + 1 ? "help-circle" : ui.tools.icon(id);
    }

    @Override
    protected boolean nativeIconOnly(int id) {
        return id == HologramEditorControlIds.GUIDE + 1 || ui.tools.iconOnly(id);
    }

    @Override
    protected boolean muiKeyBefore(char character, int key) {
        boolean back = key == org.lwjgl.input.Keyboard.KEY_ESCAPE
            || key == org.lwjgl.input.Keyboard.KEY_E && !ui.router.textFieldFocused();
        if (controllerRequest > 0) {
            if (back) deferControllerClose();
            return true;
        }
        if (back && ui.tutorial.active()) {
            deferAction(ui.tutorial::closeWithoutProgress);
            return true;
        }
        if (ui.tutorial.blockInput()) return true;
        if (detached) return true;
        if (back && ui.textBoxes.editorOpen()) {
            deferAction(ui.textBoxes::consumeEditorBack);
            return true;
        }
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE && ui.router.textFieldFocused()) {
            // Keep the current draft. MUI's late Escape closes every panel when a world is loaded.
            getScreen().getContext()
                .removeFocus();
            return true;
        }
        if (back) {
            deferAction(ui.router::back);
            return true;
        }
        return false;
    }

    @Override
    protected boolean muiMouseBefore(int button) {
        if (controllerRequest > 0) {
            if (ui.shell.controllerOpeningExitHovered(button)) deferControllerClose();
            return true;
        }
        if (ui.tutorial.blockInput()) return button != 0 || !ui.tutorial.navigationContains(
            getScreen().getContext()
                .getAbsMouseX(),
            getScreen().getContext()
                .getAbsMouseY(),
            button);
        return detached && !ui.tutorial.active();
    }

    @Override
    protected boolean muiScrollBefore() {
        return controllerRequest > 0 || ui.tutorial.blockInput() || detached && !ui.tutorial.active();
    }

    @Override
    protected void muiActivity() {
        if (!detached) ui.shortcuts.activity();
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (detached || ui.tutorial.blockInput()) return true;
        if (ui.shell.hasModalPanel()) return false;
        ui.router.keyTyped(character, key);
        return true;
    }

    int[] smokeControlPoint(int id) {
        return ui.diagnostics.smokeControlPoint(id);
    }

    void smokeClickControl(int id) {
        ui.diagnostics.smokeClickControl(id);
    }

    void smokeClickAt(int x, int y, int button) {
        ui.diagnostics.smokeClickAt(x, y, button);
    }

    NBTTagCompound smokeAreaImportUiState() {
        return ui.diagnostics.smokeAreaImportUiState();
    }

    int[] smokeViewportBounds() {
        return ui.diagnostics.smokeViewportBounds();
    }

    int[] smokePanelBounds() {
        return ui.diagnostics.smokePanelBounds();
    }

    boolean smokeKey(int key, int modifiers) {
        return ui.diagnostics.smokeKey(key, modifiers);
    }

    String smokeBinding(int index) {
        return ui.diagnostics.smokeBinding(index);
    }

    NBTTagCompound smokeSnapshot() {
        return ui.diagnostics.smokeSnapshot();
    }

    int[] smokeSelectedIndices() {
        return ui.diagnostics.smokeSelectedIndices();
    }

    int[] smokeCapturePreviewSelection(int logicalX, int logicalY, boolean sameType, boolean additive) {
        return ui.diagnostics.smokeCapturePreviewSelection(logicalX, logicalY, sameType, additive);
    }

    boolean smokeHasControl(int id) {
        return ui.diagnostics.smokeHasControl(id);
    }

    long smokeRenderGeneration() {
        return ui.diagnostics.smokeRenderGeneration();
    }

    boolean smokePending() {
        return ui.diagnostics.smokePending();
    }

    NBTTagCompound smokeCaptureMissingBuildClick() {
        return ui.diagnostics.smokeCaptureMissingBuildClick();
    }

    void smokeRenderScenario(String scenario) {
        ui.diagnostics.smokeRenderScenario(scenario);
    }

    String smokeCaptureLocalSelection() {
        return ui.diagnostics.smokeCaptureLocalSelection();
    }

    String smokeCaptureV004Editor() {
        return ui.diagnostics.smokeCaptureV004Editor();
    }

    String smokeCaptureV004Tabs() {
        return ui.diagnostics.smokeCaptureV004Tabs();
    }

    String smokePlacementReviewContext() {
        return ui.diagnostics.smokePlacementReviewContext();
    }

    String smokeConstructionScopeContext() {
        return ui.diagnostics.smokeConstructionScopeContext();
    }

    String smokeProcessingFlowContext() {
        return ui.diagnostics.smokeProcessingFlowContext();
    }

    static String smokeLibraryNavigationFences() {
        return HologramUiDiagnostics.smokeLibraryNavigationFences();
    }

    String smokeCaptureLibraryWorkflow() {
        return ui.diagnostics.smokeCaptureLibraryWorkflow();
    }

    String smokeRenderGizmoHover(String scenario) {
        return ui.diagnostics.smokeRenderGizmoHover(scenario);
    }

    String smokeLayout() {
        return ui.diagnostics.smokeLayout();
    }

    public net.minecraft.client.Minecraft minecraft() {
        return mc == null ? net.minecraft.client.Minecraft.getMinecraft() : mc;
    }

    public net.minecraft.client.gui.FontRenderer font() {
        return fontRendererObj;
    }

    public int screenWidth() {
        return width;
    }

    public int screenHeight() {
        return height;
    }

    public HologramScreen parent() {
        return this;
    }

    @Override
    public void renderBackground() {
        super.drawDefaultBackground();
    }

    @Override
    public void renderText(net.minecraft.client.gui.FontRenderer font, String text, int x, int y, int color) {
        font.drawString(text, x, y, color);
    }

    @Override
    public void renderCenteredText(net.minecraft.client.gui.FontRenderer font, String text, int x, int y, int color) {
        font.drawString(text, x - font.getStringWidth(text) / 2, y, color);
    }

    @Override
    public void renderTooltip(java.util.List<String> rows, int x, int y, net.minecraft.client.gui.FontRenderer font) {
        super.drawHoveringText(rows, x, y, font);
    }
}
