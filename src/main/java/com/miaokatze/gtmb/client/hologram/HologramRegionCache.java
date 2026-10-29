package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable 16-cube spatial index. A patch replaces only the regions containing changed cells. */
final class HologramRegionCache {

    final Map<Key, List<HologramState.Cell>> regions;
    private final int[] bounds;

    private HologramRegionCache(Map<Key, List<HologramState.Cell>> regions, int[] bounds) {
        this.regions = Collections.unmodifiableMap(regions);
        this.bounds = bounds;
    }

    /** Local publications replace only touched region directory paths; subtree extents avoid full bounds scans. */
    static HologramRegionCache document(HologramRegionCache previous, List<HologramState.Cell> cells, int[] changed,
        List<HologramState.Cell> before) {
        HologramDocumentIndex<Key, List<HologramState.Cell>> index;
        if (previous == null) index = new HologramDocumentIndex<>((a, b) -> {
            int order = Integer.compare(a.x, b.x);
            if (order == 0) order = Integer.compare(a.y, b.y);
            return order == 0 ? Integer.compare(a.z, b.z) : order;
        }, region -> ((Region) region).bounds);
        else {
            @SuppressWarnings("unchecked")
            HologramDocumentIndex<Key, List<HologramState.Cell>> retained = (HologramDocumentIndex<Key, List<HologramState.Cell>>) previous.regions;
            index = retained;
        }
        Map<Key, List<HologramState.Cell>> replacements = new LinkedHashMap<>();
        Map<Key, java.util.Set<HologramState.Cell>> removed = new LinkedHashMap<>();
        for (HologramState.Cell cell : before) {
            Key key = key(cell);
            removed.computeIfAbsent(key, unused -> Collections.newSetFromMap(new java.util.IdentityHashMap<>()))
                .add(cell);
        }
        // A native batch can replace many cells in one dense region. Repeated ArrayList.remove
        // searches and shifts cost O(changed * regionSize); filter each old region once instead.
        // Keep the old survivor order, then append replacements in the original changed order.
        for (Map.Entry<Key, java.util.Set<HologramState.Cell>> entry : removed.entrySet()) {
            List<HologramState.Cell> retained = new ArrayList<>();
            for (HologramState.Cell cell : previous.regions.get(entry.getKey())) if (!entry.getValue()
                .contains(cell)) retained.add(cell);
            replacements.put(entry.getKey(), retained);
        }
        for (int slot : changed) {
            HologramState.Cell cell = cells.get(slot);
            Key key = key(cell);
            replacements
                .computeIfAbsent(
                    key,
                    unused -> previous == null ? new ArrayList<>()
                        : new ArrayList<>(previous.regions.getOrDefault(key, Collections.emptyList())))
                .add(cell);
        }
        for (Map.Entry<Key, List<HologramState.Cell>> entry : replacements.entrySet()) index = index.with(
            entry.getKey(),
            entry.getValue()
                .isEmpty() ? null : new Region(entry.getValue(), true));
        return new HologramRegionCache(index);
    }

    private HologramRegionCache(HologramDocumentIndex<Key, List<HologramState.Cell>> index) {
        regions = index;
        bounds = index.bounds();
    }

    static final class Builder {

        final Map<Key, MutableRegion> pending = new LinkedHashMap<>();
        final Map<Key, List<HologramState.Cell>> result = new LinkedHashMap<>();
        final int[] bounds = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE };
        java.util.Iterator<Map.Entry<Key, MutableRegion>> sealing;

        void add(HologramState.Cell cell) {
            MutableRegion region = pending.computeIfAbsent(key(cell), unused -> new MutableRegion());
            region.cells.add(cell);
            region.positions.put(local(cell.x, cell.y, cell.z), cell);
            bounds[0] = Math.min(bounds[0], cell.x);
            bounds[1] = Math.min(bounds[1], cell.y);
            bounds[2] = Math.min(bounds[2], cell.z);
            bounds[3] = Math.max(bounds[3], cell.x);
            bounds[4] = Math.max(bounds[4], cell.y);
            bounds[5] = Math.max(bounds[5], cell.z);
        }

