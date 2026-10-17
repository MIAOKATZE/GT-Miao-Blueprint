package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.api.ResourceContainers;

/** Costs of the selected, final payload. Inspection never edits the captured or placement NBT. */
public final class HologramBlueprintResources {

    private HologramBlueprintResources() {}

    public static final class FluidCost {

        public final FluidStack stack;
        public long amount;
        public final String key;

        private FluidCost(FluidStack stack, long amount, String key) {
            this.stack = stack.copy();
            this.stack.amount = 1;
            this.amount = amount;
            this.key = key;
        }
    }

    private static final class Entry {

        final String key;
        final ItemStack item;
        final FluidStack fluid;
        final String essentia;
        BigInteger amount;

        Entry(String key, ItemStack item, FluidStack fluid, BigInteger amount) {
            this(key, item, fluid, null, amount);
        }

        Entry(String key, ItemStack item, FluidStack fluid, String essentia, BigInteger amount) {
            this.key = key;
            this.item = item == null ? null : item.copy();
            this.fluid = fluid == null ? null : fluid.copy();
            this.essentia = essentia;
            this.amount = amount;
        }
    }

    /**
     * An isolated accumulator for staged imports. Its result is exclusively owned until publication;
     * public Plan lists keep their existing mutable semantics and never share these indexes.
     */
    static final class Accumulator {

        final Plan plan = new Plan();
        private final java.util.Map<ExactKey, ItemStack> items = new java.util.HashMap<>();
        private final java.util.Map<ExactKey, FluidCost> fluids = new java.util.HashMap<>();
        private final java.util.Map<String, HologramEssentia.Cost> essentia = new java.util.HashMap<>();
        private final java.util.Map<RowKey, Entry> entries = new java.util.HashMap<>();

        Merge merge(Plan source) {
            return new Merge(source);
        }

        void addItem(ItemStack stack) throws IOException {
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return;
            ExactKey key = new ExactKey(stack, null);
            ItemStack existing = items.get(key);
            if (existing == null) {
                existing = stack.copy();
                plan.items.add(existing);
                items.put(key, existing);
            } else {
                if (stack.stackSize > Integer.MAX_VALUE - existing.stackSize) throw new IOException("蓝图库存数量过大");
                existing.stackSize += stack.stackSize;
            }
        }

        private void addFluid(FluidCost cost) throws IOException {
            if (cost.amount <= 0) return;
            ExactKey key = new ExactKey(null, cost.stack);
            FluidCost existing = fluids.get(key);
            if (existing == null) {
                existing = new FluidCost(cost.stack, cost.amount, cost.key);
                plan.fluids.add(existing);
                fluids.put(key, existing);
            } else {
                if (cost.amount > Long.MAX_VALUE - existing.amount) throw unsupported(cost.key, "流体数量超出上限");
                existing.amount += cost.amount;
            }
        }

        private void addEssentia(HologramEssentia.Cost cost) throws IOException {
            if (cost.amount <= 0) return;
            HologramEssentia.Cost existing = essentia.get(cost.aspect);
            if (existing == null) {
                existing = new HologramEssentia.Cost(cost.aspect, cost.amount);
                plan.essentia.add(existing);
                essentia.put(cost.aspect, existing);
            } else {
                if (cost.amount > Long.MAX_VALUE - existing.amount) throw unsupported("Amount", "源质数量超出上限");
                existing.amount += cost.amount;
            }
        }

        private void addEntry(Entry entry) {
            RowKey key = new RowKey(entry);
            Entry existing = entries.get(key);
            if (existing == null) {
                existing = new Entry(entry.key, entry.item, entry.fluid, entry.essentia, entry.amount);
                plan.entries.add(existing);
                entries.put(key, existing);
            } else existing.amount = existing.amount.add(entry.amount);
        }

        final class Merge {

            private final Plan source;
            private int stage, index;
            private long completed;

            Merge(Plan source) {
                this.source = source;
            }

            long completed() {
                return completed;
            }

            long total() {
                return (long) source.items.size() + source.fluids.size()
                    + source.essentia.size()
                    + source.entries.size()
                    + 1;
            }

            boolean done() {
                return stage == 5;
            }

            void advance() throws IOException {
                if (stage == 0 && index == source.items.size()) {
                    stage++;
                    index = 0;
                }
                if (stage == 1 && index == source.fluids.size()) {
                    stage++;
                    index = 0;
                }
                if (stage == 2 && index == source.essentia.size()) {
                    stage++;
                    index = 0;
                }
                if (stage == 0) addItem(source.items.get(index++));
                else if (stage == 1) addFluid(source.fluids.get(index++));
                else if (stage == 2) addEssentia(source.essentia.get(index++));
                else if (stage == 3) {
                    plan.energy = plan.energy.add(source.energy);
                    stage++;
                    index = 0;
                } else if (stage == 4 && index < source.entries.size()) addEntry(source.entries.get(index++));
                else {
                    stage = 5;
                    return;
                }
                completed++;
            }
        }
    }

    /** Exact native identity; NBT compounds compare structurally, independent of serialized key order. */
    static final class ExactKey {

        final Object type;
        final int meta;
        final NBTTagCompound tag;
        private final int hash;

        ExactKey(ItemStack item, FluidStack fluid) {
            this(item, fluid, true);
        }

