package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.util.CasingTier;
import gregtech.api.util.GTStructureUtility;

/** Real standard factories and callback tripwires; no private captured-field fixtures. */
final class HologramDeclarationAdaptersRegression {

    static void run() {
        try {
            ItemStack signal = new ItemStack(Items.feather, 2);
            IStructureElement<Object> tiered = StructureUtility.<Object, Integer>ofBlocksTiered(
                (block, meta) -> { throw new AssertionError("tier converter executed"); },
                Arrays.asList(Pair.of(Blocks.stone, 0), Pair.of(Blocks.dirt, 0)),
                -1,
                (machine, tier) -> { throw new AssertionError("tier setter executed"); },
                machine -> { throw new AssertionError("tier getter executed"); });
            IStructureElement<Object> decorated = StructureUtility.withDescription(
                Arrays.asList("fixture"),
                StructureUtility.onElementFail(
                    machine -> { throw new AssertionError("onFail executed"); },
                    StructureUtility.onElementPass(
                        machine -> { throw new AssertionError("onPass executed"); },
                        StructureUtility.withChannel("fixture_tier", tiered))));
            ChannelDataAccessor.setChannelData(signal, "fixture_tier", 1);
            SimpleAssert.eq(
                Blocks.stone,
                HologramCasingFallback.resolve(decorated, signal).block,
                "description/pass/fail wrappers preserve native channel declaration");
            HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
            candidates.collect(decorated, new Object(), null, 0, 0, 0, signal, null, 0);
            SimpleAssert.eq(2, candidates.stacks.size(), "transparent wrappers expose both actual tier entries");

            IStructureElement<Object> deferred = StructureUtility
                .defer(machine -> { throw new AssertionError("deferred controller factory executed"); });
            IStructureElement<Object> conditional = StructureUtility
                .onlyIf(machine -> { throw new AssertionError("conditional controller predicate executed"); }, tiered);
            HologramCapture capture = new HologramCapture(new Object(), signal);
            Method hint = HologramCapture.class.getDeclaredMethod(
                "spawnHint",
                IStructureElement.class,
                World.class,
                int.class,
                int.class,
                int.class,
                ItemStack.class,
                int.class);
            hint.setAccessible(true);
            SimpleAssert.eq(
                false,
                hint.invoke(capture, deferred, null, 0, 0, 0, signal, 0),
                "unadapted defer is not evaluated during hints");
            SimpleAssert.eq(
                false,
                hint.invoke(capture, conditional, null, 0, 0, 0, signal, 0),
                "unadapted conditional is not evaluated during hints");
            HologramDeclarationAdapters
                .register(deferred, new HologramDeclarationAdapters.Declaration(() -> tiered, copied -> {
                    copied.stackSize = 1;
                    return copied;
                }));
            SimpleAssert.eq(
                Blocks.stone,
                HologramCasingFallback.resolve(deferred, signal).block,
                "explicit provider makes declared leaf visible without invoking original factory");
            SimpleAssert.eq(2, signal.stackSize, "adapter signal projection cannot modify native trigger");
            HologramDeclarationAdapters.registerReader(
                DeclaredWrapper.class,
                wrapper -> new HologramDeclarationAdapters.Declaration(wrapper.declaration()));
            SimpleAssert.eq(
                Blocks.dirt,
                HologramCasingFallback
                    .resolve(new DeclaredWrapper(StructureUtility.ofBlock(Blocks.dirt, 0)), signal).block,
                "explicit wrapper API supplies immutable declaration without reflection guesses");
            SimpleAssert.that(
                HologramCasingFallback.resolve(new DifferentWrapper(StructureUtility.ofBlock(Blocks.dirt, 0)), signal)
                    == null,
                "subclass private acceptance cannot inherit declaration reader authority");
            nativeFactoryRecorder(signal);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void nativeFactoryRecorder(ItemStack signal) {
        List<Pair<Block, Integer>> registry = CasingTier.getCasingList();
        List<Pair<Block, Integer>> saved = new ArrayList<>(registry);
        try {
            registry.clear();
            IStructureElement<Object> nativeFactory = GTStructureUtility.chainAllCasings(
                -1,
                (machine, tier) -> { throw new AssertionError("native casing setter executed"); },
                machine -> { throw new AssertionError("native casing getter executed"); });
            // Unit tests do not launch Mixin. This is precisely the production RETURN recorder call.
            HologramGregTechDeclarations.casings(nativeFactory);
            registry.add(Pair.of(Blocks.stone, 0));
            registry.add(Pair.of(Blocks.dirt, 0));
            ChannelDataAccessor.setChannelData(signal, "casing", 2);
            SimpleAssert.eq(
                Blocks.dirt,
                HologramCasingFallback.resolve(nativeFactory, signal).block,
                "factory provenance resolves registry after declaration construction");
            HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
            candidates.collect(nativeFactory, new Object(), null, 0, 0, 0, signal, null, 0);
            SimpleAssert.eq(2, candidates.stacks.size(), "native factory exposes only its declared registry entries");
            SimpleAssert.that(
                candidates.stacks.stream()
                    .noneMatch(
                        stack -> stack.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.cobblestone)),
                "unrelated registered blocks are never candidates");
        } finally {
            registry.clear();
            registry.addAll(saved);
        }
    }

    private static class DeclaredWrapper implements IStructureElement<Object> {

        private final IStructureElement<?> declared;

        DeclaredWrapper(IStructureElement<?> declared) {
            this.declared = declared;
        }

        IStructureElement<?> declaration() {
            return declared;
        }

        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("private check callback executed");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("private hint callback executed");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("private placement callback executed");
        }
    }

    private static final class DifferentWrapper extends DeclaredWrapper {

        DifferentWrapper(IStructureElement<?> declared) {
            super(declared);
        }
    }
}
