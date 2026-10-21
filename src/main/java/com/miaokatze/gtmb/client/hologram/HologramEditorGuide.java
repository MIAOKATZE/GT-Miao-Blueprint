package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Persistent native guidance. It owns no editor value or production command handler. */
final class HologramEditorGuide {

    private static final int BASE = HologramEditorControlIds.GUIDE;
    private final HologramUiModules ui;
    private Flow bar, chip;
    private ModularPanel host;
    private IPanelHandler reference;

    HologramEditorGuide(HologramUiModules ui) {
        this.ui = ui;
    }

    private HologramEditorPreferences preferences() {
        return HologramEditorPreferences.get(ui.host.minecraft().mcDataDir);
    }

    IWidget buildBar() {
        if (bar != null) return bar;
        bar = Flow.row()
            .name("guide.bar")
            .widthRel(1f)
            .height(26)
            .childPadding(6)
            .background(HologramEditorNativeTheme.PAPER)
            .disableThemeBackground(true);
        bar.child(
            Flow.col()
                .name("guide.message")
                .expanded()
                .coverChildrenHeight()
                .childPadding(1)
                .child(
                    HologramEditorNativeTheme.label(this::instruction)
                        .widthRel(1f)
                        .height(11)
                        .name("guide.instruction"))
                .child(
                    HologramEditorNativeTheme.label(this::keys, HologramEditorNativeTheme.MUTED)
                        .widthRel(1f)
                        .height(10)
                        .name("guide.keys")));
        bar.child(
            HologramEditorNativeTheme
                .iconButton("collapse-up", () -> "收纳智能指引", this::toggleCollapsed, () -> true, () -> false)
                .size(20)
                .name("guide.collapse"));
        bar.child(
            HologramEditorNativeTheme
                .iconButton("help-circle", () -> "完整说明与独立教程", this::toggleReference, () -> true, () -> false)
                .size(20)
                .name("guide.help"));
        refreshCollapsed();
        return bar;
    }

    void attach(ModularPanel panel) {
        if (ui.session.isTutorial() || host != null) return;
        host = panel;
        reference = IPanelHandler.simple(panel, (ignored, player) -> buildReference(), true);
        refreshCollapsed();
    }

    IWidget buildChip() {
        if (chip != null) return chip;
        chip = Flow.row()
            .coverChildren()
            .height(20)
            .childPadding(3)
            .name("guide.collapsed")
            .background(IDrawable.NONE)
            .disableThemeBackground(true);
        chip.child(
            HologramEditorNativeTheme.button(() -> "指引", "collapse-down", this::toggleCollapsed)
                .size(56, 20)
                .name("guide.expand"));
        chip.child(
            HologramEditorNativeTheme
                .iconButton("help-circle", () -> "完整说明", this::toggleReference, () -> true, () -> false)
                .size(20)
                .name("guide.collapsed.help"));
        refreshCollapsed();
        return chip;
    }

    /** Compatibility with the retired canvas route; the native shell owns all guide widgets. */
    void draw(int mouseX, int mouseY) {}

    private void toggleCollapsed() {
        preferences().collapsed(!preferences().collapsed());
        refreshCollapsed();
    }

    private void refreshCollapsed() {
        boolean collapsed = preferences().collapsed();
        if (bar != null) {
            bar.height(collapsed ? 0 : 26);
            bar.setEnabled(!collapsed);
            bar.scheduleResize();
        }
        if (chip != null) chip.setEnabled(collapsed);
    }

    private void toggleReference() {
        if (reference == null) return;
        ui.host.parent()
            .deferAction(() -> {
                if (reference.isPanelOpen()) reference.closePanel();
                else reference.openPanel();
            });
    }

