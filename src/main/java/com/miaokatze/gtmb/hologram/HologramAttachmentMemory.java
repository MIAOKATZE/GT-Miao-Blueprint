package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Allocation admission and ownership for detached attachment data, including the legacy synchronous API. */
final class HologramAttachmentMemory {

    private static final ThreadLocal<Owner> CURRENT = new ThreadLocal<>();
    private static final ReferenceQueue<Object> RETIRED = new ReferenceQueue<>();
    private static final Map<WeakReference<Object>, Retention> LEGACY = new HashMap<>();

    static {
        HologramProcessGovernor.registerRetiredReaper(HologramAttachmentMemory::reap);
    }

    private HologramAttachmentMemory() {}

    static HologramProcessGovernor.Lease reserve(String name, long bytes) throws IOException {
        HologramProcessGovernor.Lease lease = HologramProcessGovernor.tryRetain(name, bytes);
        if (lease == null) throw new IOException("附加对象内存预算已满，请降低尺寸或稍后重试");
        return lease;
    }

    static Scope enter(Owner owner) {
        return new Scope(owner);
    }

    static void retain(Object object, Retention retention, HologramProcessGovernor.Lease lease) throws IOException {
        retention.add(lease, CURRENT.get());
        synchronized (LEGACY) {
            if (retention.owner == null && !retention.registered) {
                LEGACY.put(new WeakReference<>(object, RETIRED), retention);
                retention.registered = true;
            }
        }
    }

    static void retainResult(long bytes) throws IOException {
        Owner owner = CURRENT.get();
        if (owner == null) throw new IllegalStateException("Attachment result has no owner");
        owner.add(reserve("attachment result", bytes));
    }

    static void export(Object object, Retention retention) {
        synchronized (LEGACY) {
            if (!retention.registered) {
                LEGACY.put(new WeakReference<>(object, RETIRED), retention);
                retention.registered = true;
            }
            retention.export();
        }
    }

    static void reap() {
        long deadline = System.nanoTime() + 500_000L;
        int work = 0;
        synchronized (LEGACY) {
            Object reference;
            while (work < 256 && (work == 0 || System.nanoTime() < deadline) && (reference = RETIRED.poll()) != null) {
                work++;
                Retention retention = LEGACY.remove(reference);
                if (retention != null) retention.close();
            }
        }
    }

    static final class Scope implements AutoCloseable {

        private final Owner previous;

        Scope(Owner owner) {
            previous = CURRENT.get();
            CURRENT.set(owner);
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static final class Owner implements AutoCloseable {

        private final Map<HologramProcessGovernor.Lease, Boolean> leases = new IdentityHashMap<>();
        private boolean closed;

        synchronized void add(HologramProcessGovernor.Lease lease) throws IOException {
            if (closed) {
                lease.close();
                throw new IOException("附加对象结果已释放");
            }
            try {
                leases.put(lease.transfer(), Boolean.TRUE);
            } catch (RuntimeException | Error failure) {
                lease.close();
                throw failure;
            }
        }

        synchronized void detach(HologramProcessGovernor.Lease lease) {
            leases.remove(lease);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            for (HologramProcessGovernor.Lease lease : leases.keySet()) lease.close();
            leases.clear();
        }
    }

    static final class Retention implements AutoCloseable {

        private final List<HologramProcessGovernor.Lease> leases = new ArrayList<>();
        private Owner owner;
        private boolean registered, closed;

        synchronized void add(HologramProcessGovernor.Lease lease, Owner supplied) throws IOException {
            if (closed) {
                lease.close();
                throw new IOException("附加对象结果已释放");
            }
            if (owner == null) owner = supplied;
            if (owner != null) owner.add(lease);
            try {
                leases.add(lease.transfer());
            } catch (RuntimeException | Error failure) {
                lease.close();
                throw failure;
            }
        }

        synchronized void export() {
            if (owner == null) return;
            for (HologramProcessGovernor.Lease lease : leases) owner.detach(lease);
            owner = null;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            for (HologramProcessGovernor.Lease lease : leases) lease.close();
            leases.clear();
        }
    }
}
