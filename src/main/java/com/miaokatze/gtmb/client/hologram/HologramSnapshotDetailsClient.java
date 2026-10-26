package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramProcessGovernor;

/** Bounded visible report pages; absent details remain explicitly pending, never an authoritative empty list. */
final class HologramSnapshotDetailsClient {

    private static final int MAX_ROWS = 16384;
    private static final long MAX_BYTES = 32L << 20;
    private static String session = "";
    private static long revision = -1, generation = -1, bytes;
    private static final Map<String, Domain> domains = new LinkedHashMap<>(16, .75f, true);
    private static final Map<String, Node> lru = new LinkedHashMap<>(16, .75f, true);
    private static final Map<String, Pending> pending = new LinkedHashMap<>();
    private static NBTTagCompound readyPolicy;
    private static HologramProcessGovernor.Lease readyLease;
    private static boolean ready;

    private HologramSnapshotDetailsClient() {}

    static boolean paged(NBTTagCompound state) {
        return state.hasKey("detailManifest", 10);
    }

    private static void context(NBTTagCompound state) {
        NBTTagCompound manifest = state.getCompoundTag("detailManifest");
        long next = state.getLong("planRevision"), gen = manifest.getLong("generation");
        if (!session.equals(state.getString("session")) || revision != next || generation != gen) {
            clear();
            session = state.getString("session");
            revision = next;
            generation = gen;
        }
    }

    static void clear() {
        for (Node node : lru.values()) node.page.release();
        lru.clear();
        for (Domain domain : domains.values()) if (domain.metaPage != null) domain.metaPage.release();
        domains.clear();
        for (Pending request : pending.values()) request.close();
        pending.clear();
        if (readyLease != null) readyLease.close();
        readyLease = null;
        readyPolicy = null;
        ready = false;
        bytes = 0;
        session = "";
        revision = -1;
        generation = -1;
    }

    private static Domain domain(NBTTagCompound state, String name) {
        context(state);
        Domain value = domains.get(name);
        if (value == null) {
            if (domains.size() >= 64) {
                String oldest = domains.keySet()
                    .iterator()
                    .next();
                discard(oldest);
            }
            value = new Domain(
                name,
                name.startsWith("cell/") ? 1
                    : state.getCompoundTag("detailManifest")
                        .getInteger(name));
            domains.put(name, value);
        }
        return value;
    }

    static NBTTagList list(NBTTagCompound state, String name, int type, int offset, int visible) {
        Domain value = domain(state, name);
        ensure(state, value, offset, visible);
        return new View(value, type);
    }

    static int count(NBTTagCompound state, String name) {
        return paged(state) ? domain(state, name).total : 0;
    }

    static boolean loaded(NBTTagCompound state, String name, int index) {
        return domain(state, name).rows.containsKey(index);
    }

    static boolean complete(NBTTagCompound state, String name) {
        NBTTagCompound manifest = state.getCompoundTag("detailManifest");
        if (!manifest.getBoolean("available") || manifest.getLong("revision") != state.getLong("planRevision"))
            return false;
        Domain value = domain(state, name);
        return value.known && value.sourceComplete && value.rows.size() == value.total;
    }

    static NBTTagCompound cell(NBTTagCompound state, int selected, int offset, int visible) {
        Domain value = domain(state, "cell/" + selected);
        ensure(state, value, offset, visible);
        NBTTagCompound result = (NBTTagCompound) value.meta.copy();
        result.setTag("entries", new View(value, 10));
        return result;
    }

    private static void ensure(NBTTagCompound state, Domain value, int start, int visible) {
        if (!state.getCompoundTag("detailManifest")
            .getBoolean("available") || revision
                != state.getCompoundTag("detailManifest")
                    .getLong("revision"))
            return;
        for (int index = Math.max(0, start); index < Math.min(value.total, start + visible); index++)
            if (!value.rows.containsKey(index)) {
                request(state, value, index, null);
                return;
            }
        if (!value.known && value.name.startsWith("cell/")) request(state, value, 0, null);
    }

