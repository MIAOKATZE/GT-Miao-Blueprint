package com.miaokatze.gtmb.hologram;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shared immutable cache ownership follows the actual last Java reference, with no cleanup thread. */
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

    private HologramRetainedCache() {}
}
