// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.GregTechAPI;
import gregtech.api.util.CasingTier;
import gregtech.api.util.GlassTier;

/** Declared GT factory registries, rather than a list of community machines or every registered block. */
public final class HologramGregTechDeclarations {

    private HologramGregTechDeclarations() {}

    public static void glass(IStructureElement<?> element) {
        HologramDeclarationAdapters.register(
            element,
            new HologramDeclarationAdapters.Declaration(() -> tiered("glass", GlassTier.getGlassList()), signal -> {
                signal.stackSize = Math.min(signal.stackSize, GlassTier.getMaxTierIndex());
                return signal;
            }));
    }

    public static void casings(IStructureElement<?> element) {
        HologramDeclarationAdapters.register(
            element,
            new HologramDeclarationAdapters.Declaration(() -> tiered("casing", CasingTier.getCasingList()), null));
    }

    public static void itemPipes(IStructureElement<?> element) {
        HologramDeclarationAdapters.register(element, new HologramDeclarationAdapters.Declaration(() -> {
            if (GregTechAPI.sBlockCasings11 == null) return null;
            List<Pair<Block, Integer>> hints = new ArrayList<>();
            // This is the exact standard chainItemPipeCasings declaration, not a registry metadata scan.
            for (int meta = 0; meta <= 7; meta++) hints.add(Pair.of(GregTechAPI.sBlockCasings11, meta));
            return tiered("item_pipe", hints);
        }, null));
    }

    /** GT++'s common factory fixes the block/range; its getter/setter still enforce machine-wide tier consistency. */
    public static void tieredBlock(IStructureElement<?> element, Block block, int minMeta, int maxMeta) {
        if (block == null || maxMeta <= minMeta || (long) maxMeta - minMeta > 128) return;
        HologramDeclarationAdapters.register(element, new HologramDeclarationAdapters.Declaration(() -> {
            List<Pair<Block, Integer>> hints = new ArrayList<>();
            for (int meta = minMeta; meta < maxMeta; meta++) hints.add(Pair.of(block, meta));
            return tiered(hints);
        }, signal -> {
            // Preserve the reviewed native getMeta's exact integer branches, including its nonzero minimum.
            int meta = signal.stackSize;
            if (meta <= 0) meta = minMeta;
            if (meta + minMeta >= maxMeta) meta = maxMeta - 1 - minMeta;
            meta += minMeta;
            if (meta < minMeta || meta >= maxMeta)
                throw new IllegalArgumentException("GT++ tiered block hint is outside its declared metadata range");
            signal.stackSize = meta - minMeta + 1;
            return signal;
        }, false));
    }

    private static IStructureElement<?> tiered(String channel, List<Pair<Block, Integer>> declared) {
        IStructureElement<?> backing = tiered(declared);
        return backing == null ? null : StructureUtility.withChannel(channel, backing);
    }

    static IStructureElement<?> tiered(List<Pair<Block, Integer>> declared) {
        if (declared == null || declared.isEmpty()) return null;
        List<Pair<Block, Integer>> snapshot = new ArrayList<>(declared);
        // The declaration twin contains no live controller setter/getter. Its hints/candidates are the factory's
        // actual declared alternatives; arbitrary tier extractors are never evaluated by capture.
        IStructureElement<Object> backing = StructureUtility
            .<Object, Integer>ofBlocksTiered((block, meta) -> null, snapshot, -1, (context, tier) -> {}, context -> -1);
        return backing;
    }
}
