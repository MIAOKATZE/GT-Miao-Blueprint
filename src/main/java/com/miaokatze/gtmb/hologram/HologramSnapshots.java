package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Server snapshot construction; transport is an explicit SnapshotSink port. */
final class HologramSnapshots {

    private HologramSnapshots() {}

    static void aeSnapshot(HologramSession s, NBTTagCompound state) {
        try {
            if (s.virtualPreset != null) state.setTag("aeIssues", HologramAuthoringQueries.aeCurrentIssues(s));
            if (s.ae.ack != null) state.setTag("aeEditAck", s.ae.ack.copy());
            if (s.ae.review != null) {
                if (s.ae.review.revision != s.planRevision) s.ae.review = null;
                else state.setTag("aeSaveReview", s.ae.review.snapshot(s.x, s.y, s.z, s.blueprintActiveStructure));
            }
            HologramAeProtocol.Preview preview = s.ae.preview;
            if (preview != null && s.capture != null) {
                HologramCapture.Cell cell = s.capture.positions.get(preview.x + "," + preview.y + "," + preview.z);
                if (cell == null || !preview.current(
                    s.id,
                    s.toolId,
                    s.blueprintActiveStructure,
                    s.planRevision,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.block,
                    cell.meta,
                    cell.tileNbt)) s.ae.preview = null;
                else {
                    HologramAeBlueprint.Analysis analysis = HologramAeBlueprint
                        .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
                    NBTTagCompound dto = preview.snapshot(analysis);
                    NBTTagList issues = dto.getTagList("issues", 10);
                    for (HologramAePolicy.Issue historical : s.virtualPreset.aeIssues) {
                        if (!(historical.x == cell.x - s.x && historical.y == cell.y - s.y
                            && historical.z == cell.z - s.z) && !"policy".equals(historical.path)) continue;
                        boolean present = false;
                        for (int issueIndex = 0; issueIndex < issues.tagCount(); issueIndex++) {
                            NBTTagCompound now = issues.getCompoundTagAt(issueIndex);
                            present |= historical.code.equals(now.getString("code"))
                                && historical.path.equals(now.getString("path"));
                        }
                        if (!present) issues.appendTag(historical.dto(s.x, s.y, s.z));
                        if (issues.tagCount() > HologramAePolicy.MAX_ISSUES)
                            throw new java.io.IOException("AE preview report budget");
                    }
                    for (int index = 0; index < analysis.materials.size(); index++) {
                        ItemStack material = analysis.materials.get(index);
                        if (HologramMaterialAccess.materialSource(s)
                            .count(material, material.stackSize) >= material.stackSize) continue;
                        issues.appendTag(
                            new HologramAePolicy.Issue(
                                "ae.mark.missing_material",
                                "warning",
                                "materials." + index,
                                HologramAePolicy.fallback("标记允许保留；施工时缺少材料：" + material.getDisplayName()),
                                cell.x,
                                cell.y,
                                cell.z).dto(0, 0, 0));
                        if (issues.tagCount() > HologramAePolicy.MAX_ISSUES)
                            throw new java.io.IOException("AE preview report budget");
                    }
                    for (int index = 0; index < issues.tagCount(); index++)
                        HologramAePolicy.projectResolution(issues.getCompoundTagAt(index), analysis, preview.structure);
                    dto.setTag("issues", issues);
                    state.setTag("aePreview", dto);
                }
            }
        } catch (java.io.IOException | RuntimeException invalid) {
            state.setString("aeReportError", "ae.report.rejected");
            state.setBoolean("aeReportBlocking", true);
            // Never publish an incomplete/truncated report as a successful review.
            state.removeTag("aeSaveReview");
            s.ae.review = null;
        }
    }

