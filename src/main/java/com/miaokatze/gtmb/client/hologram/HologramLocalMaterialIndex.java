// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.hologram.HologramControllerMaterialCounts;
import com.miaokatze.gtmb.hologram.HologramStaticMaterials;

/**
 * Capture-local resource index, assembled in the existing owner-thread target preparation pass.
 * Opening a material pane never walks geometry or calls a native provider. Exact target tags remain
 * distinct; appearance NBT is never used as retained resource data. This is not a payment capability.
 */
final class HologramLocalMaterialIndex {

    @Desugar
    record Result(NBTTagCompound theory, int targets) {}

    @Desugar
    // Cell stacks are capture-owned and read-only (ControllerTarget.Cell and State.Cell). Borrow the
    // exact identity from that sealed source rather than retaining another complete input NBT tree.
    // Native providers receive a copy below; edits publish a different cell/source, never mutate this key.
    private record Key(Block block, int metadata, Item item, int damage, NBTTagCompound tag, boolean unresolved) {}

    private static final class Unit {
        final HologramStaticMaterials.Contribution resolved;

        Unit(Key key) {
            HologramStaticMaterials.Builder value = new HologramStaticMaterials.Builder();
            if (key.unresolved || key.block == null || key.item == null) {
                value.unaccounted("部分格缺少可识别的目标材料");
                resolved = value.freeze();
                return;
            }
            ItemStack target = new ItemStack(key.item, 1, key.damage);
            if (key.tag != null) target.setTagCompound((NBTTagCompound) key.tag.copy());
            try {
                // Native targets contain the item payload only, exactly as the old estimator did.
                // A displayed tile is never a retained tile and cannot become a bill.
                value.add(key.block, key.metadata, target, null, target.getTagCompound());
            } catch (IOException | RuntimeException | LinkageError unavailable) {
                value = new HologramStaticMaterials.Builder();
                value.unaccounted("部分目标的材料身份或原生资源无法在本机核算");
            }
            resolved = value.freeze();
        }
    }

    private static final class Group {
        final Map<Unit, Integer> counts;
        int targets;
        private Result result;

        Group() { counts = new LinkedHashMap<>(); }

        Group(Group original) {
            counts = new LinkedHashMap<>(original.counts);
            targets = original.targets;
        }

        void add(Unit unit, int delta) {
            int next = Math.addExact(counts.getOrDefault(unit, 0), delta);
            if (next < 0) throw new IllegalStateException("Missing exact material contribution");
            if (next == 0) counts.remove(unit);
            else counts.put(unit, next);
            targets = Math.addExact(targets, delta);
            if (targets < 0) throw new IllegalStateException("Invalid material target count");
            result = null;
        }

        Result result() {
            if (result == null) {
                HologramStaticMaterials.Builder total = new HologramStaticMaterials.Builder();
                counts.forEach((unit, count) -> total.addResolved(unit.resolved, count));
                result = new Result(total.snapshot(), targets);
            }
            return result;
        }
    }

    private final long nativeGeneration, resourceGeneration;
    private final int x, y, z;
    private final Map<Key, Unit> units;
    private final Group all;
    private final Map<Integer, Group> layers;
    private final Map<String, Group> families;

    private HologramLocalMaterialIndex(Builder builder) {
        nativeGeneration = builder.nativeGeneration;
        resourceGeneration = builder.resourceGeneration;
        x = builder.x; y = builder.y; z = builder.z;
        units = builder.units;
        all = builder.all;
        layers = builder.layers;
        families = builder.families;
        // Publish the full resolved total together with its source, before a material pane can open.
        all.result();
        layers.values().forEach(Group::result);
        families.values().forEach(Group::result);
    }

    boolean current() {
        return nativeGeneration == HologramStaticMaterials.nativeGeneration()
            && resourceGeneration == ResourceContainers.generation();
    }

    Result select(List<HologramState.Cell> cells, int scope, int layer, int selected, int[] selection,
        Result selectedMaterials) {
        if (scope == 0) return all.result();
        if (scope == 1) return result(layers.get(layer));
        if (scope == 3) {
            String family = selected >= 0 && selected < cells.size() ? cells.get(selected).family : "";
            return family.isEmpty() ? result(null) : result(families.get(family));
        }
        if (scope != 2) return result(null);
        if (selection.length != 0) return selectedMaterials;
        Selection one = new Selection();
        if (selected >= 0 && selected < cells.size()) one.change(cells.get(selected), 1);
        return one.result();
    }

