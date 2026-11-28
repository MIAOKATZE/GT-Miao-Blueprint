// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.chunk.Chunk;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.threads.RunnableMachineUpdate;

/**
 * Explicit actual GTSR 2.1.90-dev fixture (source 1e05c63907b7bf70f1dc2a73ef5591a03b8e0021).
 * Real constructor, native ticks/checks and public operationStatus; detached registries, no game started.
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
        initializeNativeStatusMaterials();
        Class<?> type = Class.forName("com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter");
        // Detached fixture factory only. Runtime production access remains public operationStatus.
        Constructor<?> ctor = type.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        HologramOreCenterStallInvestigation.prototype(ctor);
        System.out.println(
            "FIXTURE_SCOPE GTSR=2.1.90 source=1e05c63907b7bf70f1dc2a73ef5591a03b8e0021" + " sourceJar="
                + type.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                + " nativeChecks=true forcedFormedFlag=false game=false");
        if (args.length == 1 && args[0].equals("gapped")) {
            verify(ctor, 1, 0b101101, false);
            return;
        }
        if (args.length == 2) {
            int tier = Integer.parseInt(args[0]), sectors = Integer.parseInt(args[1]);
            verify(ctor, tier, (1 << sectors) - 1, true);
            return;
        }
        if (args.length != 0)
            throw new IllegalArgumentException("Expected no arguments, gapped, or tier and sector count");
        for (int tier : new int[] { 1, 4 })
            for (int sectors : new int[] { 1, 5, 6 }) verify(ctor, tier, (1 << sectors) - 1, true);
        verify(ctor, 1, 0b101101, false);
        System.out.println("CENTER_PUBLIC_STATUS_CONTRACTS_PASSED samplesOnly=true jointModLayouts=NOT_COVERED");
    }

    private static void initializeNativeStatusMaterials() throws Exception {
        // Public operationStatus resolves an optional BW booster fluid. As in existing isolated
        // material fixtures, supply its actual mod-owner context while native declarations initialize.
        var loader = cpw.mods.fml.common.Loader.instance();
        var field = cpw.mods.fml.common.Loader.class.getDeclaredField("modController");
        field.setAccessible(true);
        Object previous = field.get(loader);
        var controller = new cpw.mods.fml.common.LoadController(loader);
        var active = cpw.mods.fml.common.LoadController.class.getDeclaredField("activeContainer");
        active.setAccessible(true);
        var metadata = new cpw.mods.fml.common.ModMetadata();
        metadata.modId = "bartworks";
        metadata.name = "BartWorks";
        active.set(controller, new cpw.mods.fml.common.DummyModContainer(metadata));
        field.set(loader, controller);
        try {
            Class.forName("bartworks.system.material.WerkstoffLoader");
        } finally {
            field.set(loader, previous);
        }
    }

    private static HologramNativePreviewWorld geometry(Constructor<?> ctor, int tier, int sectors) throws Exception {
        var base = new BaseMetaTileEntity();
        Object machine = HologramOreCenterStallInvestigation.center(ctor, base);
        var initial = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        base.yCoord = 128;
        base.setWorldObj(initial);
        initial.tile = base;
        var result = HologramNativePreviewWorld.open(base);
        base.setWorldObj(result);
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        try {
            RunnableMachineUpdate.setCurrentThreadEnabled(false);
            ItemStack signal = HologramOreCenterStallInvestigation.signal(tier, sectors);
            ((IConstructable) machine).construct(signal, false);
            machine.getClass()
                .getMethod("onPreviewStructureComplete", ItemStack.class)
                .invoke(machine, signal);
        } finally {
            RunnableMachineUpdate.setCurrentThreadEnabled(updates);
        }
        return result;
    }

    private static void formNatively(BaseMetaTileEntity base, int tier, int mask) throws Exception {
        base.disableWorking();
        var machine = (MTEMultiBlockBase) base.getMetaTileEntity();
        var method = machine.getClass()
            .getMethod("operationStatus");
        for (int tick = 1; tick <= 10000; tick++) {
            base.getWorld()
                .getWorldInfo()
                .incrementTotalWorldTime(tick);
            machine.onPostTick(base, tick);
            NBTTagCompound status = (NBTTagCompound) method.invoke(machine);
            if (status.getInteger("Tier") == tier - 1 && status.getInteger("Sectors") == mask
                && status.getBoolean("Formed")
                && !status.getBoolean("CheckingFormation")) {
                if (!machine.checkStructure(true, base) || !machine.mMachine)
                    throw new AssertionError("Public status was not accepted by the actual native formation check");
                System.out.println("CENTER_NATIVE_FORMATION tier=" + tier + " mask=" + mask + " nativeTicks=" + tick);
                return;
            }
        }
        throw new AssertionError(
            "Native center did not establish the requested public formation status: " + method.invoke(machine));
    }

    private static void verify(Constructor<?> ctor, int tier, int mask, boolean prefix) throws Exception {
        HologramNativeProjectionJob.clearBaselines();
        HologramNativePreviewWorld generated;
        BaseMetaTileEntity live;
        if (prefix) {
            generated = geometry(ctor, tier, Integer.bitCount(mask));
            live = (BaseMetaTileEntity) generated.getTileEntity(0, 128, 0);
        } else {
            live = new BaseMetaTileEntity();
            HologramOreCenterStallInvestigation.center(ctor, live);
            var initial = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
            live.yCoord = 128;
            live.setWorldObj(initial);
            initial.tile = live;
            generated = HologramNativePreviewWorld.open(live);
            live.setWorldObj(generated);
            Object shape = Class.forName("com.miaokatze.gtsr.common.machine.orecenter.CenterStructure")
                .getConstructor()
                .newInstance();
            if (!(Boolean) shape.getClass()
                .getMethod("construct", IGregTechTileEntity.class, int.class, int.class, boolean.class)
                .invoke(shape, live, tier - 1, mask, false)) throw new AssertionError("Native gapped geometry failed");
            // Port positions come from the public native preview, not private center fields.
            var template = geometry(ctor, tier, 1);
            for (var cursor = template.currentPlacements(); cursor.hasNext();) {
                var cell = cursor.next();
                if (!(cell.tile() instanceof IGregTechTileEntity)) continue;
                generated.setBlock(cell.x(), cell.y(), cell.z(), cell.block(), cell.meta(), 2);
                TileEntity placed = generated.getTileEntity(cell.x(), cell.y(), cell.z());
                placed.readFromNBT(HologramNativeCapture.capture(cell.tile()));
                placed.setWorldObj(generated);
            }
        }
        Scene scene = HologramResourceWorld.create(() -> new Scene(generated));
        formNatively(live, tier, mask);
        var expected = new LinkedHashSet<HologramNativeTargetPlan.Offset>();
        for (var cursor = scene.currentPlacements(); cursor.hasNext();) {
            var p = cursor.next();
            expected.add(new HologramNativeTargetPlan.Offset(p.x(), p.y() - 128, p.z()));
        }
        var before = HologramNativeCapture.capture(live);
        long content = scene.contentSignature();
        scene.freeze();
        String firstDigest = null;
        NBTTagCompound firstMetrics = null;
        for (int opening = 0; opening < 2; opening++) {
            long start = System.nanoTime();
            try (var site = HologramNativeSitePlan.begin(live, HologramOreCenterStallInvestigation.settings(1, 1))) {
                int slices = 0;
                while (!site.advance(System.nanoTime() + 2_000_000L))
                    if (++slices > 200000) throw new AssertionError("Bounded fixed-layout reader did not finish");
                var found = site.settings();
                if (!prefix) {
                    if (found.getBoolean("siteKnown") || !site.siteCells()
                        .isEmpty()
                        || !site.footprint()
                            .isEmpty())
                        throw new AssertionError("Gapped public status was reduced to a smaller prefix: " + found);
                    System.out.println("PASS_CENTER_PUBLIC_GAP mask=" + mask + " known=false defaultSmallerSite=false");
                    continue;
                }
                if (!found.getBoolean("siteKnown") || found.getInteger("main") != tier
                    || found.getCompoundTag("channels")
                        .getInteger("ore_center_sectors") != Integer.bitCount(mask)
                    || !expected.equals(site.footprint())
                    || site.siteCells()
                        .size() != expected.size())
                    throw new AssertionError(
                        "Public center layout does not cover the actual complete structure: " + found);
                if (found.getInteger("siteMainCandidates") != 0 || found.getInteger("siteSizeSearchCandidates") != 0
                    || found.getInteger("siteFixedParameterConfigurations") != 1
                    || found.hasKey("siteRangeAuditRuns"))
                    throw new AssertionError("Automatic candidate/range search survived the native-read replacement");
                try (var target = HologramNativeTargetPlan
                    .fromSite(32651, ExtendedFacing.DEFAULT, site.siteCells(), found)) {
                    while (!target.advance(System.nanoTime() + 2_000_000L)) {}
                    String digest = target.targetDigest();
                    if (firstDigest != null && !firstDigest.equals(digest))
                        throw new AssertionError("Reopen actual digest changed");
                    firstDigest = digest;
                }
                HologramNativeProjectionJob.rememberSite(live, new ArrayList<>(site.footprint()), found);
                if (firstMetrics == null) firstMetrics = readMetrics(found);
                else if (!firstMetrics.equals(readMetrics(found)))
                    throw new AssertionError("Reopen source read counts changed");
                System.out.println(
                    "PASS_CENTER_PUBLIC_SITE tier=" + tier
                        + " sectors="
                        + Integer.bitCount(mask)
                        + " opening="
                        + opening
                        + " members="
                        + site.siteCells()
                            .size()
                        + " nativeCalls="
                        + found.getLong("siteNativeCalls")
                        + " geometryCalls="
                        + found.getLong("siteGeometryNativeCalls")
                        + " uniqueCells="
                        + found.getLong("siteUniqueCells")
                        + " sourceReads="
                        + found.getLong("siteCellReads")
                        + " fullSourceNbt="
                        + found.getLong("siteTileNbtCaptures")
                        + " elapsedMs="
                        + (System.nanoTime() - start) / 1_000_000.0
                        + " digest="
                        + firstDigest);
            }
        }
        if (!before.equals(HologramNativeCapture.capture(live)) || content != scene.contentSignature())
            throw new AssertionError("Fixed public-status reading modified source controller or blocks");
        if (prefix && Integer.bitCount(mask) < 6)
            verifyNeighbors(ctor, scene, live, expected, firstDigest, firstMetrics, tier);
        if (prefix) verifyConflict(scene, live, expected);
        HologramNativeProjectionJob.clearBaselines();
    }

    private static void verifyNeighbors(Constructor<?> ctor, Scene scene, BaseMetaTileEntity live,
        LinkedHashSet<HologramNativeTargetPlan.Offset> expected, String digest, NBTTagCompound metrics, int tier)
        throws Exception {
        var oldHypothetical = geometry(ctor, 4, 6);
        HologramNativeTargetPlan.Offset outside = null;
        for (var cursor = oldHypothetical.currentPlacements(); cursor.hasNext();) {
            var p = cursor.next();
            var at = new HologramNativeTargetPlan.Offset(p.x(), p.y() - 128, p.z());
            if (!expected.contains(at) && scene.isAirBlock(p.x(), p.y(), p.z())) {
                outside = at;
                break;
            }
        }
        if (outside == null) throw new AssertionError("No non-overlapping old hypothetical position found");
        for (int kind = 0; kind < 2; kind++) {
            scene.edit(true);
            scene.setBlock(
                outside.x(),
                128 + outside.y(),
                outside.z(),
                kind == 0 ? Blocks.diamond_block : GregTechAPI.sBlockMachines,
                0,
                2);
            TileEntity neighbor = scene.getTileEntity(outside.x(), 128 + outside.y(), outside.z());
            if (neighbor instanceof BaseMetaTileEntity base) base.setInitialValuesAsNBT(null, (short) 32651);
            NBTTagCompound neighborBefore = neighbor == null ? null : HologramNativeCapture.capture(neighbor);
            scene.freeze();
            var forbid = new HologramNativeCapture.Adapter() {

                public boolean supports(TileEntity tile) {
                    return tile == neighbor;
                }

                public NBTTagCompound capture(TileEntity tile) {
                    throw new AssertionError("Unrelated center neighbor serialized");
                }
            };
            HologramNativeCapture.register(forbid);
            try (var site = HologramNativeSitePlan.begin(live, HologramOreCenterStallInvestigation.settings(1, 1))) {
                while (!site.advance(System.nanoTime() + 2_000_000L)) {}
                if (!site.settings()
                    .getBoolean("siteKnown") || !expected.equals(site.footprint()))
                    throw new AssertionError("An unrelated object contaminated the publicly known source extent");
                if (!metrics.equals(readMetrics(site.settings())))
                    throw new AssertionError("Neighbor changed source read counts");
                var field = HologramNativeSitePlan.class.getDeclaredField("scene");
                field.setAccessible(true);
                if (((Map<?, ?>) field.get(site)).containsKey(
                    com.gtnewhorizon.gtnhlib.util.CoordinatePacker.pack(outside.x(), 128 + outside.y(), outside.z())))
                    throw new AssertionError("An unrelated object entered the source readset");
                try (var target = HologramNativeTargetPlan
                    .fromSite(32651, ExtendedFacing.DEFAULT, site.siteCells(), site.settings())) {
                    while (!target.advance(System.nanoTime() + 2_000_000L)) {}
                    if (!digest.equals(target.targetDigest()))
                        throw new AssertionError("An unrelated object changed the source digest");
                }
            } finally {
                HologramNativeCapture.unregister(forbid);
            }
            if (neighbor != null && !neighborBefore.equals(HologramNativeCapture.capture(neighbor)))
                throw new AssertionError("Neighbor controller native data changed");
            scene.edit(true);
            scene.setBlock(outside.x(), 128 + outside.y(), outside.z(), Blocks.air, 0, 2);
            scene.freeze();
            System.out.println(
                "PASS_CENTER_PUBLIC_NEIGHBOR kind=" + kind
                    + " outside="
                    + outside
                    + " sourceDigest="
                    + digest
                    + " neighborSerialized=false sourceTier="
                    + tier);
        }
    }

    private static NBTTagCompound readMetrics(NBTTagCompound found) {
        var result = new NBTTagCompound();
        for (String name : new String[] { "siteNativeCalls", "siteNativeCaptureCalls", "siteGeometryNativeCalls",
            "siteUniqueCells", "siteCellReads", "siteTileNbtCaptures" }) result.setLong(name, found.getLong(name));
        return result;
    }

    private static void verifyConflict(Scene scene, BaseMetaTileEntity live,
        LinkedHashSet<HologramNativeTargetPlan.Offset> expected) throws Exception {
        HologramNativeTargetPlan.Offset selected = null;
        Block original = null;
        int meta = 0;
        for (var at : expected) {
            int py = 128 + at.y();
            Block block = scene.getBlock(at.x(), py, at.z());
            if (block != Blocks.air && block != Blocks.diamond_block
                && scene.getTileEntity(at.x(), py, at.z()) == null) {
                selected = at;
                original = block;
                meta = scene.getBlockMetadata(at.x(), py, at.z());
                break;
            }
        }
        if (selected == null) throw new AssertionError("No native solid member available for true conflict fixture");
        scene.edit(true);
        scene.setBlock(selected.x(), 128 + selected.y(), selected.z(), Blocks.diamond_block, 0, 2);
        scene.freeze();
        try (var site = HologramNativeSitePlan.begin(live, HologramOreCenterStallInvestigation.settings(1, 1))) {
            while (!site.advance(System.nanoTime() + 2_000_000L)) {}
            if (site.settings()
                .getBoolean("siteKnown")
                || !site.siteCells()
                    .isEmpty()
                || !site.footprint()
                    .isEmpty())
                throw new AssertionError("A true own-member conflict was accepted or reduced to a smaller source");
            System.out.println("PASS_CENTER_PUBLIC_OWN_CONFLICT at=" + selected + " known=false cells=0");
        } finally {
            scene.edit(true);
            scene.setBlock(selected.x(), 128 + selected.y(), selected.z(), original, meta, 2);
            scene.freeze();
        }
    }

    /** Native hatch enumeration reads actual chunk TE maps; the old generation world has no chunks. */
    private static final class Scene extends HologramResourceWorld {

        private final HologramNativePreviewWorld generated;
        private final Map<Long, Chunk> chunks = new LinkedHashMap<>();

        Scene(HologramNativePreviewWorld generated) {
            super(GregTechAPI.sBlockMachines, 0);
            this.generated = generated;
            tile = generated.tile;
            tile.setWorldObj(this);
            for (var cursor = generated.currentPlacements(); cursor.hasNext();) {
                var p = cursor.next();
                if (p.tile() != null) {
                    p.tile()
                        .setWorldObj(this);
                    getChunkFromChunkCoords(p.x() >> 4, p.z() >> 4).chunkTileEntityMap
                        .put(new ChunkPosition(p.x() & 15, p.y(), p.z() & 15), p.tile());
                }
            }
            getChunkFromChunkCoords(tile.xCoord >> 4, tile.zCoord >> 4).chunkTileEntityMap
                .put(new ChunkPosition(tile.xCoord & 15, tile.yCoord, tile.zCoord & 15), tile);
        }

        @Override
        public Chunk getChunkFromChunkCoords(int cx, int cz) {
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            return chunks.computeIfAbsent(key, ignored -> new Chunk(this, cx, cz));
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return generated.getBlock(x, y, z);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return generated.getBlockMetadata(x, y, z);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return generated.getTileEntity(x, y, z);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return generated.blockExists(x, y, z);
        }

        @Override
        boolean previewChunkExists(int x, int z) {
            return true;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            boolean result = generated.setBlock(x, y, z, block, meta, flags);
            TileEntity placed = generated.getTileEntity(x, y, z);
            var chunk = getChunkFromChunkCoords(x >> 4, z >> 4);
            var at = new ChunkPosition(x & 15, y, z & 15);
            if (placed == null) chunk.chunkTileEntityMap.remove(at);
            else {
                placed.setWorldObj(this);
                chunk.chunkTileEntityMap.put(at, placed);
            }
            return result;
        }

        boolean edit(boolean value) {
            return generated.edit(value);
        }

        void freeze() {
            generated.freeze();
        }

        long contentSignature() {
            return generated.contentSignature();
        }

        java.util.Iterator<HologramNativePreviewWorld.Placement> currentPlacements() {
            return generated.currentPlacements();
        }
    }
}
