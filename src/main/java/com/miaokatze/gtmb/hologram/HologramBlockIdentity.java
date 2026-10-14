package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.common.blocks.ItemMachines;

/** Exact registered block and metadata identity; ordinary casings never become a family by appearance. */
public final class HologramBlockIdentity {

    private HologramBlockIdentity() {}

    /** A saved world state and its construction item can have different registered identities. */
    public static ItemStack materialStack(Block block, int metadata) {
        if (block == null || block == Blocks.air) return null;
        // Vanilla's state-only blocks consume the ordinary construction item.
        Block material = block == Blocks.lit_redstone_lamp ? Blocks.redstone_lamp
            : block == Blocks.lit_furnace ? Blocks.furnace
                : block == Blocks.unlit_redstone_torch ? Blocks.redstone_torch : block;
        Item item = Item.getItemFromBlock(material);
        return item == null ? null : new ItemStack(item, 1, metadata);
    }

    public static boolean same(Block first, int firstMeta, Block second, int secondMeta) {
        return first != null && second != null && firstMeta == secondMeta && first == second;
    }

    /** Vanilla lamp power is recomputed by native neighbor updates after placing the ordinary lamp item. */
    public static boolean sameForPlacement(Block first, int firstMeta, Block second, int secondMeta) {
        return same(first, firstMeta, second, secondMeta)
            || firstMeta == secondMeta && (first == Blocks.redstone_lamp || first == Blocks.lit_redstone_lamp)
                && (second == Blocks.redstone_lamp || second == Blocks.lit_redstone_lamp);
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
