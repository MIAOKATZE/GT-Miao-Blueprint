package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

/** Detached construction quote. Item identity and quantities deliberately have separate wire fields. */
public final class HologramMaterialQuote {

    private HologramMaterialQuote() {}

    interface ItemStock {

        long exact(ItemStack item, long limit);

        long fuzzy(ItemStack item, long limit);
    }

    /** Allocates shared fuzzy shell stock only after protecting each exact contents demand. */
    static final class QuoteCounter {

        private final ItemStock stock;
        private final Map<NBTTagCompound, Long> available = new IdentityHashMap<>();
        private final Map<ShellIdentity, List<NBTTagCompound>> groups = new LinkedHashMap<>();
        private final Predicate<ItemStack> fuzzy;

        QuoteCounter(HologramSession s, Collection<NBTTagCompound> rows) {
            this(rows, new ItemStock() {

                @Override
                public long exact(ItemStack item, long limit) {
                    return HologramMaterialAccess.materialSource(s)
                        .countLong(item, limit);
                }

                @Override
                public long fuzzy(ItemStack item, long limit) {
                    return HologramMaterialAccess.materialSource(s)
                        .countFuzzyLong(item, limit);
                }
            }, item -> HologramMaterialAccess.fuzzyBlueprintShells(s, item));
        }

        QuoteCounter(Collection<NBTTagCompound> rows, ItemStock stock, Predicate<ItemStack> fuzzy) {
            this.stock = stock;
            this.fuzzy = fuzzy;
            for (NBTTagCompound row : rows) {
                ItemStack item = ItemStack.loadItemStackFromNBT(row);
                if (item != null) groups.computeIfAbsent(new ShellIdentity(item), ignored -> new ArrayList<>())
                    .add(row);
            }
        }

        long count(NBTTagCompound row) {
            Long counted = available.get(row);
            if (counted != null) return counted;
            ItemStack item = ItemStack.loadItemStackFromNBT(row);
            if (item == null) return 0;
            List<NBTTagCompound> group = groups.remove(new ShellIdentity(item));
            if (group == null) return 0;
            boolean coordinatePayments = false;
            for (NBTTagCompound member : group)
                coordinatePayments |= HologramMaterialReservations.priority(ItemStack.loadItemStackFromNBT(member)) > 0;
            if (coordinatePayments) {
                countCoordinates(group);
                return available.getOrDefault(row, 0L);
            }
            ItemStack fuzzySample = null;
            long totalDemand = 0;
            for (NBTTagCompound member : group) {
                ItemStack identity = ItemStack.loadItemStackFromNBT(member);
                totalDemand = Math.addExact(totalDemand, quantity(member, "required"));
                if (quantity(member, "shellRequired") > 0 && fuzzy.test(identity)) fuzzySample = identity;
            }
            if (fuzzySample == null) {
                for (NBTTagCompound member : group) {
                    ItemStack identity = ItemStack.loadItemStackFromNBT(member);
                    long demand = quantity(member, "required");
                    available.put(member, Math.max(0, Math.min(demand, stock.exact(identity, demand))));
                }
            } else {
                long protectedStock = 0;
                Predicate<ItemStack> shell = HologramMaterials.shellPredicate(fuzzySample);
                for (NBTTagCompound member : group) {
                    ItemStack identity = ItemStack.loadItemStackFromNBT(member);
                    long shellDemand = fuzzy.test(identity) ? quantity(member, "shellRequired") : 0;
                    long exactDemand = Math.max(0L, quantity(member, "required") - shellDemand);
                    long paid = exactDemand == 0 ? 0
                        : Math.max(0, Math.min(exactDemand, stock.exact(identity, exactDemand)));
                    available.put(member, paid);
                    if (shell.test(identity)) protectedStock = Math.addExact(protectedStock, paid);
                }
                long pool = Math.max(0L, stock.fuzzy(fuzzySample, totalDemand) - protectedStock);
                for (NBTTagCompound member : group) {
                    ItemStack identity = ItemStack.loadItemStackFromNBT(member);
                    long shellDemand = fuzzy.test(identity) ? quantity(member, "shellRequired") : 0;
                    long paid = Math.min(pool, shellDemand);
                    available.put(member, Math.addExact(available.get(member), paid));
                    pool -= paid;
                }
            }
            return available.getOrDefault(row, 0L);
        }