        private ExactKey(ItemStack item, FluidStack fluid, boolean snapshot) {
            type = item != null ? item.getItem() : fluid == null ? null : fluid.getFluid();
            meta = item == null ? 0 : item.getItemDamage();
            NBTTagCompound nbt = item == null ? fluid == null ? null : fluid.tag : item.getTagCompound();
            tag = nbt == null || !snapshot ? nbt : (NBTTagCompound) nbt.copy();
            hash = (System.identityHashCode(type) * 31 + meta) * 31 + java.util.Objects.hashCode(tag);
        }

        /** A read-only lookup probe. Never store this short-lived view in a collection. */
        static ExactKey borrowed(ItemStack item) {
            return new ExactKey(item, null, false);
        }

        boolean matches(ItemStack item) {
            return type == (item == null ? null : item.getItem()) && meta == (item == null ? 0 : item.getItemDamage())
                && java.util.Objects.equals(tag, item == null ? null : item.getTagCompound());
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ExactKey)) return false;
            ExactKey key = (ExactKey) other;
            return type == key.type && meta == key.meta && java.util.Objects.equals(tag, key.tag);
        }
    }

    private static final class RowKey {

        final String key, aspect;
        final ExactKey material;

        RowKey(Entry entry) {
            key = entry.key;
            aspect = entry.essentia;
            material = new ExactKey(entry.item, entry.fluid);
        }

        @Override
        public int hashCode() {
            return (key.hashCode() * 31 + java.util.Objects.hashCode(aspect)) * 31 + material.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RowKey)) return false;
            RowKey row = (RowKey) other;
            return key.equals(row.key) && java.util.Objects.equals(aspect, row.aspect) && material.equals(row.material);
        }
    }

    /** Ordered shortage checks, one source access per operation. */
    static final class MissingTask {

        private final Plan plan;
        private final HologramMaterials.Context context;
        private final boolean essentiaOnly;
        private int stage, index;
        private long completed;
        private String missing;

        MissingTask(Plan plan, HologramMaterials.Context context, boolean essentiaOnly) {
            this.plan = plan;
            this.context = context;
            this.essentiaOnly = essentiaOnly;
            stage = essentiaOnly ? 2 : 0;
        }

        long completed() {
            return completed;
        }

        long total() {
            return essentiaOnly ? plan.essentia.size()
                : (long) plan.items.size() + plan.fluids.size() + plan.essentia.size() + 1;
        }

        boolean done() {
            return stage == 4 || missing != null;
        }

        String result() {
            if (!done()) throw new IllegalStateException("Resource check is incomplete");
            return missing;
        }

        void advance() {
            if (stage == 0 && index == plan.items.size()) {
                stage++;
                index = 0;
            }
            if (stage == 1 && index == plan.fluids.size()) {
                stage++;
                index = 0;
            }
            if (stage == 2 && index == plan.essentia.size()) {
                stage++;
                index = 0;
            }
            if (stage == 0) {
                ItemStack item = plan.items.get(index++);
                int available = context.count(item, item.stackSize);
                if (available < item.stackSize)
                    missing = "库存材料不足：" + item.getDisplayName() + " 缺 " + (item.stackSize - available);
            } else if (stage == 1) {
                FluidCost fluid = plan.fluids.get(index++);
                long available = context.fluidCount(fluid.stack, fluid.amount);
                if (available < fluid.amount)
                    missing = "AE流体不足：" + fluid.stack.getLocalizedName() + " 缺 " + (fluid.amount - available) + " mB";
            } else if (stage == 2) {
                HologramEssentia.Cost cost = plan.essentia.get(index++);
                long available = context.essentiaCount(cost.aspect, cost.amount);
                if (available < cost.amount) missing = "源质不足：" + HologramEssentia.displayName(cost.aspect)
                    + " 缺 "
                    + (cost.amount - available)
                    + " 点（背包源质罐或已认证 AE 源质网络）";
            } else if (stage == 3) {
                if (!essentiaOnly) {
                    BigInteger available = context.energyAvailable();
                    if (available.compareTo(plan.energy) < 0)
                        missing = "无线电网不足：缺 " + plan.energy.subtract(available) + " EU";
                }
                stage = 4;
                if (essentiaOnly) return;
            }
            completed++;
        }
    }

    public static final class Plan {

        public final List<ItemStack> items = new ArrayList<>();
        public final List<FluidCost> fluids = new ArrayList<>();
        public final List<HologramEssentia.Cost> essentia = new ArrayList<>();
        public BigInteger energy = BigInteger.ZERO;
        private final List<Entry> entries = new ArrayList<>();

        public Plan merge(Plan other) throws IOException {
            if (other == null) return this;
            addItems(other.items);
            for (FluidCost fluid : other.fluids) addFluid(fluid.stack, fluid.amount, fluid.key);
            for (HologramEssentia.Cost cost : other.essentia) addEssentia(cost.aspect, cost.amount);
            energy = energy.add(other.energy);
            for (Entry entry : other.entries) addEntry(entry);
            return this;
        }

        private void addEntry(Entry entry) {
            for (Entry existing : entries) {
                if (!existing.key.equals(entry.key)) continue;
                boolean same = existing.item == null && entry.item == null
                    && existing.fluid == null
                    && entry.fluid == null
                    && java.util.Objects.equals(existing.essentia, entry.essentia);
                if (existing.item != null && entry.item != null) same = existing.item.isItemEqual(entry.item)
                    && ItemStack.areItemStackTagsEqual(existing.item, entry.item);
                if (existing.fluid != null && entry.fluid != null) same = existing.fluid.isFluidEqual(entry.fluid);
                if (same) {
                    existing.amount = existing.amount.add(entry.amount);
                    return;
                }
            }
            entries.add(new Entry(entry.key, entry.item, entry.fluid, entry.essentia, entry.amount));
        }

        public Plan addItems(List<ItemStack> additions) throws IOException {
            if (additions != null) for (ItemStack stack : additions) addItem(stack);
            return this;
        }

        public Plan addNativeItems(List<ItemStack> additions) throws IOException {
            if (additions != null) for (ItemStack stack : additions) {
                addItem(stack);
                if (stack != null && stack.stackSize > 0)
                    addEntry(new Entry("native.components", stack, null, BigInteger.valueOf(stack.stackSize)));
            }
            return this;
        }

        /** Native CableBus getDrops already contains its real inventory and upgrades. */
        public Plan removeItemsIncludedByNativeDrops(List<ItemStack> nativeDrops) {
            List<ItemStack> paid = new ArrayList<>();
            for (ItemStack stack : nativeDrops) paid.add(stack.copy());
            java.util.Iterator<ItemStack> iterator = items.iterator();
            while (iterator.hasNext()) {
                ItemStack inventory = iterator.next();
                for (ItemStack nativeStack : paid) {
                    if (!inventory.isItemEqual(nativeStack) || !ItemStack.areItemStackTagsEqual(inventory, nativeStack))
                        continue;
                    int overlap = Math.min(inventory.stackSize, nativeStack.stackSize);
                    inventory.stackSize -= overlap;
                    nativeStack.stackSize -= overlap;
                }
                if (inventory.stackSize == 0) iterator.remove();
            }
            paid.clear();
            for (ItemStack stack : nativeDrops) paid.add(stack.copy());
            java.util.Iterator<Entry> rows = entries.iterator();
            while (rows.hasNext()) {
                Entry entry = rows.next();
                if (entry.item == null) continue;
                for (ItemStack nativeStack : paid) {
                    if (!entry.item.isItemEqual(nativeStack)
                        || !ItemStack.areItemStackTagsEqual(entry.item, nativeStack)) continue;
                    int overlap = entry.amount.min(BigInteger.valueOf(nativeStack.stackSize))
                        .intValue();
                    entry.amount = entry.amount.subtract(BigInteger.valueOf(overlap));
                    nativeStack.stackSize -= overlap;
                }
                if (entry.amount.signum() == 0) rows.remove();
            }
            return this;
        }

        private void addItem(ItemStack stack) throws IOException {
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return;
            for (ItemStack existing : items) {
                if (!existing.isItemEqual(stack) || !ItemStack.areItemStackTagsEqual(existing, stack)) continue;
                if (stack.stackSize > Integer.MAX_VALUE - existing.stackSize) throw new IOException("蓝图库存数量过大");
                existing.stackSize += stack.stackSize;
                return;
            }
            items.add(stack.copy());
        }

        private void addFluid(FluidStack stack, long amount, String key) throws IOException {
            if (amount <= 0) return;
            for (FluidCost existing : fluids) {
                if (!existing.stack.isFluidEqual(stack)) continue;
                if (amount > Long.MAX_VALUE - existing.amount) throw unsupported(key, "流体数量超出上限");
                existing.amount += amount;
                return;
            }
            fluids.add(new FluidCost(stack, amount, key));
        }

        private void addEssentia(String aspect, long amount) throws IOException {
            if (amount <= 0) return;
            for (HologramEssentia.Cost existing : essentia) {
                if (!existing.aspect.equals(aspect)) continue;
                if (amount > Long.MAX_VALUE - existing.amount) throw unsupported("Amount", "源质数量超出上限");
                existing.amount += amount;
                return;
            }
            essentia.add(new HologramEssentia.Cost(aspect, amount));
        }

        /** The service owns the world/main-inventory snapshot and must roll back the transaction on false. */
        public boolean reserve(HologramMaterials.Transaction transaction) {
            return reserve(new Payment() {

                @Override
                public boolean item(ItemStack stack, boolean simulate) {
                    return transaction.takeAll(stack, simulate);
                }

                @Override
                public boolean fluid(FluidStack stack, long amount, boolean simulate) {
                    return transaction.takeFluid(stack, amount, simulate);
                }

                @Override
                public boolean energy(BigInteger amount, boolean simulate) {
                    return transaction.takeEnergy(amount, simulate);
                }

                @Override
                public boolean essentia(String aspect, long amount, boolean simulate) {
                    return transaction.takeEssentia(aspect, amount, simulate);
                }
            });
        }

        boolean reserve(Payment payment) {
            for (ItemStack item : items) if (!payment.item(item.copy(), true)) return false;
            for (FluidCost fluid : fluids) if (!payment.fluid(fluid.stack.copy(), fluid.amount, true)) return false;
            for (HologramEssentia.Cost cost : essentia)
                if (!payment.essentia(cost.aspect, cost.amount, true)) return false;
            if (!payment.energy(energy, true)) return false;
            for (ItemStack item : items) if (!payment.item(item.copy(), false)) return false;
            for (FluidCost fluid : fluids) if (!payment.fluid(fluid.stack.copy(), fluid.amount, false)) return false;
            for (HologramEssentia.Cost cost : essentia)
                if (!payment.essentia(cost.aspect, cost.amount, false)) return false;
            return payment.energy(energy, false);
        }

        /** Creative construction can waive the shell; retained physical aspect points still require payment. */
        public boolean reserveEssentia(HologramMaterials.Transaction transaction) {
            for (HologramEssentia.Cost cost : essentia)
                if (!transaction.takeEssentia(cost.aspect, cost.amount, true)) return false;
            for (HologramEssentia.Cost cost : essentia)
                if (!transaction.takeEssentia(cost.aspect, cost.amount, false)) return false;
            return true;
        }

        public String missingEssentia(HologramMaterials.Context context) {
            for (HologramEssentia.Cost cost : essentia) {
                long available = context.essentiaCount(cost.aspect, cost.amount);
                if (available < cost.amount) return "源质不足：" + HologramEssentia.displayName(cost.aspect)
                    + " 缺 "
                    + (cost.amount - available)
                    + " 点（背包源质罐或已认证 AE 源质网络）";
            }
            return null;
        }

        public String missing(HologramMaterials.Context context) {
            for (ItemStack item : items) {
                int available = context.count(item, item.stackSize);
                if (available < item.stackSize)
                    return "库存材料不足：" + item.getDisplayName() + " 缺 " + (item.stackSize - available);
            }
            for (FluidCost fluid : fluids) {
                long available = context.fluidCount(fluid.stack, fluid.amount);
                if (available < fluid.amount)
                    return "AE流体不足：" + fluid.stack.getLocalizedName() + " 缺 " + (fluid.amount - available) + " mB";
            }
            String missingEssentia = missingEssentia(context);
            if (missingEssentia != null) return missingEssentia;
            BigInteger available = context.energyAvailable();
            return available.compareTo(energy) < 0 ? "无线电网不足：缺 " + energy.subtract(available) + " EU" : null;
        }

        public NBTTagList entries(HologramMaterials.Context context) {
            EntriesTask task = entriesTask(context);
            while (!task.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
            return task.rows;
        }

        EntriesTask entriesTask(HologramMaterials.Context context) {
            return new EntriesTask(context);
        }

        final class EntriesTask {

            private final HologramMaterials.Context context;
            final NBTTagList rows = new NBTTagList();
            private int cursor;

            EntriesTask(HologramMaterials.Context context) {
                this.context = context;
            }

            long done() {
                return cursor;
            }

            long total() {
                return entries.size();
            }

            boolean step(int maxWork, long deadline) {
                for (int work = 0; cursor < entries.size() && work < maxWork; work++) {
                    if (work > 0 && System.nanoTime() >= deadline) return false;
                    Entry entry = entries.get(cursor++);
                    NBTTagCompound row = new NBTTagCompound();
                    row.setString("key", entry.key);
                    BigInteger available;
                    if (entry.item != null) {
                        row.setString("type", "item");
                        row.setString("name", entry.item.getDisplayName());
                        available = BigInteger.valueOf(context.count(entry.item, entry.amount.intValue()));
                    } else if (entry.fluid != null) {
                        row.setString("type", "fluid");
                        row.setString("name", entry.fluid.getLocalizedName());
                        available = BigInteger.valueOf(context.fluidCount(entry.fluid, entry.amount.longValue()));
                    } else if (entry.essentia != null) {
                        row.setString("type", "essentia");
                        row.setString("aspect", entry.essentia);
                        row.setString("name", HologramEssentia.displayName(entry.essentia) + " 源质点");
                        available = BigInteger.valueOf(context.essentiaCount(entry.essentia, entry.amount.longValue()));
                    } else {
                        row.setString("type", "energy");
                        row.setString("name", "无线电网 EU");
                        available = context.energyAvailable();
                    }
                    row.setString("required", entry.amount.toString());
                    row.setString("available", available.toString());
                    row.setString(
                        "missing",
                        entry.amount.subtract(available)
                            .max(BigInteger.ZERO)
                            .toString());
                    rows.appendTag(row);
                }
                return cursor == entries.size();
            }
        }

    }

    interface Payment {

        boolean item(ItemStack stack, boolean simulate);

        boolean fluid(FluidStack stack, long amount, boolean simulate);

        boolean energy(BigInteger amount, boolean simulate);

        default boolean essentia(String aspect, long amount, boolean simulate) {
            return amount == 0;
        }
    }

    public static Plan plan(NBTTagCompound selectedPayload) throws IOException {
        return plan(selectedPayload, java.util.Collections.emptyList());
    }

    /** Native components are separately priced by their audited placement provider; they are never ghost samples. */
    public static Plan plan(NBTTagCompound selectedPayload, List<ItemStack> nativeComponents) throws IOException {
        Plan result = new Plan();
        if (selectedPayload == null) return result;
        NBTTagCompound original = selectedPayload;
        HologramFullPreservation.roots(original);
        selectedPayload = HologramGtMeShadow.project(HologramEnergyPayload.withoutBuffers(selectedPayload));
        if (selectedPayload.hasKey("GTSWN_NetworkSafetyEnabled")
            && (!selectedPayload.hasKey("GTSWN_NetworkSafetyEnabled", 1)
                || selectedPayload.getByte("GTSWN_NetworkSafetyEnabled") != 0
                    && selectedPayload.getByte("GTSWN_NetworkSafetyEnabled") != 1))
            throw unsupported("GTSWN_NetworkSafetyEnabled", "原生 TileEntity 配置必须为布尔 byte");
        boolean craftingHatch = HologramGtCraftingResources.supports(selectedPayload);
        selectedPayload = HologramGtCraftingResources.paymentPayload(selectedPayload);
        HologramFullPreservation.assertUnchanged(original, selectedPayload);
        HologramAeNativeResources.validateSelectedFinalPayload(selectedPayload);
        HologramEssentia.validateNonJarContents(selectedPayload);
        Inspection inspection = new Inspection(selectedPayload, nativeComponents);
        result.addNativeItems(HologramGtMeShadow.componentMaterials(selectedPayload));
        HologramEssentia.Cost jar = HologramEssentia.jarCost(selectedPayload);
        if (jar != null) {
            result.addEssentia(jar.aspect, jar.amount);
            result.addEntry(new Entry("Amount", null, null, jar.aspect, BigInteger.valueOf(jar.amount)));
        }
        for (Object raw : selectedPayload.func_150296_c()) {
            String key = (String) raw;
            if (HologramFullPreservation.TAG.equals(key)) continue;
            if (HologramEssentia.isJar(selectedPayload) && (key.equals("Aspect") || key.equals("Amount"))) continue;
            if (craftingHatch && key.equals("gtmbCraftingResourceCosts")) {
                inspect(
                    selectedPayload.getTag(key),
                    "Inventory",
                    "internalInventory",
                    "internalInventory",
                    result,
                    inspection,
                    0,
                    true,
                    false);
                continue;
            }
            inspect(selectedPayload.getTag(key), key, key, key, result, inspection, 0, false, false);
        }
        return result;
    }

    private static void inspect(NBTBase value, String name, String rootKey, String path, Plan plan,
        Inspection inspection, int depth, boolean inventoryContext, boolean fluidContext) throws IOException {
        if (depth > 64) throw unsupported(rootKey, "资源NBT嵌套过深");
        String lower = name.toLowerCase(Locale.ROOT);
        if (inspection.configuration.contains(path)) return;
        if (inspection.fullyPreserved.contains(rootKey)) {
            inspectFullyPreserved(value, rootKey, path, plan, inspection, depth);
            return;
        }
        boolean physicalItem = inspection.physical.contains(path);
        if (physicalItem && !(value instanceof NBTTagCompound)) throw unsupported(rootKey, "实物物品记录必须为NBT复合项");
        inventoryContext |= inventoryContainer(lower) || physicalItem;
        fluidContext |= fluidContainer(lower);
        if (name.equals("mStoredEnergy")) {
            long amount = number(value, rootKey);
            if (amount > 0) {
                BigInteger cost = BigInteger.valueOf(amount);
                plan.energy = plan.energy.add(cost);
                plan.addEntry(new Entry(rootKey, null, null, cost));
            }
            return;
        }
        if (name.equals("mStoredSteam")) {
            long stored = number(value, rootKey);
            if (stored == 0) return;
            if (stored > Long.MAX_VALUE / 2) throw unsupported(rootKey, "蒸汽数量超出上限");
            FluidStack steam = FluidRegistry.getFluidStack("steam", 1);
            if (steam == null) throw unsupported(rootKey, "蒸汽未注册");
            long amount = stored * 2;
            plan.addFluid(steam, amount, rootKey);
            plan.addEntry(new Entry(rootKey, null, steam, BigInteger.valueOf(amount)));
            return;
        }
        boolean storage = storageName(lower);
        if (value instanceof NBTTagCompound || value instanceof NBTTagList)
            storage |= lower.contains("fluid") || lower.contains("item")
                || lower.equals("contents")
                || lower.equals("stacks");
        if (value instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) value;
            HologramNativeResources.inspect(compound, name, rootKey);
            if (!physicalItem && !itemRecord(compound)
                && (ResourceContainers.hasCodecs() || fluidContext
                    || lower.contains("fluid")
                    || lower.contains("tank"))) {
                List<ResourceContainers.FluidAmount> decoded;
                try {
                    decoded = ResourceContainers.decode(
                        new ResourceContainers.Context(
                            inspection.nativeTileId,
                            inspection.machineId,
                            rootKey,
                            path,
                            name,
                            fluidContext,
                            compound));
                } catch (IOException invalid) {
                    throw unsupported(rootKey, invalid.getMessage());
                }
                if (decoded != null) {
                    for (ResourceContainers.FluidAmount fluid : decoded) {
                        if (fluid.amount == 0) continue;
                        FluidStack identity = fluid.identity();
                        if (!HologramMaterials.supportsFluidNbt(identity))
                            throw unsupported(rootKey, "当前AE流体接口不能保留此流体NBT");
                        plan.addFluid(identity, fluid.amount, rootKey);
                        plan.addEntry(new Entry(rootKey, null, identity, BigInteger.valueOf(fluid.amount)));
                    }
                    return;
                }
            }
            if (physicalItem || itemRecord(compound)) {
                if (!compound.hasKey("id", 99) || !compound.hasKey("Count", 99)
                    || compound.getTag("id")
                        .getId() > 4
                    || compound.getTag("Count")
                        .getId() > 4
                    || compound.hasKey("Damage") && (!compound.hasKey("Damage", 99) || compound.getTag("Damage")
                        .getId() > 4)
                    || compound.hasKey("tag") && !compound.hasKey("tag", 10)) throw unsupported(rootKey, "物品记录类型无效");
                if (compound.getBoolean(HologramBlueprintNbt.MISSING_ITEM)
                    || HologramBlueprintNbt.hasMissingItems(compound)) throw unsupported(rootKey, "库存物品未注册");
                ItemStack stack = ItemStack.loadItemStackFromNBT(HologramBlueprintNbt.nativePayload(compound));
                if (stack == null || stack.getItem() == null) throw unsupported(rootKey, "库存物品未注册");
                // Native readFromNBT defaults an omitted Damage to zero; it is still a real, payable item.
                // GT writes Count as an int for large slots, while vanilla ItemStack's loader reads a byte.
                long count = compound.getLong("Count");
                if (count < 0 || count > Integer.MAX_VALUE || physicalItem && count == 0)
                    throw unsupported(rootKey, "库存物品数量无效");
                stack.stackSize = (int) count;
                if (stack.stackSize > 0) {
                    if (!inventoryContext) {
                        if (inspection.coveredNativeComponent(stack)) return;
                        throw unsupported(rootKey, "未审核的独立物品资产 " + path + "；无法按名称认定为配置样本");
                    }
                    if (storage && !knownContainer(lower) && !inspection.physical.contains(path))
                        throw unsupported(rootKey, "未知库存存储格式");
                    plan.addItem(stack);
                    plan.addEntry(new Entry(rootKey, stack, null, BigInteger.valueOf(stack.stackSize)));
                }
                // The exact tagged item itself is paid for, including any of its nested contents.
                return;
            }
            if (compound.hasKey("FluidName") || compound.hasKey("Amount") && lower.contains("fluid")) {
                if (storage && !knownContainer(lower)) throw unsupported(rootKey, "未知流体存储格式");
                if (!compound.hasKey("FluidName", 8) || !compound.hasKey("Amount", 99)
                    || compound.getTag("Amount")
                        .getId() > 4
                    || compound.hasKey("Tag") && !compound.hasKey("Tag", 10)) throw unsupported(rootKey, "流体记录类型无效");
                long amount = compound.getLong("Amount");
                if (amount < 0) throw unsupported(rootKey, "流体数量无效");
                if (amount == 0) return;
                if (!fluidContext) throw unsupported(rootKey, "未审核的独立流体资产 " + path);
                FluidStack stack = FluidStack.loadFluidStackFromNBT(compound);
                if (stack == null || stack.getFluid() == null) throw unsupported(rootKey, "流体未注册");
                if (!HologramMaterials.supportsFluidNbt(stack)) throw unsupported(rootKey, "当前AE流体接口不能保留此流体NBT");
                plan.addFluid(stack, amount, rootKey);
                plan.addEntry(new Entry(rootKey, null, stack, BigInteger.valueOf(amount)));
                return;
            }
            if (storage && !compound.hasNoTags() && !knownContainer(lower)) throw unsupported(rootKey, "未知容器存储格式");
            for (Object raw : compound.func_150296_c()) {
                String key = (String) raw;
                inspect(
                    compound.getTag(key),
                    key,
                    rootKey,
                    path + "/" + key,
                    plan,
                    inspection,
                    depth + 1,
                    inventoryContext,
                    fluidContext);
            }
            return;
        }
        if (value instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) value;
            if (storage && list.tagCount() > 0 && list.func_150303_d() != 10) throw unsupported(rootKey, "未知容器存储格式");
            if (storage && list.tagCount() > 0 && !knownContainer(lower)) throw unsupported(rootKey, "未知容器存储格式");
            if (list.func_150303_d() != 10) return;
            for (int i = 0; i < list.tagCount(); i++) {
                NBTBase entry = list.getCompoundTagAt(i);
                if (storage && entry instanceof NBTTagCompound) {
                    NBTTagCompound row = (NBTTagCompound) entry;
                    if (!row.hasNoTags() && !row.hasKey("FluidName") && !itemRecord(row))
                        throw unsupported(rootKey, "未知容器条目格式");
                }
                inspect(
                    entry,
                    name,
                    rootKey,
                    path + "/" + i,
                    plan,
                    inspection,
                    depth + 1,
                    inventoryContext,
                    fluidContext);
            }
            return;
        }
        if (storage && nonempty(value)) throw unsupported(rootKey, "未知资源存储格式");
    }

    /**
     * An explicit author choice permits unknown container names, never unknown asset encodings. Every leaf must
     * be a complete native item/fluid record; opaque scalars and arrays cannot silently become free resources.
     */
    private static void inspectFullyPreserved(NBTBase value, String root, String path, Plan plan, Inspection inspection,
        int depth) throws IOException {
        if (depth > 64) throw unsupported(root, "资源NBT嵌套过深 " + path);
        if (inspection.configuration.contains(path)) return;
        if (value instanceof NBTTagCompound) {
            NBTTagCompound row = (NBTTagCompound) value;
            HologramNativeResources.inspect(row, path.substring(path.lastIndexOf('/') + 1), root);
            if (itemRecord(row)) {
                for (Object raw : row.func_150296_c()) {
                    String key = (String) raw;
                    if (!key.equals("id") && !key.equals("Count")
                        && !key.equals("Damage")
                        && !key.equals("tag")
                        && !key.equals("Slot")
                        && !key.equals(HologramBlueprintNbt.ITEM_REGISTRY)
                        && !key.equals(HologramBlueprintNbt.MISSING_ITEM))
                        throw unsupported(root, "物品记录含无法解析的私有字段 " + path + "/" + key);
                }
                if (!integer(row, "id") || !integer(row, "Count")
                    || row.hasKey("Damage") && !integer(row, "Damage")
                    || row.hasKey("Slot") && !integer(row, "Slot")
                    || row.hasKey("tag") && !row.hasKey("tag", 10)
                    || row.getLong("id") < 0
                    || row.getLong("id") > Short.MAX_VALUE
                    || row.hasKey("Damage") && (row.getLong("Damage") < 0 || row.getLong("Damage") > Short.MAX_VALUE)
                    || row.getLong("Count") < 1
                    || row.getLong("Count") > Integer.MAX_VALUE) throw unsupported(root, "物品记录类型或数量无效 " + path);
                NBTTagCompound nativeRow = HologramBlueprintNbt.nativePayload(row);
                ItemStack item = ItemStack.loadItemStackFromNBT(nativeRow);
                if (item == null || item.getItem() == null) throw unsupported(root, "库存物品未注册 " + path);
                item.stackSize = (int) row.getLong("Count");
                plan.addItem(item);
                plan.addEntry(new Entry(root, item, null, BigInteger.valueOf(item.stackSize)));
                return; // The entire exact tagged item pays its nested contents once.
            }
            if (row.hasKey("FluidName") || row.hasKey("Amount")) {
                for (Object raw : row.func_150296_c()) {
                    String key = (String) raw;
                    if (!key.equals("FluidName") && !key.equals("Amount") && !key.equals("Tag"))
                        throw unsupported(root, "流体记录含无法解析的私有字段 " + path + "/" + key);
                }
                if (!row.hasKey("FluidName", 8) || !integer(row, "Amount")
                    || row.getLong("Amount") < 0
                    || row.getLong("Amount") > Integer.MAX_VALUE
                    || row.hasKey("Tag") && !row.hasKey("Tag", 10)) throw unsupported(root, "流体记录类型或数量无效 " + path);
                FluidStack fluid = FluidStack.loadFluidStackFromNBT(row);
                if (fluid == null || fluid.getFluid() == null) throw unsupported(root, "流体未注册 " + path);
                if (!HologramMaterials.supportsFluidNbt(fluid)) throw unsupported(root, "当前AE流体接口不能保留此流体NBT " + path);
                long amount = row.getLong("Amount");
                if (amount > 0) {
                    plan.addFluid(fluid, amount, root);
                    plan.addEntry(new Entry(root, null, fluid, BigInteger.valueOf(amount)));
                }
                return;
            }
            for (Object raw : row.func_150296_c()) {
                String key = (String) raw;
                inspectFullyPreserved(row.getTag(key), root, path + "/" + key, plan, inspection, depth + 1);
            }
            return;
        }
        if (value instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) value;
            if (list.tagCount() > 0 && list.func_150303_d() != 10) throw unsupported(root, "无法解析的资源列表 " + path);
            for (int i = 0; i < list.tagCount(); i++)
                inspectFullyPreserved(list.getCompoundTagAt(i), root, path + "/" + i, plan, inspection, depth + 1);
            return;
        }
        throw unsupported(root, "无法解析私有资产 " + path + "；请移除此信息");
    }

    private static boolean integer(NBTTagCompound row, String key) {
        return row.hasKey(key, 99) && row.getTag(key)
            .getId() <= 4;
    }

    /** Count is part of the native item record; an omitted Damage has the native default of zero. */
    private static boolean itemRecord(NBTTagCompound compound) {
        return compound.hasKey("Count") || compound.hasKey("id") && compound.hasKey("Damage");
    }

    private static boolean knownContainer(String name) {
        return inventoryContainer(name) || fluidContainer(name);
    }

    private static boolean inventoryContainer(String name) {
        return name.equals("inventory") || name.equals("items")
            || name.equals("pattern")
            || name.equals("patterns")
            || name.matches("moutputitem[0-9]+");
    }

    private static boolean fluidContainer(String name) {
        return name.equals("mfluid") || name.equals("fluid")
            || name.equals("fluids")
            || name.equals("mfluids")
            || name.matches("mfluid[0-9]+")
            || name.equals("moutputfluid")
            || name.equals("mfluidout");
    }

    private static final class Inspection {

        final String nativeTileId;
        final int machineId;
        final Set<String> configuration = new HashSet<>(), physical = new HashSet<>();
        final Set<String> fullyPreserved;
        final List<ItemStack> nativeCredits = new ArrayList<>();

        Inspection(NBTTagCompound root, List<ItemStack> nativeComponents) throws IOException {
            fullyPreserved = HologramFullPreservation.roots(root);
            nativeTileId = root.getString("id");
            machineId = root.hasKey("mID", 99) ? root.getInteger("mID") : -1;
            configuration.addAll(HologramGtMeShadow.configurationPaths(root));
            configuration.addAll(HologramGtswnPanelConfiguration.paths(root));
            if (nativeComponents != null) for (ItemStack item : nativeComponents)
                if (item != null && item.getItem() != null && item.stackSize > 0) nativeCredits.add(item.copy());
            for (java.util.Map.Entry<String, ItemStack> attribute : HologramDecorMaterials
                .carpenterAttributeCosts(HologramBlueprintNbt.nativePayload(root))
                .entrySet()) {
                if (!coveredNativeComponent(attribute.getValue()))
                    throw unsupported("cbAttrList", "原生装饰材质未完整计价 " + attribute.getKey());
                configuration.add(attribute.getKey());
            }
            if (root.getString("id")
                .equals("blockVacuumChestTileEntity")) physical.add("filter");
            if (root.hasKey("mID", 99)) {
                int id = root.getInteger("mID");
                if (id >= 0 && id < gregtech.api.GregTechAPI.METATILEENTITIES.length
                    && gregtech.api.GregTechAPI.METATILEENTITIES[id] != null
                    && gregtech.api.GregTechAPI.METATILEENTITIES[id].getClass()
                        .getName()
                        .equals("gregtech.common.tileentities.machines.basic.MTEIndustrialApiary"))
                    physical.add("usedQueen");
            }
            // GT's facade record is the same separately paid cover item; item-filter slots are native ghost samples.
            // Only a real registered cover factory and its exact audited persistence path grant this exemption.
            NBTTagList covers = root.getTagList("gt.covers", 10);
            for (int i = 0; i < covers.tagCount(); i++) {
                NBTTagCompound row = covers.getCompoundTagAt(i);
                if (!row.hasKey("d", 10)) continue;
                try {
                    Class<?> registry = Class.forName("gregtech.api.covers.CoverRegistry");
                    Class<?> owner = Class.forName("gregtech.api.interfaces.tileentity.ICoverable");
                    Object cover = registry
                        .getMethod(
                            "buildCoverFromNbt",
                            NBTTagCompound.class,
                            net.minecraftforge.common.util.ForgeDirection.class,
                            owner)
                        .invoke(
                            null,
                            row.copy(),
                            net.minecraftforge.common.util.ForgeDirection.getOrientation(row.getByte("s")),
                            null);
                    String prefix = "gt.covers/" + i + "/d/";
                    if (cover != null && Class.forName("gregtech.common.covers.CoverFacadeBase")
                        .isInstance(cover)) {
                        NBTTagCompound body = row.getCompoundTag("d");
                        if (body.hasKey("mStack", 10) && body.getCompoundTag("mStack")
                            .getInteger("Count") != 1) throw unsupported("gt.covers", "原生外观覆盖板物品凭据必须为一件，不能增加其内部Count");
                        configuration.add(prefix + "mStack");
                    }
                    if (cover != null && cover.getClass()
                        .getName()
                        .equals("gregtech.common.covers.CoverItemFilter")) {
                        configuration.add(prefix + "mFilter");
                        configuration.add(prefix + "filter");
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    throw new IOException("无法审阅原生覆盖板资源配置 gt.covers/" + i, failure);
                }
            }
        }

        boolean coveredNativeComponent(ItemStack item) {
            long available = 0;
            for (ItemStack credit : nativeCredits)
                if (credit.isItemEqual(item) && ItemStack.areItemStackTagsEqual(credit, item))
                    available += credit.stackSize;
            if (available < item.stackSize) return false;
            int remaining = item.stackSize;
            for (ItemStack credit : nativeCredits) {
                if (!credit.isItemEqual(item) || !ItemStack.areItemStackTagsEqual(credit, item)) continue;
                int covered = Math.min(credit.stackSize, remaining);
                credit.stackSize -= covered;
                remaining -= covered;
                if (remaining == 0) break;
            }
            return true;
        }
    }

    private static boolean storageName(String name) {
        return name.equals("items") || name.equals("mitems")
            || name.contains("inventory")
            || name.contains("tank")
            || name.contains("itemhandler")
            || name.contains("itemstorage")
            || name.contains("storedenergy")
            || name.contains("storedeu")
            || name.equals("energy")
            || name.equals("menergy")
            || name.equals("eu")
            || name.equals("meu")
            || name.equals("battery")
            || name.equals("energybuffer")
            || name.equals("charge")
            || name.equals("mcharge")
            || name.equals("fluid")
            || name.equals("fluids")
            || name.equals("mfluid")
            || name.equals("mfluids")
            || name.matches("mfluid[0-9]+")
            || name.equals("storedsteam");
    }

    private static long number(NBTBase value, String key) throws IOException {
        if (!(value instanceof NBTBase.NBTPrimitive) || value.getId() > 4) throw unsupported(key, "资源数量类型无效");
        long amount = ((NBTBase.NBTPrimitive) value).func_150291_c();
        if (amount < 0) throw unsupported(key, "资源数量为负数");
        return amount;
    }

    private static boolean nonempty(NBTBase value) {
        if (value instanceof NBTBase.NBTPrimitive) return ((NBTBase.NBTPrimitive) value).func_150291_c() != 0;
        return !value.toString()
            .equals("\"\"");
    }

    private static IOException unsupported(String key, String detail) {
        return new IOException("不支持保留资源条目 " + key + "：" + detail + "；请取消此NBT条目后重试");
    }
}