    private static void request(NBTTagCompound state, Domain value, int offset, NBTTagCompound policy) {
        long now = System.nanoTime();
        java.util.Iterator<Pending> expired = pending.values()
            .iterator();
        while (expired.hasNext()) {
            Pending old = expired.next();
            if (now - old.started >= 5_000_000_000L) {
                expired.remove();
                old.close();
            }
        }
        if (now < value.retryAt) return;
        for (Pending old : pending.values()) if (old.domain.equals(value.name) && old.offset == offset
            && now - old.started < 5_000_000_000L
            && (policy == null || policy.equals(old.policy))) return;
        if (pending.size() >= 4) return;
        HologramProcessGovernor.Lease input = null;
        NBTTagCompound frozen = null;
        if (policy != null) {
            input = HologramProcessGovernor.tryRetain(
                "snapshot.detailPolicy",
                com.miaokatze.gtmb.hologram.HologramSnapshotCodec.retainedEstimate(policy));
            if (input == null) return;
            frozen = (NBTTagCompound) policy.copy();
            input.transfer();
        }
        String id = UUID.randomUUID()
            .toString();
        NBTTagCompound request = new NBTTagCompound();
        request.setString("op", "requestSnapshotDetail");
        request.setString("session", session);
        request.setLong("planRevision", revision);
        request.setString("requestId", id);
        request.setInteger("offset", offset);
        request.setString("domain", value.name.startsWith("cell/") ? "cell" : value.name);
        if (value.name.startsWith("cell/")) request.setInteger("cell", Integer.parseInt(value.name.substring(5)));
        if (frozen != null) request.setTag("policy", frozen);
        pending.put(id, new Pending(value.name, offset, now, frozen, input));
        HologramNetwork.sendAction(request);
    }

    static boolean resourcesReady(NBTTagCompound state, NBTTagCompound policy) {
        if (!paged(state)) return false;
        context(state);
        if (!state.getCompoundTag("detailManifest")
            .getBoolean("available")) return false;
        if (readyPolicy != null && readyPolicy.equals(policy)) return ready;
        request(state, domain(state, "resourceReady"), 0, policy);
        return false;
    }

    static void receive(NBTTagCompound reply, AutoCloseable ownership, long retainedBytes) {
        boolean transferred = false;
        try {
            Pending request = pending.remove(reply.getString("requestId"));
            if (request == null) return;
            try {
                Domain requestedDomain = domains.get(request.domain);
                if (!reply.getString("detailError")
                    .isEmpty() && requestedDomain != null) requestedDomain.retryAt = System.nanoTime() + 500_000_000L;
                if (!session.equals(reply.getString("session")) || revision != reply.getLong("planRevision")
                    || generation != reply.getLong("detailGeneration")
                    || !reply.getString("detailError")
                        .isEmpty())
                    return;
                if ("resourceReady".equals(request.domain)) {
                    if (readyLease != null) readyLease.close();
                    readyPolicy = request.policy;
                    readyLease = request.lease;
                    request.lease = null;
                    ready = reply.getBoolean("ready") && !reply.getBoolean("detailTruncated");
                    return;
                }
                Domain domain = domains.get(request.domain);
                if (domain == null) return;
                NBTTagList rows = reply
                    .getTagList("rows", request.domain.equals("nbtKeys") || request.domain.equals("nbtPaths") ? 8 : 10);
                int offset = reply.getInteger("offset"), total = reply.getInteger("total");
                if (!reply.hasKey("rows", 9) || !reply.hasKey("total")
                    || offset != request.offset
                    || total < 0
                    || rows.tagCount() > 64
                    || (long) offset + rows.tagCount() > total) {
                    domain.retryAt = System.nanoTime() + 500_000_000L;
                    return;
                }
                HologramProcessGovernor.Lease index = HologramProcessGovernor
                    .tryRetain("snapshot.detailIndex", 192L * rows.tagCount() + 512);
                if (index == null) return;
                Page page = new Page(ownership, index.transfer(), retainedBytes + 192L * rows.tagCount() + 512);
                transferred = true;
                bytes += page.bytes;
                domain.total = total;
                domain.known = true;
                domain.sourceComplete = !reply.getBoolean("detailTruncated")
                    && (!reply.hasKey("complete") || reply.getBoolean("complete"));
                if (reply.hasKey("cellMeta", 10)) {
                    if (domain.metaPage != null) domain.metaPage.release();
                    domain.meta = reply.getCompoundTag("cellMeta");
                    domain.metaPage = page;
                    page.references++;
                }
                for (int i = 0; i < rows.tagCount(); i++) {
                    int at = offset + i;
                    NBTBase value = domain.name.equals("nbtKeys") || domain.name.equals("nbtPaths")
                        ? new NBTTagString(rows.getStringTagAt(i))
                        : rows.getCompoundTagAt(i);
                    Node node = new Node(domain, at, value, page);
                    String key = domain.name + ":" + at;
                    Node old = lru.put(key, node);
                    if (old != null) {
                        old.domain.rows.remove(old.index);
                        old.page.release();
                    }
                    domain.rows.put(at, node);
                    page.references++;
                }
                if (page.references == 0) page.release();
                while (lru.size() > MAX_ROWS || bytes > MAX_BYTES) {
                    if (lru.isEmpty()) break;
                    String first = lru.keySet()
                        .iterator()
                        .next();
                    Node old = lru.remove(first);
                    old.domain.rows.remove(old.index);
                    old.page.release();
                }
            } finally {
                request.close();
            }
        } finally {
            if (!transferred) close(ownership);
        }
    }

