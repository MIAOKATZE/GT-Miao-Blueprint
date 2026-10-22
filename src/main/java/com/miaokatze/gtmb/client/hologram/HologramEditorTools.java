package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;

/** Single tool facade. MUI widgets/panels own all presentation, hit areas and pointer capture. */
final class HologramEditorTools {

    static final int BASE = HologramEditorControlIds.TOOLS;
    static final int TOGGLE_FLOATING = BASE + 900, COLLAPSE_FLOATING = BASE + 901;
    static final int MENU_CLOSE = BASE + 902, MENU_PREVIOUS = BASE + 903, MENU_NEXT = BASE + 904;
    static final int PROJECTION_MODE = BASE + 910, BLUEPRINT_MODE = BASE + 911, CUT_MODE = BASE + 912;
    static final int LIBRARY_BUTTON = BASE + 913;
    private final HologramUiModules ui;
    private final HologramEditorCommands commands;
    private final HologramEditorNativeTools nativeTools;

    HologramEditorTools(HologramUiModules ui) {
        this.ui = ui;
        commands = new HologramEditorCommands(ui);
        nativeTools = new HologramEditorNativeTools(ui);
    }

    HologramEditorCommands commands() {
        return commands;
    }

    HologramEditorNativeTools nativeTools() {
        return nativeTools;
    }

    void attach(ModularPanel host) {
        nativeTools.attach(host);
    }

    IWidget buildModeBar() {
        return nativeTools.buildModeBar();
    }

    IWidget buildToolRail() {
        return nativeTools.buildToolRail();
    }

    IWidget buildTopTools() {
        return nativeTools.buildTopTools();
    }

    com.cleanroommc.modularui.widgets.layout.Flow buildViewPresets() {
        return nativeTools.buildViewPresets();
    }

    IWidget buildSelectedBar() {
        return nativeTools.buildSelectedBar();
    }

    /** Compatibility only; no tools or mirrored canvas controls are painted. */
    void draw(int x, int y) {}

    boolean activateControl(int id) {
        if (id < BASE || id >= BASE + 1000) return false;
        if (ui.shortcuts.captureBindingControl(id) || ui.shortcuts.activateControl(id)) return true;
        switch (id) {
            case TOGGLE_FLOATING -> toggleFloating();
            case COLLAPSE_FLOATING -> nativeTools.closeFloating();
            case MENU_CLOSE -> nativeTools.closeContext();
            case PROJECTION_MODE -> ui.workspaces.activateProjection();
            case BLUEPRINT_MODE -> ui.workspaces.activateBlueprint();
            case LIBRARY_BUTTON -> {
                if (!ui.workspaces.blueprint()) ui.workspaces.activateBlueprint();
                else ui.shell.toggleLibrary();
            }
            case CUT_MODE -> ui.workspaces.activateCut();
            default -> {
                int offset = id - BASE;
                if (offset >= 0 && offset < 500) {
                    HologramEditorCommands.Command command = commands.get(offset % 100);
                    if (command != null) nativeTools.execute(command.name);
                }
            }
        }
        return true;
    }

    void toggleFloating() {
        nativeTools.toggleFloating();
    }

    void openShortcuts() {
        nativeTools.openShortcuts();
    }

    void closeShortcuts() {
        nativeTools.closeShortcuts();
    }

    void openViewWorkspace() {
        nativeTools.openViewWorkspace();
    }

    boolean viewWorkspaceOpen() {
        return nativeTools.viewWorkspaceOpen();
    }

    boolean restoreViewWorkspace(boolean open) {
        return nativeTools.restoreViewWorkspace(open);
    }

    void closeTransient() {
        nativeTools.closeContext();
        nativeTools.closeViewWorkspace();
    }

    boolean modalOpen() {
        return nativeTools.modalOpen();
    }

    boolean pointerCaptured() {
        return nativeTools.pointerCaptured();
    }

    /** Called only from an unconsumed scene press after native widgets and panels. */
    boolean consumeMouse(int x, int y, int button) {
        if (nativeTools.modalOpen()) return true;
        if (button != 1 || !commands.commandContextAvailable()) return false;
        if (ui.preview.inventoryDragging() || ui.preview.inventorySlot() >= 0 || ui.preview.sourceChoice() >= 0) {
            ui.preview.inventoryDragging(false);
            ui.preview.inventorySlot(ui.preview.sourceChoice(-1));
        } else nativeTools.contextAtPointer();
        ui.preview.dragging(false);
        return true;
    }

    boolean consumeDrag(int x, int y, int button) {
        return nativeTools.modalOpen() || pointerCaptured();
    }

    boolean consumeRelease(int x, int y, int button) {
        return nativeTools.modalOpen() || pointerCaptured();
    }

    boolean consumeWheel(int x, int y, int wheel) {
        return nativeTools.modalOpen() || pointerCaptured();
    }

    boolean blocksViewport(int guiX, int guiY) {
        return nativeTools.blocksGuiPoint(guiX, guiY);
    }

    boolean consumeKey(char character, int key) {
        if (ui.shell.fieldFocused() || ui.shortcuts.captureBinding() >= 0) return false;
        if (nativeTools.consumeDialogKey(character, key)) return true;
        if (pointerCaptured()) return true;
        String command = commands.match(key, HologramKeyBindings.modifiers());
        if (commands.workspaceCommand(command)) return commands.execute(command);
        if ("shortcuts".equals(command) && ui.workspaces.emptyProjection()) return commands.execute(command);
        if (ui.workspaces.isCut()) return key != Keyboard.KEY_ESCAPE && key != Keyboard.KEY_E;
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E || !commands.sceneAvailable()) return false;
        return !command.isEmpty() && commands.execute(command);
    }

    String tooltip(int id) {
        String binding = ui.shortcuts.bindingTooltip(id);
        if (binding != null) return binding;
        for (int i = 0; i < commands.size(); i++) {
            HologramEditorCommands.Command command = commands.get(i);
            if (command.control >= 0 && command.control == id) return commands.help(command.name);
            int offset = id - BASE;
            if (offset >= 0 && offset < 500 && offset % 100 == i) return commands.help(command.name);
        }
        return null;
    }

    String icon(int id) {
        return null;
    }

    boolean iconOnly(int id) {
        return false;
    }
}
