package com.miaokatze.gtmb.client.hologram;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import com.miaokatze.gtmb.hologram.HologramTextBox;

/** Resource-pack Minecraft glyphs or bundled redistributable fonts; the result is an editable binary mask. */
final class HologramTextRasterizer {

    private static final Map<String, Font> FONTS = new HashMap<>();

    private HologramTextRasterizer() {}

    static void rasterize(HologramTextBox text) throws IOException {
        if (text.text.length() > 512 || text.text.trim()
            .isEmpty()) throw new IOException("请输入 1–512 字符的文字");
        text.layoutVersion = 1;
        BufferedImage image = "minecraft".equals(text.font) ? minecraft(text) : openFont(text);
        text.width = image.getWidth();
        text.height = image.getHeight();
        text.bitmap = new byte[(text.width * text.height + 7) / 8];
        for (int y = 0; y < text.height; y++)
            for (int x = 0; x < text.width; x++) if ((image.getRGB(x, y) >>> 24) >= 96) {
                int bit = y * text.width + x;
                text.bitmap[bit >>> 3] |= (byte) (1 << (bit & 7));
            }
        text.validate();
    }

    private static BufferedImage openFont(HologramTextBox text) throws IOException {
        Font base = load(text.font);
        Font font = base.deriveFont(
            text.bold ? Font.BOLD : Font.PLAIN,
            "miao".equals(text.font) || "fusion".equals(text.font) ? 12f : 16f);
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = measure.createGraphics();
        int width = 1, lineHeight, ascent;
        String[] lines = text.lines();
        try {
            g.setFont(font);
            lineHeight = g.getFontMetrics()
                .getHeight();
            ascent = g.getFontMetrics()
                .getAscent();
            for (String line : lines) {
                int measured = 2;
                for (char c : line.toCharArray()) {
                    Font glyph = font.canDisplay(c) ? font
                        : load("fusion").deriveFont(font.getStyle(), font.getSize2D());
                    measured += g.getFontMetrics(glyph)
                        .charWidth(c);
                }
                width = Math.max(width, measured);
            }
        } finally {
            g.dispose();
        }
        if (width > 1024 || (long) lines.length * lineHeight > (text.vertical ? 1024 : 128)
            || (long) width * lines.length * lineHeight > 131072) throw new IOException("文字过宽或行数过多，请分成多个文本框");
        BufferedImage result = new BufferedImage(width, lines.length * lineHeight, BufferedImage.TYPE_INT_ARGB);
        g = result.createGraphics();
        try {
            g.setFont(font);
            g.setColor(java.awt.Color.WHITE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            for (int i = 0; i < lines.length; i++) {
                int x = 0;
                for (int at = 0; at < lines[i].length(); at++) {
                    char c = lines[i].charAt(at);
                    Font glyph = font.canDisplay(c) ? font
                        : load("fusion").deriveFont(font.getStyle(), font.getSize2D());
                    g.setFont(glyph);
                    g.drawString(String.valueOf(c), x, i * lineHeight + ascent);
                    x += g.getFontMetrics(glyph)
                        .charWidth(c);
                }
                if (text.underline) g.fillRect(0, i * lineHeight + ascent + 1, Math.max(1, x), 1);
            }
        } finally {
            g.dispose();
        }
        return result;
    }

    private static Font load(String id) throws IOException {
        Font cached = FONTS.get(id);
        if (cached != null) return cached;
        String file = switch (id) {
            case "miao" -> "miao-pixel.otf";
            case "fusion" -> "fusion-pixel.otf";
            case "dejavu" -> "DejaVuSans.ttf";
            case "dejavu_serif" -> "DejaVuSerif.ttf";
            default -> throw new IOException("未知字体");
        };
        try (InputStream input = HologramTextRasterizer.class.getResourceAsStream("/assets/gtmb/fonts/" + file)) {
            if (input == null) throw new IOException("缺少字体资源：" + file);
            Font value = Font.createFont(Font.TRUETYPE_FONT, input);
            FONTS.put(id, value);
            return value;
        } catch (java.awt.FontFormatException invalid) {
            throw new IOException("字体资源格式错误", invalid);
        }
    }

    private static BufferedImage image(String path) throws IOException {
        try (InputStream input = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(new ResourceLocation("minecraft", path))
            .getInputStream()) {
            return ImageIO.read(input);
        }
    }

    private static BufferedImage minecraft(HologramTextBox text) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        String[] lines = text.lines();
        int width = 1;
        for (String line : lines) width = Math.max(width, mc.fontRenderer.getStringWidth(line) * 2 + 3);
        if (width > 1024 || (long) lines.length * 18 > (text.vertical ? 1024 : 128)
            || (long) width * lines.length * 18 > 131072) throw new IOException("文字过宽或行数过多，请分成多个文本框");
        BufferedImage result = new BufferedImage(width, lines.length * 18, BufferedImage.TYPE_INT_ARGB);
        BufferedImage ascii = image("textures/font/ascii.png");
        Map<Integer, BufferedImage> pages = new HashMap<>();
        byte[] sizes = new byte[65536];
        try (InputStream input = mc.getResourceManager()
            .getResource(new ResourceLocation("minecraft", "font/glyph_sizes.bin"))
            .getInputStream()) {
            int at = 0, read;
            while (at < sizes.length && (read = input.read(sizes, at, sizes.length - at)) > 0) at += read;
        }
        for (int line = 0; line < lines.length; line++) {
            int pen = 0;
            for (char c : lines[line].toCharArray()) {
                if (c == ' ') {
                    pen += mc.fontRenderer.getCharWidth(c) * 2;
                    continue;
                }
                BufferedImage page;
                int gx, gy, from, to, size;
                if (c >= 32 && c < 127 && !mc.fontRenderer.getUnicodeFlag()) {
                    page = ascii;
                    gx = c % 16;
                    gy = c / 16;
                    size = 8;
                    from = 0;
                    to = 8;
                } else {
                    int pageId = c >>> 8;
                    page = pages.get(pageId);
                    if (page == null) {
                        page = image(
                            String.format(java.util.Locale.ROOT, "textures/font/unicode_page_%02x.png", pageId));
                        pages.put(pageId, page);
                    }
                    gx = c % 16;
                    gy = (c & 255) / 16;
                    size = 16;
                    from = (sizes[c] & 255) >>> 4;
                    to = (sizes[c] & 15) + 1;
                }
                for (int y = 0; y < 16; y++) for (int x = 0; x < (to - from) * (16 / size); x++) {
                    int sx = (gx * size + from + x * size / 16) * page.getWidth() / (size * 16);
                    int sy = (gy * size + y * size / 16) * page.getHeight() / (size * 16);
                    if ((page.getRGB(sx, sy) >>> 24) < 96) continue;
                    if (pen + x < width) result.setRGB(pen + x, line * 18 + y, 0xffffffff);
                    if (text.bold && pen + x + 1 < width) result.setRGB(pen + x + 1, line * 18 + y, 0xffffffff);
                }
                pen += Math.max(1, mc.fontRenderer.getCharWidth(c)) * 2;
            }
            if (text.underline)
                for (int x = 0; x < Math.min(pen, width); x++) result.setRGB(x, line * 18 + 16, 0xffffffff);
        }
        return result;
    }
}
