package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.common.blocks.ItemMachines;

/** Exact registered block and metadata identity; ordinary casings never become a family by appearance. */
public final class HologramBlockIdentity {

    private HologramBlockIdentity() {}

    public static boolean same(Block first, int firstMeta, Block second, int secondMeta) {
        return first != null && second != null && firstMeta == secondMeta && first == second;
    }

    public static String key(Block block, int metadata) {
        Object registry = block == null ? null : Block.blockRegistry.getNameForObject(block);
        return registry == null ? "" : registry.toString() + "@" + metadata;
    }

    public static String key(ItemStack stack) {
        return stack == null || stack.getItem() == null ? ""
            : key(
                Block.getBlockFromItem(stack.getItem()),
                stack.getItem() instanceof ItemMachines ? stack.getItemDamage()
                    : stack.getItem()
                        .getMetadata(stack.getItemDamage()));
    }

    /** UNKNOWN is a renderer sentinel, never a saved or planned six-face orientation. */
    public static int front(int facing) {
        return facing >= 0 && facing < 6 ? facing : -1;
    }

    public static int front(IGregTechTileEntity tile) {
        if (tile == null || tile.getMetaTileEntity() instanceof MetaPipeEntity || tile.getFrontFacing() == null)
            return -1;
        return front(
            tile.getFrontFacing()
                .ordinal());
    }
}
