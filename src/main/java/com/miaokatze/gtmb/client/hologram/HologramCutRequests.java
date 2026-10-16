package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Directory reads never lock editing. Unknown write outcomes are resolved by a read, never replayed. */
final class HologramCutRequests {

    static final long READ_TIMEOUT_NS = 15000000000L, WRITE_TIMEOUT_NS = 60000000000L;
    private final Map<String, Request> requests = new LinkedHashMap<>();
    private boolean held;

    boolean observeHeld(boolean holding) {
        boolean entered = holding && !held;
        held = holding;
        return entered;
    }

    static final class Request {

        final String id, action, target;
        final long sentAt;
        boolean expired;

        Request(String id, String action, String target, long sentAt) {
            this.id = id;
            this.action = action;
            this.target = target;
            this.sentAt = sentAt;
        }

        boolean writesWorld() {
            return "cut".equals(action) || "paste".equals(action) || "undo".equals(action);
        }
    }

    void begin(String id, String action, String target, long now) {
        requests.entrySet()
            .removeIf(
                entry -> entry.getValue().expired && !entry.getValue()
                    .writesWorld());
        requests.put(id, new Request(id, action, target, now));
    }

    Request acknowledge(String id) {
        return requests.remove(id);
    }

    boolean active(String action) {
        for (Request request : requests.values()) if (!request.expired && action.equals(request.action)) return true;
        return false;
    }

    boolean busy() {
        for (Request request : requests.values())
            if (request.writesWorld() || !request.expired && !"list".equals(request.action)) return true;
        return false;
    }

    List<Request> expire(long now) {
        List<Request> expired = new ArrayList<>();
        for (Request request : requests.values()) {
            long timeout = request.writesWorld() ? WRITE_TIMEOUT_NS : READ_TIMEOUT_NS;
            if (!request.expired && now - request.sentAt >= timeout) {
                request.expired = true;
                expired.add(request);
            }
        }
        return expired;
    }

    Request reconcile(String id) {
        Request request = requests.get(id);
        if (request == null || !request.writesWorld() || !request.expired) return null;
        requests.remove(id);
        return request;
    }

    String unresolvedWrite() {
        for (Request request : requests.values()) if (request.writesWorld() && request.expired) return request.id;
        return "";
    }

    void clear() {
        requests.clear();
        held = false;
    }
}
