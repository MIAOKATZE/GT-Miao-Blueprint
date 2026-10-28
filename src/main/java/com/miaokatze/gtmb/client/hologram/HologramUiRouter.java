package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;

import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Internal router workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiRouter {

    private final HologramProgress.Tracker importProgress = new HologramProgress.Tracker();
    private long wheelEvent = Long.MIN_VALUE;

    private final HologramUiModules ui;

    HologramUiRouter(HologramUiModules ui) {
        this.ui = ui;
    }

    void initGui() {
        ui.shortcuts.activity();
        ui.library.prepareInitialization();
        Keyboard.enableRepeatEvents(true);
        ui.shortcuts.loadBindings();
        ui.canvas.resize();
        ui.config.initializeFields();
        ui.position.initializeFields();
        ui.library.initializeFields();
        ui.annotations.initializeFields();
        ui.bindings.initializeFields();
        ui.review.initializeReview();
    }

    boolean doesGuiPauseGame() {
        return false;
    }

    void onGuiClosed() {
        ui.tutorial.closeWithoutProgress();
        ui.tools.closeTransient();
        ui.canvas.layout()
            .release();
        if (ui.session.backgroundBusy() && !ui.diagnostics.captureRenderSmokeAction()) {
            ui.memory.rememberUi();
            HologramBackgroundTasks.rememberUi(ui);
            HologramBackgroundTasks.detach();
            Keyboard.enableRepeatEvents(false);
            return;
        }
        ui.library.cancelLibraryNavigation();
        ui.memory.rememberUi();
        if (ui.ae.aeReview() != null && ui.ae.aeReview().review) ui.ae.activateAeReview(182);
        if (ui.ae.aePreviewTransition()) {
            ui.ae.aePreviewTransition(false);
            Keyboard.enableRepeatEvents(false);
            return;
        }
        if (ui.session.state().data.getBoolean("presetBrowseForPlacement"))
            ui.session.send("dismissPresetBrowse", null, false);
        ui.preview.editQueue()
            .clear();
        ui.preview.queuedDispatchSequence(-1);
        Keyboard.enableRepeatEvents(false);
    }

    void closeRoot() {
        if (ui.session.isLocalAuthor()) {
            ui.memory.rememberUi();
            ui.host.minecraft()
                .displayGuiScreen(null);
            return;
        }
        if (ui.session.backgroundBusy() && !ui.diagnostics.captureRenderSmokeAction()) {
            ui.memory.rememberUi();
            HologramBackgroundTasks.rememberUi(ui);
            HologramBackgroundTasks.detach();
            ui.host.minecraft()
                .displayGuiScreen(null);
            return;
        }
        if (!ui.diagnostics.captureRenderSmokeAction()) HologramLibraryClient.INSTANCE.cancelQuickPlacement();
        if (!ui.diagnostics.captureRenderSmokeAction()) HologramLibraryClient.INSTANCE.cancel();
        ui.memory.rememberUi();
        ui.ae.cancelAeReviewNavigation();
        if (!ui.diagnostics.captureRenderSmokeAction())
            HologramClient.dismissRootView(ui.session.state().session, ui.session.sequence() + 1);
        ui.session.send("close", null, false);
        ui.host.minecraft()
            .displayGuiScreen(null);
    }

    void drawImportBusy(int mx, int my) {
        boolean importing = ui.session.areaImporting();
        boolean validating = ui.session.state().data.getBoolean("placementValidating");
        ui.canvas.text(validating ? "正在核对放置位置与材料" : importing ? "正在导入框选区域" : "正在准备蓝图快照", 142, 13, 360, 0xf5e4b9);
        drawRect(30, 62, 650, 282, 0xff172f36);
        ui.canvas
            .text(validating ? "放置校验 · 完成后显示材料核算" : importing ? "正在导入区域 · 请稍候" : "正在读取蓝图 · 请稍候", 50, 78, 580, 0xffe59b);
        String stage = ui.session.state().data.getString("areaImportStage");
        if (stage.isEmpty()) stage = "blocks";
        ui.canvas.text("准备完成前，暂不能编辑、保存或放置蓝图。", 50, 123, 580, 0xe5c28d);
        ui.canvas.text(importing ? "取消后保留导入前的蓝图。" : "可以先返回世界，快照准备会继续。", 50, 141, 580, 0xa9d6cf);
        HologramProgress progress = ui.session.progress();
        if (progress == null || progress.complete) progress = importProgress.update(
            ui.session.state().session,
            importing ? "area." + stage : "snapshot.prepare",
            ui.session.state().data.getString("areaImportDetail"),
            ui.session.state().data.getLong("areaImportDone"),
            ui.session.state().data.getLong("areaImportTotal"),
            ui.session.state().data.getString("areaImportUnit"),
            false,
            System.nanoTime());
        ui.canvas.text(HologramProgressView.phase(progress.phase), 50, 105, 580, 0x8bdddf);
        HologramProgressView.draw(ui.host.font(), progress, 50, 165, 580, 9);
        ui.canvas.text(HologramProgressView.detail(progress), 50, 192, 580, 0x8bdddf);
        ui.canvas.text(ui.session.state().data.getString("status"), 50, 204, 580, 0xe5c28d);
        ui.canvas.button(
            193,
            218,
            239,
            244,
            20,
            importing && !ui.session.backgroundBusy() ? "取消导入并返回世界 · Esc / E" : "返回世界，后台继续 · Esc / E",
            true,
            mx,
            my);
    }

    void updateScreen() {
        ui.review.tickMaterialDialog();
        ui.library.maybeShowSaveGuidance();
        ui.library.tickStatus();
        ui.config.tickMainField();
        ui.library.tickNameField();
        ui.annotations.tickField();
        ui.nbt.tickField();
        ui.processing.tickField();
        ui.bindings.tickField();
        ui.config.tickChannelFields();
        ui.position.tickFields();
        ui.tutorial.tick();
    }

    void drawScreen(int mouseX, int mouseY, float ticks) {
        if (ui.tutorial.active()) {
            ui.tutorial.drawScreen(mouseX, mouseY, ticks);
            return;
        }
        if (!ui.session.isTutorial()) ui.shortcuts.observePointer(mouseX, mouseY);
        if (!org.lwjgl.opengl.Display.isActive()) {
            ui.canvas.layout()
                .release();
            ui.preview.releasePointerCapture();
            ui.tools.closeTransient();
        }
        if (!ui.session.isTutorial()) ui.preview.movePreviewCamera();
        ui.host.renderBackground();
        int mx = ui.canvas.localX(mouseX), my = ui.canvas.localY(mouseY);
        ui.canvas.controls()
            .clear();
        ui.canvas.tooltip(null);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        GL11.glTranslatef(ui.canvas.originX(), ui.canvas.originY(), 0);
        GL11.glScalef(ui.canvas.scale(), ui.canvas.scale(), 1);
        try {
            ui.canvas.skin(
                "cloth",
                0,
                0,
                ui.canvas.layout()
                    .width(),
                ui.canvas.layout()
                    .height());
            drawRect(
                6,
                29,
                ui.canvas.layout()
                    .width() - 6,
                ui.canvas.layout()
                    .height() - 8,
                0xed142a32);
            ui.canvas.skin(
                "panel",
                6,
                6,
                ui.canvas.layout()
                    .width() - 12,
                22);
            if (ui.session.backgroundBusy() && !ui.diagnostics.captureRenderSmokeAction()) {
                if (HologramBackgroundTasks.awaitingSourceReview() && ui.library.discardReview()) {
                    ui.library.drawBlueprintSwitchReview(mx, my);
                    ui.canvas.button(990, 470, 310, 170, 22, "后台继续 · Esc / E", true, mx, my);
                    ui.canvas.incrementRenderGeneration(1, true);
                    return;
                }
                if (HologramBackgroundTasks.awaitingReview() && ui.ae.aeReview() != null) {
                    ui.ae.drawAeReview(mx, my);
                    ui.canvas.button(990, 470, 310, 170, 22, "后台继续 · Esc / E", true, mx, my);
                    ui.canvas.incrementRenderGeneration(1, true);
                    return;
                }
            }
            if (ui.session.areaImporting() || ui.session.state().data.getBoolean("snapshotPending")
                || ui.session.state().data.getBoolean("placementValidating")) {
                ui.router.drawImportBusy(mx, my);
                if (ui.session.backgroundBusy() && !ui.diagnostics.captureRenderSmokeAction()) drawTaskStatus(mx, my);
                ui.canvas.incrementRenderGeneration(1, true);
                return;
            }
            ui.canvas.text(
                ui.review.placementReviewActive() ? "蓝图放置预检 · " + ui.session.state().data.getString("virtualPresetName")
                    : ui.session.state().data.getBoolean("virtual")
                        ? (ui.library.libraryDrawer() ? "" : "结构调整 · ")
                            + ui.session.state().data.getString("virtualPresetName")
                        : ui.session.state().data.getString("title"),
                142,
                13,
                HologramLibraryClient.dedicated() && ui.library.libraryDrawer()
                    && HologramLibraryClient.INSTANCE.config()
                        .getBoolean("admin") ? 122 : 197,
                0xf5e4b9);
            if (!ui.library.libraryDrawer()) ui.canvas.button(
                60,
                351,
                9,
                117,
                17,
                ui.session.state().data.getBoolean("virtual") ? "结构等级 / 信道" : "等级 / 信道设置",
                !ui.session.state().data.getBoolean("virtual") || !ui.session.state().data.getString("activeStructure")
                    .isEmpty(),
                mx,
                my);
            if (!ui.review.confirmation()) ui.preview.drawPreview(mx, my);
            GL11.glDepthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (!ui.library.libraryDrawer()) {
                CanvasTransform configuration = ui.canvas.layout()
                    .configuration();
                try (HologramUiCanvas.Space ignored = ui.canvas.space(configuration)) {
                    int configMouseX = ui.canvas.localMouseX(configuration);
                    int configMouseY = ui.canvas.localMouseY(configuration);
                    if (!ui.canvas.layout()
                        .collapsed()) ui.config.drawConfig(configMouseX, configMouseY);
                    else {
                        ui.canvas.skin("panel", 280, 60, 232, 18);
                        ui.canvas.text("目标属性", 286, 65, 200, 0xf5e4b9);
                    }
                    ui.canvas.text(
                        ui.canvas.layout()
                            .collapsed() ? "+" : "−",
                        497,
                        65,
                        10,
                        0xf5e4b9);
                }
                CanvasTransform footer = new CanvasTransform(
                    0,
                    0,
                    ui.canvas.layout()
                        .width() - 680,
                    ui.canvas.layout()
                        .height() - 340,
                    1);
                try (HologramUiCanvas.Space ignored = ui.canvas.space(footer)) {
                    ui.config.drawFooter(ui.canvas.localMouseX(footer), ui.canvas.localMouseY(footer));
                }
                if (!ui.canvas.layout()
                    .collapsed()) {
                    CanvasTransform supplement = new CanvasTransform(
                        452,
                        60,
                        configuration.toScreenX(280),
                        configuration.toScreenY(60),
                        1);
                    try (HologramUiCanvas.Space ignored = ui.canvas.space(supplement)) {
                        int sx = ui.canvas.localMouseX(supplement), sy = ui.canvas.localMouseY(supplement);
                        HologramState.Cell activeCell = ui.session.selectedCell();
                        String activeStructureId = ui.config.selectedStructureId();
                        if (activeCell != null && !activeStructureId.isEmpty()) {
                            ui.canvas.button(
                                163,
                                452,
                                281,
                                107,
                                17,
                                activeCell.sourceMissing ? "来源缺失" : "结构编辑",
                                ui.session.editable(),
                                sx,
                                sy);
                            ui.canvas.button(
                                164,
                                563,
                                281,
                                109,
                                17,
                                ui.config.structureEnabled(activeStructureId) ? "施工 ✓" : "施工 ×",
                                ui.session.editable(),
                                sx,
                                sy);
                        }
                        if (!ui.session.state().data.getString("activeStructure")
                            .isEmpty())
                            ui.canvas.button(165, 452, 300, 220, 13, "返回区域总览", ui.session.editable(), sx, sy);
                    }
                }
                if (ui.session.state().data.getBoolean("virtual")
                    && !ui.session.state().data.getBoolean("virtualPlacement"))
                    ui.canvas.button(
                        297,
                        252,
                        300,
                        184,
                        13,
                        "重新进行蓝图预设",
                        ui.session.editable() && !ui.session.state().cells.isEmpty(),
                        mx,
                        my);
            }
            if (!ui.library.libraryDrawer()) {
                ui.canvas.button(
                    108,
                    12,
                    34,
                    40,
                    17,
                    "撤销",
                    ui.session.editable() && ui.session.state().data.getBoolean("editCanUndo"),
                    mx,
                    my);
                ui.canvas.button(
                    109,
                    54,
                    34,
                    40,
                    17,
                    "重做",
                    ui.session.editable() && ui.session.state().data.getBoolean("editCanRedo"),
                    mx,
                    my);
                ui.canvas.button(110, 96, 34, 60, 17, "快捷键", true, mx, my);
            }
            if (!ui.library.libraryDrawer() && ui.bindings.networkGroups()
                .tagCount() > 0) ui.canvas.button(
                    196,
                    388,
                    34,
                    86,
                    17,
                    ui.bindings.selectedNetworkGroup()
                        .isEmpty()
                            ? "量子绑定 · " + ui.bindings.networkGroups()
                                .tagCount()
                            : "网络组 " + ui.bindings.selectedNetworkGroup(),
                    ui.session.editable(),
                    mx,
                    my);
            ui.canvas.button(178, 478, 9, 92, 17, "框选区域", ui.session.editable(), mx, my);
            ui.canvas.button(330, 320, 34, 102, 17, "清空工作区", ui.session.editable(), mx, my);
            ui.canvas.button(331, 158, 34, 76, 17, ui.textBoxes.label(false), ui.textBoxes.allowed(false), mx, my);
            ui.canvas.button(332, 238, 34, 78, 17, ui.textBoxes.label(true), ui.textBoxes.allowed(true), mx, my);
            if (!ui.library.libraryDrawer())
                ui.canvas.button(179, 578, 9, 94, 17, "AE 容器标记", ui.ae.canOpenAePreview(), mx, my);
            CanvasTransform overlays = overlaySpace();
            try (HologramUiCanvas.Space ignored = ui.canvas.space(overlays)) {
                mx = ui.canvas.localMouseX(overlays);
                my = ui.canvas.localMouseY(overlays);
                if (ui.config.drawer() || ui.preview.inventory()
                    || ui.position.positionDrawer()
                    || ui.config.sourceDrawer()
                    || !ui.config.optionChannel()
                        .isEmpty()) {
                    ui.canvas.controls()
                        .removeIf(control -> control.x >= 157);
                    ui.canvas.tooltip(null);
                    GL11.glDepthMask(true);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                }
                if (ui.config.drawer()) {
                    ui.config.drawChannels(mx, my);
                }
                if (ui.preview.inventory()) ui.preview.drawInventory(mx, my);
                if (ui.position.positionDrawer()) ui.position.drawPosition(mx, my);
                if (ui.config.sourceDrawer()) ui.config.drawSources(mx, my);
                if (!ui.config.optionChannel()
                    .isEmpty()) {
                    ui.canvas.controls()
                        .clear();
                    ui.config.drawOptions(mx, my);
                }
                if (ui.config.hatchDrawer() || ui.config.presetDrawer()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    GL11.glDepthMask(true);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    if (ui.config.hatchDrawer()) ui.config.drawHatches(mx, my);
                    else ui.config.drawPreset(mx, my);
                }
                if (ui.shortcuts.shortcutsDrawer()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.shortcuts.drawShortcuts(mx, my);
                }
                if (ui.library.libraryDrawer()) {
                    ui.canvas.tooltip(null);
                    ui.library.drawLibrary(mx, my);
                }
                ui.router.drawTabs(mx, my);
                if (ui.annotations.promptDrawer() || ui.annotations.versionsDrawer()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    if (ui.annotations.promptDrawer()) ui.annotations.drawPromptEditor(mx, my);
                    else ui.annotations.drawVersions(mx, my);
                }
                if (ui.config.resetReview()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.config.drawResetReview(mx, my);
                }
                if (!ui.library.deletePresetId()
                    .isEmpty()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.library.drawDeleteReview(mx, my);
                }
                if (ui.library.libraryAdminDrawer()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.library.drawLibraryAdmin(mx, my);
                }
                if (ui.review.confirmation()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    GL11.glDepthMask(true);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    ui.review.drawConfirmation(mx, my);
                }
                if (ui.config.roleDropdown() && !ui.review.confirmation()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.config.drawRoleDropdown(mx, my);
                }
                if (ui.nbt.nbtDrawer()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.nbt.drawNbtEditor(mx, my);
                }
                int statusTop = modalVisible() ? 318
                    : ui.canvas.layout()
                        .height() - 22;
                int guideTop = modalVisible() ? 306
                    : ui.canvas.layout()
                        .height() - 36;
                if (!ui.review.confirmation()) {
                    HologramProgress progress = ui.session.progress();
                    String status = progress != null ? HologramProgressView.summary(progress, System.nanoTime())
                        : ui.session.pendingSequence() >= 0 ? "等待服务端校验…" : ui.review.completionSummary();
                    int statusX = ui.library.libraryDrawer() ? 12 : 35;
                    int statusWidth = ui.library.libraryDrawer() ? ui.canvas.layout()
                        .width() - 24
                        : (modalVisible() ? HologramUiCanvas.viewportWidth
                            : ui.canvas.layout()
                                .width() - 250)
                            - 12;
                    drawRect(statusX - 3, statusTop, statusX + statusWidth + 3, statusTop + 15, 0xff142a32);
                    if (progress != null && ui.session.notice()
                        .isEmpty())
                        HologramProgressView.draw(ui.host.font(), progress, statusX, statusTop + 3, statusWidth, 0);
                    else ui.canvas.text(
                        ui.session.notice()
                            .isEmpty() ? status : ui.session.notice(),
                        statusX,
                        statusTop + 3,
                        statusWidth,
                        ui.session.notice()
                            .isEmpty() ? 0xffbd91 : 0xff9292);
                    if (mx >= statusX && mx < statusX + statusWidth && my >= statusTop && my < statusTop + 15) {
                        ui.canvas.tooltip(
                            new ArrayList<>(
                                ui.host.font()
                                    .listFormattedStringToWidth(
                                        ui.session.notice()
                                            .isEmpty() ? status : ui.session.notice(),
                                        390)));
                        if (!status.equals(ui.session.state().data.getString("status"))) ui.canvas.tooltip()
                            .addAll(
                                ui.host.font()
                                    .listFormattedStringToWidth(ui.session.state().data.getString("status"), 390));
                        ui.canvas.tooltip()
                            .add(
                                "材料缺口 " + ui.session.state().data.getInteger("missing")
                                    + " · 保护 "
                                    + ui.session.state().data.getInteger("protected"));
                    }
                }
                if (ui.review.confirmation()) {
                    drawRect(32, statusTop, HologramUiCanvas.viewportWidth + 26, statusTop + 15, 0xff142a32);
                    HologramProgress progress = ui.session.progress();
                    if (progress != null) HologramProgressView.draw(
                        ui.host.font(),
                        progress,
                        35,
                        statusTop + 3,
                        (modalVisible() ? HologramUiCanvas.viewportWidth
                            : ui.canvas.layout()
                                .width() - 250)
                            - 12,
                        0);
                    else {
                        String result = ui.session.notice()
                            .isEmpty() ? ui.review.completionSummary() : ui.session.notice();
                        ui.canvas.text(
                            result,
                            35,
                            statusTop + 3,
                            (modalVisible() ? HologramUiCanvas.viewportWidth
                                : ui.canvas.layout()
                                    .width() - 250)
                                - 12,
                            0xffbd91);
                        if (mx >= 35 && mx < HologramUiCanvas.viewportWidth + 26
                            && my >= statusTop
                            && my < statusTop + 15)
                            ui.canvas.tooltip(
                                new ArrayList<>(
                                    ui.host.font()
                                        .listFormattedStringToWidth(result, 390)));
                    }
                }
                String guidance = HologramGuidance.next(
                    ui.session.state(),
                    ui.preview.selected(),
                    ui.library.libraryDrawer(),
                    ui.session.pendingSequence() >= 0);
                ui.review.lastFooterScope(ui.review.constructionScopeLabel(false));
                String footerGuide = !ui.library.libraryDrawer() && !ui.review.confirmation()
                    && !ui.session.state().cells.isEmpty()
                        ? ui.review.lastFooterScope() + " · "
                            + (ui.session.state().data.getInteger("job") == 1
                                || ui.session.state().data.getInteger("job") == 2 ? guidance
                                    : ui.review.constructionScopeHint())
                        : guidance;
                if (!ui.library.libraryDrawer() && !ui.review.confirmation()
                    && HologramGuidance.authorWorkspace(ui.session.state())
                    && ui.session.state().data.getBoolean("virtual"))
                    footerGuide = ui.memory.initialEditHint() ? "可微调蓝图结构；点击、选格或修改后前往蓝图设置" : "结构调整完成后，前往蓝图设置处理并保存";
                ui.canvas.text(
                    footerGuide,
                    35,
                    guideTop,
                    (modalVisible() ? HologramUiCanvas.viewportWidth
                        : ui.canvas.layout()
                            .width() - 24)
                        - 4,
                    0x8bdddf);
                if (mx >= 35 && mx < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth
                    && my >= guideTop - 2
                    && my < guideTop + 11) {
                    ui.canvas.tooltip(
                        new ArrayList<>(
                            ui.host.font()
                                .listFormattedStringToWidth(footerGuide, 390)));
                    ui.canvas.tooltip()
                        .addAll(
                            ui.host.font()
                                .listFormattedStringToWidth(guidance, 390));
                }
                if (ui.ae.aeReview() != null) ui.ae.drawAeReview(mx, my);
                if (ui.ae.aeReviewNavigation() != null) ui.ae.drawAeReviewNavigation(mx, my);
                if (ui.bindings.networkDrawer()) ui.bindings.drawNetworkBindings(mx, my);
                if (ui.identity.deidentificationDrawer()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.identity.drawDeidentification(mx, my);
                }
                if (ui.processing.processingVisible()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.processing.drawProcessingFlow(mx, my);
                }
                if (ui.library.discardReview() || ui.library.overwriteReview()
                    || ui.processing.processingPresetSaveReview()) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    ui.library.drawBlueprintSwitchReview(mx, my);
                }
                if (ui.session.backgroundBusy() && !ui.diagnostics.captureRenderSmokeAction()) drawTaskStatus(mx, my);
                if (!ui.session.isTutorial()) ui.shortcuts.drawIdleHints();
                if (ui.canvas.tooltip() != null && !ui.library.saveGuidanceOpen() && !ui.review.materialDialogOpen())
                    ui.host.renderTooltip(ui.canvas.tooltip(), mx, my, ui.host.font());
            }
            if (!modalVisible()) ui.tools.draw(ui.canvas.rootMouseX(), ui.canvas.rootMouseY());
            ui.guide.draw(ui.canvas.rootMouseX(), ui.canvas.rootMouseY());
            ui.preview.drawEmptyVoxelPreview(ui.canvas.rootMouseX(), ui.canvas.rootMouseY());
            if (ui.annotations.annotationInteractive())
                ui.annotations.drawAnnotations(ui.canvas.rootMouseX(), ui.canvas.rootMouseY());
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
        ui.canvas.incrementRenderGeneration(1, true);
    }

    private void drawTaskStatus(int mx, int my) {
        int top = HologramUiCanvas.DESIGN_H - 25;
        drawRect(9, top, HologramUiCanvas.DESIGN_W - 9, top + 24, 0xff142a32);
        HologramProgress progress = HologramBackgroundTasks.progress();
        if (progress != null) HologramProgressView.draw(ui.host.font(), progress, 14, top + 3, 430, 5);
        else ui.canvas.text(HologramBackgroundTasks.status(), 14, top + 7, 430, 0xe5c488);
        ui.canvas.button(990, 451, top + 3, 70, 18, "后台继续", true, mx, my);
        ui.canvas.button(
            992,
            525,
            top + 3,
            70,
            18,
            HologramBackgroundTasks.pauseLabel(),
            HologramBackgroundTasks.pauseAvailable(),
            mx,
            my);
        ui.canvas.button(991, 599, top + 3, 70, 18, "取消任务", HologramBackgroundTasks.canCancel(), mx, my);
    }

    private boolean modalVisible() {
        return (!ui.preview.previewInteractive() || ui.library.libraryAdminDrawer() || ui.bindings.networkDrawer())
            && !ui.session.areaImporting();
    }

    private CanvasTransform overlaySpace() {
        return modalVisible() ? ui.canvas.layout()
            .modal() : CanvasTransform.identity();
    }

    void drawTabs(int mx, int my) {
        for (int i = 0; i < 2; i++) {
            ui.canvas.button(
                148 + i,
                12 + i * 62,
                9,
                58,
                17,
                i == 0 ? "\u7ed3\u6784\u8c03\u6574" : "\u84dd\u56fe\u8bbe\u7f6e",
                true,
                mx,
                my);
        }
    }

    void mouseClicked(int x, int y, int button) {
        if (ui.tutorial.blockInput()) return;
        ui.shortcuts.activity();
        int rootX = ui.canvas.localX(x), rootY = ui.canvas.localY(y);
        if (!modalVisible() && !ui.library.libraryDrawer()
            && ui.canvas.layout()
                .press(rootX, rootY, button))
            return;
        try (HologramUiCanvas.PointerSpace ignored = ui.canvas.pointerSpace(overlaySpace())) {
            int mx = ui.canvas.localX(x), my = ui.canvas.localY(y);
            if (ui.session.backgroundBusy() && !ui.diagnostics.captureRenderSmokeAction()) {
                if (button == 0) for (Control control : ui.canvas.controls())
                    if (control.enabled && control.contains(mx, my)) activate(control.id);
                return;
            }
            if (ui.library.consumeSwitchReviewMouse(mx, my, button)) return;
            if (ui.processing.consumeAuthoringModalMouse(mx, my, button)) return;
            if (button >= 0 && (ui.canvas.inViewport(mx, my) || ui.canvas.controls()
                .stream()
                .anyMatch(control -> control.enabled && control.contains(mx, my))))
                ui.memory.initialEditHint(ui.memory.savedEditHint(false));
            ui.memory.rememberGuidance();
            // The visible modal owns input, including disabled controls and empty panel space.
            if (HologramUiInputChain.consume(
                () -> ui.identity.consumeIdentityMouse(mx, my, button),
                () -> ui.library.consumeAdminMouse(mx, my, button),
                () -> ui.bindings.consumeBindingsMouse(mx, my, button),
                () -> ui.preview.consumeImportMouse(mx, my, button),
                () -> ui.ae.consumeNavigationMouse(mx, my, button),
                () -> ui.ae.consumeReviewMouse(mx, my, button),
                () -> ui.review.consumeConfirmationMouse(mx, my, button),
                () -> ui.library.consumeDeleteReviewMouse(mx, my, button),
                () -> ui.nbt.consumeEditorMouse(mx, my, button),
                () -> ui.annotations.consumeAnnotationModalMouse(mx, my, button),
                () -> ui.config.consumeRoleDropdownMouse(mx, my, button))) return;
            if (ui.library.libraryDrawer() && !ui.config.resetReview()) ui.library.presetNameField()
                .mouseClicked(mx, my, button);
            if (button == 0 && ui.annotations.annotationInteractive() && ui.annotations.toggleAnnotationAt(mx, my))
                return;
            if (ui.library.consumeBrowserChooserMouse(mx, my, button)) return;
            if (ui.config.drawer() && !ui.review.confirmation()
                && ui.config.optionChannel()
                    .isEmpty()) {
                ui.config.mainField()
                    .mouseClicked(mx, my, button);
                ui.config.channelNameField()
                    .mouseClicked(mx, my, button);
                ui.config.channelValueField()
                    .mouseClicked(mx, my, button);
            }
            if (!ui.config.optionChannel()
                .isEmpty() && !ui.review.confirmation()) ui.config.optionValueField()
                    .mouseClicked(mx, my, button);
            if (ui.position.positionDrawer() && !ui.review.confirmation()) {
                ui.position.positionXField()
                    .mouseClicked(mx, my, button);
                ui.position.positionYField()
                    .mouseClicked(mx, my, button);
                ui.position.positionZField()
                    .mouseClicked(mx, my, button);
            }
            if (ui.preview.consumeViewportNavigationMouse(mx, my, button)) return;
            if (ui.tools.consumeMouse(mx, my, button)) return;
            if (ui.preview.consumeViewportRightMouse(mx, my, button)) return;
            if (ui.preview.consumeViewportLeftMouse(mx, my, button)) return;
            ui.preview.consumeViewportMiddleMouse(mx, my, button);
        }
    }

    void mouseClickMove(int x, int y, int button, long elapsed) {
        if (ui.tutorial.blockInput()) return;
        ui.shortcuts.activity();
        if (ui.tools.consumeDrag(ui.canvas.localX(x), ui.canvas.localY(y), button)) return;
        if (ui.session.backgroundBusy()) return;
        if (ui.canvas.layout()
            .drag(ui.canvas.localX(x), ui.canvas.localY(y))) return;
        ui.preview.mouseClickMove(x, y, button, elapsed);
    }

    void mouseMovedOrUp(int x, int y, int button) {
        if (ui.tutorial.blockInput()) return;
        ui.shortcuts.activity();
        if (ui.tools.consumeRelease(ui.canvas.localX(x), ui.canvas.localY(y), button)) return;
        if (ui.canvas.layout()
            .dragging()) {
            ui.canvas.layout()
                .release();
            return;
        }
        if (ui.session.backgroundBusy()) return;
        ui.preview.mouseMovedOrUp(x, y, button);
    }

    void handleMouseInput() {
        if (ui.tutorial.blockInput()) return;
        int eventWheel = Mouse.getEventDWheel();
        if (eventWheel == 0) return;
        long event = Mouse.getEventNanoseconds();
        if (wheelEvent == event) return;
        wheelEvent = event;
        try (HologramUiCanvas.PointerSpace ignored = ui.canvas.pointerSpace(overlaySpace())) {
            if (ui.review.materialDialogOpen()) return;
            ui.shortcuts.activity();
            if (ui.session.backgroundBusy()) return;

            if (ui.bindings.networkDrawer()) return;
            if (ui.session.areaImporting() || ui.ae.aeReviewNavigation() != null
                || ui.identity.deidentificationDrawer()) return;
            int wheel = Mouse.getEventDWheel();
            if (wheel == 0) return;
            if (ui.processing.consumeProcessingWheel(wheel)) return;
            if (ui.library.discardReview() || ui.library.overwriteReview()
                || ui.processing.processingPresetSaveReview()) return;
            if (ui.ae.consumeReviewWheel(wheel)) return;
            if (ui.nbt.consumeEditorWheel(wheel)) return;
            if (ui.annotations.consumeVersionsWheel(wheel)) return;
            if (ui.annotations.promptDrawer()) return;
            if (ui.config.resetReview()) return;
            int mx = ui.canvas.localX(Mouse.getEventX() * ui.host.screenWidth() / ui.host.minecraft().displayWidth),
                my = ui.canvas.localY(
                    ui.host.screenHeight()
                        - Mouse.getEventY() * ui.host.screenHeight() / ui.host.minecraft().displayHeight
                        - 1);
            if (ui.tools.consumeWheel(mx, my, wheel)) return;
            if (ui.annotations.consumePinnedAnnotationWheel(wheel, mx, my)) return;
            if (ui.config.consumeRoleDropdownWheel(wheel)) return;
            if (ui.library.consumeBrowserWheel(wheel, mx, my)) return;
            if (ui.position.positionDrawer() || ui.config.sourceDrawer()
                || ui.config.presetDrawer()
                || ui.shortcuts.shortcutsDrawer()) return;
            if (ui.preview.consumeCompactCandidatesWheel(wheel, mx, my)) return;
            if (ui.config.consumeHatchWheel(wheel)) return;
            if (ui.review.confirmation()) {
                int reviewRows = HologramPlacementConfirmation.cacheResources(ui.session.state().data)
                    .tagCount() > 0 ? 2 : 3;
                ui.review
                    .materialOffset(
                        Math.max(
                            0,
                            Math.min(
                                Math.max(
                                    0,
                                    (ui.review.resourceReview()
                                        ? ui.session.state().data.getTagList("blueprintResources", 10)
                                            .tagCount()
                                        : ui.session.state().materials.size()) - reviewRows),
                                ui.review.materialOffset() + (wheel > 0 ? -1 : 1))));
            } else if (ui.preview.inventory() && ui.preview.sourceTargets() && mx >= 157) {
                int count = ui.session.state().data.getTagList("materialChoices", 10)
                    .tagCount();
                int maxOffset = Math.max(0, (count - 36 + 8) / 9 * 9);
                ui.preview
                    .sourceOffset(Math.max(0, Math.min(maxOffset, ui.preview.sourceOffset() + (wheel > 0 ? -9 : 9))));
            } else if (!ui.config.optionChannel()
                .isEmpty()) {
                    NBTTagCompound cap = ui.config.capability(ui.config.optionChannel());
                    ui.config.drawerOffset(
                        Math.max(
                            0,
                            Math.min(
                                Math.max(
                                    0,
                                    cap == null ? 0
                                        : cap.getTagList("options", 10)
                                            .tagCount() - 7),
                                ui.config.drawerOffset() + (wheel > 0 ? -1 : 1))));
                } else if (ui.config.drawer()) {
                    ui.config.drawerOffset(
                        Math.max(
                            0,
                            Math.min(
                                Math.max(
                                    0,
                                    ui.config.capabilities()
                                        .tagCount() - 4),
                                ui.config.drawerOffset() + (wheel > 0 ? -1 : 1))));
                } else if (ui.canvas.inViewport(mx, my) && !ui.preview.previewBlockedAt(mx, my))
                    ui.preview.zoom(ui.preview.zoom() * (wheel > 0 ? 1.1 : 1 / 1.1));
        }
    }

    /** The host consumes the physical return key before MUI's world-wide Escape fallback. */
    void back() {
        if (ui.tutorial.active()) {
            ui.tutorial.closeWithoutProgress();
            return;
        }
        if (ui.shell.hasModalPanel() && ui.shell.consumeBackLayer()) return;
        keyTyped('\0', Keyboard.KEY_ESCAPE);
    }

    void keyTyped(char c, int key) {
        if (ui.tutorial.blockInput()) return;
        ui.shortcuts.activity();
        if (ui.textBoxes.key(key)) return;
        if (ui.review.consumeMaterialDialogKey(key)) return;
        if (ui.library.consumeSaveGuidanceKey(key)) return;
        if (ui.session.backgroundBusy() && !ui.diagnostics.captureRenderSmokeAction()) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) closeRoot();
            return;
        }
        if (ui.processing.consumeProcessingKey(c, key)) return;
        if (ui.library.consumeSwitchReviewKey(c, key)) return;
        ui.memory.initialEditHint(ui.memory.savedEditHint(false));
        ui.memory.rememberGuidance();
        if (ui.identity.consumeIdentityKey(c, key)) return;
        if (ui.library.consumeAdminKey(c, key)) return;
        if (ui.bindings.consumeBindingsKey(c, key)) return;
        if (ui.preview.consumeImportKey(c, key)) return;
        if (ui.ae.consumeNavigationKey(c, key)) return;
        if (ui.ae.consumeReviewKey(c, key)) return;
        if (key == Keyboard.KEY_E && !ui.router.textFieldFocused()) key = Keyboard.KEY_ESCAPE;
        if (ui.library.consumeDeleteReviewKey(c, key)) return;
        if (ui.nbt.consumeEditorKey(c, key)) return;
        if (ui.annotations.consumeAnnotationModalKey(c, key)) return;
        if (ui.shortcuts.consumeBindingKey(c, key)) return;
        if (!ui.router.textFieldFocused() && ui.tools.consumeKey(c, key)) return;
        if (key == Keyboard.KEY_ESCAPE) {
            if (ui.session.isLocalAuthor() && ui.session.author()
                .structures()
                .busy()) {
                ui.session.author()
                    .structures()
                    .cancel();
                return;
            }
            if (!ui.annotations.pinnedAnnotation()
                .isEmpty() && ui.annotations.annotationInteractive()) {
                ui.annotations.pinnedAnnotation("");
                return;
            }
            if (ui.preview.gizmo()
                .axis() >= 0) {
                ui.preview.gizmo()
                    .cancel();
                return;
            }
            if (!ui.preview.editQueue()
                .isEmpty()) {
                ui.preview.cancelQueuedEdits("已取消等待中的编辑");
                return;
            }
            if (ui.host.parent()
                .getScreen()
                .getContext()
                .hasDraggable()) {
                ui.host.parent()
                    .getScreen()
                    .getContext()
                    .dropDraggable(true);
                return;
            }
            if (ui.config.roleDropdown()) {
                ui.config.roleDropdown(false);
                return;
            }
            if (ui.config.resetReview()) {
                ui.config.resetReview(false);
                return;
            }
            if (ui.shortcuts.shortcutsDrawer()) {
                ui.shortcuts.shortcutsDrawer(false);
                return;
            }
            if (ui.shell.consumeBackLayer()) return;
            if (ui.preview.inventoryDragging() || ui.preview.inventorySlot() >= 0 || ui.preview.sourceChoice() >= 0) {
                ui.preview.inventoryDragging(false);
                ui.preview.inventorySlot(ui.preview.sourceChoice(-1));
            } else if (ui.preview.inventory()) ui.preview.inventory(false);
            else if (ui.config.hatchDrawer()) ui.config.hatchDrawer(false);
            else if (ui.config.presetDrawer()) ui.config.presetDrawer(false);
            else if (ui.review.confirmation()) ui.review.confirmation(false);
            else if (ui.position.positionDrawer()) ui.position.positionDrawer(false);
            else if (ui.config.sourceDrawer()) ui.config.sourceDrawer(false);
            else if (!ui.config.optionChannel()
                .isEmpty()) ui.config.optionChannel("");
            else if (ui.config.drawer()) ui.config.drawer(false);
            else ui.router.closeRoot();
            return;
        }
        if (ui.config.resetReview()) return;
        if (ui.library.consumeBrowserKey(c, key)) return;
        if (ui.preview.consumeCameraKey(c, key)) return;

        if (key == Keyboard.KEY_T && !ui.router.textFieldFocused()) return;
        if (ui.position.positionDrawer() && !ui.review.confirmation()) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) ui.router.activate(76);
            else {
                ui.position.positionXField()
                    .textboxKeyTyped(c, key);
                ui.position.positionYField()
                    .textboxKeyTyped(c, key);
                ui.position.positionZField()
                    .textboxKeyTyped(c, key);
            }
        } else if (!ui.config.optionChannel()
            .isEmpty() && !ui.review.confirmation()) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) ui.router.activate(68);
                else ui.config.optionValueField()
                    .textboxKeyTyped(c, key);
            } else if (ui.config.drawer() && !ui.review.confirmation()) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) ui.router.activate(
                    (ui.config.channelNameField()
                        .isFocused()
                        || ui.config.channelValueField()
                            .isFocused()) ? 67 : 30);
                else if (ui.config.channelNameField()
                    .isFocused())
                    ui.config.channelNameField()
                        .textboxKeyTyped(c, key);
                else if (ui.config.channelValueField()
                    .isFocused())
                    ui.config.channelValueField()
                        .textboxKeyTyped(c, key);
                else ui.config.mainField()
                    .textboxKeyTyped(c, key);
            }
    }

    void activate(int id) {
        if (ui.tutorial.activate(id) || ui.guide.activate(id) || ui.tools.activateControl(id)) return;
        if (id == HologramEditorControlIds.LIBRARY) {
            ui.library.activateControl(id);
            return;
        }
        if (id >= HologramEditorControlIds.SHELL && id < HologramEditorControlIds.LIBRARY + 1000) return;
        if (id == 331 || id == 332) {
            ui.textBoxes.activate(id == 332);
            return;
        }
        if (id == 330) {
            if (ui.session.isLocalAuthor()) ui.session.localAction("clearWorkspace", null);
            else ui.session.send("clearWorkspace", null, true);
            return;
        }
        if (id == 193 && (ui.session.areaImporting() || ui.session.state().data.getBoolean("snapshotPending"))) {
            closeRoot();
            return;
        }
        if (ui.session.backgroundBusy() && !ui.diagnostics.captureRenderSmokeAction()) {
            if (id == 990) closeRoot();
            else if (id == 991) HologramBackgroundTasks.cancel();
            else if (id == 992) HologramBackgroundTasks.togglePause();
            else if (HologramBackgroundTasks.awaitingSourceReview()) {
                if (id == 224) ui.library.confirmDiscard(id);
                else if (id == 225) ui.library.cancelSwitchReview(id);
            } else if (HologramBackgroundTasks.awaitingReview() && HologramBackgroundTasks.reviewControl(id))
                ui.ae.activateAeReview(id);
            return;
        }
        if (id == HologramEditorControlIds.BLUEPRINT_ROTATE) {
            ui.shell.rotateBlueprint();
            return;
        }
        if (id == 166 || id == 146) ui.memory.cellEditHint(false);
        if (ui.library.cancelSwitchReview(id)) return;
        if (ui.processing.confirmPresetSave(id)) return;
        if (ui.library.confirmOverwrite(id)) return;
        if (ui.library.confirmDiscard(id)) return;
        if (ui.processing.prepareProcessingSave(id)) return;
        if (ui.library.toggleOverwriteMode(id)) return;
        if (ui.preview.rotateCameraControl(id)) return;
        if (ui.processing.activateProcessing(id)) return;
        if (ui.library.prepareLibraryNavigation(id)) return;
        if (ui.identity.activateDeidentification(id)) return;
        if (ui.bindings.consumeNativeControl(id)) return;
        if (ui.library.activateClientLibrary(id)) return;
        if (ui.nbt.resetNetworksControl(id)) return;
        if (ui.bindings.openBindingsControl(id)) return;
        if (ui.preview.consumeImportControl(id)) return;
        if (ui.ae.consumeNavigationControl(id)) return;
        if (ui.ae.consumeReviewControl(id)) return;
        if (ui.ae.inspectControl(id)) return;
        ui.session.notice("");
        if (ui.ae.openPreviewControl(id)) return;
        if (ui.nbt.selectEntryControl(id)) return;
        if (ui.config.selectRoleControl(id)) return;
        if (ui.shortcuts.captureBindingControl(id)) return;
        if (ui.library.selectServerPresetRow(id)) return;
        if (ui.preview.pinCandidateControl(id)) return;
        if (ui.config.chooseOptionControl(id)) return;
        if (ui.config.openOptionControl(id)) return;
        if (ui.config.selectScopeControl(id)) return;
        if (ui.config.selectModeControl(id)) return;
        if (ui.nbt.activateControl(id)) return;
        if (ui.preview.activateControl(id)) return;
        if (ui.review.activateControl(id)) return;
        if (ui.processing.activateControl(id)) return;
        if (ui.config.activateControl(id)) return;
        if (ui.library.activateControl(id)) return;
        if (ui.annotations.activateControl(id)) return;
        if (ui.shortcuts.activateControl(id)) return;
        if (ui.position.activateControl(id)) return;
    }

    boolean textFieldFocused() {
        if (ui.shell.built()) return ui.shell.fieldFocused();
        return ui.nbt.nbtDrawer() && ui.nbt.nbtValueField() != null
            && ui.nbt.nbtValueField()
                .isFocused()
            || ui.processing.processingFlow() != null && ui.processing.processingPresetField() != null
                && ui.processing.processingPresetField()
                    .isFocused()
            || ui.bindings.networkDrawer() && ui.bindings.networkHintField() != null
                && ui.bindings.networkHintField()
                    .isFocused()
            || ui.library.libraryAdminDrawer() && (ui.library.libraryUploadField()
                .isFocused()
                || ui.library.libraryDownloadField()
                    .isFocused()
                || ui.library.libraryQuotaField()
                    .isFocused())
            || ui.processing.processingPresetSaveReview() && ui.processing.processingPresetField() != null
                && ui.processing.processingPresetField()
                    .isFocused()
            || ui.annotations.promptDrawer() && ui.annotations.promptField()
                .isFocused()
            || ui.library.libraryDrawer() && ui.library.presetNameField()
                .isFocused()
            || ui.config.drawer() && (ui.config.mainField()
                .isFocused()
                || ui.config.channelNameField()
                    .isFocused()
                || ui.config.channelValueField()
                    .isFocused())
            || !ui.config.optionChannel()
                .isEmpty() && ui.config.optionValueField()
                    .isFocused()
            || ui.position.positionDrawer() && (ui.position.positionXField()
                .isFocused()
                || ui.position.positionYField()
                    .isFocused()
                || ui.position.positionZField()
                    .isFocused());
    }
}
