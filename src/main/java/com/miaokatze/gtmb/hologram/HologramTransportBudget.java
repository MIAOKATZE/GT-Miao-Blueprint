package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared ordinary/cut data admission. Small progress/control messages retain their independent lane. */
final class HologramTransportBudget {

    private final int globalBytes, ownerBytes;
    private final long nanos;
    private final Map<UUID, Integer> owners = new HashMap<>();
    private int remaining;
    private long started;

    HologramTransportBudget(int globalBytes, int ownerBytes, long nanos) {
        if (globalBytes <= 0 || ownerBytes <= 0 || nanos <= 0) throw new IllegalArgumentException("transport budget");
        this.globalBytes = globalBytes;
        this.ownerBytes = ownerBytes;
        this.nanos = nanos;
    }

    void begin(long now) {
        remaining = globalBytes;
        started = now;
        owners.clear();
    }

    boolean tryAcquire(UUID owner, int bytes, long now) {
        int used = owners.getOrDefault(owner, 0);
        if (bytes <= 0 || bytes > remaining || bytes > ownerBytes - used || now - started >= nanos) return false;
        remaining -= bytes;
        owners.put(owner, used + bytes);
        return true;
    }
}