    static void cellNbtSnapshot(HologramSession s, NBTTagCompound state, HologramPresets.Preset raw)
        throws java.io.IOException {
        NBTTagList rows = new NBTTagList();
        int entryCount = 0;
        NBTTagList overrides = (s.processingPolicy == null ? HologramBlueprintProcessing.policy(raw)
            : s.processingPolicy).write()
                .getTagList("cellNbtOverrides", 10);
        Map<String, NBTTagCompound> overridePositions = new HashMap<>();
        for (int i = 0; i < overrides.tagCount(); i++) {
            NBTTagCompound row = overrides.getCompoundTagAt(i);
            overridePositions.put(row.getInteger("x") + "," + row.getInteger("y") + "," + row.getInteger("z"), row);
        }
        Map<String, HologramPresets.Cell> cells = new HashMap<>();
        for (HologramPresets.Cell cell : raw.cells) cells.put(cell.x + "," + cell.y + "," + cell.z, cell);
        for (int index = 0; s.capture != null && index < s.capture.cells.size(); index++) {
            if ((rows.tagCount() >= 8192 || entryCount >= 65536) && index != s.selected) {
                state.setBoolean("cellNbtTruncated", true);
                continue;
            }
            HologramCapture.Cell captured = s.capture.cells.get(index);
            HologramPresets.Cell cell = cells
                .get((captured.x - s.x) + "," + (captured.y - s.y) + "," + (captured.z - s.z));
            if (cell == null) continue;
            java.util.Set<String> keys = new java.util.TreeSet<>();
            for (String encoded : new String[] { cell.tileNbt, cell.itemNbt }) {
                NBTTagCompound payload = HologramBlueprintNbt.decode(encoded);
                if (payload != null) for (Object key : payload.func_150296_c()) keys.add((String) key);
            }
            if (keys.isEmpty()) continue;
            NBTTagCompound override = overridePositions.get(cell.x + "," + cell.y + "," + cell.z);
            List<String> selected = override == null || override.getBoolean("all") ? null
                : HologramAuthoringQueries.selectedNbtKeys(override.getTagList("keys", 8));
            NBTTagList entries = HologramBlueprintProcessing.nbtEntries(cell);
            entryCount += entries.tagCount();
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                String key = entry.getString("key");
                entry.setString("label", key);
                entry.setBoolean("selected", selected == null || selected.contains(key));
                entry.setBoolean("blueprintSelected", selected == null || selected.contains(key));
            }
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("index", index);
            row.setBoolean("all", selected == null);
            row.setTag("entries", entries);
            row.setTag(
                "ignoredPaths",
                override == null ? new NBTTagList()
                    : override.getTagList("ignoredPaths", 8)
                        .copy());
            rows.appendTag(row);
            if (index == s.selected) state.setTag("selectedCellNbtEntries", entries.copy());
        }
        state.setTag("cellNbtEntries", rows);
        state.setTag("cellNbtOverrides", overrides.copy());
    }

    static void writeBlueprintState(HologramSession s, NBTTagCompound state) {
        try {
            HologramPresets.Preset networkView = s.virtualPreset;
            if (s.virtualPreset != null && s.capture != null) {
                networkView = new HologramPresets.Preset();
                for (HologramCapture.Cell captured : s.capture.cells) {
                    HologramPresets.Cell descriptor = new HologramPresets.Cell();
                    descriptor.tileNbt = captured.tileNbt;
                    descriptor.excluded = captured.excluded;
                    networkView.cells.add(descriptor);
                }
            }
            state.setTag("networkBindings", HologramNetworkBindings.snapshot(networkView, s.player, s.networkBindings));
            state.setBoolean("networkBindingRequested", s.networkBindingRequested);
        } catch (java.io.IOException invalid) {
            state.setString("networkBindingError", HologramAePolicy.fallback(invalid.getMessage()));
        }
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        state.setBoolean("areaSelecting", area != null && area.selecting);
        state.setBoolean("areaImporting", area != null && area.importing);
        state.setInteger("areaImportProgress", area != null && area.importing ? area.importProgress() : 0);
        state.setString("areaImportStage", area != null && area.importing ? area.importStage() : "");
        state.setBoolean("areaImported", area != null && area.imported);
        state.setIntArray("areaPoints", area == null ? new int[0] : area.points());
        state.setTag("areaRegions", area == null ? new NBTTagList() : area.regionSnapshot());
        state.setBoolean(
            "areaUndoAvailable",
            area != null && (!area.regionUndo.isEmpty() || area.first != null || area.second != null));
        state.setString("areaToolId", area == null ? "" : s.toolId);
        state.setString("areaPointMode", area == null ? "replace" : area.pointMode);
        state.setInteger(
            "areaPendingCorner",
            area == null ? 0
                : area.first != null && area.second == null ? 1 : area.first == null && area.second != null ? 2 : 0);
        state.setBoolean(
            "blueprintDirty",
            s.blueprintDirty || s.capture != null && !s.virtualPlacement && s.blueprintSource.isEmpty());
        state.setBoolean("blueprintSaved", "saved".equals(s.blueprintSource));
        state.setBoolean("blueprintSavePending", !s.blueprintSaveReceipt.isEmpty());
        state.setString("blueprintSource", s.blueprintSource);
        state.setBoolean("blueprintProcessingComplete", s.processingReviewed);
        state.setBoolean("blueprintProcessingRequired", !s.processingReviewed);
        state.setBoolean(
            "processingResumeAllowed",
            s.processingWorkflowStarted && s.processingPolicy != null && !s.processingPolicy.confirmedStages.isEmpty());
        state.setString("processingPresetOperation", s.processingPresetOperation);
        state.setBoolean("processingPresetOperationSuccess", s.processingPresetOperationSuccess);
        state.setBoolean(
            "quantumGroupsOperationSuccess",
            "configureBlueprintQuantumGroups".equals(s.processingPresetOperation)
                && s.processingPresetOperationSuccess);
        state.setString("selectedProcessingPresetId", s.selectedProcessingPresetId);
        state.setBoolean("discardConfirmationRequired", s.discardConfirmationRequired);
        state.setString("discardTargetOp", s.discardTargetOp);
        state.setString("discardTargetId", s.discardTargetId);
        state.setTag("discardTargetAction", s.pendingSourceAction.copy());
        try {
            HologramPresets.Preset source = HologramAuthoringQueries.processingSource(s);
            NBTTagCompound processing = HologramBlueprintProcessing
                .state(source, s.processingPolicy, s.dimension, s.x, s.y, s.z);
            boolean directSave = processing.getInteger("requiredStages") == 0;
            state.setBoolean("blueprintDirectSave", directSave);
            state.setBoolean("blueprintProcessingRequired", !s.processingReviewed && !directSave);
            processing
                .setBoolean("complete", s.processingReviewed && HologramBlueprintProcessing.complete(s.virtualPreset));
            state.setTag("processing", processing);
            state.setTag(
                "processingPolicy",
                processing.getCompoundTag("policy")
                    .copy());
            state.setTag(
                "processingRelations",
                processing.getTagList("relations", 10)
                    .copy());
            state.setTag(
                "processingNbtKeys",
                processing.getTagList("nbtKeys", 8)
                    .copy());
            state.setTag(
                "processingNbtPaths",
                processing.getTagList("nbtPaths", 8)
                    .copy());
            cellNbtSnapshot(s, state, source);
        } catch (java.io.IOException failure) {
            state.setString("processingError", failure.getMessage());
        }
        try {
            state.setTag("processingPresets", HologramProcessingPresets.labels());
        } catch (java.io.IOException failure) {
            state.setString("processingPresetError", failure.getMessage());
        }
        int deidentificationLevel = HologramDeidentification.level(s.virtualPreset);
        state.setInteger("blueprintDeidentificationLevel", deidentificationLevel);
        state.setInteger(
            "placementDeidentificationLevel",
            Math.max(deidentificationLevel, s.placementDeidentificationLevel));
        state.setInteger("deidentificationMinimumLevel", deidentificationLevel);
        state.setInteger("deidentificationRecommendedLevel", 1);
        state.setBoolean("deidentificationChosen", s.deidentificationChosen);
        state.setBoolean("placementDeidentificationChosen", s.placementDeidentificationChosen);
        state.setBoolean(
            "placementDeidentificationRecommended",
            deidentificationLevel == 0 && !s.placementDeidentificationChosen);
        NBTTagList structureLabels = area == null ? new NBTTagList() : area.labels();
        if (s.virtualPreset != null && !s.virtualPreset.structures.isEmpty()) {
            structureLabels = new NBTTagList();
            for (HologramPresets.Structure saved : s.virtualPreset.structures) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("id", saved.id);
                row.setString("name", saved.name == null ? "结构" : saved.name);
                row.setInteger("x", s.x + saved.x);
                row.setInteger("y", s.y + saved.y);
                row.setInteger("z", s.z + saved.z);
                row.setBoolean("enabled", saved.enabled);
                String warning = HologramBlueprintData.blueprintSourceWarning(s.virtualPreset, saved.id);
                row.setBoolean("sourceMissing", !warning.isEmpty());
                row.setString("sourceWarning", warning);
                row.setString("sourceClass", saved.sourceClass == null ? "" : saved.sourceClass);
                row.setString("sourceMod", saved.sourceMod == null ? "" : saved.sourceMod);
                row.setBoolean("declared", warning.isEmpty());
                TileEntity tile = s.player.worldObj.getTileEntity(s.x + saved.x, s.y + saved.y, s.z + saved.z);
                row.setBoolean(
                    "formed",
                    tile instanceof IGregTechTileEntity
                        && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase
                        && ((MTEEnhancedMultiBlockBase<?>) ((IGregTechTileEntity) tile).getMetaTileEntity()).mMachine);
                structureLabels.appendTag(row);
            }
        }
        String sourceWarning = s.virtualPreset == null ? ""
            : HologramBlueprintData.blueprintSourceWarning(s.virtualPreset, "");
        if (s.virtualPreset != null && sourceWarning.isEmpty())
            for (HologramPresets.Structure source : s.virtualPreset.structures) {
                sourceWarning = HologramBlueprintData.blueprintSourceWarning(s.virtualPreset, source.id);
                if (!sourceWarning.isEmpty()) break;
            }
        state.setBoolean("sourceMissing", !sourceWarning.isEmpty());
        state.setString("sourceWarning", sourceWarning);
        state.setTag("structures", structureLabels);
        state.setString("activeStructure", s.virtual ? s.blueprintActiveStructure : area == null ? "" : area.active);
        List<String> savedKeys = s.virtualPreset == null ? s.retainedNbtKeys : s.virtualPreset.retainedNbtKeys;
        state.setBoolean("blueprintNbtAll", savedKeys == null);
        state.setTag("blueprintNbtKeys", HologramSessionPolicy.nbtKeys(savedKeys));
        state.setBoolean("placementNbtCustomized", s.placementNbtCustomized);
        state.setBoolean("placementRetainCovers", HologramSessionPolicy.placementRetainCovers(s));
        state.setBoolean("placementNbtAll", HologramSessionPolicy.placementKeys(s) == null);
        state.setTag("placementNbtKeys", HologramSessionPolicy.nbtKeys(HologramSessionPolicy.placementKeys(s)));
        if (s.nbtKeysCapture != s.capture) {
            s.nbtKeysCapture = s.capture;
            s.availableNbtKeys.clear();
            if (s.capture != null) for (HologramCapture.Cell cell : s.capture.cells) {
                try {
                    NBTTagCompound data = s.virtual ? HologramBlueprintNbt.decode(cell.tileNbt) : null;
                    if (!s.virtual) {
                        TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
                        if (tile != null) {
                            data = HologramNativeCapture.capture(tile);
                        }
                    }
                    if (data != null) for (Object key : data.func_150296_c()) s.availableNbtKeys.add((String) key);
                    if (s.virtual) {
                        NBTTagCompound item = HologramBlueprintNbt.decode(cell.itemNbt);
                        if (item != null) for (Object key : item.func_150296_c()) s.availableNbtKeys.add((String) key);
                    }
                } catch (java.io.IOException | RuntimeException ignored) { /* Invalid payload stays unsupported. */ }
            }
        }
        NBTTagList entries = new NBTTagList();
        for (String key : s.availableNbtKeys) {
            NBTTagCompound row = new NBTTagCompound();
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            row.setString("key", key);
            row.setString("label", key);
            row.setString(
                "category",
                lower.contains("fluid") || lower.contains("tank") ? "fluid"
                    : lower.contains("energy") || lower.equals("meu") ? "energy"
                        : lower.contains("inventory") || lower.equals("items") ? "items" : "configuration");
            row.setBoolean("selected", savedKeys == null || savedKeys.contains(key));
            row.setBoolean("blueprintSelected", savedKeys == null || savedKeys.contains(key));
            row.setBoolean(
                "placementSelected",
                HologramSessionPolicy.placementKeys(s) == null || HologramSessionPolicy.placementKeys(s)
                    .contains(key));
            entries.appendTag(row);
        }
        state.setTag("nbtEntries", entries);
        state.setBoolean(
            "materialOrderingAvailable",
            s.virtual && s.virtualPreset != null && HologramMaterialOrdering.available(s.player));
        state.setTag("blueprintResources", HologramResourcePlanner.blueprintResourceRows(s));
        state.setString("blueprintEnergyNotice", s.blueprintEnergyNotice);
        state.setString("blueprintResourceMissing", s.blueprintResourceMissing);
        state.setBoolean("blueprintPreview", s.blueprintPreview);
        state.setBoolean(
            "decorativeMaterialsNotice",
            s.virtualPreset != null && s.capture != null
                && s.capture.cells.stream()
                    .anyMatch(cell -> HologramCellPolicy.decorationTarget(s, cell)));
        state.setString("decorativeMaterialsNoticeKey", "gtmb.hologram.placement.decorative_materials");
        state.setBoolean("blueprintAwaitingConfirmation", s.blueprintAwaitingConfirmation);
        state.setBoolean("forcePlacementReady", s.forcePlacementReady);
        state.setBoolean("blueprintRetainNbt", s.virtualPreset == null || s.virtualPreset.retainNbt);
        state.setBoolean("blueprintRetainCovers", s.virtualPreset == null || s.virtualPreset.retainCovers);
        state.setString("blueprintOperation", s.blueprintOperation);
        state.setBoolean("blueprintOperationSuccess", s.blueprintOperationSuccess);
        state.setString(
            "blueprintSavedId",
            s.virtualPreset == null ? s.blueprintSavedId : s.virtualPreset.id == null ? "" : s.virtualPreset.id);
        state.setString(
            "blueprintPrompt",
            s.virtualPreset == null ? s.blueprintPrompt : s.virtualPreset.prompt == null ? "" : s.virtualPreset.prompt);
        NBTTagList versions = new NBTTagList();
        Map<String, String> dependencies = s.virtualPreset == null ? new LinkedHashMap<>()
            : s.virtualPreset.modVersions;
        if (s.machine != null) {
            String modId = HologramSessionPolicy.sourceMod(s.machine.getClass());
            state.setString("sourceMod", modId);
            state.setString(
                "sourceClass",
                s.machine.getClass()
                    .getName());
            for (cpw.mods.fml.common.ModContainer installed : cpw.mods.fml.common.Loader.instance()
                .getModList())
                if (installed.getModId()
                    .equals(modId)) dependencies.put(modId, installed.getVersion());
        }
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            NBTTagCompound row = new NBTTagCompound();
            String current = "minecraft".equals(entry.getKey()) ? "1.7.10" : null;
            for (cpw.mods.fml.common.ModContainer mod : cpw.mods.fml.common.Loader.instance()
                .getModList())
                if (entry.getKey()
                    .equals(mod.getModId())) current = mod.getVersion();
            int comparison = current == null ? 0 : HologramBlueprintMetadata.compareVersions(current, entry.getValue());
            row.setString("modId", entry.getKey());
            row.setString("saved", entry.getValue());
            row.setString("current", current == null ? "" : current);
            row.setString(
                "state",
                current == null ? "missing" : comparison < 0 ? "outdated" : comparison > 0 ? "ahead" : "current");
            versions.appendTag(row);
        }
        state.setTag("modVersions", versions);
        NBTTagList hints = new NBTTagList();
        for (HologramPresets.HintArea hint : s.virtualPreset == null ? s.blueprintHintAreas
            : s.virtualPreset.hintAreas) {
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("bounds", new int[] { hint.x1, hint.y1, hint.z1, hint.x2, hint.y2, hint.z2 });
            row.setString("prompt", hint.prompt == null ? "" : hint.prompt);
            hints.appendTag(row);
        }
        state.setTag("hintAreas", hints);
        state.setIntArray(
            "selectedIndices",
            s.selectedIndices.stream()
                .mapToInt(Integer::intValue)
                .toArray());
    }

    static void sendJob(HologramSession s) {
        if (s.job == 1 && !s.open && !s.cancelled) {
            if (s.jobPublication.indices.size() < 8192
                && !s.jobPublication.due(s.player.worldObj.getTotalWorldTime(), s.planRevision, s.uiSequence)) {
                HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.SNAPSHOT_DEFERRED, 1);
                return;
            }
            NBTTagCompound state = new NBTTagCompound();
            state.setBoolean("progressOnly", true);
            state.setTag("progress", jobDelta(s));
            HologramWorkspace.snapshotSink()
                .send(s.player, state);
            s.jobPublication.published(s.player.worldObj.getTotalWorldTime(), s.planRevision, s.uiSequence);
            s.progressTick = s.player.worldObj.getTotalWorldTime();
            s.progressPhase = "job";
            return;
        }
        send(s);
    }

    /**
     * Per-result visual facts only. No cells, native payloads, quotes, editable controls or authorization are emitted.
     */
    static NBTTagCompound jobDelta(HologramSession s) {
        NBTTagCompound delta = progress(s);
        delta.setBoolean("jobDelta", true);
        delta.setLong("batchSeq", s.batchSeq);
        delta.setLong("placementReviewNonce", s.placementReviewNonce);
        delta.setInteger("job", s.job);
        delta.setInteger("cursor", s.cursor);
        delta.setInteger("completed", s.completed);
        delta.setString("status", s.status);
        delta.setInteger("jobMissingCells", s.jobMissingCells);
        delta.setInteger("jobProtectedCells", s.jobProtectedCells);
        delta.setInteger("jobUnsupportedCells", s.jobUnsupportedCells);
        delta.setInteger("jobSkippedCells", s.jobSkippedCells);
        delta.setIntArray(
            "ackIndices",
            s.jobPublication.indices.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        delta.setIntArray(
            "ackSuccesses",
            s.jobPublication.successes.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        NBTTagList statuses = new NBTTagList();
        for (String status : s.jobPublication.statuses) statuses.appendTag(new NBTTagString(status));
        delta.setTag("ackStatuses", statuses);
        return delta;
    }

    /** Only detached cursors and counters, never cell payloads or an editable state/authority replacement. */
    static NBTTagCompound progress(HologramSession s) {
        NBTTagCompound progress = new NBTTagCompound();
        progress.setString("scope", "server");
        progress.setString("session", s.id);
        progress.setLong("planRevision", s.planRevision);
        progress.setLong("uiSequence", s.uiSequence);
        progress.setString("outcome", "running");
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null && area.importing) {
            progress.setString("phase", "area." + area.importStage());
            boolean blocks = area.y <= area.maxY;
            long width = (long) area.maxX - area.minX + 1, depth = (long) area.maxZ - area.minZ + 1;
            progress
                .setLong("total", blocks ? width * depth * ((long) area.maxY - area.minY + 1) : area.discovery.size());
            progress.setLong(
                "done",
                blocks ? (((long) area.y - area.minY) * depth + area.z - area.minZ) * width + area.x - area.minX
                    : area.discoveryIndex);
            progress.setString("unit", blocks ? "cells" : "structures");
        } else if (s.capturePending) {
            progress.setString("phase", "capture");
            progress.setLong("done", s.capture == null ? 0 : s.capture.cells.size());
            progress.setLong("total", 0); // Native definitions need not expose a total before traversal finishes.
            progress.setString("unit", "cells");
        } else {
            progress.setString("phase", s.job == 1 || s.job >= 3 ? "job" : "ready");
            progress.setLong("done", s.cursor);
            progress.setLong("total", s.work.size());
            progress.setString("unit", "cells");
            progress.setBoolean("complete", s.job != 1 && s.job != 2);
            progress.setString(
                "outcome",
                s.job == 3 ? "completed"
                    : s.job == 5 ? "partial" : s.job == 4 ? "stopped" : s.job == 1 || s.job == 2 ? "running" : "ready");
        }
        return progress;
    }

    static void sendProgress(HologramSession s) {
        long tick = s.player.worldObj.getTotalWorldTime();
        NBTTagCompound progress = progress(s);
        String phase = progress.getString("phase");
        if (s.progressTick != Long.MIN_VALUE && tick >= s.progressTick
            && tick - s.progressTick < 5
            && phase.equals(s.progressPhase)) return;
        s.progressTick = tick;
        s.progressPhase = phase;
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("progressOnly", true);
        state.setTag("progress", progress);
        HologramWorkspace.snapshotSink()
            .send(s.player, state);
    }

    static void send(HologramSession s) {
        // Partial batches are server-only. Keep the original open intent until a complete snapshot exists.
        if (s.capturePending) {
            sendProgress(s);
            return;
        }
        long measured = HologramRuntimeMetrics.start();
        try {
            sendComplete(s);
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.SNAPSHOT_BUILD, measured);
        }
    }

    private static void sendComplete(HologramSession s) {
        if (s.open && !s.cancelled && s.libraryWorld == s.player.worldObj && HologramSessionPolicy.valid(s))
            s.libraryUiActive = true;
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("open", s.open);
        state.setString("openPurpose", s.openPurpose);
        if ("controllerStructure".equals(s.openPurpose)) s.workspaceTab = "structure";
        else if ("blueprintEdit".equals(s.openPurpose) || "blueprintBrowse".equals(s.openPurpose)
            || "blueprintPlacement".equals(s.openPurpose)
            || "placementReview".equals(s.openPurpose)) s.workspaceTab = "blueprint";
        state.setString("workspaceTab", s.workspaceTab);
        state.setLong("placementReviewNonce", s.placementReviewNonce);
        s.openPurpose = "";
        state.setInteger("hatchTier", s.hatchTier);
        state.setInteger("hatchTierMax", gregtech.api.enums.GTValues.VN.length - 1);
        state.setBoolean("hatchDowngrade", s.hatchDowngrade);
        state.setBoolean("presetSupported", s.presetSupported);
        state.setBoolean("presetAvailable", s.presetAvailable);
        state.setBoolean("presetActive", s.presetEnabled || !s.autoTargets.isEmpty());
        boolean editable = s.job != 1 && s.job != 2 && !s.capturePending && s.capture != null && !s.capture.incomplete;
        state.setBoolean("editCanUndo", editable && !s.undoEdits.isEmpty());
        state.setBoolean("editCanRedo", editable && !s.redoEdits.isEmpty());
        state.setBoolean("editCanPaste", editable && (s.editClipboard != null || !s.blueprintClipboard.isEmpty()));
        state.setString("presetReason", s.presetReason);
        state.setBoolean("creativeMaterials", s.player.capabilities.isCreativeMode);
        s.open = false;
        state.setString("session", s.id);
        state.setString("generation", s.id);
        state.setBoolean("capturePending", s.capturePending);
        state.setInteger("captureAttempts", s.captureAttempts);
        state.setLong("captureRetryDue", s.captureRetryDue);
        state.setLong("planRevision", s.planRevision);
        state.setTag("progress", progress(s));
        if (!s.jobPublication.indices.isEmpty()) {
            s.ackIndices.clear();
            s.ackIndices.addAll(s.jobPublication.indices);
            s.ackSuccesses.clear();
            s.ackSuccesses.addAll(s.jobPublication.successes);
        }
        aeSnapshot(s, state);
        state.setLong("uiSequence", s.uiSequence);
        state.setString("phase", s.phase);
        state.setIntArray("pending", new int[0]);
        state.setIntArray(
            "ackIndices",
            s.ackIndices.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        state.setIntArray(
            "ackSuccesses",
            s.ackSuccesses.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        state.setInteger("layerOrdinal", 0);
        state.setInteger("layerCount", 0);
        state.setInteger("layerCompleted", s.completed);
        state.setInteger("layerTotal", s.work.size());
        state.setInteger("layerY", s.y);
        state.setInteger("layerElapsed", 0);
        state.setInteger("layerLeadRemaining", 0);
        state.setInteger("layerDuration", 0);
        state.setInteger("layerGap", 0);
        state.setLong("due", 0);
        state.setLong("serverTick", s.player.worldObj.getTotalWorldTime());
        state.setLong("batchSeq", s.batchSeq);
        state.setIntArray("ack", s.ackIndex < 0 ? new int[0] : new int[] { s.ackIndex });
        state.setBoolean("ackSuccess", s.ackSuccess);
        state.setBoolean("recoveryFits", s.planRecoveryFits);
        state.setString(
            "recoveryDestinationLabel",
            s.materialSettings.recoveryDestination == 1 ? "优先 ME；余量回背包或持久暂存" : "主背包；满时持久暂存");
        state.setBoolean("formationChecked", false);
        state.setBoolean("structureBuilt", HologramPlanPolicy.structureBuilt(s));
        state.setTag("capabilities", s.capabilities == null ? new NBTTagList() : s.capabilities.toNBT());
        state.setBoolean("target", s.target != null || s.virtual && s.capture != null);
        state.setBoolean("virtual", s.virtual);
        state.setBoolean("virtualPlacement", s.virtualPlacement);
        state.setBoolean("wholeBlueprintPlacement", HologramSessionPolicy.wholeBlueprintPlacement(s));
        state.setBoolean("presetBrowseForPlacement", s.presetBrowseForPlacement);
        state.setBoolean("virtualLocked", s.virtualLocked);
        state.setString(
            "virtualPresetId",
            s.virtualPreset == null || s.virtualPreset.id == null ? "" : s.virtualPreset.id);
        state.setString("virtualPresetName", s.virtualPreset == null ? "" : s.virtualPreset.name);
        state.setBoolean("moving", HologramSessionPolicy.virtualMoving(s));
        state.setBoolean("settingsOnly", s.settingsOnly);
        state.setBoolean("cancelled", s.cancelled);
        state.setString("selectedPresetId", HologramSessionPolicy.selectedPreset(s));
        ItemStack heldDraft = HologramSessionPolicy.currentTool(s);
        state.setBoolean(
            "draftAvailable",
            heldDraft != null && HologramDraftPersistence.draftMatches(
                s,
                heldDraft.getTagCompound()
                    .getCompoundTag(HologramSessionKeys.DRAFT)));
        state.setBoolean("serverLibraryEnabled", HologramLibraryFacade.enabled());
        state.setBoolean("libraryAdmin", HologramLibraryFacade.isAdmin(s.player));
        state.setBoolean("adminOP4", HologramLibraryFacade.isAdmin(s.player));
        state.setBoolean("libraryInitialSnapshot", s.libraryInitialSnapshot);
        s.libraryInitialSnapshot = false;
        state.setString("libraryLocalId", s.libraryLocalId);
        try {
            state.setTag("presets", HologramLibraryFacade.enabled() ? new NBTTagList() : HologramPresets.labels());
        } catch (java.io.IOException invalid) {
            state.setTag("presets", new NBTTagList());
            state.setString("presetConfigError", invalid.getMessage());
        }
        state.setBoolean("targetClosed", false);
        state.setInteger("x", s.x);
        state.setInteger("y", s.y);
        state.setInteger("z", s.z);
        state.setInteger("dimension", s.dimension);
        state.setInteger("side", s.side);
        state.setString("title", s.machine == null ? "全息构造器" : s.machine.getLocalName());
        state.setInteger("main", s.main);
        state.setTag("channels", s.channels.copy());
        state.setBoolean("noHatches", s.noHatches);
        state.setBoolean("keepController", !s.virtual);
        NBTTagList materialChoices = new NBTTagList();
        for (ItemStack sample : s.materialChoices) materialChoices.appendTag(sample.writeToNBT(new NBTTagCompound()));
        state.setTag("materialChoices", materialChoices);
        state.setBoolean("materialMain", s.materialSettings.main);
        state.setBoolean("materialContainers", s.materialSettings.containers);
        state.setBoolean("materialMe", s.materialSettings.me);
        state.setInteger("materialPriority", s.materialSettings.priority);
        state.setInteger("recoveryDestination", s.materialSettings.recoveryDestination);
        state.setInteger("facing", s.facing);
        state.setInteger("mode", s.mode);
        state.setInteger("scope", s.scope);
        state.setInteger("layer", s.layer);
        state.setInteger("selected", s.selected);
        state.setString("status", s.status);
        state.setInteger("job", s.job);
        state.setInteger("completed", s.completed);
        state.setBoolean("jobPartialPlacement", s.jobPartialPlacement);
        state.setInteger("jobMissingCells", s.jobMissingCells);
        state.setInteger("jobProtectedCells", s.jobProtectedCells);
        state.setInteger("jobUnsupportedCells", s.jobUnsupportedCells);
        state.setInteger("jobSkippedCells", s.jobSkippedCells);
        state.setBoolean("hints", s.hints);
        s.hints = false;
        state.setIntArray("facings", s.machine == null ? new int[0] : new int[] { s.facing });
        state.setBoolean("supported", s.capture != null && !s.capture.incomplete);
        if (s.capture != null && !s.capture.extensionReports.hasNoTags())
            state.setTag("extensionReports", s.capture.extensionReports.copy());
        state.setBoolean(
            "geometryEditable",
            s.virtual && s.virtualPreset != null
                && s.virtualPreset.schema >= 2
                && s.capture != null
                && !s.capture.incomplete
                && !s.capture.pending
                && !s.capturePending
                && s.player.capabilities.allowEdit
                && s.job != 1
                && s.job != 2);
        state.setInteger("virtualSchema", s.virtualPreset == null ? 0 : s.virtualPreset.schema);
        state.setString(
            "unsupportedReason",
            s.capture == null || s.capture.incomplete
                ? s.status + (s.capture == null || s.capture.failure.isEmpty() ? "" : " (" + s.capture.failure + ")")
                : "");
        writeBlueprintState(s, state);
        NBTTagList cells = new NBTTagList(), materials = new NBTTagList(), recovery = new NBTTagList();
        int protectedCount = 0, missing = 0, total = 0, workTotal = 0;
        int recoveryRows = 0, recoveryItems = 0;
        Map<String, NBTTagCompound> requirements = new LinkedHashMap<>();
        if (s.capture != null) for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell c = s.capture.cells.get(index);
            World world = s.player.worldObj;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", c.x);
            row.setInteger("y", c.y);
            row.setInteger("z", c.z);
            row.setInteger("dx", c.x - s.x);
            row.setInteger("dy", c.y - s.y);
            row.setInteger("dz", c.z - s.z);
            row.setBoolean("sourceMissing", !c.sourceWarning.isEmpty());
            row.setString("sourceClass", c.sourceClass);
            row.setString("sourceWarning", c.sourceWarning);
            row.setBoolean("missingBlock", c.unknown && HologramCellPolicy.targetFor(s, c, index) == null);
            String sourceRegistry = c.blueprintBlock.isEmpty() ? c.blueprintItem : c.blueprintBlock;
            String sourceMod = sourceRegistry.contains(":") ? sourceRegistry.substring(0, sourceRegistry.indexOf(':'))
                : "";
            row.setString("sourceMod", sourceMod);
            row.setString(
                "sourceVersion",
                s.virtualPreset == null ? "" : s.virtualPreset.modVersions.getOrDefault(sourceMod, ""));
            row.setBoolean("excluded", c.excluded);
            row.setString("prompt", c.prompt);
            try {
                NBTTagCompound network = HologramBlueprintNbt.decode(c.tileNbt);
                if (network != null && network.hasKey(HologramNetworkBindings.GROUP)) {
                    String group = HologramNetworkBindings.group(network);
                    row.setString("networkGroup", group);
                    NBTTagCompound hint = new NBTTagCompound();
                    hint.setString("type", "quantumNetwork");
                    hint.setString("group", group);
                    hint.setString("hint", HologramNetworkBindings.hint(network));
                    row.setTag("specialHint", hint);
                }
            } catch (java.io.IOException invalidGroup) {
                row.setString("networkGroupError", HologramAePolicy.fallback(invalidGroup.getMessage()));
            }
            row.setString("structureId", c.structureId);
            row.setTag("structureIds", HologramSessionPolicy.nbtKeys(c.memberships()));
            row.setBoolean("anchor", c.anchor);
            row.setBoolean("hasPin", s.pins.containsKey(index));
            row.setBoolean("templateController", "templateController".equals(c.role));
            row.setBoolean("hasTargetOverride", s.pins.containsKey(index) || s.autoTargets.containsKey(index));
            row.setBoolean("hasFacingPin", s.facingPins.containsKey(HologramValues.position(c)));
            row.setInteger("hatchFacing", s.planFacings.getOrDefault(index, -1));
            row.setInteger("targetFacing", c.anchor ? c.actualFacing : s.planFacings.getOrDefault(index, -1));
            TileEntity actualTile = world.getTileEntity(c.x, c.y, c.z);
            try {
                c.actualDisplay = HologramDisplaySnapshot.capture(actualTile);
            } catch (RuntimeException | LinkageError ignored) {
                c.actualDisplay = new NBTTagCompound();
            }
            row.setTag("actualDisplay", c.actualDisplay.copy());
            row.setInteger(
                "actualFacing",
                actualTile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) actualTile).getFrontFacing()
                    .ordinal() : -1);
            row.setIntArray(
                "hatchFacings",
                HologramCellPolicy.legalFacings(s, c, HologramCellPolicy.targetFor(s, c, index)));
            row.setBoolean("userPosition", c.userPosition);
            row.setBoolean("iconOnly", c.iconOnly);
            row.setString("role", c.role);
            row.setString("family", c.family);
            row.setString("renderKind", c.renderKind);
            if (c.actualStack != null) row.setTag("actualStack", c.actualStack.writeToNBT(new NBTTagCompound()));
            NBTTagList icons = new NBTTagList();
            if (c.hintIcons != null)
                for (String icon : c.hintIcons) icons.appendTag(new NBTTagString(icon == null ? "" : icon));
            row.setTag("hintIcons", icons);
            if (c.hintTint != null) {
                int[] tint = new int[c.hintTint.length];
                for (int i = 0; i < tint.length; i++) tint[i] = c.hintTint[i];
                row.setIntArray("hintTint", tint);
            }
            row.setInteger("hintIndex", c.hintIndex);
            row.setBoolean("inScope", HologramSessionPolicy.inScope(s, c, index));
            row.setBoolean(
                "explicitTarget",
                HologramCellPolicy.explicitTarget(s, c, index) || HologramCellPolicy.explicitGrade(s, c));
            row.setBoolean(
                "autoTarget",
                !s.noHatches && s.channels.hasKey("gt_hatch", 3)
                    && "hatch".equals(c.role)
                    && !HologramCellPolicy.explicitTarget(s, c, index) || s.autoTargets.containsKey(index));
            boolean loaded = world.blockExists(c.x, c.y, c.z);
            Block current = loaded ? world.getBlock(c.x, c.y, c.z) : Blocks.air;
            int meta = loaded ? world.getBlockMetadata(c.x, c.y, c.z) : 0;
            row.setString("id", String.valueOf(Block.blockRegistry.getNameForObject(current)));
            row.setInteger("meta", meta);
            row.setString(
                "wantId",
                c.block == null ? c.blueprintBlock : String.valueOf(Block.blockRegistry.getNameForObject(c.block)));
            row.setInteger("wantMeta", c.meta);
            ItemStack pinned = HologramCellPolicy.targetFor(s, c, index);
            row.setString(
                "exactIdentity",
                pinned == null ? HologramBlockIdentity.key(c.block, c.meta) : HologramBlockIdentity.key(pinned));
            // Resolve the exact same wanted block used by this DTO before selecting its detached native display.
            int displayFacing = c.anchor ? c.actualFacing : s.planFacings.getOrDefault(index, -1);
            String previewTile = "", previewError = "";
            try {
                if (pinned != null) {
                    Block pinnedBlock = HologramCellPolicy.targetPreviewBlock(s, c, pinned);
                    if (pinnedBlock != Blocks.air) {
                        row.setString("wantId", String.valueOf(Block.blockRegistry.getNameForObject(pinnedBlock)));
                        row.setInteger("wantMeta", HologramCellPolicy.targetPreviewMeta(s, c, pinned));
                    }
                }
                previewTile = HologramCellPolicy.targetPreviewTile(s, c);
            } catch (java.io.IOException invalid) {
                previewError = "蓝图目标预览NBT无效：" + HologramAePolicy.fallback(invalid.getMessage());
                row.setBoolean("previewInvalid", true);
                row.setString("previewError", previewError);
            }
            Block wantedBlock = Block.getBlockFromName(row.getString("wantId"));
            c.targetDisplay = HologramDisplaySnapshot.target(
                wantedBlock,
                pinned,
                displayFacing,
                c.element,
                previewTile,
                !s.virtual || HologramSessionPolicy.placementRetainCovers(s));
            if (!s.virtual && pinned != null
                && actualTile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) actualTile).getMetaTileID() == pinned.getItemDamage()) {
                c.targetDisplay = (NBTTagCompound) c.actualDisplay.copy();
                if (displayFacing >= 0) c.targetDisplay.setInteger("facing", displayFacing);
                HologramDisplaySnapshot.applyCasing(c.element, c.targetDisplay);
            }
            row.setTag("targetDisplay", c.targetDisplay.copy());
            if (pinned != null) row.setTag("targetStack", pinned.writeToNBT(new NBTTagCompound()));
            if (pinned != null && pinned.getItem() instanceof ItemMachines
                && loaded
                && HologramCellPolicy.trivial(world, c.x, c.y, c.z)) row.setString("renderKind", "item");
            if (s.mode == 2 && loaded && (HologramCellPolicy.removable(s, c) || "removed".equals(c.status))) {
                row.setString("wantId", "minecraft:air");
                row.setInteger("wantMeta", 0);
            }
            row.setString("status", c.status);
            row.setString("reason", previewError.isEmpty() ? HologramPlanPolicy.reason(s, c, index) : previewError);
            row.setString(
                "operation",
                !HologramSessionPolicy.inScope(s, c, index) ? "OUTSIDE"
                    : "satisfied".equals(c.status) ? "KEEP"
                        : "protected".equals(c.status) || "unsupported".equals(c.status) || "blocked".equals(c.status)
                            ? "PROTECT"
                            : s.mode == 2 ? "REMOVE"
                                : loaded && HologramCellPolicy.trivial(world, c.x, c.y, c.z) ? "PLACE" : "REPLACE");
            if (HologramSessionPolicy.inScope(s, c, index)
                && ("protected".equals(c.status) || "unsupported".equals(c.status) || "blocked".equals(c.status)))
                protectedCount++;
            if (HologramSessionPolicy.inScope(s, c, index)) total++;
            if (HologramSessionPolicy.inScope(s, c, index) && index < s.planStatus.size()
                && "pending".equals(s.planStatus.get(index))) workTotal++;
            NBTTagList choices = new NBTTagList();
            List<ItemStack> options = index == s.selected ? HologramCellPolicy.candidates(s, c)
                : Collections.emptyList();
            int chosenChoice = -1;
            for (int choice = 0; choice < options.size(); choice++)
                if (HologramValues.same(pinned, options.get(choice))) {
                    chosenChoice = choice;
                    break;
                }
            row.setInteger("chosenChoice", chosenChoice);
            row.setString("pinScope", "target");
            for (ItemStack option : options) {
                NBTTagCompound tag = new NBTTagCompound();
                option.writeToNBT(tag);
                choices.appendTag(tag);
            }
            row.setTag("candidates", choices);
            row.setBoolean("candidateComplete", c.candidateComplete);
            row.setString("candidateBoundary", c.candidateBoundary);
            NBTTagList candidateRoles = new NBTTagList();
            int[] candidateTiers = new int[options.size()], candidateAvailable = new int[options.size()];
            for (int n = 0; n < options.size(); n++) {
                ItemStack option = options.get(n);
                candidateRoles.appendTag(new NBTTagString(HologramCellPolicy.candidateRole(s, c, option)));
                candidateTiers[n] = HologramCellPolicy.hatchTier(option) == Integer.MAX_VALUE ? -1
                    : HologramCellPolicy.hatchTier(option);
                candidateAvailable[n] = HologramMaterialAccess
                    .shellCount(s, option, Math.max(4096, s.capture.cells.size()));
            }
            row.setTag("candidateRoles", candidateRoles);
            row.setIntArray("candidateTiers", candidateTiers);
            row.setIntArray("candidateAvailable", candidateAvailable);
            cells.appendTag(row);
            if (!"satisfied".equals(c.status) && !"protected".equals(c.status)
                && !"unsupported".equals(c.status)
                && !"placed".equals(c.status)
                && !"replaced".equals(c.status)
                && pinned != null
                && !HologramCellPolicy.orientationOnly(s, c, index)
                && HologramSessionPolicy.inScope(s, c, index)
                && s.mode != 2
                && !HologramCellPolicy.decorationTarget(s, c)
                && !HologramMaterialAccess.freeShell(s, c, pinned)
                && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(pinned))) {
                ItemStack stack = HologramEssentia.shellMaterial(pinned);
                stack.stackSize = 1;
                String key = HologramValues.stackKey(stack);
                NBTTagCompound req = requirements.get(key);
                if (req == null) {
                    req = new NBTTagCompound();
                    stack.writeToNBT(req);
                    req.setInteger(
                        "available",
                        HologramMaterialAccess.shellCount(s, stack, Math.max(4096, s.capture.cells.size())));
                    req.setInteger(
                        "mainAvailable",
                        HologramMaterialAccess.materialSource(s)
                            .mainCount(stack, Math.max(4096, s.capture.cells.size())));
                    req.setInteger(
                        "controllerAvailable",
                        s.target instanceof net.minecraft.inventory.IInventory
                            && ((net.minecraft.inventory.IInventory) s.target).isUseableByPlayer(s.player)
                                ? HologramMaterials.controllerCount(
                                    (net.minecraft.inventory.IInventory) s.target,
                                    stack,
                                    Math.max(4096, s.capture.cells.size()))
                                : -1);
                    requirements.put(key, req);
                }
                req.setInteger("required", req.getInteger("required") + 1);
            }
            if (HologramSessionPolicy.inScope(s, c, index) && "pending".equals(c.status)
                && s.mode != 2
                && !s.player.capabilities.isCreativeMode) for (ItemStack cover : HologramCellPolicy.coverCosts(s, c)) {
                    String key = HologramValues.stackKey(cover);
                    NBTTagCompound requirement = requirements.get(key);
                    if (requirement == null) {
                        requirement = cover.writeToNBT(new NBTTagCompound());
                        requirement.setInteger(
                            "available",
                            HologramMaterialAccess.materialSource(s)
                                .count(cover, Math.max(4096, s.capture.cells.size() * 6)));
                        requirement.setInteger(
                            "mainAvailable",
                            HologramMaterialAccess.materialSource(s)
                                .mainCount(cover, Math.max(4096, s.capture.cells.size() * 6)));
                        requirement.setInteger("controllerAvailable", -1);
                        requirements.put(key, requirement);
                    }
                    requirement.setInteger("required", requirement.getInteger("required") + 1);
                }
            if (HologramSessionPolicy.inScope(s, c, index) && "pending".equals(c.status)
                && !HologramCellPolicy.orientationOnly(s, c, index)
                && loaded
                && !HologramCellPolicy.trivial(world, c.x, c.y, c.z)
                && (!s.player.capabilities.isCreativeMode || world.getTileEntity(c.x, c.y, c.z) != null)) try {
                    for (ItemStack drop : HologramMaterialAccess.drops(current, world, c, meta)) {
                        recoveryRows++;
                        recoveryItems += drop.stackSize;
                        if (recovery.tagCount() < 96) recovery.appendTag(recoverySummary(drop, true));
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    row.setString("reason", "预计掉落无法确定，施工将重验并可暂停");
                }
        }
        if (s.blueprintResources != null && !s.player.capabilities.isCreativeMode)
            for (ItemStack content : s.blueprintResources.items) {
                String key = HologramValues.stackKey(content);
                NBTTagCompound requirement = requirements.get(key);
                if (requirement == null) {
                    ItemStack identity = content.copy();
                    identity.stackSize = 1;
                    requirement = identity.writeToNBT(new NBTTagCompound());
                    requirement.setInteger(
                        "available",
                        HologramMaterialAccess.materialSource(s)
                            .count(identity, Math.max(4096, content.stackSize)));
                    requirement.setInteger(
                        "mainAvailable",
                        HologramMaterialAccess.materialSource(s)
                            .mainCount(identity, Math.max(4096, content.stackSize)));
                    requirement.setInteger("controllerAvailable", -1);
                    requirements.put(key, requirement);
                }
                requirement.setInteger("required", requirement.getInteger("required") + content.stackSize);
            }
        for (NBTTagCompound req : requirements.values()) {
            materials.appendTag(req);
            missing += Math.max(0, req.getInteger("required") - req.getInteger("available"));
        }
        state.setTag("cells", cells);
        state.setTag("materials", materials);
        for (ItemStack drop : s.recovered) {
            recoveryRows++;
            recoveryItems += drop.stackSize;
            if (recovery.tagCount() < 96) recovery.appendTag(recoverySummary(drop, false));
        }
        state.setInteger("recoveryMore", Math.max(0, recoveryRows - recovery.tagCount()));
        state.setInteger("recoveryTotal", recoveryItems);
        state.setTag("recovery", recovery);
        state.setInteger("protected", protectedCount);
        state.setInteger("missing", Math.max(missing, s.planMissing));
        state.setInteger("total", total);
        state.setInteger("workTotal", workTotal);
        state.setString("description", "控制器始终保留；直接施工会重新扫描并逐格重验。手选目标覆盖对应位置，不保证结构成型；差分可供查看材料与改动。");
        HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.FULL_CELLS_BUILDS, 1);
        HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.FULL_CELLS_VISITED, cells.tagCount());
        if (s.job == 1) HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.RUNNING_JOB_FULL_BUILDS, 1);
        HologramWorkspace.snapshotSink()
            .send(s.player, state);
        s.jobPublication.published(s.player.worldObj.getTotalWorldTime(), s.planRevision, s.uiSequence);
        s.progressTick = s.player.worldObj.getTotalWorldTime();
        s.progressPhase = state.getCompoundTag("progress")
            .getString("phase");
    }

    static NBTTagCompound recoverySummary(ItemStack drop, boolean estimated) {
        ItemStack plain = new ItemStack(drop.getItem(), Math.min(64, drop.stackSize), drop.getItemDamage());
        NBTTagCompound row = plain.writeToNBT(new NBTTagCompound());
        row.setInteger("expected", drop.stackSize);
        row.setBoolean("estimated", estimated);
        row.setBoolean("sealed", HologramRecovery.hasSeal(drop));
        return row;
    }
}
