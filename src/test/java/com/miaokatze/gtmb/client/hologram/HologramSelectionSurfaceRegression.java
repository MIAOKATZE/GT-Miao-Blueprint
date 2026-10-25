package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Small independent voxel oracle: every selected/unselected interface appears exactly once. */
final class HologramSelectionSurfaceRegression {

    private HologramSelectionSurfaceRegression() {}

    static void run() {
        checkHatch(new HologramSelectionSurface.Face(1, 1, 0, -3, 2, -2, 3));
        checkHatch(new HologramSelectionSurface.Face(0, -1, -2, 0, 1, 0, 1));
        checkHatch(new HologramSelectionSurface.Face(2, 1, 1, -12, 20, -7, 9));
        checkHatchWindows();
        try {
            HologramAreaSelection area = new HologramAreaSelection();
            box(area, -3, -2, -3, -1, 1, 1, "replace");
            box(area, 0, -2, -3, 2, 1, 1, "add");
            List<HologramSelectionSurface.Face> joined = verify(area);
            SimpleAssert.eq(6, joined.size(), "adjacent full faces merge into the six outer faces");
            box(area, -1, -1, -2, 3, 2, 2, "add");
            verify(area);
            box(area, -1, -1, -1, 0, 0, 0, "subtract");
            verify(area);
            box(area, 0, 0, 0, 0, 0, 0, "add");
            verify(area);

            HologramAreaSelection polygon = checkPolygon(1);
            checkPolygon(0);
            checkPolygon(2);

            area.beginNext("subtract");
            area.worldPoint(2, -2, -2, -2);
            NBTTagCompound pending = area.snapshot();
            List<HologramSelectionSurface.Box> pendingBoxes = HologramSelectionSurface
                .boxes(pending, new int[] { 1, 1, 1 });
            SimpleAssert
                .that(pendingBoxes.get(pendingBoxes.size() - 1).subtract, "pending right corner keeps subtraction");
            SimpleAssert.eq(
                pendingBoxes,
                HologramSelectionSurface.boxes(pending, new int[] { 1, 1, 1 }),
                "an unchanged geometry key reuses the cache");
            SimpleAssert.that(
                !pendingBoxes.equals(HologramSelectionSurface.boxes(pending, new int[] { 2, 1, 1 })),
                "only a pending corner makes cursor movement part of the geometry key");
            HologramSelectionSurface.Builder preview = new HologramSelectionSurface.Builder(pendingBoxes);
            while (!preview.complete()) preview.advance(0);
            area.worldPoint(1, 1, 1, 1);
            List<HologramSelectionSurface.Face> committed = verify(area);
            SimpleAssert.eq(
                unitFaces(committed),
                unitFaces(preview.faces()),
                "pending subtraction previews exactly the mask produced when its other corner is confirmed");
            SimpleAssert.eq(
                HologramSelectionSurface.boxes(polygon.snapshot(), new int[] { 1, 1, 1 }),
                HologramSelectionSurface.boxes(polygon.snapshot(), new int[] { 99, 99, 99 }),
                "completed polygon geometry ignores cursor movement");

            HologramSelectionSurface.Builder huge = new HologramSelectionSurface.Builder(
                Arrays.asList(
                    new HologramSelectionSurface.Box(Integer.MIN_VALUE, -1, -1, Integer.MAX_VALUE, 1, 1, false)));
            while (!huge.complete()) huge.advance(0);
            SimpleAssert.eq(
                6,
                huge.faces()
                    .size(),
                "coordinate span never causes world-cell enumeration");
            boolean upper = false;
            for (HologramSelectionSurface.Face face : huge.faces())
                if (face.axis == 0 && face.normal == 1) upper = face.plane == 2_147_483_648L;
            SimpleAssert.that(upper, "inclusive maximum integer coordinates retain their outer face");
        } catch (IOException invalid) {
            throw new AssertionError("selection boundary fixtures", invalid);
        }
    }

