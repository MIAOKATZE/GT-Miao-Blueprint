package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Re-declares only the selected saved member, using a disposable native controller and no source-world writes. */
final class HologramVirtualStructureEditing {

    private HologramVirtualStructureEditing() {}

    /** A canonical StructureLib air declaration keeps a known identity when no physical target exists. */
    static void identifyDeclaredAir(HologramCapture.Cell cell) {
        if (cell.block != Blocks.air || cell.unknown
            || cell.anchor
            || !cell.userPosition
            || cell.element != com.gtnewhorizon.structurelib.structure.StructureUtility.isAir()
            || "templateController".equals(cell.role)
            || !cell.tileNbt.isEmpty()
            || !cell.itemNbt.isEmpty()) return;
        cell.blueprintBlock = "minecraft:air";
        cell.blueprintItem = "";
        cell.meta = cell.blueprintDamage = 0;
    }

    static boolean pending(HologramSession s) {
        return s.virtualStructureTask != null;
    }

    static void cancel(HologramSession s) {
        cancel(s, s.virtualStructureTask);
    }

    static void cancel(HologramSession s, Task retired) {
        if (retired == null || s.virtualStructureTask != retired) return;
        s.virtualStructureTask = null;
        if (retired.nativeCapture != null) retired.nativeCapture.cancelPending();
        if (retired.legacyScope != null) retired.legacyScope.cancel();
        retired.rollback();
    }

    static boolean prepare(HologramSession s, int main, NBTTagCompound channels, boolean noHatches) {
        if (!s.virtual || s.virtualPreset == null || s.blueprintActiveStructure.isEmpty()) return true;
        try {
            HologramPresets.Structure source = HologramBlueprintData
                .blueprintSource(s.virtualPreset, s.blueprintActiveStructure);
            if (source == null) throw new IOException("请选择蓝图内的来源结构再调整信道");
            cancel(s);
            s.virtualStructureTask = new Task(s, source, main, channels, noHatches);
            return true;
        } catch (IOException | RuntimeException | LinkageError failure) {
            s.status = "结构参数未应用，原蓝图已保留：" + failure.getMessage();
            return false;
        }
    }

    static HologramCapabilities.Result capabilities(HologramSession s, HologramPresets.Structure source)
        throws IOException {
        MTEEnhancedMultiBlockBase<?> machine = controller(s, source);
        ItemStack trigger = HologramSessionPolicy.trigger(s);
        HologramCapture declarations = new HologramCapture(machine, trigger);
        HologramChannelTrace.Scope scope = HologramChannelTrace.begin(machine);
        HologramChannelTrace.Report report;
        try {
            com.miaokatze.gtmb.api.StructureSources.Plan supplied = com.miaokatze.gtmb.api.StructureSources
                .resolve(machine, trigger);
            IStructureDefinition<?> definition;
            if (supplied == null) {
                declarations = HologramCapture.declarationPieces((IConstructable) machine, trigger);
                definition = declarations.definition == null ? machine.getStructureDefinition()
                    : declarations.definition;
            } else {
                definition = supplied.definition;
                for (com.miaokatze.gtmb.api.StructureSources.Piece piece : supplied.pieces) declarations.pieces
                    .add(new HologramCapture.Piece(piece.name, piece.a, piece.b, piece.c, piece.trigger()));
            }
            for (HologramCapture.Piece piece : declarations.pieces) {
                IdentityHashMap<IStructureElement<?>, Boolean> seen = new IdentityHashMap<>();
                for (IStructureElement<?> element : definition.getStructureFor(piece.name)) {
                    if (element == null || element.isNavigating() || seen.put(element, Boolean.TRUE) != null) continue;
                    HologramDeclarationWrappers.observeChannels(element, piece.trigger);
                    HologramDeclarationWrappers.observeMaterials(element);
                }
            }
        } finally {
            report = HologramChannelTrace.end(scope);
        }
        return HologramCapabilities
            .describe(machine, trigger, HologramChannelTrace.merge(declarations.channelReport, report))
            .withSavedChannels(s.channels);
    }

