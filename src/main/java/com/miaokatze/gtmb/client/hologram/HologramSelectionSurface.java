package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Exact outer boundary of the ordered selection mask, independent of world cells and OpenGL. */
final class HologramSelectionSurface {

    private HologramSelectionSurface() {}

    /** Half-open coordinates also represent the face after Integer.MAX_VALUE without overflow. */
    static final class Box {

        final long[] low, high;
        final boolean subtract;

        Box(int ax, int ay, int az, int bx, int by, int bz, boolean subtract) {
            this(
                new long[] { Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz) },
                new long[] { (long) Math.max(ax, bx) + 1, (long) Math.max(ay, by) + 1, (long) Math.max(az, bz) + 1 },
                subtract);
        }

        private Box(long[] low, long[] high, boolean subtract) {
            this.low = low;
            this.high = high;
            this.subtract = subtract;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Box)) return false;
            Box box = (Box) other;
            return subtract == box.subtract && Arrays.equals(low, box.low) && Arrays.equals(high, box.high);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(low) + Arrays.hashCode(high) + (subtract ? 1 : 0);
        }
    }

    static final class Face {

        final int axis, normal;
        final long plane, u0, v0, v1;
        long u1;

        Face(int axis, int normal, long plane, long u0, long u1, long v0, long v1) {
            this.axis = axis;
            this.normal = normal;
            this.plane = plane;
            this.u0 = u0;
            this.u1 = u1;
            this.v0 = v0;
            this.v1 = v1;
        }
    }

    /** World-aligned crosshatching clipped to one face; at most eighteen segments regardless of its world span. */
    static List<double[]> hatch(Face face) {
        List<double[]> lines = new ArrayList<>();
        double spacing = .5;
        double target = ((double) face.u1 - face.u0 + (double) face.v1 - face.v0) / 8;
        while (spacing < target) spacing *= 2;
        for (int direction : new int[] { -1, 1 }) {
            double min = direction > 0 ? (double) face.u0 + face.v0 : (double) face.u0 - face.v1;
            double max = direction > 0 ? (double) face.u1 + face.v1 : (double) face.u1 - face.v0;
            for (double diagonal = Math.ceil(min / spacing) * spacing; diagonal <= max; diagonal += spacing) {
                double from = Math.max(face.u0, direction > 0 ? diagonal - face.v1 : diagonal + face.v0);
                double to = Math.min(face.u1, direction > 0 ? diagonal - face.v0 : diagonal + face.v1);
                if (to <= from) continue;
                lines.add(
                    new double[] { from, direction > 0 ? diagonal - from : from - diagonal, to,
                        direction > 0 ? diagonal - to : to - diagonal });
            }
        }
        return lines;
    }

    /** Only geometry enters the cache key; cursor movement matters solely while a corner is pending. */
    static List<Box> boxes(NBTTagCompound selection, int[] cursor) {
        return boxes(selection, cursor, Collections.emptyList());
    }

    static List<Box> boxes(NBTTagCompound selection, int[] cursor, List<PlanarRect> polygon) {
        List<Box> boxes = new ArrayList<>();
        NBTTagList rows = selection.getTagList("areaRegions", 10);
        for (int i = 0; i < Math.min(128, rows.tagCount()); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            boxes.add(
                new Box(
                    row.getInteger("minX"),
                    row.getInteger("minY"),
                    row.getInteger("minZ"),
                    row.getInteger("maxX"),
                    row.getInteger("maxY"),
                    row.getInteger("maxZ"),
                    "subtract".equals(row.getString("mode"))));
        }
        int[] points = selection.getIntArray("areaPoints");
        if (!selection.getBoolean("areaContinuous") && (points.length == 3 || points.length == 6 && boxes.isEmpty())) {
            int[] end = points.length == 6 ? new int[] { points[3], points[4], points[5] }
                : cursor == null ? points : cursor;
            String mode = selection.getString("areaPointMode");
            // The model clears previous regions when a new replacement box is committed.
            if (!"add".equals(mode) && !"subtract".equals(mode)) boxes.clear();
            boxes.add(new Box(points[0], points[1], points[2], end[0], end[1], end[2], "subtract".equals(mode)));
        }
        if (selection.getBoolean("areaContinuous") && selection.getBoolean("areaPolygonClosed") && !polygon.isEmpty()) {
            int[] vertices = selection.getIntArray("areaPolygon");
            int axis = selection.getInteger("areaPolygonHeightAxis");
            if (vertices.length < 9 || axis < 0 || axis > 2) return boxes;
            int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
            int height = selection.getBoolean("areaPolygonHeightChosen") ? selection.getInteger("areaPolygonHeight")
                : cursor == null ? vertices[axis] : cursor[axis];
            String mode = selection.getString("areaPolygonMode");
            if (!"add".equals(mode) && !"subtract".equals(mode)) boxes.clear();
            for (PlanarRect rect : polygon) {
                long[] low = new long[3], high = new long[3];
                low[axis] = Math.min(vertices[axis], height);
                high[axis] = (long) Math.max(vertices[axis], height) + 1;
                low[u] = rect.u0;
                high[u] = rect.u1;
                low[v] = rect.v0;
                high[v] = rect.v1;
                boxes.add(new Box(low, high, "subtract".equals(mode)));
            }
        }
        return boxes;
    }

    static final class PlanarRect {

        final long u0, u1, v0;
        long v1;

        PlanarRect(long u0, long u1, long v0, long v1) {
            this.u0 = u0;
            this.u1 = u1;
            this.v0 = v0;
            this.v1 = v1;
        }
    }

    /** Reuse the existing exact raster outline; thickness changes never reraster its block footprint. */
    static final class PlanarBuilder {

        private final List<int[]> edges;
        private final TreeSet<Long> crossings = new TreeSet<>();
        private Map<Long, PlanarRect> previous = Collections.emptyMap();
        private final List<PlanarRect> rectangles = new ArrayList<>();
        private int edge;
        private boolean complete;
        private long rowStart, rowEnd;

        PlanarBuilder(List<int[]> edges) {
            this.edges = edges;
        }

        boolean complete() {
            return complete;
        }

        boolean supported() {
            // The model rejects footprints requiring over 128 raster prisms; retain only their outline preview.
            return rectangles.size() <= 128;
        }

        List<PlanarRect> rectangles() {
            if (!complete) throw new IllegalStateException("Polygon footprint is still being built");
            return supported() ? Collections.unmodifiableList(rectangles) : Collections.emptyList();
        }

        void advance(long budgetNanos) {
            long started = System.nanoTime();
            do {
                if (complete) return;
                step();
            } while (System.nanoTime() - started < budgetNanos);
        }

        private void step() {
            if (edge < edges.size()) {
                int[] line = edges.get(edge++);
                if (line[0] == line[2]) {
                    // HologramSelectionOutline emits these unit vertical edges in ascending raster-row order.
                    long from = Math.min(line[1], line[3]), to = Math.max(line[1], line[3]);
                    if (!crossings.isEmpty() && (from != rowStart || to != rowEnd)) finishRow();
                    rowStart = from;
                    rowEnd = to;
                    long column = line[0];
                    if (!crossings.add(column)) crossings.remove(column);
                }
                if (!supported()) complete = true;
                return;
            }
            finishRow();
            complete = true;
        }

        private void finishRow() {
            Map<Long, PlanarRect> next = new HashMap<>();
            Long start = null;
            for (long crossing : crossings) {
                if (start == null) start = crossing;
                else {
                    long end = crossing, key = start << 32 | end & 0xffffffffL;
                    PlanarRect rect = previous.get(key);
                    if (rect == null || rect.v1 != rowStart) {
                        rect = new PlanarRect(start, end, rowStart, rowEnd);
                        rectangles.add(rect);
                    } else rect.v1 = rowEnd;
                    next.put(key, rect);
                    start = null;
                }
            }
            previous = next;
            crossings.clear();
        }
    }

    /**
     * Coordinate compression bounds work by region complexity, never world-space volume. Each step handles one
     * compressed planar strip; callers may spread construction across frames and publish only the complete mesh.
     */
    static final class Builder {

        private final List<Box> boxes;
        private final long[][] coordinates = new long[3][];
        private final int[][] low, high;
        private final List<Face> faces = new ArrayList<>();
        private Map<Long, Face> previous = Collections.emptyMap();
        private int axis, plane, row;

        Builder(List<Box> supplied) {
            boxes = new ArrayList<>(supplied);
            low = new int[boxes.size()][3];
            high = new int[boxes.size()][3];
            for (int a = 0; a < 3; a++) {
                TreeSet<Long> values = new TreeSet<>();
                for (Box box : boxes) {
                    values.add(box.low[a]);
                    values.add(box.high[a]);
                }
                coordinates[a] = new long[values.size()];
                int index = 0;
                for (long value : values) coordinates[a][index++] = value;
                for (int i = 0; i < boxes.size(); i++) {
                    low[i][a] = Arrays.binarySearch(coordinates[a], boxes.get(i).low[a]);
                    high[i][a] = Arrays.binarySearch(coordinates[a], boxes.get(i).high[a]);
                }
            }
            if (boxes.isEmpty()) axis = 3;
        }

        boolean complete() {
            return axis == 3;
        }

        List<Face> faces() {
            if (!complete()) throw new IllegalStateException("Selection boundary is still being built");
            return Collections.unmodifiableList(faces);
        }

        void advance(long budgetNanos) {
            long started = System.nanoTime();
            do {
                if (complete()) return;
                step();
            } while (System.nanoTime() - started < budgetNanos);
        }

        private void step() {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            BitSet left = new BitSet(coordinates[v].length), right = new BitSet(coordinates[v].length);
            for (int i = 0; i < boxes.size(); i++) {
                if (low[i][u] > row || high[i][u] <= row) continue;
                boolean add = !boxes.get(i).subtract;
                if (low[i][axis] < plane && high[i][axis] >= plane) left.set(low[i][v], high[i][v], add);
                if (low[i][axis] <= plane && high[i][axis] > plane) right.set(low[i][v], high[i][v], add);
            }
            BitSet positive = (BitSet) left.clone();
            positive.andNot(right);
            right.andNot(left);
            Map<Long, Face> next = new HashMap<>();
            strips(positive, 1, u, v, next);
            strips(right, -1, u, v, next);
            previous = next;
            if (++row == coordinates[u].length - 1) {
                row = 0;
                previous = Collections.emptyMap();
                if (++plane == coordinates[axis].length) {
                    plane = 0;
                    axis++;
                }
            }
        }

        private void strips(BitSet mask, int normal, int u, int v, Map<Long, Face> next) {
            for (int start = mask.nextSetBit(0); start >= 0;) {
                int end = mask.nextClearBit(start);
                long key = (long) start << 32 | (long) end << 1 | (normal > 0 ? 1 : 0);
                Face face = previous.get(key);
                if (face == null) {
                    face = new Face(
                        axis,
                        normal,
                        coordinates[axis][plane],
                        coordinates[u][row],
                        coordinates[u][row + 1],
                        coordinates[v][start],
                        coordinates[v][end]);
                    faces.add(face);
                } else face.u1 = coordinates[u][row + 1];
                next.put(key, face);
                start = mask.nextSetBit(end);
            }
        }
    }
}
