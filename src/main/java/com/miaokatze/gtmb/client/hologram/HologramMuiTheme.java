package com.miaokatze.gtmb.client.hologram;

import java.util.function.Predicate;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** The projector family shares one set of pocket textures, including native MUI controls. */
final class HologramMuiTheme {

    static final int TEXT = 0xff35291e, TITLE = 0xff4d2e18, MUTED = 0xff63503e, ERROR = 0xff8d2925;

    static final UITexture PANEL = texture("panel", 64, 64, 5, 5);
    static final UITexture CLOTH = texture("cloth", 32, 32, 3, 3);
    static final UITexture BUTTON = texture("btn", 88, 18, 4, 3);
    static final UITexture PRESSED = texture("btn_pressed", 88, 18, 4, 3);

    private HologramMuiTheme() {}

    private static UITexture texture(String name, int width, int height, int borderX, int borderY) {
        return UITexture.builder()
            .location("gtmb", "textures/gui/pocket/POCKET_C2_" + name + ".png")
            .imageSize(width, height)
            .adaptable(borderX, borderY)
            .build()
            .withColorOverride(0xffffffff);
    }

    static ModularPanel panel(String name, int width, int height) {
        return new ModularPanel("gtmb_" + name).size(width, height)
            .background(CLOTH);
    }

    static TextWidget<?> label(Supplier<String> text, int x, int y, int width, int height, int color) {
        int readable = (color & 0xffffff) == 0xff9292 || (color & 0xffffff) == 0xef8585 ? ERROR
            : (color & 0xffffff) == 0xffdb83 || (color & 0xffffff) == 0x9ceaf3 ? TITLE : TEXT;
        return IKey.dynamic(text)
            .color(readable)
            .asWidget()
            .pos(x, y)
            .size(width, height);
    }

    static ButtonWidget<?> button(Supplier<String> text, int x, int y, int width, int height, Runnable action) {
        ProjectorButton result = new ProjectorButton();
        return result.pos(x, y)
            .size(width, height)
            .background(BUTTON)
            .hoverBackground(PRESSED)
            .overlay(
                IKey.dynamic(text)
                    .color(() -> result.active ? 0xfff0ddb3 : 0xffa39275))
            .onMousePressed(button -> {
                if (button != 0 || !result.active) return false;
                action.run();
                return true;
            });
    }

    static TextFieldWidget textField(StringValue value, int x, int y, int width, int height, int maxLength) {
        return new TextFieldWidget().pos(x, y)
            .size(width, height)
            .background(PANEL)
            .value(value)
            .setTextColor(TEXT)
            .hintColor(MUTED)
            .setMarkedColor(0xffc4a36b)
            .setMaxLength(maxLength)
            .autoUpdateOnChange(true);
    }

    /** MUI's enabled flag also hides a widget; unavailable actions remain visible in this theme. */
    private static final class ProjectorButton extends ButtonWidget<ProjectorButton> {

        private boolean active = true;

        @Override
        public ProjectorButton setEnabledIf(Predicate<ProjectorButton> condition) {
            return onUpdateListener(widget -> active = condition.test(widget), true);
        }

        @Override
        public Result onMousePressed(int button) {
            return active ? super.onMousePressed(button) : Result.SUCCESS;
        }
    }
}
