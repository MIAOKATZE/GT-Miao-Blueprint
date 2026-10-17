package com.miaokatze.gtmb.client.hologram;

/** Local continuation intent never cancels or forgets an authoritative in-flight world write. */
final class HologramCutContinuous {

    private long generation, submittedGeneration;
    private boolean selecting;
    private String pending = "";

    void enter() {
        generation++;
        selecting = true;
    }

    void exit() {
        generation++;
        selecting = false;
    }

    void submitted(String id) {
        pending = id;
        submittedGeneration = generation;
    }

    /** Called for both a direct ACK and an authoritative timeout reconciliation. */
    boolean completed(String id, boolean success) {
        if (pending.isEmpty() || !pending.equals(id)) return false;
        pending = "";
        return success && selecting && submittedGeneration == generation;
    }
}
