package com.miaokatze.gtmb.client.hologram;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import com.miaokatze.gtmb.hologram.HologramProcessGovernor;

/** Fallback ownership for transferred buffers. Published view work leases close at publication instead. */
final class HologramSnapshotRetentions {

    private static final ReferenceQueue<Object> retired = new ReferenceQueue<>();
    private static final Map<Key, Resource> owners = new HashMap<>();
    private static final java.util.ArrayDeque<Resource> releases = new java.util.ArrayDeque<>();
    static {
        HologramProcessGovernor.registerRetiredReaper(HologramSnapshotRetentions::reap);
    }

    private HologramSnapshotRetentions() {}

    static synchronized void reap() {
        long deadline = System.nanoTime() + 500_000L;
        int work = 0;
        Key key;
        while (work < 256 && System.nanoTime() < deadline && (key = (Key) retired.poll()) != null) {
            release(owners.remove(key));
            work++;
        }
        while (work++ < 256 && System.nanoTime() < deadline && !releases.isEmpty()) {
            Resource resource = releases.removeFirst();
            if (--resource.references == 0) {
                resource.lease.close();
                release(resource.parent);
            }
        }
    }

    static synchronized void bind(Object state, Object parent, HologramProcessGovernor.Lease lease,
        boolean sharesBaseline) {
        reap();
        Resource inherited = sharesBaseline && parent != null ? owners.get(new Key(parent, null)) : null;
        while (inherited != null && inherited.displayOnly) inherited = inherited.parent;
        if (inherited != null) inherited.references++;
        Resource previous = owners.put(new Key(state, retired), new Resource(lease.transfer(), inherited, false));
        release(previous);
    }

    static synchronized void bindModelState(Object state, Object previous, HologramProcessGovernor.Lease headerLease) {
        reap();
        Resource inherited = previous == null ? null : owners.get(new Key(previous, null));
        while (inherited != null && inherited.displayOnly) inherited = inherited.parent;
        if (inherited != null) inherited.references++;
        release(owners.put(new Key(state, retired), new Resource(headerLease.transfer(), inherited, true)));
    }

    private static void release(Resource resource) {
        if (resource != null) releases.addLast(resource);
    }

    private static final class Resource {

        final HologramProcessGovernor.Lease lease;
        final Resource parent;
        final boolean displayOnly;
        int references = 1;

        Resource(HologramProcessGovernor.Lease lease, Resource parent, boolean displayOnly) {
            this.lease = lease;
            this.parent = parent;
            this.displayOnly = displayOnly;
        }
    }

    private static final class Key extends WeakReference<Object> {

        private final int hash;

        Key(Object state, ReferenceQueue<Object> queue) {
            super(state, queue);
            hash = System.identityHashCode(state);
        }

        public int hashCode() {
            return hash;
        }

        public boolean equals(Object other) {
            if (this == other) return true;
            Object state = get();
            return state != null && other instanceof Key && state == ((Key) other).get();
        }
    }
}
