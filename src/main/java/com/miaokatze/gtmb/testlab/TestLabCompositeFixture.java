package com.miaokatze.gtmb.testlab;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import appeng.api.AEApi;
import appeng.api.definitions.IBlockDefinition;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPatternTerminal;
import appeng.api.storage.StorageName;
import appeng.tile.misc.TileInterface;
import appeng.tile.storage.TileDrive;
import appeng.util.inv.MEInventoryCrafting;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTUtility;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;
import gregtech.common.tileentities.machines.MTEHatchInputME;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;
import gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor;

/** Native registered fixtures only. This class never calls machine ticks/checkMachine or sets mMachine. */
final class TestLabCompositeFixture {

    private final TestLabRuntime runtime;
    private final TestLabZone zone;
    private final List<MTEMultiBlockBase> machines = new ArrayList<>();
    private final NBTTagList controllers = new NBTTagList(), gateways = new NBTTagList(), extras = new NBTTagList();
    private final Random random = new Random(0x47544d423131L);
    private int registryCursor;
    private final Set<String> roles = new HashSet<>();
    private final NBTTagList devices = new NBTTagList(), wirelessHatches = new NBTTagList();
    private NBTTagCompound patternReceipt = new NBTTagCompound();
    private final NBTTagList links = new NBTTagList();

    TestLabCompositeFixture(TestLabRuntime runtime) {
        this.runtime = runtime;
        zone = runtime.config()
            .zone("composite-source");
    }

    /** One fixture operation per cell. Source selection is exactly 40 cubed, with native machine cavities. */
    void fill(int index) throws Exception {
        int[] at = zone.cell(index);
        runtime.requireOwned(at[0], at[1], at[2], 1, 1, 1);
        TestLabTasks.require(
            runtime.world()
                .isAirBlock(at[0], at[1], at[2]),
            "Composite source is not fresh: reset the owned facilities before rebuilding");
        TestLabTasks.require(
            runtime.world()
                .setBlock(at[0], at[1], at[2], Blocks.stone, 0, 2),
            "Native composite filler placement failed");
    }

