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

    HologramRegionCache(HologramRegionCache previous, List<HologramState.Cell> cells, int[] changed) {
        bounds = previous.bounds;
        Map<Key, List<HologramState.Cell>> result = new LinkedHashMap<>(previous.regions);
        Map<Key, List<HologramState.Cell>> replacements = new LinkedHashMap<>();
        for (int index : changed) {
            HologramState.Cell cell = cells.get(index);
            Key key = key(cell);
            List<HologramState.Cell> region = replacements
                .computeIfAbsent(key, unused -> new ArrayList<>(previous.regions.get(key)));
            for (int i = 0; i < region.size(); i++) if (region.get(i).index == index) {
                region.set(i, cell);
                break;
            }
        }
        replacements.forEach((key, region) -> result.put(key, new Region(region)));
        regions = Collections.unmodifiableMap(result);
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

        Region(List<HologramState.Cell> cells) {
            this.cells = Collections.unmodifiableList(cells);
            positions = new java.util.HashMap<>();
            for (HologramState.Cell cell : cells) positions.put(local(cell.x, cell.y, cell.z), cell);
        }

        Region(List<HologramState.Cell> cells, Map<Integer, HologramState.Cell> positions) {
            this.cells = Collections.unmodifiableList(cells);
            this.positions = Collections.unmodifiableMap(positions);
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

    static Key key(HologramState.Cell cell) {
        return new Key(cell.x >> 4, cell.y >> 4, cell.z >> 4);
    }

    static java.util.Set<Key> affected(HologramState state) {
        java.util.Set<Key> result = new java.util.HashSet<>();
        for (int index : state.changedIndices) {
            HologramState.Cell c = state.cells.get(index);
            // Native connected textures may inspect diagonal neighbors. A one-cell halo
            // intersects one or two regions per axis, hence at most eight regions per cell.
            int minX = (int) (((long) c.x - 1) >> 4), maxX = (int) (((long) c.x + 1) >> 4);
            int minY = (int) (((long) c.y - 1) >> 4), maxY = (int) (((long) c.y + 1) >> 4);
            int minZ = (int) (((long) c.z - 1) >> 4), maxZ = (int) (((long) c.z + 1) >> 4);
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) result.add(new Key(x, y, z));
        }
        return result;
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
