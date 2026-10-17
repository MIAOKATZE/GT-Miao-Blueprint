package com.miaokatze.gtmb.testlab;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManagerHell;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

/** Inert canvas. No weather writes to WorldServerMulti's shared overworld WorldInfo. */
public final class TestLabWorldProvider extends WorldProvider {

    @Override
    protected void registerWorldChunkManager() {
        worldChunkMgr = new WorldChunkManagerHell(BiomeGenBase.plains, 0.5F);
        hasNoSky = true;
    }

    @Override
    public IChunkProvider createChunkGenerator() {
        return new TestLabChunkProvider(worldObj);
    }

    @Override
    public String getDimensionName() {
        return "GTMB Agent TestLab";
    }

    @Override
    public boolean canRespawnHere() {
        return false;
    }

    @Override
    public boolean isSurfaceWorld() {
        return false;
    }

    @Override
    public void updateWeather() {}

    @Override
    public boolean canDoRainSnowIce(Chunk chunk) {
        return false;
    }

    @Override
    public boolean canDoLightning(Chunk chunk) {
        return false;
    }

    @Override
    public ChunkCoordinates getEntrancePortalLocation() {
        TestLabZone zone = TestLab.configuredEntrance();
        return zone == null ? null : new ChunkCoordinates(zone.x + 8, zone.y + 1, zone.z + 8);
    }
}
