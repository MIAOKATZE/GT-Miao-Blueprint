package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

/** Render-thread owner: replacing the decoded image always retires the texture made from its predecessor. */
final class HologramLibraryPreviewImage {

    private BufferedImage image;
    private ResourceLocation location;

    BufferedImage image() {
        return image;
    }

    void replace(BufferedImage replacement) {
        if (image == replacement) return;
        releaseTexture();
        image = replacement;
    }

    ResourceLocation texture() {
        if (image == null) return null;
        if (location == null) {
            DynamicTexture texture = new DynamicTexture(image.getWidth(), image.getHeight());
            try {
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), texture.getTextureData(), 0, image.getWidth());
                texture.updateDynamicTexture();
                location = Minecraft.getMinecraft()
                    .getTextureManager()
                    .getDynamicTextureLocation("gtmb_blueprint_preview", texture);
            } catch (RuntimeException | LinkageError failure) {
                texture.deleteGlTexture();
                throw failure;
            }
        }
        return location;
    }

    void clear() {
        replace(null);
    }

    private void releaseTexture() {
        if (location != null) Minecraft.getMinecraft()
            .getTextureManager()
            .deleteTexture(location);
        location = null;
    }
}
