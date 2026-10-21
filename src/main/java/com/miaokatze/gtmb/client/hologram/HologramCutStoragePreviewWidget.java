package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

/** An isolated GUI camera over the same native raw ghost used in the world. Never ticks tile entities. */
final class HologramCutStoragePreviewWidget extends Widget<HologramCutStoragePreviewWidget> implements Interactable {

    private final HologramCutStoragePreviewView preview;
    private Framebuffer target;
    private float yaw = -35, pitch = 24, zoom = 1;
    private int dragX, dragY;
    private String failure = "";

    HologramCutStoragePreviewWidget(HologramCutStoragePreviewView preview) {
        this.preview = preview;
        background(HologramMuiTheme.PANEL);
    }

    void resetCamera() {
        yaw = -35;
        pitch = 24;
        zoom = 1;
        failure = "";
    }

    NBTTagCompound cameraState() {
        NBTTagCompound state = new NBTTagCompound();
        state.setFloat("yaw", yaw);
        state.setFloat("pitch", pitch);
        state.setFloat("zoom", zoom);
        return state;
    }

    void restoreCamera(NBTTagCompound state) {
        yaw = state.getFloat("yaw");
        pitch = Math.max(-89, Math.min(89, state.getFloat("pitch")));
        zoom = Math.max(.35f, Math.min(4f, state.getFloat("zoom")));
    }

    void orbitBy(float x, float y) {
        yaw += x * .7f;
        pitch = Math.max(-89, Math.min(89, pitch + y * .7f));
    }

    void zoomBy(float factor) {
        zoom = Math.max(.35f, Math.min(4f, zoom * factor));
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        Minecraft mc = Minecraft.getMinecraft();
        if (preview.renderer() == null || preview.raw() == null) {
            String message = preview.notice();
            if (message.isEmpty()) message = HologramCutStoragePreview.text("choose");
            mc.fontRenderer.drawSplitString(message, 7, 8, Math.max(20, getArea().w() - 14), 0xffddcfa8);
            return;
        }
        if (!OpenGlHelper.isFramebufferEnabled()) {
            mc.fontRenderer.drawSplitString(
                HologramCutStoragePreview.text("framebuffer"),
                7,
                8,
                Math.max(20, getArea().w() - 14),
                0xffffbb88);
            return;
        }
        ScaledResolution scaled = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        double sx = mc.displayWidth / (double) scaled.getScaledWidth();
        double sy = mc.displayHeight / (double) scaled.getScaledHeight();
        int left = (int) Math.round(context.transformX(0, 0) * sx);
        int top = (int) Math.round(context.transformY(0, 0) * sy);
        int right = (int) Math.round(context.transformX(getArea().w(), getArea().h()) * sx);
        int bottom = (int) Math.round(context.transformY(getArea().w(), getArea().h()) * sy);
        int w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0) return;
        HologramCutRawPreviewScope.RenderState state = new HologramCutRawPreviewScope.RenderState();
        state.push();
        try {
            if (target == null || target.framebufferWidth != mc.displayWidth
                || target.framebufferHeight != mc.displayHeight) {
                deleteTarget();
                target = new Framebuffer(mc.displayWidth, mc.displayHeight, true);
                target.setFramebufferFilter(GL11.GL_NEAREST);
            }
            target.bindFramebuffer(false);
            if (OpenGlHelper.func_153167_i(0x8d40) != 0x8cd5)
                throw new IllegalStateException("Storage preview framebuffer is incomplete");
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glClearColor(.075f, .10f, .12f, 1);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glViewport(left, mc.displayHeight - bottom, w, h);
            NBTTagCompound raw = preview.raw();
            double x = raw.getInteger("sizeX"), y = raw.getInteger("sizeY"), z = raw.getInteger("sizeZ");
            double radius = Math.max(1, Math.sqrt(x * x + y * y + z * z) / 2);
            double aspect = w / (double) h;
            double span = radius * 1.15 / Math.min(1, aspect) / zoom;
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(-span * aspect, span * aspect, -span, span, -radius * 12, radius * 12);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glRotatef(pitch, 1, 0, 0);
            GL11.glRotatef(yaw, 0, 1, 0);
            GL11.glTranslated(-x / 2, -y / 2, -z / 2);
            preview.renderer()
                .renderLocal(context.getPartialTicks());
            failure = "";
        } catch (RuntimeException error) {
            failure = HologramCutStoragePreview.text("unavailable") + " "
                + error.getClass()
                    .getSimpleName();
        } finally {
            state.restore();
        }
        if (!failure.isEmpty()) {
            mc.fontRenderer.drawSplitString(failure, 7, 8, Math.max(20, getArea().w() - 14), 0xffffbb88);
            return;
        }
        // Draw only this viewport's texels. The framebuffer owns depth; the world and adjacent MUI widgets do not.
        state = new HologramCutRawPreviewScope.RenderState();
        state.push();
        try {
            state.useProgram(0);
            state.cleanTextureUnits();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.framebufferTexture);
            GL11.glColor4f(1, 1, 1, 1);
            double u0 = left / (double) target.framebufferTextureWidth;
            double u1 = right / (double) target.framebufferTextureWidth;
            double v0 = (mc.displayHeight - top) / (double) target.framebufferTextureHeight;
            double v1 = (mc.displayHeight - bottom) / (double) target.framebufferTextureHeight;
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2d(u0, v0);
            GL11.glVertex2d(0, 0);
            GL11.glTexCoord2d(u0, v1);
            GL11.glVertex2d(0, getArea().h());
            GL11.glTexCoord2d(u1, v1);
            GL11.glVertex2d(getArea().w(), getArea().h());
            GL11.glTexCoord2d(u1, v0);
            GL11.glVertex2d(getArea().w(), 0);
            GL11.glEnd();
        } finally {
            state.restore();
        }
    }

    @Override
    public Result onMousePressed(int button) {
        if (button != 0) return Result.IGNORE;
        dragX = getContext().getAbsMouseX();
        dragY = getContext().getAbsMouseY();
        return Result.SUCCESS;
    }

    @Override
    public void onMouseDrag(int button, long timeSinceClick) {
        if (button != 0) return;
        int x = getContext().getAbsMouseX(), y = getContext().getAbsMouseY();
        orbitBy(x - dragX, y - dragY);
        dragX = x;
        dragY = y;
    }

    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        zoomBy(direction.isUp() ? 1.12f : 1 / 1.12f);
        return true;
    }

    private void deleteTarget() {
        if (target == null) return;
        target.deleteFramebuffer();
        target = null;
    }

    @Override
    public void dispose() {
        releaseTarget();
        super.dispose();
    }

    void releaseTarget() {
        HologramCutRawPreviewScope.RenderState state = new HologramCutRawPreviewScope.RenderState();
        state.push();
        try {
            deleteTarget();
        } finally {
            state.restore();
        }
    }
}
