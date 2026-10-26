package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Immutable page/domain certificates. A document is a directory, never an overall validation result. */
final class HologramAnalysisDocument {

    private static final long MAX_BYTES = 8L * 1024 * 1024;
    final Map<Long, Page> pages;
    final Map<HologramSegmentContract.Domain, Long> computed, reused;
    final long nextId, bytes;
    final HologramRelationIndex relations;
    final Set<Long> relationAffectedPages;
    private final Map<HologramRelationIndex.Source, Long> relationSources;
    private final Map<HologramRelationIndex.Source, java.util.List<HologramSegmentDocument.Record>> relationTargets;
    final HologramAnalysisComponents components;
    final String quantumFingerprint;

    private HologramAnalysisDocument(Map<Long, Page> pages, Stage staged) {
        this.pages = Collections.unmodifiableMap(pages);
        nextId = staged.nextId;
        bytes = staged.bytes;
        computed = Collections.unmodifiableMap(new EnumMap<>(staged.computed));
        reused = Collections.unmodifiableMap(new EnumMap<>(staged.reused));
        relations = staged.relations == null ? null : staged.relations.copy();
        relationSources = Collections.unmodifiableMap(new HashMap<>(staged.relationSources));
        relationTargets = Collections.unmodifiableMap(new HashMap<>(staged.relationTargets));
        relationAffectedPages = Collections.unmodifiableSet(new HashSet<>(staged.relationAffectedPages));
        components = staged.components;
        quantumFingerprint = staged.quantumFingerprint;
    }

    interface Work {

        NBTTagCompound compute() throws IOException;
    }

    NBTTagCompound resourceCertificate(HologramPresets.Cell cell, String scope) throws IOException {
        Page page = pages.get((cell.segmentCellId - 1) / HologramSegmentContract.PAGE_SIZE);
        if (page == null) return null;
        Certificate certificate = page.certificates
            .get(new Key(cell.segmentCellId, HologramSegmentContract.Domain.ASSET_NEEDS, "resource:" + scope));
        if (certificate == null || !certificate.record.coverageComplete()
            || !certificate.result.getBoolean("nativeComplete")) return null;
        HologramSegmentDocument.Record current = certificate.record.matches(cell, true) ? certificate.record
            : new HologramSegmentDocument.Record(cell.segmentCellId, cell, true);
        Object[] dependencies = resourceDependencies(cell, scope);
        return certificate.matches(current, dependencies) ? certificate.copy() : null;
    }

    static Object[] resourceDependencies(HologramPresets.Cell cell, String scope) {
        return new Object[] { cell.block(), scope, com.miaokatze.gtmb.api.ResourceContainers.generation(),
            HologramAeBlueprint.POLICY_VERSION, cell.frozenAeError };
    }

    interface StringWork {

        String compute() throws IOException;
    }

    static void bindCaptureIds(HologramSession session, HologramPresets.Preset source) {
        if (session.capture == null || source == null) return;
        for (HologramPresets.Cell saved : source.cells) {
            HologramCapture.Cell cell = session.capture.positions.get(
                ((long) session.x + saved.x) + "," + ((long) session.y + saved.y) + "," + ((long) session.z + saved.z));
            if (cell != null) cell.segmentCellId = saved.segmentCellId;
        }
    }

    static final class Stage implements AutoCloseable {

        private final HologramPresets.Preset source;
        private final HologramAnalysisDocument before;
        private final Map<Long, HologramSegmentDocument.Record> records = new HashMap<>();
        private final Map<Long, Map<Key, Certificate>> pages = new LinkedHashMap<>();
        private final EnumMap<HologramSegmentContract.Domain, Long> computed = new EnumMap<>(
            HologramSegmentContract.Domain.class);
        private final EnumMap<HologramSegmentContract.Domain, Long> reused = new EnumMap<>(
            HologramSegmentContract.Domain.class);
        private boolean enabled;
        private HologramProcessGovernor.Lease stagingLease;
        private long nextId, bytes;
        private HologramRelationIndex relations;
        private final Map<HologramRelationIndex.Source, Long> relationSources = new HashMap<>();
        private final Set<Long> relationAffectedPages = new HashSet<>();
        private final Map<HologramRelationIndex.Source, java.util.List<HologramSegmentDocument.Record>> relationTargets = new HashMap<>();
        private final Map<Long, HologramAnalysisComponents.Node> networkNodes = new LinkedHashMap<>();
        private HologramAnalysisComponents components;
        private String quantumFingerprint;

