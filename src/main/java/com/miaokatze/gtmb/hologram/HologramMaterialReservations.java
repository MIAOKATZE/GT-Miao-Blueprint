package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

/** Remaining payment budgets and cached native stock pools. No world/cell traversal is performed here. */
final class HologramMaterialReservations {

    private static final Map<Item, Boolean> COORDINATE_ITEMS = new java.util.IdentityHashMap<>();

    interface Stock {

        long count(ItemStack sample, boolean strict);
    }

    private final Map<ExactKey, Long> pending;
    private final Budget budget = new Budget();
    private final Map<ExactKey, Long> strictStock = new HashMap<>(), absoluteStock = new HashMap<>(),
        broadStock = new HashMap<>();
    private final Stock stock;

    HologramMaterialReservations(Map<ExactKey, Long> pending, Stock stock) {
        this.pending = pending;
        this.stock = stock;
        for (Map.Entry<ExactKey, Long> entry : pending.entrySet()) budget.add(sample(entry.getKey()), entry.getValue());
    }

    boolean owns(Map<ExactKey, Long> other) {
        return pending == other;
    }

    Selection selection(Map<ExactKey, Long> current, ItemStack payment, boolean fuzzy) {
        return new Selection(current, payment, fuzzy);
    }

    void committed(Map<ExactKey, Long> paid) {
        for (Map.Entry<ExactKey, Long> entry : paid.entrySet()) budget.add(sample(entry.getKey()), -entry.getValue());
    }

    void invalidateStock() {
        strictStock.clear();
        absoluteStock.clear();
        broadStock.clear();
    }

    void extracted(ItemStack actual, long amount) {
        decrement(strictStock, HologramValues.lookupKey(actual), amount);
        if (coordinate(actual)) {
            decrement(absoluteStock, key(actual, 1), amount);
            decrement(broadStock, key(actual, 2), amount);
        }
    }

    private static void decrement(Map<ExactKey, Long> stocks, ExactKey key, long amount) {
        Long known = stocks.get(key);
        if (known != null) stocks.put(key, Math.max(0L, known - amount));
    }

    private long stock(ItemStack actual, int priority) {
        Map<ExactKey, Long> cache = priority == 0 ? strictStock : priority == 1 ? absoluteStock : broadStock;
        ExactKey key = priority == 0 ? HologramValues.stackKey(actual) : key(actual, priority);
        Long known = cache.get(key);
        if (known == null) {
            known = Math.max(0L, stock.count(priority == 0 ? actual : paymentSample(actual, priority), priority == 0));
            cache.put(key, known);
        }
        return known;
    }

    final class Selection {

        final boolean guarded;
        private final Map<ExactKey, Long> current;
        private final Budget currentBudget = new Budget();
        private final Budget used = new Budget();
        private final Map<ExactKey, Long> usedExact = new HashMap<>();
        private final boolean fuzzy;
        private final int priority;

        Selection(Map<ExactKey, Long> current, ItemStack payment, boolean fuzzy) {
            this.current = current;
            this.fuzzy = fuzzy;
            priority = priority(payment);
            guarded = fuzzy || priority > 0;
            if (priority > 0) for (Map.Entry<ExactKey, Long> entry : current.entrySet())
                currentBudget.add(sample(entry.getKey()), entry.getValue());
        }

        long allowance(ItemStack actual, long limit) {
            if (!guarded || actual == null) return limit;
            ExactKey raw = HologramValues.lookupKey(actual);
            long exactNeed = fuzzy ? pending.getOrDefault(raw, 0L) - current.getOrDefault(raw, 0L)
                : budget.strict.getOrDefault(raw, 0L) - currentBudget.strict.getOrDefault(raw, 0L);
            long result = limit;
            if (exactNeed > 0)
                result = Math.min(result, surplus(stock(actual, 0), exactNeed, usedExact.getOrDefault(raw, 0L)));
            if (priority > 0) {
                ExactKey absolute = key(actual, 1);
                long need = budget.absolute.getOrDefault(absolute, 0L)
                    - currentBudget.absolute.getOrDefault(absolute, 0L);
                if (need > 0) result = Math
                    .min(result, surplus(stock(actual, 1), need, used.absolute.getOrDefault(absolute, 0L)));
                if (priority == 2) {
                    ExactKey broad = key(actual, 2);
                    need = budget.broad.getOrDefault(broad, 0L) - currentBudget.broad.getOrDefault(broad, 0L);
                    if (need > 0)
                        result = Math.min(result, surplus(stock(actual, 2), need, used.broad.getOrDefault(broad, 0L)));
                }
            }
            return result;
        }

