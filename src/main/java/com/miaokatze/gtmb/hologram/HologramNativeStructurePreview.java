// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Compatibility boundary for capture/render consumers; generation belongs to the projector simulation. */
public final class HologramNativeStructurePreview implements AutoCloseable {

    private static final int MAX_CANDIDATES = 128;
    private final HologramProjectorSimulation simulation;

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger) throws IOException {
        this(controller, context, constructable, structureCompat, trigger, null, null);
    }

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger, ControllerFactory factory) throws IOException {
        this(controller, context, constructable, structureCompat, trigger, factory, null);
    }

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger, ControllerFactory factory,
        com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing facing) throws IOException {
        this(controller, context, constructable, structureCompat, trigger, factory, facing, false);
    }

    HologramNativeStructurePreview(TileEntity controller, Object context, IConstructable constructable,
        boolean structureCompat, ItemStack trigger, ControllerFactory factory,
        com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing facing, boolean construction)
        throws IOException {
        simulation = new HologramProjectorSimulation(
            controller,
            context,
            constructable,
            trigger,
            factory,
            facing,
            construction);
    }

    public static void install() {
        HologramProjectorSimulation.install();
    }

    boolean advance(long deadline) throws IOException {
        return simulation.step(deadline);
    }

    void configure(net.minecraft.nbt.NBTTagCompound settings) {
        simulation.configure(settings);
    }

    static void piece(IStructureDefinition<?> definition, Object context, ItemStack signal, World world) {
        HologramProjectorSimulation.piece(definition, context, signal, world);
    }

    static void piece(Object context, ItemStack signal) {
        HologramProjectorSimulation.piece(null, context, signal, null);
    }

    static boolean consumesHint(World world) {
        return HologramProjectorSimulation.consumesHint(world);
    }

    HologramNativePreviewWorld targetWorld() {
        return simulation.world;
    }

    TileEntity targetController() {
        return simulation.controller;
    }

    Object targetContext() {
        return simulation.context;
    }

    boolean checkTargetStructure() {
        return simulation.checkStructure();
    }

    int passes() {
        return simulation.rounds;
    }

    int nativeResult() {
        return simulation.nativeResult;
    }

    int extraDisplayCells() {
        return 0;
    }

    boolean optionalWaterAt(int x, int y, int z) {
        return simulation.optionalWater.contains(new HologramProjectorSimulation.Position(x, y, z));
    }

    java.util.Set<HologramNativeTargetPlan.Offset> visitedOffsets() {
        java.util.Set<HologramNativeTargetPlan.Offset> result = new java.util.LinkedHashSet<>();
        for (var p : simulation.elements.keySet()) result.add(
            new HologramNativeTargetPlan.Offset(
                p.x() - simulation.controller.xCoord,
                p.y() - simulation.controller.yCoord,
                p.z() - simulation.controller.zCoord));
        return result;
    }

    Iterator<HologramProjectorSimulation.Position> visitedPositions() {
        return simulation.elements.keySet()
            .iterator();
    }

    int facingMask(int x, int y, int z, ItemStack sample) {
        World previous = simulation.controller.getWorldObj();
        boolean recording = simulation.world.edit(true);
        simulation.controller.setWorldObj(simulation.world);
        boolean updates = gregtech.api.threads.RunnableMachineUpdate.isCurrentThreadEnabled();
        try {
            if (updates) gregtech.api.threads.RunnableMachineUpdate.setCurrentThreadEnabled(false);
            return simulation.probe.facingMask(new HologramProjectorSimulation.Position(x, y, z), sample);
        } finally {
            simulation.world.edit(recording);
            simulation.controller.setWorldObj(previous);
            if (updates) gregtech.api.threads.RunnableMachineUpdate.setCurrentThreadEnabled(true);
        }
    }

    IStructureElement<?> elementAt(int x, int y, int z) {
        return simulation.elements.get(new HologramProjectorSimulation.Position(x, y, z));
    }

    ItemStack triggerAt(int x, int y, int z) {
        return simulation.signalAt(new HologramProjectorSimulation.Position(x, y, z));
    }

    Iterator<HologramNativePreviewWorld.Placement> placements() {
        return simulation.world.placements();
    }

    HologramChannelTrace.Report channels() {
        return simulation.channels;
    }

    IStructureDefinition<?> definition() {
        return simulation.definition;
    }

    NBTTagList description() {
        return simulation.description;
    }

    HologramCapture.Cell targetCell(HologramNativePreviewWorld.Placement p) {
        return HologramCapture.nativePreviewCell(p, elementAt(p.x(), p.y(), p.z()), triggerAt(p.x(), p.y(), p.z()));
    }

    HologramCapture.Cell cell(HologramNativePreviewWorld.Placement p) {
        HologramCapture.Cell cell = targetCell(p);
        World previous = simulation.controller.getWorldObj();
        simulation.controller.setWorldObj(simulation.world);
        try {
            readCandidates(
                cell,
                simulation.probe.query(
                    cell.element,
                    new HologramProjectorSimulation.Position(p.x(), p.y(), p.z()),
                    cell.elementTrigger),
                isChain(cell.element),
                simulation.items::catalog,
                Collections::emptyList);
        } finally {
            simulation.controller.setWorldObj(previous);
        }
        return cell;
    }

    HologramCapture.Cell candidateCell(int x, int y, int z, String role) {
        HologramCapture.Cell cell = new HologramCapture.Cell(elementAt(x, y, z), x, y, z, triggerAt(x, y, z), true);
        cell.role = role;
        cell.candidates = nativeOffers(cell.element, x, y, z, cell.elementTrigger);
        return cell;
    }

    List<ItemStack> nativeOffers(IStructureElement<?> element, int x, int y, int z, ItemStack signal) {
        World previous = simulation.controller.getWorldObj();
        boolean recording = simulation.world.edit(true);
        boolean updates = gregtech.api.threads.RunnableMachineUpdate.isCurrentThreadEnabled();
        simulation.controller.setWorldObj(simulation.world);
        try {
            if (updates) gregtech.api.threads.RunnableMachineUpdate.setCurrentThreadEnabled(false);
            return simulation.probe.offers(element, new HologramProjectorSimulation.Position(x, y, z), signal);
        } finally {
            simulation.world.edit(recording);
            if (updates) gregtech.api.threads.RunnableMachineUpdate.setCurrentThreadEnabled(true);
            simulation.controller.setWorldObj(previous);
        }
    }

    @FunctionalInterface
    interface ControllerFactory {

        TileEntity create() throws IOException;
    }

    public static final class VisitedListener {

        @SubscribeEvent
        public void elementVisited(StructureElementVisitedEvent event) {
            HologramProjectorSimulation.visited(event);
        }
    }

    static boolean isChain(IStructureElement<?> element) {
        for (IStructureElement<?> part : HologramElementCatalog.parts(element))
            if (part instanceof IStructureElementChain) return true;
        return false;
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

    private static void add(Map<HologramBlueprintResources.ExactKey, ItemStack> stacks, ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        stacks.putIfAbsent(HologramValues.stackKey(copy), copy);
    }

    @Override
    public void close() {
        simulation.close();
    }
}
