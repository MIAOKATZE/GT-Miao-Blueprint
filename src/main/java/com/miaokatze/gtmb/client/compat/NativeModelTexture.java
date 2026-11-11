// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.compat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

/** Existing entity textures padded, never rescaled, for static model rendering in the block atlas. */
public final class NativeModelTexture extends TextureAtlasSprite {

    private final ResourceLocation source;
    private float uScale, vScale;

    public NativeModelTexture(String name, ResourceLocation source) {
        super(name);
        this.source = source;
    }

    public double u(float normalized) {
        return getInterpolatedU(normalized * uScale * 16);
    }

    public double v(float normalized) {
        return getInterpolatedV(normalized * vScale * 16);
    }

    @Override
    public boolean hasCustomLoader(IResourceManager manager, ResourceLocation location) {
        return true;
    }

    @Override
    public boolean load(IResourceManager manager, ResourceLocation location) {
        try (InputStream input = manager.getResource(source)
            .getInputStream()) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) throw new IOException("Unsupported native model texture: " + source);
            int size = 1;
            while (size < Math.max(image.getWidth(), image.getHeight())) size *= 2;
            BufferedImage padded = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = padded.createGraphics();
            graphics.drawImage(image, 0, 0, null);
            graphics.dispose();
            uScale = (float) image.getWidth() / size;
            vScale = (float) image.getHeight() / size;
            setIconWidth(size);
            setIconHeight(size);
            int[][] frame = new int[1][];
            frame[0] = padded.getRGB(0, 0, size, size, null, 0, size);
            setFramesTextureData(Collections.singletonList(frame));
            return false;
        } catch (IOException failure) {
            throw new IllegalStateException("Native model texture unavailable: " + source, failure);
        }
    }
}
