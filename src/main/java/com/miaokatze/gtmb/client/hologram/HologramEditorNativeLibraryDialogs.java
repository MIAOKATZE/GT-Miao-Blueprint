package com.miaokatze.gtmb.client.hologram;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Client-local native popups with one handler and one widget tree per purpose for the screen lifetime. */
final class HologramEditorNativeLibraryDialogs {

    private enum Kind {
        DISCARD,
        OVERWRITE,
        DELETE,
        ADMIN,
        PRESET
    }

    private final HologramUiModules ui;
    private final Map<Kind, IPanelHandler> handlers = new EnumMap<>(Kind.class);
    private final StringValue presetName = new StringValue(""), upload = new StringValue(""),
        download = new StringValue(""), quota = new StringValue("");
    private Kind displayed;
    private long adminRevision = Long.MIN_VALUE;

    HologramEditorNativeLibraryDialogs(HologramUiModules ui) {
        this.ui = ui;
    }

    void refresh() {
        Kind desired = desired();
        IPanelHandler current = displayed == null ? null : handlers.get(displayed);
        if (desired == Kind.ADMIN && desired == displayed
            && current != null
            && current.isPanelOpen()
            && adminRevision != ui.library.libraryConfigRevision()) loadAdmin();
        if (desired == displayed && (desired == null || current != null && current.isPanelOpen())) return;
        // No separate queued latch: the screen accepts one deferred intent. If
        // another input already owns it, the next update retries this transition.
        ui.host.parent()
            .deferAction(this::transition);
    }

    private Kind desired() {
        if (ui.processing.processingPresetSaveReview()) return Kind.PRESET;
        if (ui.library.overwriteReview()) return Kind.OVERWRITE;
        if (ui.library.discardReview()) return Kind.DISCARD;
        if (!ui.library.deletePresetId()
            .isEmpty()) return Kind.DELETE;
        return ui.library.libraryAdminDrawer() ? Kind.ADMIN : null;
    }

    private void transition() {
        Kind desired = desired();
        displayed = desired;
        for (Map.Entry<Kind, IPanelHandler> entry : handlers.entrySet())
            if (entry.getKey() != desired && entry.getValue()
                .isPanelOpen())
                entry.getValue()
                    .closePanel();
        if (desired == null) return;
        IPanelHandler handler = handlers.computeIfAbsent(
            desired,
            key -> IPanelHandler.simple(ui.shell.host(), (parent, player) -> build(key), true));
        if (handler.isPanelOpen()) return;
        if (desired == Kind.PRESET) presetName.setStringValue(
            ui.processing.processingPresetField()
                .getText());
        if (desired == Kind.ADMIN) loadAdmin();
        handler.openPanel();
    }

    private void loadAdmin() {
        upload.setStringValue(
            ui.library.libraryUploadField()
                .getText());
        download.setStringValue(
            ui.library.libraryDownloadField()
                .getText());
        quota.setStringValue(
            ui.library.libraryQuotaField()
                .getText());
        adminRevision = ui.library.libraryConfigRevision();
    }

