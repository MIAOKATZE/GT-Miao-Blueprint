package com.miaokatze.gtmb.client.hologram;

/** Logical held-tool identity survives vanilla stack copies and server slot synchronization. */
final class HologramInputContext {

    private Object world, screen, item, stack;
    private int damage, slot = -1;
    private String uuid = "";

    boolean observe(Object nextWorld, Object nextScreen, Object nextItem, int nextDamage, int nextSlot, String nextUuid,
        Object nextStack, boolean screenEvent) {
        String identity = nextUuid == null ? "" : nextUuid;
        boolean sameTool = item == nextItem && damage == nextDamage
            && slot == nextSlot
            && (identity.isEmpty() ? uuid.isEmpty() && stack == nextStack : identity.equals(uuid));
        boolean changed = screenEvent || world != nextWorld || screen != nextScreen || !sameTool;
        world = nextWorld;
        screen = nextScreen;
        item = nextItem;
        damage = nextDamage;
        slot = nextSlot;
        uuid = identity;
        stack = nextStack;
        return changed;
    }
}
