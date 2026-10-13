package com.miaokatze.gtmb.client.hologram;

/** Presentation only: explicit root exit suppresses queued openings until the server acknowledges close. */
final class HologramRootCloseFence {

    private String session = "";
    private long sequence = -1;

    void close(String session, long sequence) {
        if (session == null || session.isEmpty() || sequence < 0)
            throw new IllegalArgumentException("root close identity");
        this.sequence = this.session.equals(session) ? Math.max(this.sequence, sequence) : sequence;
        this.session = session;
    }

    boolean suppress(String session, long sequence, boolean open, boolean importing) {
        if (this.session.isEmpty()) return false;
        if (!this.session.equals(session)) {
            clear();
            return false;
        }
        // The ACK itself must not route the GUI. Later deliberate opens can reuse the same sequence.
        if (sequence >= this.sequence && !open && !importing) clear();
        return true;
    }

    void clear() {
        session = "";
        sequence = -1;
    }
}
