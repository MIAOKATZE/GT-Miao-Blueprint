// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtmb.hologram.HologramCommunityVariantPlacementProbe;
import com.miaokatze.gtmb.hologram.HologramConstructionPerformanceProbe;
import com.miaokatze.gtmb.hologram.HologramNativeKernelConstructionProbe;

final class TestLabConstructionPerformanceTask implements TestLabTasks.Task {

    private final TestLabRuntime runtime;
    private HologramConstructionPerformanceProbe probe;
    private com.miaokatze.gtmb.hologram.HologramInstalledNativeTargetMatrix matrix;
    private boolean matrixComplete;
    private HologramNativeKernelConstructionProbe kernel;
    private boolean customComplete, kernelComplete;
    private HologramCommunityVariantPlacementProbe community;
    private boolean communityComplete;

    TestLabConstructionPerformanceTask(TestLabRuntime runtime) {
        this.runtime = runtime;
    }

    public int step() throws Exception {
        if (Boolean.getBoolean("gtmb.testlab.installedNativeTargetMatrix") && !matrixComplete) {
            if (matrix == null) matrix = new com.miaokatze.gtmb.hologram.HologramInstalledNativeTargetMatrix();
            if (!matrix.step()) return TestLabTasks.WAIT;
            runtime.evidence().data.setTag("installedNativeTargetMatrix", matrix.evidence());
            runtime.evidence()
                .check(
                    "installed_native_target_matrix",
                    "PASS",
                    "Installed four machines, LCR, GTNL and GTSR boundary combinations: exact native targets and footprints");
            matrixComplete = true;
            return TestLabTasks.WAIT;
        }
        if (Boolean.getBoolean("gtmb.testlab.nativeConstructionContracts") && !kernelComplete) {
            if (!customComplete) {
                runtime.evidence().data.setTag(
                    "customNativeConstruction",
                    com.miaokatze.gtmb.hologram.HologramCustomNativeConstructionSmoke.run());
                runtime.evidence()
                    .check(
                        "custom_native_without_visits",
                        "PASS",
                        "Detached custom native constructor: zero-result continuation, exact material binding and no definition/visits");
                customComplete = true;
            }
            TestLabZone zone = runtime.config().zones.get(0);
            runtime.useZone(zone);
            runtime.requireOwned(zone.x + 8, zone.y + 1, zone.z + 8, 6, 1, 4);
            TestLabTasks
                .require(runtime.actor().player instanceof FakePlayer, "Kernel contract requires the owned fake actor");
            if (kernel == null) kernel = new HologramNativeKernelConstructionProbe(
                runtime.world(),
                (FakePlayer) runtime.actor().player,
                zone.x + 8,
                zone.y + 1,
                zone.z + 8);
            if (!kernel.step()) return TestLabTasks.WAIT;
            kernel.cleanup();
            runtime.evidence().data.setTag("nativeKernelConstruction", kernel.evidence());
            runtime.evidence()
                .check(
                    "native_kernel_20_placed_one_cancelled",
                    "PASS",
                    "Kernel-only saved/place: 20 actual paid blocks, one final Forge event each, one cancelled debit/refund, clear journal and restored owned fixture; no workspace TaskGate/GUI/TCP claim");
            kernelComplete = true;
            return TestLabTasks.WAIT;
        }
        if (Boolean.getBoolean("gtmb.testlab.installedNativeTargetMatrix") && !communityComplete) {
            TestLabZone zone = runtime.config().zones.get(0);
            runtime.useZone(zone);
            runtime.requireOwned(zone.x + 8, zone.y + 1, zone.z + 8, 3, 3, 3);
            TestLabTasks.require(
                runtime.actor().player instanceof FakePlayer,
                "Community variant placement requires the owned fake actor");
            if (community == null) community = new HologramCommunityVariantPlacementProbe(
                runtime.world(),
                (FakePlayer) runtime.actor().player,
                zone.x + 8,
                zone.y + 1,
                zone.z + 8,
                matrix.variants());
            if (!community.step()) return TestLabTasks.WAIT;
            community.cleanup();
            runtime.evidence().data.setTag("communityNativeVariantPlacement", community.evidence());
            runtime.evidence()
                .check(
                    "installed_community_material_state_variants_placed",
                    "PASS",
                    "Actual GTNL/GTSR material/state variants: one real material debit and final Forge event each, native GT id/facing/texture/ownership, journal clear and owned fixture restored; not full machine/four operations/GUI/TCP acceptance");
            communityComplete = true;
            return TestLabTasks.WAIT;
        }
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
                "Legacy workspace compatibility path: real tick slices, world and exact main-inventory debit; event count observed separately from the shared native kernel");
        return TestLabTasks.DONE;
    }

    public void cleanup() {
        if (community != null) community.cleanup();
        if (kernel != null) kernel.cleanup();
        if (probe != null) probe.cleanup();
    }
}
