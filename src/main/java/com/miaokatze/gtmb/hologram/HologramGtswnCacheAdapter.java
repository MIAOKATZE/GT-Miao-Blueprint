package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.util.GTUtility;

/** Registered GTSWN cover buffers, funded separately from construction materials by the placing player's EU bank. */
final class HologramGtswnCacheAdapter {

    private static final String ENERGY = "com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless";
    private static final String DYNAMO = "com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless";
    private static final String MATHS = "com.miaokatze.gtswn.common.util.CoverMaths";
    private static final String CONFIG = "com.miaokatze.gtswn.config.Config";

    private HologramGtswnCacheAdapter() {}

    interface Resolver {

        Schema resolve(NBTTagCompound row, int side, int id) throws IOException;
    }

    static final class Schema {

        final boolean energy;
        final long capacity;
        final double downlinkLoss;
        private final Method lossFormula;

        Schema(boolean energy, long capacity, double downlinkLoss) throws IOException {
            this(energy, capacity, downlinkLoss, null);
        }

        private Schema(boolean energy, long capacity, double downlinkLoss, Method lossFormula) throws IOException {
            if (capacity < 0 || !Double.isFinite(downlinkLoss) || downlinkLoss < 0)
                throw new IOException("Invalid native GTSWN buffer capacity or downlink loss");
            this.energy = energy;
            this.capacity = capacity;
            this.downlinkLoss = downlinkLoss;
            this.lossFormula = lossFormula;
        }

        BigInteger quote(long amount) throws IOException {
            if (lossFormula == null) return deduction(amount, downlinkLoss);
            try {
                BigInteger bill = (BigInteger) lossFormula.invoke(null, amount, downlinkLoss);
                if (bill == null || bill.compareTo(BigInteger.valueOf(amount)) < 0)
                    throw new IOException("Invalid native GTSWN downlink bill");
                return bill;
            } catch (InvocationTargetException failure) {
                throw new IOException("Cannot quote native GTSWN downlink loss", failure.getCause());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                throw new IOException("Native GTSWN downlink quote is unavailable", failure);
            }
        }
    }

    static final class Plan {

        private final Map<Integer, Buffer> buffers = new LinkedHashMap<>();
        private final Map<Integer, Integer> identities = new LinkedHashMap<>();
        private final Set<String> owned = new HashSet<>();
        private final List<String> warnings = new ArrayList<>();

        boolean owns(String path) {
            return owned.contains(path);
        }

        void apply(NBTTagCompound tilePaid, NBTTagCompound tileEmpty, NBTTagCompound itemPaid, NBTTagCompound itemEmpty,
            HologramEnergy.Demand demand, List<String> notices) {
            for (Buffer buffer : buffers.values()) {
                demand.eu = demand.eu.add(buffer.bill);
                for (Location location : buffer.locations) {
                    project(location.item ? itemPaid : tilePaid, location.index, buffer, true);
                    project(location.item ? itemEmpty : tileEmpty, location.index, buffer, false);
                }
            }
            notices.addAll(warnings);
        }
    }

    private static final class Buffer {

        final int id;
        final NBTTagCompound configuration;
        final Schema schema;
        final List<Location> locations = new ArrayList<>();
        long amount;
        BigInteger bill;

        Buffer(int id, NBTTagCompound configuration, Schema schema, long amount) {
            this.id = id;
            this.configuration = configuration;
            this.schema = schema;
            this.amount = amount;
        }
    }

    private static final class Location {

        final boolean item;
        final int index;

        Location(boolean item, int index) {
            this.item = item;
            this.index = index;
        }
    }

    static Plan inspect(NBTTagCompound tile, NBTTagCompound item) throws IOException {
        return inspect(tile, item, HologramGtswnCacheAdapter::registeredSchema);
    }

    /** Native factories are isolated from detached NBT projection tests. Unknown covers grant no buffer exemption. */
    static Plan inspect(NBTTagCompound tile, NBTTagCompound item, Resolver resolver) throws IOException {
        Plan plan = new Plan();
        inspect(tile, false, resolver, plan);
        inspect(item, true, resolver, plan);
        for (Buffer buffer : plan.buffers.values()) buffer.bill = buffer.schema.quote(buffer.amount);
        return plan;
    }

