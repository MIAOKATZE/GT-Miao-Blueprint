package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Permanent native trees and stable local handlers, independent of scene rendering. */
final class HologramEditorNativeTools {

    private static final int BASE = HologramEditorControlIds.TOOLS;
    private static final int FLOAT_PANEL = BASE + 950, CONTEXT_PANEL = BASE + 951, KEYS_PANEL = BASE + 952,
        VIEW_PANEL = BASE + 953;
    private static final String[] RAIL = { "viewWorkspace", "selectAll", "moveHandles", "editCopy", "rotateCell",
        "inventory", "candidates", "text", "image", "editUndo", "editRedo" };
    private static final String[] FLOATING = { "viewWorkspace", "editCut", "editCopy", "editPaste", "restoreCell",
        "rotateCell", "hatchFacing", "candidates", "inventory", "hatchPreset", "channels", "structure", "overview" };
    private static final String[] SELECTED = { "editCopy", "editPaste", "restoreCell", "hatchFacing", "candidates",
        "inventory", "clearSelection" };
    private static final String[] CONTEXT = { "viewWorkspace", "editCut", "editCopy", "editPaste", "restoreCell",
        "rotateCell", "hatchFacing", "rotateTextVertical", "candidates", "inventory", "hatchPreset", "channels",
        "structure", "overview", "editUndo", "editRedo", "selectAll", "sameType", "clearSelection", "resetView",
        "floatingTools" };
    private final HologramUiModules ui;
    private ModularPanel host;
    private IPanelHandler floating, context, shortcuts, viewWorkspace;
    private int contextX, contextY;

    HologramEditorNativeTools(HologramUiModules ui) {
        this.ui = ui;
    }

    private HologramEditorCommands commands() {
        return ui.tools.commands();
    }

    void attach(ModularPanel panel) {
        if (host == panel) return;
        if (host != null) throw new IllegalStateException("Native tools already attached to another graph");
        host = panel;
        floating = IPanelHandler.simple(host, (parent, player) -> buildFloating(), true);
        context = IPanelHandler.simple(host, (parent, player) -> buildContext(), true);
        shortcuts = IPanelHandler.simple(host, (parent, player) -> buildShortcuts(), true);
        viewWorkspace = IPanelHandler.simple(host, (parent, player) -> buildViewWorkspace(), true);
    }

    IWidget buildModeBar() {
        Flow row = Flow.row()
            .name("editor_native_mode_bar")
            .widthRel(1f)
            .height(24)
            .childPadding(3);
        row.child(
            local(
                HologramEditorTools.PROJECTION_MODE,
                () -> "投影",
                null,
                () -> !ui.session.state().data.getBoolean("virtual"),
                () -> !ui.session.state().data.getBoolean("virtual"),
                () -> "返回当前真实控制器规划；没有真实会话时不可切入。",
                false).size(42, 24));
        row.child(
            local(
                HologramEditorTools.BLUEPRINT_MODE,
                () -> "蓝图",
                null,
                () -> true,
                () -> ui.session.state().data.getBoolean("virtual"),
                () -> "沿原作者编辑 / 蓝图库入口，不创建世界或新会话。",
                false).size(42, 24));
        row.child(
            local(
                HologramEditorTools.CUT_MODE,
                () -> "剪切",
                null,
                () -> ui.session.editable(),
                () -> false,
                () -> "打开真实剪切存储；不自动执行世界操作。",
                false).size(42, 24));
        row.child(
            local(
                HologramEditorTools.LIBRARY_BUTTON,
                () -> "蓝图库",
                "library",
                () -> true,
                () -> ui.shell.built() ? ui.shell.libraryPageSelected() && ui.shell.dockExpanded()
                    : ui.library.libraryDrawer(),
                () -> "打开真实蓝图库。",
                true).size(28, 24));
        row.child(
            action(
                HologramEditorControlIds.TUTORIAL,
                () -> "教程",
                "tutorial",
                () -> true,
                () -> false,
                () -> "打开当前上下文的原生交互教程。",
                true,
                () -> ui.router.activate(HologramEditorControlIds.TUTORIAL)).size(28, 24));
        row.child(
            action(
                HologramEditorControlIds.GUIDE,
                () -> "帮助",
                "help",
                () -> true,
                () -> false,
                () -> "展开上下文操作说明。",
                true,
                () -> ui.router.activate(HologramEditorControlIds.GUIDE)).size(28, 24));
        return row;
    }

