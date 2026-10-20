package com.miaokatze.gtmb.api;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.hologram.HologramNativeDisplayRegistry;
import com.miaokatze.miaokatzecore.api.extension.ExtensionCatalog;

/** Versioned community registration catalog. Closing a handle removes only its own registration. */
public final class GTMBExtensions {

    public static final int CONTRACT_VERSION = 1;
    private static final ExtensionCatalog<Descriptor> ACTIVE = new ExtensionCatalog<>();

    private GTMBExtensions() {}

    public static final class Descriptor {

        public final String id, kind;
        public final int contractVersion = CONTRACT_VERSION;

        private Descriptor(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }
    }

    public interface Registration extends AutoCloseable {

        Descriptor descriptor();

        boolean active();

        @Override
        void close();
    }

    /** Immutable snapshot in registration order; IDs are unique across all public extension kinds. */
    public static synchronized List<Descriptor> descriptors() {
        return ACTIVE.snapshot();
    }

    /** Internal bridge shared by the versioned API modules; installation must return an exact removal action. */
    static synchronized Registration install(String id, String kind, java.util.function.Supplier<Runnable> install) {
        Descriptor descriptor = new Descriptor(id, kind);
        ExtensionCatalog.Handle<Descriptor> handle = ACTIVE.install(id, descriptor, install);
        return new Registration() {

            public Descriptor descriptor() {
                return handle.descriptor();
            }

            public boolean active() {
                return handle.active();
            }

            public void close() {
                synchronized (GTMBExtensions.class) {
                    handle.close();
                }
            }
        };
    }

    /** Common-side family opt-in. Predicates inspect type only, never live contents or mutable world state. */
    public static Registration nativeDisplay(String id, Predicate<TileEntity> family) {
        if (family == null) throw new IllegalArgumentException("family");
        return install(id, "native-display", () -> {
            Predicate<TileEntity> bridge = tile -> family.test(tile);
            HologramNativeDisplayRegistry.register(bridge);
            return () -> HologramNativeDisplayRegistry.unregister(bridge);
        });
    }
}
