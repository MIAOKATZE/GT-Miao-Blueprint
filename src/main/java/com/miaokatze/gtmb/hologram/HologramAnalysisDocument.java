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

    static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final long HOT_BYTES = 4L * 1024 * 1024;
    private static final long ENTRY_BYTES = 6L * 1024 * 1024 - 512;
    private static final long CLOSURE_BYTES = 1024L * 1024;
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
        if (inputEstimate(cell) > CLOSURE_BYTES) return null;
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
        // Certificate input records are owned by bounded entries, never by a whole-input directory.
        private final Owners owners = new Owners();
        private final Lane cold = new Lane(owners);
        private final Lane hot = new Lane(owners);
        private long relationBytes, networkBytes;
        private HologramPresets.Cell lastCell;
        private HologramSegmentDocument.Record lastRecord;
        private boolean networkFallback;
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
            this.enabled = enabled;
            stagingLease = enabled ? HologramRetainedCache.tryReserve("analysis.staging", MAX_BYTES) : null;
            this.enabled &= stagingLease != null;
            before = this.enabled && source != null ? source.analysisDocument : null;
            nextId = before == null ? 1 : before.nextId;
            if (source == null || !this.enabled) return;
            // Validation owns uniqueness of supplied positive IDs. Allocation scans the existing DTO list;
            // it does not allocate an O(N) identity/record map merely to retain a small working set.
            for (HologramPresets.Cell cell : source.cells) nextId = Math.max(nextId, cell.segmentCellId + 1);
            for (HologramPresets.Cell cell : source.palette) nextId = Math.max(nextId, cell.segmentCellId + 1);
            if (source.segmentDocument != null) nextId = Math.max(nextId, source.segmentDocument.nextId);
            for (HologramPresets.Cell cell : source.cells) if (cell.segmentCellId <= 0) cell.segmentCellId = nextId++;
            if (before != null) {
                // Previously hit entries win. Promote the remaining prior probation only into free hot
                // capacity, so a complete sequential scan cannot evict every useful previous page.
                for (Page old : before.pages.values())
                    for (Map.Entry<Key, Certificate> entry : old.certificates.entrySet())
                        if (old.hot.contains(entry.getKey())) retain(entry.getKey(), entry.getValue(), true);
                for (Page old : before.pages.values())
                    for (Map.Entry<Key, Certificate> entry : old.certificates.entrySet())
                        if (!old.hot.contains(entry.getKey())) retain(
                            entry.getKey(),
                            entry.getValue(),
                            hot.bytes + hot.addition(entry.getValue()) <= HOT_BYTES);
            }
        }

        private HologramSegmentDocument.Record record(HologramPresets.Cell cell) throws IOException {
            if (lastCell == cell && lastRecord != null && lastRecord.matches(cell, true)) return lastRecord;
            if (inputEstimate(cell) > CLOSURE_BYTES) {
                lastCell = null;
                lastRecord = null;
                return null;
            }
            HologramSegmentDocument.Record old = hot.records.get(cell.segmentCellId);
            if (old == null || !old.matches(cell, true)) old = cold.records.get(cell.segmentCellId);
            if (old == null || !old.matches(cell, true)) old = previousRecord(cell.segmentCellId);
            lastCell = cell;
            return lastRecord = old != null && old.matches(cell, true) ? old
                : new HologramSegmentDocument.Record(cell.segmentCellId, cell, true);
        }

        private HologramSegmentDocument.Record previousRecord(long id) {
            Page page = before == null ? null : before.pages.get(page(id));
            HologramSegmentDocument.Record record = page == null ? null : page.records.get(id);
            if (record == null && source.segmentDocument != null) record = source.segmentDocument.records.get(id);
            return record;
        }

        boolean coverage(HologramPresets.Cell cell) throws IOException {
            HologramSegmentDocument.Record record = enabled ? record(cell) : null;
            return record != null && record.coverageComplete();
        }

        void networkNode(HologramPresets.Cell cell, NBTTagCompound entry) throws IOException {
            if (!enabled || networkFallback) return;
            if (!entry.getBoolean("ae") && !entry.getBoolean("member")) return;
            HologramSegmentDocument.Record input = record(cell);
            long cost = input == null ? CLOSURE_BYTES + 1 : 4096L + recordCost(cell, input) + weight(entry);
            if (networkNodes.containsKey(cell.segmentCellId) || cost > CLOSURE_BYTES - networkBytes) {
                networkNodes.clear();
                networkBytes = 0;
                networkFallback = true;
                return;
            }
            networkBytes += cost;
            NBTTagCompound descriptor = entry.getCompoundTag("descriptor");
            networkNodes.put(
                cell.segmentCellId,
                new HologramAnalysisComponents.Node(
                    cell,
                    input,
                    entry.getBoolean("ae"),
                    entry.getBoolean("member"),
                    descriptor.getString(HologramNetworkBindings.GROUP),
                    descriptor.getString(HologramNetworkBindings.HINT)));
        }

        void networks() {
            if (!enabled || networkFallback) return;
            components = new HologramAnalysisComponents(before == null ? null : before.components, networkNodes);
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

        void relations(java.util.List<HologramCoordinatePolicies.Relation> current) throws IOException {
            if (!enabled) return;
            abandonRelations();
            relationAffectedPages.clear();
            // Reserve the complete bounded closure before copying an index or constructing dependency maps.
            // An oversized closure is recomputed by the strict caller; other domains keep their pages.
            relationBytes = 1024;
            for (HologramCoordinatePolicies.Relation relation : current) {
                relationBytes += 4096L + 2L * (textLength(relation.id) + textLength(relation.label)
                    + textLength(relation.adapter)
                    + textLength(relation.payload)
                    + textLength(relation.previousMode));
                if (relationBytes > CLOSURE_BYTES) {
                    abandonRelations();
                    return;
                }
            }
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
            for (HologramCoordinatePolicies.Relation relation : current) if (relation.internal) targets.put(
                ((long) relation.x + relation.dx) + ","
                    + ((long) relation.y + relation.dy)
                    + ","
                    + ((long) relation.z + relation.dz),
                null);
            if (source != null) for (HologramPresets.Cell cell : source.cells) {
                String position = cell.x + "," + cell.y + "," + cell.z;
                if (targets.containsKey(position)) targets.put(position, cell);
            }
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
                    HologramSegmentDocument.Record dependency = target == null ? null : record(target);
                    if (target != null && dependency == null) {
                        abandonRelations();
                        return;
                    }
                    if (dependency != null) {
                        long cost = recordCost(target, dependency);
                        if (cost > CLOSURE_BYTES - relationBytes) {
                            abandonRelations();
                            return;
                        }
                        relationBytes += cost;
                    }
                    dependencies.add(dependency);
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
        }

        private void abandonRelations() {
            relations = null;
            relationSources.clear();
            relationTargets.clear();
            relationBytes = 0;
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
            HologramSegmentDocument.Record input = record(cell);
            if (input == null) {
                remove(key);
                increment(computed, domain);
                return work.compute();
            }
            Certificate old = hot.values.get(key);
            if (old == null) old = cold.values.get(key);
            if (persistent && old != null && old.matches(input, dependencies)) {
                increment(reused, domain);
                // A hit in probation need not evict a still-resident protected entry. In particular,
                // a fully resident small input must not create misses merely by changing queue class.
                if (hot.values.containsKey(key) || hot.bytes + hot.addition(old) <= HOT_BYTES) retain(key, old, true);
                return old.copy();
            }
            remove(key);
            increment(computed, domain);
            NBTTagCompound result = work.compute();
            long cost = 512L + weight(result) + dependencyWeight(dependencies);
            long recordWeight = recordCost(cell, input) - input.nbt.weight;
            if (persistent && cost + recordWeight + input.nbt.weight <= ENTRY_BYTES)
                retain(key, new Certificate(input, domain, dependencies, result, cost, recordWeight), false);
            return result;
        }

        private void remove(Key key) {
            cold.remove(key);
            hot.remove(key);
        }

        private void retain(Key key, Certificate value, boolean protectedEntry) {
            remove(key);
            Lane lane = protectedEntry ? hot : cold;
            if (lane.addition(value) > (protectedEntry ? HOT_BYTES : ENTRY_BYTES)) return;
            while (protectedEntry && hot.bytes + hot.addition(value) > HOT_BYTES) hot.evict();
            while (owners.bytes + owners.addition(value) > ENTRY_BYTES) {
                if (!cold.values.isEmpty()) cold.evict();
                else hot.evict();
            }
            lane.add(key, value);
        }

        /** Only bounded complete domain entries are published, never a whole-blueprint validity flag. */
        HologramAnalysisDocument snapshot() {
            Map<Long, Map<Key, Certificate>> grouped = new LinkedHashMap<>();
            Set<Key> protectedKeys = new HashSet<>(hot.values.keySet());
            for (Map<Key, Certificate> entries : java.util.Arrays.asList(hot.values, cold.values))
                for (Map.Entry<Key, Certificate> entry : entries.entrySet())
                    grouped.computeIfAbsent(page(entry.getKey().cell), ignored -> new LinkedHashMap<>())
                        .put(entry.getKey(), entry.getValue());
            Map<Long, Page> result = new LinkedHashMap<>();
            for (Map.Entry<Long, Map<Key, Certificate>> entry : grouped.entrySet()) {
                Map<Long, HologramSegmentDocument.Record> members = new HashMap<>();
                Set<Key> pageHot = new HashSet<>();
                for (Map.Entry<Key, Certificate> certificate : entry.getValue()
                    .entrySet()) {
                    members.put(certificate.getKey().cell, certificate.getValue().record);
                    if (protectedKeys.contains(certificate.getKey())) pageHot.add(certificate.getKey());
                }
                result.put(entry.getKey(), new Page(members, entry.getValue(), pageHot));
            }
            bytes = 512 + owners.bytes + relationBytes + networkBytes;
            return new HologramAnalysisDocument(result, this);
        }

        void publish() {
            if (source == null) return;
            if (!enabled) {
                source.analysisDocument = null;
                return;
            }
            long retainedBytes = 512 + owners.bytes + relationBytes + networkBytes;
            HologramProcessGovernor.Lease lease = HologramRetainedCache
                .tryReserve("analysis.domain-cache", retainedBytes);
            if (lease == null) {
                releaseStagingLease();
                lease = HologramRetainedCache.tryReserve("analysis.domain-cache", retainedBytes);
            }
            if (lease == null) {
                source.analysisDocument = null;
                close();
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

        private void releaseStagingLease() {
            if (stagingLease != null) {
                stagingLease.close();
                stagingLease = null;
            }
        }

        @Override
        public void close() {
            releaseStagingLease();
            hot.clear();
            cold.clear();
            owners.recordReferences.clear();
            owners.factReferences.clear();
            owners.bytes = 0;
            networkNodes.clear();
            relationSources.clear();
            relationTargets.clear();
            relationAffectedPages.clear();
            relations = null;
            components = null;
            lastCell = null;
            lastRecord = null;
        }
    }

    static final class Page {

        private final Map<Long, HologramSegmentDocument.Record> records;
        private final Map<Key, Certificate> certificates;
        private final Set<Key> hot;

        Page(Map<Long, HologramSegmentDocument.Record> records, Map<Key, Certificate> certificates, Set<Key> hot) {
            this.hot = Collections.unmodifiableSet(hot);
            this.records = Collections.unmodifiableMap(records);
            this.certificates = Collections.unmodifiableMap(new LinkedHashMap<>(certificates));
        }
    }

    /** Unique retained owners; the two admission queues share records and payload facts without double charging. */
    private static class Owners {

        final Map<HologramSegmentDocument.Record, Integer> recordReferences = new java.util.IdentityHashMap<>();
        final Map<HologramNbtObligations.Facts, Integer> factReferences = new java.util.IdentityHashMap<>();
        long bytes;

        long addition(Certificate value) {
            return value.cost + (recordReferences.containsKey(value.record) ? 0 : value.recordWeight)
                + (factReferences.containsKey(value.record.nbt) ? 0 : value.record.nbt.weight);
        }

        void acquire(Certificate value) {
            bytes += addition(value);
            recordReferences.merge(value.record, 1, Integer::sum);
            factReferences.merge(value.record.nbt, 1, Integer::sum);
        }

        void release(Certificate old) {
            bytes -= old.cost;
            int recordCount = recordReferences.get(old.record) - 1;
            if (recordCount == 0) {
                recordReferences.remove(old.record);
                bytes -= old.recordWeight;
            } else recordReferences.put(old.record, recordCount);
            int factCount = factReferences.get(old.record.nbt) - 1;
            if (factCount == 0) {
                factReferences.remove(old.record.nbt);
                bytes -= old.record.nbt.weight;
            } else factReferences.put(old.record.nbt, factCount);
        }
    }

    /** Bounded per-lane indexes; their per-entry/map overhead is part of Certificate/recordWeight. */
    private static final class Lane extends Owners {

        final LinkedHashMap<Key, Certificate> values = new LinkedHashMap<>(16, .75f, true);
        final Map<Long, HologramSegmentDocument.Record> records = new HashMap<>();
        private final Owners shared;

        Lane(Owners shared) {
            this.shared = shared;
        }

        void add(Key key, Certificate value) {
            acquire(value);
            shared.acquire(value);
            values.put(key, value);
            records.put(value.record.id, value.record);
        }

        void remove(Key key) {
            Certificate old = values.remove(key);
            if (old == null) return;
            release(old);
            shared.release(old);
            if (!recordReferences.containsKey(old.record)) records.remove(old.record.id, old.record);
        }

        void evict() {
            remove(
                values.keySet()
                    .iterator()
                    .next());
        }

        void clear() {
            values.clear();
            records.clear();
            recordReferences.clear();
            factReferences.clear();
            bytes = 0;
        }
    }

    private static final class Certificate {

        final HologramSegmentDocument.Record record;
        final HologramSegmentContract.Domain domain;
        final Object[] dependencies;
        final NBTTagCompound result;
        final long cost, recordWeight;

        Certificate(HologramSegmentDocument.Record record, HologramSegmentContract.Domain domain, Object[] dependencies,
            NBTTagCompound result, long cost, long recordWeight) {
            this.record = record;
            this.domain = domain;
            this.dependencies = dependencies.clone();
            this.result = (NBTTagCompound) result.copy();
            this.cost = cost;
            this.recordWeight = recordWeight;
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

    private static long inputEstimate(HologramPresets.Cell cell) throws IOException {
        long cost = 4096L + 2L * (textLength(cell.tileNbt) + textLength(cell.itemNbt))
            + 64L * (HologramNbtObligations.expandedBytes(cell.tileNbt)
                + HologramNbtObligations.expandedBytes(cell.itemNbt));
        for (String text : new String[] { cell.item, cell.block, cell.defaultItem, cell.prompt, cell.structureId })
            cost += 2L * textLength(text);
        if (cell.structureIds != null) for (String text : cell.structureIds) cost += 64L + 2L * textLength(text);
        if (cell.allowedTargets != null) for (HologramPresets.Target target : cell.allowedTargets)
            cost += 128L + (target == null ? 0 : 2L * textLength(target.item));
        return cost;
    }

    private static long recordCost(HologramPresets.Cell cell, HologramSegmentDocument.Record input) {
        long cost = 1024L + input.nbt.weight;
        for (String text : new String[] { cell.item, cell.block, cell.defaultItem, cell.prompt, cell.structureId })
            cost += 2L * textLength(text);
        if (cell.structureIds != null) for (String text : cell.structureIds) cost += 64L + 2L * textLength(text);
        if (cell.allowedTargets != null) for (HologramPresets.Target target : cell.allowedTargets)
            cost += 128L + (target == null ? 0 : 2L * textLength(target.item));
        return cost;
    }

    private static long dependencyWeight(Object value) {
        if (value instanceof Object[]) {
            long cost = 64;
            for (Object entry : (Object[]) value) cost += 32 + dependencyWeight(entry);
            return cost;
        }
        return value instanceof String ? 64L + 2L * ((String) value).length() : 64L;
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
