// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.threads.RunnableMachineUpdate;

/**
 * Opt-in GTSR 2.1.86 bytecode fixture. Uses the real center constructor and native creative/survival methods.
 * The palette and registry bootstrap are isolated offline stand-ins; this is not an in-game formation proof.
 * Launch only with the explicitly MCP-member-remapped 2.1.86 runtime jar, never install that fixture jar.
 */
public final class HologramOreCenterSiteRegression {

    private HologramOreCenterSiteRegression() {}

    public static void main(String[] args) {
        int status = 0;
        try {
            Class<?> bootstrap = Class.forName("com.miaokatze.gtmb.testutil.MinecraftTestBootstrap");
            if (!(Boolean) bootstrap.getMethod("enter", Class.class, String[].class)
                .invoke(null, HologramOreCenterSiteRegression.class, args)) return;
            Thread.currentThread()
                .setName("Server thread");
            net.minecraft.launchwrapper.Launch.classLoader
                .registerTransformer("com.miaokatze.gtmb.hologram.HologramNativeSiteEventFixtureTransformer");
            // Offline bootstrap has no Mixin loader: install only the exact production placement-entry callback.
            net.minecraft.launchwrapper.Launch.classLoader
                .registerTransformer("com.miaokatze.gtmb.hologram.HologramNativeSiteFixtureTransformer");
            Class.forName("com.miaokatze.gtmb.hologram.HologramNativeSavedWorldRegression")
                .getMethod("withOfflineRegistries", Runnable.class)
                .invoke(null, (Runnable) () -> {
                    try {
                        run(args);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                });
        } catch (Throwable failure) {
            failure.printStackTrace();
            status = 1;
        }
        System.exit(status);
    }

    static void run(String[] args) throws Exception {
        HologramOreCenterStallInvestigation.registry();
        Class<?> type = Class.forName("com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter");
        Constructor<?> ctor = type.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        HologramOreCenterStallInvestigation.prototype(ctor);
        System.out.println(
            "FIXTURE_SCOPE GTSR=2.1.86 realConstructor=true nativeMethods=true isolatedPalette=true placementSeam=true game=false");
        System.out.println(
            "COST_SCOPE continuousNoSleep=true advanceBudgetMs=2 diagnosticSlicesOnly=true productionTickBudgetsMs=40_idle_5_busy cpuBudgetEstimatesBasedOnAdvanceWallTime=true estimatesExcludeSchedulingAndSharedWork=true measuredGameTps=false");
        if (args.length == 2) {
            verify(ctor, Integer.parseInt(args[0]), Integer.parseInt(args[1]));
            System.out.println("CENTER_SITE_SINGLE_CONTRACT_PASSED");
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Expected no arguments, or tier and sector count");
        for (int tier : new int[] { 1, 4 }) for (int sectors : new int[] { 1, 5, 6 }) verify(ctor, tier, sectors);
        System.out.println(
            "CENTER_SITE_CONTRACTS_PASSED: six cold and six confirmed-configuration default-tool reopens; native hatches/directions; zero live writes/state changes");
    }

    private static void verify(Constructor<?> ctor, int tier, int sectors) throws Exception {
        BaseMetaTileEntity live = new BaseMetaTileEntity();
        Object center = HologramOreCenterStallInvestigation.center(ctor, live);
        HologramResourceWorld initial = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        live.yCoord = 128;
        live.setWorldObj(initial);
        initial.tile = live;
        HologramNativePreviewWorld scene = HologramNativePreviewWorld.open(live);
        live.setWorldObj(scene);
        ItemStack signal = HologramOreCenterStallInvestigation.signal(tier, sectors);
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        try {
            RunnableMachineUpdate.setCurrentThreadEnabled(false);
            ((IConstructable) center).construct(signal, false);
            center.getClass()
                .getMethod("onPreviewStructureComplete", ItemStack.class)
                .invoke(center, signal);
        } finally {
            RunnableMachineUpdate.setCurrentThreadEnabled(updates);
        }
        Map<HologramNativeTargetPlan.Offset, NBTTagCompound> hatches = new LinkedHashMap<>();
        for (var it = scene.currentPlacements(); it.hasNext();) {
            var cell = it.next();
            if (cell.tile() instanceof IGregTechTileEntity hatch) {
                ForgeDirection direction = hatches.size() % 2 == 0 ? ForgeDirection.EAST : ForgeDirection.WEST;
                hatch.setFrontFacing(direction);
                hatches.put(
                    new HologramNativeTargetPlan.Offset(cell.x(), cell.y() - 128, cell.z()),
                    HologramNativeCapture.capture(cell.tile()));
            }
        }
        if (hatches.size() != 7) throw new AssertionError("Native preview ports missing: " + hatches.size());
        ((MTEMultiBlockBase) center).mMachine = true;
        NBTTagCompound before = HologramNativeCapture.capture(live);
        long writes = (Long) HologramOreCenterStallInvestigation.get(scene, "writeVersion");
        long signature = scene.contentSignature();
        int placements = scene.recordedPlacementCount();
        scene.freeze();
        exactNativePreflight(scene, signal, tier, sectors);
        // The default tool starts with no manually selected structure channel.
        NBTTagCompound requested = new NBTTagCompound();
        requested.setInteger("main", 1);
        requested.setTag("channels", new NBTTagCompound());
        HologramNativeProjectionJob.clearBaselines();
        for (boolean reopened : new boolean[] { false, true }) {
            long start = System.nanoTime();
            NBTTagCompound selected;
            int footprint;
            List<HologramNativeTargetPlan.Offset> siteOffsets;
            long advanceCalls = 0, advanceNanos = 0, maxAdvanceNanos = 0;
            try (var site = HologramNativeSitePlan.begin(live, requested)) {
                for (;;) {
                    long sliceStart = System.nanoTime();
                    boolean complete = site.advance(sliceStart + 2_000_000L);
                    long sliceNanos = System.nanoTime() - sliceStart;
                    advanceCalls++;
                    advanceNanos += sliceNanos;
                    maxAdvanceNanos = Math.max(maxAdvanceNanos, sliceNanos);
                    if (complete) break;
                    if (advanceCalls > 200_000)
                        throw new AssertionError("Site fixture exceeded bounded frame allowance");
                }
                selected = site.settings();
                footprint = site.footprint()
                    .size();
                siteOffsets = new ArrayList<>(site.footprint());
            }
            long elapsed = System.nanoTime() - start;
            if (selected.getInteger("main") != tier || selected.getCompoundTag("channels")
                .getInteger("ore_center_sectors") != sectors
                || !selected.getBoolean("siteKnown")
                || selected.getInteger("siteUnknown") != 0
                || selected.getBoolean("siteUsedConfirmedConfiguration") != reopened
                || footprint != placements)
                throw new AssertionError(
                    "Default-tool center site mismatch " + tier
                        + "/"
                        + sectors
                        + ": "
                        + selected
                        + " footprint="
                        + footprint
                        + " live="
                        + placements);
            NBTTagList choices = selected.getTagList("nativeHatchChoices", 10);
            if (choices.tagCount() != hatches.size()) throw new AssertionError("Site hatch count differs");
            for (int i = 0; i < choices.tagCount(); i++) {
                NBTTagCompound row = choices.getCompoundTagAt(i);
                var offset = new HologramNativeTargetPlan.Offset(
                    row.getInteger("x"),
                    row.getInteger("y"),
                    row.getInteger("z"));
                NBTTagCompound original = hatches.get(offset);
                if (original == null || original.getShort("mID") != row.getInteger("id")
                    || scene.getTileEntity(offset.x(), offset.y() + 128, offset.z()) == null
                    || ((IGregTechTileEntity) scene.getTileEntity(offset.x(), offset.y() + 128, offset.z()))
                        .getFrontFacing()
                        .ordinal() != row.getInteger("facing"))
                    throw new AssertionError("Native site hatch identity/direction lost: " + row);
            }
            long replayStart = System.nanoTime();
            try (var target = HologramNativeTargetPlan.compile(
                32651,
                ExtendedFacing.DEFAULT,
                HologramNativeTargetPlan
                    .canonicalTrigger(selected.getInteger("main"), selected.getCompoundTag("channels")),
                selected)) {
                int restored = 0;
                for (var cell : target.cells()) {
                    var offset = new HologramNativeTargetPlan.Offset(cell.dx(), cell.dy(), cell.dz());
                    NBTTagCompound original = hatches.get(offset);
                    if (original == null) continue;
                    IGregTechTileEntity hatch = (IGregTechTileEntity) scene
                        .getTileEntity(cell.dx(), cell.dy() + 128, cell.dz());
                    if (cell.material() == null || cell.material()
                        .getItemDamage() != hatch.getMetaTileID()
                        || cell.facing() != hatch.getFrontFacing()
                            .ordinal()
                        || cell.tileData() == null)
                        throw new AssertionError("Configured target did not restore native site hatch: " + offset);
                    restored++;
                }
                if (restored != hatches.size()) throw new AssertionError("Configured target lost site hatch offsets");
            }
            long replayElapsed = System.nanoTime() - replayStart;
            if (!before.equals(HologramNativeCapture.capture(live))
                || writes != (Long) HologramOreCenterStallInvestigation.get(scene, "writeVersion")
                || signature != scene.contentSignature()
                || !((MTEMultiBlockBase) center).mMachine)
                throw new AssertionError("Site read mutated live controller or blocks");
            for (var entry : hatches.entrySet()) {
                var at = entry.getKey();
                if (!entry.getValue()
                    .equals(HologramNativeCapture.capture(scene.getTileEntity(at.x(), at.y() + 128, at.z()))))
                    throw new AssertionError("Site read mutated live hatch state");
            }
            System.out.println(
                "PASS_CENTER_SITE opening=" + (reopened ? "reopen" : "cold")
                    + " tier="
                    + tier
                    + " sectors="
                    + sectors
                    + " cells="
                    + footprint
                    + " hatches="
                    + choices.tagCount()
                    + " nativeCalls="
                    + selected.getLong("siteNativeCalls")
                    + " uniqueCells="
                    + selected.getLong("siteUniqueCells")
                    + " cellReads="
                    + selected.getLong("siteCellReads")
                    + " continuousElapsedMs="
                    + elapsed / 1_000_000L
                    + " advanceCalls="
                    + advanceCalls
                    + " advanceBudgetMs=2 advanceTotalMs="
                    + advanceNanos / 1_000_000.0
                    + " maxAdvanceMs="
                    + maxAdvanceNanos / 1_000_000.0
                    + " busyFiveMsPer20TpsTickCpuLowerBoundMs="
                    + ((advanceNanos + 4_999_999L) / 5_000_000L) * 50
                    + " idleFortyMsPer20TpsTickCpuLowerBoundMs="
                    + ((advanceNanos + 39_999_999L) / 40_000_000L) * 50
                    + " targetReplayMs="
                    + replayElapsed / 1_000_000L
                    + " liveWrites=0 liveStateChanges=0");
            // Match the real Observation's successful publication into the existing in-memory baseline.
            // The next open still sends the unchanged default tool configuration, not the resolved first-open settings.
            HologramNativeProjectionJob.rememberSite(live, siteOffsets, selected);
        }
        HologramNativeProjectionJob.clearBaselines();
    }

    /** Native cursor completion must prove the fixture itself before exercising automatic signal selection. */
    private static void exactNativePreflight(HologramNativePreviewWorld scene, ItemStack signal, int tier, int sectors)
        throws Exception {
        BaseMetaTileEntity candidate = HologramNativeTargetPlan.freshController(32651, ExtendedFacing.DEFAULT);
        GregTechAPI.addDummyWorld(PreflightWorld.class);
        PreflightWorld mirror = HologramResourceWorld.create(() -> new PreflightWorld(scene, candidate));
        candidate.setWorldObj(mirror);
        Object machine = candidate.getMetaTileEntity();
        Object structure = HologramOreCenterStallInvestigation.get(machine, "structure");
        var environment = ISurvivalBuildEnvironment.create(
            (predicate, simulate, count) -> {
                throw new AssertionError("Exact native fixture needed material at " + mirror.last());
            },
            new HologramProjectorSimulation.Actor(mirror));
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        int previous = -1;
        try {
            RunnableMachineUpdate.setCurrentThreadEnabled(false);
            for (int round = 1; round <= 2000; round++) {
                int result = ((ISurvivalConstructable) machine).survivalConstruct(signal.copy(), 4096, environment);
                if (result == -1) {
                    System.out.println(
                        "PASS_CENTER_EXACT_PREFLIGHT tier=" + tier + " sectors=" + sectors + " nativeCalls=" + round);
                    return;
                }
                int cursor = (Integer) HologramOreCenterStallInvestigation.get(structure, "survivalCursor");
                if (result != 0 || cursor == previous) throw new AssertionError(
                    "Exact native fixture did not match tier=" + tier
                        + " sectors="
                        + sectors
                        + " nativeResult="
                        + result
                        + " cursor="
                        + cursor
                        + " last="
                        + mirror.last());
                previous = cursor;
            }
            throw new AssertionError("Exact native fixture exceeded cursor allowance");
        } finally {
            RunnableMachineUpdate.setCurrentThreadEnabled(updates);
        }
    }

    private static final class PreflightWorld extends HologramResourceWorld {

        private final HologramNativePreviewWorld source;
        private final BaseMetaTileEntity controller;
        private int x, y, z;

        PreflightWorld(HologramNativePreviewWorld source, BaseMetaTileEntity controller) {
            super(GregTechAPI.sBlockMachines, 0);
            this.source = source;
            this.controller = controller;
            tile = controller;
        }

        String last() {
            return x + ","
                + y
                + ","
                + z
                + " block="
                + source.getBlock(x, y, z)
                + " metadata="
                + source.getBlockMetadata(x, y, z);
        }

        @Override
        boolean previewChunkExists(int x, int z) {
            return source.previewChunkExists(x, z);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return source.blockExists(x, y, z);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return source.getBlock(x, y, z);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return source.getBlockMetadata(x, y, z);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return x == controller.xCoord && y == controller.yCoord && z == controller.zCoord ? controller
                : source.getTileEntity(x, y, z);
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("Native preflight attempted write");
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            throw new AssertionError("Native preflight attempted metadata write");
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            throw new AssertionError("Native preflight attempted tile write");
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            throw new AssertionError("Native preflight attempted tile removal");
        }
    }
}