    private ModularPanel build(Kind kind) {
        Dialog<?> dialog = new Dialog<>(
            "gtmb_native_library_" + kind.name()
                .toLowerCase(java.util.Locale.ROOT),
            null);
        dialog.setDisablePanelsBelow(true)
            .setCloseOnOutOfBoundsClick(false)
            .setDraggable(true)
            .size(360, kind == Kind.ADMIN ? 260 : kind == Kind.PRESET ? 196 : 184)
            .background(HologramEditorNativeTheme.PANEL);
        dialog.onCloseAction(
            () -> {
                if (desired() == kind && ui.host.minecraft().currentScreen == ui.host.parent())
                    ui.router.activate(cancelId(kind));
            });
        Flow content = Flow.col()
            .sizeRel(1f)
            .padding(12)
            .childPadding(7);
        content.child(
            HologramEditorNativeTheme.label(() -> title(kind), HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(22));
        if (kind == Kind.ADMIN) admin(content);
        else if (kind == Kind.PRESET) {
            content.child(
                HologramEditorNativeTheme.label(() -> "为已核对的处理选择命名，供下次导入复用。")
                    .widthRel(1f)
                    .height(32));
            content.child(
                HologramEditorNativeTheme.textField(presetName, 64)
                    .name("processing_preset_name")
                    .widthRel(1f)
                    .height(22));
            content.child(
                HologramEditorNativeTheme.label(() -> "只保存全局策略，不复制本图设备数据。")
                    .widthRel(1f)
                    .expanded());
        } else {
            content.child(
                HologramEditorNativeTheme
                    .label(
                        () -> kind == Kind.DELETE ? "删除蓝图：" + ui.library.nativeDeleteName() + "\n当前编辑稿保留；此操作不能撤销。"
                            : ui.library.nativeReviewBody())
                    .widthRel(1f)
                    .height(48));
            content.child(
                HologramEditorNativeTheme
                    .label(() -> kind == Kind.DELETE ? "只删除所选库中的文件对。" : ui.library.nativeReviewTarget())
                    .widthRel(1f)
                    .expanded());
        }
        Flow footer = Flow.row()
            .widthRel(1f)
            .height(24)
            .childPadding(8);
        footer.child(
            ui.shell
                .nativeAction(
                    cancelId(kind),
                    () -> kind == Kind.ADMIN ? "关闭" : "取消",
                    () -> true,
                    () -> "返回原上下文，保留未提交内容。")
                .expanded()
                .height(24));
        footer.child(
            confirm(kind).expanded()
                .height(24));
        content.child(footer);
        dialog.child(content);
        return dialog;
    }

    private void admin(Flow content) {
        content.child(
            HologramEditorNativeTheme.label(() -> "上传 / 下载限速单位 KiB/s；每人配额按份计。")
                .widthRel(1f)
                .height(30));
        content.child(adminField("客户端上传带宽", upload));
        content.child(adminField("客户端下载带宽", download));
        content.child(adminField("每位玩家上传配额", quota));
        content.child(
            HologramEditorNativeTheme.label(ui.library::nativeStatus)
                .widthRel(1f)
                .expanded());
    }

    private Flow adminField(String label, StringValue value) {
        return Flow.row()
            .widthRel(1f)
            .height(23)
            .childPadding(8)
            .child(
                HologramEditorNativeTheme.label(() -> label)
                    .expanded()
                    .height(20))
            .child(
                HologramEditorNativeTheme.textField(value, 7)
                    .width(118)
                    .height(22));
    }

    private ButtonWidget<?> confirm(Kind kind) {
        int id = kind == Kind.DELETE ? 176 : kind == Kind.ADMIN ? 210 : 224;
        BooleanSupplier enabled = () -> kind == Kind.ADMIN ? ui.library.nativeAdmin()
            : kind == Kind.PRESET ? ui.session.editable() && !presetName.getStringValue()
                .trim()
                .isEmpty()
                : kind == Kind.DELETE ? ui.session.editable()
                    : ui.session.editable()
                        || HologramBackgroundTasks.awaitingSourceReview() && ui.session.pendingSequence() < 0;
        Supplier<String> label = () -> kind == Kind.PRESET ? "确认保存流程预设"
            : kind == Kind.DELETE ? "确认删除"
                : kind == Kind.ADMIN ? "保存并等待服务器确认" : kind == Kind.OVERWRITE ? "确认覆盖保存" : "放弃改动并继续";
        return ui.shell.nativeAction(id, label, enabled, () -> "仍由原业务门禁和服务器核对本次请求。")
            .onMousePressed(button -> {
                if (button != 0 || !enabled.getAsBoolean() || desired() != kind) return true;
                if (kind == Kind.PRESET) ui.processing.processingPresetField()
                    .setText(presetName.getStringValue());
                if (kind == Kind.ADMIN) {
                    ui.library.libraryUploadField()
                        .setText(upload.getStringValue());
                    ui.library.libraryDownloadField()
                        .setText(download.getStringValue());
                    ui.library.libraryQuotaField()
                        .setText(quota.getStringValue());
                }
                ui.router.activate(id);
                return true;
            });
    }

    private int cancelId(Kind kind) {
        return kind == Kind.DELETE ? 177 : kind == Kind.ADMIN ? 211 : 225;
    }

    private String title(Kind kind) {
        return kind == Kind.ADMIN ? "服务器蓝图库设置 · OP 4"
            : kind == Kind.DELETE ? "删除所选蓝图" : kind == Kind.PRESET ? "保存流程预设" : ui.library.nativeReviewTitle();
    }
}
