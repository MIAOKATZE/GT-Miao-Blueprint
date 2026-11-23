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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
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

    private final Thread owner = Thread.currentThread();
    private final TileEntity controller;
    private final World emptyWorld, world;
    private final Object context;
    private final HologramNativePreviewWorld memory;
    private final HologramBlockRendererPreview blockRenderer;
    private final HologramNativePreviewItems items = new HologramNativePreviewItems();
    private final ColdCandidateCache coldCandidates = new ColdCandidateCache(items::catalog);
    private final ISurvivalBuildEnvironment survivalEnvironment;
    private final AutoPlaceEnvironment candidateEnvironment;
    private final IConstructable constructable;
    private final INEIPreviewModifier modifier;
    private final boolean structureCompat;
    private final boolean gregTech;
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

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger) throws IOException {
        this.controller = controller;
        this.context = context;
        channels = new HologramChannelTrace.Accumulator(context);
        this.constructable = constructable;
        this.structureCompat = structureCompat;
        this.trigger = trigger.copy();
        emptyWorld = controller.getWorldObj();
        gregTech = controller instanceof IGregTechTileEntity;
        modifier = gregTech && context instanceof INEIPreviewModifier supplied ? supplied : null;
        if (HologramBlockRendererPreview.required(context)) {
            // Only this implementation requires BlockRenderer's class identity. Other controllers use our memory world.
            blockRenderer = new HologramBlockRendererPreview(controller);
            memory = null;
            world = blockRenderer.world;
        } else {
            memory = HologramNativePreviewWorld.open(controller);
            blockRenderer = null;
            world = memory;
        }
        PreviewPlayer actor = new PreviewPlayer(world);
        survivalEnvironment = ISurvivalBuildEnvironment.create(items, actor);
        candidateEnvironment = AutoPlaceEnvironment.fromLegacy(items, actor, message -> {});
        if (gregTech) ChannelDataAccessor.setChannelData(this.trigger, HATCH, 1);
        // Do not leave the detached controller in an active preview world between client ticks.
        controller.setWorldObj(emptyWorld);
    }

    public static void install() {
        MinecraftForge.EVENT_BUS.register(LISTENER);
    }

    /** Each placement pass is atomic. Additional native passes can run on following client ticks. */
    boolean advance(long deadline) throws IOException {
        if (closed || done || Thread.currentThread() != owner) throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_SCOPE");
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
                ACTIVE.set(this);
                if (rounds == 0 && modifier != null) modifier.onPreviewConstruct(trigger);
                boolean repeat = false;
                do {
                    repeat = false;
                    long passStart = HologramRuntimeMetrics.start();
                    try {
                        if (constructable instanceof ISurvivalConstructable survival) {
                            int result = survival.survivalConstruct(trigger, Integer.MAX_VALUE, survivalEnvironment);
                            rounds++;
                            if (structureCompat && result == -2) {
                                constructable.construct(trigger, false);
                                break;
                            }
                            if (memory == null ? blockRenderer.takeChanged() : memory.takeChanged()) {
                                if (rounds >= MAX_ROUNDS) throw new IOException("LOCAL_NATIVE_PREVIEW_UNSTABLE");
                                repeat = true;
                                continue;
                            }
                        } else {
                            constructable.construct(trigger, false);
                            rounds++;
                        }
                    } finally {
                        HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_NATIVE_PASSES, passStart);
                    }
                    break;
                } while (System.nanoTime() < deadline);
                if (visitLimit) throw new IOException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
                // A pass whose writes have been consumed still needs a subsequent pass to establish stability.
                if (repeat) return false;
                if (modifier != null) modifier.onPreviewStructureComplete(trigger);
                if (visitLimit) throw new IOException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
                placements = memory == null ? blockRenderer.placements(controller) : memory.placements();
                if (!placements.hasNext()) throw new IOException("LOCAL_NATIVE_PREVIEW_EMPTY");
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

    private void elementVisited(StructureElementVisitedEvent event) {
        if (event.getWorld() != world || Thread.currentThread() != owner
            || instrumented && event.getInstrumentIdentifier() != this) return;
        int x = event.getX(), y = event.getY(), z = event.getZ();
        boolean atLimit = elements.size() + wideElements.size() >= HologramBlueprintLimits.MAX_CELLS;
        if (HologramNativePreviewWorld.packable(x, y, z)) {
            long position = CoordinatePacker.pack(x, y, z);
            if (atLimit && !elements.containsKey(position)) visitLimit = true;
            else elements.put(position, event.getElement());
        } else {
            // A custom declaration can emit visits outside the preview world's placement bounds.
            Position position = new Position(x, y, z);
            if (atLimit && !wideElements.containsKey(position)) visitLimit = true;
            else wideElements.put(position, event.getElement());
        }
    }

    private IStructureElement<?> elementAt(int x, int y, int z) {
        return HologramNativePreviewWorld.packable(x, y, z) ? elements.get(CoordinatePacker.pack(x, y, z))
            : wideElements.get(new Position(x, y, z));
    }

    Iterator<HologramNativePreviewWorld.Placement> placements() {
        if (!done || closed) throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_SCOPE");
        return placements;
    }

    HologramChannelTrace.Report channels() {
        return channels.snapshot();
    }

    IStructureDefinition<?> definition() {
        return definition;
    }

    NBTTagList description() {
        return description;
    }

    private void readDescription() {
        try {
            definition = constructable.getStructureDefinition();
        } catch (RuntimeException | LinkageError unavailable) {
            // A legacy declaration can expose its layout through construction alone.
        }
        try {
            String[] lines = constructable.getStructureDescription(trigger.copy());
            if (lines == null) return;
            for (int i = 0; i < Math.min(32, lines.length); i++) {
                if (lines[i] != null) description.appendTag(new NBTTagString(bounded(lines[i], 512)));
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // The geometry remains available when an optional tooltip provider is absent.
        }
    }

    HologramCapture.Cell targetCell(HologramNativePreviewWorld.Placement placement) {
        return HologramCapture.nativePreviewCell(placement, null, trigger);
    }

    HologramCapture.Cell cell(HologramNativePreviewWorld.Placement placement) {
        IStructureElement<?> element = elementAt(placement.x(), placement.y(), placement.z());
        HologramCapture.Cell cell = HologramCapture.nativePreviewCell(placement, element, trigger);
        readCellCandidates(cell);
        return cell;
    }

    HologramCapture.Cell candidateCell(int x, int y, int z, String role) {
        HologramCapture.Cell cell = new HologramCapture.Cell(elementAt(x, y, z), x, y, z, trigger, true);
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
            if (first)
                HologramDeclarationWrappers.observePreviewMaterials(element);
            // Keep the settled controller's real native result, which may limit occupancy-dependent auto-fill.
            // The browser uses an unfilled representative for browsing, not its separately placed preview controller.
            IStructureElement.BlocksToPlace blocks = element.getBlocksToPlace(
                context, emptyWorld, cell.x, cell.y, cell.z, trigger.copy(), candidateEnvironment);
            if (blocks != null) readCandidates(cell, blocks, isChain(element), items::catalog,
                () -> coldCandidates.offers(element));
            boolean hatch = "hatch".equals(HologramElementCatalog.hatchRole(element));
            if (hatch) cell.role = "hatch";
            if (first && reusablePreviewOptions(element)) sharedOptions.put(
                element,
                new PreviewOptions(cell.candidates, cell.candidateComplete, cell.candidateBoundary,
                    cell.nativeCandidateCount, hatch));
        } catch (RuntimeException | LinkageError unavailable) {
            cell.candidateBoundary = "原生结构元素未提供可读取选项";
        } finally {
            controller.setWorldObj(emptyWorld);
            HologramChannelTrace.endInto(trace, channels);
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_CANDIDATES, candidateStart);
        }
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

    private static boolean isChain(IStructureElement<?> element) {
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
        if (element == null || depth == 0 || seen.size() >= 128 || seen.put(element, Boolean.TRUE) != null
            || HologramDeclarationAdapters.declaration(element) != null) return null;
        Predicate<ItemStack> known = HologramHatchPolicy.coldPreviewPredicate(element);
        if (known != null) return known;
        if (element instanceof IStructureElementChain<?> chain) {
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
            case "withChannel", "onElementPass", "onElementFail", "withDescription" ->
                coldHatches(HologramDeclarationWrappers.nativeBacking(element), depth - 1, seen);
            default -> null;
        };
    }

    static void readCandidates(HologramCapture.Cell cell, IStructureElement.BlocksToPlace blocks, boolean chain,
        Supplier<List<ItemStack>> catalog, Supplier<List<ItemStack>> coldOffers) {
        Map<HologramBlueprintResources.ExactKey, ItemStack> stacks = new LinkedHashMap<>();
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
                if (sample != null && sample.getItemDamage() >= 0 && sample.getItemDamage() != 32767) add(stacks, sample);
                if (stacks.size() == MAX_CANDIDATES) break;
            }
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
        cell.candidateBoundary = supplemental ? "本地目录含空结构的仓室候选，不代表自动补仓" : complete ? "" : "选项来自本地物品目录";
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
                    if (sample != null && sample.getItemDamage() >= 0 && sample.getItemDamage() != 32767) add(stacks, sample);
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

    private static void add(Map<HologramBlueprintResources.ExactKey, ItemStack> stacks, ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        stacks.putIfAbsent(HologramValues.stackKey(copy), copy);
    }

    @Override
    public void close() {
        closed = true;
        elements.clear();
        wideElements.clear();
        observedMaterials.clear();
        sharedOptions.clear();
        coldCandidates.clear();
        placements = Collections.<HologramNativePreviewWorld.Placement>emptyList().iterator();
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
