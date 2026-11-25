// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Control;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Key;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Phase;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reason;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reply;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Request;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Variant;
import com.miaokatze.gtmb.hologram.HologramWorkspace.ActionEndpoint;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Actual encoded Forge ingress, shared lifecycle and serving owner, with an isolated world and reply sink. */
public final class HologramProjectedLayerServerRegression {

    private HologramProjectedLayerServerRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramProjectedLayerServerRegression.class, args)) return;
        run();
        System.out.println("projected_layer_authenticated_serving: passed");
    }

    public static void run() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        // This detached suite has no FML mod-discovery phase. Native AE/StructureLib source registries still
        // read Loader's mod map during static initialization; provide the empty discovered-mod set, not a
        // substitute material implementation.
        var names = cpw.mods.fml.common.Loader.class.getDeclaredField("namedMods");
        names.setAccessible(true);
        var loader = cpw.mods.fml.common.Loader.instance();
        Object previousNames = names.get(loader);
        if (previousNames == null) names.set(loader, new HashMap<>());
        net.minecraft.launchwrapper.Launch.classLoader.registerTransformer(
            HologramSourceRegistryFixtureTransformer.class.getName());
        appeng.core.AEConfig previousAeConfig = appeng.core.AEConfig.instance;
        if (previousAeConfig == null) {
            var config = (appeng.core.AEConfig) unsafe.allocateInstance(appeng.core.AEConfig.class);
            Field flags = appeng.core.AEConfig.class.getDeclaredField("featureFlags");
            flags.setAccessible(true);
            flags.set(config, java.util.EnumSet.noneOf(appeng.core.features.AEFeature.class));
            config.grinderOres = new String[0];
            appeng.core.AEConfig.instance = config;
        }
        Path old = HologramConstructionJournal.STORAGE_ROOT.get();
        Path root = Files.createTempDirectory("gtmb-projected-server-");
        HologramConstructionJournal.STORAGE_ROOT.set(root);
        try {
            authenticatedFirstWriteAndReplay(unsafe);
            bufferedLayersRespectServerTickCadence(unsafe);
            unsupportedReviewedProfileCannotStart(unsafe);
            ordinaryMainFirstDoesNotChangeSources(unsafe);
            selectedBackpackPaysOnlyActualPlacements(unsafe);
            reentrantBeginDoesNotAdoptChangedOwner(unsafe);
            rejectedBeginAndControls(unsafe);
            stopOvertakesBeginAndRevokedGateIsNotActive(unsafe);
            stopQueuedDuringFirstPaidPlacement(unsafe, true);
            stopQueuedDuringFirstPaidPlacement(unsafe, false);
            staleEpochAndLateRetirement(unsafe);
        } finally {
            appeng.core.AEConfig.instance = previousAeConfig;
            names.set(loader, previousNames);
            if (old == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(old);
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void unsupportedReviewedProfileCannotStart(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 1)) {
            fixture.login();
            long generation = fixture.generation();
            UUID id = UUID.randomUUID();
            String stone = "minecraft:stone";
            var row = new HologramProjectedVariantPricing.Row(stone, stone, 0, 0, -1, false, "", "", 1);
            Header header = Header.reviewed(id, 0, 64, 0, 0, 64, 0, 0, 64, 0, 1, 1,
                Collections.singletonList(Variant.reviewed(row)));
            byte[] slice = HologramProjectedLayerWire.encodeSlice(
                new Slice(id, 0, 64, new int[] { 0, 0, 0 }));
            byte[] metadataProfile = HologramProjectedLayerWire.encodeReviewedManifest(
                new Manifest(header));
            fixture.approve(id, generation, 1);
            fixture.send(fixture.player, HologramProjectedLayerTransport.begin(id, 1, generation, metadataProfile));
            fixture.nextServerTick();
            Reply response = fixture.last();
            require(response != null && response.phase == Phase.REJECTED && response.acknowledgement() == null
                && fixture.stock() == 1 && fixture.world.writes().isEmpty(),
                "reviewed metadata is never a paid placement permit in the formal plain ingress");
        }
    }

    private static void bufferedLayersRespectServerTickCadence(sun.misc.Unsafe unsafe) throws Exception {
        int baseY = 64, sections = 7;
        try (Fixture fixture = new Fixture(unsafe, sections)) {
            fixture.login();
            long generation = fixture.generation();
            UUID id = UUID.randomUUID();
            String stone = "minecraft:stone";
            Header header = new Header(id, 0, baseY, 0, 0, baseY, 0, 0, baseY + sections - 1, 0,
                sections, sections, Collections.singletonList(new Variant(stone, 0, stone, 0, -1, false, "", "",
                    sections)));
            byte[][] frames = new byte[sections][];
            for (int i = sections - 1; i >= 0; i--) {
                frames[i] = HologramProjectedLayerWire.encodeSlice(
                    new Slice(id, i, baseY + i, new int[] { 0, 0, 0 }));
            }
            fixture.approve(id, generation, 1);
            fixture.send(fixture.player, HologramProjectedLayerTransport.begin(id, 1, generation,
                HologramProjectedLayerWire.encodeManifest(new Manifest(header))));
            fixture.nextServerTick();
            Reply admitted = fixture.last();
            require(admitted.phase == Phase.AWAITING_SLICE && ack(admitted).credit == 1
                && admitted.acceptedSlices == 0 && fixture.stock() == sections && fixture.world.writes().isEmpty(),
                "authorized encoded BEGIN issues only dry admission before any geometry or payment");

            fixture.world.arm();
            for (byte[] frame : frames) fixture.send(fixture.player,
                HologramProjectedLayerTransport.slice(id, 1, generation, frame));

            long firstTick = fixture.nextServerTick();
            Reply firstGroup = fixture.last();
            require(firstGroup.phase == Phase.PLACING && firstGroup.acceptedSlices == sections
                && firstGroup.settledSlices == 6 && firstGroup.placed == 6 && ack(firstGroup).settledCells == 6
                && fixture.stock() == 1 && fixture.world.writes().size() == 6,
                "one server tick accepts all seven encoded frames but pays and writes only the first six layers");
            for (int y = baseY; y < baseY + 6; y++) require(fixture.world.getBlock(0, y, 0) == Blocks.stone,
                "each paid first-group layer exists at its exact coordinate");
            require(fixture.world.getBlock(0, baseY + 6, 0) == Blocks.air, "seventh layer remains unplaced");

            fixture.tick(); // Re-enter the serving owner with the same server START serial.
            require(HologramWorkBudget.serverTickSerial() == firstTick && fixture.world.writes().size() == 6,
                "an additional service advancement in the same tick cannot advance the layer cadence");
            for (int elapsed = 1; elapsed < 10; elapsed++) {
                require(fixture.nextServerTick() == firstTick + elapsed && fixture.stock() == 1
                    && fixture.world.writes().size() == 6 && fixture.world.getBlock(0, baseY + 6, 0) == Blocks.air,
                    "ticks one through nine neither write nor debit the staged seventh layer");
            }

            require(fixture.nextServerTick() == firstTick + 10, "tenth actual server tick reached without sleeping");
            Reply done = fixture.last();
            ActionEndpoint endpoint = HologramWorkspace.captureActionEndpoint(fixture.player.playerNetServerHandler);
            var gate = HologramWorkspace.constructionGate(endpoint);
            var view = gate.query(endpoint.constructionScope,
                new HologramConstructionTaskGate.Request(id, 1, HologramConstructionTaskGate.Kind.PROJECTED_LAYER));
            require(done.phase == Phase.COMPLETE && done.acceptedSlices == sections && done.settledSlices == sections
                && done.placed == sections && done.alreadySatisfied == 0 && ack(done).complete
                && fixture.stock() == 0 && fixture.world.writes().size() == sections
                && fixture.world.getBlock(0, baseY + 6, 0) == Blocks.stone
                && view.committedTransactions == sections && view.outcome.end == HologramConstructionTaskGate.End.COMPLETE,
                "seventh layer pays and writes only at tick ten; terminal receipt agrees with the owned gate");
        }
    }

    private static void authenticatedFirstWriteAndReplay(sun.misc.Unsafe unsafe) throws Exception {
        Plan plan = new Plan();
        try (Fixture fixture = new Fixture(unsafe, 3)) {
            fixture.world.seed(1, 64, 0, Blocks.stone, 0);
            fixture.send(fixture.player, plan.begin(1));
            require(fixture.receipts.isEmpty(), "Netty ingress cannot admit or mint a scope");
            fixture.tick();
            require(fixture.receipts.isEmpty(), "unpublished peer cannot start");
            fixture.login();
            plan.generation = fixture.generation();
            fixture.approve(plan, 1);
            fixture.send(fixture.player, plan.begin(1));
            fixture.tick();
            Reply initial = fixture.last();
            require(initial.phase == Phase.AWAITING_SLICE && initial.acceptedSlices == 0 && initial.placed == 0
                && ack(initial).credit == 1 && fixture.stock() == 3 && fixture.world.writes().isEmpty()
                && HologramWorkspace.find(fixture.player.getUniqueID()) == null,
                "first credit requires actual whole dry stock admission but does not write/charge/install geometry");

            EntityPlayerMP other = createPlayer(unsafe, fixture.world, 3);
            try {
                HologramWorkspace.publishActionEndpoint(other, false);
                long own = plan.generation;
                plan.generation = HologramWorkspace.captureActionEndpoint(other.playerNetServerHandler)
                    .constructionScope.generation;
                fixture.send(other, plan.control(1, 1, Control.STOP)); // Even the attacker's valid epoch grants no owner.
                fixture.send(other, plan.first(1));
                plan.generation = own;
                fixture.send(fixture.player, plan.tail(1));
                fixture.tick();
                require(fixture.stock() == 3 && fixture.world.writes().isEmpty()
                    && fixture.last().phase == Phase.AWAITING_SLICE && fixture.last().acceptedSlices == 0,
                    "foreign stop/slice and uncredited future slice cannot settle or abort the owner");
            } finally {
                HologramWorkspace.revokeActionEndpoint(other);
                other.playerNetServerHandler.netManager.channel().close();
            }

            fixture.world.arm();
            fixture.send(fixture.player, plan.first(1));
            fixture.send(fixture.player, plan.first(1));
            fixture.tick();
            Reply first = fixture.last();
            require(first.phase == Phase.AWAITING_SLICE && first.acceptedSlices == 1 && first.settledSlices == 1
                && first.placed == 1 && first.alreadySatisfied == 1 && first.sequence > initial.sequence
                && fixture.stock() == 2 && fixture.world.getBlock(0, 64, 0) == Blocks.stone
                && fixture.world.getBlock(0, 65, 0) == Blocks.air && fixture.world.writes().size() == 1
                && ack(first).nextSlice == 1 && !ack(first).complete
                && !HologramConstructionJournal.pending(fixture.player),
                "real world+exact stock commit before the final fragment is even sent; satisfy is not pay");

            fixture.send(fixture.player, plan.first(1)); // exact committed fragment replay
            fixture.tick();
            require(fixture.last().sequence == first.sequence && fixture.world.writes().size() == 1,
                "replayed slice returns scalar current credit and never writes twice");
            fixture.send(fixture.player, plan.tail(1));
            fixture.tick();
            Reply done = fixture.last();
            var endpoint = HologramWorkspace.captureActionEndpoint(fixture.player.playerNetServerHandler);
            var gate = HologramWorkspace.constructionGate(endpoint);
            var view = gate.query(endpoint.constructionScope,
                new HologramConstructionTaskGate.Request(plan.id, 1, HologramConstructionTaskGate.Kind.PROJECTED_LAYER));
            require(done.phase == Phase.COMPLETE && done.placed == 2 && done.alreadySatisfied == 1
                && done.sequence > first.sequence && ack(done).complete && fixture.stock() == 1
                && fixture.world.writes().size() == 2 && view.committedTransactions == 2
                && view.outcome.end == HologramConstructionTaskGate.End.COMPLETE,
                "only exact shared gate world/payment outcome publishes committed completion");
            fixture.send(fixture.player, plan.begin(1));
            fixture.send(fixture.player, plan.control(1, 1, Control.STATUS));
            fixture.tick();
            require(fixture.last().sequence == done.sequence && fixture.last().phase == Phase.COMPLETE
                && fixture.stock() == 1 && fixture.world.writes().size() == 2 && fixture.authorized == 1,
                "same-scope replay reports terminal counters without reconstructing geometry");
        }
    }

    private static void ordinaryMainFirstDoesNotChangeSources(sun.misc.Unsafe unsafe) throws Exception {
        for (int priority : new int[] { 0, 2 }) try (Fixture fixture = new Fixture(unsafe, 3)) {
            Plan plan = new Plan();
            fixture.login();
            NBTTagCompound toolProof = new NBTTagCompound();
            toolProof.setString(HologramSessionKeys.TOOL_ID, UUID.randomUUID().toString());
            fixture.player.getHeldItem().setTagCompound(toolProof);
            // Priority zero is the untouched default: no material-settings tag exists on the bound tool.
            if (priority != 0) {
                var normal = new HologramMaterials.Settings();
                normal.priority = priority;
                HologramMaterials.write(fixture.player.getHeldItem(), normal);
            }
            var before = toolProof.copy();
            plan.generation = fixture.generation();
            fixture.approve(plan, 1);
            fixture.send(fixture.player, plan.begin(1));
            fixture.tick();
            require(ack(fixture.last()).credit == 1 && fixture.stock() == 3
                && before.equals(fixture.player.getHeldItem().getTagCompound()),
                "actual effective main-first defaults are admitted only after exact dry main stock, without saving a preference");
            fixture.world.arm();
            fixture.send(fixture.player, plan.first(1));
            fixture.tick();
            require(fixture.last().placed == 2 && fixture.stock() == 1
                && before.equals(fixture.player.getHeldItem().getTagCompound())
                && HologramMaterials.read(fixture.player.getHeldItem()).containers
                && HologramMaterials.read(fixture.player.getHeldItem()).me,
                "actual paid cells use scoped main, and the original container/wireless options remain untouched");
            var edited = HologramMaterials.read(fixture.player.getHeldItem());
            edited.priority = priority == 0 ? 2 : 0; // Still eligible, but it is no longer the same confirmed settings.
            HologramMaterials.write(fixture.player.getHeldItem(), edited);
            fixture.send(fixture.player, plan.tail(1));
            fixture.tick();
            require(fixture.last().phase == Phase.STOPPED && fixture.last().reason == Reason.OWNER_CHANGED
                && fixture.last().placed == 2 && fixture.stock() == 1 && fixture.world.getBlock(0, 65, 0) == Blocks.air
                && fixture.world.writes().size() == 2,
                "a different eligible configuration cannot inherit the paid operation or next credit");
        }
        try (Fixture fixture = new Fixture(unsafe, 3)) {
            Plan first = new Plan();
            fixture.login();
            var normal = new HologramMaterials.Settings();
            normal.priority = 1;
            HologramMaterials.write(fixture.player.getHeldItem(), normal);
            first.generation = fixture.generation();
            fixture.approve(first, 1);
            fixture.send(fixture.player, first.begin(1));
            fixture.tick();
            require(ack(fixture.last()).credit == 1 && fixture.stock() == 3,
                "container-first remains selected and may fall back to enabled main when no matching container exists");
            fixture.world.arm();
            fixture.send(fixture.player, first.first(1));
            fixture.tick();
            require(fixture.last().placed == 2 && fixture.stock() == 1
                && HologramMaterials.read(fixture.player.getHeldItem()).priority == 1,
                "container-first default settings reach actual projected placement without being rewritten");
        }
        try (Fixture fixture = new Fixture(unsafe, 2)) {
            Plan shortfall = new Plan();
            fixture.login();
            var normal = new HologramMaterials.Settings();
            HologramMaterials.write(fixture.player.getHeldItem(), normal);
            shortfall.generation = fixture.generation();
            fixture.approve(shortfall, 1);
            fixture.send(fixture.player, shortfall.begin(1));
            fixture.tick();
            require(fixture.last().phase == Phase.REJECTED && fixture.last().reason == Reason.MISSING_STOCK
                && fixture.last().acknowledgement() == null && fixture.stock() == 2
                && fixture.world.writes().isEmpty() && !HologramProjectedLayerService.active(fixture.player),
                "a selected-source shortfall with no carried provider returns no geometry credit");
        }
    }

    private static void selectedBackpackPaysOnlyActualPlacements(sun.misc.Unsafe unsafe) throws Exception {
        final String bagKey = "gtmbProjectedSourceFixture";
        com.gtnewhorizon.structurelib.util.InventoryUtility.registerStackExtractor(
            "gtmb_projected_source_fixture",
            new com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackExtractor() {
                @Override
                public boolean isAPIImplemented(APIType api) {
                    return api == APIType.MAIN || api == APIType.IS_VALID_SOURCE;
                }

                @Override
                public boolean isValidSource(ItemStack bag, EntityPlayerMP player) {
                    return bag.hasTagCompound() && bag.getTagCompound().hasKey(bagKey);
                }

                @Override
                public int takeFromStack(java.util.function.Predicate<ItemStack> predicate, boolean simulate, int count,
                    com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackCounter counter, ItemStack source,
                    ItemStack exact, EntityPlayerMP player) {
                    ItemStack stone = new ItemStack(Blocks.stone);
                    if (!predicate.test(stone)) return 0;
                    int amount = Math.min(count, source.getTagCompound().getInteger(bagKey));
                    source.getTagCompound().setInteger(bagKey,
                        source.getTagCompound().getInteger(bagKey) - amount);
                    counter.add(stone, amount);
                    return amount;
                }
            });
        for (boolean reject : new boolean[] { false, true }) try (Fixture fixture = new Fixture(unsafe, 0)) {
            fixture.player.inventory.mainInventory[1] = null;
            ItemStack bag = new ItemStack(net.minecraft.init.Items.writable_book);
            bag.setTagCompound(new NBTTagCompound());
            bag.getTagCompound().setInteger(bagKey, 3);
            fixture.player.inventory.mainInventory[2] = bag;
            var selected = new HologramMaterials.Settings();
            selected.main = selected.me = false;
            selected.containers = true;
            selected.priority = 1;
            HologramMaterials.write(fixture.player.getHeldItem(), selected);
            fixture.login();
            Plan plan = new Plan();
            plan.generation = fixture.generation();
            fixture.approve(plan, 1);
            fixture.send(fixture.player, plan.begin(1));
            fixture.tick();
            require(ack(fixture.last()).credit == 1
                && fixture.player.inventory.mainInventory[2].getTagCompound().getInteger(bagKey) == 3,
                "ordinary authenticated BEGIN admits a selected native backpack without main stock and without debit");
            fixture.world.seed(1, 64, 0, Blocks.stone, 0);
            fixture.world.arm();
            Field bus = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object base = unsafe.staticFieldBase(bus);
            long offset = unsafe.staticFieldOffset(bus);
            EventBus prior = MinecraftForge.EVENT_BUS;
            if (reject) unsafe.putObjectVolatile(base, offset, new EventBus() {
                @Override
                public boolean post(Event event) {
                    if (event instanceof BlockEvent.PlaceEvent place && place.player == fixture.player
                        && place.world == fixture.world) {
                        event.setCanceled(true);
                        return true;
                    }
                    return false;
                }
            });
            try {
                fixture.send(fixture.player, plan.first(1));
                fixture.tick();
                if (!reject) {
                    require(fixture.last().placed == 1 && fixture.last().alreadySatisfied == 1
                        && fixture.player.inventory.mainInventory[2].getTagCompound().getInteger(bagKey) == 2,
                        "existing exact cell is free; actual new cell extracts exactly once from selected backpack");
                    fixture.send(fixture.player, plan.first(1));
                    fixture.tick();
                    require(fixture.player.inventory.mainInventory[2].getTagCompound().getInteger(bagKey) == 2,
                        "settled ordinary packet replay cannot debit backpack twice");
                    fixture.send(fixture.player, plan.tail(1));
                    fixture.tick();
                    require(fixture.last().phase == Phase.COMPLETE && fixture.last().placed == 2
                        && fixture.player.inventory.mainInventory[2].getTagCompound().getInteger(bagKey) == 1,
                        "ordinary formal packet path finishes with exactly two successful placement payments");
                } else require(fixture.last().phase == Phase.STOPPED && fixture.last().placed == 0
                    && fixture.world.getBlock(0, 64, 0) == Blocks.air
                    && fixture.player.inventory.mainInventory[2].getTagCompound().getInteger(bagKey) + looseStone(fixture.player) == 3
                    && !HologramConstructionJournal.pending(fixture.player),
                    "Forge-rejected ordinary placement preserves native post-extraction backpack state, returns exact material, and leaves no net debit: phase="
                        + fixture.last().phase + ",placed=" + fixture.last().placed + ",block="
                        + fixture.world.getBlock(0, 64, 0) + ",bag="
                        + fixture.player.inventory.mainInventory[2].getTagCompound().getInteger(bagKey)
                        + ",pending=" + HologramConstructionJournal.pending(fixture.player));
            } finally {
                unsafe.putObjectVolatile(base, offset, prior);
            }
        }
    }

    private static int looseStone(EntityPlayerMP player) {
        int result = 0;
        for (ItemStack stack : player.inventory.mainInventory) if (stack != null
            && stack.isItemEqual(new ItemStack(Blocks.stone)) && !stack.hasTagCompound()) result += stack.stackSize;
        return result;
    }

    private static void reentrantBeginDoesNotAdoptChangedOwner(sun.misc.Unsafe unsafe) throws Exception {
        for (int scenario : new int[] { 0, 1, 2 }) try (Fixture fixture = new Fixture(unsafe, 3)) {
            ReenterTimeWorld changedWorld = scenario == 0 ? null
                : (ReenterTimeWorld) unsafe.allocateInstance(ReenterTimeWorld.class);
            if (changedWorld != null) {
                Field provider = World.class.getDeclaredField("provider");
                provider.setAccessible(true);
                provider.set(changedWorld, new WorldProviderSurface());
                fixture.player.worldObj = changedWorld;
            }
            fixture.login();
            var prior = new HologramSession(fixture.player, 0, 64, 0, false, 1);
            HologramWorkspace.activate(fixture.player.getUniqueID(), prior);
            long epoch = prior.editorWorkspaceEpoch, revision = prior.planRevision;
            int[] calls = { 0 };
            try {
                if (scenario == 0) {
                    InventoryPlayer original = fixture.player.inventory;
                    var observed = new ReenterDirtyInventory(fixture.player);
                    System.arraycopy(original.mainInventory, 0, observed.mainInventory, 0, original.mainInventory.length);
                    observed.currentItem = original.currentItem;
                    fixture.player.inventory = observed;
                    observed.reenter = () -> {
                        calls[0]++;
                        prior.editorWorkspaceEpoch++;
                        prior.planRevision++;
                    };
                } else changedWorld.reenter = () -> {
                    calls[0]++;
                    if (scenario == 1) prior.blueprintDirty = true; // No source revision change, only quiet differs.
                    else fixture.player.posX = 600; // Same world and endpoint, but the locked position is no longer near.
                };
                Plan plan = new Plan();
                plan.generation = fixture.generation();
                fixture.approve(plan, 1);
                fixture.send(fixture.player, plan.begin(1));
                fixture.tick();
                ActionEndpoint endpoint =
                    HologramWorkspace.captureActionEndpoint(fixture.player.playerNetServerHandler);
                require(calls[0] == 1 && HologramWorkspace.find(fixture.player.getUniqueID()) == prior
                    && (scenario == 0 ? prior.editorWorkspaceEpoch == epoch + 1 && prior.planRevision == revision + 1
                        : prior.editorWorkspaceEpoch == epoch && prior.planRevision == revision)
                    && fixture.last().phase == Phase.REJECTED && fixture.last().reason == Reason.ADMISSION_REFUSED
                    && fixture.last().acknowledgement() == null && fixture.stock() == 3
                    && fixture.world.writes().isEmpty() && !HologramProjectedLayerService.active(fixture.player)
                    && !HologramWorkspace.constructionGate(endpoint).busy(fixture.player.getUniqueID()),
                    "actual session callback cannot rebind origin revision, busy workspace, or moved anchor");
            } finally {
                HologramWorkspace.rollbackCompactNative(null, prior);
                HologramEditorWorkspaceRegistry.forget(fixture.player.getUniqueID());
            }
        }
    }

    private static final class ReenterDirtyInventory extends InventoryPlayer {

        Runnable reenter;

        ReenterDirtyInventory(EntityPlayerMP owner) {
            super(owner);
        }

        @Override
        public void markDirty() {
            super.markDirty();
            Runnable callback = reenter;
            reenter = null;
            if (callback != null) callback.run();
        }
    }

    private static final class ReenterTimeWorld extends HologramServiceEditRegression.ReadOnlyWorld {

        Runnable reenter;

        @Override
        public long getTotalWorldTime() {
            if (reenter != null) for (StackTraceElement point : Thread.currentThread().getStackTrace()) {
                if (!HologramSession.class.getName().equals(point.getClassName()) || !"<init>".equals(point.getMethodName()))
                    continue;
                Runnable callback = reenter;
                reenter = null;
                callback.run();
                break;
            }
            return super.getTotalWorldTime();
        }
    }

    private static void rejectedBeginAndControls(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 2)) {
            Plan insufficient = new Plan();
            fixture.login();
            insufficient.generation = fixture.generation();
            fixture.approve(insufficient, 1);
            fixture.send(fixture.player, insufficient.begin(1));
            fixture.tick();
            require(fixture.last().phase == Phase.REJECTED && fixture.last().reason == Reason.MISSING_STOCK
                && fixture.last().acknowledgement() == null && fixture.world.writes().isEmpty(),
                "insufficient whole job stock yields small rejection and never authorizes a slice");
            fixture.player.inventory.mainInventory[1].stackSize = 3;
            fixture.send(fixture.player, insufficient.begin(1));
            fixture.tick();
            require(fixture.last().reason == Reason.MISSING_STOCK && fixture.last().acknowledgement() == null,
                "an exact denied Begin replay cannot become a new delayed start");

            Plan plan = new Plan();
            plan.generation = fixture.generation();
            int before = fixture.receipts.size();
            fixture.dispatch(fixture.player, malformedBegin(plan));
            fixture.tick();
            require(fixture.receipts.size() == before && fixture.world.writes().isEmpty(),
                "oversize Begin is discarded in the outer decoder, before queuing or cap allocation");
            fixture.approve(plan, 2);
            fixture.send(fixture.player, plan.begin(2));
            fixture.tick();
            require(ack(fixture.last()).credit == 1, "new explicit request may acquire current stock");
            fixture.world.arm();

            fixture.player.inventory.mainInventory[1].stackSize = 2;
            fixture.send(fixture.player, plan.control(2, 99, Control.STATUS));
            fixture.tick();
            require(fixture.last().phase == Phase.AWAITING_SLICE && fixture.last().controlSequence == 0
                && fixture.last().acknowledgement() == null
                && HologramProjectedLayerService.active(fixture.player),
                "read-only status neither rechecks/debits stock nor advances the mutating-control sequence");
            fixture.player.inventory.mainInventory[1].stackSize = 3;

            fixture.send(fixture.player, plan.control(2, 1, Control.PAUSE));
            fixture.send(fixture.player, plan.first(2)); // use only the previously issued in-flight credit
            fixture.tick();
            require(fixture.last().phase == Phase.PAUSED && fixture.last().acceptedSlices == 1
                && fixture.last().placed == 0 && fixture.last().controlSequence == 1
                && ack(fixture.last()).credit == 0 && fixture.stock() == 3,
                "pause stages at most the already credited frame and suppresses all world operations");
            fixture.send(fixture.player, plan.control(2, 2, Control.RESUME));
            fixture.tick();
            require(fixture.last().placed == 2 && fixture.stock() == 1 && fixture.world.writes().size() == 2,
                "matched resume executes a bounded admitted slice exactly once");
            fixture.send(fixture.player, plan.control(2, 1, Control.PAUSE));
            fixture.tick();
            require(fixture.last().phase == Phase.AWAITING_SLICE, "delayed control cannot rewind current phase");

            fixture.send(fixture.player, plan.tail(2)); // Already ahead in DATA when the player asks to stop.
            fixture.send(fixture.player, plan.control(2, 99, Control.STATUS)); // Poll cannot poison a pending STOP.
            fixture.send(fixture.player, plan.control(2, 4, Control.STOP));
            fixture.tick();
            require(fixture.last().phase == Phase.STOPPED && fixture.last().reason == Reason.CANCELLED
                && fixture.last().placed == 2 && fixture.last().controlSequence == 4
                && fixture.last().acceptedSlices == 1 && fixture.last().settledSlices == 1
                && ack(fixture.last()).aborted && fixture.stock() == 1
                && fixture.world.getBlock(0, 65, 0) == Blocks.air && fixture.world.writes().size() == 2,
                "stop reports the exact prior paid prefix and rejects potential in-flight later work");
        }
    }

    private static void stopOvertakesBeginAndRevokedGateIsNotActive(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 3)) {
            fixture.login();
            Plan noProof = new Plan();
            noProof.generation = fixture.generation();
            fixture.send(fixture.player, noProof.begin(1));
            fixture.tick();
            require(fixture.last().reason == Reason.ADMISSION_REFUSED && fixture.last().acknowledgement() == null
                && !HologramProjectedLayerService.active(fixture.player), "fresh Begin needs exact central proof");

            Plan stopped = new Plan();
            stopped.generation = fixture.generation();
            fixture.approve(stopped, 1);
            fixture.send(fixture.player, stopped.begin(1));
            fixture.send(fixture.player, stopped.control(1, 1, Control.STOP));
            fixture.tick();
            require(fixture.last().phase == Phase.REJECTED && fixture.last().reason == Reason.CANCELLED
                && fixture.last().controlSequence == 1 && fixture.last().totalCells == 0
                && fixture.last().acknowledgement() == null && !HologramProjectedLayerService.active(fixture.player)
                && fixture.authorized == 1 && fixture.world.writes().isEmpty(),
                "priority STOP cancels only the exact queued authorized Begin, before returning any credit");
            fixture.send(fixture.player, stopped.begin(1));
            fixture.tick();
            require(fixture.last().reason == Reason.CANCELLED && fixture.authorized == 1,
                "replayed canceled Begin never consumes a second proof or becomes active");

            Plan revoked = new Plan();
            revoked.generation = fixture.generation();
            fixture.approve(revoked, 2);
            fixture.send(fixture.player, revoked.begin(2));
            fixture.tick();
            var endpoint = HologramWorkspace.captureActionEndpoint(fixture.player.playerNetServerHandler);
            var gate = HologramWorkspace.constructionGate(endpoint);
            require(ack(fixture.last()).credit == 1 && gate.revoke(endpoint.constructionScope)
                && HologramWorkspace.currentActionEndpoint(endpoint),
                "the transport can still deliver while its construction lease no longer exists");
            fixture.world.arm();
            fixture.send(fixture.player, revoked.first(2));
            fixture.send(fixture.player, revoked.control(2, 10, Control.STATUS));
            fixture.send(fixture.player, revoked.control(2, 1, Control.STOP));
            fixture.tick();
            require(fixture.last().phase == Phase.REJECTED && fixture.last().reason == Reason.RETIRED_UNKNOWN
                && fixture.last().acknowledgement() == null && fixture.last().controlSequence == 0
                && !HologramProjectedLayerService.active(fixture.player) && fixture.stock() == 3
                && fixture.world.writes().isEmpty(),
                "a revoked operation has no active progress, no credit and no fake STOP acknowledgement");
        }
    }

    private static void stopQueuedDuringFirstPaidPlacement(sun.misc.Unsafe unsafe, boolean sameKey) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 3)) {
            fixture.login();
            Plan plan = new Plan();
            plan.generation = fixture.generation();
            fixture.approve(plan, 1);
            fixture.send(fixture.player, plan.begin(1));
            fixture.tick();
            require(ack(fixture.last()).credit == 1, "actual confirmed Begin grants first slice");
            fixture.world.arm();

            Plan stop = sameKey ? plan : new Plan();
            stop.generation = plan.generation; // Same actual sender and generation; a stale key must not fence this job.
            Field field = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object base = unsafe.staticFieldBase(field);
            long offset = unsafe.staticFieldOffset(field);
            EventBus previous = MinecraftForge.EVENT_BUS;
            int[] callbacks = { 0 };
            unsafe.putObjectVolatile(base, offset, new EventBus() {
                @Override
                public boolean post(Event event) {
                    if (event instanceof BlockEvent.PlaceEvent place && place.player == fixture.player
                        && place.world == fixture.world && place.x == 0 && place.y == 64
                        && fixture.world.getBlock(0, 64, 0) == Blocks.stone) {
                        callbacks[0]++;
                        require(fixture.stock() == 3, "still inside the first real candidate, before exact payment");
                        try {
                            fixture.send(fixture.player, stop.control(1, 1, Control.STOP));
                        } catch (ReflectiveOperationException failed) {
                            throw new AssertionError("encoded STOP through actual authenticated handler", failed);
                        }
                    }
                    return false;
                }
            });
            try {
                fixture.send(fixture.player, plan.first(1));
                if (sameKey) fixture.send(fixture.player, plan.tail(1));
                fixture.tick();
            } finally {
                unsafe.putObjectVolatile(base, offset, previous);
            }
            require(callbacks[0] == 1 && fixture.world.getBlock(0, 64, 0) == Blocks.stone
                && !HologramConstructionJournal.pending(fixture.player), "the already-permitted first commit settles");
            if (sameKey) {
                Reply pending = fixture.last();
                require(pending.phase == Phase.PLACING && pending.placed == 1 && pending.settledSlices == 0
                    && pending.acknowledgement() == null && fixture.stock() == 2
                    && fixture.world.getBlock(1, 64, 0) == Blocks.air && fixture.world.writes().size() == 1
                    && HologramProjectedLayerService.active(fixture.player),
                    "STOP arriving after tick start preserves uncommitted slice/index and prevents the second permit");
                fixture.tick();
                Reply stopped = fixture.last();
                require(stopped.phase == Phase.STOPPED && stopped.reason == Reason.CANCELLED
                    && stopped.controlSequence == 1 && stopped.placed == 1 && stopped.acceptedSlices == 2
                    && stopped.settledSlices == 0 && ack(stopped).aborted && fixture.stock() == 2
                    && fixture.world.writes().size() == 1,
                    "priority STOP dispatch reports exactly the paid prefix without settling the deferred slice");
            } else {
                fixture.tick();
                require(fixture.last().phase == Phase.AWAITING_SLICE && fixture.last().placed == 2
                    && fixture.last().controlSequence == 0 && fixture.last().settledSlices == 1
                    && fixture.stock() == 1 && fixture.world.getBlock(1, 64, 0) == Blocks.stone
                    && fixture.world.writes().size() == 2,
                    "stale unrelated STOP cannot defer the next permit on its physically identical endpoint");
            }
        }
    }

    private static void staleEpochAndLateRetirement(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 3)) {
            fixture.login();
            Plan old = new Plan();
            old.generation = fixture.generation();
            fixture.approve(old, 1);
            fixture.send(fixture.player, old.begin(1));
            fixture.tick();
            fixture.world.arm();
            fixture.send(fixture.player, old.first(1));
            int before = fixture.receipts.size();
            ActionEndpoint original = HologramWorkspace.captureActionEndpoint(fixture.player.playerNetServerHandler);
            Plan late = new Plan();
            late.generation = old.generation;
            fixture.approve(late, 2);
            // Same transport/world/entity objects after a nested A -> B -> A, but a different lifecycle publication.
            HologramWorkspace.publishActionDimension(fixture.player);
            fixture.send(fixture.player, late.begin(2)); // This Begin was never admitted in the departed scope.
            fixture.tick();
            require(fixture.stock() == 3 && fixture.world.writes().isEmpty() && fixture.receipts.size() == before
                && !HologramWorkspace.currentActionEndpoint(original)
                && !HologramProjectedLayerService.active(fixture.player),
                "queued body cannot revive old authority after same-object generation rotation");

            Plan current = new Plan();
            current.generation = fixture.generation();
            fixture.approve(current, 1);
            fixture.send(fixture.player, current.begin(1));
            fixture.tick();
            require(ack(fixture.last()).credit == 1, "fresh endpoint can start exact new user intent");
            Field field = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object base = unsafe.staticFieldBase(field);
            long offset = unsafe.staticFieldOffset(field);
            EventBus previous = MinecraftForge.EVENT_BUS;
            int[] callback = { 0 };
            unsafe.putObjectVolatile(base, offset, new EventBus() {
                @Override
                public boolean post(Event event) {
                    if (event instanceof BlockEvent.PlaceEvent place && place.player == fixture.player
                        && place.world == fixture.world && place.x == 0 && place.y == 64
                        && fixture.world.getBlock(0, 64, 0) == Blocks.stone) {
                        callback[0]++;
                        HologramWorkspace.publishActionDimension(fixture.player);
                    }
                    return false;
                }
            });
            try {
                fixture.send(fixture.player, current.first(1));
                fixture.tick();
            } finally {
                unsafe.putObjectVolatile(base, offset, previous);
            }
            var now = HologramWorkspace.captureActionEndpoint(fixture.player.playerNetServerHandler);
            require(callback[0] == 1 && fixture.world.getBlock(0, 64, 0) == Blocks.air && fixture.stock() == 3
                && !HologramConstructionJournal.pending(fixture.player)
                && !HologramWorkspace.constructionGate(now).busy(fixture.player.getUniqueID())
                && !HologramProjectedLayerService.active(fixture.player),
                "late real Forge generation loss rolls back candidate and never pays a replacement or claims success");
        }
    }

    private static Request malformedBegin(Plan plan) {
        ByteBuf bytes = Unpooled.buffer();
        try {
            bytes.writeByte(HologramProjectedLayerTransport.VERSION);
            bytes.writeByte(HologramProjectedLayerTransport.Operation.BEGIN.ordinal());
            bytes.writeLong(plan.id.getMostSignificantBits());
            bytes.writeLong(plan.id.getLeastSignificantBits());
            bytes.writeLong(2);
            bytes.writeLong(plan.generation);
            bytes.writeZero(HologramProjectedLayerTransport.MAX_BEGIN + 1);
            Request malformed = new Request();
            malformed.fromBytes(bytes);
            return malformed;
        } finally {
            bytes.release();
        }
    }

    private static final class Plan {

        final UUID id = UUID.randomUUID();
        final byte[] manifest, first, tail;
        long generation = 1; // Initial unauthenticated fixture probe; real sends use the published generation below.

        Plan() throws Exception {
            String stone = "minecraft:stone";
            Header header = new Header(id, 0, 64, 0, 0, 64, 0, 1, 65, 0, 3, 2,
                Collections.singletonList(new Variant(stone, 0, stone, 0, -1, false, "", "", 3)));
            tail = HologramProjectedLayerWire.encodeSlice(
                new Slice(id, 1, 65, new int[] { 0, 0, 0 }));
            first = HologramProjectedLayerWire.encodeSlice(new Slice(id, 0, 64, new int[] { 0, 0, 0, 1, 0, 0 }));
            manifest = HologramProjectedLayerWire.encodeManifest(
                new Manifest(header));
        }

        Request begin(long intent) {
            return HologramProjectedLayerTransport.begin(id, intent, generation, manifest);
        }

        Request first(long intent) {
            return HologramProjectedLayerTransport.slice(id, intent, generation, first);
        }

        Request tail(long intent) {
            return HologramProjectedLayerTransport.slice(id, intent, generation, tail);
        }

        Request control(long intent, long sequence, Control control) {
            return HologramProjectedLayerTransport.control(id, intent, generation, sequence, control);
        }
    }

    private static final class Fixture implements AutoCloseable {

        final HologramPlacementFixtureWorld world;
        final EntityPlayerMP player;
        final List<Reply> receipts = new ArrayList<>();
        final Map<Key, ActionEndpoint> approvals = new HashMap<>();
        int authorized;
        final HologramProjectedLayerServer server;
        final Constructor<MessageContext> context;

        Fixture(sun.misc.Unsafe unsafe, int stock) throws ReflectiveOperationException {
            world = HologramPlacementFixtureWorld.create(unsafe);
            player = createPlayer(unsafe, world, stock);
            context = MessageContext.class.getDeclaredConstructor(INetHandler.class, Side.class);
            context.setAccessible(true);
            server = new HologramProjectedLayerServer((endpoint, reply) -> {
                ByteBuf bytes = Unpooled.buffer();
                try {
                    reply.toBytes(bytes);
                    int size = bytes.readableBytes();
                    require(size == 84 || size == 113, "every serving reply is bounded scalar status, not geometry");
                    Reply decoded = new Reply();
                    decoded.fromBytes(bytes);
                    require(decoded.request != null && !bytes.isReadable(), "actual reply decoder accepts exact frame");
                    if (endpoint.player == player) receipts.add(decoded);
                } finally {
                    bytes.release();
                }
            }, (endpoint, key) -> {
                boolean proved = approvals.remove(key, endpoint);
                if (proved) authorized++;
                return proved;
            });
            HologramProjectedLayerTransport.installServer(server); // Fixture registers no channel or public routes.
        }

        void login() {
            HologramWorkspace.publishActionEndpoint(player, false);
        }

        long generation() {
            return HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler).constructionScope.generation;
        }

        void approve(Plan plan, long intent) {
            approve(plan.id, plan.generation, intent);
        }

        void approve(UUID id, long generation, long intent) {
            ActionEndpoint endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
            approvals.put(new Key(id, intent, generation), endpoint);
        }

        void send(EntityPlayerMP peer, Request packet) throws ReflectiveOperationException {
            ByteBuf bytes = Unpooled.buffer();
            try {
                packet.toBytes(bytes);
                Request received = new Request();
                received.fromBytes(bytes);
                require(received.key != null && !bytes.isReadable(), "actual request decoder accepts exact frame");
                dispatch(peer, received);
            } finally {
                bytes.release();
            }
        }

        void dispatch(EntityPlayerMP peer, Request request) throws ReflectiveOperationException {
            MessageContext inbound = context.newInstance(peer.playerNetServerHandler, Side.SERVER);
            new HologramProjectedLayerTransport.ServerHandler().onMessage(request, inbound);
        }

        void tick() {
            server.tick(Long.MAX_VALUE);
        }

        long nextServerTick() {
            HologramWorkBudget.startServerTick(); // Same clock source used by the normal START/END lifecycle.
            try {
                tick();
                return HologramWorkBudget.serverTickSerial();
            } finally {
                HologramWorkBudget.endServerTick();
            }
        }

        Reply last() {
            return receipts.get(receipts.size() - 1);
        }

        int stock() {
            ItemStack item = player.inventory.mainInventory[1];
            return item == null ? 0 : item.stackSize;
        }

        @Override
        public void close() {
            HologramProjectedLayerTransport.installServer(null);
            server.close();
            HologramWorkspace.revokeActionEndpoint(player);
            player.playerNetServerHandler.netManager.channel().close();
        }
    }

    private static EntityPlayerMP createPlayer(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world, int stock)
        throws ReflectiveOperationException {
        EntityPlayerMP player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "projected-packet-fix");
        player.posY = 64;
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, stock);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.write(player.getHeldItem(), settings);
        return player;
    }

    private static Ack ack(Reply reply) throws Exception {
        return HologramProjectedLayerWire.decodeAck(reply.acknowledgement());
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
