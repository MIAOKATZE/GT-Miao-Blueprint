package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses only current server issue coordinates and structure ids. Never reads old source coordinates. */
final class HologramAeWarningMarkers {

    private HologramAeWarningMarkers() {}

    static List<HologramAnnotationMarkers.Marker> collect(HologramState state) {
        List<HologramAnnotationMarkers.Marker> result = new ArrayList<>();
        HologramAeReviewModel report = HologramAeReviewModel.inspection(state);
        if (report.issues.isEmpty()) return result;
        result.add(
            new HologramAnnotationMarkers.Marker(
                "ae:report",
                "AE 综合提示 · " + report.issues.size() + " 项",
                summary(report.issues) + "\n点击固定后可打开完整分页详情。",
                null,
                hasBlocker(report.issues)));
        Map<String, List<HologramAeReviewModel.Issue>> grouped = new LinkedHashMap<>();
        for (HologramAeReviewModel.Issue issue : report.issues) {
            if (!issue.location) continue;
            String key = issue.structureId.isEmpty() ? "cell:" + issue.x + ":" + issue.y + ":" + issue.z
                : "structure:" + issue.structureId;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(issue);
        }
        Map<String, int[]> currentBounds = new LinkedHashMap<>();
        for (HologramState.Cell cell : state.cells) {
            include(currentBounds, "cell:" + cell.x + ":" + cell.y + ":" + cell.z, cell);
            if (!cell.structureId.isEmpty()) include(currentBounds, "structure:" + cell.structureId, cell);
            for (String membership : cell.structureIds)
                if (!membership.isEmpty()) include(currentBounds, "structure:" + membership, cell);
        }
        for (Map.Entry<String, List<HologramAeReviewModel.Issue>> entry : grouped.entrySet()) {
            HologramAeReviewModel.Issue first = entry.getValue()
                .get(0);
            int[] bounds = currentBounds.get(entry.getKey());
            // Unmatched/origin-only issues remain reachable through the comprehensive report icon.
            if (bounds == null) continue;
            String title = first.structureId.isEmpty() ? "AE 方块提示 · " + first.x + "," + first.y + "," + first.z
                : "AE 结构聚合提示 · " + first.structureId;
            result.add(
                new HologramAnnotationMarkers.Marker(
                    "ae:" + entry.getKey(),
                    title,
                    summary(entry.getValue()) + "\n完整路径与处理入口见 AE 详情。",
                    bounds,
                    hasBlocker(entry.getValue())));
        }
        return result;
    }

    private static void include(Map<String, int[]> bounds, String key, HologramState.Cell cell) {
        int[] b = bounds.get(key);
        if (b == null) bounds.put(key, new int[] { cell.dx, cell.dy, cell.dz, cell.dx, cell.dy, cell.dz });
        else {
            b[0] = Math.min(b[0], cell.dx);
            b[3] = Math.max(b[3], cell.dx);
            b[1] = Math.min(b[1], cell.dy);
            b[4] = Math.max(b[4], cell.dy);
            b[2] = Math.min(b[2], cell.dz);
            b[5] = Math.max(b[5], cell.dz);
        }
    }

    private static boolean hasBlocker(List<HologramAeReviewModel.Issue> issues) {
        for (HologramAeReviewModel.Issue issue : issues) if ("blocker".equals(issue.severity)) return true;
        return false;
    }

    private static String summary(List<HologramAeReviewModel.Issue> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (HologramAeReviewModel.Issue row : rows) {
            String title = ("blocker".equals(row.severity) ? "阻塞 · "
                : "warning".equals(row.severity) ? "注意 · " : "说明 · ") + row.title();
            long previous = counts.containsKey(title) ? counts.get(title) : 0;
            counts.put(title, row.count > Long.MAX_VALUE - previous ? Long.MAX_VALUE : previous + row.count);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Long> row : counts.entrySet()) {
            if (lines.size() >= 4) break;
            lines.add(row.getKey() + " × " + row.getValue());
        }
        if (counts.size() > 4) lines.add("另有 " + (counts.size() - 4) + " 类 · 完整报告可分页查看。");
        return String.join("\n", lines);
    }
}