        private void countCoordinates(List<NBTTagCompound> group) {
            for (NBTTagCompound member : group) {
                ItemStack identity = ItemStack.loadItemStackFromNBT(member);
                if (HologramMaterialReservations.priority(identity) != 0) continue;
                long demand = quantity(member, "required");
                available.put(member, Math.max(0L, Math.min(demand, stock.exact(identity, demand))));
            }
            for (int priority = 1; priority <= 2; priority++) {
                Map<HologramBlueprintResources.ExactKey, List<NBTTagCompound>> pools = new LinkedHashMap<>();
                for (NBTTagCompound member : group) {
                    ItemStack identity = ItemStack.loadItemStackFromNBT(member);
                    if (HologramMaterialReservations.priority(identity) == priority) pools
                        .computeIfAbsent(
                            HologramMaterialReservations.key(identity, priority),
                            ignored -> new ArrayList<>())
                        .add(member);
                }
                for (List<NBTTagCompound> members : pools.values()) {
                    ItemStack expected = ItemStack.loadItemStackFromNBT(members.get(0));
                    long demand = 0, protectedStock = 0;
                    for (NBTTagCompound member : members) demand = Math.addExact(demand, quantity(member, "required"));
                    for (NBTTagCompound member : group) {
                        Long paid = available.get(member);
                        if (paid != null && HologramCoordinatePolicies
                            .paymentEquivalent(expected, ItemStack.loadItemStackFromNBT(member)))
                            protectedStock = Math.addExact(protectedStock, paid);
                    }
                    long proven = Math.max(0L, stock.exact(expected, Math.addExact(demand, protectedStock)));
                    long pool = Math.max(0L, proven - Math.min(proven, protectedStock));
                    for (NBTTagCompound member : members) {
                        long paid = Math.min(pool, quantity(member, "required"));
                        available.put(member, paid);
                        pool -= paid;
                    }
                }
            }
        }
    }

    private static final class ShellIdentity {

        final Item item;
        final int damage;

        ShellIdentity(ItemStack stack) {
            item = stack.getItem();
            damage = stack.getItemDamage();
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(item) * 31 + damage;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ShellIdentity && item == ((ShellIdentity) other).item
                && damage == ((ShellIdentity) other).damage;
        }
    }

