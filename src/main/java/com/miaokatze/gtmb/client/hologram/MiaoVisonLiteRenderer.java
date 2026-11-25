// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import com.miaokatze.gtmb.hologram.HologramPreviewPose;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteDisplayList;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteMaterialBatch;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteMesh;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteOutline;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteSession;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteSpatialIndex;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteSubmission;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteTarget;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteTopology;
import com.miaokatze.miaokatzecore.client.render.ScreenLineStream;

import appeng.client.texture.FlippableIcon;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Cached static schematic geometry; rendering never ticks machines or reads live world cells. */
final class MiaoVisonLiteRenderer implements AutoCloseable {

    private static final int VOXEL_COORDINATES = 8 * 3;
    private static final int[][] FACES = { { 0, 1, 5, 4 }, { 3, 7, 6, 2 }, { 0, 3, 2, 1 }, { 4, 5, 6, 7 },
        { 0, 4, 7, 3 }, { 1, 2, 6, 5 } };
    private static final int[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };
    private static final int[][] EDGES = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 },
        { 7, 4 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };
    private static final double[][] VERTICES = { { -.5, -.5, -.5 }, { .5, -.5, -.5 }, { .5, .5, -.5 }, { -.5, .5, -.5 },
        { -.5, -.5, .5 }, { .5, -.5, .5 }, { .5, .5, .5 }, { -.5, .5, .5 } };

    enum View {
        INTERACTIVE,
        LIBRARY,
        CAPTURE
    }

    private final boolean browseViewport, cleanCapture;
    private final HologramPreviewAssets assets;
    private final MiaoVisonLiteSession<HologramRegionCache.Key, PreviewRegion> session = new MiaoVisonLiteSession<>();

    MiaoVisonLiteRenderer(HologramPreviewAssets assets, View view) {
        this.assets = assets;
        browseViewport = view == View.LIBRARY;
        cleanCapture = view == View.CAPTURE;
    }

    HologramPreviewAssets assets() {
        return assets;
    }

    MiaoVisonLiteTarget target(int width, int height) {
        return session.target(width, height);
    }

    static int lastWorldGeometry, lastWorldGTGeometry, lastWorldFallback, lastWorldMatched, lastWorldGhosts;
    static int lastWorldPlacementOutlineColor;
    private static WorldRenderWitness worldRenderWitness;
    private static long worldRenderGeneration;
    int lastPreviewRoleMarkers, lastPreviewAnchorMarkers;
    private static HologramState worldState;
    private HologramState previewState;
    private String previewKey = "";
    private List<Face> cachedFaces = new ArrayList<>();
    private PreviewSubmission previewSubmission;
    private List<Face> hoverFaces;
    private MaterialPresentation materialPresentation;
    private long materialSerial;
    private int hoverX, hoverY, hoveredCell = -1;
    HologramState controllerSitePickTarget;
    static long previewSliceCaptures, previewSliceHits, previewSliceReplayedQuads, previewSliceUnsupported,
        previewSliceFallbackFrames;
    static long previewSliceCapturedInts;
    static long previewResidentCaptures, previewResidentDraws;
    static long previewDirectMeshUploads, previewDirectMeshDraws;
    static long worldDirectMeshUploads, worldDirectMeshDraws;
    static long previewCameraReprojects, previewCameraOrderReuses, previewCameraOrderSorts;
    static long previewBrowseSurfaceReuses;
    static long previewGeometryPublications, previewWorkerCompletedRuns, previewWorkerSpecialFallbacks;
    static long previewWorkerProjectedCells, previewBoundaryOverlapFrames;
    static long previewHoverScans;
    private static final PreviewTimings PREVIEW_TIMINGS = Boolean.getBoolean("gtmb.testlab.renderParity")
        ? new PreviewTimings()
        : null;

    private void invalidatePreviewDrawCache() {
        session.replaceSubmission(null);
        previewSubmission = null;
        hoverFaces = null;
    }

    @com.github.bsideup.jabel.Desugar
    record MaterialPresentation(HologramState state, long serial, long drawnAtNanos, int submittedCells,
        boolean complete, boolean bodyMaterial) {}

    MaterialPresentation materialPresentation() {
        return materialPresentation;
    }

    private static boolean materialBody(HologramState.Cell cell) {
        return !cell.anchor && !cell.iconOnly && !cell.missingBlock && !cell.sourceMissing;
    }

    private static boolean bodyFaces(List<Face> faces) {
        for (Face face : faces) if (!face.empty && !face.nativeModel
            && materialBody(face.cell)
            && !face.samples()
                .isEmpty())
            return true;
        return false;
    }

    private int previewHovered(List<Face> faces, int mouseX, int mouseY, boolean picking) {
        if (!picking) {
            hoverFaces = null;
            return -1;
        }
        if (hoverFaces == faces && hoverX == mouseX && hoverY == mouseY) return hoveredCell;
        Face face = nearestFace(faces, mouseX, mouseY, true);
        int hovered = face == null ? -1 : face.cell.index;
        hoverFaces = faces;
        hoverX = mouseX;
        hoverY = mouseY;
        hoveredCell = hovered;
        if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled()) previewHoverScans++;
        return hovered;
    }

    private List<Face> cachedOutlines = new ArrayList<>();
    private final FloatBuffer OUTLINE_MATRIX = BufferUtils.createFloatBuffer(16);
    private final IntBuffer OUTLINE_VIEWPORT = BufferUtils.createIntBuffer(16);
    private final List<HologramState.Cell> nativePreviewCells = new ArrayList<>();
    private HologramState visibleState;
    private int visibleLayer, visibleView, visibleMode;
    private int visibleCellCount;
    private final Map<HologramRegionCache.Key, PreviewRegion> previewRegions = session.regions();
    private static final long PREVIEW_BUILD_NANOS = 2_000_000L;
    private static final long LARGE_BROWSE_BUILD_NANOS = 8_000_000L;
    private static final int LARGE_BROWSE_CELLS = 32_768;
    private PreviewPreparation previewPreparation;
    private final List<HologramState.Cell> linkedPreviewCells = new ArrayList<>();
    static int lastPreviewBuiltRegions, lastSnapshotDirtyRegions;
    private List<RoleMarker> cachedMarkers = new ArrayList<>();
    private double cachedExtent = 1;
    private static int worldLayer = Integer.MAX_VALUE;
    private static final long WORLD_BUILD_NANOS = 3_000_000L;
    private static final int WORLD_MESH_BATCH = 256;
    private static final int LOCAL_WORLD_MIN_CELLS = 32_768, EAGER_REGION_DIRECTORY = 1024;
    private static final MiaoVisonLiteSession<Coordinate, WorldGroup> WORLD_SESSION = new MiaoVisonLiteSession<>();
    private static final Map<Coordinate, WorldGroup> worldGroups = WORLD_SESSION.regions();
    private static final MiaoVisonLiteSpatialIndex<WorldGroup> worldSpatialIndex = new MiaoVisonLiteSpatialIndex<>(16);
    private static MiaoVisonLiteDisplayList placementColorList;
    private static int placementColor;
    private static final Set<Coordinate> worldGhosts = new HashSet<>();
    private static final Set<Coordinate> worldOccluders = new HashSet<>();
    private static final Set<Coordinate> worldOutlineCells = new HashSet<>();
    private static int worldIndex;
    private static boolean localWorldRegions;
    private static java.util.Iterator<Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>>> pendingRegions;
    private static long registryGeneration;

    private HologramState boundsState;
    private double boundsX, boundsY, boundsZ, boundsExtent;
    int[] moveOffset = new int[3];

    private boolean moving(HologramState.Cell cell) {
        return (moveOffset[0] != 0 || moveOffset[1] != 0 || moveOffset[2] != 0) && selection != null
            && selection.contains(cell.index);
    }

    boolean showEmptyCells;
    HologramSelection selection;
    String selectedStructureId = "";
    private HologramEditorCameraFrame projectedFrame, presentedFrame;
    private PreviewRequest projectedRequest, presentedRequest;
    private boolean cameraOnlyProjection, previewCameraMoving;
    private HologramState presentedState;
    private HologramState displayedState;
    private boolean previewSubmitted;
    private final java.util.BitSet publishedPreviewCells = new java.util.BitSet();
    private final Map<String, double[][]> structureBoxes = new LinkedHashMap<>();
    private final Set<String> missingStructures = new HashSet<>();
    private final Map<String, double[][]> hintBoxes = new LinkedHashMap<>();
    private HologramState structureBoundsState;
    // Borrowed from the current snapshot on the native path; never mutate or copy its spatial index.
    private Map<String, int[]> cachedStructureBounds = java.util.Collections.emptyMap();
    private boolean visibleEmptyCells;

    @Override
    public void close() {
        materialPresentation = null;
        releasePreview();
        assets.clear();
    }

    static boolean ownsWorldProjection(HologramState state) {
        return sameWorldProjection(state, worldState);
    }

    static boolean sameWorldProjection(HologramState state, HologramState owner) {
        if (state == null || owner == null) return false;
        if (state == owner) return true;
        if (state.data.getBoolean("localPreview") || owner.data.getBoolean("localPreview")
            || !state.session.equals(owner.session)
            || !state.data.getString("generation")
                .equals(owner.data.getString("generation")))
            return false;
        for (String key : new String[] { "planRevision", "placementReviewNonce", "editorWorkspaceEpoch", "dimension",
            "x", "y", "z" }) if (state.data.getLong(key) != owner.data.getLong(key)) return false;
        return true;
    }

    static void releaseWorld() {
        worldRenderWitness = null;
        clearWorldGroups();
        worldState = null;
    }

    private void releasePreview() {
        HologramState released = visibleState != null ? visibleState : structureBoundsState;
        int groups = cachedStructureBounds.size();
        projectedFrame = presentedFrame = null;
        projectedRequest = presentedRequest = null;
        cameraOnlyProjection = previewCameraMoving = false;
        presentedState = null;
        controllerSitePickTarget = null;
        displayedState = null;
        previewSubmitted = false;
        publishedPreviewCells.clear();
        previewState = null;
        boundsState = null;
        nativePreviewCells.clear();
        previewSubmission = null;
        hoverFaces = null;
        cachedFaces = new ArrayList<>();
        cachedMarkers = new ArrayList<>();
        cachedOutlines = new ArrayList<>();
        visibleState = null;
        visibleCellCount = 0;
        installPreviewPreparation(null);
        session.close();
        linkedPreviewCells.clear();
        structureBoxes.clear();
        missingStructures.clear();
        hintBoxes.clear();
        structureBoundsState = null;
        cachedStructureBounds = java.util.Collections.emptyMap();
        if (released != null && com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.isDebugEnabled()) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.debug(
                "GTMB preview.release view={} native={} revision={} request={} cells={} groups={}",
                browseViewport ? "library" : cleanCapture ? "capture" : "editor",
                released.nativeViewport != null,
                released.data.getLong("planRevision"),
                released.data.getLong("uiSequence"),
                released.cells.size(),
                groups);
        }
    }

    /** Preserve detached geometry across a validated delta; teardown still owns world/session and reload changes. */
    void snapshotChanged(HologramState previous, HologramState next, Set<HologramRegionCache.Key> dirty) {
        invalidatePreviewDrawCache();
        lastSnapshotDirtyRegions = dirty.size();
        for (HologramRegionCache.Key key : dirty) previewRegions.remove(key);
        if (visibleState == previous) visibleState = next;
        boolean extentChanged = next.topologyChanged
            && !java.util.Arrays.equals(previous.regions.bounds(), next.regions.bounds());
        if (boundsState == previous) boundsState = extentChanged ? null : next;
        if (next.topologyChanged) {
            structureBoundsState = null;
            installPreviewPreparation(null);
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
        advanceAssets(previous, next, dirty, assets);
        if (visibleState == next) installPreviewPreparation(new PreviewPreparation(next, false));
    }

    static void worldSnapshotChanged(HologramState previous, HologramState next, Set<HologramRegionCache.Key> dirty,
        HologramPreviewAssets assets) {
        if (worldState == previous) {
            if (localWorldRegions) {
                // A validated patch owns a new authoritative region directory; a mere status
                // update usually shares the exact old one. Never restart an all-cell world index.
                boolean follows = next.follows(previous) && !localRenderMetadataChanged(previous, next);
                if (!follows || previous.regions != next.regions
                    || next.changedIndices.length != 0
                    || !next.changedPreviousCells.isEmpty()) {
                    clearWorldGroups();
                    if (follows) localWorldRegions = seedLocalWorldRegions(next, worldLayer, true);
                }
            } else if (worldIndex != previous.cells.size()) {
                // A metadata-only publication shares the unfinished occupancy index, including its cursor.
                if (next.changedIndices.length != 0 || !next.changedPreviousCells.isEmpty()) clearWorldGroups();
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
        // World batches must be retired before the native lists they reference.
        advanceAssets(previous, next, dirty, assets);
    }

    private static void advanceAssets(HologramState previous, HologramState next, Set<HologramRegionCache.Key> dirty,
        HologramPreviewAssets assets) {
        try (HologramPreviewAssets.Scope ignored = assets.enter()) {
            HologramBlockRender.advance(previous, next, dirty);
            HologramTextures.advance(previous, next);
            HologramLinkNodePreview.advance(previous, next);
        }
    }

    /** Keep the exact frame and any unfinished render cursors when the local publication only adds editor details. */
    void retainLocalPresentation(HologramState previous, HologramState next) {
        if (visibleState != previous) return;
        visibleState = next;
        if (previewState == previous) previewState = next;
        if (presentedState == previous) presentedState = next;
        if (displayedState == previous) displayedState = next;
        if (boundsState == previous) boundsState = next;
        if (structureBoundsState == previous) structureBoundsState = next;
        if (projectedRequest != null && projectedRequest.state == previous)
            projectedRequest = projectedRequest.forState(next);
        if (presentedRequest != null && presentedRequest.state == previous)
            presentedRequest = presentedRequest.forState(next);
        if (previewPreparation != null && previewPreparation.state == previous) {
            previewPreparation.state = next;
            if (previewPreparation.projection != null) previewPreparation.projection.state = next;
            if (previewPreparation.boundary != null) previewPreparation.boundary.state = next;
        }
        advanceAssets(previous, next, java.util.Collections.emptySet(), assets);
    }

    static boolean renderMetadataChanged(HologramState previous, HologramState next) {
        String[] keys = { "mode", "virtual", "virtualPlacement", "x", "y", "z", "dimension" };
        for (String key : keys) {
            Object before = previous.data.getTag(key), after = next.data.getTag(key);
            if (!java.util.Objects.equals(before, after)) return true;
        }
        return false;
    }

    static boolean localRenderMetadataChanged(HologramState previous, HologramState next) {
        if (renderMetadataChanged(previous, next)) return true;
        for (String key : new String[] { "structureProjection", "blueprintEditing", "displayPartial", "localPreview",
            "moving", "hintAreas" }) {
            if (!java.util.Objects.equals(previous.data.getTag(key), next.data.getTag(key))) return true;
        }
        return false;
    }

    int preview(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
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

    double previewExtent() {
        return boundsExtent;
    }

    boolean previewMeshReady(HologramState state) {
        return state != null && previewState == state
            && previewPreparation != null
            && previewPreparation.state == state
            && previewPreparation.completed == state.cells.size()
            && previewPreparation.projection != null
            && previewPreparation.projection.published == state.cells.size()
            && previewSubmitted;
    }

    HologramState previewDisplayState() {
        return displayedState;
    }

    /** Edit admission compares the live UI request with the complete frame actually drawn, without allocating a key. */
    boolean previewPresented(HologramState state, int x, int y, int width, int height, double yaw, double pitch,
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
                cleanCapture);
    }

    void sceneClosed() {
        if (previewPreparation != null) previewPreparation.closeDetached();
        invalidatePreviewDrawCache();
        session.clearPresentation();
        displayedState = null;
        materialPresentation = null;
        presentedState = null;
        presentedFrame = null;
        presentedRequest = null;
        previewSubmitted = false;
    }

    private void installPreviewPreparation(PreviewPreparation preparation) {
        if (previewPreparation != null) previewPreparation.closeDetached();
        previewPreparation = preparation;
    }

    void cancelBrowseTopology(HologramState state) {
        if (previewPreparation != null && previewPreparation.state == state) previewPreparation.closeDetached();
    }

    /** Releases a retired oversized library scene and its exact material/native snapshot. */
    void releaseInactiveBrowse(HologramState state) {
        if (owns(state)) close();
    }

    boolean owns(HologramState state) {
        return state != null && (visibleState == state || previewState == state
            || previewPreparation != null && previewPreparation.state == state);
    }

    boolean previewMeshVisible(HologramState state) {
        return state != null && previewState == state
            && (!cachedFaces.isEmpty() || !nativePreviewCells.isEmpty()
                || previewPreparation != null && previewPreparation.boundary != null
                    && previewPreparation.boundary.presented);
    }

    int previewMeshCompleted(HologramState state) {
        return previewPreparation != null && previewPreparation.state == state ? previewPreparation.completed : 0;
    }

    static int previewMeshTotal(HologramState state) {
        return state == null ? 0 : state.cells.size();
    }

    /** Detached counters from the frame actually presented for this exact snapshot. No native factory or draw. */
    net.minecraft.nbt.NBTTagCompound lessonInspection(HologramState state) {
        net.minecraft.nbt.NBTTagCompound result = new net.minecraft.nbt.NBTTagCompound();
        boolean presented = state != null && presentedState == state && presentedFrame != null;
        result.setBoolean("snapshotPresented", presented);
        java.util.Set<Integer> geometryCells = new java.util.HashSet<>();
        if (presented) for (Face face : cachedFaces) geometryCells.add(face.cell.index);
        result.setInteger("renderedCellCount", geometryCells.size());
        result.setInteger("presentedVisibleCellCount", presented ? visibleCellCount : 0);
        result.setInteger("nativeModelCellCount", presented ? nativePreviewCells.size() : 0);
        result.setInteger("roleMarkerCount", presented ? lastPreviewRoleMarkers : 0);
        result.setInteger("anchorMarkerCount", presented ? lastPreviewAnchorMarkers : 0);
        result.setBoolean("worldMutation", false);
        result.setBoolean("playerMutation", false);
        return result;
    }

    int preview(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
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

    int preview(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
        double panX, double panY, double panZ, int layer, int view, int selected, int mouseX, int mouseY,
        double rehearsal) {
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
            panY,
            panZ,
            layer,
            view,
            selected,
            mouseX,
            mouseY,
            rehearsal,
            true);
    }

    int preview(HologramState state, int x, int y, int width, int height, double yaw, double pitch, double zoom,
        double panX, double panY, double panZ, int layer, int view, int selected, int mouseX, int mouseY,
        double rehearsal, boolean allowCapture) {
        if (session.contextChanged()) close();
        try (HologramPreviewAssets.Scope ignored = assets.enter()) {
            return drawPreview(
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
                selected,
                mouseX,
                mouseY,
                rehearsal,
                allowCapture);
        }
    }

    private int drawPreview(HologramState state, int x, int y, int width, int height, double yaw, double pitch,
        double zoom, double panX, double panY, double panZ, int layer, int view, int selected, int mouseX, int mouseY,
        double rehearsal, boolean allowCapture) {
        if (width <= 0 || height <= 0 || !Double.isFinite(zoom) || zoom <= 0) {
            presentedFrame = null;
            presentedState = null;
            presentedRequest = null;
            return -1;
        }
        if (PREVIEW_TIMINGS != null) PREVIEW_TIMINGS.begin(state.identity);
        HologramTextures.begin(state);
        HologramBlockRender.beginViewport(state);
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
            + cleanCapture;
        boolean budgeted = !cleanCapture;
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
            installPreviewPreparation(budgeted ? new PreviewPreparation(state, true) : null);
        }
        lastPreviewBuiltRegions = 0;
        // The stamp belongs to this preparation, not merely to its cell count: a cold/dirty final slice may also be N.
        cameraOnlyProjection = budgeted && preparedCameraOnly(state, key, layer, view, showEmptyCells);
        boolean cameraChanged = projectedRequest != null && projectedRequest.state == state
            && !projectedRequest.sameCamera(x, y, width, height, yaw, pitch, zoom, panX, panY, panZ);
        previewCameraMoving = !browseViewport && cameraChanged;
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
                installPreviewPreparation(new PreviewPreparation(state, true));
            prepareProgressiveFrame(state, key, x, y, width, height, yaw, pitch, zoom, panX, panY, panZ);
            if (cameraOnlyProjection) {
                // All input regions/materials/native models are already prepared. Publish only the complete current
                // camera, before any draw, without spending another budget on an occupancy prefix we will not show.
                previewPreparation.projection.advance(currentView, Integer.MAX_VALUE, Long.MAX_VALUE);
                previewPreparation.projection.publish();
            } else if (previewPreparation.completed != state.cells.size()
                || previewPreparation.projection.published != state.cells.size())
                previewPreparation.advance(
                    layer,
                    view,
                    currentView,
                    key,
                    (browseViewport || previewPreparation.localBlueprint) && !cameraChanged
                        && allowCapture
                        && !state.data.getBoolean("displayPartial")
                        && state.cells.size() >= LARGE_BROWSE_CELLS ? LARGE_BROWSE_BUILD_NANOS : PREVIEW_BUILD_NANOS);
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
            visibleCellCount = 0;
            linkedPreviewCells.clear();
            for (PreviewRegion region : previewRegions.values()) {
                visibleCellCount += region.visibleCount;
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
                    double[] projected = new double[VOXEL_COORDINATES];
                    projectedFrame.projectVoxel(c.dx, c.dy, c.dz, projected, 0);
                    if (!cleanCapture && !role.isEmpty() && (c.anchor || markers.size() - firstMarker < 64))
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
                        double[] points = projected;
                        if (material.partial) {
                            points = new double[VOXEL_COORDINATES];
                            projectPreviewSurface(points, surface, projected);
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
        if (PREVIEW_TIMINGS != null) PREVIEW_TIMINGS.mark(0);
        lastPreviewRoleMarkers = lastPreviewAnchorMarkers = 0;
        int hovered = -1;
        boolean measureMaterial = budgeted && HologramRuntimeMetrics.isEnabled();
        boolean bodyMaterial = false;
        long materialAt = 0;
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
            if (assets == HologramPreviewAssets.EDITOR) com.miaokatze.gtmb.main.OptionalTestLab
                .call("com.miaokatze.gtmb.client.hologram.HologramSourcePreviewReference", "captureCamera", depthScale);
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
            if (PREVIEW_TIMINGS != null) PREVIEW_TIMINGS.mark(1);
            hovered = previewHovered(faces, mouseX, mouseY, picking);
            if (PREVIEW_TIMINGS != null) PREVIEW_TIMINGS.mark(2);
            boolean displaced = (moveOffset[0] != 0 || moveOffset[1] != 0 || moveOffset[2] != 0) && selection != null
                && selection.size() > 0;
            // Angelica 2.2.28 also uses this vanilla GUI layout; its append-side extra state belongs to terrain
            // meshing.
            // DirectTessellator and drag transforms retain their original commands and active-format batch limit.
            if (!displaced && !previewCameraMoving && t.getClass() == Tessellator.class) {
                boolean snapshotUvs = budgeted && state.data.getBoolean("blueprintEditing")
                    && !browseViewport
                    && previewPreparation.completed == state.cells.size()
                    && previewPreparation.projection.published == state.cells.size();
                if (previewSubmission == null || previewSubmission.faces != faces
                    || previewSubmission.depthScale != depthScale
                    || previewSubmission.snapshotUvs != snapshotUvs) {
                    previewSubmission = new PreviewSubmission(faces, depthScale, snapshotUvs);
                    session.replaceSubmission(previewSubmission.submission);
                }
                // A published prefix will be replaced at the next milestone. Record only geometry that has already
                // been presented as the complete source, keeping the original CPU commands available while loading.
                boolean allowResident = budgeted && previewPreparation.completeGeometryPresented
                    && previewPreparation.projection.published == state.cells.size()
                    && !state.data.getBoolean("displayPartial");
                materialDrawn = previewSubmission.draw(t, allowCapture, allowResident);
            } else {
                if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled()) previewSliceFallbackFrames++;
                MiaoVisonLiteMaterialBatch.count("live-material-pass");
                materialDrawn = drawPreviewQuads(
                    t,
                    faces,
                    depthScale,
                    moveX,
                    moveY,
                    budgeted && t.getClass() == Tessellator.class);
            }
            if (measureMaterial && materialDrawn) bodyMaterial = bodyFaces(faces);
            if (PREVIEW_TIMINGS != null) PREVIEW_TIMINGS.mark(3);
            HologramRasterBridge.Frame raster = HologramRasterBridge
                .begin(state, view == 1 || state.data.getInteger("mode") == 2);
            for (HologramState.Cell cell : nativePreviewCells) {
                Object pixels = null;
                if (raster != null) for (Face outline : cachedOutlines) if (outline.cell == cell) {
                    pixels = raster.before(outlinePoints(outline.points), depthScale);
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
                    > 0) {
                    materialDrawn = true;
                    if (measureMaterial && materialBody(cell)) bodyMaterial = true;
                }
                GL11.glPopAttrib();
                GL11.glPopMatrix();
                if (pixels != null) raster.after(cell, pixels);
            }
            HologramRasterBridge.finish(raster);
            for (HologramState.Cell cell : linkedPreviewCells) {
                boolean current = view == 1 || state.data.getInteger("mode") == 2;
                if (HologramLinkNodePreview.prepare(cell, current)
                    .isEmpty()) continue;
                GL11.glPushMatrix();
                applyPreviewTransform(cell, depthScale);
                HologramLinkNodePreview.draw(cell, current, .96f);
                GL11.glPopMatrix();
            }
            if (measureMaterial && materialDrawn) materialAt = System.nanoTime();
            if (PREVIEW_TIMINGS != null) PREVIEW_TIMINGS.mark(4);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            // Read face depth without writing line depth. The small z bias avoids coplanar
            // flicker while keeping back edges hidden. Dense ordinary grids yield to their materials.
            GL11.glDepthMask(false);
            int boundaryVertices = budgeted && !previewMeshReady(state) ? drawPreviewBoundary(state, false, depthScale)
                : 0;
            if (PREVIEW_TIMINGS != null) PREVIEW_TIMINGS.mark(5);
            if (materialDrawn && budgeted && !previewPreparation.materialObserved) {
                previewPreparation.materialObserved = true;
                com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.loadStage(
                    com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.LoadStage.CLIENT_FIRST_MATERIAL,
                    state.data,
                    -1,
                    previewPreparation.projection.published);
            }
            float outlineDensity = cleanCapture || cachedOutlines.isEmpty() ? 1 : previewOutlineDensity();
            int lineVertices = 0, lineLimit = 0;
            int outlinedCells = 0;
            double[] cornerDepths = new double[8];
            boolean outlineDrawn = false;
            boolean streamedLines = false;
            ScreenLineStream previewLineStream = null;
            if (!cleanCapture && !browseViewport) {
                previewLineStream = session.lines();
                streamedLines = previewLineStream.begin(t);
            }
            MiaoVisonLiteOutline residentOutline = !streamedLines && !cleanCapture && !cachedOutlines.isEmpty()
                ? session.outline()
                : null;
            int firstOutline = 0;
            if (residentOutline != null) {
                firstOutline = residentOutline.drawPrefix(
                    t,
                    state.identity,
                    projectedFrame,
                    cachedOutlines,
                    cachedOutlines.size(),
                    selection,
                    selection == null ? 0 : selection.version(),
                    selected,
                    outlineDensity,
                    depthScale,
                    allowCapture);
                int replayed = residentOutline.replayedVertices();
                if (replayed != 0) {
                    outlinedCells = replayed / 24;
                    outlineDrawn = true;
                    lineLimit = previewLineBatchLimit(t);
                }
            }
            try {
                if (!streamedLines) t.startDrawing(GL11.GL_LINES);
                if (selection != null) selection.related(state);
                if (!cleanCapture) for (int at = firstOutline; at < cachedOutlines.size(); at++) {
                    Face face = cachedOutlines.get(at);
                    boolean chosen = face.cell.index == selected
                        || selection != null && selection.contains(face.cell.index);
                    boolean relatedCasing = !chosen && selection != null
                        && selection.relatedContains(state, face.cell.index);
                    float alpha = previewOutlineAlpha(
                        chosen,
                        relatedCasing,
                        face.empty,
                        face.fadeDefault,
                        outlineDensity);
                    if (alpha == 0) continue;
                    outlineDrawn = true;
                    if (lineLimit > 0 && lineVertices + 24 > lineLimit) {
                        if (streamedLines) previewLineStream.flush();
                        else {
                            if (residentOutline == null || !residentOutline.record(t, lineVertices, at)) t.draw();
                            t.startDrawing(GL11.GL_LINES);
                        }
                        lineVertices = 0;
                    }
                    float red = chosen ? 1 : relatedCasing ? .78f : .2f,
                        green = chosen ? .92f : relatedCasing ? .61f : .7f,
                        blue = chosen ? .5f : relatedCasing ? 1 : .8f;
                    if (streamedLines) previewLineStream.color(red, green, blue, alpha);
                    else t.setColorRGBA_F(red, green, blue, alpha);
                    for (int corner = 0; corner < cornerDepths.length; corner++)
                        cornerDepths[corner] = face.points[corner * 3 + 2] * depthScale + .15;
                    for (int[] edge : EDGES) {
                        int a = edge[0] * 3, b = edge[1] * 3;
                        if (streamedLines) previewLineStream.line(
                            face.points[a],
                            face.points[a + 1],
                            cornerDepths[edge[0]],
                            face.points[b],
                            face.points[b + 1],
                            cornerDepths[edge[1]]);
                        else {
                            t.addVertex(face.points[a], face.points[a + 1], cornerDepths[edge[0]]);
                            t.addVertex(face.points[b], face.points[b + 1], cornerDepths[edge[1]]);
                        }
                    }
                    if (lineLimit == 0)
                        lineLimit = streamedLines ? ScreenLineStream.MAX_VERTICES : previewLineBatchLimit(t);
                    lineVertices += 24;
                    if (PREVIEW_TIMINGS != null) outlinedCells++;
                }
                if (!streamedLines && (residentOutline == null || lineLimit == 0
                    || lineVertices != lineLimit
                    || !residentOutline.record(t, lineVertices, cachedOutlines.size()))) t.draw();
                if (residentOutline != null && lineVertices == 0) residentOutline.restoreEmptyTail(t);
            } finally {
                if (streamedLines) previewLineStream.end();
            }
            if (PREVIEW_TIMINGS != null) {
                PREVIEW_TIMINGS.mark(6);
                PREVIEW_TIMINGS.outline(
                    outlineDensity,
                    GL11.glGetFloat(GL11.GL_LINE_WIDTH),
                    outlinedCells * 24,
                    streamedLines,
                    boundaryVertices,
                    residentOutline);
            }
            if (budgeted && outlineDrawn && !previewPreparation.outlineObserved) {
                previewPreparation.outlineObserved = true;
                com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.loadStage(
                    com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.LoadStage.CLIENT_OUTLINE_READY,
                    state.data,
                    -1,
                    cachedOutlines.size());
            }
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glLineWidth(2.5f);
            lineVertices = 0;
            t.startDrawing(GL11.GL_LINES);
            String hoveredStructure = pickStructure(mouseX, mouseY);
            if (!cleanCapture) for (Map.Entry<String, double[][]> entry : structureBoxes.entrySet()) {
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
                if (lineLimit == 0) lineLimit = previewLineBatchLimit(t);
                lineVertices += 24;
            }
            t.draw();
            GL11.glLineWidth(1.5f);
            lineVertices = 0;
            t.startDrawing(GL11.GL_LINES);
            if (!cleanCapture) for (double[][] box : hintBoxes.values()) {
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
                if (lineLimit == 0) lineLimit = previewLineBatchLimit(t);
                lineVertices += 24;
            }
            t.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);
            if (!cleanCapture) for (RoleMarker marker : markers) {
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
        if (PREVIEW_TIMINGS != null) PREVIEW_TIMINGS.mark(7);
        presentedFrame = projectedFrame;
        presentedState = state;
        presentedRequest = projectedRequest;
        displayedState = !cachedFaces.isEmpty() || !cachedOutlines.isEmpty()
            || !structureBoxes.isEmpty()
            || HologramBlockRender.dataOnly(state, currentView)
            || previewPreparation != null && previewPreparation.boundary != null
                && previewPreparation.boundary.presented ? state : null;
        previewSubmitted = !budgeted || previewPreparation.projection.published == state.cells.size();
        if (materialAt != 0) {
            boolean complete = previewSubmitted && !state.data.getBoolean("displayPartial");
            materialPresentation = new MaterialPresentation(
                state,
                ++materialSerial,
                complete ? System.nanoTime() : materialAt,
                previewPreparation.projection.published,
                complete,
                bodyMaterial);
        }
        if (budgeted && previewSubmitted && !previewPreparation.completeGeometryPresented) {
            previewPreparation.completeGeometryPresented = true;
            if (previewPreparation.boundary != null) previewPreparation.boundary.discardProjectedPoints();
        }
        if (PREVIEW_TIMINGS != null)
            PREVIEW_TIMINGS.end(previewSubmitted, budgeted ? previewPreparation.boundary : null);
        if (budgeted && previewSubmitted && !previewPreparation.readyObserved) {
            previewPreparation.readyObserved = true;
            if (!browseViewport && state.data.getBoolean("localStandardController") && !state.cells.isEmpty())
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info(
                    "Controller preview stage=render-ready generation={} view={} scope={} cells={} prepared={} published={} visible={} faces={} nativeModels={} elapsedMs={}",
                    state.data.getString("generation"),
                    view,
                    state.data.getBoolean("controllerSiteDisplay") ? "installed" : "target",
                    state.cells.size(),
                    previewPreparation.completed,
                    previewPreparation.projection.published,
                    visibleCellCount,
                    cachedFaces.size(),
                    nativePreviewCells.size(),
                    (System.nanoTime() - previewPreparation.startedAt) / 1_000_000L);
            com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.loadStage(
                com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.LoadStage.CLIENT_RENDER_READY,
                state.data,
                -1,
                previewPreparation.projection.published);
        }
        return hovered;
    }

    static Map<String, Number> previewTiming() {
        return PREVIEW_TIMINGS == null ? java.util.Collections.emptyMap() : PREVIEW_TIMINGS.snapshot();
    }

    /** Render-thread measurements collected only by the opt-in paired client probe. */
    private static final class PreviewTimings {

        private static final String[] STAGES = { "preparation", "glSetup", "hover", "materials", "nativeModels",
            "boundary", "outline", "overlays" };
        private final long[] elapsed = new long[STAGES.length], prefixTotals = new long[STAGES.length];
        private Object source;
        private long clock, prefixFrames, frames;
        private long prefixReplayedOutlineVertices, prefixRecordedOutlineBatches;
        private int outlineVertices, boundaryVertices, streamed;
        private int replayedOutlineVertices, recordedOutlineBatches;
        private long residentOutlineBytes;
        private long boundaryProjectionBytes, peakBoundaryProjectionBytes;
        private float outlineDensity, lineWidth;

        void begin(Object next) {
            if (source != next) {
                source = next;
                prefixFrames = frames = 0;
                prefixReplayedOutlineVertices = prefixRecordedOutlineBatches = 0;
                java.util.Arrays.fill(prefixTotals, 0);
            }
            clock = System.nanoTime();
        }

        void mark(int stage) {
            long now = System.nanoTime();
            elapsed[stage] = now - clock;
            clock = now;
        }

        void outline(float density, float width, int vertices, boolean stream, int boundary,
            MiaoVisonLiteOutline cache) {
            outlineDensity = density;
            lineWidth = width;
            outlineVertices = vertices;
            streamed = stream ? 1 : 0;
            boundaryVertices = boundary;
            replayedOutlineVertices = cache == null ? 0 : cache.replayedVertices();
            recordedOutlineBatches = cache == null ? 0 : cache.recordings();
            residentOutlineBytes = cache == null ? 0 : cache.retainedBytes();
        }

        void end(boolean complete, PreviewBoundary boundary) {
            boundaryProjectionBytes = boundary == null ? 0 : boundary.projectedBytes;
            peakBoundaryProjectionBytes = boundary == null ? 0 : boundary.peakProjectedBytes;
            frames++;
            if (complete) return;
            prefixFrames++;
            prefixReplayedOutlineVertices += replayedOutlineVertices;
            prefixRecordedOutlineBatches += recordedOutlineBatches;
            for (int i = 0; i < prefixTotals.length; i++) prefixTotals[i] += elapsed[i];
        }

        Map<String, Number> snapshot() {
            Map<String, Number> result = new LinkedHashMap<>();
            for (int i = 0; i < STAGES.length; i++) {
                result.put(STAGES[i] + "Nanos", elapsed[i]);
                result.put(STAGES[i] + "PrefixCumulativeNanos", prefixTotals[i]);
            }
            result.put("frames", frames);
            result.put("prefixFrames", prefixFrames);
            result.put("outlineDensity", outlineDensity);
            result.put("outlineVertices", outlineVertices);
            result.put("boundaryVertices", boundaryVertices);
            result.put("lineWidth", lineWidth);
            result.put("outlineStreamed", streamed);
            result.put("replayedOutlineVertices", replayedOutlineVertices);
            result.put("recordedOutlineBatches", recordedOutlineBatches);
            result.put("residentOutlineBytes", residentOutlineBytes);
            result.put("boundaryProjectionBytes", boundaryProjectionBytes);
            result.put("peakBoundaryProjectionBytes", peakBoundaryProjectionBytes);
            result.put("prefixReplayedOutlineVertices", prefixReplayedOutlineVertices);
            result.put("prefixRecordedOutlineBatches", prefixRecordedOutlineBatches);
            return result;
        }
    }

    /** A source-switch wait reuses only the already projected occupancy boundary, never the old native/face loops. */
    void waitingPreview(HologramState state) {
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

    private int drawPreviewBoundary(HologramState state, boolean waiting, double depthScale) {
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
            double[] points = boundary.points(i, projectedFrame);
            int firstPoint = boundary.pointsOffset(i);
            for (int edge = 0; edge < EDGES.length; edge++) {
                if ((cell.mask & 1 << edge) == 0) continue;
                if (limit > 0 && vertices + 2 > limit) {
                    tess.draw();
                    tess.startDrawing(GL11.GL_LINES);
                    tess.setColorRGBA_F(.30f, .65f, .72f, .6f);
                    vertices = 0;
                }
                int a = firstPoint + 3 * EDGES[edge][0], b = firstPoint + 3 * EDGES[edge][1];
                tess.addVertex(points[a], points[a + 1], points[a + 2] * depthScale + .15);
                tess.addVertex(points[b], points[b + 1], points[b + 2] * depthScale + .15);
                if (limit == 0) limit = previewLineBatchLimit(tess);
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

    private void prepareProgressiveFrame(HologramState state, String key, int x, int y, int width, int height,
        double yaw, double pitch, double zoom, double panX, double panY, double panZ) {
        if (previewPreparation.projection != null && key.equals(previewPreparation.projection.key)) return;
        PreviewProjection previous = previewPreparation.projection;
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
        previewSubmitted = false;
        presentedState = null;
        presentedFrame = null;
        presentedRequest = null;
        invalidatePreviewDrawCache();
        buildStructureBoxes(state);
        // A completed browse frame can reuse its existing surface order storage when the visible sides match.
        boolean settledBrowse = browseViewport && previewPreparation.completeGeometryPresented
            && !state.data.getBoolean("displayPartial");
        if ((cameraOnlyProjection || settledBrowse) && previous != null
            && previous.reprojectComplete(key, visibleView == 1 || visibleMode == 2)) return;
        cachedFaces = new ArrayList<>();
        cachedOutlines = new ArrayList<>();
        cachedMarkers = new ArrayList<>();
        nativePreviewCells.clear();
        linkedPreviewCells.clear();
        visibleCellCount = 0;
        publishedPreviewCells.clear();
        // The old presentation, hover and slices have been retired before any owned vertex/Face can be overwritten.
        previewPreparation.projection = new PreviewProjection(state, key, previous);
    }

    private static int previewFacing(HologramEditorCameraFrame frame) {
        int facing = 0;
        for (int side = 0; side < 6; side++) {
            int[] normal = NORMALS[side];
            if (-(normal[0] * frame.basis.forwardX() + normal[1] * frame.basis.forwardY()
                + normal[2] * frame.basis.forwardZ()) > 1e-8) facing |= 1 << side;
        }
        return facing;
    }

    private static double[][] outlinePoints(double[] points) {
        double[][] outline = new double[8][3];
        for (int corner = 0; corner < outline.length; corner++)
            System.arraycopy(points, corner * 3, outline[corner], 0, 3);
        return outline;
    }

    private void projectPreviewCell(PreviewCell previewCell, PreviewRegion region, boolean current, int facing,
        boolean cacheBrowseSurfaces) {
        HologramState.Cell c = previewCell.cell;
        int exposed = previewCell.exposed & facing;
        String role = previewCell.role;
        if (exposed == 0 && role.isEmpty()) return;
        HologramEditorCameraFrame workerFrame = previewCell.workerFrame;
        previewCell.workerFrame = null;
        if (workerFrame == projectedFrame) {
            region.projectedInputs.add(previewCell);
            region.outlines.add(previewCell.outline);
            for (long surfaces = previewCell.browseSurfaces; surfaces != 0; surfaces &= surfaces - 1)
                region.faces.add(previewCell.faces[Long.numberOfTrailingZeros(surfaces)]);
            previewWorkerProjectedCells++;
            return;
        }
        if (previewCell.outline == null) {
            previewCell.outline = new Face(previewCell, new double[VOXEL_COORDINATES], 0, null);
            previewCell.faces = new Face[previewCell.nativeModel || previewCell.empty ? 6
                : previewCell.material.geometry.size()];
        }
        region.projectedInputs.add(previewCell);
        projectPreviewOutline(previewCell, current);
        double[] projected = previewCell.outline.points;
        if (!role.isEmpty() && (c.anchor || region.markers.size() < 64))
            region.markers.add(new RoleMarker(c, projected, role));
        Face outline = previewCell.outline;
        region.outlines.add(outline);
        if (outline.nativeModel || outline.empty) {
            if (outline.nativeModel) region.nativeCells.add(c);
            for (int side = 0; side < 6; side++) if ((exposed & 1 << side) != 0) {
                Face face = previewCell.faces[side];
                if (face == null) previewCell.faces[side] = face = new Face(previewCell, projected, side, null);
                face.updateDepth();
                region.faces.add(face);
            }
            return;
        }
        HologramTextures.Material material = outline.material;
        boolean cache = cacheBrowseSurfaces && material.geometry.size() <= Long.SIZE;
        if (cache && previewCell.browseExposed == exposed) {
            for (long surfaces = previewCell.browseSurfaces; surfaces != 0; surfaces &= surfaces - 1) {
                Face face = previewCell.faces[Long.numberOfTrailingZeros(surfaces)];
                if (material.partial) projectPreviewSurface(face, projected);
                face.updateDepth();
                region.faces.add(face);
            }
            previewBrowseSurfaceReuses++;
            return;
        }
        long surfaces = 0;
        for (int at = 0; at < material.geometry.size(); at++) {
            HologramGeometry.Surface surface = material.geometry.get(at);
            if ((exposed & 1 << surface.side) == 0) continue;
            Face face = previewCell.faces[at];
            if (face == null) previewCell.faces[at] = face = new Face(
                previewCell,
                material.partial ? new double[VOXEL_COORDINATES] : projected,
                surface.side,
                surface);
            if (material.partial) projectPreviewSurface(face, projected);
            face.updateDepth();
            region.faces.add(face);
            if (cache) surfaces |= 1L << at;
        }
        if (cache) {
            previewCell.browseExposed = exposed;
            previewCell.browseSurfaces = surfaces;
        }
    }

    private void projectPreviewOutline(PreviewCell input, boolean current) {
        HologramState.Cell cell = input.cell;
        Face outline = input.outline;
        projectedFrame.projectVoxel(cell.dx, cell.dy, cell.dz, outline.points, 0);
        outline.updateDepth();
        boolean renderedCurrent = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        outline.fadeDefault = input.role.isEmpty() && !cell.sourceMissing
            && (outline.nativeModel ? !HologramBlockRender.missingTexture(cell, renderedCurrent)
                : input.completeMaterial());
    }

    private static void projectPreviewSurface(Face face, double[] projected) {
        projectPreviewSurface(face.points, face.surface, projected);
    }

    private static void projectPreviewSurface(double[] points, HologramGeometry.Surface surface, double[] projected) {
        for (int vertex = 0; vertex < 8; vertex++) {
            double[] local = surface.vertices[vertex];
            for (int axis = 0; axis < 3; axis++)
                points[vertex * 3 + axis] = projected[axis] + (local[0] + .5) * (projected[3 + axis] - projected[axis])
                    + (local[1] + .5) * (projected[9 + axis] - projected[axis])
                    + (local[2] + .5) * (projected[12 + axis] - projected[axis]);
        }
    }

    /** Native blocks and linked TE previews consume the exact camera used by face projection and picking. */
    private void applyPreviewTransform(HologramState.Cell cell, double depthScale) {
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

    double[] project(double dx, double dy, double dz) {
        return presentedFrame == null ? new double[] { 0, 0 } : presentedFrame.projectScreen(dx, dy, dz);
    }

    double gridScale() {
        return presentedFrame == null ? 0 : presentedFrame.scale;
    }

    /** Use the same visible faces as selection, including the camera-facing placement side. */
    int[] pickAdjacentVoxel(int x, int y) {
        Face hit = nearestFace(cachedFaces, x, y, false);
        if (hit == null) return null;
        int[] normal = NORMALS[hit.side];
        return new int[] { hit.cell.x + normal[0], hit.cell.y + normal[1], hit.cell.z + normal[2] };
    }

    /** Intersect the orthographic view ray with a horizontal plane at an absolute world Y. */
    int[] pickEmptyVoxel(HologramState state, int mx, int my, int planeY) {
        return pickEmptyVoxel(state, mx, my, planeY, true);
    }

    int[] pickEmptyVoxel(HologramState state, int mx, int my, int planeY, boolean adjacentOnly) {
        if (state == null || state != presentedState || presentedFrame == null) return null;
        double[] hit = presentedFrame.horizontalPlane(mx, my, (double) planeY - state.data.getInteger("y"));
        if (hit == null) return null;
        int x = state.data.getInteger("x") + (int) Math.floor(hit[0] + .5);
        int z = state.data.getInteger("z") + (int) Math.floor(hit[2] + .5);
        HologramState.Cell occupied = state.regions.at(x, planeY, z);
        if (occupied != null && !occupied.wantId.equals("minecraft:air")) return null;
        return !adjacentOnly || state.regions.withinMargin(x, planeY, z) ? new int[] { x, planeY, z } : null;
    }

    private void buildStructureBoxes(HologramState state) {
        structureBoxes.clear();
        // Snapshot geometry is immutable; rotating the camera only projects its cached bounds.
        if (structureBoundsState != state) {
            structureBoundsState = state;
            cachedStructureBounds = structureBounds(state);
            missingStructures.clear();
            if (state.nativeViewport != null) missingStructures.addAll(state.nativeViewport.missingStructures);
            else if (!cachedStructureBounds.isEmpty()) for (HologramState.Cell cell : state.cells)
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
    Map<String, int[]> structureBounds(HologramState state) {
        if (state.nativeViewport != null) return state.nativeViewport.structureBounds;
        if (state.structureMembers.isEmpty()
            || (state.structureMembers.size() == 1 && state.structureMembers.containsKey("")))
            return java.util.Collections.emptyMap();
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

    String hintAt(int mx, int my) {
        for (Map.Entry<String, double[][]> entry : hintBoxes.entrySet()) for (int[] edge : EDGES) {
            double[] a = entry.getValue()[edge[0]], b = entry.getValue()[edge[1]];
            if (HologramGizmo.distance(mx, my, a[0], a[1], b[0], b[1]) <= 5) return entry.getKey();
        }
        return "";
    }

    String pickStructure(int mx, int my) {
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

    int pick(int x, int y) {
        if (presentedState == null || presentedFrame == null) return -1;
        for (int at = cachedMarkers.size() - 1; at >= 0; at--) {
            RoleMarker marker = cachedMarkers.get(at);
            if (x >= marker.left && x < marker.right && y >= marker.top && y < marker.bottom)
                return pickTargetIndex(marker.cell.index);
        }
        Face face = nearestFace(cachedFaces, x, y, true);
        return face == null ? -1 : pickTargetIndex(face.cell.index);
    }

    private int pickTargetIndex(int index) {
        if (!presentedState.data.getBoolean("controllerSiteDisplay")) return index;
        HologramState target = controllerSitePickTarget;
        return target != null && target.controllerView(1) == presentedState
            ? presentedState.matchingIndex(target, index)
            : -1;
    }

    private static Face nearestFace(List<Face> faces, int x, int y, boolean includeEmpty) {
        for (int at = faces.size() - 1; at >= 0; at--) {
            Face face = faces.get(at);
            if ((includeEmpty || !face.empty) && face.contains(x, y)) return face;
        }
        return null;
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

    private boolean previewVisible(HologramState.Cell c, int layer, int view, boolean current) {
        return previewVisible(c, layer, view, current, showEmptyCells);
    }

    private static boolean previewVisible(HologramState.Cell c, int layer, int view, boolean current,
        boolean showEmptyCells) {
        if (c.status.equals("removed") || view == 2 && (c.completed() || c.status.equals("satisfied"))) return false;
        if (current && c.capturedNativeTarget() != null && c.localObservationMissing()) return false;
        if (HologramBlockRender.invisible(c, current)) return false;
        boolean empty = !c.anchor && !c.iconOnly && (current ? c.id : c.wantId).equals("minecraft:air");
        return (!empty || showEmptyCells) && (c.anchor || c.dy == layer || layer == Integer.MAX_VALUE);
    }

    private PreviewRegion previewRegion(HologramState state, List<HologramState.Cell> cells, int layer, int view,
        boolean current) {
        PreviewRegion result = new PreviewRegion();
        for (HologramState.Cell c : cells) {
            previewCell(state, result, c, layer, view, current);
            result.prepared++;
        }
        result.complete = true;
        return result;
    }

    private void previewCell(HologramState state, PreviewRegion result, HologramState.Cell c, int layer, int view,
        boolean current) {
        if (!previewVisible(c, layer, view, current)) return;
        result.visibleCount++;
        if (!HologramLinkNodePreview.prepare(c, current)
            .isEmpty()) result.linked.add(c);
        boolean empty = !c.anchor && !c.iconOnly && (current ? c.id : c.wantId).equals("minecraft:air");
        boolean nativeModel = !empty && HologramBlockRender.ready(c, current);
        HologramTextures.Material material = empty ? null : HologramTextures.material(c, current);
        String role = previewRole(c, current, empty, nativeModel, material);
        int exposed = 63;
        if (!nativeModel && (empty || !material.partial)) {
            exposed = 0;
            for (int side = 0; side < 6; side++) {
                int[] n = NORMALS[side];
                HologramState.Cell neighbor = state.regions.at(c.x + n[0], c.y + n[1], c.z + n[2]);
                if (neighbor == null || !previewVisible(neighbor, layer, view, current) || !occludes(neighbor, current))
                    exposed |= 1 << side;
            }
        }
        if (exposed != 0 || !role.isEmpty())
            result.cells.add(new PreviewCell(c, exposed, role, result.prepared + 1, material, empty, nativeModel));
    }

    private static String previewRole(HologramState.Cell c, boolean current, boolean empty, boolean nativeModel,
        HologramTextures.Material material) {
        String role = roleLabel(c);
        if (c.missingBlock) role = "缺失方块";
        else if (c.sourceMissing && c.anchor) role = "来源结构缺失";
        if (!nativeModel && !empty && role.isEmpty() && material.fallback) role = "材质缺失";
        if (!nativeModel && !empty && HologramNativeTiles.supported(c.block(current || c.anchor))) role = "模型缺失";
        if (!empty && role.isEmpty() && c.iconOnly) role = "占位";
        if (!c.prompt.isEmpty()) role = role.isEmpty() ? "提示" : role + " · 提示";
        return role;
    }

    private boolean preparedCameraOnly(HologramState state, String key, int layer, int view, boolean empty) {
        return !browseViewport && previewPreparation != null
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

        PreviewRequest forState(HologramState next) {
            return new PreviewRequest(
                next,
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
                clean);
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

    /** Only render-thread code resolves an appearance. Lite receives the region view and the resolved flags. */
    private MiaoVisonLiteTopology<HologramRegionCache.Key, HologramState.Cell, PreviewCell> startDetached(
        HologramState state, int layer, int view, boolean current) {
        boolean localEditor = assets == HologramPreviewAssets.EDITOR && state.data.getBoolean("localBlueprintDocument")
            && state.cells.size() >= LARGE_BROWSE_CELLS;
        if ((!browseViewport && !localEditor) || state.data.getBoolean("displayPartial")
            || state.plainDisplayExamples.isEmpty()
            || state.plainDisplayExamples.size() > 64
            || layer != Integer.MAX_VALUE
            || view != 0
            || current
            || showEmptyCells) return null;
        Map<Object, OrdinaryTemplate> templates = new IdentityHashMap<>();
        for (HologramState.Cell example : state.plainDisplayExamples) {
            Block block = example.block(false);
            if (example.anchor || example.iconOnly
                || HologramNativeTiles.supported(block)
                || example.hasDisplayTag(false, "gt.covers", 9) && example.display(false)
                    .getTagList("gt.covers", 10)
                    .tagCount() != 0)
                continue;
            boolean visible = previewVisible(example, layer, view, false);
            HologramTextures.Material material = visible ? HologramTextures.material(example, false) : null;
            if (material != null && material.partial) continue;
            boolean opaque = visible && HologramOcclusion.fullCube(block, false, false, false);
            String role = visible ? previewRole(example, false, false, false, material) : "";
            templates.put(example.descriptorIdentity(), new OrdinaryTemplate(visible, opaque, role, material));
        }
        return templates.isEmpty() ? null
            : session.prepare(state.regions.regions, new PreviewTopologyView(state.regions, templates, projectedFrame));
    }

    static final class OrdinaryTemplate {

        final int flags;
        final String role;
        final HologramTextures.Material material;
        final Boolean completeMaterial;

        OrdinaryTemplate(boolean visible, boolean opaque, String role, HologramTextures.Material material) {
            flags = MiaoVisonLiteTopology.KNOWN | (visible ? MiaoVisonLiteTopology.VISIBLE : 0)
                | (opaque ? MiaoVisonLiteTopology.OPAQUE : 0);
            this.role = role;
            this.material = material;
            // Resolve appearance on the client. Custom icon names can change and are queried on every draw.
            completeMaterial = role.isEmpty() && material != null
                && !material.partial
                && material.geometry.size() <= Long.SIZE
                && hasStableMaterialNames(material) ? completePreviewMaterial(material) : null;
        }
    }

    static final class PreviewTopologyView implements MiaoVisonLiteTopology.View<HologramState.Cell, PreviewCell> {

        private final HologramRegionCache regions;
        private final Map<Object, OrdinaryTemplate> templates;
        private final HologramEditorCameraFrame frame;
        private final int facing;

        PreviewTopologyView(HologramRegionCache regions, Map<Object, OrdinaryTemplate> templates) {
            this(regions, templates, null);
        }

        PreviewTopologyView(HologramRegionCache regions, Map<Object, OrdinaryTemplate> templates,
            HologramEditorCameraFrame frame) {
            this.regions = regions;
            this.templates = templates;
            this.frame = frame;
            facing = frame == null ? 0 : previewFacing(frame);
        }

        @Override
        public HologramState.Cell at(int x, int y, int z) {
            return regions.at(x, y, z);
        }

        @Override
        public int x(HologramState.Cell cell) {
            return cell.x;
        }

        @Override
        public int y(HologramState.Cell cell) {
            return cell.y;
        }

        @Override
        public int z(HologramState.Cell cell) {
            return cell.z;
        }

        @Override
        public int flags(HologramState.Cell cell) {
            OrdinaryTemplate template = templates.get(cell.descriptorIdentity());
            return template == null ? 0 : template.flags;
        }

        @Override
        public PreviewCell faceInput(HologramState.Cell cell, int exposed, int sourceOrdinal) {
            OrdinaryTemplate appearance = templates.get(cell.descriptorIdentity());
            if (exposed == 0 && appearance.role.isEmpty()) return null;
            PreviewCell input = new PreviewCell(
                cell,
                exposed,
                appearance.role,
                sourceOrdinal,
                appearance.material,
                false,
                false);
            input.cachedCompleteMaterial = appearance.completeMaterial;
            int facingSurfaces = exposed & facing;
            if (facingSurfaces == 0 || appearance.completeMaterial == null) return input;
            // This worker owns its arrays until Core publishes the region. Only immutable topology and camera
            // values are read. Matrix and depth arithmetic match the adapter's render-thread path.
            double[] points = new double[VOXEL_COORDINATES];
            frame.projectVoxel(cell.dx, cell.dy, cell.dz, points, 0);
            input.outline = new Face(input, points, 0, null);
            input.outline.updateDepth();
            input.outline.fadeDefault = !cell.sourceMissing && appearance.completeMaterial;
            List<HologramGeometry.Surface> surfaces = appearance.material.geometry;
            input.faces = new Face[surfaces.size()];
            input.browseExposed = facingSurfaces;
            for (int at = 0; at < surfaces.size(); at++) {
                HologramGeometry.Surface surface = surfaces.get(at);
                if ((facingSurfaces & 1 << surface.side) == 0) continue;
                Face face = new Face(input, points, surface.side, surface);
                face.updateDepth();
                input.faces[at] = face;
                input.browseSurfaces |= 1L << at;
            }
            input.workerFrame = frame;
            return input;
        }
    }

    /** Client render-thread cursor: each cell invokes the existing native/texture path exactly once. */
    private final class PreviewPreparation {

        HologramState state;
        final java.util.Iterator<Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>>> regions;
        Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry;
        PreviewRegion region;
        int cell, completed;
        private boolean detachedAttempted;
        private MiaoVisonLiteTopology<HologramRegionCache.Key, HologramState.Cell, PreviewCell> detached;
        private List<MiaoVisonLiteTopology.Run<HologramState.Cell, PreviewCell>> detachedRuns;
        private int detachedRun;
        final PreviewBoundary boundary;
        final boolean localBlueprint;
        PreviewProjection projection;
        final long startedAt = System.nanoTime();
        boolean outlineObserved, materialObserved, readyObserved, completeGeometryPresented;

        PreviewPreparation(HologramState state, boolean outline) {
            this.state = state;
            localBlueprint = state.data.getBoolean("localBlueprintDocument")
                || (browseViewport && "blueprint-artifact".equals(state.session));
            regions = state.regions.regions.entrySet()
                .iterator();
            // Structure hints already enter the progressive mesh directly. A second all-cell occupancy/edge pass
            // would gate their first material on a separate preview that the mesh replaces immediately afterwards.
            boundary = outline && !state.data.getBoolean("structureProjection")
                ? new PreviewBoundary(state, showEmptyCells)
                : null;
        }

        void advance(int layer, int view, boolean current, String key, long budget) {
            long start = System.nanoTime();
            long deadline = start + budget;
            if (!detachedAttempted) {
                detachedAttempted = true;
                detached = startDetached(state, layer, view, current);
            }
            boolean waitingForBoundary = false;
            if (boundary != null && !localBlueprint) {
                long boundaryDeadline = start + PREVIEW_BUILD_NANOS;
                boundary.advance(layer, view, current, Integer.MAX_VALUE, boundaryDeadline);
                boundary.project(key, projectedFrame, Integer.MAX_VALUE, boundaryDeadline);
                waitingForBoundary = !boundary.presented && (!boundary.complete() || !boundary.cells.isEmpty());
                // Only the immutable library job can safely work ahead of first presentation. The tutorial and
                // world can retire each other's unpublished native snapshot before the next frame.
                if (waitingForBoundary && detached == null) return;
                if (budget == PREVIEW_BUILD_NANOS) deadline = System.nanoTime() + budget;
            }
            int before = completed;
            long topologyDeadline = budget == PREVIEW_BUILD_NANOS ? deadline : deadline - PREVIEW_BUILD_NANOS;
            advanceRegions(layer, view, current, topologyDeadline);
            projection.advance(
                current,
                Integer.MAX_VALUE,
                budget == PREVIEW_BUILD_NANOS ? System.nanoTime() + budget : deadline);
            // Once material is visible, let remaining time go to native/topology work when projection finished early.
            // The outer deadline and the once-per-frame ordered publication are unchanged.
            if (budget != PREVIEW_BUILD_NANOS && materialObserved
                && !waitingForBoundary
                && completed < state.cells.size()
                && projection.completed == completed
                && System.nanoTime() < deadline) {
                int remaining = completed;
                advanceRegions(layer, view, current, deadline);
                if (completed != remaining) projection.advance(current, Integer.MAX_VALUE, deadline);
            }
            // Build material and pick inputs while the outline is still being prepared. Keep the first boundary
            // presentation before material publication, including when the worker has already produced a full mesh.
            if (!waitingForBoundary) projection.publish();
            else if (completed != before) previewBoundaryOverlapFrames++;
            // A validated blueprint already owns its neighbor index. Fill in the interim outline
            // in the remaining budget while material sections start using that same index.
            if (localBlueprint && boundary != null
                && projection.published < state.cells.size()
                && System.nanoTime() < deadline) {
                boundary.advance(layer, view, current, Integer.MAX_VALUE, deadline);
                boundary.project(key, projectedFrame, Integer.MAX_VALUE, deadline);
            }
            if (completed == state.cells.size()) closeDetached();
        }

        private void advanceRegions(int layer, int view, boolean current, long deadline) {
            int work = 0;
            while (completed < state.cells.size() && (work == 0 || System.nanoTime() < deadline)) {
                if (entry == null || cell == entry.getValue()
                    .size()) {
                    if (region != null) region.complete = true;
                    if (!regions.hasNext()) break;
                    entry = regions.next();
                    cell = 0;
                    region = null;
                    detachedRuns = null;
                }
                if (region == null) {
                    PreviewRegion retained = previewRegions.get(entry.getKey());
                    if (retained != null && retained.complete) {
                        region = retained;
                        completed += entry.getValue()
                            .size();
                        cell = entry.getValue()
                            .size();
                        continue;
                    }
                    if (detached != null) {
                        detachedRuns = detached.take(entry.getKey());
                        if (detachedRuns == null && detached.done()) detachedRuns = detached.take(entry.getKey());
                        // Start a local artifact on the client if the first worker section has not arrived.
                        // Once material is visible, give the bounded background preparation priority again.
                        if (detachedRuns == null && !detached.done() && (!localBlueprint || materialObserved)) break;
                        detachedRun = 0;
                    }
                    region = new PreviewRegion();
                    previewRegions.put(entry.getKey(), region);
                    lastPreviewBuiltRegions++;
                }
                MiaoVisonLiteTopology.Run<HologramState.Cell, PreviewCell> run = detachedRuns == null ? null
                    : detachedRuns.get(detachedRun);
                int startCell = cell;
                if (run != null && run.ordinary) {
                    region.visibleCount += run.visible.size();
                    region.cells.addAll(run.faces);
                    cell = run.end;
                    previewWorkerCompletedRuns++;
                } else {
                    previewCell(
                        state,
                        region,
                        entry.getValue()
                            .get(cell++),
                        layer,
                        view,
                        current);
                    if (run != null) previewWorkerSpecialFallbacks++;
                }
                if (run != null && cell == run.end) detachedRun++;
                region.prepared += cell - startCell;
                completed += cell - startCell;
                work++;
            }
            if (region != null && cell == entry.getValue()
                .size()) region.complete = true;
        }

        void closeDetached() {
            if (detached != null) session.cancelPreparation();
            detached = null;
            detachedRuns = null;
        }
    }

    private static final Comparator<Face> CAMERA_DEPTH_ORDER = Comparator.comparingDouble((Face face) -> face.depth)
        .thenComparingInt(face -> face.projectionOrder);

    /** Projects only newly prepared cells; camera changes reuse native/material preparation and restart this cursor. */
    private final class PreviewProjection {

        HologramState state;
        String key;
        final java.util.Iterator<Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>>> regions;
        Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry;
        PreviewRegion region;
        int counted, completed, published, facing;
        final boolean cacheBrowseSurfaces;
        final List<Face> faces, outlines;
        final List<RoleMarker> markers;
        final List<HologramState.Cell> nativeCells, linked;
        int visible;
        final java.util.BitSet cells;

        PreviewProjection(HologramState state, String key) {
            this(state, key, null);
        }

        PreviewProjection(HologramState state, String key, PreviewProjection previous) {
            this.state = state;
            this.key = key;
            cacheBrowseSurfaces = browseViewport && !state.data.getBoolean("displayPartial");
            regions = state.regions.regions.entrySet()
                .iterator();
            faces = previous == null ? new ArrayList<>() : previous.faces;
            outlines = previous == null ? new ArrayList<>() : previous.outlines;
            markers = previous == null ? new ArrayList<>() : previous.markers;
            nativeCells = previous == null ? new ArrayList<>() : previous.nativeCells;
            linked = previous == null ? new ArrayList<>() : previous.linked;
            cells = previous == null ? new java.util.BitSet() : previous.cells;
            faces.clear();
            outlines.clear();
            markers.clear();
            nativeCells.clear();
            linked.clear();
            visible = 0;
            cells.clear();
        }

        void advance(boolean current, int maximum, long deadline) {
            int work = 0;
            facing = previewFacing(projectedFrame);
            while (work < maximum && (deadline == Long.MAX_VALUE || work == 0 || System.nanoTime() < deadline)) {
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
                        region.projectedInputs.clear();
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
                    projectPreviewCell(input, region, current, facing, cacheBrowseSurfaces);
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
                    visible += region.visibleCount;
                    for (int i = 0; i < region.linked.size(); i++) linked.add(region.linked.get(i));
                    if (previewPreparation.boundary != null)
                        for (HologramState.Cell cell : entry.getValue()) cells.set(cell.index);
                    entry = null;
                    work++;
                }
            }
        }

        boolean reprojectComplete(String newKey, boolean current) {
            if (entry != null || regions.hasNext()
                || published != state.cells.size()
                || facing != previewFacing(projectedFrame)) return false;
            key = newKey;
            int order = 0;
            // Region/cell emission order resolves exact-depth ties in the same order as a fresh stable sort.
            for (HologramRegionCache.Key regionKey : state.regions.regions.keySet()) {
                PreviewRegion input = previewRegions.get(regionKey);
                input.projectedKey = newKey;
                for (PreviewCell cell : input.projectedInputs) {
                    projectPreviewOutline(cell, current);
                    if (!cell.nativeModel && !cell.empty && cell.material.partial)
                        for (Face face : cell.faces) if (face != null && (facing & 1 << face.side) != 0)
                            projectPreviewSurface(face, cell.outline.points);
                }
                for (Face face : input.faces) {
                    face.updateDepth();
                    face.projectionOrder = order++;
                }
                for (RoleMarker marker : input.markers) marker.reproject();
            }
            previewCameraReprojects++;
            // Consecutive poses usually preserve long runs of the prior order. A stable depth tie uses the
            // canonical input ordinal even when those faces had different depths in the previous pose.
            for (int at = 1; at < cachedFaces.size(); at++) {
                if (CAMERA_DEPTH_ORDER.compare(cachedFaces.get(at - 1), cachedFaces.get(at)) > 0) {
                    cachedFaces.sort(CAMERA_DEPTH_ORDER);
                    previewCameraOrderSorts++;
                    return true;
                }
            }
            previewCameraOrderReuses++;
            return true;
        }

        void publish() {
            if (completed <= published
                || published != 0 && completed < 2L * published && completed != state.cells.size()) return;
            // Geometric milestones bound total merge/copy work; old projected geometry is never recreated here.
            faces.sort(Comparator.comparingDouble(face -> face.depth));
            boolean complete = published == 0 && completed == state.cells.size();
            if (complete) {
                // This cursor cannot append again. Transfer its complete ordered output, retaining capacity for the
                // next camera only after prepareProgressiveFrame has invalidated the old actual presentation.
                cachedFaces = faces;
                cachedOutlines = outlines;
            } else {
                List<Face> merged = new ArrayList<>(cachedFaces.size() + faces.size());
                int old = 0, added = 0;
                while (old < cachedFaces.size() && added < faces.size()) {
                    if (cachedFaces.get(old).depth <= faces.get(added).depth) merged.add(cachedFaces.get(old++));
                    else merged.add(faces.get(added++));
                }
                while (old < cachedFaces.size()) merged.add(cachedFaces.get(old++));
                while (added < faces.size()) merged.add(faces.get(added++));
                cachedFaces = merged;
                for (int i = 0; i < outlines.size(); i++) cachedOutlines.add(outlines.get(i));
            }
            invalidatePreviewDrawCache();
            for (int i = 0; i < nativeCells.size(); i++) nativePreviewCells.add(nativeCells.get(i));
            for (int i = 0; i < linked.size(); i++) linkedPreviewCells.add(linked.get(i));
            visibleCellCount += visible;
            int shownMarkers = cachedMarkers.size();
            for (RoleMarker marker : markers) if (marker.cell.anchor || shownMarkers < 64) {
                cachedMarkers.add(marker);
                shownMarkers++;
            }
            cachedMarkers.sort(Comparator.comparingInt(marker -> marker.cell.anchor ? 1 : 0));
            publishedPreviewCells.or(cells);
            if (!complete) {
                faces.clear();
                outlines.clear();
            }
            nativeCells.clear();
            markers.clear();
            linked.clear();
            visible = 0;
            cells.clear();
            previewState = state;
            published = completed;
            previewGeometryPublications++;
        }
    }

    /** Pure voxel occupancy; no registry, textures, native tile factory or GL is queried. */
    static final class PreviewBoundary {

        private static final int PAGE_SHIFT = 6, PAGE_CELLS = 1 << PAGE_SHIFT, POINTS_PER_CELL = 24;
        HologramState state;
        final Map<HologramRegionCache.Key, java.util.BitSet> occupancy = new java.util.HashMap<>();
        java.util.Iterator<Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>>> regions;
        Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry;
        java.util.BitSet bits;
        final java.util.BitSet[] neighbors = new java.util.BitSet[27];
        final List<BoundaryCell> cells = new ArrayList<>();
        private List<double[]> projectedPages = new ArrayList<>();
        private final double[] drawScratch = new double[POINTS_PER_CELL];
        private long projectedBytes, peakProjectedBytes;
        int stage, cell, projected;
        boolean presented;
        String projectedKey = "";

        private final boolean showEmptyCells;

        PreviewBoundary(HologramState state) {
            this(state, false);
        }

        PreviewBoundary(HologramState state, boolean showEmptyCells) {
            this.state = state;
            this.showEmptyCells = showEmptyCells;
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
                        if (stage == 1) regions = state.regions.regions.entrySet()
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
                        // The four voxels meeting any edge never span three neighboring axes at once.
                        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                            if (x != 0 && y != 0 && z != 0) continue;
                            neighbors[(x + 1) * 9 + (y + 1) * 3 + z + 1] = (x | y | z) == 0 ? bits
                                : occupancy.get(new HologramRegionCache.Key(key.x + x, key.y + y, key.z + z));
                        }
                    }
                }
                HologramState.Cell c = entry.getValue()
                    .get(cell++);
                int index = local(c.x, c.y, c.z);
                if (stage == 0) {
                    if (previewVisible(c, layer, view, current, showEmptyCells)
                        && (c.iconOnly || !(current ? c.id : c.wantId).equals("minecraft:air"))) bits.set(index);
                } else if (bits.get(index)) {
                    int mask = BoundaryNeighborhood.edges(neighbors, index);
                    if (mask != 0) cells.add(new BoundaryCell(c, mask));
                }
            }
        }

        private static int local(int x, int y, int z) {
            return ((x & 15) << 8) | ((y & 15) << 4) | (z & 15);
        }

        void project(String key, HologramEditorCameraFrame frame, int maximum, long deadline) {
            if (projectedPages == null) {
                projectedPages = new ArrayList<>();
                projected = 0;
            }
            if (!key.equals(projectedKey)) {
                projectedKey = key;
                projected = 0;
            }
            for (int work = 0; projected < cells.size() && work < maximum
                && (work == 0 || System.nanoTime() < deadline); work++) {
                int page = projected >> PAGE_SHIFT;
                if (page == projectedPages.size()) {
                    int capacity = Math.min(PAGE_CELLS, state.cells.size() - projected);
                    projectedPages.add(new double[capacity * POINTS_PER_CELL]);
                    projectedBytes += (long) capacity * POINTS_PER_CELL * Double.BYTES;
                    peakProjectedBytes = Math.max(peakProjectedBytes, projectedBytes);
                }
                writePoints(frame, cells.get(projected).cell, projectedPages.get(page), pointsOffset(projected));
                projected++;
            }
        }

        void discardProjectedPoints() {
            projectedPages = null;
            projectedBytes = 0;
            if (complete()) {
                occupancy.clear();
                bits = null;
                java.util.Arrays.fill(neighbors, null);
            }
        }

        private void writePoints(HologramEditorCameraFrame frame, HologramState.Cell cell, double[] output, int at) {
            frame.projectVoxel(cell.dx, cell.dy, cell.dz, output, at);
        }

        double[] points(int index, HologramEditorCameraFrame frame) {
            if (projectedPages != null) return projectedPages.get(index >> PAGE_SHIFT);
            writePoints(frame, cells.get(index).cell, drawScratch, 0);
            return drawScratch;
        }

        int pointsOffset(int index) {
            return projectedPages == null ? 0 : (index & (PAGE_CELLS - 1)) * POINTS_PER_CELL;
        }
    }

    /**
     * Each of the three edge directions reads the surrounding eight voxels from one plane through the occupied cell.
     */
    static final class BoundaryNeighborhood {

        private static final int[][] OFFSETS = { { -1, 0, 0 }, { 1, 0, 0 }, { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 },
            { 0, 0, 1 }, { -1, -1, 0 }, { 1, -1, 0 }, { -1, 1, 0 }, { 1, 1, 0 }, { -1, 0, -1 }, { 1, 0, -1 },
            { -1, 0, 1 }, { 1, 0, 1 }, { 0, -1, -1 }, { 0, 1, -1 }, { 0, -1, 1 }, { 0, 1, 1 } };

        // Only the editor's region/BitSet path needs a per-local-position address table.
        private static final class RegionAddresses {

            static final int[] VALUES = buildAddresses();
        }

        private static final class EdgeMasks {

            static final short[][] VALUES = buildEdgeMasks();
        }

        private BoundaryNeighborhood() {}

        static int edges(java.util.BitSet[] regions, int local) {
            int occupied = 0, start = local * OFFSETS.length;
            for (int i = 0; i < OFFSETS.length; i++) {
                int address = RegionAddresses.VALUES[start + i];
                java.util.BitSet region = regions[address >>> 12];
                if (region != null && region.get(address & 4095)) occupied |= 1 << i;
            }
            return edgeMask(occupied);
        }

        /** The world's twelve edges touch only the center and eighteen distinct neighbors. */
        static int worldEdges(int x, int y, int z, HologramSelectionOutline.Occupancy cells) {
            if (!cells.contains(x, y, z)) return 0;
            int occupied = 0;
            for (int i = 0; i < OFFSETS.length; i++) {
                int[] offset = OFFSETS[i];
                if (cells.contains(x + offset[0], y + offset[1], z + offset[2])) occupied |= 1 << i;
            }
            return edgeMask(occupied);
        }

        private static int edgeMask(int occupied) {
            // An isolated block (including a one-block cold projection) needs no lookup table.
            if (occupied == 0) return (1 << EDGES.length) - 1;
            int yz = (occupied >>> 2 & 15) | (occupied >>> 10 & 240);
            int xz = (occupied & 3) | (occupied >>> 2 & 12) | (occupied >>> 6 & 240);
            int xy = (occupied & 15) | (occupied >>> 2 & 240);
            return EdgeMasks.VALUES[0][yz] | EdgeMasks.VALUES[1][xz] | EdgeMasks.VALUES[2][xy];
        }

        private static int[] buildAddresses() {
            int[] addresses = new int[4096 * OFFSETS.length];
            for (int local = 0; local < 4096; local++) {
                for (int i = 0; i < OFFSETS.length; i++) {
                    int[] offset = OFFSETS[i];
                    int x = (local >>> 8) + offset[0], y = (local >>> 4 & 15) + offset[1], z = (local & 15) + offset[2];
                    int region = ((x >> 4) + 1) * 9 + ((y >> 4) + 1) * 3 + (z >> 4) + 1;
                    addresses[local * OFFSETS.length + i] = region << 12 | PreviewBoundary.local(x, y, z);
                }
            }
            return addresses;
        }

        private static short[][] buildEdgeMasks() {
            short[][] masks = new short[3][256];
            for (int edge = 0; edge < EDGES.length; edge++) {
                double[] a = VERTICES[EDGES[edge][0]], b = VERTICES[EDGES[edge][1]];
                int axis = a[0] != b[0] ? 0 : a[1] != b[1] ? 1 : 2;
                int x = (int) (Math.min(a[0], b[0]) + .5), y = (int) (Math.min(a[1], b[1]) + .5),
                    z = (int) (Math.min(a[2], b[2]) + .5);
                for (int mask = 0; mask < 256; mask++) {
                    int occupied = mask;
                    if (HologramSelectionOutline.ownsEdge(
                        0,
                        0,
                        0,
                        x,
                        y,
                        z,
                        axis,
                        (px, py, pz) -> planeContains(axis == 0 ? py : px, axis == 2 ? py : pz, occupied)))
                        masks[axis][mask] |= 1 << edge;
                }
            }
            return masks;
        }

        private static boolean planeContains(int u, int v, int occupied) {
            if (u == 0 && v == 0) return true;
            int bit = v == 0 ? (u > 0 ? 1 : 0) : u == 0 ? (v > 0 ? 3 : 2) : 4 + (u > 0 ? 1 : 0) + (v > 0 ? 2 : 0);
            return (occupied & 1 << bit) != 0;
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

        BoundaryCell(HologramState.Cell cell, int mask) {
            this.cell = cell;
            this.mask = mask;
        }
    }

    private static final class PreviewRegion {

        int visibleCount;
        final List<HologramState.Cell> linked = new ArrayList<>();
        final List<PreviewCell> cells = new ArrayList<>();
        final List<PreviewCell> projectedInputs = new ArrayList<>();
        String projectedKey = "";
        List<Face> faces = new ArrayList<>(), outlines = new ArrayList<>();
        List<RoleMarker> markers = new ArrayList<>();
        List<HologramState.Cell> nativeCells = new ArrayList<>();
        int prepared, projectedCells;
        boolean complete;
    }

    static final class PreviewCell {

        final HologramState.Cell cell;
        final int exposed;
        final String role;
        final int sourceOrdinal;
        final HologramTextures.Material material;
        final boolean empty, nativeModel;
        Face outline;
        Face[] faces;
        int browseExposed = -1;
        long browseSurfaces;
        HologramEditorCameraFrame workerFrame;
        private Boolean cachedCompleteMaterial;
        private boolean liveMaterialNames;

        PreviewCell(HologramState.Cell cell, int exposed, String role, int sourceOrdinal,
            HologramTextures.Material material, boolean empty, boolean nativeModel) {
            this.cell = cell;
            this.exposed = exposed;
            this.role = role;
            this.sourceOrdinal = sourceOrdinal;
            this.material = material;
            this.empty = empty;
            this.nativeModel = nativeModel;
        }

        boolean completeMaterial() {
            if (cachedCompleteMaterial != null) return cachedCompleteMaterial;
            boolean complete = completePreviewMaterial(material);
            if (liveMaterialNames) return complete;
            if (!hasStableMaterialNames(material)) {
                liveMaterialNames = true;
                return complete;
            }
            // AtlasSprite's name is final. Custom icons can change their answer and retain the live lookup.
            cachedCompleteMaterial = complete;
            return complete;
        }
    }

    private static boolean hasStableMaterialNames(HologramTextures.Material material) {
        if (material == null) return true;
        for (HologramGeometry.Surface surface : material.geometry)
            for (HologramTextures.Sample sample : material.samples(surface))
                if (sample.icon != null && sample.icon.getClass() != TextureAtlasSprite.class) return false;
        return true;
    }

    private static final class RoleMarker {

        final HologramState.Cell cell;
        final String label;
        private final double[] points;
        double x, y;
        int left, top, right, bottom;

        RoleMarker(HologramState.Cell cell, double[] points, String label) {
            this.cell = cell;
            this.label = label;
            this.points = points;
            reproject();
        }

        void reproject() {
            double sx = 0, sy = 0;
            for (int at = 0; at < points.length; at += 3) {
                sx += points[at];
                sy += points[at + 1];
            }
            x = sx / (points.length / 3);
            y = sy / (points.length / 3);
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

    static void world(HologramState state, float partialTicks, int projectionLayer) {
        boolean witness = Boolean.getBoolean("gtmb.hologram.realInteractionSmoke")
            || Boolean.getBoolean("gtmb.hologram.followupAreaClient")
            || Boolean.getBoolean("gtmb.hologram.previewCaptureWitness");
        if (witness) worldRenderWitness = null;
        if (WORLD_SESSION.contextChanged()) {
            releaseWorld();
            HologramPreviewAssets.active()
                .clear();
        }
        HologramTextures.begin(state);
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.renderViewEntity;
        if (camera == null) return;
        double px = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double py = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double pz = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        long registry = HologramNativeRenderRegistry.generation();
        if (worldState != state || worldLayer != projectionLayer || registryGeneration != registry) {
            if (registryGeneration != registry) {
                HologramPreviewAssets.active()
                    .clear();
                HologramTextures.begin(state);
            }
            registryGeneration = registry;
            clearWorldGroups();
            worldState = state;
            worldLayer = projectionLayer;
            localWorldRegions = seedLocalWorldRegions(state, projectionLayer);
            lastWorldGeometry = lastWorldGTGeometry = lastWorldFallback = lastWorldMatched = lastWorldGhosts = 0;
        }
        HologramBlockRender.beginViewport(state);
        // Indexing and list compilation share the original per-frame budget.
        long deadline = System.nanoTime() + WORLD_BUILD_NANOS;
        if (!localWorldRegions) indexWorld(state, deadline);
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
        HologramLocalPlacement local = state.data.getBoolean("localPreview") ? HologramLocalPlacement.INSTANCE : null;
        HologramPreviewPose pose = local == null ? null : local.previewPose();
        double sx = px - offsetX, sy = py - offsetY, sz = pz - offsetZ;
        if (pose != null) {
            double[] unrotated = pose.inverse(sx - .5, sy - .5, sz - .5);
            int[] pivot = local.pivot();
            sx = unrotated[0] + .5 + pivot[0];
            sy = unrotated[1] + .5 + pivot[1];
            sz = unrotated[2] + .5 + pivot[2];
        }
        if (localWorldRegions && pendingRegions != null) discoverWorldRegions(state, sx, sy, sz, deadline);
        List<WorldGroup> candidates = worldSpatialIndex.select(sx, sy, sz, range);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        int drawnLists = 0;
        try {
            GL11.glTranslated(-px, -py, -pz);
            GL11.glTranslated(offsetX, offsetY, offsetZ);
            if (local != null) local.rotateGl();
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
            MiaoVisonLiteMaterialBatch.Frame meshFrame = MiaoVisonLiteMesh.currentFrame(Tessellator.instance);
            for (WorldGroup group : candidates) {
                double gx = group.x + offsetX, gy = group.y + offsetY, gz = group.z + offsetZ;
                double ex = gx + 16, ey = gy + 16, ez = gz + 16;
                if (local != null) {
                    double[] bounds = group.rotatedBounds(local, pose);
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
                if (group.directGeometry != null && !group.directGeometry.belongsTo(meshFrame))
                    invalidateWorldGroup(group);
                if (readyForWorldGeometry(state, group, deadline)) {
                    while (group.built < group.cells.size() && System.nanoTime() < deadline) {
                        if (!compileWorldGroup(group, state.data.getInteger("mode") == 2, meshFrame)) break;
                    }
                }
                for (WorldBatch batch : group.batches) {
                    batch.draw();
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
        for (WorldGroup group : worldGroups.values()) group.releaseBatches();
        if (placementColorList != null) placementColorList.close();
        placementColorList = null;
        placementColor = 0;
        WORLD_SESSION.close();
        worldSpatialIndex.clear();
        worldGhosts.clear();
        worldOccluders.clear();
        worldOutlineCells.clear();
        worldIndex = 0;
        localWorldRegions = false;
        pendingRegions = null;
    }

    /** Cached geometry calls this tiny list, so a local lock changes color without rebuilding the blueprint. */
    private static void syncPlacementColor(HologramState state) {
        if (!state.data.getBoolean("virtualPlacement") || state.data.getInteger("mode") == 2) return;
        int color = placementOutlineColor(state);
        lastWorldPlacementOutlineColor = color;
        if (placementColorList == null) placementColorList = MiaoVisonLiteDisplayList.allocate();
        if (placementColorList == null || placementColor == color) return;
        placementColorList.begin();
        try {
            GL11.glColor4f(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, .65f);
        } finally {
            placementColorList.finish();
        }
        placementColor = color;
    }

    static int placementOutlineColor(HologramState state) {
        return HologramClient.positionLocked(state) ? 0x66d9ef : 0x70e69a;
    }

    /** Rebuild only affected cached groups; visual receipts never replace snapshot cells. */
    static void jobVisualChanged(HologramState state, int[] indices) {
        if (worldState != state || indices.length == 0) return;
        // Receipts invalidate exactly these cells and adjoining mesh boundaries. A local
        // directory can have partially filled groups; unseen rows consult the receipt at indexing.
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
        group.releaseBatches();
        group.built = 0;
        lastWorldGeometry -= group.geometry;
        lastWorldGTGeometry -= group.gt;
        lastWorldFallback -= group.fallback;
        lastWorldGhosts -= group.ghosts;
        group.geometry = group.gt = group.fallback = group.ghosts = 0;
    }

    void nativeSnapshotChanged() {
        previewState = null;
        visibleState = null;
    }

    private static void indexWorld(HologramState state, long deadline) {
        while (worldIndex < state.cells.size() && System.nanoTime() < deadline)
            indexWorldCell(state, state.cells.get(worldIndex++));
    }

    private static boolean includedWorldCell(HologramState state, HologramState.Cell cell, int layer) {
        return !HologramJobVisuals.completed(state, cell)
            && (cell.anchor || layer == Integer.MAX_VALUE || cell.dy == layer)
            && (cell.anchor || !cell.id.equals("minecraft:air")
                || !cell.wantId.equals("minecraft:air")
                || cell.role.contains("interface"));
    }

    /** Seed the small authoritative region directory, not the contents of all regions. */
    private static boolean seedLocalWorldRegions(HologramState state, int layer) {
        return seedLocalWorldRegions(state, layer, false);
    }

    private static boolean seedLocalWorldRegions(HologramState state, int layer, boolean validatedDirectory) {
        if ((!state.completeBaseline() && !validatedDirectory) || state.data.getBoolean("displayPartial")
            || state.nativeViewport == null && state.cells.size() < LOCAL_WORLD_MIN_CELLS
                && state.regions.regions.size() <= EAGER_REGION_DIRECTORY
            || !"local-preview".equals(state.data.getString("session"))
            || !state.data.getBoolean("localPreview")
            || !state.data.getBoolean("virtual")
            || !state.data.getBoolean("virtualPlacement")
            || state.data.getInteger("mode") == 2) return false;
        if (state.regions.regions.size() > EAGER_REGION_DIRECTORY) {
            pendingRegions = state.regions.regions.entrySet()
                .iterator();
            return true;
        }
        for (var entry : state.regions.regions.entrySet()) materializeRegion(entry.getKey(), entry.getValue());
        return true;
    }

    private static WorldGroup materializeRegion(HologramRegionCache.Key key, List<HologramState.Cell> cells) {
        if (cells == null) return null;
        Coordinate id = coordinate(key.x, key.y, key.z);
        WorldGroup group = worldGroups.get(id);
        if (group != null) return group;
        group = new WorldGroup(key.x << 4, key.y << 4, key.z << 4);
        group.localSource = cells;
        worldGroups.put(id, group);
        worldSpatialIndex.add(group.x, group.y, group.z, group);
        return group;
    }

    /**
     * Prioritize the camera's neighborhood, not the first file row. Drain only a bounded
     * number of remaining directory records per frame, leaving time for actual visible geometry.
     */
    private static void discoverWorldRegions(HologramState state, double sx, double sy, double sz, long deadline) {
        var bounds = state.regions.bounds();
        int x = (int) Math.floor(Math.max(bounds[0], Math.min(bounds[3], sx))) >> 4;
        int y = (int) Math.floor(Math.max(bounds[1], Math.min(bounds[4], sy))) >> 4;
        int z = (int) Math.floor(Math.max(bounds[2], Math.min(bounds[5], sz))) >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            var k = new HologramRegionCache.Key(x + dx, y + dy, z + dz);
            materializeRegion(k, state.regions.regions.get(k));
        }
        long end = Math.min(deadline, System.nanoTime() + 300_000L);
        for (int count = 0; pendingRegions != null && count < 128 && System.nanoTime() < end; count++) {
            if (!pendingRegions.hasNext()) {
                pendingRegions = null;
                break;
            }
            var entry = pendingRegions.next();
            materializeRegion(entry.getKey(), entry.getValue());
        }
    }

    private static boolean readyForWorldGeometry(HologramState state, WorldGroup visible, long deadline) {
        if (!localWorldRegions) return worldIndex == state.cells.size();
        if (visible.localHaloReady) return true;
        // Outline edges read at most two adjacent axes; material faces read one. For retained native
        // geometry, HologramBlockRender still has the full authoritative snapshot as its access.
        int gx = visible.x >> 4, gy = visible.y >> 4, gz = visible.z >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx != 0 && dy != 0 && dz != 0) continue;
            WorldGroup region = worldGroups.get(coordinate(gx + dx, gy + dy, gz + dz));
            if (region == null && pendingRegions != null) {
                var k = new HologramRegionCache.Key(gx + dx, gy + dy, gz + dz);
                region = materializeRegion(k, state.regions.regions.get(k));
            }
            if (region == null) continue; // Absent in the authoritative directory, not merely unindexed.
            while (region.localCursor < region.localSource.size()) {
                if (System.nanoTime() >= deadline) return false;
                HologramState.Cell cell = region.localSource.get(region.localCursor++);
                if (includedWorldCell(state, cell, worldLayer)) indexWorldCell(state, cell, region);
            }
        }
        visible.localHaloReady = true;
        return true;
    }

    private static void indexWorldCell(HologramState state, HologramState.Cell c) {
        if (!includedWorldCell(state, c, worldLayer)) return;
        Coordinate key = coordinate(c.x >> 4, c.y >> 4, c.z >> 4);
        WorldGroup group = worldGroups.get(key);
        if (group == null) {
            group = new WorldGroup(c.x >> 4 << 4, c.y >> 4 << 4, c.z >> 4 << 4);
            worldGroups.put(key, group);
            worldSpatialIndex.add(group.x, group.y, group.z, group);
        }
        indexWorldCell(state, c, group);
    }

    private static void indexWorldCell(HologramState state, HologramState.Cell c, WorldGroup group) {
        boolean removal = state.data.getInteger("mode") == 2;
        boolean virtual = state.data.getBoolean("virtual");
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

    private static boolean compileWorldGroup(WorldGroup group, boolean removal,
        MiaoVisonLiteMaterialBatch.Frame meshFrame) {
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
        MiaoVisonLiteDisplayList batch = MiaoVisonLiteDisplayList.allocate();
        if (batch == null) return false;
        MiaoVisonLiteMesh mesh = null;
        try {
            int vertices = meshFrame == null ? 0 : worldMaterialVertices(cells);
            if (vertices > 0) {
                MiaoVisonLiteMesh.Builder geometry = MiaoVisonLiteMesh.liveBuilder(Tessellator.instance, vertices);
                emitWorldMaterials(cells, null, geometry);
                mesh = MiaoVisonLiteMesh.upload(meshFrame, geometry, MiaoVisonLiteMesh.Quads.RECORDED);
            }
            batch.begin();
            compileWorldGeometry(cells, removal, mesh == null);
            batch.finish();
            group.batches.add(new WorldBatch(mesh, batch));
        } catch (RuntimeException | Error failure) {
            try {
                batch.close();
            } finally {
                if (mesh != null) mesh.close();
            }
            lastWorldGeometry = beforeGeometry;
            lastWorldGTGeometry = beforeGT;
            lastWorldFallback = beforeFallback;
            lastWorldGhosts = beforeGhosts;
            throw failure;
        }
        if (mesh != null) {
            group.directGeometry = mesh;
            worldDirectMeshUploads++;
        }
        group.built = end;
        group.geometry += lastWorldGeometry - beforeGeometry;
        group.gt += lastWorldGTGeometry - beforeGT;
        group.fallback += lastWorldFallback - beforeFallback;
        group.ghosts += lastWorldGhosts - beforeGhosts;
        return true;
    }

    private static int worldMaterialVertices(List<HologramState.Cell> cells) {
        int vertices = 0;
        for (HologramState.Cell cell : cells) {
            if (!worldGhosts.contains(coordinate(cell.x, cell.y, cell.z)) || HologramBlockRender.ready(cell, false))
                continue;
            HologramTextures.Material material = HologramTextures.material(cell, false);
            for (HologramGeometry.Surface surface : material.geometry) {
                if (!worldFaceVisible(cell, material, NORMALS[surface.side])) continue;
                for (HologramTextures.Sample sample : material.samples(surface)) {
                    if (!stablePreviewIcon(sample.icon)) return 0;
                    vertices += 4;
                    if (vertices > MiaoVisonLiteMesh.MAX_VERTICES) return 0;
                }
            }
        }
        return vertices;
    }

    private static boolean worldFaceVisible(HologramState.Cell cell, HologramTextures.Material material, int[] normal) {
        return material.partial
            || !worldOccluders.contains(coordinate(cell.x + normal[0], cell.y + normal[1], cell.z + normal[2]));
    }

    private static void emitWorldMaterials(List<HologramState.Cell> cells, Tessellator t,
        MiaoVisonLiteMesh.Builder mesh) {
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
                if (!worldFaceVisible(c, material, normal)) continue;
                float shade = side == 1 ? 1 : side == 0 ? .65f : .85f;
                int sampleIndex = 0;
                for (HologramTextures.Sample sample : material.samples(surface)) {
                    if (mesh == null) setSampleColor(t, sample, shade, .38f);
                    else setMeshSampleColor(mesh, sample, shade, .38f);
                    for (int vertex : FACES[side]) {
                        double[] point = surface.vertices[vertex];
                        double offset = .002 + sampleIndex * .0005;
                        double x = c.x + .5 + point[0] + normal[0] * offset;
                        double y = c.y + .5 + point[1] + normal[1] * offset;
                        double z = c.z + .5 + point[2] + normal[2] * offset;
                        double u = textureU(sample.icon, side, point);
                        double v = textureV(sample.icon, side, point);
                        if (mesh == null) t.addVertexWithUV(x, y, z, u, v);
                        else mesh.vertex(x, y, z, u, v);
                    }
                    sampleIndex++;
                }
            }
        }
    }

    private static void compileWorldGeometry(List<HologramState.Cell> cells, boolean removal,
        boolean includeMaterials) {
        Tessellator t = Tessellator.instance;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (includeMaterials) {
            t.startDrawingQuads();
            emitWorldMaterials(cells, t, null);
            t.draw();
        }
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
            // Do not record an empty transform in the display list for ghosts without link nodes.
            List<HologramLinkNodePreview.Node> nodes = HologramLinkNodePreview.prepare(c, false);
            if (nodes.isEmpty()) continue;
            GL11.glPushMatrix();
            GL11.glTranslated(c.x, c.y, c.z);
            HologramLinkNodePreview.draw(nodes, .38f);
            GL11.glPopMatrix();
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        boolean dynamicPlacementColor = worldState != null && worldState.data.getBoolean("virtualPlacement")
            && !removal
            && placementColorList != null;
        if (dynamicPlacementColor) placementColorList.draw();
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
            int outline = BoundaryNeighborhood
                .worldEdges(c.x, c.y, c.z, (x, y, z) -> worldOutlineCells.contains(coordinate(x, y, z)));
            for (int edge = 0; edge < EDGES.length; edge++) {
                if ((outline & 1 << edge) == 0) continue;
                for (int v : EDGES[edge])
                    t.addVertex(c.x + .5 + VERTICES[v][0], c.y + .5 + VERTICES[v][1], c.z + .5 + VERTICES[v][2]);
            }
            lastWorldGeometry++;
        }
        t.draw();
    }

    private static final class WorldGroup {

        final int x, y, z;
        final List<HologramState.Cell> cells = new ArrayList<>();
        final List<WorldBatch> batches = new ArrayList<>();
        List<HologramState.Cell> localSource;
        int localCursor;
        boolean localHaloReady;
        MiaoVisonLiteMesh directGeometry;
        int built;
        int geometry, gt, fallback, ghosts;
        final Set<Integer> matchedIndices = new HashSet<>();
        private HologramPreviewPose boundsPose;
        private double[] rotatedBounds;

        WorldGroup(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        double[] rotatedBounds(HologramLocalPlacement placement, HologramPreviewPose pose) {
            if (boundsPose != pose) {
                rotatedBounds = placement.bounds(x, y, z);
                boundsPose = pose;
            }
            return rotatedBounds;
        }

        void releaseBatches() {
            for (WorldBatch batch : batches) batch.close();
            batches.clear();
            directGeometry = null;
        }
    }

    private static final class WorldBatch implements AutoCloseable {

        final MiaoVisonLiteMesh mesh;
        final MiaoVisonLiteDisplayList effects;

        WorldBatch(MiaoVisonLiteMesh mesh, MiaoVisonLiteDisplayList effects) {
            this.mesh = mesh;
            this.effects = effects;
        }

        void draw() {
            if (mesh != null) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                mesh.draw();
                worldDirectMeshDraws++;
            }
            effects.draw();
        }

        @Override
        public void close() {
            try {
                effects.close();
            } finally {
                if (mesh != null) mesh.close();
            }
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

    private static void setMeshSampleColor(MiaoVisonLiteMesh.Builder mesh, HologramTextures.Sample sample, float shade,
        float alpha) {
        int color = sample.color;
        mesh.color(
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
            HologramTextures.partial(cell, current),
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

    static int previewLineBatchLimit(Tessellator tess) {
        // The exact vanilla/Angelica raw layout is eight ints per vertex. 16368 vertices leave 128 ints of headroom
        // below the large-buffer shrink threshold, including addVertex's 32-int reservation. Full boxes still fit.
        return tess.getClass() == Tessellator.class ? 16368 : previewBatchLimit(tess);
    }

    private float previewOutlineDensity() {
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
        emitPreviewQuad(tess, face, sample, sampleIndex, depthScale, shiftX, shiftY, displaced, null);
    }

    private static void emitPreviewQuad(Tessellator tess, Face face, HologramTextures.Sample sample, int sampleIndex,
        double depthScale, double shiftX, double shiftY, boolean displaced, PreviewIcon icon) {
        float shade = face.side == 1 ? 1 : face.side < 2 ? .6f : .8f;
        setSampleColor(tess, sample, shade, displaced ? .38f : .96f);
        if (face.cell.sourceMissing && !face.cell.missingBlock) tess.setColorRGBA_F(.55f, .55f, .55f, .78f);
        for (int i = 0; i < 4; i++) {
            int vertex = FACES[face.side][i];
            int point = vertex * 3;
            tess.addVertexWithUV(
                face.points[point] + shiftX,
                face.points[point + 1] + shiftY,
                face.points[point + 2] * depthScale + sampleIndex * .015,
                icon != null && icon.valid ? icon.u(face.side, face.local(vertex))
                    : textureU(sample.icon, face.side, face.local(vertex)),
                icon != null && icon.valid ? icon.v(face.side, face.local(vertex))
                    : textureV(sample.icon, face.side, face.local(vertex)));
        }
    }

    /** Dynamic icons, drag transforms and extended tessellators keep the same once-only vertex commands. */
    private boolean drawPreviewQuads(Tessellator tess, List<Face> faces, double depthScale, double moveX,
        double moveY) {
        return drawPreviewQuads(tess, faces, depthScale, moveX, moveY, false);
    }

    private boolean drawPreviewQuads(Tessellator tess, List<Face> faces, double depthScale, double moveX, double moveY,
        boolean snapshotStaticIcons) {
        int vertices = 0, limit = 0;
        boolean drawn = false;
        Map<IIcon, PreviewIcon> frameIcons = snapshotStaticIcons ? new IdentityHashMap<>() : null;
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
                HologramTextures.Sample material = samples.get(sample);
                PreviewIcon icon = null;
                if (frameIcons != null && material.icon != null
                    && material.icon.getClass() == TextureAtlasSprite.class) {
                    icon = frameIcons.get(material.icon);
                    if (icon == null) {
                        icon = new PreviewIcon(material.icon);
                        icon.refresh();
                        frameIcons.put(material.icon, icon);
                    }
                }
                emitPreviewQuad(
                    tess,
                    face,
                    material,
                    sample,
                    depthScale,
                    displaced ? moveX : 0,
                    displaced ? moveY : 0,
                    displaced,
                    icon);
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

    /** Reused only by one complete editor submission; neither a wrapper nor its atlas address is immutable. */
    private static final class PreviewIcon {

        final IIcon icon;
        final boolean candidate;
        IIcon original;
        boolean valid;
        float minU, maxU, minV, maxV;
        long version;
        int lastBatch = -1;

        PreviewIcon(IIcon icon) {
            this.icon = icon;
            candidate = icon != null
                && (icon.getClass() == TextureAtlasSprite.class || icon.getClass() == FlippableIcon.class);
        }

        void refresh() {
            if (!candidate) return;
            IIcon endpoint = icon.getClass() == FlippableIcon.class ? ((FlippableIcon) icon).getOriginal() : icon;
            boolean nextValid = stablePreviewIcon(endpoint);
            boolean changed = original != endpoint || valid != nextValid;
            if (nextValid) {
                // Both U endpoints together reach the base getMinU even when the wrapper is flipped. Its real
                // Angelica hook marks usage, including enclosing SpriteCapture/display-list recording, on every draw.
                float u0 = icon.getMinU(), u1 = icon.getMaxU(), v0 = icon.getMinV(), v1 = icon.getMaxV();
                changed |= Float.floatToRawIntBits(minU) != Float.floatToRawIntBits(u0)
                    || Float.floatToRawIntBits(maxU) != Float.floatToRawIntBits(u1)
                    || Float.floatToRawIntBits(minV) != Float.floatToRawIntBits(v0)
                    || Float.floatToRawIntBits(maxV) != Float.floatToRawIntBits(v1);
                minU = u0;
                maxU = u1;
                minV = v0;
                maxV = v1;
            }
            original = endpoint;
            valid = nextValid;
            if (changed) version++;
        }

        double u(int side, double[] point) {
            double u = side == 4 ? point[2] + .5
                : side == 5 ? .5 - point[2] : side == 2 ? .5 - point[0] : point[0] + .5;
            return minU + (maxU - minU) * u;
        }

        double v(int side, double[] point) {
            double v = side == 0 ? point[2] + .5 : side == 1 ? .5 - point[2] : .5 - point[1];
            return minV + (maxV - minV) * v;
        }
    }

    /** Original sorted face/layer order, partitioned only at complete quad boundaries. */
    private static final class PreviewSubmission {

        final List<Face> faces;
        final double depthScale;
        final boolean snapshotUvs;
        final Map<IIcon, PreviewIcon> icons;
        final PreviewIcon[] iconRecords;
        final List<PreviewBatch> batches = new ArrayList<>();
        final MiaoVisonLiteSubmission<PreviewBatch> submission;

        PreviewSubmission(List<Face> faces, double depthScale) {
            this(faces, depthScale, false);
        }

        PreviewSubmission(List<Face> faces, double depthScale, boolean snapshotUvs) {
            this.faces = faces;
            this.depthScale = depthScale;
            this.snapshotUvs = snapshotUvs;
            icons = snapshotUvs ? new IdentityHashMap<>() : null;
            List<PreviewIcon> dependencies = snapshotUvs ? new ArrayList<>() : null;
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
                    IIcon icon = samples.get(sample).icon;
                    if (snapshotUvs) {
                        PreviewIcon record = icons.get(icon);
                        if (record == null) {
                            record = new PreviewIcon(icon);
                            icons.put(icon, record);
                        }
                        stable &= record.candidate;
                        if (record.lastBatch != batches.size()) {
                            record.lastBatch = batches.size();
                            dependencies.add(record);
                        }
                    } else stable &= stablePreviewIcon(icon);
                    lastFace = at;
                    lastSample = sample + 1;
                    if (++quads == MiaoVisonLiteSubmission.QUADS_PER_BATCH) {
                        batches.add(
                            new PreviewBatch(
                                firstFace,
                                firstSample,
                                lastFace,
                                lastSample,
                                quads,
                                stable,
                                dependencies));
                        if (snapshotUvs) dependencies.clear();
                        firstFace = -1;
                        quads = 0;
                        stable = true;
                    }
                }
            }
            if (quads != 0) batches
                .add(new PreviewBatch(firstFace, firstSample, lastFace, lastSample, quads, stable, dependencies));
            iconRecords = snapshotUvs ? icons.values()
                .toArray(new PreviewIcon[0]) : null;
            submission = new MiaoVisonLiteSubmission<>(batches, this::emit, this::emitMesh);
        }

        boolean draw(Tessellator tess) {
            return draw(tess, true);
        }

        boolean draw(Tessellator tess, boolean allowCapture) {
            return draw(tess, allowCapture, false);
        }

        boolean draw(Tessellator tess, boolean allowCapture, boolean allowResident) {
            if (snapshotUvs) prepareIcons();
            boolean drawn = submission.draw(tess, allowCapture, allowResident);
            if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled()) {
                previewSliceCaptures += submission.sliceCaptures();
                previewSliceHits += submission.sliceHits();
                previewSliceReplayedQuads += submission.sliceReplayedQuads();
                previewSliceUnsupported += submission.sliceUnsupported();
                previewSliceCapturedInts += submission.sliceCapturedInts();
            }
            previewResidentCaptures += submission.residentCaptures();
            previewResidentDraws += submission.residentDraws();
            previewDirectMeshUploads += submission.directMeshUploads();
            previewDirectMeshDraws += submission.directMeshDraws();
            return drawn;
        }

        private void prepareIcons() {
            for (PreviewIcon icon : iconRecords) icon.refresh();
            submission.invalidateChangedDependencies();
        }

        private void emit(Tessellator tess, PreviewBatch batch) {
            for (int at = batch.firstFace; at <= batch.lastFace; at++) {
                Face face = faces.get(at);
                if (face.empty || face.nativeModel) continue;
                List<HologramTextures.Sample> samples = face.samples();
                int first = at == batch.firstFace ? batch.firstSample : 0;
                int last = at == batch.lastFace ? batch.lastSample : samples.size();
                for (int sample = first; sample < last; sample++) emitPreviewQuad(
                    tess,
                    face,
                    samples.get(sample),
                    sample,
                    depthScale,
                    0,
                    0,
                    false,
                    snapshotUvs ? icons.get(samples.get(sample).icon) : null);
            }
        }

        private void emitMesh(MiaoVisonLiteMesh.Builder vertices, PreviewBatch batch) {
            for (int at = batch.firstFace; at <= batch.lastFace; at++) {
                Face face = faces.get(at);
                if (face.empty || face.nativeModel) continue;
                List<HologramTextures.Sample> samples = face.samples();
                int first = at == batch.firstFace ? batch.firstSample : 0;
                int last = at == batch.lastFace ? batch.lastSample : samples.size();
                for (int sample = first; sample < last; sample++) {
                    HologramTextures.Sample material = samples.get(sample);
                    PreviewIcon icon = snapshotUvs ? icons.get(material.icon) : null;
                    float shade = face.side == 1 ? 1 : face.side < 2 ? .6f : .8f;
                    if (face.cell.sourceMissing && !face.cell.missingBlock) vertices.color(.55f, .55f, .55f, .78f);
                    else setMeshSampleColor(vertices, material, shade, .96f);
                    for (int vertex : FACES[face.side]) {
                        int point = vertex * 3;
                        double[] local = face.local(vertex);
                        vertices.vertex(
                            face.points[point],
                            face.points[point + 1],
                            face.points[point + 2] * depthScale + sample * .015,
                            icon == null ? textureU(material.icon, face.side, local) : icon.u(face.side, local),
                            icon == null ? textureV(material.icon, face.side, local) : icon.v(face.side, local));
                    }
                }
            }
        }
    }

    private static final class PreviewBatch extends MiaoVisonLiteSubmission.Batch {

        final int firstFace, firstSample, lastFace, lastSample;
        final PreviewIcon[] dependencies;
        final long[] versions;

        PreviewBatch(int firstFace, int firstSample, int lastFace, int lastSample, int quads, boolean stable,
            List<PreviewIcon> dependencies) {
            super(quads, stable);
            this.firstFace = firstFace;
            this.firstSample = firstSample;
            this.lastFace = lastFace;
            this.lastSample = lastSample;
            this.dependencies = dependencies == null ? null : dependencies.toArray(new PreviewIcon[0]);
            versions = dependencies == null ? null : new long[dependencies.size()];
        }

        @Override
        protected boolean valid() {
            if (dependencies != null) for (PreviewIcon icon : dependencies) if (!icon.valid) return false;
            return true;
        }

        @Override
        protected boolean current() {
            if (dependencies != null) for (int at = 0; at < dependencies.length; at++)
                if (!dependencies[at].valid || dependencies[at].version != versions[at]) return false;
            return true;
        }

        @Override
        protected void captureVersions() {
            if (dependencies != null)
                for (int at = 0; at < dependencies.length; at++) versions[at] = dependencies[at].version;
        }

    }

    private static final class Face {

        final HologramState.Cell cell;
        final double[] points;
        final int side;
        int projectionOrder;
        double depth;
        final HologramTextures.Material material;
        final boolean empty, nativeModel;
        final HologramGeometry.Surface surface;
        boolean fadeDefault;

        Face(HologramState.Cell cell, double[] points, int side, boolean current) {
            this(cell, points, side, current, null);
        }

        Face(HologramState.Cell cell, double[] points, int side, boolean current, HologramGeometry.Surface surface) {
            this.cell = cell;
            this.points = points;
            this.side = side;
            this.surface = surface;
            empty = !cell.anchor && !cell.iconOnly && (current ? cell.id : cell.wantId).equals("minecraft:air");
            nativeModel = !empty && HologramBlockRender.ready(cell, current);
            material = empty ? null : HologramTextures.material(cell, current);
            updateDepth();
        }

        Face(PreviewCell input, double[] points, int side, HologramGeometry.Surface surface) {
            cell = input.cell;
            this.points = points;
            this.side = side;
            this.surface = surface;
            empty = input.empty;
            nativeModel = input.nativeModel;
            material = input.material;
        }

        void updateDepth() {
            double d = 0;
            for (int index : FACES[side]) d += points[index * 3 + 2];
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
                int a = face[i] * 3, b = face[j] * 3;
                double ax = points[a], ay = points[a + 1], bx = points[b], by = points[b + 1];
                if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside;
            }
            return inside;
        }
    }
}
