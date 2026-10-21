package com.miaokatze.gtmb.client.hologram;

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
        Dialog<?> panel = new Dialog<Object>("gtmb_editor_configuration_" + name) {

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
        panel.width(() -> Math.min(346, Math.max(160, ui.host.screenWidth() - 24)), Unit.Measure.PIXEL)
            .height(() -> Math.min(height, Math.max(160, ui.host.screenHeight() - 24)), Unit.Measure.PIXEL)
            .background(HologramEditorNativeTheme.PANEL)
            .onCloseAction(() -> {
                if ("inventory".equals(name) && panel.getContext()
                    .hasDraggable())
                    panel.getContext()
                        .dropDraggable(true);
                close.run();
            });
        Flow heading = row();
        heading.child(
            HologramEditorNativeTheme.label(title, HologramEditorNativeTheme.TITLE)
                .expanded()
                .height(20));
        ButtonWidget<?> closeButton = local(() -> "×", () -> {
            if ("inventory".equals(name) && panel.getContext()
                .hasDraggable())
                panel.getContext()
                    .dropDraggable(true);
            close.run();
            panel.closeIfOpen();
        }, () -> true, () -> "关闭窗口；已接受的规划修改保留。").width(24);
        heading.child(closeButton);
        ui.shell.registerTarget("modal", panel);
        ui.shell.registerTarget("modal.close", closeButton);
        ui.shell.registerTarget("config." + name, panel);
        ui.shell.registerTarget("config." + name + ".close", closeButton);
        // The entire form can scroll on short screens; its inner bounded row list keeps its normal allocation.
        ListWidget<IWidget, ?> viewport = list();
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
        return new ListWidget<>().widthRel(1f)
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

    private ButtonWidget<?> dynamicAction(IntSupplier id, Supplier<String> label, BooleanSupplier enabled,
        Supplier<String> tooltip) {
        return local(label, () -> ui.router.activate(id.getAsInt()), enabled, tooltip);
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

    private boolean editable() {
        return ui.session.editable();
    }

    private String reason() {
        return ui.session.editable() ? "仅更新规划；施工仍需原材料核对与确认。" : ui.session.editingReason();
    }

    private String presetFileReason() {
        return ui.session.isTutorial() ? "演示隔离：不读取或修改用户的仓室搭配和上次配置文件。" : reason();
    }

    private Flow pager(IntSupplier offset, IntSupplier count, int page, java.util.function.IntConsumer change) {
        Flow row = row();
        row.child(
            local(
                () -> "上一页",
                () -> change.accept(Math.max(0, offset.getAsInt() - page)),
                () -> offset.getAsInt() > 0,
                () -> "查看前一页。").expanded());
        row.child(
            HologramEditorNativeTheme
                .label(
                    () -> count.getAsInt() == 0 ? "0 项"
                        : (offset.getAsInt() + 1) + "–"
                            + Math.min(count.getAsInt(), offset.getAsInt() + page)
                            + " / "
                            + count.getAsInt())
                .expanded()
                .height(20));
        row.child(
            local(
                () -> "下一页",
                () -> change.accept(offset.getAsInt() + page),
                () -> offset.getAsInt() + page < count.getAsInt(),
                () -> "查看后一页。").expanded());
        return row;
    }

    private ModularPanel channels() {
        Flow content = column();
        content.child(
            note(
                () -> "来源：" + HologramEditorPropertiesPane
                    .sourceLabel(ui, ui.session.state().data.getString("activeStructure"))));
        ListWidget<IWidget, ?> rows = list();
        for (int row = 0; row < 4; row++) {
            final int slot = row;
            rows.child(visibleRow(dynamicAction(() -> 2000 + ui.config.drawerOffset() + slot, () -> {
                NBTTagCompound cap = capabilityRow(slot);
                if (cap == null) return "";
                String source = "main".equals(cap.getString("id")) ? "主值"
                    : cap.getBoolean("explicit") ? "指定" : "presence".equals(cap.getString("kind")) ? "默认" : "继承";
                return cap.getString("label") + "：" + ui.config.optionLabel(cap) + "（" + source + "）";
            }, () -> editable() && capabilityRow(slot) != null && capabilityRow(slot).getBoolean("editable"), () -> {
                NBTTagCompound cap = capabilityRow(slot);
                return cap == null ? "本页无此项"
                    : cap.getString("id") + " · 原始值 " + cap.getInteger("current") + "；" + reason();
            }).height(28), 28, () -> capabilityRow(slot) != null));
        }
        content.child(rows);
        content.child(
            pager(
                () -> ui.config.drawerOffset(),
                () -> ui.config.capabilities()
                    .tagCount(),
                4,
                value -> ui.config.drawerOffset(value)));
        content.child(note(() -> "手填具名信道：小写 name / 正整数 value"));
        Flow raw = row();
        raw.child(field(ui.config.channelNameField()).expanded());
        raw.child(field(ui.config.channelValueField()).width(76));
        content.child(raw);
        content.child(action(67, () -> "添加 / 更新具名信道", this::editable, this::reason));
        Flow main = row();
        main.child(field(ui.config.mainField()).expanded());
        main.child(action(30, () -> "应用主值", this::editable, () -> "未独立指定的子信道按原结构规则继承主值。").width(100));
        content.child(main);
        Flow toggles = row();
        toggles.child(action(23, () -> "重新采集", this::editable, this::reason).expanded());
        toggles.child(
            action(
                33,
                () -> ui.session.state().data.getBoolean("noHatches") ? "只放外壳 ✓" : "允许仓室",
                () -> editable() && ui.session.state().data.getInteger("mode") != 2,
                this::reason).expanded());
        toggles.child(action(83, () -> {
            NBTTagCompound hatch = ui.config.capability("gt_hatch");
            return hatch != null && hatch.getBoolean("explicit") ? "自动选材" : "仓室手选";
        },
            () -> editable() && ui.session.state().data.getInteger("mode") != 2
                && ui.config.capability("gt_hatch") != null
                && ui.config.capability("gt_hatch")
                    .getBoolean("editable"),
            this::reason).expanded());
        content.child(toggles);
        return dialog("channels", () -> "结构等级 / 信道", content, () -> ui.router.activate(62), 354);
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
        ListWidget<IWidget, ?> rows = list();
        for (int row = 0; row < 7; row++) {
            final int slot = row;
            rows.child(visibleRow(dynamicAction(() -> 4000 + ui.config.drawerOffset() + slot, () -> {
                NBTTagCompound option = optionRow(slot), cap = optionCapability();
                if (option == null || cap == null) return "";
                boolean selected = option.getBoolean("present")
                    ? cap.getBoolean("explicit") && option.getInteger("value") == cap.getInteger("current")
                    : !cap.getBoolean("explicit");
                return (selected ? "✓ " : "") + option.getString("label");
            }, () -> editable() && optionRow(slot) != null, () -> {
                NBTTagCompound option = optionRow(slot);
                return option == null ? "本页无此项"
                    : (option.getBoolean("present") ? "显式目标；原始值 " + option.getInteger("value") : "恢复默认 / 继承") + "；"
                        + reason();
            }), 22, () -> optionRow(slot) != null));
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
        raw.child(note(() -> "原始正整数（允许列表之外的值）"));
        Flow input = row();
        input.child(field(ui.config.optionValueField()).expanded());
        input.child(action(68, () -> "应用原始值", this::editable, this::reason).width(100));
        raw.child(input);
        content.child(raw);
        return dialog(
            "options",
            () -> optionCapability() == null ? "能力选项" : optionCapability().getString("label") + " · 选择目标",
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
                () -> "沿本格实际候选等级切换；指定类型时沿用原自动选首个合法候选行为。").width(28));
        row.child(
            action(
                98,
                () -> ui.config.nativeHatchTier() < 0 ? "等级：全部"
                    : "等级：" + ui.config.tierName(ui.config.nativeHatchTier()),
                () -> ui.session.selectedCell() != null,
                () -> "清除等级筛选。").expanded());
        row.child(
            action(
                118,
                () -> "+",
                () -> ui.session.selectedCell() != null
                    && HologramTargetTiers.steppedTier(ui.config.availableTiers(), ui.config.nativeHatchTier(), 1)
                        != ui.config.nativeHatchTier(),
                () -> "沿本格实际候选等级切换。").width(28));
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
        int[] available = ui.session.cellTag()
            .getIntArray("candidateAvailable");
        return ui.session.state().data.getBoolean("creativeMaterials") ? "∞"
            : index >= 0 && index < available.length ? Integer.toString(available[index]) : "?";
    }

    private ModularPanel candidates() {
        Flow content = column();
        content.child(
            action(
                97,
                () -> "类型：" + HologramUiConfiguration.roleLabel(
                    ui.config.hatchRoles()
                        .get(ui.config.nativeHatchRole()))
                    + " ▾",
                () -> ui.session.selectedCell() != null,
                () -> ui.config.candidateNote()));
        content.child(tierSelector());
        ListWidget<IWidget, ?> rows = list();
        for (int row = 0; row < 5; row++) {
            final int slot = row;
            Flow line = row();
            line.setEnabledIf(w -> candidate(slot) != null);
            ItemDrawable item = new ItemDrawable();
            line.child(
                item.asWidget()
                    .size(18)
                    .onUpdateListener(w -> item.setItem(candidate(slot))));
            line.child(dynamicAction(() -> 10000 + candidateIndex(slot), () -> {
                ItemStack stack = candidate(slot);
                return stack == null ? ""
                    : (ui.session.selectedCell().chosenChoice == candidateIndex(slot) ? "✓ " : "")
                        + stack.getDisplayName()
                        + " ×"
                        + candidateCount(candidateIndex(slot));
            },
                () -> editable() && ui.preview.canInventoryPin() && candidate(slot) != null,
                () -> candidate(slot) == null ? "本页无候选"
                    : candidate(slot).getDisplayName() + " · 已启用来源可用 "
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
                    .isEmpty() ? "本筛选无候选；请更换类型或等级。" : ui.config.candidateNote()));
        content.child(action(66, () -> "恢复默认壳", () -> editable() && ui.preview.hasSelectedPin(), this::reason));
        Flow facing = row();
        facing.child(
            action(
                99,
                () -> "仓室朝向：" + ui.config.directionName(
                    ui.session.cellTag()
                        .getInteger("hatchFacing")),
                () -> editable() && ui.session.cellTag()
                    .getIntArray("hatchFacings").length > 0,
                () -> "只轮换当前格的合法设备朝向。").expanded());
        facing.child(
            action(
                102,
                () -> "恢复朝向",
                () -> editable() && ui.session.cellTag()
                    .getBoolean("hasFacingPin"),
                () -> "清除本格显式朝向。").width(100));
        content.child(facing);
        return dialog("candidates", () -> "本格可替换选项 · 类型 / 等级", content, () -> {
            ui.router.activate(96);
            ui.config.roleDropdown(false);
        }, 332);
    }

    private ModularPanel roles() {
        Flow content = column();
        ListWidget<IWidget, ?> rows = list();
        for (int row = 0; row < 7; row++) {
            final int slot = row;
            rows.child(visibleRow(dynamicAction(() -> 15000 + ui.config.nativeRoleOffset() + slot, () -> {
                int index = ui.config.nativeRoleOffset() + slot;
                return index < ui.config.hatchRoles()
                    .size() ? HologramUiConfiguration.roleLabel(
                        ui.config.hatchRoles()
                            .get(index))
                        : "";
            },
                () -> ui.config.nativeRoleOffset() + slot < ui.config.hatchRoles()
                    .size(),
                () -> "类型仅来自当前格实际结构声明，不是全局设备类型表。"),
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
        return dialog("roles", () -> "选择本格目标类型", content, () -> ui.config.roleDropdown(false), 244);
    }

    private ModularPanel presets() {
        Flow content = column();
        Flow tabs = row();
        tabs.child(
            action(1710, () -> ui.config.nativeCustomPresets() ? "基础预设" : "✓ 基础预设", () -> true, () -> "结构声明配额与独立设备等级。")
                .expanded());
        tabs.child(
            action(
                1711,
                () -> ui.config.nativeCustomPresets() ? "✓ 自定义搭配" : "自定义搭配",
                () -> true,
                () -> "本机保存的具体设备、等级与数量组合，不含坐标。").expanded());
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
                        () -> "选择本地搭配或设备等级；尚未应用到规划。"),
                    22,
                    () -> ui.config.nativePresetOffset() + slot < ui.config.nativePresetCount()));
        }
        content.child(rows);
        Flow paging = row();
        paging.child(action(1717, () -> "上一页", () -> ui.config.nativePresetOffset() > 0, () -> "前四项。").expanded());
        paging.child(
            action(
                1718,
                () -> "下一页",
                () -> ui.config.nativePresetOffset() + 4 < ui.config.nativePresetCount(),
                () -> "后四项。").expanded());
        content.child(paging);
        PagedWidget<?> pages = new PagedWidget<>().widthRel(1f)
            .height(80);
        Flow basic = Flow.col()
            .sizeRel(1f)
            .childPadding(4);
        basic.child(
            action(
                105,
                () -> ui.config.nativePresetDowngrade() ? "缺料允许降级 ✓" : "缺料严格保持等级",
                () -> true,
                () -> "保留原结构配额、手选目标与现场仓室。"));
        basic.child(note(() -> "可选快捷搭配；不选择预设也可确认构建。"));
        Flow custom = Flow.col()
            .sizeRel(1f)
            .childPadding(4);
        custom.child(field(ui.config.nativePresetNameField()).widthRel(1f));
        Flow manage = row();
        manage.child(
            action(
                1712,
                () -> ui.session.isTutorial() ? "演示不保存" : "命名保存",
                () -> editable() && !ui.session.isTutorial(),
                this::presetFileReason).expanded());
        manage.child(
            action(1713, () -> "覆盖所选", () -> editable() && ui.config.nativeSelectedPreset(), this::presetFileReason)
                .expanded());
        manage.child(
            action(1714, () -> "删除所选", () -> ui.config.nativeSelectedPreset(), this::presetFileReason).expanded());
        custom.child(manage);
        custom.child(note(() -> ui.session.isTutorial() ? "演示不读写本机搭配；保存与上次配置不可用。" : "精确设备 / NBT / 数量来自当前规划；本机跨世界保存。"));
        pages.addPage(basic)
            .addPage(custom)
            .onUpdateListener(w -> {
                int page = ui.config.nativeCustomPresets() ? 1 : 0;
                if (pages.getCurrentPageIndex() != page) pages.setPage(page);
            });
        content.child(pages);
        content.child(
            action(
                1715,
                () -> "使用上次仓室预设",
                () -> editable() && ui.config.nativeLastPreset(true),
                this::presetFileReason));
        Flow apply = row();
        apply.child(
            action(
                106,
                () -> ui.config.nativeCustomPresets() ? "应用所选搭配" : "生成预设目标",
                () -> editable() && ui.session.state().data.getBoolean("supported")
                    && (ui.config.nativeCustomPresets() ? ui.config.nativeSelectedPreset()
                        : ui.session.state().data.getBoolean("presetAvailable")),
                this::reason).expanded());
        apply.child(
            action(
                107,
                () -> "撤销预设",
                () -> editable() && ui.session.state().data.getBoolean("presetActive"),
                () -> "撤自动预设，保留现场仓室与手选目标。").width(100));
        content.child(apply);
        content.child(
            note(
                () -> ui.session.isTutorial() ? "演示不读写本机搭配；保存与上次配置不可用。"
                    : ui.session.state().data.getString("presetReason")));
        return dialog("presets", () -> "仓室预设 · 可选快捷搭配", content, () -> ui.router.activate(96), 368);
    }

    private ModularPanel sources() {
        Flow content = column();
        ListWidget<IWidget, ?> rows = list();
        String[] keys = { "materialMain", "materialContainers", "materialMe" };
        String[] names = { "主背包", "背包内物品容器", "携带的 ME 无线终端" };
        for (int i = 0; i < keys.length; i++) {
            final int index = i;
            rows.child(
                action(
                    84 + i,
                    () -> names[index] + (ui.config.sourceEnabled(keys[index]) ? " ✓" : " ×"),
                    this::editable,
                    this::reason).height(28));
        }
        rows.child(
            action(
                87,
                () -> "优先：" + names[Math.floorMod(ui.session.state().data.getInteger("materialPriority"), 3)],
                this::editable,
                () -> "轮换主背包 → 容器 → ME 的优先次序。"));
        rows.child(
            action(
                1716,
                () -> "使用上次材料来源配置",
                () -> editable() && ui.config.nativeLastPreset(false),
                this::presetFileReason));
        rows.child(note(() -> "ME 终端仍需已绑定、有电、范围内并符合网络权限。"));
        content.child(rows);
        return dialog("sources", () -> "材料来源", content, () -> ui.router.activate(88), 236);
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
            action(16, () -> ui.preview.sourceTargets() ? "主背包" : "✓ 主背包", () -> true, () -> "选择真实库存样本，不消耗物品。")
                .expanded());
        tabs.child(
            action(17, () -> ui.preview.sourceTargets() ? "✓ 材料来源" : "材料来源", () -> true, () -> "已启用的背包、容器和 ME 目录。")
                .expanded());
        tabs.child(
            action(95, () -> "可替换选项", () -> ui.session.selectedCell() != null, () -> ui.config.candidateNote())
                .expanded());
        content.child(tabs);
        content.child(note(() -> "点击样本再点目标槽，或拖到左侧预览格；不消耗物品。"));
        ListWidget<IWidget, ?> grid = list();
        for (int row = 0; row < 4; row++) {
            Flow line = Flow.row()
                .widthRel(1f)
                .height(30)
                .childPadding(4);
            for (int col = 0; col < 9; col++) {
                final int slot = row * 9 + col;
                SampleButton button = new SampleButton(slot).expanded()
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
                () -> ui.preview.pickedTarget() == null ? "未选择物品样本"
                    : "样本：" + ui.preview.pickedTarget()
                        .getDisplayName()));
        content.child(
            action(
                65,
                () -> ui.session.selectedCell() == null ? "先在预览选中目标格"
                    : "指定目标格：" + ui.preview.cellName(ui.session.selectedCell(), false),
                () -> editable() && ui.preview.canInventoryPin() && ui.preview.pickedTarget() != null,
                () -> "沿用原 customPin / sourcePin：只更新当前一格，不消耗物品。"));
        Flow footer = row();
        footer
            .child(action(66, () -> "恢复默认壳", () -> editable() && ui.preview.hasSelectedPin(), this::reason).expanded());
        footer.child(action(18, () -> "刷新材料来源", this::editable, this::reason).expanded());
        content.child(footer);
        content.child(
            note(() -> ui.session.pendingSequence() >= 0 ? "等待服务端校验目标…" : ui.session.state().data.getString("status")));
        return dialog("inventory", () -> "选择方块 / 仓室 · 不消耗物品", content, () -> ui.router.activate(64), 360).right(12)
            .top(12);
    }

    private ModularPanel reset() {
        Flow content = column();
        content.child(note(() -> ui.config.resetCalibration() ? "识别现场材质信道，保留独立仓室与朝向。" : "清除自定义目标，恢复结构默认规划。"));
        content.child(note(() -> "仅更新规划，不施工，也不删除已存预设。"));
        content.child(note(() -> ui.config.resetCalibration() ? "未识别的尺寸与信道保留，已识别项可继续修改。" : "操作前请确认当前手改目标是否需要保留。"));
        Flow footer = row();
        footer.child(action(136, () -> "保留当前规划", () -> true, () -> "取消尚未执行的重置。").expanded());
        footer.child(
            action(137, () -> ui.config.resetCalibration() ? "确认校准为当前" : "确认重置为默认", this::editable, this::reason)
                .expanded());
        content.child(footer);
        return dialog(
            "reset",
            () -> ui.config.resetCalibration() ? "将规划校准为当前现场？" : "将规划重置为结构默认？",
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
                    inventoryStack(row) == null ? "空"
                        : inventoryStack(row).getDisplayName() + " · 点击选择样本，或拖到预览格；不消耗物品。"));
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
