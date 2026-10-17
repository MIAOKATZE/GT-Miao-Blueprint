package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

/** Single native capture boundary. EnderIO serializers with source mutations are never invoked on live objects. */
public final class HologramNativeCapture {

    public interface Adapter {

        boolean supports(TileEntity tile);

        /** Return the complete persistence schema without changing the source, its inventory, or its network. */
        NBTTagCompound capture(TileEntity tile) throws Exception;
    }

    public interface ConduitAdapter {

        boolean supports(Object conduit);

        /** Complete conduit body (the bundle adds conduitType); source and connected network must remain unchanged. */
        NBTTagCompound capture(Object conduit) throws Exception;
    }

    private static final List<Adapter> ADAPTERS = new CopyOnWriteArrayList<>();
    private static final List<ConduitAdapter> CONDUIT_ADAPTERS = new CopyOnWriteArrayList<>();
    private static final String EIO = "crazypants.enderio.conduit.";
    private static final Set<String> READ_ONLY_CONDUITS = new HashSet<>(
        Arrays.asList(
            EIO + "power.PowerConduit",
            EIO + "redstone.RedstoneConduit",
            EIO + "redstone.InsulatedRedstoneConduit",
            EIO + "me.MEConduit",
            EIO + "oc.OCConduit",
            EIO + "liquid.EnderLiquidConduit",
            EIO + "liquid.CrystallineEnderLiquidConduit",
            EIO + "liquid.CrystallinePinkSlimeEnderLiquidConduit",
            EIO + "liquid.MelodicEnderLiquidConduit",
            EIO + "liquid.StellarEnderLiquidConduit"));
    private static final Set<String> READ_ONLY_ITEM_FILTERS = new HashSet<>(
        Arrays.asList(
            EIO + "item.filter.ItemFilter",
            EIO + "item.filter.ItemFilterBig",
            EIO + "item.filter.ItemFilterLimited",
            EIO + "item.filter.ExistingItemFilter",
            EIO + "item.filter.ModItemFilter",
            EIO + "item.filter.PowerItemFilter"));

    private HologramNativeCapture() {}

    public static void register(Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("capture adapter");
        ADAPTERS.add(adapter);
    }

    public static boolean unregister(Adapter adapter) {
        return ADAPTERS.remove(adapter);
    }

    public static boolean unregisterConduit(ConduitAdapter adapter) {
        return CONDUIT_ADAPTERS.remove(adapter);
    }

    public static void registerConduit(ConduitAdapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("conduit capture adapter");
        CONDUIT_ADAPTERS.add(adapter);
    }

    public static NBTTagCompound capture(TileEntity tile) throws IOException {
        if (tile == null) return new NBTTagCompound();
        try {
            for (Adapter adapter : ADAPTERS) if (adapter.supports(tile)) {
                NBTTagCompound adapted = checked(adapter.capture(tile));
                HologramDisplaySnapshot.enrichNativeTile(tile, adapted);
                HologramNetworkBindings.capture(tile, adapted);
                return checked(adapted);
            }
            NBTTagCompound tag;
            if (isA(tile, EIO + "TileConduitBundle")) {
                if (!tile.getClass()
                    .getName()
                    .equals(EIO + "TileConduitBundle"))
                    throw new IOException(
                        "EnderIO bundle subtype requires a read-only capture provider: " + tile.getClass()
                            .getName());
                tag = bundle(tile);
            } else {
                // Existing vanilla/GT and native shape serializers write their output only.
                // Mutating third-party formats can replace this boundary with an explicit adapter.
                tag = new NBTTagCompound();
                tile.writeToNBT(tag);
            }
            HologramDisplaySnapshot.enrichNativeTile(tile, tag);
            HologramNetworkBindings.capture(tile, tag);
            return checked(tag);
        } catch (IOException unsupported) {
            throw unsupported;
        } catch (Exception | LinkageError unavailable) {
            throw new IOException(
                "Native capture failed for " + tile.getClass()
                    .getName(),
                unavailable);
        }
    }

    private static NBTTagCompound checked(NBTTagCompound tag) throws IOException {
        if (tag == null || tag.hasNoTags()) throw new IOException("Native capture provider omitted the tile payload");
        return (NBTTagCompound) tag.copy();
    }

