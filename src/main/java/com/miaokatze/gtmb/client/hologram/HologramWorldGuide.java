package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.opengl.GL11;

/** Contextual world guidance shares one centered, width-bounded top panel. */
final class HologramWorldGuide {

    static final class RenderWitness {

        final int centerX, top, width, height;
        final List<String> rows;

        RenderWitness(int centerX, int top, int width, int height, List<String> rows) {
            this.centerX = centerX;
            this.top = top;
            this.width = width;
            this.height = height;
            this.rows = java.util.Collections.unmodifiableList(new ArrayList<>(rows));
        }
    }

    private static RenderWitness lastRenderWitness;

    private static final Pattern KEYS = Pattern.compile(
        "Ctrl\\s*\\+\\s*Shift|Ctrl\\s*\\+\\s*[ZR]|Alt\\s*\\+\\s*(?:Up/Down|上下|滚轮|scroll)"
            + "|Shift(?:\\s*\\+\\s*(?:右击|右键)|-right-click)|Alt-right-click"
            + "|Left/right|[Rr]ight-click|左键|右键|右击|方向键|Arrows|\\b(?:Ctrl|Alt|Esc|R)\\b");

    private HologramWorldGuide() {}

    static void clearRenderWitness() {
        lastRenderWitness = null;
    }

    static RenderWitness lastRenderWitness() {
        return lastRenderWitness;
    }

    static void draw(List<String> messages, ScaledResolution resolution) {
        if (messages.isEmpty()) return;
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        int available = Math.max(40, resolution.getScaledWidth() - 24);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String styled = highlight(messages.get(i), i == 0 ? "\u00a7b" : "\u00a77");
            rows.addAll(font.listFormattedStringToWidth(styled, available));
        }
        int width = 0;
        for (String row : rows) width = Math.max(width, font.getStringWidth(row));
        int x = (resolution.getScaledWidth() - width) / 2, y = 8;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            Gui.drawRect(x - 5, y - 4, x + width + 5, y + rows.size() * (font.FONT_HEIGHT + 2), 0x99000000);
            for (String row : rows) {
                font.drawStringWithShadow(
                    row,
                    (resolution.getScaledWidth() - font.getStringWidth(row)) / 2,
                    y,
                    0xFFFFFF);
                y += font.FONT_HEIGHT + 2;
            }
        } finally {
            GL11.glPopAttrib();
        }
        lastRenderWitness = new RenderWitness(
            resolution.getScaledWidth() / 2,
            8,
            width,
            rows.size() * (font.FONT_HEIGHT + 2),
            rows);
    }

    static String highlight(String message, String baseColor) {
        Matcher matcher = KEYS.matcher(message);
        StringBuffer highlighted = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(
            highlighted,
            Matcher.quoteReplacement("\u00a7e" + matcher.group() + "\u00a7r" + baseColor));
        matcher.appendTail(highlighted);
        return baseColor + highlighted;
    }
}
