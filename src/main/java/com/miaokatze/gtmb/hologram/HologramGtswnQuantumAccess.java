package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import appeng.api.features.INetworkEncodable;

/** Optional native migration synchronization and controller identity; no GTMB terminal security gate. */
final class HologramGtswnQuantumAccess {

    private static final String PACKAGE = "com.miaokatze.gtswn.common.quantum.";
    private static Api cached;
    private static GenerationApi generations;

    private HologramGtswnQuantumAccess() {}

    static final class Failure extends IOException {

        private static final long serialVersionUID = 1L;

        Failure(Throwable cause) {
            super("GTSWN 量子同步或原网络世代接口不可用，不能确定目标锚点；请核对兼容版本", cause);
        }
    }

    static Method requireStatic(Class<?> type, String name, Class<?> result, Class<?>... parameters)
        throws ReflectiveOperationException {
        Method method = type.getMethod(name, parameters);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
            throw new NoSuchMethodException("GTSWN native API signature changed: " + name);
        return method;
    }

    private static Api api() throws IOException {
        if (cached != null) return cached;
        ClassLoader loader = HologramGtswnQuantumAccess.class.getClassLoader();
        Class<?> access;
        try {
            access = Class.forName(PACKAGE + "QuantumTerminalAccess", false, loader);
        } catch (ClassNotFoundException legacy) {
            try {
                Class<?> terminal = Class
                    .forName("com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal", false, loader);
                if (INetworkEncodable.class.isAssignableFrom(terminal)) throw new Failure(legacy);
            } catch (ClassNotFoundException optionalModAbsent) {
                // Neither the optional mod nor its authority exists.
            } catch (LinkageError brokenModernMod) {
                throw new Failure(brokenModernMod);
            }
            return null;
        } catch (LinkageError brokenModernMod) {
            throw new Failure(brokenModernMod);
        }
        try {
            cached = new Api(access);
            return cached;
        } catch (ReflectiveOperationException | LinkageError changed) {
            throw new Failure(changed);
        }
    }

    static boolean modern() throws IOException {
        return api() != null;
    }

    static void synchronize(EntityPlayer player, ItemStack terminal) throws IOException {
        Api api = api();
        if (api != null) synchronizeWith(api.synchronize, player, terminal);
    }

    /** The native boolean means 'changed'. An unchanged terminal needs no mutation and is still usable. */
    static void synchronizeWith(Method synchronize, EntityPlayer player, ItemStack terminal) throws IOException {
        invoke(synchronize, null, player, terminal);
    }

    private static GenerationApi generationApi() throws IOException {
        if (generations != null) return generations;
        ClassLoader loader = HologramGtswnQuantumAccess.class.getClassLoader();
        try {
            // The migration journal already existed in 1.8.66. Named-node attribution marks the
            // reviewed generation-aware format without invoking any terminal or security API.
            if (!namedNodeType(Class.forName(HologramNetworkBindings.NODE_TILE, false, loader))) return null;
        } catch (ClassNotFoundException legacy) {
            return null;
        } catch (LinkageError brokenModernMod) {
            throw new Failure(brokenModernMod);
        }
        try {
            generations = new GenerationApi(loader);
            return generations;
        } catch (ReflectiveOperationException | LinkageError changed) {
            throw new Failure(changed);
        }
    }

    static boolean generationAware() throws IOException {
        return generationApi() != null;
    }

    static boolean namedNode(TileEntity tile) throws IOException {
        return namedNodeType(tile.getClass());
    }

    static boolean namedNodeType(Class<?> type) throws IOException {
        Method uuid = null, name = null;
        try {
            uuid = type.getMethod("getPlacerUUID");
        } catch (NoSuchMethodException legacy) {
            // Both getters are absent in the reviewed legacy format.
        }
        try {
            name = type.getMethod("getPlacerName");
        } catch (NoSuchMethodException legacy) {
            // A partially present format below is an interface error, never a legacy fallback.
        }
        if (uuid == null && name == null) return false;
        if (uuid == null || name == null
            || uuid.getReturnType() != String.class
            || name.getReturnType() != String.class
            || Modifier.isStatic(uuid.getModifiers())
            || Modifier.isStatic(name.getModifiers()))
            throw new Failure(new NoSuchMethodException("node attribution type changed"));
        return true;
    }