    private static MTEEnhancedMultiBlockBase<?> controller(HologramSession s, HologramPresets.Structure source)
        throws IOException {
        String warning = HologramBlueprintData.sourceWarning(s.virtualPreset, source);
        if (!warning.isEmpty()) throw new IOException(warning);
        int front = -1;
        NBTTagCompound state = null;
        for (HologramPresets.Cell cell : s.virtualPreset.cells) if (cell.controller && cell.belongsTo(source.id)) {
            front = cell.facing;
            state = HologramBlueprintNbt.decode(cell.tileNbt);
            if (state == null || state.hasNoTags()) {
                state = HologramBlueprintNbt.decode(cell.itemNbt);
                if (state != null && state.hasKey(HologramRecovery.TAG, 10)) {
                    NBTTagCompound seal = state.getCompoundTag(HologramRecovery.TAG);
                    if (!HologramRecovery.matchesEnvelope(seal, cell.block, cell.item, cell.damage)
                        || seal.getInteger("mID") != source.controllerId) throw new IOException("保存的来源控制器封存身份不匹配");
                    state = seal.getCompoundTag("tile");
                }
            }
            state = HologramBlueprintNbt.nativePayload(state);
            if (state != null && state.hasKey("mID", 3) && state.getInteger("mID") != source.controllerId)
                throw new IOException("保存的来源控制器 NBT 身份不匹配");
            break;
        }
        ExtendedFacing facing = HologramVirtualTransform.controller(s.virtualPreset);
        if (front >= 0 && front <= 5) facing = facing.with(ForgeDirection.getOrientation(front));
        MTEEnhancedMultiBlockBase<?> machine = HologramDetachedController.create(source.controllerId, facing, state);
        if (!HologramStructureDiscovery.captureSupported(machine)) throw new IOException("来源控制器未提供可重建的原生声明");
        return machine;
    }

    static void advance(HologramSession s) {
        Task task = s.virtualStructureTask;
        if (task == null) return;
        if (!task.current()) {
            cancel(s, task);
            s.capturePending = false;
            return;
        }
        try {
            if (!task.step()) {
                s.capturePending = true;
                s.status = task.recoveringLegacy() ? "正在恢复旧结构归属，其他结构与编辑保留" : "正在更新所选结构声明，其他结构与编辑保留";
                return;
            }
            s.capturePending = false;
            task.publish();
            if (s.virtualStructureTask == task) s.virtualStructureTask = null;
            HologramDeferredPlanRefresh.request(s, new java.util.HashSet<>(s.selectedIndices));
            s.status = "所选结构的尺寸与材料信道已更新；其他结构与明确编辑保留";
            HologramSnapshots.send(s);
        } catch (RuntimeException | LinkageError | IOException failure) {
            cancel(s, task);
            s.capturePending = false;
            s.status = "结构参数未应用，原蓝图已保留：" + failure.getMessage();
            HologramSnapshots.send(s);
        }
    }

    static final class Task {

        final HologramSession s;
        final HologramCapture original;
        final HologramPresets.Preset preset;
        final HologramPresets.Structure source;
        final HologramPresets.Structure previousSource;
        final HologramCapabilities.Result previousCapabilities;
        final MTEEnhancedMultiBlockBase<?> machine;
        final ItemStack trigger;
        final long revision;
        final String session;
        final int main, previousMain;
        final boolean noHatches, previousMainExplicit, previousNoHatches;
        final String controllerTileNbt, controllerItemNbt;
        final NBTTagCompound channels, previousChannels;
        final Map<String, HologramCapture.Cell> generated = new LinkedHashMap<>();
        final Map<String, ItemStack> targets = new HashMap<>();
        final HologramLegacyStructureScope legacyScope;
        boolean legacyComplete;
        HologramCapture nativeCapture;
        int cursor;

        Task(HologramSession s, HologramPresets.Structure source, int main, NBTTagCompound channels)
            throws IOException {
            this(s, source, main, channels, s.noHatches);
        }

