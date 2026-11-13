// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Bounded current-draft placement preparation; neither the worker nor publication saves or writes the world. */
final class HologramCurrentPlacementPreparation {

    private final HologramSession s;
    private final HologramCapture capture;
    private final HologramPresets.Preset original;
    private final long revision, epoch;
    private final boolean reviewed;
    private final int dimension, x, y, z;
    private final HologramSnapshotDataContext data = new HologramSnapshotDataContext();
    private final HologramSnapshotMetadataSource source;
    private HologramBlueprintValidationCache validation;
    private FutureTask<HologramPresets.Preset> worker;
    private final java.util.Set<Integer> generatedGeometry = new java.util.HashSet<>();
    private int cursor;
    private boolean projected;

    HologramCurrentPlacementPreparation(HologramSession s) {
        this.s = s;
        capture = s.capture;
        original = s.virtualPreset;
        revision = s.planRevision;
        epoch = s.editorWorkspaceEpoch;
        reviewed = s.processingReviewed;
        dimension = s.dimension;
        x = s.x;
        y = s.y;
        z = s.z;
        source = new HologramSnapshotMetadataSource(s, data, true);
    }

    boolean ready() {
        return worker == null || worker.isDone();
    }

    private boolean current() {
        return s.currentPlacementTask == this && HologramWorkspace.find(s.player.getUniqueID()) == s
            && !s.cancelled
            && s.capture == capture
            && s.virtualPreset == original
            && s.planRevision == revision
            && s.editorWorkspaceEpoch == epoch
            && s.x == x
            && s.y == y
            && s.z == z
            && s.processingReviewed == reviewed
            && HologramSessionPolicy.valid(s)
            && HologramSessionPolicy.authoring(s)
            && !s.capturePending
            && !s.localPlanPending
            && !capture.incomplete
            && !capture.pending
            && capture.sourceCurrent()
            && s.player.capabilities.allowEdit
            && s.attachmentTask == null
            && !HologramAttachmentUpload.pending(s)
            && s.blueprintSaveReceipt.isEmpty()
            && s.job != 1
            && s.job != 2
            && s.job != 4;
    }

    void cancel() {
        if (worker != null) HologramAreaPreparation.cancel(worker);
        if (s.currentPlacementTask == this) s.currentPlacementTask = null;
    }

    void tick() {
        tick(128, HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(2_000_000L)), true);
    }

    /** The test port retains the actual continuation and worker, without requiring a transport connection. */
    void tick(int maxWork, long deadline, boolean publish) {
        try {
            if (!current()) throw new IOException("蓝图会话或计划已变化，放置准备已取消；草稿保留");
            if (!projected) {
                if (!source.step(maxWork, deadline)) return;
                projected = true;
                validation = new HologramBlueprintValidationCache(source.result);
                return;
            }
            HologramPresets.Preset draft = source.result;
            // Native factories, material identities and filtering always remain on the owner thread, one row at a time.
            int cells = draft.cells.size(), total = cells + draft.palette.size();
            for (int work = 0; cursor < total && work < maxWork; work++) {
                if (work > 0 && System.nanoTime() >= deadline) return;
                boolean placed = cursor < cells;
                int index = cursor++;
                HologramPresets.Cell cell = placed ? draft.cells.get(index) : draft.palette.get(index - cells);
                if (placed && !HologramWorldFlow
                    .worldPosition(x + cell.x, y + cell.y, z + cell.z, s.player.worldObj.getHeight()))
                    throw new IOException("蓝图结构范围超出世界边界或高度");
                if (placed) {
                    HologramCapture.Cell originalCell = capture.cells.get(index);
                    HologramAttachmentPlacementProof proof = originalCell.attachmentPlacementProof;
                    if (proof != null && proof.matches(originalCell, cell)) generatedGeometry.add(index);
                }
                HologramPresets.validateBlueprintCell(cell, placed, validation);
                validation.filter(cell);
                data.freezeBlock(cell);
            }
            if (cursor < total) return;
            if (worker == null) {
                HologramPresets.Preset frozen = draft;
                java.util.Set<Integer> frozenGeometry = java.util.Collections
                    .unmodifiableSet(new java.util.HashSet<>(generatedGeometry));
                HologramSnapshotDataContext frozenData = data;
                boolean wasReviewed = reviewed;
                int frozenDimension = dimension, frozenX = x, frozenY = y, frozenZ = z;
                FutureTask<HologramPresets.Preset> candidate = new FutureTask<>(
                    () -> prepareData(
                        frozen,
                        frozenGeometry,
                        frozenData,
                        wasReviewed,
                        frozenDimension,
                        frozenX,
                        frozenY,
                        frozenZ));
                if (HologramAreaPreparation.execute(candidate)) worker = candidate;
                return;
            }
            if (!worker.isDone()) return;
            draft = worker.get();
            if (!current() || !s.player.worldObj.blockExists(x, y, z)
                || !HologramSessionPolicy.previewReach(s.player, x, y, z)) throw new IOException("放置准备期间会话或位置已变化；草稿保留");
            s.currentPlacementTask = null;
            HologramWorldPlacementWorkflow.publishCurrentPlacement(s, draft);
            if (publish) HologramSnapshots.send(s);
        } catch (IOException | ExecutionException | RuntimeException failure) {
            cancel();
            s.status = "放置准备失败；当前草稿保留：" + failure.getMessage();
            if (publish && HologramWorkspace.find(s.player.getUniqueID()) == s) HologramSnapshots.send(s);
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            cancel();
        }
    }

    private static HologramPresets.Preset prepareData(HologramPresets.Preset draft, java.util.Set<Integer> geometry,
        HologramSnapshotDataContext context, boolean reviewed, int dimension, int x, int y, int z) throws IOException {
        HologramSnapshotMetadataSource.enterDetached(context);
        String id = draft.id;
        try {
            if (draft.id == null || draft.id.isEmpty()) draft.id = "draft";
            HologramPresets.validatePlacementData(draft);
            if (!reviewed) {
                if (requiredStages(draft, geometry, dimension, x, y, z) != 0)
                    throw new IOException("请先完成蓝图处理流程，再放置当前蓝图；无需保存");
            } else if (!HologramBlueprintProcessing.complete(draft)) throw new IOException("蓝图处理结果已变化，请重新核对；无需保存");
            return draft;
        } finally {
            draft.id = id;
            HologramSnapshotMetadataSource.exitDetached();
        }
    }

    /** Only exact owner-attested generated geometry is exempt from author NBT choices; final payloads stay intact. */
    private static int requiredStages(HologramPresets.Preset draft, java.util.Set<Integer> geometry, int dimension,
        int x, int y, int z) throws IOException {
        java.util.Map<Integer, String[]> retained = new java.util.HashMap<>();
        try {
            for (int index : geometry) {
                HologramPresets.Cell cell = draft.cells.get(index);
                retained.put(index, new String[] { cell.tileNbt, cell.itemNbt });
                cell.tileNbt = null;
                cell.itemNbt = null;
            }
            return HologramBlueprintProcessing.requiredStages(draft, dimension, x, y, z);
        } finally {
            for (java.util.Map.Entry<Integer, String[]> entry : retained.entrySet()) {
                HologramPresets.Cell cell = draft.cells.get(entry.getKey());
                cell.tileNbt = entry.getValue()[0];
                cell.itemNbt = entry.getValue()[1];
            }
        }
    }
}