    /**
     * Called only when publishing a completed material review, never by a progress tick. The caller supplies the
     * existing aggregated material rows; no world/cell traversal or item inventory enumeration is performed here.
     */
    static NBTTagCompound snapshot(HologramSession s, NBTTagList materialRows, NBTTagList resourceRows) {
        NBTTagCompound quote = fromRows(materialRows, resourceRows);
        NBTTagList rows = quote.getTagList("rows", 10);
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            if ("fluid".equals(row.getString("kind"))) {
                FluidStack fluid = readFluid(row);
                counts(
                    row,
                    row.getLong("required"),
                    fluid == null ? 0
                        : HologramMaterialAccess.materialSource(s)
                            .fluidCount(fluid, row.getLong("required")));
            } else if ("essentia".equals(row.getString("kind"))) {
                counts(
                    row,
                    row.getLong("required"),
                    HologramMaterialAccess.materialSource(s)
                        .essentiaCount(row.getString("aspect"), row.getLong("required")));
            }
        }
        quote.setString("session", s.id);
        quote.setLong("planRevision", s.planRevision);
        quote.setLong("placementReviewNonce", s.placementReviewNonce);
        quote.setBoolean("useDecorationVoucher", s.jobVoucherRequested);
        quote.setBoolean("complete", !HologramSessionPolicy.authoring(s) && !s.localPlanPending);
        s.materialQuote = (NBTTagCompound) quote.copy();
        return quote;
    }

    /** Recheck only cached payable identities. No cell/world traversal, plan rebuild or render-payload encoding. */
    static void recheck(HologramSession s) throws IOException {
        if (s.materialQuote == null || !s.materialQuote.getBoolean("complete")
            || s.materialQuote.getLong("planRevision") != s.planRevision
            || !s.id.equals(s.materialQuote.getString("session"))) throw new IOException("材料需求缓存已失效，请重新打开材料确认");
        NBTTagCompound quote = (NBTTagCompound) s.materialQuote.copy();
        NBTTagList rows = quote.getTagList("rows", 10);
        List<NBTTagCompound> legacy = new ArrayList<>();
        Map<NBTTagCompound, NBTTagCompound> linked = new IdentityHashMap<>();
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            if (!"item".equals(row.getString("kind"))) continue;
            ItemStack item = readItem(row);
            if (item == null) throw new IOException("材料物品身份已失效，不能继续确认");
            NBTTagCompound nativeRow = item.writeToNBT(new NBTTagCompound());
            nativeRow.setLong("required", row.getLong("required"));
            nativeRow.setLong("shellRequired", row.getLong("shellRequired"));
            legacy.add(nativeRow);
            linked.put(row, nativeRow);
        }
        QuoteCounter counter = new QuoteCounter(s, legacy);
        long missing = 0;
        String firstMissing = "";
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            String kind = row.getString("kind");
            long required = row.getLong("required");
            if ("item".equals(kind)) counts(row, required, counter.count(linked.get(row)));
            else if ("fluid".equals(kind)) {
                FluidStack fluid = readFluid(row);
                counts(
                    row,
                    required,
                    fluid == null ? 0
                        : HologramMaterialAccess.materialSource(s)
                            .fluidCount(fluid, required));
            } else if ("essentia".equals(kind)) counts(
                row,
                required,
                HologramMaterialAccess.materialSource(s)
                    .essentiaCount(row.getString("aspect"), required));
            else if ("energy".equals(kind)) {
                BigInteger exactRequired = new BigInteger(row.getString("requiredExact"));
                BigInteger available = HologramMaterialAccess.materialSource(s)
                    .energyAvailable()
                    .max(BigInteger.ZERO);
                BigInteger shortage = exactRequired.subtract(available)
                    .max(BigInteger.ZERO);
                row.setString("availableExact", available.toString());
                row.setString("missingExact", shortage.toString());
                counts(
                    row,
                    required,
                    available.min(BigInteger.valueOf(Long.MAX_VALUE))
                        .longValue());
                row.setLong(
                    "missing",
                    shortage.min(BigInteger.valueOf(Long.MAX_VALUE))
                        .longValue());
            }
            long shortage = row.getLong("missing");
            missing = Math.min(Integer.MAX_VALUE, missing + Math.min(Integer.MAX_VALUE, shortage));
            if (shortage > 0 && firstMissing.isEmpty()) firstMissing = row.getString("label") + " 缺 "
                + (row.hasKey("missingExact", 8) ? row.getString("missingExact") : Long.toString(shortage))
                + " "
                + row.getString("unit");
        }
        s.materialQuote = quote;
        s.planMissing = (int) missing;
        if (s.planRecoveryFits) s.blueprintResourceMissing = firstMissing;
    }

    /** Pure wire conversion, also used by regression fixtures. Does not inspect player inventories or ME. */
    public static NBTTagCompound fromRows(NBTTagList materials, NBTTagList resources) {
        Map<HologramBlueprintResources.ExactKey, NBTTagCompound> items = new LinkedHashMap<>();
        Map<HologramBlueprintResources.ExactKey, NBTTagCompound> fluids = new LinkedHashMap<>();
        Map<String, NBTTagCompound> otherResources = new LinkedHashMap<>();
        NBTTagList rows = new NBTTagList();
        for (int index = 0; index < materials.tagCount(); index++) {
            NBTTagCompound source = materials.getCompoundTagAt(index);
            ItemStack stack = ItemStack.loadItemStackFromNBT(source);
            if (stack == null || stack.getItem() == null) continue;
            long required = quantity(source, "required");
            if (required <= 0) continue;
            HologramBlueprintResources.ExactKey key = new HologramBlueprintResources.ExactKey(stack, null);
            NBTTagCompound existing = items.get(key);
            if (existing == null) {
                existing = itemRow(stack, required, quantity(source, "available"));
                existing.setLong("shellRequired", quantity(source, "shellRequired"));
                existing.setLong("mainAvailable", quantity(source, "mainAvailable"));
                existing.setLong("controllerAvailable", source.getInteger("controllerAvailable"));
                items.put(key, existing);
                rows.appendTag(existing);
            } else {
                long combined = Math.addExact(existing.getLong("required"), required);
                existing.setLong(
                    "shellRequired",
                    Math.addExact(existing.getLong("shellRequired"), quantity(source, "shellRequired")));
                counts(existing, combined, Math.max(existing.getLong("available"), quantity(source, "available")));
            }
        }
        // Item resource entries are already included in materials. Their paths describe provenance, not extra costs.
        // Fluid/essentia/energy rows retain the planner's exact resource path and detached native icon payload.
        for (int index = 0; index < resources.tagCount(); index++) {
            NBTTagCompound source = resources.getCompoundTagAt(index);
            if ("item".equals(source.getString("type"))) {
                ItemStack identity = readItem(source);
                NBTTagCompound material = identity == null ? null
                    : items.get(new HologramBlueprintResources.ExactKey(identity, null));
                if (material != null) appendPath(material, source.getString("key"));
                continue;
            }
            NBTTagCompound row = (NBTTagCompound) source.copy();
            String kind = source.getString("type");
            row.setString("kind", kind);
            row.setString("label", source.getString("name"));
            row.setString("nbtKey", source.getString("key"));
            row.setString("unit", "fluid".equals(kind) ? "mB" : "energy".equals(kind) ? "EU" : "点");
            row.setBoolean("voucherEligible", false);
            if ("energy".equals(kind)) {
                row.setString("requiredExact", exactQuantity(source, "required").toString());
                row.setString("availableExact", exactQuantity(source, "available").toString());
                row.setString("missingExact", exactQuantity(source, "missing").toString());
            }
            counts(row, quantity(source, "required"), quantity(source, "available"));
            if ("energy".equals(kind)) row.setLong(
                "missing",
                exactQuantity(source, "missing").max(BigInteger.ZERO)
                    .min(BigInteger.valueOf(Long.MAX_VALUE))
                    .longValue());
            if (row.getLong("required") <= 0) continue;
            NBTTagCompound previous;
            if ("fluid".equals(kind)) {
                FluidStack fluid = readFluid(row);
                if (fluid == null) continue;
                HologramBlueprintResources.ExactKey key = new HologramBlueprintResources.ExactKey(null, fluid);
                previous = fluids.putIfAbsent(key, row);
            } else previous = otherResources.putIfAbsent(kind + ":" + row.getString("aspect"), row);
            if (previous == null) rows.appendTag(row);
            else {
                if ("energy".equals(kind)) {
                    BigInteger required = new BigInteger(previous.getString("requiredExact"))
                        .add(new BigInteger(row.getString("requiredExact")));
                    BigInteger available = new BigInteger(previous.getString("availableExact"))
                        .max(new BigInteger(row.getString("availableExact")));
                    previous.setString("requiredExact", required.toString());
                    previous.setString("availableExact", available.toString());
                    previous.setString(
                        "missingExact",
                        required.subtract(available)
                            .max(BigInteger.ZERO)
                            .toString());
                    counts(
                        previous,
                        required.min(BigInteger.valueOf(Long.MAX_VALUE))
                            .longValue(),
                        available.min(BigInteger.valueOf(Long.MAX_VALUE))
                            .longValue());
                    previous.setLong(
                        "missing",
                        required.subtract(available)
                            .max(BigInteger.ZERO)
                            .min(BigInteger.valueOf(Long.MAX_VALUE))
                            .longValue());
                } else counts(
                    previous,
                    Math.addExact(previous.getLong("required"), row.getLong("required")),
                    Math.max(previous.getLong("available"), row.getLong("available")));
                appendPath(previous, row.getString("nbtKey"));
            }
        }
        NBTTagCompound quote = new NBTTagCompound();
        quote.setTag("rows", rows);
        quote.setBoolean("complete", true);
        return quote;
    }

    private static void appendPath(NBTTagCompound row, String path) {
        if (path == null || path.isEmpty()) return;
        String existing = row.getString("nbtKey");
        if (!existing.equals(path)) row.setString("nbtKey", existing.isEmpty() ? path : existing + ", " + path);
    }

    public static NBTTagCompound itemRow(ItemStack sample, long required, long available) {
        ItemStack identity = sample.copy();
        identity.stackSize = 1;
        NBTTagCompound row = new NBTTagCompound();
        row.setString("kind", "item");
        row.setTag("item", identity.writeToNBT(new NBTTagCompound()));
        row.setString("itemRegistry", String.valueOf(Item.itemRegistry.getNameForObject(identity.getItem())));
        row.setString("label", identity.getDisplayName());
        row.setString("unit", "个");
        row.setString("nbtKey", "");
        row.setBoolean("voucherEligible", false);
        counts(row, required, available);
        return row;
    }

    /** Stable registry identity wins over the process-local numeric id in ItemStack's native NBT format. */
    public static ItemStack readItem(NBTTagCompound row) {
        String registry = row.getString("itemRegistry");
        Object item = Item.itemRegistry.getObject(registry);
        if (!(item instanceof Item)) return null;
        NBTTagCompound nativeStack = row.getCompoundTag("item");
        ItemStack stack = new ItemStack((Item) item, 1, nativeStack.getShort("Damage"));
        if (nativeStack.hasKey("tag", 10)) stack.setTagCompound(
            (NBTTagCompound) nativeStack.getCompoundTag("tag")
                .copy());
        return stack;
    }

    static FluidStack readFluid(NBTTagCompound row) {
        return row.hasKey("fluid", 10) ? FluidStack.loadFluidStackFromNBT(row.getCompoundTag("fluid")) : null;
    }

    static long quantity(NBTTagCompound row, String field) {
        return exactQuantity(row, field).max(BigInteger.ZERO)
            .min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }

    private static BigInteger exactQuantity(NBTTagCompound row, String field) {
        if (!row.hasKey(field, 8)) return BigInteger.valueOf(row.getLong(field));
        try {
            return new BigInteger(row.getString(field));
        } catch (NumberFormatException invalid) {
            return BigInteger.ZERO;
        }
    }

    private static void counts(NBTTagCompound row, long required, long available) {
        long bounded = Math.max(0, required);
        long stock = Math.max(0, available);
        row.setLong("required", bounded);
        row.setLong("available", stock);
        row.setLong("missing", bounded - Math.min(bounded, stock));
    }
}
