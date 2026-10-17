package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** The projector family shares one set of pocket textures, including native MUI controls. */
final class HologramMuiTheme {

    static final int TEXT = 0xffedf1eb, TITLE = 0xffefcb8a, MUTED = 0xff9bacb3, ERROR = 0xffff9891;

    private static final UITexture POCKET_PANEL = texture("panel", 64, 64, 5, 5);
    private static final UITexture POCKET_CLOTH = texture("cloth", 32, 32, 3, 3);
    private static final UITexture POCKET_BUTTON = texture("btn", 88, 18, 4, 3);
    private static final UITexture POCKET_PRESSED = texture("btn_pressed", 88, 18, 4, 3);
    // The original four series textures remain real, visible trim; quiet interiors carry the content.
    static final UITexture PANEL = new PocketSurface(POCKET_PANEL, 2, 0xff121c22, 0xff18242b);
    static final UITexture CLOTH = new PocketSurface(POCKET_CLOTH, 3, 0xff263238, 0xff192329);
    static final UITexture BUTTON = new PocketSurface(POCKET_BUTTON, 2, 0xff35434b, 0xff29363e);
    static final UITexture PRESSED = new PocketSurface(POCKET_PRESSED, 2, 0xff4c5e63, 0xff35484f);
    private static final UITexture PRIMARY = new PocketSurface(POCKET_BUTTON, 2, 0xff725b3e, 0xff56442f);
    private static final UITexture PRIMARY_HOVER = new PocketSurface(POCKET_PRESSED, 2, 0xff8a704d, 0xff665139);
    private static final UITexture CAT = UITexture.builder()
        .location("gtmb", "textures/items/neko_hologram_projector.png")
        .imageSize(16, 96)
        .subAreaXYWH(0, 0, 16, 16)
        .nonOpaque()
        .build()
        .withColorOverride(0xffffffff);

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
        int readable = switch (color & 0xffffff) {
            case 0xff9292, 0xef8585, 0xff9696 -> ERROR;
            case 0xffdb83, 0x9ceaf3, 0xf0ddb3 -> TITLE;
            case 0xd4c5a8, 0xb5c2cc, 0xb9ced8, 0xbacbd1 -> MUTED;
            default -> color;
        };
        return IKey.dynamic(text)
            .color(readable)
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
            .background(BUTTON)
            .hoverBackground(PRESSED)
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
        return button(text, x, y, width, height, action).background(PRIMARY)
            .hoverBackground(PRIMARY_HOVER);
    }

    /** Keep the projector trim, then cover its orange edge with a semantic solid frame. */
    static IDrawable guidanceBackground(boolean hover, IntSupplier accent, BooleanSupplier enabled) {
        return (context, x, y, width, height, theme) -> {
            (hover ? PRESSED : BUTTON).draw(context, x, y, width, height, theme);
            int color = HologramUiAccent.borderColor(accent.getAsInt(), enabled.getAsBoolean());
            if (color == 0) return;
            int edge = HologramUiAccent.BORDER_WIDTH;
            GuiDraw.drawRect(x, y, width, edge, color);
            GuiDraw.drawRect(x, y + height - edge, width, edge, color);
            GuiDraw.drawRect(x, y + edge, edge, height - edge * 2, color);
            GuiDraw.drawRect(x + width - edge, y + edge, edge, height - edge * 2, color);
        };
    }

    static void header(ModularPanel panel, Supplier<String> title, int width) {
        panel.child(
            CAT.asWidget()
                .pos(12, 9)
                .size(16));
        panel.child(label(title, 36, 9, width - 48, 18, TITLE));
        panel.child(
            new Rectangle().color(0xff596269)
                .asWidget()
                .pos(12, 31)
                .size(width - 24, 1));
    }

    static IDrawable row(BooleanSupplier selected, boolean hover) {
        return (context, x, y, width, height, theme) -> {
            boolean active = selected.getAsBoolean();
            GuiDraw.drawRect(x, y, width, height, active ? 0xff465047 : hover ? 0xff35444d : 0xff24323a);
            GuiDraw.drawRect(x, y + height - 1, width, 1, active ? 0xffa98a59 : 0xff384850);
            if (active) GuiDraw.drawRect(x, y, 2, height, TITLE);
        };
    }

    /** UITexture compatibility also keeps legacy canvas hosts on the exact same visible skin. */
    private static final class PocketSurface extends UITexture {

        private final UITexture trim;
        private final int inset, top, bottom;

        PocketSurface(UITexture trim, int inset, int top, int bottom) {
            super(trim.location, 0, 0, 1, 1, null);
            this.trim = trim;
            this.inset = inset;
            this.top = top;
            this.bottom = bottom;
        }

        @Override
        public void draw(GuiContext context, int x, int y, int width, int height, WidgetTheme theme) {
            draw((float) x, y, width, height);
        }

        @Override
        public void draw(float x, float y, float width, float height) {
            trim.draw(x, y, width, height);
            if (width > inset * 2 && height > inset * 2) GuiDraw
                .drawVerticalGradientRect(x + inset, y + inset, width - inset * 2, height - inset * 2, top, bottom);
        }
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
