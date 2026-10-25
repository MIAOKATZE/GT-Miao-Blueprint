package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

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
            Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(4)
                .child(
                    HologramEditorNativeTheme.label(() -> "蓝图设置", HologramEditorNativeTheme.TITLE)
                        .expanded()
                        .height(20))
                .child(
                    ui.shell
                        .nativeIconAction(
                            HologramEditorControlIds.BLUEPRINT_ROTATE,
                            "rotate",
                            () -> "旋转默认放置朝向",
                            this::canRotateBlueprint,
                            this::rotationTooltip)
                        .size(HologramEditorNativeTheme.ICON_BUTTON)));
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

    boolean canRotateBlueprint() {
        NBTTagCompound state = ui.session.state().data;
        return ui.workspaces.blueprint() && editable()
            && state.getBoolean("geometryEditable")
            && state.getInteger("virtualSchema") >= 2
            && !state.getBoolean("virtualPlacement")
            && !state.getBoolean("localPlanPending")
            && state.getInteger("job") != 4
            && !state.getBoolean("capturePending")
            && !state.getBoolean("snapshotPending")
            && !ui.session.state().cells.isEmpty();
    }

    void rotateBlueprint() {
        if (!canRotateBlueprint()) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", ui.session.revision());
        ui.session.send("rotateBlueprint", request, true);
    }

    private String rotationTooltip() {
        int facing = ui.session.state().data.getInteger("facing");
        ForgeDirection direction = facing >= 0 && facing < ExtendedFacing.values().length
            ? ExtendedFacing.values()[facing].getDirection()
            : ForgeDirection.UNKNOWN;
        String heading = switch (direction) {
            case NORTH -> "当前：北 → 下次：东";
            case EAST -> "当前：东 → 下次：南";
            case SOUTH -> "当前：南 → 下次：西";
            case WEST -> "当前：西 → 下次：北";
            default -> "保留原有竖直姿态，仅调整水平布局";
        };
        return heading + "\n每次水平旋转整份蓝图 90°，四个固定方向循环。" + "\n保存后，下次载入和放置沿用此朝向。" + "\nCtrl+Z 撤销 · Ctrl+S 快捷保存";
    }

    private ButtonWidget<?> action(int id, Supplier<String> label, BooleanSupplier enabled, Supplier<String> tooltip) {
        return ui.shell.nativeAction(id, label, enabled, tooltip);
    }
}
