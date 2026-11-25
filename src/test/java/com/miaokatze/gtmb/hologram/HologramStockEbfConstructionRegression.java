// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramStockEbfProfile.Installed;
import com.miaokatze.gtmb.hologram.HologramStockNativeConstruction.State;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Dedicated offline owner → private job → native ItemCasings setter → real scoped debit. Not a loaded game. */
public final class HologramStockEbfConstructionRegression {

    private HologramStockEbfConstructionRegression() {}

    public static void main(String[] args) {
        int status = 0;
        try {
            if (MinecraftTestBootstrap.enter(HologramStockEbfConstructionRegression.class, args)) {
                HologramNativeSavedWorldRegression.withOfflineRegistries(()->
                    HologramStockEbfProfileRegression.run(HologramStockEbfConstructionRegression::contracts));
                System.out.println("HologramStockEbfConstructionRegression passed (offline stock writes and debits)");
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        }
        System.exit(status);
    }

    private static void contracts(sun.misc.Unsafe unsafe, RawWorld world,
        BaseMetaTileEntity controller, ExtendedFacing facing, List<Installed> installed)
        throws IOException, ReflectiveOperationException {
        var previousStorage = HologramConstructionJournal.STORAGE_ROOT.get();
        HologramConstructionJournal.STORAGE_ROOT.set(Files.createTempDirectory("gtmb-stock-ebf-offline-"));
        // The shared fixture factory only wires an offline actor/embedded connection. Bind it to the real
        // loaded-section fixture before publishing any authenticated construction owner.
        var player = HologramLibraryServiceRegression.placementPlayer(
            unsafe, HologramPlacementFixtureWorld.create(unsafe), "RealStockEbfEngine");
        player.worldObj = world;
        literalCapture(unsafe, world, controller, player);
        var network = player.playerNetServerHandler.netManager;
        network.setNetHandler(player.playerNetServerHandler);
        var reentry = new TransportProbe(network);
        try {
            SimpleAssert.that(network.getClass() == NetworkManager.class && network.channel() == reentry
                && reentry.isOpen(), "real NetworkManager retains an open embedded transport with a single late probe");
            var exactHatches = new BaseMetaTileEntity[installed.size()];
            for (int i = 0; i < installed.size(); i++) {
                var keep = installed.get(i);
                int[] pos = coordinate(controller, facing, keep.a(), keep.b(), keep.c());
                exactHatches[i] = (BaseMetaTileEntity) world.getTileEntity(pos[0], pos[1], pos[2]);
            }
            player.capabilities.allowEdit = true;
            player.capabilities.isCreativeMode = false;
            player.posX = controller.xCoord + .5;
            player.posY = controller.yCoord + .5;
            player.posZ = controller.zCoord + .5;
            var main = player.inventory.mainInventory;
            String id = UUID.randomUUID().toString();
            var projectorTag = new NBTTagCompound();
            projectorTag.setString(HologramSessionKeys.TOOL_ID, id);
            main[0].setTagCompound(projectorTag);
            var backpackOnly=new HologramMaterials.Settings();backpackOnly.me=backpackOnly.containers=false;
            HologramMaterials.write(main[0],backpackOnly);
            main[1] = new ItemStack(GregTechAPI.sBlockCasings1, 12, 11);
            main[2] = new ItemStack(GregTechAPI.sBlockCasings5, 16, 1); // Wrong heat.
            HologramWorkspace.publishActionEndpoint(player, false);
            var endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
            var gate = HologramWorkspace.constructionGate(endpoint);
            SimpleAssert.that(gate != null, "headless fixture publishes real authenticated endpoint owner");
            var configuration = new HologramStockNativeEntry.EbfConfig(6, installed);
            selectedEbfMe(world,controller,facing,endpoint,id,configuration); // Existing request at sequence 1.
            declaredWholeNew(world, controller, facing, endpoint, id); // Sequence 2–5 before legacy 11–20.
            var wrong = typed(endpoint, controller, facing, id, request(1), configuration);
            try {
                HologramStockNativeEntry.begin(wrong);
                throw new AssertionError("wrong coil inventory must never start actual EBF");
            } catch (IOException refused) {
                SimpleAssert.that(world.writes().isEmpty() && HologramWorkspace.find(endpoint.actor) == null,
                    "fully funded admission refuses the wrong stock identity before reserving scratch or writing");
            }
            main[2].setItemDamage(9);
            var firstRequest = request(2);
            var job = HologramStockNativeEntry.begin(typed(endpoint, controller, facing, id, firstRequest, configuration));
            SimpleAssert.that(job.owns(HologramWorkspace.find(endpoint.actor)) && world.writes().isEmpty()
                && !job.stop(endpoint, request(3)), "same owner gate privately reserves only the exact EBF request; wrong STOP inert");
            var firstCasing = coordinate(controller, facing, -1, -3, 0);
            SimpleAssert.eq(State.ACTIVE, tickPrefix(job, 1), "real first permit commits only one casing");
            SimpleAssert.that(job.placed() == 1 && world.writes().size() == 1
                && world.getBlock(firstCasing[0], firstCasing[1], firstCasing[2]) == GregTechAPI.sBlockCasings1
                && world.getBlockMetadata(firstCasing[0], firstCasing[1], firstCasing[2]) == 11
                && main[1].stackSize == 11 && main[2].stackSize == 16,
                "sealed source order gives the first native heatproof world write and one exact debit");
            var firstCoil = coordinate(controller, facing, -1, -2, 0);
            for (int i = 0; i < 12 && main[2].stackSize == 16; i++) tickPrefix(job, 1);
            SimpleAssert.that(world.getBlock(firstCoil[0], firstCoil[1], firstCoil[2]) == GregTechAPI.sBlockCasings5
                && world.getBlockMetadata(firstCoil[0], firstCoil[1], firstCoil[2]) == 9 && main[2].stackSize == 15,
                "real stock coil setter uses selected LuV meta and deducts one coil, never another stock");
            int prefix = job.placed();
            int casingPrefix = 12 - main[1].stackSize;
            int coilPrefix = 16 - main[2].stackSize;
            int previousWrites = world.writes().size();
            SimpleAssert.that(job.stop(endpoint, firstRequest) && job.tick(Long.MAX_VALUE) == State.STOPPED
                && gate.query(endpoint.constructionScope, firstRequest).outcome.committedTransactions == prefix
                && world.writes().size() == previousWrites && HologramWorkspace.find(endpoint.actor) == null,
                "exact owner STOP retires the job, retains only the committed prefix, and prevents another permit");

            // A real Forge callback changes a different coil while observing an installed candidate.
            main[1] = new ItemStack(GregTechAPI.sBlockCasings1, 12, 11);
            main[2] = new ItemStack(GregTechAPI.sBlockCasings5, 16, 9);
            var otherCoil = coordinate(controller, facing, 1, -1, 2);
            var secondRequest = request(4);
            var rolledBack = HologramStockNativeEntry.begin(
                typed(endpoint, controller, facing, id, secondRequest, configuration));
            var field = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object eventBase = unsafe.staticFieldBase(field);
            long eventOffset = unsafe.staticFieldOffset(field);
            EventBus previousBus = MinecraftForge.EVENT_BUS;
            int[][] attempted = { null };
            unsafe.putObjectVolatile(eventBase, eventOffset, new EventBus() {
                @Override
                public boolean post(Event event) {
                    if (attempted[0] == null && event instanceof BlockEvent.PlaceEvent place && place.world == world
                        && place.player == player) {
                        attempted[0] = new int[] { place.x, place.y, place.z };
                        if (java.util.Arrays.equals(attempted[0], otherCoil))
                            throw new AssertionError("late coil must be distinct from the candidate");
                        SimpleAssert.that(world.getBlock(place.x, place.y, place.z) != Blocks.air
                            && main[1].stackSize == 12 && main[2].stackSize == 16,
                            "Forge sees the single real setter before exact stock payment");
                        world.setBlock(otherCoil[0], otherCoil[1], otherCoil[2], GregTechAPI.sBlockCasings5, 1, 3);
                    }
                    return false;
                }
            });
            try {
                SimpleAssert.eq(State.STOPPED, tickPrefix(rolledBack, 1),
                    "late mixed coil causes rollback and no new permits");
            } finally {
                unsafe.putObjectVolatile(eventBase, eventOffset, previousBus);
            }
            SimpleAssert.that(attempted[0] != null && world.getBlock(attempted[0][0], attempted[0][1], attempted[0][2])
                == Blocks.air && world.getBlockMetadata(otherCoil[0], otherCoil[1], otherCoil[2]) == 1
                && main[1].stackSize == 12 && main[2].stackSize == 16 && rolledBack.placed() == 0
                && !HologramConstructionJournal.pending(player)
                && gate.query(endpoint.constructionScope, secondRequest).outcome.committedTransactions == 0,
                "restores only the owned candidate, preserves independent coil mutation, and debits neither material");
            world.setBlock(otherCoil[0], otherCoil[1], otherCoil[2], Blocks.air, 0, 3);

            var finalRequest = request(5);
            var declaration = confirmation(endpoint, controller, facing, id, finalRequest, configuration);
            var observed = HologramStockNativeWire.observedEbfHatches(world, controller);
            SimpleAssert.that(observed.size() == 5 && observed.containsAll(declaration.ebfHatches()),
                "formal client profile observes exactly the five configured physical hatches");
            var replies = new ArrayList<HologramStockNativeWire.Reply>();
            var server = new HologramStockNativeServer((origin, reply) -> {
                SimpleAssert.that(origin == endpoint, "only the authenticated origin receives the construction receipt");
                var roundTrip = HologramStockNativeWire.readReply(HologramStockNativeWire.writeReply(reply));
                SimpleAssert.that(reply.equals(roundTrip), "v2 wire decodes the complete EBF progress range");
                replies.add(roundTrip);
            });
            var probe = HologramStockNativeWire.probe(declaration.forGeneration(0));
            var malformed = (NBTTagCompound) probe.copy();
            int[] duplicate = malformed.getIntArray("installedHatches");
            System.arraycopy(duplicate, 0, duplicate, 5, 5);
            malformed.setIntArray("installedHatches", duplicate);
            SimpleAssert.that(HologramStockNativeWire.readRequest(malformed) == null,
                "duplicate physical hatches are refused before any native command enters the server queue");
            int writesBeforeProof = world.writes().size();
            SimpleAssert.that(server.enqueue(endpoint, HologramStockNativeWire.readRequest(probe)),
                "formal EBF capability probe is queued");
            server.tick(Long.MAX_VALUE);
            SimpleAssert.that(replies.stream().anyMatch(reply -> reply.key().equals(declaration.key())
                && reply.phase() == HologramStockNativeWire.Phase.CAPABLE && reply.placed() == 0)
                && world.writes().size() == writesBeforeProof && main[1].stackSize == 12 && main[2].stackSize == 16,
                "proof is read-only; it preserves both materials and world state");
            SimpleAssert.that(server.enqueue(endpoint, HologramStockNativeWire.readRequest(
                HologramStockNativeWire.confirmAfterProbe(declaration))), "exact EBF confirmation is queued");
            server.tick(Long.MAX_VALUE);
            var complete = replies.stream().filter(reply -> reply.key().equals(declaration.key())
                && reply.phase() == HologramStockNativeWire.Phase.COMPLETE).findFirst().orElseThrow(() -> new AssertionError("No EBF completion receipt: " + replies));
            SimpleAssert.that(prefix + complete.placed() == 28
                && casingPrefix + 12 - (main[1] == null ? 0 : main[1].stackSize) == 12
                && coilPrefix + 16 - (main[2] == null ? 0 : main[2].stackSize) == 16
                && gate.query(endpoint.constructionScope, finalRequest).outcome.committedTransactions == complete.placed()
                && HologramWorkspace.find(endpoint.actor) == null && !HologramConstructionJournal.pending(player),
                "production short-command server queue completes remaining EBF work in one drain with exact debits");
            var lastCoilRead = coordinate(controller, facing, 1, -1, 2);
            var changedCoil = coordinate(controller, facing, -1, -2, 0);
            verifyLateRawReentry(unsafe, world, controller, facing, endpoint, id, configuration, request(6), firstCasing,
                lastCoilRead, false,
                () -> world.seed(changedCoil[0], changedCoil[1], changedCoil[2], GregTechAPI.sBlockCasings5, 1));
            SimpleAssert.eq(1, world.getBlockMetadata(changedCoil[0], changedCoil[1], changedCoil[2]),
                "raw late coil edit remains independently owned");
            world.seed(changedCoil[0], changedCoil[1], changedCoil[2], GregTechAPI.sBlockCasings5, 9);
            var energy = installed.get(0);
            var changedHatch = coordinate(controller, facing, energy.a(), energy.b(), energy.c());
            verifyLateRawReentry(unsafe, world, controller, facing, endpoint, id, configuration, request(7), firstCasing,
                lastCoilRead, false, () -> world.seedTile(changedHatch[0], changedHatch[1], changedHatch[2], null));
            SimpleAssert.that(world.getTileEntity(changedHatch[0], changedHatch[1], changedHatch[2]) == null
                && !exactHatches[0].isInvalid(), "raw hatch loss is detected despite the original tile still being valid");
            world.seedTile(changedHatch[0], changedHatch[1], changedHatch[2], exactHatches[0]);

            for (int i = 0; i < installed.size(); i++) {
                var keep = installed.get(i);
                int[] pos = coordinate(controller, facing, keep.a(), keep.b(), keep.c());
                var tile = (BaseMetaTileEntity) world.getTileEntity(pos[0], pos[1], pos[2]);
                SimpleAssert.that(tile != null && tile == exactHatches[i] && tile.getMetaTileID() == keep.id()
                    && tile.getFrontFacing().ordinal() == keep.facing(), "original hatch IDs and poses stay untouched");
            }

            verifyLateRawReentry(unsafe, world, controller, facing, endpoint, id, configuration, request(8), firstCasing,
                lastCoilRead, false, () -> world.seed(changedCoil[0], changedCoil[1], changedCoil[2],
                    GregTechAPI.sBlockCasings5, 1), reentry);
            SimpleAssert.that(reentry.fired == 1
                && world.getBlockMetadata(changedCoil[0], changedCoil[1], changedCoil[2]) == 1,
                "late Channel.isOpen reentry runs before the last raw witness and cannot debit");
            world.seed(changedCoil[0], changedCoil[1], changedCoil[2], GregTechAPI.sBlockCasings5, 9);
            verifyRollbackWithoutVirtualRead(unsafe, world, controller, facing, endpoint, id, configuration,
                request(9), firstCasing);

            verifyLateRawReentry(unsafe, world, controller, facing, endpoint, id, configuration, request(10), firstCasing,
                lastCoilRead, true, () -> world.seed(firstCasing[0], firstCasing[1], firstCasing[2], Blocks.dirt, 11));
            SimpleAssert.that(world.getBlock(firstCasing[0], firstCasing[1], firstCasing[2]) == Blocks.dirt,
                "a raw contested candidate is retained with its exact receipt, never restored over the foreign block");
        } finally {
            HologramWorkspace.revokeActionEndpoint(player);
            network.channel().close();
            HologramConstructionJournal.STORAGE_ROOT.set(previousStorage);
        }
    }

    /** Whole generic endpoint against the exact GT #1000 declaration and a bare controller, not the legacy EBF codec. */
    private static void declaredWholeNew(RawWorld world, BaseMetaTileEntity controller,
        ExtendedFacing facing, HologramWorkspace.ActionEndpoint endpoint, String id)
        throws IOException, ReflectiveOperationException {
        var machine = (gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace) controller.getMetaTileEntity();
        var definition = machine.getStructureDefinition();
        var elements = definition.getStructureFor("main");
        var cursor = new HologramStructureCursor(elements, 1, 3, 0);
        var offsets = new ArrayList<HologramDeclaredNativeSelection.Offset>();
        var original = new ArrayList<Block>();
        var metadata = new ArrayList<Integer>();
        var tiles = new ArrayList<TileEntity>();
        var exhaust = coordinate(controller, facing, 0, -3, 1);
        var shell = coordinate(controller, facing, -1, -3, 0);
        var coil = coordinate(controller, facing, -1, -2, 0);
        var player = endpoint.player;
        var inventory = player.inventory.mainInventory;
        var heldCasing = inventory[1];
        var heldCoil = inventory[2];
        var replies = new ArrayList<HologramStockNativeWire.Reply>();
        var server = new HologramStockNativeServer((actor, reply) -> {
            SimpleAssert.that(actor == endpoint, "the authenticated actor owns the exact declared receipt");
            replies.add(HologramStockNativeWire.readReply(HologramStockNativeWire.writeReply(reply)));
        });
        // Bare headless launch does not apply the buildPiece/HatchBuilder mixins; register the actual GT array
        // from its own construct() offsets, and attach the matching build callback to its actual muffler leaf.
        while (cursor.done() < cursor.total()) {
            int element = cursor.done();
            var position = cursor.declarationOffset(facing);
            if (position == null || position.x() == 0 && position.y() == 0 && position.z() == 0) continue;
            int x = controller.xCoord + position.x(), y = controller.yCoord + position.y(),
                z = controller.zCoord + position.z();
            if (x == exhaust[0] && y == exhaust[1] && z == exhaust[2]) {
                var leaf = elements[element];
                Object builder = new Object();
                HologramHatchPolicy.declare(builder, java.util.Collections.singletonList(gregtech.api.enums.HatchElement.Muffler), false);
                HologramHatchPolicy.built(builder, leaf);
                SimpleAssert.that(HologramHatchPolicy.completeMarker(leaf), "exact GT exhaust is a declared required hatch");
            }
            offsets.add(position);
            original.add(world.getBlock(x, y, z));
            metadata.add(world.getBlockMetadata(x, y, z));
            tiles.add(world.getTileEntity(x, y, z));
            world.seed(x, y, z, Blocks.air, 0);
            world.seedTile(x, y, z, null);
        }
        @SuppressWarnings("unchecked")
        var tileNames = (java.util.Map<Class<?>,String>) cpw.mods.fml.relauncher.ReflectionHelper
            .findField(TileEntity.class, "classToNameMap", "field_145853_j").get(null);
        String oldTileName = tileNames.putIfAbsent(BaseMetaTileEntity.class, "gtmb_fixture:registered_gt_device");
        try (var registration = com.miaokatze.gtmb.api.StructureSources.register("gtmb_test:exact-gt-ebf",
            gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace.class,
            (context, trigger) -> new com.miaokatze.gtmb.api.StructureSources.Plan(definition,
                java.util.Collections.singletonList(new com.miaokatze.gtmb.api.StructureSources.Piece("main", 1, 3, 0, trigger))))) {
            var selection = new HologramDeclaredNativeSelection(1000, java.util.Collections.singletonMap("coil", 6), 0, 0,
                java.util.Collections.emptyList(), java.util.Collections.emptyList());
            var create = (java.util.function.LongFunction<HologramStockNativeWire.Confirmation>) seq ->
                HologramStockNativeWire.Confirmation.declared(new HologramStockNativeWire.Key(UUID.randomUUID(), seq, 0),
                    player.dimension, UUID.fromString(id), controller.xCoord, controller.yCoord, controller.zCoord,
                    2, 0, facing.ordinal(), 1, false, false, 1, selection);
            long generation = endpoint.constructionScope.generation;
            inventory[1] = inventory[2] = null;
            player.capabilities.isCreativeMode = true;
            var stale = create.apply(2);
            SimpleAssert.that(server.enqueue(endpoint, HologramStockNativeWire.readRequest(HologramStockNativeWire.probe(stale))),
                "empty creative EBF PROBE was queued");
            server.tick(Long.MAX_VALUE);
            SimpleAssert.eq(HologramStockNativeWire.Phase.CAPABLE, replies.get(replies.size()-1).phase(),
                "real Creative allowEdit gives a read-only declared EBF proof");
            player.capabilities.isCreativeMode = false;
            var rejected = finishDeclared(server, endpoint, replies, stale.forGeneration(generation));
            SimpleAssert.that(rejected.phase() == HologramStockNativeWire.Phase.REFUSED
                && rejected.reason().contains("游戏模式已变化") && world.writes().isEmpty(),
                "mode switch between PROBE and BEGIN invalidates its proof without touching the world");
            var missing = create.apply(3);
            proveDeclared(server, endpoint, replies, missing);
            var unfunded = finishDeclared(server, endpoint, replies, missing.forGeneration(generation));
            SimpleAssert.that(unfunded.phase() == HologramStockNativeWire.Phase.STOPPED
                && unfunded.reason().contains("材料来源不足") && world.writes().isEmpty(),
                "not Creative: empty stock remains blocked on the same declared confirmation");
            inventory[1] = new ItemStack(GregTechAPI.sBlockCasings1, 32, 11);
            inventory[2] = new ItemStack(GregTechAPI.sBlockCasings5, 16, 9);
            var survivor = create.apply(4);
            proveDeclared(server, endpoint, replies, survivor);
            var built = finishDeclared(server, endpoint, replies, survivor.forGeneration(generation));
            SimpleAssert.that(built.phase() == HologramStockNativeWire.Phase.COMPLETE && built.placed() == 32
                && built.reason().contains("1 处未选仓室") && world.writes().size()==32
                && inventory[1].stackSize == 16 && inventory[2] == null
                && world.getBlock(shell[0], shell[1], shell[2]) == GregTechAPI.sBlockCasings1
                && world.getBlockMetadata(coil[0], coil[1], coil[2]) == 9
                && world.getBlock(exhaust[0], exhaust[1], exhaust[2]) == Blocks.air,
                "survival bare EBF commits exact shell/coils, charges 16 casing + 16 chosen coil, no guessed exhaust: " + built);
            for (var p : offsets)
                world.seed(controller.xCoord+p.x(),controller.yCoord+p.y(),controller.zCoord+p.z(),Blocks.air,0);
            world.writes().clear();
            inventory[1] = inventory[2] = null;
            player.capabilities.isCreativeMode = true;
            var creative = create.apply(5);
            proveDeclared(server, endpoint, replies, creative);
            var free = finishDeclared(server, endpoint, replies, creative.forGeneration(generation));
            SimpleAssert.that(free.phase() == HologramStockNativeWire.Phase.COMPLETE && free.placed() == 32
                && free.reason().contains("1 处未选仓室") && world.writes().size() == 32
                && inventory[1] == null && inventory[2] == null && !HologramConstructionJournal.pending(player)
                && world.getTileEntity(controller.xCoord, controller.yCoord, controller.zCoord) == controller
                && world.getBlockMetadata(coil[0],coil[1],coil[2]) == 9,
                "creative bare EBF commits real shell and selected coil with an empty inventory: " + free);
            System.out.println("GTMB_DECLARED_EBF_PROOF_AND_PLACEMENT survival=32 (+32 debit), creative=32 (0 debit), exhaust preserved; mode drift and missing stock denied");
        } finally {
            for (int i = 0; i < offsets.size(); i++) {
                var p = offsets.get(i);
                int x = controller.xCoord + p.x(), y = controller.yCoord + p.y(), z = controller.zCoord + p.z();
                world.seed(x, y, z, original.get(i), metadata.get(i));
                world.seedTile(x, y, z, tiles.get(i));
            }
            world.writes().clear();
            if (oldTileName == null) tileNames.remove(BaseMetaTileEntity.class);
            inventory[1] = heldCasing;
            inventory[2] = heldCoil;
            player.capabilities.isCreativeMode = false;
        }
    }

    private static void proveDeclared(HologramStockNativeServer server, HologramWorkspace.ActionEndpoint endpoint,
        List<HologramStockNativeWire.Reply> replies, HologramStockNativeWire.Confirmation confirmation) {
        SimpleAssert.that(server.enqueue(endpoint, HologramStockNativeWire.readRequest(HologramStockNativeWire.probe(confirmation))),
            "actual declared PROBE decode and enqueue");
        server.tick(Long.MAX_VALUE);
        SimpleAssert.eq(HologramStockNativeWire.Phase.CAPABLE, replies.get(replies.size()-1).phase(),
            "matching authenticated owner receives CAPABLE");
    }

    private static HologramStockNativeWire.Reply finishDeclared(HologramStockNativeServer server,
        HologramWorkspace.ActionEndpoint endpoint, List<HologramStockNativeWire.Reply> replies,
        HologramStockNativeWire.Confirmation confirmation) {
        SimpleAssert.that(server.enqueue(endpoint, HologramStockNativeWire.readRequest(HologramStockNativeWire.confirmAfterProbe(confirmation))),
            "actual declared BEGIN_AFTER_PROBE decode and enqueue");
        server.tick(Long.MAX_VALUE);
        var last = replies.get(replies.size() - 1);
        SimpleAssert.eq(confirmation.key(), last.key(), "actual declared terminal receipt belongs to the confirmation");
        return last;
    }

    private static void verifyLateRawReentry(sun.misc.Unsafe unsafe, RawWorld world, BaseMetaTileEntity controller,
        ExtendedFacing facing, HologramWorkspace.ActionEndpoint endpoint, String id,
        HologramStockNativeEntry.EbfConfig configuration, HologramConstructionTaskGate.Request key,
        int[] candidate, int[] lastCoil, boolean contestedCandidate, Runnable foreignMutation)
        throws IOException, ReflectiveOperationException {
        verifyLateRawReentry(unsafe, world, controller, facing, endpoint, id, configuration, key, candidate,
            lastCoil, contestedCandidate, foreignMutation, null);
    }

    private static void verifyLateRawReentry(sun.misc.Unsafe unsafe, RawWorld world, BaseMetaTileEntity controller,
        ExtendedFacing facing, HologramWorkspace.ActionEndpoint endpoint, String id,
        HologramStockNativeEntry.EbfConfig configuration, HologramConstructionTaskGate.Request key,
        int[] candidate, int[] lastCoil, boolean contestedCandidate, Runnable foreignMutation, TransportProbe transport)
        throws IOException, ReflectiveOperationException {
        int x = candidate[0], y = candidate[1], z = candidate[2];
        world.seed(x, y, z, Blocks.air, 0);
        var stock = endpoint.player.inventory.mainInventory;
        stock[1] = new ItemStack(GregTechAPI.sBlockCasings1, 12, 11);
        stock[2] = new ItemStack(GregTechAPI.sBlockCasings5, 16, 9);
        var owned = HologramStockNativeEntry.begin(typed(endpoint, controller, facing, id, key, configuration));
        int before = world.writes().size();
        var bus = MinecraftForge.class.getDeclaredField("EVENT_BUS");
        var base = unsafe.staticFieldBase(bus);
        long offset = unsafe.staticFieldOffset(bus);
        EventBus previous = MinecraftForge.EVENT_BUS;
        int[] phase = { 0 };
        unsafe.putObjectVolatile(base, offset, new EventBus() {
            @Override
            public boolean post(Event event) {
                if (phase[0] == 0 && event instanceof BlockEvent.PlaceEvent place && place.world == world
                    && place.player == endpoint.player && place.x == x && place.y == y && place.z == z) {
                    phase[0] = 1;
                    Runnable change = () -> {
                        phase[0] = 2;
                        foreignMutation.run();
                    };
                    world.watchLateCandidate(candidate, lastCoil, transport == null ? change : () -> transport.onOpen = change);
                }
                return false;
            }
        });
        try {
            SimpleAssert.eq(contestedCandidate ? State.RECOVERY_PENDING : State.STOPPED,
                tickPrefix(owned, 1),
                "late change after the full virtual dependency read is settled only with exact ownership");
        } finally {
            unsafe.putObjectVolatile(base, offset, previous);
        }
        var gate = HologramWorkspace.constructionGate(endpoint);
        SimpleAssert.that(phase[0] == 2 && stock[1].stackSize == 12 && stock[2].stackSize == 16 && owned.placed() == 0,
            "the raw witness ran after the final virtual callback and prevented both debits");
        var settled = gate.query(endpoint.constructionScope, key);
        if (contestedCandidate) {
            SimpleAssert.that(world.writes().size() == before + 1 && owned.unsettled() != null
                && settled.phase == HologramConstructionTaskGate.Phase.RECOVERY_PENDING
                && HologramConstructionJournal.pending(endpoint.player),
                "unknown raw candidate leaves the exact permit and receipt pending without rollback");
        } else {
            SimpleAssert.that(world.writes().size() == before + 2 && world.getBlock(x, y, z) == Blocks.air
                && settled.outcome.committedTransactions == 0 && !HologramConstructionJournal.pending(endpoint.player),
                "actual owned candidate is rolled back when a different raw dependency changed");
        }
    }

    private static void verifyRollbackWithoutVirtualRead(sun.misc.Unsafe unsafe, RawWorld world,
        BaseMetaTileEntity controller, ExtendedFacing facing, HologramWorkspace.ActionEndpoint endpoint, String id,
        HologramStockNativeEntry.EbfConfig configuration, HologramConstructionTaskGate.Request key, int[] candidate)
        throws IOException, ReflectiveOperationException {
        int x = candidate[0], y = candidate[1], z = candidate[2];
        world.seed(x, y, z, Blocks.air, 0);
        var stock = endpoint.player.inventory.mainInventory;
        stock[1] = new ItemStack(GregTechAPI.sBlockCasings1, 12, 11);
        stock[2] = new ItemStack(GregTechAPI.sBlockCasings5, 16, 9);
        var owned = HologramStockNativeEntry.begin(typed(endpoint, controller, facing, id, key, configuration));
        int before = world.writes().size();
        var bus = MinecraftForge.class.getDeclaredField("EVENT_BUS");
        var base = unsafe.staticFieldBase(bus);
        long offset = unsafe.staticFieldOffset(bus);
        EventBus previous = MinecraftForge.EVENT_BUS;
        boolean[] veto = { false };
        unsafe.putObjectVolatile(base, offset, new EventBus() {
            @Override
            public boolean post(Event event) {
                if (!veto[0] && event instanceof BlockEvent.PlaceEvent place && place.world == world
                    && place.player == endpoint.player && place.x == x && place.y == y && place.z == z) {
                    veto[0] = true;
                    world.forbidVirtualCandidate = candidate;
                    return true;
                }
                return false;
            }
        });
        try {
            SimpleAssert.eq(State.STOPPED, tickPrefix(owned, 1), "Forge veto stops exact EBF without debit");
        } finally {
            world.forbidVirtualCandidate = null;
            unsafe.putObjectVolatile(base, offset, previous);
        }
        SimpleAssert.that(veto[0] && world.writes().size() == before + 2 && world.getBlock(x, y, z) == Blocks.air
            && world.getBlockMetadata(x, y, z) == 0 && stock[1].stackSize == 12 && stock[2].stackSize == 16
            && !HologramConstructionJournal.pending(endpoint.player),
            "original raw Air is restored and proved without any intervening virtual candidate lookup");
    }

    /** Real EmbeddedChannel behavior, with only the late armed isOpen call made reentrant. */
    private static final class TransportProbe extends EmbeddedChannel {

        Runnable onOpen;
        int fired;

        TransportProbe(NetworkManager network) throws ReflectiveOperationException {
            super(new ChannelInboundHandlerAdapter());
            var field = NetworkManager.class.getDeclaredField("channel");
            field.setAccessible(true);
            var original = network.channel();
            field.set(network, this);
            original.close();
        }

        @Override
        public boolean isOpen() {
            if (onOpen != null) {
                Runnable mutation = onOpen;
                onOpen = null;
                fired++;
                mutation.run();
            }
            return super.isOpen();
        }
    }

    /** Ordinary server and client imports see the physical subset; standalone stock construction below still sees the definition. */
    private static void literalCapture(sun.misc.Unsafe unsafe, RawWorld world, BaseMetaTileEntity controller,
        net.minecraft.entity.player.EntityPlayerMP player) throws IOException, ReflectiveOperationException {
        int x = controller.xCoord, y = controller.yCoord, z = controller.zCoord;
        Block prior = world.getBlock(x,y+7,z);
        var items = player.inventory.mainInventory;
        int slot = player.inventory.currentItem;
        var old = items[slot];
        @SuppressWarnings("unchecked")
        var tileNames = (java.util.Map<Class<?>,String>) cpw.mods.fml.relauncher.ReflectionHelper
            .findField(TileEntity.class,"classToNameMap","field_145853_j").get(null);
        String oldName = tileNames.putIfAbsent(BaseMetaTileEntity.class,"gtmb_fixture:registered_gt_device");
        world.seed(x,y+7,z,Blocks.diamond_block,0); // Outside both selections.
        try {
            String signature = null;
            for (int setting : new int[] {1, 7}) {
                var tool = new ItemStack(Blocks.stone,setting);
                com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                    .setChannelData(tool,"coil",setting);
                items[slot] = tool;
                var area = new HologramArea();
                area.point(1,x,y,z,"replace"); area.point(2,x,y,z,"replace");
                area.begin(world);
                long end = System.nanoTime() + 5_000_000_000L;
                while (!area.tick(player)) {
                    SimpleAssert.that(area.importProgress()<100,"raw server editor is not yet published");
                    if (System.nanoTime() > end) throw new AssertionError("server literal capture did not complete");
                    Thread.yield();
                }
                String server=literalSignature(area.blueprint, controller);
                SimpleAssert.that(area.blueprint.cells instanceof HologramLiteralCaptureRows,"server/standalone literal inputs keep one packed source and sparse special rows");
                SimpleAssert.that(area.structures.isEmpty() && area.members.isEmpty() && "captured".equals(area.importStage()),
                    "ordinary server capture has no inferred machines, membership second pass, or tool dependency");
                boolean remote=world.isRemote;
                try {
                    world.isRemote=true;
                    var client = new HologramLocalAreaCapture(world, player.dimension, area);
                    HologramLocalBlueprintDocument doc = null;
                    while(doc==null) {
                        if (System.nanoTime() > end) throw new AssertionError("client literal capture did not finish");
                        SimpleAssert.that(client.progress()<100, "only completed drafts report 100");
                        doc=client.step(128,10_000_000L);
                        Thread.yield();
                    }
                    var docField=HologramLocalBlueprintDocument.class.getDeclaredField("document");
                    docField.setAccessible(true);
                    SimpleAssert.eq(server,literalSignature((HologramPresets.Preset)docField.get(doc),controller),
                        "both formal local and server region-capture paths preserve identical physical state");
                    SimpleAssert.eq(100,client.progress(),"completed local literal capture");
                } finally { world.isRemote=remote; }
                if(signature!=null)SimpleAssert.eq(signature,server,"changing held tool master and coil never changes capture");
                signature=server;
            }
        } finally {
            if(oldName==null)tileNames.remove(BaseMetaTileEntity.class);
            items[slot]=old; world.seed(x,y+7,z,prior,0);
        }
    }

    private static String literalSignature(HologramPresets.Preset preset, BaseMetaTileEntity controller) throws IOException {
        SimpleAssert.that(preset.structures.isEmpty() && preset.cells.size()==1,
            "partial GT machine selection includes just its controller, no exterior marker, hatches or template cells");
        var cell=preset.cells.get(0);
        SimpleAssert.that(!cell.controller && (cell.structureIds==null || cell.structureIds.isEmpty())
            && cell.structureId==null && cell.x==0 && cell.y==0 && cell.z==0,
            "the physical controller is not a structure anchor, nor a machine-membership group");
        var nbt=HologramBlueprintNbt.decode(cell.tileNbt);
        SimpleAssert.that(nbt!=null && nbt.getInteger("mID")==1000 && nbt.getByte("mFacing")==cell.facing && cell.damage==1000
            && cell.facing==HologramBlockIdentity.front(controller),"literal controller identity, stored NBT, and facing survive");
        return cell.block+"/"+cell.metadata+"/"+cell.damage+"/"+cell.facing+"/"+nbt;
    }

    /** Genuine GT cell + projected source options through native BEGIN and real wireless auth/storage path. */
    private static void selectedEbfMe(RawWorld world,BaseMetaTileEntity controller,ExtendedFacing facing,
        HologramWorkspace.ActionEndpoint endpoint,String tool,HologramStockNativeEntry.EbfConfig config) throws IOException,ReflectiveOperationException {
        var actor=endpoint.player;var slots=actor.inventory.mainInventory;
        var beforeTool=(NBTTagCompound)slots[0].getTagCompound().copy();
        var shownOne=slots[1];var shownTwo=slots[2];int[] booked={12,16};
        try(var grid=new HologramProjectedMeFixture(actor,true,new ItemStack[]{
            new ItemStack(GregTechAPI.sBlockCasings1,1,11),new ItemStack(GregTechAPI.sBlockCasings5,1,9)},booked)) {
            slots[1]=null;
            var choice=new HologramMaterials.Settings();choice.main=choice.containers=false;choice.me=true;
            HologramMaterials.write(slots[0],choice);
            var request=new HologramConstructionTaskGate.Request(UUID.randomUUID(),1,HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE);
            var control=confirmation(endpoint,controller,facing,tool,request,config)
                .withSources(HologramProjectedMainPolicy.settingsKey(choice));
            var wire=HologramStockNativeWire.confirm(control);
            var decoded=HologramStockNativeWire.readRequest(wire);
            SimpleAssert.that(decoded!=null && !wire.hasKey("cells"),"small BEGIN preserves selected ME-only policy");
            var replay=(NBTTagCompound)wire.copy();replay.setInteger("sources",1);
            var other=HologramStockNativeWire.readRequest(replay);
            SimpleAssert.that(other!=null && HologramStockNativeEntry.ingress(endpoint,other.confirmation().intent(),tool)==null,
                "stale provider selection rejected instead of silently extracting from another source");
            var submit=HologramStockNativeEntry.ingress(endpoint,decoded.confirmation().intent(),tool);
            SimpleAssert.that(submit!=null,"server receives ME-only consent");
            var job=HologramStockNativeEntry.begin(submit);
            SimpleAssert.eq(0,grid.modulates,"admission only simulates");
            SimpleAssert.eq(State.ACTIVE,tickPrefix(job,1),"ME-only stock pays a physical EBF shell");
            var pt=coordinate(controller,facing,-1,-3,0);
            SimpleAssert.that(job.placed()==1 && world.getBlock(pt[0],pt[1],pt[2])==GregTechAPI.sBlockCasings1
                && world.getBlockMetadata(pt[0],pt[1],pt[2])==11 && booked[0]==11 && booked[1]==16 && grid.modulates==1
                && slots[1]==null && !HologramConstructionJournal.pending(actor),"original physical casing paid only once from the wireless grid");
            SimpleAssert.that(job.stop(endpoint,request),"exact owning EBF can cancel after first genuine cell");
            world.seed(pt[0],pt[1],pt[2],Blocks.air,0); world.writes().clear();
            System.out.println("GTMB_EBF_SELECTED_ME one actual heatproof casing, one physical write/ME MODULATE, zero backpack, stale-source refused");
        }finally{slots[0].setTagCompound(beforeTool);slots[1]=shownOne;slots[2]=shownTwo;}
    }

    private static HologramStockNativeEntry.Submission typed(HologramWorkspace.ActionEndpoint endpoint,
        BaseMetaTileEntity controller, ExtendedFacing facing, String tool, HologramConstructionTaskGate.Request request,
        HologramStockNativeEntry.EbfConfig configuration) {
        var confirmation = confirmation(endpoint, controller, facing, tool, request, configuration);
        var encoded = HologramStockNativeWire.confirm(confirmation);
        var decoded = HologramStockNativeWire.readRequest(encoded);
        SimpleAssert.that(decoded != null && confirmation.equals(decoded.confirmation()) && !encoded.hasKey("cells"),
            "official stock native codec round trips exact EBF hatches and coil without geometry");
        var submitted = HologramStockNativeEntry.ingress(endpoint, decoded.confirmation().intent(), tool);
        SimpleAssert.that(submitted != null, "production EBF wire admits the current typed #1000 intent");
        return submitted;
    }

    private static HologramStockNativeWire.Confirmation confirmation(HologramWorkspace.ActionEndpoint endpoint,
        BaseMetaTileEntity controller, ExtendedFacing facing, String tool, HologramConstructionTaskGate.Request request,
        HologramStockNativeEntry.EbfConfig configuration) {
        var hatches = new ArrayList<HologramStockNativeWire.EbfHatch>();
        for (var hatch : configuration.installed())
            hatches.add(new HologramStockNativeWire.EbfHatch(hatch.a(), hatch.b(), hatch.c(), hatch.id(), hatch.facing()));
        return HologramStockNativeWire.Confirmation.ebf(
            new HologramStockNativeWire.Key(request.id, request.sequence, endpoint.constructionScope.generation),
            endpoint.player.dimension, UUID.fromString(tool), controller.xCoord, controller.yCoord, controller.zCoord,
            2, 0, facing.ordinal(), 1, false, false, 0, configuration.coil(), hatches);

    }

    private static HologramConstructionTaskGate.Request request(long sequence) {
        return new HologramConstructionTaskGate.Request(
            UUID.randomUUID(), sequence+10, HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE);
    }

    private static int[] coordinate(BaseMetaTileEntity controller, ExtendedFacing facing, int a, int b, int c) {
        int[] result = new int[3];
        facing.getWorldOffset(new int[] { a, b, c }, result);
        result[0] += controller.xCoord;
        result[1] += controller.yCoord;
        result[2] += controller.zCoord;
        return result;
    }

    /** Real loaded Forge backing; overrides the headless setter only to avoid creating/running a server instance. */
    static final class RawWorld extends HologramServiceEditRegression.ReadOnlyWorld {

        private List<String> writes;
        private int[] watch, candidate, forbidVirtualCandidate;
        private Runnable pending;
        private boolean reachedLastCoil;

        static RawWorld create(sun.misc.Unsafe unsafe, int x, int z) throws ReflectiveOperationException {
            var created = (RawWorld) unsafe.allocateInstance(RawWorld.class);
            created.writes = new ArrayList<>();
            setWorld(created, "provider", new WorldProviderSurface());
            setWorld(created, "worldAccesses", new ArrayList<>());
            setWorld(created, "perWorldStorage", new MapStorage(null));
            created.capturedBlockSnapshots = new ArrayList<>();
            created.theChunkProviderServer = new ChunkProviderServer(created, null, null);
            setWorld(created,"chunkProvider",created.theChunkProviderServer);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                var chunk = new Chunk(created, (x >> 4) + dx, (z >> 4) + dz);
                chunk.isChunkLoaded = true;
                created.theChunkProviderServer.loadedChunkHashMap.add(
                    ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition), chunk);
            }
            return created;
        }

