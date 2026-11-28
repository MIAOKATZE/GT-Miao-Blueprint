// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widget.sizer.Unit;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** Cached native configuration windows. Rows resolve the live snapshot when read and clicked. */
final class HologramEditorConfigurationDialogs {

    private final HologramUiModules ui;
    private final List<Window> windows = new ArrayList<>();
    private boolean initialized;

    HologramEditorConfigurationDialogs(HologramUiModules ui) {
        this.ui = ui;
    }

    void prepare() {
        if (!initialized) {
            initialized = true;
            window("channels", () -> ui.config.drawer(), this::channels);
            window(
                "options",
                () -> !ui.config.optionChannel()
                    .isEmpty(),
                this::options);
            window("candidates", () -> ui.config.hatchDrawer(), this::candidates);
            window("roles", () -> ui.config.roleDropdown(), this::roles);
            window("hatch_presets", () -> ui.config.presetDrawer(), this::presets);
            window("sources", () -> ui.config.sourceDrawer(), this::sources);
            window("inventory", () -> ui.preview.inventory(), this::inventory);
            window("reset", () -> ui.config.resetReview(), this::reset);
        }
    }

    void refresh() {
        prepare();
        ui.config.normalizeNativeViewState();
        // Run from the shell's update, outside input dispatch and panel-manager safe scopes.
        for (Window window : windows) {
            if (window.visible.getAsBoolean()) {
                if (!window.handler.isPanelOpen()) window.handler.openPanel();
            } else if (window.handler.isPanelOpen()) window.handler.closePanel();
        }
    }

    private void window(String name, BooleanSupplier visible, Supplier<ModularPanel> builder) {
        windows
            .add(new Window(visible, IPanelHandler.simple(ui.shell.host(), (parent, player) -> builder.get(), true)));
    }

