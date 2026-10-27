package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Candidate-only, owner-thread pose replay. A rejected partial turn can never replace the published editor. */
final class HologramPresetPosePreparation {

    private final int[] turns;
    private final boolean quickLock, baselinePlacement;
    private final Thread owner = Thread.currentThread();
    private Map<String, int[]> littleReferences = new HashMap<>();
    private final Map<String, Integer> initialFacings = new HashMap<>();
    private HologramResourcePlanner.PlanTask baselinePlan;
    private HologramSession orientation;
    private ExtendedFacing baselineController = ExtendedFacing.DEFAULT;
    private boolean facingsCollected, controllerFound;
    private int turn, phase, cursor;
    private ExtendedFacing controller;
    private HologramBlueprintProjection.Population population;
    private boolean initialized, finished;

    HologramPresetPosePreparation(int[] turns, boolean quickLock) {
        this(turns, quickLock, false);
    }

    HologramPresetPosePreparation(int[] turns, boolean quickLock, boolean baselinePlacement) {
        this.turns = turns.clone();
        this.quickLock = quickLock;
        this.baselinePlacement = baselinePlacement;
    }

    boolean step(HologramSession s, int maxWork, long maxNanos) throws IOException {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Preset pose must stay on its owner thread");
        if (maxWork <= 0 || maxNanos <= 0) return false;
        if (finished) return true;
        if (!initialized) {
            // Preserve the canonical source for future processing and private lookup; only virtualPreset turns.
            if (s.libraryPrivatePreset != null) s.libraryPrivatePreset = s.processingSource;
            if (turns.length > 0) {
                // Keep the preliminary orientation quote off the final candidate. The final plan starts
                // with empty result collections rather than synchronously clearing a whole obsolete plan.
                orientation = new HologramSession(s.player, s.x, s.y, s.z, false, s.side);
                orientation.virtual = true;
                orientation.virtualPlacement = baselinePlacement;
                orientation.virtualPreset = s.virtualPreset;
                orientation.capture = s.capture;
                orientation.materialSettings = s.materialSettings;
                orientation.pins = s.pins;
                orientation.coordinatePins = s.coordinatePins;
                orientation.facingPins = s.facingPins;
                orientation.retainedNbtKeys = s.retainedNbtKeys;
                orientation.processingPolicy = s.processingPolicy;
                orientation.processingSource = s.processingSource;
                baselinePlan = HologramResourcePlanner.beginPlan(orientation);
                if (s.virtualPreset.controllerFacing != null) {
                    baselineController = ExtendedFacing.byIndex(s.virtualPreset.controllerFacing);
                    controllerFound = true;
                }
            } else facingsCollected = true;
            initialized = true;
        }
        if (baselinePlan != null) {
            if (!baselinePlan.step(maxWork, maxNanos)) return false;
            baselinePlan = null;
            return false;
        }
        if (population != null) {
            if (!population
                .step(maxWork, maxNanos == Long.MAX_VALUE ? Long.MAX_VALUE : HologramWorkBudget.deadline(maxNanos)))
                return false;
            if (s.capture == null || s.capture.incomplete)
                throw new IOException("Transformed blueprint projection incomplete");
            population = null;
            phase = 6;
            cursor = 0;
            return false;
        }
        HologramPlanBudget budget = new HologramPlanBudget(maxWork, maxNanos);
        while (budget.take()) {
            if (!facingsCollected) {
                if (cursor < s.capture.cells.size()) {
                    HologramCapture.Cell cell = s.capture.cells.get(cursor);
                    String key = HologramValues.position(cell);
                    Integer front = orientation.facingPins.getOrDefault(key, orientation.planFacings.get(cursor));
                    if (front != null && !s.virtualEdited.contains(key)) initialFacings.put(key, front);
                    HologramPresets.Cell saved = s.virtualPreset.cells.get(cursor++);
                    if (!controllerFound && saved.controller) {
                        baselineController = ExtendedFacing
                            .of(net.minecraftforge.common.util.ForgeDirection.getOrientation(saved.facing));
                        controllerFound = true;
                    }
                } else {
                    Integer front = s.facingPins.get(s.x + "," + s.y + "," + s.z);
                    if (front != null) baselineController = baselineController
                        .with(net.minecraftforge.common.util.ForgeDirection.getOrientation(front));
                    facingsCollected = true;
                    orientation = null;
                    cursor = 0;
                }
            } else if (turn < turns.length) {
                turnStep(s);
            } else if (phase < 6) {
                if (turns.length > 0) {
                    // Population's normal clear operations now see empty candidate-only collections.
                    // No editor history or selection exists on an unpublished library candidate.
                    s.pins = new HashMap<>();
                    s.coordinatePins = new HashMap<>();
                    s.facingPins = new HashMap<>();
                    s.manualPositions = new java.util.LinkedHashMap<>();
                    s.suppressedAuto = new java.util.HashSet<>();
                    s.autoTargets = new HashMap<>();
                    s.virtualEdited = new java.util.HashSet<>();
                    s.frozenAutoTargets = new HashMap<>();
                    population = new HologramBlueprintProjection.Population(s);
                    return false;
                }
                phase = 6;
                cursor = 0;
            } else if (cursor < s.capture.cells.size()) {
                HologramCapture.Cell cell = s.capture.cells.get(cursor++);
                if (!HologramSessionPolicy.ignoredPlacementAir(s, cell, cell.stableIndex)
                    && !HologramWorldFlow.worldPosition(cell.x, cell.y, cell.z, s.player.worldObj.getHeight()))
                    throw new IOException("Blueprint final placement exceeds world bounds");
            } else {
                if (s.virtualPlacement) HologramProjectorWorkflow.libraryCloseUi(s);
                if (quickLock) {
                    s.virtualLocked = true;
                    s.blueprintAwaitingConfirmation = true;
                    s.placementReviewIssued = false;
                    s.forcePlacementReady = false;
                    s.openPurpose = "";
                    s.placementReviewNonce++;
                }
                s.status = quickLock ? "位置已锁定；请核对材料与 NBT 放置确认"
                    : s.virtualPlacement ? "移动鼠标预览位置；右键锁定，再次右键核对材料与 NBT，Esc 取消放置" : "已打开自定义预设；仅编辑规划，不施工";
                finished = true;
                return true;
            }
        }
        return false;
    }

