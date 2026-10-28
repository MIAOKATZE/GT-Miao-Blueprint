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
    private static final Map<HologramRegionCache.Key, PreviewRegion> previewRegions = new LinkedHashMap<>();
    private static final long PREVIEW_BUILD_NANOS = 2_000_000L;
    private static PreviewPreparation previewPreparation;
    private static boolean projectionPending;
    private static int modelDemandCursor, modelDependencyCursor;
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
    private static WorldPreparation worldPreparation;
    private static HologramState boundsState;
    private static double boundsX, boundsY, boundsZ, boundsExtent;
    static int projectionLayer = Integer.MAX_VALUE;
    static int[] moveOffset = new int[3];

    private static boolean moving(HologramState.Cell cell) {
        return (moveOffset[0] != 0 || moveOffset[1] != 0 || moveOffset[2] != 0) && selection != null
            && selection.contains(cell.index);
    }

    private static double[] moveScreen(HologramState.Cell cell) {
        if (!moving(cell)) return new double[] { 0, 0 };
        double[] from = projectedFrame.projectScreen(0, 0, 0),
            to = projectedFrame.projectScreen(moveOffset[0], moveOffset[1], moveOffset[2]);
        return new double[] { to[0] - from[0], to[1] - from[1] };
    }

    static boolean showEmptyCells;
    static HologramSelection selection;
    static String selectedStructureId = "";
    private static HologramEditorCameraFrame projectedFrame, presentedFrame;
    private static HologramState presentedState;
    private static final Map<String, double[][]> structureBoxes = new LinkedHashMap<>();
    private static final Set<String> missingStructures = new HashSet<>();
    private static final Map<String, double[][]> hintBoxes = new LinkedHashMap<>();
    private static HologramState structureBoundsState;
    private static Map<String, int[]> cachedStructureBounds = new LinkedHashMap<>();
    private static boolean visibleEmptyCells;

    static void release() {
        projectionPending = false;
        previewKey = "";
        projectedFrame = presentedFrame = null;
        presentedState = null;
        worldRenderWitness = null;
        clearWorldGroups();
        worldState = previewState = null;
        boundsState = null;
        HologramTextures.clear();
        HologramLightPreview.clear();
        HologramLinkNodePreview.clear();
        HologramBlockRender.clear();
        nativePreviewCells.clear();
        cachedFaces.clear();
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

    static void authorSnapshotChanged(HologramState previous, HologramState next,
        Set<HologramRegionCache.Key> dependencies) {
        // Inactive documents may be edited/saved without retiring a different document/world viewport.
        if (visibleState == previous || previewState == previous
            || previewPreparation != null && previewPreparation.state == previous)
            modelSnapshotChanged(previous, next, dependencies);
    }

    static boolean authorCellVisible(HologramState state, HologramState.Cell cell) {
        // Without a presented camera, retain rather than guessing that an unrelated viewport is offscreen.
        if (projectedFrame == null || visibleState == null || visibleState.authorProjection != state.authorProjection)
            return true;
        return previewVisible(cell, visibleLayer, visibleView, visibleView == 1 || visibleMode == 2)
            && previewRegionVisible(state, HologramRegionCache.key(cell));
    }

    static boolean authorRegionVisible(HologramState state, HologramRegionCache.Key key) {
        if (!state.regions.regions.containsKey(key)) return false;
        if (projectedFrame == null || visibleState == null || visibleState.authorProjection != state.authorProjection)
            return true;
        return previewRegionVisible(state, key);
    }

    static void releaseAuthor(HologramAuthorProjection owner) {
        if (visibleState != null && visibleState.authorProjection == owner
            || previewState != null && previewState.authorProjection == owner) release();
    }

    /** Preserve detached geometry across a validated delta; teardown still owns world/session and reload changes. */
    static void snapshotChanged(HologramState previous, HologramState next) {
        modelSnapshotChanged(previous, next, java.util.Collections.emptySet());
    }

    static void modelSnapshotChanged(HologramState previous, HologramState next,
        Set<HologramRegionCache.Key> modelDependencies) {
        if (!next.follows(previous) || renderMetadataChanged(previous, next)) {
            release();
            return;
        }
        Set<HologramRegionCache.Key> dirty = HologramRegionCache.affected(next);
        dirty.addAll(modelDependencies);
        lastSnapshotDirtyRegions = dirty.size();
        for (HologramRegionCache.Key key : dirty) previewRegions.remove(key);
        if (visibleState == previous) visibleState = next;
        boolean extentChanged = next.topologyChanged
            && !java.util.Arrays.equals(previous.regions.bounds(), next.regions.bounds());
        if (boundsState == previous) boundsState = extentChanged ? null : next;
        if (next.topologyChanged) {
            structureBoundsState = null;
            // Document cells keep stable slots (only deleted tail swaps change an extra slot).
            // Dirty old/new regions are sufficient even for a coordinate or membership edit.
            if (next.authorProjection != null && previewPreparation != null && previewPreparation.state == previous)
                previewPreparation.update(previous, next, dirty);
            else previewPreparation = null;
            // A changed extent changes the screen transform, not the retained native region geometry.
            if (extentChanged) for (PreviewRegion region : previewRegions.values()) {
                region.projectedKey = "";
                region.projectionWorkKey = "";
            }
        } else if (previewPreparation != null && previewPreparation.state == previous) {
            previewPreparation.update(previous, next, dirty);
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
            boolean indexed = worldIndex == previous.cells.size();
            if (!indexed && !next.modelOnly
                && (next.changedIndices.length != 0 || !next.changedPreviousCells.isEmpty())) {
                clearWorldGroups();
            } else {
                for (HologramRegionCache.Key key : dirty) {
                    WorldGroup group = worldGroups.get(coordinate(key.x, key.y, key.z));
                    if (group != null) invalidateWorldGroup(group);
                }
                if (next.modelOnly) {
                    for (HologramState.Cell old : next.changedPreviousCells) {
                        WorldGroup group = worldGroups.get(coordinate(old.x >> 4, old.y >> 4, old.z >> 4));
                        int slot = group == null ? -1 : group.cells.indexOf(old);
                        if (slot >= 0) {
                            HologramState.Cell replacement = next.cells.get(old.index);
                            group.cells.set(slot, replacement);
                            Coordinate position = coordinate(old.x, old.y, old.z);
                            if (worldGhosts.contains(position) && occludes(replacement, false))
                                worldOccluders.add(position);
                            else worldOccluders.remove(position);
                        }
                    }
                } else {
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
                }
                if (indexed) worldIndex = next.cells.size();
                if (worldPreparation != null) worldPreparation.state = next;
            }
            worldState = next;
        }
        // World lists must be retired before the native lists they reference.
        HologramBlockRender.advance(previous, next, dirty);
        HologramTextures.advance(previous, next);
        HologramLinkNodePreview.advance(previous, next);
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

    /** Readiness is evidence about current-camera output, not merely a visited detail cursor. */
    static boolean previewMeshReady(HologramState state) {
        if (state == null || previewState != state
            || previewKey.isEmpty()
            || projectionPending
            || previewPreparation == null
            || previewPreparation.state != state
            || !previewPreparation.pending.isEmpty()
            || previewPreparation.detailedCompleted != state.cells.size()) return false;
        for (HologramRegionCache.Key key : state.regions.regions.keySet()) {
            if (projectedFrame != null && !previewRegionVisible(state, key)) continue;
            PreviewRegion region = previewRegions.get(key);
            if (region == null || !region.complete
                || region.unresolved != 0
                || region.budgetLimited != 0
                || !previewKey.equals(region.projectedKey)) return false;
        }
        return true;
    }

    static boolean previewMeshVisible(HologramState state) {
        return state != null && previewState == state && (!cachedFaces.isEmpty() || !nativePreviewCells.isEmpty());
    }

    static int previewMeshCompleted(HologramState state) {
        if (previewPreparation == null || previewPreparation.state != state) return 0;
        int unresolved = 0;
        for (PreviewRegion region : previewRegions.values())
            if (region.complete) unresolved += region.unresolved + region.budgetLimited;
        PreviewTask active = previewPreparation.task;
        if (active != null && active.refining && active.detail != null)
            unresolved += active.detail.unresolved + active.detail.budgetLimited;
        return Math.max(0, previewPreparation.detailedCompleted - unresolved);
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
            previewPreparation = null;
        }
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
        double cx = boundsX + panX, cy = boundsY + panY, cz = boundsZ + panZ;
        if (projectedFrame == null || previewState != state || !key.equals(previewKey))
            projectedFrame = new HologramEditorCameraFrame(
                x,
                y,
                width,
                height,
                yaw,
                pitch,
                zoom,
                cx,
                cy,
                cz,
                boundsExtent);
        lastPreviewBuiltRegions = 0;
        long projectionDeadline = System.nanoTime() + PREVIEW_BUILD_NANOS;
        if (budgeted) {
            if (previewPreparation == null || previewPreparation.state != state)
                previewPreparation = new PreviewPreparation(state);
            if (state.authorProjection != null) {
                Set<HologramRegionCache.Key> revealed = new HashSet<>();
                for (Map.Entry<HologramRegionCache.Key, PreviewRegion> entry : previewRegions.entrySet())
                    if (entry.getValue().deferredVisibility && previewRegionVisible(state, entry.getKey()))
                        revealed.add(entry.getKey());
                if (!revealed.isEmpty()) {
                    for (HologramRegionCache.Key region : revealed) previewRegions.remove(region);
                    previewPreparation.update(state, state, revealed);
                    previewState = null;
                }
            }
            previewPreparation.advance(
                layer,
                view,
                currentView,
                Math.min(projectionDeadline, System.nanoTime() + PREVIEW_BUILD_NANOS / 2));
            int prepared = previewPreparation.completed;
            // Reproject at geometrically growing milestones. Rebuilding every accumulated face after each
            // time slice would turn a linear preparation into quadratic work on large library previews.
            if (prepared > previewPreparation.published
                && (previewPreparation.published == 0 || prepared >= 2L * previewPreparation.published
                    || prepared == 2L * state.cells.size())) {
                previewPreparation.published = prepared;
                previewState = null;
            }
        }
        if (previewState != state || !key.equals(previewKey) || projectionPending) {
            List<Face> faces = new ArrayList<>();
            List<RoleMarker> markers = new ArrayList<>();
            List<Face> outlines = new ArrayList<>();
            nativePreviewCells.clear();
            if (!budgeted)
                for (Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry : state.regions.regions
                    .entrySet()) {
                        if (!previewRegions.containsKey(entry.getKey())) {
                            previewRegions
                                .put(entry.getKey(), previewRegion(state, entry.getValue(), layer, view, currentView));
                            lastPreviewBuiltRegions++;
                        }
                    }
            visibleCells.clear();
            linkedPreviewCells.clear();
            for (PreviewRegion region : previewRegions.values()) {
                visibleCells.addAll(region.visible);
            }
            lastPreviewRoleMarkers = lastPreviewAnchorMarkers = 0;
            int facing = 0;
            for (int side = 0; side < 6; side++) {
                int[] normal = NORMALS[side];
                if (-(normal[0] * projectedFrame.basis.forwardX() + normal[1] * projectedFrame.basis.forwardY()
                    + normal[2] * projectedFrame.basis.forwardZ()) > 1e-8) facing |= 1 << side;
            }
            buildStructureBoxes(state);
            List<PreviewRegion> orderedRegions = new ArrayList<>(previewRegions.values());
            // Coarse spatial ordering is enough for hints; final capture retains exact face sorting.
            for (PreviewRegion region : orderedRegions) {
                HologramState.Cell first = region.visible.isEmpty() ? null : region.visible.get(0);
                region.depth = first == null ? 0 : projectedFrame.project(first.dx, first.dy, first.dz)[2];
            }
            orderedRegions.sort(Comparator.comparingDouble(region -> region.depth));
            projectionPending = false;
            int projectedWork = 0;
            for (PreviewRegion region : orderedRegions) {
                if (budgeted && !region.visible.isEmpty()
                    && !previewRegionVisible(state, HologramRegionCache.key(region.visible.get(0)))) continue;
                if (!key.equals(region.projectedKey)) {
                    if (!budgeted || projectedWork == 0 || System.nanoTime() < projectionDeadline)
                        projectedWork += projectPreviewRegion(
                            region,
                            key,
                            facing,
                            currentView,
                            budgeted,
                            projectionDeadline,
                            Integer.MAX_VALUE);
                    if (!key.equals(region.projectedKey)) projectionPending = true;
                }
                if (!key.equals(region.projectionWorkKey)) continue;
                faces.addAll(region.faces);
                outlines.addAll(region.outlines);
                markers.addAll(region.markers);
                nativePreviewCells.addAll(region.nativeCells);
                if (key.equals(region.projectedKey)) linkedPreviewCells.addAll(region.linked);
            }
            int shownMarkers = 0;
            java.util.Iterator<RoleMarker> markerIterator = markers.iterator();
            while (markerIterator.hasNext()) {
                RoleMarker marker = markerIterator.next();
                if (!marker.cell.anchor && shownMarkers >= 64) markerIterator.remove();
                else shownMarkers++;
            }
            if (!budgeted) faces.sort(Comparator.comparingDouble(f -> f.depth));
            markers.sort(Comparator.comparingInt(marker -> marker.cell.anchor ? 1 : 0));
            previewState = state;
            previewKey = key;
            cachedFaces = faces;
            cachedMarkers = markers;
            cachedOutlines = outlines;
            cachedExtent = boundsExtent;
        }
        List<Face> faces = cachedFaces;
        List<RoleMarker> markers = cachedMarkers;
        double extent = cachedExtent;
        lastPreviewRoleMarkers = lastPreviewAnchorMarkers = 0;
        if (!HologramBlueprintPreview.cleanCapture()) {
            if (selected >= 0 && selected < state.cells.size())
                HologramSnapshotModelsClient.requestVisible(state, state.cells.get(selected), currentView);
            for (int work = 0; work < Math.min(128, cachedOutlines.size()); work++) {
                Face face = cachedOutlines.get(Math.floorMod(modelDemandCursor++, cachedOutlines.size()));
                HologramSnapshotModelsClient.requestVisible(state, face.cell, currentView);
            }
            if (state.authorProjection != null) {
                modelDependencyCursor = retryAuthorModelDemands(
                    state,
                    cachedOutlines.size(),
                    index -> cachedOutlines.get(index).cell,
                    modelDependencyCursor,
                    currentView,
                    Math.min(projectionDeadline, System.nanoTime() + 250_000L),
                    System::nanoTime);
            }
        }
        int hovered = -1;
        double hoveredDepth = Double.NEGATIVE_INFINITY;
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
                if (inside && face.depth >= hoveredDepth) {
                    hovered = face.cell.index;
                    hoveredDepth = face.depth;
                }
                if (face.empty || face.nativeModel) continue;
                float shade = face.side == 1 ? 1 : face.side < 2 ? .6f : .8f;
                double[] shifted = moveScreen(face.cell);
                int sampleIndex = 0;
                for (HologramTextures.Sample sample : face.samples()) {
                    setSampleColor(t, sample, shade, moving(face.cell) ? .38f : .96f);
                    if (face.cell.sourceMissing && !face.cell.missingBlock) t.setColorRGBA_F(.55f, .55f, .55f, .78f);
                    for (int i = 0; i < 4; i++) {
                        int vertex = FACES[face.side][i];
                        double[] point = face.points[vertex];
                        t.addVertexWithUV(
                            point[0] + shifted[0],
                            point[1] + shifted[1],
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
                applyPreviewTransform(cell, depthScale);
                if (moving(cell)) GL11.glTranslated(moveOffset[0], moveOffset[1], moveOffset[2]);
                GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT);
                if (moving(cell)) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL14.glBlendColor(1, 1, 1, .38f);
                    GL11.glBlendFunc(0x8003, 0x8004);
                }
                HologramBlockRender.draw(cell, view == 1 || state.data.getInteger("mode") == 2);
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
            // flicker while keeping back edges hidden; all cells retain clear wireframes.
            GL11.glDepthMask(false);
            t.startDrawing(GL11.GL_LINES);
            java.util.Set<Integer> related = selection == null ? java.util.Collections.emptySet()
                : selection.related(state);
            if (!HologramBlueprintPreview.cleanCapture()) for (Face face : cachedOutlines) {
                boolean chosen = face.cell.index == selected
                    || selection != null && selection.contains(face.cell.index);
                boolean relatedCasing = !chosen && related.contains(face.cell.index);
                t.setColorRGBA_F(
                    chosen ? 1 : relatedCasing ? .78f : .2f,
                    chosen ? .92f : relatedCasing ? .61f : .7f,
                    chosen ? .5f : relatedCasing ? 1 : .8f,
                    chosen || relatedCasing ? 1 : face.empty ? .18f : .45f);
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
                    else if (com.miaokatze.gtmb.hologram.HologramTextBox.isAttachmentId(entry.getKey()))
                        t.setColorRGBA_F(.78f, .52f, 1, .85f);
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
        if (!previewPickingReady(state, mouseX, mouseY)) hovered = -1;
        presentedFrame = projectedFrame;
        presentedState = state;
        return hovered;
    }

    /**
     * A full demand queue can reject a connected model's neighbor even after the reader itself is loaded.
     * Retry readiness, not only the reader payload, without rebuilding cached regions or scanning every model.
     * The cursor survives yields; one bounded 27-cell neighborhood may finish past the shared frame deadline.
     */
    static int retryAuthorModelDemands(HologramState state, int count,
        java.util.function.IntFunction<HologramState.Cell> cellAt, int cursor, boolean current, long deadline,
        java.util.function.LongSupplier clock) {
        if (state == null || state.authorProjection == null || count <= 0) return cursor;
        for (int work = 0; work < Math.min(16, count) && (work == 0 || clock.getAsLong() < deadline); work++) {
            HologramState.Cell cell = cellAt.apply(Math.floorMod(cursor++, count));
            HologramSnapshotModelsClient.ready(state, cell, current);
        }
        return cursor;
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
        if (!previewPickingReady(presentedState, x, y)) return -1;
        int result = -1;
        double depth = Double.NEGATIVE_INFINITY;
        for (Face face : cachedFaces) if (face.depth >= depth && face.contains(x, y)) {
            result = face.cell.index;
            depth = face.depth;
        }
        for (RoleMarker marker : cachedMarkers)
            if (x >= marker.left && x < marker.right && y >= marker.top && y < marker.bottom)
                result = marker.cell.index;
        return result;
    }

    /** An undisplayed spatial segment must not turn a click into a hit on geometry behind it. */
    private static boolean previewPickingReady(HologramState state, int x, int y) {
        if (state == null || projectedFrame == null) return false;
        for (HologramRegionCache.Key key : state.regions.regions.keySet()) {
            PreviewRegion region = previewRegions.get(key);
            if (region != null && region.hintsComplete && previewKey.equals(region.projectedKey)) continue;
            double[] bounds = previewRegionBounds(state, key);
            if (x >= bounds[0] && x <= bounds[2] && y >= bounds[1] && y <= bounds[3]) return false;
        }
        return true;
    }

    private static double[] previewRegionBounds(HologramState state, HologramRegionCache.Key key) {
        double left = Double.POSITIVE_INFINITY, top = left, right = Double.NEGATIVE_INFINITY, bottom = right;
        for (double[] vertex : VERTICES) {
            double[] point = projectedFrame.projectScreen(
                (double) (key.x << 4) - state.data.getInteger("x") + 7.5 + vertex[0] * 16,
                (double) (key.y << 4) - state.data.getInteger("y") + 7.5 + vertex[1] * 16,
                (double) (key.z << 4) - state.data.getInteger("z") + 7.5 + vertex[2] * 16);
            left = Math.min(left, point[0]);
            top = Math.min(top, point[1]);
            right = Math.max(right, point[0]);
            bottom = Math.max(bottom, point[1]);
        }
        return new double[] { left, top, right, bottom };
    }

    private static boolean previewRegionVisible(HologramState state, HologramRegionCache.Key key) {
        if (projectedFrame == null) return true;
        double[] bounds = previewRegionBounds(state, key);
        return bounds[2] >= projectedFrame.coordinates.x() && bounds[3] >= projectedFrame.coordinates.y()
            && bounds[0] <= projectedFrame.coordinates.x() + projectedFrame.coordinates.width()
            && bounds[1] <= projectedFrame.coordinates.y() + projectedFrame.coordinates.height();
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
        for (HologramState.Cell c : cells) previewCell(state, result, c, layer, view, current);
        result.complete = true;
        result.hintsComplete = true;
        return result;
    }

    private static void previewCell(HologramState state, PreviewRegion result, HologramState.Cell c, int layer,
        int view, boolean current) {
        previewCell(state, result, c, layer, view, current, true);
    }

    private static void previewCell(HologramState state, PreviewRegion result, HologramState.Cell c, int layer,
        int view, boolean current, boolean detailed) {
        if (!previewVisible(c, layer, view, current)) return;
        result.visible.add(c);
        boolean visibleNative = state.authorProjection == null || projectedFrame == null
            || previewRegionVisible(state, HologramRegionCache.key(c));
        if (detailed && visibleNative && HologramSnapshotModelsClient.resumableBudget(state, c)) {
            HologramSnapshotModelsClient.requestVisible(state, c, current);
            result.budgetLimited++;
            result.cells.add(new PreviewCell(c, 63, "细节预算不足，等待容量", false));
            return;
        }
        if (!detailed || !visibleNative || !HologramSnapshotModelsClient.ready(state, c, current)) {
            if (detailed) {
                result.unresolved++;
                if (!visibleNative) result.deferredVisibility = true;
            }
            result.cells.add(new PreviewCell(c, 63, roleLabel(c), false));
            return;
        }
        if (state.authorProjection != null && "localFallback".equals(c.renderKind)) {
            result.cells.add(new PreviewCell(c, 63, "预览不可用", false));
            return;
        }
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
        if (!nativeModel && HologramBlockRender.deferred(c, current)) {
            result.unresolved++;
            result.cells.add(new PreviewCell(c, 63, "细节待载入", false));
            return;
        }
        HologramTextures.Material material = empty ? null : HologramTextures.material(c, current);
        if (nativeModel || !empty && material.partial) exposed = 63;
        if (c.missingBlock) role = "缺失方块";
        else if (c.sourceMissing && c.anchor) role = "来源结构缺失";
        if (!nativeModel && !empty && role.isEmpty() && material.fallback) role = "材质缺失";
        if (!nativeModel && !empty && HologramNativeTiles.supported(c.block(current || c.anchor))) role = "模型缺失";
        if (!empty && role.isEmpty() && c.iconOnly) role = "占位";
        if (!c.prompt.isEmpty()) role = role.isEmpty() ? "提示" : role + " · 提示";
        if (exposed != 0 || !role.isEmpty()) result.cells.add(new PreviewCell(c, exposed, role, true));
    }

    /** Client render-thread cursor: each cell invokes the existing native/texture path exactly once. */
    private static int projectPreviewRegion(PreviewRegion region, String key, int facing, boolean currentView,
        boolean budgeted, long deadline, int maxCells) {
        return projectPreviewRegion(region, key, facing, currentView, budgeted, deadline, maxCells, System::nanoTime);
    }

    private static int projectPreviewRegion(PreviewRegion region, String key, int facing, boolean currentView,
        boolean budgeted, long deadline, int maxCells, java.util.function.LongSupplier clock) {
        if (!key.equals(region.projectionWorkKey)) {
            region.projectionWorkKey = key;
            region.projectedKey = "";
            region.projectedCells = 0;
            region.faces.clear();
            region.outlines.clear();
            region.markers.clear();
            region.nativeCells.clear();
        }
        int work = 0;
        while (region.projectedCells < region.cells.size()
            && (!budgeted || work < maxCells && (work == 0 || clock.getAsLong() < deadline))) {
            PreviewCell previewCell = region.cells.get(region.projectedCells++);
            work++;
            HologramState.Cell c = previewCell.cell;
            int exposed = previewCell.exposed & facing;
            String role = previewCell.role;
            if (exposed == 0 && role.isEmpty()) continue;
            double[][] projected = new double[8][];
            for (int v = 0; v < 8; v++) {
                projected[v] = projectedFrame
                    .project(c.dx + VERTICES[v][0], c.dy + VERTICES[v][1], c.dz + VERTICES[v][2]);
            }
            if (!HologramBlueprintPreview.cleanCapture() && !role.isEmpty() && (c.anchor || region.markers.size() < 64))
                region.markers.add(new RoleMarker(c, projected, role));
            region.outlines.add(new Face(c, projected, 0, currentView, null, previewCell.detailed));
            if (previewCell.detailed && HologramBlockRender.ready(c, currentView)) {
                region.nativeCells.add(c);
                // Cell picking keeps the established voxel contract while the drawing uses
                // the mod's actual triangles, materials, covers and part geometry.
                for (int side = 0; side < 6; side++)
                    if ((exposed & 1 << side) != 0) region.faces.add(new Face(c, projected, side, currentView));
                continue;
            }
            HologramTextures.Material material = previewCell.detailed ? HologramTextures.material(c, currentView)
                : HologramLightPreview.material(c, currentView);
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
                region.faces.add(new Face(c, points, surface.side, currentView, surface, previewCell.detailed));
            }
        }
        if (region.projectedCells == region.cells.size()) {
            region.faces.sort(Comparator.comparingDouble(face -> face.depth));
            region.projectedKey = key;
        }
        return work;
    }

    private static final class PreviewPreparation {

        HologramState state;
        final Map<HologramRegionCache.Key, PreviewTask> pending = new LinkedHashMap<>();
        PreviewTask task;
        int completed, published, detailedCompleted;
        boolean firstHintsPresented;

        PreviewPreparation(HologramState state) {
            this.state = state;
            for (Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry : state.regions.regions
                .entrySet()) {
                PreviewRegion retained = previewRegions.get(entry.getKey());
                if (retained != null && retained.complete) {
                    completed += 2 * entry.getValue()
                        .size();
                    detailedCompleted += entry.getValue()
                        .size();
                } else {
                    previewRegions.remove(entry.getKey());
                    pending.put(entry.getKey(), new PreviewTask(entry.getKey(), entry.getValue()));
                }
            }
        }

        /** A model reply changes only its dirty regions; unrelated partial native work keeps its cursor. */
        void update(HologramState previous, HologramState next, Set<HologramRegionCache.Key> dirty) {
            state = next;
            for (HologramRegionCache.Key key : dirty) {
                PreviewTask old = pending.remove(key);
                if (old != null) {
                    completed -= old.cell + (old.refining ? old.cells.size() : 0);
                    if (old.refining) detailedCompleted -= old.cell;
                    if (task == old) task = null;
                } else {
                    List<HologramState.Cell> before = previous.regions.regions.get(key);
                    if (before != null) {
                        completed -= 2 * before.size();
                        detailedCompleted -= before.size();
                    }
                }
                List<HologramState.Cell> cells = next.regions.regions.get(key);
                if (cells != null) pending.put(key, new PreviewTask(key, cells));
            }
            published = Math.min(published, completed);
        }

        void advance(int layer, int view, boolean current) {
            advance(layer, view, current, System.nanoTime() + PREVIEW_BUILD_NANOS);
        }

        void advance(int layer, int view, boolean current, long deadline) {
            advance(layer, view, current, deadline, System::nanoTime);
        }

        void advance(int layer, int view, boolean current, long deadline, java.util.function.LongSupplier clock) {
            int work = 0;
            if (task != null && !previewRegionVisible(state, task.key)) task = null;
            while (!pending.isEmpty() && (work == 0 || clock.getAsLong() < deadline)) {
                if (task == null) task = nextTask();
                if (task.cell == task.cells.size()) {
                    if (!task.refining) {
                        task.region.hintsComplete = true;
                        task.refining = true;
                        task.cell = 0;
                        task.detail = new PreviewRegion();
                        // Preserve the first cheap visual feedback; later regions consume the shared frame deadline.
                        if (!firstHintsPresented) {
                            firstHintsPresented = true;
                            break;
                        }
                        continue;
                    }
                    completeTask();
                    continue;
                }
                if (task.region == null) {
                    task.region = new PreviewRegion();
                    previewRegions.put(task.key, task.region);
                    lastPreviewBuiltRegions++;
                }
                PreviewRegion result = task.refining ? task.detail : task.region;
                previewCell(state, result, task.cells.get(task.cell++), layer, view, current, task.refining);
                result.projectedKey = "";
                completed++;
                if (task.refining) detailedCompleted++;
                work++;
            }
            if (task != null && task.refining && task.cell == task.cells.size()) completeTask();
        }

        private PreviewTask nextTask() {
            PreviewTask first = null;
            for (PreviewTask candidate : pending.values()) {
                if (first == null) first = candidate;
                if (previewRegionVisible(state, candidate.key)) return candidate;
            }
            return first;
        }

        private void completeTask() {
            task.detail.complete = true;
            task.detail.hintsComplete = true;
            previewRegions.put(task.key, task.detail);
            pending.remove(task.key);
            task = null;
        }
    }

    private static final class PreviewTask {

        final HologramRegionCache.Key key;
        final List<HologramState.Cell> cells;
        PreviewRegion region, detail;
        int cell;
        boolean refining;

        PreviewTask(HologramRegionCache.Key key, List<HologramState.Cell> cells) {
            this.key = key;
            this.cells = cells;
        }
    }

    private static final class PreviewRegion {

        final List<HologramState.Cell> visible = new ArrayList<>(), linked = new ArrayList<>();
        final List<PreviewCell> cells = new ArrayList<>();
        String projectedKey = "", projectionWorkKey = "";
        int projectedCells, unresolved, budgetLimited;
        double depth;
        boolean complete, hintsComplete, deferredVisibility;
        List<Face> faces = new ArrayList<>(), outlines = new ArrayList<>();
        List<RoleMarker> markers = new ArrayList<>();
        List<HologramState.Cell> nativeCells = new ArrayList<>();
    }

    private static final class PreviewCell {

        final HologramState.Cell cell;
        final int exposed;
        final String role;
        final boolean detailed;

        PreviewCell(HologramState.Cell cell, int exposed, String role, boolean detailed) {
            this.cell = cell;
            this.exposed = exposed;
            this.role = role;
            this.detailed = detailed;
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
        WorldView worldView = new WorldView(state, px, py, pz, offsetX, offsetY, offsetZ, range, frustum);
        if (worldPreparation == null) worldPreparation = new WorldPreparation(state);
        worldPreparation.advance(worldView, Math.min(deadline, System.nanoTime() + WORLD_BUILD_NANOS / 2));
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        int drawnLists = 0, modelDemand = 0;
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
                for (int work = 0; work < Math.min(16, group.cells.size()) && modelDemand < 64; work++, modelDemand++) {
                    HologramState.Cell model = group.cells.get(Math.floorMod(group.modelCursor++, group.cells.size()));
                    HologramSnapshotModelsClient.requestVisible(state, model, state.data.getInteger("mode") == 2);
                }
                // Native block access already reads the immutable full spatial index. Only the
                // occupancy actually used by face/edge culling needs this region and its halo.
                if (group.lightBuilt < group.cells.size() && System.nanoTime() < deadline)
                    compileWorldGroup(group, state.data.getInteger("mode") == 2, true, deadline);
                if (worldPreparation.ready(group)) {
                    while (group.built < group.cells.size() && System.nanoTime() < deadline) {
                        if (!compileWorldGroup(group, state.data.getInteger("mode") == 2, false, deadline)) break;
                    }
                }
                boolean detailed = worldPreparation.ready(group) && group.built == group.cells.size();
                if (detailed) {
                    for (int list : group.lightLists) GL11.glDeleteLists(list, 1);
                    group.lightLists.clear();
                }
                for (int list : detailed ? group.lists : group.lightLists) {
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
        for (WorldGroup group : worldGroups.values()) {
            for (int list : group.lists) GL11.glDeleteLists(list, 1);
            for (int list : group.lightLists) GL11.glDeleteLists(list, 1);
        }
        if (placementColorList != 0) GL11.glDeleteLists(placementColorList, 1);
        placementColorList = placementColor = 0;
        worldGroups.clear();
        worldGhosts.clear();
        worldOccluders.clear();
        worldOutlineCells.clear();
        worldIndex = 0;
        worldPreparation = null;
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
        for (int list : group.lightLists) GL11.glDeleteLists(list, 1);
        group.lists.clear();
        group.lightLists.clear();
        group.built = 0;
        group.lightBuilt = 0;
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

    /** Spatial cursors retain partial occupancy, independently of native meshes and camera motion. */
    private static final class WorldPreparation {

        HologramState state;
        final Map<HologramRegionCache.Key, WorldRegionCursor> regions = new LinkedHashMap<>();
        final List<WorldRegionCursor> distanceOrder = new ArrayList<>();
        String orderKey = "";

        WorldPreparation(HologramState state) {
            this.state = state;
            state.regions.regions.forEach((key, cells) -> regions.put(key, new WorldRegionCursor(key, cells)));
            distanceOrder.addAll(regions.values());
        }

        void advance(WorldView view, long deadline) {
            if (worldIndex == worldState.cells.size()) return;
            String nextOrder = view.orderKey();
            if (!orderKey.equals(nextOrder)) {
                orderKey = nextOrder;
                for (WorldRegionCursor region : distanceOrder) region.distance = view.distance(region.key);
                distanceOrder.sort(Comparator.comparingDouble(region -> region.distance));
            }
            List<WorldRegionCursor> visible = new ArrayList<>();
            for (WorldRegionCursor region : distanceOrder) if (view.visible(region.key)) visible.add(region);
            Set<WorldRegionCursor> ordered = new java.util.LinkedHashSet<>();
            for (WorldRegionCursor region : visible) {
                ordered.add(region);
                for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                    WorldRegionCursor halo = regions
                        .get(new HologramRegionCache.Key(region.key.x + x, region.key.y + y, region.key.z + z));
                    if (halo != null) ordered.add(halo);
                }
            }
            int work = 0;
            for (WorldRegionCursor region : ordered) {
                while (region.index < region.cells.size() && work < WORLD_MESH_BATCH && System.nanoTime() < deadline) {
                    HologramState.Cell source = region.cells.get(region.index++);
                    indexWorldCell(state, state.cells.get(source.index));
                    worldIndex++;
                    work++;
                }
                if (work == WORLD_MESH_BATCH || System.nanoTime() >= deadline) break;
            }
        }

        boolean ready(WorldGroup group) {
            if (worldIndex == worldState.cells.size()) return true;
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                WorldRegionCursor region = regions
                    .get(new HologramRegionCache.Key((group.x >> 4) + x, (group.y >> 4) + y, (group.z >> 4) + z));
                if (region != null && region.index != region.cells.size()) return false;
            }
            return true;
        }
    }

    private static final class WorldRegionCursor {

        final HologramRegionCache.Key key;
        final List<HologramState.Cell> cells;
        int index;
        double distance;

        WorldRegionCursor(HologramRegionCache.Key key, List<HologramState.Cell> cells) {
            this.key = key;
            this.cells = cells;
        }
    }

    private static final class WorldView {

        final HologramState state;
        final double px, py, pz, offsetX, offsetY, offsetZ, range;
        final Frustrum frustum;

        WorldView(HologramState state, double px, double py, double pz, double offsetX, double offsetY, double offsetZ,
            double range, Frustrum frustum) {
            this.state = state;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.range = range;
            this.frustum = frustum;
        }

        double[] bounds(HologramRegionCache.Key key) {
            int x = key.x << 4, y = key.y << 4, z = key.z << 4;
            double[] bounds = state.data.getBoolean("localPreview") ? HologramLocalPlacement.INSTANCE.bounds(x, y, z)
                : new double[] { x, y, z, (double) x + 16, (double) y + 16, (double) z + 16 };
            for (int axis = 0; axis < 6; axis++)
                bounds[axis] += axis % 3 == 0 ? offsetX : axis % 3 == 1 ? offsetY : offsetZ;
            return bounds;
        }

        String orderKey() {
            return Math.floor((px - offsetX) / 16) + ":"
                + Math.floor((py - offsetY) / 16)
                + ":"
                + Math.floor((pz - offsetZ) / 16);
        }

        double distance(HologramRegionCache.Key key) {
            double[] bounds = bounds(key);
            double dx = Math.max(bounds[0] - px, Math.max(0, px - bounds[3]));
            double dy = Math.max(bounds[1] - py, Math.max(0, py - bounds[4]));
            double dz = Math.max(bounds[2] - pz, Math.max(0, pz - bounds[5]));
            return dx * dx + dy * dy + dz * dz;
        }

        boolean visible(HologramRegionCache.Key key) {
            if (distance(key) > range * range) return false;
            double[] bounds = bounds(key);
            return frustum.isBoundingBoxInFrustum(
                AxisAlignedBB.getBoundingBox(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
        }
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

    private static boolean compileWorldGroup(WorldGroup group, boolean removal, boolean light, long deadline) {
        int list = GL11.glGenLists(1);
        if (list == 0) return false;
        int beforeGeometry = lastWorldGeometry, beforeGT = lastWorldGTGeometry, beforeFallback = lastWorldFallback,
            beforeGhosts = lastWorldGhosts;
        int start = light ? group.lightBuilt : group.built;
        int limit = Math.min(start + WORLD_MESH_BATCH, group.cells.size()), end = start;
        List<HologramState.Cell> cells = new ArrayList<>();
        // Native lists and material callbacks must finish before opening the group's list/tessellator.
        // The deadline is checked per cell, rather than after baking a whole 256-cell native batch.
        while (end < limit && (end == start || System.nanoTime() < deadline)) {
            HologramState.Cell cell = group.cells.get(end++);
            if (HologramJobVisuals.completed(worldState, cell)) continue;
            cells.add(cell);
            if (!worldGhosts.contains(coordinate(cell.x, cell.y, cell.z))) continue;
            if (light || !HologramSnapshotModelsClient.ready(worldState, cell, false))
                HologramLightPreview.material(cell, false);
            else {
                HologramBlockRender.ready(cell, false);
                HologramTextures.material(cell, false);
                HologramLinkNodePreview.prepare(cell, false);
            }
        }
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        t.startDrawingQuads();
        for (HologramState.Cell c : cells) {
            if (!worldGhosts.contains(coordinate(c.x, c.y, c.z))) continue;
            boolean modelReady = !light && HologramSnapshotModelsClient.ready(worldState, c, false);
            if (modelReady && HologramBlockRender.ready(c, false)) {
                lastWorldGhosts++;
                continue;
            }
            if (HologramBlockRender.deferred(c, false)) modelReady = false;
            HologramTextures.Material material = !modelReady ? HologramLightPreview.material(c, false)
                : HologramTextures.material(c, false);
            lastWorldGhosts++;
            if (material.gregTech) lastWorldGTGeometry++;
            if (material.fallback || HologramNativeTiles.supported(c.block(false))) lastWorldFallback++;
            for (HologramGeometry.Surface surface : material.geometry) {
                int side = surface.side;
                int[] normal = NORMALS[side];
                if (!light && !material.partial
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
        for (HologramState.Cell c : cells) if (!light && worldGhosts.contains(coordinate(c.x, c.y, c.z))
            && HologramSnapshotModelsClient.ready(worldState, c, false)
            && HologramBlockRender.ready(c, false)) {
                GL11.glPushMatrix();
                GL11.glTranslated(c.x, c.y, c.z);
                HologramBlockRender.draw(c, false);
                GL11.glPopMatrix();
            }
        GL11.glPopAttrib();
        for (HologramState.Cell c : cells) if (!light && worldGhosts.contains(coordinate(c.x, c.y, c.z))
            && HologramSnapshotModelsClient.ready(worldState, c, false)) {
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
        if (light) {
            group.lightLists.add(list);
            group.lightBuilt = end;
            lastWorldGeometry = beforeGeometry;
            lastWorldGTGeometry = beforeGT;
            lastWorldFallback = beforeFallback;
            lastWorldGhosts = beforeGhosts;
            return true;
        }
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
        final List<Integer> lists = new ArrayList<>(), lightLists = new ArrayList<>();
        int built, lightBuilt, modelCursor;
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
        if (cell.modelPending(current)) return false;
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
            this(cell, points, side, current, surface, true);
        }

        Face(HologramState.Cell cell, double[][] points, int side, boolean current, HologramGeometry.Surface surface,
            boolean detailed) {
            this.cell = cell;
            this.points = points;
            this.side = side;
            this.surface = surface;
            empty = !cell.anchor && !cell.iconOnly && (current ? cell.id : cell.wantId).equals("minecraft:air");
            nativeModel = detailed && !empty && HologramBlockRender.ready(cell, current);
            material = empty ? null
                : detailed ? HologramTextures.material(cell, current) : HologramLightPreview.material(cell, current);
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
