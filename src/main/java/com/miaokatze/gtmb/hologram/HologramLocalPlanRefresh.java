package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Stable-index edit cache. Geometry/allocator changes explicitly use the complete planner. */
final class HologramLocalPlanRefresh {

    final Set<Integer> pending = new LinkedHashSet<>();
    Map<String, List<Integer>> memberships = new HashMap<>();
    Map<String, HologramPresets.Cell> defaults = new HashMap<>();
    private final Set<Integer> awaiting = new LinkedHashSet<>();
    private boolean metadataPending;
    private ExactKey[] shellKeys = new ExactKey[0];
    private Map<ExactKey, ExactKey> canonicalKeys = new HashMap<>();
    private Map<ExactKey, ItemStack> identities = new LinkedHashMap<>();
    private Map<ExactKey, Integer> counts = new LinkedHashMap<>();
    private byte[] summaries = new byte[0];
    private String[] statuses = new String[0];
    private boolean[] complexCells = new boolean[0];
    private int complexCount;
    private final Set<Integer> selection = new LinkedHashSet<>();
    private Map<String, Integer> sourceIndices = new HashMap<>();
    private Set<String> palette = new HashSet<>();
    private HologramCapture capture;
    private HologramPresets.Preset source;
    private HologramPresets.Preset defaultsPreset;
    boolean snapshotReuseIndices;
    private long revision;
    private int size, scope, layer, selected, total, work, protectedCells;
    private String active;
    private boolean ready, simple;
    private boolean incomplete;
    private boolean indexCapture;

    void invalidate() {
        ready = false;
        pending.clear();
        metadataPending = false;
    }

    boolean current(HologramSession s) {
        return ready && capture == s.capture && s.capture != null && size == s.capture.cells.size();
    }

    static Set<Integer> selected(HologramSession s, NBTTagCompound action) {
        Set<Integer> result = new LinkedHashSet<>();
        int[] indices = action.getIntArray("indices");
        if (indices.length == 0) indices = action.getIntArray("selectedIndices");
        if (indices.length == 0)
            indices = new int[] { action.hasKey("selected", 3) ? action.getInteger("selected") : s.selected };
        for (int index : indices)
            if (s.capture != null && index >= 0 && index < s.capture.cells.size()) result.add(index);
        return result;
    }

    static boolean sparseOperation(HologramSession s, String op) {
        return "rotateCell".equals(op) || "restoreCell".equals(op)
            || "clearPin".equals(op)
            || "customPin".equals(op)
            || "sourcePin".equals(op)
            || "pin".equals(op)
            || "hatchFacing".equals(op)
            || "editCut".equals(op)
            || "editPaste".equals(op) && !(s.virtual && s.virtualPreset != null && s.virtualPreset.schema >= 2);
    }

    static void snapshotBegin(HologramSession s) {
        HologramLocalPlanRefresh cache = s.localEdits;
        cache.snapshotReuseIndices = cache.current(s) && cache.revision == s.planRevision
            && cache.source == s.processingSource
            && cache.defaultsPreset == s.virtualPreset;
        cache.ready = false;
        cache.pending.clear();
        cache.awaiting.clear();
        cache.metadataPending = false;
        if (!cache.snapshotReuseIndices) {
            cache.memberships = new HashMap<>();
            cache.defaults = new HashMap<>();
        }
        cache.identities = new LinkedHashMap<>();
        cache.counts = new LinkedHashMap<>();
        cache.canonicalKeys = new HashMap<>();
        cache.summaries = new byte[s.capture == null ? 0 : s.capture.cells.size()];
        cache.shellKeys = new ExactKey[cache.summaries.length];
        cache.statuses = new String[cache.summaries.length];
        cache.complexCells = new boolean[cache.summaries.length];
        cache.complexCount = 0;
        if (!cache.snapshotReuseIndices) {
            cache.sourceIndices = new HashMap<>();
            cache.palette = new HashSet<>();
        }
        cache.source = s.processingSource;
        cache.defaultsPreset = s.virtualPreset;
        cache.indexCapture = s.indexedCapture != s.capture;
        if (cache.indexCapture) s.cellIndices = new java.util.IdentityHashMap<>();
        cache.simple = s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && s.mode == 0
            && s.planRecoveryFits
            && s.decorPlanItems.isEmpty()
            && s.blueprintEnergy.empty()
            && s.blueprintResources != null
            && empty(s.blueprintResources);
    }

