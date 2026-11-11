// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
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
                .buildAndChain(Blocks.wool, 4);
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
                                cell.block == Blocks.wool && cell.meta == 4 && !cell.unknown,
                                "native hatch-first chain resolves its real casing rather than its numeric hint");
                            SimpleAssert.that(
                                "hatch".equals(cell.role) && cell.renderHint.block == Blocks.wool
                                    && cell.renderHint.metadata == 4,
                                "native hatch role survives while the final projection renders the payable casing");
                            ItemStack material = new ItemStack(Blocks.wool, 1, 4);
                            SimpleAssert.eq(
                                4,
                                material.getItem()
                                    .getMetadata(material.getItemDamage()),
                                "the casing fixture's native item mapping preserves its declared world metadata");
                            SimpleAssert.that(
                                HologramCasingFallback.matches(cell, material),
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
            nativeHintProtocol(unsafe, controller);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("light structure projection", failure);
        } finally {
            Thread.currentThread()
                .setName(thread);
        }
    }

    private static void nativeHintProtocol(sun.misc.Unsafe unsafe,
        HologramDynamicDefinitionRegression.DynamicController controller) throws ReflectiveOperationException {
        java.lang.reflect.Method players = HologramControllerCaptureRegression.class
            .getDeclaredMethod("player", sun.misc.Unsafe.class);
        players.setAccessible(true);
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) players
            .invoke(null, unsafe);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) player.worldObj;
        net.minecraft.tileentity.TileEntity tile = (net.minecraft.tileentity.TileEntity) controller
            .getBaseMetaTileEntity();
        tile.setWorldObj(world);
        world.occupant = tile;
        HologramSession session = new HologramSession(player, 0, 100, 0, false, 2);
        session.machine = controller;
        session.context = controller;
        session.target = tile;
        session.facing = controller.getExtendedFacing()
            .ordinal();
        session.materialSettings.containers = session.materialSettings.me = false;
        ProtocolHint helper = new ProtocolHint(), readerVisual = new ProtocolHint();
        ReaderHint reader = new ReaderHint();
        for (NativeHint hint : NativeHint.values()) hint.calls = 0;
        com.gtnewhorizon.structurelib.structure.IStructureDefinition<Object> definition = StructureDefinition
            .<Object>builder()
            .addShape("hints", new String[][] { { "HHBITVMNERP" } })
            .addElement('H', helper)
            .addElement('B', NativeHint.BLOCK)
            .addElement('I', NativeHint.ICONS)
            .addElement('T', NativeHint.TINTED_ICONS)
            .addElement('V', NativeHint.TINTED_BLOCK)
            .addElement('M', NativeHint.MARKER)
            .addElement('N', NativeHint.EMPTY)
            .addElement('E', NativeHint.FAIL)
            .addElement('R', reader)
            .addElement('P', new HologramPureElementsRegression.Leaf(false))
            .build();
        Field proxy = StructureLib.class.getDeclaredField("proxy"),
            marker = StructureLib.class.getDeclaredField("blockHint");
        proxy.setAccessible(true);
        marker.setAccessible(true);
        Object previousProxy = proxy.get(null), previousMarker = marker.get(null);
        proxy.set(null, unsafe.allocateInstance(CaptureProxy.class));
        marker.set(null, Blocks.glass);
        try (
            GTMBExtensions.Registration registered = com.miaokatze.gtmb.api.Declarations.registerReader(
                "gtmb_test:native_hint_reader",
                ReaderHint.class,
                ignored -> new com.miaokatze.gtmb.api.Declarations.Declaration(readerVisual));
            GTMBExtensions.Registration source = StructureSources.register(
                "gtmb_test:native_hint_protocol",
                HologramDynamicDefinitionRegression.DynamicController.class,
                (machine, signal) -> new StructureSources.Plan(
                    definition,
                    java.util.Collections.singletonList(new StructureSources.Piece("hints", -1, 0, 0, signal))))) {
            ItemStack signal = HologramSessionPolicy.trigger(session);
            do {
                session.capture = HologramCapture.collectProjection(
                    controller,
                    signal,
                    controller.getExtendedFacing(),
                    session.captureIntent,
                    session.capture);
            } while (session.capture.pending);
            SimpleAssert.eq(0, helper.calls, "lightweight capture defers generic helper hints to preparation");
            HologramControllerCapture.FinishWork work = new HologramControllerCapture.FinishWork(
                new java.util.HashMap<>());
            work.beginFinish();
            int slices = 0;
            while (!work.step(session, 3, Long.MAX_VALUE))
                SimpleAssert.that(++slices < 10000, "native hint preparation uses the existing bounded finish work");
            for (int index = 0; index < session.capture.cells.size(); index++) {
                HologramCapture.Cell cell = session.capture.cells.get(index);
                if (cell.anchor || cell.element instanceof com.miaokatze.gtmb.api.Declarations.PureElement) continue;
                SimpleAssert.that(
                    cell.block == null && cell.unknown && cell.candidates.isEmpty() && !cell.candidateComplete,
                    "native hints provide no material, certainty or placement contract");
                SimpleAssert.eq(
                    null,
                    HologramCellPolicy.targetFor(session, cell, index),
                    "a native hint creates no payable target");
                SimpleAssert.eq("protected", cell.status, "a native hint grants no construction authority");
                if (cell.element == NativeHint.EMPTY || cell.element == NativeHint.FAIL) {
                    SimpleAssert
                        .that(cell.renderHint == null, "no output or an exception cannot become a successful hint");
                    continue;
                }
                SimpleAssert
                    .that(cell.renderHint != null, "ordinary and enum elements expose actual native hint output");
                if (cell.element == NativeHint.ICONS || cell.element == NativeHint.TINTED_ICONS) {
                    SimpleAssert.that(
                        cell.iconOnly && "gtmb:native_hint".equals(cell.hintIcons[0]),
                        "native icon hints retain only their texture descriptors");
                } else if (cell.element == NativeHint.MARKER) {
                    SimpleAssert.that(
                        cell.renderHint.block == Blocks.glass && cell.hintIndex == 7,
                        "numeric native markers remain display facts");
                } else SimpleAssert.that(
                    cell.renderHint.block == Blocks.stone
                        && cell.renderHint.metadata == ((cell.x + cell.y + cell.z + signal.stackSize) & 15),
                    "helper and computed native hints retain their own coordinate and signal");
                if (cell.element == NativeHint.TINTED_BLOCK || cell.element == NativeHint.TINTED_ICONS)
                    SimpleAssert.that(
                        cell.hintTint != null && cell.hintTint[0] == 11 && cell.hintTint[3] == 44,
                        "both native tint protocols retain their actual display values");
                HologramCapture.prepareNativeHint(session, cell);
            }
            SimpleAssert.eq(2, helper.calls, "each unresolved helper cell is hinted once at its own position");
            SimpleAssert.eq(1, readerVisual.calls, "a registered reader supplies its own native visual backing");
            SimpleAssert.eq(0, reader.calls, "registered readers never invoke the original hint callback");
            for (NativeHint hint : NativeHint.values())
                SimpleAssert.eq(1, hint.calls, "each enum hint protocol is read once during preparation");
        } finally {
            proxy.set(null, previousProxy);
            marker.set(null, previousMarker);
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

        @Override
        public void hintParticle(World world, int x, int y, int z, IIcon[] icons) {
            HologramCapture.iconHint(world, x, y, z, icons, null);
        }

        @Override
        public void hintParticleTinted(World world, int x, int y, int z, IIcon[] icons, short[] tint) {
            HologramCapture.iconHint(world, x, y, z, icons, tint);
        }

        @Override
        public void hintParticleTinted(World world, int x, int y, int z, Block block, int meta, short[] tint) {
            HologramCapture.hint(world, x, y, z, block, meta);
            HologramCapture.hintTint(x, y, z, tint);
        }
    }

    private static class ProtocolHint implements IStructureElementNoPlacement<Object> {

        int calls;

        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("native hint preparation ran a structure check");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("native hint preparation ran element placement");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack signal) {
            calls++;
            emit(world, x, y, z, signal);
            return true;
        }

        static void emit(World world, int x, int y, int z, ItemStack signal) {
            StructureLibAPI.hintParticle(world, x, y, z, Blocks.stone, (x + y + z + signal.stackSize) & 15);
        }
    }

    private static final class ReaderHint extends ProtocolHint {

        @Override
        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack signal) {
            calls++;
            throw new AssertionError("registered reader invoked its original hint callback");
        }
    }

    private enum NativeHint implements IStructureElementNoPlacement<Object> {

        BLOCK,
        ICONS,
        TINTED_ICONS,
        TINTED_BLOCK,
        MARKER,
        EMPTY,
        FAIL;

        int calls;

        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("enum native hint preparation ran a structure check");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("enum native hint preparation ran element placement");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack signal) {
            calls++;
            if (this == EMPTY) return true;
            short[] tint = { 11, 22, 33, 44 };
            if (this == ICONS || this == TINTED_ICONS) {
                IIcon icon = (IIcon) java.lang.reflect.Proxy.newProxyInstance(
                    IIcon.class.getClassLoader(),
                    new Class<?>[] { IIcon.class },
                    (proxy, method, arguments) -> "getIconName".equals(method.getName()) ? "gtmb:native_hint" : null);
                IIcon[] icons = { icon, icon, icon, icon, icon, icon };
                if (this == ICONS) StructureLibAPI.hintParticle(world, x, y, z, icons);
                else StructureLibAPI.hintParticleTinted(world, x, y, z, icons, tint);
            } else if (this == MARKER) StructureLibAPI.hintParticle(world, x, y, z, StructureLibAPI.getBlockHint(), 7);
            else if (this == TINTED_BLOCK) StructureLibAPI
                .hintParticleTinted(world, x, y, z, Blocks.stone, (x + y + z + signal.stackSize) & 15, tint);
            else ProtocolHint.emit(world, x, y, z, signal);
            if (this == FAIL) throw new IllegalStateException("native hint failed after emitting incomplete output");
            return true;
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
