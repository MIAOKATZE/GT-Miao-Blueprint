package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraftforge.common.MinecraftForge;

import com.cleanroommc.modularui.api.IThemeApi;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.DrawableSerialization;
import com.cleanroommc.modularui.drawable.DrawableStack;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.theme.ReloadThemeEvent;
import com.cleanroommc.modularui.theme.SelectableTheme;
import com.cleanroommc.modularui.theme.TextFieldTheme;
import com.cleanroommc.modularui.theme.ThemeBuilder;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PageButton;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** GTMB's native paper controls. The world viewport and legacy dark domain controls use their own palette. */
public final class HologramEditorNativeTheme {

    static final String ID = "gtmb:editor_paper";
    static final int TEXT = HologramEditorTheme.TEXT, TITLE = HologramEditorTheme.TITLE,
        MUTED = HologramEditorTheme.MUTED;
    static final int DARK_TEXT = HologramEditorTheme.BUTTON_TEXT, DISABLED_TEXT = 0xff81745e;

    // MUI 2.3.92 recurses when serializing unregistered tiled/adaptable textures. Register the final objects first;
    // ThemeBuilder then serializes stable IDs, whose registry entries retain the actual tiling and nine-slice metadata.
    static final UITexture PAPER = UITexture.builder()
        .location("gtmb", "gui/editor/kraft/paper")
        .tiled(64, 64)
        .nonOpaque()
        .build()
        .withColorOverride(0xffffffff)
        .register("gtmb:editor/kraft/paper");
    private static final UITexture FRAME = texture("panel");
    static final IDrawable PANEL = new DrawableStack(PAPER, FRAME);
    static final UITexture INPUT = texture("input"), INPUT_FOCUS = texture("input-focus");
    static final UITexture BUTTON = texture("native-button"), HOVER = texture("native-button-hover");
    static final UITexture SELECTED = texture("native-button-selected"),
        SELECTED_HOVER = texture("native-button-selected-hover");
    static final UITexture DISABLED = texture("native-button-disabled");
    static final UITexture PRIMARY = texture("native-primary"), PRIMARY_HOVER = texture("native-primary-hover");
    static final IDrawable SCRIM = new Rectangle().color(0x4026292b);
    static final IDrawable DIVIDER = new Rectangle().color(0xffbaa783);
    private static final BooleanSupplier YES = () -> true, NO = () -> false;
    private static final ThemeBuilder<?> DEFINITION = definition();
    private static boolean installed;

    private HologramEditorNativeTheme() {}

    /**
     * Install during client preInit, before MUI's resource/theme reload. Never reload all themes when opening a screen.
     */
    public static void install() {
        if (installed) return;
        installed = true;
        MinecraftForge.EVENT_BUS.register(new HologramEditorNativeTheme());
        register();
    }

    @SubscribeEvent
    public void reload(ReloadThemeEvent.Pre event) {
        register();
    }

    private static void register() {
        IThemeApi.get()
            .registerTheme(DEFINITION);
    }

    static void apply(ModularScreen screen) {
        screen.useTheme(ID);
    }

    private static UITexture texture(String name) {
        return UITexture.builder()
            .location("gtmb", "gui/editor/kraft/" + name)
            .imageSize(8, 8)
            .adaptable(1)
            .nonOpaque()
            .build()
            .withColorOverride(0xffffffff)
            .register("gtmb:editor/kraft/" + name);
    }

