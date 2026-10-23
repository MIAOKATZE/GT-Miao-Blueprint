package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.AxisAlignedBB;

import org.lwjgl.opengl.GL11;

/** Read-only bounded overlay of local selection geometry or the last authoritative server mirror. */
final class HologramAreaOverlay {

    static final class RenderWitness {

        final HologramState state;
        private final int[] cursor;

        RenderWitness(HologramState state, int[] cursor) {
            this.state = state;
            this.cursor = cursor == null ? null : cursor.clone();
        }

        int[] cursor() {
            return cursor == null ? null : cursor.clone();
        }
    }

    private static RenderWitness lastRenderWitness;
    private static int[] outlineVertices, outlineCursor;
    private static boolean outlineClosed;
    private static int outlineAxis = -1;
    private static long polygonStartFlashUntil;
    private static java.util.List<int[]> outline = java.util.Collections.emptyList();

    private HologramAreaOverlay() {}

    static RenderWitness lastRenderWitness() {
        return lastRenderWitness;
    }

    static void clearRenderWitness() {
        lastRenderWitness = null;
    }

    static void flashPolygonStart() {
        polygonStartFlashUntil = System.nanoTime() + 1_500_000_000L;
    }

    static void render(HologramState state, float partialTicks) {
        render(state, partialTicks, HologramClient.areaProjectorWorld() ? HologramClient.areaSelectionAnchor() : null);
    }

    static void render(HologramState state, float partialTicks, int[] cursor) {
        renderSelection(state, state.data, partialTicks, cursor);
    }

