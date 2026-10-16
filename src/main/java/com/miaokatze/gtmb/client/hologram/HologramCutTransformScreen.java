package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/** Dedicated raw-cut pose controls. Every preview request is derived from the immutable server original. */
final class HologramCutTransformScreen extends GuiScreen {

    private final HologramCutClient cut = HologramCutClient.INSTANCE;

    @Override
    public void initGui() {
        buttonList.clear();
        int left = width / 2 - 132, top = height / 2 - 62;
        for (int axis = 0; axis < 3; axis++) {
            String name = new String[] { "X", "Y", "Z" }[axis];
            buttonList.add(new GuiButton(10 + axis * 2, left, top + axis * 24, 82, 20, name + " -90°"));
            buttonList.add(new GuiButton(11 + axis * 2, left + 90, top + axis * 24, 82, 20, name + " +90°"));
            buttonList.add(
                new GuiButton(
                    20 + axis,
                    left + 180,
                    top + axis * 24,
                    84,
                    20,
                    HologramCutClient.text("mirror") + " " + name));
        }
        String[] faces = { "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST" };
        for (int face = 0; face < 6; face++) buttonList.add(
            new GuiButton(
                30 + face,
                left + face % 3 * 90,
                top + 80 + face / 3 * 24,
                84,
                20,
                HologramCutClient.text("face." + faces[face].toLowerCase(java.util.Locale.ROOT))));
        buttonList.add(new GuiButton(40, left, top + 134, 128, 20, HologramCutClient.text("resetPose")));
        buttonList.add(new GuiButton(0, left + 136, top + 134, 128, 20, HologramCutClient.text("done")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) mc.displayGuiScreen(null);
        else if (!cut.busy()) {
            if (button.id >= 10 && button.id < 16) cut.rotate((button.id - 10) / 2, button.id % 2 == 0 ? -1 : 1);
            else if (button.id >= 20 && button.id < 23) cut.mirror(button.id - 20);
            else if (button.id >= 30 && button.id < 36) cut.face(button.id - 30);
            else if (button.id == 40) cut.resetPose();
        }
    }

    @Override
    public void updateScreen() {
        if (!cut.active()) mc.displayGuiScreen(null);
        for (Object value : buttonList) {
            GuiButton button = (GuiButton) value;
            button.enabled = button.id == 0 || !cut.busy();
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(null);
    }

    @Override
    public void drawScreen(int x, int y, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, HologramCutClient.text("transform"), width / 2, height / 2 - 102, 0xffdb83);
        drawCenteredString(fontRendererObj, cut.poseSummary(), width / 2, height / 2 - 86, 0xd1dee6);
        super.drawScreen(x, y, ticks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
