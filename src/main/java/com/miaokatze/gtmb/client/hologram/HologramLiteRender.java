// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import com.miaokatze.gtmb.client.miaovisonlite.MiaoVisonLite;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;

import gregtech.common.blocks.ItemMachines;

/** Adapts detached GTMB snapshots to resident, region-local preview geometry. */
final class HologramLiteRender implements AutoCloseable {

    private static final int CELLS_PER_BATCH = 1024;
    private final MiaoVisonLite.Scene geometry = new MiaoVisonLite.Scene();
    private final MiaoVisonLite.Writer opaque = new MiaoVisonLite.Writer(MiaoVisonLite.Layout.MATERIAL);
    private final MiaoVisonLite.Writer transparent = new MiaoVisonLite.Writer(MiaoVisonLite.Layout.MATERIAL);
    private final MiaoVisonLite.Writer outlines = new MiaoVisonLite.Writer(MiaoVisonLite.Layout.OUTLINE);
    private final Iterator<Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>>> pending;
    private final List<Region> regions = new ArrayList<>();
    private final Map<HologramRegionCache.Key, Region> lookup = new HashMap<>();
    private final List<Region> visible = new ArrayList<>();
    private final Map<String, Map<Integer, Style>> plain = new HashMap<>();
    private final Map<Object, Style> descriptors = new IdentityHashMap<>();
    private final Map<HologramTextures.Material, List<Quad>> palette = new IdentityHashMap<>();
    final HologramState state;
    final int layer, view;
    final boolean empty, ghost, current;
    final int x, y, z;
    private List<HologramState.Cell> rows;
    private Region building;
    private int row, inBatch;
    private int marked;
    int completed, submittedLists, drawnCells, drawnFallbackCells, drawnGtCells;
    boolean finished, submitted, materialVisible, materialObserved;

    HologramLiteRender(HologramState state, int layer, int view, boolean empty, boolean ghost) {
        this.state = state;
        this.layer = layer;
        this.view = view;
        this.empty = empty;
        this.ghost = ghost;
        current = view == 1 || state.data.getInteger("mode") == 2;
        x = state.data.getInteger("x");
        y = state.data.getInteger("y");
        z = state.data.getInteger("z");
        pending = state.regions.regions.entrySet()
            .iterator();
    }

    boolean matches(HologramState state, int layer, int view, boolean empty) {
        return this.state == state && this.layer == layer && this.view == view && this.empty == empty;
    }

    void advance(long budget) {
        if (finished) return;
        long deadline = System.nanoTime() + budget;
        boolean worked = false;
        while (!worked || System.nanoTime() < deadline) {
            if (rows == null) {
                if (!pending.hasNext()) {
                    finished = true;
                    building = null;
                    break;
                }
                Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry = pending.next();
                rows = entry.getValue();
                building = new Region(entry.getKey(), geometry);
                row = 0;
                regions.add(building);
                lookup.put(entry.getKey(), building);
            }
            if (row == rows.size()) {
                flush();
                rows = null;
                continue;
            }
            emit(rows.get(row++));
            worked = true;
            completed++;
            if (++inBatch == CELLS_PER_BATCH) flush();
        }
        // Publish the first prepared prefix on its actual draw frame, including sparse input.
        if (rows != null) flush();
    }

    private void flush() {
        geometry.publish(building.geometry, MiaoVisonLite.Layer.OPAQUE, opaque);
        geometry.publish(building.geometry, MiaoVisonLite.Layer.TRANSLUCENT, transparent);
        geometry.publish(building.geometry, MiaoVisonLite.Layer.OUTLINE, outlines);
        inBatch = 0;
    }

