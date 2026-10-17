package com.miaokatze.gtmb.client.hologram;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

/** Observations only: progress never authorizes an edit or acknowledges a command. */
final class HologramClientProgress {

    private static final class Entry {

        final HologramProgress.Tracker tracker = new HologramProgress.Tracker();
        final HologramProgress.Tracker preparation = new HologramProgress.Tracker();
        String session = "", request = "";
        long sequence = Long.MIN_VALUE, command = -1, revision = -1;
    }

    private static final Map<String, Entry> views = new HashMap<>();
    private static final Set<String> retiredServerSessions = new LinkedHashSet<>();

    private HologramClientProgress() {}

    static void receive(NBTTagCompound event) {
        String scope = event.getString("scope"), session = event.getString("session");
        if (scope.isEmpty() || event.getString("phase")
            .isEmpty()) return;
        Entry entry = views.get(scope);
        long command = event.hasKey("uiSequence") ? event.getLong("uiSequence") : -1;
        String request = event.getString("requestId");
        if ("rejected".equals(event.getString("outcome"))) {
            // Unrelated denials cannot finish another command or an uncorrelated byte transfer.
            if (entry != null && entry.session.equals(session)
                && (command > 0 || !request.isEmpty())
                && (command < 0 || command == entry.command)
                && request.equals(entry.request)) views.remove(scope);
            return;
        }
        if ("server".equals(scope) && retiredServerSessions.contains(session)) return;
        boolean newSession = entry != null && !session.equals(entry.session);
        if (newSession && "server".equals(scope) && !entry.session.isEmpty()) {
            retiredServerSessions.add(entry.session);
            if (retiredServerSessions.size() > 64) retiredServerSessions.remove(
                retiredServerSessions.iterator()
                    .next());
        }
        if (entry == null || newSession) entry = new Entry();
        if (command >= 0 && entry.command >= 0 && command < entry.command) return;
        long revision = event.hasKey("planRevision", 4) ? event.getLong("planRevision") : -1;
        if ("server".equals(scope) && entry.revision >= 0 && revision < entry.revision) return;
        long sequence = event.getLong("sequence");
        if (command <= entry.command && sequence < entry.sequence) return;
        if ("server".equals(scope) && (command != entry.command || revision > entry.revision)) {
            entry.tracker.clear();
            entry.preparation.clear();
        }
        entry.session = session;
        entry.request = request;
        entry.command = command;
        entry.sequence = sequence;
        entry.revision = revision;
        views.put(scope, entry);
        if ("partial".equals(event.getString("outcome")) || "stopped".equals(event.getString("outcome"))) {
            entry.tracker.clear();
            entry.preparation.clear();
            return;
        }
        String identity = request;
        if (identity.isEmpty()) identity = "server".equals(scope)
            ? session + ":"
                + command
                + ":"
                + event.getString("phase")
                    .split("\\.")[0]
            : scope + ":" + sequence;
        // Snapshot metadata is a child publication of this command, not the world worker itself.
        // Keep its latest observation without overwriting an admitted job/capture/area observation.
        HologramProgress.Tracker target = "server".equals(scope) && event.getString("phase")
            .startsWith("snapshot.") ? entry.preparation : entry.tracker;
        target.update(
            identity,
            event.getString("phase"),
            event.getString("detail"),
            event.getLong("done"),
            event.getLong("total"),
            event.getString("unit"),
            event.getBoolean("complete") && !"snapshot".equals(scope),
            System.nanoTime());
    }

    static HologramProgress view(String scope, String session) {
        Entry entry = views.get(scope);
        if (entry == null || !session.isEmpty() && !session.equals(entry.session)) return null;
        long now = System.nanoTime();
        HologramProgress value = entry.tracker.view(now);
        if (value == null && "server".equals(scope)) value = entry.preparation.view(now);
        if (value != null && !"server".equals(scope)
            && !"decode".equals(value.phase)
            && now - value.observedAt > 15000000000L) return null;
        return value;
    }

    static void snapshot(NBTTagCompound state) {
        if (state.hasKey("progress", 10)) receive(state.getCompoundTag("progress"));
        String session = state.getString("session");
        Entry entry = views.get("server");
        HologramProgress value = view("server", session);
        if (value == null || state.getLong("uiSequence") != entry.command
            || entry.revision >= 0
                && (!state.hasKey("planRevision", 4) || state.getLong("planRevision") != entry.revision))
            return;
        boolean finished = value.phase.startsWith("capture") && !state.getBoolean("capturePending")
            || value.phase.startsWith("area.") && state.getBoolean("areaImported") && !state.getBoolean("areaImporting")
            || "job".equals(value.phase) && state.getInteger("job") == 3;
        if (finished && !value.complete) entry.tracker.finish(System.nanoTime());
        if ("job".equals(value.phase) && (state.getInteger("job") == 4 || state.getInteger("job") == 5)) {
            entry.tracker.clear();
            entry.preparation.clear();
        }
    }

    static void clear() {
        views.clear();
        retiredServerSessions.clear();
    }

    static void transportFailed(NBTTagCompound failure) {
        if (!failure.hasKey("uiSequence", 4)) return;
        Entry current = views.get("server");
        if (current != null && current.session.equals(failure.getString("session"))
            && current.command == failure.getLong("uiSequence")) {
            current.tracker.clear();
            current.preparation.clear();
        }
    }

    static long sequence(String scope) {
        Entry entry = views.get(scope);
        return entry == null ? -1 : entry.sequence;
    }

    static boolean commandMatches(String scope, String session, long command) {
        Entry entry = views.get(scope);
        return entry != null && entry.session.equals(session) && entry.command == command;
    }

    static void decoding(long sequence) {
        Entry entry = views.get("snapshot");
        if (entry == null || sequence != entry.sequence) return;
        HologramProgress current = entry.tracker.view(System.nanoTime());
        if (current != null) entry.tracker.update(current.operation, "decode", 0, 0, "", false, System.nanoTime());
    }

    static void decoded(long sequence, boolean success) {
        Entry entry = views.get("snapshot");
        if (entry == null || sequence < 0 || sequence != entry.sequence) return;
        if (success) entry.tracker.finish(System.nanoTime());
        else entry.tracker.clear();
    }

    static boolean matches(NBTTagCompound event) {
        Entry entry = views.get(event.getString("scope"));
        return entry != null && entry.session.equals(event.getString("session"))
            && entry.sequence == event.getLong("sequence")
            && entry.command == (event.hasKey("uiSequence") ? event.getLong("uiSequence") : -1)
            && entry.request.equals(event.getString("requestId"));
    }
}
