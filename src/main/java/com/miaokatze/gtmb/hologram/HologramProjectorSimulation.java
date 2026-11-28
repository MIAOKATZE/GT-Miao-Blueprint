// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
// Independently implemented against native APIs; stage organization references GTNL
// ea917993f634158bc65945b7a58d69257936712c.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.stats.StatBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.mojang.authlib.GameProfile;

import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.threads.RunnableMachineUpdate;

/** One setup -> build -> probe -> apply -> collect pipeline, shared by native targets and detached previews. */
final class HologramProjectorSimulation implements AutoCloseable {

    @Desugar
    record Position(int x, int y, int z) {}

    private enum Stage {
        BUILD,
        REQUIRED,
        PROBE,
        APPLY,
        COLLECT,
        DONE
    }

    private static final ThreadLocal<HologramProjectorSimulation> ACTIVE = new ThreadLocal<>();
    private static boolean installed;
    TileEntity controller;
    Object context;
    HologramNativePreviewWorld world;
    HologramNativePreviewItems items = new HologramNativePreviewItems();
    final HologramProjectorProbe probe;
    final Map<Position, IStructureElement<?>> elements = new LinkedHashMap<>();
    final Map<Position, ItemStack> signals = new LinkedHashMap<>();
    final Set<Position> optionalWater = new LinkedHashSet<>();
    final NBTTagList description = new NBTTagList();
    final ItemStack trigger;
    final HologramNativeStructurePreview.ControllerFactory factory;
    final ExtendedFacing facing;
    final boolean construction;
    IStructureDefinition<?> definition;
    HologramChannelTrace.Report channels;
    int rounds, nativeResult = Integer.MIN_VALUE;
    private IConstructable constructable;
    private World baseWorld;
    private Stage stage = Stage.BUILD;
    private ItemStack pieceSignal;
    private ItemStack piecePublishedSignal;
    private Actor actor;
    private ISurvivalBuildEnvironment environment;
    private int stableRounds, replayRounds, replayStableRounds;
    private HologramNativePreviewItems replaySource, previousItems;
    private boolean creative, started, instrumented, limitExceeded;
    private NBTTagCompound config = new NBTTagCompound();
    private final Thread owner = Thread.currentThread();

    HologramProjectorSimulation(TileEntity tile, Object context, IConstructable constructable, ItemStack trigger,
        HologramNativeStructurePreview.ControllerFactory factory, ExtendedFacing facing, boolean construction) {
        install();
        this.trigger = trigger.copy();
        this.factory = factory;
        this.facing = facing;
        this.construction = construction;
        setup(tile, context, constructable);
        probe = new HologramProjectorProbe(this);
    }