    /** At most one 3x4x3 machine per cooperative step, placed through the official machine item. */
    void machine(int index) throws Exception {
        int x = zone.x + index % 8 * 5, y = zone.y + index / 64 * 6, z = zone.z + index / 8 % 8 * 5;
        boolean blast = index % 2 == 0;
        int height = blast ? 4 : 3;
        for (int dx = 0; dx < 3; dx++) for (int dy = 0; dy < height; dy++) for (int dz = 0; dz < 3; dz++) block(
            x + dx,
            y + dy,
            z + dz,
            blast ? GregTechAPI.sBlockCasings1 : GregTechAPI.sBlockCasings8,
            blast ? 11 : 0);
        BaseMetaTileEntity base = gt(
            blast ? ItemList.Machine_Multi_BlastFurnace.get(1) : ItemList.Machine_Multi_LargeChemicalReactor.get(1),
            x + 1,
            y + (blast ? 0 : 1),
            z,
            ForgeDirection.NORTH);
        MTEMultiBlockBase machine;
        if (blast) {
            MTEElectricBlastFurnace furnace = (MTEElectricBlastFurnace) base.getMetaTileEntity();
            furnace.setExtendedFacing(ExtendedFacing.NORTH_NORMAL_NONE);
            machine = furnace;
            for (int dy = 1; dy <= 2; dy++) for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++)
                block(x + dx, y + dy, z + dz, dx == 1 && dz == 1 ? Blocks.air : GregTechAPI.sBlockCasings5, 0);
            gt(ItemList.Hatch_Muffler_HV.get(1), x + 1, y + 3, z + 1, ForgeDirection.UP);
            // The muffler's actual outward exhaust cell must remain air.
            block(x + 1, y + 4, z + 1, Blocks.air, 0);
        } else {
            MTELargeChemicalReactor reactor = (MTELargeChemicalReactor) base.getMetaTileEntity();
            reactor.setExtendedFacing(ExtendedFacing.NORTH_NORMAL_NONE);
            machine = reactor;
            block(x + 1, y + 1, z + 1, GregTechAPI.sBlockCasings8, 1);
            block(x + 1, y + 2, z + 1, GregTechAPI.sBlockCasings5, 0);
        }
        base.disableWorking();
        machine.setInventorySlotContents(machine.getControllerSlotIndex(), GTUtility.getIntegratedCircuit(1));
        gt(ItemList.Hatch_Energy_HV.get(1), x, y, z, ForgeDirection.NORTH);
        gt(ItemList.Wireless_Hatch_Energy_HV.get(1), x + 2, y, z, ForgeDirection.NORTH);
        NBTTagCompound wireless = new NBTTagCompound();
        wireless.setIntArray("position", new int[] { x + 2, y, z });
        wirelessHatches.appendTag(wireless);
        MTEHatchMaintenance maintenance = (MTEHatchMaintenance) gt(
            ItemList.Hatch_AutoMaintenance.get(1),
            x,
            y,
            z + 1,
            ForgeDirection.WEST).getMetaTileEntity();
        ItemStack[] maintenanceStock = MTEHatchMaintenance.getAutoMaintenanceInputs();
        for (int slot = 0; slot < maintenanceStock.length; slot++)
            maintenance.setInventorySlotContents(slot, maintenanceStock[slot].copy());
        MTEHatchInputBusME itemInput = (MTEHatchInputBusME) gt(
            ItemList.Hatch_Input_Bus_ME.get(1),
            x,
            y,
            z + 2,
            ForgeDirection.SOUTH).getMetaTileEntity();
        itemInput.setSlotConfig(0, blast ? Materials.Iron.getDust(1) : Materials.Sulfur.getDust(1));
        MTEHatchInputME fluidInput = (MTEHatchInputME) gt(
            ItemList.Hatch_Input_ME.get(1),
            x + 1,
            y,
            z + 2,
            ForgeDirection.SOUTH).getMetaTileEntity();
        fluidInput.setSlotConfig(0, blast ? Materials.Nitrogen.getGas(1) : Materials.Oxygen.getGas(1));
        gt(ItemList.Hatch_Output_Bus_ME.get(1), x + 2, y, z + 2, ForgeDirection.SOUTH);
        // Fourth ME hatch uses the middle-right casing, not a heating coil.
        gt(ItemList.Hatch_Output_ME.get(1), x + 2, y, z + 1, ForgeDirection.EAST);
        gateway(x, y, z + 2, ForgeDirection.SOUTH);
        gateway(x + 1, y, z + 2, ForgeDirection.SOUTH);
        gateway(x + 2, y, z + 2, ForgeDirection.SOUTH);
        gateway(x + 2, y, z + 1, ForgeDirection.EAST);
        machines.add(machine);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("kind", blast ? "EBF" : "LCR");
        receipt.setIntArray("position", new int[] { base.xCoord, base.yCoord, base.zCoord });
        receipt.setInteger("x", base.xCoord);
        receipt.setInteger("y", base.yCoord);
        receipt.setInteger("z", base.zCoord);
        receipt.setString(
            "nativeClass",
            machine.getClass()
                .getName());
        receipt.setTag(
            "controllerItem",
            machine.getStackForm(1)
                .writeToNBT(new NBTTagCompound()));
        controllers.appendTag(receipt);
    }

    /** Twenty actual distinct GT recipe-map machine roles, with reproducible native covers. */
    void extra(int index) throws Exception {
        IMetaTileEntity prototype = null;
        while (registryCursor < GregTechAPI.METATILEENTITIES.length) {
            IMetaTileEntity next = GregTechAPI.METATILEENTITIES[registryCursor++];
            if (next instanceof MTEBasicMachine) {
                MTEBasicMachine basic = (MTEBasicMachine) next;
                if (basic.getRecipeMap() != null && roles.add(basic.getRecipeMap().unlocalizedName)) {
                    prototype = next;
                    break;
                }
            }
        }
        TestLabTasks.require(prototype != null, "Pack does not contain 20 distinct GT single block recipe maps");
        ItemStack item = prototype.getStackForm(1);
        int x = zone.x + index % 10 * 4, y = zone.y + 16, z = zone.z + index / 10 * 4;
        BaseMetaTileEntity base = gt(item, x, y, z, ForgeDirection.NORTH);
        base.disableWorking();
        ItemStack[] choices = { ItemList.Cover_Controller.get(1), ItemList.Conveyor_Module_LV.get(1),
            ItemList.Electric_Pump_LV.get(1), ItemList.Cover_RedstoneTransmitter.get(1) };
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (side == ForgeDirection.NORTH || !random.nextBoolean()) continue;
            ItemStack cover = choices[random.nextInt(choices.length)];
            TestLabTasks.require(CoverRegistry.isCover(cover), "Native random cover is not registered");
            base.attachCover(CoverRegistry.buildCover(cover.copy(), side, base));
            TestLabTasks.require(
                base.getCoverAtSide(side)
                    .isValid(),
                "Actual GT cover attachment failed");
        }
        NBTTagCompound row = new NBTTagCompound();
        row.setTag("item", item.writeToNBT(new NBTTagCompound()));
        row.setIntArray("position", new int[] { x, y, z });
        row.setString(
            "nativeClass",
            base.getMetaTileEntity()
                .getClass()
                .getName());
        extras.appendTag(row);
    }

    static final String[] AE_DEVICES = { "drive", "chest", "iface", "cellWorkbench", "iOPort", "condenser",
        "energyAcceptor", "energyCell", "energyCellDense", "inscriber", "advancedInscriber", "molecularAssembler",
        "craftingUnit", "craftingAccelerator", "craftingStorage1k", "craftingStorage4k", "craftingStorage16k",
        "craftingStorage64k", "craftingMonitor", "quartzGrowthAccelerator", "charger", "vibrationChamber",
        "storageReshuffle", "patternsOptimizationMatrix" };
    static final String[] EIO_DEVICES = { "blockSagMill", "blockAlloySmelter", "blockCapBank", "blockPowerMonitor",
        "blockBuffer", "blockFarmStation", "blockVat", "blockSoulBinder", "blockSliceAndSplice",
        "blockCombustionGenerator", "blockStirlingGenerator", "blockReservoir" };

    /** Three copies of each native AE device, including configured interfaces and independent physical cells. */
    void aeDevice(int index) throws Exception {
        String method = AE_DEVICES[index % AE_DEVICES.length];
        Object definitions = AEApi.instance()
            .definitions()
            .blocks();
        IBlockDefinition definition = (IBlockDefinition) definitions.getClass()
            .getMethod(method)
            .invoke(definitions);
        int x = zone.x + index % 10 * 4, y = zone.y + 22, z = zone.z + index / 10 * 4;
        ItemStack nativeItem = definition.maybeStack(1)
            .get();
        TileEntity tile = device(
            definition.maybeBlock()
                .get(),
            nativeItem.getItemDamage(),
            x,
            y,
            z,
            "AE2:" + method);
        if (tile instanceof TileDrive) {
            TileDrive drive = (TileDrive) tile;
            drive.getInternalInventory()
                .setInventorySlotContents(
                    0,
                    AEApi.instance()
                        .definitions()
                        .items()
                        .cell64k()
                        .maybeStack(1)
                        .get());
        } else if ("appeng.tile.storage.TileChest".equals(
            tile.getClass()
                .getName())) {
                    // TileChest's optional Mekanism superinterface is not a GTMB compilation dependency.
                    // The exact native tile exposes its real cell slot through vanilla IInventory.
                    IInventory inventory = (IInventory) tile.getClass()
                        .getMethod("getInternalInventory")
                        .invoke(tile);
                    ItemStack cell = AEApi.instance()
                        .definitions()
                        .items()
                        .cell64k()
                        .maybeStack(1)
                        .get();
                    TestLabTasks.require(
                        inventory.getSizeInventory() == 2 && inventory.isItemValidForSlot(1, cell),
                        "Native AE chest slot 1 must be its real storage-cell slot");
                    inventory.setInventorySlotContents(1, cell);
                    TestLabTasks
                        .require(inventory.getStackInSlot(1) == cell, "Native AE chest physical cell insertion failed");
                }
        nativeSmallInventory(tile, index);
        gateway(x, y, z, ForgeDirection.SOUTH);
    }

    void eioDevice(int index) throws Exception {
        String name = EIO_DEVICES[index % EIO_DEVICES.length];
        Block block = GameRegistry.findBlock("EnderIO", name);
        TestLabTasks.require(block != null, "Required native EnderIO registration absent: " + name);
        int x = zone.x + index % 10 * 4, y = zone.y + 30, z = zone.z + index / 10 * 4;
        nativeSmallInventory(device(block, 0, x, y, z, "EnderIO:" + name), index);
    }

    private TileEntity device(Block block, int meta, int x, int y, int z, String role) throws Exception {
        block(x, y, z, block, meta);
        block.onBlockPlacedBy(runtime.world(), x, y, z, runtime.actor().player, new ItemStack(block, 1, meta));
        TileEntity tile = runtime.world()
            .getTileEntity(x, y, z);
        TestLabTasks.require(tile != null && !tile.isInvalid(), "Native device tile absent for " + role);
        NBTTagCompound row = new NBTTagCompound();
        row.setString("role", role);
        row.setIntArray("position", new int[] { x, y, z });
        row.setString("registry", String.valueOf(Block.blockRegistry.getNameForObject(block)));
        row.setInteger("meta", meta);
        row.setString(
            "nativeClass",
            tile.getClass()
                .getName());
        devices.appendTag(row);
        return tile;
    }

    private void nativeSmallInventory(TileEntity tile, int index) throws Exception {
        if ("appeng.tile.storage.TileChest".equals(
            tile.getClass()
                .getName())) {
            // Slot 0 is an automatic insertion port, not stable fixture stock. Its actual 64k cell remains installed.
            tile.markDirty();
            return;
        }
        if (tile instanceof TileInterface) {
            TileInterface iface = (TileInterface) tile;
            ItemStack sample = index % 2 == 0 ? Materials.Iron.getDust(3) : GTUtility.getIntegratedCircuit(1);
            iface.getInterfaceDuality()
                .getConfig()
                .setInventorySlotContents(0, sample.copy());
            iface.getInterfaceDuality()
                .getStorage()
                .setInventorySlotContents(0, sample.copy());
            TestLabTasks.require(
                ItemStack.areItemStacksEqual(
                    sample,
                    iface.getInterfaceDuality()
                        .getStorage()
                        .getStackInSlot(0))
                    && ItemStack.areItemStacksEqual(
                        sample,
                        iface.getInterfaceDuality()
                            .getConfig()
                            .getStackInSlot(0)),
                "Native AE interface must hold actual stock matching its configured target quantity");
            tile.markDirty();
            return;
        }
        if (tile instanceof IInventory) {
            IInventory inventory = (IInventory) tile;
            ItemStack sample = index % 2 == 0 ? Materials.Iron.getDust(3) : GTUtility.getIntegratedCircuit(1);
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                if (inventory.getStackInSlot(slot) == null && inventory.isItemValidForSlot(slot, sample)) {
                    inventory.setInventorySlotContents(slot, sample.copy());
                    break;
                }
        }
        tile.markDirty();
    }

    void connect(int index, ItemStack terminal) throws Exception {
        NBTTagCompound gateway = gateways.getCompoundTagAt(index);
        int[] host = gateway.getIntArray("host");
        int side = gateway.getInteger("side");
        TestLabTasks.require(
            terminal != null && terminal.getItem()
                .onItemUseFirst(
                    terminal,
                    runtime.actor().player,
                    runtime.world(),
                    host[0],
                    host[1],
                    host[2],
                    side,
                    .5F,
                    .5F,
                    .5F),
            "Native bound quantum terminal interaction failed");
        ForgeDirection direction = ForgeDirection.getOrientation(side);
        TileEntity node = runtime.world()
            .getTileEntity(host[0] + direction.offsetX, host[1] + direction.offsetY, host[2] + direction.offsetZ);
        TestLabTasks.require(
            node != null && node.getClass()
                .getName()
                .equals("com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode"),
            "Real terminal did not create quantum node");
    }

    /** Native multipart placement owns host creation, registered parts and attachment NBT. */
    void cable(int index) throws Exception {
        int x = zone.x + index % 10 * 3, y = zone.y + 26, z = zone.z + index / 10 * 3;
        block(x, y, z, Blocks.air, 0);
        ItemStack cable = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(appeng.api.util.AEColor.Transparent, 1);
        ItemStack originalHeld = runtime.actor().player.getHeldItem();
        try {
            runtime.actor().player.inventory.setInventorySlotContents(0, cable);
            TestLabTasks.require(
                appeng.parts.PartPlacement
                    .placeItemPart(cable, runtime.actor().player, runtime.world(), x, y, z, ForgeDirection.UP),
                "Native AE cable placement failed");
            if (index % 3 == 0) {
                ItemStack part = AEApi.instance()
                    .definitions()
                    .parts()
                    .terminal()
                    .maybeStack(1)
                    .get();
                runtime.actor().player.inventory.setInventorySlotContents(0, part);
                TestLabTasks.require(
                    appeng.parts.PartPlacement
                        .placeItemPart(part, runtime.actor().player, runtime.world(), x, y, z, ForgeDirection.NORTH),
                    "Native AE terminal attachment failed");
            }
        } finally {
            runtime.actor().player.inventory.setInventorySlotContents(0, originalHeld);
        }
        gateway(x, y, z, ForgeDirection.SOUTH);
    }

    void screen(int index) throws Exception {
        Block screen = GameRegistry.findBlock("gtswn", "NetworkInfoPanel_GTswn");
        TestLabTasks.require(screen != null, "Required actual GTSWN industrial information panel absent");
        int x = zone.x + index * 3, y = zone.y + 35, z = zone.z + 3;
        TileEntity tile = device(screen, ForgeDirection.NORTH.ordinal(), x, y, z, "GTSWN:NetworkInfoPanel");
        tile.getClass()
            .getMethod("bindOwner", java.util.UUID.class, String.class)
            .invoke(tile, runtime.actor().player.getUniqueID(), runtime.actor().player.getCommandSenderName());
        tile.getClass()
            .getMethod("setChartItem", ItemStack.class)
            .invoke(tile, Materials.Iron.getDust(1));
        tile.getClass()
            .getMethod("toggleItemMonitor", ItemStack.class)
            .invoke(tile, Materials.Sulfur.getDust(1));
        gateway(x, y, z, ForgeDirection.SOUTH);
    }

    void link(int index) throws Exception {
        BaseMetaTileEntity host = null;
        int eligible = 0;
        for (int candidate = 0; candidate < extras.tagCount(); candidate++) {
            int[] position = extras.getCompoundTagAt(candidate)
                .getIntArray("position");
            BaseMetaTileEntity actual = (BaseMetaTileEntity) runtime.world()
                .getTileEntity(position[0], position[1], position[2]);
            // Registry order includes real steam machines. GTSWN correctly rejects their zero EU capacity/voltage.
            if (actual.getEUCapacity() <= 0 || (actual.getInputVoltage() <= 0 && actual.getOutputVoltage() <= 0))
                continue;
            if (eligible++ == index) {
                host = actual;
                break;
            }
        }
        TestLabTasks.require(host != null, "Four native electrical extra machines are required for link terminals");
        runtime.requireOwned(host.xCoord, host.yCoord, host.zCoord, 1, 1, 1);
        if (host.hasCoverAtSide(ForgeDirection.UP)) host.detachCover(ForgeDirection.UP);
        Class<?> list = Class.forName("com.miaokatze.gtswn.common.api.enums.GTSWNItemList");
        Object entry = java.lang.Enum.valueOf(list.asSubclass(java.lang.Enum.class), "Wireless_Energy_Tap");
        ItemStack terminal = (ItemStack) list.getMethod("get", int.class)
            .invoke(entry, 1);
        TestLabTasks.require(
            terminal.getItemDamage() == 0 && !terminal.hasTagCompound(),
            "Fresh actual link terminal must start in native energy-input mode");
        ItemStack originalHeld = runtime.actor().player.getHeldItem();
        boolean originalSneaking = runtime.actor().player.isSneaking();
        try {
            runtime.actor().player.inventory.setInventorySlotContents(0, terminal);
            runtime.actor().player.setSneaking(false);
            TestLabTasks.require(
                terminal.getItem()
                    .onItemUseFirst(
                        terminal,
                        runtime.actor().player,
                        runtime.world(),
                        host.xCoord,
                        host.yCoord,
                        host.zCoord,
                        ForgeDirection.UP.ordinal(),
                        .5F,
                        .5F,
                        .5F),
                "Native link terminal interaction failed");
        } finally {
            runtime.actor().player.inventory.setInventorySlotContents(0, originalHeld);
            runtime.actor().player.setSneaking(originalSneaking);
        }
        TestLabTasks.require(
            host.hasCoverAtSide(ForgeDirection.UP) && host.getCoverAtSide(ForgeDirection.UP)
                .isValid()
                && host.getCoverAtSide(ForgeDirection.UP)
                    .getClass()
                    .getName()
                    .equals("com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless"),
            "Native link terminal did not install the actual configured energy cover");
        NBTTagCompound row = new NBTTagCompound();
        row.setIntArray("position", new int[] { host.xCoord, host.yCoord, host.zCoord });
        row.setLong("nativeEUCapacity", host.getEUCapacity());
        row.setLong("nativeInputVoltage", host.getInputVoltage());
        row.setLong("nativeOutputVoltage", host.getOutputVoltage());
        row.setInteger("side", ForgeDirection.UP.ordinal());
        row.setTag("nativeTerminal", terminal.writeToNBT(new NBTTagCompound()));
        NBTTagCompound nativeCover = new NBTTagCompound();
        CoverRegistry.writeCoverToNbt(host.getCoverAtSide(ForgeDirection.UP), nativeCover);
        row.setTag("nativeCover", nativeCover);
        links.appendTag(row);
        if (index == 0) runtime.actor().player.inventory.setInventorySlotContents(31, terminal);
    }

    /** Actual native encoder, installed patterns, and native PatternSlot item/fluid buffers. No fixture recipe runs. */
    void patternAssets() throws Exception {
        int x = zone.x + 26, y = zone.y + 35, z = zone.z + 20;
        block(x, y, z, Blocks.air, 0);
        ItemStack cable = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(appeng.api.util.AEColor.Transparent, 1);
        ItemStack patternTerminal = AEApi.instance()
            .definitions()
            .parts()
            .patternTerminal()
            .maybeStack(1)
            .get();
        ItemStack originalHeld = runtime.actor().player.getHeldItem();
        try {
            runtime.actor().player.inventory.setInventorySlotContents(0, cable);
            TestLabTasks.require(
                appeng.parts.PartPlacement
                    .placeItemPart(cable, runtime.actor().player, runtime.world(), x, y, z, ForgeDirection.UP),
                "Native pattern-terminal cable host placement failed");
            runtime.actor().player.inventory.setInventorySlotContents(0, patternTerminal);
            TestLabTasks.require(
                appeng.parts.PartPlacement.placeItemPart(
                    patternTerminal,
                    runtime.actor().player,
                    runtime.world(),
                    x,
                    y,
                    z,
                    ForgeDirection.NORTH),
                "Native pattern-terminal part placement failed");
        } finally {
            runtime.actor().player.inventory.setInventorySlotContents(0, originalHeld);
        }
        IPartHost host = appeng.parts.PartPlacement.getExistingHost(
            runtime.world()
                .getTileEntity(x, y, z));
        TestLabTasks.require(
            host != null && host.getPart(ForgeDirection.NORTH) instanceof IPatternTerminal,
            "Actual native IPatternTerminal part required");
        IPatternTerminal terminal = (IPatternTerminal) host.getPart(ForgeDirection.NORTH);
        terminal.setCraftingRecipe(false);
        terminal.setSubstitution(false);
        terminal.setCanBeSubstitution(false);
        terminal.getAEInventoryByName(StorageName.CRAFTING_INPUT)
            .putAEStackInSlot(0, AEItemStack.create(Materials.Sulfur.getDust(1)));
        terminal.getAEInventoryByName(StorageName.CRAFTING_INPUT)
            .putAEStackInSlot(1, AEFluidStack.create(Materials.Oxygen.getGas(2000)));
        terminal.getAEInventoryByName(StorageName.CRAFTING_OUTPUT)
            .putAEStackInSlot(0, AEFluidStack.create(Materials.SulfurDioxide.getGas(1000)));
        IInventory patternSlots = terminal.getInventoryByName("pattern");
        ItemStack blank = AEApi.instance()
            .definitions()
            .materials()
            .blankPattern()
            .maybeStack(1)
            .get();
        patternSlots.setInventorySlotContents(0, blank);
        NBTTagCompound evidence = new NBTTagCompound();
        evidence.setTag("blankBeforeNativeEncode", blank.writeToNBT(new NBTTagCompound()));
        TestLabTasks.require(
            terminal.encode(null, null, null, runtime.actor().player.getCommandSenderName(), runtime.world()),
            "Actual native pattern encoder rejected the processing recipe");
        TestLabTasks
            .require(patternSlots.getStackInSlot(0) == null, "Native encoding must consume its physical blank pattern");
        ItemStack encoded = patternSlots.getStackInSlot(1);
        TestLabTasks.require(
            encoded != null && encoded.getItem() instanceof ICraftingPatternItem,
            "Native encoding did not produce a registered crafting-pattern item");
        ICraftingPatternDetails details = ((ICraftingPatternItem) encoded.getItem())
            .getPatternForItem(encoded, runtime.world());
        TestLabTasks.require(
            details != null && !details.isCraftable()
                && details.getCondensedAEInputs().length == 2
                && details.getCondensedAEOutputs().length == 1,
            "Native encoded processing recipe details invalid");
        evidence.setInteger("nativeEncodingBlankConsumed", 1);
        evidence.setTag("encoded", encoded.writeToNBT(new NBTTagCompound()));
        evidence.setIntArray("terminalPosition", new int[] { x, y, z });
        gateway(x, y, z, ForgeDirection.SOUTH);

        BaseMetaTileEntity base = gt(ItemList.Hatch_CraftingInput_Bus_ME.get(1), x + 4, y, z, ForgeDirection.SOUTH);
        TestLabTasks.require(
            base.getMetaTileEntity() instanceof MTEHatchCraftingInputME,
            "Registered native GT crafting-input ME hatch required");
        MTEHatchCraftingInputME hatch = (MTEHatchCraftingInputME) base.getMetaTileEntity();
        hatch.setInventorySlotContents(0, encoded.copy());
        java.util.Iterator<MTEHatchCraftingInputME.PatternSlot<MTEHatchCraftingInputME>> slots = hatch.inventories();
        TestLabTasks.require(slots.hasNext(), "Native GT pattern change did not create PatternSlot");
        MTEHatchCraftingInputME.PatternSlot<MTEHatchCraftingInputME> slot = slots.next();
        TestLabTasks.require(
            slot.getPatternDetails() != null && !slots.hasNext(),
            "Exactly one valid native PatternSlot required");
        MEInventoryCrafting buffered = new MEInventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(EntityPlayer player) {
                return false;
            }
        }, 2, 1);
        buffered.setInventorySlotContents(0, AEItemStack.create(Materials.Sulfur.getDust(13)));
        buffered.setInventorySlotContents(1, AEFluidStack.create(Materials.Oxygen.getGas(26000)));
        TestLabTasks.require(slot.insertItemsAndFluids(buffered), "Native PatternSlot buffered supply rejected");
        TestLabTasks.require(
            slot.getItemInputs().length == 1 && slot.getItemInputs()[0].stackSize == 13
                && slot.getFluidInputs().length == 1
                && slot.getFluidInputs()[0].amount == 26000,
            "Actual native PatternSlot item/fluid buffer counts differ from finite fixture supply");
        base.disableWorking();
        base.markDirty();
        evidence.setTag("nativePatternSlot", slot.writeToNBT(new NBTTagCompound()));
        NBTTagCompound hatchNbt = new NBTTagCompound();
        base.writeToNBT(hatchNbt);
        evidence.setTag("sourceHatchNbt", hatchNbt);
        evidence.setIntArray("hatchPosition", new int[] { x + 4, y, z });
        gateway(x + 4, y, z, ForgeDirection.SOUTH);

        TileEntity interfaceTile = device(
            AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeBlock()
                .get(),
            0,
            x + 8,
            y,
            z,
            "AE2:encodedPatternInterface");
        TestLabTasks.require(interfaceTile instanceof TileInterface, "Native AE interface tile required");
        IInventory patterns = ((TileInterface) interfaceTile).getInterfaceDuality()
            .getPatterns();
        patterns.setInventorySlotContents(0, encoded.copy());
        TestLabTasks.require(
            ItemStack.areItemStacksEqual(encoded, patterns.getStackInSlot(0)),
            "Native AE interface physical encoded-pattern slot did not preserve recipe NBT");
        NBTTagCompound interfaceNbt = new NBTTagCompound();
        interfaceTile.writeToNBT(interfaceNbt);
        evidence.setTag("sourceInterfaceNbt", interfaceNbt);
        evidence.setIntArray("interfacePosition", new int[] { x + 8, y, z });
        evidence.setString(
            "bufferSupply",
            "native PatternSlot.insertItemsAndFluids(MEInventoryCrafting): sulfur=13, oxygen=26000mB");
        evidence.setString(
            "encodingEntry",
            "actual PartPatternTerminal.encode / PatternEncodingHelper, consuming one physical blank");
        patternReceipt = evidence;
        gateway(x + 8, y, z, ForgeDirection.SOUTH);
    }

    int gatewayCount() {
        return gateways.tagCount();
    }

    private void gateway(int x, int y, int z, ForgeDirection side) throws Exception {
        block(x + side.offsetX, y + side.offsetY, z + side.offsetZ, Blocks.air, 0);
        NBTTagCompound row = new NBTTagCompound();
        row.setIntArray("host", new int[] { x, y, z });
        row.setInteger("side", side.ordinal());
        gateways.appendTag(row);
    }

    boolean formed() {
        if (machines.size() != runtime.config().compositeMachines) return false;
        for (MTEMultiBlockBase machine : machines) if (!machine.mMachine || machine.getBaseMetaTileEntity()
            .isAllowedToWork() || machine.mProgresstime != 0 || machine.mMaxProgresstime != 0) return false;
        return true;
    }

    NBTTagCompound receipt() {
        NBTTagCompound out = new NBTTagCompound();
        out.setString("fixtureVersion", "native-composite-40-v1");
        out.setInteger("machineCount", machines.size());
        out.setInteger("ebfCount", (machines.size() + 1) / 2);
        out.setInteger("lcrCount", machines.size() / 2);
        out.setInteger("additionalGtVariants", extras.tagCount());
        out.setInteger("selectionVolume", zone.cells());
        out.setLong("coverSeed", 0x47544d423131L);
        out.setBoolean("naturalFormedIdle", formed());
        out.setBoolean("debugEnergy", false);
        out.setBoolean("formationFlagWritten", false);
        out.setTag("controllers", controllers.copy());
        out.setTag("gateways", gateways.copy());
        out.setTag("additionalGt", extras.copy());
        out.setTag("devices", devices.copy());
        out.setTag("patternFixture", patternReceipt.copy());
        out.setTag("nativeLinks", links.copy());
        out.setTag("wirelessHatches", wirelessHatches.copy());
        out.setInteger("distinctExtraRoles", roles.size() + AE_DEVICES.length + EIO_DEVICES.length);
        NBTTagList live = new NBTTagList();
        for (MTEMultiBlockBase machine : machines) {
            BaseMetaTileEntity base = (BaseMetaTileEntity) machine.getBaseMetaTileEntity();
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("position", new int[] { base.xCoord, base.yCoord, base.zCoord });
            row.setLong("timer", base.getTimer());
            row.setInteger("startUpCheck", machine.getmStartUpCheck());
            row.setBoolean("invalid", base.isInvalid());
            row.setBoolean("tickDisabled", base.isTickDisabled());
            row.setBoolean(
                "worldTileMatches",
                runtime.world()
                    .getTileEntity(base.xCoord, base.yCoord, base.zCoord) == base);
            row.setBoolean("tickListContains", runtime.world().loadedTileEntityList.contains(base));
            Chunk chunk = (Chunk) runtime.world().theChunkProviderServer.loadedChunkHashMap
                .getValueByKey(ChunkCoordIntPair.chunkXZ2Int(base.xCoord >> 4, base.zCoord >> 4));
            row.setBoolean("chunkLoaded", chunk != null && chunk.isChunkLoaded);
            row.setBoolean("formed", machine.mMachine);
            row.setInteger("progress", machine.mProgresstime);
            row.setInteger("maxProgress", machine.mMaxProgresstime);
            row.setLong("recipesDone", machine.recipesDone);
            row.setString("recipeMap", machine.getRecipeMap().unlocalizedName);
            row.setString(
                "nativeRecipeResult",
                machine.getCheckRecipeResult()
                    .getID());
            live.appendTag(row);
        }
        out.setTag("liveMachines", live);
        return out;
    }

    private BaseMetaTileEntity gt(ItemStack item, int x, int y, int z, ForgeDirection facing) throws Exception {
        runtime.requireOwned(x, y, z, 1, 1, 1);
        TestLabTasks
            .require(item != null && item.getItem() instanceof ItemMachines, "Registered GT machine item required");
        block(x, y, z, Blocks.air, 0);
        TestLabTasks.require(
            ((ItemMachines) item.getItem()).placeBlockAt(
                item,
                runtime.actor().player,
                runtime.world(),
                x,
                y,
                z,
                facing.ordinal(),
                .5F,
                .5F,
                .5F,
                0),
            "Official ItemMachines placement failed");
        TileEntity tile = runtime.world()
            .getTileEntity(x, y, z);
        TestLabTasks.require(tile instanceof BaseMetaTileEntity, "Native GT tile factory failed");
        BaseMetaTileEntity base = (BaseMetaTileEntity) tile;
        base.setFrontFacing(facing);
        base.setOwnerName(runtime.actor().player.getCommandSenderName());
        base.setOwnerUuid(runtime.actor().player.getUniqueID());
        base.markDirty();
        return base;
    }

    private void block(int x, int y, int z, Block block, int meta) throws Exception {
        runtime.requireOwned(x, y, z, 1, 1, 1);
        if (runtime.world()
            .getTileEntity(x, y, z) != null) TestLabFixtureWorld.clear(runtime.world(), x, y, z);
        if (runtime.world()
            .getBlock(x, y, z) != block
            || runtime.world()
                .getBlockMetadata(x, y, z) != meta)
            TestLabTasks.require(
                runtime.world()
                    .setBlock(x, y, z, block, meta, 3),
                "Native structure block placement failed");
    }
}