    static void renderSelection(HologramState state, NBTTagCompound selection, float partialTicks, int[] cursor) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.renderViewEntity;
        int[] points = selection.getIntArray("areaPoints");
        lastRenderWitness = null;
        if (camera == null) return;
        double px = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double py = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double pz = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glLineWidth(2);
            NBTTagList regions = selection.getTagList("areaRegions", 10);
            drawPolygon(selection, cursor, px, py, pz);
            for (int i = 0; i < regions.tagCount(); i++) {
                NBTTagCompound region = regions.getCompoundTagAt(i);
                boolean current = selection.hasKey("areaCurrentRegion", 3)
                    && i == selection.getInteger("areaCurrentRegion");
                GL11.glLineWidth(current ? 4 : 2);
                if ("subtract".equals(region.getString("mode"))) GL11.glColor4f(1, .35f, .35f, current ? 1 : .8f);
                else GL11.glColor4f(.35f, .85f, 1, current ? 1 : .8f);
                draw(
                    region.getInteger("minX"),
                    region.getInteger("minY"),
                    region.getInteger("minZ"),
                    region.getInteger("maxX"),
                    region.getInteger("maxY"),
                    region.getInteger("maxZ"),
                    px,
                    py,
                    pz);
            }
            // A completed box is already part of areaRegions (including red subtraction boxes).
            if (points.length >= 3 && (points.length < 6 || regions.tagCount() == 0)) {
                GL11.glLineWidth(2);
                int bx = points[0], by = points[1], bz = points[2];
                if (points.length >= 6) {
                    bx = points[3];
                    by = points[4];
                    bz = points[5];
                } else if (cursor != null) {
                    bx = cursor[0];
                    by = cursor[1];
                    bz = cursor[2];
                }
                if ("subtract".equals(selection.getString("areaPointMode"))) GL11.glColor4f(1, .35f, .35f, .85f);
                else GL11.glColor4f(.35f, .85f, 1, .85f);
                draw(points[0], points[1], points[2], bx, by, bz, px, py, pz);
            }
            // Draw last so the live red target remains legible on confirmed regions and the pending cyan box.
            if (cursor != null && showTarget(HologramIsometricCamera.INSTANCE.active(), HologramClient.controlHeld())) {
                GL11.glLineWidth(3);
                GL11.glColor4f(1, .15f, .15f, 1);
                draw(cursor[0], cursor[1], cursor[2], cursor[0], cursor[1], cursor[2], px, py, pz);
            }
            if (selection.getBoolean("areaContinuous"))
                drawPolygonVertices(selection.getIntArray("areaPolygon"), px, py, pz);
            lastRenderWitness = new RenderWitness(state, cursor);
        } finally {
            GL11.glPopAttrib();
        }
    }

    static boolean showTarget(boolean isometric, boolean control) {
        return !isometric || control;
    }

    private static void draw(int ax, int ay, int az, int bx, int by, int bz, double px, double py, double pz) {
        AxisAlignedBB box = AxisAlignedBB
            .getBoundingBox(
                Math.min(ax, bx),
                Math.min(ay, by),
                Math.min(az, bz),
                Math.max(ax, bx) + 1D,
                Math.max(ay, by) + 1D,
                Math.max(az, bz) + 1D)
            .expand(.002, .002, .002)
            .getOffsetBoundingBox(-px, -py, -pz);
        RenderGlobal.drawOutlinedBoundingBox(box, -1);
    }

    private static void drawPolygon(NBTTagCompound selection, int[] cursor, double px, double py, double pz) {
        if (!selection.getBoolean("areaContinuous")) return;
        int[] vertices = selection.getIntArray("areaPolygon");
        if (vertices.length < 3) return;
        boolean closed = selection.getBoolean("areaPolygonClosed");
        boolean subtract = "subtract".equals(selection.getString("areaPolygonMode"));
        GL11.glColor4f(subtract ? 1 : .35f, subtract ? .35f : .85f, subtract ? .35f : 1, 1);
        GL11.glLineWidth(3);
        int axis = selection.hasKey("areaPolygonHeightAxis", 3) ? selection.getInteger("areaPolygonHeightAxis") : 1;
        if (axis < 0 || axis > 2) return;
        int base = vertices[axis];
        int height = selection.getBoolean("areaPolygonHeightChosen") ? selection.getInteger("areaPolygonHeight")
            : closed && cursor != null ? cursor[axis] : base;
        if (!java.util.Arrays.equals(vertices, outlineVertices) || !java.util.Arrays.equals(cursor, outlineCursor)
            || closed != outlineClosed
            || axis != outlineAxis) {
            outlineVertices = vertices.clone();
            outlineCursor = cursor == null ? null : cursor.clone();
            outlineClosed = closed;
            outlineAxis = axis;
            outline = HologramSelectionOutline.polygon(vertices, cursor, closed, axis);
        }
        java.util.Map<Long, Integer> corners = new java.util.HashMap<>();
        GL11.glBegin(GL11.GL_LINES);
        for (int[] edge : outline) {
            for (int level : new int[] { Math.min(base, height), Math.max(base, height) + 1 }) {
                polygonVertex(axis, edge[0], edge[1], level, px, py, pz);
                polygonVertex(axis, edge[2], edge[3], level, px, py, pz);
            }
            for (int i : new int[] { 0, 2 }) {
                long key = (long) edge[i] << 32 | edge[i + 1] & 0xffffffffL;
                corners.put(key, corners.getOrDefault(key, 0) | (edge[0] == edge[2] ? 1 : 2));
            }
        }
        corners.forEach((key, directions) -> {
            if (directions != 3) return;
            int x = (int) (key >> 32), z = (int) (long) key;
            polygonVertex(axis, x, z, Math.min(base, height), px, py, pz);
            polygonVertex(axis, x, z, Math.max(base, height) + 1, px, py, pz);
        });
        GL11.glEnd();
        int[] lock = selection.getIntArray("areaPolygonHeightPoint");
        if (closed && selection.getBoolean("areaPolygonHeightChosen") && lock.length == 3) {
            int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
            GL11.glColor4f(subtract ? 1 : .35f, subtract ? .35f : .85f, subtract ? .35f : 1, 1);
            GL11.glLineWidth(5);
            GL11.glBegin(GL11.GL_LINES);
            polygonVertex(axis, lock[u] + .5, lock[v] + .5, Math.min(base, height), px, py, pz);
            polygonVertex(axis, lock[u] + .5, lock[v] + .5, Math.max(base, height) + 1, px, py, pz);
            GL11.glEnd();
        }
    }

    private static void drawPolygonVertices(int[] vertices, double px, double py, double pz) {
        long remaining = polygonStartFlashUntil - System.nanoTime();
        boolean flash = remaining > 0 && remaining / 150_000_000L % 2 == 0;
        for (int i = 0; i < vertices.length; i += 3) {
            if (i == 0) {
                GL11.glLineWidth(flash ? 6 : 4);
                GL11.glColor4f(flash ? 1 : .75f, flash ? .45f : .2f, 1, 1);
            } else {
                GL11.glLineWidth(3);
                GL11.glColor4f(.05f, .45f, .2f, 1);
            }
            draw(
                vertices[i],
                vertices[i + 1],
                vertices[i + 2],
                vertices[i],
                vertices[i + 1],
                vertices[i + 2],
                px,
                py,
                pz);
        }
    }

    private static void polygonVertex(int axis, double u, double v, double height, double px, double py, double pz) {
        GL11.glVertex3d(
            (axis == 0 ? height : u) - px,
            (axis == 1 ? height : axis == 0 ? u : v) - py,
            (axis == 2 ? height : v) - pz);
    }
}
