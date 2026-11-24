// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

/** Resource report from a bounded plan, including resumable distinct-material checks. */
final class HologramResourceTask {

    private final HologramSession s;
    private final HologramPlanClassifier classifier;
    private final HologramNativeItemBill nativeItems;
    private final DirectDemand direct;
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
        this(s, classifier, false);
    }

    HologramResourceTask(HologramSession s, HologramPlanClassifier classifier, boolean observeNativeItems) {
        this(s, classifier, observeNativeItems, null);
    }

    HologramResourceTask(HologramSession s, HologramPlanClassifier classifier, boolean observeNativeItems,
        DirectDemand direct) {
        this.s = s;
        this.classifier = classifier;
        nativeItems = observeNativeItems ? new HologramNativeItemBill(s) : null;
        this.direct = direct;
    }

    HologramNativeItemBill nativeItemBill() {
        return done() && nativeItems != null && nativeItems.current(s) ? nativeItems : null;
    }

    boolean done() {
        return stage == 14;
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
            case 13:
                return "resources.direct-demand";
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
        if (stage == 13) return s.blueprintResources.items.size();
        return s.capture.cells.size();
    }

    void advance() {
        long measured = HologramRuntimeMetrics.start();
        try {
            advanceChecked();
        } catch (IOException invalid) {
            if (nativeItems != null) nativeItems.rejectUnsupported();
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
                int required = counts.get(entry.getKey());
                s.planMissing += Math.max(0, required - available);
                if (nativeItems != null) nativeItems.record(entry.getValue(), required, available);
                checked++;
            } else if (s.virtual && s.virtualPreset != null
                && s.virtualPreset.schema >= 2
                && !s.player.capabilities.isCreativeMode) {
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
                HologramBlueprintResources.Plan payable = HologramMaterialAccess.fuzzyBlueprintShells(s)
                    ? s.blueprintResources
                    : total.plan;
                if (payable.items.isEmpty() && payable.fluids.isEmpty() && payable.essentia.isEmpty()) {
                    cursor = 0;
                    stage = HologramMaterialAccess.fuzzyBlueprintShells(s) ? 9 : 12;
                    return;
                }
                missing = new HologramBlueprintResources.MissingTask(
                    payable,
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
                if (nativeItems != null) nativeItems.finish();
                if (direct == null) stage = 14;
                else {
                    cursor = 0;
                    stage = 13;
                }
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
        } else if (stage == 13) {
            // Already aggregated by exact payload identity; this list also contains each chosen decoration cost once.
            List<ItemStack> items = s.blueprintResources.items;
            if (!direct.creative && cursor < items.size()) {
                ItemStack item = items.get(cursor++);
                direct.add(item, item.stackSize, false);
            } else {
                direct.finish(s.blueprintResources.energy);
                stage = 14;
            }
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
            if (HologramSessionPolicy.ignoredPlacementAir(s, cell, activeIndex)) return;
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
        if (HologramSessionPolicy.ignoredPlacementAir(s, c, i)) return;
        if (!HologramSessionPolicy.inScope(s, c, i) || !"pending".equals(c.status)) return;
        ItemStack target = HologramCellPolicy.targetFor(s, c, i);
        if (nativeItems != null && (target == null || !c.tileNbt.isEmpty()
            || !c.itemNbt.isEmpty()
            || c.userPosition
            || c.attachmentPlacementProof != null)) nativeItems.rejectUnsupported();
        if (s.mode != 2 && s.virtual && s.virtualPreset.schema >= 2 && !classifier.emptyOrdinary(s, c, i)) {
            try {
                HologramEnergyPayload.Prepared[] prepared = new HologramEnergyPayload.Prepared[1];
                merge = aggregate.merge(HologramCellPolicy.resourcePlan(s, c, prepared));
                HologramEnergyPayload.Prepared cache = prepared[0] == null ? HologramCellPolicy.cachePlan(s, c, i)
                    : prepared[0];
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
            for (ItemStack cover : HologramCellPolicy.coverCosts(s, c)) addShell(cover, false);
        if (s.mode != 2 && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(target))) {
            try {
                HologramDecorMaterials.Descriptor descriptor = HologramCellPolicy.decoration(s, c);
                if (descriptor == null && !HologramMaterialAccess.freeShell(s, c, target))
                    addShell(HologramEssentia.shellMaterial(target), true);
                else if (descriptor != null) {
                    if (nativeItems != null) nativeItems.rejectUnsupported();
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
                if (nativeItems != null) nativeItems.rejectUnsupported();
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

    private void addShell(ItemStack stack, boolean mainShell) {
        if (direct != null) direct.add(stack, 1, mainShell);
        ExactKey key = HologramValues.lookupKey(stack);
        Integer previous = counts.get(key);
        if (previous == null) {
            key = HologramValues.stackKey(stack);
            stacks.put(key, stack);
            counts.put(key, 1);
        } else counts.replace(key, previous + 1);
    }

    /** Server-owned payable demand, not a UI quote or permission. No cell or world traversal during the recheck. */
    static final class DirectDemand {

        private final HologramSession owner;
        private final HologramCapture capture;
        private final HologramPresets.Preset preset;
        private final World world;
        private final String request;
        private final long revision, nonce, workspace, declaration, variants, containers, adapters, voucherRules;
        private final int scope, layer, selected, x, y, z, sources;
        private final boolean creative, retainNbt, retainCovers;
        private final Map<ExactKey, NBTTagCompound> index = new HashMap<>();
        private final List<NBTTagCompound> rows = new ArrayList<>();
        private BigInteger energy;

        DirectDemand(HologramSession s) {
            owner = s;
            capture = s.capture;
            preset = s.virtualPreset;
            world = s.player.worldObj;
            request = s.executionRequestId;
            revision = s.planRevision;
            nonce = s.placementReviewNonce;
            workspace = s.editorWorkspaceEpoch;
            scope = s.scope;
            layer = s.layer;
            selected = s.selected;
            x = s.x;
            y = s.y;
            z = s.z;
            sources = sources(s.materialSettings);
            creative = s.player.capabilities.isCreativeMode;
            retainNbt = preset.retainNbt;
            retainCovers = HologramSessionPolicy.placementRetainCovers(s);
            declaration = HologramDeclarationAdapters.admissionGeneration();
            variants = com.miaokatze.gtmb.api.MaterialVariants.generation();
            containers = com.miaokatze.gtmb.api.ResourceContainers.generation();
            adapters = HologramNativePlacement.generation();
            voucherRules = DecorationVoucherConfig.revision();
        }

        private static int sources(HologramMaterials.Settings settings) {
            return (settings.main ? 1 : 0) | (settings.containers ? 2 : 0) | (settings.me ? 4 : 0)
                | settings.priority << 3 | settings.recoveryDestination << 5;
        }

        boolean current(HologramSession s) {
            return s == owner && capture == s.capture && preset == s.virtualPreset && world == s.player.worldObj
                && request.equals(s.executionRequestId)
                && !request.isEmpty()
                && revision == s.planRevision && nonce == s.placementReviewNonce && workspace == s.editorWorkspaceEpoch
                && creative == s.player.capabilities.isCreativeMode
                && scope == s.scope && layer == s.layer && selected == s.selected
                && x == s.x && y == s.y && z == s.z
                && s.mode == 0 && s.virtualPlacement && s.virtualLocked && !s.jobVoucherRequested
                && retainNbt == preset.retainNbt && retainCovers == HologramSessionPolicy.placementRetainCovers(s)
                && sources == sources(s.materialSettings)
                && declaration == HologramDeclarationAdapters.admissionGeneration()
                && variants == com.miaokatze.gtmb.api.MaterialVariants.generation()
                && containers == com.miaokatze.gtmb.api.ResourceContainers.generation()
                && adapters == HologramNativePlacement.generation()
                && voucherRules == DecorationVoucherConfig.revision();
        }

        void add(ItemStack item, long quantity, boolean mainShell) {
            if (finished()) throw new IllegalStateException("Direct material demand already frozen");
            if (item == null || item.getItem() == null || quantity <= 0) return;
            NBTTagCompound row = index.get(HologramValues.lookupKey(item));
            if (row == null) {
                ItemStack identity = item.copy();
                identity.stackSize = 1;
                row = identity.writeToNBT(new NBTTagCompound());
                index.put(HologramValues.stackKey(identity), row);
                rows.add(row);
            }
            row.setLong("required", Math.addExact(row.getLong("required"), quantity));
            if (mainShell) row.setLong("shellRequired", Math.addExact(row.getLong("shellRequired"), quantity));
        }

        private void finish(BigInteger energy) {
            this.energy = creative ? BigInteger.ZERO : energy;
        }

        boolean finished() {
            return energy != null;
        }

        /** New observations and carried-container discovery, not the Context used during early planning. */
        boolean stocked(HologramSession s) throws IOException {
            if (!finished() || !current(s) || !liveSources(s)) throw new IOException("施工材料规划已变化，请重新确认");
            if (rows.isEmpty() && energy.signum() == 0) return true;
            var stock = new HologramMaterials.Context(s.player, s.materialSettings, s.slot);
            var counter = new HologramMaterialQuote.QuoteCounter(rows, new HologramMaterialQuote.ItemStock() {

                public long exact(ItemStack item, long limit) {
                    return stock.countLong(item, limit);
                }

                public long fuzzy(ItemStack item, long limit) {
                    return stock.countFuzzyLong(item, limit);
                }
            }, item -> HologramMaterialAccess.fuzzyBlueprintShells(s, item));
            for (NBTTagCompound row : rows) if (counter.count(row) < row.getLong("required")) return false;
            boolean enoughEnergy = energy.signum() == 0 || stock.energyAvailable().compareTo(energy) >= 0;
            return enoughEnergy && liveSources(s);
        }

        private static boolean liveSources(HologramSession s) {
            HologramMaterials.Settings live = HologramMaterials.read(s.player.getHeldItem());
            // ME remains off for direct jobs; the preference on the tool does not enable it here.
            return !s.materialSettings.me && live.main == s.materialSettings.main
                && live.containers == s.materialSettings.containers && live.priority == s.materialSettings.priority
                && live.recoveryDestination == s.materialSettings.recoveryDestination;
        }
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