    IWidget buildToolRail() {
        Flow rail = Flow.column()
            .name("editor_native_tool_rail")
            .width(34)
            .heightRel(1f)
            .padding(2)
            .childPadding(3);
        rail.child(
            local(
                HologramEditorTools.TOGGLE_FLOATING,
                () -> "",
                "detach",
                () -> true,
                this::floatingOpen,
                () -> commands().help("floatingTools"),
                true).size(28));
        ListWidget<IWidget, ?> list = new ListWidget<>().name("editor_native_tool_rail_list")
            .sizeRel(1f)
            .expanded();
        for (String name : RAIL) list.child(command(0, name, true).size(28));
        return rail.child(list);
    }

    IWidget buildSelectedBar() {
        ListWidget<IWidget, ?> row = new ListWidget<>().name("editor_native_selected_actions")
            .scrollDirection(GuiAxis.X)
            .widthRel(1f)
            .height(33)
            .setEnabledIf(ignored -> ui.session.selectedCell() != null);
        row.setEnabled(ui.session.selectedCell() != null);
        for (String name : SELECTED) row.child(command(2, name, true).size(26));
        return row;
    }

    private ButtonWidget<?> command(int surface, String name, boolean iconOnly) {
        HologramEditorCommands.Command command = commands().get(name);
        int id = BASE + surface * 100 + commands().index(name);
        BooleanSupplier enabled = () -> commands().available(name);
        ButtonWidget<?> button = action(
            id,
            () -> iconOnly ? "" : commandLabel(name) + "  " + commands().shortcut(name),
            command.icon,
            enabled,
            () -> "moveHandles".equals(name) && ui.preview.moveHandles(),
            () -> commands().help(name),
            iconOnly,
            () -> execute(name));
        if (command.control >= 0)
            HologramEditorNativeTheme.guided(button, () -> ui.canvas.controlAccent(command.control), enabled);
        return button;
    }

    private String commandLabel(String name) {
        int control = commands().get(name).control;
        if (control >= 10 && control <= 15) {
            String label = ui.preview.viewControlLabel(control);
            return control == 10 ? "显示：" + label : label;
        }
        if (control == 196) return ui.bindings.selectedNetworkGroup()
            .isEmpty()
                ? "量子绑定 · " + ui.bindings.networkGroups()
                    .tagCount()
                : "网络组 " + ui.bindings.selectedNetworkGroup();
        return commands().get(name).label;
    }

    private ButtonWidget<?> local(int id, Supplier<String> label, String icon, BooleanSupplier enabled,
        BooleanSupplier selected, Supplier<String> tooltip, boolean iconOnly) {
        ButtonWidget<?> button = action(
            id,
            label,
            icon,
            enabled,
            selected,
            tooltip,
            iconOnly,
            () -> ui.tools.activateControl(id));
        if (id == HologramEditorTools.LIBRARY_BUTTON)
            HologramEditorNativeTheme.guided(button, () -> ui.canvas.controlAccent(149), enabled);
        return button;
    }

    private ButtonWidget<?> action(int id, Supplier<String> label, String icon, BooleanSupplier enabled,
        BooleanSupplier selected, Supplier<String> tooltip, boolean iconOnly, Runnable action) {
        // Capturing a key is local state only and must be ready for the next input event. All
        // actions which can replace UI content run after the current MUI dispatch has returned.
        boolean binding = id >= HologramUiShortcuts.BINDING_FIRST
            && id < HologramUiShortcuts.BINDING_FIRST + commands().size();
        Runnable invoke = binding ? action
            : () -> ui.host.parent()
                .deferAction(action);
        ButtonWidget<?> button = iconOnly
            ? HologramEditorNativeTheme.iconButton(icon, tooltip, invoke, enabled, selected)
            : HologramEditorNativeTheme.button(label, icon, invoke, enabled, selected, false)
                .tooltipBuilder(t -> t.addLine(tooltip.get()))
                .tooltipAutoUpdate(true);
        button.name("editor_native_tool_" + id);
        ui.shell.registerControl(id, button, label, enabled);
        return button;
    }

    void execute(String name) {
        ui.host.parent()
            .deferAction(() -> {
                if (contextOpen()) context.closePanel();
                if (viewWorkspaceOpen() && ("shortcuts".equals(name) || "networkBindings".equals(name)
                    || "aeMarking".equals(name)
                    || "areaSelection".equals(name)
                    || "clearWorkspace".equals(name))) viewWorkspace.closePanel();
                commands().execute(name);
            });
    }

