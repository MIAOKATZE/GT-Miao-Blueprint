// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.WirelessTerminalGuiObject;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileDenseEnergyCell;
import appeng.tile.networking.TileWireless;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEItemStack;

/**
 * A small finite real AE network. Only explicit fixture supply writes storage; business debit and recovery stay native.
 */
final class HologramFourMachineAeBank {

    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final List<int[]> positions = new ArrayList<>();
    private final TileEntity controller;
    private final TileDenseEnergyCell power;
    private final TileWireless wap;
    private final TileDrive drive;
    private final ItemStack terminal;
    private final int slot;
    private int totalSupplied;

    HologramFourMachineAeBank(WorldServer world, EntityPlayerMP actor, int x, int y, int z, int slot) throws Exception {
        this.world = world;
        this.actor = actor;
        this.slot = slot;
        controller = place(
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeBlock()
                .get(),
            x,
            y,
            z,
            TileEntity.class);
        power = place(
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCellDense()
                .maybeBlock()
                .get(),
            x - 1,
            y,
            z,
            TileDenseEnergyCell.class);
        require(!power.isInfinite(), "Finite fixture power cell required");
        NBTTagCompound supply = new NBTTagCompound();
        supply.setDouble("internalCurrentPower", 1_000_000);
        power.readFromNBT_TileEnergyCell(supply);
        power.markDirty();
        require(power.getAECurrentPower() == 1_000_000, "Real finite AE initialization failed");
        wap = place(
            AEApi.instance()
                .definitions()
                .blocks()
                .wireless()
                .maybeBlock()
                .get(),
            x - 1,
            y + 1,
            z,
            TileWireless.class);
        wap.setOrientation(ForgeDirection.UP, ForgeDirection.NORTH);
        wap.getInternalInventory()
            .setInventorySlotContents(
                0,
                AEApi.instance()
                    .definitions()
                    .materials()
                    .wirelessBooster()
                    .maybeStack(32)
                    .get());
        wap.markDirty();
        drive = place(
            AEApi.instance()
                .definitions()
                .blocks()
                .drive()
                .maybeBlock()
                .get(),
            x - 1,
            y - 1,
            z,
            TileDrive.class);
        for (int index = 0; index < 2; index++) drive.getInternalInventory()
            .setInventorySlotContents(
                index,
                AEApi.instance()
                    .definitions()
                    .items()
                    .cell64k()
                    .maybeStack(1)
                    .get());
        drive.markDirty();
        terminal = AEApi.instance()
            .definitions()
            .items()
            .wirelessTerminal()
            .maybeStack(1)
            .get();
        require(terminal.getItem() instanceof IAEItemPowerStorage, "Registered finite wireless terminal missing");
        IAEItemPowerStorage battery = (IAEItemPowerStorage) terminal.getItem();
        double amount = Math.min(100_000, battery.getAEMaxPower(terminal));
        battery.injectAEPower(terminal, amount);
        require(battery.getAECurrentPower(terminal) > 0, "Finite wireless terminal initialization failed");
    }

    boolean ready() throws Exception {
        if (drive.getProxy()
            .getNode() == null || wap.getActionableNode() == null
            || !drive.getProxy()
                .isActive())
            return false;
        var handler = AEApi.instance()
            .registries()
            .wireless()
            .getWirelessTerminalHandler(terminal);
        require(
            handler != null && !handler.hasInfinityPower(terminal) && !handler.hasInfinityRange(terminal),
            "Finite ordinary terminal required");
        if (wap.getLocatableSerial() == 0) return false;
        handler.setEncryptionKey(terminal, Long.toString(wap.getLocatableSerial()), "Owned four-machine runtime bank");
        require(
            actor.inventory.getStackInSlot(slot) == null || actor.inventory.getStackInSlot(slot) == terminal,
            "Owned terminal slot changed");
        actor.inventory.setInventorySlotContents(slot, terminal);
        actor.inventoryContainer.detectAndSendChanges();
        WirelessTerminalGuiObject gui = new WirelessTerminalGuiObject(handler, terminal, actor, world, slot, 0, 0);
        if (!gui.rangeCheck() || gui.getActionableNode() == null) return false;
        ISecurityGrid security = drive.getProxy()
            .getGrid()
            .getCache(ISecurityGrid.class);
        require(
            security.hasPermission(actor, SecurityPermissions.EXTRACT)
                && security.hasPermission(actor, SecurityPermissions.INJECT),
            "Actual business actor lacks native AE storage permissions");
        return true;
    }

    void supply(List<ItemStack> stacks) throws Exception {
        require(ready(), "Native finite AE grid is not ready");
        int amount = 0;
        for (ItemStack stack : stacks) {
            require(stack != null && stack.stackSize > 0, "Invalid fixture supply");
            amount = Math.addExact(amount, stack.stackSize);
        }
        require(
            totalSupplied + amount <= HologramFourMachineSpec.MAX_SEEDED_ITEMS,
            "Finite AE fixture supply exhausted");
        for (ItemStack stack : stacks) {
            IAEItemStack remainder = items()
                .injectItems(AEItemStack.create(stack), Actionable.MODULATE, new PlayerSource(actor, drive));
            require(remainder == null || remainder.getStackSize() == 0, "Real AE fixture supply did not fit");
        }
        totalSupplied += amount;
    }

    Map<NBTTagCompound, Long> counts() throws Exception {
        var result = new LinkedHashMap<NBTTagCompound, Long>();
        for (IAEItemStack entry : items().getStorageList()) if (entry.getStackSize() > 0) result
            .merge(HologramFourMachineWorldAudit.identity(entry.getItemStack()), entry.getStackSize(), Math::addExact);
        return result;
    }

    NBTTagCompound snapshot() throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("items", HologramFourMachineWorldAudit.counts(counts()));
        result.setDouble("finiteAE", power.getAECurrentPower());
        result.setBoolean("infinite", power.isInfinite());
        result.setInteger("suppliedItems", totalSupplied);
        result.setTag("terminal", terminal.writeToNBT(new NBTTagCompound()));
        return result;
    }

    void removeOwned() {
        for (int index = positions.size() - 1; index >= 0; index--) {
            int[] p = positions.get(index);
            world.setBlockToAir(p[0], p[1], p[2]);
        }
    }

    private IMEMonitor<IAEItemStack> items() throws Exception {
        return drive.getProxy()
            .getStorage()
            .getItemInventory();
    }

    private <T extends TileEntity> T place(Block block, int x, int y, int z, Class<T> type) {
        require(world.isAirBlock(x, y, z), "AE fixture position was not initially empty");
        positions.add(new int[] { x, y, z });
        require(world.setBlock(x, y, z, block, 0, 3), "Native AE block placement failed");
        TileEntity tile = world.getTileEntity(x, y, z);
        require(type.isInstance(tile) && tile instanceof IGridProxyable, "Registered native AE factory mismatch");
        ((IGridProxyable) tile).getProxy()
            .setOwner(actor);
        tile.markDirty();
        return type.cast(tile);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
