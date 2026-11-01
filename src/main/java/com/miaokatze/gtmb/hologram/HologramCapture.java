package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods;

/** Armed only during a server-thread preview. Hints describe appearance, never permission to write. */
public final class HologramCapture {

    /** Recorded cells; unrecorded space inside the bounds does not consume this budget. */
    public static final int LIMIT = HologramBlueprintLimits.MAX_CELLS;
    private static final int CELLS_PER_BATCH = 4096;
    /** Wall-clock threshold at checkpoints; native machine callbacks cannot be interrupted mid-call. */
    private static final long CAPTURE_TIME_NS = 100000000L;
    private static final Logger LOG = LogManager.getLogger("gtmb");
    private static final ThreadLocal<HologramCapture> ACTIVE = new ThreadLocal<>();
    final Object context;
    final ItemStack trigger;
    final StructureSources.CaptureIntent captureIntent;
    StructureSources.Resolution sourceResolution;
    net.minecraft.nbt.NBTTagCompound sourceMetadata = new net.minecraft.nbt.NBTTagCompound();
    private ExtendedFacing sourceFacing;
    private SourceBinding sourceBinding;
    private final List<SourceBinding> inheritedSources = new ArrayList<>();
    private final List<StructureSources.ReadSet> observations = new ArrayList<>();
    private String sourceRejection = "";
    final List<Piece> pieces = new ArrayList<>();
    final List<Cell> cells = new ArrayList<>();
    final Map<String, Cell> positions = new HashMap<>();
    final IdentityHashMap<IStructureElement<?>, Map<Pair<Integer, ExactKey>, HologramCasingFallback.Target>> casingCache = new IdentityHashMap<>();
    final IdentityHashMap<IStructureElement<?>, HologramReplacementFamily.Family> familyCache = new IdentityHashMap<>();
    final IdentityHashMap<IStructureElement<?>, String> roleCache = new IdentityHashMap<>();
    final IdentityHashMap<IStructureElement<?>, Set<Pair<Integer, ExactKey>>> declarationTrace = new IdentityHashMap<>();
    /** One declaration identity graph per capture, retained across server tick continuations. */
    IStructureDefinition<?> definition;
    private long interpretationGeneration = HologramDeclarationAdapters.generation();
    net.minecraft.nbt.NBTTagCompound extensionReports = new net.minecraft.nbt.NBTTagCompound();
    boolean incomplete;
    String failure = "";
    String failureOrigin = "";
    final long started = System.nanoTime();
    long deadline = started + CAPTURE_TIME_NS;
    boolean pending;
    int pieceCursor;
    private int declaredAnchorIndex = -1;
    HologramStructureCursor structureCursor;
    boolean walkingComplete;
    HologramExtensionSnapshots.CaptureTask extensionTask;
    Cell current;
    HologramChannelTrace.Report channelReport;
    /** Counts actual GTMB invocation sites, not unrelated calls elsewhere in the server. */
    int nativeConstructCalls, nativeDefinitionCalls, nativeHintCalls;

    HologramCapture(Object context, ItemStack trigger) {
        this(context, trigger, StructureSources.CaptureIntent.NEW_TEMPLATE);
    }

    HologramCapture(Object context, ItemStack trigger, StructureSources.CaptureIntent intent) {
        this.context = context;
        this.trigger = trigger == null ? null : trigger.copy();
        this.captureIntent = intent;
    }

    /** Test/diagnostic declaration capture on a detached native controller; no structure/world traversal. */
    static HologramCapture declarationPieces(IConstructable machine, ItemStack trigger) {
        HologramCapture capture = new HologramCapture(machine, trigger.copy());
        HologramCapture previous = ACTIVE.get();
        HologramChannelTrace.Scope channels = HologramChannelTrace.begin(machine);
        ACTIVE.set(capture);
        try {
            capture.nativeConstructCalls++;
            machine.construct(capture.trigger.copy(), true);
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            capture.channelReport = HologramChannelTrace.end(channels);
        }
        return capture;
    }

    public static boolean piece(Object controller, String name, ItemStack trigger, boolean hints, int a, int b, int c) {
        HologramCapture capture = ACTIVE.get();
        if (capture == null || capture.context != controller || !hints) return false;
        if (System.nanoTime() > capture.deadline) {
            capture.fail("TIME_LIMIT");
            throw new CaptureLimitException();
        }
        capture.pieces.add(new Piece(name, a, b, c, trigger == null ? capture.trigger.copy() : trigger.copy()));
        return true;
    }

