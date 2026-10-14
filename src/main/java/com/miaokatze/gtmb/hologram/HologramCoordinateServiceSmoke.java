package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Actual NC inventory payments and native installation; this explicitly constructed session is world-only evidence. */
public final class HologramCoordinateServiceSmoke {

    private HologramCoordinateServiceSmoke() {}

    public static NBTTagCompound run(WorldServer world, EntityPlayerMP actor, Block panel, int metadata,
        NBTTagCompound source, ItemStack[] physicalCards, int sourceX, int sourceY, int sourceZ) throws Exception {
        require(
            physicalCards.length == 3 && "IC2NCAdvancedInfoPanel".equals(source.getString("id")),
            "coordinate payment fixture uses three real cards and the native advanced panel");
        ItemStack[] savedInventory = copy(actor.inventory.mainInventory);
        int savedSlot = actor.inventory.currentItem;
        boolean savedCreative = actor.capabilities.isCreativeMode;
        double savedX = actor.posX, savedY = actor.posY, savedZ = actor.posZ;
        NBTTagCompound originalSource = snapshot(world.getTileEntity(sourceX, sourceY, sourceZ));
        NBTTagCompound evidence = new NBTTagCompound();
        evidence.setString(
            "fixtureKind",
            "native WorldServer, actual Service payment and PlaceEvent rollback; constructed fixture Session");
        evidence.setTag("sourceBefore", originalSource.copy());
        evidence.setIntArray("sourcePosition", new int[] { sourceX, sourceY, sourceZ });
        evidence.setTag("initialInventory", inventory(actor));
        evidence.setTag("stages", new NBTTagList());
        Object session = null;
        HologramCapture.Cell failureTarget = null;
        Veto observedVeto = null;
        String stage = "prepare-reviewed-source";
        try {
            actor.capabilities.isCreativeMode = false;
            actor.inventory.currentItem = 0;
            actor.inventory.mainInventory[0] = savedInventory[savedSlot].copy();
            HologramPresets.Preset raw = new HologramPresets.Preset();
            raw.schema = 2;
            raw.id = "nc_paid_coordinate_fixture";
            raw.name = "NC coordinate payment";
            raw.controllerType = "area";
            HologramPresets.Cell host = HologramPresets.cell(new ItemStack(panel, 1, metadata), 0, 0, 0, -1, false);
            host.block = Block.blockRegistry.getNameForObject(panel);
            host.metadata = metadata;
            host.tileNbt = HologramBlueprintNbt.encodeCaptured(source);
            raw.cells.add(host);
            raw.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false));
            HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
            policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
            for (HologramCoordinatePolicies.Relation relation : HologramCoordinatePolicies
                .inspect(raw, actor.dimension, sourceX, sourceY, sourceZ))
                if ("nc.sensor".equals(relation.adapter) && relation.slot > 0)
                    policy.coordinateOverrides.put(relation.id, "clear");
            HologramPresets.Preset processed = HologramBlueprintProcessing
                .process(raw, policy, actor.dimension, sourceX, sourceY, sourceZ);
            session = session(actor, processed, sourceX, sourceY, sourceZ);
            actor.setPosition(sourceX + 32.5, sourceY, sourceZ + .5);
            Method transform = HologramService.class.getDeclaredMethod(
                "transformVirtual",
                session.getClass(),
                int.class,
                int.class,
                int.class,
                boolean.class,
                boolean.class);
            transform.setAccessible(true);
            require(
                (Boolean) transform.invoke(null, session, sourceX + 32, sourceY, sourceZ, true, false),
                "actual Service rotates and moves the reviewed NC blueprint");
            HologramCapture capture = (HologramCapture) get(session, "capture");
            HologramCapture.Cell target = capture.cells.stream()
                .filter(cell -> cell.block == panel)
                .findFirst()
                .get();
            failureTarget = target;
            world.getChunkFromBlockCoords(target.x, target.z);
            require(world.isAirBlock(target.x, target.y, target.z), "NC transaction destination is empty");
            ItemStack[] paymentCards = copy(physicalCards);
            // A cleared reviewed relationship accepts the same real cache from a different old location.
            paymentCards[1].getTagCompound()
                .setInteger("x", sourceX + 701);
            paymentCards[1].getTagCompound()
                .setDouble(
                    "energyL",
                    physicalCards[1].getTagCompound()
                        .getDouble("energyL") + 10D);
            fill(actor, panel, metadata, paymentCards);
            NBTTagList actualBefore = inventory(actor);
            ItemStack badCache = actor.inventory.mainInventory[2].copy();
            badCache.getTagCompound()
                .setString("followupUnexpectedConfiguration", "must-match-exactly");
            actor.inventory.mainInventory[2] = badCache;
            NBTTagList badBefore = inventory(actor);
            stage = "before-different-configuration";
            record(evidence, stage, session, target, world, actor, null);
            evidence.setBoolean("differentConfigurationOperateResult", operate(session, target));
            stage = "after-different-configuration";
            record(evidence, stage, session, target, world, actor, null);
            require("missing".equals(target.status), "a sensor card with different unknown configuration is rejected");
            require(
                badBefore.equals(inventory(actor)) && world.isAirBlock(target.x, target.y, target.z),
                "rejected configuration preserves all actual inventory variants and the destination");
            evidence.setTag("differentConfigurationBefore", badBefore.copy());
            evidence.setTag("differentConfigurationAfter", inventory(actor));
            evidence.setTag("reviewedRuntimeCandidate", paymentCards[1].writeToNBT(new NBTTagCompound()));
            fill(actor, panel, metadata, paymentCards);
            NBTTagCompound expected = HologramBlueprintNbt.nativePayload(
                HologramCoordinatePolicies.placementProjection(
                    HologramBlueprintNbt.forPlacement(
                        target.block,
                        target.meta,
                        target.tileNbt,
                        actor.getUniqueID(),
                        actor.getCommandSenderName(),
                        target.x,
                        target.y,
                        target.z,
                        true,
                        true,
                        null),
                    actor.dimension,
                    target.x,
                    target.y,
                    target.z));
            evidence.setTag("expectedInstalledTile", expected.copy());
            existingPanels(session, target, world, actor, expected, evidence);
            Veto veto = new Veto(
                target,
                world.getTileEntity(sourceX, sourceY, sourceZ)
                    .getClass(),
                expected);
            observedVeto = veto;
            evidence.setTag("expectedInstalledTile", expected.copy());
            stage = "before-place-event-veto";
            record(evidence, stage, session, target, world, actor, veto);
            MinecraftForge.EVENT_BUS.register(veto);
            try {
                evidence.setBoolean("vetoOperateResult", operate(session, target));
                stage = "after-place-event-veto";
                record(evidence, stage, session, target, world, actor, veto);
                require(
                    "protected".equals(target.status),
                    "real PlaceEvent veto marks the NC target protected and unbuilt: " + get(session, "status"));
            } finally {
                MinecraftForge.EVENT_BUS.unregister(veto);
            }
            require(
                veto.events == 2 && veto.shellEvents == 1
                    && veto.completeEvents == 1
                    && veto.cancelledEvents == 1
                    && veto.unexpectedEvents == 0
                    && veto.observedCards == 3
                    && veto.complete,
                "one native empty-shell event precedes one cancelled complete three-card Service target");
            require(
                actualBefore.equals(inventory(actor)) && world.isAirBlock(target.x, target.y, target.z),
                "PlaceEvent rollback restores original paid card coordinates, caches and opaque tags exactly");
            evidence.setTag("rollbackBefore", actualBefore.copy());
            evidence.setTag("rollbackAfter", inventory(actor));
            evidence.setTag("eventNativeCards", veto.observed.copy());
            stage = "before-successful-placement";
            record(evidence, stage, session, target, world, actor, veto);
            boolean placedResult = operate(session, target);
            stage = "after-successful-placement";
            record(evidence, stage, session, target, world, actor, veto);
            require(
                placedResult,
                "reviewed runtime observations and old coordinates pay for the mapped and cleared native cards");
            NBTTagCompound placed = snapshot(world.getTileEntity(target.x, target.y, target.z));
            NBTTagList cards = placed.getTagList("Items", 10);
            require(cards.tagCount() == 3, "successful paid placement installs exactly three cards");
            requireNativeState(card(cards, 0), "NO_TARGET");
            requireNativeState(card(cards, 1), "OUT_OF_RANGE");
            requireNativeState(card(cards, 2), "OUT_OF_RANGE");
            invoke("refreshPlan", session);
            require(
                "satisfied".equals(target.status),
                "the mapped three-card target remains present after native state updates");
            NBTTagCompound reactor = card(cards, 0).getCompoundTag("tag");
            require(
                reactor.getInteger("x") == target.x && reactor.getInteger("y") == target.y
                    && reactor.getInteger("z") == target.z + 1,
                "internal sensor target follows actual moved and rotated geometry");
            require(
                reactor.getString("followupAsset")
                    .equals(
                        physicalCards[0].getTagCompound()
                            .getString("followupAsset")),
                "relative projection retains the exact non-coordinate card payload");
            require(
                !HologramCoordinatePolicies.sensorStateProjection(card(cards, 1))
                    .hasKey("tag")
                    && !HologramCoordinatePolicies.sensorStateProjection(card(cards, 2))
                        .hasKey("tag"),
                "clear produces native unbound cards with only their current typed native state");
            require(
                actor.inventory.mainInventory[1] == null && actor.inventory.mainInventory[2] == null
                    && actor.inventory.mainInventory[3] == null
                    && actor.inventory.mainInventory[4] == null,
                "actual transaction consumes the three paid cards and one panel shell once");
            require(
                originalSource.equals(snapshot(world.getTileEntity(sourceX, sourceY, sourceZ))),
                "original native source panel and all original card NBT remain unchanged");
            HologramBlueprintProcessing.Policy unsafe = HologramBlueprintProcessing.Policy.read(policy.write());
            for (HologramCoordinatePolicies.Relation relation : HologramCoordinatePolicies
                .inspect(raw, actor.dimension, sourceX, sourceY, sourceZ))
                if ("nc.sensor".equals(relation.adapter) && relation.slot == 0)
                    unsafe.coordinateOverrides.put(relation.id, "clear");
            boolean blocked = false;
            try {
                HologramBlueprintProcessing.process(raw, unsafe, actor.dimension, sourceX, sourceY, sourceZ);
            } catch (java.io.IOException expectedFailure) {
                blocked = true;
            }
            require(blocked, "unknown extra sensor card NBT blocks a clear policy before any world write");
            evidence.setTag("sourceBefore", originalSource);
            evidence.setTag("sourceAfter", snapshot(world.getTileEntity(sourceX, sourceY, sourceZ)));
            evidence.setTag("inventoryBefore", actualBefore);
            evidence.setTag("inventoryAfter", inventory(actor));
            evidence.setTag("placed", placed);
            evidence.setBoolean("passed", true);
            HologramV011Evidence.write("nc-payment-world.nbt", evidence);
            return evidence;
        } catch (Exception | AssertionError | LinkageError failure) {
            evidence.setBoolean("passed", false);
            evidence.setString(
                "failureStage",
                evidence.hasKey("activeStage", 8) ? evidence.getString("activeStage") : stage);
            evidence.setString("failure", failure.toString());
            try {
                record(evidence, "failure", session, failureTarget, world, actor, observedVeto);
                TileEntity sourceAtFailure = world.getTileEntity(sourceX, sourceY, sourceZ);
                evidence.setBoolean("sourceMissingAtFailure", sourceAtFailure == null);
                if (sourceAtFailure != null) evidence.setTag("sourceAtFailure", snapshot(sourceAtFailure));
                HologramV011Evidence.write("nc-payment-world-failure.nbt", evidence);
            } catch (Exception | LinkageError diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
            }
            throw failure;
        } finally {
            actor.inventory.mainInventory = savedInventory;
            actor.inventory.currentItem = savedSlot;
            actor.capabilities.isCreativeMode = savedCreative;
            actor.setPosition(savedX, savedY, savedZ);
            actor.inventory.markDirty();
        }
    }

    private static Object session(EntityPlayerMP actor, HologramPresets.Preset preset, int x, int y, int z)
        throws Exception {
        Class<?> type = Class.forName(HologramService.class.getName() + "$Session");
        Constructor<?> constructor = type
            .getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class, boolean.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(actor, x, y, z, false, 1);
        set(session, "virtual", true);
        set(session, "virtualPreset", preset);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.main = true;
        settings.me = false;
        settings.containers = false;
        set(session, "materialSettings", settings);
        set(session, "materialContext", new HologramMaterials.Context(actor, settings));
        invoke("populateVirtual", session);
        return session;
    }

    private static boolean operate(Object session, HologramCapture.Cell target) throws Exception {
        invoke("refreshPlan", session);
        HologramCapture capture = (HologramCapture) get(session, "capture");
        Method operate = HologramService.class
            .getDeclaredMethod("operate", session.getClass(), HologramCapture.Cell.class, int.class);
        operate.setAccessible(true);
        return (Boolean) operate.invoke(null, session, target, capture.cells.indexOf(target));
    }

    private static void fill(EntityPlayerMP actor, Block panel, int metadata, ItemStack[] cards) {
        Arrays.fill(actor.inventory.mainInventory, 1, actor.inventory.mainInventory.length, null);
        for (int i = 0; i < cards.length; i++) actor.inventory.mainInventory[i + 1] = cards[i].copy();
        actor.inventory.mainInventory[4] = new ItemStack(panel, 1, metadata);
        actor.inventory.mainInventory[5] = new ItemStack(Blocks.stone);
        actor.inventory.markDirty();
    }

    private static void existingPanels(Object session, HologramCapture.Cell target, WorldServer world,
        EntityPlayerMP actor, NBTTagCompound expected, NBTTagCompound evidence) throws Exception {
        Method matches = HologramService.class.getDeclaredMethod(
            "targetMatches",
            session.getClass(),
            HologramCapture.Cell.class,
            int.class,
            Block.class,
            int.class);
        matches.setAccessible(true);
        HologramCapture capture = (HologramCapture) get(session, "capture");
        int index = capture.cells.indexOf(target);
        for (String kind : new String[] { "empty", "wrong-coordinate", "wrong-opaque", "wrong-static-config",
            "correct" }) {
            require(world.isAirBlock(target.x, target.y, target.z), "existing NC comparison fixture starts in air");
            require(
                world.setBlock(target.x, target.y, target.z, target.block, target.meta, 3),
                "native existing NC comparison shell installed");
            TileEntity existing = world.getTileEntity(target.x, target.y, target.z);
            require(existing instanceof IInventory, "existing NC comparison uses its real native inventory");
            try {
                if (!"empty".equals(kind)) {
                    NBTTagCompound candidate = (NBTTagCompound) expected.copy();
                    NBTTagCompound first = card(candidate.getTagList("Items", 10), 0).getCompoundTag("tag");
                    if ("wrong-coordinate".equals(kind)) first.setInteger("x", first.getInteger("x") + 7);
                    if ("wrong-opaque".equals(kind))
                        first.setString("followupUnexpectedNativeConfiguration", "preserve-exactly");
                    if ("wrong-static-config".equals(kind))
                        candidate.setBoolean("showLabels", !candidate.getBoolean("showLabels"));
                    existing.readFromNBT(candidate);
                }
                existing.markDirty();
                NBTTagCompound before = snapshot(existing);
                if ("correct".equals(kind)) {
                    NBTTagList cards = before.getTagList("Items", 10);
                    requireNativeState(card(cards, 0), "NO_TARGET");
                    requireNativeState(card(cards, 1), "OUT_OF_RANGE");
                    requireNativeState(card(cards, 2), "OUT_OF_RANGE");
                }
                NBTTagList beforeInventory = inventory(actor);
                invoke("refreshPlan", session);
                boolean present = (Boolean) matches.invoke(null, session, target, index, target.block, target.meta);
                record(evidence, "existing-" + kind + "-before-operation", session, target, world, actor, null);
                require(
                    present == "correct".equals(kind),
                    "native NC target matching distinguishes complete coordinates/configuration: " + kind);
                require(
                    ("correct".equals(kind) ? "satisfied" : "blocked").equals(target.status),
                    "native NC existing target classification preserves authored configuration: " + kind);
                operate(session, target);
                record(evidence, "existing-" + kind + "-after-operation", session, target, world, actor, null);
                if ("correct".equals(kind)) require(
                    "satisfied".equals(target.status),
                    "keeping a fully configured native NC target requires no harvest or device-recovery authority");
                require(
                    world.getTileEntity(target.x, target.y, target.z) == existing && before.equals(snapshot(existing))
                        && beforeInventory.equals(inventory(actor)),
                    "existing NC comparison leaves the native target and every inventory variant unchanged: " + kind);
            } finally {
                IInventory inventory = (IInventory) existing;
                for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                    inventory.setInventorySlotContents(slot, null);
                world.setBlockToAir(target.x, target.y, target.z);
            }
        }
    }

    private static void requireNativeState(NBTTagCompound card, String name) throws Exception {
        Class<?> type = Class.forName("shedar.mods.ic2.nuclearcontrol.api.CardState");
        Object state = type.getField(name)
            .get(null);
        int expected = (Integer) type.getMethod("getIndex")
            .invoke(state);
        NBTTagCompound tag = card.getCompoundTag("tag");
        require(
            tag.hasKey("state", 3) && tag.getInteger("state") == expected,
            "native sensor lifecycle reports its exact current CardState." + name);
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? null : items[i].copy();
        return copy;
    }

    private static NBTTagList inventory(EntityPlayerMP actor) {
        NBTTagList items = new NBTTagList();
        for (int i = 0; i < actor.inventory.mainInventory.length; i++) if (actor.inventory.mainInventory[i] != null) {
            NBTTagCompound tag = actor.inventory.mainInventory[i].writeToNBT(new NBTTagCompound());
            tag.setInteger("fixtureSlot", i);
            items.appendTag(tag);
        }
        return items;
    }

    private static NBTTagCompound card(NBTTagList cards, int slot) {
        for (int i = 0; i < cards.tagCount(); i++) if (cards.getCompoundTagAt(i)
            .getByte("Slot") == slot) return cards.getCompoundTagAt(i);
        throw new AssertionError("missing native NC card slot " + slot);
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        require(tile != null, "native NC tile exists");
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        return tag;
    }

    private static Object get(Object target, String name) throws Exception {
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

    private static Object invoke(String name, Object session) throws Exception {
        Method method = HologramService.class.getDeclaredMethod(name, session.getClass());
        method.setAccessible(true);
        return method.invoke(null, session);
    }

    private static void record(NBTTagCompound evidence, String stage, Object session, HologramCapture.Cell target,
        WorldServer world, EntityPlayerMP actor, Veto veto) throws Exception {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("stage", stage);
        row.setString(
            "world",
            world.getWorldInfo()
                .getWorldName());
        row.setInteger("dimension", actor.dimension);
        row.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        row.setTag("inventory", inventory(actor));
        if (session != null) {
            row.setString("sessionStatus", String.valueOf(get(session, "status")));
            row.setString("quoteError", String.valueOf(get(session, "blueprintResourceMissing")));
            row.setInteger("mode", (Integer) get(session, "mode"));
            row.setInteger("job", (Integer) get(session, "job"));
            HologramBlueprintResources.Plan resources = (HologramBlueprintResources.Plan) get(
                session,
                "blueprintResources");
            NBTTagList costs = new NBTTagList();
            if (resources != null) for (ItemStack item : resources.items) {
                NBTTagCompound cost = item.writeToNBT(new NBTTagCompound());
                HologramMaterials.Context context = (HologramMaterials.Context) get(session, "materialContext");
                if (context != null) cost.setInteger("available", context.count(item, Integer.MAX_VALUE));
                costs.appendTag(cost);
            }
            row.setTag("resourceItems", costs);
        }
        if (target != null) {
            row.setIntArray("targetPosition", new int[] { target.x, target.y, target.z });
            row.setString("cellStatus", target.status);
            row.setString("resourceError", target.resourceError);
            row.setString("plannedBlock", Block.blockRegistry.getNameForObject(target.block));
            row.setInteger("plannedMetadata", target.meta);
            NBTTagCompound planned = HologramBlueprintNbt.decode(target.tileNbt);
            if (planned != null) row.setTag("plannedTile", planned);
            row.setString(
                "actualBlock",
                Block.blockRegistry.getNameForObject(world.getBlock(target.x, target.y, target.z)));
            row.setInteger("actualMetadata", world.getBlockMetadata(target.x, target.y, target.z));
            TileEntity actual = world.getTileEntity(target.x, target.y, target.z);
            row.setString(
                "actualTileClass",
                actual == null ? "null"
                    : actual.getClass()
                        .getName());
            if (actual != null) row.setTag("actualTile", snapshot(actual));
        }
        if (veto != null) {
            row.setInteger("events", veto.events);
            row.setInteger("earlyShellEvents", veto.shellEvents);
            row.setInteger("completeEvents", veto.completeEvents);
            row.setInteger("cancelledEvents", veto.cancelledEvents);
            row.setInteger("unexpectedEvents", veto.unexpectedEvents);
            row.setTag("placementEvents", veto.observations.copy());
        }
        evidence.setString("activeStage", stage);
        evidence.getTagList("stages", 10)
            .appendTag(row);
        HologramV011Evidence.write("nc-payment-world-attempt.nbt", evidence);
        System.out.println(
            "[GTMB-NC-PAYMENT] stage=" + stage
                + " cellStatus="
                + row.getString("cellStatus")
                + " sessionStatus="
                + row.getString("sessionStatus")
                + " quoteError="
                + row.getString("quoteError")
                + " events="
                + row.getInteger("events")
                + " completeEvents="
                + row.getInteger("completeEvents"));
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }

    public static final class Veto {

        final int x, y, z;
        final Block block;
        final int metadata;
        final Class<?> nativeTileClass;
        final NBTTagCompound expectedTile;
        final NBTTagList expected;
        int events, observedCards;
        int shellEvents, completeEvents, cancelledEvents, unexpectedEvents;
        boolean complete;
        NBTTagList observed = new NBTTagList();
        final NBTTagList observations = new NBTTagList();

        Veto(HologramCapture.Cell target, Class<?> nativeTileClass, NBTTagCompound expectedTile) {
            x = target.x;
            y = target.y;
            z = target.z;
            block = target.block;
            metadata = target.meta;
            this.nativeTileClass = nativeTileClass;
            this.expectedTile = (NBTTagCompound) expectedTile.copy();
            expected = (NBTTagList) expectedTile.getTagList("Items", 10)
                .copy();
            require(expected.tagCount() == 3, "NC event observer requires the full expected three-card payload");
        }

        @SubscribeEvent
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.x != x || event.y != y || event.z != z) return;
            events++;
            TileEntity tile = event.world.getTileEntity(x, y, z);
            NBTTagCompound nativeState = tile == null ? new NBTTagCompound() : snapshot(tile);
            observed = nativeState.getTagList("Items", 10);
            observedCards = observed.tagCount();
            boolean factory = tile != null && tile.getClass() == nativeTileClass
                && nativeState.getString("id")
                    .equals(expectedTile.getString("id"))
                && event.world.getBlock(x, y, z) == block
                && event.world.getBlockMetadata(x, y, z) == metadata;
            complete = factory && observedCards == 3;
            for (int slot = 0; slot < 3 && complete; slot++) {
                boolean found = false;
                for (int i = 0; i < observed.tagCount(); i++) if (observed.getCompoundTagAt(i)
                    .getByte("Slot") == slot) {
                        if (found || !HologramCoordinatePolicies.sensorStateProjection(observed.getCompoundTagAt(i))
                            .equals(HologramCoordinatePolicies.sensorStateProjection(card(expected, slot))))
                            complete = false;
                        found = true;
                    }
                complete &= found;
            }
            boolean cancel = true;
            String phase = "unexpected-native-target";
            if (events == 1 && factory && observedCards == 0 && shellEvents == 0 && completeEvents == 0) {
                shellEvents++;
                cancel = false;
                phase = "early-native-empty-shell";
            } else if (complete && shellEvents == 1 && completeEvents == 0) {
                completeEvents++;
                phase = "complete-service-target";
            } else unexpectedEvents++;
            if (cancel) cancelledEvents++;
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setString("phase", phase);
            receipt.setBoolean("cancelled", cancel);
            receipt.setBoolean("factoryMatches", factory);
            receipt.setBoolean("complete", complete);
            receipt.setTag("nativeTile", nativeState);
            receipt.setTag("expectedTile", expectedTile.copy());
            receipt.setString(
                "nativeTileClass",
                tile == null ? "null"
                    : tile.getClass()
                        .getName());
            receipt.setString("block", Block.blockRegistry.getNameForObject(event.world.getBlock(x, y, z)));
            receipt.setInteger("metadata", event.world.getBlockMetadata(x, y, z));
            if (event.player instanceof EntityPlayerMP) {
                receipt.setTag("inventoryDuring", inventory((EntityPlayerMP) event.player));
            }
            observations.appendTag(receipt);
            if (cancel) event.setCanceled(true);
        }
    }
}
