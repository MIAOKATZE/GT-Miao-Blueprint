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
            Thread callbackOwner = Thread.currentThread();
            try (GTMBExtensions.Registration observer = BlueprintSnapshots
                .register("gtmb_test:dynamic-snapshot", BlueprintSnapshots.Stage.CAPTURE_COMPLETE, snapshot -> {
                    callbacks.incrementAndGet();
                    SimpleAssert.that(
                        Thread.currentThread() == callbackOwner,
                        "existing community capture processors retain their documented server thread");
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
            customIterator(controller);
            virtualMemberRebuild(unsafe);
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

    private static void virtualMemberRebuild(sun.misc.Unsafe unsafe) throws Exception {
        HologramWorkBudget.startServerTick();
        HologramWorkBudget.endServerTick();
        int id = 32760;
        IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        DynamicController prototype = new DynamicController();
        gregtech.api.GregTechAPI.METATILEENTITIES[id] = prototype;
        try {
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) unsafe
                .allocateInstance(net.minecraft.entity.player.EntityPlayerMP.class);
            player.worldObj = world;
            player.inventory = new net.minecraft.entity.player.InventoryPlayer(player);
            player.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
            player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
            session.virtual = true;
            session.virtualPreset = new HologramPresets.Preset();
            session.virtualPreset.schema = 2;
            HologramPresets.Structure selected = new HologramPresets.Structure();
            selected.id = "selected";
            selected.controllerId = id;
            selected.sourceClass = DynamicController.class.getName();
            session.virtualPreset.structures.add(selected);
            session.blueprintActiveStructure = selected.id;
            HologramCapture original = new HologramCapture(null, signal(1, 1));
            HologramCapture.Cell controller = new HologramCapture.Cell(null, 0, 64, 0);
            controller.block = Blocks.stone;
            controller.role = "templateController";
            controller.userPosition = true;
            HologramBlueprintData.setMemberships(controller, java.util.Collections.singletonList("selected"));
            HologramCapture.Cell unrelated = new HologramCapture.Cell(null, 200000, 64, 0);
            unrelated.block = Blocks.stone;
            unrelated.userPosition = true;
            unrelated.itemNbt = "preserved opaque edit";
            HologramBlueprintData.setMemberships(unrelated, java.util.Collections.singletonList("other"));
            original.cells.add(controller);
            original.cells.add(unrelated);
            original.positions.put("0,64,0", controller);
            original.positions.put("200000,64,0", unrelated);
            session.capture = original;
            session.pins.put(0, new ItemStack(Blocks.stone));
            session.pins.put(1, new ItemStack(Blocks.dirt));
            session.virtualEdited.add("200000,64,0");
            HologramPresets.Cell saved = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, 2, true);
            saved.structureId = "selected";
            net.minecraft.nbt.NBTTagCompound persistent = new net.minecraft.nbt.NBTTagCompound();
            persistent.setInteger("fixtureTail", 7);
            saved.tileNbt = HologramBlueprintNbt.encodeCaptured(persistent);
            controller.tileNbt = saved.tileNbt;
            session.virtualPreset.cells.add(saved);
            HologramPresets.Cell other = HologramPresets.cell(new ItemStack(Blocks.stone), 200000, 0, 0, -1, false);
            other.structureId = "other";
            session.virtualPreset.cells.add(other);
            net.minecraft.nbt.NBTTagList options = HologramVirtualStructureEditing.capabilities(session, selected)
                .toNBT();
            boolean observed = false;
            for (int i = 0; i < options.tagCount(); i++) observed |= "shape".equals(
                options.getCompoundTagAt(i)
                    .getString("id"));
            SimpleAssert.that(observed, "old saved member with empty channels discovers native construct channel");
            net.minecraft.nbt.NBTTagCompound channels = new net.minecraft.nbt.NBTTagCompound();
            channels.setInteger("shape", 3);
            HologramVirtualStructureEditing.Task cancelledTask = new HologramVirtualStructureEditing.Task(
                session,
                selected,
                2,
                channels);
            session.virtualStructureTask = cancelledTask;
            session.main = 2;
            session.mainExplicit = true;
            session.channels = (net.minecraft.nbt.NBTTagCompound) channels.copy();
            HologramBlueprintData.storeStructureConfiguration(session, selected);
            HologramVirtualStructureEditing.cancel(session, cancelledTask);
            SimpleAssert.that(
                session.capture == original && session.main == 1
                    && session.channels.hasNoTags()
                    && selected.main == 1
                    && selected.channels.isEmpty(),
                "retiring an unfinished member edit restores configuration without traversing its original geometry");
            net.minecraft.nbt.NBTTagCompound successorChannels = new net.minecraft.nbt.NBTTagCompound();
            successorChannels.setInteger("shape", 4);
            HologramVirtualStructureEditing.Task successor = new HologramVirtualStructureEditing.Task(
                session,
                selected,
                3,
                successorChannels);
            session.virtualStructureTask = successor;
            session.main = 3;
            session.channels = successorChannels;
            HologramBlueprintData.storeStructureConfiguration(session, selected);
            HologramVirtualStructureEditing.cancel(session, cancelledTask);
            SimpleAssert.that(
                session.virtualStructureTask == successor && session.main == 3
                    && session.channels.getInteger("shape") == 4
                    && selected.main == 3,
                "a delayed cancellation cannot retire or roll back a newer member task");
            HologramVirtualStructureEditing.cancel(session, successor);
            HologramVirtualStructureEditing.Task task = new HologramVirtualStructureEditing.Task(
                session,
                selected,
                2,
                channels);
            session.main = 2;
            session.channels = channels;
            HologramBlueprintData.storeStructureConfiguration(session, selected);
            AtomicInteger syntheticCaptures = new AtomicInteger();
            try (GTMBExtensions.Registration observer = BlueprintSnapshots
                .register("gtmb_test:virtual-not-capture", BlueprintSnapshots.Stage.CAPTURE_COMPLETE, snapshot -> {
                    syntheticCaptures.incrementAndGet();
                    return null;
                })) {
                int batches = 0;
                while (!task.step() && ++batches < 512) {}
                SimpleAssert.that(batches < 512, "selected member declaration finishes in bounded continuations");
                SimpleAssert.eq(0, syntheticCaptures.get(), "legacy virtual regeneration is not a world capture");
            }
            task.publish();
            SimpleAssert.eq(
                6309,
                session.capture.cells.size(),
                "changed native size replaces selected member and retains other member");
            SimpleAssert.that(
                session.capture.positions.get("200000,64,0") == unrelated,
                "other member retains the exact cell and opaque NBT edit");
            int otherIndex = session.capture.cells.indexOf(unrelated);
            SimpleAssert.that(
                session.pins.get(otherIndex)
                    .getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.dirt),
                "unrelated edited target survives index remapping");
            SimpleAssert.eq(1, prototype.tier, "native construct cannot overwrite registration prototype tier");
            SimpleAssert.eq(0, prototype.constructions, "registration prototype never receives construct");
            SimpleAssert.that(task.machine != prototype, "native geometry uses independent controller instance");
            SimpleAssert.eq(
                7,
                ((DynamicController) task.machine).persistentTail,
                "native loader preserves a saved construction field independently of the new channel");
            SimpleAssert.eq(3, prototype.persistentTail, "saved construction NBT cannot overwrite prototype state");
            HologramPresets.Cell rebuiltController = session.virtualPreset.cells.stream()
                .filter(cell -> cell.controller && cell.belongsTo("selected"))
                .findFirst()
                .get();
            SimpleAssert.eq(
                7,
                HologramBlueprintNbt.decode(rebuiltController.tileNbt)
                    .getInteger("fixtureTail"),
                "a later member resize can recover the same saved native construction state");
            SimpleAssert.that(
                gregtech.api.GregTechAPI.isDummyWorld(
                    task.machine.getBaseMetaTileEntity()
                        .getWorld()),
                "detached alignment stays inside GT's native no-network dummy-world boundary");
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    private static void customIterator(DynamicController controller) {
        com.gtnewhorizon.structurelib.structure.IStructureElement<DynamicController> element = StructureUtility
            .ofBlock(Blocks.dirt, 0);
        IStructureDefinition<DynamicController> custom = new IStructureDefinition<DynamicController>() {

            @SuppressWarnings("unchecked")
            public com.gtnewhorizon.structurelib.structure.IStructureElement<DynamicController>[] getStructureFor(
                String name) {
                return new com.gtnewhorizon.structurelib.structure.IStructureElement[] { element, element, element };
            }

            public boolean isContainedInStructure(String name, int a, int b, int c) {
                return false;
            }

            @Override
            public void iterate(String name, net.minecraft.world.World world, ExtendedFacing facing, int x, int y,
                int z, int a, int b, int c,
                com.gtnewhorizon.structurelib.structure.IStructureWalker<DynamicController> walker) {
                // The reviewed native override intentionally exposes a different footprint from its raw array.
                walker.visit(element, world, x + 17, y + 1, z, 17, 1, 0);
                walker.visit(element, world, x + 18, y + 1, z, 18, 1, 0);
            }
        };
        SimpleAssert.that(!HologramStructureCursor.supports(custom), "custom iterator must retain its native callback");
        try (GTMBExtensions.Registration registration = StructureSources.register(
            "gtmb_test:custom-native-iterator",
            DynamicController.class,
            (machine, trigger) -> new StructureSources.Plan(
                custom,
                Arrays.asList(new StructureSources.Piece("custom", 0, 0, 0, trigger))))) {
            HologramCapture capture = complete(controller, signal(1, 1));
            SimpleAssert.eq(3, capture.cells.size(), "custom override's two cells and controller anchor are retained");
            SimpleAssert.that(
                capture.positions.containsKey("17,101,0") && capture.positions.containsKey("18,101,0")
                    && !capture.positions.containsKey("1,100,0"),
                "custom native coordinates cannot be replaced by array traversal");
        }
    }

    private static HologramCapture complete(DynamicController controller, ItemStack trigger) {
        HologramCapture capture = null;
        int batches = 0;
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            capture = HologramCapture.collect(controller, trigger, ExtendedFacing.DEFAULT, capture);
            batches++;
            if (capture.pending && capture.walkingComplete) Thread.yield();
        } while (capture.pending && System.nanoTime() < deadline);
        SimpleAssert.that(
            !capture.pending && !capture.incomplete,
            "capture completed: " + capture.failure
                + "; pending="
                + capture.pending
                + "; incomplete="
                + capture.incomplete
                + "; batches="
                + batches
                + "; cells="
                + capture.cells.size()
                + "; origin="
                + capture.failureOrigin);
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

        int tier = 1, persistentTail = 3, definitionBuilds, constructions;

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
                .addShape("tail", new String[][] { { new String(new char[persistentTail]).replace('\0', 'A') } })
                .addElement('A', StructureUtility.ofBlock(Blocks.stone, tier))
                .build();
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            constructions++;
            tier = ChannelDataAccessor.getChannelData(trigger, "shape");
            HologramChannelTrace.record(trigger, "shape", tier, 1);
            // Raw harness invokes the same hook installed at native buildPiece HEAD by Mixin.
            HologramCapture.piece(this, "tier" + tier, trigger, hints, tier, 0, 1);
            HologramCapture.piece(this, "tail", trigger, hints, -150, 0, 1);
        }

        @Override
        public ItemStack getStackForm(long count) {
            return new ItemStack(Blocks.stone, (int) count);
        }

        @Override
        public void loadNBTData(net.minecraft.nbt.NBTTagCompound tag) {
            super.loadNBTData(tag);
            persistentTail = Math.max(3, tag.getInteger("fixtureTail"));
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return null;
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new DynamicController();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }
}
