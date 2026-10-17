package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Annotation content is grouped before projection: one structure produces one marker. */
final class HologramAnnotationMarkers {

    private HologramAnnotationMarkers() {}

    static List<Marker> collect(HologramState state) {
        List<Marker> result = new ArrayList<>();
        String global = state.data.getString("blueprintPrompt");
        if (!global.isEmpty()) result.add(new Marker("blueprint", "蓝图提示", global, null));
        Map<String, Set<String>> structurePrompts = new LinkedHashMap<>();
        Map<String, List<Integer>> structureCells = new LinkedHashMap<>();
        Map<String, Set<String>> networkPrompts = new LinkedHashMap<>();
        Map<String, List<Integer>> networkCells = new LinkedHashMap<>();
        String activeStructure = state.data.getString("activeStructure");
        for (HologramState.Cell cell : state.cells) {
            if (state.data.getBoolean("virtual") && cell.quantumNetworkHint()) {
                // Bounds follow this snapshot's transformed cells, restricted to the current editing structure.
                if (!activeStructure.isEmpty() && !cell.belongsToStructure(activeStructure)) continue;
                Set<String> prompts = networkPrompts.computeIfAbsent(cell.networkGroup, key -> new LinkedHashSet<>());
                String hint = cell.specialHint.getString("hint");
                if (!hint.isEmpty() && !hint.equals("量子网络组 " + cell.networkGroup.substring(1))) prompts.add(hint);
                if (!cell.prompt.isEmpty()) prompts.add(cell.prompt);
                networkCells.computeIfAbsent(cell.networkGroup, key -> new ArrayList<>())
                    .add(cell.index);
                continue;
            }
            if (cell.prompt.isEmpty()) continue;
            if (cell.structureId.isEmpty()) {
                result.add(
                    new Marker(
                        "cell:" + cell.index,
                        "方块 " + cell.dx + "," + cell.dy + "," + cell.dz,
                        cell.prompt,
                        new int[] { cell.dx, cell.dy, cell.dz, cell.dx, cell.dy, cell.dz }));
            } else {
                structurePrompts.computeIfAbsent(cell.structureId, key -> new LinkedHashSet<>())
                    .add(cell.prompt);
                structureCells.computeIfAbsent(cell.structureId, key -> new ArrayList<>())
                    .add(cell.index);
            }
        }
        for (Map.Entry<String, List<Integer>> group : networkCells.entrySet()) {
            int[] indices = group.getValue()
                .stream()
                .mapToInt(Integer::intValue)
                .toArray();
            String prompt = String.join("\n", networkPrompts.get(group.getKey()));
            result.add(
                new Marker(
                    "quantum:" + group.getKey(),
                    "量子网络组 " + group.getKey()
                        .substring(1),
                    prompt.isEmpty() ? "同组设备共享本次蓝图放置的网络绑定。" : prompt,
                    HologramAnnotations.bounds(state, indices)));
        }
        NBTTagList structures = state.data.getTagList("structures", 10);
        for (Map.Entry<String, Set<String>> row : structurePrompts.entrySet()) {
            String name = row.getKey();
            for (int i = 0; i < structures.tagCount(); i++) {
                NBTTagCompound structure = structures.getCompoundTagAt(i);
                if (row.getKey()
                    .equals(structure.getString("id"))) name = structure.getString("name");
            }
            int[] indices = structureCells.get(row.getKey())
                .stream()
                .mapToInt(Integer::intValue)
                .toArray();
            result.add(
                new Marker(
                    "structure:" + row.getKey(),
                    "结构提示 · " + name,
                    String.join("\n", row.getValue()),
                    HologramAnnotations.bounds(state, indices)));
        }
        NBTTagList areas = state.data.getTagList("hintAreas", 10);
        for (int i = 0; i < areas.tagCount(); i++) {
            NBTTagCompound area = areas.getCompoundTagAt(i);
            int[] bounds = area.getIntArray("bounds");
            if (bounds.length == 6 && !area.getString("prompt")
                .isEmpty()) result.add(new Marker("area:" + i, "提示区 " + (i + 1), area.getString("prompt"), bounds));
        }
        result.addAll(0, HologramAeWarningMarkers.collect(state));
        addResourceBlockerMarker(state, result);
        return result;
    }

    private static void addResourceBlockerMarker(HologramState state, List<Marker> markers) {
        NBTTagCompound report = state.data.hasKey("resourceBlockers", 9) ? state.data
            : state.data.getCompoundTag("processing");
        NBTTagList blockers = report.getTagList("resourceBlockers", 10);
        if (blockers.tagCount() == 0) return;
        boolean budgetExceeded = report.getBoolean("resourceReviewTruncated");
        List<String> lines = new ArrayList<>();
        if (budgetExceeded)
            lines.add("报告超过 8192 项预算（共 " + report.getLong("resourceReviewTotal") + " 项），尚未完整列出。请在蓝图处理第五步明确移除相关全局根条目。");
        for (int i = 0; i < Math.min(4, blockers.tagCount()); i++) {
            NBTTagCompound row = blockers.getCompoundTagAt(i);
            lines.add(
                "原图格 " + row.getInteger("x")
                    + ","
                    + row.getInteger("y")
                    + ","
                    + row.getInteger("z")
                    + " · "
                    + row.getString("path")
                    + "："
                    + row.getString("message"));
        }
        if (blockers.tagCount() > 4) lines.add("另有 " + (blockers.tagCount() - 4) + " 项；完整坐标与路径见蓝图处理第五步。");
        lines.add("资源尚不能安全核算；在蓝图流程内选择精确路径处理后再保存。");
        // Raw preset coordinates are not projected onto transformed preview cells.
        markers.add(
            0,
            new Marker(
                "resource:report",
                "资源阻塞 · " + blockers.tagCount() + " 项" + (budgetExceeded ? "（未完整）" : ""),
                String.join("\n", lines),
                null,
                true));
    }

    /** Keep the complete icon/hit target inside the preview even after repeated collision shifts. */
    static boolean place(Marker marker, List<Marker> shown, int bottom) {
        if (marker.y - 7 < HologramScreenLayout.VIEWPORT_Y) return false;
        while (marker.y + 7 <= bottom) {
            boolean collision = false;
            for (Marker previous : shown) {
                if (Math.abs(marker.x - previous.x) < 14 && Math.abs(marker.y - previous.y) < 14) {
                    marker.y = previous.y + 15;
                    collision = true;
                    break;
                }
            }
            if (!collision) return true;
        }
        return false;
    }

    static final class Marker {

        final String key, title, prompt;
        final boolean blocker;
        final int[] bounds;
        int x, y;

        Marker(String key, String title, String prompt, int[] bounds) {
            this(key, title, prompt, bounds, false);
        }

        Marker(String key, String title, String prompt, int[] bounds, boolean blocker) {
            this.key = key;
            this.title = title;
            this.prompt = prompt;
            this.bounds = bounds;
            this.blocker = blocker;
        }

        boolean contains(int mx, int my) {
            return mx >= x - 6 && mx < x + 7 && my >= y - 6 && my < y + 7;
        }
    }
}
