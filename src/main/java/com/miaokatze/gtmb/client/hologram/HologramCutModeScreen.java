package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/** Modal Shift switcher: all input stays in this screen until one release commits the choice. */
final class HologramCutModeScreen extends GuiScreen {

    private final HologramCutInput.Switcher choice = new HologramCutInput.Switcher();

    @Override
    public void updateScreen() {
        if (!choice.release(HologramCutClient.shift())) return;
        mc.displayGuiScreen(null);
        if (choice.cut()) HologramCutClient.INSTANCE.beginSelection();
        else HologramClient.beginAreaSelection();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (button == 1 && HologramCutClient.shift()) choice.cycle();
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            choice.cancel();
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float ticks) {
        drawDefaultBackground();
        int panelWidth = Math.min(480, width - 20), left = (width - panelWidth) / 2;
        java.util.List<String> hints = fontRendererObj
            .listFormattedStringToWidth(HologramCutClient.text("switcherHint"), panelWidth - 20);
        int panelHeight = 88 + hints.size() * 11, top = (height - panelHeight) / 2;
        drawRect(left, top, left + panelWidth, top + panelHeight, 0xee17232b);
        drawCenteredString(fontRendererObj, HologramCutClient.text("switcher"), width / 2, top + 10, 0xffffff);
        int half = panelWidth / 2;
        for (int i = 0; i < 2; i++) {
            int x = left + i * half + 8;
            boolean selected = choice.cut() == (i == 1);
            drawRect(x, top + 31, x + half - 16, top + 72, selected ? 0xff327685 : 0xff293843);
            drawCenteredString(
                fontRendererObj,
                HologramCutClient.text(i == 0 ? "blueprint" : "mode"),
                x + (half - 16) / 2,
                top + 47,
                selected ? 0xffffff : 0x99aabb);
        }
        int y = top + 82;
        for (String hint : hints) {
            drawCenteredString(fontRendererObj, hint, width / 2, y, 0xb9ced8);
            y += 11;
        }
        super.drawScreen(mouseX, mouseY, ticks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
