package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/** One authored pixel banner, shared with the web export; its own filled pixels define the tooltip region. */
final class HologramVoucherRibbon {

    static final String TITLE = "§6纯装饰蓝图 · 完全抵用";
    static final String HELP = "该蓝图为纯装饰蓝图，可使用猫猫装饰抵用券抵扣建造材料；具体可抵扣范围与消耗以当前服务器规则和材料核算为准。";
    static final int PIXELS = 28;
    static final int[] COLORS = { 0x00000000, 0xff79532c, 0xffb48038, 0xffe4b75b, 0xffffe7a0, 0xff74421e, 0xfff5cc6e };
    static final String[] ART = { "0142233366663333666633334100", "0014223666633336666333366410",
        "0001422666333311663333611641", "0000142263333156133331651334", "0000014223336156611116651333",
        "0000001422366156666666651336", "0000000142266156566656651366", "0000000014226156666666651666",
        "0000000001422116666666611666", "0000000000142556666566655563", "0000000000014156665656651633",
        "0000000000001425666566566333", "0000000000000142155551663333", "0000000000000014223666633336",
        "0000000000000001422666333366", "0000000000000000142263333666", "0000000000000000014223336666",
        "0000000000000000001422366663", "0000000000000000000142266633", "0000000000000000000014226333",
        "0000000000000000000001422333", "0000000000000000000000142236", "0000000000000000000000014226",
        "0000000000000000000000001422", "0000000000000000000000000142", "0000000000000000000000000014",
        "0000000000000000000000000001", "0000000000000000000000000000" };

    private HologramVoucherRibbon() {}

    static boolean contains(float x, float y, float width, float height, float mouseX, float mouseY) {
        float side = Math.min(PIXELS, Math.min(width, height));
        if (side <= 0) return false;
        int px = (int) Math.floor((mouseX - (x + width - side)) * PIXELS / side);
        int py = (int) Math.floor((mouseY - y) * PIXELS / side);
        return px >= 0 && py >= 0 && px < PIXELS && py < PIXELS && ART[py].charAt(px) != '0';
    }

    static void draw(float x, float y, float width, float height) {
        float side = Math.min(PIXELS, Math.min(width, height)), unit = side / PIXELS;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            for (int py = 0; py < PIXELS; py++) for (int px = 0; px < PIXELS; px++) {
                int index = ART[py].charAt(px) - '0';
                if (index == 0) continue;
                tessellator.setColorOpaque_I(COLORS[index] & 0xffffff);
                float left = x + width - side + px * unit, top = y + py * unit;
                tessellator.addVertex(left, top, 2);
                tessellator.addVertex(left + unit, top, 2);
                tessellator.addVertex(left + unit, top + unit, 2);
                tessellator.addVertex(left, top + unit, 2);
            }
            tessellator.draw();
        } finally {
            GL11.glPopAttrib();
        }
    }
}
