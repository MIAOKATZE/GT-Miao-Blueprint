package com.miaokatze.gtmb.client.hologram;

import static miaotest.host.ClientAccess.call;
import static miaotest.host.ClientAccess.field;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Explicitly selected host-only scene. No production notification is invoked here: native GT setters on
 * the integrated server generate real GT packets, which the existing client processes unchanged.
 * Reflection accommodates the production SRG game and keeps this helper independent of mod compile jars.
 */
public final class GtmbWorldProjectionProbe {
    private static GtmbWorldProjectionProbe active;
    private static final String LITE = "com.miaokatze.gtmb.client.hologram.MiaoVisonLiteRenderer";
    private static final String RENDERER = "com.miaokatze.gtmb.client.hologram.HologramRenderer";
    private static final int X = 8, Y = 64, Z = 10, FAR_Z = 42;
    private final Object mc, clientWorld, clientPlayer, server;
    private final String run;
    private final Path out, expectedSave;
    private final Thread clientThread;
    private final JsonObject result = new JsonObject();
    private final JsonArray checks = new JsonArray(), transitions = new JsonArray(), packets = new JsonArray();
    private final ConcurrentLinkedQueue<JsonObject> packetRows = new ConcurrentLinkedQueue<JsonObject>();
    private final ConcurrentLinkedQueue<JsonObject> serverRows = new ConcurrentLinkedQueue<JsonObject>();
    private final List<SavedBlock> originals = new ArrayList<SavedBlock>();
    private volatile Throwable error;
    private volatile boolean restoreRequested, restored, ready, terminal;
    private volatile int requestedMutation, appliedMutation;
    private volatile Object serverWorld, serverTile, machineStack;
    private volatile int machineId;
    private Object state, cells, stateIdentity, regions, targetCell, targetGroup, farGroup;
    private Object clientTile, targetOriginalBatch, farOriginalBatch, farOriginalMesh;
    private int baselineIndex, ticks, baselineStable, stageStable, stageTicks, finishTicks;
    private int commonEvent, colorEvent, renders, stage, capturedStage;
    private long started = System.nanoTime();
    private Channel observedChannel;
    private String observerName;
    private boolean registered, cleaned;
    private Object serverPlayer;
    private double actorX, actorY, actorZ;
    private float actorYaw, actorPitch;
    private JsonObject currentTransition;

    public static void begin(Object minecraft, String runId, Path output) throws Exception {
        if (active != null) throw new IllegalStateException("World projection probe already exists");
        GtmbWorldProjectionProbe probe = new GtmbWorldProjectionProbe(minecraft, runId, output);
        active = probe;
        try { probe.install(); }
        catch (Throwable failure) { probe.fail(failure); throw new RuntimeException("Probe install", failure); }
    }

    public static void tick() { if (active != null) active.clientTick(); }
    /** Called by the probe's natural RenderWorldLastEvent, never by RenderTick without world matrices. */
    public static void render(float partialTicks) { if (active != null) active.renderWorld(partialTicks); }
    public static boolean done() { return active != null && active.terminal; }
    public static Throwable failure() { return active == null ? null : active.error; }
    public static JsonObject report() { return active == null ? new JsonObject() : active.snapshot(); }
    public static void cleanup() throws Exception { if (active != null) active.close(); }

