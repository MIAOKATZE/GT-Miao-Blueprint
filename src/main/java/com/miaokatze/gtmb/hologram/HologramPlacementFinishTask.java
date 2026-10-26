package com.miaokatze.gtmb.hologram;

/** Bounded terminal accounting and controller notifications; the job stays running until both finish. */
final class HologramPlacementFinishTask {

    private final HologramCapture capture;
    private final long revision;
    private int stage, cursor, unresolved;

    HologramPlacementFinishTask(HologramSession s) {
        capture = s.capture;
        revision = s.planRevision;
    }

    boolean current(HologramSession s) {
        return capture == s.capture && revision == s.planRevision;
    }

    String phase() {
        return stage == 0 ? "build.controllers" : "build.account";
    }

    long completed() {
        return cursor;
    }

    long total(HologramSession s) {
        return stage == 0 ? s.work.size() : capture.cells.size();
    }

    boolean step(HologramSession s, int maxWork, long maxNanos) {
        if (!current(s)) throw new IllegalStateException("Obsolete placement accounting");
        HologramPlanBudget budget = new HologramPlanBudget(maxWork, maxNanos);
        while (stage < 2 && budget.take()) {
            if (!current(s)) return false;
            if (stage == 0) {
                if (cursor < s.work.size()) HologramPlacementJobs.notifyCompletedController(s, s.work.get(cursor++));
                else {
                    cursor = 0;
                    stage = 1;
                }
            } else if (cursor < capture.cells.size()) {
                int index = cursor++;
                HologramCapture.Cell cell = capture.cells.get(index);
                if (!cell.anchor && HologramSessionPolicy.inScope(s, cell, index) && !resolved(cell.status))
                    unresolved++;
            } else stage = 2;
        }
        return stage == 2;
    }

    static boolean resolved(String status) {
        return "satisfied".equals(status) || "placed".equals(status)
            || "replaced".equals(status)
            || "removed".equals(status);
    }

    void publish(HologramSession s) {
        if (stage != 2 || !current(s)) throw new IllegalStateException("Incomplete placement accounting");
        s.job = unresolved == 0 ? 3 : 5;
        s.phase = "ACK";
        s.status = s.jobPartialPlacement
            ? "部分放置结束：已放置 " + s.completed
                + " 格；缺料跳过 "
                + s.jobMissingCells
                + " 格；保护或占位跳过 "
                + s.jobProtectedCells
                + " 格；不支持跳过 "
                + s.jobUnsupportedCells
                + " 格"
            : unresolved == 0 ? "范围内施工完成：已完成 " + s.completed + " 格（未执行机器成型检查）"
                : "部分完成：已完成 " + s.completed
                    + " 格；缺料跳过 "
                    + s.jobMissingCells
                    + " 格；其他未完成 "
                    + (unresolved - s.jobMissingCells)
                    + " 格";
    }
}
