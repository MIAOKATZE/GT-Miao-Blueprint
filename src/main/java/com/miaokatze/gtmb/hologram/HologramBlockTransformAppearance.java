package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.api.CutTransforms;

/** Exact world-space appearance fields in ArchitectureCraft 1.12.18 and EnderIO 2.10.48. */
final class HologramBlockTransformAppearance {

    private HologramBlockTransformAppearance() {}

    static void architectureConnections(NBTTagCompound tag, CutTransforms.Transform pose) {
        if (!tag.hasKey("Disconnected", 3)) return;
        int before = tag.getInteger("Disconnected"), after = before & ~63;
        for (int side = 0; side < 6; side++) if ((before & (1 << side)) != 0) after |= 1 << pose.direction(side);
        tag.setInteger("Disconnected", after);
    }

    static void enderIoFacade(NBTTagCompound tag, CutTransforms.Transform pose) {
        if (!tag.hasKey("facadeId", 8) || !tag.hasKey("facadeMeta", 3)) return;
        Block block = Block.getBlockFromName(tag.getString("facadeId"));
        if (!(block instanceof BlockRotatedPillar)) return;
        int metadata = tag.getInteger("facadeMeta"), axis = metadata & 12;
        if (axis == 12) return; // The native all-bark state has no preferred axis.
        int next = pose.direction(axis == 4 ? 5 : axis == 8 ? 3 : 1) >> 1;
        tag.setInteger("facadeMeta", (metadata & ~12) | (next == 2 ? 4 : next == 1 ? 8 : 0));
    }
}
