package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.parts.IPart;
import appeng.block.AEBaseTileBlock;
import appeng.tile.grid.AENetworkTile;
import appeng.tile.networking.TileCableBus;
import appeng.tile.networking.TileCreativeEnergyCell;
import appeng.tile.networking.TileCreativeEnergyController;
import appeng.tile.networking.TileEnergyCell;
import appeng.tile.networking.TileWireless;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.common.misc.WirelessNetworkManager;

/** Opt-in real FML/world/network checks. Tick staging lets actual AE nodes and wireless range become ready. */
public final class HologramEnergyNetworkSmoke {

    private static final String PREFIX = "[GTMB-ENERGY-NETWORK] ";
    private WorldServer world;
    private EntityPlayerMP player;
    private TileEnergyCell cell;
    private TileWireless wap;
    private ItemStack terminal;
    private int ticks;
    private volatile boolean finished;
    private int checks;
    private volatile String result = "PENDING";
    private static volatile HologramEnergyNetworkSmoke installed;
    private final NBTTagCompound receipt = new NBTTagCompound();
    private ForgeChunkManager.Ticket chunkTicket;
    private int retainedTicks;
    private static final int X = 1008, Y = 88, Z = 320;
    private static final ChunkCoordIntPair[] FIXTURE_CHUNKS = { new ChunkCoordIntPair((X - 1) >> 4, Z >> 4),
        new ChunkCoordIntPair(X >> 4, Z >> 4) };

    public static void registerIfEnabled() {
        installIfEnabled();
    }

    public static synchronized void installIfEnabled() {
        if (enabled() && installed == null) {
            installed = new HologramEnergyNetworkSmoke();
            FMLCommonHandler.instance()
                .bus()
                .register(installed);
        }
    }

    /** Shared bootstrap for opt-in isolated energy/network fixtures; does not create an energy probe listener. */
    public static synchronized void ensureFixtureChunkCallback() {
        com.miaokatze.gtmb.util.ProbeChunkTickets.ensureRegistered();
    }

    public static boolean enabled() {
        return Boolean.getBoolean("gtmb.hologram.energyNetworkSmoke") || Boolean.getBoolean("energyNetworkSmoke");
    }

    public static boolean done() {
        return !enabled() || installed != null && installed.finished;
    }

    public static String status() {
        return !enabled() ? "DISABLED" : installed == null ? "PENDING" : installed.result;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        try {
            if (++ticks == 60) create();
            if (ticks > 60 && world != null) retainNativeChunks();
            if (ticks == 120) {
                verify();
            }
            if (ticks == 180) {
                verifyInfiniteGrid();
                result = "PASS";
                receipt.setString("result", result);
                releaseNativeChunks();
                writeReceipts();
                finished = true;
                System.out.println(PREFIX + "FINAL PASS assertions=" + checks + " actual-FML-grid=true");
            }
        } catch (Throwable failure) {
            result = "FAIL: " + failure;
            receipt.setString("result", result);
            try {
                releaseNativeChunks();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                writeReceipts();
            } catch (Exception writeFailure) {
                failure.addSuppressed(writeFailure);
            }
            finished = true;
            failure.printStackTrace(System.err);
            System.out.println(PREFIX + "FINAL FAIL " + failure);
        }
    }