    /** Selection edits merge already-priced facts in their existing mutation pass, never at pane open. */
    final class Selection {
        private Group chosen = new Group();

        void change(HologramState.Cell cell, int delta) {
            if (!included(cell, x, y, z)) return;
            Unit unit = units.get(key(cell));
            if (unit == null) throw new IllegalStateException("Material index does not match selected source");
            chosen.add(unit, delta);
        }

        void clear() { chosen = new Group(); }
        Result result() { return chosen.result(); }
    }

    private static Result result(Group group) { return group == null ? new Group().result() : group.result(); }

    HologramLocalMaterialIndex edited(List<HologramState.Cell> previous, List<HologramState.Cell> current,
        int[] changedIndices) {
        Builder next = new Builder(this);
        for (HologramState.Cell cell : previous) next.add(cell, -1);
        for (int index : changedIndices) next.add(current.get(index), 1);
        return next.seal();
    }

    private static Key key(HologramState.Cell cell) {
        ItemStack target = cell.targetStack;
        return new Key(cell.block(false), cell.wantMeta, target == null ? null : target.getItem(),
            target == null ? 0 : target.getItemDamage(), target == null ? null : target.getTagCompound(),
            target == null || cell.missingBlock || cell.iconOnly);
    }

    private static boolean included(HologramState.Cell cell, int x, int y, int z) {
        return !cell.excluded && cell.inScope && !cell.anchor && !"minecraft:air".equals(cell.wantId)
            && !(cell.x == x && cell.y == y && cell.z == z)
            && !HologramControllerMaterialCounts.sourceWater(cell.capturedNativeTarget(), x, y, z);
    }

    static final class Builder {
        private final int x, y, z;
        private final long nativeGeneration, resourceGeneration;
        private final Map<Key, Unit> units;
        private final Group all;
        private final Map<Integer, Group> layers;
        private final Map<String, Group> families;
        private final Set<Integer> ownLayers = new HashSet<>();
        private final Set<String> ownFamilies = new HashSet<>();
        private boolean sealed;

        Builder(NBTTagCompound metadata) {
            nativeGeneration = HologramStaticMaterials.nativeGeneration();
            resourceGeneration = ResourceContainers.generation();
            x = metadata.getInteger("x"); y = metadata.getInteger("y"); z = metadata.getInteger("z");
            units = new HashMap<>();
            all = new Group();
            layers = new HashMap<>();
            families = new HashMap<>();
        }

        private Builder(HologramLocalMaterialIndex original) {
            nativeGeneration = original.nativeGeneration;
            resourceGeneration = original.resourceGeneration;
            x = original.x; y = original.y; z = original.z;
            units = new HashMap<>(original.units);
            all = new Group(original.all);
            layers = new HashMap<>(original.layers);
            families = new HashMap<>(original.families);
        }

        void add(HologramState.Cell cell) { add(cell, 1); }

        private void add(HologramState.Cell cell, int delta) {
            if (sealed) throw new IllegalStateException("Material index already published");
            if (!included(cell, x, y, z)) return;
            Key key = key(cell);
            Unit unit = units.get(key);
            if (unit == null) {
                if (delta < 0) throw new IllegalStateException("Missing old material identity");
                unit = new Unit(key);
                units.put(key, unit);
            }
            all.add(unit, delta);
            if (ownLayers.add(cell.dy)) {
                Group previous = layers.get(cell.dy);
                layers.put(cell.dy, previous == null ? new Group() : new Group(previous));
            }
            layers.get(cell.dy).add(unit, delta);
            if (!cell.family.isEmpty()) {
                if (ownFamilies.add(cell.family)) {
                    Group previous = families.get(cell.family);
                    families.put(cell.family, previous == null ? new Group() : new Group(previous));
                }
                families.get(cell.family).add(unit, delta);
            }
        }

        HologramLocalMaterialIndex seal() {
            if (sealed) throw new IllegalStateException("Material index already published");
            sealed = true;
            // Point edits do not retain resources that have disappeared from the current source.
            units.values().removeIf(unit -> !all.counts.containsKey(unit));
            layers.values().removeIf(group -> group.targets == 0);
            families.values().removeIf(group -> group.targets == 0);
            return new HologramLocalMaterialIndex(this);
        }
    }
}
