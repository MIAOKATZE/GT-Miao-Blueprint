// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

/** Exact old GTNH material aliases for detached LittleTiles template payloads. No global registry or save changes. */
public final class HologramLittleTilesMaterialAliases {

    private static final String HOST = "com.creativemd.littletiles.common.blocks.BlockTile";
    static final String OLD_CONCRETE = "VillageNames:concrete";
    static final String CURRENT_CONCRETE = "etfuturum:concrete";

    private HologramLittleTilesMaterialAliases() {}

    private static boolean applies(Block host, NBTTagCompound tag) {
        return host != null && host.getClass()
            .getName()
            .equals(HOST) && tag != null;
    }

    // VillageNames ModObjects.chooseModConcrete(int) prefers the Et Futurum substitute and returns unchanged dye meta.
    private static boolean concreteAliasAvailable() {
        return !registered(OLD_CONCRETE) && registered(CURRENT_CONCRETE);
    }

    private static boolean registered(String id) {
        Block block = Block.getBlockFromName(id);
        return block != null && block != Blocks.air && id.equals(Block.blockRegistry.getNameForObject(block));
    }

    /** The preview or normal placement adapter must own this detached copy, never pass a live or stored tag. */
    public static boolean applyTile(Block host, NBTTagCompound copy) {
        return applies(host, copy) && rewriteTile(copy, concreteAliasAvailable());
    }

    /** The saved quote and the placement sample must use the same physical LittleTiles cut identity. */
    static boolean applyItem(Block host, NBTTagCompound copy) {
        return applies(host, copy) && rewriteChild(copy, concreteAliasAvailable());
    }

    static boolean rewriteTile(NBTTagCompound copy, boolean concreteAliasAvailable) {
        if (!concreteAliasAvailable || !"LittleTilesTileEntity".equals(copy.getString("id"))
            || !copy.hasKey("tilesCount", 3)) return false;
        int count = copy.getInteger("tilesCount");
        if (count <= 0 || count > 65536) return false;
        boolean changed = false;
        for (int i = 0; i < count; i++) changed |= rewriteChild(copy.getCompoundTag("t" + i), true);
        return changed;
    }

    static boolean rewriteChild(NBTTagCompound child, boolean concreteAliasAvailable) {
        if (!concreteAliasAvailable || !OLD_CONCRETE.equals(child.getString("block"))
            || !child.hasKey("meta", 3)
            || child.getInteger("meta") < 0
            || child.getInteger("meta") > 15
            || child.hasKey("tileEntity")
            || child.hasKey("tileentity")) return false;
        String type = child.getString("tID");
        if (!"BlockTileBlock".equals(type) && !"BlockTileColored".equals(type)) return false;
        child.setString("block", CURRENT_CONCRETE);
        return true;
    }
}
