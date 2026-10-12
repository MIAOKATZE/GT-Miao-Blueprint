package com.miaokatze.gtmb.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real standard declarations grant variants only within their accepted registered identities. */
final class HologramRegisteredFamilyRegression {

    private HologramRegisteredFamilyRegression() {}

    static void run() {
        IStructureElement<Object> wildcard = StructureUtility.ofBlockAnyMeta(Blocks.wool, Blocks.wool, 0);
        HologramDeclarationCandidates variants = new HologramDeclarationCandidates();
        variants.collect(wildcard, null, null, 0, 64, 0, new ItemStack(Blocks.stone), null, 0);
        SimpleAssert.eq(16, variants.stacks.size(), "wildcard exposes all sixteen declared wool metadata states");
        SimpleAssert.that(!variants.complete, "inventory mappings never assert exhaustive world acceptance");
        HologramReplacementFamily.Family family = HologramReplacementFamily.resolve(wildcard, Blocks.wool, 15);
        SimpleAssert.that(
            family != null && family.canTarget(new ItemStack(Blocks.wool, 1, 7)),
            "exact wildcard factory resolves its own registered variant family");
        SimpleAssert.that(
            !family.canTarget(new ItemStack(Blocks.stone)),
            "wildcard block family never includes a different casing registry");
        Map<Block, Integer> states = new LinkedHashMap<>();
        states.put(Blocks.stone, 0);
        states.put(Blocks.dirt, 0);
        IStructureElement<Object> flat = StructureUtility.ofBlocksFlat(states, Blocks.stone, 0);
        HologramDeclarationCandidates finite = new HologramDeclarationCandidates();
        finite.collect(flat, null, null, 0, 64, 0, new ItemStack(Blocks.stone), null, 0);
        SimpleAssert.eq(2, finite.stacks.size(), "finite block map exposes exactly its accepted states");
        SimpleAssert.that(finite.complete, "exact finite standard declaration is complete");
        family = HologramReplacementFamily.resolve(flat, Blocks.stone, 0);
        SimpleAssert.that(
            family != null && family.canTarget(new ItemStack(Blocks.dirt)),
            "finite factory variants are a declaration-derived family");
        SimpleAssert.that(
            !family.canTarget(new ItemStack(Blocks.cobblestone)),
            "finite family rejects nearby undeclared casing identities");
        SimpleAssert.that(
            HologramReplacementFamily.resolve(StructureUtility.ofBlock(Blocks.wool, 0), Blocks.wool, 0) == null,
            "same registry alone never turns a fixed block into a replacement family");
        ItemStack one = new ItemStack(Blocks.wool, 1, 1), two = new ItemStack(Blocks.wool, 1, 2);
        SimpleAssert.that(
            !HologramBlockIdentity.key(one)
                .equals(HologramBlockIdentity.key(two)),
            "ordinary exact block identity distinguishes registered metadata");
    }
}
