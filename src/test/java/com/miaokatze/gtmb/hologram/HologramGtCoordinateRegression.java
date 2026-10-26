package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Detached native-schema tests; these do not claim native registration/reconnection lifecycle acceptance. */
public final class HologramGtCoordinateRegression {

    private static final int UNIT = 31991, PCB = 31992, FACTORY = 31993;

    private HologramGtCoordinateRegression() {}

    public static void run() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        IMetaTileEntity[] old = { GregTechAPI.METATILEENTITIES[UNIT], GregTechAPI.METATILEENTITIES[PCB] };
        try {
            GregTechAPI.METATILEENTITIES[UNIT] = (IMetaTileEntity) unsafe.allocateInstance(UnitFixture.class);
            GregTechAPI.METATILEENTITIES[PCB] = (IMetaTileEntity) unsafe.allocateInstance(PcbFixture.class);
            rawRelationships();
            blueprintPolicies();
            factorySourceSchema();
        } finally {
            GregTechAPI.METATILEENTITIES[UNIT] = old[0];
            GregTechAPI.METATILEENTITIES[PCB] = old[1];
        }
    }

    private static void factorySourceSchema() throws Exception {
        // Loading the exact .205 class without initialization checks the schema without constructing its structures.
        Class<?> nativeFactory = Class.forName(
            "gregtech.common.tileentities.machines.multi.pcb.MTEPCBFactory",
            false,
            HologramGtCoordinateRegression.class.getClassLoader());
        NBTTagCompound factory = tile(FACTORY);
        factory.setTag("mBioChamber", xyz(11, 65, 22));
        factory.setTag("mCoolingTower", xyz(999, 80, 1));
        factory.setTag("controller", xyz(55, 66, 77));
        List<HologramGtCoordinateData.Binding> bindings = HologramGtCoordinateData.bindings(factory, nativeFactory);
        SimpleAssert.eq(2, bindings.size(), "exact native factory exposes only its two registered caches");
        SimpleAssert.eq(HologramGtCoordinateData.BIO, bindings.get(0).adapter, "native biological module cache schema");
        SimpleAssert.eq(11, bindings.get(0).x, "native biological target x");
        SimpleAssert
            .eq(HologramGtCoordinateData.COOLING, bindings.get(1).adapter, "native cooling module cache schema");
        SimpleAssert.eq(999, bindings.get(1).x, "native cooling target x");
        SimpleAssert.that(
            HologramGtCoordinateData.bindings(factory, String.class)
                .isEmpty(),
            "same cache keys on an unrelated source class remain opaque");
    }

    /** Optional after genuine GT registration: the factory class eagerly builds its native structure singleton. */
    public static void runNativeFactory() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        IMetaTileEntity old = GregTechAPI.METATILEENTITIES[FACTORY];
        try {
            GregTechAPI.METATILEENTITIES[FACTORY] = (IMetaTileEntity) ((sun.misc.Unsafe) field.get(null))
                .allocateInstance(Class.forName("gregtech.common.tileentities.machines.multi.pcb.MTEPCBFactory"));
            factoryRelationships();
        } finally {
            GregTechAPI.METATILEENTITIES[FACTORY] = old;
        }
    }

    private static void rawRelationships() throws Exception {
        NBTTagCompound unit = tile(UNIT);
        unit.setTag("controller", xyz(11, 65, 22));
        NBTTagCompound inventory = xyz(11, 65, 22);
        inventory.setString("network", "unchanged");
        unit.setTag("inventory", inventory);
        NBTTagCompound entry = entry(unit), before = (NBTTagCompound) entry.copy();
        int[][] expected = { { 101, 71, 202 }, { 104, 71, 201 }, { 103, 71, 202 } };
        CutTransforms.Transform[] poses = { new CutTransforms.Transform(0, 0, 0, 0),
            new CutTransforms.Transform(1, 0, 0, 0), new CutTransforms.Transform(0, 0, 0, 1) };
        for (int i = 0; i < poses.length; i++) {
            NBTTagCompound copy = (NBTTagCompound) unit.copy();
            HologramCutRelations.Context context = HologramCutRelations.context(entry, poses[i], 100, 70, 200, 0);
            SimpleAssert.that(context.targetDependent(), "internal link needs target-origin preview");
            context.apply(unit, copy);
            coordinates(copy.getCompoundTag("controller"), expected[i][0], expected[i][1], expected[i][2]);
            SimpleAssert.eq(inventory, copy.getCompoundTag("inventory"), "inventory and network identity remain exact");
        }
        SimpleAssert.eq(before, entry, "complete raw source remains immutable");
        NBTTagCompound cross = (NBTTagCompound) unit.copy();
        HologramCutRelations.context(entry, poses[0], 100, 70, 200, 1)
            .apply(unit, cross);
        coordinates(cross.getCompoundTag("controller"), 101, 71, 202);
        NBTTagCompound gap = tile(UNIT);
        gap.setTag("controller", xyz(12, 65, 22));
        NBTTagCompound gapCopy = (NBTTagCompound) gap.copy();
        HologramCutRelations.Context gapContext = HologramCutRelations.context(entry(gap), poses[0], 100, 70, 200);
        gapContext.apply(gap, gapCopy);
        SimpleAssert.that(!gapContext.targetDependent(), "bbox gap is not an internally selected target");
        SimpleAssert.eq(gap, gapCopy, "masked and external targets are preserved");
        SimpleAssert.that(
            HologramCutRelations.context(entry(gap), poses[0], 100, 70, 200, 1)
                .crossDimensionExternal(),
            "implicit external links need a cross-dimension warning");
        NBTTagCompound pcb = tile(PCB);
        pcb.setIntArray("controllers", new int[] { 11, 65, 22, 12, 65, 22, 999, 80, 1 });
        NBTTagCompound pcbCopy = (NBTTagCompound) pcb.copy();
        HologramCutRelations.context(entry(pcb), poses[1], 100, 70, 200)
            .apply(pcb, pcbCopy);
        array(new int[] { 104, 71, 201, 12, 65, 22, 999, 80, 1 }, pcbCopy.getIntArray("controllers"));
        pcb.setIntArray("controllers", new int[] { 11, 65, 22, 4 });
        pcbCopy = (NBTTagCompound) pcb.copy();
        HologramCutRelations.context(entry(pcb), poses[0], 100, 70, 200)
            .apply(pcb, pcbCopy);
        SimpleAssert.eq(pcb, pcbCopy, "malformed triples are not repaired by guessing");
        NBTTagCompound wrong = (NBTTagCompound) unit.copy();
        wrong.setShort("mID", (short) PCB);
        NBTTagCompound wrongCopy = (NBTTagCompound) wrong.copy();
        HologramCutRelations.context(entry(wrong), poses[0], 100, 70, 200)
            .apply(wrong, wrongCopy);
        SimpleAssert.eq(wrong, wrongCopy, "same named controller on other native type is not a relation");
        wrong = (NBTTagCompound) unit.copy();
        wrong.setString("id", "unrelated.Tile");
        wrongCopy = (NBTTagCompound) wrong.copy();
        HologramCutRelations.context(entry(wrong), poses[0], 100, 70, 200)
            .apply(wrong, wrongCopy);
        SimpleAssert.eq(wrong, wrongCopy, "mID alone cannot identify a native tile schema");
        wrong = (NBTTagCompound) unit.copy();
        wrong.getCompoundTag("controller")
            .setString("x", "11");
        wrongCopy = (NBTTagCompound) wrong.copy();
        HologramCutRelations.context(entry(wrong), poses[0], 100, 70, 200)
            .apply(wrong, wrongCopy);
        SimpleAssert.eq(wrong, wrongCopy, "incorrect native coordinate types stay opaque");
    }

    private static void blueprintPolicies() throws Exception {
        NBTTagCompound pcb = tile(PCB);
        pcb.setIntArray("controllers", new int[] { 11, 65, 22, 999, 80, 1 });
        NBTTagCompound before = (NBTTagCompound) pcb.copy();
        HologramPresets.Preset preset = preset(pcb);
        List<HologramCoordinatePolicies.Relation> links = HologramCoordinatePolicies.inspect(preset, 0, 10, 64, 20);
        SimpleAssert.eq(2, links.size(), "each native controller triple is reviewed independently");
        SimpleAssert.that(links.get(0).internal && !links.get(1).internal, "blueprint uses actual selected mask");
        HologramBlueprintProcessing.Policy policy = new HologramBlueprintProcessing.Policy();
        HologramCoordinatePolicies.apply(preset, links, policy);
        NBTTagCompound projected = HologramCoordinatePolicies
            .placementProjection(HologramBlueprintNbt.decode(preset.cells.get(0).tileNbt), 0, 100, 70, 200);
        array(new int[] { 97, 71, 202, 999, 80, 1 }, projected.getIntArray("controllers"));
        SimpleAssert.eq(before, pcb, "blueprint review source stays immutable");
        preset = preset(pcb);
        links = HologramCoordinatePolicies.inspect(preset, 0, 10, 64, 20);
        policy.coordinateOverrides.put(links.get(0).id, "clear");
        HologramCoordinatePolicies.apply(preset, links, policy);
        NBTTagCompound reviewed = HologramBlueprintNbt.decode(preset.cells.get(0).tileNbt);
        array(new int[] { 0, 0, 0, 999, 80, 1 }, reviewed.getIntArray("controllers"));
        projected = HologramCoordinatePolicies.placementProjection(reviewed, 0, 100, 70, 200);
        array(new int[] { 999, 80, 1 }, projected.getIntArray("controllers"));
        preset = preset(pcb);
        preset.cells.get(1).excluded = true;
        links = HologramCoordinatePolicies.inspect(preset, 0, 10, 64, 20);
        SimpleAssert.that(!links.get(0).internal, "excluded blueprint target is external");
        NBTTagCompound unit = tile(UNIT);
        unit.setTag("controller", xyz(11, 65, 22));
        preset = preset(unit);
        links = HologramCoordinatePolicies.inspect(preset, 0, 10, 64, 20);
        HologramCoordinatePolicies.apply(preset, links, new HologramBlueprintProcessing.Policy());
        projected = HologramCoordinatePolicies
            .placementProjection(HologramBlueprintNbt.decode(preset.cells.get(0).tileNbt), 0, 100, 70, 200);
        coordinates(projected.getCompoundTag("controller"), 97, 71, 202);
        projected = HologramCoordinatePolicies.placementProjection(
            HologramBlueprintNbt.decode(HologramCoordinatePolicies.rotatePayload(preset.cells.get(0).tileNbt, false)),
            0,
            100,
            70,
            200);
        coordinates(projected.getCompoundTag("controller"), 98, 71, 197);
    }

    private static void factoryRelationships() throws Exception {
        NBTTagCompound factory = tile(FACTORY);
        factory.setTag("mBioChamber", xyz(11, 65, 22));
        factory.setTag("mCoolingTower", xyz(999, 80, 1));
        factory.setTag("compatMode", xyz(11, 65, 22));
        NBTTagCompound factoryCopy = (NBTTagCompound) factory.copy();
        HologramCutRelations.context(entry(factory), new CutTransforms.Transform(0, 0, 0, 0), 100, 70, 200)
            .apply(factory, factoryCopy);
        coordinates(factoryCopy.getCompoundTag("mBioChamber"), 101, 71, 202);
        SimpleAssert
            .eq(factory.getTag("mCoolingTower"), factoryCopy.getTag("mCoolingTower"), "external module preserved");
        SimpleAssert
            .eq(factory.getTag("compatMode"), factoryCopy.getTag("compatMode"), "local structure offsets preserved");
        HologramPresets.Preset preset = preset(factory);
        List<HologramCoordinatePolicies.Relation> links = HologramCoordinatePolicies.inspect(preset, 0, 10, 64, 20);
        HologramBlueprintProcessing.Policy policy = new HologramBlueprintProcessing.Policy();
        policy.tendency = "share";
        HologramCoordinatePolicies.apply(preset, links, policy);
        NBTTagCompound projected = HologramCoordinatePolicies
            .placementProjection(HologramBlueprintNbt.decode(preset.cells.get(0).tileNbt), 0, 100, 70, 200);
        coordinates(projected.getCompoundTag("mBioChamber"), 97, 71, 202);
        SimpleAssert.that(!projected.hasKey("mCoolingTower"), "shared external cache is cleared by reviewed policy");
    }

    /** Inherits the real native schema identity without eager concrete-machine structure singletons. */
    private static final class UnitFixture
        extends gregtech.common.tileentities.machines.multi.purification.MTEPurificationUnitBase<UnitFixture> {

        private UnitFixture() {
            super("coordinate_unit_fixture");
        }

        public int getWaterTier() {
            return 1;
        }

        public long getBasePowerUsage() {
            return 0;
        }

        public com.gtnewhorizon.structurelib.structure.IStructureDefinition<UnitFixture> getStructureDefinition() {
            return null;
        }

        protected gregtech.api.util.MultiblockTooltipBuilder createTooltip() {
            return null;
        }

        public IMetaTileEntity newMetaEntity(gregtech.api.interfaces.tileentity.IGregTechTileEntity base) {
            return null;
        }

        public gregtech.api.interfaces.ITexture[] getTexture(
            gregtech.api.interfaces.tileentity.IGregTechTileEntity base,
            net.minecraftforge.common.util.ForgeDirection side, net.minecraftforge.common.util.ForgeDirection front,
            int color, boolean active, boolean redstone) {
            return new gregtech.api.interfaces.ITexture[0];
        }

        public void construct(net.minecraft.item.ItemStack trigger, boolean hints) {}
    }

    private static final class PcbFixture
        extends gregtech.common.tileentities.machines.multi.pcb.MTEPCBUpgradeBase<PcbFixture> {

        private PcbFixture() {
            super("coordinate_pcb_fixture");
        }

        public com.gtnewhorizon.structurelib.structure.IStructureDefinition<PcbFixture> getStructureDefinition() {
            return null;
        }

        protected gregtech.api.util.MultiblockTooltipBuilder createTooltip() {
            return null;
        }

        public IMetaTileEntity newMetaEntity(gregtech.api.interfaces.tileentity.IGregTechTileEntity base) {
            return null;
        }

        public gregtech.api.interfaces.ITexture[] getTexture(
            gregtech.api.interfaces.tileentity.IGregTechTileEntity base,
            net.minecraftforge.common.util.ForgeDirection side, net.minecraftforge.common.util.ForgeDirection front,
            int color, boolean active, boolean redstone) {
            return new gregtech.api.interfaces.ITexture[0];
        }

        public void construct(net.minecraft.item.ItemStack trigger, boolean hints) {}
    }

    private static HologramPresets.Preset preset(NBTTagCompound tag) throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        HologramPresets.Cell host = new HologramPresets.Cell(), target = new HologramPresets.Cell();
        host.x = 4;
        host.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        target.x = 1;
        target.y = 1;
        target.z = 2;
        preset.cells.add(host);
        preset.cells.add(target);
        return preset;
    }

    private static NBTTagCompound entry(NBTTagCompound tile) {
        NBTTagCompound entry = xyz(10, 64, 20);
        entry.setInteger("dimension", 0);
        entry.setInteger("sizeX", 5);
        entry.setInteger("sizeY", 3);
        entry.setInteger("sizeZ", 7);
        NBTTagList cells = new NBTTagList();
        NBTTagCompound host = xyz(4, 0, 0);
        host.setTag("tile", tile.copy());
        cells.appendTag(host);
        cells.appendTag(xyz(1, 1, 2));
        entry.setTag("cells", cells);
        return entry;
    }

    private static NBTTagCompound tile(int id) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "BaseMetaTileEntity");
        tag.setShort("mID", (short) id);
        return tag;
    }

    private static NBTTagCompound xyz(int x, int y, int z) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        return tag;
    }

    private static void coordinates(NBTTagCompound tag, int x, int y, int z) {
        SimpleAssert.eq(x, tag.getInteger("x"), "expected x");
        SimpleAssert.eq(y, tag.getInteger("y"), "expected y");
        SimpleAssert.eq(z, tag.getInteger("z"), "expected z");
    }

    private static void array(int[] expected, int[] actual) {
        SimpleAssert
            .that(Arrays.equals(expected, actual), "native ordered controller triples " + Arrays.toString(actual));
    }
}
