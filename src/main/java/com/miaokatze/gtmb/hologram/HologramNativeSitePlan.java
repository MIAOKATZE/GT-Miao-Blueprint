// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.threads.RunnableMachineUpdate;

/** One native membership capture, or one publicly identified center layout. Never searches tool parameters. */
final class HologramNativeSitePlan implements AutoCloseable {

    private static final ThreadLocal<HologramNativeSitePlan> ACTIVE = new ThreadLocal<>();

    private final BaseMetaTileEntity live;
    private final World source;
    private final MTEEnhancedMultiBlockBase<?> machine;
    private final ExtendedFacing facing;
    private final NBTTagCompound settings;
    private final NBTTagCompound controllerConfiguration = new NBTTagCompound();
    private final Map<HologramNativeTargetPlan.Offset, IStructureElement<?>> members = new LinkedHashMap<>();
    private final Set<HologramNativeTargetPlan.Offset> footprint = new LinkedHashSet<>();
    private final Map<Long, SceneCell> scene = new LinkedHashMap<>();
    private final List<HologramNativeTargetPlan.Cell> cells = new ArrayList<>();
    private final NBTTagList hatches = new NBTTagList();
    private final HologramLocalControllerSite materialEvidence = new HologramLocalControllerSite();
    private final Map<IStructureElement<?>, Boolean> optionalWater = new IdentityHashMap<>();
    private List<HologramNativeTargetPlan.Cell> frozenCells = Collections.emptyList();
    private Iterator<Map.Entry<HologramNativeTargetPlan.Offset, IStructureElement<?>>> memberCursor;
    private Iterator<Map.Entry<Long, SceneCell>> validation;
    private HologramOreCenterSiteAccess.State center;
    private HologramNativeStructurePreview geometry;
    private Iterator<HologramNativePreviewWorld.Placement> placements;
    private Iterator<HologramProjectorSimulation.Position> visited;
    private BaseMetaTileEntity candidate;
    private SiteWorld mirror;
    private ISurvivalConstructable constructable;
    private ISurvivalBuildEnvironment environment;
    private ItemStack trigger;
    private int phase, nativeRounds, nonAir;
    private long cellReads, validationReads, tileNbtCaptures, nativeCalls, captureCalls;
    private long captureNanos, geometryNanos, acceptanceNanos, geometryCalls;
    private boolean closed, complete, accepted;
    private String failure = "";

    static HologramNativeSitePlan begin(BaseMetaTileEntity live, NBTTagCompound requested) throws IOException {
        return new HologramNativeSitePlan(live, requested);
    }

    private HologramNativeSitePlan(BaseMetaTileEntity live, NBTTagCompound requested) throws IOException {
        if (live == null || !(live.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>found))
            throw new IOException("控制器不支持原生成员读取。");
        this.live = live;
        machine = found;
        source = live.getWorldObj();
        facing = found.getExtendedFacing();
        settings = (NBTTagCompound) requested.copy();
        settings.removeTag("siteActualBlocks");
        settings.removeTag("nativeHatchChoices");
        settings.setBoolean("siteUsedConfirmedConfiguration", false);
        settings.setBoolean("siteMainRecovered", false);
        settings.setBoolean("siteInheritedParameters", true);
    }

