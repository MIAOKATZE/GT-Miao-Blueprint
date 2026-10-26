package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Builds differential plans and complete resource/facing allocations before any transaction. */
final class HologramResourcePlanner {

    private HologramResourcePlanner() {}

    /** Local edits retain the already chosen whole-machine allocation, including its source cell. */
    static void freezeAllocations(HologramSession s) {
        if (s.allocationsFrozen) return;
        s.allocationsFrozen = true;
        s.presetSupported = false;
        s.presetReason = "已保留逐格编辑前的仓室分配；手动改动后的配额由原机检查，重新生成预设才重新分配";
        s.frozenAutoTargets.clear();
        for (Map.Entry<Integer, ItemStack> entry : s.autoTargets.entrySet()) s.frozenAutoTargets.put(
            HologramValues.position(s.capture.cells.get(entry.getKey())),
            entry.getValue()
                .copy());
        // Resolve a former family-wide pin before switching to a single-cell scope.
        if (s.scope == 3) for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.anchor || s.pins.containsKey(i) || !HologramCellPolicy.explicitTarget(s, cell, i)) continue;
            ItemStack target = HologramCellPolicy.targetFor(s, cell, i);
            if (target != null) {
                s.pins.put(i, target.copy());
                s.coordinatePins.put(HologramValues.position(cell), target.copy());
            }
        }
    }

    static void refreshPlan(HologramSession s) {
        if (!HologramGeometryEdits.requirePlan(s)) return;
        HologramDeferredPlanRefresh.cancel(s);
        resetPlan(s);
        if (s.capture == null) return;
        World w = s.player.worldObj;
        if (s.virtual && !s.allocationsFrozen) allocateVirtualTargets(s);
        buildPreset(s);
        for (int i = 0; i < s.capture.cells.size(); i++) {
            s.capture.cells.get(i).stableIndex = i;
            s.planTargets.add(HologramCellPolicy.draftTarget(s, s.capture.cells.get(i), i));
            if (HologramSessionPolicy.authoring(s)) s.capture.cells.get(i).status = "preview";
        }
        if (s.mode == 2) s.planFacings.clear();
        else refreshFacings(s);
        if (HologramSessionPolicy.authoring(s)) return;
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            if (observeIgnoredAir(s, c, i)) continue;
            boolean loaded = w.blockExists(c.x, c.y, c.z);
            net.minecraft.block.Block actual = loaded ? w.getBlock(c.x, c.y, c.z) : Blocks.air;
            int actualMeta = loaded ? w.getBlockMetadata(c.x, c.y, c.z) : 0;
            s.expectedBlocks.add(actual);
            s.expectedMeta.add(actualMeta);
            TileEntity tile = loaded ? w.getTileEntity(c.x, c.y, c.z) : null;
            s.expectedTiles.add(tile);
            s.expectedFacings.add(
                tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getFrontFacing()
                    .ordinal() : -1);
            c.status = HologramPlanPolicy.classify(s, c, i, loaded, actual, actualMeta, tile);
            s.planStatus.add(c.status);
        }
        refreshResources(s);
    }

    private static boolean observeIgnoredAir(HologramSession s, HologramCapture.Cell cell, int index) {
        if (!HologramSessionPolicy.ignoredPlacementAir(s, cell, index)) return false;
        // Preserve stable-index alignment without observing the world at this geometry-only position.
        s.expectedBlocks.add(Blocks.air);
        s.expectedMeta.add(0);
        s.expectedTiles.add(null);
        s.expectedFacings.add(-1);
        cell.status = "satisfied";
        s.planStatus.add(cell.status);
        if (s.localPlanPending) HologramLocalPlanRefresh.classified(s, index);
        return true;
    }

    private static void resetPlan(HologramSession s) {
        s.ae.invalidate();
        s.replacementAuthorized = false;
        s.replacementReady = !HologramSessionPolicy.authoring(s) && HologramPlanPolicy.replacementStructureBuilt(s);
        s.job = 0;
        s.cursor = 0;
        s.completed = 0;
        HologramSession.resetJobCounts(s, false);
        s.recovered.clear();
        s.defer = false;
        s.stopTick = false;
        HologramSession.clearPending(s);
        s.status = "差分已更新，可查看材料、回收与保护原因";
        s.planRevision++;
        s.expectedBlocks.clear();
        s.expectedMeta.clear();
        s.expectedTiles.clear();
        s.expectedFacings.clear();
        s.planStatus.clear();
        s.planTargets.clear();
        s.planMissing = 0;
        s.planRecoveryFits = true;
        s.blueprintTotalResources = null;
        if (HologramSessionPolicy.authoring(s)) clearAuthoringQuotes(s);
    }

    static void clearAuthoringQuotes(HologramSession s) {
        s.expectedBlocks.clear();
        s.expectedMeta.clear();
        s.expectedTiles.clear();
        s.expectedFacings.clear();
        s.planStatus.clear();
        s.replacementAuthorized = false;
        s.replacementReady = false;
        s.planMissing = 0;
        s.planRecoveryFits = true;
        s.blueprintTotalResources = null;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        s.blueprintAwaitingConfirmation = false;
        s.materialContext = null;
        s.blueprintResources = new HologramBlueprintResources.Plan();
        s.blueprintEnergy = new HologramEnergy.Demand();
        s.blueprintEnergyCells.clear();
        s.blueprintEnergyWarnings.clear();
        s.blueprintIntrinsicSources.clear();
        s.blueprintResourceMissing = "";
        s.blueprintEnergyNotice = "";
        s.decorPlanItems.clear();
        s.pendingAssetBudget.clear();
        s.cellAssetBudgets.clear();
    }

    /** Area imports plan into an unactivated staging session. Native/world calls stay on this thread. */
    static PlanTask beginPlan(HologramSession staging) {
        if (!staging.virtual) throw new IllegalArgumentException("Staged planning requires a virtual session");
        return new PlanTask(staging);
    }

    static final class PlanTask {

        private final HologramSession s;
        private final Thread owner = Thread.currentThread();
        private final List<int[]> geometry = new ArrayList<>();
        private HologramVirtualAllocationTask allocation;
        private HologramExteriorTask exterior;
        private HologramResourceTask resources;
        private HologramPlanClassifier classifier;
        private int stage, cursor, lastWork;
        private boolean needsExterior;
        private java.util.Set<String> outside = Collections.emptySet();

        private PlanTask(HologramSession s) {
            this.s = s;
        }

        boolean step(int maxWork, long maxNanos) {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Planning must stay on its main thread");
            long measured = HologramRuntimeMetrics.start();
            long now = System.nanoTime();
            long available = maxNanos == Long.MAX_VALUE ? Long.MAX_VALUE
                : Math.max(0L, HologramWorkBudget.deadline(Math.max(0L, maxNanos)) - now);
            HologramPlanBudget budget = new HologramPlanBudget(maxWork, available);
            try {
                while (!done() && budget.take()) advance();
                lastWork = budget.used();
                return done();
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.PLAN_STEP, measured);
            }
        }

        boolean done() {
            return stage == 9;
        }

        int lastWork() {
            return lastWork;
        }

        String phase() {
            if (stage == 1) return allocation.phase();
            if (stage == 4) return exterior.phase();
            if (stage == 8) return resources.phase();
            return stage == 0 ? "plan.initialize"
                : stage == 2 ? "plan.preset"
                    : stage == 3 ? "facing.cells"
                        : stage == 5 ? "facing.choose"
                            : stage == 6 ? "plan.targets"
                                : stage == 7 ? "plan.classify" : stage == 10 ? "plan.index" : "plan.complete";
        }

        long completed() {
            if (stage == 1) return allocation.completed();
            if (stage == 4) return exterior.completed();
            if (stage == 8) return resources.completed();
            return done() ? total() : cursor;
        }

        long total() {
            if (stage == 1) return allocation.total();
            if (stage == 4) return exterior.total();
            if (stage == 8) return resources.total();
            return s.capture == null ? 0 : s.capture.cells.size();
        }

        private void advance() {
            if (stage == 0) {
                resetPlan(s);
                if (s.capture == null) {
                    stage = 9;
                    return;
                }
                if (!HologramSessionPolicy.authoring(s)) classifier = new HologramPlanClassifier(s);
                s.cellIndices.clear();
                stage = 10;
            } else if (stage == 10) {
                if (cursor < s.capture.cells.size()) {
                    int i = cursor++;
                    s.capture.cells.get(i).stableIndex = i;
                    s.cellIndices.put(s.capture.cells.get(i), i);
                } else {
                    s.indexedCapture = s.capture;
                    allocation = new HologramVirtualAllocationTask(s);
                    cursor = 0;
                    stage = 1;
                }
            } else if (stage == 1) {
                if (!allocation.done()) allocation.advance();
                else {
                    s.autoTargets.clear();
                    if (!s.allocationsFrozen) buildPreset(s); // virtual non-frozen path is constant work
                    s.planFacings.clear();
                    cursor = 0;
                    stage = 2;
                }
            } else if (stage == 2) {
                if (s.allocationsFrozen && cursor < s.capture.cells.size()) {
                    int i = cursor++;
                    HologramCapture.Cell c = s.capture.cells.get(i);
                    ItemStack target = s.frozenAutoTargets.get(HologramValues.position(c));
                    if (!c.anchor && !s.pins.containsKey(i) && HologramCellPolicy.acceptableSample(s, c, target))
                        s.autoTargets.put(i, target.copy());
                } else {
                    cursor = 0;
                    stage = s.mode == 2 ? 6 : 3;
                }
            } else if (stage == 3) {
                if (cursor < s.capture.cells.size()) {
                    int i = cursor++;
                    HologramCapture.Cell c = s.capture.cells.get(i);
                    geometry.add(new int[] { c.x, c.y, c.z, c.block == Blocks.air && !c.anchor ? 0 : 1 });
                    ItemStack target = HologramCellPolicy.draftTarget(s, c, i);
                    if (target != null && target.getItem() instanceof ItemMachines
                        && !(ItemMachines
                            .getMetaTileEntity(target) instanceof gregtech.api.metatileentity.MetaPipeEntity))
                        needsExterior = true;
                } else {
                    if (needsExterior) {
                        exterior = new HologramExteriorTask(
                            geometry,
                            (x, y, z) -> HologramSessionPolicy.authoring(s)
                                || HologramSessionPolicy.ignoredPlacementAirAt(s, x, y, z)
                                || s.player.worldObj.blockExists(x, y, z)
                                    && HologramCellPolicy.trivial(s.player.worldObj, x, y, z));
                        stage = 4;
                    } else {
                        cursor = 0;
                        stage = 5;
                    }
                }
            } else if (stage == 4) {
                if (!exterior.done()) exterior.advance();
                else {
                    outside = exterior.result();
                    cursor = 0;
                    stage = 5;
                }
            } else if (stage == 5) {
                if (cursor < s.capture.cells.size()) facing(cursor++);
                else {
                    cursor = 0;
                    stage = 6;
                }
            } else if (stage == 6) {
                if (cursor < s.capture.cells.size()) {
                    int i = cursor++;
                    s.planTargets.add(HologramCellPolicy.draftTarget(s, s.capture.cells.get(i), i));
                    if (HologramSessionPolicy.authoring(s)) s.capture.cells.get(i).status = "preview";
                } else {
                    cursor = 0;
                    stage = HologramSessionPolicy.authoring(s) ? 9 : 7;
                }
            } else if (stage == 7) {
                if (cursor < s.capture.cells.size()) classify(cursor++);
                else {
                    resources = new HologramResourceTask(s, classifier);
                    stage = 8;
                }
            } else if (stage == 8) {
                if (!resources.done()) resources.advance();
                else stage = 9;
            }
        }

        private void classify(int i) {
            long measured = HologramRuntimeMetrics.start();
            try {
                classifyCurrent(i);
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.PLAN_CLASSIFY, measured);
            }
        }

        private void classifyCurrent(int i) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            if (observeIgnoredAir(s, c, i)) return;
            World world = s.player.worldObj;
            boolean loaded = world.blockExists(c.x, c.y, c.z);
            net.minecraft.block.Block actual = loaded ? world.getBlock(c.x, c.y, c.z) : Blocks.air;
            int actualMeta = loaded ? world.getBlockMetadata(c.x, c.y, c.z) : 0;
            s.expectedBlocks.add(actual);
            s.expectedMeta.add(actualMeta);
            TileEntity tile = loaded ? world.getTileEntity(c.x, c.y, c.z) : null;
            s.expectedTiles.add(tile);
            s.expectedFacings.add(
                tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getFrontFacing()
                    .ordinal() : -1);
            c.status = classifier.classify(s, c, i, loaded, actual, actualMeta, tile);
            s.planStatus.add(c.status);
            if (s.localPlanPending) HologramLocalPlanRefresh.classified(s, i);
        }

        private void facing(int i) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            ItemStack target = HologramCellPolicy.draftTarget(s, c, i);
            if (target != null && target.getItem() instanceof ItemMachines
                && ItemMachines.getMetaTileEntity(target) instanceof gregtech.api.metatileentity.MetaPipeEntity) {
                s.facingPins.remove(HologramValues.position(c));
                return;
            }
            int[] legal = HologramCellPolicy.legalFacings(s, c, target);
            if (legal.length == 0) return;
            Integer pin = s.facingPins.get(HologramValues.position(c));
            if (pin != null) {
                for (int side : legal) if (side == pin) s.planFacings.put(i, pin);
                return;
            }
            if (HologramSessionPolicy.authoring(s)) {
                for (int side : legal) if (side == c.actualFacing) {
                    s.planFacings.put(i, side);
                    return;
                }
            }
            TileEntity actual = HologramSessionPolicy.authoring(s) ? null
                : s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            if (actual instanceof IGregTechTileEntity && target != null
                && !HologramRecovery.hasSeal(target)
                && ((IGregTechTileEntity) actual).getMetaTileID() == target.getItemDamage()) {
                int front = HologramBlockIdentity.front((IGregTechTileEntity) actual);
                if (front >= 0) s.planFacings.put(i, front);
                return;
            }
            if (HologramRecovery.hasSeal(target)) return;
            int direction = HologramHatchGeometry.choose(c.x, c.y, c.z, legal, outside);
            if (direction >= 0) s.planFacings.put(i, direction);
        }
    }

    static NBTTagList blueprintResourceRows(HologramSession s) {
        ResourceRowsTask task = new ResourceRowsTask(s);
        while (!task.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
        return task.rows;
    }

    static NBTTagList cacheResourceRows(HologramSession s) {
        ResourceRowsTask task = beginCacheResourceRows(s);
        while (!task.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
        return task.rows;
    }

    static ResourceRowsTask beginCacheResourceRows(HologramSession s) {
        ResourceRowsTask task = new ResourceRowsTask(s);
        task.stage = HologramSessionPolicy.authoring(s) ? 5 : 1;
        return task;
    }

    static final class ResourceRowsTask {

        private final HologramSession s;
        private HologramBlueprintResources.Plan.EntriesTask entries;
        private NBTTagList rows = new NBTTagList();
        private final HologramEnergy.Demand converted = new HologramEnergy.Demand();
        private final java.util.Set<String> sourceSet = new java.util.LinkedHashSet<>();
        private boolean individualFallback, unpricedCache;
        private int stage, cursor;
        private long completed;

        ResourceRowsTask(HologramSession s) {
            this.s = s;
            if (HologramSessionPolicy.authoring(s)) stage = 5;
        }

        NBTTagList result() {
            return rows;
        }

        long done() {
            return stage == 0 ? entries == null ? 0 : entries.done() : stage == 2 ? 0 : stage == 5 ? 1 : cursor;
        }

        long total() {
            if (stage == 0) return entries == null ? 0 : entries.total();
            if (stage == 1) return s.blueprintEnergy.empty() ? 0 : s.blueprintEnergyCells.size();
            if (stage == 3) return s.blueprintEnergyWarnings.size();
            if (stage == 4) return s.blueprintIntrinsicSources.size();
            return 1;
        }

        boolean step(int maxWork, long deadline) {
            for (int work = 0; work < maxWork && stage < 5; work++) {
                if (work > 0 && System.nanoTime() >= deadline) return false;
                if (stage == 0) {
                    if (s.blueprintResources != null) {
                        if (entries == null)
                            entries = s.blueprintResources.entriesTask(HologramMaterialAccess.materialSource(s));
                        long before = entries.done();
                        boolean finished = entries.step(1, deadline);
                        completed += entries.done() - before;
                        if (!finished) continue;
                        rows = entries.rows;
                    }
                    stage = 1;
                    completed++;
                } else if (stage == 1) {
                    if (!s.blueprintEnergy.empty() && cursor < s.blueprintEnergyCells.size()) {
                        HologramEnergy.Demand cell = s.blueprintEnergyCells.get(cursor++);
                        try {
                            HologramEnergy.Quote quote = HologramMaterialAccess.materialSource(s)
                                .cachedEnergyQuote(cell);
                            converted.eu = converted.eu.add(quote.eu);
                            converted.ae = converted.ae.add(java.math.BigDecimal.valueOf(quote.ae));
                            individualFallback |= !quote.affordable;
                            unpricedCache |= quote.source.contains("unsupported amount precision");
                            sourceSet.add(quote.source);
                        } catch (RuntimeException | LinkageError unavailable) {
                            failedEnergy();
                            stage = 3;
                            cursor = 0;
                        }
                        completed++;
                    } else {
                        stage = 2;
                        cursor = 0;
                    }
                } else if (stage == 2) {
                    if (!s.blueprintEnergy.empty()) finalizeEnergy();
                    stage = 3;
                    completed++;
                } else if (stage == 3) {
                    if (cursor < s.blueprintEnergyWarnings.size()) {
                        String warning = s.blueprintEnergyWarnings.get(cursor++);
                        appendCacheRow(
                            rows,
                            "cache.warning",
                            warning,
                            "0",
                            "0",
                            "\u7f13\u5b58\u5df2\u5b89\u5168\u7701\u7565",
                            false);
                        completed++;
                    } else {
                        stage = 4;
                        cursor = 0;
                    }
                } else {
                    if (cursor < s.blueprintIntrinsicSources.size()) {
                        String intrinsic = s.blueprintIntrinsicSources.get(cursor++);
                        appendCacheRow(
                            rows,
                            "cache.intrinsic",
                            intrinsic,
                            "0",
                            "0",
                            "\u539f\u751f\u56fa\u6709\u80fd\u6e90",
                            false);
                        completed++;
                    } else stage = 5;
                }
            }
            return stage == 5;
        }

        private void failedEnergy() {
            appendCacheRow(rows, "cache.unavailable", "设备缓存 · 网络不可用", "0", "0", "缓存将清空", true);
        }

        private void finalizeEnergy() {
            int energyStart = rows.tagCount();
            List<String> sources = new ArrayList<>(sourceSet);
            try {
                // Placement pays/rounds each cell. Sum those quoted bills, never round combined RF/AE once.
                HologramEnergy.Quote quote = HologramMaterialAccess.materialSource(s)
                    .cachedEnergyQuote(converted);
                String source = String.join("; ", sources);
                if (quote.eu.signum() > 0) appendCacheRow(
                    rows,
                    "cache.EU",
                    "设备缓存 · 无线 EU",
                    quote.eu.toString(),
                    quote.availableEu.toString(),
                    quote.source,
                    quote.availableEu.compareTo(quote.eu) < 0);
                if (quote.ae > 0) appendCacheRow(
                    rows,
                    "cache.AE",
                    "设备缓存 · AE 网络",
                    Double.toString(quote.ae),
                    Double.toString(quote.availableAe),
                    quote.source,
                    quote.availableAe < quote.ae);
                if (unpricedCache || converted.empty() && individualFallback)
                    appendCacheRow(rows, "cache.precision", "设备缓存 · 数值不可精确付取", "0", "0", source, true);
                for (int i = energyStart; i < rows.tagCount(); i++) {
                    NBTTagCompound row = rows.getCompoundTagAt(i);
                    if (!row.getBoolean("optionalCache")) continue;
                    row.setString("source", source);
                    row.setString("originalEU", s.blueprintEnergy.eu.toString());
                    row.setString("originalRF", s.blueprintEnergy.rf.toPlainString());
                    row.setString("originalAE", s.blueprintEnergy.ae.toPlainString());
                    row.setString("convertedEU", converted.eu.toString());
                    row.setString("convertedAE", converted.ae.toPlainString());
                }
            } catch (RuntimeException | LinkageError unavailable) {
                failedEnergy();
            }
        }
    }

    static void appendCacheRow(NBTTagList rows, String key, String name, String required, String available,
        String source, boolean insufficient) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("key", key);
        row.setString("type", "energy");
        row.setString("name", name);
        row.setString("required", required);
        row.setString("available", available);
        row.setString("source", source);
        row.setBoolean("optionalCache", true);
        row.setBoolean("emptyFallback", insufficient);
        row.setString("missing", insufficient ? "缓存余额不足，空缓存放置" : "0");
        rows.appendTag(row);
    }

    static void refreshResources(HologramSession s) {
        if (!HologramGeometryEdits.requirePlan(s)) return;
        if (HologramSessionPolicy.authoring(s)) {
            clearAuthoringQuotes(s);
            return;
        }
        s.planMissing = 0;
        s.planRecoveryFits = true;
        s.blueprintTotalResources = null;
        if (s.capture == null) return;
        World w = s.player.worldObj;
        Map<ExactKey, ItemStack> stacks = new LinkedHashMap<>();
        Map<ExactKey, Integer> counts = new LinkedHashMap<>();
        s.blueprintResources = new HologramBlueprintResources.Plan();
        s.blueprintEnergy = new HologramEnergy.Demand();
        s.blueprintEnergyCells.clear();
        s.blueprintEnergyWarnings.clear();
        s.blueprintIntrinsicSources.clear();
        s.blueprintResourceMissing = "";
        s.decorPlanItems.clear();
        HologramDecorMaterials.Ledger decorReport = s.decorLedger.copy();
        Map<ExactKey, Long> decorReserved = new HashMap<>();
        s.pendingAssetBudget.clear();
        s.cellAssetBudgets.clear();
        if (!s.player.capabilities.isCreativeMode) for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (HologramSessionPolicy.ignoredPlacementAir(s, cell, i)) continue;
            if (!HologramSessionPolicy.inScope(s, cell, i) || !"pending".equals(cell.status)) continue;
            try {
                Map<ExactKey, Long> costs = new HashMap<>();
                for (ItemStack item : HologramCellPolicy.resourcePlan(s, cell).items)
                    HologramValues.mergeStack(costs, item, (long) item.stackSize, Long::sum);
                for (ItemStack cover : HologramCellPolicy.coverCosts(s, cell))
                    HologramValues.mergeStack(costs, cover, 1L, Long::sum);
                if (!HologramCellPolicy.orientationOnly(s, cell, i) && HologramCellPolicy.decoration(s, cell) == null
                    && !HologramMaterialAccess.freeShell(s, cell, HologramCellPolicy.targetFor(s, cell, i))) {
                    ItemStack shell = HologramEssentia.shellMaterial(HologramCellPolicy.targetFor(s, cell, i));
                    if (shell != null) HologramValues.mergeStack(costs, shell, 1L, Long::sum);
                }
                s.cellAssetBudgets.put(i, costs);
                for (Map.Entry<ExactKey, Long> cost : costs.entrySet())
                    s.pendingAssetBudget.merge(cost.getKey(), cost.getValue(), Long::sum);
            } catch (java.io.IOException ignored) { /* The complete report below retains this blocker. */ }
        }
        List<ItemStack> recovery = new ArrayList<>();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            if (HologramSessionPolicy.ignoredPlacementAir(s, c, i)) continue;
            if (!HologramSessionPolicy.inScope(s, c, i) || !"pending".equals(c.status)) continue;
            ItemStack target = HologramCellPolicy.targetFor(s, c, i);
            if (s.mode != 2 && s.virtual && s.virtualPreset.schema >= 2) {
                try {
                    s.blueprintResources.merge(HologramCellPolicy.resourcePlan(s, c));
                    HologramEnergyPayload.Prepared cache = HologramCellPolicy.cachePlan(s, c, i);
                    s.blueprintEnergy.merge(cache.demand);
                    if (!cache.demand.empty()) s.blueprintEnergyCells.add(cache.demand);
                    for (String warning : cache.warnings)
                        if (s.blueprintEnergyWarnings.size() < 32 && !s.blueprintEnergyWarnings.contains(warning))
                            s.blueprintEnergyWarnings.add(warning);
                    if (!cache.intrinsicSource.isEmpty()
                        && !s.blueprintIntrinsicSources.contains(cache.intrinsicSource))
                        s.blueprintIntrinsicSources.add(cache.intrinsicSource);
                } catch (java.io.IOException invalid) {
                    s.planRecoveryFits = false;
                    s.blueprintResourceMissing = invalid.getMessage();
                }
            }
            if (s.mode != 2 && HologramCellPolicy.orientationOnly(s, c, i)) continue;
            if (s.mode != 2 && !s.player.capabilities.isCreativeMode)
                for (ItemStack cover : HologramCellPolicy.coverCosts(s, c)) {
                    ExactKey key = HologramValues.stackKey(cover);
                    stacks.put(key, cover);
                    counts.merge(key, 1, Integer::sum);
                }
            if (s.mode != 2 && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(target))) {
                try {
                    HologramDecorMaterials.Descriptor descriptor = HologramCellPolicy.decoration(s, c);
                    if (descriptor == null && !HologramMaterialAccess.freeShell(s, c, target)) {
                        ItemStack shell = HologramEssentia.shellMaterial(target);
                        ExactKey key = HologramValues.stackKey(shell);
                        stacks.put(key, shell);
                        counts.merge(key, 1, Integer::sum);
                    } else if (descriptor != null) {
                        HologramDecorMaterials.Quote quote = decorReport.quote(
                            HologramMaterialAccess.payableDecoration(s, c, descriptor),
                            (stack, limit) -> (int) Math.max(
                                0L,
                                (long) HologramMaterialAccess.materialSource(s)
                                    .count(stack, Integer.MAX_VALUE)
                                    - decorReserved.getOrDefault(HologramValues.lookupKey(stack), 0L)
                                    - s.pendingAssetBudget.getOrDefault(HologramValues.lookupKey(stack), 0L)));
                        for (ItemStack payment : quote.items) {
                            ExactKey key = HologramValues.stackKey(payment);
                            decorReserved.merge(key, (long) payment.stackSize, Long::sum);
                            s.decorPlanItems.add(payment.copy());
                        }
                        decorReport.commit(quote);
                    }
                } catch (java.io.IOException failure) {
                    s.planRecoveryFits = false;
                    s.blueprintResourceMissing = failure.getMessage();
                }
            }
            if (!HologramCellPolicy.trivial(w, c.x, c.y, c.z)
                && (!s.player.capabilities.isCreativeMode || w.getTileEntity(c.x, c.y, c.z) != null)) try {
                    recovery.addAll(
                        HologramMaterialAccess
                            .drops(w.getBlock(c.x, c.y, c.z), w, c, w.getBlockMetadata(c.x, c.y, c.z)));
                } catch (RuntimeException | LinkageError failure) {
                    s.planRecoveryFits = false;
                }
        }
        try {
            s.blueprintResources.addItems(s.decorPlanItems);
        } catch (java.io.IOException failure) {
            s.planRecoveryFits = false;
            s.blueprintResourceMissing = failure.getMessage();
        }
        if (s.player.capabilities.isCreativeMode && !s.blueprintResources.essentia.isEmpty()) {
            String missing = s.blueprintResources.missingEssentia(HologramMaterialAccess.materialSource(s));
            if (missing != null) {
                s.planMissing = Math.max(1, s.planMissing);
                s.blueprintResourceMissing = missing;
            }
        }
        for (Map.Entry<ExactKey, ItemStack> entry : stacks.entrySet()) {
            ItemStack stack = entry.getValue();
            int available = HologramMaterialAccess.shellCount(s, stack, Math.max(4096, s.capture.cells.size()));
            s.planMissing += Math.max(0, counts.get(entry.getKey()) - available);
        }
        if (s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && !s.player.capabilities.isCreativeMode) {
            try {
                HologramBlueprintResources.Plan total = new HologramBlueprintResources.Plan()
                    .merge(s.blueprintResources);
                List<ItemStack> base = new ArrayList<>();
                for (Map.Entry<ExactKey, ItemStack> entry : stacks.entrySet()) {
                    ItemStack stack = entry.getValue()
                        .copy();
                    stack.stackSize = counts.get(entry.getKey());
                    base.add(stack);
                }
                total.addItems(base);
                s.blueprintTotalResources = total;
                HologramBlueprintResources.Plan payable = HologramMaterialAccess.fuzzyBlueprintShells(s)
                    ? s.blueprintResources
                    : total;
                String missing = payable.items.isEmpty() && payable.fluids.isEmpty() && payable.essentia.isEmpty()
                    ? null
                    : payable.missing(HologramMaterialAccess.materialSource(s));
                if (HologramMaterialAccess.fuzzyBlueprintShells(s))
                    for (Map.Entry<ExactKey, ItemStack> entry : stacks.entrySet()) {
                        ItemStack shell = entry.getValue();
                        boolean sealed = HologramRecovery.hasSeal(shell);
                        long exactContents = 0;
                        for (ItemStack resource : s.blueprintResources.items) if (shell.isItemEqual(resource)
                            && (sealed ? ItemStack.areItemStackTagsEqual(shell, resource)
                                : !HologramRecovery.hasSeal(resource)))
                            exactContents += resource.stackSize;
                        long shellNeed = 0;
                        for (Map.Entry<ExactKey, ItemStack> other : stacks.entrySet())
                            if (shell.isItemEqual(other.getValue())
                                && (sealed ? ItemStack.areItemStackTagsEqual(shell, other.getValue())
                                    : !HologramRecovery.hasSeal(other.getValue())))
                                shellNeed += counts.get(other.getKey());
                        if (HologramMaterialAccess.shellCount(s, shell, Integer.MAX_VALUE)
                            < shellNeed + exactContents) {
                            if (missing == null) missing = "Blueprint shell material shortage";
                            s.planMissing = Math.max(1, s.planMissing);
                        }
                    }
                s.blueprintResourceMissing = missing == null ? "" : missing;
                if (missing != null) s.planMissing = Math.max(1, s.planMissing);
            } catch (java.io.IOException invalid) {
                s.planRecoveryFits = false;
                s.blueprintResourceMissing = invalid.getMessage();
            }
        }
        // Unsupported cells are excluded from material totals, but their audited native blockers remain visible.
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (HologramSessionPolicy.ignoredPlacementAir(s, cell, i)) continue;
            if (!HologramSessionPolicy.inScope(s, cell, i) || !"unsupported".equals(cell.status)
                || cell.resourceError.isEmpty()) continue;
            s.planRecoveryFits = false;
            if (s.blueprintResourceMissing.isEmpty()) s.blueprintResourceMissing = cell.resourceError;
            else if (!s.blueprintResourceMissing.contains(cell.resourceError))
                s.blueprintResourceMissing += "；" + cell.resourceError;
        }
        // Committed drops can remain in the player's persistent recovery queue when destinations are full.
    }

    /** Allocate only untouched template targets, with cached class/tier groups and shared material reservations. */
    static void allocateVirtualTargets(HologramSession s) {
        if (s.virtualPreset == null || s.capture.incomplete || s.virtualPreset.schema >= 2) return;
        if (s.virtualGroups.isEmpty()) {
            for (HologramPresets.Cell entry : s.virtualPreset.palette) {
                ItemStack sample = entry.stack();
                if (sample == null || HologramCellPolicy.hatchTier(sample) == Integer.MAX_VALUE) continue;
                String role = ItemMachines.getMetaTileEntity(sample)
                    .getClass()
                    .getName();
                s.virtualGroups.computeIfAbsent(role, ignored -> new ArrayList<>())
                    .add(sample);
            }
            for (List<ItemStack> group : s.virtualGroups.values())
                group.sort((a, b) -> Integer.compare(HologramCellPolicy.hatchTier(b), HologramCellPolicy.hatchTier(a)));
        }
        Map<ExactKey, Integer> counts = new HashMap<>();
        Map<ExactKey, Integer> reserved = new HashMap<>();
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            String key = HologramValues.position(cell);
            indices.put(key, i);
            ItemStack manual = s.pins.get(i);
            if (manual != null && s.virtualEdited.contains(key)
                && !HologramCellPolicy.plainTargetInstalled(s, cell, manual))
                HologramValues.mergeStack(reserved, manual, 1, Integer::sum);
        }
        for (HologramPresets.Cell saved : s.virtualPreset.cells) {
            String position = (s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z);
            Integer index = indices.get(position);
            if (index == null || s.virtualEdited.contains(position)) continue;
            HologramCapture.Cell cell = s.capture.cells.get(index);
            ItemStack desired = saved.stack();
            if (desired == null) continue;
            ItemStack chosen = desired;
            int tier = HologramCellPolicy.hatchTier(desired);
            if (s.hatchDowngrade && tier != Integer.MAX_VALUE
                && !HologramCellPolicy.plainTargetInstalled(s, cell, desired)) {
                String role = ItemMachines.getMetaTileEntity(desired)
                    .getClass()
                    .getName();
                List<ItemStack> group = s.virtualGroups.getOrDefault(role, Collections.emptyList());
                ItemStack existing = null;
                for (ItemStack candidate : group) if (HologramCellPolicy.hatchTier(candidate) <= tier
                    && HologramCellPolicy.plainTargetInstalled(s, cell, candidate)) {
                        existing = candidate;
                        break;
                    }
                if (existing != null) chosen = existing;
                else for (ItemStack candidate : group) {
                    if (HologramCellPolicy.hatchTier(candidate) > tier
                        || HologramCellPolicy.legalFacings(s, cell, candidate).length == 0) continue;
                    ExactKey key = HologramValues.lookupKey(candidate);
                    Integer available = counts.get(key);
                    if (available == null) {
                        available = s.player.capabilities.isCreativeMode ? HologramCapture.LIMIT
                            : HologramMaterialAccess.materialSource(s)
                                .count(candidate, Math.max(4096, s.capture.cells.size()));
                        counts.put(HologramValues.stackKey(candidate), available);
                    }
                    if (available > reserved.getOrDefault(key, 0)) {
                        chosen = candidate;
                        break;
                    }
                }
            }
            s.pins.put(index, chosen.copy());
            s.coordinatePins.put(position, chosen.copy());
            if (!HologramCellPolicy.plainTargetInstalled(s, cell, chosen))
                HologramValues.mergeStack(reserved, chosen, 1, Integer::sum);
            Integer facing = s.facingPins.get(position);
            if (facing != null) {
                boolean legal = false;
                for (int direction : HologramCellPolicy.legalFacings(s, cell, chosen))
                    if (direction == facing) legal = true;
                if (!legal) s.facingPins.remove(position);
            }
        }
    }

    static void refreshFacings(HologramSession s) {
        s.planFacings.clear();
        List<int[]> cells = new ArrayList<>();
        boolean needsExterior = false;
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            cells.add(new int[] { c.x, c.y, c.z, c.block == Blocks.air && !c.anchor ? 0 : 1 });
            ItemStack target = HologramCellPolicy.draftTarget(s, c, i);
            if (target != null && target.getItem() instanceof ItemMachines
                && !(ItemMachines.getMetaTileEntity(target) instanceof gregtech.api.metatileentity.MetaPipeEntity))
                needsExterior = true;
        }
        java.util.Set<String> exterior = !needsExterior ? Collections.emptySet()
            : HologramHatchGeometry.exterior(
                cells,
                (x, y, z) -> HologramSessionPolicy.authoring(s)
                    || HologramSessionPolicy.ignoredPlacementAirAt(s, x, y, z)
                    || s.player.worldObj.blockExists(x, y, z)
                        && HologramCellPolicy.trivial(s.player.worldObj, x, y, z));
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            ItemStack target = HologramCellPolicy.draftTarget(s, c, i);
            if (target != null && target.getItem() instanceof ItemMachines
                && ItemMachines.getMetaTileEntity(target) instanceof gregtech.api.metatileentity.MetaPipeEntity) {
                s.facingPins.remove(HologramValues.position(c));
                continue;
            }
            int[] legal = HologramCellPolicy.legalFacings(s, c, target);
            if (legal.length == 0) continue;
            Integer pin = s.facingPins.get(HologramValues.position(c));
            if (pin != null) {
                for (int side : legal) if (side == pin) s.planFacings.put(i, pin);
                continue;
            }
            boolean retainedFacing = false;
            if (HologramSessionPolicy.authoring(s)) for (int side : legal) if (side == c.actualFacing) {
                s.planFacings.put(i, side);
                retainedFacing = true;
                break;
            }
            if (retainedFacing) continue;
            TileEntity actual = HologramSessionPolicy.authoring(s) ? null
                : s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            if (actual instanceof IGregTechTileEntity && target != null
                && !HologramRecovery.hasSeal(target)
                && ((IGregTechTileEntity) actual).getMetaTileID() == target.getItemDamage()) {
                int front = HologramBlockIdentity.front((IGregTechTileEntity) actual);
                if (front >= 0) s.planFacings.put(i, front);
                continue;
            }
            if (HologramRecovery.hasSeal(target)) continue;
            int direction = HologramHatchGeometry.choose(c.x, c.y, c.z, legal, exterior);
            if (direction >= 0) s.planFacings.put(i, direction);
        }
    }

    /** Freeze a whole-machine target list; quotas and inventory are shared across every position. */
    static void buildPreset(HologramSession s) {
        s.autoTargets.clear();
        if (s.allocationsFrozen) {
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                ItemStack target = s.frozenAutoTargets.get(HologramValues.position(cell));
                if (!cell.anchor && !s.pins.containsKey(i) && HologramCellPolicy.acceptableSample(s, cell, target))
                    s.autoTargets.put(i, target.copy());
            }
            return;
        }
        s.presetSupported = false;
        s.presetAvailable = false;
        s.presetReason = "此定义未声明完整仓室配额，不能保证自动成型";
        if (s.virtual || s.mode == 2) {
            s.presetReason = "拆除独立使用原结构范围，保留仓室预设设置供建造或替换使用";
            return;
        }
        Map<String, gregtech.api.interfaces.IHatchElement<?>> types = new LinkedHashMap<>();
        Map<String, Integer> quotas = new LinkedHashMap<>();
        Map<ExactKey, Integer> reserved = new HashMap<>();
        int minimumCasings = HologramIndustrialConstraints.minimumCasings(s.context);
        int remainingCasings = 0;
        for (int i = 0; i < s.capture.cells.size(); i++) if (HologramIndustrialConstraints
            .casing(s.capture.cells.get(i), HologramCellPolicy.draftTarget(s, s.capture.cells.get(i), i)))
            remainingCasings++;
        for (HologramCapture.Cell c : s.capture.cells) HologramCellPolicy.hatchRequirements(s, c)
            .forEach((type, count) -> {
                types.putIfAbsent(type.name(), type);
                quotas.merge(type.name(), count, Math::max);
            });
        if (types.isEmpty()) return;
        s.presetAvailable = true;
        if (s.presetPaused) {
            s.presetReason = "预设已撤销；选择电压级后可重新生成";
            return;
        }
        if (s.noHatches || !(s.presetEnabled || s.channels.hasKey("gt_hatch", 3))) {
            s.presetReason = "选择电压级后生成目标；保留已有仓室与手动指定";
            return;
        }
        // Count legal existing devices and explicit targets without changing either of them.
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            if (c.anchor) continue;
            ItemStack stack = s.pins.get(i);
            TileEntity actual = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            if (stack == null && actual instanceof IGregTechTileEntity)
                stack = new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) actual).getMetaTileID());
            if (stack == null || HologramCellPolicy.hatchElement(s, c, stack, true) == null) continue;
            if (s.pins.containsKey(i) && !(actual instanceof IGregTechTileEntity))
                HologramValues.mergeStack(reserved, stack, 1, Integer::sum);
            gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(stack);
            for (Map.Entry<String, gregtech.api.interfaces.IHatchElement<?>> type : types.entrySet())
                if (HologramHatchPolicy.matchesHatch(type.getValue(), prototype))
                    quotas.computeIfPresent(type.getKey(), (key, n) -> Math.max(0, n - 1));
        }
        List<String> ordered = new ArrayList<>(types.keySet());
        ordered.sort((a, b) -> Integer.compare(presetPositions(s, types.get(a)), presetPositions(s, types.get(b))));
        int unfilled = 0;
        for (String name : ordered) for (int remaining = quotas.get(name); remaining > 0; remaining--) {
            gregtech.api.interfaces.IHatchElement<?> type = types.get(name);
            ItemStack chosen = null;
            int chosenIndex = -1;
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell c = s.capture.cells.get(i);
                if (!presetPosition(s, c, i, type)) continue;
                if (HologramIndustrialConstraints.casing(c, HologramCellPolicy.draftTarget(s, c, i))
                    && remainingCasings <= minimumCasings) continue;
                List<ItemStack> options = new ArrayList<>();
                for (ItemStack option : HologramCellPolicy.candidates(s, c))
                    if (HologramCellPolicy.hatchTier(option) != Integer.MAX_VALUE
                        && HologramHatchPolicy.matchesHatch(type, ItemMachines.getMetaTileEntity(option)))
                        options.add(option);
                ItemStack pick = presetCandidate(s, options, reserved);
                if (pick == null) continue;
                if (chosen == null || HologramCellPolicy.hatchTier(pick) > HologramCellPolicy.hatchTier(chosen)) {
                    chosen = pick;
                    chosenIndex = i;
                }
                if (HologramCellPolicy.hatchTier(pick) == s.hatchTier) break;
            }
            if (chosen == null) {
                unfilled++;
                continue;
            }
            s.autoTargets.put(chosenIndex, chosen.copy());
            HologramCapture.Cell chosenCell = s.capture.cells.get(chosenIndex);
            if (HologramIndustrialConstraints.casing(chosenCell, new ItemStack(chosenCell.block, 1, chosenCell.meta)))
                remainingCasings--;
            HologramValues.mergeStack(reserved, chosen, 1, Integer::sum);
        }
        s.presetSupported = unfilled == 0 && remainingCasings >= minimumCasings;
        s.presetReason = s.presetSupported ? "已按声明配额生成仓室预设；各仓室按声明允许的等级范围自动调整，已有仓室与手选目标保留"
            : remainingCasings < minimumCasings ? "当前手选目标或已有仓室超过原机允许的壳体数量；至少需保留 " + minimumCasings + " 格壳体"
                : "部分声明仓室没有可放置位置或对应等级候选，不能保证自动成型（缺 " + unfilled + " 格）";
    }

    static boolean presetPosition(HologramSession s, HologramCapture.Cell c, int i,
        gregtech.api.interfaces.IHatchElement<?> type) {
        if (c.anchor || c.userPosition
            || "coil".equals(c.family)
            || HologramCellPolicy.explicitTarget(s, c, i)
            || s.autoTargets.containsKey(i)
            || s.suppressedAuto.contains(HologramValues.position(c))
            || s.player.worldObj.getTileEntity(c.x, c.y, c.z) != null) return false;
        boolean declared = false;
        for (gregtech.api.interfaces.IHatchElement<?> allowed : HologramCellPolicy.hatchRequirements(s, c)
            .keySet())
            if (allowed.name()
                .equals(type.name())) declared = true;
        return declared && (HologramCellPolicy.trivial(s.player.worldObj, c.x, c.y, c.z)
            || s.mode == 1 && HologramCellPolicy.destructive(
                c,
                s.player.worldObj.getBlock(c.x, c.y, c.z),
                s.player.worldObj.getBlockMetadata(c.x, c.y, c.z)));
    }

    static int presetPositions(HologramSession s, gregtech.api.interfaces.IHatchElement<?> type) {
        int count = 0;
        for (int i = 0; i < s.capture.cells.size(); i++)
            if (presetPosition(s, s.capture.cells.get(i), i, type)) count++;
        return count;
    }

    static ItemStack presetCandidate(HologramSession s, List<ItemStack> options, Map<ExactKey, Integer> reserved) {
        int[] tiers = new int[options.size()], available = new int[options.size()];
        for (int i = 0; i < options.size(); i++) {
            ItemStack option = options.get(i);
            tiers[i] = HologramCellPolicy.hatchTier(option);
            available[i] = s.player.capabilities.isCreativeMode ? 1
                : HologramMaterialAccess.materialSource(s)
                    .count(option, Math.max(4096, s.capture.cells.size()))
                    - reserved.getOrDefault(HologramValues.lookupKey(option), 0);
        }
        int chosen = HologramHatchPreset.choose(tiers, available, s.hatchTier, s.hatchDowngrade);
        return chosen < 0 ? null : options.get(chosen);
    }
}
