package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.structure.IStructureElementNoPlacement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the real geometry walk with callbacks that fail if preview reaches operation preparation. */
final class HologramStructureProjectionRegression {

    private HologramStructureProjectionRegression() {}

    static void run() {
        String thread = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) access.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            HologramDynamicDefinitionRegression.DynamicController controller = new HologramDynamicDefinitionRegression.DynamicController();
            HologramDynamicDefinitionRegression.NativeTile tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
                .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
            tile.setWorldObj(world);
            tile.yCoord = 100;
            world.occupant = tile;
            controller.setBaseMetaTileEntity(tile);
            FixedHint fixed = new FixedHint(Blocks.stone, 3);
            UnknownHint unknown = new UnknownHint();
            HologramStructureHintData.Hint data = HologramStructureHintData.read(fixed);
            SimpleAssert.that(
                data != null && data.block == Blocks.stone && data.metadata == 3,
                "fixed hint bytecode exposes only immutable visual data without spawning particles");
            SimpleAssert.that(
                HologramStructureHintData.read(unknown) == null,
                "a callback with side effects is not interpreted as fixed hint data");
            AtomicInteger processors = new AtomicInteger();
            String row = new String(new char[4095]).replace('\0', 'A') + 'U';
            try (
                GTMBExtensions.Registration source = StructureSources.register(
                    "gtmb_test:light_projection",
                    HologramDynamicDefinitionRegression.DynamicController.class,
                    (machine, trigger) -> new StructureSources.Plan(
                        StructureDefinition.<Object>builder()
                            .addShape("projection", new String[][] { { row } })
                            .addElement('A', fixed)
                            .addElement('U', unknown)
                            .build(),
                        Arrays.asList(new StructureSources.Piece("projection", -1, 0, 0, trigger))));
                GTMBExtensions.Registration processor = BlueprintSnapshots
                    .register("gtmb_test:projection_processor", BlueprintSnapshots.Stage.CAPTURE_COMPLETE, snapshot -> {
                        processors.incrementAndGet();
                        throw new AssertionError("preview invoked a blueprint completion processor");
                    })) {
                ItemStack trigger = new ItemStack(Blocks.stone);
                HologramCapture capture = null;
                int slices = 0;
                do {
                    capture = HologramCapture.collectProjection(
                        controller,
                        trigger,
                        controller.getExtendedFacing(),
                        StructureSources.CaptureIntent.NEW_TEMPLATE,
                        capture);
                    SimpleAssert.that(++slices < 1024, "projection makes bounded forward progress");
                } while (capture.pending);
                SimpleAssert.that(!capture.incomplete && capture.projectionOnly, "real geometry projection completes");
                SimpleAssert.eq(4097, capture.cells.size(), "geometry includes every declared cell and controller");
                int unknowns = 0;
                for (HologramCapture.Cell cell : capture.cells) {
                    SimpleAssert.that(
                        cell.candidates == null && !cell.candidateComplete,
                        "projection never constructs per-cell candidate tables");
                    if (!cell.anchor) {
                        SimpleAssert.that(
                            cell.actualDisplay == null && cell.targetDisplay == null
                                && cell.actualStack == null
                                && cell.elementTrigger == null,
                            "ordinary geometry cells retain no world/native-NBT or repeated trigger payload");
                        if (cell.renderHint == null) unknowns++;
                    }
                }
                SimpleAssert.eq(1, unknowns, "unknown callback remains one visible unresolved marker");
                SimpleAssert.eq(0, unknown.calls, "unknown check/hint callbacks never execute");
                SimpleAssert.eq(0, processors.get(), "preview has no blueprint completion processing");
                SimpleAssert.that(
                    world.blockReads + world.metaReads + world.tileReads < 20,
                    "initial geometry does not observe the world once per structure cell");
                try {
                    HologramBlueprintData.projectedCapture(capture, new HologramPresets.Preset(), 0, 100, 0);
                    throw new AssertionError("render-only capture accepted as independent blueprint authority");
                } catch (java.io.IOException expected) {
                    SimpleAssert.that(
                        expected.getMessage()
                            .contains("完整操作数据"),
                        "conversion fails explicitly before consuming absent native display payload");
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("light structure projection", failure);
        } finally {
            Thread.currentThread()
                .setName(thread);
        }
    }

    private static final class FixedHint implements IStructureElementNoPlacement<Object> {

        final Block block;
        final int metadata;

        FixedHint(Block block, int metadata) {
            this.block = block;
            this.metadata = metadata;
        }

        @Override
        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("preview checked the world");
        }

        @Override
        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
            StructureLibAPI.hintParticle(world, x, y, z, block, metadata);
            return true;
        }
    }

    private static final class UnknownHint implements IStructureElementNoPlacement<Object> {

        int calls;

        @Override
        public boolean check(Object context, World world, int x, int y, int z) {
            calls++;
            throw new AssertionError("unknown preview check executed");
        }

        @Override
        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
            calls++;
            throw new AssertionError("unknown preview hint executed");
        }
    }
}
