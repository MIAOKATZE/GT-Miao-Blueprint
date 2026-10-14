package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Exact native decoration costs and reversible fractional raw-material accounting. */
public final class HologramDecorMaterials {

    private HologramDecorMaterials() {}

    public interface Availability {

        int count(ItemStack exactStack, int limit);
    }

    public static final class Cost {

        public final ItemStack prebuilt;
        public final List<ItemStack> alternatives;
        public final ItemStack raw;
        public final long numerator, denominator;

        public Cost(ItemStack prebuilt, ItemStack raw, long numerator, long denominator) {
            this(prebuilt, Collections.singletonList(prebuilt), raw, numerator, denominator);
        }

        private Cost(ItemStack prebuilt, List<ItemStack> alternatives, ItemStack raw, long numerator,
            long denominator) {
            if (prebuilt == null || prebuilt.getItem() == null
                || prebuilt.stackSize <= 0
                || raw == null
                || raw.getItem() == null
                || numerator < 0
                || denominator <= 0) throw new IllegalArgumentException("native decoration cost");
            this.prebuilt = prebuilt.copy();
            List<ItemStack> choices = new ArrayList<>();
            for (ItemStack choice : alternatives) {
                if (choice == null || choice.getItem() == null || choice.stackSize != prebuilt.stackSize)
                    throw new IllegalArgumentException("native decoration alternative");
                choices.add(choice.copy());
            }
            this.alternatives = Collections.unmodifiableList(choices);
            this.raw = raw.copy();
            this.raw.stackSize = 1;
            this.numerator = numerator;
            this.denominator = denominator;
        }
    }

    public static final class Descriptor {

        public final List<Cost> costs;
        public final boolean rawAlternatives;

        public Descriptor(List<Cost> costs) {
            this.costs = Collections.unmodifiableList(new ArrayList<>(costs));
            boolean alternatives = false;
            for (Cost cost : costs)
                alternatives |= !same(cost.prebuilt, cost.raw) || !BigInteger.valueOf(cost.numerator)
                    .equals(
                        BigInteger.valueOf(cost.denominator)
                            .multiply(BigInteger.valueOf(cost.prebuilt.stackSize)));
            rawAlternatives = alternatives;
        }
    }

    private static final class Balance {

        final ItemStack item;
        BigInteger numerator = BigInteger.ZERO, denominator = BigInteger.ONE;

        Balance(ItemStack item) {
            this.item = item.copy();
            this.item.stackSize = 1;
        }

        Balance copy() {
            Balance result = new Balance(item);
            result.numerator = numerator;
            result.denominator = denominator;
            return result;
        }

        BigInteger ceil() {
            return numerator.add(denominator)
                .subtract(BigInteger.ONE)
                .divide(denominator);
        }

        void add(long amount, long divisor) {
            BigInteger d = BigInteger.valueOf(divisor);
            numerator = numerator.multiply(d)
                .add(
                    BigInteger.valueOf(amount)
                        .multiply(denominator));
            denominator = denominator.multiply(d);
            BigInteger gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }

    public static final class Quote {

        public final List<ItemStack> items;
        private final Ledger owner;
        private final long revision;
        private final List<Balance> after;

        private Quote(Ledger owner, List<ItemStack> items, List<Balance> after) {
            this.owner = owner;
            revision = owner.revision;
            this.items = Collections.unmodifiableList(items);
            this.after = after;
        }
    }

    /** Keep one ledger for the authorized placement batch, including retries of failed cells. */
    public static final class Ledger {

        private List<Balance> balances = new ArrayList<>();
        private long revision;

        public Ledger copy() {
            Ledger result = new Ledger();
            result.balances = copyBalances(balances);
            result.revision = revision;
            return result;
        }

