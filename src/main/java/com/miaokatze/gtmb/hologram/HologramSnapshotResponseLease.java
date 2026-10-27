package com.miaokatze.gtmb.hologram;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Stop/clear cannot orphan a queued response lease, or release a producer's live allocation early. */
final class HologramSnapshotResponseLease implements AutoCloseable {

    private static final Set<HologramSnapshotResponseLease> LIVE = ConcurrentHashMap.newKeySet();
    private HologramProcessGovernor.Lease lease;
    private boolean running, retired;

    HologramSnapshotResponseLease(HologramProcessGovernor.Lease lease) {
        this.lease = lease.transfer();
        LIVE.add(this);
    }

    synchronized boolean start() {
        if (retired) return false;
        running = true;
        return true;
    }

    synchronized void finish() {
        running = false;
        if (retired) release();
    }

    synchronized boolean retired() {
        return retired;
    }

    public synchronized void close() {
        retired = true;
        if (!running) release();
    }

    private void release() {
        if (lease != null) lease.close();
        lease = null;
        LIVE.remove(this);
    }

    static void clear() {
        for (HologramSnapshotResponseLease lease : LIVE) lease.close();
    }
}
