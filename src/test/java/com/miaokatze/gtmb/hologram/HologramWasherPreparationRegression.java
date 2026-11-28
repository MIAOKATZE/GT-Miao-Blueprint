// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.threads.RunnableMachineUpdate;
import gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.processing.MTEIndustrialWashPlantLegacy;

/** Uses GT5U's actual legacy washer construction and water/air chain; no substitute shape or build callback. */
final class HologramWasherPreparationRegression {

    private static final int ID = 32653;

    private HologramWasherPreparationRegression() {}

    static void run() {
        Thread thread = Thread.currentThread();
        String oldName = thread.getName();
        thread.setName("Server thread");
        try {
            SimpleAssert.eq(
                cpw.mods.fml.relauncher.Side.SERVER,
                cpw.mods.fml.common.FMLCommonHandler.instance()
                    .getEffectiveSide(),
                "the actual GT washer uses native server-side construction semantics");
            HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                try {
                    verify();
                } catch (ReflectiveOperationException | IOException failure) {
                    throw new AssertionError("actual GT5U industrial washer preparation", failure);
                }
            });
        } catch (Exception failure) {
            throw new AssertionError("actual industrial washer offline registry fixture", failure);
        } finally {
            thread.setName(oldName);
        }
    }

    private static void verify() throws ReflectiveOperationException, IOException {
        Block oldBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity oldPrototype = GregTechAPI.METATILEENTITIES[ID];
        // GT5U caches this definition in a static field, including the first instance's casing and piece name.
        Field definition = MTEIndustrialWashPlantLegacy.class.getDeclaredField("STRUCTURE_DEFINITION");
        definition.setAccessible(true);
        Object oldDefinition = definition.get(null);
        boolean instrument = StructureLibAPI.isInstrumentEnabled();
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        // The detached loader has no Forge event-class transformer (the normal game does).
        // Register the real consumer on the untransformed event's inherited listener list so the
        // washer's actual StructureLib walker/fireEvent reaches production capture. No visits are invented.
        Field busId = cpw.mods.fml.common.eventhandler.EventBus.class.getDeclaredField("busID");
        busId.setAccessible(true);
        int bus = busId.getInt(net.minecraftforge.common.MinecraftForge.EVENT_BUS);
        cpw.mods.fml.common.eventhandler.ListenerList listeners = new cpw.mods.fml.common.eventhandler.Event()
            .getListenerList();
        cpw.mods.fml.common.eventhandler.IEventListener visited = event -> {
            if (event instanceof com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent nativeVisit)
                new HologramNativeStructurePreview.VisitedListener().elementVisited(nativeVisit);
        };
        listeners.register(bus, cpw.mods.fml.common.eventhandler.EventPriority.NORMAL, visited);
        try {
            definition.set(null, null);
            GregTechAPI.sBlockMachines = Blocks.iron_block;
            Washer washer = new Washer();
            BaseMetaTileEntity base = new BaseMetaTileEntity();
            washer.setBaseMetaTileEntity(base);
            base.setMetaTileID((short) ID);
            GregTechAPI.METATILEENTITIES[ID] = washer;
            SimpleAssert.eq(
                MTEIndustrialWashPlantLegacy.class,
                Washer.class
                    .getMethod(
                        "survivalConstruct",
                        ItemStack.class,
                        int.class,
                        com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment.class)
                    .getDeclaringClass(),
                "the fixture executes the real washer survival callback");
            SimpleAssert.eq(
                MTEIndustrialWashPlantLegacy.class,
                Washer.class.getMethod("getStructureDefinition")
                    .getDeclaringClass(),
                "the fixture executes the real washer shape and water/air declaration");

            ItemStack trigger = HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound());
            try (HologramNativeTargetPlan target = HologramNativeTargetPlan
                .begin(ID, ExtendedFacing.DEFAULT, trigger, new NBTTagCompound())) {
                TileEntity controller = target.controller();
                World originalWorld = controller.getWorldObj();
                SimpleAssert.that(!target.advance(0), "an expired target deadline does not start washer construction");
                SimpleAssert.that(
                    !target.ready() && target.cells()
                        .isEmpty(),
                    "expired preparation publishes no partial target");
                SimpleAssert
                    .eq(originalWorld, controller.getWorldObj(), "expired preparation preserves the controller world");
                int advances = 0;
                while (!target.ready() && advances < 512) {
                    target.advance(System.nanoTime() + 1_000_000L);
                    advances++;
                    restored(instrument, updates);
                }
                SimpleAssert.that(
                    target.ready() && target.complete(),
                    "the actual washer completes within bounded advancement calls");
                // Native shape: 5*3*7 cells, minus the controller and 30 water-or-air interior positions.
                SimpleAssert.eq(
                    74,
                    target.cells()
                        .size(),
                    "default washer preparation retains every native casing position");
                SimpleAssert.that(
                    target.cells()
                        .stream()
                        .allMatch(cell -> cell.element() != null),
                    "actual StructureLib visit events reach the real projector consumer");
                for (var cell : target.cells()) {
                    SimpleAssert.that(
                        cell.block() == Blocks.stone && cell.metadata() == 0 && !cell.requiredAir(),
                        "unconfigured washer targets use its casing and omit the native optional water volume");
                    SimpleAssert.that(
                        cell.material() != null && cell.material()
                            .getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone),
                        "optional water is not charged as a water bucket or synthetic material");
                }
                SimpleAssert.that(
                    !target.nativeFormed(),
                    "default casing geometry does not claim missing mandatory hatches formed");
                SimpleAssert.that(
                    target.targetDigest()
                        .matches("[0-9a-f]{64}"),
                    "completed real washer target has a canonical digest");
                SimpleAssert
                    .eq(originalWorld, controller.getWorldObj(), "completed preparation restores the original world");
            }

            HologramNativeTargetPlan cancelled = HologramNativeTargetPlan
                .begin(ID, ExtendedFacing.DEFAULT, trigger, new NBTTagCompound());
            TileEntity cancelledController = cancelled.controller();
            World cancelledWorld = cancelledController.getWorldObj();
            cancelled.advance(0);
            cancelled.close();
            SimpleAssert.that(
                cancelled.controller() == null && cancelled.context() == null,
                "cancelling an unpublished washer releases the owned native controller and context");
            SimpleAssert
                .eq(cancelledWorld, cancelledController.getWorldObj(), "cancel restores the controller's base world");
            try {
                cancelled.advance(Long.MAX_VALUE);
                throw new AssertionError("Cancelled washer target resumed construction");
            } catch (IOException expected) {}
            restored(instrument, updates);
        } finally {
            listeners.unregister(bus, visited);
            definition.set(null, oldDefinition);
            GregTechAPI.METATILEENTITIES[ID] = oldPrototype;
            GregTechAPI.sBlockMachines = oldBlock;
        }
    }

    private static void restored(boolean instrument, boolean updates) {
        SimpleAssert
            .eq(instrument, StructureLibAPI.isInstrumentEnabled(), "washer advancement restores instrumentation");
        SimpleAssert
            .eq(updates, RunnableMachineUpdate.isCurrentThreadEnabled(), "washer advancement restores machine updates");
    }

    /** Only replaces unavailable GT++ casing registration; native construction and machine checks stay inherited. */
    private static final class Washer extends MTEIndustrialWashPlantLegacy {

        Washer() {
            super("gtmb_actual_washer_preparation_fixture");
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new Washer();
        }

        @Override
        public Block getCasingBlock() {
            return Blocks.stone;
        }

        @Override
        public byte getCasingMeta() {
            return 0;
        }

        @Override
        public byte getCasingTextureIndex() {
            return 1;
        }
    }
}
