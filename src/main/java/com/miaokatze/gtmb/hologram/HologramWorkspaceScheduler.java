package com.miaokatze.gtmb.hologram;

/** Tick orchestration over connected server-owned sessions. Each workflow owns its continuation. */
final class HologramWorkspaceScheduler {

    private static int nextSession;

    private HologramWorkspaceScheduler() {}

    static void tick() {
        long started = HologramRuntimeMetrics.start();
        java.util.List<HologramSession> sessions = new java.util.ArrayList<>();
        HologramWorkspace.forEachConnected(sessions::add);
        try {
            runRound(
                sessions,
                HologramWorkspaceScheduler::tickSession,
                System::nanoTime,
                HologramWorkBudget.TICK_NANOS);
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.WORKSPACE_TICK, started);
        }
    }

    /** Clock/operation ports keep fairness independently testable without fake world transactions. */
    static <T> void runRound(java.util.List<T> sessions, java.util.function.Consumer<T> operation,
        java.util.function.LongSupplier clock, long budget) {
        if (sessions.isEmpty()) return;
        int start = Math.floorMod(nextSession, sessions.size()), visited = 0;
        long deadline = clock.getAsLong() + budget;
        HologramWorkBudget.begin(deadline);
        try {
            while (visited < sessions.size() && (visited == 0 || clock.getAsLong() < deadline)) {
                T session = sessions.get((start + visited) % sessions.size());
                visited++;
                operation.accept(session);
            }
        } finally {
            nextSession = (start + visited) % sessions.size();
            HologramWorkBudget.end();
        }
    }

    private static void tickSession(HologramSession s) {
        if (!HologramSessionPolicy.valid(s) || s.libraryWorld != s.player.worldObj) {
            HologramDeferredPlanRefresh.suspend(s);
            HologramProjectorWorkflow.libraryCloseUi(s);
            if (!HologramWorkspace.areaSessionOwned(s)) HologramWorkspace.clearAreaSelection(s);
        }
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null && area.importing && !HologramSessionPolicy.valid(s) && HologramWorkspace.areaSessionOwned(s))
            return;
        if (area != null && area.importing) {
            HologramAreaWorkflow.tickImport(s, area);
            // The published selection baseline is independent of the private staging import. Keep
            // its pending UI snapshot moving while preserving the scheduler's shared tick deadline.
            long snapshotDeadline = HologramWorkBudget.deadline(2_000_000L);
            if (HologramWorkspace.find(s.player.getUniqueID()) == s && area.importing
                && !s.capturePending
                && System.nanoTime() < snapshotDeadline) HologramSnapshotPreparation.tick(s);
            return;
        }
        if (s.capturePending) {
            HologramBuildPreparation.tickPending(s);
            return;
        }
        if (s.localPlanPending) {
            if (!s.localPlanError.isEmpty() && HologramSnapshotPreparation.tick(s)) return;
            HologramDeferredPlanRefresh.tick(s);
            return;
        }
        if (HologramSnapshotPreparation.tick(s)) return;
        HologramPlacementJobs.tickJob(s);
    }
}
