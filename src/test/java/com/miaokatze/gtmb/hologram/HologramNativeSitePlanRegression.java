// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Real native member reads; detached registry bootstrap is not an in-game formation proof. */
public final class HologramNativeSitePlanRegression {

    private HologramNativeSitePlanRegression() {}

    public static void main(String[] args) {
        int result = 0;
        try {
            if (!MinecraftTestBootstrap.enter(HologramNativeSitePlanRegression.class, args)) return;
            var loadedField = net.minecraft.launchwrapper.LaunchClassLoader.class.getDeclaredField("cachedClasses");
            loadedField.setAccessible(true);
            var loaded = (Map<?, ?>) loadedField.get(net.minecraft.launchwrapper.Launch.classLoader);
            SimpleAssert.that(
                !loaded.containsKey("gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase"),
                "native capture seams must install before verification loads the real .277 checker");
            net.minecraft.launchwrapper.Launch.classLoader
                .registerTransformer("com.miaokatze.gtmb.hologram.HologramNativeSiteEventFixtureTransformer");
            net.minecraft.launchwrapper.Launch.classLoader.registerTransformer(
                "com.miaokatze.gtmb.hologram.HologramNativeSiteCaptureRegression$PieceFixtureTransformer");
            net.minecraft.launchwrapper.Launch.classLoader
                .registerTransformer("com.miaokatze.gtmb.hologram.HologramNativeSiteFixtureTransformer");
            Thread.currentThread()
                .setName("Server thread");
            Fixture.run();
            System.out
                .println("NATIVE_SITE_MEMBERS_PASS ordinaryCapture=true numericSearch=0 failedRangeAudit=0 game=false");
        } catch (Throwable failure) {
            failure.printStackTrace();
            result = 1;
        }
        System.exit(result);
    }

    // The independent capture suite reuses this registry helper, after installing its own native seams.
    private static void registries() throws Exception {
        Fixture.registries();
    }

    /** Keep all native controller types out of the bootstrap class's verifier until seams are installed. */
    private static final class Fixture {

