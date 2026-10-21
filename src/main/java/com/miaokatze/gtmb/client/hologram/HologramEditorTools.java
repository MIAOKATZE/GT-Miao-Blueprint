package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Root-space editor tools. All target edits enter the existing gated command/router chain. */
final class HologramEditorTools {

    static final int BASE = HologramEditorControlIds.TOOLS;
    static final int TOGGLE_FLOATING = BASE + 900, COLLAPSE_FLOATING = BASE + 901;
    static final int MENU_CLOSE = BASE + 902, MENU_PREVIOUS = BASE + 903, MENU_NEXT = BASE + 904;
    static final int PROJECTION_MODE = BASE + 910, BLUEPRINT_MODE = BASE + 911, CUT_MODE = BASE + 912;
    private static final int ICON = 24, BUTTON = 28, GAP = 3, MENU_WIDTH = 218, MENU_ROW = 25;
    private static final String[] RAIL = { "selectAll", "moveHandles", "editCopy", "rotateCell", "inventory",
        "candidates", "text", "image", "editUndo", "editRedo" };
    private static final String[] FLOATING = { "editCut", "editCopy", "editPaste", "restoreCell", "rotateCell",
        "hatchFacing", "candidates", "inventory", "hatchPreset", "channels", "structure", "overview" };
    private static final String[] SELECTED = { "editCopy", "editPaste", "restoreCell", "hatchFacing", "candidates",
        "inventory", "clearSelection" };
    private static final String[] CONTEXT = { "editCut", "editCopy", "editPaste", "restoreCell", "rotateCell",
        "hatchFacing", "rotateTextVertical", "candidates", "inventory", "hatchPreset", "channels", "structure",
        "overview", "editUndo", "editRedo", "selectAll", "sameType", "clearSelection", "resetView", "floatingTools" };

    private final HologramUiModules ui;
    private final HologramEditorCommands commands;
    private final Map<Integer, ToolButton> buttons = new HashMap<>();
    private final List<Bounds> surfaces = new ArrayList<>();
    private boolean floating, menuOpen, pointerCaptured, dragging;
    private int floatingX = -1, floatingY = -1, grabX, grabY, menuX, menuY, menuPage;
    private Bounds floatingHeader, menuBounds;

    private static final class Bounds {

        final int x, y, width, height;

        Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }

    private static final class ToolButton {

        final int id;
        final String command, label, icon, help;
        final Bounds bounds;
        final boolean iconOnly;

        ToolButton(int id, String command, String label, String icon, String help, Bounds bounds, boolean iconOnly) {
            this.id = id;
            this.command = command;
            this.label = label;
            this.icon = icon;
            this.help = help;
            this.bounds = bounds;
            this.iconOnly = iconOnly;
        }
    }

    HologramEditorTools(HologramUiModules ui) {
        this.ui = ui;
        commands = new HologramEditorCommands(ui);
    }

    HologramEditorCommands commands() {
        return commands;
    }

    private boolean visible() {
        return !HologramBackgroundTasks.busy() && !ui.review.materialDialogOpen()
            && !ui.library.saveGuidanceOpen()
            && ui.preview.previewInteractive()
            && !ui.preview.inventory()
            && !ui.bindings.networkDrawer()
            && !ui.library.libraryAdminDrawer();
    }

