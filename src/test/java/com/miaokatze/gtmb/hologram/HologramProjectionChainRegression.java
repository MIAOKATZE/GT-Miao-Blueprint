package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.CommonProxy;
import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.IStructureElementNoPlacement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.util.HatchElementBuilder;

/** Real native hatch chains remain visual declarations, with controller callbacks outside projection. */
final class HologramProjectionChainRegression {

    private HologramProjectionChainRegression() {}

    @SuppressWarnings("unchecked")
    static void run() {
        try {
            Field activeField = HologramCapture.class.getDeclaredField("ACTIVE");
            Field proxyField = StructureLib.class.getDeclaredField("proxy");
            Field markerField = StructureLib.class.getDeclaredField("blockHint");
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            for (Field field : new Field[] { activeField, proxyField, markerField, unsafeField })
                field.setAccessible(true);
            ThreadLocal<HologramCapture> active = (ThreadLocal<HologramCapture>) activeField.get(null);
            HologramCapture previousCapture = active.get();
            Object previousProxy = proxyField.get(null), previousMarker = markerField.get(null);
            Constructor<HologramCapture> constructor = HologramCapture.class.getDeclaredConstructor(
                Object.class,
                ItemStack.class,
                StructureSources.CaptureIntent.class,
                boolean.class);
            constructor.setAccessible(true);
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
            try {
                // The headless suite mirrors only the native hint capture mixin.
                proxyField.set(null, ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(CaptureProxy.class));
                markerField.set(null, Blocks.glass);
                ItemStack signal = new ItemStack(Blocks.stone);
                for (boolean filtered : new boolean[] { false, true }) {
                    HatchElementBuilder<Object> builder = HatchElementBuilder.<Object>builder()
                        .adder(
                            (controller, tile, texture) -> { throw new AssertionError("projection ran hatch adder"); })
                        .casingIndex(1)
                        .hint(filtered ? 3 : 2)
                        .hint(() -> { throw new AssertionError("projection ran hatch label supplier"); })
                        .description(() -> { throw new AssertionError("projection ran hatch description"); });
                    if (filtered) builder.hatchItemFilter(
                        (controller, trigger) -> { throw new AssertionError("projection ran hatch filter"); });
                    IStructureElement<Object> unsafeChild = new IStructureElementNoPlacement<Object>() {

                        @Override
                        public boolean check(Object controller, World world, int x, int y, int z) {
                            throw new AssertionError("projection checked a chain child");
                        }

                        @Override
                        public boolean spawnHint(Object controller, World world, int x, int y, int z,
                            ItemStack trigger) {
                            throw new AssertionError("projection ran an unreviewed child hint");
                        }
                    };
                    IStructureElementChain<Object> chain = filtered
                        ? builder.buildAndChain(StructureUtility.ofBlock(Blocks.stone, 4), unsafeChild)
                        : builder.buildAndChain(Blocks.stone, 4);
                    IStructureElement<?>[] children = HologramDeclarationWrappers.nativeChainChildren(chain);
                    SimpleAssert.that(
                        children != null && children.length == (filtered ? 3 : 2)
                            && (!filtered || children[2] == unsafeChild)
                            && HologramDeclarationWrappers.factory(children[0].getClass())
                                .getDeclaringClass() == HatchElementBuilder.class,
                        "real buildAndChain exposes its captured native children");
                    HologramCapture capture = constructor
                        .newInstance(new Object(), signal, StructureSources.CaptureIntent.NEW_TEMPLATE, true);
                    capture.current = new HologramCapture.Cell(chain, 0, 64, 0, signal, true);
                    active.set(capture);
                    SimpleAssert.eq(
                        true,
                        hint.invoke(capture, chain, null, 0, 64, 0, signal, 0),
                        "projection reaches each native hatch marker through the transparent chain");
                    SimpleAssert.that(
                        capture.current.renderHint != null && capture.current.renderHint.block == Blocks.glass
                            && capture.current.renderHint.metadata == (filtered ? 2 : 1),
                        "chain projection retains the native marker as render data");
                    SimpleAssert.that(
                        capture.current.block == null && !capture.current.candidateComplete
                            && capture.current.candidates == null,
                        "render data supplies no material authority");
                    SimpleAssert.eq(1, capture.nativeHintCalls, "only the reviewed native marker hint executes");
                    IStructureElementChain<Object> unknown = () -> {
                        throw new AssertionError("projection ran arbitrary chain fallbacks");
                    };
                    SimpleAssert.that(
                        HologramDeclarationWrappers.nativeChainChildren(unknown) == null,
                        "an arbitrary chain has no reviewed captured native declaration");
                    SimpleAssert.eq(
                        false,
                        hint.invoke(capture, unknown, null, 0, 64, 0, signal, 0),
                        "projection rejects arbitrary fallbacks without invoking them");
                    SimpleAssert.that(
                        HologramDeclarationWrappers.nativeChainChildren(StructureUtility.ofChain(chain)).length == 1,
                        "the existing standard ofChain declaration remains supported");
                }
            } finally {
                if (previousCapture == null) active.remove();
                else active.set(previousCapture);
                proxyField.set(null, previousProxy);
                markerField.set(null, previousMarker);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native chain projection", failure);
        }
    }

    private static final class CaptureProxy extends CommonProxy {

        @Override
        public void hintParticle(World world, int x, int y, int z, Block block, int meta) {
            SimpleAssert.that(HologramCapture.hint(world, x, y, z, block, meta), "projection consumes native hint");
        }
    }
}
