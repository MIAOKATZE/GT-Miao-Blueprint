package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/** No mutation occurs until the dedicated confirm button receives a separate click. */
final class HologramCutConfirmScreen extends GuiScreen {

    private final String title, detail;
    private final Runnable confirm, cancel, transform;
    private final Object world, connection;
    private final int dimension, slot;
    private boolean finished;

    HologramCutConfirmScreen(String title, String detail, Runnable confirm, Runnable cancel) {
        this(title, detail, confirm, cancel, null);
    }

    HologramCutConfirmScreen(String title, String detail, Runnable confirm, Runnable cancel, Runnable transform) {
        this.title = title;
        this.detail = detail;
        this.confirm = confirm;
        this.cancel = cancel;
        this.transform = transform;
        Minecraft minecraft = Minecraft.getMinecraft();
        world = minecraft.theWorld;
        connection = minecraft.getNetHandler();
        dimension = minecraft.thePlayer == null ? 0 : minecraft.thePlayer.dimension;
        slot = minecraft.thePlayer == null ? -1 : minecraft.thePlayer.inventory.currentItem;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int w = Math.min(120, (width - 34) / 2);
        buttonList.add(new GuiButton(1, width / 2 - w - 5, height / 2 + 35, w, 20, HologramCutClient.text("confirm")));
        buttonList.add(new GuiButton(0, width / 2 + 5, height / 2 + 35, w, 20, HologramCutClient.text("cancel")));
        if (transform != null) buttonList
            .add(new GuiButton(2, width / 2 - w, height / 2 + 60, w * 2, 20, HologramCutClient.text("transform")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 2 && transform != null && !finished) {
            finished = true;
            mc.displayGuiScreen(null);
            transform.run();
            return;
        }
        finish(button.id == 1);
    }

    private void finish(boolean accepted) {
        if (finished) return;
        accepted = accepted && mc.theWorld == world
            && mc.getNetHandler() == connection
            && mc.thePlayer != null
            && mc.thePlayer.dimension == dimension
            && mc.thePlayer.inventory.currentItem == slot
            && ItemNekoHologramProjector.isProjector(mc.thePlayer.getHeldItem());
        finished = true;
        mc.displayGuiScreen(null);
        if (accepted) confirm.run();
        else cancel.run();
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) finish(false);
    }

    @Override
    public void onGuiClosed() {
        if (!finished) {
            finished = true;
            cancel.run();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, title, width / 2, height / 2 - 48, 0xffdb83);
        java.util.List<String> rows = fontRendererObj.listFormattedStringToWidth(detail, Math.max(40, width - 40));
        int y = height / 2 - 26;
        for (String row : rows) {
            drawCenteredString(fontRendererObj, row, width / 2, y, 0xd1dee6);
            y += 11;
        }
        super.drawScreen(mouseX, mouseY, ticks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
