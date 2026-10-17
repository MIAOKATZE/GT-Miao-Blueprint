package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** Own transient tickets only. Reset/release never deletes region files or touches other dimension tickets. */
final class TestLabLease {

    private ForgeChunkManager.Ticket ticket;
    private final Set<ChunkCoordIntPair> chunks = new LinkedHashSet<>();
    private final WorldServer world;
    private final TestLabConfig config;
    private final TestLabOwnership ownership;
    private final String run;

    TestLabLease(WorldServer world, TestLabConfig config, TestLabOwnership ownership, String run) {
        this.world = world;
        this.config = config;
        this.ownership = ownership;
        this.run = run;
    }

    void acquire(TestLabZone... zones) throws IOException {
        release();
        ticket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        if (ticket == null) throw new IOException("Forge refused a bounded lab ticket");
        ticket.getModData()
            .setBoolean("gtmbTestLab", true);
        ticket.getModData()
            .setString("labOwner", ownership.owner.toString());
        ticket.getModData()
            .setString("run", run);
        int limit = ticket.getChunkListDepth() <= 0 ? config.maxChunks
            : Math.min(config.maxChunks, ticket.getChunkListDepth());
        try {
            for (TestLabZone zone : zones) for (int x = zone.x >> 4; x <= (zone.x + zone.sizeX - 1) >> 4; x++)
                for (int z = zone.z >> 4; z <= (zone.z + zone.sizeZ - 1) >> 4; z++) {
                    if (chunks.size() >= limit) throw new IOException("Lab chunk lease budget exceeded");
                    ChunkCoordIntPair point = new ChunkCoordIntPair(x, z);
                    chunks.add(point);
                    Chunk loaded = world.theChunkProviderServer.loadChunk(x, z);
                    if (loaded == null || loaded instanceof EmptyChunk || !loaded.isChunkLoaded)
                        throw new IOException("Lab fixture chunk did not load natively");
                    ForgeChunkManager.forceChunk(ticket, point);
                }
        } catch (RuntimeException | IOException failure) {
            release();
            throw failure;
        }
    }

    int size() {
        return chunks.size();
    }

    void release() {
        if (ticket != null) {
            for (ChunkCoordIntPair chunk : chunks) ForgeChunkManager.unforceChunk(ticket, chunk);
            ForgeChunkManager.releaseTicket(ticket);
            ticket = null;
        }
        for (ChunkCoordIntPair chunk : chunks)
            world.theChunkProviderServer.unloadChunksIfNotNearSpawn(chunk.chunkXPos, chunk.chunkZPos);
        chunks.clear();
    }
}
