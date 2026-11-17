// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Small real area import for the explicitly owned integrated-server GUI regression. */
public final class HologramEditorIssueFixture {

    private ItemStack[] inventory;
    private ItemStack[] inventoryReference;
    private int selected;

    public void prepare(EntityPlayerMP actor) {
        if (!Boolean.getBoolean("gtmb.testlab.editorIssues") || !actor.mcServer.isSinglePlayer())
            throw new IllegalStateException("Explicit integrated GUI issue profile required");
        inventoryReference = actor.inventory.mainInventory;
        inventory = new ItemStack[inventoryReference.length];
        for (int i = 0; i < inventory.length; i++)
            inventory[i] = actor.inventory.mainInventory[i] == null ? null : actor.inventory.mainInventory[i].copy();
        selected = actor.inventory.currentItem;
        actor.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        actor.inventory.mainInventory[1] = new ItemStack(Blocks.cobblestone, 64);
        actor.inventory.mainInventory[2] = new ItemStack(Blocks.stone, 64);
        actor.inventory.currentItem = 0;
        actor.inventory.markDirty();
        actor.inventoryContainer.detectAndSendChanges();
        actor.playerNetServerHandler.setPlayerLocation(8.5, 81, 4.5, 0, 35);
        for (int x = 7; x <= 11; x++) for (int z = 3; z <= 9; z++) {
            if (!actor.worldObj.isAirBlock(x, 79, z)) throw new IllegalStateException("Owned GUI fixture not empty");
            actor.worldObj.setBlock(x, 79, z, Blocks.glass);
        }
        for (int x = 8; x <= 10; x++) actor.worldObj.setBlock(x, 80, 8, Blocks.stone);
        HologramService.beginAreaSelection(actor);
        HologramService.areaPoint(actor, 1, 8, 80, 8, false);
        HologramService.areaPoint(actor, 2, 10, 80, 8, false);
        HologramSession session = HologramWorkspace.find(actor.getUniqueID());
        NBTTagCompound finish = new NBTTagCompound();
        finish.setString("session", session.id);
        finish.setLong("planRevision", session.planRevision);
        finish.setLong("uiSequence", session.uiSequence + 1);
        HologramService.finishAreaSelection(actor, finish);
    }

    public NBTTagCompound observe(EntityPlayerMP actor) {
        NBTTagCompound result = new NBTTagCompound();
        HologramSession session = HologramWorkspace.find(actor.getUniqueID());
        if (session != null) {
            result.setString("session", session.id);
            result.setLong("revision", session.planRevision);
            result.setLong("uiSequence", session.uiSequence);
            result.setInteger("job", session.job);
            result.setString("status", session.status);
        }
        int source = 0;
        for (int x = 8; x <= 10; x++) if (actor.worldObj.getBlock(x, 80, 8) == Blocks.stone) source++;
        result.setInteger("sourceStoneCount", source);
        result.setTag("inventory", actor.inventory.writeToNBT(new NBTTagList()));
        return result;
    }

    public void restore(EntityPlayerMP actor) {
        HologramService.cancel(actor);
        if (inventory != null) {
            if (actor.inventory.mainInventory != inventoryReference)
                throw new IllegalStateException("Inventory array changed during owned GUI fixture");
            for (int i = 0; i < inventory.length; i++) actor.inventory.mainInventory[i] = inventory[i];
            actor.inventory.currentItem = selected;
            actor.inventory.markDirty();
            actor.inventoryContainer.detectAndSendChanges();
            inventory = null;
            inventoryReference = null;
        }
    }
}
