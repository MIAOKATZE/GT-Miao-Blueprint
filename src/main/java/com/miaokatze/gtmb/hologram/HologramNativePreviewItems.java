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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizon.structurelib.structure.IItemSource;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Creative inventory semantics over local registry data, with preview-owned copies of all returned stacks. */
final class HologramNativePreviewItems implements IItemSource {

    private final List<ItemStack> preferred = new ArrayList<>();
    private List<ItemStack> catalog;
    private final NBTTagCompound nativeConfig;
    private final java.util.Set<String> mandatory;
    private final java.util.function.Supplier<Map<Integer, Integer>> installed;
    private ItemStack approved;
    private final Map<String, Integer> pinned = new java.util.LinkedHashMap<>();
    private final Map<String, Integer> pinnedFacings = new java.util.LinkedHashMap<>();
    private final Map<Integer, Integer> profile = new java.util.LinkedHashMap<>();
    private final Map<String, Integer> mandatoryModels = new java.util.LinkedHashMap<>();
    private String position = "";
    private HologramNativePreviewWorld world;

    HologramNativePreviewItems() {
        this(null, Collections.emptySet(), Collections::emptyMap);
    }

    HologramNativePreviewItems(NBTTagCompound config, java.util.Set<String> mandatory,
        java.util.function.Supplier<Map<Integer, Integer>> installed) {
        nativeConfig = config == null ? null : (NBTTagCompound) config.copy();
        this.mandatory = mandatory;
        this.installed = installed;
        if (config == null) return;
        NBTTagList choices = config.getTagList("nativeHatchChoices", 10);
        for (int n = 0; n < choices.tagCount(); n++) {
            NBTTagCompound row = choices.getCompoundTagAt(n);
            String at = HologramNativeTargetPlan
                .position(row.getInteger("x"), row.getInteger("y"), row.getInteger("z"));
            pinned.put(at, row.getInteger("id"));
            pinnedFacings.put(at, row.hasKey("facing", 3) ? row.getInteger("facing") : -1);
        }
        NBTTagList entries = config.getCompoundTag("hatchProfile")
            .getTagList("entries", 10);
        for (int n = 0; n < entries.tagCount(); n++) {
            NBTTagCompound row = entries.getCompoundTagAt(n);
            ItemStack item = HologramHatchProfiles.stack(row);
            if (item == null || !(item.getItem() instanceof ItemMachines)
                || item.hasTagCompound()
                || row.getInteger("count") < 1
                || row.getInteger("count") > 512) throw new IllegalArgumentException("原生仓室搭配含无效型号或数量。");
            if (item != null && item.getItem() instanceof ItemMachines)
                profile.merge(item.getItemDamage(), row.getInteger("count"), Integer::sum);
        }
        if (config.hasKey("hatchProfile", 10) && profile.isEmpty()) throw new IllegalArgumentException("原生仓室搭配为空。");
        entries = config.getTagList("nativeMandatoryDefaults", 10);
        for (int n = 0; n < entries.tagCount(); n++) {
            NBTTagCompound row = entries.getCompoundTagAt(n);
            mandatoryModels.put(row.getString("position"), row.getInteger("id"));
        }
    }

    void bind(HologramNativePreviewWorld world) {
        this.world = world;
    }

    private void selected(ItemStack stack, boolean simulate) {
        if (world != null) world.materialSelected(stack, simulate);
    }

    void position(String value) {
        position = value;
    }

    @Override
    public Map<ItemStack, Integer> take(Predicate<ItemStack> predicate, boolean simulate, int count) {
        if (count <= 0) return Collections.emptyMap();
        if (nativeConfig != null) {
            for (ItemStack stack : catalog()) {
                ItemStack sample = match(predicate, stack);
                if (sample != null && nativeAllows(sample)) {
                    selected(sample, simulate);
                    approved = simulate ? sample.copy() : null;
                    return Collections.singletonMap(sample, count);
                }
            }
            return Collections.emptyMap();
        }
        for (ItemStack stack : preferred) {
            ItemStack sample = match(predicate, stack);
            if (sample != null) {
                selected(sample, simulate);
                return Collections.singletonMap(sample, Integer.MAX_VALUE);
            }
        }
        for (ItemStack stack : catalog()) {
            ItemStack sample = match(predicate, stack);
            if (sample == null) continue;
            preferred.add(stack);
            selected(sample, simulate);
            return Collections.singletonMap(sample, Integer.MAX_VALUE);
        }
        return Collections.emptyMap();
    }

