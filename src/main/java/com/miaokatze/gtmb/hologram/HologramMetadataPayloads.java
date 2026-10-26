package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** Closed, nonspatial payloads inspected in Forestry 4.11.39 and Railcraft 9.17.31 native writers. */
final class HologramMetadataPayloads {

    private HologramMetadataPayloads() {}

    /** Never creates a tile, invokes a native reader, or changes any accepted/rejected payload. */
    static boolean known(Block block, NBTTagCompound tile, NBTTagCompound item) {
        if (empty(tile) && empty(item)) return true;
        if (block == null || !empty(item) || empty(tile) || !tile.hasKey("id", 8)) return false;
        try {
            Class<?> registered = HologramCutTiles.registeredType(tile.getString("id"));
            return registered != null && knownRegistered(
                block.getClass()
                    .getName(),
                registered.getName(),
                tile,
                item);
        } catch (RuntimeException | LinkageError unavailableRegistry) {
            return false;
        }
    }

    /** The caller supplies the actual registered tile class, not a guessed class derived from the NBT ID. */
    static boolean knownRegistered(String blockType, String tileType, NBTTagCompound tile, NBTTagCompound item) {
        if (!empty(item) || empty(tile)
            || !tile.hasKey("id", 8)
            || tile.getString("id")
                .isEmpty())
            return false;
        int schema;
        if ("forestry.apiculture.blocks.BlockCandle".equals(blockType)
            && "forestry.apiculture.tiles.TileCandle".equals(tileType)) schema = 1;
        else if ("forestry.arboriculture.blocks.BlockArbStairs".equals(blockType)
            && "forestry.arboriculture.tiles.TileWood".equals(tileType)) schema = 2;
        else if ("mods.railcraft.common.blocks.aesthetics.stairs.BlockRailcraftStairs".equals(blockType)
            && "mods.railcraft.common.blocks.aesthetics.stairs.TileStair".equals(tileType)) schema = 3;
        // BlockStump and TC BlockCosmeticStairs have no native tile payload. Only the empty fast path applies.
        else return false;
        for (Object raw : tile.func_150296_c()) {
            String key = (String) raw;
            NBTBase value = tile.getTag(key);
            int type = value.getId();
            if ("id".equals(key) && type == 8) continue;
            if (("x".equals(key) || "y".equals(key) || "z".equals(key)) && type == 3) continue;
            if (schema == 1 && ("colour".equals(key) && type == 3 || "lit".equals(key) && type == 1)) continue;
            if (schema == 2 && "WT".equals(key) && type == 3) continue;
            if (schema == 3) {
                // TileStair still reads legacy byte materials; its current writer emits the enum name.
                if ("stair".equals(key) && (type == 8 || type == 1)) continue;
                if (("owner".equals(key) || "ownerId".equals(key)) && type == 8) continue;
                if ("uuid".equals(key) && type == 10 && uuid((NBTTagCompound) value)) continue;
            }
            return false;
        }
        return true;
    }

    private static boolean uuid(NBTTagCompound tag) {
        return tag.func_150296_c()
            .size() == 2 && tag.hasKey("most", 4)
            && tag.hasKey("least", 4);
    }

    private static boolean empty(NBTTagCompound tag) {
        return tag == null || tag.hasNoTags();
    }
}
