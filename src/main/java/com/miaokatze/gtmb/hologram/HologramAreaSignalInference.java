package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Per-import, resumable inference from native declarations and the actual source world. */
final class HologramAreaSignalInference {

    private final MTEEnhancedMultiBlockBase<?> machine;
    private final World actual;
    private final List<String> signals = new ArrayList<>();
    private final Map<String, Candidate> geometries = new LinkedHashMap<>();
    private final IdentityHashMap<IStructureElement<?>, List<Options>> alternatives = new IdentityHashMap<>();
    private final IdentityHashMap<IStructureDefinition<?>, Map<String, Layout>> layouts = new IdentityHashMap<>();
    private HologramCapture best, candidate;
    private ItemStack trigger;
    private int signalIndex, value = 1, candidateIndex, matchIndex, materialIndex;
    private List<Candidate> ordered;
    private boolean initialized, matching, bestKnownValid, restoredBestState, inferredMaterials, done;
    private final Map<String, ItemStack> actualMaterials = new HashMap<>();
    private int declarationQueries, rejectedQueries, fullCaptures;
    private long comparedCells, activeNanos;

    HologramAreaSignalInference(MTEEnhancedMultiBlockBase<?> source, ItemStack seed) throws IOException {
        machine = HologramDetachedController.fromLive(source);
        actual = source.getBaseMetaTileEntity()
            .getWorld();
        trigger = seed.copy();
        trigger.stackSize = 1;
        List<String> previous = new ArrayList<>();
        ChannelDataAccessor.iterateChannelData(trigger)
            .forEach(entry -> previous.add(entry.getKey()));
        for (String id : previous) ChannelDataAccessor.unsetChannelData(trigger, id);
        // The native constructable may explicitly offer a saved main signal through its own snapshot source.
        if (source instanceof tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods) trigger.stackSize = Math.max(
            1,
            Math.min(
                3,
                ((tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods) source).getData()
                    .getRingAmount()));
    }

    HologramCapture capture() {
        return best;
    }

    void cancel() {
        if (best != null) best.cancelPending();
        if (candidate != null && candidate != best) candidate.cancelPending();
        done = true;
    }

    boolean step(long deadline) throws IOException {
        long started = System.nanoTime();
        try {
            return advance(deadline);
        } finally {
            activeNanos += System.nanoTime() - started;
        }
    }

    NBTTagCompound diagnostics() {
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("declarationQueries", declarationQueries);
        result.setInteger("rejectedQueries", rejectedQueries);
        result.setInteger("fullCaptures", fullCaptures);
        result.setLong("comparedCells", comparedCells);
        result.setLong("activeNanos", activeNanos);
        result.setBoolean("done", done);
        return result;
    }

