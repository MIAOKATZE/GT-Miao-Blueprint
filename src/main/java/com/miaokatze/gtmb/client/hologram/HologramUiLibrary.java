package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.client.hologram.HologramUiLibrary.LibraryNavigation;
import com.miaokatze.gtmb.client.hologram.HologramUiMemory.UiMemory;
import com.miaokatze.gtmb.hologram.HologramLibraryConfig;

/** Internal library workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiLibrary {

    private final HologramUiModules ui;
    private static final HologramSaveGuidance SAVE_GUIDANCE = new HologramSaveGuidance();
    private HologramSaveGuidanceDialog saveGuidanceDialog;

    HologramUiLibrary(HologramUiModules ui) {
        this.ui = ui;
    }

    /** Called only after the client/server save transaction has actually committed. */
    static void committedSave(String session) {
        SAVE_GUIDANCE.committed(session);
    }

    int savedStructureAccent(int id) {
        return SAVE_GUIDANCE.accent(ui.session.state().session, id);
    }

    boolean saveGuidanceOpen() {
        return saveGuidanceDialog != null && saveGuidanceDialog.isOpen();
    }

    boolean consumeSaveGuidanceKey(int key) {
        return saveGuidanceDialog != null && saveGuidanceDialog.consumeKey(key);
    }

    void maybeShowSaveGuidance() {
        if (ui.host.minecraft().currentScreen != ui.host.parent() || HologramBackgroundTasks.busy()
            || !ui.preview.previewInteractive()
            || libraryAdminDrawer
            || ui.bindings.networkDrawer()
            || saveGuidanceOpen()) return;
        HologramUiTabMemory preferences = HologramUiMemory.tabMemory();
        if (preferences == null
            || !SAVE_GUIDANCE.takeNotice(ui.session.state().session, preferences.saveGuidanceSuppressed())) return;
        // MUI2 retains the handler for a panel name after closing; reuse it for later Save commits.
        if (saveGuidanceDialog == null) {
            saveGuidanceDialog = new HologramSaveGuidanceDialog(
                ui.host.parent()
                    .panel(),
                preferences,
                () -> HologramBlueprintWebsite.open(ui.host.parent()));
        }
        saveGuidanceDialog.open();
    }

    private HologramMuiTextField presetNameField;

    private boolean libraryDrawer;
    private boolean browsePreview;
    private String browseServerPresetId = "";

    boolean browsePreviewActive() {
        return libraryDrawer && browsePreview;
    }

    private String selectedServerPresetId() {
        return browseServerPresetId.isEmpty() ? ui.session.state().data.getString("selectedPresetId")
            : browseServerPresetId;
    }

    private void browsePreset(String id, boolean server) {
        cancelLibraryNavigation();
        browsePreview = true;
        if (server) HologramLibraryClient.INSTANCE.previewPublic(id, false);
        else HologramLibraryClient.INSTANCE.previewLocal(id, false);
    }

    private boolean blueprintTab;

    private int libraryOffset;

    private boolean publicLibrary;

    private boolean libraryAdminDrawer;

    private HologramMuiTextField libraryUploadField;

    private HologramMuiTextField libraryDownloadField;

    private HologramMuiTextField libraryQuotaField;

    private long libraryConfigRevision = -1;

    private long presetSaveSequence = -1;

    private String libraryResult = "";

    private String deletePresetId = "";

    private String deletePresetName = "";

    private boolean overwriteMode;

    private boolean overwriteReview;

    private String overwriteTarget = "";

    private String overwriteName = "";

    private int deferredLibraryAction = -1;

    private String deferredLibraryTargetId = "";

    private boolean discardReview;

    private boolean discardConfirmed;

    private NBTTagCompound sourceSwitchProposal;

    private boolean keepLibraryOffset;

    private LibraryNavigation libraryNavigation;

    static final class LibraryNavigation {

        final String sourceSession, sourceGeneration, targetId, purpose;
        final long sourceRevision;
        final int dimension, offset;
        final Object world;
        final boolean local;
        private boolean cancelled;

        LibraryNavigation(HologramState source, Object world, String targetId, boolean local, String purpose,
            int offset) {
            sourceSession = source.session;
            sourceGeneration = source.data.getString("generation");
            sourceRevision = source.data.getLong("planRevision");
            dimension = source.data.getInteger("dimension");
            this.world = world;
            this.targetId = targetId;
            this.local = local;
            this.purpose = purpose;
            this.offset = offset;
        }

        boolean source(HologramState snapshot, Object currentWorld, boolean publicView) {
            return !cancelled && world == currentWorld
                && local != publicView
                && sourceSession.equals(snapshot.session)
                && sourceGeneration.equals(snapshot.data.getString("generation"))
                && sourceRevision == snapshot.data.getLong("planRevision")
                && dimension == snapshot.data.getInteger("dimension");
        }

        boolean destination(HologramState snapshot) {
            return !cancelled && !sourceSession.equals(snapshot.session)
                && snapshot.session.equals(snapshot.data.getString("generation"))
                && dimension == snapshot.data.getInteger("dimension")
                && snapshot.data.getBoolean("virtual")
                && !snapshot.data.getBoolean("virtualPlacement")
                && snapshot.data.getBoolean("open")
                && purpose.equals(snapshot.data.getString("openPurpose"))
                && targetId.equals(snapshot.data.getString(local ? "libraryLocalId" : "selectedPresetId"));
        }

        void cancel() {
            cancelled = true;
        }
    }

    void rememberLibraryNavigation(String targetId, String purpose) {
        ui.library.cancelLibraryNavigation();
        this.libraryNavigation = targetId.isEmpty() ? null
            : new LibraryNavigation(
                ui.session.state(),
                ui.host.minecraft().theWorld,
                targetId,
                !this.publicLibrary,
                purpose,
                this.libraryOffset);
    }

    void cancelLibraryNavigation() {
        if (this.libraryNavigation != null) this.libraryNavigation.cancel();
        this.libraryNavigation = null;
    }

    boolean acceptsLibraryNavigation(HologramState next) {
        return this.libraryNavigation != null
            && this.libraryNavigation.source(ui.session.state(), ui.host.minecraft().theWorld, this.publicLibrary)
            && this.libraryNavigation.destination(next);
    }

    void acceptSourceSwitchProposal() {
        NBTTagCompound proposal = ui.session.state().data.getCompoundTag("discardTargetAction");
        String operation = proposal.getString("op");
        if (operation.isEmpty() && presetSwitchOperation(ui.session.state().data.getString("discardTargetOp"))) {
            proposal = new NBTTagCompound();
            proposal.setString("op", ui.session.state().data.getString("discardTargetOp"));
            proposal.setString("presetId", ui.session.state().data.getString("discardTargetId"));
            operation = proposal.getString("op");
        }
        if (ui.session.state().data.getBoolean("discardConfirmationRequired")
            && ("openController".equals(operation) || "importArea".equals(operation)
                || presetSwitchOperation(operation) && !proposal.getString("presetId")
                    .isEmpty())) {
            this.sourceSwitchProposal = (NBTTagCompound) proposal.copy();
            this.discardReview = true;
            this.deferredLibraryAction = -1;
            this.deferredLibraryTargetId = "";
        } else if (this.sourceSwitchProposal != null) {
            this.sourceSwitchProposal = null;
            this.discardReview = false;
        }
    }

    void applyOpeningPurpose() {
        String purpose = ui.session.state().session + ":" + ui.session.state().data.getString("openPurpose");
        boolean newPurpose = !purpose.equals(ui.session.openedPurpose());
        if (newPurpose) {
            ui.session.openedPurpose(purpose);
            ui.memory.initialEditHint(
                ui.session.state().data.getBoolean("virtual") && !ui.session.state().data.getBoolean("blueprintSaved")
                    && !ui.session.state().data.getBoolean("blueprintProcessingComplete"));
            ui.memory.savedEditHint(
                "blueprintEdit".equals(ui.session.state().data.getString("openPurpose"))
                    && ui.session.state().data.getBoolean("blueprintSaved"));
            ui.memory.structureVisited(false);
            ui.memory.basicConfigured(ui.session.state().data.getBoolean("presetActive"));
            boolean[] memory = HologramUiMemory.GUIDANCE_MEMORY()
                .get(purpose);
            if (memory != null) {
                ui.memory.initialEditHint(memory[0]);
                ui.memory.savedEditHint(memory[1]);
                ui.memory.structureVisited(memory[2]);
                ui.memory.basicConfigured(memory[3]);
            }
            Boolean openingTab = HologramUiTabMemory
                .openingTab(ui.session.state().data, ui.memory.sessionMemory(ui.session.state().session) != null);
            if (openingTab != null) this.blueprintTab = this.libraryDrawer = openingTab;
        }
        // The server identifies actual controller imports; this overrides both per-session and disk tab memory.
        if (newPurpose && "controllerStructure".equals(ui.session.state().data.getString("openPurpose"))) {
            this.blueprintTab = false;
            this.libraryDrawer = false;
            ui.memory.rememberUi();
        } else if (newPurpose && "blueprintBrowse".equals(ui.session.state().data.getString("openPurpose"))) {
            this.blueprintTab = true;
            this.libraryDrawer = true;
            this.publicLibrary = ui.session.state().data.getString("selectedPresetId")
                .startsWith("public_");
            ui.memory.rememberUi();
        } else if (newPurpose && ("blueprintEdit".equals(ui.session.state().data.getString("openPurpose"))
            || "blueprintPlacement".equals(ui.session.state().data.getString("openPurpose")))) {
                this.blueprintTab = true;
                this.libraryDrawer = "blueprintEdit".equals(ui.session.state().data.getString("openPurpose"));
                ui.memory.rememberUi();
            }
        ui.review.placementReviewContext()
            .accept(
                ui.session.state().session,
                ui.session.state().data.getLong("placementReviewNonce"),
                ui.session.state().data.getBoolean("open"),
                ui.session.state().data.getBoolean("virtualPlacement")
                    && ui.session.state().data.getBoolean("virtualLocked"),
                ui.session.state().data.getBoolean("blueprintAwaitingConfirmation"));
        if (!"placementReview".equals(ui.session.state().data.getString("openPurpose"))) return;
        this.blueprintTab = true;
        long nonce = ui.session.state().data.getLong("placementReviewNonce");
        if (nonce <= ui.review.seenPlacementReviewNonce()) return;
        ui.review.seenPlacementReviewNonce(nonce);
        ui.memory.rememberUi();
    }

    void drawLibrary(int mx, int my) {
        if (HologramLibraryClient.dedicated()) {
            ui.library.drawClientServerLibrary(mx, my);
            return;
        }
        int x = HologramScreenLayout.COMPACT_PANEL_X, w = HologramScreenLayout.COMPACT_PANEL_WIDTH;
        NBTTagList presets = ui.session.state().data.getTagList("presets", 10);
        drawRect(x, 57, x + w, 314, 0xef172f36);
        ui.canvas.text("蓝图库 · " + presets.tagCount() + " 项", x + 5, 62, 83, 0xf5e4b9);
        drawWebsiteButton(309, x + 91, 60, 62, 15, false, mx, my);
        ui.canvas.button(303, x + w - 62, 60, 57, 15, "刷新", ui.session.editable(), mx, my);
        this.presetNameField.drawTextBox();
        if (this.presetNameField.getText()
            .isEmpty() && !this.presetNameField.isFocused()) ui.canvas.text("当前蓝图名称", x + 8, 83, w - 16, 0x647c7f);
        boolean canSave = !browsePreviewActive() && ui.session.editable()
            && ui.processing.processingReady()
            && !ui.session.state().data.getBoolean("settingsOnly")
            && !ui.session.state().cells.isEmpty();
        drawRect(x + 3, 97, x + w - 3, 117, 0xff24444d);
        ui.canvas.button(140, x + 5, 99, 103, 16, "保存蓝图", canSave, mx, my);
        ui.canvas.button(
            222,
            x + 112,
            99,
            103,
            16,
            "覆盖保存 " + (this.overwriteMode ? "✓" : "×"),
            !browsePreviewActive(),
            mx,
            my);
        ui.canvas.button(
            141,
            x + 112,
            34,
            103,
            17,
            "放置所选蓝图",
            ui.session.editable() && ui.library.selectedPresetExists(),
            mx,
            my);
        this.libraryOffset = Math.max(0, Math.min(this.libraryOffset, Math.max(0, presets.tagCount() - 4)));
        NBTTagCompound selectedRow = null;
        for (int i = 0; i < presets.tagCount(); i++) {
            NBTTagCompound row = presets.getCompoundTagAt(i);
            if (row.getString("id")
                .equals(selectedServerPresetId())) selectedRow = row;
            if (i < this.libraryOffset || i >= this.libraryOffset + 4) continue;
            int y = 123 + (i - this.libraryOffset) * 28;
            boolean active = row.getString("id")
                .equals(this.overwriteMode ? this.overwriteTarget : selectedServerPresetId());
            Control entry = new Control(14000 + i, x + 5, y, w - 10, 28, !HologramBackgroundTasks.busy());
            ui.canvas.controls()
                .add(entry);
            entry.label = (active ? "\u25b6 " : "") + row.getString(
                "name") + "\n" + row.getInteger("cells") + " \u683c \u00b7 " + ui.library.blueprintSource(row);
            entry.accent = active ? 0x81dce9 : 0;
            if (entry.contains(mx, my)) {
                ui.canvas.tooltip(new ArrayList<>());
                ui.canvas.tooltip()
                    .add(row.getString("name"));
                ui.canvas.tooltip()
                    .add(
                        row.getInteger("cells") + " 格 · "
                            + row.getInteger("structures")
                            + " 结构 · 来源："
                            + ui.library.blueprintSource(row));
                ui.canvas.tooltip()
                    .add(this.overwriteMode ? "仅选择覆盖目标；保存时确认覆盖" : "点击选择并预览；上方“放置”开始世界定位");
            }
        }
        if (presets.tagCount() == 0) {
            ui.canvas.text("尚无保存蓝图", x + 12, 139, w - 24, 0xb9d5d4);
            ui.canvas.text("框选区域后保存为蓝图", x + 12, 157, w - 24, 0x8ba7ad);
            drawWebsiteButton(310, x + 12, 181, w - 24, 20, true, mx, my);
        }
        if (presets.tagCount() > 4) {
            drawRect(x + w - 3, 123, x + w - 1, 235, 0xff243a40);
            int thumbHeight = Math.max(12, 112 * 4 / presets.tagCount());
            int thumbY = 123 + (112 - thumbHeight) * this.libraryOffset / (presets.tagCount() - 4);
            drawRect(x + w - 3, thumbY, x + w - 1, thumbY + thumbHeight, 0xff6aabb8);
        }
        HologramScrollEdges.draw(x + 2, 122, w - 4, 114);
        ui.canvas.button(
            139,
            x + 5,
            241,
            103,
            17,
            "编辑所选",
            ui.session.editable() && ui.library.selectedPresetExists(),
            mx,
            my);
        ui.canvas.button(
            299,
            x + 112,
            241,
            103,
            17,
            "放弃蓝图预设",
            ui.session.editable() && !ui.session.state().cells.isEmpty(),
            mx,
            my);
        ui.canvas.button(
            142,
            x + 5,
            261,
            103,
            17,
            "保留 NBT " + (ui.processing.retainNbt() ? "✓" : "×"),
            !browsePreviewActive(),
            mx,
            my);
        ui.canvas.button(
            143,
            x + 112,
            261,
            103,
            17,
            "覆盖板 " + (ui.processing.retainCovers() ? "✓" : "×"),
            !browsePreviewActive(),
            mx,
            my);
        ui.canvas.button(
            159,
            x + 5,
            281,
            67,
            17,
            "导出 JSON",
            ui.session.editable() && ui.library.selectedPresetExists(),
            mx,
            my);
        ui.canvas.button(161, x + 77, 281, 66, 17, "导入 JSON", ui.session.editable(), mx, my);
        ui.canvas.button(
            175,
            x + 148,
            281,
            67,
            17,
            "删除",
            ui.session.editable() && ui.library.selectedPresetExists(),
            mx,
            my);
        ui.canvas.button(150, x + 5, 301, 67, 13, "来源版本", true, mx, my);
        drawUpgradeButton(x + 77, mx, my);
        ui.canvas.button(227, x + 148, 301, 67, 13, "全局提示词", !browsePreviewActive() && ui.session.editable(), mx, my);
        ui.library.drawBlueprintWorkbench(mx, my);
        drawRect(35, 261, 442, 279, 0xe8172f36);
        HologramProgress waiting = HologramLibraryClient.INSTANCE.progress();
        if (waiting == null) waiting = ui.session.progress();
        if (waiting != null) HologramProgressView.draw(ui.host.font(), waiting, 41, 263, 395, 3);
        else if (selectedRow == null) {
            ui.canvas.text("蓝图来源：" + ui.session.state().data.getString("blueprintSource"), 41, 263, 395, 0x8ba7ad);
        } else {
            ui.canvas.text(
                "来源：" + ui.library.blueprintSource(selectedRow) + " · " + selectedRow.getInteger("cells") + " 格",
                41,
                263,
                395,
                0x8ba7ad);
        }
        if (mx >= 35 && mx < 442 && my >= 239 && my < 279) {
            ui.canvas.tooltip(new ArrayList<>());
            if (selectedRow != null) {
                ui.canvas.tooltip()
                    .add(selectedRow.getString("name"));
                ui.canvas.tooltip()
                    .add("来源：" + ui.library.blueprintSource(selectedRow));
                if (!selectedRow.getString("sourceClass")
                    .isEmpty())
                    ui.canvas.tooltip()
                        .add(selectedRow.getString("sourceClass"));
            }
            String error = ui.session.state().data.getString("presetConfigError");
            String result = error.isEmpty() ? this.libraryResult : "读取失败：" + error;
            if (!result.isEmpty()) ui.canvas.tooltip()
                .addAll(
                    ui.host.font()
                        .listFormattedStringToWidth(result, 350));
        }
    }

    void rejectCommand(long sequence) {
        if (presetSaveSequence == sequence) presetSaveSequence = -1;
    }

    void drawClientServerLibrary(int mx, int my) {
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        int x = HologramScreenLayout.COMPACT_PANEL_X, w = HologramScreenLayout.COMPACT_PANEL_WIDTH;
        NBTTagList rows = library.rows(this.publicLibrary);
        NBTTagCompound selectedRow = library.selectedRow(this.publicLibrary);
        boolean chosen = !selectedRow.getString("id")
            .isEmpty(), idle = ui.session.editable() && !library.busy() && !HologramBackgroundTasks.busy();
        drawRect(x, 57, x + w, 314, 0xef172f36);
        ui.canvas.text("蓝图库 · " + rows.tagCount() + " 项", x + 5, 62, 83, 0xf5e4b9);
        drawWebsiteButton(309, x + 91, 60, 62, 15, false, mx, my);
        ui.canvas.button(201, x + w - 62, 60, 57, 15, this.publicLibrary ? "服务器 ▾" : "客户端 ▾", true, mx, my);
        if (library.config()
            .getBoolean("admin")) ui.canvas.button(207, 272, 9, 76, 17, "管理员设置", true, mx, my);
        this.presetNameField.drawTextBox();
        if (this.presetNameField.getText()
            .isEmpty() && !this.presetNameField.isFocused()) ui.canvas.text("当前蓝图名称", x + 8, 83, w - 16, 0x647c7f);
        ui.canvas.button(
            140,
            x + 5,
            99,
            103,
            16,
            "保存蓝图",
            !browsePreviewActive() && idle && ui.processing.processingReady() && !ui.session.state().cells.isEmpty(),
            mx,
            my);
        ui.canvas.button(
            222,
            x + 112,
            99,
            103,
            16,
            "覆盖保存 " + (this.overwriteMode ? "✓" : "×"),
            !browsePreviewActive(),
            mx,
            my);
        ui.canvas.button(141, x + 112, 34, 103, 17, "放置所选蓝图", idle && chosen, mx, my);
        this.libraryOffset = Math.max(0, Math.min(this.libraryOffset, Math.max(0, rows.tagCount() - 4)));
        for (int i = this.libraryOffset; i < Math.min(rows.tagCount(), this.libraryOffset + 4); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            int y = 123 + (i - this.libraryOffset) * 28;
            boolean active = row.getString("id")
                .equals(this.overwriteMode ? this.overwriteTarget : library.selected(this.publicLibrary));
            Control entry = new Control(
                14000 + i,
                x + 5,
                y,
                w - 10,
                28,
                !library.busy() && !HologramBackgroundTasks.busy());
            ui.canvas.controls()
                .add(entry);
            String author = row.getString("authorName");
            entry.label = (active ? "\u25b6 " : "") + row.getString("name")
                + "\n"
                + "\u4f5c\u8005\uff1a"
                + (author.isEmpty() ? "\u672a\u7f72\u540d" : author)
                + " \u00b7 "
                + HologramLibraryClient.bytes(row.getLong("bytes"));
            entry.accent = active ? 0x81dce9 : 0;
            if (entry.contains(mx, my)) {
                ui.canvas.tooltip(new ArrayList<>());
                ui.canvas.tooltip()
                    .add(row.getString("name"));
                ui.canvas.tooltip()
                    .add("作者：" + (author.isEmpty() ? "未署名（旧蓝图）" : author));
                ui.canvas.tooltip()
                    .add(
                        row.getInteger("cells") + " 格 · "
                            + row.getInteger("structures")
                            + " 结构 · "
                            + HologramLibraryClient.bytes(row.getLong("bytes")));
                if (this.publicLibrary) ui.canvas.tooltip()
                    .add("上传者（服务器验证）：" + row.getString("uploadedBy"));
                ui.canvas.tooltip()
                    .add("单击只读预览；点击“放置所选蓝图”进入世界定位");
            }
        }
        if (rows.tagCount() == 0) {
            ui.canvas.text(this.publicLibrary ? "服务器暂未发布蓝图" : "客户端暂无蓝图", x + 12, 140, w - 24, 0xb9d5d4);
            ui.canvas.text(this.publicLibrary ? "切换客户端页，上传自己的蓝图" : "保存蓝图后可携带到其他服务器", x + 12, 158, w - 24, 0x8ba7ad);
            drawWebsiteButton(310, x + 12, 181, w - 24, 20, true, mx, my);
        }
        ui.canvas.button(139, x + 5, 241, 103, 17, "编辑所选", idle && chosen, mx, my);
        ui.canvas.button(299, x + 112, 241, 103, 17, "放弃蓝图预设", idle && !ui.session.state().cells.isEmpty(), mx, my);
        ui.canvas.button(
            142,
            x + 5,
            261,
            103,
            17,
            "保留 NBT " + (ui.processing.retainNbt() ? "✓" : "×"),
            !browsePreviewActive(),
            mx,
            my);
        ui.canvas.button(
            143,
            x + 112,
            261,
            103,
            17,
            "覆盖板 " + (ui.processing.retainCovers() ? "✓" : "×"),
            !browsePreviewActive(),
            mx,
            my);
        ui.canvas.button(203, x + 5, 281, 103, 17, this.publicLibrary ? "下载至客户端" : "上传 / 更新公库", idle && chosen, mx, my);
        ui.canvas.button(
            205,
            x + 112,
            281,
            103,
            17,
            this.publicLibrary ? "删除自己的上传" : "删除客户端蓝图",
            idle && chosen && (!this.publicLibrary || selectedRow.getBoolean("mine")),
            mx,
            my);
        if (this.publicLibrary) {
            ui.canvas.button(208, x + 5, 301, 30, 13, "上页", library.page() > 0, mx, my);
            ui.canvas.text((library.page() + 1) + " / " + library.pages(), x + 36, 303, 40, 0x8ba7ad);
            ui.canvas.button(209, x + 78, 301, 30, 13, "下页", library.page() + 1 < library.pages(), mx, my);
            if (library.config()
                .getBoolean("admin")) drawUpgradeButton(x + 113, mx, my);
        } else {
            ui.canvas.button(202, x + 5, 301, 67, 13, "刷新", !library.busy(), mx, my);
            drawUpgradeButton(x + 77, mx, my);
        }
        ui.canvas.button(227, x + 148, 301, 67, 13, "全局提示词", !browsePreviewActive() && ui.session.editable(), mx, my);
        ui.library.drawBlueprintWorkbench(mx, my);
        drawRect(35, 261, 442, 279, 0xe8172f36);
        HologramProgress progress = library.progress();
        if (progress == null) ui.canvas.text(library.status(), 41, 258, 395, 0xb9d5d4);
        else HologramProgressView.draw(ui.host.font(), progress, 41, 258, 395, 4);
        if (mx >= 35 && mx < 442 && my >= 239 && my < 279) {
            ui.canvas.tooltip(new ArrayList<>());
            ui.canvas.tooltip()
                .add(library.status());
            if (progress != null) {
                ui.canvas.tooltip()
                    .add(HologramProgressView.summary(progress, System.nanoTime()));
                if (!HologramProgressView.detail(progress)
                    .isEmpty())
                    ui.canvas.tooltip()
                        .add(HologramProgressView.detail(progress));
            }
            if (library.busy()) ui.canvas.tooltip()
                .add(
                    HologramLibraryClient.bytes(library.progressBytes()) + " / "
                        + HologramLibraryClient.bytes(library.totalBytes()));
            ui.canvas.tooltip()
                .add("客户端目录：MiaoBlueprints/");
        }
    }

    private void drawWebsiteButton(int id, int x, int y, int width, int height, boolean empty, int mx, int my) {
        ui.canvas.button(
            id,
            x,
            y,
            width,
            height,
            HologramUiText.localized(
                empty ? "gtmb.hologram.website.discover" : "gtmb.hologram.website.button",
                empty ? "前往蓝图站发现更多蓝图 ↗" : "蓝图站 ↗"),
            ui.session.pendingSequence() < 0 && !HologramBackgroundTasks.busy()
                && !HologramLibraryClient.INSTANCE.busy(),
            mx,
            my);
    }

    private void drawUpgradeButton(int x, int mx, int my) {
        boolean server = HologramLibraryClient.dedicated() && this.publicLibrary;
        if (server && !HologramLibraryClient.INSTANCE.config()
            .getBoolean("admin")) return;
        int width = server ? 30 : 66;
        int upgrade = upgradeStatus();
        ui.canvas.button(
            226,
            x,
            301,
            width,
            13,
            server ? "升级" : "升级旧蓝图",
            upgrade > 0 && !HologramLibraryClient.INSTANCE.busy()
                && !HologramBackgroundTasks.busy()
                && ui.session.pendingSequence() < 0,
            mx,
            my);
        if (mx >= x && mx < x + width && my >= 301 && my < 314) {
            ui.canvas.tooltip(new ArrayList<>());
            ui.canvas.tooltip()
                .add(upgrade == 0 ? "蓝图 GTMB 版本已一致，无需升级。" : upgrade == 2 ? "包含重要处理更新：建议升级旧蓝图。" : "只有版本信息更新，可按需升级。");
            ui.canvas.tooltip()
                .add("仅更新 GTMB 元数据及兼容存储；保留原整合包、其他 Mod 版本和方块内容。");
            ui.canvas.tooltip()
                .add("保留逻辑ID、最近蓝图与上传映射；缺图或配对不明确时保留原件并报告。");
            String status = HologramLibraryClient.INSTANCE.status();
            if (!status.isEmpty()) ui.canvas.tooltip()
                .add(status);
            java.util.List<String> details = HologramLibraryClient.INSTANCE.upgradeDetails();
            for (int index = 0; index < Math.min(8, details.size()); index++) ui.canvas.tooltip()
                .add(details.get(index));
            if (details.size() > 8) ui.canvas.tooltip()
                .add("另有 " + (details.size() - 8) + " 条跳过原因，完整明细见游戏日志。");
        }
    }

    int upgradeStatus() {
        NBTTagList rows = HologramLibraryClient.dedicated() ? HologramLibraryClient.INSTANCE.rows(this.publicLibrary)
            : ui.session.state().data.getTagList("presets", 10);
        int status = 0;
        for (int i = 0; i < rows.tagCount(); i++) status = Math.max(
            status,
            com.miaokatze.gtmb.hologram.HologramBlueprintUpgrade.status(
                rows.getCompoundTagAt(i)
                    .getString("gtmbVersion")));
        return status;
    }

    private void upgradedLibrary() {
        libraryResult = HologramLibraryClient.INSTANCE.status();
        ui.session.notice(libraryResult);
        if (HologramLibraryClient.dedicated() && this.publicLibrary) HologramLibraryClient.INSTANCE.refreshServer(0);
        else refreshLibrary();
    }

    void openLibraryAdmin() {
        NBTTagCompound config = HologramLibraryClient.INSTANCE.config();
        if (!config.getBoolean("admin")) return;
        this.libraryAdminDrawer = true;
        this.libraryConfigRevision = config.getLong("configRevision");
        this.libraryUploadField = ui.library.libraryAdminField(140, String.valueOf(config.getInteger("uploadKiB")));
        this.libraryDownloadField = ui.library.libraryAdminField(175, String.valueOf(config.getInteger("downloadKiB")));
        this.libraryQuotaField = ui.library.libraryAdminField(210, String.valueOf(config.getInteger("maxUploads")));
    }

    HologramMuiTextField libraryAdminField(int y, String value) {
        HologramMuiTextField field = new HologramMuiTextField(ui.host.parent(), 282, y, 136, 18);
        field.setMaxStringLength(7);
        field.setText(value);
        return field;
    }

    String blueprintSaveState() {
        if (ui.session.state().data.getBoolean("blueprintSavePending")) return HologramLibraryClient.INSTANCE.status()
            .isEmpty() ? HologramUiText.localized("gtmb.hologram.blueprint.saving", "正在传输保存，等待写盘确认")
                : HologramLibraryClient.INSTANCE.status();
        return ui.session.state().data.getBoolean("blueprintDirty")
            ? ui.session.state().data.getBoolean("blueprintSaved")
                ? HologramUiText.localized("gtmb.hologram.blueprint.dirty", "改动未保存")
                : HologramUiText.localized("gtmb.hologram.blueprint.unsaved", "未保存")
            : ui.session.state().data.getBoolean("blueprintSaved")
                ? HologramUiText.localized("gtmb.hologram.blueprint.saved", "已保存")
                : HologramUiText.localized("gtmb.hologram.blueprint.unsaved", "未保存");
    }

    void drawBlueprintWorkbench(int mx, int my) {
        if (browsePreviewActive()) {
            drawRect(35, HologramScreenLayout.LIBRARY_FOOTER_Y, 442, 259, 0xf0172f36);
            ui.canvas.text("只读预览 · 拖动旋转，滚轮缩放 · 点击“编辑所选”后编辑", 41, 244, 395, 0xb9d5d4);
            ui.canvas.button(223, 41, 282, 120, 17, "旋转查看 ↻", true, mx, my);
            return;
        }
        ui.canvas.drawLocalPlanProgress(41, 260, 395, mx, my);
        if (!HologramGuidance.authorWorkspace(ui.session.state())) {
            drawRect(35, HologramScreenLayout.LIBRARY_FOOTER_Y, 442, 259, 0xf0172f36);
            ui.canvas.text(
                ui.library.selectedPresetExists() ? "已保存蓝图 · 点击绿色“放置所选蓝图”进入世界定位" : "从右侧列表选择已保存蓝图",
                41,
                244,
                395,
                0xb9d5d4);
            ui.canvas.button(223, 41, 282, 120, 17, "旋转查看 ↻", true, mx, my);
            if (ui.preview.selected() >= 0)
                ui.canvas.button(146, 167, 282, 129, 17, "所选格提示词", ui.session.editable(), mx, my);
            ui.config.drawResetButton(302, 282, 134, mx, my);
            return;
        }
        drawRect(35, HologramScreenLayout.LIBRARY_AUTHOR_FOOTER_Y, 442, 259, 0xf0172f36);
        ui.canvas.text(
            ui.library.blueprintSaveState() + " · "
                + (ui.session.state().data.getBoolean("blueprintDirectSave") ? "无需处理，可直接保存"
                    : ui.processing.processingReady() ? "处理已完成" : "先完成蓝图处理流程"),
            41,
            223,
            395,
            0xb9d5d4);
        ui.canvas.button(
            ui.processing.processingReady() ? 297 : 220,
            41,
            239,
            143,
            17,
            ui.processing.processingReady() ? "重新进行蓝图预设" : "开始蓝图处理流程",
            ui.session.editable() && !ui.session.state().cells.isEmpty(),
            mx,
            my);
        ui.canvas.button(
            221,
            190,
            239,
            125,
            17,
            "保存流程预设",
            ui.session.editable() && ui.processing.processingReady() && !ui.session.state().cells.isEmpty(),
            mx,
            my);
        ui.canvas.button(
            166,
            321,
            239,
            115,
            17,
            ui.preview.selected() >= 0 ? "所选格 NBT" : "方块 NBT",
            ui.session.editable() && ui.processing.canEditBlueprintNbt(),
            mx,
            my);
        ui.canvas.button(223, 41, 282, 120, 17, "旋转查看 ↻", true, mx, my);
        if (ui.preview.selected() >= 0)
            ui.canvas.button(146, 167, 282, 129, 17, "所选格提示词", ui.session.editable(), mx, my);
        ui.config.drawResetButton(302, 282, 134, mx, my);
        if (SAVE_GUIDANCE.pending(ui.session.state().session))
            ui.canvas.text("蓝图已保存 · 点击左上青色“结构调整”继续设置结构", 41, 301, 395, HologramUiAccent.SUGGESTION);
    }

    void drawBlueprintSwitchReview(int mx, int my) {
        drawRect(80, 92, 598, 242, 0xff172f36);
        if (ui.processing.processingPresetSaveReview()) {
            ui.canvas.text("保存流程预设", 96, 106, 486, 0xf5e4b9);
            ui.canvas.text("为本次已核对的处理选择命名，供下次导入时复用", 96, 132, 486, 0xb9d5d4);
            ui.processing.processingPresetField()
                .drawTextBox();
            ui.canvas.button(225, 96, 201, 230, 22, "取消", true, mx, my);
            ui.canvas.button(
                224,
                338,
                201,
                244,
                22,
                "确认保存流程预设",
                ui.session.editable() && !ui.processing.processingPresetField()
                    .getText()
                    .trim()
                    .isEmpty(),
                mx,
                my);
            return;
        }
        ui.canvas.text(this.overwriteReview ? "确认覆盖蓝图" : "放弃未保存改动", 96, 106, 486, 0xf5e4b9);
        ui.canvas.text(
            this.overwriteReview ? "将以当前蓝图替换：" + this.overwriteName
                : this.deferredLibraryAction == 299 ? "放弃当前蓝图预设及未保存改动，返回蓝图库。已保存文件保留。" : "当前蓝图尚未保存；继续将放弃这些改动并切换蓝图。",
            96,
            138,
            486,
            0xb9d5d4);
        ui.canvas.text(
            this.overwriteReview ? "目标：" + this.overwriteTarget
                : this.sourceSwitchProposal != null
                    ? "将导入：" + ("openController".equals(this.sourceSwitchProposal.getString("op")) ? "新控制器结构"
                        : "importArea".equals(this.sourceSwitchProposal.getString("op")) ? "新的框选区域" : "所选蓝图")
                    : "取消可返回编辑并先保存蓝图",
            96,
            163,
            486,
            0x94afb4);
        ui.canvas.button(225, 96, 201, 230, 22, "取消", true, mx, my);
        ui.canvas.button(
            224,
            338,
            201,
            244,
            22,
            this.overwriteReview ? "确认覆盖保存" : "放弃改动并继续",
            ui.session.editable() || HologramBackgroundTasks.awaitingSourceReview() && ui.session.pendingSequence() < 0,
            mx,
            my);
    }

    void drawLibraryAdmin(int mx, int my) {
        drawRect(0, 0, HologramUiCanvas.DESIGN_W, HologramUiCanvas.DESIGN_H, 0xd0102026);
        drawRect(60, 77, 452, 290, 0xff172f36);
        ui.canvas.text("服务器蓝图库 · 管理员设置（OP 4）", 76, 90, 360, 0xf5e4b9);
        ui.canvas.text(
            "单位 KiB/s；新默认上传 " + HologramLibraryConfig.DEFAULT_UPLOAD_KIB_PER_SECOND
                + " / 下载 "
                + HologramLibraryConfig.DEFAULT_DOWNLOAD_KIB_PER_SECOND
                + "；配额 10 项",
            76,
            112,
            360,
            0x8ba7ad);
        ui.canvas.text("客户端上传带宽", 76, 145, 190, 0xb9d5d4);
        this.libraryUploadField.drawTextBox();
        ui.canvas.text("客户端下载带宽", 76, 180, 190, 0xb9d5d4);
        this.libraryDownloadField.drawTextBox();
        ui.canvas.text("每位玩家上传配额", 76, 215, 190, 0xb9d5d4);
        this.libraryQuotaField.drawTextBox();
        ui.canvas.button(210, 76, 247, 166, 19, "保存并等待服务器确认", true, mx, my);
        ui.canvas.button(211, 254, 247, 164, 19, "关闭", true, mx, my);
        HologramProgress progress = HologramLibraryClient.INSTANCE.progress();
        if (progress != null) HologramProgressView.draw(ui.host.font(), progress, 76, 274, 342, 3);
        else ui.canvas.text(HologramLibraryClient.INSTANCE.status(), 76, 274, 342, 0x8ba7ad);
    }

    boolean activateClientLibrary(int id) {
        if (!HologramLibraryClient.dedicated()) return false;
        if (ui.canvas.rejectLocalPlanAction(id)) return true;
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        if (id == 210 || id == 211) {
            if (id == 211) this.libraryAdminDrawer = false;
            else try {
                library.saveConfig(
                    Integer.parseInt(this.libraryUploadField.getText()),
                    Integer.parseInt(this.libraryDownloadField.getText()),
                    Integer.parseInt(this.libraryQuotaField.getText()));
            } catch (NumberFormatException invalid) {
                ui.session.notice("请输入有效的正整数");
            }
            return true;
        }
        if (!this.libraryDrawer) return false;
        if (id >= 14000 && id < 15000) {
            NBTTagCompound row = library.rows(this.publicLibrary)
                .getCompoundTagAt(id - 14000);
            library.select(this.publicLibrary, row.getString("id"));
            browsePreset(row.getString("id"), this.publicLibrary);
            return true;
        }
        switch (id) {
            case 201:
                ui.library.cancelLibraryNavigation();
                this.publicLibrary = !this.publicLibrary;
                this.libraryOffset = 0;
                browsePreview = true;
                library.clearBrowsePreview();
                if (this.publicLibrary) library.refreshServer(0);
                return true;
            case 202:
                library.refreshLocal();
                return true;
            case 203:
                if (this.publicLibrary) library.downloadSelected();
                else library.sendLocal(ui.session.state(), "preview", true);
                return true;
            case 205:
                NBTTagCompound row = library.selectedRow(this.publicLibrary);
                if (!this.publicLibrary || row.getBoolean("mine")) {
                    this.deletePresetId = row.getString("id");
                    this.deletePresetName = row.getString("name");
                }
                return true;
            case 206:
                ui.library.cancelLibraryNavigation();
                library.cancel();
                return true;
            case 207:
                ui.library.openLibraryAdmin();
                return true;
            case 208:
                browsePreview = true;
                library.clearBrowsePreview();
                library.refreshServer(library.page() - 1);
                this.libraryOffset = 0;
                return true;
            case 209:
                browsePreview = true;
                library.clearBrowsePreview();
                library.refreshServer(library.page() + 1);
                this.libraryOffset = 0;
                return true;
            case 176:
                library.deleteSelected(this.publicLibrary);
                this.deletePresetId = this.deletePresetName = "";
                return true;
            case 141:
            case 139:
                if (id == 139) browsePreview = false;
                if (id == 139 && (this.publicLibrary || !library.busy()))
                    ui.library.rememberLibraryNavigation(library.selected(this.publicLibrary), "blueprintEdit");
                else if (id == 141) ui.library.cancelLibraryNavigation();
                if (this.publicLibrary) {
                    if (id == 141) {
                        library.previewPublic(library.selected(true));
                        return true;
                    }
                    NBTTagCompound action = new NBTTagCompound();
                    action.setString("presetId", library.selected(true));
                    ui.session.send("editPreset", action, true);
                } else
                    library.sendLocal(ui.session.state(), id == 141 ? "place" : "edit", false, this.discardConfirmed);
                if (id == 139) {
                    this.blueprintTab = true;
                    this.libraryDrawer = true;
                }
                return true;
            case 140:
            case 132:
                if (browsePreviewActive()) return true;
                if (!ui.processing.processingReady()) return true;
                String name = this.presetNameField.getText()
                    .trim();
                if (name.isEmpty()) {
                    ui.session.notice("请输入蓝图名称");
                    return true;
                }
                NBTTagCompound options = new NBTTagCompound();
                options.setString("name", name);
                options.setBoolean("retainNbt", ui.processing.retainNbt());
                options.setBoolean("retainCovers", ui.processing.retainCovers());
                options.setBoolean(
                    "retainAllNbt",
                    !ui.session.state().data.hasKey("blueprintNbtAll")
                        || ui.session.state().data.getBoolean("blueprintNbtAll"));
                if (ui.session.state().data.hasKey("blueprintNbtKeys")) options.setTag(
                    "retainedNbtKeys",
                    ui.session.state().data.getTagList("blueprintNbtKeys", 8)
                        .copy());
                ui.identity.deidentificationSaveOptions(options);
                library.saveDraft(ui.session.state(), options, false);
                return true;
            default:
                return false;
        }
    }

    String blueprintSource(NBTTagCompound row) {
        String source = row.getString("sourceMod");
        if (source.isEmpty() && row.getString("id")
            .equals(ui.session.state().data.getString("selectedPresetId")))
            source = ui.session.state().data.getString("sourceMod");
        return source.isEmpty() ? row.getInteger("schema") >= 2 ? "自定义蓝图" : "结构模板" : source;
    }

    NBTTagCompound blueprintSaveOptions() {
        NBTTagCompound options = new NBTTagCompound();
        String name = this.presetNameField.getText()
            .trim();
        options.setString("name", name.isEmpty() ? ui.session.state().data.getString("virtualPresetName") : name);
        options.setBoolean("retainNbt", ui.processing.retainNbt());
        options.setBoolean("retainCovers", ui.processing.retainCovers());
        options.setBoolean(
            "retainAllNbt",
            !ui.session.state().data.hasKey("blueprintNbtAll")
                || ui.session.state().data.getBoolean("blueprintNbtAll"));
        options.setTag(
            "retainedNbtKeys",
            ui.session.state().data.getTagList("blueprintNbtKeys", 8)
                .copy());
        ui.identity.deidentificationSaveOptions(options);
        return options;
    }

    void drawDeleteReview(int mx, int my) {
        drawRect(50, 85, 428, 219, 0xff172f36);
        String storage = HologramLibraryClient.dedicated() ? this.publicLibrary ? "服务器公库" : "客户端本地" : "本地蓝图库";
        ui.canvas.text("删除" + storage + "蓝图", 62, 97, 354, 0xf5e4b9);
        ui.canvas.text(this.deletePresetName, 62, 117, 354, 0xffbd91);
        ui.canvas.text("删除此" + storage + "蓝图，当前编辑蓝图保留。", 62, 139, 354, 0x94afb4);
        ui.canvas.text("此操作不能撤销。", 62, 155, 354, 0xef8585);
        ui.canvas.button(177, 62, 187, 171, 17, "取消", true, mx, my);
        ui.canvas.button(176, 245, 187, 171, 17, "确认删除", ui.session.editable(), mx, my);
    }

    boolean selectedPresetExists() {
        boolean dedicated = HologramLibraryClient.dedicated();
        if (!dedicated && !browseServerPresetId.isEmpty()) {
            NBTTagList rows = ui.session.state().data.getTagList("presets", 10);
            for (int i = 0; i < rows.tagCount(); i++) if (browseServerPresetId.equals(
                rows.getCompoundTagAt(i)
                    .getString("id")))
                return true;
            return false;
        }
        return HologramGuidance.selectedSavedBlueprint(
            ui.session.state().data,
            dedicated,
            dedicated ? HologramLibraryClient.INSTANCE.selectedRow(this.publicLibrary)
                .getString("id") : "");
    }

    int websiteAccent() {
        NBTTagCompound state = ui.session.state().data;
        String source = state.getString("blueprintSource");
        // New controller sessions and imported drafts can retain the tool's previous saved ID.
        // A source-less library browser still accepts a real row selection before its preview arrives.
        boolean unsavedSource = !state.getBoolean("blueprintSaved")
            && ("imported".equals(source) || "area".equals(source)
                || "processedDraft".equals(source)
                || "discarded".equals(source)
                || source.isEmpty() && !state.getBoolean("virtual")
                    && ("controllerStructure".equals(state.getString("openPurpose"))
                        || HologramGuidance.authorWorkspace(ui.session.state())));
        return !unsavedSource && selectedPresetExists() ? HologramUiAccent.SPECTRUM : HologramUiAccent.WEBSITE;
    }

    void revealSelectedPreset() {
        boolean dedicated = HologramLibraryClient.dedicated();
        NBTTagList presets = dedicated ? HologramLibraryClient.INSTANCE.rows(this.publicLibrary)
            : ui.session.state().data.getTagList("presets", 10);
        String selectedId = dedicated ? HologramLibraryClient.INSTANCE.selected(this.publicLibrary)
            : selectedServerPresetId();
        for (int i = 0; i < presets.tagCount(); i++) {
            if (!selectedId.equals(
                presets.getCompoundTagAt(i)
                    .getString("id")))
                continue;
            if (i < this.libraryOffset || i >= this.libraryOffset + 4)
                this.libraryOffset = Math.max(0, Math.min(i, presets.tagCount() - 4));
            return;
        }
    }

    boolean cancelSwitchReview(int id) {
        if (id == 225) {
            ui.library.cancelLibraryNavigation();
            if (this.sourceSwitchProposal != null) {
                NBTTagCompound proposal = (NBTTagCompound) this.sourceSwitchProposal.copy();
                this.sourceSwitchProposal = null;
                ui.session.send("cancelSourceSwitch", proposal, false);
            }
            this.discardReview = this.overwriteReview = ui.processing.processingPresetSaveReview(false);
            this.deferredLibraryAction = -1;
            this.deferredLibraryTargetId = "";
            return true;
        }
        return false;
    }

    boolean confirmOverwrite(int id) {
        if (id == 224 && this.overwriteReview) {
            if (!ui.session.editable()) return true;
            if (ui.canvas.rejectLocalPlanAction(id)) return true;
            NBTTagList rows = HologramLibraryClient.dedicated() ? HologramLibraryClient.INSTANCE.rows(false)
                : ui.session.state().data.getTagList("presets", 10);
            boolean found = false;
            for (int i = 0; i < rows.tagCount(); i++) if (overwriteTarget.equals(
                rows.getCompoundTagAt(i)
                    .getString("id")))
                found = true;
            if (!found) {
                overwriteReview = false;
                ui.session.notice("覆盖目标已变化，请刷新蓝图库后重新选择");
                return true;
            }
            NBTTagCompound request = ui.library.blueprintSaveOptions();
            request.setString("presetId", this.overwriteTarget);
            request.setBoolean("confirmOverwrite", true);
            this.overwriteReview = false;
            HologramLibraryClient.INSTANCE.expectDraftDownload();
            ui.session.send("saveBlueprintReplace", request, true);
            this.presetSaveSequence = ui.session.sequence();
            return true;
        }
        return false;
    }

    boolean confirmDiscard(int id) {
        if (id == 224 && this.discardReview) {
            this.discardReview = false;
            if (this.sourceSwitchProposal != null) {
                NBTTagCompound proposal = (NBTTagCompound) this.sourceSwitchProposal.copy();
                this.sourceSwitchProposal = null;
                proposal.setBoolean("confirmDiscard", true);
                if (presetSwitchOperation(proposal.getString("op")))
                    sendPresetSwitch(proposal.getString("op"), proposal.getString("presetId"), true);
                else ui.session.send(proposal.getString("op"), proposal, true);
                return true;
            }
            this.discardConfirmed = true;
            int action = this.deferredLibraryAction;
            String target = this.deferredLibraryTargetId;
            this.deferredLibraryAction = -1;
            this.deferredLibraryTargetId = "";
            if (action == 139 || action == 141 || action >= 14000 && action < 15000) {
                if (target.isEmpty()) ui.session.notice("所选蓝图已变化，请重新选择");
                else sendPresetSwitch(
                    action == 139 ? "editPreset" : action == 141 ? "choosePlacementPreset" : "previewPreset",
                    target,
                    true);
            } else ui.router.activate(action);
            this.discardConfirmed = false;
            return true;
        }
        return false;
    }

    private static boolean presetSwitchOperation(String operation) {
        return "previewPreset".equals(operation) || "editPreset".equals(operation)
            || "choosePlacementPreset".equals(operation);
    }

    private void sendPresetSwitch(String operation, String target, boolean confirmDiscard) {
        if ("previewPreset".equals(operation)) {
            browsePreset(target, !HologramLibraryClient.dedicated() || target.startsWith("public_"));
            return;
        }
        if ("choosePlacementPreset".equals(operation)) {
            HologramLibraryClient library = HologramLibraryClient.INSTANCE;
            if (HologramLibraryClient.dedicated() && !target.startsWith("public_")) library.previewLocal(target, true);
            else library.previewPublic(target, true);
            return;
        }
        if ("editPreset".equals(operation)) {
            browsePreview = false;
            blueprintTab = libraryDrawer = true;
            if (presetNameField != null) presetNameField.setFocused(false);
        }
        if (HologramLibraryClient.dedicated()) {
            HologramLibraryClient library = HologramLibraryClient.INSTANCE;
            // Public IDs already carry their namespace; a refreshed view cannot redirect a confirmed local target.
            boolean publicTarget = target.startsWith("public_");
            publicLibrary = publicTarget;
            library.select(publicTarget, target);
            if (!"choosePlacementPreset".equals(operation))
                rememberLibraryNavigation(target, "editPreset".equals(operation) ? "blueprintEdit" : "blueprintBrowse");
            else cancelLibraryNavigation();
            if (!publicTarget) {
                library.sendLocal(
                    ui.session.state(),
                    "editPreset".equals(operation) ? "edit"
                        : "choosePlacementPreset".equals(operation) ? "place" : "preview",
                    false,
                    confirmDiscard);
                return;
            }
        }
        NBTTagCompound request = new NBTTagCompound();
        request.setString("presetId", target);
        request.setBoolean("confirmDiscard", confirmDiscard);
        keepLibraryOffset = true;
        ui.session.send(operation, request, true);
    }

    boolean toggleOverwriteMode(int id) {
        if (id == 222) {
            this.overwriteMode = !this.overwriteMode;
            if (this.overwriteMode && HologramLibraryClient.dedicated() && this.publicLibrary) {
                this.publicLibrary = false;
                this.libraryOffset = 0;
                ui.session.notice("请在客户端蓝图库选择覆盖目标；公库使用上传／更新公库");
                HologramLibraryClient.INSTANCE.refreshLocal();
            }
            this.overwriteTarget = this.overwriteName = "";
            return true;
        }
        return false;
    }

    boolean prepareLibraryNavigation(int id) {
        if (id == 299 && !discardConfirmed) {
            if (!ui.session.editable()) return true;
            this.discardReview = true;
            this.deferredLibraryAction = id;
            this.deferredLibraryTargetId = "";
            this.sourceSwitchProposal = null;
            return true;
        }
        boolean rowSelection = id >= 14000 && id < 15000;
        // Browsing and world positioning are client displays; only authenticated authoring replaces the draft.
        boolean switching = id == 139 || id == 161;
        if (switching && (!this.overwriteMode || !rowSelection)
            && ui.session.state().data.getBoolean("blueprintDirty")
            && !this.discardConfirmed) {
            this.discardReview = true;
            this.deferredLibraryAction = id;
            this.sourceSwitchProposal = null;
            if (rowSelection) this.deferredLibraryTargetId = (HologramLibraryClient.dedicated()
                ? HologramLibraryClient.INSTANCE.rows(this.publicLibrary)
                : ui.session.state().data.getTagList("presets", 10)).getCompoundTagAt(id - 14000)
                    .getString("id");
            else this.deferredLibraryTargetId = id == 139 || id == 141
                ? HologramLibraryClient.dedicated() ? HologramLibraryClient.INSTANCE.selected(this.publicLibrary)
                    : selectedServerPresetId()
                : "";
            return true;
        }
        if (this.overwriteMode && id >= 14000 && id < 15000) {
            NBTTagCompound row = (HologramLibraryClient.dedicated()
                ? HologramLibraryClient.INSTANCE.rows(this.publicLibrary)
                : ui.session.state().data.getTagList("presets", 10)).getCompoundTagAt(id - 14000);
            this.overwriteTarget = row.getString("id");
            this.overwriteName = row.getString("name");
            return true;
        }
        if (id == 140 && this.overwriteMode) {
            if (!ui.processing.processingReady()) return true;
            if (this.overwriteTarget.isEmpty()) {
                ui.session.notice("先在列表中选择要覆盖的蓝图");
                return true;
            }
            this.overwriteReview = true;
            return true;
        }
        return false;
    }

    boolean selectServerPresetRow(int id) {
        if (this.libraryDrawer && id >= 14000) {
            NBTTagCompound preset = ui.session.state().data.getTagList("presets", 10)
                .getCompoundTagAt(id - 14000);
            browseServerPresetId = preset.getString("id");
            browsePreset(browseServerPresetId, true);
            return true;
        }
        return false;
    }

    boolean activateControl(int id) {
        if (id == 226) {
            if (HologramLibraryClient.dedicated() && this.publicLibrary) {
                if (HologramLibraryClient.INSTANCE.config()
                    .getBoolean("admin")) HologramLibraryClient.INSTANCE.upgradeServer(this::upgradedLibrary);
            } else HologramLibraryClient.INSTANCE.upgradeLocal(this::upgradedLibrary);
            return true;
        }
        if (id == 309 || id == 310) {
            HologramBlueprintWebsite.open(ui.host.parent());
            return true;
        }
        if (ui.canvas.rejectLocalPlanAction(id)) return true;
        switch (id) {
            case 303:
                refreshLibrary();
                return true;
            case 299:
                if (discardConfirmed && ui.session.editable()) {
                    NBTTagCompound discard = new NBTTagCompound();
                    discard.setLong("planRevision", ui.session.revision());
                    ui.session.send("discardBlueprint", discard, true);
                }
                return true;
            case 141:
                if (ui.session.editable() && ui.library.selectedPresetExists()) {
                    HologramLibraryClient.INSTANCE.previewPublic(selectedServerPresetId(), true);
                }
                return true;
            case 148:
                this.blueprintTab = false;
                this.libraryDrawer = false;
                ui.memory.structureVisited(true);
                SAVE_GUIDANCE.visitedStructure(ui.session.state().session);
                ui.memory.rememberGuidance();
                this.presetNameField.setFocused(false);
                ui.memory.rememberUi();
                return true;
            case 149:
                this.blueprintTab = true;
                this.libraryDrawer = true;
                refreshLibrary();
                ui.memory.rememberUi();
                return true;
            case 159:
            case 161:
                if (ui.session.editable()) {
                    NBTTagCompound transfer = new NBTTagCompound();
                    transfer.setString("presetId", selectedServerPresetId());
                    transfer.setString(
                        "fileId",
                        this.presetNameField.getText()
                            .trim());
                    ui.session.send(id == 159 ? "exportBlueprint" : "importBlueprint", transfer, true);
                }
                return true;
            case 130:
                this.blueprintTab = true;
                this.libraryDrawer = true;
                refreshLibrary();
                ui.config.resetReview(false);
                if (ui.session.state().data.getBoolean("virtual") && this.presetNameField.getText()
                    .isEmpty()) this.presetNameField.setText(ui.session.state().data.getString("virtualPresetName"));
                return true;
            case 131:
                if (ui.session.state().data.getBoolean("presetBrowseForPlacement"))
                    ui.session.send("dismissPresetBrowse", null, false);
                this.libraryDrawer = ui.config.resetReview(false);
                this.presetNameField.setFocused(false);
                return true;
            case 175:
                if (!ui.session.editable() || !ui.library.selectedPresetExists()) return true;
                this.deletePresetId = selectedServerPresetId();
                NBTTagList deleteRows = ui.session.state().data.getTagList("presets", 10);
                for (int i = 0; i < deleteRows.tagCount(); i++) {
                    NBTTagCompound row = deleteRows.getCompoundTagAt(i);
                    if (this.deletePresetId.equals(row.getString("id"))) this.deletePresetName = row.getString("name");
                }
                this.presetNameField.setFocused(false);
                return true;
            case 176:
                if (!ui.session.editable() || this.deletePresetId.isEmpty()) return true;
                NBTTagCompound deletion = new NBTTagCompound();
                deletion.setString("presetId", this.deletePresetId);
                this.libraryResult = ui.session.notice("正在删除蓝图，请等待服务器确认");
                ui.session.send("deletePreset", deletion, true);
                this.presetSaveSequence = ui.session.sequence();
                this.deletePresetId = this.deletePresetName = "";
                return true;
            case 177:
                this.deletePresetId = this.deletePresetName = "";
                return true;
            case 139:
                if (!ui.session.editable() || !ui.library.selectedPresetExists()) return true;
                browsePreview = false;
                NBTTagCompound editPreset = new NBTTagCompound();
                editPreset.setString("presetId", selectedServerPresetId());
                this.blueprintTab = true;
                this.libraryDrawer = true;
                this.presetNameField.setFocused(false);
                ui.session.send("editPreset", editPreset, true);
                return true;
            default:
                return false;
        }
    }

    boolean consumeAdminMouse(int mx, int my, int button) {
        if (this.libraryAdminDrawer) {
            this.libraryUploadField.mouseClicked(mx, my, button);
            this.libraryDownloadField.mouseClicked(mx, my, button);
            this.libraryQuotaField.mouseClicked(mx, my, button);
            if (button == 0) for (Control control : ui.canvas.controls())
                if (control.enabled && control.contains(mx, my)) ui.library.activateClientLibrary(control.id);
            return true;
        }
        return false;
    }

    boolean consumeDeleteReviewMouse(int mx, int my, int button) {
        if (!this.deletePresetId.isEmpty()) {
            if (button == 0) for (Control control : ui.canvas.controls()) if (control.contains(mx, my)) {
                if (control.enabled) ui.router.activate(control.id);
                return true;
            }
            return true;
        }
        return false;
    }

    boolean consumeBrowserChooserMouse(int mx, int my, int button) {
        if (this.libraryDrawer || ui.config.resetReview()
            || ui.shortcuts.shortcutsDrawer()
            || ui.config.hatchDrawer()
            || ui.config.presetDrawer()) {
            if (button == 0) for (int i = ui.canvas.controls()
                .size() - 1; i >= 0; i--) {
                    Control control = ui.canvas.controls()
                        .get(i);
                    if (control.contains(mx, my)) {
                        if (control.enabled) ui.router.activate(control.id);
                        return true;
                    }
                }
            if (!this.libraryDrawer || !ui.canvas.inViewport(mx, my) || ui.preview.previewBlockedAt(mx, my))
                return true;
        }
        return false;
    }

    boolean consumeSwitchReviewKey(char c, int key) {
        if (this.discardReview || this.overwriteReview || ui.processing.processingPresetSaveReview()) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E
                && (!ui.processing.processingPresetSaveReview() || !ui.processing.processingPresetField()
                    .isFocused()))
                ui.router.activate(225);
            else if (ui.processing.processingPresetSaveReview()) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) ui.router.activate(224);
                else ui.processing.processingPresetField()
                    .textboxKeyTyped(c, key);
            }
            return true;
        }
        return false;
    }

    boolean consumeAdminKey(char c, int key) {
        if (this.libraryAdminDrawer) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E && !this.libraryUploadField.isFocused()
                && !this.libraryDownloadField.isFocused()
                && !this.libraryQuotaField.isFocused()) this.libraryAdminDrawer = false;
            else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER)
                ui.library.activateClientLibrary(210);
            else {
                this.libraryUploadField.textboxKeyTyped(c, key);
                this.libraryDownloadField.textboxKeyTyped(c, key);
                this.libraryQuotaField.textboxKeyTyped(c, key);
            }
            return true;
        }
        return false;
    }

    boolean consumeDeleteReviewKey(char c, int key) {
        if (!this.deletePresetId.isEmpty()) {
            if (key == Keyboard.KEY_ESCAPE) ui.router.activate(177);
            return true;
        }
        return false;
    }

    boolean consumeBrowserKey(char c, int key) {
        if (this.libraryDrawer) {
            if (browsePreviewActive()) return true;
            if (!ui.config.resetReview()) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) ui.router.activate(140);
                else this.presetNameField.textboxKeyTyped(c, key);
            }
            return true;
        }
        return false;
    }

    boolean consumeBrowserWheel(int wheel, int mx, int my) {
        if (this.libraryDrawer) {
            if (ui.canvas.inViewport(mx, my) && !ui.preview.previewBlockedAt(mx, my)) {
                ui.preview.zoom(Math.max(.25, Math.min(4, ui.preview.zoom() * (wheel > 0 ? 1.1 : 1 / 1.1))));
                return true;
            }
            this.libraryOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        (HologramLibraryClient.dedicated() ? HologramLibraryClient.INSTANCE.rows(this.publicLibrary)
                            : ui.session.state().data.getTagList("presets", 10)).tagCount() - 4),
                    this.libraryOffset + (wheel > 0 ? -1 : 1)));
            return true;
        }
        return false;
    }

    static final class SnapshotTransition {

        final boolean handoff, waiting, sourceChanged, canonicalSaveAck, saveAck;
        final int offset;
        final String previousPreset;

        SnapshotTransition(boolean handoff, boolean waiting, boolean sourceChanged, boolean canonicalSaveAck,
            boolean saveAck, int offset, String previousPreset) {
            this.handoff = handoff;
            this.waiting = waiting;
            this.sourceChanged = sourceChanged;
            this.canonicalSaveAck = canonicalSaveAck;
            this.saveAck = saveAck;
            this.offset = offset;
            this.previousPreset = previousPreset;
        }
    }

    SnapshotTransition prepareSnapshot(HologramState next) {
        boolean handoff = acceptsLibraryNavigation(next);
        boolean waiting = libraryNavigation != null
            && libraryNavigation.source(ui.session.state(), ui.host.minecraft().theWorld, publicLibrary)
            && libraryNavigation.source(next, ui.host.minecraft().theWorld, publicLibrary);
        int offset = handoff ? libraryNavigation.offset : libraryOffset;
        if (!waiting) cancelLibraryNavigation();
        String operation = next.data.getString("blueprintOperation");
        boolean canonical = next.data.getBoolean("virtual") && next.data.getBoolean("blueprintOperationSuccess")
            && ("savePreset".equals(operation) || "saveBlueprintAs".equals(operation)
                || "updatePreset".equals(operation)
                || "exportDraft".equals(operation))
            && (!ui.session.state().data.getBoolean("blueprintOperationSuccess")
                || !HologramDraftNameMemory.canonicalName(ui.session.state().data)
                    .equals(HologramDraftNameMemory.canonicalName(next.data)));
        return new SnapshotTransition(
            handoff,
            waiting,
            !HologramDraftNameMemory.sameSource(ui.session.state().data, next.data),
            canonical,
            saveAcknowledged(next.data, presetSaveSequence),
            offset,
            ui.session.state().data.getString("selectedPresetId"));
    }

    private static boolean saveAcknowledged(NBTTagCompound snapshot, long sequence) {
        String operation = snapshot.getString("blueprintOperation");
        return sequence >= 0 && snapshot.hasKey("uiSequence", 4)
            && snapshot.getLong("uiSequence") == sequence
            && (HologramLibraryClient.saveOperation(operation) || "deletePreset".equals(operation))
            && !snapshot.getBoolean("blueprintSavePending")
            && !snapshot.getBoolean("snapshotPending")
            && !snapshot.getBoolean("workspaceUiOnly")
            && !HologramLibraryClient.SaveCommand.reviewPending(
                snapshot,
                snapshot.getCompoundTag("aeSaveReview")
                    .getLong("clientTaskToken"));
    }

    void restoreSession(HologramState next, SnapshotTransition change) {
        browsePreview = false;
        browseServerPresetId = "";
        UiMemory memory = HologramUiMemory.UI_MEMORY()
            .get(next.session);
        blueprintTab = memory == null ? next.data.getBoolean("presetBrowseForPlacement") || HologramUiMemory.tabMemory()
            .blueprints(next.data.getBoolean("blueprintPreview")) : memory.blueprints;
        libraryDrawer = memory == null ? blueprintTab : memory.libraryDrawer;
        libraryOffset = change.handoff ? change.offset
            : keepLibraryOffset ? libraryOffset : memory == null ? 0 : memory.offset;
        ui.review.resetSession(memory == null ? 0 : memory.reviewNonce);
        presetSaveSequence = -1;
    }

    void acknowledgeSnapshot(HologramState next, SnapshotTransition change, boolean newSession) {
        if (change.sourceChanged && ("area".equals(next.data.getString("blueprintSource"))
            || "discarded".equals(next.data.getString("blueprintSource")))) {
            HologramLibraryClient.INSTANCE.clearSelection();
            overwriteMode = overwriteReview = false;
            overwriteTarget = overwriteName = "";
        }
        boolean saved = change.saveAck && !newSession;
        if (saved) {
            presetSaveSequence = -1;
            libraryResult = next.data.getString("status");
            ui.session.notice(libraryResult);
        }
        if (!change.handoff && !change.waiting
            && !keepLibraryOffset
            && (saved || !change.previousPreset.equals(next.data.getString("selectedPresetId"))))
            revealSelectedPreset();
        keepLibraryOffset = false;
        if ((change.sourceChanged || change.canonicalSaveAck) && presetNameField != null) {
            presetNameField.setText(HologramDraftNameMemory.canonicalName(next.data));
            ui.memory.rememberUi();
        }
    }

    boolean blueprintTab() {
        return this.blueprintTab;
    }

    boolean blueprintTab(boolean value) {
        this.blueprintTab = value;
        return value;
    }

    String deletePresetId() {
        return this.deletePresetId;
    }

    boolean discardConfirmed() {
        return this.discardConfirmed;
    }

    boolean discardReview() {
        return this.discardReview;
    }

    boolean libraryAdminDrawer() {
        return this.libraryAdminDrawer;
    }

    boolean libraryAdminDrawer(boolean value) {
        this.libraryAdminDrawer = value;
        return value;
    }

    long libraryConfigRevision() {
        return this.libraryConfigRevision;
    }

    long libraryConfigRevision(long value) {
        this.libraryConfigRevision = value;
        return value;
    }

    HologramMuiTextField libraryDownloadField() {
        return this.libraryDownloadField;
    }

    boolean libraryDrawer() {
        return this.libraryDrawer;
    }

    boolean libraryDrawer(boolean value) {
        this.libraryDrawer = value;
        return value;
    }

    LibraryNavigation libraryNavigation() {
        return this.libraryNavigation;
    }

    int libraryOffset() {
        return this.libraryOffset;
    }

    int libraryOffset(int value) {
        this.libraryOffset = value;
        return value;
    }

    HologramMuiTextField libraryQuotaField() {
        return this.libraryQuotaField;
    }

    String libraryResult() {
        return this.libraryResult;
    }

    String libraryResult(String value) {
        this.libraryResult = value;
        return value;
    }

    HologramMuiTextField libraryUploadField() {
        return this.libraryUploadField;
    }

    boolean overwriteMode() {
        return this.overwriteMode;
    }

    boolean overwriteReview() {
        return this.overwriteReview;
    }

    String overwriteTarget() {
        return this.overwriteTarget;
    }

    HologramMuiTextField presetNameField() {
        return this.presetNameField;
    }

    HologramMuiTextField presetNameField(HologramMuiTextField value) {
        this.presetNameField = value;
        return value;
    }

    long presetSaveSequence() {
        return this.presetSaveSequence;
    }

    long presetSaveSequence(long value) {
        this.presetSaveSequence = value;
        return value;
    }

    boolean publicLibrary() {
        return this.publicLibrary;
    }

    NBTTagCompound sourceSwitchProposal() {
        return this.sourceSwitchProposal;
    }

    NBTTagCompound backgroundUiState() {
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("blueprintTab", blueprintTab);
        state.setBoolean("libraryDrawer", libraryDrawer);
        state.setBoolean("publicLibrary", publicLibrary);
        state.setInteger("libraryOffset", libraryOffset);
        state.setBoolean("overwriteMode", overwriteMode);
        state.setBoolean("overwriteReview", overwriteReview);
        state.setString("overwriteTarget", overwriteTarget);
        state.setString("overwriteName", overwriteName);
        state.setInteger("deferredLibraryAction", deferredLibraryAction);
        state.setString("deferredLibraryTargetId", deferredLibraryTargetId);
        state.setBoolean("discardReview", discardReview);
        state.setBoolean("discardConfirmed", discardConfirmed);
        state.setBoolean("keepLibraryOffset", keepLibraryOffset);
        state.setString("name", presetNameField == null ? "" : presetNameField.getText());
        state.setString("libraryResult", libraryResult);
        state.setString("deletePresetId", deletePresetId);
        state.setString("deletePresetName", deletePresetName);
        state.setBoolean("libraryAdminDrawer", libraryAdminDrawer);
        state.setLong("libraryConfigRevision", libraryConfigRevision);
        if (libraryUploadField != null) state.setString("upload", libraryUploadField.getText());
        if (libraryDownloadField != null) state.setString("download", libraryDownloadField.getText());
        if (libraryQuotaField != null) state.setString("quota", libraryQuotaField.getText());
        if (sourceSwitchProposal != null) state.setTag("sourceSwitchProposal", sourceSwitchProposal.copy());
        return state;
    }

    void restoreBackgroundUiState(NBTTagCompound state) {
        blueprintTab = state.getBoolean("blueprintTab");
        libraryDrawer = state.getBoolean("libraryDrawer");
        publicLibrary = state.getBoolean("publicLibrary");
        libraryOffset = Math.max(0, state.getInteger("libraryOffset"));
        overwriteMode = state.getBoolean("overwriteMode");
        overwriteReview = state.getBoolean("overwriteReview");
        overwriteTarget = state.getString("overwriteTarget");
        overwriteName = state.getString("overwriteName");
        deferredLibraryAction = state.getInteger("deferredLibraryAction");
        deferredLibraryTargetId = state.getString("deferredLibraryTargetId");
        discardReview = state.getBoolean("discardReview");
        discardConfirmed = state.getBoolean("discardConfirmed");
        keepLibraryOffset = state.getBoolean("keepLibraryOffset");
        if (presetNameField != null) presetNameField.setText(state.getString("name"));
        libraryResult = state.getString("libraryResult");
        deletePresetId = state.getString("deletePresetId");
        deletePresetName = state.getString("deletePresetName");
        libraryConfigRevision = state.getLong("libraryConfigRevision");
        libraryAdminDrawer = state.getBoolean("libraryAdminDrawer");
        if (libraryAdminDrawer) {
            libraryUploadField = libraryAdminField(140, state.getString("upload"));
            libraryDownloadField = libraryAdminField(175, state.getString("download"));
            libraryQuotaField = libraryAdminField(210, state.getString("quota"));
        }
        NBTTagCompound proposal = state.getCompoundTag("sourceSwitchProposal");
        NBTTagCompound current = ui.session.state().data.getCompoundTag("discardTargetAction");
        sourceSwitchProposal = null;
        boolean exactProposal = !proposal.hasNoTags()
            && ui.session.state().data.getBoolean("discardConfirmationRequired")
            && ui.session.state().session.equals(proposal.getString("session"))
            && ui.session.revision() == proposal.getLong("planRevision")
            && !proposal.getString("sourceSwitchNonce")
                .isEmpty()
            && proposal.equals(current);
        if (!exactProposal && (!proposal.hasNoTags() || !current.hasNoTags())) {
            discardReview = false;
            discardConfirmed = false;
            deferredLibraryAction = -1;
            deferredLibraryTargetId = "";
        }
        // A source proposal may have arrived while the screen was closed. The current server snapshot wins.
        acceptSourceSwitchProposal();
    }

    void prepareInitialization() {
        if (ui.library.presetNameField() != null) ui.memory.rememberUi();
        if (HologramLibraryClient.dedicated()) HologramLibraryClient.INSTANCE.open();
        else refreshLibrary();
    }

    private void refreshLibrary() {
        if (HologramLibraryClient.dedicated()) HologramLibraryClient.INSTANCE.refreshLocal();
        else ui.session.send("refreshPresetLibrary", null, false);
    }

    /** Modal controls are appended after the library rows; only the top modal may own this click. */
    boolean consumeSwitchReviewMouse(int mx, int my, int button) {
        if (!discardReview && !overwriteReview && !ui.processing.processingPresetSaveReview()) return false;
        if (ui.processing.processingPresetSaveReview() && ui.processing.processingPresetField() != null)
            ui.processing.processingPresetField()
                .mouseClicked(mx, my, button);
        Control control = switchReviewControl(ui.canvas.controls(), mx, my);
        if (button == 0 && control != null && control.enabled) ui.router.activate(control.id);
        return true;
    }

    static Control switchReviewControl(java.util.List<Control> controls, int mx, int my) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            Control control = controls.get(i);
            if ((control.id == 224 || control.id == 225) && control.contains(mx, my)) return control;
        }
        return null;
    }

    void initializeFields() {
        ui.library.presetNameField(
            new HologramMuiTextField(ui.host.parent(), 457, 78, HologramScreenLayout.COMPACT_PANEL_WIDTH - 10, 16));
        ui.library.presetNameField()
            .setMaxStringLength(240);
        if (ui.session.state().data.getBoolean("virtual")) ui.library.presetNameField()
            .setText(ui.session.state().data.getString("virtualPresetName"));
        UiMemory memory = HologramUiMemory.UI_MEMORY()
            .get(ui.session.state().session);
        if (memory != null) ui.library.presetNameField()
            .setText(memory.draftName.restore(ui.session.state().data));
    }

    void tickStatus() {
        if (ui.library.presetSaveSequence() >= 0 && !HologramLibraryClient.INSTANCE.status()
            .isEmpty()) ui.library.libraryResult(ui.session.notice(HologramLibraryClient.INSTANCE.status()));
        if (ui.library.libraryAdminDrawer()) {
            NBTTagCompound libraryConfig = HologramLibraryClient.INSTANCE.config();
            if (!libraryConfig.getBoolean("admin")) ui.library.libraryAdminDrawer(false);
            else {
                if (ui.library.libraryConfigRevision() != libraryConfig.getLong("configRevision")) {
                    ui.library.libraryUploadField()
                        .setText(String.valueOf(libraryConfig.getInteger("uploadKiB")));
                    ui.library.libraryDownloadField()
                        .setText(String.valueOf(libraryConfig.getInteger("downloadKiB")));
                    ui.library.libraryQuotaField()
                        .setText(String.valueOf(libraryConfig.getInteger("maxUploads")));
                    ui.library.libraryConfigRevision(libraryConfig.getLong("configRevision"));
                }
                ui.library.libraryUploadField()
                    .updateCursorCounter();
                ui.library.libraryDownloadField()
                    .updateCursorCounter();
                ui.library.libraryQuotaField()
                    .updateCursorCounter();
            }
        }
    }

    void tickNameField() {
        if (ui.library.presetNameField() != null) ui.library.presetNameField()
            .updateCursorCounter();
    }

    void prepareCommand(NBTTagCompound request) {
        if (discardConfirmed) request.setBoolean("confirmDiscard", true);
    }

    void initializeBrowsing(HologramState snapshot) {
        libraryDrawer = snapshot.data.getBoolean("presetBrowseForPlacement")
            || snapshot.data.getBoolean("blueprintPreview");
    }

    void initializeTab(HologramState snapshot) {
        blueprintTab = snapshot.data.getBoolean("presetBrowseForPlacement") || HologramUiMemory.tabMemory()
            .blueprints(libraryDrawer);
        libraryDrawer = blueprintTab;
    }

    void restoreInitialUi(UiMemory memory) {
        blueprintTab = memory.blueprints;
        libraryDrawer = memory.libraryDrawer;
        libraryOffset = memory.offset;
    }
}
