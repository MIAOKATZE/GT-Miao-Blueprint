package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.Loader;

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
        "(?:Ctrl\\s*\\+\\s*Shift|Shift\\s*\\+\\s*Alt|Shift|Alt)\\s*\\+\\s*(?:right-click|right|右击|右键|右鍵)"
            + "|Ctrl\\s*\\+\\s*Shift|Ctrl\\s*\\+\\s*[ZR]|Alt\\s*\\+\\s*(?:Up/Down|上下|滚轮|scroll)"
            + "|Shift(?:\\s*\\+\\s*(?:右击|右键|右鍵)|-right-click)|Alt-right-click"
            + "|Left/right|[Rr]ight-click|左键|右键|右鍵|右击|方向键|Arrows|\\b(?:Ctrl|Alt|Esc|R)\\b");

    private HologramWorldGuide() {}

    static void clearRenderWitness() {
        lastRenderWitness = null;
    }

    static RenderWitness lastRenderWitness() {
        return lastRenderWitness;
    }

    static String selectionCursorKey(NBTTagCompound selection) {
        int pending = selection.getInteger("areaPendingCorner");
        if (pending == 1) return "worldGuideSelectionSecond";
        if (pending == 2) return "worldGuideSelectionFirst";
        if (selection.getIntArray("areaPoints").length == 6) return "worldGuideSelectionDone";
        return selection.getBoolean("areaMore") ? "worldGuideSelectionMore" : "worldGuideSelectionBoth";
    }

    static String selectionCursorText(NBTTagCompound selection) {
        if (selection.getBoolean("areaContinuous")) {
            String stage = selection.getBoolean("areaPolygonClosed")
                ? selection.getBoolean("areaPolygonHeightChosen") ? "continuousReady" : "continuousHeight"
                : "continuousLine";
            return modeText(selection.getString("areaPolygonMode")) + " · "
                + String.format(
                    java.util.Locale.ROOT,
                    HologramClient.text(stage, "%s / %s"),
                    HologramSelectionInput.continuousLabel(),
                    HologramIsometricCamera.INSTANCE.toggleLabel());
        }
        if (HologramIsometricCamera.INSTANCE.active()) {
            return modeText(selection.getString("areaInputMode")) + " · "
                + HologramClient
                    .text("camera.selectionCorners", "Ctrl+left: point 1 · Ctrl+right: point 2 · Shift+left: submit");
        }
        String key = selectionCursorKey(selection);
        String fallback = "worldGuideSelectionSecond".equals(key) ? "Right: point 2"
            : "worldGuideSelectionFirst".equals(key) ? "Left: point 1"
                : "worldGuideSelectionDone".equals(key) ? "Selected"
                    : "worldGuideSelectionMore".equals(key) ? "More boxes · Left: point 1 · Right: point 2"
                        : "Left: point 1 · Right: point 2";
        return modeText(selection.getString("areaInputMode")) + " · " + HologramClient.text(key, fallback);
    }

    private static String modeText(String mode) {
        return HologramClient.text("selectionMode." + (mode.isEmpty() ? "replace" : mode), mode);
    }

    static void selectionGuide(List<String> lines, NBTTagCompound selection) {
        if (HologramIsometricCamera.INSTANCE.active()) {
            if (selection != null && selection.getBoolean("areaContinuous")) {
                lines.add(selectionCursorText(selection));
                lines.add(HologramClient.text("camera.continuousKeys", "Left: vertex / height · Ctrl+click: ignored"));
            } else {
                lines.add(
                    HologramClient.text(
                        "camera.selectionCorners",
                        "Ctrl+left: point 1 · Ctrl+right: point 2 · Shift+left: submit"));
                lines.add(
                    String.format(
                        java.util.Locale.ROOT,
                        HologramClient.text(
                            "camera.selectionEdit",
                            "%s: continuous · Shift+%s: subtract · Z / Alt+Z: add / subtract · Ctrl+Z/Y: undo / redo"),
                        HologramSelectionInput.continuousLabel(),
                        HologramSelectionInput.continuousLabel()));
            }
            return;
        }
        lines.add(
            HologramClient.text(
                HologramIsometricCamera.INSTANCE.active() ? "worldGuideSelectionEdit.camera"
                    : "worldGuideSelectionEdit",
                "Z: add · Alt+Z: subtract · Shift+Z: merge"));
        lines.add(HologramClient.text("worldGuideSelectionKeys", "Ctrl+Z: undo · Ctrl+Y: redo"));
        lines.add(
            String.format(
                java.util.Locale.ROOT,
                HologramClient.text("continuousGuide", "%s: continuous selection · Shift+%s: subtract · %s: finish"),
                HologramSelectionInput.continuousLabel(),
                HologramSelectionInput.continuousLabel(),
                HologramIsometricCamera.INSTANCE.toggleLabel()));
        if (selection != null && selection.getBoolean("areaContinuous")) lines.add(selectionCursorText(selection));
    }

    static void drawSelectionCursor(NBTTagCompound selection, ScaledResolution resolution) {
        if (HologramIsometricCamera.INSTANCE.blocksWorldInput()) return;
        Minecraft mc = Minecraft.getMinecraft();
        String message = selectionCursorText(selection);
        int x = resolution.getScaledWidth() / 2 + 12, y = resolution.getScaledHeight() / 2 + 10;
        if (HologramIsometricCamera.INSTANCE.active() && Mouse.isCreated()) {
            x = Mouse.getX() * resolution.getScaledWidth() / mc.displayWidth + 12;
            y = resolution.getScaledHeight() - Mouse.getY() * resolution.getScaledHeight() / mc.displayHeight + 10;
        }
        x = Math.max(4, Math.min(x, resolution.getScaledWidth() - mc.fontRenderer.getStringWidth(message) - 6));
        y = Math.max(4, Math.min(y, resolution.getScaledHeight() - mc.fontRenderer.FONT_HEIGHT - 4));
        mc.fontRenderer.drawStringWithShadow(message, x, y, 0x7FFFFF);
    }

    static void draw(List<String> messages, ScaledResolution resolution) {
        if (messages.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        int available = Math.max(40, resolution.getScaledWidth() - 24);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String styled = highlight(messages.get(i), i == 0 ? "\u00a7b" : "\u00a77");
            rows.addAll(font.listFormattedStringToWidth(styled, available));
        }
        int width = 0;
        for (String row : rows) width = Math.max(width, font.getStringWidth(row));
        // Leave room for Waila's standard top block tooltip without linking its optional renderer.
        boolean blockTooltip = Loader.isModLoaded("Waila") && mc.objectMouseOver != null
            && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        int top = blockTooltip ? 40 : 8;
        int x = (resolution.getScaledWidth() - width) / 2, y = top;
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
            top,
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
