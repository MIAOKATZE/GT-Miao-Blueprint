package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

/** Main-resolved NBT values. Detached consumers can copy known values and can never resolve new registry identities. */
final class HologramSnapshotDataContext {

    private static final ThreadLocal<HologramSnapshotDataContext> ACTIVE = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final Map<String, NBTTagCompound> decoded = new HashMap<>();

    static boolean active() {
        return ACTIVE.get() != null;
    }

    static void enter(HologramSnapshotDataContext context) {
        ACTIVE.set(context);
    }

    static void exit() {
        ACTIVE.remove();
    }

    NBTTagCompound freeze(String encoded) throws IOException {
        if (Thread.currentThread() != owner || active())
            throw new IllegalStateException("NBT identity freezing requires its owner thread");
        if (encoded == null || encoded.isEmpty()) return null;
        NBTTagCompound tag = decoded.get(encoded);
        if (tag == null) {
            tag = HologramBlueprintNbt.decode(encoded);
            decoded.put(encoded, tag);
        }
        return tag;
    }

    static NBTTagCompound decode(String encoded) throws IOException {
        HologramSnapshotDataContext context = ACTIVE.get();
        NBTTagCompound tag = context == null ? null : context.decoded.get(encoded);
        if (tag == null) throw new IOException("Snapshot NBT identity was not frozen");
        return (NBTTagCompound) tag.copy();
    }
}