    private ModularPanel buildFloating() {
        ToolDialog panel = new ToolDialog("gtmb_editor_floating_tools", false);
        panel.setDraggable(true)
            .setDisablePanelsBelow(false)
            .setCloseOnOutOfBoundsClick(false);
        int width = Math.min(
            184,
            Math.max(
                120,
                host.getScreen()
                    .getScreenArea().width - 16));
        int height = Math.min(
            240,
            Math.max(
                120,
                host.getScreen()
                    .getScreenArea().height - 20));
        panel.size(width, height)
            .pos(
                Math.max(
                    0,
                    Math.min(
                        72,
                        host.getScreen()
                            .getScreenArea().width - width)),
                Math.max(
                    0,
                    Math.min(
                        80,
                        host.getScreen()
                            .getScreenArea().height - height)))
            .background(HologramEditorNativeTheme.PANEL);
        Flow body = Flow.column()
            .sizeRel(1f)
            .padding(7)
            .childPadding(5);
        body.child(
            HologramEditorNativeTheme.label(() -> "编辑工具 · 拖动空白处移动")
                .widthRel(1f)
                .height(18));
        ListWidget<IWidget, ?> rows = new ListWidget<>().name("editor_native_floating_actions")
            .sizeRel(1f)
            .expanded();
        for (String name : FLOATING) rows.child(
            command(1, name, false).widthRel(1f)
                .height(26));
        body.child(rows);
        body.child(
            local(
                HologramEditorTools.COLLAPSE_FLOATING,
                () -> "收起工具",
                "collapse-down",
                () -> true,
                () -> false,
                () -> commands().help("floatingTools"),
                false).widthRel(1f)
                    .height(24));
        panel.child(body);
        ui.shell.registerControl(FLOAT_PANEL, panel, () -> "浮动工具", this::floatingOpen);
        return panel;
    }

    private ModularPanel buildContext() {
        ToolDialog panel = new ToolDialog("gtmb_editor_context_tools", true);
        panel.setDisablePanelsBelow(true)
            .setCloseOnOutOfBoundsClick(true)
            .setDraggable(false);
        int width = Math.min(
            248,
            Math.max(
                180,
                host.getScreen()
                    .getScreenArea().width - 16));
        int height = Math.min(
            276,
            Math.max(
                120,
                host.getScreen()
                    .getScreenArea().height - 20));
        panel.size(width, height)
            .pos(
                Math.max(
                    0,
                    Math.min(
                        contextX,
                        host.getScreen()
                            .getScreenArea().width - width)),
                Math.max(
                    0,
                    Math.min(
                        contextY,
                        host.getScreen()
                            .getScreenArea().height - height)))
            .background(HologramEditorNativeTheme.PANEL);
        Flow body = Flow.column()
            .sizeRel(1f)
            .padding(7)
            .childPadding(4);
        body.child(
            HologramEditorNativeTheme.label(() -> "当前选择")
                .widthRel(1f)
                .height(16));
        ListWidget<IWidget, ?> entries = new ListWidget<>().name("editor_native_context_actions")
            .sizeRel(1f)
            .expanded();
        for (String name : CONTEXT) {
            Flow item = Flow.row()
                .widthRel(1f)
                .height(25)
                .setEnabledIf(ignored -> commands().available(name));
            item.setEnabled(commands().available(name));
            item.child(
                command(3, name, false).widthRel(1f)
                    .height(25));
            entries.child(item);
        }
        body.child(entries);
        body.child(
            local(
                HologramEditorTools.MENU_CLOSE,
                () -> "返回编辑",
                "close",
                () -> true,
                () -> false,
                () -> "关闭此菜单，保留选择 · Esc / E",
                false).widthRel(1f)
                    .height(23));
        panel.child(body);
        ui.shell.registerControl(CONTEXT_PANEL, panel, () -> "右键工具", this::contextOpen);
        return panel;
    }

