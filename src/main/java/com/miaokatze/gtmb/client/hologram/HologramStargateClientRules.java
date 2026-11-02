package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** Connection-scoped server authority; no client configuration participates in this decision. */
final class HologramStargateClientRules {

    private Object connection;
    private boolean enabled, received, requested;

    void observeConnection(Object current) {
        if (connection == current) return;
        connection = current;
        enabled = received = requested = false;
    }

    boolean requestNeeded(Object current, boolean worldReady) {
        observeConnection(current);
        if (current == null || !worldReady || received || requested) return false;
        requested = true;
        return true;
    }

    void receive(Object current, NBTTagCompound state) {
        observeConnection(current);
        if (current == null || !state.hasKey("stargateRules", 1)) return;
        enabled = state.getBoolean("stargateRules");
        received = true;
    }

    boolean enabled(Object current) {
        observeConnection(current);
        return enabled;
    }
}
