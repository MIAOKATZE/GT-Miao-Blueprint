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

    static boolean anchorGenerationAware() throws IOException {
        GenerationApi api = generationApi();
        return api != null && api.generation != null;
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
        final String generation;

        Migration(int[] anchor, long cursor) {
            this(anchor, cursor, "");
        }

        Migration(int[] anchor, long cursor, String generation) {
            this.anchor = anchor;
            this.cursor = cursor;
            this.generation = generation;
        }
    }

    static Migration follow(World world, int[] anchor, long cursor) throws IOException {
        return follow(world, anchor, cursor, "");
    }

    static Migration follow(World world, int[] anchor, long cursor, String generation) throws IOException {
        GenerationApi api = generationApi();
        if (api == null) return new Migration(anchor.clone(), cursor);
        try {
            Object journal = invoke(api.journal, null, world);
            Object key = api.anchor.newInstance(anchor[0], anchor[1], anchor[2], anchor[3]);
            Object resolution = api.generation == null ? invoke(api.resolve, journal, key, cursor)
                : invoke(api.resolve, journal, key, cursor, generation);
            Object target = api.resolvedAnchor.get(resolution);
            int[] result = new int[4];
            for (int i = 0; i < result.length; i++) result[i] = (Integer) invoke(api.coordinates[i], target);
            return new Migration(
                result,
                api.resolvedCursor.getLong(resolution),
                api.generation == null ? "" : (String) api.generation.get(resolution));
        } catch (ReflectiveOperationException failure) {
            throw new Failure(failure);
        }
    }

    static void checkAnchorGeneration(World world, int x, int y, int z, String generation) throws IOException {
        GenerationApi api = generationApi();
        if (api == null || api.generation == null) return;
        if (generation == null || generation.isEmpty()) throw new IOException("量子锚点缺少原生网络世代，请重新绑定终端或采集蓝图");
        try {
            Object registry = invoke(api.registry, null, world);
            long position = (Long) invoke(api.pack, null, x, y, z);
            Object canonical = invoke(api.resolveAnchor, registry, position, generation);
            if (canonical == null || api.canonicalPosition.getLong(canonical) != position
                || !generation.equals(api.canonicalGeneration.get(canonical)))
                throw new IOException("原量子网络世代已不可用，不能沿用相同坐标上的其他网络");
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

        final Method registry, pack, current, journal, resolve, resolveAnchor;
        final Constructor<?> anchor;
        final Field resolvedAnchor, resolvedCursor;
        final Field generation, canonicalPosition, canonicalGeneration;
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
            Method generationResolve = null;
            try {
                generationResolve = journals.getMethod("resolve", key, long.class, String.class);
            } catch (NoSuchMethodException legacy) {
                // Reviewed pre-generation journals only expose the two-argument API.
            }
            resolve = generationResolve == null ? journals.getMethod("resolve", key, long.class) : generationResolve;
            if (Modifier.isStatic(resolve.getModifiers()) || resolve.getReturnType() != resolution)
                throw new NoSuchMethodException("migration API changed");
            resolvedAnchor = resolution.getField("anchor");
            resolvedCursor = resolution.getField("sequence");
            if (resolvedAnchor.getType() != key || resolvedCursor.getType() != long.class)
                throw new NoSuchFieldException("migration result changed");
            if (generationResolve == null) {
                generation = canonicalPosition = canonicalGeneration = null;
                resolveAnchor = null;
            } else {
                generation = resolution.getField("generation");
                Class<?> canonical = Class
                    .forName(PACKAGE + "QuantumControllerRegistry$CanonicalAnchor", false, loader);
                resolveAnchor = controllers.getMethod("resolveAnchor", long.class, String.class);
                canonicalPosition = canonical.getField("position");
                canonicalGeneration = canonical.getField("generation");
                if (generation.getType() != String.class || resolveAnchor.getReturnType() != canonical
                    || canonicalPosition.getType() != long.class
                    || canonicalGeneration.getType() != String.class)
                    throw new NoSuchFieldException("anchor generation API changed");
            }
            String[] names = { "getDimension", "getX", "getY", "getZ" };
            for (int i = 0; i < names.length; i++) {
                coordinates[i] = key.getMethod(names[i]);
                if (coordinates[i].getReturnType() != int.class) throw new NoSuchMethodException(names[i]);
            }
        }
    }
}
