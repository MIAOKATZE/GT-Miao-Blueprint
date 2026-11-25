// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Region;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.View;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Views;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Window;

/**
 * Main-thread bounded render plan for one exclusive trusted archive owner. Only Ticket crosses to the single worker.
 */
final class HologramArchiveCameraTiles implements AutoCloseable {

    static final int MAX_CANDIDATES = 24, MAX_RESIDENT = 8;

    private final Views owner;
    private final Thread main = Thread.currentThread();
    private final Map<Region, Tile> resident = new LinkedHashMap<>(MAX_RESIDENT, .75f, true);
    private View lastOwnerView;
    private volatile Ticket pending;
    private Frame lastFrame;
    private long textures = -1;
    private int layer, opaqueBits;
    private boolean closed;

    HologramArchiveCameraTiles(Views owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    /**
     * Candidate discovery/coverage is upstream: this bounded set may be only a visible prefix. This never enumerates
     * the archive or claims global coverage. Same resident regions reuse geometry regardless of current camera pose.
     */
    Frame plan(HologramEditorCameraFrame pose, List<Region> candidates, long textureGeneration, int visibleLayer,
        int fullOpaqueProofBits) {
        requireMain();
        if (candidates.size() > MAX_CANDIDATES || !HologramArchiveTileMesh.validOpaqueBits(fullOpaqueProofBits))
            throw new IllegalArgumentException("Archive camera tile bounds/proof");
        if (closed || textureGeneration < 0 || textureGeneration < textures) return null;
        if (!ownerCurrent()) {
            close();
            return null;
        }
        if (textureGeneration != textures || visibleLayer != layer || fullOpaqueProofBits != opaqueBits) {
            pending = null;
            resident.clear();
            textures = textureGeneration;
            layer = visibleLayer;
            opaqueBits = fullOpaqueProofBits;
        }
        List<Projected> ranked = ranked(pose, candidates, visibleLayer);
        int selected = Math.min(ranked.size(), MAX_RESIDENT);
        Ticket job = pending;
        if (job != null && !selected(ranked, selected, job.view.region)) pending = null;
        Tile[] ready = new Tile[selected];
        int readyCount = 0;
        Ticket enqueue = null;
        for (int i = 0; i < selected; i++) {
            Region region = ranked.get(i).region;
            Tile tile = resident.get(region);
            if (tile != null) ready[readyCount++] = tile;
            else if (pending == null) {
                View request = owner.request(region, textures);
                if (request == null) {
                    close();
                    return null;
                }
                lastOwnerView = request;
                pending = enqueue = new Ticket(this, request, visibleLayer, fullOpaqueProofBits);
            }
        }
        return lastFrame = new Frame(
            this,
            pose,
            textures,
            ready,
            readyCount,
            selected,
            ranked.size() > selected,
            enqueue);
    }

    private static List<Projected> ranked(HologramEditorCameraFrame pose, List<Region> candidates, int layer) {
        double minX = pose.coordinates.screenX(-1), maxX = pose.coordinates.screenX(1);
        double minY = pose.coordinates.screenY(-1), maxY = pose.coordinates.screenY(1);
        double left = Math.min(minX, maxX), right = Math.max(minX, maxX);
        double top = Math.min(minY, maxY), bottom = Math.max(minY, maxY);
        double cx = pose.coordinates.screenX(0), cy = pose.coordinates.screenY(0);
        double spreadX = 8 * pose.scale * (Math.abs(pose.basis.rightX()) + Math.abs(pose.basis.rightZ()));
        boolean all = layer == HologramArchiveTileMesh.ALL_LAYERS;
        double spreadY = pose.scale * (8 * (Math.abs(pose.basis.upX()) + Math.abs(pose.basis.upZ()))
            + (all ? 8 : .5) * Math.abs(pose.basis.upY()));
        List<Projected> ranked = new ArrayList<>(candidates.size());
        double[] point = new double[3];
        for (Region tile : candidates) {
            double baseY = 16.0 * tile.y;
            if (!all && (layer < baseY || layer >= baseY + 16)) continue;
            pose.project(16.0 * tile.x + 7.5, all ? baseY + 7.5 : layer, 16.0 * tile.z + 7.5, point);
            if (point[0] + spreadX < left || point[0] - spreadX > right
                || point[1] + spreadY < top
                || point[1] - spreadY > bottom
                || selected(ranked, ranked.size(), tile)) continue;
            double dx = point[0] - cx, dy = point[1] - cy;
            ranked.add(new Projected(tile, dx * dx + dy * dy, point[2]));
        }
        ranked.sort(
            Comparator.comparingDouble((Projected p) -> p.distance)
                .thenComparingDouble(p -> p.depth)
                .thenComparingInt(p -> p.region.y)
                .thenComparingInt(p -> p.region.z)
                .thenComparingInt(p -> p.region.x));
        return ranked;
    }

    private static boolean selected(List<Projected> list, int count, Region region) {
        for (int i = 0; i < count; i++) if (list.get(i).region.equals(region)) return true;
        return false;
    }

    /** Accept only the exact outstanding ticket and its still-current verified window. */
    boolean install(Built result) {
        requireMain();
        if (result == null || !current(result.ticket)) return false;
        Ticket ticket = result.ticket;
        pending = null;
        if (resident.size() == MAX_RESIDENT) resident.remove(
            resident.keySet()
                .iterator()
                .next());
        resident.put(ticket.view.region, new Tile(ticket.view.region, result.mesh));
        return true;
    }

    void abandon(Ticket failed) {
        requireMain();
        if (pending == failed) pending = null;
    }

    private boolean current(Ticket job) {
        return job != null && pending == job && owner.current(job.view);
    }

    private boolean ownerCurrent() {
        return lastOwnerView == null || owner.current(lastOwnerView);
    }

    private boolean current(Frame frame) {
        requireMain();
        return !closed && lastFrame == frame && ownerCurrent();
    }

    @Override
    public void close() {
        requireMain();
        closed = true;
        pending = null;
        lastFrame = null;
        resident.clear();
        owner.retire();
    }

    private void requireMain() {
        if (Thread.currentThread() != main)
            throw new IllegalStateException("Archive camera belongs to its main thread");
    }

    private static final class Projected {

        final Region region;
        final double distance, depth;

        Projected(Region region, double distance, double depth) {
            this.region = region;
            this.distance = distance;
            this.depth = depth;
        }
    }

    static final class Ticket {

        private final HologramArchiveCameraTiles planner;
        final View view;
        private final int layer, opaqueBits;

        private Ticket(HologramArchiveCameraTiles planner, View view, int layer, int opaqueBits) {
            this.planner = planner;
            this.view = view;
            this.layer = layer;
            this.opaqueBits = opaqueBits;
        }

        Built build(Window window) {
            if (window == null || window.view != view || !planner.current(this)) return null;
            HologramArchiveTileMesh mesh = HologramArchiveTileMesh
                .build(window, layer, opaqueBits, () -> planner.current(this));
            return mesh == null ? null : new Built(this, mesh);
        }
    }

    static final class Built {

        private final Ticket ticket;
        private final HologramArchiveTileMesh mesh;

        private Built(Ticket ticket, HologramArchiveTileMesh mesh) {
            this.ticket = ticket;
            this.mesh = mesh;
        }
    }

    static final class Tile {

        final Region region;
        final HologramArchiveTileMesh mesh;

        private Tile(Region region, HologramArchiveTileMesh mesh) {
            this.region = region;
            this.mesh = mesh;
        }
    }

    enum FrameScope {
        CURRENT,
        OLD_FRAME,
        RETIRED,
        FOREIGN
    }

    static final class Frame {

        private final HologramArchiveCameraTiles planner;
        final HologramEditorCameraFrame pose;
        private final long textures;
        private final Tile[] tiles;
        final int ready, requested;
        final boolean capacityLimited;
        final Ticket enqueue;

        private Frame(HologramArchiveCameraTiles planner, HologramEditorCameraFrame pose, long textures, Tile[] tiles,
            int ready, int requested, boolean capacityLimited, Ticket enqueue) {
            this.planner = planner;
            this.pose = pose;
            this.textures = textures;
            this.tiles = tiles;
            this.ready = ready;
            this.requested = requested;
            this.capacityLimited = capacityLimited;
            this.enqueue = enqueue;
        }

        Tile tile(int index) {
            if (index < 0 || index >= ready) throw new IndexOutOfBoundsException("Archive draw tile");
            return tiles[index];
        }

        FrameScope scope(HologramArchiveCameraTiles expected, long liveTextures) {
            if (planner != expected) return FrameScope.FOREIGN;
            planner.requireMain();
            if (planner.closed || !planner.ownerCurrent()) return FrameScope.RETIRED;
            return textures == liveTextures && planner.lastFrame == this ? FrameScope.CURRENT : FrameScope.OLD_FRAME;
        }

        Draws beginDraws() {
            return planner.current(this) ? new Draws(this) : null;
        }
    }

    /** One frame only. Future GL owner records success after a complete resident tile is actually accepted to draw. */
    static final class Draws {

        private final Frame frame;
        private int submitted;

        private Draws(Frame frame) {
            this.frame = frame;
        }

        boolean record(Tile tile) {
            if (!frame.planner.current(frame)) return false;
            for (int i = 0; i < frame.ready; i++) {
                if (frame.tiles[i] == tile) {
                    submitted |= 1 << i;
                    return true;
                }
            }
            return false;
        }

        /** After the first pass, draw caps on submitted tiles where the matching neighbor did not submit this frame. */
        int neededCaps(Tile tile) {
            if (!frame.planner.current(frame)) return 0;
            boolean drawn = false;
            for (int i = 0; i < frame.ready; i++) {
                if ((submitted & 1 << i) != 0 && frame.tiles[i] == tile) drawn = true;
            }
            if (!drawn) return 0;
            int mask = 0;
            for (int side = 0; side < HologramArchiveTileMesh.SIDES; side++) {
                if (tile.mesh.capCount(side) == 0) continue;
                boolean matching = false;
                for (int i = 0; i < frame.ready; i++) {
                    if ((submitted & 1 << i) != 0
                        && HologramArchiveTileMesh.neighbor(side, tile.region, frame.tiles[i].region)) {
                        matching = true;
                        break;
                    }
                }
                if (!matching) mask |= 1 << side;
            }
            return mask;
        }
    }
}
