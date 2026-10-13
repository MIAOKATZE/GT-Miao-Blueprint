package com.miaokatze.gtmb.hologram;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.core.worlddata.WorldData;
import appeng.me.helpers.IGridProxyable;
import gregtech.common.misc.WirelessNetworkManager;

/** Initial fixtures are server-owned. Every binding, hint edit, save and build is sent by the real client GUI. */
public final class HologramNetworkInteractionSmoke {

    private static final String PREFIX = "[GTMB-NETWORK-INTERACTION-SERVER] ";
    private static final int Y = 88, Z = 576, DESTINATION = 1296;
    private static final int[] ANCHORS = { 1168, 1184, 1200, 1216 }, SOURCES = { 1248, 1252, 1256 };
    private static volatile Throwable failure;
    private static volatile boolean done, ready;
    private static volatile String sessionId, presetId;
    private static int stage, ticks;
    private static EntityPlayerMP actor;
    private static Block node, iface;
    private static Class<?> registryType;
    private static Object registry;
    private static NBTTagCompound[] sourceWitness;
    private static Path output;
    private static NBTTagCompound receipt = new NBTTagCompound();
    private static ForgeChunkManager.Ticket chunkTicket;
    private static final Set<ChunkCoordIntPair> fixtureChunks = new LinkedHashSet<>();
    private static int retainedTicks;

    private HologramNetworkInteractionSmoke() {}

    public static boolean enabled() {
        return Boolean.getBoolean("gtmb.hologram.networkBindingSmoke")
            || Boolean.getBoolean("gtmb.hologram.networkInteractionSmoke");
    }

    public static boolean done() {
        return !enabled() || done;
    }

    public static boolean ready() {
        return ready;
    }

    public static Throwable failure() {
        return failure;
    }

    public static String status() {
        return !enabled() ? "DISABLED" : failure != null ? "FAIL" : done ? "PASS" : "PENDING";
    }

    public static String session() {
        return sessionId;
    }

    public static String preset() {
        return presetId;
    }

    public static NBTTagCompound receipt() {
        return (NBTTagCompound) receipt.copy();
    }