    private boolean advance(long deadline) throws IOException {
        if (done) return true;
        if (!initialized) {
            if (best == null) fullCaptures++;
            best = HologramCapture.collect(machine, trigger, machine.getExtendedFacing(), best);
            if (best.pending) return false;
            if (best.incomplete || !(machine instanceof IConstructable)
                && !com.miaokatze.gtmb.api.StructureSources.hasSource(machine.getClass())) return done = true;
            initialized = true;
            signals.add("main");
            if (best.channelReport != null) for (String id : best.channelReport.observations.keySet()) {
                List<ItemStack> material = best.channelReport.materials.get(id);
                if (material == null || material.isEmpty()) signals.add(id);
            }
        }
        while (signalIndex < signals.size() && System.nanoTime() < deadline) {
            String id = signals.get(signalIndex);
            if (ordered == null) {
                // Probe buildPiece calls only. Equal piece layouts never repeat a full structure traversal.
                for (; value <= 64 && System.nanoTime() < deadline; value++) {
                    ItemStack proposed = signal(trigger, id, value);
                    declarationQueries++;
                    HologramCapture pieces;
                    try {
                        pieces = declaration(proposed);
                    } catch (RuntimeException | LinkageError outsideNativeRange) {
                        rejectedQueries++;
                        continue;
                    }
                    try {
                        String layout = layout(pieces);
                        if (!layout.equals(layout(best)) && !geometries.containsKey(layout)) {
                            // Native controllers may rebuild their definition for a new piece selection.
                            // Freeze it only for a distinct layout, rather than for every duplicate signal.
                            if (pieces.definition == null) pieces.definition = machine.getStructureDefinition();
                            long size = weight(pieces);
                            if (size <= HologramCapture.LIMIT)
                                geometries.put(layout, new Candidate(proposed, pieces, size));
                        }
                    } catch (RuntimeException | LinkageError outsideNativeRange) {
                        rejectedQueries++;
                    }
                }
                if (value <= 64) return false;
                ordered = new ArrayList<>(geometries.values());
                ordered.add(new Candidate(trigger, best, weight(best), true));
                ordered.sort(
                    Comparator.comparingLong((Candidate entry) -> entry.weight)
                        .reversed());
            }
            if (candidateIndex >= ordered.size()) {
                nextSignal();
                continue;
            }
            if (!matching) {
                Candidate next = ordered.get(candidateIndex);
                if (next.baseline && bestKnownValid) {
                    // Smaller valid subsets must never replace a larger, already verified source footprint.
                    nextSignal();
                    continue;
                }
                if (candidate == null) {
                    // A preceding probe can mutate this isolated controller's temporary tier/size fields.
                    // Re-enter the chosen native declaration before its callbacks and freeze its own graph.
                    declarationQueries++;
                    HologramCapture restored = declaration(next.trigger);
                    candidate = next.baseline ? best : restored;
                    if (candidate.definition == null) candidate.definition = machine.getStructureDefinition();
                    alternatives.clear();
                    if (!next.baseline) fullCaptures++;
                }
                if (!next.baseline)
                    candidate = HologramCapture.collect(machine, next.trigger, machine.getExtendedFacing(), candidate);
                if (candidate.pending) return false;
                if (candidate.incomplete) {
                    nextCandidate();
                    continue;
                }
                matching = true;
            }
            while (matchIndex < candidate.cells.size() && System.nanoTime() < deadline) {
                if (!matches(candidate.cells.get(matchIndex++))) {
                    nextCandidate();
                    break;
                }
            }
            if (matching && matchIndex == candidate.cells.size()) {
                best = candidate;
                bestKnownValid = true;
                trigger = best.trigger.copy();
                nextSignal();
            }
        }
        if (signalIndex < signals.size()) return false;
        if (!restoredBestState) {
            declarationQueries++;
            HologramCapture restored = declaration(trigger);
            best.channelReport = HologramChannelTrace.merge(best.channelReport, restored.channelReport);
            alternatives.clear();
            layouts.clear();
            restoredBestState = true;
        }
        // Infer material signals once, after geometry. This reuses the exact factory candidate sets per element.
        while (materialIndex < best.cells.size() && System.nanoTime() < deadline) {
            HologramCapture.Cell cell = best.cells.get(materialIndex++);
            if (cell.anchor || !actual.blockExists(cell.x, cell.y, cell.z)) continue;
            cell.candidates = options(cell);
            ItemStack observed = HologramArea.serverSample(
                actual.getBlock(cell.x, cell.y, cell.z),
                actual.getBlockMetadata(cell.x, cell.y, cell.z),
                actual.getTileEntity(cell.x, cell.y, cell.z));
            if (observed != null) actualMaterials.put(HologramValues.position(cell), observed);
        }
        if (materialIndex < best.cells.size()) return false;
        if (!inferredMaterials) {
            inferredMaterials = true;
            NBTTagCompound channels = new NBTTagCompound();
            ChannelDataAccessor.iterateChannelData(trigger)
                .forEach(entry -> channels.setInteger(entry.getKey(), entry.getValue()));
            HologramControllerCapture.calibrateMaterialChannels(
                best,
                HologramCapabilities.describe(machine, trigger, best.channelReport)
                    .toNBT(),
                channels,
                new HashMap<>(),
                actualMaterials);
            ItemStack calibrated = trigger.copy();
            for (Object raw : channels.func_150296_c())
                ChannelDataAccessor.setChannelData(calibrated, (String) raw, channels.getInteger((String) raw));
            if (!ItemStack.areItemStackTagsEqual(trigger, calibrated)) {
                trigger = calibrated;
                candidate = null;
                fullCaptures++;
            } else return done = true;
        }
        candidate = HologramCapture.collect(machine, trigger, machine.getExtendedFacing(), candidate);
        if (candidate.pending) return false;
        if (!candidate.incomplete) best = candidate;
        return done = true;
    }

    private void nextCandidate() {
        candidateIndex++;
        matchIndex = 0;
        candidate = null;
        matching = false;
    }

    private void nextSignal() {
        signalIndex++;
        value = 1;
        ordered = null;
        geometries.clear();
        candidateIndex = 0;
        matchIndex = 0;
        candidate = null;
        matching = false;
    }

    private boolean matches(HologramCapture.Cell cell) {
        comparedCells++;
        if (cell.anchor) return true;
        if (!actual.blockExists(cell.x, cell.y, cell.z)) return false;
        Block block = actual.getBlock(cell.x, cell.y, cell.z);
        int meta = actual.getBlockMetadata(cell.x, cell.y, cell.z);
        if (cell.block == Blocks.air && !cell.unknown) return actual.isAirBlock(cell.x, cell.y, cell.z);
        if (HologramCellPolicy.knownSatisfied(cell, block, meta) || HologramCellPolicy.destructive(cell, block, meta)
            || HologramFrameSupport.acceptsCovered(cell.element, block, meta)) return true;
        TileEntity tile = actual.getTileEntity(cell.x, cell.y, cell.z);
        if (tile instanceof IGregTechTileEntity) {
            for (IStructureElement<?> part : HologramElementCatalog.parts(cell.element))
                for (gregtech.api.interfaces.IHatchElement<?> type : HologramHatchPolicy.declared(part))
                    if (HologramHatchPolicy.matchesHatch(type, ((IGregTechTileEntity) tile).getMetaTileEntity()))
                        return true;
        }
        ItemStack observed = HologramArea.serverSample(block, meta, tile);
        for (ItemStack option : options(cell)) if (HologramValues.sameMaterial(option, observed)) return true;
        return false;
    }

