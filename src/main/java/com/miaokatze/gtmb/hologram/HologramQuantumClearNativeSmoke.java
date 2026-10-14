package com.miaokatze.gtmb.hologram;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;

import com.mojang.authlib.GameProfile;

import appeng.me.helpers.IGridProxyable;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Isolated native Service evidence; it never spends the sulfur demo's player or ME inventory. */
public final class HologramQuantumClearNativeSmoke {

    private HologramQuantumClearNativeSmoke() {}

    public static NBTTagCompound run(WorldServer world) throws Exception {
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-sulfur-"),
            "isolated sulfur world required");
        Block node = null;
        for (Object value : Block.blockRegistry)
            if (value instanceof Block && HologramNetworkBindings.node((Block) value)) node = (Block) value;
        require(node != null, "exact installed native GTSWN node");
        EntityPlayerMP actor = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("39efead7-a1d5-4ef2-af4b-94c71e338794"), "GTMBQuantumClear"));
        actor.inventory.mainInventory = new ItemStack[36];
        actor.inventory.currentItem = 0;
        actor.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
        actor.capabilities.allowEdit = true;
        actor.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        int x = 128, y = 85, z = 48;
        for (int position = x; position <= x + 8; position++) {
            world.getChunkFromBlockCoords(position, z);
            require(world.isAirBlock(position, y, z), "fresh auxiliary quantum coordinate");
        }
        require(world.setBlock(x + 8, y, z, node, 0, 3), "actual auxiliary source node installed");
        TileEntity actualSource = world.getTileEntity(x + 8, y, z);
        require(
            actualSource != null && HologramNetworkBindings.NODE_TILE.equals(
                actualSource.getClass()
                    .getName()),
            "exact native node source tile");
        ItemStack component = appeng.api.AEApi.instance()
            .definitions()
            .parts()
            .cableAnchor()
            .maybeStack(1)
            .get();
        require(
            component != null && actualSource instanceof appeng.api.parts.IPartHost,
            "real enabled AE anchor component and native part host");
        appeng.api.parts.IPartHost sourceHost = (appeng.api.parts.IPartHost) actualSource;
        require(
            sourceHost.addPart(component.copy(), net.minecraftforge.common.util.ForgeDirection.EAST, actor) != null
                && sourceHost.getPart(net.minecraftforge.common.util.ForgeDirection.EAST) != null,
            "actual native source node contains one real non-cable component");
        NBTTagCompound nativeSourceBefore = HologramNativeCapture.capture(actualSource);
        NBTTagCompound captured = (NBTTagCompound) nativeSourceBefore.copy();
        captured.setString(HologramNetworkBindings.GROUP, "q1");
        captured.setString(HologramNetworkBindings.ROLE, "node");
        ItemStack shell = HologramAeBlueprint.blockSample(node, 0);
        require(shell != null, "native node payment item");
        HologramPresets.Preset source = preset(node, shell, captured);
        NBTTagCompound sourceBefore = (NBTTagCompound) captured.copy();
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 1);
        policy.quantum = "clear";
        policy.autoHints = "none";
        policy.confirmedStages = new ArrayList<>(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset processed = HologramBlueprintProcessing
            .process(source, policy, world.provider.dimensionId, x, y, z);
        require(HologramBlueprintProcessing.quantumCleared(processed), "complete six-stage explicit clear policy");
        com.google.gson.Gson gson = new com.google.gson.Gson();
        com.google.gson.JsonObject share = new com.google.gson.JsonObject();
        share.addProperty("schema", 2);
        HologramBlueprintVersions.stamp(processed);
        share.add("blueprint", gson.toJsonTree(processed));
        byte[] serialized = HologramBlueprintSharing.encodeBounded(gson, share, HologramBlueprintSharing.MAX_BYTES);
        HologramPresets.Preset reloaded = HologramBlueprintSharing
            .decode(new String(serialized, java.nio.charset.StandardCharsets.UTF_8));
        require(
            HologramBlueprintProcessing.quantumCleared(reloaded),
            "serialized policy reload preserves clear decision");
        NBTTagCompound cleared = HologramBlueprintNbt.decode(reloaded.cells.get(0).tileNbt);
        require(
            !cleared.hasKey(HologramNetworkBindings.GROUP) && !cleared.hasKey("anchorDim"),
            "processed node contains no external group or anchor");

        NBTTagCompound receipt = new NBTTagCompound();
        receipt
            .setString("fixtureKind", "native Service only; independent fake actor; no GUI or main-line stock claim");
        receipt.setString(
            "world",
            world.getWorldInfo()
                .getWorldName());
        receipt.setInteger("dimension", world.provider.dimensionId);
        receipt.setString(
            "fixtureActor",
            actor.getUniqueID()
                .toString());
        receipt.setBoolean("actualNativeWorld", true);
        receipt.setBoolean("actualService", true);
        receipt.setBoolean("actualGui", false);
        receipt.setBoolean("fakeFixtureActor", true);
        receipt.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        receipt.setIntArray("sourcePosition", new int[] { x + 8, y, z });
        receipt.setIntArray("targetPosition", new int[] { x, y, z });
        receipt.setIntArray("conflictPosition", new int[] { x + 6, y, z });
        receipt.setTag("sourceCapturedTile", captured.copy());
        receipt.setTag("nativeSourceBefore", nativeSourceBefore.copy());
        receipt.setTag("processedTile", cleared.copy());
        receipt.setString("processingNbt", reloaded.processingNbt);
        receipt.setInteger("serializedBlueprintBytes", serialized.length);
        receipt.setByteArray("serializedBlueprintJson", serialized);
        StringBuilder serializedHash = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256")
            .digest(serialized)) serializedHash.append(String.format("%02x", value & 255));
        receipt.setString("serializedBlueprintSha256", serializedHash.toString());
        receipt.setBoolean("completeClearPolicyReloaded", true);
        Object session = session(actor, reloaded, node, shell, x, y, z);
        actor.inventory.mainInventory[1] = shell.copy();
        actor.inventory.mainInventory[2] = component.copy();
        receipt.setTag("nodePaymentBefore", actor.inventory.mainInventory[1].writeToNBT(new NBTTagCompound()));
        receipt.setTag("componentPaymentBefore", actor.inventory.mainInventory[2].writeToNBT(new NBTTagCompound()));
        invoke("refreshPlan", session);
        HologramCapture.Cell cell = ((HologramCapture) field(session, "capture")).cells.get(0);
        receipt.setString("quoteCellStatus", cell.status);
        receipt.setString("quoteError", cell.resourceError);
        if (!"pending".equals(cell.status) || !cell.resourceError.isEmpty())
            quoteFailure(session, actor, cell, receipt);
        require(
            "pending".equals(cell.status) && cell.resourceError.isEmpty(),
            "cleared node has a payable native quote");
        Veto veto = new Veto(x, y, z, actor);
        MinecraftForge.EVENT_BUS.register(veto);
        try {
            invoke("operate", session, cell, 0);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
        }
        require(veto.events == 1 && veto.unbound && veto.owner, "place event sees a complete unbound actor-owned node");
        require(
            world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null
                && ItemStack.areItemStacksEqual(actor.inventory.mainInventory[1], shell),
            "cancelled clear restores the exact node item and original empty world");
        require(
            ItemStack.areItemStacksEqual(actor.inventory.mainInventory[2], component),
            "cancelled clear restores the exact retained native component item");
        receipt.setTag("cancelledTargetTile", veto.snapshot);
        receipt.setTag("nodePaymentAfterCancelled", actor.inventory.mainInventory[1].writeToNBT(new NBTTagCompound()));
        receipt.setTag(
            "componentPaymentAfterCancelled",
            actor.inventory.mainInventory[2].writeToNBT(new NBTTagCompound()));
        receipt.setBoolean("cancelledExactPaymentRefunded", true);
        invoke("refreshPlan", session);
        require((Boolean) invoke("operate", session, cell, 0), "Service installs the explicitly cleared node");
        TileEntity installed = world.getTileEntity(x, y, z);
        require(unbound(installed) && owner(installed, actor), "paid native node is truly unbound and actor-owned");
        require(
            actor.inventory.mainInventory[1] == null && actor.inventory.mainInventory[2] == null,
            "one real node and one real retained component item each consumed exactly once");
        require(
            ((appeng.api.parts.IPartHost) installed).getPart(net.minecraftforge.common.util.ForgeDirection.EAST)
                != null,
            "network clearing retains the fully paid native component container");
        receipt.setBoolean("nodePaymentConsumedOnce", true);
        receipt.setBoolean("componentPaymentConsumedOnce", true);
        receipt.setBoolean("paidNativeComponentRetained", true);
        NBTTagCompound installedTag = HologramNativeCapture.capture(installed);
        for (String key : new String[] { "anchorDim", "anchorX", "anchorY", "anchorZ" })
            require(!installedTag.hasKey(key), "native writer omits every unbound anchor coordinate");
        receipt.setTag("installedTargetTile", installedTag);
        receipt.setBoolean("paidServiceClearPlaced", true);
        receipt.setBoolean("nativeUnboundSentinel", true);
        receipt.setInteger("nativeAnchorDim", (Integer) call(installed, "getAnchorDim"));
        receipt.setInteger(
            "nativeControllerOwner",
            appeng.core.worlddata.WorldData.instance()
                .playerData()
                .getPlayerID(actor.getGameProfile()));
        receipt.setBoolean("nativeOwnerPending", owner(installed, actor));
        receipt.setString(
            "nativeOwnerMode",
            "native unbound node keeps AENetworkProxy.owner pending until an authorized anchor");
        require(
            ((IGridProxyable) installed).getProxy()
                .getNode() == null,
            "unbound node never initializes a live AE grid or external bridge");
        receipt.setBoolean("nativeGridCreated", false);

        try {
            HologramNativePlacement.install(world, x + 2, y, z, node, 0, cleared, actor);
            throw new AssertionError("default installation must reject an absent group");
        } catch (java.io.IOException expected) {
            receipt.setString("defaultMissingGroupError", expected.getMessage());
        }
        require(
            world.isAirBlock(x + 2, y, z) && world.getTileEntity(x + 2, y, z) == null,
            "default overload never installs an unauthorized empty descriptor");
        policy.quantum = "rebind";
        HologramPresets.Preset rebinding = HologramBlueprintProcessing
            .process(source, policy, world.provider.dimensionId, x, y, z);
        require(
            !HologramBlueprintProcessing.quantumCleared(rebinding),
            "complete rebind policy grants no clear permission");
        Object rebindSession = session(actor, rebinding, node, shell, x + 4, y, z);
        actor.inventory.mainInventory[1] = shell.copy();
        actor.inventory.mainInventory[2] = component.copy();
        receipt.setTag("rebindPaymentBefore", actor.inventory.mainInventory[1].writeToNBT(new NBTTagCompound()));
        invoke("refreshPlan", rebindSession);
        HologramCapture.Cell rebindCell = ((HologramCapture) field(rebindSession, "capture")).cells.get(0);
        invoke("operate", rebindSession, rebindCell, 0);
        require(
            world.isAirBlock(x + 4, y, z) && world.getTileEntity(x + 4, y, z) == null
                && ItemStack.areItemStacksEqual(actor.inventory.mainInventory[1], shell),
            "rebind without a real authorized terminal preserves exact payment and world");
        require(
            ItemStack.areItemStacksEqual(actor.inventory.mainInventory[2], component),
            "rebind denial preserves the exact native component payment");
        receipt.setString("rebindWithoutTerminalStatus", (String) field(rebindSession, "status"));
        receipt.setTag("rebindPaymentAfter", actor.inventory.mainInventory[1].writeToNBT(new NBTTagCompound()));
        receipt.setBoolean("rebindWithoutTerminalRejected", true);
        require(world.setBlock(x + 6, y, z, node, 0, 3), "existing occupied native node installed independently");
        TileEntity bound = world.getTileEntity(x + 6, y, z);
        bound.getClass()
            .getMethod("setAnchor", int.class, int.class, int.class, int.class)
            .invoke(bound, world.provider.dimensionId, x + 8, y, z);
        require(
            !HologramNativePlacement.hasUnboundNativeNode(bound),
            "independent existing target has a native anchor");
        NBTTagCompound boundBefore = HologramNativeCapture.capture(bound);
        Object occupied = session(actor, reloaded, node, shell, x + 6, y, z);
        actor.inventory.mainInventory[1] = shell.copy();
        actor.inventory.mainInventory[2] = component.copy();
        receipt.setTag("conflictPaymentBefore", actor.inventory.mainInventory[1].writeToNBT(new NBTTagCompound()));
        invoke("refreshPlan", occupied);
        HologramCapture.Cell occupiedCell = ((HologramCapture) field(occupied, "capture")).cells.get(0);
        require(
            "blocked".equals(occupiedCell.status),
            "clear policy cannot misclassify an existing bound node as present");
        invoke("operate", occupied, occupiedCell, 0);
        require(
            world.getTileEntity(x + 6, y, z) == bound && boundBefore.equals(HologramNativeCapture.capture(bound))
                && ItemStack.areItemStacksEqual(actor.inventory.mainInventory[1], shell),
            "clear conflict preserves every existing anchor field and exact physical payment");
        require(
            ItemStack.areItemStacksEqual(actor.inventory.mainInventory[2], component),
            "clear conflict cannot consume the retained native component item");
        receipt.setTag("existingBoundBefore", boundBefore);
        receipt.setTag("existingBoundAfter", HologramNativeCapture.capture(bound));
        receipt.setString("existingBoundCellStatus", occupiedCell.status);
        receipt.setBoolean("existingBoundConflictPreserved", true);
        receipt.setTag("conflictPaymentAfter", actor.inventory.mainInventory[1].writeToNBT(new NBTTagCompound()));
        require(sourceBefore.equals(captured), "author source payload remains unchanged");
        NBTTagCompound nativeSourceAfter = HologramNativeCapture.capture(actualSource);
        require(nativeSourceBefore.equals(nativeSourceAfter), "actual source native tile remains byte-exact unchanged");
        receipt.setTag("nativeSourceAfter", nativeSourceAfter);
        receipt.setBoolean("sourceUnchanged", true);
        receipt.setBoolean("passed", true);
        System.out.println(
            "[GTMB-QUANTUM-CLEAR-NATIVE] FINAL PASS clear=paid-unbound-owned refund=exact default=denied rebind=terminal-required");
        return receipt;
    }

    /** Failure-only evidence. Replays only detached quote readers; the original guard still decides the result. */
    private static void quoteFailure(Object session, EntityPlayerMP actor, HologramCapture.Cell cell,
        NBTTagCompound receipt) {
        NBTTagCompound diagnostic = (NBTTagCompound) receipt.copy();
        diagnostic.setBoolean("passed", false);
        diagnostic.setString("failureGuard", "cleared node has a payable native quote");
        try {
            NBTTagCompound snapshot = new NBTTagCompound();
            for (String name : new String[] { "status", "mode", "scope", "dimension", "planRevision", "planMissing",
                "planRecoveryFits", "blueprintResourceMissing", "allocationsFrozen", "processingReviewed",
                "processingWorkflowStarted", "blueprintSource", "placementNbtCustomized", "placementRetainCovers",
                "placementNbtKeys", "planStatus", "planTargets", "pendingAssetBudget", "cellAssetBudgets" })
                snapshot.setString(name, String.valueOf(field(session, name)));
            snapshot.setString("resourceFingerprint", cell.resourceFingerprint);
            snapshot.setString("resourceTile", cell.resourceTile);
            snapshot.setString("resourceItem", cell.resourceItem);
            snapshot.setString("resourceError", cell.resourceError);
            snapshot.setString("cellStatus", cell.status);
            snapshot.setString("cellRole", cell.role);
            snapshot.setString("cellSourceWarning", cell.sourceWarning);
            snapshot.setString("cellTileNbt", cell.tileNbt);
            snapshot.setString("cellItemNbt", cell.itemNbt);
            snapshot.setString(
                "blockClass",
                cell.block.getClass()
                    .getName());
            snapshot.setString("blockRegistry", Block.blockRegistry.getNameForObject(cell.block));
            snapshot.setInteger("metadata", cell.meta);
            snapshot.setBoolean("worldBlockExists", actor.worldObj.blockExists(cell.x, cell.y, cell.z));
            snapshot.setBoolean("worldAir", actor.worldObj.isAirBlock(cell.x, cell.y, cell.z));
            snapshot.setBoolean("canMineBlock", actor.worldObj.canMineBlock(actor, cell.x, cell.y, cell.z));
            snapshot.setBoolean("canPlayerEdit", actor.canPlayerEdit(cell.x, cell.y, cell.z, 1, actor.getHeldItem()));
            snapshot.setBoolean("creative", actor.capabilities.isCreativeMode);
            HologramMaterials.Settings settings = (HologramMaterials.Settings) field(session, "materialSettings");
            snapshot.setBoolean("materialMain", settings.main);
            snapshot.setBoolean("materialContainers", settings.containers);
            snapshot.setBoolean("materialMe", settings.me);
            snapshot.setInteger("materialPriority", settings.priority);
            HologramMaterials.Context context = new HologramMaterials.Context(actor, settings);
            if (cell.resourcePlan != null) snapshot.setTag("cachedResourceEntries", cell.resourcePlan.entries(context));
            NBTTagList inventory = new NBTTagList();
            for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++) {
                ItemStack stack = actor.inventory.mainInventory[slot];
                if (stack == null) continue;
                NBTTagCompound entry = stack.writeToNBT(new NBTTagCompound());
                entry.setInteger("slot", slot);
                entry.setString(
                    "itemClass",
                    stack.getItem()
                        .getClass()
                        .getName());
                entry.setString("itemRegistry", Item.itemRegistry.getNameForObject(stack.getItem()));
                inventory.appendTag(entry);
            }
            snapshot.setTag("inventory", inventory);
            diagnostic.setTag("quoteSnapshot", snapshot);
            try {
                HologramAeBlueprint.Analysis raw = HologramAeBlueprint
                    .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
                snapshot.setBoolean("sourceAnalysisSupported", raw.supported);
                snapshot.setBoolean("sourceAnalysisBlocking", raw.blocking);
                snapshot.setTag("sourceAnalysisPayload", raw.payload());
                snapshot.setTag("sourceAnalysisIssues", issues(raw.issues));
            } catch (Throwable failure) {
                snapshot.setString("sourceAnalysisFailureTrace", trace(failure));
            }
            try {
                ItemStack main = (ItemStack) invoke("targetFor", session, cell, 0);
                if (main != null) snapshot.setTag("resolvedMainMaterial", main.writeToNBT(new NBTTagCompound()));
                HologramEnergyPayload.Prepared cache = (HologramEnergyPayload.Prepared) invoke(
                    "cachePlan",
                    session,
                    cell,
                    0);
                if (cache.tileEmpty != null) snapshot.setTag("placementTile", cache.tileEmpty.copy());
                if (cache.itemEmpty != null) snapshot.setTag("placementItem", cache.itemEmpty.copy());
                HologramAeBlueprint.Analysis analysis = HologramAeBlueprint
                    .analyze(cell.block, cell.meta, cache.tileEmpty);
                snapshot.setBoolean("nativeQuoteSupported", analysis.supported);
                snapshot.setBoolean("nativeQuoteBlocking", analysis.blocking);
                snapshot.setTag("nativeQuotePayload", analysis.payload());
                snapshot.setTag("nativeQuoteMaterials", stacks(analysis.materials));
                snapshot.setTag("nativeQuoteIssues", issues(analysis.issues));
                snapshot.setTag(
                    "nativeShellMaterials",
                    stacks(HologramAeBlueprint.nativeShellMaterials(cell.block, cell.meta, cache.tileEmpty)));
                snapshot.setTag(
                    "completeNativeMaterials",
                    stacks(HologramNativePlacement.materials(cell.block, cell.meta, cache.tileEmpty)));
                snapshot.setTag(
                    "replayedResourceEntries",
                    HologramAeBlueprint.resourcePlan(cell.block, cell.meta, cache.tileEmpty, main)
                        .entries(context));
            } catch (Throwable failure) {
                snapshot.setString("quoteReplayFailureTrace", trace(failure));
                failure.printStackTrace(System.err);
            }
        } catch (Throwable failure) {
            diagnostic.setString("diagnosticExtractionFailureTrace", trace(failure));
            failure.printStackTrace(System.err);
        }
        try {
            Path target = HologramV011Evidence.directory()
                .resolve("quantum-clear-native-quote-failure.nbt");
            try (java.io.OutputStream output = Files
                .newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                CompressedStreamTools.writeCompressed(diagnostic, output);
            }
            System.err.println("[GTMB-QUANTUM-CLEAR-NATIVE] QUOTE FAIL receipt=" + target + " snapshot=" + diagnostic);
        } catch (Throwable failure) {
            System.err.println("[GTMB-QUANTUM-CLEAR-NATIVE] QUOTE DIAGNOSTIC FAIL snapshot=" + diagnostic);
            failure.printStackTrace(System.err);
        }
    }

    private static NBTTagList stacks(List<ItemStack> stacks) {
        NBTTagList result = new NBTTagList();
        for (ItemStack stack : stacks) result.appendTag(stack.writeToNBT(new NBTTagCompound()));
        return result;
    }

    private static NBTTagList issues(List<HologramAeBlueprint.Issue> issues) {
        NBTTagList result = new NBTTagList();
        for (HologramAeBlueprint.Issue issue : issues) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("code", issue.code);
            row.setString("severity", issue.severity);
            row.setString("path", issue.path);
            row.setString("message", issue.message);
            result.appendTag(row);
        }
        return result;
    }

    private static String trace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static HologramPresets.Preset preset(Block block, ItemStack shell, NBTTagCompound payload)
        throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = "quantum_clear_native";
        preset.name = "Quantum clear native";
        preset.controllerType = "area";
        HologramPresets.Cell saved = HologramPresets.cell(shell, 0, 0, 0, -1, false);
        saved.block = Block.blockRegistry.getNameForObject(block);
        saved.metadata = 0;
        saved.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        preset.cells.add(saved);
        preset.palette.add(HologramPresets.cell(shell, 0, 0, 0, -1, false));
        return preset;
    }

    private static Object session(EntityPlayerMP actor, HologramPresets.Preset preset, Block block, ItemStack shell,
        int x, int y, int z) throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramService$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(actor, x, y, z);
        set(session, "virtual", true);
        set(session, "virtualPreset", HologramPresets.copy(preset));
        HologramCapture capture = new HologramCapture(null, actor.getHeldItem());
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, y, z);
        cell.block = block;
        cell.meta = 0;
        cell.tileNbt = preset.cells.get(0).tileNbt;
        cell.blueprintBlock = Block.blockRegistry.getNameForObject(block);
        cell.blueprintItem = Item.itemRegistry.getNameForObject(shell.getItem());
        cell.blueprintDamage = shell.getItemDamage();
        cell.role = "prefab";
        cell.userPosition = true;
        capture.cells.add(cell);
        String position = x + "," + y + "," + z;
        capture.positions.put(position, cell);
        set(session, "capture", capture);
        map(session, "pins").put(0, shell.copy());
        map(session, "coordinatePins").put(position, shell.copy());
        map(session, "autoTargets").put(0, shell.copy());
        map(session, "frozenAutoTargets").put(position, shell.copy());
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = false;
        settings.containers = false;
        set(session, "materialSettings", settings);
        actor.setPosition(x + .5, y, z + .5);
        return session;
    }

    private static boolean unbound(TileEntity tile) throws Exception {
        return HologramNativePlacement.hasUnboundNativeNode(tile)
            && "NO_ANCHOR".equals(call(tile, "getOfflineReason").toString());
    }

    private static boolean owner(TileEntity tile, EntityPlayerMP actor) throws Exception {
        if (!(tile instanceof IGridProxyable)) return false;
        appeng.me.helpers.AENetworkProxy proxy = ((IGridProxyable) tile).getProxy();
        appeng.api.networking.IGridNode grid = proxy.getNode();
        if (grid != null) return grid.getPlayerID() == appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(actor.getGameProfile());
        // Native unbound nodes deliberately return before proxy.onReady. AE stores setOwner in this exact field
        // until a future authorized anchor creates a grid node; inspecting it must not call onReady itself.
        Field pendingOwner = appeng.me.helpers.AENetworkProxy.class.getDeclaredField("owner");
        require(
            net.minecraft.entity.player.EntityPlayer.class == pendingOwner.getType(),
            "exact AE pending-owner schema");
        pendingOwner.setAccessible(true);
        Object value = pendingOwner.get(proxy);
        return value instanceof net.minecraft.entity.player.EntityPlayer && actor.getUniqueID()
            .equals(((net.minecraft.entity.player.EntityPlayer) value).getUniqueID());
    }

    public static final class Veto {

        private final int x, y, z;
        private final EntityPlayerMP actor;
        int events;
        boolean unbound, owner;
        NBTTagCompound snapshot = new NBTTagCompound();

        Veto(int x, int y, int z, EntityPlayerMP actor) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.actor = actor;
        }

        @SubscribeEvent
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.x != x || event.y != y || event.z != z) return;
            events++;
            try {
                TileEntity tile = event.world.getTileEntity(x, y, z);
                unbound = HologramQuantumClearNativeSmoke.unbound(tile);
                owner = HologramQuantumClearNativeSmoke.owner(tile, actor);
                snapshot = HologramNativeCapture.capture(tile);
            } catch (Exception failure) {
                throw new IllegalStateException("quantum clear event witness failed", failure);
            } finally {
                event.setCanceled(true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, ItemStack> map(Object owner, String name) throws Exception {
        return (Map<Object, ItemStack>) field(owner, name);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Object call(Object owner, String name) throws Exception {
        return owner.getClass()
            .getMethod(name)
            .invoke(owner);
    }

    private static Object invoke(String name, Object session, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length + 1];
        Object[] values = new Object[args.length + 1];
        types[0] = session.getClass();
        values[0] = session;
        for (int i = 0; i < args.length; i++) {
            types[i + 1] = args[i] instanceof Integer ? int.class : args[i].getClass();
            values[i + 1] = args[i];
        }
        Method method = HologramService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, values);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("quantum clear native fixture: " + message);
    }
}
