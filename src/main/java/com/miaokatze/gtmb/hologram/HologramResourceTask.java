package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

/** Resource report from a private virtual plan, including resumable distinct-material checks. */
final class HologramResourceTask {

    private final HologramSession s;
    private final HologramPlanClassifier classifier;
    private final Map<ExactKey, ItemStack> stacks = new LinkedHashMap<>();
    private final Map<ExactKey, Integer> counts = new LinkedHashMap<>();
    private final Map<ExactKey, Long> decorReserved = new HashMap<>();
    private final Map<ShellKey, Long> contentsByShell = new HashMap<>(), shellsByShell = new HashMap<>();
    private final Set<String> intrinsic = new HashSet<>();
    private HologramDecorMaterials.Ledger decorReport;
    private HologramBlueprintResources.Accumulator aggregate, total;
    private HologramBlueprintResources.Accumulator.Merge merge;
    private HologramBlueprintResources.MissingTask missing;
    private Iterator<Map.Entry<ExactKey, ItemStack>> shells;
    private Iterator<ItemStack> assets;
    private Iterator<Map.Entry<ExactKey, Long>> assetCosts;
    private Map<ExactKey, Long> costs;
    private HologramCapture.Cell active;
    private int stage, cursor, activeIndex, assetStage;
    private long checked;

    HologramResourceTask(HologramSession s, HologramPlanClassifier classifier) {
        this.s = s;
        this.classifier = classifier;
    }

    boolean done() {
        return stage == 13;
    }

    String phase() {
        switch (stage) {
            case 0:
                return "resources.initialize";
            case 1:
                return "resources.reservations";
            case 2:
                return merge == null ? "resources.cells" : "resources.merge";
            case 3:
                return "resources.decorations";
            case 4:
                return "resources.essentia";
            case 5:
                return "resources.shells";
            case 6:
                return "resources.total";
            case 7:
                return "resources.total-shells";
            case 8:
                return "resources.availability";
            case 9:
                return "resources.contents-index";
            case 10:
                return "resources.shell-index";
            case 11:
                return "resources.shared-shells";
            default:
                return "resources.blockers";
        }
    }

    long completed() {
        if (stage == 0 || (stage == 4 && missing == null)) return 0;
        if (merge != null && (stage == 2 || stage == 6)) return merge.completed();
        if (missing != null && (stage == 4 || stage == 8)) return missing.completed();
        if (stage == 1) return cursor - (active == null ? 0 : 1);
        return stage == 5 || stage == 7 || stage == 10 || stage == 11 ? checked : cursor;
    }

    long total() {
        if (stage == 0) return 1;
        if (stage == 4 && missing == null) return 0;
        if (merge != null && (stage == 2 || stage == 6)) return merge.total();
        if (missing != null && (stage == 4 || stage == 8)) return missing.total();
        if (stage == 3) return s.decorPlanItems.size();
        if (stage == 5 || stage == 7 || stage == 10 || stage == 11) return stacks.size();
        if (stage == 9) return s.blueprintResources.items.size();
        return s.capture.cells.size();
    }