    void draw(int rootMouseX, int rootMouseY) {
        buttons.clear();
        surfaces.clear();
        floatingHeader = null;
        if (!visible()) {
            closeTransient();
            return;
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try (HologramUiCanvas.Space ignored = ui.canvas.space(CanvasTransform.identity())) {
            // These original entry points are now represented by the mode bar and tool commands.
            // Keep the other original header actions (area, AE, network, clear, channels) intact.
            ui.canvas.controls()
                .removeIf(
                    control -> control.y < 54 && (control.id == 108 || control.id == 109
                        || control.id == 110
                        || control.id == 331
                        || control.id == 332
                        || control.id == 148
                        || control.id == 149));
            ui.canvas.controls()
                .removeIf(control -> control.id == 147);
            HologramEditorTheme.PANEL.draw(8f, 29, 375, 25);
            drawModes(rootMouseX, rootMouseY);
            if (!ui.library.libraryDrawer() && !ui.library.browsePreviewActive()) {
                drawRail(rootMouseX, rootMouseY);
                drawSelection(rootMouseX, rootMouseY);
                if (floating) drawFloating(rootMouseX, rootMouseY);
            }
            if (menuOpen) {
                // The context menu owns the whole press while open, including empty panel space.
                // Remove underlying native targets as well as blocking scene picking.
                ui.canvas.controls()
                    .clear();
                buttons.clear();
                drawMenu(rootMouseX, rootMouseY);
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void drawModes(int mx, int my) {
        ViewportCoordinates viewport = ui.canvas.viewport();
        int x = (int) viewport.x() + 7, y = 31;
        Bounds modeBar = new Bounds(x, y, 367, 25);
        surfaces.add(modeBar);
        boolean controller = !ui.session.state().data.getBoolean("virtual");
        localButton(
            PROJECTION_MODE,
            x,
            y,
            82,
            25,
            "控制器投影",
            "world",
            !ui.library.libraryDrawer() && controller,
            controller,
            "只返回当前真实控制器工作台；不会制造控制器或新会话。",
            false,
            mx,
            my);
        localButton(
            BLUEPRINT_MODE,
            x + 85,
            y,
            80,
            25,
            "蓝图",
            "library",
            ui.library.libraryDrawer() || !controller,
            true,
            "沿原蓝图库入口浏览 / 编辑蓝图。",
            false,
            mx,
            my);
        localButton(
            HologramEditorControlIds.TUTORIAL,
            x + 168,
            y,
            60,
            25,
            "教程",
            "tutorial",
            false,
            true,
            "重播当前标签页的隔离教程；也可从问号说明选择其他课程。",
            false,
            mx,
            my);
        localButton(
            CUT_MODE,
            x + 231,
            y,
            80,
            25,
            "剪切存储",
            "storage",
            false,
            ui.session.editable(),
            "打开真实剪切存储目录；仅浏览，不自动框选、剪切或放置。",
            false,
            mx,
            my);
        commandButton(0, "shortcuts", x + 315, y, 45, 25, true, mx, my);
    }

    private void drawRail(int mx, int my) {
        ViewportCoordinates viewport = ui.canvas.viewport();
        int x = (int) viewport.x() + 7, y = (int) viewport.y() + 35;
        int bottom = (int) (viewport.y() + viewport.height()) - 7;
        int available = Math.max(1, (bottom - y - BUTTON - GAP) / (BUTTON + GAP));
        int drawn = 0;
        List<String> applicable = applicable(RAIL);
        for (String name : applicable) {
            if (drawn >= available) break;
            commandButton(0, name, x, y + drawn * (BUTTON + GAP), BUTTON, BUTTON, true, mx, my);
            drawn++;
        }
        int toggleY = y + drawn * (BUTTON + GAP);
        localButton(
            TOGGLE_FLOATING,
            x,
            toggleY,
            BUTTON,
            BUTTON,
            "",
            "detach",
            floating,
            true,
            commands.help("floatingTools"),
            true,
            mx,
            my);
        surfaces.add(new Bounds(x - 2, y - 2, BUTTON + 4, toggleY - y + BUTTON + 4));
    }

    private void drawSelection(int mx, int my) {
        if (ui.session.selectedCell() == null || ui.textBoxes.active()) return;
        List<String> applicable = applicable(SELECTED);
        if (applicable.isEmpty()) return;
        ViewportCoordinates viewport = ui.canvas.viewport();
        int count = Math.min(applicable.size(), Math.max(1, ((int) viewport.width() - 92) / (BUTTON + GAP)));
        int x = (int) viewport.x() + 45, y = (int) (viewport.y() + viewport.height()) - BUTTON - 9;
        Bounds strip = new Bounds(x - 3, y - 3, count * (BUTTON + GAP) + 3, BUTTON + 6);
        surfaces.add(strip);
        HologramEditorTheme.PANEL.draw((float) strip.x, strip.y, strip.width, strip.height);
        for (int i = 0; i < count; i++)
            commandButton(2, applicable.get(i), x + i * (BUTTON + GAP), y, BUTTON, BUTTON, true, mx, my);
    }

    private void drawFloating(int mx, int my) {
        List<String> applicable = applicable(FLOATING);
        int columns = 4, width = columns * (BUTTON + GAP) + 9;
        int rows = Math.max(1, (applicable.size() + columns - 1) / columns);
        int height = 25 + rows * (BUTTON + GAP) + 5;
        if (floatingX < 0) {
            floatingX = (int) ui.canvas.viewport()
                .x() + 48;
            floatingY = (int) ui.canvas.viewport()
                .y() + 38;
        }
        floatingX = clamp(
            floatingX,
            0,
            ui.canvas.layout()
                .width() - width);
        floatingY = clamp(
            floatingY,
            54,
            ui.canvas.layout()
                .height() - height - 32);
        Bounds body = new Bounds(floatingX, floatingY, width, height);
        surfaces.add(body);
        HologramEditorTheme.PANEL.draw((float) body.x, body.y, body.width, body.height);
        floatingHeader = new Bounds(floatingX, floatingY, width - 25, 23);
        ui.canvas.text("编辑工具 · 拖动", floatingX + 6, floatingY + 7, width - 31, HologramEditorTheme.TEXT);
        localButton(
            COLLAPSE_FLOATING,
            floatingX + width - 24,
            floatingY + 2,
            22,
            21,
            "",
            "collapse-down",
            false,
            true,
            commands.help("floatingTools"),
            true,
            mx,
            my);
        for (int i = 0; i < applicable.size(); i++) commandButton(
            1,
            applicable.get(i),
            floatingX + 6 + i % columns * (BUTTON + GAP),
            floatingY + 25 + i / columns * (BUTTON + GAP),
            BUTTON,
            BUTTON,
            true,
            mx,
            my);
        if (applicable.isEmpty())
            ui.canvas.text("当前无可用选格操作", floatingX + 6, floatingY + 32, width - 12, HologramEditorTheme.MUTED);
    }

    private void drawMenu(int mx, int my) {
        List<String> entries = applicable(CONTEXT);
        int rows = Math.max(
            1,
            Math.min(
                8,
                (ui.canvas.layout()
                    .height() - 103) / MENU_ROW));
        int pages = Math.max(1, (entries.size() + rows - 1) / rows);
        menuPage = clamp(menuPage, 0, pages - 1);
        int first = menuPage * rows, count = Math.min(rows, Math.max(0, entries.size() - first));
        int height = 29 + Math.max(1, count) * MENU_ROW + (pages > 1 ? 26 : 5);
        menuX = clamp(
            menuX,
            0,
            ui.canvas.layout()
                .width() - MENU_WIDTH);
        menuY = clamp(
            menuY,
            54,
            ui.canvas.layout()
                .height() - height - 25);
        menuBounds = new Bounds(menuX, menuY, MENU_WIDTH, height);
        HologramEditorTheme.PANEL.draw((float) menuX, menuY, MENU_WIDTH, height);
        ui.canvas.text("当前选择 · 右键操作", menuX + 7, menuY + 9, MENU_WIDTH - 33, HologramEditorTheme.TEXT);
        localButton(
            MENU_CLOSE,
            menuX + MENU_WIDTH - 24,
            menuY + 3,
            21,
            21,
            "",
            "close",
            false,
            true,
            "关闭右键菜单 · Esc / E",
            true,
            mx,
            my);
        for (int i = 0; i < count; i++) commandButton(
            3,
            entries.get(first + i),
            menuX + 5,
            menuY + 28 + i * MENU_ROW,
            MENU_WIDTH - 10,
            MENU_ROW - 2,
            false,
            mx,
            my);
        if (count == 0) ui.canvas.text("当前没有可用操作", menuX + 9, menuY + 37, MENU_WIDTH - 18, HologramEditorTheme.MUTED);
        if (pages > 1) {
            int y = menuY + height - 25;
            localButton(
                MENU_PREVIOUS,
                menuX + 5,
                y,
                52,
                21,
                "上一页",
                "back",
                false,
                menuPage > 0,
                "滚轮或点击翻页；不会缩放底下的画布。",
                false,
                mx,
                my);
            localButton(
                MENU_NEXT,
                menuX + MENU_WIDTH - 57,
                y,
                52,
                21,
                "下一页",
                "collapse-right",
                false,
                menuPage < pages - 1,
                "滚轮或点击翻页；不会缩放底下的画布。",
                false,
                mx,
                my);
            ui.canvas.text((menuPage + 1) + " / " + pages, menuX + 84, y + 6, 48, HologramEditorTheme.MUTED);
        }
    }

    private List<String> applicable(String[] names) {
        List<String> result = new ArrayList<>();
        for (String name : names) if (commands.available(name)) result.add(name);
        return result;
    }

    private void commandButton(int surface, String name, int x, int y, int width, int height, boolean iconOnly, int mx,
        int my) {
        HologramEditorCommands.Command command = commands.get(name);
        if (command == null) return;
        int id = BASE + surface * 100 + commands.index(name);
        String label = iconOnly ? "" : command.label + "  " + commands.shortcut(name);
        ToolButton button = new ToolButton(
            id,
            name,
            label,
            command.icon,
            commands.help(name),
            new Bounds(x, y, width, height),
            iconOnly);
        buttons.put(id, button);
        register(button, commands.available(name), name.equals("moveHandles") && ui.preview.moveHandles(), mx, my);
    }

    private void localButton(int id, int x, int y, int width, int height, String label, String icon, boolean selected,
        boolean enabled, String help, boolean iconOnly, int mx, int my) {
        ToolButton button = new ToolButton(id, "", label, icon, help, new Bounds(x, y, width, height), iconOnly);
        buttons.put(id, button);
        register(button, enabled, selected, mx, my);
    }

    private void register(ToolButton button, boolean enabled, boolean selected, int mx, int my) {
        Bounds b = button.bounds;
        boolean hover = b.contains(mx, my);
        // Scene drawing and native hit targets use exactly the same root-space rectangle.
        HologramEditorTheme.button(enabled, selected, hover)
            .draw((float) b.x, b.y, b.width, b.height);
        HologramUiCanvas.Control control = new HologramUiCanvas.Control(
            button.id,
            b.x,
            b.y,
            b.width,
            b.height,
            enabled);
        control.label = button.label;
        control.sceneTooltip = false;
        ui.canvas.addControl(control);
        if (button.iconOnly) HologramEditorIcons.draw(
            button.icon,
            b.x + (b.width - ICON) / 2,
            b.y + (b.height - ICON) / 2,
            ICON,
            true,
            enabled,
            selected,
            hover);
        else ui.canvas.text(
            button.label,
            b.x + 5,
            b.y + Math.max(3, (b.height - 8) / 2),
            b.width - 10,
            HologramEditorTheme.BUTTON_TEXT);
        if (hover) ui.canvas.tooltip(Arrays.asList(button.help.split("\n")));
    }

    String tooltip(int id) {
        String binding = ui.shortcuts.bindingTooltip(id);
        if (binding != null) return binding;
        ToolButton button = buttons.get(id);
        if (button != null) return button.command.isEmpty() ? button.help : commands.help(button.command);
        for (int i = 0; i < commands.size(); i++) {
            HologramEditorCommands.Command command = commands.get(i);
            if (command.control >= 0 && command.control == id) return commands.help(command.name);
        }
        return null;
    }

    String icon(int id) {
        ToolButton button = buttons.get(id);
        return button == null ? null : button.icon;
    }

    boolean iconOnly(int id) {
        ToolButton button = buttons.get(id);
        return button != null && button.iconOnly;
    }

    boolean activateControl(int id) {
        if (id < BASE || id >= BASE + 1000) return false;
        if (ui.shortcuts.captureBindingControl(id) || ui.shortcuts.activateControl(id)) return true;
        ToolButton button = buttons.get(id);
        if (!visible() || button == null) return true;
        if (!button.command.isEmpty()) {
            // Recheck the live snapshot. A native widget can outlive its last rendered eligibility.
            if (commands.available(button.command)) {
                menuOpen = false;
                commands.execute(button.command);
            } else ui.session.notice(commands.reason(button.command));
            return true;
        }
        switch (id) {
            case TOGGLE_FLOATING -> toggleFloating();
            case COLLAPSE_FLOATING -> {
                floating = false;
                dragging = false;
            }
            case MENU_CLOSE -> menuOpen = false;
            case MENU_PREVIOUS -> menuPage = Math.max(0, menuPage - 1);
            case MENU_NEXT -> menuPage++;
            case PROJECTION_MODE -> {
                if (!ui.session.state().data.getBoolean("virtual")) {
                    closeTransient();
                    ui.router.activate(148);
                } else ui.session.notice("当前没有真实控制器会话；请在世界中打开控制器投影仪");
            }
            case BLUEPRINT_MODE -> {
                closeTransient();
                ui.router.activate(149);
            }
            case CUT_MODE -> {
                if (!ui.session.editable()) {
                    ui.session.notice(ui.session.editingReason());
                    break;
                }
                closeTransient();
                ui.memory.rememberUi();
                ui.host.parent()
                    .deferAction(() -> {
                        if (ui.host.minecraft().currentScreen != ui.host.parent() || !ui.session.editable()) return;
                        HologramCutClient.INSTANCE.refreshDirectory();
                        new HologramCutStorageScreen().openFrom(ui.host.parent());
                    });
            }
            default -> { /* Unknown editor-local IDs never fall through as server candidate indices. */ }
        }
        return true;
    }

    void toggleFloating() {
        floating = !floating;
        dragging = false;
        menuOpen = false;
        ui.session.notice(
            floating ? "浮动工具已唤出，可拖动标题；再次按 " + commands.shortcut("floatingTools") + " 收起"
                : "浮动工具已收起；左侧工具按钮或 " + commands.shortcut("floatingTools") + " 可唤回");
    }

    boolean consumeMouse(int mx, int my, int button) {
        if (!visible()) return false;
        if (menuOpen) {
            pointerCaptured = true;
            if (button == 0)
                for (ToolButton target : new ArrayList<>(buttons.values())) if (target.bounds.contains(mx, my)) {
                    activateControl(target.id);
                    return true;
                }
            if (button >= 0) menuOpen = false;
            return true;
        }
        if (button == 0 && floating && floatingHeader != null && floatingHeader.contains(mx, my)) {
            pointerCaptured = dragging = true;
            grabX = mx - floatingX;
            grabY = my - floatingY;
            return true;
        }
        for (ToolButton target : new ArrayList<>(buttons.values())) if (target.bounds.contains(mx, my)) {
            pointerCaptured = true;
            if (button == 0) activateControl(target.id);
            return true;
        }
        if (blocksViewport(mx, my)) {
            pointerCaptured = true;
            return true;
        }
        if (button == 1 && commands.sceneAvailable()
            && ui.canvas.inViewport(mx, my)
            && !ui.preview.previewBlockedAt(mx, my)) {
            // Preserve the original right-click cancellation of a carried inventory sample.
            if (ui.preview.inventoryDragging() || ui.preview.inventorySlot() >= 0 || ui.preview.sourceChoice() >= 0) {
                ui.preview.inventoryDragging(false);
                ui.preview.inventorySlot(ui.preview.sourceChoice(-1));
            } else {
                menuOpen = true;
                menuX = mx;
                menuY = my;
                menuPage = 0;
            }
            ui.preview.dragging(false);
            pointerCaptured = true;
            return true;
        }
        return false;
    }

    boolean consumeDrag(int mx, int my, int button) {
        if (!pointerCaptured && !dragging) return false;
        if (dragging && button == 0) {
            floatingX = mx - grabX;
            floatingY = my - grabY;
        }
        return true;
    }

    boolean consumeRelease(int mx, int my, int button) {
        boolean consumed = pointerCaptured || dragging || menuOpen;
        pointerCaptured = false;
        dragging = false;
        return consumed;
    }

    boolean consumeWheel(int mx, int my, int wheel) {
        if (menuOpen) {
            if (wheel != 0) menuPage = Math.max(0, menuPage + (wheel < 0 ? 1 : -1));
            return true;
        }
        return pointerCaptured || blocksViewport(mx, my);
    }

    boolean consumeKey(char character, int key) {
        if (ui.router.textFieldFocused() || ui.shortcuts.captureBinding() >= 0) return false;
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) {
            if (menuOpen || dragging) {
                closeTransient();
                return true;
            }
            return false;
        }
        if (menuOpen) return true;
        if (!commands.sceneAvailable()) return false;
        String name = commands.match(key, HologramKeyBindings.modifiers());
        return !name.isEmpty() && commands.execute(name);
    }

    boolean blocksViewport(int mx, int my) {
        if (!visible()) return false;
        if (menuOpen || dragging || pointerCaptured) return true;
        for (Bounds bounds : surfaces) if (bounds.contains(mx, my)) return true;
        return false;
    }

    void closeTransient() {
        menuOpen = false;
        dragging = false;
        pointerCaptured = false;
        menuBounds = null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}
