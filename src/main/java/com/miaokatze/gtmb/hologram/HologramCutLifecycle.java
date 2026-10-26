package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRedstoneComparator;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityComparator;
import net.minecraft.util.Direction;
import net.minecraft.world.World;

/** Restored native tiles have already been initialized by the raw loading path. */
final class HologramCutLifecycle {

    private HologramCutLifecycle() {}

    static void added(World world, Block block, int x, int y, int z, TileEntity restored) {
        if (block.getClass() == BlockRedstoneComparator.class && restored instanceof TileEntityComparator) {
            // BlockRedstoneComparator.onBlockAdded first does the diode output notification, then unconditionally
            // replaces its tile. Preserve the attached original while retaining that exact notification contract.
            int direction = world.getBlockMetadata(x, y, z) & 3;
            int nx = x - Direction.offsetX[direction], nz = z - Direction.offsetZ[direction];
            world.notifyBlockOfNeighborChange(nx, y, nz, block);
            world.notifyBlocksOfNeighborChange(nx, y, nz, block, Direction.directionToFacing[direction]);
        } else block.onBlockAdded(world, x, y, z);
    }
}