        /** Pure quote: failed payment or world rollback never earns fractional credit. */
        public Quote quote(Descriptor descriptor, Availability available) throws IOException {
            List<Balance> after = copyBalances(balances);
            List<ItemStack> items = new ArrayList<>();
            for (Cost cost : descriptor.costs) {
                if (cost.numerator == 0) continue;
                int remaining = cost.prebuilt.stackSize;
                for (ItemStack prebuilt : cost.alternatives) {
                    int used = count(items, prebuilt);
                    int prebuiltAvailable = Math
                        .max(0, available.count(prebuilt.copy(), saturatedAdd(used, remaining)) - used);
                    int paidPrebuilt = Math.min(remaining, prebuiltAvailable);
                    if (paidPrebuilt > 0) add(items, prebuilt, paidPrebuilt);
                    remaining -= paidPrebuilt;
                    if (remaining == 0) break;
                }
                if (remaining == 0) continue;
                // Each Cost represents one native drop stack. Its raw fraction prices the entire stack.
                Balance balance = balance(after, cost.raw);
                BigInteger before = balance.ceil();
                long divisor;
                long amount;
                try {
                    divisor = Math.multiplyExact(cost.denominator, cost.prebuilt.stackSize);
                    amount = Math.multiplyExact(cost.numerator, remaining);
                } catch (ArithmeticException overflow) {
                    throw new IOException("Decoration material quantity exceeds supported range", overflow);
                }
                balance.add(amount, divisor);
                BigInteger charge = balance.ceil()
                    .subtract(before);
                if (charge.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0)
                    throw new IOException("Decoration material quantity exceeds supported range");
                if (charge.signum() > 0) add(items, cost.raw, charge.intValue());
            }
            return new Quote(this, items, after);
        }

        /** Call once, after both the real placement and its material transaction succeed. */
        public void commit(Quote quote) {
            if (quote.owner != this || quote.revision != revision)
                throw new IllegalStateException("Stale decoration material quote");
            balances = copyBalances(quote.after);
            revision++;
        }
    }

    public static boolean supports(Block block) {
        if (block == null) return false;
        String name = block.getClass()
            .getName();
        return name.equals("gcewing.architecture.common.block.BlockShape")
            || name.equals("codechicken.multipart.BlockMultipart")
            || name.equals("com.creativemd.littletiles.common.blocks.BlockTile")
            || name.startsWith("com.carpentersblocks.block.");
    }

    /** AE multipart hosts retain the independent exact inventory/network-resource planner. */
    public static boolean supports(Block block, NBTTagCompound payload) {
        return supports(block) && !HologramAeBlueprint.supports(block, payload);
    }

    /** Native factory/material validation still runs, including in creative with an empty inventory. */
    public static Descriptor describe(Block block, int metadata, NBTTagCompound payload) throws IOException {
        if (!supports(block, payload))
            throw new IOException("Unregistered native decoration family or AE resource host");
        NBTTagCompound nativeTag = HologramBlueprintNbt.nativePayload(payload);
        if (block.getClass()
            .getName()
            .startsWith("com.carpentersblocks.block.")) carpenterAttributeCosts(nativeTag);
        List<ItemStack> drops = HologramNativePlacement.materials(block, metadata, payload);
        List<Cost> result = new ArrayList<>();
        String family = block.getClass()
            .getName();
        try {
            for (int index = 0; index < drops.size(); index++) {
                ItemStack prebuilt = drops.get(index);
                if (family.equals("com.carpentersblocks.block.BlockCarpentersSlope") && index == 0) {
                    // The five native item damage values are initial hammer-selectable shape groups.
                    // The full persisted cbMetadata remains the final shape; every empty native slope shell pays once.
                    List<ItemStack> choices = new ArrayList<>();
                    for (int damage = 0; damage < 5; damage++)
                        choices.add(new ItemStack(prebuilt.getItem(), 1, damage));
                    result.add(new Cost(prebuilt, choices, prebuilt, 1, 1));
                } else if (family.equals("gcewing.architecture.common.block.BlockShape") && index == 0) {
                    Object shape = Class.forName("gcewing.architecture.common.shape.Shape")
                        .getMethod("forId", int.class)
                        .invoke(null, nativeTag.getInteger("Shape"));
                    Block material = Block.getBlockFromName(nativeTag.getString("BaseName"));
                    ItemStack raw = new ItemStack(material, 1, nativeTag.getInteger("BaseData"));
                    int used = shape.getClass()
                        .getField("materialUsed")
                        .getInt(shape);
                    int produced = shape.getClass()
                        .getField("itemsProduced")
                        .getInt(shape);
                    if (used <= 0 || produced <= 0 || raw.getItem() == null)
                        throw new IOException("ArchitectureCraft shape has no registered raw-material recipe");
                    result.add(new Cost(prebuilt, raw, (material instanceof BlockSlab ? 2L : 1L) * used, produced));
                } else if (family.equals("gcewing.architecture.common.block.BlockShape") && index == 1
                    && nativeTag.hasKey("Name2", 8)) {
                        Block material = Block.getBlockFromName(nativeTag.getString("Name2"));
                        ItemStack raw = new ItemStack(material, 1, nativeTag.getInteger("Data2"));
                        // Native application consumes one secondary block or one matching cladding sheet.
                        result.add(new Cost(prebuilt, raw, prebuilt.stackSize, 1));
                    } else if (family.equals("codechicken.multipart.BlockMultipart") && prebuilt.getItem()
                        .getClass()
                        .getName()
                        .equals("codechicken.microblock.ItemMicroPart")) {
                            int shape = prebuilt.getItemDamage();
                            int microClass = shape >> 8, size = shape & 255;
                            int divisor = microClass == 0 || microClass == 1 ? 8
                                : microClass == 3 ? 16 : microClass == 2 ? 32 : 0;
                            if (divisor == 0 || size <= 0
                                || size >= 8
                                || !prebuilt.hasTagCompound()
                                || !prebuilt.getTagCompound()
                                    .hasKey("mat", 8))
                                throw new IOException("Unreviewed multipart cutting recipe");
                            Object material = Class.forName("codechicken.microblock.MicroMaterialRegistry")
                                .getMethod("getMaterial", String.class)
                                .invoke(
                                    null,
                                    prebuilt.getTagCompound()
                                        .getString("mat"));
                            if (material == null) throw new IOException("Unregistered multipart raw material");
                            ItemStack raw = (ItemStack) material.getClass()
                                .getMethod("getItem")
                                .invoke(material);
                            result.add(new Cost(prebuilt, raw, (long) size * prebuilt.stackSize, divisor));
                        } else if (family.equals("com.creativemd.littletiles.common.blocks.BlockTile")
                            && prebuilt.hasTagCompound()
                            && !prebuilt.getTagCompound()
                                .hasKey("isStructure")
                            && prebuilt.getTagCompound()
                                .getString("tID")
                                .equals("BlockTileBlock")) {
                                    NBTTagCompound cut = prebuilt.getTagCompound();
                                    int x = cut.getInteger("sizex"), y = cut.getInteger("sizey"),
                                        z = cut.getInteger("sizez");
                                    if (x <= 0 || x > 16 || y <= 0 || y > 16 || z <= 0 || z > 16)
                                        throw new IOException("Invalid LittleTiles static cutting dimensions");
                                    Block material = Block.getBlockFromName(cut.getString("block"));
                                    ItemStack raw = new ItemStack(material, 1, cut.getInteger("meta"));
                                    result.add(new Cost(prebuilt, raw, (long) x * y * z * prebuilt.stackSize, 4096));
                                } else {
                                    result.add(new Cost(prebuilt, prebuilt, prebuilt.stackSize, 1));
                                }
            }
            return new Descriptor(result);
        } catch (IOException failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IOException("Unsupported native decoration material recipe", failure);
        }
    }

