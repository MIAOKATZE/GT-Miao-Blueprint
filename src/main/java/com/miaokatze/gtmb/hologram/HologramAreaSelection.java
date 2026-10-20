package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Pure selection geometry shared by the local preview and final server import. No world or native data access. */
public class HologramAreaSelection {

    int[] first, second;
    String pointMode = "replace";
    final List<Region> regions = new ArrayList<>();
    final List<SelectionState> regionUndo = new ArrayList<>();
    private final List<SelectionState> regionRedo = new ArrayList<>();
    private int current = -1;
    private boolean more;
    private String inputMode = "replace", polygonMode = "replace";
    private final List<int[]> polygon = new ArrayList<>();
    private boolean continuous, closed, heightChosen;
    private int polygonHeight;
    private int lastWorldCorner;

    private final class SelectionState {

        final int[] a = first == null ? null : first.clone(), b = second == null ? null : second.clone();
        final List<Region> boxes = new ArrayList<>(regions);
        final String mode = pointMode;
        final int selected = current;
        final boolean adding = more;
        final String input = inputMode, polyMode = polygonMode;
        final List<int[]> vertices = new ArrayList<>(polygon);
        final boolean drawing = continuous, loop = closed, high = heightChosen;
        final int height = polygonHeight;
        final int corner = lastWorldCorner;
    }

    private void remember() {
        remember(new SelectionState());
    }

    private void remember(SelectionState state) {
        regionRedo.clear();
        if (regionUndo.size() == 128) regionUndo.remove(0);
        regionUndo.add(state);
    }

    static final class Region {

        final int minX, minY, minZ, maxX, maxY, maxZ;
        final String mode;
        final int[] first, second;

