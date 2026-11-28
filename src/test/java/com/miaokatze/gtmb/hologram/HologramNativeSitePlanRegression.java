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

/** Actual EBF survival checks against an immutable loaded scene; no game, server or client is started. */
public final class HologramNativeSitePlanRegression {

    private static final int CONTROLLER = 32648, ENERGY = 32647, PORT_START = 32640;
    private static int blockId = 3790;

    private HologramNativeSitePlanRegression() {}

    public static void main(String[] args) {
        int result = 0;
        try {
            if (!MinecraftTestBootstrap.enter(HologramNativeSitePlanRegression.class, args)) return;
            net.minecraft.launchwrapper.Launch.classLoader
                .registerTransformer(HologramNativeSiteEventFixtureTransformer.class.getName());
            net.minecraft.launchwrapper.Launch.classLoader
                .registerTransformer("com.miaokatze.gtmb.hologram.HologramNativeSiteFixtureTransformer");
            Thread.currentThread()
                .setName("Server thread");
            HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                try {
                    registries();
                    verify(1);
                    verify(5);
                    verifyControllerMode();
                } catch (Exception failure) {
                    throw new AssertionError("actual EBF site fixture", failure);
                }
            });
            System.out.println("NATIVE_SITE_EBF_PASS scope=offline_native_survival_and_target no_game_started=true");
        } catch (Throwable failure) {
            failure.printStackTrace();
            result = 1;
        }
        System.exit(result);
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
        if (gregtech.GTMod.proxy == null)
            gregtech.GTMod.proxy = (gregtech.common.GTProxy) unsafe().allocateInstance(gregtech.common.GTProxy.class);
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

    private static void verify(int coilChannel) throws Exception {
        NBTTagCompound defaults = HologramLocalControllerCapture.toolSettings(new ItemStack(Items.stick));
        Scene world = HologramResourceWorld.create(Scene::new);
        BaseMetaTileEntity live = HologramNativeTargetPlan.freshController(CONTROLLER, ExtendedFacing.DEFAULT);
        live.setWorldObj(world);
        world.tile = live;
        HologramNativeTargetPlan.Cell replace = null, coil = null;
        NBTTagCompound installedChannels = new NBTTagCompound();
        installedChannels.setInteger("coil", coilChannel);
        try (var target = HologramNativeTargetPlan.compile(
            CONTROLLER,
            ExtendedFacing.DEFAULT,
            HologramNativeTargetPlan.canonicalTrigger(1, installedChannels),
            new NBTTagCompound())) {
            for (var cell : target.cells()) {
                world.seed(cell);
                if (cell.block() == GregTechAPI.sBlockCasings1 && cell.dy() == 0) replace = cell;
                if (cell.block() == GregTechAPI.sBlockCasings5) coil = cell;
            }
        }
        SimpleAssert.that(replace != null && coil != null, "real EBF supplies bottom hatch and coil positions");
        final Block coilBlock = coil.block();
        final int coilMetadata = coil.metadata();
        System.out.println(
            "NATIVE_SITE_COIL_MAPPING channel=" + coilChannel
                + " block="
                + Block.blockRegistry.getNameForObject(coilBlock)
                + " metadata="
                + coilMetadata);
        var at = new HologramNativeTargetPlan.Offset(replace.dx(), replace.dy(), replace.dz());
        BaseMetaTileEntity hatch = new BaseMetaTileEntity();
        hatch.xCoord = at.x();
        hatch.yCoord = 128 + at.y();
        hatch.zCoord = at.z();
        hatch.setWorldObj(world);
        hatch.setInitialValuesAsNBT(null, (short) ENERGY);
        hatch.setFrontFacing(ForgeDirection.WEST);
        hatch.setColorization((byte) 7);
        ((MTEHatchEnergy) hatch.getMetaTileEntity()).updateTexture(23);
        world.cells.put(at, new Stored(GregTechAPI.sBlockMachines, 0, hatch));
        NBTTagCompound before = HologramNativeCapture.capture(hatch);
        ItemStack expectedItem = HologramGtNativeItemState.sample(GregTechAPI.sBlockMachines, hatch);
        NBTTagCompound expectedDisplay = HologramDisplaySnapshot.projection(hatch);
        world.armed = true;
        NBTTagCompound confirmed = null;
        java.util.List<HologramNativeTargetPlan.Offset> confirmedOffsets = null;
        for (int reopening = 0; reopening < 2; reopening++) {
            world.blockReads = 0;
            long start = System.nanoTime();
            try (var site = HologramNativeSitePlan.begin(live, defaults)) {
                SimpleAssert.that(site.advance(Long.MAX_VALUE), "actual EBF site completes");
                NBTTagCompound found = site.settings();
                SimpleAssert.that(found.getBoolean("siteKnown"), "complete native EBF scene is known: " + found);
                if (coilChannel == 1)
                    SimpleAssert.eq(1, found.getInteger("main"), "default native coil retains the smallest main");
                SimpleAssert.eq(
                    coilChannel,
                    found.getCompoundTag("channels")
                        .getInteger("coil"),
                    "native material channel follows the installed real coil material");
                SimpleAssert.eq(
                    2 * found.getLong("siteUniqueCells"),
                    world.blockReads,
                    "each scene cell is captured once and revalidated once, independently of candidate count");
                SimpleAssert.eq(world.blockReads, found.getLong("siteCellReads"), "published actual source reads");
                var pins = found.getTagList("nativeHatchChoices", 10);
                NBTTagCompound pin = null;
                for (int i = 0; i < pins.tagCount(); i++) if (pins.getCompoundTagAt(i)
                    .getInteger("id") == ENERGY) pin = pins.getCompoundTagAt(i);
                SimpleAssert.that(pin != null, "native site retains installed legal energy hatch");
                SimpleAssert
                    .that(pin.getBoolean("siteNativeAccepted"), "actual native scene publishes its acceptance marker");
                SimpleAssert.eq(ForgeDirection.WEST.ordinal(), pin.getInteger("facing"), "site hatch facing");
                SimpleAssert
                    .eq(expectedItem.writeToNBT(new NBTTagCompound()), pin.getCompoundTag("item"), "site native item");
                SimpleAssert.eq(expectedDisplay, pin.getCompoundTag("display"), "site hatch display");
                if (reopening == 0) verifySitePayloadMerge(found, pin);
                try (var target = HologramNativeTargetPlan.compile(
                    CONTROLLER,
                    ExtendedFacing.DEFAULT,
                    HologramNativeTargetPlan
                        .canonicalTrigger(found.getInteger("main"), found.getCompoundTag("channels")),
                    found)) {
                    var result = target.cells()
                        .stream()
                        .filter(c -> c.dx() == at.x() && c.dy() == at.y() && c.dz() == at.z())
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("installed hatch target missing"));
                    SimpleAssert.eq(
                        ENERGY,
                        result.material()
                            .getItemDamage(),
                        "final target hatch id");
                    SimpleAssert.eq(ForgeDirection.WEST.ordinal(), result.facing(), "final target hatch facing");
                    SimpleAssert
                        .that(ItemStack.areItemStacksEqual(expectedItem, result.material()), "final native item state");
                    SimpleAssert.eq(expectedDisplay, result.display(), "final target display");
                    SimpleAssert.eq(
                        16L,
                        target.cells()
                            .stream()
                            .filter(c -> c.block() == coilBlock && c.metadata() == coilMetadata)
                            .count(),
                        "all sixteen final native EBF coils preserve the installed material tier");
                }
                SimpleAssert
                    .eq(before, HologramNativeCapture.capture(hatch), "site and target do not mutate live hatch");
                confirmed = found;
                confirmedOffsets = new java.util.ArrayList<>(site.footprint());
                System.out.println(
                    "NATIVE_SITE_EBF_CASE coilChannel=" + coilChannel
                        + " reopening="
                        + reopening
                        + " calls="
                        + found.getLong("siteNativeCalls")
                        + " sourceUniqueCells="
                        + found.getLong("siteUniqueCells")
                        + " sourceCellReads="
                        + found.getLong("siteCellReads")
                        + " footprint="
                        + site.footprint()
                            .size()
                        + " sourceGetBlockCalls="
                        + world.blockReads
                        + " elapsedNanos="
                        + (System.nanoTime() - start));
            }
        }
        HologramNativeProjectionJob.clearBaselines();
        HologramNativeProjectionJob.rememberSite(live, confirmedOffsets, confirmed);
        var missing = new HologramNativeTargetPlan.Offset(coil.dx(), coil.dy(), coil.dz());
        Stored saved = world.cells.remove(missing);
        assertUnknown(live, defaults, "broken");
        try (var reopened = HologramNativeSitePlan.begin(live, defaults)) {
            SimpleAssert.that(reopened.advance(Long.MAX_VALUE), "damaged EBF reopens through a fresh native site read");
            NBTTagCompound observed = reopened.settings();
            SimpleAssert.that(!observed.getBoolean("siteKnown"), "damage has no newly invented native proof");
            SimpleAssert.that(
                observed.getBoolean("siteConfirmedConfigurationRejected")
                    && !observed.getBoolean("siteUsedConfirmedConfiguration"),
                "failed confirmed configuration returns to complete native inference");
            SimpleAssert.that(
                observed.getLong("siteNativeCalls") > confirmed.getLong("siteNativeCalls"),
                "damage uses full inference after the additional confirmed-configuration check");
            NBTTagCompound retained = HologramNativeProjectionJob.retainProvedSettings(live, observed);
            SimpleAssert.that(
                retained.getBoolean("siteKnown") && retained.getBoolean("siteDamaged"),
                "production retention marks the previously proved scene as damaged");
            SimpleAssert.eq(confirmed.getInteger("main"), retained.getInteger("main"), "repair retains proved main");
            SimpleAssert.eq(
                confirmed.getCompoundTag("channels"),
                retained.getCompoundTag("channels"),
                "repair retains proved native channels");
            SimpleAssert.eq(
                confirmed.getTag("nativeHatchChoices"),
                retained.getTag("nativeHatchChoices"),
                "repair retains proved exact hatch choices");
            try (var repair = HologramNativeTargetPlan.compile(
                CONTROLLER,
                ExtendedFacing.DEFAULT,
                HologramNativeTargetPlan
                    .canonicalTrigger(retained.getInteger("main"), retained.getCompoundTag("channels")),
                retained)) {
                SimpleAssert.eq(
                    new java.util.LinkedHashSet<>(confirmedOffsets),
                    repair.footprint(),
                    "production retained settings rebuild the complete independently proved footprint");
                var restored = repair.cells()
                    .stream()
                    .filter(c -> c.dx() == missing.x() && c.dy() == missing.y() && c.dz() == missing.z())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("repair omitted missing coil"));
                SimpleAssert.eq(saved.block, restored.block(), "repair reconstructs the original missing coil block");
                SimpleAssert.eq(saved.metadata, restored.metadata(), "repair reconstructs the original coil tier");
                var port = repair.cells()
                    .stream()
                    .filter(c -> c.dx() == at.x() && c.dy() == at.y() && c.dz() == at.z())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("repair omitted installed hatch"));
                SimpleAssert
                    .that(ItemStack.areItemStacksEqual(expectedItem, port.material()), "repair native hatch item");
                SimpleAssert.eq(ForgeDirection.WEST.ordinal(), port.facing(), "repair hatch facing");
                SimpleAssert.eq(expectedDisplay, port.display(), "repair hatch display");
                SimpleAssert
                    .eq(before, HologramNativeCapture.capture(hatch), "repair target leaves live hatch unchanged");
                System.out.println(
                    "NATIVE_SITE_EBF_REPAIR provedCells=" + confirmedOffsets.size()
                        + " repairCells="
                        + repair.footprint()
                            .size()
                        + " siteDamaged="
                        + retained.getBoolean("siteDamaged")
                        + " siteNativeCalls="
                        + observed.getLong("siteNativeCalls")
                        + " confirmedRejected="
                        + observed.getBoolean("siteConfirmedConfigurationRejected")
                        + " usedConfirmed="
                        + observed.getBoolean("siteUsedConfirmedConfiguration")
                        + " scope=production_retention_and_native_target full_network_permissions=not_exercised");
            }
            HologramNativeProjectionJob.clearBaselines();
            NBTTagCompound uncached = HologramNativeProjectionJob.retainProvedSettings(live, observed);
            SimpleAssert.that(
                !uncached.getBoolean("siteKnown") && !uncached.getBoolean("siteDamaged"),
                "clearing production baselines cannot turn the same damaged scene into proof");
        }
        world.cells.put(missing, saved);
        world.unloaded = missing;
        assertUnknown(live, defaults, "unloaded");
        try (var cancelled = HologramNativeSitePlan.begin(live, defaults)) {
            cancelled.close();
            try {
                cancelled.advance(Long.MAX_VALUE);
                throw new AssertionError("cancelled site continued");
            } catch (java.io.IOException expected) { /* cancellation is terminal */ }
        }
        verifyScopeAuthority(live, confirmed);
    }

    private static void verifyScopeAuthority(BaseMetaTileEntity live, NBTTagCompound canonical) {
        var speculative = new HologramNativeTargetPlan.Offset(999, 0, 0);
        var explicit = new HologramNativeTargetPlan.Offset(1, 0, 0);
        var old = new HologramNativeTargetPlan.Offset(2, 0, 0);
        NBTTagCompound unknown = (NBTTagCompound) canonical.copy();
        unknown.setBoolean("siteKnown", false);
        HologramNativeProjectionJob.clearBaselines();
        HologramNativeProjectionJob.rememberSite(live, java.util.Collections.singletonList(speculative), unknown);
        SimpleAssert.that(
            HologramNativeProjectionJob.provedHistory(live)
                .isEmpty(),
            "unknown observation cannot authorize a speculative offset");
        HologramNativeProjectionJob
            .rememberConstructedTarget(live, java.util.Collections.singletonList(explicit), canonical, false);
        SimpleAssert.eq(
            java.util.Collections.singleton(explicit),
            new java.util.LinkedHashSet<>(HologramNativeProjectionJob.provedHistory(live)),
            "successful explicit construction authorizes only its target after an unknown observation");

        HologramNativeProjectionJob.clearBaselines();
        HologramNativeProjectionJob.rememberSite(live, java.util.Collections.singletonList(old), canonical);
        HologramNativeProjectionJob.rememberSite(live, java.util.Collections.singletonList(speculative), unknown);
        SimpleAssert.eq(
            java.util.Collections.singleton(old),
            new java.util.LinkedHashSet<>(HologramNativeProjectionJob.provedHistory(live)),
            "unknown observation preserves existing trusted history without appending speculative cells");
        HologramNativeProjectionJob
            .rememberConstructedTarget(live, java.util.Collections.singletonList(explicit), canonical, false);
        SimpleAssert.eq(
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(old, explicit)),
            new java.util.LinkedHashSet<>(HologramNativeProjectionJob.provedHistory(live)),
            "ordinary repair retains old trusted history plus the explicit successful target");
        HologramNativeProjectionJob
            .rememberConstructedTarget(live, java.util.Collections.singletonList(explicit), canonical, true);
        SimpleAssert.eq(
            java.util.Collections.singleton(explicit),
            new java.util.LinkedHashSet<>(HologramNativeProjectionJob.provedHistory(live)),
            "successful synchronized repair narrows trusted history to the explicit target");
        HologramNativeProjectionJob.clearBaselines();
        System.out.println(
            "NATIVE_SITE_AUTHORITY_PASS unknownSpeculative999=false explicitConstruction1=true"
                + " ordinaryRepairHistory=old2+target1 synchronizedRepairHistory=target1 scope=production_observation_and_finished_helpers");
    }

    private static void verifySitePayloadMerge(NBTTagCompound site, NBTTagCompound sourcePin) {
        NBTTagCompound sourceOriginal = (NBTTagCompound) site.copy();
        NBTTagCompound request = (NBTTagCompound) site.copy();
        var wanted = (NBTTagCompound) sourcePin.copy();
        wanted.setTag("item", new ItemStack(Items.stick).writeToNBT(new NBTTagCompound()));
        NBTTagCompound forgedDisplay = new NBTTagCompound();
        forgedDisplay.setInteger("color", 99);
        wanted.setTag("display", forgedDisplay);
        var onlyRequested = new net.minecraft.nbt.NBTTagList();
        onlyRequested.appendTag(wanted);
        request.setTag("nativeHatchChoices", onlyRequested);
        NBTTagCompound original = (NBTTagCompound) request.copy();
        NBTTagCompound merged = HologramNativeTargetPlan.retainSiteChoices(request, site);
        var rows = merged.getTagList("nativeHatchChoices", 10);
        SimpleAssert.eq(1, rows.tagCount(), "shared site merge does not append unrequested installed hatches");
        var restored = rows.getCompoundTagAt(0);
        SimpleAssert.eq(
            sourcePin.getCompoundTag("item"),
            restored.getCompoundTag("item"),
            "shared merge restores trusted exact source item");
        SimpleAssert.eq(
            sourcePin.getCompoundTag("display"),
            restored.getCompoundTag("display"),
            "same native parameters retain source display");
        SimpleAssert.that(restored.getBoolean("siteNativeAccepted"), "shared merge retains trusted native acceptance");
        SimpleAssert.eq(original, request, "shared merge returns a copy without changing requested settings");
        restored.getCompoundTag("display")
            .setInteger("color", 98);
        SimpleAssert.eq(sourceOriginal, site, "merge result does not share mutable site display data");
        for (boolean differentChannel : new boolean[] { false, true }) {
            NBTTagCompound changed = (NBTTagCompound) request.copy();
            if (differentChannel) changed.getCompoundTag("channels")
                .setInteger(
                    "coil",
                    site.getCompoundTag("channels")
                        .getInteger("coil") + 1);
            else changed.setInteger("main", site.getInteger("main") + 1);
            var row = HologramNativeTargetPlan.retainSiteChoices(changed, site)
                .getTagList("nativeHatchChoices", 10)
                .getCompoundTagAt(0);
            SimpleAssert.eq(
                sourcePin.getCompoundTag("item"),
                row.getCompoundTag("item"),
                "changed parameters retain requested source item");
            SimpleAssert.that(
                row.getBoolean("siteNativeAccepted") && !row.hasKey("display"),
                "changed main/channels cannot inherit stale source display");
        }
        NBTTagCompound differentId = (NBTTagCompound) request.copy();
        differentId.getTagList("nativeHatchChoices", 10)
            .getCompoundTagAt(0)
            .setInteger("id", sourcePin.getInteger("id") - 1);
        var changedModel = HologramNativeTargetPlan.retainSiteChoices(differentId, site)
            .getTagList("nativeHatchChoices", 10)
            .getCompoundTagAt(0);
        SimpleAssert.that(
            !changedModel.hasKey("item") && !changedModel.hasKey("display")
                && !changedModel.hasKey("siteNativeAccepted"),
            "different requested model cannot inherit another hatch's item NBT, display or native acceptance");
        NBTTagCompound none = (NBTTagCompound) request.copy();
        none.setTag("nativeHatchChoices", new net.minecraft.nbt.NBTTagList());
        SimpleAssert.eq(
            0,
            HologramNativeTargetPlan.retainSiteChoices(none, site)
                .getTagList("nativeHatchChoices", 10)
                .tagCount(),
            "unrequested native hatches are never restored by the shared merge");
        System.out.println(
            "NATIVE_SITE_PAYLOAD_MERGE_PASS trustedItem=true trustedAcceptance=true displayRequiresSameParameters=true"
                + " changedModelPayload=false unrequestedHatches=false inputMutation=false");
    }

    private static void assertUnknown(BaseMetaTileEntity live, NBTTagCompound defaults, String mode) throws Exception {
        try (var site = HologramNativeSitePlan.begin(live, defaults)) {
            SimpleAssert.that(site.advance(Long.MAX_VALUE), "partial native EBF scene completes: " + mode);
            SimpleAssert.that(
                !site.settings()
                    .getBoolean("siteKnown"),
                "partial EBF is never known: " + mode);
            System.out.println(
                "NATIVE_SITE_EBF_PARTIAL mode=" + mode
                    + " calls="
                    + site.settings()
                        .getLong("siteNativeCalls")
                    + " sourceCellReads="
                    + site.settings()
                        .getLong("siteCellReads"));
        }
    }

    private static void verifyControllerMode() throws Exception {
        final int id = 32639;
        prototype(id, new ModeController());
        Scene world = HologramResourceWorld.create(Scene::new);
        BaseMetaTileEntity live = HologramNativeTargetPlan.freshController(id, ExtendedFacing.DEFAULT);
        live.setWorldObj(world);
        world.tile = live;
        ModeController machine = (ModeController) live.getMetaTileEntity();
        ((gregtech.api.interfaces.modularui.IControllerWithOptionalFeatures) machine).setMachineMode(1);
        machine.mInventory[0] = new ItemStack(Items.diamond, 32);
        machine.mProgresstime = 43;
        machine.mMaxProgresstime = 100;
        machine.mMachine = true;
        live.setOwnerName("native_mode_fixture_owner");
        live.setOwnerUuid(ModeController.SOURCE_OWNER);
        world.cells.put(new HologramNativeTargetPlan.Offset(4, 0, 0), new Stored(Blocks.gold_block, 0, null));
        world.cells.put(new HologramNativeTargetPlan.Offset(5, 0, 0), new Stored(Blocks.glass, 0, null));
        NBTTagCompound before = HologramNativeCapture.capture(live);
        NBTTagCompound defaults = HologramLocalControllerCapture.toolSettings(new ItemStack(Items.stick));
        ModeController.freshSurvivalCalls = 0;
        try (var site = HologramNativeSitePlan.begin(live, defaults)) {
            SimpleAssert.that(site.advance(Long.MAX_VALUE), "portable controller mode site completes");
            NBTTagCompound found = site.settings();
            SimpleAssert.that(found.getBoolean("siteKnown"), "default tool recognizes public machineMode=1 scene");
            NBTTagCompound payload = found.getCompoundTag("siteControllerConfiguration");
            SimpleAssert.eq(
                1,
                payload.getCompoundTag(HologramNativeConfiguration.TAG)
                    .getCompoundTag("settings")
                    .getInteger("machineMode"),
                "site stores the audited native controller mode envelope");
            SimpleAssert.eq(
                1,
                payload.func_150296_c()
                    .size(),
                "portable mode payload contains only its audited envelope");
            try (var target = HologramNativeTargetPlan.compile(
                id,
                ExtendedFacing.DEFAULT,
                HologramNativeTargetPlan.canonicalTrigger(found.getInteger("main"), found.getCompoundTag("channels")),
                found)) {
                SimpleAssert.eq(
                    2,
                    target.cells()
                        .size(),
                    "mode one final native target retains both cells");
                SimpleAssert.that(
                    target.cells()
                        .stream()
                        .anyMatch(c -> c.dx() == 4 && c.block() == Blocks.gold_block)
                        && target.cells()
                            .stream()
                            .anyMatch(c -> c.dx() == 5 && c.block() == Blocks.glass),
                    "final target uses public machine mode rather than default mode zero geometry");
                ModeController fresh = (ModeController) target.context();
                SimpleAssert.eq(1, fresh.getMachineMode(), "target controller receives public native mode setter");
                fresh.assertClean();
            }
            SimpleAssert
                .that(ModeController.freshSurvivalCalls >= 65, "every lightweight candidate uses a fresh native mode");
            HologramNativeProjectionJob.rememberSite(live, new java.util.ArrayList<>(site.footprint()), found);
            SimpleAssert.that(
                !HologramNativeProjectionJob.portableConfigurationChanged(live),
                "freshly proved public mode has not changed");
            machine.setMachineMode(0);
            SimpleAssert.that(
                HologramNativeProjectionJob.portableConfigurationChanged(live),
                "public mode change invalidates the proved portable configuration");
            SimpleAssert.eq(
                null,
                HologramNativeProjectionJob.provedSettings(live),
                "changed public mode cannot reuse proved settings");
            machine.setMachineMode(1);
            SimpleAssert.that(
                !HologramNativeProjectionJob.portableConfigurationChanged(live),
                "restoring public mode restores portable configuration equality");
            SimpleAssert.that(
                HologramNativeProjectionJob.provedSettings(live) != null,
                "restored public mode can reuse its proof");
            HologramNativeProjectionJob.clearBaselines();
            SimpleAssert.eq(
                before,
                HologramNativeCapture.capture(live),
                "portable controller capture leaves all live NBT intact");
            SimpleAssert.that(machine.mMachine, "live formed state remains true without becoming target authority");
            SimpleAssert.eq(43, machine.mProgresstime, "live recipe progress remains intact");
            SimpleAssert.eq(32, machine.mInventory[0].stackSize, "live inventory remains intact");
            System.out.println(
                "NATIVE_SITE_PUBLIC_MODE_PASS mode=1 cells=2 freshCalls=" + ModeController.freshSurvivalCalls
                    + " copiedInventory=false copiedProgress=false copiedOwner=false copiedFormedState=false");
        }
    }

    private static final class ModeController
        extends gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<ModeController>
        implements com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable {

        static final java.util.UUID SOURCE_OWNER = new java.util.UUID(13, 17);
        static int freshSurvivalCalls;

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
        public ITexture[] getTexture(gregtech.api.interfaces.tileentity.IGregTechTileEntity tile, ForgeDirection side,
            ForgeDirection facing, int color, boolean active, boolean redstone) {
            return new ITexture[0];
        }

        @Override
        public boolean checkStructure(boolean reset, gregtech.api.interfaces.tileentity.IGregTechTileEntity tile) {
            return mMachine = false;
        }

        void assertClean() {
            for (ItemStack stack : mInventory) SimpleAssert.eq(null, stack, "fresh controller has no live inventory");
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
        HologramNativeTargetPlan.Offset unloaded;
        boolean armed;
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
            if (!blockExists(x, y, z)) throw new AssertionError("read unavailable source cell");
            if (armed) blockReads++;
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
