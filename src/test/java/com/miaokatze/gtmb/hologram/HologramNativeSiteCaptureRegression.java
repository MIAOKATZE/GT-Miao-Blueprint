// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.structure.error.StructureError;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Independent one-check capture contract against the actual .277 checker and real EBF definition. */
public final class HologramNativeSiteCaptureRegression {

    private static final int CONTROLLER = 32648, ENERGY = 32647, PORT_START = 32640, BRANCH = 32639;

    private HologramNativeSiteCaptureRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramNativeSiteCaptureRegression.class, args)) return;
        net.minecraft.launchwrapper.Launch.classLoader
            .registerTransformer(HologramNativeSiteEventFixtureTransformer.class.getName());
        net.minecraft.launchwrapper.Launch.classLoader.registerTransformer(PieceFixtureTransformer.class.getName());
        Thread.currentThread()
            .setName("Server thread");
        HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
            try {
                // Reuse only registry setup, independently from the other suite's evolving verification flow.
                var registries = HologramNativeSitePlanRegression.class.getDeclaredMethod("registries");
                registries.setAccessible(true);
                registries.invoke(null);
                prototype(CONTROLLER, new CountingEbf());
                prototype(BRANCH, new BranchMachine());
                ebf();
                branchAndLifecycle();
            } catch (Exception failure) {
                throw new AssertionError("single native site capture fixture", failure);
            }
        });
        System.out
            .println("NATIVE_SITE_CAPTURE_PASS platform=GT5U-5.09.54.277 checkStructure=one no_game_started=true");
    }

    private static void prototype(int id, IMetaTileEntity machine) {
        var base = new BaseMetaTileEntity();
        machine.setBaseMetaTileEntity(base);
        base.setMetaTileID((short) id);
        GregTechAPI.METATILEENTITIES[id] = machine;
    }

    private static void ebf() throws Exception {
        Scene world = HologramResourceWorld.create(Scene::new);
        BaseMetaTileEntity controller = HologramNativeTargetPlan.freshController(CONTROLLER, ExtendedFacing.DEFAULT);
        controller.setWorldObj(world);
        world.tile = controller;
        List<HologramNativeTargetPlan.Offset> bottom = new ArrayList<>();
        HologramNativeTargetPlan.Offset coil = null;
        try (var target = HologramNativeTargetPlan.compile(
            CONTROLLER,
            ExtendedFacing.DEFAULT,
            HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound()),
            new NBTTagCompound())) {
            for (var cell : target.cells()) {
                world.seed(cell);
                var offset = new HologramNativeTargetPlan.Offset(cell.dx(), cell.dy(), cell.dz());
                if (cell.block() == GregTechAPI.sBlockCasings1 && cell.dy() == 0) bottom.add(offset);
                if (cell.block() == GregTechAPI.sBlockCasings5) coil = offset;
            }
        }
        SimpleAssert.that(bottom.size() >= 6 && coil != null, "actual EBF has native hatch and coil coordinates");
        for (int i = 0; i < 6; i++) world.hatch(bottom.get(i), i < 5 ? PORT_START + i : ENERGY);
        var machine = (CountingEbf) controller.getMetaTileEntity();
        machine.mMachine = true;
        world.armed = true;
        var first = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(first.complete(), "actual native EBF is fully formed: " + first.failure());
        SimpleAssert.eq(1, machine.checks, "one explicit capture performs exactly one checkStructure call");
        SimpleAssert.eq(
            35,
            first.elements()
                .size(),
            "the native 3x4x3 EBF owns every cell except its controller");
        SimpleAssert.that(
            first.elements()
                .entrySet()
                .stream()
                .anyMatch(entry -> {
                    var at = entry.getKey();
                    return world.getBlock(at.x(), 128 + at.y(), at.z()) == Blocks.air;
                }),
            "native required air remains in the successful membership declaration");
        var sentinel = new TrackedTile();
        sentinel.xCoord = 3;
        sentinel.yCoord = 128;
        sentinel.setWorldObj(world);
        sentinel.setInitialValuesAsNBT(null, (short) BRANCH);
        ((BranchMachine) sentinel.getMetaTileEntity()).mMachine = true;
        sentinel.saves = 0;
        world.cells
            .put(new HologramNativeTargetPlan.Offset(3, 0, 0), new Stored(GregTechAPI.sBlockMachines, 0, sentinel));
        world.neighborX = 3;
        world.neighborReads = 0;
        var reopened = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(
            reopened.complete() && reopened.elements()
                .equals(first.elements()),
            "an unrelated adjacent controller cannot alter the proved native members");
        SimpleAssert.eq(2, machine.checks, "reopening requests one further native check");
        SimpleAssert.eq(0, world.neighborReads, "out-of-piece neighboring machine is not read");
        SimpleAssert.eq(0, sentinel.saves, "neighboring TE is never serialized");
        world.unloaded = coil;
        var unloaded = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(
            !unloaded.complete() && unloaded.elements()
                .isEmpty(),
            "an unloaded native member never publishes a partial removal scope");
        SimpleAssert.that(!StructureLibAPI.isInstrumentEnabled(), "unloaded outcome releases instrumentation");
        world.unloaded = null;
        world.cells.put(coil, new Stored(Blocks.dirt, 0, null));
        machine.mMachine = true;
        var broken = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(
            !broken.complete() && broken.elements()
                .isEmpty() && !machine.mMachine,
            "failed actual EBF check excludes every visited failed-piece coordinate");
        System.out.println(
            "NATIVE_SITE_CAPTURE_EBF members=" + first.elements()
                .size()
                + " calls="
                + machine.checks
                + " neighborReads="
                + world.neighborReads
                + " neighborNbt="
                + sentinel.saves);
    }

    private static void branchAndLifecycle() throws Exception {
        Scene world = HologramResourceWorld.create(Scene::new);
        var controller = HologramNativeTargetPlan.freshController(BRANCH, ExtendedFacing.DEFAULT);
        controller.setWorldObj(world);
        world.tile = controller;
        var machine = (BranchMachine) controller.getMetaTileEntity();
        machine.mMachine = true;
        var complete = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(
            complete.complete() && complete.elements()
                .size() == 1,
            "a successful native piece exports the original declaration wrapper");
        SimpleAssert.that(
            complete.elements()
                .values()
                .iterator()
                .next() == BranchMachine.PASS,
            "capture never substitutes a flattened element or calls it for a second validation");
        SimpleAssert.eq(1, machine.elementChecks, "native element check runs once; center walk does not recheck it");
        machine.mode = 1;
        var ambiguous = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(
            machine.mMachine && !ambiguous.complete()
                && ambiguous.elements()
                    .isEmpty()
                && ambiguous.failure()
                    .contains("AMBIGUOUS_BRANCH"),
            "a failed probe followed by successful native fallback does not prove final membership");
        machine.mode = 2;
        machine.mMachine = true;
        var failed = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(
            !failed.complete() && failed.elements()
                .isEmpty(),
            "failed piece scope stays unpublished");
        machine.mode = 3;
        machine.mMachine = true;
        try {
            HologramNativeSiteCapture.capture(controller);
            throw new AssertionError("native callback exception must stop capture");
        } catch (IOException expected) {}
        SimpleAssert
            .that(!StructureLibAPI.isInstrumentEnabled(), "exceptional checkPiece exits clean up the instrument");
        machine.mode = 0;
        machine.mMachine = true;
        SimpleAssert.that(
            HologramNativeSiteCapture.capture(controller)
                .complete(),
            "an abandoned piece cannot contaminate the next explicit capture");
        int checks = machine.checks;
        Object instrument = new Object();
        StructureLibAPI.enableInstrument(instrument);
        try {
            HologramNativeSiteCapture.capture(controller);
            throw new AssertionError("a borrowed instrument must be refused");
        } catch (IOException expected) {
            SimpleAssert.that(
                StructureLibAPI.isInstrumentEnabled() && machine.checks == checks,
                "instrument refusal does not run a check or disable another instrument");
        } finally {
            StructureLibAPI.disableInstrument();
        }
        var neighbor = HologramNativeTargetPlan.freshController(BRANCH, ExtendedFacing.DEFAULT);
        neighbor.xCoord = 9;
        neighbor.setWorldObj(world);
        machine.neighbor = (BranchMachine) neighbor.getMetaTileEntity();
        world.cells
            .put(new HologramNativeTargetPlan.Offset(9, 0, 0), new Stored(GregTechAPI.sBlockMachines, 0, neighbor));
        var nested = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(
            nested.complete() && nested.elements()
                .equals(complete.elements()) && machine.neighbor.elementChecks == 1,
            "same-world nested neighboring-controller pieces are isolated from the requested piece");
        machine.neighbor = null;
        machine.mode = 4;
        var changed = HologramNativeSiteCapture.capture(controller);
        SimpleAssert.that(
            !changed.complete() && changed.elements()
                .isEmpty(),
            "controller identity invalidation during native check revokes capture");
        controller.xCoord = 0;
        machine.mode = 0;
        machine.mMachine = true;
        SimpleAssert.that(
            HologramNativeSiteCapture.capture(controller)
                .complete(),
            "capture identity failure leaves no native piece or instrument behind");
    }

    private static final class CountingEbf extends MTEElectricBlastFurnace {

        int checks;

        CountingEbf() {
            super("single_capture_actual_ebf");
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new CountingEbf();
        }

        @Override
        public boolean checkStructure(boolean force, IGregTechTileEntity tile) {
            checks++;
            return super.checkStructure(force, tile);
        }
    }

    private static final class BranchMachine extends MTEEnhancedMultiBlockBase<BranchMachine> {

        static final IStructureElement<BranchMachine> PASS = new IStructureElement<>() {

            public boolean check(BranchMachine context, World world, int x, int y, int z) {
                context.elementChecks++;
                if (context.mode == 3) throw new IllegalStateException("fixture native check threw");
                if (context.mode == 4) ((BaseMetaTileEntity) context.getBaseMetaTileEntity()).xCoord++;
                if (context.neighbor != null) context.neighbor.checkPiece("pass", 0, 0, 0);
                return true;
            }

            public boolean spawnHint(BranchMachine context, World world, int x, int y, int z, ItemStack trigger) {
                return true;
            }

            public boolean placeBlock(BranchMachine context, World world, int x, int y, int z, ItemStack trigger) {
                return false;
            }
        };
        static final IStructureElement<BranchMachine> FAIL = new IStructureElement<>() {

            public boolean check(BranchMachine context, World world, int x, int y, int z) {
                context.elementChecks++;
                return false;
            }

            public boolean spawnHint(BranchMachine context, World world, int x, int y, int z, ItemStack trigger) {
                return false;
            }

            public boolean placeBlock(BranchMachine context, World world, int x, int y, int z, ItemStack trigger) {
                return false;
            }
        };
        static final IStructureDefinition<BranchMachine> DEFINITION = StructureDefinition.<BranchMachine>builder()
            .addShape("pass", new String[][] { { "A" } })
            .addShape("fail", new String[][] { { "B" } })
            .addElement('A', PASS)
            .addElement('B', FAIL)
            .build();
        int checks, elementChecks, mode;
        BranchMachine neighbor;

        BranchMachine() {
            super("single_capture_branch");
        }

        @Override
        public IStructureDefinition<BranchMachine> getStructureDefinition() {
            return DEFINITION;
        }

        @Override
        public void construct(ItemStack trigger, boolean hintsOnly) {
            buildPiece("pass", trigger, hintsOnly, 0, 0, 0);
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new BranchMachine();
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return new MultiblockTooltipBuilder();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }

        @Override
        public void checkMachine(IGregTechTileEntity tile, ItemStack trigger, List<StructureError> errors) {
            if (mode == 1) checkPiece("fail", 0, 0, 0);
            checkPiece(mode == 2 ? "fail" : "pass", 0, 0, 0, errors);
        }

        @Override
        public boolean checkStructure(boolean force, IGregTechTileEntity tile) {
            checks++;
            return super.checkStructure(force, tile);
        }
    }

    private static final class Stored {

        final Block block;
        final int metadata;
        final TileEntity tile;

        Stored(Block block, int metadata, TileEntity tile) {
            this.block = block;
            this.metadata = metadata;
            this.tile = tile;
        }
    }

    private static final class TrackedTile extends BaseMetaTileEntity {

        int saves;

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            saves++;
            super.writeToNBT(tag);
        }
    }

    private static final class Scene extends HologramResourceWorld {

        final Map<HologramNativeTargetPlan.Offset, Stored> cells = new LinkedHashMap<>();
        HologramNativeTargetPlan.Offset unloaded;
        boolean armed;
        int neighborReads, neighborX = 9;

        Scene() {
            super(GregTechAPI.sBlockMachines, 0);
        }

        void seed(HologramNativeTargetPlan.Cell cell) {
            TileEntity created = null;
            if (cell.tileData() != null) {
                created = cell.block()
                    .createTileEntity(this, cell.metadata());
                created.setWorldObj(this);
                created.readFromNBT(
                    (NBTTagCompound) cell.tileData()
                        .copy());
                created.setWorldObj(this);
            }
            cells.put(
                new HologramNativeTargetPlan.Offset(cell.dx(), cell.dy(), cell.dz()),
                new Stored(cell.block(), cell.metadata(), created));
        }

        void hatch(HologramNativeTargetPlan.Offset at, int id) {
            var hatch = new BaseMetaTileEntity();
            hatch.xCoord = at.x();
            hatch.yCoord = 128 + at.y();
            hatch.zCoord = at.z();
            hatch.setWorldObj(this);
            hatch.setInitialValuesAsNBT(null, (short) id);
            hatch.setFrontFacing(ForgeDirection.WEST);
            cells.put(at, new Stored(GregTechAPI.sBlockMachines, 0, hatch));
        }

        private HologramNativeTargetPlan.Offset at(int x, int y, int z) {
            return new HologramNativeTargetPlan.Offset(x, y - 128, z);
        }

        @Override
        boolean previewChunkExists(int x, int z) {
            return true;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return !at(x, y, z).equals(unloaded);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            if (armed && x == neighborX) neighborReads++;
            if (x == 0 && y == 128 && z == 0) return GregTechAPI.sBlockMachines;
            var cell = cells.get(at(x, y, z));
            return cell == null ? Blocks.air : cell.block;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            var cell = cells.get(at(x, y, z));
            return cell == null ? 0 : cell.metadata;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (armed && x == neighborX) neighborReads++;
            if (x == 0 && y == 128 && z == 0) return tile;
            var cell = cells.get(at(x, y, z));
            return cell == null ? null : cell.tile;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
            throw new AssertionError("native site capture must not place world blocks");
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
            throw new AssertionError("native site capture must not edit world metadata");
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            throw new AssertionError("native site capture must not replace world tiles");
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            throw new AssertionError("native site capture must not remove world tiles");
        }
    }

    /** Offline equivalent of only the four production checkPiece HEAD/RETURN injections. */
    public static final class PieceFixtureTransformer implements IClassTransformer {

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (!"gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase".equals(transformedName)
                || bytes == null) return bytes;
            var output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            int[] hooks = { 0 };
            // Compile against Forge ASM5, then use the launcher's existing ASM9 visitor to preserve every .277
            // class attribute, including nest members. The older tree API silently drops those attributes.
            var visitor = new ClassVisitor(9 << 16, output) {

                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                    String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, method, descriptor, signature, exceptions);
                    if (!"checkPiece".equals(method) || !("(Ljava/lang/String;III)Z".equals(descriptor)
                        || "(Ljava/lang/String;IIILjava/util/List;)Z".equals(descriptor))) return target;
                    hooks[0]++;
                    return new MethodVisitor(9 << 16, target) {

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ALOAD, 1);
                            for (int local = 2; local <= 4; local++) visitVarInsn(Opcodes.ILOAD, local);
                            visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "com/miaokatze/gtmb/hologram/HologramNativeSiteCapture",
                                "pieceHead",
                                "(Ljava/lang/Object;Ljava/lang/String;III)V",
                                false);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                // Keep the real native return value on the stack and pass a duplicate after the
                                // six category-one hook arguments. No synthetic locals or changed frames.
                                super.visitInsn(Opcodes.DUP);
                                visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.SWAP);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitInsn(Opcodes.SWAP);
                                for (int local = 2; local <= 4; local++) {
                                    visitVarInsn(Opcodes.ILOAD, local);
                                    super.visitInsn(Opcodes.SWAP);
                                }
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "com/miaokatze/gtmb/hologram/HologramNativeSiteCapture",
                                    "pieceReturn",
                                    "(Ljava/lang/Object;Ljava/lang/String;IIIZ)V",
                                    false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            };
            new ClassReader(bytes).accept(visitor, 0);
            if (hooks[0] != 2) throw new AssertionError("Actual .277 checkPiece descriptors changed: " + hooks[0]);
            return output.toByteArray();
        }
    }
}