    private void emit(HologramState.Cell cell) {
        if (!includes(cell)) return;
        Style style = style(cell);
        int exposed = 0;
        if (style.nativeModel || style.material != null && style.material.partial || style.empty) exposed = 63;
        else for (int side = 0; side < 6; side++) {
            int[] n = HologramRenderer.NORMALS[side];
            HologramState.Cell adjacent = state.regions.at(cell.x + n[0], cell.y + n[1], cell.z + n[2]);
            if (adjacent == null || !includes(adjacent) || !style(adjacent).opaque) exposed |= 1 << side;
        }

        int lx = cell.x - building.x, ly = cell.y - building.y, lz = cell.z - building.z;
        if (ghost || exposed != 0) putOutline(cell, lx, ly, lz, exposed);
        if (style.empty) return;
        if (ghost || exposed != 0) building.cells++;
        boolean linked = style.linked;
        boolean nativeReady = false;
        if (style.nativeModel || linked) {
            HologramBlockRender.begin(state);
            nativeReady = style.nativeModel && HologramBlockRender.ready(cell, current);
            if (linked) linked = !HologramLinkNodePreview.prepare(cell, current)
                .isEmpty();
        }
        if (nativeReady || linked || exposed != 0 && style.dynamic)
            building.special.add(new Special(cell, style, exposed, nativeReady, linked));
        building.nativeModels |= nativeReady || linked;
        if (!nativeReady) {
            if (style.material.fallback || style.nativeModel) building.fallbackCells++;
            if (style.material.gregTech) building.gtCells++;
        }
        if (!ghost && marked < 64) {
            String label = HologramRenderer.roleLabel(cell);
            if (cell.missingBlock) label = "缺失方块";
            else if (style.nativeModel && !nativeReady) label = "模型缺失";
            else if (style.material.fallback && label.isEmpty()) label = "材质缺失";
            else if (cell.iconOnly && label.isEmpty()) label = "占位";
            if (!cell.prompt.isEmpty()) label = label.isEmpty() ? "提示" : label + " · 提示";
            if (!label.isEmpty()) {
                building.labels.add(new Label(cell, label));
                marked++;
            }
        }
        if (nativeReady || exposed == 0) return;
        MiaoVisonLite.Writer writer = ghost || !style.opaque ? transparent : opaque;
        for (Quad quad : style.quads) {
            if (quad.dynamic || (exposed & 1 << quad.side) == 0) continue;
            if (writer.remainingVertices() < 4) geometry.publish(
                building.geometry,
                writer == opaque ? MiaoVisonLite.Layer.OPAQUE : MiaoVisonLite.Layer.TRANSLUCENT,
                writer);
            int rgba = rgba(cell, quad);
            putQuad(writer, quad, lx, ly, lz, rgba);
        }
    }

    private void putOutline(HologramState.Cell cell, int lx, int ly, int lz, int exposed) {
        for (int[] edge : HologramRenderer.EDGES) {
            double[] a = HologramRenderer.VERTICES[edge[0]], b = HologramRenderer.VERTICES[edge[1]];
            int axis = a[0] != b[0] ? 0 : a[1] != b[1] ? 1 : 2;
            if (!ghost && !exposedEdge(a, axis, exposed)) continue;
            int ax = cell.x + (int) (Math.min(a[0], b[0]) + .5);
            int ay = cell.y + (int) (Math.min(a[1], b[1]) + .5);
            int az = cell.z + (int) (Math.min(a[2], b[2]) + .5);
            if (ghost && !HologramSelectionOutline.ownsEdge(cell.x, cell.y, cell.z, ax, ay, az, axis, this::includedAt))
                continue;
            if (outlines.remainingVertices() < 2)
                geometry.publish(building.geometry, MiaoVisonLite.Layer.OUTLINE, outlines);
            outlines.position(lx + .5 + a[0], ly + .5 + a[1], lz + .5 + a[2]);
            outlines.position(lx + .5 + b[0], ly + .5 + b[1], lz + .5 + b[2]);
        }
    }

    private int rgba(HologramState.Cell cell, Quad quad) {
        float shade = quad.side == 1 ? 1 : quad.side == 0 ? ghost ? .65f : .6f : ghost ? .85f : .8f;
        return !ghost && cell.sourceMissing && !cell.missingBlock ? color(0x8c8c8c, 1, .78f)
            : color(quad.color, shade, ghost ? .38f : .96f);
    }

    private boolean includedAt(int ax, int ay, int az) {
        HologramState.Cell cell = state.regions.at(ax, ay, az);
        return cell != null && includes(cell);
    }

