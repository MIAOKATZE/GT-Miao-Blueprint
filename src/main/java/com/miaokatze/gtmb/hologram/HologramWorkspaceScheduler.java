package com.miaokatze.gtmb.hologram;

/** Tick orchestration over connected server-owned sessions. Each workflow owns its continuation. */
final class HologramWorkspaceScheduler {

    private HologramWorkspaceScheduler() {}

    static void tick() {
        HologramWorkspace.forEachConnected(HologramWorkspaceScheduler::tickSession);
    }

    private static void tickSession(HologramSession s) {
        if (!HologramSessionPolicy.valid(s) || s.libraryWorld != s.player.worldObj) {
            HologramProjectorWorkflow.libraryCloseUi(s);
            if (!HologramWorkspace.areaSessionOwned(s)) HologramWorkspace.clearAreaSelection(s);
        }
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null && area.importing && !HologramSessionPolicy.valid(s) && HologramWorkspace.areaSessionOwned(s))
            return;
        if (area != null && area.importing) {
            HologramAreaWorkflow.tickImport(s, area);
            return;
        }
        if (s.capturePending) {
            HologramBuildPreparation.tickPending(s);
            return;
        }
        HologramPlacementJobs.tickJob(s);
    }
}
