package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.google.gson.GsonBuilder;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Two native world regressions through production author processing and Service placement; no GUI claim. */
public final class HologramSulfurLineSmokeOldIssues {

    private static final int SOURCE_X = 160, TARGET_X = 164, Y = 85, REACTOR_Z = 48, LAMP_Z = 52;
    private static final String REACTOR_CLASS = "gregtech.common.tileentities.machines.multi.MTELargeNaquadahReactor";
    private static final UUID ACTOR_ID = UUID.fromString("8ed16d86-63fd-49ba-95e8-6cceddf6c31e");

    private HologramSulfurLineSmokeOldIssues() {}

    public static NBTTagCompound run(WorldServer world) throws Exception {
        require(
            world != null && !world.isRemote
                && world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-sulfur-"),
            "isolated native sulfur source world required");
        int realPlayersBefore = world.playerEntities.size();
        HologramEnergyNetworkSmoke.ensureFixtureChunkCallback();
        ForgeChunkManager.Ticket ticket = ForgeChunkManager
            .requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        require(ticket != null, "bounded native old-issues chunk ticket");
        ticket.getModData()
            .setBoolean("gtmbSulfurOldIssues", true);
        try {
            // LNR's native declaration reaches three blocks west of its controller, into chunk 9.
            for (int chunkX = 9; chunkX <= 10; chunkX++) {
                ChunkCoordIntPair coordinate = new ChunkCoordIntPair(chunkX, 3);
                ForgeChunkManager.forceChunk(ticket, coordinate);
                Chunk loaded = world.theChunkProviderServer.loadChunk(chunkX, 3);
                require(
                    loaded != null && !(loaded instanceof EmptyChunk) && loaded.isChunkLoaded,
                    "actual bounded native chunk is loaded");
            }
            FakePlayer actor = FakePlayerFactory.get(world, new GameProfile(ACTOR_ID, "GTMB-OldIssues"));
            actor.inventory.mainInventory = new ItemStack[36];
            actor.inventory.currentItem = 0;
            actor.capabilities.isCreativeMode = false;
            actor.capabilities.allowEdit = true;
            Item projector = (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector");
            require(projector != null, "actual registered projector item");
            actor.inventory.mainInventory[0] = new ItemStack(projector, 1);
            actor.setPosition(SOURCE_X + .5, Y, REACTOR_Z + .5);
            require(
                actor.worldObj == world && actor.getUniqueID()
                    .equals(ACTOR_ID),
                "isolated fake fixture actor");
            fresh(world, SOURCE_X, Y, REACTOR_Z);
            fresh(world, TARGET_X, Y, REACTOR_Z);
            fresh(world, SOURCE_X, Y, LAMP_Z);
            fresh(world, SOURCE_X, Y - 1, LAMP_Z);
            fresh(world, TARGET_X, Y, LAMP_Z);

            int reactorId = reactorId();
            ItemStack reactorItem = new ItemStack(GregTechAPI.sBlockMachines, 1, reactorId);
            require(reactorItem.getItem() instanceof ItemMachines, "registered LNR ItemMachines item");
            require(
                ((ItemMachines) reactorItem.getItem()).placeBlockAt(
                    reactorItem,
                    actor,
                    world,
                    SOURCE_X,
                    Y,
                    REACTOR_Z,
                    ForgeDirection.NORTH.ordinal(),
                    .5F,
                    .5F,
                    .5F,
                    0),
                "official ItemMachines creates actual LNR source");
            IGregTechTileEntity sourceBase = gt(world.getTileEntity(SOURCE_X, Y, REACTOR_Z));
            sourceBase.setFrontFacing(ForgeDirection.NORTH);
            sourceBase.disableWorking();
            require(
                sourceBase.getMetaTileID() == reactorId && sourceBase.getMetaTileEntity() != null
                    && sourceBase.getMetaTileEntity()
                        .getClass()
                        .getName()
                        .equals(REACTOR_CLASS),
                "actual source is the exact registered large naquadah reactor");
            NBTTagCompound reactor = runCase(world, actor, "largeNaquadahReactor", REACTOR_Z, reactorId);

            require(
                world.setBlock(SOURCE_X, Y - 1, LAMP_Z, Blocks.redstone_block, 0, 3),
                "native source lamp power block");
            require(world.setBlock(SOURCE_X, Y, LAMP_Z, Blocks.redstone_lamp, 0, 3), "native source lamp item block");
            require(
                world.isBlockIndirectlyGettingPowered(SOURCE_X, Y, LAMP_Z)
                    && world.getBlock(SOURCE_X, Y, LAMP_Z) == Blocks.lit_redstone_lamp,
                "native redstone power changes source lamp to its real lit world block");
            NBTTagCompound lamp = runCase(world, actor, "redstoneLamp", LAMP_Z, -1);
            lamp.setIntArray("sourcePowerPosition", new int[] { SOURCE_X, Y - 1, LAMP_Z });
            lamp.setInteger("sourcePowerNativeBlockId", Block.getIdFromBlock(world.getBlock(SOURCE_X, Y - 1, LAMP_Z)));
            lamp.setString("sourcePowerBlockRegistryName", registry(world.getBlock(SOURCE_X, Y - 1, LAMP_Z)));
            require(
                world.getBlock(SOURCE_X, Y - 1, LAMP_Z) == Blocks.redstone_block,
                "native source power block preserved");
            require(world.playerEntities.size() == realPlayersBefore, "fixture does not add a main server player");

            NBTTagCompound summary = flags();
            summary.setString(
                "world",
                world.getWorldInfo()
                    .getWorldName());
            summary.setInteger("dimension", world.provider.dimensionId);
            summary.setString("fixtureActor", ACTOR_ID.toString());
            summary.setIntArray("forcedChunkBounds", new int[] { 9, 3, 10, 3 });
            summary.setString(
                "scope",
                "native Service branch; real area capture and six-stage server processing; no GUI or network-player claim");
            summary.setBoolean("mainPlayersUnchanged", true);
            summary.setTag("largeNaquadahReactor", reactor);
            summary.setTag("redstoneLamp", lamp);
            NBTTagList cases = new NBTTagList();
            cases.appendTag(reactor.copy());
            cases.appendTag(lamp.copy());
            summary.setTag("cases", cases);
            return summary;
        } finally {
            ForgeChunkManager.releaseTicket(ticket);
        }
    }

    private static NBTTagCompound runCase(WorldServer world, EntityPlayerMP actor, String kind, int z,
        int expectedReactorId) throws Exception {
        Block sourceBlock = world.getBlock(SOURCE_X, Y, z);
        int sourceMetadata = world.getBlockMetadata(SOURCE_X, Y, z);
        TileEntity sourceTile = world.getTileEntity(SOURCE_X, Y, z);
        NBTTagCompound before = sourceState(world, SOURCE_X, Y, z);
        NBTTagCompound sourceCaptured = sourceTile == null ? null : HologramNativeCapture.capture(sourceTile);
        HologramArea area = new HologramArea();
        area.point(1, SOURCE_X, Y, z);
        area.point(2, SOURCE_X, Y, z);
        area.begin(world);
        for (int attempt = 0; attempt < 256 && !area.imported; attempt++) area.tick(actor, actor.getHeldItem());
        require(
            area.imported && area.blueprint.cells.size() == 1,
            "actual one-cell area capture completes for " + kind);
        HologramPresets.Cell rawCell = area.blueprint.cells.get(0);
        ItemStack capturedItem = rawCell.stack();
        require(
            !rawCell.missing() && capturedItem != null && rawCell.block() == sourceBlock,
            "actual area retains native source block and payable item");
        if (expectedReactorId >= 0) {
            require(
                rawCell.controller && capturedItem.getItem() instanceof ItemMachines
                    && capturedItem.getItemDamage() == expectedReactorId,
                "actual area preserves full LNR controller ID");
            require(area.structures.size() == 1, "actual area discovers native LNR declaration");
            // This is the same publication of actual discovered descriptors used by Service's area completion.
            for (HologramArea.Structure nativeStructure : area.structures.values()) {
                HologramPresets.Structure declared = new HologramPresets.Structure();
                declared.id = nativeStructure.id;
                declared.name = nativeStructure.machine.getLocalName();
                declared.x = nativeStructure.x - SOURCE_X;
                declared.y = nativeStructure.y - Y;
                declared.z = nativeStructure.z - z;
                declared.controllerId = nativeStructure.machine.getBaseMetaTileEntity()
                    .getMetaTileID();
                declared.sourceClass = nativeStructure.machine.getClass()
                    .getName();
                declared.sourceMod = "gregtech";
                declared.enabled = nativeStructure.enabled;
                require(
                    declared.controllerId == expectedReactorId && REACTOR_CLASS.equals(declared.sourceClass),
                    "declared controller descriptor comes from the actual native LNR");
                area.blueprint.structures.add(declared);
            }
            rawCell.structureIds = area.memberships(SOURCE_X, Y, z);
            require(rawCell.structureIds.size() == 1, "native LNR area membership preserved");
            rawCell.structureId = rawCell.structureIds.get(0);
        } else require(
            capturedItem.getItem() == Item.getItemFromBlock(Blocks.redstone_lamp),
            "lit world lamp captures the real unlit lamp item identity");

        Object author = fixtureSession(actor, SOURCE_X, Y, z, area.blueprint);
        require(!(Boolean) field(author, "processingReviewed"), "fresh author fixture begins unreviewed");
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing
            .defaults("self", Math.max(1, HologramBlueprintProcessing.minimumLevel(area.blueprint)));
        NBTTagCompound incomplete = processingAction(author, policy);
        require(
            (Boolean) call("processingAction", author, incomplete, "processBlueprint")
                && !(Boolean) field(author, "processingReviewed")
                && !(Boolean) field(author, "processingPresetOperationSuccess"),
            "real processing function rejects missing ordered six-stage confirmations");
        for (String stage : HologramBlueprintProcessing.STAGES) {
            policy.confirmedStages.add(stage);
            HologramBlueprintProcessing.Policy.read(policy.write());
        }
        NBTTagCompound complete = processingAction(author, policy);
        require(
            (Boolean) call("processingAction", author, complete, "processBlueprint")
                && (Boolean) field(author, "processingReviewed")
                && (Boolean) field(author, "processingPresetOperationSuccess"),
            "real Service processing function acknowledges the completed legal six-stage author policy");
        HologramPresets.Preset processed = (HologramPresets.Preset) field(author, "virtualPreset");
        require(HologramBlueprintProcessing.complete(processed), "production processing receipt is complete");
        call("requireBlueprintProcessing", author, processed);
        call("validateProcessedSaveOptions", author, new NBTTagCompound());
        HologramLibraryService.stampAuthor(processed, actor, true);
        processed.id = "oldissue_" + UUID.randomUUID()
            .toString();
        processed.name = "Native " + kind;
        HologramBlueprintVersions.stamp(processed);
        HologramPresets.validate(processed);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", 2);
        envelope.put("blueprint", processed);
        byte[] json = HologramBlueprintSharing
            .encodeBounded(new GsonBuilder().create(), envelope, HologramBlueprintSharing.MAX_BYTES);
        HologramPresets.Preset reloaded = HologramBlueprintSharing.decode(new String(json, StandardCharsets.UTF_8));
        require(
            HologramBlueprintProcessing.complete(reloaded) && actor.getUniqueID()
                .toString()
                .equals(reloaded.authorUuid) && reloaded.cells.size() == 1,
            "Sharing serialization reload retains the genuine author policy and identity");
        require(
            reloaded.cells.get(0).damage == capturedItem.getItemDamage()
                && reloaded.cells.get(0).controller == rawCell.controller,
            "serialized reload preserves actual native item damage and controller role");

        actor.setPosition(TARGET_X + .5, Y, z + .5);
        Object placement = fixtureSession(actor, TARGET_X, Y, z, reloaded);
        HologramCapture capture = (HologramCapture) field(placement, "capture");
        require(
            capture.cells.size() == 1 && !capture.incomplete && !capture.pending,
            "production virtual population creates the actual one-cell target");
        HologramCapture.Cell target = capture.cells.get(0);
        if (expectedReactorId >= 0) require(
            !target.anchor && "templateController".equals(target.role) && target.sourceWarning.isEmpty(),
            "reloaded native LNR remains a legitimate construction controller");
        ItemStack paid = (ItemStack) call("targetFor", placement, target, 0);
        require(
            paid != null && paid.stackSize > 0 && paid.isItemEqual(capturedItem),
            "actual Service resolves the exact captured native payment item");
        paid = paid.copy();
        paid.stackSize = 1;
        for (int slot = 1; slot < actor.inventory.mainInventory.length; slot++)
            actor.inventory.mainInventory[slot] = null;
        actor.inventory.mainInventory[1] = paid.copy();
        actor.inventory.markDirty();
        call("refreshPlan", placement);
        HologramBlueprintResources.Plan plan = (HologramBlueprintResources.Plan) field(
            placement,
            "blueprintTotalResources");
        require(
            plan != null && plan.fluids.isEmpty() && plan.energy.signum() == 0 && plan.essentia.isEmpty(),
            "this exact native single-cell quote needs no fabricated external resource provider");
        long quotedCount = 0;
        for (ItemStack quoted : plan.items) {
            require(
                quoted.isItemEqual(paid) && ItemStack.areItemStackTagsEqual(quoted, paid),
                "native quote contains only the exact single-cell payment variant");
            quotedCount += quoted.stackSize;
        }
        require(quotedCount == 1 && stock(actor) == 1, "actual quote is funded by one finite physical native item");
        NBTTagCompound paymentBefore = inventory(actor);
        NBTTagCompound wholeInventoryBefore = wholeInventory(actor);
        NBTTagCompound targetBefore = sourceState(world, TARGET_X, Y, z);
        PlacementVeto veto = new PlacementVeto(world, actor, TARGET_X, Y, z, expectedReactorId);
        boolean cancelAttemptReturned;
        MinecraftForge.EVENT_BUS.register(veto);
        try {
            cancelAttemptReturned = (Boolean) call("operate", placement, target, 0);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
        }
        require(
            veto.events == 1 && veto.nativeVisible && veto.stockDuringEvent == 0,
            "actual PlaceEvent observes a native installation after real material debit");
        NBTTagCompound paymentAfterRefund = inventory(actor);
        boolean targetAir = world.isAirBlock(TARGET_X, Y, z);
        boolean targetTileAbsent = world.getTileEntity(TARGET_X, Y, z) == null;
        boolean protectedStatus = "protected".equals(target.status);
        boolean exactRefund = paymentBefore.equals(paymentAfterRefund);
        if (!targetAir || !targetTileAbsent || !protectedStatus || !exactRefund) {
            NBTTagCompound failed = flags();
            failed.setBoolean("passed", false);
            failed.setString("case", kind);
            failed.setString(
                "world",
                world.getWorldInfo()
                    .getWorldName());
            failed.setString(
                "fixtureActor",
                actor.getUniqueID()
                    .toString());
            failed.setIntArray("targetPosition", new int[] { TARGET_X, Y, z });
            failed.setInteger("expectedMetaTileID", expectedReactorId);
            failed.setBoolean("operateReturned", cancelAttemptReturned);
            failed.setInteger("cancelPlaceEvents", veto.events);
            failed.setBoolean("nativeVisibleDuringEvent", veto.nativeVisible);
            failed.setBoolean("eventCancelledBeforeVeto", veto.cancelledBefore);
            failed.setBoolean("eventCancelledAfterVeto", veto.cancelledAfter);
            failed.setInteger("stockDuringCancelPlaceEvent", veto.stockDuringEvent);
            failed.setBoolean("targetAirImmediatelyAfter", targetAir);
            failed.setBoolean("targetTileAbsentImmediatelyAfter", targetTileAbsent);
            failed.setBoolean("protectedStatusImmediatelyAfter", protectedStatus);
            failed.setBoolean("exactPaymentRefundImmediatelyAfter", exactRefund);
            failed.setString("cellStatus", target.status);
            failed.setString("cellResourceError", target.resourceError == null ? "" : target.resourceError);
            failed.setString("serviceStatus", (String) field(placement, "status"));
            failed.setTag("targetBefore", targetBefore);
            failed.setTag("targetDuringEvent", veto.nativeState);
            failed.setTag("targetImmediatelyAfter", sourceState(world, TARGET_X, Y, z));
            failed.setTag("sourceImmediatelyAfter", sourceState(world, SOURCE_X, Y, z));
            failed.setTag("paymentBefore", paymentBefore);
            failed.setTag("paymentImmediatelyAfter", paymentAfterRefund);
            failed.setTag("wholeInventoryBefore", wholeInventoryBefore);
            failed.setTag("wholeInventoryImmediatelyAfter", wholeInventory(actor));
            HologramV011Evidence.write("sulfur-source-old-issues-cancel-failure.nbt", failed);
            System.err.println(
                "[GTMB-OLD-ISSUES-CANCEL] case=" + kind
                    + " operated="
                    + cancelAttemptReturned
                    + " cancelled="
                    + veto.cancelledAfter
                    + " air="
                    + targetAir
                    + " tileAbsent="
                    + targetTileAbsent
                    + " status="
                    + target.status
                    + " exactRefund="
                    + exactRefund
                    + " stock="
                    + stock(actor));
        }
        require(
            targetAir && targetTileAbsent && protectedStatus && exactRefund,
            "cancelled native Service placement restores target and exact physical payment: case=" + kind
                + " air="
                + targetAir
                + " tileAbsent="
                + targetTileAbsent
                + " status="
                + target.status
                + " exactRefund="
                + exactRefund);
        call("refreshPlan", placement);
        require(
            (Boolean) call("operate", placement, target, 0) && "placed".equals(target.status),
            "actual Service retry pays and installs the native target: " + field(placement, "status"));
        require(stock(actor) == 0, "actual native payment is debited exactly once on successful placement");
        TileEntity targetTile = world.getTileEntity(TARGET_X, Y, z);
        int placedId = -1;
        String nativeClass;
        if (expectedReactorId >= 0) {
            IGregTechTileEntity placed = gt(targetTile);
            placedId = placed.getMetaTileID();
            nativeClass = placed.getMetaTileEntity()
                .getClass()
                .getName();
            require(
                world.getBlock(TARGET_X, Y, z) == GregTechAPI.sBlockMachines && placedId == expectedReactorId
                    && REACTOR_CLASS.equals(nativeClass),
                "actual paid world target has the exact LNR metaTile ID and exact native class");
        } else {
            nativeClass = world.getBlock(TARGET_X, Y, z)
                .getClass()
                .getName();
            require(
                targetTile == null && !world.isBlockIndirectlyGettingPowered(TARGET_X, Y, z)
                    && world.getBlock(TARGET_X, Y, z) == Blocks.redstone_lamp,
                "unpowered actual destination contains native unlit lamp rather than copied lit texture state");
        }
        NBTTagCompound after = sourceState(world, SOURCE_X, Y, z);
        require(
            before.equals(after) && world.getBlock(SOURCE_X, Y, z) == sourceBlock
                && world.getBlockMetadata(SOURCE_X, Y, z) == sourceMetadata,
            "source native block and physical persistence remain unchanged through capture/process/refund/placement");
        if (sourceTile != null) require(
            world.getTileEntity(SOURCE_X, Y, z) == sourceTile
                && sourceCaptured.equals(HologramNativeCapture.capture(sourceTile)),
            "source native TE reference and captured payload unchanged");

        NBTTagCompound result = flags();
        result.setString("case", kind);
        result.setIntArray("sourcePosition", new int[] { SOURCE_X, Y, z });
        result.setIntArray("targetPosition", new int[] { TARGET_X, Y, z });
        result.setTag("sourceNativeBefore", before);
        result.setTag("sourceNativeAfter", after);
        result.setInteger("sourceNativeBlockId", Block.getIdFromBlock(sourceBlock));
        result.setInteger("nativeBlockId", Block.getIdFromBlock(world.getBlock(TARGET_X, Y, z)));
        result.setString("sourceBlockRegistryName", registry(sourceBlock));
        result.setString("targetBlockRegistryName", registry(world.getBlock(TARGET_X, Y, z)));
        result.setBoolean("sourceIsPowered", world.isBlockIndirectlyGettingPowered(SOURCE_X, Y, z));
        result.setBoolean("targetIsPowered", world.isBlockIndirectlyGettingPowered(TARGET_X, Y, z));
        result.setInteger("sourceMetadata", sourceMetadata);
        result.setInteger("targetMetadata", world.getBlockMetadata(TARGET_X, Y, z));
        result.setInteger("nativeMetaTileID", placedId);
        result.setInteger("expectedMetaTileID", expectedReactorId);
        result.setString("nativeClass", nativeClass);
        result.setString("paidItemRegistryName", Item.itemRegistry.getNameForObject(paid.getItem()));
        result.setTag("paidItem", paid.writeToNBT(new NBTTagCompound()));
        result.setByteArray("serializedBlueprintJson", json);
        result.setString("serializedBlueprintSha256", sha256(json));
        result.setInteger("serializedBlueprintLength", json.length);
        result.setTag("authorProcessingPolicy", policy.write());
        result.setString("authorProcessingReceipt", reloaded.processingNbt);
        result.setBoolean("incompleteProcessingRejected", true);
        result.setBoolean("processingActionAcknowledged", true);
        result.setBoolean("authorSaveGateAccepted", true);
        result.setBoolean("serializedReloadComplete", true);
        result.setBoolean("sourceUnchanged", true);
        result.setBoolean("cancelledExactPaymentRefunded", true);
        result.setBoolean("paidServicePlaced", true);
        result.setInteger("cancelPlaceEvents", veto.events);
        result.setInteger("stockDuringCancelPlaceEvent", veto.stockDuringEvent);
        result.setLong("nativePlanPaidItemCount", quotedCount);
        result.setLong("debitedCount", 1);
        result.setLong("remainingCount", stock(actor));
        result.setTag("paymentBefore", paymentBefore);
        result.setTag("paymentAfterRefund", paymentAfterRefund);
        result.setTag("paymentAfterPlacement", inventory(actor));
        result.setTag("targetNativeAfter", sourceState(world, TARGET_X, Y, z));
        return result;
    }

    /** Only initializes a raw fixture draft; author review flags and placement authorization are never assigned. */
    private static Object fixtureSession(EntityPlayerMP actor, int x, int y, int z, HologramPresets.Preset raw)
        throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramService$Session");
        Constructor<?> constructor = type
            .getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class, boolean.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(actor, x, y, z, false, ForgeDirection.NORTH.ordinal());
        set(session, "virtual", true);
        set(session, "virtualPreset", HologramPresets.copy(raw));
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = false;
        settings.containers = false;
        set(session, "materialSettings", settings);
        call("populateVirtual", session);
        return session;
    }

