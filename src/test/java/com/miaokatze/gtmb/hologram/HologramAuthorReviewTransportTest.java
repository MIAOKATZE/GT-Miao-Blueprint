package com.miaokatze.gtmb.hologram;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Isolates exact review-ticket reservations from unrelated native fixtures' weak-owner retirement. */
public final class HologramAuthorReviewTransportTest {

    private HologramAuthorReviewTransportTest() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorReviewTransportTest.class, args)) return;
        HologramResourceWorld.init();
        HologramAuthorReviewTransportRegression.run();
        System.out.println("Author review transport regression passed");
    }
}
