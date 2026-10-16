package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/** No mutation occurs until the dedicated confirm button receives a separate click. */
final class HologramCutConfirmScreen extends HologramMuiScreen {

    private final String title, detail;
    private final Runnable confirm, cancel, confirmWithoutPrompt;
    private final Object world, connection;
    private final int dimension, slot;
    private boolean finished;

    HologramCutConfirmScreen(String title, String detail, Runnable confirm, Runnable cancel) {
        this(title, detail, confirm, cancel, null);
    }

    HologramCutConfirmScreen(String title, String detail, Runnable confirm, Runnable cancel,
        Runnable confirmWithoutPrompt) {
        super("cut_confirm", 306, 154);
        this.title = title;
        this.detail = detail;
        this.confirm = confirm;
        this.cancel = cancel;
        this.confirmWithoutPrompt = confirmWithoutPrompt;
        Minecraft minecraft = Minecraft.getMinecraft();
        world = minecraft.theWorld;
        connection = minecraft.getNetHandler();
        dimension = minecraft.thePlayer == null ? 0 : minecraft.thePlayer.dimension;
        slot = minecraft.thePlayer == null ? -1 : minecraft.thePlayer.inventory.currentItem;
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> title, 306);
        panel().child(HologramMuiTheme.label(() -> detail, 14, 41, 278, 66, HologramMuiTheme.TEXT));
        if (confirmWithoutPrompt == null) {
            panel().child(
                HologramMuiTheme
                    .primaryButton(
                        () -> HologramCutClient.text("confirm"),
                        162,
                        120,
                        132,
                        22,
                        () -> finish(true, false))
                    .name("cut_confirm"));
            panel().child(
                HologramMuiTheme
                    .button(() -> HologramCutClient.text("cancel"), 12, 120, 132, 22, () -> finish(false, false))
                    .name("cut_cancel"));
        } else {
            panel().child(
                HologramMuiTheme
                    .button(() -> HologramCutClient.text("cancel"), 12, 120, 56, 22, () -> finish(false, false))
                    .name("cut_cancel"));
            panel().child(
                HologramMuiTheme
                    .primaryButton(() -> HologramCutClient.text("confirm"), 74, 120, 56, 22, () -> finish(true, false))
                    .name("cut_confirm"));
            panel().child(
                HologramMuiTheme
                    .button(
                        () -> HologramCutClient.text("placement.confirmWithoutPrompt"),
                        136,
                        120,
                        158,
                        22,
                        () -> finish(true, true))
                    .name("cut_confirm_skip"));
        }
    }

    private void finish(boolean accepted, boolean skipPrompt) {
        if (finished) return;
        accepted = accepted && mc.theWorld == world
            && mc.getNetHandler() == connection
            && mc.thePlayer != null
            && mc.thePlayer.dimension == dimension
            && mc.thePlayer.inventory.currentItem == slot
            && ItemNekoHologramProjector.isProjector(mc.thePlayer.getHeldItem());
        finished = true;
        mc.displayGuiScreen(null);
        if (accepted) {
            if (skipPrompt && confirmWithoutPrompt != null) confirmWithoutPrompt.run();
            else confirm.run();
        } else cancel.run();
    }

    @Override
    protected boolean muiKey(char c, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        finish(false, false);
        return true;
    }

    @Override
    protected void onMuiClose() {
        if (!finished) {
            finished = true;
            cancel.run();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