        Region(int[] a, int[] b, String mode) {
            first = a.clone();
            second = b.clone();
            minX = Math.min(a[0], b[0]);
            minY = Math.min(a[1], b[1]);
            minZ = Math.min(a[2], b[2]);
            maxX = Math.max(a[0], b[0]);
            maxY = Math.max(a[1], b[1]);
            maxZ = Math.max(a[2], b[2]);
            this.mode = mode;
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** Arm one further box. This gesture never replaces either corner of the current box. */
    public void beginNext(String mode) throws IOException {
        if (!validMode(mode)) throw new IOException("区域组合模式无效");
        if (regions.size() >= 128) throw new IOException("区域组合过多，请撤销或完成");
        remember();
        first = second = null;
        current = -1;
        pointMode = mode;
        more = true;
    }

    public void point(int index, int xx, int yy, int zz, String mode) throws IOException {
        if (!validMode(mode)) throw new IOException("区域组合模式无效");
        if (index != 1 && index != 2) throw new IOException("区域角点无效");
        // Explicit geometry modes remain available to the protocol; world input owns its persistent mode.
        boolean next = first != null && second != null && !"replace".equals(mode);
        if ((next || current < 0) && regions.size() >= 128) throw new IOException("区域组合过多，请撤销或完成");
        remember();
        if (next) {
            first = second = null;
            current = -1;
            more = true;
        }
        if (first == null && second == null && (!more || !"replace".equals(mode))) pointMode = mode;
        if (index == 1) first = new int[] { xx, yy, zz };
        else second = new int[] { xx, yy, zz };
        if (first == null || second == null) return;
        Region region = new Region(first, second, pointMode);
        if (current >= 0) regions.set(current, region);
        else {
            if ("replace".equals(pointMode)) regions.clear();
            regions.add(region);
            current = regions.size() - 1;
        }
        more = false;
    }

    /** Undo one input, including a single corner, a box movement, deletion or the more-box gesture. */
    public void undoRegion() {
        if (regionUndo.isEmpty()) return;
        regionRedo.add(new SelectionState());
        SelectionState previous = regionUndo.remove(regionUndo.size() - 1);
        restore(previous);
    }

    public void redoRegion() {
        if (regionRedo.isEmpty()) return;
        regionUndo.add(new SelectionState());
        restore(regionRedo.remove(regionRedo.size() - 1));
    }

    private void restore(SelectionState previous) {
        first = previous.a;
        second = previous.b;
        regions.clear();
        regions.addAll(previous.boxes);
        pointMode = previous.mode;
        current = previous.selected;
        more = previous.adding;
        inputMode = previous.input;
        polygonMode = previous.polyMode;
        polygon.clear();
        polygon.addAll(previous.vertices);
        continuous = previous.drawing;
        closed = previous.loop;
        heightChosen = previous.high;
        polygonHeight = previous.height;
        lastWorldCorner = previous.corner;
    }

    /** Persistent world input mode; a first complete box is required before add/subtract. */
    public void toggleMode(String mode) {
        if (regions.isEmpty() || !validMode(mode) || "replace".equals(mode) || continuous) return;
        remember();
        inputMode = mode.equals(inputMode) ? "replace" : mode;
        if (more) select(regions.size() - 1);
    }

    public void worldPoint(int index, int x, int y, int z) throws IOException {
        if (index == 0) index = first == null ? 1 : second == null ? 2 : lastWorldCorner == 1 ? 2 : 1;
        point(index, x, y, z, inputMode);
        lastWorldCorner = index;
    }

    public boolean continuousActive() {
        return continuous;
    }

    /** C during line entry resets the line, while keeping the committed selection intact. */
    public void beginContinuous(boolean subtract) {
        if (continuous && closed) return;
        if (subtract && regions.isEmpty()) return;
        remember();
        polygonMode = continuous ? polygonMode : regions.isEmpty() ? "replace" : subtract ? "subtract" : "add";
        continuous = true;
        closed = heightChosen = false;
        polygon.clear();
    }

    public void continuousPoint(int x, int y, int z) throws IOException {
        if (!continuous) return;
        if (closed) {
            remember();
            polygonHeight = y;
            heightChosen = true;
            return;
        }
        if (!polygon.isEmpty() && x == polygon.get(0)[0] && z == polygon.get(0)[2]) {
            if (polygon.size() < 3) throw new IOException("请至少选择三个不同的连续框选点");
            long area = 0;
            for (int i = 0; i < polygon.size(); i++) {
                int[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
                area += (long) a[0] * b[2] - (long) b[0] * a[2];
            }
            if (area == 0) throw new IOException("连续框选轮廓没有面积");
            if (polygonBoxes(polygon.get(0)[1]).isEmpty()) throw new IOException("连续框选轮廓没有面积");
            remember();
            closed = true;
            return;
        }
        if (polygon.size() >= 128) throw new IOException("连续框选点过多");
        for (int[] vertex : polygon) if (vertex[0] == x && vertex[2] == z) throw new IOException("请点击首点闭合，或选择新的轮廓点");
        remember();
        polygon.add(new int[] { x, polygon.isEmpty() ? y : polygon.get(0)[1], z });
    }

    public void confirmContinuous() throws IOException {
        if (!continuous || !closed || !heightChosen) throw new IOException("请先闭合连续框选轮廓，再点击方块选择高度");
        commitContinuous(polygonHeight);
    }

    /** The same free-selection key closes the pending footprint and confirms its current preview height. */
    public void finishContinuous(int previewHeight) throws IOException {
        finishContinuous(previewHeight, null);
    }

    public void finishContinuous(int[] cursor) throws IOException {
        SelectionState before = new SelectionState();
        if (!closed && cursor != null) {
            boolean existing = false;
            for (int[] vertex : polygon) if (vertex[0] == cursor[0] && vertex[2] == cursor[2]) existing = true;
            if (!existing) {
                if (polygon.size() >= 128) throw new IOException("连续框选点过多");
                polygon.add(new int[] { cursor[0], polygon.isEmpty() ? cursor[1] : polygon.get(0)[1], cursor[2] });
            }
        }
        try {
            finishContinuous(cursor != null ? cursor[1] : polygon.isEmpty() ? 0 : polygon.get(0)[1], before);
        } catch (IOException invalid) {
            restore(before);
            throw invalid;
        }
    }

    private void finishContinuous(int previewHeight, SelectionState before) throws IOException {
        if (!continuous || polygon.size() < 3) throw new IOException("请至少选择三个不同的连续框选点");
        long area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            int[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            area += (long) a[0] * b[2] - (long) b[0] * a[2];
        }
        if (area == 0) throw new IOException("连续框选轮廓没有面积");
        commitContinuous(heightChosen ? polygonHeight : previewHeight, before);
    }

    private void commitContinuous(int height) throws IOException {
        commitContinuous(height, null);
    }

    private void commitContinuous(int height, SelectionState before) throws IOException {
        List<Region> boxes = polygonBoxes(height);
        if (boxes.isEmpty()) throw new IOException("连续框选轮廓没有面积");
        int retained = "replace".equals(polygonMode) ? 0 : regions.size();
        if (retained + boxes.size() > 128) throw new IOException("区域组合过多，请先合并或简化轮廓");
        if (before == null) remember();
        else remember(before);
        if (retained == 0) regions.clear();
        regions.addAll(boxes);
        continuous = closed = heightChosen = false;
        polygon.clear();
        select(regions.size() - 1);
    }

    /** Inclusive block-center polygon raster, merged into bounded row prisms for the existing wire protocol. */
    private List<Region> polygonBoxes(int height) throws IOException {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = minX, maxZ = maxX;
        for (int[] v : polygon) {
            minX = Math.min(minX, v[0]);
            maxX = Math.max(maxX, v[0]);
            minZ = Math.min(minZ, v[2]);
            maxZ = Math.max(maxZ, v[2]);
        }
        if (((long) maxX - minX + 1) * ((long) maxZ - minZ + 1) > 1_048_576L) throw new IOException("连续框选底面过大，请缩小轮廓");
        List<Region> result = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            int start = 0;
            boolean run = false;
            for (int x = minX; x <= maxX; x++) {
                boolean inside = polygonContains(x, z);
                if (inside && !run) {
                    start = x;
                    run = true;
                }
                if (run && (!inside || x == maxX)) {
                    int end = inside && x == maxX ? x : x - 1;
                    Region previous = null;
                    for (Region box : result)
                        if (box.minX == start && box.maxX == end && box.maxZ == z - 1) previous = box;
                    Region row = new Region(
                        new int[] { start, polygon.get(0)[1], previous == null ? z : previous.minZ },
                        new int[] { end, height, z },
                        polygonMode);
                    if (previous != null) result.set(result.indexOf(previous), row);
                    else result.add(row);
                    if (result.size() > 128) throw new IOException("连续框选轮廓过于复杂，请简化轮廓");
                    run = false;
                }
            }
        }
        return result;
    }

    private boolean polygonContains(int x, int z) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            int[] a = polygon.get(j), b = polygon.get(i);
            long cross = (long) (x - a[0]) * (b[2] - a[2]) - (long) (z - a[2]) * (b[0] - a[0]);
            if (cross == 0 && x >= Math.min(a[0], b[0])
                && x <= Math.max(a[0], b[0])
                && z >= Math.min(a[2], b[2])
                && z <= Math.max(a[2], b[2])) return true;
            if ((a[2] > z) != (b[2] > z) && x < a[0] + (double) (b[0] - a[0]) * (z - a[2]) / (b[2] - a[2]))
                inside = !inside;
        }
        return inside;
    }