    void advance() {
        long measured = HologramRuntimeMetrics.start();
        try {
            advanceChecked();
        } catch (IOException invalid) {
            if (stage != 1) {
                s.planRecoveryFits = false;
                s.blueprintResourceMissing = invalid.getMessage();
            }
            // A failed cell merge must not prevent subsequent cells and native blocker reporting.
            if (stage == 1) {
                active = null;
                assets = null;
                assetCosts = null;
            } else if (stage == 2) merge = null;
            else if (stage == 6 || stage == 7) {
                merge = null;
                stage = 12;
                cursor = 0;
            }
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.PLAN_RESOURCES, measured);
        }
    }

    private void advanceChecked() throws IOException {
        if (stage == 0) {
            s.planMissing = 0;
            s.planRecoveryFits = true;
            s.blueprintTotalResources = null;
            aggregate = new HologramBlueprintResources.Accumulator();
            s.blueprintResources = aggregate.plan;
            s.blueprintEnergy = new HologramEnergy.Demand();
            s.blueprintEnergyCells.clear();
            s.blueprintEnergyWarnings.clear();
            s.blueprintIntrinsicSources.clear();
            s.blueprintResourceMissing = "";
            s.decorPlanItems.clear();
            decorReport = s.decorLedger.copy();
            s.pendingAssetBudget.clear();
            s.cellAssetBudgets.clear();
            stage = s.player.capabilities.isCreativeMode ? 2 : 1;
        } else if (stage == 1) reserveAssets();
        else if (stage == 2) {
            if (merge != null) {
                if (!merge.done()) merge.advance();
                else merge = null;
                return;
            }
            if (cursor < s.capture.cells.size()) resourceCell(cursor++);
            else {
                cursor = 0;
                stage = 3;
            }
        } else if (stage == 3) {
            if (cursor < s.decorPlanItems.size()) aggregate.addItem(s.decorPlanItems.get(cursor++));
            else {
                missing = s.player.capabilities.isCreativeMode && !s.blueprintResources.essentia.isEmpty()
                    ? new HologramBlueprintResources.MissingTask(
                        s.blueprintResources,
                        HologramMaterialAccess.materialSource(s),
                        true)
                    : null;
                stage = 4;
            }
        } else if (stage == 4) {
            if (missing != null && !missing.done()) missing.advance();
            else {
                if (missing != null && missing.result() != null) {
                    s.planMissing = Math.max(1, s.planMissing);
                    s.blueprintResourceMissing = missing.result();
                }
                missing = null;
                shells = stacks.entrySet()
                    .iterator();
                checked = 0;
                stage = 5;
            }
        } else if (stage == 5) {
            if (shells.hasNext()) {
                Map.Entry<ExactKey, ItemStack> entry = shells.next();
                int available = HologramMaterialAccess
                    .shellCount(s, entry.getValue(), Math.max(4096, s.capture.cells.size()));
                s.planMissing += Math.max(0, counts.get(entry.getKey()) - available);
                checked++;
            } else
                if (s.virtualPreset != null && s.virtualPreset.schema >= 2 && !s.player.capabilities.isCreativeMode) {
                    total = new HologramBlueprintResources.Accumulator();
                    merge = total.merge(s.blueprintResources);
                    stage = 6;
                } else {
                    stage = 12;
                    cursor = 0;
                }
        } else if (stage == 6) {
            if (!merge.done()) merge.advance();
            else {
                merge = null;
                shells = stacks.entrySet()
                    .iterator();
                checked = 0;
                stage = 7;
            }
        } else if (stage == 7) {
            if (shells.hasNext()) {
                Map.Entry<ExactKey, ItemStack> entry = shells.next();
                ItemStack stack = entry.getValue()
                    .copy();
                stack.stackSize = counts.get(entry.getKey());
                total.addItem(stack);
                checked++;
            } else {
                s.blueprintTotalResources = total.plan;
                missing = new HologramBlueprintResources.MissingTask(
                    HologramMaterialAccess.fuzzyBlueprintShells(s) ? s.blueprintResources : total.plan,
                    HologramMaterialAccess.materialSource(s),
                    false);
                stage = 8;
            }
        } else if (stage == 8) {
            if (!missing.done()) missing.advance();
            else {
                s.blueprintResourceMissing = missing.result() == null ? "" : missing.result();
                if (missing.result() != null) s.planMissing = Math.max(1, s.planMissing);
                missing = null;
                cursor = 0;
                stage = HologramMaterialAccess.fuzzyBlueprintShells(s) ? 9 : 12;
            }
        } else if (stage == 9) {
            if (cursor < s.blueprintResources.items.size()) {
                ItemStack item = s.blueprintResources.items.get(cursor++);
                contentsByShell.merge(new ShellKey(item), (long) item.stackSize, Long::sum);
            } else {
                shells = stacks.entrySet()
                    .iterator();
                checked = 0;
                stage = 10;
            }
        } else if (stage == 10) {
            if (shells.hasNext()) {
                Map.Entry<ExactKey, ItemStack> entry = shells.next();
                shellsByShell.merge(new ShellKey(entry.getValue()), (long) counts.get(entry.getKey()), Long::sum);
                checked++;
            } else {
                shells = stacks.entrySet()
                    .iterator();
                checked = 0;
                stage = 11;
            }
        } else if (stage == 11) {
            if (shells.hasNext()) {
                ItemStack shell = shells.next()
                    .getValue();
                ShellKey key = new ShellKey(shell);
                long need = shellsByShell.getOrDefault(key, 0L) + contentsByShell.getOrDefault(key, 0L);
                if (HologramMaterialAccess.shellCount(s, shell, Integer.MAX_VALUE) < need) {
                    if (s.blueprintResourceMissing.isEmpty())
                        s.blueprintResourceMissing = "Blueprint shell material shortage";
                    s.planMissing = Math.max(1, s.planMissing);
                }
                checked++;
            } else {
                cursor = 0;
                stage = 12;
            }
        } else if (stage == 12) {
            if (cursor == s.capture.cells.size()) {
                stage = 13;
                return;
            }
            int i = cursor++;
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (!HologramSessionPolicy.inScope(s, cell, i) || !"unsupported".equals(cell.status)
                || cell.resourceError.isEmpty()) return;
            s.planRecoveryFits = false;
            if (s.blueprintResourceMissing.isEmpty()) s.blueprintResourceMissing = cell.resourceError;
            else if (!s.blueprintResourceMissing.contains(cell.resourceError))
                s.blueprintResourceMissing += "；" + cell.resourceError;
        }
    }

    private void reserveAssets() throws IOException {
        if (assetCosts != null) {
            if (assetCosts.hasNext()) {
                Map.Entry<ExactKey, Long> cost = assetCosts.next();
                s.pendingAssetBudget.merge(cost.getKey(), cost.getValue(), Long::sum);
            } else {
                assetCosts = null;
                active = null;
            }
        } else if (active == null) {
            if (cursor == s.capture.cells.size()) {
                cursor = 0;
                stage = 2;
                return;
            }
            activeIndex = cursor++;
            HologramCapture.Cell cell = s.capture.cells.get(activeIndex);
            if (!HologramSessionPolicy.inScope(s, cell, activeIndex) || !"pending".equals(cell.status)) return;
            active = cell;
            costs = new HashMap<>();
            assets = classifier.resources(s, cell, activeIndex).items.iterator();
            assetStage = 0;
        } else if (assets.hasNext()) {
            ItemStack item = assets.next();
            HologramValues.mergeStack(costs, item, assetStage == 0 ? (long) item.stackSize : 1L, Long::sum);
        } else if (assetStage == 0) {
            assets = HologramCellPolicy.coverCosts(s, active)
                .iterator();
            assetStage = 1;
        } else {
            if (!HologramCellPolicy.orientationOnly(s, active, activeIndex)
                && HologramCellPolicy.decoration(s, active) == null
                && !HologramMaterialAccess.freeShell(s, active, HologramCellPolicy.targetFor(s, active, activeIndex))) {
                ItemStack shell = HologramEssentia.shellMaterial(HologramCellPolicy.targetFor(s, active, activeIndex));
                if (shell != null) HologramValues.mergeStack(costs, shell, 1L, Long::sum);
            }
            s.cellAssetBudgets.put(activeIndex, costs);
            assetCosts = costs.entrySet()
                .iterator();
        }
    }

    private void resourceCell(int i) throws IOException {
        HologramCapture.Cell c = s.capture.cells.get(i);
        if (!HologramSessionPolicy.inScope(s, c, i) || !"pending".equals(c.status)) return;
        ItemStack target = HologramCellPolicy.targetFor(s, c, i);
        if (s.mode != 2 && s.virtualPreset.schema >= 2 && !classifier.emptyOrdinary(s, c, i)) {
            try {
                merge = aggregate.merge(HologramCellPolicy.resourcePlan(s, c));
                HologramEnergyPayload.Prepared cache = HologramCellPolicy.cachePlan(s, c, i);
                s.blueprintEnergy.merge(cache.demand);
                if (!cache.demand.empty()) s.blueprintEnergyCells.add(cache.demand);
                for (String warning : cache.warnings)
                    if (s.blueprintEnergyWarnings.size() < 32 && !s.blueprintEnergyWarnings.contains(warning))
                        s.blueprintEnergyWarnings.add(warning);
                if (!cache.intrinsicSource.isEmpty() && intrinsic.add(cache.intrinsicSource))
                    s.blueprintIntrinsicSources.add(cache.intrinsicSource);
            } catch (IOException invalid) {
                s.planRecoveryFits = false;
                s.blueprintResourceMissing = invalid.getMessage();
            }
        }
        if (s.mode != 2 && HologramCellPolicy.orientationOnly(s, c, i)) return;
        if (s.mode != 2 && !s.player.capabilities.isCreativeMode)
            for (ItemStack cover : HologramCellPolicy.coverCosts(s, c)) addShell(cover);
        if (s.mode != 2 && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(target))) {
            try {
                HologramDecorMaterials.Descriptor descriptor = HologramCellPolicy.decoration(s, c);
                if (descriptor == null && !HologramMaterialAccess.freeShell(s, c, target))
                    addShell(HologramEssentia.shellMaterial(target));
                else if (descriptor != null) {
                    HologramDecorMaterials.Quote quote = decorReport.quote(
                        HologramMaterialAccess.payableDecoration(s, c, descriptor),
                        (stack, limit) -> (int) Math.max(
                            0L,
                            (long) HologramMaterialAccess.materialSource(s)
                                .count(stack, Integer.MAX_VALUE)
                                - decorReserved.getOrDefault(HologramValues.lookupKey(stack), 0L)
                                - s.pendingAssetBudget.getOrDefault(HologramValues.lookupKey(stack), 0L)));
                    for (ItemStack payment : quote.items) {
                        HologramValues.mergeStack(decorReserved, payment, (long) payment.stackSize, Long::sum);
                        s.decorPlanItems.add(payment.copy());
                    }
                    decorReport.commit(quote);
                }
            } catch (IOException failure) {
                s.planRecoveryFits = false;
                s.blueprintResourceMissing = failure.getMessage();
            }
        }
        World world = s.player.worldObj;
        if (!HologramCellPolicy.trivial(world, c.x, c.y, c.z)
            && (!s.player.capabilities.isCreativeMode || world.getTileEntity(c.x, c.y, c.z) != null)) {
            try {
                // This report only audits drop availability; the original planner did not reserve these drops.
                HologramMaterialAccess
                    .drops(world.getBlock(c.x, c.y, c.z), world, c, world.getBlockMetadata(c.x, c.y, c.z));
            } catch (RuntimeException | LinkageError failure) {
                s.planRecoveryFits = false;
            }
        }
    }

    private void addShell(ItemStack stack) {
        ExactKey key = HologramValues.lookupKey(stack);
        Integer previous = counts.get(key);
        if (previous == null) {
            key = HologramValues.stackKey(stack);
            stacks.put(key, stack);
            counts.put(key, 1);
        } else counts.replace(key, previous + 1);
    }

    /** Fuzzy shells ignore unsealed NBT, but sealed resources require exact payload equality. */
    private static final class ShellKey {

        final Object item;
        final int meta;
        final NBTTagCompound seal;

        ShellKey(ItemStack stack) {
            item = stack.getItem();
            meta = stack.getItemDamage();
            seal = HologramRecovery.hasSeal(stack) ? (NBTTagCompound) stack.getTagCompound()
                .copy() : null;
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(item) * 31 + meta) * 31 + java.util.Objects.hashCode(seal);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ShellKey)) return false;
            ShellKey key = (ShellKey) other;
            return item == key.item && meta == key.meta && java.util.Objects.equals(seal, key.seal);
        }
    }
}
