// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

/** Connects the editor, library, and world render entry points to their own Lite caches. */
final class HologramRenderer {

    private static final MiaoVisonLiteRenderer EDITOR = new MiaoVisonLiteRenderer(
        HologramPreviewAssets.EDITOR,
        MiaoVisonLiteRenderer.View.INTERACTIVE);
    private static final MiaoVisonLiteRenderer BROWSE = new MiaoVisonLiteRenderer(
        new HologramPreviewAssets(),
        MiaoVisonLiteRenderer.View.LIBRARY);
    private static final MiaoVisonLiteRenderer QUANTUM = new MiaoVisonLiteRenderer(
        new HologramPreviewAssets(),
        MiaoVisonLiteRenderer.View.INTERACTIVE);
    private static final HologramPreviewAssets WORLD = new HologramPreviewAssets();

    static int projectionLayer = Integer.MAX_VALUE;

    private HologramRenderer() {}

    static MiaoVisonLiteRenderer editor() {
        return EDITOR;
    }

    static MiaoVisonLiteRenderer browse() {
        return BROWSE;
    }

    static MiaoVisonLiteRenderer quantum() {
        return QUANTUM;
    }

    static void release() {
        EDITOR.close();
        BROWSE.close();
        QUANTUM.close();
        MiaoVisonLiteRenderer.releaseWorld();
        WORLD.clear();
        HologramLinkNodePreview.clearTextures();
    }

    static void sceneClosed() {
        EDITOR.sceneClosed();
        BROWSE.sceneClosed();
    }

    static void controllerDetailsPublished(HologramState previous, HologramState next) {
        if (next.reusesControllerGeometry(previous)) EDITOR.retainLocalPresentation(previous, next);
    }

    static void localBlueprintPublished(HologramState previous, HologramState next) {
        if (!next.follows(previous) || MiaoVisonLiteRenderer.localRenderMetadataChanged(previous, next)) return;
        if (previous.cells == next.cells && previous.regions == next.regions) {
            EDITOR.retainLocalPresentation(previous, next);
        } else if (EDITOR.owns(previous) && next.changedIndices.length > 0) {
            EDITOR.snapshotChanged(previous, next, HologramRegionCache.affected(next));
        }
    }

    static void snapshotChanged(HologramState previous, HologramState next) {
        if (!next.follows(previous) || MiaoVisonLiteRenderer.renderMetadataChanged(previous, next)) {
            EDITOR.close();
            MiaoVisonLiteRenderer.releaseWorld();
            WORLD.clear();
            return;
        }
        Set<HologramRegionCache.Key> dirty = HologramRegionCache.affected(next);
        EDITOR.snapshotChanged(previous, next, dirty);
        try (HologramPreviewAssets.Scope ignored = WORLD.enter()) {
            MiaoVisonLiteRenderer.worldSnapshotChanged(previous, next, dirty, WORLD);
        }
    }

    static void world(HologramState state, float partialTicks) {
        try (HologramPreviewAssets.Scope ignored = WORLD.enter()) {
            MiaoVisonLiteRenderer.world(state, partialTicks, projectionLayer);
        }
    }

    static void jobVisualChanged(HologramState state, int[] indices) {
        try (HologramPreviewAssets.Scope ignored = WORLD.enter()) {
            MiaoVisonLiteRenderer.jobVisualChanged(state, indices);
        }
    }

    static void nativeSnapshotChanged() {
        MiaoVisonLiteRenderer.releaseWorld();
        WORLD.clear();
    }

    static void editorNativeSnapshotChanged() {
        EDITOR.nativeSnapshotChanged();
    }

    static void cancelBrowseTopology(HologramState state) {
        BROWSE.cancelBrowseTopology(state);
    }

    static void releaseInactiveBrowse(HologramState state) {
        BROWSE.releaseInactiveBrowse(state);
    }

    static MiaoVisonLiteRenderer viewportFor(HologramState state) {
        return state != null && (BROWSE.owns(state) || state == HologramLibraryClient.INSTANCE.browsePreviewState())
            ? BROWSE : EDITOR;
    }

    static HologramState previewDisplayState() {
        return EDITOR.previewDisplayState();
    }

    static boolean previewMeshReady(HologramState state) {
        return viewportFor(state).previewMeshReady(state);
    }

    static int previewMeshCompleted(HologramState state) {
        return viewportFor(state).previewMeshCompleted(state);
    }

    static int previewMeshTotal(HologramState state) {
        return MiaoVisonLiteRenderer.previewMeshTotal(state);
    }

    static int pick(int x, int y) {
        return EDITOR.pick(x, y);
    }

    static String pickStructure(int x, int y) {
        return EDITOR.pickStructure(x, y);
    }

    static int[] pickAdjacentVoxel(int x, int y) {
        return EDITOR.pickAdjacentVoxel(x, y);
    }

    static String hintAt(int x, int y) {
        return EDITOR.hintAt(x, y);
    }

    static double[] project(double x, double y, double z) {
        return EDITOR.project(x, y, z);
    }

    static double gridScale() {
        return EDITOR.gridScale();
    }

    static Map<String, int[]> structureBounds(HologramState state) {
        return EDITOR.structureBounds(state);
    }

    static NBTTagCompound lessonInspection(HologramState state) {
        return EDITOR.lessonInspection(state);
    }
}
