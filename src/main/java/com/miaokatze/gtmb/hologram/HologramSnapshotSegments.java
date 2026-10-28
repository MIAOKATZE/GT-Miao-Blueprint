package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Negotiated transport projection only; public content/artifact hashes keep their original codec. */
public final class HologramSnapshotSegments {

    public static final int VERSION = 2;
    private static final int MAGIC = 0x47545332;
    private static final int PAGE_CELLS = 256, MAX_SEGMENTS = 4096;
    private static final int RAW_LIMIT = 128 * 1024 * 1024;
    private static final int CACHE_RAW_BYTES = 8 * 1024 * 1024, CACHE_ENTRIES = 128;
    public static final String RECEIPT = "gtmbTransportSegments";

    private HologramSnapshotSegments() {}

    /** Peer acknowledgements are fenced by the owning connection and session in HologramNetwork. */
    static final class Peer {

        final Set<String> confirmed = new LinkedHashSet<>();
        final Set<String> offered = new LinkedHashSet<>();

        synchronized Set<String> baseline() {
            return new LinkedHashSet<>(confirmed);
        }

        synchronized void offered(List<String> hashes) {
            offered.addAll(hashes);
            while (offered.size() > CACHE_ENTRIES * 2) offered.remove(
                offered.iterator()
                    .next());
        }

        synchronized void acknowledge(NBTTagList hashes) {
            for (int i = 0; i < Math.min(hashes.tagCount(), CACHE_ENTRIES); i++) {
                String hash = hashes.getStringTagAt(i);
                if (offered.contains(hash)) confirmed.add(hash);
            }
            while (confirmed.size() > CACHE_ENTRIES) confirmed.remove(
                confirmed.iterator()
                    .next());
        }

        synchronized void clear() {
            confirmed.clear();
            offered.clear();
        }
    }

    static final class Encoded {

        final byte[] bytes;
        final List<String> hashes;

        Encoded(byte[] bytes, List<String> hashes) {
            this.bytes = bytes;
            this.hashes = hashes;
        }
    }