    private static void checkHatch(HologramSelectionSurface.Face face) {
        List<double[]> lines = hatch(face);
        SimpleAssert.that(!lines.isEmpty(), "ordinary boundary faces contain visible diagonals");
        boolean forward = false, backward = false;
        double previousForward = Double.NaN, previousBackward = Double.NaN;
        for (double[] line : lines) {
            for (int end : new int[] { 0, 2 }) SimpleAssert.that(
                Double.isFinite(line[end]) && Double.isFinite(line[end + 1])
                    && line[end] >= face.u0
                    && line[end] <= face.u1
                    && line[end + 1] >= face.v0
                    && line[end + 1] <= face.v1,
                "every diagonal endpoint is clipped to its actual CSG boundary face");
            double du = line[2] - line[0], dv = line[3] - line[1];
            SimpleAssert
                .that(du > 0 && Math.abs(Math.abs(dv) - du) < 1e-7, "hatch uses finite nonempty diagonal segments");
            forward |= dv > 0;
            backward |= dv < 0;
            double diagonal = dv > 0 ? line[0] - line[1] : line[0] + line[1];
            SimpleAssert.that(
                Math.abs(diagonal / 2 - Math.rint(diagonal / 2)) < 1e-7,
                "separately clipped faces share the same two-block world grid phase");
            double previous = dv > 0 ? previousForward : previousBackward;
            if (Double.isFinite(previous)) SimpleAssert.that(
                Math.abs(diagonal - previous - 2) < 1e-7,
                "two-block grid spacing never expands to satisfy a face line-count limit");
            if (dv > 0) previousForward = diagonal;
            else previousBackward = diagonal;
        }
        if (face.u1 - face.u0 >= 2 && face.v1 - face.v0 >= 2)
            SimpleAssert.that(forward && backward, "both diagonal mesh directions survive on larger faces");
        if (face.u1 - face.u0 > 10) SimpleAssert
            .that(lines.size() > 18, "larger faces retain all two-block lines instead of eighteen sparse lines");
    }

    private static List<double[]> hatch(HologramSelectionSurface.Face face) {
        HologramSelectionSurface.Hatch hatch = new HologramSelectionSurface.Hatch(face);
        List<double[]> lines = new ArrayList<>();
        for (double[] line; (line = hatch.next()) != null;) lines.add(line);
        return lines;
    }

