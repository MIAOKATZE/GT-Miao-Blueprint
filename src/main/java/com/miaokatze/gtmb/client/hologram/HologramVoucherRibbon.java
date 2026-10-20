package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/**
 * Pixel ribbon extending the original voucher sprite, shared with the web export; its own filled pixels define the
 * tooltip region.
 */
final class HologramVoucherRibbon {

    static final String TITLE = "§6纯装饰蓝图 · 完全抵用";
    static final String HELP = "该蓝图为纯装饰蓝图，可使用猫猫装饰抵用券抵扣建造材料；具体可抵扣范围与消耗以当前服务器规则和材料核算为准。";
    static final int PIXELS = 28;
    // Original decoration_voucher.png frame 0: 142 unchanged cat pixels, rotated clockwise by 45 degrees.
    // Sprite SHA-256: 1f290a4b1490c50b1aaad3ce24e785240002b7492224df7051c14d8d1da5513e
    static final String PIXEL_ENCODING = "hex-index";
    static final float CAT_SCALE = 0.8425f, CAT_CENTER_X = 19f, CAT_CENTER_Y = 9f;
    static final double CAT_TURN = Math.sqrt(0.5);
    static final int[] COLORS = { 0x00000000, 0xff752a08, 0xffc0801c, 0xffffdf73, 0xfffff2b0, 0xffe3ab36, 0xfffed63d,
        0xff9c3c0a, 0xff8b3208, 0xff8b330d, 0xfff3a44d, 0xff93360a, 0xff903509, 0xff7e2f06, 0xff822c05, 0xff7c2907,
        0xff803400, 0xffee8819, 0xfffedf49, 0xff853210, 0xfffdf47d, 0xfffddf4b, 0xffffdc4a, 0xfffdd945, 0xfffed641,
        0xffe98009, 0xffea8614, 0xfffae860, 0xfffde34e, 0xfffee04e, 0xfffddf49, 0xffffdd46, 0xfffedb43, 0xfffdd842,
        0xffffd33e, 0xfffcd33d, 0xff883209, 0xff923911, 0xfffedd50, 0xfffddd4a, 0xff8d3609, 0xfffdd540, 0xfffed53d,
        0xfffece38, 0xffaa5f0c, 0xffffe562, 0xfffed640, 0xfffdd33f, 0xfffdd03f, 0xfffdc935, 0xfffec42f, 0xff872f07,
        0xff6f2502, 0xfffcd844, 0xfffed73e, 0xfffece3a, 0xfffecb34, 0xfffdca33, 0xfff9d84d, 0xffffe052, 0xfffed53b,
        0xfffdd43e, 0xfffdcf3c, 0xff7d2b04, 0xfffedb39, 0xfffec331, 0xfffec230, 0xfffcca3f, 0xfffebd2b, 0xff7d2a08,
        0xffffcf37, 0xfffdbf2e, 0xfffcba28, 0xfff6a51e, 0xfffecc37, 0xfffdc937, 0xffffc931, 0xfffdbf2c, 0xfffcbe2d,
        0xfffeb92a, 0xfff9b62a, 0xff772406, 0xfffec72f, 0xfffdc029, 0xffffbb2a, 0xfffeb926, 0xfffdb523, 0xff732809,
        0xff772905, 0xff782a06, 0xff772807, 0xff772a08, 0xff752a0a };
    static final String[] ART = { "01040303030303030303030303030303030303040100000000000000",
        "00010403030303030303030303030303030303030401000000000000",
        "00000104030303030303030303030303030303030304010000000000",
        "00000001040303030303030303030303030303030303040100000000",
        "00000000010403030303030303030303030303030303030401000000",
        "00000000000104030303030303030303030303030303030304010000",
        "00000000000001040303030303030303030303030303030303040100",
        "00000000000000010403030303030303030303030303030303030401",
        "00000000000000000104060606060606060606060606060606060604",
        "00000000000000000001040606060606060606060606060606060606",
        "00000000000000000000010406060606060606060606060606060606",
        "00000000000000000000000104060606060606060606060606060606",
        "00000000000000000000000001040606060606060606060606060606",
        "00000000000000000000000000010406060606060606060606060606",
        "00000000000000000000000000000104060606060606060606060606",
        "00000000000000000000000000000001040606060606060606060606",
        "00000000000000000000000000000000010406060606060606060606",
        "00000000000000000000000000000000000104060606060606060606",
        "00000000000000000000000000000000000001040505050505050505",
        "00000000000000000000000000000000000000010405050505050505",
        "00000000000000000000000000000000000000000104050505050505",
        "00000000000000000000000000000000000000000001040505050505",
        "00000000000000000000000000000000000000000000010405050505",
        "00000000000000000000000000000000000000000000000104050505",
        "00000000000000000000000000000000000000000000000001040505",
        "00000000000000000000000000000000000000000000000000010405",
        "00000000000000000000000000000000000000000000000000000104",
        "00000000000000000000000000000000000000000000000000000001" };
    static final String[] CAT_ART = { "000000000000000000000000000000000000000000000000",
        "000000000000000000000000000000000000000000000000", "000000000000000000000000000000000000000000000000",
        "000000000000000000000000000000000000000000000000", "000000000000000000000000000000000000000000000000",
        "000000000000000000000000000000000000000000000000", "000000000000000708000000000000090800000000000000",
        "000000000000080a0a0b0c0d0e0f10111213000000000000", "000000000000080a0a141215161718191a08000000000000",
        "000000000000081b1c1d1e1f202118222324000000000000", "000000000000252627282821292a28282b2c000000000000",
        "0000000000082d271e28212e2f3028213132330000000000", "000000003434343435211836283721383934343400000000",
        "0000000000083a3b3c3d3e3f403841424344450000000000", "0000000034343434342b4608082847483434343400000000",
        "00000000000034494a4b4c324d4e4f504934000000000000", "00000000000000514952534d545550565700000000000000",
        "0000000000000000080858595a5b5c080000000000000000", "000000000000000000000000000000000000000000000000",
        "000000000000000000000000000000000000000000000000", "000000000000000000000000000000000000000000000000",
        "000000000000000000000000000000000000000000000000", "000000000000000000000000000000000000000000000000",
        "000000000000000000000000000000000000000000000000" };