    /** One producer owns the source. References are emitted only for explicitly acknowledged exact bytes. */
    static Encoded encode(NBTTagCompound source, Set<String> confirmed, BooleanSupplier current, LongConsumer progress)
        throws IOException {
        NBTTagCompound header = new NBTTagCompound();
        for (Object keyObject : source.func_150296_c()) {
            String key = (String) keyObject;
            if (!"cells".equals(key)) header.setTag(
                key,
                source.getTag(key)
                    .copy());
        }
        NBTTagList cells = source.getTagList("cells", 10);
        int count = (cells.tagCount() + PAGE_CELLS - 1) / PAGE_CELLS;
        if (count > MAX_SEGMENTS) throw new IOException("snapshot segment count budget");
        long[] raw = { 0 };
        long[] totalRaw = { 0 };
        LongConsumer measured = written -> progress.accept(totalRaw[0] + written);
        byte[] manifest = HologramSnapshotCodec.compress(header, current, written -> {
            raw[0] = written;
            measured.accept(written);
        });
        totalRaw[0] = raw[0];
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<String> hashes = new ArrayList<>();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(1);
            out.writeInt((int) raw[0]);
            out.writeInt(manifest.length);
            out.write(manifest);
            out.writeInt(cells.tagCount());
            out.writeInt(count);
            for (int page = 0; page < count; page++) {
                if (!current.getAsBoolean()) throw new java.io.InterruptedIOException("snapshot segment retired");
                NBTTagCompound segment = new NBTTagCompound();
                NBTTagList records = new NBTTagList();
                int end = Math.min(cells.tagCount(), (page + 1) * PAGE_CELLS);
                for (int i = page * PAGE_CELLS; i < end; i++) records.appendTag(cells.getCompoundTagAt(i));
                segment.setTag("cells", records);
                raw[0] = 0;
                byte[] encoded = HologramSnapshotCodec.compress(segment, current, written -> {
                    raw[0] = written;
                    measured.accept(written);
                });
                totalRaw[0] += raw[0];
                if (totalRaw[0] > RAW_LIMIT) throw new IOException("snapshot expanded segment budget");
                byte[] digest = HologramLibraryProtocol.digest()
                    .digest(encoded);
                String identity = hex(digest);
                hashes.add(identity);
                out.write(digest);
                out.writeInt((int) raw[0]);
                boolean reference = confirmed.contains(identity);
                out.writeInt(reference ? 0 : encoded.length);
                if (!reference) out.write(encoded);
                if (bytes.size() > HologramSnapshotAssembly.MAX_BYTES)
                    throw new IOException("snapshot segment byte budget");
            }
        }
        return new Encoded(bytes.toByteArray(), hashes);
    }

    /** Only this decoder owns cached mutable NBT; every published projection receives independent copies. */
    public static final class Decoder implements AutoCloseable {

        private static final long CACHE_RETAINED_BYTES = 32L * 1024 * 1024;
        private final Map<String, Segment> cache = new LinkedHashMap<>(16, .75f, true);
        private int cachedRaw;
        private long cachedEstimate;

        public synchronized void clear() {
            for (Segment segment : cache.values()) segment.close();
            cache.clear();
            cachedRaw = 0;
            cachedEstimate = 0;
        }

        public void close() {
            clear();
        }

        public synchronized NBTTagCompound decode(byte[] bytes) throws IOException {
            return decodeProjection(bytes, () -> true, false, true).value;
        }

        public synchronized NBTTagCompound decode(byte[] bytes, BooleanSupplier current) throws IOException {
            return decodeProjection(bytes, current, false, true).value;
        }

        public synchronized HologramSnapshotCodec.OwnedSnapshot decodeOwned(byte[] bytes, BooleanSupplier current)
            throws IOException {
            try {
                return decodeProjection(bytes, current, true, true);
            } catch (HologramSnapshotCodec.BusyException quota) {
                // Derived cache bytes never prevent the authoritative current projection from admission.
                clear();
                try {
                    return decodeProjection(bytes, current, true, false);
                } catch (HologramSnapshotCodec.BusyException uncached) {
                    long required = requiredProjectionBytes(bytes, current);
                    if (required > HologramProcessGovernor.retainedByteLimit())
                        throw new HologramSnapshotCodec.BusyException(uncached.getMessage(), required);
                    throw uncached;
                }
            }
        }

        /** Only failed uncached admission needs this pass; successful frames keep their existing two NBT passes. */
        static long requiredProjectionBytes(byte[] bytes, BooleanSupplier current) throws IOException {
            if (bytes.length < 4 || readInt(bytes) != MAGIC)
                return HologramSnapshotCodec.measure(bytes, 0, bytes.length, RAW_LIMIT, current).retainedEstimate;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                input.readInt();
                int version = input.readInt();
                if (version != 1 && version != 2) throw new IOException("snapshot segment version");
                int raw = positive(input.readInt(), RAW_LIMIT);
                int manifestLength = positive(input.readInt(), bytes.length);
                if (manifestLength > input.available()) throw new IOException("snapshot segment payload budget");
                int manifestOffset = bytes.length - input.available();
                HologramSnapshotCodec.Decoded header = HologramSnapshotCodec
                    .measure(bytes, manifestOffset, manifestLength, raw, current);
                if (header.rawBytes != raw) throw new IOException("snapshot manifest shape");
                input.skipBytes(manifestLength);
                long estimate = header.retainedEstimate;
                long peak = manifestLength + estimate;
                int cellCount = input.readInt(), count = input.readInt();
                if (cellCount < 0 || count < 0
                    || count > MAX_SEGMENTS
                    || version == 1 && count != (cellCount + (long) PAGE_CELLS - 1) / PAGE_CELLS)
                    throw new IOException("snapshot segment count");
                for (int page = 0; page < count; page++) {
                    if (!current.getAsBoolean())
                        throw new java.util.concurrent.CancellationException("snapshot segment retired");
                    byte[] hash = payload(input, 32);
                    int segmentRaw = positive(input.readInt(), RAW_LIMIT - raw);
                    raw += segmentRaw;
                    int length = input.readInt();
                    if (length < 0 || length > input.available()) throw new IOException("snapshot segment length");
                    // This pass only proves infeasibility. A missing reference needs the normal decoder's
                    // exact session once admission permits it; an incomplete lower bound cannot reject it.
                    if (length == 0) return Math.max(estimate, peak);
                    int offset = bytes.length - input.available();
                    java.security.MessageDigest digest = HologramLibraryProtocol.digest();
                    digest.update(bytes, offset, length);
                    if (!Arrays.equals(hash, digest.digest())) throw new IOException("snapshot segment digest");
                    HologramSnapshotCodec.Decoded measured = HologramSnapshotCodec
                        .measure(bytes, offset, length, segmentRaw, current);
                    if (measured.rawBytes != segmentRaw) throw new IOException("snapshot segment shape");
                    peak = Math.max(peak, estimate + length + measured.retainedEstimate);
                    estimate += measured.retainedEstimate;
                    if (measured.compactRows && measured.cellCount >= 0)
                        estimate += HologramSnapshotRows.projectionOverhead(measured.cellCount);
                    input.skipBytes(length);
                }
                if (input.available() != 0) throw new IOException("snapshot segment trailing bytes");
                if (version == 2) estimate += 16L * cellCount + 128;
                return Math.max(estimate, peak);
            }
        }

        private HologramSnapshotCodec.OwnedSnapshot decodeProjection(byte[] bytes, BooleanSupplier current,
            boolean owned, boolean cacheAllowed) throws IOException {
            if (bytes.length < 4 || readInt(bytes) != MAGIC) {
                if (owned) return HologramSnapshotCodec.decodeOwned(bytes, current);
                return new HologramSnapshotCodec.OwnedSnapshot(HologramSnapshotCodec.decode(bytes, current));
            }
            HologramSnapshotCodec.OwnedSnapshot owner = null;
            boolean complete = false;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                input.readInt();
                int version = input.readInt();
                if (version != 1 && version != 2) throw new IOException("snapshot segment version");
                int raw = positive(input.readInt(), RAW_LIMIT);
                HologramSnapshotCodec.Decoded decoded;
                try (Payload manifest = copiedPayload(input, positive(input.readInt(), bytes.length), owned)) {
                    decoded = decodePayload(manifest, raw, current, owned);
                }
                owner = new HologramSnapshotCodec.OwnedSnapshot(decoded.tag);
                owner.add(decoded.lease, decoded.retainedEstimate);
                if (decoded.rawBytes != raw || decoded.tag.hasKey("cells"))
                    throw new IOException("snapshot manifest shape");
                NBTTagCompound result = decoded.tag;
                int cellCount = input.readInt(), count = input.readInt();
                int[] sizes = version == 2 ? result.getIntArray("gtmbTransportPageSizes") : null;
                int[] order = version == 2 ? result.getIntArray("gtmbTransportOrder") : null;
                if (cellCount < 0 || count < 0
                    || count > MAX_SEGMENTS
                    || version == 1 && count != (cellCount + (long) PAGE_CELLS - 1) / PAGE_CELLS
                    || version == 2 && (sizes.length != count || order.length != cellCount))
                    throw new IOException("snapshot segment count");
                if (version == 2) {
                    long total = 0;
                    for (int size : sizes) {
                        if (size < 1 || size > PAGE_CELLS) throw new IOException("snapshot stable page size");
                        total += size;
                    }
                    if (total != cellCount) throw new IOException("snapshot stable page total");
                }
                NBTTagList cells = new NBTTagList();
                List<String> retained = new ArrayList<>();
                for (int page = 0; page < count; page++) {
                    if (!current.getAsBoolean())
                        throw new java.util.concurrent.CancellationException("snapshot segment retired");
                    byte[] hash = payload(input, 32);
                    String identity = hex(hash);
                    int expected = version == 2 ? sizes[page] : Math.min(PAGE_CELLS, cellCount - page * PAGE_CELLS);
                    int segmentRaw = positive(input.readInt(), RAW_LIMIT - raw);
                    raw += segmentRaw;
                    int length = input.readInt();
                    if (length < 0 || length > input.available()) throw new IOException("snapshot segment length");
                    Segment segment = cacheAllowed ? cache.get(identity) : null;
                    boolean cached = segment != null;
                    if (length == 0) {
                        if (segment == null || segment.rawBytes != segmentRaw)
                            throw new MissingSegmentException(result.getString("session"));
                    } else {
                        try (Payload encoded = copiedPayload(input, length, owned)) {
                            if (!Arrays.equals(
                                hash,
                                HologramLibraryProtocol.digest()
                                    .digest(encoded.bytes)))
                                throw new IOException("snapshot segment digest");
                            if (segment == null || segment.rawBytes != segmentRaw) {
                                HologramSnapshotCodec.Decoded part = decodePayload(encoded, segmentRaw, current, owned);
                                boolean compact = HologramSnapshotRows.isCompactPage(part.tag);
                                if (part.rawBytes != segmentRaw || !part.tag.hasKey("cells", 9)
                                    || !compact && part.tag.func_150296_c()
                                        .size() != 1) {
                                    if (part.lease != null) part.lease.close();
                                    throw new IOException("snapshot segment shape");
                                }
                                if (compact) try {
                                    HologramSnapshotRows.validatePage(part.tag, expected);
                                } catch (IOException invalid) {
                                    if (part.lease != null) part.lease.close();
                                    throw invalid;
                                }
                                segment = new Segment(part.tag, segmentRaw, part.retainedEstimate, part.lease);
                                cached = cacheAllowed && segmentRaw <= CACHE_RAW_BYTES
                                    && part.retainedEstimate <= CACHE_RETAINED_BYTES;
                                if (cached) {
                                    while (!cache.isEmpty()
                                        && (cache.size() >= CACHE_ENTRIES || segmentRaw > CACHE_RAW_BYTES - cachedRaw
                                            || part.retainedEstimate > CACHE_RETAINED_BYTES - cachedEstimate))
                                        evictOldest();
                                    Segment previous = cache.put(identity, segment);
                                    if (previous != null) {
                                        cachedRaw -= previous.rawBytes;
                                        cachedEstimate -= previous.estimate;
                                        previous.close();
                                    }
                                    cachedRaw += segmentRaw;
                                    cachedEstimate += part.retainedEstimate;
                                }
                            }
                        }
                    }
                    if (segment.cells.tagCount() != expected) {
                        if (!cached) segment.close();
                        throw new IOException("snapshot segment cell count");
                    }
                    long projectionOverhead = HologramSnapshotRows.isCompactPage(segment.root)
                        ? HologramSnapshotRows.projectionOverhead(segment.root)
                        : 0;
                    if (cached) {
                        if (owned && segment.lease == null)
                            segment.lease = admission("snapshot.segmentCache", segment.estimate).transfer();
                        HologramProcessGovernor.Lease projection = owned
                            ? admission("snapshot.segmentProjection", segment.estimate + projectionOverhead)
                            : null;
                        owner.add(projection, segment.estimate + projectionOverhead);
                    } else {
                        // An uncached segment is already private; transfer it instead of allocating another full copy.
                        owner.add(segment.lease, segment.estimate);
                        segment.lease = null;
                        if (projectionOverhead > 0) owner.add(
                            owned ? admission("snapshot.segmentBindings", projectionOverhead) : null,
                            projectionOverhead);
                    }
                    NBTTagList projection = projectionOverhead > 0 ? HologramSnapshotRows.project(segment.root, cached)
                        : segment.cells;
                    for (int i = 0; i < expected; i++) cells.appendTag(
                        cached && projectionOverhead == 0 ? projection.getCompoundTagAt(i)
                            .copy() : projection.getCompoundTagAt(i));
                    retained.add(identity);
                }
                if (input.available() != 0) throw new IOException("snapshot segment trailing bytes");
                if (version == 2) {
                    HologramProcessGovernor.Lease reorder = owned ? admission("snapshot.order", 16L * cellCount + 128)
                        : null;
                    owner.add(reorder, 16L * cellCount + 128);
                    boolean[] seen = new boolean[cellCount];
                    NBTTagList ordered = new NBTTagList();
                    for (int at : order) {
                        if (at < 0 || at >= cellCount || seen[at]) throw new IOException("snapshot stable order");
                        seen[at] = true;
                        ordered.appendTag(cells.getCompoundTagAt(at));
                    }
                    cells = ordered;
                    result.removeTag("gtmbTransportPageSizes");
                    result.removeTag("gtmbTransportOrder");
                }
                result.setTag("cells", cells);
                NBTTagList receipts = new NBTTagList();
                for (String hash : retained) if (cache.containsKey(hash)) receipts.appendTag(new NBTTagString(hash));
                result.setTag(RECEIPT, receipts);
                complete = true;
                return owner;
            } catch (HologramSnapshotCodec.BusyException quota) {
                throw accumulatedQuota(owner == null ? 0 : owner.retainedEstimate, quota);
            } finally {
                if (!complete && owner != null) owner.close();
            }
        }

        static HologramSnapshotCodec.BusyException accumulatedQuota(long retained,
            HologramSnapshotCodec.BusyException quota) {
            return quota.requestedBytes == 0 || retained == 0 ? quota
                : new HologramSnapshotCodec.BusyException(quota.getMessage(), retained + quota.requestedBytes);
        }

        private HologramProcessGovernor.Lease admission(String owner, long bytes)
            throws HologramSnapshotCodec.BusyException {
            HologramProcessGovernor.Lease lease = HologramProcessGovernor.tryRetain(owner, bytes);
            if (lease == null) throw new HologramSnapshotCodec.BusyException(
                "Snapshot retained-memory quota is busy; retry the current view",
                bytes);
            return lease;
        }

        private void evictOldest() {
            String oldest = cache.keySet()
                .iterator()
                .next();
            Segment removed = cache.remove(oldest);
            cachedRaw -= removed.rawBytes;
            cachedEstimate -= removed.estimate;
            removed.close();
        }
    }

    public static final class MissingSegmentException extends IOException {

        public final String session;

        MissingSegmentException(String session) {
            super("snapshot segment reference was evicted; full transfer required");
            this.session = session;
        }
    }

    private static final class Segment {

        final NBTTagCompound root;
        final NBTTagList cells;
        final int rawBytes;
        final long estimate;
        HologramProcessGovernor.Lease lease;

        Segment(NBTTagCompound root, int rawBytes, long estimate, HologramProcessGovernor.Lease lease) {
            this.root = root;
            cells = root.getTagList("cells", 10);
            this.rawBytes = rawBytes;
            this.estimate = estimate;
            this.lease = lease == null ? null : lease.transfer();
        }

        void close() {
            if (lease != null) lease.close();
            lease = null;
        }
    }

    private static Payload copiedPayload(DataInputStream input, int length, boolean owned) throws IOException {
        if (length < 0 || length > input.available()) throw new IOException("snapshot segment payload budget");
        HologramProcessGovernor.Lease lease = owned
            ? HologramProcessGovernor.tryRetain("snapshot.compressedCopy", length)
            : null;
        if (owned && lease == null) throw new HologramSnapshotCodec.BusyException(
            "Snapshot copy-memory quota is busy; retry the current view",
            length);
        try {
            return new Payload(payload(input, length), lease);
        } catch (RuntimeException | IOException | Error failure) {
            if (lease != null) lease.close();
            throw failure;
        }
    }

    private static HologramSnapshotCodec.Decoded decodePayload(Payload payload, int raw, BooleanSupplier current,
        boolean owned) throws IOException {
        try {
            return HologramSnapshotCodec.decodeMeasured(payload.bytes, raw, 4L * raw, current, owned);
        } catch (HologramSnapshotCodec.BusyException quota) {
            throw Decoder.accumulatedQuota(payload.lease == null ? 0 : payload.bytes.length, quota);
        }
    }

    private static final class Payload implements AutoCloseable {

        final byte[] bytes;
        final HologramProcessGovernor.Lease lease;

        Payload(byte[] bytes, HologramProcessGovernor.Lease lease) {
            this.bytes = bytes;
            this.lease = lease;
        }

        public void close() {
            if (lease != null) lease.close();
        }
    }

    private static int positive(int value, int limit) throws IOException {
        if (value <= 0 || value > limit) throw new IOException("snapshot segment expanded budget");
        return value;
    }

    private static byte[] payload(DataInputStream input, int length) throws IOException {
        if (length < 0 || length > input.available()) throw new IOException("snapshot segment payload budget");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static int readInt(byte[] bytes) {
        return (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | bytes[3] & 255;
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits[(bytes[i] >>> 4) & 15];
            result[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(result);
    }
}
