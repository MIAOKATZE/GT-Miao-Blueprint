package com.miaokatze.gtmb.client.hologram;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.miaokatze.miaokatzecore.client.render.TessellatorSlice;

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
    private static PreviewSubmission previewSubmission;
    private static List<Face> hoverFaces;
    private static int hoverX, hoverY, hoveredCell = -1;
    static long previewSliceCaptures, previewSliceHits, previewSliceUnsupported, previewSliceFallbackFrames;
    static long previewSliceCapturedInts;
    static long previewHoverScans;

    private static void invalidatePreviewDrawCache() {
        previewSubmission = null;
        hoverFaces = null;
    }

    private static int previewHovered(List<Face> faces, int mouseX, int mouseY, boolean picking) {
        if (!picking) {
            hoverFaces = null;
            return -1;
        }
        if (hoverFaces == faces && hoverX == mouseX && hoverY == mouseY) return hoveredCell;
        int hovered = -1;
        for (int at = 0; at < faces.size(); at++) {
            Face face = faces.get(at);
            if (face.contains(mouseX, mouseY)) hovered = face.cell.index;
        }
        hoverFaces = faces;
        hoverX = mouseX;
        hoverY = mouseY;
        hoveredCell = hovered;
        if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled()) previewHoverScans++;
        return hovered;
    }

    private static List<Face> cachedOutlines = new ArrayList<>();
    private static final FloatBuffer OUTLINE_MATRIX = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer OUTLINE_VIEWPORT = BufferUtils.createIntBuffer(16);
    private static final List<HologramState.Cell> nativePreviewCells = new ArrayList<>();
    private static HologramState visibleState;
    private static int visibleLayer, visibleView, visibleMode;
    private static final List<HologramState.Cell> visibleCells = new ArrayList<>();
    private static final List<PreviewCell> previewCells = new ArrayList<>();
    private static final Set<Coordinate> occluders = new HashSet<>();
    private static final Map<HologramRegionCache.Key, PreviewRegion> previewRegions = new LinkedHashMap<>();
    private static final long PREVIEW_BUILD_NANOS = 2_000_000L;
    private static PreviewPreparation previewPreparation;
    private static final List<HologramState.Cell> linkedPreviewCells = new ArrayList<>();
    static int lastPreviewBuiltRegions, lastSnapshotDirtyRegions;
    private static List<RoleMarker> cachedMarkers = new ArrayList<>();
    private static double cachedExtent = 1;
    private static int worldLayer = Integer.MAX_VALUE;
    private static final long WORLD_BUILD_NANOS = 3_000_000L;
    private static final int WORLD_MESH_BATCH = 256;
    private static final Map<Coordinate, WorldGroup> worldGroups = new LinkedHashMap<>();
    private static int placementColorList, placementColor;
    private static final Set<Coordinate> worldGhosts = new HashSet<>();
    private static final Set<Coordinate> worldOccluders = new HashSet<>();
    private static final Set<Coordinate> worldOutlineCells = new HashSet<>();
    private static int worldIndex;
    private static HologramState boundsState;
    private static double boundsX, boundsY, boundsZ, boundsExtent;
    static int projectionLayer = Integer.MAX_VALUE;
    static int[] moveOffset = new int[3];

    private static boolean moving(HologramState.Cell cell) {
        return (moveOffset[0] != 0 || moveOffset[1] != 0 || moveOffset[2] != 0) && selection != null
            && selection.contains(cell.index);
    }

    static boolean showEmptyCells;
    static HologramSelection selection;
    static String selectedStructureId = "";
    private static HologramEditorCameraFrame projectedFrame, presentedFrame;
    private static PreviewRequest projectedRequest, presentedRequest;
    private static boolean cameraOnlyProjection, previewCameraMoving;
    private static HologramState presentedState;
    private static HologramState displayedState;
    private static boolean previewSubmitted;
    private static final java.util.BitSet publishedPreviewCells = new java.util.BitSet();
    private static final Map<String, double[][]> structureBoxes = new LinkedHashMap<>();
    private static final Set<String> missingStructures = new HashSet<>();
    private static final Map<String, double[][]> hintBoxes = new LinkedHashMap<>();
    private static HologramState structureBoundsState;
    private static Map<String, int[]> cachedStructureBounds = new LinkedHashMap<>();
    private static boolean visibleEmptyCells;

    static void release() {
        projectedFrame = presentedFrame = null;
        projectedRequest = presentedRequest = null;
        cameraOnlyProjection = previewCameraMoving = false;
        presentedState = null;
        displayedState = null;
        previewSubmitted = false;
        publishedPreviewCells.clear();
        worldRenderWitness = null;
        clearWorldGroups();
        worldState = previewState = null;
        boundsState = null;
        HologramTextures.clear();
        HologramLinkNodePreview.clear();
        HologramBlockRender.clear();
        nativePreviewCells.clear();
        cachedFaces.clear();
        invalidatePreviewDrawCache();
        cachedMarkers.clear();
        cachedOutlines.clear();
        visibleState = null;
        visibleCells.clear();
        previewCells.clear();
        previewRegions.clear();
        previewPreparation = null;
        linkedPreviewCells.clear();
        occluders.clear();
        structureBoxes.clear();
        hintBoxes.clear();
        structureBoundsState = null;
        cachedStructureBounds.clear();
    }

    /** Preserve detached geometry across a validated delta; teardown still owns world/session and reload changes. */
    static void snapshotChanged(HologramState previous, HologramState next) {
        if (!next.follows(previous) || renderMetadataChanged(previous, next)) {
            release();
            return;
        }
        Set<HologramRegionCache.Key> dirty = HologramRegionCache.affected(next);
        invalidatePreviewDrawCache();
        lastSnapshotDirtyRegions = dirty.size();
        for (HologramRegionCache.Key key : dirty) previewRegions.remove(key);
        if (visibleState == previous) visibleState = next;
        boolean extentChanged = next.topologyChanged
            && !java.util.Arrays.equals(previous.regions.bounds(), next.regions.bounds());
        if (boundsState == previous) boundsState = extentChanged ? null : next;
        if (next.topologyChanged) {
            structureBoundsState = null;
            previewPreparation = null;
            // A changed extent changes the screen transform, not the retained native region geometry.
            if (extentChanged) for (PreviewRegion region : previewRegions.values()) region.projectedKey = "";
        }
        if (structureBoundsState == previous) {
            boolean sameMemberships = true;
            for (int index : next.changedIndices) {
                HologramState.Cell before = previous.cells.get(index), after = next.cells.get(index);
                if (!before.structureIds.equals(after.structureIds) || before.sourceMissing != after.sourceMissing)
                    sameMemberships = false;
            }
            if (sameMemberships) structureBoundsState = next;
        }
        if (worldState == previous) {
            if (worldIndex != previous.cells.size()
                && (next.changedIndices.length != 0 || !next.changedPreviousCells.isEmpty())) {
                clearWorldGroups();
            } else {
                for (HologramRegionCache.Key key : dirty) {
                    WorldGroup group = worldGroups.get(coordinate(key.x, key.y, key.z));
                    if (group != null) invalidateWorldGroup(group);
                }
                for (HologramState.Cell old : next.changedPreviousCells) {
                    int index = old.index;
                    Coordinate position = coordinate(old.x, old.y, old.z);
                    WorldGroup group = worldGroups.get(coordinate(old.x >> 4, old.y >> 4, old.z >> 4));
                    if (group != null) {
                        group.cells.remove(old);
                        if (group.matchedIndices.remove(index)) {
                            lastWorldMatched--;
                        }
                    }
                    worldGhosts.remove(position);
                    worldOccluders.remove(position);
                    worldOutlineCells.remove(position);
                }
                for (int index : next.changedIndices) indexWorldCell(next, next.cells.get(index));
                worldIndex = next.cells.size();
            }
            worldState = next;
        }
        // World lists must be retired before the native lists they reference.
        HologramBlockRender.advance(previous, next, dirty);
        HologramTextures.advance(previous, next);
        HologramLinkNodePreview.advance(previous, next);
        if (visibleState == next) previewPreparation = new PreviewPreparation(next, false);
    }

    private static boolean renderMetadataChanged(HologramState previous, HologramState next) {
        String[] keys = { "mode", "virtual", "virtualPlacement", "x", "y", "z", "dimension" };
        for (String key : keys) {
            Object before = previous.data.getTag(key), after = next.data.getTag(key);
            if (!java.util.Objects.equals(before, after)) return true;
        }
        return false;
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

    static boolean previewMeshReady(HologramState state) {
        return state != null && previewState == state
            && previewPreparation != null
            && previewPreparation.state == state
            && previewPreparation.completed == state.cells.size()
            && previewPreparation.projection != null
            && previewPreparation.projection.published == state.cells.size()
            && previewSubmitted;
    }

    static HologramState previewDisplayState() {
        return displayedState;
    }

    static boolean previewPreparing(HologramState state) {
        return previewPreparation != null && previewPreparation.state == state && !previewMeshReady(state);
    }

    /** Edit admission compares the live UI request with the complete frame actually drawn, without allocating a key. */
    static boolean previewPresented(HologramState state, int x, int y, int width, int height, double yaw, double pitch,
        double zoom, double panX, double panY, double panZ, int layer, int view, boolean empty) {
        return previewMeshReady(state) && presentedState == state
            && presentedRequest != null
            && presentedRequest.matches(
                state,
                x,
                y,
                width,
                height,
                yaw,
                pitch,
                zoom,
                panX,
                panY,
                panZ,
                layer,
                view,
                empty,
                HologramBlueprintPreview.cleanCapture());
    }

    static void sceneClosed() {
        invalidatePreviewDrawCache();
        displayedState = null;
        presentedState = null;
        presentedFrame = null;
        presentedRequest = null;
        previewSubmitted = false;
    }

    static boolean previewMeshVisible(HologramState state) {
        return state != null && previewState == state
            && (!cachedFaces.isEmpty() || !nativePreviewCells.isEmpty()
                || previewPreparation != null && previewPreparation.boundary != null
                    && previewPreparation.boundary.presented);
    }

    static int previewMeshCompleted(HologramState state) {
        return previewPreparation != null && previewPreparation.state == state ? previewPreparation.completed : 0;
    }

    static int previewMeshTotal(HologramState state) {
        return state == null ? 0 : state.cells.size();
    }

    /** Detached counters from the frame actually presented for this exact snapshot. No native factory or draw. */
    static net.minecraft.nbt.NBTTagCompound lessonInspection(HologramState state) {
        net.minecraft.nbt.NBTTagCompound result = new net.minecraft.nbt.NBTTagCompound();
        boolean presented = state != null && presentedState == state && presentedFrame != null;
        result.setBoolean("snapshotPresented", presented);
        java.util.Set<Integer> geometryCells = new java.util.HashSet<>();
        if (presented) for (Face face : cachedFaces) geometryCells.add(face.cell.index);
        result.setInteger("renderedCellCount", geometryCells.size());
        result.setInteger("presentedVisibleCellCount", presented ? visibleCells.size() : 0);
        result.setInteger("nativeModelCellCount", presented ? nativePreviewCells.size() : 0);
        result.setInteger("roleMarkerCount", presented ? lastPreviewRoleMarkers : 0);
        result.setInteger("anchorMarkerCount", presented ? lastPreviewAnchorMarkers : 0);
        result.setBoolean("worldMutation", false);
        result.setBoolean("playerMutation", false);
        return result;
    }

    static int preview(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
        double panX, double panZ, int layer, int view, int selected, int mouseX, int mouseY, double rehearsal) {
        return preview(
            state,
            x,
            y,
            width,
            height,
            yaw,
            pitch,
            zoom,
            panX,
            0,
            panZ,
            layer,
            view,
            selected,
            mouseX,
            mouseY,
            rehearsal);
    }

    static int preview(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
        double panX, double panY, double panZ, int layer, int view, int selected, int mouseX, int mouseY,
        double rehearsal) {
        if (width <= 0 || height <= 0 || !Double.isFinite(zoom) || zoom <= 0) {
            presentedFrame = null;
            presentedState = null;
            presentedRequest = null;
            return -1;
        }
        HologramTextures.begin(state);
        HologramBlockRender.begin(state);
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
            + panY
            + ":"
            + panZ
            + ":"
            + layer
            + ":"
            + view
            + ":"
            + showEmptyCells
            + ":"
            + HologramBlueprintPreview.cleanCapture();
        boolean budgeted = !HologramBlueprintPreview.cleanCapture();
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
            previewRegions.clear();
            previewPreparation = budgeted ? new PreviewPreparation(state, true) : null;
        }
        lastPreviewBuiltRegions = 0;
        // The stamp belongs to this preparation, not merely to its cell count: a cold/dirty final slice may also be N.
        cameraOnlyProjection = budgeted && preparedCameraOnly(state, key, layer, view, showEmptyCells);
        previewCameraMoving = state != HologramLibraryClient.INSTANCE.browsePreviewState() && projectedRequest != null
            && projectedRequest.state == state
            && !projectedRequest.sameCamera(x, y, width, height, yaw, pitch, zoom, panX, panY, panZ);
        if (projectedRequest == null || !projectedRequest.matches(
            state,
            x,
            y,
            width,
            height,
            yaw,
            pitch,
            zoom,
            panX,
            panY,
            panZ,
            layer,
            view,
            showEmptyCells,
            !budgeted))
            projectedRequest = new PreviewRequest(
                state,
                x,
                y,
                width,
                height,
                yaw,
                pitch,
                zoom,
                panX,
                panY,
                panZ,
                layer,
                view,
                showEmptyCells,
                !budgeted);
        if (budgeted) {
            if (previewPreparation == null || previewPreparation.state != state)
                previewPreparation = new PreviewPreparation(state, true);
            prepareProgressiveFrame(state, key, x, y, width, height, yaw, pitch, zoom, panX, panY, panZ);
            if (cameraOnlyProjection) {
                // All input regions/materials/native models are already prepared. Publish only the complete current
                // camera, before any draw, without spending another budget on an occupancy prefix we will not show.
                previewPreparation.projection.advance(currentView, Integer.MAX_VALUE, Long.MAX_VALUE);
                previewPreparation.projection.publish();
            } else if (previewPreparation.completed != state.cells.size()
                || previewPreparation.projection.published != state.cells.size())
                previewPreparation.advance(layer, view, currentView, key);
        }
        if (!budgeted && (previewState != state || !key.equals(previewKey))) {
            List<Face> faces = new ArrayList<>();
            List<RoleMarker> markers = new ArrayList<>();
            List<Face> outlines = new ArrayList<>();
            nativePreviewCells.clear();
            if (!budgeted)
                for (Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry : state.regions.regions
                    .entrySet()) {
                        if (!previewRegions.containsKey(entry.getKey())
                            || !previewRegions.get(entry.getKey()).complete) {
                            previewRegions
                                .put(entry.getKey(), previewRegion(state, entry.getValue(), layer, view, currentView));
                            lastPreviewBuiltRegions++;
                        }
                    }
            visibleCells.clear();
            linkedPreviewCells.clear();
            for (PreviewRegion region : previewRegions.values()) {
                visibleCells.addAll(region.visible);
                linkedPreviewCells.addAll(region.linked);
            }
            lastPreviewRoleMarkers = lastPreviewAnchorMarkers = 0;
            if (boundsState != state) {
                boundsState = state;
                boundsX = boundsY = boundsZ = 0;
                boundsExtent = 1;
                if (!state.cells.isEmpty()) {
                    int[] bounds = state.regions.bounds();
                    double minX = (double) bounds[0] - state.data.getInteger("x"),
                        maxX = (double) bounds[3] - state.data.getInteger("x"),
                        minZ = (double) bounds[2] - state.data.getInteger("z"),
                        maxZ = (double) bounds[5] - state.data.getInteger("z");
                    boundsX = (minX + maxX) / 2;
                    boundsY = (state.minY + (double) state.maxY) / 2.0;
                    boundsZ = (minZ + maxZ) / 2;
                    boundsExtent = Math
                        .max(maxX - minX + 2, Math.max(maxZ - minZ + 2, (double) state.maxY - state.minY + 2));
                }
            }
            double cx = boundsX + panX, cy = boundsY + panY, cz = boundsZ + panZ, extent = boundsExtent;
            projectedFrame = new HologramEditorCameraFrame(x, y, width, height, yaw, pitch, zoom, cx, cy, cz, extent);
            int facing = 0;
            for (int side = 0; side < 6; side++) {
                int[] normal = NORMALS[side];
                if (-(normal[0] * projectedFrame.basis.forwardX() + normal[1] * projectedFrame.basis.forwardY()
                    + normal[2] * projectedFrame.basis.forwardZ()) > 1e-8) facing |= 1 << side;
            }
            buildStructureBoxes(state);
            for (PreviewRegion region : previewRegions.values()) {
                if (key.equals(region.projectedKey)) {
                    faces.addAll(region.faces);
                    outlines.addAll(region.outlines);
                    markers.addAll(region.markers);
                    nativePreviewCells.addAll(region.nativeCells);
                    continue;
                }
                int firstFace = faces.size(), firstOutline = outlines.size(), firstMarker = markers.size();
                int firstNative = nativePreviewCells.size();
                for (PreviewCell previewCell : region.cells) {
                    HologramState.Cell c = previewCell.cell;
                    int exposed = previewCell.exposed & facing;
                    String role = previewCell.role;
                    if (exposed == 0 && role.isEmpty()) continue;
                    double[][] projected = new double[8][];
                    for (int v = 0; v < 8; v++) {
                        projected[v] = projectedFrame
                            .project(c.dx + VERTICES[v][0], c.dy + VERTICES[v][1], c.dz + VERTICES[v][2]);
                    }
                    if (!HologramBlueprintPreview.cleanCapture() && !role.isEmpty()
                        && (c.anchor || markers.size() - firstMarker < 64))
                        markers.add(new RoleMarker(c, projected, role));
                    Face outline = new Face(c, projected, 0, currentView);
                    boolean renderedCurrent = currentView || c.anchor
                        || c.status.equals("protected") && c.actualStack != null;
                    outline.fadeDefault = role.isEmpty() && !c.sourceMissing
                        && (outline.nativeModel ? !HologramBlockRender.missingTexture(c, renderedCurrent)
                            : completePreviewMaterial(outline.material));
                    outlines.add(outline);
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
                region.projectedKey = key;
                region.faces = new ArrayList<>(faces.subList(firstFace, faces.size()));
                region.outlines = new ArrayList<>(outlines.subList(firstOutline, outlines.size()));
                region.markers = new ArrayList<>(markers.subList(firstMarker, markers.size()));
                region.nativeCells = new ArrayList<>(
                    nativePreviewCells.subList(firstNative, nativePreviewCells.size()));
                region.projectedCells = region.cells.size();
            }
            int shownMarkers = 0;
            java.util.Iterator<RoleMarker> markerIterator = markers.iterator();
            while (markerIterator.hasNext()) {
                RoleMarker marker = markerIterator.next();
                if (!marker.cell.anchor && shownMarkers >= 64) markerIterator.remove();
                else shownMarkers++;
            }
            faces.sort(Comparator.comparingDouble(f -> f.depth));
            markers.sort(Comparator.comparingInt(marker -> marker.cell.anchor ? 1 : 0));
            previewState = state;
            previewKey = key;
            cachedFaces = faces;
            invalidatePreviewDrawCache();
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
            double moveX = 0, moveY = 0;
            if ((moveOffset[0] != 0 || moveOffset[1] != 0 || moveOffset[2] != 0) && selection != null
                && selection.size() > 0) {
                double[] from = projectedFrame.projectScreen(0, 0, 0),
                    to = projectedFrame.projectScreen(moveOffset[0], moveOffset[1], moveOffset[2]);
                moveX = to[0] - from[0];
                moveY = to[1] - from[1];
            }
            boolean materialDrawn = false;
            boolean picking = (!budgeted || previewPreparation.projection.published == state.cells.size())
                && projectedFrame.coordinates.contains(mouseX, mouseY);
            // These GUI batches use color/UV only, with no brightness or normal flag enabled.
            // Restart at primitive boundaries before reapplying color/UV; translation stays unchanged.
            hovered = previewHovered(faces, mouseX, mouseY, picking);
            boolean displaced = (moveOffset[0] != 0 || moveOffset[1] != 0 || moveOffset[2] != 0) && selection != null
                && selection.size() > 0;
            // Angelica 2.2.28 also uses this vanilla GUI layout; its append-side extra state belongs to terrain
            // meshing.
            // DirectTessellator and drag transforms retain their original commands and active-format batch limit.
            if (!displaced && !previewCameraMoving && t.getClass() == Tessellator.class) {
                if (previewSubmission == null || previewSubmission.faces != faces
                    || previewSubmission.depthScale != depthScale)
                    previewSubmission = new PreviewSubmission(faces, depthScale);
                materialDrawn = previewSubmission.draw(t);
            } else {
                if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled()) previewSliceFallbackFrames++;
                materialDrawn = drawPreviewQuads(t, faces, depthScale, moveX, moveY);
            }
            HologramNativeRasterSmoke.Frame raster = HologramNativeRasterSmoke
                .begin(state, view == 1 || state.data.getInteger("mode") == 2);
            for (HologramState.Cell cell : nativePreviewCells) {
                HologramNativeRasterSmoke.Slice pixels = null;
                if (raster != null) for (Face outline : cachedOutlines) if (outline.cell == cell) {
                    pixels = raster.before(outline.points, depthScale);
                    break;
                }
                GL11.glPushMatrix();
                applyPreviewTransform(cell, depthScale);
                if (moving(cell)) GL11.glTranslated(moveOffset[0], moveOffset[1], moveOffset[2]);
                GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT);
                if (moving(cell)) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL14.glBlendColor(1, 1, 1, .38f);
                    GL11.glBlendFunc(0x8003, 0x8004);
                }
                HologramBlockRender.draw(cell, view == 1 || state.data.getInteger("mode") == 2);
                if (HologramBlockRender.vertexCount(
                    cell,
                    view == 1 || state.data.getInteger("mode") == 2
                        || cell.anchor
                        || cell.status.equals("protected") && cell.actualStack != null)
                    > 0) materialDrawn = true;
                GL11.glPopAttrib();
                GL11.glPopMatrix();
                if (pixels != null) raster.after(cell, pixels);
            }
            HologramNativeRasterSmoke.finish(raster);
            for (HologramState.Cell cell : linkedPreviewCells) {
                boolean current = view == 1 || state.data.getInteger("mode") == 2;
                if (HologramLinkNodePreview.prepare(cell, current)
                    .isEmpty()) continue;
                GL11.glPushMatrix();
                applyPreviewTransform(cell, depthScale);
                HologramLinkNodePreview.draw(cell, current, .96f);
                GL11.glPopMatrix();
            }
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            // Read face depth without writing line depth. The small z bias avoids coplanar
            // flicker while keeping back edges hidden. Dense ordinary grids yield to their materials.
            GL11.glDepthMask(false);
            if (budgeted && !previewMeshReady(state)) drawPreviewBoundary(state, false, depthScale);
            if (materialDrawn && budgeted && !previewPreparation.materialObserved) {
                previewPreparation.materialObserved = true;
                com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.loadStage(
                    com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.LoadStage.CLIENT_FIRST_MATERIAL,
                    state.data,
                    -1,
                    previewPreparation.projection.published);
            }
            float outlineDensity = HologramBlueprintPreview.cleanCapture() || cachedOutlines.isEmpty() ? 1
                : previewOutlineDensity();
            int lineVertices = 0, lineLimit = 0;
            t.startDrawing(GL11.GL_LINES);
            if (selection != null) selection.related(state);
            if (!HologramBlueprintPreview.cleanCapture()) for (Face face : cachedOutlines) {
                boolean chosen = face.cell.index == selected
                    || selection != null && selection.contains(face.cell.index);
                boolean relatedCasing = !chosen && selection != null
                    && selection.relatedContains(state, face.cell.index);
                float alpha = previewOutlineAlpha(chosen, relatedCasing, face.empty, face.fadeDefault, outlineDensity);
                if (alpha == 0) continue;
                if (lineLimit > 0 && lineVertices + 24 > lineLimit) {
                    t.draw();
                    t.startDrawing(GL11.GL_LINES);
                    lineVertices = 0;
                }
                t.setColorRGBA_F(
                    chosen ? 1 : relatedCasing ? .78f : .2f,
                    chosen ? .92f : relatedCasing ? .61f : .7f,
                    chosen ? .5f : relatedCasing ? 1 : .8f,
                    alpha);
                for (int[] edge : EDGES) {
                    double[] a = face.points[edge[0]], b = face.points[edge[1]];
                    t.addVertex(a[0], a[1], a[2] * depthScale + .15);
                    t.addVertex(b[0], b[1], b[2] * depthScale + .15);
                }
                if (lineLimit == 0) lineLimit = previewBatchLimit(t);
                lineVertices += 24;
            }
            t.draw();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glLineWidth(2.5f);
            lineVertices = 0;
            t.startDrawing(GL11.GL_LINES);
            String hoveredStructure = pickStructure(mouseX, mouseY);
            if (!HologramBlueprintPreview.cleanCapture())
                for (Map.Entry<String, double[][]> entry : structureBoxes.entrySet()) {
                    if (lineLimit > 0 && lineVertices + 24 > lineLimit) {
                        t.draw();
                        t.startDrawing(GL11.GL_LINES);
                        lineVertices = 0;
                    }
                    if (entry.getKey()
                        .equals(hoveredStructure)) t.setColorRGBA_F(1, 1, .8f, 1);
                    else if (entry.getKey()
                        .equals(selectedStructureId)) t.setColorRGBA_F(1, .78f, .3f, .95f);
                    else if (missingStructures.contains(entry.getKey())) t.setColorRGBA_F(.65f, .65f, .65f, .72f);
                    else if (com.miaokatze.gtmb.hologram.HologramTextBox.isAttachmentId(entry.getKey()))
                        t.setColorRGBA_F(.78f, .52f, 1, .85f);
                    else t.setColorRGBA_F(.45f, .85f, 1, .72f);
                    for (int[] edge : EDGES) {
                        double[] a = entry.getValue()[edge[0]], b = entry.getValue()[edge[1]];
                        t.addVertex(a[0], a[1], 1);
                        t.addVertex(b[0], b[1], 1);
                    }
                    if (lineLimit == 0) lineLimit = previewBatchLimit(t);
                    lineVertices += 24;
                }
            t.draw();
            GL11.glLineWidth(1.5f);
            lineVertices = 0;
            t.startDrawing(GL11.GL_LINES);
            if (!HologramBlueprintPreview.cleanCapture()) for (double[][] box : hintBoxes.values()) {
                if (lineLimit > 0 && lineVertices + 24 > lineLimit) {
                    t.draw();
                    t.startDrawing(GL11.GL_LINES);
                    lineVertices = 0;
                }
                t.setColorRGBA_F(.95f, .77f, .39f, .48f);
                for (int[] edge : EDGES) {
                    double[] a = box[edge[0]], b = box[edge[1]];
                    t.addVertex(a[0], a[1], 1);
                    t.addVertex(b[0], b[1], 1);
                }
                if (lineLimit == 0) lineLimit = previewBatchLimit(t);
                lineVertices += 24;
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
                if (picking && mouseX >= lx - 2
                    && mouseX < lx + labelWidth + 2
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
        presentedFrame = projectedFrame;
        presentedState = state;
        presentedRequest = projectedRequest;
        displayedState = !cachedFaces.isEmpty() || !cachedOutlines.isEmpty()
            || !structureBoxes.isEmpty()
            || previewPreparation != null && previewPreparation.boundary != null
                && previewPreparation.boundary.presented ? state : null;
        previewSubmitted = !budgeted || previewPreparation.projection.published == state.cells.size();
        if (budgeted && previewSubmitted) previewPreparation.completeGeometryPresented = true;
        if (budgeted && previewSubmitted && !previewPreparation.readyObserved) {
            previewPreparation.readyObserved = true;
            com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.loadStage(
                com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.LoadStage.CLIENT_RENDER_READY,
                state.data,
                -1,
                previewPreparation.projection.published);
        }
        return hovered;
    }

    /** A source-switch wait reuses only the already projected occupancy boundary, never the old native/face loops. */
    static void waitingPreview(HologramState state) {
        presentedState = null;
        presentedFrame = null;
        presentedRequest = null;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            displayedState = drawPreviewBoundary(state, true, 128 / cachedExtent) > 0 ? state : null;
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static int drawPreviewBoundary(HologramState state, boolean waiting, double depthScale) {
        if (previewPreparation == null || previewPreparation.state != state || previewPreparation.boundary == null)
            return 0;
        PreviewBoundary boundary = previewPreparation.boundary;
        // A completed camera-only projection may deliberately leave the unused light outline at an older pose.
        if (!boundary.projectedKey.equals(previewKey)) return 0;
        Tessellator tess = Tessellator.instance;
        int vertices = 0, total = 0, limit = 0;
        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA_F(.30f, .65f, .72f, .6f);
        for (int i = 0; i < boundary.projected; i++) {
            BoundaryCell cell = boundary.cells.get(i);
            if (!waiting && publishedPreviewCells.get(cell.cell.index)) continue;
            for (int edge = 0; edge < EDGES.length; edge++) {
                if ((cell.mask & 1 << edge) == 0) continue;
                if (limit > 0 && vertices + 2 > limit) {
                    tess.draw();
                    tess.startDrawing(GL11.GL_LINES);
                    tess.setColorRGBA_F(.30f, .65f, .72f, .6f);
                    vertices = 0;
                }
                double[] a = cell.points[EDGES[edge][0]], b = cell.points[EDGES[edge][1]];
                tess.addVertex(a[0], a[1], a[2] * depthScale + .15);
                tess.addVertex(b[0], b[1], b[2] * depthScale + .15);
                if (limit == 0) limit = previewBatchLimit(tess);
                vertices += 2;
                total += 2;
            }
        }
        tess.draw();
        if (total > 0) {
            boundary.presented = true;
            if (!waiting && !previewPreparation.outlineObserved) {
                previewPreparation.outlineObserved = true;
                com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.loadStage(
                    com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.LoadStage.CLIENT_OUTLINE_READY,
                    state.data,
                    -1,
                    boundary.projected);
            }
        }
        return total;
    }

    private static void prepareProgressiveFrame(HologramState state, String key, int x, int y, int width, int height,
        double yaw, double pitch, double zoom, double panX, double panY, double panZ) {
        if (previewPreparation.projection != null && key.equals(previewPreparation.projection.key)) return;
        if (boundsState != state) {
            boundsState = state;
            boundsX = boundsY = boundsZ = 0;
            boundsExtent = 1;
            if (!state.cells.isEmpty()) {
                int[] bounds = state.regions.bounds();
                double minX = (double) bounds[0] - state.data.getInteger("x"),
                    maxX = (double) bounds[3] - state.data.getInteger("x"),
                    minZ = (double) bounds[2] - state.data.getInteger("z"),
                    maxZ = (double) bounds[5] - state.data.getInteger("z");
                boundsX = (minX + maxX) / 2;
                boundsY = (state.minY + (double) state.maxY) / 2;
                boundsZ = (minZ + maxZ) / 2;
                boundsExtent = Math
                    .max(maxX - minX + 2, Math.max(maxZ - minZ + 2, (double) state.maxY - state.minY + 2));
            }
        }
        projectedFrame = new HologramEditorCameraFrame(
            x,
            y,
            width,
            height,
            yaw,
            pitch,
            zoom,
            boundsX + panX,
            boundsY + panY,
            boundsZ + panZ,
            boundsExtent);
        cachedExtent = boundsExtent;
        previewKey = key;
        previewState = state;
        cachedFaces = new ArrayList<>();
        invalidatePreviewDrawCache();
        cachedOutlines = new ArrayList<>();
        cachedMarkers = new ArrayList<>();
        nativePreviewCells.clear();
        linkedPreviewCells.clear();
        visibleCells.clear();
        publishedPreviewCells.clear();
        previewSubmitted = false;
        presentedState = null;
        presentedFrame = null;
        buildStructureBoxes(state);
        previewPreparation.projection = new PreviewProjection(state, key);
    }

    private static void projectPreviewCell(PreviewCell previewCell, PreviewRegion region, boolean current) {
        HologramState.Cell c = previewCell.cell;
        int exposed = previewCell.exposed;
        for (int side = 0; side < 6; side++) {
            int[] normal = NORMALS[side];
            if (-(normal[0] * projectedFrame.basis.forwardX() + normal[1] * projectedFrame.basis.forwardY()
                + normal[2] * projectedFrame.basis.forwardZ()) <= 1e-8) exposed &= ~(1 << side);
        }
        String role = previewCell.role;
        if (exposed == 0 && role.isEmpty()) return;
        double[][] projected = new double[8][];
        for (int v = 0; v < 8; v++)
            projected[v] = projectedFrame.project(c.dx + VERTICES[v][0], c.dy + VERTICES[v][1], c.dz + VERTICES[v][2]);
        if (!role.isEmpty() && (c.anchor || region.markers.size() < 64))
            region.markers.add(new RoleMarker(c, projected, role));
        Face outline = new Face(c, projected, 0, current);
        boolean renderedCurrent = current || c.anchor || c.status.equals("protected") && c.actualStack != null;
        outline.fadeDefault = role.isEmpty() && !c.sourceMissing
            && (outline.nativeModel ? !HologramBlockRender.missingTexture(c, renderedCurrent)
                : completePreviewMaterial(outline.material));
        region.outlines.add(outline);
        if (outline.nativeModel) {
            region.nativeCells.add(c);
            for (int side = 0; side < 6; side++)
                if ((exposed & 1 << side) != 0) region.faces.add(new Face(c, projected, side, current));
            return;
        }
        HologramTextures.Material material = outline.material;
        if (outline.empty) {
            for (int side = 0; side < 6; side++)
                if ((exposed & 1 << side) != 0) region.faces.add(new Face(c, projected, side, current));
            return;
        }
        for (HologramGeometry.Surface surface : material.geometry) {
            if ((exposed & 1 << surface.side) == 0) continue;
            double[][] points = projected;
            if (material.partial) {
                points = new double[8][3];
                for (int v = 0; v < 8; v++) {
                    double[] local = surface.vertices[v];
                    for (int axis = 0; axis < 3; axis++) points[v][axis] = projected[0][axis]
                        + (local[0] + .5) * (projected[1][axis] - projected[0][axis])
                        + (local[1] + .5) * (projected[3][axis] - projected[0][axis])
                        + (local[2] + .5) * (projected[4][axis] - projected[0][axis]);
                }
            }
            region.faces.add(new Face(c, points, surface.side, current, surface));
        }
    }

    /** Native blocks and linked TE previews consume the exact camera used by face projection and picking. */
    private static void applyPreviewTransform(HologramState.Cell cell, double depthScale) {
        HologramEditorCameraFrame frame = projectedFrame;
        GL11.glTranslated(frame.coordinates.screenX(0), frame.coordinates.screenY(0), 0);
        GL11.glScaled(frame.scale, -frame.scale, depthScale);
        GL11.glRotated(frame.pose.pitchDegrees(), 1, 0, 0);
        GL11.glRotated(frame.pose.yawDegrees(), 0, 1, 0);
        GL11.glTranslated(
            cell.dx - frame.pose.anchorX() - .5,
            cell.dy - frame.pose.anchorY() - .5,
            cell.dz - frame.pose.anchorZ() - .5);
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
        return presentedFrame == null ? new double[] { 0, 0 } : presentedFrame.projectScreen(dx, dy, dz);
    }

    static double gridScale() {
        return presentedFrame == null ? 0 : presentedFrame.scale;
    }

    /** Use the same visible faces as selection, including the camera-facing placement side. */
    static int[] pickAdjacentVoxel(int x, int y) {
        Face hit = null;
        for (Face face : cachedFaces) if (!face.empty && face.contains(x, y)) hit = face;
        if (hit == null) return null;
        int[] normal = NORMALS[hit.side];
        return new int[] { hit.cell.x + normal[0], hit.cell.y + normal[1], hit.cell.z + normal[2] };
    }

    /** Intersect the orthographic view ray with a horizontal plane at an absolute world Y. */
    static int[] pickEmptyVoxel(HologramState state, int mx, int my, int planeY) {
        return pickEmptyVoxel(state, mx, my, planeY, true);
    }

    static int[] pickEmptyVoxel(HologramState state, int mx, int my, int planeY, boolean adjacentOnly) {
        if (state == null || state != presentedState || presentedFrame == null) return null;
        double[] hit = presentedFrame.horizontalPlane(mx, my, (double) planeY - state.data.getInteger("y"));
        if (hit == null) return null;
        int x = state.data.getInteger("x") + (int) Math.floor(hit[0] + .5);
        int z = state.data.getInteger("z") + (int) Math.floor(hit[2] + .5);
        HologramState.Cell occupied = state.regions.at(x, planeY, z);
        if (occupied != null && !occupied.wantId.equals("minecraft:air")) return null;
        return !adjacentOnly || state.regions.withinMargin(x, planeY, z) ? new int[] { x, planeY, z } : null;
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
            for (int i = 0; i < 8; i++) box[i] = projectedFrame.projectScreen(
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
            for (int v = 0; v < 8; v++) box[v] = projectedFrame.projectScreen(
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
        if (presentedState == null || presentedFrame == null) return -1;
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

    private static boolean previewVisible(HologramState.Cell c, int layer, int view, boolean current) {
        if (c.status.equals("removed") || view == 2 && (c.completed() || c.status.equals("satisfied"))) return false;
        boolean empty = !c.anchor && !c.iconOnly && (current ? c.id : c.wantId).equals("minecraft:air");
        return (!empty || showEmptyCells) && (c.anchor || c.dy == layer || layer == Integer.MAX_VALUE);
    }

    private static PreviewRegion previewRegion(HologramState state, List<HologramState.Cell> cells, int layer, int view,
        boolean current) {
        PreviewRegion result = new PreviewRegion();
        for (HologramState.Cell c : cells) {
            previewCell(state, result, c, layer, view, current);
            result.prepared++;
        }
        result.complete = true;
        return result;
    }

    private static void previewCell(HologramState state, PreviewRegion result, HologramState.Cell c, int layer,
        int view, boolean current) {
        if (!previewVisible(c, layer, view, current)) return;
        result.visible.add(c);
        if (!HologramLinkNodePreview.prepare(c, current)
            .isEmpty()) result.linked.add(c);
        int exposed = 0;
        for (int side = 0; side < 6; side++) {
            int[] n = NORMALS[side];
            HologramState.Cell neighbor = state.regions.at(c.x + n[0], c.y + n[1], c.z + n[2]);
            if (neighbor == null || !previewVisible(neighbor, layer, view, current) || !occludes(neighbor, current))
                exposed |= 1 << side;
        }
        boolean empty = !c.anchor && !c.iconOnly && (current ? c.id : c.wantId).equals("minecraft:air");
        String role = roleLabel(c);
        boolean nativeModel = !empty && HologramBlockRender.ready(c, current);
        HologramTextures.Material material = empty ? null : HologramTextures.material(c, current);
        if (nativeModel || !empty && material.partial) exposed = 63;
        if (c.missingBlock) role = "缺失方块";
        else if (c.sourceMissing && c.anchor) role = "来源结构缺失";
        if (!nativeModel && !empty && role.isEmpty() && material.fallback) role = "材质缺失";
        if (!nativeModel && !empty && HologramNativeTiles.supported(c.block(current || c.anchor))) role = "模型缺失";
        if (!empty && role.isEmpty() && c.iconOnly) role = "占位";
        if (!c.prompt.isEmpty()) role = role.isEmpty() ? "提示" : role + " · 提示";
        if (exposed != 0 || !role.isEmpty()) result.cells.add(new PreviewCell(c, exposed, role, result.prepared + 1));
    }

    private static boolean preparedCameraOnly(HologramState state, String key, int layer, int view, boolean empty) {
        return state != HologramLibraryClient.INSTANCE.browsePreviewState() && previewPreparation != null
            && previewPreparation.state == state
            && previewPreparation.completeGeometryPresented
            && previewPreparation.completed == state.cells.size()
            && previewPreparation.projection != null
            && previewPreparation.projection.published == state.cells.size()
            && projectedRequest != null
            && projectedRequest.sameGeometry(state, layer, view, empty, false)
            && !key.equals(previewKey);
    }

    /**
     * Immutable input of one projected/presented frame. UI admission reads scalars, never builds another string key.
     */
    private static final class PreviewRequest {

        final HologramState state;
        final int x, y, width, height, layer, view;
        final double yaw, pitch, zoom, panX, panY, panZ;
        final boolean empty, clean;

        PreviewRequest(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
            double panX, double panY, double panZ, int layer, int view, boolean empty, boolean clean) {
            this.state = state;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.yaw = yaw;
            this.pitch = pitch;
            this.zoom = zoom;
            this.panX = panX;
            this.panY = panY;
            this.panZ = panZ;
            this.layer = layer;
            this.view = view;
            this.empty = empty;
            this.clean = clean;
        }

        boolean sameGeometry(HologramState state, int layer, int view, boolean empty, boolean clean) {
            return this.state == state && this.layer == layer
                && this.view == view
                && this.empty == empty
                && this.clean == clean;
        }

        boolean sameCamera(int x, int y, int width, int height, double yaw, double pitch, double zoom, double panX,
            double panY, double panZ) {
            return this.x == x && this.y == y
                && this.width == width
                && this.height == height
                && this.yaw == yaw
                && this.pitch == pitch
                && this.zoom == zoom
                && this.panX == panX
                && this.panY == panY
                && this.panZ == panZ;
        }

        boolean matches(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
            double panX, double panY, double panZ, int layer, int view, boolean empty, boolean clean) {
            return sameGeometry(state, layer, view, empty, clean)
                && sameCamera(x, y, width, height, yaw, pitch, zoom, panX, panY, panZ);
        }
    }

    /** Client render-thread cursor: each cell invokes the existing native/texture path exactly once. */
    private static final class PreviewPreparation {

        final HologramState state;
        final java.util.Iterator<Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>>> regions;
        Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry;
        PreviewRegion region;
        int cell, completed;
        final PreviewBoundary boundary;
        PreviewProjection projection;
        boolean outlineObserved, materialObserved, readyObserved, completeGeometryPresented;

        PreviewPreparation(HologramState state, boolean outline) {
            this.state = state;
            regions = state.regions.regions.entrySet()
                .iterator();
            boundary = outline ? new PreviewBoundary(state) : null;
        }

        void advance(int layer, int view, boolean current, String key) {
            long start = System.nanoTime();
            if (boundary != null) {
                boundary.advance(layer, view, current, Integer.MAX_VALUE, start + PREVIEW_BUILD_NANOS);
                boundary.project(key, projectedFrame, Integer.MAX_VALUE, start + PREVIEW_BUILD_NANOS);
                if (!boundary.presented && (!boundary.complete() || !boundary.cells.isEmpty())) return;
                start = System.nanoTime();
            }
            int work = 0;
            while (completed < state.cells.size() && (work == 0 || System.nanoTime() - start < PREVIEW_BUILD_NANOS)) {
                if (entry == null || cell == entry.getValue()
                    .size()) {
                    if (region != null) region.complete = true;
                    if (!regions.hasNext()) break;
                    entry = regions.next();
                    cell = 0;
                    region = previewRegions.get(entry.getKey());
                    if (region != null && region.complete) {
                        completed += entry.getValue()
                            .size();
                        cell = entry.getValue()
                            .size();
                        continue;
                    }
                    region = new PreviewRegion();
                    previewRegions.put(entry.getKey(), region);
                    lastPreviewBuiltRegions++;
                }
                previewCell(
                    state,
                    region,
                    entry.getValue()
                        .get(cell++),
                    layer,
                    view,
                    current);
                region.prepared++;
                completed++;
                work++;
            }
            if (region != null && cell == entry.getValue()
                .size()) region.complete = true;
            projection.advance(current, Integer.MAX_VALUE, System.nanoTime() + PREVIEW_BUILD_NANOS);
            projection.publish();
        }
    }

    /** Projects only newly prepared cells; camera changes reuse native/material preparation and restart this cursor. */
    private static final class PreviewProjection {

        final HologramState state;
        final String key;
        final java.util.Iterator<Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>>> regions;
        Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry;
        PreviewRegion region;
        int counted, completed, published;
        final List<Face> faces = new ArrayList<>(), outlines = new ArrayList<>();
        final List<RoleMarker> markers = new ArrayList<>();
        final List<HologramState.Cell> nativeCells = new ArrayList<>(), linked = new ArrayList<>(),
            visible = new ArrayList<>();
        final java.util.BitSet cells = new java.util.BitSet();

        PreviewProjection(HologramState state, String key) {
            this.state = state;
            this.key = key;
            regions = state.regions.regions.entrySet()
                .iterator();
        }

        void advance(boolean current, int maximum, long deadline) {
            int work = 0;
            while (work < maximum && (work == 0 || System.nanoTime() < deadline)) {
                if (entry == null) {
                    if (!regions.hasNext()) return;
                    entry = regions.next();
                    counted = 0;
                    region = null;
                }
                if (region == null) {
                    region = previewRegions.get(entry.getKey());
                    if (region == null) return;
                    if (key.equals(region.projectedKey)) {
                        faces.addAll(region.faces);
                        outlines.addAll(region.outlines);
                        markers.addAll(region.markers);
                        nativeCells.addAll(region.nativeCells);
                    } else {
                        region.projectedKey = key;
                        region.projectedCells = 0;
                        region.faces.clear();
                        region.outlines.clear();
                        region.markers.clear();
                        region.nativeCells.clear();
                    }
                }
                if (region.projectedCells < region.cells.size()) {
                    PreviewCell input = region.cells.get(region.projectedCells++);
                    int firstFace = region.faces.size(), firstOutline = region.outlines.size(),
                        firstMarker = region.markers.size(), firstNative = region.nativeCells.size();
                    projectPreviewCell(input, region, current);
                    for (int i = firstFace; i < region.faces.size(); i++) faces.add(region.faces.get(i));
                    for (int i = firstOutline; i < region.outlines.size(); i++) outlines.add(region.outlines.get(i));
                    for (int i = firstMarker; i < region.markers.size(); i++) markers.add(region.markers.get(i));
                    for (int i = firstNative; i < region.nativeCells.size(); i++)
                        nativeCells.add(region.nativeCells.get(i));
                    cells.set(input.cell.index);
                    completed += input.sourceOrdinal - counted;
                    counted = input.sourceOrdinal;
                    work++;
                } else {
                    completed += region.prepared - counted;
                    counted = region.prepared;
                    if (!region.complete) return;
                    visible.addAll(region.visible);
                    linked.addAll(region.linked);
                    if (previewPreparation.boundary != null)
                        for (HologramState.Cell cell : entry.getValue()) cells.set(cell.index);
                    entry = null;
                    work++;
                }
            }
        }

        void publish() {
            if (completed <= published
                || published != 0 && completed < 2L * published && completed != state.cells.size()) return;
            // Geometric milestones bound total merge/copy work; old projected geometry is never recreated here.
            faces.sort(Comparator.comparingDouble(face -> face.depth));
            List<Face> merged = new ArrayList<>(cachedFaces.size() + faces.size());
            int old = 0, added = 0;
            while (old < cachedFaces.size() && added < faces.size()) {
                if (cachedFaces.get(old).depth <= faces.get(added).depth) merged.add(cachedFaces.get(old++));
                else merged.add(faces.get(added++));
            }
            while (old < cachedFaces.size()) merged.add(cachedFaces.get(old++));
            while (added < faces.size()) merged.add(faces.get(added++));
            cachedFaces = merged;
            invalidatePreviewDrawCache();
            cachedOutlines.addAll(outlines);
            nativePreviewCells.addAll(nativeCells);
            linkedPreviewCells.addAll(linked);
            visibleCells.addAll(visible);
            int shownMarkers = cachedMarkers.size();
            for (RoleMarker marker : markers) if (marker.cell.anchor || shownMarkers < 64) {
                cachedMarkers.add(marker);
                shownMarkers++;
            }
            cachedMarkers.sort(Comparator.comparingInt(marker -> marker.cell.anchor ? 1 : 0));
            publishedPreviewCells.or(cells);
            faces.clear();
            outlines.clear();
            nativeCells.clear();
            markers.clear();
            linked.clear();
            visible.clear();
            cells.clear();
            previewState = state;
            published = completed;
        }
    }

    /** Pure voxel occupancy; no registry, textures, native tile factory or GL is queried. */
    static final class PreviewBoundary {

        final HologramState state;
        final Map<HologramRegionCache.Key, java.util.BitSet> occupancy = new java.util.HashMap<>();
        java.util.Iterator<Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>>> regions;
        Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry;
        java.util.BitSet bits;
        final java.util.BitSet[] neighbors = new java.util.BitSet[27];
        final HologramSelectionOutline.Occupancy contains = this::contains;
        final List<BoundaryCell> cells = new ArrayList<>();
        int stage, cell, projected;
        boolean presented;
        String projectedKey = "";

        PreviewBoundary(HologramState state) {
            this.state = state;
            regions = state.regions.regions.entrySet()
                .iterator();
        }

        boolean complete() {
            return stage == 2;
        }

        void advance(int layer, int view, boolean current, int maximum, long deadline) {
            for (int work = 0; stage < 2 && work < maximum && (work == 0 || System.nanoTime() < deadline); work++) {
                if (entry == null || cell == entry.getValue()
                    .size()) {
                    if (!regions.hasNext()) {
                        stage++;
                        regions = state.regions.regions.entrySet()
                            .iterator();
                        entry = null;
                        continue;
                    }
                    entry = regions.next();
                    cell = 0;
                    if (stage == 0) {
                        bits = new java.util.BitSet();
                        occupancy.put(entry.getKey(), bits);
                    } else {
                        bits = occupancy.get(entry.getKey());
                        HologramRegionCache.Key key = entry.getKey();
                        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++)
                            for (int z = -1; z <= 1; z++) neighbors[(x + 1) * 9 + (y + 1) * 3 + z + 1] = occupancy
                                .get(new HologramRegionCache.Key(key.x + x, key.y + y, key.z + z));
                    }
                }
                HologramState.Cell c = entry.getValue()
                    .get(cell++);
                int index = local(c.x, c.y, c.z);
                if (stage == 0) {
                    if (previewVisible(c, layer, view, current)
                        && (c.iconOnly || !(current ? c.id : c.wantId).equals("minecraft:air"))) bits.set(index);
                } else if (bits.get(index)) {
                    int mask = boundaryEdges(c.x, c.y, c.z, contains);
                    if (mask != 0) cells.add(new BoundaryCell(c, mask));
                }
            }
        }

        private boolean contains(int x, int y, int z) {
            HologramRegionCache.Key key = entry.getKey();
            int rx = (x >> 4) - key.x + 1, ry = (y >> 4) - key.y + 1, rz = (z >> 4) - key.z + 1;
            java.util.BitSet neighbor = neighbors[rx * 9 + ry * 3 + rz];
            return neighbor != null && neighbor.get(local(x, y, z));
        }

        private static int local(int x, int y, int z) {
            return ((x & 15) << 8) | ((y & 15) << 4) | (z & 15);
        }

        void project(String key, HologramEditorCameraFrame frame, int maximum, long deadline) {
            if (!key.equals(projectedKey)) {
                projectedKey = key;
                projected = 0;
            }
            for (int work = 0; projected < cells.size() && work < maximum
                && (work == 0 || System.nanoTime() < deadline); work++) {
                BoundaryCell boundary = cells.get(projected++);
                boundary.points = new double[8][];
                for (int v = 0; v < 8; v++) boundary.points[v] = frame.project(
                    boundary.cell.dx + VERTICES[v][0],
                    boundary.cell.dy + VERTICES[v][1],
                    boundary.cell.dz + VERTICES[v][2]);
            }
        }
    }

    static int boundaryEdges(int cellX, int cellY, int cellZ, HologramSelectionOutline.Occupancy cells) {
        int result = 0;
        for (int edge = 0; edge < EDGES.length; edge++) {
            double[] a = VERTICES[EDGES[edge][0]], b = VERTICES[EDGES[edge][1]];
            int axis = a[0] != b[0] ? 0 : a[1] != b[1] ? 1 : 2;
            int x = cellX + (int) (Math.min(a[0], b[0]) + .5), y = cellY + (int) (Math.min(a[1], b[1]) + .5),
                z = cellZ + (int) (Math.min(a[2], b[2]) + .5);
            if (HologramSelectionOutline.ownsEdge(cellX, cellY, cellZ, x, y, z, axis, cells)) result |= 1 << edge;
        }
        return result;
    }

    private static final class BoundaryCell {

        final HologramState.Cell cell;
        final int mask;
        double[][] points;

        BoundaryCell(HologramState.Cell cell, int mask) {
            this.cell = cell;
            this.mask = mask;
        }
    }

    private static final class PreviewRegion {

        final List<HologramState.Cell> visible = new ArrayList<>(), linked = new ArrayList<>();
        final List<PreviewCell> cells = new ArrayList<>();
        String projectedKey = "";
        List<Face> faces = new ArrayList<>(), outlines = new ArrayList<>();
        List<RoleMarker> markers = new ArrayList<>();
        List<HologramState.Cell> nativeCells = new ArrayList<>();
        int prepared, projectedCells;
        boolean complete;
    }

    private static final class PreviewCell {

        final HologramState.Cell cell;
        final int exposed;
        final String role;
        final int sourceOrdinal;

        PreviewCell(HologramState.Cell cell, int exposed, String role, int sourceOrdinal) {
            this.cell = cell;
            this.exposed = exposed;
            this.role = role;
            this.sourceOrdinal = sourceOrdinal;
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
        boolean witness = Boolean.getBoolean("gtmb.hologram.realInteractionSmoke")
            || Boolean.getBoolean("gtmb.hologram.followupAreaClient");
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
        if (state.data.getBoolean("virtual") && state.data.getBoolean("moving") && anchor == null) return;
        double offsetX = anchor == null ? 0 : anchor[0] - state.data.getInteger("x");
        double offsetY = anchor == null ? 0 : anchor[1] - state.data.getInteger("y");
        double offsetZ = anchor == null ? 0 : anchor[2] - state.data.getInteger("z");
        syncPlacementColor(state);
        Frustrum frustum = new Frustrum();
        frustum.setPosition(px, py, pz);
        double range = Math.max(16, mc.gameSettings.renderDistanceChunks * 16);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        int drawnLists = 0;
        try {
            GL11.glTranslated(-px, -py, -pz);
            GL11.glTranslated(offsetX, offsetY, offsetZ);
            if (state.data.getBoolean("localPreview")) HologramLocalPlacement.INSTANCE.rotateGl();
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
                double ex = gx + 16, ey = gy + 16, ez = gz + 16;
                if (state.data.getBoolean("localPreview")) {
                    double[] bounds = HologramLocalPlacement.INSTANCE.bounds(group.x, group.y, group.z);
                    gx = bounds[0] + offsetX;
                    gy = bounds[1] + offsetY;
                    gz = bounds[2] + offsetZ;
                    ex = bounds[3] + offsetX;
                    ey = bounds[4] + offsetY;
                    ez = bounds[5] + offsetZ;
                }
                double dx = Math.max(gx - px, Math.max(0, px - ex));
                double dy = Math.max(gy - py, Math.max(0, py - ey));
                double dz = Math.max(gz - pz, Math.max(0, pz - ez));
                if (dx * dx + dy * dy + dz * dz > range * range
                    || !frustum.isBoundingBoxInFrustum(AxisAlignedBB.getBoundingBox(gx, gy, gz, ex, ey, ez))) continue;
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
        lastWorldGeometry = lastWorldGTGeometry = lastWorldFallback = lastWorldMatched = lastWorldGhosts = 0;
        lastWorldPlacementOutlineColor = 0;
        for (WorldGroup group : worldGroups.values()) for (int list : group.lists) GL11.glDeleteLists(list, 1);
        if (placementColorList != 0) GL11.glDeleteLists(placementColorList, 1);
        placementColorList = placementColor = 0;
        worldGroups.clear();
        worldGhosts.clear();
        worldOccluders.clear();
        worldOutlineCells.clear();
        worldIndex = 0;
    }

    /** Cached geometry calls this tiny list, so a local lock changes color without rebuilding the blueprint. */
    private static void syncPlacementColor(HologramState state) {
        if (!state.data.getBoolean("virtualPlacement") || state.data.getInteger("mode") == 2) return;
        int color = placementOutlineColor(state);
        lastWorldPlacementOutlineColor = color;
        if (placementColorList == 0) placementColorList = GL11.glGenLists(1);
        if (placementColorList == 0 || placementColor == color) return;
        placementColor = color;
        GL11.glNewList(placementColorList, GL11.GL_COMPILE);
        GL11.glColor4f(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, .65f);
        GL11.glEndList();
    }

    static int placementOutlineColor(HologramState state) {
        return HologramClient.positionLocked(state) ? 0x66d9ef : 0x70e69a;
    }

    /** Rebuild only affected cached groups; visual receipts never replace snapshot cells. */
    static void jobVisualChanged(HologramState state, int[] indices) {
        if (worldState != state || indices.length == 0) return;
        Set<Coordinate> touched = new HashSet<>();
        for (int index : indices) {
            HologramState.Cell cell = state.cells.get(index);
            int gx = cell.x >> 4, gy = cell.y >> 4, gz = cell.z >> 4;
            touched.add(coordinate(gx, gy, gz));
            if ((cell.x & 15) == 0) touched.add(coordinate(gx - 1, gy, gz));
            if ((cell.x & 15) == 15) touched.add(coordinate(gx + 1, gy, gz));
            if ((cell.y & 15) == 0) touched.add(coordinate(gx, gy - 1, gz));
            if ((cell.y & 15) == 15) touched.add(coordinate(gx, gy + 1, gz));
            if ((cell.z & 15) == 0) touched.add(coordinate(gx, gy, gz - 1));
            if ((cell.z & 15) == 15) touched.add(coordinate(gx, gy, gz + 1));
            if (HologramJobVisuals.completed(state, cell)) {
                Coordinate position = coordinate(cell.x, cell.y, cell.z);
                worldGhosts.remove(position);
                worldOccluders.remove(position);
                worldOutlineCells.remove(position);
            }
        }
        for (Coordinate key : touched) {
            WorldGroup group = worldGroups.get(key);
            if (group == null) continue;
            invalidateWorldGroup(group);
        }
    }

    private static void invalidateWorldGroup(WorldGroup group) {
        for (int list : group.lists) GL11.glDeleteLists(list, 1);
        group.lists.clear();
        group.built = 0;
        lastWorldGeometry -= group.geometry;
        lastWorldGTGeometry -= group.gt;
        lastWorldFallback -= group.fallback;
        lastWorldGhosts -= group.ghosts;
        group.geometry = group.gt = group.fallback = group.ghosts = 0;
    }

    /** Group lists refer to native lists, so discard them before releasing their native snapshot. */
    static void nativeSnapshotChanged() {
        clearWorldGroups();
        worldState = null;
        previewState = null;
        visibleState = null;
    }

    private static void indexWorld(HologramState state, long deadline) {
        while (worldIndex < state.cells.size() && System.nanoTime() < deadline)
            indexWorldCell(state, state.cells.get(worldIndex++));
    }

    private static void indexWorldCell(HologramState state, HologramState.Cell c) {
        boolean removal = state.data.getInteger("mode") == 2;
        boolean virtual = state.data.getBoolean("virtual");
        if (HologramJobVisuals.completed(state, c)) return;
        if (!c.anchor && worldLayer != Integer.MAX_VALUE && c.dy != worldLayer) return;
        if (!c.anchor && c.id.equals("minecraft:air")
            && c.wantId.equals("minecraft:air")
            && !c.role.contains("interface")) return;
        Coordinate key = coordinate(c.x >> 4, c.y >> 4, c.z >> 4);
        WorldGroup group = worldGroups.get(key);
        if (group == null) {
            group = new WorldGroup(c.x >> 4 << 4, c.y >> 4 << 4, c.z >> 4 << 4);
            worldGroups.put(key, group);
        }
        group.cells.add(c);
        worldOutlineCells.add(coordinate(c.x, c.y, c.z));
        if ((!c.anchor || virtual) && !removal
            && (virtual || !c.status.equals("satisfied") && !worldMatched(c))
            && !c.wantId.equals("minecraft:air")) {
            Coordinate position = coordinate(c.x, c.y, c.z);
            worldGhosts.add(position);
            if (occludes(c, false)) worldOccluders.add(position);
        } else if (!c.anchor && !removal) {
            lastWorldMatched++;
            group.matchedIndices.add(c.index);
        }
    }

    private static boolean compileWorldGroup(WorldGroup group, boolean removal) {
        int list = GL11.glGenLists(1);
        if (list == 0) return false;
        int beforeGeometry = lastWorldGeometry, beforeGT = lastWorldGTGeometry, beforeFallback = lastWorldFallback,
            beforeGhosts = lastWorldGhosts;
        int end = Math.min(group.built + WORLD_MESH_BATCH, group.cells.size());
        List<HologramState.Cell> cells = new ArrayList<>();
        for (HologramState.Cell cell : group.cells.subList(group.built, end))
            if (!HologramJobVisuals.completed(worldState, cell)) cells.add(cell);
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
        boolean dynamicPlacementColor = worldState != null && worldState.data.getBoolean("virtualPlacement")
            && !removal
            && placementColorList != 0;
        if (dynamicPlacementColor) GL11.glCallList(placementColorList);
        t.startDrawing(GL11.GL_LINES);
        if (dynamicPlacementColor) t.disableColor();
        for (HologramState.Cell c : cells) {
            int color = c.anchor ? 0xffffbd : HologramJobVisuals.color(worldState, c);
            if (worldState != null && worldState.data.getBoolean("virtualPlacement") && !removal) {
                color = placementOutlineColor(worldState);
                lastWorldPlacementOutlineColor = color;
            }
            if (removal && HologramJobVisuals.status(worldState, c)
                .equals("pending")) color = 0xf08b83;
            if (!dynamicPlacementColor) t.setColorRGBA_F(
                ((color >> 16) & 255) / 255f,
                ((color >> 8) & 255) / 255f,
                (color & 255) / 255f,
                c.anchor ? .95f : .65f);
            for (int[] edge : EDGES) {
                double[] a = VERTICES[edge[0]], b = VERTICES[edge[1]];
                int axis = a[0] != b[0] ? 0 : a[1] != b[1] ? 1 : 2;
                int x = c.x + (int) (Math.min(a[0], b[0]) + .5);
                int y = c.y + (int) (Math.min(a[1], b[1]) + .5);
                int z = c.z + (int) (Math.min(a[2], b[2]) + .5);
                if (!HologramSelectionOutline.ownsEdge(
                    c.x,
                    c.y,
                    c.z,
                    x,
                    y,
                    z,
                    axis,
                    (xx, yy, zz) -> worldOutlineCells.contains(coordinate(xx, yy, zz)))) continue;
                for (int v : edge)
                    t.addVertex(c.x + .5 + VERTICES[v][0], c.y + .5 + VERTICES[v][1], c.z + .5 + VERTICES[v][2]);
            }
            lastWorldGeometry++;
        }
        t.draw();
        GL11.glEndList();
        group.lists.add(list);
        group.built = end;
        group.geometry += lastWorldGeometry - beforeGeometry;
        group.gt += lastWorldGTGeometry - beforeGT;
        group.fallback += lastWorldFallback - beforeFallback;
        group.ghosts += lastWorldGhosts - beforeGhosts;
        return true;
    }

    private static final class WorldGroup {

        final int x, y, z;
        final List<HologramState.Cell> cells = new ArrayList<>();
        final List<Integer> lists = new ArrayList<>();
        int built;
        int geometry, gt, fallback, ghosts;
        final Set<Integer> matchedIndices = new HashSet<>();

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

    private static int previewBatchLimit(Tessellator tess) {
        // Vanilla's 8-int vertices fit comfortably below its large-buffer shrink threshold.
        // DirectTessellator exposes the active format; also reserve addVertex's 32-int headroom.
        if (!(tess instanceof DirectTessellator)) return 4092;
        int bytesPerVertex = ((DirectTessellator) tess).getVertexFormat()
            .getVertexSize();
        return Math.min(4092, ((0x20000 * 4 - 32 * 4) / bytesPerVertex) & ~3);
    }

    private static float previewOutlineDensity() {
        // GUI drawing is orthographic with translation/axis scale. Include the active MUI transform and
        // framebuffer viewport, so thresholds do not change meaning with GUI scale or a resized scene widget.
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, OUTLINE_MATRIX);
        double scaleX = OUTLINE_MATRIX.get(0), scaleY = OUTLINE_MATRIX.get(5);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, OUTLINE_MATRIX);
        GL11.glGetInteger(GL11.GL_VIEWPORT, OUTLINE_VIEWPORT);
        scaleX = Math.abs(scaleX * OUTLINE_MATRIX.get(0)) * OUTLINE_VIEWPORT.get(2) / 2 * projectedFrame.scale;
        scaleY = Math.abs(scaleY * OUTLINE_MATRIX.get(5)) * OUTLINE_VIEWPORT.get(3) / 2 * projectedFrame.scale;
        return previewOutlineDensity(
            Math.hypot(projectedFrame.basis.rightX() * scaleX, projectedFrame.basis.upX() * scaleY),
            Math.abs(projectedFrame.basis.upY() * scaleY),
            Math.hypot(projectedFrame.basis.rightZ() * scaleX, projectedFrame.basis.upZ() * scaleY));
    }

    /** Physical framebuffer pixels between adjacent voxel corners; a head-on collapsed axis is ignored. */
    static float previewOutlineDensity(double xPixels, double yPixels, double zPixels) {
        double spacing = Double.POSITIVE_INFINITY;
        if (xPixels > 1e-6) spacing = Math.min(spacing, xPixels);
        if (yPixels > 1e-6) spacing = Math.min(spacing, yPixels);
        if (zPixels > 1e-6) spacing = Math.min(spacing, zPixels);
        return spacing == Double.POSITIVE_INFINITY ? 0 : (float) Math.max(0, Math.min(1, (spacing - 2) / 4));
    }

    static float previewOutlineAlpha(boolean chosen, boolean related, boolean empty, boolean fadeDefault,
        float density) {
        return chosen || related ? 1 : empty ? .18f : .45f * (fadeDefault ? density : 1);
    }

    /** Only already-resolved, complete material samples permit hiding a default wireframe. */
    static boolean completePreviewMaterial(HologramTextures.Material material) {
        if (material == null || material.fallback || material.geometry.isEmpty()) return false;
        for (HologramGeometry.Surface surface : material.geometry) {
            List<HologramTextures.Sample> samples = material.samples(surface);
            if (samples.isEmpty()) return false;
            for (HologramTextures.Sample sample : samples) {
                if (sample.icon == null) return false;
                String name = sample.icon.getIconName();
                if (name == null || name.isEmpty() || name.equals("missingno") || name.endsWith(":missingno"))
                    return false;
            }
        }
        return true;
    }

    private static double textureU(IIcon icon, int side, double[] point) {
        double u = side == 4 ? point[2] + .5 : side == 5 ? .5 - point[2] : side == 2 ? .5 - point[0] : point[0] + .5;
        return icon.getMinU() + (icon.getMaxU() - icon.getMinU()) * u;
    }

    private static double textureV(IIcon icon, int side, double[] point) {
        double v = side == 0 ? point[2] + .5 : side == 1 ? .5 - point[2] : .5 - point[1];
        return icon.getMinV() + (icon.getMaxV() - icon.getMinV()) * v;
    }

    private static void emitPreviewQuad(Tessellator tess, Face face, HologramTextures.Sample sample, int sampleIndex,
        double depthScale, double shiftX, double shiftY, boolean displaced) {
        float shade = face.side == 1 ? 1 : face.side < 2 ? .6f : .8f;
        setSampleColor(tess, sample, shade, displaced ? .38f : .96f);
        if (face.cell.sourceMissing && !face.cell.missingBlock) tess.setColorRGBA_F(.55f, .55f, .55f, .78f);
        for (int i = 0; i < 4; i++) {
            int vertex = FACES[face.side][i];
            double[] point = face.points[vertex];
            tess.addVertexWithUV(
                point[0] + shiftX,
                point[1] + shiftY,
                point[2] * depthScale + sampleIndex * .015,
                textureU(sample.icon, face.side, face.local(vertex)),
                textureV(sample.icon, face.side, face.local(vertex)));
        }
    }

    /** Dynamic icons, drag transforms and extended tessellators keep the same once-only vertex commands. */
    private static boolean drawPreviewQuads(Tessellator tess, List<Face> faces, double depthScale, double moveX,
        double moveY) {
        int vertices = 0, limit = 0;
        boolean drawn = false;
        tess.startDrawingQuads();
        for (int at = 0; at < faces.size(); at++) {
            Face face = faces.get(at);
            if (face.empty || face.nativeModel) continue;
            boolean displaced = moving(face.cell);
            List<HologramTextures.Sample> samples = face.samples();
            for (int sample = 0; sample < samples.size(); sample++) {
                if (limit > 0 && vertices + 4 > limit) {
                    tess.draw();
                    tess.startDrawingQuads();
                    vertices = 0;
                }
                emitPreviewQuad(
                    tess,
                    face,
                    samples.get(sample),
                    sample,
                    depthScale,
                    displaced ? moveX : 0,
                    displaced ? moveY : 0,
                    displaced);
                if (limit == 0) limit = previewBatchLimit(tess);
                vertices += 4;
                drawn = true;
            }
        }
        tess.draw();
        return drawn;
    }

    /** Custom/subclass UV access and animated sprites remain live; resource reload releases every cached slice. */
    static boolean stablePreviewIcon(IIcon icon) {
        return icon != null && icon.getClass() == TextureAtlasSprite.class
            && ((TextureAtlasSprite) icon).getFrameCount() <= 1;
    }

    /** Original sorted face/layer order, partitioned only at complete quad boundaries. No GL resource is retained. */
    private static final class PreviewSubmission {

        // 64 MiB of copied raw ints; masks, scalar states and range objects are additional retained memory.
        private static final int MAX_RETAINED_INTS = 16 * 1024 * 1024;
        final List<Face> faces;
        final double depthScale;
        final List<PreviewBatch> batches = new ArrayList<>();
        boolean unsupported;
        int retainedInts;

        PreviewSubmission(List<Face> faces, double depthScale) {
            this.faces = faces;
            this.depthScale = depthScale;
            int firstFace = -1, firstSample = 0, lastFace = 0, lastSample = 0, quads = 0;
            boolean stable = true;
            for (int at = 0; at < faces.size(); at++) {
                Face face = faces.get(at);
                if (face.empty || face.nativeModel) continue;
                List<HologramTextures.Sample> samples = face.samples();
                for (int sample = 0; sample < samples.size(); sample++) {
                    if (firstFace < 0) {
                        firstFace = at;
                        firstSample = sample;
                    }
                    stable &= stablePreviewIcon(samples.get(sample).icon);
                    lastFace = at;
                    lastSample = sample + 1;
                    if (++quads == 1023) {
                        batches.add(new PreviewBatch(firstFace, firstSample, lastFace, lastSample, quads, stable));
                        firstFace = -1;
                        quads = 0;
                        stable = true;
                    }
                }
            }
            if (quads != 0) batches.add(new PreviewBatch(firstFace, firstSample, lastFace, lastSample, quads, stable));
        }

        boolean draw(Tessellator tess) {
            for (int at = 0; at < batches.size(); at++) {
                PreviewBatch batch = batches.get(at);
                tess.startDrawingQuads();
                if (batch.stable && !unsupported) {
                    if (batch.slice != null && batch.slice.replay(tess)) {
                        if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled()) previewSliceHits++;
                    } else if (retainedInts - (batch.slice == null ? 0 : batch.slice.retainedInts()) + batch.quads * 32
                        <= MAX_RETAINED_INTS) {
                            // Capture executes the append-only callback once, including on unsupported layouts.
                            int previousInts = batch.slice == null ? 0 : batch.slice.retainedInts();
                            TessellatorSlice captured = TessellatorSlice.capture(tess, () -> emit(tess, batch));
                            batch.slice = captured;
                            retainedInts -= previousInts;
                            if (batch.slice == null) {
                                unsupported = true;
                                if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled())
                                    previewSliceUnsupported++;
                            } else {
                                retainedInts += batch.slice.retainedInts();
                                if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled()) {
                                    previewSliceCaptures++;
                                    previewSliceCapturedInts += batch.slice.retainedInts();
                                }
                            }
                        } else emit(tess, batch);
                } else emit(tess, batch);
                tess.draw();
            }
            return !batches.isEmpty();
        }

        private void emit(Tessellator tess, PreviewBatch batch) {
            for (int at = batch.firstFace; at <= batch.lastFace; at++) {
                Face face = faces.get(at);
                if (face.empty || face.nativeModel) continue;
                List<HologramTextures.Sample> samples = face.samples();
                int first = at == batch.firstFace ? batch.firstSample : 0;
                int last = at == batch.lastFace ? batch.lastSample : samples.size();
                for (int sample = first; sample < last; sample++)
                    emitPreviewQuad(tess, face, samples.get(sample), sample, depthScale, 0, 0, false);
            }
        }
    }

    private static final class PreviewBatch {

        final int firstFace, firstSample, lastFace, lastSample, quads;
        final boolean stable;
        TessellatorSlice slice;

        PreviewBatch(int firstFace, int firstSample, int lastFace, int lastSample, int quads, boolean stable) {
            this.firstFace = firstFace;
            this.firstSample = firstSample;
            this.lastFace = lastFace;
            this.lastSample = lastSample;
            this.quads = quads;
            this.stable = stable;
        }
    }

    private static final class Face {

        final HologramState.Cell cell;
        final double[][] points;
        final int side;
        final double depth;
        final HologramTextures.Material material;
        final boolean empty, nativeModel;
        final HologramGeometry.Surface surface;
        boolean fadeDefault;

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