        Task(HologramSession s, HologramPresets.Structure source, int main, NBTTagCompound channels, boolean noHatches)
            throws IOException {
            this.s = s;
            original = s.capture;
            preset = s.virtualPreset;
            this.source = source;
            previousSource = HologramBlueprintData.copyStructure(source);
            previousCapabilities = s.capabilities;
            revision = s.planRevision;
            session = s.id;
            this.main = main;
            this.noHatches = noHatches;
            this.channels = (NBTTagCompound) channels.copy();
            previousMain = s.main;
            previousMainExplicit = s.mainExplicit;
            previousNoHatches = s.noHatches;
            previousChannels = (NBTTagCompound) s.channels.copy();
            HologramPresets.Cell savedController = null;
            for (HologramPresets.Cell cell : preset.cells) if (cell.controller && cell.belongsTo(source.id)) {
                savedController = cell;
                break;
            }
            controllerTileNbt = savedController == null || savedController.tileNbt == null ? ""
                : savedController.tileNbt;
            controllerItemNbt = savedController == null || savedController.itemNbt == null ? ""
                : savedController.itemNbt;
            machine = controller(s, source);
            trigger = HologramSessionPolicy.trigger(s)
                .copy();
            trigger.stackSize = main;
            if (!trigger.hasTagCompound()) trigger.setTagCompound(new NBTTagCompound());
            trigger.getTagCompound()
                .setTag("channels", channels.copy());
            legacyScope = HologramLegacyStructureScope.needed(
                s,
                previousSource,
                controllerTileNbt,
                controllerItemNbt,
                machine.getExtendedFacing()
                    .ordinal()) ? new HologramLegacyStructureScope(s, original, previousSource, machine, trigger)
                        : null;
        }

        boolean current() {
            return s.virtual && s.virtualPreset == preset
                && s.capture == original
                && s.planRevision == revision
                && session.equals(s.id)
                && source.id.equals(s.blueprintActiveStructure)
                && s.main == main
                && s.channels.equals(channels)
                && s.noHatches == noHatches
                && !s.cancelled
                && HologramSessionPolicy.tickValid(s);
        }

        private void rollback() {
            if (s.virtualPreset != preset || s.capture != original
                || s.planRevision != revision
                || !session.equals(s.id)
                || HologramBlueprintData.blueprintSource(preset, source.id) != source
                || source.main != main
                || source.noHatches != noHatches
                || !source.channels.equals(channelValues(channels))) return;
            source.main = previousSource.main;
            source.mainExplicit = previousSource.mainExplicit;
            source.noHatches = previousSource.noHatches;
            source.channels.clear();
            source.channels.putAll(previousSource.channels);
            if (source.id.equals(s.blueprintActiveStructure) && s.main == main
                && s.channels.equals(channels)
                && s.noHatches == noHatches) {
                s.main = previousMain;
                s.mainExplicit = previousMainExplicit;
                s.channels = (NBTTagCompound) previousChannels.copy();
                s.noHatches = previousNoHatches;
                s.capabilities = previousCapabilities;
            }
        }

        private static Map<String, Integer> channelValues(NBTTagCompound channels) {
            Map<String, Integer> values = new HashMap<>();
            for (Object key : channels.func_150296_c()) values.put((String) key, channels.getInteger((String) key));
            return values;
        }

