package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import org.lwjgl.input.Keyboard;

/** Internal shortcuts workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiShortcuts {

    private static final String SELECTION_HELP = "T 同种全选 · Ctrl 多选 · Shift 框选 · Ctrl+A 全选";
    private static final String GEOMETRY_HELP = "Alt 选空气 · G 开/关移动轴 · Ctrl+拖轴复制";
    private static final String VIEW_HELP = "拖动旋转 · 滚轮缩放 · WASD 平移";
    private static final String CLOSE_HELP = "Esc/E 关闭";

    private final HologramUiModules ui;

    HologramUiShortcuts(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean shortcutsDrawer;

    private int captureBinding = -1;

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
            ? GEOMETRY_HELP + "（当前" + (ui.preview.moveHandles() ? "开" : "关") + "）"
            : VIEW_HELP;
        return java.util.Arrays.asList(
            SELECTION_HELP,
            editing,
            keyBindings.label(5) + " "
                + HologramKeyBindings.LABELS[5]
                + " · "
                + keyBindings.label(6)
                + " "
                + HologramKeyBindings.LABELS[6]
                + " · "
                + CLOSE_HELP);
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
        drawRect(70, 62, 346, 250, 0xff172f36);
        ui.canvas.text("快捷键 · 点击一项后按新组合", 80, 70, 230, 0xf5e4b9);
        ui.canvas.button(114, 318, 66, 20, 17, "×", true, mx, my);
        for (int i = 0; i < HologramKeyBindings.ACTIONS.length; i++) {
            ui.canvas.button(
                120 + i,
                80,
                88 + i * 18,
                256,
                16,
                HologramKeyBindings.LABELS[i] + "："
                    + (this.captureBinding == i ? "请按键（Esc取消）" : this.keyBindings.label(i)),
                true,
                mx,
                my);
        }
        ui.canvas.button(115, 80, 220, 80, 17, "恢复默认", true, mx, my);
        ui.canvas.text("撤销 / 重做只改变规划，不回滚世界", 80, 241, 256, 0xe4b85a);
        drawRect(70, 252, 442, 296, 0xff172f36);
        ui.canvas.text("选择：" + SELECTION_HELP, 80, 259, 352, 0xb9d5d4);
        ui.canvas.text("编辑：" + GEOMETRY_HELP + " · " + CLOSE_HELP, 80, 277, 352, 0xb9d5d4);
    }

    boolean captureBindingControl(int id) {
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
                return true;
            case 114:
                this.shortcutsDrawer = false;
                this.captureBinding = -1;
                return true;
            case 115:
                this.keyBindings.defaults();
                ui.session.notice(this.keyBindings.save() ? "已恢复默认快捷键" : "快捷键保存失败");
                return true;
            default:
                return false;
        }
    }

    boolean consumeBindingKey(char c, int key) {
        if (this.captureBinding >= 0) {
            if (key == Keyboard.KEY_ESCAPE) this.captureBinding = -1;
            else if (!HologramKeyBindings.isModifier(key)) {
                if (this.keyBindings.bind(this.captureBinding, key, HologramKeyBindings.modifiers())) {
                    this.captureBinding = -1;
                    ui.session.notice(this.keyBindings.save() ? "快捷键已保存" : "快捷键保存失败");
                } else ui.session.notice("该组合已使用或保留给模式切换，请选择其他按键");
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
    }
}
