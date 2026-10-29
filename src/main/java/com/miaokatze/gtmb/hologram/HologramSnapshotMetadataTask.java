package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Full metadata publication: source/world queries checkpoint on the server, detached reports run on the worker. */
final class HologramSnapshotMetadataTask {

    private final HologramSession s;
    private final NBTTagCompound state;
    private final long revision;
    private final HologramCapture capture;
    private final boolean authoring, draftOnly;
    private final HologramSnapshotDataContext dataContext = new HologramSnapshotDataContext();
    private HologramSnapshotMetadataSource source;
    private HologramSnapshotAeTask ae;
    private HologramNetworkBindings.SnapshotTask networks;
    private HologramResourcePlanner.ResourceRowsTask resources;
    private final List<Position> positions = new ArrayList<>();
    private Set<String> availableKeys = new TreeSet<>();
    private final NBTTagList structures = new NBTTagList(), entries = new NBTTagList(), hints = new NBTTagList(),
        versions = new NBTTagList();
    private final Map<String, String> installed = new HashMap<>();
    private Iterator<HologramArea.Structure> areaIterator;
    private Iterator<String> keyIterator;
    private Iterator<Map.Entry<String, String>> dependencyIterator;
    private Map<String, String> dependencies;
    private Iterator<Integer> selectionIterator;
    private int[] selection;
    private int stage, cursor;
    private boolean keysCached, decorative;
    private String sourceWarning = "";
    private FutureTask<Report> report;
    private final WorkerProgress workerProgress = new WorkerProgress();

    HologramSnapshotMetadataTask(HologramSession s, NBTTagCompound state) {
        this.s = s;
        this.state = state;
        revision = s.planRevision;
        capture = s.capture;
        authoring = HologramSessionPolicy.authoring(s);
        draftOnly = authoring || s.localPlanPending && !s.localPlanError.isEmpty();
    }

    String phase() {
        if (stage == 1 && source != null) return source.phase();
        if (stage == 3) return workerProgress.value.phase;
        if (stage == 12 && ae != null) return ae.phase();
        return new String[] { "metadata.initialize", "metadata.source", "metadata.capture", "metadata.processing",
            "metadata.network_groups", "metadata.labels", "metadata.nbt_keys", "metadata.resources", "metadata.mods",
            "metadata.dependencies", "metadata.hints", "metadata.selection", "metadata.ae",
            "metadata.complete" }[stage];
    }

    long done() {
        if (stage == 1 && source != null) return source.done();
        if (stage == 3) return workerProgress.value.done;
        if (stage == 4 && networks != null) return networks.done();
        if (stage == 7 && resources != null) return resources.done();
        if (stage == 12 && ae != null) return ae.done();
        return stage == 13 ? 1 : cursor;
    }

    HologramAreaPreparation.Progress progress() {
        return stage == 3 ? workerProgress.value : new HologramAreaPreparation.Progress(phase(), done(), total());
    }

    long total() {
        if (stage == 1 && source != null) return source.total();
        if (stage == 2) return capture == null ? networkSize() : capture.cells.size();
        if (stage == 3) return workerProgress.value.total;
        if (stage == 4 && networks != null) return networks.total();
        if (stage == 5) return labelSize();
        if (stage == 6) return availableKeys.size();
        if (stage == 7 && resources != null) return resources.total();
        if (stage == 8) return cpw.mods.fml.common.Loader.instance()
            .getModList()
            .size();
        if (stage == 9) return s.virtualPreset == null ? s.machine == null ? 0 : 1 : s.virtualPreset.modVersions.size();
        if (stage == 10) return (s.virtualPreset == null ? s.blueprintHintAreas : s.virtualPreset.hintAreas).size();
        if (stage == 11) return s.selectedIndices.size();
        if (stage == 12 && ae != null) return ae.total();
        return 1;
    }