    public static void serverTick(EntityPlayerMP player) {
        if (!enabled() || failure != null || done && chunkTicket == null) return;
        try {
            if (done) {
                retainNativeChunks((WorldServer) actor.worldObj);
                if (clientPassed()) {
                    releaseNativeChunks(true);
                    write("network-server-final.nbt", receipt);
                }
                return;
            }
            if (stage == 0) {
                actor = player;
                WorldServer world = (WorldServer) actor.worldObj;
                require(
                    world.getWorldInfo()
                        .getWorldName()
                        .startsWith("hologram-production-")
                        || world.getWorldInfo()
                            .getWorldName()
                            .startsWith("hologram-smoke-"),
                    "isolated_world_guard");
                String configured = System.getProperty("gtmb.hologram.productionPreviewOutput", "");
                output = Paths
                    .get(
                        configured.isEmpty() ? System.getProperty("gtmb.hologram.packagedProbeDir", ".") + "/receipts"
                            : configured)
                    .toAbsolutePath()
                    .normalize();
                Files.createDirectories(output);
                acquireNativeChunks(world);
                require(
                    world.isAirBlock(DESTINATION, Y, Z - 3) && world.setBlock(DESTINATION, Y, Z - 3, Blocks.stone, 0, 3)
                        && world.getBlock(DESTINATION, Y, Z - 3) == Blocks.stone
                        && world.getBlockMetadata(DESTINATION, Y, Z - 3) == 0,
                    "fresh_native_player_stand");
                actor.setPositionAndUpdate(DESTINATION + .5, Y + 1, Z - 2.5);
                createSources(world);
                stage = 1;
                return;
            }
            retainNativeChunks((WorldServer) actor.worldObj);
            if (stage == 1) {
                if (++ticks < 4) return;
                prepareRealSession((WorldServer) actor.worldObj);
                ready = true;
                stage = 2;
                receipt.setString("session", sessionId);
                receipt.setString("preset", presetId);
                receipt.setBoolean("fixturesReady", true);
                write("network-server-fixtures.nbt", receipt);
                return;
            }
            Object session = liveSession();
            if (session == null || !sessionId.equals(field(session, "id"))) throw new IllegalStateException(
                "Network fixture session was replaced before completing the real client flow");
            if (stage == 2) {
                HologramPresets.Preset saved = HologramPresets.get(presetId);
                if (saved == null || !hints(saved)) return; // Only the real client confirm-save can publish these
                                                            // values.
                require(
                    !saved.retainNbt && !saved.retainCovers,
                    "client_save_keeps_both_optional_payload_modes_disabled");
                receipt.setBoolean("actualClientHintSave", true);
                ticks = 0;
                stage = 3;
            }
            if (stage == 3) {
                int job = (Integer) field(session, "job");
                if (job == 1 || job == 2 || !Files.exists(output.resolve("network-client-built.txt"))) return;
                if (++ticks < 3) return; // Let the newly installed native proxies run their ordinary first world ticks.
                HologramPresets.Preset installedPreset = (HologramPresets.Preset) field(session, "virtualPreset");
                require(
                    !installedPreset.retainNbt && !installedPreset.retainCovers,
                    "node_and_join_are_installed_with_both_optional_payload_modes_disabled");
                receipt.setBoolean("retainNbtDisabled", true);
                receipt.setBoolean("retainCoversDisabled", true);
                WorldServer world = (WorldServer) actor.worldObj;
                for (int i = 0; i < 3; i++) {
                    TileEntity tile = requireNativeTile(
                        world,
                        DESTINATION + i * 4,
                        i == 1 ? iface : node,
                        i == 1 ? "appeng.tile.misc.TileInterface"
                            : "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode");
                    require(
                        tile != null,
                        "client_build_installed_native_tile_" + i + " status=" + field(session, "status"));
                    NBTTagCompound payload = new NBTTagCompound();
                    tile.writeToNBT(payload);
                    if (i != 1) {
                        require(
                            (Integer) tile.getClass()
                                .getMethod("getAnchorX")
                                .invoke(tile) == ANCHORS[i == 0 ? 2 : 3],
                            "node_uses_selected_real_inventory_coordinates_" + i);
                        require(
                            payload.getCompoundTag("proxy")
                                .hasKey("p", 3)
                                && payload.getCompoundTag("proxy")
                                    .getInteger("p")
                                    == WorldData.instance()
                                        .playerData()
                                        .getPlayerID(actor.getGameProfile()),
                            "node_owner_is_real_connected_placer_" + i);
                    } else {
                        String identity = (String) tile.getClass()
                            .getMethod("gtswn$getIncorporationIdentity")
                            .invoke(tile);
                        Object entry = actualEntry(identity);
                        require(
                            entry != null && actor.getUniqueID()
                                .toString()
                                .equals(field(entry, "owner")),
                            "join_owner_is_real_connected_placer");
                        require(
                            entry.getClass()
                                .getField("ax")
                                .getInt(entry) == ANCHORS[2],
                            "join_uses_selected_inventory_coordinates");
                        HologramNetworkBindings.rollbackJoin(tile, actor);
                        require(
                            !(Boolean) registryType
                                .getMethod("isIncorporated", World.class, int.class, int.class, int.class)
                                .invoke(null, world, DESTINATION + 4, Y, Z),
                            "new_join_rollback_removes_only_new_identity");
                        require(
                            (Boolean) registryType
                                .getMethod("isIncorporated", World.class, int.class, int.class, int.class)
                                .invoke(null, world, SOURCES[1], Y, Z),
                            "new_join_rollback_preserves_source_entry");
                    }
                    receipt.setTag("placed" + i, payload);
                    require(
                        sourceWitness[i].equals(
                            sourceWitness(
                                requireNativeTile(
                                    world,
                                    SOURCES[i],
                                    i == 1 ? iface : node,
                                    i == 1 ? "appeng.tile.misc.TileInterface"
                                        : "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode"))),
                        "actual_source_unchanged_" + i);
                }
                require(
                    actor.inventory.mainInventory[1] == null && actor.inventory.mainInventory[2] == null,
                    "real_survival_materials_were_consumed");
                require(
                    actor.inventory.mainInventory[20] != null && actor.inventory.mainInventory[21] != null,
                    "real_coordinate_carriers_remain_in_inventory");
                receipt.setBoolean("actualNettyClient", true);
                receipt.setBoolean("owner", true);
                receipt.setBoolean("physicalPayment", true);
                receipt.setBoolean("sourceUnchanged", true);
                receipt.setBoolean("joinRollback", true);
                receipt.setBoolean("serverCarrierAndPermissionRechecked", true);
                write("network-server-final.nbt", receipt);
                Files.write(
                    output.resolve("NETWORK_SERVER_PASS.txt"),
                    (PREFIX + "FINAL PASS actual Netty node+JOIN two groups hints owner physical payment rollback\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                done = true;
                System.out.println(
                    PREFIX + "FINAL PASS actual Netty node+JOIN two groups hints owner physical payment rollback");
            }
        } catch (Throwable problem) {
            failure = problem;
            releaseNativeChunks(false);
            problem.printStackTrace();
            try {
                receipt.setString("failure", problem.toString());
                receipt.setInteger("stage", stage);
                if (output != null) write("network-server-failure.nbt", receipt);
            } catch (Exception ignored) {}
        }
    }

    private static void acquireNativeChunks(WorldServer world) {
        // Opt-in isolated fixture only. Production binding reads never load a dimension or chunk.
        HologramEnergyNetworkSmoke.ensureFixtureChunkCallback();
        chunkTicket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        require(chunkTicket != null, "isolated_network_fixture_obtains_native_forge_ticket");
        chunkTicket.getModData()
            .setBoolean("gtmbNetworkProbe", true);
        for (int x : ANCHORS) fixtureChunks.add(new ChunkCoordIntPair(x >> 4, Z >> 4));
        for (int x : SOURCES) fixtureChunks.add(new ChunkCoordIntPair(x >> 4, Z >> 4));
        for (int i = 0; i < 3; i++) fixtureChunks.add(new ChunkCoordIntPair((DESTINATION + i * 4) >> 4, Z >> 4));
        fixtureChunks.add(new ChunkCoordIntPair(DESTINATION >> 4, (Z - 3) >> 4));
        for (ChunkCoordIntPair coordinate : fixtureChunks) {
            loadNativeChunk(world, coordinate);
            ForgeChunkManager.forceChunk(chunkTicket, coordinate);
        }
        receipt.setString("chunkTicketMarker", "gtmbNetworkProbe");
        receipt.setBoolean("nativeChunkTicket", true);
        receipt.setBoolean("nativeLoadChunkOnProvideRequest", world.theChunkProviderServer.loadChunkOnProvideRequest);
        retainNativeChunks(world);
    }

    private static Chunk loadNativeChunk(WorldServer world, ChunkCoordIntPair coordinate) {
        Chunk loaded = world.theChunkProviderServer.loadChunk(coordinate.chunkXPos, coordinate.chunkZPos);
        if (loaded == null || loaded instanceof EmptyChunk
            || !loaded.isChunkLoaded
            || loaded.xPosition != coordinate.chunkXPos
            || loaded.zPosition != coordinate.chunkZPos
            || !world.theChunkProviderServer.chunkExists(coordinate.chunkXPos, coordinate.chunkZPos))
            throw new IllegalStateException("Network fixture native chunk failed to load: " + coordinate);
        return loaded;
    }

    private static void retainNativeChunks(WorldServer world) {
        if (chunkTicket == null) throw new IllegalStateException("Network fixture native chunk ticket is missing");
        for (ChunkCoordIntPair coordinate : fixtureChunks) {
            Chunk loaded = loadNativeChunk(world, coordinate);
            boolean forced = world.getPersistentChunks()
                .containsEntry(coordinate, chunkTicket);
            if (!forced) throw new IllegalStateException("Network fixture native chunk ticket lost: " + coordinate);
            NBTTagCompound chunk = new NBTTagCompound();
            chunk.setInteger("x", coordinate.chunkXPos);
            chunk.setInteger("z", coordinate.chunkZPos);
            chunk.setBoolean("nativeLoaded", loaded.isChunkLoaded);
            chunk.setBoolean("persistentTicket", forced);
            receipt.setTag("chunk" + coordinate.chunkXPos + "_" + coordinate.chunkZPos, chunk);
        }
        receipt.setInteger("nativeChunkRetentionChecks", ++retainedTicks);
    }

    private static void releaseNativeChunks(boolean afterClientPass) {
        if (chunkTicket == null) return;
        ForgeChunkManager.releaseTicket(chunkTicket);
        chunkTicket = null;
        receipt.setBoolean("chunkTicketReleased", true);
        receipt.setBoolean("chunkTicketReleasedAfterBothEndpoints", afterClientPass && done);
    }

    private static boolean clientPassed() throws Exception {
        Path finalReceipt = output.resolve("network-client-final.nbt");
        if (!Files.exists(output.resolve("NETWORK_CLIENT_PASS.txt")) || !Files.exists(finalReceipt)) return false;
        try (InputStream stream = Files.newInputStream(finalReceipt)) {
            NBTTagCompound client = CompressedStreamTools.readCompressed(stream);
            return sessionId.equals(client.getString("session")) && client.getBoolean("passed")
                && client.getBoolean("actualNettyClient");
        }
    }

    private static TileEntity requireNativeTile(WorldServer world, int x, Block expectedBlock, String expectedType) {
        TileEntity tile = world.getTileEntity(x, Y, Z);
        if (world.getBlock(x, Y, Z) != expectedBlock || world.getBlockMetadata(x, Y, Z) != 0
            || tile == null
            || !tile.getClass()
                .getName()
                .equals(expectedType)
            || tile.getWorldObj() != world
            || tile.xCoord != x
            || tile.yCoord != Y
            || tile.zCoord != Z
            || tile.isInvalid())
            throw new IllegalStateException(
                "Network fixture native block/tile identity mismatch at " + x + "," + Y + "," + Z);
        receipt.setString(
            "nativeTileClassAt" + x,
            tile.getClass()
                .getName());
        receipt.setBoolean("nativeTileIdentityAt" + x, true);
        return tile;
    }

    private static void createSources(WorldServer world) throws Exception {
        for (Object candidate : Block.blockRegistry)
            if (candidate instanceof Block && HologramNetworkBindings.node((Block) candidate)) node = (Block) candidate;
        require(node != null, "actual_registered_gtswn_node");
        iface = AEApi.instance()
            .definitions()
            .blocks()
            .iface()
            .maybeBlock()
            .get();
        Block controller = AEApi.instance()
            .definitions()
            .blocks()
            .controller()
            .maybeBlock()
            .get();
        Class<?> controllers = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry");
        Object controllerRegistry = controllers.getMethod("get", World.class)
            .invoke(null, world);
        for (int x : ANCHORS) {
            require(
                world.isAirBlock(x, Y, Z) && world.setBlock(x, Y, Z, controller, 0, 3),
                "fresh_real_controller_" + x);
            TileEntity controllerTile = requireNativeTile(
                world,
                x,
                controller,
                "appeng.tile.networking.TileController");
            ((IGridProxyable) controllerTile).getProxy()
                .setOwner(actor);
            ((IGridProxyable) controllerTile).getProxy()
                .onReady();
            controllers.getMethod("quantize", int.class, int.class, int.class)
                .invoke(controllerRegistry, x, Y, Z);
        }
        registryType = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry");
        registry = registryType.getMethod("get", World.class)
            .invoke(null, world);
        EntityPlayerMP original = FakePlayerFactory.get(
            world,
            new GameProfile(UUID.fromString("24536443-1c74-4f51-a904-5b057e606acf"), "GTMBOriginalNetwork"));
        for (int i = 0; i < 3; i++) {
            require(
                world.isAirBlock(DESTINATION + i * 4, Y, Z) && world.getTileEntity(DESTINATION + i * 4, Y, Z) == null,
                "fresh_native_destination_" + i);
            Block block = i == 1 ? iface : node;
            require(
                world.isAirBlock(SOURCES[i], Y, Z) && world.setBlock(SOURCES[i], Y, Z, block, 0, 3),
                "fresh_source_" + i);
            TileEntity tile = requireNativeTile(
                world,
                SOURCES[i],
                block,
                i == 1 ? "appeng.tile.misc.TileInterface"
                    : "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode");
            if (i == 1) {
                ((IGridProxyable) tile).getProxy()
                    .setOwner(original);
                ((IGridProxyable) tile).getProxy()
                    .onReady();
                require(
                    (Boolean) registryType
                        .getMethod(
                            "add",
                            World.class,
                            int.class,
                            int.class,
                            int.class,
                            NBTTagCompound.class,
                            EntityPlayer.class)
                        .invoke(registry, world, SOURCES[i], Y, Z, terminal(world, ANCHORS[0]), original),
                    "original_join_registration");
            } else {
                tile.getClass()
                    .getMethod("setAnchor", int.class, int.class, int.class, int.class)
                    .invoke(tile, world.provider.dimensionId, ANCHORS[i == 0 ? 0 : 1], Y, Z);
                tile.getClass()
                    .getMethod("setPlacer", EntityPlayer.class)
                    .invoke(tile, original);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void prepareRealSession(WorldServer world) throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.id = UUID.randomUUID()
            .toString();
        source.name = "Production quantum network bindings";
        source.schema = 2;
        source.controllerType = "area";
        source.retainNbt = false;
        source.retainCovers = false;
        sourceWitness = new NBTTagCompound[3];
        List<HologramCapture.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Block block = i == 1 ? iface : node;
            NBTTagCompound original = HologramNativeCapture.capture(world.getTileEntity(SOURCES[i], Y, Z));
            sourceWitness[i] = sourceWitness(world.getTileEntity(SOURCES[i], Y, Z));
            if (i != 1) {
                NBTTagCompound before = (NBTTagCompound) original.copy();
                HologramAeBlueprint.Analysis cleaned = HologramAeBlueprint.analyze(block, 0, original);
                require(
                    cleaned.supported && !cleaned.blocking
                        && !cleaned.payload()
                            .hasKey("proxy")
                        && !cleaned.payload()
                            .hasKey("anchorDim")
                        && !cleaned.payload()
                            .hasKey("anchorX"),
                    "detached_registered_quantum_sanitizer_removes_identity_without_live_world_" + i);
                require(
                    original.equals(before)
                        && sourceWitness[i].equals(sourceWitness(world.getTileEntity(SOURCES[i], Y, Z))),
                    "detached_quantum_sanitizer_preserves_real_source_" + i);
                receipt.setBoolean("detachedQuantumSanitizer" + i, true);
            }
            HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(block), i * 4, 0, 0, -1, false);
            cell.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
            cell.prompt = i == 1 ? "Production merged interface A"
                : i == 0 ? "Production source node A" : "Production source node B";
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(original);
            source.cells.add(cell);
        }
        HologramPresets.Preset portable = HologramAePolicy.sanitize(source).preset;
        HologramPresets.save(portable);
        presetId = portable.id;
        // The integrated library allocates the stored ID on save. Preserve that same identity
        // when replaying the raw live-area checkpoint so the later real GUI update targets it.
        source.id = presetId;
        actor.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
        actor.capabilities.isCreativeMode = false;
        actor.capabilities.allowEdit = true;
        actor.setPositionAndUpdate(DESTINATION + .5, Y + 1, Z - 2.5);
        for (int i = 1; i < 36; i++) actor.inventory.mainInventory[i] = null;
        actor.inventory.mainInventory[1] = new ItemStack(node, 2);
        actor.inventory.mainInventory[2] = new ItemStack(iface, 1);
        actor.inventory.mainInventory[20] = carrier(world, ANCHORS[2]);
        actor.inventory.mainInventory[21] = carrier(world, ANCHORS[3]);
        WirelessNetworkManager.addEUToGlobalEnergyMap(actor.getUniqueID(), BigInteger.valueOf(1000000000L));
        actor.inventory.markDirty();
        actor.inventoryContainer.detectAndSendChanges();
        for (int i = 0; i < 3; i++) {
            HologramPresets.Cell saved = portable.cells.get(i);
            cells.add(
                (HologramCapture.Cell) fixture(
                    "cell",
                    new Class<?>[] { Block.class, int.class, NBTTagCompound.class, ItemStack.class, int.class,
                        int.class, int.class },
                    i == 1 ? iface : node,
                    0,
                    HologramBlueprintNbt.decode(saved.tileNbt),
                    saved.stack(),
                    DESTINATION + i * 4,
                    Y,
                    Z));
            cells.get(i).prompt = saved.prompt;
        }
        Object session = fixture(
            "session",
            new Class<?>[] { EntityPlayerMP.class, HologramCapture.Cell.class, ItemStack.class },
            actor,
            cells.get(0),
            portable.cells.get(0)
                .stack());
        HologramCapture capture = new HologramCapture(null, actor.getHeldItem());
        for (HologramCapture.Cell cell : cells) {
            capture.cells.add(cell);
            capture.positions.put(cell.x + "," + cell.y + "," + cell.z, cell);
        }
        set(session, "capture", capture);
        set(session, "virtualPreset", portable);
        set(session, "virtualPlacement", true);
        set(session, "virtualLocked", true);
        set(session, "open", false);
        set(session, "blueprintAwaitingConfirmation", false);
        set(session, "placementReviewIssued", false);
        set(session, "presetBrowseForPlacement", false);
        set(session, "blueprintPreview", false);
        ((Map<Integer, ItemStack>) field(session, "pins")).clear();
        ((Map<String, ItemStack>) field(session, "coordinatePins")).clear();
        ((Map<Integer, ItemStack>) field(session, "autoTargets")).clear();
        ((Map<String, ItemStack>) field(session, "frozenAutoTargets")).clear();
        ((List<ItemStack>) field(session, "planTargets")).clear();
        for (int i = 0; i < 3; i++) {
            ItemStack material = portable.cells.get(i)
                .stack();
            ((Map<Integer, ItemStack>) field(session, "pins")).put(i, material.copy());
            ((Map<String, ItemStack>) field(session, "coordinatePins"))
                .put(cells.get(i).x + "," + Y + "," + Z, material.copy());
            ((List<ItemStack>) field(session, "planTargets")).add(material.copy());
        }
        // Reproduce live-area capture before its first placement check. The real server gate must
        // normalize all source groups and request the inventory chooser before accepting a build.
        for (int i = 0; i < 3; i++) cells.get(i).tileNbt = source.cells.get(i).tileNbt;
        set(session, "virtualPreset", source);
        require(!(Boolean) invokeService("aeReady", session), "raw_live_area_cannot_place_before_network_grouping");
        require((Boolean) field(session, "networkBindingRequested"), "raw_live_area_requests_real_inventory_bindings");
        portable = (HologramPresets.Preset) field(session, "virtualPreset");
        require(
            presetId.equals(portable.id) && HologramPresets.get(portable.id) != null,
            "raw_live_area_normalization_preserves_real_saved_preset_identity");
        String firstGroup = HologramNetworkBindings.group(HologramBlueprintNbt.decode(cells.get(0).tileNbt));
        require(
            !firstGroup.isEmpty()
                && firstGroup.equals(HologramNetworkBindings.group(HologramBlueprintNbt.decode(cells.get(1).tileNbt)))
                && !firstGroup.equals(HologramNetworkBindings.group(HologramBlueprintNbt.decode(cells.get(2).tileNbt))),
            "raw_live_area_groups_node_and_join_by_original_network");
        for (HologramCapture.Cell cell : cells) {
            NBTTagCompound normalized = HologramBlueprintNbt.decode(cell.tileNbt);
            require(
                !normalized.hasKey("anchorDim") && !normalized.hasKey("anchorX")
                    && !normalized.hasKey("proxy")
                    && !normalized.hasKey("GTSWN_IncorporationIdentity")
                    && !normalized.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR),
                "raw_live_area_removes_external_identity");
        }
        receipt.setBoolean("rawLiveAreaNormalizedBeforePlacement", true);
        receipt.setBoolean("rawLiveAreaRequiresBinding", true);
        receipt.setString("normalizedPresetId", portable.id);
        Field sessions = HologramService.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        ((Map<UUID, Object>) sessions.get(null)).put(actor.getUniqueID(), session);
        // Fixture setup finishes through the ordinary production world-opening path. That path
        // issues the material review, its nonce and its opening snapshot together.
        long beforeReview = (Long) field(session, "placementReviewNonce");
        HologramService.openWorld(actor, DESTINATION, Y, Z, 2);
        require(
            (Long) field(session, "placementReviewNonce") > beforeReview
                && (Boolean) field(session, "placementReviewIssued")
                && (Boolean) field(session, "blueprintAwaitingConfirmation"),
            "ordinary_production_world_open_issues_real_material_review_context");
        receipt.setLong("placementReviewNonce", (Long) field(session, "placementReviewNonce"));
        receipt.setBoolean("productionReviewIssuance", true);
        sessionId = (String) field(session, "id");
        require(((Integer) field(session, "planMissing")) == 0, "real_inventory_complete_material_plan");
    }

    private static boolean hints(HologramPresets.Preset preset) throws Exception {
        for (int i = 0; i < 3; i++) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(preset.cells.get(i).tileNbt);
            if (!tag.getString(HologramNetworkBindings.HINT)
                .equals(i == 2 ? "Production quantum group B" : "Production quantum group A")) return false;
        }
        return true;
    }

