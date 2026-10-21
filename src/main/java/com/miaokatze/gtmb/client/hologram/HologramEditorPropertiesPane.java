package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Native right dock. The domain modules remain the sole authority for selection and edits. */
final class HologramEditorPropertiesPane {

    private final HologramUiModules ui;
    private final HologramEditorConfigurationDialogs dialogs;
    private boolean selectedContext;
    private boolean targetExpanded, sourceExpanded, materialExpanded;
    private Flow root;

    HologramEditorPropertiesPane(HologramUiModules ui) {
        this.ui = ui;
        dialogs = new HologramEditorConfigurationDialogs(ui);
        selectedContext = ui.session.selectedCell() != null;
        targetExpanded = selectedContext;
        sourceExpanded = !selectedContext;
    }

    IWidget build() {
        dialogs.prepare();
        ListWidget<IWidget, ?> list = new ListWidget<>().name("editor_native_properties_list")
            .sizeRel(1f);
        list.child(label(() -> "目标属性"));
        list.child(
            label(
                () -> ui.session.selectedCell() == null ? "点击预览选择位置或来源结构"
                    : "所选 " + ui.preview.selection()
                        .size() + " 格 · Ctrl 多选 / T 同种"));

        Flow source = section();
        source.child(label(() -> "来源：" + sourceLabel(ui, ui.config.selectedStructureId())));
        source.child(
            label(
                () -> ui.session.selectedCell() != null && ui.session.selectedCell().sourceMissing
                    ? "来源缺失：" + ui.session.selectedCell().sourceWarning
                    : "范围：" + (ui.preview.selection()
                        .size() > 0 && !ui.session.state().data.getBoolean("wholeBlueprintPlacement")
                        && !ui.session.state().data.getBoolean("virtualPlacement") ? "当前选区" : "当前完整规划")));
        Flow modes = row();
        String[] modeNames = { "补建", "替换", "拆除" };
        for (int i = 0; i < modeNames.length; i++) {
            final int mode = i;
            modes.child(
                action(
                    70 + i,
                    () -> (ui.session.state().data.getInteger("mode") == mode ? "✓ " : "") + modeNames[mode],
                    () -> ui.session.editable(),
                    () -> ui.session.editingReason()).expanded());
        }
        source.child(modes);
        Flow structure = section();
        structure.setEnabledIf(
            w -> !ui.config.selectedStructureId()
                .isEmpty());
        structure.child(
            action(
                163,
                () -> ui.session.selectedCell() != null && ui.session.selectedCell().sourceMissing ? "来源缺失"
                    : "进入来源结构编辑",
                () -> ui.session.editable() && !ui.config.selectedStructureId()
                    .isEmpty(),
                () -> "分别编辑此来源成员的主值与信道。"));
        structure.child(
            action(
                164,
                () -> "参与施工：" + (ui.config.structureEnabled(ui.config.selectedStructureId()) ? "是" : "否"),
                () -> ui.session.editable() && !ui.config.selectedStructureId()
                    .isEmpty(),
                () -> "改变来源成员参与施工的状态。"));
        source.child(structure);
        Flow back = section();
        back.setEnabledIf(
            w -> !ui.session.state().data.getString("activeStructure")
                .isEmpty());
        back.child(
            action(
                165,
                () -> "返回区域总览",
                () -> ui.session.editable() && !ui.session.state().data.getString("activeStructure")
                    .isEmpty(),
                () -> "保留已接受的成员配置并返回。"));
        source.child(back);
        source.child(
            action(
                60,
                () -> "结构等级 / 信道",
                () -> !ui.session.state().data.getBoolean("virtual")
                    || !ui.session.state().data.getString("activeStructure")
                        .isEmpty(),
                () -> "当前来源结构的主值、动态能力、具名信道及继承值。"));
        Flow target = section();
        target.child(label(() -> {
            HologramState.Cell cell = ui.session.selectedCell();
            return cell == null ? ""
                : "位置 " + cell.dx + "," + cell.dy + "," + cell.dz + " · 当前：" + ui.preview.cellName(cell, true);
        }));
        target.child(label(() -> {
            HologramState.Cell cell = ui.session.selectedCell();
            return cell == null ? "目标：未选中" : "目标：" + ui.preview.cellName(cell, false);
        }));
        Flow grade = row();
        grade.setEnabledIf(w -> ui.config.primaryCapability() != null);
        grade.child(action(80, () -> "−", () -> canStep(-1), () -> "连续降低本结构声明的等级。").width(24));
        grade.child(action(81, () -> {
            NBTTagCompound cap = ui.config.primaryCapability();
            return cap == null ? "无独立等级" : ui.config.optionLabel(cap);
        }, () -> ui.session.editable() && ui.config.primaryCapability() != null, () -> "从此能力的真实选项中选择，或填写原始正整数。")
            .expanded());
        grade.child(action(82, () -> "+", () -> canStep(1), () -> "连续提高本结构声明的等级。").width(24));
        target.child(grade);
        target.child(
            action(
                95,
                () -> "类型 / 等级 / 可替换目标",
                () -> ui.session.editable() && ui.session.selectedCell() != null,
                () -> ui.config.candidateNote()));
        Flow facing = row();
        facing.setEnabledIf(
            w -> ui.session.cellTag()
                .getIntArray("hatchFacings").length > 0);
        facing.child(action(99, () -> {
            int[] legal = ui.session.cellTag()
                .getIntArray("hatchFacings");
            return legal.length == 0 ? "仓室朝向不可用"
                : "仓室朝向：" + ui.config.directionName(
                    ui.session.cellTag()
                        .getInteger("hatchFacing"));
        },
            () -> ui.session.editable() && ui.session.cellTag()
                .getIntArray("hatchFacings").length > 0,
            () -> "只轮换当前设备与声明共同允许的朝向；仅修改当前格。").expanded());
        facing.child(
            action(
                102,
                () -> "自动",
                () -> ui.session.editable() && ui.session.cellTag()
                    .getBoolean("hasFacingPin"),
                () -> "清除显式朝向，恢复自动朝向。").width(44));
        target.child(facing);
        Flow edit = row();
        edit.child(
            action(
                112,
                () -> "复制",
                () -> ui.session.editable() && ui.session.selectedCell() != null,
                () -> "复制所选目标；沿用当前模式的剪贴板语义。").expanded());
        edit.child(
            action(
                113,
                () -> "转向",
                () -> ui.session.editable() && ui.session.selectedCell() != null,
                () -> "普通方块状态转向或设备合法朝向。").expanded());
        target.child(edit);
        Flow materialTarget = row();
        materialTarget.child(
            action(
                66,
                () -> "恢复默认壳",
                () -> ui.session.editable() && ui.session.selectedCell() != null && !ui.session.selectedCell().anchor,
                () -> "按来源声明恢复默认目标；作者总览中的原动作是删除格。").expanded());
        materialTarget.child(
            action(
                61,
                () -> "背包选材",
                () -> ui.session.editable() && ui.session.state().data.getInteger("mode") != 2,
                () -> "选择真实物品样本，再指定当前一格；不消耗物品。").expanded());
        target.child(materialTarget);
        target.child(
            action(
                94,
                () -> "仓室预设",
                () -> ui.session.editable() && ui.session.state().data.getInteger("mode") != 2,
                () -> "基础声明配额或本机保存的精确仓室搭配；应用仍受原服务端机器与模式校验。"));
        Flow materials = section();
        materials.child(action(78, () -> "材料来源", () -> ui.session.editable(), () -> "主背包、容器、ME 终端开关与优先次序。"));
        materials.child(
            action(
                94,
                () -> "仓室预设",
                () -> ui.session.editable() && ui.session.state().data.getInteger("mode") != 2,
                () -> "无需选中格也可使用整机声明配额或精确搭配。"));
        materials.child(
            action(
                134,
                () -> ui.session.state().data.getBoolean("structureBuilt") ? "校准为当前现场" : "重置为默认规划",
                () -> ui.session.editable() && ui.preview.previewInteractive()
                    && ui.session.state().data.getBoolean("target")
                    && ui.session.state().data.getBoolean("supported"),
                () -> "沿用原重置 / 校准确认；不施工、不删除已保存预设。"));
        materials.child(label(() -> ui.session.state().data.getString("status")));

        Flow targetGroup = group(
            "target",
            "目标与仓室",
            () -> targetExpanded,
            () -> targetExpanded = !targetExpanded,
            target);
        targetGroup.setEnabledIf(w -> ui.session.selectedCell() != null);
        list.child(targetGroup);
        list.child(group("source", "来源与信道", () -> sourceExpanded, () -> sourceExpanded = !sourceExpanded, source));
        list.child(
            group("material", "材料与重置", () -> materialExpanded, () -> materialExpanded = !materialExpanded, materials));
        root = Flow.col()
            .sizeRel(1f)
            .padding(6)
            .childPadding(4)
            .child(list);
        return root;
    }

