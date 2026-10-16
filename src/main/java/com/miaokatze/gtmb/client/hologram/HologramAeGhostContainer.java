package com.miaokatze.gtmb.client.hologram;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.ModularContainer;

/**
 * Empty native client-only MUI2 container, created and initialized by ClientGUI.
 * All icons live in ItemDisplayWidget, never in Container slots. Vanilla close may run when
 * NEI takes over the screen; it must not drop or otherwise mutate the player's real cursor.
 */
final class HologramAeGhostContainer extends ModularContainer {

    HologramAeGhostContainer() {
        windowId = -1;
    }

    @Override
    public ItemStack slotClick(int id, int button, int mode, EntityPlayer player) {
        return null;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slot) {
        return null;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return false;
    }

    @Override
    public boolean canDragIntoSlot(Slot slot) {
        return false;
    }

    @Override
    public boolean func_94530_a(ItemStack stack, Slot slot) {
        return false;
    }

    @Override
    protected boolean mergeItemStack(ItemStack stack, int start, int end, boolean reverse) {
        return false;
    }

    @Override
    protected void retrySlotClick(int slot, int button, boolean retry, EntityPlayer player) {}

    @Override
    public void onContainerClosed(EntityPlayer player) {}

    @Override
    public void putStackInSlot(int slot, ItemStack stack) {}

    @Override
    public void putStacksInSlots(ItemStack[] stacks) {}
}
