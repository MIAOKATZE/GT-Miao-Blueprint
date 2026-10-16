package com.miaokatze.gtmb.client.hologram;

/** One pending workstation request. A different session cannot acknowledge its sequence. */
final class HologramUiTicket {

    private String session = "";
    private long sequence = -1;

    void begin(String session, long sequence) {
        this.session = session;
        this.sequence = sequence;
    }

    long sequence() {
        return sequence;
    }

    void clear() {
        session = "";
        sequence = -1;
    }

    boolean acknowledge(String session, long sequence) {
        if (this.sequence < 0 || !this.session.equals(session) || sequence < this.sequence) return false;
        clear();
        return true;
    }

    boolean rejectExact(String session, long sequence) {
        if (this.sequence < 0 || !this.session.equals(session) || this.sequence != sequence) return false;
        clear();
        return true;
    }
}
