package com.miaokatze.gtmb.hologram;

import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Opt-in real FML/Mixin buildPiece traversal. A representative controller, never an impersonated GTNL class. */
public final class HologramDynamicDefinitionNativeSmoke {

    private HologramDynamicDefinitionNativeSmoke() {}

    public static void run(WorldServer world) throws Exception {
        Controller machine = new Controller();
        BaseMetaTileEntity tile = new BaseMetaTileEntity();
        tile.setWorldObj(world);
        tile.xCoord = world.getSpawnPoint().posX;
        tile.yCoord = world.getHeight() - 20;
        tile.zCoord = world.getSpawnPoint().posZ;
        machine.setBaseMetaTileEntity(tile);
        // Factory class loading is outside the production per-callback capture budget.
        machine.getStructureDefinition();
        machine.definitions = 0;
        NBTTagCompound first = capture(machine, 1, 1), second = capture(machine, 2, 3);
        require(machine.definitions == 2 && machine.constructions == 2, "one factory and construct per fresh capture");
        require(
            first.getInteger("cells") == first.getInteger("expectedUnion")
                && second.getInteger("cells") == second.getInteger("expectedUnion"),
            "full dynamic geometry union");
        require(
            first.getInteger("batches") > 1 && second.getInteger("batches") > 1,
            "real collect continuation batches");
        require(first.getInteger("offset") == 1 && second.getInteger("offset") == 3, "dynamic native offsets");
        require(first.getInteger("main") == 1 && second.getInteger("main") == 2, "independent main trigger");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("scope", "representative-native-GT-StructureLib-Mixin-not-installed-GTNL");
        receipt.setBoolean("pass", true);
        receipt.setBoolean("worldUnchanged", true);
        receipt.setTag("first", first);
        receipt.setTag("second", second);
        receipt.setInteger("definitionFactoryCalls", machine.definitions);
        receipt.setInteger("nativeConstructCalls", machine.constructions);
        String output = System.getProperty("gtmb.hologram.dynamicDefinitionReceipt", "");
        if (!output.isEmpty()) try (FileOutputStream stream = new FileOutputStream(output)) {
            CompressedStreamTools.writeCompressed(receipt, stream);
        }
        System.out.println(
            "[GTMB-DYNAMIC-DEFINITION-NATIVE] PASS pieces=2 firstCells=" + first.getInteger("cells")
                + " secondCells="
                + second.getInteger("cells")
                + " firstBatches="
                + first.getInteger("batches")
                + " secondBatches="
                + second.getInteger("batches")
                + " definitions=2 constructs=2 worldUnchanged=true installedGTNL=false");
    }

