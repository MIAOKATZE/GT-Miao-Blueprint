package com.miaokatze.gtmb.hologram;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Stable P1 pages plus exact detached projection input. Hashes/deflate run once per exact page producer. */
final class HologramSnapshotSegmentEncoding {

    private static final int MAGIC = 0x47545332, MAX_ENTRIES = 128;
    private static final long MAX_CACHE_BYTES = 32L << 20;
    private static final Map<Address, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private static long cachedBytes;
    static final AtomicLong PRODUCERS = new AtomicLong(), REUSED = new AtomicLong();

    private HologramSnapshotSegmentEncoding() {}

    static void clear() {
        synchronized (cache) {
            for (Entry entry : cache.values()) entry.release();
            cache.clear();
            cachedBytes = 0;
        }
    }

    static Result encode(NBTTagCompound source, HologramTransportPageLayout layout, Set<String> confirmed,
        BooleanSupplier current, LongConsumer progress) throws Exception {
        List<Group> groups = new ArrayList<>();
        NBTTagCompound header = new NBTTagCompound();
        try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor.acquireHeavyCpu("snapshot.manifest")) {
            for (Object keyObject : source.func_150296_c()) {
                String key = (String) keyObject;
                if (!"cells".equals(key)) header.setTag(
                    key,
                    source.getTag(key)
                        .copy());
            }
            NBTTagList rows = source.getTagList("cells", 10);
            boolean compactRows = rows.tagCount() > 0 && HologramSnapshotRows.compactRow(rows.getCompoundTagAt(0));
            if (layout == null || layout.size() != rows.tagCount())
                throw new IOException("snapshot stable layout missing");
            Map<Address, Group> stable = new LinkedHashMap<>();
            for (int i = 0; i < rows.tagCount(); i++) {
                HologramTransportPageLayout.Row row = layout.row(i);
                Address address = new Address(row, compactRows);
                Group group = stable.get(address);
                if (group == null) {
                    group = new Group(address);
                    stable.put(address, group);
                }
                group.rows.add(rows.getCompoundTagAt(i));
                group.ids.add(row.cellId);
                group.indices.add(i);
            }
            groups.addAll(stable.values());
            if (groups.size() > 4096) throw new IOException("snapshot stable page count budget");
            int[] order = new int[rows.tagCount()], sizes = new int[groups.size()];
            int position = 0, page = 0;
            for (Group group : groups) {
                if (group.rows.size() > 256) throw new IOException("snapshot stable page size");
                sizes[page++] = group.rows.size();
                for (int index : group.indices) order[index] = position++;
            }
            header.setIntArray("gtmbTransportOrder", order);
            header.setIntArray("gtmbTransportPageSizes", sizes);
        }
        List<String> hashes = new ArrayList<>();
        long expanded = 0;
        try (HologramSnapshotBytes.Output bytes = new HologramSnapshotBytes.Output()) {
            DataOutputStream out = new DataOutputStream(bytes);
            HologramSnapshotBytes manifest;
            long[] manifestRaw = { 0 };
            try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor.acquireHeavyCpu("snapshot.manifest")) {
                manifest = HologramSnapshotCodec.compressOwned(header, current, n -> manifestRaw[0] = n);
            }
            expanded = manifestRaw[0];
            try {
                out.writeInt(MAGIC);
                out.writeInt(2);
                out.writeInt((int) expanded);
                out.writeInt(manifest.bytes.length);
                out.write(manifest.bytes);
            } finally {
                manifest.close();
            }
            out.writeInt(
                source.getTagList("cells", 10)
                    .tagCount());
            out.writeInt(groups.size());
            for (Group group : groups) {
                if (!current.getAsBoolean())
                    throw new java.util.concurrent.CancellationException("snapshot page retired");
                Entry entry;
                try (
                    HologramProcessGovernor.Lease cpu = HologramProcessGovernor.acquireHeavyCpu("snapshot.pageInput")) {
                    entry = input(group);
                }
                Entry pinned = entry;
                try {
                    if (entry.encoded == null) {
                        HologramProcessGovernor.Subscription<Entry> subscription = HologramProcessGovernor.singleFlight(
                            entry,
                            Long.MIN_VALUE + group.address.authority,
                            new Producer(entry),
                            Runnable::run);
                        // Direct bounded worker execution returns only after its producer completes. No owner thread
                        // waits.
                        // A concurrent subscriber on another worker may find an existing producer; yield this job for
                        // retry.
                        if (!subscription.future()
                            .isDone()) throw new PendingPage(subscription);
                        try {
                            entry = subscription.future()
                                .getNow(null);
                        } catch (java.util.concurrent.CompletionException failed) {
                            Throwable cause = failed.getCause();
                            if (cause instanceof Exception) throw (Exception) cause;
                            throw failed;
                        } finally {
                            subscription.close();
                        }
                    }
                    if (entry == null || entry.encoded == null) throw new IOException("snapshot page producer failed");
                    expanded += entry.raw;
                    if (expanded > 128L * 1024 * 1024) throw new LegacyFallback();
                    hashes.add(entry.hashText);
                    out.write(entry.hash);
                    out.writeInt((int) entry.raw);
                    boolean reference = confirmed.contains(entry.hashText);
                    out.writeInt(reference ? 0 : entry.encoded.bytes.length);
                    if (!reference) out.write(entry.encoded.bytes);
                    progress.accept(expanded);
                } finally {
                    pinned.release();
                }
            }
            out.flush();
            return new Result(bytes.finish(), hashes);
        }
    }

    private static Entry input(Group group) throws IOException {
        synchronized (cache) {
            Entry previous = cache.get(group.address);
            if (previous != null && previous.matches(group)) {
                previous.references++;
                REUSED.incrementAndGet();
                return previous;
            }
            NBTTagCompound root;
            if (group.address.compactRows) root = HologramSnapshotRows.compactPage(group.rows);
            else {
                root = new NBTTagCompound();
                NBTTagList rows = new NBTTagList();
                for (NBTTagCompound row : group.rows) rows.appendTag(row);
                root.setTag("cells", rows);
            }
            long estimate = HologramSnapshotCodec.retainedEstimate(root);
            while (!cache.isEmpty() && (cache.size() >= MAX_ENTRIES || estimate > MAX_CACHE_BYTES - cachedBytes))
                evict();
            HologramProcessGovernor.Lease admitted = HologramProcessGovernor.tryRetain("snapshot.pageInput", estimate);
            if (admitted == null)
                throw new HologramSnapshotCodec.BusyException("Snapshot page-cache quota is busy; retry");
            Entry entry;
            try {
                entry = new Entry(
                    group.address,
                    group.ids,
                    (NBTTagCompound) root.copy(),
                    estimate,
                    admitted.transfer());
            } catch (RuntimeException | Error failure) {
                admitted.close();
                throw failure;
            }
            if (estimate <= MAX_CACHE_BYTES) {
                Entry replaced = cache.put(group.address, entry);
                if (replaced != null) {
                    cachedBytes -= replaced.estimate;
                    replaced.release();
                }
                cachedBytes += estimate;
                entry.references++;
            }
            return entry;
        }
    }

    private static void evict() {
        Address oldest = cache.keySet()
            .iterator()
            .next();
        Entry entry = cache.remove(oldest);
        cachedBytes -= entry.estimate;
        entry.release();
    }

    static final class Result implements AutoCloseable {

        final HologramSnapshotBytes bytes;
        final List<String> hashes;

        Result(HologramSnapshotBytes bytes, List<String> hashes) {
            this.bytes = bytes;
            this.hashes = hashes;
        }

        public void close() {
            bytes.close();
        }
    }

    static final class LegacyFallback extends IOException {

        LegacyFallback() {
            super("stable wrapper overhead requires the original complete projection");
        }
    }

    static final class PendingPage extends IOException {

        final HologramProcessGovernor.Subscription<Entry> subscription;

        PendingPage(HologramProcessGovernor.Subscription<Entry> subscription) {
            super("snapshot shared page producer pending");
            this.subscription = subscription;
        }
    }

    private static final class Group {

        final Address address;
        final List<NBTTagCompound> rows = new ArrayList<>();
        final List<Long> ids = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        Group(Address address) {
            this.address = address;
        }
    }

    private static final class Address {

        final long authority, page, report, mesh, reportAuthority, meshAuthority;
        final boolean compactRows;

        Address(HologramTransportPageLayout.Row row, boolean compactRows) {
            this.compactRows = compactRows;
            authority = row.authority;
            page = row.pageId;
            report = row.report == null ? 0 : row.report.input;
            mesh = row.mesh == null ? 0 : row.mesh.input;
            reportAuthority = row.report == null ? 0 : row.report.authority;
            meshAuthority = row.mesh == null ? 0 : row.mesh.authority;
        }

        public int hashCode() {
            int hash = Long.hashCode(authority);
            hash = 31 * hash + Long.hashCode(page);
            hash = 31 * hash + Long.hashCode(mesh);
            return 31 * (31 * hash + Long.hashCode(meshAuthority)) + Boolean.hashCode(compactRows);
        }

        public boolean equals(Object value) {
            if (!(value instanceof Address)) return false;
            Address other = (Address) value;
            return authority == other.authority && page == other.page
                && mesh == other.mesh
                && meshAuthority == other.meshAuthority
                && compactRows == other.compactRows;
        }
    }

    private static final class Entry {

        final Address address;
        final long[] ids;
        final NBTTagCompound frozen;
        final long estimate;
        final HologramProcessGovernor.Lease input;
        int references = 1;
        volatile HologramSnapshotBytes encoded;
        byte[] hash;
        String hashText;
        long raw;

        Entry(Address address, List<Long> ids, NBTTagCompound frozen, long estimate,
            HologramProcessGovernor.Lease input) {
            this.address = address;
            this.ids = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) this.ids[i] = ids.get(i);
            this.frozen = frozen;
            this.estimate = estimate;
            this.input = input;
        }

        boolean matches(Group group) {
            if (ids.length != group.ids.size()) return false;
            for (int i = 0; i < ids.length; i++)
                if (ids[i] != group.ids.get(i) || !HologramSnapshotRows.sameRow(frozen, i, group.rows.get(i)))
                    return false;
            return true;
        }

        void release() {
            synchronized (cache) {
                if (--references != 0) return;
                input.close();
                if (encoded != null) encoded.close();
            }
        }
    }

    private static final class Producer implements Callable<Entry>, AutoCloseable {

        private Entry entry;

        Producer(Entry entry) {
            synchronized (cache) {
                this.entry = entry;
                entry.references++;
            }
        }

        public Entry call() throws Exception {
            synchronized (entry) {
                if (entry.encoded == null) {
                    PRODUCERS.incrementAndGet();
                    long[] raw = { 0 };
                    HologramSnapshotBytes encoded = HologramSnapshotCodec.compressOwned(
                        entry.frozen,
                        () -> !HologramProcessGovernor.currentFlightCancelled(),
                        n -> raw[0] = n);
                    entry.raw = raw[0];
                    entry.hash = HologramLibraryProtocol.digest()
                        .digest(encoded.bytes);
                    entry.hashText = hex(entry.hash);
                    entry.encoded = encoded;
                }
                return entry;
            }
        }

        public void close() {
            if (entry != null) entry.release();
            entry = null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16))
            .append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}
