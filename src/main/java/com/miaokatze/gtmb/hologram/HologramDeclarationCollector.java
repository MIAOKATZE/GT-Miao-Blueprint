package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.apache.commons.lang3.tuple.Triple;

import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Bounded declaration cache. Its owner provides read-only collection context, never a mutable service session. */
final class HologramDeclarationCollector {

    private final IdentityHashMap<IStructureElement<?>, Map<Triple<String, Integer, ExactKey>, HologramDeclarationCandidates>> candidates = new IdentityHashMap<>();
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
        Triple<String, Integer, ExactKey> key = Triple.of(context, signal.stackSize, HologramValues.lookupKey(signal));
        Map<Triple<String, Integer, ExactKey>, HologramDeclarationCandidates> signals = candidates
            .computeIfAbsent(element, ignored -> new HashMap<>());
        HologramDeclarationCandidates declaration = signals.get(key);
        if (declaration == null) {
            Triple<String, Integer, ExactKey> owned = Triple
                .of(context, signal.stackSize, HologramValues.stackKey(signal));
            declaration = new HologramDeclarationCandidates();
            declaration.collect(element, machine, world, x, y, z, signal, environment, 0);
            signals.put(owned, declaration);
            size++;
        }
        return declaration;
    }
}
