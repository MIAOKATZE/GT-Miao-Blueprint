package com.miaokatze.gtmb.client.hologram;

import java.nio.FloatBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.AxisAlignedBB;

import org.lwjgl.BufferUtils;
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
    private static final float CORNER_TEXT_SCALE = 1.5f;
    private static final FloatBuffer CORNER_MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer CORNER_PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer CORNER_BILLBOARD = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer SURFACE_MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer SURFACE_PROJECTION = BufferUtils.createFloatBuffer(16);
    private static java.util.List<HologramSelectionSurface.Box> surfaceBoxes = java.util.Collections.emptyList();
    private static java.util.List<HologramSelectionSurface.Face> surfaceFaces = java.util.Collections.emptyList();
    private static HologramSelectionSurface.Builder surfaceBuilder;
    private static final java.util.List<SurfaceBatch> surfaceLists = new java.util.ArrayList<>();
    private static int surfaceUploaded;
    private static double surfaceOriginX, surfaceOriginY, surfaceOriginZ;
    private static java.util.List<int[]> surfaceFootprintOutline;
    private static HologramSelectionSurface.PlanarBuilder surfaceFootprintBuilder;
    private static java.util.List<HologramSelectionSurface.PlanarRect> surfaceFootprint = java.util.Collections
        .emptyList();

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
            drawSurface(selection, cursor, px, py, pz);
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
            else drawCorners(selection, points, px, py, pz);
            lastRenderWitness = new RenderWitness(state, cursor);
        } finally {
            GL11.glPopAttrib();
        }
    }

    static boolean showTarget(boolean isometric, boolean control) {
        return !isometric || control;
    }

    private static final class SurfaceBatch {

        final int list, axis;
        final long plane;

        SurfaceBatch(int list, HologramSelectionSurface.Face face) {
            this.list = list;
            axis = face.axis;
            plane = face.plane;
        }
    }

    private static void drawSurface(NBTTagCompound selection, int[] cursor, double px, double py, double pz) {
        long started = System.nanoTime();
        if (selection.getBoolean("areaContinuous") && selection.getBoolean("areaPolygonClosed")) {
            int[] vertices = selection.getIntArray("areaPolygon");
            int axis = selection.getInteger("areaPolygonHeightAxis");
            if (vertices.length >= 9 && axis >= 0 && axis <= 2) {
                updatePolygonOutline(vertices, cursor, true, axis);
                if (outline != surfaceFootprintOutline) {
                    surfaceFootprintOutline = outline;
                    surfaceFootprintBuilder = new HologramSelectionSurface.PlanarBuilder(outline);
                    surfaceFootprint = java.util.Collections.emptyList();
                    discardSurface();
                }
                if (surfaceFootprintBuilder != null) {
                    surfaceFootprintBuilder.advance(Math.max(0, 1_500_000L - (System.nanoTime() - started)));
                    if (!surfaceFootprintBuilder.complete()) return;
                    surfaceFootprint = surfaceFootprintBuilder.rectangles();
                    surfaceFootprintBuilder = null;
                }
            }
        }
        java.util.List<HologramSelectionSurface.Box> boxes = HologramSelectionSurface
            .boxes(selection, cursor, surfaceFootprint);
        if (!boxes.equals(surfaceBoxes)) {
            discardSurface();
            surfaceBoxes = boxes;
            surfaceBuilder = new HologramSelectionSurface.Builder(boxes);
            // An obsolete mesh must not fill a newly subtracted region while its replacement is being built.
            if (!boxes.isEmpty()) {
                surfaceOriginX = boxes.get(0).low[0];
                surfaceOriginY = boxes.get(0).low[1];
                surfaceOriginZ = boxes.get(0).low[2];
            }
        }
        if (surfaceBuilder != null) {
            surfaceBuilder.advance(Math.max(0, 1_500_000L - (System.nanoTime() - started)));
            if (surfaceBuilder.complete()) {
                surfaceFaces = surfaceBuilder.faces();
                surfaceBuilder = null;
            }
        }
        if (surfaceFaces.isEmpty()) return;
        // Upload small chunks under the same frame budget. Steady-state rendering never resubmits every vertex in Java.
        while (surfaceUploaded < surfaceFaces.size() && System.nanoTime() - started < 1_500_000L) {
            HologramSelectionSurface.Face first = surfaceFaces.get(surfaceUploaded);
            int list = GL11.glGenLists(3);
            if (list == 0) return;
            surfaceLists.add(new SurfaceBatch(list, first));
            int end = surfaceUploaded + 1;
            while (end < Math.min(surfaceFaces.size(), surfaceUploaded + 32) && surfaceFaces.get(end).axis == first.axis
                && surfaceFaces.get(end).plane == first.plane) end++;
            GL11.glNewList(list, GL11.GL_COMPILE);
            surfaceTriangles(surfaceUploaded, end, surfaceOriginX, surfaceOriginY, surfaceOriginZ);
            GL11.glEndList();
            for (int normal : new int[] { 1, -1 }) {
                GL11.glNewList(list + (normal > 0 ? 1 : 2), GL11.GL_COMPILE);
                surfaceLines(surfaceUploaded, end, normal, surfaceOriginX, surfaceOriginY, surfaceOriginZ);
                GL11.glEndList();
            }
            surfaceUploaded = end;
        }
        if (surfaceUploaded != surfaceFaces.size()) return;
        double[] view = surfaceView(px, py, pz);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_POLYGON_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(surfaceOriginX - px, surfaceOriginY - py, surfaceOriginZ - pz);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glFrontFace(GL11.GL_CCW);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glColor4f(.35f, .85f, 1, .025f);
            for (SurfaceBatch batch : surfaceLists) GL11.glCallList(batch.list);
            GL11.glLineWidth(1);
            GL11.glColor4f(.35f, .85f, 1, view == null ? .045f : .09f);
            for (SurfaceBatch batch : surfaceLists) {
                // Lines have no GL face culling. Use the actual render matrix, including Core's virtual camera.
                if (view == null) {
                    GL11.glCallList(batch.list + 1);
                    GL11.glCallList(batch.list + 2);
                } else {
                    double facing = view[6] == 0 ? view[batch.axis + 3] : view[batch.axis] - batch.plane;
                    if (Math.abs(facing) > 1e-8) GL11.glCallList(batch.list + (facing > 0 ? 1 : 2));
                }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            GL11.glPopAttrib();
        }
    }

    private static void discardSurface() {
        surfaceBoxes = null;
        surfaceBuilder = null;
        surfaceFaces = java.util.Collections.emptyList();
        for (SurfaceBatch batch : surfaceLists) GL11.glDeleteLists(batch.list, 3);
        surfaceLists.clear();
        surfaceUploaded = 0;
    }

    private static void surfaceTriangles(int start, int end, double px, double py, double pz) {
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int index = start; index < end; index++) {
            HologramSelectionSurface.Face face = surfaceFaces.get(index);
            surfaceVertex(face, face.u0, face.v0, px, py, pz);
            surfaceVertex(face, face.normal > 0 ? face.u1 : face.u0, face.normal > 0 ? face.v0 : face.v1, px, py, pz);
            surfaceVertex(face, face.u1, face.v1, px, py, pz);
            surfaceVertex(face, face.u0, face.v0, px, py, pz);
            surfaceVertex(face, face.u1, face.v1, px, py, pz);
            surfaceVertex(face, face.normal > 0 ? face.u0 : face.u1, face.normal > 0 ? face.v1 : face.v0, px, py, pz);
        }
        GL11.glEnd();
    }

    private static void surfaceLines(int start, int end, int normal, double px, double py, double pz) {
        GL11.glBegin(GL11.GL_LINES);
        for (int index = start; index < end; index++) {
            HologramSelectionSurface.Face face = surfaceFaces.get(index);
            if (face.normal != normal) continue;
            for (double[] line : HologramSelectionSurface.hatch(face)) {
                surfaceVertex(face, line[0], line[1], px, py, pz);
                surfaceVertex(face, line[2], line[3], px, py, pz);
            }
        }
        GL11.glEnd();
    }

    private static void surfaceVertex(HologramSelectionSurface.Face face, double u, double v, double px, double py,
        double pz) {
        double plane = face.plane + face.normal * .001;
        GL11.glVertex3d(
            (face.axis == 0 ? plane : face.axis == 1 ? v : u) - px,
            (face.axis == 1 ? plane : face.axis == 2 ? v : u) - py,
            (face.axis == 2 ? plane : face.axis == 0 ? v : u) - pz);
    }

    /** Invert the current affine view, rather than assuming player position or an unrotated isometric camera. */
    private static double[] surfaceView(double px, double py, double pz) {
        SURFACE_MODELVIEW.clear();
        SURFACE_PROJECTION.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, SURFACE_MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, SURFACE_PROJECTION);
        double a = SURFACE_MODELVIEW.get(0), b = SURFACE_MODELVIEW.get(4), c = SURFACE_MODELVIEW.get(8);
        double d = SURFACE_MODELVIEW.get(1), e = SURFACE_MODELVIEW.get(5), f = SURFACE_MODELVIEW.get(9);
        double g = SURFACE_MODELVIEW.get(2), h = SURFACE_MODELVIEW.get(6), i = SURFACE_MODELVIEW.get(10);
        double determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (!Double.isFinite(determinant) || Math.abs(determinant) < 1e-12) return null;
        double x = SURFACE_MODELVIEW.get(12), y = SURFACE_MODELVIEW.get(13), z = SURFACE_MODELVIEW.get(14);
        double[] view = { px - ((e * i - f * h) * x + (c * h - b * i) * y + (b * f - c * e) * z) / determinant,
            py - ((f * g - d * i) * x + (a * i - c * g) * y + (c * d - a * f) * z) / determinant,
            pz - ((d * h - e * g) * x + (b * g - a * h) * y + (a * e - b * d) * z) / determinant,
            (b * f - c * e) / determinant, (c * d - a * f) / determinant, (a * e - b * d) / determinant,
            Math.abs(SURFACE_PROJECTION.get(15)) > .5 ? 0 : 1 };
        for (double value : view) if (!Double.isFinite(value)) return null;
        return view;
    }

    private static final class CornerLabel {

        final String text;
        final double viewX, viewY, viewZ, screenX, screenY, worldScale;
        final int width, height;
        double left, top;

        CornerLabel(String text, double viewX, double viewY, double viewZ, double screenX, double screenY,
            double worldScale, int width, int height) {
            this.text = text;
            this.viewX = viewX;
            this.viewY = viewY;
            this.viewZ = viewZ;
            this.screenX = screenX;
            this.screenY = screenY;
            this.worldScale = worldScale;
            this.width = width;
            this.height = height;
        }
    }

    /** Only the snapshot's editable corners are marked; history and the next-box gesture need no extra state. */
    private static void drawCorners(NBTTagCompound selection, int[] points, double px, double py, double pz) {
        if (points.length != 3 && points.length != 6) return;
        if (points.length == 6 && selection.hasKey("areaInputMode", 8)
            && !"replace".equals(selection.getString("areaInputMode"))) return;
        int pending = selection.getInteger("areaPendingCorner");
        if (points.length == 3 && pending != 1 && pending != 2) return;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int width = resolution.getScaledWidth(), height = resolution.getScaledHeight();
        CORNER_MODELVIEW.clear();
        CORNER_PROJECTION.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, CORNER_MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, CORNER_PROJECTION);
        CornerLabel[] labels = new CornerLabel[2];
        for (int i = 0; i < points.length; i += 3) {
            int button = points.length == 3 ? pending - 1 : i / 3;
            GL11.glLineWidth(6);
            GL11.glColor4f(.75f, .2f, 1, 1);
            draw(points[i], points[i + 1], points[i + 2], points[i], points[i + 1], points[i + 2], px, py, pz);
            labels[button] = cornerLabel(
                font,
                button,
                points[i] + .5 - px,
                points[i + 1] + .5 - py,
                points[i + 2] + .5 - pz,
                width,
                height);
        }
        HologramWorldGuide.RenderWitness guide = HologramWorldGuide.lastRenderWitness();
        int topInset = guide == null ? 8 : guide.top + guide.height + 8;
        for (int i = 0; i < labels.length; i++) {
            CornerLabel label = labels[i];
            if (label == null || label.width > width - 8 || label.height > height - topInset - 4) {
                labels[i] = null;
                continue;
            }
            label.left = Math.max(4, Math.min(width - label.width - 4, label.screenX - label.width / 2.0));
            label.top = Math
                .max(topInset, Math.min(height - label.height - 4, label.screenY + (i == 0 ? -label.height - 10 : 10)));
        }
        CornerLabel left = labels[0], right = labels[1];
        if (left != null && right != null
            && left.left < right.left + right.width
            && right.left < left.left + left.width
            && left.top < right.top + right.height
            && right.top < left.top + left.height) {
            // Coincident blocks and nearby projected corners retain two distinct button labels.
            if (left.top + left.height + 6 + right.height <= height - 4) right.top = left.top + left.height + 6;
            else if (left.top - right.height - 6 >= topInset) right.top = left.top - right.height - 6;
            else if (left.left + left.width + 6 + right.width <= width - 4) right.left = left.left + left.width + 6;
            else right.left = Math.max(4, left.left - right.width - 6);
        }
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            for (CornerLabel label : labels) if (label != null) drawCornerLabel(font, label);
        } finally {
            GL11.glMatrixMode(matrixMode);
        }
    }

    private static CornerLabel cornerLabel(FontRenderer font, int button, double x, double y, double z, int width,
        int height) {
        double vx = CORNER_MODELVIEW.get(0) * x + CORNER_MODELVIEW.get(4) * y
            + CORNER_MODELVIEW.get(8) * z
            + CORNER_MODELVIEW.get(12);
        double vy = CORNER_MODELVIEW.get(1) * x + CORNER_MODELVIEW.get(5) * y
            + CORNER_MODELVIEW.get(9) * z
            + CORNER_MODELVIEW.get(13);
        double vz = CORNER_MODELVIEW.get(2) * x + CORNER_MODELVIEW.get(6) * y
            + CORNER_MODELVIEW.get(10) * z
            + CORNER_MODELVIEW.get(14);
        double cx = CORNER_PROJECTION.get(0) * vx + CORNER_PROJECTION.get(4) * vy
            + CORNER_PROJECTION.get(8) * vz
            + CORNER_PROJECTION.get(12);
        double cy = CORNER_PROJECTION.get(1) * vx + CORNER_PROJECTION.get(5) * vy
            + CORNER_PROJECTION.get(9) * vz
            + CORNER_PROJECTION.get(13);
        double cz = CORNER_PROJECTION.get(2) * vx + CORNER_PROJECTION.get(6) * vy
            + CORNER_PROJECTION.get(10) * vz
            + CORNER_PROJECTION.get(14);
        double cw = CORNER_PROJECTION.get(3) * vx + CORNER_PROJECTION.get(7) * vy
            + CORNER_PROJECTION.get(11) * vz
            + CORNER_PROJECTION.get(15);
        if (!(cw > 0) || !Double.isFinite(cw) || Math.abs(cx) > cw || Math.abs(cy) > cw || Math.abs(cz) > cw)
            return null;
        double pixelsPerUnit = Math.abs(CORNER_PROJECTION.get(0)) * width / (2 * cw);
        if (!(pixelsPerUnit > 0) || !Double.isFinite(pixelsPerUnit)) return null;
        String text = "\u00a7l" + HologramClient.text(
            button == 0 ? "selectionCorner.left" : "selectionCorner.right",
            button == 0 ? "Left click" : "Right click");
        return new CornerLabel(
            text,
            vx,
            vy,
            vz,
            (cx / cw + 1) * width / 2,
            (1 - cy / cw) * height / 2,
            CORNER_TEXT_SCALE / pixelsPerUnit,
            (int) Math.ceil((font.getStringWidth(text) + 8) * CORNER_TEXT_SCALE),
            (int) Math.ceil((font.FONT_HEIGHT + 6) * CORNER_TEXT_SCALE));
    }

    private static void drawCornerLabel(FontRenderer font, CornerLabel label) {
        CORNER_BILLBOARD.clear();
        for (int i = 0; i < 16; i++) CORNER_BILLBOARD.put(i, 0);
        CORNER_BILLBOARD.put(0, (float) label.worldScale);
        CORNER_BILLBOARD.put(5, (float) -label.worldScale);
        CORNER_BILLBOARD.put(10, (float) label.worldScale);
        CORNER_BILLBOARD.put(12, (float) label.viewX);
        CORNER_BILLBOARD.put(13, (float) label.viewY);
        CORNER_BILLBOARD.put(14, (float) label.viewZ);
        CORNER_BILLBOARD.put(15, 1);
        GL11.glPushMatrix();
        try {
            // Read the actual WorldLast transform, including Core's current virtual camera, instead of player angles.
            GL11.glLoadMatrix(CORNER_BILLBOARD);
            int x = (int) Math.round((label.left - label.screenX) / CORNER_TEXT_SCALE);
            int y = (int) Math.round((label.top - label.screenY) / CORNER_TEXT_SCALE);
            int width = (int) Math.ceil(label.width / CORNER_TEXT_SCALE);
            Gui.drawRect(x, y, x + width, y + font.FONT_HEIGHT + 6, 0xCC160C22);
            font.drawStringWithShadow(label.text, x + 4, y + 3, 0xE0B0FF);
        } finally {
            GL11.glPopMatrix();
        }
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
        updatePolygonOutline(vertices, cursor, closed, axis);
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

    private static void updatePolygonOutline(int[] vertices, int[] cursor, boolean closed, int axis) {
        int[] planarCursor = closed ? null : cursor;
        if (!java.util.Arrays.equals(vertices, outlineVertices) || !java.util.Arrays.equals(planarCursor, outlineCursor)
            || closed != outlineClosed
            || axis != outlineAxis) {
            outlineVertices = vertices.clone();
            outlineCursor = planarCursor == null ? null : planarCursor.clone();
            outlineClosed = closed;
            outlineAxis = axis;
            outline = HologramSelectionOutline.polygon(vertices, planarCursor, closed, axis);
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
