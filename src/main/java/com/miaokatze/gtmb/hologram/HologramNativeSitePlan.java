// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IChannelDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.threads.RunnableMachineUpdate;

/** One server-owned native site read. Candidate checks never build a target or touch live tile state. */
final class HologramNativeSitePlan implements AutoCloseable {

    private static final ThreadLocal<HologramNativeSitePlan> ACTIVE = new ThreadLocal<>();

    private static final class Different extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Different() {
            super(null, null, false, false);
        }
    }

    private final BaseMetaTileEntity live;
    private final ExtendedFacing facing;
    private final NBTTagCompound settings;
    private final NBTTagCompound controllerConfiguration = new NBTTagCompound();
    private final List<HologramNativeTargetPlan.Offset> provedHistory;
    private final int provedTargetSize;
    private final boolean nativeConfigurationChanged;
    private Set<HologramNativeTargetPlan.Offset> historyIndex;
    private final Map<Long, SceneCell> scene = new LinkedHashMap<>();
    private final Map<String, List<Integer>> domains = new LinkedHashMap<>();
    private final List<String> signals = new ArrayList<>();
    private final Set<IStructureElement<?>> observedElements = Collections
        .newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<String> unresolvedDomains = new LinkedHashSet<>();
    private final Set<HologramNativeTargetPlan.Offset> footprint = new LinkedHashSet<>();
    private int signalIndex, valueIndex, rounds, phase, finalCursor;
    private long nativeCalls, cellReads;
    private int candidateFailures;
    private String lastFailure = "";
    private long winningWeight = -1;
    private int winner;
    private long provisionalWeight = -1;
    private int provisional;
    private long validationReads;
    private boolean matched, closed, unavailable;
    private BaseMetaTileEntity candidate;
    private ISurvivalConstructable constructable;
    private SiteWorld mirror;
    private ISurvivalBuildEnvironment environment;
    private ItemStack trigger;
    private HologramNativeTargetPlan finalPlan;
    private final NBTTagList hatches = new NBTTagList();
    private int nonAir, unknown;
    private java.util.Iterator<Map.Entry<Long, SceneCell>> validation;

    static HologramNativeSitePlan begin(BaseMetaTileEntity live, NBTTagCompound requested) throws IOException {
        return new HologramNativeSitePlan(live, requested);
    }

    private HologramNativeSitePlan(BaseMetaTileEntity live, NBTTagCompound requested) throws IOException {
        if (!(live.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>machine))
            throw new IOException("控制器不支持原生现场读取。");
        this.live = live;
        HologramNativeStructurePreview.install();
        facing = machine.getExtendedFacing();
        settings = (NBTTagCompound) requested.copy();
        HologramNativeConfiguration.capture(live, null, controllerConfiguration);
        settings.setTag("siteControllerConfiguration", controllerConfiguration.copy());
        settings
            .setBoolean("siteControllerCopyRequiresPlayer", machine instanceof gregtech.api.interfaces.IDataCopyable);
        NBTTagCompound confirmed = HologramNativeProjectionJob.provedSettings(live);
        provedHistory = HologramNativeProjectionJob.provedHistory(live);
        provedTargetSize = HologramNativeProjectionJob.provedTargetSize(live);
        nativeConfigurationChanged = HologramNativeProjectionJob.portableConfigurationChanged(live);
        if (confirmed != null) {
            settings.setInteger("main", confirmed.getInteger("main"));
            settings.setTag(
                "channels",
                confirmed.getCompoundTag("channels")
                    .copy());
            settings.setBoolean("siteUsedConfirmedConfiguration", true);
            phase = -1;
        }
        // Authorized fallback: lightweight native SKIP checks over the existing tool/UI signal domain.
        // This never compiles a candidate target and never assumes adjacent values prove saturation.
        List<Integer> main = new ArrayList<>();
        for (int value = 1; value <= 64; value++) main.add(value);
        domains.put("main", main);
        BaseMetaTileEntity fresh = fresh();
        IConstructable source = HologramStructureDiscovery.localConstructable(fresh, fresh.getMetaTileEntity(), facing);
        if (!(source instanceof ISurvivalConstructable)) throw new IOException("控制器没有原生生存建造接口。");
        IStructureDefinition<?> definition = ((MTEEnhancedMultiBlockBase<?>) fresh.getMetaTileEntity())
            .getStructureDefinition();
        if (definition != null) for (IChannelDefinition channel : definition.getChannelDefinitions()) {
            int minimum = channel.getMinimumValue(), maximum = channel.getMaximumValue();
            if (minimum > 0 && maximum >= minimum && (long) maximum - minimum < 64) {
                List<Integer> values = new ArrayList<>();
                for (int value = minimum; value <= maximum; value++) values.add(value);
                domains.put(channel.getChannel(), values);
            } else unresolvedDomains.add(channel.getChannel());
        }
        signals.addAll(domains.keySet());
    }

    private BaseMetaTileEntity fresh() throws IOException {
        BaseMetaTileEntity result = HologramNativeTargetPlan
            .freshController(live.getMetaTileID(), facing, controllerConfiguration);
        result.xCoord = live.xCoord;
        result.yCoord = live.yCoord;
        result.zCoord = live.zCoord;
        return result;
    }

    boolean advance(long deadline) throws IOException {
        if (closed) throw new IOException("原生现场读取已取消。");
        while (System.nanoTime() < deadline) {
            if (phase == -1) {
                if (candidate == null) startCandidate("main", settings.getInteger("main"));
                Boolean accepted = stepCandidate();
                if (accepted == null) continue;
                matched = accepted;
                candidate = null;
                mirror = null;
                environment = null;
                if (accepted) phase = 2;
                else {
                    // A changed site may be another complete native configuration, not damage.
                    // Only a failed full inference may fall back to the remembered restoration scope.
                    settings.setBoolean("siteUsedConfirmedConfiguration", false);
                    settings.setBoolean("siteConfirmedConfigurationRejected", true);
                    phase = 0;
                }
            } else if (phase == 0) {
                if (signalIndex == signals.size()) {
                    phase++;
                    continue;
                }
                String signal = signals.get(signalIndex);
                List<Integer> domain = domains.get(signal);
                if (valueIndex == domain.size()) {
                    int proposed = winner > 0 ? winner : provisional;
                    if (proposed > 0) {
                        if (signal.equals("main")) settings.setInteger("main", proposed);
                        else settings.getCompoundTag("channels")
                            .setInteger(signal, proposed);
                    }
                    valueIndex = 0;
                    signalIndex++;
                    winner = 0;
                    winningWeight = -1;
                    provisional = 0;
                    provisionalWeight = -1;
                    continue;
                }
                if (candidate == null) startCandidate(signal, domain.get(valueIndex));
                Boolean accepted = stepCandidate();
                if (accepted == null) continue;
                if (accepted && mirror.weight() > winningWeight) {
                    winningWeight = mirror.weight();
                    winner = domain.get(valueIndex);
                }
                // A custom constructor may use independent layouts rather than nested sizes. A partial
                // candidate can guide the next signal, but only the final complete native replay proves it.
                if (mirror.weight() > provisionalWeight) {
                    provisionalWeight = mirror.weight();
                    provisional = domain.get(valueIndex);
                }
                candidate = null;
                mirror = null;
                environment = null;
                valueIndex++;
            } else if (phase == 1) {
                if (candidate == null) startCandidate("main", settings.getInteger("main"));
                Boolean accepted = stepCandidate();
                if (accepted == null) continue;
                matched = accepted;
                candidate = null;
                mirror = null;
                environment = null;
                phase++;
            } else if (phase == 2) {
                NBTTagCompound config = new NBTTagCompound();
                config.setBoolean("footprintOnly", true);
                config.setTag("siteControllerConfiguration", controllerConfiguration.copy());
                finalPlan = HologramNativeTargetPlan.begin(
                    live.getMetaTileID(),
                    facing,
                    HologramNativeTargetPlan
                        .canonicalTrigger(settings.getInteger("main"), settings.getCompoundTag("channels")),
                    config);
                phase++;
            } else if (phase == 3) {
                if (!finalPlan.advance(deadline)) return false;
                phase++;
            } else if (phase == 4) {
                List<HologramNativeTargetPlan.Cell> cells = finalPlan.cells();
                if (finalCursor < cells.size()) {
                    var cell = cells.get(finalCursor++);
                    var at = new HologramNativeTargetPlan.Offset(cell.dx(), cell.dy(), cell.dz());
                    footprint.add(at);
                    SceneCell actual;
                    try {
                        actual = read(live.xCoord + at.x(), live.yCoord + at.y(), live.zCoord + at.z());
                    } catch (Different unloaded) {
                        unknown++;
                        continue;
                    }
                    if (actual.block != Blocks.air) nonAir++;
                    if (matched && actual.original instanceof IGregTechTileEntity gt
                        && gt.getMetaTileEntity() instanceof MTEHatch) {
                        if (hatches.tagCount() >= 128) throw new IOException("现场仓室数量超过原生请求上限。");
                        NBTTagCompound row = new NBTTagCompound();
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
                } else {
                    validation = scene.entrySet()
                        .iterator();
                    phase++;
                }
            } else if (phase == 5) {
                if (validation.hasNext()) {
                    Map.Entry<Long, SceneCell> entry = validation.next();
                    int sx = CoordinatePacker.unpackX(entry.getKey());
                    int sy = CoordinatePacker.unpackY(entry.getKey());
                    int sz = CoordinatePacker.unpackZ(entry.getKey());
                    if (!entry.getValue()
                        .current(this, live.getWorldObj(), sx, sy, sz))
                        throw new IOException("原生现场读取期间方块、仓室身份或朝向已变化，请重新读取。");
                    if (entry.getValue().block == Blocks.air) continue;
                    if (sx == live.xCoord && sy == live.yCoord && sz == live.zCoord) continue;
                    // Surviving evidence from a larger failed candidate cannot become a smaller complete site.
                    var offset = new HologramNativeTargetPlan.Offset(
                        sx - live.xCoord,
                        sy - live.yCoord,
                        sz - live.zCoord);
                    if (!footprint.contains(offset)) {
                        boolean historical = false;
                        if (matched && (footprint.size() >= provedTargetSize || nativeConfigurationChanged)
                            && !provedHistory.isEmpty()) {
                            if (historyIndex == null) historyIndex = new java.util.HashSet<>(provedHistory);
                            historical = historyIndex.contains(offset);
                        }
                        if (!historical) unknown++;
                    }
                } else {
                    validation = null;
                    if (!HologramNativeConfiguration.matches(live, null, controllerConfiguration))
                        throw new IOException("现场控制器的原生便携配置已变化，请重新读取。");
                    settings.setTag("nativeHatchChoices", hatches);
                    settings.setBoolean(
                        "siteKnown",
                        (matched || nonAir == 0) && unknown == 0
                            && !unavailable
                            && (nonAir == 0 || unresolvedDomains.isEmpty()));
                    settings.setInteger("siteNonAir", nonAir);
                    settings.setInteger("siteFootprintCells", footprint.size());
                    settings.setInteger("siteUnknown", unknown + (matched || nonAir == 0 ? 0 : 1));
                    settings.setLong("siteNativeCalls", nativeCalls);
                    settings.setLong("siteUniqueCells", cellReads);
                    settings.setLong("siteCellReads", cellReads + validationReads);
                    settings.setInteger("siteCandidateFailures", candidateFailures);
                    if (!settings.getBoolean("siteKnown")) settings.setString("siteLastCandidateFailure", lastFailure);
                    finalPlan.close();
                    finalPlan = null;
                    phase++;
                }
            } else return true;
        }
        return false;
    }

    private void startCandidate(String signal, int value) throws IOException {
        candidate = fresh();
        GregTechAPI.addDummyWorld(SiteWorld.class);
        mirror = HologramResourceWorld.create(() -> new SiteWorld(candidate));
        candidate.setWorldObj(mirror);
        Object context = candidate.getMetaTileEntity();
        constructable = (ISurvivalConstructable) HologramStructureDiscovery
            .localConstructable(candidate, context, facing);
        NBTTagCompound channels = (NBTTagCompound) settings.getCompoundTag("channels")
            .copy();
        int main = settings.getInteger("main");
        if (signal.equals("main")) main = value;
        else channels.setInteger(signal, value);
        trigger = HologramNativeTargetPlan.canonicalTrigger(main, channels);
        environment = ISurvivalBuildEnvironment.create((predicate, simulate, count) -> {
            mirror.placementAttempted = true;
            return Collections.emptyMap();
        }, new HologramProjectorSimulation.Actor(mirror));
        rounds = 0;
    }

    /** A zero-write round is progress only when the native constructor eventually reports completion. */
    private Boolean stepCandidate() throws IOException {
        if (++rounds > 2000) {
            unavailable = true;
            lastFailure = "native cursor did not complete";
            return false;
        }
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        boolean instrument = !StructureLibAPI.isInstrumentEnabled();
        HologramNativeSitePlan previous = ACTIVE.get();
        HologramChannelTrace.Scope trace = HologramChannelTrace.begin(candidate.getMetaTileEntity());
        try {
            RunnableMachineUpdate.setCurrentThreadEnabled(false);
            if (instrument) StructureLibAPI.enableInstrument(this);
            ACTIVE.set(this);
            nativeCalls++;
            int result = constructable.survivalConstruct(trigger.copy(), 4096, environment);
            if (mirror.rejected) return false;
            if (result == -1) return true;
            if (result == 0 && mirror.placementAttempted) return false;
            if (result < -1 || result > 0) return false;
            return null;
        } catch (Different changed) {
            return false;
        } catch (RuntimeException | LinkageError unsupported) {
            // Out-of-domain native values may throw (for example a tier selector beyond its table).
            // They are rejected candidates; they cannot invalidate a separately proved legal result.
            if (phase == 1) unavailable = true;
            candidateFailures++;
            lastFailure = unsupported.getClass()
                .getSimpleName() + ": "
                + unsupported.getMessage();
            return false;
        } finally {
            HologramChannelTrace.Report report = HologramChannelTrace.end(trace);
            for (Map.Entry<String, List<ItemStack>> entry : report.materials.entrySet()) {
                if (domains.containsKey(entry.getKey()) || entry.getValue()
                    .isEmpty()) continue;
                List<Integer> values = new ArrayList<>();
                for (int index = 1; index <= entry.getValue()
                    .size(); index++) values.add(index);
                domains.put(entry.getKey(), values);
                signals.add(entry.getKey());
                unresolvedDomains.remove(entry.getKey());
            }
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            if (instrument) StructureLibAPI.disableInstrument();
            RunnableMachineUpdate.setCurrentThreadEnabled(updates);
        }
    }

    static void visited(StructureElementVisitedEvent event) {
        HologramNativeSitePlan active = ACTIVE.get();
        if (active == null || event.getWorld() != active.mirror) return;
        if (active.observedElements.add(event.getElement()))
            HologramDeclarationWrappers.observeChannels(event.getElement(), active.trigger);
    }

    static void placementAttempt(World world) {
        HologramNativeSitePlan active = ACTIVE.get();
        if (active != null && world == active.mirror) active.mirror.placementAttempted = true;
    }

    private SceneCell read(int x, int y, int z) {
        if (!HologramNativePreviewWorld.packable(x, y, z)) throw new Different();
        long key = CoordinatePacker.pack(x, y, z);
        SceneCell cell = scene.get(key);
        if (cell == null) {
            if (!live.getWorldObj()
                .blockExists(x, y, z)) throw new Different();
            if (scene.size() >= HologramBlueprintLimits.MAX_CELLS) throw new Different();
            cell = new SceneCell(live.getWorldObj(), x, y, z);
            scene.put(key, cell);
            cellReads++;
        }
        return cell;
    }

    private static final class SceneCell {

        final Block block;
        final int meta;
        final TileEntity original;
        final NBTTagCompound data;
        final int machineId, direction;
        final ItemStack item;
        final NBTTagCompound display;

        SceneCell(World world, int x, int y, int z) {
            block = world.getBlock(x, y, z);
            meta = world.getBlockMetadata(x, y, z);
            original = block.hasTileEntity(meta) ? world.getTileEntity(x, y, z) : null;
            machineId = original instanceof IGregTechTileEntity gt ? gt.getMetaTileID() : -1;
            direction = original instanceof IGregTechTileEntity gt ? gt.getFrontFacing()
                .ordinal() : -1;
            item = original instanceof IGregTechTileEntity gt && gt.getMetaTileEntity() instanceof MTEHatch
                ? HologramGtNativeItemState.sample(block, gt)
                : null;
            display = item == null ? null : HologramDisplaySnapshot.projection(original);
            try {
                data = original == null ? null : HologramNativeCapture.capture(original);
            } catch (IOException unsupported) {
                throw new Different();
            }
        }

        boolean current(HologramNativeSitePlan owner, World world, int x, int y, int z) {
            if (!world.blockExists(x, y, z)) return false;
            owner.validationReads++;
            if (world.getBlock(x, y, z) != block || world.getBlockMetadata(x, y, z) != meta) return false;
            if (original == null) return !block.hasTileEntity(meta) || world.getTileEntity(x, y, z) == null;
            if (world.getTileEntity(x, y, z) != original) return false;
            if (!(original instanceof IGregTechTileEntity gt)) return true;
            return gt.getMetaTileID() == machineId && gt.getFrontFacing()
                .ordinal() == direction
                && (item == null || HologramValues.same(item, HologramGtNativeItemState.sample(block, gt)));
        }
    }

    private final class SiteWorld extends HologramResourceWorld {

        private final BaseMetaTileEntity anchor;
        private final Set<Long> touched = new LinkedHashSet<>();
        private final Map<Long, TileEntity> copies = new LinkedHashMap<>();
        private long solid;
        private boolean rejected;
        private boolean placementAttempted;

        SiteWorld(BaseMetaTileEntity anchor) {
            super(GregTechAPI.sBlockMachines, 0);
            this.anchor = anchor;
            tile = anchor;
        }

        private boolean anchor(int x, int y, int z) {
            return x == live.xCoord && y == live.yCoord && z == live.zCoord;
        }

        private SceneCell at(int x, int y, int z) {
            SceneCell result = read(x, y, z);
            if (!anchor(x, y, z) && touched.add(CoordinatePacker.pack(x, y, z)) && result.block != Blocks.air) solid++;
            return result;
        }

        long weight() {
            return solid;
        }

        @Override
        boolean previewChunkExists(int x, int z) {
            return live.getWorldObj()
                .getChunkProvider()
                .chunkExists(x, z);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return live.getWorldObj()
                .blockExists(x, y, z);
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
            SceneCell cell = at(x, y, z);
            if (cell.data == null) return null;
            long key = CoordinatePacker.pack(x, y, z);
            if (!copies.containsKey(key)) {
                TileEntity copy = cell.block.createTileEntity(this, cell.meta);
                if (copy == null || copy == cell.original) throw new Different();
                copy.setWorldObj(this);
                copy.readFromNBT((NBTTagCompound) cell.data.copy());
                copy.setWorldObj(this);
                copies.put(key, copy);
            }
            return copies.get(key);
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            SceneCell cell = at(x, y, z);
            if (cell.block != block || cell.meta != meta) {
                rejected = true;
                throw new Different();
            }
            return true;
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            if (at(x, y, z).meta != meta) {
                rejected = true;
                throw new Different();
            }
            return true;
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            rejected = true;
            throw new Different();
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            rejected = true;
            throw new Different();
        }
    }

    NBTTagCompound settings() {
        return (NBTTagCompound) settings.copy();
    }

    Set<HologramNativeTargetPlan.Offset> footprint() {
        return Collections.unmodifiableSet(footprint);
    }

    @Override
    public void close() {
        closed = true;
        if (finalPlan != null) finalPlan.close();
        finalPlan = null;
        candidate = null;
        mirror = null;
        environment = null;
        scene.clear();
    }
}
