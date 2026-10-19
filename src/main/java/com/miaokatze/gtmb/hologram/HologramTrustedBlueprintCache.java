package com.miaokatze.gtmb.hologram;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded connection/save/policy scoped canonical bases. A digest never grants trust to unseen bytes. */
final class HologramTrustedBlueprintCache {

    private static final String POLICY = "canonical-1/display-1";
    private static final int MAX_ENTRIES = 32;
    static final int OWNER_ENTRIES = 10;
    static final long OWNER_BYTES = 100_000_000L;
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long bytes;

    synchronized boolean contains(UUID owner, Object connection, Object world, long epoch, String hash) {
        Entry entry = current(owner, connection, world, epoch, hash);
        return entry != null && entry.ready;
    }

    synchronized HologramPresets.Preset get(UUID owner, Object connection, Object world, long epoch, String hash) {
        Entry entry = current(owner, connection, world, epoch, hash);
        return entry != null && entry.ready ? HologramPresets.copy(entry.base) : null;
    }

    private Entry current(UUID owner, Object connection, Object world, long epoch, String hash) {
        Entry entry = entries.get(POLICY + owner + ":" + hash);
        return entry != null && entry.connection == connection && entry.world == world && entry.epoch == epoch ? entry
            : null;
    }

    /** Input has completed canonical admission. JSON bytes are the normalized compact encoding, without PNG. */
    synchronized boolean put(UUID owner, Object connection, Object world, long epoch, String hash,
        HologramPresets.Preset trusted, int jsonBytes) throws java.io.IOException {
        return retain(owner, connection, world, epoch, hash, trusted, jsonBytes, "", "");
    }

    /** Stage only server-owned reviewed originals. A disk ACK cannot manufacture a missing staged original. */
    synchronized boolean stage(UUID owner, Object connection, Object world, long epoch, String hash,
        HologramPresets.Preset trusted, int jsonBytes, String receipt, String rawJsonHash) throws java.io.IOException {
        discardPending(connection, "");
        if (contains(owner, connection, world, epoch, hash)) return true;
        return retain(owner, connection, world, epoch, hash, trusted, jsonBytes, receipt, rawJsonHash);
    }

    private boolean retain(UUID owner, Object connection, Object world, long epoch, String hash,
        HologramPresets.Preset trusted, int jsonBytes, String receipt, String rawJsonHash) throws java.io.IOException {
        long weight = dtoWeight(trusted, jsonBytes);
        if (jsonBytes <= 0 || jsonBytes > OWNER_BYTES || weight > MAX_BYTES) return false;
        String key = POLICY + owner + ":" + hash;
        Entry previous = entries.remove(key);
        if (previous != null) bytes -= previous.bytes;
        long ownerBytes = 0;
        int ownerEntries = 0;
        for (Entry entry : entries.values()) if (entry.owner.equals(owner)) {
            ownerEntries++;
            ownerBytes += entry.jsonBytes;
        }
        Iterator<Entry> owned = entries.values()
            .iterator();
        while (owned.hasNext() && (ownerEntries >= OWNER_ENTRIES || ownerBytes + jsonBytes > OWNER_BYTES)) {
            Entry entry = owned.next();
            if (!entry.owner.equals(owner)) continue;
            bytes -= entry.bytes;
            ownerBytes -= entry.jsonBytes;
            ownerEntries--;
            owned.remove();
        }
        while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES || bytes + weight > MAX_BYTES)) {
            Iterator<Entry> iterator = entries.values()
                .iterator();
            bytes -= iterator.next().bytes;
            iterator.remove();
        }
        entries.put(
            key,
            new Entry(
                owner,
                connection,
                world,
                epoch,
                hash,
                HologramPresets.copy(trusted),
                weight,
                jsonBytes,
                receipt,
                rawJsonHash));
        bytes += weight;
        return true;
    }

    /**
     * Repeated dictionary payloads count once per expanded occurrence; compressed bytes cannot evade the heap budget.
     */
    static long dtoWeight(HologramPresets.Preset source, int encodedBytes) throws java.io.IOException {
        long expandedBytes = Math.max(encodedBytes, HologramBlueprintCompactCodec.expandedBytes(source));
        return 2L * expandedBytes + 512L * (source.cells.size() + source.palette.size())
            + 256L * (source.aeIssues.size() + source.structures.size() + source.hintAreas.size());
    }

    synchronized String commitSaved(Object connection, String receipt, String rawJsonHash) {
        for (Entry entry : entries.values()) if (entry.connection == connection && !entry.ready
            && entry.receipt.equals(receipt)
            && entry.rawJsonHash.equals(rawJsonHash)) {
                entry.ready = true;
                return entry.hash;
            }
        return "";
    }

    synchronized void discardPending(Object connection, String receipt) {
        Iterator<Entry> iterator = entries.values()
            .iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.connection == connection && !entry.ready
                && (receipt.isEmpty() || entry.receipt.equals(receipt))) {
                bytes -= entry.bytes;
                iterator.remove();
            }
        }
    }

    synchronized void retire(Object connection) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next()
                .getValue();
            if (entry.connection != connection) continue;
            bytes -= entry.bytes;
            iterator.remove();
        }
    }

    synchronized void clear() {
        entries.clear();
        bytes = 0;
    }

    private static final class Entry {

        final UUID owner;
        final Object connection, world;
        final long epoch, bytes;
        final int jsonBytes;
        final String hash, receipt, rawJsonHash;
        boolean ready;
        final HologramPresets.Preset base;

        Entry(UUID owner, Object connection, Object world, long epoch, String hash, HologramPresets.Preset base,
            long bytes, int jsonBytes, String receipt, String rawJsonHash) {
            this.owner = owner;
            this.hash = hash;
            this.connection = connection;
            this.world = world;
            this.epoch = epoch;
            this.base = base;
            this.bytes = bytes;
            this.jsonBytes = jsonBytes;
            this.receipt = receipt;
            this.rawJsonHash = rawJsonHash;
            ready = receipt.isEmpty();
        }
    }
}