    @Override
    public boolean takeOne(ItemStack stack, boolean simulate) {
        boolean accepted = stack != null && stack.getItem() != null
            && (nativeConfig == null || !simulate && approved != null && ItemStack.areItemStacksEqual(approved, stack)
                || nativeAllows(stack));
        if (accepted) selected(stack, simulate);
        if (accepted && nativeConfig != null) approved = simulate ? stack.copy() : null;
        return accepted;
    }

    @Override
    public boolean takeAll(ItemStack stack, boolean simulate) {
        return takeOne(stack, simulate);
    }

    List<ItemStack> catalog() {
        if (catalog != null) return catalog;
        long started = HologramRuntimeMetrics.start();
        try {
            catalog = nativeConfig == null ? readNeiCatalog() : null;
            if (catalog == null) {
                catalog = new ArrayList<>();
                readRegistry(catalog, nativeConfig != null);
            }
            if (nativeConfig != null) {
                catalog.sort(java.util.Comparator.comparingInt((ItemStack stack) -> {
                    int tier = HologramCellPolicy.hatchTier(stack);
                    return nativeConfig.getBoolean("presetActive") && tier != Integer.MAX_VALUE ? -tier : tier;
                })
                    .thenComparing(stack -> String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())))
                    .thenComparingInt(ItemStack::getItemDamage));
            }
            return catalog;
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_CATALOG, started);
        }
    }

    private boolean nativeAllows(ItemStack stack) {
        int tier = HologramCellPolicy.hatchTier(stack);
        if (tier == Integer.MAX_VALUE) return true;
        int id = stack.getItemDamage();
        Integer fixed = pinned.get(position);
        if (fixed != null) return fixed == id;
        Map<Integer, Integer> placed = installed.get();
        if (!nativeConfig.getBoolean("presetActive")) return pinned.isEmpty() || mandatory.contains(position);
        if (!profile.isEmpty()) return profile.containsKey(id) ? placed.getOrDefault(id, 0) < profile.get(id)
            : Integer.valueOf(id)
                .equals(mandatoryModels.get(position));
        int wanted = nativeConfig.getInteger("hatchTier");
        return tier == wanted || nativeConfig.getBoolean("hatchDowngrade") && tier <= wanted;
    }

    void requireConfiguredChoices(List<HologramNativeTargetPlan.Cell> cells) throws java.io.IOException {
        Map<String, Integer> actual = new java.util.LinkedHashMap<>();
        Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
        for (var cell : cells) if (HologramCellPolicy.hatchTier(cell.material()) != Integer.MAX_VALUE) {
            int id = cell.material()
                .getItemDamage();
            actual.put(HologramNativeTargetPlan.position(cell.dx(), cell.dy(), cell.dz()), id);
            counts.merge(id, 1, Integer::sum);
            int wanted = pinnedFacings
                .getOrDefault(HologramNativeTargetPlan.position(cell.dx(), cell.dy(), cell.dz()), -1);
            if (wanted >= 0 && wanted != cell.facing()) throw new java.io.IOException("所选仓室方向与原生放置结果不同。");
        }
        for (var entry : pinned.entrySet()) if (!entry.getValue()
            .equals(actual.get(entry.getKey()))) throw new java.io.IOException("所选仓室未被该位置的原生建造流程接受。");
        for (var entry : profile.entrySet()) if (!entry.getValue()
            .equals(counts.getOrDefault(entry.getKey(), 0))) throw new java.io.IOException("所选仓室搭配未被完整放入原生目标。");
    }

    private static void readRegistry(List<ItemStack> result, boolean nativeMode) {
        for (int id = 0; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity machine = GregTechAPI.METATILEENTITIES[id];
            if (machine != null)
                add(result, nativeMode ? new ItemStack(GregTechAPI.sBlockMachines, 1, id) : machine.getStackForm(1));
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
            if (!itemList.getField("loadFinished")
                .getBoolean(null)) return null;
            Object items = itemList.getField("items")
                .get(null);
            if (items instanceof List<?>source && !source.isEmpty()) {
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
