package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** Paper controls use dark ink; legacy custom dark rows keep the original light text tokens. */
final class HologramMuiTheme {

    static final int TEXT = 0xffedf1eb, TITLE = 0xffefcb8a, MUTED = 0xff9bacb3, ERROR = 0xffff9891;

    static final UITexture PANEL = HologramEditorTheme.PANEL;
    static final UITexture CLOTH = HologramEditorTheme.CLOTH;
    static final UITexture BUTTON = HologramEditorTheme.BUTTON;
    static final UITexture PRESSED = HologramEditorTheme.HOVER;
    private static final UITexture CAT = UITexture.builder()
        .location("gtmb", "textures/items/neko_hologram_projector.png")
        .imageSize(16, 96)
        .subAreaXYWH(0, 0, 16, 16)
        .nonOpaque()
        .build()
        .withColorOverride(0xffffffff);

    private HologramMuiTheme() {}

    static ModularPanel panel(String name, int width, int height) {
        return new ModularPanel("gtmb_" + name).size(width, height)
            .background(CLOTH);
    }

    static TextWidget<?> label(Supplier<String> text, int x, int y, int width, int height, int color) {
        return IKey.dynamic(text)
            .color(HologramEditorTheme.textColor(color, false))
            .shadow(false)
            .asWidget()
            .textAlign(Alignment.CenterLeft)
            .pos(x, y)
            .size(width, height);
    }

    static ButtonWidget<?> button(Supplier<String> text, int x, int y, int width, int height, Runnable action) {
        ProjectorButton result = new ProjectorButton();
        return result.pos(x, y)
            .size(width, height)
            .background(
                (context, px, py, pw, ph, theme) -> HologramEditorTheme.button(result.active, result.primary, false)
                    .draw(context, px, py, pw, ph, theme))
            .hoverBackground(
                (context, px, py, pw, ph, theme) -> HologramEditorTheme.button(result.active, result.primary, true)
                    .draw(context, px, py, pw, ph, theme))
            .overlay(
                IKey.dynamic(text)
                    .color(() -> result.active ? TEXT : MUTED)
                    .shadow(false))
            .onMousePressed(button -> {
                if (button != 0 || !result.active) return false;
                action.run();
                return true;
            });
    }

    static ButtonWidget<?> primaryButton(Supplier<String> text, int x, int y, int width, int height, Runnable action) {
        ProjectorButton result = (ProjectorButton) button(text, x, y, width, height, action);
        result.primary = true;
        return result;
    }

    /** Sample render time only, preserving each action's semantic color as a pixel breathing edge. */
    static IDrawable guidanceBackground(boolean hover, IntSupplier accent, BooleanSupplier enabled) {
        return guidanceBackground(hover, accent, enabled, () -> false);
    }

    static IDrawable guidanceBackground(boolean hover, IntSupplier accent, BooleanSupplier enabled,
        BooleanSupplier selected) {
        return (context, x, y, width, height, theme) -> {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                boolean active = enabled.getAsBoolean();
                HologramEditorTheme.button(active, selected.getAsBoolean(), hover)
                    .draw(context, x, y, width, height, theme);
                HologramEditorTheme.drawGuidanceFrame(x, y, width, height, accent.getAsInt(), active);
            } finally {
                GL11.glPopAttrib();
            }
        };
    }

    static void header(ModularPanel panel, Supplier<String> title, int width) {
        panel.child(
            CAT.asWidget()
                .pos(12, 9)
                .size(16));
        panel.child(label(title, 36, 9, width - 48, 18, TITLE));
        panel.child(
            new Rectangle().color(0xffa1845b)
                .asWidget()
                .pos(12, 31)
                .size(width - 24, 1));
    }

    static IDrawable row(BooleanSupplier selected, boolean hover) {
        return (context, x, y, width, height, theme) -> {
            boolean active = selected.getAsBoolean();
            GuiDraw.drawRect(x, y, width, height, active ? 0xff665335 : hover ? 0xff65503a : 0xff4c3c2b);
            GuiDraw.drawRect(x, y + height - 1, width, 1, active ? 0xffd2b17d : 0xff846747);
            if (active) GuiDraw.drawRect(x, y, 2, height, TITLE);
        };
    }

    static TextFieldWidget textField(StringValue value, int x, int y, int width, int height, int maxLength) {
        return new TextFieldWidget().pos(x, y)
            .size(width, height)
            .background(HologramEditorTheme.INPUT)
            .value(value)
            .setTextColor(HologramEditorTheme.TEXT)
            .hintColor(HologramEditorTheme.MUTED)
            .setMarkedColor(0xffc4a36b)
            .setMaxLength(maxLength)
            .autoUpdateOnChange(true);
    }

    /** MUI's enabled flag also hides a widget; unavailable actions remain visible in this theme. */
    private static final class ProjectorButton extends ButtonWidget<ProjectorButton> {

        private boolean active = true, primary;

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
