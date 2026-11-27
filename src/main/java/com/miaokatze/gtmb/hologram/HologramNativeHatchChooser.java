// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

/** Additional manual chooser offers, following the native browser's unfilled-controller item query. */
final class HologramNativeHatchChooser {

    private static final int MAX_OPTIONS = 128, MAX_CATALOG_ROWS = 65_536;
    private final Supplier<List<ItemStack>> catalog;
    private final QueryFactory factory;
    private Query query;
    private boolean queryUnavailable;
    private final Map<QueryKey, List<ItemStack>> cached = new LinkedHashMap<>();

    private static final class QueryKey {

        final IStructureElement<?> element;
        final int x, y, z, count;
        final HologramBlueprintResources.ExactKey signal;
        final long policy = HologramHatchPolicy.automaticGeneration();
        final long adapters = HologramDeclarationAdapters.generation();

        QueryKey(IStructureElement<?> element, int x, int y, int z, ItemStack trigger) {
            this.element = element;
            this.x = x;
            this.y = y;
            this.z = z;
            count = trigger.stackSize;
            signal = HologramValues.stackKey(trigger);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof QueryKey key && element == key.element
                && x == key.x
                && y == key.y
                && z == key.z
                && count == key.count
                && signal.equals(key.signal)
                && policy == key.policy
                && adapters == key.adapters;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(System.identityHashCode(element), x, y, z, count, signal, policy, adapters);
        }
    }

    private static List<ItemStack> copies(List<ItemStack> values) {
        List<ItemStack> result = new ArrayList<>(values.size());
        for (ItemStack stack : values) result.add(stack.copy());
        return result;
    }

    HologramNativeHatchChooser(Supplier<List<ItemStack>> catalog, QueryFactory factory) {
        this.catalog = catalog;
        this.factory = factory;
    }

    List<ItemStack> offers(IStructureElement<?> root, int x, int y, int z, ItemStack trigger) {
        if (queryUnavailable || root == null || trigger == null) return Collections.emptyList();
        QueryKey key = new QueryKey(root, x, y, z, trigger);
        List<ItemStack> previous = cached.get(key);
        if (previous != null) return copies(previous);
        // The public query owns contextual/channel/chain semantics. A reviewed factory name is
        // supplementary role evidence, never permission to ask an ordinary native element.
        Map<HologramBlueprintResources.ExactKey, ItemStack> result = new LinkedHashMap<>();
        try {
            read(root, x, y, z, trigger, result);
        } catch (RuntimeException | LinkageError unavailable) {
            // A chooser failure supplies no candidate; full configured native replay is still required.
        }
        List<ItemStack> values = new ArrayList<>(result.values());
        if (cached.size() < 128) cached.put(key, values);
        return copies(values);
    }

    private void read(IStructureElement<?> element, int x, int y, int z, ItemStack trigger,
        Map<HologramBlueprintResources.ExactKey, ItemStack> result) {
        if (queryUnavailable) return;
        if (query == null) try {
            query = factory.create();
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            queryUnavailable = true;
            return;
        }
        IStructureElement.BlocksToPlace blocks = query.get(element, x, y, z, trigger.copy());
        if (blocks == null) return;
        Iterable<ItemStack> listed = blocks.getStacks();
        if (listed != null) {
            int listedRows = 0;
            for (ItemStack stack : listed) {
                if (++listedRows > MAX_OPTIONS) break;
                if (stack == null || stack.getItem() == null
                    || HologramCellPolicy.hatchTier(stack) == Integer.MAX_VALUE) continue;
                ItemStack copy = stack.copy();
                copy.stackSize = 1;
                result.putIfAbsent(HologramValues.stackKey(copy), copy);
            }
        }
        if (listed != null && !HologramNativeStructurePreview.isChain(element)) return;
        Predicate<ItemStack> predicate = blocks.getPredicate();
        if (predicate == null) return;
        int rows = 0, failures = 0;
        for (ItemStack candidate : catalog.get()) {
            if (rows++ >= MAX_CATALOG_ROWS || result.size() >= MAX_OPTIONS) break;
            if (HologramCellPolicy.hatchTier(candidate) == Integer.MAX_VALUE) continue;
            try {
                ItemStack sample = HologramNativePreviewItems.match(predicate, candidate);
                if (sample != null) result.putIfAbsent(HologramValues.stackKey(sample), sample);
            } catch (RuntimeException | LinkageError unavailable) {
                // A broken optional candidate is not a reason to invent acceptance or discard earlier offers.
                if (++failures >= 8) break;
            }
        }
    }

    void clear() {
        cached.clear();
        queryUnavailable = false;
        query = null;
    }

    @FunctionalInterface
    interface Query {

        IStructureElement.BlocksToPlace get(IStructureElement<?> element, int x, int y, int z, ItemStack trigger);
    }

    @FunctionalInterface
    interface QueryFactory {

        Query create() throws IOException;
    }
}
