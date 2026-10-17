package com.miaokatze.gtmb.client.hologram;

/** One selected item and one read request. Changing selection invalidates every older reply. */
final class HologramCutStoragePreviewState {

    // Canonical UUID namespace: the existing endpoint deliberately rejects non-UUID request IDs.
    static final String PREFIX = "47544d42-6d75-";
    private String selected = "", request = "";
    private long sentAt;
    private boolean attempted, closed;

    String selected() {
        return selected;
    }

    String request() {
        return request;
    }

    boolean select(String id) {
        if (closed || selected.equals(id)) return false;
        selected = id;
        request = "";
        attempted = false;
        return true;
    }

    boolean needsRequest() {
        return !closed && !selected.isEmpty() && !attempted;
    }

    void begin(String id, long now) {
        request = id;
        sentAt = now;
        attempted = true;
    }

    boolean accept(String id) {
        if (closed || request.isEmpty() || !request.equals(id)) return false;
        request = "";
        return true;
    }

    boolean expire(long now) {
        if (request.isEmpty() || now - sentAt < HologramCutRequests.READ_TIMEOUT_NS) return false;
        request = "";
        return true;
    }

    void retry() {
        request = "";
        attempted = false;
    }

    void close() {
        closed = true;
        request = "";
        selected = "";
    }
}