    private ModularPanel dialog(String name, Supplier<String> title, Flow content, Runnable close, int height) {
        Dialog<?> panel = new Dialog<Object>(ui.panelName("gtmb_editor_configuration_" + name)) {

            @Override
            public boolean onKeyPressed(char character, int key) {
                if ("inventory".equals(name)
                    && (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E && !ui.shell.fieldFocused())
                    && (ui.preview.inventoryDragging() || ui.preview.inventorySlot() >= 0
                        || ui.preview.sourceChoice() >= 0)) {
                    if (getContext().hasDraggable()) getContext().dropDraggable(true);
                    ui.preview.inventoryDragging(false);
                    ui.preview.inventorySlot(ui.preview.sourceChoice(-1));
                    return true;
                }
                return super.onKeyPressed(character, key);
            }
        };
        // Inventory is a sample palette beside the scene: uncovered preview pixels remain selectable.
        if ("inventory".equals(name)) panel.setDisablePanelsBelow(false)
            .setCloseOnOutOfBoundsClick(false);
        panel.width(() -> Math.min(346, Math.max(160, ui.host.screenWidth() - 24)), Unit.Measure.PIXEL)
            .height(() -> dialogHeight(height), Unit.Measure.PIXEL)
            .background(HologramEditorNativeTheme.PANEL)
            .onCloseAction(() -> {
                if ("inventory".equals(name) && panel.getContext()
                    .hasDraggable())
                    panel.getContext()
                        .dropDraggable(true);
                close.run();
            });
        if (ui.session.isTutorial())
            panel.top(
                () -> Math.max(
                    6,
                    (ui.host.screenHeight() - HologramEditorTutorialOverlay.RESERVED_HEIGHT - dialogHeight(height))
                        / 2d),
                Unit.Measure.PIXEL);
        Flow heading = row();
        heading.child(
            HologramEditorNativeTheme.label(title, HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .expanded()
                .height(20));
        ButtonWidget<?> closeButton = local(() -> "×", () -> {
            if ("inventory".equals(name) && panel.getContext()
                .hasDraggable())
                panel.getContext()
                    .dropDraggable(true);
            close.run();
            panel.closeIfOpen();
        },
            () -> true,
            () -> projectorOr("native.ui.close_the_window_accepted_planning_changes_are", "关闭窗口；已接受的规划修改保留。"))
                .width(24);
        heading.child(closeButton);
        ui.shell.registerTarget("modal", panel);
        ui.shell.registerTarget("modal.close", closeButton);
        ui.shell.registerTarget("config." + name, panel);
        ui.shell.registerTarget("config." + name + ".close", closeButton);
        // The entire form can scroll on short screens; its inner bounded row list keeps its normal allocation.
        ListWidget<IWidget, ?> viewport = list().name("config." + name + ".viewport");
        viewport.child(
            content.widthRel(1f)
                .height(height - 48));
        panel.child(
            Flow.col()
                .sizeRel(1f)
                .padding(10)
                .childPadding(6)
                .child(heading)
                .child(viewport));
        return panel;
    }

    private int dialogHeight(int preferred) {
        int reserve = ui.session.isTutorial() ? HologramEditorTutorialOverlay.RESERVED_HEIGHT : 0;
        // Tutorial subtitles and navigation own the footer. The existing form viewport scrolls inside the remainder.
        return Math.min(preferred, Math.max(ui.session.isTutorial() ? 56 : 160, ui.host.screenHeight() - reserve - 24));
    }

    private Flow column() {
        return Flow.col()
            .widthRel(1f)
            .heightRel(1f)
            .childPadding(5);
    }

    private Flow row() {
        return HologramEditorPropertiesPane.row()
            .height(22);
    }

    private ListWidget<IWidget, ?> list() {
        return new ListWidget<>().sizeRel(1f)
            .expanded();
    }

    private IWidget visibleRow(IWidget widget, int height, BooleanSupplier visible) {
        return Flow.col()
            .widthRel(1f)
            .height(height)
            .setEnabledIf(w -> visible.getAsBoolean())
            .child(widget);
    }

    private IWidget note(Supplier<String> text) {
        return HologramEditorNativeTheme.label(text)
            .widthRel(1f)
            .height(28);
    }

    private ButtonWidget<?> action(int id, Supplier<String> label, BooleanSupplier enabled, Supplier<String> tooltip) {
        return ui.shell.nativeAction(id, label, enabled, tooltip)
            .widthRel(1f)
            .height(22);
    }

    private ButtonWidget<?> dynamicAction(String target, IntSupplier id, Supplier<String> label,
        BooleanSupplier enabled, Supplier<String> tooltip) {
        ButtonWidget<?> widget = local(label, () -> ui.router.activate(id.getAsInt()), enabled, tooltip);
        ui.shell.registerTarget(target, widget);
        return widget;
    }

    private ButtonWidget<?> local(Supplier<String> label, Runnable pressed, BooleanSupplier enabled,
        Supplier<String> tooltip) {
        return HologramEditorNativeTheme.button(label, null, pressed, enabled, () -> false, false)
            .tooltipBuilder(t -> t.addLine(tooltip.get()))
            .tooltipAutoUpdate(true)
            .widthRel(1f)
            .height(22);
    }

    private TextFieldWidget field(HologramMuiTextField adapter) {
        return ui.shell.adoptField(adapter)
            .height(20)
            .setEnabledIf(w -> ui.session.editable());
    }

    private TextFieldWidget configurationField(HologramMuiTextField adapter) {
        return ui.shell.adoptField(adapter)
            .height(20)
            .setEnabledIf(w -> ui.session.configurationEditable());
    }

    private boolean optionEditable() {
        return "_hatch_candidate".equals(ui.config.optionChannel()) ? ui.session.selectedNativeEditable()
            : ui.session.configurationEditable();
    }

    private boolean editable() {
        return ui.session.state().data.getBoolean("localStandardController") ? ui.session.configurationEditable()
            : ui.session.editable();
    }

    private String reason() {
        return editable()
            ? projectorOr("native.ui.updates_the_plan_only_construction_still_requires", "仅更新规划；施工仍需原材料核对与确认。")
            : ui.session.editingReason();
    }

    private String presetFileReason() {
        return ui.session.isTutorial()
            ? projectorOr("native.ui.tutorial_isolation_user_hatch_presets_and_last", "演示隔离：不读取或修改用户的仓室搭配和上次配置文件。")
            : reason();
    }

    private Flow pager(IntSupplier offset, IntSupplier count, int page, java.util.function.IntConsumer change) {
        Flow row = row();
        row.child(
            local(
                () -> projectorOr("native.ui.previous_page", "上一页"),
                () -> change.accept(Math.max(0, offset.getAsInt() - page)),
                () -> offset.getAsInt() > 0,
                () -> projectorOr("native.ui.view_the_previous_page", "查看前一页。")).expanded());
        row.child(
            HologramEditorNativeTheme
                .label(
                    () -> count.getAsInt() == 0 ? projectorOr("native.ui.0_entries", "0 项")
                        : (offset.getAsInt() + 1) + "–"
                            + Math.min(count.getAsInt(), offset.getAsInt() + page)
                            + " / "
                            + count.getAsInt())
                .widthRel(1f)
                .expanded()
                .height(20));
        row.child(
            local(
                () -> projectorOr("native.ui.next_page", "下一页"),
                () -> change.accept(offset.getAsInt() + page),
                () -> offset.getAsInt() + page < count.getAsInt(),
                () -> projectorOr("native.ui.view_the_next_page", "查看后一页。")).expanded());
        return row;
    }

    private ModularPanel channels() {
        Flow content = column().collapseDisabledChild(true);
        content.child(
            note(
                () -> (projectorOr("native.ui.source", "来源：") + " ") + HologramEditorPropertiesPane
                    .sourceLabel(ui, ui.session.state().data.getString("activeStructure"))));
        content.child(
            visibleRow(
                local(
                    () -> (projectorOr("native.ui.purpose", "用途：") + " ")
                        + ("LIVE_SNAPSHOT".equals(ui.session.state().data.getString("captureIntent"))
                            ? projectorOr("native.ui.world_snapshot", "现场快照")
                            : projectorOr("native.ui.new_template", "新建模板")),
                    () -> {
                        NBTTagCompound request = new NBTTagCompound();
                        request.setString(
                            "intent",
                            "LIVE_SNAPSHOT".equals(ui.session.state().data.getString("captureIntent")) ? "NEW_TEMPLATE"
                                : "LIVE_SNAPSHOT");
                        ui.session.send("captureIntent", request, true);
                    },
                    () -> editable() && !ui.session.state().data.getBoolean("virtual"),
                    () -> projectorOr(
                        "native.ui.recaptures_and_recalculates_materials_a_snapshot_keeps",
                        "切换后重新采集与计算材料；现场快照保留机器当前状态，新建模板使用建造结构。")),
                22,
                () -> ui.session.state().data.getCompoundTag("structureSemantics")
                    .getBoolean("sourcePolicy")));
        content.child(visibleRow(note(() -> {
            NBTTagCompound semantics = ui.session.state().data.getCompoundTag("structureSemantics");
            return semantics.hasNoTags()
                ? projectorOr(
                    "native.ui.interpreted_from_the_source_declaration_custom_conditions",
                    "按原结构声明解释；自定义条件需单独核实")
                : semantics.getBoolean("partial")
                    ? projectorOr("native.ui.some_rules_are_recognized_unverified_rules_grant", "部分规则已识别；未核实部分不授予施工资格")
                    : projectorOr(
                        "native.ui.structure_rules_recognized_whole_machine_constraints_and",
                        "已识别结构规则；施工前仍须核对整机约束与现场");
        }),
            28,
            () -> ui.session.state().data.getCompoundTag("structureSemantics")
                .getBoolean("sourcePolicy")));
        ListWidget<IWidget, ?> rows = list().name("config.channels.rows");
        for (int row = 0; row < 4; row++) {
            final int slot = row;
            rows.child(
                visibleRow(
                    dynamicAction("config.capability.row." + slot, () -> 2000 + ui.config.drawerOffset() + slot, () -> {
                        NBTTagCompound cap = capabilityRow(slot);
                        if (cap == null) return "";
                        String source = "main".equals(cap.getString("id")) ? projectorOr("native.ui.main_value", "主值")
                            : cap.getBoolean("explicit") ? projectorOr("native.ui.explicit", "指定")
                                : "presence".equals(cap.getString("kind")) ? projectorOr("native.ui.default", "默认")
                                    : projectorOr("native.ui.inherited", "继承");
                        String planned = ui.config.optionLabel(cap) + "（" + source + projectorOr("native.ui.text", "）");
                        if (!ui.host.parent()
                            .localControllerStandard()) return ui.config.capabilityLabel(cap) + "：" + planned;
                        NBTTagCompound observed = ui.session.state().data.getCompoundTag("siteChannels");
                        String id = cap.getString("id");
                        return ui.config.capabilityLabel(cap) + (" " + projectorOr("native.ui.world_297", "· 现场") + " ")
                            + (observed.hasKey(id, 3) ? Integer.toString(observed.getInteger(id))
                                : projectorOr("native.ui.unverified_298", "未确认"))
                            + (" " + projectorOr("native.ui.plan", "/ 规划") + " ")
                            + planned;
                    },
                        () -> ui.session.configurationEditable() && capabilityRow(slot) != null
                            && capabilityRow(slot).getBoolean("editable"),
                        () -> {
                            NBTTagCompound cap = capabilityRow(slot);
                            return cap == null ? projectorOr("native.ui.no_entry_on_this_page", "本页无此项")
                                : cap.getString("id") + (" " + projectorOr("native.ui.raw_value", "· 原始值") + " ")
                                    + cap.getInteger("current")
                                    + "；"
                                    + reason();
                        }).height(28),
                    28,
                    () -> capabilityRow(slot) != null));
        }
        content.child(rows);
        content.child(
            pager(
                () -> ui.config.drawerOffset(),
                () -> ui.config.capabilities()
                    .tagCount(),
                4,
                value -> ui.config.drawerOffset(value)));
        content.child(
            note(
                () -> projectorOr(
                    "native.ui.named_channel_lowercase_name_positive_integer_value",
                    "手填具名信道：小写 name / 正整数 value")));
        Flow raw = row();
        raw.child(configurationField(ui.config.channelNameField()).expanded());
        raw.child(configurationField(ui.config.channelValueField()).width(76));
        content.child(raw);
        content.child(
            action(
                67,
                () -> projectorOr("native.ui.add_update_named_channel", "添加 / 更新具名信道"),
                ui.session::configurationEditable,
                this::reason));
        Flow main = row();
        main.child(configurationField(ui.config.mainField()).expanded());
        main.child(
            action(
                30,
                () -> projectorOr("native.ui.apply_main_value", "应用主值"),
                ui.session::configurationEditable,
                () -> projectorOr("native.ui.channels_without_an_explicit_value_inherit_the", "未独立指定的子信道按原结构规则继承主值。"))
                    .width(100));
        content.child(main);
        Flow toggles = row();
        toggles.child(
            action(23, () -> projectorOr("native.ui.recapture", "重新采集"), this::editable, this::reason).expanded());
        toggles.child(
            action(
                33,
                () -> ui.session.state().data.getBoolean("localStandardController")
                    ? projectorOr("native.ui.clear_optional_hatch_preset", "撤销可选仓室预设")
                    : ui.session.state().data.getBoolean("noHatches") ? projectorOr("native.ui.casings_only", "只放外壳 ✓")
                        : projectorOr("native.ui.allow_hatches", "允许仓室"),
                () -> ui.session.configurationEditable() && ui.config.targetEditingMode()
                    && (!ui.session.state().data.getBoolean("localStandardController")
                        || ui.session.state().data.getBoolean("presetActive")),
                () -> ui.session.state().data.getBoolean("localStandardController") ? projectorOr(
                    "native.ui.restore_default_casings_at_optional_positions_keep",
                    "恢复可选位置的默认机壳；保留声明必需仓室和手选目标。") : reason()).expanded());
        if (!ui.session.state().data.getBoolean("localStandardController")) toggles.child(action(83, () -> {
            NBTTagCompound hatch = ui.config.capability("gt_hatch");
            return hatch != null && hatch.getBoolean("explicit") ? projectorOr("native.ui.automatic_materials", "自动选材")
                : projectorOr("native.ui.manual_hatches", "仓室手选");
        },
            () -> editable() && ui.config.targetEditingMode()
                && ui.config.capability("gt_hatch") != null
                && ui.config.capability("gt_hatch")
                    .getBoolean("editable"),
            this::reason).expanded());
        content.child(toggles);
        if (ui.session.state().data.getBoolean("localStandardController")) content.child(
            note(
                () -> projectorOr(
                    "native.ui.optional_hatch_positions_default_to_casings_mandatory",
                    "默认可选仓位使用机壳；声明必需仓室始终保留。")));
        ListWidget<IWidget, ?> semanticRules = new ListWidget<>().widthRel(1f)
            .height(84);
        for (int row = 0; row < 16; row++) {
            final int index = row;
            semanticRules.child(visibleRow(note(() -> semanticRule(index)), 28, () -> !semanticRule(index).isEmpty()));
        }
        content.child(
            visibleRow(
                semanticRules,
                84,
                () -> ui.session.state().data.getCompoundTag("structureSemantics")
                    .getBoolean("sourcePolicy")));
        boolean semantics = ui.session.state().data.getCompoundTag("structureSemantics")
            .getBoolean("sourcePolicy");
        return dialog(
            "channels",
            () -> projectorOr("native.ui.structure_tier_channels", "结构等级 / 信道"),
            content,
            () -> ui.router.activate(62),
            semantics ? 466 : 354);
    }

    private String semanticRule(int index) {
        NBTTagCompound semantics = ui.session.state().data.getCompoundTag("structureSemantics");
        NBTTagList rules = semantics.getTagList("rules", 10);
        if (index < rules.tagCount())
            return (projectorOr("native.ui.whole_structure", "整机 ·") + " ") + rules.getCompoundTagAt(index)
                .getString("text");
        NBTTagList restrictions = semantics.getTagList("restrictions", 8);
        int restriction = index - rules.tagCount();
        return restriction < restrictions.tagCount()
            ? (projectorOr("native.ui.build_restrictions", "建造 ·") + " ") + restrictions.getStringTagAt(restriction)
            : "";
    }

    private NBTTagCompound capabilityRow(int row) {
        int index = ui.config.drawerOffset() + row;
        NBTTagList caps = ui.config.capabilities();
        return index >= 0 && index < caps.tagCount() ? caps.getCompoundTagAt(index) : null;
    }

    private NBTTagCompound optionCapability() {
        return ui.config.capability(ui.config.optionChannel());
    }

    private NBTTagCompound optionRow(int row) {
        NBTTagCompound cap = optionCapability();
        if (cap == null) return null;
        int index = ui.config.drawerOffset() + row;
        NBTTagList options = cap.getTagList("options", 10);
        return index >= 0 && index < options.tagCount() ? options.getCompoundTagAt(index) : null;
    }

    private ModularPanel options() {
        Flow content = column();
        ListWidget<IWidget, ?> rows = list().name("config.options.rows");
        for (int row = 0; row < 7; row++) {
            final int slot = row;
            rows.child(
                visibleRow(
                    dynamicAction("config.option.row." + slot, () -> 4000 + ui.config.drawerOffset() + slot, () -> {
                        NBTTagCompound option = optionRow(slot), cap = optionCapability();
                        if (option == null || cap == null) return "";
                        boolean selected = option.getBoolean("present")
                            ? cap.getBoolean("explicit") && option.getInteger("value") == cap.getInteger("current")
                            : !cap.getBoolean("explicit");
                        return (selected ? "✓ " : "") + ui.config.capabilityOptionLabel(option);
                    }, () -> optionRow(slot) != null && optionEditable(), () -> {
                        NBTTagCompound option = optionRow(slot);
                        return option == null ? projectorOr("native.ui.no_entry_on_this_page", "本页无此项")
                            : (option.getBoolean("present")
                                ? (projectorOr("native.ui.explicit_target_raw_value", "显式目标；原始值") + " ")
                                    + option.getInteger("value")
                                : projectorOr("native.ui.restore_default_inherit", "恢复默认 / 继承")) + "；" + reason();
                    }),
                    22,
                    () -> optionRow(slot) != null));
        }
        content.child(rows);
        content.child(
            pager(
                () -> ui.config.drawerOffset(),
                () -> optionCapability() == null ? 0
                    : optionCapability().getTagList("options", 10)
                        .tagCount(),
                7,
                value -> ui.config.drawerOffset(value)));
        Flow raw = Flow.col()
            .widthRel(1f)
            .height(55)
            .childPadding(4);
        raw.setEnabledIf(w -> !"_hatch_candidate".equals(ui.config.optionChannel()));
        raw.child(note(() -> projectorOr("native.ui.raw_positive_integer_values_outside_the_list", "原始正整数（允许列表之外的值）")));
        Flow input = row();
        TextFieldWidget value = configurationField(ui.config.optionValueField()).expanded();
        ui.shell.registerTarget("config.option.value", value);
        input.child(value);
        input.child(
            action(
                68,
                () -> projectorOr("native.ui.apply_raw_value", "应用原始值"),
                ui.session::configurationEditable,
                this::reason).width(100));
        raw.child(input);
        content.child(raw);
        return dialog(
            "options",
            () -> optionCapability() == null ? projectorOr("native.ui.capability_options", "能力选项")
                : ui.config.capabilityLabel(optionCapability())
                    + (" " + projectorOr("native.ui.choose_target", "· 选择目标")),
            content,
            () -> {
                ui.router.activate(63);
                ui.config.drawerOffset(0);
            },
            316);
    }

    private Flow tierSelector() {
        Flow row = row();
        row.child(
            action(
                117,
                () -> "−",
                () -> ui.session.selectedCell() != null
                    && HologramTargetTiers.steppedTier(ui.config.availableTiers(), ui.config.nativeHatchTier(), -1)
                        != ui.config.nativeHatchTier(),
                () -> projectorOr(
                    "native.ui.cycle_this_cell_s_actual_candidate_tiers",
                    "沿本格实际候选等级切换；指定类型时沿用原自动选首个合法候选行为。")).width(28));
        row.child(
            action(
                98,
                () -> ui.config.nativeHatchTier() < 0 ? projectorOr("native.ui.tier_all", "等级：全部")
                    : (projectorOr("native.ui.tier", "等级：") + " ") + ui.config.tierName(ui.config.nativeHatchTier()),
                () -> ui.session.selectedCell() != null,
                () -> projectorOr("native.ui.clear_tier_filter", "清除等级筛选。")).expanded());
        row.child(
            action(
                118,
                () -> "+",
                () -> ui.session.selectedCell() != null
                    && HologramTargetTiers.steppedTier(ui.config.availableTiers(), ui.config.nativeHatchTier(), 1)
                        != ui.config.nativeHatchTier(),
                () -> projectorOr("native.ui.cycle_this_cell_s_actual_candidate_tiers_326", "沿本格实际候选等级切换。")).width(28));
        return row;
    }

    private int candidateIndex(int row) {
        List<Integer> choices = ui.config.filteredHatches();
        int offset = ui.config.hatchOffset() + row;
        return offset >= 0 && offset < choices.size() ? choices.get(offset) : -1;
    }

    private ItemStack candidate(int row) {
        int index = candidateIndex(row);
        HologramState.Cell cell = ui.session.selectedCell();
        return cell != null && index >= 0 && index < cell.candidates.size() ? cell.candidates.get(index) : null;
    }

    private String candidateCount(int index) {
        int[] available = ui.session.candidateAvailable();
        return ui.session.state().data.getBoolean("creativeMaterials") ? "∞"
            : index >= 0 && index < available.length ? Integer.toString(available[index]) : "?";
    }

    private ModularPanel candidates() {
        Flow content = column();
        content.child(
            action(
                97,
                () -> (projectorOr("native.ui.type", "类型：") + " ") + HologramUiConfiguration.roleLabel(
                    ui.config.hatchRoles()
                        .get(ui.config.nativeHatchRole()))
                    + " ▾",
                () -> ui.session.selectedCell() != null,
                () -> ui.config.candidateNote()));
        content.child(tierSelector());
        ListWidget<IWidget, ?> rows = list().name("config.candidates.rows");
        for (int row = 0; row < 5; row++) {
            final int slot = row;
            Flow line = row();
            line.setEnabledIf(w -> candidate(slot) != null);
            ItemDrawable item = new ItemDrawable();
            line.child(
                item.asWidget()
                    .size(18)
                    .onUpdateListener(w -> item.setItem(candidate(slot))));
            line.child(dynamicAction("config.candidate.row." + slot, () -> 10000 + candidateIndex(slot), () -> {
                ItemStack stack = candidate(slot);
                return stack == null ? ""
                    : (ui.session.selectedCell().chosenChoice == candidateIndex(slot) ? "✓ " : "")
                        + stack.getDisplayName()
                        + " ×"
                        + candidateCount(candidateIndex(slot));
            },
                () -> ui.session.selectedNativeEditable() && ui.preview.canInventoryPin() && candidate(slot) != null,
                () -> candidate(slot) == null ? projectorOr("native.ui.no_candidates_on_this_page", "本页无候选")
                    : candidate(slot).getDisplayName()
                        + (" " + projectorOr("native.ui.available_from_enabled_sources", "· 已启用来源可用") + " ")
                        + candidateCount(candidateIndex(slot))
                        + "；"
                        + ui.config.candidateNote()).expanded());
            rows.child(line);
        }
        content.child(rows);
        content.child(
            pager(
                () -> ui.config.hatchOffset(),
                () -> ui.config.filteredHatches()
                    .size(),
                5,
                value -> ui.config.hatchOffset(value)));
        content.child(
            note(
                () -> ui.config.filteredHatches()
                    .isEmpty() ? projectorOr("native.ui.no_candidates_match_this_filter_change_the", "本筛选无候选；请更换类型或等级。")
                        : ui.config.candidateNote()));
        content.child(
            action(
                66,
                () -> projectorOr("native.ui.restore_default_casing", "恢复默认壳"),
                () -> editable() && ui.preview.hasSelectedPin(),
                this::reason));
        Flow facing = row();
        facing.child(
            action(
                99,
                () -> ui.session.state().data.getBoolean("localStandardController") ? ui.config.nativeFacingLabel()
                    : (projectorOr("native.ui.hatch_facing", "仓室朝向：") + " ") + ui.config.directionName(
                        ui.session.cellTag()
                            .getInteger("hatchFacing")),
                () -> !ui.session.state().data.getBoolean("localStandardController")
                    && ui.session.selectedNativeEditable()
                    && ui.session.hatchFacings().length > 0,
                () -> projectorOr("native.ui.cycle_only_valid_device_facings_for_this", "只轮换当前格的合法设备朝向。")).expanded());
        facing.child(
            action(
                102,
                () -> projectorOr("native.ui.restore_facing", "恢复朝向"),
                () -> !ui.session.state().data.getBoolean("localStandardController")
                    && ui.session.selectedNativeEditable()
                    && ui.session.cellTag()
                        .getBoolean("hasFacingPin"),
                () -> projectorOr("native.ui.clear_this_cell_s_explicit_facing", "清除本格显式朝向。")).width(100));
        content.child(facing);
        return dialog(
            "candidates",
            () -> projectorOr("native.ui.replacement_options_type_tier", "本格可替换选项 · 类型 / 等级"),
            content,
            () -> {
                ui.router.activate(96);
                ui.config.roleDropdown(false);
            },
            332);
    }

    private ModularPanel roles() {
        Flow content = column();
        ListWidget<IWidget, ?> rows = list();
        for (int row = 0; row < 7; row++) {
            final int slot = row;
            rows.child(
                visibleRow(
                    dynamicAction("config.role.row." + slot, () -> 15000 + ui.config.nativeRoleOffset() + slot, () -> {
                        int index = ui.config.nativeRoleOffset() + slot;
                        return index < ui.config.hatchRoles()
                            .size() ? HologramUiConfiguration.roleLabel(
                                ui.config.hatchRoles()
                                    .get(index))
                                : "";
                    },
                        () -> ui.config.nativeRoleOffset() + slot < ui.config.hatchRoles()
                            .size(),
                        () -> projectorOr("native.ui.types_come_from_this_cell_s_actual", "类型仅来自当前格实际结构声明，不是全局设备类型表。")),
                    22,
                    () -> ui.config.nativeRoleOffset() + slot < ui.config.hatchRoles()
                        .size()));
        }
        content.child(rows);
        content.child(
            pager(
                () -> ui.config.nativeRoleOffset(),
                () -> ui.config.hatchRoles()
                    .size(),
                7,
                value -> ui.config.nativeRoleOffset(value)));
        return dialog(
            "roles",
            () -> projectorOr("native.ui.choose_target_type_for_this_cell", "选择本格目标类型"),
            content,
            () -> ui.config.roleDropdown(false),
            244);
    }

    private ModularPanel presets() {
        Flow content = column();
        Flow tabs = row();
        tabs.child(
            action(
                1710,
                () -> ui.config.nativeCustomPresets() ? projectorOr("native.ui.base_presets", "基础预设")
                    : projectorOr("native.ui.base_presets_338", "✓ 基础预设"),
                () -> true,
                () -> projectorOr("native.ui.structure_declared_quotas_and_independent_device_tiers", "结构声明配额与独立设备等级。"))
                    .expanded());
        tabs.child(
            action(
                1711,
                () -> ui.config.nativeCustomPresets() ? projectorOr("native.ui.custom_presets", "✓ 自定义搭配")
                    : projectorOr("native.ui.custom_presets_341", "自定义搭配"),
                () -> true,
                () -> projectorOr("native.ui.locally_saved_exact_devices_tiers_and_counts", "本机保存的具体设备、等级与数量组合，不含坐标。"))
                    .expanded());
        content.child(tabs);
        ListWidget<IWidget, ?> rows = list();
        for (int row = 0; row < 4; row++) {
            final int slot = row;
            rows.child(
                visibleRow(
                    action(
                        1700 + row,
                        () -> ui.config.nativePresetRow(slot),
                        () -> ui.config.nativePresetOffset() + slot < ui.config.nativePresetCount(),
                        () -> projectorOr("native.ui.select_a_local_preset_or_device_tier", "选择本地搭配或设备等级；尚未应用到规划。")),
                    22,
                    () -> ui.config.nativePresetOffset() + slot < ui.config.nativePresetCount()));
        }
        content.child(rows);
        Flow paging = row();
        paging.child(
            action(
                1717,
                () -> projectorOr("native.ui.previous_page", "上一页"),
                () -> ui.config.nativePresetOffset() > 0,
                () -> projectorOr("native.ui.first_four_entries", "前四项。")).expanded());
        paging.child(
            action(
                1718,
                () -> projectorOr("native.ui.next_page", "下一页"),
                () -> ui.config.nativePresetOffset() + 4 < ui.config.nativePresetCount(),
                () -> projectorOr("native.ui.last_four_entries", "后四项。")).expanded());
        content.child(paging);
        PagedWidget<?> pages = new PagedWidget<>().widthRel(1f)
            .height(80);
        Flow basic = Flow.col()
            .sizeRel(1f)
            .childPadding(4);
        basic.child(
            action(
                105,
                () -> ui.session.state().data.getBoolean("localStandardController")
                    ? HologramNativeUiText.presetTierPolicy(ui.config.nativePresetDowngrade())
                    : ui.config.nativePresetDowngrade()
                        ? projectorOr("native.ui.allow_downgrade_on_shortage", "缺料允许降级 ✓")
                        : projectorOr("native.ui.keep_exact_tier_on_shortage", "缺料严格保持等级"),
                () -> true,
                () -> ui.session.state().data.getBoolean("localStandardController")
                    ? HologramNativeUiText.presetTierPolicyHint()
                    : projectorOr(
                        "native.ui.keep_structure_quotas_manual_targets_and_installed",
                        "保留原结构配额、手选目标与现场仓室。")));
        basic.child(
            note(
                () -> ui.session.state().data.getBoolean("localStandardController")
                    ? HologramNativeUiText.presetTargetNote()
                    : ui.config.basicPresetActionAvailable()
                        ? projectorOr(
                            "native.ui.optional_shortcut_construction_can_be_confirmed_without",
                            "可选快捷搭配；不选择预设也可确认构建。")
                        : projectorOr(
                            "native.ui.select_a_tier_before_generating_a_preset",
                            "未选择预设时先选择等级；不选择预设也可确认构建。")));
        Flow custom = Flow.col()
            .sizeRel(1f)
            .childPadding(4);
        custom.child(field(ui.config.nativePresetNameField()).widthRel(1f));
        Flow manage = row();
        manage.child(
            action(
                1712,
                () -> ui.session.isTutorial() ? projectorOr("native.ui.tutorial_no_saving", "演示不保存")
                    : projectorOr("native.ui.save_with_name", "命名保存"),
                () -> editable() && !ui.session.isTutorial(),
                this::presetFileReason).expanded());
        manage.child(
            action(
                1713,
                () -> projectorOr("native.ui.overwrite_selected", "覆盖所选"),
                () -> editable() && ui.config.nativeSelectedPreset(),
                this::presetFileReason).expanded());
        manage.child(
            action(
                1714,
                () -> projectorOr("native.ui.delete_selected", "删除所选"),
                () -> ui.config.nativeSelectedPreset(),
                this::presetFileReason).expanded());
        custom.child(manage);
        if (!ui.session.isTutorial()) custom.child(
            note(
                () -> projectorOr(
                    "native.ui.exact_devices_nbt_and_counts_come_from",
                    "精确设备 / NBT / 数量来自当前规划；本机跨世界保存。")));
        pages.addPage(basic)
            .addPage(custom)
            .onUpdateListener(w -> {
                int page = ui.config.nativeCustomPresets() ? 1 : 0;
                if (pages.getCurrentPageIndex() != page) pages.setPage(page);
            });
        content.child(pages);
        Flow apply = row();
        apply.child(
            action(
                106,
                () -> ui.config.nativeCustomPresets() ? projectorOr("native.ui.apply_selected_preset", "应用所选搭配")
                    : projectorOr("native.ui.generate_preset_target", "生成预设目标"),
                () -> editable()
                    && (ui.session.state().data.getBoolean("localStandardController")
                        || ui.session.state().data.getBoolean("supported"))
                    && (ui.config.nativeCustomPresets() ? ui.config.customPresetActionAvailable()
                        : ui.config.basicPresetActionAvailable()),
                () -> ui.session.state().data.getBoolean("localStandardController")
                    ? HologramNativeUiText.presetTargetNote()
                    : reason()).expanded());
        apply.child(
            action(
                107,
                () -> projectorOr("native.ui.clear_preset", "撤销预设"),
                () -> editable() && ui.session.state().data.getBoolean("presetActive"),
                () -> ui.session.state().data.getBoolean("localStandardController")
                    ? projectorOr(
                        "native.ui.restore_default_casings_at_optional_positions_keep",
                        "恢复可选位置的默认机壳；保留声明必需仓室和手选目标。")
                    : projectorOr("native.ui.clear_the_automatic_preset_keep_installed_hatches", "撤自动预设，保留现场仓室与手选目标。"))
                        .width(100));
        content.child(apply);
        content.child(
            note(
                () -> ui.session.isTutorial()
                    ? projectorOr("native.ui.tutorial_mode_does_not_read_or_write", "演示不读写本机搭配。")
                    : ui.session.state().data.getBoolean("localStandardController")
                        ? HologramNativeUiText.reason(ui.session.state().data.getString("presetReason"))
                        : ui.session.state().data.getString("presetReason")));
        return dialog(
            "presets",
            () -> projectorOr("native.ui.hatch_presets_optional_shortcuts", "仓室预设 · 可选快捷搭配"),
            content,
            () -> ui.router.activate(96),
            368);
    }

    private ModularPanel sources() {
        Flow content = column();
        ListWidget<IWidget, ?> rows = list();
        String[] keys = { "materialMain", "materialContainers", "materialMe" };
        String[] names = { projectorOr("native.ui.main_inventory_363", "主背包"),
            projectorOr("native.ui.inventory_item_containers", "背包内物品容器"),
            projectorOr("native.ui.carried_me_wireless_terminal", "携带的 ME 无线终端") };
        for (int i = 0; i < keys.length; i++) {
            final int index = i;
            rows.child(
                action(
                    84 + i,
                    () -> names[index] + (ui.config.sourceEnabled(keys[index]) ? " ✓" : " ×"),
                    ui.session::configurationEditable,
                    this::reason).height(28));
        }
        rows.child(
            action(
                87,
                () -> (projectorOr("native.ui.priority", "优先：") + " ")
                    + names[Math.floorMod(ui.session.state().data.getInteger("materialPriority"), 3)],
                ui.session::configurationEditable,
                () -> projectorOr("native.ui.cycle_main_inventory_containers_me_priority", "轮换主背包 → 容器 → ME 的优先次序。")));
        rows.child(
            note(
                () -> projectorOr("native.ui.the_me_terminal_must_remain_bound_powered", "ME 终端仍需已绑定、有电、范围内并符合网络权限。")));
        content.child(rows);
        return dialog(
            "sources",
            () -> projectorOr("native.ui.material_sources", "材料来源"),
            content,
            () -> ui.router.activate(88),
            236);
    }

    private ItemStack inventoryStack(int row) {
        if (ui.preview.sourceTargets()) return ui.preview.sourceStack(ui.preview.sourceOffset() + row);
        int slot = row / 9 == 3 ? row % 9 : 9 + row;
        return ui.host.minecraft().thePlayer == null ? null
            : ui.host.minecraft().thePlayer.inventory.mainInventory[slot];
    }

    private void selectInventory(int row) {
        if (inventoryStack(row) == null) return;
        if (ui.preview.sourceTargets()) ui.preview.sourceChoice(ui.preview.sourceOffset() + row);
        else ui.preview.inventorySlot(row / 9 == 3 ? row % 9 : 9 + row);
    }

    private ModularPanel inventory() {
        Flow content = column();
        Flow tabs = row();
        tabs.child(
            action(
                16,
                () -> ui.preview.sourceTargets() ? projectorOr("native.ui.main_inventory_363", "主背包")
                    : projectorOr("native.ui.main_inventory_369", "✓ 主背包"),
                () -> true,
                () -> projectorOr("native.ui.select_a_real_inventory_sample_no_items", "选择真实库存样本，不消耗物品。")).expanded());
        tabs.child(
            action(
                17,
                () -> ui.preview.sourceTargets() ? projectorOr("native.ui.material_sources_371", "✓ 材料来源")
                    : projectorOr("native.ui.material_sources", "材料来源"),
                () -> true,
                () -> projectorOr("native.ui.enabled_inventory_container_and_me_catalogues", "已启用的背包、容器和 ME 目录。"))
                    .expanded());
        tabs.child(
            action(
                95,
                () -> projectorOr("native.ui.replacement_options", "可替换选项"),
                () -> ui.session.selectedCell() != null,
                () -> ui.config.candidateNote()).expanded());
        content.child(tabs);
        content.child(
            note(
                () -> projectorOr(
                    "native.ui.select_targets_on_the_left_ctrl_for",
                    "左侧点选目标，Ctrl 多选；点击样本后应用，或拖到选区；不消耗物品。")));
        ListWidget<IWidget, ?> grid = list();
        for (int row = 0; row < 4; row++) {
            Flow line = Flow.row()
                .widthRel(1f)
                .height(30)
                .childPadding(4);
            for (int col = 0; col < 9; col++) {
                final int slot = row * 9 + col;
                SampleButton button = new SampleButton(slot).widthRel(1f)
                    .expanded()
                    .height(30);
                ui.shell.registerTarget("config.inventory.sample." + slot, button);
                line.child(button);
            }
            grid.child(line);
        }
        content.child(grid);
        content.child(
            pager(
                () -> ui.preview.sourceOffset(),
                () -> ui.preview.sourceTargets() ? ui.session.state().data.getTagList("materialChoices", 10)
                    .tagCount() : 36,
                36,
                value -> ui.preview.sourceOffset(value)));
        content.child(
            note(
                () -> ui.preview.pickedTarget() == null ? projectorOr("native.ui.no_item_sample_selected", "未选择物品样本")
                    : (projectorOr("native.ui.sample", "样本：") + " ") + ui.preview.pickedTarget()
                        .getDisplayName()));
        content.child(
            action(
                65,
                () -> ui.session.selectedCell() == null
                    ? projectorOr("native.ui.select_target_cells_in_the_preview_first", "先在预览选中目标格")
                    : (projectorOr("native.ui.apply_to_selected", "应用到所选") + " ") + ui.preview.selection()
                        .size() + (" " + projectorOr("native.ui.cells_61", "格")),
                () -> editable() && ui.preview.canInventoryPin() && ui.preview.pickedTarget() != null,
                () -> projectorOr(
                    "native.ui.all_selected_cells_must_pass_validation_together",
                    "全部所选格通过校验后一起替换；只更新规划，不消耗物品。")));
        Flow footer = row();
        footer.child(
            action(
                66,
                () -> projectorOr("native.ui.restore_default_casing", "恢复默认壳"),
                () -> editable() && ui.preview.hasSelectedPin(),
                this::reason).expanded());
        footer.child(
            action(18, () -> projectorOr("native.ui.refresh_material_sources", "刷新材料来源"), this::editable, this::reason)
                .expanded());
        content.child(footer);
        content.child(
            note(
                () -> ui.session.pendingSequence() >= 0
                    ? projectorOr("native.ui.waiting_for_server_target_validation", "等待服务端校验目标…")
                    : ui.session.state().data.getString("status")));
        return dialog(
            "inventory",
            () -> projectorOr("native.ui.choose_blocks_hatches_no_item_consumption", "选择方块 / 仓室 · 不消耗物品"),
            content,
            () -> ui.router.activate(64),
            360).width(() -> Math.min(346, Math.max(160, ui.host.screenWidth() / 2 - 18)), Unit.Measure.PIXEL)
                .right(12)
                .top(12);
    }

    private ModularPanel reset() {
        Flow content = column();
        content.child(
            note(
                () -> ui.config.resetCalibration()
                    ? projectorOr(
                        "native.ui.recognize_world_material_channels_while_keeping_independent",
                        "识别现场材质信道，保留独立仓室与朝向。")
                    : projectorOr("native.ui.clear_custom_targets_and_restore_the_structure", "清除自定义目标，恢复结构默认规划。")));
        content.child(note(() -> projectorOr("native.ui.updates_only_the_plan_does_not_build", "仅更新规划，不施工，也不删除已存预设。")));
        content.child(
            note(
                () -> ui.config.resetCalibration()
                    ? projectorOr(
                        "native.ui.unrecognized_dimensions_and_channels_are_kept_recognized",
                        "未识别的尺寸与信道保留，已识别项可继续修改。")
                    : projectorOr("native.ui.check_whether_to_keep_your_manual_targets", "操作前请确认当前手改目标是否需要保留。")));
        Flow footer = row();
        footer.child(
            action(
                136,
                () -> projectorOr("native.ui.keep_current_plan", "保留当前规划"),
                () -> true,
                () -> projectorOr("native.ui.cancel_the_pending_reset", "取消尚未执行的重置。")).expanded());
        footer.child(
            action(
                137,
                () -> ui.config.resetCalibration() ? projectorOr("native.ui.confirm_match_current_world", "确认校准为当前")
                    : projectorOr("native.ui.confirm_reset_to_default", "确认重置为默认"),
                this::editable,
                this::reason).expanded());
        content.child(footer);
        return dialog(
            "reset",
            () -> ui.config.resetCalibration()
                ? projectorOr("native.ui.match_the_plan_to_the_current_world", "将规划校准为当前现场？")
                : projectorOr("native.ui.reset_the_plan_to_structure_defaults", "将规划重置为结构默认？"),
            content,
            () -> ui.config.resetReview(false),
            216);
    }

    private static final class Window {

        final BooleanSupplier visible;
        final IPanelHandler handler;

        Window(BooleanSupplier visible, IPanelHandler handler) {
            this.visible = visible;
            this.handler = handler;
        }
    }

    /** A native MUI drag payload, never a movable inventory slot or an item transfer. */
    private final class SampleButton extends ButtonWidget<SampleButton> implements IDraggable {

        private final int row;
        private final ItemDrawable icon = new ItemDrawable();
        private ItemStack dragSample;
        private boolean moving, dragFromSources;
        private int dragChoice;

        SampleButton(int row) {
            this.row = row;
            background(
                (context, x, y, width, height, theme) -> HologramEditorNativeTheme
                    .background(available(), selected(), false, false)
                    .draw(context, x, y, width, height, theme));
            hoverBackground(
                (context, x, y, width, height, theme) -> HologramEditorNativeTheme
                    .background(available(), selected(), true, false)
                    .draw(context, x, y, width, height, theme));
            overlay(
                icon.asIcon()
                    .size(18));
            onUpdateListener(w -> icon.setItem(inventoryStack(row)));
            tooltipBuilder(
                t -> t.addLine(
                    inventoryStack(row) == null ? projectorOr("native.ui.empty", "空")
                        : inventoryStack(row).getDisplayName() + (" "
                            + projectorOr("native.ui.click_to_select_a_sample_or_drag", "· 点击选择样本，或拖到预览格；不消耗物品。"))));
            tooltipAutoUpdate(true);
        }

        private boolean available() {
            return ui.preview.inventory() && editable() && inventoryStack(row) != null;
        }

        private int choice() {
            return ui.preview.sourceTargets() ? ui.preview.sourceOffset() + row : row / 9 == 3 ? row % 9 : 9 + row;
        }

        private boolean selected() {
            return ui.preview.sourceTargets() ? ui.preview.sourceChoice() == choice()
                : ui.preview.inventorySlot() == choice();
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0 || !available()) return Result.STOP;
            selectInventory(row);
            // ACCEPT lets ModularPanel.onHoveredClick hand capture to IDraggable.
            return Result.ACCEPT;
        }

        @Override
        public boolean onDragStart(int button) {
            if (button != 0 || !available()) return false;
            selectInventory(row);
            dragFromSources = ui.preview.sourceTargets();
            dragChoice = choice();
            dragSample = inventoryStack(row).copy();
            ui.preview.inventoryDragging(true);
            return true;
        }

        private boolean sameSample() {
            ItemStack current = ui.preview.pickedTarget();
            return dragSample != null && current != null
                && dragFromSources == ui.preview.sourceTargets()
                && dragChoice == (dragFromSources ? ui.preview.sourceChoice() : ui.preview.inventorySlot())
                && current.isItemEqual(dragSample)
                && ItemStack.areItemStackTagsEqual(current, dragSample);
        }

        @Override
        public boolean canDropHere(int x, int y, IWidget widget) {
            // A modal's covered scene pixels are never drop targets. Outside it MUI may report no hovered widget.
            return (widget == null || widget == ui.shell.scene()) && sameSample()
                && ui.shell.canDropInventorySample(x, y);
        }

        @Override
        public void onDragEnd(boolean successful) {
            try {
                if (successful && sameSample())
                    ui.shell.dropInventorySample(getContext().getAbsMouseX(), getContext().getAbsMouseY());
            } finally {
                ui.preview.inventoryDragging(false);
                dragSample = null;
            }
        }

        @Override
        public void drawMovingState(ModularGuiContext context, float partialTicks) {
            if (dragSample != null && ui.preview.inventory()) GuiDraw.drawItem(
                dragSample,
                context.getMouseX() - 8,
                context.getMouseY() - 8,
                16,
                16,
                context.getCurrentDrawingZ());
        }

        @Override
        public void onDrag(int button, long elapsed) {}

        @Override
        public Area getMovingArea() {
            return null;
        }

        @Override
        public boolean isMoving() {
            return moving;
        }

        @Override
        public void setMoving(boolean moving) {
            this.moving = moving;
        }
    }
}
