package com.miaokatze.gtmb.testutil;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** Encoded PNG geometry for file protocol tests; this is not a rendered machine preview. */
public final class BlueprintArtifactTestData {

    private BlueprintArtifactTestData() {}

    public static byte[] previewPng() throws IOException {
        BufferedImage image = new BufferedImage(640, 640, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, y, 0xff000000 | ((x / 20 & 15) << 20) | ((y / 20 & 15) << 12) | 0x60);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("No PNG encoder available for artifact fixture");
        return bytes.toByteArray();
    }
}