    boolean advance(long deadline) throws IOException {
        if (closed) throw new IOException("原生现场读取已取消。");
        while (!complete && System.nanoTime() < deadline) {
            requireIdentity();
            try {
                if (phase == 0) {
                    if (!machine.mMachine) return fail("NATIVE_SITE_UNFORMED");
                    if (HologramOreCenterSiteAccess.supports(machine)) {
                        center = HologramOreCenterSiteAccess.read(live);
                        settings.setInteger("main", center.main());
                        var channels = new NBTTagCompound();
                        channels.setInteger("ore_center_sectors", center.sectorCount());
                        settings.setTag("channels", channels);
                        settings.setBoolean("siteMainRecovered", true);
                        settings.setBoolean("siteInheritedParameters", false);
                        settings.setInteger("siteNativeSectorMask", center.mask());
                        captureConfiguration();
                        startGeometry();
                        phase = 10;
                    } else {
                        long start = System.nanoTime();
                        HologramNativeSiteCapture.Result captured;
                        try {
                            captureCalls++;
                            captured = HologramNativeSiteCapture.capture(live);
                        } finally {
                            captureNanos += System.nanoTime() - start;
                        }
                        if (!captured.complete() || !machine.mMachine) return fail(
                            captured.failure()
                                .isEmpty() ? "NATIVE_SITE_MEMBERS_UNAVAILABLE" : captured.failure());
                        for (var entry : captured.elements()
                            .entrySet()) addMember(entry.getKey(), entry.getValue());
                        if (members.isEmpty()) return fail("NATIVE_SITE_NO_MEMBERS");
                        captureConfiguration();
                        trigger = HologramNativeTargetPlan
                            .canonicalTrigger(settings.getInteger("main"), settings.getCompoundTag("channels"));
                        accepted = true;
                        memberCursor = members.entrySet()
                            .iterator();
                        phase = 1;
                    }
                } else if (phase == 10) {
                    long start = System.nanoTime();
                    boolean done;
                    try {
                        done = geometry.advance(deadline);
                    } finally {
                        geometryNanos += System.nanoTime() - start;
                    }
                    if (!done) return false;
                    geometryCalls = geometry.passes();
                    placements = geometry.placements();
                    visited = geometry.visitedPositions();
                    phase = 11;
                } else if (phase == 11) {
                    while (placements.hasNext() && System.nanoTime() < deadline) {
                        var p = placements.next();
                        addMember(
                            offset(p.x(), p.y(), p.z()),
                            geometry.targetWorld()
                                .hasPlacement(p.x(), p.y(), p.z()) ? geometry.elementAt(p.x(), p.y(), p.z()) : null);
                    }
                    if (placements.hasNext()) return false;
                    while (visited.hasNext() && System.nanoTime() < deadline) {
                        var p = visited.next();
                        addMember(offset(p.x(), p.y(), p.z()), geometry.elementAt(p.x(), p.y(), p.z()));
                    }
                    if (visited.hasNext()) return false;
                    geometry.close();
                    geometry = null;
                    placements = null;
                    visited = null;
                    if (members.isEmpty()) return fail("NATIVE_SITE_CENTER_EMPTY_LAYOUT");
                    startAcceptance();
                    phase = 12;
                } else if (phase == 12) {
                    if (!advanceAcceptance(deadline)) return false;
                    if (!accepted) return fail("NATIVE_SITE_CENTER_LAYOUT_REJECTED");
                    memberCursor = members.entrySet()
                        .iterator();
                    phase = 1;
                } else if (phase == 1) {
                    while (memberCursor.hasNext() && System.nanoTime() < deadline) {
                        var member = memberCursor.next();
                        var at = member.getKey();
                        int x = live.xCoord + at.x(), y = live.yCoord + at.y(), z = live.zCoord + at.z();
                        if (center != null && !mirror.touched.contains(CoordinatePacker.pack(x, y, z)))
                            return fail("NATIVE_SITE_CENTER_UNPROVED_MEMBER");
                        SceneCell actual = read(x, y, z);
                        collect(at, member.getValue(), actual);
                    }
                    if (memberCursor.hasNext()) return false;
                    memberCursor = null;
                    validation = scene.entrySet()
                        .iterator();
                    phase = 2;
                } else if (phase == 2) {
                    while (validation.hasNext() && System.nanoTime() < deadline) {
                        var entry = validation.next();
                        long key = entry.getKey();
                        if (!entry.getValue()
                            .current(
                                CoordinatePacker.unpackX(key),
                                CoordinatePacker.unpackY(key),
                                CoordinatePacker.unpackZ(key)))
                            return fail("NATIVE_SITE_READSET_CHANGED");
                    }
                    if (validation.hasNext()) return false;
                    validation = null;
                    if (!machine.mMachine || !HologramNativeConfiguration.matches(live, null, controllerConfiguration))
                        return fail("NATIVE_SITE_CONTROLLER_CHANGED");
                    if (center != null && !center.equals(HologramOreCenterSiteAccess.read(live)))
                        return fail("NATIVE_SITE_CENTER_STATUS_CHANGED");
                    materialEvidence.publish(settings);
                    if (center == null) settings.setTag(
                        "channels",
                        settings.getCompoundTag("siteChannels")
                            .copy());
                    settings.setTag("nativeHatchChoices", hatches);
                    settings.setBoolean("siteKnown", accepted);
                    settings.setInteger("siteUnknown", 0);
                    settings.setInteger("siteNonAir", nonAir);
                    settings.setInteger("siteFootprintCells", footprint.size());
                    frozenCells = Collections.unmodifiableList(new ArrayList<>(cells));
                    complete = true;
                    publishMetrics();
                } else throw new IOException("原生现场读取阶段无效。");
            } catch (IOException | RuntimeException | LinkageError unsupported) {
                return fail(
                    unsupported.getMessage() == null ? unsupported.getClass()
                        .getSimpleName() : unsupported.getMessage());
            }
        }
        return complete;
    }

