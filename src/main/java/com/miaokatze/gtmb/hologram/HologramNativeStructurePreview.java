// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.stats.StatBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.Declarations;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.misc.GTStructureChannels;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Runs the native multiblock browser's placement callbacks on a controller owned by this capture. */
public final class HologramNativeStructurePreview implements AutoCloseable {

    private static final int MAX_ROUNDS = 2000;
    private static final int MAX_CANDIDATES = 128;
    private static final String HATCH = GTStructureChannels.HATCH.get();
    private static final ThreadLocal<HologramNativeStructurePreview> ACTIVE = new ThreadLocal<>();
    private static final VisitedListener LISTENER = new VisitedListener();
    private static boolean installed;

    private final Thread owner = Thread.currentThread();
    private final TileEntity controller;
    private final World emptyWorld, world;
    private final Object context;
    private final HologramNativePreviewWorld memory;
    private final HologramBlockRendererPreview blockRenderer;
    private final HologramNativePreviewItems items;
    private final boolean construction;
    private ItemStack pieceTrigger;
    private final Map<Position, ItemStack> pieceTriggers = new HashMap<>();
    private final ColdCandidateCache coldCandidates;
    private final HologramNativeHatchChooser nativeHatchChooser;
    private final PresetCandidates presetCandidates;
    private final ISurvivalBuildEnvironment survivalEnvironment;
    private final AutoPlaceEnvironment candidateEnvironment;
    private final IConstructable constructable;
    private final INEIPreviewModifier modifier;
    private final boolean structureCompat;
    private final boolean gregTech;
    private final Set<Position> sourceWater;
    private final ItemStack trigger;
    private final NBTTagList description = new NBTTagList();
    private final Long2ObjectOpenHashMap<IStructureElement<?>> elements = new Long2ObjectOpenHashMap<>();
    private final Map<Position, IStructureElement<?>> wideElements = new HashMap<>();
    private final Map<IStructureElement<?>, Boolean> observedMaterials = new IdentityHashMap<>();
    private final Map<IStructureElement<?>, PreviewOptions> sharedOptions = new IdentityHashMap<>();
    private final HologramChannelTrace.Accumulator channels;
    private IStructureDefinition<?> definition;
    private Iterator<HologramNativePreviewWorld.Placement> placements;
    private boolean instrumented, done, closed, visitLimit;
    private int rounds;
    private int lastSurvivalResult = Integer.MIN_VALUE;
    private HologramNativePreviewWorld complement;
    private TileEntity complementController;
    private Object complementContext;
    private HologramChannelTrace.Report constructionChannels;
    private final ControllerFactory freshController;
    private final com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing previewFacing;

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger) throws IOException {
        this(controller, context, constructable, structureCompat, trigger, null, null);
    }

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger, ControllerFactory pristine) throws IOException {
        this(controller, context, constructable, structureCompat, trigger, pristine, null);
    }

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger, ControllerFactory pristine,
        com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing facing) throws IOException {
        this(
            controller,
            context,
            constructable,
            structureCompat,
            trigger,
            pristine,
            facing,
            new HologramNativePreviewItems(),
            false);
    }

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger, ControllerFactory pristine,
        com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing facing, HologramNativePreviewItems source,
        boolean construction) throws IOException {
        install();
        this.items = source;
        this.construction = construction;
        coldCandidates = new ColdCandidateCache(items::catalog);
        presetCandidates = new PresetCandidates(items::catalog);
        freshController = pristine;
        previewFacing = facing;
        this.controller = controller;
        this.context = context;
        channels = new HologramChannelTrace.Accumulator(context);
        this.constructable = constructable;
        this.structureCompat = structureCompat;
        this.trigger = trigger.copy();
        emptyWorld = controller.getWorldObj();
        gregTech = controller instanceof IGregTechTileEntity;
        modifier = !construction && gregTech && context instanceof INEIPreviewModifier supplied ? supplied : null;
        nativeHatchChooser = !gregTech || pristine == null || modifier != null ? null
            : new HologramNativeHatchChooser(items::catalog, () -> pristineHatchQuery(pristine));
        if (!construction && HologramBlockRendererPreview.required(context)) {
            // Only this implementation requires BlockRenderer's class identity. Other controllers use our memory world.
            blockRenderer = new HologramBlockRendererPreview(controller);
            memory = null;
            world = blockRenderer.world;
        } else {
            memory = HologramNativePreviewWorld.open(controller);
            blockRenderer = null;
            world = memory;
        }
        sourceWater = !construction && memory != null
            && context != null
            && HologramNativeSourceWater.reviewedController(context.getClass()) ? new LinkedHashSet<>() : null;
        PreviewPlayer actor = new PreviewPlayer(world);
        survivalEnvironment = ISurvivalBuildEnvironment.create(items, actor);
        candidateEnvironment = AutoPlaceEnvironment.fromLegacy(items, actor, message -> {});
        if (!construction && gregTech) ChannelDataAccessor.setChannelData(this.trigger, HATCH, 1);
        pieceTrigger = this.trigger.copy();
        if (memory != null) items.bind(memory);
        // Do not leave the detached controller in an active preview world between client ticks.
        controller.setWorldObj(emptyWorld);
    }

    public static synchronized void install() {
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(LISTENER);
            installed = true;
        }
    }

    /** Each placement pass is atomic. Additional native passes can run on following client ticks. */
    boolean advance(long deadline) throws IOException {
        if (closed || done || Thread.currentThread() != owner)
            throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_SCOPE");
        try {
            controller.setWorldObj(world);
            HologramChannelTrace.Scope trace = HologramChannelTrace.begin(context);
            boolean updateEnabled = RunnableMachineUpdate.isCurrentThreadEnabled();
            boolean ownsInstrument = !StructureLibAPI.isInstrumentEnabled();
            HologramNativeStructurePreview previous = ACTIVE.get();
            try {
                if (updateEnabled) RunnableMachineUpdate.setCurrentThreadEnabled(false);
                if (ownsInstrument) StructureLibAPI.enableInstrument(this);
                instrumented = ownsInstrument;
                if (!ownsInstrument && sourceWater != null) sourceWater.clear();
                ACTIVE.set(this);
                if (rounds == 0 && modifier != null) modifier.onPreviewConstruct(trigger);
                boolean repeat = false;
                do {
                    repeat = false;
                    long passStart = HologramRuntimeMetrics.start();
                    try {
                        if (modifier != null) {
                            // GTGuiMultiblockHandler's NEI lifecycle advances by actual world changes,
                            // independently of survival completion. A pre-hook may suspend survival with 0;
                            // its completion hook is still required to produce the display geometry.
                            if (constructable instanceof ISurvivalConstructable survival) {
                                lastSurvivalResult = survival
                                    .survivalConstruct(trigger, Integer.MAX_VALUE, survivalEnvironment);
                                rounds++;
                                if (memory == null ? blockRenderer.takeChanged() : memory.takeChanged()) {
                                    if (rounds >= MAX_ROUNDS) throw new IOException("LOCAL_NATIVE_PREVIEW_UNSTABLE");
                                    repeat = true;
                                    continue;
                                }
                            } else {
                                constructable.construct(trigger.copy(), false);
                                rounds++;
                            }
                        } else if (constructable instanceof ISurvivalConstructable survival) {
                            int result = survival.survivalConstruct(trigger, Integer.MAX_VALUE, survivalEnvironment);
                            rounds++;
                            lastSurvivalResult = result;
                            if (result < -2) throw new IOException("LOCAL_NATIVE_PREVIEW_RESULT:" + result);
                            // -1 is the only survival completion signal. A cursor may scan many already
                            // correct slices, returning 0 without touching the world between useful passes.
                            if (result == -1) break;
                            if (result == -2) {
                                if (freshController == null || previewFacing == null || memory == null)
                                    throw new IOException("LOCAL_NATIVE_PREVIEW_INCOMPLETE");
                                completeNativeFootprint();
                                break;
                            }
                            if (rounds >= MAX_ROUNDS) throw new IOException("LOCAL_NATIVE_PREVIEW_UNSTABLE");
                            if (memory == null) blockRenderer.takeChanged();
                            else memory.takeChanged();
                            repeat = true;
                            continue;
                        } else {
                            constructable.construct(trigger, false);
                            rounds++;
                        }
                    } finally {
                        HologramRuntimeMetrics
                            .finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_NATIVE_PASSES, passStart);
                    }
                    break;
                } while (System.nanoTime() < deadline);
                if (visitLimit) throw new IOException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
                // Nonterminal survival passes remain pending even when they did not write a block.
                if (repeat) return false;
                if (modifier != null) modifier.onPreviewStructureComplete(trigger);
                if (construction && complement == null) {
                    // Hints contribute only declaration metadata/explicit air. The real placements above
                    // remain the target authority, including direct custom writes with no visit events.
                    world.isRemote = true;
                    memory.hintsOnly(true);
                    try {
                        constructable.construct(trigger.copy(), true);
                    } finally {
                        memory.hintsOnly(false);
                        world.isRemote = false;
                    }
                }
                if (visitLimit) throw new IOException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
                placements = memory == null ? blockRenderer.placements(controller)
                    : construction ? memory.currentPlacements() : memory.placements();
                if (complement != null) {
                    int declarationCells = complement.recordedPlacementCount();
                    if (construction) {
                        // Unsupported survival is a separate branch, never a union with its prefix.
                        placements = complement.currentPlacements();
                    } else {
                        complement.retainOnlyAdditionalTo(memory);
                        // A no-op or strict subset is not authoritative evidence that the stalled prefix is complete.
                        if (complement.recordedPlacementCount() == 0
                            && (declarationCells == 0 || declarationCells < memory.recordedPlacementCount()))
                            throw new IOException("LOCAL_NATIVE_PREVIEW_INCOMPLETE");
                        placements = both(placements, complement.placements());
                    }
                }
                if (sourceWater != null && !sourceWater.isEmpty()) appendUnplacedSourceWater();
                if (!placements.hasNext() && !(construction && (!elements.isEmpty() || !wideElements.isEmpty())))
                    throw new IOException("LOCAL_NATIVE_PREVIEW_EMPTY");
                readDescription();
                done = true;
                return true;
            } finally {
                try {
                    if (previous == null) ACTIVE.remove();
                    else ACTIVE.set(previous);
                    if (ownsInstrument) StructureLibAPI.disableInstrument();
                } finally {
                    if (RunnableMachineUpdate.isCurrentThreadEnabled() != updateEnabled)
                        RunnableMachineUpdate.setCurrentThreadEnabled(updateEnabled);
                    HologramChannelTrace.endInto(trace, channels);
                }
            }
        } finally {
            controller.setWorldObj(emptyWorld);
        }
    }

    private void completeNativeFootprint() throws IOException {
        TileEntity fresh = freshController.create();
        World base = fresh.getWorldObj();
        Object freshContext = HologramStructureDiscovery.context(fresh);
        if (fresh == controller || !(base instanceof HologramResourceWorld)
            || base == world
            || base == emptyWorld
            || freshContext == null
            || freshContext == context
            || fresh.getClass() != controller.getClass()
            || freshContext.getClass() != context.getClass()
            || fresh.xCoord != controller.xCoord
            || fresh.yCoord != controller.yCoord
            || fresh.zCoord != controller.zCoord) throw new IOException("LOCAL_NATIVE_PREVIEW_COMPLETION_SCOPE");
        IConstructable full = HologramStructureDiscovery.localConstructable(fresh, freshContext, previewFacing);
        if (full == null) throw new IOException("LOCAL_NATIVE_PREVIEW_DECLARATION_UNAVAILABLE");
        if (construction) {
            elements.clear();
            wideElements.clear();
            pieceTriggers.clear();
        }
        complement = HologramNativePreviewWorld.open(fresh);
        complementController = fresh;
        complementContext = freshContext;
        fresh.setWorldObj(complement);
        ItemStack copy = trigger.copy();
        HologramChannelTrace.Scope freshTrace = construction ? HologramChannelTrace.begin(freshContext) : null;
        try {
            INEIPreviewModifier hooks = !construction && gregTech
                && freshContext instanceof INEIPreviewModifier supplied ? supplied : null;
            if (hooks != null) hooks.onPreviewConstruct(copy);
            full.construct(copy, false);
            if (construction) {
                complement.isRemote = true;
                complement.hintsOnly(true);
                try {
                    full.construct(copy.copy(), true);
                } finally {
                    complement.hintsOnly(false);
                    complement.isRemote = false;
                }
            }
            if (hooks != null) hooks.onPreviewStructureComplete(copy);
        } finally {
            if (freshTrace != null) constructionChannels = HologramChannelTrace.end(freshTrace);
            fresh.setWorldObj(base);
        }
    }

    private static <T> Iterator<T> both(Iterator<T> first, Iterator<T> second) {
        return new Iterator<T>() {

            @Override
            public boolean hasNext() {
                return first.hasNext() || second.hasNext();
            }

            @Override
            public T next() {
                return first.hasNext() ? first.next() : second.next();
            }
        };
    }

    static void piece(IStructureDefinition<?> definition, Object context, ItemStack signal, World world) {
        HologramNativeStructurePreview run = ACTIVE.get();
        if (run != null && (run.context == context && run.world == world
            || run.complementContext == context && run.complement == world)) {
            run.definition = definition;
            if (signal != null) run.pieceTrigger = signal.copy();
        }
    }

    static void piece(Object context, ItemStack signal) {
        HologramNativeStructurePreview run = ACTIVE.get();
        if (run != null && (run.context == context || run.complementContext == context) && signal != null)
            run.pieceTrigger = signal.copy();
    }

    static boolean consumesHint(World world) {
        HologramNativeStructurePreview run = ACTIVE.get();
        return run != null && (run.world == world || run.complement == world);
    }

    ItemStack triggerAt(int x, int y, int z) {
        return pieceTriggers.getOrDefault(new Position(x, y, z), trigger)
            .copy();
    }

    boolean checkTargetStructure() {
        TileEntity tile = targetController();
        Object target = targetContext();
        if (!(target instanceof gregtech.api.metatileentity.implementations.MTEMultiBlockBase machine)
            || !(tile instanceof IGregTechTileEntity nativeTile)) return false;
        World previous = tile.getWorldObj();
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        try {
            tile.setWorldObj(targetWorld());
            if (updates) RunnableMachineUpdate.setCurrentThreadEnabled(false);
            return machine.checkStructure(true, nativeTile);
        } finally {
            if (RunnableMachineUpdate.isCurrentThreadEnabled() != updates)
                RunnableMachineUpdate.setCurrentThreadEnabled(updates);
            tile.setWorldObj(previous);
        }
    }

    TileEntity targetController() {
        return construction && complementController != null ? complementController : controller;
    }

    Object targetContext() {
        return construction && complementContext != null ? complementContext : context;
    }

    HologramNativePreviewWorld targetWorld() {
        return construction && complement != null ? complement : memory;
    }

    java.util.Set<HologramNativeTargetPlan.Offset> visitedOffsets() {
        java.util.Set<HologramNativeTargetPlan.Offset> result = new java.util.LinkedHashSet<>();
        for (long key : elements.keySet()) {
            result.add(
                new HologramNativeTargetPlan.Offset(
                    CoordinatePacker.unpackX(key) - controller.xCoord,
                    CoordinatePacker.unpackY(key) - controller.yCoord,
                    CoordinatePacker.unpackZ(key) - controller.zCoord));
        }
        for (Position p : wideElements.keySet()) result.add(
            new HologramNativeTargetPlan.Offset(
                p.x() - controller.xCoord,
                p.y() - controller.yCoord,
                p.z() - controller.zCoord));
        return result;
    }

    int passes() {
        return rounds;
    }

    int nativeResult() {
        return lastSurvivalResult;
    }

    int extraDisplayCells() {
        return complement == null ? 0 : complement.recordedPlacementCount();
    }

    private void elementVisited(StructureElementVisitedEvent event) {
        if ((event.getWorld() != world && event.getWorld() != complement) || Thread.currentThread() != owner
            || instrumented && event.getInstrumentIdentifier() != this) return;
        int x = event.getX(), y = event.getY(), z = event.getZ();
        boolean known = HologramNativePreviewWorld.packable(x, y, z)
            ? elements.containsKey(CoordinatePacker.pack(x, y, z))
            : wideElements.containsKey(new Position(x, y, z));
        if (elements.size() + wideElements.size() >= HologramBlueprintLimits.MAX_CELLS && !known) {
            visitLimit = true;
            return;
        }
        pieceTriggers.put(new Position(x, y, z), pieceTrigger.copy());
        items.position(
            HologramNativeTargetPlan.position(x - controller.xCoord, y - controller.yCoord, z - controller.zCoord));
        if (!construction && complement != null && event.getWorld() == complement && memory.hasPlacement(x, y, z))
            return;
        boolean atLimit = elements.size() + wideElements.size() >= HologramBlueprintLimits.MAX_CELLS;
        if (HologramNativePreviewWorld.packable(x, y, z)) {
            long positionKey = CoordinatePacker.pack(x, y, z);
            if (sourceWater != null && instrumented
                && (!atLimit || elements.containsKey(positionKey))
                && !(x == controller.xCoord && y == controller.yCoord && z == controller.zCoord)) {
                Position position = new Position(x, y, z);
                if (HologramNativeSourceWater.reviewedSlot(event.getElement())) sourceWater.add(position);
                else sourceWater.remove(position);
            }
            if (atLimit && !elements.containsKey(positionKey)) visitLimit = true;
            else elements.put(positionKey, event.getElement());
        } else {
            // A custom declaration can emit visits outside the preview world's placement bounds.
            Position position = new Position(x, y, z);
            if (atLimit && !wideElements.containsKey(position)) visitLimit = true;
            else wideElements.put(position, event.getElement());
        }
    }

    private void appendUnplacedSourceWater() throws IOException {
        // Survival-place has no item for water and can fall through to native air. Materialize a preview
        // row only: do not call element.placeBlock, setBlock, bucket logic or a real controller callback.
        List<HologramNativePreviewWorld.Placement> generated = new ArrayList<>();
        for (Position p : sourceWater) {
            if (!memory.blockExists(p.x(), p.y(), p.z())
                || complement != null && complement.hasPlacement(p.x(), p.y(), p.z())
                || !HologramNativeSourceWater.missingFromDetachedPreview(memory.getBlock(p.x(), p.y(), p.z())))
                continue;
            HologramBlueprintLimits
                .require(memory.recordedPlacementCount() + extraDisplayCells() + generated.size() + 2L);
            generated.add(new HologramNativePreviewWorld.Placement(p.x(), p.y(), p.z(), Blocks.water, 0, null));
        }
        if (generated.isEmpty()) return;
        Iterator<HologramNativePreviewWorld.Placement> nativeRows = placements, waterRows = generated.iterator();
        placements = new Iterator<HologramNativePreviewWorld.Placement>() {

            @Override
            public boolean hasNext() {
                return nativeRows.hasNext() || waterRows.hasNext();
            }

            @Override
            public HologramNativePreviewWorld.Placement next() {
                return nativeRows.hasNext() ? nativeRows.next() : waterRows.next();
            }
        };
    }

    IStructureElement<?> elementAt(int x, int y, int z) {
        return HologramNativePreviewWorld.packable(x, y, z) ? elements.get(CoordinatePacker.pack(x, y, z))
            : wideElements.get(new Position(x, y, z));
    }

    Iterator<HologramNativePreviewWorld.Placement> placements() {
        if (!done || closed) throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_SCOPE");
        return placements;
    }

    HologramChannelTrace.Report channels() {
        return constructionChannels != null ? constructionChannels : channels.snapshot();
    }

    IStructureDefinition<?> definition() {
        return definition;
    }

    NBTTagList description() {
        return description;
    }

    private void readDescription() {
        IConstructable source = construction && complementController != null
            ? HologramStructureDiscovery.localConstructable(complementController, complementContext, previewFacing)
            : constructable;
        try {
            IStructureDefinition<?> available = source.getStructureDefinition();
            if (available != null) definition = available;
        } catch (RuntimeException | LinkageError unavailable) {
            // A legacy declaration can expose its layout through construction alone.
        }
        try {
            String[] lines = source.getStructureDescription(trigger.copy());
            if (lines == null) return;
            for (int i = 0; i < Math.min(32, lines.length); i++) {
                if (lines[i] != null) description.appendTag(new NBTTagString(bounded(lines[i], 512)));
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // The geometry remains available when an optional tooltip provider is absent.
        }
    }

    HologramCapture.Cell targetCell(HologramNativePreviewWorld.Placement placement) {
        return previewCell(placement, elementAt(placement.x(), placement.y(), placement.z()));
    }

    HologramCapture.Cell cell(HologramNativePreviewWorld.Placement placement) {
        IStructureElement<?> element = elementAt(placement.x(), placement.y(), placement.z());
        HologramCapture.Cell cell = previewCell(placement, element);
        readCellCandidates(cell);
        return cell;
    }

    private HologramCapture.Cell previewCell(HologramNativePreviewWorld.Placement placement,
        IStructureElement<?> element) {
        HologramCapture.Cell cell = HologramCapture
            .nativePreviewCell(placement, element, triggerAt(placement.x(), placement.y(), placement.z()));
        if (complement != null && complement.hasPlacement(placement.x(), placement.y(), placement.z())) {
            // Native creative display is not a survival quote, writable hatch identity or permission.
            cell.unknown = true;
            cell.prompt = "仅供原生展示，未取得生存放置依据";
            return cell;
        }
        if (sourceWater != null && HologramNativeSourceWater.source(placement.block(), placement.meta())
            && sourceWater.contains(new Position(placement.x(), placement.y(), placement.z())))
            cell.nativeSourceWater = true;
        return cell;
    }

    /** Public native offers are queried at the exact position on an unfilled controller. */
    List<ItemStack> presetOffers(int x, int y, int z) {
        if (nativeHatchChooser == null) return Collections.emptyList();
        return nativeOffers(elementAt(x, y, z), x, y, z, triggerAt(x, y, z));
    }

    List<ItemStack> nativeOffers(IStructureElement<?> element, int x, int y, int z, ItemStack signal) {
        return nativeHatchChooser == null ? Collections.emptyList()
            : nativeHatchChooser.offers(element, x, y, z, signal);
    }

    Object presetSharedKey(int x, int y, int z) {
        IStructureElement<?> element = elementAt(x, y, z);
        // Reviewed position-independent metadata permits caching; it never controls query eligibility.
        return reusablePreviewOptions(element) ? presetCandidates.sharedKey(element, triggerAt(x, y, z)) : null;
    }

    /** Registry snapshots are capture-owned. Only the exact-version GTSR policy may filter this view. */
    List<ItemStack> presetCatalog() {
        return items.catalog();
    }

    HologramCapture.Cell candidateCell(int x, int y, int z, String role) {
        HologramCapture.Cell cell = new HologramCapture.Cell(elementAt(x, y, z), x, y, z, triggerAt(x, y, z), true);
        cell.role = role;
        readCellCandidates(cell);
        return cell;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void readCellCandidates(HologramCapture.Cell cell) {
        IStructureElement element = cell.element;
        if (element == null) {
            cell.candidateBoundary = "此格由原生预览直接写入";
            return;
        }
        PreviewOptions cached = sharedOptions.get(element);
        if (cached != null) {
            cell.candidates = cached.candidates();
            cell.candidateComplete = cached.complete();
            cell.candidateBoundary = cached.boundary();
            cell.nativeCandidateCount = cached.nativeCount();
            if (cached.hatch()) cell.role = "hatch";
            return;
        }
        HologramChannelTrace.Scope trace = HologramChannelTrace.begin(context);
        long candidateStart = HologramRuntimeMetrics.start();
        try {
            controller.setWorldObj(world);
            boolean first = observedMaterials.put(element, Boolean.TRUE) == null;
            if (first) try {
                HologramDeclarationWrappers.observePreviewMaterials(element);
            } catch (RuntimeException | LinkageError unavailable) {
                // Optional channel/material metadata must not abort the exact cell's native offer query.
            }
            // Keep the settled controller's real native result, which may limit occupancy-dependent auto-fill.
            // The browser uses an unfilled representative for browsing, not its separately placed preview controller.
            IStructureElement.BlocksToPlace blocks = null;
            boolean chain = false;
            try {
                chain = isChain(element);
                blocks = element.getBlocksToPlace(
                    context,
                    emptyWorld,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.elementTrigger.copy(),
                    candidateEnvironment);
            } catch (RuntimeException | LinkageError unavailable) {
                // An opaque sibling must not suppress separately readable native hatch branches.
            }
            boolean settled = blocks != null;
            readCandidates(cell, blocks, chain, items::catalog, () -> hatchSupplements(cell, element, settled));
            boolean hatch;
            try {
                hatch = "hatch".equals(HologramElementCatalog.hatchRole(element));
            } catch (RuntimeException | LinkageError unavailable) {
                // A successfully appended native hatch offer can still drive this cell's manual chooser.
                hatch = cell.nativeCandidateCount >= 0;
            }
            if (!hatch) for (ItemStack candidate : cell.candidates)
                if (HologramCellPolicy.hatchTier(candidate) != Integer.MAX_VALUE) {
                    hatch = true;
                    break;
                }
            if (hatch) cell.role = "hatch";
            if (first && reusablePreviewOptions(element)) sharedOptions.put(
                element,
                new PreviewOptions(
                    cell.candidates,
                    cell.candidateComplete,
                    cell.candidateBoundary,
                    cell.nativeCandidateCount,
                    hatch));
        } catch (RuntimeException | LinkageError unavailable) {
            cell.candidateBoundary = "原生结构元素未提供可读取选项";
        } finally {
            controller.setWorldObj(emptyWorld);
            HologramChannelTrace.endInto(trace, channels);
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_CANDIDATES, candidateStart);
        }
    }

    private List<ItemStack> hatchSupplements(HologramCapture.Cell cell, IStructureElement<?> element, boolean settled) {
        List<ItemStack> normal = settled ? coldCandidates.offers(element) : Collections.emptyList();
        if (nativeHatchChooser == null) return normal;
        List<ItemStack> fresh = nativeHatchChooser.offers(element, cell.x, cell.y, cell.z, cell.elementTrigger);
        if (normal.isEmpty()) return fresh;
        if (fresh.isEmpty()) return normal;
        List<ItemStack> merged = new ArrayList<>(normal.size() + fresh.size());
        merged.addAll(normal);
        merged.addAll(fresh);
        return merged;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private HologramNativeHatchChooser.Query pristineHatchQuery(ControllerFactory factory) throws IOException {
        TileEntity pristine = factory.create();
        Object fresh = HologramStructureDiscovery.context(pristine);
        World pristineWorld = pristine.getWorldObj();
        if (pristine == controller || !(pristineWorld instanceof HologramResourceWorld)
            || pristineWorld == emptyWorld
            || pristineWorld == world
            || fresh == context
            || fresh == null
            || fresh.getClass() != context.getClass()) throw new IOException("LOCAL_NATIVE_HATCH_CONTEXT_UNAVAILABLE");
        AutoPlaceEnvironment environment = AutoPlaceEnvironment
            .fromLegacy(new HologramNativePreviewItems(), new PreviewPlayer(pristineWorld), msg -> {});
        return (element, x, y, z, sample) -> ((IStructureElement) element)
            .getBlocksToPlace(fresh, pristineWorld, x, y, z, sample, environment);
    }

    @FunctionalInterface
    interface ControllerFactory {

        TileEntity create() throws IOException;
    }

    /** Only a proven coordinate-independent native offer is eligible for shared material-row encoding. */
    boolean hasSharedOptions(IStructureElement<?> element) {
        return sharedOptions.containsKey(element);
    }

    private static boolean reusablePreviewOptions(IStructureElement<?> element) {
        try {
            return HologramDeclarationWrappers.reusablePreviewOptions(element, 8);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static String bounded(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }

    static boolean isChain(IStructureElement<?> element) {
        for (IStructureElement<?> part : HologramElementCatalog.parts(element))
            if (part instanceof IStructureElementChain) return true;
        return false;
    }

    static Predicate<ItemStack> coldHatches(IStructureElement<?> element) {
        try {
            return coldHatches(element, 8, new IdentityHashMap<>());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static Predicate<ItemStack> coldHatches(IStructureElement<?> element, int depth,
        IdentityHashMap<IStructureElement<?>, Boolean> seen) throws ReflectiveOperationException {
        if (element == null || depth == 0
            || seen.size() >= 128
            || seen.put(element, Boolean.TRUE) != null
            || HologramDeclarationAdapters.declaration(element) != null) return null;
        Predicate<ItemStack> known = HologramHatchPolicy.coldPreviewPredicate(element);
        if (known != null) return known;
        if (element instanceof IStructureElementChain<?>chain) {
            IStructureElement<?>[] children = HologramDeclarationWrappers.nativeChainChildren(chain);
            if (children == null || children.length > 64) return null;
            Predicate<ItemStack> result = null;
            for (IStructureElement<?> child : children) {
                Predicate<ItemStack> predicate = coldHatches(child, depth - 1, seen);
                if (predicate != null) result = result == null ? predicate : result.or(predicate);
            }
            return result;
        }
        var factory = HologramDeclarationWrappers.factory(element.getClass());
        if (factory == null || factory.getDeclaringClass() != StructureUtility.class) return null;
        // Native atLeast's matcher is trigger-invariant. Its transparent channel wrapper still runs unaltered
        // in the settled query above; never unroll a conditional, lazy, contextual, or extension wrapper.
        return switch (factory.getName()) {
            case "withChannel", "onElementPass", "onElementFail", "withDescription" -> coldHatches(
                HologramDeclarationWrappers.nativeBacking(element),
                depth - 1,
                seen);
            default -> null;
        };
    }

    static void readCandidates(HologramCapture.Cell cell, IStructureElement.BlocksToPlace blocks, boolean chain,
        Supplier<List<ItemStack>> catalog, Supplier<List<ItemStack>> coldOffers) {
        Map<HologramBlueprintResources.ExactKey, ItemStack> stacks = new LinkedHashMap<>();
        boolean complete = false;
        if (blocks != null) try {
            complete = readSettledOffers(stacks, blocks, chain, catalog);
        } catch (RuntimeException | LinkageError unavailable) {
            // A bad settled predicate still must not erase independently proved manual options or lift auto
            // eligibility.
            stacks.clear();
        }
        int nativeCount = stacks.size();
        if (nativeCount < MAX_CANDIDATES) for (ItemStack candidate : coldOffers.get()) {
            add(stacks, candidate);
            if (stacks.size() == MAX_CANDIDATES) break;
        }
        boolean supplemental = stacks.size() > nativeCount;
        cell.nativeCandidateCount = supplemental ? nativeCount : -1;
        cell.candidates = new ArrayList<>(stacks.values());
        cell.candidateComplete = complete && !supplemental;
        cell.candidateBoundary = supplemental ? "本地目录含空结构的仓室候选，不代表自动补仓"
            : complete ? "" : blocks == null ? "原生结构元素未提供可读取选项" : "选项来自本地物品目录";
    }

    private static boolean readSettledOffers(Map<HologramBlueprintResources.ExactKey, ItemStack> stacks,
        IStructureElement.BlocksToPlace blocks, boolean chain, Supplier<List<ItemStack>> catalog) {
        Iterable<ItemStack> listed = blocks.getStacks();
        // StructureLib chains can return a partial stack iterable alongside the full combined predicate.
        boolean complete = listed != null && !chain;
        if (listed != null) {
            int count = 0;
            for (ItemStack stack : listed) {
                if (count++ >= MAX_CANDIDATES || stack == null || stack.getItem() == null) {
                    complete = false;
                    break;
                }
                if (stack.getItemDamage() < 0 || stack.getItemDamage() == 32767) {
                    complete = false;
                    continue;
                }
                add(stacks, stack);
            }
        }
        if (!complete) {
            Predicate<ItemStack> predicate = blocks.getPredicate();
            if (predicate != null) for (ItemStack candidate : catalog.get()) {
                if (stacks.size() >= MAX_CANDIDATES) break;
                ItemStack sample = HologramNativePreviewItems.match(predicate, candidate);
                if (sample != null && sample.getItemDamage() >= 0 && sample.getItemDamage() != 32767)
                    add(stacks, sample);
                if (stacks.size() == MAX_CANDIDATES) break;
            }
        }
        return complete;
    }

    /** Capture-owned memo: only reviewed trigger-invariant native leaves can contribute, even through a wrapper. */
    static final class ColdCandidateCache {

        private final Supplier<List<ItemStack>> catalog;
        private final Map<IStructureElement<?>, List<ItemStack>> matches = new IdentityHashMap<>();

        ColdCandidateCache(Supplier<List<ItemStack>> catalog) {
            this.catalog = catalog;
        }

        List<ItemStack> offers(IStructureElement<?> element) {
            return matches.computeIfAbsent(element, this::read);
        }

        private List<ItemStack> read(IStructureElement<?> element) {
            Predicate<ItemStack> predicate = coldHatches(element);
            if (predicate == null) return Collections.emptyList();
            Map<HologramBlueprintResources.ExactKey, ItemStack> stacks = new LinkedHashMap<>();
            try {
                for (ItemStack candidate : catalog.get()) {
                    if (!(candidate.getItem() instanceof ItemMachines)) continue;
                    ItemStack sample = HologramNativePreviewItems.match(predicate, candidate);
                    if (sample != null && sample.getItemDamage() >= 0 && sample.getItemDamage() != 32767)
                        add(stacks, sample);
                    if (stacks.size() == MAX_CANDIDATES) break;
                }
            } catch (RuntimeException | LinkageError unavailable) {
                // The settled native offers remain usable if optional browsing evidence cannot be read.
            }
            return stacks.isEmpty() ? Collections.emptyList() : new ArrayList<>(stacks.values());
        }

        void clear() {
            matches.clear();
        }
    }

    /**
     * Geometry-independent automatic candidates shared by native and declared-source captures. Only context-free
     * pure leaves and the pinned stock cold builder rules are read; no native count/filter/adder is invoked.
     */
    static final class PresetCandidates {

        private final Supplier<List<ItemStack>> catalog;
        private final Map<PresetKey, List<ItemStack>> offers = new LinkedHashMap<>();
        private long policyGeneration = HologramHatchPolicy.automaticGeneration();
        private long adapterGeneration = HologramDeclarationAdapters.generation();

        PresetCandidates(Supplier<List<ItemStack>> catalog) {
            this.catalog = catalog;
        }

        private static final class PresetKey {

            private final IStructureElement<?> element;
            private final HologramBlueprintResources.ExactKey signal;
            private final int count;

            PresetKey(IStructureElement<?> element, HologramBlueprintResources.ExactKey signal, int count) {
                this.element = element;
                this.signal = signal;
                this.count = count;
            }

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof PresetKey key)) return false;
                return element == key.element && count == key.count && java.util.Objects.equals(signal, key.signal);
            }

            @Override
            public int hashCode() {
                return 31 * (31 * System.identityHashCode(element) + java.util.Objects.hashCode(signal)) + count;
            }
        }

        Object sharedKey(IStructureElement<?> element, ItemStack signal) {
            if (signal == null || signal.getItem() == null) return null;
            // All accepted primitives below are context-free. Include the full captured signal anyway: a caller
            // must never infer sharing merely because two pieces happened to reuse an element object.
            return new PresetKey(
                element,
                signal == null ? null : HologramValues.stackKey(signal),
                signal == null ? 0 : signal.stackSize);
        }

        List<ItemStack> offers(IStructureElement<?> element, ItemStack signal) {
            if (signal == null || signal.getItem() == null) return Collections.emptyList();
            long policy = HologramHatchPolicy.automaticGeneration(),
                adapters = HologramDeclarationAdapters.generation();
            if (policyGeneration != policy || adapterGeneration != adapters) {
                offers.clear();
                policyGeneration = policy;
                adapterGeneration = adapters;
            }
            PresetKey key = (PresetKey) sharedKey(element, signal);
            List<ItemStack> memo = offers.get(key);
            if (memo == null) {
                Map<HologramBlueprintResources.ExactKey, ItemStack> result = new LinkedHashMap<>();
                collect(element, signal, 0, new IdentityHashMap<>(), new int[] { 128 }, result, true);
                memo = new ArrayList<>(result.values());
                if (offers.size() < 128) offers.put(key, memo);
            }
            List<ItemStack> copy = new ArrayList<>(memo.size());
            for (ItemStack stack : memo) copy.add(stack.copy());
            return copy;
        }

        private void collect(IStructureElement<?> element, ItemStack signal, int depth,
            IdentityHashMap<IStructureElement<?>, Boolean> path, int[] work,
            Map<HologramBlueprintResources.ExactKey, ItemStack> result, boolean automatic) {
            if (element == null || depth > 12
                || work[0]-- <= 0
                || result.size() >= MAX_CANDIDATES
                || path.put(element, Boolean.TRUE) != null) return;
            try {
                if (element instanceof Declarations.PureElement<?>pure) {
                    int rows = 0;
                    for (ItemStack stack : pure.candidates()) {
                        if (++rows > MAX_CANDIDATES || result.size() >= MAX_CANDIDATES) break;
                        if (stack != null && stack.getItem() != null
                            && (!automatic || eligible(stack))
                            && pure.accepts(stack.copy())
                            && (!automatic || pure.placementAllows(stack.copy()))) add(result, stack);
                    }
                    return;
                }
                var declaration = HologramDeclarationAdapters.declaration(element);
                if (declaration != null) {
                    if (declaration.backingAuthority) collect(
                        declaration.backing(),
                        declaration.trigger(signal),
                        depth + 1,
                        path,
                        work,
                        result,
                        automatic);
                    return;
                }
                Predicate<ItemStack> predicate = automatic ? HologramHatchPolicy.automaticAtLeastPredicate(element)
                    : HologramHatchPolicy.structuralPredicate(element, null, signal);
                var single = automatic ? HologramHatchPolicy.singleNativePresetRole(element) : null;
                if (predicate != null || single != null) {
                    int rows = 0;
                    for (ItemStack stack : catalog.get()) {
                        if (++rows > 65_536 || result.size() >= MAX_CANDIDATES) break;
                        if (eligible(stack) && (predicate != null ? predicate.test(stack.copy())
                            : HologramHatchPolicy
                                .singleNativePresetMatches(single, ItemMachines.getMetaTileEntity(stack))))
                            add(result, stack);
                    }
                } else if (element instanceof IStructureElementChain<?>chain) {
                    var children = HologramDeclarationWrappers.nativeChainChildren(chain);
                    if (children != null && children.length <= 64)
                        for (var child : children) collect(child, signal, depth + 1, path, work, result, automatic);
                } else {
                    var factory = HologramDeclarationWrappers.factory(element.getClass());
                    if (factory == null || factory.getDeclaringClass() != StructureUtility.class) return;
                    switch (factory.getName()) {
                        case "withChannel", "onElementPass", "onElementFail", "withDescription" -> collect(
                            HologramDeclarationWrappers.nativeBacking(element),
                            HologramDeclarationWrappers.trigger(element, signal),
                            depth + 1,
                            path,
                            work,
                            result,
                            automatic);
                        default -> {}
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // Independent positive branches remain useful; opaque declarations grant no automatic candidates.
            } finally {
                path.remove(element);
            }
        }

        private static boolean eligible(ItemStack stack) {
            return stack != null && stack.getItem() != null
                && !stack.hasTagCompound()
                && HologramCellPolicy.hatchTier(stack) != Integer.MAX_VALUE;
        }

        List<ItemStack> manualOffers(IStructureElement<?> element, ItemStack signal) {
            if (signal == null || signal.getItem() == null) return Collections.emptyList();
            Map<HologramBlueprintResources.ExactKey, ItemStack> result = new LinkedHashMap<>();
            collect(element, signal, 0, new IdentityHashMap<>(), new int[] { 128 }, result, false);
            HologramCasingFallback.Target casing = HologramCasingFallback.resolve(element, signal);
            if (casing != null && result.size() < MAX_CANDIDATES) {
                ItemStack stack = HologramBlockIdentity.materialStack(casing.block, casing.meta);
                if (stack != null) add(result, stack);
            }
            return new ArrayList<>(result.values());
        }

        void clear() {
            offers.clear();
        }
    }

    private static void add(Map<HologramBlueprintResources.ExactKey, ItemStack> stacks, ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        stacks.putIfAbsent(HologramValues.stackKey(copy), copy);
    }

    @Override
    public void close() {
        closed = true;
        items.bind(null);
        if (construction) {
            memory.freeze();
            if (complement != null) complement.freeze();
        }
        // Cells keep their element/trigger metadata; target consumers may query native offers afterwards.
        if (!construction) complement = null;
        elements.clear();
        pieceTriggers.clear();
        if (sourceWater != null) sourceWater.clear();
        wideElements.clear();
        observedMaterials.clear();
        sharedOptions.clear();
        coldCandidates.clear();
        presetCandidates.clear();
        if (nativeHatchChooser != null) nativeHatchChooser.clear();
        placements = Collections.<HologramNativePreviewWorld.Placement>emptyList()
            .iterator();
    }

    @Desugar
    private record PreviewOptions(List<ItemStack> candidates, boolean complete, String boundary, int nativeCount,
        boolean hatch) {}

    @Desugar
    private record Position(int x, int y, int z) {}

    public static final class VisitedListener {

        @SubscribeEvent
        public void elementVisited(StructureElementVisitedEvent event) {
            HologramNativeStructurePreview capture = ACTIVE.get();
            if (capture != null) capture.elementVisited(event);
        }
    }

    private static final class PreviewPlayer extends EntityPlayer {

        PreviewPlayer(World world) {
            super(world, new GameProfile(UUID.fromString("1e62d1da-8b3b-45ec-8dd4-350a8616544b"), "[GTMB Preview]"));
        }

        @Override
        public void addChatMessage(IChatComponent message) {}

        @Override
        public void addStat(StatBase statistic, int amount) {}

        @Override
        public void openGui(Object mod, int id, World world, int x, int y, int z) {}

        @Override
        public void travelToDimension(int dimension) {}

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return false;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }
    }
}
