package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Render-only native payloads. Model identities never authorize placement, payment or NBT edits. */
final class HologramSnapshotModels {

    private static final AtomicLong IDS = new AtomicLong(), GENERATIONS = new AtomicLong();
    private static final long CACHE_BYTES = 32L << 20;
    private static final Map<Key, Entry> memo = new LinkedHashMap<>(16, .75f, true);
    private static final Map<Long, Entry> byId = new HashMap<>();
    private static final Map<UUID, LinkedHashMap<Long, Source>> published = new LinkedHashMap<>();
    private static final Map<UUID, Integer> pending = new HashMap<>();
    private static long cached;

    private HologramSnapshotModels() {}

    static void clear() {
        for (LinkedHashMap<Long, Source> history : published.values())
            for (Source source : history.values()) source.close();
        published.clear();
        pending.clear();
        synchronized (memo) {
            for (Entry entry : memo.values()) entry.release();
            memo.clear();
            cached = 0;
        }
    }

    static void prune() {
        java.util.Iterator<Map.Entry<UUID, LinkedHashMap<Long, Source>>> actors = published.entrySet()
            .iterator();
        while (actors.hasNext()) {
            Map.Entry<UUID, LinkedHashMap<Long, Source>> actor = actors.next();
            HologramSession owner = HologramWorkspace.find(actor.getKey());
            if (owner == null || !HologramNetwork.progressConnected(owner.player)) {
                for (Source source : actor.getValue()
                    .values()) source.close();
                actors.remove();
            }
        }
    }

    static final class Builder implements AutoCloseable {

        final UUID actor;
        final String session;
        final long revision;
        final long generation = GENERATIONS.incrementAndGet();
        Map<Integer, Entry> rows = new LinkedHashMap<>();

        Builder(HologramSession owner) {
            actor = owner.player.getUniqueID();
            session = owner.id;
            revision = owner.planRevision;
        }

        void add(int index, HologramTransportPageLayout.Row identity, NBTTagCompound row) {
            NBTTagCompound actual = row.getCompoundTag("actualDisplay"), target = row.getCompoundTag("targetDisplay");
            int mask = (actual.hasKey("nativeTile", 10) && !actual.getCompoundTag("nativeTile")
                .hasNoTags() ? 1 : 0) | (target.hasKey("nativeTile", 10)
                    && !target.getCompoundTag("nativeTile")
                        .hasNoTags() ? 2 : 0);
            if (mask == 0) return;
            long actualBytes = HologramSnapshotNbt.estimate(actual.getCompoundTag("nativeTile"));
            long targetBytes = HologramSnapshotNbt.estimate(target.getCompoundTag("nativeTile"));
            if (actualBytes > (8L << 20) || targetBytes > (8L << 20)) return; // Keep the exact original full row for a
                                                                              // single over-budget model.
            NBTTagCompound nativeActual = nativeOnly(actual), nativeTarget = nativeOnly(target);
            Key key = new Key(
                actor,
                session,
                identity.authority,
                identity.cellId,
                row.getString("id"),
                row.getInteger("meta"),
                row.getString("wantId"),
                row.getInteger("wantMeta"));
            Entry entry;
            synchronized (memo) {
                entry = memo.get(key);
                if (entry != null && entry.mask == mask
                    && HologramSnapshotNbt.same(entry.actual, nativeActual)
                    && HologramSnapshotNbt.same(entry.target, nativeTarget)) entry.retain();
                else {
                    long weight = HologramSnapshotNbt.estimate(nativeActual)
                        + HologramSnapshotNbt.estimate(nativeTarget)
                        + 512;
                    while (!memo.isEmpty() && (memo.size() >= 4096 || weight > CACHE_BYTES - cached)) {
                        Key first = memo.keySet()
                            .iterator()
                            .next();
                        Entry old = memo.remove(first);
                        cached -= old.weight;
                        old.release();
                    }
                    HologramProcessGovernor.Lease admitted = HologramProcessGovernor
                        .tryRetain("snapshot.nativeModel", weight);
                    if (admitted == null) return; // Full old row remains intact when an optional domain cannot be
                                                  // admitted.
                    entry = new Entry(key, mask, nativeActual, nativeTarget, weight, admitted.transfer());
                    byId.put(entry.id, entry);
                    if (weight <= CACHE_BYTES) {
                        Entry old = memo.put(key, entry);
                        if (old != null) {
                            cached -= old.weight;
                            old.release();
                        }
                        cached += weight;
                        entry.retain();
                    }
                }
            }
            rows.put(index, entry);
            if ((mask & 1) != 0) row.setTag("actualDisplay", light(actual));
            if ((mask & 2) != 0) row.setTag("targetDisplay", light(target));
            row.setInteger("nativeModelPending", mask);
            row.setLong("nativeModelId", entry.id);
        }

