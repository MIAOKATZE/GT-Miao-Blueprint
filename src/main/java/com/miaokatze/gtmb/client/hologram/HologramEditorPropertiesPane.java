// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.miaokatze.gtmb.hologram.HologramGtMeOutputDisplay;

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
                () -> ui.session.selectedCell() == null
                    ? projectorOr("native.ui.click_a_preview_cell_or_select_a", "点击预览选择一格，或从结构列表选择整组")
                    : (projectorOr("native.ui.selected", "所选") + " ") + ui.preview.selection()
                        .size()
                        + (" " + projectorOr("native.ui.cells_ctrl_multi_select_t_same_type", "格 · Ctrl 多选 / T 同种"))));
        Flow target = section();
        target.child(label(() -> {
            HologramState.Cell cell = ui.session.selectedCell();
            return cell == null ? ""
                : (projectorOr("native.ui.position", "位置") + " ") + cell.dx
                    + ","
                    + cell.dy
                    + ","
                    + cell.dz
                    + (" " + projectorOr("native.ui.current", "· 当前：") + " ")
                    + (cell.localObservationMissing() ? projectorOr("native.ui.world_state_not_read", "未读取到现场")
                        : ui.preview.cellName(cell, true));
        }));
        target.child(label(() -> {
            HologramState.Cell cell = ui.session.selectedCell();
            return cell == null ? projectorOr("native.ui.target_none_selected", "目标：未选中")
                : (projectorOr("native.ui.target", "目标：") + " ") + ui.preview.cellName(cell, false);
        }));
        target.child(HologramEditorNativeTheme.label(() -> {
            HologramState.Cell cell = ui.session.selectedCell();
            if (cell == null || cell.localObservationMissing()) return "";
            int tier = cell.displayInteger(true, "siteHatchTier", -1);
            return (projectorOr("native.ui.installed_hatch", "现场仓室 ·") + " ")
                + (tier >= 0 && tier < gregtech.api.enums.GTValues.VN.length ? gregtech.api.enums.GTValues.VN[tier]
                    : projectorOr("native.ui.unknown_tier", "等级未知"))
                + (" " + projectorOr("native.ui.facing", "· 朝") + " ")
                + ui.config.directionName(cell.actualFacing);
        })
            .widthRel(1f)
            .height(20)
            .setEnabledIf(w -> {
                HologramState.Cell cell = ui.session.selectedCell();
                return ui.host.parent()
                    .localControllerStandard() && cell != null
                    && !cell.localObservationMissing()
                    && cell.displayInteger(true, "siteHatchTier", -1) >= 0;
            }));
        target.child(meOutputCapacity(true));
        target.child(meOutputCapacity(false));
        Flow grade = row();
        grade.setEnabledIf(w -> ui.config.primaryCapability() != null);
        sizingSections.add(grade);
        grade.child(
            action(
                80,
                () -> "−",
                () -> canStep(-1),
                () -> projectorOr("native.ui.continuously_decrease_the_tier_declared_by_this", "连续降低本结构声明的等级。"))
                    .width(24));
        grade.child(action(81, () -> {
            NBTTagCompound cap = ui.config.primaryCapability();
            return cap == null ? projectorOr("native.ui.no_independent_tier", "无独立等级") : ui.config.optionLabel(cap);
        },
            () -> ui.session.configurationEditable() && ui.config.primaryCapability() != null,
            () -> projectorOr("native.ui.select_from_this_capability_s_actual_options", "从此能力的真实选项中选择，或填写原始正整数。"))
                .expanded());
        grade.child(
            action(
                82,
                () -> "+",
                () -> canStep(1),
                () -> projectorOr("native.ui.continuously_increase_the_tier_declared_by_this", "连续提高本结构声明的等级。"))
                    .width(24));
        target.child(grade);
        target.child(
            action(
                95,
                () -> projectorOr("native.ui.type_tier_replacement_target", "类型 / 等级 / 可替换目标"),
                () -> ui.session.selectedNativeEditable(),
                () -> ui.config.candidateNote()));
        Flow facing = row();
        facing.setEnabledIf(
            w -> ui.session.state().data.getBoolean("localStandardController") || ui.session.hatchFacings().length > 0);
        sizingSections.add(facing);
        facing.child(action(99, () -> {
            if (ui.session.state().data.getBoolean("localStandardController")) return ui.config.nativeFacingLabel();
            int[] legal = ui.session.hatchFacings();
            return legal.length == 0 ? projectorOr("native.ui.hatch_facing_unavailable", "仓室朝向不可用")
                : (projectorOr("native.ui.hatch_facing", "仓室朝向：") + " ") + ui.config.directionName(
                    ui.session.cellTag()
                        .getInteger("hatchFacing"));
        },
            () -> !ui.session.state().data.getBoolean("localStandardController") && ui.session.selectedNativeEditable()
                && ui.session.hatchFacings().length > 0,
            () -> projectorOr("native.ui.cycles_only_facings_accepted_by_both_this", "只轮换当前设备与声明共同允许的朝向；仅修改当前格。"))
                .expanded());
        facing.child(
            action(
                102,
                () -> projectorOr("native.ui.automatic", "自动"),
                () -> !ui.session.state().data.getBoolean("localStandardController")
                    && ui.session.selectedNativeEditable()
                    && ui.session.cellTag()
                        .getBoolean("hasFacingPin"),
                () -> projectorOr("native.ui.clear_the_explicit_facing_and_restore_automatic", "清除显式朝向，恢复自动朝向。"))
                    .width(44));
        target.child(facing);
        Flow edit = row();
        edit.child(
            action(
                112,
                () -> projectorOr("native.ui.copy", "复制"),
                () -> ui.session.editable() && ui.session.selectedCell() != null,
                () -> projectorOr("native.ui.copy_selected_targets_using_the_current_mode", "复制所选目标；沿用当前模式的剪贴板语义。"))
                    .expanded());
        edit.child(
            action(
                113,
                () -> projectorOr("native.ui.rotate", "转向"),
                () -> !ui.session.state().data.getBoolean("localStandardController") && ui.session.editable()
                    && ui.session.selectedCell() != null,
                () -> projectorOr("native.ui.rotate_a_block_state_or_use_a", "普通方块状态转向或设备合法朝向。")).expanded());
        target.child(edit);
        Flow materialTarget = row();
        materialTarget.child(
            action(
                66,
                () -> authorOverview() ? projectorOr("native.ui.delete_selected_cells", "删除所选格")
                    : projectorOr("native.ui.restore_default_casing", "恢复默认壳"),
                () -> ui.session.editable() && ui.session.selectedCell() != null && !ui.session.selectedCell().anchor,
                () -> authorOverview()
                    ? projectorOr("native.ui.delete_the_author_selection_using_the_existing", "删除当前作者选区，沿用原结构完整性和锚点校验。")
                    : projectorOr("native.ui.restore_the_source_declaration_s_default_target", "按来源声明恢复默认目标。"))
                        .expanded());
        materialTarget.child(
            action(
                61,
                () -> projectorOr("native.ui.inventory_materials", "背包选材"),
                () -> ui.session.editable() && ui.config.targetEditingMode(),
                () -> projectorOr("native.ui.select_a_real_item_sample_to_replace", "选择真实物品样本，替换全部所选格；不消耗物品。"))
                    .expanded());
        target.child(materialTarget);
        target.child(
            action(
                94,
                () -> projectorOr("native.ui.hatch_presets", "仓室预设"),
                this::canHatchPreset,
                this::hatchPresetReason));
        Flow targetGroup = group(
            "target",
            projectorOr("native.ui.targets_and_hatches", "目标与仓室"),
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
        source.child(
            label(
                () -> (projectorOr("native.ui.source", "来源：") + " ")
                    + sourceLabel(ui, ui.config.selectedStructureId())));
        Flow observed = row();
        observed.setEnabledIf(
            w -> ui.host.parent()
                .localControllerStandard());
        observed.child(
            label(
                () -> ui.host.parent()
                    .localMachineState()
                    .caption()));
        sizingSections.add(observed);
        source.child(observed);
        source.child(
            HologramEditorNativeTheme.label(
                () -> ui.host.parent()
                    .nativeStatusCaption())
                .widthRel(1f)
                .height(24)
                .setEnabledIf(
                    w -> ui.host.parent()
                        .localControllerStandard()));
        source.child(
            label(
                () -> ui.session.selectedCell() != null && ui.session.selectedCell().sourceMissing
                    ? (projectorOr("native.ui.missing_source", "来源缺失：") + " ") + ui.session.selectedCell().sourceWarning
                    : (projectorOr("native.ui.scope", "范围：") + " ") + (ui.preview.selection()
                        .size() > 0 && !ui.session.state().data.getBoolean("wholeBlueprintPlacement")
                        && !ui.session.state().data.getBoolean("virtualPlacement")
                            ? projectorOr("native.ui.current_selection", "当前选区")
                            : projectorOr("native.ui.complete_current_plan", "当前完整规划"))));
        Flow modes = row();
        boolean nativeController = ui.session.state().data.getBoolean("localStandardController");
        var actions = nativeController
            ? new com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation[] {
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.BUILD,
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.REPLACE,
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.DEMOLISH }
            : new com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation[] {
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.BUILD };
        for (var action : actions) {
            modes.child(
                action(
                    nativeController ? 1720 + action.uiIndex() : 70,
                    () -> (ui.session.state().data.getInteger(nativeController ? "nativeAction" : "mode")
                        == action.uiIndex() ? "✓ " : "") + HologramNativeControllerState.label(action),
                    () -> nativeController ? ui.host.parent()
                        .nativeActionBlocker(action)
                        .isEmpty() : ui.session.configurationEditable(),
                    () -> nativeController ? ui.host.parent()
                        .nativeActionBlocker(action)
                        : ui.session.configurationEditable()
                            ? projectorOr("native.ui.blueprints_build_only", "蓝图仅支持建造；施工须另行核对材料。")
                            : ui.session.editingReason()).expanded());
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
                () -> ui.session.selectedCell() != null && ui.session.selectedCell().sourceMissing
                    ? projectorOr("native.ui.missing_source_201", "来源缺失")
                    : projectorOr("native.ui.edit_source_structure", "进入来源结构编辑"),
                () -> ui.session.editable() && !ui.config.selectedStructureId()
                    .isEmpty(),
                () -> projectorOr("native.ui.edit_this_source_member_s_main_value", "分别编辑此来源成员的主值与信道。")));
        structure.child(
            action(
                164,
                () -> (projectorOr("native.ui.include_in_construction", "参与施工：") + " ")
                    + (ui.config.structureEnabled(ui.config.selectedStructureId()) ? projectorOr("native.ui.yes", "是")
                        : projectorOr("native.ui.no", "否")),
                () -> ui.session.editable() && !ui.config.selectedStructureId()
                    .isEmpty(),
                () -> projectorOr("native.ui.change_whether_this_source_member_participates_in", "改变来源成员参与施工的状态。")));
        source.child(structure);
        Flow back = section();
        back.setEnabledIf(
            w -> !ui.session.state().data.getString("activeStructure")
                .isEmpty());
        sizingSections.add(back);
        back.child(
            action(
                165,
                () -> projectorOr("native.ui.back_to_area_overview", "返回区域总览"),
                () -> ui.session.editable() && !ui.session.state().data.getString("activeStructure")
                    .isEmpty(),
                () -> projectorOr("native.ui.keep_accepted_member_settings_and_return", "保留已接受的成员配置并返回。")));
        source.child(back);
        source.child(
            action(
                60,
                () -> ui.host.parent()
                    .localControllerStandard() ? projectorOr("native.ui.world_planned_channels", "现场 / 规划信道")
                        : projectorOr("native.ui.structure_tier_channels", "结构等级 / 信道"),
                () -> !ui.session.state().data.getBoolean("virtual")
                    || !ui.session.state().data.getString("activeStructure")
                        .isEmpty(),
                () -> projectorOr(
                    "native.ui.main_value_dynamic_capabilities_named_channels_and",
                    "当前来源结构的主值、动态能力、具名信道及继承值。")));
        list.child(
            group(
                "source",
                projectorOr("native.ui.source_and_channels", "来源与信道"),
                () -> sourceExpanded,
                () -> sourceExpanded = !sourceExpanded,
                source));
        list.child(
            action(
                94,
                () -> projectorOr("native.ui.hatch_presets", "仓室预设"),
                this::canHatchPreset,
                this::hatchPresetReason));
        structureRoot = pane(list);
        return structureRoot;
    }

    private IWidget meOutputCapacity(boolean current) {
        return HologramEditorNativeTheme
            .label(() -> meOutputCapacityText(ui.session.state(), ui.session.selectedCell(), current))
            .widthRel(1f)
            .height(24)
            .setEnabledIf(w -> hasMeOutputCapacity(ui.session.state(), ui.session.selectedCell(), current));
    }

    private static boolean hasMeOutputCapacity(HologramState view, HologramState.Cell cell, boolean current) {
        return cell != null
            && (cell.hasDisplayTag(current, HologramGtMeOutputDisplay.ITEMS, 4)
                || cell.hasDisplayTag(current, HologramGtMeOutputDisplay.MILLIBUCKETS, 4))
            && (current || HologramBlueprintPreview.editorAllowsMeOutputCapacity(view.data));
    }

    static String meOutputCapacityText(HologramState view, HologramState.Cell cell, boolean current) {
        if (!hasMeOutputCapacity(view, cell, current)) return "";
        NBTTagCompound display = cell.display(current);
        long items = HologramGtMeOutputDisplay.read(display, false);
        long fluid = HologramGtMeOutputDisplay.read(display, true);
        if ((items == 0) == (fluid == 0)) return "";
        long capacity = items != 0 ? items : fluid;
        String value = capacity == Long.MAX_VALUE ? "∞" : String.format(Locale.ROOT, "%,d", capacity);
        String key = current ? "current" : "target";
        String type = items != 0 ? "items" : "fluid";
        String fallback = (current ? "Current" : "Target") + " base buffer: %1$s " + (items != 0 ? "items" : "mB");
        String template = HologramUiText.localized("gtmb.hologram.meOutput." + key + "." + type, fallback);
        // Only the known token is interpolated; an invalid external language override cannot break the panel.
        return (template.contains("%1$s") ? template : fallback).replace("%1$s", value);
    }

    IWidget buildMaterials() {
        if (materialsRoot != null) return materialsRoot;
        prepare();
        ListWidget<IWidget, ?> list = list("materials");
        Flow materials = section();
        materials.child(
            action(
                78,
                () -> projectorOr("native.ui.material_sources", "材料来源"),
                () -> ui.session.configurationEditable(),
                () -> projectorOr(
                    "native.ui.main_inventory_containers_and_me_terminal_switches",
                    "主背包、容器、ME 终端开关与优先次序。")));
        materials.child(
            action(
                94,
                () -> projectorOr("native.ui.hatch_presets", "仓室预设"),
                this::canHatchPreset,
                this::hatchPresetReason));
        materials.child(
            action(
                134,
                () -> ui.session.state().data.getBoolean("structureBuilt")
                    ? projectorOr("native.ui.match_current_world", "校准为当前现场")
                    : projectorOr("native.ui.reset_to_default_plan", "重置为默认规划"),
                () -> ui.session.editable() && ui.preview.previewInteractive()
                    && ui.session.state().data.getBoolean("target")
                    && ui.session.state().data.getBoolean("supported"),
                () -> projectorOr(
                    "native.ui.uses_the_existing_reset_confirmation_does_not",
                    "沿用原重置 / 校准确认；不施工、不删除已保存预设。")));
        materials.child(
            label(
                () -> ui.session.state().data.getBoolean("localStandardController") ? ui.host.parent()
                    .nativeStatusCaption() : ui.session.state().data.getString("status")));

        list.child(
            group(
                "material",
                projectorOr("native.ui.materials_and_reset", "材料与重置"),
                () -> materialExpanded,
                () -> materialExpanded = !materialExpanded,
                materials));
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
                .height(HologramEditorNativeTheme.PRIMARY_BUTTON_HEIGHT));
        Flow task = row().height(18);
        task.child(
            action(
                52,
                () -> projectorOr("native.ui.cancel", "取消"),
                () -> ui.session.pendingSequence() < 0
                    && (ui.session.state().data.getInteger("job") == 1 || ui.session.state().data.getInteger("job") == 2
                        || ui.session.state().data.getBoolean("capturePending")),
                () -> ui.session.pendingSequence() >= 0
                    ? projectorOr("native.ui.wait_for_the_server_response_before_continuing", "等待服务端回执后可操作。")
                    : projectorOr("native.ui.cancel_the_task_completed_construction_is_kept", "取消当前任务；已施工内容仍保留。"))
                        .height(18)
                        .expanded());
        list.child(task);
        list.child(
            action(
                78,
                () -> projectorOr("native.ui.material_sources", "材料来源"),
                () -> ui.session.configurationEditable(),
                () -> ui.session.configurationEditable()
                    ? projectorOr(
                        "native.ui.main_inventory_containers_and_me_terminal_switches",
                        "主背包、容器、ME 终端开关与优先次序。")
                    : ui.session.editingReason()));
        list.child(
            HologramEditorNativeTheme.label(() -> ui.review.completionSummary())
                .widthRel(1f)
                .height(64));
        constructionRoot = pane(list);
        return constructionRoot;
    }

    private String constructionActionLabel() {
        NBTTagCompound state = ui.session.state().data;
        if (ui.session.state().nativeViewport != null)
            return projectorOr("native.ui.view_blueprint_materials", "查看蓝图材料");
        if (state.getBoolean("localStandardController")) return HologramNativeControllerState.reviewLabel(
            ui.host.parent()
                .nativeControllerState()
                .action());
        if (finishedWholeNativePlacement()) return HologramUiText.projectorOr("stock.view_materials", "查看材料");
        return ui.review.placementReviewActive() ? projectorOr("native.ui.continue_placement_review", "继续放置预检")
            : state.getBoolean("virtual") && !state.getBoolean("virtualPlacement")
                ? projectorOr("native.ui.place_from_library", "从蓝图库放置")
                : state.getBoolean("virtual") && !state.getBoolean("virtualLocked")
                    ? projectorOr("native.ui.right_click_in_the_world_to_position", "世界右键确定位置")
                    : new String[] { projectorOr("native.ui.confirm_build", "确认构建"),
                        projectorOr("native.ui.confirm_replacement", "确认替换"),
                        projectorOr("native.ui.confirm_demolition", "确认拆除") }[Math
                            .floorMod(state.getInteger("mode"), 3)];
    }

    private boolean finishedWholeNativePlacement() {
        if (!ui.session.state().data.getBoolean("nativeBuildSatisfied")) return false;
        HologramScreen root = ui.host == null ? null : ui.host.parent();
        return root != null && HologramPlacementConfirmation
            .finishedWholeNativePlacement(ui.session.state(), ui.config.configuration(), root.hasLocalNativeChoices());
    }

    private boolean canReviewConstruction() {
        if (ui.session.state().data.getBoolean("localStandardController")) return ui.host.parent()
            .nativeActionBlocker(
                ui.host.parent()
                    .nativeControllerState()
                    .action())
            .isEmpty();
        if (ui.review.nativeMaterialInspectionReady()) return true;
        NBTTagCompound state = ui.session.state().data;
        return ui.session.editable()
            && (!state.getBoolean("virtual")
                || state.getBoolean("virtualPlacement") && state.getBoolean("virtualLocked"))
            && state.getBoolean("target")
            && HologramPlacementConfirmation.localStructureReviewBlocker(ui.session.state())
                .isEmpty()
            && (state.getBoolean("supported") || state.getInteger("mode") == 0);
    }

    private String constructionReason() {
        if (ui.session.state().data.getBoolean("localStandardController")) return ui.host.parent()
            .nativeActionBlocker(
                ui.host.parent()
                    .nativeControllerState()
                    .action());
        if (ui.session.state().nativeViewport != null)
            return projectorOr("native.ui.view_materials_for_the_current_blueprint_version", "按当前蓝图版本查看材料，不提交施工。");
        if (finishedWholeNativePlacement()) return "";
        if (ui.review.nativeMaterialInspectionReady())
            return ui.session.nativeConstructionEditable(ui.config.configuration()) ? ""
                : projectorOr("native.ui.you_can_view_materials_first", "可先查看材料。");
        NBTTagCompound state = ui.session.state().data;
        if (!ui.session.editable()) return ui.session.editingReason();
        if (state.getBoolean("virtual") && !state.getBoolean("virtualPlacement"))
            return projectorOr("native.ui.choose_a_blueprint_in_the_library_to", "从蓝图库选择蓝图进入世界放置。");
        if (state.getBoolean("virtual") && !state.getBoolean("virtualLocked"))
            return projectorOr("native.ui.right_click_in_the_world_to_fix", "先在世界右键确定放置位置。");
        if (!state.getBoolean("target")) return projectorOr("native.ui.no_construction_target", "尚无施工目标。");
        String localBlocker = HologramPlacementConfirmation.localStructureReviewBlocker(ui.session.state());
        if (!localBlocker.isEmpty()) return localBlocker;
        if (!state.getBoolean("supported") && state.getInteger("mode") != 0)
            return projectorOr("native.ui.this_structure_does_not_support_this_construction", "当前结构不支持此施工模式。");
        return "";
    }

    private boolean canHatchPreset() {
        if (ui.session.state().data.getBoolean("localStandardController")) return ui.session.configurationEditable();
        return ui.session.configurationEditable() && !ui.session.state().data.getBoolean("virtual")
            && ui.session.state().data.getBoolean("supported")
            && ui.config.targetEditingMode();
    }

    private String hatchPresetReason() {
        if (ui.session.state().data.getBoolean("localStandardController"))
            return ui.session.configurationEditable() ? "" : ui.session.editingReason();
        if (!ui.session.editable()) return ui.session.editingReason();
        if (ui.session.state().data.getBoolean("virtual")) return projectorOr(
            "native.ui.hatch_presets_apply_to_complete_real_structures",
            "仓室预设只用于完整实体结构；虚拟蓝图请逐格选择设备或编辑来源声明。");
        if (!ui.config.targetEditingMode())
            return projectorOr("native.ui.hatch_presets_are_unavailable_in_demolition_mode", "拆除模式不能应用仓室预设。");
        if (!ui.session.state().data.getBoolean("supported"))
            return projectorOr("native.ui.wait_for_complete_structure_capture_before_using", "结构尚未完整采集，请完成采集后使用仓室预设。");
        return "";
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
            .childPadding(3)
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
            .height(20);
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
            .childPadding(3)
            .collapseDisabledChild(true);
    }

    private boolean canStep(int delta) {
        NBTTagCompound cap = ui.config.primaryCapability();
        return ui.session.configurationEditable() && cap != null && ui.config.canStep(cap, delta);
    }

    private boolean authorOverview() {
        return ui.session.state().data.getBoolean("virtual") && !ui.session.state().data.getBoolean("virtualPlacement")
            && ui.session.state().data.getString("activeStructure")
                .isEmpty();
    }

    ButtonWidget<?> action(int id, Supplier<String> label, BooleanSupplier enabled, Supplier<String> tooltip) {
        return ui.shell.nativeAction(id, label, enabled, tooltip)
            .widthRel(1f)
            .height(18);
    }

    static IWidget label(Supplier<String> text) {
        return HologramEditorNativeTheme.label(text)
            .widthRel(1f)
            .height(24);
    }

    static String sourceLabel(HologramUiModules ui, String id) {
        if (id.isEmpty())
            return ui.session.state().data.getBoolean("virtual") ? projectorOr("native.ui.area_overview", "区域总览")
                : ui.session.state().data.getString("title");
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
            .height(18)
            .childPadding(3)
            .crossAxisAlignment(Alignment.CrossAxis.START);
    }
}