    private List<ItemStack> options(HologramCapture.Cell cell) {
        ItemStack signal = cell.elementTrigger == null ? trigger : cell.elementTrigger;
        List<Options> cached = alternatives.computeIfAbsent(cell.element, ignored -> new ArrayList<>());
        for (Options option : cached) if (option.trigger == signal
            || option.trigger.stackSize == signal.stackSize && HologramValues.same(option.trigger, signal))
            return option.items;
        HologramDeclarationCandidates declared = new HologramDeclarationCandidates();
        declared.collect(cell.element, machine, actual, cell.x, cell.y, cell.z, signal, null, 0);
        cached.add(new Options(signal, declared.stacks));
        return declared.stacks;
    }

    static ItemStack signal(ItemStack original, String id, int value) {
        ItemStack result = original.copy();
        if ("main".equals(id)) result.stackSize = value;
        else ChannelDataAccessor.setChannelData(result, id, value);
        return result;
    }

    static String geometry(HologramCapture capture) {
        StringBuilder result = new StringBuilder();
        for (HologramCapture.Piece piece : capture.pieces) result.append(piece.name)
            .append(':')
            .append(piece.a)
            .append(',')
            .append(piece.b)
            .append(',')
            .append(piece.c)
            .append(';');
        return result.toString();
    }

    private long weight(HologramCapture pieces) {
        long size = 0;
        for (HologramCapture.Piece piece : pieces.pieces)
            size += (pieces.definition == null ? best.definition : pieces.definition)
                .getStructureFor(piece.name).length;
        return size;
    }

    private String layout(HologramCapture capture) {
        StringBuilder result = new StringBuilder(geometry(capture));
        Map<String, Layout> definitions = layouts.computeIfAbsent(capture.definition, ignored -> new HashMap<>());
        for (HologramCapture.Piece piece : capture.pieces) {
            IStructureElement<?>[] elements = capture.definition.getStructureFor(piece.name);
            Layout cached = definitions.get(piece.name);
            boolean nativeDefinition = capture.definition.getClass()
                == com.gtnewhorizon.structurelib.structure.StructureDefinition.class;
            String navigation = nativeDefinition && cached != null
                && cached.elements == elements
                && cached.length == elements.length ? cached.signature : null;
            if (navigation == null) {
                StringBuilder shape = new StringBuilder();
                int cells = 0;
                for (IStructureElement<?> element : elements) {
                    if (!element.isNavigating()) cells++;
                    else {
                        shape.append(cells)
                            .append('n')
                            .append(element.resetA())
                            .append(',')
                            .append(element.resetB())
                            .append(',')
                            .append(element.resetC())
                            .append(',')
                            .append(element.getStepA())
                            .append(',')
                            .append(element.getStepB())
                            .append(',')
                            .append(element.getStepC())
                            .append(';');
                        cells = 0;
                    }
                }
                navigation = shape.append(cells)
                    .toString();
                if (nativeDefinition) definitions.put(piece.name, new Layout(elements, navigation));
            }
            result.append('|')
                .append(navigation);
        }
        return result.toString();
    }

    private HologramCapture declaration(ItemStack proposed) {
        HologramChannelTrace.Scope scope = HologramChannelTrace.begin(machine);
        HologramCapture result = null;
        try {
            com.miaokatze.gtmb.api.StructureSources.Plan supplied = com.miaokatze.gtmb.api.StructureSources
                .resolve(machine, proposed);
            if (supplied == null) {
                result = HologramCapture.declarationPieces((IConstructable) machine, proposed);
                result.definition = machine.getStructureDefinition();
                return result;
            }
            result = new HologramCapture(machine, proposed);
            result.definition = supplied.definition;
            for (com.miaokatze.gtmb.api.StructureSources.Piece piece : supplied.pieces)
                result.pieces.add(new HologramCapture.Piece(piece.name, piece.a, piece.b, piece.c, piece.trigger()));
            return result;
        } finally {
            HologramChannelTrace.Report report = HologramChannelTrace.end(scope);
            if (result != null) result.channelReport = HologramChannelTrace.merge(result.channelReport, report);
        }
    }

    private static final class Candidate {

        final ItemStack trigger;
        final HologramCapture pieces;
        final long weight;
        final boolean baseline;

        Candidate(ItemStack trigger, HologramCapture pieces, long weight) {
            this(trigger, pieces, weight, false);
        }

        Candidate(ItemStack trigger, HologramCapture pieces, long weight, boolean baseline) {
            this.trigger = trigger;
            this.pieces = pieces;
            this.weight = weight;
            this.baseline = baseline;
        }
    }

    private static final class Options {

        final ItemStack trigger;
        final List<ItemStack> items;

        Options(ItemStack trigger, List<ItemStack> items) {
            this.trigger = trigger;
            this.items = items;
        }
    }

    private static final class Layout {

        final IStructureElement<?>[] elements;
        final int length;
        final String signature;

        Layout(IStructureElement<?>[] elements, String signature) {
            this.elements = elements;
            length = elements.length;
            this.signature = signature;
        }
    }
}
