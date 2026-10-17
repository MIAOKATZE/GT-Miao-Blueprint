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
    final List<List<Region>> regionUndo = new ArrayList<>();

    static final class Region {

        final int minX, minY, minZ, maxX, maxY, maxZ;
        final String mode;

        Region(int[] a, int[] b, String mode) {
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

    public void point(int index, int xx, int yy, int zz, String mode) throws IOException {
        if (!validMode(mode)) throw new IOException("区域组合模式无效");
        if (index != 1 && index != 2) throw new IOException("区域角点无效");
        boolean pending = (first == null) != (second == null);
        boolean completes = pending && (index == 1 ? second != null : first != null);
        if (completes && (regions.size() >= 128 || regionUndo.size() >= 128)) throw new IOException("区域组合过多，请撤销或完成");
        // Either mouse button may start the next pair. Re-marking that same corner keeps its original mode.
        if (!pending) {
            first = null;
            second = null;
            pointMode = mode;
        }
        if (index == 1) first = new int[] { xx, yy, zz };
        else second = new int[] { xx, yy, zz };
        if (completes) {
            regionUndo.add(new ArrayList<>(regions));
            if ("replace".equals(pointMode)) regions.clear();
            regions.add(new Region(first, second, pointMode));
        }
    }

    public void undoRegion() {
        if (regionUndo.isEmpty()) {
            first = null;
            second = null;
            return;
        }
        regions.clear();
        regions.addAll(regionUndo.remove(regionUndo.size() - 1));
        first = null;
        second = null;
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
            rows.appendTag(row);
        }
        return rows;
    }

    public void point(int index, int xx, int yy, int zz) {
        if (index == 1) first = new int[] { xx, yy, zz };
        if (index == 2) second = new int[] { xx, yy, zz };
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
            decoded.add(
                new Region(
                    new int[] { row.getInteger("minX"), row.getInteger("minY"), row.getInteger("minZ") },
                    new int[] { row.getInteger("maxX"), row.getInteger("maxY"), row.getInteger("maxZ") },
                    row.getString("mode")));
        }
        first = points.length == 6 || points.length == 3 && pending == 1 ? new int[] { points[0], points[1], points[2] }
            : null;
        second = points.length == 6 ? new int[] { points[3], points[4], points[5] }
            : points.length == 3 && pending == 2 ? new int[] { points[0], points[1], points[2] } : null;
        regions.clear();
        regions.addAll(decoded);
        regionUndo.clear();
        pointMode = mode;
    }

    private static boolean validMode(String mode) {
        return "replace".equals(mode) || "add".equals(mode) || "subtract".equals(mode);
    }

}
