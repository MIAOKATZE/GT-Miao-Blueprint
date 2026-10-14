package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Cached static schematic geometry; rendering never ticks machines or reads live world cells. */
final class HologramRenderer {

    private static final int[][] FACES = { { 0, 1, 5, 4 }, { 3, 7, 6, 2 }, { 0, 3, 2, 1 }, { 4, 5, 6, 7 },
        { 0, 4, 7, 3 }, { 1, 2, 6, 5 } };
    private static final int[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };
    private static final int[][] EDGES = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 },
        { 7, 4 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };
    private static final double[][] VERTICES = { { -.5, -.5, -.5 }, { .5, -.5, -.5 }, { .5, .5, -.5 }, { -.5, .5, -.5 },
        { -.5, -.5, .5 }, { .5, -.5, .5 }, { .5, .5, .5 }, { -.5, .5, .5 } };

    private HologramRenderer() {}

    static int lastWorldGeometry, lastWorldGTGeometry, lastWorldFallback, lastWorldMatched, lastWorldGhosts;
    static int lastWorldPlacementOutlineColor;
    private static WorldRenderWitness worldRenderWitness;
    private static long worldRenderGeneration;
    static int lastPreviewRoleMarkers, lastPreviewAnchorMarkers;
    private static HologramState previewState, worldState;
    private static String previewKey = "";
    private static List<Face> cachedFaces = new ArrayList<>();
    private static List<Face> cachedOutlines = new ArrayList<>();
    private static final List<HologramState.Cell> nativePreviewCells = new ArrayList<>();
    private static HologramState visibleState;
    private static int visibleLayer, visibleView, visibleMode;
    private static final List<HologramState.Cell> visibleCells = new ArrayList<>();
    private static final List<PreviewCell> previewCells = new ArrayList<>();
    private static final Set<Coordinate> occluders = new HashSet<>();
    private static List<RoleMarker> cachedMarkers = new ArrayList<>();
    private static double cachedExtent = 1;
    private static int worldLayer = Integer.MAX_VALUE;
    private static final long WORLD_BUILD_NANOS = 3_000_000L;
    private static final int WORLD_INDEX_BATCH = 2048, WORLD_MESH_BATCH = 256;
    private static final Map<Coordinate, WorldGroup> worldGroups = new LinkedHashMap<>();
    private static final Set<Coordinate> worldGhosts = new HashSet<>();
    private static final Set<Coordinate> worldOccluders = new HashSet<>();
    private static int worldIndex;
    private static HologramState boundsState;
    private static double boundsX, boundsY, boundsZ, boundsExtent;
    static int projectionLayer = Integer.MAX_VALUE;
    static boolean showEmptyCells;
    static HologramSelection selection;
    static String selectedStructureId = "";
    private static int projectedX, projectedY, projectedWidth, projectedHeight;
    private static double projectedYaw, projectedPitch, projectedScale;
    private static double projectedPanX, projectedPanZ;
    private static final Map<String, double[][]> structureBoxes = new LinkedHashMap<>();
    private static final Set<String> missingStructures = new HashSet<>();
    private static final Map<String, double[][]> hintBoxes = new LinkedHashMap<>();
    private static HologramState structureBoundsState;
    private static Map<String, int[]> cachedStructureBounds = new LinkedHashMap<>();
    private static boolean visibleEmptyCells;

    static void release() {
        worldRenderWitness = null;
        clearWorldGroups();
        worldState = previewState = null;
        boundsState = null;
        HologramTextures.clear();
        HologramLinkNodePreview.clear();
        HologramBlockRender.clear();
        nativePreviewCells.clear();
        cachedFaces.clear();
        cachedMarkers.clear();
        cachedOutlines.clear();
        visibleState = null;
        visibleCells.clear();
        previewCells.clear();
        occluders.clear();
        structureBoxes.clear();
        hintBoxes.clear();
        structureBoundsState = null;
        cachedStructureBounds.clear();
    }

    static int preview(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
        int layer, int view, int selected, int mouseX, int mouseY, double rehearsal) {
        return preview(
            state,
            x,
            y,
            width,
            height,
            yaw,
            pitch,
            zoom,
            0,
            0,
            layer,
            view,
            selected,
            mouseX,
            mouseY,
            rehearsal);
    }

    static double previewExtent() {
        return boundsExtent;
    }

    static int preview(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
        double panX, double panZ, int layer, int view, int selected, int mouseX, int mouseY, double rehearsal) {
        HologramTextures.begin(state);
        HologramBlockRender.begin(state);
        projectedX = x;
        projectedY = y;
        projectedWidth = width;
        projectedHeight = height;
        projectedYaw = yaw;
        projectedPitch = pitch;
        projectedPanX = panX;
        projectedPanZ = panZ;
        String key = x + ":"
            + y
            + ":"
            + width
            + ":"
            + height
            + ":"
            + yaw
            + ":"
            + pitch
            + ":"
            + zoom
            + ":"
            + panX
            + ":"
            + panZ
            + ":"
            + layer
            + ":"
            + view
            + ":"
            + showEmptyCells;
        if (previewState != state || !key.equals(previewKey)) {
            List<Face> faces = new ArrayList<>();
            List<RoleMarker> markers = new ArrayList<>();
            List<Face> outlines = new ArrayList<>();
            nativePreviewCells.clear();
            double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
            double sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
            int mode = state.data.getInteger("mode");
            boolean currentView = view == 1 || mode == 2;
            if (visibleState != state || visibleLayer != layer
                || visibleView != view
                || visibleMode != mode
                || visibleEmptyCells != showEmptyCells) {
                visibleState = state;
                visibleLayer = layer;
                visibleView = view;
                visibleMode = mode;
                visibleEmptyCells = showEmptyCells;
                visibleCells.clear();
                occluders.clear();
                for (HologramState.Cell c : state.cells) {
                    if (c.status.equals("removed") || (view == 2 && (c.completed() || c.status.equals("satisfied"))))
                        continue;
                    boolean empty = !c.anchor && !c.iconOnly && (currentView ? c.id : c.wantId).equals("minecraft:air");
                    if (empty && !showEmptyCells) continue;
                    if (!c.anchor && c.dy != layer && layer != Integer.MAX_VALUE) continue;
                    visibleCells.add(c);
                    if (!empty && occludes(c, currentView)) occluders.add(coordinate(c.x, c.y, c.z));
                }
                previewCells.clear();
                for (HologramState.Cell c : visibleCells) {
                    int exposed = 0;
                    for (int side = 0; side < 6; side++) {
                        int[] normal = NORMALS[side];
                        if (!occluders.contains(coordinate(c.x + normal[0], c.y + normal[1], c.z + normal[2])))
                            exposed |= 1 << side;
                    }
                    boolean empty = !c.anchor && !c.iconOnly && (currentView ? c.id : c.wantId).equals("minecraft:air");
                    String role = roleLabel(c);
                    boolean nativeModel = !empty && HologramBlockRender.ready(c, currentView);
                    if (nativeModel || !empty && HologramTextures.material(c, currentView).partial) exposed = 63;
                    if (c.missingBlock) role = "缺失方块";
                    else if (c.sourceMissing && c.anchor) role = "来源结构缺失";
                    if (!nativeModel && !empty && role.isEmpty() && HologramTextures.material(c, currentView).fallback)
                        role = "\u6750\u8d28\u7f3a\u5931";
                    if (!nativeModel && !empty && HologramNativeTiles.supported(c.block(currentView || c.anchor)))
                        role = "\u6a21\u578b\u7f3a\u5931";
                    if (!empty && role.isEmpty() && c.iconOnly) role = "\u5360\u4f4d";
                    if (!c.prompt.isEmpty()) role = role.isEmpty() ? "提示" : role + " · 提示";
                    if (exposed != 0 || !role.isEmpty()) previewCells.add(new PreviewCell(c, exposed, role));
                }
            }
            int facing = 0;
            for (int side = 0; side < 6; side++) {
                int[] normal = NORMALS[side];
                if (normal[1] * sinPitch + (normal[0] * sinYaw + normal[2] * cosYaw) * cosPitch > 1e-8)
                    facing |= 1 << side;
            }
            lastPreviewRoleMarkers = lastPreviewAnchorMarkers = 0;
            if (boundsState != state) {
                boundsState = state;
                boundsX = boundsY = boundsZ = 0;
                boundsExtent = 1;
                if (!state.cells.isEmpty()) {
                    double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = minX, maxZ = maxX;
                    for (HologramState.Cell c : state.cells) {
                        minX = Math.min(minX, c.dx);
                        maxX = Math.max(maxX, c.dx);
                        minZ = Math.min(minZ, c.dz);
                        maxZ = Math.max(maxZ, c.dz);
                    }
                    boundsX = (minX + maxX) / 2;
                    boundsY = (state.minY + (double) state.maxY) / 2.0;
                    boundsZ = (minZ + maxZ) / 2;
                    boundsExtent = Math
                        .max(maxX - minX + 2, Math.max(maxZ - minZ + 2, (double) state.maxY - state.minY + 2));
                }
            }
            double cx = boundsX + panX, cy = boundsY, cz = boundsZ + panZ, extent = boundsExtent;
            double scale = Math.min(width, height) * .72 / extent * zoom;
            projectedScale = scale;
            buildStructureBoxes(state);
            for (PreviewCell previewCell : previewCells) {
                HologramState.Cell c = previewCell.cell;
                int exposed = previewCell.exposed & facing;
                String role = previewCell.role;
                if (exposed == 0 && role.isEmpty()) continue;
                double[][] projected = new double[8][];
                for (int v = 0; v < 8; v++) {
                    double px = c.dx - cx + VERTICES[v][0], pz = c.dz - cz + VERTICES[v][2];
                    double py = c.dy - cy + VERTICES[v][1];
                    double rx = px * cosYaw - pz * sinYaw;
                    double rz = px * sinYaw + pz * cosYaw;
                    double ry = py * cosPitch - rz * sinPitch;
                    double depth = py * sinPitch + rz * cosPitch;
                    projected[v] = new double[] { x + width / 2.0 + rx * scale, y + height / 2.0 - ry * scale, depth };
                }
                if (!HologramBlueprintPreview.cleanCapture() && !role.isEmpty() && (c.anchor || markers.size() < 64))
                    markers.add(new RoleMarker(c, projected, role));
                outlines.add(new Face(c, projected, 0, currentView));
                if (HologramBlockRender.ready(c, currentView)) {
                    nativePreviewCells.add(c);
                    // Cell picking keeps the established voxel contract while the drawing uses
                    // the mod's actual triangles, materials, covers and part geometry.
                    for (int side = 0; side < 6; side++)
                        if ((exposed & 1 << side) != 0) faces.add(new Face(c, projected, side, currentView));
                    continue;
                }
                HologramTextures.Material material = HologramTextures.material(c, currentView);
                for (HologramGeometry.Surface surface : material.geometry) {
                    if ((exposed & (1 << surface.side)) == 0) continue;
                    double[][] points = projected;
                    if (material.partial) {
                        points = new double[8][3];
                        for (int vertex = 0; vertex < 8; vertex++) {
                            double[] local = surface.vertices[vertex];
                            for (int axis = 0; axis < 3; axis++) points[vertex][axis] = projected[0][axis]
                                + (local[0] + .5) * (projected[1][axis] - projected[0][axis])
                                + (local[1] + .5) * (projected[3][axis] - projected[0][axis])
                                + (local[2] + .5) * (projected[4][axis] - projected[0][axis]);
                        }
                    }
                    faces.add(new Face(c, points, surface.side, currentView, surface));
                }
            }
            faces.sort(Comparator.comparingDouble(f -> f.depth));
            markers.sort(Comparator.comparingInt(marker -> marker.cell.anchor ? 1 : 0));
            previewState = state;
            previewKey = key;
            cachedFaces = faces;
            cachedMarkers = markers;
            cachedOutlines = outlines;
            cachedExtent = extent;
        }
        List<Face> faces = cachedFaces;
        List<RoleMarker> markers = cachedMarkers;
        double extent = cachedExtent;
        lastPreviewRoleMarkers = lastPreviewAnchorMarkers = 0;
        int hovered = -1;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            // GUI ortho spans +/-1000 around z=0. Normalize model depth independently of zoom,
            // and clear only this viewport so schematic faces and outlines share reliable occlusion.
            Minecraft mc = Minecraft.getMinecraft();
            int factor = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight)
                .getScaleFactor();
            // HologramScreen supplies a framebuffer scissor after its origin/scale transform.
            // x/y here are model-local coordinates; replacing that rectangle clips the model
            // against the wrong part of the screen. Retain the caller's transformed viewport.
            if (!GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(x * factor, mc.displayHeight - (y + height) * factor, width * factor, height * factor);
            }
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, .01f);
            double depthScale = 128 / extent;
            HologramSourcePreviewReference.captureCamera(depthScale);
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            for (Face face : faces) {
                boolean inside = face.contains(mouseX, mouseY) && mouseX >= x
                    && mouseX < x + width
                    && mouseY >= y
                    && mouseY < y + height;
                if (inside) hovered = face.cell.index;
                if (face.empty || face.nativeModel) continue;
                float shade = face.side == 1 ? 1 : face.side < 2 ? .6f : .8f;
                int sampleIndex = 0;
                for (HologramTextures.Sample sample : face.samples()) {
                    setSampleColor(t, sample, shade, .96f);
                    if (face.cell.sourceMissing && !face.cell.missingBlock) t.setColorRGBA_F(.55f, .55f, .55f, .78f);
                    for (int i = 0; i < 4; i++) {
                        int vertex = FACES[face.side][i];
                        double[] point = face.points[vertex];
                        t.addVertexWithUV(
                            point[0],
                            point[1],
                            point[2] * depthScale + sampleIndex * .015,
                            textureU(sample.icon, face.side, face.local(vertex)),
                            textureV(sample.icon, face.side, face.local(vertex)));
                    }
                    sampleIndex++;
                }
            }
            t.draw();
            HologramNativeRasterSmoke.Frame raster = HologramNativeRasterSmoke
                .begin(state, view == 1 || state.data.getInteger("mode") == 2);
            for (HologramState.Cell cell : nativePreviewCells) {
                HologramNativeRasterSmoke.Slice pixels = null;
                if (raster != null) for (Face outline : cachedOutlines) if (outline.cell == cell) {
                    pixels = raster.before(outline.points, depthScale);
                    break;
                }
                GL11.glPushMatrix();
                GL11.glTranslated(x + width / 2.0, y + height / 2.0, 0);
                GL11.glScaled(projectedScale, -projectedScale, depthScale);
                GL11.glRotated(Math.toDegrees(pitch), 1, 0, 0);
                GL11.glRotated(-Math.toDegrees(yaw), 0, 1, 0);
                GL11.glTranslated(cell.dx - boundsX - panX - .5, cell.dy - boundsY - .5, cell.dz - boundsZ - panZ - .5);
                HologramBlockRender.draw(cell, view == 1 || state.data.getInteger("mode") == 2);
                GL11.glPopMatrix();
                if (pixels != null) raster.after(cell, pixels);
            }
            HologramNativeRasterSmoke.finish(raster);
            for (HologramState.Cell cell : visibleCells) {
                boolean current = view == 1 || state.data.getInteger("mode") == 2;
                if (HologramLinkNodePreview.prepare(cell, current)
                    .isEmpty()) continue;
                GL11.glPushMatrix();
                GL11.glTranslated(x + width / 2.0, y + height / 2.0, 0);
                GL11.glScaled(projectedScale, -projectedScale, depthScale);
                GL11.glRotated(Math.toDegrees(pitch), 1, 0, 0);
                GL11.glRotated(-Math.toDegrees(yaw), 0, 1, 0);
                GL11.glTranslated(cell.dx - boundsX - panX - .5, cell.dy - boundsY - .5, cell.dz - boundsZ - panZ - .5);
                HologramLinkNodePreview.draw(cell, current, .96f);
                GL11.glPopMatrix();
            }
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            // Read face depth without writing line depth. The small z bias avoids coplanar
            // flicker while keeping back edges hidden; all cells retain clear wireframes.
            GL11.glDepthMask(false);
            t.startDrawing(GL11.GL_LINES);
            if (!HologramBlueprintPreview.cleanCapture()) for (Face face : cachedOutlines) {
                boolean chosen = face.cell.index == selected
                    || selection != null && selection.contains(face.cell.index);
                t.setColorRGBA_F(
                    chosen ? 1 : .2f,
                    chosen ? .92f : .7f,
                    chosen ? .5f : .8f,
                    chosen ? 1 : face.empty ? .18f : .45f);
                for (int[] edge : EDGES) {
                    double[] a = face.points[edge[0]], b = face.points[edge[1]];
                    t.addVertex(a[0], a[1], a[2] * depthScale + .15);
                    t.addVertex(b[0], b[1], b[2] * depthScale + .15);
                }
            }
            t.draw();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glLineWidth(2.5f);
            t.startDrawing(GL11.GL_LINES);
            String hoveredStructure = pickStructure(mouseX, mouseY);
            if (!HologramBlueprintPreview.cleanCapture())
                for (Map.Entry<String, double[][]> entry : structureBoxes.entrySet()) {
                    if (entry.getKey()
                        .equals(hoveredStructure)) t.setColorRGBA_F(1, 1, .8f, 1);
                    else if (entry.getKey()
                        .equals(selectedStructureId)) t.setColorRGBA_F(1, .78f, .3f, .95f);
                    else if (missingStructures.contains(entry.getKey())) t.setColorRGBA_F(.65f, .65f, .65f, .72f);
                    else t.setColorRGBA_F(.45f, .85f, 1, .72f);
                    for (int[] edge : EDGES) {
                        double[] a = entry.getValue()[edge[0]], b = entry.getValue()[edge[1]];
                        t.addVertex(a[0], a[1], 1);
                        t.addVertex(b[0], b[1], 1);
                    }
                }
            t.draw();
            GL11.glLineWidth(1.5f);
            t.startDrawing(GL11.GL_LINES);
            t.setColorRGBA_F(.95f, .77f, .39f, .48f);
            if (!HologramBlueprintPreview.cleanCapture())
                for (double[][] box : hintBoxes.values()) for (int[] edge : EDGES) {
                    double[] a = box[edge[0]], b = box[edge[1]];
                    t.addVertex(a[0], a[1], 1);
                    t.addVertex(b[0], b[1], 1);
                }
            t.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);
            if (!HologramBlueprintPreview.cleanCapture()) for (RoleMarker marker : markers) {
                if (!marker.cell.anchor
                    && (marker.x < x || marker.x >= x + width || marker.y < y || marker.y >= y + height)) continue;
                float labelScale = marker.cell.anchor || marker.cell.missingBlock ? 1f : .7f;
                int labelWidth = Math.round(mc.fontRenderer.getStringWidth(marker.label) * labelScale);
                int lx = Math.max(x + 2, Math.min(x + width - labelWidth - 4, (int) marker.x - labelWidth / 2));
                int ly = Math.max(y + 2, Math.min(y + height - 12, (int) marker.y - 4));
                net.minecraft.client.gui.Gui.drawRect(
                    lx - 2,
                    ly - 1,
                    lx + labelWidth + 2,
                    ly + Math.round(9 * labelScale) + 1,
                    marker.cell.anchor ? 0xee514025 : 0xda102a34);
                GL11.glPushMatrix();
                try {
                    GL11.glTranslatef(lx, ly, 0);
                    GL11.glScalef(labelScale, labelScale, 1);
                    mc.fontRenderer.drawStringWithShadow(
                        marker.label,
                        0,
                        0,
                        marker.cell.sourceMissing ? 0xff7272
                            : marker.cell.missingBlock ? 0xff6be4 : marker.cell.anchor ? 0xffffbd : 0x9ceaf3);
                } finally {
                    GL11.glPopMatrix();
                }
                marker.left = lx - 2;
                marker.top = ly - 1;
                marker.right = lx + labelWidth + 2;
                marker.bottom = ly + Math.round(9 * labelScale) + 1;
                if (mouseX >= lx - 2 && mouseX < lx + labelWidth + 2
                    && mouseY >= ly - 1
                    && mouseY < ly + Math.round(9 * labelScale) + 1) hovered = marker.cell.index;
                lastPreviewRoleMarkers++;
                if (marker.cell.anchor) lastPreviewAnchorMarkers++;
            }
        } finally {
            // Depth is framebuffer data, not an attribute: do not leave schematic depth in later GUI overlays.
            GL11.glDepthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glPopAttrib();
        }
        return hovered;
    }

    private static void drawHintLabels(Minecraft mc, int x, int y, int width, int height) {
        int shown = 0;
        for (Map.Entry<String, double[][]> entry : hintBoxes.entrySet()) {
            double minX = Double.MAX_VALUE, minY = minX, maxX = -minX, maxY = -minX;
            for (double[] point : entry.getValue()) {
                minX = Math.min(minX, point[0]);
                minY = Math.min(minY, point[1]);
                maxX = Math.max(maxX, point[0]);
                maxY = Math.max(maxY, point[1]);
            }
            if (maxX < x || minX >= x + width || maxY < y || minY >= y + height) continue;
            String label = mc.fontRenderer.trimStringToWidth(
                "提示区 " + entry.getKey()
                    .replace('\n', ' '),
                Math.min(160, width - 12));
            int labelWidth = mc.fontRenderer.getStringWidth(label);
            int lx = Math.max(x + 4, Math.min(x + width - labelWidth - 4, (int) minX));
            int ly = Math.max(y + 18, Math.min(y + height - 13, (int) minY - 12));
            net.minecraft.client.gui.Gui.drawRect(lx - 2, ly - 2, lx + labelWidth + 2, ly + 10, 0xd9362c1a);
            mc.fontRenderer.drawStringWithShadow(label, lx, ly, 0xffdc81);
            if (++shown == 8) break;
        }
    }

    static double[] project(double dx, double dy, double dz) {
        double px = dx - boundsX - projectedPanX, py = dy - boundsY, pz = dz - boundsZ - projectedPanZ;
        double rx = px * Math.cos(projectedYaw) - pz * Math.sin(projectedYaw);
        double rz = px * Math.sin(projectedYaw) + pz * Math.cos(projectedYaw);
        double ry = py * Math.cos(projectedPitch) - rz * Math.sin(projectedPitch);
        return new double[] { projectedX + projectedWidth / 2.0 + rx * projectedScale,
            projectedY + projectedHeight / 2.0 - ry * projectedScale };
    }

    /** Intersect the orthographic view ray with a horizontal plane at an absolute world Y. */
    static int[] pickEmptyVoxel(HologramState state, int mx, int my, int planeY) {
        if (state == null || state.cells.isEmpty()
            || projectedScale <= 0
            || mx < projectedX
            || mx >= projectedX + projectedWidth
            || my < projectedY
            || my >= projectedY + projectedHeight
            || Math.abs(Math.sin(projectedPitch)) < .05) return null;
        double rx = (mx - projectedX - projectedWidth / 2.0) / projectedScale;
        double ry = -(my - projectedY - projectedHeight / 2.0) / projectedScale;
        double py = planeY - state.data.getInteger("y") - boundsY;
        double rz = (py * Math.cos(projectedPitch) - ry) / Math.sin(projectedPitch);
        int x = state.data.getInteger("x") + (int) Math
            .floor(rx * Math.cos(projectedYaw) + rz * Math.sin(projectedYaw) + boundsX + projectedPanX + .5);
        int z = state.data.getInteger("z") + (int) Math
            .floor(-rx * Math.sin(projectedYaw) + rz * Math.cos(projectedYaw) + boundsZ + projectedPanZ + .5);
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
        int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        for (HologramState.Cell cell : state.cells) {
            minX = Math.min(minX, cell.x);
            maxX = Math.max(maxX, cell.x);
            minY = Math.min(minY, cell.y);
            maxY = Math.max(maxY, cell.y);
            minZ = Math.min(minZ, cell.z);
            maxZ = Math.max(maxZ, cell.z);
            if (cell.x == x && cell.y == planeY && cell.z == z && !cell.wantId.equals("minecraft:air")) return null;
        }
        return x < minX - 1 || x > maxX + 1 || z < minZ - 1 || z > maxZ + 1 || planeY < minY - 1 || planeY > maxY + 1
            ? null
            : new int[] { x, planeY, z };
    }

    private static void buildStructureBoxes(HologramState state) {
        structureBoxes.clear();
        // Snapshot geometry is immutable; rotating the camera only projects its cached bounds.
        if (structureBoundsState != state) {
            structureBoundsState = state;
            cachedStructureBounds = structureBounds(state);
            missingStructures.clear();
            for (HologramState.Cell cell : state.cells)
                if (cell.sourceMissing) missingStructures.addAll(cell.structureIds);
        }
        for (Map.Entry<String, int[]> entry : cachedStructureBounds.entrySet()) {
            int[] b = entry.getValue();
            double[][] box = new double[8][];
            for (int i = 0; i < 8; i++) box[i] = project(
                VERTICES[i][0] < 0 ? b[0] - .55 : b[3] + .55,
                VERTICES[i][1] < 0 ? b[1] - .55 : b[4] + .55,
                VERTICES[i][2] < 0 ? b[2] - .55 : b[5] + .55);
            structureBoxes.put(entry.getKey(), box);
        }
        hintBoxes.clear();
        net.minecraft.nbt.NBTTagList hints = state.data.getTagList("hintAreas", 10);
        for (int i = 0; i < hints.tagCount(); i++) {
            net.minecraft.nbt.NBTTagCompound hint = hints.getCompoundTagAt(i);
            int[] b = hint.getIntArray("bounds");
            if (b.length != 6) continue;
            double[][] box = new double[8][];
            for (int v = 0; v < 8; v++) box[v] = project(
                VERTICES[v][0] < 0 ? Math.min(b[0], b[3]) - .6 : Math.max(b[0], b[3]) + .6,
                VERTICES[v][1] < 0 ? Math.min(b[1], b[4]) - .6 : Math.max(b[1], b[4]) + .6,
                VERTICES[v][2] < 0 ? Math.min(b[2], b[5]) - .6 : Math.max(b[2], b[5]) + .6);
            hintBoxes.put((i + 1) + " · " + hint.getString("prompt"), box);
        }
    }

    /** Pure shared-wall bounds calculation; one cell can extend several structures without duplicate mesh cells. */
    static Map<String, int[]> structureBounds(HologramState state) {
        Map<String, int[]> bounds = new LinkedHashMap<>();
        for (HologramState.Cell cell : state.cells) for (String structureId : cell.structureIds) {
            int[] b = bounds.get(structureId);
            if (b == null) {
                b = new int[] { cell.dx, cell.dy, cell.dz, cell.dx, cell.dy, cell.dz };
                bounds.put(structureId, b);
            }
            b[0] = Math.min(b[0], cell.dx);
            b[1] = Math.min(b[1], cell.dy);
            b[2] = Math.min(b[2], cell.dz);
            b[3] = Math.max(b[3], cell.dx);
            b[4] = Math.max(b[4], cell.dy);
            b[5] = Math.max(b[5], cell.dz);
        }
        return bounds;
    }

    static String hintAt(int mx, int my) {
        for (Map.Entry<String, double[][]> entry : hintBoxes.entrySet()) for (int[] edge : EDGES) {
            double[] a = entry.getValue()[edge[0]], b = entry.getValue()[edge[1]];
            if (HologramGizmo.distance(mx, my, a[0], a[1], b[0], b[1]) <= 5) return entry.getKey();
        }
        return "";
    }

    static String pickStructure(int mx, int my) {
        String result = "";
        double best = 5;
        for (Map.Entry<String, double[][]> entry : structureBoxes.entrySet()) for (int[] edge : EDGES) {
            double[] a = entry.getValue()[edge[0]], b = entry.getValue()[edge[1]];
            double distance = HologramGizmo.distance(mx, my, a[0], a[1], b[0], b[1]);
            if (distance < best) {
                result = entry.getKey();
                best = distance;
            }
        }
        return result;
    }

    /** Exact coordinates also preserve virtual previews outside normal world height. */
    private static Coordinate coordinate(int x, int y, int z) {
        return new Coordinate(x, y, z);
    }

    private static final class Coordinate {

        final int x, y, z;

        Coordinate(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Coordinate)) return false;
            Coordinate c = (Coordinate) other;
            return x == c.x && y == c.y && z == c.z;
        }

        @Override
        public int hashCode() {
            return x * 73856093 ^ y * 19349663 ^ z * 83492791;
        }
    }

    static int pick(int x, int y) {
        int result = -1;
        for (Face face : cachedFaces) if (face.contains(x, y)) result = face.cell.index;
        for (RoleMarker marker : cachedMarkers)
            if (x >= marker.left && x < marker.right && y >= marker.top && y < marker.bottom)
                result = marker.cell.index;
        return result;
    }

    static String roleLabel(HologramState.Cell cell) {
        if (cell.anchor || "controller".equals(cell.role)) return "\u63a7\u5236\u5668";
        if (hatchStack(cell.actualStack) || hatchStack(cell.targetStack)) return "\u4ed3\u5ba4";
        if (cell.role.contains("interface")) return "\u63a5\u53e3";
        return "";
    }

    private static boolean hatchStack(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemMachines)) return false;
        int id = stack.getItemDamage();
        return id >= 0 && id < gregtech.api.GregTechAPI.METATILEENTITIES.length
            && gregtech.api.GregTechAPI.METATILEENTITIES[id] instanceof gregtech.api.metatileentity.implementations.MTEHatch;
    }

    private static final class PreviewCell {

        final HologramState.Cell cell;
        final int exposed;
        final String role;

        PreviewCell(HologramState.Cell cell, int exposed, String role) {
            this.cell = cell;
            this.exposed = exposed;
            this.role = role;
        }
    }

    private static final class RoleMarker {

        final HologramState.Cell cell;
        final String label;
        final double x, y;
        int left, top, right, bottom;

        RoleMarker(HologramState.Cell cell, double[][] points, String label) {
            this.cell = cell;
            this.label = label;
            double sx = 0, sy = 0;
            for (double[] point : points) {
                sx += point[0];
                sy += point[1];
            }
            x = sx / points.length;
            y = sy / points.length;
        }
    }

    // Exposed within the client package for smoke assertions; no chunk renderer or world mutation is used.
    static boolean renderableBlock(HologramState.Cell cell) {
        Block block = cell.block(false);
        return block != null && block != Blocks.air
            && !cell.iconOnly
            && cell.actualStack == null
            && !(cell.targetStack != null && cell.targetStack.getItem() instanceof ItemMachines)
            && !block.hasTileEntity(cell.wantMeta)
            && block.getRenderType() >= 0;
    }

    static boolean worldMatched(HologramState.Cell cell) {
        net.minecraft.world.World world = Minecraft.getMinecraft().theWorld;
        Block block = cell.block(false);
        if (world == null || !world.blockExists(cell.x, cell.y, cell.z)
            || block == null
            || world.getBlock(cell.x, cell.y, cell.z) != block) return false;
        ItemStack target = cell.targetStack;
        if (target != null && target.getItem() instanceof ItemMachines) {
            net.minecraft.tileentity.TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            return tile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) tile).getMetaTileID() == target.getItemDamage();
        }
        return world.getBlockMetadata(cell.x, cell.y, cell.z) == cell.wantMeta;
    }

    static ItemStack renderStack(HologramState.Cell cell, boolean current) {
        if (current || cell.anchor || (cell.status.equals("protected") && cell.actualStack != null))
            return cell.actualStack;
        if (cell.targetStack != null
            && (cell.targetStack.getItem() instanceof ItemMachines || "item".equals(cell.renderKind)))
            return cell.targetStack;
        return cell.actualStack;
    }

    static void world(HologramState state, float partialTicks) {
        boolean witness = Boolean.getBoolean("gtmb.hologram.realInteractionSmoke");
        if (witness) worldRenderWitness = null;
        HologramTextures.begin(state);
        HologramBlockRender.begin(state);
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.renderViewEntity;
        if (camera == null) return;
        double px = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double py = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double pz = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        if (worldState != state || worldLayer != projectionLayer) {
            clearWorldGroups();
            worldState = state;
            worldLayer = projectionLayer;
            lastWorldGeometry = lastWorldGTGeometry = lastWorldFallback = lastWorldMatched = lastWorldGhosts = 0;
        }
        // Indexing and list compilation share a small frame budget. No structure coordinates
        // are discarded; distant groups remain cached and are built when the player approaches.
        long deadline = System.nanoTime() + WORLD_BUILD_NANOS;
        indexWorld(state, deadline);
        int[] anchor = state.data.getBoolean("virtual") && state.data.getBoolean("moving")
            ? HologramClient.movingAnchor(state)
            : null;
        double offsetX = anchor == null ? 0 : anchor[0] - state.data.getInteger("x");
        double offsetY = anchor == null ? 0 : anchor[1] - state.data.getInteger("y");
        double offsetZ = anchor == null ? 0 : anchor[2] - state.data.getInteger("z");
        Frustrum frustum = new Frustrum();
        frustum.setPosition(px, py, pz);
        double range = Math.max(16, mc.gameSettings.renderDistanceChunks * 16);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        int drawnLists = 0;
        try {
            GL11.glTranslated(-px, -py, -pz);
            GL11.glTranslated(offsetX, offsetY, offsetZ);
            GL11.glDisable(GL11.GL_LIGHTING);
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glFrontFace(GL11.GL_CCW);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, .01f);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            GL11.glLineWidth(1.25f);
            for (WorldGroup group : worldGroups.values()) {
                double gx = group.x + offsetX, gy = group.y + offsetY, gz = group.z + offsetZ;
                double dx = Math.max(gx - px, Math.max(0, px - gx - 16));
                double dy = Math.max(gy - py, Math.max(0, py - gy - 16));
                double dz = Math.max(gz - pz, Math.max(0, pz - gz - 16));
                if (dx * dx + dy * dy + dz * dz > range * range || !frustum
                    .isBoundingBoxInFrustum(AxisAlignedBB.getBoundingBox(gx, gy, gz, gx + 16, gy + 16, gz + 16)))
                    continue;
                // Finish occupancy first so faces shared by different groups stay hidden.
                if (worldIndex == state.cells.size()) {
                    while (group.built < group.cells.size() && System.nanoTime() < deadline) {
                        if (!compileWorldGroup(group, state.data.getInteger("mode") == 2)) break;
                    }
                }
                for (int list : group.lists) {
                    GL11.glCallList(list);
                    if (witness) drawnLists++;
                }
            }
            if (witness && drawnLists > 0) worldRenderWitness = new WorldRenderWitness(
                state,
                anchor,
                offsetX,
                offsetY,
                offsetZ,
                ++worldRenderGeneration,
                drawnLists);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** Opt-in observation of the state and translation actually consumed by a submitted world list. */
    static WorldRenderWitness lastWorldRenderWitness() {
        return worldRenderWitness;
    }

    static final class WorldRenderWitness {

        final HologramState state;
        final String session;
        final long revision, generation;
        final int anchorX, anchorY, anchorZ, lists, geometry, ghosts, outlineColor;
        final double offsetX, offsetY, offsetZ;
        final boolean moving;

        WorldRenderWitness(HologramState state, int[] anchor, double offsetX, double offsetY, double offsetZ,
            long generation, int lists) {
            this.state = state;
            session = state.session;
            revision = state.data.getLong("planRevision");
            this.generation = generation;
            this.lists = lists;
            moving = anchor != null;
            anchorX = moving ? anchor[0] : state.data.getInteger("x");
            anchorY = moving ? anchor[1] : state.data.getInteger("y");
            anchorZ = moving ? anchor[2] : state.data.getInteger("z");
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            geometry = lastWorldGeometry;
            ghosts = lastWorldGhosts;
            outlineColor = lastWorldPlacementOutlineColor;
        }

        int[] anchor() {
            return moving ? new int[] { anchorX, anchorY, anchorZ } : null;
        }
    }

    private static void clearWorldGroups() {
        lastWorldPlacementOutlineColor = 0;
        for (WorldGroup group : worldGroups.values()) for (int list : group.lists) GL11.glDeleteLists(list, 1);
        worldGroups.clear();
        worldGhosts.clear();
        worldOccluders.clear();
        worldIndex = 0;
    }

    /** Group lists refer to native lists, so discard them before releasing their native snapshot. */
    static void nativeSnapshotChanged() {
        clearWorldGroups();
        worldState = null;
        previewState = null;
        visibleState = null;
    }

    private static void indexWorld(HologramState state, long deadline) {
        boolean removal = state.data.getInteger("mode") == 2;
        boolean virtual = state.data.getBoolean("virtual");
        int count = 0;
        while (worldIndex < state.cells.size() && count++ < WORLD_INDEX_BATCH && System.nanoTime() < deadline) {
            HologramState.Cell c = state.cells.get(worldIndex++);
            if (c.status.equals("removed") || c.completed()) continue;
            if (!c.anchor && worldLayer != Integer.MAX_VALUE && c.dy != worldLayer) continue;
            if (!c.anchor && c.id.equals("minecraft:air")
                && c.wantId.equals("minecraft:air")
                && !c.role.contains("interface")) continue;
            Coordinate key = coordinate(c.x >> 4, c.y >> 4, c.z >> 4);
            WorldGroup group = worldGroups.get(key);
            if (group == null) {
                group = new WorldGroup(c.x >> 4 << 4, c.y >> 4 << 4, c.z >> 4 << 4);
                worldGroups.put(key, group);
            }
            group.cells.add(c);
            if ((!c.anchor || virtual) && !removal
                && (virtual || !c.status.equals("satisfied") && !worldMatched(c))
                && !c.wantId.equals("minecraft:air")) {
                Coordinate position = coordinate(c.x, c.y, c.z);
                worldGhosts.add(position);
                if (occludes(c, false)) worldOccluders.add(position);
            } else if (!c.anchor && !removal) lastWorldMatched++;
        }
    }

    private static boolean compileWorldGroup(WorldGroup group, boolean removal) {
        int list = GL11.glGenLists(1);
        if (list == 0) return false;
        int end = Math.min(group.built + WORLD_MESH_BATCH, group.cells.size());
        List<HologramState.Cell> cells = group.cells.subList(group.built, end);
        // Native lists must be built before opening the group's list and its tessellator.
        for (HologramState.Cell c : cells) if (worldGhosts.contains(coordinate(c.x, c.y, c.z))) {
            HologramBlockRender.ready(c, false);
            HologramLinkNodePreview.prepare(c, false);
        }
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        t.startDrawingQuads();
        for (HologramState.Cell c : cells) {
            if (!worldGhosts.contains(coordinate(c.x, c.y, c.z))) continue;
            if (HologramBlockRender.ready(c, false)) {
                lastWorldGhosts++;
                continue;
            }
            HologramTextures.Material material = HologramTextures.material(c, false);
            lastWorldGhosts++;
            if (material.gregTech) lastWorldGTGeometry++;
            if (material.fallback || HologramNativeTiles.supported(c.block(false))) lastWorldFallback++;
            for (HologramGeometry.Surface surface : material.geometry) {
                int side = surface.side;
                int[] normal = NORMALS[side];
                if (!material.partial
                    && worldOccluders.contains(coordinate(c.x + normal[0], c.y + normal[1], c.z + normal[2]))) continue;
                int sampleIndex = 0;
                for (HologramTextures.Sample sample : material.samples(surface)) {
                    setSampleColor(t, sample, side == 1 ? 1 : side == 0 ? .65f : .85f, .38f);
                    for (int vertex : FACES[side]) {
                        double[] point = surface.vertices[vertex];
                        double offset = .002 + sampleIndex * .0005;
                        t.addVertexWithUV(
                            c.x + .5 + point[0] + normal[0] * offset,
                            c.y + .5 + point[1] + normal[1] * offset,
                            c.z + .5 + point[2] + normal[2] * offset,
                            textureU(sample.icon, side, point),
                            textureV(sample.icon, side, point));
                    }
                    sampleIndex++;
                }
            }
        }
        t.draw();
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT);
        GL14.glBlendColor(1, 1, 1, .38f);
        GL11.glBlendFunc(0x8003, 0x8004); // GL_CONSTANT_ALPHA / GL_ONE_MINUS_CONSTANT_ALPHA
        GL11.glEnable(GL11.GL_BLEND);
        for (HologramState.Cell c : cells)
            if (worldGhosts.contains(coordinate(c.x, c.y, c.z)) && HologramBlockRender.ready(c, false)) {
                GL11.glPushMatrix();
                GL11.glTranslated(c.x, c.y, c.z);
                HologramBlockRender.draw(c, false);
                GL11.glPopMatrix();
            }
        GL11.glPopAttrib();
        for (HologramState.Cell c : cells) if (worldGhosts.contains(coordinate(c.x, c.y, c.z))) {
            GL11.glPushMatrix();
            GL11.glTranslated(c.x, c.y, c.z);
            HologramLinkNodePreview.draw(c, false, .38f);
            GL11.glPopMatrix();
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        t.startDrawing(GL11.GL_LINES);
        for (HologramState.Cell c : cells) {
            int color = c.anchor ? 0xffffbd : c.color();
            if (worldState != null && worldState.data.getBoolean("virtualPlacement") && !removal) {
                color = worldState.data.getBoolean("virtualLocked") ? 0x66d9ef : 0x70e69a;
                lastWorldPlacementOutlineColor = color;
            }
            if (removal && c.status.equals("pending")) color = 0xf08b83;
            t.setColorRGBA_F(
                ((color >> 16) & 255) / 255f,
                ((color >> 8) & 255) / 255f,
                (color & 255) / 255f,
                c.anchor ? .95f : .65f);
            for (int[] edge : EDGES) for (int v : edge) t.addVertex(
                c.x + .5 + VERTICES[v][0] * 1.006,
                c.y + .5 + VERTICES[v][1] * 1.006,
                c.z + .5 + VERTICES[v][2] * 1.006);
            lastWorldGeometry++;
        }
        t.draw();
        GL11.glEndList();
        group.lists.add(list);
        group.built = end;
        return true;
    }

    private static final class WorldGroup {

        final int x, y, z;
        final List<HologramState.Cell> cells = new ArrayList<>();
        final List<Integer> lists = new ArrayList<>();
        int built;

        WorldGroup(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static void setSampleColor(Tessellator tessellator, HologramTextures.Sample sample, float shade,
        float alpha) {
        int color = sample.color;
        tessellator.setColorRGBA_F(
            ((color >> 16) & 255) / 255f * shade,
            ((color >> 8) & 255) / 255f * shade,
            (color & 255) / 255f * shade,
            alpha);
    }

    private static boolean occludes(HologramState.Cell cell, boolean current) {
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        Block block = cell.block(current);
        return HologramOcclusion.fullCube(
            block,
            HologramTextures.material(cell, current).partial,
            HologramNativeTiles.supported(block),
            cell.iconOnly);
    }

    private static double textureU(IIcon icon, int side, double[] point) {
        double u = side == 4 ? point[2] + .5 : side == 5 ? .5 - point[2] : side == 2 ? .5 - point[0] : point[0] + .5;
        return icon.getMinU() + (icon.getMaxU() - icon.getMinU()) * u;
    }

    private static double textureV(IIcon icon, int side, double[] point) {
        double v = side == 0 ? point[2] + .5 : side == 1 ? .5 - point[2] : .5 - point[1];
        return icon.getMinV() + (icon.getMaxV() - icon.getMinV()) * v;
    }

    private static final class Face {

        final HologramState.Cell cell;
        final double[][] points;
        final int side;
        final double depth;
        final HologramTextures.Material material;
        final boolean empty, nativeModel;
        final HologramGeometry.Surface surface;

        Face(HologramState.Cell cell, double[][] points, int side, boolean current) {
            this(cell, points, side, current, null);
        }

        Face(HologramState.Cell cell, double[][] points, int side, boolean current, HologramGeometry.Surface surface) {
            this.cell = cell;
            this.points = points;
            this.side = side;
            this.surface = surface;
            empty = !cell.anchor && !cell.iconOnly && (current ? cell.id : cell.wantId).equals("minecraft:air");
            nativeModel = !empty && HologramBlockRender.ready(cell, current);
            material = empty ? null : HologramTextures.material(cell, current);
            double d = 0;
            for (int index : FACES[side]) d += points[index][2];
            depth = d / 4;
        }

        List<HologramTextures.Sample> samples() {
            return surface == null ? material.sides[side] : material.samples(surface);
        }

        double[] local(int vertex) {
            return surface == null ? VERTICES[vertex] : surface.vertices[vertex];
        }

        boolean contains(double x, double y) {
            boolean inside = false;
            int[] face = FACES[side];
            for (int i = 0, j = 3; i < 4; j = i++) {
                double[] a = points[face[i]], b = points[face[j]];
                if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
            }
            return inside;
        }
    }
}