    private static NBTTagCompound sourceWitness(TileEntity tile) throws Exception {
        NBTTagCompound tag = HologramNativeCapture.capture(tile);
        // Native link liveness and grid-storage merge IDs change with ordinary ticks; their owner and configuration do
        // not.
        if (tag.hasKey("proxy", 10)) tag.setInteger(
            "witnessSourcePlayer",
            tag.getCompoundTag("proxy")
                .getInteger("p"));
        tag.removeTag("proxy");
        tag.removeTag("linked");
        tag.removeTag("color");
        return tag;
    }

    private static Object actualEntry(String identity) throws Exception {
        for (Object entry : (Iterable<?>) registryType.getMethod("snapshot")
            .invoke(registry))
            if (identity.equals(
                entry.getClass()
                    .getMethod("identity")
                    .invoke(entry)))
                return entry;
        return null;
    }

    private static NBTTagCompound terminal(WorldServer world, int x) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("QT_Bound", true);
        tag.setInteger("QT_AnchorDim", world.provider.dimensionId);
        tag.setInteger("QT_AnchorX", x);
        tag.setInteger("QT_AnchorY", Y);
        tag.setInteger("QT_AnchorZ", Z);
        return tag;
    }

    private static ItemStack carrier(WorldServer world, int x) {
        ItemStack item = new ItemStack(node);
        item.setTagCompound(terminal(world, x));
        return item;
    }

    private static Object liveSession() throws Exception {
        Field field = HologramService.class.getDeclaredField("SESSIONS");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).get(actor.getUniqueID());
    }

    private static Object fixture(String name, Class<?>[] parameters, Object... args) throws Exception {
        Method method = HologramNativePlacementSmoke.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static Object invokeService(String name, Object session) throws Exception {
        Method method = HologramService.class.getDeclaredMethod(name, session.getClass());
        method.setAccessible(true);
        return method.invoke(null, session);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void write(String name, NBTTagCompound data) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(
            output.resolve(name)
                .toFile())) {
            CompressedStreamTools.writeCompressed(data, stream);
        }
    }

    private static void require(boolean value, String name) {
        if (!value) throw new IllegalStateException(PREFIX + "FAIL " + name);
        System.out.println(PREFIX + "PASS " + name);
    }
}
