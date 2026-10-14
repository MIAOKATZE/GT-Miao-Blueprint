package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.ForgeChunkManager;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

import appeng.api.AEApi;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.GregTechAPI;

/**
 * Opt-in paired fresh-save integration fixture. Machines and inventories are native world objects; the author and
 * placer use separate actual Netty clients. Fixture stock is explicitly finite and never comes from copied cell NBT.
 */
public final class HologramSulfurLineSmoke {

    private static final String PREFIX = "[GTMB-SULFUR-LINE] ";
    private static final int SOURCE_X = 64, SOURCE_Y = 80, SOURCE_Z = 64;
    private static final int TARGET_X = 320, TARGET_Y = 80, TARGET_Z = 320;
    private static HologramSulfurLineSmoke installed;
    private final long started = System.nanoTime();
    private final NBTTagCompound findings = new NBTTagCompound();
    private final Set<ChunkCoordIntPair> chunks = new LinkedHashSet<>();
    private EntityPlayerMP actor;
    private WorldServer world;
    private Object line, decor, bank;
    private ForgeChunkManager.Ticket ticket;
    private NBTTagCompound transfer, inventoryBefore;
    private int[] bounds;
    private int ticks, stage;
    private boolean finished;
    private String session = "", result = "PENDING";
    private ItemStack quantumTerminal;

    public static synchronized void install() {
        if (!enabled() || installed != null) return;
        installed = new HologramSulfurLineSmoke();
        FMLCommonHandler.instance()
            .bus()
            .register(installed);
    }

    public static boolean enabled() {
        return Boolean.getBoolean("gtmb.hologram.sulfurLineServer");
    }

    public static boolean done() {
        return !enabled() || installed != null && installed.finished;
    }

    public static String status() {
        return !enabled() ? "DISABLED" : installed == null ? "PENDING" : installed.result;
    }

