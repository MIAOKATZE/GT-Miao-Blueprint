package com.miaokatze.gtmb.client.hologram;

/** One client operation owns the projector until its exact completion or explicit cancellation. */
final class HologramTaskOwner {

    enum Result {
        SUCCESS,
        FAILURE,
        CANCELLED
    }

    interface Notifications {

        void background(String label);

        void finished(String label, Result result, String detail);
    }

    private final Notifications notifications;
    private long nextToken;
    private long token;
    private String kind = "", key = "", label = "";
    private boolean detached, announced;
    private final java.util.Set<String> retired = new java.util.LinkedHashSet<>();

    HologramTaskOwner(Notifications notifications) {
        this.notifications = notifications;
    }

    long begin(String kind, String key, String label) {
        if (busy()) return this.kind.equals(kind) && this.key.equals(key) ? token : 0;
        if (retired.contains(kind + ":" + key)) return 0;
        token = ++nextToken;
        this.kind = kind;
        this.key = key;
        this.label = label;
        detached = false;
        announced = false;
        return token;
    }

    boolean finish(long expected, Result result, String detail) {
        if (!busy() || expected != token) return false;
        String finishedLabel = label;
        retired.add(kind + ":" + key);
        while (retired.size() > 128) retired.remove(
            retired.iterator()
                .next());
        token = 0;
        kind = "";
        key = "";
        notifications.finished(finishedLabel, result, detail);
        return true;
    }

    boolean matches(String kind, String key) {
        return busy() && this.kind.equals(kind) && this.key.equals(key);
    }

    boolean advance(String kind, String previousKey, String nextKey) {
        if (!matches(kind, previousKey) || previousKey.equals(nextKey) || retired.contains(kind + ":" + nextKey))
            return false;
        retired.add(kind + ":" + previousKey);
        while (retired.size() > 128) retired.remove(
            retired.iterator()
                .next());
        key = nextKey;
        return true;
    }

    boolean finishReceipt(String kind, String key, Result result, String detail) {
        return matches(kind, key) && finish(token, result, detail);
    }

    boolean rejectAdvance(String kind, String rejectedKey, String previousKey) {
        if (!matches(kind, rejectedKey) || !retired.remove(kind + ":" + previousKey)) return false;
        retired.add(kind + ":" + rejectedKey);
        key = previousKey;
        return true;
    }

    void detach() {
        if (!busy()) return;
        detached = true;
        if (!announced) {
            announced = true;
            notifications.background(label);
        }
    }

    void attach() {
        detached = false;
    }

    boolean busy() {
        return token != 0;
    }

    boolean detached() {
        return detached;
    }

    long token() {
        return token;
    }

    String kind() {
        return kind;
    }

    String key() {
        return key;
    }

    String label() {
        return label;
    }
}
