package com.miaokatze.gtmb.testlab;

import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Discard only test-owned cells. Avoid block-break drops when removing the lab's own fixture inventories. */
final class TestLabFixtureWorld {

    private TestLabFixtureWorld() {}

    static void clear(WorldServer world, int x, int y, int z) {
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        TileEntity tile = world.getTileEntity(x, y, z);
        chunk.removeTileEntity(x & 15, y, z & 15);
        if (tile != null) world.loadedTileEntityList.remove(tile);
        ExtendedBlockStorage section = chunk.getBlockStorageArray()[y >> 4];
        if (section != null) {
            section.func_150818_a(x & 15, y & 15, z & 15, Blocks.air);
            section.setExtBlockMetadata(x & 15, y & 15, z & 15, 0);
        }
        chunk.setChunkModified();
    }

    static void finish(WorldServer world, TestLabZone zone) {
        for (int x = zone.x >> 4; x <= (zone.x + TestLabZone.SIZE - 1) >> 4; x++)
            for (int z = zone.z >> 4; z <= (zone.z + TestLabZone.SIZE - 1) >> 4; z++) {
                Chunk chunk = world.getChunkFromChunkCoords(x, z);
                chunk.generateSkylightMap();
                chunk.setChunkModified();
            }
    }
}
