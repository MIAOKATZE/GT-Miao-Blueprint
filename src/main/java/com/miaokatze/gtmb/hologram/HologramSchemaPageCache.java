package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A bounded working set of exact shape proofs, never a complete-document admission certificate. */
final class HologramSchemaPageCache {

    static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final long ROOT_STAGING_BYTES = 128 * 1024, PAGE_BYTES = 128 * 1024;
    static final int MAX_PAGES = 256;
    private static final long ROOT_BUDGET_BYTES = 4096 + 128L * MAX_PAGES;
    final Map<Long, Page> pages;
    final long nextId, scanCursor, checks, reused, retainedBytes;

    private HologramSchemaPageCache(Map<Long, Page> pages, long nextId, long scanCursor, long checks, long reused,
        HologramProcessGovernor.Lease rootLease) {
        this.pages = Collections.unmodifiableMap(pages);
        this.nextId = nextId;
        this.scanCursor = scanCursor;
        this.checks = checks;
        this.reused = reused;
        long bytes = rootLease.bytes();
        for (Page page : pages.values()) bytes += page.weight;
        retainedBytes = bytes;
        HologramRetainedCache.watch(this)
            .add(rootLease);
    }

    /** An owner-side scheduling hint only: it grants no validation authority. */
    static void prefer(HologramPresets.Preset preset, long cellId) {
        if (preset != null && cellId > 0) preset.schemaPreferredCellId = cellId;
    }

    static Stage begin(HologramPresets.Preset preset) {
        HologramProcessGovernor.Lease root = "false".equals(System.getProperty("gtmb.segment.validationCache")) ? null
            : HologramRetainedCache.tryReserve("schema.pages.staging", ROOT_STAGING_BYTES);
        try {
            return new Stage(preset, root);
        } catch (RuntimeException | Error failed) {
            if (root != null) root.close();
            throw failed;
        }
    }

    static final class Stage implements AutoCloseable {

        private final HologramSchemaPageCache before;
        private final Map<Long, Page> pages = new LinkedHashMap<>();
        private final Map<Long, Page> owned = new HashMap<>();
        private final Set<Long> seen = new HashSet<>(), blocked = new HashSet<>();
        private HologramProcessGovernor.Lease root;
        private final long explore, preferred;
        private long nextId, checks, reused, bytes = ROOT_BUDGET_BYTES;
        private Builder builder;
        private long builderKey;

        Stage(HologramPresets.Preset preset, HologramProcessGovernor.Lease root) {
            this.root = root;
            before = root == null ? null : preset.schemaPageCache;
            nextId = before == null ? 1 : before.nextId;
            if (preset.segmentDocument != null) nextId = Math.max(nextId, preset.segmentDocument.nextId);
            preferred = preset.schemaPreferredCellId <= 0 ? -1 : page(preset.schemaPreferredCellId);
            long count = (long) preset.cells.size() + preset.palette.size();
            long pageCount = Math.max(
                1,
                (Math.max(count, nextId - 1) + HologramSegmentContract.PAGE_SIZE - 1)
                    / HologramSegmentContract.PAGE_SIZE);
            long candidate = before == null ? 0 : (before.scanCursor + 1) % pageCount;
            if (before != null) {
                for (int i = 0; i < MAX_PAGES && before.pages.containsKey(candidate); i++)
                    candidate = (candidate + 1) % pageCount;
                int reserve = before.pages.containsKey(candidate) ? 0 : 1;
                if (preferred >= 0 && preferred != candidate && !before.pages.containsKey(preferred)) reserve++;
                for (Map.Entry<Long, Page> entry : before.pages.entrySet()) {
                    pages.put(entry.getKey(), entry.getValue());
                    bytes += entry.getValue().weight;
                }
                java.util.Iterator<Map.Entry<Long, Page>> iterator = pages.entrySet()
                    .iterator();
                while (iterator.hasNext()
                    && (pages.size() > MAX_PAGES - reserve || bytes > MAX_BYTES - reserve * PAGE_BYTES)) {
                    Map.Entry<Long, Page> entry = iterator.next();
                    if (entry.getKey() == preferred) continue;
                    bytes -= entry.getValue().weight;
                    iterator.remove();
                }
            }
            explore = candidate;
        }

        void validate(HologramPresets.Cell cell, boolean placed, HologramBlueprintValidationCache validation)
            throws IOException {
            long id = identity(cell);
            Page old = before == null || id <= 0 ? null : before.pages.get(page(id));
            Shape shape = old == null ? null : old.shapes.get(id);
            if (shape != null && shape.matches(cell, placed)) reused++;
            else {
                HologramPresets.validateBlueprintCellShape(cell, validation);
                checks++;
                shape = null;
            }
            remember(cell, placed, id, shape);
            // Registry/native/material obligations are intentionally outside this pure shape proof.
            HologramPresets.validateMaterialIdentity(cell, placed, validation);
        }

