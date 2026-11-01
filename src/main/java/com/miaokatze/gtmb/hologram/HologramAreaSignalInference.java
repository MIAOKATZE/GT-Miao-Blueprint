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

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Per-import, resumable inference from native declarations and the actual source world. */
final class HologramAreaSignalInference {

    interface Scene {

        boolean exists(int x, int y, int z);

        Block block(int x, int y, int z);

        int metadata(int x, int y, int z);

        boolean air(int x, int y, int z);

        ItemStack material(int x, int y, int z);

        IMetaTileEntity machine(int x, int y, int z);
    }

    private final MTEEnhancedMultiBlockBase<?> machine;
    private final World actual;
    private final Scene scene;
    private final boolean strict;
    private final boolean reviewedSource;
    private List<ItemStack> reviewedTriggers;
    private int reviewedIndex;
    private final Map<String, SceneCell> reviewedCells = new LinkedHashMap<>();
    private final List<String> signals = new ArrayList<>();
    private final Map<String, Candidate> geometries = new LinkedHashMap<>();
    private final IdentityHashMap<IStructureElement<?>, List<Options>> alternatives = new IdentityHashMap<>();
    private final IdentityHashMap<IStructureDefinition<?>, Map<String, Layout>> layouts = new IdentityHashMap<>();
    private HologramCapture best, candidate;
    private ItemStack trigger;
    private int signalIndex, value = 1, candidateIndex, matchIndex, materialIndex;
    private List<Candidate> ordered;
    private boolean initialized, matching, bestKnownValid, restoredBestState, inferredMaterials, done;
    private boolean ambiguous;
    private HologramCapture signalWinner;
    private long winnerWeight;
    private final Map<String, ItemStack> actualMaterials = new HashMap<>();
    private int declarationQueries, rejectedQueries, fullCaptures;
    private long comparedCells, activeNanos;

    HologramAreaSignalInference(MTEEnhancedMultiBlockBase<?> source, ItemStack seed) throws IOException {
        this(
            source,
            seed,
            new WorldScene(
                source.getBaseMetaTileEntity()
                    .getWorld()),
            false,
            true);
    }

    static HologramAreaSignalInference detachedScene(MTEEnhancedMultiBlockBase<?> owned, ItemStack seed, Scene scene)
        throws IOException {
        if (!(owned.getBaseMetaTileEntity()
            .getWorld() instanceof HologramResourceWorld)) throw new IOException("旧结构声明必须使用独立只读控制器");
        return new HologramAreaSignalInference(owned, seed, scene, true, false);
    }