    private GtmbWorldProjectionProbe(Object minecraft, String runId, Path output) throws Exception {
        mc = minecraft;
        run = UUID.fromString(runId).toString();
        out = output.toRealPath();
        clientWorld = field(mc, "theWorld|field_71441_e");
        clientPlayer = field(mc, "thePlayer|field_71439_g");
        server = call(mc, "getIntegratedServer|func_71401_C");
        clientThread = Thread.currentThread();
        expectedSave = Paths.get(System.getProperty("miaotest.host.worldPath")).toRealPath();
        require(clientWorld != null && clientPlayer != null && server != null, "A real integrated player/world is required");
        JsonObject marker = new JsonParser().parse(new String(Files.readAllBytes(expectedSave.resolve(".miaotest-host-owner.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        require(run.equals(marker.get("runId").getAsString()) && expectedSave.getFileName().toString().equals("MiaoHost-" + run), "Exact fresh host save marker");
        result.addProperty("schema", "gtmb-world-projection-events-v1");
        result.addProperty("runId", run);
        result.addProperty("profile", "gtmb/world-projection-events");
        result.addProperty("status", "RUNNING");
        result.addProperty("completed", false);
        result.addProperty("passed", false);
        result.addProperty("osInput", false);
        result.addProperty("nativeServerSetters", true);
        result.addProperty("directNotificationCalls", false);
        result.addProperty("fixtureSnapshot", true);
        result.addProperty("sourceReferenceBoundary", "GT5-Unofficial 5.09.54.264 is adjacent-version reference only; runtime class/code source and decoded native packets are recorded separately");
        result.addProperty("businessConstruction", "NOT_RUN");
        result.addProperty("permissionChecks", "NOT_RUN; fresh SURVIVAL integrated owner, no commands or addOp");
        result.addProperty("worldPath", expectedSave.toString());
        result.add("checks", checks); result.add("transitions", transitions); result.add("nativePackets", packets);
    }

    private static Class<?> type(String name) throws Exception { return Class.forName(name); }
    private static int number(Object value) { return ((Number) value).intValue(); }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    private void check(String name, boolean condition, String detail) {
        JsonObject row = new JsonObject(); row.addProperty("name", name); row.addProperty("pass", condition); row.addProperty("detail", detail);
        checks.add(row); require(condition, name + ": " + detail);
    }
    private void install() throws Exception {
        require(field(type("com.miaokatze.gtmb.client.hologram.HologramClient"), "state") == null, "Fresh save must not have another active server projection");
        commonEvent = number(field(type("gregtech.api.metatileentity.GregTechTileClientEvents"), "CHANGE_COMMON_DATA"));
        colorEvent = number(field(type("gregtech.api.metatileentity.GregTechTileClientEvents"), "CHANGE_COLOR"));
        List<String> handlers = new ArrayList<String>();
        for (Method method : type("gregtech.api.metatileentity.BaseMetaTileEntity").getDeclaredMethods())
            if (method.getName().contains("gtmb$machineChanged")) handlers.add(method.toString());
        check("runtime.gtmb-native-handler-merged", handlers.size() == 1, handlers.toString());
        result.addProperty("baseMetaTileEntityCodeSource", String.valueOf(type("gregtech.api.metatileentity.BaseMetaTileEntity").getProtectionDomain().getCodeSource()));
        result.addProperty("gtmbCodeSource", String.valueOf(type(LITE).getProtectionDomain().getCodeSource()));
        Object network = field(type("gregtech.api.enums.GTValues"), "NW");
        Map<?, ?> channels = (Map<?, ?>) field(network, "mChannel");
        observedChannel = (Channel) channels.get(Side.CLIENT);
        require(observedChannel != null, "GT's existing CLIENT embedded channel");
        ChannelHandlerContext codec = observedChannel.pipeline().context((ChannelHandler) network);
        require(codec != null, "GT's existing native decode handler");
        observerName = "gtmb-owned-world-probe-" + run;
        observedChannel.pipeline().addAfter(codec.name(), observerName, new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                List<JsonObject> observed = null;
                try {
                    if (message.getClass().getName().equals("gregtech.api.net.GTPacketBlockEvent")) observed = observePacket(message);
                } catch (Throwable failure) { fail(failure); }
                // Pass the exact original message down the original pipeline once. No injected packet/event.
                super.channelRead(context, message);
                if (observed != null) for (JsonObject row : observed) {
                    try { observeAfterNativeReceive(row); }
                    catch (Throwable failure) { fail(failure); row.addProperty("observerError", failure.toString()); }
                    packetRows.add(row);
                }
            }
        });
        result.addProperty("packetObserver", "Existing GT CLIENT FMLEmbeddedChannel, immediately after GTNetwork decode; original message forwarded once");
        result.addProperty("nativePipeline", observedChannel.pipeline().names().toString());
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        registered = true;
        write();
    }

    private List<JsonObject> observePacket(Object packet) throws Exception {
        int dimension = number(field(packet, "worldId"));
        if (dimension != 0) return null;
        int count = number(field(packet, "eventCount"));
        require(count >= 0 && count <= 65536, "Bounded native GT event packet");
        Object positions = field(packet, "packedCoordinates"), values = field(packet, "idsAndValues");
        List<JsonObject> selected = new ArrayList<JsonObject>();
        for (int i = 0; i < count; i++) {
            Object packed = call(positions, "getLong", i);
            int x = number(call(type("com.gtnewhorizon.gtnhlib.util.CoordinatePacker"), "unpackX", packed));
            int y = number(call(type("com.gtnewhorizon.gtnhlib.util.CoordinatePacker"), "unpackY", packed));
            int z = number(call(type("com.gtnewhorizon.gtnhlib.util.CoordinatePacker"), "unpackZ", packed));
            if (x != X || y != Y || z != Z) continue;
            int pair = number(call(values, "getShort", i));
            JsonObject row = new JsonObject();
            row.addProperty("packetClass", packet.getClass().getName()); row.addProperty("stage", requestedMutation);
            row.addProperty("eventId", pair >>> 8 & 255); row.addProperty("eventValue", pair & 255);
            row.addProperty("x", x); row.addProperty("y", y); row.addProperty("z", z);
            row.addProperty("thread", Thread.currentThread().getName()); row.addProperty("clientThread", Thread.currentThread() == clientThread);
            row.addProperty("nanosSinceBegin", System.nanoTime() - started);
            selected.add(row);
        }
        return selected;
    }

    private void observeAfterNativeReceive(JsonObject row) throws Exception {
        Object tile = call(clientWorld, "getTileEntity|func_147438_o", X, Y, Z);
        row.addProperty("nativeClientTilePresent", tile != null);
        if (tile != null) {
            row.addProperty("clientFacingAfterNativeReceive", number(call(call(tile, "getFrontFacing"), "ordinal")));
            row.addProperty("clientColorAfterNativeReceive", number(call(tile, "getColorization")));
        }
        if (targetGroup != null) {
            Set<?> dirty = (Set<?>) field(targetGroup, "dirtyMatches");
            row.addProperty("targetCellDirtyAfterNativeReceive", dirty.contains(Integer.valueOf(0)));
            row.addProperty("dirtyCountAfterNativeReceive", dirty.size());
            row.addProperty("allMatchesDirtyAfterNativeReceive", (Boolean) field(targetGroup, "allMatchesDirty"));
            row.addProperty("targetBuiltAfterNativeReceive", number(field(targetGroup, "built")));
            row.addProperty("targetBatchesAfterNativeReceive", ((List<?>) field(targetGroup, "batches")).size());
            row.addProperty("farBuiltAfterNativeReceive", number(field(farGroup, "built")));
            row.addProperty("farDirtyCountAfterNativeReceive", ((Set<?>) field(farGroup, "dirtyMatches")).size());
            row.addProperty("farAllMatchesDirtyAfterNativeReceive", (Boolean) field(farGroup, "allMatchesDirty"));
            row.addProperty("farBatchPreservedAfterNativeReceive", firstBatch(farGroup) == farOriginalBatch);
        }
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || terminal || cleaned) return;
        try {
            require(FMLCommonHandler.instance().getMinecraftServerInstance() == server, "Same owned integrated server");
            if (restoreRequested) { if (!restored) restoreFixture(); return; }
            if (!ready) { setupServer(); return; }
            if (requestedMutation > appliedMutation) {
                require(requestedMutation == appliedMutation + 1, "Ordered one native server mutation at a time");
                require(call(serverWorld, "getTileEntity|func_147438_o", X, Y, Z) == serverTile, "Server GT tile identity unchanged");
                int step = requestedMutation;
                if (step <= 2) call(serverTile, "setFrontFacing", field(type("net.minecraftforge.common.util.ForgeDirection"), step == 1 ? "SOUTH" : "NORTH"));
                else call(serverTile, "setColorization", Byte.valueOf((byte) (step == 3 ? 4 : -1)));
                JsonObject row = new JsonObject(); row.addProperty("stage", step); row.addProperty("thread", Thread.currentThread().getName());
                row.addProperty("nativeSetter", step <= 2 ? "BaseMetaTileEntity.setFrontFacing" : "BaseMetaTileEntity.setColorization");
                row.addProperty("serverFacing", number(call(call(serverTile, "getFrontFacing"), "ordinal")));
                row.addProperty("serverColor", number(call(serverTile, "getColorization"))); serverRows.add(row);
                appliedMutation = step;
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void setupServer() throws Exception {
        Object world = call(server, "worldServerForDimension|func_71218_a", 0);
        Path actual = ((java.io.File) call(call(world, "getSaveHandler|func_72860_G"), "getWorldDirectory|func_75765_b")).toPath().toRealPath();
        require(actual.equals(expectedSave), "Server exact SaveHandler path");
        require(Boolean.TRUE.equals(call(type("com.miaokatze.miaokatzecore.testlab.LabWorldPolicy"), "owns", world)), "Core exact owned-save policy");
        List<?> players = (List<?>) field(call(server, "getConfigurationManager|func_71203_ab"), "playerEntityList|field_72404_b");
        if (players.size() != 1) return;
        Object player = players.get(0);
        require(UUID.fromString(System.getProperty("miaotest.host.actorUuid")).equals(call(player, "getUniqueID|func_110124_au")), "Exact sole host actor UUID");
        require(field(player, "worldObj|field_70170_p") == world, "Actor remains in exact owned Overworld");
        require(!Boolean.TRUE.equals(field(field(player, "capabilities|field_71075_bZ"), "isCreativeMode|field_75098_d")), "Actual server actor is SURVIVAL");
        if (number(field(player, "ticksExisted|field_70173_aa")) < 100) return;
        serverWorld = world;
        serverPlayer = player;
        actorX = ((Number) field(player, "posX|field_70165_t")).doubleValue();
        actorY = ((Number) field(player, "posY|field_70163_u")).doubleValue();
        actorZ = ((Number) field(player, "posZ|field_70161_v")).doubleValue();
        actorYaw = ((Number) field(player, "rotationYaw|field_70177_z")).floatValue();
        actorPitch = ((Number) field(player, "rotationPitch|field_70125_A")).floatValue();
        Object worldInfo = call(world, "getWorldInfo|func_72912_H");
        Object terrain = call(worldInfo, "getTerrainType|func_76067_t");
        require(terrain == field(type("net.minecraft.world.WorldType"), "FLAT|field_77138_c"), "Native fresh flat world");
        Object stone = field(type("net.minecraft.init.Blocks"), "stone|field_150348_b");
        for (int x = 6; x <= 10; x++) for (int z = 2; z <= 13; z++) saveAndSet(world, x, Y - 1, z, stone, 0);
        save(world, X, Y, Z);
        require(call(world, "getBlock|func_147439_a", X, Y, Z) == field(type("net.minecraft.init.Blocks"), "air|field_150350_a"), "Owned machine fixture starts in air");
        Object itemList = field(type("gregtech.api.enums.ItemList"), "Hatch_Input_LV");
        Object stack = call(itemList, "get", Long.valueOf(1L), new Object[0]);
        require(stack != null, "Registered legal LV input hatch stack");
        Object item = call(stack, "getItem|func_77973_b");
        require(type("gregtech.common.blocks.ItemMachines").isInstance(item), "Actual GT ItemMachines");
        require(Boolean.TRUE.equals(call(item, "placeBlockAt", stack, player, world, X, Y, Z, 1, .5f, .5f, .5f, 0)), "Native GT ItemMachines placement");
        serverTile = call(world, "getTileEntity|func_147438_o", X, Y, Z);
        require(type("gregtech.api.metatileentity.BaseMetaTileEntity").isInstance(serverTile), "Real server BaseMetaTileEntity");
        Object north = field(type("net.minecraftforge.common.util.ForgeDirection"), "NORTH");
        Object south = field(type("net.minecraftforge.common.util.ForgeDirection"), "SOUTH");
        Object metaTile = call(serverTile, "getMetaTileEntity");
        require(Boolean.TRUE.equals(call(metaTile, "isFacingValid", north))
            && Boolean.TRUE.equals(call(metaTile, "isFacingValid", south)), "Native hatch accepts both tested directions");
        call(serverTile, "setFrontFacing", field(type("net.minecraftforge.common.util.ForgeDirection"), "NORTH"));
        call(serverTile, "setColorization", Byte.valueOf((byte) -1));
        require(facing(serverTile) == 2 && color(serverTile) == -1, "Native initial facing and color readback");
        machineStack = stack; machineId = number(call(serverTile, "getMetaTileID"));
        require(machineId == number(call(stack, "getItemDamage|func_77960_j")), "GT machine meta ID equals legal stack");
        call(player, "setPositionAndUpdate|func_70634_a", 8.5d, 64d, 3.5d);
        set(player, "rotationYaw|field_70177_z", Float.valueOf(0f)); set(player, "rotationPitch|field_70125_A", Float.valueOf(0f));
        JsonObject row = new JsonObject(); row.addProperty("kind", "nativeServerFixture"); row.addProperty("actorUUID", String.valueOf(call(player, "getUniqueID|func_110124_au")));
        row.addProperty("serverThread", Thread.currentThread().getName()); row.addProperty("ownedSave", actual.toString());
        row.addProperty("gameMode", "SURVIVAL"); row.addProperty("terrain", "FLAT"); row.addProperty("machineId", machineId);
        row.addProperty("machineClass", serverTile.getClass().getName()); row.addProperty("metaClass", call(serverTile, "getMetaTileEntity").getClass().getName());
        row.addProperty("originalBlocksRetained", originals.size()); serverRows.add(row);
        ready = true;
    }

    private void save(Object world, int x, int y, int z) throws Exception {
        require(call(world, "getTileEntity|func_147438_o", x, y, z) == null, "Do not overwrite an existing tile entity");
        originals.add(new SavedBlock(x, y, z, call(world, "getBlock|func_147439_a", x, y, z), number(call(world, "getBlockMetadata|func_72805_g", x, y, z))));
    }
    private void saveAndSet(Object world, int x, int y, int z, Object block, int meta) throws Exception {
        save(world, x, y, z); require(Boolean.TRUE.equals(call(world, "setBlock|func_147465_d", x, y, z, block, meta, 3)), "Owned support-pad placement");
    }
    private void restoreFixture() throws Exception {
        if (serverWorld != null) {
            if (serverPlayer != null) {
                call(serverPlayer, "setPositionAndUpdate|func_70634_a", actorX, actorY, actorZ);
                set(serverPlayer, "rotationYaw|field_70177_z", Float.valueOf(actorYaw));
                set(serverPlayer, "rotationPitch|field_70125_A", Float.valueOf(actorPitch));
            }
            for (int i = originals.size() - 1; i >= 0; i--) {
                SavedBlock original = originals.get(i);
                call(serverWorld, "setBlock|func_147465_d", original.x, original.y, original.z, original.block, original.meta, 3);
                require(call(serverWorld, "getBlock|func_147439_a", original.x, original.y, original.z) == original.block
                    && number(call(serverWorld, "getBlockMetadata|func_72805_g", original.x, original.y, original.z)) == original.meta, "Fixture original block/meta restored");
            }
        }
        JsonObject row = new JsonObject(); row.addProperty("kind", "fixtureRestoration"); row.addProperty("restoredBlocks", originals.size());
        row.addProperty("serverThread", Thread.currentThread().getName()); serverRows.add(row);
        restored = true;
    }

    private void clientTick() {
        if (terminal || cleaned) return;
        try {
            ticks++;
            drainEvidence();
            require(field(mc, "theWorld|field_71441_e") == clientWorld && field(mc, "thePlayer|field_71439_g") == clientPlayer, "Real client world/player identity retained");
            if (error != null) { finishWhenRestored(); return; }
            require(System.nanoTime() - started < 150000000000L, "World projection scene bounded to 150 seconds");
            // The production client first observes this world's context through its own normal lifecycle.
            if (!ready || ticks < 40) return;
            if (state == null) {
                Object tile = call(clientWorld, "getTileEntity|func_147438_o", X, Y, Z);
                if (tile == null || !type("gregtech.api.metatileentity.BaseMetaTileEntity").isInstance(tile)
                    || number(call(tile, "getMetaTileID")) != machineId || facing(tile) != 2 || color(tile) != -1) return;
                clientTile = tile;
                installSnapshot();
                return;
            }
            if (restoreRequested) { finishWhenRestored(); return; }
            if (renders == 0) return;
            assertIdentity();
            require(call(clientWorld, "getTileEntity|func_147438_o", X, Y, Z) == clientTile, "Client native GT tile never replaced during ABA");
            if (targetGroup == null || farGroup == null || !fullyBuilt(targetGroup) || !fullyBuilt(farGroup)) return;
            if (stage == 0) {
                if (ghostCount() != 2 || !matchedTarget()) { baselineStable = 0; return; }
                if (++baselineStable < 20) return;
                baselineIndex = number(field(type(LITE), "worldIndex"));
                targetOriginalBatch = firstBatch(targetGroup); farOriginalBatch = firstBatch(farGroup); farOriginalMesh = field(farGroup, "directGeometry");
                check("world.real-native-projection-baseline", baselineIndex == 3 && targetOriginalBatch != null && farOriginalBatch != null,
                    "Actual RenderWorldLast production world render: 3 indexed cells, matching machine hidden, 2 ordinary ghosts, both regions compiled");
                result.addProperty("baselineRenders", renders); result.addProperty("baselineGhosts", ghostCount());
                result.addProperty("baselineStateIdentity", System.identityHashCode(stateIdentity));
                result.addProperty("baselineTargetBatchIdentity", System.identityHashCode(targetOriginalBatch));
                result.addProperty("baselineFarBatchIdentity", System.identityHashCode(farOriginalBatch));
                startStage(1); return;
            }
            stageTicks++;
            require(stageTicks < 500, "Native transition received and rendered within 500 client ticks, stage=" + stage);
            boolean expectedMatched = stage == 2 || stage == 4;
            int expectedFacing = stage == 1 ? 3 : 2, expectedColor = stage == 3 ? 4 : -1;
            if (appliedMutation < stage || capturedStage != stage || facing(clientTile) != expectedFacing || color(clientTile) != expectedColor
                || matchedTarget() != expectedMatched || ghostCount() != (expectedMatched ? 2 : 3)) { stageStable = 0; return; }
            if (++stageStable < 5) return;
            check("world.transition-" + stage + "-ghost-mask", true, "native client GT facing=" + facing(clientTile) + ", color=" + color(clientTile) + ", matched=" + matchedTarget() + ", ghosts=" + ghostCount());
            check("world.transition-" + stage + "-local-mesh", firstBatch(targetGroup) != targetOriginalBatch && firstBatch(targetGroup) != null,
                "Changed native group compiled a new actual world batch after the packet retired its old batch");
            check("world.transition-" + stage + "-far-region", farUnchanged(), "Far region keeps same object, cells, mask, batch and direct mesh; no match dirtiness");
            currentTransition.addProperty("clientFacing", facing(clientTile)); currentTransition.addProperty("clientColor", color(clientTile));
            currentTransition.addProperty("ghostsAfterRender", ghostCount()); currentTransition.addProperty("targetMatchedAfterRender", matchedTarget());
            currentTransition.addProperty("targetBatchIdentityAfterRender", System.identityHashCode(firstBatch(targetGroup)));
            currentTransition.addProperty("farBatchIdentityAfterRender", System.identityHashCode(firstBatch(farGroup)));
            currentTransition.addProperty("worldIndexAfterRender", number(field(type(LITE), "worldIndex")));
            currentTransition.addProperty("worldStateIdentity", System.identityHashCode(field(type(LITE), "worldState")));
            currentTransition.addProperty("renderCount", renders); currentTransition.addProperty("pass", true);
            transitions.add(currentTransition);
            targetOriginalBatch = firstBatch(targetGroup);
            if (stage < 4) startStage(stage + 1);
            else { restoreRequested = true; result.addProperty("scenarioOutcome", "PASS"); write(); }
        } catch (Throwable failure) { fail(failure); finishWhenRestored(); }
    }

    private void installSnapshot() throws Exception {
        Object nbt = compound();
        nbtString(nbt, "session", "owned-world-projection-" + run); nbtInt(nbt, "dimension", 0);
        Object rows = type("net.minecraft.nbt.NBTTagList").newInstance();
        Object machine = cell(X, Y, Z, String.valueOf(call(field(type("net.minecraft.block.Block"), "blockRegistry|field_149771_c"), "getNameForObject|func_148750_c", call(clientWorld, "getBlock|func_147439_a", X, Y, Z))));
        nbtInt(machine, "wantMeta", number(call(clientWorld, "getBlockMetadata|func_72805_g", X, Y, Z)));
        nbtInt(machine, "targetFacing", 2);
        Object stackNbt = compound(); call(machineStack, "writeToNBT|func_77955_b", stackNbt); nbtTag(machine, "targetStack", stackNbt);
        Object display = compound(); nbtInt(display, "color", -1); nbtTag(machine, "targetDisplay", display);
        nbtString(machine, "sourceClass", call(serverTile, "getMetaTileEntity").getClass().getName());
        call(rows, "appendTag|func_74742_a", machine);
        call(rows, "appendTag|func_74742_a", cell(10, Y, Z, "minecraft:stone"));
        call(rows, "appendTag|func_74742_a", cell(X, Y, FAR_Z, "minecraft:stone"));
        nbtTag(nbt, "cells", rows);
        Constructor<?> ctor = type("com.miaokatze.gtmb.client.hologram.HologramState").getDeclaredConstructor(type("net.minecraft.nbt.NBTTagCompound"));
        ctor.setAccessible(true); state = ctor.newInstance(nbt);
        cells = field(state, "cells"); regions = field(state, "regions"); stateIdentity = field(state, "identity"); targetCell = ((List<?>) cells).get(0);
        require(((List<?>) cells).size() == 3, "Three-cell projection fixture");
        // Owned in-game camera only. No OS focus or input, and no changes to game preferences.
        set(clientPlayer, "rotationYaw|field_70177_z", Float.valueOf(0f)); set(clientPlayer, "prevRotationYaw|field_70126_B", Float.valueOf(0f));
        set(clientPlayer, "rotationPitch|field_70125_A", Float.valueOf(0f)); set(clientPlayer, "prevRotationPitch|field_70127_C", Float.valueOf(0f));
        write();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public void onWorldRender(RenderWorldLastEvent event) { render(event.partialTicks); }
    private void renderWorld(float partialTicks) {
        if (state == null || terminal || cleaned || restoreRequested) return;
        try {
            require(Thread.currentThread() == clientThread, "Natural world GL rendering stays on the client thread");
            call(type(RENDERER), "world", state, partialTicks);
            renders++;
            if (targetGroup == null) targetGroup = findGroup(X, Y, Z);
            if (farGroup == null) farGroup = findGroup(X, Y, FAR_Z);
        } catch (Throwable failure) { fail(failure); }
    }

    private void startStage(int next) throws Exception {
        stage = next; stageTicks = stageStable = 0; capturedStage = 0;
        currentTransition = new JsonObject(); currentTransition.addProperty("stage", stage);
        currentTransition.addProperty("kind", stage <= 2 ? "facing" : "color");
        currentTransition.addProperty("expected", stage == 1 ? "NORTH -> SOUTH: hidden -> ghost" : stage == 2 ? "SOUTH -> NORTH: ghost -> hidden" : stage == 3 ? "-1 -> 4: hidden -> ghost" : "4 -> -1: ghost -> hidden");
        currentTransition.addProperty("targetBatchIdentityBefore", System.identityHashCode(targetOriginalBatch));
        requestedMutation = stage;
        write();
    }

    private void drainEvidence() throws Exception {
        JsonObject row;
        while ((row = serverRows.poll()) != null) {
            if (row.has("kind")) result.add(row.get("kind").getAsString(), row);
            else if (currentTransition != null && row.get("stage").getAsInt() == stage) currentTransition.add("serverSetter", row);
        }
        while ((row = packetRows.poll()) != null) {
            packets.add(row);
            if (!row.has("stage") || row.get("stage").getAsInt() != stage || stage == 0 || !row.has("targetCellDirtyAfterNativeReceive")) continue;
            int eventId = row.get("eventId").getAsInt(), value = row.get("eventValue").getAsInt();
            boolean relevant = stage <= 2 ? eventId == commonEvent && (value & 7) == (stage == 1 ? 3 : 2)
                : eventId == colorEvent && value == (stage == 3 ? 5 : 0);
            if (!relevant) continue;
            boolean exactLocalRetirement = row.get("targetCellDirtyAfterNativeReceive").getAsBoolean()
                && row.get("dirtyCountAfterNativeReceive").getAsInt() == 1
                && !row.get("allMatchesDirtyAfterNativeReceive").getAsBoolean()
                && row.get("targetBuiltAfterNativeReceive").getAsInt() == 0
                && row.get("targetBatchesAfterNativeReceive").getAsInt() == 0
                && row.get("farDirtyCountAfterNativeReceive").getAsInt() == 0
                && !row.get("farAllMatchesDirtyAfterNativeReceive").getAsBoolean()
                && row.get("farBatchPreservedAfterNativeReceive").getAsBoolean();
            require(exactLocalRetirement, "Actual decoded GT event must retire exactly the target native group's batches, preserving the far region: " + row);
            currentTransition.add("nativeReceiveEvidence", row); capturedStage = stage;
        }
    }

    private void assertIdentity() throws Exception {
        require(field(type(LITE), "worldState") == state && field(state, "cells") == cells && field(state, "regions") == regions
            && field(state, "identity") == stateIdentity && ((List<?>) cells).get(0) == targetCell, "State/geometry directory/target identity never rebuilt");
        if (stage > 0) require(number(field(type(LITE), "worldIndex")) == baselineIndex && ((Map<?, ?>) field(type(LITE), "worldGroups")).size() == 2,
            "Complete three-cell target index and two region objects retained");
        if (stage > 0) require(findGroup(X, Y, Z) == targetGroup && findGroup(X, Y, FAR_Z) == farGroup,
            "Native events keep the exact same target and far region objects");
    }
    private boolean farUnchanged() throws Exception {
        return findGroup(X, Y, FAR_Z) == farGroup && firstBatch(farGroup) == farOriginalBatch && field(farGroup, "directGeometry") == farOriginalMesh
            && fullyBuilt(farGroup) && ((Set<?>) field(farGroup, "dirtyMatches")).isEmpty() && !((Boolean) field(farGroup, "allMatchesDirty"))
            && ((Map<?, ?>) field(farGroup, "cellMasks")).get(Integer.valueOf(2)).equals(Byte.valueOf((byte) 3));
    }
    private int facing(Object tile) throws Exception { return number(call(call(tile, "getFrontFacing"), "ordinal")); }
    private int color(Object tile) throws Exception { return number(call(tile, "getColorization")); }
    private int ghostCount() throws Exception { return ((Set<?>) field(type(LITE), "worldGhosts")).size(); }
    private boolean matchedTarget() throws Exception { return ((Set<?>) field(targetGroup, "matchedIndices")).contains(Integer.valueOf(0)); }
    private static Object firstBatch(Object group) throws Exception {
        if (group == null) return null; List<?> list = (List<?>) field(group, "batches"); return list.isEmpty() ? null : list.get(0);
    }
    private boolean fullyBuilt(Object group) throws Exception {
        return group != null && number(field(group, "built")) == ((List<?>) field(group, "cells")).size()
            && !((Boolean) field(group, "allMatchesDirty")) && ((Set<?>) field(group, "dirtyMatches")).isEmpty() && firstBatch(group) != null;
    }
    private Object findGroup(int x, int y, int z) throws Exception {
        for (Object group : ((Map<?, ?>) field(type(LITE), "worldGroups")).values())
            if (number(field(group, "x")) == (x >> 4 << 4) && number(field(group, "y")) == (y >> 4 << 4) && number(field(group, "z")) == (z >> 4 << 4)) return group;
        return null;
    }

    private synchronized void fail(Throwable failure) {
        if (error == null) { error = failure; result.addProperty("failure", failure.toString()); }
        restoreRequested = true;
        result.addProperty("status", "FAIL"); result.addProperty("scenarioOutcome", "FAIL");
        try { write(); } catch (Throwable writing) { failure.addSuppressed(writing); }
    }
    private void finishWhenRestored() {
        if (!restored) return;
        try {
            drainEvidence();
            if (ready && call(clientWorld, "getTileEntity|func_147438_o", X, Y, Z) != null && ++finishTicks < 100) return;
            check("recovery.server-fixture-original-blocks", restored, "All " + originals.size() + " retained original block/meta pairs read back on the server thread");
            check("recovery.client-machine-removed", !ready || call(clientWorld, "getTileEntity|func_147438_o", X, Y, Z) == null, "Native restoration packets reached the same real client world");
            if (error == null) {
                check("world.facing-and-color-ABA", transitions.size() == 4, "NORTH/SOUTH/NORTH plus -1/4/-1 through actual GT packets and natural world GL rendering");
                result.addProperty("passed", true); result.addProperty("status", "PASS"); result.addProperty("outcome", "PASS");
            } else result.addProperty("outcome", "FAIL");
            result.addProperty("completed", true); result.addProperty("fixtureRestored", true); result.addProperty("worldUnload", "HOST_PENDING");
            terminal = true; write();
        } catch (Throwable failure) {
            fail(failure); result.addProperty("completed", true); result.addProperty("outcome", "FAIL"); terminal = true;
            try { write(); } catch (Exception ignored) { }
        }
    }
    private void close() throws Exception {
        if (cleaned) return;
        require(restored || originals.isEmpty(), "Preserve scene until server-thread fixture restoration completes");
        if (registered) { FMLCommonHandler.instance().bus().unregister(this); MinecraftForge.EVENT_BUS.unregister(this); registered = false; }
        if (observedChannel != null && observerName != null && observedChannel.pipeline().get(observerName) != null) observedChannel.pipeline().remove(observerName);
        if (state != null && field(type(LITE), "worldState") == state) call(type(RENDERER), "nativeSnapshotChanged");
        result.addProperty("observerRemoved", observedChannel == null || observedChannel.pipeline().get(observerName) == null);
        result.addProperty("rendererReleased", state == null || field(type(LITE), "worldState") != state);
        result.addProperty("cleanupComplete", true); cleaned = true; write();
    }
    private synchronized JsonObject snapshot() {
        result.addProperty("clientTicks", ticks); result.addProperty("naturalWorldRenders", renders);
        result.addProperty("serverMutationApplied", appliedMutation); result.addProperty("fixtureRestored", restored);
        result.addProperty("elapsedMillis", (System.nanoTime() - started) / 1000000L);
        return new JsonParser().parse(result.toString()).getAsJsonObject();
    }
    private synchronized void write() throws Exception {
        Path temporary = out.resolve("gtmb-world-projection-events.json.tmp"), destination = out.resolve("gtmb-world-projection-events.json");
        Files.write(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(snapshot()).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private static Object compound() throws Exception { return type("net.minecraft.nbt.NBTTagCompound").newInstance(); }
    private static void nbtString(Object tag, String key, String value) throws Exception { call(tag, "setString|func_74778_a", key, value); }
    private static void nbtInt(Object tag, String key, int value) throws Exception { call(tag, "setInteger|func_74768_a", key, value); }
    private static void nbtTag(Object tag, String key, Object value) throws Exception { call(tag, "setTag|func_74782_a", key, value); }
    private static Object cell(int x, int y, int z, String target) throws Exception {
        Object tag = compound(); nbtInt(tag, "x", x); nbtInt(tag, "y", y); nbtInt(tag, "z", z);
        nbtString(tag, "id", "minecraft:air"); nbtString(tag, "wantId", target); nbtString(tag, "status", "satisfied");
        nbtInt(tag, "targetFacing", -1); return tag;
    }
    private static void set(Object target, String aliases, Object value) throws Exception {
        for (String name : aliases.split("\\|")) for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(aliases);
    }
    private static final class SavedBlock {
        final int x, y, z, meta; final Object block;
        SavedBlock(int x, int y, int z, Object block, int meta) { this.x = x; this.y = y; this.z = z; this.block = block; this.meta = meta; }
    }
}
