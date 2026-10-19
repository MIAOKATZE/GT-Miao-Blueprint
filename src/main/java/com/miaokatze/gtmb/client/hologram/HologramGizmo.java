package com.miaokatze.gtmb.client.hologram;

/** Blender-style axis handles with camera-aware screen hit testing and integer block snapping. */
public final class HologramGizmo {

    public static final int[] COLORS = { 0xffed6767, 0xff79dc8c, 0xff6f9ef8 };
    public static final int XY = 3, XZ = 4, YZ = 5;
    private static final int[][] PLANE_AXES = { { 0, 1 }, { 0, 2 }, { 1, 2 } };
    private final double[][] axes = new double[3][2];
    private double x, y;
    private int dragging = -1, startX, startY;
    private int delta;
    private double gridScale = 12;
    private final int[] offset = new int[3];

    public void project(double x, double y, double yaw, double pitch) {
        project(x, y, yaw, pitch, 12);
    }

    public void project(double x, double y, double yaw, double pitch, double gridScale) {
        this.x = x;
        this.y = y;
        this.gridScale = Math.max(.1, gridScale);
        axes[0][0] = Math.cos(yaw);
        axes[0][1] = Math.sin(yaw) * Math.sin(pitch);
        axes[1][0] = 0;
        axes[1][1] = -Math.cos(pitch);
        axes[2][0] = -Math.sin(yaw);
        axes[2][1] = Math.cos(yaw) * Math.sin(pitch);
    }

    public double[] start() {
        return new double[] { x, y };
    }

    public double[] end(int axis) {
        return new double[] { x + axes[axis][0] * 42, y + axes[axis][1] * 42 };
    }

    public int pick(int mx, int my) {
        for (int handle = XY; handle <= YZ; handle++) {
            int[] pair = PLANE_AXES[handle - XY];
            double determinant = determinant(pair[0], pair[1]);
            if (Math.abs(determinant) < .08) continue;
            double[] at = planeCoordinates(pair[0], pair[1], mx - x, my - y, determinant);
            if (at[0] >= 12 && at[0] <= 23 && at[1] >= 12 && at[1] <= 23) return handle;
        }
        int result = -1;
        double best = 6;
        for (int axis = 0; axis < 3; axis++) {
            double[] end = end(axis);
            double distance = distance(mx, my, x, y, end[0], end[1]);
            double length = Math.hypot(end[0] - x, end[1] - y);
            if (length >= 8 && Math.hypot(mx - x, my - y) >= 9 && distance < best) {
                result = axis;
                best = distance;
            }
        }
        return result;
    }

    public static double distance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
        if (length == 0) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    public void begin(int axis, int mx, int my) {
        if (axis < 0 || axis > YZ) {
            cancel();
            return;
        }
        dragging = axis;
        startX = mx;
        startY = my;
        delta = 0;
        java.util.Arrays.fill(offset, 0);
    }

    public int update(int mx, int my) {
        if (dragging < 0) return 0;
        if (dragging < XY) {
            double dx = axes[dragging][0], dy = axes[dragging][1];
            delta = (int) Math
                .round(((mx - startX) * dx + (my - startY) * dy) / Math.max(.01, dx * dx + dy * dy) / gridScale);
            offset[dragging] = delta;
        } else {
            int[] pair = PLANE_AXES[dragging - XY];
            double determinant = determinant(pair[0], pair[1]);
            if (Math.abs(determinant) < .08) return delta;
            double[] at = planeCoordinates(pair[0], pair[1], mx - startX, my - startY, determinant);
            offset[pair[0]] = (int) Math.round(at[0] / gridScale);
            offset[pair[1]] = (int) Math.round(at[1] / gridScale);
            delta = offset[pair[0]];
        }
        return delta;
    }

    private double determinant(int a, int b) {
        return axes[a][0] * axes[b][1] - axes[b][0] * axes[a][1];
    }

    private double[] planeCoordinates(int a, int b, double sx, double sy, double determinant) {
        return new double[] { (sx * axes[b][1] - axes[b][0] * sy) / determinant,
            (axes[a][0] * sy - sx * axes[a][1]) / determinant };
    }

    public double[][] plane(int handle) {
        int[] pair = PLANE_AXES[handle - XY];
        double[][] corners = new double[4][2];
        for (int i = 0; i < 4; i++) {
            int a = i == 0 || i == 3 ? 12 : 23, b = i < 2 ? 12 : 23;
            corners[i][0] = x + axes[pair[0]][0] * a + axes[pair[1]][0] * b;
            corners[i][1] = y + axes[pair[0]][1] * a + axes[pair[1]][1] * b;
        }
        return corners;
    }

    public int[] offset() {
        return offset.clone();
    }

    public static String label(int handle) {
        return handle >= 0 && handle <= YZ ? new String[] { "X", "Y", "Z", "XY", "XZ", "YZ" }[handle] : "";
    }

    public static int color(int handle) {
        return COLORS[handle < XY ? handle : handle == XY ? 2 : handle == XZ ? 1 : 0];
    }

    public int axis() {
        return dragging;
    }

    public int delta() {
        return delta;
    }

    public void cancel() {
        dragging = -1;
        delta = 0;
        java.util.Arrays.fill(offset, 0);
    }
}
