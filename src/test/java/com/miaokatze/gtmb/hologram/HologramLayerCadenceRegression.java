// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

/** Pure scheduling checks; does not claim live world throughput or network-prefetch coverage. */
public final class HologramLayerCadenceRegression {

    private HologramLayerCadenceRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("layer_cadence: passed");
    }

    public static void run() {
        HologramLayerCadence cadence = new HologramLayerCadence(64);
        require(cadence.enter(64, 100), "first group starts immediately");
        cadence.settle(100);
        require(cadence.enter(69, 112), "sixth layer can finish on a later tick");
        cadence.settle(112);
        require(!cadence.enter(70, 112), "slow group still requires a gap after the last settlement");
        require(!cadence.enter(70, 121) && !cadence.enter(70, 121), "retries do not shorten the gap");
        require(cadence.enter(70, 122), "next group starts exactly ten ticks after settlement");
        cadence.settle(123);
        require(cadence.enter(100, 133), "empty height bands do not synthesize extra delays");
        boolean rejected = false;
        try { cadence.enter(99, 150); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "backward group is rejected");
        HologramLayerCadence negative = new HologramLayerCadence(-7);
        require(negative.enter(-7, 0) && negative.enter(-2, 0), "height bands allow negative origins");
        negative.settle(0);
        require(!negative.enter(-1, 9) && negative.enter(-1, 10), "height arithmetic is relative");
        negative.settle(10);
        require(!negative.enter(5, 1) && !negative.enter(5, 10) && negative.enter(5, 11),
            "clock rollback neither grants early start nor strands future work");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