        boolean sealNext() {
            if (sealing == null) sealing = pending.entrySet()
                .iterator();
            if (!sealing.hasNext()) return false;
            Map.Entry<Key, MutableRegion> entry = sealing.next();
            MutableRegion region = entry.getValue();
            result.put(entry.getKey(), new Region(region.cells, region.positions));
            return true;
        }

        HologramRegionCache finish() {
            return new HologramRegionCache(result, bounds);
        }
    }

    private static final class MutableRegion {

        final List<HologramState.Cell> cells = new ArrayList<>();
        final Map<Integer, HologramState.Cell> positions = new java.util.HashMap<>();
    }

    HologramRegionCache(List<HologramState.Cell> cells) {
        bounds = new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE };
        Map<Key, List<HologramState.Cell>> result = new LinkedHashMap<>();
        for (HologramState.Cell cell : cells) {
            result.computeIfAbsent(key(cell), unused -> new ArrayList<>())
                .add(cell);
            bounds[0] = Math.min(bounds[0], cell.x);
            bounds[1] = Math.min(bounds[1], cell.y);
            bounds[2] = Math.min(bounds[2], cell.z);
            bounds[3] = Math.max(bounds[3], cell.x);
            bounds[4] = Math.max(bounds[4], cell.y);
            bounds[5] = Math.max(bounds[5], cell.z);
        }
        result.replaceAll((key, region) -> new Region(region));
        regions = Collections.unmodifiableMap(result);
    }

    HologramRegionCache(HologramRegionCache previous, List<HologramState.Cell> cells, int[] changed,
        List<HologramState.Cell> before, boolean topology) {
        ModelMap model = previous.regions instanceof ModelMap ? (ModelMap) previous.regions : null;
        Map<Key, List<HologramState.Cell>> result = new LinkedHashMap<>(model == null ? previous.regions : model.root);
        Map<Key, List<HologramState.Cell>> models = model == null ? new LinkedHashMap<>()
            : new LinkedHashMap<>(model.overrides);
        Map<Key, List<HologramState.Cell>> replacements = new LinkedHashMap<>();
        for (HologramState.Cell old : before) replacements
            .computeIfAbsent(
                key(old),
                unused -> new ArrayList<>(previous.regions.getOrDefault(key(old), Collections.emptyList())))
            .remove(old);
        for (int index : changed) {
            HologramState.Cell cell = cells.get(index);
            replacements
                .computeIfAbsent(
                    key(cell),
                    unused -> new ArrayList<>(previous.regions.getOrDefault(key(cell), Collections.emptyList())))
                .add(cell);
        }
        replacements.forEach((key, region) -> {
            if (region.isEmpty()) {
                result.remove(key);
                models.remove(key);
            } else {
                List<HologramState.Cell> cold = new ArrayList<>(region.size());
                boolean loaded = false;
                for (HologramState.Cell cell : region) {
                    loaded |= cell.modelSource != null;
                    cold.add(cell.modelSource == null ? cell : cell.modelSource);
                }
                result.put(key, model == null ? new Region(cold, topology) : modelRegion(cold, topology, null));
                if (loaded) models.put(key, modelRegion(region, topology, null));
                else models.remove(key);
            }
        });
        regions = models.isEmpty() ? Collections.unmodifiableMap(result) : new ModelMap(result, models);
        if (!topology) {
            bounds = previous.bounds;
            return;
        }
        bounds = new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (List<HologramState.Cell> region : regions.values()) {
            int[] extent = ((Region) region).bounds;
            for (int axis = 0; axis < 3; axis++) {
                bounds[axis] = Math.min(bounds[axis], extent[axis]);
                bounds[axis + 3] = Math.max(bounds[axis + 3], extent[axis + 3]);
            }
        }
    }

    /** Model-only changes retain the immutable cold index and copy only the bounded loaded-region overlay. */
    static HologramRegionCache models(HologramRegionCache previous, List<HologramState.Cell> cells, int[] changed,
        List<HologramState.Cell> before, List<com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease> unpublished) {
        ModelMap model = previous.regions instanceof ModelMap ? (ModelMap) previous.regions : null;
        Map<Key, List<HologramState.Cell>> root = model == null ? previous.regions : model.root;
        Map<Key, List<HologramState.Cell>> overlays = model == null ? new LinkedHashMap<>()
            : new LinkedHashMap<>(model.overrides);
        Map<Key, List<HologramState.Cell>> replacements = new LinkedHashMap<>();
        for (HologramState.Cell old : before)
            replacements.computeIfAbsent(key(old), unused -> new ArrayList<>(previous.regions.get(key(old))))
                .remove(old);
        for (int index : changed) {
            HologramState.Cell cell = cells.get(index);
            replacements.computeIfAbsent(key(cell), unused -> new ArrayList<>(previous.regions.get(key(cell))))
                .add(cell);
        }
        replacements.forEach((key, region) -> {
            boolean loaded = false;
            for (HologramState.Cell cell : region) loaded |= cell.modelSource != null;
            if (loaded) overlays.put(key, modelRegion(region, false, unpublished));
            else overlays.remove(key);
        });
        return new HologramRegionCache(overlays.isEmpty() ? root : new ModelMap(root, overlays), previous.bounds);
    }

    private static Region modelRegion(List<HologramState.Cell> cells, boolean topology,
        List<com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease> unpublished) {
        com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease lease = com.miaokatze.gtmb.hologram.HologramProcessGovernor
            .tryRetain("snapshot.modelRegion", 512L + cells.size() * 128L);
        if (lease == null) throw new IllegalStateException("Model region memory quota busy");
        try {
            Region result = new Region(cells, topology);
            if (unpublished != null) unpublished.add(lease);
            else lease.close();
            return result;
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    private static final class ModelMap extends java.util.AbstractMap<Key, List<HologramState.Cell>> {

        final Map<Key, List<HologramState.Cell>> root, overrides;

        ModelMap(Map<Key, List<HologramState.Cell>> root, Map<Key, List<HologramState.Cell>> overrides) {
            this.root = Collections.unmodifiableMap(root);
            this.overrides = Collections.unmodifiableMap(overrides);
        }

        @Override
        public List<HologramState.Cell> get(Object key) {
            List<HologramState.Cell> value = overrides.get(key);
            return value == null ? root.get(key) : value;
        }

        @Override
        public int size() {
            return root.size();
        }

        @Override
        public boolean containsKey(Object key) {
            return root.containsKey(key);
        }

        @Override
        public java.util.Set<Entry<Key, List<HologramState.Cell>>> entrySet() {
            return new java.util.AbstractSet<Entry<Key, List<HologramState.Cell>>>() {

                public int size() {
                    return root.size();
                }

                public java.util.Iterator<Entry<Key, List<HologramState.Cell>>> iterator() {
                    java.util.Iterator<Entry<Key, List<HologramState.Cell>>> source = root.entrySet()
                        .iterator();
                    return new java.util.Iterator<Entry<Key, List<HologramState.Cell>>>() {

                        public boolean hasNext() {
                            return source.hasNext();
                        }

                        public Entry<Key, List<HologramState.Cell>> next() {
                            Entry<Key, List<HologramState.Cell>> next = source.next();
                            List<HologramState.Cell> replacement = overrides.get(next.getKey());
                            return replacement == null ? next : new SimpleImmutableEntry<>(next.getKey(), replacement);
                        }
                    };
                }
            };
        }
    }

    static int modelRegionCount(HologramRegionCache regions) {
        return regions.regions instanceof ModelMap ? ((ModelMap) regions.regions).overrides.size() : 0;
    }

    int[] bounds() {
        return bounds.clone();
    }

    HologramState.Cell at(int x, int y, int z) {
        List<HologramState.Cell> region = regions.get(new Key(x >> 4, y >> 4, z >> 4));
        return region == null ? null : ((Region) region).positions.get(local(x, y, z));
    }

    boolean withinMargin(int x, int y, int z) {
        return x >= bounds[0] - 1L && y >= bounds[1] - 1L
            && z >= bounds[2] - 1L
            && x <= bounds[3] + 1L
            && y <= bounds[4] + 1L
            && z <= bounds[5] + 1L;
    }

    private static int local(int x, int y, int z) {
        return ((x & 15) << 8) | ((y & 15) << 4) | (z & 15);
    }

    private static final class Region extends java.util.AbstractList<HologramState.Cell> {

        final List<HologramState.Cell> cells;
        final Map<Integer, HologramState.Cell> positions;
        final int[] bounds;

        Region(List<HologramState.Cell> cells) {
            this(cells, false);
        }

        Region(List<HologramState.Cell> cells, boolean topology) {
            this.cells = Collections.unmodifiableList(cells);
            positions = new java.util.HashMap<>();
            for (HologramState.Cell cell : cells)
                if (positions.put(local(cell.x, cell.y, cell.z), cell) != null && topology)
                    throw new IllegalArgumentException("Duplicate topology coordinates");
            bounds = regionBounds(cells);
        }

        Region(List<HologramState.Cell> cells, Map<Integer, HologramState.Cell> positions) {
            this.cells = Collections.unmodifiableList(cells);
            this.positions = Collections.unmodifiableMap(positions);
            bounds = regionBounds(cells);
        }

        @Override
        public HologramState.Cell get(int index) {
            return cells.get(index);
        }

        @Override
        public int size() {
            return cells.size();
        }
    }

    private static int[] regionBounds(List<HologramState.Cell> cells) {
        int[] bounds = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE };
        for (HologramState.Cell cell : cells) {
            bounds[0] = Math.min(bounds[0], cell.x);
            bounds[1] = Math.min(bounds[1], cell.y);
            bounds[2] = Math.min(bounds[2], cell.z);
            bounds[3] = Math.max(bounds[3], cell.x);
            bounds[4] = Math.max(bounds[4], cell.y);
            bounds[5] = Math.max(bounds[5], cell.z);
        }
        return bounds;
    }

    static Key key(HologramState.Cell cell) {
        return new Key(cell.x >> 4, cell.y >> 4, cell.z >> 4);
    }

    static java.util.Set<Key> affected(HologramState state) {
        java.util.Set<Key> result = new java.util.HashSet<>();
        for (HologramState.Cell old : state.changedPreviousCells) affect(result, old);
        for (int index : state.changedIndices) affect(result, state.cells.get(index));
        return result;
    }

    /** Opaque appearance publication leaves neighboring cold block/meta facts unchanged. */
    static java.util.Set<Key> changedRegions(HologramState state) {
        java.util.Set<Key> result = new java.util.HashSet<>();
        for (HologramState.Cell old : state.changedPreviousCells) result.add(key(old));
        for (int index : state.changedIndices) result.add(key(state.cells.get(index)));
        return result;
    }

    private static void affect(java.util.Set<Key> result, HologramState.Cell c) {
        // Native connected textures may inspect diagonal neighbors. A one-cell halo
        // intersects one or two regions per axis, hence at most eight regions per cell.
        int minX = (int) (((long) c.x - 1) >> 4), maxX = (int) (((long) c.x + 1) >> 4);
        int minY = (int) (((long) c.y - 1) >> 4), maxY = (int) (((long) c.y + 1) >> 4);
        int minZ = (int) (((long) c.z - 1) >> 4), maxZ = (int) (((long) c.z + 1) >> 4);
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) result.add(new Key(x, y, z));
    }

    static final class Key {

        final int x, y, z;

        Key(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }
    }
}
