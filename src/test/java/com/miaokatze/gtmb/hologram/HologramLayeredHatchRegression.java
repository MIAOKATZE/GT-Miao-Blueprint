// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.HatchElementBuilder;
import gregtech.api.util.IGTHatchAdder;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Native decorated hatch candidates retain manual editing without claiming whole-machine layer quotas. */
final class HologramLayeredHatchRegression {

    private static final int OUTPUT_ID = 32748, ENERGY_ID = 32747, STEAM_OUTPUT_ID = 32746;
    private static final int INPUT_BUS_ID = 32745, SPECIAL_BUS_ID = 32744;

    private HologramLayeredHatchRegression() {}

    static void run() {
        Block previousMachines = GregTechAPI.sBlockMachines;
        IMetaTileEntity[] previous = GregTechAPI.METATILEENTITIES.clone();
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            Block machines = new Block(net.minecraft.block.material.Material.iron) {};
            Block.blockRegistry.addObject(4069, "gtmb:layered_hatch_machines", machines);
            Item.itemRegistry
                .addObject(4069, "gtmb:layered_hatch_machines", new gregtech.common.blocks.ItemMachines(machines));
            GregTechAPI.sBlockMachines = machines;
            // The native candidate collector enumerates the registry. Keep this fixture independent of other tests.
            Arrays.fill(GregTechAPI.METATILEENTITIES, null);
            GregTechAPI.METATILEENTITIES[OUTPUT_ID] = (OutputFixture) unsafe.allocateInstance(OutputFixture.class);
            GregTechAPI.METATILEENTITIES[ENERGY_ID] = (EnergyFixture) unsafe.allocateInstance(EnergyFixture.class);
            IHatchElement<MTEMultiBlockBase> layered = HatchElement.OutputHatch
                .withCount(context -> { throw new AssertionError("layer count executed while editing"); })
                .withAdder(
                    (context, tile, texture) -> { throw new AssertionError("layer adder executed while editing"); });
            try {
                mixedNativeChooser(unsafe, machines);
            } finally {
                // The isolated Unsafe nodes have no GT host; do not leave them in later native registry walks.
                GregTechAPI.METATILEENTITIES[INPUT_BUS_ID] = null;
                GregTechAPI.METATILEENTITIES[SPECIAL_BUS_ID] = null;
            }
            nativeCandidatesAndPin(unsafe, machines, layered);
            localFacingChoices(machines, layered);
            contextualPresets(unsafe, machines, layered);
            unknownBoundaries(unsafe, layered);
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("native decorated hatch declaration boundary", failure);
        } finally {
            GregTechAPI.sBlockMachines = previousMachines;
            System.arraycopy(previous, 0, GregTechAPI.METATILEENTITIES, 0, previous.length);
        }
    }

    private static void mixedNativeChooser(sun.misc.Unsafe unsafe, Block machines) throws ReflectiveOperationException {
        var fresh = (FacingOnlyController) unsafe.allocateInstance(FacingOnlyController.class);
        var settled = (FacingOnlyController) unsafe.allocateInstance(FacingOnlyController.class);
        var bus = (InputBusFixture) unsafe.allocateInstance(InputBusFixture.class);
        var special = (SpecialBusFixture) unsafe.allocateInstance(SpecialBusFixture.class);
        GregTechAPI.METATILEENTITIES[INPUT_BUS_ID] = bus;
        GregTechAPI.METATILEENTITIES[SPECIAL_BUS_ID] = special;
        fresh.mInputBusses = new ArrayList<>();
        fresh.mDualInputHatches = new ArrayList<>();
        fresh.mEnergyHatches = new ArrayList<>();
        fresh.mOutputHatches = new ArrayList<>();
        settled.mInputBusses = new ArrayList<>(Collections.singletonList(bus));
        settled.mDualInputHatches = new ArrayList<>();
        settled.mEnergyHatches = new ArrayList<>();
        settled.mOutputHatches = new ArrayList<>();
        ItemStack casingA = new ItemStack(Blocks.stone), casingC = new ItemStack(Blocks.dirt);
        ItemStack input = new ItemStack(machines, 1, INPUT_BUS_ID),
            extended = new ItemStack(machines, 1, SPECIAL_BUS_ID), fluid = new ItemStack(machines, 1, OUTPUT_ID),
            energy = new ItemStack(machines, 1, ENERGY_ID);
        var busA = witnessedAtLeast(Collections.singletonMap(HatchElement.InputBus.or(specialRole()), 1));
        var fluidOnly = witnessedAtLeast(Collections.singletonMap(HatchElement.OutputHatch.or(specialRole()), 1));
        var energyC = witnessedAtLeast(Collections.singletonMap(HatchElement.Energy, 1));
        int[] opaqueQueries = { 0 };
        IStructureElement<MTEMultiBlockBase> opaque = StructureUtility.onlyIf(context -> {
            opaqueQueries[0]++;
            return false;
        }, energyC);
        var a = StructureUtility.withChannel(
            "gtmb_test_mixed_hatch",
            StructureUtility.ofChain(opaque, busA, StructureUtility.ofBlock(Blocks.stone, 0)));
        var c = StructureUtility.ofChain(energyC, StructureUtility.ofBlock(Blocks.dirt, 0));
        var fluidRow = StructureUtility.ofChain(fluidOnly, StructureUtility.ofBlock(Blocks.stone, 0));
        ItemStack trigger = casingA.copy();
        ChannelDataAccessor.setChannelData(trigger, "gtmb_test_mixed_hatch", 3);
        var used = busA.getBlocksToPlace(settled, null, 0, 64, 0, trigger, null)
            .getPredicate();
        SimpleAssert.that(
            !used.test(input) && !used.test(extended),
            "an already populated A quota hides both native buses and the old stock parser cannot supplement them");
        int[] factories = { 0 }, queries = { 0 };
        HologramNativeHatchChooser chooser = new HologramNativeHatchChooser(
            () -> Arrays.asList(input, input.copy(), extended, fluid, energy, casingA, casingC),
            () -> {
                factories[0]++;
                return (element, x, y, z, stack) -> {
                    queries[0]++;
                    if (element == a) SimpleAssert.that(
                        y == 64 && z == 0
                            && (x == 0 || x == 4)
                            && ChannelDataAccessor.getChannelData(stack, "gtmb_test_mixed_hatch") >= 3,
                        "public root query keeps the slot and lets the native channel wrapper transform its signal");
                    return getNativeOffers(element, fresh, x, y, z, stack);
                };
            });
        SimpleAssert.that(
            chooser.offers(c, 2, 64, 0, trigger)
                .size() == 1 && HologramValues.same(
                    chooser.offers(c, 2, 64, 0, trigger)
                        .get(0),
                    energy)
                && factories[0] == 1
                && queries[0] == 2,
            "reviewed stock C has its own energy offer and opens the one capture-owned unfilled controller");

        HologramCapture.Cell rowA = new HologramCapture.Cell(a, 0, 64, 0, trigger, true);
        HologramNativeStructurePreview.readCandidates(
            rowA,
            IStructureElement.BlocksToPlace.create(casingA),
            false,
            Collections::emptyList,
            () -> chooser.offers(a, 0, 64, 0, trigger));
        SimpleAssert.that(
            rowA.nativeCandidateCount == 1 && !rowA.candidateComplete
                && rowA.candidates.size() == 3
                && HologramValues.same(rowA.candidates.get(0), casingA)
                && HologramValues.same(rowA.candidates.get(1), input)
                && HologramValues.same(rowA.candidates.get(2), extended),
            "A retains the exact settled autos and offers only proved manual bus variants, not fluid/energy outsiders");

        HologramCapture.Cell rowC = new HologramCapture.Cell(c, 2, 64, 0, trigger, true);
        HologramNativeStructurePreview.readCandidates(
            rowC,
            IStructureElement.BlocksToPlace.create(casingC, energy),
            false,
            Collections::emptyList,
            () -> chooser.offers(c, 2, 64, 0, trigger));
        SimpleAssert.that(
            rowC.candidates.size() == 2 && HologramValues.same(rowC.candidates.get(1), energy)
                && rowC.candidateComplete
                && rowC.nativeCandidateCount == -1,
            "C retains its own settled roles with neither A's buses nor a manual-only marker");
        SimpleAssert.that(
            chooser.offers(a, 4, 64, 0, trigger)
                .size() == 2 && queries[0] == 5
                && opaqueQueries[0] == 2,
            "a different A position performs its own complete public native query");

        HologramCapture.Cell rowFluid = new HologramCapture.Cell(fluidRow, 6, 64, 0, trigger, true);
        HologramNativeStructurePreview.readCandidates(
            rowFluid,
            null,
            true,
            Collections::emptyList,
            () -> chooser.offers(fluidRow, 6, 64, 0, trigger));
        SimpleAssert.that(
            rowFluid.candidates.size() == 2 && rowFluid.nativeCandidateCount == 0
                && !rowFluid.candidateComplete
                && HologramValues.same(rowFluid.candidates.get(0), extended)
                && HologramValues.same(rowFluid.candidates.get(1), fluid)
                && queries[0] == 6
                && factories[0] == 1,
            "native fluid sibling remains available even without settled candidates and never becomes a preset");
        SimpleAssert.that(
            fresh.mInputBusses.isEmpty() && fresh.mOutputHatches.isEmpty()
                && settled.mInputBusses.size() == 1
                && HologramHatchPolicy.predicate(busA, null, null) == null
                && !HologramHatchPolicy.globalRequirementsComplete(busA),
            "chooser leaves both construction contexts and existing structural/whole-machine proof unchanged");
        ItemStack changedTrigger = trigger.copy();
        ChannelDataAccessor.setChannelData(changedTrigger, "gtmb_test_mixed_hatch", 5);
        SimpleAssert.that(
            chooser.offers(a, 4, 64, 0, changedTrigger)
                .size() == 2 && queries[0] == 7,
            "an exact changed trigger does not reuse a prior capture signal's offers");
        chooser.clear();

        HologramNativeStructurePreview.readCandidates(
            rowFluid,
            IStructureElement.BlocksToPlace
                .create(stack -> { throw new IllegalStateException("opaque settled predicate"); }),
            true,
            () -> Collections.singletonList(fluid),
            () -> Collections.singletonList(fluid));
        SimpleAssert.that(
            rowFluid.candidates.size() == 1 && rowFluid.nativeCandidateCount == 0 && !rowFluid.candidateComplete,
            "failed settled predicates never swallow independent manual proof");

        var builder = HatchElementBuilder.<MTEMultiBlockBase>builder()
            .atLeast(HatchElement.InputBus.or(specialRole()))
            .casingIndex(1)
            .hint(1);
        var oldLeaf = builder.build();
        var unknownRoles = Collections
            .<IHatchElement<? super MTEMultiBlockBase>, Integer>singletonMap(specialRole(), 1);
        HologramHatchPolicy.declare(builder, unknownRoles.keySet(), true);
        HologramHatchPolicy.requirements(builder, unknownRoles);
        HologramHatchPolicy.built(builder, oldLeaf);
        SimpleAssert
            .that(HologramHatchPolicy.nativeBrowseAtLeast(oldLeaf), "plain mixed builder qualifies for chooser");
        builder.hatchItemFilter(mte -> stack -> { throw new AssertionError("replacement filter must not run"); });
        HologramHatchPolicy.reset(builder);
        SimpleAssert.that(
            !HologramHatchPolicy.nativeBrowseAtLeast(oldLeaf),
            "an older native leaf cannot call a replacement filter via its retained mutable builder");
    }

    private static IHatchElement<MTEMultiBlockBase> specialRole() {
        return new IHatchElement<>() {

            public List<Class<? extends IMetaTileEntity>> mteClasses() {
                return Collections.singletonList(SpecialBusFixture.class);
            }

            public IGTHatchAdder<MTEMultiBlockBase> adder() {
                return (context, tile, texture) -> { throw new AssertionError("chooser called a native hatch adder"); };
            }

            public String name() {
                return "gtmb_test_mixed_hatch";
            }

            public long count(MTEMultiBlockBase context) {
                return context.mInputBusses.stream()
                    .filter(SpecialBusFixture.class::isInstance)
                    .count();
            }
        };
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static IStructureElement.BlocksToPlace getNativeOffers(IStructureElement<?> element,
        MTEMultiBlockBase fresh, int x, int y, int z, ItemStack trigger) {
        return ((IStructureElement) element).getBlocksToPlace(fresh, null, x, y, z, trigger, null);
    }

    private static IStructureElement<MTEMultiBlockBase> witnessedAtLeast(
        Map<IHatchElement<? super MTEMultiBlockBase>, Integer> requirements) {
        HatchElementBuilder<MTEMultiBlockBase> builder = HatchElementBuilder.<MTEMultiBlockBase>builder()
            .atLeast(requirements)
            .casingIndex(11)
            .hint(1);
        IStructureElement<MTEMultiBlockBase> leaf = builder.build();
        // The headless suite does not load the production mixin. Mirror the source-reviewed callback notifications.
        HologramHatchPolicy.declare(builder, requirements.keySet(), true);
        HologramHatchPolicy.requirements(builder, requirements);
        HologramHatchPolicy.built(builder, leaf);
        return leaf;
    }

    private static void localFacingChoices(Block machines, IHatchElement<?> layered) {
        ItemStack sample = new ItemStack(machines, 1, OUTPUT_ID);
        var element = com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock(Blocks.stone, 0);
        HologramCapture.Cell cell = new HologramCapture.Cell(element, 0, 64, 0, sample, true);
        ExtendedFacing facing = ExtendedFacing.of(ForgeDirection.EAST);
        SimpleAssert.eq(
            0,
            HologramCellPolicy.previewHatchFacings(cell, sample, null, facing).length,
            "an unknown branch gives no local facing proof");
        Object builder = new Object();
        HologramHatchPolicy.declare(builder, Collections.singletonList(layered), false);
        HologramHatchPolicy.built(builder, element);
        var prototype = GregTechAPI.METATILEENTITIES[OUTPUT_ID];
        ForgeDirection abc = Arrays.stream(ForgeDirection.VALID_DIRECTIONS)
            .filter(direction -> direction.offsetY == 0 && prototype.isFacingValid(facing.getWorldDirection(direction)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("test fixture lacks an allowed horizontal face"));
        HologramHatchPolicy.directions(element, java.util.EnumSet.complementOf(java.util.EnumSet.of(abc)));
        SimpleAssert.that(
            Arrays.equals(
                new int[] { facing.getWorldDirection(abc)
                    .ordinal() },
                HologramCellPolicy.previewHatchFacings(cell, sample, null, facing)),
            "native prototype and rotated declared branch together determine the sole legal choice");
        SimpleAssert.eq(
            0,
            HologramCellPolicy.previewHatchFacings(cell, new ItemStack(machines, 1, ENERGY_ID), null, facing).length,
            "another GT hatch is not accepted by the output declaration");
    }

    private static HologramSession session(sun.misc.Unsafe unsafe, Block machines, IHatchElement<?> layered)
        throws ReflectiveOperationException {
        Map<IHatchElement<?>, Integer> requirements = new LinkedHashMap<>();
        requirements.put(layered, 1);
        requirements.put(HatchElement.Energy, 1);
        HologramSession s = HologramSemanticSafetyRegression.presetSession(
            unsafe,
            requirements,
            new ItemStack(machines, 1, OUTPUT_ID),
            new ItemStack(machines, 1, ENERGY_ID));
        s.machine = (FacingOnlyController) unsafe.allocateInstance(FacingOnlyController.class);
        s.player.capabilities.isCreativeMode = true;
        s.areaStructureId = "layered";
        for (HologramCapture.Cell cell : s.capture.cells) {
            cell.candidates = null;
            cell.structureId = "layered";
            s.capture.positions.put(HologramValues.position(cell), cell);
        }
        return s;
    }

    private static void nativeCandidatesAndPin(sun.misc.Unsafe unsafe, Block machines, IHatchElement<?> layered)
        throws ReflectiveOperationException, IOException {
        HologramSession s = session(unsafe, machines, layered);
        HologramCapture.Cell cell = s.capture.cells.get(1);
        ItemStack output = new ItemStack(machines, 1, OUTPUT_ID), energy = new ItemStack(machines, 1, ENERGY_ID);
        List<ItemStack> candidates = HologramCellPolicy.candidates(s, cell);
        SimpleAssert.that(
            candidates.stream()
                .anyMatch(stack -> HologramValues.sameMaterial(stack, output)),
            "native withCount/withAdder output appears in the real cell candidate collector");
        SimpleAssert.that(
            candidates.stream()
                .anyMatch(stack -> HologramValues.sameMaterial(stack, energy)),
            "a decorated output does not discard the builder's ordinary energy role");
        SimpleAssert.that(
            HologramCellPolicy.hatchElement(s, cell, output, true) != null
                && HologramCellPolicy.declaredTarget(s, cell, output, false),
            "manual hatch matching and construction target proof accept the declared output without callbacks");
        NBTTagCompound action = new NBTTagCompound();
        action.setInteger("selected", 1);
        HologramBlueprintEditor.pinSelection(s, action, 1, output);
        SimpleAssert.that(
            HologramValues.sameMaterial(output, s.pins.get(1))
                && HologramValues.sameMaterial(output, s.coordinatePins.get(HologramValues.position(cell))),
            "the production manual pin operation retains the output in both target maps");
        SimpleAssert.eq(
            1,
            s.player.inventory.mainInventory[1].stackSize,
            "manual pin and its planning refresh do not debit output stock");

        // A previously saved declaration is independent of the newly improved native parser.
        s.virtual = true;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.schema = 2;
        cell.structureTargets = Collections.singletonList(new ItemStack(Blocks.stone));
        SimpleAssert.that(
            HologramCellPolicy.candidates(s, cell)
                .stream()
                .noneMatch(stack -> HologramValues.sameMaterial(stack, output))
                && !HologramCellPolicy.acceptableSample(s, cell, output),
            "old schema-2 saved candidates do not acquire the native parser's new output permission");
    }

    private static void contextualPresets(sun.misc.Unsafe unsafe, Block machines,
        IHatchElement<MTEMultiBlockBase> layered) throws ReflectiveOperationException, IOException {
        IMetaTileEntity output = GregTechAPI.METATILEENTITIES[OUTPUT_ID];
        SimpleAssert
            .eq(0, HologramHatchPolicy.contribution(layered, output), "layer count grants no global contribution");
        SimpleAssert.eq(
            0,
            HologramHatchPolicy.contribution(layered.or(HatchElement.OutputHatch), output),
            "a global OR alternative does not certify a contextual count branch");
        IHatchElement<MTEMultiBlockBase> onlyAdder = HatchElement.OutputHatch
            .withAdder((context, tile, texture) -> { throw new AssertionError("decorated adder executed"); });
        SimpleAssert.eq(
            1,
            HologramHatchPolicy.contribution(onlyAdder, output),
            "an adder-only native decorator retains its backing's global count contract");
        for (boolean frozen : new boolean[] { false, true }) {
            HologramSession s = session(unsafe, machines, layered);
            s.pins.put(0, new ItemStack(machines, 1, OUTPUT_ID));
            s.allocationsFrozen = frozen;
            s.presetAvailable = s.presetSupported = true;
            s.frozenAutoTargets
                .put(HologramValues.position(s.capture.cells.get(1)), new ItemStack(machines, 1, OUTPUT_ID));
            HologramResourcePlanner.buildPreset(s);
            SimpleAssert.that(
                !s.presetAvailable && !s.presetSupported && s.autoTargets.isEmpty() && s.presetReason.contains("逐格"),
                "contextual layer counts cannot be claimed as one whole-machine preset, frozen=" + frozen);
            SimpleAssert.eq(1, s.pins.size(), "rejecting a global preset preserves explicit output pins");
            NBTTagCompound profile = new NBTTagCompound(), entry = new NBTTagCompound();
            NBTTagList entries = new NBTTagList();
            entry.setString("role", HatchElement.OutputHatch.name());
            entry
                .setString("item", String.valueOf(Item.itemRegistry.getNameForObject(Item.getItemFromBlock(machines))));
            entry.setInteger("damage", OUTPUT_ID);
            entry.setInteger("count", 3);
            entries.appendTag(entry);
            profile.setTag("entries", entries);
            try {
                HologramHatchProfiles.plan(s, profile);
                throw new AssertionError("custom whole-machine profile accepted a contextual layer quota");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("逐格"),
                    "custom profiles explain the per-cell boundary");
            }
            SimpleAssert.eq(1, s.pins.size(), "custom profile rejection also leaves explicit pins unchanged");
        }
    }

    private static void unknownBoundaries(sun.misc.Unsafe unsafe, IHatchElement<MTEMultiBlockBase> layered)
        throws ReflectiveOperationException, IOException {
        IHatchElement<MTEMultiBlockBase> unknown = new IHatchElement<MTEMultiBlockBase>() {

            public List<Class<? extends IMetaTileEntity>> mteClasses() {
                throw new AssertionError("unknown classes executed");
            }

            public IGTHatchAdder<MTEMultiBlockBase> adder() {
                throw new AssertionError("unknown adder executed");
            }

            public String name() {
                throw new AssertionError("unknown name executed");
            }

            public long count(MTEMultiBlockBase context) {
                throw new AssertionError("unknown count executed");
            }

            public boolean matchesHatch(IMetaTileEntity tile) {
                throw new AssertionError("unknown matcher executed");
            }
        };
        rejected(unknown.withCount(context -> { throw new AssertionError("unknown decorated count executed"); }));
        rejected(HatchElement.OutputHatch.or(unknown.withAdder((context, tile, texture) -> false)));
        IHatchElement<MTEMultiBlockBase> deep = layered;
        for (int i = 0; i < 13; i++) deep = deep.or(HatchElement.OutputHatch);
        rejected(deep);
        Field backing = layered.getClass()
            .getDeclaredField("mBacking");
        backing.setAccessible(true);
        Object original = backing.get(layered);
        try {
            backing.set(layered, layered);
            rejected(layered);
        } finally {
            backing.set(layered, original);
        }
        try (InputStream input = IHatchElement.class.getResourceAsStream("HatchElement.class")) {
            if (input == null) throw new AssertionError("native decorator class resource absent");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) bytes.write(buffer, 0, count);
            Class<?> foreign = new ForeignLoader().copy(bytes.toByteArray());
            rejected((IHatchElement<?>) unsafe.allocateInstance(foreign));
        }
    }

    private static void rejected(IHatchElement<?> role) {
        Object builder = new Object();
        IStructureElement<?> element = StructureUtility.ofBlock(Blocks.stone, 0);
        HologramHatchPolicy.declare(builder, Collections.singletonList(role), true);
        HologramHatchPolicy.requirements(builder, Collections.singletonMap(role, 1));
        HologramHatchPolicy.built(builder, element);
        SimpleAssert.that(
            HologramHatchPolicy.predicate(element, null, null) == null && HologramHatchPolicy.declared(element)
                .isEmpty() && !HologramHatchPolicy.globalRequirementsComplete(element),
            "unknown, foreign, deep or cyclic backing grants neither candidate nor global quota authority");
    }

    private static final class ForeignLoader extends ClassLoader {

        ForeignLoader() {
            super(IHatchElement.class.getClassLoader());
        }

        Class<?> copy(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    private static final class OutputFixture extends MTEHatchOutput {

        private OutputFixture() {
            super("layered_output", 0, new String[0], (ITexture[][][]) null);
        }

        @Override
        public ItemStack getStackForm(long amount) {
            return new ItemStack(GregTechAPI.sBlockMachines, (int) amount, OUTPUT_ID);
        }
    }

    private static final class EnergyFixture extends MTEHatchEnergy {

        private EnergyFixture() {
            super("layered_energy", 0, new String[0], (ITexture[][][]) null);
        }

        @Override
        public ItemStack getStackForm(long amount) {
            return new ItemStack(GregTechAPI.sBlockMachines, (int) amount, ENERGY_ID);
        }
    }

    private static class InputBusFixture extends MTEHatchInputBus {

        private InputBusFixture() {
            super("mixed_input_bus", 0, new String[0], (ITexture[][][]) null);
        }
    }

    private static final class SpecialBusFixture extends InputBusFixture {
    }

    private static final class FacingOnlyController extends MTEEnhancedMultiBlockBase<FacingOnlyController> {

        private FacingOnlyController() {
            super("layered_facing");
        }

        @Override
        public IStructureDefinition<FacingOnlyController> getStructureDefinition() {
            return null;
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return null;
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity base) {
            return null;
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity base, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {}

        @Override
        public ExtendedFacing getExtendedFacing() {
            return ExtendedFacing.DEFAULT;
        }
    }
}
