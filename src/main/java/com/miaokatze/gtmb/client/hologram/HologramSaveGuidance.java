package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

/** Only a successful committed save enters this lifecycle. No screen or server authority is retained. */
final class HologramSaveGuidance {

    private final Map<String, Entry> sessions = new LinkedHashMap<>();

    private static final class Entry {

        boolean suggestion = true, notice = true;
    }

    void committed(String session) {
        if (session == null || session.isEmpty()) return;
        sessions.put(session, new Entry());
        while (sessions.size() > 64) sessions.remove(
            sessions.keySet()
                .iterator()
                .next());
    }

    boolean pending(String session) {
        Entry entry = sessions.get(session);
        return entry != null && entry.suggestion;
    }

    boolean takeNotice(String session, boolean suppressed) {
        Entry entry = sessions.get(session);
        if (entry == null || !entry.notice) return false;
        entry.notice = false;
        return !suppressed;
    }

    void visitedStructure(String session) {
        Entry entry = sessions.get(session);
        if (entry != null) entry.suggestion = false;
    }

    int accent(String session, int control) {
        return pending(session) && (control == 148 || control == 60 || control == 163) ? HologramUiAccent.SUGGESTION
            : 0;
    }
}
