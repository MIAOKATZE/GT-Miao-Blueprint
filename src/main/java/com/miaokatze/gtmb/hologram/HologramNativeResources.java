package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

/** Exact installed-mod resource schemas whose native drops cannot pay their complete persisted payload. */
final class HologramNativeResources {

    private HologramNativeResources() {}

    static void inspect(NBTTagCompound compound, String name, String rootKey) throws IOException {
        if (name.equals("conduits") && compound.hasKey("conduitType", 8) && compound.hasKey("conduit", 10)) {
            String type = compound.getString("conduitType");
            try {
                ClassLoader loader = HologramNativeResources.class.getClassLoader();
                Class<?> base = Class.forName("crazypants.enderio.conduit.power.PowerConduit", false, loader);
                if (base.isAssignableFrom(Class.forName(type, false, loader))) {
                    NBTTagCompound body = compound.getCompoundTag("conduit");
                    if (body.hasKey("energyStoredRF")
                        && (!body.hasKey("energyStoredRF", 3) || body.getInteger("energyStoredRF") != 0))
                        throw unsupported(
                            rootKey,
                            "EnderIO energyStoredRF cannot be paid exactly through the EU budget");
                    if (body.hasKey("energyStored")
                        && (!body.hasKey("energyStored", 5) || body.getFloat("energyStored") != 0.0F))
                        throw unsupported(
                            rootKey,
                            "EnderIO legacy energyStored cannot be paid exactly through the EU budget");
                }
            } catch (ClassNotFoundException | LinkageError invalid) {
                throw new IOException("Unsupported native conduit resource schema: " + type, invalid);
            }
        }
    }

    private static IOException unsupported(String key, String detail) {
        return new IOException(
            "Unsupported retained resource " + key
                + ": "
                + detail
                + "; deselect this NBT root or empty the native buffer before capture");
    }
}
