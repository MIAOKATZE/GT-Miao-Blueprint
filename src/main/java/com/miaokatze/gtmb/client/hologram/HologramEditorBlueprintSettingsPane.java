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
    private int pendingTendency = -1;
    private long pendingTendencySequence = -1;
    private String pendingTendencySession = "";

    HologramEditorBlueprintSettingsPane(HologramUiModules ui) {
        this.ui = ui;
    }

    IWidget build() {
        if (built != null) return built;
        Flow content = Flow.col()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(5)
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
            HologramEditorNativeTheme.label(() -> "默认处理预设", HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(20));
        Flow defaults = Flow.row()
            .widthRel(1f)
            .height(24)
            .childPadding(4);
        defaults.child(defaultTendency(false).expanded());
        defaults.child(defaultTendency(true).expanded());
        content.child(defaults);
        content.child(
            HologramEditorNativeTheme.label(() -> "自用保留可用关系；分享按原流程推荐清理。细项仍可调整。")
                .widthRel(1f)
                .height(32));
        content.child(
            action(
                220,
                () -> "开始蓝图处理流程",
                () -> author() && !ui.processing.processingReady(),
                () -> "按实际内容核对流程预设、身份、量子网络、坐标、NBT 与提示词。").widthRel(1f)
                    .height(24));
        content.child(
            action(
                297,
                () -> "重新进行蓝图预设",
                () -> author() && ui.processing.processingReady(),
                () -> "按当前草稿重新核对六阶段；已移除的数据不会恢复。").widthRel(1f)
                    .height(24));
        content.child(
            action(
                221,
                () -> "保存流程预设",
                () -> author() && ui.processing.processingReady(),
                () -> "保存已核对的全局处理选择，不复制某张蓝图的设备内容。").widthRel(1f)
                    .height(22));
        content.child(
            action(
                166,
                () -> ui.preview.selected() >= 0 ? "所选格 NBT" : "方块 NBT",
                () -> author() && ui.processing.canEditBlueprintNbt(),
                () -> "沿用已有逐格 NBT 及资源路径审核。").widthRel(1f)
                    .height(22));
        content.child(
            action(146, () -> "所选格提示词", () -> editable() && ui.preview.selected() >= 0, () -> "只编辑当前选格提示；全局提示词使用独立入口。")
                .widthRel(1f)
                .height(22));
        content.child(
            action(227, () -> "全局提示词", this::editable, () -> "编辑整份蓝图提示。").widthRel(1f)
                .height(22));
        content.child(
            action(223, () -> "旋转查看 ↻", () -> true, () -> "只改变观察方向，不旋转世界中的机器。").widthRel(1f)
                .height(22));
        content.child(
            action(
                134,
                () -> ui.session.state().data.getBoolean("structureBuilt") ? "校准为当前" : "重置为默认",
                () -> editable() && ui.preview.previewInteractive()
                    && ui.session.state().data.getBoolean("target")
                    && ui.session.state().data.getBoolean("supported"),
                () -> "沿用当前结构默认规划的独立确认；不撤回已施工内容。").widthRel(1f)
                    .height(22));
        built = new HologramRecessedList().paperEdges()
            .name("native_blueprint_settings")
            .sizeRel(1f)
            .child(content);
        return built;
    }

    void refresh() {
        if (pendingTendency < 0) return;
        if (!pendingTendencySession.equals(ui.session.state().session)) {
            pendingTendency = -1;
            pendingTendencySequence = -1;
            return;
        }
        if (ui.processing.processingFlow() != null) {
            final int choice = pendingTendency;
            pendingTendency = -1;
            pendingTendencySequence = -1;
            ui.host.parent()
                .deferAction(() -> {
                    if (!author() || ui.processing.processingFlow() == null) return;
                    try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                        .fieldGraph(ui)) {
                        ui.router.activate(choice);
                    }
                });
        } else if (pendingTendencySequence >= 0 && ui.session.pendingSequence() < 0
            && ui.session.state().data.getLong("uiSequence") >= pendingTendencySequence) {
                pendingTendency = -1;
                pendingTendencySequence = -1;
            }
    }

    private ButtonWidget<?> defaultTendency(boolean share) {
        String label = share ? "倾向分享" : "倾向自用";
        ButtonWidget<?> button = HologramEditorNativeTheme.button(() -> label, "process", () -> {
            ui.host.parent()
                .deferAction(() -> {
                    if (!author()) return;
                    try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                        .fieldGraph(ui)) {
                        if (ui.processing.processingReady()) {
                            // Wait for the authoritative report and original flow before applying the chosen tendency.
                            pendingTendency = share ? 233 : 232;
                            pendingTendencySession = ui.session.state().session;
                            ui.router.activate(297);
                            pendingTendencySequence = ui.session.sequence();
                            return;
                        }
                        ui.router.activate(220);
                        ui.router.activate(share ? 233 : 232);
                    }
                });
        }, this::author, () -> false, false)
            .widthRel(1f)
            .height(24)
            .tooltipBuilder(
                t -> t.addLine(
                    ui.processing.processingReady() ? "按当前稿重新准备处理报告并选用" + label + "；已移除数据不会恢复。"
                        : "打开真实处理流程并选用" + label + "默认策略；量子、坐标和 NBT 继续按原规则校验。"))
            .tooltipAutoUpdate(true);
        ui.shell.registerTarget(share ? "processing.default.share" : "processing.default.self", button);
        return button;
    }

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