    private void captureConfiguration() throws IOException {
        HologramNativeConfiguration.capture(live, null, controllerConfiguration);
        settings.setTag("siteControllerConfiguration", controllerConfiguration.copy());
        settings
            .setBoolean("siteControllerCopyRequiresPlayer", machine instanceof gregtech.api.interfaces.IDataCopyable);
    }

    private BaseMetaTileEntity fresh() throws IOException {
        var fresh = HologramNativeTargetPlan.freshController(live.getMetaTileID(), facing, controllerConfiguration);
        fresh.xCoord = live.xCoord;
        fresh.yCoord = live.yCoord;
        fresh.zCoord = live.zCoord;
        return fresh;
    }

    private void startGeometry() throws IOException {
        candidate = fresh();
        Object context = candidate.getMetaTileEntity();
        IConstructable nativeConstructable = HologramStructureDiscovery.localConstructable(candidate, context, facing);
        trigger = HologramNativeTargetPlan
            .canonicalTrigger(settings.getInteger("main"), settings.getCompoundTag("channels"));
        geometry = new HologramNativeStructurePreview(
            candidate,
            context,
            nativeConstructable,
            false,
            trigger,
            this::fresh,
            facing,
            false);
        var config = new NBTTagCompound();
        config.setBoolean("footprintOnly", true);
        config.setBoolean("rangeOnly", true);
        geometry.configure(config);
    }

    private void addMember(HologramNativeTargetPlan.Offset at, IStructureElement<?> element) throws IOException {
        if (at.x() == 0 && at.y() == 0 && at.z() == 0) return;
        if (members.size() >= HologramBlueprintLimits.MAX_CELLS && !members.containsKey(at))
            throw new IOException("NATIVE_SITE_MEMBER_LIMIT");
        members.putIfAbsent(at, element);
    }

    private HologramNativeTargetPlan.Offset offset(int x, int y, int z) {
        return new HologramNativeTargetPlan.Offset(x - live.xCoord, y - live.yCoord, z - live.zCoord);
    }

    private void startAcceptance() throws IOException {
        candidate = fresh();
        mirror = HologramResourceWorld.create(() -> new SiteWorld(candidate));
        candidate.setWorldObj(mirror);
        IConstructable nativeConstructable = HologramStructureDiscovery
            .localConstructable(candidate, candidate.getMetaTileEntity(), facing);
        if (!(nativeConstructable instanceof ISurvivalConstructable survival))
            throw new IOException("NATIVE_SITE_CENTER_NO_ACCEPTANCE");
        constructable = survival;
        environment = ISurvivalBuildEnvironment.create((predicate, simulate, count) -> {
            mirror.placementAttempted = true;
            return Collections.emptyMap();
        }, new HologramProjectorSimulation.Actor(mirror));
    }

