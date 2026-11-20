// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable 16-cube spatial index. A patch replaces only the regions containing changed cells. */
final class HologramRegionCache {

    private static final int REGION_CELLS = 4096;
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
        private final boolean compact;
        java.util.Iterator<Map.Entry<Key, MutableRegion>> sealing;

        Builder(boolean compact) {
            this.compact = compact;
        }

        void add(HologramState.Cell cell) {
            MutableRegion region = pending.computeIfAbsent(key(cell), unused -> new MutableRegion(compact));
            region.cells.add(cell);
            if (compact) return;
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
            Region sealed = new Region(region.cells, region.positions, compact);
            result.put(entry.getKey(), sealed);
            if (compact) for (int axis = 0; axis < 3; axis++) {
                bounds[axis] = Math.min(bounds[axis], sealed.bounds[axis]);
                bounds[axis + 3] = Math.max(bounds[axis + 3], sealed.bounds[axis + 3]);
            }
            return true;
        }

        HologramRegionCache finish() {
            return new HologramRegionCache(result, bounds);
        }
    }

    private static final class MutableRegion {

        final List<HologramState.Cell> cells = new ArrayList<>();
        final Map<Integer, HologramState.Cell> positions;

        MutableRegion(boolean compact) {
            positions = compact ? null : new java.util.HashMap<>();
        }
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
        Map<Key, List<HologramState.Cell>> result = new LinkedHashMap<>(previous.regions);
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
            if (region.isEmpty()) result.remove(key);
            else result.put(key, new Region(region, topology));
        });
        regions = Collections.unmodifiableMap(result);
        if (!topology) {
            bounds = previous.bounds;
            return;
        }
        bounds = new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (List<HologramState.Cell> region : result.values()) {
            int[] extent = ((Region) region).bounds;
            for (int axis = 0; axis < 3; axis++) {
                bounds[axis] = Math.min(bounds[axis], extent[axis]);
                bounds[axis + 3] = Math.max(bounds[axis + 3], extent[axis + 3]);
            }
        }
    }

    int[] bounds() {
        return bounds.clone();
    }

    /** Attach enriched rows to the same spatial index. Each replacement keeps its original ordinal and position. */
    HologramRegionCache withCells(List<HologramState.Cell> cells) {
        Map<Key, List<HologramState.Cell>> rebound = new LinkedHashMap<>();
        regions.forEach((key, region) -> rebound.put(key, new Region((Region) region, cells)));
        return new HologramRegionCache(rebound, bounds);
    }

    HologramState.Cell at(int x, int y, int z) {
        List<HologramState.Cell> region = regions.get(new Key(x >> 4, y >> 4, z >> 4));
        return region == null ? null : ((Region) region).at(local(x, y, z));
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
        final int[] compactPositions;
        final int[] bounds;
        final List<HologramState.Cell> rebound;

        Region(List<HologramState.Cell> cells) {
            this(cells, false);
        }

        Region(List<HologramState.Cell> cells, boolean topology) {
            this.cells = Collections.unmodifiableList(cells);
            rebound = null;
            positions = new java.util.HashMap<>();
            compactPositions = null;
            for (HologramState.Cell cell : cells)
                if (positions.put(local(cell.x, cell.y, cell.z), cell) != null && topology)
                    throw new IllegalArgumentException("Duplicate topology coordinates");
            bounds = regionBounds(cells);
        }

        Region(List<HologramState.Cell> cells, Map<Integer, HologramState.Cell> positions, boolean compact) {
            this.cells = Collections.unmodifiableList(cells);
            rebound = null;
            this.positions = compact ? null : Collections.unmodifiableMap(positions);
            compactPositions = compact ? compactPositions(cells) : null;
            bounds = regionBounds(cells);
        }

        Region(Region source, List<HologramState.Cell> replacement) {
            cells = source.cells;
            positions = source.positions;
            compactPositions = source.compactPositions;
            bounds = source.bounds;
            rebound = replacement;
        }

        HologramState.Cell at(int local) {
            if (compactPositions == null) {
                HologramState.Cell cell = positions.get(local);
                return cell == null || rebound == null ? cell : rebound.get(cell.index);
            }
            int row = compactPositions.length == REGION_CELLS ? compactPositions[local]
                : compactPositions[compactSlot(compactPositions, local)] & (REGION_CELLS - 1);
            return row == 0 ? null : get(row - 1);
        }

        @Override
        public HologramState.Cell get(int index) {
            HologramState.Cell cell = cells.get(index);
            return rebound == null ? cell : rebound.get(cell.index);
        }

        @Override
        public int size() {
            return cells.size();
        }
    }

    /** Dense regions index rows directly. Sparse regions use a half-full table of packed local keys and row numbers. */
    private static int[] compactPositions(List<HologramState.Cell> cells) {
        int size = cells.size();
        boolean dense = size > REGION_CELLS / 4;
        int[] positions = new int[dense ? REGION_CELLS : Integer.highestOneBit(size * 2 - 1) << 1];
        for (int i = 0; i < size; i++) {
            HologramState.Cell cell = cells.get(i);
            int local = local(cell.x, cell.y, cell.z);
            int row = i + 1;
            if (dense) positions[local] = row;
            else positions[compactSlot(positions, local)] = local * REGION_CELLS + row;
        }
        return positions;
    }

    private static int compactSlot(int[] positions, int local) {
        int shift = Integer.numberOfLeadingZeros(positions.length) + 1;
        int slot = (local * 0x9e3779b9) >>> shift;
        while (positions[slot] != 0 && positions[slot] / REGION_CELLS != local)
            slot = (slot + 1) & (positions.length - 1);
        return slot;
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
