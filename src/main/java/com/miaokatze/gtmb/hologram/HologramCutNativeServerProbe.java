package com.miaokatze.gtmb.hologram;

import java.nio.file.Files;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.security.PlayerSource;
import appeng.api.parts.IPart;
import appeng.me.storage.ItemCellInventory;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Opt-in fixture and independent disk/world observer. All cut operations arrive from the real client. */
public final class HologramCutNativeServerProbe {

    private EntityPlayerMP actor;
    private final long startupBudgetNanos = Math
        .max(60L, Math.min(3600L, Long.getLong("gtmb.hologram.nativeStartupSeconds", 1800L))) * 1000000000L;
    private long startupStarted;
    private WorldServer world;
    private NBTTagCompound originalA, originalB;
    private final NBTTagList observations = new NBTTagList();
    private boolean done, cutA, cutB, pastedA, restoredB;
    private int ticks;
    private boolean compositeReady, compositeCut, compositePasted, compositeRecut, compositeUndone;
    private NBTTagCompound compositeOriginal, recutOriginal, originalCompositeCell;
    private String compositeDiskHash, recutDiskHash;
    private int immutablePreviewChecks, originalAeOwner;
    private boolean placementSkipReady, placementSkipCut, placementSkipLock, placementSkipPasted;
    private NBTTagCompound placementSkipOriginal;
    private String placementSkipDiskHash;
    private boolean ordinaryReady, ordinaryPolicyApplied, ordinaryCompleted;
    private Block ordinaryAe, ordinaryGt;
    private String ordinarySession;
    private long ordinaryReviewRevision = -1, ordinaryReviewNonce, ordinaryAppliedRevision, ordinaryAppliedNonce;
    private long ordinaryAppliedTick, ordinaryAdmissionTick = -1;
    private int ordinaryRepeatedSnapshots;

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.cutNativeServer")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramCutNativeServerProbe());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = MinecraftServer.getServer();
        try {
            long now = System.nanoTime();
            if (actor == null) {
                if (startupStarted == 0) startupStarted = now;
                require(now - startupStarted < startupBudgetNanos, "native fixture real-client startup timeout");
            } else require(++ticks < 12000, "native cut timeout");
            require(
                !Files.exists(
                    HologramV011Evidence.directory()
                        .resolve("cut-client-fail.txt")),
                "paired client failed during startup or active validation");
            world = server.worldServerForDimension(0);
            require(
                world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-cut-v020-"),
                "isolated cut world");
            if (actor == null) {
                if (server.getConfigurationManager().playerEntityList.isEmpty()) return;
                require(server.getConfigurationManager().playerEntityList.size() == 1, "one real Netty client");
                actor = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
                require(!(actor instanceof net.minecraftforge.common.util.FakePlayer), "real player required");
                actor.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
                // Real Shift is also vanilla flight descent. Stand on real blocks so held selector inputs cannot
                // lower the actor away from the raycast fixtures during natural client/server ticks.
                actor.capabilities.allowFlying = actor.capabilities.isFlying = false;
                actor.sendPlayerAbilities();
                for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++)
                    actor.inventory.mainInventory[slot] = null;
                actor.inventory.mainInventory[0] = new ItemStack(
                    (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
                actor.inventory.currentItem = 0;
                loadFixtureChunks(47, 57, 63, 70);
                supportPlatform(47, 57, 66, 69);
                originalA = chest(48, Items.diamond, 7, "native-cut-A");
                originalB = chest(52, Items.emerald, 3, "native-cut-B");
                require(
                    world.isAirBlock(49, 80, 64) && world.isAirBlock(53, 80, 64),
                    "real air cells in both selections");
                require(
                    setBlockChecked(49, 81, 64, Blocks.stone, 0, 3) && setBlockChecked(53, 81, 64, Blocks.stone, 0, 3),
                    "physical second-corner sight markers; boxes still contain two air cells");
                require(setBlockChecked(56, 79, 64, Blocks.stone, 0, 3), "real paste sight support");
                actor.playerNetServerHandler.setPlayerLocation(50.5, 80, 67.5, 180, 0);
                actor.inventoryContainer.detectAndSendChanges();
                NBTTagCompound ready = base();
                ready.setTag("originalA", originalA.copy());
                ready.setTag("originalB", originalB.copy());
                ready.setIntArray("sourceA", new int[] { 48, 80, 64, 49, 81, 64 });
                ready.setIntArray("sourceB", new int[] { 52, 80, 64, 53, 81, 64 });
                ready.setIntArray("pasteOrigin", new int[] { 56, 80, 64 });
                HologramV011Evidence.write("cut-server-ready.nbt", ready);
                return;
            }
            if (Files.exists(
                HologramCutService.saveRoot()
                    .resolve("gtmb-cut")
                    .resolve(actor.getUniqueID() + ".nbt"))) {
                NBTTagCompound durable = new HologramCutStorage(HologramCutService.saveRoot(), actor.getUniqueID())
                    .load();
                if (!durable.hasKey("pending", 10)) {
                    int count = durable.getTagList("entries", 10)
                        .tagCount();
                    if (!cutA && world.isAirBlock(48, 80, 64) && count == 1) {
                        require(world.isAirBlock(49, 81, 64), "complete first selection cleared");
                        noDrops();
                        cutA = true;
                        observe("first-cut-disk-and-air", durable);
                    }
                    if (cutA && !cutB && world.isAirBlock(52, 80, 64) && count == 2) {
                        require(world.isAirBlock(53, 81, 64), "complete second selection cleared");
                        noDrops();
                        cutB = true;
                        observe("second-cut-fifo-disk-and-air", durable);
                        actor.playerNetServerHandler.setPlayerLocation(55.5, 80, 67, 180, 0);
                    }
                    if (cutB && !pastedA && world.getBlock(56, 80, 64) == Blocks.chest && count == 1) {
                        require(
                            tileEqual(originalA, world.getTileEntity(56, 80, 64)),
                            "paste retains complete chest inventory/custom item NBT");
                        require(world.isAirBlock(57, 80, 64), "air cell pasted faithfully");
                        require(
                            world.getBlock(57, 81, 64) == Blocks.stone && world.isAirBlock(56, 81, 64),
                            "complete mixed air/stone region pasted");
                        require(world.isAirBlock(48, 80, 64), "paste does not resurrect original");
                        pastedA = true;
                        observe("paste-consumed-original-nbt-retained", durable);
                    }
                    if (pastedA && !restoredB && world.getBlock(52, 80, 64) == Blocks.chest && count == 0) {
                        require(
                            tileEqual(originalB, world.getTileEntity(52, 80, 64)),
                            "undo retains original chest NBT/inventory");
                        require(
                            world.getBlock(53, 81, 64) == Blocks.stone && world.isAirBlock(53, 80, 64),
                            "undo restores complete original air/stone region");
                        noDrops();
                        restoredB = true;
                        observe("undo-restores-original-and-consumes", durable);
                        createComposite();
                    }
                    observeComposite(durable, count);
                    observePlacementSkip(durable, count);
                }
            }
            require(
                !Files.exists(
                    HologramV011Evidence.directory()
                        .resolve("cut-client-fail.txt")),
                "native client did not fail");
            if (placementSkipPasted && !ordinaryReady
                && HologramV011Evidence.read("ordinary-client-prepare.nbt") != null) createOrdinaryBlueprint();
            if (ordinaryReady) observeOrdinaryBlueprint();
            NBTTagCompound result = HologramV011Evidence.read("cut-client-final.nbt");
            if (result == null) return;
            require(
                result.getBoolean("passed") && cutA
                    && cutB
                    && pastedA
                    && restoredB
                    && compositeUndone
                    && placementSkipPasted
                    && ordinaryCompleted,
                "all independently witnessed world transitions completed");
            NBTTagCompound proof = base();
            proof.setTag("actualClient", result.copy());
            proof.setTag("observations", observations.copy());
            proof.setBoolean("allMutationsFromActualNettyClient", true);
            proof.setInteger("immutablePreviewChecks", immutablePreviewChecks);
            HologramV011Evidence.write("cut-server-final.nbt", proof);
            HologramV011Evidence
                .marker("cut-server-pass.txt", "PASS actual Netty UI cut/paste/undo, disk, NBT and no drops");
            done = true;
            server.initiateShutdown();
        } catch (Throwable failure) {
            failure.printStackTrace();
            try {
                HologramV011Evidence.marker("cut-server-fail.txt", "FAIL " + failure);
            } catch (Exception evidence) {
                failure.addSuppressed(evidence);
            }
            done = true;
            server.initiateShutdown();
        }
    }

