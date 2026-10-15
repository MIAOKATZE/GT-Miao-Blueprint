package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/** Save-local provenance for private quantum anchors; never a replacement for native network permissions. */
public final class HologramQuantumWorldIdentity extends WorldSavedData {

    private static final String NAME = "gtmb_quantum_world_identity";
    private String identity;

    public HologramQuantumWorldIdentity(String name) {
        super(name);
    }

    static String get(World world) throws IOException {
        if (world == null || world.isRemote) throw new IOException("量子原网络存档身份不可用");
        WorldServer overworld = DimensionManager.getWorld(0);
        World storage = overworld == null ? world : overworld;
        HologramQuantumWorldIdentity saved = (HologramQuantumWorldIdentity) storage.perWorldStorage
            .loadData(HologramQuantumWorldIdentity.class, NAME);
        if (saved == null) {
            saved = new HologramQuantumWorldIdentity(NAME);
            saved.identity = UUID.randomUUID()
                .toString();
            storage.perWorldStorage.setData(NAME, saved);
            saved.markDirty();
        }
        try {
            if (!UUID.fromString(saved.identity)
                .toString()
                .equals(saved.identity)) throw new IllegalArgumentException("noncanonical UUID");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("量子原网络存档身份损坏，不能推断原绑定", invalid);
        }
        return saved.identity;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        identity = tag.getString("identity");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setString("identity", identity == null ? "" : identity);
    }
}
