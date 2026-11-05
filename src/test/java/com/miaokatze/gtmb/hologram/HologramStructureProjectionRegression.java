package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.CommonProxy;
import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.structure.IStructureElementNoPlacement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
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
            Field proxy = StructureLib.class.getDeclaredField("proxy"),
                marker = StructureLib.class.getDeclaredField("blockHint");
            proxy.setAccessible(true);
            marker.setAccessible(true);
            Object oldProxy = proxy.get(null), oldMarker = marker.get(null);
            proxy.set(null, unsafe.allocateInstance(CaptureProxy.class));
            marker.set(null, Blocks.glass);
            com.gtnewhorizon.structurelib.structure.IStructureElement<Object> casing = gregtech.api.util.HatchElementBuilder
                .<Object>builder()
                .adder((machine, hatch, texture) -> { throw new AssertionError("projection invoked hatch adder"); })
                .casingIndex(1)
                .hint(2)
                .buildAndChain(Blocks.stone, 4);
            String row = new String(new char[4093]).replace('\0', 'A') + "BCU";
            try (
                GTMBExtensions.Registration source = StructureSources.register(
                    "gtmb_test:light_projection",
                    HologramDynamicDefinitionRegression.DynamicController.class,
                    (machine, trigger) -> new StructureSources.Plan(
                        StructureDefinition.<Object>builder()
                            .addShape("projection", new String[][] { { row } })
                            .addElement('A', fixed)
                            .addElement('U', unknown)
                            .addElement('B', casing)
                            .addElement(
                                'C',
                                StructureUtility.withChannel("casing", StructureUtility.ofBlock(Blocks.dirt, 2)))
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
                    if (!cell.anchor) {
                        SimpleAssert.that(
                            cell.actualDisplay == null && cell.targetDisplay == null
                                && cell.actualStack == null
                                && cell.elementTrigger == capture.pieces.get(cell.projectionPiece).trigger,
                            "native material cells retain a shared piece signal without native-NBT payload copies");
                        if (cell.element == casing) {
                            SimpleAssert.that(
                                cell.block == Blocks.stone && cell.meta == 4 && !cell.unknown,
                                "native hatch-first chain resolves its real casing rather than its numeric hint");
                            SimpleAssert.that(
                                "hatch".equals(cell.role) && cell.renderHint.block == Blocks.stone
                                    && cell.renderHint.metadata == 4,
                                "native hatch role survives while the final projection renders the payable casing");
                            SimpleAssert.that(
                                HologramCasingFallback.matches(cell, new ItemStack(Blocks.stone, 1, 4)),
                                "the native fixed fallback remains the declaration's actual material");
                        }
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
            } finally {
                proxy.set(null, oldProxy);
                marker.set(null, oldMarker);
            }
            targetCacheBoundaries(unsafe, world, controller);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("light structure projection", failure);
        } finally {
            Thread.currentThread()
                .setName(thread);
        }
    }

    private static void targetCacheBoundaries(sun.misc.Unsafe unsafe, World world,
        HologramDynamicDefinitionRegression.DynamicController controller) throws ReflectiveOperationException {
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> stable = StructureUtility
            .ofBlock(Blocks.wool, 5);
        java.util.Map<Block, java.util.Collection<Integer>> accepted = new java.util.LinkedHashMap<>();
        accepted.put(Blocks.stone, java.util.Collections.singletonList(0));
        accepted.put(Blocks.dirt, java.util.Collections.singletonList(0));
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> contextual = StructureUtility
            .ofBlocksMap(accepted, Blocks.dirt, 0);
        SimpleAssert.that(
            HologramDeclarationPurity.contextFree(stable) && !HologramDeclarationPurity.contextFree(contextual),
            "projection uses the existing declaration stability boundary");
        Field proxy = StructureLib.class.getDeclaredField("proxy");
        proxy.setAccessible(true);
        Object previous = proxy.get(null);
        ContextHints hints = (ContextHints) unsafe.allocateInstance(ContextHints.class);
        proxy.set(null, hints);
        try (GTMBExtensions.Registration source = StructureSources.register(
            "gtmb_test:projection_target_context",
            HologramDynamicDefinitionRegression.DynamicController.class,
            (machine, signal) -> new StructureSources.Plan(
                StructureDefinition.<Object>builder()
                    .addShape("context", new String[][] { { "SSCC" } })
                    .addElement('S', stable)
                    .addElement('C', contextual)
                    .build(),
                java.util.Collections.singletonList(new StructureSources.Piece("context", -1, 0, 0, signal))))) {
            HologramCapture capture = null;
            ItemStack signal = new ItemStack(Blocks.stone);
            do {
                capture = HologramCapture.collectProjection(
                    controller,
                    signal,
                    controller.getExtendedFacing(),
                    StructureSources.CaptureIntent.NEW_TEMPLATE,
                    capture);
            } while (capture.pending);
            SimpleAssert.that(!capture.incomplete, "context-bound native projection completes");
            java.util.Set<Block> observed = new java.util.HashSet<>();
            int stableCells = 0, contextualCells = 0;
            for (HologramCapture.Cell cell : capture.cells) {
                if (cell.element == stable) {
                    stableCells++;
                    SimpleAssert.that(
                        cell.block == Blocks.wool && cell.meta == 5,
                        "stable native target identity survives shared interpretation");
                } else if (cell.element == contextual) {
                    contextualCells++;
                    Block expected = ((cell.x + cell.y + cell.z) & 1) == 0 ? Blocks.stone : Blocks.dirt;
                    SimpleAssert.eq(
                        expected,
                        cell.block,
                        "a contextual declaration retains the target observed at its own coordinate");
                    observed.add(cell.block);
                }
            }
            SimpleAssert.eq(2, stableCells, "both stable native cells are present");
            SimpleAssert.eq(1, hints.stable, "a proven stable declaration still shares its native target capture");
            SimpleAssert.eq(2, contextualCells, "both contextual native cells are present");
            SimpleAssert.eq(2, hints.contextual, "contextual targets are captured separately at each position");
            SimpleAssert.eq(2, observed.size(), "distinct contextual targets cannot borrow the first cell's target");
        } finally {
            proxy.set(null, previous);
        }
    }

    /** Existing headless hint-interception port supplies position-dependent observations without element checks. */
    private static final class ContextHints extends CommonProxy {

        int stable, contextual;

        @Override
        public void hintParticle(World world, int x, int y, int z, Block block, int meta) {
            if (block == Blocks.wool && meta == 5) stable++;
            if (block == Blocks.dirt && meta == 0) {
                contextual++;
                block = ((x + y + z) & 1) == 0 ? Blocks.stone : Blocks.dirt;
            }
            HologramCapture.hint(world, x, y, z, block, meta);
        }
    }

    private static final class CaptureProxy extends CommonProxy {

        @Override
        public void hintParticle(World world, int x, int y, int z, Block block, int meta) {
            HologramCapture.hint(world, x, y, z, block, meta);
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
