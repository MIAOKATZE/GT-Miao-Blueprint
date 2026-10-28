package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;

import net.minecraft.init.Blocks;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.village.VillageCollection;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.storage.ISaveHandler;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.Side;

/** Run in separate JVMs with CLIENT and SERVER arguments; exercises real Forge side stripping, not a fake World. */
public final class HologramResourceWorldSidedRegression {

    private HologramResourceWorldSidedRegression() {}

    public static void main(String[] args) throws Exception {
        String side = args.length == 0 ? "SERVER" : args[0];
        if (!(HologramResourceWorldSidedRegression.class.getClassLoader() instanceof LaunchClassLoader)) {
            enterSided(side, args);
            return;
        }
        if (!MinecraftTestBootstrap.enter(HologramResourceWorldSidedRegression.class, args)) return;
        SimpleAssert.eq(Side.valueOf(side), FMLLaunchHandler.side(), "physical launch side");
        boolean client = FMLLaunchHandler.side() == Side.CLIENT;
        SimpleAssert.eq(client, hasClientWorldConstructor(), "Forge strips the actual client World constructor");
        SimpleAssert.eq(client, hasClientResourceConstructor(), "Forge strips the resource client constructor");
        HologramResourceWorld.init();
        HologramResourceWorld.init(); // Bootstrap is idempotent and declarations no longer register at runtime.
        Field saveCache = field(World.class, "s_savehandler");
        Field mapCache = field(World.class, "s_mapStorage");
        Object oldSave = saveCache.get(null), oldMap = mapCache.get(null);
        try {
            HologramResourceWorld seed = HologramResourceWorld.create(Blocks.stone, 3);
            saveCache.set(null, seed.getSaveHandler());
            mapCache.set(null, seed.mapStorage);
            HologramResourceWorld created = HologramResourceWorld.create(Blocks.wool, 5);
            SimpleAssert.that(saveCache.get(null) == seed.getSaveHandler(), "factory preserves live save cache");
            SimpleAssert.that(mapCache.get(null) == seed.mapStorage, "factory preserves live map cache");
            checkDetached(created);
            SimpleAssert.that(created.mapStorage != seed.mapStorage, "each world owns map storage");
            SimpleAssert.that(created.perWorldStorage != seed.perWorldStorage, "each world owns per-world storage");
            HologramResourceWorld declarations = HologramResourceWorld.declarations(Blocks.wool, 5);
            declarations.tile = new TileEntity();
            declarations.tile.xCoord = 7;
            declarations.tile.yCoord = 90;
            declarations.tile.zCoord = -4;
            SimpleAssert.that(declarations.blockExists(100, 100, 100), "declarations expose remote coordinates");
            SimpleAssert.that(declarations.getBlock(100, 100, 100) == Blocks.air, "declarations keep neighbors air");
            SimpleAssert.that(declarations.getBlock(7, 90, -4) == Blocks.wool, "declarations preserve tile position");
            SimpleAssert.that(gregtech.api.GregTechAPI.isDummyWorld(declarations), "GT native dummy-world branch");
            if (client) checkDuringClientConstruction(seed, created, saveCache, mapCache);
        } finally {
            saveCache.set(null, oldSave);
            mapCache.set(null, oldMap);
        }
        System.out.println("PASS resource_world_" + side.toLowerCase() + "_real_forge_side_and_storage_isolation");
    }

    private static void checkDetached(HologramResourceWorld world) {
        SimpleAssert.that(!world.isRemote, "native persistence readers retain server-side behavior");
        SimpleAssert.that(
            world.getWorldInfo()
                .isInitialized(),
            "detached world is initialized without terrain");
        SimpleAssert.that(
            world.getSaveHandler()
                .loadWorldInfo() == world.getWorldInfo(),
            "save handler retains native world info");
        SimpleAssert.that(world.provider.worldObj == world, "provider is bound to detached world");
        SimpleAssert.that(world.provider.worldChunkMgr != null, "provider owns its biome manager");
        SimpleAssert.that(world.getChunkProvider() != null, "detached chunk provider is initialized");
        SimpleAssert.that(world.mapStorage != world.perWorldStorage, "map and per-world stores remain separate");
        SimpleAssert.that(
            world.perWorldStorage.loadData(VillageCollection.class, "villages") == world.villageCollectionObj,
            "villages retain common-world per-world storage placement");
        SimpleAssert.that(world.mapStorage.loadData(VillageCollection.class, "villages") == null, "no shared villages");
        SimpleAssert.that(
            world.getSaveHandler()
                .getWorldDirectory() == null,
            "no world directory");
        SimpleAssert.that(
            world.getSaveHandler()
                .getMapFileFromName("probe") == null,
            "no map file");
        SimpleAssert.that(world.getBlock(0, 64, 0) == Blocks.wool, "single native cell block");
        SimpleAssert.eq(5, world.getBlockMetadata(0, 64, 0), "single native cell metadata");
        SimpleAssert.that(world.getBlock(1, 64, 0) == Blocks.air, "unrelated positions remain air");
        SimpleAssert.that(!world.setBlock(0, 64, 0, Blocks.dirt, 0, 3), "detached world remains read-only");
    }

