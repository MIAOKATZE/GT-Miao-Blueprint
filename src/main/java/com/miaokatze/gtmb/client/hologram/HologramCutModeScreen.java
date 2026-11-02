package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.drawable.IKey;

/** Modal Shift switcher: all input stays in this screen until one release commits the choice. */
final class HologramCutModeScreen extends HologramMuiScreen {

    private final HologramCutInput.Switcher choice = new HologramCutInput.Switcher(HologramCutClient.INSTANCE.mode());

    @Override
    public void updateScreen() {
        if (!choice.release(HologramCutClient.shift())) return;
        mc.displayGuiScreen(null);
        if (choice.mode() == HologramCutInput.Mode.DEMOLITION) HologramCutClient.INSTANCE.chooseDemolitionMode();
        else if (choice.cut()) HologramCutClient.INSTANCE.chooseCutMode();
        else {
            HologramCutClient.INSTANCE.chooseBlueprintMode();
            HologramClient.beginAreaSelection();
        }
    }

    HologramCutModeScreen() {
        super("cut_modes", 306, 126);
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> HologramCutClient.text("switcher"), 306);
        for (HologramCutInput.Mode mode : HologramCutInput.Mode.values()) {
            final String label = mode == HologramCutInput.Mode.CUT ? "mode"
                : mode == HologramCutInput.Mode.DEMOLITION ? "demolition" : "blueprint";
            panel().child(
                HologramMuiTheme
                    .button(
                        () -> (choice.mode() == mode ? "▶ " : "") + HologramCutClient.text(label),
                        12 + mode.ordinal() * 96,
                        42,
                        90,
                        34,
                        () -> {})
                    .background(HologramMuiTheme.row(() -> choice.mode() == mode, false))
                    .hoverBackground(HologramMuiTheme.row(() -> choice.mode() == mode, true))
                    .overlay(
                        IKey.dynamic(() -> (choice.mode() == mode ? "▶ " : "") + HologramCutClient.text(label))
                            .color(() -> choice.mode() == mode ? HologramMuiTheme.TITLE : HologramMuiTheme.TEXT)
                            .shadow(false))
                    .name("cut_mode_" + mode.ordinal())
                    .onMousePressed(button -> {
                        if (button == 1 && HologramCutClient.shift()) choice.cycle();
                        return true;
                    }));
        }
        panel().child(
            HologramMuiTheme
                .label(() -> HologramCutClient.text("switcherHint"), 12, 86, 282, 28, HologramMuiTheme.MUTED));
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        choice.cancel();
        HologramCutClient.INSTANCE.exitCutMode();
        return true;
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (button == 1 && HologramCutClient.shift()) choice.cycle();
    }

    @Override
    protected boolean muiMouseBefore(int button) {
        if (button == 1 && HologramCutClient.shift()) choice.cycle();
        return true;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
