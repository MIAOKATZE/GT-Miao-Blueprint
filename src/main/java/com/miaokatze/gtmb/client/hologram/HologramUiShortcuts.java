package com.miaokatze.gtmb.client.hologram;


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
            && !ui.library.saveGuidanceOpen()
            && !ui.preview.inventory()
            && ui.tools.commands().sceneAvailable()
            && !ui.tools.modalOpen()
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
        // Native guide widgets consume idleHintLines(); the scene no longer paints UI text.
    }

    void drawShortcuts(int mx, int my) {
        // The stable native shortcut Dialog owns its bounded ListWidget.
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
                ui.tools.openShortcuts();
                return true;
            case 114:
                ui.tools.closeShortcuts();
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

    void cancelBindingCapture() { this.captureBinding = -1; }

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