    private static ThemeBuilder<?> definition() {
        ThemeBuilder<?> theme = new ThemeBuilder<>(ID).defaultColor(0xffffffff)
            .defaultIconColor(0xffffffff)
            .defaultTextColor(TEXT)
            .defaultTextShadow(false)
            .defaultTextHoverShadow(false)
            .defaultBackground(IDrawable.NONE)
            .background(IThemeApi.BUTTON, BUTTON)
            .hoverBackground(IThemeApi.BUTTON, HOVER)
            .textColor(IThemeApi.BUTTON, TEXT)
            .textHoverColor(IThemeApi.BUTTON, TEXT)
            .textShadow(IThemeApi.BUTTON, false)
            .textHoverShadow(IThemeApi.BUTTON, false)
            .widgetTheme(
                IThemeApi.TOGGLE_BUTTON,
                new SelectableTheme.Builder<>().background(BUTTON)
                    .selectedBackground(SELECTED)
                    .color(0xffffffff)
                    .selectedColor(0xffffffff)
                    .textColor(TEXT)
                    .selectedTextColor(TEXT)
                    .iconColor(0xffffffff)
                    .selectedIconColor(0xffffffff))
            .widgetThemeHover(
                IThemeApi.TOGGLE_BUTTON,
                new SelectableTheme.Builder<>().background(HOVER)
                    .selectedBackground(SELECTED_HOVER)
                    .color(0xffffffff)
                    .selectedColor(0xffffffff)
                    .textColor(TEXT)
                    .selectedTextColor(TEXT)
                    .iconColor(0xffffffff)
                    .selectedIconColor(0xffffffff))
            .widgetTheme(
                IThemeApi.TEXT_FIELD,
                new TextFieldTheme.Builder<>().background(INPUT)
                    .color(0xffffffff)
                    .textColor(TEXT)
                    .hintColor(MUTED)
                    .markedColor(0xffb5cdb0));
        // The 2.3.92 stack serializer serializes children by concrete type. Supply typed texture references explicitly
        // so the theme parser restores both the tiled paper and its adaptable frame, instead of empty child drawables.
        JsonArray panelLayers = new JsonArray();
        panelLayers.add(DrawableSerialization.serialize(PAPER));
        panelLayers.add(DrawableSerialization.serialize(FRAME));
        JsonObject panel = new JsonObject();
        panel.add(IThemeApi.BACKGROUND, panelLayers);
        theme.getJson()
            .add(IThemeApi.PANEL.getFullName(), panel);
        // MUI 2.3.92's selectedTextShadow builder takes int; its JSON property is nevertheless boolean.
        theme.getJson()
            .getAsJsonObject(IThemeApi.TOGGLE_BUTTON.getFullName())
            .addProperty(IThemeApi.SELECTED_TEXT_SHADOW, false);
        theme.getJson()
            .getAsJsonObject(IThemeApi.TOGGLE_BUTTON.getFullName() + IThemeApi.HOVER_SUFFIX)
            .addProperty(IThemeApi.SELECTED_TEXT_SHADOW, false);
        theme.textShadow(IThemeApi.TOGGLE_BUTTON, false)
            .textHoverShadow(IThemeApi.TOGGLE_BUTTON, false);
        return theme;
    }

    static UITexture background(boolean enabled, boolean selected, boolean hover, boolean primary) {
        if (!enabled) return DISABLED;
        if (primary) return hover ? PRIMARY_HOVER : PRIMARY;
        if (selected) return hover ? SELECTED_HOVER : SELECTED;
        return hover ? HOVER : BUTTON;
    }

    static TextWidget<?> label(Supplier<String> text) {
        return label(text, TEXT);
    }

    static TextWidget<?> label(Supplier<String> text, int color) {
        return IKey.dynamic(text)
            .color(HologramEditorTheme.textColor(color, false))
            .shadow(false)
            .asWidget()
            .textAlign(Alignment.CenterLeft);
    }

    static ButtonWidget<?> button(Supplier<String> text, Runnable action) {
        return button(text, null, action);
    }

    static ButtonWidget<?> button(Supplier<String> text, String icon, Runnable action) {
        return button(text, icon, action, YES, NO, false);
    }

    static ButtonWidget<?> primaryButton(Supplier<String> text, Runnable action) {
        return button(text, null, action, YES, NO, true);
    }

    static ButtonWidget<?> button(Supplier<String> text, String icon, Runnable action, BooleanSupplier enabled,
        BooleanSupplier selected, boolean primary) {
        return new PaperButton(text, icon, action, enabled, selected, primary, false);
    }

    static ButtonWidget<?> iconButton(String icon, Supplier<String> tooltip, Runnable action, BooleanSupplier enabled,
        BooleanSupplier selected) {
        return new PaperButton(tooltip, icon, action, enabled, selected, false, true);
    }