    /** Only the registered native attribute schema can use the separately paid Carpenter material bill. */
    static Map<String, ItemStack> carpenterAttributeCosts(NBTTagCompound payload) throws IOException {
        Map<String, ItemStack> costs = new LinkedHashMap<>();
        if (payload == null || !payload.hasKey("cbAttrList")) return costs;
        try {
            if (!payload.hasKey("id", 8)) throw carpenterUnsupported("registered tile identity is missing");
            java.lang.reflect.Field names = ReflectionHelper
                .findField(TileEntity.class, "nameToClassMap", "field_145855_i");
            @SuppressWarnings("unchecked")
            Map<String, Class<?>> registered = (Map<String, Class<?>>) names.get(null);
            Class<?> family = registered.get(payload.getString("id"));
            if (family == null || !carpenterTile(family.getName()))
                throw carpenterUnsupported("tile identity is not a registered native Carpenter family");
            if (payload.hasKey("owner")) throw carpenterUnsupported("legacy attribute mappings are not reviewed");
            if (payload.hasKey("cbMetadata") && !payload.hasKey("cbMetadata", 2) && !payload.hasKey("cbMetadata", 3))
                throw carpenterUnsupported("native shape metadata must be short or int");
            for (String key : new String[] { "cbOwner", "cbDesign", "cbChiselDesign_0", "cbChiselDesign_1",
                "cbChiselDesign_2", "cbChiselDesign_3", "cbChiselDesign_4", "cbChiselDesign_5", "cbChiselDesign_6" })
                if (payload.hasKey(key) && !payload.hasKey(key, 8))
                    throw carpenterUnsupported("native configuration type is invalid: " + key);
            if (!payload.hasKey("cbAttrList", 9))
                throw carpenterUnsupported("attribute list must be a native NBT list");
            NBTTagList rows = (NBTTagList) payload.getTag("cbAttrList");
            if (rows.func_150303_d() != 10 && !(rows.tagCount() == 0 && rows.func_150303_d() == 0))
                throw carpenterUnsupported("attribute entries must be NBT compounds");
            Set<Byte> attributes = new HashSet<>();
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound row = rows.getCompoundTagAt(i);
                if (!row.hasKey("cbAttribute", 1) || !row.hasKey("id", 2)
                    || !row.hasKey("Count", 1)
                    || row.getByte("Count") != 1
                    || !row.hasKey("Damage", 2)
                    || !row.hasKey("cbUniqueId", 8)
                    || row.hasKey("tag") && !row.hasKey("tag", 10))
                    throw carpenterUnsupported("invalid native item/attribute types at cbAttrList/" + i);
                byte attr = row.getByte("cbAttribute");
                if (attr < 0 || attr > 25 || !attributes.add(attr))
                    throw carpenterUnsupported("unregistered or duplicate attribute at cbAttrList/" + i);
                for (Object raw : row.func_150296_c()) {
                    String key = (String) raw;
                    if (!key.equals("cbAttribute") && !key.equals("id")
                        && !key.equals("Count")
                        && !key.equals("Damage")
                        && !key.equals("cbUniqueId")
                        && !key.equals("tag"))
                        throw carpenterUnsupported("unreviewed item field cbAttrList/" + i + "/" + key);
                }
                ItemStack item = ItemStack.loadItemStackFromNBT(row);
                if (item == null || item.getItem() == null
                    || !row.getString("cbUniqueId")
                        .equals(Item.itemRegistry.getNameForObject(item.getItem())))
                    throw carpenterUnsupported("unregistered or inconsistent item at cbAttrList/" + i);
            }
            if (rows.tagCount() == 0) return costs;
            // TEBase owns this schema for every audited subclass. Read before attaching a detached world so the
            // native reader cannot perform lighting/neighbor updates; the native drop callback only needs world.rand.
            Class<?> base = Class.forName("com.carpentersblocks.tileentity.TEBase");
            TileEntity tile = (TileEntity) base.getConstructor()
                .newInstance();
            tile.readFromNBT((NBTTagCompound) payload.copy());
            tile.setWorldObj(HologramResourceWorld.create(Blocks.stone, 0));
            tile.xCoord = 0;
            tile.yCoord = 64;
            tile.zCoord = 0;
            for (int i = 0; i < rows.tagCount(); i++) {
                byte attr = rows.getCompoundTagAt(i)
                    .getByte("cbAttribute");
                ItemStack drop = (ItemStack) base.getMethod("getAttributeForDrop", byte.class)
                    .invoke(tile, attr);
                if (drop == null || drop.getItem() == null || drop.stackSize != 1)
                    throw carpenterUnsupported("attribute has no exact native material at cbAttrList/" + i);
                costs.put("cbAttrList/" + i, drop.copy());
            }
            return costs;
        } catch (IOException failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IOException("Unsupported native Carpenter attribute materials cbAttrList", failure);
        }
    }

