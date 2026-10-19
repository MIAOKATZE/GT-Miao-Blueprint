package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

/** Approximate material albedo multiplied by the LittleTiles dye, shared by both local previews. */
final class HologramTextColors {

    private static final int[] WOOL = { 0xe9ecec, 0xf07613, 0xbd44b3, 0x3aafd9, 0xf8c627, 0x70b919, 0xed8dac, 0x3e4447,
        0x8e8e86, 0x158991, 0x792aac, 0x35399d, 0x724728, 0x546d1b, 0xa02722, 0x141519 };
    private static final int[] CLAY = { 0xd1b2a1, 0xa15325, 0x95576c, 0x716c89, 0xba8523, 0x677535, 0xa14e4e, 0x392a23,
        0x876b62, 0x575b5b, 0x764656, 0x4a3b5b, 0x4d3323, 0x4c532a, 0x8f3d2e, 0x251710 };

    private HologramTextColors() {}

    static int preview(String material, ItemStack stack, int dye) {
        int metadata = stack == null ? 0 : stack.getItemDamage();
        boolean woolenClay = com.miaokatze.gtmb.hologram.HologramPictureBox.WOOLEN_CLAY.equals(material);
        int base = woolenClay ? com.miaokatze.gtmb.hologram.HologramPictureBox.woolenClayColor(metadata)
            : "minecraft:wool".equals(material) ? WOOL[metadata & 15]
                : "minecraft:stained_hardened_clay".equals(material) ? CLAY[metadata & 15] : 0xffffff;
        if (stack != null && !woolenClay
            && !"minecraft:wool".equals(material)
            && !"minecraft:stained_hardened_clay".equals(material)) {
            Block block = Block.getBlockFromItem(stack.getItem());
            if (block != null) base = block.getRenderColor(metadata);
        }
        int red = ((base >> 16) & 255) * ((dye >> 16) & 255) / 255;
        int green = ((base >> 8) & 255) * ((dye >> 8) & 255) / 255;
        int blue = (base & 255) * (dye & 255) / 255;
        return red << 16 | green << 8 | blue;
    }
}
