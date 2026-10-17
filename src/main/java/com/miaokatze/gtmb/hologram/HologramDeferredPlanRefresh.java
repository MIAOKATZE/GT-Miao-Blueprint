package com.miaokatze.gtmb.hologram;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Latest-edit-wins continuation for complex virtual edits; it never owns a background UI lock. */
final class HologramDeferredPlanRefresh {

    private static final long NANOS_PER_TICK = 2_000_000L;
    private static final String WAIT = "材料与差分正在更新，可继续编辑；完成后可保存或施工";

    private HologramDeferredPlanRefresh() {}

    static void request(HologramSession s, Set<Integer> dirty) {
        if (!s.virtual || s.virtualPreset == null || s.virtualPreset.schema < 2 || s.capture == null)
            throw new IllegalArgumentException("Deferred local planning requires a schema-2 virtual blueprint");
        s.localPlanGeneration++;
        s.localPlanPending = true;
        s.localPlanError = "";
        s.localPlanTask = new Work(s);
        s.forcePlacementReady = false;
        s.ae.invalidateReviews();
        HologramLocalPlanRefresh.pending(s, dirty);
    }

    /** No retired continuation can apply to a different session, capture, tool or world. */
    static void cancel(HologramSession s) {
        if (!s.localPlanPending && s.localPlanTask == null) return;
        s.localPlanGeneration++;
        s.localPlanPending = false;
        s.localPlanTask = null;
        s.localPlanError = "";
    }

    /** A temporarily held different tool suspends this draft; returning resumes a fresh continuation. */
    static void suspend(HologramSession s) {
        if (!s.localPlanPending || s.localPlanTask == null) return;
        s.localPlanGeneration++;
        s.localPlanTask = null;
    }

    static boolean blocked(HologramSession s) {
        if (!s.localPlanPending) return false;
        s.forcePlacementReady = false;
        s.status = s.localPlanError.isEmpty() ? WAIT : s.localPlanError;
        return true;
    }

    /** Local edits and display/selection remain available; full-plan consumers wait for publication. */
    static boolean blocksAction(HologramSession s, String op) {
        if (!s.localPlanPending) return false;
        boolean needsPlan = "build".equals(op) || "start".equals(op)
            || "native".equals(op)
            || "resume".equals(op)
            || "confirmBlueprintPlacement".equals(op)
            || op.startsWith("save")
            || "updatePreset".equals(op)
            || "exportDraft".equals(op)
            || "confirmAeSave".equals(op)
            || "aePreviewOpen".equals(op)
            || "aePreviewEdit".equals(op)
            || op.startsWith("process")
            || op.startsWith("review")
            || op.startsWith("prepare")
            || "orderMissingMaterials".equals(op);
        return needsPlan && blocked(s);
    }

    static void tick(HologramSession s) {
        boolean pending = s.localPlanPending;
        String error = s.localPlanError;
        tick(
            s,
            Integer.MAX_VALUE,
            HologramWorkBudget.remainingNanos(NANOS_PER_TICK),
            HologramLocalPlanRefresh::completed);
        if (pending && !s.cancelled && HologramWorkspace.find(s.player.getUniqueID()) == s) {
            if (!s.localPlanError.isEmpty() && !s.localPlanError.equals(error)) HologramLocalPlanRefresh.failed(s);
            HologramSnapshots.sendProgress(s);
        }
    }

    /** Deterministic budget/completion ports exercise the production lifecycle without network publication. */
    static void tick(HologramSession s, int maxWork, long maxNanos,
        java.util.function.Consumer<HologramSession> complete) {
        if (!s.localPlanPending) return;
        if (s.cancelled || HologramWorkspace.find(s.player.getUniqueID()) != s) {
            cancel(s);
            return;
        }
        if (s.libraryWorld != s.player.worldObj || !HologramSessionPolicy.valid(s)) {
            suspend(s);
            return;
        }
        if (!s.localPlanError.isEmpty()) return;
        Work work = s.localPlanTask;
        if (work == null || !work.current(s)) {
            s.localPlanGeneration++;
            work = s.localPlanTask = new Work(s);
        }
        try {
            if (!work.step(s, maxWork, maxNanos)) return;
            if (s.localPlanTask != work || !work.current(s)) return;
            s.localPlanTask = null;
            s.localPlanPending = false;
            s.status = "材料与差分已更新，可继续编辑、保存或施工";
            complete.accept(s);
        } catch (RuntimeException | LinkageError failure) {
            if (s.localPlanTask != work || !work.current(s)) return;
            // Keep consumers fenced until another edit retries or a new source replaces this draft.
            s.localPlanError = "材料与差分更新失败，请修改目标重试或重新载入蓝图";
            s.status = s.localPlanError;
        }
    }

