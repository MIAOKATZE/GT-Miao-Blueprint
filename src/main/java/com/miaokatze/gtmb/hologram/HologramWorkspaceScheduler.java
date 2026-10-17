package com.miaokatze.gtmb.hologram;

/** Tick orchestration over connected server-owned sessions. Each workflow owns its continuation. */
final class HologramWorkspaceScheduler {

    private static int nextSession;
    private static boolean ticking;

    private HologramWorkspaceScheduler() {}

    static void tick() {
        if (ticking) return;
        ticking = true;
        long started = HologramRuntimeMetrics.start();
        try {
            java.util.List<HologramSession> sessions = new java.util.ArrayList<>();
            HologramWorkspace.forEachConnected(sessions::add);
            runRound(
                sessions,
                HologramWorkspaceScheduler::tickSession,
                HologramWorkspaceScheduler::continueSession,
                HologramWorkspaceScheduler::canContinue,
                System::nanoTime,
                HologramWorkBudget.tickNanos());
        } finally {
            ticking = false;
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.WORKSPACE_TICK, started);
        }
    }

    /** Clock/operation ports keep fairness independently testable without fake world transactions. */
    static <T> void runRound(java.util.List<T> sessions, java.util.function.Consumer<T> operation,
        java.util.function.LongSupplier clock, long budget) {
        runRound(sessions, operation, operation, session -> false, clock, budget);
    }

    static <T> void runRound(java.util.List<T> sessions, java.util.function.Consumer<T> operation,
        java.util.function.Consumer<T> continuation, java.util.function.Predicate<T> canContinue,
        java.util.function.LongSupplier clock, long budget) {
        if (sessions.isEmpty()) return;
        int start = Math.floorMod(nextSession, sessions.size()), visited = 0;
        long deadline = clock.getAsLong() + budget;
        HologramWorkBudget.begin(deadline, clock);
        try {
            while (visited < sessions.size() && (visited == 0 || clock.getAsLong() < deadline)) {
                T session = sessions.get((start + visited) % sessions.size());
                visited++;
                HologramWorkBudget.quota(clock.getAsLong() + HologramWorkBudget.SLICE_NANOS);
                operation.accept(session);
            }
            nextSession = (start + visited) % sessions.size();
            // Give every peer its first slice before sharing idle tick time among actual continuations.
            // Jobs and capture retries are intentionally absent from this pass.
            while (visited == sessions.size() && clock.getAsLong() < deadline) {
                int active = 0;
                for (T session : sessions) if (canContinue.test(session)) active++;
                if (active == 0) break;
                boolean progressed = false;
                for (int offset = 0; offset < sessions.size() && clock.getAsLong() < deadline; offset++) {
                    int index = (start + offset) % sessions.size();
                    T session = sessions.get(index);
                    if (!canContinue.test(session)) continue;
                    long before = clock.getAsLong();
                    long share = Math.max(1L, (deadline - before) / Math.max(1, active--));
                    HologramWorkBudget.quota(before + share);
                    continuation.accept(session);
                    progressed |= clock.getAsLong() > before;
                    nextSession = (index + 1) % sessions.size();
                }
                if (!progressed) break;
            }
        } finally {
            if (visited < sessions.size()) nextSession = (start + visited) % sessions.size();
            HologramWorkBudget.end();
        }
    }

    private static boolean canAdvanceImport(HologramArea area) {
        if (!area.importing || "declarations".equals(area.importPhase)) return false;
        return !"canonicalize".equals(area.importPhase) || area.preparation != null && area.preparation.ready();
    }

    private static boolean canContinue(HologramSession s) {
        if (HologramWorkspace.find(s.player.getUniqueID()) != s || s.capturePending
            || s.cancelled
            || !HologramSessionPolicy.valid(s)
            || s.libraryWorld != s.player.worldObj) return false;
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null && area.importing) return canAdvanceImport(area) || HologramSnapshotPreparation.canAdvance(s);
        return s.localPlanPending && s.localPlanError.isEmpty() || HologramSnapshotPreparation.canAdvance(s);
    }

    private static void continueSession(HologramSession s) {
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null && area.importing) {
            if (canAdvanceImport(area)) HologramAreaWorkflow.tickImport(s, area);
            else HologramSnapshotPreparation.tick(s);
        } else if (s.localPlanPending && s.localPlanError.isEmpty()) HologramDeferredPlanRefresh.tick(s);
        else HologramSnapshotPreparation.tick(s);
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