    static void snapshotSeedDefaults(HologramSession s, int index) {
        HologramPresets.Cell cell = s.virtualPreset.cells.get(index);
        s.localEdits.defaults.put(cell.x + "," + cell.y + "," + cell.z, cell);
    }

    static void snapshotSeedSource(HologramSession s, int index) {
        HologramPresets.Cell cell = s.processingSource.cells.get(index);
        s.localEdits.sourceIndices.put(cell.x + "," + cell.y + "," + cell.z, index);
    }

    static void snapshotSeedPalette(HologramSession s, int index) {
        ItemStack stack = s.processingSource.palette.get(index)
            .stack();
        if (stack != null) s.localEdits.palette
            .add(net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()) + ":" + stack.getItemDamage());
    }

    static void snapshotSeedCapture(HologramSession s, int index) {
        if (!s.localEdits.indexCapture) return;
        s.cellIndices.put(s.capture.cells.get(index), index);
        s.capture.cells.get(index).stableIndex = index;
        if (index + 1 == s.capture.cells.size()) s.indexedCapture = s.capture;
    }

    /** Primitive storage avoids retaining boxed indices and hash nodes for every blueprint cell. */
    private static final class Indices extends java.util.AbstractList<Integer> {

        private int[] values = new int[16];
        private int size;

        @Override
        public Integer get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            return values[index];
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean add(Integer index) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size++] = index;
            return true;
        }
    }

    private static boolean empty(HologramBlueprintResources.Plan plan) {
        return plan.items.isEmpty() && plan.fluids.isEmpty() && plan.essentia.isEmpty() && plan.energy.signum() == 0;
    }

    static void snapshotCell(HologramSession s, int index, NBTTagCompound row) {
        snapshotSeedCapture(s, index);
        HologramCapture.Cell cell = s.capture.cells.get(index);
        snapshotCell(
            s,
            index,
            row,
            HologramCellPolicy.targetFor(s, cell, index),
            HologramSessionPolicy.inScope(s, cell, index));
    }

    static void snapshotCell(HologramSession s, int index, NBTTagCompound row, ItemStack target, boolean inScope) {
        HologramLocalPlanRefresh cache = s.localEdits;
        HologramCapture.Cell cell = s.capture.cells.get(index);
        if (!cache.snapshotReuseIndices) {
            for (String member : cell.memberships()) cache.memberships.computeIfAbsent(member, ignored -> new Indices())
                .add(index);
        }
        cache.summaries[index] = (byte) summary(cell.status, inScope);
        cache.statuses[index] = cell.status;
        cache.complexCells[index] = inScope
            && (!cell.tileNbt.isEmpty() || !cell.itemNbt.isEmpty() || target != null && target.hasTagCompound());
        if (cache.complexCells[index]) cache.complexCount++;
        cache.replaceShell(index, shell(s, index, target, inScope));
    }

    /** A failed planner may publish editable draft geometry without claiming a complete material plan. */
    static void snapshotDraftCell(HologramSession s, int index, NBTTagCompound row) {
        HologramLocalPlanRefresh cache = s.localEdits;
        HologramCapture.Cell cell = s.capture.cells.get(index);
        if (!cache.snapshotReuseIndices) {
            for (String member : cell.memberships()) cache.memberships.computeIfAbsent(member, ignored -> new Indices())
                .add(index);
        }
        cache.summaries[index] = (byte) (HologramSessionPolicy.inScope(s, cell, index) ? 1 : 0);
        cache.statuses[index] = cell.status;
        cache.complexCells[index] = true;
        cache.complexCount++;
        cache.simple = false;
    }

    static void snapshotComplete(HologramSession s, NBTTagCompound state) {
        HologramLocalPlanRefresh cache = s.localEdits;
        cache.capture = s.capture;
        cache.size = s.capture == null ? 0 : s.capture.cells.size();
        cache.revision = s.planRevision;
        cache.context(s);
        cache.total = state.getInteger("total");
        cache.work = state.getInteger("workTotal");
        cache.protectedCells = state.getInteger("protected");
        cache.ready = true;
        cache.incomplete = state.getBoolean("planIncomplete");
        if (cache.indexCapture) s.indexedCapture = s.capture;
        cache.simple &= cache.complexCount == 0 && !cache.incomplete;
    }

    private void context(HologramSession s) {
        scope = s.scope;
        layer = s.layer;
        selected = s.selected;
        active = s.blueprintActiveStructure;
        selection.clear();
        selection.addAll(s.selectedIndices);
    }

    private static int summary(HologramSession s, int index) {
        HologramCapture.Cell cell = s.capture.cells.get(index);
        return summary(cell.status, HologramSessionPolicy.inScope(s, cell, index));
    }

    private static int summary(String status, boolean inScope) {
        if (!inScope) return 0;
        return 1 | ("pending".equals(status) ? 2 : 0)
            | ("protected".equals(status) || "unsupported".equals(status) || "blocked".equals(status) ? 4 : 0);
    }

    private static ItemStack shell(HologramSession s, int index) {
        HologramCapture.Cell cell = s.capture.cells.get(index);
        ItemStack target = HologramCellPolicy.targetFor(s, cell, index);
        return shell(s, index, target, HologramSessionPolicy.inScope(s, cell, index));
    }

    private static ItemStack shell(HologramSession s, int index, ItemStack target, boolean inScope) {
        HologramCapture.Cell cell = s.capture.cells.get(index);
        if (!inScope || !"pending".equals(cell.status)
            || s.player.capabilities.isCreativeMode
            || HologramCellPolicy.orientationOnly(s, cell, index)
            || HologramMaterialAccess.freeShell(s, cell, target)) return null;
        return target == null ? null : HologramEssentia.shellMaterial(target);
    }

    private static boolean complex(HologramSession s, int index) {
        HologramCapture.Cell cell = s.capture.cells.get(index);
        ItemStack target = HologramCellPolicy.targetFor(s, cell, index);
        return HologramSessionPolicy.inScope(s, cell, index)
            && (!cell.tileNbt.isEmpty() || !cell.itemNbt.isEmpty() || target != null && target.hasTagCompound());
    }

    private void replaceShell(int index, ItemStack replacement) {
        ExactKey previous = shellKeys[index];
        shellKeys[index] = null;
        if (previous != null) {
            int remaining = counts.get(previous) - 1;
            if (remaining == 0) {
                counts.remove(previous);
                identities.remove(previous);
                canonicalKeys.remove(previous);
            } else counts.put(previous, remaining);
        }
        if (replacement != null) {
            ExactKey canonical = canonicalKeys.get(HologramValues.lookupKey(replacement));
            if (canonical == null) {
                canonical = HologramValues.stackKey(replacement);
                canonicalKeys.put(canonical, canonical);
                identities.put(canonical, replacement.copy());
            }
            shellKeys[index] = canonical;
            counts.merge(canonical, 1, Integer::sum);
        }
    }

    /** Returns false before mutating a plan when its shared resource/allocation dependencies require a full pass. */
    static boolean refresh(HologramSession s, Set<Integer> edited) {
        HologramLocalPlanRefresh cache = s.localEdits;
        if (HologramSessionPolicy.authoring(s)) return refreshAuthoring(s, edited);
        if (!cache.ready || !cache.simple
            || cache.capture != s.capture
            || cache.size != s.capture.cells.size()
            || s.mode != 0
            || s.job == 1
            || s.job == 2
            || !cache.active.equals(s.blueprintActiveStructure)
            || cache.scope != s.scope
            || cache.layer != s.layer
            || s.scope == 3
            || s.presetEnabled && !s.allocationsFrozen) return false;
        Set<Integer> dirty = new LinkedHashSet<>(edited);
        if (s.scope == 2 && !HologramSessionPolicy.wholeBlueprintPlacement(s)) {
            dirty.addAll(cache.selection);
            dirty.addAll(s.selectedIndices);
            if (cache.selected >= 0) dirty.add(cache.selected);
            if (s.selected >= 0) dirty.add(s.selected);
        }
        World world = s.player.worldObj;
        for (int index : dirty) {
            if (index < 0 || index >= cache.size) return false;
            HologramCapture.Cell cell = s.capture.cells.get(index);
            ItemStack proposed = HologramCellPolicy.draftTarget(s, cell, index);
            if (!cell.tileNbt.isEmpty() || !cell.itemNbt.isEmpty()
                || proposed != null && proposed.hasTagCompound()
                || !HologramCellPolicy.trivial(world, cell.x, cell.y, cell.z)) return false;
            int[] legal = HologramCellPolicy.legalFacings(s, cell, HologramCellPolicy.draftTarget(s, cell, index));
            if (legal.length > 0 && !s.facingPins.containsKey(HologramValues.position(cell))) return false;
            try {
                if (HologramCellPolicy.decoration(s, cell) != null || !empty(HologramCellPolicy.resourcePlan(s, cell)))
                    return false;
            } catch (IOException invalid) {
                return false;
            }
        }
        s.ae.invalidate();
        s.replacementAuthorized = false;
        s.job = 0;
        s.cursor = s.completed = 0;
        s.recovered.clear();
        s.defer = false;
        s.stopTick = false;
        HologramSession.resetJobCounts(s, false);
        HologramSession.clearPending(s);
        s.planRevision++;
        for (int index : dirty) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            int old = cache.summaries[index];
            if ((old & 1) != 0) cache.total--;
            if ((old & 2) != 0) cache.work--;
            if ((old & 4) != 0) cache.protectedCells--;
            s.planTargets.set(index, HologramCellPolicy.draftTarget(s, cell, index));
            s.planFacings.remove(index);
            Integer pin = s.facingPins.get(HologramValues.position(cell));
            if (pin != null) for (int side : HologramCellPolicy.legalFacings(s, cell, s.planTargets.get(index)))
                if (side == pin) s.planFacings.put(index, pin);
            boolean loaded = world.blockExists(cell.x, cell.y, cell.z);
            s.expectedBlocks.set(index, loaded ? world.getBlock(cell.x, cell.y, cell.z) : Blocks.air);
            s.expectedMeta.set(index, loaded ? world.getBlockMetadata(cell.x, cell.y, cell.z) : 0);
            TileEntity tile = loaded ? world.getTileEntity(cell.x, cell.y, cell.z) : null;
            s.expectedTiles.set(index, tile);
            s.expectedFacings.set(
                index,
                tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getFrontFacing()
                    .ordinal() : -1);
            cell.status = HologramPlanPolicy
                .classify(s, cell, index, loaded, s.expectedBlocks.get(index), s.expectedMeta.get(index), tile);
            s.planStatus.set(index, cell.status);
            int now = summary(s, index);
            cache.summaries[index] = (byte) now;
            cache.statuses[index] = cell.status;
            if ((now & 1) != 0) cache.total++;
            if ((now & 2) != 0) cache.work++;
            if ((now & 4) != 0) cache.protectedCells++;
            cache.replaceShell(index, shell(s, index));
            Map<ExactKey, Long> previous = s.cellAssetBudgets.remove(index);
            if (previous != null) previous.forEach(
                (key, value) -> s.pendingAssetBudget
                    .compute(key, (ignored, count) -> count == null || count <= value ? null : count - value));
            ItemStack cost = cache.identities.get(cache.shellKeys[index]);
            if (cost != null) {
                Map<ExactKey, Long> budget = new HashMap<>();
                ExactKey key = cache.shellKeys[index];
                budget.put(key, 1L);
                s.cellAssetBudgets.put(index, budget);
                s.pendingAssetBudget.merge(key, 1L, Long::sum);
            }
        }
        s.planMissing = 0;
        s.blueprintResourceMissing = "";
        HologramBlueprintResources.Plan total = new HologramBlueprintResources.Plan();
        try {
            for (Map.Entry<ExactKey, Integer> entry : cache.counts.entrySet()) {
                ItemStack stack = cache.identities.get(entry.getKey())
                    .copy();
                int available = HologramMaterialAccess.shellCount(s, stack, Math.max(4096, cache.size));
                s.planMissing += Math.max(0, entry.getValue() - available);
                stack.stackSize = entry.getValue();
                total.addItems(java.util.Collections.singletonList(stack));
            }
            s.blueprintTotalResources = s.player.capabilities.isCreativeMode ? null : total;
            if (!s.player.capabilities.isCreativeMode) {
                String missing = total.missing(HologramMaterialAccess.materialSource(s));
                s.blueprintResourceMissing = missing == null ? "" : missing;
                if (missing != null) s.planMissing = Math.max(1, s.planMissing);
            }
        } catch (IOException invalid) {
            s.planRecoveryFits = false;
            s.blueprintResourceMissing = invalid.getMessage();
        }
        cache.pending.addAll(dirty);
        if (cache.selected >= 0) cache.pending.add(cache.selected);
        if (s.selected >= 0) cache.pending.add(s.selected);
        cache.context(s);
        return true;
    }

    /** Author targets are owned DTO edits; no live differential or material transaction exists yet. */
    private static boolean refreshAuthoring(HologramSession s, Set<Integer> edited) {
        HologramLocalPlanRefresh cache = s.localEdits;
        if (!cache.current(s) || s.localPlanPending
            || s.planTargets.size() != cache.size
            || s.mode != 0
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || !cache.active.equals(s.blueprintActiveStructure)
            || cache.scope != s.scope
            || cache.layer != s.layer
            || s.scope == 3) return false;
        Set<Integer> dirty = new LinkedHashSet<>(edited);
        if (s.scope == 2) {
            dirty.addAll(cache.selection);
            dirty.addAll(s.selectedIndices);
            if (cache.selected >= 0) dirty.add(cache.selected);
            if (s.selected >= 0) dirty.add(s.selected);
        }
        for (int index : dirty) {
            if (index < 0 || index >= cache.size) return false;
            HologramCapture.Cell cell = s.capture.cells.get(index);
            int[] legal = HologramCellPolicy.legalFacings(s, cell, HologramCellPolicy.draftTarget(s, cell, index));
            int wanted = s.facingPins
                .getOrDefault(HologramValues.position(cell), s.planFacings.getOrDefault(index, cell.actualFacing));
            boolean retained = legal.length == 0;
            for (int side : legal) if (side == wanted) retained = true;
            // A new unpinned machine orientation still needs the complete geometry choice.
            if (!retained) return false;
        }
        s.ae.invalidate();
        s.job = 0;
        s.cursor = s.completed = 0;
        s.recovered.clear();
        s.defer = false;
        s.stopTick = false;
        HologramSession.resetJobCounts(s, false);
        HologramSession.clearPending(s);
        HologramResourcePlanner.clearAuthoringQuotes(s);
        s.planRevision++;
        for (int index : dirty) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if ((cache.summaries[index] & 1) != 0) cache.total--;
            ItemStack target = HologramCellPolicy.draftTarget(s, cell, index);
            s.planTargets.set(index, target);
            int previous = s.planFacings.getOrDefault(index, cell.actualFacing);
            int wanted = s.facingPins.getOrDefault(HologramValues.position(cell), previous);
            s.planFacings.remove(index);
            for (int side : HologramCellPolicy.legalFacings(s, cell, target))
                if (side == wanted) s.planFacings.put(index, wanted);
            cell.status = "preview";
            cache.statuses[index] = cell.status;
            cache.summaries[index] = (byte) (HologramSessionPolicy.inScope(s, cell, index) ? 1 : 0);
            if (cache.summaries[index] != 0) cache.total++;
        }
        cache.work = cache.protectedCells = 0;
        cache.pending.addAll(dirty);
        cache.metadataPending = true;
        cache.context(s);
        return true;
    }

    /** Update only owned annotation fields; source NBT and identity decisions remain untouched. */
    static boolean annotations(HologramSession s, Set<Integer> dirty) {
        HologramLocalPlanRefresh cache = s.localEdits;
        if (!cache.current(s) || cache.defaultsPreset != s.virtualPreset
            || cache.source != s.processingSource
            || cache.source == null) return false;
        for (int index : dirty) {
            if (index < 0 || index >= cache.size) return false;
            HologramCapture.Cell cell = s.capture.cells.get(index);
            String key = (cell.x - s.x) + "," + (cell.y - s.y) + "," + (cell.z - s.z);
            if (!cache.sourceIndices.containsKey(key) || s.virtualPreset != null && !cache.defaults.containsKey(key))
                return false;
        }
        for (int index : dirty) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            String key = (cell.x - s.x) + "," + (cell.y - s.y) + "," + (cell.z - s.z);
            HologramPresets.Cell original = cache.defaults.get(key);
            if (original != null) {
                original.prompt = cell.prompt;
                original.excluded = cell.excluded;
            }
            HologramPresets.Cell sourceCell = cache.source.cells.get(cache.sourceIndices.get(key));
            sourceCell.prompt = cell.prompt;
            sourceCell.excluded = cell.excluded;
        }
        cache.pending.addAll(dirty);
        cache.metadataPending = true;
        return true;
    }

    static boolean editedSource(HologramSession s, Set<Integer> dirty) {
        HologramLocalPlanRefresh cache = s.localEdits;
        if (!cache.ready || cache.capture != s.capture || cache.source != s.processingSource || cache.source == null)
            return false;
        try {
            for (int index : dirty) {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                Integer target = cache.sourceIndices.get((cell.x - s.x) + "," + (cell.y - s.y) + "," + (cell.z - s.z));
                if (target == null) return false;
                HologramPresets.Cell before = cache.source.cells.get(target);
                HologramPresets.Cell projected = HologramBlueprintProjection
                    .projectedCell(s, index, cache.source, cache.palette);
                if (java.util.Objects.equals(before.item, projected.item) && before.damage == projected.damage
                    && java.util.Objects.equals(before.block, projected.block)) {
                    projected.tileNbt = before.tileNbt;
                    projected.itemNbt = before.itemNbt;
                }
                cache.source.cells.set(target, projected);
            }
            s.blueprintDirty = true;
            HologramAuthoringWorkflow.clearSourceSwitch(s);
            s.blueprintSaveReceipt = "";
            s.processingReviewed = false;
            s.decorLedger = new HologramDecorMaterials.Ledger();
            HologramBlueprintProcessing.invalidate(s.virtualPreset, "geometry");
            HologramBlueprintProcessing.invalidate(cache.source, "geometry");
            s.processingPolicy = HologramBlueprintProcessing.policy(cache.source);
            s.ae.invalidateReviews();
            return true;
        } catch (IOException invalid) {
            return false;
        }
    }

    static void pending(HologramSession s, Set<Integer> dirty) {
        HologramLocalPlanRefresh cache = s.localEdits;
        cache.simple = false;
        cache.pending.addAll(dirty);
        cache.awaiting.addAll(dirty);
        if (cache.selected >= 0) {
            cache.pending.add(cache.selected);
            cache.awaiting.add(cache.selected);
        }
        if (s.selected >= 0) {
            cache.pending.add(s.selected);
            cache.awaiting.add(s.selected);
        }
        cache.metadataPending = true;
        cache.context(s);
    }

    /** Called once per budgeted classification, never adds an unbounded completion scan. */
    static void classified(HologramSession s, int index) {
        HologramLocalPlanRefresh cache = s.localEdits;
        if (!s.localPlanPending || !cache.ready || cache.capture != s.capture) return;
        int old = cache.summaries[index], now = summary(s, index);
        cache.total += (now & 1) - (old & 1);
        cache.work += ((now & 2) != 0 ? 1 : 0) - ((old & 2) != 0 ? 1 : 0);
        cache.protectedCells += ((now & 4) != 0 ? 1 : 0) - ((old & 4) != 0 ? 1 : 0);
        cache.summaries[index] = (byte) now;
        String status = s.capture.cells.get(index).status;
        if (!java.util.Objects.equals(cache.statuses[index], status)) {
            cache.pending.add(index);
            cache.awaiting.add(index);
        }
        cache.statuses[index] = status;
        boolean complex = complex(s, index);
        if (complex != cache.complexCells[index]) cache.complexCount += complex ? 1 : -1;
        cache.complexCells[index] = complex;
        cache.replaceShell(index, shell(s, index));
    }

    static void completed(HologramSession s) {
        HologramLocalPlanRefresh cache = s.localEdits;
        if (cache.incomplete) {
            cache.incomplete = false;
            cache.invalidate();
            cache.awaiting.clear();
            HologramSnapshots.send(s);
            return;
        }
        cache.pending.addAll(cache.awaiting);
        cache.metadataPending = true;
        cache.simple = s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && s.mode == 0
            && s.planRecoveryFits
            && s.decorPlanItems.isEmpty()
            && s.blueprintEnergy.empty()
            && s.blueprintResources != null
            && empty(s.blueprintResources)
            && cache.complexCount == 0;
        cache.context(s);
        HologramSnapshots.send(s);
    }

    static void failed(HologramSession s) {
        s.localEdits.metadataPending = true;
        sendDelta(s);
    }

    static boolean sendDelta(HologramSession s) {
        NBTTagCompound state = delta(s);
        if (state == null) return false;
        HologramWorkspace.snapshotSink()
            .send(s.player, state);
        return true;
    }

    static NBTTagCompound delta(HologramSession s) {
        HologramLocalPlanRefresh cache = s.localEdits;
        if (!s.cellDeltaProtocol || !cache.ready
            || cache.pending.isEmpty() && !cache.metadataPending
            || cache.capture != s.capture
            || cache.size != s.capture.cells.size()
            || s.open
            || s.cancelled
            || s.capturePending) return null;
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("partial", true);
        state.setBoolean("planIncomplete", s.localPlanPending);
        state.setBoolean("quotesIncomplete", HologramSessionPolicy.authoring(s) || s.localPlanPending);
        state.setString("snapshotKind", "cellDelta");
        state.setString("session", s.id);
        state.setString("generation", s.id);
        state.setLong("baseRevision", cache.revision);
        state.setLong("planRevision", s.planRevision);
        state.setLong("uiSequence", s.uiSequence);
        state.setLong("batchSeq", s.batchSeq);
        state.setString("phase", s.phase);
        state.setInteger("cellCount", cache.size);
        state.setString("status", s.status);
        state.setInteger("job", s.job);
        state.setBoolean("localPlanPending", s.localPlanPending);
        state.setString("localPlanError", s.localPlanError);
        state.setInteger("selected", s.selected);
        state.setInteger("scope", s.scope);
        state.setString("activeStructure", s.blueprintActiveStructure);
        state.setInteger("main", s.main);
        state.setBoolean("noHatches", s.noHatches);
        state.setTag("channels", s.channels.copy());
        state.setBoolean("blueprintDirty", s.blueprintDirty);
        state.setBoolean("blueprintProcessingComplete", s.processingReviewed);
        state.setBoolean("blueprintProcessingRequired", !s.processingReviewed);
        state.setBoolean("editCanUndo", !s.undoEdits.isEmpty());
        state.setBoolean("editCanRedo", !s.redoEdits.isEmpty());
        state.setBoolean("editCanPaste", s.editClipboard != null || !s.blueprintClipboard.isEmpty());
        state.setBoolean("recoveryFits", s.planRecoveryFits);
        state.setInteger("missing", s.planMissing);
        state.setInteger("total", cache.total);
        state.setInteger("workTotal", cache.work);
        state.setInteger("protected", cache.protectedCells);
        state.setTag("progress", HologramSnapshots.progress(s));
        NBTTagCompound processingState = new NBTTagCompound();
        processingState.setBoolean("complete", s.processingReviewed);
        if (s.processingPolicy != null) state
            .setTag("processingConfirmedStages", HologramSessionPolicy.nbtKeys(s.processingPolicy.confirmedStages));
        state.setTag("processingPatch", processingState);
        state.setBoolean("blueprintSavePending", !s.blueprintSaveReceipt.isEmpty());
        state.setBoolean("discardConfirmationRequired", s.discardConfirmationRequired);
        state.setString("discardTargetOp", s.discardTargetOp);
        state.setString("discardTargetId", s.discardTargetId);
        state.setTag("discardTargetAction", s.pendingSourceAction.copy());
        state.setBoolean(
            "processingResumeAllowed",
            s.processingWorkflowStarted && s.processingPolicy != null && !s.processingPolicy.confirmedStages.isEmpty());
        if (s.ae.review == null) state.setTag("aeSaveReview", new NBTTagCompound());
        state.setString(
            "blueprintPrompt",
            s.virtualPreset == null ? s.blueprintPrompt : s.virtualPreset.prompt == null ? "" : s.virtualPreset.prompt);
        NBTTagList hints = new NBTTagList();
        for (HologramPresets.HintArea hint : s.virtualPreset == null ? s.blueprintHintAreas
            : s.virtualPreset.hintAreas) {
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("bounds", new int[] { hint.x1, hint.y1, hint.z1, hint.x2, hint.y2, hint.z2 });
            row.setString("prompt", hint.prompt == null ? "" : hint.prompt);
            hints.appendTag(row);
        }
        state.setTag("hintAreas", hints);
        boolean annotationsOnly = cache.revision == s.planRevision && cache.awaiting.isEmpty() && !s.localPlanPending;
        NBTTagList cells = new NBTTagList();
        for (int index : cache.pending) {
            NBTTagCompound row;
            if (annotationsOnly) {
                row = new NBTTagCompound();
                row.setBoolean("annotationOnly", true);
                row.setString("prompt", s.capture.cells.get(index).prompt);
                row.setBoolean("excluded", s.capture.cells.get(index).excluded);
            } else row = HologramSnapshots.cellRow(s, index);
            row.setInteger("index", index);
            if (s.localPlanPending) {
                row.setString("status", "planning");
                row.setString("reason", "正在更新本次编辑计划");
            }
            cells.appendTag(row);
        }
        state.setTag("cells", cells);
        if (!s.localPlanPending && !annotationsOnly && !HologramSessionPolicy.authoring(s)) {
            NBTTagList materials = new NBTTagList();
            if (cache.simple) {
                for (Map.Entry<ExactKey, Integer> entry : cache.counts.entrySet())
                    materials.appendTag(materialRow(s, cache.identities.get(entry.getKey()), entry.getValue()));
            } else if (s.blueprintTotalResources != null) {
                for (ItemStack item : s.blueprintTotalResources.items)
                    materials.appendTag(materialRow(s, item, item.stackSize));
            }
            state.setTag("materials", materials);
            state.setTag("blueprintResources", HologramResourcePlanner.blueprintResourceRows(s));
            state.setString("blueprintEnergyNotice", s.blueprintEnergyNotice);
            state.setString("blueprintResourceMissing", s.blueprintResourceMissing);
        }
        cache.revision = s.planRevision;
        // A pending-plan delta cannot retire the final quote obligation at this same revision.
        if (!s.localPlanPending) cache.awaiting.clear();
        cache.pending.clear();
        cache.metadataPending = false;
        return state;
    }

    private static NBTTagCompound materialRow(HologramSession s, ItemStack stack, int required) {
        NBTTagCompound row = stack.writeToNBT(new NBTTagCompound());
        row.setInteger("required", required);
        row.setInteger("available", HologramMaterialAccess.shellCount(s, stack, Math.max(4096, s.localEdits.size)));
        row.setInteger(
            "mainAvailable",
            HologramMaterialAccess.materialSource(s)
                .mainCount(stack, Math.max(4096, s.localEdits.size)));
        row.setInteger("controllerAvailable", -1);
        return row;
    }

}