        private static void setWorld(RawWorld world, String name, Object value) throws ReflectiveOperationException {
            var field = World.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(world, value);
        }

        private Chunk loaded(int x, int z) {
            return (Chunk) theChunkProviderServer.loadedChunkHashMap.getValueByKey(
                ChunkCoordIntPair.chunkXZ2Int(x >> 4, z >> 4));
        }

        void seed(int x, int y, int z, Block block, int meta) {
            var sections = loaded(x, z).getBlockStorageArray();
            if (sections[y >> 4] == null) sections[y >> 4] = new ExtendedBlockStorage(y & ~15, false);
            sections[y >> 4].func_150818_a(x & 15, y & 15, z & 15, block);
            sections[y >> 4].setExtBlockMetadata(x & 15, y & 15, z & 15, meta);
        }

        void seedTile(int x, int y, int z, TileEntity tile) {
            var tiles = loaded(x, z).chunkTileEntityMap;
            var pos = new ChunkPosition(x & 15, y, z & 15);
            if (tile == null) tiles.remove(pos);
            else tiles.put(pos, tile);
        }

        List<String> writes() {
            return writes;
        }

        void watchLateCandidate(int[] currentCandidate, int[] lastCoil, Runnable mutation) {
            if (Arrays.equals(currentCandidate, lastCoil)) throw new AssertionError("watch must be a distinct coil");
            watch = lastCoil;
            candidate = currentCandidate;
            pending = mutation;
            reachedLastCoil = false;
        }

