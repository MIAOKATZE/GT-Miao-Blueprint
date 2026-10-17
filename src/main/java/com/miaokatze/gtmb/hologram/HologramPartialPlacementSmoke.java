package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

/** Real reviewed jobs: mixed stock, repeated identities and atomic extra-resource shortages. */
public final class HologramPartialPlacementSmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-PARTIAL-PLACEMENT] ";
    private static final String ADAPTER_ID = "gtmb:partial-placement-smoke";
    private static final UUID USER = UUID.fromString("f61fdc71-9df5-431f-bd98-99193673bea0");
    private static final int X = 416, Y = 84, SOURCE_Z = 608, TARGET_Z = 624;
    private final CaptureNetwork capture = new CaptureNetwork();
    private WorldServer world;
    private FakePlayer player;
    private int assertions;

    private HologramPartialPlacementSmoke() {}

    /** Separate backend-only opt-in; does not install the legacy CaptureNetwork or invent client input. */
    public static void registerIdentityFixtureIfEnabled() {
        if (Boolean.getBoolean("gtmb.hologram.placementIdentitySmoke")) cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(new IdentityFixture());
    }

    /** Uses native mod serializers and one production job slice per actual server END event. */
    public static final class IdentityFixture {

        private static final int FX = 1536, FY = 100, FZ = 1536;
        private final NBTTagCompound evidence = new NBTTagCompound();
        private WorldServer fixtureWorld;
        private FakePlayer actor;
        private HologramSession paid;
        private Block aeBlock, gtBlock;
        private int ticks, slices;
        private long admittedTick;
        private long verifiedWorldTick = -1;
        private boolean finished;

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void tick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
            if (finished || event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END || ++ticks < 40) return;
            try {
                if (paid == null) prepare();
                else {
                    owned();
                    if (verifiedWorldTick >= 0) {
                        if (fixtureWorld.getTotalWorldTime() - verifiedWorldTick >= 25) {
                            gregtech.api.metatileentity.BaseMetaTileEntity stopped = (gregtech.api.metatileentity.BaseMetaTileEntity) fixtureWorld
                                .getTileEntity(FX + 1, FY, FZ + 12);
                            check(
                                !stopped.isAllowedToWork() && HologramNativeCapture.capture(stopped)
                                    .getCompoundTag("mFluid")
                                    .getInteger("Amount") == 1000,
                                "native stopped fixture changed air contents across a full generator cycle");
                            evidence.setLong("stoppedNaturalTickCheck", fixtureWorld.getTotalWorldTime());
                            evidence.setBoolean("nativeStoppedGeneratorStable", true);
                            finish(true);
                        }
                        return;
                    }
                    check(
                        fixtureWorld.getTotalWorldTime() > admittedTick,
                        "job must run on a later natural server tick");
                    HologramPlacementJobs.tickJob(paid);
                    slices++;
                    evidence.setInteger("naturalEndSlices", slices);
                    evidence.setInteger("job", paid.job);
                    evidence.setString("status", paid.status);
                    if (paid.job != 1) verifyPlaced();
                    else check(slices < 120, "native placement job timed out: " + paid.status);
                }
            } catch (Throwable failure) {
                evidence.setString("failure", failure.toString());
                failure.printStackTrace();
                finish(false);
            }
        }

        private void owned() {
            String name = fixtureWorld.getWorldInfo()
                .getWorldName();
            check(
                !fixtureWorld.isRemote && (name.startsWith("hologram-smoke-") || name.startsWith("hologram-cut-v020-")),
                "isolated explicitly prepared smoke world required");
            for (Object player : fixtureWorld.playerEntities)
                check(player instanceof FakePlayer, "human presence forbids automated fixture construction");
        }

        private void prepare() throws Exception {
            // Resolve the receipt guard before creating anything in the isolated world.
            HologramV011Evidence.directory();
            fixtureWorld = MinecraftServer.getServer()
                .worldServerForDimension(0);
            owned();
            evidence.setBoolean("serverBackendOnly", true);
            evidence.setBoolean("actualNetty", false);
            evidence.setBoolean("guiReviewExercised", false);
            evidence.setBoolean("syntheticNativeWriterPreset", true);
            evidence.setBoolean("naturalServerEndTicks", true);
            evidence
                .setString("transportScope", "Disconnected FakePlayer; production snapshot delivery is not asserted");
            for (int cx = FX >> 4; cx <= (FX + 1) >> 4; cx++) for (int cz = FZ >> 4; cz <= (FZ + 12) >> 4; cz++) {
                net.minecraft.world.chunk.Chunk chunk = fixtureWorld.theChunkProviderServer.loadChunk(cx, cz);
                check(
                    chunk != null && !(chunk instanceof net.minecraft.world.chunk.EmptyChunk)
                        && chunk.xPosition == cx
                        && chunk.zPosition == cz
                        && fixtureWorld.getChunkFromChunkCoords(cx, cz) == chunk,
                    "real loaded fixture chunk required");
            }
            for (int dz : new int[] { 0, 8, 12 }) for (int dx = 0; dx < 2; dx++) check(
                fixtureWorld.isAirBlock(FX + dx, FY, FZ + dz)
                    && fixtureWorld.getTileEntity(FX + dx, FY, FZ + dz) == null,
                "fixture must start in untouched air");
            aeBlock = Block.getBlockFromName("ae2fc:fluid_interface");
            gtBlock = Block.getBlockFromName("gregtech:gt.blockmachines");
            check(
                aeBlock != null && gtBlock != null && aeBlock != Blocks.air && gtBlock != Blocks.air,
                "native AE2FC and GregTech registrations required");
            check(
                fixtureWorld.setBlock(FX, FY, FZ, aeBlock, 0, 3) && fixtureWorld.getBlock(FX, FY, FZ) == aeBlock,
                "native AE2FC source block write failed");
            TileEntity ae = fixtureWorld.getTileEntity(FX, FY, FZ);
            check(
                ae != null && ae.getClass()
                    .getName()
                    .contains("TileFluidInterface"),
                "native AE2FC tile required");
            NBTTagCompound aeData = HologramNativeCapture.capture(ae);
            check(
                aeData.hasKey("FluidInv", 10) && aeData.getCompoundTag("FluidInv")
                    .hasKey("#0", 10)
                    && aeData.getCompoundTag("FluidInv")
                        .hasKey("#5", 10),
                "native six-slot FluidInv writer schema required");
            check(fixtureWorld.setBlock(FX + 1, FY, FZ, gtBlock, 3, 3), "native GT source write failed");
            TileEntity gt = fixtureWorld.getTileEntity(FX + 1, FY, FZ);
            check(gt instanceof gregtech.api.metatileentity.BaseMetaTileEntity, "native GT base tile required");
            gregtech.api.metatileentity.BaseMetaTileEntity base = (gregtech.api.metatileentity.BaseMetaTileEntity) gt;
            base.setInitialValuesAsNBT(null, (short) 32765);
            base.setFrontFacing(net.minecraftforge.common.util.ForgeDirection.EAST);
            // This native generator otherwise fills to two billion mB every 20 ticks. Persist its real stop switch.
            base.disableWorking();
            check(
                base.getMetaTileID() == 32765
                    && base.getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEBasicTank,
                "actual GT32765 atmospheric intake must be registered");
            net.minecraftforge.fluids.Fluid air = net.minecraftforge.fluids.FluidRegistry.getFluid("air");
            check(air != null, "registered air fluid required");
            ((gregtech.api.metatileentity.implementations.MTEBasicTank) base
                .getMetaTileEntity()).mFluid = new net.minecraftforge.fluids.FluidStack(air, 1000);
            NBTTagCompound gtData = HologramNativeCapture.capture(base);
            check(
                gtData.getCompoundTag("mFluid")
                    .getInteger("Amount") == 1000 && gtData.getBoolean("mWorks")
                    && !base.isAllowedToWork(),
                "native GT writer must retain real fluid resource and the stopped machine setting");
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.id = UUID.randomUUID()
                .toString();
            preset.name = "Native AE2FC and GT32765";
            preset.controllerType = "area";
            HologramPresets.Cell a = HologramPresets.cell(new ItemStack(aeBlock), 0, 0, 0, -1, false);
            a.block = "ae2fc:fluid_interface";
            a.metadata = 0;
            a.tileNbt = HologramBlueprintNbt.encodeCanonical(aeBlock, aeData);
            HologramPresets.Cell g = HologramPresets.cell(new ItemStack(gtBlock, 1, 32765), 1, 0, 0, 5, false);
            g.block = "gregtech:gt.blockmachines";
            g.metadata = 3;
            g.tileNbt = HologramBlueprintNbt.encodeCanonical(gtBlock, gtData);
            preset.cells.add(a);
            preset.cells.add(g);
            HologramPresets.validate(preset);
            actor = FakePlayerFactory.get(
                fixtureWorld,
                new GameProfile(UUID.fromString("5983680c-e0cd-42a4-b0e5-bf1ea926db85"), "GTMBIdentity"));
            // Native packs can append offhand slots before ContainerPlayer binds its armor/main indices.
            // Clear this fixture's inventory without changing the real array sizes or the existing container.
            java.util.Arrays.fill(actor.inventory.mainInventory, null);
            java.util.Arrays.fill(actor.inventory.armorInventory, null);
            check(
                actor.inventory.mainInventory.length >= 36 && actor.inventoryContainer != null,
                "native player inventory and container required");
            for (Object value : actor.inventoryContainer.inventorySlots)
                ((net.minecraft.inventory.Slot) value).getStack();
            actor.inventoryContainer.detectAndSendChanges();
            evidence.setInteger("nativeMainInventorySlots", actor.inventory.mainInventory.length);
            evidence.setInteger("nativeArmorSlots", actor.inventory.armorInventory.length);
            evidence.setInteger("nativeContainerSlots", actor.inventoryContainer.inventorySlots.size());
            evidence.setBoolean("allNativeContainerSlotsReadable", true);
            actor.inventory.mainInventory[0] = new ItemStack(
                (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
            actor.inventory.mainInventory[1] = new ItemStack(aeBlock);
            actor.inventory.mainInventory[2] = new ItemStack(gtBlock, 1, 32765);
            actor.inventory.currentItem = 0;
            actor.capabilities.allowEdit = true;
            actor.setPosition(FX + .5, FY, FZ + 10.5);
            actor.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
            HologramSession denied = session(preset, FZ + 8);
            applyOnce(denied);
            HologramAuthoringWorkflow.blueprintAction(denied, request(denied), "confirmBlueprintPlacement");
            check(
                denied.job == 0 && denied.planMissing > 0
                    && denied.forcePlacementReady
                    && denied.blueprintResourceMissing
                        .contains(new net.minecraftforge.fluids.FluidStack(air, 1000).getLocalizedName())
                    && denied.status.contains(denied.blueprintResourceMissing),
                "survival must reveal real retained air shortage: " + denied.status);
            check(
                actor.inventory.mainInventory[1].stackSize == 1 && actor.inventory.mainInventory[2].stackSize == 1
                    && fixtureWorld.isAirBlock(FX, FY, FZ + 8)
                    && fixtureWorld.isAirBlock(FX + 1, FY, FZ + 8),
                "survival rejection must change neither target nor base inventory");
            evidence.setString("survivalStatus", denied.status);
            evidence.setInteger("survivalJob", denied.job);
            evidence.setBoolean("survivalZeroWritesAndDebits", true);
            actor.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.CREATIVE);
            paid = session(preset, FZ + 12);
            applyOnce(paid);
            HologramAuthoringWorkflow.blueprintAction(paid, request(paid), "confirmBlueprintPlacement");
            check(
                paid.job == 1 && paid.completed == 0
                    && fixtureWorld.isAirBlock(FX, FY, FZ + 12)
                    && fixtureWorld.isAirBlock(FX + 1, FY, FZ + 12),
                "confirmation admits a job; ACK is not completed world placement: " + paid.status);
            admittedTick = fixtureWorld.getTotalWorldTime();
            evidence.setLong("admittedWorldTick", admittedTick);
        }

        private HologramSession session(HologramPresets.Preset preset, int z) throws Exception {
            HologramSession s = new HologramSession(actor, FX, FY, z, false, 1);
            s.virtual = s.virtualPlacement = s.virtualLocked = true;
            s.open = true;
            s.materialSettings.main = true;
            s.materialSettings.containers = s.materialSettings.me = false;
            s.materialContext = new HologramMaterials.Context(actor, s.materialSettings);
            s.virtualPreset = HologramPresets.copy(preset);
            HologramBlueprintProjection.populateVirtual(s);
            s.blueprintAwaitingConfirmation = s.placementReviewIssued = true;
            check(
                HologramSessionPolicy.valid(s) && s.capture.cells.size() == 2 && !s.capture.incomplete,
                "actual blueprint projection must produce two valid native cells");
            return s;
        }

        private void applyOnce(HologramSession s) throws Exception {
            long revision = s.planRevision, nonce = s.placementReviewNonce;
            NBTTagCompound configure = request(s);
            configure.setInteger("level", 1);
            HologramAuthoringWorkflow.blueprintAction(s, configure, "configurePlacementDeidentification");
            check(
                s.placementDeidentificationChosen && s.placementDeidentificationLevel == 1
                    && s.planRevision == revision + 1
                    && s.placementReviewNonce == nonce + 1,
                "manual policy must apply exactly once and keep the locked review");
            long applied = s.planRevision, reviewed = s.placementReviewNonce;
            configure = request(s);
            configure.setInteger("level", 1);
            for (int i = 0; i < 20; i++)
                HologramAuthoringWorkflow.blueprintAction(s, configure, "configurePlacementDeidentification");
            check(
                s.planRevision == applied && s.placementReviewNonce == reviewed && s.job == 0,
                "same preference must not repeatedly invalidate revision/nonce");
            NBTTagCompound nativeFluid = HologramBlueprintNbt.decode(s.capture.cells.get(1).tileNbt);
            check(
                nativeFluid.getCompoundTag("mFluid")
                    .getInteger("Amount") == 1000 && nativeFluid.getBoolean("mWorks"),
                "level1 must preserve authentic physical fluid contents and native stopped configuration");
            for (HologramCapture.Cell cell : s.capture.cells) check(
                cell.resourceError.isEmpty() && "pending".equals(cell.status),
                "native schema refused: " + cell.resourceError);
        }

        private void verifyPlaced() throws Exception {
            check(paid.job == 3 && paid.completed == 2, "world job did not actually finish both cells: " + paid.status);
            TileEntity ae = fixtureWorld.getTileEntity(FX, FY, FZ + 12);
            TileEntity gt = fixtureWorld.getTileEntity(FX + 1, FY, FZ + 12);
            check(
                fixtureWorld.getBlock(FX, FY, FZ + 12) == aeBlock && ae != null
                    && ae.getClass()
                        .getName()
                        .contains("TileFluidInterface"),
                "actual destination AE2FC tile missing");
            check(
                fixtureWorld.getBlock(FX + 1, FY, FZ + 12) == gtBlock
                    && gt instanceof gregtech.api.metatileentity.BaseMetaTileEntity
                    && ((gregtech.api.metatileentity.BaseMetaTileEntity) gt).getMetaTileID() == 32765,
                "actual destination GT32765 tile missing");
            NBTTagCompound finalGt = HologramNativeCapture.capture(gt);
            check(
                finalGt.getCompoundTag("mFluid")
                    .getInteger("Amount") == 1000
                    && !((gregtech.api.metatileentity.BaseMetaTileEntity) gt).isAllowedToWork(),
                "native placement did not retain priced air contents and the stopped machine setting");
            check(
                actor.inventory.mainInventory[1].stackSize == 1 && actor.inventory.mainInventory[2].stackSize == 1,
                "creative placement must not debit survival base inventory");
            evidence.setTag("destinationAe", HologramNativeCapture.capture(ae));
            evidence.setTag("destinationGt", finalGt);
            evidence.setInteger("completed", paid.completed);
            evidence.setLong("completedWorldTick", fixtureWorld.getTotalWorldTime());
            long revision = paid.planRevision, nonce = paid.placementReviewNonce;
            String terminalStatus = paid.status;
            NBTTagCompound retry = request(paid);
            retry.setInteger("level", 1);
            HologramAuthoringWorkflow.blueprintAction(paid, retry, "configurePlacementDeidentification");
            check(
                paid.job == 3 && paid.completed == 2
                    && paid.planRevision == revision
                    && paid.placementReviewNonce == nonce
                    && paid.status.equals(terminalStatus)
                    && fixtureWorld.getTileEntity(FX, FY, FZ + 12) == ae
                    && fixtureWorld.getTileEntity(FX + 1, FY, FZ + 12) == gt,
                "late preference retry must preserve the actual terminal result and native tiles");
            evidence.setBoolean("terminalPreferenceRetryPreservedWorld", true);
            verifiedWorldTick = fixtureWorld.getTotalWorldTime();
        }

        private static NBTTagCompound request(HologramSession s) {
            NBTTagCompound request = new NBTTagCompound();
            request.setString("session", s.id);
            request.setLong("planRevision", s.planRevision);
            request.setLong("placementReviewNonce", s.placementReviewNonce);
            request.setBoolean("forceExplicit", false);
            return request;
        }

        private static void check(boolean condition, String message) {
            if (!condition) throw new AssertionError("native placement identity fixture: " + message);
        }

        private void finish(boolean passed) {
            finished = true;
            evidence.setBoolean("passed", passed);
            try {
                HologramV011Evidence.write("placement-identity-native.nbt", evidence);
                HologramV011Evidence
                    .marker("placement-identity-native.txt", passed ? "PASS" : "FAIL " + evidence.getString("failure"));
            } catch (Throwable failure) {
                failure.printStackTrace();
                passed = false;
            }
            System.out.println(
                "[GTMB-PLACEMENT-IDENTITY-NATIVE] FINAL " + (passed ? "PASS" : "FAIL")
                    + " backendOnly=true gui=false netty=false");
            if (Boolean.getBoolean("gtmb.hologram.smokeShutdown")) MinecraftServer.getServer()
                .initiateShutdown();
        }
    }

    public static void run(WorldServer world) throws Exception {
        HologramPartialPlacementSmoke smoke = new HologramPartialPlacementSmoke();
        try {
            smoke.exercise(world);
            System.out.println(PREFIX + "FINAL PASS assertions=" + smoke.assertions);
        } catch (Throwable failure) {
            System.err.println(PREFIX + "FINAL FAIL " + failure);
            throw failure;
        }
    }

    private void exercise(WorldServer serverWorld) throws Exception {
        world = serverWorld;
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-smoke-"),
            "isolated_world_guard");
        player = FakePlayerFactory.get(world, new GameProfile(USER, "GTMBPartial"));
        java.util.Arrays.fill(player.inventory.mainInventory, null);
        player.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        player.inventory.currentItem = 0;
        player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        player.capabilities.allowEdit = true;
        player.setPosition(X + 3.5, Y, TARGET_Z - 1.5);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.write(player.getHeldItem(), settings);
        EmbeddedChannel transport = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        NetworkManager manager = new NetworkManager(false);
        for (Field field : NetworkManager.class.getDeclaredFields()) if (field.getType() == Channel.class) {
            field.setAccessible(true);
            field.set(manager, transport);
        }
        player.playerNetServerHandler = new NetHandlerPlayServer(MinecraftServer.getServer(), manager, player);
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previousChannel = channel.get(null);
        NativeExtraFixture adapter = new NativeExtraFixture();
        String presetId = null;
        boolean registered = false;
        Path library = HologramPresets.libraryPath();
        byte[] libraryBefore = Files.exists(library)
            ? HologramBlueprintSharing.readBytes(library, HologramPresets.MAX_LIBRARY_BYTES)
            : null;
        Field cache = HologramPresets.class.getDeclaredField("cached");
        cache.setAccessible(true);
        Object cacheBefore = cache.get(null);
        boolean libraryTouched = false;
        HologramService.clear();
        channel.set(null, capture);
        try {
            HologramNativePlacement.register(ADAPTER_ID, adapter);
            registered = true;
            HologramPresets.Preset preset = fixture();
            HologramBlueprintVersions.stamp(preset);
            if (!HologramLibraryFacade.enabled()) {
                libraryTouched = true;
                HologramPresets.save(preset);
                presetId = preset.id;
            }
            stock(1, new ItemStack(Blocks.chest));
            stock(2, new ItemStack(Blocks.stone));
            stock(3, new ItemStack(Blocks.cobblestone, 2));
            stock(4, new ItemStack(Blocks.furnace));
            HologramService.openAir(player);
            HologramSmokeTest.flushTransport();
            if (HologramLibraryFacade.enabled()) {
                NBTTagCompound upload = HologramLibraryProtocol.request("privateBegin");
                upload.setString("session", capture.state.getString("session"));
                upload.setLong("planRevision", capture.state.getLong("planRevision"));
                upload.setString("action", "place");
                upload.setString(
                    "localId",
                    "local_" + UUID.randomUUID()
                        .toString());
                HologramPresets.Preset canonical = HologramBlueprintSharing.decode(
                    new String(HologramServerLibrary.encodeShare(preset), java.nio.charset.StandardCharsets.UTF_8));
                String privateId = HologramService.libraryActivate(player, upload, canonical);
                HologramSmokeTest.flushTransport();
                require(
                    privateId.startsWith("private_") && HologramPresets.get(privateId) == null,
                    "dedicated_partial_fixture_uses_validated_session_private_upload_without_global_save");
            } else action("choosePlacementPreset", tag("presetId", preset.id));
            require(
                capture.state.getBoolean("moving") && capture.state.getInteger("missing") > 0,
                "mixed_blueprint_starts_only_unpaid_preview");
            NBTTagCompound position = new NBTTagCompound();
            position.setString("session", capture.state.getString("session"));
            position.setLong("planRevision", capture.state.getLong("planRevision"));
            position.setInteger("x", X);
            position.setInteger("y", Y);
            position.setInteger("z", TARGET_Z);
            position.setInteger("side", 1);
            HologramService.confirmWorldPosition(player, position);
            HologramSmokeTest.flushTransport();
            require(
                capture.state.getBoolean("virtualLocked") && capture.state.getBoolean("blueprintAwaitingConfirmation")
                    && !capture.state.getBoolean("open"),
                "real_first_position_confirmation_locks_without_opening_material_review");
            HologramService.openAir(player);
            HologramSmokeTest.flushTransport();
            require(capture.state.getBoolean("open"), "real_second_right_opens_locked_material_review");
            NBTTagCompound inspect = new NBTTagCompound();
            for (String key : new String[] { "main", "facing", "mode" })
                inspect.setInteger(key, capture.state.getInteger(key));
            inspect.setTag(
                "channels",
                capture.state.getCompoundTag("channels")
                    .copy());
            inspect.setInteger("scope", 2);
            inspect.setInteger("selected", 1);
            inspect.setIntArray("selectedIndices", new int[] { 1 });
            inspect.setInteger("layer", 0);
            action("configure", inspect);
            require(
                capture.state.getBoolean("wholeBlueprintPlacement") && capture.state.getInteger("missing") > 0,
                "inspecting_one_stocked_cell_cannot_hide_whole_blueprint_material_shortage");
            action("confirmBlueprintPlacement", new NBTTagCompound());
            require(
                capture.state.getInteger("job") != 1 && capture.state.getBoolean("forcePlacementReady"),
                "first_material_review_issues_second_confirmation_without_job");
            require(
                allAir() && stock(Blocks.chest) == 1
                    && stock(Blocks.stone) == 1
                    && stock(Blocks.cobblestone) == 2
                    && stock(Blocks.furnace) == 1,
                "first_confirmation_changes_neither_world_nor_any_stock");
            action("confirmBlueprintPlacement", new NBTTagCompound());
            require(
                capture.state.getInteger("job") != 1 && allAir(),
                "ordinary_duplicate_confirmation_cannot_force_construction");
            NBTTagCompound force = new NBTTagCompound();
            force.setBoolean("forceExplicit", true);
            action("confirmBlueprintPlacement", force);
            require(
                capture.state.getInteger("job") == 1 && capture.state.getBoolean("jobPartialPlacement")
                    && !capture.state.getBoolean("blueprintAwaitingConfirmation"),
                "second_explicit_review_schedules_actual_partial_job");
            settle();
            require(
                world.isAirBlock(X, Y, TARGET_Z) && world.getTileEntity(X, Y, TARGET_Z) == null,
                "unpaid_chest_contents_never_create_world_or_tile");
            require(
                world.getBlock(X + 1, Y, TARGET_Z) == Blocks.stone && world.isAirBlock(X + 3, Y, TARGET_Z),
                "shared_one_stone_stock_places_exactly_one_of_two_cells");
            require(
                world.getBlock(X + 2, Y, TARGET_Z) == Blocks.cobblestone
                    && world.getBlock(X + 5, Y, TARGET_Z) == Blocks.cobblestone
                    && world.isAirBlock(X + 4, Y, TARGET_Z),
                "missing_gold_does_not_block_other_fully_stocked_material");
            require(
                world.getBlock(X + 6, Y, TARGET_Z) == Blocks.chest
                    && ((TileEntityChest) world.getTileEntity(X + 6, Y, TARGET_Z)).getStackInSlot(0) == null,
                "failed_resource_reservation_returns_chest_for_later_plain_cell");
            require(
                world.isAirBlock(X + 7, Y, TARGET_Z) && world.getTileEntity(X + 7, Y, TARGET_Z) == null
                    && stock(Blocks.furnace) == 1
                    && stock(Items.diamond) == 0,
                "missing_registered_native_extra_never_generates_tile_or_consumes_base_material");
            require(
                stock(Blocks.stone) == 0 && stock(Blocks.cobblestone) == 0 && stock(Blocks.chest) == 0,
                "partial_job_consumes_each_available_block_once_without_global_reservation");
            require(
                capture.state.getInteger("job") == 5 && capture.state.getInteger("completed") == 4
                    && capture.state.getInteger("jobMissingCells") == 4
                    && capture.state.getInteger("jobSkippedCells") == 4,
                "partial_finish_receipt_reports_four_placed_and_four_shortage_skips");
            require(
                capture.state.getString("status")
                    .contains("4")
                    && capture.state.getString("status")
                        .contains("缺料"),
                "partial_job_explains_missing_material_in_player_summary");
            unchangedSource();

            stock(1, new ItemStack(Blocks.chest));
            stock(2, new ItemStack(Blocks.stone));
            stock(3, new ItemStack(Blocks.gold_block));
            stock(5, new ItemStack(Items.diamond, 3));
            action("scan", new NBTTagCompound());
            dumpCells("remaining-review");
            require(
                capture.state.getInteger("missing") == 0,
                "supplying_exact_remaining_base_and_extra_materials_clears_shortage");
            action("build", new NBTTagCompound());
            require(
                capture.state.getInteger("job") == 1,
                "reviewed_retry_builds_remaining_positions_without_a_second_generic_click");
            settle();
            dumpCells("remaining-finished");
            require(
                capture.state.getInteger("job") == 3 && capture.state.getInteger("completed") == 4
                    && capture.state.getInteger("jobMissingCells") == 0
                    && capture.state.getInteger("jobSkippedCells") == 0,
                "full_remaining_material_retry_finishes_four_remaining_cells");
            require(
                world.getBlock(X + 3, Y, TARGET_Z) == Blocks.stone
                    && world.getBlock(X + 4, Y, TARGET_Z) == Blocks.gold_block
                    && world.getBlock(X + 7, Y, TARGET_Z) == Blocks.furnace
                    && world.getTileEntity(X + 7, Y, TARGET_Z) != null,
                "paid_native_base_extra_and_other_remaining_cells_are_actually_placed");
            ItemStack restored = ((TileEntityChest) world.getTileEntity(X, Y, TARGET_Z)).getStackInSlot(0);
            require(
                restored != null && restored.getItem() == Items.diamond && restored.stackSize == 2,
                "paid_inventory_contents_restore_exact_original_count");
            require(
                stock(Blocks.chest) == 0 && stock(Blocks.stone) == 0
                    && stock(Blocks.gold_block) == 0
                    && stock(Blocks.furnace) == 0
                    && stock(Items.diamond) == 0,
                "retry_consumes_remaining_materials_and_all_three_extra_diamonds_exactly_once");
            unchangedSource();
        } finally {
            HologramService.clear();
            channel.set(null, previousChannel);
            try {
                try {
                    if (registered) removeAdapter(adapter);
                } finally {
                    if (libraryTouched) {
                        try {
                            if (presetId != null) {
                                Path pair = library.getParent()
                                    .resolve("local_" + presetId + ".json");
                                if (Files.exists(pair) || Files.exists(HologramBlueprintArtifact.previewPath(pair)))
                                    HologramPresets.delete(presetId);
                            }
                        } finally {
                            restoreLibrary(library, libraryBefore, cache, cacheBefore);
                        }
                    }
                }
            } finally {
                transport.finish();
                Object pending;
                while ((pending = transport.readOutbound()) != null) io.netty.util.ReferenceCountUtil.release(pending);
            }
        }
    }

    /** Restore the logical-only server fixture without weakening native JSON/PNG deletion. */
    private static void restoreLibrary(Path library, byte[] bytes, Field cache, Object previousCache) throws Exception {
        if (bytes == null) {
            Files.deleteIfExists(library);
        } else {
            Path restore = Files.createTempFile(library.getParent(), "gtmb-partial-fixture-restore-", ".tmp");
            try {
                Files.write(restore, bytes);
                Files.move(restore, library, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(restore);
            }
        }
        cache.set(null, previousCache);
    }

    private HologramPresets.Preset fixture() throws Exception {
        for (int offset = 0; offset < 8; offset++) {
            world.getChunkFromBlockCoords(X + offset, SOURCE_Z);
            world.getChunkFromBlockCoords(X + offset, TARGET_Z);
            require(world.isAirBlock(X + offset, Y, TARGET_Z), "fresh_destination_" + offset);
        }
        require(world.isAirBlock(X, Y, SOURCE_Z) && world.isAirBlock(X + 7, Y, SOURCE_Z), "fresh_source_tiles");
        world.setBlock(X, Y, SOURCE_Z, Blocks.chest, 2, 3);
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(X, Y, SOURCE_Z);
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 2));
        NBTTagCompound contents = new NBTTagCompound();
        chest.writeToNBT(contents);
        TileEntityChest plain = new TileEntityChest();
        NBTTagCompound plainPayload = new NBTTagCompound();
        plain.writeToNBT(plainPayload);
        world.setBlock(X + 7, Y, SOURCE_Z, Blocks.furnace, 2, 3);
        NBTTagCompound nativePayload = new NBTTagCompound();
        world.getTileEntity(X + 7, Y, SOURCE_Z)
            .writeToNBT(nativePayload);
        require(
            HologramNativePlacement.materials(Blocks.furnace, 2, nativePayload)
                .size() == 2,
            "registered_native_provider_declares_base_and_extra_real_items");
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = UUID.randomUUID()
            .toString();
        preset.schema = 2;
        preset.name = "Runtime mixed partial receipt";
        preset.controllerType = "area";
        preset.retainNbt = preset.retainCovers = true;
        preset.cells.add(cell(Blocks.chest, 0, 2, contents));
        preset.cells.add(cell(Blocks.stone, 1, 0, null));
        preset.cells.add(cell(Blocks.cobblestone, 2, 0, null));
        preset.cells.add(cell(Blocks.stone, 3, 0, null));
        preset.cells.add(cell(Blocks.gold_block, 4, 0, null));
        preset.cells.add(cell(Blocks.cobblestone, 5, 0, null));
        preset.cells.add(cell(Blocks.chest, 6, 2, plainPayload));
        preset.cells.add(cell(Blocks.furnace, 7, 2, nativePayload));
        return preset;
    }

    private static HologramPresets.Cell cell(Block block, int x, int metadata, NBTTagCompound payload)
        throws Exception {
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(block), x, 0, 0, -1, false);
        cell.block = Block.blockRegistry.getNameForObject(block);
        cell.metadata = metadata;
        cell.tileNbt = payload == null ? "" : HologramBlueprintNbt.encodeCaptured(payload);
        return cell;
    }

    private void unchangedSource() {
        ItemStack source = ((TileEntityChest) world.getTileEntity(X, Y, SOURCE_Z)).getStackInSlot(0);
        require(
            source != null && source.getItem() == Items.diamond
                && source.stackSize == 2
                && world.getBlock(X + 7, Y, SOURCE_Z) == Blocks.furnace,
            "source_inventory_and_native_tile_remain_untouched");
    }

    private void dumpCells(String phase) {
        net.minecraft.nbt.NBTTagList cells = capture.state.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            int x = row.getInteger("x"), y = row.getInteger("y"), z = row.getInteger("z");
            TileEntity tile = world.getTileEntity(x, y, z);
            System.out.println(
                PREFIX + "CELL phase="
                    + phase
                    + " index="
                    + i
                    + " at="
                    + x
                    + ","
                    + y
                    + ","
                    + z
                    + " status="
                    + row.getString("status")
                    + " reason="
                    + row.getString("reason")
                    + " actualBlock="
                    + Block.blockRegistry.getNameForObject(world.getBlock(x, y, z))
                    + " actualMeta="
                    + world.getBlockMetadata(x, y, z)
                    + " actualTile="
                    + (tile == null ? "none"
                        : tile.getClass()
                            .getName()));
        }
    }

    private boolean allAir() {
        for (int offset = 0; offset < 8; offset++) if (!world.isAirBlock(X + offset, Y, TARGET_Z)) return false;
        return true;
    }

    private void stock(int slot, ItemStack stack) {
        player.inventory.mainInventory[slot] = stack;
    }

    private int stock(Block block) {
        return stock(Item.getItemFromBlock(block));
    }

    private int stock(Item item) {
        int count = 0;
        for (int slot = 1; slot < player.inventory.mainInventory.length; slot++) {
            ItemStack stack = player.inventory.mainInventory[slot];
            if (stack != null && stack.getItem() == item) count += stack.stackSize;
        }
        return count;
    }

    private static NBTTagCompound tag(String key, String value) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(key, value);
        return tag;
    }

    private void action(String op, NBTTagCompound fields) {
        require(capture.state != null, "server_receipt_before_" + op);
        NBTTagCompound action = (NBTTagCompound) fields.copy();
        action.setString("session", capture.state.getString("session"));
        action.setString("op", op);
        action.setLong("planRevision", capture.state.getLong("planRevision"));
        advance();
        HologramService.handle(player, action);
        HologramSmokeTest.flushTransport();
    }

    private void settle() {
        for (int tick = 0; tick < 128; tick++) {
            advance();
            HologramService.tick();
            HologramSmokeTest.flushTransport();
            if (capture.state.getInteger("job") != 1) return;
        }
        throw new AssertionError("bounded partial placement job timeout");
    }

    private void advance() {
        world.getWorldInfo()
            .incrementTotalWorldTime(world.getTotalWorldTime() + 20);
    }

    @SuppressWarnings("unchecked")
    private static void removeAdapter(HologramNativePlacement.Adapter expected) throws Exception {
        Field field = HologramNativePlacement.class.getDeclaredField("ADAPTERS");
        field.setAccessible(true);
        synchronized (HologramNativePlacement.class) {
            Map<String, HologramNativePlacement.Adapter> adapters = (Map<String, HologramNativePlacement.Adapter>) field
                .get(null);
            if (adapters.get(ADAPTER_ID) != expected)
                throw new AssertionError("temporary native adapter ownership changed");
            adapters.remove(ADAPTER_ID);
        }
    }

    private void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(
            label + " job="
                + (capture.state == null ? -1 : capture.state.getInteger("job"))
                + " status="
                + (capture.state == null ? "none" : capture.state.getString("status")));
        assertions++;
        System.out.println(PREFIX + "PASS " + label);
    }

    private static final class NativeExtraFixture implements HologramNativePlacement.Adapter {

        public boolean supports(Block block) {
            return block == Blocks.furnace;
        }

        public TileEntity create(Block block, int metadata, NBTTagCompound tag, World detachedWorld) {
            TileEntityFurnace tile = new TileEntityFurnace();
            tile.readFromNBT(tag);
            tile.setWorldObj(detachedWorld);
            return tile;
        }

        public List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
            List<ItemStack> costs = new ArrayList<>();
            costs.add(new ItemStack(Blocks.furnace));
            costs.add(new ItemStack(Items.diamond));
            return costs;
        }
    }

    private static final class CaptureNetwork extends SimpleNetworkWrapper {

        NBTTagCompound state;
        private final HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();

        CaptureNetwork() {
            super("gtmb_partial_observer");
        }

        public void sendTo(IMessage message, EntityPlayerMP recipient) {
            if (!USER.equals(recipient.getUniqueID())) return;
            NBTTagCompound next = HologramSmokeTest.decodeState(message, assembly);
            if (next != null) state = next;
        }
    }
}