    private void turnStep(HologramSession s) throws IOException {
        HologramPresets.Preset preset = s.virtualPreset;
        boolean vertical = turns[turn] == 1, finalPose = turn == turns.length - 1;
        if (phase == 0) {
            controller = HologramVirtualTransform
                .alignment(turn == 0 ? baselineController : ExtendedFacing.byIndex(preset.controllerFacing), vertical);
            if (finalPose && (preset.schema < 2 || preset.controllerId >= 0)) {
                gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = preset.controllerId < 0
                    || preset.controllerId >= GregTechAPI.METATILEENTITIES.length ? null
                        : GregTechAPI.METATILEENTITIES[preset.controllerId];
                if (!(prototype instanceof MTEEnhancedMultiBlockBase)
                    || !((MTEEnhancedMultiBlockBase<?>) prototype).getAlignmentLimits()
                        .isNewExtendedFacingValid(controller))
                    throw new IOException("This blueprint cannot represent the selected orientation");
            }
            preset.controllerFacing = controller.ordinal();
            phase = 1;
        } else if (phase == 1) {
            if (cursor < preset.cells.size()) {
                // Existing per-payload reference validation remains exact; no full-preset synchronous scan.
                littleReferences.putAll(
                    HologramBlockTransformLittleTiles
                        .presetReferences(Collections.singletonList(preset.cells.get(cursor++)), vertical));
            } else {
                cursor = 0;
                phase = 2;
            }
        } else if (phase == 2) {
            if (cursor < preset.cells.size()) {
                HologramPresets.Cell saved = preset.cells.get(cursor++);
                int front = turn == 0
                    ? initialFacings
                        .getOrDefault((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z), saved.facing)
                    : saved.facing;
                if (preset.schema >= 2) {
                    HologramBlockTransforms.turn(saved, vertical, littleReferences, false);
                    saved.tileNbt = HologramCoordinatePolicies.rotatePayload(saved.tileNbt, vertical);
                    saved.itemNbt = HologramCoordinatePolicies.rotatePayload(saved.itemNbt, vertical);
                    HologramBlockTransforms.nativeSample(saved);
                }
                int[] offset = HologramVirtualTransform.turn(saved.x, saved.y, saved.z, vertical);
                saved.x = offset[0];
                saved.y = offset[1];
                saved.z = offset[2];
                saved.facing = HologramBlockTransforms.isPipe(saved.stack()) ? -1
                    : saved.controller && preset.schema < 2 ? controller.getDirection()
                        .ordinal() : HologramVirtualTransform.front(front, vertical);
                if (finalPose && saved.stack() != null
                    && !saved.missing()
                    && !HologramCellPolicy.virtualFrontAllowed(saved.stack(), saved.facing))
                    throw new IOException("设备不支持转向后的朝向");
            } else {
                cursor = 0;
                phase = 3;
            }
        } else if (phase == 3) {
            if (cursor < preset.aeIssues.size()) {
                HologramAePolicy.Issue issue = preset.aeIssues.get(cursor++);
                if (!"policy".equals(issue.path)) {
                    int[] location = HologramVirtualTransform.turn(issue.x, issue.y, issue.z, vertical);
                    issue.x = location[0];
                    issue.y = location[1];
                    issue.z = location[2];
                    issue.path = HologramAeBlueprint.rotateIssuePath(issue.path, vertical);
                }
            } else {
                cursor = 0;
                phase = 4;
            }
        } else if (phase == 4) {
            if (cursor < preset.structures.size()) {
                HologramPresets.Structure saved = preset.structures.get(cursor++);
                int[] coordinate = HologramVirtualTransform.turn(saved.x, saved.y, saved.z, vertical);
                saved.x = coordinate[0];
                saved.y = coordinate[1];
                saved.z = coordinate[2];
                if (saved.textBoxNbt != null && !saved.textBoxNbt.isEmpty()) {
                    NBTTagCompound definition = HologramBlueprintNbt.decode(saved.textBoxNbt);
                    int[] previous = definition.getIntArray("turns");
                    if (previous.length >= 96) throw new IOException("文字旋转历史过长，请重建文本框");
                    int[] next = java.util.Arrays.copyOf(previous, previous.length + 1);
                    next[previous.length] = vertical ? 2 : 1;
                    definition.setIntArray("turns", next);
                    saved.textBoxNbt = HologramBlueprintNbt.encodeCaptured(definition);
                }
            } else {
                cursor = 0;
                phase = 5;
            }
        } else if (cursor < preset.hintAreas.size()) {
            HologramPresets.HintArea hint = preset.hintAreas.get(cursor++);
            int[] a = HologramVirtualTransform.turn(hint.x1, hint.y1, hint.z1, vertical);
            int[] b = HologramVirtualTransform.turn(hint.x2, hint.y2, hint.z2, vertical);
            hint.x1 = Math.min(a[0], b[0]);
            hint.x2 = Math.max(a[0], b[0]);
            hint.y1 = Math.min(a[1], b[1]);
            hint.y2 = Math.max(a[1], b[1]);
            hint.z1 = Math.min(a[2], b[2]);
            hint.z2 = Math.max(a[2], b[2]);
        } else {
            s.facing = controller.ordinal();
            turn++;
            cursor = phase = 0;
            littleReferences = new HashMap<>();
        }
    }
}