    private ModularPanel buildShortcuts() {
        ToolDialog panel = new ToolDialog("gtmb_editor_shortcut_settings", true);
        panel.setDisablePanelsBelow(true)
            .setCloseOnOutOfBoundsClick(false)
            .setDraggable(true);
        panel.size(
            Math.min(
                462,
                Math.max(
                    250,
                    host.getScreen()
                        .getScreenArea().width - 20)),
            Math.min(
                300,
                Math.max(
                    180,
                    host.getScreen()
                        .getScreenArea().height - 20)))
            .center()
            .background(HologramEditorNativeTheme.PANEL);
        Flow body = Flow.column()
            .sizeRel(1f)
            .padding(8)
            .childPadding(5);
        body.child(
            HologramEditorNativeTheme.label(() -> "编辑器快捷键 · 点击条目后按新组合")
                .widthRel(1f)
                .height(18));
        ListWidget<IWidget, ?> rows = new ListWidget<>().name("editor_native_shortcut_list")
            .sizeRel(1f)
            .expanded();
        for (int i = 0; i < commands().size(); i++) {
            final int index = i;
            HologramEditorCommands.Command command = commands().get(i);
            int id = HologramUiShortcuts.BINDING_FIRST + i;
            rows.child(
                action(
                    id,
                    () -> command.label + "："
                        + (ui.shortcuts.captureBinding() == index ? "请按键（Esc取消）" : commands().shortcut(command.name)),
                    command.icon,
                    () -> true,
                    () -> ui.shortcuts.captureBinding() == index,
                    () -> ui.shortcuts.bindingTooltip(id),
                    false,
                    () -> ui.shortcuts.captureBindingControl(id)).widthRel(1f)
                        .height(27));
        }
        body.child(rows);
        body.child(
            HologramEditorNativeTheme.label(() -> "字段焦点优先 · E/Esc保留退出 · 撤销不回滚世界")
                .widthRel(1f)
                .height(16));
        Flow footer = Flow.row()
            .widthRel(1f)
            .height(25)
            .childPadding(5);
        footer.child(
            action(
                BASE + 960,
                () -> "恢复默认",
                "reset-view",
                () -> true,
                () -> false,
                () -> "恢复全部编辑器命令的默认键位。",
                false,
                () -> ui.shortcuts.activateControl(115)).width(105)
                    .height(25));
        footer.child(
            action(
                BASE + 961,
                () -> "返回编辑",
                "close",
                () -> true,
                () -> false,
                () -> "关闭键位编辑窗口。",
                false,
                this::closeShortcuts).width(105)
                    .height(25));
        body.child(footer);
        panel.child(body);
        panel.onCloseAction(() -> {
            ui.shortcuts.shortcutsDrawer(false);
            ui.shortcuts.cancelBindingCapture();
        });
        ui.shell.registerControl(KEYS_PANEL, panel, () -> "快捷键", this::shortcutsOpen);
        return panel;
    }

    private ModularPanel buildViewWorkspace() {
        ToolDialog panel = new ToolDialog("gtmb_editor_view_workspace", true);
        panel.setDisablePanelsBelow(true)
            .setCloseOnOutOfBoundsClick(false)
            .setDraggable(true);
        panel.size(
            Math.min(
                324,
                Math.max(
                    220,
                    host.getScreen()
                        .getScreenArea().width - 20)),
            Math.min(
                306,
                Math.max(
                    170,
                    host.getScreen()
                        .getScreenArea().height - 20)))
            .center()
            .background(HologramEditorNativeTheme.PANEL);
        Flow body = Flow.column()
            .sizeRel(1f)
            .padding(8)
            .childPadding(5);
        body.child(
            HologramEditorNativeTheme.label(() -> "视图与工作区")
                .widthRel(1f)
                .height(18));
        ListWidget<IWidget, ?> rows = new ListWidget<>().name("editor_native_view_workspace_list")
            .sizeRel(1f)
            .expanded();
        rows.child(
            HologramEditorNativeTheme.label(() -> "显示与切片")
                .widthRel(1f)
                .height(19));
        for (String name : new String[] { "viewCycle", "allLayers", "layerDown", "layerUp", "resetView", "frontView",
            "rightView", "topView", "worldPreview" })
            rows.child(
                command(4, name, false).widthRel(1f)
                    .height(27));
        rows.child(
            HologramEditorNativeTheme.label(() -> "现有工作区操作")
                .widthRel(1f)
                .height(21));
        rows.child(
            command(4, "areaSelection", false).widthRel(1f)
                .height(27));
        rows.child(
            command(4, "clearWorkspace", false).widthRel(1f)
                .height(27));
        Flow networks = Flow.row()
            .widthRel(1f)
            .height(27)
            .setEnabledIf(
                ignored -> ui.bindings.networkGroups()
                    .tagCount() > 0);
        networks.setEnabled(
            ui.bindings.networkGroups()
                .tagCount() > 0);
        networks.child(
            command(4, "networkBindings", false).widthRel(1f)
                .height(27));
        rows.child(networks);
        rows.child(
            HologramEditorNativeTheme.label(() -> "当前蓝图没有已登记的量子类群")
                .widthRel(1f)
                .height(21)
                .setEnabledIf(
                    ignored -> ui.bindings.networkGroups()
                        .tagCount() == 0));
        rows.child(
            command(4, "aeMarking", false).widthRel(1f)
                .height(27));
        rows.child(
            command(4, "shortcuts", false).widthRel(1f)
                .height(27));
        body.child(rows);
        body.child(
            action(
                BASE + 962,
                () -> "返回编辑",
                "close",
                () -> true,
                () -> false,
                () -> "关闭窗口，保持当前选择。",
                false,
                this::closeViewWorkspace).widthRel(1f)
                    .height(24));
        panel.child(body);
        ui.shell.registerControl(VIEW_PANEL, panel, () -> "视图与工作区", this::viewWorkspaceOpen);
        return panel;
    }

