package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Installed-EnderIO assertions on live upgrade identities and a deliberately unsynchronized liquid network. */
public final class HologramNativeCaptureSmoke {

    private HologramNativeCaptureSmoke() {}

    public static void run(WorldServer world) throws Exception {
        Block bundle = null;
        for (Block block : HologramNativeCompatibilityFixture.blocks()) if (block.getClass()
            .getName()
            .equals("crazypants.enderio.conduit.BlockConduitBundle")) bundle = block;
        require(bundle != null, "read-only capture smoke requires EnderIO");
        int x = 384, y = 88, z = 112;
        require(
            world.isAirBlock(x, y, z) && world.isAirBlock(x + 1, y, z),
            "capture smoke fixture region must be clear");
        try {
            TileEntity first = install(world, bundle, x, y, z);
            Object item = conduits(first).get(0);
            Object power = conduits(first).get(1);
            set(power, "energyStoredRF", 1234);
            Item filterItem = (Item) Class.forName("crazypants.enderio.EnderIO")
                .getField("itemBasicFilterUpgrade")
                .get(null);
            ItemStack input = new ItemStack(filterItem), output = new ItemStack(filterItem);
            output.setTagCompound(new NBTTagCompound());
            output.getTagCompound()
                .setString("fixture", "unchanged source");
            put(item, "inputFilterUpgrades", ForgeDirection.NORTH, input);
            put(item, "outputFilterUpgrades", ForgeDirection.EAST, output);
            Class<?> filterClass = Class.forName("crazypants.enderio.conduit.item.filter.ItemFilter");
            Object inputFilter = filterClass.getConstructor()
                .newInstance();
            filterClass.getMethod("setInventorySlotContents", int.class, ItemStack.class)
                .invoke(inputFilter, 0, new ItemStack(Blocks.stone));
            put(item, "inputFilters", ForgeDirection.NORTH, inputFilter);
            Object outputFilter = filterClass.getConstructor()
                .newInstance();
            filterClass.getMethod("setInventorySlotContents", int.class, ItemStack.class)
                .invoke(outputFilter, 0, new ItemStack(Blocks.planks));
            put(item, "outputFilters", ForgeDirection.EAST, outputFilter);

            assertSourceUnchanged(first, () -> {
                NBTTagCompound saved = HologramNativeCapture.capture(first);
                NBTTagCompound capturedItem = saved.getTagList("conduits", 10)
                    .getCompoundTagAt(0)
                    .getCompoundTag("conduit");
                require(
                    capturedItem.hasKey("inputFilterUpgrades.NORTH", 10)
                        && capturedItem.getCompoundTag("inputFilterUpgrades.NORTH")
                            .getCompoundTag("tag")
                            .hasKey("filter", 10),
                    "captured upgrade must retain the native filter data while the source stack remains unmodified");
                require(
                    saved.getTagList("conduits", 10)
                        .getCompoundTagAt(1)
                        .getCompoundTag("conduit")
                        .getInteger("energyStoredRF") == 1234,
                    "capture must preserve stored RF");
                HologramDisplaySnapshot.capture(first);
                HologramBlueprintNbt.encodeCaptured(saved);
                HologramNativePlacement.sample(bundleBlock(first), 0, first);
            });

            TileEntity second = install(world, bundle, x + 1, y, z);
            Object left = Class.forName("crazypants.enderio.conduit.liquid.AdvancedLiquidConduit")
                .getConstructor()
                .newInstance();
            Object right = left.getClass()
                .getConstructor()
                .newInstance();
            Class<?> conduitType = Class.forName("crazypants.enderio.conduit.IConduit");
            first.getClass()
                .getMethod("addConduit", conduitType)
                .invoke(first, left);
            second.getClass()
                .getMethod("addConduit", conduitType)
                .invoke(second, right);
            Object network = Class.forName("crazypants.enderio.conduit.liquid.AdvancedLiquidConduitNetwork")
                .getConstructor()
                .newInstance();
            @SuppressWarnings("unchecked")
            List<Object> members = (List<Object>) get(network, "conduits");
            members.add(left);
            members.add(right);
            set(left, "network", network);
            set(right, "network", network);
            FluidStack networkType = new FluidStack(FluidRegistry.WATER, 0);
            set(network, "liquidType", networkType);
            Object sharedTank = get(network, "tank");
            sharedTank.getClass()
                .getMethod("setCapacity", int.class)
                .invoke(sharedTank, 4000);
            sharedTank.getClass()
                .getMethod("setLiquid", FluidStack.class)
                .invoke(sharedTank, new FluidStack(FluidRegistry.WATER, 1001));
            Object leftTank = get(left, "tank"), rightTank = get(right, "tank");
            leftTank.getClass()
                .getMethod("setCapacity", int.class)
                .invoke(leftTank, 900);
            rightTank.getClass()
                .getMethod("setCapacity", int.class)
                .invoke(rightTank, 901);
            leftTank.getClass()
                .getMethod("setLiquid", FluidStack.class)
                .invoke(leftTank, new FluidStack(FluidRegistry.WATER, 77));
            rightTank.getClass()
                .getMethod("setLiquid", FluidStack.class)
                .invoke(rightTank, new FluidStack(FluidRegistry.WATER, 66));
            set(network, "lastSyncedVolume", -987);
            assertSourceUnchanged(first, () -> assertSourceUnchanged(second, () -> {
                NBTTagCompound a = HologramNativeCapture.capture(first), b = HologramNativeCapture.capture(second);
                require(
                    tankAmount(a, 2) == 501 && tankAmount(b, 2) == 500,
                    "read-only capture preserves shared network total and native remainder order");
                HologramDisplaySnapshot.capture(first);
                HologramDisplaySnapshot.capture(second);
                // sample determines physical components. Stored-fluid payment is checked
                // independently by BlueprintResources.plan during the real service action.
                HologramNativePlacement.sample(bundleBlock(first), 0, first);
            }));
            set(network, "lastSyncedVolume", 1001);
            assertSourceUnchanged(first, () -> assertSourceUnchanged(second, () -> {
                NBTTagCompound a = HologramNativeCapture.capture(first), b = HologramNativeCapture.capture(second);
                require(
                    tankAmount(a, 2) == 77 && tankAmount(b, 2) == 66,
                    "unchanged-volume capture preserves native local amounts rather than redistributing them");
                HologramDisplaySnapshot.capture(first);
                HologramNativePlacement.sample(bundleBlock(first), 0, first);
            }));
        } finally {
            world.setBlockToAir(x, y, z);
            world.setBlockToAir(x + 1, y, z);
        }
    }

