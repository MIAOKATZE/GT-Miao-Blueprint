// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.DataInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Actual GTSR completion contracts; opt-in offline fixture, never an ordinary runtime probe. */
public final class HologramOreCenterStallInvestigation {

    static int nextId = 3600;
    static final Map<String, Block> registered = new LinkedHashMap<>();

    public static void main(String[] args) {
        int status = 0;
        try {
            Class<?> bootstrap = Class.forName("com.miaokatze.gtmb.testutil.MinecraftTestBootstrap");
            if (!(Boolean) bootstrap.getMethod("enter", Class.class, String[].class)
                .invoke(null, HologramOreCenterStallInvestigation.class, args)) return;
            Thread.currentThread()
                .setName("Server thread");
            Class.forName("com.miaokatze.gtmb.hologram.HologramNativeSavedWorldRegression")
                .getMethod("withOfflineRegistries", Runnable.class)
                .invoke(null, (Runnable) () -> {
                    try {
                        run(args);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        } catch (Throwable e) {
            e.printStackTrace();
            status = 1;
        }
        System.exit(status);
    }

    static Object get(Object o, String n) throws Exception {
        Field f = o.getClass()
            .getDeclaredField(n);
        f.setAccessible(true);
        return f.get(o);
    }

    static Block register(String name) throws Exception {
        if (registered.containsKey(name)) return registered.get(name);
        Block existing = (Block) Block.blockRegistry.getObject(name);
        if (existing != null && existing != Blocks.air) {
            registered.put(name, existing);
            return existing;
        }
        Block b = name.startsWith("chisel:") ? (Block) Class.forName("team.chisel.block.BlockCarvable")
            .getConstructor(Material.class)
            .newInstance(Material.rock) : new Block(Material.rock) {};
        ItemBlock i = new ItemBlock(b) {

            public int getMetadata(int m) {
                return m;
            }
        };
        if (name.startsWith("chisel:")) i = (ItemBlock) Class.forName("team.chisel.item.ItemCarvable")
            .getConstructor(Block.class)
            .newInstance(b);
        while (Block.blockRegistry.containsId(nextId) || Item.itemRegistry.containsId(nextId)) nextId--;
        rawRegister(name, b, i, nextId--);
        registered.put(name, b);
        return b;
    }

    static void rawRegister(String name, Block b, Item i, int id) throws Exception {
        Class<?> c = cpw.mods.fml.common.registry.GameData.class;
        Method main = c.getDeclaredMethod("getMain");
        main.setAccessible(true);
        Object data = main.invoke(null);
        Method block = c.getDeclaredMethod("registerBlock", Block.class, String.class, int.class);
        block.setAccessible(true);
        block.invoke(data, b, name, id);
        Method item = c.getDeclaredMethod("registerItem", Item.class, String.class, int.class);
        item.setAccessible(true);
        item.invoke(data, i, name, id);
    }

    static void registry() throws Exception {
        for (String key : new String[] { "tower-service-0", "tower-service-1", "tower-service-2", "tower-service-3",
            "outer-0", "outer-1", "outer-2", "outer-3", "outer-4", "outer-5", "outer-full" }) {
            try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(
                    HologramOreCenterStallInvestigation.class
                        .getResourceAsStream("/assets/gtsr/orecenter/" + key + ".gz")))) {
                int n = in.readUnsignedShort();
                in.readInt();
                for (int j = 0; j < n; j++) {
                    byte[] bytes = new byte[in.readUnsignedByte()];
                    in.readFully(bytes);
                    register(new String(bytes, "UTF-8"));
                    in.readByte();
                }
            }
        }
        GregTechAPI.sBlockCasings1 = registered.get("gregtech:gt.blockcasings");
        GregTechAPI.sBlockCasings2 = registered.get("gregtech:gt.blockcasings2");
        GregTechAPI.sBlockCasings3 = registered.get("gregtech:gt.blockcasings3");
        GregTechAPI.sBlockCasings4 = registered.get("gregtech:gt.blockcasings4");
        System.out.println(
            "REGISTRY gt=" + GregTechAPI.sBlockCasings1
                + " vanilla="
                + Block.blockRegistry.getObject("gregtech:gt.blockcasings")
                + " forge="
                + cpw.mods.fml.common.registry.GameRegistry.findBlock("gregtech", "gt.blockcasings"));
        // Real controller item class; native MTE construction is retained.
        Block machine = new Block(Material.iron) {

            @Override
            public boolean hasTileEntity(int meta) {
                return true;
            }

            @Override
            public net.minecraft.tileentity.TileEntity createTileEntity(net.minecraft.world.World world, int meta) {
                return new BaseMetaTileEntity();
            }
        };
        while (Block.blockRegistry.containsId(nextId) || Item.itemRegistry.containsId(nextId)) nextId--;
        Block.blockRegistry.addObject(nextId, "gtmb:ore_stall_machine_fixture", machine);
        Item.itemRegistry
            .addObject(nextId, "gtmb:ore_stall_machine_fixture", new gregtech.common.blocks.ItemMachines(machine));
        GregTechAPI.sBlockMachines = machine;
        // Only the cooling hatch's texture constant requires the real casing class before full GT registration.
        // getTextureIndex(10) is the native field-independent implementation; no build/placement method is replaced.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        if (gregtech.GTMod.proxy == null)
            gregtech.GTMod.proxy = (gregtech.common.GTProxy) unsafe.allocateInstance(gregtech.common.GTProxy.class);
        Field preferences = gregtech.common.GTProxy.class.getDeclaredField("mClientPrefernces");
        preferences.setAccessible(true);
        if (preferences.get(gregtech.GTMod.proxy) == null)
            preferences.set(gregtech.GTMod.proxy, new java.util.concurrent.ConcurrentHashMap<>());
        if (gregtech.GTMod.proxy.powerfailTracker == null)
            gregtech.GTMod.proxy.powerfailTracker = new gregtech.common.data.GTPowerfailTracker();
        Field names = cpw.mods.fml.relauncher.ReflectionHelper
            .findField(net.minecraft.tileentity.TileEntity.class, "classToNameMap", "field_145853_j");
        if (!((Map<?, ?>) names.get(null)).containsKey(BaseMetaTileEntity.class)) {
            Method mapping = net.minecraft.tileentity.TileEntity.class
                .getDeclaredMethod("addMapping", Class.class, String.class);
            mapping.setAccessible(true);
            mapping.invoke(null, BaseMetaTileEntity.class, "BaseMetaTileEntity");
        }
        Block paletteCasing = GregTechAPI.sBlockCasings1;
        try {
            GregTechAPI.sBlockCasings1 = (Block) unsafe.allocateInstance(gregtech.common.blocks.BlockCasings1.class);
            Class.forName("com.miaokatze.gtsr.common.machine.base.MTESteamCoolingHatch");
        } finally {
            GregTechAPI.sBlockCasings1 = paletteCasing;
        }
        String[] types = { "gregtech.api.metatileentity.implementations.MTEHatchInput",
            "gregtech.api.metatileentity.implementations.MTEHatchOutput",
            "gregtech.api.metatileentity.implementations.MTEHatchInputBus",
            "gregtech.api.metatileentity.implementations.MTEHatchOutputBus",
            "com.miaokatze.gtsr.common.machine.base.MTESteamInputHatchGeneric",
            "com.miaokatze.gtsr.common.machine.base.MTESteamCoolingHatch",
            "gregtech.api.metatileentity.implementations.MTEHatchEnergy" };
        String[] entries = { "Hatch_Input_LV", "Hatch_Output_LV", "Hatch_Input_Bus_LV", "Hatch_Output_Bus_LV",
            "SteamInputHatchGeneric", "SteamCoolingHatch", "Hatch_Energy_LV" };
        for (int n = 0; n < types.length; n++) {
            int id = 31000 + n;
            var port = (gregtech.api.interfaces.metatileentity.IMetaTileEntity) Class.forName(types[n])
                .getConstructor(String.class, int.class, String[].class, gregtech.api.interfaces.ITexture[][][].class)
                .newInstance(
                    "gtmb_actual_center_port_" + n,
                    1,
                    new String[0],
                    new gregtech.api.interfaces.ITexture[0][][]);
            BaseMetaTileEntity base = new BaseMetaTileEntity();
            port.setBaseMetaTileEntity(base);
            base.setMetaTileID((short) id);
            GregTechAPI.METATILEENTITIES[id] = port;
            Class<?> list = Class.forName(
                n == 4 || n == 5 ? "com.miaokatze.gtsr.common.api.enums.GTSRItemList" : "gregtech.api.enums.ItemList");
            Object entry = list.getField(entries[n])
                .get(null);
            list.getMethod("set", ItemStack.class)
                .invoke(entry, new ItemStack(machine, 1, id));
        }
    }

    static void run(String[] args) throws Exception {
        registry();
        Class<?> c = Class.forName("com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter");
        Constructor<?> ctor = c.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        for (boolean prebuilt : new boolean[] { false, true })
            for (int sectors : new int[] { 1, 5 }) verifySimulation(ctor, 1, sectors, prebuilt);
        verifySimulation(ctor, 4, 5, false);
        verifySimulation(ctor, 4, 5, true);
        for (int sectors : new int[] { 1, 5 }) {
            verifyTarget(ctor, 1, sectors, false);
            verifyTarget(ctor, 1, sectors, true);
            verifyTarget(ctor, 1, sectors, false, true);
            verifyBlueprint(ctor, 1, sectors);
        }
        verifyTarget(ctor, 4, 5, false);
        verifyBlueprint(ctor, 4, 5);
        System.out.println(
            "COMPLETION_CONTRACTS_PASSED: six native generation/replay pairs, seven native server targets including configured hatches and three blueprint source conversions");
    }

    static Object center(Constructor<?> ctor, BaseMetaTileEntity base) throws Exception {
        Object center = ctor.newInstance("gtmb_actual_center_completion_fixture");
        base.setMetaTileEntity((gregtech.api.interfaces.metatileentity.IMetaTileEntity) center);
        ((gregtech.api.interfaces.metatileentity.IMetaTileEntity) center).setBaseMetaTileEntity(base);
        base.setMetaTileID((short) 32651);
        return center;
    }

    static ItemStack signal(int main, int sectors) {
        return HologramNativeTargetPlan.canonicalTrigger(main, settings(main, sectors).getCompoundTag("channels"));
    }

    static int expected(int sectors) {
        return sectors == 1 ? 46946 : 136392;
    }

    static void verifySimulation(Constructor<?> ctor, int main, int sectors, boolean prebuilt) throws Exception {
        BaseMetaTileEntity base = new BaseMetaTileEntity();
        Object center = center(ctor, base);
        var initial = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        base.yCoord = 128;
        base.setWorldObj(initial);
        initial.tile = base;
        ItemStack signal = signal(main, sectors);
        try (var sim = new HologramProjectorSimulation(
            base,
            center,
            (IConstructable) center,
            signal,
            null,
            ExtendedFacing.DEFAULT,
            true)) {
            sim.configure(new NBTTagCompound());
            if (prebuilt) {
                base.setWorldObj(sim.world);
                try {
                    ((IConstructable) center).construct(signal, false);
                } finally {
                    base.setWorldObj(initial);
                }
            }
            boolean done = false;
            for (int frame = 0; frame < 10000; frame++) if (sim.step(System.nanoTime() + 2_000_000L)) {
                done = true;
                break;
            }
            if (!done || sim.nativeResult != -1 || sim.world.recordedPlacementCount() != expected(sectors))
                throw new AssertionError(
                    "Actual native generation did not complete: " + main + "/" + sectors + "/" + prebuilt);
            int count = sim.world.recordedPlacementCount();
            long beforeWrites = (Long) get(sim.world, "writeVersion"), signature = sim.world.contentSignature();
            base.setWorldObj(sim.world);
            int replayPasses = 0;
            try {
                sim.beginReplay(new HologramNativePreviewItems());
                for (; replayPasses < 2000; replayPasses++) if (sim.replayStep()) {
                    replayPasses++;
                    break;
                }
            } finally {
                base.setWorldObj(initial);
            }
            if (replayPasses >= 2000 || replayPasses <= 16
                || sim.world.recordedPlacementCount() != count
                || beforeWrites != (Long) get(sim.world, "writeVersion")
                || signature != sim.world.contentSignature())
                throw new AssertionError("Native replay must complete a long verification without further writes");
            System.out.println(
                "PASS_CENTER_GENERATION main=" + main
                    + " sectors="
                    + sectors
                    + " prebuilt="
                    + prebuilt
                    + " rounds="
                    + sim.rounds
                    + " blocks="
                    + count
                    + " replayPasses="
                    + replayPasses
                    + " replayWrites=0");
        }
    }

    static NBTTagCompound settings(int main, int sectors) {
        NBTTagCompound config = new NBTTagCompound();
        config.setInteger("main", main);
        NBTTagCompound channels = new NBTTagCompound();
        channels.setInteger("ore_center_sectors", sectors);
        config.setTag("channels", channels);
        return config;
    }

    static void prototype(Constructor<?> ctor) throws Exception {
        BaseMetaTileEntity base = new BaseMetaTileEntity();
        GregTechAPI.METATILEENTITIES[32651] = (gregtech.api.interfaces.metatileentity.IMetaTileEntity) center(
            ctor,
            base);
    }

    static void verifyTarget(Constructor<?> ctor, int main, int sectors, boolean footprint) throws Exception {
        verifyTarget(ctor, main, sectors, footprint, false);
    }

    static void verifyTarget(Constructor<?> ctor, int main, int sectors, boolean footprint, boolean preset)
        throws Exception {
        prototype(ctor);
        NBTTagCompound config = settings(main, sectors);
        config.setBoolean("footprintOnly", footprint);
        if (preset) {
            config.setBoolean("presetActive", true);
            config.setInteger("hatchTier", 1);
        }
        try (
            var target = HologramNativeTargetPlan.begin(32651, ExtendedFacing.DEFAULT, signal(main, sectors), config)) {
            boolean done = false;
            for (int frame = 0; frame < 20000; frame++) if (target.advance(System.nanoTime() + 2_000_000L)) {
                done = true;
                break;
            }
            if (!done || !target.complete()
                || target.targetDigest() == null
                || target.targetDigest()
                    .length() != 64
                || target.cells()
                    .size() != expected(sectors))
                throw new AssertionError("Actual server native target/footprint incomplete");
            long hatches = target.cells()
                .stream()
                .filter(
                    cell -> cell.material() != null
                        && HologramCellPolicy.hatchTier(cell.material()) != Integer.MAX_VALUE)
                .count();
            if (preset && hatches == 0 || !preset && hatches != 0)
                throw new AssertionError("Optional hatch defaults/preset mismatch");
            if (preset && target.cells()
                .stream()
                .filter(
                    cell -> cell.material() != null
                        && HologramCellPolicy.hatchTier(cell.material()) != Integer.MAX_VALUE)
                .anyMatch(cell -> cell.tileData() == null || cell.facing() < 0))
                throw new AssertionError("Configured hatch lost NBT or direction");
            System.out.println(
                "PASS_CENTER_SERVER_TARGET main=" + main
                    + " sectors="
                    + sectors
                    + " footprint="
                    + footprint
                    + " preset="
                    + preset
                    + " hatches="
                    + hatches
                    + " cells="
                    + target.cells()
                        .size()
                    + " digest="
                    + target.targetDigest());
        }
    }

    static void verifyBlueprint(Constructor<?> ctor, int main, int sectors) throws Exception {
        prototype(ctor);
        try (var capture = HologramLocalControllerCapture.forBlueprint(
            GregTechAPI.sBlockMachines,
            0,
            32651,
            "com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter",
            ExtendedFacing.DEFAULT,
            null,
            settings(main, sectors))) {
            HologramControllerTarget.Projection model = null;
            for (int frame = 0; frame < 20000 && model == null; frame++) model = capture.stepTarget(2048, 2_000_000L);
            if (model == null || model.cells()
                .size() != expected(sectors) + 1)
                throw new AssertionError(
                    "Actual blueprint source conversion incomplete: " + (model == null ? -1
                        : model.cells()
                            .size()));
            if (model.metadata()
                .getCompoundTag("channels")
                .getInteger("ore_center_sectors") != sectors)
                throw new AssertionError("Blueprint source channel was lost");
            System.out.println(
                "PASS_CENTER_BLUEPRINT main=" + main
                    + " sectors="
                    + sectors
                    + " cells="
                    + model.cells()
                        .size());
        }
    }
}
