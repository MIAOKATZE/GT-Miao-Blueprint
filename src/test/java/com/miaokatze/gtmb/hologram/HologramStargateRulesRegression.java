package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.stats.StatBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Real opt-in config, paid automatic world edits, ore suppression and retained device/material recovery. */
public final class HologramStargateRulesRegression {

    private HologramStargateRulesRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramStargateRulesRegression.class, args)) return;
        run();
        System.out.println("HologramStargateRulesRegression PASS");
    }

    public static void run() {
        String threadName = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try (AutoCloseable nativeOres = nativeOreEnvironment()) {
            configuration();
            try (Mode rules = new Mode(true)) {
                admissionsAndJobs();
                retainedMaterials();
                recognition();
            }
        } catch (Exception failure) {
            throw new AssertionError("Stargate rules", failure);
        } finally {
            Thread.currentThread()
                .setName(threadName);
        }
    }

    private static void configuration() throws Exception {
        Path directory = Files.createTempDirectory("gtmb-stargate-rules-");
        Path file = directory.resolve(HologramStargateRules.FILE_NAME);
        try (Mode original = new Mode(false)) {
            HologramStargateRules.load(directory.toFile());
            SimpleAssert.that(!HologramStargateRules.enabled(), "a new server keeps every previous feature enabled");
            String generated = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            SimpleAssert
                .that(generated.contains("stargateRules {"), "the generated category matches the documented case");
            SimpleAssert.that(generated.contains("B:enabled=false"), "the generated rules switch is opt-in");
            for (boolean enabled : new boolean[] { true, false }) {
                Configuration config = new Configuration(file.toFile(), true);
                config.load();
                config.get("stargateRules", "enabled", false)
                    .set(enabled);
                config.get("stargateRules", "enabled", false).comment = "Obsolete blanket construction restriction";
                config.save();
                HologramStargateRules.load(directory.toFile());
                SimpleAssert.eq(enabled, HologramStargateRules.enabled(), "server restart reads the explicit switch");
                String refreshed = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                SimpleAssert.that(
                    refreshed.contains("Keep automatic construction") && !refreshed.contains("Obsolete blanket"),
                    "existing config keeps its value and refreshes rule guidance");
            }
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }

    private static void admissionsAndJobs() throws Exception {
        Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) access.get(null);
        Scene world = (Scene) unsafe.allocateInstance(Scene.class);
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        world.capturedBlockSnapshots = new java.util.ArrayList<>();
        world.block = Blocks.air;
        Actor player = actor(unsafe, world);
        player.posY = 64;
        OreBlock ore = (OreBlock) registeredOre("gtmb:stargate_transaction_ore_fixture", new OreBlock());
        OreDictionary.registerOre("oreGtmbTransactionFixture", new ItemStack(ore));
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previousChannel = channel.get(null);
        EventBus previousBus = MinecraftForge.EVENT_BUS;
        Field bus = MinecraftForge.class.getDeclaredField("EVENT_BUS");
        Object busBase = unsafe.staticFieldBase(bus);
        long busOffset = unsafe.staticFieldOffset(bus);
        unsafe.putObject(busBase, busOffset, new Guard(previousBus));
        try {
            RecordingChannel recording = (RecordingChannel) unsafe.allocateInstance(RecordingChannel.class);
            channel.set(null, recording);
            HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
            s.virtual = s.virtualPlacement = s.virtualLocked = true;
            s.virtualPreset = new HologramPresets.Preset();
            s.virtualPreset.schema = 2;
            s.virtualPreset.name = "retained blueprint";
            s.virtualPreset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
            s.capture = new HologramCapture(null, player.getHeldItem());
            HologramCapture.Cell cell = new HologramCapture.Cell(null, 0, 64, 0);
            cell.block = Blocks.stone;
            cell.userPosition = true;
            cell.blueprintBlock = cell.blueprintItem = "minecraft:stone";
            s.capture.cells.add(cell);
            s.pins.put(0, new ItemStack(Blocks.stone));
            s.materialSettings.containers = s.materialSettings.me = false;
            HologramWorkspace.activate(player.getUniqueID(), s);
            HologramResourcePlanner.refreshPlan(s);
            HologramBuildPreparation.directBuild(s, false);
            SimpleAssert.eq(1, s.job, "enabled rules admit the normal paid construction job");
            s.job = 2;
            NBTTagCompound resume = new NBTTagCompound();
            resume.setString("session", s.id);
            resume.setString("op", "resume");
            resume.setBoolean("stargateRules", false);
            HologramActionDispatcher.handle(player, resume);
            SimpleAssert.eq(1, s.job, "normal dispatcher can resume an admitted job under enabled rules");
            HologramPlacementJobs.tickJob(s);
            SimpleAssert.eq(Blocks.stone, world.block, "enabled job performs its actual world placement");
            SimpleAssert.eq(4, player.inventory.mainInventory[1].stackSize, "normal construction pays a real shell");
            HologramSnapshots.sendUi(s);
            SimpleAssert.that(
                recording.latest.getCompoundTag("progress")
                    .getBoolean("stargateRules"),
                "the authoritative state still reports the server-owned opt-in switch");

            s.job = 0;
            s.blueprintAwaitingConfirmation = s.placementReviewIssued = true;
            world.block = ore;
            HologramResourcePlanner.refreshPlan(s);
            NBTTagCompound clear = new NBTTagCompound();
            clear.setLong("placementReviewNonce", s.placementReviewNonce);
            clear.setLong("planRevision", s.planRevision);
            HologramWorldTransactions.removeBlueprintObstructions(s, clear);
            SimpleAssert.eq(Blocks.air, world.block, "reviewed obstruction removal may destroy ore");
            SimpleAssert.that(s.recovered.isEmpty(), "reviewed clearing returns no ore drops");
            SimpleAssert.eq(0, world.liveItems + world.liveXp, "native clearing callbacks cannot leave ore loot");

            world.block = ore;
            s.mode = 2;
            SimpleAssert
                .that(HologramWorldTransactions.operateChecked(s, cell, 0), "normal transaction may remove ore");
            SimpleAssert.eq(Blocks.air, world.block, "transaction removal destroys the ore block");
            SimpleAssert.that(s.recovered.isEmpty(), "transaction removal grants no ore refund");

            java.util.Map<Block, Integer> replacementFamily = new java.util.LinkedHashMap<>();
            replacementFamily.put(ore, 0);
            replacementFamily.put(Blocks.stone, 0);
            HologramCapture.Cell replacement = new HologramCapture.Cell(
                StructureUtility.ofBlocksFlat(replacementFamily, Blocks.stone, 0),
                0,
                64,
                0);
            replacement.block = Blocks.stone;
            replacement.blueprintBlock = replacement.blueprintItem = "minecraft:stone";
            s.capture.cells.set(0, replacement);
            s.mode = 1;
            s.replacementAuthorized = true;
            world.block = ore;
            SimpleAssert
                .that(HologramWorldTransactions.operateChecked(s, replacement, 0), "replacement may remove ore");
            SimpleAssert.eq(Blocks.stone, world.block, "replacement installs its paid target over the ore");
            SimpleAssert.eq(3, player.inventory.mainInventory[1].stackSize, "replacement pays its real shell");
            SimpleAssert.that(s.recovered.isEmpty(), "replacement grants no ore refund");
            SimpleAssert.eq(0, world.liveItems + world.liveXp, "transaction callbacks cannot leave ore loot");

            HologramAreaSelection selection = new HologramAreaSelection();
            selection.point(1, 0, 64, 0);
            selection.point(2, 0, 64, 0);
            HologramDemolition.start(player, selection, 0);
            SimpleAssert.that(HologramDemolition.active(player), "enabled rules admit ordinary area demolition");
            HologramDemolition.cancel(player);
            s.mode = 0;
            s.job = 0;
            HologramBuildPreparation.reviewedBuild(s, new NBTTagCompound());
            SimpleAssert.that(s.status.contains("确认计划已更新"), "normal material review checks remain authoritative");
        } finally {
            unsafe.putObject(busBase, busOffset, previousBus);
            HologramWorkspace.clear();
            HologramDemolition.clear();
            channel.set(null, previousChannel);
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static void retainedMaterials() throws ReflectiveOperationException {
        HologramRecoveryDeliveryRegression.Fixture ordinary = new HologramRecoveryDeliveryRegression.Fixture(
            DeliveryWorld.class);
        ordinary.player.inventory.mainInventory[1] = ordinary.stack(60);
        ordinary.context.recoverWorld(Collections.singletonList(ordinary.stack(4)));
        SimpleAssert.eq(4L, ordinary.pending(), "an actual old refund exists in the persisted material ledger");
        HologramSession s = new HologramSession(ordinary.player, 0, 64, 0, false, 2);
        s.materialSettings.me = s.materialSettings.containers = false;
        HologramMaterialAccess.materialSource(s);
        SimpleAssert
            .eq(64, ordinary.player.inventory.mainInventory[1].stackSize, "material inspection still delivers refunds");
        SimpleAssert.eq(0L, ordinary.pending(), "delivered ledger quantity is acknowledged exactly once");
        HologramRecoveryDeliveryRegression.Fixture demolition = new HologramRecoveryDeliveryRegression.Fixture();
        demolition.context.recoverWorldAtPlayer(Collections.singletonList(demolition.stack(70)), 0);
        demolition.flushFully();
        SimpleAssert.eq(70, demolition.dropped(), "real committed demolition returns can still be delivered");
        SimpleAssert.eq(0L, demolition.pending(), "only actual delivery clears committed recovery records");
    }

    private static void recognition() throws Exception {
        String previous = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            Method factory = HologramBuildOrHintsRegression.class.getDeclaredMethod("controller");
            factory.setAccessible(true);
            MTEEnhancedMultiBlockBase<?> controller = (MTEEnhancedMultiBlockBase<?>) factory.invoke(null);
            HologramCapture capture = HologramCapture
                .collect(controller, new ItemStack(Blocks.stone), ExtendedFacing.DEFAULT);
            SimpleAssert.that(
                !capture.incomplete && !capture.cells.isEmpty(),
                "construct(true) recognition retains normal semantics");
        } finally {
            Thread.currentThread()
                .setName(previous);
        }
    }

    private static Actor actor(sun.misc.Unsafe unsafe, World world) throws ReflectiveOperationException {
        Actor player = (Actor) unsafe.allocateInstance(Actor.class);
        UUID id = UUID.randomUUID();
        for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
            field.setAccessible(true);
            field.set(player, new GameProfile(id, "StargateRulesFixture"));
        }
        for (Field field : Entity.class.getDeclaredFields()) if (field.getType() == UUID.class) {
            field.setAccessible(true);
            field.set(player, id);
        }
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.inventory.mainInventory[0] = new ItemStack(new ItemNekoHologramProjector());
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 5);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.allowEdit = true;
        NetworkManager connection = new NetworkManager(false);
        for (Field field : NetworkManager.class.getDeclaredFields())
            if (field.getType() == io.netty.channel.Channel.class) {
                field.setAccessible(true);
                field.set(connection, new EmbeddedChannel(new ChannelInboundHandlerAdapter()));
            }
        new NetHandlerPlayServer(null, connection, player);
        return player;
    }

    /** Only native ore initialization's raw-FML prerequisite; restore even an uninitialized null registry. */
    static AutoCloseable nativeOreEnvironment() throws ReflectiveOperationException {
        cpw.mods.fml.common.Loader loader = cpw.mods.fml.common.Loader.instance();
        Field names = cpw.mods.fml.common.Loader.class.getDeclaredField("namedMods");
        names.setAccessible(true);
        Object previous = names.get(loader);
        if (previous == null) names.set(loader, Collections.emptyMap());
        return () -> names.set(loader, previous);
    }

    /** Exercise Forge's real registration, including the delegates required by OreDictionary. */
    static Block registeredOre(String name, Block block) throws ReflectiveOperationException {
        Block existing = (Block) Block.blockRegistry.getObject(name);
        if (existing != null && existing != Blocks.air) return existing;
        int free = 3700;
        while (free > 0 && (Item.getItemById(free) != null || Block.getBlockById(free) != Blocks.air)) free--;
        if (free == 0) throw new AssertionError("No isolated ore fixture registry slot");
        Class<?> type = cpw.mods.fml.common.registry.GameData.class;
        Method main = type.getDeclaredMethod("getMain");
        main.setAccessible(true);
        Object data = main.invoke(null);
        Method registerBlock = type.getDeclaredMethod("registerBlock", Block.class, String.class, int.class);
        registerBlock.setAccessible(true);
        registerBlock.invoke(data, block, name, free);
        Method registerItem = type.getDeclaredMethod("registerItem", Item.class, String.class, int.class);
        registerItem.setAccessible(true);
        registerItem.invoke(data, new ItemBlock(block), name, free);
        return block;
    }

    static final class Mode implements AutoCloseable {

        private final Field field;
        private final boolean previous;

        Mode(boolean enabled) throws ReflectiveOperationException {
            field = HologramStargateRules.class.getDeclaredField("enabled");
            field.setAccessible(true);
            previous = field.getBoolean(null);
            field.setBoolean(null, enabled);
        }

        @Override
        public void close() throws IllegalAccessException {
            field.setBoolean(null, previous);
        }
    }

    private static final class Actor extends EntityPlayerMP {

        boolean operator;

        private Actor() {
            super(null, null, null, null);
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return operator;
        }

        @Override
        public void addChatMessage(IChatComponent message) {}

        @Override
        public boolean canPlayerEdit(int x, int y, int z, int side, ItemStack stack) {
            return true;
        }

        @Override
        public boolean isSneaking() {
            return false;
        }

        @Override
        public void addStat(StatBase stat, int count) {}
    }

    private static final class Scene extends WorldServer {

        Block block;
        int liveItems, liveXp;

        private Scene() {
            super(null, null, "stargate fixture", 0, null, null);
        }

        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        public int getHeight() {
            return 256;
        }

        public long getTotalWorldTime() {
            return 0;
        }

        public Block getBlock(int x, int y, int z) {
            return block;
        }

        public int getBlockMetadata(int x, int y, int z) {
            return 0;
        }

        public TileEntity getTileEntity(int x, int y, int z) {
            return null;
        }

        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            return true;
        }

        public GameRules getGameRules() {
            return new GameRules();
        }

        public void removeTileEntity(int x, int y, int z) {}

        public void markBlockForUpdate(int x, int y, int z) {}

        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            return true;
        }

        public void playSoundEffect(double x, double y, double z, String sound, float volume, float pitch) {}

        public boolean canPlaceEntityOnSide(Block block, int x, int y, int z, boolean replace, int side, Entity entity,
            ItemStack stack) {
            return true;
        }

        public boolean setBlock(int x, int y, int z, Block next, int metadata, int flags) {
            Block old = block;
            block = next;
            old.breakBlock(this, x, y, z, old, 0);
            return true;
        }

        public boolean spawnEntityInWorld(Entity entity) {
            EntityJoinWorldEvent event = new EntityJoinWorldEvent(entity, this) {

                @Override
                public boolean isCancelable() {
                    return true;
                }
            };
            if (MinecraftForge.EVENT_BUS.post(event) && !entity.forceSpawn) return false;
            if (!entity.isDead && entity instanceof EntityItem) liveItems++;
            if (!entity.isDead && entity instanceof EntityXPOrb) liveXp++;
            return true;
        }
    }

    private static final class Guard extends EventBus {

        final EventBus original;

        Guard(EventBus original) {
            this.original = original;
        }

        @Override
        public boolean post(Event event) {
            if (event instanceof EntityJoinWorldEvent) {
                HologramDemolition.DROPS.spawned((EntityJoinWorldEvent) event);
                return event.isCanceled();
            }
            if (event instanceof BlockEvent.HarvestDropsEvent)
                HologramDemolition.DROPS.harvested((BlockEvent.HarvestDropsEvent) event);
            return original.post(event);
        }
    }

    private static final class OreBlock extends Block {

        OreBlock() {
            super(Material.rock);
        }

        @Override
        public java.util.ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
            throw new AssertionError("ore preflight must not invoke native getDrops before a suppression scope");
        }

        @Override
        public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
            world.spawnEntityInWorld(new EntityItem(world, x + .5, y + .5, z + .5, new ItemStack(Blocks.iron_ore)));
            world.spawnEntityInWorld(new EntityXPOrb(world, x + .5, y + .5, z + .5, 5));
        }
    }

    private static final class RecordingChannel extends SimpleNetworkWrapper {

        NBTTagCompound latest;

        private RecordingChannel() {
            super("stargate_rules_fixture");
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP player) {
            if (message instanceof HologramNetwork.State) latest = ((HologramNetwork.State) message).tag;
        }
    }

    private static final class DeliveryWorld extends HologramRecoveryDeliveryRegression.RecoveryWorld {

        @Override
        public long getTotalWorldTime() {
            return 0;
        }
    }
}