    private HologramVoucherRibbon() {}

    static boolean contains(float x, float y, float width, float height, float mouseX, float mouseY) {
        float side = Math.min(PIXELS, Math.min(width, height));
        if (side <= 0) return false;
        int px = (int) Math.floor((mouseX - (x + width - side)) * PIXELS / side);
        int py = (int) Math.floor((mouseY - y) * PIXELS / side);
        return px >= 0 && py >= 0 && px < PIXELS && py < PIXELS && index(px, py) != 0;
    }

    private static int index(int x, int y) {
        return Character.digit(ART[y].charAt(x * 2), 16) * 16 + Character.digit(ART[y].charAt(x * 2 + 1), 16);
    }

    private static void catVertex(Tessellator tessellator, float left, float top, float unit, int x, int y) {
        double localX = (x - 12) * CAT_SCALE, localY = (y - 12) * CAT_SCALE;
        tessellator.addVertex(
            left + (CAT_CENTER_X + (localX - localY) * CAT_TURN) * unit,
            top + (CAT_CENTER_Y + (localX + localY) * CAT_TURN) * unit,
            2.01);
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
                int index = index(px, py);
                if (index == 0) continue;
                tessellator.setColorOpaque_I(COLORS[index] & 0xffffff);
                float left = x + width - side + px * unit, top = y + py * unit;
                tessellator.addVertex(left, top, 2);
                tessellator.addVertex(left + unit, top, 2);
                tessellator.addVertex(left + unit, top + unit, 2);
                tessellator.addVertex(left, top + unit, 2);
            }
            for (int py = 0; py < 24; py++) for (int px = 0; px < 24; px++) {
                int color = Character.digit(CAT_ART[py].charAt(px * 2), 16) * 16
                    + Character.digit(CAT_ART[py].charAt(px * 2 + 1), 16);
                if (color == 0) continue;
                tessellator.setColorOpaque_I(COLORS[color] & 0xffffff);
                catVertex(tessellator, x + width - side, y, unit, px, py);
                catVertex(tessellator, x + width - side, y, unit, px + 1, py);
                catVertex(tessellator, x + width - side, y, unit, px + 1, py + 1);
                catVertex(tessellator, x + width - side, y, unit, px, py + 1);
            }
            tessellator.draw();
        } finally {
            GL11.glPopAttrib();
        }
    }
}
