package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Differential CPU buffer inspection only; no GL, CTM, renderer-equivalence or elapsed-time claim. */
public final class HologramNativeGeometryInspectionRegression {

    private HologramNativeGeometryInspectionRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("HologramNativeGeometryInspectionRegression PASS");
    }

    public static void run() {
        for (boolean points : new boolean[] { false, true }) for (boolean attributes : new boolean[] { false, true }) {
            HologramNativeGeometryInspection actual = new HologramNativeGeometryInspection(points, attributes);
            Legacy expected = new Legacy(points, attributes);
            compare(actual, expected, new int[0], 0);
            // Repeated corners and degenerate/valid mixed quads, raw signed-zero positions, NaN UVs.
            int[] unusual = new int[12 * 8];
            vertex(unusual, 4, 0, 0, 0, .5f, .5f);
            vertex(unusual, 5, 1, 0, 0, .5f, .5f);
            vertex(unusual, 6, 1, 1, 0, .5f, .5f);
            vertex(unusual, 7, -0f, 0, 0, .5f, .5f);
            vertex(unusual, 8, 0, 0, 0, Float.NaN, .5f);
            vertex(unusual, 9, 0, 0, 0, .5f, .5f);
            vertex(unusual, 10, 2, 0, 0, .5f, .5f);
            vertex(unusual, 11, 0, 2, 0, .5f, .5f);
            compare(actual, expected, unusual, 12);
            SimpleAssert.eq(6, actual.uniquePositions(), "raw signed zero remains a distinct position");
            // Incomplete trailing vertices have always contributed only position evidence, even NaN payloads.
            int[] tail = new int[3 * 8];
            tail[0] = 0x7fc00001;
            tail[8] = 0x7fc00002;
            tail[16] = Float.floatToRawIntBits(Float.POSITIVE_INFINITY);
            compare(actual, expected, tail, 3);
            // Differential fuzz exercises table growth/collisions, tiny geometry, UV boundaries and multiple passes.
            Random random = new Random(8321401);
            for (int pass = 0; pass < 35; pass++) {
                int emitted = 17 + pass * 31;
                int[] raw = new int[emitted * 8];
                for (int v = 0; v < emitted; v++) {
                    vertex(
                        raw,
                        v,
                        random.nextInt(47),
                        random.nextInt(43),
                        random.nextInt(41),
                        random.nextBoolean() ? .5f : .25f,
                        random.nextBoolean() ? .5f : .75f);
                    if (v % 23 == 0) raw[v * 8] = random.nextInt();
                    if (v % 31 == 0) raw[v * 8 + 3] = 0x7fc00001;
                    raw[v * 8 + 5] = random.nextInt();
                }
                compare(actual, expected, raw, emitted);
            }
            int[] tiny = new int[32];
            vertex(tiny, 0, 0, 0, 0, .25f, .75f);
            vertex(tiny, 1, 1e-6f, 0, 0, .25f, .75f);
            vertex(tiny, 2, 1e-6f, 1e-6f, 0, .25f, .75f);
            vertex(tiny, 3, 0, 1e-6f, 0, .25f, .75f);
            compare(actual, expected, tiny, 4);
        }
        HologramNativeGeometryInspection missing = new HologramNativeGeometryInspection(false, false);
        int[] valid = new int[32];
        vertex(valid, 0, 0, 0, 0, .5f, .5f);
        vertex(valid, 1, 1, 0, 0, .5f, .5f);
        vertex(valid, 2, 1, 1, 0, .5f, .5f);
        vertex(valid, 3, 0, 1, 0, .5f, .5f);
        missing.inspect(valid, 4, .25, .75, .25, .75);
        SimpleAssert.eq(true, missing.missingTexture, "normal mode retains missing-texture detection");
        SimpleAssert.eq(0, missing.points.size(), "normal mode retains no smoke point arrays");
        SimpleAssert.eq(0, missing.attributes.size(), "normal mode retains no smoke attribute arrays");
        // Fresh accumulators: a previous missing quad must not mask false positives at boundaries/NaN UVs.
        for (float u : new float[] { .25f, .75f, Float.NaN, Float.POSITIVE_INFINITY, -.5f }) {
            int[] outside = valid.clone();
            for (int v = 0; v < 4; v++) outside[v * 8 + 3] = Float.floatToRawIntBits(u);
            HologramNativeGeometryInspection fresh = new HologramNativeGeometryInspection(true, true);
            compare(fresh, new Legacy(true, true), outside, 4);
            SimpleAssert.eq(false, fresh.missingTexture, "UV boundary/nonfinite/outside center is not missing atlas");
        }
        int[] degenerate = valid.clone();
        for (int v = 0; v < 4; v++) {
            degenerate[v * 8] = 0;
            degenerate[v * 8 + 1] = 0;
        }
        HologramNativeGeometryInspection sentinel = new HologramNativeGeometryInspection(true, true);
        compare(sentinel, new Legacy(true, true), degenerate, 4);
        SimpleAssert.eq(false, sentinel.missingTexture, "degenerate atlas sentinel is not usable missing geometry");
        SimpleAssert.eq(0, sentinel.uniquePositions(), "degenerate sentinel has no usable positions");
    }

    private static void vertex(int[] raw, int vertex, float x, float y, float z, float u, float v) {
        int offset = vertex * 8;
        raw[offset] = Float.floatToRawIntBits(x);
        raw[offset + 1] = Float.floatToRawIntBits(y);
        raw[offset + 2] = Float.floatToRawIntBits(z);
        raw[offset + 3] = Float.floatToRawIntBits(u);
        raw[offset + 4] = Float.floatToRawIntBits(v);
    }

    private static void compare(HologramNativeGeometryInspection actual, Legacy expected, int[] raw, int emitted) {
        actual.inspect(raw, emitted, .25, .75, .25, .75);
        expected.inspect(raw, emitted);
        SimpleAssert.eq(expected.positions.size(), actual.uniquePositions(), "same exact position count");
        SimpleAssert.eq(expected.missing, actual.missingTexture, "same missing-texture result");
        arrays(expected.points, actual.points);
        arrays(expected.centers, actual.textureCenters);
        arrays(expected.attributes, actual.attributes);
    }

    private static void arrays(List<double[]> expected, List<double[]> actual) {
        SimpleAssert.eq(expected.size(), actual.size(), "same evidence row count");
        for (int row = 0; row < expected.size(); row++) {
            SimpleAssert.eq(expected.get(row).length, actual.get(row).length, "same evidence width");
            for (int column = 0; column < expected.get(row).length; column++) SimpleAssert.eq(
                Double.doubleToRawLongBits(expected.get(row)[column]),
                Double.doubleToRawLongBits(actual.get(row)[column]),
                "same ordered raw-bit evidence");
        }
    }

    /** Frozen 36980a62 inspection algorithm, independent of the replacement helper. */
    private static final class Legacy {

        final Set<String> positions = new HashSet<>();
        final List<double[]> points = new ArrayList<>(), centers = new ArrayList<>(), attributes = new ArrayList<>();
        final boolean collectPoints, collectAttributes;
        boolean missing;

        Legacy(boolean collectPoints, boolean collectAttributes) {
            this.collectPoints = collectPoints;
            this.collectAttributes = collectAttributes;
        }

        void inspect(int[] raw, int emitted) {
            for (int v = 0; v < emitted; v++) {
                int quad = v / 4 * 4;
                if (quad + 3 < emitted && !usableQuad(raw, quad)) continue;
                int start = v * 8;
                if (positions.add(raw[start] + ":" + raw[start + 1] + ":" + raw[start + 2]) && collectPoints)
                    points.add(
                        new double[] { Float.intBitsToFloat(raw[start]), Float.intBitsToFloat(raw[start + 1]),
                            Float.intBitsToFloat(raw[start + 2]) });
            }
            for (int v = 0; v + 3 < emitted; v += 4) {
                if (!usableQuad(raw, v)) continue;
                double u = 0, w = 0;
                for (int corner = 0; corner < 4; corner++) {
                    u += Float.intBitsToFloat(raw[(v + corner) * 8 + 3]) / 4.0;
                    w += Float.intBitsToFloat(raw[(v + corner) * 8 + 4]) / 4.0;
                }
                if (collectPoints) centers.add(new double[] { u, w });
                if (u > .25 + 1e-6 && u < .75 - 1e-6 && w > .25 + 1e-6 && w < .75 - 1e-6) missing = true;
            }
            if (collectAttributes) for (int v = 0; v < emitted; v++) {
                int quad = v / 4 * 4;
                if (quad + 3 >= emitted || !usableQuad(raw, quad)) continue;
                int offset = v * 8;
                attributes.add(
                    new double[] { Float.intBitsToFloat(raw[offset]), Float.intBitsToFloat(raw[offset + 1]),
                        Float.intBitsToFloat(raw[offset + 2]), Float.intBitsToFloat(raw[offset + 3]),
                        Float.intBitsToFloat(raw[offset + 4]), raw[offset + 5] });
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
    }
}