    private boolean advanceAcceptance(long deadline) {
        while (System.nanoTime() < deadline) {
            if (++nativeRounds > 2000) return true;
            boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
            HologramNativeSitePlan previous = ACTIVE.get();
            long start = System.nanoTime();
            try {
                RunnableMachineUpdate.setCurrentThreadEnabled(false);
                ACTIVE.set(this);
                nativeCalls++;
                int result = constructable.survivalConstruct(trigger.copy(), 4096, environment);
                if (mirror.rejected || result < -1 || result > 0 || result == 0 && mirror.placementAttempted)
                    return true;
                if (result == -1) {
                    accepted = true;
                    return true;
                }
            } finally {
                acceptanceNanos += System.nanoTime() - start;
                if (previous == null) ACTIVE.remove();
                else ACTIVE.set(previous);
                RunnableMachineUpdate.setCurrentThreadEnabled(updates);
            }
        }
        return false;
    }

    private SceneCell read(int x, int y, int z) throws IOException {
        if (!HologramNativePreviewWorld.packable(x, y, z) || !source.blockExists(x, y, z))
            throw new IOException("NATIVE_SITE_UNLOADED_MEMBER");
        long key = CoordinatePacker.pack(x, y, z);
        SceneCell found = scene.get(key);
        if (found != null) return found;
        if (scene.size() >= HologramBlueprintLimits.MAX_CELLS) throw new IOException("NATIVE_SITE_READSET_LIMIT");
        found = new SceneCell(x, y, z);
        scene.put(key, found);
        cellReads++;
        return found;
    }

    private void collect(HologramNativeTargetPlan.Offset at, IStructureElement<?> element, SceneCell actual)
        throws IOException {
        boolean requiredAir = actual.block == Blocks.air && HologramElementCatalog.nativeAir(element);
        // Successful optional visits stay in the readset, without granting material/removal targets.
        // Null center declarations retain their already accepted native geometry semantics.
        if (actual.block == Blocks.air && !requiredAir && !(center != null && element == null)) return;
        if (HologramNativeSourceWater.optionalWater(actual.block) && allowsNativeAir(element)) return;
        footprint.add(at);
        if (actual.block != Blocks.air) nonAir++;
        boolean hatch = actual.original instanceof IGregTechTileEntity gt && gt.getMetaTileEntity() instanceof MTEHatch;
        if (hatch) {
            if (hatches.tagCount() >= 128) throw new IOException("NATIVE_SITE_HATCH_LIMIT");
            var row = new NBTTagCompound();
            row.setInteger("x", at.x());
            row.setInteger("y", at.y());
            row.setInteger("z", at.z());
            row.setInteger("id", actual.machineId);
            row.setInteger("facing", actual.direction);
            row.setBoolean("siteNativeAccepted", true);
            row.setTag("item", actual.item.writeToNBT(new NBTTagCompound()));
            row.setTag("display", actual.display.copy());
            hatches.appendTag(row);
        }
        materialEvidence.observe(
            element,
            false,
            actual.block,
            actual.meta,
            actual.item,
            actual.direction,
            actual.block,
            actual.meta,
            actual.item,
            actual.direction,
            false,
            hatch);
        cells.add(
            new HologramNativeTargetPlan.Cell(
                at.x(),
                at.y(),
                at.z(),
                actual.block,
                actual.meta,
                actual.item == null ? null : actual.item.copy(),
                actual.direction,
                actual.display == null ? null : (NBTTagCompound) actual.display.copy(),
                actual.data == null ? null : (NBTTagCompound) actual.data.copy(),
                element,
                trigger,
                requiredAir));
    }

    private boolean allowsNativeAir(IStructureElement<?> element) {
        if (element == null) return false;
        Boolean cached = optionalWater.get(element);
        if (cached != null) return cached;
        var stability = new HologramElementCatalog.AirStability();
        HologramElementCatalog.nativeAir(element, stability); // records dynamic-wrapper cache restrictions
        boolean result = allowsNativeAir(element, new IdentityHashMap<>(), stability, 0);
        if (stability.reusable) optionalWater.put(element, result);
        return result;
    }

