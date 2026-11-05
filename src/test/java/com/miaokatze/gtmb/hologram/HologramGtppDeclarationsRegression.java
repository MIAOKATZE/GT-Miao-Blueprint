package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.apache.commons.lang3.tuple.Triple;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.IStructureElementNoPlacement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.GTPPMultiBlockBase;

/** Reviewed GT++ data projections retain their native hint selection without borrowing placement authority. */
final class HologramGtppDeclarationsRegression {

    private HologramGtppDeclarationsRegression() {}

    static void run() {
        try {
            tieredBlocks();
            chemicalUnion();
            existingAuthority();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("GT++ declaration projection", failure);
        }
    }

    private static void tieredBlocks() {
        for (int[] range : new int[][] { { 0, 10 }, { 12, 16 } }) {
            IStructureElement<Object> element = GTPPMultiBlockBase.<Object>addTieredBlock(
                Blocks.stone,
                (java.util.function.BiPredicate<Object, Integer>) (controller, meta) -> {
                    throw new AssertionError("tier setter executed");
                },
                controller -> { throw new AssertionError("tier getter executed"); },
                range[0],
                range[1]);
            ItemStack first = new ItemStack(Items.feather, 1);
            SimpleAssert.that(
                HologramCasingFallback.resolve(element, first) == null,
                "unregistered GT++ factory has no inferred material authority");
            // A headless run has no Mixins; forward the real factory result through the exact RETURN hook.
            HologramGregTechDeclarations.tieredBlock(element, Blocks.stone, range[0], range[1]);
            for (int count : new int[] { 0, 1, 2, 3, 8, 64 }) {
                ItemStack signal = new ItemStack(Items.feather, count);
                signal.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
                signal.getTagCompound()
                    .setString("custom", "retained");
                // This exact reviewed native method only computes the constant block and getMeta(trigger).
                ItemStack expected = element.getBlocksToPlace(null, null, 0, 0, 0, signal, null)
                    .getStacks()
                    .iterator()
                    .next();
                HologramCasingFallback.Target target = HologramCasingFallback.resolve(element, signal);
                SimpleAssert
                    .that(target != null && target.block == Blocks.stone, "tiered native hint has a declared block");
                SimpleAssert
                    .eq(expected.getItemDamage(), target.meta, "projected metadata equals the native getMeta branches");
                SimpleAssert.eq(count, signal.stackSize, "projection does not mutate the original trigger");
                SimpleAssert.eq(
                    "retained",
                    signal.getTagCompound()
                        .getString("custom"),
                    "full trigger NBT survives projection");
                HologramDeclarationCandidates candidates = candidates(element, signal);
                SimpleAssert.eq(
                    range[1] - range[0],
                    candidates.stacks.size(),
                    "only the factory's literal metadata range is displayed");
                SimpleAssert.that(
                    !candidates.complete,
                    "machine-wide getter/setter tier consistency is not certified by hints");
                SimpleAssert.that(
                    !candidates.allows(expected, false) && !candidates.allows(expected, true),
                    "new descriptor candidates grant no explicit or automatic placement contract");
            }
            if (range[0] == 12) SimpleAssert.that(
                HologramCasingFallback.resolve(element, new ItemStack(Items.feather, Integer.MAX_VALUE)) == null,
                "native integer overflow is rejected, not silently clamped to a different valid material");
            HologramChannelTrace.Report report = channels(element);
            SimpleAssert.that(
                report.observations.containsKey("gtpp_tier") && report.materials.isEmpty(),
                "advisory hints preserve raw channel observations without inventing an inverse material mapping");
            SimpleAssert.eq(
                1,
                report.observations.get("gtpp_tier").effective,
                "the observed channel is not the transformed hint-list index");
            net.minecraft.nbt.NBTTagList capabilities = HologramCapabilities.describe(report.context, first, report)
                .toNBT();
            for (int row = 0; row < capabilities.tagCount(); row++) if ("gtpp_tier".equals(
                capabilities.getCompoundTagAt(row)
                    .getString("id")))
                SimpleAssert.eq(
                    "observed",
                    capabilities.getCompoundTagAt(row)
                        .getString("kind"),
                    "a noninvertible hint transform remains a raw integer channel, not a calibration option");
        }
    }

    private static void chemicalUnion() throws ReflectiveOperationException {
        Map<Integer, Triple<Block, Integer, Integer>> registry = new HashMap<>();
        List<IStructureElement<Object>> leaves = new ArrayList<>();
        for (int tier = 0; tier < 8; tier++) {
            registry.put(tier, Triple.of(Blocks.stone, tier, tier + 16));
            leaves.add(unknown());
        }
        IStructureElementChain<Object> union = StructureUtility.ofChain(leaves);
        ItemStack signal = new ItemStack(Items.feather, 4);
        SimpleAssert.that(
            HologramCasingFallback.resolve(union, signal) == null,
            "private casing leaves are not guessed from their surrounding machine");
        // Only the exact complete native eight-tier alternative is registered by the Chemical getDefinition hook.
        HologramGtppChemicalDeclarations.solidCasings(union, registry);
        for (int count : new int[] { 1, 4, 8, 64 }) {
            signal.stackSize = count;
            HologramCasingFallback.Target target = HologramCasingFallback.resolve(union, signal);
            SimpleAssert.that(
                target != null && target.block == Blocks.stone,
                "the complete union resolves its trigger-selected casing hint");
            SimpleAssert.eq(Math.min(count, 8) - 1, target.meta, "chemical casing uses its native tier index");
            HologramDeclarationCandidates candidates = candidates(union, signal);
            SimpleAssert.that(
                !candidates.complete && candidates.stacks.isEmpty(),
                "native chain-first candidate semantics remain incomplete for the private leaves");
            SimpleAssert.that(
                !candidates.allows(new ItemStack(Blocks.stone, 1, target.meta), false),
                "the descriptive whole-chain backing grants no leaf placement authority");
        }
        for (IStructureElement<Object> leaf : leaves) SimpleAssert.that(
            HologramDeclarationWrappers.backing(leaf) == null && HologramCasingFallback.resolve(leaf, signal) == null,
            "an isolated native leaf never borrows the complete union's interpretation");
        SimpleAssert.that(
            HologramCasingFallback.resolve(StructureUtility.ofChain(leaves.subList(0, 7)), signal) == null,
            "a partial private casing alternative stays unresolved");
        SimpleAssert.that(
            HologramCasingFallback.resolve(StructureUtility.ofChain(leaves.get(0), unknown()), signal) == null,
            "an arbitrary mixed chain stays unresolved");
        IStructureElementChain<Object> missing = StructureUtility.ofChain(new ArrayList<>(leaves));
        registry.remove(7);
        HologramGtppChemicalDeclarations.solidCasings(missing, registry);
        SimpleAssert.that(
            HologramCasingFallback.resolve(missing, signal) == null,
            "missing declared casing tiers are not substituted from the world");
    }

