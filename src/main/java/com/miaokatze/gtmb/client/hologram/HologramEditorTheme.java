package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;

/** Client presentation only. Paper ink and dark-surface lettering deliberately have separate palettes. */
final class HologramEditorTheme {

    static final int TEXT = 0xff46301d, TITLE = 0xff684017, MUTED = 0xff5f4428, ERROR = 0xff7c261f;
    static final int BUTTON_TEXT = 0xfff8e4b9, BUTTON_MUTED = 0xffc1ab87;
    static final int PAPER_WARNING = ERROR, PAPER_SUGGESTION = 0xff144d51, PAPER_INFORMATION = 0xff244c78;
    static final int PAPER_NEXT = 0xff254b2b, PAPER_WEBSITE = 0xff674018;

    static final UITexture PAPER = pixel(
        UITexture.builder()
            .location("gtmb", "gui/editor/kraft/paper")
            .tiled(64, 64)
            .nonOpaque()
            .build());
    static final UITexture PANEL_FRAME = texture("kraft/panel", 8, 8, 1);
    static final UITexture PANEL = new PaperSurface();
    static final UITexture CLOTH = PANEL;
    static final UITexture BUTTON = texture("kraft/button", 12, 12, 2);
    static final UITexture HOVER = texture("kraft/button-hover", 12, 12, 2);
    static final UITexture PRESSED = texture("kraft/button-pressed", 12, 12, 2);
    static final UITexture SELECTED_HOVER = texture("kraft/button-selected-hover", 12, 12, 2);
    static final UITexture DISABLED = texture("kraft/button-disabled", 12, 12, 2);
    static final UITexture INPUT = texture("kraft/input", 8, 8, 1);
    static final UITexture SLOT = texture("kraft/slot", 12, 12, 2);
    static final UITexture SCROLLBAR = texture("kraft/scrollbar", 12, 12, 2);

    private HologramEditorTheme() {}

    static UITexture texture(String path, int width, int height, int border) {
        UITexture.Builder builder = UITexture.builder()
            .location("gtmb", "gui/editor/" + path)
            .imageSize(width, height)
            .nonOpaque();
        if (border >= 0) builder.adaptable(border);
        return pixel(builder.build());
    }

    static UITexture pixel(UITexture texture) {
        return new PixelTexture(texture);
    }

    static UITexture button(boolean enabled, boolean selected, boolean hovering) {
        if (!enabled) return DISABLED;
        if (selected) return hovering ? SELECTED_HOVER : PRESSED;
        return hovering ? HOVER : BUTTON;
    }

    /** Preserve semantic hue on paper; callers rendering a world viewport or tooltip pass true. */
    static int textColor(int legacyColor, boolean darkSurface) {
        int color = 0xff000000 | legacyColor & 0xffffff;
        if (darkSurface) return color;
        if (legacyColor == HologramUiAccent.SPECTRUM) return PAPER_SUGGESTION;
        return switch (color & 0xffffff) {
            case 0xff9292, 0xef8585, 0xff9696, 0xff7777, 0xef7b7b, 0xff7272, 0xffce86 -> ERROR;
            case 0x80efaa, 0x8bf5a2 -> PAPER_NEXT;
            case 0x68dded, 0x81dce9, 0x62e8ef, 0x9ceaf3 -> PAPER_SUGGESTION;
            case 0x86baff -> PAPER_INFORMATION;
            case 0xffb45e -> PAPER_WEBSITE;
            case 0xffdb83, 0xf0ddb3, 0xefcb8a -> TITLE;
            case 0xd4c5a8, 0xb5c2cc, 0xb9ced8, 0xbacbd1, 0x9bacb3 -> MUTED;
            default -> paperInk(color);
        };
    }

    private static int paperInk(int color) {
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        if (Math.max(r, Math.max(g, b)) < 145) return color;
        if (r > g * 1.3 && r > b * 1.3) return ERROR;
        if (g > r * 1.15 && g > b * 1.08) return PAPER_NEXT;
        if (g > r * 1.08 && b > r * 1.08) return PAPER_SUGGESTION;
        if (b > r * 1.16 && b > g * 1.08) return PAPER_INFORMATION;
        if (r > b * 1.2 && g > b * 1.08) return TITLE;
        return TEXT;
    }

    /** Two-pixel, discrete edge samples: no blur, gradient or animation-owned state. */
    static void drawGuidanceFrame(int x, int y, int width, int height, int accent, boolean enabled) {
        if (!enabled || accent == 0 || width < 4 || height < 4) return;
        long now = System.nanoTime();
        int edge = HologramUiAccent.BORDER_WIDTH;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (accent != HologramUiAccent.SPECTRUM) {
                int color = edgeColor(accent, now, 0);
                GuiDraw.drawRect(x, y, width, edge, color);
                GuiDraw.drawRect(x, y + height - edge, width, edge, color);
                GuiDraw.drawRect(x, y + edge, edge, height - edge * 2, color);
                GuiDraw.drawRect(x + width - edge, y + edge, edge, height - edge * 2, color);
                return;
            }
            for (int px = 0; px < width; px += 2) {
                int length = Math.min(2, width - px);
                GuiDraw.drawRect(x + px, y, length, edge, edgeColor(accent, now, px / (double) width * .25));
                GuiDraw.drawRect(
                    x + px,
                    y + height - edge,
                    length,
                    edge,
                    edgeColor(accent, now, .75 - px / (double) width * .25));
            }
            for (int py = edge; py < height - edge; py += 2) {
                int length = Math.min(2, height - edge - py);
                GuiDraw.drawRect(x, y + py, edge, length, edgeColor(accent, now, 1 - py / (double) height * .25));
                GuiDraw.drawRect(
                    x + width - edge,
                    y + py,
                    edge,
                    length,
                    edgeColor(accent, now, .25 + py / (double) height * .25));
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static int edgeColor(int accent, long now, double offset) {
        int color = accent == HologramUiAccent.SPECTRUM ? HologramUiSpectrum.color(now, offset)
            : HologramUiAccent.color(accent);
        return HologramUiSpectrum.breathe(color, now);
    }

    /** The delegate retains MUI's tiled/nine-slice implementation; only editor textures are filtered. */
    private static final class PixelTexture extends UITexture {

        private final UITexture delegate;

        PixelTexture(UITexture delegate) {
            super(delegate.location, 0, 0, 1, 1, null);
            this.delegate = delegate;
        }

        @Override
        public void draw(GuiContext context, int x, int y, int width, int height, WidgetTheme theme) {
            draw((float) x, y, width, height);
        }

        @Override
        public void draw(float x, float y, float width, float height) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .bindTexture(delegate.location);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glColor4f(1, 1, 1, 1);
                delegate.draw(x, y, width, height);
            } finally {
                GL11.glPopAttrib();
            }
        }
    }

    private static final class PaperSurface extends UITexture {

        PaperSurface() {
            super(PAPER.location, 0, 0, 1, 1, null);
        }

        @Override
        public void draw(GuiContext context, int x, int y, int width, int height, WidgetTheme theme) {
            draw((float) x, y, width, height);
        }

        @Override
        public void draw(float x, float y, float width, float height) {
            PAPER.draw(x, y, width, height);
            PANEL_FRAME.draw(x, y, width, height);
        }
    }
}
