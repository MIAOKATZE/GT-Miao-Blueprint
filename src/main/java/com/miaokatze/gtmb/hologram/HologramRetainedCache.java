package com.miaokatze.gtmb.hologram;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Immutable cache ownership with phantom fallback and explicit scoped ownership for operation caches. */
final class HologramRetainedCache {

    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    private static final Map<PhantomReference<Object>, Owner> OWNERS = new HashMap<>();
    static {
        HologramProcessGovernor.registerRetiredReaper(HologramRetainedCache::drain);
    }

    static HologramProcessGovernor.Lease tryReserve(String name, long bytes) {
        drain();
        return HologramProcessGovernor.tryRetain(name, bytes);
    }

    static Owner watch(Object value) {
        Owner owner = new Owner();
        owner.reference = new PhantomReference<>(value, QUEUE);
        synchronized (OWNERS) {
            OWNERS.put(owner.reference, owner);
        }
        return owner;
    }

    static void drain() {
        long deadline = System.nanoTime() + 500_000L;
        for (int count = 0; count < 256 && System.nanoTime() < deadline; count++) {
            @SuppressWarnings("unchecked")
            PhantomReference<Object> reference = (PhantomReference<Object>) QUEUE.poll();
            if (reference == null) return;
            Owner owner;
            synchronized (OWNERS) {
                owner = OWNERS.remove(reference);
            }
            if (owner != null) owner.close();
        }
    }

    static final class Owner implements AutoCloseable {

        private PhantomReference<Object> reference;
        private final Set<HologramProcessGovernor.Lease> leases = new HashSet<>();
        private boolean closed;

        synchronized void add(HologramProcessGovernor.Lease lease) {
            if (closed) {
                lease.close();
                return;
            }
            leases.add(lease.transfer());
        }

        synchronized void release(HologramProcessGovernor.Lease lease) {
            if (leases.remove(lease)) lease.close();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (reference != null) {
                synchronized (OWNERS) {
                    OWNERS.remove(reference);
                }
                reference.clear();
            }
            for (HologramProcessGovernor.Lease lease : leases) lease.close();
            leases.clear();
        }
    }

    /** Explicit cache residency plus scoped readers; phantom ownership is only an abandoned-owner fallback. */
    static final class Value<T> implements AutoCloseable {

        private T value;
        private final Owner owner;
        private int readers;
        private boolean retired;

        Value(T value, HologramProcessGovernor.Lease lease) {
            this.value = java.util.Objects.requireNonNull(value);
            owner = watch(value);
            owner.add(lease);
        }

        synchronized Borrow<T> borrow() {
            if (retired) throw new IllegalStateException("Retired cache value");
            readers++;
            return new Borrow<>(value, this);
        }

        private synchronized void release() {
            if (--readers == 0 && retired) owner.close();
        }

        @Override
        public synchronized void close() {
            if (retired) return;
            retired = true;
            value = null;
            if (readers == 0) owner.close();
        }
    }

    /** A borrow pins the actual value as well as its charge until its last scoped use, even after eviction. */
    static final class Borrow<T> implements AutoCloseable {

        private T value;
        private Value<T> owner;
        private boolean closed;

        private Borrow(T value, Value<T> owner) {
            this.value = value;
            this.owner = owner;
        }

        static <T> Borrow<T> uncached(T value) {
            return new Borrow<>(value, null);
        }

        synchronized T value() {
            if (closed) throw new IllegalStateException("Closed cache borrow");
            return value;
        }

        synchronized boolean cached() {
            return owner != null;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            value = null;
            if (owner != null) owner.release();
            owner = null;
        }
    }

    private HologramRetainedCache() {}
}
