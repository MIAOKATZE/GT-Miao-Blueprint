package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraftforge.common.MinecraftForge;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.IThemeApi;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.DrawableSerialization;
import com.cleanroommc.modularui.drawable.DrawableStack;
import com.cleanroommc.modularui.drawable.GuiDraw;
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
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Native controls using the blueprint-studio HTML's original R3 kraft artwork. */
public final class HologramEditorNativeTheme {

    static final String ID = "gtmb:editor_paper";
    static final int TEXT = HologramEditorTheme.TEXT, TITLE = HologramEditorTheme.TITLE,
        MUTED = HologramEditorTheme.MUTED;
    static final int DARK_TEXT = HologramEditorTheme.BUTTON_TEXT, DISABLED_TEXT = 0xffdbccb1;
    static final int ICON_SIZE = HologramEditorIcons.DISPLAY_SIZE, ICON_BUTTON = 16, TEXT_BUTTON_HEIGHT = 18,
        PRIMARY_BUTTON_HEIGHT = 22, TOOL_RAIL_WIDTH = 20;

    // MUI 2.3.92 recurses when serializing unregistered tiled/adaptable textures. Register the final objects first;
    // ThemeBuilder then serializes stable IDs, whose registry entries retain the actual tiling and nine-slice metadata.
    static final UITexture PAPER = UITexture.builder()
        .location("gtmb", "gui/editor/kraft/paper")
        .tiled(32, 32)
        .nonOpaque()
        .build()
        .withColorOverride(0xffffffff)
        .register("gtmb:editor/kraft/paper");
    private static final UITexture FRAME = texture("panel", 16, 3);
    static final IDrawable PANEL = new DrawableStack(PAPER, FRAME);
    static final UITexture INPUT = texture("input", 12, 2), INPUT_FOCUS = INPUT;
    static final UITexture BUTTON = texture("button", 12, 2), HOVER = texture("button-hover", 12, 2);
    static final UITexture SELECTED = texture("button-pressed", 12, 2),
        SELECTED_HOVER = texture("button-selected-hover", 12, 2);
    static final UITexture DISABLED = texture("button-disabled", 12, 2);
    static final UITexture PRIMARY = SELECTED, PRIMARY_HOVER = SELECTED_HOVER;
    static final UITexture SCROLLBAR = texture("scrollbar", 12, 2);
    static final IDrawable SCRIM = new Rectangle().color(0x4026292b);
    static final IDrawable DIVIDER = new Rectangle().color(0xffa78b63);
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

    private static UITexture texture(String name, int size, int border) {
        return UITexture.builder()
            .location("gtmb", "gui/editor/kraft/" + name)
            .imageSize(size, size)
            .adaptable(border)
            .nonOpaque()
            .build()
            .withColorOverride(0xffffffff)
            .register("gtmb:editor/kraft/" + name);
    }

