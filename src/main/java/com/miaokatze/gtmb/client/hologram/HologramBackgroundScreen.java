package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.widgets.ProgressWidget;

/** Every reopen builds fresh MUI widgets; the operation itself remains owned outside the screen. */
final class HologramBackgroundScreen extends HologramMuiScreen {

    private long renderedFrames;

    HologramBackgroundScreen() {
        super("background_task", 360, 174);
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> HologramBackgroundTasks.label(), 360);
        panel().child(HologramMuiTheme.label(HologramBackgroundTasks::status, 18, 48, 324, 34, 0xffe5c488));
        panel().child(new ProgressWidget() {

            @Override
            public void draw(com.cleanroommc.modularui.screen.viewport.ModularGuiContext context,
                com.cleanroommc.modularui.theme.WidgetThemeEntry<?> theme) {
                super.draw(context, theme);
                renderedFrames++;
            }
        }.texture(HologramMuiTheme.PANEL, HologramMuiTheme.PRESSED, 324)
            .value(new DoubleValue.Dynamic(HologramBackgroundTasks::fraction, null))
            .pos(18, 84)
            .size(324, 10));
        panel().child(HologramMuiTheme.label(HologramBackgroundTasks::percentage, 18, 96, 324, 14, 0xffe5c488));
        panel().child(HologramMuiTheme.label(() -> "关闭后继续 · 右键投影仪返回任务", 18, 110, 324, 14, 0xffbacbd1));
        panel().child(HologramMuiTheme.button(() -> "后台继续", 18, 126, 74, 22, this::close));
        panel().child(
            HologramMuiTheme
                .button(HologramBackgroundTasks::pauseLabel, 100, 126, 74, 22, HologramBackgroundTasks::togglePause)
                .setEnabledIf(widget -> HologramBackgroundTasks.pauseAvailable()));
        panel().child(
            HologramMuiTheme.button(() -> "主动取消", 182, 126, 74, 22, HologramBackgroundTasks::cancel)
                .setEnabledIf(widget -> HologramBackgroundTasks.canCancel()));
        panel().child(
            HologramMuiTheme.button(() -> "返回工作台", 264, 126, 78, 22, HologramBackgroundTasks::returnToWorkspace)
                .setEnabledIf(widget -> !HologramBackgroundTasks.busy()));
    }

    private void close() {
        HologramBackgroundTasks.detach();
        Minecraft.getMinecraft()
            .displayGuiScreen(null);
    }

    long renderedFrames() {
        return renderedFrames;
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE && key != Keyboard.KEY_E) return false;
        close();
        return true;
    }

    @Override
    protected void onMuiClose() {
        HologramBackgroundTasks.detach();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
