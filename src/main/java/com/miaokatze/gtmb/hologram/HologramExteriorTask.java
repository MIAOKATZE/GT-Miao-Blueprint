package com.miaokatze.gtmb.hologram;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraftforge.common.util.ForgeDirection;

/** Same exterior proof as HologramHatchGeometry, with one cell or flood edge per operation. */
final class HologramExteriorTask {

    private final List<int[]> cells;
    private final HologramHatchGeometry.Air air;
    private final Set<String> occupied = new HashSet<>(), exterior = new HashSet<>();
    private final ArrayDeque<int[]> queue = new ArrayDeque<>();
    private int stage, cursor, minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
    private int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
    private int x, y, z, side;
    private int[] active;
    private long volume, boundary, walked, discovered;

    HologramExteriorTask(List<int[]> cells, HologramHatchGeometry.Air air) {
        this.cells = cells;
        this.air = air;
    }

    boolean done() {
        return stage == 4;
    }

    Set<String> result() {
        if (!done()) throw new IllegalStateException("Exterior proof is incomplete");
        return exterior;
    }

    String phase() {
        return stage == 0 ? "facing.bounds"
            : stage == 1 ? "facing.boundary" : stage == 2 ? "facing.exterior" : "facing.exclude";
    }

    long completed() {
        return stage == 1 || stage == 2 ? walked : cursor;
    }

    long total() {
        return stage == 1 ? boundary : stage == 2 ? discovered * 6 : cells.size();
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
                if (c.length < 4 || c[3] != 0) occupied.add(HologramHatchGeometry.key(c[0], c[1], c[2]));
                return;
            }
            if (cells.isEmpty()) {
                stage = 4;
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
                stage = 4;
                return;
            }
            boundary = volume - Math.max(0, width - 2) * Math.max(0, height - 2) * Math.max(0, depth - 2);
            x = minX;
            y = minY;
            z = minZ;
            stage = 1;
        } else if (stage == 1) {
            if (air.test(x, y, z) && exterior.add(HologramHatchGeometry.key(x, y, z))) {
                queue.add(new int[] { x, y, z });
                discovered++;
            }
            walked++;
            // Preserve native x/y/z boundary order while skipping all interior positions in constant work.
            if (x != minX && x != maxX && y != minY && y != maxY && z == minZ) z = maxZ;
            else if (++z > maxZ) {
                z = minZ;
                if (++y > maxY) {
                    y = minY;
                    if (++x > maxX) {
                        walked = 0;
                        stage = 2;
                    }
                }
            }
        } else if (stage == 2) {
            if (active == null) {
                active = queue.poll();
                side = 0;
                if (active == null) {
                    stage = 3;
                    cursor = 0;
                    return;
                }
            }
            ForgeDirection d = ForgeDirection.VALID_DIRECTIONS[side++];
            int nx = active[0] + d.offsetX, ny = active[1] + d.offsetY, nz = active[2] + d.offsetZ;
            String key = HologramHatchGeometry.key(nx, ny, nz);
            if (nx >= minX && nx <= maxX
                && ny >= minY
                && ny <= maxY
                && nz >= minZ
                && nz <= maxZ
                && !occupied.contains(key)
                && !exterior.contains(key)
                && air.test(nx, ny, nz)) {
                exterior.add(key);
                queue.add(new int[] { nx, ny, nz });
                discovered++;
            }
            walked++;
            if (side == 6) active = null;
        } else if (stage == 3) {
            if (cursor < cells.size()) {
                int[] c = cells.get(cursor++);
                exterior.remove(HologramHatchGeometry.key(c[0], c[1], c[2]));
            } else stage = 4;
        }
    }
}
