package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.Dialog;

/** Local, unsynced MUI2 child panel. It blocks the workspace underneath, including outside clicks. */
final class HologramSaveGuidanceDialog {

    private final HologramUiTabMemory preferences;
    private final IPanelHandler handler;
    private final Runnable openWebsite;

    HologramSaveGuidanceDialog(ModularPanel parent, HologramUiTabMemory preferences, Runnable openWebsite) {
        this.preferences = preferences;
        this.openWebsite = openWebsite;
        handler = IPanelHandler.simple(parent, (panel, player) -> build(), true);
    }

    void open() {
        handler.openPanel();
    }

    boolean isOpen() {
        return handler.isPanelOpen();
    }

    boolean consumeKey(int key) {
        if (!isOpen()) return false;
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN
            || key == Keyboard.KEY_NUMPADENTER
            || key == Keyboard.KEY_E) dismiss(false);
        return true;
    }

    private void dismiss(boolean suppress) {
        if (suppress) preferences.suppressSaveGuidance();
        // Acknowledging the explanation deliberately leaves the suggested structure entry highlighted.
        handler.closePanel();
    }

    private ModularPanel build() {
        Dialog<?> panel = new ExplanationPanel();
        panel.size(428, 255)
            .background(HologramMuiTheme.CLOTH);
        HologramMuiTheme.header(panel, () -> text("title", "蓝图已保存 · 接下来设置结构"), 428);
        panel.child(
            HologramMuiTheme.label(() -> text("blueprint", "蓝图设置与蓝图库"), 18, 46, 392, 16, HologramUiAccent.INFORMATION));
        panel.child(
            HologramMuiTheme.label(
                () -> text("blueprint_help", "在蓝图设置处理方案，在蓝图库保存与管理分享文件。"),
                18,
                65,
                392,
                28,
                HologramMuiTheme.TEXT));
        panel.child(
            HologramMuiTheme.label(() -> text("structure", "属性侧栏"), 18, 101, 392, 16, HologramUiAccent.SUGGESTION));
        panel.child(
            HologramMuiTheme.label(
                () -> text("structure_help", "设置结构等级、信道、方块目标和仓室；施工前再核对材料。"),
                18,
                120,
                392,
                30,
                HologramMuiTheme.TEXT));
        panel.child(
            HologramMuiTheme
                .label(() -> text("next", "选中模型后，打开右侧“属性”继续编辑。"), 18, 157, 392, 18, HologramUiAccent.SUGGESTION));
        panel.child(
            HologramMuiTheme.button(
                () -> HologramUiText.localized("gtmb.hologram.website.share", "前往蓝图站浏览与分享 ↗"),
                18,
                184,
                392,
                23,
                () -> {
                    dismiss(false);
                    openWebsite.run();
                })
                .background(HologramMuiTheme.guidanceBackground(false, () -> HologramUiAccent.WEBSITE, () -> true))
                .hoverBackground(HologramMuiTheme.guidanceBackground(true, () -> HologramUiAccent.WEBSITE, () -> true))
                .overlay(
                    IKey.dynamic(() -> HologramUiText.localized("gtmb.hologram.website.share", "前往蓝图站浏览与分享 ↗"))
                        .color(HologramUiAccent.WEBSITE)
                        .shadow(false)));
        panel.child(
            HologramMuiTheme.button(() -> text("acknowledge", "知道了"), 18, 218, 190, 23, () -> dismiss(false))
                .background(HologramMuiTheme.guidanceBackground(false, () -> HologramUiAccent.NEXT, () -> true))
                .hoverBackground(HologramMuiTheme.guidanceBackground(true, () -> HologramUiAccent.NEXT, () -> true))
                .overlay(
                    IKey.dynamic(() -> text("acknowledge", "知道了"))
                        .color(HologramUiAccent.NEXT)
                        .shadow(false)));
        panel.child(HologramMuiTheme.button(() -> text("suppress", "不再显示"), 220, 218, 190, 23, () -> dismiss(true)));
        return panel;
    }

    private static String text(String suffix, String fallback) {
        return HologramUiText.localized("gtmb.hologram.save_guidance." + suffix, fallback);
    }

    private static final class ExplanationPanel extends Dialog<Void> {

        ExplanationPanel() {
            super("gtmb_save_structure_guidance");
            setDisablePanelsBelow(true);
            setCloseOnOutOfBoundsClick(false);
            setDraggable(false);
        }

        @Override
        public void transform(IViewportStack stack) {
            int screenWidth = getScreen().getScreenArea().width;
            int screenHeight = getScreen().getScreenArea().height;
            float scale = Math.min(1f, Math.min(screenWidth / 440f, screenHeight / 267f));
            stack.translate(
                (screenWidth - getArea().width * scale) / 2f,
                (screenHeight - getArea().height * scale) / 2f);
            stack.scale(scale, scale);
        }
    }
}
