// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.miaovisonlite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Broad-phase bounds for fixed-size regions, with a stable order for blended submissions. */
public final class MiaoVisonLiteSpatialIndex<T> {

    private final int edge;
    private final NavigableMap<Integer, NavigableMap<Integer, NavigableMap<Integer, Entry<T>>>> columns = new TreeMap<>();
    private final List<Entry<T>> ordered = new ArrayList<>();
    private final List<Entry<T>> selected = new ArrayList<>();
    private final List<T> result = new ArrayList<>();
    private int minX, maxX, minY, maxY, minZ, maxZ;

    public MiaoVisonLiteSpatialIndex(int edge) {
        this.edge = edge;
    }

    public void add(int x, int y, int z, T value) {
        if (ordered.isEmpty()) {
            minX = maxX = x;
            minY = maxY = y;
            minZ = maxZ = z;
        } else {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        Entry<T> entry = new Entry<>(ordered.size(), value);
        columns.computeIfAbsent(x, key -> new TreeMap<>())
            .computeIfAbsent(z, key -> new TreeMap<>())
            .put(y, entry);
        ordered.add(entry);
    }

    /** The returned view is reused by the next query. Callers apply exact distance and view frustum tests. */
    public List<T> select(double x, double y, double z, double range) {
        result.clear();
        int left = (int) Math.ceil(x - range - edge), right = (int) Math.floor(x + range);
        int bottom = (int) Math.ceil(y - range - edge), top = (int) Math.floor(y + range);
        int back = (int) Math.ceil(z - range - edge), front = (int) Math.floor(z + range);
        if (ordered.size() <= 32
            || left <= minX && right >= maxX && bottom <= minY && top >= maxY && back <= minZ && front >= maxZ) {
            for (Entry<T> entry : ordered) result.add(entry.value);
            return result;
        }
        selected.clear();
        for (NavigableMap<Integer, NavigableMap<Integer, Entry<T>>> column : columns.subMap(left, true, right, true)
            .values()) {
            for (NavigableMap<Integer, Entry<T>> levels : column.subMap(back, true, front, true)
                .values()) {
                selected.addAll(
                    levels.subMap(bottom, true, top, true)
                        .values());
            }
        }
        selected.sort(Comparator.comparingInt(entry -> entry.order));
        for (Entry<T> entry : selected) result.add(entry.value);
        return result;
    }

    public void clear() {
        columns.clear();
        ordered.clear();
        selected.clear();
        result.clear();
    }

    private static final class Entry<T> {

        final int order;
        final T value;

        Entry(int order, T value) {
            this.order = order;
            this.value = value;
        }
    }
}
