// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputME;

/** A visual label for GT5U .265 recipe upgrades, not an item, asset, placement or payment identity. */
public final class HologramGtMeOutputDisplay {

    public static final String ITEMS = "gtmb.meOutputBaseItems";
    public static final String MILLIBUCKETS = "gtmb.meOutputBaseMillibuckets";
    private static final String BASE_CAPACITY = "baseCapacity";
    private static final int ITEM_BUS = 2710, FLUID_HATCH = 2713;

    private HologramGtMeOutputDisplay() {}

    static void observeLive(IGregTechTileEntity tile, NBTTagCompound display) {
        int id = tile.getMetaTileID();
        Class<?> owner = registeredOwner(id);
        if (owner == null) return;
        IMetaTileEntity machine = tile.getMetaTileEntity();
        if (machine == null || machine.getClass() != owner) return;
        try {
            // The two exact .265 implementations write only their non-default baseCapacity into this fresh tag.
            // Do not ask for drops, save the tile, inspect the installed cell, or touch the live output cache.
            NBTTagCompound itemNbt = new NBTTagCompound();
            machine.setItemNBT(itemNbt);
            write(display, id, capacity(itemNbt, id));
        } catch (RuntimeException | LinkageError unavailable) {
            // An unavailable optional visual detail does not invalidate the rest of the snapshot.
        }
    }

    /** Copies only the verified capacity scalar; callers supply the already selected preview data. */
    public static void observeStored(Block block, ItemStack stack, NBTTagCompound tile, NBTTagCompound display) {
        if (stack == null || block == null || block != GregTechAPI.sBlockMachines
            || stack.getItem() == null || stack.getItem().getClass() != ItemMachines.class
            || stack.getItem() != Item.getItemFromBlock(block)) return;
        int id = stack.getItemDamage();
        if (registeredOwner(id) == null) return;
        NBTTagCompound item = stack.getTagCompound();
        boolean hasItem = item != null && item.hasKey(BASE_CAPACITY);
        long itemCapacity = hasItem ? capacity(item, id) : 0;
        if (hasItem && itemCapacity == 0) return; // A malformed explicit item value never falls back to tile data.
        if (tile != null && tile.hasKey(BASE_CAPACITY)) {
            if (!"BaseMetaTileEntity".equals(tile.getString("id")) || !tile.hasKey("mID", 3)
                || tile.getInteger("mID") != id) return;
            long tileCapacity = capacity(tile, id);
            if (tileCapacity == 0 || hasItem && itemCapacity != tileCapacity) return;
            itemCapacity = tileCapacity;
        }
        write(display, id, itemCapacity);
    }

    static void observeFilteredTile(Block block, ItemStack stack, NBTTagCompound tile, NBTTagCompound display) {
        // A projected placement snapshot must not fall back to the material stack when author filtering omitted this key.
        if (tile != null && tile.hasKey(BASE_CAPACITY)) observeStored(block, stack, tile, display);
    }

    public static long read(NBTTagCompound display, boolean fluid) {
        String key = fluid ? MILLIBUCKETS : ITEMS;
        if (!display.hasKey(key, 4)) return 0;
        long value = display.getLong(key);
        return upgraded(fluid ? FLUID_HATCH : ITEM_BUS, value) ? value : 0;
    }

    private static void write(NBTTagCompound display, int id, long capacity) {
        if (capacity > 0) display.setLong(id == FLUID_HATCH ? MILLIBUCKETS : ITEMS, capacity);
    }

    private static long capacity(NBTTagCompound data, int id) {
        if (!data.hasKey(BASE_CAPACITY, 4)) return 0;
        long value = data.getLong(BASE_CAPACITY);
        return upgraded(id, value) ? value : 0;
    }

    private static boolean upgraded(int id, long value) {
        if (value == Long.MAX_VALUE) return true;
        long multiplier = id == FLUID_HATCH ? 256 : 1;
        for (long items = 8_192; items <= 134_217_728; items *= 4) {
            if (value == items * multiplier) return true;
        }
        return false;
    }

    private static Class<?> registeredOwner(int id) {
        Class<?> expected = id == ITEM_BUS ? MTEHatchOutputBusME.class
            : id == FLUID_HATCH ? MTEHatchOutputME.class : null;
        if (expected == null) return null;
        IMetaTileEntity registered = GregTechAPI.METATILEENTITIES[id];
        return registered != null && registered.getClass() == expected ? expected : null;
    }
}
