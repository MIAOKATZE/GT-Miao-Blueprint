// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.SortedRegistry;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import com.gtnewhorizon.structurelib.util.InventoryIterable;
import com.gtnewhorizon.structurelib.util.InventoryUtility;
import com.gtnewhorizon.structurelib.util.InventoryUtility.InventoryProvider;

/** StructureLib item-source adapters used by the authoritative transaction boundary. */
final class HologramMaterialSources {

    private HologramMaterialSources() {}

    /** Read the native player order without changing StructureLib's global preferences. */
    static List<InventoryProvider<?>> inventoryProviders(EntityPlayerMP player) {
        // Ensure SL has installed its built-in providers before looking up the named registry.
        InventoryUtility.getStackExtractors(player);
        SortedRegistry<?> registry = SortedRegistry.getRegistry("inventoryProviders");
        if (registry == null) throw new IllegalStateException("Native inventory provider registry unavailable");
        List<InventoryProvider<?>> result = new java.util.ArrayList<>();
        for (Object value : registry.getPlayerOrdering(player)) {
            if (!(value instanceof InventoryProvider<?>))
                throw new IllegalStateException("Invalid native inventory provider");
            result.add((InventoryProvider<?>) value);
        }
        return result;
    }

    /** SL's registered main provider owns the package-private main-slot range constructor. */
    static Iterable<ItemStack> mainInventory(EntityPlayerMP player) {
        InventoryUtility.getStackExtractors(player);
        SortedRegistry<?> registry = SortedRegistry.getRegistry("inventoryProviders");
        Object main = registry == null ? null : registry.get("5000-main-inventory");
        if (!(main instanceof InventoryProvider<?>provider))
            throw new IllegalStateException("Native inventory provider registry unavailable");
        Iterable<ItemStack> inventory = provider.getInventory(player);
        return inventory == null ? Collections.emptyList() : inventory;
    }

    static boolean mainInventoryEnabled(EntityPlayerMP player) {
        InventoryUtility.getStackExtractors(player);
        SortedRegistry<?> registry = SortedRegistry.getRegistry("inventoryProviders");
        if (registry == null) throw new IllegalStateException("Native inventory provider registry unavailable");
        Object main = registry.get("5000-main-inventory");
        for (Object provider : registry.getPlayerOrdering(player)) if (provider == main) return true;
        return false;
    }

    static final class NativeInventory {

        final InventoryProvider<Iterable<ItemStack>> provider;
        final Iterable<ItemStack> inventory;
        final boolean main;

        @SuppressWarnings("unchecked")
        NativeInventory(InventoryProvider<?> provider, EntityPlayerMP player) {
            this.provider = (InventoryProvider<Iterable<ItemStack>>) provider;
            inventory = this.provider.getInventory(player);
            main = inventory instanceof InventoryIterable<?>
                && ((InventoryIterable<?>) inventory).getInventory() == player.inventory;
        }

        boolean contains(EntityPlayerMP player, ItemStack source) {
            Iterable<ItemStack> current = provider.getInventory(player);
            if (current != null) for (ItemStack stack : current) if (stack == source) return true;
            return false;
        }

        void markDirty() {
            provider.markDirty(inventory);
        }
    }

    /** A native iterable view: removal still reaches the underlying inventory, with no copied extraction loop. */
    static Iterable<ItemStack> filtered(Iterable<ItemStack> source, java.util.function.Predicate<ItemStack> allowed) {
        return () -> new java.util.Iterator<ItemStack>() {

            final java.util.Iterator<ItemStack> nativeIterator = source.iterator();
            ItemStack next;
            boolean ready;

            public boolean hasNext() {
                while (!ready && nativeIterator.hasNext()) {
                    ItemStack candidate = nativeIterator.next();
                    if (candidate != null && allowed.test(candidate)) {
                        next = candidate;
                        ready = true;
                    }
                }
                return ready;
            }

            public ItemStack next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                ItemStack result = next;
                ready = false;
                next = null;
                return result;
            }

            public void remove() {
                nativeIterator.remove();
            }
        };
    }

    static final class FilteredSource implements IItemSource {

        final IItemSource source;
        final ItemStack pin;

        FilteredSource(IItemSource source, ItemStack pin) {
            this.source = source;
            this.pin = pin;
        }

        public boolean takeOne(ItemStack stack, boolean simulate) {
            return HologramValues.same(stack, pin) && source.takeOne(stack, simulate);
        }

        public boolean takeAll(ItemStack stack, boolean simulate) {
            return HologramValues.same(stack, pin) && source.takeAll(stack, simulate);
        }

        public Map<ItemStack, Integer> take(java.util.function.Predicate<ItemStack> predicate, boolean simulate,
            int count) {
            return source.take(stack -> HologramValues.same(stack, pin) && predicate.test(stack), simulate, count);
        }
    }

    static final class ShellSource implements IItemSource {

        final IItemSource source;

        ShellSource(IItemSource source) {
            this.source = source;
        }

        private boolean shell(ItemStack stack) {
            Block block = Block.getBlockFromItem(stack.getItem());
            return block != Blocks.air && (!block.hasTileEntity(stack.getItemDamage())
                || HologramFrameSupport.inertFrameBlock(block, stack.getItemDamage()));
        }

        public boolean takeOne(ItemStack stack, boolean simulate) {
            return shell(stack) && source.takeOne(stack, simulate);
        }

        public boolean takeAll(ItemStack stack, boolean simulate) {
            return shell(stack) && source.takeAll(stack, simulate);
        }

        public Map<ItemStack, Integer> take(java.util.function.Predicate<ItemStack> predicate, boolean simulate,
            int count) {
            return source.take(stack -> shell(stack) && predicate.test(stack), simulate, count);
        }
    }

    static final class ReservedSource implements IItemSource {

        final ItemStack reserved;
        boolean used;

        ReservedSource(ItemStack stack) {
            reserved = stack.copy();
            reserved.stackSize = 1;
        }

        public Map<ItemStack, Integer> take(java.util.function.Predicate<ItemStack> predicate, boolean simulate,
            int count) {
            if (used || count != 1 || !predicate.test(reserved)) return Collections.emptyMap();
            if (!simulate) used = true;
            return Collections.singletonMap(reserved.copy(), 1);
        }
    }

    static final class CreativeSource implements IItemSource {

        final List<ItemStack> options;

        CreativeSource(List<ItemStack> options) {
            this.options = options;
        }

        public boolean takeOne(ItemStack stack, boolean simulate) {
            return stack != null && stack.getItem() != null && stack.stackSize == 1;
        }

        public boolean takeAll(ItemStack stack, boolean simulate) {
            return stack != null && stack.getItem() != null && stack.stackSize > 0 && stack.stackSize <= 4096;
        }

        public Map<ItemStack, Integer> take(java.util.function.Predicate<ItemStack> predicate, boolean simulate,
            int count) {
            if (count < 1 || count > 4096) return Collections.emptyMap();
            for (ItemStack option : options) if (predicate.test(option)) {
                ItemStack stack = option.copy();
                stack.stackSize = count;
                return Collections.singletonMap(stack, count);
            }
            return Collections.emptyMap();
        }
    }
}
