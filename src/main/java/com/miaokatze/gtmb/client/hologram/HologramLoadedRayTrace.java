package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.miaokatzecore.api.camera.CameraRay;

/** Pure, exact voxel traversal. Unloaded space before the visible world is not an occluder. */
final class HologramLoadedRayTrace {

    interface Loaded {

        boolean test(int x, int y, int z);
    }

    interface Trace<T> {

        /** Only called for a loaded voxel; both ray parameters bound that voxel exactly. */
        T hit(int x, int y, int z, double from, double to);
    }

    static final class Result<T> {

        final boolean available;
        final T hit;

        Result(boolean available, T hit) {
            this.available = available;
            this.hit = hit;
        }
    }

    private HologramLoadedRayTrace() {}

    static <T> Result<T> trace(CameraRay ray, double start, double end, Loaded loaded, Trace<T> trace) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start)
            return new Result<>(false, null);
        double[] origin = { ray.originX(), ray.originY(), ray.originZ() };
        double[] direction = { ray.directionX(), ray.directionY(), ray.directionZ() };
        int[] cell = new int[3], step = new int[3];
        double[] boundary = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double position = origin[axis] + direction[axis] * start;
            cell[axis] = (int) Math.floor(position);
            step[axis] = (int) Math.signum(direction[axis]);
            // At a negative-going boundary the ray enters the lower voxel, not the voxel it just left.
            if (step[axis] < 0 && position == cell[axis]) cell[axis]--;
            boundary[axis] = nextBoundary(origin[axis], direction[axis], cell[axis]);
        }
        boolean entered = false;
        double from = start;
        while (from < end) {
            double to = Math.min(end, Math.min(boundary[0], Math.min(boundary[1], boundary[2])));
            if (to > from) {
                boolean present = loaded.test(cell[0], cell[1], cell[2]);
                if (!present && entered) return new Result<>(false, null);
                if (present) {
                    entered = true;
                    T hit = trace.hit(cell[0], cell[1], cell[2], from, to);
                    if (hit != null) return new Result<>(true, hit);
                }
            }
            if (to >= end) break;
            // Advance every tied axis: a corner touch has no interval inside either side voxel.
            for (int axis = 0; axis < 3; axis++) if (boundary[axis] <= to) {
                cell[axis] += step[axis];
                boundary[axis] = nextBoundary(origin[axis], direction[axis], cell[axis]);
            }
            from = Math.max(from, to);
        }
        return new Result<>(entered, null);
    }

    private static double nextBoundary(double origin, double direction, int cell) {
        return direction == 0 ? Double.POSITIVE_INFINITY : ((direction > 0 ? cell + 1.0 : cell) - origin) / direction;
    }
}
