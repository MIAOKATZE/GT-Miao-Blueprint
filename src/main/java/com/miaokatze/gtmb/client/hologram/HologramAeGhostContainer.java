package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Client-only icon inventory. Never installed as EntityPlayer.openContainer or opened on the server. */
final class HologramAeGhostContainer extends Container {

    private final IInventory icons;

    HologramAeGhostContainer(List<HologramAePreviewModel.MarkSlot> slots) {
        windowId = -1;
        icons = new IconInventory(slots);
        for (int i = 0; i < slots.size(); i++) addSlotToContainer(new IconSlot(icons, i));
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
    public void detectAndSendChanges() {}

    @Override
    public void putStackInSlot(int slot, ItemStack stack) {}

    @Override
    public void putStacksInSlots(ItemStack[] stacks) {}

    private static final class IconSlot extends Slot {

        IconSlot(IInventory inventory, int index) {
            super(inventory, index, -10000, -10000);
        }

        @Override
        public boolean canTakeStack(EntityPlayer player) {
            return false;
        }

        @Override
        public boolean isItemValid(ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack decrStackSize(int count) {
            return null;
        }

        @Override
        public void putStack(ItemStack stack) {}

        @Override
        public void onPickupFromSlot(EntityPlayer player, ItemStack stack) {}
    }

    private static final class IconInventory implements IInventory {

        private final List<HologramAePreviewModel.MarkSlot> slots;

        IconInventory(List<HologramAePreviewModel.MarkSlot> slots) {
            this.slots = slots;
        }

        @Override
        public int getSizeInventory() {
            return slots.size();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot < 0 || slot >= slots.size() ? null
                : slots.get(slot)
                    .stack();
        }

        @Override
        public ItemStack decrStackSize(int slot, int count) {
            return null;
        }

        @Override
        public ItemStack getStackInSlotOnClosing(int slot) {
            return null;
        }

        @Override
        public void setInventorySlotContents(int slot, ItemStack stack) {}

        @Override
        public String getInventoryName() {
            return "GTMB AE blueprint markers";
        }

        @Override
        public boolean hasCustomInventoryName() {
            return false;
        }

        @Override
        public int getInventoryStackLimit() {
            return 64;
        }

        @Override
        public void markDirty() {}

        @Override
        public boolean isUseableByPlayer(EntityPlayer player) {
            return false;
        }

        @Override
        public void openInventory() {}

        @Override
        public void closeInventory() {}

        @Override
        public boolean isItemValidForSlot(int slot, ItemStack stack) {
            return false;
        }
    }
}