    private static NBTTagCompound capture(Controller machine, int main, int tier) {
        ItemStack trigger = new ItemStack(Blocks.stone, main);
        ChannelDataAccessor.setChannelData(trigger, "shape", tier);
        HologramCapture previous = null;
        int batches = 0, oldDefinitions = machine.definitions;
        do {
            previous = HologramCapture.collect(machine, trigger, ExtendedFacing.DEFAULT, previous);
            batches++;
        } while (previous.pending && batches < 128);
        require(!previous.pending && !previous.incomplete, "native collection complete: " + previous.failure);
        require(machine.definitions == oldDefinitions + 1, "resume retains original definition graph");
        require(
            previous.pieces.size() == 2 && previous.pieces.get(0).name.equals("tier" + tier),
            "Mixin captured native pieces");
        require(previous.pieces.get(1).a == -8, "negative native offset retained");
        IGregTechTileEntity base = machine.getBaseMetaTileEntity();
        String controllerPosition = base.getXCoord() + "," + base.getYCoord() + "," + base.getZCoord();
        // Independent lattice union: main is 4 x 20 x (80+tier), tail is a separate three-cell row.
        // Native offset (tier,0,1) is inside main, so the protected anchor replaces one declared A cell.
        int expectedUnion = 4 * 20 * (80 + tier) + 3;
        java.util.Set<String> coordinates = new java.util.HashSet<>();
        for (HologramCapture.Cell cell : previous.cells) {
            String key = cell.x + "," + cell.y + "," + cell.z;
            require(coordinates.add(key), "no duplicate coordinates after protected anchor replacement");
            require(previous.positions.get(key) == cell, "coordinate index and cell list share the protected identity");
        }
        HologramCapture.Cell anchor = previous.positions.get(controllerPosition);
        require(
            anchor != null && anchor.anchor && anchor.element == null,
            "controller overlap resolves to protected anchor");
        require(machine.worldSamples.containsKey(controllerPosition), "native hint visits declared controller overlap");
        require(
            machine.worldSamples.size() == expectedUnion && previous.cells.size() == expectedUnion,
            "exact lattice union: expected=" + expectedUnion
                + " samples="
                + machine.worldSamples.size()
                + " cells="
                + previous.cells.size());
        require(
            machine.worldSamples.keySet()
                .equals(coordinates),
            "real hint samples cover the complete geometry union");
        for (HologramCapture.Cell cell : previous.cells) if (!cell.anchor) require(
            cell.block == Blocks.stone && cell.meta == tier && !cell.unknown,
            "real hint Mixin supplied native block appearance");
        for (Sample sample : machine.worldSamples.values()) sample.unchanged(
            machine.getBaseMetaTileEntity()
                .getWorld());
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("cells", previous.cells.size());
        result.setInteger("expectedUnion", expectedUnion);
        result.setInteger("hintSamples", machine.worldSamples.size());
        result.setBoolean("controllerOverlapVisited", true);
        result.setBoolean("coordinateIdentityConsistent", true);
        result.setInteger("pieces", previous.pieces.size());
        result.setInteger("batches", batches);
        result.setInteger("offset", previous.pieces.get(0).a);
        result.setInteger("main", previous.pieces.get(0).trigger.stackSize);
        result.setInteger("channel", tier);
        machine.worldSamples.clear();
        return result;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError("dynamic native definition: " + message);
    }

    private static final class Sample {

        final int x, y, z, metadata;
        final Block block;
        final TileEntity tile;

        Sample(World world, int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            block = world.getBlock(x, y, z);
            metadata = world.getBlockMetadata(x, y, z);
            tile = world.getTileEntity(x, y, z);
        }

        void unchanged(World world) {
            require(
                block == world.getBlock(x, y, z) && metadata == world.getBlockMetadata(x, y, z)
                    && tile == world.getTileEntity(x, y, z),
                "source world block/metadata/tile identity unchanged");
        }
    }

    private static final class Controller extends MTEEnhancedMultiBlockBase<Controller> {

        int tier = 1, definitions, constructions;
        final Map<String, Sample> worldSamples = new LinkedHashMap<>();

        Controller() {
            super("gtmb_dynamic_native_fixture");
        }

        @Override
        public IStructureDefinition<Controller> getStructureDefinition() {
            definitions++;
            String[][] shape = new String[80 + tier][20];
            for (String[] slice : shape) Arrays.fill(slice, "AAAA");
            final IStructureElement<Controller> leaf = StructureUtility.ofBlock(Blocks.stone, tier);
            IStructureElement<Controller> audited = new IStructureElement<Controller>() {

                public boolean check(Controller controller, World world, int x, int y, int z) {
                    throw new AssertionError("native preview called structural check");
                }

                public boolean placeBlock(Controller controller, World world, int x, int y, int z, ItemStack signal) {
                    throw new AssertionError("native preview called placement");
                }

                public boolean spawnHint(Controller controller, World world, int x, int y, int z, ItemStack signal) {
                    worldSamples.put(x + "," + y + "," + z, new Sample(world, x, y, z));
                    return leaf.spawnHint(controller, world, x, y, z, signal);
                }
            };
            return StructureDefinition.<Controller>builder()
                .addShape("tier" + tier, shape)
                .addShape("tail", new String[][] { { "AAA" } })
                .addElement('A', audited)
                .build();
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            constructions++;
            tier = ChannelDataAccessor.getChannelData(trigger, "shape");
            buildPiece("tier" + tier, trigger, hints, tier, 0, 1);
            buildPiece("tail", trigger, hints, -8, 0, 1);
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
