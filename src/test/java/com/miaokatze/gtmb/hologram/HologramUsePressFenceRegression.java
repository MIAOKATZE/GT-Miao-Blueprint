package com.miaokatze.gtmb.hologram;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Held use-item input never advances two world stages without an actual release. */
public final class HologramUsePressFenceRegression {

    private HologramUsePressFenceRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        HologramWorldFlow.UsePressFence fence = new HologramWorldFlow.UsePressFence();
        fence.observe(false, true);
        SimpleAssert.that(fence.consume(), "a fresh right press locks once");
        SimpleAssert.that(!fence.consume(), "block and air callbacks share the same physical press");
        for (int tick = 0; tick < 12; tick++) {
            fence.observe(true, false);
            SimpleAssert.that(!fence.consume(), "held use cannot reopen UI after the lock acknowledgment");
        }
        fence.observe(false, false);
        SimpleAssert.that(fence.consume(), "released use allows a second press to open review");
        fence.observe(true, true);
        SimpleAssert.that(!fence.consume(), "held input across a screen or tool transition remains consumed");
        fence.observe(false, true);
        SimpleAssert.that(fence.consume(), "actual release rearms after a context transition");
        for (String stage : new String[] { "area start", "area finish", "quick placement", "position unlock" }) {
            fence.observe(false, false);
            SimpleAssert.that(fence.consume(), stage + " accepts one fresh use press");
            fence.observe(true, false);
            SimpleAssert.that(!fence.consume(), stage + " cannot repeat from a held binding");
        }
        HologramWorldFlow.UsePressFence switched = new HologramWorldFlow.UsePressFence();
        switched.observe(true, true);
        SimpleAssert.that(!switched.consume(), "switching to the projector while use is held suppresses a gesture");
        switched.observe(false, false);
        SimpleAssert.that(switched.consume(), "release after switching permits a deliberate world gesture");
    }
}