        boolean step() throws IOException {
            if (recoveringLegacy()) {
                if (!legacyScope.step(HologramWorkBudget.deadline(HologramWorkBudget.SLICE_NANOS))) return false;
                legacyComplete = true;
                // The inference used the same disposable controller; the requested capture re-enters construct.
                return false;
            }
            if (nativeCapture == null || nativeCapture.pending) {
                nativeCapture = HologramCapture.collect(
                    machine,
                    trigger,
                    machine.getExtendedFacing(),
                    com.miaokatze.gtmb.api.StructureSources.CaptureIntent.NEW_TEMPLATE,
                    nativeCapture);
                if (nativeCapture.pending) return false;
                if (nativeCapture.incomplete) throw new IOException(nativeCapture.failure);
            }
            long deadline = HologramWorkBudget.deadline(HologramWorkBudget.SLICE_NANOS);
            for (int work = 0; cursor < nativeCapture.cells.size(); cursor++, work++) {
                if (work > 0 && (work >= 4096 || System.nanoTime() >= deadline)) return false;
                HologramCapture.Cell nativeCell = nativeCapture.cells.get(cursor);
                int x = s.x + source.x + nativeCell.x;
                int y = s.y + source.y + nativeCell.y - 128;
                int z = s.z + source.z + nativeCell.z;
                HologramCapture.Cell cell = HologramBlueprintData.blueprintCell(nativeCell, x, y, z);
                cell.anchor = false;
                cell.userPosition = true;
                cell.role = nativeCell.anchor ? "templateController" : "prefab";
                cell.sourceClass = HologramBlueprintData.sourceClass(preset, source);
                cell.sourceWarning = "";
                HologramBlueprintData.setMemberships(cell, Collections.singletonList(source.id));
                ItemStack target;
                if (nativeCell.anchor) {
                    target = machine.getStackForm(1);
                    cell.tileNbt = controllerTileNbt;
                    cell.itemNbt = controllerItemNbt;
                } else {
                    HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
                    candidates.collect(
                        nativeCell.element,
                        machine,
                        machine.getBaseMetaTileEntity()
                            .getWorld(),
                        nativeCell.x,
                        nativeCell.y,
                        nativeCell.z,
                        nativeCell.elementTrigger,
                        null,
                        0);
                    cell.structureTargets = candidates.stacks;
                    target = cell.block == null || cell.block == Blocks.air
                        || cell.block == com.gtnewhorizon.structurelib.StructureLibAPI.getBlockHint() ? null
                            : HologramBlockIdentity.materialStack(cell.block, cell.meta);
                    if (target == null && !candidates.stacks.isEmpty()) target = candidates.stacks.get(0)
                        .copy();
                }
                if (target != null) {
                    cell.blueprintItem = String
                        .valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(target.getItem()));
                    cell.blueprintDamage = target.getItemDamage();
                    cell.blueprintBlock = String.valueOf(Block.blockRegistry.getNameForObject(cell.block));
                } else identifyDeclaredAir(cell);
                targets.put(HologramValues.position(cell), target);
                generated.put(HologramValues.position(cell), cell);
            }
            return true;
        }

        boolean recoveringLegacy() {
            return legacyScope != null && !legacyComplete;
        }