    private NBTTagCompound chest(int x, Item item, int amount, String name) {
        require(world.isAirBlock(x, 80, 64) && setBlockChecked(x, 80, 64, Blocks.chest, 2, 3), "fresh real chest");
        require(
            world.getTileEntity(x, 80, 64) instanceof TileEntityChest,
            "actual loaded chunk created a native chest tile");
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(x, 80, 64);
        chest.func_145976_a(name);
        ItemStack stack = new ItemStack(item, amount);
        NBTTagCompound custom = new NBTTagCompound();
        custom.setString("nativeCutWitness", name);
        custom.setLong("serial", 202610150020L);
        stack.setTagCompound(custom);
        chest.setInventorySlotContents(4, stack);
        chest.markDirty();
        NBTTagCompound result = new NBTTagCompound();
        chest.writeToNBT(result);
        require(
            chest.getWorldObj() == world && chest.getStackInSlot(4) != null
                && chest.getStackInSlot(4)
                    .getItem() == item
                && chest.getStackInSlot(4).stackSize == amount
                && name.equals(
                    chest.getStackInSlot(4)
                        .getTagCompound()
                        .getString("nativeCutWitness"))
                && tileEqual(result, world.getTileEntity(x, 80, 64)),
            "actual world chest retains its complete fixture inventory");
        return result;
    }

    private static boolean tileEqual(NBTTagCompound expected, TileEntity tile) {
        if (tile == null) return false;
        NBTTagCompound a = (NBTTagCompound) expected.copy(), b = new NBTTagCompound();
        tile.writeToNBT(b);
        for (String coordinate : new String[] { "x", "y", "z" }) {
            a.removeTag(coordinate);
            b.removeTag(coordinate);
        }
        return a.equals(b);
    }

