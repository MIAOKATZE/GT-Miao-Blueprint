package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

final class HologramEditState {

    final java.util.Set<Integer> sparseIndices;
    final HologramCapture sparseCapture;
    final java.util.Set<Integer> selectionIndices;
    final List<HologramCapture.Cell> geometry = new ArrayList<>();
    final List<HologramPresets.Structure> structures = new ArrayList<>();
    final List<HologramAePolicy.Issue> aeIssues;
    final Map<String, ItemStack> targets = new HashMap<>();
    final Map<String, ItemStack> automatic = new HashMap<>();
    final java.util.Set<String> selectedPositions = new java.util.LinkedHashSet<>();
    final int mode, scope, selected;
    final boolean presetSupported, presetAvailable;
    final String presetReason;
    final Map<String, Integer> facings;
    final java.util.Set<String> suppressed;
    final java.util.Set<String> virtualEdited;
    final boolean presetEnabled, presetPaused, hatchDowngrade, noHatches;
    final int hatchTier;
    // Present only for whole-blueprint turns; ordinary sparse/full edits keep their existing memory cost.
    final HologramPresets.Preset presetPose;
    final int sessionFacing;
    final java.util.Set<String> manualPositionKeys;
    final int rotationTurns;
    final long rotationProofEpoch;

    HologramEditState(HologramSession s) {
        this(s, null);
    }

    HologramEditState(HologramSession s, java.util.Set<Integer> indices) {
        this(s, indices, false);
    }

    HologramEditState(HologramSession s, boolean fullPose) {
        this(s, null, fullPose);
    }

    private HologramEditState(HologramSession s, java.util.Set<Integer> indices, boolean fullPose) {
        presetPose = fullPose ? HologramBlueprintRotation.copyPreset(s.virtualPreset) : null;
        sessionFacing = s.facing;
        manualPositionKeys = fullPose ? new java.util.HashSet<>(s.manualPositions.keySet()) : null;
        rotationTurns = s.rotationTurns;
        rotationProofEpoch = s.rotationProofEpoch;
        sparseIndices = indices == null ? null : new java.util.LinkedHashSet<>(indices);
        sparseCapture = indices == null ? null : s.capture;
        selectionIndices = new java.util.LinkedHashSet<>(s.selectedIndices);
        aeIssues = indices == null
            ? HologramAePolicy.copyIssues(s.virtualPreset == null ? null : s.virtualPreset.aeIssues)
            : null;
        if (indices == null && s.virtualPreset != null)
            for (HologramPresets.Structure original : s.virtualPreset.structures)
                structures.add(HologramBlueprintData.copyStructure(original));
        if (s.virtual && s.virtualPreset != null && s.virtualPreset.schema >= 2 && s.capture != null)
            for (int index : indices == null ? allIndices(s.capture.cells.size()) : indices) {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                geometry.add(HologramBlueprintData.blueprintCell(cell, cell.x, cell.y, cell.z));
            }
        presetSupported = s.presetSupported;
        presetAvailable = s.presetAvailable;
        presetReason = s.presetReason;
        mode = s.mode;
        scope = s.scope;
        selected = s.selected;
        if (s.capture != null) for (int index : s.selectedIndices) if (index >= 0 && index < s.capture.cells.size())
            selectedPositions.add(HologramValues.position(s.capture.cells.get(index)));
        if (indices == null) {
            if (s.capture != null) for (Map.Entry<Integer, ItemStack> entry : s.autoTargets.entrySet()) automatic.put(
                HologramValues.position(s.capture.cells.get(entry.getKey())),
                entry.getValue()
                    .copy());
            for (Map.Entry<String, ItemStack> entry : s.coordinatePins.entrySet()) targets.put(
                entry.getKey(),
                entry.getValue()
                    .copy());
            if (s.capture != null) for (Map.Entry<Integer, ItemStack> entry : s.pins.entrySet()) targets.put(
                HologramValues.position(s.capture.cells.get(entry.getKey())),
                entry.getValue()
                    .copy());
            facings = new HashMap<>(s.facingPins);
            suppressed = new java.util.HashSet<>(s.suppressedAuto);
            virtualEdited = new java.util.HashSet<>(s.virtualEdited);
        } else {
            facings = new HashMap<>();
            suppressed = new java.util.HashSet<>();
            virtualEdited = new java.util.HashSet<>();
            for (int index : indices) {
                String key = HologramValues.position(s.capture.cells.get(index));
                ItemStack pin = s.pins.containsKey(index) ? s.pins.get(index) : s.coordinatePins.get(key);
                if (pin != null) targets.put(key, pin.copy());
                ItemStack auto = s.localPlanPending ? s.frozenAutoTargets.get(key) : s.autoTargets.get(index);
                if (auto != null) automatic.put(key, auto.copy());
                if (s.facingPins.containsKey(key)) facings.put(key, s.facingPins.get(key));
                if (s.suppressedAuto.contains(key)) suppressed.add(key);
                if (s.virtualEdited.contains(key)) virtualEdited.add(key);
            }
        }
        presetEnabled = s.presetEnabled;
        presetPaused = s.presetPaused;
        hatchDowngrade = s.hatchDowngrade;
        noHatches = s.noHatches;
        hatchTier = s.hatchTier;
    }