    @FunctionalInterface
    public interface Action {

        void run() throws Exception;
    }

    /** Service/GUI/NBT-key smoke owners can wrap their own real actions with the same source-state guard. */
    public static void assertSourceUnchanged(TileEntity source, Action action) throws Exception {
        List<Guard> guards = guards(source);
        try {
            action.run();
        } finally {
            for (Guard guard : guards) guard.verify();
        }
    }

    private static List<Guard> guards(TileEntity source) throws Exception {
        List<Guard> guards = new ArrayList<>();
        net.minecraft.world.World sourceWorld = source.getWorldObj();
        guards.add(() -> require(source.getWorldObj() == sourceWorld, "capture replaced the source world"));
        for (Object conduit : conduits(source)) {
            guards.add(new IdentityGuard(conduit, "bundle"));
            Object network = optional(conduit, "network");
            if (hasField(conduit, "network")) guards.add(new IdentityGuard(conduit, "network"));
            if (conduit.getClass()
                .getName()
                .endsWith("ItemConduit")) {
                for (String name : new String[] { "inputFilterUpgrades", "outputFilterUpgrades", "speedUpgrades",
                    "functionUpgrades" })
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) get(conduit, name)).entrySet())
                        if (entry.getValue() != null) guards.add(
                            new StackGuard(
                                (Map<?, ?>) get(conduit, name),
                                entry.getKey(),
                                (ItemStack) entry.getValue()));
            }
            if (hasField(conduit, "energyStoredRF")) guards.add(new ValueGuard(conduit, "energyStoredRF"));
            if (hasField(conduit, "tank")) guards.add(new TankGuard(get(conduit, "tank")));
            if (network != null && hasField(network, "tank")) {
                guards.add(new TankGuard(get(network, "tank")));
                if (hasField(network, "lastSyncedVolume")) guards.add(new ValueGuard(network, "lastSyncedVolume"));
                if (hasField(network, "liquidType")) guards.add(new IdentityGuard(network, "liquidType"));
            }
        }
        return guards;
    }

    private interface Guard {

        void verify() throws Exception;
    }

    private static final class IdentityGuard implements Guard {

        final Object owner, original;
        final String field;

        IdentityGuard(Object owner, String field) throws Exception {
            this.owner = owner;
            this.field = field;
            original = get(owner, field);
        }

        public void verify() throws Exception {
            require(get(owner, field) == original, "capture changed source identity " + field);
        }
    }

    private static final class ValueGuard implements Guard {

        final Object owner, original;
        final String field;

        ValueGuard(Object owner, String field) throws Exception {
            this.owner = owner;
            this.field = field;
            original = get(owner, field);
        }

        public void verify() throws Exception {
            require(original.equals(get(owner, field)), "capture changed source value " + field);
        }
    }

    private static final class StackGuard implements Guard {

        final Map<?, ?> map;
        final Object key;
        final ItemStack stack;
        final NBTTagCompound tagIdentity, value;

        StackGuard(Map<?, ?> map, Object key, ItemStack stack) {
            this.map = map;
            this.key = key;
            this.stack = stack;
            tagIdentity = stack.getTagCompound();
            value = tagIdentity == null ? null : (NBTTagCompound) tagIdentity.copy();
        }

        public void verify() {
            require(
                map.get(key) == stack && stack.getTagCompound() == tagIdentity,
                "capture replaced live upgrade stack/tag identity");
            require(
                value == null ? stack.getTagCompound() == null : value.equals(stack.getTagCompound()),
                "capture rewrote live filter upgrade NBT");
        }
    }

    private static final class TankGuard implements Guard {

        final Object tank;
        final FluidStack fluidIdentity;
        final NBTTagCompound fluidValue;
        final int capacity;

        TankGuard(Object tank) throws Exception {
            this.tank = tank;
            capacity = (Integer) tank.getClass()
                .getMethod("getCapacity")
                .invoke(tank);
            fluidIdentity = (FluidStack) tank.getClass()
                .getMethod("getFluid")
                .invoke(tank);
            fluidValue = fluidIdentity == null ? null
                : fluidIdentity.copy()
                    .writeToNBT(new NBTTagCompound());
        }

        public void verify() throws Exception {
            require(
                (Integer) tank.getClass()
                    .getMethod("getCapacity")
                    .invoke(tank) == capacity,
                "capture updated live conduit/network capacity");
            FluidStack fluid = (FluidStack) tank.getClass()
                .getMethod("getFluid")
                .invoke(tank);
            require(fluid == fluidIdentity, "capture replaced live conduit/network fluid identity");
            require(
                fluidValue == null ? fluid == null
                    : fluidValue.equals(
                        fluid.copy()
                            .writeToNBT(new NBTTagCompound())),
                "capture redistributed or changed live conduit/network fluid");
        }
    }

    private static TileEntity install(WorldServer world, Block block, int x, int y, int z) throws Exception {
        require(world.setBlock(x, y, z, block, 0, 3), "native capture fixture block install failed");
        TileEntity tile = world.getTileEntity(x, y, z);
        require(tile != null, "native capture fixture tile factory failed");
        tile.readFromNBT(HologramNativeCompatibilityFixture.seed(block, x, y, z));
        return tile;
    }

    private static Block bundleBlock(TileEntity tile) {
        return tile.getWorldObj()
            .getBlock(tile.xCoord, tile.yCoord, tile.zCoord);
    }

    private static int tankAmount(NBTTagCompound tag, int index) {
        return tag.getTagList("conduits", 10)
            .getCompoundTagAt(index)
            .getCompoundTag("conduit")
            .getCompoundTag("tank")
            .getInteger("Amount");
    }

    private static List<?> conduits(TileEntity tile) throws Exception {
        return (List<?>) get(tile, "conduits");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void put(Object target, String name, Object key, Object value) throws Exception {
        ((Map) get(target, name)).put(key, value);
    }

    private static Object optional(Object target, String name) throws Exception {
        return hasField(target, name) ? get(target, name) : null;
    }

    private static boolean hasField(Object target, String name) {
        try {
            field(target.getClass(), name);
            return true;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException absent) {}
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
