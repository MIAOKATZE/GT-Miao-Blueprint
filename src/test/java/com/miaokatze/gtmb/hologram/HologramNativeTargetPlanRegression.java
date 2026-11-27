// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Real compiler coverage for custom cursor constructors without a definition or visit events. */
final class HologramNativeTargetPlanRegression {

    private static final int ID = 32666;

    static void run() {
        try {
            HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                try {
                    verify();
                } catch (IOException failure) {
                    throw new AssertionError("native target generation", failure);
                }
            });
        } catch (Exception failure) {
            throw new AssertionError("native target registry fixture", failure);
        }
    }

    private static HologramNativeTargetPlan compile(boolean footprint) throws IOException {
        ItemStack trigger = HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound());
        return footprint ? HologramNativeTargetPlan.compileFootprint(ID, ExtendedFacing.DEFAULT, trigger)
            : HologramNativeTargetPlan.compile(ID, ExtendedFacing.DEFAULT, trigger, new NBTTagCompound());
    }

    private static void verify() throws IOException {
        Block previousBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[ID];
        try {
            GregTechAPI.sBlockMachines = Blocks.iron_block;
            GregTechAPI.METATILEENTITIES[ID] = new Controller(0);
            HologramNativeTargetPlan target = compile(false);
            SimpleAssert.eq(null, target.definition(), "custom constructor needs no definition");
            SimpleAssert.eq(
                2,
                target.cells()
                    .size(),
                "unvisited real writes form the complete target");
            Controller built = (Controller) target.context();
            SimpleAssert.eq(6, built.passes, "four empty cursor slices and a final write reach -1");
            SimpleAssert.eq(0, built.started + built.completed, "ordinary targets never run NEI hooks");
            for (var cell : target.cells()) {
                SimpleAssert.eq(null, cell.element(), "custom targets retain optional element absence");
                SimpleAssert.that(!cell.requiredAir(), "sparse unvisited blank space is never required air");
                SimpleAssert.eq(
                    cell.dx() == 1 ? "replacement" : "second",
                    cell.material()
                        .getTagCompound()
                        .getString("nativeMaterial"),
                    "exact material NBT follows the write coordinate, including same-coordinate replacement");
            }
            SimpleAssert.eq(target.footprint(), compile(true).footprint(), "footprint reuses completed native output");
            SimpleAssert.that(
                !target.footprint()
                    .contains(new HologramNativeTargetPlan.Offset(7, 0, 0)),
                "hint-only writes cannot become destruction authority");

            GregTechAPI.METATILEENTITIES[ID] = new Controller(1);
            HologramNativeTargetPlan creative = compile(false);
            SimpleAssert.eq(
                1,
                creative.cells()
                    .size(),
                "unsupported survival prefix is discarded");
            SimpleAssert.eq(
                Blocks.gold_block,
                creative.cells()
                    .get(0)
                    .block(),
                "fresh creative result is selected");
            SimpleAssert.eq(0, ((Controller) creative.context()).passes, "creative fallback owns a new controller");

            for (int mode : new int[] { 2, 3, 4 }) {
                GregTechAPI.METATILEENTITIES[ID] = new Controller(mode);
                try {
                    compile(false);
                    throw new AssertionError("accepted incomplete/invalid native mode " + mode);
                } catch (IOException expected) {
                    SimpleAssert.that(
                        !com.gtnewhorizon.structurelib.StructureLibAPI.isInstrumentEnabled(),
                        "failed generation restores the instrument scope");
                }
            }

            GregTechAPI.METATILEENTITIES[ID] = new Controller(0);
            HologramResourceWorld base = HologramResourceWorld.declarations(Blocks.iron_block, 0);
            BaseMetaTileEntity tile = new BaseMetaTileEntity();
            tile.setWorldObj(base);
            tile.yCoord = 128;
            base.tile = tile;
            tile.setInitialValuesAsNBT(null, (short) ID);
            Controller context = (Controller) tile.getMetaTileEntity();
            try (var preview = new HologramNativeStructurePreview(
                tile,
                context,
                context,
                false,
                HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound()))) {
                SimpleAssert.that(preview.advance(Long.MAX_VALUE), "NEI-only completion creates display geometry");
                SimpleAssert.eq(1, context.started, "NEI start runs once");
                SimpleAssert.eq(1, context.completed, "NEI completion runs once");
                SimpleAssert.eq(
                    2,
                    context.passes,
                    "NEI ignores survival 0 and finishes after the initial controller change settles");
                var shown = preview.placements();
                SimpleAssert.eq(
                    Blocks.glass,
                    shown.next()
                        .block(),
                    "completion creates the entire display geometry");
                SimpleAssert.that(!shown.hasNext(), "NEI examples never enter ordinary target generation");
                SimpleAssert.eq(base, tile.getWorldObj(), "display restores the detached base world");
            }
        } finally {
            GregTechAPI.sBlockMachines = previousBlock;
            GregTechAPI.METATILEENTITIES[ID] = previous;
        }
    }

    private static final class Controller extends MTEEnhancedMultiBlockBase<Controller>
        implements ISurvivalConstructable, INEIPreviewModifier {

        final int mode;
        int passes, started, completed;

        Controller(int mode) {
            super("gtmb_native_target_fixture");
            this.mode = mode;
        }

        @Override
        public IStructureDefinition<Controller> getStructureDefinition() {
            return null;
        }

        @Override
        public int survivalConstruct(ItemStack signal, int budget, ISurvivalBuildEnvironment env) {
            passes++;
            if (started > 0) return 0;
            World world = getBaseMetaTileEntity().getWorld();
            if (mode == 2) return 0;
            if (mode == 3) return -3;
            if (mode == 4) throw new IllegalStateException("fixture failure");
            if (mode == 1) {
                world.setBlock(9, 128, 0, Blocks.stone, 0, 3);
                return -2;
            }
            if (passes == 1) {
                place(env, world, 1, "first");
                place(env, world, 2, "second");
                return 2;
            }
            if (passes <= 5) return 0;
            world.setBlockToAir(1, 128, 0);
            place(env, world, 1, "replacement");
            return -1;
        }

        private static void place(ISurvivalBuildEnvironment env, World world, int x, String label) {
            ItemStack material = new ItemStack(Blocks.stone, 1, 0);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("nativeMaterial", label);
            material.setTagCompound(tag);
            SimpleAssert.that(
                env.getSource()
                    .takeOne(material, true),
                "native source simulates the exact item");
            world.setBlock(x, 128, 0, Blocks.stone, 0, 3);
            SimpleAssert.that(
                env.getSource()
                    .takeOne(material, false),
                "native source confirms the exact item");
        }

        @Override
        public void construct(ItemStack signal, boolean hints) {
            getBaseMetaTileEntity().getWorld()
                .setBlock(hints ? 7 : 3, 128, 0, hints ? Blocks.glass : Blocks.gold_block, 0, 3);
        }

        @Override
        public void onPreviewConstruct(ItemStack signal) {
            started++;
        }

        @Override
        public void onPreviewStructureComplete(ItemStack signal) {
            completed++;
            getBaseMetaTileEntity().getWorld()
                .setBlock(8, 128, 0, Blocks.glass, 0, 3);
        }

        @Override
        public boolean checkStructure(boolean reset, IGregTechTileEntity tile) {
            return mMachine = false;
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return new MultiblockTooltipBuilder();
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new Controller(mode);
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }
}