        private NBTTagCompound nativeOnly(NBTTagCompound full) {
            NBTTagCompound result = new NBTTagCompound();
            if (full.hasKey("nativeTile", 10)) result.setTag("nativeTile", full.getTag("nativeTile"));
            return result;
        }

        private NBTTagCompound light(NBTTagCompound full) {
            NBTTagCompound result = new NBTTagCompound();
            for (Object object : full.func_150296_c()) {
                String key = (String) object;
                if (!"nativeTile".equals(key)) result.setTag(
                    key,
                    full.getTag(key)
                        .copy());
            }
            return result;
        }

        Source finish() {
            Source source = new Source(actor, session, revision, generation, rows);
            rows = null;
            return source;
        }

        public void close() {
            if (rows != null) {
                for (Entry entry : rows.values()) entry.release();
                rows = null;
            }
        }
    }

    static final class Source implements AutoCloseable {

        final UUID actor;
        final String session;
        final long revision, generation;
        final Map<Integer, Entry> rows;
        boolean closed;

        Source(UUID actor, String session, long revision, long generation, Map<Integer, Entry> rows) {
            this.actor = actor;
            this.session = session;
            this.revision = revision;
            this.generation = generation;
            this.rows = rows;
        }

        NBTTagCompound manifest() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("generation", generation);
            tag.setLong("revision", revision);
            tag.setBoolean("available", true);
            return tag;
        }

        void publish() {
            LinkedHashMap<Long, Source> history = published.get(actor);
            if (history == null) {
                history = new LinkedHashMap<>();
                published.put(actor, history);
            }
            while (history.size() >= 2) {
                Long first = history.keySet()
                    .iterator()
                    .next();
                history.remove(first)
                    .close();
            }
            for (Entry entry : rows.values()) entry.published = true;
            history.put(generation, this);
        }

