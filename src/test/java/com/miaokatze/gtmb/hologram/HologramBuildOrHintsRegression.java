// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** The raw harness mirrors only the new HEAD hook; native Mixin installation needs the separate FML check. */
final class HologramBuildOrHintsRegression {

    private HologramBuildOrHintsRegression() {}

    static void run() {
        try {
            Controller controller = controller();
            ItemStack trigger = new ItemStack(Blocks.stone, 3);
            for (ExtendedFacing facing : new ExtendedFacing[] { ExtendedFacing.DEFAULT,
                ExtendedFacing.of(ForgeDirection.EAST, Rotation.CLOCKWISE, Flip.HORIZONTAL),
                ExtendedFacing.of(ForgeDirection.UP, Rotation.COUNTER_CLOCKWISE, Flip.VERTICAL) }) {
                controller.facing = facing;
                for (int direction : new int[] { -1, 1 }) {
                    controller.shift = direction;
                    HologramCapture declaration = HologramCapture.declarationPieces(controller, trigger);
                    SimpleAssert.eq(1, declaration.pieces.size(), "the direct interface entry records one piece");
                    SimpleAssert
                        .that(declaration.definition == controller.definition, "the called definition is frozen");
                    HologramCapture capture = HologramCapture.collect(controller, trigger, facing);
                    SimpleAssert
                        .that(!capture.incomplete && !capture.pending, "direct capture completes: " + capture.failure);
                    Set<String> expected = nativePositions(controller);
                    Set<String> actual = new HashSet<>(capture.positions.keySet());
                    actual.remove("0,100,0");
                    SimpleAssert
                        .eq(expected, actual, "positive and negative anchors retain native rotated/flipped positions");
                    SimpleAssert.eq(
                        0,
                        capture.nativeDefinitionCalls,
                        "capture does not replace the observed graph with a new getter result");
                    SimpleAssert.eq(0, capture.nativeHintCalls, "unknown elements never enter native hint callbacks");
                    SimpleAssert.that(capture.sourceCurrent(), "normal source and controller guards remain bound");
                }
            }
            SimpleAssert.eq(0, controller.definitionGets, "direct declarations never rebuild the graph");
            SimpleAssert.eq(0, controller.definition.nativeTraversals, "the HEAD hook cancels default traversal");

            controller.facing = ExtendedFacing.DEFAULT;
            controller.legacy = true;
            HologramCapture legacy = HologramCapture.collect(controller, trigger, controller.facing);
            SimpleAssert.that(!legacy.incomplete, "the existing buildPiece route remains available");
            SimpleAssert.eq(1, legacy.pieces.size(), "the existing HEAD cancellation prevents double recording");
            SimpleAssert.eq(1, controller.definitionGets, "the legacy route retains its single definition lookup");
            controller.legacy = false;

            String[] reasons = { "BUILD_OR_HINTS_WORLD", "BUILD_OR_HINTS_FACING", "BUILD_OR_HINTS_CONTEXT",
                "BUILD_OR_HINTS_MODE", "BUILD_OR_HINTS_DEFINITION", "BUILD_OR_HINTS_MIXED", "BUILD_OR_HINTS_MIXED" };
            for (int mode = 1; mode <= reasons.length; mode++) {
                controller.reject = mode;
                HologramCapture rejected = HologramCapture.collect(controller, trigger, controller.facing);
                SimpleAssert.that(
                    rejected.incomplete && rejected.failure.startsWith(reasons[mode - 1]),
                    "unsupported frames/graphs are rejected explicitly: " + rejected.failure);
                SimpleAssert.that(!rejected.sourceCurrent(), "a rejected entry cannot retain source authority");
            }
            controller.reject = 0;
            SimpleAssert.eq(
                0,
                controller.definition.nativeTraversals,
                "rejection never falls through to unknown native elements");
            SimpleAssert.eq(
                false,
                controller.definition.buildOrHints(
                    controller,
                    trigger,
                    "main",
                    controller.getBaseMetaTileEntity()
                        .getWorld(),
                    controller.facing,
                    0,
                    100,
                    0,
                    7,
                    -2,
                    5,
                    true),
                "without an active capture the actual StructureLib server-side hint result is unchanged");
            SimpleAssert
                .eq(1, controller.definition.nativeTraversals, "ordinary calls reach the native default method");
            atomicDiscovery(trigger);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("StructureLib buildOrHints capture", failure);
        }
    }

