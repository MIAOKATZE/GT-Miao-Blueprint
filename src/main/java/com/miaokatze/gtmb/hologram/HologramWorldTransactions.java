// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.BlockSnapshot;

/** Read-only world facts for the native construction transaction. */
final class HologramWorldTransactions {

    private HologramWorldTransactions() {}

    static boolean sameWorldCell(World world, HologramCapture.Cell cell, Block block, int meta, TileEntity tile) {
        return world.blockExists(cell.x, cell.y, cell.z) && world.getBlock(cell.x, cell.y, cell.z) == block
            && world.getBlockMetadata(cell.x, cell.y, cell.z) == meta
            && world.getTileEntity(cell.x, cell.y, cell.z) == tile;
    }

    static BlockSnapshot readOnlyBlockSnapshot(World world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        NBTTagCompound data = null;
        if (tile != null) try {
            data = HologramNativeCapture.capture(tile);
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException(invalid);
        }
        return new BlockSnapshot(world, x, y, z, world.getBlock(x, y, z), world.getBlockMetadata(x, y, z), data);
    }
}
