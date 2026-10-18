package com.miaokatze.gtmb.client.hologram;

/** Local presentation timing only; input observation never consumes an event or sends a command. */
final class HologramIdleHints {

    static final long DELAY_NANOS = 2_000_000_000L;
    private long lastActivity;
    private boolean pointerKnown;
    private int pointerX, pointerY;

    HologramIdleHints(long now) {
        lastActivity = now;
    }

    void activity(long now) {
        lastActivity = now;
    }

    void pointer(int x, int y, long now) {
        if (!pointerKnown || x != pointerX || y != pointerY) activity(now);
        pointerKnown = true;
        pointerX = x;
        pointerY = y;
    }

    boolean visible(long now) {
        return pointerKnown && now - lastActivity >= DELAY_NANOS;
    }
}