    boolean step(int maxWork, long deadline) {
        if (s.planRevision != revision || s.capture != capture) {
            cancel();
            throw new IllegalStateException("Snapshot metadata source changed");
        }
        for (int work = 0; work < maxWork && stage < 13; work++) {
            if (work > 0 && System.nanoTime() >= deadline) return false;
            try {
                if (stage == 0) initialize();
                else if (stage == 1) {
                    try {
                        if (source.step(1, deadline)) next();
                    } catch (IOException invalid) {
                        state.setString("processingError", invalid.getMessage());
                        source = null;
                        next();
                    }
                } else if (stage == 2) capturePosition();
                else if (stage == 3) {
                    if (!deriveReport()) return false;
                } else if (stage == 4) {
                    if (networks == null || networks.step(1, deadline)) {
                        if (networks != null) state.setTag("networkBindings", networks.result);
                        next();
                    }
                } else if (stage == 5) appendLabel();
                else if (stage == 6) appendNbtKey();
                else if (stage == 7) {
                    if (draftOnly) {
                        state.setTag("blueprintResources", new NBTTagList());
                        state.setBoolean("planIncomplete", s.localPlanPending);
                        state.setBoolean("quotesIncomplete", true);
                        next();
                    } else if (resources.step(1, deadline)) {
                        state.setTag("blueprintResources", resources.result());
                        next();
                    }
                } else if (stage == 8) {
                    List<cpw.mods.fml.common.ModContainer> mods = cpw.mods.fml.common.Loader.instance()
                        .getModList();
                    if (cursor < mods.size()) {
                        cpw.mods.fml.common.ModContainer mod = mods.get(cursor++);
                        installed.put(mod.getModId(), mod.getVersion());
                    } else next();
                } else if (stage == 9) appendDependency();
                else if (stage == 10) appendHint();
                else if (stage == 11) appendSelection();
                else if (authoring && s.geometryEdits.validationPending) {
                    stage = 13;
                    cursor = 1;
                } else if (ae.step(1, deadline)) {
                    stage = 13;
                    cursor = 1;
                }
            } catch (IOException invalid) {
                if (stage == 4) {
                    state.setString("networkBindingError", HologramAePolicy.fallback(invalid.getMessage()));
                    stage = 5;
                    cursor = 0;
                } else throw new IllegalStateException("Snapshot metadata rejected", invalid);
            }
        }
        return stage == 13;
    }

    void cancel() {
        if (report != null) HologramAreaPreparation.cancel(report);
    }

    boolean waitingForWorker() {
        return stage == 3 && (report == null || !report.isDone());
    }

    private void next() {
        stage++;
        cursor = 0;
    }

    private void initialize() {
        HologramSnapshots.writeBlueprintScalars(s, state);
        if (authoring && s.geometryEdits.validationPending) {
            // Presentation resync needs geometry rows, but does not authorize a complete processing pass.
            HologramGeometryEdits.pendingMetadata(s, state);
            keysCached = true;
            sourceWarning = s.virtualPreset == null ? "" : HologramBlueprintData.sourceWarning(s.virtualPreset, null);
            stage = 5;
            cursor = 0;
            return;
        }
        source = new HologramSnapshotMetadataSource(s, dataContext);
        if (s.virtualPreset != null) try {
            dataContext.freeze(s.virtualPreset.processingNbt);
        } catch (IOException ignored) { /* The detached processing report retains the same validation error. */ }
        ae = new HologramSnapshotAeTask(s, state);
        if (authoring) {
            NBTTagCompound bindings = new NBTTagCompound();
            bindings.setBoolean("nativeAuthority", false);
            bindings.setBoolean("terminalSecurityChecked", false);
            bindings.setBoolean("terminalBinding", false);
            bindings.setTag("groups", new NBTTagList());
            bindings.setTag("inventory", new NBTTagList());
            state.setTag("networkBindings", bindings);
        } else networks = new HologramNetworkBindings.SnapshotTask(s.player, s.networkBindings);
        if (!draftOnly) resources = new HologramResourcePlanner.ResourceRowsTask(s);
        keysCached = s.nbtKeysCapture == capture;
        if (keysCached) keyIterator = s.availableNbtKeys.iterator();
        if (s.virtualPreset != null) sourceWarning = HologramBlueprintData.sourceWarning(s.virtualPreset, null);
        next();
    }

