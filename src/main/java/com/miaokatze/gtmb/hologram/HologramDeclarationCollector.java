package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Bounded declaration cache. Its owner provides read-only collection context, never a mutable service session. */
final class HologramDeclarationCollector {

    private final IdentityHashMap<IStructureElement<?>, Map<String, HologramDeclarationCandidates>> candidates = new IdentityHashMap<>();
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
        String key = (contextFree ? "pure:" : x + "," + y + "," + z + ":" + structureId + ":") + signal.stackSize
            + ":"
            + signal.getTagCompound();
        Map<String, HologramDeclarationCandidates> signals = candidates
            .computeIfAbsent(element, ignored -> new HashMap<>());
        HologramDeclarationCandidates declaration = signals.get(key);
        if (declaration == null) {
            declaration = new HologramDeclarationCandidates();
            declaration.collect(element, machine, world, x, y, z, signal, environment, 0);
            signals.put(key, declaration);
            size++;
        }
        return declaration;
    }
}
