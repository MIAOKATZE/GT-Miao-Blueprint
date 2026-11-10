package com.miaokatze.gtmb.testlab;

import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

/** Empty pre-populated chunks: no ores, decorators, structures, terrain events or creatures. */
final class TestLabChunkProvider implements IChunkProvider {

    private final World world;

    TestLabChunkProvider(World world) {
        this.world = world;
    }

    public Chunk provideChunk(int x, int z) {
        Chunk chunk = new Chunk(world, new Block[65536], new byte[65536], x, z);
        for (int bx = 0; bx < 16; bx++)
            for (int bz = 0; bz < 16; bz++) chunk.getBiomeGenForWorldCoords(bx, bz, world.getWorldChunkManager());
        chunk.isTerrainPopulated = true;
        chunk.generateSkylightMap();
        return chunk;
    }

    public Chunk loadChunk(int x, int z) {
        return provideChunk(x, z);
    }

    public boolean chunkExists(int x, int z) {
        return true;
    }

    public void populate(IChunkProvider provider, int x, int z) {}

    public boolean saveChunks(boolean all, IProgressUpdate progress) {
        return true;
    }

    public boolean unloadQueuedChunks() {
        return false;
    }

    public boolean canSave() {
        return true;
    }

    public String makeString() {
        return "GTMB owned testlab void canvas";
    }

    public List getPossibleCreatures(EnumCreatureType type, int x, int y, int z) {
        return Collections.emptyList();
    }

    public ChunkPosition func_147416_a(World ignored, String name, int x, int y, int z) {
        return null;
    }

    public int getLoadedChunkCount() {
        return 0;
    }

    public void recreateStructures(int x, int z) {}

    public void saveExtraData() {}
}