    private static NBTTagCompound processingAction(Object session, HologramBlueprintProcessing.Policy policy)
        throws Exception {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("op", "processBlueprint");
        request.setString("session", (String) field(session, "id"));
        request.setLong("planRevision", (Long) field(session, "planRevision"));
        request.setTag("policy", policy.write());
        return request;
    }

    private static int reactorId() {
        int result = -1;
        for (int id = 1; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity registered = GregTechAPI.METATILEENTITIES[id];
            if (registered == null || !registered.getClass()
                .getName()
                .equals(REACTOR_CLASS)) continue;
            require(result < 0, "unique actual native LNR registration");
            result = id;
        }
        require(result > 0, "actual large naquadah reactor is registered; no substitute machine permitted");
        return result;
    }

    private static IGregTechTileEntity gt(TileEntity tile) {
        require(tile instanceof IGregTechTileEntity, "actual native GT tile");
        return (IGregTechTileEntity) tile;
    }

    private static NBTTagCompound sourceState(WorldServer world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        NBTTagCompound result = new NBTTagCompound();
        if (tile != null) tile.writeToNBT(result);
        result.setString("nativeBlockRegistryName", registry(world.getBlock(x, y, z)));
        result.setInteger("nativeBlockId", Block.getIdFromBlock(world.getBlock(x, y, z)));
        result.setInteger("nativeMetadata", world.getBlockMetadata(x, y, z));
        result.setBoolean("nativeIsPowered", world.isBlockIndirectlyGettingPowered(x, y, z));
        return result;
    }