    private static boolean exposedEdge(double[] vertex, int axis, int exposed) {
        if (axis != 0 && (exposed & 1 << (vertex[0] < 0 ? 4 : 5)) != 0) return true;
        if (axis != 1 && (exposed & 1 << (vertex[1] < 0 ? 0 : 1)) != 0) return true;
        return axis != 2 && (exposed & 1 << (vertex[2] < 0 ? 2 : 3)) != 0;
    }

    private void putQuad(MiaoVisonLite.Writer writer, Quad quad, int lx, int ly, int lz, int rgba) {
        int[] normal = HologramRenderer.NORMALS[quad.side];
        double offset = (ghost ? .002 : 0) + quad.layer * .0005;
        for (int corner = 0; corner < 4; corner++) {
            double[] p = quad.surface.vertices[HologramRenderer.FACES[quad.side][corner]];
            writer.material(
                lx + .5 + p[0] + normal[0] * offset,
                ly + .5 + p[1] + normal[1] * offset,
                lz + .5 + p[2] + normal[2] * offset,
                quad.uv[corner * 2],
                quad.uv[corner * 2 + 1],
                rgba);
        }
    }

    private static int color(int rgb, float shade, float alpha) {
        return (int) ((rgb >>> 16 & 255) * shade) << 24 | (int) ((rgb >>> 8 & 255) * shade) << 16
            | (int) ((rgb & 255) * shade) << 8
            | (int) (alpha * 255);
    }

    private boolean includes(HologramState.Cell cell) {
        if (ghost) {
            return (cell.anchor || layer == Integer.MAX_VALUE || cell.dy == layer) && !"removed".equals(cell.status)
                && !"minecraft:air".equals(cell.wantId)
                && !HologramBlockRender.invisible(cell, false);
        }
        return HologramRenderer.previewVisible(cell, layer, view, current, empty);
    }

    private Style style(HologramState.Cell cell) {
        boolean actual = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        String id = actual ? cell.id : cell.wantId;
        ItemStack stack = actual ? cell.actualStack : cell.targetStack;
        NBTTagCompound display = actual ? cell.actualDisplay : cell.targetDisplay;
        if (!cell.iconOnly && (stack == null || !(stack.getItem() instanceof ItemMachines))
            && !display.hasKey("gt.covers")) {
            int meta = actual ? cell.meta : cell.wantMeta;
            // Intern the material before touching tile, texture or vertex state.
            Map<Integer, Style> metas = plain.get(id);
            if (metas == null) {
                metas = new HashMap<>();
                plain.put(id, metas);
            }
            Style found = metas.get(meta);
            if (found != null) return found;
            Style resolved = resolveStyle(cell, actual, id, false);
            metas.put(meta, resolved);
            return resolved;
        }
        Object descriptor = cell.descriptorIdentity();
        Style shared = descriptor == cell ? null : descriptors.get(descriptor);
        if (shared != null) return shared;
        Style resolved = resolveStyle(cell, actual, id, display.hasKey("gt.covers"));
        if (descriptor != cell) descriptors.put(descriptor, resolved);
        return resolved;
    }

    private Style resolveStyle(HologramState.Cell cell, boolean actual, String id, boolean linked) {
        Block block = cell.block(actual);
        boolean air = !cell.iconOnly && "minecraft:air".equals(id);
        if (air) return new Style(null, java.util.Collections.emptyList(), false, false, true, false);
        boolean nativeModel = HologramNativeTiles.supported(block);
        HologramTextures.Material material = HologramTextures.materialUntracked(cell, actual);
        boolean solid = HologramOcclusion.fullCube(block, material.partial, nativeModel, cell.iconOnly);
        List<Quad> quads = palette.computeIfAbsent(material, HologramLiteRender::quads);
        return new Style(material, quads, solid, nativeModel, false, linked);
    }

