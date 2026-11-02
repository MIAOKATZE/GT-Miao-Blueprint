package com.miaokatze.gtmb.hologram;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
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
            world.chunks = new HashMap<>();
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
            channel.set(null, null);
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
                permissionsAndRollback(world, player, guard, storage);
                recovery(world, player, guard, storage);
            } finally {
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
        }
    }

    private static void permissionsAndRollback(Scene world, Actor player, Guard guard, HologramCutStorage storage)
        throws Exception {
        ObservedChest source = chest(world, 0, Items.emerald, 3);
        NBTTagCompound cut = request("cut");
        cut.setInteger("x1", 0);
        cut.setInteger("y1", 200);
        cut.setInteger("z1", 0);
        cut.setInteger("x2", 1); // Selected air must also be protected at the destination.
        cut.setInteger("y2", 200);
        cut.setInteger("z2", 0);
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
        wire(player, cut); // The very same rejected request id is still usable.
        SimpleAssert
            .eq(Blocks.air, world.getBlock(0, 200, 0), "ordinary non-OP wire cut removes its authorized source");
        SimpleAssert.eq(
            1,
            storage.load()
                .getTagList("entries", 10)
                .tagCount(),
            "one successful cut produces one entry");
        SimpleAssert.eq(1, source.invalidations, "native teardown occurs only for the approved cut");
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
        try (HologramStargateRulesRegression.Mode rules = new HologramStargateRulesRegression.Mode(true)) {
            int permissionEvents = guard.breaks + guard.places;
            SimpleAssert.eq(
                HologramStargateRules.RESTRICTION_CODE,
                HologramCutService.execute(player, paste)
                    .getString("code"),
                "rules reject an otherwise valid paste before any real candidate staging");
            SimpleAssert.eq(permissionEvents, guard.breaks + guard.places, "denied paste dispatches no native events");
            unchanged(world, target, storage, before);
            SimpleAssert.that(
                HologramCutService.execute(player, request("list"))
                    .getBoolean("success"),
                "rules still permit reading a completed clipboard entry");
            NBTTagCompound preview = request("preview");
            preview.setString("id", cut.getString("requestId"));
            SimpleAssert.that(
                HologramCutService.execute(player, preview)
                    .getBoolean("success"),
                "rules still permit a detached preview of a completed clipboard entry");
            unchanged(world, target, storage, before);
        }
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
        guard.changeCandidate = true;
        SimpleAssert.that(
            !HologramCutService.execute(player, paste)
                .getBoolean("success"),
            "changed candidate contents invalidate approval");
        unchanged(world, target, storage, before);
        guard.changeCandidate = false;

        guard.parkedOriginal = target;
        guard.changeOriginal = true;
        SimpleAssert.that(
            !HologramCutService.execute(player, paste)
                .getBoolean("success"),
            "a changed parked original cannot receive approval");
        SimpleAssert
            .that(world.getTileEntity(8, 200, 0) == target, "parked-alias failure retains the original instance");
        SimpleAssert.eq(
            10,
            target.getStackInSlot(0).stackSize,
            "listener's own change is preserved rather than overwritten by the stale before-image");
        SimpleAssert.that(before.equals(storage.load()), "parked-alias failure consumes neither entry nor nonce");
        guard.changeOriginal = false;
        target.getStackInSlot(0).stackSize = 9;

        guard.cancelPlace = true;
        guard.failCleanup = true;
        SimpleAssert.eq(
            "recovery_pending",
            HologramCutService.execute(player, paste)
                .getString("code"),
            "failed journal cleanup retains rollback evidence");
        guard.failCleanup = false;
        guard.cancelPlace = false;
        Files.delete(guard.blockedTemporary);
        Path journalFile = world.save.getWorldDirectory()
            .toPath()
            .resolve("gtmb-cut")
            .resolve(player.getUniqueID() + ".nbt");
        byte[] blockedJournal = Files.readAllBytes(journalFile);
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
        HologramCutService.recover(storage, storage.load());
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
            "same ordinary player can complete the authorized paste");
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
        try (HologramStargateRulesRegression.Mode rules = new HologramStargateRulesRegression.Mode(true)) {
            byte[] paused = Files.readAllBytes(file);
            for (String action : new String[] { "list", "preview", "cut", "paste", "undo" }) {
                NBTTagCompound response = HologramCutService.execute(player, request(action));
                SimpleAssert.that(!response.getBoolean("success"), "pending journals never expose live entries");
                SimpleAssert.eq(
                    HologramStargateRules.RESTRICTION_CODE,
                    response.getString("code"),
                    "even a read cannot bypass rules by replaying an approved journal: " + action);
            }
            HologramCutService.startupRecovery();
            boolean refused = false;
            NBTTagCompound retained = storage.load();
            NBTTagCompound original = (NBTTagCompound) retained.copy();
            try {
                HologramCutService.recover(storage, retained);
            } catch (IOException expected) {
                refused = HologramStargateRules.restrictionMessage()
                    .equals(expected.getMessage());
            }
            SimpleAssert.that(refused, "direct recovery cannot bypass server rules");
            SimpleAssert.eq(original, retained, "blocked recovery preserves the in-memory transaction journal");
            SimpleAssert.that(Arrays.equals(paused, Files.readAllBytes(file)), "rules preserve journal bytes exactly");
            SimpleAssert.that(world.getTileEntity(0, 200, 0) == source, "rules preserve the original live source tile");
            SimpleAssert.eq(0, source.invalidations, "paused recovery never runs native teardown");
        }
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
            ServerTaskScheduler.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
        } finally {
            bytes.release();
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

        private Actor() {
            super(null, null, new GameProfile(UUID.randomUUID(), "unused"), null);
        }

        @Override
        public boolean canPlayerEdit(int x, int y, int z, int side, ItemStack item) {
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
        int breaks, places;

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
                        3,
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
            return world.chunks.containsKey((long) x << 32 ^ z & 0xffffffffL);
        }
    }

    private static final class Scene extends WorldServer {

        Map<Long, Chunk> chunks;
        ISaveHandler save;
        int deniedX;

        private Scene() {
            super(null, null, "cut protection fixture", 0, null, null);
        }

        @Override
        public ISaveHandler getSaveHandler() {
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
            return x != deniedX;
        }

        @Override
        public Chunk getChunkFromChunkCoords(int x, int z) {
            long key = (long) x << 32 ^ z & 0xffffffffL;
            return chunks.computeIfAbsent(key, ignored -> new Chunk(this, x, z));
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
