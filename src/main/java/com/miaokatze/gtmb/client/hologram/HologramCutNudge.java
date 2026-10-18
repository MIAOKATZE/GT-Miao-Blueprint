package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/** A cut's locked anchor is local draft state; nudging it never sends a packet or changes raw source data. */
final class HologramCutNudge {

    private final HologramWorldFlow.KeyRepeat[] keys = { new HologramWorldFlow.KeyRepeat(),
        new HologramWorldFlow.KeyRepeat(), new HologramWorldFlow.KeyRepeat(), new HologramWorldFlow.KeyRepeat() };
    private long context = Long.MIN_VALUE;
    private boolean enabled;

    int[] sample(int[] anchor, boolean active, long currentContext, boolean[] pressed, boolean alt, double yaw,
        long tick, int height) {
        boolean changed = context != currentContext || enabled != active;
        context = currentContext;
        enabled = active;
        int fired = -1, count = 0;
        for (int i = 0; i < keys.length; i++) {
            boolean down = pressed[i];
            if (down) count++;
            if (keys[i].sample(down, active, changed, tick)) fired = i;
        }
        if (!active || anchor == null || fired < 0 || count != 1) return anchor;
        int[] offset = HologramWorldFlow.nudgeOffset(yaw, HologramCutInput.nudgeDirection(fired, alt));
        int[] next = { anchor[0] + offset[0], anchor[1] + offset[1], anchor[2] + offset[2] };
        return HologramWorldFlow.worldPosition(next[0], next[1], next[2], height) ? next : anchor;
    }
}
