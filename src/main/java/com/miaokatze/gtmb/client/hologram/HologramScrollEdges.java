package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.miaokatzecore.ui.mui2.ScrollEdges;

/** GTMB keeps its palette; Core owns the draw-only edge implementation. */
final class HologramScrollEdges {

    private static final ScrollEdges.Style STYLE = new ScrollEdges.Style(
        6,
        3,
        0x7803090d,
        0x6503090d,
        0x5003090d,
        0xff566167,
        0xff435057);
    private static final ScrollEdges.Style PAPER_STYLE = new ScrollEdges.Style(1, 1, 0, 0, 0, 0xffbaa783, 0xffbaa783);

    private HologramScrollEdges() {}

    static void draw(int x, int y, int width, int height) {
        ScrollEdges.draw(x, y, width, height, STYLE);
    }

    static void drawPaper(int x, int y, int width, int height) {
        ScrollEdges.draw(x, y, width, height, PAPER_STYLE);
    }
}
