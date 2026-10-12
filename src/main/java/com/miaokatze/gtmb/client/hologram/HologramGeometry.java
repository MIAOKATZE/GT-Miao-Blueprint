package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

/** Static bounds from GT MetaPipeEntity.renderInWorld, independent of a world or GL context. */
final class HologramGeometry {

    static final class Surface {

        final double[][] vertices;
        final int side, coverSide;

        Surface(double[] bounds, int side, int coverSide) {
            this.side = side;
            this.coverSide = coverSide;
            double x = bounds[0] - .5, y = bounds[1] - .5, z = bounds[2] - .5;
            double X = bounds[3] - .5, Y = bounds[4] - .5, Z = bounds[5] - .5;
            vertices = new double[][] { { x, y, z }, { X, y, z }, { X, Y, z }, { x, Y, z }, { x, y, Z }, { X, y, Z },
                { X, Y, Z }, { x, Y, Z } };
        }
    }

    private HologramGeometry() {}

    static List<Surface> cube() {
        List<Surface> faces = new ArrayList<>();
        box(faces, new double[] { 0, 0, 0, 1, 1, 1 }, 63, -1);
        return faces;
    }

    static List<Surface> pipe(float thickness, int connections, int covers) {
        List<Surface> faces = new ArrayList<>();
        double size = Math.max(1.0 / 16, Math.min(1, thickness));
        double low = (1 - size) / 2, high = 1 - low;
        connections &= 63;
        if (size >= 1) {
            box(faces, new double[] { 0, 0, 0, 1, 1, 1 }, 63, -1);
            return faces;
        }
        box(faces, new double[] { low, low, low, high, high, high }, 63 & ~connections, -1);
        for (int side = 0; side < 6; side++) {
            if ((connections & 1 << side) == 0) continue;
            double[] bounds = { low, low, low, high, high, high };
            int axis = axis(side);
            if ((side & 1) == 0) {
                bounds[axis] = 0;
                bounds[axis + 3] = low;
            } else {
                bounds[axis] = high;
                bounds[axis + 3] = 1;
            }
            box(faces, bounds, 63 & ~(1 << (side ^ 1)), -1);
        }
        // Native GT covers on pipes are full-face plates with a hole at a connected pipe end.
        double plate = Math.min(.125, low);
        for (int side = 0; side < 6; side++) {
            if ((covers & 1 << side) == 0) continue;
            int axis = axis(side);
            double[] bounds = { 0, 0, 0, 1, 1, 1 };
            if ((side & 1) == 0) bounds[axis + 3] = plate;
            else bounds[axis] = 1 - plate;
            if ((connections & 1 << side) == 0) box(faces, bounds, 63, side);
            else {
                int a = (axis + 1) % 3, b = (axis + 2) % 3;
                double[] lower = bounds.clone();
                lower[a + 3] = low;
                double[] upper = bounds.clone();
                upper[a] = high;
                double[] left = bounds.clone();
                left[a] = low;
                left[a + 3] = high;
                left[b + 3] = low;
                double[] right = bounds.clone();
                right[a] = low;
                right[a + 3] = high;
                right[b] = high;
                box(faces, lower, 63, side);
                box(faces, upper, 63, side);
                box(faces, left, 63, side);
                box(faces, right, 63, side);
            }
        }
        return faces;
    }

    private static int axis(int side) {
        return side < 2 ? 1 : side < 4 ? 2 : 0;
    }

    private static void box(List<Surface> faces, double[] bounds, int mask, int coverSide) {
        for (int side = 0; side < 6; side++)
            if ((mask & 1 << side) != 0) faces.add(new Surface(bounds, side, coverSide));
    }
}
