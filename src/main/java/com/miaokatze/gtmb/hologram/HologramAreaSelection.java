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
    private int current = -1;
    private boolean more;

    private final class SelectionState {

        final int[] a = first == null ? null : first.clone(), b = second == null ? null : second.clone();
        final List<Region> boxes = new ArrayList<>(regions);
        final String mode = pointMode;
        final int selected = current;
        final boolean adding = more;
    }

    private void remember() {
        if (regionUndo.size() == 128) regionUndo.remove(0);
        regionUndo.add(new SelectionState());
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
        // Explicit Ctrl combinations retain their add/subtract shortcut; ordinary clicks edit the current box.
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
        SelectionState previous = regionUndo.remove(regionUndo.size() - 1);
        first = previous.a;
        second = previous.b;
        regions.clear();
        regions.addAll(previous.boxes);
        pointMode = previous.mode;
        current = previous.selected;
        more = previous.adding;
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
        pointMode = mode;
        more = state.getBoolean("areaMore");
        current = decodedCurrent;
        if (current >= 0) regions.set(current, new Region(first, second, pointMode));
    }

    private static boolean validMode(String mode) {
        return "replace".equals(mode) || "add".equals(mode) || "subtract".equals(mode);
    }

}
