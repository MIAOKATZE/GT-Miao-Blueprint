package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

/** The chemical plant's complete private casing alternative, using only its own immutable tier registrations. */
public final class HologramGtppChemicalDeclarations {

    private HologramGtppChemicalDeclarations() {}

    public static void solidCasings(IStructureElement<?> element,
        Map<Integer, Triple<Block, Integer, Integer>> registry) {
        HologramDeclarationAdapters.register(element, new HologramDeclarationAdapters.Declaration(() -> {
            List<Pair<Block, Integer>> hints = new ArrayList<>();
            for (int tier = 0; tier < 8; tier++) {
                Triple<Block, Integer, Integer> casing = registry.get(tier);
                if (casing == null || casing.getLeft() == null) return null;
                hints.add(Pair.of(casing.getLeft(), casing.getMiddle()));
            }
            // Only the native complete union is registered: each private leaf accepts one fixed tier, but all
            // eight together accept the trigger-selected hint. No isolated leaf borrows the union's declaration.
            // Tier consistency/count checks remain native; this advisory twin grants no placement contract.
            return HologramGregTechDeclarations.tiered(hints);
        }, signal -> {
            if (signal.stackSize <= 0) throw new IllegalArgumentException("Chemical casing hint requires a positive tier");
            signal.stackSize = Math.min(signal.stackSize, 8);
            return signal;
        }, false));
    }
}
