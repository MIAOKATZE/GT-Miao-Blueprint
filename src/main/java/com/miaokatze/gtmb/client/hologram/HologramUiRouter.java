package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;

import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/** Internal router workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiRouter {

    private final HologramProgress.Tracker importProgress = new HologramProgress.Tracker();

    private final HologramUiModules ui;

    HologramUiRouter(HologramUiModules ui) {
        this.ui = ui;
    }

    void initGui() {
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
        ui.canvas.text("正在导入框选区域", 142, 13, 360, 0xf5e4b9);
        drawRect(30, 62, 650, 282, 0xff172f36);
        ui.canvas.text("正在导入区域 · 请稍候", 50, 78, 580, 0xffe59b);
        String stage = "declarations".equals(ui.session.state().data.getString("areaImportStage")) ? "正在解析结构与配置…"
            : "正在读取方块与配置…";
        ui.canvas.text(stage, 50, 105, 580, 0x8bdddf);
        ui.canvas.text("导入完成前，暂不能编辑、保存或放置蓝图。", 50, 123, 580, 0xe5c28d);
        ui.canvas.text("取消后保留导入前的蓝图。", 50, 141, 580, 0xa9d6cf);
        HologramProgress progress = ui.session.progress();
        if (progress == null || !progress.phase.startsWith("area.")) progress = importProgress.update(
            ui.session.state().session,
            "declarations".equals(ui.session.state().data.getString("areaImportStage")) ? "area.declarations"
                : "area.blocks",
            ui.session.state().data.getInteger("areaImportProgress"),
            100,
            "steps",
            false,
            System.nanoTime());
        HologramProgressView.draw(ui.host.font(), progress, 50, 165, 580, 9);
        ui.canvas.text(HologramProgressView.detail(progress), 50, 192, 580, 0x8bdddf);
        ui.canvas.text(ui.session.state().data.getString("status"), 50, 204, 580, 0xe5c28d);
        ui.canvas.button(193, 218, 239, 244, 20, "取消导入并返回世界 · Esc / E", true, mx, my);
    }

    void updateScreen() {
        ui.library.tickStatus();
        ui.config.tickMainField();
        ui.library.tickNameField();
        ui.annotations.tickField();
        ui.nbt.tickField();
        ui.processing.tickField();
        ui.bindings.tickField();
        ui.config.tickChannelFields();
        ui.position.tickFields();
    }

    void drawScreen(int mouseX, int mouseY, float ticks) {
        ui.preview.movePreviewCamera();
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
            ui.canvas.skin("cloth", 0, 0, HologramUiCanvas.DESIGN_W, HologramUiCanvas.DESIGN_H);
            drawRect(6, 29, HologramUiCanvas.DESIGN_W - 6, HologramUiCanvas.DESIGN_H - 8, 0xed142a32);
            ui.canvas.skin("panel", 6, 6, HologramUiCanvas.DESIGN_W - 12, 22);
            if (ui.session.areaImporting()) {
                ui.router.drawImportBusy(mx, my);
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
                GL11.glPushMatrix();
                GL11.glTranslatef(HologramScreenLayout.COMPACT_PANEL_X, 0, 0);
                GL11.glScalef(HologramScreenLayout.CONTROL_WIDTH_SCALE, 1, 1);
                GL11.glTranslatef(-HologramUiCanvas.panelX, 0, 0);
                ui.canvas.compactControls(true);
                try {
                    ui.config.drawConfig(ui.canvas.compactMouseX(mx), my);
                } finally {
                    ui.canvas.compactControls(false);
                    GL11.glPopMatrix();
                }
                ui.config.drawFooter(mx, my);
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
                        mx,
                        my);
                    ui.canvas.button(
                        164,
                        563,
                        281,
                        109,
                        17,
                        ui.config.structureEnabled(activeStructureId) ? "施工 ✓" : "施工 ×",
                        ui.session.editable(),
                        mx,
                        my);
                }
                if (!ui.session.state().data.getString("activeStructure")
                    .isEmpty()) ui.canvas.button(165, 452, 300, 220, 13, "返回区域总览", ui.session.editable(), mx, my);
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
            if (!ui.library.libraryDrawer())
                ui.canvas.button(179, 578, 9, 94, 17, "AE 容器标记", ui.ae.canOpenAePreview(), mx, my);
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
            if (!ui.review.confirmation()) {
                HologramProgress progress = ui.session.progress();
                String status = progress != null ? HologramProgressView.summary(progress, System.nanoTime())
                    : ui.session.pendingSequence() >= 0 ? "等待服务端校验…" : ui.review.completionSummary();
                int statusX = ui.library.libraryDrawer() ? 12 : 35;
                int statusWidth = ui.library.libraryDrawer() ? HologramUiCanvas.DESIGN_W - 24
                    : HologramUiCanvas.viewportWidth - 12;
                drawRect(statusX - 3, 318, statusX + statusWidth + 3, 333, 0xff142a32);
                if (progress != null && ui.session.notice()
                    .isEmpty()) HologramProgressView.draw(ui.host.font(), progress, statusX, 321, statusWidth, 0);
                else ui.canvas.text(
                    ui.session.notice()
                        .isEmpty() ? status : ui.session.notice(),
                    statusX,
                    321,
                    statusWidth,
                    ui.session.notice()
                        .isEmpty() ? 0xffbd91 : 0xff9292);
                if (mx >= statusX && mx < statusX + statusWidth && my >= 318 && my < 333) {
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
                drawRect(32, 318, HologramUiCanvas.viewportWidth + 26, 333, 0xff142a32);
                HologramProgress progress = ui.session.progress();
                if (progress != null) HologramProgressView
                    .draw(ui.host.font(), progress, 35, 321, HologramUiCanvas.viewportWidth - 12, 0);
                else {
                    String result = ui.session.notice()
                        .isEmpty() ? ui.review.completionSummary() : ui.session.notice();
                    ui.canvas.text(result, 35, 321, HologramUiCanvas.viewportWidth - 12, 0xffbd91);
                    if (mx >= 35 && mx < HologramUiCanvas.viewportWidth + 26 && my >= 318 && my < 333)
                        ui.canvas.tooltip(
                            new ArrayList<>(
                                ui.host.font()
                                    .listFormattedStringToWidth(result, 390)));
                }
            }
            ui.preview.drawEmptyVoxelPreview(mx, my);
            if (ui.annotations.annotationInteractive()) ui.annotations.drawAnnotations(mx, my);
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
                && HologramGuidance.authorWorkspace(ui.session.state().data)
                && ui.session.state().data.getBoolean("virtual"))
                footerGuide = ui.memory.initialEditHint() ? "可微调蓝图结构；点击、选格或修改后前往蓝图设置" : "结构调整完成后，前往蓝图设置处理并保存";
            ui.canvas.text(footerGuide, 35, 306, HologramUiCanvas.viewportWidth - 4, 0x8bdddf);
            if (mx >= 35 && mx < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth && my >= 304 && my < 317) {
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
            if (ui.processing.processingFlow() != null) {
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
            if (ui.canvas.tooltip() != null) ui.host.renderTooltip(ui.canvas.tooltip(), mx, my, ui.host.font());
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
        ui.canvas.incrementRenderGeneration(1, true);
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
        int mx = ui.canvas.localX(x), my = ui.canvas.localY(y);
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
        if (button == 0 && ui.annotations.annotationInteractive() && ui.annotations.toggleAnnotationAt(mx, my)) return;
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
        if (ui.preview.consumeViewportRightMouse(mx, my, button)) return;
        if (ui.preview.consumeViewportLeftMouse(mx, my, button)) return;
        if (button == 2 && ui.preview.previewInteractive()
            && !ui.preview.inventory()
            && ui.canvas.inViewport(mx, my)
            && !ui.preview.previewBlockedAt(mx, my)) ui.preview.resetCamera();
    }

    void mouseClickMove(int x, int y, int button, long elapsed) {
        ui.preview.mouseClickMove(x, y, button, elapsed);
    }

    void mouseMovedOrUp(int x, int y, int button) {
        ui.preview.mouseMovedOrUp(x, y, button);
    }

    void handleMouseInput() {

        if (ui.bindings.networkDrawer()) return;
        if (ui.session.areaImporting() || ui.ae.aeReviewNavigation() != null || ui.identity.deidentificationDrawer())
            return;
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        if (ui.processing.consumeProcessingWheel(wheel)) return;
        if (ui.library.discardReview() || ui.library.overwriteReview() || ui.processing.processingPresetSaveReview())
            return;
        if (ui.ae.consumeReviewWheel(wheel)) return;
        if (ui.nbt.consumeEditorWheel(wheel)) return;
        if (ui.annotations.consumeVersionsWheel(wheel)) return;
        if (ui.annotations.promptDrawer()) return;
        if (ui.config.resetReview()) return;
        int mx = ui.canvas.localX(Mouse.getEventX() * ui.host.screenWidth() / ui.host.minecraft().displayWidth),
            my = ui.canvas.localY(
                ui.host.screenHeight() - Mouse.getEventY() * ui.host.screenHeight() / ui.host.minecraft().displayHeight
                    - 1);
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
            ui.review.materialOffset(
                Math.max(
                    0,
                    Math.min(
                        Math.max(
                            0,
                            (ui.review.resourceReview() ? ui.session.state().data.getTagList("blueprintResources", 10)
                                .tagCount() : ui.session.state().materials.size()) - reviewRows),
                        ui.review.materialOffset() + (wheel > 0 ? -1 : 1))));
        } else if (ui.preview.inventory() && ui.preview.sourceTargets() && mx >= 157) {
            int count = ui.session.state().data.getTagList("materialChoices", 10)
                .tagCount();
            int maxOffset = Math.max(0, (count - 36 + 8) / 9 * 9);
            ui.preview.sourceOffset(Math.max(0, Math.min(maxOffset, ui.preview.sourceOffset() + (wheel > 0 ? -9 : 9))));
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
                ui.preview.zoom(Math.max(.25, Math.min(4, ui.preview.zoom() * (wheel > 0 ? 1.1 : 1 / 1.1))));
    }

    void keyTyped(char c, int key) {
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
        if (ui.preview.consumeGizmoKey(c, key)) return;
        if (ui.shortcuts.consumeBindingKey(c, key)) return;
        if (key == Keyboard.KEY_ESCAPE) {
            if (!ui.annotations.pinnedAnnotation()
                .isEmpty() && ui.annotations.annotationInteractive()) {
                ui.annotations.pinnedAnnotation("");
                return;
            }
            ui.preview.cancelQueuedEdits("已取消等待中的编辑");
            if (ui.preview.gizmo()
                .axis() >= 0) {
                ui.preview.gizmo()
                    .cancel();
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
        if (key == Keyboard.KEY_T && !ui.router.textFieldFocused()) return;
        if (ui.library.consumeBrowserKey(c, key)) return;
        if (ui.preview.consumeCameraKey(c, key)) return;
        if (ui.preview.handleEditorShortcut(key, HologramKeyBindings.modifiers())) return;
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
        if (id == 166 || id == 146) ui.memory.cellEditHint(false);
        if (ui.library.cancelSwitchReview(id)) return;
        if (ui.processing.confirmPresetSave(id)) return;
        if (ui.library.confirmOverwrite(id)) return;
        if (ui.library.confirmDiscard(id)) return;
        if (ui.library.toggleOverwriteMode(id)) return;
        if (ui.preview.rotateCameraControl(id)) return;
        if (ui.processing.activateProcessing(id)) return;
        if (ui.library.prepareLibraryNavigation(id)) return;
        if (ui.identity.activateDeidentification(id)) return;
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
