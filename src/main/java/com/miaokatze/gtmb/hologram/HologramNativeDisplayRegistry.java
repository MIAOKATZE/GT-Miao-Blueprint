package com.miaokatze.gtmb.hologram;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import net.minecraft.tileentity.TileEntity;

/** Common-side extension for native preview snapshots. Register a family during mod initialization. */
public final class HologramNativeDisplayRegistry {

    private static final List<Predicate<TileEntity>> FAMILIES = new CopyOnWriteArrayList<>();

    private HologramNativeDisplayRegistry() {}

    /** The predicate must only inspect the tile's type; its serializer supplies the detached NBT. */
    public static void register(Predicate<TileEntity> family) {
        if (family == null) throw new IllegalArgumentException("native display family is required");
        FAMILIES.add(family);
    }

    static boolean supports(TileEntity tile) {
        for (Predicate<TileEntity> family : FAMILIES) if (family.test(tile)) return true;
        return false;
    }
}