    private void capturePosition() {
        if (keysCached && keyIterator != null && keyIterator.hasNext()) {
            availableKeys.add(keyIterator.next());
            return;
        }
        if (capture == null && cursor < networkSize()) {
            HologramPresets.Cell cell = s.virtualPreset.cells.get(cursor++);
            collectNetwork(cell.tileNbt, cell.excluded);
        } else if (capture != null && cursor < capture.cells.size()) {
            HologramCapture.Cell cell = capture.cells.get(cursor);
            if (s.virtualPreset != null) collectNetwork(cell.tileNbt, cell.excluded);
            if (!keysCached && s.virtual) try {
                dataContext.rootKeys(cell.tileNbt, cell.itemNbt, availableKeys);
            } catch (IOException ignored) { /* Invalid cell payload remains unsupported in the detached report. */ }
            HologramPresets.Cell frozen = source == null ? null
                : source.cellWithPayloadAt(cell.x - s.x, cell.y - s.y, cell.z - s.z);
            if (frozen != null) positions.add(new Position(cursor, frozen));
            cursor++;
            if (!decorative && s.virtualPreset != null) decorative = HologramCellPolicy.decorationTarget(s, cell);
            if (!keysCached && !s.virtual) try {
                TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
                if (tile != null) rootKeys(HologramNativeCapture.capture(tile), availableKeys);
            } catch (IOException | RuntimeException ignored) { /* Invalid payload stays unsupported. */ }
        } else {
            keyIterator = null;
            next();
        }
    }

    private boolean deriveReport() {
        if (report == null) {
            HologramPresets.Preset frozen = source == null ? null : source.result;
            HologramBlueprintProcessing.Policy frozenPolicy = source == null ? null : source.policy;
            int selected = s.selected, dimension = s.dimension, x = s.x, y = s.y, z = s.z;
            boolean reviewed = s.processingReviewed;
            String receipt = s.virtualPreset == null ? null : s.virtualPreset.processingNbt;
            List<Position> frozenPositions = positions;
            HologramSnapshotDataContext frozenContext = dataContext;
            WorkerProgress progress = workerProgress;
            FutureTask<Report> candidate = new FutureTask<>(() -> {
                HologramSnapshotMetadataSource.enterDetached(frozenContext);
                try {
                    return derive(
                        frozen,
                        frozenPolicy,
                        frozenPositions,
                        selected,
                        dimension,
                        x,
                        y,
                        z,
                        reviewed,
                        receipt,
                        progress);
                } finally {
                    HologramSnapshotMetadataSource.exitDetached();
                }
            });
            if (!HologramAreaPreparation.execute(candidate)) return false;
            report = candidate;
        }
        if (!report.isDone()) return false;
        try {
            Report result = report.get();
            for (Object raw : result.fields.func_150296_c())
                state.setTag((String) raw, result.fields.getTag((String) raw));
            if (!keysCached) {
                s.availableNbtKeys.clear();
                s.nbtKeysCapture = null;
            }
            if (source != null && source.publishesSource()
                && (s.processingSource == null || s.localPlanPending && !s.localPlanError.isEmpty())) {
                s.processingSource = result.source;
                if (s.processingPolicy == null) s.processingPolicy = result.policy;
            }
            state.setBoolean("networkBindingRequested", !authoring && s.networkBindingRequested);
            state.setBoolean("decorativeMaterialsNotice", decorative);
            next();
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("Snapshot metadata worker failed", failed.getCause());
        }
    }

    private int networkSize() {
        return s.virtualPreset != null && capture != null ? capture.cells.size()
            : s.virtualPreset == null ? 0 : s.virtualPreset.cells.size();
    }

    private void collectNetwork(String payload, boolean excluded) {
        if (authoring || networks == null || excluded) return;
        try {
            if (dataContext.hasNetwork(payload)) networks.add(dataContext.freeze(payload));
        } catch (IOException invalid) {
            state.setString("networkBindingError", HologramAePolicy.fallback(invalid.getMessage()));
            networks = null;
        }
    }

    private int labelSize() {
        if (s.virtualPreset != null && !s.virtualPreset.structures.isEmpty()) return s.virtualPreset.structures.size();
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        return area == null ? 0 : area.structures.size();
    }

