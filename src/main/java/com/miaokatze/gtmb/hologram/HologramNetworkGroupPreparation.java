package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.item.ItemStack;

/** Automatic network deidentification, with private preparation and one coherent owner-thread publication. */
final class HologramNetworkGroupPreparation {

    private final HologramSnapshotMetadataSource source;
    private final HologramSnapshotDataContext dataContext;
    private final Map<String, String> networks = new HashMap<>();
    private final Map<String, HologramPresets.Target> nativeShells = new HashMap<>();
    private final Map<Object, String> identities = new HashMap<>();
    private HologramPreparedBlueprintValidation validation;
    private HologramEditState.Preparation history;
    private HologramSession staged;
    private HologramPresets.Preset prepared;
    private HologramAreaPreparation sanitation;
    private Iterator<?> entries;
    private int stage, cursor;
    private boolean waiting;

    HologramNetworkGroupPreparation(HologramSession s) {
        dataContext = new HologramSnapshotDataContext();
        source = new HologramSnapshotMetadataSource(s, dataContext, true);
    }

    boolean done() {
        return stage == 18;
    }

    boolean waiting() {
        return waiting;
    }

    String phase() {
        if (stage == 0) return "build.network.project";
        if (stage == 1) return "build.network.freeze";
        if (stage == 2) return "build.network.queued";
        if (stage == 3) return "build.network." + sanitation.progress().phase;
        if (stage == 4) return "build.network.validate";
        if (stage == 5) return "build.network.history";
        return stage == 17 ? "build.network.publish" : "build.network.stage";
    }

    long completed() {
        if (stage == 0) return source.done();
        if (stage == 3) return sanitation.progress().done;
        if (stage == 4) return validation.completed();
        return cursor;
    }

    long total() {
        if (stage == 0) return source.total();
        if (stage == 3) return sanitation.progress().total;
        if (stage == 4) return validation.total();
        return prepared == null ? source.result.cells.size() : prepared.cells.size();
    }

    void cancel() {
        if (sanitation != null) sanitation.cancel();
        dataContext.close();
    }

