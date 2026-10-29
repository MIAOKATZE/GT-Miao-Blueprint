package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure inspection of the canonical native vertex buffer; never changes the emitted geometry. */
final class HologramNativeGeometryInspection {

    final List<double[]> points, textureCenters, attributes;
    private final boolean collectPoints, collectAttributes;
    private final Positions positions = new Positions();
    boolean missingTexture;

    HologramNativeGeometryInspection(boolean collectPoints, boolean collectAttributes) {
        this.collectPoints = collectPoints;
        this.collectAttributes = collectAttributes;
        points = collectPoints ? new ArrayList<>() : Collections.emptyList();
        textureCenters = collectPoints ? new ArrayList<>() : Collections.emptyList();
        attributes = collectAttributes ? new ArrayList<>() : Collections.emptyList();
    }

    int uniquePositions() {
        return positions.size;
    }

    void inspect(int[] raw, int emitted, double missingMinU, double missingMaxU, double missingMinV,
        double missingMaxV) {
        // Validate each complete quad once, rather than once per vertex and again for its UVs.
        // Preserve the historical trailing-vertex position count; only complete quads have UV/attribute evidence.
        for (int first = 0; first < emitted; first += 4) {
            boolean complete = first + 3 < emitted;
            if (complete && !usableQuad(raw, first)) continue;
            int end = Math.min(first + 4, emitted);
            for (int vertex = first; vertex < end; vertex++) {
                int offset = vertex * 8;
                if (positions.add(raw[offset], raw[offset + 1], raw[offset + 2]) && collectPoints) points.add(
                    new double[] { Float.intBitsToFloat(raw[offset]), Float.intBitsToFloat(raw[offset + 1]),
                        Float.intBitsToFloat(raw[offset + 2]) });
                if (complete && collectAttributes) attributes.add(
                    new double[] { Float.intBitsToFloat(raw[offset]), Float.intBitsToFloat(raw[offset + 1]),
                        Float.intBitsToFloat(raw[offset + 2]), Float.intBitsToFloat(raw[offset + 3]),
                        Float.intBitsToFloat(raw[offset + 4]), raw[offset + 5] });
            }
            if (!complete) continue;
            double u = 0, v = 0;
            for (int corner = 0; corner < 4; corner++) {
                u += Float.intBitsToFloat(raw[(first + corner) * 8 + 3]) / 4.0;
                v += Float.intBitsToFloat(raw[(first + corner) * 8 + 4]) / 4.0;
            }
            if (collectPoints) textureCenters.add(new double[] { u, v });
            if (u > missingMinU + 1e-6 && u < missingMaxU - 1e-6 && v > missingMinV + 1e-6 && v < missingMaxV - 1e-6)
                missingTexture = true;
        }
    }

    private static boolean usableQuad(int[] raw, int first) {
        for (int triangle = 0; triangle < 2; triangle++) {
            int a = first * 8, b = (first + triangle + 1) * 8, c = (first + triangle + 2) * 8;
            double bx = Float.intBitsToFloat(raw[b]) - Float.intBitsToFloat(raw[a]);
            double by = Float.intBitsToFloat(raw[b + 1]) - Float.intBitsToFloat(raw[a + 1]);
            double bz = Float.intBitsToFloat(raw[b + 2]) - Float.intBitsToFloat(raw[a + 2]);
            double cx = Float.intBitsToFloat(raw[c]) - Float.intBitsToFloat(raw[a]);
            double cy = Float.intBitsToFloat(raw[c + 1]) - Float.intBitsToFloat(raw[a + 1]);
            double cz = Float.intBitsToFloat(raw[c + 2]) - Float.intBitsToFloat(raw[a + 2]);
            double nx = by * cz - bz * cy, ny = bz * cx - bx * cz, nz = bx * cy - by * cx;
            if (nx * nx + ny * ny + nz * nz > 1e-18) return true;
        }
        return false;
    }

    /** Exact raw-bit triples, including signed zero and NaN payloads; no per-vertex decimal strings. */
    private static final class Positions {

        private int[] xyz = new int[16 * 3];
        private boolean[] occupied = new boolean[16];
        private int size;

        boolean add(int x, int y, int z) {
            if (size >= occupied.length / 2) grow();
            int slot = slot(x, y, z);
            if (occupied[slot]) return false;
            occupied[slot] = true;
            xyz[slot * 3] = x;
            xyz[slot * 3 + 1] = y;
            xyz[slot * 3 + 2] = z;
            size++;
            return true;
        }

        private int slot(int x, int y, int z) {
            int hash = (x * 31 + y) * 31 + z;
            hash ^= hash >>> 16;
            hash *= 0x7feb352d;
            hash ^= hash >>> 15;
            int slot = hash & (occupied.length - 1);
            while (occupied[slot] && (xyz[slot * 3] != x || xyz[slot * 3 + 1] != y || xyz[slot * 3 + 2] != z))
                slot = (slot + 1) & (occupied.length - 1);
            return slot;
        }

        private void grow() {
            int[] previous = xyz;
            boolean[] used = occupied;
            occupied = new boolean[Math.multiplyExact(used.length, 2)];
            xyz = new int[Math.multiplyExact(occupied.length, 3)];
            size = 0;
            for (int slot = 0; slot < used.length; slot++)
                if (used[slot]) add(previous[slot * 3], previous[slot * 3 + 1], previous[slot * 3 + 2]);
        }
    }
}
