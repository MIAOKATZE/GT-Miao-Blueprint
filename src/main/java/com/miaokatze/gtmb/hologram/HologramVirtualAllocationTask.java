package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

import gregtech.common.blocks.ItemMachines;

/** Shared reservations and tier choices from allocateVirtualTargets, resumed at individual candidates. */
final class HologramVirtualAllocationTask {

    private final HologramSession s;
    private final Map<String, Map<Integer, List<ItemStack>>> groups = new HashMap<>();
    private final Map<String, Integer> indices = new HashMap<>();
    private final Map<ExactKey, Integer> reserved = new HashMap<>(), counts = new HashMap<>();
    private Iterator<Map.Entry<String, Map<Integer, List<ItemStack>>>> roles;
    private Iterator<List<ItemStack>> tiers;
    private Iterator<ItemStack> samples;
    private List<ItemStack> output, candidates;
    private HologramCapture.Cell cell;
    private ItemStack desired, chosen;
    private String position;
    private int stage, cursor, index, tier, candidate;
    private int groupSamples, orderedSamples;

    HologramVirtualAllocationTask(HologramSession s) {
        this.s = s;
        if (s.allocationsFrozen || s.virtualPreset == null || s.capture.incomplete || s.virtualPreset.schema >= 2)
            stage = 6;
        else if (!s.virtualGroups.isEmpty()) stage = 2;
    }

    boolean done() {
        return stage == 6;
    }

    String phase() {
        return stage == 0 ? "allocation.groups"
            : stage == 1 ? "allocation.order" : stage == 2 ? "allocation.reservations" : "allocation.targets";
    }

    long completed() {
        return stage == 1 ? orderedSamples : cursor - (stage == 4 || stage == 5 ? 1 : 0);
    }

    long total() {
        return stage == 1 ? groupSamples
            : stage == 0 ? s.virtualPreset.palette.size()
                : stage == 2 ? s.capture.cells.size() : s.virtualPreset.cells.size();
    }

    void advance() {
        if (stage == 0) {
            if (cursor < s.virtualPreset.palette.size()) {
                ItemStack sample = s.virtualPreset.palette.get(cursor++)
                    .stack();
                if (sample == null || HologramCellPolicy.hatchTier(sample) == Integer.MAX_VALUE) return;
                String role = ItemMachines.getMetaTileEntity(sample)
                    .getClass()
                    .getName();
                groups.computeIfAbsent(role, ignored -> new TreeMap<>(Collections.reverseOrder()))
                    .computeIfAbsent(HologramCellPolicy.hatchTier(sample), ignored -> new ArrayList<>())
                    .add(sample);
                groupSamples++;
            } else {
                roles = groups.entrySet()
                    .iterator();
                stage = 1;
            }
        } else if (stage == 1) {
            if (samples != null && samples.hasNext()) {
                output.add(samples.next());
                orderedSamples++;
            } else if (tiers != null && tiers.hasNext()) samples = tiers.next()
                .iterator();
            else if (roles.hasNext()) {
                Map.Entry<String, Map<Integer, List<ItemStack>>> role = roles.next();
                output = new ArrayList<>();
                s.virtualGroups.put(role.getKey(), output);
                tiers = role.getValue()
                    .values()
                    .iterator();
            } else {
                cursor = 0;
                stage = 2;
            }
        } else if (stage == 2) {
            if (cursor == s.capture.cells.size()) {
                cursor = 0;
                stage = 3;
                return;
            }
            int i = cursor++;
            HologramCapture.Cell c = s.capture.cells.get(i);
            String key = HologramValues.position(c);
            indices.put(key, i);
            ItemStack manual = s.pins.get(i);
            if (manual != null && s.virtualEdited.contains(key)
                && !HologramCellPolicy.plainTargetInstalled(s, c, manual))
                HologramValues.mergeStack(reserved, manual, 1, Integer::sum);
        } else if (stage == 3) {
            if (cursor == s.virtualPreset.cells.size()) {
                stage = 6;
                return;
            }
            HologramPresets.Cell saved = s.virtualPreset.cells.get(cursor++);
            position = (s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z);
            Integer found = indices.get(position);
            if (found == null || s.virtualEdited.contains(position)) return;
            index = found;
            cell = s.capture.cells.get(index);
            desired = saved.stack();
            if (desired == null) return;
            chosen = desired;
            tier = HologramCellPolicy.hatchTier(desired);
            if (s.hatchDowngrade && tier != Integer.MAX_VALUE
                && !HologramCellPolicy.plainTargetInstalled(s, cell, desired)) {
                String role = ItemMachines.getMetaTileEntity(desired)
                    .getClass()
                    .getName();
                candidates = s.virtualGroups.getOrDefault(role, Collections.emptyList());
                candidate = 0;
                stage = 4;
            } else commit();
        } else if (stage == 4) {
            if (candidate == candidates.size()) {
                candidate = 0;
                stage = 5;
                return;
            }
            ItemStack option = candidates.get(candidate++);
            if (HologramCellPolicy.hatchTier(option) <= tier
                && HologramCellPolicy.plainTargetInstalled(s, cell, option)) {
                chosen = option;
                commit();
            }
        } else if (stage == 5) {
            if (candidate == candidates.size()) {
                commit();
                return;
            }
            ItemStack option = candidates.get(candidate++);
            if (HologramCellPolicy.hatchTier(option) > tier
                || HologramCellPolicy.legalFacings(s, cell, option).length == 0) return;
            ExactKey key = HologramValues.lookupKey(option);
            Integer available = counts.get(key);
            if (available == null) {
                available = s.player.capabilities.isCreativeMode ? HologramCapture.LIMIT
                    : HologramMaterialAccess.materialSource(s)
                        .count(option, Math.max(4096, s.capture.cells.size()));
                counts.put(HologramValues.stackKey(option), available);
            }
            if (available > reserved.getOrDefault(key, 0)) {
                chosen = option;
                commit();
            }
        }
    }

    private void commit() {
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
        stage = 3;
    }
}