    private static ThemeBuilder<?> definition() {
        ThemeBuilder<?> theme = new ThemeBuilder<>(ID).defaultColor(0xffffffff)
            .defaultHoverColor(0xffffffff)
            .defaultIconColor(0xffffffff)
            .defaultIconHoverColor(0xffffffff)
            .defaultTextColor(TEXT)
            .defaultTextHoverColor(TEXT)
            .defaultTextShadow(false)
            .defaultTextHoverShadow(false)
            .defaultBackground(IDrawable.NONE)
            .background(IThemeApi.BUTTON, BUTTON)
            .hoverBackground(IThemeApi.BUTTON, HOVER)
            .textColor(IThemeApi.BUTTON, DARK_TEXT)
            .textHoverColor(IThemeApi.BUTTON, DARK_TEXT)
            .textShadow(IThemeApi.BUTTON, true)
            .textHoverShadow(IThemeApi.BUTTON, true)
            .background(IThemeApi.CLOSE_BUTTON, BUTTON)
            .hoverBackground(IThemeApi.CLOSE_BUTTON, HOVER)
            .textColor(IThemeApi.CLOSE_BUTTON, DARK_TEXT)
            .textHoverColor(IThemeApi.CLOSE_BUTTON, DARK_TEXT)
            .background(IThemeApi.SCROLLBAR, SCROLLBAR)
            .widgetTheme(
                IThemeApi.TOGGLE_BUTTON,
                new SelectableTheme.Builder<>().background(BUTTON)
                    .selectedBackground(SELECTED)
                    .color(0xffffffff)
                    .selectedColor(0xffffffff)
                    .textColor(DARK_TEXT)
                    .selectedTextColor(DARK_TEXT)
                    .iconColor(0xffffffff)
                    .selectedIconColor(0xffffffff))
            .widgetThemeHover(
                IThemeApi.TOGGLE_BUTTON,
                new SelectableTheme.Builder<>().background(HOVER)
                    .selectedBackground(SELECTED_HOVER)
                    .color(0xffffffff)
                    .selectedColor(0xffffffff)
                    .textColor(DARK_TEXT)
                    .selectedTextColor(DARK_TEXT)
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
            .addProperty(IThemeApi.SELECTED_TEXT_SHADOW, true);
        theme.getJson()
            .getAsJsonObject(IThemeApi.TOGGLE_BUTTON.getFullName() + IThemeApi.HOVER_SUFFIX)
            .addProperty(IThemeApi.SELECTED_TEXT_SHADOW, true);
        theme.textShadow(IThemeApi.TOGGLE_BUTTON, true)
            .textHoverShadow(IThemeApi.TOGGLE_BUTTON, true);
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
            .color(color)
            .shadow(false)
            .asWidget()
            .textAlign(Alignment.CenterLeft);
    }

    /** Recalculate descendants after a hidden slot changes the space assigned to an expanded sibling. */
    static Flow collapsingFlow(GuiAxis axis) {
        return new Flow(axis) {

            @Override
            public void onChildChangeEnabled(IWidget child, boolean enabled) {
                super.onChildChangeEnabled(child, enabled);
                // 2.3.92 Flow only relays out its immediate children here. The viewport scene and labels also
                // depend on the new expanded width, so they need the normal full resize pass on the next frame.
                scheduleResize();
            }
        }.collapseDisabledChild(true);
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

    /** HTML tree/library row: paper shows through the wash; selection uses a warm gold inset. */
    static ButtonWidget<?> rowButton(Supplier<String> text, String icon, Runnable action, BooleanSupplier enabled,
        BooleanSupplier selected) {
        return new PaperButton(text, icon, action, enabled, selected, false, false, Surface.ROW);
    }

    static ButtonWidget<?> rowButton(Supplier<String> text, Runnable action, BooleanSupplier selected) {
        return rowButton(text, null, action, YES, selected);
    }

    /** HTML flat controls, for visibility/disclosure glyphs placed on paper. */
    static ButtonWidget<?> flatIconButton(String icon, Supplier<String> tooltip, Runnable action,
        BooleanSupplier enabled, BooleanSupplier selected) {
        return new PaperButton(tooltip, icon, action, enabled, selected, false, true, Surface.FLAT);
    }

    static PageButton pageButton(int index, PagedWidget.Controller controller, Supplier<String> text) {
        return new PageButton(index, controller).overlay(
            IKey.dynamic(text)
                .color(DARK_TEXT)
                .shadow(true))
            .hoverBackgroundOverlay((IDrawable[]) null)
            .disableHoverThemeBackground(false)
            .tooltipBuilder(
                t -> t.addLine(
                    IKey.str(text.get())
                        .color(DARK_TEXT)
                        .shadow(false)))
            .tooltipAutoUpdate(true);
    }

    static TextFieldWidget textField(StringValue value, int maxLength) {
        TextFieldWidget field = new TextFieldWidget();
        return field.background((context, x, y, width, height, theme) -> {
            INPUT.draw(context, x, y, width, height, theme);
            if (field.isFocused()) drawInputFocus(x, y, width, height);
        })
            .disableHoverBackground()
            .value(value)
            .setTextColor(TEXT)
            .hintColor(MUTED)
            .setMarkedColor(0xffb5cdb0)
            .setMaxLength(maxLength)
            .autoUpdateOnChange(true);
    }

    private static void drawInputFocus(int x, int y, int width, int height) {
        // HTML :focus-visible outline, kept inside the field so it cannot bleed into an adjacent control.
        GuiDraw.drawBorderInsideXYWH(x, y, width, height, 0xffb57a24);
    }

    private enum Surface {
        BUTTON,
        ROW,
        FLAT
    }

    private static void drawPaperControl(int x, int y, int width, int height, boolean enabled, boolean selected,
        boolean hover, Surface surface) {
        if (surface == Surface.FLAT) {
            if (enabled && (hover || selected)) GuiDraw.drawRect(x, y, width, height, 0x339b773e);
            return;
        }
        // These are the HTML .tree-row/.library-item colors; no extra paper texture is stretched into the row.
        int wash = selected ? 0x8cf9df9d : hover && enabled ? 0x40fff6d8 : 0x1af1deb3;
        int border = selected ? 0xff6c471b : hover && enabled ? 0xff8e683d : 0xffa98b60;
        GuiDraw.drawRect(x, y, width, height, wash);
        GuiDraw.drawBorderInsideXYWH(x, y, width, height, border);
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
            this(text, icon, action, enabled, selected, primary, iconOnly, Surface.BUTTON);
        }

        PaperButton(Supplier<String> text, String icon, Runnable action, BooleanSupplier enabled,
            BooleanSupplier selected, boolean primary, boolean iconOnly, Surface surface) {
            available = enabled;
            background((context, x, y, width, height, theme) -> {
                if (surface == Surface.BUTTON)
                    HologramEditorNativeTheme.background(available(), selected.getAsBoolean(), false, primary)
                        .draw(context, x, y, width, height, theme);
                else drawPaperControl(x, y, width, height, available(), selected.getAsBoolean(), false, surface);
            });
            hoverBackground((context, x, y, width, height, theme) -> {
                if (surface == Surface.BUTTON)
                    HologramEditorNativeTheme.background(available(), selected.getAsBoolean(), true, primary)
                        .draw(context, x, y, width, height, theme);
                else drawPaperControl(x, y, width, height, available(), selected.getAsBoolean(), true, surface);
            });
            boolean dark = surface == Surface.BUTTON;
            IDrawable lettering = IKey.dynamic(text)
                .color(() -> !available() ? dark ? DISABLED_TEXT : MUTED : dark ? DARK_TEXT : TEXT)
                .shadow(dark)
                .alignment(surface == Surface.ROW ? Alignment.CenterLeft : Alignment.Center);
            overlay((context, x, y, width, height, theme) -> {
                boolean hasIcon = HologramEditorIcons.contains(icon);
                // The atlas is authored on a 12 GUI pixel grid. Keep ordinary glyphs at their native size,
                // including primary actions, so tighter button padding never introduces fractional scaling.
                int size = ICON_SIZE;
                if (hasIcon && iconOnly) {
                    HologramEditorIcons.draw(
                        icon,
                        x + (width - size) / 2,
                        y + (height - size) / 2,
                        size,
                        dark,
                        available(),
                        selected.getAsBoolean(),
                        isHovering());
                } else if (hasIcon && height >= 18 && width >= 60) {
                    HologramEditorIcons.draw(
                        icon,
                        x + 4,
                        y + (height - size) / 2,
                        size,
                        dark,
                        available(),
                        selected.getAsBoolean(),
                        isHovering());
                    lettering.draw(context, x + size + 8, y, width - size - 12, height, theme);
                } else {
                    int inset = surface == Surface.ROW ? 5 : 0;
                    lettering.draw(context, x + inset, y, width - inset * 2, height, theme);
                }
            });
            tooltipBuilder(
                t -> t.addLine(
                    IKey.str(text.get())
                        .color(DARK_TEXT)
                        .shadow(false))).tooltipAutoUpdate(true);
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
