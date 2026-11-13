// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.IGTHatchAdder;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Native decorated hatch candidates retain manual editing without claiming whole-machine layer quotas. */
final class HologramLayeredHatchRegression {

    private static final int OUTPUT_ID = 32748, ENERGY_ID = 32747;

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
            nativeCandidatesAndPin(unsafe, machines, layered);
            contextualPresets(unsafe, machines, layered);
            unknownBoundaries(unsafe, layered);
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("native decorated hatch declaration boundary", failure);
        } finally {
            GregTechAPI.sBlockMachines = previousMachines;
            System.arraycopy(previous, 0, GregTechAPI.METATILEENTITIES, 0, previous.length);
        }
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
