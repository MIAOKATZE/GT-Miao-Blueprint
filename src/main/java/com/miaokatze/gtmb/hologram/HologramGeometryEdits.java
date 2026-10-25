package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Author geometry has no material authority. Publish its topology now and validate at the next workflow boundary. */
final class HologramGeometryEdits {

    private List<HologramCapture.Cell> baseline;
    private long baselineRevision;
    private HologramCapture published;
    private long publishedRevision;
    private final Set<Integer> forced = new java.util.LinkedHashSet<>();
    boolean validationPending;
    long fullValidations;
    Map<String, Integer> payloadSizes = new java.util.HashMap<>();

    void begin(HologramSession s) {
        if (baseline != null) return;
        if (!s.cellDeltaProtocol || s.open
            || !HologramSessionPolicy.authoring(s)
            || !(s.localEdits.current(s) || published == s.capture && publishedRevision == s.planRevision)) return;
        baseline = new ArrayList<>(s.capture.cells);
        baselineRevision = s.planRevision;
    }

    void changed(HologramSession s, Set<Integer> indices) {
        begin(s);
        forced.addAll(indices);
    }

    /** Keep untouched indices, reuse a moving cell's slot, and fill deletion holes from the tail. */
    static List<HologramCapture.Cell> stableOrder(List<HologramCapture.Cell> previous,
        Map<String, HologramCapture.Cell> staged, List<HologramCapture.Cell> copies) {
        List<HologramCapture.Cell> result = new ArrayList<>(Collections.nCopies(staged.size(), null));
        Set<HologramCapture.Cell> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = 0; index < Math.min(previous.size(), result.size()); index++) {
            HologramCapture.Cell retained = staged.get(HologramValues.position(previous.get(index)));
            if (retained != null && used.add(retained)) result.set(index, retained);
        }
        for (HologramCapture.Cell copy : copies) if (copy.stableIndex >= 0 && copy.stableIndex < result.size()
            && result.get(copy.stableIndex) == null
            && used.add(copy)) result.set(copy.stableIndex, copy);
        List<HologramCapture.Cell> remaining = new ArrayList<>();
        for (HologramCapture.Cell cell : staged.values()) if (!used.contains(cell)) remaining.add(cell);
        int tail = remaining.size();
        for (int index = 0; index < result.size(); index++)
            if (result.get(index) == null) result.set(index, remaining.get(--tail));
        return result;
    }

    /** Only a published editable draft may use this path; placement keeps the complete planner. */
    static void refresh(HologramSession s) {
        s.localEdits.invalidate();
        s.ae.invalidate();
        s.planRevision++;
        s.planTargets.clear();
        s.planFacings.clear();
        s.cellIndices.clear();
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            cell.stableIndex = index;
            s.cellIndices.put(cell, index);
            cell.status = "preview";
            // Targets have already been transferred by coordinate, without native validation or world observation.
            String position = HologramValues.position(cell);
            s.planTargets.add(
                s.coordinatePins.containsKey(position) ? s.coordinatePins.get(position)
                    : HologramCellPolicy.draftTarget(s, cell, index));
        }
        s.indexedCapture = s.capture;
        HologramResourcePlanner.clearAuthoringQuotes(s);
        s.blueprintTotalResources = null;
        s.planMissing = 0;
        s.replacementAuthorized = false;
        s.geometryEdits.validationPending = true;
    }

    static void invalidateSource(HologramSession s) {
        s.geometryEdits.validationPending = true;
        s.blueprintDirty = true;
        s.blueprintSaveReceipt = "";
        s.processingReviewed = false;
        s.decorLedger = new HologramDecorMaterials.Ledger();
        HologramAuthoringWorkflow.clearSourceSwitch(s);
        if (s.processingPolicy != null) {
            int stage = HologramBlueprintProcessing.STAGES.indexOf("coordinates");
            while (s.processingPolicy.confirmedStages.size() > stage)
                s.processingPolicy.confirmedStages.remove(s.processingPolicy.confirmedStages.size() - 1);
            s.processingPolicy.coordinateOverrides.clear();
            s.processingPolicy.cellNbtOverrides.clear();
            s.processingPolicy.resourceDecisions.clear();
        }
        s.ae.invalidateReviews();
    }

    /** Failure preserves the dirty flag and old source; no save, processing or construction receives stale geometry. */
    static void validate(HologramSession s) throws IOException {
        if (!s.geometryEdits.validationPending) return;
        s.geometryEdits.fullValidations++;
        HologramPresets.Preset current = HologramBlueprintProjection
            .blueprint(s, s.virtualPreset == null ? "未命名蓝图" : s.virtualPreset.name);
        HologramBlueprintProcessing.invalidate(current, "geometry", s.processingPolicy);
        HologramAuthoringWorkflow.validateAuthorDraft(current);
        // Keep the editor's default-shell object; the next workflow owns the validated current projection.
        if (s.virtualPreset != null) s.virtualPreset.processingNbt = current.processingNbt;
        s.processingSource = current;
        s.geometryEdits.validationPending = false;
    }

    static boolean requirePlan(HologramSession s) {
        try {
            validate(s);
            return true;
        } catch (IOException | RuntimeException invalid) {
            s.planRecoveryFits = false;
            s.planMissing = 1;
            s.forcePlacementReady = false;
            s.expectedBlocks.clear();
            s.status = "蓝图完整校验失败，未进入材料规划或施工：" + invalid.getMessage();
            return false;
        }
    }

    static boolean sendDelta(HologramSession s) {
        NBTTagCompound state = delta(s);
        if (state == null) return false;
        HologramWorkspace.snapshotSink()
            .send(s.player, state);
        return true;
    }

    static void pendingMetadata(HologramSession s, NBTTagCompound state) {
        state.setBoolean("geometryValidationPending", true);
        state.setBoolean("blueprintProcessingComplete", false);
        state.setBoolean("blueprintProcessingRequired", true);
        state.setBoolean("blueprintDirectSave", false);
        state.setBoolean("processingResumeAllowed", false);
        state.setString("processingError", "本次几何修改将在进入蓝图处理、保存或施工前完整校验");
        for (String key : new String[] { "cellNbtEntries", "cellNbtOverrides", "processingRelations",
            "processingNbtKeys", "processingNbtPaths", "selectedCellNbtEntries", "resourceReview", "resourceBlockers",
            "aeIssues" }) state.setTag(key, new NBTTagList());
        NBTTagCompound processing = new NBTTagCompound();
        processing.setBoolean("complete", false);
        processing.setInteger("requiredStages", 63);
        if (s.processingPolicy != null) {
            processing.setTag("policy", s.processingPolicy.write());
            state.setTag("processingPolicy", s.processingPolicy.write());
        }
        state.setTag("processing", processing);
        state.setTag("processingPatch", processing);
        NBTTagCompound bindings = new NBTTagCompound();
        bindings.setBoolean("nativeAuthority", false);
        bindings.setBoolean("terminalSecurityChecked", false);
        bindings.setTag("groups", new NBTTagList());
        bindings.setTag("inventory", new NBTTagList());
        state.setTag("networkBindings", bindings);
    }

    static NBTTagCompound delta(HologramSession s) {
        HologramGeometryEdits edit = s.geometryEdits;
        if (edit.baseline == null) return null;
        if (!HologramSessionPolicy.authoring(s) || s.open
            || s.cancelled
            || s.capturePending
            || s.planRevision <= edit.baselineRevision) {
            edit.baseline = null;
            edit.forced.clear();
            return null;
        }
        int changed = 0;
        for (int index = 0; index < s.capture.cells.size(); index++)
            if (index >= edit.baseline.size() || edit.baseline.get(index) != s.capture.cells.get(index)
                || edit.forced.contains(index)) changed++;
        // Large edits use the bounded full DTO continuation; its pending metadata still defers complete validation.
        if (changed > 16384) {
            edit.baseline = null;
            edit.forced.clear();
            edit.published = null;
            return null;
        }
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("partial", true);
        state.setBoolean("topology", true);
        state.setString("snapshotKind", "geometryDelta");
        state.setString("session", s.id);
        state.setString("generation", s.id);
        state.setLong("baseRevision", edit.baselineRevision);
        state.setLong("planRevision", s.planRevision);
        state.setInteger("baseCellCount", edit.baseline.size());
        state.setInteger("cellCount", s.capture.cells.size());
        state.setLong("uiSequence", s.uiSequence);
        state.setLong("batchSeq", s.batchSeq);
        state.setString("phase", s.phase);
        state.setString("status", s.status);
        state.setInteger("selected", s.selected);
        state.setIntArray(
            "selectedIndices",
            s.selectedIndices.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        state.setInteger("scope", s.scope);
        state.setInteger("facing", s.facing);
        state.setString("activeStructure", s.blueprintActiveStructure);
        state.setTag("structures", HologramSnapshots.structureLabels(s));
        state.setBoolean("blueprintDirty", true);
        state.setBoolean("blueprintProcessingComplete", false);
        state.setBoolean("blueprintProcessingRequired", true);
        state.setBoolean("blueprintDirectSave", false);
        state.setBoolean("geometryValidationPending", edit.validationPending);
        state.setBoolean("quotesIncomplete", true);
        state.setBoolean("planIncomplete", false);
        state.setBoolean("localPlanPending", false);
        state.setBoolean("editCanUndo", !s.undoEdits.isEmpty());
        state.setBoolean("editCanRedo", !s.redoEdits.isEmpty());
        state.setBoolean("editCanPaste", s.editClipboard != null || !s.blueprintClipboard.isEmpty());
        state.setTag("aeSaveReview", new NBTTagCompound());
        state.setBoolean("blueprintSavePending", false);
        state.setInteger("total", s.capture.cells.size());
        state.setInteger("workTotal", 0);
        state.setInteger("protected", 0);
        pendingMetadata(s, state);
        if (s.processingPolicy != null) state
            .setTag("processingConfirmedStages", HologramSessionPolicy.nbtKeys(s.processingPolicy.confirmedStages));
        NBTTagList rows = new NBTTagList();
        HologramSnapshots.CellRows context = new HologramSnapshots.CellRows(s);
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (index < edit.baseline.size() && edit.baseline.get(index) == cell && !edit.forced.contains(index))
                continue;
            NBTTagCompound row = HologramSnapshots.cellRowData(s, index, true, context).row;
            row.setInteger("index", index);
            rows.appendTag(row);
        }
        state.setTag("cells", rows);
        edit.baseline = null;
        edit.forced.clear();
        edit.published = s.capture;
        edit.publishedRevision = s.planRevision;
        return state;
    }
}
