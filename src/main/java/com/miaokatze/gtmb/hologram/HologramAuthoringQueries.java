package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Detached source and validation projections consumed by authoring and snapshots. */
final class HologramAuthoringQueries {

    private HologramAuthoringQueries() {}

    /** Original losses and unresolved blockers persist; explicit validated mutations retire exact resolved problems. */
    static NBTTagList aeCurrentIssues(HologramSession s) throws java.io.IOException {
        Map<String, HologramAePolicy.Issue> issues = new LinkedHashMap<>();
        Map<String, HologramAeBlueprint.Analysis> analyses = new LinkedHashMap<>();
        if (HologramDeidentification.level(s.virtualPreset) == 0 && s.capture != null)
            for (HologramCapture.Cell cell : s.capture.cells)
                if (!cell.excluded && HologramCellPolicy.foreignBlueprintOwner(s, cell)) {
                    HologramAePolicy.Issue issue = new HologramAePolicy.Issue(
                        "identity.foreign_owner",
                        "blocker",
                        "owner",
                        "Level 0 contains another player's identity; choose level 1 before placement.",
                        cell.x - s.x,
                        cell.y - s.y,
                        cell.z - s.z);
                    issues.put(HologramValues.position(cell) + ":owner", issue);
                }
        if (s.virtualPreset.aeIssues != null) for (HologramAePolicy.Issue issue : s.virtualPreset.aeIssues) {
            issue.validate();
            issues.put(issue.x + "," + issue.y + "," + issue.z + ":" + issue.path + ":" + issue.code, issue);
        }
        if (s.capture != null) for (HologramCapture.Cell cell : s.capture.cells) {
            if (!HologramAePolicy.handles(cell.block, cell.tileNbt)) continue;
            HologramAeBlueprint.Analysis analysis = HologramAeBlueprint
                .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
            analyses.put(HologramValues.position(cell), analysis);
            for (HologramAeBlueprint.Issue source : analysis.issues) {
                HologramAePolicy.Issue issue = new HologramAePolicy.Issue(
                    source.code,
                    HologramAePolicy.severity(source.severity),
                    source.path,
                    HologramAePolicy.fallback(source.message),
                    cell.x - s.x,
                    cell.y - s.y,
                    cell.z - s.z);
                issues.put(issue.x + "," + issue.y + "," + issue.z + ":" + issue.path + ":" + issue.code, issue);
            }
            if (issues.size() > HologramAePolicy.MAX_ISSUES) throw new java.io.IOException("AE complete report budget");
        }
        NBTTagList rows = HologramAePolicy.issues(new ArrayList<>(issues.values()), s.x, s.y, s.z);
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            String key = row.getInteger("x") + "," + row.getInteger("y") + "," + row.getInteger("z");
            HologramAeBlueprint.Analysis analysis = analyses.get(key);
            HologramCapture.Cell cell = s.capture == null ? null : s.capture.positions.get(key);
            if ("identity.foreign_owner".equals(row.getString("code"))) {
                row.setString("resolution", "deidentifyBlueprint");
                continue;
            }
            if (analysis == null || cell == null) continue;
            String structure = cell.belongsTo(s.blueprintActiveStructure) ? s.blueprintActiveStructure
                : cell.memberships()
                    .isEmpty() ? ""
                        : cell.memberships()
                            .get(0);
            HologramAePolicy.projectResolution(row, analysis, structure);
        }
        return rows;
    }

    static HologramPresets.Preset processingSource(HologramSession s) throws java.io.IOException {
        if (s.capture == null || s.capture.incomplete || s.capturePending)
            throw new java.io.IOException("请先完整载入结构或区域，再进行蓝图处理");
        if (s.processingSource == null) {
            s.processingSource = HologramBlueprintProjection
                .blueprint(s, s.virtualPreset == null ? "未命名蓝图" : s.virtualPreset.name);
            s.processingPolicy = HologramBlueprintProcessing.policy(s.processingSource);
        }
        return s.processingSource;
    }

    static List<String> selectedNbtKeys(NBTTagList input) throws java.io.IOException {
        if (input.tagCount() > 512) throw new java.io.IOException("NBT 条目过多");
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (int index = 0; index < input.tagCount(); index++) {
            String key = input.getStringTagAt(index);
            if (key.isEmpty() || key.length() > 128) throw new java.io.IOException("NBT 条目名称无效");
            keys.add(key);
        }
        return new ArrayList<>(keys);
    }

}