    private static java.util.Set<Integer> allIndices(int count) {
        java.util.Set<Integer> indices = new java.util.LinkedHashSet<>();
        for (int i = 0; i < count; i++) indices.add(i);
        return indices;
    }

    HologramEditState current(HologramSession s) {
        return new HologramEditState(s, sparseIndices, presetPose != null);
    }

    boolean sameAs(HologramEditState other) {
        if (geometry.size() != other.geometry.size()) return false;
        for (int i = 0; i < geometry.size(); i++) {
            HologramCapture.Cell a = geometry.get(i), b = other.geometry.get(i);
            if (!HologramValues.position(a)
                .equals(HologramValues.position(b))
                || !a.memberships()
                    .equals(b.memberships())
                || a.block != b.block
                || a.meta != b.meta
                || a.blueprintDamage != b.blueprintDamage
                || !a.blueprintBlock.equals(b.blueprintBlock)
                || !a.blueprintItem.equals(b.blueprintItem)
                || a.excluded != b.excluded
                || !a.prompt.equals(b.prompt)
                || !a.tileNbt.equals(b.tileNbt)
                || !a.itemNbt.equals(b.itemNbt)) return false;
        }
        if (!targets.keySet()
            .equals(other.targets.keySet()) || !facings.equals(other.facings)
            || !suppressed.equals(other.suppressed)
            || !virtualEdited.equals(other.virtualEdited)
            || presetEnabled != other.presetEnabled
            || presetPaused != other.presetPaused
            || hatchDowngrade != other.hatchDowngrade
            || noHatches != other.noHatches
            || hatchTier != other.hatchTier
            || mode != other.mode
            || scope != other.scope
            || selected != other.selected
            || !selectedPositions.equals(other.selectedPositions)
            || !automatic.keySet()
                .equals(other.automatic.keySet()))
            return false;
        for (String key : automatic.keySet())
            if (!HologramValues.same(automatic.get(key), other.automatic.get(key))) return false;
        for (String key : targets.keySet())
            if (!HologramValues.same(targets.get(key), other.targets.get(key))) return false;
        return true;
    }