    private void appendLabel() {
        if (cursor >= labelSize()) {
            state.setTag("structures", structures);
            state.setBoolean("sourceMissing", !sourceWarning.isEmpty());
            state.setString("sourceWarning", sourceWarning);
            keyIterator = availableKeys.iterator();
            next();
            return;
        }
        NBTTagCompound row = new NBTTagCompound();
        if (s.virtualPreset != null && !s.virtualPreset.structures.isEmpty()) {
            HologramPresets.Structure saved = s.virtualPreset.structures.get(cursor++);
            row.setString("id", saved.id);
            if (saved.textBoxNbt != null && !saved.textBoxNbt.isEmpty()) {
                try {
                    row.setTag("textBox", HologramTextWorkflow.definition(saved));
                } catch (IOException invalid) {
                    row.setString("textBoxError", "文字定义损坏");
                }
            }
            row.setString("name", saved.name == null ? "\u7ed3\u6784" : saved.name);
            row.setInteger("x", s.x + saved.x);
            row.setInteger("y", s.y + saved.y);
            row.setInteger("z", s.z + saved.z);
            row.setBoolean("enabled", saved.enabled);
            String warning = HologramBlueprintData.sourceWarning(s.virtualPreset, saved);
            if (sourceWarning.isEmpty()) sourceWarning = warning;
            row.setBoolean("sourceMissing", !warning.isEmpty());
            row.setString("sourceWarning", warning);
            row.setString("sourceClass", saved.sourceClass == null ? "" : saved.sourceClass);
            row.setString("sourceMod", saved.sourceMod == null ? "" : saved.sourceMod);
            row.setBoolean("declared", warning.isEmpty());
            TileEntity tile = authoring ? null
                : s.player.worldObj.getTileEntity(s.x + saved.x, s.y + saved.y, s.z + saved.z);
            row.setBoolean(
                "formed",
                tile instanceof IGregTechTileEntity
                    && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase
                    && ((MTEEnhancedMultiBlockBase<?>) ((IGregTechTileEntity) tile).getMetaTileEntity()).mMachine);
        } else {
            HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
            if (areaIterator == null) areaIterator = area.structures.values()
                .iterator();
            HologramArea.Structure structure = areaIterator.next();
            cursor++;
            row.setString("id", structure.id);
            row.setString("name", structure.machine.getLocalName());
            row.setInteger("x", structure.x);
            row.setInteger("y", structure.y);
            row.setInteger("z", structure.z);
            row.setBoolean("enabled", structure.enabled);
            row.setBoolean("formed", structure.machine.mMachine);
            row.setBoolean("declared", structure.capture != null && !structure.capture.incomplete);
        }
        structures.appendTag(row);
    }

    private void appendNbtKey() {
        if (!keyIterator.hasNext()) {
            state.setTag("nbtEntries", entries);
            if (!keysCached) s.nbtKeysCapture = capture;
            next();
            return;
        }
        String key = keyIterator.next(), lower = key.toLowerCase(java.util.Locale.ROOT);
        if (!keysCached) s.availableNbtKeys.add(key);
        cursor++;
        List<String> savedKeys = s.virtualPreset == null ? s.retainedNbtKeys : s.virtualPreset.retainedNbtKeys;
        List<String> placement = HologramSessionPolicy.placementKeys(s);
        NBTTagCompound row = new NBTTagCompound();
        row.setString("key", key);
        row.setString("label", key);
        row.setString(
            "category",
            lower.contains("fluid") || lower.contains("tank") ? "fluid"
                : lower.contains("energy") || lower.equals("meu") ? "energy"
                    : lower.contains("inventory") || lower.equals("items") ? "items" : "configuration");
        row.setBoolean("selected", savedKeys == null || savedKeys.contains(key));
        row.setBoolean("blueprintSelected", savedKeys == null || savedKeys.contains(key));
        row.setBoolean("placementSelected", placement == null || placement.contains(key));
        entries.appendTag(row);
    }

