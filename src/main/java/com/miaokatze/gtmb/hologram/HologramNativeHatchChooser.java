// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

/** Additional manual chooser offers, following the native browser's unfilled-controller item query. */
final class HologramNativeHatchChooser {

    private static final int MAX_OPTIONS = 128, MAX_DECLARATIONS = 128, MAX_CATALOG_ROWS = 65_536;
    private static final int MAX_QUERIES = 64, MAX_SKIPPED = 256;
    private final Supplier<List<ItemStack>> catalog;
    private final QueryFactory factory;
    private final Map<IStructureElement<?>, List<ItemStack>> offers = new IdentityHashMap<>();
    private final Map<IStructureElement<?>, Boolean> skipped = new IdentityHashMap<>();
    private ItemStack previousTrigger;
    private Query query;
    private boolean queryUnavailable;
    private int queries;

    HologramNativeHatchChooser(Supplier<List<ItemStack>> catalog, QueryFactory factory) {
        this.catalog = catalog;
        this.factory = factory;
    }

    List<ItemStack> offers(IStructureElement<?> root, int x, int y, int z, ItemStack trigger) {
        if (queryUnavailable) return Collections.emptyList();
        if (previousTrigger == null || !ItemStack.areItemStacksEqual(previousTrigger, trigger)) {
            previousTrigger = trigger.copy();
            offers.clear();
            skipped.clear();
        }
        List<ItemStack> memo = offers.get(root);
        if (memo != null) return memo;
        if (queries >= MAX_QUERIES || skipped.containsKey(root)) return Collections.emptyList();

        // One chooser is scoped to one captured geometry/trigger. Only plain atLeast queries are cached:
        // their builder callback consumes controller counts and the item, not coordinates or the passed world.
        int before = queries;
        Map<HologramBlueprintResources.ExactKey, ItemStack> result = new LinkedHashMap<>();
        collect(root, x, y, z, trigger, 0, new IdentityHashMap<>(), new int[] { MAX_DECLARATIONS }, result);
        List<ItemStack> options = result.isEmpty() ? Collections.emptyList() : new ArrayList<>(result.values());
        if (queries > before) offers.put(root, options);
        else if (skipped.size() < MAX_SKIPPED) skipped.put(root, Boolean.TRUE);
        return options;
    }

    private void collect(IStructureElement<?> element, int x, int y, int z, ItemStack trigger, int depth,
        IdentityHashMap<IStructureElement<?>, Boolean> path, int[] remaining,
        Map<HologramBlueprintResources.ExactKey, ItemStack> result) {
        if (element == null || depth > 8 || remaining[0]-- <= 0 || result.size() >= MAX_OPTIONS
            || queries >= MAX_QUERIES
            || path.put(element, Boolean.TRUE) != null) return;
        try {
            if (HologramDeclarationAdapters.declaration(element) != null) return;
            if (HologramHatchPolicy.nativeBrowseAtLeast(element)) {
                read(element, x, y, z, trigger, result);
            } else if (element instanceof IStructureElementChain<?> chain) {
                IStructureElement<?>[] children = HologramDeclarationWrappers.nativeChainChildren(chain);
                if (children != null && children.length <= 64) for (IStructureElement<?> child : children)
                    collect(child, x, y, z, trigger, depth + 1, path, remaining, result);
            } else {
                Method method = HologramDeclarationWrappers.factory(element.getClass());
                if (method == null || method.getDeclaringClass() != StructureUtility.class) return;
                switch (method.getName()) {
                    case "withChannel" -> {
                        String name = HologramDeclarationWrappers.channel(element);
                        if (name != null)
                            collect(HologramDeclarationWrappers.nativeBacking(element), x, y, z,
                                ChannelDataAccessor.withChannel(trigger, name), depth + 1, path, remaining, result);
                    }
                    case "onElementPass", "onElementFail", "withDescription" ->
                        collect(HologramDeclarationWrappers.nativeBacking(element), x, y, z, trigger, depth + 1,
                            path, remaining, result);
                    default -> {}
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Keep the exact positives of independent branches. Do not reconstruct an opaque predicate.
        } finally {
            path.remove(element);
        }
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
        queries++;
        IStructureElement.BlocksToPlace blocks = query.get(element, x, y, z, trigger.copy());
        Predicate<ItemStack> predicate = blocks == null ? null : blocks.getPredicate();
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
        offers.clear();
        skipped.clear();
        previousTrigger = null;
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
