// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtmb.hologram.HologramConstructionPerformanceProbe;

final class TestLabConstructionPerformanceTask implements TestLabTasks.Task {

    private final TestLabRuntime runtime;
    private HologramConstructionPerformanceProbe probe;

    TestLabConstructionPerformanceTask(TestLabRuntime runtime) {
        this.runtime = runtime;
    }

    public int step() throws Exception {
        if (probe == null) {
            TestLabZone zone = runtime.config().zones.get(0);
            runtime.useZone(zone);
            runtime.requireOwned(zone.x + 8, zone.y + 1, zone.z + 8, 5, 1, 4);
            TestLabTasks.require(
                runtime.actor().player instanceof FakePlayer,
                "Performance probe requires the owned fake actor");
            probe = new HologramConstructionPerformanceProbe(
                runtime.world(),
                (FakePlayer) runtime.actor().player,
                zone.x + 8,
                zone.y + 1,
                zone.z + 8);
        }
        if (!probe.step()) return TestLabTasks.WAIT;
        runtime.evidence().data.setTag("constructionPerformance", probe.evidence());
        runtime.evidence()
            .check(
                "construction_performance_20_cells",
                "PASS",
                "Real tick slices, actual world and exact main-inventory debit; disk publication counters retained");
        return TestLabTasks.DONE;
    }

    public void cleanup() {
        if (probe != null) probe.cleanup();
    }
}
