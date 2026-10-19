package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramProjectionLifetimeRegression {

    private HologramProjectionLifetimeRegression() {}

    public static void run() {
        HologramProjectionLifetime clock = new HologramProjectionLifetime();
        SimpleAssert.that(clock.visible("one", 1, 1), "active work remains visible");
        SimpleAssert.that(clock.visible("one", 3, 2), "completion starts its retention interval");
        SimpleAssert.that(clock.visible("one", 3, 14_000_000_002L), "completion remains visible before 15 seconds");
        SimpleAssert
            .that(!clock.visible("one", 3, 15_000_000_002L), "repeated completion snapshots cannot extend retention");
        SimpleAssert
            .that(clock.visible("two", 5, 16_000_000_002L), "new session has an independent partial-job deadline");
        SimpleAssert.that(!clock.visible("two", 4, 17_000_000_002L), "cancellation hides immediately");
    }
}
