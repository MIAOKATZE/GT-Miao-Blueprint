package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

/**
 * A light, GPU-only tutorial backdrop. Call once after the underlying screen is drawn, in its full-screen orthographic
 * coordinates, before the clear focus and navigation. No renderer, timer or shared framebuffer is owned here.
 */
final class HologramEditorTutorialBlur {

    private static int texture, textureWidth, textureHeight;
    private static final float SAMPLE_ALPHA = .14f;
    private static final float PHYSICAL_RADIUS = 1.5f;

    private HologramEditorTutorialBlur() {}

    static void drawOutside(int logicalWidth, int logicalHeight, int x, int y, int width, int height) {
        if (logicalWidth <= 0 || logicalHeight <= 0) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        int physicalWidth = minecraft.displayWidth, physicalHeight = minecraft.displayHeight;
        if (physicalWidth <= 0 || physicalHeight <= 0) return;
        int left = clamp(x, 0, logicalWidth), top = clamp(y, 0, logicalHeight);
        int right = clamp((long) x + Math.max(0, width), left, logicalWidth);
        int bottom = clamp((long) y + Math.max(0, height), top, logicalHeight);
        if (left == 0 && top == 0 && right == logicalWidth && bottom == logicalHeight) return;

        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (physicalWidth > GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)
                || physicalHeight > GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)) return;
            if (texture == 0) texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            copyDrawBuffer(physicalWidth, physicalHeight);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColorMask(true, true, true, true);
            // Four .14-alpha samples leave 54.7% of the sharp original, followed by a separate neutral scrim.
            GL11.glColor4f(1, 1, 1, SAMPLE_ALPHA);
            float du = PHYSICAL_RADIUS / physicalWidth, dv = PHYSICAL_RADIUS / physicalHeight;
            sample(logicalWidth, logicalHeight, left, top, right, bottom, du, 0);
            sample(logicalWidth, logicalHeight, left, top, right, bottom, -du, 0);
            sample(logicalWidth, logicalHeight, left, top, right, bottom, 0, dv);
            sample(logicalWidth, logicalHeight, left, top, right, bottom, 0, -dv);
        } finally {
            GL11.glPopAttrib();
            GL13.glActiveTexture(activeTexture);
        }
    }

    private static void copyDrawBuffer(int physicalWidth, int physicalHeight) {
        boolean splitFramebuffer = GLContext.getCapabilities().OpenGL30
            || GLContext.getCapabilities().GL_ARB_framebuffer_object
            || GLContext.getCapabilities().GL_EXT_framebuffer_blit;
        int readFramebuffer = splitFramebuffer ? GL11.glGetInteger(0x8caa) : 0;
        int drawFramebuffer = splitFramebuffer ? GL11.glGetInteger(0x8ca6) : 0;
        // Copy the screen being drawn, including when a native preview used a different read FBO.
        if (readFramebuffer != drawFramebuffer) OpenGlHelper.func_153171_g(0x8ca8, drawFramebuffer);
        int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        try {
            GL11.glReadBuffer(GL11.glGetInteger(GL11.GL_DRAW_BUFFER));
            // Reallocate only on first use or physical resize. No readback or per-frame texture allocation.
            if (textureWidth != physicalWidth || textureHeight != physicalHeight) {
                GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, 0, 0, physicalWidth, physicalHeight, 0);
                textureWidth = physicalWidth;
                textureHeight = physicalHeight;
            } else {
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, physicalWidth, physicalHeight);
            }
        } finally {
            GL11.glReadBuffer(readBuffer);
            if (readFramebuffer != drawFramebuffer) OpenGlHelper.func_153171_g(0x8ca8, readFramebuffer);
        }
    }

    private static void sample(int screenWidth, int screenHeight, int left, int top, int right, int bottom, float du,
        float dv) {
        GL11.glBegin(GL11.GL_QUADS);
        try {
            quad(0, 0, screenWidth, top, screenWidth, screenHeight, du, dv);
            quad(0, bottom, screenWidth, screenHeight, screenWidth, screenHeight, du, dv);
            quad(0, top, left, bottom, screenWidth, screenHeight, du, dv);
            quad(right, top, screenWidth, bottom, screenWidth, screenHeight, du, dv);
        } finally {
            GL11.glEnd();
        }
    }

    private static void quad(int left, int top, int right, int bottom, int screenWidth, int screenHeight, float du,
        float dv) {
        if (right <= left || bottom <= top) return;
        float u0 = left / (float) screenWidth + du, u1 = right / (float) screenWidth + du;
        // Framebuffer copies begin at bottom left; the orthographic screen begins at top left.
        float v0 = 1 - top / (float) screenHeight + dv, v1 = 1 - bottom / (float) screenHeight + dv;
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2i(left, top);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex2i(left, bottom);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2i(right, bottom);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex2i(right, top);
    }

    private static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }

    /** Called when the active tutorial closes, on the render thread with its normal GL context current. */
    static void dispose() {
        int closingTexture = texture;
        // Relinquish ownership first, so a failed GL delete never leaves a stale reusable handle.
        texture = textureWidth = textureHeight = 0;
        if (closingTexture != 0) GL11.glDeleteTextures(closingTexture);
    }
}
