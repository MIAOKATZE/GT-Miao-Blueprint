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
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("StructureLib buildOrHints capture", failure);
        }
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
        int shift = 1, reject, definitionGets;
        boolean legacy;

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
