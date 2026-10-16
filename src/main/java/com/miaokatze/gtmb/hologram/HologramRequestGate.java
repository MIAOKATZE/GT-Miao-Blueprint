package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;
import java.util.Map;

/** Bounds outstanding ingress before main-thread scheduling, using connection object identity and no world access. */
final class HologramRequestGate {

    private final int perOwner, maximum;
    private final Map<Object, Integer> owners = new IdentityHashMap<>();
    private int outstanding;
    private long generation;

    HologramRequestGate(int perOwner, int maximum) {
        this.perOwner = perOwner;
        this.maximum = maximum;
    }

    synchronized boolean enter(Object owner) {
        int count = owners.getOrDefault(owner, 0);
        if (count >= perOwner || outstanding >= maximum) return false;
        owners.put(owner, count + 1);
        outstanding++;
        return true;
    }

    synchronized void leave(Object owner) {
        Integer count = owners.get(owner);
        if (count == null) return;
        if (count == 1) owners.remove(owner);
        else owners.put(owner, count - 1);
        outstanding--;
    }

    synchronized int size() {
        return outstanding;
    }

    synchronized Lease acquire(Object owner) {
        return enter(owner) ? new Lease(this, owner, generation) : null;
    }

    static final class Lease implements AutoCloseable {

        private final HologramRequestGate gate;
        private final Object owner;
        private final long generation;
        private boolean closed;

        private Lease(HologramRequestGate gate, Object owner, long generation) {
            this.gate = gate;
            this.owner = owner;
            this.generation = generation;
        }

        boolean active() {
            synchronized (gate) {
                return !closed && generation == gate.generation;
            }
        }

        @Override
        public void close() {
            synchronized (gate) {
                if (closed) return;
                closed = true;
                if (generation == gate.generation) gate.leave(owner);
            }
        }
    }

    synchronized void clear() {
        generation++;
        owners.clear();
        outstanding = 0;
    }
}
