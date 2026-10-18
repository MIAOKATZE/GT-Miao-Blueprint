package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/** Preparation stays in a cancellable MUI page, before the world placement preview opens. */
final class HologramContentPreparationScreen extends HologramMuiScreen {

    private final GuiScreen previous;
    private final Object world, connection;
    private final int slot;
    private boolean finished;

    static void open() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen instanceof HologramContentPreparationScreen) return;
        minecraft.displayGuiScreen(new HologramContentPreparationScreen(minecraft.currentScreen));
    }

    static void complete() {
        if (Minecraft.getMinecraft().currentScreen instanceof HologramContentPreparationScreen screen)
            screen.finished = true;
    }

    private HologramContentPreparationScreen(GuiScreen previous) {
        super("blueprint_content_preparation", 420, 172);
        this.previous = previous;
        Minecraft minecraft = Minecraft.getMinecraft();
        world = minecraft.theWorld;
        connection = minecraft.getNetHandler();
        slot = minecraft.thePlayer == null ? -1 : minecraft.thePlayer.inventory.currentItem;
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> "准备蓝图", 420);
        panel().child(HologramMuiTheme.label(() -> "正在确认蓝图内容；完成后进入位置预览。", 14, 39, 392, 22, HologramMuiTheme.TEXT));
        panel().child(
            HologramMuiTheme
                .label(() -> HologramLibraryClient.INSTANCE.status(), 14, 68, 392, 32, HologramMuiTheme.MUTED));
        panel().child(HologramMuiTheme.label(() -> {
            HologramProgress progress = HologramLibraryClient.INSTANCE.progress();
            return progress == null ? "" : HologramProgressView.summary(progress, System.nanoTime());
        }, 14, 103, 392, 20, HologramMuiTheme.TEXT));
        panel().child(HologramMuiTheme.button(() -> "取消 / 返回", 14, 136, 392, 22, this::cancel));
    }

    private void cancel() {
        if (finished) return;
        finished = true;
        HologramLibraryClient.INSTANCE.cancelBackgroundTask();
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(
            previous instanceof HologramScreen && HologramClient.state != null
                && minecraft.thePlayer != null
                && minecraft.theWorld == world ? new HologramScreen(HologramClient.state) : null);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!finished && (minecraft.theWorld != world || minecraft.getNetHandler() != connection
            || minecraft.thePlayer == null
            || minecraft.thePlayer.inventory.currentItem != slot
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector
                .isProjector(minecraft.thePlayer.getHeldItem())))
            cancel();
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        cancel();
        return true;
    }

    @Override
    protected void onMuiClose() {
        if (!finished) {
            finished = true;
            HologramLibraryClient.INSTANCE.cancelBackgroundTask();
        }
    }
}
