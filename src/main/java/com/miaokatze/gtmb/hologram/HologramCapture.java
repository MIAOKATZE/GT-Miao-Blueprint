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
import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods;

/** Armed only during a server-thread preview. Hints describe appearance, never permission to write. */
public final class HologramCapture {

    /** Java collection capacity; no structure-size policy limit. */
    public static final int LIMIT = Integer.MAX_VALUE - 8;
    private static final int CELLS_PER_BATCH = 4096;
    /** Wall-clock threshold at checkpoints; native machine callbacks cannot be interrupted mid-call. */
    private static final long CAPTURE_TIME_NS = 100000000L;
    private static final Logger LOG = LogManager.getLogger("gtmb");
    private static final ThreadLocal<HologramCapture> ACTIVE = new ThreadLocal<>();
    final Object context;
    final ItemStack trigger;
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

    HologramCapture(Object context, ItemStack trigger) {
        this.context = context;
        this.trigger = trigger;
    }

    /** Test/diagnostic declaration capture on a detached native controller; no structure/world traversal. */
    static HologramCapture declarationPieces(IConstructable machine, ItemStack trigger) {
        HologramCapture capture = new HologramCapture(machine, trigger.copy());
        HologramCapture previous = ACTIVE.get();
        ACTIVE.set(capture);
        try {
            machine.construct(capture.trigger.copy(), true);
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
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
        return collect(machine, trigger, facing, null);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    static HologramCapture collect(MTEEnhancedMultiBlockBase<?> machine, ItemStack trigger, ExtendedFacing facing,
        HologramCapture resumed) {
        HologramCapture capture = resumed == null ? new HologramCapture(machine, trigger) : resumed;
        capture.deadline = System.nanoTime() + CAPTURE_TIME_NS;
        capture.pending = false;
        if (capture.walkingComplete) {
            capture.deadline = HologramWorkBudget.captureDeadline(capture.deadline);
            capture.finishExtensions();
            return capture;
        }
        HologramCapture previous = ACTIVE.get();
        HologramChannelTrace.Scope scope = HologramChannelTrace.begin(machine);
        ACTIVE.set(capture);
        try {
            if (resumed == null) {
                com.miaokatze.gtmb.api.StructureSources.Plan supplied = com.miaokatze.gtmb.api.StructureSources
                    .resolve(machine, trigger);
                if (supplied != null) {
                    capture.definition = supplied.definition;
                    for (com.miaokatze.gtmb.api.StructureSources.Piece piece : supplied.pieces)
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
                    } else((IConstructable) machine).construct(trigger, true);
            }
            // Some community controllers rebuild every element on every call. Rebuilding here on
            // continuation would conflict with cells already collected from the previous identity graph.
            if (capture.definition == null) capture.definition = machine.getStructureDefinition();
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
                        if (element == StructureUtility.isAir()) {
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
        return element.spawnHint(context, world, x, y, z, signal);
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