    private static void checkDuringClientConstruction(HologramResourceWorld before, HologramResourceWorld concurrent,
        Field save, Field map) throws Exception {
        Constructor<HologramResourceWorld> constructor = HologramResourceWorld.class
            .getDeclaredConstructor(net.minecraft.block.Block.class, int.class, WorldProvider.class);
        constructor.setAccessible(true);
        WorldProviderSurface provider = new WorldProviderSurface() {

            @Override
            protected void registerWorldChunkManager() {
                try {
                    // This callback runs before the constructor returns: a save/restore wrapper cannot hide mutation.
                    SimpleAssert.that(save.get(null) == before.getSaveHandler(), "no transient save cache overwrite");
                    SimpleAssert.that(map.get(null) == before.mapStorage, "no transient map cache overwrite");
                    // Simulate another real world publishing its new cache while this client world initializes.
                    save.set(null, concurrent.getSaveHandler());
                    map.set(null, concurrent.mapStorage);
                } catch (IllegalAccessException failure) {
                    throw new AssertionError(failure);
                }
                super.registerWorldChunkManager();
            }
        };
        HologramResourceWorld created = constructor.newInstance(Blocks.wool, 5, provider);
        checkDetached(created);
        SimpleAssert.that(save.get(null) == concurrent.getSaveHandler(), "client does not restore a stale save cache");
        SimpleAssert.that(map.get(null) == concurrent.mapStorage, "client does not restore a stale map cache");
    }

    private static boolean hasClientWorldConstructor() {
        try {
            World.class.getDeclaredConstructor(
                ISaveHandler.class,
                String.class,
                WorldProvider.class,
                WorldSettings.class,
                Profiler.class);
            return true;
        } catch (NoSuchMethodException stripped) {
            return false;
        }
    }

    private static boolean hasClientResourceConstructor() {
        try {
            HologramResourceWorld.class
                .getDeclaredConstructor(net.minecraft.block.Block.class, int.class, WorldProvider.class);
            return true;
        } catch (NoSuchMethodException stripped) {
            return false;
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Apply Forge's transformer to the audited production classes, not modern dependency fixture bytecode. */
    public static final class AuditedSideTransformer implements net.minecraft.launchwrapper.IClassTransformer {

        private final cpw.mods.fml.common.asm.transformers.SideTransformer delegate = new cpw.mods.fml.common.asm.transformers.SideTransformer();

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (name.equals("net.minecraft.world.World")
                || name.equals("com.miaokatze.gtmb.hologram.HologramResourceWorld"))
                return delegate.transform(name, transformedName, bytes);
            return bytes;
        }
    }

    private static void enterSided(String side, String[] args) throws Exception {
        String[] paths = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator);
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = new java.io.File(paths[i]).toURI()
            .toURL();
        LaunchClassLoader loader = new LaunchClassLoader(urls);
        Launch.classLoader = loader;
        Launch.minecraftHome = new java.io.File(".");
        Thread.currentThread()
            .setContextClassLoader(loader);
        Class<?> launchHandler = loader.loadClass("cpw.mods.fml.relauncher.FMLLaunchHandler");
        Class<?> sideClass = loader.loadClass("cpw.mods.fml.relauncher.Side");
        field(launchHandler, "side").set(
            null,
            sideClass.getField(side)
                .get(null));
        loader.addTransformerExclusion("cpw.mods.fml.relauncher.");
        loader.registerTransformer(HologramResourceWorldSidedRegression.class.getName() + "$AuditedSideTransformer");
        Class<?> injection = loader.loadClass("cpw.mods.fml.relauncher.FMLInjectionData");
        java.lang.reflect.Method build = injection
            .getDeclaredMethod("build", java.io.File.class, LaunchClassLoader.class);
        build.setAccessible(true);
        build.invoke(null, new java.io.File("."), loader);
        Object[] data = (Object[]) injection.getMethod("data")
            .invoke(null);
        loader.loadClass("cpw.mods.fml.common.Loader")
            .getMethod("injectData", Object[].class)
            .invoke(null, new Object[] { data });
        loader.loadClass(HologramResourceWorldSidedRegression.class.getName())
            .getMethod("main", String[].class)
            .invoke(null, new Object[] { args });
    }
}
