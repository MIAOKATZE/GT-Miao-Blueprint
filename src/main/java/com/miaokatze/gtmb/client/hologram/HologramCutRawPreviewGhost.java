package com.miaokatze.gtmb.client.hologram;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.client.MinecraftForgeClient;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/** Composite native geometry once: native vertex colors/TESRs cannot override the final ghost opacity. */
final class HologramCutRawPreviewGhost {

    private static final int FRAMEBUFFER = 0x8d40, READ_FRAMEBUFFER = 0x8ca8, DRAW_FRAMEBUFFER = 0x8ca9;
    private static final float OPACITY = .65f;
    private static final float[] TINT = { .90f, .97f, 1 };
    private Framebuffer layer;
    private int destination = -1, width, height, priorGlError, copyGlError;
    private long composites, depthCopies, sampleChecks, sampleMismatches;
    private long depthChecks, depthMismatches, backgroundPixels;
    private long depthCopyChecks, depthCopyMismatches;
    private long inheritedGlErrors, depthCopyGlErrors;
    private String depthBefore = "", depthCopied = "", depthAfter = "";
    private final NBTTagList pixelEvidence = new NBTTagList();
    private boolean sampled, depthPending;
    private String failure = "";

    boolean begin() {
        Minecraft mc = Minecraft.getMinecraft();
        destination = -1;
        if (!OpenGlHelper.isFramebufferEnabled()
            || !(GLContext.getCapabilities().OpenGL30 || GLContext.getCapabilities().GL_ARB_framebuffer_object
                || GLContext.getCapabilities().GL_EXT_framebuffer_blit)) {
            failure = "Cut ghost requires framebuffer depth-copy support; showing outlines only";
            return false;
        }
        destination = GL11.glGetInteger(0x8ca6);
        // Retain an inherited error separately; never silently claim a depth copy succeeded through GL errors.
        priorGlError = GL11.glGetError();
        if (priorGlError != GL11.GL_NO_ERROR) {
            inheritedGlErrors++;
            failure = "Inherited GL error before cut ghost: " + priorGlError + "; showing outlines only";
            return false;
        }
        failure = "";
        width = mc.displayWidth;
        height = mc.displayHeight;
        boolean allocate = layer == null || layer.framebufferWidth != width || layer.framebufferHeight != height;
        if (allocate) release();
        if (!sampled && Boolean.getBoolean("gtmb.hologram.cutNativeClient")) {
            OpenGlHelper.func_153171_g(READ_FRAMEBUFFER, destination);
            pixelStore();
            depthBefore = sceneDepthDigest();
        }
        if (allocate) {
            // Forge's Framebuffer uses getStencilBits() to choose DEPTH24 or DEPTH24_STENCIL8, exactly as
            // Minecraft's scene framebuffer does. A mod-replaced incompatible target fails the checked copy below.
            layer = new Framebuffer(width, height, true);
            // Nearest filtering keeps the coverage mask and native texels exact at this 1:1 screen composite.
            layer.setFramebufferFilter(GL11.GL_NEAREST);
        }
        layer.bindFramebuffer(false);
        if (OpenGlHelper.func_153167_i(FRAMEBUFFER) != 0x8cd5)
            throw new IllegalStateException("Cut ghost framebuffer is incomplete");
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_LINE);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_POINT);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        OpenGlHelper.func_153171_g(READ_FRAMEBUFFER, destination);
        OpenGlHelper.func_153171_g(DRAW_FRAMEBUFFER, layer.framebufferObject);
        if (GLContext.getCapabilities().OpenGL30)
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        else if (GLContext.getCapabilities().GL_ARB_framebuffer_object) ARBFramebufferObject
            .glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        else EXTFramebufferBlit
            .glBlitFramebufferEXT(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        copyGlError = GL11.glGetError();
        if (copyGlError != GL11.GL_NO_ERROR) {
            depthCopyGlErrors++;
            failure = "Cut ghost depth-copy GL error: " + copyGlError + "; showing outlines only";
            bindDestination();
            return false;
        }
        depthCopies++;
        layer.bindFramebuffer(false);
        if (!sampled && Boolean.getBoolean("gtmb.hologram.cutNativeClient")) {
            depthCopied = sceneDepthDigest();
            depthCopyChecks++;
            if (!depthBefore.equals(depthCopied)) depthCopyMismatches++;
        }
        return true;
    }

    void composite(HologramCutRawPreviewScope.RenderState scope) {
        List<Sample> samples = samples();
        bindDestination();
        GL11.glReadBuffer(destination == 0 ? GL11.GL_BACK : 0x8ce0);
        for (Sample sample : samples) sample.before = pixel(sample.x, sample.y);
        scope.useProgram(0);
        scope.cleanTextureUnits();
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_LINE);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_POINT);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            for (int plane = 0; plane < 6; plane++) GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
            // Keep the real scene's alpha too; only the temporary layer needs native alpha writes.
            GL11.glColorMask(true, true, true, false);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer.framebufferTexture);
            GL11.glColor4f(TINT[0], TINT[1], TINT[2], OPACITY);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0, 0);
            GL11.glVertex2f(-1, -1);
            GL11.glTexCoord2f(1, 0);
            GL11.glVertex2f(1, -1);
            GL11.glTexCoord2f(1, 1);
            GL11.glVertex2f(1, 1);
            GL11.glTexCoord2f(0, 1);
            GL11.glVertex2f(-1, 1);
            GL11.glEnd();
            composites++;
            for (Sample sample : samples) {
                byte[] after = pixel(sample.x, sample.y);
                float alpha = (sample.rgba[3] & 255) / 255f * OPACITY;
                boolean mismatch = after[3] != sample.before[3];
                boolean differsFromBackground = false, differsFromOpaqueModel = false;
                for (int channel = 0; channel < 3; channel++) {
                    float expected = (sample.rgba[channel] & 255) * TINT[channel] * alpha
                        + (sample.before[channel] & 255) * (1 - alpha);
                    if (Math.abs((after[channel] & 255) - expected) > 3) mismatch = true;
                    if (Math.abs((after[channel] & 255) - (sample.before[channel] & 255)) > 8)
                        differsFromBackground = true;
                    if (Math.abs((after[channel] & 255) - (sample.rgba[channel] & 255) * TINT[channel]) > 8)
                        differsFromOpaqueModel = true;
                }
                sampleChecks++;
                if (mismatch) sampleMismatches++;
                if (differsFromBackground && differsFromOpaqueModel) backgroundPixels++;
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("x", sample.x);
                row.setInteger("y", sample.y);
                row.setByteArray("nativeRgba", sample.rgba);
                row.setByteArray("sceneBeforeRgba", sample.before);
                row.setByteArray("compositedRgba", after);
                row.setFloat("effectiveAlpha", alpha);
                pixelEvidence.appendTag(row);
            }
            if (!samples.isEmpty()) {
                sampled = true;
                depthPending = true;
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
    }

    /** Opt-in readback measures the actual composite, before borders, rather than treating draw calls as pixels. */
    private List<Sample> samples() {
        List<Sample> result = new ArrayList<>();
        if (sampled || !Boolean.getBoolean("gtmb.hologram.cutNativeClient")) return result;
        GL11.glReadBuffer(0x8ce0);
        ByteBuffer image = BufferUtils.createByteBuffer(width * height * 4);
        pixelStore();
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
        // Spread native-covered samples across the frame, including fully opaque native pixels.
        for (int y = 0; y < height && result.size() < 16; y += Math.max(1, height / 64))
            for (int x = 0; x < width && result.size() < 16; x += Math.max(1, width / 64)) {
                int offset = (y * width + x) * 4;
                if ((image.get(offset + 3) & 255) < 240) continue;
                byte[] rgba = new byte[4];
                for (int channel = 0; channel < 4; channel++) rgba[channel] = image.get(offset + channel);
                result.add(new Sample(x, y, rgba));
            }
        return result;
    }

    private static byte[] pixel(int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        byte[] result = new byte[4];
        pixel.get(result);
        return result;
    }

    private static void pixelStore() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, GL11.GL_FALSE);
        GL11.glPixelStorei(GL11.GL_PACK_LSB_FIRST, GL11.GL_FALSE);
    }

    private String sceneDepthDigest() {
        ByteBuffer values = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, values);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(values);
            StringBuilder result = new StringBuilder();
            for (byte value : hash.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    String failure() {
        return failure;
    }

    void finishFrame() {
        if (!depthPending) return;
        // Includes both the native layer and the subsequent real-world edge pass.
        depthAfter = sceneDepthDigest();
        depthChecks++;
        if (!depthBefore.equals(depthAfter)) depthMismatches++;
        depthPending = false;
    }

    void bindDestination() {
        if (destination >= 0) OpenGlHelper.func_153171_g(FRAMEBUFFER, destination);
    }

    void inspect(NBTTagCompound out) {
        out.setString("ghostMethod", "native-offscreen-depth-copy-tinted-alpha-composite");
        out.setInteger("ghostStencilBits", MinecraftForgeClient.getStencilBits());
        out.setInteger("ghostInheritedGlError", priorGlError);
        out.setInteger("ghostDepthCopyGlError", copyGlError);
        out.setLong("ghostInheritedGlErrors", inheritedGlErrors);
        out.setLong("ghostDepthCopyGlErrors", depthCopyGlErrors);
        out.setFloat("ghostOpacity", OPACITY);
        out.setLong("ghostComposites", composites);
        out.setLong("ghostDepthCopies", depthCopies);
        out.setLong("ghostDepthCopyChecks", depthCopyChecks);
        out.setLong("ghostDepthCopyMismatches", depthCopyMismatches);
        out.setString("ghostCopiedSceneDepthSha256", depthCopied);
        out.setLong("ghostPixelChecks", sampleChecks);
        out.setLong("ghostPixelMismatches", sampleMismatches);
        out.setLong("ghostVisibleBackgroundPixels", backgroundPixels);
        out.setTag("ghostPixelEvidence", pixelEvidence.copy());
        out.setLong("ghostSceneDepthChecks", depthChecks);
        out.setLong("ghostSceneDepthMismatches", depthMismatches);
        out.setString("ghostSceneDepthBeforeSha256", depthBefore);
        out.setString("ghostSceneDepthAfterSha256", depthAfter);
        out.setString("ghostFailure", failure);
    }

    void release() {
        if (layer == null) {
            sampled = false;
            return;
        }
        HologramCutRawPreviewScope.RenderState scope = new HologramCutRawPreviewScope.RenderState();
        scope.push();
        try {
            // Vanilla deletion unbinds the active texture and framebuffer; cancellation must not leak those changes.
            layer.deleteFramebuffer();
        } finally {
            layer = null;
            sampled = false;
            scope.restore();
        }
    }

    private static final class Sample {

        final int x, y;
        final byte[] rgba;
        byte[] before;

        Sample(int x, int y, byte[] rgba) {
            this.x = x;
            this.y = y;
            this.rgba = rgba;
        }
    }
}
