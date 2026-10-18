package com.miaokatze.gtmb.client.hologram;

/** Client capability fence: a canceled bootstrap or final upload ACK cannot revive a world placement. */
final class HologramLibraryQuickState {

    private String token = "";
    private String session = "";
    private boolean bootstrapped;

    String begin() {
        token = java.util.UUID.randomUUID()
            .toString();
        bootstrapped = false;
        session = "";
        return token;
    }

    boolean active() {
        return !token.isEmpty();
    }

    String token() {
        return token;
    }

    boolean matches(String candidate) {
        return active() && token.equals(candidate);
    }

    boolean bootstrap(String candidate, String acknowledgedSession) {
        if (!matches(candidate) || bootstrapped || acknowledgedSession == null || acknowledgedSession.isEmpty())
            return false;
        bootstrapped = true;
        session = acknowledgedSession;
        return true;
    }

    boolean commitLockedSession(String lockedSession) {
        if (!active() || !bootstrapped || !session.equals(lockedSession)) return false;
        committed();
        return true;
    }

    boolean placement(String candidate, String previousSession, String placedSession) {
        if (!matches(candidate) || !bootstrapped
            || !session.equals(previousSession)
            || placedSession == null
            || placedSession.isEmpty()
            || placedSession.equals(previousSession)) return false;
        session = placedSession;
        return true;
    }

    boolean snapshotMatches(String candidate, long expectedTask, long snapshotTask) {
        return expectedTask > 0 && expectedTask == snapshotTask && matches(candidate);
    }

    String cancel() {
        String revoked = token;
        token = "";
        bootstrapped = false;
        session = "";
        return revoked;
    }

    void committed() {
        token = "";
        bootstrapped = false;
        session = "";
    }
}
