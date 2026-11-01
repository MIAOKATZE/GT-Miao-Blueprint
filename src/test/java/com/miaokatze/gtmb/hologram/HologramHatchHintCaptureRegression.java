package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.CommonProxy;
import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementNoPlacement;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.util.HatchElementBuilder;

/** Real GT hatch factories reach the capture hook without evaluating controller callbacks. */
final class HologramHatchHintCaptureRegression {

    private HologramHatchHintCaptureRegression() {}

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
                // The headless suite has no mixins. Mirror only MixinHologramHints' forwarding to the real hook.
                proxyField.set(null, ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(CaptureProxy.class));
                markerField.set(null, Blocks.glass);
                for (boolean filtered : new boolean[] { false, true }) {
                    HatchElementBuilder<Object> builder = HatchElementBuilder.<Object>builder()
                        .adder((controller, tile, texture) -> { throw new AssertionError("hatch adder executed"); })
                        .casingIndex(1)
                        .hint(filtered ? 3 : 2)
                        .hint(() -> { throw new AssertionError("hatch label supplier executed"); })
                        .description(() -> { throw new AssertionError("hatch description executed"); });
                    if (filtered) builder.hatchItemFilter(
                        (controller, trigger) -> { throw new AssertionError("hatch filter executed"); });
                    IStructureElement<Object> element = builder.build();
                    ItemStack signal = new ItemStack(Items.feather);
                    HologramCapture capture = new HologramCapture(new Object(), signal);
                    capture.current = new HologramCapture.Cell(element, 0, 64, 0, signal);
                    active.set(capture);
                    SimpleAssert.eq(
                        true,
                        hint.invoke(capture, element, null, 0, 64, 0, signal, 0),
                        "each real builder branch emits its fixed preview marker through the production dispatcher");
                    SimpleAssert.eq(Blocks.glass, capture.current.block, "the capture hook retains the actual marker");
                    SimpleAssert.eq(filtered ? 2 : 1, capture.current.hintIndex, "the original marker index survives");
                    SimpleAssert.eq(1, capture.nativeHintCalls, "exactly one reviewed native hint is called");
                    SimpleAssert.that(
                        !capture.current.candidateComplete && capture.current.candidates == null,
                        "a visible marker supplies no material declaration or construction authority");
                    IStructureElement<Object> unknown = new IStructureElementNoPlacement<Object>() {

                        @Override
                        public boolean check(Object controller, World world, int x, int y, int z) {
                            throw new AssertionError("unknown check executed");
                        }

                        @Override
                        public boolean spawnHint(Object controller, World world, int x, int y, int z,
                            ItemStack trigger) {
                            throw new AssertionError("unknown hint executed");
                        }
                    };
                    SimpleAssert.eq(
                        false,
                        hint.invoke(capture, unknown, null, 0, 64, 0, signal, 0),
                        "unreviewed leaves remain outside the preview callback path");
                    SimpleAssert
                        .eq(1, capture.nativeHintCalls, "the exact factory exception does not admit unknown hints");
                }
            } finally {
                if (previousCapture == null) active.remove();
                else active.set(previousCapture);
                proxyField.set(null, previousProxy);
                markerField.set(null, previousMarker);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("fixed hatch hint capture", failure);
        }
    }

    private static final class CaptureProxy extends CommonProxy {

        @Override
        public void hintParticle(World world, int x, int y, int z, Block block, int meta) {
            SimpleAssert
                .that(HologramCapture.hint(world, x, y, z, block, meta), "the active capture consumes the hint");
        }
    }
}