    static PageButton pageButton(int index, PagedWidget.Controller controller, Supplier<String> text) {
        return new PageButton(index, controller).overlay(
            IKey.dynamic(text)
                .shadow(false))
            .hoverBackgroundOverlay((IDrawable[]) null)
            .disableHoverThemeBackground(false)
            .tooltipBuilder(t -> t.addLine(text.get()))
            .tooltipAutoUpdate(true);
    }

    static TextFieldWidget textField(StringValue value, int maxLength) {
        TextFieldWidget field = new TextFieldWidget();
        return field
            .background(
                (context, x, y, width, height, theme) -> (field.isFocused() ? INPUT_FOCUS : INPUT)
                    .draw(context, x, y, width, height, theme))
            .hoverBackground(INPUT_FOCUS)
            .value(value)
            .setTextColor(TEXT)
            .hintColor(MUTED)
            .setMarkedColor(0xffb5cdb0)
            .setMaxLength(maxLength)
            .autoUpdateOnChange(true);
    }

    /** Append once while constructing a widget; keep its existing label/icon overlay and never serialize the effect. */
    static ButtonWidget<?> guided(ButtonWidget<?> button, IntSupplier accent, BooleanSupplier enabled) {
        button.overlay(
            button.getOverlay(),
            (context, x, y, width, height, theme) -> HologramEditorTheme.drawGuidanceFrame(
                x,
                y,
                width,
                height,
                accent.getAsInt(),
                enabled.getAsBoolean() && (!(button instanceof PaperButton) || ((PaperButton) button).available())));
        return button;
    }

    private static final class PaperButton extends ButtonWidget<PaperButton> {

        private boolean active = true;
        private final BooleanSupplier available;

        PaperButton(Supplier<String> text, String icon, Runnable action, BooleanSupplier enabled,
            BooleanSupplier selected, boolean primary, boolean iconOnly) {
            available = enabled;
            background(
                (context, x, y, width, height, theme) -> HologramEditorNativeTheme
                    .background(available(), selected.getAsBoolean(), false, primary)
                    .draw(context, x, y, width, height, theme));
            hoverBackground(
                (context, x, y, width, height, theme) -> HologramEditorNativeTheme
                    .background(available(), selected.getAsBoolean(), true, primary)
                    .draw(context, x, y, width, height, theme));
            IDrawable lettering = IKey.dynamic(text)
                .color(() -> !available() ? DISABLED_TEXT : primary ? DARK_TEXT : TEXT)
                .shadow(false);
            overlay((context, x, y, width, height, theme) -> {
                boolean hasIcon = HologramEditorIcons.contains(icon), dark = primary && available();
                if (hasIcon && iconOnly) {
                    int size = Math.min(width, height) >= 24 ? 24 : 12;
                    HologramEditorIcons.draw(
                        icon,
                        x + (width - size) / 2,
                        y + (height - size) / 2,
                        size,
                        dark,
                        available(),
                        selected.getAsBoolean(),
                        isHovering());
                } else if (hasIcon && height >= 24 && width >= 80) {
                    HologramEditorIcons.draw(
                        icon,
                        x + 3,
                        y + (height - 24) / 2,
                        24,
                        dark,
                        available(),
                        selected.getAsBoolean(),
                        isHovering());
                    lettering.draw(context, x + 30, y, width - 33, height, theme);
                } else lettering.draw(context, x, y, width, height, theme);
            });
            tooltipBuilder(t -> t.addLine(text.get())).tooltipAutoUpdate(true);
            onMousePressed(mouse -> {
                if (mouse != 0 || !available()) return false;
                action.run();
                return true;
            });
        }

        private boolean available() {
            return active && available.getAsBoolean();
        }

        @Override
        public PaperButton setEnabledIf(Predicate<PaperButton> condition) {
            return onUpdateListener(button -> active = condition.test(button), true);
        }

        @Override
        public Result onMousePressed(int button) {
            return available() ? super.onMousePressed(button) : Result.SUCCESS;
        }
    }
}