        void extracted(ItemStack actual, long amount) {
            HologramValues.mergeStack(usedExact, actual, amount, Long::sum);
            if (coordinate(actual)) {
                used.absolute.merge(key(actual, 1), amount, Long::sum);
                used.broad.merge(key(actual, 2), amount, Long::sum);
            }
        }
    }

    private static long surplus(long stock, long protectedAmount, long alreadySelected) {
        long remainder = stock - Math.min(stock, Math.max(0L, protectedAmount));
        return remainder - Math.min(remainder, Math.max(0L, alreadySelected));
    }

    private static final class Budget {

        final Map<ExactKey, Long> strict = new HashMap<>(), absolute = new HashMap<>(), broad = new HashMap<>();

        void add(ItemStack item, long amount) {
            if (item == null || !coordinate(item) || amount == 0) return;
            int priority = priority(item);
            if (priority == 0) strict.merge(HologramValues.stackKey(item), amount, Long::sum);
            if (priority <= 1) absolute.merge(key(item, 1), amount, Long::sum);
            broad.merge(key(item, 2), amount, Long::sum);
        }
    }

    static int priority(ItemStack item) {
        if (!HologramCoordinatePolicies.reviewedCoordinateItem(item)) return 0;
        return "absolute".equals(
            item.getTagCompound()
                .getString(HologramCoordinatePolicies.PAYMENT)) ? 1 : 2;
    }

    /** Internal placement markers are not native AE outputs; only a proven payable projection may be requested. */
    static ItemStack craftingSample(ItemStack expected) {
        if (!HologramCoordinatePolicies.reviewedCoordinateItem(expected))
            return expected == null ? null : expected.copy();
        ItemStack nativeSample = HologramCoordinatePolicies.normalizePaymentItem(expected);
        return HologramCoordinatePolicies.paymentEquivalent(expected, nativeSample) ? nativeSample : null;
    }

    static boolean coordinate(ItemStack item) {
        if (item == null) return false;
        Boolean known = COORDINATE_ITEMS.get(item.getItem());
        if (known != null) return known;
        ItemStack probe = item.copy();
        NBTTagCompound tag = probe.hasTagCompound() ? probe.getTagCompound() : new NBTTagCompound();
        tag.setString(HologramCoordinatePolicies.PAYMENT, "relative");
        probe.setTagCompound(tag);
        boolean supported = HologramCoordinatePolicies.reviewedCoordinateItem(probe);
        COORDINATE_ITEMS.put(item.getItem(), supported);
        return supported;
    }

    static ExactKey key(ItemStack item, int priority) {
        ItemStack normalized = item.copy();
        if (normalized.hasTagCompound()) {
            NBTTagCompound tag = HologramCoordinatePolicies.normalizePaymentTag(
                item.getItem()
                    .getClass()
                    .getName(),
                normalized.getTagCompound(),
                priority == 2);
            normalized.setTagCompound(tag.hasNoTags() ? null : tag);
        }
        return HologramValues.stackKey(normalized);
    }

    private static ItemStack paymentSample(ItemStack actual, int priority) {
        ItemStack result = sample(key(actual, priority));
        if (!result.hasTagCompound()) result.setTagCompound(new NBTTagCompound());
        result.getTagCompound()
            .setString(HologramCoordinatePolicies.PAYMENT, priority == 1 ? "absolute" : "relative");
        return result;
    }

    private static ItemStack sample(ExactKey key) {
        if (!(key.type instanceof Item)) return null;
        ItemStack sample = new ItemStack((Item) key.type, 1, key.meta);
        if (key.tag != null) sample.setTagCompound((NBTTagCompound) key.tag.copy());
        return sample;
    }
}