    private static boolean isA(Object object, String name) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) if (type.getName()
            .equals(name)) return true;
        return false;
    }

    private static NBTTagCompound bundle(TileEntity source) throws Exception {
        if (get(source, "covers") != null)
            throw new IOException("EnderIO Immibis cover payload requires a read-only capture provider");
        TileEntity detached = source.getClass()
            .asSubclass(TileEntity.class)
            .getConstructor()
            .newInstance();
        detached.xCoord = source.xCoord;
        detached.yCoord = source.yCoord;
        detached.zCoord = source.zCoord;
        for (String name : new String[] { "facadeId", "facadeMeta", "facadeType" })
            set(detached, name, get(source, name));
        // Some Forge environments add lazy tile data. Read that field if it exists;
        // the target 1.7.10 TileEntity has no such field or extra base payload.
        try {
            Object forgeData = get(source, "customTileData");
            if (forgeData != null) set(detached, "customTileData", ((NBTTagCompound) forgeData).copy());
        } catch (NoSuchFieldException absentInThisMinecraftVersion) {}
        NBTTagCompound result = new NBTTagCompound();
        // The new bundle is empty and has no world/network; this invokes no source conduit serializer.
        detached.writeToNBT(result);
        NBTTagList rows = new NBTTagList();
        for (Object conduit : (Collection<?>) get(source, "conduits")) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString(
                "conduitType",
                conduit.getClass()
                    .getCanonicalName());
            row.setTag("conduit", conduit(conduit));
            rows.appendTag(row);
        }
        result.setTag("conduits", rows);
        return result;
    }

    private static NBTTagCompound conduit(Object source) throws Exception {
        for (ConduitAdapter adapter : CONDUIT_ADAPTERS)
            if (adapter.supports(source)) return checked(adapter.capture(source));
        String name = source.getClass()
            .getName();
        if (name.equals(EIO + "item.ItemConduit")) return item(source);
        if (name.equals(EIO + "liquid.LiquidConduit") || name.equals(EIO + "liquid.AdvancedLiquidConduit"))
            return liquid(source);
        if (name.equals(EIO + "gas.GasConduit")) return gas(source);
        if (!READ_ONLY_CONDUITS.contains(name))
            throw new IOException("Unreviewed EnderIO conduit capture schema: " + name);
        NBTTagCompound tag = new NBTTagCompound();
        invoke(source, "writeToNBT", new Class<?>[] { NBTTagCompound.class }, tag);
        return (NBTTagCompound) tag.copy();
    }

    private static NBTTagCompound item(Object source) throws Exception {
        Object detached = source.getClass()
            .getConstructor()
            .newInstance();
        copyCollection(source, detached, "conduitConnections");
        copyCollection(source, detached, "externalConnections");
        copyMap(source, detached, "conectionModes", false, false);
        set(detached, "active", get(source, "active"));
        set(detached, "metaData", get(source, "metaData"));
        for (String name : new String[] { "extractionModes", "extractionColors", "selfFeed", "roundRobin", "priority",
            "inputColors", "outputColors" }) copyMap(source, detached, name, false, false);
        for (String name : new String[] { "inputFilterUpgrades", "outputFilterUpgrades", "speedUpgrades",
            "functionUpgrades" }) copyMap(source, detached, name, true, false);
        for (String name : new String[] { "inputFilters", "outputFilters" })
            copyMap(source, detached, name, false, true);
        // Constructor defaults keep bundle/network null. Only this clone's upgrade stacks are rewritten by EnderIO.
        NBTTagCompound tag = new NBTTagCompound();
        invoke(detached, "writeToNBT", new Class<?>[] { NBTTagCompound.class }, tag);
        tag.setShort("metaData", ((Number) get(source, "metaData")).shortValue());
        return tag;
    }

    private static NBTTagCompound liquid(Object source) throws Exception {
        NBTTagCompound tag = base(source);
        enumMap(tag, source, "extractionModes", "extRM.");
        enumMap(tag, source, "extractionColors", "extSC.");
        FluidStack type = (FluidStack) invoke(source, "getFluidType");
        Object tank = invoke(source, "getTank");
        int amount = ((Number) invoke(tank, "getFluidAmount")).intValue();
        Object network = invoke(source, "getTankNetwork");
        if (source.getClass()
            .getName()
            .equals(EIO + "liquid.AdvancedLiquidConduit") && network != null)
            amount = networkShare(source, network, "getFluidAmount", amount);
        if (type != null && type.getFluid() != null) {
            FluidStack copied = type.copy();
            copied.amount = amount;
            tag.setTag("tank", copied.writeToNBT(new NBTTagCompound()));
        }
        tag.setBoolean("fluidLocked", (Boolean) get(source, "fluidTypeLocked"));
        return tag;
    }

    private static NBTTagCompound gas(Object source) throws Exception {
        NBTTagCompound tag = base(source);
        enumMap(tag, source, "extractionModes", "extRM.");
        enumMap(tag, source, "extractionColors", "extSC.");
        Object type = invoke(source, "getGasType");
        Object tank = invoke(source, "getTank");
        int amount = ((Number) invoke(tank, "getStored")).intValue();
        Object network = invoke(source, "getTankNetwork");
        if (network != null) amount = networkShare(source, network, "getStored", amount);
        if (type != null) {
            Object copied = invoke(type, "copy");
            set(copied, "amount", amount);
            NBTTagCompound fluid = new NBTTagCompound();
            invoke(copied, "write", new Class<?>[] { NBTTagCompound.class }, fluid);
            tag.setTag("tank", fluid);
        }
        return tag;
    }

    /** Reproduce native division and remainder order without updateTank/updateConduitVolumes or tank writes. */
    private static int networkShare(Object source, Object network, String amountMethod, int fallback) throws Exception {
        List<?> conduits = (List<?>) invoke(network, "getConduits");
        int index = conduits.indexOf(source);
        if (index < 0 || conduits.isEmpty()) return fallback;
        Object tank = get(network, "tank");
        int total = ((Number) invoke(tank, amountMethod)).intValue();
        if (total < 0) throw new IOException("Negative native conduit network contents");
        // The native network skips distribution when its stored volume is unchanged.
        // Newly joined empty conduits therefore must not receive a synthesized share here.
        if (total == ((Number) get(network, "lastSyncedVolume")).intValue()) return fallback;
        return total / conduits.size() + (index < total % conduits.size() ? 1 : 0);
    }

    private static NBTTagCompound base(Object source) throws Exception {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setIntArray("connections", directions((Collection<?>) get(source, "conduitConnections")));
        tag.setIntArray("externalConnections", directions((Collection<?>) get(source, "externalConnections")));
        tag.setBoolean("signalActive", (Boolean) get(source, "active"));
        if (!((Map<?, ?>) get(source, "conectionModes")).isEmpty()) {
            byte[] modes = new byte[6];
            for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
                modes[direction.ordinal()] = (byte) ((Enum<?>) invoke(
                    source,
                    "getConnectionMode",
                    new Class<?>[] { ForgeDirection.class },
                    direction)).ordinal();
            tag.setByteArray("conModes", modes);
        }
        return tag;
    }

    private static int[] directions(Collection<?> collection) {
        int[] result = new int[collection.size()];
        int i = 0;
        for (Object direction : collection) result[i++] = ((Enum<?>) direction).ordinal();
        return result;
    }

    private static void enumMap(NBTTagCompound tag, Object source, String field, String prefix) throws Exception {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) get(source, field)).entrySet()) if (entry.getValue() != null)
            tag.setShort(prefix + ((Enum<?>) entry.getKey()).name(), (short) ((Enum<?>) entry.getValue()).ordinal());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void copyCollection(Object source, Object detached, String field) throws Exception {
        Collection destination = (Collection) get(detached, field);
        destination.clear();
        destination.addAll((Collection) get(source, field));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void copyMap(Object source, Object detached, String field, boolean stacks, boolean filters)
        throws Exception {
        Map destination = (Map) get(detached, field);
        destination.clear();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) get(source, field)).entrySet()) {
            Object value = entry.getValue();
            if (value != null && stacks) value = ((ItemStack) value).copy();
            if (value != null && filters) value = filterCopy(value);
            destination.put(entry.getKey(), value);
        }
    }

    private static Object filterCopy(Object source) throws Exception {
        if (!READ_ONLY_ITEM_FILTERS.contains(
            source.getClass()
                .getName()))
            throw new IOException(
                "Unreviewed EnderIO item filter capture schema: " + source.getClass()
                    .getName());
        NBTTagCompound tag = new NBTTagCompound();
        invoke(source, "writeToNBT", new Class<?>[] { NBTTagCompound.class }, tag);
        Object copy = source.getClass()
            .getConstructor()
            .newInstance();
        invoke(copy, "readFromNBT", new Class<?>[] { NBTTagCompound.class }, tag.copy());
        return copy;
    }

    private static Object invoke(Object source, String name) throws Exception {
        return invoke(source, name, new Class<?>[0]);
    }

    private static Object invoke(Object source, String name, Class<?>[] arguments, Object... values) throws Exception {
        Method method = source.getClass()
            .getMethod(name, arguments);
        return method.invoke(source, values);
    }

    private static Object get(Object source, String name) throws Exception {
        return field(source.getClass(), name).get(source);
    }

    private static void set(Object destination, String name, Object value) throws Exception {
        field(destination.getClass(), name).set(destination, value);
    }

    /** Exact reviewed schema fields only; no object graph walk or copying live bundle/network references. */
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absentHere) {}
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
