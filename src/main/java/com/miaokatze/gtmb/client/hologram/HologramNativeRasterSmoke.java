package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Opt-in framebuffer evidence from the real screen draw, before voxel outlines and UI labels. */
final class HologramNativeRasterSmoke {

    private static Frame last;

    private HologramNativeRasterSmoke() {}

    static Frame begin(HologramState state, boolean current) {
        return Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke") ? new Frame(state, current) : null;
    }

    static void finish(Frame frame) {
        if (frame != null) last = frame;
    }

    static String requireVisible(NBTTagCompound snapshot, boolean current) {
        require(
            last != null && last.current == current && last.state.data.equals(snapshot),
            "native raster audit must come from this actual screen snapshot/view");
        require(
            last.stats.size() == snapshot.getTagList("cells", 10)
                .tagCount(),
            "every native fixture cell must be drawn on the actual screen");
        StringBuilder receipt = new StringBuilder();
        for (Map.Entry<String, Stats> entry : last.stats.entrySet()) {
            Stats stats = entry.getValue();
            require(
                stats.area > 0 && stats.changed >= Math.max(12, stats.area / 20),
                entry.getKey() + " native model is not visibly rasterized: pixels=" + stats.changed + "/" + stats.area);
            require(
                stats.colors >= 3,
                entry.getKey() + " actual native surface lacks visible material/color detail: colors=" + stats.colors);
            receipt.append(entry.getKey())
                .append(" rasterPixels=")
                .append(stats.changed)
                .append('/')
                .append(stats.area)
                .append(" colors=")
                .append(stats.colors)
                .append('\n');
        }
        return receipt.toString();
    }

    /** Final saved PNG must retain material pixels observed before overlays, not merely wireframes. */
    static String requireScreenshot(File screenshot) throws IOException {
        require(last != null, "native screen must have a raster receipt before saving its screenshot");
        BufferedImage image = ImageIO.read(screenshot);
        require(image != null, "native screenshot must decode as an image");
        StringBuilder receipt = new StringBuilder();
        for (Map.Entry<String, Stats> entry : last.stats.entrySet()) {
            Stats stats = entry.getValue();
            int retained = 0;
            Set<Integer> colors = new HashSet<>();
            for (int pixel = 0; pixel < stats.samples.length; pixel++) {
                if (stats.samples[pixel] == 0) continue;
                int x = stats.x + pixel % stats.width;
                int y = image.getHeight() - 1 - (stats.y + pixel / stats.width);
                require(
                    x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight(),
                    "native raster sample must map to the saved screenshot");
                int expected = stats.samples[pixel] - 1, actual = image.getRGB(x, y) & 0xffffff;
                int difference = Math.abs((expected & 255) - (actual & 255))
                    + Math.abs((expected >> 8 & 255) - (actual >> 8 & 255))
                    + Math.abs((expected >> 16 & 255) - (actual >> 16 & 255));
                if (difference <= 9) {
                    retained++;
                    colors.add(actual);
                }
            }
            require(
                retained >= Math.max(5, stats.changed / 4) && colors.size() >= 3,
                entry.getKey() + " native material vanished from final screenshot: retained="
                    + retained
                    + "/"
                    + stats.changed
                    + " colors="
                    + colors.size());
            receipt.append(entry.getKey())
                .append(" screenshotMaterialPixels=")
                .append(retained)
                .append(" colors=")
                .append(colors.size())
                .append('\n');
        }
        return receipt.toString();
    }

    static final class Frame {

        final HologramState state;
        final boolean current;
        final Map<String, Stats> stats = new LinkedHashMap<>();

        Frame(HologramState state, boolean current) {
            this.state = state;
            this.current = current;
        }

        Slice before(double[][] outline, double depthScale) {
            FloatBuffer model = BufferUtils.createFloatBuffer(16), projection = BufferUtils.createFloatBuffer(16);
            IntBuffer viewport = BufferUtils.createIntBuffer(16), scissor = BufferUtils.createIntBuffer(16);
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, model);
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissor);
            double minX = Double.POSITIVE_INFINITY, minY = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
            for (double[] point : outline) {
                double[] eye = multiply(model, new double[] { point[0], point[1], point[2] * depthScale, 1 });
                double[] clip = multiply(projection, eye);
                double x = viewport.get(0) + (clip[0] / clip[3] + 1) * viewport.get(2) / 2;
                double y = viewport.get(1) + (clip[1] / clip[3] + 1) * viewport.get(3) / 2;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            int x0 = Math.max(viewport.get(0), (int) Math.floor(minX) - 1);
            int y0 = Math.max(viewport.get(1), (int) Math.floor(minY) - 1);
            int x1 = Math.min(viewport.get(0) + viewport.get(2), (int) Math.ceil(maxX) + 1);
            int y1 = Math.min(viewport.get(1) + viewport.get(3), (int) Math.ceil(maxY) + 1);
            if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
                x0 = Math.max(x0, scissor.get(0));
                y0 = Math.max(y0, scissor.get(1));
                x1 = Math.min(x1, scissor.get(0) + scissor.get(2));
                y1 = Math.min(y1, scissor.get(1) + scissor.get(3));
            }
            Slice slice = new Slice(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
            slice.before = slice.read();
            return slice;
        }

        void after(HologramState.Cell cell, Slice slice) {
            ByteBuffer after = slice.read();
            int changed = 0;
            Set<Integer> colors = new HashSet<>();
            int[] samples = new int[slice.width * slice.height];
            for (int pixel = 0; pixel < slice.width * slice.height; pixel++) {
                int index = pixel * 4, difference = 0, rgb = 0;
                for (int channel = 0; channel < 3; channel++) {
                    int value = after.get(index + channel) & 255;
                    difference += Math.abs(value - (slice.before.get(index + channel) & 255));
                    rgb = rgb << 8 | value;
                }
                if (difference > 12) {
                    changed++;
                    colors.add(rgb);
                    samples[pixel] = rgb + 1;
                }
            }
            stats.put(
                (current ? cell.id : cell.wantId) + "@" + cell.index,
                new Stats(slice, changed, colors.size(), samples));
        }
    }

    private static double[] multiply(FloatBuffer matrix, double[] vector) {
        double[] result = new double[4];
        for (int row = 0; row < 4; row++)
            for (int column = 0; column < 4; column++) result[row] += matrix.get(column * 4 + row) * vector[column];
        return result;
    }

    static final class Slice {

        final int x, y, width, height;
        ByteBuffer before;

        Slice(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        ByteBuffer read() {
            ByteBuffer bytes = BufferUtils.createByteBuffer(width * height * 4);
            if (width == 0 || height == 0) return bytes;
            GL11.glPushClientAttrib(0xffffffff); // Includes pixel pack state.
            try {
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
                GL11.glReadPixels(x, y, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
            } finally {
                GL11.glPopClientAttrib();
            }
            return bytes;
        }
    }

    private static final class Stats {

        final int area, changed, colors, x, y, width;
        final int[] samples;

        Stats(Slice slice, int changed, int colors, int[] samples) {
            this.area = slice.width * slice.height;
            this.changed = changed;
            this.colors = colors;
            x = slice.x;
            y = slice.y;
            width = slice.width;
            this.samples = samples;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
