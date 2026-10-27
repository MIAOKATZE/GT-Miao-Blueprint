package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

/** Bounded, operation-owned static facts. Never a world/payment result or a whole-blueprint validity receipt. */
final class HologramAnalysisFacts implements AutoCloseable {

    private static final int MAX_ENTRIES = 256;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private final Map<Object, Entry> values = new LinkedHashMap<>(16, .75f, true);
    private final boolean enabled;
    private long bytes;
    private HologramAnalysisDocument.Stage domains;
    private final long started = HologramRuntimeMetrics.start();
    private boolean closed;
    long decoded, analyses;

    HologramAnalysisFacts() {
        this(!"false".equalsIgnoreCase(System.getProperty("gtmb.segment.analysisCache", "true")));
    }

    HologramAnalysisFacts(boolean enabled) {
        this.enabled = enabled;
    }

    void bind(HologramPresets.Preset source) throws IOException {
        if (domains == null) domains = new HologramAnalysisDocument.Stage(source, enabled);
    }

    boolean coverage(HologramPresets.Cell cell) throws IOException {
        return domains != null && domains.coverage(cell);
    }

    NBTTagCompound certificate(HologramPresets.Cell cell, HologramSegmentContract.Domain domain, String kind,
        Object[] dependencies, boolean persistent, HologramAnalysisDocument.Work work) throws IOException {
        return domains == null ? work.compute() : domains.value(cell, domain, kind, dependencies, persistent, work);
    }

    void publish() {
        if (domains != null) domains.publish();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (domains != null) domains.close();
        values.clear();
        bytes = 0;
        HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.SEGMENT_ANALYSIS, started);
    }

    void relations(java.util.List<HologramCoordinatePolicies.Relation> relations) throws IOException {
        if (domains != null) domains.relations(relations);
    }

    void networkNode(HologramPresets.Cell cell, NBTTagCompound entry) throws IOException {
        if (domains != null) domains.networkNode(cell, entry);
    }

    void networks() {
        if (domains != null) domains.networks();
    }

    String fingerprint(HologramAnalysisDocument.StringWork work) throws IOException {
        return domains == null ? work.compute() : domains.fingerprint(work);
    }

    /** Trusted internal readers only. Adapters and writers receive independent copies. */
    NBTTagCompound read(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) return null;
        Entry cached = values.get(encoded);
        if (cached != null) return (NBTTagCompound) cached.value;
        HologramBlueprintNbt.ExactBlob blob = HologramSnapshotDataContext.active() ? null
            : HologramBlueprintNbt.exact(encoded);
        int expanded = blob == null ? HologramSnapshotDataContext.expandedBytes(encoded) : blob.expandedBytes();
        long cost = cost(encoded, expanded);
        boolean retain = reserve(cost);
        NBTTagCompound tag = blob == null ? HologramBlueprintNbt.decode(encoded) : blob.copyForEdit();
        decoded++;
        if (retain) put(encoded, tag, cost);
        return tag;
    }

    NBTTagCompound copyForEdit(String encoded) throws IOException {
        NBTTagCompound tag = read(encoded);
        return tag == null ? null : (NBTTagCompound) tag.copy();
    }

    /** Analysis owns its payload and returns a copy from payload(); callers only read its facts. */
    HologramAeBlueprint.Analysis ae(Block block, int metadata, String encoded, NBTTagCompound payload)
        throws IOException {
        if (HologramSnapshotDataContext.active())
            throw new IllegalStateException("Native AE analysis requires an owner continuation");
        AeKey key = new AeKey(block, metadata, encoded);
        Entry cached = values.get(key);
        if (cached != null) return (HologramAeBlueprint.Analysis) cached.value;
        Entry input = values.get(encoded);
        long cost = encoded == null || encoded.isEmpty() ? 8192 : input == null ? MAX_BYTES + 1 : input.cost + 4096;
        boolean retain = reserve(cost);
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, metadata, payload);
        analyses++;
        if (retain) put(key, analysis, cost);
        return analysis;
    }

    private static long cost(String encoded, int expanded) {
        // Deliberately conservative object estimate; admission bounds this operation's extra retained copies.
        // The authoritative logical expanded budget is still charged per reference by blueprint validation.
        return 4096L + 2L * encoded.length() + 64L * expanded;
    }

    private boolean reserve(long cost) {
        if (!enabled || cost > MAX_BYTES) return false;
        Iterator<Map.Entry<Object, Entry>> oldest = values.entrySet()
            .iterator();
        while ((values.size() >= MAX_ENTRIES || bytes > MAX_BYTES - cost) && oldest.hasNext()) {
            bytes -= oldest.next()
                .getValue().cost;
            oldest.remove();
        }
        return true;
    }

    private void put(Object key, Object value, long cost) {
        values.put(key, new Entry(value, cost));
        bytes += cost;
    }

    private static final class Entry {

        final Object value;
        final long cost;

        Entry(Object value, long cost) {
            this.value = value;
            this.cost = cost;
        }
    }

    private static final class AeKey {

        final Block block;
        final int metadata;
        final String encoded;
        final long generation = com.miaokatze.gtmb.api.ResourceContainers.generation();

        AeKey(Block block, int metadata, String encoded) {
            this.block = block;
            this.metadata = metadata;
            this.encoded = encoded;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(block), metadata, encoded, generation);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AeKey)) return false;
            AeKey key = (AeKey) other;
            return block == key.block && metadata == key.metadata
                && generation == key.generation
                && Objects.equals(encoded, key.encoded);
        }
    }
}