        void publish() throws IOException {
            boolean sameTemplate = rereadSameTemplate();
            HologramCapture merged = new HologramCapture(
                null,
                HologramSessionPolicy.trigger(s),
                nativeCapture.captureIntent);
            merged.inheritSources(original);
            merged.inheritSources(nativeCapture);
            merged.sourceMetadata = (NBTTagCompound) nativeCapture.sourceMetadata.copy();
            if (!nativeCapture.sourceMetadata.hasNoTags())
                source.semanticNbt = HologramBlueprintNbt.encodeCanonical(nativeCapture.sourceMetadata);
            Map<String, ItemStack> previousPins = new HashMap<>();
            for (int i = 0; i < original.cells.size(); i++) {
                HologramCapture.Cell cell = original.cells.get(i);
                String key = HologramValues.position(cell);
                if (s.pins.containsKey(i)) previousPins.put(key, s.pins.get(i));
                HologramCapture.Cell replacement = generated.remove(key);
                boolean own = cell.belongsTo(source.id) || legacyScope != null && legacyScope.recovered.contains(key),
                    shared = cell.memberships()
                        .size() > 1;
                boolean payload = !cell.tileNbt.isEmpty() || !cell.itemNbt.isEmpty();
                if (sameTemplate && own && !shared && replacement != null) {
                    // Re-reading obtains fresh authority for the saved physical target; it does not choose new parts.
                    // Keep invalid old choices visible so the fresh element and whole-machine gate reject them.
                    replacement.block = cell.block;
                    replacement.meta = cell.meta;
                    replacement.blueprintBlock = cell.blueprintBlock;
                    replacement.blueprintItem = cell.blueprintItem;
                    replacement.blueprintDamage = cell.blueprintDamage;
                    replacement.tileNbt = cell.tileNbt;
                    replacement.itemNbt = cell.itemNbt;
                    replacement.prompt = cell.prompt;
                    replacement.excluded = cell.excluded;
                    replacement.unknown = cell.unknown;
                    ItemStack previous = s.pins.get(i);
                    targets.put(key, previous == null ? null : previous.copy());
                    append(merged, replacement);
                } else if (!own || shared
                    || s.virtualEdited.contains(key)
                    || payload
                        && (replacement == null || cell.block != replacement.block || cell.meta != replacement.meta)) {
                            if (own && shared && replacement == null && !s.virtualEdited.contains(key)) {
                                List<String> ids = new ArrayList<>(cell.memberships());
                                ids.remove(source.id);
                                cell = HologramBlueprintData.blueprintCell(cell, cell.x, cell.y, cell.z);
                                HologramBlueprintData.setMemberships(cell, ids);
                            }
                            if (replacement != null && !own
                                && cell.memberships()
                                    .isEmpty()
                                && !s.virtualEdited.contains(key)
                                && !payload) {
                                List<String> ids = new ArrayList<>(cell.memberships());
                                ids.add(source.id);
                                cell = HologramBlueprintData.blueprintCell(cell, cell.x, cell.y, cell.z);
                                HologramBlueprintData.setMemberships(cell, ids);
                            }
                            append(merged, cell);
                            targets.remove(key);
                        } else
                    if (replacement != null) {
                        if (cell.block == replacement.block && cell.meta == replacement.meta) {
                            replacement.tileNbt = cell.tileNbt;
                            replacement.itemNbt = cell.itemNbt;
                            replacement.prompt = cell.prompt;
                            replacement.excluded = cell.excluded;
                            ItemStack originalTarget = s.pins.get(i);
                            if (originalTarget != null && !cell.itemNbt.isEmpty())
                                targets.put(key, originalTarget.copy());
                        }
                        append(merged, replacement);
                    }
            }
            for (HologramCapture.Cell cell : generated.values()) append(merged, cell);
            HologramBlueprintLimits.require(merged.cells.size());
            // Replace only the selected member's saved rows; unrelated source rows and their NBT are retained.
            HologramPresets.Preset replacementPreset = HologramSnapshotMetadataSource.copyMetadata(preset);
            replacementPreset.palette.addAll(preset.palette);
            Map<String, HologramPresets.Cell> savedByPosition = new HashMap<>();
            for (HologramPresets.Cell saved : preset.cells)
                savedByPosition.put((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z), saved);
            for (HologramCapture.Cell cell : merged.cells) {
                String key = HologramValues.position(cell);
                HologramPresets.Cell saved = savedByPosition.get(key);
                if (targets.containsKey(key) || saved == null) {
                    ItemStack target = targets.get(key);
                    saved = target == null ? new HologramPresets.Cell()
                        : HologramPresets.cell(
                            target,
                            cell.x - s.x,
                            cell.y - s.y,
                            cell.z - s.z,
                            "templateController".equals(cell.role) ? machine.getExtendedFacing()
                                .getDirection()
                                .ordinal() : sameTemplate ? s.facingPins.getOrDefault(key, -1) : -1,
                            "templateController".equals(cell.role));
                    saved.x = cell.x - s.x;
                    saved.y = cell.y - s.y;
                    saved.z = cell.z - s.z;
                    saved.block = cell.block == null ? null
                        : String.valueOf(Block.blockRegistry.getNameForObject(cell.block));
                    saved.metadata = cell.meta;
                    saved.tileNbt = cell.tileNbt;
                    saved.itemNbt = cell.itemNbt;
                    saved.prompt = cell.prompt;
                    saved.excluded = cell.excluded;
                    if (cell.structureTargets != null)
                        saved.allowedTargets = HologramBlueprintData.savedTargets(cell.structureTargets);
                } else saved = HologramSnapshotMetadataSource.copyCell(saved);
                saved.structureId = cell.structureId;
                saved.structureIds = new ArrayList<>(cell.memberships());
                replacementPreset.cells.add(saved);
            }
            HologramPresets.Preset raw = replacementPreset;
            if (s.processingSource != null) {
                raw = HologramSnapshotMetadataSource.copyMetadata(s.processingSource);
                raw.palette.addAll(s.processingSource.palette);
                Map<String, HologramPresets.Cell> rawByPosition = new HashMap<>();
                for (HologramPresets.Cell old : s.processingSource.cells)
                    rawByPosition.put(old.x + "," + old.y + "," + old.z, old);
                for (HologramPresets.Cell saved : replacementPreset.cells) {
                    HologramPresets.Cell old = rawByPosition.get(saved.x + "," + saved.y + "," + saved.z);
                    String worldKey = (s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z);
                    HologramPresets.Cell row = old == null || targets.containsKey(worldKey) ? saved
                        : HologramSnapshotMetadataSource.copyCell(old);
                    row.structureId = saved.structureId;
                    row.structureIds = new ArrayList<>(saved.memberships());
                    raw.cells.add(row);
                }
                HologramPresets.Structure rawStructure = HologramBlueprintData.blueprintSource(raw, source.id);
                if (rawStructure != null) HologramBlueprintData.storeStructureConfiguration(s, rawStructure);
            }
            HologramBlueprintProcessing.Policy policy = s.processingPolicy == null
                ? HologramBlueprintProcessing.policy(preset)
                : s.processingPolicy;
            HologramBlueprintProcessing.invalidate(replacementPreset, "geometry", policy);
            if (raw != replacementPreset) HologramBlueprintProcessing.invalidate(raw, "geometry", policy);
            HologramCapabilities.Result capabilities = HologramCapabilities
                .describe(machine, trigger, nativeCapture.channelReport)
                .withSavedChannels(s.channels);
            s.capture = merged;
            s.pins.clear();
            s.coordinatePins.clear();
            s.selectedIndices.clear();
            for (int i = 0; i < merged.cells.size(); i++) {
                HologramCapture.Cell cell = merged.cells.get(i);
                String key = HologramValues.position(cell);
                ItemStack target = targets.containsKey(key) ? targets.get(key) : previousPins.get(key);
                if (target != null) {
                    s.pins.put(i, target.copy());
                    s.coordinatePins.put(key, target.copy());
                }
                if (cell.belongsTo(source.id)) s.selectedIndices.add(i);
            }
            s.selected = s.selectedIndices.isEmpty() ? -1
                : s.selectedIndices.iterator()
                    .next();
            s.autoTargets.clear();
            s.frozenAutoTargets.clear();
            s.allocationsFrozen = false;
            s.undoEdits.clear();
            s.redoEdits.clear();
            s.structureIndex = null;
            s.capabilities = capabilities;
            s.virtualPreset = replacementPreset;
            s.processingSource = raw;
            s.processingPolicy = policy;
            s.processingReviewed = false;
            s.blueprintSaveReceipt = "";
            HologramAuthoringWorkflow.clearSourceSwitch(s);
            s.ae.invalidateReviews();
            s.blueprintDirty = true;
            HologramLegacyStructureScope.verified(
                merged,
                HologramBlueprintData.blueprintSource(replacementPreset, source.id),
                controllerTileNbt,
                controllerItemNbt,
                machine.getExtendedFacing()
                    .ordinal());
        }

