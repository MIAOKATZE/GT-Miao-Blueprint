// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

/** Testlab-only driver of actual production boundaries; never schedules unrelated actors. */
final class HologramTestLabConstructionDriver {

    private HologramTestLabConstructionDriver() {}

    static void step(HologramSession session) {
        var job = HologramConstructionJobs.find(session.player);
        if (job == null || job.session != session)
            throw new IllegalStateException("No admitted common job for fixture session");
        job.step(System.nanoTime() + Math.max(5_000_000L, HologramWorkBudget.tickNanos()));
    }

    static boolean write(HologramSession session, HologramCapture.Cell cell, int index) throws java.io.IOException {
        var sample = HologramCellPolicy.targetFor(session, cell, index);
        if (sample == null) throw new java.io.IOException("Fixture has no payable native target");
        var block = HologramCellPolicy.targetPreviewBlock(session, cell, sample);
        int metadata = HologramCellPolicy.targetPreviewMeta(session, cell, sample);
        int facing = cell.savedPlacementTarget != null ? cell.savedPlacementFacing
            : session.facingPins.getOrDefault(
                HologramValues.position(cell),
                index < session.planFacings.size() && session.planFacings.get(index) != null
                    ? session.planFacings.get(index)
                    : -1);
        var target = session.virtual
            ? HologramNativeConstruction.saved(session, block, metadata, sample, facing, cell.tileNbt, cell.itemNbt)
            : HologramNativeConstruction.projection(
                new HologramNativeTargetPlan.Cell(
                    0,
                    0,
                    0,
                    block,
                    metadata,
                    sample,
                    facing,
                    null,
                    null,
                    cell.element,
                    null,
                    false));
        if (session.materialContext == null) session.materialContext = new HologramMaterials.Context(
            session.player,
            session.materialSettings,
            session.slot);
        if (HologramNativeConstruction.matches(session, target, cell.x, cell.y, cell.z)) {
            cell.status = "satisfied";
            return true;
        }
        if (HologramNativeConstruction.synchronizable(session, target, cell.x, cell.y, cell.z)) {
            boolean changed = HologramNativeConstruction.synchronize(
                session,
                target,
                cell.x,
                cell.y,
                cell.z,
                () -> HologramSessionPolicy.jobTool(session) != null);
            if (changed) cell.status = "placed";
            return changed;
        }
        var outcome = HologramNativeConstruction
            .place(session, target, cell.x, cell.y, cell.z, () -> HologramSessionPolicy.jobTool(session) != null);
        if (outcome == HologramNativeConstruction.Outcome.PLACED) cell.status = "placed";
        return outcome == HologramNativeConstruction.Outcome.PLACED
            || outcome == HologramNativeConstruction.Outcome.SATISFIED;
    }
}
