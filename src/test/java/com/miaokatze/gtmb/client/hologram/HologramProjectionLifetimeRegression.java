package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramProjectionLifetimeRegression {

    private HologramProjectionLifetimeRegression() {}

    public static void run() {
        SimpleAssert.that(
            HologramVoucherRibbon.contains(10, 20, 220, 28, 225, 23),
            "painted cat/banner pixels own the voucher tooltip");
        SimpleAssert.that(
            !HologramVoucherRibbon.contains(10, 20, 220, 28, 12, 23)
                && !HologramVoucherRibbon.contains(10, 20, 220, 28, 230, 23)
                && !HologramVoucherRibbon.contains(10, 20, 220, 28, 225, 48),
            "row text, the next row and the outside edge never acquire the voucher tooltip");
        SimpleAssert.that(
            HologramVoucherRibbon.contains(10, 20, 220, 14, 227.5f, 21.5f),
            "smaller controls keep the same painted-pixel hit region after scaling");
        SimpleAssert.that(
            !HologramVoucherRibbon.contains(10, 20, 220, 28, 229, 20),
            "the open top-right corner retains the blueprint tooltip");
        HologramProjectionLifetime clock = new HologramProjectionLifetime();
        SimpleAssert.that(clock.visible("one", 1, 1), "active work remains visible");
        SimpleAssert.that(clock.visible("one", 3, 2), "completion starts its retention interval");
        SimpleAssert.that(clock.visible("one", 3, 14_000_000_002L), "completion remains visible before 15 seconds");
        SimpleAssert
            .that(!clock.visible("one", 3, 15_000_000_002L), "repeated completion snapshots cannot extend retention");
        SimpleAssert
            .that(clock.visible("two", 5, 16_000_000_002L), "new session has an independent partial-job deadline");
        SimpleAssert.that(!clock.visible("two", 4, 17_000_000_002L), "cancellation hides immediately");
        SimpleAssert.that(
            clock.visible("two", 4, 17_000_000_003L, true),
            "new local selection remains visible over a stopped projection");
        SimpleAssert.that(clock.visible("one", 5, 20_000_000_000L), "new partial job begins its own retention");
        SimpleAssert.that(!clock.visible("one", 5, 36_000_000_000L), "old partial projection expires");
        SimpleAssert.that(
            clock.visible("one", 5, 36_000_000_001L, true),
            "new selection remains visible after the prior completion deadline");
        SimpleAssert.that(
            !clock.visible("one", 5, 36_000_000_002L),
            "selection display never extends the old placement deadline");
    }
}
