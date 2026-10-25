package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Blueprint authoring actions live in the dock, never in a horizontal band over the model. */
final class HologramEditorBlueprintSettingsPane {

    private final HologramUiModules ui;
    private IWidget built;

    HologramEditorBlueprintSettingsPane(HologramUiModules ui) {
        this.ui = ui;
    }

    IWidget build() {
        if (built != null) return built;
        Flow content = Flow.col()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(3)
            .padding(6);
        content.child(
            HologramEditorNativeTheme.label(() -> "蓝图设置", HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(20));
        content.child(
            HologramEditorNativeTheme
                .label(
                    () -> ui.library.blueprintSaveState() + " · "
                        + (ui.session.state().data.getBoolean("blueprintDirectSave") ? "可直接保存"
                            : ui.processing.processingReady() ? "处理已完成" : "选择处理方案后应用"))
                .widthRel(1f)
                .height(32));
        content.child(
            action(
                220,
                () -> ui.processing.processingReady() ? "重新核对蓝图处理" : "开始蓝图处理流程",
                this::author,
                () -> "打开处理弹窗，选择倾向自用、倾向分享或已保存的流程；完成后在窗内保存蓝图。").widthRel(1f)
                    .height(22));
        content.child(
            action(
                166,
                () -> ui.preview.selected() >= 0 ? "所选格 NBT" : "方块 NBT",
                () -> author() && ui.processing.canEditBlueprintNbt(),
                () -> "沿用已有逐格 NBT 及资源路径审核。").widthRel(1f)
                    .height(18));
        content.child(
            action(146, () -> "所选格提示词", () -> editable() && ui.preview.selected() >= 0, () -> "只编辑当前选格提示；全局提示词使用独立入口。")
                .widthRel(1f)
                .height(18));
        content.child(
            action(227, () -> "全局提示词", this::editable, () -> "编辑整份蓝图提示。").widthRel(1f)
                .height(18));
        content.child(
            action(223, () -> "旋转查看 ↻", () -> true, () -> "只改变观察方向，不旋转世界中的机器。").widthRel(1f)
                .height(18));
        content.child(
            action(
                134,
                () -> ui.session.state().data.getBoolean("structureBuilt") ? "校准为当前" : "重置为默认",
                () -> editable() && ui.preview.previewInteractive()
                    && ui.session.state().data.getBoolean("target")
                    && ui.session.state().data.getBoolean("supported"),
                () -> "沿用当前结构默认规划的独立确认；不撤回已施工内容。").widthRel(1f)
                    .height(18));
        built = new HologramRecessedList().paperEdges()
            .name("native_blueprint_settings")
            .sizeRel(1f)
            .child(content);
        return built;
    }

    void refresh() {}

    private boolean editable() {
        return ui.session.editable() && !ui.library.browsePreviewActive();
    }

    private boolean author() {
        return editable() && HologramGuidance.authorWorkspace(ui.session.state())
            && !ui.session.state().cells.isEmpty();
    }

    private ButtonWidget<?> action(int id, Supplier<String> label, BooleanSupplier enabled, Supplier<String> tooltip) {
        return ui.shell.nativeAction(id, label, enabled, tooltip);
    }
}
