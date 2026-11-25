// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.SaveHandler;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkDataEvent;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.util.ServerTaskScheduler;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Real request decoding, native tile instances and region I/O; event dispatch is a synthetic bootstrap boundary. */
public final class HologramCutProtectionRegression {

    private static final String TILE = "gtmb_test_cut_protection_chest";

    private HologramCutProtectionRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramCutProtectionRegression.class, args)) return;
        run();
        System.out.println("HologramCutProtectionRegression PASS");
    }

    @SuppressWarnings("unchecked")
    public static void run() {
        String threadName = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
            Path directory = Files.createTempDirectory("gtmb-cut-protection-");
            Scene world = (Scene) unsafe.allocateInstance(Scene.class);
            Field provider = World.class.getDeclaredField("provider");
            provider.setAccessible(true);
            provider.set(world, new WorldProviderSurface());
            world.provider.hasNoSky = true;
            world.loadedTileEntityList = new ArrayList<>();
            world.save = new SaveHandler(directory.toFile(), "world", false);
            world.deniedX = Integer.MIN_VALUE;
            world.theChunkProviderServer = new Provider(world, new Writer(world.save.getWorldDirectory()));
            Field chunkProvider = World.class.getDeclaredField("chunkProvider");
            chunkProvider.setAccessible(true);
            chunkProvider.set(world, world.theChunkProviderServer);
            UUID owner = UUID.randomUUID();
            Actor player = actor(unsafe, world, owner);
            Field global = MinecraftServer.class.getDeclaredField("mcServer");
            global.setAccessible(true);
            Object previousServer = global.get(null);
            Field recoveredServer = HologramCutService.class.getDeclaredField("recoveredServer");
            recoveredServer.setAccessible(true);
            Object previousRecoveredServer = recoveredServer.get(null);
            Field worldsField = DimensionManager.class.getDeclaredField("worlds");
            worldsField.setAccessible(true);
            Map<Integer, WorldServer> worlds = (Map<Integer, WorldServer>) worldsField.get(null);
            WorldServer previousWorld = worlds.put(0, world);
            global.set(null, unsafe.allocateInstance(DedicatedServer.class));
            Map<String, Class<?>> types = (Map<String, Class<?>>) ReflectionHelper
                .findField(TileEntity.class, "nameToClassMap", "field_145855_i")
                .get(null);
            Map<Class<?>, String> names = (Map<Class<?>, String>) ReflectionHelper
                .findField(TileEntity.class, "classToNameMap", "field_145853_j")
                .get(null);
            Class<?> previousType = types.put(TILE, ObservedChest.class);
            String previousName = names.put(ObservedChest.class, TILE);
            Field channel = HologramNetwork.class.getDeclaredField("channel");
            channel.setAccessible(true);
            Object previousChannel = channel.get(null);
            CutCapture capture = new CutCapture(player);
            channel.set(null, capture);
            EventBus previousBus = MinecraftForge.EVENT_BUS;
            Guard guard = new Guard(world);
            Field bus = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object busBase = unsafe.staticFieldBase(bus);
            long busOffset = unsafe.staticFieldOffset(bus);
            unsafe.putObject(busBase, busOffset, guard);
            try {
                HologramCutStorage storage = new HologramCutStorage(
                    world.save.getWorldDirectory()
                        .toPath(),
                    owner);
                UUID proof = endpointCapability(world, player, guard, storage, capture);
                proof = staleBreakAndHolder(world, player, guard, storage, capture, proof);
                lateLoadedSlotReplacement(world, player, guard, storage, capture, proof);
                proof = permissionsAndRollback(world, player, guard, storage, capture, proof);
                knownVanillaPose(world, player, guard, storage, capture, proof);
                guard.expectedContents = 4;
                try {
                    compoundJournal(world, player, guard, storage, capture, proof);
                } finally {
                    guard.expectedContents = 3;
                }
                recovery(world, player, guard, storage);
                contestedStages(unsafe, world, guard, channel, capture);
            } finally {
                HologramWorkspace.revokeActionEndpoint(player);
                HologramCutService.disconnected(player);
                unsafe.putObject(busBase, busOffset, previousBus);
                channel.set(null, previousChannel);
                if (previousType == null) types.remove(TILE);
                else types.put(TILE, previousType);
                if (previousName == null) names.remove(ObservedChest.class);
                else names.put(ObservedChest.class, previousName);
                if (previousWorld == null) worlds.remove(0);
                else worlds.put(0, previousWorld);
                global.set(null, previousServer);
                recoveredServer.set(null, previousRecoveredServer);
                player.playerNetServerHandler.netManager.channel()
                    .close();
            }
            // RegionFileCache retains its own handles; do not clear another test's global region cache.
            directory.toFile()
                .deleteOnExit();
        } catch (Exception failure) {
            throw new AssertionError("cut protection and recovery", failure);
        } finally {
            Thread.currentThread()
                .setName(threadName);
        }
    }

    private static UUID endpointCapability(Scene world, Actor player, Guard guard, HologramCutStorage storage,
        CutCapture capture) throws Exception {
        NBTTagCompound passive = request("list");
        HologramCutProtocol.markPassiveList(passive);
        wire(player, passive);
        SimpleAssert.eq(0, capture.parts, "unpublished endpoint cannot issue a Cut capability");
        HologramWorkspace.publishActionEndpoint(player, false);
        NBTTagCompound first = wireReply(player, passive, capture);
        UUID old = HologramCutProtocol.boundsProof(first);
        SimpleAssert.that(first.getBoolean("success") && first.hasKey("entries", 9) && old != null,
            "genuine marked passive LIST on the actual handler issues the exact endpoint proof");

        HologramSession beforeWorkspace = HologramWorkspace.find(player.getUniqueID());
        NBTTagCompound beforeDirectory = storage.load();
        int reads = world.saveReads, ordinary = capture.ordinary;
        NBTTagCompound oldOpen = request("openWorkspace");
        NBTTagCompound refusal;
        world.forbidChunkLookup = true;
        try {
            refusal = wireReply(player, oldOpen, capture);
        } finally {
            world.forbidChunkLookup = false;
        }
        SimpleAssert.that("workspace_local_only".equals(refusal.getString("code")) && !refusal.getBoolean("success")
            && !refusal.hasKey("entries") && !refusal.hasKey("preview") && !refusal.hasKey("session")
            && HologramCutProtocol.boundsProof(refusal) == null
            && world.saveReads == reads && capture.ordinary == ordinary
            && HologramWorkspace.find(player.getUniqueID()) == beforeWorkspace,
            "real old remote-manager packet yields only local-only refusal, before storage, capture or ordinary State");
        SimpleAssert.that(beforeDirectory.equals(storage.load()), "remote manager never changes the owner's directory");

        NBTTagCompound wrongType = request("list");
        wrongType.setInteger("cutMetadataOnly", 1);
        NBTTagCompound wrongTarget = request("list");
        HologramCutProtocol.markPassiveList(wrongTarget);
        wrongTarget.setString("id", UUID.randomUUID().toString());
        reads = world.saveReads;
        NBTTagCompound wrongTypeReply = wireReply(player, wrongType, capture);
        NBTTagCompound wrongTargetReply = wireReply(player, wrongTarget, capture);
        SimpleAssert.that(!wrongTypeReply.getBoolean("success") && !wrongTargetReply.getBoolean("success")
            && !wrongTypeReply.hasKey("entries") && !wrongTargetReply.hasKey("entries")
            && world.saveReads == reads,
            "passive marker neither coerces numeric types nor authorizes a targeted read; both reject pre-storage");

        ObservedChest original = chest(world, 6, Items.redstone, 2);
        NBTTagCompound candidate = denseCut(6, 6);
        NBTTagCompound before = storage.load();
        int events = guard.breaks + guard.places;
        NBTTagCompound retired = wireReply(player, candidate, capture);
        SimpleAssert.that("bounds_protocol_required".equals(retired.getString("code"))
            && !retired.hasKey("entries") && !retired.hasKey("preview") && HologramCutProtocol.boundsProof(retired) == null,
            "old client gets a refusal only, never complete geometry or a capability from a write");
        HologramCutProtocol.writeBoundsProof(candidate, old);
        int count = capture.parts;
        enqueueCut(player, candidate);
        HologramWorkspace.publishActionDimension(player);
        wireTick();
        SimpleAssert.eq(count, capture.parts, "old ingress lease dies even after a same-world generation change");

        NBTTagCompound buffered = wireReply(player, candidate, capture);
        SimpleAssert.eq("bounds_protocol_required", buffered.getString("code"),
            "an old buffered packet arriving on the new generation cannot reuse its capability");

        enqueueCut(player, request("list"));
        cutTaskTick(); // Build the exact old endpoint's response but hold it before its first fragment.
        count = capture.parts;
        HologramWorkspace.publishActionDimension(player);
        cutTransportTick();
        SimpleAssert.eq(count, capture.parts, "late egress cannot emit an old endpoint's LIST fragment");
        SimpleAssert.that(storage.load().equals(before) && world.getTileEntity(6, 200, 0) == original
            && events == guard.breaks + guard.places,
            "unproved and retired packets do not author a clipboard or run permission events");
        UUID current = HologramCutProtocol.boundsProof(wireReply(player, request("list"), capture));
        SimpleAssert.that(current != null && !current.equals(old), "fresh genuine LIST negotiates a new generation");
        return current;
    }

    private static UUID staleBreakAndHolder(Scene world, Actor player, Guard guard, HologramCutStorage storage,
        CutCapture capture, UUID proof) throws Exception {
        NBTTagCompound cut = denseCut(6, 6);
        HologramCutProtocol.writeBoundsProof(cut, proof);
        TileEntity original = world.getTileEntity(6, 200, 0);
        NBTTagCompound before = storage.load();
        int breaks = guard.breaks;
        player.capabilities.allowEdit = false;
        enqueueCut(player, cut);
        player.capabilities.allowEdit = true;
        wireTick();
        SimpleAssert.that("failed".equals(capture.reply.getString("code")) && guard.breaks == breaks,
            "an ingress that lacked edit authority cannot borrow it before its queued write");
        player.inventory.mainInventory[1] = player.getHeldItem().copy();
        try {
            enqueueCut(player, cut);
            player.inventory.currentItem = 1;
            wireTick();
            SimpleAssert.eq("failed", capture.reply.getString("code"), "queued Cut cannot adopt another held stack/slot");
            SimpleAssert.eq(breaks, guard.breaks, "a replacement projector never reaches Cut callbacks");
        } finally {
            player.inventory.currentItem = 0;
            player.inventory.mainInventory[1] = null;
        }
        int replies = capture.parts;
        guard.breakHook = () -> HologramWorkspace.publishActionDimension(player);
        try {
            wire(player, cut);
        } finally {
            guard.breakHook = null;
        }
        SimpleAssert.that(guard.breaks == breaks + 1 && capture.parts == replies
            && world.getTileEntity(6, 200, 0) == original && storage.load().equals(before),
            "expiry during a real BreakEvent cannot save a redo, clear the source, or emit stale egress");
        UUID current = HologramCutProtocol.boundsProof(wireReply(player, request("list"), capture));
        SimpleAssert.that(current != null && !current.equals(proof), "the next genuine LIST binds the new endpoint only");
        return current;
    }

    private static void lateLoadedSlotReplacement(Scene world, Actor player, Guard guard,
        HologramCutStorage storage, CutCapture capture, UUID proof) throws Exception {
        // Two actual occupied cells and callbacks, each on a different loaded chunk.
        int left = 47, right = 48;
        world.put(left, 200, 0, Blocks.stone, 0);
        world.put(right, 200, 0, Blocks.stone, 0);
        Chunk original = world.getChunkFromChunkCoords(left >> 4, 0);
        Chunk later = world.getChunkFromChunkCoords(right >> 4, 0);
        Chunk foreign = new Chunk(world, left >> 4, 0);
        ExtendedBlockStorage section = new ExtendedBlockStorage(192, false);
        foreign.getBlockStorageArray()[200 >> 4] = section;
        section.func_150818_a(left & 15, 200 & 15, 0, Blocks.gold_block);
        NBTTagCompound before = storage.load();
        NBTTagCompound action = denseCut(left, right);
        HologramCutProtocol.writeBoundsProof(action, proof);
        int[] callbacks = { 0 };
        guard.breakHook = () -> {
            if (++callbacks[0] == 2) {
                world.fixtureMount(foreign);
                world.staleVirtualChunk = original; // Deliberate stale World view, never authoritative.
            }
        };
        try {
            NBTTagCompound response = wireReply(player, action, capture);
            SimpleAssert.that(callbacks[0] == 2 && !response.getBoolean("success") && !response.hasKey("preview")
                && storage.load().equals(before) && !storage.load().hasKey("pending"),
                "a later native protection callback replacing an earlier loaded slot cannot author rollback/redo");
            SimpleAssert.that(world.getChunkFromChunkCoords(left >> 4, 0) == original
                && world.fixtureLoaded(left >> 4, 0) == foreign && original.isChunkLoaded
                && original.getBlock(left & 15, 200, 0) == Blocks.stone
                && foreign.getBlock(left & 15, 200, 0) == Blocks.gold_block
                && later.getBlock(right & 15, 200, 0) == Blocks.stone,
                "do not touch the detached original, the extant replacement, or the later selected chunk");
        } finally {
            guard.breakHook = null;
            world.staleVirtualChunk = null;
            world.fixtureMount(original);
        }
    }

    private static UUID permissionsAndRollback(Scene world, Actor player, Guard guard, HologramCutStorage storage,
        CutCapture capture, UUID proof) throws Exception {
        ObservedChest source = chest(world, 0, Items.emerald, 3);
        NBTTagCompound cut = request("cut");
        cut.setInteger("x1", 0);
        cut.setInteger("y1", 200);
        cut.setInteger("z1", 0);
        cut.setInteger("x2", 1); // Selected air must also be protected at the destination.
        cut.setInteger("y2", 200);
        cut.setInteger("z2", 0);
        HologramCutProtocol.writeBoundsProof(cut, proof);
        player.capabilities.allowEdit = false;
        wire(player, cut);
        SimpleAssert.that(world.getTileEntity(0, 200, 0) == source, "no-edit wire request preserves the original tile");
        SimpleAssert.eq(
            0,
            storage.load()
                .getTagList("requests", 10)
                .tagCount(),
            "denial records no nonce");
        player.capabilities.allowEdit = true;
        player.editable = false;
        wire(player, cut);
        SimpleAssert.that(world.getTileEntity(0, 200, 0) == source, "canPlayerEdit denial preserves source and nonce");
        player.editable = true;
        guard.cancelBreak = true;
        wire(player, cut);
        SimpleAssert.eq(0, source.invalidations, "BreakEvent cancellation never tears down the source");
        SimpleAssert.eq(
            0,
            storage.load()
                .getTagList("entries", 10)
                .tagCount(),
            "cancelled cut creates no clipboard entry");
        guard.cancelBreak = false;
        world.deniedX = 1;
        wire(player, cut);
        SimpleAssert.eq(
            0,
            storage.load()
                .getTagList("requests", 10)
                .tagCount(),
            "protected selected air cannot consume a nonce");
        world.deniedX = Integer.MIN_VALUE;
        NBTTagCompound cutAccepted = wireReply(player, cut, capture);
        SimpleAssert.that(cutAccepted.getBoolean("success") && !cutAccepted.hasKey("preview")
            && !cutAccepted.hasKey("cells"), "current new-client Cut keeps only a bounded reply");
        SimpleAssert
            .eq(Blocks.air, world.getBlock(0, 200, 0), "ordinary non-OP wire cut removes its authorized source");
        SimpleAssert.eq(
            1,
            storage.load()
                .getTagList("entries", 10)
                .tagCount(),
            "one successful cut produces one entry");
        SimpleAssert.eq(1, source.invalidations, "native teardown occurs only for the approved cut");
        NBTTagCompound retained = storage.load().getTagList("entries", 10).getCompoundTagAt(0);
        exactShape(retained, 2, 2, "ordinary dense cut");
        int events = guard.breaks;
        SimpleAssert.eq(
            "already_completed",
            HologramCutService.execute(player, cut)
                .getString("code"),
            "duplicate cut is acknowledged");
        SimpleAssert.eq(events, guard.breaks, "duplicate acknowledgement never repeats permission events");

        ObservedChest target = chest(world, 8, Items.diamond, 9);
        world.put(9, 200, 0, Blocks.stone, 0);
        NBTTagCompound paste = request("paste");
        paste.setString("id", cut.getString("requestId"));
        paste.setInteger("originX", 8);
        paste.setInteger("originY", 200);
        paste.setInteger("originZ", 0);
        NBTTagCompound before = storage.load();
        NBTTagCompound directory = HologramCutService.execute(player, request("list"));
        SimpleAssert.that(directory.getBoolean("success"), "completed clipboard entries remain readable");
        NBTTagCompound row = directory.getTagList("entries", 10).getCompoundTagAt(0);
        SimpleAssert.eq(cut.getString("requestId"), row.getString("id"), "metadata refers to the exact retained entry");
        SimpleAssert.that(row.hasKey("sequence", 4) && row.getLong("sequence") > 0
            && !row.hasKey("cells") && !row.hasKey("preview"), "directory exposes typed revision, not raw cells");
        NBTTagCompound preview = request("preview");
        preview.setString("id", cut.getString("requestId"));
        NBTTagCompound refused = HologramCutService.execute(player, preview);
        SimpleAssert.eq("preview_metadata_only", refused.getString("code"), "legacy full preview has a truthful refusal");
        SimpleAssert.that(!refused.getBoolean("success") && !refused.hasKey("preview")
            && !refused.hasKey("entries"), "legacy preview cannot fall back to raw output or another query");
        int breaksBeforeProof = guard.breaks;
        SimpleAssert.that(!HologramCutService.execute(player, paste).getBoolean("success"),
            "missing sequence cannot place a retained entry");
        paste.setInteger("entrySequence", (int) row.getLong("sequence"));
        SimpleAssert.that(!HologramCutService.execute(player, paste).getBoolean("success"),
            "same numeric sequence with the wrong tag type cannot place");
        paste.setLong("entrySequence", row.getLong("sequence") + 1);
        SimpleAssert.that(!HologramCutService.execute(player, paste).getBoolean("success"),
            "stale sequence cannot place a retained entry");
        SimpleAssert.eq(breaksBeforeProof, guard.breaks, "wrong source proof fails before target permission events");
        unchanged(world, target, storage, before);
        paste.setLong("entrySequence", row.getLong("sequence"));
        HologramCutProtocol.writeBoundsProof(paste, proof);
        int oldReplies = capture.parts;
        int oldPlaces = guard.places;
        guard.placeHook = () -> HologramWorkspace.publishActionDimension(player);
        try {
            wire(player, paste);
        } finally {
            guard.placeHook = null;
        }
        SimpleAssert.that(guard.places == oldPlaces + 1 && capture.parts == oldReplies,
            "owned stale PlaceEvent rolls back, but cannot authorize redo or reply on another generation");
        unchanged(world, target, storage, before);
        proof = HologramCutProtocol.boundsProof(wireReply(player, request("list"), capture));
        SimpleAssert.that(proof != null, "paste retry uses the new exact current generation");
        HologramCutProtocol.writeBoundsProof(paste, proof);
        guard.sawCandidate = false;
        guard.sawDurableRollback = false;
        world.put(0, 200, 0, Blocks.stone, 0);
        NBTTagCompound undo = request("undo");
        undo.setString("id", cut.getString("requestId"));
        guard.cancelBreak = true;
        SimpleAssert.that(
            !HologramCutService.execute(player, undo)
                .getBoolean("success"),
            "undo cannot overwrite a protected occupied origin");
        SimpleAssert.eq(Blocks.stone, world.getBlock(0, 200, 0), "rejected undo keeps the occupied origin");
        SimpleAssert.that(before.equals(storage.load()), "rejected undo leaves its clipboard entry unconsumed");
        guard.cancelBreak = false;
        world.put(0, 200, 0, Blocks.air, 0);
        world.deniedX = 9;
        SimpleAssert.that(
            !HologramCutService.execute(player, paste)
                .getBoolean("success"),
            "selected-air overwrite obeys destination protection");
        SimpleAssert.that(
            before.equals(storage.load()),
            "denied destination preserves the entire durable clipboard and nonce list");
        world.deniedX = Integer.MIN_VALUE;
        guard.cancelPlace = true;
        SimpleAssert.that(
            !HologramCutService.execute(player, paste)
                .getBoolean("success"),
            "actual PlaceEvent veto rejects paste");
        unchanged(world, target, storage, before);
        SimpleAssert.that(
            guard.sawCandidate,
            "native PlaceEvent reads the actual candidate block and inventory in the real world");
        SimpleAssert.that(guard.sawDurableRollback, "before-image is durable before the candidate becomes visible");
        guard.cancelPlace = false;
        guard.throwPlace = true;
        SimpleAssert.that(
            !HologramCutService.execute(player, paste)
                .getBoolean("success"),
            "PlaceEvent exception rejects paste");
        unchanged(world, target, storage, before);
        guard.throwPlace = false;
        exactStageChunks(world, before.getTagList("entries", 10).getCompoundTagAt(0), target);

        int[] revoked = { 0 };
        guard.placeHook = () -> player.editHook = () -> {
            if (world.getTileEntity(8, 200, 0) == target) {
                revoked[0]++;
                player.capabilities.allowEdit = false;
            }
        };
        try {
            NBTTagCompound lost = wireReply(player, paste, capture);
            SimpleAssert.that(revoked[0] == 1 && !lost.getBoolean("success"),
                "the last virtual canPlayerEdit may return true after revoking allowEdit; the pure tail refuses redo");
        } finally {
            guard.placeHook = null;
            player.editHook = null;
            player.capabilities.allowEdit = true;
        }
        unchanged(world, target, storage, before);

        guard.cancelPlace = true;
        guard.failCleanup = true;
        SimpleAssert.eq(
            "recovery_pending",
            wireReply(player, paste, capture).getString("code"),
            "failed journal cleanup retains rollback evidence");
        guard.failCleanup = false;
        guard.cancelPlace = false;
        Files.delete(guard.blockedTemporary);
        Path journalFile = world.save.getWorldDirectory()
            .toPath()
            .resolve("gtmb-cut")
            .resolve(player.getUniqueID() + ".nbt");
        NBTTagCompound retainedRollback = storage.load().getCompoundTag("pending");
        exactShape(retainedRollback.getCompoundTag("entry"), 2, 2, "durable dense rollback before-image");
        exactShape(retainedRollback.getCompoundTag("candidate"), 2, 2, "durable dense rollback candidate");
        byte[] blockedJournal = Files.readAllBytes(journalFile);
        NBTTagCompound passive = wireReply(player, request("list"), capture);
        NBTTagCompound wrong = request("list");
        wrong.setString("id", UUID.randomUUID().toString());
        NBTTagCompound wrongReply = wireReply(player, wrong, capture);
        SimpleAssert.that("recovery_pending".equals(passive.getString("code")) && !passive.hasKey("entries")
            && "recovery_pending".equals(wrongReply.getString("code")) && !wrongReply.hasKey("entries")
            && Arrays.equals(blockedJournal, Files.readAllBytes(journalFile)),
            "passive LIST and mismatched target do not recover or disclose a pending journal");
        target.getStackInSlot(0).stackSize = 10; // The restored live inventory changes on a later ordinary tick.
        boolean blocked = false;
        try {
            HologramCutService.recover(storage, storage.load());
        } catch (IOException changed) {
            blocked = changed.getMessage()
                .contains("前后图之外");
        }
        SimpleAssert.that(blocked, "a third live state blocks stale rollback instead of overwriting newer inventory");
        SimpleAssert.that(
            world.getTileEntity(8, 200, 0) == target,
            "blocked recovery preserves the current original tile identity");
        SimpleAssert.eq(10, target.getStackInSlot(0).stackSize, "blocked recovery never restores the older item count");
        SimpleAssert.that(
            Arrays.equals(blockedJournal, Files.readAllBytes(journalFile)),
            "blocked recovery preserves its journal byte for byte");
        target.getStackInSlot(0).stackSize = 9; // Restore this isolated fixture's known before-state.
        NBTTagCompound exact = request("list");
        exact.setString("id", paste.getString("requestId"));
        NBTTagCompound checked = wireReply(player, exact, capture);
        SimpleAssert.that(checked.getBoolean("success") && !checked.getBoolean("requestCompleted")
            && paste.getString("requestId").equals(checked.getString("reconciledRequestId"))
            && !checked.hasKey("preview"), "current same-endpoint targeted LIST reconciles only its authorized rollback");
        unchanged(world, target, storage, before);

        NBTTagCompound entry = before.getTagList("entries", 10)
            .getCompoundTagAt(0);
        HologramCutWorld.Edit interrupted = new HologramCutWorld.Edit(world, entry, 8, 200, 0);
        NBTTagCompound rollback = pending(interrupted.before, 8, false, null);
        rollback.setTag("candidate", entry.copy());
        storage.markPending(
            rollback,
            "paste",
            UUID.randomUUID()
                .toString(),
            false);
        NBTTagCompound crashed = (NBTTagCompound) before.copy();
        crashed.setTag("pending", rollback);
        storage.save(crashed);
        interrupted.stage(entry);
        int permissionEvents = guard.breaks + guard.places;
        HologramCutService.recover(storage, storage.load());
        SimpleAssert
            .eq(permissionEvents, guard.breaks + guard.places, "rollback-only restart dispatches no permission events");
        SimpleAssert.eq(
            9,
            ((ObservedChest) world.getTileEntity(8, 200, 0)).getStackInSlot(0).stackSize,
            "restart restores before-image inventory");
        SimpleAssert.that(before.equals(storage.load()), "rollback-only recovery never consumes clipboard or nonce");

        SimpleAssert.that(
            HologramCutService.execute(player, paste)
                .getBoolean("success"),
            "an ordinary player can paste an authorized device");
        SimpleAssert.eq(
            3,
            ((ObservedChest) world.getTileEntity(8, 200, 0)).getStackInSlot(0).stackSize,
            "approved paste preserves source inventory");
        SimpleAssert.eq(Blocks.air, world.getBlock(9, 200, 0), "authorized selected air clears the destination");
        SimpleAssert.eq(
            0,
            storage.load()
                .getTagList("entries", 10)
                .tagCount(),
            "successful paste consumes exactly one entry");

        NBTTagCompound recut = HologramCutService.execute(player, denseCut(8, 9));
        SimpleAssert.that(recut.getBoolean("success"), "dense recut produces an undoable journal");
        exactShape(storage.load().getTagList("entries", 10).getCompoundTagAt(0), 2, 2, "dense undo input");
        NBTTagCompound restore = request("undo");
        HologramCutProtocol.writeBoundsProof(restore, proof);
        NBTTagCompound undoBefore = storage.load();
        int undoBreaks = guard.breaks, undoPlaces = guard.places;
        SimpleAssert.that(!wireReply(player, restore, capture).getBoolean("success"),
            "real Undo never defaults to the first clipboard row");
        restore.setString("id", recut.getString("id"));
        long sequence = recut.getTagList("entries", 10).getCompoundTagAt(0).getLong("sequence");
        restore.setInteger("entrySequence", (int) sequence);
        SimpleAssert.that(!wireReply(player, restore, capture).getBoolean("success"),
            "real Undo refuses a coercible integer sequence");
        restore.setLong("entrySequence", sequence + 1);
        SimpleAssert.that(!wireReply(player, restore, capture).getBoolean("success") && undoBefore.equals(storage.load())
            && guard.breaks == undoBreaks && guard.places == undoPlaces,
            "stale Undo proof runs no permission callbacks and leaves the complete durable clipboard");
        restore.setLong("entrySequence", sequence);
        SimpleAssert.that(wireReply(player, restore, capture).getBoolean("success"), "current exact Undo still works");
        SimpleAssert.eq(3, ((ObservedChest) world.getTileEntity(8, 200, 0)).getStackInSlot(0).stackSize,
            "dense undo restores the original inventory");
        return proof;
    }

    private static void knownVanillaPose(Scene world, Actor player, Guard guard, HologramCutStorage storage,
        CutCapture capture, UUID proof) throws Exception {
        int sourceX = 72, targetX = 76;
        chest(world, sourceX, Items.emerald, 3); // Actual captured vanilla chest is facing SOUTH (metadata 3).
        ObservedChest target = chest(world, targetX, Items.diamond, 9);
        NBTTagCompound cut = denseCut(sourceX, sourceX);
        HologramCutProtocol.writeBoundsProof(cut, proof);
        NBTTagCompound captured = wireReply(player, cut, capture);
        SimpleAssert.that(captured.getBoolean("success"), "real current-peer vanilla source captured");
        String id = captured.getString("id");
        NBTTagCompound paste = request("paste");
        paste.setString("id", id);
        HologramCutProtocol.writeBoundsProof(paste, proof);
        var rows = captured.getTagList("entries", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.getString("id").equals(id) && row.hasKey("sequence", 4))
                paste.setLong("entrySequence", row.getLong("sequence"));
        }
        SimpleAssert.that(paste.getLong("entrySequence") > 0, "Paste uses the genuine selected row proof");
        paste.setInteger("originX", targetX);
        paste.setInteger("originY", 200);
        paste.setInteger("originZ", 0);
        new com.miaokatze.gtmb.api.CutTransforms.Transform(0, 1, 0, 0).write(paste);

        Path file = world.save.getWorldDirectory().toPath().resolve("gtmb-cut").resolve(player.getUniqueID() + ".nbt");
        byte[] retained = Files.readAllBytes(file);
        NBTTagCompound before = storage.load();
        int mine = world.mineChecks, breaks = guard.breaks, places = guard.places;
        int[] playerPermission = { 0 };
        NBTTagCompound refused;
        world.forbidChunkLookup = true;
        player.editHook = () -> playerPermission[0]++;
        try {
            refused = wireReply(player, paste, capture);
        } finally {
            player.editHook = null;
            world.forbidChunkLookup = false;
        }
        SimpleAssert.that(!refused.getBoolean("success") && refused.getString("message").contains("minecraft:chest")
            && world.mineChecks == mine && playerPermission[0] == 0 && guard.breaks == breaks
            && guard.places == places,
            "known impossible tilt gives an honest real refusal before permissions, world lookup or Forge events");
        SimpleAssert.that(world.getTileEntity(targetX, 200, 0) == target && target.getStackInSlot(0).stackSize == 9
            && world.getBlockMetadata(targetX, 200, 0) == 3 && before.equals(storage.load())
            && Arrays.equals(retained, Files.readAllBytes(file)), "refused tilt is byte-exact and nonce-neutral");

        // Same request id is still unused. SOUTH reflected in Z and yawed once is EAST (metadata 5).
        new com.miaokatze.gtmb.api.CutTransforms.Transform(1, 0, 0, 4).write(paste);
        NBTTagCompound placed = wireReply(player, paste, capture);
        SimpleAssert.that(placed.getBoolean("success") && world.getBlockMetadata(targetX, 200, 0) == 5
            && ((ObservedChest) world.getTileEntity(targetX, 200, 0)).getStackInSlot(0).stackSize == 3,
            "a legal yaw and mirror can use the original nonce and commit the exact native heading and contents");
        SimpleAssert.eq(0, storage.load().getTagList("entries", 10).tagCount(),
            "only the admitted pose consumes its untouched source row");
    }

    private static NBTTagCompound denseCut(int min, int max) {
        NBTTagCompound cut = request("cut");
        cut.setInteger("x1", min);
        cut.setInteger("x2", max);
        cut.setInteger("y1", 200);
        cut.setInteger("y2", 200);
        cut.setInteger("z1", 0);
        cut.setInteger("z2", 0);
        return cut;
    }

    private static NBTTagCompound compoundCut(int left) throws IOException {
        HologramAreaSelection area = new HologramAreaSelection();
        area.point(1, left, 200, 0, "replace");
        area.point(2, left + 2, 200, 0, "replace");
        area.point(1, left + 1, 200, 0, "subtract");
        area.point(2, left + 1, 200, 0, "subtract");
        NBTTagCompound cut = request("cut");
        cut.setTag("areaSelection", area.snapshot());
        return cut;
    }

    private static void exactShape(NBTTagCompound entry, int width, int selected, String phase) throws IOException {
        HologramCutEntry.validate(entry);
        SimpleAssert.that(entry.hasKey("selectionVersion", 3) && entry.getInteger("selectionVersion") == 1,
            phase + " retains typed selection version");
        SimpleAssert.eq(width, entry.getInteger("sizeX"), phase + " retains envelope");
        SimpleAssert.eq(selected, entry.getInteger("selectedCells"), phase + " retains selected cardinality");
        SimpleAssert.eq(selected, entry.getTagList("cells", 10).tagCount(), phase + " retains exact cells");
    }

    private static void compoundJournal(Scene world, Actor player, Guard guard, HologramCutStorage storage,
        CutCapture capture, UUID proof) throws Exception {
        chest(world, 2, Items.gold_ingot, 4);
        world.put(3, 200, 0, Blocks.stone, 0);
        world.put(4, 200, 0, Blocks.air, 0);
        ObservedChest target = chest(world, 12, Items.diamond, 7);
        world.put(13, 200, 0, Blocks.stone, 0);
        world.put(14, 200, 0, Blocks.stone, 0);
        NBTTagCompound cut = HologramCutService.execute(player, compoundCut(2));
        SimpleAssert.that(cut.getBoolean("success"), "real compound cut succeeds with a subtracted hole");
        SimpleAssert.eq(Blocks.stone, world.getBlock(3, 200, 0), "compound cut leaves the subtracted source");
        NBTTagCompound before = storage.load();
        NBTTagCompound source = before.getTagList("entries", 10).getCompoundTagAt(0);
        exactShape(source, 3, 2, "compound cut journal");
        NBTTagCompound row = cut.getTagList("entries", 10).getCompoundTagAt(0);
        SimpleAssert.eq(source.getString("id"), row.getString("id"), "compound wire summary refers to the journal");
        SimpleAssert.that(row.hasKey("sequence", 4) && !row.hasKey("selectionVersion") && !row.hasKey("cells"),
            "compound wire summary does not expose journal fields or raw cells");

        NBTTagCompound paste = request("paste");
        paste.setString("id", row.getString("id"));
        paste.setLong("entrySequence", row.getLong("sequence"));
        HologramCutProtocol.writeBoundsProof(paste, proof);
        paste.setInteger("originX", 12);
        paste.setInteger("originY", 200);
        paste.setInteger("originZ", 0);
        guard.cancelPlace = true;
        guard.failCleanup = true;
        try {
            SimpleAssert.eq("recovery_pending", wireReply(player, paste, capture).getString("code"),
                "compound PlaceEvent veto and interrupted cleanup leave an actual rollback journal");
        } finally {
            guard.failCleanup = false;
            guard.cancelPlace = false;
        }
        Files.delete(guard.blockedTemporary);
        NBTTagCompound rollbackState = storage.load();
        NBTTagCompound journal = rollbackState.getCompoundTag("pending");
        exactShape(journal.getCompoundTag("entry"), 3, 2, "durable compound rollback preimage");
        exactShape(journal.getCompoundTag("candidate"), 3, 2, "durable compound rollback candidate");
        HologramWorkspace.publishActionDimension(player);
        NBTTagCompound exact = request("list");
        exact.setString("id", paste.getString("requestId"));
        SimpleAssert.that("recovery_pending".equals(wireReply(player, exact, capture).getString("code"))
            && rollbackState.equals(storage.load()), "even the exact old receipt cannot recover on a new endpoint");
        HologramCutService.recover(storage, rollbackState);
        SimpleAssert.that(world.getTileEntity(12, 200, 0) == target && target.getStackInSlot(0).stackSize == 7
            && Blocks.stone == world.getBlock(14, 200, 0) && before.equals(storage.load()),
            "compound paste veto restores selected preimage, inventory, and exact journal");
        SimpleAssert.that(HologramCutService.execute(player, paste).getBoolean("success"),
            "authorized compound paste still works");
        SimpleAssert.eq(Blocks.stone, world.getBlock(13, 200, 0), "compound paste does not fill a subtracted cell");
        SimpleAssert.eq(Blocks.air, world.getBlock(14, 200, 0), "compound paste still overwrites selected air");

        NBTTagCompound recut = HologramCutService.execute(player, compoundCut(12));
        SimpleAssert.that(recut.getBoolean("success"), "compound recut provides the original undo coordinates");
        exactShape(storage.load().getTagList("entries", 10).getCompoundTagAt(0), 3, 2, "compound undo journal");
        NBTTagCompound undo = request("undo");
        undo.setString("id", recut.getString("id"));
        SimpleAssert.that(HologramCutService.execute(player, undo).getBoolean("success"), "compound undo succeeds");
        SimpleAssert.eq(4, ((ObservedChest) world.getTileEntity(12, 200, 0)).getStackInSlot(0).stackSize,
            "compound undo restores exact original inventory");
        SimpleAssert.eq(Blocks.stone, world.getBlock(13, 200, 0), "compound undo leaves the subtracted cell");
    }

    private static void exactStageChunks(Scene world, NBTTagCompound shape, ObservedChest original) throws Exception {
        Chunk chunk = world.getChunkFromChunkCoords(0, 0);
        ChunkPosition pos = new ChunkPosition(8, 200, 0);
        HologramCutWorld.Edit edit = new HologramCutWorld.Edit(world, shape, 8, 200, 0);
        try {
            edit.stage(shape, () -> {}, () -> world.forbidChunkLookup = true);
            SimpleAssert.that(chunk.chunkTileEntityMap.get(pos) instanceof ObservedChest candidate
                && candidate != original && candidate.getStackInSlot(0).stackSize == 3,
                "physical staging uses only the already bound original chunk after the last fence");
        } finally {
            world.forbidChunkLookup = false;
            edit.restoreOwned(shape);
        }
        SimpleAssert.that(chunk.chunkTileEntityMap.get(pos) == original,
            "owned restoration uses the exact original chunk and tile");
        HologramCutWorld.Edit swapped = new HologramCutWorld.Edit(world, shape, 8, 200, 0);
        Chunk foreign = new Chunk(world, 0, 0);
        world.fixtureMount(foreign);
        boolean refused = false;
        try {
            swapped.stage(shape, () -> {}, () -> { throw new AssertionError("foreign chunk reached the final fence"); });
        } catch (IOException expected) {
            refused = expected.getMessage().contains("原区块");
        } finally {
            world.fixtureMount(chunk);
        }
        SimpleAssert.that(refused && chunk.chunkTileEntityMap.get(pos) == original
            && foreign.getBlock(8, 200, 0) == Blocks.air && foreign.chunkTileEntityMap.isEmpty(),
            "a changed chunk identity is rejected before the final fence or mutation of either chunk");
    }

    private enum Contest {
        EXPIRED_FOREIGN, CURRENT_FOREIGN, CANDIDATE_NBT, PARKED_NBT
    }

    private static void contestedStages(sun.misc.Unsafe unsafe, Scene world, Guard guard, Field channel,
        CutCapture originalCapture) throws Exception {
        for (Contest contest : Contest.values()) {
            Actor other = actor(unsafe, world, UUID.randomUUID());
            other.capabilities.allowEdit = true;
            CutCapture wire = new CutCapture(other);
            channel.set(null, wire);
            try {
                HologramWorkspace.publishActionEndpoint(other, false);
                contestedPlace(world, other, guard,
                    new HologramCutStorage(world.save.getWorldDirectory().toPath(), other.getUniqueID()), wire, contest);
            } finally {
                HologramWorkspace.revokeActionEndpoint(other);
                HologramCutService.disconnected(other);
                other.playerNetServerHandler.netManager.channel().close();
                channel.set(null, originalCapture);
            }
        }
    }

    private static void contestedPlace(Scene world, Actor player, Guard guard, HologramCutStorage storage,
        CutCapture capture, Contest contest) throws Exception {
        UUID proof = HologramCutProtocol.boundsProof(wireReply(player, request("list"), capture));
        SimpleAssert.that(proof != null, "contested fixture uses actual current bounds capability");
        int sourceX = 16 + contest.ordinal() * 4, targetX = sourceX + 2;
        chest(world, sourceX, Items.gold_ingot, 3);
        ObservedChest original = chest(world, targetX, Items.diamond, 8);
        NBTTagCompound cut = denseCut(sourceX, sourceX);
        HologramCutProtocol.writeBoundsProof(cut, proof);
        NBTTagCompound captured = wireReply(player, cut, capture);
        SimpleAssert.that(captured.getBoolean("success"), "real contested fixture clipboard was captured");
        String selected = captured.getString("id");
        NBTTagCompound paste = request("paste");
        paste.setString("id", selected);
        HologramCutProtocol.writeBoundsProof(paste, proof);
        var rows = captured.getTagList("entries", 10);
        for (int i = 0; i < rows.tagCount(); i++)
            if (selected.equals(rows.getCompoundTagAt(i).getString("id")))
                paste.setLong("entrySequence", rows.getCompoundTagAt(i).getLong("sequence"));
        SimpleAssert.that(paste.getLong("entrySequence") > 0, "proof uses the actual captured clipboard row");
        paste.setInteger("originX", targetX);
        paste.setInteger("originY", 200);
        paste.setInteger("originZ", 0);
        Path file = world.save.getWorldDirectory().toPath().resolve("gtmb-cut")
            .resolve(player.getUniqueID() + ".nbt");
        byte[][] durable = { null };
        ObservedChest[] observed = { null };
        int replies = capture.parts;
        guard.parkedOriginal = original;
        guard.changeCandidate = contest == Contest.CANDIDATE_NBT;
        guard.changeOriginal = contest == Contest.PARKED_NBT;
        guard.placeHook = () -> {
            try {
                durable[0] = Files.readAllBytes(file);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            if (contest == Contest.EXPIRED_FOREIGN) HologramWorkspace.publishActionDimension(player);
            if (contest == Contest.EXPIRED_FOREIGN || contest == Contest.CURRENT_FOREIGN)
                observed[0] = chest(world, targetX, Items.emerald, 11);
            else observed[0] = (ObservedChest) world.getTileEntity(targetX, 200, 0);
        };
        try {
            wire(player, paste);
        } finally {
            guard.placeHook = null;
            guard.parkedOriginal = null;
            guard.changeCandidate = false;
            guard.changeOriginal = false;
        }
        int expected = contest == Contest.CANDIDATE_NBT ? 4 : contest == Contest.PARKED_NBT ? 3 : 11;
        SimpleAssert.that(durable[0] != null && observed[0] != null
            && world.getTileEntity(targetX, 200, 0) == observed[0]
            && observed[0].getStackInSlot(0).stackSize == expected
            && original.getStackInSlot(0).stackSize == (contest == Contest.PARKED_NBT ? 9 : 8)
            && Arrays.equals(durable[0], Files.readAllBytes(file)),
            contest + ": preserve live/parked evidence and the exact original rollback bytes");
        if (contest == Contest.EXPIRED_FOREIGN) {
            SimpleAssert.eq(replies, capture.parts, "expired endpoint never receives the refusal");
        } else {
            SimpleAssert.that(capture.parts == replies + 1 && "recovery_pending".equals(capture.reply.getString("code"))
                && paste.getString("requestId").equals(capture.reply.getString("requestId"))
                && !capture.reply.hasKey("entries") && !capture.reply.hasKey("preview"),
                contest + ": current peer gets only the honest pending result, never raw or success");
        }
        NBTTagCompound state = storage.load();
        SimpleAssert.that(!storage.approvedPending(state.getCompoundTag("pending"))
            && state.getTagList("entries", 10).tagCount() == 1 && state.getTagList("requests", 10).tagCount() == 1,
            contest + ": never promote to redo or consume the source row/operation receipt");
    }

    private static void unchanged(Scene world, ObservedChest target, HologramCutStorage storage, NBTTagCompound before)
        throws IOException {
        SimpleAssert
            .that(world.getTileEntity(8, 200, 0) == target, "veto restores the same original native tile instance");
        SimpleAssert.eq(9, target.getStackInSlot(0).stackSize, "veto preserves original target inventory");
        SimpleAssert.eq(0, target.invalidations, "veto does not invalidate the original tile");
        SimpleAssert.eq(0, target.validations, "temporary preparation does not validate a native tile");
        SimpleAssert.that(world.loadedTileEntityList.contains(target), "veto restores original loaded membership");
        SimpleAssert.eq(Blocks.stone, world.getBlock(9, 200, 0), "veto restores the selected-air overwrite target");
        SimpleAssert.that(before.equals(storage.load()), "veto leaves clipboard, pending state and nonces unchanged");
    }

    private static void recovery(Scene world, Actor player, Guard guard, HologramCutStorage storage) throws Exception {
        ObservedChest source = chest(world, 0, Items.redstone, 6);
        NBTTagCompound entry = HologramCutEntry.capture(world, 0, 200, 0, 1, 1, 1);
        String id = UUID.randomUUID()
            .toString();
        entry.setString("id", id);
        NBTTagCompound state = storage.load();
        state.getTagList("entries", 10)
            .appendTag(entry);
        NBTTagCompound done = new NBTTagCompound();
        done.setString("id", id);
        state.getTagList("requests", 10)
            .appendTag(done);
        NBTTagCompound journal = pending(entry, 0, true, null);
        state.setTag("pending", journal);
        storage.save(state);
        Path file = world.save.getWorldDirectory()
            .toPath()
            .resolve("gtmb-cut")
            .resolve(player.getUniqueID() + ".nbt");
        byte[] legacy = Files.readAllBytes(file);
        int events = guard.breaks + guard.places;
        NBTTagCompound list = request("list");
        SimpleAssert.that(
            !HologramCutService.execute(player, list)
                .getBoolean("success"),
            "list cannot promote a legacy pending transaction");
        SimpleAssert
            .that(Arrays.equals(legacy, Files.readAllBytes(file)), "legacy pending file is retained byte for byte");
        HologramCutService.startupRecovery();
        SimpleAssert.that(
            Arrays.equals(legacy, Files.readAllBytes(file)),
            "startup cannot promote an old pending journal either");
        SimpleAssert.that(world.getTileEntity(0, 200, 0) == source, "legacy pending cannot mutate the original world");
        storage.markPending(journal, "cut", id, true);
        storage.save(state);
        HologramCutService.recover(storage, storage.load());
        SimpleAssert.eq(
            Blocks.air,
            world.getBlock(0, 200, 0),
            "recovery replays a previously authorized ordinary-device journal");
        HologramCutService.recover(
            new HologramCutStorage(
                world.save.getWorldDirectory()
                    .toPath(),
                player.getUniqueID()),
            storage.load());
        HologramCutService.recover(storage, storage.load());
        SimpleAssert.eq(events, guard.breaks + guard.places, "approved restart never repeats authorization callbacks");
        SimpleAssert.eq(1, source.invalidations, "approved recovery tears down the source only once");
        SimpleAssert.eq(
            1,
            storage.load()
                .getTagList("entries", 10)
                .tagCount(),
            "approved recovery retains exactly one clipboard copy");
        SimpleAssert.that(
            !storage.load()
                .hasKey("pending"),
            "verified recovery clears the journal");
    }

    private static NBTTagCompound pending(NBTTagCompound entry, int x, boolean air, String consume) {
        NBTTagCompound pending = new NBTTagCompound();
        pending.setTag("entry", entry.copy());
        pending.setInteger("dimension", 0);
        pending.setInteger("x", x);
        pending.setInteger("y", 200);
        pending.setInteger("z", 0);
        pending.setBoolean("air", air);
        if (consume != null) pending.setString("consumeId", consume);
        return pending;
    }

    private static NBTTagCompound request(String action) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", action);
        request.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        return request;
    }

    private static void wire(Actor player, NBTTagCompound request) throws Exception {
        enqueueCut(player, request);
        wireTick();
    }

    private static NBTTagCompound wireReply(Actor player, NBTTagCompound request, CutCapture capture) throws Exception {
        int parts = capture.parts;
        wire(player, request);
        SimpleAssert.that(capture.parts == parts + 1 && capture.reply != null
            && request.getString("requestId").equals(capture.reply.getString("requestId")),
            "one actual matching bounded Cut fragment per fixture reply");
        return capture.reply;
    }

    private static void wireTick() {
        cutTaskTick();
        cutTransportTick();
    }

    private static void cutTaskTick() {
        ServerTaskScheduler.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
    }

    private static void cutTransportTick() {
        new HologramNetwork.TransportTicks().tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
    }

    private static void enqueueCut(Actor player, NBTTagCompound request) throws Exception {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new HologramNetwork.CutAction(request).toBytes(bytes);
            HologramNetwork.CutAction decoded = new HologramNetwork.CutAction();
            decoded.fromBytes(bytes);
            java.lang.reflect.Constructor<MessageContext> constructor = MessageContext.class
                .getDeclaredConstructor(net.minecraft.network.INetHandler.class, Side.class);
            constructor.setAccessible(true);
            new HologramNetwork.CutServerHandler()
                .onMessage(decoded, constructor.newInstance(player.playerNetServerHandler, Side.SERVER));
        } finally {
            bytes.release();
        }
    }

    private static final class CutCapture extends SimpleNetworkWrapper {

        final EntityPlayerMP owner;
        int parts, ordinary;
        NBTTagCompound reply;

        CutCapture(EntityPlayerMP owner) {
            super("gtmb_cut_" + owner.getUniqueID().toString().substring(0, 8));
            this.owner = owner;
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP player) {
            if (!(message instanceof HologramNetwork.CutFragment)) {
                if (player == owner && (message instanceof HologramNetwork.State
                    || message instanceof HologramNetwork.Fragment)) ordinary++;
                return;
            }
            SimpleAssert.that(player == owner, "Cut fragment has the exact endpoint's actor");
            ByteBuf wire = Unpooled.buffer();
            try {
                message.toBytes(wire);
                int bytes = wire.readableBytes();
                long sequence = wire.getLong(wire.readerIndex());
                SimpleAssert.that(HologramCutReplyCodec.marked(sequence) && bytes + 1 <= HologramCutReplyCodec.MAX_PACKET_BYTES,
                    "registered Cut replies always advertise the same bounded single-frame metadata version");
                HologramNetwork.CutFragment received = new HologramNetwork.CutFragment();
                received.fromBytes(wire);
                SimpleAssert.that(received.admitted() && wire.readableBytes() == 0 && bytes < 4096,
                    "the real Cut reader admits and consumes these normal tiny replies");
                reply = received.decodeReply();
                parts++;
            } catch (IOException failure) {
                throw new AssertionError("Cut response codec", failure);
            } finally {
                wire.release();
            }
        }
    }

    private static Actor actor(sun.misc.Unsafe unsafe, Scene world, UUID owner) throws Exception {
        Actor player = (Actor) unsafe.allocateInstance(Actor.class);
        for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
            field.setAccessible(true);
            field.set(player, new GameProfile(owner, "CutProtectionOwner"));
        }
        for (Field field : Entity.class.getDeclaredFields()) if (field.getType() == UUID.class) {
            field.setAccessible(true);
            field.set(player, owner);
        }
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.inventory.mainInventory[0] = new ItemStack(new ItemNekoHologramProjector());
        player.capabilities = new PlayerCapabilities();
        player.editable = true;
        NetworkManager connection = new NetworkManager(false);
        for (Field field : NetworkManager.class.getDeclaredFields()) if (field.getType() == Channel.class) {
            field.setAccessible(true);
            field.set(connection, new EmbeddedChannel(new ChannelInboundHandlerAdapter()));
        }
        new NetHandlerPlayServer(null, connection, player);
        return player;
    }

    private static ObservedChest chest(Scene world, int x, net.minecraft.item.Item item, int count) {
        world.put(x, 200, 0, Blocks.chest, 3);
        ObservedChest tile = new ObservedChest();
        tile.setWorldObj(world);
        tile.xCoord = x;
        tile.yCoord = 200;
        tile.zCoord = 0;
        tile.setInventorySlotContents(0, new ItemStack(item, count));
        world.getChunkFromChunkCoords(x >> 4, 0).chunkTileEntityMap.put(new ChunkPosition(x & 15, 200, 0), tile);
        world.loadedTileEntityList.add(tile);
        return tile;
    }

    public static final class ObservedChest extends TileEntityChest {

        int invalidations, validations;

        @Override
        public void invalidate() {
            invalidations++;
            super.invalidate();
        }

        @Override
        public void validate() {
            validations++;
            super.validate();
        }
    }

    private static final class Actor extends EntityPlayerMP {

        boolean editable;
        Runnable editHook;

        private Actor() {
            super(null, null, new GameProfile(UUID.randomUUID(), "unused"), null);
        }

        @Override
        public boolean canPlayerEdit(int x, int y, int z, int side, ItemStack item) {
            if (editHook != null) editHook.run();
            return editable;
        }
    }

    /** Inject only the missing FML event transformer boundary; production constructs the actual Forge event. */
    private static final class Guard extends EventBus {

        final Scene world;
        HologramCutWorld.NativeSaveSnapshot save;
        boolean cancelBreak, cancelPlace, throwPlace, changeCandidate, changeOriginal, failCleanup, sawCandidate,
            sawDurableRollback;
        ObservedChest parkedOriginal;
        Path blockedTemporary;
        int breaks, places, expectedContents = 3;
        Runnable breakHook, placeHook;

        Guard(Scene world) {
            this.world = world;
        }

        @Override
        public void register(Object listener) {
            if (listener instanceof HologramCutWorld.NativeSaveSnapshot)
                save = (HologramCutWorld.NativeSaveSnapshot) listener;
        }

        @Override
        public void unregister(Object listener) {
            if (save == listener) save = null;
        }

        @Override
        public boolean post(Event event) {
            if (event instanceof ChunkDataEvent.Save) {
                if (save != null) save.onSave((ChunkDataEvent.Save) event);
            } else if (event instanceof BlockEvent.BreakEvent) {
                breaks++;
                if (breakHook != null) breakHook.run();
                return cancelBreak;
            } else if (event instanceof BlockEvent.PlaceEvent) {
                places++;
                BlockEvent.PlaceEvent placed = (BlockEvent.PlaceEvent) event;
                try {
                    SimpleAssert.that(placed.world == world, "permission event uses the original real WorldServer");
                    SimpleAssert.eq(
                        world.getBlock(placed.x, placed.y, placed.z),
                        placed.placedBlock,
                        "event reports the truly installed candidate block");
                    ObservedChest tile = (ObservedChest) world.getTileEntity(placed.x, placed.y, placed.z);
                    SimpleAssert.eq(
                        expectedContents,
                        tile.getStackInSlot(0).stackSize,
                        "permission callback sees full native source inventory");
                    SimpleAssert.eq(0, tile.validations, "candidate has not received native validate before approval");
                    sawCandidate = true;
                    HologramCutStorage storage = new HologramCutStorage(
                        world.save.getWorldDirectory()
                            .toPath(),
                        placed.player.getUniqueID());
                    sawDurableRollback = !storage.approvedPending(
                        storage.load()
                            .getCompoundTag("pending"));
                    if (placeHook != null) placeHook.run();
                    if (changeCandidate) tile.getStackInSlot(0).stackSize++;
                    if (changeOriginal) parkedOriginal.getStackInSlot(0).stackSize++;
                    if (failCleanup) {
                        blockedTemporary = world.save.getWorldDirectory()
                            .toPath()
                            .resolve("gtmb-cut")
                            .resolve(placed.player.getUniqueID() + ".nbt.tmp");
                        Files.createDirectory(blockedTemporary);
                    }
                    if (throwPlace) throw new IllegalStateException("synthetic placement listener failure");
                    if (cancelPlace) {
                        Field cancelled = Event.class.getDeclaredField("isCanceled");
                        cancelled.setAccessible(true);
                        cancelled.setBoolean(event, true);
                    }
                } catch (ReflectiveOperationException | IOException failure) {
                    throw new AssertionError("synthetic Forge boundary", failure);
                }
            }
            return event.isCanceled();
        }
    }

    private static final class Writer extends AnvilChunkLoader {

        Writer(java.io.File directory) {
            super(directory);
        }

        @Override
        protected void addChunkToPending(ChunkCoordIntPair coordinate, NBTTagCompound root) {
            try (DataOutputStream output = RegionFileCache
                .getChunkOutputStream(chunkSaveLocation, coordinate.chunkXPos, coordinate.chunkZPos)) {
                CompressedStreamTools.write(root, output);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class Provider extends ChunkProviderServer {

        final Scene world;

        Provider(Scene world, Writer writer) {
            super(world, writer, null);
            this.world = world;
        }

        @Override
        public Chunk loadChunk(int x, int z) {
            return world.getChunkFromChunkCoords(x, z);
        }

        @Override
        public boolean chunkExists(int x, int z) {
            return world.fixtureLoaded(x, z) != null;
        }
    }

    private static final class Scene extends WorldServer {

        ISaveHandler save;
        int deniedX, saveReads, mineChecks;
        boolean forbidChunkLookup;
        Chunk staleVirtualChunk;

        private Scene() {
            super(null, null, "cut protection fixture", 0, null, null);
        }

        @Override
        public ISaveHandler getSaveHandler() {
            saveReads++;
            return save;
        }

        @Override
        public void checkSessionLock() {}

        @Override
        public long getTotalWorldTime() {
            return 0;
        }

        @Override
        public List<NextTickListEntry> getPendingBlockUpdates(Chunk chunk, boolean remove) {
            return null;
        }

        @Override
        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            mineChecks++;
            return x != deniedX;
        }

        Chunk fixtureLoaded(int x, int z) {
            return (Chunk) theChunkProviderServer.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x, z));
        }

        void fixtureMount(Chunk chunk) {
            // Standalone fixture only: stand in for vanilla loadChunk's backing registration/onChunkLoad.
            chunk.isChunkLoaded = true;
            theChunkProviderServer.loadedChunkHashMap.add(ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition),
                chunk);
        }

        @Override
        public Chunk getChunkFromChunkCoords(int x, int z) {
            if (forbidChunkLookup) throw new AssertionError("late virtual chunk/provider lookup");
            if (staleVirtualChunk != null && staleVirtualChunk.xPosition == x && staleVirtualChunk.zPosition == z)
                return staleVirtualChunk;
            Chunk loaded = fixtureLoaded(x, z);
            if (loaded == null) {
                loaded = new Chunk(this, x, z);
                fixtureMount(loaded);
            }
            return loaded;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return getChunkFromChunkCoords(x >> 4, z >> 4).chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return getChunkFromChunkCoords(x >> 4, z >> 4).getBlock(x & 15, y, z & 15);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return getChunkFromChunkCoords(x >> 4, z >> 4).getBlockMetadata(x & 15, y, z & 15);
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
            put(x, y, z, getBlock(x, y, z), metadata);
            return true;
        }

        @Override
        public boolean func_147451_t(int x, int y, int z) {
            return false;
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}

        @Override
        public void notifyBlockChange(int x, int y, int z, Block block) {}

        @Override
        public void func_147453_f(int x, int y, int z, Block block) {}

        void put(int x, int y, int z, Block block, int metadata) {
            ExtendedBlockStorage[] sections = getChunkFromChunkCoords(x >> 4, z >> 4).getBlockStorageArray();
            if (sections[y >> 4] == null) sections[y >> 4] = new ExtendedBlockStorage(y & ~15, false);
            sections[y >> 4].func_150818_a(x & 15, y & 15, z & 15, block);
            sections[y >> 4].setExtBlockMetadata(x & 15, y & 15, z & 15, metadata);
        }
    }
}
