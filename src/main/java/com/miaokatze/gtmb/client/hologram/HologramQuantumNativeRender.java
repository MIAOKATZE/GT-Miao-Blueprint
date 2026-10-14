package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/** Optional GTSWN native renderer, hydrated with visual fields only; no anchor, proxy or ownership reader. */
final class HologramQuantumNativeRender implements HologramNativeRenderRegistry.Adapter {

    static final HologramQuantumNativeRender INSTANCE = new HologramQuantumNativeRender();
    static final String BLOCK = "com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode";
    static final String TILE = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";

    @Override
    public boolean supports(Block block) {
        return block != null && BLOCK.equals(
            block.getClass()
                .getName());
    }

    @Override
    public void loadTile(TileEntity tile, NBTTagCompound payload) {
        try {
            if (!TILE.equals(
                tile.getClass()
                    .getName()))
                throw new IllegalArgumentException("Unknown quantum tile");
            int color = payload.getInteger("color");
            field(tile, "colorIndex").setInt(tile, color >= 0 && color < 16 ? color : 0);
            field(tile, "clientLinked").setBoolean(tile, payload.getBoolean("linked"));
            field(tile, "clientConnectedSides").setInt(tile, payload.getInteger("gtmb.quantumSides") & 63);
            if (payload.hasKey("partContainer", 10))
                HologramAeNativeRender.loadCable(tile, payload.getCompoundTag("partContainer"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("GTSWN visual API is unavailable", failure);
        }
    }

    private static Field field(Object tile, String name) throws ReflectiveOperationException {
        Field field = tile.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
