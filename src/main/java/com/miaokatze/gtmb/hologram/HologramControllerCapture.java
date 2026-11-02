package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Resumable native structure capture, calibration and capture retry policy. */
final class HologramControllerCapture {

    private HologramControllerCapture() {}

    static final int CAPTURE_ATTEMPTS = 3;
    private static final int FINISH_WORK_PER_BATCH = 4096;

    static void applyCurrentCalibration(HologramSession s) {
        int main = s.main;
        boolean mainExplicit = s.mainExplicit;
        NBTTagCompound channels = (NBTTagCompound) s.channels.copy();
        HologramDraftPersistence.clearDraft(s);
        s.main = main;
        s.mainExplicit = mainExplicit;
        s.channels = channels;
        s.channels.removeTag("gt_hatch");
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.anchor || !s.player.worldObj.blockExists(cell.x, cell.y, cell.z)) continue;
            TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            Block block = s.player.worldObj.getBlock(cell.x, cell.y, cell.z);
            if (block == Blocks.air) continue;
            ItemStack sample = tile instanceof IGregTechTileEntity
                ? new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) tile).getMetaTileID())
                : new ItemStack(block, 1, block.getDamageValue(s.player.worldObj, cell.x, cell.y, cell.z));
            if (!HologramCellPolicy.acceptableSample(s, cell, sample)) continue;
            if (tile instanceof IGregTechTileEntity) {
                if (HologramCellPolicy.hatchElement(s, cell, sample, true) == null) continue;
            } else if (!HologramCellPolicy
                .destructive(cell, block, s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z))
                && !HologramCellPolicy
                    .knownSatisfied(cell, block, s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z)))
                continue;
            s.pins.put(i, sample);
            s.coordinatePins.put(HologramValues.position(cell), sample.copy());
            if (tile instanceof IGregTechTileEntity) {
                int facing = ((IGregTechTileEntity) tile).getFrontFacing()
                    .ordinal();
                for (int legal : HologramCellPolicy.legalFacings(s, cell, sample))
                    if (legal == facing) s.facingPins.put(HologramValues.position(cell), facing);
            }
        }
        calibrateMaterialChannels(s.capture, s.capabilities.toNBT(), s.channels, s.pins, s.coordinatePins);
        // Reconstruct with the inferred signals; otherwise the captured default block still reflects the old trigger.
        collect(s);
        s.status = s.capturePending ? "现场材质信道已识别，正在重新采集校准后的结构"
            : s.capture.incomplete ? "现场材质已识别，但重新采集不完整，高级施工已阻止" : "已校准现场材质信道与独立仓室；无法可靠反推的主信号、尺寸及原始信道保留当前值";
    }

    /** Only uniform, source-reviewed material options can establish a reliable named signal. */
    static void calibrateMaterialChannels(HologramCapture capture, NBTTagList rows, NBTTagCompound channels,
        Map<Integer, ItemStack> pins, Map<String, ItemStack> coordinatePins) {
        for (int r = 0; r < rows.tagCount(); r++) {
            NBTTagCompound row = rows.getCompoundTagAt(r);
            String kind = row.getString("kind");
            if (!"material".equals(kind) && !"coil".equals(kind)) continue;
            NBTTagList options = row.getTagList("options", 10);
            int uniform = 0;
            boolean invalid = false;
            List<Integer> governed = new ArrayList<>();
            for (int i = 0; i < capture.cells.size(); i++) {
                HologramCapture.Cell cell = capture.cells.get(i);
                // ofCoil (including activeCoils wrappers) enumerates only the old trigger's tier.
                // Its source-reviewed replacement family identifies the controlled cells independently.
                boolean relevant = "coil".equals(kind) && "coil".equals(cell.family);
                int observed = 0;
                ItemStack actual = coordinatePins.get(HologramValues.position(cell));
                for (int o = 0; o < options.tagCount(); o++) {
                    NBTTagCompound option = options.getCompoundTagAt(o);
                    ItemStack sample = ItemStack.loadItemStackFromNBT(option.getCompoundTag("item"));
                    if (sample == null) continue;
                    if (cell.candidates != null) for (ItemStack candidate : cell.candidates)
                        if (HologramValues.same(candidate, sample)) relevant = true;
                    if (HologramValues.same(actual, sample)
                        || "coil".equals(kind) && relevant && HologramValues.sameCoilLevel(actual, sample)) {
                        int value = option.getInteger("value");
                        if (observed != 0 && observed != value) invalid = true;
                        observed = value;
                    }
                }
                if (!relevant) continue;
                governed.add(i);
                if (observed == 0 || (uniform != 0 && uniform != observed)) {
                    invalid = true;
                    break;
                }
                uniform = observed;
            }
            if (!governed.isEmpty()) channels.removeTag(row.getString("id"));
            if (!invalid && uniform > 0) {
                channels.setInteger(row.getString("id"), uniform);
                for (int index : governed) {
                    pins.remove(index);
                    coordinatePins.remove(HologramValues.position(capture.cells.get(index)));
                }
            }
        }
    }

    static boolean captureConfigurationCurrent(HologramSession s) {
        return s.retryChannels != null && s.retryChannels.equals(s.channels)
            && s.retryMain == s.main
            && s.retryNoHatches == s.noHatches
            && s.retryMode == s.mode
            && s.retryScope == s.scope
            && s.retryLayer == s.layer
            && s.retrySelected == s.selected
            && (s.capture == null || s.capture.captureIntent == s.captureIntent);
    }

    static void scheduleCaptureRetry(HologramSession s) {
        // A timeout mixed with another failure is not treated as a transient cold-load timeout.
        boolean continuing = s.capture != null && (s.capture.pending || s.captureWork != null);
        boolean timeoutOnly = s.capture != null && s.capture.incomplete
            && (s.capture.failure.equals("TIME_LIMIT") || s.capture.failure.startsWith("TIME_LIMIT "));
        if (!continuing && !timeoutOnly) return;
        if (!continuing && s.captureAttempts >= CAPTURE_ATTEMPTS) {
            s.status = "结构采集连续 " + CAPTURE_ATTEMPTS + " 次超时，高级施工已阻止；可重新扫描或使用原版提示";
            return;
        }
        if (s.captureWork != null) s.captureWork.retainPins(s);
        s.capturePending = true;
        s.captureRetryDue = s.player.worldObj.getTotalWorldTime() + 2;
        s.retryFacing = s.facing;
        s.retryMain = s.main;
        s.retryChannels = (NBTTagCompound) s.channels.copy();
        s.retryNoHatches = s.noHatches;
        s.retryMode = s.mode;
        s.retryScope = s.scope;
        s.retryLayer = s.layer;
        s.retrySelected = s.selected;
        s.status = continuing
            ? s.captureWork != null && s.captureWork.finalizing() ? "正在分批解析候选与计算计划（" + s.capture.cells.size() + " 格）"
                : "正在分批采集完整结构（已采集 " + s.capture.cells.size() + " 格）"
            : "正在采集结构：首次加载超出单次预算，稍后自动重试（" + s.captureAttempts + "/" + CAPTURE_ATTEMPTS + "）";
    }

    static void collect(HologramSession s) {
        collect(s, true);
    }

    static void collect(HologramSession s, boolean resetAttempts) {
        if (s.virtual) {
            if (HologramVirtualStructureEditing.pending(s)) HologramVirtualStructureEditing.advance(s);
            else HologramResourcePlanner.refreshPlan(s);
            return;
        }
        FinishWork work = !resetAttempts ? s.captureWork : null;
        HologramCapture resumed = !resetAttempts && (work == null || !work.finalizing())
            && s.capture != null
            && s.capture.pending ? s.capture : null;
        if (work == null) {
            HologramMaterialAccess.refreshMaterialChoices(s);
            boolean calibrate = s.calibratePending, build = !resetAttempts && s.buildAfterCapture;
            HologramSession.cancelCaptureRetry(s);
            s.calibratePending = calibrate;
            s.buildAfterCapture = build;
            if (resetAttempts) s.captureAttempts = 0;
            s.job = 0;
            s.cursor = 0;
            s.completed = 0;
            s.recovered.clear();
            s.defer = false;
            s.stopTick = false;
            HologramSession.clearPending(s);
            Map<String, ItemStack> savedPins = new HashMap<>(s.coordinatePins);
            if (s.capture != null) {
                // Only explicit edits and selections need coordinates; do not rescan the captured prefix.
                for (Map.Entry<Integer, ItemStack> pin : s.pins.entrySet()) savedPins.put(
                    HologramValues.position(s.capture.cells.get(pin.getKey())),
                    pin.getValue()
                        .copy());
                if (resetAttempts) {
                    if (s.selected >= 0 && s.selected < s.capture.cells.size())
                        s.scanSelectedPosition = HologramValues.position(s.capture.cells.get(s.selected));
                    for (int i : s.selectedIndices)
                        s.scanSelectedPositions.add(HologramValues.position(s.capture.cells.get(i)));
                }
            }
            s.pins.clear();
            s.hatchCandidates.clear();
            s.declarations.clear();
            s.hatchParts.clear();
            s.hatchRequirements.clear();
            s.capabilities = HologramCapabilities.describe(s.context, HologramSessionPolicy.trigger(s), null)
                .withSavedChannels(s.channels);
            if (s.target == null) {
                s.capture = null;
                HologramResourcePlanner.refreshPlan(s);
                s.status = "信道配置已保存；请右键控制器选择施工目标";
                return;
            }
            if (!HologramStructureDiscovery.captureSupported(s.machine)) {
                s.capture = null;
                HologramResourcePlanner.refreshPlan(s);
                s.status = "未支持高级采集；仍可使用机器原版构造器接口";
                return;
            }
            s.facing = s.machine.getExtendedFacing()
                .ordinal();
            work = s.captureWork = new FinishWork(savedPins);
        }
        s.capturePending = false;
        s.captureRetryDue = 0;
        s.retryChannels = null;
        if (!work.finalizing()) {
            s.captureAttempts++;
            ItemStack initialTrigger = HologramSessionPolicy.trigger(s);
            s.capture = HologramCapture
                .collect(s.machine, initialTrigger, s.machine.getExtendedFacing(), s.captureIntent, resumed);
            if (s.capture.pending) {
                scheduleCaptureRetry(s);
                return;
            }
            s.capabilities = HologramCapabilities.describe(s.context, initialTrigger, s.capture.channelReport)
                .withSavedChannels(s.channels);
            ItemStack observedTrigger = HologramSessionPolicy.trigger(s);
            if (!s.capture.incomplete && !ItemStack.areItemStackTagsEqual(initialTrigger, observedTrigger)) {
                s.capture = HologramCapture
                    .collect(s.machine, observedTrigger, s.machine.getExtendedFacing(), s.captureIntent);
                if (s.capture.pending) {
                    scheduleCaptureRetry(s);
                    return;
                }
                s.capabilities = HologramCapabilities.describe(s.context, observedTrigger, s.capture.channelReport)
                    .withSavedChannels(s.channels);
                if (!ItemStack.areItemStackTagsEqual(observedTrigger, HologramSessionPolicy.trigger(s))) {
                    s.capture.incomplete = true;
                    s.capture.failure = "CHANNEL_BRANCH_UNSTABLE";
                }
            }
            work.beginFinish();
        }
        if (!work.step(s, FINISH_WORK_PER_BATCH, HologramWorkBudget.remainingNanos(HologramWorkBudget.SLICE_NANOS))) {
            scheduleCaptureRetry(s);
            return;
        }
        s.captureWork = null;
        s.status = s.capture.incomplete ? "结构采集不完整，高级施工已阻止" : "预览已采集；未知元素及机器部件保持保护";
        scheduleCaptureRetry(s);
        if (s.calibratePending && !s.capturePending) {
            s.calibratePending = false;
            if (!s.capture.incomplete) applyCurrentCalibration(s);
            else s.status = "采集失败，当前规划已保留，未校准现场目标";
        }
    }

    /** Capture retries also carry the bounded candidate/planning tail on the same server thread. */
    static final class FinishWork {

        private final Thread owner = Thread.currentThread();
        private final Map<String, ItemStack> savedPins;
        private Iterator<String> pinPositions;
        private Iterator<HologramCapture.Cell> manual;
        private HologramResourcePlanner.PlanTask planner;
        private boolean pinsRetained;
        private int stage, cursor, lastWork;

        FinishWork(Map<String, ItemStack> savedPins) {
            this.savedPins = savedPins;
        }

        boolean finalizing() {
            return stage != 0;
        }

        void beginFinish() {
            pinPositions = savedPins.keySet()
                .iterator();
            stage = 1;
        }

        private void retainPins(HologramSession s) {
            if (pinsRetained) return;
            s.coordinatePins.putAll(savedPins);
            pinsRetained = true;
        }

        String phase() {
            return planner != null ? planner.phase() : stage == 4 ? "capture.candidates" : "capture.restore";
        }

        int lastWork() {
            return lastWork;
        }

        boolean step(HologramSession s, int maxWork, long maxNanos) {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Capture finalization must stay on its server thread");
            if (maxWork <= 0 || maxNanos <= 0) {
                lastWork = 0;
                return planner != null && planner.done();
            }
            long started = System.nanoTime();
            HologramPlanBudget budget = new HologramPlanBudget(maxWork, maxNanos);
            s.capture.sourceCurrent(HologramSessionPolicy.trigger(s));
            while (stage < 6 && budget.take()) advance(s);
            lastWork = budget.used();
            if (planner == null) return false;
            boolean done = planner
                .step(Math.max(0, maxWork - lastWork), Math.max(0L, maxNanos - (System.nanoTime() - started)));
            lastWork += planner.lastWork();
            return done;
        }

        private void advance(HologramSession s) {
            if (stage == 1) {
                if (pinPositions.hasNext()) {
                    String key = pinPositions.next();
                    if (!s.capture.positions.containsKey(key) && !s.manualPositions.containsKey(key)) {
                        String[] xyz = key.split(",");
                        HologramCapture.Cell cell = new HologramCapture.Cell(
                            null,
                            Integer.parseInt(xyz[0]),
                            Integer.parseInt(xyz[1]),
                            Integer.parseInt(xyz[2]));
                        cell.userPosition = true;
                        cell.block = Blocks.air;
                        cell.role = "manual";
                        s.manualPositions.put(key, cell);
                    }
                } else {
                    pinPositions = null;
                    manual = s.manualPositions.values()
                        .iterator();
                    stage = 2;
                }
            } else if (stage == 2) {
                if (manual.hasNext()) {
                    HologramCapture.Cell cell = manual.next();
                    String key = HologramValues.position(cell);
                    if (s.capture.positions.containsKey(key)) return;
                    if (s.capture.cells.size() >= HologramCapture.LIMIT) {
                        s.capture.incomplete = true;
                        s.capture.failure = "CELL_LIMIT: 保留手动格后蓝图实际记录格数须小于一百万";
                        stage = 3;
                        return;
                    }
                    s.capture.cells.add(cell);
                    s.capture.positions.put(key, cell);
                } else {
                    manual = null;
                    stage = 3;
                }
            } else if (stage == 3) {
                s.selected = -1;
                s.selectedIndices.clear();
                // Memberships must precede shared-cell candidate intersections.
                HologramAreaDeclarations.bindAreaMemberships(s);
                stage = 4;
            } else if (stage == 4) {
                if (cursor < s.capture.cells.size()) {
                    int i = cursor++;
                    HologramCapture.Cell cell = s.capture.cells.get(i);
                    String key = HologramValues.position(cell);
                    ItemStack pin = savedPins.get(key);
                    if (pin != null && !cell.anchor) s.pins.put(i, pin);
                    if (key.equals(s.scanSelectedPosition)) s.selected = i;
                    if (s.scanSelectedPositions.contains(key)) s.selectedIndices.add(i);
                    cell.candidates = HologramCellPolicy.candidates(s, cell);
                } else stage = 5;
            } else if (stage == 5) {
                s.scanSelectedPosition = null;
                s.scanSelectedPositions.clear();
                HologramDraftPersistence.restoreBlueprintAnnotations(s);
                planner = HologramResourcePlanner.beginCapturePlan(s);
                stage = 6;
            }
        }
    }

}
