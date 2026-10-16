package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import gregtech.common.blocks.BlockFrameBox;

/** Inventory/material queries and accounting snapshots shared by planning and transactions. */
final class HologramMaterialAccess {

    private HologramMaterialAccess() {}

    static List<ItemStack> drops(Block block, World world, HologramCapture.Cell cell, int meta) {
        if (world.getTileEntity(cell.x, cell.y, cell.z) != null)
            return HologramRecovery.drops(world, cell.x, cell.y, cell.z);
        if (block instanceof BlockFrameBox && !block.hasTileEntity(meta))
            return Collections.singletonList(((BlockFrameBox) block).getStackForm(1, meta));
        return block.getDrops(world, cell.x, cell.y, cell.z, meta, 0);
    }

    static ItemStack[] copyInventory(EntityPlayerMP p) {
        ItemStack[] result = new ItemStack[p.inventory.mainInventory.length];
        for (int i = 0; i < result.length; i++)
            result[i] = p.inventory.mainInventory[i] == null ? null : p.inventory.mainInventory[i].copy();
        return result;
    }

    static void restoreInventory(EntityPlayerMP p, ItemStack[] saved) {
        for (int i = 0; i < saved.length; i++) {
            ItemStack current = p.inventory.mainInventory[i];
            if (i == p.inventory.currentItem && current != null
                && saved[i] != null
                && current.getItem() == saved[i].getItem()
                && current.hasTagCompound()
                && saved[i].hasTagCompound()
                && current.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID)
                    .equals(
                        saved[i].getTagCompound()
                            .getString(HologramSessionKeys.TOOL_ID))) {
                current.stackSize = saved[i].stackSize;
                current.setItemDamage(saved[i].getItemDamage());
                current.setTagCompound(
                    saved[i].hasTagCompound() ? (NBTTagCompound) saved[i].getTagCompound()
                        .copy() : null);
            } else if (i != p.inventory.currentItem)
                p.inventory.mainInventory[i] = saved[i] == null ? null : saved[i].copy();
        }
        p.inventory.markDirty();
    }

    static boolean canFit(EntityPlayerMP p, List<ItemStack> drops) {
        ItemStack[] simulation = copyInventory(p);
        for (ItemStack drop : drops) {
            int left = drop.stackSize;
            for (ItemStack slot : simulation) if (HologramValues.same(slot, drop)) {
                int n = Math
                    .min(left, Math.min(slot.getMaxStackSize(), p.inventory.getInventoryStackLimit()) - slot.stackSize);
                slot.stackSize += n;
                left -= n;
            }
            for (int i = 0; i < simulation.length && left > 0; i++) if (simulation[i] == null) {
                simulation[i] = drop.copy();
                simulation[i].stackSize = Math.min(left, drop.getMaxStackSize());
                left -= simulation[i].stackSize;
            }
            if (left > 0) return false;
        }
        return true;
    }

    static boolean fuzzyBlueprintShells(HologramSession s) {
        return s.virtual && HologramDeidentification.level(s.virtualPreset) > 0;
    }

    static boolean freeShell(HologramSession s, HologramCapture.Cell cell, ItemStack sample) {
        return s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && HologramGtswnPlacementMaterials.freeShell(cell.block, sample);
    }

    static boolean fuzzyBlueprintShells(HologramSession s, ItemStack target) {
        return fuzzyBlueprintShells(s) && target != null
            && !HologramRecovery.hasSeal(target)
            && HologramEssentia.allowsFuzzyShell(target);
    }

    static int shellCount(HologramSession s, ItemStack stack, int limit) {
        return fuzzyBlueprintShells(s, stack) ? materialSource(s).countFuzzy(stack, limit)
            : materialSource(s).count(stack, limit);
    }

    static int decorativeAvailability(HologramSession s, int current, ItemStack stack, int limit) {
        String key = HologramValues.stackKey(stack);
        long protectedAssets = s.pendingAssetBudget.getOrDefault(key, 0L)
            - s.cellAssetBudgets.getOrDefault(current, Collections.emptyMap())
                .getOrDefault(key, 0L);
        return (int) Math
            .max(0L, (long) materialSource(s).count(stack, Integer.MAX_VALUE) - Math.max(0L, protectedAssets));
    }

    static void commitAssetBudget(HologramSession s, int index) {
        Map<String, Long> costs = s.cellAssetBudgets.remove(index);
        if (costs == null) return;
        for (Map.Entry<String, Long> cost : costs.entrySet()) {
            long remaining = s.pendingAssetBudget.getOrDefault(cost.getKey(), 0L) - cost.getValue();
            if (remaining <= 0) s.pendingAssetBudget.remove(cost.getKey());
            else s.pendingAssetBudget.put(cost.getKey(), remaining);
        }
    }

    static void refreshMaterialChoices(HologramSession s) {
        s.materialChoices.clear();
        s.materialChoices.addAll(materialSource(s).catalog(96));
    }

    static HologramMaterials.Context materialSource(HologramSession s) {
        if (s.materialContext == null) s.materialContext = new HologramMaterials.Context(s.player, s.materialSettings);
        return s.materialContext;
    }
}
