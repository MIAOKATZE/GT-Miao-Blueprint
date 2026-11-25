// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Window;

/** Camera-independent ordinary voxel quads in tile-local block coordinates. No game or GL state. */
final class HologramArchiveTileMesh {

    static final int EDGE = 16, SLOTS = EDGE * EDGE * EDGE;
    static final int ALL_LAYERS = Integer.MAX_VALUE;
    static final int SIDES = 6;
    private static final int[] DX = { 0, 0, 0, 0, -1, 1 };
    private static final int[] DY = { -1, 1, 0, 0, 0, 0 };
    private static final int[] DZ = { 0, 0, -1, 1, 0, 0 };
    private static final int VALID_OPAQUE_BITS = 0b1110; // Archive codes 1..3, never 0 (air).
    private static final int[] EMPTY = new int[0];

    final int cellChecks, neighborChecks;
    private final int[] body;
    private final int[][] caps;

    private HologramArchiveTileMesh(int[] body, int[][] caps, int cellChecks, int neighborChecks) {
        this.body = body;
        this.caps = caps;
        this.cellChecks = cellChecks;
        this.neighborChecks = neighborChecks;
    }

    static boolean validOpaqueBits(int fullOpaqueBits) {
        return (fullOpaqueBits & ~VALID_OPAQUE_BITS) == 0;
    }

    /**
     * Bits are positive, generation-local proof of complete opaque full-cube coverage, not occupancy or palette IDs.
     * An unresolved or non-proved neighbor never hides a face. Full opaque halo suppresses only conditionally.
     */
    static HologramArchiveTileMesh build(Window window, int layer, int fullOpaqueBits, BooleanSupplier current) {
        if (!validOpaqueBits(fullOpaqueBits)) throw new IllegalArgumentException("Archive opaque proof");
        if (window == null || !current.getAsBoolean()) return null;
        long baseY = (long) EDGE * window.view.region.y;
        long inTile = (long) layer - baseY;
        int[][] caps = new int[SIDES][];
        if (window.centerRows() == 0 || layer != ALL_LAYERS && (inTile < 0 || inTile >= EDGE))
            return new HologramArchiveTileMesh(EMPTY, caps, 0, 0);
        int firstY = layer == ALL_LAYERS ? 0 : (int) inTile;
        int lastY = layer == ALL_LAYERS ? EDGE - 1 : firstY;
        int bodyCount = 0, cellChecks = 0, neighborChecks = 0;
        int[] body = new int[Math.min(window.centerRows(), EDGE * EDGE * (lastY - firstY + 1)) * SIDES];
        int[] capCounts = new int[SIDES];
        for (int x = 0; x < EDGE; x++) {
            if (!current.getAsBoolean()) return null;
            for (int y = firstY; y <= lastY; y++) {
                for (int z = 0; z < EDGE; z++) {
                    cellChecks++;
                    int code = window.code(x, y, z);
                    if (code == 0) continue;
                    int slot = x << 8 | y << 4 | z;
                    for (int side = 0; side < SIDES; side++) {
                        int quad = slot | side << 12 | code << 15;
                        int nx = x + DX[side], ny = y + DY[side], nz = z + DZ[side];
                        // The selected Y is the scene. A hidden layer must not occlude its top/bottom.
                        if (layer != ALL_LAYERS && ny != y) {
                            body[bodyCount++] = quad;
                            continue;
                        }
                        neighborChecks++;
                        int neighbor = window.code(nx, ny, nz);
                        if (neighbor == 0 || (fullOpaqueBits & 1 << neighbor) == 0) {
                            body[bodyCount++] = quad;
                        } else if ((nx | ny | nz) < 0 || nx == EDGE || ny == EDGE || nz == EDGE) {
                            if (caps[side] == null) caps[side] = new int[EDGE * EDGE];
                            caps[side][capCounts[side]++] = quad;
                        }
                    }
                }
            }
        }
        if (!current.getAsBoolean()) return null;
        for (int side = 0; side < SIDES; side++)
            if (caps[side] != null) caps[side] = Arrays.copyOf(caps[side], capCounts[side]);
        return new HologramArchiveTileMesh(Arrays.copyOf(body, bodyCount), caps, cellChecks, neighborChecks);
    }

    int bodyCount() {
        return body.length;
    }

    int bodyQuad(int index) {
        return body[index];
    }

    int capCount(int side) {
        return caps[side] == null ? 0 : caps[side].length;
    }

    int capQuad(int side, int index) {
        return caps[side][index];
    }

    static int x(int quad) { return quad >>> 8 & 15; }

    static int y(int quad) { return quad >>> 4 & 15; }

    static int z(int quad) { return quad & 15; }

    static int side(int quad) { return quad >>> 12 & 7; }

    static int code(int quad) { return quad >>> 15 & 3; }

    static boolean neighbor(int side, HologramArchiveRegionSource.Region tile,
        HologramArchiveRegionSource.Region other) {
        return (long) tile.x + DX[side] == other.x && (long) tile.y + DY[side] == other.y
            && (long) tile.z + DZ[side] == other.z;
    }
}
