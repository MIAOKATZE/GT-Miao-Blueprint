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

    /** Two payable projections of the same complete, exact-NBT demand and inventory observation. */
    static final class Cache {

        final String session;
        final long revision;
        final HologramCapture capture;
        final NBTTagCompound ordinary, voucher;
        final long eligibleDemand;

        Cache(HologramSession s, NBTTagCompound ordinary, NBTTagCompound voucher) {
            session = s.id;
            revision = s.planRevision;
            capture = s.capture;
            this.ordinary = ordinary;
            this.voucher = voucher;
            Map<HologramBlueprintResources.ExactKey, Long> reduced = new LinkedHashMap<>();
            NBTTagList waivedRows = voucher.getTagList("rows", 10);
            for (int i = 0; i < waivedRows.tagCount(); i++) {
                NBTTagCompound row = waivedRows.getCompoundTagAt(i);
                ItemStack item = readItem(row);
                if ("item".equals(row.getString("kind")) && item != null)
                    reduced.put(new HologramBlueprintResources.ExactKey(item, null), quantity(row, "required"));
            }
            long demand = 0;
            NBTTagList normalRows = ordinary.getTagList("rows", 10);
            for (int i = 0; i < normalRows.tagCount(); i++) {
                NBTTagCompound row = normalRows.getCompoundTagAt(i);
                if (!"item".equals(row.getString("kind"))) continue;
                ItemStack item = readItem(row);
                if (item == null) continue;
                long waived = Math.max(
                    0L,
                    quantity(row, "required")
                        - reduced.getOrDefault(new HologramBlueprintResources.ExactKey(item, null), 0L));
                demand = Math.addExact(demand, waived);
            }
            eligibleDemand = demand;
        }

        boolean current(HologramSession s) {
            return session.equals(s.id) && revision == s.planRevision
                && capture == s.capture
                && !s.localPlanPending
                && !HologramSessionPolicy.authoring(s);
        }
    }

    /** Memoizes physical stock by complete identity, never by block or item/damage alone. */
    static final class StockSnapshot implements ItemStock {

        final ItemStock source;
        final Map<HologramBlueprintResources.ExactKey, Observation> exact = new LinkedHashMap<>(),
            fuzzy = new LinkedHashMap<>();
        final Map<HologramBlueprintResources.ExactKey, Observation> fluids = new LinkedHashMap<>();
        final Map<String, Observation> essentia = new LinkedHashMap<>();
        BigInteger energy;

        StockSnapshot(ItemStock source) {
            this.source = source;
        }

        public long exact(ItemStack item, long limit) {
            return observed(
                exact,
                new HologramBlueprintResources.ExactKey(item, null),
                limit,
                () -> source.exact(item, limit));
        }

        public long fuzzy(ItemStack item, long limit) {
            return observed(
                fuzzy,
                new HologramBlueprintResources.ExactKey(item, null),
                limit,
                () -> source.fuzzy(item, limit));
        }

        long fluid(HologramSession s, FluidStack sample, long limit) {
            if (sample == null) return 0;
            return observed(
                fluids,
                new HologramBlueprintResources.ExactKey(null, sample),
                limit,
                () -> HologramMaterialAccess.materialSource(s)
                    .fluidCount(sample, limit));
        }

        long essentia(HologramSession s, String aspect, long limit) {
            return observed(
                essentia,
                aspect,
                limit,
                () -> HologramMaterialAccess.materialSource(s)
                    .essentiaCount(aspect, limit));
        }

        private static final class Observation {

            final long limit, amount;

            Observation(long limit, long amount) {
                this.limit = limit;
                this.amount = Math.max(0L, Math.min(limit, amount));
            }
        }

        private static <K> long observed(Map<K, Observation> observations, K key, long limit,
            java.util.function.LongSupplier query) {
            if (limit <= 0) return 0;
            Observation previous = observations.get(key);
            if (previous == null || limit > previous.limit && previous.amount == previous.limit) {
                // A larger simulation replaces the earlier capped observation; it never adds the same stock twice.
                previous = new Observation(limit, query.getAsLong());
                observations.put(key, previous);
            }
            return Math.min(limit, previous.amount);
        }

        ItemStock cachedItems() {
            return new ItemStock() {

                public long exact(ItemStack item, long limit) {
                    return cached(exact, new HologramBlueprintResources.ExactKey(item, null), limit);
                }

                public long fuzzy(ItemStack item, long limit) {
                    return cached(fuzzy, new HologramBlueprintResources.ExactKey(item, null), limit);
                }
            };
        }

        private static <K> long cached(Map<K, Observation> observations, K key, long limit) {
            if (limit <= 0) return 0;
            Observation known = observations.get(key);
            if (known == null || limit > known.limit && known.amount == known.limit)
                throw new IllegalStateException("Quote allocation requested an unprepared exact-NBT stock budget");
            return Math.min(limit, known.amount);
        }

        BigInteger energy(HologramSession s) {
            if (energy == null) energy = HologramMaterialAccess.materialSource(s)
                .energyAvailable()
                .max(BigInteger.ZERO);
            return energy;
        }
    }

    static ItemStock stock(HologramSession s) {
        return new ItemStock() {

            public long exact(ItemStack item, long limit) {
                return HologramMaterialAccess.materialSource(s)
                    .countLong(item, limit);
            }

            public long fuzzy(ItemStack item, long limit) {
                return HologramMaterialAccess.materialSource(s)
                    .countFuzzyLong(item, limit);
            }
        };
    }

    static boolean cached(HologramSession s) {
        return s.materialQuoteCache != null && s.materialQuoteCache.current(s);
    }

    static NBTTagList voucherRows(NBTTagList ordinary) {
        NBTTagList result = new NBTTagList();
        for (int i = 0; i < ordinary.tagCount(); i++) {
            NBTTagCompound row = (NBTTagCompound) ordinary.getCompoundTagAt(i)
                .copy();
            long waived = Math.min(quantity(row, "required"), quantity(row, "voucherShellRequired"));
            row.setLong("required", quantity(row, "required") - waived);
            row.setLong("shellRequired", Math.max(0L, quantity(row, "shellRequired") - waived));
            if (row.getLong("required") > 0) result.appendTag(row);
        }
        return result;
    }

    static void mergeRows(NBTTagList rows, Collection<NBTTagCompound> additions) {
        Map<HologramBlueprintResources.ExactKey, NBTTagCompound> index = new LinkedHashMap<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            ItemStack item = ItemStack.loadItemStackFromNBT(row);
            if (item != null) index.put(new HologramBlueprintResources.ExactKey(item, null), row);
        }
        for (NBTTagCompound addition : additions) {
            ItemStack item = ItemStack.loadItemStackFromNBT(addition);
            if (item == null) continue;
            HologramBlueprintResources.ExactKey key = new HologramBlueprintResources.ExactKey(item, null);
            NBTTagCompound row = index.get(key);
            if (row == null) {
                row = (NBTTagCompound) addition.copy();
                rows.appendTag(row);
                index.put(key, row);
            } else row.setLong("required", Math.addExact(quantity(row, "required"), quantity(addition, "required")));
        }
    }

    static void project(HologramSession s) throws IOException {
        if (!cached(s)) throw new IOException("材料需求缓存已失效，请等待完整计划");
        s.materialQuote = (NBTTagCompound) (s.jobVoucherRequested ? s.materialQuoteCache.voucher
            : s.materialQuoteCache.ordinary).copy();
        stamp(s, s.materialQuote);
        long missing = 0;
        NBTTagList rows = s.materialQuote.getTagList("rows", 10);
        for (int i = 0; i < rows.tagCount(); i++) missing = Math.min(
            Integer.MAX_VALUE,
            missing + Math.min(
                Integer.MAX_VALUE,
                Math.max(
                    0L,
                    rows.getCompoundTagAt(i)
                        .getLong("missing"))));
        s.planMissing = s.player.capabilities.isCreativeMode ? 0 : (int) missing;
        if (s.planRecoveryFits && s.materialQuote.hasKey("firstMissing", 8))
            s.blueprintResourceMissing = s.materialQuote.getString("firstMissing");
    }

    private static void stamp(HologramSession s, NBTTagCompound quote) {
        quote.setString("session", s.id);
        quote.setLong("planRevision", s.planRevision);
        quote.setLong("placementReviewNonce", s.placementReviewNonce);
        quote.setBoolean("useDecorationVoucher", s.jobVoucherRequested);
        quote.setBoolean("creativePaymentsFree", s.player.capabilities.isCreativeMode);
        if (!quote.hasKey("inventoryCheck", 8)) quote.setString("inventoryCheck", "cached");
        quote.setString("validation", "库存以确认施工时为准");
    }

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
        return snapshot(s, materialRows, voucherRows(materialRows), resourceRows, new StockSnapshot(stock(s)));
    }

    static NBTTagCompound snapshot(HologramSession s, NBTTagList materials, NBTTagList voucherMaterials,
        NBTTagList resources, StockSnapshot inventory) {
        Preparation preparation = new Preparation(s, materials, voucherMaterials, resources, inventory);
        while (!preparation.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
        return preparation.result();
    }

    private static List<NBTTagCompound> rows(NBTTagList rows) {
        List<NBTTagCompound> result = new ArrayList<>();
        for (int i = 0; i < rows.tagCount(); i++) result.add(rows.getCompoundTagAt(i));
        return result;
    }

    /** Main-thread quote preparation: native stock calls are separate from pure shared-budget allocation. */
    static final class Preparation {

        private final HologramSession s;
        private final Thread owner = Thread.currentThread();
        private final String session;
        private final long revision;
        private final HologramCapture capture;
        private final NBTTagList materials, voucherMaterials, resources;
        private final List<NBTTagCompound> ordinaryRows, waivedRows;
        private final StockSnapshot inventory;
        private final RecordingStock requirements = new RecordingStock();
        private final Predicate<ItemStack> fuzzy;
        private QuoteCounter counter;
        private NBTTagCompound ordinary, voucher, result;
        private NBTTagList wireRows;
        private int stage, index;
        private long completedWork;

        Preparation(HologramSession s, NBTTagList materials, NBTTagList voucherMaterials, NBTTagList resources,
            StockSnapshot inventory) {
            this.s = s;
            session = s.id;
            revision = s.planRevision;
            capture = s.capture;
            this.materials = materials;
            this.voucherMaterials = voucherMaterials;
            this.resources = resources;
            this.inventory = inventory;
            ordinaryRows = rows(materials);
            waivedRows = rows(voucherMaterials);
            fuzzy = item -> HologramMaterialAccess.fuzzyBlueprintShells(s, item);
            counter = new QuoteCounter(ordinaryRows, requirements, fuzzy);
        }

        boolean step(int maxWork, long deadline) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Quote stock requires owner thread");
            if (!done() && (!session.equals(s.id) || revision != s.planRevision || capture != s.capture))
                throw new IllegalStateException("Obsolete quote preparation cannot observe or publish a new plan");
            int work = 0;
            while (!done() && work < maxWork && (work == 0 || System.nanoTime() < deadline)) {
                advance();
                completedWork++;
                work++;
            }
            return done();
        }

        boolean done() {
            return stage == 8;
        }

        int completed() {
            return index;
        }

        long completedWork() {
            return completedWork;
        }

        int total() {
            if (stage == 0 || stage == 3) return ordinaryRows.size();
            if (stage == 1 || stage == 4) return waivedRows.size();
            if (stage == 2) return requirements.queries.size();
            if (stage == 5 || stage == 6) return wireRows == null ? 0 : wireRows.tagCount();
            return 1;
        }

        String phase() {
            switch (stage) {
                case 0:
                    return "quote.demands.ordinary";
                case 1:
                    return "quote.demands.voucher";
                case 2:
                    return "quote.inventory";
                case 3:
                    return "quote.allocate.ordinary";
                case 4:
                    return "quote.allocate.voucher";
                case 5:
                    return "quote.resources.ordinary";
                case 6:
                    return "quote.resources.voucher";
                default:
                    return "quote.publish";
            }
        }

        NBTTagCompound result() {
            if (!done()) throw new IllegalStateException("Quote preparation incomplete");
            return (NBTTagCompound) result.copy();
        }

        private void advance() {
            if (stage < 7 && index >= total()) {
                stage++;
                index = 0;
                if (stage == 1) counter = new QuoteCounter(waivedRows, requirements, fuzzy);
                else if (stage == 3) counter = new QuoteCounter(ordinaryRows, inventory.cachedItems(), fuzzy);
                else if (stage == 4) counter = new QuoteCounter(waivedRows, inventory.cachedItems(), fuzzy);
                else if (stage == 5) {
                    ordinary = fromRows(materials, resources);
                    wireRows = ordinary.getTagList("rows", 10);
                } else if (stage == 6) {
                    voucher = fromRows(voucherMaterials, resources);
                    wireRows = voucher.getTagList("rows", 10);
                }
                // Publish the new phase's real 0/total before its first potentially blocking native call.
                return;
            }
            if (stage <= 1) {
                // Returning the requested bound proves the largest protected-stock request for either ledger.
                counter.count((stage == 0 ? ordinaryRows : waivedRows).get(index++));
            } else if (stage == 2) {
                StockRequest query = requirements.queries.get(index++);
                if (query.fuzzy) inventory.fuzzy(query.sample, query.limit);
                else inventory.exact(query.sample, query.limit);
            } else if (stage <= 4) {
                NBTTagCompound row = (stage == 3 ? ordinaryRows : waivedRows).get(index++);
                row.setLong("available", counter.count(row));
            } else if (stage <= 6) {
                observeResource(s, wireRows.getCompoundTagAt(index++), inventory);
            } else {
                completeQuote(s, ordinary);
                completeQuote(s, voucher);
                s.materialQuoteCache = new Cache(s, ordinary, voucher);
                try {
                    project(s);
                } catch (IOException incomplete) {
                    s.materialQuote = s.jobVoucherRequested ? voucher : ordinary;
                }
                result = (NBTTagCompound) s.materialQuote.copy();
                index = 1;
                stage = 8;
            }
        }
    }

    private static final class StockRequest {

        final ItemStack sample;
        final boolean fuzzy;
        long limit;

        StockRequest(ItemStack sample, long limit, boolean fuzzy) {
            this.sample = sample.copy();
            this.limit = limit;
            this.fuzzy = fuzzy;
        }
    }

    private static final class RecordingStock implements ItemStock {

        final Map<HologramBlueprintResources.ExactKey, StockRequest> exact = new LinkedHashMap<>(),
            fuzzy = new LinkedHashMap<>();
        final List<StockRequest> queries = new ArrayList<>();

        public long exact(ItemStack item, long limit) {
            return record(item, limit, false);
        }

        public long fuzzy(ItemStack item, long limit) {
            return record(item, limit, true);
        }

        private long record(ItemStack item, long limit, boolean shell) {
            if (limit <= 0) return 0;
            Map<HologramBlueprintResources.ExactKey, StockRequest> pool = shell ? fuzzy : exact;
            HologramBlueprintResources.ExactKey key = new HologramBlueprintResources.ExactKey(item, null);
            StockRequest known = pool.get(key);
            if (known == null) {
                known = new StockRequest(item, limit, shell);
                pool.put(key, known);
                queries.add(known);
            } else known.limit = Math.max(known.limit, limit);
            return limit;
        }
    }

    private static void observeResource(HologramSession s, NBTTagCompound row, StockSnapshot inventory) {
        if ("fluid".equals(row.getString("kind")))
            counts(row, row.getLong("required"), inventory.fluid(s, readFluid(row), row.getLong("required")));
        else if ("essentia".equals(row.getString("kind"))) counts(
            row,
            row.getLong("required"),
            inventory.essentia(s, row.getString("aspect"), row.getLong("required")));
    }

    private static void completeQuote(HologramSession s, NBTTagCompound quote) {
        stamp(s, quote);
        quote.setBoolean("complete", !HologramSessionPolicy.authoring(s) && !s.localPlanPending);
        quote.setLong("inventorySnapshotTick", s.player.worldObj.getTotalWorldTime());
    }

    /** Recheck only cached payable identities. No cell/world traversal, plan rebuild or render-payload encoding. */
    static void recheck(HologramSession s) throws IOException {
        if (s.materialQuote == null || !s.materialQuote.getBoolean("complete")
            || s.materialQuote.getLong("planRevision") != s.planRevision
            || !s.id.equals(s.materialQuote.getString("session"))) throw new IOException("材料需求缓存已失效，请重新打开材料确认");
        StockSnapshot inventory = new StockSnapshot(stock(s));
        NBTTagCompound ordinary = (NBTTagCompound) (cached(s) ? s.materialQuoteCache.ordinary : s.materialQuote).copy();
        NBTTagCompound voucher = (NBTTagCompound) (cached(s) ? s.materialQuoteCache.voucher : s.materialQuote).copy();
        recount(s, ordinary, inventory);
        recount(s, voucher, inventory);
        s.materialQuoteCache = new Cache(s, ordinary, voucher);
        project(s);
    }

    private static void recount(HologramSession s, NBTTagCompound quote, StockSnapshot inventory) throws IOException {
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
        QuoteCounter counter = new QuoteCounter(
            legacy,
            inventory,
            item -> HologramMaterialAccess.fuzzyBlueprintShells(s, item));
        String firstMissing = "";
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            String kind = row.getString("kind");
            long required = row.getLong("required");
            if ("item".equals(kind)) counts(row, required, counter.count(linked.get(row)));
            else if ("fluid".equals(kind)) {
                FluidStack fluid = readFluid(row);
                counts(row, required, inventory.fluid(s, fluid, required));
            } else if ("essentia".equals(kind))
                counts(row, required, inventory.essentia(s, row.getString("aspect"), required));
            else if ("energy".equals(kind)) {
                BigInteger exactRequired = new BigInteger(row.getString("requiredExact"));
                BigInteger available = inventory.energy(s);
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
            if (shortage > 0 && firstMissing.isEmpty()) firstMissing = row.getString("label") + " 缺 "
                + (row.hasKey("missingExact", 8) ? row.getString("missingExact") : Long.toString(shortage))
                + " "
                + row.getString("unit");
        }
        quote.setString("inventoryCheck", "authoritative");
        quote.setLong("inventorySnapshotTick", s.player.worldObj.getTotalWorldTime());
        quote.setString("firstMissing", firstMissing);
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
                existing.setBoolean("voucherEligible", quantity(source, "voucherShellRequired") > 0);
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