        Stage(HologramPresets.Preset source, boolean enabled) throws IOException {
            this.source = source;
            this.enabled = enabled && admissible(source);
            stagingLease = this.enabled ? HologramRetainedCache.tryReserve("analysis.staging", MAX_BYTES) : null;
            this.enabled &= stagingLease != null;
            before = this.enabled && source != null ? source.analysisDocument : null;
            nextId = before == null ? 1 : before.nextId;
            try {
                if (source == null || !this.enabled) return;
                for (HologramPresets.Cell cell : source.cells) nextId = Math.max(nextId, cell.segmentCellId + 1);
                for (HologramPresets.Cell cell : source.palette) nextId = Math.max(nextId, cell.segmentCellId + 1);
                if (source.segmentDocument != null) nextId = Math.max(nextId, source.segmentDocument.nextId);
                Set<Long> seen = new HashSet<>();
                Set<HologramNbtObligations.Facts> ownedFacts = Collections
                    .newSetFromMap(new java.util.IdentityHashMap<>());
                Map<java.util.List<Object>, HologramNbtObligations.Facts> exactFacts = new HashMap<>();
                for (HologramPresets.Cell cell : source.cells) {
                    if (cell.segmentCellId <= 0 || !seen.add(cell.segmentCellId)) {
                        cell.segmentCellId = nextId++;
                        seen.add(cell.segmentCellId);
                    }
                    HologramSegmentDocument.Record old = previousRecord(cell.segmentCellId);
                    java.util.List<Object> sourceKey = HologramNbtObligations.sourceKey(cell);
                    HologramSegmentDocument.Record record;
                    if (old != null && old.matches(cell, true)) record = old;
                    else {
                        HologramNbtObligations.Facts facts = exactFacts.get(sourceKey);
                        if (facts == null) facts = HologramNbtObligations.analyze(cell);
                        record = new HologramSegmentDocument.Record(cell.segmentCellId, cell, true, facts);
                    }
                    exactFacts.putIfAbsent(sourceKey, record.nbt);
                    bytes += 1024L + 2L * textLength(cell.tileNbt) + 2L * textLength(cell.itemNbt);
                    if (ownedFacts.add(record.nbt)) bytes += record.nbt.weight;
                    if (bytes > MAX_BYTES) {
                        this.enabled = false;
                        records.clear();
                        bytes = 0;
                        close();
                        return;
                    }
                    records.put(cell.segmentCellId, record);
                }
            } catch (IOException | RuntimeException | Error failed) {
                close();
                throw failed;
            }
        }

        private HologramSegmentDocument.Record previousRecord(long id) {
            Page page = before == null ? null : before.pages.get(page(id));
            HologramSegmentDocument.Record record = page == null ? null : page.records.get(id);
            if (record == null && source.segmentDocument != null) record = source.segmentDocument.records.get(id);
            return record;
        }

        boolean coverage(HologramPresets.Cell cell) {
            HologramSegmentDocument.Record record = records.get(cell.segmentCellId);
            return record != null && record.coverageComplete();
        }

        void networkNode(HologramPresets.Cell cell, NBTTagCompound entry) {
            if (!enabled) return;
            if (!entry.getBoolean("ae") && !entry.getBoolean("member")) return;
            NBTTagCompound descriptor = entry.getCompoundTag("descriptor");
            networkNodes.put(
                cell.segmentCellId,
                new HologramAnalysisComponents.Node(
                    cell,
                    records.get(cell.segmentCellId),
                    entry.getBoolean("ae"),
                    entry.getBoolean("member"),
                    descriptor.getString(HologramNetworkBindings.GROUP),
                    descriptor.getString(HologramNetworkBindings.HINT)));
        }

        void networks() {
            if (!enabled) return;
            components = new HologramAnalysisComponents(before == null ? null : before.components, networkNodes);
            long cost = 1024L * networkNodes.size();
            if (cost > MAX_BYTES - bytes) components = null;
            else bytes += cost;
        }

        String fingerprint(StringWork work) throws IOException {
            if (quantumFingerprint == null) {
                quantumFingerprint = enabled && before != null
                    && before.quantumFingerprint != null
                    && components != null
                    && components.sameComponents(before.components) ? before.quantumFingerprint : work.compute();
            }
            return quantumFingerprint;
        }