    private static void inspect(NBTTagCompound payload, boolean item, Resolver resolver, Plan plan) throws IOException {
        if (payload == null || !payload.hasKey("gt.covers")) return;
        // Carried inventory items are paid exactly, including their own stored energy; they are not device banks.
        if (payload.hasKey("id") && payload.hasKey("Count") && payload.hasKey("Damage")) return;
        if (!payload.hasKey("gt.covers", 9)) throw new IOException("GTSWN cover payload must be a list");
        NBTTagList rows = (NBTTagList) payload.getTag("gt.covers");
        if (rows.tagCount() > 6 || rows.tagCount() > 0 && rows.func_150303_d() != 10)
            throw new IOException("Malformed GTSWN cover list");
        int sides = 0;
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            if (!row.hasKey("s", 1) || !row.hasKey("id", 3) || row.getInteger("id") == 0)
                throw new IOException("GTSWN cover identity or side is missing");
            int side = row.getByte("s");
            if (side < 0 || side >= 6 || (sides & (1 << side)) != 0)
                throw new IOException("GTSWN cover side is invalid or repeated");
            sides |= 1 << side;
            int id = row.getInteger("id");
            Schema schema = resolver.resolve((NBTTagCompound) row.copy(), side, id);
            Integer previousId = plan.identities.putIfAbsent(side, id);
            if (previousId != null && previousId != id && (schema != null || plan.buffers.containsKey(side)))
                throw new IOException("Tile and item GTSWN cover identities disagree");
            if (schema == null) continue;
            if (!row.hasKey("d", 10)) throw new IOException("GTSWN buffer data must be a compound");
            NBTTagCompound data = row.getCompoundTag("d");
            if (data.hasKey("storedEU") && !data.hasKey("storedEU", 4)
                || data.hasKey("configured") && !data.hasKey("configured", 1))
                throw new IOException("GTSWN storedEU/configured has an invalid native type");
            long stored = data.getLong("storedEU");
            if (stored < 0) throw new IOException("GTSWN storedEU is negative");
            long amount = data.getBoolean("configured") ? Math.min(stored, schema.capacity) : 0;
            String path = (item ? "item" : "tile") + ".gt.covers[" + index + "].d.storedEU";
            if (amount < stored) plan.warnings.add(path + ": buffer limited to current native capacity/configuration");
            NBTTagCompound configuration = configuration(row);
            Buffer buffer = plan.buffers.get(side);
            if (buffer == null) {
                buffer = new Buffer(id, configuration, schema, amount);
                plan.buffers.put(side, buffer);
            } else {
                if (buffer.id != id || !buffer.configuration.equals(configuration)
                    || buffer.schema.energy != schema.energy
                    || buffer.schema.capacity != schema.capacity
                    || Double.compare(buffer.schema.downlinkLoss, schema.downlinkLoss) != 0)
                    throw new IOException("Tile and item GTSWN cover aliases disagree");
                // Both payloads serialize the same installed cover. Fund its final buffer once.
                buffer.amount = Math.max(buffer.amount, amount);
            }
            buffer.locations.add(new Location(item, index));
            plan.owned.add(path);
        }
    }

    private static NBTTagCompound configuration(NBTTagCompound row) {
        NBTTagCompound copy = (NBTTagCompound) row.copy();
        NBTTagCompound data = copy.getCompoundTag("d");
        for (String key : new String[] { "storedEU", "capacity", "fractionalEU", "ticksSinceLastRefill",
            "ticksSinceLastUpload" }) data.removeTag(key);
        return copy;
    }

    private static void project(NBTTagCompound payload, int index, Buffer buffer, boolean paid) {
        NBTTagCompound data = payload.getTagList("gt.covers", 10)
            .getCompoundTagAt(index)
            .getCompoundTag("d");
        data.setLong("storedEU", paid ? buffer.amount : 0);
        if (buffer.schema.energy) data.setLong("capacity", buffer.schema.capacity);
        // Fractional carry and the transfer clock do not represent purchased stored EU.
        if (data.hasKey("fractionalEU")) data.setDouble("fractionalEU", 0);
        if (data.hasKey("ticksSinceLastRefill")) data.setLong("ticksSinceLastRefill", 0);
        if (data.hasKey("ticksSinceLastUpload")) data.setLong("ticksSinceLastUpload", 0);
    }

    static BigInteger deduction(long stored, double loss) {
        return BigDecimal.valueOf(stored)
            .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(loss)))
            .setScale(0, RoundingMode.CEILING)
            .toBigIntegerExact();
    }

    static int kindForCover(Object cover) {
        if (cover == null) return -1;
        String type = cover.getClass()
            .getName();
        return type.equals(ENERGY) ? 0 : type.equals(DYNAMO) ? 1 : -1;
    }

    /** Share the exact registered class/identity check with native cover material accounting. */
    static int kindForCover(ItemStack material) throws IOException {
        if (material == null || material.getItem() == null) return -1;
        int id = GTUtility.stackToInt(material);
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("id", id);
        row.setByte("s", (byte) 0);
        row.setTag("d", new NBTTagCompound());
        return kindForCover(registeredCover(row, 0, id));
    }

    private static Object registeredCover(NBTTagCompound row, int side, int id) throws IOException {
        try {
            Class<?> registry = Class.forName("gregtech.api.covers.CoverRegistry");
            ItemStack identity = GTUtility.intToStack(id);
            if (identity == null || identity.getItem() == null
                || !Boolean.TRUE.equals(
                    registry.getMethod("isCover", ItemStack.class)
                        .invoke(null, identity)))
                throw new IOException("GTSWN buffer cover is not registered");
            Object cover = registry
                .getMethod(
                    "buildCoverFromNbt",
                    NBTTagCompound.class,
                    ForgeDirection.class,
                    Class.forName("gregtech.api.interfaces.tileentity.ICoverable"))
                .invoke(null, row, ForgeDirection.getOrientation(side), null);
            if (kindForCover(cover) < 0) return cover;
            Class<?> nativeCover = Class.forName("gregtech.common.covers.Cover");
            if (!Boolean.TRUE.equals(
                nativeCover.getMethod("isValid")
                    .invoke(cover)))
                throw new IOException("Invalid registered GTSWN cover");
            ItemStack material = (ItemStack) nativeCover.getMethod("asItemStack")
                .invoke(cover);
            if (material == null || material.getItem() == null || GTUtility.stackToInt(material) != id)
                throw new IOException("GTSWN cover factory changed the registered identity");
            return cover;
        } catch (InvocationTargetException failure) {
            throw new IOException("Cannot inspect native GTSWN cover identity", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IOException("Native GTSWN cover identity is unavailable", failure);
        }
    }

    private static Schema registeredSchema(NBTTagCompound row, int side, int id) throws IOException {
        Object cover = registeredCover(row, side, id);
        int kind = kindForCover(cover);
        if (kind < 0) return null;
        try {
            long capacity = kind == 0 ? nativeCapacity(row.getCompoundTag("d"), cover) : Long.MAX_VALUE;
            double loss = Class.forName(CONFIG)
                .getField("downlinkLossEU")
                .getDouble(null);
            Method lossFormula = Class.forName(MATHS)
                .getMethod("downlinkDeduction", long.class, double.class);
            return new Schema(kind == 0, capacity, loss, lossFormula);
        } catch (InvocationTargetException failure) {
            throw new IOException("Cannot inspect native GTSWN cover buffer", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IOException("Native GTSWN buffer schema is unavailable", failure);
        }
    }

    private static long nativeCapacity(NBTTagCompound data, Object cover)
        throws ReflectiveOperationException, IOException {
        if (data.hasKey("voltage") && !data.hasKey("voltage", 3))
            throw new IOException("GTSWN voltage must use the native int type");
        if (data.hasKey("amperageDecimal") && !data.hasKey("amperageDecimal", 6)
            || !data.hasKey("amperageDecimal") && data.hasKey("amperage") && !data.hasKey("amperage", 3))
            throw new IOException("GTSWN amperage must use its native double or legacy int type");
        // The native reader selects decimal/legacy A and applies restoredAmperage's default of 0.1.
        double amperage = ((Number) cover.getClass()
            .getMethod("getAmperage")
            .invoke(cover)).doubleValue();
        return ((Number) Class.forName(MATHS)
            .getMethod("bufferCapacity", long.class, double.class)
            .invoke(null, (long) data.getInteger("voltage"), amperage)).longValue();
    }
}