    public static NBTTagCompound receipt() {
        return installed == null ? new NBTTagCompound() : (NBTTagCompound) installed.findings.copy();
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        try {
            require(++ticks <= Integer.getInteger("gtmb.hologram.sulfurLineMaxTicks", 24000), "native tick timeout");
            require(System.nanoTime() - started < 1800000000000L, "wall-clock timeout");
            MinecraftServer server = MinecraftServer.getServer();
            require(server != null, "actual FML server exists");
            String phase = System.getProperty("gtmb.hologram.sulfurLinePhase", "source");
            require("source".equals(phase) || "target".equals(phase), "explicit source/target phase");
            world = server.worldServerForDimension(0);
            require(
                world != null && world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-sulfur-"),
                "fresh isolated sulfur world guard");
            if (actor == null) {
                if (server.getConfigurationManager().playerEntityList.isEmpty()) return;
                require(server.getConfigurationManager().playerEntityList.size() == 1, "one actual connected client");
                actor = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
                require(!(actor instanceof net.minecraftforge.common.util.FakePlayer), "actor is a real Netty player");
                actor.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
                actor.capabilities.allowFlying = actor.capabilities.isFlying = true;
                actor.sendPlayerAbilities();
                for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++)
                    actor.inventory.mainInventory[slot] = null;
                actor.inventory.mainInventory[0] = new ItemStack(
                    (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
                actor.inventory.currentItem = 0;
                findings.setString(
                    "world",
                    world.getWorldInfo()
                        .getWorldName());
                findings.setString(
                    "actor",
                    actor.getUniqueID()
                        .toString());
                findings.setString(
                    "profile",
                    actor.getGameProfile()
                        .getName());
                findings.setString("phase", phase);
                acquireChunks("source".equals(phase) ? 48 : 304, "source".equals(phase) ? 48 : 304);
                if ("source".equals(phase)) startSource();
                else startTarget();
            }
            retainChunks();
            if (line != null) call(line, "tick");
            if ("source".equals(phase)) sourceTick(server);
            else targetTick(server);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void startSource() throws Exception {
        nativeSourcePreflight(
            "HologramQuantumClearNativeSmoke",
            "SOURCE_NATIVE_PREFLIGHT_QUANTUM_CLEAR",
            "sulfur-source-quantum-clear-native.nbt",
            "nativeQuantumClear");
        nativeSourcePreflight(
            "HologramSulfurLineSmokeOldIssues",
            "SOURCE_NATIVE_PREFLIGHT_OLD_ISSUES",
            "sulfur-source-old-issues-native.nbt",
            "nativeOldIssues");
        line = nativeStatic("create", world, actor, SOURCE_X, SOURCE_Y, SOURCE_Z);
        int[] controller = (int[]) call(line, "firstController");
        decor = decorStatic(
            "create",
            world,
            SOURCE_X,
            SOURCE_Y,
            SOURCE_Z + 8,
            controller[0],
            controller[1],
            controller[2]);
        createQuantum(SOURCE_X - 2, SOURCE_Y, SOURCE_Z + 8, SOURCE_X + 18, SOURCE_Y, SOURCE_Z + 8, true);
        bounds = union((int[]) call(line, "bounds"), (int[]) call(decor, "bounds"));
        bounds = union(
            bounds,
            new int[] { SOURCE_X + 18, SOURCE_Y, SOURCE_Z + 8, SOURCE_X + 18, SOURCE_Y, SOURCE_Z + 8 });
        moveNear(SOURCE_X, SOURCE_Y, SOURCE_Z + 12);
        findings.setIntArray("bounds", bounds);
        stage = 1;
    }

    /** Independent service-only regressions use their own finite fixture actor, inventory and world coordinates. */
    private void nativeSourcePreflight(String helper, String phase, String filename, String finding) throws Exception {
        NBTTagCompound proof = (NBTTagCompound) invoke(
            Class.forName("com.miaokatze.gtmb.hologram." + helper),
            null,
            "run",
            world);
        require(
            proof.getBoolean("passed") && proof.getBoolean("actualNativeWorld")
                && proof.getBoolean("actualService")
                && proof.hasKey("actualGui", 1)
                && !proof.getBoolean("actualGui")
                && proof.getBoolean("fakeFixtureActor"),
            "independent native source preflight succeeded without claiming GUI coverage: " + helper);
        NBTTagCompound receipt = base(phase);
        receipt.setTag("nativeProof", proof);
        receipt.setBoolean("passed", true);
        write(filename, receipt);
        findings.setTag(finding, receipt.copy());
    }

    private void sourceTick(MinecraftServer server) throws Exception {
        if (stage == 1) {
            if (!(Boolean) call(line, "ready")) return;
            call(line, "supplyInitialStock");
            findings.setTag("sourceInitialStock", (NBTTagCompound) call(line, "initialStock"));
            stage = 2;
        }
        if (stage == 2) {
            if (!(Boolean) call(line, "operational") || (Long) call(line, "acid") <= 0) return;
            NBTTagCompound running = findings.hasKey("sourceRunning", 10) ? findings.getCompoundTag("sourceRunning")
                : null;
            if (running == null) {
                running = base("SOURCE_RUNNING");
                NBTTagCompound nativeReceipt = (NBTTagCompound) call(line, "receipt");
                if (!eachNativeRecipeCompleted(nativeReceipt)) return;
                assertAcidProduction(nativeReceipt);
                running.setTag("nativeLine", nativeReceipt);
                running.setIntArray("bounds", bounds);
                running.setBoolean("passed", true);
                write("sulfur-source-running.nbt", running);
                findings.setTag("sourceRunning", running.copy());
            }
            NBTTagCompound frame = read("sulfur-source-running-frame.nbt");
            if (frame == null) return;
            require(
                frame.getBoolean("passed") && "RenderTick.END".equals(frame.getString("capturePhase")),
                "actual source runtime frame");
            call(line, "stop");
            findings.setTag("sourceRunningFrame", frame);
            stage = 3;
        }
        if (stage == 3) {
            if (!(Boolean) call(line, "quiescent")) return;
            call(decor, "verifySource");
            NBTTagCompound ready = base("SOURCE_QUIESCENT");
            ready.setIntArray("bounds", bounds);
            ready.setTag("nativeLine", (NBTTagCompound) call(line, "receipt"));
            ready.setBoolean("passed", true);
            write("sulfur-server-ready.nbt", ready);
            findings.setTag("sourceQuiescent", ready);
            findings.setTag("sourceMeBeforeCapture", nativeMeObservations());
            stage = 4;
        }
        if (stage != 4) return;
        failIfClientMarker("sulfur-author-fail.txt");
        NBTTagCompound author = read("sulfur-author-final.nbt");
        if (author == null) return;
        require(
            author.getBoolean("passed") && author.getBoolean("actualNettyClient"),
            "actual author client succeeded");
        require(
            author.getBoolean("processingComplete") && author.getBoolean("newSave")
                && author.getBoolean("overwriteSave")
                && author.getBoolean("processingPresetSave"),
            "six-stage/new/overwrite/preset GUI coverage");
        Object live = liveSession();
        require(live != null && (Boolean) field(live, "processingReviewed"), "real server processing gate was reached");
        session = (String) field(live, "id");
        HologramPresets.Preset processed = (HologramPresets.Preset) field(live, "virtualPreset");
        require(
            processed != null && HologramBlueprintProcessing.complete(processed),
            "native server policy receipt complete");
        HologramBlueprintArtifact.Artifact artifact = exportedArtifact(author, "exportedJsonSha256");
        byte[] bytes = artifact.json;
        HologramPresets.Preset exported = HologramBlueprintSharing.decode(new String(bytes, StandardCharsets.UTF_8));
        require(
            actor.getUniqueID()
                .toString()
                .equals(exported.authorUuid),
            "saved blueprint has actual author UUID");
        require(HologramBlueprintProcessing.complete(exported), "saved exported policy stages complete");
        assertPortableMeConfiguration(exported);
        call(decor, "verifySource");
        NBTTagCompound sourceAfter = (NBTTagCompound) call(line, "receipt");
        NBTTagCompound sourceBefore = findings.getCompoundTag("sourceQuiescent")
            .getCompoundTag("nativeLine");
        for (String key : new String[] { "sulfurRemaining", "oxygenRemaining", "waterRemaining", "acid",
            "sulfurDioxide", "sulfurTrioxide" })
            require(
                sourceBefore.getLong(key) == sourceAfter.getLong(key),
                "capture/process/save did not debit stopped source ME stock: " + key);
        findings.setTag("sourceMeAfterAuthor", nativeMeObservations());
        transfer = base("SOURCE_COMPLETE");
        transfer.setBoolean("passed", true);
        transfer.setByteArray("exportedJson", bytes);
        transfer.setString("exportedSha256", sha256(bytes));
        transfer.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        transfer.setByteArray("exportedPng", artifact.png);
        transfer.setString("exportedPngSha256", artifact.pngSha256);
        transfer.setInteger("previewWidth", artifact.width);
        transfer.setInteger("previewHeight", artifact.height);
        transfer.setString("localId", author.getString("localId"));
        transfer.setString(
            "authorUuid",
            actor.getUniqueID()
                .toString());
        transfer.setString(
            "authorProfile",
            actor.getGameProfile()
                .getName());
        transfer.setString(
            "sourceWorld",
            world.getWorldInfo()
                .getWorldName());
        transfer.setIntArray("sourceBounds", bounds);
        transfer.setTag("authorClient", author.copy());
        transfer.setTag("sourceNativeLine", (NBTTagCompound) call(line, "receipt"));
        transfer.setTag(
            "sourceMeBeforeCapture",
            findings.getCompoundTag("sourceMeBeforeCapture")
                .copy());
        transfer.setTag(
            "sourceMeAfterAuthor",
            findings.getCompoundTag("sourceMeAfterAuthor")
                .copy());
        transfer.setTag(
            "serverProcessingPolicy",
            ((HologramBlueprintProcessing.Policy) field(live, "processingPolicy")).write());
        write("sulfur-author-transfer.nbt", transfer);
        write("sulfur-author-accepted.nbt", transfer);
        findings.setTag("transfer", transfer.copy());
        finish(server, "SOURCE_PASS");
    }

    private void startTarget() throws Exception {
        transfer = read("sulfur-author-transfer.nbt");
        require(transfer != null && transfer.getBoolean("passed"), "source-generated transfer exists");
        require(
            !actor.getUniqueID()
                .toString()
                .equals(transfer.getString("authorUuid")),
            "another actual placing profile");
        require(
            !world.getWorldInfo()
                .getWorldName()
                .equals(transfer.getString("sourceWorld")),
            "another fresh save");
        HologramBlueprintArtifact.Artifact artifact = exportedArtifact(transfer, "exportedSha256");
        byte[] bytes = artifact.json;
        int[] source = transfer.getIntArray("sourceBounds");
        require(source.length == 6, "source bounds receipt");
        bounds = new int[] { TARGET_X, TARGET_Y, TARGET_Z, TARGET_X + source[3] - source[0],
            TARGET_Y + source[4] - source[1], TARGET_Z + source[5] - source[2] };
        for (int x = bounds[0]; x <= bounds[3]; x++)
            for (int y = bounds[1]; y <= bounds[4]; y++) for (int z = bounds[2]; z <= bounds[5]; z++)
                require(world.isAirBlock(x, y, z), "entire new target footprint fresh air");
        createQuantum(TARGET_X - 2, TARGET_Y, TARGET_Z + 8, 0, 0, 0, false);
        bank = bankStatic("create", world, actor, TARGET_X - 2, TARGET_Y, TARGET_Z + 8);
        actor.inventory.mainInventory[35] = quantumTerminal.copy();
        actor.inventoryContainer.detectAndSendChanges();
        moveNear(TARGET_X, TARGET_Y, TARGET_Z + 12);
        NBTTagCompound ready = base("TARGET_READY");
        ready.setIntArray("targetOrigin", new int[] { TARGET_X, TARGET_Y, TARGET_Z });
        ready.setIntArray("sourceBounds", source);
        ready.setIntArray("bounds", bounds);
        ready.setByteArray("exportedJson", bytes);
        ready.setString("exportedSha256", transfer.getString("exportedSha256"));
        ready.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        ready.setByteArray("exportedPng", artifact.png);
        ready.setString("exportedPngSha256", artifact.pngSha256);
        ready.setInteger("previewWidth", artifact.width);
        ready.setInteger("previewHeight", artifact.height);
        ready.setString("authorUuid", transfer.getString("authorUuid"));
        ready.setInteger("quantumTerminalSlot", 35);
        ready.setBoolean("passed", true);
        write("sulfur-target-ready.nbt", ready);
        stage = 10;
    }

    private void targetTick(MinecraftServer server) throws Exception {
        failIfClientMarker("sulfur-placer-fail.txt");
        Object live = liveSession();
        if (stage == 10 && live != null && (Boolean) field(live, "virtualLocked")) {
            if (!(Boolean) call(bank, "ready")) return;
            session = (String) field(live, "id");
            supplyQuotedInventory(live);
            stage = 11;
        }
        if (stage == 11) {
            require(live != null && session.equals(field(live, "id")), "placement uses the actual locked session");
            int job = (Integer) field(live, "job");
            if (job == 1 || job == 2 || job == 4) return;
            HologramCapture captured = (HologramCapture) field(live, "capture");
            if (captured == null || captured.cells.stream()
                .anyMatch(cell -> !cell.excluded && "pending".equals(cell.status))) return;
            if (line == null) {
                try {
                    line = nativeStatic("discover", world, actor, TARGET_X, TARGET_Y, TARGET_Z);
                } catch (ReflectiveOperationException | AssertionError notYetPlaced) {
                    return;
                }
                call(line, "supplyBootPower");
            }
            if (!(Boolean) call(line, "ready")) return;
            findings.setTag("constructionInventoryBefore", inventoryBefore.copy());
            findings.setTag("constructionInventoryAfter", inventory());
            call(bank, "verifyPaid");
            findings.setTag("constructionMeStockAfter", (NBTTagCompound) call(bank, "receipt"));
            verifyTargetQuantum(TARGET_X + 18, TARGET_Y, TARGET_Z + 8);
            NBTTagCompound preSupply = (NBTTagCompound) call(line, "receipt");
            findings.setTag("targetBeforeFixtureSupply", preSupply.copy());
            for (String key : new String[] { "acid", "sulfurRemaining", "oxygenRemaining", "waterRemaining",
                "sulfurDioxide", "sulfurTrioxide" })
                require(preSupply.getLong(key) == 0, "target blueprint did not grant stored source resources: " + key);
            call(line, "supplyInitialStock");
            findings.setTag("targetInitialStock", (NBTTagCompound) call(line, "initialStock"));
            findings.setBoolean("targetStockFromBlueprint", false);
            stage = 12;
        }
        if (stage == 12) {
            if (!(Boolean) call(line, "operational") || (Long) call(line, "acid") <= 0) return;
            int[] source = transfer.getIntArray("sourceBounds");
            decorStatic(
                "verifyPlaced",
                world,
                TARGET_X - source[0],
                TARGET_Y - source[1],
                TARGET_Z - source[2],
                SOURCE_X,
                SOURCE_Y,
                SOURCE_Z + 8);
            NBTTagCompound running = base("TARGET_RUNNING");
            running.setBoolean("passed", true);
            running.setIntArray("bounds", bounds);
            NBTTagCompound nativeReceipt = (NBTTagCompound) call(line, "receipt");
            if (!eachNativeRecipeCompleted(nativeReceipt)) return;
            assertAcidProduction(nativeReceipt);
            running.setTag("nativeLine", nativeReceipt);
            running.setString("sourceBlueprintSha256", transfer.getString("exportedSha256"));
            running.setString("sourcePreviewSha256", transfer.getString("exportedPngSha256"));
            running.setBoolean("newSave", true);
            running.setBoolean("newProfile", true);
            write("sulfur-target-running.nbt", running);
            findings.setTag("targetRunning", running);
            stage = 13;
        }
        if (stage != 13) return;
        if (!findings.getBoolean("monitorCameraReady")) {
            NBTTagCompound runtimeFrame = read("sulfur-target-running-frame.nbt");
            if (runtimeFrame == null) return;
            require(
                runtimeFrame.getBoolean("passed") && runtimeFrame.getBoolean("actualFrame")
                    && session.equals(runtimeFrame.getString("session"))
                    && "RenderTick.END".equals(runtimeFrame.getString("capturePhase")),
                "actual target runtime frame precedes monitor view");
            actor.playerNetServerHandler.setPlayerLocation(TARGET_X + 4.5, TARGET_Y + 2, TARGET_Z + 1.5, 0, 20);
            NBTTagCompound monitor = base("TARGET_MONITOR_VIEW");
            monitor.setBoolean("passed", true);
            monitor.setIntArray("cameraPosition", new int[] { TARGET_X + 4, TARGET_Y + 2, TARGET_Z + 1 });
            monitor.setIntArray("bounds", bounds);
            monitor.setTag("nativeLine", (NBTTagCompound) call(line, "receipt"));
            monitor.setTag("actualRuntimeFrame", runtimeFrame.copy());
            write("sulfur-target-monitor-ready.nbt", monitor);
            findings.setBoolean("monitorCameraReady", true);
            findings.setTag("monitorView", monitor.copy());
        }
        NBTTagCompound client = read("sulfur-placer-final.nbt");
        if (client == null) return;
        require(
            client.getBoolean("passed") && client.getBoolean("actualNettyClient"),
            "actual placer client and frames complete");
        require(session.equals(client.getString("session")), "target client receipt tied to actual placement session");
        findings.setTag("placerClient", client.copy());
        finish(server, "TARGET_PASS");
    }

    /** Read the real production quote, then seed a finite native ME fixture bank. This never installs a Session. */
    private void supplyQuotedInventory(Object live) throws Exception {
        HologramBlueprintResources.Plan quoted = (HologramBlueprintResources.Plan) field(
            live,
            "blueprintTotalResources");
        require(quoted != null, "actual production aggregate material quote exists");
        HologramBlueprintResources.Plan bill = new HologramBlueprintResources.Plan().merge(quoted);
        require(!bill.items.isEmpty(), "actual blueprint has positive finite physical construction material costs");
        require(
            bill.energy.signum() == 0 && bill.essentia.isEmpty(),
            "native quoted EU/essentia require their real providers; no free resource bypass");
        call(bank, "supply", bill);
        findings.setTag("constructionMeStockBefore", (NBTTagCompound) call(bank, "receipt"));
        inventoryBefore = inventory();
        NBTTagCompound ready = base("MATERIALS_SUPPLIED");
        ready.setBoolean("passed", true);
        ready.setTag("physicalInventory", inventoryBefore.copy());
        ready.setTag("actualMeStock", (NBTTagCompound) call(bank, "receipt"));
        ready.setInteger("physicalStacks", bill.items.size());
        ready.setBoolean("finiteFixtureSupply", true);
        ready.setString("paymentProvider", "native finite ME grid with native authenticated carried wireless terminal");
        write("sulfur-materials-ready.nbt", ready);
    }

    private void createQuantum(int cx, int cy, int cz, int nx, int ny, int nz, boolean withNode) throws Exception {
        Block controllerBlock = AEApi.instance()
            .definitions()
            .blocks()
            .controller()
            .maybeBlock()
            .get();
        require(
            world.isAirBlock(cx, cy, cz) && world.setBlock(cx, cy, cz, controllerBlock, 0, 3),
            "native quantum controller installed");
        TileEntity controller = world.getTileEntity(cx, cy, cz);
        require(controller instanceof TileController, "registered native AE controller factory");
        ((IGridProxyable) controller).getProxy()
            .setOwner(actor);
        ((IGridProxyable) controller).getProxy()
            .onReady();
        Class<?> registry = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry");
        Object saved = registry.getMethod("get", World.class)
            .invoke(null, world);
        registry.getMethod("quantize", int.class, int.class, int.class)
            .invoke(saved, cx, cy, cz);
        Item terminal = null;
        for (Object candidate : Item.itemRegistry) if (candidate instanceof Item && candidate.getClass()
            .getName()
            .equals("com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal")) terminal = (Item) candidate;
        require(terminal != null, "native GTSWN quantum terminal item registered");
        quantumTerminal = new ItemStack(terminal);
        // GTSWN 1.8.64's native fixture writer is private; it also writes the mandatory bound marker.
        Method writeAnchor = terminal.getClass()
            .getDeclaredMethod("writeAnchor", ItemStack.class, World.class, int.class, int.class, int.class);
        writeAnchor.setAccessible(true);
        writeAnchor.invoke(null, quantumTerminal, world, cx, cy, cz);
        require(
            (Boolean) terminal.getClass()
                .getMethod("isBound", ItemStack.class)
                .invoke(null, quantumTerminal),
            "native quantum terminal binding writer produced a bound fixture terminal");
        int[] boundAnchor = (int[]) terminal.getClass()
            .getMethod("getAnchor", ItemStack.class)
            .invoke(null, quantumTerminal);
        require(
            Arrays.equals(boundAnchor, new int[] { world.provider.dimensionId, cx, cy, cz }),
            "native quantum terminal anchor exactly matches this actor's fixture controller");
        if (!withNode) return;
        Block node = null;
        for (Object candidate : Block.blockRegistry)
            if (candidate instanceof Block && HologramNetworkBindings.node((Block) candidate)) node = (Block) candidate;
        require(
            node != null && world.isAirBlock(nx, ny, nz) && world.setBlock(nx, ny, nz, node, 0, 3),
            "native GTSWN node installed");
        TileEntity nativeNode = world.getTileEntity(nx, ny, nz);
        require(
            nativeNode != null && HologramNetworkBindings.NODE_TILE.equals(
                nativeNode.getClass()
                    .getName()),
            "native GTSWN node factory");
        nativeNode.getClass()
            .getMethod("setAnchor", int.class, int.class, int.class, int.class)
            .invoke(nativeNode, world.provider.dimensionId, cx, cy, cz);
        nativeNode.getClass()
            .getMethod("setPlacer", EntityPlayer.class)
            .invoke(nativeNode, actor);
    }

    private void verifyTargetQuantum(int x, int y, int z) throws Exception {
        TileEntity node = world.getTileEntity(x, y, z);
        require(
            node != null && HologramNetworkBindings.NODE_TILE.equals(
                node.getClass()
                    .getName()),
            "target contains native quantum node");
        NBTTagCompound tag = new NBTTagCompound();
        node.writeToNBT(tag);
        require(
            tag.getInteger("anchorDim") == world.provider.dimensionId && tag.getInteger("anchorX") == TARGET_X - 2
                && tag.getInteger("anchorY") == TARGET_Y
                && tag.getInteger("anchorZ") == TARGET_Z + 8,
            "node rebound to placing player's chosen controller");
        int playerId = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(actor.getGameProfile());
        require(
            ((IGridProxyable) node).getProxy()
                .getNode()
                .getPlayerID() == playerId,
            "target native grid node owner is current placer");
        findings.setTag("targetQuantumNode", tag);
    }

    private NBTTagCompound inventory() {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++)
            if (actor.inventory.mainInventory[slot] != null) {
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("slot", slot);
                actor.inventory.mainInventory[slot].writeToNBT(row);
                rows.appendTag(row);
            }
        result.setTag("items", rows);
        return result;
    }

    private NBTTagCompound base(String phase) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("phase", phase);
        tag.setString("session", session);
        tag.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        tag.setString(
            "profile",
            actor.getGameProfile()
                .getName());
        tag.setString(
            "world",
            world.getWorldInfo()
                .getWorldName());
        tag.setInteger("dimension", world.provider.dimensionId);
        tag.setInteger("sourceDimension", world.provider.dimensionId);
        tag.setLong("worldTick", world.getTotalWorldTime());
        tag.setInteger("runtimeSequence", ticks);
        return tag;
    }

    private static void assertAcidProduction(NBTTagCompound nativeReceipt) {
        require(
            nativeReceipt.getBoolean("operational") && nativeReceipt.getBoolean("outputsAreNative"),
            "native operating line receipt");
        require(
            nativeReceipt.getLong("acid") > 0 && nativeReceipt.getLong("sulfurConsumed") > 0
                && nativeReceipt.getLong("oxygenConsumed") > 0
                && nativeReceipt.getLong("waterConsumed") > 0,
            "actual ME sulfur/oxygen/water debit accompanies native acid inventory increase");
        require(
            nativeReceipt.getLong("acid") <= nativeReceipt.getLong("sulfurConsumed") * 1000L
                && nativeReceipt.getLong("acid") <= nativeReceipt.getLong("waterConsumed"),
            "finite native stoichiometric stock bound");
        NBTTagList machines = nativeReceipt.getTagList("machines", 10);
        require(machines.tagCount() == 3, "all three actual chemical reactor observations present");
        for (int stage = 0; stage < machines.tagCount(); stage++) {
            NBTTagCompound machine = machines.getCompoundTagAt(stage);
            require(
                machine.getBoolean("formed") && nativeRecipeCompleted(machine)
                    && machine.getInteger("observedProgress") > 0,
                "each native reactor naturally progressed and completed recipe " + stage);
            require(
                machine.getLong("euInjected") > 0 && machine.getLong("euInjected") <= 2000000L,
                "finite per-reactor native energy budget " + stage);
            require(
                machine.getBoolean("automaticMaintenance") && machine.getInteger("repairStatus") == 6,
                "real automatic maintenance hatch restored all six native repair flags " + stage);
        }
    }

    private static boolean eachNativeRecipeCompleted(NBTTagCompound nativeReceipt) {
        NBTTagList machines = nativeReceipt.getTagList("machines", 10);
        if (machines.tagCount() != 3) return false;
        for (int stage = 0; stage < machines.tagCount(); stage++) {
            if (!nativeRecipeCompleted(machines.getCompoundTagAt(stage))) return false;
        }
        return true;
    }

    private static boolean nativeRecipeCompleted(NBTTagCompound machine) {
        if (machine.getLong("recipesDone") > 0) return true;
        NBTTagList boundaries = machine.getTagList("nativeCompletionBoundaries", 10);
        for (int index = 0; index < boundaries.tagCount(); index++) {
            NBTTagCompound boundary = boundaries.getCompoundTagAt(index);
            NBTTagCompound before = boundary.getCompoundTag("before");
            NBTTagCompound after = boundary.getCompoundTag("after");
            if (before.getInteger("stage") == machine.getInteger("stage")
                && after.getInteger("stage") == machine.getInteger("stage")
                && "ServerTick.END".equals(before.getString("phase"))
                && "ServerTick.END".equals(after.getString("phase"))
                && before.getLong("worldTick") + 1 == after.getLong("worldTick")
                && before.getLong("totalRunTime") + 1 == after.getLong("totalRunTime")
                && before.getBoolean("formed")
                && after.getBoolean("formed")
                && before.getInteger("maxProgress") > 1
                && before.getInteger("progress") == before.getInteger("maxProgress") - 1
                && (after.getInteger("progress") == 0 || after.getInteger("progress") == 1)
                && after.getLong("lastWorkingTick") > before.getLong("lastWorkingTick")
                && after.getLong("lastWorkingTick") == after.getLong("totalRunTime")
                && after.getInteger("efficiency") > 0
                && before.getTagList("pendingOutputs", 10)
                    .tagCount() > 0)
                return true;
        }
        return false;
    }

    private NBTTagCompound nativeMeObservations() throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (int x = bounds[0]; x <= bounds[3]; x++)
            for (int y = bounds[1]; y <= bounds[4]; y++) for (int z = bounds[2]; z <= bounds[5]; z++) {
                TileEntity tile = world.getTileEntity(x, y, z);
                if (!(tile instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity)) continue;
                gregtech.api.interfaces.metatileentity.IMetaTileEntity meta = ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) tile)
                    .getMetaTileEntity();
                if (meta == null || !gtMeInput(
                    meta.getClass()
                        .getName()))
                    continue;
                NBTTagCompound row = new NBTTagCompound();
                row.setIntArray("position", new int[] { x, y, z });
                row.setString(
                    "nativeClass",
                    meta.getClass()
                        .getName());
                NBTTagCompound rawNative = new NBTTagCompound();
                tile.writeToNBT(rawNative);
                row.setTag("nativeTile", rawNative);
                rows.appendTag(row);
            }
        result.setTag("tiles", rows);
        result.setString("sha256", sha256(CompressedStreamTools.compress(result)));
        result.setLong("worldTick", world.getTotalWorldTime());
        return result;
    }

    private static boolean gtMeInput(String type) {
        return type.equals("gregtech.common.tileentities.machines.MTEHatchInputME")
            || type.equals("gregtech.common.tileentities.machines.MTEHatchInputBusME");
    }

    private static void assertPortableMeConfiguration(HologramPresets.Preset preset) throws Exception {
        int observed = 0;
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null || !tag.hasKey("mID", 99)) continue;
            int id = tag.getInteger("mID");
            if (id < 0 || id >= GregTechAPI.METATILEENTITIES.length
                || GregTechAPI.METATILEENTITIES[id] == null
                || !gtMeInput(
                    GregTechAPI.METATILEENTITIES[id].getClass()
                        .getName()))
                continue;
            observed++;
            require(tag.getInteger("version") == 1, "native ME fixture keeps audited version 1");
            NBTTagList slots = tag.getTagList("slots", 10);
            require(slots.tagCount() > 0, "native ME filtering configuration retained");
            for (int i = 0; i < slots.tagCount(); i++) {
                NBTTagCompound row = slots.getCompoundTagAt(i);
                require(
                    row.hasKey("config", 10) && !row.getCompoundTag("config")
                        .hasNoTags(),
                    "native ME filter sample preserved");
                require(
                    !row.hasKey("extracted") && !row.hasKey("extractedAmount"),
                    "native simulated stock mirrors omitted from portable saved blueprint");
            }
        }
        require(observed == 4, "three fluid ME inputs and one item ME input audited in actual saved file");
    }

    private void moveNear(int x, int y, int z) {
        actor.playerNetServerHandler.setPlayerLocation(x + .5, y + 2, z + .5, 180, 10);
        actor.inventoryContainer.detectAndSendChanges();
    }

    private void acquireChunks(int x, int z) {
        HologramEnergyNetworkSmoke.ensureFixtureChunkCallback();
        ticket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        require(ticket != null, "native sulfur fixture chunk ticket");
        ticket.getModData()
            .setBoolean("gtmbSulfurProbe", true);
        for (int cx = x >> 4; cx <= (x + 63) >> 4; cx++) for (int cz = z >> 4; cz <= (z + 47) >> 4; cz++) {
            world.theChunkProviderServer.loadChunk(cx, cz);
            ChunkCoordIntPair position = new ChunkCoordIntPair(cx, cz);
            chunks.add(position);
            ForgeChunkManager.forceChunk(ticket, position);
        }
    }

    private void retainChunks() {
        for (ChunkCoordIntPair coordinate : chunks) require(
            world.theChunkProviderServer.chunkExists(coordinate.chunkXPos, coordinate.chunkZPos)
                && world.getPersistentChunks()
                    .containsEntry(coordinate, ticket),
            "actual native source/target chunks stay ticking");
    }

    private Object liveSession() throws Exception {
        Field field = HologramService.class.getDeclaredField("SESSIONS");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).get(actor.getUniqueID());
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object call(Object object, String name, Object... arguments) throws Exception {
        return invoke(object.getClass(), object, name, arguments);
    }

    private static Object nativeStatic(String name, Object... arguments) throws Exception {
        return invoke(
            Class.forName("com.miaokatze.gtmb.hologram.HologramSulfurLineSmokeNative"),
            null,
            name,
            arguments);
    }

    private static Object decorStatic(String name, Object... arguments) throws Exception {
        return invoke(Class.forName("com.miaokatze.gtmb.hologram.HologramSulfurLineSmokeDecor"), null, name, arguments);
    }

    private static Object bankStatic(String name, Object... arguments) throws Exception {
        return invoke(Class.forName("com.miaokatze.gtmb.hologram.HologramSulfurLineSmokeBank"), null, name, arguments);
    }

    private static Object invoke(Class<?> type, Object receiver, String name, Object... arguments) throws Exception {
        for (Method method : type.getDeclaredMethods()) if (method.getName()
            .equals(name) && method.getParameterTypes().length == arguments.length) {
                Class<?>[] types = method.getParameterTypes();
                boolean matches = true;
                for (int i = 0; i < types.length; i++)
                    if (arguments[i] != null && !box(types[i]).isInstance(arguments[i])) matches = false;
                if (!matches) continue;
                method.setAccessible(true);
                return method.invoke(receiver, arguments);
            }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + arguments.length);
    }

    private static Class<?> box(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        return type;
    }

    private static int[] union(int[] a, int[] b) {
        return new int[] { Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[3], b[3]),
            Math.max(a[4], b[4]), Math.max(a[5], b[5]) };
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) text.append(String.format("%02x", value & 255));
        return text.toString();
    }

    private static HologramBlueprintArtifact.Artifact exportedArtifact(NBTTagCompound receipt, String jsonHash)
        throws Exception {
        byte[] json = receipt.getByteArray("exportedJson"), png = receipt.getByteArray("exportedPng");
        require(
            receipt.getInteger("artifactVersion") == HologramBlueprintArtifact.VERSION && json.length > 0
                && png.length > 0
                && sha256(json).equals(receipt.getString(jsonHash))
                && sha256(png).equals(receipt.getString("exportedPngSha256"))
                && receipt.getInteger("previewWidth") == HologramBlueprintArtifact.WIDTH
                && receipt.getInteger("previewHeight") == HologramBlueprintArtifact.HEIGHT,
            "exact atomic source JSON/PNG pair, artifact version, dimensions and independent digests preserved");
        HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact
            .decode(HologramBlueprintArtifact.encode(json, png));
        require(
            Arrays.equals(json, artifact.json) && Arrays.equals(png, artifact.png),
            "source preview already binds the exact exported JSON without digest rebinding or pixel replacement");
        return artifact;
    }

    private static void write(String name, NBTTagCompound value) throws Exception {
        HologramV011Evidence.write(name, value);
    }

    private static NBTTagCompound read(String name) throws Exception {
        return HologramV011Evidence.read(name);
    }

    private void failIfClientMarker(String name) throws Exception {
        require(
            !Files.isRegularFile(
                HologramV011Evidence.directory()
                    .resolve(name)),
            "client failed: " + name);
    }

    private void finish(MinecraftServer server, String status) throws Exception {
        result = status;
        findings.setString("result", status);
        findings.setBoolean("passed", true);
        findings.setInteger("serverTicks", ticks);
        findings.setBoolean("actualServerSessions", true);
        write("sulfur-server-final.nbt", findings);
        HologramV011Evidence.marker("sulfur-server-pass.txt", status);
        if (ticket != null) ForgeChunkManager.releaseTicket(ticket);
        finished = true;
        System.out.println(PREFIX + status + " receipts=" + HologramV011Evidence.directory());
        server.initiateShutdown();
    }

    private void fail(Throwable failure) {
        result = "FAIL " + failure;
        finished = true;
        failure.printStackTrace();
        try {
            findings.setString("result", result);
            findings.setInteger("stage", stage);
            write("sulfur-server-final.nbt", findings);
            HologramV011Evidence.marker("sulfur-server-fail.txt", result);
        } catch (Exception evidenceFailure) {
            failure.addSuppressed(evidenceFailure);
        }
        if (ticket != null) ForgeChunkManager.releaseTicket(ticket);
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) server.initiateShutdown();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("sulfur fixture: " + message);
    }
}
