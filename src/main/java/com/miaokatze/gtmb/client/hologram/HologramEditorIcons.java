package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.UITexture;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Original 12px artwork. Geometry and tooltip/click ownership stay with the native widget. */
final class HologramEditorIcons {

    static final int SOURCE_SIZE = 12, DISPLAY_SIZE = SOURCE_SIZE;
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
    private static final Map<String, boolean[]> MASKS = new HashMap<>();
    private static final ResourceLocation GLINT = new ResourceLocation("textures/misc/enchanted_item_glint.png");

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

    /** Vanilla's two moving enchantment passes, clipped to the actual opaque source pixels. */
    static void drawGlint(String name, int x, int y, int size, int accent) {
        if (accent == 0 || !contains(name) || size < SOURCE_SIZE) return;
        boolean[] mask = MASKS.computeIfAbsent(name, HologramEditorIcons::readMask);
        int color = HologramUiAccent.color(accent), scale = Math.max(1, size / SOURCE_SIZE);
        int originX = x + (size - SOURCE_SIZE * scale) / 2;
        int originY = y + (size - SOURCE_SIZE * scale) / 2;
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
            GL11.glDepthMask(false);
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(GLINT);
            GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1f);
            double seconds = Minecraft.getSystemTime() / 1000d;
            for (int pass = 0; pass < 2; pass++) {
                double offset = seconds * (pass == 0 ? .12 : -.08) % 1d;
                Tessellator tess = Tessellator.instance;
                tess.startDrawingQuads();
                for (int py = 0; py < SOURCE_SIZE; py++) for (int px = 0; px < SOURCE_SIZE; px++) {
                    if (!mask[py * SOURCE_SIZE + px]) continue;
                    for (int corner = 0; corner < 4; corner++) {
                        int dx = corner >= 2 ? 1 : 0, dy = corner == 1 || corner == 2 ? 1 : 0;
                        double u = (px + dx + (pass == 0 ? py + dy : -py - dy)) / 24d + offset;
                        double v = (py + dy) / 24d;
                        tess.addVertexWithUV(originX + (px + dx) * scale, originY + (py + dy) * scale, 0, u, v);
                    }
                }
                tess.draw();
            }
        }
    }

    private static boolean[] readMask(String name) {
        boolean[] mask = new boolean[SOURCE_SIZE * SOURCE_SIZE];
        ResourceLocation resource = new ResourceLocation("gtmb", "textures/gui/editor/icons/normal/" + name + ".png");
        try (InputStream input = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(resource)
            .getInputStream()) {
            BufferedImage image = ImageIO.read(input);
            if (image != null) for (int y = 0; y < SOURCE_SIZE; y++)
                for (int x = 0; x < SOURCE_SIZE; x++) mask[y * SOURCE_SIZE + x] = (image.getRGB(x, y) >>> 24) >= 128;
        } catch (java.io.IOException ignored) {
            // Missing artwork already has Minecraft's missing-texture handling; never fill the button with glint.
        }
        return mask;
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
