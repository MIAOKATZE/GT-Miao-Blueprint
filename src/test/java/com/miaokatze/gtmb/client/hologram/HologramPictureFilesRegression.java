package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import com.miaokatze.gtmb.hologram.HologramPictureBox;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramPictureFilesRegression {

    private HologramPictureFilesRegression() {}

    public static void run() throws Exception {
        Path folder = Files.createTempDirectory("gtmb-picture-files");
        try {
            BufferedImage image = new BufferedImage(1280, 640, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, 0xff963430);
            Path png = folder.resolve("cat.PNG");
            ImageIO.write(image, "png", png.toFile());
            Files.write(folder.resolve("ignored.txt"), new byte[] { 1 });
            SimpleAssert.eq(
                java.util.Collections.singletonList("cat.PNG"),
                HologramPictureFiles.scan(folder),
                "catalog admits supported image files only");
            HologramPictureBox picture = HologramPictureFiles.load(folder, "cat.PNG");
            SimpleAssert.eq(128, picture.width, "source image pixel dimensions do not set output resolution");
            SimpleAssert.eq(64, picture.height, "small-pixel default short side remains 64");
            SimpleAssert.that(
                picture.source.length <= HologramPictureBox.MAX_PIXELS,
                "large source is normalized before embedding");
            SimpleAssert.eq(0xff963430, picture.source[0], "normalization preserves sampled ARGB color");
            try {
                HologramPictureFiles.load(folder, "../outside.png");
                throw new AssertionError("directory escape admitted");
            } catch (java.io.IOException expected) {}
            Files.write(folder.resolve("broken.png"), new byte[] { 1, 2, 3 });
            try {
                HologramPictureFiles.load(folder, "broken.png");
                throw new AssertionError("invalid image admitted");
            } catch (java.io.IOException expected) {}
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(folder)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }
}
