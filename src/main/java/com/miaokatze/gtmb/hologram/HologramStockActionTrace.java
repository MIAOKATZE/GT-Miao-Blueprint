// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.concurrent.TimeUnit;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** Operation-level diagnostics only. Never log inventories, NBT, player/tool IDs, positions or individual cells. */
public final class HologramStockActionTrace {

    private HologramStockActionTrace() {}

    private static long windowStart = System.nanoTime();
    private static int count;

    public static void event(String phase, HologramStockNativeWire.Key key, String outcome) {
        synchronized (HologramStockActionTrace.class) {
            long now = System.nanoTime();
            if (now - windowStart >= TimeUnit.MINUTES.toNanos(1)) {
                count = 0;
                windowStart = now;
            }
            if (count >= 120) return;
            count++;
        }
        GTMiaoBlueprint.LOG.info(
            "GTMB stock action phase={} request={} seq={} outcome={}",
            phase,
            key == null ? "-" : key.request(),
            key == null ? 0 : key.sequence(),
            outcome);
    }
}