    private ModularPanel buildReference() {
        ModularPanel panel = new Dialog<Void>(ui.panelName("gtmb_editor_reference")).setDisablePanelsBelow(true)
            .setCloseOnOutOfBoundsClick(false)
            .size(Math.min(520, ui.host.screenWidth() - 16), Math.min(330, ui.host.screenHeight() - 16))
            .background(HologramEditorNativeTheme.PANEL);
        Flow column = Flow.col()
            .sizeRel(1f)
            .padding(9)
            .childPadding(7)
            .name("guide.reference.content");
        column.child(
            HologramEditorNativeTheme.label(() -> "操作说明与独立课程", HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(17));
        ListWidget<IWidget, ?> details = new ListWidget<>().sizeRel(1f)
            .expanded()
            .name("guide.reference.details")
            .background(IDrawable.NONE)
            .disableThemeBackground(true);
        String[] commands = { "sameType", "moveHandles", "channels", "candidates", "hatchPreset", "library" };
        String[] explanations = { "点一格选择，Ctrl 增减；点结构轮廓整选。", "轴线改单轴，平面同时改两轴；Ctrl 起拖复制。", "每个来源结构独立保存声明参数与具名信道。",
            "筛选后仍须选择具体设备，保留合法候选、朝向和 NBT。", "整机仓室配额用于实体结构，作者蓝图不支持 virtual 套用。", "点库行更新中央草稿，未保存时沿用原提示；世界 ghost 先定位并锁定。" };
        for (int i = 0; i < commands.length; i++) {
            final int index = i;
            details.child(
                HologramEditorNativeTheme.label(() -> key(commands[index]) + " · " + explanations[index])
                    .widthRel(1f)
                    .height(26)
                    .name("guide.reference." + commands[i]));
        }
        details.child(
            HologramEditorNativeTheme.label(() -> "材料核验只有一次最终确认；Esc 按面板层级返回。")
                .widthRel(1f)
                .height(26));
        column.child(details);
        String[] topics = { "projection", "blueprint", "cut", "structure", "box-fps", "box-iso", "cut-fps", "cut-iso" };
        String[] labels = { "投影教程", "蓝图教程", "剪切教程", "结构操作", "普通框选", "等距框选", "普通剪切", "等距剪切" };
        for (int row = 0; row < 2; row++) {
            Flow entries = Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(5);
            for (int col = 0; col < 4; col++) {
                final int index = row * 4 + col;
                entries.child(
                    HologramEditorNativeTheme.button(
                        () -> labels[index],
                        () -> ui.host.parent()
                            .deferAction(() -> {
                                reference.closePanel();
                                ui.tutorial.start(topics[index], true);
                            }))
                        .widthRel(1f)
                        .expanded()
                        .height(20)
                        .name("guide.lesson." + topics[index]));
            }
            column.child(entries);
        }
        column.child(
            Flow.row()
                .widthRel(1f)
                .height(20)
                .mainAxisAlignment(Alignment.MainAxis.END)
                .child(
                    HologramEditorNativeTheme.button(() -> "关闭说明", this::toggleReference)
                        .size(78, 20)));
        return panel.child(column);
    }

    String instruction() {
        if (ui.session.progress() != null) return "任务正在进行；完成后继续编辑。";
        if (!ui.session.editable()) return "等待当前操作完成，后台业务继续。";
        if (ui.review.confirmation()) return "核对精确材料与缺口，再作最终确认。";
        if (ui.processing.processingFlow() != null) return "选择自用或分享倾向，再逐步核对处理策略。";
        if (ui.config.presetDrawer()) return "按声明配额或精确搭配生成目标，保留手选和现场设备。";
        if (ui.config.hatchDrawer()) return "从本格合法候选选择具体设备。";
        if (ui.config.drawer()) return "编辑当前来源结构的声明参数，再应用。";
        if (ui.preview.inventory()) return "选择真实背包或已启用来源中的具体物品。";
        if (ui.library.libraryDrawer()) return "单击库行更新中央草稿；放置与保存使用库内入口。";
        if (ui.preview.selected() >= 0) return ui.preview.moveHandles() ? "拖轴改单轴，拖平面同时改两轴。" : "查看所选格目标，或点轮廓整选结构。";
        return ui.session.state().data.getBoolean("virtual") ? "点击对象选择，从蓝图库载入或开始空白稿。" : "控制器锚定结构，先调目标再核对材料。";
    }

    String keys() {
        if (ui.review.confirmation()) return "Esc 返回编辑 · 确认沿用材料窗口按钮";
        if (ui.config.drawer() || ui.config.hatchDrawer() || ui.config.presetDrawer()) return "Esc 关窗 · ? 完整说明";
        return "Ctrl 增减 · " + key("sameType") + " 同类 · " + key("moveHandles") + " 手柄";
    }

    private String key(String command) {
        return ui.tools.commands()
            .shortcut(command);
    }

    boolean activate(int id) {
        if (id == BASE) {
            toggleCollapsed();
            return true;
        }
        if (id == BASE + 1) {
            toggleReference();
            return true;
        }
        if (id >= BASE + 2 && id <= BASE + 9) {
            if (reference != null && reference.isPanelOpen()) reference.closePanel();
            ui.tutorial.start(
                new String[] { "projection", "blueprint", "cut", "structure", "box-fps", "box-iso", "cut-fps",
                    "cut-iso" }[id - BASE - 2],
                true);
            return true;
        }
        return false;
    }
}
