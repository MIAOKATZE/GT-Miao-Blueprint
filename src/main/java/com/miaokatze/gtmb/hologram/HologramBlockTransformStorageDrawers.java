package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.api.CutTransforms;

/** StorageDrawers 2.2.31 fixed NBT, shared by raw clipboard and blueprint rotations. */
final class HologramBlockTransformStorageDrawers {

    private static final int[][] VERTICAL_UP = { { 3, 5, 2, 4 }, { 2, 4, 3, 5 } };

    private HologramBlockTransformStorageDrawers() {}

    static boolean supports(Block block, NBTTagCompound tile) {
        if (tile == null) return false;
        String id = tile.getString("id");
        if ("StorageDrawers:tileDrawersStandard".equals(id) || "StorageDrawers:tileDrawersComp".equals(id)) return true;
        for (Class<?> type = block == null ? null : block.getClass(); type != null; type = type.getSuperclass())
            if ("com.jaquadro.minecraft.storagedrawers.block.BlockDrawers".equals(type.getName())) return true;
        return false;
    }

    static void apply(NBTTagCompound tile, CutTransforms.Transform pose) {
        if (!tile.hasKey("Dir", 1)) return;
        int side = tile.getByte("Dir");
        if (side < 0 || side >= 6) return;
        int next = pose.direction(side);
        tile.setByte("Dir", (byte) next);
        // Native horizontal fronts ignore Rot and always render upright. Vertical fronts encode four rolls.
        // A reflected face or an upside-down horizontal front cannot be represented without changing drawer slots.
        if (next >= 2) return;
        if (tile.hasKey("Rot") && !tile.hasKey("Rot", 1)) return;
        int wantedUp = pose.direction(up(side, tile.getByte("Rot")));
        for (int rotation = 0; rotation < 4; rotation++) if (up(next, rotation) == wantedUp) {
            // Native faceLocalU/V normalize any byte modulo four; retain its raw spelling when equivalent.
            if (tile.hasKey("Rot", 1) && Math.floorMod(tile.getByte("Rot"), 4) == rotation) return;
            if (rotation != 0 || tile.hasKey("Rot", 1)) tile.setByte("Rot", (byte) rotation);
            return;
        }
    }

    static int up(int side, int rotation) {
        // BlockDrawers.faceLocalV and TileEntityDrawersRenderer's Y-roll followed by X-tilt.
        return side >= 2 ? 1 : VERTICAL_UP[side][Math.floorMod(rotation, 4)];
    }
}
