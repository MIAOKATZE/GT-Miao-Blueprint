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
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.misc.GTStructureChannels;

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
    private final ISurvivalBuildEnvironment survivalEnvironment;
    private final AutoPlaceEnvironment candidateEnvironment;
    private final IConstructable constructable;
    private final INEIPreviewModifier modifier;
    private final boolean structureCompat;
    private final boolean gregTech;
    private final ItemStack trigger;
    private final NBTTagList description = new NBTTagList();
    private final Map<Position, IStructureElement<?>> elements = new HashMap<>();
    private final Map<IStructureElement<?>, Boolean> observedMaterials = new IdentityHashMap<>();
    private HologramChannelTrace.Report channels;
    private IStructureDefinition<?> definition;
    private Iterator<HologramNativePreviewWorld.Placement> placements;
    private boolean instrumented, done, closed, visitLimit;
    private int rounds;

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger) throws IOException {
        this.controller = controller;
        this.context = context;
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
                    channels = HologramChannelTrace.merge(channels, HologramChannelTrace.end(trace));
                }
            }
        } finally {
            controller.setWorldObj(emptyWorld);
        }
    }

    private void elementVisited(StructureElementVisitedEvent event) {
        if (event.getWorld() != world || Thread.currentThread() != owner
            || instrumented && event.getInstrumentIdentifier() != this) return;
        Position position = new Position(event.getX(), event.getY(), event.getZ());
        if (elements.size() >= HologramBlueprintLimits.MAX_CELLS && !elements.containsKey(position)) {
            visitLimit = true;
            return;
        }
        elements.put(position, event.getElement());
    }

    Iterator<HologramNativePreviewWorld.Placement> placements() {
        if (!done || closed) throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_SCOPE");
        return placements;
    }

    HologramChannelTrace.Report channels() {
        return channels;
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

    @SuppressWarnings({ "rawtypes", "unchecked" })
    HologramCapture.Cell cell(HologramNativePreviewWorld.Placement placement) {
        IStructureElement element = elements.get(new Position(placement.x(), placement.y(), placement.z()));
        HologramCapture.Cell cell = HologramCapture.nativePreviewCell(placement, element, trigger);
        if (element == null) {
            cell.candidateBoundary = "此格由原生预览直接写入";
            return cell;
        }
        HologramChannelTrace.Scope trace = HologramChannelTrace.begin(context);
        try {
            controller.setWorldObj(world);
            if (observedMaterials.put(element, Boolean.TRUE) == null)
                HologramDeclarationWrappers.observePreviewMaterials(element);
            // Like the multiblock browser, inspect the final visited element against an empty detached world.
            IStructureElement.BlocksToPlace blocks = element.getBlocksToPlace(
                context, emptyWorld, cell.x, cell.y, cell.z, trigger.copy(), candidateEnvironment);
            if (blocks != null) readCandidates(cell, blocks, isChain(element));
            if ("hatch".equals(HologramElementCatalog.hatchRole(element))) cell.role = "hatch";
            readElementDescription(cell, element);
        } catch (RuntimeException | LinkageError unavailable) {
            cell.candidateBoundary = "原生结构元素未提供可读取选项";
        } finally {
            controller.setWorldObj(emptyWorld);
            channels = HologramChannelTrace.merge(channels, HologramChannelTrace.end(trace));
        }
        return cell;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void readElementDescription(HologramCapture.Cell cell, IStructureElement element) {
        try {
            List<String> lines = element.getDescription(context);
            if (lines != null && !lines.isEmpty())
                cell.prompt = bounded(String.join("\n", lines.subList(0, Math.min(8, lines.size()))), 512);
        } catch (RuntimeException | LinkageError unavailable) {
            // A missing description leaves the independently observed candidates available.
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

    private void readCandidates(HologramCapture.Cell cell, IStructureElement.BlocksToPlace blocks, boolean chain) {
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
            if (predicate != null) for (ItemStack candidate : items.catalog()) {
                ItemStack sample = HologramNativePreviewItems.match(predicate, candidate);
                if (sample != null && sample.getItemDamage() >= 0 && sample.getItemDamage() != 32767) add(stacks, sample);
                if (stacks.size() == MAX_CANDIDATES) break;
            }
        }
        cell.candidates = new ArrayList<>(stacks.values());
        cell.candidateComplete = complete;
        cell.candidateBoundary = complete ? "" : "选项来自本地物品目录";
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
        observedMaterials.clear();
        placements = Collections.<HologramNativePreviewWorld.Placement>emptyList().iterator();
    }

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
