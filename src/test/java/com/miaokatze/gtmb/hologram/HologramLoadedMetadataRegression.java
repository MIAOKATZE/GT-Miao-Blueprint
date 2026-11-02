package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Collections;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.util.HatchElementBuilder;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Full metadata must observe loaded tiles without making an unknown chunk exist. */
public final class HologramLoadedMetadataRegression {

    private HologramLoadedMetadataRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLoadedMetadataRegression.class, args)) return;
        run();
        System.out.println("HologramLoadedMetadataRegression PASS");
    }

    static void run() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            fullMetadata(unsafe);
            installedHatch(unsafe);
        } catch (Exception failure) {
            throw new AssertionError("loaded-world snapshot metadata", failure);
        }
    }

    private static void fullMetadata(sun.misc.Unsafe unsafe) throws Exception {
        LoadedWorld world = (LoadedWorld) unsafe.allocateInstance(LoadedWorld.class);
        world.near = new PayloadTile("loadedConfiguration");
        world.far = new PayloadTile("laterConfiguration");
        world.near.setWorldObj(world);
        world.near.xCoord = 1;
        world.near.yCoord = 64;
        world.far.setWorldObj(world);
        world.far.xCoord = 32;
        world.far.yCoord = 64;
        HologramSession session = session(unsafe, world);
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.virtualPreset.deidentificationLevel = 1;
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.id = "remote";
        structure.x = 32;
        session.virtualPreset.structures.add(structure);
        for (int x : new int[] { 1, 32 }) {
            HologramCapture.Cell cell = new HologramCapture.Cell(StructureUtility.ofBlock(Blocks.stone, 0), x, 64, 0);
            cell.block = Blocks.stone;
            session.capture.cells.add(cell);
            session.capture.positions.put(HologramValues.position(cell), cell);
        }
        NBTTagCompound partial = metadata(session);
        SimpleAssert.that(
            session.availableNbtKeys.contains("loadedConfiguration") && session.availableNbtKeys.contains("nested"),
            "full native metadata retains root keys from the complete loaded tile payload");
        SimpleAssert.that(
            !session.availableNbtKeys.contains("laterConfiguration") && session.nbtKeysCapture == null,
            "unobserved positions cannot publish a complete key-cache generation");
        NBTTagCompound payload = HologramBlueprintNbt.decode(session.processingSource.cells.get(0).tileNbt);
        SimpleAssert.eq(73, payload.getInteger("loadedConfiguration"), "the loaded native value survives projection");
        SimpleAssert.eq(
            "complete native payload",
            payload.getCompoundTag("nested")
                .getString("preserved"),
            "metadata keeps the loaded native payload's complete nested value");
        SimpleAssert.that(world.tileReads > 0, "the guard still performs real reads in loaded chunks");
        SimpleAssert.eq(0, world.unloadedTileReads, "source projection, capture and labels never read unloaded tiles");
        HologramCapture.Cell remote = session.capture.cells.get(1);
        SimpleAssert.eq(
            "unsupported",
            HologramPlanPolicy.classify(session, remote, 1),
            "unknown world state remains rejected by the real construction classifier");
        SimpleAssert.eq(
            "区块未加载",
            HologramPlanPolicy.reason(session, remote, 1),
            "the unknown position is not reported as observed empty world state");
        oracle(session, partial);
        SimpleAssert
            .that(session.nbtKeysCapture == null, "the synchronous reference also leaves unknown keys uncached");
        world.farLoaded = true;
        NBTTagCompound complete = metadata(session);
        SimpleAssert.that(
            session.availableNbtKeys.contains("loadedConfiguration")
                && session.availableNbtKeys.contains("laterConfiguration")
                && session.availableNbtKeys.contains("nested"),
            "the same capture discovers the newly loaded native payload on its next metadata pass");
        SimpleAssert.that(session.nbtKeysCapture == session.capture, "a fully observed pass can publish its key cache");
        oracle(session, complete);
        SimpleAssert
            .eq(0, world.unloadedTileReads, "complete metadata and both synchronous oracles avoid unknown reads");
    }

    private static void installedHatch(sun.misc.Unsafe unsafe) throws Exception {
        int id = 32004;
        Block previousMachines = GregTechAPI.sBlockMachines;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[id];
        try {
            Block machines = new Block(Material.iron) {};
            Block.blockRegistry.addObject(4062, "gtmb:loaded_metadata_machines", machines);
            Item.itemRegistry.addObject(4062, "gtmb:loaded_metadata_machines", new ItemMachines(machines));
            GregTechAPI.sBlockMachines = machines;
            DeclaredMuffler prototype = (DeclaredMuffler) unsafe.allocateInstance(DeclaredMuffler.class);
            GregTechAPI.METATILEENTITIES[id] = prototype;
            LoadedWorld world = (LoadedWorld) unsafe.allocateInstance(LoadedWorld.class);
            HologramDynamicDefinitionRegression.NativeTile tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
                .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
            tile.setWorldObj(world);
            tile.setMetaTileID((short) id);
            prototype.setBaseMetaTileEntity(tile);
            world.far = tile;
            HologramSession session = session(unsafe, world);
            HatchElementBuilder<MTEElectricBlastFurnace> builder = HatchElementBuilder
                .<MTEElectricBlastFurnace>builder()
                .atLeast(HatchElement.Muffler)
                .casingIndex(1)
                .hint(1);
            HologramHatchPolicy.declare(builder, Collections.singletonList(HatchElement.Muffler), false);
            IStructureElement<MTEElectricBlastFurnace> hatch = builder.build();
            HologramHatchPolicy.built(builder, hatch);
            HologramCapture.Cell cell = new HologramCapture.Cell(
                StructureUtility.ofChain(hatch, StructureUtility.ofBlock(Blocks.stone, 0)),
                32,
                64,
                0);
            cell.block = Blocks.stone;
            session.capture.cells.add(cell);
            SimpleAssert.that(
                ItemStack
                    .areItemStacksEqual(new ItemStack(Blocks.stone), HologramCellPolicy.draftTarget(session, cell, 0)),
                "an unloaded native declaration retains its casing target without reading the world");
            world.farLoaded = true;
            SimpleAssert.that(
                ItemStack.areItemStacksEqual(
                    new ItemStack(machines, 1, id),
                    HologramCellPolicy.draftTarget(session, cell, 0)),
                "the same loaded declaration still prefers its accepted installed native hatch identity");
            SimpleAssert.eq(0, world.unloadedTileReads, "native hatch preference never probes an unloaded chunk");
        } finally {
            GregTechAPI.sBlockMachines = previousMachines;
            GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    private static HologramSession session(sun.misc.Unsafe unsafe, LoadedWorld world) throws Exception {
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.isCreativeMode = true;
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
        session.materialSettings.main = session.materialSettings.me = session.materialSettings.containers = false;
        session.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        return session;
    }

    private static NBTTagCompound metadata(HologramSession session) {
        NBTTagCompound state = new NBTTagCompound();
        HologramSnapshotMetadataTask task = new HologramSnapshotMetadataTask(session, state);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!task.step(3, Long.MAX_VALUE) && System.nanoTime() < deadline) Thread.yield();
        SimpleAssert.eq("metadata.complete", task.phase(), "the complete production metadata pipeline terminates");
        return state;
    }

    private static void oracle(HologramSession session, NBTTagCompound actual) {
        NBTTagCompound expected = new NBTTagCompound();
        HologramSnapshots.writeBlueprintState(session, expected);
        HologramSnapshots.aeSnapshot(session, expected);
        SimpleAssert.eq(expected, actual, "budgeted metadata preserves the complete synchronous field/NBT contract");
    }

    private static final class DeclaredMuffler extends gregtech.api.metatileentity.implementations.MTEHatchMuffler {

        private DeclaredMuffler() {
            super("loaded_metadata_muffler", 1, new String[0], (gregtech.api.interfaces.ITexture[][][]) null);
        }
    }

    private static final class PayloadTile extends TileEntity {

        final String key;

        PayloadTile(String key) {
            this.key = key;
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            tag.setInteger(key, 73);
            NBTTagCompound nested = new NBTTagCompound();
            nested.setString("preserved", "complete native payload");
            tag.setTag("nested", nested);
        }
    }

    private static final class LoadedWorld extends WorldServer {

        TileEntity near, far;
        boolean farLoaded;
        int tileReads, unloadedTileReads;

        private LoadedWorld() {
            super(null, null, "loaded_metadata", 0, null, null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return x < 16 || farLoaded;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            tileReads++;
            if (!blockExists(x, y, z)) {
                unloadedTileReads++;
                throw new AssertionError("metadata requested an unloaded tile at " + x + "," + y + "," + z);
            }
            return x == 1 ? near : x == 32 ? far : null;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            if (!blockExists(x, y, z)) throw new AssertionError("unloaded block read");
            return x == 1 || x == 32 ? Blocks.stone : Blocks.air;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            if (!blockExists(x, y, z)) throw new AssertionError("unloaded metadata read");
            return 0;
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public long getTotalWorldTime() {
            return 0;
        }

        @Override
        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            return true;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("metadata must not write blocks");
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            throw new AssertionError("metadata must not write tiles");
        }
    }
}