    /** All world/native access stays on this owner continuation; only pure DTO passes use the area worker. */
    void advance(HologramSession s) throws IOException {
        waiting = false;
        if (stage == 0) {
            try {
                if (source.step(1, Long.MAX_VALUE)) {
                    // The canonicalization worker never enters/borrows this context; its DTO owns encoded inputs.
                    dataContext.close();
                    next();
                }
            } catch (HologramSnapshotDataContext.Busy busy) {
                waiting = true;
            }
        } else if (stage == 1) {
            if (cursor < source.result.cells.size()) {
                HologramPresets.Cell cell = source.result.cells.get(cursor++);
                HologramNetworkBindings.captureNetwork(cell, networks, identities);
                HologramAePolicy.captureNativeShell(cell, nativeShells);
            } else if (!identities.isEmpty()) {
                // Do not retain live source grids in a queued worker, or hide a large map clear in admission.
                if (entries == null) entries = identities.keySet()
                    .iterator();
                entries.next();
                entries.remove();
            } else next();
        } else if (stage == 2) {
            source.result.deidentificationLevel = Math.max(1, HologramDeidentification.level(source.result));
            sanitation = new HologramAreaPreparation(networks, nativeShells);
            sanitation.start(source.result);
            next();
        } else if (stage == 3) {
            sanitation.advance(1, Long.MAX_VALUE);
            waiting = sanitation.waiting();
            if (!sanitation.ready()) return;
            prepared = sanitation.result();
            validation = new HologramPreparedBlueprintValidation(prepared, true);
            next();
        } else if (stage == 4) {
            if (!validation.done()) validation.advance();
            else {
                history = new HologramEditState.Preparation(s);
                next();
            }
        } else if (stage == 5) {
            if (!history.done()) history.advance();
            else {
                staged = new HologramSession(s.player, s.x, s.y, s.z, false, s.side);
                staged.virtual = true;
                staged.virtualPreset = prepared;
                staged.allocationsFrozen = s.allocationsFrozen;
                staged.capture = new HologramCapture(s.capture.context, s.capture.trigger);
                staged.capture.extensionReports = (net.minecraft.nbt.NBTTagCompound) s.capture.extensionReports.copy();
                next();
            }
        } else if (stage == 6) {
            if (cursor < s.capture.cells.size()) {
                int index = cursor++;
                HologramCapture.Cell old = s.capture.cells.get(index);
                HologramCapture.Cell cell = HologramBlueprintData.blueprintCell(old, old.x, old.y, old.z);
                cell.status = old.status;
                cell.actualDisplay = (net.minecraft.nbt.NBTTagCompound) old.actualDisplay.copy();
                cell.targetDisplay = (net.minecraft.nbt.NBTTagCompound) old.targetDisplay.copy();
                cell.stableIndex = index;
                staged.capture.cells.add(cell);
                staged.capture.positions.put(HologramValues.position(cell), cell);
                staged.cellIndices.put(cell, index);
            } else next();
        } else if (stage >= 7 && stage <= 10) {
            Map<?, ItemStack> from = stage == 7 ? s.pins
                : stage == 8 ? s.autoTargets : stage == 9 ? s.coordinatePins : s.frozenAutoTargets;
            if (entries == null) entries = from.entrySet()
                .iterator();
            if (entries.hasNext()) {
                @SuppressWarnings("unchecked")
                Map.Entry<Object, ItemStack> entry = (Map.Entry<Object, ItemStack>) entries.next();
                if (stage == 7) staged.pins.put(
                    (Integer) entry.getKey(),
                    entry.getValue()
                        .copy());
                else if (stage == 8) staged.autoTargets.put(
                    (Integer) entry.getKey(),
                    entry.getValue()
                        .copy());
                else if (stage == 9) staged.coordinatePins.put(
                    (String) entry.getKey(),
                    entry.getValue()
                        .copy());
                else staged.frozenAutoTargets.put(
                    (String) entry.getKey(),
                    entry.getValue()
                        .copy());
            } else next();
        } else if (stage == 11) {
            if (entries == null) entries = s.facingPins.entrySet()
                .iterator();
            if (entries.hasNext()) {
                @SuppressWarnings("unchecked")
                Map.Entry<String, Integer> entry = (Map.Entry<String, Integer>) entries.next();
                staged.facingPins.put(entry.getKey(), entry.getValue());
            } else next();
        } else if (stage == 12 || stage == 13) {
            if (entries == null) entries = (stage == 12 ? s.suppressedAuto : s.virtualEdited).iterator();
            if (entries.hasNext())
                (stage == 12 ? staged.suppressedAuto : staged.virtualEdited).add((String) entries.next());
            else next();
        } else if (stage == 14) {
            if (cursor < s.planTargets.size()) {
                ItemStack target = s.planTargets.get(cursor++);
                staged.planTargets.add(target == null ? null : target.copy());
            } else next();
        } else if (stage == 15) {
            if (entries == null) entries = s.manualPositions.entrySet()
                .iterator();
            if (entries.hasNext()) {
                @SuppressWarnings("unchecked")
                Map.Entry<String, HologramCapture.Cell> entry = (Map.Entry<String, HologramCapture.Cell>) entries
                    .next();
                staged.manualPositions
                    .put(entry.getKey(), staged.capture.positions.getOrDefault(entry.getKey(), entry.getValue()));
            } else next();
        } else if (stage == 16) {
            if (prepared.cells.size() != staged.capture.cells.size())
                throw new IOException("Transformed draft changed cell count");
            if (cursor < prepared.cells.size()) {
                int index = cursor++;
                HologramCapture.Cell cell = staged.capture.cells.get(index);
                HologramPresets.Cell saved = prepared.cells.get(index);
                if (cell.x != s.x + saved.x || cell.y != s.y + saved.y || cell.z != s.z + saved.z)
                    throw new IOException("Transformed draft changed stable cell order");
                if (saved.item != null
                    && (!saved.item.equals(cell.blueprintItem) || saved.damage != cell.blueprintDamage)
                    && HologramNativePlacement.supported(cell.block)
                    && !saved.excluded) HologramAuthoringWorkflow.rebindAeShell(staged, cell, index, saved.stack());
                cell.tileNbt = saved.tileNbt == null ? "" : saved.tileNbt;
                cell.itemNbt = saved.itemNbt == null ? "" : saved.itemNbt;
                cell.excluded = saved.excluded;
                cell.resourceFingerprint = "";
                cell.resourcePlanKey = null;
                staged.virtualEdited.add(HologramValues.position(cell));
            } else next();
        } else if (stage == 17) {
            // The outer admission fence is checked immediately before this single owner-thread manifest swap.
            s.capture = staged.capture;
            s.indexedCapture = staged.capture;
            s.cellIndices = staged.cellIndices;
            s.virtualPreset = prepared;
            s.pins = staged.pins;
            s.coordinatePins = staged.coordinatePins;
            s.autoTargets = staged.autoTargets;
            s.frozenAutoTargets = staged.frozenAutoTargets;
            s.facingPins = staged.facingPins;
            s.suppressedAuto = staged.suppressedAuto;
            s.planTargets = staged.planTargets;
            s.virtualEdited = staged.virtualEdited;
            s.manualPositions = staged.manualPositions;
            s.allocationsFrozen = staged.allocationsFrozen;
            s.networkBindings.clear();
            s.ae.invalidate();
            HologramBlueprintEditor.remember(s.undoEdits, history.result());
            s.redoEdits.clear();
            if (s.virtualPlacement && s.virtualLocked && s.placementReviewIssued) s.placementReviewNonce++;
            HologramDeferredPlanRefresh.request(s, Collections.emptySet());
            s.networkBindingRequested = true;
            s.status = "已生成量子设备分组；分享图请选择背包内已绑定目标网络的量子终端，再确认材料";
            next();
        }
    }

    private void next() {
        stage++;
        cursor = 0;
        entries = null;
    }

}
