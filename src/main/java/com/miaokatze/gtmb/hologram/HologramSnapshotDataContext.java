package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

/** Main-resolved NBT values. Detached consumers can copy known values and can never resolve new registry identities. */
final class HologramSnapshotDataContext implements AutoCloseable {

    private static final ThreadLocal<HologramSnapshotDataContext> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OWNER_FREEZE = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final Object descriptorAuthority = new Object();
    private static final long DECODED_BYTES = 4L << 20;
    private static final int INPUT_BATCH = 2048;
    private final Map<String, Decoded> decoded = new java.util.LinkedHashMap<>(16, .75f, true);
    private final Map<String, Integer> expanded = new HashMap<>();
    private final HologramRetainedCache.Owner retained = HologramRetainedCache.watch(this);
    private HologramBlueprintNbt.FrozenItemPatch itemPatch;
    private long decodedBytes;
    // All non-evictable context leases, including optional AE rows once retained for the frozen consumer.
    private long fixedBytes;
    private boolean decodedDisabled;
    private HologramPresets.Cell lastAeCell;
    private BlockIdentity lastAeIdentity;
    private int references = 1;
    private boolean ownerClosed;
    private int aeRows, resourceRows;
    private long resourceBytes;
    private final Map<ResourceIdentity, HologramResourceReviewFact> resourceReviews = new HashMap<>();
    private final java.util.List<HologramPresets.Cell> aeIssueCells = new java.util.ArrayList<>();
    private HologramAnalysisDocument certificates;
    private final Map<BlockIdentity, FrozenBlock> blocks = new HashMap<>();
    private final Map<HologramNbtObligations.Descriptor, HologramNbtObligations.Descriptor> descriptors = new HashMap<>();
    private final long resourceEpoch = com.miaokatze.gtmb.api.ResourceContainers.generation();
    private final long nativeEpoch = HologramNativePlacement.generation();

    static boolean active() {
        return ACTIVE.get() != null;
    }

    static boolean freezingOnOwner() {
        return Boolean.TRUE.equals(OWNER_FREEZE.get());
    }

    private OwnerFreeze ownerFreeze() {
        if (Thread.currentThread() != owner || active() || ownerClosed)
            throw new IllegalStateException("Metadata freeze scope requires its live owner thread");
        return new OwnerFreeze();
    }

    /** Separate from ACTIVE: providers still execute native owner work inside this nesting-safe scope. */
    private static final class OwnerFreeze implements AutoCloseable {

        private final Thread thread = Thread.currentThread();
        private final Boolean previous = OWNER_FREEZE.get();
        private boolean closed;

        OwnerFreeze() {
            OWNER_FREEZE.set(Boolean.TRUE);
        }

        @Override
        public void close() {
            if (Thread.currentThread() != thread)
                throw new IllegalStateException("Metadata freeze scope changed thread");
            if (closed) return;
            closed = true;
            if (previous == null) OWNER_FREEZE.remove();
            else OWNER_FREEZE.set(previous);
        }
    }

    static void enter(HologramSnapshotDataContext context) {
        ACTIVE.set(context);
    }

    static void exit() {
        ACTIVE.remove();
    }