    void refresh() {
        boolean selected = ui.session.selectedCell() != null;
        if (selected != selectedContext) {
            selectedContext = selected;
            targetExpanded = selected;
            sourceExpanded = !selected;
            materialExpanded = false;
        }
        dialogs.refresh();
    }

    boolean groupExpanded(String key) {
        return switch (key) {
            case "target" -> targetExpanded;
            case "source" -> sourceExpanded;
            case "material" -> materialExpanded;
            default -> false;
        };
    }

    void restoreGroups(boolean target, boolean source, boolean material) {
        targetExpanded = target;
        sourceExpanded = source;
        materialExpanded = material;
        // Checkpoint selection is already restored; do not reapply the normal selection-change defaults next tick.
        selectedContext = ui.session.selectedCell() != null;
        if (root != null) root.scheduleResize();
    }

    private Flow group(String key, String title, BooleanSupplier expanded, Runnable toggle, Flow content) {
        Flow group = section();
        ButtonWidget<?> heading = HologramEditorNativeTheme
            .button(() -> (expanded.getAsBoolean() ? "▾ " : "▸ ") + title, () -> {
                toggle.run();
                content.setEnabled(expanded.getAsBoolean());
                group.scheduleResize();
            })
            .name("properties." + key)
            .widthRel(1f)
            .height(23);
        ui.shell.registerTarget("properties." + key, heading);
        content.setEnabledIf(w -> expanded.getAsBoolean());
        return group.child(heading)
            .child(content);
    }

