package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.interfaces.IHeatingCoil;
import gregtech.common.blocks.BlockCasings5;

/** Small value operations shared across the engine domains. */
final class HologramValues {

    private HologramValues() {}

    static <T> void rekey(Map<String, T> values, Map<String, String> keys) {
        Map<String, T> result = new HashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            String key = keys.get(entry.getKey());
            if (key != null) result.put(key, entry.getValue());
        }
        values.clear();
        values.putAll(result);
    }

    static void rekey(java.util.Set<String> values, Map<String, String> keys) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String old : values) if (keys.containsKey(old)) result.add(keys.get(old));
        values.clear();
        values.addAll(result);
    }

    static String position(HologramCapture.Cell cell) {
        return cell.x + "," + cell.y + "," + cell.z;
    }

    static boolean coil(Block b, int meta) {
        return b == GregTechAPI.sBlockCasings5 && meta >= 0
            && meta < 16
            && BlockCasings5.getCoilHeatFromDamage(meta) != HeatingCoilLevel.None;
    }

    static String stackKey(ItemStack stack) {
        return stack.getItem() + ":" + stack.getItemDamage() + ":" + stack.getTagCompound();
    }

    static boolean same(ItemStack a, ItemStack b) {
        return a != null && b != null
            && a.getItem() == b.getItem()
            && a.getItemDamage() == b.getItemDamage()
            && ItemStack.areItemStackTagsEqual(a, b);
    }

    static boolean sameMaterial(ItemStack a, ItemStack b) {
        return a != null && b != null && a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    static boolean sameCoilLevel(ItemStack actual, ItemStack sample) {
        if (actual == null || sample == null) return false;
        Block installed = Block.getBlockFromItem(actual.getItem());
        Block option = Block.getBlockFromItem(sample.getItem());
        if (!(installed instanceof IHeatingCoil) || !(option instanceof IHeatingCoil)) return false;
        HeatingCoilLevel level = ((IHeatingCoil) installed).getCoilHeat(actual.getItemDamage());
        return level != HeatingCoilLevel.None && level == ((IHeatingCoil) option).getCoilHeat(sample.getItemDamage());
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
