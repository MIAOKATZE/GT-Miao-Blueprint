package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonSyntaxException;

/** Legacy DTO boundary: independent mutable records without a second full JSON string/tree. */
final class HologramPresetCopies {

    static HologramPresets.Preset copy(HologramPresets.Preset source) {
        if (source == null) return null;
        Task task = new Task(source);
        while (!task.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
        return task.result;
    }

    /** Owner-held DTO input: one bounded record per operation; no registry or world callbacks. */
    static final class Task {

        private final HologramPresets.Preset source;
        final HologramPresets.Preset result = new HologramPresets.Preset();
        private java.util.Iterator<Map.Entry<String, String>> versions;
        private int stage, cursor;
        private long completed;

        Task(HologramPresets.Preset source) {
            this.source = java.util.Objects.requireNonNull(source);
            result.id = source.id;
            result.name = source.name;
            result.controllerType = source.controllerType;
            result.authorName = source.authorName;
            result.authorUuid = source.authorUuid;
            result.createdAt = source.createdAt;
            result.nativeBlueprint = metadata(source.nativeBlueprint);
            result.fullyVoucherCovered = source.fullyVoucherCovered;
            result.schema = source.schema;
            result.aePolicyVersion = source.aePolicyVersion;
            result.deidentificationVersion = source.deidentificationVersion;
            result.deidentificationLevel = source.deidentificationLevel;
            result.deidentificationChosen = source.deidentificationChosen;
            result.processingNbt = source.processingNbt;
            result.quantumGroupingNbt = source.quantumGroupingNbt;
            result.retainNbt = source.retainNbt;
            result.retainCovers = source.retainCovers;
            result.prompt = source.prompt;
            result.sourceMod = source.sourceMod;
            result.sourceClass = source.sourceClass;
            result.retainedNbtKeys = source.retainedNbtKeys == null ? null : new ArrayList<>();
            result.controllerId = source.controllerId;
            result.controllerFacing = source.controllerFacing;
            // These manifests are immutable. Inheriting them preserves authority while new DTOs own all writes.
            result.segmentDocument = source.segmentDocument;
            result.schemaPageCache = source.schemaPageCache;
            result.schemaPreferredCellId = source.schemaPreferredCellId;
            result.analysisDocument = source.analysisDocument;
        }

        long completed() {
            return completed;
        }

        long total() {
            return (long) size(source.aeIssues) + size(source.hintAreas)
                + size(source.structures)
                + size(source.retainedNbtKeys)
                + size(source.cells)
                + size(source.palette)
                + (source.modVersions == null ? 0 : source.modVersions.size());
        }

        boolean step(int maxWork, long maxNanos) {
            HologramPlanBudget budget = new HologramPlanBudget(maxWork, maxNanos);
            while (stage < 7 && budget.take()) {
                if (stage == 0 && cursor < size(source.aeIssues))
                    result.aeIssues.add(issue(source.aeIssues.get(cursor++)));
                else if (stage == 1 && cursor < size(source.hintAreas))
                    result.hintAreas.add(area(source.hintAreas.get(cursor++)));
                else if (stage == 2 && cursor < size(source.structures))
                    result.structures.add(structure(source.structures.get(cursor++)));
                else if (stage == 3 && cursor < size(source.retainedNbtKeys))
                    result.retainedNbtKeys.add(source.retainedNbtKeys.get(cursor++));
                else if (stage == 4 && cursor < size(source.cells)) result.cells.add(cell(source.cells.get(cursor++)));
                else if (stage == 5 && cursor < size(source.palette))
                    result.palette.add(cell(source.palette.get(cursor++)));
                else if (stage == 6) {
                    if (versions == null)
                        versions = (source.modVersions == null ? java.util.Collections.<String, String>emptyMap()
                            : source.modVersions).entrySet()
                                .iterator();
                    if (!versions.hasNext()) {
                        stage++;
                        continue;
                    }
                    Map.Entry<String, String> entry = versions.next();
                    if (entry.getValue() != null) {
                        String key = String.valueOf(entry.getKey());
                        if (result.modVersions.containsKey(key)) throw new JsonSyntaxException("duplicate key: " + key);
                        result.modVersions.put(key, entry.getValue());
                    }
                } else {
                    stage++;
                    cursor = 0;
                    continue;
                }
                completed++;
            }
            return stage == 7;
        }

        private static int size(List<?> values) {
            return values == null ? 0 : values.size();
        }
    }

    private static List<HologramPresets.Cell> cells(List<HologramPresets.Cell> source) {
        List<HologramPresets.Cell> result = new ArrayList<>(source.size());
        for (HologramPresets.Cell cell : source) result.add(cell(cell));
        return result;
    }

    private static HologramPresets.Cell cell(HologramPresets.Cell source) {
        if (source == null) return null;
        HologramPresets.Cell result = new HologramPresets.Cell();
        result.item = source.item;
        result.defaultItem = source.defaultItem;
        result.block = source.block;
        result.tileNbt = source.tileNbt;
        result.itemNbt = source.itemNbt;
        result.prompt = source.prompt;
        result.structureId = source.structureId;
        result.structureIds = strings(source.structureIds);
        if (source.allowedTargets != null) {
            result.allowedTargets = new ArrayList<>(source.allowedTargets.size());
            for (HologramPresets.Target target : source.allowedTargets) result.allowedTargets.add(target(target));
        }
        result.metadata = source.metadata;
        result.excluded = source.excluded;
        result.defaultDamage = source.defaultDamage;
        result.damage = source.damage;
        result.count = source.count;
        result.x = source.x;
        result.y = source.y;
        result.z = source.z;
        result.facing = source.facing;
        result.controller = source.controller;
        result.segmentCellId = source.segmentCellId;
        // Frozen native/provider data belongs to its original snapshot scope, as with the former Gson boundary.
        return result;
    }

    private static HologramPresets.Target target(HologramPresets.Target source) {
        if (source == null) return null;
        HologramPresets.Target result = new HologramPresets.Target();
        result.item = source.item;
        result.damage = source.damage;
        return result;
    }

    private static HologramPresets.HintArea area(HologramPresets.HintArea source) {
        if (source == null) return null;
        HologramPresets.HintArea result = new HologramPresets.HintArea();
        result.x1 = source.x1;
        result.y1 = source.y1;
        result.z1 = source.z1;
        result.x2 = source.x2;
        result.y2 = source.y2;
        result.z2 = source.z2;
        result.prompt = source.prompt;
        return result;
    }

    private static HologramPresets.Structure structure(HologramPresets.Structure source) {
        if (source == null) return null;
        HologramPresets.Structure result = new HologramPresets.Structure();
        result.id = source.id;
        result.name = source.name;
        result.sourceMod = source.sourceMod;
        result.sourceClass = source.sourceClass;
        result.textBoxNbt = source.textBoxNbt;
        result.x = source.x;
        result.y = source.y;
        result.z = source.z;
        result.controllerId = source.controllerId;
        result.enabled = source.enabled;
        result.main = source.main;
        result.mainExplicit = source.mainExplicit;
        result.noHatches = source.noHatches;
        result.channels = map(source.channels);
        return result;
    }

    private static HologramBlueprintVersions.Metadata metadata(HologramBlueprintVersions.Metadata source) {
        if (source == null) return null;
        HologramBlueprintVersions.Metadata result = new HologramBlueprintVersions.Metadata();
        result.formatVersion = source.formatVersion;
        result.gtmbVersion = source.gtmbVersion;
        result.gtnhVersion = source.gtnhVersion;
        result.gtnhVersionSource = source.gtnhVersionSource;
        return result;
    }

    private static HologramAePolicy.Issue issue(HologramAePolicy.Issue source) {
        if (source == null) return null;
        HologramAePolicy.Issue result = new HologramAePolicy.Issue();
        result.code = source.code;
        result.severity = source.severity;
        result.path = source.path;
        result.message = source.message;
        result.x = source.x;
        result.y = source.y;
        result.z = source.z;
        result.count = source.count;
        return result;
    }

    private static List<String> strings(List<String> source) {
        return source == null ? null : new ArrayList<>(source);
    }

    /** Default Gson omits null map values and serializes a null String key as the literal "null". */
    private static <V> Map<String, V> map(Map<String, V> source) {
        Map<String, V> result = new LinkedHashMap<>();
        if (source != null) for (Map.Entry<String, V> entry : source.entrySet()) {
            if (entry.getValue() == null) continue;
            String key = String.valueOf(entry.getKey());
            if (result.containsKey(key)) throw new JsonSyntaxException("duplicate key: " + key);
            result.put(key, entry.getValue());
        }
        return result;
    }

    private HologramPresetCopies() {}
}
