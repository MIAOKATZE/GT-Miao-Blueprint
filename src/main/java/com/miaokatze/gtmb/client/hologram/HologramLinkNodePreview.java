package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;

import gregtech.api.covers.CoverRegistry;
import gregtech.common.covers.Cover;

/** GTSWN's procedural material and static relief mesh; reads snapshots, never live cover hosts or particles. */
final class HologramLinkNodePreview {

    private static final Map<HologramState.Cell, List<Node>[]> NODES = new IdentityHashMap<>();
    private static final Map<String, ResourceLocation[]> TEXTURES = new HashMap<>();
    private static final String VISUALS = "com.miaokatze.gtswn.client.render.LinkNodeVisuals";
    private static final String CONFIG = "com.miaokatze.gtswn.config.Config";
    private static boolean warned;

    private HologramLinkNodePreview() {}

    static void begin() {
        NODES.clear();
    }

    static void clear() {
        NODES.clear();
        for (ResourceLocation[] pair : TEXTURES.values()) for (ResourceLocation texture : pair) Minecraft.getMinecraft()
            .getTextureManager()
            .deleteTexture(texture);
        TEXTURES.clear();
    }

    static boolean enhanced(NBTTagCompound row) {
        try {
            return coverType(row) >= 0 && !legacy(material());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static String material() throws ReflectiveOperationException {
        return (String) Class.forName(CONFIG)
            .getField("tapMaterial")
            .get(null);
    }

    private static boolean legacy(String material) {
        return "old".equals(material) || "oldplus".equals(material);
    }

    private static int coverType(NBTTagCompound row) {
        Cover cover = CoverRegistry
            .buildCoverFromNbt((NBTTagCompound) row.copy(), ForgeDirection.getOrientation(row.getByte("s")), null);
        if (cover == null) return -1;
        String name = cover.getClass()
            .getName();
        if (name.equals("com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless")) return 0;
        if (name.equals("com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless")) return 1;
        return -1;
    }

    @SuppressWarnings("unchecked")
    static List<Node> prepare(HologramState.Cell cell, boolean current) {
        List<Node>[] pair = NODES.computeIfAbsent(cell, unused -> new List[2]);
        int index = current ? 1 : 0;
        if (pair[index] != null) return pair[index];
        List<Node> nodes = new ArrayList<>();
        NBTTagCompound display = current ? cell.actualDisplay : cell.targetDisplay;
        NBTTagList covers = display.getTagList("gt.covers", 10);
        try {
            for (int i = 0; i < covers.tagCount(); i++) {
                NBTTagCompound row = covers.getCompoundTagAt(i);
                int side = row.getByte("s");
                if (side < 0 || side >= 6) continue;
                int type = coverType(row);
                if (type < 0) continue;
                String material = material();
                if (legacy(material)) continue;
                Class<?> visuals = Class.forName(VISUALS), config = Class.forName(CONFIG);
                String key = material + ":" + type;
                ResourceLocation[] textures = TEXTURES.get(key);
                if (textures == null) {
                    int size = (Integer) visuals.getMethod("resolution", String.class)
                        .invoke(null, material);
                    textures = new ResourceLocation[2];
                    for (int pass = 0; pass < 2; pass++) {
                        DynamicTexture texture = new DynamicTexture(size, size);
                        int[] pixels = (int[]) visuals
                            .getMethod("pixels", int.class, double.class, boolean.class, boolean.class)
                            .invoke(null, size, 2.0, type == 0, pass == 1);
                        System.arraycopy(pixels, 0, texture.getTextureData(), 0, pixels.length);
                        texture.updateDynamicTexture();
                        textures[pass] = Minecraft.getMinecraft()
                            .getTextureManager()
                            .getDynamicTextureLocation("gtmb_link_node_" + type + "_" + pass, texture);
                    }
                    TEXTURES.put(key, textures);
                }
                nodes.add(
                    new Node(
                        side,
                        type == 0,
                        "high".equals(material),
                        textures,
                        visuals,
                        config.getField("linkNodeDepth")
                            .getDouble(null),
                        config.getField("linkNodeRelief")
                            .getDouble(null),
                        config.getField("linkNodeOpacity")
                            .getFloat(null)));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!warned) {
                warned = true;
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("GTSWN link node preview unavailable", failure);
            }
        }
        pair[index] = nodes;
        return nodes;
    }

    static void draw(HologramState.Cell cell, boolean current, float alpha) {
        List<Node> nodes = prepare(cell, current);
        if (nodes.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            for (Node node : nodes) {
                for (int pass = 0; pass < 2; pass++) {
                    Minecraft.getMinecraft()
                        .getTextureManager()
                        .bindTexture(node.textures[pass]);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, pass == 0 ? GL11.GL_ONE_MINUS_SRC_ALPHA : GL11.GL_ONE);
                    Tessellator tess = Tessellator.instance;
                    tess.startDrawingQuads();
                    tess.setBrightness(0xf000f0);
                    tess.setColorRGBA_F(1, 1, 1, alpha * (pass == 0 ? node.opacity : 1));
                    for (double[][] quad : node.surface)
                        for (double[] v : quad) tess.addVertexWithUV(v[0], v[1], v[2], v[3], v[4]);
                    tess.draw();
                }
                if (!node.walls.isEmpty()) {
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    Tessellator tess = Tessellator.instance;
                    tess.startDrawingQuads();
                    tess.setColorRGBA_F(
                        node.energy ? 1 : .87f,
                        node.energy ? .7f : .3f,
                        node.energy ? .14f : 1,
                        alpha * node.opacity * .55f);
                    for (double[][] quad : node.walls) for (double[] v : quad) tess.addVertex(v[0], v[1], v[2]);
                    tess.draw();
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    static final class Node {

        final boolean energy;
        final float opacity;
        final ResourceLocation[] textures;
        final List<double[][]> surface = new ArrayList<>(), walls = new ArrayList<>();

        Node(int side, boolean energy, boolean high, ResourceLocation[] textures, Class<?> visuals, double depth,
            double relief, float opacity) throws ReflectiveOperationException {
            this.energy = energy;
            this.opacity = opacity;
            this.textures = textures;
            Method point = visuals.getMethod("point", int.class, double.class, double.class, double.class);
            if (!high) {
                surface.add(
                    new double[][] { vertex(point, side, -.5, -.5, .001), vertex(point, side, .5, -.5, .001),
                        vertex(point, side, .5, .5, .001), vertex(point, side, -.5, .5, .001) });
                return;
            }
            double radius = visuals.getField("CELL_RADIUS")
                .getDouble(null);
            for (Object hex : (List<?>) visuals.getField("CELLS")
                .get(null)) {
                double u = hex.getClass()
                    .getField("u")
                    .getDouble(hex),
                    v = hex.getClass()
                        .getField("v")
                        .getDouble(hex);
                double d = Math.max(
                    .001,
                    (Double) visuals.getMethod("surfaceDepth", hex.getClass(), double.class, double.class)
                        .invoke(null, hex, depth, relief));
                for (int edge = 0; edge < 6; edge++) {
                    double a = edge * Math.PI / 3, b = (edge + 1) * Math.PI / 3;
                    double u1 = u + Math.cos(a) * radius, v1 = v + Math.sin(a) * radius;
                    double u2 = u + Math.cos(b) * radius, v2 = v + Math.sin(b) * radius;
                    surface.add(
                        new double[][] { vertex(point, side, u, v, d), vertex(point, side, u1, v1, d),
                            vertex(point, side, u2, v2, d), vertex(point, side, u, v, d) });
                    walls.add(
                        new double[][] { vertex(point, side, u1, v1, .001), vertex(point, side, u2, v2, .001),
                            vertex(point, side, u2, v2, d), vertex(point, side, u1, v1, d) });
                }
            }
        }

        private static double[] vertex(Method point, int side, double u, double v, double depth)
            throws ReflectiveOperationException {
            double[] p = (double[]) point.invoke(null, side, u, v, depth);
            return new double[] { p[0], p[1], p[2], u + .5, v + .5 };
        }
    }
}
