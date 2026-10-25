package com.miaokatze.gtmb.client.hologram;

/** Logical held-tool identity survives vanilla stack copies and server slot synchronization. */
final class HologramInputContext {

    private Object world, screen, item, stack;
    private int damage, slot = -1;
    private String uuid = "";
    private boolean focused;

    /** Focus is an input boundary; it does not change the logical tool or server selection. */
    boolean observeFocus(boolean nextFocused) {
        boolean changed = focused != nextFocused;
        focused = nextFocused;
        return changed;
    }

    static boolean selectionTool(String boundUuid, String heldUuid) {
        return boundUuid == null || boundUuid.isEmpty() || boundUuid.equals(heldUuid);
    }

    /** Controller ghosts belong to the tool/UI context; virtual placement and active jobs keep their own lifetime. */
    static boolean worldProjection(HologramState snapshot, boolean projectorHeld, boolean workspaceOpen) {
        int job = snapshot.data.getInteger("job");
        return snapshot.data.getBoolean("virtual") || projectorHeld || workspaceOpen || job == 1 || job == 2;
    }

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
