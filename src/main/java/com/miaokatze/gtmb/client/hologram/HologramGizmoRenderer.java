package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/** One handle style for structure, blueprint and text placement editing. */
final class HologramGizmoRenderer {

    private HologramGizmoRenderer() {}

    static void draw(HologramGizmo gizmo, HologramUiCanvas canvas, int mouseX, int mouseY) {
        int highlighted = gizmo.axis() >= 0 ? gizmo.axis() : gizmo.pick(mouseX, mouseY);
        double[] origin = gizmo.start();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Tessellator buffer = Tessellator.instance;
            buffer.startDrawingQuads();
            for (int plane = HologramGizmo.XY; plane <= HologramGizmo.YZ; plane++) {
                buffer.setColorRGBA_I(HologramGizmo.color(plane) & 0xffffff, plane == highlighted ? 170 : 72);
                for (double[] point : gizmo.plane(plane)) buffer.addVertex(point[0], point[1], 20);
            }
            buffer.draw();
            GL11.glLineWidth(2);
            buffer.startDrawing(GL11.GL_LINES);
            for (int axis = 0; axis < 3; axis++) {
                buffer.setColorOpaque_I(axis == highlighted ? 0xffe6ad : HologramGizmo.COLORS[axis] & 0xffffff);
                double[] end = gizmo.end(axis);
                buffer.addVertex(origin[0], origin[1], 21);
                buffer.addVertex(end[0], end[1], 21);
            }
            for (int plane = HologramGizmo.XY; plane <= HologramGizmo.YZ; plane++) {
                double[][] corners = gizmo.plane(plane);
                buffer.setColorRGBA_I(HologramGizmo.color(plane) & 0xffffff, plane == highlighted ? 255 : 150);
                for (int i = 0; i < 4; i++) {
                    buffer.addVertex(corners[i][0], corners[i][1], 21);
                    buffer.addVertex(corners[(i + 1) % 4][0], corners[(i + 1) % 4][1], 21);
                }
            }
            buffer.draw();
            buffer.startDrawingQuads();
            for (int axis = 0; axis < 3; axis++) {
                double[] end = gizmo.end(axis);
                buffer.setColorOpaque_I(HologramGizmo.COLORS[axis] & 0xffffff);
                buffer.addVertex(end[0] - 3, end[1] - 3, 22);
                buffer.addVertex(end[0] - 3, end[1] + 3, 22);
                buffer.addVertex(end[0] + 3, end[1] + 3, 22);
                buffer.addVertex(end[0] + 3, end[1] - 3, 22);
            }
            buffer.draw();
        } finally {
            GL11.glPopAttrib();
        }
        for (int axis = 0; axis < 3; axis++) {
            double[] end = gizmo.end(axis);
            canvas.text(HologramGizmo.label(axis), (int) end[0] + 5, (int) end[1] - 4, 14, HologramGizmo.COLORS[axis]);
        }
    }
}