    private static boolean allowsNativeAir(IStructureElement<?> element,
        IdentityHashMap<IStructureElement<?>, Boolean> seen, HologramElementCatalog.AirStability stability, int depth) {
        if (element == null || depth > 12 || seen.size() >= 128 || seen.put(element, Boolean.TRUE) != null)
            return false;
        try {
            if (element instanceof IStructureElementChain<?>chain) {
                // Only reviewed native OR alternatives grant authority; never call a foreign fallbacks().
                var children = HologramDeclarationWrappers.nativeChainChildren(chain);
                if (children == null || children.length > 64) return false;
                for (var child : children) if (HologramElementCatalog.nativeAir(child, stability)
                    || allowsNativeAir(child, seen, stability, depth + 1)) return true;
                return false;
            }
            var declaration = HologramDeclarationAdapters.declaration(element);
            if (declaration != null && !declaration.backingAuthority) return false;
            return allowsNativeAir(HologramDeclarationWrappers.backing(element), seen, stability, depth + 1);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) {
            stability.reusable = false;
            return false;
        }
    }

    private void requireIdentity() throws IOException {
        if (closed || live.isInvalid()
            || live.getWorldObj() != source
            || live.getMetaTileEntity() != machine
            || machine.getBaseMetaTileEntity() != live
            || machine.getExtendedFacing() != facing
            || !source.blockExists(live.xCoord, live.yCoord, live.zCoord)
            || source.getTileEntity(live.xCoord, live.yCoord, live.zCoord) != live
            || source.getBlock(live.xCoord, live.yCoord, live.zCoord) != GregTechAPI.sBlockMachines)
            throw new IOException("原生现场控制器、世界或朝向已变化。");
    }

    private boolean fail(String reason) {
        failure = reason == null || reason.isEmpty() ? "NATIVE_SITE_INCOMPLETE" : reason;
        settings.setBoolean("siteKnown", false);
        settings.setInteger("siteUnknown", 1);
        settings.setString("siteLastCandidateFailure", failure);
        cells.clear();
        footprint.clear();
        frozenCells = Collections.emptyList();
        complete = true;
        publishMetrics();
        releaseNative();
        return true;
    }

    private void publishMetrics() {
        settings.setLong("siteNativeCalls", nativeCalls);
        settings.setLong("siteNativeCaptureCalls", captureCalls);
        settings.setLong("siteNativeCaptureNanos", captureNanos);
        settings.setLong("siteGeometryNanos", geometryNanos);
        settings.setLong("siteGeometryNativeCalls", geometryCalls);
        settings.setLong("siteAcceptanceNanos", acceptanceNanos);
        settings.setLong("siteUniqueCells", cellReads);
        settings.setLong("siteCellReads", cellReads + validationReads);
        settings.setLong("siteTileNbtCaptures", tileNbtCaptures);
        settings.setInteger("siteMainCandidates", 0);
        settings.setInteger("siteSizeSearchCandidates", 0);
        settings.setInteger("siteFixedParameterConfigurations", center == null ? 0 : 1);
    }

    // Existing placement instrumentation remains scoped to the one center acceptance mirror.
    static void placementAttempt(World world) {
        var active = ACTIVE.get();
        if (active != null && world == active.mirror) active.mirror.placementAttempted = true;
    }

    private final class SceneCell {

        final Block block;
        final int meta;
        final TileEntity original;
        final int machineId, direction;
        final ItemStack item;
        final NBTTagCompound display;
        private NBTTagCompound data;
        private boolean captured;

        SceneCell(int x, int y, int z) {
            block = source.getBlock(x, y, z);
            meta = source.getBlockMetadata(x, y, z);
            original = block.hasTileEntity(meta) ? source.getTileEntity(x, y, z) : null;
            machineId = original instanceof IGregTechTileEntity gt ? gt.getMetaTileID() : -1;
            direction = original instanceof IGregTechTileEntity gt ? gt.getFrontFacing()
                .ordinal() : -1;
            item = original instanceof IGregTechTileEntity gt ? HologramGtNativeItemState.sample(block, gt)
                : HologramBlockIdentity.materialStack(block, meta);
            display = original instanceof IGregTechTileEntity ? HologramDisplaySnapshot.projection(original) : null;
        }

