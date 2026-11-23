// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IItemSource;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Creative inventory semantics over local registry data, with preview-owned copies of all returned stacks. */
final class HologramNativePreviewItems implements IItemSource {

    private final List<ItemStack> preferred = new ArrayList<>();
    private List<ItemStack> catalog;

    @Override
    public Map<ItemStack, Integer> take(Predicate<ItemStack> predicate, boolean simulate, int count) {
        if (count <= 0) return Collections.emptyMap();
        for (ItemStack stack : preferred) {
            ItemStack sample = match(predicate, stack);
            if (sample != null) return Collections.singletonMap(sample, Integer.MAX_VALUE);
        }
        for (ItemStack stack : catalog()) {
            ItemStack sample = match(predicate, stack);
            if (sample == null) continue;
            preferred.add(stack);
            return Collections.singletonMap(sample, Integer.MAX_VALUE);
        }
        return Collections.emptyMap();
    }

    @Override
    public boolean takeOne(ItemStack stack, boolean simulate) {
        return stack != null && stack.getItem() != null;
    }

    @Override
    public boolean takeAll(ItemStack stack, boolean simulate) {
        return takeOne(stack, simulate);
    }

    List<ItemStack> catalog() {
        if (catalog != null) return catalog;
        long started = HologramRuntimeMetrics.start();
        try {
            catalog = readNeiCatalog();
            if (catalog == null) {
                catalog = new ArrayList<>();
                readRegistry(catalog);
            }
            return catalog;
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_CATALOG, started);
        }
    }

    private static void readRegistry(List<ItemStack> result) {
        for (IMetaTileEntity machine : GregTechAPI.METATILEENTITIES) {
            if (machine != null) add(result, machine.getStackForm(1));
        }
        for (Object entry : Item.itemRegistry) {
            if (!(entry instanceof ItemBlock item) || item instanceof ItemMachines) continue;
            List<ItemStack> items = new ArrayList<>();
            try {
                item.getSubItems(item, item.getCreativeTab(), items);
            } catch (RuntimeException | LinkageError unavailable) {
                // One unenumerable item must not discard the rest of the local catalog.
            }
            if (items.isEmpty()) items.add(new ItemStack(item, 1, 0));
            for (ItemStack stack : items) add(result, stack);
        }
    }

    private static List<ItemStack> readNeiCatalog() {
        try {
            Class<?> itemList = Class.forName("codechicken.nei.ItemList");
            if (!itemList.getField("loadFinished").getBoolean(null)) return null;
            Object items = itemList.getField("items").get(null);
            if (items instanceof List<?> source && !source.isEmpty()) {
                // NEI publishes a new ordered list when it loads or reorders items. Freeze the order for this
                // capture, and copy individual item/NBT payloads only when evaluating their predicates.
                List<ItemStack> result = new ArrayList<>(source.size());
                for (Object item : source) {
                    if (item instanceof ItemStack stack && stack.getItem() != null) result.add(stack);
                }
                if (!result.isEmpty()) return result;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // The preview can still use the local registries when NEI is absent or the catalog is reloading.
        }
        return null;
    }

    private static void add(List<ItemStack> result, ItemStack stack) {
        if (stack == null || stack.getItem() == null) return;
        ItemStack sample = stack.copy();
        sample.stackSize = 1;
        result.add(sample);
    }

    static ItemStack match(Predicate<ItemStack> predicate, ItemStack stack) {
        ItemStack sample = stack.copy();
        sample.stackSize = 1;
        return predicate.test(sample) ? sample : null;
    }
}
