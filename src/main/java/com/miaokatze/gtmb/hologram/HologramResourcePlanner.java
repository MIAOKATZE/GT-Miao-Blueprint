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
        s.ae.invalidate();
        s.replacementAuthorized = false;
        s.replacementReady = HologramPlanPolicy.replacementStructureBuilt(s);
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
        if (s.capture == null) return;
        World w = s.player.worldObj;
        if (s.virtual && !s.allocationsFrozen) allocateVirtualTargets(s);
        buildPreset(s);
        if (s.mode == 2) s.planFacings.clear();
        else refreshFacings(s);
        for (int i = 0; i < s.capture.cells.size(); i++)
            s.planTargets.add(HologramCellPolicy.draftTarget(s, s.capture.cells.get(i), i));
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            boolean loaded = w.blockExists(c.x, c.y, c.z);
            s.expectedBlocks.add(loaded ? w.getBlock(c.x, c.y, c.z) : Blocks.air);
            s.expectedMeta.add(loaded ? w.getBlockMetadata(c.x, c.y, c.z) : 0);
            TileEntity tile = loaded ? w.getTileEntity(c.x, c.y, c.z) : null;
            s.expectedTiles.add(tile);
            s.expectedFacings.add(
                tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getFrontFacing()
                    .ordinal() : -1);
            c.status = HologramPlanPolicy.classify(s, c, i);
            s.planStatus.add(c.status);
        }
        refreshResources(s);
    }

    static NBTTagList blueprintResourceRows(HologramSession s) {
        NBTTagList rows = s.blueprintResources == null ? new NBTTagList()
            : s.blueprintResources.entries(HologramMaterialAccess.materialSource(s));
        if (!s.blueprintEnergy.empty()) {
            try {
                HologramEnergy.Demand converted = new HologramEnergy.Demand();
                List<String> sources = new ArrayList<>();
                boolean individualFallback = false;
                boolean unpricedCache = false;
                for (HologramEnergy.Demand cell : s.blueprintEnergyCells) {
                    HologramEnergy.Quote cellQuote = HologramMaterialAccess.materialSource(s)
                        .cachedEnergyQuote(cell);
                    converted.eu = converted.eu.add(cellQuote.eu);
                    converted.ae = converted.ae.add(java.math.BigDecimal.valueOf(cellQuote.ae));
                    individualFallback |= !cellQuote.affordable;
                    unpricedCache |= cellQuote.source.contains("unsupported amount precision");
                    if (!sources.contains(cellQuote.source)) sources.add(cellQuote.source);
                }
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
                for (int i = 0; i < rows.tagCount(); i++) {
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
                appendCacheRow(rows, "cache.unavailable", "设备缓存 · 网络不可用", "0", "0", "缓存将清空", true);
            }
        }
        for (String warning : s.blueprintEnergyWarnings)
            appendCacheRow(rows, "cache.warning", warning, "0", "0", "缓存已安全省略", false);
        for (String intrinsic : s.blueprintIntrinsicSources)
            appendCacheRow(rows, "cache.intrinsic", intrinsic, "0", "0", "原生固有能源", false);
        return rows;
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
        s.planMissing = 0;
        s.planRecoveryFits = true;
        s.blueprintTotalResources = null;
        if (s.capture == null) return;
        World w = s.player.worldObj;
        Map<String, ItemStack> stacks = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        s.blueprintResources = new HologramBlueprintResources.Plan();
        s.blueprintEnergy = new HologramEnergy.Demand();
        s.blueprintEnergyCells.clear();
        s.blueprintEnergyWarnings.clear();
        s.blueprintIntrinsicSources.clear();
        s.blueprintResourceMissing = "";
        s.decorPlanItems.clear();
        HologramDecorMaterials.Ledger decorReport = s.decorLedger.copy();
        Map<String, Long> decorReserved = new HashMap<>();
        s.pendingAssetBudget.clear();
        s.cellAssetBudgets.clear();
        if (!s.player.capabilities.isCreativeMode) for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (!HologramSessionPolicy.inScope(s, cell, i) || !"pending".equals(cell.status)) continue;
            try {
                Map<String, Long> costs = new HashMap<>();
                for (ItemStack item : HologramCellPolicy.resourcePlan(s, cell).items)
                    costs.merge(HologramValues.stackKey(item), (long) item.stackSize, Long::sum);
                for (ItemStack cover : HologramCellPolicy.coverCosts(s, cell))
                    costs.merge(HologramValues.stackKey(cover), 1L, Long::sum);
                if (!HologramCellPolicy.orientationOnly(s, cell, i) && HologramCellPolicy.decoration(s, cell) == null
                    && !HologramMaterialAccess.freeShell(s, cell, HologramCellPolicy.targetFor(s, cell, i))) {
                    ItemStack shell = HologramEssentia.shellMaterial(HologramCellPolicy.targetFor(s, cell, i));
                    if (shell != null) costs.merge(HologramValues.stackKey(shell), 1L, Long::sum);
                }
                s.cellAssetBudgets.put(i, costs);
                for (Map.Entry<String, Long> cost : costs.entrySet())
                    s.pendingAssetBudget.merge(cost.getKey(), cost.getValue(), Long::sum);
            } catch (java.io.IOException ignored) { /* The complete report below retains this blocker. */ }
        }
        List<ItemStack> recovery = new ArrayList<>();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
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
                    String key = HologramValues.stackKey(cover);
                    stacks.put(key, cover);
                    counts.merge(key, 1, Integer::sum);
                }
            if (s.mode != 2 && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(target))) {
                try {
                    HologramDecorMaterials.Descriptor descriptor = HologramCellPolicy.decoration(s, c);
                    if (descriptor == null && !HologramMaterialAccess.freeShell(s, c, target)) {
                        ItemStack shell = HologramEssentia.shellMaterial(target);
                        String key = HologramValues.stackKey(shell);
                        stacks.put(key, shell);
                        counts.merge(key, 1, Integer::sum);
                    } else if (descriptor != null) {
                        HologramDecorMaterials.Quote quote = decorReport.quote(
                            descriptor,
                            (stack, limit) -> (int) Math.max(
                                0L,
                                (long) HologramMaterialAccess.materialSource(s)
                                    .count(stack, Integer.MAX_VALUE)
                                    - decorReserved.getOrDefault(HologramValues.stackKey(stack), 0L)
                                    - s.pendingAssetBudget.getOrDefault(HologramValues.stackKey(stack), 0L)));
                        for (ItemStack payment : quote.items) {
                            String key = HologramValues.stackKey(payment);
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
        for (Map.Entry<String, ItemStack> entry : stacks.entrySet()) {
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
                for (Map.Entry<String, ItemStack> entry : stacks.entrySet()) {
                    ItemStack stack = entry.getValue()
                        .copy();
                    stack.stackSize = counts.get(entry.getKey());
                    base.add(stack);
                }
                total.addItems(base);
                s.blueprintTotalResources = total;
                String missing = HologramMaterialAccess.fuzzyBlueprintShells(s)
                    ? s.blueprintResources.missing(HologramMaterialAccess.materialSource(s))
                    : total.missing(HologramMaterialAccess.materialSource(s));
                if (HologramMaterialAccess.fuzzyBlueprintShells(s))
                    for (Map.Entry<String, ItemStack> entry : stacks.entrySet()) {
                        ItemStack shell = entry.getValue();
                        boolean sealed = HologramRecovery.hasSeal(shell);
                        long exactContents = 0;
                        for (ItemStack resource : s.blueprintResources.items) if (shell.isItemEqual(resource)
                            && (sealed ? ItemStack.areItemStackTagsEqual(shell, resource)
                                : !HologramRecovery.hasSeal(resource)))
                            exactContents += resource.stackSize;
                        long shellNeed = 0;
                        for (Map.Entry<String, ItemStack> other : stacks.entrySet())
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
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> reserved = new HashMap<>();
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            String key = HologramValues.position(cell);
            indices.put(key, i);
            ItemStack manual = s.pins.get(i);
            if (manual != null && s.virtualEdited.contains(key)
                && !HologramCellPolicy.plainTargetInstalled(s, cell, manual))
                reserved.merge(HologramValues.stackKey(manual), 1, Integer::sum);
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
                    String key = HologramValues.stackKey(candidate);
                    int available = counts.computeIfAbsent(
                        key,
                        ignored -> s.player.capabilities.isCreativeMode ? HologramCapture.LIMIT
                            : HologramMaterialAccess.materialSource(s)
                                .count(candidate, Math.max(4096, s.capture.cells.size())));
                    if (available > reserved.getOrDefault(key, 0)) {
                        chosen = candidate;
                        break;
                    }
                }
            }
            s.pins.put(index, chosen.copy());
            s.coordinatePins.put(position, chosen.copy());
            if (!HologramCellPolicy.plainTargetInstalled(s, cell, chosen))
                reserved.merge(HologramValues.stackKey(chosen), 1, Integer::sum);
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
        for (HologramCapture.Cell c : s.capture.cells)
            cells.add(new int[] { c.x, c.y, c.z, c.block == Blocks.air && !c.anchor ? 0 : 1 });
        java.util.Set<String> exterior = HologramHatchGeometry.exterior(
            cells,
            (x, y, z) -> s.player.worldObj.blockExists(x, y, z)
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
            TileEntity actual = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
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
        Map<String, Integer> reserved = new HashMap<>();
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
                reserved.merge(HologramValues.stackKey(stack), 1, Integer::sum);
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
            reserved.merge(HologramValues.stackKey(chosen), 1, Integer::sum);
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

    static ItemStack presetCandidate(HologramSession s, List<ItemStack> options, Map<String, Integer> reserved) {
        int[] tiers = new int[options.size()], available = new int[options.size()];
        for (int i = 0; i < options.size(); i++) {
            ItemStack option = options.get(i);
            tiers[i] = HologramCellPolicy.hatchTier(option);
            available[i] = s.player.capabilities.isCreativeMode ? 1
                : HologramMaterialAccess.materialSource(s)
                    .count(option, Math.max(4096, s.capture.cells.size()))
                    - reserved.getOrDefault(HologramValues.stackKey(option), 0);
        }
        int chosen = HologramHatchPreset.choose(tiers, available, s.hatchTier, s.hatchDowngrade);
        return chosen < 0 ? null : options.get(chosen);
    }
}
