package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.client.MinecraftForgeClient;

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
    private long composites, depthCopies;
    private long inheritedGlErrors, depthCopyGlErrors;
    private String failure = "";
    private final HologramCutGhostDiagnostics diagnostics = HologramCutGhostDiagnostics.create(OPACITY, TINT);

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
        diagnostics.beforeDepthCopy(destination, width, height);
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
        diagnostics.afterDepthCopy();
        return true;
    }

    void composite(HologramCutRawPreviewScope.RenderState scope) {
        diagnostics.sampleLayer();
        bindDestination();
        GL11.glReadBuffer(destination == 0 ? GL11.GL_BACK : 0x8ce0);
        diagnostics.beforeComposite();
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
            diagnostics.afterComposite();
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
    }

    String failure() {
        return failure;
    }

    void finishFrame() {
        diagnostics.finishFrame();
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
        diagnostics.inspect(out);
        out.setString("ghostFailure", failure);
    }

    void release() {
        if (layer == null) {
            diagnostics.reset();
            return;
        }
        HologramCutRawPreviewScope.RenderState scope = new HologramCutRawPreviewScope.RenderState();
        scope.push();
        try {
            // Vanilla deletion unbinds the active texture and framebuffer; cancellation must not leak those changes.
            layer.deleteFramebuffer();
        } finally {
            layer = null;
            diagnostics.reset();
            scope.restore();
        }
    }

}
