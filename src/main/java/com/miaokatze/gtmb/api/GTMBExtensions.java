package com.miaokatze.gtmb.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.hologram.HologramNativeDisplayRegistry;

/** Versioned community registration catalog. Closing a handle removes only its own registration. */
public final class GTMBExtensions {

    public static final int CONTRACT_VERSION = 1;
    private static final Map<String, Descriptor> ACTIVE = new LinkedHashMap<>();

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
        return Collections.unmodifiableList(new ArrayList<>(ACTIVE.values()));
    }

    /** Internal bridge shared by the versioned API modules; installation must return an exact removal action. */
    static synchronized Registration install(String id, String kind, java.util.function.Supplier<Runnable> install) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Expected namespaced extension ID");
        if (ACTIVE.containsKey(id)) throw new IllegalArgumentException("Extension ID already registered: " + id);
        Runnable remove = install.get();
        Descriptor descriptor = new Descriptor(id, kind);
        ACTIVE.put(id, descriptor);
        return new Registration() {

            private boolean active = true;

            public Descriptor descriptor() {
                return descriptor;
            }

            public synchronized boolean active() {
                return active;
            }

            public void close() {
                synchronized (GTMBExtensions.class) {
                    synchronized (this) {
                        if (!active) return;
                        remove.run();
                        ACTIVE.remove(id, descriptor);
                        active = false;
                    }
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
