package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.me.storage.ItemCellInventory;
import appeng.tile.networking.TileCableBus;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEItemStack;

/** Actual ordinary AE hosts, including deferred node persistence, a filled cell, configuration, and a real card. */
final class HologramCutAeNativeSmoke {

    private static int originalOwner;
    private static NBTTagCompound originalCell;

    private HologramCutAeNativeSmoke() {}

    static void run(WorldServer world, EntityPlayerMP actor) throws Exception {
        Block driveBlock = find("appeng.block.storage.BlockDrive");
        Block cableBlock = find("appeng.block.networking.BlockCableBus");
        require(
            world.isAirBlock(832, 200, 720) && world.isAirBlock(834, 200, 720)
                && world.isAirBlock(840, 200, 720)
                && world.isAirBlock(842, 200, 720),
            "fresh ordinary AE fixture");
        originalOwner = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(actor.getGameProfile());
        require(originalOwner >= 0, "native registered AE owner is nonnegative");
        world.setBlock(832, 200, 720, driveBlock, 0, 2);
        TileDrive drive = (TileDrive) world.getTileEntity(832, 200, 720);
        drive.setOrientation(ForgeDirection.WEST, ForgeDirection.UP);
        ItemStack cell = AEApi.instance()
            .definitions()
            .items()
            .cell1k()
            .maybeStack(1)
            .get();
        ItemCellInventory contents = new ItemCellInventory(cell, null);
        require(
            contents.injectItems(
                AEItemStack.create(new ItemStack(Items.diamond, 37)),
                Actionable.MODULATE,
                new PlayerSource(actor, null)) == null,
            "real native storage cell contains diamonds");
        ICellWorkbenchItem workbench = (ICellWorkbenchItem) cell.getItem();
        workbench.getConfigAEInventory(cell)
            .putAEStackInSlot(0, AEItemStack.create(new ItemStack(Items.diamond)));
        ItemStack card = AEApi.instance()
            .definitions()
            .materials()
            .cardFuzzy()
            .maybeStack(1)
            .get();
        IInventory cards = workbench.getUpgradesInventory(cell);
        require(cards.isItemValidForSlot(0, card), "real native cell accepts fuzzy card");
        cards.setInventorySlotContents(0, card);
        cell.setStackDisplayName("Cut cell original identity");
        drive.getInternalInventory()
            .setInventorySlotContents(3, cell);
        drive.onReady();
        IGridNode oldDriveNode = drive.getGridNode(ForgeDirection.UNKNOWN);
        require(oldDriveNode != null, "ordinary drive native node initialized");
        oldDriveNode.setPlayerID(originalOwner);
        originalCell = (NBTTagCompound) cell.writeToNBT(new NBTTagCompound())
            .copy();

        world.setBlock(834, 200, 720, cableBlock, 0, 2);
        TileCableBus bus = (TileCableBus) world.getTileEntity(834, 200, 720);
        NBTTagCompound seed = snapshot(bus);
        NBTTagCompound additions = HologramNativeCompatibilityFixture.seed(cableBlock, 834, 200, 720);
        for (Object key : additions.func_150296_c()) seed.setTag(
            (String) key,
            additions.getTag((String) key)
                .copy());
        bus.readFromNBT(seed);
        bus.onReady();
        IGridNode oldCableNode = bus.getPart(ForgeDirection.UNKNOWN)
            .getGridNode();
        require(oldCableNode != null, "ordinary cable bus native node initialized");
        oldCableNode.setPlayerID(originalOwner);
        NBTTagCompound driveOriginal = snapshot(drive), cableOriginal = snapshot(bus);
        require(
            driveOriginal.hasKey("proxy", 10) && driveOriginal.getCompoundTag("proxy")
                .getInteger("p") == originalOwner,
            "drive raw NBT contains original registered native owner");
        require(
            cableOriginal.getCompoundTag("extra:6")
                .getCompoundTag("part")
                .getInteger("p") == originalOwner,
            "ordinary cable raw NBT contains original registered native owner");

        NBTTagCompound cut = request("cut");
        cut.setInteger("x1", 832);
        cut.setInteger("x2", 834);
        cut.setInteger("y1", 200);
        cut.setInteger("y2", 200);
        cut.setInteger("z1", 720);
        cut.setInteger("z2", 720);
        NBTTagCompound captured = HologramCutService.execute(actor, cut);
        require(captured.getBoolean("success"), "ordinary AE cut: " + captured.getString("message"));
        require(
            driveOriginal.equals(HologramCutNativeReceipt.storedTile(actor, captured.getString("id"), 0)),
            "complete original drive NBT stored without transformation");
        require(
            cableOriginal.equals(HologramCutNativeReceipt.storedTile(actor, captured.getString("id"), 2)),
            "complete original cable NBT stored without transformation");
        require(
            drive.isInvalid() && bus.isInvalid()
                && drive.getProxy()
                    .getNode() == null
                && bus.getPart(ForgeDirection.UNKNOWN)
                    .getGridNode() == null,
            "ordinary AE old hosts and nodes detached");
        NBTTagCompound paste = request("paste");
        paste.setString("id", captured.getString("id"));
        paste.setInteger("originX", 840);
        paste.setInteger("originY", 200);
        paste.setInteger("originZ", 720);
        NBTTagCompound restored = HologramCutService.execute(actor, paste);
        require(restored.getBoolean("success"), "ordinary AE paste: " + restored.getString("message"));
        HologramCutNativeReceipt.record("ordinaryDrive", driveOriginal, snapshot(world.getTileEntity(840, 200, 720)));
        HologramCutNativeReceipt.record("ordinaryCable", cableOriginal, snapshot(world.getTileEntity(842, 200, 720)));
        require(
            ((TileDrive) world.getTileEntity(840, 200, 720)).getForward() == ForgeDirection.WEST,
            "ordinary drive original native facing preserved");
        verifyLater(world);
        System.out.println(
            "[GTMB-CUT-AE-NATIVE] CORE PASS driveCable=true originalOwner=" + originalOwner
                + " rawStoredNBT=true nativeChangesRecorded=true filledCell37=true slot3=true configurationAndCard=true");
    }