    private static List<Quad> quads(HologramTextures.Material material) {
        List<Quad> quads = new ArrayList<>();
        for (HologramGeometry.Surface surface : material.geometry) {
            int layer = 0;
            for (HologramTextures.Sample sample : material.samples(surface)) {
                if (sample.icon != null) quads.add(new Quad(surface, sample, layer));
                layer++;
            }
        }
        return quads;
    }

    void draw(HologramEditorCameraFrame frame, float outlineAlpha, int outlineColor, RegionFilter filter) {
        visible.clear();
        drawnCells = drawnFallbackCells = drawnGtCells = 0;
        for (Region region : regions) if (filter.visible(region.x, region.y, region.z)) {
            visible.add(region);
            drawnCells += region.cells;
            drawnFallbackCells += region.fallbackCells;
            drawnGtCells += region.gtCells;
        }
        // The GUI draws the resident regions back to front for transparent atlas materials.
        if (frame != null) visible.sort(Comparator.comparingDouble(region -> region.depth(frame, x, y, z)));
        submittedLists = 0;
        materialVisible = false;
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glFrontFace(GL11.GL_CCW);
        GL11.glColor4f(1, 1, 1, 1);
        try (MiaoVisonLite.Frame pass = geometry.beginFrame()) {
            for (Region region : visible) submittedLists += pass.draw(region.geometry, MiaoVisonLite.Layer.OPAQUE);
            for (Region region : visible) submittedLists += pass.draw(region.geometry, MiaoVisonLite.Layer.TRANSLUCENT);
            materialVisible = submittedLists > 0;
        }
        drawDynamic();
        if (hasSpecial()) drawNative();
        if (outlineAlpha > 0) {
            try (MiaoVisonLite.Frame pass = geometry.beginFrame()) {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDepthMask(false);
                GL11.glColor4f(
                    (outlineColor >>> 16 & 255) / 255f,
                    (outlineColor >>> 8 & 255) / 255f,
                    (outlineColor & 255) / 255f,
                    outlineAlpha);
                for (Region region : visible) submittedLists += pass.draw(region.geometry, MiaoVisonLite.Layer.OUTLINE);
                GL11.glDepthMask(!ghost);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
            }
        }
        submitted = finished;
    }

    RegionFilter viewport(HologramEditorCameraFrame frame) {
        double minX = frame.coordinates.x(), minY = frame.coordinates.y();
        double maxX = minX + frame.coordinates.width(), maxY = minY + frame.coordinates.height();
        double rx = 8 * frame.scale * (Math.abs(frame.basis.rightX()) + Math.abs(frame.basis.rightZ()));
        double ry = 8 * frame.scale
            * (Math.abs(frame.basis.upX()) + Math.abs(frame.basis.upY()) + Math.abs(frame.basis.upZ()));
        double[] center = new double[3];
        return (gx, gy, gz) -> {
            frame.project(gx + 7.5 - x, gy + 7.5 - y, gz + 7.5 - z, center);
            return center[0] + rx >= minX && center[0] - rx <= maxX && center[1] + ry >= minY && center[1] - ry <= maxY;
        };
    }

    private boolean hasSpecial() {
        for (Region region : visible) if (region.nativeModels) return true;
        return false;
    }

    private void drawDynamic() {
        Tessellator tess = Tessellator.instance;
        int vertices = 0;
        for (Region region : visible) for (Special special : region.special) {
            if (special.nativeModel || !special.style.dynamic) continue;
            HologramState.Cell cell = special.cell;
            for (Quad quad : special.style.quads) {
                if (!quad.dynamic || (special.exposed & 1 << quad.side) == 0) continue;
                if (vertices == 0) tess.startDrawingQuads();
                int rgba = rgba(cell, quad);
                tess.setColorRGBA(rgba >>> 24, rgba >>> 16 & 255, rgba >>> 8 & 255, rgba & 255);
                int[] normal = HologramRenderer.NORMALS[quad.side];
                double offset = (ghost ? .002 : 0) + quad.layer * .0005;
                for (int vertex : HologramRenderer.FACES[quad.side]) {
                    double[] p = quad.surface.vertices[vertex];
                    tess.addVertexWithUV(
                        cell.x + .5 + p[0] + normal[0] * offset,
                        cell.y + .5 + p[1] + normal[1] * offset,
                        cell.z + .5 + p[2] + normal[2] * offset,
                        HologramRenderer.textureU(quad.icon, quad.side, p),
                        HologramRenderer.textureV(quad.icon, quad.side, p));
                }
                vertices += 4;
                materialVisible = true;
                if (vertices >= 4092) {
                    tess.draw();
                    submittedLists++;
                    vertices = 0;
                }
            }
        }
        if (vertices > 0) {
            tess.draw();
            submittedLists++;
        }
    }

