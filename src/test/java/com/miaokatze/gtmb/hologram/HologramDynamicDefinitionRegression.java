package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/**
 * Native GT controller interface and real StructureLib iteration with rebuilt definitions and forbidden world writes.
 */
final class HologramDynamicDefinitionRegression {

    private HologramDynamicDefinitionRegression() {}

    static void run() {
        String threadName = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            DynamicController controller = new DynamicController();
            NativeTile tile = (NativeTile) unsafe.allocateInstance(NativeTile.class);
            tile.setWorldObj(world);
            tile.yCoord = 100;
            controller.setBaseMetaTileEntity(tile);
            // Warm declaration classes outside the production callback budget.
            controller.getStructureDefinition();
            controller.definitionBuilds = 0;
            AtomicInteger callbacks = new AtomicInteger();
            try (GTMBExtensions.Registration observer = BlueprintSnapshots
                .register("gtmb_test:dynamic-snapshot", BlueprintSnapshots.Stage.CAPTURE_COMPLETE, snapshot -> {
                    callbacks.incrementAndGet();
                    SimpleAssert.that(snapshot.cells.size() > 6000, "processor sees completed declaration only");
                    net.minecraft.nbt.NBTTagCompound report = new net.minecraft.nbt.NBTTagCompound();
                    report.setInteger("cells", snapshot.cells.size());
                    return report;
                })) {
                HologramCapture first = complete(controller, signal(1, 1));
                SimpleAssert.eq(1, controller.definitionBuilds, "continuations retain one definition identity graph");
                SimpleAssert.eq(1, controller.constructions, "continuations never replay construct");
                SimpleAssert.eq(6104, first.cells.size(), "all dynamic piece cells and protected controller collected");
                SimpleAssert.eq(2, first.pieces.size(), "both pieces retain native offsets");
                SimpleAssert.eq(1, callbacks.get(), "completion processor runs once across all batches");
                SimpleAssert.eq(
                    6104,
                    first.extensionReports.getCompoundTag("gtmb_test:dynamic-snapshot")
                        .getInteger("cells"),
                    "namespaced report retained");
                HologramCapture second = complete(controller, signal(2, 3));
                SimpleAssert.eq(2, controller.definitionBuilds, "new capture freezes a fresh definition");
                SimpleAssert.that(first.definition != second.definition, "definition is never cached across captures");
                SimpleAssert.eq("tier3", second.pieces.get(0).name, "channel selects dynamic piece name");
                SimpleAssert.eq(3, second.pieces.get(0).a, "native dynamic controller offset retained");
                SimpleAssert.eq(6304, second.cells.size(), "new channel changes footprint without truncation");
                SimpleAssert
                    .eq(2, second.pieces.get(0).trigger.stackSize, "main signal remains independent of channel");
            }
            int builds = controller.definitionBuilds, constructions = controller.constructions;
            AtomicInteger sourceCalls = new AtomicInteger();
            try (GTMBExtensions.Registration source = StructureSources
                .register("gtmb_test:dynamic-source", DynamicController.class, (machine, trigger) -> {
                    sourceCalls.incrementAndGet();
                    int requestedMain = trigger.stackSize;
                    trigger.stackSize = 42;
                    IStructureDefinition<DynamicController> definition = StructureDefinition
                        .<DynamicController>builder()
                        .addShape("external", new String[][] { { "AA" } })
                        .addElement('A', StructureUtility.ofBlock(Blocks.dirt, 0))
                        .build();
                    return new StructureSources.Plan(
                        definition,
                        Arrays.asList(
                            new StructureSources.Piece("external", requestedMain == 7 ? 0 : -8, 0, 0, trigger)));
                })) {
                ItemStack original = signal(2, 1);
                HologramCapture external = complete(controller, original);
                SimpleAssert.eq(1, sourceCalls.get(), "registered source called once per capture");
                SimpleAssert.eq(builds, controller.definitionBuilds, "source bypasses native definition factory");
                SimpleAssert.eq(constructions, controller.constructions, "source bypasses native construct callback");
                SimpleAssert.eq(2, original.stackSize, "source receives detached trigger");
                SimpleAssert
                    .eq(3, external.cells.size(), "source plan enters production traversal and adds protected anchor");
                HologramCapture overlap = complete(controller, signal(7, 1));
                SimpleAssert
                    .eq(2, overlap.cells.size(), "controller inside declared shape is replaced, not double counted");
                HologramCapture.Cell anchor = overlap.positions.get("0,100,0");
                SimpleAssert.that(
                    anchor != null && anchor.anchor && anchor.element == null && overlap.cells.contains(anchor),
                    "source overlap resolves to the same protected anchor in both coordinate map and cell list");
                SimpleAssert.eq(2, sourceCalls.get(), "fresh source captures keep independent declaration plans");
                SimpleAssert.that(source.active(), "source lifecycle handle is active");
            }
            SimpleAssert
                .that(!StructureSources.hasSource(DynamicController.class), "closing source removes exact provider");
        } catch (Exception failure) {
            throw new AssertionError("dynamic native definition capture", failure);
        } finally {
            Thread.currentThread()
                .setName(threadName);
        }
    }

    private static ItemStack signal(int main, int tier) {
        ItemStack trigger = new ItemStack(Blocks.stone, main);
        ChannelDataAccessor.setChannelData(trigger, "shape", tier);
        return trigger;
    }

    private static HologramCapture complete(DynamicController controller, ItemStack trigger) {
        HologramCapture capture = null;
        int batches = 0;
        do {
            capture = HologramCapture.collect(controller, trigger, ExtendedFacing.DEFAULT, capture);
            batches++;
        } while (capture.pending && batches < 128);
        SimpleAssert.that(!capture.pending && !capture.incomplete, "capture completed: " + capture.failure);
        if (capture.cells.size() > 4096)
            SimpleAssert.that(batches > 1, "real collect continuation spans server batches");
        return capture;
    }

    static final class NativeTile extends BaseMetaTileEntity {

        @Override
        public ForgeDirection getFrontFacing() {
            return ForgeDirection.NORTH;
        }
    }

    static final class DynamicController extends MTEEnhancedMultiBlockBase<DynamicController> {

        int tier = 1, definitionBuilds, constructions;

        DynamicController() {
            super("dynamic_definition_fixture");
        }

        @Override
        public IStructureDefinition<DynamicController> getStructureDefinition() {
            definitionBuilds++;
            String[] rows = new String[60 + tier];
            Arrays.fill(rows, new String(new char[100]).replace('\0', 'A'));
            return StructureDefinition.<DynamicController>builder()
                .addShape("tier" + tier, new String[][] { rows })
                .addShape("tail", new String[][] { { "AAA" } })
                .addElement('A', StructureUtility.ofBlock(Blocks.stone, tier))
                .build();
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            constructions++;
            tier = ChannelDataAccessor.getChannelData(trigger, "shape");
            // Raw harness invokes the same hook installed at native buildPiece HEAD by Mixin.
            HologramCapture.piece(this, "tier" + tier, trigger, hints, tier, 0, 1);
            HologramCapture.piece(this, "tail", trigger, hints, -150, 0, 1);
        }

        @Override
        public ItemStack getStackForm(long count) {
            return new ItemStack(Blocks.stone, (int) count);
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return null;
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return null;
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }
}
