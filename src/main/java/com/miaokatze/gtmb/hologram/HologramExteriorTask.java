package com.miaokatze.gtmb.hologram;

import java.util.BitSet;
import java.util.List;

import net.minecraftforge.common.util.ForgeDirection;

/** Same exterior proof as HologramHatchGeometry, with one cell or flood edge per operation. */
final class HologramExteriorTask {

    private final List<int[]> cells;
    private final HologramHatchGeometry.Air air;
    private BitSet occupied;
    private HologramHatchGeometry.Exterior exterior = HologramHatchGeometry.Exterior.EMPTY;
    private int[] queue;
    private int head, tail;
    private int stage, cursor, minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
    private int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
    private int x, y, z, side;
    private int active = -1;
    private long volume, boundary, walked, discovered;

    HologramExteriorTask(List<int[]> cells, HologramHatchGeometry.Air air) {
        this.cells = cells;
        this.air = air;
    }

    boolean done() {
        return stage == 5;
    }

    HologramHatchGeometry.Exterior result() {
        if (!done()) throw new IllegalStateException("Exterior proof is incomplete");
        return exterior;
    }

    String phase() {
        return stage == 0 ? "facing.bounds"
            : stage == 1 ? "facing.occupancy"
                : stage == 2 ? "facing.boundary" : stage == 3 ? "facing.exterior" : "facing.exclude";
    }

    long completed() {
        return stage == 2 || stage == 3 ? walked : cursor;
    }

    long total() {
        return stage == 2 ? boundary : stage == 3 ? discovered * 6 : cells.size();
    }

    void advance() {
        if (stage == 0) {
            if (cursor < cells.size()) {
                int[] c = cells.get(cursor++);
                minX = Math.min(minX, c[0]);
                minY = Math.min(minY, c[1]);
                minZ = Math.min(minZ, c[2]);
                maxX = Math.max(maxX, c[0]);
                maxY = Math.max(maxY, c[1]);
                maxZ = Math.max(maxZ, c[2]);
                return;
            }
            if (cells.isEmpty()) {
                stage = 5;
                return;
            }
            minX--;
            minY--;
            minZ--;
            maxX++;
            maxY++;
            maxZ++;
            long width = (long) maxX - minX + 1, height = (long) maxY - minY + 1, depth = (long) maxZ - minZ + 1;
            volume = width <= 0 || height <= 0 || depth <= 0 || width > 131072 || height > 131072 || depth > 131072 ? 0
                : width * height * depth;
            if (volume <= 0 || volume > 131072) {
                stage = 5;
                return;
            }
            // Admit bounds before allocating occupancy or retaining any per-position state.
            exterior = new HologramHatchGeometry.Exterior(
                minX, minY, minZ, (int) width, (int) height, (int) depth);
            occupied = new BitSet((int) volume);
            queue = new int[(int) volume];
            boundary = volume - Math.max(0, width - 2) * Math.max(0, height - 2) * Math.max(0, depth - 2);
            cursor = 0;
            stage = 1;
        } else if (stage == 1) {
            if (cursor < cells.size()) {
                int[] c = cells.get(cursor++);
                if (c.length < 4 || c[3] != 0) occupied.set(exterior.index(c[0], c[1], c[2]));
                return;
            }
            x = minX;
            y = minY;
            z = minZ;
            stage = 2;
        } else if (stage == 2) {
            int index = exterior.index(x, y, z);
            if (air.test(x, y, z) && !exterior.positions.get(index)) enqueue(index);
            walked++;
            // Preserve native x/y/z boundary order while skipping all interior positions in constant work.
            if (x != minX && x != maxX && y != minY && y != maxY && z == minZ) z = maxZ;
            else if (++z > maxZ) {
                z = minZ;
                if (++y > maxY) {
                    y = minY;
                    if (++x > maxX) {
                        walked = 0;
                        stage = 3;
                    }
                }
            }
        } else if (stage == 3) {
            if (active < 0) {
                side = 0;
                if (head == tail) {
                    queue = null;
                    occupied = null;
                    stage = 4;
                    cursor = 0;
                    return;
                }
                active = queue[head++];
                x = minX + active / (exterior.height * exterior.depth);
                y = minY + active / exterior.depth % exterior.height;
                z = minZ + active % exterior.depth;
            }
            ForgeDirection d = ForgeDirection.VALID_DIRECTIONS[side++];
            int nx = x + d.offsetX, ny = y + d.offsetY, nz = z + d.offsetZ;
            int index = exterior.index(nx, ny, nz);
            if (index >= 0 && !occupied.get(index) && !exterior.positions.get(index)
                && air.test(nx, ny, nz)) enqueue(index);
            walked++;
            if (side == 6) active = -1;
        } else if (stage == 4) {
            if (cursor < cells.size()) {
                int[] c = cells.get(cursor++);
                exterior.positions.clear(exterior.index(c[0], c[1], c[2]));
            } else stage = 5;
        }
    }

    private void enqueue(int index) {
        exterior.positions.set(index);
        queue[tail++] = index;
        discovered++;
    }
}