    static void verifyLater(WorldServer world) throws Exception {
        TileDrive drive = (TileDrive) world.getTileEntity(840, 200, 720);
        TileCableBus bus = (TileCableBus) world.getTileEntity(842, 200, 720);
        require(
            drive != null && bus != null && !drive.isInvalid() && !bus.isInvalid(),
            "ordinary AE hosts survive normal ticks");
        require(
            drive.getGridNode(ForgeDirection.UNKNOWN) != null && drive.getGridNode(ForgeDirection.UNKNOWN)
                .getPlayerID() == originalOwner,
            "ordinary drive native original owner survives natural ticks");
        require(
            bus.getPart(ForgeDirection.UNKNOWN)
                .getGridNode() != null
                && bus.getPart(ForgeDirection.UNKNOWN)
                    .getGridNode()
                    .getPlayerID() == originalOwner,
            "ordinary cable native original owner survives natural ticks");
        ItemStack actual = drive.getInternalInventory()
            .getStackInSlot(3);
        require(
            actual != null && originalCell.equals(actual.writeToNBT(new NBTTagCompound())),
            "filled cell configuration and card remain exactly in original inventory slot after natural ticks");
        require(
            new ItemCellInventory(actual, null).getStoredItemCount() == 37L,
            "actual native cell still contains all 37 diamonds");
    }

    private static Block find(String name) {
        for (Object block : Block.blockRegistry) if (block != null && block.getClass()
            .getName()
            .equals(name)) return (Block) block;
        throw new AssertionError("Required native fixture block missing: " + name);
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound result = new NBTTagCompound();
        tile.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    private static NBTTagCompound request(String action) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", action);
        request.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        return request;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