        /** The complete manifest path already performed the shape checks or proved the exact same input. */
        void rememberValidated(HologramPresets.Cell cell, boolean placed) {
            long id = identity(cell);
            Page old = before == null || id <= 0 ? null : before.pages.get(page(id));
            Shape shape = old == null ? null : old.shapes.get(id);
            remember(cell, placed, id, shape != null && shape.matches(cell, placed) ? shape : null);
        }

        private long identity(HologramPresets.Cell cell) {
            if (cell == null) return -1;
            long id = cell.segmentCellId;
            if (root == null) return id;
            if (id <= 0 && nextId > 0 && nextId < Long.MAX_VALUE) cell.segmentCellId = id = nextId++;
            if (id > 0 && id < Long.MAX_VALUE) nextId = Math.max(nextId, id + 1);
            return id;
        }

        private void remember(HologramPresets.Cell cell, boolean placed, long id, Shape known) {
            if (root == null || id <= 0) return;
            long key = page(id);
            if (blocked.contains(key)) return;
            if (builder != null && builderKey != key) finishBuilder();
            int coldReserve = preferred >= 0 && !pages.containsKey(preferred)
                && (builder == null || builderKey != preferred) ? 1 : 0;
            boolean wanted = pages.containsKey(key) || builder != null && builderKey == key
                || key == explore
                || key == preferred
                || before == null && blocked.size() < MAX_PAGES
                    && pages.size() < MAX_PAGES - coldReserve
                    && bytes + 4096 < MAX_BYTES - coldReserve * PAGE_BYTES;
            if (!wanted) return;
            seen.add(key);
            Page old = pages.get(key);
            if (builder == null && known != null && old != null && old.shapes.get(id) == known) return;
            if (builder == null) {
                if (pages.size() >= MAX_PAGES && old == null) return;
                HologramProcessGovernor.Lease lease = HologramRetainedCache.tryReserve("schema.page", PAGE_BYTES);
                if (lease == null) return;
                builder = new Builder(old, lease);
                builderKey = key;
                pages.remove(key);
                Page previousOwned = owned.remove(key);
                if (previousOwned != null) previousOwned.closeUnpublished();
                if (old != null) bytes -= old.weight;
                bytes += builder.weight;
            }
            Shape previous = builder.shapes.get(id);
            if (known != null && previous == known) return;
            if (known == null && previous != null && builder.weight + Shape.weight(cell) > PAGE_BYTES) {
                dropBuilder();
                return;
            }
            long oldWeight = builder.weight;
            boolean fits = builder.prepare(cell, known, previous);
            bytes += builder.weight - oldWeight;
            if (!fits || bytes > MAX_BYTES) {
                dropBuilder();
                return;
            }
            Shape value = known == null ? new Shape(cell, placed, Shape.weight(cell)) : known;
            builder.shapes.put(id, value);
        }

        private void dropBuilder() {
            bytes -= builder.weight;
            builder.close();
            builder = null;
            if (blocked.size() < MAX_PAGES) blocked.add(builderKey);
        }

        private void finishBuilder() {
            if (builder == null) return;
            // Actual resident ownership is acquired while the candidate scratch is still charged.
            HologramProcessGovernor.Lease actual = HologramRetainedCache
                .tryReserve("schema.page.resident", builder.weight);
            if (actual == null) {
                dropBuilder();
                return;
            }
            Page result = builder.finish(actual);
            builder = null;
            pages.put(builderKey, result);
            owned.put(builderKey, result);
        }

        HologramSchemaPageCache finish() {
            if (root == null) return null;
            finishBuilder();
            pages.keySet()
                .retainAll(seen);
            HologramProcessGovernor.Lease actualRoot = HologramRetainedCache
                .tryReserve("schema.pages.manifest", 4096 + 128L * pages.size());
            if (actualRoot == null) {
                close();
                return null;
            }
            HologramSchemaPageCache result = new HologramSchemaPageCache(
                new LinkedHashMap<>(pages),
                nextId,
                explore,
                checks,
                reused,
                actualRoot);
            for (Page page : owned.values()) page.publish();
            owned.clear();
            root.close();
            root = null;
            return result;
        }

        @Override
        public void close() {
            if (builder != null) builder.close();
            builder = null;
            for (Page page : owned.values()) page.closeUnpublished();
            owned.clear();
            pages.clear();
            if (root != null) root.close();
            root = null;
        }
    }

    private static long page(long id) {
        return (id - 1) / HologramSegmentContract.PAGE_SIZE;
    }

    static final class Page {

        final Map<Long, Shape> shapes;
        final Map<String, Integer> texts;
        final long weight;
        private HologramProcessGovernor.Lease unpublished;

        Page(Map<Long, Shape> shapes, Map<String, Integer> texts, long weight, HologramProcessGovernor.Lease lease) {
            this.shapes = Collections.unmodifiableMap(shapes);
            this.texts = Collections.unmodifiableMap(texts);
            this.weight = weight;
            unpublished = lease;
        }

        void publish() {
            HologramRetainedCache.watch(this)
                .add(unpublished);
            unpublished = null;
        }

