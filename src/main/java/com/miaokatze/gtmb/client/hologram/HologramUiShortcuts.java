package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import org.lwjgl.input.Keyboard;

/** Internal shortcuts workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiShortcuts {

    private static final String CLOSE_HELP = "Esc/E 关闭";
    static final int BINDING_FIRST = HologramEditorControlIds.TOOLS + 700;
    static final int BINDING_PREVIOUS = HologramEditorControlIds.TOOLS + 850;
    static final int BINDING_NEXT = HologramEditorControlIds.TOOLS + 851;
    private static final int BINDINGS_PER_PAGE = 9;

    private final HologramUiModules ui;

    HologramUiShortcuts(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean shortcutsDrawer;

    private int captureBinding = -1;
    private int bindingPage;

    private final HologramKeyBindings keyBindings = new HologramKeyBindings();

    private final HologramIdleHints idleHints = new HologramIdleHints(System.nanoTime());

    void activity() {
        idleHints.activity(System.nanoTime());
    }

    void observePointer(int x, int y) {
        idleHints.pointer(x, y, System.nanoTime());
    }

    boolean idleHintsVisible(long now) {
        return idleHints.visible(now) && !HologramBackgroundTasks.busy()
            && !ui.library.libraryDrawer()
            && !ui.library.saveGuidanceOpen()
            && !ui.preview.inventory()
            && ui.preview.previewInteractive()
            && !ui.router.textFieldFocused()
            && !ui.preview.boxSelecting()
            && ui.preview.gizmo()
                .axis() < 0
            && ui.annotations.pinnedAnnotation()
                .isEmpty()
            && !ui.session.state().cells.isEmpty();
    }

    java.util.List<String> idleHintLines() {
        String editing = ui.session.state().data.getBoolean("virtual")
            ? geometryHelp() + "（当前" + (ui.preview.moveHandles() ? "开" : "关") + "）"
            : viewHelp();
        return java.util.Arrays.asList(
            selectionHelp(),
            editing,
            ui.tools.commands()
                .shortcut("editUndo") + " 撤销规划"
                + " · "
                + ui.tools.commands()
                    .shortcut("editRedo")
                + " 重做规划"
                + " · "
                + CLOSE_HELP);
    }

    private String selectionHelp() {
        return ui.tools.commands()
            .shortcut("sameType") + " 同种全选 · Ctrl 多选 · Shift 框选 · "
            + ui.tools.commands()
                .shortcut("selectAll")
            + " 全选";
    }

    private String geometryHelp() {
        return ui.tools.commands()
            .shortcut("air") + " 选空气 · "
            + ui.tools.commands()
                .shortcut("moveHandles")
            + " 移动轴 · Ctrl+拖轴复制";
    }

    private String viewHelp() {
        return "拖动旋转 · 滚轮缩放 · " + ui.tools.commands()
            .shortcut("cameraForward")
            + "/"
            + ui.tools.commands()
                .shortcut("cameraLeft")
            + "/"
            + ui.tools.commands()
                .shortcut("cameraBack")
            + "/"
            + ui.tools.commands()
                .shortcut("cameraRight")
            + " 平移";
    }

    void drawIdleHints() {
        if (!idleHintsVisible(System.nanoTime())) return;
        int x = HologramUiCanvas.viewportX + 6;
        int y = HologramUiCanvas.viewportY + ui.preview.previewHeight() - 88;
        int width = HologramUiCanvas.viewportWidth - 12;
        ui.canvas.skin("panel", x, y, width, 62);
        ui.canvas.text("快捷键 · 点击结构轮廓可整组选中 · 操作后收起", x + 6, y + 6, width - 12, 0xffe59b);
        java.util.List<String> lines = idleHintLines();
        for (int i = 0; i < lines.size(); i++)
            ui.canvas.text(lines.get(i), x + 6, y + 20 + i * 12, width - 12, 0xb9d5d4);
    }

    void drawShortcuts(int mx, int my) {
        HologramEditorCommands commands = ui.tools.commands();
        int pages = (commands.size() + BINDINGS_PER_PAGE - 1) / BINDINGS_PER_PAGE;
        bindingPage = Math.max(0, Math.min(pages - 1, bindingPage));
        ui.canvas.skin("panel", 70, 53, 478, 253);
        ui.canvas.text("编辑器快捷键 · 点击条目后按新组合 · E/Esc保留退出", 80, 62, 432, 0xf5e4b9);
        ui.canvas.button(114, 520, 57, 20, 18, "×", true, mx, my);
        int first = bindingPage * BINDINGS_PER_PAGE;
        int end = Math.min(commands.size(), first + BINDINGS_PER_PAGE);
        for (int i = first; i < end; i++) {
            HologramEditorCommands.Command command = commands.get(i);
            ui.canvas.button(
                BINDING_FIRST + i,
                80,
                82 + (i - first) * 20,
                458,
                18,
                command.label + (command.held ? "（可按住）" : "")
                    + "："
                    + (this.captureBinding == i ? "请按键（Esc取消）" : commands.shortcut(command.name)),
                true,
                mx,
                my);
        }
        ui.canvas.button(115, 80, 267, 90, 18, "全部恢复默认", true, mx, my);
        ui.canvas.button(BINDING_PREVIOUS, 328, 267, 66, 18, "上一页", bindingPage > 0, mx, my);
        ui.canvas.text((bindingPage + 1) + " / " + pages, 405, 272, 45, 0xb9d5d4);
        ui.canvas.button(BINDING_NEXT, 468, 267, 70, 18, "下一页", bindingPage + 1 < pages, mx, my);
        ui.canvas.text("字段焦点优先 · 本编辑器按键不透传游戏 · 撤销不回滚世界", 80, 291, 458, 0xe4b85a);
    }

    boolean captureBindingControl(int id) {
        if (id >= BINDING_FIRST && id < BINDING_FIRST + ui.tools.commands()
            .size()) {
            this.captureBinding = id - BINDING_FIRST;
            return true;
        }
        // Retain the original seven IDs for existing diagnostic/control callers.
        if (id >= 120 && id < 120 + HologramKeyBindings.ACTIONS.length) {
            this.captureBinding = id - 120;
            return true;
        }
        return false;
    }

    boolean activateControl(int id) {
        switch (id) {
            case 110:
                this.shortcutsDrawer = true;
                ui.tools.closeTransient();
                return true;
            case 114:
                this.shortcutsDrawer = false;
                this.captureBinding = -1;
                return true;
            case 115:
                ui.tools.commands()
                    .defaults();
                ui.session.notice(
                    ui.tools.commands()
                        .save() ? "已恢复全部编辑器快捷键" : "快捷键保存失败");
                return true;
            case BINDING_PREVIOUS:
                bindingPage = Math.max(0, bindingPage - 1);
                return true;
            case BINDING_NEXT:
                bindingPage = Math.min(
                    (ui.tools.commands()
                        .size() - 1) / BINDINGS_PER_PAGE,
                    bindingPage + 1);
                return true;
            default:
                return false;
        }
    }

    boolean consumeBindingKey(char c, int key) {
        if (this.captureBinding >= 0) {
            if (key == Keyboard.KEY_ESCAPE) this.captureBinding = -1;
            else if (!HologramKeyBindings.isModifier(key) || ui.tools.commands()
                .get(captureBinding).held) {
                    if (ui.tools.commands()
                        .bind(this.captureBinding, key, HologramKeyBindings.modifiers())) {
                        this.captureBinding = -1;
                        ui.session.notice(
                            ui.tools.commands()
                                .save() ? "编辑器快捷键已保存" : "快捷键保存失败");
                    } else ui.session.notice("该组合已使用；E/Esc保留退出，请选择其他按键");
                }
            return true;
        }
        return false;
    }

    int captureBinding() {
        return this.captureBinding;
    }

    HologramKeyBindings keyBindings() {
        return this.keyBindings;
    }

    boolean shortcutsDrawer() {
        return this.shortcutsDrawer;
    }

    boolean shortcutsDrawer(boolean value) {
        this.shortcutsDrawer = value;
        return value;
    }

    void loadBindings() {
        ui.shortcuts.keyBindings()
            .load(new java.io.File(ui.host.minecraft().mcDataDir, "config/gtmb-hologram-keys.properties"));
        ui.tools.commands()
            .load();
    }

    String bindingTooltip(int id) {
        if (id >= BINDING_FIRST && id < BINDING_FIRST + ui.tools.commands()
            .size()) {
            HologramEditorCommands.Command command = ui.tools.commands()
                .get(id - BINDING_FIRST);
            return command.label + " · "
                + ui.tools.commands()
                    .shortcut(command.name)
                + "\n"
                + command.help
                + "\n点击后按新的组合；Esc取消录入。";
        }
        if (id == BINDING_PREVIOUS || id == BINDING_NEXT) return "翻页查看并重绑全部编辑器命令。";
        return null;
    }
}