    private static void atomicDiscovery(ItemStack trigger) throws ReflectiveOperationException {
        Controller controller = controller();
        controller.expireDeclaration = true;
        HologramCapture capture = HologramCapture.collect(controller, trigger, controller.facing);
        SimpleAssert.that(
            !capture.incomplete && capture.pending && !capture.walkingComplete,
            "expired declaration deadline yields only during resumable walking: " + capture.failure);
        SimpleAssert.eq(1, capture.pieces.size(), "cold declaration records the complete piece before yielding");
        SimpleAssert.that(capture.sourceCurrent(), "cold declaration binds its source before the walk yields");
        int slices = 0;
        while (capture.pending && slices++ < 16)
            capture = HologramCapture.collect(controller, trigger, controller.facing, capture);
        SimpleAssert.that(
            !capture.incomplete && !capture.pending && capture.walkingComplete,
            "the expired first slice resumes to completion: " + capture.failure);
        SimpleAssert.eq(1, capture.nativeConstructCalls, "continuation never replays atomic construct");
        SimpleAssert.eq(1, controller.constructions, "only one native declaration callback executes");
        SimpleAssert.eq(0, controller.definitionGets, "the direct declaration keeps its observed graph");
        SimpleAssert.eq(2, nativePositions(controller).size(), "fixture declares two separate cells");
        SimpleAssert.eq(3, capture.cells.size(), "continuation retains both declared cells and controller anchor");

        controller = controller();
        controller.legacy = true;
        controller.expireDeclaration = true;
        capture = HologramCapture.collect(controller, trigger, controller.facing);
        SimpleAssert
            .that(!capture.incomplete && capture.pending, "legacy declaration also survives an expired deadline");
        slices = 0;
        while (capture.pending && slices++ < 16)
            capture = HologramCapture.collect(controller, trigger, controller.facing, capture);
        SimpleAssert.that(!capture.incomplete && !capture.pending, "legacy cold declaration resumes successfully");
        SimpleAssert.eq(1, capture.nativeConstructCalls, "legacy continuation never repeats construct");
        SimpleAssert.eq(1, capture.nativeDefinitionCalls, "legacy continuation never repeats definition discovery");
        SimpleAssert.eq(1, controller.definitionGets, "legacy getter executes only once");

        controller = controller();
        controller.throwDeclaration = true;
        HologramCapture rejected = HologramCapture.collect(controller, trigger, controller.facing);
        SimpleAssert.that(rejected.incomplete && !rejected.pending, "a genuine declaration exception remains terminal");
        SimpleAssert.eq("IllegalStateException", rejected.failure, "unbound failure retains the original exception");
        SimpleAssert.that(
            rejected.failureOrigin.contains("Controller.construct"),
            "unbound failure retains its declaration origin");
        SimpleAssert.that(!rejected.sourceCurrent(), "a failed declaration never acquires source authority");
        SimpleAssert
            .eq("IllegalStateException", rejected.failure, "validity checks do not overwrite declaration failure");

        controller = controller();
        controller.pieceCount = 4097;
        rejected = HologramCapture.collect(controller, trigger, controller.facing);
        SimpleAssert.eq(4096, rejected.pieces.size(), "atomic discovery has a finite piece limit");
        SimpleAssert.eq("PIECE_LIMIT", rejected.failure, "piece overflow retains its explicit failure reason");
        SimpleAssert.that(
            rejected.incomplete && !rejected.pending && !rejected.sourceCurrent(),
            "piece overflow cannot be resumed or accepted as a complete source");
        SimpleAssert.that(
            rejected.failureOrigin.contains("CaptureLimitException"),
            "piece overflow retains its original bounded-discovery exception");
        SimpleAssert.eq(1, rejected.nativeConstructCalls, "overflow does not retry native declaration discovery");
        SimpleAssert.eq(0, rejected.nativeDefinitionCalls, "overflow aborts before definition binding");
    }

    private static Set<String> nativePositions(Controller controller) {
        Set<String> result = new HashSet<>();
        controller.definition.iterate(
            "main",
            controller.getBaseMetaTileEntity()
                .getWorld(),
            controller.facing,
            4 * controller.shift,
            100 - 3 * controller.shift,
            2 * controller.shift,
            7,
            -2,
            5,
            new IStructureWalker<Controller>() {

                public boolean visit(IStructureElement<Controller> element, World world, int x, int y, int z, int a,
                    int b, int c) {
                    result.add(x + "," + y + "," + z);
                    return true;
                }
            });
        return result;
    }