    private void create() throws Exception {
        world = MinecraftServer.getServer()
            .worldServerForDimension(0);
        String worldName = world.getWorldInfo()
            .getWorldName();
        if (!worldName.startsWith("hologram-production-") && !worldName.startsWith("hologram-smoke-"))
            throw new IllegalStateException("Energy network fixture requires its isolated production/smoke world");
        player = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("ba2a85b9-50a9-49ba-b95f-999935da4ce1"), "GTMBEnergyBank"));
        player.inventory.mainInventory = new ItemStack[36];
        player.inventory.currentItem = 0;
        player.capabilities.isCreativeMode = false;
        player.capabilities.allowEdit = true;
        receipt.setString("world", worldName);
        receipt.setString(
            "actor",
            player.getUniqueID()
                .toString());
        receipt.setString(
            "profile",
            player.getGameProfile()
                .getName());
        player.setPosition(X + 0.5, Y + 0.5, Z + 2.5);
        acquireNativeChunks();
        check(
            world.isAirBlock(X - 1, Y, Z) && world.isAirBlock(X, Y, Z),
            "independent fixture positions are fresh air");
        cell = placeNativeFixture(
            block("appeng.block.networking.BlockEnergyCell"),
            X - 1,
            0,
            TileEnergyCell.class,
            "finiteCell");
        wap = placeNativeFixture(
            block("appeng.block.networking.BlockWireless"),
            X,
            0,
            TileWireless.class,
            "wirelessAccessPoint");
        check(!cell.isInfinite(), "actual finite cell factory does not produce an infinite provider");
        cell.getProxy()
            .setOwner(player);
        wap.getProxy()
            .setOwner(player);
        wap.setOrientation(ForgeDirection.EAST, ForgeDirection.UP);
        // Native reader initializes a finite real tile before it joins/boots; no estimated grid balance is seeded.
        NBTTagCompound power = new NBTTagCompound();
        power.setDouble("internalCurrentPower", 100000);
        cell.readFromNBT_TileEnergyCell(power);
        cell.markDirty();
        terminal = AEApi.instance()
            .definitions()
            .items()
            .wirelessTerminal()
            .maybeStack(1)
            .get();
        player.inventory.mainInventory[1] = terminal;
        System.out.println(PREFIX + "WAIT actual wireless access point boot/range");
    }

    /** Use the registered block's native tile factory; do not replace a null world tile with a mock provider. */
    private <T extends TileEntity> T placeNativeFixture(Block target, int x, int metadata, Class<T> expected,
        String key) {
        loadNativeChunk(x >> 4, Z >> 4);
        check(world.blockExists(x, Y, Z), key + " chunk is loaded");
        check(world.isAirBlock(x, Y, Z), key + " target is fresh air");
        String registryName = Block.blockRegistry.getNameForObject(target);
        check(
            registryName != null && Block.blockRegistry.getObject(registryName) == target,
            key + " block has a live native registry identity");
        boolean written = world.setBlock(x, Y, Z, target, metadata, 3);
        receipt.setString(key + "Block", registryName);
        receipt.setInteger(key + "X", x);
        receipt.setInteger(key + "Metadata", metadata);
        receipt.setBoolean(key + "BlockWrite", written);
        receipt.setBoolean(key + "HasTileEntity", target.hasTileEntity(metadata));
        if (target instanceof AEBaseTileBlock) {
            Class<? extends TileEntity> factoryType = ((AEBaseTileBlock) target).getTileEntityClass();
            receipt.setString(key + "FactoryTileClass", factoryType == null ? "null" : factoryType.getName());
            check(
                factoryType != null && expected.isAssignableFrom(factoryType),
                key + " native factory is bound to the expected tile type");
        }
        check(
            written && world.getBlock(x, Y, Z) == target && world.getBlockMetadata(x, Y, Z) == metadata,
            key + " registered block/metadata was actually written");
        TileEntity tile = world.getTileEntity(x, Y, Z);
        receipt.setBoolean(key + "AutomaticTile", tile != null);
        if (tile == null) {
            TileEntity created = target.createTileEntity(world, metadata);
            if (created == null && target instanceof AEBaseTileBlock)
                created = ((AEBaseTileBlock) target).createNewTileEntity(world, metadata);
            check(expected.isInstance(created), key + " registered native factory returns its real tile type");
            created.setWorldObj(world);
            created.xCoord = x;
            created.yCoord = Y;
            created.zCoord = Z;
            world.setTileEntity(x, Y, Z, created);
            tile = world.getTileEntity(x, Y, Z);
            check(tile == created, key + " world retains the factory tile through its native chunk lifecycle");
        }
        check(
            expected.isInstance(tile) && tile.getWorldObj() == world && !tile.isInvalid(),
            key + " live tile type/world/validation are correct");
        receipt.setString(
            key + "TileClass",
            tile.getClass()
                .getName());
        tile.markDirty();
        world.markBlockForUpdate(x, Y, Z);
        return expected.cast(tile);
    }

    private void acquireNativeChunks() {
        // Register only after the opt-in fixture has verified its isolated world.
        ensureFixtureChunkCallback();
        chunkTicket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        check(chunkTicket != null, "isolated energy fixture obtains a native Forge chunk ticket");
        chunkTicket.getModData()
            .setBoolean("gtmbEnergyProbe", true);
        for (ChunkCoordIntPair coordinate : FIXTURE_CHUNKS) {
            loadNativeChunk(coordinate.chunkXPos, coordinate.chunkZPos);
            ForgeChunkManager.forceChunk(chunkTicket, coordinate);
        }
        receipt.setString("chunkTicketMod", GTMiaoBlueprint.MODID);
        receipt.setString(
            "nativeChunkProviderClass",
            world.theChunkProviderServer.getClass()
                .getName());
        receipt.setBoolean("nativeLoadChunkOnProvideRequest", world.theChunkProviderServer.loadChunkOnProvideRequest);
        receipt.setBoolean("nativeChunkTicket", true);
        retainNativeChunks();
    }

    private Chunk loadNativeChunk(int chunkX, int chunkZ) {
        // provideChunk may return defaultEmptyChunk when loadChunkOnProvideRequest is false.
        Chunk loaded = world.theChunkProviderServer.loadChunk(chunkX, chunkZ);
        if (loaded == null || loaded instanceof EmptyChunk
            || !loaded.isChunkLoaded
            || loaded.xPosition != chunkX
            || loaded.zPosition != chunkZ
            || !world.theChunkProviderServer.chunkExists(chunkX, chunkZ))
            throw new AssertionError(PREFIX + "native provider failed to load chunk " + chunkX + "," + chunkZ);
        return loaded;
    }

    private void retainNativeChunks() {
        if (chunkTicket == null)
            throw new AssertionError(PREFIX + "fixture chunk ticket is missing before final checks");
        for (ChunkCoordIntPair coordinate : FIXTURE_CHUNKS) {
            Chunk loaded = loadNativeChunk(coordinate.chunkXPos, coordinate.chunkZPos);
            boolean forced = world.getPersistentChunks()
                .containsEntry(coordinate, chunkTicket);
            if (!forced) throw new AssertionError(PREFIX + "fixture native chunk ticket was lost at tick " + ticks);
            NBTTagCompound chunk = new NBTTagCompound();
            chunk.setInteger("x", coordinate.chunkXPos);
            chunk.setInteger("z", coordinate.chunkZPos);
            chunk.setBoolean("nativeLoaded", loaded.isChunkLoaded);
            chunk.setBoolean(
                "providerChunkExists",
                world.theChunkProviderServer.chunkExists(coordinate.chunkXPos, coordinate.chunkZPos));
            chunk.setBoolean("persistentTicket", forced);
            receipt.setTag("chunk" + coordinate.chunkXPos + "_" + coordinate.chunkZPos, chunk);
        }
        receipt.setInteger("nativeChunkRetainedThroughTick", ticks);
        receipt.setInteger("nativeChunkRetentionChecks", ++retainedTicks);
    }

    private void releaseNativeChunks() {
        if (chunkTicket != null) {
            ForgeChunkManager.releaseTicket(chunkTicket);
            chunkTicket = null;
            receipt.setBoolean("chunkTicketReleased", true);
            receipt.setInteger("chunkTicketReleasedAtProbeTick", ticks);
            receipt.setBoolean("chunkTicketReleasedAfterChecks", ticks == 180 && "PASS".equals(result));
        }
    }

    private void verify() throws Exception {
        check(
            wap.getActionableNode() != null && wap.getActionableNode()
                .isActive(),
            "real finite network wireless node is active");
        IEnergyGrid grid = wap.getActionableNode()
            .getGrid()
            .getCache(IEnergyGrid.class);
        ISecurityGrid security = wap.getActionableNode()
            .getGrid()
            .getCache(ISecurityGrid.class);
        check(
            security.hasPermission(player, SecurityPermissions.EXTRACT)
                && security.hasPermission(player, SecurityPermissions.BUILD),
            "placing actor has native grid permissions");
        IWirelessTermHandler handler = AEApi.instance()
            .registries()
            .wireless()
            .getWirelessTerminalHandler(terminal);
        handler.setEncryptionKey(terminal, Long.toString(wap.getLocatableSerial()), "Actual energy fixture");
        HologramMaterials.Context context = new HologramMaterials.Context(player, new HologramMaterials.Settings());
        ItemStack terminalBefore = terminal.copy();
        receipt.setTag("terminalBefore", terminalBefore.writeToNBT(new NBTTagCompound()));
        HologramEnergy.Demand demand = new HologramEnergy.Demand();
        demand.ae = BigDecimal.valueOf(137.5);
        double before = grid.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE);
        receipt.setDouble("aeBefore", before);
        receipt.setDouble("aeRequired", demand.ae.doubleValue());
        HologramEnergy.Quote quote = context.cachedEnergyQuote(demand);
        check(
            quote.affordable && quote.source.equals("AE network"),
            "legit linked carried terminal resolves actual finite AE source");
        check(
            grid.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE) == before,
            "preview does not debit network");
        HologramMaterials.Transaction transaction = context.begin();
        check(transaction.takeCachedEnergy(demand), "actual finite network payment succeeds");
        receipt.setDouble("aeAfterDebit", grid.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE));
        check(
            before - grid.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE) == 137.5,
            "finite network loses exact buffer amount");
        transaction.rollbackExternal();
        receipt.setDouble("aeAfterRefund", grid.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE));
        check(
            grid.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE) == before,
            "failed-placement transaction refunds actual finite network exactly");
        check(
            ItemStack.areItemStacksEqual(terminalBefore, terminal),
            "preview/payment/refund never reads or changes carried terminal charge");
        receipt.setTag("terminalAfter", terminal.writeToNBT(new NBTTagCompound()));
        transaction = context.begin();
        check(transaction.takeCachedEnergy(demand), "second actual finite payment succeeds");
        Block finiteBlock = block("appeng.block.networking.BlockEnergyCell");
        NBTTagCompound savedFinite = new NBTTagCompound();
        cell.writeToNBT(savedFinite);
        savedFinite.setDouble("internalCurrentPower", 137.5);
        HologramEnergyPayload.Prepared finitePlan = HologramEnergyPayload.prepare(finiteBlock, 0, savedFinite, null);
        int placedX = X + 11;
        check(world.isAirBlock(placedX, Y, Z), "paid finite energy cell target is fresh air");
        placeNativeFixture(finiteBlock, placedX, 0, TileEnergyCell.class, "paidFiniteTarget");
        TileEnergyCell restored = (TileEnergyCell) HologramNativePlacement
            .install(world, placedX, Y, Z, finiteBlock, 0, finitePlan.tilePaid, player);
        check(
            restored.getAECurrentPower() == 137.5,
            "actual paid finite AE tile keeps the paid buffer through native installation");
        saveTile("finitePlacedPaid", restored);
        transaction.commit();
        receipt.setDouble("aeAfterCommit", grid.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE));
        check(
            before - grid.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE) == 137.5,
            "commit retains actual AE deduction");
        HologramEnergy.Demand tooMuch = new HologramEnergy.Demand();
        tooMuch.ae = BigDecimal.valueOf(1000000);
        check(
            !context.cachedEnergyQuote(tooMuch).affordable,
            "connected finite network shortage does not fall back to EU");
        transaction = context.begin();
        check(!transaction.takeCachedEnergy(tooMuch), "short finite network cannot authorize a copied full buffer");
        transaction.rollbackExternal();
        check(
            restored.extractAEPower(20, Actionable.MODULATE, PowerMultiplier.ONE) == 20
                && HologramNativePlacement.matchesPlaced(finiteBlock, 0, restored, finitePlan.tilePaid),
            "normal native buffer consumption does not make the paid device incomplete or payable again");
        saveTile("finitePlacedAfterConsumption", restored);
        wirelessEu(context);
        nativeExceptions();
    }

    private void wirelessEu(HologramMaterials.Context context) throws Exception {
        UUID owner = player.getUniqueID();
        WirelessNetworkManager.strongCheckOrAddUser(owner);
        BigInteger previous = WirelessNetworkManager.getUserEU(owner);
        try {
            WirelessNetworkManager.setUserEU(owner, BigInteger.valueOf(10000));
            receipt.setString(
                "euBefore",
                WirelessNetworkManager.getUserEU(owner)
                    .toString());
            NBTTagCompound captured = new NBTTagCompound();
            captured.setLong("mStoredEnergy", 777);
            String encoded = HologramBlueprintNbt.encodeCaptured(captured);
            NBTTagCompound selected = HologramBlueprintNbt.forPlacement(
                encoded,
                owner,
                player.getCommandSenderName(),
                X,
                Y,
                Z,
                true,
                false,
                java.util.Collections.singletonList("mStoredEnergy"));
            HologramEnergyPayload.Prepared selectedCache = HologramEnergyPayload.prepare(selected, null);
            HologramEnergy.Demand demand = selectedCache.demand;
            check(
                demand.eu.equals(BigInteger.valueOf(777)) && selectedCache.tileEmpty.getLong("mStoredEnergy") == 0,
                "actual context receives the exact retained-root cache demand and empty fallback projection");
            receipt.setTag("selectedEuCache", selectedCache.tilePaid.copy());
            HologramMaterials.Transaction transaction = context.begin();
            check(
                transaction.takeCachedEnergy(demand) && WirelessNetworkManager.getUserEU(owner)
                    .intValue() == 9223,
                "actual GT wireless UUID account debits exact EU");
            receipt.setString(
                "euAfterDebit",
                WirelessNetworkManager.getUserEU(owner)
                    .toString());
            transaction.rollbackExternal();
            check(
                WirelessNetworkManager.getUserEU(owner)
                    .intValue() == 10000,
                "actual wireless UUID refund restores exact EU");
            receipt.setString(
                "euAfterRefund",
                WirelessNetworkManager.getUserEU(owner)
                    .toString());
            NBTTagCompound omitted = HologramBlueprintNbt.forPlacement(
                encoded,
                owner,
                player.getCommandSenderName(),
                X,
                Y,
                Z,
                true,
                false,
                java.util.Collections.emptyList());
            HologramEnergyPayload.Prepared omittedCache = HologramEnergyPayload.prepare(omitted, null);
            transaction = context.begin();
            check(
                omittedCache.demand.empty() && transaction.takeCachedEnergy(omittedCache.demand),
                "deselected energy root creates no actual context payment requirement");
            transaction.commit();
            check(
                WirelessNetworkManager.getUserEU(owner)
                    .intValue() == 10000,
                "deselecting the energy root leaves the actual UUID balance untouched");
            receipt.setTag("deselectedEuCache", omittedCache.tilePaid.copy());
            player.inventory.mainInventory[1] = null;
            demand = new HologramEnergy.Demand();
            demand.eu = BigInteger.ZERO;
            demand.ae = BigDecimal.valueOf(10);
            HologramEnergy.Quote fallback = context.cachedEnergyQuote(demand);
            receipt.setString("fallbackSource", fallback.source);
            receipt.setString("fallbackConvertedEU", fallback.eu.toString());
            receipt.setDouble("fallbackOriginalAE", demand.ae.doubleValue());
            check(
                fallback.source.contains("unavailable") && fallback.ae == 0 && fallback.eu.signum() > 0,
                "removed terminal enables explicit real EU fallback without item energy");
            transaction = context.begin();
            check(transaction.takeCachedEnergy(demand), "real EU fallback payment succeeds");
            receipt.setString(
                "fallbackEuAfterDebit",
                WirelessNetworkManager.getUserEU(owner)
                    .toString());
            transaction.rollbackExternal();
            check(
                WirelessNetworkManager.getUserEU(owner)
                    .intValue() == 10000,
                "fallback refund returns actual converted EU");
            receipt.setString(
                "fallbackEuAfterRefund",
                WirelessNetworkManager.getUserEU(owner)
                    .toString());
            player.inventory.mainInventory[1] = terminal;
        } finally {
            WirelessNetworkManager.setUserEU(owner, previous);
        }
    }

    private void nativeExceptions() throws Exception {
        String[] names = { "appeng.block.networking.BlockCreativeEnergyCell",
            "appeng.block.networking.BlockCreativeEnergyController" };
        for (int i = 0; i < names.length; i++) {
            Block target = block(names[i]);
            int x = X + 4 + i;
            check(world.isAirBlock(x, Y, Z), "creative AE target position is fresh " + i);
            NBTTagCompound source = new NBTTagCompound();
            source.setDouble("internalCurrentPower", 123456789);
            HologramEnergyPayload.Prepared prepared = HologramEnergyPayload.prepare(target, 0, source, null);
            check(
                prepared.demand.empty() && !prepared.intrinsicSource.isEmpty(),
                "registered neutron-energy native target has no copied-buffer demand " + i);
            Class<? extends TileEntity> expectedTile = i == 0 ? TileCreativeEnergyCell.class
                : TileCreativeEnergyController.class;
            check(
                IAEPowerStorage.class.isAssignableFrom(expectedTile),
                "exact creative tile implements the native infinite-storage API " + i);
            placeNativeFixture(target, x, 0, expectedTile, "creativeAeTarget" + i);
            TileEntity installed = HologramNativePlacement
                .install(world, x, Y, Z, target, 0, prepared.tileEmpty, player);
            check(
                installed.getClass() == expectedTile,
                "installed creative provider has the exact registered native tile class " + i);
            IAEPowerStorage infinite = (IAEPowerStorage) installed;
            NBTTagCompound nativeTrait = new NBTTagCompound();
            nativeTrait.setString("expectedTileClass", expectedTile.getName());
            nativeTrait.setString(
                "actualTileClass",
                installed.getClass()
                    .getName());
            nativeTrait.setBoolean("isInfinite", infinite.isInfinite());
            nativeTrait.setDouble("currentAE", infinite.getAECurrentPower());
            nativeTrait.setDouble("capacityAE", infinite.getAEMaxPower());
            receipt.setTag("nativeCreativeTrait" + i, nativeTrait);
            saveTile("nativeCreativeAe" + i, installed);
            check(
                infinite.isInfinite() && infinite.getAECurrentPower() > 1000000000
                    && infinite.extractAEPower(1000, Actionable.MODULATE, PowerMultiplier.ONE) == 1000,
                "actual installed native neutron-energy provider remains infinite without copied cache " + i);
        }
        Block bankBlock = block("crazypants.enderio.machine.capbank.BlockCapBank");
        Class<?> bankType = Class.forName("crazypants.enderio.machine.capbank.CapBankType");
        Object creative = bankType.getField("CREATIVE")
            .get(null);
        int meta = ((Number) bankType.getMethod("getMetaFromType", bankType)
            .invoke(null, creative)).intValue();
        int capacity = ((Number) bankType.getMethod("getMaxEnergyStored")
            .invoke(creative)).intValue();
        NBTTagCompound source = new NBTTagCompound();
        source.setInteger("storedEnergyRF", 3);
        HologramEnergyPayload.Prepared prepared = HologramEnergyPayload.prepare(bankBlock, meta, source, null);
        check(
            prepared.demand.empty() && prepared.tileEmpty.getInteger("storedEnergyRF") == capacity / 2,
            "actual chaotic capacitor variant uses native default fill rather than copied source balance");
        int x = X + 7;
        check(world.isAirBlock(x, Y, Z), "chaotic bank target position is fresh");
        TileEntity bank = placeNativeFixture(bankBlock, x, meta, TileEntity.class, "chaoticBankTarget");
        NBTTagCompound nativeTag = new NBTTagCompound();
        bank.writeToNBT(nativeTag);
        nativeTag.setString("type", prepared.tileEmpty.getString("type"));
        nativeTag.setInteger("storedEnergyRF", prepared.tileEmpty.getInteger("storedEnergyRF"));
        bank.readFromNBT(nativeTag);
        check(
            (Boolean) bank.getClass()
                .getMethod("isCreative")
                .invoke(bank)
                && ((Number) bank.getClass()
                    .getMethod("getEnergyStored")
                    .invoke(bank)).intValue() == capacity / 2,
            "actual placed chaotic bank preserves its native creative flag and default buffer");
        saveTile("nativeChaoticCapBank", bank);
        receipt.setInteger("nativeChaoticCapacity", capacity);
        receipt.setInteger("nativeChaoticDefaultFill", capacity / 2);
        Block cableBlock = block("appeng.block.networking.BlockCableBus");
        ItemStack anchor = AEApi.instance()
            .definitions()
            .parts()
            .partCreativeEnergy()
            .maybeStack(1)
            .get();
        NBTTagCompound anchorDef = new NBTTagCompound();
        anchor.writeToNBT(anchorDef);
        NBTTagCompound cableData = new NBTTagCompound();
        cableData.setTag("def:" + ForgeDirection.NORTH.ordinal(), anchorDef);
        int cableX = X + 9;
        check(world.isAirBlock(cableX, Y, Z), "neutron energy anchor position is fresh");
        placeNativeFixture(cableBlock, cableX, 0, TileCableBus.class, "neutronAnchorTarget");
        HologramEnergyPayload.Prepared anchorPlan = HologramEnergyPayload.prepare(cableBlock, 0, cableData, null);
        TileCableBus anchorHost = (TileCableBus) HologramNativePlacement
            .install(world, cableX, Y, Z, cableBlock, 0, anchorPlan.tileEmpty, player);
        IPart nativeAnchor = anchorHost.getCableBus()
            .getPart(ForgeDirection.NORTH);
        saveTile("nativeNeutronAnchor", anchorHost);
        check(
            nativeAnchor instanceof IAEPowerStorage && ((IAEPowerStorage) nativeAnchor).isInfinite()
                && ((IAEPowerStorage) nativeAnchor).extractAEPower(1000, Actionable.MODULATE, PowerMultiplier.ONE)
                    == 1000,
            "actual physical neutron energy anchor part remains a native infinite provider");
        check(
            !HologramAeBlueprint.nativeShellMaterials(cableBlock, 0, anchorPlan.tileEmpty)
                .isEmpty(),
            "native neutron anchor has a real physical component cost");
    }

    private void verifyInfiniteGrid() throws Exception {
        AENetworkTile creativeCell = (AENetworkTile) world.getTileEntity(X + 4, Y, Z);
        IEnergyGrid infinite = creativeCell.getProxy()
            .getNode()
            .getGrid()
            .getCache(IEnergyGrid.class);
        check(
            infinite.getHasInfiniteStore()
                && infinite.extractAEPower(1000000, Actionable.MODULATE, PowerMultiplier.ONE) == 1000000,
            "actual installed neutron provider network advertises and supplies native infinite energy");
        receipt.setBoolean("nativeInfiniteGrid", infinite.getHasInfiniteStore());
    }

    private void saveTile(String key, TileEntity tile) {
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        receipt.setTag(key, tag);
    }

    private void writeReceipts() throws Exception {
        String configured = System.getProperty("gtmb.hologram.productionPreviewOutput", "");
        String root = System.getProperty("gtmb.hologram.packagedProbeDir", "");
        if (configured.isEmpty() && root.isEmpty()) return;
        Path output = configured.isEmpty() ? new File(root, "receipts").toPath() : new File(configured).toPath();
        Files.createDirectories(output);
        receipt.setInteger("assertions", checks);
        receipt.setInteger("probeTicks", ticks);
        if (world != null) receipt.setLong("worldTick", world.getTotalWorldTime());
        try (FileOutputStream stream = new FileOutputStream(
            output.resolve("energy-network-receipt.nbt")
                .toFile())) {
            CompressedStreamTools.writeCompressed(receipt, stream);
        }
        Files.write(
            output.resolve("energy-network-receipt.txt"),
            receipt.toString()
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Block block(String className) {
        for (Object registered : Block.blockRegistry) {
            if (registered instanceof Block && registered.getClass()
                .getName()
                .equals(className)) return (Block) registered;
        }
        throw new IllegalStateException("Missing actual registered fixture block " + className);
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(PREFIX + description);
        checks++;
        System.out.println(PREFIX + "PASS " + description);
    }
}
