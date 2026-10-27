package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramProcessGovernor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/** Client-owner exact model domain. Cache and active page views own independent references. */
final class HologramSnapshotModelCache {

    static final long MAX_BYTES = 32L << 20;
    private final Map<Long, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long bytes;

    boolean put(long id, NBTTagCompound actual, NBTTagCompound target) {
        Entry old = entries.get(id);
        View borrowed = old == null ? null : old.borrow(3);
        try {
            NBTTagCompound a = actual != null ? actual : old == null ? null : old.actual;
            NBTTagCompound t = target != null ? target : old == null ? null : old.target;
            long cost = estimate(a, t);
            if (cost > MAX_BYTES) {
                // The requested side still fits even when retaining both views would exceed the cache.
                a = actual;
                t = target;
                cost = estimate(a, t);
            }
            if (cost > MAX_BYTES) return false;
            while (!entries.isEmpty() && (entries.size() >= 4096 || bytes + cost > MAX_BYTES)) {
                Long first = entries.keySet()
                    .iterator()
                    .next();
                entries.remove(first)
                    .release();
            }
            if (bytes + cost > MAX_BYTES) return false;
            HologramProcessGovernor.Lease lease = HologramProcessGovernor.tryRetain("snapshot.modelCache", cost);
            if (lease == null) return false;
            try {
                Entry next = new Entry(id, a, t, lease.transfer(), cost);
                Entry previous = entries.put(id, next);
                bytes += cost;
                if (previous != null) previous.release();
                return true;
            } catch (RuntimeException | Error failure) {
                lease.close();
                throw failure;
            }
        } finally {
            if (borrowed != null) borrowed.close();
        }
    }

    View get(long id, int mask) {
        Entry entry = entries.get(id);
        return entry == null || (entry.mask() & mask) == 0 ? null : entry.borrow(mask);
    }

    void clear() {
        for (Entry entry : entries.values()) entry.release();
        entries.clear();
    }

    static long estimate(NBTTagCompound actual, NBTTagCompound target) {
        return 1024L + (actual == null ? 0 : HologramSnapshotCodec.retainedEstimate(actual))
            + (target == null ? 0 : HologramSnapshotCodec.retainedEstimate(target));
    }

    final class Entry {

        final long id, cost;
        NBTTagCompound actual, target;
        HologramProcessGovernor.Lease lease;
        int references = 1;

        Entry(long id, NBTTagCompound actual, NBTTagCompound target, HologramProcessGovernor.Lease lease, long cost) {
            this.id = id;
            this.actual = actual;
            this.target = target;
            this.lease = lease;
            this.cost = cost;
        }

        int mask() {
            return (actual == null ? 0 : 1) | (target == null ? 0 : 2);
        }

        View borrow(int mask) {
            View view = new View(this, mask);
            references++;
            return view;
        }

        void release() {
            if (--references != 0) return;
            bytes -= cost;
            actual = target = null;
            lease.close();
            lease = null;
        }
    }

    static final class View implements AutoCloseable {

        private Entry entry;
        final int mask;

        View(Entry entry, int mask) {
            this.entry = entry;
            this.mask = entry.mask() & mask;
        }

        NBTTagCompound actual() {
            return (mask & 1) == 0 ? null : entry.actual;
        }

        NBTTagCompound target() {
            return (mask & 2) == 0 ? null : entry.target;
        }

        @Override
        public void close() {
            Entry release = entry;
            entry = null;
            if (release != null) release.release();
        }
    }
}