    private final class ToolDialog extends Dialog<Void> {

        private final boolean modal;

        ToolDialog(String name, boolean modal) {
            super(ui.panelName(name));
            this.modal = modal;
        }

        @Override
        public boolean onKeyPressed(char character, int key) {
            if (shortcutsOpen() && ui.shortcuts.consumeBindingKey(character, key)) return true;
            if (super.onKeyPressed(character, key)) return true;
            if ((key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) && !ui.shell.fieldFocused()) {
                ui.host.parent()
                    .deferAction(this::closeIfOpen);
                return true;
            }
            return modal;
        }
    }

    void toggleFloating() {
        if (floatingOpen()) closeFloating();
        else open(floating, false);
    }

    void contextAtPointer() {
        if (host == null || !commands().commandContextAvailable()) return;
        contextX = host.getContext()
            .getAbsMouseX();
        contextY = host.getContext()
            .getAbsMouseY();
        open(context, true);
    }

    void openShortcuts() {
        ui.shortcuts.shortcutsDrawer(true);
        open(shortcuts, true);
    }

    void openViewWorkspace() {
        open(viewWorkspace, true);
    }

    private void open(IPanelHandler handler, boolean refresh) {
        if (handler == null) {
            ui.session.notice("原生工具尚未挂接编辑器面板");
            return;
        }
        ui.host.parent()
            .deferAction(() -> {
                if (handler.isPanelOpen()) return;
                if (refresh) handler.deleteCachedPanel();
                handler.openPanel();
            });
    }

    void closeFloating() {
        close(floating);
    }

    void closeContext() {
        close(context);
    }

    void closeShortcuts() {
        close(shortcuts);
    }

    void closeViewWorkspace() {
        close(viewWorkspace);
    }

    private void close(IPanelHandler handler) {
        if (handler != null && handler.isPanelOpen()) ui.host.parent()
            .deferAction(handler::closePanel);
    }

    private boolean floatingOpen() {
        return floating != null && floating.isPanelOpen();
    }

    private boolean contextOpen() {
        return context != null && context.isPanelOpen();
    }

    private boolean shortcutsOpen() {
        return shortcuts != null && shortcuts.isPanelOpen();
    }

    private boolean viewWorkspaceOpen() {
        return viewWorkspace != null && viewWorkspace.isPanelOpen();
    }

    boolean modalOpen() {
        return contextOpen() || shortcutsOpen() || viewWorkspaceOpen();
    }

    boolean pointerCaptured() {
        return host != null && host.getContext()
            .hasDraggable();
    }

    boolean consumeDialogKey(char character, int key) {
        if (!modalOpen()) return false;
        if (shortcutsOpen() && ui.shortcuts.consumeBindingKey(character, key)) return true;
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) {
            if (contextOpen()) closeContext();
            else if (viewWorkspaceOpen()) closeViewWorkspace();
            else closeShortcuts();
        }
        return true;
    }

    boolean blocksGuiPoint(int guiX, int guiY) {
        if (modalOpen() || pointerCaptured()) return true;
        if (!floatingOpen()) return false;
        int[] bounds = ui.shell.controlBounds(FLOAT_PANEL);
        return bounds != null && guiX >= bounds[0]
            && guiY >= bounds[1]
            && guiX < bounds[0] + bounds[2]
            && guiY < bounds[1] + bounds[3];
    }
}