    static String controllerIdentity(TileEntity tile) throws IOException {
        if (tile == null) return "";
        try {
            Method getter = tile.getClass()
                .getMethod("gtswn$getControllerIdentity");
            if (getter.getReturnType() != String.class) throw new NoSuchMethodException("controller identity type");
            return (String) invoke(getter, tile);
        } catch (ReflectiveOperationException failure) {
            throw new Failure(failure);
        }
    }

    static boolean currentController(World world, int x, int y, int z) throws IOException {
        GenerationApi api = generationApi();
        if (api == null) return true;
        Object registry = invoke(api.registry, null, world);
        long position = (Long) invoke(api.pack, null, x, y, z);
        return (Boolean) invoke(api.current, registry, world, position);
    }

    static final class Migration {

        final int[] anchor;
        final long cursor;

        Migration(int[] anchor, long cursor) {
            this.anchor = anchor;
            this.cursor = cursor;
        }
    }

    static Migration follow(World world, int[] anchor, long cursor) throws IOException {
        GenerationApi api = generationApi();
        if (api == null) return new Migration(anchor.clone(), cursor);
        try {
            Object journal = invoke(api.journal, null, world);
            Object key = api.anchor.newInstance(anchor[0], anchor[1], anchor[2], anchor[3]);
            Object resolution = invoke(api.resolve, journal, key, cursor);
            Object target = api.resolvedAnchor.get(resolution);
            int[] result = new int[4];
            for (int i = 0; i < result.length; i++) result[i] = (Integer) invoke(api.coordinates[i], target);
            return new Migration(result, api.resolvedCursor.getLong(resolution));
        } catch (ReflectiveOperationException failure) {
            throw new Failure(failure);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws IOException {
        try {
            return method.invoke(receiver, args);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new Failure(failure);
        }
    }

    private static final class Api {

        final Method synchronize;

        Api(Class<?> type) throws ReflectiveOperationException {
            synchronize = requireStatic(type, "synchronize", boolean.class, EntityPlayer.class, ItemStack.class);
        }
    }

    private static final class GenerationApi {

        final Method registry, pack, current, journal, resolve;
        final Constructor<?> anchor;
        final Field resolvedAnchor, resolvedCursor;
        final Method[] coordinates = new Method[4];

        GenerationApi(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> controllers = Class.forName(PACKAGE + "QuantumControllerRegistry", false, loader);
            registry = requireStatic(controllers, "get", controllers, World.class);
            pack = requireStatic(controllers, "pack", long.class, int.class, int.class, int.class);
            current = controllers.getMethod("isCurrentController", World.class, long.class);
            if (Modifier.isStatic(current.getModifiers()) || current.getReturnType() != boolean.class)
                throw new NoSuchMethodException("controller generation API changed");
            Class<?> journals = Class.forName(PACKAGE + "QuantumMigrationJournal", false, loader);
            Class<?> key = Class.forName(PACKAGE + "AnchorKey", false, loader);
            Class<?> resolution = Class.forName(PACKAGE + "QuantumMigrationJournal$Resolution", false, loader);
            journal = requireStatic(journals, "get", journals, World.class);
            anchor = key.getConstructor(int.class, int.class, int.class, int.class);
            resolve = journals.getMethod("resolve", key, long.class);
            if (Modifier.isStatic(resolve.getModifiers()) || resolve.getReturnType() != resolution)
                throw new NoSuchMethodException("migration API changed");
            resolvedAnchor = resolution.getField("anchor");
            resolvedCursor = resolution.getField("sequence");
            if (resolvedAnchor.getType() != key || resolvedCursor.getType() != long.class)
                throw new NoSuchFieldException("migration result changed");
            String[] names = { "getDimension", "getX", "getY", "getZ" };
            for (int i = 0; i < names.length; i++) {
                coordinates[i] = key.getMethod(names[i]);
                if (coordinates[i].getReturnType() != int.class) throw new NoSuchMethodException(names[i]);
            }
        }
    }
}
