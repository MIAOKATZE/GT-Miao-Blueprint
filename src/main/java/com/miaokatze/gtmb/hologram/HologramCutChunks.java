package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

/** Explicit loading for authorized cut transactions, including recovery far from every connected player. */
final class HologramCutChunks {

    private HologramCutChunks() {}

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Union of real radius-17 neighborhoods of touched chunks, including selected air. */
    static Set<Long> lightNeighbors(NBTTagList cells, int ox, int oz) {
        Set<Long> touched = new LinkedHashSet<>();
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            touched.add(key((ox + cell.getInteger("x")) >> 4, (oz + cell.getInteger("z")) >> 4));
        }
        return lightNeighbors(touched);
    }

    static void loadWithLightNeighbors(WorldServer world, NBTTagList cells, int ox, int oz) throws IOException {
        load(world, lightNeighbors(cells, ox, oz));
    }

    static void loadWithLightNeighbors(WorldServer world, BitSet mask, int ox, int oz, int sx, int sz)
        throws IOException {
        Set<Long> touched = new LinkedHashSet<>();
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1))
            touched.add(key((ox + i % sx) >> 4, (oz + i / sx % sz) >> 4));
        load(world, lightNeighbors(touched));
    }

    private static Set<Long> lightNeighbors(Set<Long> touched) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (long position : touched) {
            int x = ((int) (position >> 32)) << 4, z = ((int) position) << 4;
            // A newly allocated section relights untouched columns too, so include the whole touched chunk.
            int minX = Math.max(-30000000, x - 17) >> 4, minZ = Math.max(-30000000, z - 17) >> 4;
            int maxX = Math.min(29999999, x + 15 + 17) >> 4, maxZ = Math.min(29999999, z + 15 + 17) >> 4;
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) chunks.add(key(cx, cz));
        }
        return chunks;
    }

    static void loadWithLightNeighbors(WorldServer world, int x, int z, int sizeX, int sizeZ) throws IOException {
        int minX = Math.max(-30000000, x - 17), minZ = Math.max(-30000000, z - 17);
        int maxX = Math.min(29999999, x + sizeX - 1 + 17), maxZ = Math.min(29999999, z + sizeZ - 1 + 17);
        // Native light propagation refuses to run without its radius-17 neighborhood. Remote recovery has no
        // nearby player to load it later, so make this read neighborhood real before taking or restoring data.
        load(world, minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
    }

    static void load(WorldServer world, int x, int z, int sizeX, int sizeZ) throws IOException {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int cx = x >> 4; cx <= (x + sizeX - 1) >> 4; cx++)
            for (int cz = z >> 4; cz <= (z + sizeZ - 1) >> 4; cz++) chunks.add(key(cx, cz));
        load(world, chunks);
    }

    static void load(WorldServer world, Set<Long> chunks) throws IOException {
        Map<Chunk, Long> positions = new IdentityHashMap<>();
        for (long position : chunks) {
            int cx = (int) (position >> 32), cz = (int) position;
            // provideChunk/getChunkFromChunkCoords may return the shared EmptyChunk when remote loading
            // is disabled. It accepts writes while always reading as air, so it is never a transaction world.
            Chunk chunk = world.theChunkProviderServer.loadChunk(cx, cz);
            if (chunk == null || chunk instanceof EmptyChunk || chunk.xPosition != cx || chunk.zPosition != cz)
                throw new IOException("??????????????????");
            Long previous = positions.put(chunk, position);
            if (previous != null && previous.longValue() != position) throw new IOException("??????????????????");
        }
    }
}