    static final class Busy extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /** A terminal admission failure, not a retryable lack of presently free process quota. */
    static final class CapacityExceeded extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        CapacityExceeded(String phase, long held, long requested, long limit) {
            super(
                "Snapshot metadata working set exceeds quota phase=" + phase
                    + " held="
                    + held
                    + " requested="
                    + requested
                    + " limit="
                    + limit);
        }
    }

    static void requireCapacity(String phase, long held, long requested, long limit) {
        if (held < 0 || requested < 0 || limit < 0) throw new IllegalArgumentException("Negative metadata admission");
        // Subtraction avoids overflow, and does not misclassify unrelated live work as our own working set.
        if (held > limit || requested > limit - held) throw new CapacityExceeded(phase, held, requested, limit);
    }

    private HologramProcessGovernor.Lease reserveMandatory(String phase, long bytes) {
        requireCapacity(phase, fixedBytes, bytes, HologramProcessGovernor.retainedByteLimit());
        HologramProcessGovernor.Lease lease = HologramRetainedCache.tryReserve(phase, bytes);
        if (lease == null) {
            // Optional cache residency must not prevent mandatory facts from reaching admission. Live
            // borrows remain charged until the caller unwinds; its next real-tick retry stays uncached.
            decodedDisabled = true;
            retireDecoded();
            throw new Busy();
        }
        return lease;
    }

    private void retainMandatory(HologramProcessGovernor.Lease lease) {
        retained.add(lease);
        fixedBytes += lease.bytes();
    }

    synchronized NBTTagCompound freeze(String encoded) throws IOException {
        try (OwnerFreeze scope = ownerFreeze();
            HologramRetainedCache.Borrow<NBTTagCompound> value = freezeBorrowed(encoded)) {
            return copyForCaller(value);
        }
    }

    private synchronized HologramRetainedCache.Borrow<NBTTagCompound> freezeBorrowed(String encoded)
        throws IOException {
        if (Thread.currentThread() != owner || active())
            throw new IllegalStateException("NBT identity freezing requires its owner thread");
        if (ownerClosed) throw new IllegalStateException("Closed frozen metadata source");
        if (encoded == null || encoded.isEmpty()) return HologramRetainedCache.Borrow.uncached(null);
        Decoded cached = decoded.get(encoded);
        if (cached != null) return cached.value.borrow();
        HologramBlueprintNbt.ExactBlob blob = HologramBlueprintNbt.exactDeferred(encoded);
        if (!expanded.containsKey(encoded)) {
            if (!blob.registryIndependent() && itemPatch == null) {
                HologramProcessGovernor.Lease lease = reserveMandatory(
                    "metadata.item-patch",
                    HologramBlueprintNbt.frozenItemPatchBytes());
                try {
                    itemPatch = HologramBlueprintNbt.freezeItemPatch();
                    retainMandatory(lease);
                } catch (RuntimeException | Error failed) {
                    lease.close();
                    throw failed;
                }
            }
            if (expanded.size() % INPUT_BATCH == 0) {
                HologramProcessGovernor.Lease lease = reserveMandatory("metadata.source-directory", 128L * INPUT_BATCH);
                retainMandatory(lease);
            }
            expanded.put(encoded, blob.expandedBytes());
        }
        return materializeBorrowed(encoded, blob);
    }

    private synchronized NBTTagCompound materialize(String encoded) throws IOException {
        try (HologramRetainedCache.Borrow<NBTTagCompound> value = materializeBorrowed(encoded, null)) {
            return copyForCaller(value);
        }
    }

    private static NBTTagCompound copyForCaller(HologramRetainedCache.Borrow<NBTTagCompound> value) {
        NBTTagCompound tag = value.value();
        return tag == null || !value.cached() ? tag : (NBTTagCompound) tag.copy();
    }

    private synchronized HologramRetainedCache.Borrow<NBTTagCompound> materializeBorrowed(String encoded,
        HologramBlueprintNbt.ExactBlob frozenSource) throws IOException {
        if (references == 0) throw new IllegalStateException("Closed frozen metadata source");
        Decoded previous = decoded.get(encoded);
        if (previous != null) return previous.value.borrow();
        Integer size = expanded.get(encoded);
        if (size == null) throw new IOException("Snapshot NBT identity was not frozen");
        long cost = 512L + 64L * size;
        HologramProcessGovernor.Lease lease = null;
        if (!decodedDisabled && cost <= DECODED_BYTES) {
            java.util.Iterator<Decoded> oldest = decoded.values()
                .iterator();
            while ((decoded.size() >= 256 || cost > DECODED_BYTES - decodedBytes) && oldest.hasNext()) {
                Decoded evicted = oldest.next();
                decodedBytes -= evicted.cost;
                oldest.remove();
                evicted.value.close();
            }
            lease = HologramRetainedCache.tryReserve("metadata.decoded-lru", cost);
        }
        NBTTagCompound tag;
        try {
            tag = frozenSource == null ? HologramBlueprintNbt.decodeData(encoded) : frozenSource.copyFrozenSource();
            if (itemPatch != null) itemPatch.apply(tag);
        } catch (IOException | RuntimeException | Error failed) {
            if (lease != null) lease.close();
            throw failed;
        }
        if (lease != null) {
            HologramRetainedCache.Value<NBTTagCompound> value = new HologramRetainedCache.Value<>(tag, lease);
            decoded.put(encoded, new Decoded(value, cost));
            decodedBytes += cost;
            return value.borrow();
        }
        // A derived-cache admission miss is never Busy and never bypasses strict decoding/native patching.
        return HologramRetainedCache.Borrow.uncached(tag);
    }

    private void retireDecoded() {
        for (Decoded entry : decoded.values()) entry.value.close();
        decoded.clear();
        decodedBytes = 0;
    }

    synchronized AutoCloseable retain() {
        if (ownerClosed) throw new IllegalStateException("Closed frozen metadata source");
        references++;
        return new AutoCloseable() {

            private boolean closed;

            @Override
            public void close() {
                synchronized (HologramSnapshotDataContext.this) {
                    if (closed) return;
                    closed = true;
                    release();
                }
            }
        };
    }

    @Override
    public synchronized void close() {
        if (ownerClosed) return;
        ownerClosed = true;
        release();
    }

    private void release() {
        if (--references != 0) return;
        retireDecoded();
        expanded.clear();
        blocks.clear();
        descriptors.clear();
        itemPatch = null;
        certificates = null;
        for (HologramPresets.Cell cell : aeIssueCells) cell.frozenAeIssues = null;
        aeIssueCells.clear();
        lastAeCell = null;
        lastAeIdentity = null;
        resourceReviews.clear();
        resourceBytes = 0;
        resourceRows = aeRows = 0;
        retained.close();
        fixedBytes = 0;
    }

    private static final class Decoded {

        final HologramRetainedCache.Value<NBTTagCompound> value;
        final long cost;

        Decoded(HologramRetainedCache.Value<NBTTagCompound> value, long cost) {
            this.value = value;
            this.cost = cost;
        }
    }

    /** Native identity checks depend on the complete payload, never merely the shared block type. */
    synchronized void freezeBlock(HologramPresets.Cell cell) throws IOException {
        if (Thread.currentThread() != owner || active())
            throw new IllegalStateException("Block identity freezing requires its owner thread");
        if (ownerClosed) throw new IllegalStateException("Closed frozen metadata source");
        if (!currentProviders()) throw new IllegalStateException("Snapshot metadata providers changed");
        HologramProcessGovernor.Lease cpu = HologramProcessGovernor.tryHeavyCpu("metadata.freeze");
        if (cpu == null) throw new Busy();
        try (OwnerFreeze scope = ownerFreeze()) {
            freezeBlockOwned(cell);
        } finally {
            cpu.close();
        }
    }

    void seed(HologramAnalysisDocument document) {
        certificates = document;
    }

    private void freezeBlockOwned(HologramPresets.Cell cell) throws IOException {
        if (Thread.currentThread() != owner || active())
            throw new IllegalStateException("Block identity freezing requires its owner thread");
        Block block = cell.block();
        // The tile remains borrowed while materializing the item, which may evict that tile from the LRU.
        try (HologramRetainedCache.Borrow<NBTTagCompound> tile = freezeBorrowed(cell.tileNbt);
            HologramRetainedCache.Borrow<NBTTagCompound> item = freezeBorrowed(cell.itemNbt)) {
            freezeBlockFacts(cell, block, tile.value());
        }
    }

    private void freezeBlockFacts(HologramPresets.Cell cell, Block block, NBTTagCompound payload) throws IOException {
        BlockIdentity identity = new BlockIdentity(block, cell.damage, cell.metadata, cell.tileNbt);
        FrozenBlock frozen = blocks.get(identity);
        if (frozen == null) {
            HologramCoordinatePolicies.freezeSensorSlots(cell, payload);
            boolean boundary = HologramAeBlueprint.supports(block, payload) || HologramNetworkBindings.node(block)
                || payload != null && (payload.hasKey(HologramNetworkBindings.GROUP)
                    || payload.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR))
                || HologramNetworkSchemas.removeExternalReferences(
                    block,
                    cell.damage,
                    payload == null ? null : (NBTTagCompound) payload.copy());
            HologramNbtObligations.Descriptor descriptor = HologramNbtObligations
                .descriptor(block, cell.metadata, payload);
            HologramNbtObligations.Descriptor interned = descriptors.get(descriptor);
            if (interned == null) {
                interned = descriptor;
                if (descriptors.size() < 4096) descriptors.put(descriptor, descriptor);
            }
            frozen = new FrozenBlock(boundary, java.util.Collections.unmodifiableSet(cell.frozenSensorSlots), interned);
            // Bound auxiliary classification storage when every cell carries a distinct native payload.
            if (blocks.size() < 4096) blocks.put(identity, frozen);
        }
        cell.frozenSensorSlots = frozen.sensorSlots;
        cell.metadataIdentityBoundary = frozen.boundary;
        cell.frozenBlock = block;
        cell.frozenBlockResolved = true;
        cell.frozenNbtDescriptor = frozen.descriptor;
        cell.frozenDescriptorOwner = descriptorAuthority;
        boolean aeFrozen = lastAeCell == cell && cell.frozenAeTotal >= 0 && identity.equals(lastAeIdentity);
        if (!aeFrozen) {
            cell.frozenAeIssues = null;
            cell.frozenAeTotal = -1;
            cell.frozenAeError = null;
        }
        if (!aeFrozen && !cell.excluded
            && cell.tileNbt != null
            && !cell.tileNbt.isEmpty()
            && HologramAeBlueprint.supports(block, payload)) {
            java.util.List<HologramAeBlueprint.Issue> issues = new java.util.ArrayList<>();
            int total = 0;
            String failure = null;
            try {
                NBTTagCompound certificate = certificates == null
                    || com.miaokatze.gtmb.api.ResourceContainers.hasCodecs() ? null
                        : certificates.resourceCertificate(cell, "tile");
                if (certificate != null) {
                    total = (int) certificate.getLong("total");
                    net.minecraft.nbt.NBTTagList rows = certificate.getTagList("rows", 10);
                    for (int i = 0; i < rows.tagCount() && aeRows + issues.size() < 8192; i++) {
                        NBTTagCompound row = rows.getCompoundTagAt(i);
                        String path = row.getString("path");
                        int slash = path.indexOf('/');
                        issues.add(
                            new HologramAeBlueprint.Issue(
                                "cached-resource",
                                "blocker",
                                slash < 0 ? path : path.substring(slash + 1),
                                row.getString("message")));
                    }
                } else {
                    HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, cell.damage, payload);
                    for (HologramAeBlueprint.Issue issue : analysis.issues) {
                        if (!"blocker".equals(issue.severity) || issue.path.isEmpty()
                            || java.util.Arrays
                                .asList(
                                    "unsupported_host",
                                    "unsupported_part",
                                    "unsupported_part_item",
                                    "unsupported_cell")
                                .contains(issue.code))
                            continue;
                        total++;
                        if (aeRows + issues.size() < 8192) issues.add(issue);
                    }
                }
            } catch (IOException invalid) {
                failure = HologramAePolicy.fallback(invalid.getMessage());
            }
            cell.frozenAeTotal = total;
            cell.frozenAeError = failure;
            long cost = 512;
            for (HologramAeBlueprint.Issue issue : issues) cost += 256L
                + 2L * (issue.code.length() + issue.path.length() + issue.message.length() + issue.severity.length());
            HologramProcessGovernor.Lease lease = issues.isEmpty() ? null
                : HologramRetainedCache.tryReserve("metadata.ae-issues", cost);
            if (lease != null) {
                AeIssues frozenIssues = new AeIssues(total, issues, failure);
                retainMandatory(lease);
                cell.frozenAeIssues = frozenIssues;
                aeIssueCells.add(cell);
                aeRows += issues.size();
            }
        }
        lastAeCell = cell;
        lastAeIdentity = identity;
        if (!cell.excluded) {
            for (int i = 0; i < 2; i++) {
                String encoded = i == 0 ? cell.tileNbt : cell.itemNbt;
                if (encoded == null || encoded.isEmpty() || i == 0 && HologramAeBlueprint.supports(block, payload))
                    continue;
                String scope = i == 0 ? "tile" : "item";
                ResourceIdentity resourceIdentity = new ResourceIdentity(
                    block,
                    cell.damage,
                    cell.metadata,
                    scope,
                    encoded,
                    resourceEpoch,
                    nativeEpoch);
                if (resourceReviews.containsKey(resourceIdentity)) continue;
                NBTTagCompound review = certificates == null || com.miaokatze.gtmb.api.ResourceContainers.hasCodecs()
                    ? null
                    : certificates.resourceCertificate(cell, scope);
                if (review == null) {
                    try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
                        review = HologramBlueprintProcessing.freezeResourceReview(
                            cell,
                            scope,
                            facts,
                            Math.max(0, 8192 - resourceRows),
                            Math.max(0L, (8L << 20) - resourceBytes));
                    }
                } else review = boundedResourceReview(
                    review,
                    Math.max(0, 8192 - resourceRows),
                    Math.max(0L, (8L << 20) - resourceBytes));
                HologramResourceReviewFact fact = HologramResourceReviewFact.freeze(review);
                long cost = fact.retainedBytes();
                HologramProcessGovernor.Lease lease = reserveMandatory("metadata.resource-review", cost);
                retainMandatory(lease);
                resourceRows += fact.rowCount();
                resourceBytes += cost;
                resourceReviews.put(resourceIdentity, fact);
            }
        }
    }

    private static NBTTagCompound boundedResourceReview(NBTTagCompound source, int limit, long byteLimit) {
        // Unknown row schemas are not safe to project. Preserve them in the full-NBT fallback,
        // where mandatory admission still charges their complete retained representation.
        if (!source.hasKey("rows", 9)) return (NBTTagCompound) source.copy();
        net.minecraft.nbt.NBTTagList originalRows = (net.minecraft.nbt.NBTTagList) source.getTag("rows");
        if (originalRows.tagCount() == 0 || originalRows.func_150303_d() != 10) return (NBTTagCompound) source.copy();
        NBTTagCompound result = new NBTTagCompound();
        // Bound only the presentation rows; future fields and exact scalar types must survive
        // so the fact representation can conservatively fall back to the complete NBT schema.
        for (Object raw : source.func_150296_c()) {
            String key = (String) raw;
            if (!"rows".equals(key)) result.setTag(
                key,
                source.getTag(key)
                    .copy());
        }
        net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList(), originals = originalRows;
        long bytes = 0;
        for (int i = 0; i < Math.min(limit, originals.tagCount()); i++) {
            NBTTagCompound row = originals.getCompoundTagAt(i);
            long cost = HologramSnapshotNbt.estimate(row);
            if (cost > byteLimit - bytes) break;
            rows.appendTag(row.copy());
            bytes += cost;
        }
        result.setTag("rows", rows);
        return result;
    }

    static NBTTagCompound resourceReview(HologramPresets.Cell cell, String scope) {
        HologramSnapshotDataContext context = ACTIVE.get();
        String encoded = "tile".equals(scope) ? cell.tileNbt : cell.itemNbt;
        HologramResourceReviewFact review = context == null ? null
            : context.resourceReviews.get(
                new ResourceIdentity(
                    cell.block(),
                    cell.damage,
                    cell.metadata,
                    scope,
                    encoded,
                    context.resourceEpoch,
                    context.nativeEpoch));
        if (review == null) throw new IllegalStateException("Resource provider facts were not frozen on the owner");
        return review.copy();
    }

    /** Coordinates are presentation inputs; every logical reference is counted by ResourceReview.add. */
    private static final class ResourceIdentity {

        final Block block;
        final int damage, metadata;
        final String scope, encoded;
        final long resources, nativeProvider;

        ResourceIdentity(Block block, int damage, int metadata, String scope, String encoded, long resources,
            long nativeProvider) {
            this.block = block;
            this.damage = damage;
            this.metadata = metadata;
            this.scope = scope;
            this.encoded = encoded;
            this.resources = resources;
            this.nativeProvider = nativeProvider;
        }

        @Override
        public int hashCode() {
            int hash = 31 * (31 * System.identityHashCode(block) + damage) + metadata;
            hash = 31 * hash + Objects.hashCode(scope);
            hash = 31 * hash + Objects.hashCode(encoded);
            hash = 31 * hash + (int) (resources ^ resources >>> 32);
            return 31 * hash + (int) (nativeProvider ^ nativeProvider >>> 32);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ResourceIdentity)) return false;
            ResourceIdentity right = (ResourceIdentity) other;
            return block == right.block && damage == right.damage
                && metadata == right.metadata
                && resources == right.resources
                && nativeProvider == right.nativeProvider
                && Objects.equals(scope, right.scope)
                && Objects.equals(encoded, right.encoded);
        }
    }

    boolean currentProviders() {
        return resourceEpoch == com.miaokatze.gtmb.api.ResourceContainers.generation()
            && nativeEpoch == HologramNativePlacement.generation();
    }

    synchronized long retainedDecodedBytes() {
        return decodedBytes;
    }

    long retainedResourceBytes() {
        return resourceBytes;
    }

    int retainedResourceFacts() {
        return resourceReviews.size();
    }

    static HologramNbtObligations.Descriptor descriptor(HologramPresets.Cell cell) {
        HologramSnapshotDataContext context = ACTIVE.get();
        if (context == null || cell.frozenDescriptorOwner != context.descriptorAuthority
            || cell.frozenNbtDescriptor == null)
            throw new IllegalStateException("Snapshot native descriptor was not frozen for this task");
        return cell.frozenNbtDescriptor;
    }

    static AeIssues aeIssues(HologramPresets.Cell cell) {
        descriptor(cell);
        if (cell.frozenAeTotal < 0) throw new IllegalStateException("AE metadata facts were not frozen on the owner");
        return cell.frozenAeIssues == null
            ? new AeIssues(cell.frozenAeTotal, java.util.Collections.emptyList(), cell.frozenAeError)
            : cell.frozenAeIssues;
    }

    static final class AeIssues {

        final int total;
        final java.util.List<HologramAeBlueprint.Issue> issues;
        final String failure;

        AeIssues(int total, java.util.List<HologramAeBlueprint.Issue> issues, String failure) {
            this.total = total;
            this.issues = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(issues));
            this.failure = failure;
        }
    }

    private static final class FrozenBlock {

        final boolean boundary;
        final Set<Integer> sensorSlots;
        final HologramNbtObligations.Descriptor descriptor;

        FrozenBlock(boolean boundary, Set<Integer> sensorSlots, HologramNbtObligations.Descriptor descriptor) {
            this.boundary = boundary;
            this.sensorSlots = sensorSlots;
            this.descriptor = descriptor;
        }
    }

    private static final class BlockIdentity {

        final Block block;
        final int damage, metadata;
        final String tile;

        BlockIdentity(Block block, int damage, int metadata, String tile) {
            this.block = block;
            this.damage = damage;
            this.metadata = metadata;
            this.tile = tile;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * System.identityHashCode(block) + damage) + metadata) + Objects.hashCode(tile);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BlockIdentity)) return false;
            BlockIdentity identity = (BlockIdentity) other;
            return block == identity.block && damage == identity.damage
                && metadata == identity.metadata
                && Objects.equals(tile, identity.tile);
        }
    }

    static NBTTagCompound decode(String encoded) throws IOException {
        HologramSnapshotDataContext context = ACTIVE.get();
        if (context == null) throw new IOException("Snapshot NBT identity was not frozen");
        return context.materialize(encoded);
    }

    static int expandedBytes(String encoded) throws IOException {
        HologramSnapshotDataContext context = ACTIVE.get();
        Integer bytes = context == null ? null : context.expanded.get(encoded);
        if (bytes == null) throw new IOException("Snapshot NBT size was not frozen");
        return bytes;
    }
}
