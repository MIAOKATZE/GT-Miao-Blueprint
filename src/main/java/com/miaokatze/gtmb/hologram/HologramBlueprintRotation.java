package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

/** Whole-draft orientation is an author edit, using the same detached transform as world placement. */
final class HologramBlueprintRotation {

    private HologramBlueprintRotation() {}

    /** Session-local provenance, never a persistent user-supplied angle or a processing approval. */
    static final class Proof {

        final String sourceId, sourceHash;
        final int savedTurns;

        Proof(String sourceId, String sourceHash, int savedTurns) {
            this.sourceId = sourceId;
            this.sourceHash = sourceHash;
            this.savedTurns = Math.floorMod(savedTurns, 4);
        }
    }

    static void rotate(HologramSession s, NBTTagCompound action) throws IOException {
        if (!HologramSessionPolicy.authoring(s) || s.cancelled
            || s.capture == null
            || s.capture.incomplete
            || s.capture.pending
            || s.capturePending
            || HologramSessionPolicy.blockedByAreaImport(s)
            || s.localPlanPending
            || s.job == 1
            || s.job == 2
            || s.job == 4) throw new IOException("请先完整载入可编辑蓝图，再调整整图朝向");
        if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision)
            throw new IOException("蓝图计划已更新，请重新调整整图朝向");
        HologramEditState before = new HologramEditState(s, true);
        Proof nextProof = nextProof(s);
        String previousStatus = s.status;
        if (!HologramWorldPlacementWorkflow.rotateBlueprint(s, () -> s.geometryEdits.begin(s))) {
            String reason = java.util.Objects.equals(previousStatus, s.status) ? "坐标或方块方向数据不支持该转向" : s.status;
            throw new IOException(reason + "；原蓝图保留");
        }
        HologramBlueprintEditor.remember(s.undoEdits, before);
        s.rotationProof = nextProof;
        s.rotationTurns = Math.floorMod(s.rotationTurns + 1, 4);
        s.redoEdits.clear();
        s.allocationsFrozen = true;
        HologramGeometryEdits.refresh(s);
        HologramAuthoringWorkflow.blueprintEdited(s, "geometry");
        s.status = "整份蓝图已水平转向 90°；保存后再次载入和放置会沿用，可撤销";
    }

    private static Proof nextProof(HologramSession s) throws IOException {
        if (!"saved".equals(s.blueprintSource) || s.virtualPreset.id == null) return null;
        Proof previous = s.rotationProof;
        if (previous != null && previous.sourceId.equals(s.virtualPreset.id)) return previous;
        HologramPresets.Preset saved = HologramLibraryFacade.get(s.player, s.virtualPreset.id);
        return saved == null ? null : new Proof(saved.id, HologramBlueprintContentHash.of(saved), s.rotationTurns);
    }

    /** No rotation proof uses the unchanged saved baseline; a changed bound source cannot reuse the proof. */
    static int approvedTurns(HologramSession s, HologramPresets.Preset actualSaved) throws IOException {
        Proof proof = s.rotationProof;
        if (proof == null) return 0;
        return actualSaved != null && s.virtualPreset != null
            && proof.sourceId.equals(s.virtualPreset.id)
            && proof.sourceId.equals(actualSaved.id)
            && proof.sourceHash.equals(HologramBlueprintContentHash.of(actualSaved))
                ? Math.floorMod(s.rotationTurns - proof.savedTurns, 4)
                : -1;
    }

    /** Call only after the exact saved artifact's successful receipt, using the actual new disk identity. */
    static void committed(HologramSession s) {
        try {
            HologramPresets.Preset saved = s.virtualPreset == null ? null
                : s.libraryPrivatePreset != null && s.virtualPreset.id != null
                    && s.virtualPreset.id.equals(s.libraryPrivatePreset.id) ? s.libraryPrivatePreset
                        : HologramLibraryFacade.get(s.player, s.virtualPreset.id);
            if (saved == null) {
                reset(s);
                return;
            }
            s.rotationProof = new Proof(saved.id, HologramBlueprintContentHash.of(saved), s.rotationTurns);
        } catch (IOException invalid) {
            // No new processing authority if the newly written baseline cannot be reread.
            reset(s);
        }
    }

    /** A new disk/source baseline invalidates the authority in older undo entries. */
    static void reset(HologramSession s) {
        s.rotationProof = null;
        s.rotationTurns = 0;
        s.rotationProofEpoch++;
    }

    static HologramPresets.Preset rotatedBaseline(HologramPresets.Preset original, int turns) throws IOException {
        HologramPresets.Preset result = copyPreset(original);
        for (int turn = 0; turn < Math.floorMod(turns, 4); turn++)
            HologramWorldPlacementWorkflow.turnSavedPreset(result);
        return result;
    }

    /** Typed detached copies avoid serializing a second complete JSON tree for the undo state. */
    static HologramPresets.Preset copyPreset(HologramPresets.Preset source) {
        HologramPresets.Preset result = HologramSnapshotMetadataSource.copyMetadata(source);
        for (HologramPresets.Cell cell : source.cells) result.cells.add(HologramSnapshotMetadataSource.copyCell(cell));
        for (HologramPresets.Cell cell : source.palette)
            result.palette.add(HologramSnapshotMetadataSource.copyCell(cell));
        return result;
    }

    /** Undo pose while retaining the current saved file identity and current processing/retention choices. */
    static HologramPresets.Preset restorePose(HologramPresets.Preset current, HologramPresets.Preset pose) {
        HologramPresets.Preset result = HologramSnapshotMetadataSource.copyMetadata(current);
        result.controllerFacing = pose.controllerFacing;
        result.hintAreas.clear();
        result.hintAreas.addAll(HologramSnapshotMetadataSource.copyMetadata(pose).hintAreas);
        for (HologramPresets.Cell cell : pose.cells) result.cells.add(HologramSnapshotMetadataSource.copyCell(cell));
        for (HologramPresets.Cell cell : current.palette)
            result.palette.add(HologramSnapshotMetadataSource.copyCell(cell));
        return result;
    }
}
