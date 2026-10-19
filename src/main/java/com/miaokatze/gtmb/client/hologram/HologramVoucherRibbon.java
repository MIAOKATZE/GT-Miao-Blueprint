package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

/** A contained gold corner strip; no hit target or material authorization. */
final class HologramVoucherRibbon {

    static final String TITLE = "§6完全抵用";
    static final String HELP = "保存或升级时全部有效内容可用猫猫装饰抵用券抵扣；实际施工以当前服务器材料核算为准。";
    private static final ResourceLocation VOUCHER = new ResourceLocation(
        "gtmb",
        "textures/items/decoration_voucher.png");

    private HologramVoucherRibbon() {}

    static void draw(float x, float y, float width, float height) {
        float side = Math.min(58, Math.min(width, height)), band = side * .58f;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
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
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(VOUCHER);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glTranslatef((side + band / 2) / 2, (side - band / 2) / 2, 1);
            GL11.glRotatef(45, 0, 0, 1);
            float iconWidth = band * .9f, iconHeight = iconWidth * .75f;
            // Reuse only the cat head from frame zero of the original 24 x 96 voucher sprite.
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(-iconWidth / 2, -iconHeight / 2, 0, 4d / 24, 6d / 96);
            tessellator.addVertexWithUV(-iconWidth / 2, iconHeight / 2, 0, 4d / 24, 18d / 96);
            tessellator.addVertexWithUV(iconWidth / 2, iconHeight / 2, 0, 20d / 24, 18d / 96);
            tessellator.addVertexWithUV(iconWidth / 2, -iconHeight / 2, 0, 20d / 24, 6d / 96);
            tessellator.draw();
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
