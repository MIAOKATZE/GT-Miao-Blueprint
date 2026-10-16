package com.miaokatze.gtmb.hologram;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/** Relight only changed height intervals and newly allocated sections after the complete raw block replacement. */
final class HologramCutLighting {

    private final Map<Chunk, Before> chunks = new LinkedHashMap<>();

    void capture(Chunk chunk) {
        if (!chunks.containsKey(chunk)) chunks.put(chunk, new Before(chunk));
    }

    void sectionCreated(Chunk chunk, int section) {
        chunks.get(chunk).createdSections |= 1 << section;
    }

    void finish(WorldServer world) {
        // Native generation leaves an old height untouched when a column becomes entirely empty. Retain the
        // old heights separately, then start this rebuild at zero so removing the last block really exposes sky.
        for (Chunk chunk : chunks.keySet()) {
            Arrays.fill(chunk.heightMap, 0);
            chunk.generateSkylightMap();
            chunk.setChunkModified();
        }
        if (world.provider.hasNoSky) return;
        for (Map.Entry<Chunk, Before> entry : chunks.entrySet()) {
            Chunk chunk = entry.getKey();
            Before before = entry.getValue();
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int oldHeight = before.heights[z << 4 | x], height = chunk.heightMap[z << 4 | x];
                int low = 256, high = -1;
                int wx = (chunk.xPosition << 4) + x, wz = (chunk.zPosition << 4) + z;
                if (oldHeight != height) {
                    low = Math.max(0, Math.min(oldHeight, height) - 1);
                    high = Math.min(255, Math.max(oldHeight, height));
                    world.markBlocksDirtyVertical(wx, wz, low, high);
                }
                // A new section below an unchanged existing roof starts with zero SkyLight. Its untouched air
                // also needs lateral light; updating only selected opaque blocks cannot populate that air.
                for (int section = 0; section < 16; section++) {
                    if ((before.createdSections & (1 << section)) == 0) continue;
                    int bottom = section << 4, top = Math.min(bottom + 15, height - 1);
                    if (top < bottom) continue;
                    if (high < bottom || low > top) world.markBlocksDirtyVertical(wx, wz, bottom, top);
                    else {
                        if (bottom < low) world.markBlocksDirtyVertical(wx, wz, bottom, low - 1);
                        if (top > high) world.markBlocksDirtyVertical(wx, wz, high + 1, top);
                    }
                }
            }
        }
    }

    /** Native sky/block propagation can allocate storage and regenerate skylight again; normalize only afterward. */
    void complete() {
        for (Chunk chunk : chunks.keySet()) {
            int minimum = 256;
            for (int height : chunk.heightMap) minimum = Math.min(minimum, height);
            chunk.heightMapMinimum = minimum;
        }
    }

    private static final class Before {

        final int[] heights;
        int createdSections;

        Before(Chunk chunk) {
            heights = chunk.heightMap.clone();
        }
    }
}
