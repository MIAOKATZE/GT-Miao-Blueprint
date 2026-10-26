package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable common-side page manifest. DTO reconciliation is the conservative legacy edit adapter. */
final class HologramSegmentDocument {

    private static final AtomicLong AUTHORITIES = new AtomicLong();
    private static final AtomicLong STAMPS = new AtomicLong();
    final long authority;
    final Map<Long, Record> records;
    final Map<Long, Page> pages;
    private final Map<Position, Long> positions;
    private final Set<String> structures;
    final Map<String, Set<Long>> structurePages;
    private final Map<String, Boolean> structureEnabled;
    final long expandedOccurrences;
    final long nextId;
    final long schemaChecks, reusedPages;
    final long revision;
    final Set<Long> changedRecordIds, removedRecordIds;
    private final Object[] policy;

    private HologramSegmentDocument(long authority, Map<Long, Record> records, Map<Long, Page> pages,
        Map<Position, Long> positions, Set<String> structures, long expanded, long nextId, long checks, long reused,
        Object[] policy, Map<String, Boolean> structureEnabled, Map<String, Set<Long>> structurePages,
        HologramSegmentDocument before) {
        this.authority = authority;
        this.records = Collections.unmodifiableMap(records);
        this.pages = Collections.unmodifiableMap(pages);
        this.positions = Collections.unmodifiableMap(positions);
        this.structures = Collections.unmodifiableSet(new HashSet<>(structures));
        expandedOccurrences = expanded;
        this.nextId = nextId;
        schemaChecks = checks;
        reusedPages = reused;
        this.policy = policy;
        revision = before == null ? 1 : before.revision + 1;
        Set<Long> changed = new HashSet<>(), removed = new HashSet<>();
        for (Record record : records.values())
            if (before == null || before.records.get(record.id) != record) changed.add(record.id);
        if (before != null) for (long id : before.records.keySet()) if (!records.containsKey(id)) removed.add(id);
        changedRecordIds = Collections.unmodifiableSet(changed);
        removedRecordIds = Collections.unmodifiableSet(removed);
        this.structureEnabled = Collections.unmodifiableMap(structureEnabled);
        Map<String, Set<Long>> frozenMembership = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : structurePages.entrySet())
            frozenMembership.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        this.structurePages = Collections.unmodifiableMap(frozenMembership);
    }

    static HologramSegmentDocument validate(HologramPresets.Preset preset, Set<String> structures,
        HologramBlueprintValidationCache validation, long attachmentBytes) throws IOException {
        boolean cacheEnabled = !"false".equals(System.getProperty("gtmb.segment.validationCache"));
        // This legacy adapter retains at most a bounded immutable certificate working set. Large inputs
        // use the exact former path, before allocating page/record snapshots.
        if (!cacheEnabled || !manifestAdmissible(preset))
            return validateUncached(preset, structures, validation, attachmentBytes);
        long nativeEpoch = HologramNativePlacement.generation();
        long codecEpoch = com.miaokatze.gtmb.api.ResourceContainers.generation();
        HologramSegmentDocument before = cacheEnabled ? preset.segmentDocument : null;
        long authority = before == null ? AUTHORITIES.incrementAndGet() : before.authority;
        long nextId = before == null ? 1 : before.nextId;
        Map<Long, Record> records = new LinkedHashMap<>();
        Map<Long, List<Record>> grouped = new LinkedHashMap<>();
        Map<Long, HologramPresets.Cell> sources = new HashMap<>();
        Map<List<Object>, HologramNbtObligations.Facts> stageFacts = new HashMap<>();
        long observedExpanded = attachmentBytes;
        for (int kind = 0; kind < 2; kind++) {
            List<HologramPresets.Cell> cells = kind == 0 ? preset.cells : preset.palette;
            for (HologramPresets.Cell cell : cells) {
                if (cell == null) throw new IOException("蓝图方块无效");
                if (cell.segmentCellId <= 0 || records.containsKey(cell.segmentCellId)) cell.segmentCellId = nextId++;
                nextId = Math.max(nextId, cell.segmentCellId + 1);
                long id = cell.segmentCellId;
                Record old = before == null ? null : before.records.get(id);
                List<Object> sourceKey = HologramNbtObligations.sourceKey(cell);
                boolean same = old != null && old.matches(cell, kind == 0);
                HologramNbtObligations.Facts facts = same ? old.nbt : stageFacts.get(sourceKey);
                if (facts == null) facts = HologramNbtObligations.analyze(cell);
                stageFacts.putIfAbsent(sourceKey, facts);
                Record record = same ? old : new Record(id, cell, kind == 0, facts);
                observedExpanded += record.expanded;
                if (observedExpanded > 64L * 1024 * 1024)
                    throw new IOException("蓝图设备NBT展开总量超过64MiB上限；请减少保存的NBT条目或拆分蓝图");
                records.put(id, record);
                sources.put(id, cell);
                grouped.computeIfAbsent((id - 1) / HologramSegmentContract.PAGE_SIZE, ignored -> new ArrayList<>())
                    .add(record);
            }
        }
        // Stage removals before additions: a swap is legal, an occupied destination is not.
        Map<Position, Long> positions = before == null ? new HashMap<>() : new HashMap<>(before.positions);
        long expanded = before == null ? 0 : before.expandedOccurrences;
        if (before != null) for (Record old : before.records.values()) {
            if (records.get(old.id) != old) {
                if (old.placed) positions.remove(old.position);
                expanded -= old.expanded;
            }
        }
        for (Record record : records.values()) {
            if (before != null && before.records.get(record.id) == record) continue;
            expanded += record.expanded;
            if (record.placed && positions.put(record.position, record.id) != null) throw new IOException("蓝图位置重复");
        }
        if (expanded + attachmentBytes > 64L * 1024 * 1024)
            throw new IOException("蓝图设备NBT展开总量超过64MiB上限；请减少保存的NBT条目或拆分蓝图");
        Map<String, Boolean> enabled = new HashMap<>();
        for (HologramPresets.Structure structure : preset.structures) enabled.put(structure.id, structure.enabled);
        Set<String> changedStructures = new HashSet<>(structures);
        if (before != null) {
            changedStructures.addAll(before.structures);
            changedStructures.removeIf(id -> Objects.equals(enabled.get(id), before.structureEnabled.get(id)));
        }
        Map<String, Set<Long>> structurePages = before == null ? new HashMap<>() : new HashMap<>(before.structurePages);
        if (before != null) for (Page page : before.pages.values()) {
            if (!grouped.containsKey(page.id))
                for (String id : page.memberships) updateMembershipIndex(structurePages, id, page.id, false);
        }
        Object[] policy = new Object[] { preset.retainNbt, preset.retainCovers,
            preset.retainedNbtKeys == null ? null : preset.retainedNbtKeys.toArray(), preset.aePolicyVersion,
            preset.deidentificationVersion, preset.deidentificationLevel };
        boolean samePolicy = before != null && Arrays.deepEquals(policy, before.policy);
        Map<Long, Page> pages = new LinkedHashMap<>();
        long checks = 0, reused = 0;
        for (Map.Entry<Long, List<Record>> entry : grouped.entrySet()) {
            entry.getValue()
                .sort((left, right) -> Long.compare(left.id, right.id));
            Record[] content = entry.getValue()
                .toArray(new Record[0]);
            Page old = before == null ? null : before.pages.get(entry.getKey());
            boolean sameRecords = old != null && Arrays.equals(content, old.records);
            Set<String> references = sameRecords ? old.memberships : new HashSet<>();
            if (!sameRecords) {
                for (Record record : content) references.addAll(record.memberships());
                if (old != null)
                    for (String id : old.memberships) updateMembershipIndex(structurePages, id, old.id, false);
                for (String id : references) updateMembershipIndex(structurePages, id, entry.getKey(), true);
            }
            boolean sameMembership = old != null;
            for (String id : references) {
                if (changedStructures.contains(id)) sameMembership = false;
            }
            Page page = old != null && samePolicy && sameMembership && sameRecords ? old
                : new Page(authority, entry.getKey(), content, old, sameMembership, references, policy);
            boolean schemaHit = old != null && page.stamp(HologramSegmentContract.Domain.SCHEMA)
                .sameInput(old.stamp(HologramSegmentContract.Domain.SCHEMA));
            boolean membershipHit = sameMembership && old != null
                && page.stamp(HologramSegmentContract.Domain.RELATIONS)
                    .sameInput(old.stamp(HologramSegmentContract.Domain.RELATIONS));
            if (schemaHit) reused++;
            for (Record record : content) {
                HologramPresets.Cell cell = sources.get(record.id);
                if (!schemaHit) {
                    HologramPresets.validateBlueprintCell(cell, record.placed, validation);
                    checks++;
                } else HologramPresets.validateMaterialIdentity(cell, record.placed, validation);
                if (!membershipHit) HologramPresets.validateMembership(cell, structures);
                // Native/material identity is deliberately outside the persistent schema certificate.
                if (record.hasPayload) validation.filter(cell);
                else {
                    // Proven absence has no binary/resource/binding subtree to inspect.
                    cell.tileNbt = null;
                    cell.itemNbt = null;
                }
            }
            pages.put(entry.getKey(), page);
        }
        // Filtering may rewrite encodings/block aliases. Reconcile the resulting values next invocation;
        // never certify source input as the filtered output merely because this call succeeded.
        if (nativeEpoch != HologramNativePlacement.generation()
            || codecEpoch != com.miaokatze.gtmb.api.ResourceContainers.generation())
            throw new IOException("蓝图校验期间原生或资源描述符发生变化；请重新校验");
        return cacheEnabled
            ? new HologramSegmentDocument(
                authority,
                records,
                pages,
                positions,
                structures,
                expanded,
                nextId,
                checks,
                reused,
                policy,
                enabled,
                structurePages,
                before)
            : null;
    }

    private static void updateMembershipIndex(Map<String, Set<Long>> index, String id, long page, boolean add) {
        Set<Long> pages = index.containsKey(id) ? new HashSet<>(index.get(id)) : new HashSet<>();
        if (add) pages.add(page);
        else pages.remove(page);
        if (pages.isEmpty()) index.remove(id);
        else index.put(id, pages);
    }

    private static boolean manifestAdmissible(HologramPresets.Preset preset) throws IOException {
        long bytes = 0;
        Set<List<Object>> projected = new HashSet<>();
        for (List<HologramPresets.Cell> cells : Arrays.asList(preset.cells, preset.palette)) {
            for (HologramPresets.Cell cell : cells) {
                if (cell == null) return false;
                // Charge aliased source strings as resident inputs as well as the new immutable arrays.
                bytes += 1024L + (cell.allowedTargets == null ? 0 : 96L * cell.allowedTargets.size())
                    + (cell.structureIds == null ? 0 : 48L * cell.structureIds.size());
                bytes += chars(cell.item) + chars(cell.block)
                    + chars(cell.defaultItem)
                    + chars(cell.prompt)
                    + chars(cell.structureId)
                    + chars(cell.tileNbt)
                    + chars(cell.itemNbt);
                // Reserve immutable typed projection weight before creating it, including legacy resident aliases.
                if ((cell.tileNbt != null && !cell.tileNbt.isEmpty() || cell.itemNbt != null && !cell.itemNbt.isEmpty())
                    && projected.add(HologramNbtObligations.sourceKey(cell)))
                    bytes += 64L * (HologramBlueprintNbt.expandedBytes(cell.tileNbt)
                        + HologramBlueprintNbt.expandedBytes(cell.itemNbt));
                if (bytes > 64L * 1024 * 1024) return false;
            }
        }
        return true;
    }

    private static long chars(String value) {
        return value == null ? 0 : 2L * value.length();
    }

    private static HologramSegmentDocument validateUncached(HologramPresets.Preset preset, Set<String> structures,
        HologramBlueprintValidationCache validation, long expanded) throws IOException {
        Set<Position> positions = new HashSet<>();
        for (int kind = 0; kind < 2; kind++) {
            for (HologramPresets.Cell cell : kind == 0 ? preset.cells : preset.palette) {
                if (cell == null) throw new IOException("蓝图方块无效");
                expanded += (long) HologramBlueprintNbt.expandedBytes(cell.tileNbt)
                    + HologramBlueprintNbt.expandedBytes(cell.itemNbt);
                if (expanded > 64L * 1024 * 1024) throw new IOException("蓝图设备NBT展开总量超过64MiB上限");
                HologramPresets.validateBlueprintCell(cell, kind == 0, validation);
                if (kind == 0 && !positions.add(new Position(cell.x, cell.y, cell.z))) throw new IOException("蓝图位置重复");
                HologramPresets.validateMembership(cell, structures);
                validation.filter(cell);
            }
        }
        return null;
    }

    static final class Page {

        final long id, generation;
        private final Record[] records;
        private final Set<String> memberships;
        private final EnumMap<HologramSegmentContract.Domain, HologramSegmentContract.Stamp> stamps = new EnumMap<>(
            HologramSegmentContract.Domain.class);
        private final EnumMap<HologramSegmentContract.Domain, Object[]> policies = new EnumMap<>(
            HologramSegmentContract.Domain.class);

        Page(long authority, long id, Record[] records, Page old, boolean sameMembership, Set<String> memberships,
            Object[] policy) {
            this.id = id;
            this.records = records;
            this.memberships = Collections.unmodifiableSet(memberships);
            generation = old != null && Arrays.equals(records, old.records) ? old.generation : STAMPS.incrementAndGet();
            for (HologramSegmentContract.Domain domain : HologramSegmentContract.Domain.values()) {
                Object[] selectedPolicy = new Object[records.length];
                for (int index = 0; index < records.length; index++)
                    selectedPolicy[index] = records[index].nbt.policy(domain, policy);
                policies.put(domain, selectedPolicy);
                boolean same = old != null && records.length == old.records.length
                    && Arrays.deepEquals(selectedPolicy, old.policies.get(domain))
                    && (sameMembership || domain != HologramSegmentContract.Domain.RELATIONS
                        && domain != HologramSegmentContract.Domain.ASSET_NEEDS);
                if (same) for (int index = 0; index < records.length; index++) {
                    if (records[index].id != old.records[index].id
                        || !records[index].sameInput(old.records[index], domain)) {
                        same = false;
                        break;
                    }
                }
                stamps.put(
                    domain,
                    same ? old.stamp(domain)
                        : new HologramSegmentContract.Stamp(authority, id, domain, STAMPS.incrementAndGet()));
            }
        }

        HologramSegmentContract.Stamp stamp(HologramSegmentContract.Domain domain) {
            return stamps.get(domain);
        }

        List<Record> records() {
            return Collections.unmodifiableList(Arrays.asList(records));
        }
    }

    static final class Record {

        final long id, expanded;
        final boolean placed;
        final boolean hasPayload;
        final Position position;
        final HologramNbtObligations.Facts nbt;
        private final String tilePayload, itemPayload;
        private final Object[] shape, assets, relations, appearance, all;

        Record(long id, HologramPresets.Cell cell, boolean placed) throws IOException {
            this(id, cell, placed, HologramNbtObligations.analyze(cell));
        }

        Record(long id, HologramPresets.Cell cell, boolean placed, HologramNbtObligations.Facts facts)
            throws IOException {
            this.id = id;
            this.placed = placed;
            hasPayload = cell.tileNbt != null && !cell.tileNbt.isEmpty()
                || cell.itemNbt != null && !cell.itemNbt.isEmpty();
            position = new Position(cell.x, cell.y, cell.z);
            tilePayload = cell.tileNbt;
            itemPayload = cell.itemNbt;
            nbt = facts;
            shape = shape(cell);
            assets = new Object[] { cell.item, cell.block, cell.damage, cell.metadata,
                nbt.input(HologramSegmentContract.Domain.ASSET_NEEDS), cell.defaultItem, cell.defaultDamage, cell.count,
                cell.excluded, cell.controller, placed };
            relations = new Object[] { cell.x, cell.y, cell.z, cell.structureId,
                cell.structureIds == null ? null : cell.structureIds.toArray(),
                nbt.input(HologramSegmentContract.Domain.RELATIONS), placed, cell.item, cell.block, cell.damage,
                cell.metadata, cell.facing, cell.controller };
            appearance = new Object[] { cell.block, cell.metadata, cell.facing, cell.prompt,
                nbt.input(HologramSegmentContract.Domain.APPEARANCE), cell.excluded, placed };
            all = new Object[] { shape, assets, relations, appearance, cell.defaultDamage, cell.controller, placed,
                nbt.input(HologramSegmentContract.Domain.REPORT) };
            expanded = (long) HologramNbtObligations.expandedBytes(cell.tileNbt)
                + HologramNbtObligations.expandedBytes(cell.itemNbt);
        }

        boolean matches(HologramPresets.Cell cell, boolean placed) {
            return this.placed == placed && shapeMatches(cell)
                && Objects.equals(tilePayload, cell.tileNbt)
                && Objects.equals(itemPayload, cell.itemNbt)
                && (HologramSnapshotDataContext.active()
                    ? nbt.descriptor.equals(HologramSnapshotDataContext.descriptor(cell))
                    : nbt.descriptor.current(cell.block))
                && Objects.equals(relations[3], cell.structureId)
                && sameStrings((Object[]) relations[4], cell.structureIds)
                && (Boolean) appearance[5] == cell.excluded
                && (Integer) all[4] == cell.defaultDamage
                && (Boolean) all[5] == cell.controller;
        }

        /** Lightweight owner-side binding; transport must still compare its complete frozen dynamic DTO. */
        boolean at(long x, long y, long z) {
            return position.x == x && position.y == y && position.z == z;
        }

        private boolean shapeMatches(HologramPresets.Cell cell) {
            if (!Objects.equals(shape[0], cell.item) || !Objects.equals(shape[1], cell.block)
                || !Objects.equals(shape[2], cell.defaultItem)
                || (Integer) shape[3] != cell.damage
                || (Integer) shape[4] != cell.metadata
                || (Integer) shape[5] != cell.count
                || (Integer) shape[6] != cell.facing
                || (Integer) shape[7] != cell.x
                || (Integer) shape[8] != cell.y
                || (Integer) shape[9] != cell.z
                || !Objects.equals(shape[10], cell.prompt)) return false;
            Object[] targets = (Object[]) shape[11];
            if (targets == null || cell.allowedTargets == null) return targets == null && cell.allowedTargets == null;
            if (targets.length != cell.allowedTargets.size()) return false;
            for (int index = 0; index < targets.length; index++) {
                Object[] previous = (Object[]) targets[index];
                HologramPresets.Target target = cell.allowedTargets.get(index);
                if (previous == null || target == null) {
                    if (previous != null || target != null) return false;
                } else if (!Objects.equals(previous[0], target.item) || (Integer) previous[1] != target.damage)
                    return false;
            }
            return true;
        }

        private static boolean sameStrings(Object[] values, List<String> current) {
            if (values == null || current == null) return values == null && current == null;
            if (values.length != current.size()) return false;
            for (int index = 0; index < values.length; index++)
                if (!Objects.equals(values[index], current.get(index))) return false;
            return true;
        }

        private Object[] input(HologramSegmentContract.Domain domain) {
            switch (domain) {
                case SCHEMA:
                    return shape;
                case ASSET_NEEDS:
                    return assets;
                case RELATIONS:
                    return relations;
                case APPEARANCE:
                    return appearance;
                case MESH:
                    return new Object[] { appearance, position.x, position.y, position.z };
                default:
                    return all;
            }
        }

        boolean sameInput(Record other, HologramSegmentContract.Domain domain) {
            return other != null && id == other.id && Arrays.deepEquals(input(domain), other.input(domain));
        }

        boolean coverageComplete() {
            return nbt.coverageComplete();
        }

        boolean needsAssets() {
            return nbt.needsAssets();
        }

        boolean needsBindings() {
            return nbt.needsBindings();
        }

        Set<String> claimedPaths() {
            return nbt.claimedPaths;
        }

        Set<String> unclaimedPaths() {
            return nbt.unclaimedPaths;
        }

        List<String> memberships() {
            List<String> result = new ArrayList<>();
            if (relations[3] instanceof String && !((String) relations[3]).isEmpty()) result.add((String) relations[3]);
            Object[] ids = (Object[]) relations[4];
            if (ids != null) for (Object id : ids) if (id instanceof String) result.add((String) id);
            return result;
        }

        private static Object[] shape(HologramPresets.Cell cell) {
            Object[] targets = null;
            if (cell.allowedTargets != null) {
                targets = new Object[cell.allowedTargets.size()];
                for (int index = 0; index < targets.length; index++) {
                    HologramPresets.Target target = cell.allowedTargets.get(index);
                    targets[index] = target == null ? null : new Object[] { target.item, target.damage };
                }
            }
            return new Object[] { cell.item, cell.block, cell.defaultItem, cell.damage, cell.metadata, cell.count,
                cell.facing, cell.x, cell.y, cell.z, cell.prompt, targets };
        }
    }

    private static final class Position {

        final int x, y, z;

        Position(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int hashCode() {
            return (31 * x + y) * 31 + z;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Position)) return false;
            Position other = (Position) value;
            return x == other.x && y == other.y && z == other.z;
        }
    }
}
