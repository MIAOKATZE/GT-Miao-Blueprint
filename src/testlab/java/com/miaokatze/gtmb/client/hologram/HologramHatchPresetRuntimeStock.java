// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;

import gregtech.api.enums.ItemList;

/** Finite real survival inventory fixture; original complete inventory is restored on the server. */
final class HologramHatchPresetRuntimeStock {

    private final NBTTagList original;
    private final int selected;
    private final Map<String, Object> report;
    private final List<Map<String, Object>> seeds = new ArrayList<>();

    HologramHatchPresetRuntimeStock(EntityPlayerMP actor, Map<String, Object> evidence) {
        if (!HologramHatchPresetRuntimeWitness.enabled() || !Boolean.getBoolean("miaotest.host.environment")
            || actor.capabilities.isCreativeMode) throw new IllegalStateException("Owned survival fixture required");
        original = (NBTTagList) actor.inventory.writeToNBT(new NBTTagList())
            .copy();
        selected = actor.inventory.currentItem;
        report = new LinkedHashMap<>();
        evidence.put("hatchPresetStock", report);
        report.put(
            "actor",
            actor.getUniqueID()
                .toString());
        report.put("originalInventory", original.toString());
        report.put("selectedSlot", selected);
        report.put("seeds", seeds);
        report.put("restored", false);
    }

    void seed(EntityPlayerMP actor) {
        for (String tier : new String[] { "LV", "MV" }) {
            for (String role : new String[] { "Energy", "Input", "Output", "Input_Bus", "Output_Bus", "Muffler" }) seed(
                actor,
                ItemList.valueOf("Hatch_" + role + "_" + tier)
                    .get(8),
                seeds);
        }
        seed(
            actor,
            ItemList.valueOf("Hatch_Maintenance")
                .get(8),
            seeds);
        report.put("seededStacks", seeds.size());
        report.put("restored", false);
    }

    private void seed(EntityPlayerMP actor, ItemStack item, List<Map<String, Object>> seeds) {
        if (item == null || item.stackSize != 8) throw new IllegalStateException("Missing registered fixture hatch");
        for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++) {
            if (slot == selected || actor.inventory.getStackInSlot(slot) != null) continue;
            actor.inventory.setInventorySlotContents(slot, item.copy());
            var row = new LinkedHashMap<String, Object>();
            row.put("slot", slot);
            row.put(
                "stack",
                item.writeToNBT(new net.minecraft.nbt.NBTTagCompound())
                    .toString());
            seeds.add(row);
            return;
        }
        throw new IllegalStateException("Owned fixture has insufficient empty inventory slots");
    }

    void restore(EntityPlayerMP actor) {
        actor.inventory.readFromNBT((NBTTagList) original.copy());
        actor.inventory.currentItem = selected;
        actor.inventoryContainer.detectAndSendChanges();
        boolean equal = selected == actor.inventory.currentItem
            && original.equals(actor.inventory.writeToNBT(new NBTTagList()));
        report.put("restored", equal);
        report.put(
            "restoredInventory",
            actor.inventory.writeToNBT(new NBTTagList())
                .toString());
        if (!equal) throw new IllegalStateException("Complete fixture inventory restoration failed");
    }
}
