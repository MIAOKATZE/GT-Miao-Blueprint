package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.common.config.Configuration;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Real Forge config, authenticated service boundaries, stopped jobs and persisted material-return delivery. */
public final class HologramStargateRulesRegression {

    private HologramStargateRulesRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramStargateRulesRegression.class, args)) return;
        run();
        System.out.println("HologramStargateRulesRegression PASS");
    }

    public static void run() {
        try {
            configuration();
            try (Mode rules = new Mode(true)) {
                admissionsAndJobs();
                retainedMaterials();
                recognition();
            }
        } catch (Exception failure) {
            throw new AssertionError("Stargate rules", failure);
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
                config.save();
                HologramStargateRules.load(directory.toFile());
                SimpleAssert.eq(enabled, HologramStargateRules.enabled(), "server restart reads the explicit switch");
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
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        Actor player = actor(unsafe, world);
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previousChannel = channel.get(null);
        try {
            RecordingChannel recording = (RecordingChannel) unsafe.allocateInstance(RecordingChannel.class);
            channel.set(null, recording);
            HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
            s.virtual = true;
            s.virtualPreset = new HologramPresets.Preset();
            s.virtualPreset.schema = 2;
            s.virtualPreset.name = "retained blueprint";
            s.capture = new HologramCapture(null, player.getHeldItem());
            for (int index = 0; index < 2; index++) {
                HologramCapture.Cell cell = new HologramCapture.Cell(null, index, 64, 0);
                cell.block = Blocks.stone;
                s.capture.cells.add(cell);
                s.work.add(index);
                s.virtualPreset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), index, 0, 0, -1, false));
            }
            s.materialSettings.containers = s.materialSettings.me = false;
            HologramPresets.Preset blueprint = s.virtualPreset;
            HologramWorkspace.activate(player.getUniqueID(), s);
            for (boolean operator : new boolean[] { false, true }) {
                player.operator = operator;
                player.capabilities.isCreativeMode = operator;
                SimpleAssert
                    .eq(operator, player.canCommandSenderUseCommand(4, "gtmb"), "fixture has real OP capability");
                for (String op : new String[] { "build", "start", "native", "confirmBlueprintPlacement",
                    "removeBlueprintObstructions", "resume" }) {
                    s.job = "resume".equals(op) ? 2 : 0;
                    int beforeJob = s.job;
                    s.actionCount = 0;
                    s.forcePlacementReady = true;
                    NBTTagCompound request = new NBTTagCompound();
                    request.setString("op", op);
                    request.setString("session", s.id);
                    request.setBoolean("stargateRules", false); // Untrusted client preferences grant no authority.
                    HologramActionDispatcher.handle(player, request);
                    SimpleAssert.eq(beforeJob, s.job, "a forged automatic operation never starts or resumes: " + op);
                    SimpleAssert.eq(HologramStargateRules.restrictionMessage(), s.status, "server-owned denial: " + op);
                    SimpleAssert.that(!s.forcePlacementReady, "denial revokes old partial-placement consent");
                    SimpleAssert.that(
                        recording.latest.getCompoundTag("progress")
                            .getBoolean("stargateRules"),
                        "the authoritative UI receipt publishes enabled server rules");
                }
                for (String action : new String[] { "cut", "paste", "undo", "demolish" }) {
                    NBTTagCompound request = new NBTTagCompound();
                    request.setString("action", action);
                    request.setString(
                        "requestId",
                        UUID.randomUUID()
                            .toString());
                    NBTTagCompound denied = HologramCutService.execute(player, request);
                    SimpleAssert.that(!denied.getBoolean("success"), "wire-level world action is rejected: " + action);
                    SimpleAssert.eq(HologramStargateRules.RESTRICTION_CODE, denied.getString("code"), "stable reason");
                }
            }
            s.job = 0;
            HologramPlacementJobs.start(s);
            SimpleAssert.eq(0, s.job, "direct job callers cannot bypass server admission");
            HologramBuildPreparation.directBuild(s, false);
            HologramBuildPreparation.nativeBuild(s);
            HologramBuildPreparation.reviewedBuild(s, new NBTTagCompound());
            SimpleAssert
                .eq(HologramStargateRules.restrictionMessage(), s.status, "deferred/native build is also gated");
            for (int mode = 0; mode <= 2; mode++) {
                s.mode = mode;
                SimpleAssert.that(
                    !HologramWorldTransactions.operateChecked(s, s.capture.cells.get(0), 0),
                    "direct world transaction cannot build, replace or remove: " + mode);
            }
            refuses(() -> HologramWorldTransactions.removeBlueprintObstructions(s, new NBTTagCompound()));
            refuses(() -> HologramDemolition.start(player, new HologramAreaSelection(), 0));
            refuses(() -> HologramDemolition.removeBlock(player, world, 0, 64, 0));
            SimpleAssert
                .eq(0L, world.blockReads + world.tileReads, "denied world changes never enter native callbacks");
            SimpleAssert
                .that(s.virtualPreset == blueprint && blueprint.cells.size() == 2, "old blueprint content survives");
            SimpleAssert.eq(5, player.inventory.mainInventory[1].stackSize, "denials consume no material");

            s.job = 1;
            s.cursor = s.completed = 1;
            HologramPlacementJobs.tickJob(s);
            SimpleAssert.eq(4, s.job, "already admitted placement stops before its next cell");
            SimpleAssert.eq(1, s.cursor, "stopped job retains its attempted count");
            SimpleAssert.eq(1, s.completed, "stopped job retains already committed progress");
            SimpleAssert.eq(2, s.work.size(), "stopped job retains its original work receipt");
            SimpleAssert.eq(
                "stopped",
                recording.latest.getCompoundTag("progress")
                    .getString("outcome"),
                "the actual snapshot transport publishes a terminal receipt");

            try (Mode ordinary = new Mode(false)) {
                s.virtual = false;
                s.mode = 0;
                HologramSnapshots.sendUi(s);
                SimpleAssert.that(
                    !recording.latest.getCompoundTag("progress")
                        .getBoolean("stargateRules"),
                    "switching off is published by the same authoritative UI receipt");
                s.capture.cells.get(0).anchor = true;
                SimpleAssert.that(
                    HologramWorldTransactions.operateChecked(s, s.capture.cells.get(0), 0),
                    "switching off preserves the existing read-only anchor transaction");
                HologramBuildPreparation.reviewedBuild(s, new NBTTagCompound());
                SimpleAssert.that(s.status.contains("确认计划已更新"), "disabled mode keeps original plan validation");
                HologramAreaSelection selection = new HologramAreaSelection();
                selection.point(1, 0, 64, 0);
                selection.point(2, 0, 64, 0);
                HologramDemolition.start(player, selection, 0);
                SimpleAssert.that(HologramDemolition.active(player), "disabled mode admits an ordinary demolition job");
            }
            long reads = world.blockReads + world.tileReads;
            HologramDemolition.tick();
            SimpleAssert.that(!HologramDemolition.active(player), "rules also stop an already admitted demolition job");
            SimpleAssert
                .eq(reads, world.blockReads + world.tileReads, "stopped demolition never begins a native removal");
        } finally {
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
        Method factory = HologramBuildOrHintsRegression.class.getDeclaredMethod("controller");
        factory.setAccessible(true);
        MTEEnhancedMultiBlockBase<?> controller = (MTEEnhancedMultiBlockBase<?>) factory.invoke(null);
        HologramCapture capture = HologramCapture
            .collect(controller, new ItemStack(Blocks.stone), ExtendedFacing.DEFAULT);
        SimpleAssert.that(
            !capture.incomplete && !capture.cells.isEmpty(),
            "construct(true) recognition retains normal semantics");
    }

    private static Actor actor(sun.misc.Unsafe unsafe, HologramServiceEditRegression.ReadOnlyWorld world)
        throws ReflectiveOperationException {
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

    private static void refuses(Operation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("automatic world operation was admitted");
        } catch (IOException expected) {
            SimpleAssert.eq(HologramStargateRules.restrictionMessage(), expected.getMessage(), "direct server denial");
        }
    }

    private interface Operation {

        void run() throws Exception;
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