    private void drawNative() {
        HologramBlockRender.begin(state);
        GL11.glDisable(GL11.GL_CULL_FACE);
        if (ghost) {
            GL14.glBlendColor(1, 1, 1, .38f);
            GL11.glBlendFunc(0x8003, 0x8004);
        }
        long deadline = System.nanoTime() + 1_000_000L;
        for (Region region : visible) for (Special special : region.special) {
            if (!special.nativeModel && !special.linked) continue;
            HologramState.Cell cell = special.cell;
            GL11.glPushMatrix();
            try {
                GL11.glTranslated(cell.x, cell.y, cell.z);
                if (special.nativeModel && HologramBlockRender.cachedOrPrepare(cell, current, deadline)) {
                    HologramBlockRender.draw(cell, current);
                    if (HologramBlockRender.vertexCount(cell, current || cell.anchor) > 0) {
                        materialVisible = true;
                        submittedLists++;
                    }
                }
                if (special.linked) {
                    HologramLinkNodePreview.draw(cell, current, ghost ? .38f : .96f);
                    submittedLists++;
                }
            } finally {
                GL11.glPopMatrix();
            }
        }
        if (ghost) GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    List<Label> labels() {
        List<Label> result = new ArrayList<>(marked);
        for (Region region : visible) result.addAll(region.labels);
        return result;
    }

    Hit pick(HologramEditorCameraFrame frame, int mouseX, int mouseY) {
        if (!submitted || !frame.coordinates.contains(mouseX, mouseY)) return null;
        CameraRay ray = frame.basis
            .ray(frame.projection, frame.coordinates.ndcX(mouseX), frame.coordinates.ndcY(mouseY));
        double[] start = { ray.originX() + x + .5, ray.originY() + y + .5, ray.originZ() + z + .5 };
        double[] direction = { ray.directionX(), ray.directionY(), ray.directionZ() };
        Hit best = null;
        for (Region region : visible) {
            Hit hit = hitRegion(region, start, direction, best == null ? Double.POSITIVE_INFINITY : best.distance);
            if (hit != null) best = hit;
        }
        return best;
    }

    private Hit hitRegion(Region region, double[] start, double[] direction, double best) {
        int[] min = { region.x, region.y, region.z };
        double enter = 0, end = best;
        int side = -1;
        for (int axis = 0; axis < 3; axis++) {
            double o = start[axis], d = direction[axis];
            if (Math.abs(d) < 1e-12) {
                if (o < min[axis] || o > min[axis] + 16) return null;
                continue;
            }
            double a = (min[axis] - o) / d, b = (min[axis] + 16.0 - o) / d;
            double first = Math.min(a, b);
            if (first > enter) {
                enter = first;
                side = side(axis, d);
            }
            end = Math.min(end, Math.max(a, b));
            if (enter >= end) return null;
        }
        int[] voxel = new int[3], step = new int[3];
        double[] next = new double[3], stride = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double d = direction[axis];
            voxel[axis] = (int) Math.floor(start[axis] + (enter + 1e-8) * d);
            step[axis] = d > 0 ? 1 : -1;
            stride[axis] = Math.abs(d) < 1e-12 ? Double.POSITIVE_INFINITY : Math.abs(1 / d);
            next[axis] = Math.abs(d) < 1e-12 ? Double.POSITIVE_INFINITY
                : (voxel[axis] + (d > 0 ? 1 : 0) - start[axis]) / d;
        }
        while (enter < end) {
            HologramState.Cell cell = state.regions.at(voxel[0], voxel[1], voxel[2]);
            if (cell != null && includes(cell)) return new Hit(cell, side, enter);
            int axis = next[0] < next[1] ? next[0] < next[2] ? 0 : 2 : next[1] < next[2] ? 1 : 2;
            enter = next[axis];
            next[axis] += stride[axis];
            voxel[axis] += step[axis];
            side = side(axis, direction[axis]);
        }
        return null;
    }

