// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/** Actual GPU submission and framebuffer readback for a detached metadata-bearing local blueprint. */
final class HologramLitePreviewProbe {

    private static final int SIZE = 128;
    private static final int FRAMEBUFFER = 0x8d40;
    private static final int RED = 17, GREEN = 27, BLUE = 42;
    private final HologramState fixture;

    HologramLitePreviewProbe() throws java.io.IOException {
        String json = "{\"blueprint\":{\"cells\":[{\"x\":0,\"block\":\"minecraft:stone\"},"
            + "{\"x\":1,\"block\":\"minecraft:planks\"},{\"y\":1,\"block\":\"minecraft:glass\"}]}}";
        NBTTagCompound source = HologramBlueprintPreview.snapshot(json.getBytes(StandardCharsets.UTF_8));
        source.setBoolean("localBlueprintDocument", true);
        fixture = new HologramState(source);
    }

    boolean frame(Map<String, Object> receipt) {
        if (!OpenGlHelper.isFramebufferEnabled())
            throw new IllegalStateException("Lite probe requires framebuffer support");
        boolean splitFramebuffer = GLContext.getCapabilities().OpenGL30
            || GLContext.getCapabilities().GL_ARB_framebuffer_object;
        int draw = GL11.glGetInteger(0x8ca6);
        int read = splitFramebuffer ? GL11.glGetInteger(0x8caa) : draw;
        int renderbuffer = GL11.glGetInteger(0x8ca7);
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        Framebuffer target = null;
        try {
            target = new Framebuffer(SIZE, SIZE, true);
            target.bindFramebuffer(true);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, SIZE, SIZE);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glDepthRange(0, 1);
            GL11.glClearColor(RED / 255f, GREEN / 255f, BLUE / 255f, 1);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, SIZE, SIZE, 0, -1000, 1000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();

            HologramRenderer.preview(fixture, 0, 0, SIZE, SIZE, 36, 28, 1, Integer.MAX_VALUE, 0, -1, -1, -1, -1);
            boolean lite = HologramRenderer.litePreviewActive();
            boolean ready = HologramRenderer.previewMeshReady(fixture);
            boolean visible = HologramRenderer.previewMeshVisible(fixture);
            int draws = HologramRenderer.litePreviewDraws();
            receipt.put("backend", lite ? "MiaoVisonLite" : "legacy-fallback");
            receipt.put("meshReady", ready);
            receipt.put("meshVisible", visible);
            receipt.put("sourceCells", fixture.cells.size());
            receipt.put("preparedCells", HologramRenderer.previewMeshCompleted(fixture));
            receipt.put("submittedDraws", draws);
            if (!ready) return false;

            GL11.glReadBuffer(0x8ce0);
            ByteBuffer pixels = new HologramNativeRasterSmoke.Slice(0, 0, SIZE, SIZE).read();
            int changed = 0;
            for (int offset = 0; offset < pixels.capacity(); offset += 4) {
                int difference = Math.abs((pixels.get(offset) & 255) - RED)
                    + Math.abs((pixels.get(offset + 1) & 255) - GREEN)
                    + Math.abs((pixels.get(offset + 2) & 255) - BLUE);
                if (difference > 12) changed++;
            }
            receipt.put("changedRgbPixels", changed);
            if (!lite || !visible || draws == 0 || changed < 128 || changed >= SIZE * SIZE)
                throw new IllegalStateException("Lite preview did not rasterize fixture geometry: " + receipt);
            return true;
        } finally {
            if (target != null) target.deleteFramebuffer();
            OpenGlHelper.func_153171_g(splitFramebuffer ? 0x8ca9 : FRAMEBUFFER, draw);
            if (splitFramebuffer) OpenGlHelper.func_153171_g(0x8ca8, read);
            OpenGlHelper.func_153176_h(0x8d41, renderbuffer);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            GL11.glPopAttrib();
        }
    }
}