    /** Resolve ordered masks into disjoint positive boxes without enumerating world cells. */
    public void mergeRegions() throws IOException {
        if (continuous || regions.size() < 2) return;
        List<Region> merged = new ArrayList<>();
        int work = 0;
        for (Region box : regions) {
            if ("subtract".equals(box.mode)) {
                List<Region> next = new ArrayList<>();
                for (Region existing : merged) {
                    if (++work > 131072) throw new IOException("合并计算过于复杂，请减少选区");
                    split(existing, box, next);
                }
                merged = next;
            } else {
                List<Region> additions = new ArrayList<>();
                additions.add(box);
                for (Region existing : merged) {
                    List<Region> next = new ArrayList<>();
                    for (Region addition : additions) {
                        if (++work > 131072) throw new IOException("合并计算过于复杂，请减少选区");
                        split(addition, existing, next);
                    }
                    additions = next;
                    if (additions.size() > 4096) throw new IOException("合并结果过于复杂，请减少选区");
                }
                merged.addAll(additions);
            }
            if (merged.size() > 4096) throw new IOException("合并结果过于复杂，请减少选区");
        }
        // Adjacent boxes sharing a complete face can become one box as well.
        boolean joined;
        do {
            joined = false;
            outer: for (int i = 0; i < merged.size(); i++) for (int j = i + 1; j < merged.size(); j++) {
                if (++work > 131072) throw new IOException("合并计算过于复杂，请减少选区");
                Region a = merged.get(i), b = merged.get(j);
                boolean x = a.minY == b.minY && a.maxY == b.maxY
                    && a.minZ == b.minZ
                    && a.maxZ == b.maxZ
                    && ((long) a.maxX + 1 == b.minX || (long) b.maxX + 1 == a.minX);
                boolean y = a.minX == b.minX && a.maxX == b.maxX
                    && a.minZ == b.minZ
                    && a.maxZ == b.maxZ
                    && ((long) a.maxY + 1 == b.minY || (long) b.maxY + 1 == a.minY);
                boolean z = a.minX == b.minX && a.maxX == b.maxX
                    && a.minY == b.minY
                    && a.maxY == b.maxY
                    && ((long) a.maxZ + 1 == b.minZ || (long) b.maxZ + 1 == a.minZ);
                if (x || y || z) {
                    merged.set(
                        i,
                        new Region(
                            new int[] { Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ) },
                            new int[] { Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ) },
                            "add"));
                    merged.remove(j);
                    joined = true;
                    break outer;
                }
            }
        } while (joined);
        if (merged.size() > 128) throw new IOException("合并结果超过128个区域，请减少选区");
        remember();
        regions.clear();
        for (Region box : merged) regions.add(new Region(box.first, box.second, "add"));
        inputMode = "replace";
        select(regions.size() - 1);
    }

    private static void split(Region a, Region b, List<Region> out) {
        int x0 = Math.max(a.minX, b.minX), x1 = Math.min(a.maxX, b.maxX);
        int y0 = Math.max(a.minY, b.minY), y1 = Math.min(a.maxY, b.maxY);
        int z0 = Math.max(a.minZ, b.minZ), z1 = Math.min(a.maxZ, b.maxZ);
        if (x0 > x1 || y0 > y1 || z0 > z1) {
            out.add(a);
            return;
        }
        if (a.minX < x0)
            out.add(new Region(new int[] { a.minX, a.minY, a.minZ }, new int[] { x0 - 1, a.maxY, a.maxZ }, "add"));
        if (x1 < a.maxX)
            out.add(new Region(new int[] { x1 + 1, a.minY, a.minZ }, new int[] { a.maxX, a.maxY, a.maxZ }, "add"));
        if (a.minY < y0) out.add(new Region(new int[] { x0, a.minY, a.minZ }, new int[] { x1, y0 - 1, a.maxZ }, "add"));
        if (y1 < a.maxY) out.add(new Region(new int[] { x0, y1 + 1, a.minZ }, new int[] { x1, a.maxY, a.maxZ }, "add"));
        if (a.minZ < z0) out.add(new Region(new int[] { x0, y0, a.minZ }, new int[] { x1, y1, z0 - 1 }, "add"));
        if (z1 < a.maxZ) out.add(new Region(new int[] { x0, y0, z1 + 1 }, new int[] { x1, y1, a.maxZ }, "add"));
    }

    public int currentRegion() {
        return current;
    }

    public boolean addingRegion() {
        return more;
    }

    public boolean complete() {
        return first != null && second != null && current >= 0;
    }

    public void cycle(int delta) {
        if (regions.isEmpty()) return;
        int selected = current < 0 ? regions.size() - 1 : current;
        selected = Math.floorMod(selected + delta, regions.size());
        if (selected == current) return;
        remember();
        select(selected);
    }

    private void select(int selected) {
        current = selected;
        if (current < 0) {
            first = second = null;
            pointMode = "replace";
        } else {
            Region region = regions.get(current);
            first = region.first.clone();
            second = region.second.clone();
            pointMode = region.mode;
        }
        more = false;
    }

    public void deleteCurrent() {
        if (current < 0) return;
        remember();
        regions.remove(current);
        select(regions.isEmpty() ? -1 : Math.min(current, regions.size() - 1));
    }

    public void move(int dx, int dy, int dz, int height) {
        if (!complete()) return;
        int[] a = translated(first, dx, dy, dz), b = translated(second, dx, dy, dz);
        if (!HologramWorldFlow.worldPosition(a[0], a[1], a[2], height)
            || !HologramWorldFlow.worldPosition(b[0], b[1], b[2], height)) return;
        remember();
        first = a;
        second = b;
        regions.set(current, new Region(a, b, pointMode));
    }

    private static int[] translated(int[] point, int dx, int dy, int dz) {
        return new int[] { point[0] + dx, point[1] + dy, point[2] + dz };
    }

    /** Ordered constructive mask; kept independent of blueprint cleaning and world inspection. */
    public boolean contains(int x, int y, int z) {
        boolean included = false;
        for (Region region : regions) if (region.contains(x, y, z)) included = !"subtract".equals(region.mode);
        return included;
    }

    public NBTTagList regionSnapshot() {
        NBTTagList rows = new NBTTagList();
        for (Region region : regions) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("minX", region.minX);
            row.setInteger("minY", region.minY);
            row.setInteger("minZ", region.minZ);
            row.setInteger("maxX", region.maxX);
            row.setInteger("maxY", region.maxY);
            row.setInteger("maxZ", region.maxZ);
            row.setString("mode", region.mode);
            row.setIntArray(
                "corners",
                new int[] { region.first[0], region.first[1], region.first[2], region.second[0], region.second[1],
                    region.second[2] });
            rows.appendTag(row);
        }
        return rows;
    }

    public void point(int index, int xx, int yy, int zz) {
        try {
            point(index, xx, yy, zz, "replace");
        } catch (IOException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
    }

    public int[] bounds() throws IOException {
        if (regions.isEmpty()) {
            if (first == null || second == null) throw new IOException("请先选择两个区域角点");
            regions.add(new Region(first, second, "replace"));
        }
        int[] bounds = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE };
        for (Region region : regions) {
            if ("subtract".equals(region.mode)) continue;
            bounds[0] = Math.min(bounds[0], region.minX);
            bounds[1] = Math.min(bounds[1], region.minY);
            bounds[2] = Math.min(bounds[2], region.minZ);
            bounds[3] = Math.max(bounds[3], region.maxX);
            bounds[4] = Math.max(bounds[4], region.maxY);
            bounds[5] = Math.max(bounds[5], region.maxZ);
        }
        if (bounds[0] == Integer.MAX_VALUE) throw new IOException("减选后没有可导入的正选区域");
        return bounds;
    }

    public int[] points() {
        if (first == null) return second == null ? new int[0] : second.clone();
        if (second == null) return first.clone();
        return new int[] { first[0], first[1], first[2], second[0], second[1], second[2] };
    }

    public NBTTagCompound snapshot() {
        NBTTagCompound state = new NBTTagCompound();
        state.setIntArray("areaPoints", points());
        state.setTag("areaRegions", regionSnapshot());
        state.setString("areaPointMode", pointMode);
        state.setInteger("areaCurrentRegion", current);
        state.setBoolean("areaMore", more);
        state.setString("areaInputMode", inputMode);
        state.setBoolean("areaContinuous", continuous);
        state.setBoolean("areaPolygonClosed", closed);
        state.setBoolean("areaPolygonHeightChosen", heightChosen);
        state.setInteger("areaPolygonHeight", polygonHeight);
        state.setString("areaPolygonMode", polygonMode);
        int[] vertices = new int[polygon.size() * 3];
        for (int i = 0; i < polygon.size(); i++) System.arraycopy(polygon.get(i), 0, vertices, i * 3, 3);
        state.setIntArray("areaPolygon", vertices);
        state.setInteger(
            "areaPendingCorner",
            first != null && second == null ? 1 : first == null && second != null ? 2 : 0);
        state.setBoolean("areaUndoAvailable", !regionUndo.isEmpty() || first != null || second != null);
        return state;
    }

    /** Decode only the existing bounded selection protocol; world limits belong to final import admission. */
    public void readSnapshot(NBTTagCompound state) throws IOException {
        int[] points = state.getIntArray("areaPoints");
        int pending = state.getInteger("areaPendingCorner");
        if (points.length != 0 && points.length != 3 && points.length != 6
            || points.length == 3 && pending != 1 && pending != 2) throw new IOException("区域角点数据无效");
        String mode = state.getString("areaPointMode");
        if (mode.isEmpty()) mode = "replace";
        if (!validMode(mode)) throw new IOException("区域组合模式无效");
        NBTTagList rows = state.getTagList("areaRegions", 10);
        if (rows.tagCount() > 128) throw new IOException("区域组合过多，请撤销或完成");
        List<Region> decoded = new ArrayList<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (!validMode(row.getString("mode"))) throw new IOException("区域组合模式无效");
            for (String key : new String[] { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" })
                if (!row.hasKey(key, 3)) throw new IOException("区域角点数据不完整");
            int[] corners = row.getIntArray("corners");
            if (corners.length != 0 && corners.length != 6) throw new IOException("区域角点数据无效");
            decoded.add(
                corners.length == 6
                    ? new Region(
                        new int[] { corners[0], corners[1], corners[2] },
                        new int[] { corners[3], corners[4], corners[5] },
                        row.getString("mode"))
                    : new Region(
                        new int[] { row.getInteger("minX"), row.getInteger("minY"), row.getInteger("minZ") },
                        new int[] { row.getInteger("maxX"), row.getInteger("maxY"), row.getInteger("maxZ") },
                        row.getString("mode")));
        }
        int[] decodedFirst = points.length == 6 || points.length == 3 && pending == 1
            ? new int[] { points[0], points[1], points[2] }
            : null;
        int[] decodedSecond = points.length == 6 ? new int[] { points[3], points[4], points[5] }
            : points.length == 3 && pending == 2 ? new int[] { points[0], points[1], points[2] } : null;
        int decodedCurrent = state.hasKey("areaCurrentRegion", 3) ? state.getInteger("areaCurrentRegion")
            : decodedFirst != null && decodedSecond != null ? decoded.size() - 1 : -1;
        if (decodedCurrent < -1 || decodedCurrent >= decoded.size()
            || decodedCurrent >= 0 && (decodedFirst == null || decodedSecond == null))
            throw new IOException("当前选框数据无效");
        first = decodedFirst;
        second = decodedSecond;
        regions.clear();
        regions.addAll(decoded);
        regionUndo.clear();
        regionRedo.clear();
        polygon.clear();
        continuous = closed = heightChosen = false;
        inputMode = "replace";
        pointMode = mode;
        more = state.getBoolean("areaMore");
        current = decodedCurrent;
        if (current >= 0) regions.set(current, new Region(first, second, pointMode));
    }

    private static boolean validMode(String mode) {
        return "replace".equals(mode) || "add".equals(mode) || "subtract".equals(mode);
    }

}