    public static boolean hint(World world, int x, int y, int z, Block block, int meta) {
        HologramCapture capture = ACTIVE.get();
        if (capture == null) return false;
        Cell cell = capture.current;
        if (cell != null && cell.x == x && cell.y == y && cell.z == z && block != null) {
            cell.block = block;
            cell.meta = meta;
            if (block == StructureLibAPI.getBlockHint()) cell.hintIndex = meta;
        }
        return true;
    }

    public static boolean iconHint(World world, int x, int y, int z, IIcon[] icons, short[] tint) {
        HologramCapture capture = ACTIVE.get();
        if (capture == null) return false;
        Cell cell = capture.current;
        if (cell != null && cell.x == x && cell.y == y && cell.z == z) {
            cell.iconOnly = true;
            cell.renderKind = "icons";
            cell.hintIcons = new String[6];
            // Texture names are descriptors only. Never retain atlas objects or serialize IIcon instances.
            if (icons != null) for (int side = 0; side < Math.min(6, icons.length); side++) {
                if (icons[side] != null) cell.hintIcons[side] = icons[side].getIconName();
            }
            cell.hintTint = tint == null ? null : tint.clone();
        }
        return true;
    }

    public static boolean iconHint() {
        return ACTIVE.get() != null;
    }

    public static void hintTint(int x, int y, int z, short[] tint) {
        HologramCapture capture = ACTIVE.get();
        Cell cell = capture == null ? null : capture.current;
        if (cell != null && cell.x == x && cell.y == y && cell.z == z)
            cell.hintTint = tint == null ? null : tint.clone();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    static HologramCapture collect(MTEEnhancedMultiBlockBase machine, ItemStack trigger, ExtendedFacing facing) {
        return collect(machine, trigger, facing, StructureSources.CaptureIntent.NEW_TEMPLATE, null);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    static HologramCapture collect(MTEEnhancedMultiBlockBase<?> machine, ItemStack trigger, ExtendedFacing facing,
        HologramCapture resumed) {
        return collect(machine, trigger, facing, StructureSources.CaptureIntent.NEW_TEMPLATE, resumed);
    }

    static HologramCapture collect(MTEEnhancedMultiBlockBase<?> machine, ItemStack trigger, ExtendedFacing facing,
        StructureSources.CaptureIntent intent) {
        return collect(machine, trigger, facing, intent, null);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    static HologramCapture collect(MTEEnhancedMultiBlockBase<?> machine, ItemStack trigger, ExtendedFacing facing,
        StructureSources.CaptureIntent intent, HologramCapture resumed) {
        HologramCapture capture = resumed == null ? new HologramCapture(machine, trigger, intent) : resumed;
        capture.deadline = System.nanoTime() + CAPTURE_TIME_NS;
        capture.pending = false;
        if (resumed != null && !capture.resumeCurrent(machine, trigger, facing, intent)) return capture;
        if (capture.walkingComplete) {
            capture.deadline = HologramWorkBudget.captureDeadline(capture.deadline);
            capture.finishExtensions();
            capture.sourceCurrent();
            return capture;
        }
        HologramCapture previous = ACTIVE.get();
        HologramChannelTrace.Scope scope = HologramChannelTrace.begin(machine);
        ACTIVE.set(capture);
        try {
            if (resumed == null) {
                try {
                    capture.sourceResolution = StructureSources.resolve(machine, trigger, intent);
                } catch (RuntimeException | LinkageError failure) {
                    capture.noteFailure(failure);
                    capture.invalidateSource("SOURCE_DESCRIPTION_FAILED");
                    return capture;
                }
                if (capture.sourceResolution.status == StructureSources.Status.UNSUPPORTED) {
                    capture.invalidateSource(capture.sourceResolution.failureCode);
                    return capture;
                }
                if (capture.sourceResolution.status == StructureSources.Status.ABSENT
                    && HologramGTNLSemantics.isGTNLController(machine)) {
                    capture.invalidateSource(HologramGTNLSemantics.unsupportedReason(machine));
                    return capture;
                }
                StructureSources.Plan supplied = capture.sourceResolution.plan;
                if (capture.sourceResolution.status == StructureSources.Status.READY) {
                    capture.definition = supplied.definition;
                    capture.sourceMetadata = supplied.metadata();
                    for (StructureSources.Piece piece : supplied.pieces)
                        capture.pieces.add(new Piece(piece.name, piece.a, piece.b, piece.c, piece.trigger()));
                } else if (machine instanceof MTEForgeOfGods && ((MTEForgeOfGods) machine).getData()
                    .isRenderActive()) {
                        // The native hint constructor always shows the dormant main ring. The live machine
                        // checks shaft + air rings after converting its rings into the star renderer.
                        int rings = ((MTEForgeOfGods) machine).getData()
                            .getRingAmount();
                        capture.pieces.add(new Piece("beam_shaft", 63, 14, 1, trigger.copy()));
                        capture.pieces.add(new Piece("first_ring_air", 63, 14, -59, trigger.copy()));
                        if (trigger.stackSize > 1) capture.pieces.add(
                            new Piece(rings >= 2 ? "second_ring_air" : "second_ring", 55, 11, -67, trigger.copy()));
                        if (trigger.stackSize > 2) capture.pieces
                            .add(new Piece(rings >= 3 ? "third_ring_air" : "third_ring", 47, 13, -76, trigger.copy()));
                    } else {
                        capture.nativeConstructCalls++;
                        ((IConstructable) machine).construct(capture.trigger.copy(), true);
                    }
            }
            // Some community controllers rebuild every element on every call. Rebuilding here on
            // continuation would conflict with cells already collected from the previous identity graph.
            if (capture.definition == null) {
                capture.nativeDefinitionCalls++;
                capture.definition = machine.getStructureDefinition();
            }
            if (resumed == null) capture.bindSource(machine, facing);
            if (!capture.sourceCurrent()) return capture;
            IStructureDefinition definition = capture.definition;
            capture.refreshInterpretationGeneration();
            final World world = machine.getBaseMetaTileEntity()
                .getWorld();
            int x = machine.getBaseMetaTileEntity()
                .getXCoord(),
                y = machine.getBaseMetaTileEntity()
                    .getYCoord(),
                z = machine.getBaseMetaTileEntity()
                    .getZCoord();
            // Native definition discovery is atomic; resumable element walking shares the server tick budget.
            capture.deadline = HologramWorkBudget.captureDeadline(capture.deadline);
            walkPieces(capture, definition, world, machine.getExtendedFacing(), x, y, z);
            if (!capture.sourceCurrent()) return capture;
        } catch (RuntimeException | LinkageError e) {
            capture.noteFailure(e);
            capture.incomplete = true;
            if (capture.failure.isEmpty()) capture.failure = e.getClass()
                .getSimpleName();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            HologramChannelTrace.Report report = HologramChannelTrace.end(scope);
            capture.channelReport = HologramChannelTrace.merge(capture.channelReport, report);
        }
        if (capture.pending) return capture;
        capture.addAnchor(machine);
        capture.walkingComplete = true;
        if (capture.pieces.isEmpty()) capture.fail("NO_BUILD_PIECES");
        capture.finishExtensions();
        if (!capture.sourceCurrent()) return capture;
        if (capture.pending) return capture;
        if (capture.incomplete) {
            capture.failure += " elapsedMs=" + ((System.nanoTime() - capture.started) / 1000000L)
                + " pieces="
                + capture.pieces.size()
                + " cells="
                + capture.cells.size()
                + " casingFactories="
                + capture.casingCache.size();
            LOG.warn(
                "Hologram preview incomplete: {} ({})",
                machine.getClass()
                    .getName(),
                capture.failure);
        }
        return capture;
    }

    private void bindSource(MTEEnhancedMultiBlockBase<?> machine, ExtendedFacing facing) {
        sourceFacing = facing;
        sourceBinding = new SourceBinding(machine, sourceResolution.validityProof(), facing);
        sourceMetadata.setString("sourceId", sourceResolution.sourceId);
        sourceMetadata.setLong("sourceRevision", sourceResolution.sourceRevision);
        sourceMetadata.setLong("declarationGeneration", sourceBinding.declarationGeneration);
        sourceMetadata.setString("purpose", captureIntent.name());
    }

    /** Bind an already described, not yet walked graph used by the bounded legacy geometry search. */
    void bindPreparedSource(MTEEnhancedMultiBlockBase<?> machine, ExtendedFacing facing,
        StructureSources.Resolution resolution) {
        if (!cells.isEmpty() || resolution.status == StructureSources.Status.UNSUPPORTED)
            throw new IllegalStateException("Invalid prepared declaration");
        sourceResolution = resolution;
        if (resolution.plan != null) sourceMetadata = resolution.plan.metadata();
        bindSource(machine, facing);
    }

    /** Copies only source validity guards; no old cells, definition graph, or capture task is retained. */
    void inheritSources(HologramCapture from) {
        String reason = from.sourceValidityFailure();
        if (reason != null) invalidateSource(reason);
        if (from.sourceBinding != null && sourceBinding != from.sourceBinding
            && !inheritedSources.contains(from.sourceBinding)) inheritedSources.add(from.sourceBinding);
        for (SourceBinding inherited : from.inheritedSources)
            if (sourceBinding != inherited && !inheritedSources.contains(inherited)) inheritedSources.add(inherited);
        for (StructureSources.ReadSet observation : from.observations)
            if (!observations.contains(observation)) observations.add(observation);
    }

    /** Attach only the world/controller facts actually read by a capture or its bounded area comparison. */
    void observe(StructureSources.ReadSet observation) {
        if (observation == null) throw new IllegalArgumentException("read set");
        observations.add(observation);
    }

    private boolean resumeCurrent(MTEEnhancedMultiBlockBase<?> machine, ItemStack currentTrigger, ExtendedFacing facing,
        StructureSources.CaptureIntent intent) {
        String reason = context != machine ? "SOURCE_CONTROLLER_CHANGED"
            : captureIntent != intent ? "SOURCE_INTENT_CHANGED"
                : !ItemStack.areItemStacksEqual(trigger, currentTrigger) ? "SOURCE_TRIGGER_CHANGED"
                    : sourceFacing != facing ? "SOURCE_POSE_CHANGED" : sourceValidityFailure();
        if (reason == null) return true;
        invalidateSource(reason);
        return false;
    }

    /** Pure validity check shared by continuations and operation admission; the definition is never rebuilt. */
    String sourceValidityFailure() {
        if (!sourceRejection.isEmpty()) return sourceRejection;
        if (sourceResolution != null && sourceBinding == null) {
            String reason = sourceResolution.validityFailure();
            return reason == null ? "SOURCE_NOT_CAPTURED" : reason;
        }
        if (sourceBinding != null) {
            String reason = sourceBinding.validityFailure();
            if (reason != null) return reason;
        }
        for (SourceBinding inherited : inheritedSources) {
            String reason = inherited.validityFailure();
            if (reason != null) return reason;
        }
        for (StructureSources.ReadSet observation : observations) {
            try {
                String reason = observation.invalidReason();
                if (reason != null && !reason.isEmpty()) return reason;
            } catch (RuntimeException | LinkageError failure) {
                return "SOURCE_OBSERVATION_FAILED";
            }
        }
        return null;
    }

    private static final class SourceBinding {

        final MTEEnhancedMultiBlockBase<?> machine;
        final StructureSources.ReadSet proof;
        final IGregTechTileEntity tile;
        final gregtech.api.interfaces.metatileentity.IMetaTileEntity metaTile;
        final World world;
        final net.minecraft.world.WorldProvider provider;
        final int dimension;
        final TileEntity worldTile;
        final int x, y, z;
        final ExtendedFacing facing;
        final long declarationGeneration;

        SourceBinding(MTEEnhancedMultiBlockBase<?> machine, StructureSources.ReadSet proof, ExtendedFacing facing) {
            this.machine = machine;
            this.proof = proof;
            this.tile = machine.getBaseMetaTileEntity();
            this.metaTile = tile.getMetaTileEntity();
            this.world = tile.getWorld();
            this.provider = world.provider;
            this.dimension = provider == null ? 0 : provider.dimensionId;
            this.x = tile.getXCoord();
            this.y = tile.getYCoord();
            this.z = tile.getZCoord();
            this.worldTile = world.getTileEntity(x, y, z);
            this.facing = facing;
            this.declarationGeneration = HologramDeclarationAdapters.generation();
        }

        String validityFailure() {
            try {
                if (machine.getBaseMetaTileEntity() != tile || ((TileEntity) tile).isInvalid()
                    || tile.getMetaTileEntity() != metaTile) return "SOURCE_CONTROLLER_CHANGED";
                if (tile.getWorld() != world || world.provider != provider
                    || provider != null && provider.dimensionId != dimension) return "SOURCE_WORLD_CHANGED";
                if (tile.getXCoord() != x || tile.getYCoord() != y || tile.getZCoord() != z)
                    return "SOURCE_POSITION_CHANGED";
                if (machine.getExtendedFacing() != facing) return "SOURCE_POSE_CHANGED";
                if (!world.blockExists(x, y, z)) return "SOURCE_CHUNK_UNLOADED";
                if (world.getTileEntity(x, y, z) != worldTile) return "SOURCE_CONTROLLER_CHANGED";
                if (declarationGeneration != HologramDeclarationAdapters.generation())
                    return "SOURCE_DECLARATIONS_CHANGED";
                return proof.invalidReason();
            } catch (RuntimeException | LinkageError failure) {
                return "SOURCE_STATE_UNREADABLE";
            }
        }
    }

    boolean sourceCurrent() {
        String reason = sourceValidityFailure();
        if (reason == null) return true;
        invalidateSource(reason);
        return false;
    }

    private void invalidateSource(String reason) {
        sourceRejection = reason;
        incomplete = true;
        failure = reason;
        pending = false;
        cancelPending();
        extensionTask = null;
    }

    private void finishExtensions() {
        if (incomplete || !com.miaokatze.gtmb.api.BlueprintSnapshots
            .hasProcessors(com.miaokatze.gtmb.api.BlueprintSnapshots.Stage.CAPTURE_COMPLETE)) {
            cancelPending();
            extensionTask = null;
            return;
        }
        if (extensionTask == null) extensionTask = new HologramExtensionSnapshots.CaptureTask(this);
        try {
            pending = !extensionTask.step(this, CELLS_PER_BATCH, deadline);
            if (!pending) {
                extensionReports = extensionTask.result();
                extensionTask = null;
            }
        } catch (java.io.IOException rejected) {
            fail(rejected.getMessage());
            pending = false;
            cancelPending();
            extensionTask = null;
        }
    }

    void cancelPending() {
        if (extensionTask != null) extensionTask.cancel();
    }

    /** Resumable traversal of declared pieces; collection scope owns hint interception and completion. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void walkPieces(HologramCapture capture, IStructureDefinition definition, World world,
        ExtendedFacing facing, int x, int y, int z) {
        int remaining = CELLS_PER_BATCH;
        for (; capture.pieceCursor < capture.pieces.size(); capture.pieceCursor++) {
            Piece piece = capture.pieces.get(capture.pieceCursor);
            IStructureWalker walker = new IStructureWalker() {

                public boolean visit(IStructureElement element, World w, int xx, int yy, int zz, int a, int b, int c) {
                    String position = xx + "," + yy + "," + zz;
                    Cell existing = capture.positions.get(position);
                    if (existing != null) {
                        if (existing.element == element
                            && ItemStack.areItemStacksEqual(existing.elementTrigger, piece.trigger)) return true;
                        capture.fail("CONFLICTING_CELL " + position);
                        return false;
                    }
                    if (capture.cells.size() >= LIMIT) {
                        capture.fail("CELL_LIMIT: 蓝图实际记录格数须小于一百万");
                        return false;
                    }
                    Cell cell = new Cell(element, xx, yy, zz, piece.trigger);
                    if (xx == x && yy == y && zz == z) capture.declaredAnchorIndex = capture.cells.size();
                    capture.cells.add(cell);
                    capture.positions.put(position, cell);
                    capture.current = cell;
                    try {
                        Set<Pair<Integer, ExactKey>> signals = capture.declarationTrace
                            .computeIfAbsent(element, ignored -> new HashSet<>());
                        boolean observeDeclaration = !signals
                            .contains(Pair.of(piece.trigger.stackSize, HologramValues.lookupKey(piece.trigger)));
                        if (observeDeclaration)
                            signals.add(Pair.of(piece.trigger.stackSize, HologramValues.stackKey(piece.trigger)));
                        if (observeDeclaration) HologramDeclarationWrappers.observeChannels(element, piece.trigger);
                        HologramGTNLElement reviewed = HologramGTNLSemantics.reviewedElement(element);
                        if (reviewed != null) {
                            reviewed.describeCell(cell);
                        } else if (element == StructureUtility.isAir()) {
                            cell.block = Blocks.air;
                            cell.meta = 0;
                        } else capture.spawnHint(element, w, xx, yy, zz, piece.trigger, 0);
                        if (observeDeclaration) HologramDeclarationWrappers.observeMaterials(element);
                        if (cell.block == null || cell.block == StructureLibAPI.getBlockHint()) {
                            HologramCasingFallback.Target casing = capture.casing(element, piece.trigger);
                            if (casing == null) cell.unknown = true;
                            else {
                                cell.block = casing.block;
                                cell.meta = casing.meta;
                            }
                        }
                        net.minecraft.item.ItemStack frame = HologramFrameSupport.target(element);
                        if (frame != null) {
                            cell.block = Block.getBlockFromItem(frame.getItem());
                            cell.meta = frame.getItem()
                                .getMetadata(frame.getItemDamage());
                            cell.unknown = false;
                            cell.iconOnly = false;
                        }
                        if (!capture.roleCache.containsKey(element))
                            capture.roleCache.put(element, HologramElementCatalog.hatchRole(element));
                        cell.role = capture.roleCache.get(element);
                        if (!capture.familyCache.containsKey(element)) capture.familyCache
                            .put(element, HologramReplacementFamily.resolve(element, cell.block, cell.meta));
                        HologramReplacementFamily.Family family = capture.familyCache.get(element);
                        if (family != null) cell.family = family.id;
                        if (cell.block == null) {
                            cell.unknown = true;
                            if (cell.iconOnly) cell.role = "interface";
                        }
                        if (cell.unknown && "block".equals(cell.role)) cell.role = "interface";
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                        capture.noteFailure(e);
                        cell.unknown = true;
                        capture.failure = e.getClass()
                            .getSimpleName();
                    } finally {
                        try {
                            capture.describeActual(cell, w);
                        } catch (RuntimeException | LinkageError e) {
                            cell.unknown = true;
                        }
                        capture.current = null;
                    }
                    return true;
                }

                public boolean blockNotLoaded(IStructureElement e, World w, int xx, int yy, int zz, int a, int b,
                    int c) {
                    capture.fail("UNLOADED " + xx + "," + yy + "," + zz);
                    return false;
                }
            };
            if (HologramStructureCursor.supports(definition)) {
                if (capture.structureCursor == null) capture.structureCursor = new HologramStructureCursor(
                    definition.getStructureFor(piece.name),
                    piece.a,
                    piece.b,
                    piece.c);
                int cursorStart = capture.structureCursor.done();
                boolean finished = capture.structureCursor
                    .advance(world, facing, x, y, z, walker, remaining, capture.deadline);
                remaining -= capture.structureCursor.done() - cursorStart;
                if (!finished) {
                    capture.pending = true;
                    return;
                }
                capture.structureCursor = null;
            } else {
                // A custom iterator may deliberately differ from its declaration array. Preserve its callback
                // semantics as one atomic native operation; only the standard V2 walk can be checkpointed safely.
                definition.iterate(piece.name, world, facing, x, y, z, piece.a, piece.b, piece.c, walker);
                remaining = 0;
            }
            if (capture.pieceCursor + 1 < capture.pieces.size()
                && (remaining <= 0 || System.nanoTime() >= capture.deadline)) {
                capture.pieceCursor++;
                capture.pending = true;
                return;
            }
        }
    }

    void clearInterpretationCaches() {
        casingCache.clear();
        familyCache.clear();
        roleCache.clear();
        declarationTrace.clear();
        interpretationGeneration = HologramDeclarationAdapters.generation();
    }

    private void refreshInterpretationGeneration() {
        if (interpretationGeneration != HologramDeclarationAdapters.generation()) clearInterpretationCaches();
    }

    private void fail(String reason) {
        incomplete = true;
        if (failure.isEmpty()) failure = reason;
        else if ("TIME_LIMIT".equals(reason) && !failure.startsWith("TIME_LIMIT")) failure = reason + ": " + failure;
    }

    private void noteFailure(Throwable failure) {
        if (!failureOrigin.isEmpty()) return;
        StackTraceElement[] trace = failure.getStackTrace();
        failureOrigin = failure.getClass()
            .getSimpleName() + (trace.length == 0 ? "" : " at " + trace[0]);
    }

    /** Delegate hints through reviewed wrappers without evaluating a deferred controller factory. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean spawnHint(IStructureElement element, World world, int x, int y, int z, ItemStack signal, int depth)
        throws ReflectiveOperationException {
        if (element == null || depth > 12) return false;
        HologramGTNLElement reviewed = HologramGTNLSemantics.reviewedElement(element);
        if (reviewed != null) {
            if (current == null) return false;
            reviewed.describeCell(current);
            return true;
        }
        IStructureElement backing = HologramDeclarationWrappers.backing(element);
        if (backing != null)
            return spawnHint(backing, world, x, y, z, HologramDeclarationWrappers.trigger(element, signal), depth + 1);
        if (HologramDeclarationWrappers.requiresAdapter(element)) return false;
        if (element instanceof com.gtnewhorizon.structurelib.structure.IStructureElementChain) {
            IStructureElement[] fallbacks = ((com.gtnewhorizon.structurelib.structure.IStructureElementChain) element)
                .fallbacks();
            if (fallbacks == null || fallbacks.length > 64) return false;
            for (IStructureElement fallback : fallbacks)
                if (spawnHint(fallback, world, x, y, z, signal, depth + 1)) return true;
            return false;
        }
        if (!reviewedHintLeaf(element)) return false;
        nativeHintCalls++;
        return element.spawnHint(context, world, x, y, z, signal);
    }

    /** Only these exact factories emit fixed hints without invoking caller-supplied callbacks. */
    private static boolean reviewedHintLeaf(IStructureElement<?> element) {
        if (element == StructureUtility.isAir() || element == StructureUtility.notAir()
            || element == StructureUtility.error()) return true;
        java.lang.reflect.Method factory = element.getClass()
            .getEnclosingMethod();
        if (factory == null) return false;
        if (factory.getDeclaringClass() == StructureUtility.class) {
            switch (factory.getName()) {
                case "ofBlock":
                case "ofBlockAnyMeta":
                case "ofBlocksFlat":
                case "ofBlocksMap":
                case "ofBlocksTiered":
                case "ofHint":
                case "ofBlockHint":
                case "ofBlocksFlatHint":
                case "ofBlocksMapHint":
                case "ofBlockAdder":
                case "ofBlockAdderHint":
                case "ofTileAdder":
                    return true;
                case "ofBlockUnlocalizedName":
                    // The overload taking an arbitrary fallback can invoke an unknown leaf.
                    return factory.getParameterTypes().length == 4 && factory.getParameterTypes()[3] == boolean.class;
                default:
                    return false;
            }
        }
        if (factory.getDeclaringClass() == gregtech.api.util.GTStructureUtility.class) {
            switch (factory.getName()) {
                case "ofAnyWater":
                case "ofHatchAdder":
                case "ofHatchAdderOptional":
                case "ofCoil":
                case "ofSolenoidCoil":
                case "ofGenericMTETiered":
                    return true;
                default:
                    return false;
            }
        }
        return false;
    }

    private void describeActual(Cell cell, World world) {
        TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
        if (tile == null) return;
        cell.actualDisplay = HologramDisplaySnapshot.capture(tile);
        cell.renderKind = "item";
        if (tile instanceof IGregTechTileEntity) {
            IGregTechTileEntity gt = (IGregTechTileEntity) tile;
            if (gt.getMetaTileEntity() != null) cell.actualStack = gt.getMetaTileEntity()
                .getStackForm(1);
        }
        if ("block".equals(cell.role)) cell.role = "interface";
    }

    private void addAnchor(MTEEnhancedMultiBlockBase<?> machine) {
        World world = machine.getBaseMetaTileEntity()
            .getWorld();
        int x = machine.getBaseMetaTileEntity()
            .getXCoord();
        int y = machine.getBaseMetaTileEntity()
            .getYCoord();
        int z = machine.getBaseMetaTileEntity()
            .getZCoord();
        Cell anchor = new Cell(null, x, y, z);
        anchor.anchor = true;
        anchor.role = "controller";
        anchor.renderKind = "item";
        anchor.status = "protected";
        anchor.block = world.getBlock(x, y, z);
        anchor.meta = world.getBlockMetadata(x, y, z);
        anchor.actualStack = machine.getStackForm(1);
        anchor.actualDisplay = HologramDisplaySnapshot.capture((TileEntity) machine.getBaseMetaTileEntity());
        anchor.actualFacing = machine.getBaseMetaTileEntity()
            .getFrontFacing()
            .ordinal();
        if (declaredAnchorIndex >= 0) cells.remove(declaredAnchorIndex);
        if (cells.size() >= LIMIT) {
            fail("CELL_LIMIT: 控制器计入蓝图实际记录格数，一百万格不予采集");
            return;
        }
        cells.add(anchor);
        positions.put(x + "," + y + "," + z, anchor);
    }

    private HologramCasingFallback.Target casing(IStructureElement<?> element, ItemStack trigger) {
        Map<Pair<Integer, ExactKey>, HologramCasingFallback.Target> signals = casingCache
            .computeIfAbsent(element, ignored -> new HashMap<>());
        Pair<Integer, ExactKey> signal = trigger == null ? null
            : Pair.of(trigger.stackSize, HologramValues.lookupKey(trigger));
        if (!signals.containsKey(signal)) {
            Pair<Integer, ExactKey> owned = trigger == null ? null
                : Pair.of(trigger.stackSize, HologramValues.stackKey(trigger));
            HologramCasingFallback.Target target = HologramCasingFallback.resolve(element, trigger);
            signals.put(owned, target);
            return target;
        }
        return signals.get(signal);
    }

    static final class Piece {

        final String name;
        final int a, b, c;
        final ItemStack trigger;

        Piece(String name, int a, int b, int c, ItemStack trigger) {
            this.name = name;
            this.a = a;
            this.b = b;
            this.c = c;
            this.trigger = trigger;
        }
    }

    static final class Cell {

        final IStructureElement element;
        final int x, y, z;
        // Runtime-only hint, accepted only when the current capture still owns this exact cell at that index.
        int stableIndex = -1;
        Block block;
        int meta;
        boolean unknown;
        String blueprintBlock = "", blueprintItem = "";
        int blueprintDamage;
        String sourceClass = "", sourceWarning = "";
        String resourceFingerprint = "", resourceTile = "", resourceItem = "", resourceError = "";
        HologramBlueprintResources.Plan resourcePlan;
        HologramCellPolicy.ResourcePlanKey resourcePlanKey;
        String coverCostsNbt = "";
        List<ItemStack> coverMaterials = java.util.Collections.emptyList();
        boolean invalidCoverCosts;
        boolean excluded;
        String prompt = "", tileNbt = "", itemNbt = "", structureId = "";
        List<String> structureIds;
        String status = "pending";
        List<ItemStack> candidates;
        List<ItemStack> structureTargets;
        boolean candidateComplete;
        String candidateBoundary = "尚未解析结构声明";
        final ItemStack elementTrigger;
        boolean anchor;
        boolean userPosition;
        boolean iconOnly;
        String role = "block";
        String family = "";
        String renderKind = "block";
        ItemStack actualStack;
        net.minecraft.nbt.NBTTagCompound actualDisplay = new net.minecraft.nbt.NBTTagCompound();
        net.minecraft.nbt.NBTTagCompound targetDisplay = new net.minecraft.nbt.NBTTagCompound();
        int actualFacing = -1;
        String[] hintIcons;
        short[] hintTint;
        int hintIndex = -1;

        List<String> memberships() {
            if (structureIds != null && !structureIds.isEmpty()) return structureIds;
            return structureId.isEmpty() ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(structureId);
        }

        boolean belongsTo(String id) {
            return id != null && !id.isEmpty() && memberships().contains(id);
        }

        Cell(IStructureElement element, int x, int y, int z) {
            this(element, x, y, z, null);
        }

        Cell(IStructureElement element, int x, int y, int z, ItemStack elementTrigger) {
            this.element = element;
            this.x = x;
            this.y = y;
            this.z = z;
            this.elementTrigger = elementTrigger == null ? null : elementTrigger.copy();
        }
    }

    private static final class CaptureLimitException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CaptureLimitException() {
            super("hologram capture budget exhausted", null, false, false);
        }
    }
}
