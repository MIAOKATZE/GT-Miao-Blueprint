package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.apache.commons.lang3.tuple.Triple;

import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Bounded declaration cache. Its owner provides read-only collection context, never a mutable service session. */
final class HologramDeclarationCollector {

    private final IdentityHashMap<IStructureElement<?>, Map<Triple<Object, Integer, ExactKey>, HologramDeclarationCandidates>> candidates = new IdentityHashMap<>();
    private final IdentityHashMap<IStructureElement<?>, Boolean> pure = new IdentityHashMap<>();
    private int size;
    private long generation = HologramDeclarationAdapters.generation();

    void clear() {
        candidates.clear();
        pure.clear();
        size = 0;
    }

    boolean invalidateIfStale() {
        long current = HologramDeclarationAdapters.generation();
        if (generation == current) return false;
        clear();
        generation = current;
        return true;
    }

    HologramDeclarationCandidates collect(IStructureElement<?> element, MTEEnhancedMultiBlockBase<?> machine,
        World world, int x, int y, int z, String structureId, ItemStack signal, AutoPlaceEnvironment environment) {
        invalidateIfStale();
        if (size >= 1024) clear();
        boolean contextFree = pure.computeIfAbsent(element, HologramDeclarationPurity::contextFree);
        String context = contextFree ? "pure:" : x + "," + y + "," + z + ":" + structureId + ":";
        HologramDeclarationCandidates declaration = find(element, context, signal);
        if (declaration == null) {
            declaration = new HologramDeclarationCandidates(this);
            declaration.collect(element, machine, world, x, y, z, signal, environment, 0);
            remember(element, context, signal, declaration);
        }
        return declaration;
    }

    /** Only the recorded standard hatch enum rule is shared; contextual chains retain their own entries. */
    HologramDeclarationCandidates hatches(IStructureElement<?> element, ItemStack signal,
        Predicate<ItemStack> declared) {
        Object context = HologramHatchPolicy.declared(element);
        HologramDeclarationCandidates declaration = find(element, context, signal);
        if (declaration == null) {
            declaration = new HologramDeclarationCandidates();
            declaration.collectHatches(declared);
            remember(element, context, signal, declaration);
        }
        return declaration;
    }

    private HologramDeclarationCandidates find(IStructureElement<?> element, Object context, ItemStack signal) {
        Map<Triple<Object, Integer, ExactKey>, HologramDeclarationCandidates> signals = candidates.get(element);
        return signals == null ? null
            : signals.get(Triple.of(context, signal.stackSize, HologramValues.lookupKey(signal)));
    }

    private void remember(IStructureElement<?> element, Object context, ItemStack signal,
        HologramDeclarationCandidates declaration) {
        // Recursive leaf collection also consumes this budget and may evict its parent's previous map.
        if (size >= 1024) clear();
        candidates.computeIfAbsent(element, ignored -> new HashMap<>())
            .put(Triple.of(context, signal.stackSize, HologramValues.stackKey(signal)), declaration);
        size++;
    }
}
