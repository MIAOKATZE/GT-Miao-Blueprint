package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import appeng.api.config.SecurityPermissions;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.util.DimensionalCoord;
import appeng.helpers.ICustomButtonDataObject;
import appeng.helpers.ICustomButtonProvider;
import appeng.helpers.ICustomButtonSource;
import appeng.helpers.WirelessTerminalGuiObject;

/** Installed native range code with detached WAPs; no claim that a real FML network was exercised. */
final class HologramWirelessAccessRegression {

    private HologramWirelessAccessRegression() {}

    static void run() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            RangeWorld world = (RangeWorld) unsafe.allocateInstance(RangeWorld.class);
            set(World.class, world, "provider", new net.minecraft.world.WorldProviderSurface());
            world.tick = 1;
            Method factory = HologramRegressionTest.class.getDeclaredMethod("materialPlayer");
            factory.setAccessible(true);
            EntityPlayerMP player = (EntityPlayerMP) factory.invoke(null);
            player.worldObj = world;
            player.posY = 64;
            player.posX = .5;
            boolean[] extract = { true }, craft = { true };
            ISecurityGrid security = proxy(ISecurityGrid.class, (p, method, args) -> {
                if (!"hasPermission".equals(method.getName())) throw new AssertionError(method.getName());
                return args[1] == SecurityPermissions.EXTRACT ? extract[0]
                    : args[1] == SecurityPermissions.CRAFT ? craft[0] : true;
            });
            IGrid[] grid = { null };
            IGridNode[] nodes = new IGridNode[2];
            for (int index = 0; index < nodes.length; index++) {
                int x = index * 20;
                IWirelessAccessPoint wap = proxy(IWirelessAccessPoint.class, (p, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation":
                            return new DimensionalCoord(world, x, 64, 0);
                        case "getRange":
                            return 10d;
                        case "isActive":
                            return true;
                        case "getGrid":
                            return grid[0];
                        case "getActionableNode":
                        case "getGridNode":
                            return nodes[x / 20];
                        default:
                            throw new AssertionError(method.getName());
                    }
                });
                nodes[index] = proxy(IGridNode.class, (p, method, args) -> {
                    switch (method.getName()) {
                        case "getMachine":
                            return wap;
                        case "getGrid":
                            return grid[0];
                        case "isActive":
                            return true;
                        default:
                            throw new AssertionError(method.getName());
                    }
                });
            }
            IMachineSet machines = proxy(IMachineSet.class, (p, method, args) -> {
                if ("iterator".equals(method.getName())) return Arrays.asList(nodes)
                    .iterator();
                throw new AssertionError(method.getName());
            });
            int[] scans = { 0 };
            grid[0] = proxy(IGrid.class, (p, method, args) -> {
                if ("getMachines".equals(method.getName())) {
                    scans[0]++;
                    return machines;
                }
                if ("getCache".equals(method.getName()) && args[0] == ISecurityGrid.class) return security;
                throw new AssertionError(method.getName());
            });
            String[] key = { "headless-native-range" };
            IWirelessTermHandler handler = proxy(IWirelessTermHandler.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getEncryptionKey":
                        return key[0];
                    case "hasInfinityRange":
                    case "hasInfinityPower":
                        return false;
                    case "hasPower":
                        return true;
                    default:
                        throw new AssertionError(method.getName());
                }
            });
            RangeTerminalItem item = new RangeTerminalItem();
            item.grid = grid[0];
            ItemStack carried = new ItemStack(item);
            carried.setTagCompound(new NBTTagCompound());
            player.inventory.mainInventory[2] = carried;
            WirelessTerminalGuiObject original = new WirelessTerminalGuiObject(
                handler,
                carried,
                player,
                world,
                -1,
                0,
                0);
            require(original.rangeCheck() && scans[0] == 1, "native range initially selects WAP A");
            player.posX = 20.5;
            require(
                !original.rangeCheck() && scans[0] == 1,
                "installed AE retains the out-of-range WAP instead of discovering WAP B");

            HologramMaterials.Settings settings = new HologramMaterials.Settings();
            settings.me = settings.containers = false;
            HologramMaterials.Context context = new HologramMaterials.Context(player, settings);
            set(HologramMaterials.Context.class, context, "terminal", original);
            set(HologramMaterials.Context.class, context, "terminalStack", carried);
            set(HologramMaterials.Context.class, context, "terminalHandler", handler);
            set(HologramMaterials.Context.class, context, "terminalKey", key[0]);
            settings.me = true;
            require(
                context.craftingGrid() == grid[0] && scans[0] == 2 && item.constructions == 2,
                "production context rediscovers B while retaining the same authenticated grid");
            extract[0] = false;
            require(context.craftingGrid() == null, "revoked extract permission invalidates crafting access");
            extract[0] = true;
            craft[0] = false;
            require(context.craftingGrid() == null, "revoked crafting permission invalidates crafting access");
            craft[0] = true;

            player.posX = 100;
            require(context.craftingGrid() == null, "both WAPs out of range invalidate access");
            int unavailableScans = scans[0], unavailableConstructions = item.constructions;
            for (int query = 0; query < 32; query++)
                require(context.craftingGrid() == null, "offline range remains unavailable");
            require(
                scans[0] == unavailableScans && item.constructions == unavailableConstructions,
                "repeated material queries in one world tick do not repeatedly scan or rebuild the terminal");
            player.posX = .5;
            require(context.craftingGrid() == null, "range rediscovery waits for the next world tick");
            world.tick++;
            require(
                context.craftingGrid() == grid[0],
                "the next tick recovers when the carried terminal returns to range");
            key[0] = "rebound-terminal";
            require(context.craftingGrid() == null, "rebinding a carried terminal cannot reuse an old authorization");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Wireless access regression", failure);
        }
    }

    /** The native custom-button hook injects only the detached grid; all WAP choices remain native AE decisions. */
    private static final class RangeTerminalItem extends Item implements ICustomButtonSource {

        IGrid grid;
        int constructions;

        @Override
        public ICustomButtonDataObject getCustomDataObject(ICustomButtonProvider provider) {
            try {
                set(WirelessTerminalGuiObject.class, provider, "targetGrid", grid);
                constructions++;
                return proxy(ICustomButtonDataObject.class, (p, method, args) -> {
                    if ("readData".equals(method.getName())) return null;
                    throw new AssertionError(method.getName());
                });
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class RangeWorld extends WorldServer {

        long tick;

        private RangeWorld() {
            super(null, null, "wireless-range", 0, null, null);
        }

        @Override
        public long getTotalWorldTime() {
            return tick;
        }
    }

    private static void set(Class<?> type, Object target, String name, Object value)
        throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
