package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.cleanroommc.modularui.drawable.UITexture;

/** Original 12px artwork. Geometry and tooltip/click ownership stay with the native widget. */
final class HologramEditorIcons {

    static final int SOURCE_SIZE = 12, DISPLAY_SIZE = 24;
    private static final Set<String> NAMES = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "back",
                "box-select",
                "cancel",
                "clear",
                "close",
                "collapse-down",
                "collapse-left",
                "collapse-right",
                "collapse-up",
                "confirm",
                "copy",
                "cut",
                "delete",
                "detach",
                "export",
                "help",
                "help-circle",
                "hidden",
                "image",
                "import",
                "info",
                "layer",
                "library",
                "material",
                "move",
                "network",
                "palette",
                "pan",
                "pause",
                "pin",
                "place",
                "process",
                "quantum",
                "redo",
                "reset-view",
                "rotate",
                "save",
                "select",
                "settings",
                "storage",
                "structure",
                "text",
                "tutorial",
                "undo",
                "visible",
                "warning",
                "world",
                "xray",
                "zoom-in",
                "zoom-out")));
    private static final Map<String, UITexture> CACHE = new HashMap<>();

    private HologramEditorIcons() {}

    static boolean contains(String name) {
        return name != null && NAMES.contains(name);
    }

    static UITexture texture(String name, boolean onDark, boolean enabled, boolean selected, boolean hover) {
        if (name == null || !NAMES.contains(name)) return null;
        String state = !enabled ? "disabled" : selected ? "active" : hover ? "hover" : "normal";
        String path = "icons/" + (onDark ? "on-dark/" : "") + state + "/" + name;
        return CACHE.computeIfAbsent(path, key -> HologramEditorTheme.texture(key, SOURCE_SIZE, SOURCE_SIZE, -1));
    }

    /** Snap the art to whole source-pixel multiples; never resize a hit target to match its icon. */
    static void draw(String name, int x, int y, int size, boolean onDark, boolean enabled, boolean selected,
        boolean hover) {
        UITexture texture = texture(name, onDark, enabled, selected, hover);
        if (texture == null || size < SOURCE_SIZE) return;
        int pixels = Math.max(1, size / SOURCE_SIZE) * SOURCE_SIZE;
        texture.draw((float) x + (size - pixels) / 2, y + (size - pixels) / 2, pixels, pixels);
    }

    /** Known native action identities only; unknown controls retain their text instead of guessing. */
    static String forControl(int id, String label) {
        return switch (id) {
            case 10, 11, 12, 13 -> "layer";
            case 15 -> "visible";
            case 26 -> "material";
            case 50, 55, 141 -> "place";
            case 51 -> "pause";
            case 52, 54, 299 -> "cancel";
            case 108 -> "undo";
            case 109 -> "redo";
            case 112 -> "copy";
            case 113, 223 -> "rotate";
            case 130, 134, 149 -> "settings";
            case 132, 140, 175, 221 -> "save";
            case 139 -> "select";
            case 146 -> "info";
            case 147 -> "move";
            case 148, 163 -> "structure";
            case 159 -> "export";
            case 161 -> "import";
            case 178 -> "box-select";
            case 179 -> "storage";
            case 220 -> "process";
            case 309, 310 -> "library";
            case 331 -> "text";
            case 332 -> "image";
            default -> null;
        };
    }
}