    private static int stock(EntityPlayerMP actor) {
        int result = 0;
        for (int slot = 1; slot < actor.inventory.mainInventory.length; slot++) {
            ItemStack stack = actor.inventory.mainInventory[slot];
            if (stack != null && stack.stackSize > 0) result += stack.stackSize;
        }
        return result;
    }

    private static NBTTagCompound inventory(EntityPlayerMP actor) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList stocks = new NBTTagList();
        for (int slot = 1; slot < actor.inventory.mainInventory.length; slot++) {
            ItemStack stack = actor.inventory.mainInventory[slot];
            if (stack == null || stack.stackSize <= 0) continue;
            NBTTagCompound row = stack.writeToNBT(new NBTTagCompound());
            row.setInteger("nativeSlot", slot);
            stocks.appendTag(row);
        }
        result.setTag("stocks", stocks);
        return result;
    }

    private static NBTTagCompound wholeInventory(EntityPlayerMP actor) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList stocks = new NBTTagList();
        for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++) {
            ItemStack stack = actor.inventory.mainInventory[slot];
            if (stack == null || stack.stackSize <= 0) continue;
            NBTTagCompound row = stack.writeToNBT(new NBTTagCompound());
            row.setInteger("nativeSlot", slot);
            stocks.appendTag(row);
        }
        result.setTag("stocks", stocks);
        return result;
    }

    private static NBTTagCompound flags() {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("passed", true);
        result.setBoolean("actualNativeWorld", true);
        result.setBoolean("actualService", true);
        result.setBoolean("actualGui", false);
        result.setBoolean("fakeFixtureActor", true);
        return result;
    }

    private static void fresh(WorldServer world, int x, int y, int z) {
        require(world.blockExists(x, y, z) && world.isAirBlock(x, y, z), "fresh isolated old-issues coordinate");
    }

    private static String registry(Block block) {
        return Block.blockRegistry.getNameForObject(block);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object call(String name, Object session, Object... arguments) throws Exception {
        Class<?>[] types = new Class<?>[arguments.length + 1];
        Object[] values = new Object[arguments.length + 1];
        types[0] = session.getClass();
        values[0] = session;
        for (int i = 0; i < arguments.length; i++) {
            types[i + 1] = arguments[i] instanceof Integer ? int.class : arguments[i].getClass();
            values[i + 1] = arguments[i];
        }
        Method method = HologramService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, values);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    private static void require(boolean accepted, String message) {
        if (!accepted) throw new IllegalStateException("Native old-issues fixture: " + message);
    }

    public static final class PlacementVeto {

        private final WorldServer world;
        private final EntityPlayerMP actor;
        private final int x, y, z, reactorId;
        int events, stockDuringEvent = -1;
        boolean nativeVisible, cancelledBefore, cancelledAfter;
        NBTTagCompound nativeState = new NBTTagCompound();

        private PlacementVeto(WorldServer world, EntityPlayerMP actor, int x, int y, int z, int reactorId) {
            this.world = world;
            this.actor = actor;
            this.x = x;
            this.y = y;
            this.z = z;
            this.reactorId = reactorId;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.world != world || event.player != actor || event.x != x || event.y != y || event.z != z) return;
            events++;
            cancelledBefore = event.isCanceled();
            stockDuringEvent = stock(actor);
            nativeState = sourceState(world, x, y, z);
            TileEntity tile = world.getTileEntity(x, y, z);
            if (reactorId >= 0 && tile instanceof IGregTechTileEntity) {
                IGregTechTileEntity gt = (IGregTechTileEntity) tile;
                nativeVisible = world.getBlock(x, y, z) == GregTechAPI.sBlockMachines && gt.getMetaTileID() == reactorId
                    && gt.getMetaTileEntity() != null
                    && gt.getMetaTileEntity()
                        .getClass()
                        .getName()
                        .equals(REACTOR_CLASS);
            } else if (reactorId < 0) nativeVisible = tile == null && world.getBlock(x, y, z) == Blocks.redstone_lamp;
            event.setCanceled(true);
            cancelledAfter = event.isCanceled();
        }
    }
}