    private static Flow section() {
        return Flow.col()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(4)
            .collapseDisabledChild(true);
    }

    private boolean canStep(int delta) {
        NBTTagCompound cap = ui.config.primaryCapability();
        return ui.session.editable() && cap != null && ui.config.canStep(cap, delta);
    }

    ButtonWidget<?> action(int id, Supplier<String> label, BooleanSupplier enabled, Supplier<String> tooltip) {
        return ui.shell.nativeAction(id, label, enabled, tooltip)
            .widthRel(1f)
            .height(20);
    }

    static IWidget label(Supplier<String> text) {
        return HologramEditorNativeTheme.label(text)
            .widthRel(1f)
            .height(24);
    }

    static String sourceLabel(HologramUiModules ui, String id) {
        if (id.isEmpty())
            return ui.session.state().data.getBoolean("virtual") ? "区域总览" : ui.session.state().data.getString("title");
        NBTTagList structures = ui.session.state().data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            NBTTagCompound structure = structures.getCompoundTagAt(i);
            if (id.equals(structure.getString("id"))) return structure.getString("name") + " @ "
                + structure.getInteger("x")
                + ","
                + structure.getInteger("y")
                + ","
                + structure.getInteger("z");
        }
        return id;
    }

    static Flow row() {
        return Flow.row()
            .widthRel(1f)
            .height(20)
            .childPadding(4)
            .crossAxisAlignment(Alignment.CrossAxis.START);
    }
}
