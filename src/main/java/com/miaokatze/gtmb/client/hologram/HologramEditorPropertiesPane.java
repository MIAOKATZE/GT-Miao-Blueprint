package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
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
    private final List<Flow> sizingSections = new ArrayList<>();
    private int sizingVisibility = -1;
    private final List<Flow> roots = new ArrayList<>();
    private Flow selectionRoot, structureRoot, materialsRoot, constructionRoot;
    private boolean prepared;

    HologramEditorPropertiesPane(HologramUiModules ui) {
        this.ui = ui;
        dialogs = new HologramEditorConfigurationDialogs(ui);
        selectedContext = ui.session.selectedCell() != null;
        targetExpanded = selectedContext;
        sourceExpanded = true;
        materialExpanded = true;
    }

    /** Compatibility entry point for callers that still open the old properties page. */
    IWidget build() {
        return buildSelection();
    }

    IWidget buildSelection() {
        if (selectionRoot != null) return selectionRoot;
        prepare();
        ListWidget<IWidget, ?> list = list("selection");
        list.child(
            label(
                () -> ui.session.selectedCell() == null ? "点击预览选择一格，或从结构列表选择整组"
                    : "所选 " + ui.preview.selection()
                        .size() + " 格 · Ctrl 多选 / T 同种"));
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
        sizingSections.add(grade);
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
        sizingSections.add(facing);
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
                () -> authorOverview() ? "删除所选格" : "恢复默认壳",
                () -> ui.session.editable() && ui.session.selectedCell() != null && !ui.session.selectedCell().anchor,
                () -> authorOverview() ? "删除当前作者选区，沿用原结构完整性和锚点校验。" : "按来源声明恢复默认目标。").expanded());
        materialTarget.child(
            action(
                61,
                () -> "背包选材",
                () -> ui.session.editable() && ui.session.state().data.getInteger("mode") != 2,
                () -> "选择真实物品样本，再指定当前一格；不消耗物品。").expanded());
        target.child(materialTarget);
        target.child(action(94, () -> "仓室预设", this::canHatchPreset, this::hatchPresetReason));
        Flow targetGroup = group(
            "target",
            "目标与仓室",
            () -> targetExpanded,
            () -> targetExpanded = !targetExpanded,
            target);
        targetGroup.setEnabledIf(w -> ui.session.selectedCell() != null);
        sizingSections.add(targetGroup);
        list.child(targetGroup);
        selectionRoot = pane(list);
        return selectionRoot;
    }

    IWidget buildStructure() {
        if (structureRoot != null) return structureRoot;
        prepare();
        ListWidget<IWidget, ?> list = list("structure");
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
                    () -> ui.session.editable() && (mode != 2 || !ui.session.state().data.getBoolean("virtual")),
                    () -> mode == 2 && ui.session.state().data.getBoolean("virtual") ? "虚拟预设不支持拆除；请先建立实体控制器。"
                        : ui.session.editable() ? "只修改规划模式；施工须另行核对材料。" : ui.session.editingReason()).expanded());
        }
        source.child(modes);
        Flow structure = section();
        structure.setEnabledIf(
            w -> !ui.config.selectedStructureId()
                .isEmpty());
        sizingSections.add(structure);
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
        sizingSections.add(back);
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
        list.child(group("source", "来源与信道", () -> sourceExpanded, () -> sourceExpanded = !sourceExpanded, source));
        list.child(action(94, () -> "仓室预设", this::canHatchPreset, this::hatchPresetReason));
        structureRoot = pane(list);
        return structureRoot;
    }

    IWidget buildMaterials() {
        if (materialsRoot != null) return materialsRoot;
        prepare();
        ListWidget<IWidget, ?> list = list("materials");
        Flow materials = section();
        materials.child(action(78, () -> "材料来源", () -> ui.session.editable(), () -> "主背包、容器、ME 终端开关与优先次序。"));
        materials.child(action(94, () -> "仓室预设", this::canHatchPreset, this::hatchPresetReason));
        materials.child(
            action(
                134,
                () -> ui.session.state().data.getBoolean("structureBuilt") ? "校准为当前现场" : "重置为默认规划",
                () -> ui.session.editable() && ui.preview.previewInteractive()
                    && ui.session.state().data.getBoolean("target")
                    && ui.session.state().data.getBoolean("supported"),
                () -> "沿用原重置 / 校准确认；不施工、不删除已保存预设。"));
        materials.child(label(() -> ui.session.state().data.getString("status")));

        list.child(
            group("material", "材料与重置", () -> materialExpanded, () -> materialExpanded = !materialExpanded, materials));
        materialsRoot = pane(list);
        return materialsRoot;
    }

    /** Only opens the original material review; its independent dialog owns final confirmation ID 55. */
    IWidget buildConstruction() {
        if (constructionRoot != null) return constructionRoot;
        prepare();
        ListWidget<IWidget, ?> list = list("construction");
        list.child(
            HologramEditorNativeTheme.label(() -> ui.review.constructionScopeLabel(false))
                .widthRel(1f)
                .height(32));
        list.child(
            HologramEditorNativeTheme.label(() -> ui.review.constructionScopeHint())
                .widthRel(1f)
                .height(28));
        list.child(
            action(50, this::constructionActionLabel, this::canReviewConstruction, this::constructionReason)
                .height(26));
        Flow task = row().height(24);
        task.child(
            action(
                51,
                () -> ui.session.state().data.getInteger("job") == 2 ? "继续" : "暂停",
                () -> ui.session.pendingSequence() < 0 && (ui.session.state().data.getInteger("job") == 1
                    || ui.session.state().data.getInteger("job") == 2),
                () -> ui.session.pendingSequence() >= 0 ? "等待服务端回执后可操作。" : "暂停或继续当前施工任务，保留已完成结果。").height(24)
                    .expanded());
        task.child(
            action(
                52,
                () -> "取消",
                () -> ui.session.pendingSequence() < 0
                    && (ui.session.state().data.getInteger("job") == 1 || ui.session.state().data.getInteger("job") == 2
                        || ui.session.state().data.getBoolean("capturePending")),
                () -> ui.session.pendingSequence() >= 0 ? "等待服务端回执后可操作。" : "取消当前任务；已施工内容仍保留。").height(24)
                    .expanded());
        list.child(task);
        list.child(
            action(
                78,
                () -> "材料来源",
                () -> ui.session.editable(),
                () -> ui.session.editable() ? "主背包、容器、ME 终端开关与优先次序。" : ui.session.editingReason()));
        list.child(
            HologramEditorNativeTheme.label(() -> ui.review.completionSummary())
                .widthRel(1f)
                .height(64));
        constructionRoot = pane(list);
        return constructionRoot;
    }

    private String constructionActionLabel() {
        NBTTagCompound state = ui.session.state().data;
        return ui.review.placementReviewActive() ? "继续放置预检"
            : state.getBoolean("virtual") && !state.getBoolean("virtualPlacement") ? "从蓝图库放置"
                : state.getBoolean("virtual") && !state.getBoolean("virtualLocked") ? "世界右键确定位置"
                    : new String[] { "确认构建", "确认替换", "确认拆除" }[Math.floorMod(state.getInteger("mode"), 3)];
    }

    private boolean canReviewConstruction() {
        NBTTagCompound state = ui.session.state().data;
        return ui.session.editable()
            && (!state.getBoolean("virtual")
                || state.getBoolean("virtualPlacement") && state.getBoolean("virtualLocked"))
            && state.getBoolean("target")
            && (state.getBoolean("supported") || state.getInteger("mode") == 0);
    }

    private String constructionReason() {
        NBTTagCompound state = ui.session.state().data;
        if (!ui.session.editable()) return ui.session.editingReason();
        if (state.getBoolean("virtual") && !state.getBoolean("virtualPlacement")) return "从蓝图库选择蓝图进入世界放置。";
        if (state.getBoolean("virtual") && !state.getBoolean("virtualLocked")) return "先在世界右键确定放置位置。";
        if (!state.getBoolean("target")) return "尚无施工目标。";
        if (!state.getBoolean("supported") && state.getInteger("mode") != 0) return "当前结构不支持此施工模式。";
        return "核对真实材料与改动；在原材料确认窗口最终确认后才施工。";
    }

    private boolean canHatchPreset() {
        return ui.session.editable() && !ui.session.state().data.getBoolean("virtual")
            && ui.session.state().data.getBoolean("supported")
            && ui.session.state().data.getInteger("mode") != 2;
    }

    private String hatchPresetReason() {
        if (!ui.session.editable()) return ui.session.editingReason();
        if (ui.session.state().data.getBoolean("virtual")) return "仓室预设只用于完整实体结构；虚拟蓝图请逐格选择设备或编辑来源声明。";
        if (ui.session.state().data.getInteger("mode") == 2) return "拆除模式不能应用仓室预设。";
        if (!ui.session.state().data.getBoolean("supported")) return "结构尚未完整采集，请完成采集后使用仓室预设。";
        return "基础声明配额或本机精确搭配；无需选中格，应用仍按当前结构匹配设备、等级与数量。";
    }

    private void prepare() {
        if (prepared) return;
        prepared = true;
        dialogs.prepare();
    }

    private ListWidget<IWidget, ?> list(String name) {
        return new ListWidget<>().name("editor_native_" + name + "_list")
            .sizeRel(1f);
    }

    private Flow pane(IWidget list) {
        Flow pane = Flow.col()
            .sizeRel(1f)
            .padding(6)
            .childPadding(4)
            .child(list);
        roots.add(pane);
        return pane;
    }

    private void resizePanes() {
        for (Flow pane : roots) pane.scheduleResize();
    }

    void refresh() {
        boolean selected = ui.session.selectedCell() != null;
        if (selected != selectedContext) {
            selectedContext = selected;
            targetExpanded = selected;
        }
        dialogs.refresh();
        int visibility = 0;
        for (int i = 0; i < sizingSections.size(); i++) if (sizingSections.get(i)
            .isEnabled()) visibility |= 1 << i;
        if (visibility != sizingVisibility) {
            sizingVisibility = visibility;
            // MUI2 enabled changes reposition children without recalculating nested coverChildrenHeight bounds.
            resizePanes();
        }
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
        resizePanes();
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
        sizingSections.add(content);
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

    private boolean authorOverview() {
        return ui.session.state().data.getBoolean("virtual") && !ui.session.state().data.getBoolean("virtualPlacement")
            && ui.session.state().data.getString("activeStructure")
                .isEmpty();
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
