// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.MapStorage;

/** Loaded chunk backing for the independent large literal blueprint capture fixture. */
final class HologramLoadedFixtureWorld extends HologramServiceEditRegression.ReadOnlyWorld {

    static HologramLoadedFixtureWorld create(sun.misc.Unsafe unsafe, int x, int z) throws ReflectiveOperationException {
        var created = (HologramLoadedFixtureWorld) unsafe.allocateInstance(HologramLoadedFixtureWorld.class);
        setWorld(created, "provider", new WorldProviderSurface());
        setWorld(created, "rand", new java.util.Random(1));
        setWorld(created, "worldAccesses", new ArrayList<>());
        setWorld(created, "perWorldStorage", new MapStorage(null));
        created.capturedBlockSnapshots = new ArrayList<>();
        created.theChunkProviderServer = new ChunkProviderServer(created, null, null);
        setWorld(created, "chunkProvider", created.theChunkProviderServer);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var chunk = new Chunk(created, (x >> 4) + dx, (z >> 4) + dz);
            chunk.isChunkLoaded = true;
            created.theChunkProviderServer.loadedChunkHashMap
                .add(ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition), chunk);
        }
        return created;
    }

    private static void setWorld(World world, String name, Object value) throws ReflectiveOperationException {
        var field = World.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(world, value);
    }

    private Chunk loaded(int x, int z) {
        return (Chunk) theChunkProviderServer.loadedChunkHashMap
            .getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x >> 4, z >> 4));
    }

    @Override
    public boolean blockExists(int x, int y, int z) {
        return y >= 0 && y < 256 && loaded(x, z) != null;
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        return loaded(x, z).getBlock(x & 15, y, z & 15);
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        return loaded(x, z).getBlockMetadata(x & 15, y, z & 15);
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return loaded(x, z).chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
    }
}
