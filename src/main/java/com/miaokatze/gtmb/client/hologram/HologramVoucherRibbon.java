package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/** A contained gold corner strip; no hit target or material authorization. */
final class HologramVoucherRibbon {

    private HologramVoucherRibbon() {}

    static void draw(float x, float y, float width, float height) {
        float side = Math.min(58, Math.min(width, height)), band = side * .34f;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x + width - side, y, 2);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            tessellator.setColorOpaque_I(0xe8bb55);
            tessellator.addVertex(0, 0, 0);
            tessellator.addVertex(band, 0, 0);
            tessellator.addVertex(side, side - band, 0);
            tessellator.addVertex(side, side, 0);
            tessellator.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            String label = "完全抵用";
            GL11.glTranslatef((side + band / 2) / 2, (side - band / 2) / 2, 1);
            GL11.glRotatef(45, 0, 0, 1);
            float scale = Math.min(.8f, side * .95f / Math.max(1, font.getStringWidth(label)));
            GL11.glScalef(scale, scale, 1);
            font.drawString(label, -font.getStringWidth(label) / 2, -font.FONT_HEIGHT / 2, 0x49320b);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
