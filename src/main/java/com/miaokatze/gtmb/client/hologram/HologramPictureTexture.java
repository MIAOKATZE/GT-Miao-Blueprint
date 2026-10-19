package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import com.miaokatze.gtmb.hologram.HologramPictureBox;

/** One material-color texture per changed raster; drawing a megapixel image never emits a million quads. */
final class HologramPictureTexture {

    private byte[] raster;
    private ResourceLocation location;

    void update(HologramPictureBox picture, byte[] pixels) {
        if (raster == pixels) return;
        clear();
        DynamicTexture texture = new DynamicTexture(picture.width, picture.height);
        int[] colors = texture.getTextureData();
        for (int i = 0; i < pixels.length; i++) {
            int index = (pixels[i] & 255) - 1;
            colors[i] = index < 0 ? 0 : 0xff000000 | picture.paletteColor(index);
        }
        texture.updateDynamicTexture();
        location = Minecraft.getMinecraft()
            .getTextureManager()
            .getDynamicTextureLocation("gtmb_picture", texture);
        raster = pixels;
    }

    void bind() {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(location);
    }

    void clear() {
        if (location != null) Minecraft.getMinecraft()
            .getTextureManager()
            .deleteTexture(location);
        location = null;
        raster = null;
    }
}
