package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

/** Explicit loading for authorized cut transactions, including recovery far from every connected player. */
final class HologramCutChunks {

    private HologramCutChunks() {}

    static void loadWithLightNeighbors(WorldServer world, int x, int z, int sizeX, int sizeZ) throws IOException {
        int minX = Math.max(-30000000, x - 17), minZ = Math.max(-30000000, z - 17);
        int maxX = Math.min(29999999, x + sizeX - 1 + 17), maxZ = Math.min(29999999, z + sizeZ - 1 + 17);
        // Native light propagation refuses to run without its radius-17 neighborhood. Remote recovery has no
        // nearby player to load it later, so make this read neighborhood real before taking or restoring data.
        load(world, minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
    }

    static void load(WorldServer world, int x, int z, int sizeX, int sizeZ) throws IOException {
        Map<Chunk, Long> positions = new IdentityHashMap<>();
        for (int cx = x >> 4; cx <= (x + sizeX - 1) >> 4; cx++)
            for (int cz = z >> 4; cz <= (z + sizeZ - 1) >> 4; cz++) {
                // provideChunk/getChunkFromChunkCoords may return the shared EmptyChunk when remote loading
                // is disabled. It accepts writes while always reading as air, so it is never a transaction world.
                Chunk chunk = world.theChunkProviderServer.loadChunk(cx, cz);
                if (chunk == null || chunk instanceof EmptyChunk || chunk.xPosition != cx || chunk.zPosition != cz)
                    throw new IOException("剪切区块未能完整加载，原件记录已保留");
                long position = ((long) cx << 32) ^ (cz & 0xffffffffL);
                Long previous = positions.put(chunk, position);
                if (previous != null && previous.longValue() != position) throw new IOException("剪切区块加载身份冲突，原件记录已保留");
            }
    }
}
