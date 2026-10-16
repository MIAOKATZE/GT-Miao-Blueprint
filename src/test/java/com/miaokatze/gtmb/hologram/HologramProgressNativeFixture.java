package com.miaokatze.gtmb.hologram;

import static com.miaokatze.gtmb.hologram.HologramProgressNativeBootstrap.require;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.miaokatze.gtmb.testlab.TestLab;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Real connected player only. Fixture setup is explicit; all build work belongs to the production scheduler. */
public final class HologramProgressNativeFixture {

    public static final int X = 8192, Y = 96, Z = 8192, SIDE = 32, CELLS = SIDE * SIDE * SIDE;
    private final NBTTagList samples = new NBTTagList();
    private final NBTTagCompound result = new NBTTagCompound();
    private final MinecraftServer server = MinecraftServer.getServer();
    private final long started = System.nanoTime();
    private EntityPlayerMP player;
    private WorldServer world;
    private HologramPresets.Preset preset;
    private HologramSession session;
    private MessageDigest actualWorld, expectedWorld;
    private int ticks, phase, chunk, prepared, verified, stone;
    private long runningSequence = -1, closeSequence = -1;
    private NBTTagCompound windowStart, windowEnd;
    private boolean done;

    public static void install() {
        HologramProgressNativeFixture listener = new HologramProgressNativeFixture();
        try {
            require(listener.server != null && listener.server.isDedicatedServer(), "actual dedicated server");
            require(
                Paths.get("")
                    .toAbsolutePath()
                    .normalize()
                    .equals(
                        HologramProgressNativeBootstrap.root()
                            .resolve("server")),
                "fresh paired server cwd");
            require(
                TestLab.isEnabled() && TestLab.status()
                    .getBoolean("configEnabled"),
                "server configuration enables lab");
            require(
                DimensionManager.getWorld(
                    TestLab.status()
                        .getInteger("dimensionId"))
                    == null,
                "unused lab world initially absent");
            listener.result.setTag("initialLabStatus", TestLab.status());
            listener.result.setTag("metricsBeforeSetup", HologramRuntimeMetrics.snapshot());
            HologramRuntimeMetrics.setEnabled(true);
            FMLCommonHandler.instance()
                .bus()
                .register(listener);
            System.out.println("[GTMB-PROGRESS-SERVER] READY actualDedicated=true fixtureOnly=true");
        } catch (Throwable failure) {
            listener.fail(failure);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        try {
            require(
                ++ticks <= 4800 && System.nanoTime() - started < 240000000000L,
                "paired infrastructure watchdog; production budgets unchanged");
            require(
                DimensionManager.getWorld(
                    TestLab.status()
                        .getInteger("dimensionId"))
                    == null,
                "real connected player never loads or enters lab world");
            if (Files.exists(
                HologramProgressNativeBootstrap.root()
                    .resolve("receipts/client-complete.nbt"))) {
                require(phase == 4, "client completion cannot substitute for actual world verification");
                NBTTagCompound client = HologramProgressNativeBootstrap.read(
                    HologramProgressNativeBootstrap.root()
                        .resolve("receipts/client-complete.nbt"));
                require(
                    client.getBoolean("passed") && session.id.equals(client.getString("session"))
                        && player.getUniqueID()
                            .toString()
                            .equals(client.getString("playerUuid")),
                    "exact-source client shutdown barrier");
                finish();
                return;
            }
            List<?> online = server.getConfigurationManager().playerEntityList;
            if (player == null) {
                if (online.isEmpty()) return;
                require(
                    online.size() == 1 && online.get(0) instanceof EntityPlayerMP
                        && !(online.get(0) instanceof FakePlayer),
                    "one actual online human-shaped network player, never FakePlayer");
                player = (EntityPlayerMP) online.get(0);
                require(
                    "GTMBProgress".equals(player.getCommandSenderName()) && player.dimension == 0
                        && player.playerNetServerHandler != null
                        && player.playerNetServerHandler.netManager.isChannelOpen(),
                    "actual offline fixture login in overworld");
                world = (WorldServer) player.worldObj;
                result.setString(
                    "playerUuid",
                    player.getUniqueID()
                        .toString());
                result.setString("playerName", player.getCommandSenderName());
                result.setInteger("loginDimension", player.dimension);
                result.setInteger("actualOnlinePlayers", online.size());
                player.theItemInWorldManager.setGameType(WorldSettings.GameType.CREATIVE);
                player.capabilities.isFlying = true;
                player.sendPlayerAbilities();
                player.inventory.currentItem = 0;
                player.inventory.setInventorySlotContents(0, new ItemStack(ProjectorRegistry.projector));
                player.inventory.markDirty();
                player.inventoryContainer.detectAndSendChanges();
                preset = new HologramPresets.Preset();
                preset.schema = 2;
                preset.id = UUID.randomUUID()
                    .toString();
                preset.name = "Native paired 32768 ordinary stone";
                preset.controllerType = "area";
                preset.authorUuid = player.getUniqueID()
                    .toString();
                preset.authorName = player.getCommandSenderName();
                preset.sourceMod = "minecraft";
                preset.sourceClass = getClass().getName();
                preset.createdAt = System.currentTimeMillis();
                return;
            }
            require(
                online.size() == 1 && online.get(0) == player
                    && player.dimension == 0
                    && player.worldObj == world
                    && player.playerNetServerHandler.netManager.isChannelOpen(),
                "same unique actual Netty player remains in overworld");
            if (phase == 0) {
                // Exactly one explicit target chunk request per tick; ordinary player-view loading is independent.
                if (chunk < 4) {
                    world.getChunkFromChunkCoords((X >> 4) + chunk % 2, (Z >> 4) + chunk / 2);
                    chunk++;
                    if (chunk == 4)
                        player.playerNetServerHandler.setPlayerLocation(X + 16.5, Y + 35, Z + 16.5, 180, 70);
                    return;
                }
                int stop = Math.min(CELLS, prepared + 512);
                while (prepared < stop) {
                    int index = prepared++, dx = index % SIDE, dz = index / SIDE % SIDE, dy = index / (SIDE * SIDE);
                    require(
                        world.blockExists(X + dx, Y + dy, Z + dz) && world.isAirBlock(X + dx, Y + dy, Z + dz),
                        "fresh explicit pressure target is air");
                    HologramPresets.Cell cell = HologramPresets
                        .cell(new ItemStack(Blocks.stone), dx, dy, dz, -1, false);
                    cell.block = "minecraft:stone";
                    preset.cells.add(cell);
                }
                if (prepared == CELLS) {
                    HologramPresets.validate(preset);
                    session = HologramWorkspace.create(player, X, Y, Z, false, 1);
                    session.virtual = true;
                    session.virtualPreset = preset;
                    session.materialSettings.main = true;
                    session.materialSettings.containers = session.materialSettings.me = false;
                    session.materialContext = new HologramMaterials.Context(player, session.materialSettings);
                    HologramBlueprintProjection.populateVirtual(session);
                    require(
                        session.capture != null && session.capture.cells.size() == CELLS
                            && !session.capture.incomplete
                            && HologramSessionPolicy.valid(session),
                        "legal schema2 projection bound to actual physical projector/player");
                    for (HologramCapture.Cell cell : session.capture.cells) require(
                        !cell.anchor && "prefab".equals(cell.role),
                        "ordinary controller-free preset has no protected origin anchor");
                    HologramWorkspace.activate(player.getUniqueID(), session);
                    require(
                        HologramWorldPlacementWorkflow.beginVirtualPlacement(session, X, Y, Z, 1),
                        "production placement workflow admits owned blueprint at valid nearby anchor");
                    NBTTagCompound lock = new NBTTagCompound();
                    lock.setString("session", session.id);
                    lock.setLong("planRevision", session.planRevision);
                    lock.setInteger("x", X);
                    lock.setInteger("y", Y);
                    lock.setInteger("z", Z);
                    lock.setInteger("side", 1);
                    HologramWorldPlacementWorkflow.confirmWorldPosition(player, lock);
                    require(
                        session.virtualPlacement && session.virtualLocked && !session.placementReviewIssued,
                        "production world-position validation issues a lock without reviewed build authority");
                    require(
                        HologramProjectorWorkflow.openPlacementReview(session, player),
                        "production workflow issues current review nonce");
                    result.setString("session", session.id);
                    result.setLong("reviewRevision", session.planRevision);
                    result.setLong("reviewNonce", session.placementReviewNonce);
                    result.setTag("metricsAfterInitialFull", HologramRuntimeMetrics.snapshot());
                    phase = 1;
                }
                return;
            }
            if (phase == 1 || phase == 2) {
                require(
                    HologramWorkspace.find(player.getUniqueID()) == session && HologramSessionPolicy.valid(session),
                    "production scheduler retains exact live session");
                if (session.job == 1) {
                    if (runningSequence < 0) {
                        java.nio.file.Path issuedPath = HologramProgressNativeBootstrap.root()
                            .resolve("receipts/client-issued-confirm.nbt");
                        if (!Files.exists(issuedPath)) return;
                        NBTTagCompound issued = HologramProgressNativeBootstrap.read(issuedPath);
                        require(
                            session.id.equals(issued.getString("session"))
                                && session.planRevision == issued.getLong("planRevision")
                                && session.placementReviewNonce == issued.getLong("placementReviewNonce"),
                            "actual running job matches client's actual issued reviewed request");
                        runningSequence = issued.getLong("uiSequence");
                        require(
                            runningSequence > 0 && session.uiSequence >= runningSequence,
                            "server consumed actual submitted build sequence");
                        result.setLong("buildUiSequence", runningSequence);
                        result.setLong("buildRevision", session.planRevision);
                        result.setLong("buildNonce", session.placementReviewNonce);
                    }
                    if (!result.hasKey("metricsAtRunningObserved")) {
                        result.setTag("metricsAtRunningObserved", HologramRuntimeMetrics.snapshot());
                    }
                    // closeRoot is a real Netty command after confirm, and its consumed sequence marks the window.
                    if (session.uiSequence > runningSequence && !session.open) {
                        if (closeSequence < 0) closeSequence = session.uiSequence;
                        require(
                            session.uiSequence == closeSequence,
                            "no explicit UI command during measured running window");
                        NBTTagCompound sample = sample();
                        if (windowStart == null) windowStart = sample;
                        windowEnd = sample;
                        if (samples.tagCount() < 1024) samples.appendTag(sample);
                    }
                    phase = 2;
                } else if (session.job != 0) {
                    require(
                        session.job == 3 && session.cursor == CELLS
                            && session.completed == CELLS
                            && session.jobMissingCells == 0
                            && session.jobProtectedCells == 0
                            && session.jobUnsupportedCells == 0,
                        "actual production job completed all ordinary cells");
                    require(
                        windowStart != null && windowEnd.getLong("tick") > windowStart.getLong("tick"),
                        "real post-close running measurement window exists");
                    result.setTag("runningWindowStart", windowStart);
                    result.setTag("runningWindowEnd", windowEnd);
                    result.setLong("closeUiSequence", closeSequence);
                    result.setTag("metricsAtTerminalFull", HologramRuntimeMetrics.snapshot());
                    result.setInteger("job", session.job);
                    result.setInteger("cursor", session.cursor);
                    result.setInteger("completed", session.completed);
                    actualWorld = MessageDigest.getInstance("SHA-256");
                    expectedWorld = MessageDigest.getInstance("SHA-256");
                    phase = 3;
                }
            }
            if (phase == 3) {
                int stop = Math.min(CELLS, verified + 512);
                while (verified < stop) {
                    int index = verified++, x = X + index % SIDE, z = Z + index / SIDE % SIDE,
                        y = Y + index / (SIDE * SIDE);
                    require(world.blockExists(x, y, z), "independent terminal world target chunk remains loaded");
                    boolean match = world.getBlock(x, y, z) == Blocks.stone && world.getBlockMetadata(x, y, z) == 0
                        && world.getTileEntity(x, y, z) == null;
                    if (match) stone++;
                    String prefix = x + "," + y + "," + z + "=";
                    actualWorld.update(
                        (prefix
                            + String.valueOf(
                                net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(x, y, z)))
                            + ":"
                            + world.getBlockMetadata(x, y, z)
                            + ":"
                            + (world.getTileEntity(x, y, z) == null)
                            + "\n").getBytes(StandardCharsets.UTF_8));
                    expectedWorld.update((prefix + "minecraft:stone:0:true\n").getBytes(StandardCharsets.UTF_8));
                }
                if (verified == CELLS) {
                    require(stone == CELLS, "independent world readback counted exactly 32768 native stone cells");
                    result.setInteger("independentWorldCells", verified);
                    result.setInteger("independentStoneCells", stone);
                    result.setString("actualWorldSha256", hex(actualWorld.digest()));
                    result.setString("expectedWorldSha256", hex(expectedWorld.digest()));
                    require(
                        result.getString("actualWorldSha256")
                            .equals(result.getString("expectedWorldSha256")),
                        "independent native world digest matches ordinary schema2 fixture");
                    result.setTag("metricsAfterWorldReadback", HologramRuntimeMetrics.snapshot());
                    HologramProgressNativeBootstrap.write("server-world-complete", result);
                    phase = 4;
                }
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private NBTTagCompound sample() {
        NBTTagCompound n = new NBTTagCompound();
        n.setLong("tick", ticks);
        n.setString("session", session.id);
        n.setLong("planRevision", session.planRevision);
        n.setLong("placementReviewNonce", session.placementReviewNonce);
        n.setLong("uiSequence", session.uiSequence);
        n.setLong("batchSeq", session.batchSeq);
        n.setInteger("job", session.job);
        n.setInteger("cursor", session.cursor);
        n.setInteger("completed", session.completed);
        n.setInteger("actualOnlinePlayers", server.getConfigurationManager().playerEntityList.size());
        n.setBoolean("actualNettyChannelOpen", player.playerNetServerHandler.netManager.isChannelOpen());
        n.setInteger("actualPlayerDimension", player.dimension);
        n.setTag("metrics", HologramRuntimeMetrics.snapshot());
        return n;
    }

    private void finish() throws Exception {
        result.setBoolean("passed", true);
        result.setString("releaseVersion", Tags.RELEASE_VERSION);
        result.setBoolean("actualDedicatedServer", true);
        result.setBoolean("actualNettyPlayer", true);
        result.setBoolean("fakePlayer", false);
        result.setBoolean("creativePressureFixture", true);
        result.setBoolean("survivalPaymentClaim", false);
        result.setBoolean("directJobTicks", false);
        result.setBoolean("notPhysicalOSInput", true);
        result.setBoolean("sharedFileShutdownBarrierNotNetworkEvidence", true);
        result.setIntArray("origin", new int[] { X, Y, Z });
        result.setInteger("side", SIDE);
        result.setInteger("cells", CELLS);
        result.setInteger("explicitChunkRequests", chunk);
        result.setInteger("fixtureCellsPerTickBound", 512);
        result.setInteger("productionJobPerTickBound", HologramPlacementJobs.BUDGET);
        result.setLong("productionSliceNanos", HologramWorkBudget.SLICE_NANOS);
        result.setLong("productionTickNanos", HologramWorkBudget.TICK_NANOS);
        result.setTag("samples", samples);
        result.setTag("finalLabStatus", TestLab.status());
        result.setTag("finalMetrics", HologramRuntimeMetrics.snapshot());
        HologramProgressNativeBootstrap.write("server-final", result);
        done = true;
        System.out
            .println("[GTMB-PROGRESS-SERVER] FINAL PASS cells=" + stone + " actualNetty=true notPhysicalOSInput=true");
        server.initiateShutdown();
    }

    private void fail(Throwable failure) {
        done = true;
        failure.printStackTrace();
        System.err.println("[GTMB-PROGRESS-SERVER] FINAL FAIL " + failure);
        try {
            result.setBoolean("passed", false);
            result.setString("failure", failure.toString());
            result.setTag("samples", samples);
            HologramProgressNativeBootstrap.write("server-failed", result);
        } catch (Throwable preserving) {
            preserving.printStackTrace();
        }
        if (server != null) server.initiateShutdown();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format("%02x", b & 255));
        return value.toString();
    }
}