    private static int side(int axis, double direction) {
        return (axis == 0 ? 4 : axis == 1 ? 0 : 2) + (direction < 0 ? 1 : 0);
    }

    boolean readyCell(HologramState.Cell cell) {
        Region region = lookup.get(HologramRegionCache.key(cell));
        return region != null && region != building && includes(cell);
    }

    @Override
    public void close() {
        geometry.close();
        regions.clear();
        visible.clear();
        lookup.clear();
        opaque.close();
        transparent.close();
        outlines.close();
    }

    interface RegionFilter {

        boolean visible(int x, int y, int z);
    }

    static final class Hit {

        final HologramState.Cell cell;
        final int side;
        final double distance;

        Hit(HologramState.Cell cell, int side, double distance) {
            this.cell = cell;
            this.side = side;
            this.distance = distance;
        }
    }

    static final class Label {

        final HologramState.Cell cell;
        final String text;

        Label(HologramState.Cell cell, String text) {
            this.cell = cell;
            this.text = text;
        }
    }

    private static final class Style {

        final HologramTextures.Material material;
        final List<Quad> quads;
        final boolean opaque, nativeModel, empty, linked, dynamic;

        Style(HologramTextures.Material material, List<Quad> quads, boolean opaque, boolean nativeModel, boolean empty,
            boolean linked) {
            this.material = material;
            this.quads = quads;
            this.opaque = opaque;
            this.nativeModel = nativeModel;
            this.empty = empty;
            this.linked = linked;
            dynamic = quads.stream()
                .anyMatch(quad -> quad.dynamic);
        }
    }

    private static final class Quad {

        final HologramGeometry.Surface surface;
        final net.minecraft.util.IIcon icon;
        final int side, color, layer;
        final boolean dynamic;
        final float[] uv;

        Quad(HologramGeometry.Surface surface, HologramTextures.Sample sample, int layer) {
            this.surface = surface;
            icon = sample.icon;
            side = surface.side;
            color = sample.color;
            this.layer = layer;
            dynamic = !HologramRenderer.stablePreviewIcon(icon);
            uv = dynamic ? null : new float[8];
            if (uv != null) for (int corner = 0; corner < 4; corner++) {
                double[] p = surface.vertices[HologramRenderer.FACES[side][corner]];
                uv[2 * corner] = (float) HologramRenderer.textureU(icon, side, p);
                uv[2 * corner + 1] = (float) HologramRenderer.textureV(icon, side, p);
            }
        }
    }

    private static final class Special {

        final HologramState.Cell cell;
        final Style style;
        final int exposed;
        final boolean nativeModel, linked;

        Special(HologramState.Cell cell, Style style, int exposed, boolean nativeModel, boolean linked) {
            this.cell = cell;
            this.style = style;
            this.exposed = exposed;
            this.nativeModel = nativeModel;
            this.linked = linked;
        }
    }

    private static final class Region {

        final int x, y, z;
        final MiaoVisonLite.Region geometry;
        final List<Special> special = new ArrayList<>();
        final List<Label> labels = new ArrayList<>();
        boolean nativeModels;
        int cells, gtCells, fallbackCells;

        Region(HologramRegionCache.Key key, MiaoVisonLite.Scene scene) {
            x = key.x << 4;
            y = key.y << 4;
            z = key.z << 4;
            geometry = scene.region(x, y, z);
        }

        double depth(HologramEditorCameraFrame frame, int originX, int originY, int originZ) {
            return -(frame.basis.forwardX() * ((double) x + 8 - originX)
                + frame.basis.forwardY() * ((double) y + 8 - originY)
                + frame.basis.forwardZ() * ((double) z + 8 - originZ));
        }

    }
}