    static synchronized void install() {
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(new HologramNativeStructurePreview.VisitedListener());
            installed = true;
        }
    }

    void configure(NBTTagCompound settings) {
        config = (NBTTagCompound) settings.copy();
    }

    boolean option(String name) {
        return config.getBoolean(name);
    }

    private void setup(TileEntity tile, Object target, IConstructable nativeConstructable) {
        controller = tile;
        context = target;
        constructable = nativeConstructable;
        baseWorld = tile.getWorldObj();
        world = HologramNativePreviewWorld.open(tile);
        items.bind(world);
        actor = null;
        environment = null;
        pieceSignal = trigger.copy();
        piecePublishedSignal = publishedSignal(pieceSignal);
        tile.setWorldObj(baseWorld);
    }

    Actor actor() {
        if (actor == null) actor = new Actor(world);
        return actor;
    }

    private ISurvivalBuildEnvironment environment() {
        if (environment == null) environment = ISurvivalBuildEnvironment.create(items, actor());
        return environment;
    }

    ItemStack probeSignal(ItemStack original) {
        ItemStack copy = original.copy();
        if (construction || controller instanceof IGregTechTileEntity)
            ChannelDataAccessor.setChannelData(copy, "gt_hatch", 1);
        return copy;
    }

    boolean step(long deadline) throws IOException {
        if (owner != Thread.currentThread() || stage == Stage.DONE)
            throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_SCOPE");
        if (System.nanoTime() >= deadline) return false;
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled(),
            instrument = !StructureLibAPI.isInstrumentEnabled();
        HologramProjectorSimulation previous = ACTIVE.get();
        HologramChannelTrace.Scope trace = HologramChannelTrace.begin(context);
        try {
            if (updates) RunnableMachineUpdate.setCurrentThreadEnabled(false);
            if (instrument) StructureLibAPI.enableInstrument(this);
            instrumented = instrument;
            ACTIVE.set(this);
            controller.setWorldObj(world);
            do {
                switch (stage) {
                    case BUILD -> {
                        if (buildRound()) stage = Stage.PROBE;
                    }
                    case REQUIRED -> {
                        if (probe.fillRequiredSpots(deadline)) {
                            if (probe.requiredFilled()) stableRounds = 0;
                            stage = Stage.BUILD;
                        }
                    }
                    case PROBE -> {
                        if (probe.discover(deadline)) stage = Stage.APPLY;
                    }
                    case APPLY -> {
                        if (probe.apply(config, deadline)) stage = Stage.COLLECT;
                    }
                    case COLLECT -> {
                        collect();
                        stage = Stage.DONE;
                    }
                    default -> {}
                }
                if (limitExceeded) throw new IOException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
            } while (stage != Stage.DONE && System.nanoTime() < deadline);
            return stage == Stage.DONE;
        } finally {
            HologramChannelTrace.Report report = HologramChannelTrace.end(trace);
            if (report.context == context)
                channels = channels == null ? report : HologramChannelTrace.merge(channels, report);
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            if (instrument) StructureLibAPI.disableInstrument();
            if (RunnableMachineUpdate.isCurrentThreadEnabled() != updates)
                RunnableMachineUpdate.setCurrentThreadEnabled(updates);
            controller.setWorldObj(baseWorld);
        }
    }

    private boolean buildRound() throws IOException {
        if (++rounds > 2000) throw new IOException("LOCAL_NATIVE_PREVIEW_UNSTABLE");
        if (!started) {
            started = true;
            if (!construction && controller instanceof IGregTechTileEntity
                && context instanceof INEIPreviewModifier modifier) modifier.onPreviewConstruct(trigger.copy());
        }
        if (!creative && constructable instanceof ISurvivalConstructable survival) {
            long before = world.contentSignature();
            world.takeChanged();
            nativeResult = survival.survivalConstruct(probeSignal(trigger), 4096, environment());
            boolean changed = world.takeChanged();
            if (construction && nativeResult == 0 && !changed) stage = Stage.REQUIRED;
            if (!construction && controller instanceof IGregTechTileEntity
                && context instanceof INEIPreviewModifier
                && !changed) {
                ((INEIPreviewModifier) context).onPreviewStructureComplete(trigger.copy());
                return true;
            }
            if (nativeResult == -1) return true;
            if (nativeResult == -2) {
                if (factory == null || facing == null) throw new IOException("LOCAL_NATIVE_PREVIEW_INCOMPLETE");
                TileEntity fresh = factory.create();
                Object target = HologramStructureDiscovery.context(fresh);
                IConstructable full = HologramStructureDiscovery.localConstructable(fresh, target, facing);
                if (fresh == controller || target == context || full == null)
                    throw new IOException("LOCAL_NATIVE_PREVIEW_COMPLETION_SCOPE");
                controller.setWorldObj(baseWorld);
                elements.clear();
                signals.clear();
                optionalWater.clear();
                setup(fresh, target, full);
                controller.setWorldObj(world);
                creative = true;
                constructable.construct(trigger.copy(), false);
                return true;
            }
            if (nativeResult < -2) throw new IOException("LOCAL_NATIVE_PREVIEW_RESULT:" + nativeResult);
            // Some custom constructors advance an internal cursor across a few empty rounds. Allow
            // those, but never spin 2000 times on an impossible element or a self-removing block.
            stableRounds = world.contentSignature() != before ? 0 : stableRounds + 1;
            if (stableRounds >= 16) throw new IOException("LOCAL_NATIVE_PREVIEW_NO_PROGRESS");
            return false;
        }
        constructable.construct(trigger.copy(), false);
        if (!construction && controller instanceof IGregTechTileEntity
            && context instanceof INEIPreviewModifier modifier) modifier.onPreviewStructureComplete(trigger.copy());
        return true;
    }

    void beginReplay(HologramNativePreviewItems source) {
        if (replaySource != null) throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_REPLAY_ACTIVE");
        previousItems = items;
        replaySource = source;
        items = source;
        items.bind(world);
        environment = null;
        replayRounds = replayStableRounds = 0;
        if (context instanceof MTEMultiBlockBase machine) {
            machine.clearHatches();
            machine.mMachine = false;
        }
    }

    boolean replayStep() throws IOException {
        boolean done = false;
        try {
            if (++replayRounds > 2000) throw new IOException("LOCAL_NATIVE_PREVIEW_UNSTABLE");
            if (constructable instanceof ISurvivalConstructable survival && !creative) {
                long before = world.contentSignature();
                world.takeChanged();
                int result = survival.survivalConstruct(trigger.copy(), 4096, environment());
                world.takeChanged();
                if (result == -1) return done = true;
                if (result == -2) {
                    constructable.construct(trigger.copy(), false);
                    return done = true;
                }
                if (result < -2) throw new IOException("LOCAL_NATIVE_PREVIEW_RESULT:" + result);
                replayStableRounds = world.contentSignature() != before ? 0 : replayStableRounds + 1;
                if (replayStableRounds >= 16) throw new IOException("LOCAL_NATIVE_PREVIEW_NO_PROGRESS");
                return false;
            }
            constructable.construct(trigger.copy(), false);
            return done = true;
        } finally {
            if (done) endReplay();
        }
    }

    private void endReplay() {
        if (replaySource == null) return;
        items = previousItems;
        previousItems = null;
        replaySource = null;
        environment = null;
    }

    private void collect() throws IOException {
        try {
            IStructureDefinition<?> supplied = constructable.getStructureDefinition();
            if (supplied != null) definition = supplied;
        } catch (RuntimeException | LinkageError ignored) {}
        try {
            String[] lines = constructable.getStructureDescription(trigger.copy());
            if (lines != null) for (int n = 0; n < Math.min(32, lines.length); n++) if (lines[n] != null)
                description.appendTag(new NBTTagString(lines[n].substring(0, Math.min(512, lines[n].length()))));
        } catch (RuntimeException | LinkageError ignored) {}
        if (world.recordedPlacementCount() == 0 && elements.isEmpty())
            throw new IOException("LOCAL_NATIVE_PREVIEW_EMPTY");
    }

    boolean checkStructure() {
        if (!(context instanceof MTEMultiBlockBase machine) || !(controller instanceof IGregTechTileEntity tile))
            return false;
        World old = controller.getWorldObj();
        controller.setWorldObj(world);
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        try {
            if (updates) RunnableMachineUpdate.setCurrentThreadEnabled(false);
            machine.clearHatches();
            return machine.checkStructure(true, tile);
        } finally {
            if (RunnableMachineUpdate.isCurrentThreadEnabled() != updates)
                RunnableMachineUpdate.setCurrentThreadEnabled(updates);
            controller.setWorldObj(old);
        }
    }

    ItemStack signalAt(Position p) {
        return signals.getOrDefault(p, trigger)
            .copy();
    }

    private ItemStack publishedSignal(ItemStack signal) {
        ItemStack copy = signal.copy();
        if (construction) {
            NBTTagCompound channels = trigger.hasTagCompound() ? trigger.getTagCompound()
                .getCompoundTag("channels") : new NBTTagCompound();
            if (channels.hasKey("gt_hatch", 3))
                ChannelDataAccessor.setChannelData(copy, "gt_hatch", channels.getInteger("gt_hatch"));
            else ChannelDataAccessor.unsetChannelData(copy, "gt_hatch");
        }
        return copy;
    }

    static void piece(IStructureDefinition<?> definition, Object target, ItemStack signal, World world) {
        HologramProjectorSimulation simulation = ACTIVE.get();
        if (simulation != null && simulation.context == target && (world == null || simulation.world == world)) {
            if (definition != null) simulation.definition = definition;
            if (signal != null) {
                simulation.pieceSignal = signal.copy();
                simulation.piecePublishedSignal = simulation.publishedSignal(signal);
            }
        }
    }

    static boolean consumesHint(World world) {
        HologramProjectorSimulation active = ACTIVE.get();
        return active != null && active.world == world;
    }

    static void visited(StructureElementVisitedEvent event) {
        HologramProjectorSimulation active = ACTIVE.get();
        if (active == null || active.world != event.getWorld()
            || active.owner != Thread.currentThread()
            || active.instrumented && event.getInstrumentIdentifier() != active) return;
        Position p = new Position(event.getX(), event.getY(), event.getZ());
        if (active.elements.size() >= HologramBlueprintLimits.MAX_CELLS && !active.elements.containsKey(p)) {
            active.limitExceeded = true;
            return;
        }
        active.elements.put(p, event.getElement());
        // Signals are owned immutable declarations; readers receive copies via signalAt. A large
        // repeated native walk must not allocate one compound/NBT tree per visit and round.
        active.signals.put(p, active.piecePublishedSignal);
        active.items.position(
            HologramNativeTargetPlan.position(
                p.x() - active.controller.xCoord,
                p.y() - active.controller.yCoord,
                p.z() - active.controller.zCoord),
            active.probe.casingAt(p),
            active.probe.hatchSlot(p));
    }

    @Override
    public void close() {
        endReplay();
        controller.setWorldObj(baseWorld);
        actor = null;
        environment = null;
    }

    static final class Actor extends EntityPlayer {

        Actor(World world) {
            super(world, new GameProfile(UUID.fromString("1e62d1da-8b3b-45ec-8dd4-350a8616544b"), "[GTMB Projector]"));
        }

        @Override
        public void addChatMessage(IChatComponent message) {}

        @Override
        public void addStat(StatBase stat, int amount) {}

        @Override
        public void openGui(Object mod, int id, World world, int x, int y, int z) {}

        @Override
        public void travelToDimension(int dimension) {}

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return false;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }
    }
}