        static void run() throws Exception {
            HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                try {
                    HologramNativeTargetPlanRegression.verifyAdoptedSiteMaterial();
                    registries();
                    verifyMembers(1);
                    verifyMembers(5);
                    prototype(32639, new ModeController());
                    verifyObservationGate();
                } catch (Exception failure) {
                    throw new AssertionError("native member site fixture", failure);
                }
            });
        }

        private static final int CONTROLLER = 32648, ENERGY = 32647, PORT_START = 32640;
        private static int blockId = 3790;

        private static BaseMetaTileEntity formedEbf(Scene world, int x, int coilTier) throws Exception {
            var channels = new NBTTagCompound();
            channels.setInteger("coil", coilTier);
            var bottoms = new java.util.ArrayList<HologramNativeTargetPlan.Cell>();
            try (var target = HologramNativeTargetPlan.compile(
                CONTROLLER,
                ExtendedFacing.DEFAULT,
                HologramNativeTargetPlan.canonicalTrigger(1, channels),
                new NBTTagCompound())) {
                for (var cell : target.cells()) {
                    var moved = new HologramNativeTargetPlan.Cell(
                        cell.dx() + x,
                        cell.dy(),
                        cell.dz(),
                        cell.block(),
                        cell.metadata(),
                        cell.material(),
                        cell.facing(),
                        cell.display(),
                        cell.tileData(),
                        cell.element(),
                        cell.trigger(),
                        cell.requiredAir());
                    world.seed(moved);
                    Stored seeded = world.cells
                        .get(new HologramNativeTargetPlan.Offset(moved.dx(), moved.dy(), moved.dz()));
                    if (seeded.tile != null) {
                        seeded.tile.xCoord = moved.dx();
                        seeded.tile.yCoord = 128 + moved.dy();
                        seeded.tile.zCoord = moved.dz();
                    }
                    if (cell.block() == GregTechAPI.sBlockCasings1 && cell.dy() == 0) bottoms.add(moved);
                }
            }
            SimpleAssert.that(bottoms.size() >= 6, "native EBF has six legal bottom hatch slots");
            int[] ports = { ENERGY, PORT_START, PORT_START + 1, PORT_START + 2, PORT_START + 3, PORT_START + 4 };
            for (int i = 0; i < ports.length; i++) {
                var at = bottoms.get(i);
                var hatch = new BaseMetaTileEntity();
                hatch.xCoord = at.dx();
                hatch.yCoord = 128 + at.dy();
                hatch.zCoord = at.dz();
                hatch.setWorldObj(world);
                hatch.setInitialValuesAsNBT(null, (short) ports[i]);
                hatch.setFrontFacing(i == 0 ? ForgeDirection.WEST : ForgeDirection.NORTH);
                world.cells.put(
                    new HologramNativeTargetPlan.Offset(at.dx(), at.dy(), at.dz()),
                    new Stored(GregTechAPI.sBlockMachines, 0, hatch));
            }
            var base = HologramNativeTargetPlan.freshController(CONTROLLER, ExtendedFacing.DEFAULT);
            base.xCoord = x;
            base.yCoord = 128;
            base.setWorldObj(world);
            base.disableWorking();
            if (x == 0) world.tile = base;
            else world.cells
                .put(new HologramNativeTargetPlan.Offset(x, 0, 0), new Stored(GregTechAPI.sBlockMachines, 0, base));
            var machine = (gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>) base
                .getMetaTileEntity();
            SimpleAssert.that(
                machine.checkStructure(true, base) && machine.mMachine,
                "fixture formation comes from the actual native EBF check, never a forced formed flag");
            return base;
        }

        private static String readSite(Scene world, BaseMetaTileEntity live, int coilTier) throws Exception {
            var requested = HologramLocalControllerCapture.toolSettings(new ItemStack(Items.stick));
            try (var site = HologramNativeSitePlan.begin(live, requested)) {
                SimpleAssert.that(site.advance(Long.MAX_VALUE), "member read completes");
                var found = site.settings();
                SimpleAssert.that(found.getBoolean("siteKnown"), "native EBF members are complete: " + found);
                SimpleAssert.eq(1L, found.getLong("siteNativeCaptureCalls"), "exactly one purpose-built native check");
                SimpleAssert
                    .eq(0L, found.getLong("siteNativeCalls"), "ordinary readers do not run parameter survival probes");
                SimpleAssert.eq(0, found.getInteger("siteMainCandidates"), "no main candidates");
                SimpleAssert.eq(0, found.getInteger("siteSizeSearchCandidates"), "no size candidates");
                SimpleAssert.that(!found.hasKey("siteRangeAuditRuns"), "failed candidate range auditing is removed");
                SimpleAssert.eq(
                    coilTier,
                    found.getCompoundTag("channels")
                        .getInteger("coil"),
                    "native same-domain material evidence");
                SimpleAssert.that(!found.getBoolean("siteMainRecovered"), "ordinary tool main is explicitly inherited");
                SimpleAssert.eq(
                    site.footprint()
                        .size(),
                    site.siteCells()
                        .size(),
                    "actual frozen cells cover the proved footprint");
                SimpleAssert.eq(
                    0L,
                    found.getLong("siteTileNbtCaptures"),
                    "ordinary member capture does not serialize source hatches");
                try (var target = HologramNativeTargetPlan
                    .fromSite(CONTROLLER, ExtendedFacing.DEFAULT, site.siteCells(), found)) {
                    SimpleAssert.that(target.advance(Long.MAX_VALUE), "frozen site digest completes");
                    String digest = target.targetDigest();
                    HologramNativeProjectionJob.rememberSite(live, new java.util.ArrayList<>(site.footprint()), found);
                    System.out.println(
                        "NATIVE_SITE_MEMBER_CASE coil=" + coilTier
                            + " members="
                            + site.siteCells()
                                .size()
                            + " uniqueReads="
                            + found.getLong("siteUniqueCells")
                            + " readSetReads="
                            + found.getLong("siteCellReads")
                            + " captureNanos="
                            + found.getLong("siteNativeCaptureNanos")
                            + " digest="
                            + digest);
                    return digest;
                }
            }
        }

        private static void verifyMembers(int coilTier) throws Exception {
            HologramNativeProjectionJob.clearBaselines();
            Scene world = HologramResourceWorld.create(Scene::new);
            var live = formedEbf(world, 0, coilTier);
            String original = readSite(world, live, coilTier);
            SimpleAssert.eq(original, readSite(world, live, coilTier), "reopening uses the same actual cells");
            var owned = new LinkedHashMap<>(world.cells);
            for (int neighbor = 0; neighbor < 3; neighbor++) {
                world.cells.clear();
                world.cells.putAll(owned);
                if (neighbor == 0) world.cells
                    .put(new HologramNativeTargetPlan.Offset(8, 0, 0), new Stored(Blocks.diamond_block, 0, null));
                else if (neighbor == 1) {
                    var controller = HologramNativeTargetPlan.freshController(CONTROLLER, ExtendedFacing.DEFAULT);
                    controller.xCoord = 8;
                    controller.yCoord = 128;
                    controller.setWorldObj(world);
                    world.cells.put(
                        new HologramNativeTargetPlan.Offset(8, 0, 0),
                        new Stored(GregTechAPI.sBlockMachines, 0, controller));
                } else formedEbf(world, 8, 1);
                Map<TileEntity, NBTTagCompound> outside = new LinkedHashMap<>();
                for (var row : world.cells.entrySet())
                    if (!owned.containsKey(row.getKey()) && row.getValue().tile != null)
                        outside.put(row.getValue().tile, HologramNativeCapture.capture(row.getValue().tile));
                var noNeighborCapture = new HologramNativeCapture.Adapter() {

                    public boolean supports(TileEntity tile) {
                        return outside.containsKey(tile);
                    }

                    public NBTTagCompound capture(TileEntity tile) {
                        throw new AssertionError("neighbor TE serialization");
                    }
                };
                world.readPositions.clear();
                world.armed = true;
                HologramNativeCapture.register(noNeighborCapture);
                try {
                    SimpleAssert.eq(
                        original,
                        readSite(world, live, coilTier),
                        "outside block/controller/complete neighbor does not alter source digest");
                } finally {
                    HologramNativeCapture.unregister(noNeighborCapture);
                    world.armed = false;
                }
                for (var read : world.readPositions) SimpleAssert.that(
                    read.equals(new HologramNativeTargetPlan.Offset(0, 0, 0)) || owned.containsKey(read),
                    "native member read never queries an unrelated neighbor coordinate: " + read);
                for (var row : outside.entrySet()) SimpleAssert.eq(
                    row.getValue(),
                    HologramNativeCapture.capture(row.getKey()),
                    "neighbor native state is unchanged");
                System.out.println(
                    "NATIVE_SITE_NEIGHBOR_PASS kind=" + neighbor
                        + " sourceDigest="
                        + original
                        + " neighborMutation=false");
            }
            world.cells.clear();
            world.cells.putAll(owned);
            var actualHistory = HologramNativeProjectionJob.provedHistory(live);
            var knownSettings = HologramNativeProjectionJob.provedSettings(live);
            SimpleAssert.that(knownSettings != null && !actualHistory.isEmpty(), "complete source baseline exists");
            var staleHistory = new java.util.ArrayList<>(actualHistory);
            staleHistory.add(new HologramNativeTargetPlan.Offset(120, 0, 0));
            HologramNativeProjectionJob.rememberSite(live, staleHistory, knownSettings);
            HologramNativeProjectionJob.rememberSite(live, actualHistory, knownSettings);
            SimpleAssert.eq(
                actualHistory,
                HologramNativeProjectionJob.provedHistory(live),
                "a fresh complete proof replaces historical removal authority");
            new HologramLifecycle().worldUnloaded(new net.minecraftforge.event.world.WorldEvent.Unload(world));
            SimpleAssert.that(
                HologramNativeProjectionJob.provedHistory(live)
                    .isEmpty(),
                "native world unload releases cached elements and actual cells without polling");
            HologramNativeTargetPlan.Offset conflict = null;
            for (var row : world.cells.entrySet()) if (row.getValue().block == GregTechAPI.sBlockCasings5) {
                conflict = row.getKey();
                break;
            }
            SimpleAssert.that(conflict != null, "native coil member exists");
            world.cells.put(conflict, new Stored(Blocks.diamond_block, 0, null));
            try (var site = HologramNativeSitePlan
                .begin(live, HologramLocalControllerCapture.toolSettings(new ItemStack(Items.stick)))) {
                SimpleAssert.that(site.advance(Long.MAX_VALUE), "conflicting native check terminates");
                SimpleAssert.that(
                    !site.settings()
                        .getBoolean("siteKnown") && site.siteCells()
                            .isEmpty()
                        && site.footprint()
                            .isEmpty(),
                    "a real member conflict cannot publish a smaller complete site");
            }
            HologramNativeProjectionJob.clearBaselines();
        }

        private static sun.misc.Unsafe unsafe() throws Exception {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        }

        private static void prototype(int id, IMetaTileEntity machine) {
            BaseMetaTileEntity base = new BaseMetaTileEntity();
            machine.setBaseMetaTileEntity(base);
            base.setMetaTileID((short) id);
            GregTechAPI.METATILEENTITIES[id] = machine;
        }

        private static void register(String name, Block block, Item item) {
            while (Block.blockRegistry.containsId(blockId) || Item.itemRegistry.containsId(blockId)) blockId--;
            Block.blockRegistry.addObject(blockId, name, block);
            Item.itemRegistry.addObject(blockId--, name, item);
        }

        private static void registries() throws Exception {
            Field structureProxy = com.gtnewhorizon.structurelib.StructureLib.class.getDeclaredField("proxy");
            structureProxy.setAccessible(true);
            Object proxy = unsafe().allocateInstance(com.gtnewhorizon.structurelib.CommonProxy.class);
            Field throttles = com.gtnewhorizon.structurelib.CommonProxy.class.getDeclaredField("throttleMap");
            throttles.setAccessible(true);
            throttles.set(proxy, new java.util.WeakHashMap<>());
            structureProxy.set(null, proxy);
            Block machine = new Block(Material.iron) {

                @Override
                public boolean hasTileEntity(int metadata) {
                    return true;
                }

                @Override
                public TileEntity createTileEntity(World world, int metadata) {
                    return new BaseMetaTileEntity();
                }
            };
            register("gtmb:native_site_ebf_machine", machine, new ItemMachines(machine));
            GregTechAPI.sBlockMachines = machine;
            GregTechAPI.sBlockCasings1 = Blocks.wool;
            BlockCasings5 coils = (BlockCasings5) unsafe().allocateInstance(BlockCasings5.class);
            Field material = cpw.mods.fml.relauncher.ReflectionHelper
                .findField(Block.class, "blockMaterial", "field_149764_J");
            unsafe().putObject(coils, unsafe().objectFieldOffset(material), Material.iron);
            Field delegate = Block.class.getDeclaredField("delegate");
            delegate.setAccessible(true);
            delegate.set(
                coils,
                ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Block.blockRegistry)
                    .getDelegate(coils, Block.class));
            coils.stepSound = Blocks.iron_block.stepSound;
            register("gtmb:native_site_ebf_coils", coils, new ItemBlock(coils) {

                @Override
                public int getMetadata(int damage) {
                    return damage;
                }
            });
            GregTechAPI.sBlockCasings5 = coils;
            if (gregtech.GTMod.proxy == null) gregtech.GTMod.proxy = (gregtech.common.GTProxy) unsafe()
                .allocateInstance(gregtech.common.GTProxy.class);
            Field preferences = gregtech.common.GTProxy.class.getDeclaredField("mClientPrefernces");
            preferences.setAccessible(true);
            if (preferences.get(gregtech.GTMod.proxy) == null)
                preferences.set(gregtech.GTMod.proxy, new java.util.concurrent.ConcurrentHashMap<>());
            if (gregtech.GTMod.proxy.powerfailTracker == null)
                gregtech.GTMod.proxy.powerfailTracker = new gregtech.common.data.GTPowerfailTracker();
            Field names = cpw.mods.fml.relauncher.ReflectionHelper
                .findField(TileEntity.class, "classToNameMap", "field_145853_j");
            if (!((Map<?, ?>) names.get(null)).containsKey(BaseMetaTileEntity.class)) {
                var add = TileEntity.class.getDeclaredMethod("addMapping", Class.class, String.class);
                add.setAccessible(true);
                add.invoke(null, BaseMetaTileEntity.class, "gtmb_native_site_ebf");
            }
            String[] types = { "Input", "Output", "InputBus", "OutputBus", "Maintenance", "Muffler" };
            ItemList[] entries = { ItemList.Hatch_Input_LV, ItemList.Hatch_Output_LV, ItemList.Hatch_Input_Bus_LV,
                ItemList.Hatch_Output_Bus_LV, ItemList.Hatch_Maintenance, ItemList.Hatch_Muffler_LV };
            for (int i = 0; i < types.length; i++) {
                IMetaTileEntity port;
                Class<?> type = Class.forName("gregtech.api.metatileentity.implementations.MTEHatch" + types[i]);
                if (types[i].equals("Maintenance")) {
                    port = (IMetaTileEntity) type
                        .getConstructor(String.class, int.class, String[].class, ITexture[][][].class, boolean.class)
                        .newInstance("native_site_" + types[i], 1, new String[0], new ITexture[0][][], false);
                } else port = (IMetaTileEntity) type
                    .getConstructor(String.class, int.class, String[].class, ITexture[][][].class)
                    .newInstance("native_site_" + types[i], 1, new String[0], new ITexture[0][][]);
                prototype(PORT_START + i, port);
                entries[i].set(new ItemStack(machine, 1, PORT_START + i));
            }
            prototype(ENERGY, new MTEHatchEnergy("native_site_energy", 4, new String[0], new ITexture[0][][]));
            ItemList.Hatch_Energy_LV.set(new ItemStack(machine, 1, ENERGY));
            prototype(CONTROLLER, new MTEElectricBlastFurnace("native_site_actual_ebf"));
        }

        private static void verifyObservationGate() throws Exception {
            final int id = 32639;
            Scene world = HologramResourceWorld.create(Scene::new);
            BaseMetaTileEntity live = HologramNativeTargetPlan.freshController(id, ExtendedFacing.DEFAULT);
            live.setWorldObj(world);
            world.tile = live;
            var machine = (ModeController) live.getMetaTileEntity();
            machine.setMachineMode(1);
            machine.mProgresstime = 17;
            machine.mMaxProgresstime = 90;
            machine.mInventory[0] = new ItemStack(Items.diamond, 7);
            world.controllerOnlyReads = true;
            world.armed = true;
            var player = observationPlayer(world);
            try {
                HologramWorkspace.publishActionEndpoint(player, false);
                var endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
                SimpleAssert.that(endpoint != null, "offline actor has a current authenticated endpoint");
                for (boolean formed : new boolean[] { false, true }) {
                    machine.mMachine = formed;
                    for (boolean readSite : new boolean[] { false, true }) {
                        NBTTagCompound before = HologramNativeCapture.capture(live);
                        int survival = ModeController.freshSurvivalCalls;
                        int forced = ModeController.liveChecks;
                        long reads = world.blockReads;
                        var config = new NBTTagCompound();
                        config.setBoolean("readSiteSettings", readSite);
                        var channels = new LinkedHashMap<String, Integer>();
                        channels.put("coil", 5);
                        var projection = new HologramStockNativeWire.Projection(
                            id,
                            HologramStockNativeWire.Operation.BUILD,
                            channels,
                            java.util.Collections.emptyList(),
                            0,
                            29,
                            config);
                        var confirm = HologramStockNativeWire.Confirmation.projection(
                            new HologramStockNativeWire.Key(java.util.UUID.randomUUID(), 1),
                            player.dimension,
                            java.util.UUID.fromString(
                                player.getHeldItem()
                                    .getTagCompound()
                                    .getString(HologramSessionKeys.TOOL_ID)),
                            0,
                            128,
                            0,
                            2,
                            0,
                            ExtendedFacing.DEFAULT.ordinal(),
                            3,
                            true,
                            false,
                            HologramProjectedMainPolicy.settingsKey(new HologramMaterials.Settings()),
                            projection);
                        var ingress = HologramStockNativeEntry.inspect(endpoint, confirm);
                        SimpleAssert.that(
                            ingress.submission() != null,
                            "production admission accepts the unchanged actor and tool");
                        try (var observation = HologramStockNativeEntry.beginObservation(ingress.submission())) {
                            var field = HologramNativeProjectionJob.Observation.class.getDeclaredField("site");
                            field.setAccessible(true);
                            SimpleAssert.eq(
                                formed && readSite,
                                field.get(observation) != null,
                                "only a formed controller with an explicit site request allocates the site reader");
                            if (!formed || !readSite) {
                                SimpleAssert.that(
                                    observation.advance(Long.MAX_VALUE),
                                    "state-only probe finishes in one advance");
                                var result = observation.result();
                                SimpleAssert.eq(
                                    formed ? HologramStockNativeWire.Stage.FORMED
                                        : HologramStockNativeWire.Stage.UNFORMED,
                                    result.stage(),
                                    "state comes from the existing native formed flag");
                                SimpleAssert
                                    .eq(29L, result.targetRevision(), "probe keeps the current target revision");
                                if (!formed) {
                                    var selected = result.siteSettings();
                                    SimpleAssert.that(
                                        selected.getBoolean("sitePlanning") && selected.getBoolean("siteReadSkipped"),
                                        "unformed state explicitly publishes planning without site inference");
                                    SimpleAssert.eq(
                                        3,
                                        selected.getInteger("main"),
                                        "unformed probe preserves the requested main");
                                    SimpleAssert.eq(
                                        5,
                                        selected.getCompoundTag("channels")
                                            .getInteger("coil"),
                                        "unformed probe preserves the requested material channel");
                                    SimpleAssert
                                        .that(!selected.getBoolean("siteKnown"), "planning is not a formed-site proof");
                                }
                            }
                        }
                        SimpleAssert.eq(
                            survival,
                            ModeController.freshSurvivalCalls,
                            "state probe and pending-site cancellation do not run native survival checks");
                        SimpleAssert
                            .eq(forced, ModeController.liveChecks, "observation never forces a live checkStructure");
                        SimpleAssert.that(
                            world.blockReads - reads <= 8,
                            "state probe uses only constant controller identity reads");
                        SimpleAssert.eq(
                            before,
                            HologramNativeCapture.capture(live),
                            "observation leaves formed/progress/inventory NBT intact");
                    }
                }
                // Permission revocation is still checked on every advance, even when no scene is read.
                machine.mMachine = false;
                player.capabilities.allowEdit = false;
                SimpleAssert.that(
                    !HologramNativeProjectionJob.controllerAccessAllowed(player, live, machine),
                    "lightweight formed-state access preserves current edit permission");
            } finally {
                HologramWorkspace.revokeActionEndpoint(player);
                player.playerNetServerHandler.netManager.channel()
                    .close();
                HologramNativeProjectionJob.clearBaselines();
            }
            System.out.println(
                "NATIVE_OBSERVATION_GATE_PASS unformedConstantReads=true formedGate=true forcedLiveChecks=0 sceneReads=0");
        }

        private static net.minecraft.entity.player.EntityPlayerMP observationPlayer(Scene world) throws Exception {
            var player = (net.minecraft.entity.player.EntityPlayerMP) unsafe()
                .allocateInstance(net.minecraft.entity.player.EntityPlayerMP.class);
            var actor = java.util.UUID.randomUUID();
            for (Field field : net.minecraft.entity.Entity.class.getDeclaredFields())
                if (field.getType() == java.util.UUID.class) {
                    field.setAccessible(true);
                    field.set(player, actor);
                }
            for (Field field : net.minecraft.entity.player.EntityPlayer.class.getDeclaredFields())
                if (field.getType() == com.mojang.authlib.GameProfile.class) {
                    field.setAccessible(true);
                    field.set(player, new com.mojang.authlib.GameProfile(actor, "NativeObservationActor"));
                }
            player.worldObj = world;
            player.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
            player.capabilities.allowEdit = true;
            player.inventory = new net.minecraft.entity.player.InventoryPlayer(player);
            var tool = new ItemStack(new com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector());
            var settings = new NBTTagCompound();
            settings.setString(
                HologramSessionKeys.TOOL_ID,
                java.util.UUID.randomUUID()
                    .toString());
            tool.setTagCompound(settings);
            player.inventory.mainInventory[0] = tool;
            var network = new net.minecraft.network.NetworkManager(false);
            for (Field field : net.minecraft.network.NetworkManager.class.getDeclaredFields())
                if (field.getType() == io.netty.channel.Channel.class) {
                    field.setAccessible(true);
                    field.set(
                        network,
                        new io.netty.channel.embedded.EmbeddedChannel(
                            new io.netty.channel.ChannelInboundHandlerAdapter()));
                }
            new net.minecraft.network.NetHandlerPlayServer(null, network, player);
            return player;
        }

        private static class ModeController
            extends gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<ModeController>
            implements com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable {

            static final java.util.UUID SOURCE_OWNER = new java.util.UUID(13, 17);
            static int freshSurvivalCalls, liveChecks;
            int materialTier;

            ModeController() {
                super("native_site_public_mode");
            }

            @Override
            public boolean supportsMachineModeSwitch() {
                return true;
            }

            @Override
            public com.gtnewhorizon.structurelib.structure.IStructureDefinition<ModeController> getStructureDefinition() {
                return null;
            }

            @Override
            protected gregtech.api.util.MultiblockTooltipBuilder createTooltip() {
                return new gregtech.api.util.MultiblockTooltipBuilder();
            }

            @Override
            public IMetaTileEntity newMetaEntity(gregtech.api.interfaces.tileentity.IGregTechTileEntity tile) {
                return new ModeController();
            }

            @Override
            public ITexture[] getTexture(gregtech.api.interfaces.tileentity.IGregTechTileEntity tile,
                ForgeDirection side, ForgeDirection facing, int color, boolean active, boolean redstone) {
                return new ITexture[0];
            }

            @Override
            public boolean checkStructure(boolean reset, gregtech.api.interfaces.tileentity.IGregTechTileEntity tile) {
                liveChecks++;
                return mMachine = false;
            }

            void assertClean() {
                for (ItemStack stack : mInventory)
                    SimpleAssert.eq(null, stack, "fresh controller has no live inventory");
                SimpleAssert.eq(0, mProgresstime, "fresh controller has no live progress");
                SimpleAssert.eq(0, mMaxProgresstime, "fresh controller has no live recipe duration");
                SimpleAssert.that(!mMachine, "fresh controller has no live formed state");
                SimpleAssert.that(
                    !SOURCE_OWNER.equals(getBaseMetaTileEntity().getOwnerUuid()),
                    "fresh controller has no live owner UUID");
                SimpleAssert.that(
                    !"native_mode_fixture_owner".equals(getBaseMetaTileEntity().getOwnerName()),
                    "fresh controller has no live owner name");
            }

            @Override
            public int survivalConstruct(ItemStack signal, int budget,
                com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment environment) {
                assertClean();
                SimpleAssert.eq(1, getMachineMode(), "all native site/target candidates receive audited mode one");
                freshSurvivalCalls++;
                construct(signal, false);
                return -1;
            }

            @Override
            public void construct(ItemStack signal, boolean hintsOnly) {
                World world = getBaseMetaTileEntity().getWorld();
                int x = getBaseMetaTileEntity().getXCoord(), y = getBaseMetaTileEntity().getYCoord(),
                    z = getBaseMetaTileEntity().getZCoord();
                if (getMachineMode() == 1) {
                    world.setBlock(x + 4, y, z, Blocks.gold_block, 0, 2);
                    world.setBlock(x + 5, y, z, Blocks.glass, 0, 2);
                } else world.setBlock(x + 1, y, z, Blocks.stone, 0, 2);
            }
        }

        private static final class Stored {

            final Block block;
            final int metadata;
            final TileEntity tile;

            Stored(Block block, int metadata, TileEntity tile) {
                this.block = block;
                this.metadata = metadata;
                this.tile = tile;
            }
        }

        private static final class Scene extends HologramResourceWorld {

            final Map<HologramNativeTargetPlan.Offset, Stored> cells = new LinkedHashMap<>();
            final java.util.Set<HologramNativeTargetPlan.Offset> readPositions = new java.util.LinkedHashSet<>();
            HologramNativeTargetPlan.Offset unloaded;
            boolean armed, controllerOnlyReads;
            long blockReads;

            Scene() {
                super(GregTechAPI.sBlockMachines, 0);
            }

            void seed(HologramNativeTargetPlan.Cell cell) {
                TileEntity created = null;
                if (cell.tileData() != null) {
                    created = cell.block()
                        .createTileEntity(this, cell.metadata());
                    created.setWorldObj(this);
                    created.readFromNBT(
                        (NBTTagCompound) cell.tileData()
                            .copy());
                    created.setWorldObj(this);
                }
                cells.put(
                    new HologramNativeTargetPlan.Offset(cell.dx(), cell.dy(), cell.dz()),
                    new Stored(cell.block(), cell.metadata(), created));
            }

            private HologramNativeTargetPlan.Offset at(int x, int y, int z) {
                return new HologramNativeTargetPlan.Offset(x, y - 128, z);
            }

            @Override
            boolean previewChunkExists(int x, int z) {
                return true;
            }

            @Override
            public boolean blockExists(int x, int y, int z) {
                return !at(x, y, z).equals(unloaded);
            }

            @Override
            public Block getBlock(int x, int y, int z) {
                if (controllerOnlyReads && (x != 0 || y != 128 || z != 0))
                    throw new AssertionError("state-only observation scanned a surrounding block");
                if (!blockExists(x, y, z)) throw new AssertionError("read unavailable source cell");
                if (armed) {
                    blockReads++;
                    readPositions.add(at(x, y, z));
                }
                if (x == 0 && y == 128 && z == 0) return GregTechAPI.sBlockMachines;
                Stored cell = cells.get(at(x, y, z));
                return cell == null ? Blocks.air : cell.block;
            }

            @Override
            public int getBlockMetadata(int x, int y, int z) {
                Stored cell = cells.get(at(x, y, z));
                return cell == null ? 0 : cell.metadata;
            }

            @Override
            public TileEntity getTileEntity(int x, int y, int z) {
                if (armed) readPositions.add(at(x, y, z));
                if (controllerOnlyReads && (x != 0 || y != 128 || z != 0))
                    throw new AssertionError("state-only observation scanned a surrounding tile");
                if (x == 0 && y == 128 && z == 0) return tile;
                Stored cell = cells.get(at(x, y, z));
                return cell == null ? null : cell.tile;
            }

            @Override
            public boolean setBlock(int x, int y, int z, Block b, int meta, int flags) {
                throw new AssertionError("live scene write");
            }

            @Override
            public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
                throw new AssertionError("live scene metadata write");
            }

            @Override
            public void setTileEntity(int x, int y, int z, TileEntity tile) {
                throw new AssertionError("live scene tile write");
            }

            @Override
            public void removeTileEntity(int x, int y, int z) {
                throw new AssertionError("live scene tile removal");
            }
        }
    }
}
