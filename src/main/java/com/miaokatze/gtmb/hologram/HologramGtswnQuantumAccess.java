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

/** Exact optional GTSWN 1.8.67 authority. A broken modern API never falls back to legacy AE checks. */
final class HologramGtswnQuantumAccess {

    private static final String PACKAGE = "com.miaokatze.gtswn.common.quantum.";
    private static Api cached;

    private HologramGtswnQuantumAccess() {}

    static final class Failure extends IOException {

        private static final long serialVersionUID = 1L;

        Failure(Throwable cause) {
            super("GTSWN 记名量子安全接口不可用，已阻止施工；请核对兼容版本，不会按旧权限规则放行", cause);
        }
    }

    static Method requireStatic(Class<?> type, String name, Class<?> result, Class<?>... parameters)
        throws ReflectiveOperationException {
        Method method = type.getMethod(name, parameters);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
            throw new NoSuchMethodException("GTSWN authority signature changed: " + name);
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
            cached = new Api(access, loader);
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
        if (api != null) invoke(api.synchronize, null, player, terminal);
    }

    static boolean validate(EntityPlayer player, ItemStack terminal) throws IOException {
        Api api = api();
        return api == null || decision(api.validate, player, terminal);
    }

    static boolean canAccess(EntityPlayer player, ItemStack terminal, World world, int x, int y, int z)
        throws IOException {
        Api api = api();
        return api == null || decision(api.canAccess, player, terminal, world, x, y, z);
    }

    static boolean securityRequired(World world, int x, int y, int z) throws IOException {
        Api api = api();
        return api != null && decision(api.required, world, x, y, z);
    }

    /** Shared by every authorization call: false stays false, and an invocation error stays a blocker. */
    static boolean decision(Method authority, Object... arguments) throws IOException {
        return (Boolean) invoke(authority, null, arguments);
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
        Api api = api();
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
        Api api = api();
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

        final Method synchronize, validate, canAccess, required, registry, pack, current, journal, resolve;
        final Constructor<?> anchor;
        final Field resolvedAnchor, resolvedCursor;
        final Method[] coordinates = new Method[4];

        Api(Class<?> type, ClassLoader loader) throws ReflectiveOperationException {
            synchronize = requireStatic(type, "synchronize", boolean.class, EntityPlayer.class, ItemStack.class);
            validate = requireStatic(type, "validate", boolean.class, EntityPlayer.class, ItemStack.class);
            canAccess = requireStatic(
                type,
                "canAccessAnchor",
                boolean.class,
                EntityPlayer.class,
                ItemStack.class,
                World.class,
                int.class,
                int.class,
                int.class);
            required = requireStatic(
                type,
                "isSecurityRequired",
                boolean.class,
                World.class,
                int.class,
                int.class,
                int.class);
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
