// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.miaovisonlite;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/** A depth-backed render target for previews and composition, with caller state managed by RenderState. */
public final class MiaoVisonLiteTarget implements AutoCloseable {

    private final Framebuffer framebuffer;
    private boolean closed;

    public MiaoVisonLiteTarget(int width, int height) {
        framebuffer = new Framebuffer(width, height, true);
    }

    public boolean sizeMatches(int width, int height) {
        return framebuffer.framebufferWidth == width && framebuffer.framebufferHeight == height;
    }

    public int textureWidth() {
        return framebuffer.framebufferTextureWidth;
    }

    public int textureHeight() {
        return framebuffer.framebufferTextureHeight;
    }

    public void nearestFiltering() {
        framebuffer.setFramebufferFilter(GL11.GL_NEAREST);
    }

    public void bind(boolean viewport) {
        framebuffer.bindFramebuffer(viewport);
    }

    public void bindColorTexture() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebuffer.framebufferTexture);
    }

    public boolean complete() {
        return OpenGlHelper.func_153167_i(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    public static boolean supportsDepthCopy() {
        ContextCapabilities caps = GLContext.getCapabilities();
        return caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_blit;
    }

    public void copyDepthFrom(int source) {
        int width = framebuffer.framebufferWidth, height = framebuffer.framebufferHeight;
        ContextCapabilities caps = GLContext.getCapabilities();
        OpenGlHelper.func_153171_g(GL30.GL_READ_FRAMEBUFFER, source);
        OpenGlHelper.func_153171_g(GL30.GL_DRAW_FRAMEBUFFER, framebuffer.framebufferObject);
        if (caps.OpenGL30)
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        else if (caps.GL_ARB_framebuffer_object) ARBFramebufferObject
            .glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        else EXTFramebufferBlit
            .glBlitFramebufferEXT(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
    }

    public void readRgba(MiaoVisonLiteRenderScope scope, ByteBuffer pixels) {
        scope.prepareReadback();
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glReadPixels(
            0,
            0,
            framebuffer.framebufferWidth,
            framebuffer.framebufferHeight,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            pixels);
    }

    @Override
    public void close() {
        if (!closed) {
            framebuffer.deleteFramebuffer();
            closed = true;
        }
    }
}
