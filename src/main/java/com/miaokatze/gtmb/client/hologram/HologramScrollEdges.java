package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.drawable.GuiDraw;

/** Restrained recessed edges, drawn above clipped rows without changing their hit rectangles. */
final class HologramScrollEdges {

    private HologramScrollEdges() {}

    static void draw(int x, int y, int width, int height) {
        int depth = Math.min(6, Math.max(1, height / 8));
        GuiDraw.drawVerticalGradientRect(x + 1, y + 1, width - 2, depth, 0x7803090d, 0x0003090d);
        GuiDraw.drawVerticalGradientRect(x + 1, y + height - depth - 1, width - 2, depth, 0x0003090d, 0x6503090d);
        GuiDraw.drawHorizontalGradientRect(x + 1, y + 1, 3, height - 2, 0x5003090d, 0x0003090d);
        GuiDraw.drawHorizontalGradientRect(x + width - 4, y + 1, 3, height - 2, 0x0003090d, 0x5003090d);
        GuiDraw.drawRect(x, y, width, 1, 0xff566167);
        GuiDraw.drawRect(x, y + height - 1, width, 1, 0xff435057);
        GuiDraw.drawRect(x, y + 1, 1, height - 2, 0xff435057);
        GuiDraw.drawRect(x + width - 1, y + 1, 1, height - 2, 0xff435057);
    }
}