    private static void existingAuthority() {
        IStructureElement<Object> element = unknown();
        HologramDeclarationAdapters
            .register(element, new HologramDeclarationAdapters.Declaration(StructureUtility.ofBlock(Blocks.stone, 0)));
        HologramDeclarationCandidates candidates = candidates(element, new ItemStack(Items.feather));
        SimpleAssert.that(
            candidates.complete && candidates.allows(new ItemStack(Blocks.stone), false)
                && candidates.allows(new ItemStack(Blocks.stone), true),
            "existing public declaration constructors retain their placement semantics");
        HologramDeclarationAdapters.Declaration glass = new HologramDeclarationAdapters.Declaration(() -> null, null);
        SimpleAssert
            .that(glass.backingAuthority, "the existing GT glass/casing constructor path keeps its authority default");
        IStructureElement<?> nativeTiered = HologramGregTechDeclarations.tiered(
            java.util.Arrays.asList(
                org.apache.commons.lang3.tuple.Pair.of(Blocks.stone, 0),
                org.apache.commons.lang3.tuple.Pair.of(Blocks.stone, 1)));
        IStructureElement<Object> oldProjection = unknown();
        HologramDeclarationAdapters.register(oldProjection, new HologramDeclarationAdapters.Declaration(nativeTiered));
        SimpleAssert.that(
            candidates(oldProjection, new ItemStack(Items.feather)).allows(new ItemStack(Blocks.stone), true),
            "the old authoritative tiered declaration path keeps its existing placement contract");
        SimpleAssert.eq(
            2,
            channels(oldProjection).materials.get("gtpp_tier")
                .size(),
            "the old GT glass/casing descriptor path keeps its real material-channel evidence");
        IStructureElement<Object> advisory = unknown();
        HologramDeclarationAdapters.register(
            advisory,
            new HologramDeclarationAdapters.Declaration(() -> StructureUtility.ofBlock(Blocks.dirt, 0), null, false));
        @SuppressWarnings("unchecked")
        IStructureElement<Object> literal = (IStructureElement<Object>) nativeTiered;
        SimpleAssert.eq(
            2,
            channels(StructureUtility.ofChain(advisory, literal)).materials.get("gtpp_tier")
                .size(),
            "an advisory sibling does not hide a native chain's independent material declaration");
        try (com.miaokatze.gtmb.api.GTMBExtensions.Registration reader = com.miaokatze.gtmb.api.Declarations
            .registerReader(
                "gtmb_test:broken-material-backing",
                BrokenMaterial.class,
                elementWithBrokenBacking -> { throw new IllegalStateException("fixture unreadable backing"); })) {
            HologramChannelTrace.Report report = channels(StructureUtility.ofChain(new BrokenMaterial(), literal));
            SimpleAssert.that(
                report.materials.containsKey("gtpp_tier"),
                "an unreadable backing does not hide a later native chain material declaration");
            SimpleAssert.eq(
                2,
                report.materials.get("gtpp_tier")
                    .size(),
                "material discovery retains the existing catalog's exception isolation");
        }
    }

    private static HologramChannelTrace.Report channels(IStructureElement<Object> element) {
        HologramChannelTrace.Scope scope = HologramChannelTrace.begin(new Object());
        HologramChannelTrace.Report report;
        try {
            IStructureElement<Object> wrapped = StructureUtility.withChannel("gtpp_tier", element);
            HologramDeclarationWrappers.observeChannels(wrapped, new ItemStack(Items.feather));
            HologramDeclarationWrappers.observeMaterials(wrapped);
        } finally {
            report = HologramChannelTrace.end(scope);
        }
        return report;
    }

    private static HologramDeclarationCandidates candidates(IStructureElement<?> element, ItemStack signal) {
        HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
        candidates.collect(element, null, null, 0, 0, 0, signal, null, 0);
        return candidates;
    }

    private static IStructureElement<Object> unknown() {
        return new IStructureElementNoPlacement<Object>() {

            public boolean check(Object controller, World world, int x, int y, int z) {
                throw new AssertionError("unreviewed leaf check executed");
            }

            public boolean spawnHint(Object controller, World world, int x, int y, int z, ItemStack trigger) {
                throw new AssertionError("unreviewed leaf hint executed");
            }
        };
    }

    private static final class BrokenMaterial implements IStructureElementNoPlacement<Object> {

        public boolean check(Object controller, World world, int x, int y, int z) {
            throw new AssertionError("broken material check executed");
        }

        public boolean spawnHint(Object controller, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("broken material hint executed");
        }
    }
}