    private HologramAreaSignalInference(MTEEnhancedMultiBlockBase<?> source, ItemStack seed, Scene scene,
        boolean strict, boolean copySource) throws IOException {
        reviewedSource = HologramGTNLSemantics.supports(source);
        machine = copySource && !reviewedSource ? HologramDetachedController.fromLive(source) : source;
        this.scene = scene;
        this.strict = strict;
        actual = source.getBaseMetaTileEntity()
            .getWorld();
        trigger = seed.copy();
        if (reviewedSource) return;
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

    boolean verifiedScope() {
        return done && bestKnownValid && !ambiguous && best != null && !best.incomplete;
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
        result.setBoolean("verifiedScope", verifiedScope());
        result.setBoolean("ambiguousScope", ambiguous);
        return result;
    }

    private boolean advance(long deadline) throws IOException {
        if (done) return true;
        if (reviewedSource) return advanceReviewed(deadline);
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
                if (strict && signalWinner != null && next.weight < winnerWeight) {
                    nextSignal();
                    continue;
                }
                if (next.baseline && bestKnownValid) {
                    // Smaller valid subsets must never replace a larger, already verified source footprint.
                    if (strict) {
                        candidate = best;
                        acceptStrict(next);
                    } else nextSignal();
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
                if (strict) acceptStrict(ordered.get(candidateIndex));
                else {
                    best = candidate;
                    bestKnownValid = true;
                    trigger = best.trigger.copy();
                    nextSignal();
                }
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
        if (strict) return done = true;
        // Infer material signals once, after geometry. This reuses the exact factory candidate sets per element.
        while (materialIndex < best.cells.size() && System.nanoTime() < deadline) {
            HologramCapture.Cell cell = best.cells.get(materialIndex++);
            if (cell.anchor || !scene.exists(cell.x, cell.y, cell.z)) continue;
            cell.candidates = options(cell);
            ItemStack observed = scene.material(cell.x, cell.y, cell.z);
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

    /** A finite, source-reviewed parameter domain; never probes a native constructor. */
    private boolean advanceReviewed(long deadline) {
        if (reviewedTriggers == null) reviewedTriggers = HologramGTNLSemantics.inferenceTriggers(machine, trigger);
        while (reviewedIndex < reviewedTriggers.size() && System.nanoTime() < deadline) {
            if (candidate == null || candidate.pending) {
                if (candidate == null) {
                    fullCaptures++;
                    reviewedCells.clear();
                }
                candidate = HologramCapture.collect(
                    machine,
                    reviewedTriggers.get(reviewedIndex),
                    machine.getExtendedFacing(),
                    com.miaokatze.gtmb.api.StructureSources.CaptureIntent.LIVE_SNAPSHOT,
                    candidate);
                if (candidate.pending) return false;
                if (best == null) best = candidate;
                matching = !candidate.incomplete;
                matchIndex = 0;
            }
            while (matching && matchIndex < candidate.cells.size() && System.nanoTime() < deadline)
                matching = matches(candidate.cells.get(matchIndex++));
            if (matching && matchIndex < candidate.cells.size()) return false;
            if (matching && candidate.sourceCurrent()) {
                List<SceneCell> observed = new ArrayList<>(reviewedCells.values());
                candidate.observe(() -> {
                    for (SceneCell cell : observed) if (!cell.current(actual)) return "GTNL_SCENE_CHANGED";
                    return "";
                });
                if (!candidate.sourceCurrent()) {
                    best = candidate;
                    return done = true;
                }
                if (bestKnownValid && best != candidate) ambiguous = true;
                else {
                    best = candidate;
                    bestKnownValid = true;
                    trigger = candidate.trigger.copy();
                }
            }
            candidate = null;
            reviewedIndex++;
        }
        if (reviewedIndex < reviewedTriggers.size()) return false;
        if (best != null && (!bestKnownValid || ambiguous)) {
            best.incomplete = true;
            best.failure = ambiguous ? "GTNL_AMBIGUOUS_PARAMETERS" : "GTNL_SCENE_NOT_VERIFIED";
        }
        return done = true;
    }

    private void nextCandidate() {
        candidateIndex++;
        matchIndex = 0;
        candidate = null;
        matching = false;
    }

    private void nextSignal() {
        if (strict && signalWinner != null) {
            best = signalWinner;
            bestKnownValid = true;
            trigger = best.trigger.copy();
        }
        signalWinner = null;
        winnerWeight = Long.MIN_VALUE;
        signalIndex++;
        value = 1;
        ordered = null;
        geometries.clear();
        candidateIndex = 0;
        matchIndex = 0;
        candidate = null;
        matching = false;
    }

    private void acceptStrict(Candidate accepted) {
        if (signalWinner == null) {
            signalWinner = candidate;
            winnerWeight = accepted.weight;
        } else if (accepted.weight == winnerWeight && !signalWinner.positions.keySet()
            .equals(candidate.positions.keySet())) ambiguous = true;
        nextCandidate();
    }

    private boolean matches(HologramCapture.Cell cell) {
        comparedCells++;
        if (cell.anchor) return true;
        if (!scene.exists(cell.x, cell.y, cell.z)) return false;
        if (reviewedSource && scene instanceof WorldScene)
            reviewedCells.putIfAbsent(HologramValues.position(cell), new SceneCell(actual, cell.x, cell.y, cell.z));
        if (reviewedSource && cell.element instanceof HologramGTNLElement && scene instanceof WorldScene)
            return ((HologramGTNLElement) cell.element).matchesWorld(actual, cell.x, cell.y, cell.z);
        Block block = scene.block(cell.x, cell.y, cell.z);
        int meta = scene.metadata(cell.x, cell.y, cell.z);
        if (cell.block == Blocks.air && !cell.unknown) return scene.air(cell.x, cell.y, cell.z);
        if (HologramCellPolicy.knownSatisfied(cell, block, meta) || HologramCellPolicy.destructive(cell, block, meta)
            || HologramFrameSupport.acceptsCovered(cell.element, block, meta)) return true;
        IMetaTileEntity machine = scene.machine(cell.x, cell.y, cell.z);
        if (machine != null) {
            for (IStructureElement<?> part : HologramElementCatalog.parts(cell.element))
                for (gregtech.api.interfaces.IHatchElement<?> type : HologramHatchPolicy.declared(part))
                    if (HologramHatchPolicy.matchesHatch(type, machine)) return true;
        }
        ItemStack observed = scene.material(cell.x, cell.y, cell.z);
        for (ItemStack option : options(cell)) if (HologramValues.sameMaterial(option, observed)) return true;
        return false;
    }

    /** Only fields used by this interpretation are observed; arbitrary TE state is not an atomic snapshot. */
    private static final class SceneCell {

        final int x, y, z, metadata, machineId, facing, materialId;
        final Block block;
        final TileEntity tile;

        SceneCell(World world, int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            block = world.getBlock(x, y, z);
            metadata = world.getBlockMetadata(x, y, z);
            tile = world.getTileEntity(x, y, z);
            machineId = tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getMetaTileID() : -1;
            facing = tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getFrontFacing()
                .ordinal() : -1;
            materialId = tile instanceof bartworks.system.material.TileEntityMetaGeneratedBlock
                ? ((bartworks.system.material.TileEntityMetaGeneratedBlock) tile).mMetaData
                : -1;
        }

        boolean current(World world) {
            return world.blockExists(x, y, z) && world.getBlock(x, y, z) == block
                && world.getBlockMetadata(x, y, z) == metadata
                && world.getTileEntity(x, y, z) == tile
                && (!(tile instanceof IGregTechTileEntity) || ((IGregTechTileEntity) tile).getMetaTileID() == machineId
                    && ((IGregTechTileEntity) tile).getFrontFacing()
                        .ordinal() == facing)
                && (!(tile instanceof bartworks.system.material.TileEntityMetaGeneratedBlock)
                    || ((bartworks.system.material.TileEntityMetaGeneratedBlock) tile).mMetaData == materialId);
        }
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
            com.miaokatze.gtmb.api.StructureSources.Resolution resolution = com.miaokatze.gtmb.api.StructureSources
                .resolve(machine, proposed, com.miaokatze.gtmb.api.StructureSources.CaptureIntent.NEW_TEMPLATE);
            if (resolution.status == com.miaokatze.gtmb.api.StructureSources.Status.UNSUPPORTED)
                throw new IllegalStateException(resolution.failureCode);
            com.miaokatze.gtmb.api.StructureSources.Plan supplied = resolution.plan;
            if (supplied == null) {
                if (HologramGTNLSemantics.isGTNLController(machine))
                    throw new IllegalStateException(HologramGTNLSemantics.unsupportedReason(machine));
                result = HologramCapture.declarationPieces((IConstructable) machine, proposed);
                result.definition = machine.getStructureDefinition();
                result.bindPreparedSource(machine, machine.getExtendedFacing(), resolution);
                return result;
            }
            result = new HologramCapture(machine, proposed);
            result.definition = supplied.definition;
            for (com.miaokatze.gtmb.api.StructureSources.Piece piece : supplied.pieces)
                result.pieces.add(new HologramCapture.Piece(piece.name, piece.a, piece.b, piece.c, piece.trigger()));
            result.bindPreparedSource(machine, machine.getExtendedFacing(), resolution);
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

    private static final class WorldScene implements Scene {

        final World world;

        WorldScene(World world) {
            this.world = world;
        }

        public boolean exists(int x, int y, int z) {
            return world.blockExists(x, y, z);
        }

        public Block block(int x, int y, int z) {
            return world.getBlock(x, y, z);
        }

        public int metadata(int x, int y, int z) {
            return world.getBlockMetadata(x, y, z);
        }

        public boolean air(int x, int y, int z) {
            return world.isAirBlock(x, y, z);
        }

        public ItemStack material(int x, int y, int z) {
            return HologramArea.serverSample(block(x, y, z), metadata(x, y, z), world.getTileEntity(x, y, z));
        }

        public IMetaTileEntity machine(int x, int y, int z) {
            TileEntity tile = world.getTileEntity(x, y, z);
            return tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getMetaTileEntity() : null;
        }
    }
}
