package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Block-boundary geometry only; neither the world nor the OpenGL state is consulted. */
final class HologramSelectionOutline {

    interface Occupancy {

        boolean contains(int x, int y, int z);
    }

    private HologramSelectionOutline() {}

    /** Four cells meet at an edge. Coplanar shared faces have no crease to draw. */
    static boolean exterior(boolean a, boolean b, boolean c, boolean d) {
        int count = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0);
        return count == 1 || count == 3 || count == 2 && a == d;
    }

    static boolean ownsEdge(int cellX, int cellY, int cellZ, int x, int y, int z, int axis, Occupancy cells) {
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        int occupied = 0;
        boolean owner = false;
        for (int i = 0; i < 4; i++) {
            int du = (i & 1) == 0 ? 1 : 0, dv = (i & 2) == 0 ? 1 : 0;
            int px = x - (u == 0 ? du : v == 0 ? dv : 0);
            int py = y - (u == 1 ? du : v == 1 ? dv : 0);
            int pz = z - (u == 2 ? du : v == 2 ? dv : 0);
            if (!cells.contains(px, py, pz)) continue;
            if (occupied == 0) owner = px == cellX && py == cellY && pz == cellZ;
            occupied |= 1 << i;
        }
        return owner && exterior((occupied & 1) != 0, (occupied & 2) != 0, (occupied & 4) != 0, (occupied & 8) != 0);
    }

    /** Raster the same inclusive block-center polygon as import, retaining its concave outer perimeter. */
    static List<int[]> polygon(int[] supplied, int[] cursor, boolean closed) {
        return polygon(supplied, cursor, closed, 1);
    }

    /** Edges use the two in-plane axes in world-axis order, excluding the extrusion axis. */
    static List<int[]> polygon(int[] supplied, int[] cursor, boolean closed, int heightAxis) {
        int u = heightAxis == 0 ? 1 : 0, v = heightAxis == 2 ? 1 : 2;
        int[] planar = new int[supplied.length];
        for (int i = 0; i < supplied.length; i += 3) {
            planar[i] = supplied[i + u];
            planar[i + 2] = supplied[i + v];
        }
        return planarPolygon(planar, cursor == null ? null : new int[] { cursor[u], 0, cursor[v] }, closed);
    }

    private static List<int[]> planarPolygon(int[] supplied, int[] cursor, boolean closed) {
        int[] vertices = supplied;
        boolean existing = false;
        if (cursor != null) for (int i = 0; i < supplied.length; i += 3)
            if (supplied[i] == cursor[0] && supplied[i + 2] == cursor[2]) existing = true;
        if (!closed && cursor != null && !existing) {
            vertices = Arrays.copyOf(supplied, supplied.length + 3);
            System.arraycopy(cursor, 0, vertices, supplied.length, 3);
        }
        List<int[]> edges = new ArrayList<>();
        if (vertices.length < 3) return edges;
        int minX = vertices[0], maxX = minX, minZ = vertices[2], maxZ = minZ;
        for (int i = 0; i < vertices.length; i += 3) {
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }
        long area = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        if (area > 1_048_576L) return edges;
        int width = maxX - minX + 1;
        boolean[] previous = new boolean[width];
        for (int z = minZ; z <= maxZ + 1; z++) {
            boolean[] row = new boolean[width];
            List<Double> crossings = new ArrayList<>();
            if (z <= maxZ) for (int i = 0; i < vertices.length; i += 3) {
                int j = (i + 3) % vertices.length;
                int ax = vertices[i], az = vertices[i + 2], bx = vertices[j], bz = vertices[j + 2];
                if (az == bz) {
                    if (z == az) Arrays.fill(row, Math.min(ax, bx) - minX, Math.max(ax, bx) - minX + 1, true);
                } else {
                    double x = ax + (double) (bx - ax) * (z - az) / (bz - az);
                    if ((az > z) != (bz > z)) crossings.add(x);
                    if (z >= Math.min(az, bz) && z <= Math.max(az, bz) && Math.abs(x - Math.rint(x)) < 1e-7)
                        row[(int) Math.rint(x) - minX] = true;
                }
            }
            java.util.Collections.sort(crossings);
            for (int i = 0; i + 1 < crossings.size(); i += 2) {
                int from = Math.max(0, (int) Math.ceil(crossings.get(i)) - minX);
                int to = Math.min(width, (int) Math.floor(crossings.get(i + 1)) - minX + 1);
                if (from < to) Arrays.fill(row, from, to, true);
            }
            int start = -1;
            for (int x = 0; x <= width; x++) {
                boolean boundary = x < width && row[x] != previous[x];
                if (boundary && start < 0) start = x;
                if (!boundary && start >= 0) {
                    edges.add(new int[] { minX + start, z, minX + x, z });
                    start = -1;
                }
                if (z <= maxZ && (x > 0 && row[x - 1]) != (x < width && row[x]))
                    edges.add(new int[] { minX + x, z, minX + x, z + 1 });
            }
            previous = row;
        }
        return edges;
    }
}
