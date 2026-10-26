package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/** Freezes the processing input a row at a time. Projection/native callbacks execute only on the owner thread. */
final class HologramSnapshotMetadataSource {

    static boolean detached() {
        return HologramSnapshotDataContext.active();
    }

    static void enterDetached() {
        enterDetached(new HologramSnapshotDataContext());
    }

    static void enterDetached(HologramSnapshotDataContext context) {
        HologramSnapshotDataContext.enter(context);
    }

    static void exitDetached() {
        HologramSnapshotDataContext.exit();
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private final HologramSession s;
    private final HologramSnapshotDataContext dataContext;
    private final HologramPresets.Preset original;
    private final boolean project;
    private final boolean draftOnly;
    private final boolean publishSource;
    final HologramPresets.Preset result = new HologramPresets.Preset();
    HologramBlueprintProcessing.Policy policy;
    private final java.util.Set<String> palette = new HashSet<>();
    private final Map<String, HologramPresets.Cell> payloadCells = new java.util.HashMap<>();
    private int stage, cursor;
    private Iterator<? extends Map.Entry<String, String>> map;
    private Iterator<HologramArea.Structure> areaStructures;

    HologramSnapshotMetadataSource(HologramSession s, HologramSnapshotDataContext dataContext) {
        this(s, dataContext, false);
    }

    HologramSnapshotMetadataSource(HologramSession s, HologramSnapshotDataContext dataContext, boolean forceProject) {
        this.s = s;
        this.dataContext = dataContext;
        project = forceProject || s.processingSource == null;
        draftOnly = s.localPlanPending && !s.localPlanError.isEmpty();
        publishSource = project || draftOnly;
        original = project ? s.virtualPreset : s.processingSource;
    }

    long done() {
        return stage == 12 ? 1 : cursor;
    }

    String phase() {
        return "metadata.source." + stage;
    }

    long total() {
        if (!publishSource && (stage >= 2 && stage <= 5 || stage == 7)) return 0;
        if (stage == 1) return original == null || project && s.machine != null ? 0 : original.structures.size();
        if (stage == 2) return original == null ? 0 : original.aeIssues.size();
        if (stage == 3) return original == null ? s.blueprintHintAreas.size() : original.hintAreas.size();
        if (stage == 4) {
            java.util.List<String> keys = original == null ? s.retainedNbtKeys : original.retainedNbtKeys;
            return keys == null ? 0 : keys.size();
        }
        if (stage == 5) return original == null ? 0 : original.modVersions.size();
        if (stage == 6) return project ? s.capture == null ? 0 : s.capture.cells.size() : original.cells.size();
        if (stage == 7) return project ? 0 : original.palette.size();
        if (stage == 8) {
            HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
            return !project || area == null ? 0 : area.structures.size();
        }
        if (stage == 9) return s.processingPolicy == null ? 0 : s.processingPolicy.cellNbtOverrides.size();
        if (stage == 10) return s.processingPolicy == null ? 0 : s.processingPolicy.coordinateOverrides.size();
        if (stage == 11) return s.processingPolicy == null ? 0 : s.processingPolicy.resourceDecisions.size();
        return 1;
    }

    boolean step(int maxWork, long deadline) throws IOException {
        for (int work = 0; work < maxWork && stage < 12; work++) {
            if (work > 0 && System.nanoTime() >= deadline) return false;
            if (stage == 0) initialize();
            else if (stage == 1) {
                if (original != null && !(project && s.machine != null) && cursor < original.structures.size())
                    result.structures.add(HologramBlueprintData.copyStructure(original.structures.get(cursor++)));
                else next();
            } else if (stage == 2) {
                if (!publishSource) {
                    stage = 6;
                    cursor = 0;
                } else if (original != null && cursor < original.aeIssues.size()) result.aeIssues
                    .add(GSON.fromJson(GSON.toJson(original.aeIssues.get(cursor++)), HologramAePolicy.Issue.class));
                else next();
            } else if (stage == 3) {
                java.util.List<HologramPresets.HintArea> hints = original == null ? s.blueprintHintAreas
                    : original.hintAreas;
                if (cursor < hints.size()) result.hintAreas
                    .add(GSON.fromJson(GSON.toJson(hints.get(cursor++)), HologramPresets.HintArea.class));
                else next();
            } else if (stage == 4) {
                java.util.List<String> keys = original == null ? s.retainedNbtKeys : original.retainedNbtKeys;
                if (keys != null && cursor < keys.size()) result.retainedNbtKeys.add(keys.get(cursor++));
                else next();
            } else if (stage == 5) {
                if (map == null && original != null) map = original.modVersions.entrySet()
                    .iterator();
                if (map != null && map.hasNext()) {
                    Map.Entry<String, String> entry = map.next();
                    result.modVersions.put(entry.getKey(), entry.getValue());
                    cursor++;
                } else {
                    map = null;
                    next();
                }
            } else if (stage == 6) {
                int size = project ? s.capture.cells.size() : original.cells.size();
                if (cursor < size) {
                    HologramPresets.Cell cell = project
                        ? HologramBlueprintProjection.projectedCell(s, cursor++, result, palette)
                        : copyCell(original.cells.get(cursor++), publishSource);
                    if (draftOnly) {
                        HologramCapture.Cell authoring = project ? s.capture.cells.get(cursor - 1)
                            : s.capture.positions.get((s.x + cell.x) + "," + (s.y + cell.y) + "," + (s.z + cell.z));
                        if (authoring == null && cursor <= s.capture.cells.size()) {
                            HologramCapture.Cell candidate = s.capture.cells.get(cursor - 1);
                            if (candidate.x == s.x + cell.x && candidate.y == s.y + cell.y
                                && candidate.z == s.z + cell.z) authoring = candidate;
                        }
                        if (authoring != null) cell.facing = HologramBlockIdentity.front(
                            s.facingPins.getOrDefault(HologramValues.position(authoring), authoring.actualFacing));
                    }
                    try {
                        freezeBlock(cell, dataContext);
                    } catch (HologramSnapshotDataContext.Busy busy) {
                        cursor--;
                        throw busy;
                    }
                    if (hasPayload(cell.tileNbt) || hasPayload(cell.itemNbt))
                        payloadCells.put(cell.x + "," + cell.y + "," + cell.z, cell);
                    result.cells.add(cell);
                } else next();
            } else if (stage == 7) {
                if (!project && publishSource && cursor < original.palette.size())
                    result.palette.add(copyCell(original.palette.get(cursor++)));
                else next();
            } else if (stage == 8) {
                HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
                if (areaStructures == null && project && area != null) areaStructures = area.structures.values()
                    .iterator();
                if (areaStructures != null && areaStructures.hasNext()) {
                    String mod = HologramSessionPolicy.sourceMod(areaStructures.next().machine.getClass());
                    cursor++;
                    for (cpw.mods.fml.common.ModContainer installed : cpw.mods.fml.common.Loader.instance()
                        .getModList())
                        if (mod.equals(installed.getModId())) result.modVersions.put(mod, installed.getVersion());
                } else {
                    initializePolicy();
                    next();
                }
            } else if (stage == 9) {
                if (s.processingPolicy != null && cursor < s.processingPolicy.cellNbtOverrides.size())
                    policy.cellNbtOverrides.add(
                        GSON.fromJson(
                            GSON.toJson(s.processingPolicy.cellNbtOverrides.get(cursor++)),
                            HologramBlueprintProcessing.CellNbtOverride.class));
                else next();
            } else if (stage == 10) {
                if (map == null && s.processingPolicy != null) map = s.processingPolicy.coordinateOverrides.entrySet()
                    .iterator();
                if (map != null && map.hasNext()) {
                    Map.Entry<String, String> entry = map.next();
                    policy.coordinateOverrides.put(entry.getKey(), entry.getValue());
                    cursor++;
                } else next();
            } else {
                if (s.processingPolicy != null && cursor < s.processingPolicy.resourceDecisions.size())
                    policy.resourceDecisions.add(
                        GSON.fromJson(
                            GSON.toJson(s.processingPolicy.resourceDecisions.get(cursor++)),
                            HologramBlueprintProcessing.ResourceDecision.class));
                else next();
            }
        }
        return stage == 12;
    }

    private void next() {
        stage++;
        cursor = 0;
    }

    private void initialize() throws IOException {
        if (s.capture == null || s.capture.incomplete || s.capturePending) throw new IOException("请先完整载入结构或区域，再进行蓝图处理");
        if (original != null) {
            dataContext.seed(original.analysisDocument);
            dataContext.freeze(original.processingNbt);
            dataContext.freeze(original.quantumGroupingNbt);
            copyScalars(original, result);
            if (publishSource && original.nativeBlueprint != null) result.nativeBlueprint = GSON
                .fromJson(GSON.toJson(original.nativeBlueprint), HologramBlueprintVersions.Metadata.class);
        }
        java.util.List<String> keys = original == null ? s.retainedNbtKeys : original.retainedNbtKeys;
        result.retainedNbtKeys = keys == null ? null : new ArrayList<>();
        if (project) {
            result.schema = 2;
            result.name = s.virtualPreset == null || s.virtualPreset.name == null
                || s.virtualPreset.name.trim()
                    .isEmpty() ? "\u672a\u547d\u540d\u84dd\u56fe" : s.virtualPreset.name.trim();
            if (s.virtualPreset == null) {
                result.id = s.blueprintSavedId.isEmpty() ? null : s.blueprintSavedId;
                result.prompt = s.blueprintPrompt;
            }
            if (s.machine != null) {
                HologramPresets.Structure declared = new HologramPresets.Structure();
                declared.id = s.x + "," + s.y + "," + s.z;
                declared.name = s.machine.getLocalName();
                declared.controllerId = ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) s.target)
                    .getMetaTileID();
                declared.sourceClass = s.machine.getClass()
                    .getName();
                declared.sourceMod = HologramSessionPolicy.sourceMod(s.machine.getClass());
                HologramBlueprintData.storeStructureConfiguration(s, declared);
                result.structures.add(declared);
                result.sourceClass = declared.sourceClass;
                result.sourceMod = declared.sourceMod;
                result.controllerId = declared.controllerId;
                result.controllerType = HologramSessionPolicy.controllerType(s);
                result.controllerFacing = s.machine.getExtendedFacing()
                    .ordinal();
            }
        }
        next();
    }

    private void initializePolicy() {
        if (s.processingPolicy == null) return;
        policy = new HologramBlueprintProcessing.Policy();
        copyScalars(s.processingPolicy, policy);
        policy.confirmedStages = new ArrayList<>(s.processingPolicy.confirmedStages);
        policy.ignoredNbtKeys = new ArrayList<>(s.processingPolicy.ignoredNbtKeys);
        policy.ignoredNbtPaths = new ArrayList<>(s.processingPolicy.ignoredNbtPaths);
    }

    static HologramPresets.Cell copyCell(HologramPresets.Cell source) {
        return copyCell(source, true);
    }

    private static HologramPresets.Cell copyCell(HologramPresets.Cell source, boolean targets) {
        HologramPresets.Cell result = new HologramPresets.Cell();
        copyScalars(source, result);
        if (source.structureIds != null) result.structureIds = new ArrayList<>(source.structureIds);
        if (targets && source.allowedTargets != null) {
            result.allowedTargets = new ArrayList<>();
            for (HologramPresets.Target target : source.allowedTargets)
                result.allowedTargets.add(GSON.fromJson(GSON.toJson(target), HologramPresets.Target.class));
        }
        return result;
    }

    boolean publishesSource() {
        return publishSource;
    }

    /** Copy the header and small descriptors without copying a cell tree that the caller will replace. */
    static HologramPresets.Preset copyMetadata(HologramPresets.Preset source) {
        HologramPresets.Preset result = new HologramPresets.Preset();
        copyScalars(source, result);
        if (source.nativeBlueprint != null) result.nativeBlueprint = GSON
            .fromJson(GSON.toJson(source.nativeBlueprint), HologramBlueprintVersions.Metadata.class);
        for (HologramPresets.Structure structure : source.structures)
            result.structures.add(HologramBlueprintData.copyStructure(structure));
        for (HologramAePolicy.Issue issue : source.aeIssues)
            result.aeIssues.add(GSON.fromJson(GSON.toJson(issue), HologramAePolicy.Issue.class));
        for (HologramPresets.HintArea hint : source.hintAreas)
            result.hintAreas.add(GSON.fromJson(GSON.toJson(hint), HologramPresets.HintArea.class));
        result.retainedNbtKeys = source.retainedNbtKeys == null ? null : new ArrayList<>(source.retainedNbtKeys);
        result.modVersions.putAll(source.modVersions);
        return result;
    }

    static void freezeBlock(HologramPresets.Cell cell) throws IOException {
        freezeBlock(cell, new HologramSnapshotDataContext());
    }

    static void freezeBlock(HologramPresets.Cell cell, HologramSnapshotDataContext dataContext) throws IOException {
        dataContext.freezeBlock(cell);
    }

    HologramPresets.Cell cellWithPayloadAt(int x, int y, int z) {
        return payloadCells.isEmpty() ? null : payloadCells.get(x + "," + y + "," + z);
    }

    static boolean hasPayload(String encoded) {
        return encoded != null && !encoded.isEmpty();
    }

    /** Typed copies remain legal when the native bootstrap redirects reflective Field access. */
    private static void copyScalars(HologramPresets.Preset source, HologramPresets.Preset target) {
        target.id = source.id;
        target.name = source.name;
        target.controllerType = source.controllerType;
        target.authorName = source.authorName;
        target.authorUuid = source.authorUuid;
        target.createdAt = source.createdAt;
        target.fullyVoucherCovered = source.fullyVoucherCovered;
        target.schema = source.schema;
        target.aePolicyVersion = source.aePolicyVersion;
        target.deidentificationVersion = source.deidentificationVersion;
        target.deidentificationLevel = source.deidentificationLevel;
        target.deidentificationChosen = source.deidentificationChosen;
        target.processingNbt = source.processingNbt;
        target.quantumGroupingNbt = source.quantumGroupingNbt;
        target.segmentDocument = source.segmentDocument;
        target.analysisDocument = source.analysisDocument;
        target.retainNbt = source.retainNbt;
        target.retainCovers = source.retainCovers;
        target.prompt = source.prompt;
        target.sourceMod = source.sourceMod;
        target.sourceClass = source.sourceClass;
        target.controllerId = source.controllerId;
        target.controllerFacing = source.controllerFacing;
    }

    private static void copyScalars(HologramPresets.Cell source, HologramPresets.Cell target) {
        target.item = source.item;
        target.segmentCellId = source.segmentCellId;
        target.defaultItem = source.defaultItem;
        target.block = source.block;
        target.tileNbt = source.tileNbt;
        target.itemNbt = source.itemNbt;
        target.prompt = source.prompt;
        target.structureId = source.structureId;
        target.metadata = source.metadata;
        target.excluded = source.excluded;
        target.defaultDamage = source.defaultDamage;
        target.damage = source.damage;
        target.count = source.count;
        target.x = source.x;
        target.y = source.y;
        target.z = source.z;
        target.facing = source.facing;
        target.controller = source.controller;
    }

    private static void copyScalars(HologramBlueprintProcessing.Policy source,
        HologramBlueprintProcessing.Policy target) {
        target.tendency = source.tendency;
        target.quantum = source.quantum;
        target.coordinateDefault = source.coordinateDefault;
        target.autoHints = source.autoHints;
        target.deidentificationLevel = source.deidentificationLevel;
    }
}