    void apply(HologramSession s) {
        // The disk baseline does not travel through undo. Only this draft's orientation does.
        if (rotationProofEpoch == s.rotationProofEpoch) s.rotationTurns = rotationTurns;
        if (presetPose != null) {
            s.virtualPreset = HologramBlueprintRotation.restorePose(s.virtualPreset, presetPose);
            s.facing = sessionFacing;
        }
        if (sparseIndices != null) {
            int row = 0;
            for (int index : sparseIndices) {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                String key = HologramValues.position(cell);
                if (!geometry.isEmpty()) {
                    s.cellIndices.remove(cell);
                    cell = HologramBlueprintData.blueprintCell(geometry.get(row++), cell.x, cell.y, cell.z);
                    s.capture.cells.set(index, cell);
                    cell.stableIndex = index;
                    s.capture.positions.put(key, cell);
                    s.cellIndices.put(cell, index);
                    if (s.manualPositions.containsKey(key)) s.manualPositions.put(key, cell);
                }
                s.pins.remove(index);
                s.coordinatePins.remove(key);
                if (targets.containsKey(key) && HologramCellPolicy.acceptableSample(s, cell, targets.get(key))) {
                    s.pins.put(
                        index,
                        targets.get(key)
                            .copy());
                    s.coordinatePins.put(
                        key,
                        targets.get(key)
                            .copy());
                }
                s.autoTargets.remove(index);
                s.frozenAutoTargets.remove(key);
                if (automatic.containsKey(key) && HologramCellPolicy.acceptableSample(s, cell, automatic.get(key))) {
                    s.autoTargets.put(
                        index,
                        automatic.get(key)
                            .copy());
                    s.frozenAutoTargets.put(
                        key,
                        automatic.get(key)
                            .copy());
                }
                s.facingPins.remove(key);
                if (facings.containsKey(key)) {
                    ItemStack target = s.pins.containsKey(index) ? s.pins.get(index)
                        : HologramCellPolicy.draftTarget(s, cell, index);
                    for (int direction : HologramCellPolicy.legalFacings(s, cell, target))
                        if (direction == facings.get(key)) s.facingPins.put(key, direction);
                }
                if (suppressed.contains(key)) s.suppressedAuto.add(key);
                else s.suppressedAuto.remove(key);
                if (virtualEdited.contains(key)) s.virtualEdited.add(key);
                else s.virtualEdited.remove(key);
            }
            s.mode = mode;
            s.scope = scope;
            s.selected = selected;
            s.selectedIndices.clear();
            for (int index : selectionIndices)
                if (index >= 0 && index < s.capture.cells.size()) s.selectedIndices.add(index);
            s.presetSupported = presetSupported;
            s.presetAvailable = presetAvailable;
            s.presetReason = presetReason;
            s.presetEnabled = presetEnabled;
            s.presetPaused = presetPaused;
            s.hatchDowngrade = hatchDowngrade;
            s.noHatches = noHatches;
            s.hatchTier = hatchTier;
            s.allocationsFrozen = true;
            return;
        }
        if (s.virtualPreset != null) {
            s.virtualPreset.aeIssues = HologramAePolicy.copyIssues(aeIssues);
            s.virtualPreset.structures.clear();
            for (HologramPresets.Structure original : structures)
                s.virtualPreset.structures.add(HologramBlueprintData.copyStructure(original));
        }
        if (s.virtual && s.virtualPreset != null && s.virtualPreset.schema >= 2) {
            HologramCapture capture = new HologramCapture(
                null,
                HologramSessionPolicy.trigger(s),
                s.capture.captureIntent);
            capture.inheritSources(s.capture);
            capture.sourceMetadata = (NBTTagCompound) s.capture.sourceMetadata.copy();
            for (HologramCapture.Cell saved : geometry) {
                HologramCapture.Cell cell = HologramBlueprintData.blueprintCell(saved, saved.x, saved.y, saved.z);
                capture.cells.add(cell);
                capture.positions.put(HologramValues.position(cell), cell);
            }
            s.capture = capture;
            s.selectedIndices.clear();
        }
        // History restores exact positions, never reruns quota allocation.
        s.allocationsFrozen = true;
        s.frozenAutoTargets.clear();
        for (Map.Entry<String, ItemStack> entry : automatic.entrySet()) s.frozenAutoTargets.put(
            entry.getKey(),
            entry.getValue()
                .copy());
        s.presetSupported = presetSupported;
        s.presetAvailable = presetAvailable;
        s.presetReason = presetReason;
        s.mode = mode;
        s.scope = scope;
        s.selected = selected;
        s.selectedIndices.clear();
        for (int index = 0; index < s.capture.cells.size(); index++)
            if (selectedPositions.contains(HologramValues.position(s.capture.cells.get(index))))
                s.selectedIndices.add(index);
        s.pins.clear();
        s.autoTargets.clear();
        s.coordinatePins.clear();
        s.facingPins.clear();
        s.suppressedAuto.clear();
        s.virtualEdited.clear();
        s.virtualEdited.addAll(virtualEdited);
        s.presetEnabled = presetEnabled;
        s.presetPaused = presetPaused;
        s.hatchDowngrade = hatchDowngrade;
        s.noHatches = noHatches;
        s.hatchTier = hatchTier;
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.anchor) continue;
            String key = HologramValues.position(cell);
            ItemStack automaticTarget = automatic.get(key);
            if (HologramCellPolicy.acceptableSample(s, cell, automaticTarget))
                s.autoTargets.put(i, automaticTarget.copy());
            ItemStack target = targets.get(key);
            if (HologramCellPolicy.acceptableSample(s, cell, target)) {
                s.pins.put(i, target.copy());
                s.coordinatePins.put(key, target.copy());
            }
            if (suppressed.contains(key)) s.suppressedAuto.add(key);
        }
        // Validate directions again against the current capture; rescans can change a cell's legal faces.
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            Integer direction = facings.get(HologramValues.position(cell));
            if (direction == null || cell.anchor) continue;
            ItemStack target = s.pins.get(i);
            if (target == null) target = HologramCellPolicy.draftTarget(s, cell, i);
            for (int allowed : HologramCellPolicy.legalFacings(s, cell, target))
                if (allowed == direction) s.facingPins.put(HologramValues.position(cell), direction);
        }
        for (HologramCapture.Cell cell : s.capture.cells) cell.candidates = null;
        if (manualPositionKeys != null) {
            s.manualPositions.clear();
            for (String key : manualPositionKeys) {
                HologramCapture.Cell cell = s.capture.positions.get(key);
                if (cell != null) s.manualPositions.put(key, cell);
            }
        }
    }
}
