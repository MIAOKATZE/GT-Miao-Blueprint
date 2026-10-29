package com.miaokatze.gtmb.hologram;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Runs the complete attachment suite without unrelated weak-owner leases in its exact byte baseline. */
public final class HologramLargeAttachmentTest {

    private HologramLargeAttachmentTest() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLargeAttachmentTest.class, args)) return;
        HologramResourceWorld.init();
        HologramLargeAttachmentRegression.run();
        System.out.println("Large attachment regression passed");
    }
}