    private static boolean carpenterTile(String family) {
        return family.equals("com.carpentersblocks.tileentity.TEBase")
            || family.equals("com.carpentersblocks.tileentity.TECarpentersDaylightSensor")
            || family.equals("com.carpentersblocks.tileentity.TECarpentersFlowerPot")
            || family.equals("com.carpentersblocks.tileentity.TECarpentersSafe")
            || family.equals("com.carpentersblocks.tileentity.TECarpentersTorch")
            || family.equals("com.carpentersblocks.tileentity.TECarpentersGarageDoor");
    }

    private static IOException carpenterUnsupported(String detail) {
        return new IOException("Unsupported native Carpenter attribute materials cbAttrList: " + detail);
    }

    private static int saturatedAdd(int a, int b) {
        return a > Integer.MAX_VALUE - b ? Integer.MAX_VALUE : a + b;
    }

    private static List<Balance> copyBalances(List<Balance> source) {
        List<Balance> result = new ArrayList<>();
        for (Balance balance : source) result.add(balance.copy());
        return result;
    }

    private static Balance balance(List<Balance> balances, ItemStack item) {
        for (Balance balance : balances) if (same(balance.item, item)) return balance;
        Balance result = new Balance(item);
        balances.add(result);
        return result;
    }

    private static int count(List<ItemStack> items, ItemStack item) {
        for (ItemStack existing : items) if (same(existing, item)) return existing.stackSize;
        return 0;
    }

    private static void add(List<ItemStack> items, ItemStack item, int amount) throws IOException {
        for (ItemStack existing : items) if (same(existing, item)) {
            if (amount > Integer.MAX_VALUE - existing.stackSize) throw new IOException("Decoration material overflow");
            existing.stackSize += amount;
            return;
        }
        ItemStack addition = item.copy();
        addition.stackSize = amount;
        items.add(addition);
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b);
    }
}