    private static Controller controller() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        HologramDynamicDefinitionRegression.NativeTile tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
            .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
        tile.setWorldObj(world);
        tile.yCoord = 100;
        Controller controller = new Controller();
        controller.setBaseMetaTileEntity(tile);
        world.occupant = tile;
        return controller;
    }

    private static final class EntryDefinition implements IStructureDefinition<Controller> {

        private final IStructureDefinition<Controller> backing = StructureDefinition.<Controller>builder()
            .addShape("main", new String[][] { { "AA" } })
            .addElement('A', new Unknown())
            .build();
        int nativeTraversals;

        public IStructureElement<Controller>[] getStructureFor(String piece) {
            return backing.getStructureFor(piece);
        }

        public boolean isContainedInStructure(String piece, int a, int b, int c) {
            return backing.isContainedInStructure(piece, a, b, c);
        }

        public boolean buildOrHints(Controller controller, ItemStack trigger, String piece, World world,
            ExtendedFacing facing, int x, int y, int z, int a, int b, int c, boolean hints) {
            // The headless suite does not install Mixins; forward the exact default-entry arguments to its hook.
            if (HologramCapture.piece(this, controller, trigger, piece, world, facing, x, y, z, a, b, c, hints))
                return true;
            nativeTraversals++;
            return IStructureDefinition.super.buildOrHints(
                controller,
                trigger,
                piece,
                world,
                facing,
                x,
                y,
                z,
                a,
                b,
                c,
                hints);
        }
    }

    private static final class Unknown implements IStructureElement<Controller> {

        public boolean check(Controller controller, World world, int x, int y, int z) {
            throw new AssertionError("unknown check executed");
        }

        public boolean spawnHint(Controller controller, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("unknown hint executed");
        }

        public boolean placeBlock(Controller controller, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("unknown placement executed");
        }
    }

    private static final class Controller extends MTEEnhancedMultiBlockBase<Controller> {

        final EntryDefinition definition = new EntryDefinition();
        ExtendedFacing facing = ExtendedFacing.DEFAULT;
        int shift = 1, reject, definitionGets, constructions, pieceCount = 1;
        boolean legacy, expireDeclaration, throwDeclaration;

        Controller() {
            super("build_or_hints_fixture");
        }

        public IStructureDefinition<Controller> getStructureDefinition() {
            definitionGets++;
            return definition;
        }

        public ExtendedFacing getExtendedFacing() {
            return facing;
        }

        public void construct(ItemStack trigger, boolean hints) {
            constructions++;
            if (expireDeclaration) {
                try {
                    Field field = HologramCapture.class.getDeclaredField("ACTIVE");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    ThreadLocal<HologramCapture> active = (ThreadLocal<HologramCapture>) field.get(null);
                    active.get().deadline = Long.MIN_VALUE;
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("expire atomic declaration deadline", failure);
                }
            }
            if (throwDeclaration) throw new IllegalStateException("native declaration failed");
            if (pieceCount > 1) {
                for (int i = 0; i < pieceCount; i++) HologramCapture.piece(this, "main", trigger, hints, 7, -2, 5);
                return;
            }
            if (legacy || reject == 6) {
                if (HologramCapture.piece(this, "main", trigger, hints, 7, -2, 5) && legacy) return;
            }
            Object context = reject == 3 ? new Object() : this;
            World world = reject == 1 ? null : getBaseMetaTileEntity().getWorld();
            ExtendedFacing calledFacing = reject == 2 ? facing.with(ForgeDirection.SOUTH) : facing.getDuplicate();
            // Same receiver and signature as StructureWrapper.constructImpl; the context negative calls the bridge
            // directly because the test definition has a concrete Controller generic parameter.
            if (context != this) HologramCapture.piece(
                definition,
                context,
                trigger,
                "main",
                world,
                calledFacing,
                4 * shift,
                100 - 3 * shift,
                2 * shift,
                7,
                -2,
                5,
                hints);
            else definition.buildOrHints(
                this,
                trigger,
                "main",
                world,
                calledFacing,
                4 * shift,
                100 - 3 * shift,
                2 * shift,
                7,
                -2,
                5,
                reject == 4 ? false : hints);
            if (reject == 5) new EntryDefinition().buildOrHints(
                this,
                trigger,
                "main",
                world,
                calledFacing,
                4 * shift,
                100 - 3 * shift,
                2 * shift,
                7,
                -2,
                5,
                hints);
            if (reject == 7) HologramCapture.piece(this, "main", trigger, hints, 7, -2, 5);
        }

        public ItemStack getStackForm(long count) {
            return new ItemStack(Blocks.stone, (int) count);
        }

        protected MultiblockTooltipBuilder createTooltip() {
            return null;
        }

        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new Controller();
        }

        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }
}
