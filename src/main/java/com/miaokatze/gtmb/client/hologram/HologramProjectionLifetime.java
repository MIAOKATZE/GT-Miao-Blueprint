package com.miaokatze.gtmb.client.hologram;

/** Terminal receipts start one monotonic deadline; repeated snapshots never extend it. */
final class HologramProjectionLifetime {

    static final long RETENTION_NANOS = 15_000_000_000L;
    private String session = "";
    private long deadline;

    void observe(HologramState state) {
        if (state != null) visible(state.session, state.data.getInteger("job"), System.nanoTime());
    }

    boolean visible(String id, int job, long now, boolean activeSelection) {
        return activeSelection || visible(id, job, now);
    }

    boolean visible(String id, int job, long now) {
        if (!session.equals(id)) {
            session = id;
            deadline = 0;
        }
        if (job == 4) return false;
        if (job != 3 && job != 5) {
            deadline = 0;
            return true;
        }
        if (deadline == 0) deadline = now + RETENTION_NANOS;
        return now < deadline;
    }
}
