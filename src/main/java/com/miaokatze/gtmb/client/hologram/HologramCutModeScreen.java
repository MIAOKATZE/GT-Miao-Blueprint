package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

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
        super("cut_modes", 360, 144);
    }

    @Override
    protected void buildPanel() {
        panel().child(HologramMuiTheme.label(() -> HologramCutClient.text("switcher"), 12, 10, 336, 20, 0xffffdb83));
        for (int mode = 0; mode < 2; mode++) {
            final boolean cut = mode == 1;
            panel()
                .child(
                    HologramMuiTheme
                        .button(
                            () -> (choice.cut() == cut ? "▶ " : "")
                                + HologramCutClient.text(cut ? "mode" : "blueprint"),
                            12 + mode * 174,
                            42,
                            162,
                            40,
                            () -> {})
                        .name("cut_mode_" + mode)
                        .onMousePressed(button -> {
                            if (button == 1 && HologramCutClient.shift()) choice.cycle();
                            return true;
                        }));
        }
        panel()
            .child(HologramMuiTheme.label(() -> HologramCutClient.text("switcherHint"), 12, 94, 336, 40, 0xffb9ced8));
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        choice.cancel();
        mc.displayGuiScreen(null);
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