        private boolean rereadSameTemplate() throws IOException {
            if (nativeCapture.sourceResolution == null || !nativeCapture.sourceCurrent()
                || main != previousSource.main
                || noHatches != previousSource.noHatches
                || !channelValues(channels).equals(previousSource.channels)) return false;
            String encoded = previousSource.semanticNbt == null ? preset.semanticNbt : previousSource.semanticNbt;
            NBTTagCompound previous = HologramBlueprintNbt.decode(encoded);
            if (previous == null || !nativeCapture.captureIntent.name()
                .equals(previous.getString("purpose"))
                || !nativeCapture.sourceResolution.sourceId.equals(previous.getString("sourceId"))) return false;
            HologramCapture.SourceAuthority authority = null;
            for (HologramCapture.Cell cell : nativeCapture.cells) {
                if (cell.unknown || cell.sourceAuthority == null
                    || authority != null && authority != cell.sourceAuthority) return false;
                authority = cell.sourceAuthority;
            }
            int members = 0;
            for (HologramCapture.Cell cell : original.cells) {
                String key = HologramValues.position(cell);
                if (!cell.belongsTo(source.id) && (legacyScope == null || !legacyScope.recovered.contains(key)))
                    continue;
                HologramCapture.Cell fresh = generated.get(key);
                if (fresh == null || "templateController".equals(cell.role) != "templateController".equals(fresh.role))
                    return false;
                members++;
            }
            // Preserve physical choices only for the same purpose, parameters and complete freshly read geometry.
            // New element/target/schema validation still owns eligibility; an invalid old choice stays visible.
            return members == generated.size();
        }

        private static void append(HologramCapture capture, HologramCapture.Cell cell) throws IOException {
            HologramBlueprintLimits.require((long) capture.cells.size() + 1);
            capture.cells.add(cell);
            capture.positions.put(HologramValues.position(cell), cell);
        }
    }
}
