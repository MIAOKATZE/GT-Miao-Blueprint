package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;
import java.util.Map;

/** Bounds outstanding ingress before main-thread scheduling, using connection object identity and no world access. */
final class HologramRequestGate {

    private final int perOwner, maximum;
    private final Map<Object, Integer> owners = new IdentityHashMap<>();
    private int outstanding;

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

    synchronized void clear() {
        owners.clear();
        outstanding = 0;
    }
}
