// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import appeng.util.Platform;
import cpw.mods.fml.common.Optional;

final class HologramCarriedTerminals {

    private HologramCarriedTerminals() {}

    // AE's pick-block order: equipped terminals first, then hotbar and inventory.
    static List<ItemStack> candidates(EntityPlayerMP player) {
        List<ItemStack> result = new ArrayList<>();
        IInventory equipped = Platform.isBaublesLoaded ? baubles(player) : null;
        if (equipped != null)
            for (int i = 0; i < equipped.getSizeInventory(); i++) add(result, equipped.getStackInSlot(i));
        for (ItemStack stack : player.inventory.mainInventory) add(result, stack);
        return result;
    }

    private static void add(List<ItemStack> result, ItemStack stack) {
        if (stack != null && stack.stackSize > 0) result.add(stack);
    }

    static boolean contains(EntityPlayerMP player, ItemStack item) {
        if (item == null || item.stackSize <= 0) return false;
        for (ItemStack stack : player.inventory.mainInventory) if (stack == item) return true;
        IInventory equipped = Platform.isBaublesLoaded ? baubles(player) : null;
        if (equipped != null)
            for (int i = 0; i < equipped.getSizeInventory(); i++) if (equipped.getStackInSlot(i) == item) return true;
        return false;
    }

    @Optional.Method(modid = "Baubles|Expanded")
    private static IInventory baubles(EntityPlayerMP player) {
        return baubles.api.BaublesApi.getBaubles(player);
    }
}