    private void appendDependency() {
        if (dependencyIterator == null) {
            dependencies = s.virtualPreset == null ? new LinkedHashMap<>() : s.virtualPreset.modVersions;
            if (s.machine != null) {
                String mod = HologramSessionPolicy.sourceMod(s.machine.getClass());
                state.setString("sourceMod", mod);
                state.setString(
                    "sourceClass",
                    s.machine.getClass()
                        .getName());
                if (installed.containsKey(mod)) dependencies.put(mod, installed.get(mod));
            }
            dependencyIterator = dependencies.entrySet()
                .iterator();
        }
        if (!dependencyIterator.hasNext()) {
            state.setTag("modVersions", versions);
            next();
            return;
        }
        Map.Entry<String, String> dependency = dependencyIterator.next();
        cursor++;
        String current = "minecraft".equals(dependency.getKey()) ? "1.7.10" : installed.get(dependency.getKey());
        int comparison = current == null ? 0
            : HologramBlueprintMetadata.compareVersions(current, dependency.getValue());
        NBTTagCompound row = new NBTTagCompound();
        row.setString("modId", dependency.getKey());
        row.setString("saved", dependency.getValue());
        row.setString("current", current == null ? "" : current);
        row.setString(
            "state",
            current == null ? "missing" : comparison < 0 ? "outdated" : comparison > 0 ? "ahead" : "current");
        versions.appendTag(row);
        if (s.machine != null) {
            state.setString("sourceMod", HologramSessionPolicy.sourceMod(s.machine.getClass()));
            state.setString(
                "sourceClass",
                s.machine.getClass()
                    .getName());
        }
    }

    private void appendHint() {
        List<HologramPresets.HintArea> sourceHints = s.virtualPreset == null ? s.blueprintHintAreas
            : s.virtualPreset.hintAreas;
        if (cursor >= sourceHints.size()) {
            state.setTag("hintAreas", hints);
            selection = new int[s.selectedIndices.size()];
            selectionIterator = s.selectedIndices.iterator();
            next();
            return;
        }
        HologramPresets.HintArea hint = sourceHints.get(cursor++);
        NBTTagCompound row = new NBTTagCompound();
        row.setIntArray("bounds", new int[] { hint.x1, hint.y1, hint.z1, hint.x2, hint.y2, hint.z2 });
        row.setString("prompt", hint.prompt == null ? "" : hint.prompt);
        hints.appendTag(row);
    }

    private void appendSelection() {
        if (selectionIterator.hasNext()) selection[cursor++] = selectionIterator.next();
        else {
            state.setIntArray("selectedIndices", selection);
            next();
        }
    }

    private static Report derive(HologramPresets.Preset raw, HologramBlueprintProcessing.Policy policy,
        List<Position> frozenPositions, int selected, int dimension, int x, int y, int z, boolean reviewed,
        String receipt, WorkerProgress progress) throws IOException {
        Report result = new Report(raw, policy);
        if (raw != null) try {
            if (result.policy == null) result.policy = HologramBlueprintProcessing.policy(raw);
            progress.value = new HologramAreaPreparation.Progress("metadata.processing", 0, 1);
            NBTTagCompound processing = HologramBlueprintProcessing.state(raw, result.policy, dimension, x, y, z);
            boolean direct = processing.getInteger("requiredStages") == 0;
            result.fields.setBoolean("blueprintDirectSave", direct);
            result.fields.setBoolean("blueprintProcessingRequired", !reviewed && !direct);
            HologramPresets.Preset current = new HologramPresets.Preset();
            current.processingNbt = receipt;
            processing.setBoolean("complete", reviewed && HologramBlueprintProcessing.complete(current));
            result.fields.setTag("processing", processing);
            result.fields.setTag(
                "processingPolicy",
                processing.getCompoundTag("policy")
                    .copy());
            result.fields.setTag(
                "processingRelations",
                processing.getTagList("relations", 10)
                    .copy());
            result.fields.setTag(
                "processingNbtKeys",
                processing.getTagList("nbtKeys", 8)
                    .copy());
            result.fields.setTag(
                "processingNbtPaths",
                processing.getTagList("nbtPaths", 8)
                    .copy());
            progress.value = new HologramAreaPreparation.Progress("metadata.processing", 1, 1);
            cellNbt(result.fields, result.policy, frozenPositions, selected, progress);
        } catch (IOException invalid) {
            result.fields.setString("processingError", invalid.getMessage());
        }
        try {
            result.fields.setTag("processingPresets", HologramProcessingPresets.labels());
        } catch (IOException invalid) {
            result.fields.setString("processingPresetError", invalid.getMessage());
        }
        return result;
    }

