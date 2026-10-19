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
            jpegAndPngSources(folder);
            optionalOriginalJpeg(folder);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(folder)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void jpegAndPngSources(Path folder) throws Exception {
        BufferedImage rgb = new BufferedImage(250, 250, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(250);
        for (int y = 0; y < 250; y++) for (int x = 0; x < 250; x++) rgb.setRGB(x, y, random.nextInt(1 << 24));
        SimpleAssert.that(
            ImageIO.write(
                rgb,
                "png",
                folder.resolve("noise.png")
                    .toFile()),
            "PNG writer available");
        SimpleAssert.that(
            ImageIO.write(
                rgb,
                "jpg",
                folder.resolve("noise.jpg")
                    .toFile()),
            "JPEG writer available");
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg")
            .next();
        try (javax.imageio.stream.ImageOutputStream output = ImageIO.createImageOutputStream(
            folder.resolve("progressive.jpeg")
                .toFile())) {
            writer.setOutput(output);
            javax.imageio.ImageWriteParam params = writer.getDefaultWriteParam();
            params.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
        for (String filename : new String[] { "noise.png", "noise.jpg", "progressive.jpeg" }) {
            HologramPictureBox picture = HologramPictureFiles.load(folder, filename);
            picture.resizeShortSide(250);
            picture.validate();
            SimpleAssert
                .eq(62500, picture.source.length, "decoder retains every 250-square source sample: " + filename);
            SimpleAssert.eq(
                62500,
                picture.rasterize().length,
                "format does not change requested output dimensions: " + filename);
            for (int pixel : picture.source) SimpleAssert.eq(255, pixel >>> 24, "RGB JPEG/PNG converts to opaque ARGB");
            com.miaokatze.gtmb.hologram.HologramTextBox value = new com.miaokatze.gtmb.hologram.HologramTextBox();
            value.picture = picture;
            net.minecraft.nbt.NBTTagCompound request = new net.minecraft.nbt.NBTTagCompound();
            request.setTag("textBox", value.write());
            int payload = net.minecraft.nbt.CompressedStreamTools.compress(request).length;
            SimpleAssert
                .that(payload > 32767, "both complex JPEG and PNG can cross the original upload limit: " + filename);
            System.out.println(
                "GTMB_IMAGE_FORMAT file=" + filename
                    + " fileBytes="
                    + Files.size(folder.resolve(filename))
                    + " decodedSourceNbtBytes="
                    + payload);
        }
        BufferedImage gray = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY);
        ImageIO.write(
            gray,
            "jpeg",
            folder.resolve("gray.jpg")
                .toFile());
        HologramPictureBox loaded = HologramPictureFiles.load(folder, "gray.jpg");
        SimpleAssert.eq(
            0xff000000,
            loaded.source[0],
            "grayscale JPEG is normalized to opaque RGB without a missing alpha channel");
    }

    /** Explicit read-only external replay; no picture or absolute user path is bundled. */
    private static void optionalOriginalJpeg(Path folder) throws Exception {
        String input = System.getenv("GTMB_QA_PICTURE");
        if (input == null || input.isEmpty()) return;
        Path original = java.nio.file.Paths.get(input);
        byte[] before = Files.readAllBytes(original);
        Files.write(folder.resolve("original.jpg"), before);
        HologramPictureBox jpeg = HologramPictureFiles.load(folder, "original.jpg");
        BufferedImage image = new BufferedImage(jpeg.sourceWidth, jpeg.sourceHeight, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, jpeg.sourceWidth, jpeg.sourceHeight, jpeg.source, 0, jpeg.sourceWidth);
        ImageIO.write(
            image,
            "png",
            folder.resolve("equivalent.png")
                .toFile());
        HologramPictureBox png = HologramPictureFiles.load(folder, "equivalent.png");
        SimpleAssert.that(
            java.util.Arrays.equals(jpeg.source, png.source),
            "original JPEG and decoded-equivalent PNG have identical source pixels");
        jpeg.filename = png.filename = "comparison";
        jpeg.resizeShortSide(250);
        png.resizeShortSide(250);
        SimpleAssert.eq(jpeg.write(), png.write(), "file extension never changes the canonical decoded upload");
        int payload = net.minecraft.nbt.CompressedStreamTools.compress(jpeg.write()).length;
        SimpleAssert.that(payload > 32767, "provided JPEG reproduces the old single-packet boundary");
        SimpleAssert.that(
            java.util.Arrays.equals(before, Files.readAllBytes(original)),
            "external original remains byte-identical");
        System.out.println(
            "GTMB_ACTUAL_JPEG fileBytes=" + before.length
                + " decodedSourceNbtBytes="
                + payload
                + " output="
                + jpeg.width
                + "x"
                + jpeg.height
                + " sourceUnchanged=true equivalentPngIdentical=true");
    }
}