    static final class Work {

        private final long generation;
        private final String session;
        private final HologramCapture capture;
        private final HologramPresets.Preset preset;
        private final World world;
        private final String tool;
        private final int freezeTotal;
        private final boolean freezeFamily;
        private final String family, familyStructure;
        private final ItemStack familyPin;
        private HologramResourcePlanner.PlanTask planner;
        private Iterator<Map.Entry<Integer, ItemStack>> frozen;
        private int frozenDone, familyCursor, freezeStage;

        Work(HologramSession s) {
            generation = s.localPlanGeneration;
            session = s.id;
            capture = s.capture;
            preset = s.virtualPreset;
            world = s.player.worldObj;
            tool = s.toolId;
            freezeFamily = s.scope == 3;
            HologramCapture.Cell selected = capture != null && s.selected >= 0 && s.selected < capture.cells.size()
                ? capture.cells.get(s.selected)
                : null;
            family = selected == null ? "" : selected.family;
            String active = s.blueprintActiveStructure;
            familyStructure = active.isEmpty() && selected != null ? selected.structureId : active;
            ItemStack pin = s.pins.get(s.selected);
            familyPin = pin == null ? null : pin.copy();
            freezeTotal = s.autoTargets.size() + (freezeFamily && capture != null ? capture.cells.size() : 0);
            if (s.allocationsFrozen) {
                freezeStage = 3;
                planner = HologramResourcePlanner.beginPlan(s);
            }
        }

        boolean current(HologramSession s) {
            return generation == s.localPlanGeneration && session.equals(s.id)
                && capture == s.capture
                && preset == s.virtualPreset
                && world == s.player.worldObj
                && tool.equals(s.toolId);
        }

        String phase() {
            return planner == null ? "plan.freeze" : planner.phase();
        }

        long completed() {
            return planner == null ? (long) frozenDone + familyCursor : planner.completed();
        }

        long total() {
            return planner == null ? freezeTotal : planner.total();
        }

        private boolean step(HologramSession s, int maxWork, long maxNanos) {
            long started = System.nanoTime();
            HologramPlanBudget budget = new HologramPlanBudget(maxWork, maxNanos);
            while (planner == null && budget.take()) freeze(s);
            if (planner == null) return false;
            long remaining = maxNanos - (System.nanoTime() - started);
            return planner.step(Math.max(0, maxWork - budget.used()), Math.max(0L, remaining));
        }

        private void freeze(HologramSession s) {
            if (freezeStage == 0) {
                s.frozenAutoTargets.clear();
                frozen = s.autoTargets.entrySet()
                    .iterator();
                freezeStage = 1;
            } else if (freezeStage == 1) {
                if (frozen.hasNext()) {
                    Map.Entry<Integer, ItemStack> entry = frozen.next();
                    s.frozenAutoTargets.put(
                        HologramValues.position(capture.cells.get(entry.getKey())),
                        entry.getValue()
                            .copy());
                    frozenDone++;
                } else {
                    frozen = null;
                    freezeStage = 2;
                }
            } else if (freezeStage == 2) {
                if (freezeFamily && familyCursor < capture.cells.size()) {
                    int i = familyCursor++;
                    HologramCapture.Cell cell = capture.cells.get(i);
                    if (cell.anchor || s.pins.containsKey(i)) return;
                    ItemStack target = HologramCellPolicy.implicitBlueprintController(s, cell);
                    if (target == null && familyPin != null
                        && !family.isEmpty()
                        && family.equals(cell.family)
                        && (familyStructure.isEmpty() ? cell.memberships()
                            .isEmpty() : cell.belongsTo(familyStructure)))
                        target = familyPin;
                    if (target != null) {
                        s.pins.put(i, target.copy());
                        s.coordinatePins.put(HologramValues.position(cell), target.copy());
                    }
                } else {
                    s.allocationsFrozen = true;
                    s.presetSupported = false;
                    s.presetReason = "已保留逐格编辑前的仓室分配；重新生成预设才重新分配";
                    freezeStage = 3;
                    planner = HologramResourcePlanner.beginPlan(s);
                }
            }
        }
    }
}