        void closeUnpublished() {
            if (unpublished != null) unpublished.close();
            unpublished = null;
        }
    }

    private static final class Builder implements AutoCloseable {

        final Map<Long, Shape> shapes;
        final Map<String, Integer> texts;
        long weight;
        private HologramProcessGovernor.Lease lease;

        Builder(Page old, HologramProcessGovernor.Lease lease) {
            this.lease = lease;
            shapes = old == null ? new HashMap<>() : new HashMap<>(old.shapes);
            texts = old == null ? new IdentityHashMap<>() : new IdentityHashMap<>(old.texts);
            weight = old == null ? 4096 : old.weight;
        }

        boolean prepare(HologramPresets.Cell cell, Shape known, Shape previous) {
            if (previous != null) {
                weight -= previous.weight;
                previous.texts(this, -1);
            }
            long shapeBytes = known == null ? Shape.weight(cell) : known.weight;
            if (shapeBytes > PAGE_BYTES - weight) return false;
            weight += shapeBytes;
            return known == null ? Shape.texts(cell, this) : known.texts(this, 1);
        }

        boolean text(String value, int direction) {
            if (value == null) return true;
            Integer count = texts.get(value);
            if (direction < 0) {
                if (count == 1) {
                    texts.remove(value);
                    weight -= textWeight(value);
                } else texts.put(value, count - 1);
            } else if (count != null) texts.put(value, count + 1);
            else {
                long cost = textWeight(value);
                if (cost > PAGE_BYTES - weight) return false;
                texts.put(value, 1);
                weight += cost;
            }
            return true;
        }

        private static long textWeight(String value) {
            // One actual String identity, its characters and the identity-counter entry, once per page.
            return 112L + 2L * value.length();
        }

        Page finish(HologramProcessGovernor.Lease actual) {
            Page result = new Page(shapes, texts, weight, actual);
            lease.close();
            lease = null;
            return result;
        }

        @Override
        public void close() {
            shapes.clear();
            texts.clear();
            if (lease != null) lease.close();
            lease = null;
        }
    }

    /** Fields read by validateBlueprintCellShape, copied exactly; no hash or mutable DTO is a proof. */
    private static final class Shape {

        final String item, block, defaultItem, prompt;
        final int damage, metadata, count, facing, x, y, z;
        final boolean placed;
        final String[] targets;
        final int[] targetDamage;
        final long weight;

        Shape(HologramPresets.Cell cell, boolean placed, long weight) {
            item = cell.item;
            block = cell.block;
            defaultItem = cell.defaultItem;
            prompt = cell.prompt;
            damage = cell.damage;
            metadata = cell.metadata;
            count = cell.count;
            facing = cell.facing;
            x = cell.x;
            y = cell.y;
            z = cell.z;
            this.placed = placed;
            this.weight = weight;
            targets = cell.allowedTargets == null ? null : new String[cell.allowedTargets.size()];
            targetDamage = targets == null ? null : new int[targets.length];
            if (targets != null) for (int i = 0; i < targets.length; i++) {
                HologramPresets.Target target = cell.allowedTargets.get(i);
                targets[i] = target.item;
                targetDamage[i] = target.damage;
            }
        }

        boolean matches(HologramPresets.Cell cell, boolean placed) {
            if (this.placed != placed || !Objects.equals(item, cell.item)
                || !Objects.equals(block, cell.block)
                || !Objects.equals(defaultItem, cell.defaultItem)
                || !Objects.equals(prompt, cell.prompt)
                || damage != cell.damage
                || metadata != cell.metadata
                || count != cell.count
                || facing != cell.facing
                || x != cell.x
                || y != cell.y
                || z != cell.z) return false;
            if (targets == null || cell.allowedTargets == null) return targets == null && cell.allowedTargets == null;
            if (targets.length != cell.allowedTargets.size()) return false;
            for (int i = 0; i < targets.length; i++) {
                HologramPresets.Target target = cell.allowedTargets.get(i);
                if (target == null || !Objects.equals(targets[i], target.item) || targetDamage[i] != target.damage)
                    return false;
            }
            return true;
        }

        static long weight(HologramPresets.Cell cell) {
            return 256L + (cell.allowedTargets == null ? 0 : 40L + 8L * cell.allowedTargets.size());
        }

        boolean texts(Builder builder, int direction) {
            if (!builder.text(item, direction) || !builder.text(block, direction)
                || !builder.text(defaultItem, direction)
                || !builder.text(prompt, direction)) return false;
            if (targets != null) for (String target : targets) if (!builder.text(target, direction)) return false;
            return true;
        }

        static boolean texts(HologramPresets.Cell cell, Builder builder) {
            if (!builder.text(cell.item, 1) || !builder.text(cell.block, 1)
                || !builder.text(cell.defaultItem, 1)
                || !builder.text(cell.prompt, 1)) return false;
            if (cell.allowedTargets != null) for (HologramPresets.Target target : cell.allowedTargets)
                if (!builder.text(target.item, 1)) return false;
            return true;
        }
    }

}