        public void close() {
            if (closed) return;
            closed = true;
            for (Entry entry : rows.values()) entry.release();
        }
    }

    static void request(HologramSession session, NBTTagCompound request) {
        UUID actor = session.player.getUniqueID();
        int[] indices = request.getIntArray("indices");
        NBTTagList ids = request.getTagList("modelIds", 10);
        NBTTagCompound header = header(session, request);
        int mask = request.getInteger("mask");
        if (request.getLong("planRevision") != session.planRevision || indices.length == 0
            || indices.length > 32
            || ids.tagCount() != indices.length
            || mask < 1
            || mask > 3
            || request.getString("requestId")
                .length() > 64
            || pending.getOrDefault(actor, 0) >= 4) {
            header.setString("modelError", "busy");
            HologramNetwork.sendModel(session.player, header, null);
            return;
        }
        LinkedHashMap<Long, Source> history = published.get(actor);
        Source publication = history == null ? null : history.get(request.getLong("modelGeneration"));
        if (publication == null || publication.closed
            || publication.revision != session.planRevision
            || !publication.session.equals(session.id)) {
            header.setString("modelError", "stale");
            HologramNetwork.sendModel(session.player, header, null);
            return;
        }
        List<Entry> selected = new ArrayList<>();
        Set<Integer> unique = new HashSet<>();
        synchronized (memo) {
            for (int i = 0; i < indices.length; i++) {
                Entry entry = publication.rows.get(indices[i]);
                if (indices[i] < 0 || !unique.add(indices[i])
                    || entry == null
                    || entry.id != ids.getCompoundTagAt(i)
                        .getLong("value")
                    || !entry.published
                    || !entry.key.actor.equals(actor)
                    || !entry.key.session.equals(session.id)) {
                    for (Entry value : selected) value.release();
                    header.setString("modelError", "stale");
                    HologramNetwork.sendModel(session.player, header, null);
                    return;
                }
                entry.retain();
                selected.add(entry);
            }
        }
        HologramProcessGovernor.Lease accepted = null;
        int count = selected.size();
        while (count > 0) {
            long charge = 1024;
            for (int i = 0; i < count; i++) {
                Entry value = selected.get(i);
                if ((mask & value.mask & 1) != 0) charge += 2L * value.actualBytes;
                if ((mask & value.mask & 2) != 0) charge += 2L * value.targetBytes;
            }
            if (charge <= (16L << 20)) accepted = HologramProcessGovernor.tryRetain("snapshot.modelReply", charge);
            if (accepted != null) break;
            count--;
        }
        if (accepted == null) {
            for (Entry entry : selected) entry.release();
            header.setString("modelError", "busy");
            HologramNetwork.sendModel(session.player, header, null);
            return;
        }
        while (selected.size() > count) selected.remove(selected.size() - 1)
            .release();
        final int delivered = count;
        final HologramProcessGovernor.Lease output = accepted;
        HologramSnapshotResponseLease response = new HologramSnapshotResponseLease(output);
        pending.put(actor, pending.getOrDefault(actor, 0) + 1);
        String ownerSession = session.id;
        long revision = session.planRevision;
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(),
            cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable cleanup = () -> {
            if (cleaned.compareAndSet(false, true)) for (Entry entry : selected) entry.release();
        };
        java.util.concurrent.FutureTask<NBTTagCompound> work = new java.util.concurrent.FutureTask<NBTTagCompound>(
            () -> {
                try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor
                    .acquireHeavyCpu("snapshot.nativeModelReply")) {
                    NBTTagList rows = new NBTTagList();
                    for (int i = 0; i < delivered; i++) {
                        Entry entry = selected.get(i);
                        NBTTagCompound row = new NBTTagCompound();
                        row.setInteger("index", indices[i]);
                        row.setLong("nativeModelId", entry.id);
                        if ((mask & entry.mask & 1) != 0) {
                            NBTTagCompound display = new NBTTagCompound();
                            display.setTag(
                                "nativeTile",
                                entry.actual.getCompoundTag("nativeTile")
                                    .copy());
                            row.setTag("actualDisplay", display);
                        }
                        if ((mask & entry.mask & 2) != 0) {
                            NBTTagCompound display = new NBTTagCompound();
                            display.setTag(
                                "nativeTile",
                                entry.target.getCompoundTag("nativeTile")
                                    .copy());
                            row.setTag("targetDisplay", display);
                        }
                        rows.appendTag(row);
                    }
                    header.setTag("rows", rows);
                    return header;
                } finally {
                    cleanup.run();
                }
            }) {

            public void run() {
                if (!response.start()) {
                    cancel(false);
                    return;
                }
                started.set(true);
                try {
                    super.run();
                } finally {
                    response.finish();
                }
            }

            protected void done() {
                if (!started.get()) cleanup.run();
                if (response.retired()) return;
                com.miaokatze.gtmb.util.ServerTaskScheduler.scheduleServerTask(() -> {
                    pending.computeIfPresent(actor, (key, count) -> count <= 1 ? null : count - 1);
                    if (response.retired() || isCancelled()
                        || HologramWorkspace.find(actor) != session
                        || !ownerSession.equals(session.id)
                        || revision != session.planRevision) {
                        response.close();
                        return;
                    }
                    try {
                        HologramNetwork.sendModel(session.player, get(), response);
                    } catch (Exception failed) {
                        response.close();
                    }
                });
            }
        };
        if (!HologramAreaPreparation.execute(work)) work.cancel(false);
    }

    private static NBTTagCompound header(HologramSession session, NBTTagCompound request) {
        NBTTagCompound reply = new NBTTagCompound();
        reply.setBoolean("modelOnly", true);
        reply.setString("session", session.id);
        reply.setLong("planRevision", request.getLong("planRevision"));
        reply.setLong("modelGeneration", request.getLong("modelGeneration"));
        String id = request.getString("requestId");
        reply.setString("requestId", id.length() > 64 ? id.substring(0, 64) : id);
        NBTTagList ids = request.getTagList("modelIds", 10);
        reply.setTag("modelIds", ids.tagCount() <= 32 ? ids.copy() : new NBTTagList());
        return reply;
    }

    private static final class Key {

        final UUID actor;
        final String session, actual, target;
        final long authority, id;
        final int actualMeta, targetMeta;

        Key(UUID actor, String session, long authority, long id, String actual, int actualMeta, String target,
            int targetMeta) {
            this.actor = actor;
            this.session = session;
            this.authority = authority;
            this.id = id;
            this.actual = actual;
            this.target = target;
            this.actualMeta = actualMeta;
            this.targetMeta = targetMeta;
        }

        public int hashCode() {
            return Objects.hash(actor, session, authority, id, actual, actualMeta, target, targetMeta);
        }

        public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return actor.equals(other.actor) && session.equals(other.session)
                && authority == other.authority
                && id == other.id
                && actualMeta == other.actualMeta
                && targetMeta == other.targetMeta
                && actual.equals(other.actual)
                && target.equals(other.target);
        }
    }

    private static final class Entry {

        final Key key;
        final long id = IDS.incrementAndGet(), weight, actualBytes, targetBytes;
        final int mask;
        final NBTTagCompound actual, target;
        final HologramProcessGovernor.Lease lease;
        final AtomicInteger references = new AtomicInteger(1);
        volatile boolean published;

        Entry(Key key, int mask, NBTTagCompound actual, NBTTagCompound target, long weight,
            HologramProcessGovernor.Lease lease) {
            this.key = key;
            this.mask = mask;
            this.actual = actual;
            this.target = target;
            this.weight = weight;
            this.lease = lease;
            actualBytes = HologramSnapshotNbt.estimate(actual);
            targetBytes = HologramSnapshotNbt.estimate(target);
        }

        void retain() {
            references.incrementAndGet();
        }

        void release() {
            if (references.decrementAndGet() == 0) {
                synchronized (memo) {
                    byId.remove(id);
                }
                lease.close();
            }
        }
    }
}
