package com.miaokatze.gtmb.client.hologram;

import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.NBTTagCompound;

/** Correlated preparation activity. Repeated heartbeats have a finite stall allowance. */
final class HologramSavePreparationProgress {

    private static final long STALL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private final HologramProgress.Tracker tracker = new HologramProgress.Tracker();
    private String phase = "", detail = "";
    private long done = -1, total = -1, advancedAt;
    private final java.util.Map<String, Long> completed = new java.util.HashMap<>();

    boolean receive(NBTTagCompound event, long task, String session, long sequence, long revision, long now) {
        return receive(event, task, session, sequence, revision, now, true, "save:");
    }

    boolean receiveAuthorSource(NBTTagCompound event, long task, String session, long sequence, long revision,
        long now) {
        // Raw-source extraction has no save receipt/ACK; its optional later encoder sequence is still exact if present.
        if (!"snapshot.prepare".equals(event.getString("phase"))
            || !("author_source".equals(event.getString("detail")) || "export_json".equals(event.getString("detail"))))
            return false;
        return receive(event, task, session, sequence, revision, now, event.hasKey("uiSequence", 4), "author-source:");
    }

    private boolean receive(NBTTagCompound event, long task, String session, long sequence, long revision, long now,
        boolean requireSequence, String identityPrefix) {
        String nextPhase = event.getString("phase");
        if (task <= 0 || event.getLong("clientTaskToken") != task
            || !session.equals(event.getString("session"))
            || requireSequence && sequence != event.getLong("uiSequence")
            || revision != event.getLong("planRevision")
            || !(nextPhase.startsWith("snapshot.") || nextPhase.equals("library.prepare"))) return false;
        long nextDone = event.getLong("done"), nextTotal = event.getLong("total");
        String nextDetail = event.getString("detail");
        if (nextDone < 0 || nextTotal < 0 || nextTotal > 0 && nextDone > nextTotal) return false;
        if (phase.equals(nextPhase) && detail.equals(nextDetail) && nextTotal == total && nextDone < done) return false;
        String stage = nextPhase + ":" + nextDetail;
        Long previous = completed.get(stage);
        if (previous != null && nextDone < previous) return false;
        if (previous == null && completed.size() >= 32) return false;
        if (advancedAt != 0 && now - advancedAt > STALL_NANOS) return false;
        if (previous == null || nextDone > previous) advancedAt = now;
        completed.put(stage, nextDone);
        phase = nextPhase;
        detail = nextDetail;
        done = nextDone;
        total = nextTotal;
        tracker.update(identityPrefix + task, phase, detail, done, total, event.getString("unit"), false, now);
        return true;
    }

    HologramProgress view(long now) {
        return tracker.view(now);
    }

    boolean stalled(long now) {
        return advancedAt != 0 && now - advancedAt > STALL_NANOS;
    }

    void clear() {
        tracker.clear();
        phase = detail = "";
        done = total = -1;
        advancedAt = 0;
        completed.clear();
    }
}