        void relations(java.util.List<HologramCoordinatePolicies.Relation> current) {
            if (!enabled) return;
            relations = before == null || before.relations == null ? new HologramRelationIndex(Collections.emptyList())
                : before.relations.copy();
            Map<HologramRelationIndex.Source, java.util.List<HologramCoordinatePolicies.Relation>> grouped = new HashMap<>();
            for (HologramCoordinatePolicies.Relation relation : current) {
                HologramRelationIndex.Source key = new HologramRelationIndex.Source(
                    relation.x,
                    relation.y,
                    relation.z,
                    relation.payload);
                grouped.computeIfAbsent(key, ignored -> new java.util.ArrayList<>())
                    .add(relation);
            }
            if (source != null) for (HologramPresets.Cell cell : source.cells) {
                for (String payload : new String[] { "tile", "item" }) {
                    HologramRelationIndex.Source key = new HologramRelationIndex.Source(
                        cell.x,
                        cell.y,
                        cell.z,
                        payload);
                    if (grouped.containsKey(key)) relationSources.put(key, cell.segmentCellId);
                }
            }
            Map<String, HologramPresets.Cell> targets = new HashMap<>();
            if (source != null)
                for (HologramPresets.Cell cell : source.cells) targets.put(cell.x + "," + cell.y + "," + cell.z, cell);
            for (Map.Entry<HologramRelationIndex.Source, java.util.List<HologramCoordinatePolicies.Relation>> entry : grouped
                .entrySet()) {
                java.util.List<HologramSegmentDocument.Record> dependencies = new java.util.ArrayList<>();
                for (HologramCoordinatePolicies.Relation relation : entry.getValue()) {
                    HologramPresets.Cell target = !relation.internal ? null
                        : targets.get(
                            ((long) relation.x + relation.dx) + ","
                                + ((long) relation.y + relation.dy)
                                + ","
                                + ((long) relation.z + relation.dz));
                    dependencies.add(target == null ? null : records.get(target.segmentCellId));
                }
                relationTargets.put(entry.getKey(), Collections.unmodifiableList(dependencies));
            }
            Set<HologramRelationIndex.Source> changed = new HashSet<>(relations.sources());
            changed.addAll(grouped.keySet());
            for (HologramRelationIndex.Source key : changed) {
                java.util.List<HologramCoordinatePolicies.Relation> next = grouped
                    .getOrDefault(key, Collections.emptyList());
                if (relations.same(key, next)
                    && sameTargets(before == null ? null : before.relationTargets.get(key), relationTargets.get(key)))
                    continue;
                Long id = relationSources.get(key);
                if (id == null && before != null) id = before.relationSources.get(key);
                if (id != null) relationAffectedPages.add(page(id));
                relations.replace(key, next);
            }
            long cost = 1024L * current.size();
            if (cost > MAX_BYTES - bytes) {
                relations = null;
                relationSources.clear();
                relationTargets.clear();
            } else bytes += cost;
        }

        private static boolean sameTargets(java.util.List<HologramSegmentDocument.Record> previous,
            java.util.List<HologramSegmentDocument.Record> current) {
            if (previous == null || current == null) return previous == current;
            if (previous.size() != current.size()) return false;
            for (int i = 0; i < previous.size(); i++) {
                HologramSegmentDocument.Record left = previous.get(i), right = current.get(i);
                if (left == null || right == null) {
                    if (left != right) return false;
                } else if (left.id != right.id || !left.sameInput(right, HologramSegmentContract.Domain.RELATIONS))
                    return false;
            }
            return true;
        }

        NBTTagCompound value(HologramPresets.Cell cell, HologramSegmentContract.Domain domain, String kind,
            Object[] dependencies, boolean persistent, Work work) throws IOException {
            if (!enabled) {
                increment(computed, domain);
                return work.compute();
            }
            Key key = new Key(cell.segmentCellId, domain, kind);
            long pageId = page(key.cell);
            HologramSegmentDocument.Record record = records.get(key.cell);
            Page oldPage = before == null ? null : before.pages.get(pageId);
            Certificate old = oldPage == null ? null : oldPage.certificates.get(key);
            Map<Key, Certificate> staged = pages.computeIfAbsent(pageId, ignored -> new LinkedHashMap<>());
            Certificate current = staged.get(key);
            if (current != null && current.matches(record, dependencies)) return current.copy();
            if (enabled && persistent && old != null && old.matches(record, dependencies)) {
                increment(reused, domain);
                if (old.cost <= MAX_BYTES - bytes) {
                    staged.put(key, old);
                    bytes += old.cost;
                }
                return old.copy();
            }
            increment(computed, domain);
            NBTTagCompound result = work.compute();
            long cost = 512L + weight(result) + 2L * textLength(cell.tileNbt) + 2L * textLength(cell.itemNbt);
            if (enabled && persistent && record != null && cost <= MAX_BYTES - bytes) {
                Certificate certificate = new Certificate(record, domain, dependencies, result, cost);
                staged.put(key, certificate);
                bytes += cost;
            }
            return result;
        }

