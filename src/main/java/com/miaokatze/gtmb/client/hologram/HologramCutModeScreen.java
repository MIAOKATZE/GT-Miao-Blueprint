package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.drawable.IKey;

/** Modal Shift switcher: all input stays in this screen until one release commits the choice. */
final class HologramCutModeScreen extends HologramMuiScreen {

    private final HologramCutInput.Switcher choice = new HologramCutInput.Switcher(
        HologramCutClient.INSTANCE.cutMode());

    @Override
    public void updateScreen() {
        if (!choice.release(HologramCutClient.shift())) return;
        mc.displayGuiScreen(null);
        if (choice.cut()) HologramCutClient.INSTANCE.chooseCutMode();
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
        for (int mode = 0; mode < 2; mode++) {
            final boolean cut = mode == 1;
            panel()
                .child(
                    HologramMuiTheme
                        .button(
                            () -> (choice.cut() == cut ? "▶ " : "")
                                + HologramCutClient.text(cut ? "mode" : "blueprint"),
                            12 + mode * 146,
                            42,
                            136,
                            34,
                            () -> {})
                        .background(HologramMuiTheme.row(() -> choice.cut() == cut, false))
                        .hoverBackground(HologramMuiTheme.row(() -> choice.cut() == cut, true))
                        .overlay(
                            IKey.dynamic(
                                () -> (choice.cut() == cut ? "▶ " : "")
                                    + HologramCutClient.text(cut ? "mode" : "blueprint"))
                                .color(() -> choice.cut() == cut ? HologramMuiTheme.TITLE : HologramMuiTheme.TEXT)
                                .shadow(false))
                        .name("cut_mode_" + mode)
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
