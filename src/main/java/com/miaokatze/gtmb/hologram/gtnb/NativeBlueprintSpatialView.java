// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram.gtnb;

import static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintBytes.need;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import com.google.gson.JsonObject;

/**
 * Rank/select over the canonical page directory. The only cached geometry is bounded run data,
 * never one record per occupied position. Region spans are indexes into this definition.
 */
public final class NativeBlueprintSpatialView {

    public interface SpanConsumer {

        void span(int y, int firstOrdinal, long start, int count, long descriptor) throws IOException;
    }

    public static final class Point {

        public final int x, y, z, ordinal;
        public final long descriptor;

        Point(int x, int y, int z, int ordinal, long descriptor) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.ordinal = ordinal;
            this.descriptor = descriptor;
        }
    }

    public static long descriptor(int element, int extra) {
        return (long) element << 32 | (extra & 0xffffffffL);
    }

    public static int element(long desc) {
        return (int) (desc >>> 32);
    }

    public static int extra(long desc) {
        return (int) desc;
    }

    final NativeBlueprintDefinition definition;
    private final NativeBlueprintDefinition.Frame frame;
    private final int[] pageRanks, pageYs;
    private final Map<Long, Integer> histogram;
    private static final int MAX_CACHE_BYTES = 8 << 20;
    private final LinkedHashMap<Integer, Decoded> cache = new LinkedHashMap<>(16, .75f, true);
    private int cachedBytes;

    private static final class Decoded {

        final long[] starts;
        final int[] ranks, ids;

        Decoded(int records) {
            starts = new long[records];
            ranks = new int[records + 1];
            ids = new int[records];
        }

        int bytes() {
            return 128 + starts.length * 16;
        }
    }

    NativeBlueprintSpatialView(NativeBlueprintDefinition d, Map<Long, Integer> counts) {
        definition = d;
        frame = d.frame();
        histogram = Collections.unmodifiableMap(counts);
        pageRanks = new int[d.pages.size() + 1];
        pageYs = new int[d.pages.size()];
        for (var l : d.layers) for (int i = l.first; i < l.first + l.pages; i++) {
            pageRanks[i + 1] = Math.addExact(pageRanks[i], d.pages.get(i).cells);
            pageYs[i] = l.y;
        }
    }

    public int size() {
        return pageRanks[pageRanks.length - 1];
    }

    public Map<Long, Integer> counts() {
        return histogram;
    }

    public NativeBlueprintDefinition definition() {
        return definition;
    }

    public JsonObject fields(long desc) throws IOException {
        JsonObject base = definition.element(element(desc));
        JsonObject w = definition.extraWrapper(extra(desc));
        for (var e : w.getAsJsonObject("fields")
            .entrySet()) base.add(e.getKey(), e.getValue());
        return base;
    }

    public JsonObject wrapper(long desc) throws IOException {
        return definition.extraWrapper(extra(desc));
    }

    private synchronized Decoded page(int i) throws IOException {
        Decoded out = cache.get(i);
        if (out != null) return out;
        var page = definition.pages.get(i);
        out = new Decoded(page.records);
        try (var cursor = definition.new RunCursor(
            new NativeBlueprintDefinition.Layer(pageYs[i], i, 1, page.records, page.cells))) {
            int n = 0;
            while (cursor.next()) {
                out.starts[n] = cursor.start();
                out.ids[n] = cursor.element();
                out.ranks[n + 1] = out.ranks[n] + cursor.length();
                n++;
            }
            need(n == page.records && out.ranks[n] == page.cells, "page changed after open");
        }
        while (cachedBytes + out.bytes() > MAX_CACHE_BYTES && !cache.isEmpty()) {
            var it = cache.entrySet()
                .iterator();
            cachedBytes -= it.next()
                .getValue()
                .bytes();
            it.remove();
        }
        cache.put(i, out);
        cachedBytes += out.bytes();
        return out;
    }

    private static int floor(int[] a, int value) {
        int i = Arrays.binarySearch(a, value);
        return i >= 0 ? i : -i - 2;
    }

    public Point atOrdinal(int ordinal) throws IOException {
        if (ordinal < 0 || ordinal >= size()) throw new IndexOutOfBoundsException();
        int i = floor(pageRanks, ordinal), y = pageYs[i], n = ordinal - pageRanks[i];
        Decoded d = page(i);
        int r = floor(d.ranks, n);
        long p = d.starts[r] + n - d.ranks[r];
        int x = (int) (frame.minX + p % frame.sizeX), z = (int) (frame.minZ + p / frame.sizeX), el = d.ids[r];
        return new Point(x, y, z, ordinal, descriptor(el, definition.extraIdAt(x, y, z, el)));
    }

    /** Forward work cursor. Keeps one bounded page, does not allocate per cell, and may pause between ticks. */
    public Cursor cursor() {
        return new Cursor();
    }

    public final class Cursor {

        private final Iterator<Map.Entry<NativeBlueprintDefinition.Position, Integer>> overrides = definition.overrides
            .entrySet()
            .iterator();
        private final int maxX = (int) ((long) frame.minX + frame.sizeX - 1);
        private Map.Entry<NativeBlueprintDefinition.Position, Integer> nextOverride;
        private long overridePosition;
        private Decoded decoded;
        private int pageIndex = -1, runIndex;
        private long position, end, defaultDescriptor, activeDescriptor;
        private int rank, x, y, z;

        private Cursor() {
            loadOverride();
        }

        public boolean hasNext() {
            return rank < size();
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        public long descriptor() {
            return activeDescriptor;
        }

        private void loadOverride() {
            nextOverride = overrides.hasNext() ? overrides.next() : null;
            if (nextOverride != null) {
                var key = nextOverride.getKey();
                overridePosition = frame.position(key.x, key.z);
            }
        }

        public void advance() throws IOException {
            if (!hasNext()) throw new NoSuchElementException();
            if (position == end) {
                if (decoded == null || runIndex == decoded.ids.length) {
                    decoded = page(++pageIndex);
                    y = pageYs[pageIndex];
                    runIndex = 0;
                }
                position = decoded.starts[runIndex];
                end = position + decoded.ranks[runIndex + 1] - decoded.ranks[runIndex];
                int element = decoded.ids[runIndex++];
                defaultDescriptor = NativeBlueprintSpatialView
                    .descriptor(element, definition.defaults.getOrDefault(element, 0));
                x = (int) (frame.minX + position % frame.sizeX);
                z = (int) (frame.minZ + position / frame.sizeX);
            } else if (x == maxX) {
                x = frame.minX;
                z++;
            } else x++;
            activeDescriptor = defaultDescriptor;
            if (nextOverride != null && nextOverride.getKey().y == y && overridePosition == position) {
                activeDescriptor = NativeBlueprintSpatialView
                    .descriptor(element(defaultDescriptor), nextOverride.getValue());
                loadOverride();
            }
            position++;
            rank++;
        }
    }

    public Point atPosition(int x, int y, int z) throws IOException {
        if (!frame.contains(x, y, z)) return null;
        var l = definition.findLayer(y);
        if (l == null) return null;
        long p = frame.position(x, z);
        int lo = l.first, hi = l.first + l.pages - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            var pg = definition.pages.get(mid);
            if (p < pg.start) hi = mid - 1;
            else if (p >= pg.end) lo = mid + 1;
            else {
                Decoded d = page(mid);
                int r = Arrays.binarySearch(d.starts, p);
                if (r < 0) r = -r - 2;
                if (r < 0 || p - d.starts[r] >= d.ranks[r + 1] - d.ranks[r]) return null;
                int rank = Math.toIntExact((long) pageRanks[mid] + d.ranks[r] + (p - d.starts[r])), el = d.ids[r];
                return new Point(x, y, z, rank, descriptor(el, definition.extraIdAt(x, y, z, el)));
            }
        }
        return null;
    }

    /** One callback per same descriptor interval; coalesced defaults, sparse overlay splits. */
    public void spans(SpanConsumer visitor) throws IOException {
        var it = definition.overrides.entrySet()
            .iterator();
        var next = it.hasNext() ? it.next() : null;
        for (int i = 0; i < pageYs.length; i++) {
            int y = pageYs[i], base = pageRanks[i];
            Decoded page = page(i);
            for (int r = 0; r < page.ids.length; r++) {
                int el = page.ids[r], rank = base + page.ranks[r];
                long start = page.starts[r], end = start + page.ranks[r + 1] - page.ranks[r], position = start;
                long def = descriptor(el, definition.defaults.getOrDefault(el, 0));
                while (next != null && next.getKey().y == y) {
                    var pos = next.getKey();
                    long at = frame.position(pos.x, pos.z);
                    if (at >= end) break;
                    if (at > position)
                        visitor.span(y, Math.toIntExact(rank + position - start), position, (int) (at - position), def);
                    visitor.span(y, Math.toIntExact(rank + at - start), at, 1, descriptor(el, next.getValue()));
                    position = at + 1;
                    next = it.hasNext() ? it.next() : null;
                }
                if (position < end)
                    visitor.span(y, Math.toIntExact(rank + position - start), position, (int) (end - position), def);
            }
        }
    }
}
