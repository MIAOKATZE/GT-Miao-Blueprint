package com.miaokatze.gtmb.api;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.hologram.HologramNativeCapture;

/** Complete read-only persistence capture for reviewed native tiles and conduit schemas. */
public final class NativeCapture {

    private NativeCapture() {}

    public interface Adapter {

        boolean supports(TileEntity tile);

        NBTTagCompound capture(TileEntity tile) throws Exception;
    }

    public interface ConduitAdapter {

        boolean supports(Object conduit);

        NBTTagCompound capture(Object conduit) throws Exception;
    }

    public static GTMBExtensions.Registration register(String id, Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        return GTMBExtensions.install(id, "native-capture", () -> {
            HologramNativeCapture.Adapter bridge = new HologramNativeCapture.Adapter() {

                public boolean supports(TileEntity tile) {
                    return adapter.supports(tile);
                }

                public NBTTagCompound capture(TileEntity tile) throws Exception {
                    return adapter.capture(tile);
                }
            };
            HologramNativeCapture.register(bridge);
            return () -> HologramNativeCapture.unregister(bridge);
        });
    }

    public static GTMBExtensions.Registration registerConduit(String id, ConduitAdapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        return GTMBExtensions.install(id, "native-conduit-capture", () -> {
            HologramNativeCapture.ConduitAdapter bridge = new HologramNativeCapture.ConduitAdapter() {

                public boolean supports(Object conduit) {
                    return adapter.supports(conduit);
                }

                public NBTTagCompound capture(Object conduit) throws Exception {
                    return adapter.capture(conduit);
                }
            };
            HologramNativeCapture.registerConduit(bridge);
            return () -> HologramNativeCapture.unregisterConduit(bridge);
        });
    }
}