    private static void discard(String name) {
        Domain old = domains.remove(name);
        if (old == null) return;
        java.util.Iterator<Map.Entry<String, Node>> it = lru.entrySet()
            .iterator();
        while (it.hasNext()) {
            Node node = it.next()
                .getValue();
            if (node.domain == old) {
                it.remove();
                node.page.release();
            }
        }
        if (old.metaPage != null) old.metaPage.release();
    }

    private static void close(AutoCloseable value) {
        if (value != null) try {
            value.close();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static final class Domain {

        final String name;
        int total;
        boolean known, sourceComplete = true;
        long retryAt;
        final Map<Integer, Node> rows = new java.util.HashMap<>();
        NBTTagCompound meta = new NBTTagCompound();
        Page metaPage;

        Domain(String name, int total) {
            this.name = name;
            this.total = total;
            known = !name.startsWith("cell/");
        }
    }

    private static final class Node {

        final Domain domain;
        final int index;
        final NBTBase value;
        final Page page;

        Node(Domain d, int i, NBTBase v, Page p) {
            domain = d;
            index = i;
            value = v;
            page = p;
        }
    }

    private static final class Page {

        final AutoCloseable owner;
        final HologramProcessGovernor.Lease index;
        final long bytes;
        int references;
        boolean closed;

        Page(AutoCloseable o, HologramProcessGovernor.Lease i, long b) {
            owner = o;
            index = i;
            bytes = b;
        }

        void release() {
            if (closed) return;
            if (references > 0 && --references > 0) return;
            closed = true;
            HologramSnapshotDetailsClient.bytes -= bytes;
            close(owner);
            index.close();
        }
    }

    private static final class Pending implements AutoCloseable {

        final String domain;
        final int offset;
        final long started;
        final NBTTagCompound policy;
        HologramProcessGovernor.Lease lease;

        Pending(String d, int o, long s, NBTTagCompound p, HologramProcessGovernor.Lease l) {
            domain = d;
            offset = o;
            started = s;
            policy = p;
            lease = l;
        }

        public void close() {
            if (lease != null) lease.close();
            lease = null;
        }
    }

    private static final class View extends NBTTagList {

        final Domain domain;
        final int type;

        View(Domain d, int t) {
            domain = d;
            type = t;
        }

        public int tagCount() {
            return domain.total;
        }

        public int func_150303_d() {
            return type;
        }

        public NBTTagCompound getCompoundTagAt(int i) {
            Node row = domain.rows.get(i);
            if (row != null && row.value instanceof NBTTagCompound) return (NBTTagCompound) row.value;
            NBTTagCompound missing = new NBTTagCompound();
            missing.setBoolean("detailPending", true);
            missing.setBoolean("protected", true);
            missing.setString("key", "(loading)");
            return missing;
        }

        public String getStringTagAt(int i) {
            Node row = domain.rows.get(i);
            return row != null && row.value instanceof NBTTagString ? ((NBTTagString) row.value).func_150285_a_()
                : "(loading)";
        }
    }
}