    private static void checkHatchWindows() {
        double[] orthographic = { .125, 0, 0, 0, 0, .125, 0, 0, 0, 0, .125, 0, 0, 0, 0, 1 };
        HologramSelectionSurface.Face huge = new HologramSelectionSurface.Face(
            1,
            1,
            0,
            Integer.MIN_VALUE,
            (long) Integer.MAX_VALUE + 1,
            Integer.MIN_VALUE,
            (long) Integer.MAX_VALUE + 1);
        HologramSelectionSurface.Face window = HologramSelectionSurface.hatchWindow(huge, orthographic, 0, 0, 0);
        SimpleAssert.that(
            window != null && window.u0 == -16 && window.u1 == 16 && window.v0 == -16 && window.v1 == 16,
            "an integer-span face only uploads its actual visible orthographic tile window");
        checkHatch(window);
        HologramSelectionSurface.Face offscreen = new HologramSelectionSurface.Face(0, 1, 100, -4, 4, -4, 4);
        SimpleAssert.eq(
            null,
            HologramSelectionSurface.hatchWindow(offscreen, orthographic, 0, 0, 0),
            "offscreen faces allocate no hatch upload work");
        // Perspective looking down -Z: X/Y clip against +/-(-Z), near=1, far=32.
        double[] perspective = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -33.0 / 31, -1, 0, 0, -64.0 / 31, 0 };
        HologramSelectionSurface.Face front = new HologramSelectionSurface.Face(2, 1, -8, -100, 100, -100, 100);
        window = HologramSelectionSurface.hatchWindow(front, perspective, 0, 0, 0);
        SimpleAssert.that(
            window != null && window.u0 == -16 && window.u1 == 16 && window.v0 == -16 && window.v1 == 16,
            "ordinary perspective uses the same fixed-spacing visible face window");
        // Keep world alignment with a camera translated far from the origin, including negative coordinates.
        window = HologramSelectionSurface.hatchWindow(huge, orthographic, 103, 0, -103);
        SimpleAssert.that(
            window != null && window.u0 == -112 && window.u1 == -80 && window.v0 == 80 && window.v1 == 112,
            "camera-relative frustum clipping retains the surface's cyclic UV axes and world grid phase");
        HologramSelectionSurface.Hatch resumable = new HologramSelectionSurface.Hatch(huge);
        for (int i = 0; i < 513; i++) SimpleAssert.that(
            resumable.next() != null,
            "a large face iterator resumes after an upload chunk without allocating its entire world grid");
    }

    private static void box(HologramAreaSelection area, int ax, int ay, int az, int bx, int by, int bz, String mode)
        throws IOException {
        area.beginNext(mode);
        area.point(1, ax, ay, az, mode);
        area.point(2, bx, by, bz, mode);
    }

    private static HologramAreaSelection checkPolygon(int axis) throws IOException {
        HologramAreaSelection polygon = new HologramAreaSelection();
        polygon.beginContinuous(false);
        int[][] vertices = { { -2, -2 }, { 2, -2 }, { 2, -1 }, { -1, -1 }, { -1, 2 }, { -2, 2 } };
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        int[] first = null;
        for (int[] vertex : vertices) {
            int[] point = new int[3];
            point[u] = vertex[0];
            point[v] = vertex[1];
            if (first == null) first = point;
            polygon.continuousPoint(point[0], point[1], point[2]);
        }
        polygon.continuousPoint(first[0], first[1], first[2]);
        NBTTagCompound closed = polygon.snapshot();
        HologramSelectionSurface.PlanarBuilder footprint = new HologramSelectionSurface.PlanarBuilder(
            HologramSelectionOutline.polygon(closed.getIntArray("areaPolygon"), null, true, axis));
        while (!footprint.complete()) footprint.advance(0);
        SimpleAssert.that(footprint.supported(), "ordinary concave polygon footprint stays within the model's limit");
        int[] cursor = { 4, 4, 4 };
        cursor[axis] = 2;
        List<HologramSelectionSurface.Box> pending = HologramSelectionSurface
            .boxes(closed, cursor, footprint.rectangles());
        HologramSelectionSurface.Builder preview = new HologramSelectionSurface.Builder(pending);
        while (!preview.complete()) preview.advance(0);
        polygon.continuousPoint(cursor[0], cursor[1], cursor[2]);
        cursor[axis] = 99;
        SimpleAssert.eq(
            pending,
            HologramSelectionSurface.boxes(polygon.snapshot(), cursor, footprint.rectangles()),
            "locked polygon thickness ignores later cursor motion along its normal axis");
        polygon.finishContinuous(2);
        SimpleAssert.eq(
            unitFaces(verify(polygon)),
            unitFaces(preview.faces()),
            "closed polygon preview matches committed raster-box surfaces on axis " + axis);
        return polygon;
    }

    private static List<HologramSelectionSurface.Face> verify(HologramAreaSelection area) {
        HologramSelectionSurface.Builder builder = new HologramSelectionSurface.Builder(
            HologramSelectionSurface.boxes(area.snapshot(), null));
        while (!builder.complete()) builder.advance(0);
        Set<String> actual = unitFaces(builder.faces());
        Set<String> expected = new HashSet<>();
        for (int x = -4; x <= 4; x++) for (int y = -3; y <= 3; y++) for (int z = -4; z <= 4; z++) {
            if (!area.contains(x, y, z)) continue;
            int[] cell = { x, y, z };
            for (int axis = 0; axis < 3; axis++) for (int normal : new int[] { -1, 1 }) {
                int[] neighbor = cell.clone();
                neighbor[axis] += normal;
                if (!area.contains(neighbor[0], neighbor[1], neighbor[2])) expected.add(
                    key(axis, normal, cell[axis] + (normal > 0 ? 1 : 0), cell[(axis + 1) % 3], cell[(axis + 2) % 3]));
            }
        }
        SimpleAssert
            .eq(expected, actual, "surface equals the ordered mask, including concavities and exposed hole walls");
        return builder.faces();
    }

    private static Set<String> unitFaces(List<HologramSelectionSurface.Face> faces) {
        Set<String> actual = new HashSet<>();
        for (HologramSelectionSurface.Face face : faces) {
            for (long u = face.u0; u < face.u1; u++) for (long v = face.v0; v < face.v1; v++) {
                String key = key(face.axis, face.normal, face.plane, u, v);
                SimpleAssert.that(actual.add(key), "overlapping boxes never double-cover a boundary surface: " + key);
            }
        }
        return actual;
    }

    private static String key(int axis, int normal, long plane, long u, long v) {
        return axis + ":" + normal + ":" + plane + ":" + u + ":" + v;
    }
}
