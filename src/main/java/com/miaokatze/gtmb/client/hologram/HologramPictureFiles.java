package com.miaokatze.gtmb.client.hologram;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.miaokatze.gtmb.hologram.HologramPictureBox;

/** Instance-local image catalog; decoding is bounded before pixels are allocated and runs off the UI thread. */
final class HologramPictureFiles {

    private HologramPictureFiles() {}

    static List<String> scan(Path folder) throws IOException {
        Files.createDirectories(folder);
        List<String> names = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            int visited = 0;
            for (Path path : stream) {
                if (++visited > 4096) break;
                String name = path.getFileName()
                    .toString(), lower = name.toLowerCase(Locale.ROOT);
                if (name.length() <= 128 && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && (lower.endsWith(".png") || lower.endsWith(".jpg")
                        || lower.endsWith(".jpeg")
                        || lower.endsWith(".bmp")
                        || lower.endsWith(".gif")))
                    names.add(name);
            }
        }
        names.sort(Comparator.comparing(value -> value.toLowerCase(Locale.ROOT)));
        if (names.size() > 256) return new ArrayList<>(names.subList(0, 256));
        return names;
    }

    static HologramPictureBox load(Path folder, String filename) throws IOException {
        Path base = folder.toRealPath(), path = base.resolve(filename)
            .normalize();
        if (!path.getParent()
            .equals(base) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || !path.toRealPath()
                .getParent()
                .equals(base))
            throw new IOException("请选择图片目录中的普通文件");
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("图片文件不得超过 16 MiB");
        BufferedImage image;
        // Memory-backed input avoids ImageIO's implicit disk cache and leaves the source file untouched.
        byte[] bytes;
        try (java.io.InputStream input = Files.newInputStream(path);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) >= 0;) {
                if ((long) output.size() + n > 16 * 1024 * 1024) throw new IOException("图片文件超过 16 MiB");
                output.write(buffer, 0, n);
            }
            bytes = output.toByteArray();
        }
        try (ImageInputStream input = new javax.imageio.stream.MemoryCacheImageInputStream(
            new java.io.ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("无法读取图片；支持 PNG、JPEG、BMP 和 GIF 首帧");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 16384 || height > 16384 || (long) width * height > 16777216)
                    throw new IOException("原图最多 1600 万像素、单边最多 16384");
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (image == null) throw new IOException("图片内容无效");
        HologramPictureBox picture = new HologramPictureBox();
        picture.filename = filename;
        picture.originalWidth = image.getWidth();
        picture.originalHeight = image.getHeight();
        double scale = Math.min(
            1,
            Math.min(
                512d / Math.max(image.getWidth(), image.getHeight()),
                Math.sqrt(HologramPictureBox.MAX_PIXELS / (double) ((long) image.getWidth() * image.getHeight()))));
        picture.sourceWidth = Math.max(1, (int) Math.floor(image.getWidth() * scale));
        picture.sourceHeight = Math.max(1, (int) Math.floor(image.getHeight() * scale));
        BufferedImage normalized = new BufferedImage(
            picture.sourceWidth,
            picture.sourceHeight,
            BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setComposite(java.awt.AlphaComposite.Src);
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(image, 0, 0, picture.sourceWidth, picture.sourceHeight, null);
        } finally {
            graphics.dispose();
            image.flush();
        }
        picture.source = normalized
            .getRGB(0, 0, picture.sourceWidth, picture.sourceHeight, null, 0, picture.sourceWidth);
        normalized.flush();
        picture.resizeShortSide(64);
        // An extremely wide source remains loadable: the UI can reduce its short side before conversion.
        return picture;
    }
}
