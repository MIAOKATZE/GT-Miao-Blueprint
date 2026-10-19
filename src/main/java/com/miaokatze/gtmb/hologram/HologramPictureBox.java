package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

/** Portable source samples and deterministic image-to-material conversion. No client, AWT or file access. */
public final class HologramPictureBox {

    public static final String PREFIX = "gtmb_picture_";
    public static final int MAX_PIXELS = 65536, MAX_SIDE = 512;
    public static final String[] PALETTE_NAMES = { "羊毛 + 染色黏土", "羊毛", "混凝土" };
    public static final String[] FILTER_NAMES = { "原图", "黑白", "怀旧", "反色", "色阶", "柔化", "锐化", "轮廓" };
    private static final int[][] COLORS = {
        { 0xdddddd, 0xdb7d3e, 0xb350bc, 0x6b8ac9, 0xb1a627, 0x41ae38, 0xd08499, 0x404040, 0x9aa1a1, 0x2e6e89, 0x7e3db5,
            0x2e388d, 0x4f321f, 0x35461b, 0x963430, 0x191616 },
        { 0xd1b1a1, 0x9f5224, 0x95586c, 0x706c8a, 0xba8523, 0x677534, 0xa04d4e, 0x392a23, 0x876b62, 0x575b5b, 0x7a4958,
            0x4c3e5c, 0x4c3223, 0x4c522a, 0x8e3c2e, 0x251610 },
        { 0xcfd5d6, 0xe06101, 0xa9309f, 0x2389c6, 0xf0af15, 0x5ea818, 0xd5658e, 0x36393d, 0x7d7d73, 0x157788, 0x64209c,
            0x2c2e8f, 0x603b1f, 0x495b24, 0x8e2020, 0x080a0f } };
    public String filename = "";
    public int originalWidth, originalHeight, sourceWidth, sourceHeight;
    public int[] source = new int[0];
    public int width = 64, height = 64, shortSide = 64, palette, filter;
    public int brightness, contrast, saturation = 100, backgroundTolerance;
    public boolean keepAspect = true, removeBackground, dither;

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("filename", filename);
        tag.setInteger("originalWidth", originalWidth);
        tag.setInteger("originalHeight", originalHeight);
        tag.setInteger("sourceWidth", sourceWidth);
        tag.setInteger("sourceHeight", sourceHeight);
        tag.setIntArray("source", source);
        tag.setInteger("width", width);
        tag.setInteger("height", height);
        tag.setInteger("shortSide", shortSide);
        tag.setInteger("palette", palette);
        tag.setInteger("filter", filter);
        tag.setInteger("brightness", brightness);
        tag.setInteger("contrast", contrast);
        tag.setInteger("saturation", saturation);
        tag.setInteger("backgroundTolerance", backgroundTolerance);
        tag.setBoolean("keepAspect", keepAspect);
        tag.setBoolean("removeBackground", removeBackground);
        tag.setBoolean("dither", dither);
        return tag;
    }

    public static HologramPictureBox read(NBTTagCompound tag) throws IOException {
        HologramPictureBox result = new HologramPictureBox();
        result.filename = tag.getString("filename");
        result.originalWidth = tag.getInteger("originalWidth");
        result.originalHeight = tag.getInteger("originalHeight");
        result.sourceWidth = tag.getInteger("sourceWidth");
        result.sourceHeight = tag.getInteger("sourceHeight");
        result.source = tag.getIntArray("source");
        result.width = tag.getInteger("width");
        result.height = tag.getInteger("height");
        result.shortSide = tag.getInteger("shortSide");
        result.palette = tag.getInteger("palette");
        result.filter = tag.getInteger("filter");
        result.brightness = tag.getInteger("brightness");
        result.contrast = tag.getInteger("contrast");
        result.saturation = tag.getInteger("saturation");
        result.backgroundTolerance = tag.getInteger("backgroundTolerance");
        result.keepAspect = tag.getBoolean("keepAspect");
        result.removeBackground = tag.getBoolean("removeBackground");
        result.dither = tag.getBoolean("dither");
        result.validate();
        return result;
    }

    public void resizeShortSide(int size) throws IOException {
        if (size < 1 || size > MAX_SIDE || originalWidth < 1 || originalHeight < 1)
            throw new IOException("图片短边像素应为 1–512");
        shortSide = size;
        if (originalWidth <= originalHeight) {
            width = size;
            height = (int) Math.round(size * (double) originalHeight / originalWidth);
        } else {
            height = size;
            width = (int) Math.round(size * (double) originalWidth / originalHeight);
        }
    }

    public void validate() throws IOException {
        if (filename.length() > 128 || filename.indexOf('/') >= 0
            || filename.indexOf('\\') >= 0
            || originalWidth < 1
            || originalHeight < 1
            || originalWidth > 16384
            || originalHeight > 16384
            || (long) originalWidth * originalHeight > 16777216
            || sourceWidth < 1
            || sourceWidth > MAX_SIDE
            || sourceHeight < 1
            || sourceHeight > MAX_SIDE
            || (long) sourceWidth * sourceHeight > MAX_PIXELS
            || source.length != sourceWidth * sourceHeight
            || width < 1
            || width > MAX_SIDE
            || height < 1
            || height > MAX_SIDE
            || (long) width * height > MAX_PIXELS
            || shortSide < 1
            || shortSide > MAX_SIDE
            || palette < 0
            || palette >= PALETTE_NAMES.length
            || filter < 0
            || filter >= FILTER_NAMES.length
            || brightness < -100
            || brightness > 100
            || contrast < -100
            || contrast > 100
            || saturation < 0
            || saturation > 200
            || backgroundTolerance < 0
            || backgroundTolerance > 64) throw new IOException("图片尺寸、采样数据或滤镜参数超出范围（每边最多 512，总像素最多 65536）");
        if (keepAspect) {
            int expectedW = originalWidth <= originalHeight ? shortSide
                : (int) Math.round(shortSide * (double) originalWidth / originalHeight);
            int expectedH = originalWidth <= originalHeight
                ? (int) Math.round(shortSide * (double) originalHeight / originalWidth)
                : shortSide;
            if (width != expectedW || height != expectedH) throw new IOException("图片宽高与锁定比例不一致");
        }
    }

    public int paletteSize() {
        return palette == 0 ? 32 : 16;
    }

    public String material(int index) {
        return palette == 2 ? "etfuturum:concrete"
            : palette == 0 && index >= 16 ? "minecraft:stained_hardened_clay" : "minecraft:wool";
    }

    public int metadata(int index) {
        return index & 15;
    }

    public int paletteColor(int index) {
        return COLORS[palette == 2 ? 2 : palette == 0 && index >= 16 ? 1 : 0][index & 15];
    }

    /** Zero is transparent; other bytes index the fixed material palette plus one. */
    public byte[] rasterize() throws IOException {
        validate();
        boolean[] background = removeBackground ? backgroundMask() : new boolean[source.length];
        int[] pixels = sample(background);
        int[] filtered = pixels.clone();
        if (filter >= 5) {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int at = y * width + x;
                if (pixels[at] >>> 24 < 128) continue;
                int[] sum = new int[3];
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    int neighbor = pixels[ny * width + nx];
                    if (neighbor >>> 24 < 128) continue;
                    for (int c = 0; c < 3; c++) sum[c] += neighbor >>> (16 - 8 * c) & 255;
                    count++;
                }
                int color = 0xff000000;
                for (int c = 0; c < 3; c++) {
                    int value = pixels[at] >>> (16 - 8 * c) & 255;
                    int blurred = sum[c] / count;
                    value = filter == 5 ? blurred : filter == 6 ? value * 2 - blurred : Math.abs(value - blurred) * 4;
                    color |= clamp(value) << (16 - 8 * c);
                }
                filtered[at] = color;
            }
        }
        byte[] result = new byte[pixels.length];
        double[][] error = new double[2][(width + 2) * 3];
        for (int y = 0; y < height; y++) {
            Arrays.fill(error[(y + 1) & 1], 0);
            for (int x = 0; x < width; x++) {
                int at = y * width + x;
                if (filtered[at] >>> 24 < 128) continue;
                int rgb = adjust(filtered[at]);
                int r = clamp((rgb >>> 16 & 255) + (dither ? error[y & 1][(x + 1) * 3] : 0));
                int g = clamp((rgb >>> 8 & 255) + (dither ? error[y & 1][(x + 1) * 3 + 1] : 0));
                int b = clamp((rgb & 255) + (dither ? error[y & 1][(x + 1) * 3 + 2] : 0));
                int best = 0;
                long nearest = Long.MAX_VALUE;
                for (int i = 0; i < paletteSize(); i++) {
                    int color = paletteColor(i), dr = r - (color >>> 16 & 255), dg = g - (color >>> 8 & 255),
                        db = b - (color & 255);
                    long distance = (512L + r + (color >>> 16 & 255)) * dr * dr + 1024L * dg * dg
                        + (767L - r - (color >>> 16 & 255)) * db * db;
                    if (distance < nearest) {
                        nearest = distance;
                        best = i;
                    }
                }
                result[at] = (byte) (best + 1);
                if (dither) {
                    int color = paletteColor(best);
                    double[] delta = { r - (color >>> 16 & 255), g - (color >>> 8 & 255), b - (color & 255) };
                    for (int c = 0; c < 3; c++) {
                        error[y & 1][(x + 2) * 3 + c] += delta[c] * 7 / 16;
                        error[(y + 1) & 1][x * 3 + c] += delta[c] * 3 / 16;
                        error[(y + 1) & 1][(x + 1) * 3 + c] += delta[c] * 5 / 16;
                        error[(y + 1) & 1][(x + 2) * 3 + c] += delta[c] / 16;
                    }
                }
            }
        }
        return result;
    }

    private int[] sample(boolean[] background) {
        int[] result = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int x0 = x * sourceWidth / width, x1 = Math.max(x0 + 1, (x + 1) * sourceWidth / width);
            int y0 = y * sourceHeight / height, y1 = Math.max(y0 + 1, (y + 1) * sourceHeight / height);
            long r = 0, g = 0, b = 0, alpha = 0;
            for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
                int at = yy * sourceWidth + xx, color = source[at], a = background[at] ? 0 : color >>> 24;
                alpha += a;
                r += (color >>> 16 & 255) * a;
                g += (color >>> 8 & 255) * a;
                b += (color & 255) * a;
            }
            if (alpha / ((x1 - x0) * (y1 - y0)) >= 128) result[y * width + x] = 0xff000000 | (int) (r / alpha) << 16
                | (int) (g / alpha) << 8
                | (int) (b / alpha);
        }
        return result;
    }

    private boolean[] backgroundMask() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int y = 0; y < sourceHeight; y++) for (int x = 0; x < sourceWidth; x++)
            if (x == 0 || y == 0 || x == sourceWidth - 1 || y == sourceHeight - 1) {
                int color = source[y * sourceWidth + x];
                if (color >>> 24 >= 128) counts.merge(color & 0xffffff, 1, Integer::sum);
            }
        // Transparent padding must not make an interior object's own edge its background.
        int target = -1, frequency = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet())
            if (entry.getValue() > frequency || entry.getValue() == frequency && entry.getKey() < target) {
                frequency = entry.getValue();
                target = entry.getKey();
            }
        boolean[] mask = new boolean[source.length];
        if (target < 0) return mask;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int y = 0; y < sourceHeight; y++)
            for (int x = 0; x < sourceWidth; x++) if (x == 0 || y == 0 || x == sourceWidth - 1 || y == sourceHeight - 1)
                enqueueBackground(y * sourceWidth + x, target, mask, queue);
        while (!queue.isEmpty()) {
            int at = queue.removeFirst(), x = at % sourceWidth, y = at / sourceWidth;
            if (x > 0) enqueueBackground(at - 1, target, mask, queue);
            if (x + 1 < sourceWidth) enqueueBackground(at + 1, target, mask, queue);
            if (y > 0) enqueueBackground(at - sourceWidth, target, mask, queue);
            if (y + 1 < sourceHeight) enqueueBackground(at + sourceWidth, target, mask, queue);
        }
        return mask;
    }

    private void enqueueBackground(int at, int target, boolean[] mask, ArrayDeque<Integer> queue) {
        if (mask[at]) return;
        int color = source[at];
        if (color >>> 24 < 128) return;
        for (int shift : new int[] { 16, 8, 0 })
            if (Math.abs((color >>> shift & 255) - (target >>> shift & 255)) > backgroundTolerance) return;
        mask[at] = true;
        queue.addLast(at);
    }

    private int adjust(int color) {
        double r = color >>> 16 & 255, g = color >>> 8 & 255, b = color & 255;
        double gray = r * .299 + g * .587 + b * .114;
        if (filter == 1) r = g = b = gray;
        else if (filter == 2) {
            double oldR = r, oldG = g;
            r = oldR * .393 + oldG * .769 + b * .189;
            g = oldR * .349 + oldG * .686 + b * .168;
            b = oldR * .272 + oldG * .534 + b * .131;
        } else if (filter == 3) {
            r = 255 - r;
            g = 255 - g;
            b = 255 - b;
        } else if (filter == 4) {
            r = Math.round(r / 85) * 85;
            g = Math.round(g / 85) * 85;
            b = Math.round(b / 85) * 85;
        }
        gray = r * .299 + g * .587 + b * .114;
        double scale = contrast >= 0 ? 1 + contrast / 50d : 1 + contrast / 100d;
        r = ((gray + (r - gray) * saturation / 100d) - 127.5) * scale + 127.5 + brightness * 2.55;
        g = ((gray + (g - gray) * saturation / 100d) - 127.5) * scale + 127.5 + brightness * 2.55;
        b = ((gray + (b - gray) * saturation / 100d) - 127.5) * scale + 127.5 + brightness * 2.55;
        return clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    List<HologramTextBox.Cell> generate(HologramTextBox placement) throws IOException {
        byte[] pixels = rasterize();
        HologramMicroTiles tiles = new HologramMicroTiles();
        for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) {
            int index = (pixels[py * width + px] & 255) - 1;
            if (index < 0) continue;
            for (int sx = 0; sx < placement.pixelX; sx++)
                for (int sy = 0; sy < placement.pixelY; sy++) for (int sz = 0; sz < placement.thickness; sz++) {
                    int[] voxel = placement
                        .orient(px * placement.pixelX + sx, (height - py - 1) * placement.pixelY + sy, sz);
                    tiles.add(voxel[0], voxel[1], voxel[2], material(index), metadata(index), 0xffffff);
                }
        }
        return tiles.finish(placement.x, placement.y, placement.z);
    }
}
