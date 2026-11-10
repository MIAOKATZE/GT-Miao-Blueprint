package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Opt-in comparison: original registered world renderers, raw source tile NBT and the editor's exact camera. */
public final class HologramSourcePreviewReference {

    private static final FloatBuffer model = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private static final IntBuffer scissor = BufferUtils.createIntBuffer(16);
    private static double depthScale;
    private static boolean captured;

    private HologramSourcePreviewReference() {}

    static void captureCamera(double scale) {
        if (!Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke")) return;
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, model);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissor);
        depthScale = scale;
        captured = true;
    }

    /** Input rows use the preview coordinates/offsets, but display.nativeTile must be the original source NBT. */
    public static NBTTagCompound renderReference(NBTTagCompound sourceSnapshot, NBTTagCompound previewSnapshot,
        boolean current) throws ReflectiveOperationException {
        require(
            captured && Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"),
            "source reference requires a captured real editor camera");
        HologramState preview = (HologramState) read("previewState");
        require(
            preview != null && preview.data.equals(previewSnapshot),
            "reference must use this actual editor snapshot");
        int renderedView = ((Number) read("visibleView")).intValue();
        int renderedMode = ((Number) read("visibleMode")).intValue();
        require(
            (renderedView == 1 || renderedMode == 2) == current,
            "reference requires the actual displayed target/current view");
        HologramState source = new HologramState(sourceSnapshot);
        HologramBlockAccess access = new HologramBlockAccess(source, current);
        NBTTagList rows = new NBTTagList();
        int oldMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadMatrix(projection);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadMatrix(model);
        try {
            GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(scissor.get(0), scissor.get(1), scissor.get(2), scissor.get(3));
            GL11.glDepthMask(true);
            GL11.glClearDepth(1);
            GL11.glClearColor(.04f, .08f, .1f, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, .01f);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            double x = ((Number) read("projectedX")).doubleValue();
            double y = ((Number) read("projectedY")).doubleValue();
            double width = ((Number) read("projectedWidth")).doubleValue();
            double height = ((Number) read("projectedHeight")).doubleValue();
            double scale = ((Number) read("projectedScale")).doubleValue();
            double pitch = ((Number) read("projectedPitch")).doubleValue();
            double yaw = ((Number) read("projectedYaw")).doubleValue();
            double bx = ((Number) read("boundsX")).doubleValue();
            double by = ((Number) read("boundsY")).doubleValue();
            double bz = ((Number) read("boundsZ")).doubleValue();
            for (HologramState.Cell cell : source.cells) {
                if (cell.block(current) == null || cell.block(current) == net.minecraft.init.Blocks.air) continue;
                GL11.glPushMatrix();
                List<double[]> attributes;
                try {
                    GL11.glTranslated(x + width / 2, y + height / 2, 0);
                    GL11.glScaled(scale, -scale, depthScale);
                    GL11.glRotated(Math.toDegrees(pitch), 1, 0, 0);
                    GL11.glRotated(-Math.toDegrees(yaw), 0, 1, 0);
                    GL11.glTranslated(cell.dx - bx - .5, cell.dy - by - .5, cell.dz - bz - .5);
                    attributes = HologramBlockRender.drawSourceReference(cell, current, access);
                } finally {
                    GL11.glPopMatrix();
                }
                NBTTagCompound row = new NBTTagCompound();
                row.setString("block", current ? cell.id : cell.wantId);
                row.setInteger("x", cell.x);
                row.setInteger("y", cell.y);
                row.setInteger("z", cell.z);
                if (HologramNativeTiles.supported(cell.block(current))) {
                    require(
                        attributes.size() >= 3,
                        "original native source renderer produced no reference surface: "
                            + (current ? cell.id : cell.wantId)
                            + " at "
                            + cell.x
                            + ":"
                            + cell.y
                            + ":"
                            + cell.z);
                    HologramState.Cell planned = null;
                    for (HologramState.Cell candidate : preview.cells)
                        if (candidate.x == cell.x && candidate.y == cell.y && candidate.z == cell.z) {
                            planned = candidate;
                            break;
                        }
                    require(planned != null, "source reference position must exist in the preview");
                    List<double[]> actual = HologramBlockRender.attributes(planned, current);
                    int matching = 0;
                    for (double[] expected : attributes) {
                        for (double[] emitted : actual) {
                            boolean same = true;
                            for (int component = 0; component < expected.length; component++)
                                if (Math.abs(expected[component] - emitted[component]) > 1e-6) {
                                    same = false;
                                    break;
                                }
                            if (same) {
                                matching++;
                                break;
                            }
                        }
                    }
                    row.setInteger("referenceVertices", attributes.size());
                    row.setInteger("matchingPreviewVertices", matching);
                    require(
                        matching == attributes.size(),
                        "preview differs from original native source position/UV/color: "
                            + (current ? cell.id : cell.wantId)
                            + " matched "
                            + matching
                            + "/"
                            + attributes.size());
                }
                NBTTagList geometry = new NBTTagList();
                for (double[] vertex : attributes) {
                    NBTTagList components = new NBTTagList();
                    for (double component : vertex) components.appendTag(new NBTTagDouble(component));
                    geometry.appendTag(components);
                }
                row.setTag("originalRendererVertices", geometry);
                rows.appendTag(row);
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(oldMode);
            GL11.glPopAttrib();
        }
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("originalWorldFaceSelection", true);
        receipt.setBoolean("rawSourceTilesDetached", true);
        receipt.setTag("sourceModels", rows);
        return receipt;
    }

    static double[][] framebufferPoints(double[][] points) {
        require(captured, "face polygon requires the actual editor camera");
        double[][] pixels = new double[points.length][3];
        for (int i = 0; i < points.length; i++) {
            double[] eye = multiply(model, new double[] { points[i][0], points[i][1], points[i][2] * depthScale, 1 });
            double[] clip = multiply(projection, eye);
            pixels[i][0] = viewport.get(0) + (clip[0] / clip[3] + 1) * viewport.get(2) / 2;
            pixels[i][1] = viewport.get(1) + (clip[1] / clip[3] + 1) * viewport.get(3) / 2;
            pixels[i][2] = (clip[2] / clip[3] + 1) / 2;
        }
        return pixels;
    }

    private static double[] multiply(FloatBuffer matrix, double[] vector) {
        double[] result = new double[4];
        for (int row = 0; row < 4; row++)
            for (int column = 0; column < 4; column++) result[row] += matrix.get(column * 4 + row) * vector[column];
        return result;
    }

    private static Object read(String name) throws IllegalAccessException {
        Field field = ReflectionHelper.findField(HologramRenderer.class, name);
        return field.get(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