        NBTTagCompound fullData() throws IOException {
            if (!captured) {
                data = original == null ? null : HologramNativeCapture.capture(original);
                captured = true;
                if (original != null) tileNbtCaptures++;
            }
            return data;
        }

        boolean current(int x, int y, int z) {
            if (!source.blockExists(x, y, z)) return false;
            validationReads++;
            if (source.getBlock(x, y, z) != block || source.getBlockMetadata(x, y, z) != meta) return false;
            if (original == null) return !block.hasTileEntity(meta) || source.getTileEntity(x, y, z) == null;
            if (source.getTileEntity(x, y, z) != original) return false;
            if (!(original instanceof IGregTechTileEntity gt)) return true;
            return gt.getMetaTileID() == machineId && gt.getFrontFacing()
                .ordinal() == direction && HologramValues.same(item, HologramGtNativeItemState.sample(block, gt));
        }
    }

    private final class SiteWorld extends HologramResourceWorld {

        private final BaseMetaTileEntity anchor;
        private final Set<Long> touched = new LinkedHashSet<>();
        private final Map<Long, TileEntity> copies = new LinkedHashMap<>();
        private boolean placementAttempted, rejected;

        SiteWorld(BaseMetaTileEntity anchor) {
            super(GregTechAPI.sBlockMachines, 0);
            this.anchor = anchor;
            tile = anchor;
        }

        private boolean anchor(int x, int y, int z) {
            return x == live.xCoord && y == live.yCoord && z == live.zCoord;
        }

        private SceneCell at(int x, int y, int z) {
            try {
                touched.add(CoordinatePacker.pack(x, y, z));
                return read(x, y, z);
            } catch (IOException unavailable) {
                throw new IllegalStateException(unavailable);
            }
        }

        @Override
        boolean previewChunkExists(int x, int z) {
            return source.getChunkProvider()
                .chunkExists(x, z);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return source.blockExists(x, y, z);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return at(x, y, z).block;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return at(x, y, z).meta;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (anchor(x, y, z)) return anchor;
            SceneCell actual = at(x, y, z);
            if (actual.original == null) return null;
            long key = CoordinatePacker.pack(x, y, z);
            if (!copies.containsKey(key)) {
                try {
                    var data = actual.fullData();
                    TileEntity copy = actual.block.createTileEntity(this, actual.meta);
                    if (copy == null || copy == actual.original) throw new IOException("NATIVE_SITE_TILE_FACTORY");
                    copy.setWorldObj(this);
                    copy.readFromNBT((NBTTagCompound) data.copy());
                    copy.setWorldObj(this);
                    copies.put(key, copy);
                } catch (IOException unsupported) {
                    throw new IllegalStateException(unsupported);
                }
            }
            return copies.get(key);
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            SceneCell actual = at(x, y, z);
            if (actual.block != block || actual.meta != meta) rejected = true;
            return !rejected;
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            rejected |= at(x, y, z).meta != meta;
            return !rejected;
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            rejected = true;
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            rejected = true;
        }
    }

    NBTTagCompound settings() {
        return (NBTTagCompound) settings.copy();
    }

    Set<HologramNativeTargetPlan.Offset> footprint() {
        return Collections.unmodifiableSet(footprint);
    }

    List<HologramNativeTargetPlan.Cell> siteCells() {
        return frozenCells;
    }

    private void releaseNative() {
        if (geometry != null) geometry.close();
        geometry = null;
        placements = null;
        visited = null;
        candidate = null;
        mirror = null;
        environment = null;
        constructable = null;
    }

    @Override
    public void close() {
        closed = true;
        releaseNative();
        memberCursor = null;
        validation = null;
        scene.clear();
        members.clear();
    }
}