        /** Only complete domain results are published; exceptions leave the previous immutable directory intact. */
        HologramAnalysisDocument snapshot() {
            Map<Long, Page> result = new LinkedHashMap<>();
            if (enabled) for (Map.Entry<Long, Map<Key, Certificate>> entry : pages.entrySet()) {
                Map<Long, HologramSegmentDocument.Record> members = new HashMap<>();
                for (Key key : entry.getValue()
                    .keySet()) members.put(key.cell, records.get(key.cell));
                result.put(entry.getKey(), new Page(members, entry.getValue()));
            }
            return new HologramAnalysisDocument(result, this);
        }

        void publish() {
            if (source == null) return;
            if (!enabled) {
                source.analysisDocument = null;
                return;
            }
            long retainedBytes = 512 + bytes;
            HologramProcessGovernor.Lease lease = HologramRetainedCache
                .tryReserve("analysis.domain-cache", retainedBytes);
            if (lease == null) {
                close();
                lease = HologramRetainedCache.tryReserve("analysis.domain-cache", retainedBytes);
            }
            if (lease == null) {
                source.analysisDocument = null;
                return;
            }
            try {
                HologramAnalysisDocument result = snapshot();
                HologramRetainedCache.watch(result)
                    .add(lease);
                source.analysisDocument = result;
            } catch (RuntimeException | Error failed) {
                lease.close();
                throw failed;
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (stagingLease != null) {
                stagingLease.close();
                stagingLease = null;
            }
        }
    }

    static final class Page {

        private final Map<Long, HologramSegmentDocument.Record> records;
        private final Map<Key, Certificate> certificates;

        Page(Map<Long, HologramSegmentDocument.Record> records, Map<Key, Certificate> certificates) {
            this.records = Collections.unmodifiableMap(records);
            this.certificates = Collections.unmodifiableMap(new LinkedHashMap<>(certificates));
        }
    }

    private static final class Certificate {

        final HologramSegmentDocument.Record record;
        final HologramSegmentContract.Domain domain;
        final Object[] dependencies;
        final NBTTagCompound result;
        final long cost;

        Certificate(HologramSegmentDocument.Record record, HologramSegmentContract.Domain domain, Object[] dependencies,
            NBTTagCompound result, long cost) {
            this.record = record;
            this.domain = domain;
            this.dependencies = dependencies.clone();
            this.result = (NBTTagCompound) result.copy();
            this.cost = cost;
        }

        boolean matches(HologramSegmentDocument.Record current, Object[] dependencies) {
            return current != null && record.sameInput(current, domain)
                && Arrays.deepEquals(this.dependencies, dependencies);
        }

        NBTTagCompound copy() {
            return (NBTTagCompound) result.copy();
        }
    }

    private static final class Key {

        final long cell;
        final HologramSegmentContract.Domain domain;
        final String kind;

        Key(long cell, HologramSegmentContract.Domain domain, String kind) {
            this.cell = cell;
            this.domain = domain;
            this.kind = kind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(cell, domain, kind);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return cell == key.cell && domain == key.domain && kind.equals(key.kind);
        }
    }

    private static long page(long id) {
        return (id - 1) / HologramSegmentContract.PAGE_SIZE;
    }

    private static void increment(Map<HologramSegmentContract.Domain, Long> counts,
        HologramSegmentContract.Domain domain) {
        counts.put(domain, counts.getOrDefault(domain, 0L) + 1);
    }

    private static int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean admissible(HologramPresets.Preset source) {
        if (source == null) return true;
        long bytes = 0;
        for (HologramPresets.Cell cell : source.cells) {
            bytes += 1024L + 2L * textLength(cell.tileNbt) + 2L * textLength(cell.itemNbt);
            if (bytes > MAX_BYTES) return false;
        }
        return true;
    }

    /** Counts newly produced certificate objects without compressing or encoding any payload. */
    private static long weight(NBTBase value) {
        if (value == null) return 0;
        if (value instanceof NBTTagCompound) {
            long size = 128;
            NBTTagCompound tag = (NBTTagCompound) value;
            for (Object key : tag.func_150296_c())
                size += 128L + 2L * ((String) key).length() + weight(tag.getTag((String) key));
            return size;
        }
        if (value instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) value;
            long size = 128;
            for (int i = 0; i < list.tagCount(); i++) {
                // Certificate lists contain only compounds or strings.
                size += 32L + (list.func_150303_d() == 10 ? weight(list.getCompoundTagAt(i))
                    : 64L + 2L * list.getStringTagAt(i)
                        .length());
            }
            return size;
        }
        if (value instanceof NBTTagString) return 64L + 2L * ((NBTTagString) value).func_150285_a_()
            .length();
        if (value instanceof NBTTagByteArray) return 64L + ((NBTTagByteArray) value).func_150292_c().length;
        if (value instanceof NBTTagIntArray) return 64L + 4L * ((NBTTagIntArray) value).func_150302_c().length;
        return 64;
    }
}
