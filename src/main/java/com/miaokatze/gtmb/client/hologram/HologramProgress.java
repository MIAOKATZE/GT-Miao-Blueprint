package com.miaokatze.gtmb.client.hologram;

/** Immutable observations: an incomplete phase never reports100, and unknown work never invents a total. */
final class HologramProgress {

    final String operation, phase, unit;
    final long done, total, startedAt, observedAt;
    final boolean complete;

    private HologramProgress(String operation, String phase, long done, long total, String unit, boolean complete,
        long startedAt, long now) {
        this.operation = operation;
        this.phase = phase;
        this.done = Math.max(0, done);
        this.total = Math.max(0, total);
        this.unit = unit;
        this.complete = complete;
        this.startedAt = startedAt;
        this.observedAt = now;
    }

    int percent() {
        if (complete && (total == 0 || done >= total)) return 100;
        if (total == 0) return -1;
        long bounded = Math.min(done, total);
        int percent = bounded <= Long.MAX_VALUE / 100 ? (int) (bounded * 100 / total)
            : java.math.BigInteger.valueOf(bounded)
                .multiply(java.math.BigInteger.valueOf(100))
                .divide(java.math.BigInteger.valueOf(total))
                .intValue();
        return Math.min(99, percent);
    }

    long elapsedSeconds(long now) {
        return Math.max(0, (complete ? observedAt : now) - startedAt) / 1000000000L;
    }

    static final class Tracker {

        private HologramProgress current;

        synchronized HologramProgress update(String operation, String phase, long done, long total, String unit,
            boolean complete, long now) {
            long start = current == null || !current.operation.equals(operation) || current.complete && !complete ? now
                : current.startedAt;
            current = new HologramProgress(operation, phase, done, total, unit, complete, start, now);
            return current;
        }

        synchronized void finish(long now) {
            if (current != null)
                update(current.operation, current.phase, current.total, current.total, current.unit, true, now);
        }

        synchronized boolean matches(String operation) {
            return current != null && current.operation.equals(operation);
        }

        synchronized boolean finish(String operation, long now) {
            if (!matches(operation)) return false;
            finish(now);
            return true;
        }

        synchronized HologramProgress view(long now) {
            return current != null && current.complete && now - current.observedAt > 2000000000L ? null : current;
        }

        synchronized void clear() {
            current = null;
        }
    }
}
