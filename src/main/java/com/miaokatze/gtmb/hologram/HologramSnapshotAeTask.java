package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Same complete AE report as AuthoringQueries, with an owner-thread checkpoint at every cell/issue. */
final class HologramSnapshotAeTask {

    private final HologramSession s;
    private final NBTTagCompound state;
    private final boolean draftOnly;
    private final Map<String, HologramAePolicy.Issue> issues = new LinkedHashMap<>();
    private final Map<String, HologramAeBlueprint.Analysis> analyses = new LinkedHashMap<>();
    private final java.util.Set<String> historicalPositions = new java.util.HashSet<>();
    private final NBTTagList rows = new NBTTagList();
    private Iterator<HologramAePolicy.Issue> iterator;
    private int stage, cursor;

    HologramSnapshotAeTask(HologramSession s, NBTTagCompound state) {
        this.s = s;
        this.state = state;
        draftOnly = s.localPlanPending && !s.localPlanError.isEmpty();
    }

    String phase() {
        return "metadata.ae." + stage;
    }

    long done() {
        return cursor;
    }

    long total() {
        if (stage == 0)
            return s.virtualPreset == null || HologramDeidentification.level(s.virtualPreset) != 0 || s.capture == null
                ? 0
                : s.capture.cells.size();
        if (stage == 1)
            return s.virtualPreset == null || s.virtualPreset.aeIssues == null ? 0 : s.virtualPreset.aeIssues.size();
        if (stage == 2) return s.virtualPreset == null || s.capture == null ? 0 : s.capture.cells.size();
        if (stage == 3) return issues.size();
        return 1;
    }

    boolean step(int maxWork, long deadline) {
        try {
            for (int work = 0; work < maxWork && stage < 5; work++) {
                if (work > 0 && System.nanoTime() >= deadline) return false;
                if (stage == 0) {
                    if (cursor < total()) {
                        HologramCapture.Cell cell = s.capture.cells.get(cursor++);
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
                    } else next();
                } else if (stage == 1) {
                    if (cursor < total()) {
                        HologramAePolicy.Issue issue = s.virtualPreset.aeIssues.get(cursor++);
                        issue.validate();
                        issues
                            .put(issue.x + "," + issue.y + "," + issue.z + ":" + issue.path + ":" + issue.code, issue);
                        historicalPositions.add((issue.x + s.x) + "," + (issue.y + s.y) + "," + (issue.z + s.z));
                    } else next();
                } else if (stage == 2) {
                    if (cursor < total()) analyze(s.capture.cells.get(cursor++));
                    else {
                        iterator = issues.values()
                            .iterator();
                        next();
                    }
                } else if (stage == 3) {
                    if (iterator.hasNext()) {
                        append(iterator.next());
                        cursor++;
                    } else {
                        if (s.virtualPreset != null) state.setTag("aeIssues", rows);
                        next();
                    }
                } else {
                    if (draftOnly) {
                        if (s.ae.ack != null) state.setTag("aeEditAck", s.ae.ack.copy());
                        state.setBoolean("aePreviewIncomplete", true);
                    } else HologramSnapshots.aeSnapshot(s, state, false);
                    stage = 5;
                    cursor = 1;
                }
                if (issues.size() > HologramAePolicy.MAX_ISSUES) throw new IOException("AE complete report budget");
            }
        } catch (IOException | RuntimeException invalid) {
            state.setString("aeReportError", "ae.report.rejected");
            state.setBoolean("aeReportBlocking", true);
            state.removeTag("aeSaveReview");
            s.ae.review = null;
            stage = 5;
            cursor = 1;
        }
        return stage == 5;
    }

    private void next() {
        stage++;
        cursor = 0;
    }

    private void analyze(HologramCapture.Cell cell) throws IOException {
        NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
        if (!HologramAeBlueprint.supports(cell.block, payload)) return;
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(cell.block, cell.meta, payload);
        if (!analysis.issues.isEmpty() || historicalPositions.contains(HologramValues.position(cell)))
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
    }

    private void append(HologramAePolicy.Issue issue) throws IOException {
        NBTTagCompound row = issue.dto(s.x, s.y, s.z);
        rows.appendTag(row);
        String key = row.getInteger("x") + "," + row.getInteger("y") + "," + row.getInteger("z");
        if ("identity.foreign_owner".equals(row.getString("code"))) {
            row.setString("resolution", "deidentifyBlueprint");
            return;
        }
        HologramAeBlueprint.Analysis analysis = analyses.get(key);
        HologramCapture.Cell cell = s.capture == null ? null : s.capture.positions.get(key);
        if (analysis == null || cell == null) return;
        String structure = cell.belongsTo(s.blueprintActiveStructure) ? s.blueprintActiveStructure
            : cell.memberships()
                .isEmpty() ? ""
                    : cell.memberships()
                        .get(0);
        HologramAePolicy.projectResolution(row, analysis, structure);
    }
}
