package com.miaokatze.gtmb.testlab;

import net.minecraftforge.common.DimensionManager;

/** Client connection owns only a mapping it actually registered; no provider/world/server ownership changes. */
final class TestLabClientMapping {

    private Object connection;
    private int dimension, provider;
    private boolean owned;

    synchronized boolean connected(Object next, boolean local, boolean enabled, boolean ready, boolean serverOwned,
        int dimension, int provider) {
        if (next == null || local || !enabled || !ready || serverOwned) return false;
        if (owned && this.dimension == dimension
            && this.provider == provider
            && DimensionManager.isDimensionRegistered(dimension)
            && DimensionManager.getProviderType(dimension) == provider) {
            connection = next;
            return true;
        }
        if (DimensionManager.isDimensionRegistered(dimension)) return false;
        DimensionManager.registerDimension(dimension, provider);
        this.connection = next;
        this.dimension = dimension;
        this.provider = provider;
        owned = true;
        return true;
    }

    synchronized void disconnected(Object previous, boolean serverOwned) {
        if (!owned || connection != previous) return;
        if (!serverOwned && DimensionManager.isDimensionRegistered(dimension)
            && DimensionManager.getProviderType(dimension) == provider) DimensionManager.unregisterDimension(dimension);
        connection = null;
        owned = false;
    }

    synchronized void beforeServerWorldsLoad(boolean serverOwned) {
        if (owned) disconnected(connection, serverOwned);
    }

    synchronized boolean owned() {
        return owned;
    }
}