    private static void cellNbt(NBTTagCompound output, HologramBlueprintProcessing.Policy policy,
        List<Position> frozenPositions, int selected, WorkerProgress progress) throws IOException {
        NBTTagList rows = new NBTTagList();
        int entryCount = 0;
        NBTTagList overrides = policy.write()
            .getTagList("cellNbtOverrides", 10);
        Map<String, NBTTagCompound> overridePositions = new HashMap<>();
        for (int i = 0; i < overrides.tagCount(); i++) {
            NBTTagCompound row = overrides.getCompoundTagAt(i);
            overridePositions.put(key(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")), row);
        }
        int visited = 0;
        for (Position position : frozenPositions) {
            interrupted();
            progress.value = new HologramAreaPreparation.Progress(
                "metadata.cell_nbt",
                visited++,
                frozenPositions.size());
            if ((rows.tagCount() >= 8192 || entryCount >= 65536) && position.index != selected) {
                output.setBoolean("cellNbtTruncated", true);
                continue;
            }
            HologramPresets.Cell cell = position.cell;
            NBTTagList cellEntries = HologramBlueprintProcessing.nbtEntries(cell);
            if (cellEntries.tagCount() == 0) continue;
            NBTTagCompound override = overridePositions.get(key(cell.x, cell.y, cell.z));
            List<String> chosen = override == null || override.getBoolean("all") ? null
                : HologramAuthoringQueries.selectedNbtKeys(override.getTagList("keys", 8));
            entryCount += cellEntries.tagCount();
            for (int i = 0; i < cellEntries.tagCount(); i++) {
                NBTTagCompound entry = cellEntries.getCompoundTagAt(i);
                String entryKey = entry.getString("key");
                entry.setString("label", entryKey);
                entry.setBoolean("selected", chosen == null || chosen.contains(entryKey));
                entry.setBoolean("blueprintSelected", chosen == null || chosen.contains(entryKey));
            }
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("index", position.index);
            row.setBoolean("all", chosen == null);
            row.setTag("entries", cellEntries);
            row.setTag(
                "ignoredPaths",
                override == null ? new NBTTagList()
                    : override.getTagList("ignoredPaths", 8)
                        .copy());
            rows.appendTag(row);
            if (position.index == selected) output.setTag("selectedCellNbtEntries", cellEntries.copy());
        }
        output.setTag("cellNbtEntries", rows);
        output.setTag("cellNbtOverrides", overrides.copy());
        progress.value = new HologramAreaPreparation.Progress(
            "metadata.cell_nbt",
            frozenPositions.size(),
            frozenPositions.size());
    }

    private static void interrupted() throws IOException {
        if (Thread.currentThread()
            .isInterrupted()) throw new IOException("Snapshot metadata cancelled");
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static void rootKeys(NBTTagCompound tag, Set<String> keys) {
        if (tag != null) for (Object raw : tag.func_150296_c()) keys.add((String) raw);
    }

    private static final class WorkerProgress {

        volatile HologramAreaPreparation.Progress value = new HologramAreaPreparation.Progress(
            "metadata.processing",
            0,
            1);
    }

    private static final class Position {

        final int index;
        final HologramPresets.Cell cell;

        Position(int index, HologramPresets.Cell cell) {
            this.index = index;
            this.cell = cell;
        }
    }

    private static final class Report {

        final NBTTagCompound fields = new NBTTagCompound();
        final HologramPresets.Preset source;
        HologramBlueprintProcessing.Policy policy;

        Report(HologramPresets.Preset source, HologramBlueprintProcessing.Policy policy) {
            this.source = source;
            this.policy = policy;
        }
    }
}
