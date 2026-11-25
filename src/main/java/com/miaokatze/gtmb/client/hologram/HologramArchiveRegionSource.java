// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.miaokatze.gtmb.hologram.HologramArchiveGeometryReader;
import com.miaokatze.gtmb.hologram.HologramArchiveGeometryReader.Page;

/** Worker-only geometry for one local preview region. No paths, renderer, editor state, or network authority. */
final class HologramArchiveRegionSource implements AutoCloseable {

    private static final int EDGE = HologramArchiveGeometryReader.EDGE, PLANE = EDGE * EDGE;
    private static final int[][] FACE_DELTAS = { { -1, 0, 0 }, { 1, 0, 0 }, { 0, -1, 0 }, { 0, 1, 0 },
        { 0, 0, -1 }, { 0, 0, 1 } };
    static final int MAX_PAGE_READS = 1 + FACE_DELTAS.length;
    static final int MAX_ROW_VISITS = MAX_PAGE_READS * HologramArchiveGeometryReader.MAX_PAGE_ROWS;
    private static final int CACHE_PAGES = MAX_PAGE_READS * 2;

    static final class Region {

        final int x, y, z;

        Region(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private Region near(int[] delta) {
            return new Region(x + delta[0], y + delta[1], z + delta[2]);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * x + y) + z;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Region)) return false;
            Region r = (Region) other;
            return x == r.x && y == r.y && z == r.z;
        }
    }

    /** Immutable spatial request; the canvas tracks its continuous camera pose separately. */
    static final class View {

        final Region region;
        final long textures;

        private View(Region region, long textures) {
            this.region = region;
            this.textures = textures;
        }
    }

    /**
     * One exact source/owner lifetime, usable by the main thread and one worker. Allocate a new owner on catalog/head,
     * pane, world or connection replacement. A texture reload repins the view without evicting verified raw rows.
     */
    static final class Views {

        private volatile View latest;
        private long latestTextures = -1;
        private boolean claimed, retired;

        synchronized View request(Region region, long textures) {
            Objects.requireNonNull(region);
            if (retired || textures < 0 || textures < latestTextures) return null;
            View previous = latest;
            if (previous != null && textures == latestTextures && region.equals(previous.region)) return previous;
            latestTextures = textures;
            return latest = new View(region, textures);
        }

        synchronized void retire() {
            retired = true;
            latest = null;
        }

        boolean current(View view) {
            return view != null && latest == view;
        }

        /** The UI must recheck when consuming a worker result, not only when that worker returns. */
        boolean accepts(Window window) {
            return window != null && current(window.view);
        }

        private synchronized boolean claim() {
            if (claimed || retired) return false;
            claimed = true;
            return true;
        }

        private synchronized boolean live() {
            return !retired;
        }
    }

    /** One caller-owned pump. Charge page and row work before I/O, including checks that subsequently fail. */
    static final class Budget {

        private final int maxPages, maxRows;
        private int pages, rows;

        Budget(int maxPages, int maxRows) {
            if (maxPages < 0 || maxPages > MAX_PAGE_READS || maxRows < 0 || maxRows > MAX_ROW_VISITS)
                throw new IllegalArgumentException("Archive region budget");
            this.maxPages = maxPages;
            this.maxRows = maxRows;
        }

        int pageReads() {
            return pages;
        }

        int rowVisits() {
            return rows;
        }

        private boolean reserve(int nextRows) {
            if (pages == maxPages || nextRows > maxRows - rows) return false;
            pages++;
            rows += nextRows;
            return true;
        }
    }

    enum State {
        READY,
        WAITING_BUDGET,
        STALE
    }

    static final class Result {

        final State state;
        final Window window;

        private Result(State state, Window window) {
            this.state = state;
            this.window = window;
        }
    }

    static final class Window {

        final View view;
        private final Page center;
        private final byte[] faces;

        private Window(View view, Page center, byte[] faces) {
            this.view = view;
            this.center = center;
            this.faces = faces;
        }

        int centerRows() {
            return center == null ? 0 : center.rows;
        }

        /** Center + six face planes. No edges/corners, and no neighbor claim for an absent center. */
        int code(int x, int y, int z) {
            int outside = (inside(x) ? 0 : 1) + (inside(y) ? 0 : 1) + (inside(z) ? 0 : 1);
            if (outside == 0) return center == null ? 0 : center.code(x, y, z);
            if (outside != 1 || x < -1 || x > EDGE || y < -1 || y > EDGE || z < -1 || z > EDGE)
                throw new IndexOutOfBoundsException("Not a region or face-neighbor cell");
            if (faces == null) throw new IllegalStateException("No halo for an absent region");
            if (x == -1 || x == EDGE) return faces[(x == -1 ? 0 : 1) * PLANE + y * EDGE + z];
            if (y == -1 || y == EDGE) return faces[(y == -1 ? 2 : 3) * PLANE + x * EDGE + z];
            return faces[(z == -1 ? 4 : 5) * PLANE + x * EDGE + y];
        }

        private static boolean inside(int coordinate) {
            return coordinate >= 0 && coordinate < EDGE;
        }
    }

    private static final Result STALE = new Result(State.STALE, null);
    private static final Result WAITING = new Result(State.WAITING_BUDGET, null);

    private final Thread worker = Thread.currentThread();
    private final HologramArchiveGeometryReader reader;
    private final Views views;
    private final LinkedHashMap<Region, Page> cache = new LinkedHashMap<>(CACHE_PAGES * 2, 0.75f, true);
    private Window last;
    private boolean closed;

    /** Transfers one already authorized held read handle to this worker, even on failure. Never opens a pathname. */
    static HologramArchiveRegionSource take(Views views, SeekableByteChannel held, byte[] trustedManifest)
        throws IOException {
        Objects.requireNonNull(held);
        if (views == null || !views.claim()) {
            held.close();
            throw new IOException("Archive view is missing, already bound or retired");
        }
        try {
            HologramArchiveGeometryReader reader = HologramArchiveGeometryReader.take(held, trustedManifest);
            if (!views.live()) {
                reader.close();
                throw new IOException("Archive view retired during open");
            }
            return new HologramArchiveRegionSource(views, reader);
        } catch (IOException | RuntimeException | Error failed) {
            views.retire();
            throw failed;
        }
    }

    private HologramArchiveRegionSource(Views views, HologramArchiveGeometryReader reader) {
        this.views = views;
        this.reader = reader;
    }

    Result read(View view, Budget budget) throws IOException {
        requireWorker();
        Objects.requireNonNull(budget);
        if (closed || !views.current(view)) return STALE;
        if (last != null && last.view == view) return new Result(State.READY, last);
        try {
            Region at = view.region;
            int rows = reader.rows(at.x, at.y, at.z);
            if (rows == 0) return publish(view, null, null);
            Page center = page(view, at, rows, budget);
            if (center == null) return views.current(view) ? WAITING : STALE;
            Page[] adjacent = new Page[FACE_DELTAS.length];
            for (int side = 0; side < adjacent.length; side++) {
                if (!views.current(view)) return STALE;
                Region neighbor = at.near(FACE_DELTAS[side]);
                int count = reader.rows(neighbor.x, neighbor.y, neighbor.z);
                if (count > 0) {
                    adjacent[side] = page(view, neighbor, count, budget);
                    if (adjacent[side] == null) return views.current(view) ? WAITING : STALE;
                }
            }
            if (!views.current(view)) return STALE;
            byte[] halo = new byte[FACE_DELTAS.length * PLANE];
            for (int side = 0; side < adjacent.length; side++) {
                Page neighbor = adjacent[side];
                if (neighbor == null) continue; // A complete, authenticated directory proved the section absent.
                int edge = (side & 1) == 0 ? EDGE - 1 : 0;
                for (int a = 0; a < EDGE; a++) for (int b = 0; b < EDGE; b++) {
                    int code = side < 2 ? neighbor.code(edge, a, b)
                        : side < 4 ? neighbor.code(a, edge, b) : neighbor.code(a, b, edge);
                    halo[side * PLANE + a * EDGE + b] = (byte) code;
                }
            }
            return publish(view, center, halo);
        } catch (IOException | RuntimeException failed) {
            try {
                close();
            } catch (IOException | RuntimeException closeFailed) {
                failed.addSuppressed(closeFailed);
            }
            throw failed;
        }
    }

    private Page page(View view, Region key, int count, Budget budget) throws IOException {
        Page saved = cache.get(key);
        if (saved != null) return saved;
        if (!views.current(view) || !budget.reserve(count)) return null;
        Page decoded = reader.readPage(key.x, key.y, key.z);
        if (decoded == null || decoded.rows != count) throw new IOException("Archive directory changed");
        cache.put(key, decoded);
        if (cache.size() > CACHE_PAGES) {
            java.util.Iterator<Map.Entry<Region, Page>> oldest = cache.entrySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return decoded;
    }

    private Result publish(View view, Page center, byte[] faces) {
        if (!views.current(view)) return STALE;
        last = new Window(view, center, faces);
        return new Result(State.READY, last);
    }

    private void requireWorker() {
        if (Thread.currentThread() != worker) throw new IllegalStateException("Not the archive worker");
    }

    @Override
    public void close() throws IOException {
        requireWorker();
        if (closed) return;
        closed = true;
        views.retire();
        last = null;
        cache.clear();
        reader.close();
    }
}
