package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import com.cleanroommc.modularui.api.IThemeApi;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.drawable.DrawableSerialization;
import com.cleanroommc.modularui.drawable.DrawableStack;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.theme.ThemeBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Runs against real MUI serialization in the existing MinecraftTestBootstrap; never creates a screen or GL context. */
public final class HologramEditorNativeThemeRegression {

    private HologramEditorNativeThemeRegression() {}

    public static void run() {
        try {
            // The headless suite does not execute MUI's client proxy preInit. Initialize its real serializer types
            // once.
            Field types = DrawableSerialization.class.getDeclaredField("DRAWABLE_TYPES");
            types.setAccessible(true);
            if (((Map<?, ?>) types.get(null)).isEmpty()) DrawableSerialization.init();

            Field definitionField = HologramEditorNativeTheme.class.getDeclaredField("DEFINITION");
            definitionField.setAccessible(true);
            ThemeBuilder<?> definition = (ThemeBuilder<?>) definitionField.get(null);
            check(definition != null, "native definition initializes without recursive texture serialization");
            IThemeApi.get()
                .registerTheme(definition);
            check(
                IThemeApi.get()
                    .getJavaDefaultThemes(HologramEditorNativeTheme.ID)
                    .contains(definition),
                "the actual native theme definition is accepted by the actual MUI registry");

            int textures = 0;
            for (Field field : HologramEditorNativeTheme.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != UITexture.class) continue;
                field.setAccessible(true);
                UITexture texture = (UITexture) field.get(null);
                String id = DrawableSerialization.getTextureId(texture);
                check(
                    id != null && id.startsWith("gtmb:editor/kraft/"),
                    field.getName() + " has a stable own-domain ID");
                JsonObject encoded = DrawableSerialization.serialize(texture)
                    .getAsJsonObject();
                check(
                    "texture".equals(
                        encoded.get("type")
                            .getAsString())
                        && id.equals(
                            encoded.get("id")
                                .getAsString()),
                    field.getName() + " serializes as a typed ID reference without expansion/recursion");
                check(
                    DrawableSerialization.getTexture(id) == texture
                        && DrawableSerialization.deserialize(encoded) == texture,
                    field.getName() + " decodes to the exact registered drawable, preserving its native geometry");
                if (field.getName()
                    .equals("PAPER")) {
                    check(
                        texture.getClass()
                            .getSimpleName()
                            .equals("TiledUITexture"),
                        "paper remains a real tiled texture");
                    check(
                        number(texture, "imageWidth") == 32 && number(texture, "imageHeight") == 32,
                        "paper uses the HTML's original 32px tile dimensions");
                } else {
                    boolean panel = field.getName()
                        .equals("FRAME");
                    int sourceSize = panel ? 16 : 12, border = panel ? 3 : 2;
                    check(
                        texture.getClass()
                            .getSimpleName()
                            .equals("AdaptableUITexture"),
                        field.getName() + " remains nine-sliced");
                    check(
                        number(texture, "imageWidth") == sourceSize && number(texture, "imageHeight") == sourceSize,
                        field.getName() + " retains the original HTML asset's source dimensions");
                    for (String edge : new String[] { "bl", "bt", "br", "bb" })
                        check(number(texture, edge) == border, field.getName() + " retains its original slice " + edge);
                }
                textures++;
            }
            check(textures >= 11, "all native paper, input and button variants were covered");
            check(
                HologramEditorNativeTheme.BUTTON.location.getResourcePath()
                    .endsWith("/kraft/button.png")
                    && HologramEditorNativeTheme.SELECTED.location.getResourcePath()
                        .endsWith("/kraft/button-pressed.png"),
                "native buttons reuse the HTML's dark R3 artwork");
            check(
                HologramEditorNativeTheme.PRIMARY == HologramEditorNativeTheme.SELECTED
                    && HologramEditorNativeTheme.PRIMARY_HOVER == HologramEditorNativeTheme.SELECTED_HOVER,
                "HTML primary buttons use the pressed states instead of a separate visual family");

            JsonObject json = definition.getJson();
            JsonArray layers = json.getAsJsonObject(IThemeApi.PANEL.getFullName())
                .getAsJsonArray(IThemeApi.BACKGROUND);
            check(layers.size() == 2, "panel JSON has both paper and frame layers");
            for (JsonElement layer : layers) checkTextureReference(layer);
            IDrawable restoredPanel = DrawableSerialization.deserialize(layers);
            check(restoredPanel instanceof DrawableStack, "real MUI deserializes the panel as a drawable stack");
            IDrawable[] children = ((DrawableStack) restoredPanel).getDrawables();
            check(
                children.length == 2 && children[0] == HologramEditorNativeTheme.PAPER,
                "panel stack keeps its tiled paper bottom layer");
            check(
                children[1] == DrawableSerialization.getTexture("gtmb:editor/kraft/panel"),
                "panel stack keeps its real nine-sliced frame above the paper");
            for (String key : new String[] { IThemeApi.BUTTON.getFullName(),
                IThemeApi.BUTTON.getFullName() + IThemeApi.HOVER_SUFFIX, IThemeApi.TEXT_FIELD.getFullName() })
                checkTextureReference(
                    json.getAsJsonObject(key)
                        .get(IThemeApi.BACKGROUND));
            for (String key : new String[] { IThemeApi.TOGGLE_BUTTON.getFullName(),
                IThemeApi.TOGGLE_BUTTON.getFullName() + IThemeApi.HOVER_SUFFIX }) {
                JsonObject state = json.getAsJsonObject(key);
                checkTextureReference(state.get(IThemeApi.BACKGROUND));
                checkTextureReference(state.get(IThemeApi.SELECTED_BACKGROUND));
                check(
                    state.get(IThemeApi.SELECTED_TEXT_SHADOW)
                        .getAsBoolean(),
                    "selected button retains the HTML's text shadow");
                check(
                    state.get(IThemeApi.TEXT_COLOR)
                        .getAsInt() == HologramEditorNativeTheme.DARK_TEXT
                        && state.get(IThemeApi.SELECTED_TEXT_COLOR)
                            .getAsInt() == HologramEditorNativeTheme.DARK_TEXT,
                    "both toggle states use readable cream lettering on the dark HTML button artwork");
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect the actual native MUI theme definition", failure);
        }
    }

    private static int number(UITexture texture, String name) throws ReflectiveOperationException {
        Field field = texture.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(texture);
    }

    private static void checkTextureReference(JsonElement element) {
        check(element != null && element.isJsonObject(), "state background is a typed texture object");
        JsonObject object = element.getAsJsonObject();
        check(
            object.has("type") && "texture".equals(
                object.get("type")
                    .getAsString())
                && object.has("id"),
            "state background is a typed texture ID, not runtime empty");
        String id = object.get("id")
            .getAsString();
        check(DrawableSerialization.getTexture(id) != null, "state background ID resolves to a real texture");
        check(
            DrawableSerialization.deserialize(object) == DrawableSerialization.getTexture(id),
            "real serializer restores the registered background");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