    private void noDrops() {
        require(
            world.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(45, 77, 61, 92, 86, 73))
                .isEmpty(),
            "cut/undo created no item entities");
    }

    private void createComposite() throws Exception {
        // The EBF helper inspects an x/z +/-5 cube before placing its controller.
        loadFixtureChunks(59, 77, 59, 70);
        BaseMetaTileEntity gt = HologramInteractionSmoke.createFixture(world, actor, 64, 80, 64);
        require(
            world.getBlock(64, 80, 64) == gregtech.api.GregTechAPI.sBlockMachines
                && world.getTileEntity(64, 80, 64) == gt
                && gt.getWorldObj() == world
                && gt.getMetaTileEntity() != null,
            "real loaded composite chunk contains the native GT controller");
        // The native EBF helper first requires its entire surrounding cube to be fresh air.
        supportPlatform(63, 77, 66, 70);
        gt.setOwnerName("RawNativeOwner");
        gt.setOwnerUuid(java.util.UUID.fromString("7d3c5811-5c28-4897-82e9-02dd31791a20"));
        ItemStack sample = new ItemStack(Items.diamond, 11);
        NBTTagCompound custom = new NBTTagCompound();
        custom.setString("fullRawWitness", "GT native inventory retained");
        sample.setTagCompound(custom);
        gt.setInventorySlotContents(1, sample);
        Block fmp = nativeBlock("codechicken.multipart.BlockMultipart"),
            cable = nativeBlock("appeng.block.networking.BlockCableBus");
        NBTTagCompound raw = HologramNativeCompatibilityFixture.seed(fmp, 66, 80, 64);
        raw.setString("id", "savedMultipart");
        NBTTagCompound cableTag = HologramNativeCompatibilityFixture.seed(cable, 66, 80, 64);
        cableTag.setString("id", "ae2_cablebus");
        raw.getTagList("parts", 10)
            .appendTag(cableTag);
        require(setBlockChecked(66, 80, 64, fmp, 0, 2), "real composite FMP block");
        TileEntity multipart = HologramCutWorld.createTile(world, raw);
        multipart.setWorldObj(world);
        world.getChunkFromBlockCoords(66, 64)
            .addTileEntity(multipart);
        HologramCutWorld.loadMultipart(multipart);
        HologramCutWorld.describeMultipart(world, multipart);
        originalAeOwner = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(actor.getGameProfile());
        require(originalAeOwner >= 0, "real AE registered owner");
        nativeCable(multipart).getGridNode()
            .setPlayerID(originalAeOwner);
        require(setBlockChecked(67, 80, 64, nativeBlock("appeng.block.storage.BlockDrive"), 0, 2), "real AE drive");
        require(
            world.getTileEntity(66, 80, 64) == multipart && world.getTileEntity(67, 80, 64) instanceof TileDrive,
            "actual world contains both native multipart and AE drive tiles");
        TileDrive drive = (TileDrive) world.getTileEntity(67, 80, 64);
        drive.setOrientation(ForgeDirection.WEST, ForgeDirection.UP);
        ItemStack cell = AEApi.instance()
            .definitions()
            .items()
            .cell1k()
            .maybeStack(1)
            .get();
        require(
            new ItemCellInventory(cell, null).injectItems(
                AEItemStack.create(new ItemStack(Items.emerald, 37)),
                Actionable.MODULATE,
                new PlayerSource(actor, null)) == null,
            "native filled cell");
        cell.setStackDisplayName("Full raw native cell identity");
        originalCompositeCell = new NBTTagCompound();
        cell.writeToNBT(originalCompositeCell);
        drive.getInternalInventory()
            .setInventorySlotContents(3, cell);
        drive.onReady();
        drive.getGridNode(ForgeDirection.UNKNOWN)
            .setPlayerID(originalAeOwner);
        chest(68, Items.emerald, 9, "native-fullraw-chest");
        require(
            setBlockChecked(69, 81, 64, Blocks.stone, 0, 3) && setBlockChecked(72, 79, 64, Blocks.stone, 0, 3),
            "real raw selection marker and paste support");
        actor.playerNetServerHandler.setPlayerLocation(66.5, 80, 67.5, 180, 0);
        NBTTagCompound proof = base();
        proof.setTag("gt", tile(gt));
        proof.setTag("multipart", tile(multipart));
        proof.setTag("drive", tile(drive));
        proof.setInteger("originalAeOwner", originalAeOwner);
        HologramV011Evidence.write("cut-composite-server-ready.nbt", proof);
        compositeReady = true;
    }

    private void observeComposite(NBTTagCompound durable, int count) throws Exception {
        if (!compositeReady) return;
        NBTTagList rows = durable.getTagList("entries", 10);
        if (!compositeCut && count == 1 && world.isAirBlock(64, 80, 64)) {
            require(
                world.isAirBlock(66, 80, 64) && world.isAirBlock(67, 80, 64)
                    && world.isAirBlock(68, 80, 64)
                    && world.isAirBlock(69, 81, 64),
                "complete real composite cut");
            compositeOriginal = (NBTTagCompound) rows.getCompoundTagAt(0)
                .copy();
            require(
                compositeOriginal.getInteger("sizeX") == 6 && compositeOriginal.getInteger("sizeY") == 2
                    && compositeOriginal.getInteger("sizeZ") == 1,
                "real mixed-air composite volume");
            compositeDiskHash = diskHash();
            compositeCut = true;
            noDrops();
            observe("composite-cut-raw-disk-and-air", durable);
            actor.playerNetServerHandler.setPlayerLocation(74.5, 80, 68, 180, 0);
        }
        if (compositeCut && !compositePasted && count == 1) {
            require(
                rows.getCompoundTagAt(0)
                    .equals(compositeOriginal) && diskHash().equals(compositeDiskHash),
                "all raw/pose previews retain exact original queue entry and disk bytes");
            immutablePreviewChecks++;
        }
        if (compositeCut && !compositePasted
            && count == 0
            && world.getTileEntity(72, 80, 69) instanceof BaseMetaTileEntity) {
            verifyCompositePlaced();
            require(immutablePreviewChecks >= 5, "independent queue invariance witnessed through multiple real ticks");
            compositePasted = true;
            noDrops();
            observe("composite-pose-paste-consumed", durable);
        }
        if (compositePasted && !compositeRecut && count == 1 && world.isAirBlock(72, 80, 69)) {
            recutOriginal = (NBTTagCompound) rows.getCompoundTagAt(0)
                .copy();
            require(
                recutOriginal.getInteger("sizeX") == 1 && recutOriginal.getInteger("sizeY") == 2
                    && recutOriginal.getInteger("sizeZ") == 6,
                "recut transformed non-default source volume");
            recutDiskHash = diskHash();
            compositeRecut = true;
            noDrops();
            observe("composite-recut-nondefault-pose", durable);
        }
        if (compositeRecut && !compositeUndone && count == 1) {
            require(
                rows.getCompoundTagAt(0)
                    .equals(recutOriginal) && diskHash().equals(recutDiskHash),
                "recut preview/cancel leaves non-default source raw entry intact");
        }
        if (compositeRecut && !compositeUndone
            && count == 0
            && world.getTileEntity(72, 80, 69) instanceof BaseMetaTileEntity) {
            verifyCompositePlaced();
            compositeUndone = true;
            noDrops();
            observe("composite-undo-original-pose-consumed", durable);
            createPlacementSkip();
        }
    }

    /** This final fixture leaves the preceding genuine original-position undo unchanged. */
    private void createPlacementSkip() throws Exception {
        loadFixtureChunks(83, 90, 63, 70);
        supportPlatform(83, 90, 66, 69);
        require(world.isAirBlock(84, 80, 64) && world.isAirBlock(88, 80, 64), "fresh independent skip fixture");
        require(setBlockChecked(84, 80, 64, Blocks.gold_block, 0, 3), "one real original gold block");
        require(setBlockChecked(88, 79, 64, Blocks.stone, 0, 3), "one real skip-placement support");
        actor.playerNetServerHandler.setPlayerLocation(84.5, 80, 67, 180, 0);
        placementSkipReady = true;
        NBTTagCompound proof = base();
        proof.setIntArray("source", new int[] { 84, 80, 64 });
        proof.setIntArray("target", new int[] { 88, 80, 64 });
        HologramV011Evidence.write("cut-placement-skip-server-ready.nbt", proof);
    }

    /** Fixture preparation only. No ordinary workspace/session/action is created or confirmed here. */
    private void createOrdinaryBlueprint() throws Exception {
        loadFixtureChunks(93, 105, 63, 70);
        supportPlatform(93, 105, 63, 70);
        ordinaryAe = Block.getBlockFromName("ae2fc:fluid_interface");
        ordinaryGt = Block.getBlockFromName("gregtech:gt.blockmachines");
        require(
            ordinaryAe != null && ordinaryAe != Blocks.air && ordinaryGt != null && ordinaryGt != Blocks.air,
            "ordinary GUI fixture requires actual AE2FC and GT registries");
        for (int x : new int[] { 94, 95, 100, 101 }) require(
            world.isAirBlock(x, 80, 64) && world.getTileEntity(x, 80, 64) == null,
            "ordinary GUI source/target must start in genuine air");
        require(setBlockChecked(94, 80, 64, ordinaryAe, 0, 3), "native ordinary AE2FC source");
        TileEntity ae = world.getTileEntity(94, 80, 64);
        require(
            ae != null && ae.getClass()
                .getName()
                .contains("TileFluidInterface"),
            "native fluid-interface TE");
        NBTTagCompound aeRaw = HologramNativeCapture.capture(ae);
        require(
            aeRaw.hasKey("FluidInv", 10) && aeRaw.getCompoundTag("FluidInv")
                .hasKey("#0", 10)
                && aeRaw.getCompoundTag("FluidInv")
                    .hasKey("#5", 10),
            "native six empty fluid slots");
        require(setBlockChecked(95, 80, 64, ordinaryGt, 3, 3), "native ordinary GT source");
        require(world.getTileEntity(95, 80, 64) instanceof BaseMetaTileEntity, "native ordinary GT base");
        BaseMetaTileEntity gt = (BaseMetaTileEntity) world.getTileEntity(95, 80, 64);
        gt.setInitialValuesAsNBT(null, (short) 32765);
        gt.setFrontFacing(ForgeDirection.EAST);
        gt.disableWorking();
        require(
            gt.getMetaTileID() == 32765
                && gt.getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEBasicTank,
            "actual registered atmospheric intake");
        net.minecraftforge.fluids.Fluid fluid = net.minecraftforge.fluids.FluidRegistry.getFluid("air");
        require(fluid != null, "actual registered air");
        ((gregtech.api.metatileentity.implementations.MTEBasicTank) gt
            .getMetaTileEntity()).mFluid = new net.minecraftforge.fluids.FluidStack(fluid, 1000);
        NBTTagCompound gtRaw = HologramNativeCapture.capture(gt);
        require(
            gtRaw.getBoolean("mWorks") && gtRaw.getCompoundTag("mFluid")
                .getInteger("Amount") == 1000,
            "native stopped atmospheric intake serializer");
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = java.util.UUID.randomUUID()
            .toString();
        preset.name = "Native GUI AE2FC and GT32765";
        preset.schema = 2;
        preset.controllerType = "area";
        preset.authorName = actor.getCommandSenderName();
        preset.authorUuid = actor.getUniqueID()
            .toString();
        preset.createdAt = System.currentTimeMillis();
        HologramPresets.Cell a = HologramPresets.cell(new ItemStack(ordinaryAe), 0, 0, 0, -1, false);
        a.block = "ae2fc:fluid_interface";
        a.tileNbt = HologramBlueprintNbt.encodeCanonical(ordinaryAe, aeRaw);
        HologramPresets.Cell g = HologramPresets.cell(new ItemStack(ordinaryGt, 1, 32765), 1, 0, 0, 5, false);
        g.block = "gregtech:gt.blockmachines";
        g.metadata = 3;
        g.tileNbt = HologramBlueprintNbt.encodeCanonical(ordinaryGt, gtRaw);
        preset.cells.add(a);
        preset.cells.add(g);
        HologramBlueprintVersions.stamp(preset);
        HologramPresets.validate(preset);
        actor.theItemInWorldManager.setGameType(WorldSettings.GameType.CREATIVE);
        actor.capabilities.allowFlying = actor.capabilities.isFlying = false;
        actor.sendPlayerAbilities();
        actor.playerNetServerHandler.setPlayerLocation(100.5, 80, 67.5, 180, 0);
        NBTTagCompound proof = base();
        proof.setString(
            "blueprintJson",
            new String(HologramServerLibrary.encodeShare(preset), java.nio.charset.StandardCharsets.UTF_8));
        proof.setString("fixtureName", preset.name);
        proof.setIntArray("target", new int[] { 100, 80, 64 });
        proof.setBoolean("fixturePreparationOnly", true);
        proof.setBoolean("ordinarySessionCreatedByProbe", false);
        HologramV011Evidence.write("ordinary-server-ready.nbt", proof);
        ordinaryReady = true;
    }

    /** Observes the real player's production workspace, Netty actions and naturally advanced job. */
    private void observeOrdinaryBlueprint() throws Exception {
        if (ordinaryCompleted) return;
        HologramSession s = HologramWorkspace.find(actor.getUniqueID());
        if (s == null || s.virtualPreset == null || !"Native GUI AE2FC and GT32765".equals(s.virtualPreset.name))
            return;
        require(
            s.player == actor && s.capture != null && s.capture.cells.size() == 2,
            "ordinary private upload must bind the actual connected actor and two native cells");
        if (s.virtualLocked && s.placementReviewIssued
            && !s.placementDeidentificationChosen
            && ordinaryReviewRevision < 0) {
            require(
                s.x == 100 && s.y == 80 && s.z == 64 && s.blueprintAwaitingConfirmation && s.job == 0,
                "OS world input must lock the ordinary target without writing it");
            ordinarySession = s.id;
            ordinaryReviewRevision = s.planRevision;
            ordinaryReviewNonce = s.placementReviewNonce;
            HologramV011Evidence.write("ordinary-server-before-policy.nbt", ordinaryState(s));
        }
        if (s.placementDeidentificationChosen && !ordinaryPolicyApplied) {
            require(
                ordinaryReviewRevision >= 0 && s.id.equals(ordinarySession)
                    && s.virtualLocked
                    && s.x == 100
                    && s.y == 80
                    && s.z == 64
                    && s.planRevision == ordinaryReviewRevision + 1
                    && s.placementReviewNonce == ordinaryReviewNonce + 1
                    && s.placementDeidentificationLevel == 1
                    && s.job == 0
                    && s.blueprintAwaitingConfirmation,
                "real manual policy ACK must preserve the exact ghost and refresh revision/nonce only once");
            ordinaryPolicyApplied = true;
            ordinaryAppliedRevision = s.planRevision;
            ordinaryAppliedNonce = s.placementReviewNonce;
            ordinaryAppliedTick = world.getTotalWorldTime();
            HologramV011Evidence.write("ordinary-server-policy-applied.nbt", ordinaryState(s));
        }
        if (!ordinaryPolicyApplied) return;
        require(
            s.id.equals(ordinarySession) && s.planRevision == ordinaryAppliedRevision
                && s.placementReviewNonce == ordinaryAppliedNonce,
            "background snapshots must not refresh the reviewed plan");
        if (ordinaryRepeatedSnapshots < 3
            && world.getTotalWorldTime() >= ordinaryAppliedTick + 5L * (ordinaryRepeatedSnapshots + 1)) {
            // Read-only retransmission stimulus: unmodified production DTOs over the actual connection.
            HologramSnapshots.send(s);
            ordinaryRepeatedSnapshots++;
            if (ordinaryRepeatedSnapshots == 3)
                HologramV011Evidence.write("ordinary-server-repeated-snapshots.nbt", ordinaryState(s));
        }
        if (s.job == 1 && ordinaryAdmissionTick < 0) ordinaryAdmissionTick = world.getTotalWorldTime();
        if (s.job != 3) return;
        require(
            ordinaryRepeatedSnapshots == 3 && s.completed == 2 && s.cursor == 2,
            "only the naturally completed production two-cell world job can pass");
        require(
            world.getBlock(100, 80, 64) == ordinaryAe && world.getTileEntity(100, 80, 64) != null
                && world.getTileEntity(100, 80, 64)
                    .getClass()
                    .getName()
                    .contains("TileFluidInterface"),
            "actual ordinary destination fluid interface");
        require(
            world.getBlock(101, 80, 64) == ordinaryGt && world.getTileEntity(101, 80, 64) instanceof BaseMetaTileEntity,
            "actual ordinary destination GT base");
        BaseMetaTileEntity gt = (BaseMetaTileEntity) world.getTileEntity(101, 80, 64);
        NBTTagCompound raw = HologramNativeCapture.capture(gt);
        require(
            gt.getMetaTileID() == 32765 && !gt.isAllowedToWork()
                && raw.getCompoundTag("mFluid")
                    .getInteger("Amount") == 1000,
            "ordinary placement must retain native GT identity, stopped configuration and physical air");
        NBTTagCompound proof = ordinaryState(s);
        proof.setTag("actualAeTile", HologramNativeCapture.capture(world.getTileEntity(100, 80, 64)));
        proof.setTag("actualGtTile", raw);
        proof.setBoolean("actualWorldCompletion", true);
        proof.setBoolean("backendDirectBuildUsed", false);
        HologramV011Evidence.write("ordinary-server-world-complete.nbt", proof);
        ordinaryCompleted = true;
    }

    private NBTTagCompound ordinaryState(HologramSession s) {
        NBTTagCompound proof = base();
        proof.setString("session", s.id);
        proof.setLong("planRevision", s.planRevision);
        proof.setLong("placementReviewNonce", s.placementReviewNonce);
        proof.setInteger("level", s.placementDeidentificationLevel);
        proof.setInteger("job", s.job);
        proof.setInteger("completed", s.completed);
        proof.setString("status", s.status);
        proof.setLong("worldTick", world.getTotalWorldTime());
        proof.setLong("observedJobAdmissionTick", ordinaryAdmissionTick);
        proof.setInteger("readOnlySnapshotRetransmissions", ordinaryRepeatedSnapshots);
        proof.setTag("runtime", HologramRuntimeMetrics.snapshot());
        return proof;
    }

    private void observePlacementSkip(NBTTagCompound durable, int count) throws Exception {
        if (!placementSkipReady) return;
        if (!placementSkipCut && count == 1 && world.isAirBlock(84, 80, 64)) {
            require(
                durable.getBoolean(HologramCutPlacementPreference.SKIP_PROMPT),
                "placement skip persisted before final fixture");
            placementSkipOriginal = (NBTTagCompound) durable.getTagList("entries", 10)
                .getCompoundTagAt(0)
                .copy();
            require(
                placementSkipOriginal.getInteger("sizeX") == 1 && placementSkipOriginal.getInteger("sizeY") == 1
                    && placementSkipOriginal.getInteger("sizeZ") == 1,
                "independent one-cell real cut");
            NBTTagList cells = placementSkipOriginal.getTagList("cells", 10);
            require(
                cells.tagCount() == 1 && "minecraft:gold_block".equals(
                    cells.getCompoundTagAt(0)
                        .getString("block"))
                    && cells.getCompoundTagAt(0)
                        .getInteger("meta") == 0,
                "actual original complete gold-cell payload");
            placementSkipDiskHash = diskHash();
            placementSkipCut = true;
            noDrops();
            observe("placement-skip-source-cut", durable);
            actor.playerNetServerHandler.setPlayerLocation(87.5, 80, 67, 180, 0);
        }
        if (placementSkipCut && !placementSkipPasted && count == 1) {
            require(
                world.isAirBlock(88, 80, 64) && durable.getTagList("entries", 10)
                    .getCompoundTagAt(0)
                    .equals(placementSkipOriginal) && diskHash().equals(placementSkipDiskHash),
                "preview and first lock preserve the actual source, target, queue and disk bytes");
            if (!placementSkipLock && HologramV011Evidence.read("cut-placement-skip-client-locked.nbt") != null) {
                require(
                    "LOCKED".equals(
                        HologramV011Evidence.read("cut-placement-skip-client-locked.nbt")
                            .getString("stage")),
                    "client's independently sampled locked stage");
                placementSkipLock = true;
                observe("placement-skip-lock-no-write", durable);
            }
        }
        if (placementSkipCut && !placementSkipPasted && count == 0 && world.getBlock(88, 80, 64) == Blocks.gold_block) {
            require(
                placementSkipLock && world.isAirBlock(84, 80, 64) && world.getBlockMetadata(88, 80, 64) == 0,
                "only the later separate right press placed the original exact cell");
            require(
                durable.getBoolean(HologramCutPlacementPreference.SKIP_PROMPT),
                "placement skip remains durable after consuming the final entry");
            placementSkipPasted = true;
            noDrops();
            observe("placement-skip-second-right-pasted", durable);
        }
    }

    private void verifyCompositePlaced() throws Exception {
        BaseMetaTileEntity gt = (BaseMetaTileEntity) world.getTileEntity(72, 80, 69);
        require(
            gt.getFrontFacing() == ForgeDirection.EAST && "RawNativeOwner".equals(gt.getOwnerName())
                && gt.getStackInSlot(1) != null
                && gt.getStackInSlot(1).stackSize == 11
                && gt.getStackInSlot(1)
                    .getTagCompound()
                    .getString("fullRawWitness")
                    .equals("GT native inventory retained"),
            "known Y90/source-X mirror geometry, native GT owner, inventory and item NBT");
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(72, 80, 65);
        NBTTagCompound sourceChest = compositeSourceChest();
        require(
            world.getBlockMetadata(72, 80, 65) == independentlyTransformedChestMetadata(sourceChest.getInteger("meta"))
                && chest.getStackInSlot(4).stackSize == 9
                && tileEqual(sourceChest.getCompoundTag("tile"), chest),
            "actual immutable source chest direction independently mirrored X then rotated Y90; complete chest NBT retained");
        TileDrive drive = (TileDrive) world.getTileEntity(72, 80, 66);
        require(
            drive.getForward() == ForgeDirection.SOUTH && drive.getInternalInventory()
                .getStackInSlot(3) != null
                && drive.getGridNode(ForgeDirection.UNKNOWN)
                    .getPlayerID() == originalAeOwner,
            "AE original WEST becomes SOUTH, filled cell and original owner retained");
        NBTTagCompound actualCell = new NBTTagCompound();
        drive.getInternalInventory()
            .getStackInSlot(3)
            .writeToNBT(actualCell);
        require(
            actualCell.equals(originalCompositeCell),
            "exact native AE filled storage cell item and contents NBT retained");
        TileEntity fmp = world.getTileEntity(72, 80, 67);
        require(
            tile(fmp).getTagList("parts", 10)
                .tagCount() == 3
                && nativeCable(fmp).getGridNode()
                    .getPlayerID() == originalAeOwner,
            "native FMP three parts and AE owner retained");
        require(
            world.isAirBlock(72, 80, 68) && world.getBlock(72, 81, 64) == Blocks.stone && world.isAirBlock(64, 80, 64),
            "known transformed air/marker geometry and original source remains empty");
    }

    private String diskHash() throws Exception {
        byte[] data = Files.readAllBytes(
            HologramCutService.saveRoot()
                .resolve("gtmb-cut")
                .resolve(actor.getUniqueID() + ".nbt"));
        StringBuilder out = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256")
            .digest(data)) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private NBTTagCompound compositeSourceChest() {
        NBTTagList cells = compositeOriginal.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (cell.getInteger("x") == 4 && cell.getInteger("y") == 0 && cell.getInteger("z") == 0) {
                require(
                    "minecraft:chest".equals(cell.getString("block")) && cell.hasKey("tile", 10),
                    "actual source disk contains the expected native chest cell");
                return cell;
            }
        }
        throw new AssertionError("actual source disk chest missing");
    }

    private static int independentlyTransformedChestMetadata(int sourceMetadata) {
        // Vanilla chest creation can choose a facing different from setBlock's requested seed metadata.
        // Derive the expected result from the captured immutable source, without calling product transforms.
        require(sourceMetadata >= 2 && sourceMetadata <= 5, "actual source chest has horizontal facing metadata");
        ForgeDirection source = ForgeDirection.getOrientation(sourceMetadata);
        int afterXMirrorX = -source.offsetX;
        int afterY90X = -source.offsetZ, afterY90Z = afterXMirrorX;
        for (ForgeDirection target : new ForgeDirection[] { ForgeDirection.NORTH, ForgeDirection.SOUTH,
            ForgeDirection.WEST, ForgeDirection.EAST })
            if (target.offsetX == afterY90X && target.offsetZ == afterY90Z) return target.ordinal();
        throw new AssertionError("independent horizontal facing transform failed");
    }

    private void supportPlatform(int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) require(
            world.isAirBlock(x, 79, z) && setBlockChecked(x, 79, z, Blocks.stone, 0, 3),
            "real standing platform strictly below all source and target volumes");
    }

    /** Independent fixture oracle; deliberately does not call the production transaction loader. */
    private void loadFixtureChunks(int minX, int maxX, int minZ, int maxZ) {
        java.util.Set<Chunk> identities = java.util.Collections
            .newSetFromMap(new java.util.IdentityHashMap<Chunk, Boolean>());
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
            Chunk loaded = world.theChunkProviderServer.loadChunk(cx, cz);
            require(
                loaded != null && !(loaded instanceof EmptyChunk)
                    && loaded.xPosition == cx
                    && loaded.zPosition == cz
                    && loaded.isChunkLoaded
                    && identities.add(loaded),
                "each fixture position loads a distinct genuine chunk with matching coordinates");
            require(
                world.theChunkProviderServer.chunkExists(cx, cz) && world.getChunkFromChunkCoords(cx, cz) == loaded,
                "normal world reads use the exact genuine loaded fixture chunk");
        }
    }

    private boolean setBlockChecked(int x, int y, int z, Block block, int metadata, int flags) {
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        require(
            !(chunk instanceof EmptyChunk) && chunk.xPosition == (x >> 4) && chunk.zPosition == (z >> 4),
            "fixture writes never target EmptyChunk or a wrong-position chunk");
        boolean placed = world.setBlock(x, y, z, block, metadata, flags);
        // Native chest/AE placement may derive facing metadata in onBlockAdded; their complete tiles
        // are checked by each fixture. Static platform/marker/gold metadata must remain exact.
        require(
            placed && world.getBlock(x, y, z) == block
                && (block.hasTileEntity(metadata) || world.getBlockMetadata(x, y, z) == metadata),
            "fixture write is the actual expected block, with exact static-marker metadata");
        return placed;
    }

    private static Block nativeBlock(String name) {
        for (Object value : Block.blockRegistry) if (value != null && value.getClass()
            .getName()
            .equals(name)) return (Block) value;
        throw new AssertionError("native block missing " + name);
    }

    private static IPart nativeCable(TileEntity tile) throws Exception {
        for (Object part : (java.util.List<?>) tile.getClass()
            .getMethod("jPartList")
            .invoke(tile))
            if (Class.forName("appeng.fmp.CableBusPart")
                .isInstance(part)) {
                    Object bus = part.getClass()
                        .getMethod("getCableBus")
                        .invoke(part);
                    return (IPart) bus.getClass()
                        .getMethod("getPart", ForgeDirection.class)
                        .invoke(bus, ForgeDirection.UNKNOWN);
                }
        throw new AssertionError("native AE multipart cable missing");
    }

    private static NBTTagCompound tile(TileEntity tile) {
        require(tile != null, "real native tile exists");
        NBTTagCompound out = new NBTTagCompound();
        tile.writeToNBT(out);
        return out;
    }

    private void observe(String label, NBTTagCompound durable) throws Exception {
        NBTTagCompound proof = base();
        proof.setString("label", label);
        proof.setTag("actualDisk", durable.copy());
        if (label.equals("composite-pose-paste-consumed") || label.equals("composite-undo-original-pose-consumed")) {
            proof.setTag("actualPlacedGT", tile(world.getTileEntity(72, 80, 69)));
            proof.setTag("actualPlacedDrive", tile(world.getTileEntity(72, 80, 66)));
            proof.setTag("actualPlacedMultipart", tile(world.getTileEntity(72, 80, 67)));
            proof.setTag("actualPlacedChest", tile(world.getTileEntity(72, 80, 65)));
            proof.setTag("immutableSourceChest", compositeSourceChest().copy());
            proof.setInteger(
                "expectedChestMetadata",
                independentlyTransformedChestMetadata(compositeSourceChest().getInteger("meta")));
            proof.setString(
                "independentChestPose",
                "source-local mirror X followed by positive Y90: (dx,dz)->(-dz,-dx)");
            proof.setInteger("actualChestMetadata", world.getBlockMetadata(72, 80, 65));
            proof.setInteger("actualAeOwner", originalAeOwner);
            proof.setBoolean("actualOriginalSourceAir", world.isAirBlock(64, 80, 64));
        }
        observations.appendTag(proof);
        HologramV011Evidence.write("cut-" + label + ".nbt", proof);
    }

    private NBTTagCompound base() {
        NBTTagCompound n = new NBTTagCompound();
        n.setBoolean("passed", true);
        n.setString("releaseVersion", "0.1.4");
        n.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        n.setLong("worldTick", world.getTotalWorldTime());
        n.setDouble("actualActorX", actor.posX);
        n.setDouble("actualActorY", actor.posY);
        n.setDouble("actualActorZ", actor.posZ);
        n.setBoolean("actualActorFlying", actor.capabilities.isFlying);
        n.setBoolean("actualActorAllowFlying", actor.capabilities.allowFlying);
        n.setBoolean(
            "actualStandingPlatform",
            world.getBlock((int) Math.floor(actor.posX), 79, (int) Math.floor(actor.posZ)) == Blocks.stone);
        return n;
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError("native cut server: " + message);
    }
}