        private static boolean same(int[] point, int x, int y, int z) {
            return point[0] == x && point[1] == y && point[2] == z;
        }

        private void rejectRollbackProbe(int x, int y, int z) {
            if (forbidVirtualCandidate != null && same(forbidVirtualCandidate, x, y, z))
                throw new AssertionError("rollback used a virtual candidate read instead of exact raw ownership");
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            rejectRollbackProbe(x, y, z);
            return y >= 0 && y < 256 && loaded(x, z) != null;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            rejectRollbackProbe(x, y, z);
            var block = loaded(x, z).getBlock(x & 15, y, z & 15);
            if (pending != null) {
                if (same(watch, x, y, z)) reachedLastCoil = true;
                else if (reachedLastCoil && same(candidate, x, y, z)) {
                    var mutation = pending;
                    pending = null;
                    mutation.run();
                }
            }
            return block;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            rejectRollbackProbe(x, y, z);
            return loaded(x, z).getBlockMetadata(x & 15, y, z & 15);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            rejectRollbackProbe(x, y, z);
            return loaded(x, z).chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
        }

        @Override
        public boolean isAirBlock(int x, int y, int z) {
            return getBlock(x, y, z) == Blocks.air;
        }

        @Override
        public boolean canPlaceEntityOnSide(Block block, int x, int y, int z, boolean replace, int side, Entity entity,
            ItemStack stack) {
            return block != null && block != Blocks.air && blockExists(x, y, z) && isAirBlock(x, y, z)
                && getTileEntity(x, y, z) == null;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            seed(x, y, z, block, meta);
            writes.add(x + "," + y + "," + z);
            return true;
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            seed(x, y, z, getBlock(x, y, z), meta);
            return true;
        }

        @Override
        public void playSoundEffect(double x, double y, double z, String sound, float volume, float pitch) {}
    }

    /** Test-only interposition for cancellation/rollback at a committed prefix, not a production speed limit. */
    private static HologramStockNativeConstruction.State tickPrefix(HologramStockNativeEntry entry, int count) {
        int stopAt = entry.placed() + count;
        return entry.tick(Long.MAX_VALUE,
            (gate, lease) -> entry.placed() >= stopAt ? null : gate.beginTransaction(lease));
    }
}
