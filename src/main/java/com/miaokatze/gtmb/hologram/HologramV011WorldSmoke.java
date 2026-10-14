package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** New native WorldServer transactions. Constructed Service Sessions are explicitly world-only fixtures. */
public final class HologramV011WorldSmoke {

    private boolean started;
    private EntityPlayerMP preparedActor;
    private int naturalGridTicks;

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.followupWorldServer")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramV011WorldSmoke());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (started || event.phase != TickEvent.Phase.END) return;
        if (preparedActor != null && ++naturalGridTicks < 80) return;
        started = true;
        MinecraftServer server = MinecraftServer.getServer();
        String profile = System.getProperty("gtmb.hologram.followupProfile", "");
        boolean shutdown = true;
        try {
            WorldServer world = server.worldServerForDimension(0);
            require(
                world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-followup-"),
                "fresh isolated world");
            EntityPlayerMP actor = preparedActor;
            if (actor == null) {
                actor = FakePlayerFactory.get(
                    world,
                    new GameProfile(UUID.fromString("a770b364-865a-49db-a24d-cf5400b0c0fe"), "GTMBFollowup"));
                actor.inventory.mainInventory = new ItemStack[36];
                actor.inventory.currentItem = 0;
                actor.inventory.mainInventory[0] = new ItemStack(
                    (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
                actor.capabilities.allowEdit = true;
                creative(actor, false);
            }
            if (profile.equals("essentia") && preparedActor == null) {
                HologramEssentiaMeSmoke.prepare(world, actor);
                preparedActor = actor;
                started = false;
                shutdown = false;
                return;
            }
            if (profile.equals("decor")) decorations(world, actor);
            else if (profile.equals("essentia")) localJar(world, actor);
            else throw new AssertionError("unsupported world-only follow-up profile " + profile);
            NBTTagCompound result = new NBTTagCompound();
            result.setString("profile", profile);
            result.setBoolean("passed", true);
            result.setString(
                "fixtureKind",
                "native WorldServer and Service transactions; explicitly constructed fixture Sessions");
            HologramV011Evidence.write(profile + "-world-final.nbt", result);
            HologramV011Evidence
                .marker(profile + "-world-pass.txt", "PASS new native WorldServer transactions for " + profile);
            System.out.println("[GTMB-FOLLOWUP-WORLD] FINAL PASS " + profile);
        } catch (Throwable failure) {
            failure.printStackTrace();
            try {
                HologramV011Evidence.marker(profile + "-world-fail.txt", "FAIL " + failure);
            } catch (Exception evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
        } finally {
            if (shutdown) server.initiateShutdown();
        }
    }

    private static void decorations(WorldServer world, EntityPlayerMP actor) throws Exception {
        String[] types = { "gcewing.architecture.common.block.BlockShape", "codechicken.multipart.BlockMultipart",
            "com.creativemd.littletiles.common.blocks.BlockTile", "com.carpentersblocks.block.BlockCarpentersSlope" };
        int[] counts = { 17, 5, 6, 1 };
        for (int family = 0; family < types.length; family++) {
            Block block = registered(types[family]);
            int x = 800 + family * 96, y = 88, z = 160;
            NativeSource source = source(world, block, family, x, y, z);
            HologramDecorMaterials.Descriptor descriptor = HologramDecorMaterials
                .describe(block, source.metadata, source.payload);
            require(!descriptor.costs.isEmpty(), "native costs present for " + types[family]);
            if (family == 0) require(
                descriptor.costs.get(0).numerator == 1 && descriptor.costs.get(0).denominator == 16,
                "official AC Shape17 raw recipe is one sixteenth");
            if (family == 1) require(
                descriptor.costs.size() == 2 && descriptor.costs.get(0).numerator == 1
                    && descriptor.costs.get(0).denominator == 8
                    && descriptor.costs.get(1).numerator == 1
                    && descriptor.costs.get(1).denominator == 8,
                "two native same-material FMP faces cost one quarter together");
            if (family == 2) require(descriptor.costs.size() == 2, "two real same-material LittleTiles static cuts");
            List<ItemStack> raw = new ArrayList<>();
            if (family < 3)
                raw.add(new ItemStack(family == 0 ? Blocks.quartz_block : Blocks.stone, 2, family == 0 ? 2 : 0));
            else {
                for (HologramDecorMaterials.Cost cost : descriptor.costs) raw.add(cost.raw.copy());
                raw.get(0)
                    .setItemDamage(2); // A real triangular slope item funds the same native base shell.
            }
            factoryIdGuards(world, actor, source, family, raw);
            Object rawSession = session(actor, source, x + 16, y, z, counts[family]);
            fill(actor, raw);
            NBTTagCompound row = base("decor-" + family + "-raw-ceil-refund", world, source, actor);
            NBTTagCompound expectedTarget = expectedPlacementPayload(source, actor);
            row.setTag("expectedTargetTile", expectedTarget.copy());
            row.setTag("nativeMaterialCosts", materialCosts(descriptor));
            List<HologramCapture.Cell> cells = cells(rawSession);
            invoke("refreshPlan", rawSession);
            row.setTag("reportedPaymentBefore", plannedPayments(rawSession));
            row.setInteger("reportedMissingBefore", (Integer) field(rawSession, "planMissing"));
            row.setTag("targetWorldBefore", targets(world, cells));
            HologramV011Evidence
                .write(row.getString("scenario") + "-operation-before.nbt", (NBTTagCompound) row.copy());
            Veto veto = new Veto(cells.get(0), expectedTarget, source);
            MinecraftForge.EVENT_BUS.register(veto);
            try {
                row.setBoolean("cancelOperationHandled", operate(rawSession, cells.get(0)));
            } catch (Exception | Error failure) {
                row.setString("operationFailure", failure.toString());
                throw failure;
            } finally {
                MinecraftForge.EVENT_BUS.unregister(veto);
                row.setInteger("placementEventCount", veto.events);
                row.setBoolean("eventNativeTargetComplete", veto.complete);
                row.setTag("placementEvents", veto.observations.copy());
                row.setTag("sourceTileAfterCancelled", snapshot(source.tile));
                row.setTag("targetWorldAfterCancelled", targets(world, cells));
                row.setTag("inventoryAfterCancelled", inventory(actor));
                row.setString("cancelledCellStatus", cells.get(0).status);
                row.setString("sessionStatusAfterCancelled", String.valueOf(field(rawSession, "status")));
                HologramV011Evidence
                    .write(row.getString("scenario") + "-cancel-observation.nbt", (NBTTagCompound) row.copy());
            }
            require(
                veto.events == 1 && veto.complete,
                "PlaceEvent observed complete native target before cancellation family=" + types[family]
                    + " events="
                    + veto.events
                    + " status="
                    + cells.get(0).status);
            require(
                world.isAirBlock(cells.get(0).x, y, z) && world.getTileEntity(cells.get(0).x, y, z) == null
                    && !"placed".equals(cells.get(0).status)
                    && inventory(actor).equals(row.getTagList("inventoryBefore", 10)),
                "cancelled real placement restores every original item tag and leaves no world target");
            row.setTag("inventoryAfterCancelled", inventory(actor));
            row.setString("cancelledCellStatus", cells.get(0).status);
            for (HologramCapture.Cell cell : cells) {
                require(
                    operate(rawSession, cell),
                    "actual raw payment places " + cell.x
                        + " status="
                        + cell.status
                        + " resourceError="
                        + cell.resourceError
                        + " session="
                        + field(rawSession, "status"));
                require(
                    matchesExpectedTarget(source, actor, world.getTileEntity(cell.x, y, z), expectedTarget),
                    "every actual target retains non-default native geometry/material/data");
            }
            require(stock(actor) == 0, "same-material group pays exactly the independent expected aggregate ceiling");
            row.setTag("inventoryAfter", inventory(actor));
            row.setTag("placed", targets(world, cells));
            row.setTag("sourceTileAfter", snapshot(source.tile));
            row.setInteger("placedCount", counts[family]);
            row.setInteger("expectedRawBlocks", family < 3 ? 2 : raw.size());
            row.setBoolean("cancelledPaymentRefunded", true);
            source.assertUnchanged(world);
            passed(row);

            List<ItemStack> prebuilt = new ArrayList<>();
            for (HologramDecorMaterials.Cost cost : descriptor.costs) prebuilt.add(cost.prebuilt.copy());
            if (family == 3) prebuilt.get(0)
                .setItemDamage(2);
            Object prebuiltSession = session(actor, source, x + 40, y, z, 1);
            fill(actor, prebuilt);
            NBTTagCompound prefab = base("decor-" + family + "-prebuilt", world, source, actor);
            prefab.setTag("expectedTargetTile", expectedTarget.copy());
            require(
                operate(prebuiltSession, cells(prebuiltSession).get(0)) && stock(actor) == 0,
                "exact native prebuilt components pay once without duplicate raw charges");
            require(
                matchesExpectedTarget(source, actor, world.getTileEntity(x + 40, y, z), expectedTarget),
                "prebuilt target matches native source");
            prefab.setTag("inventoryAfter", inventory(actor));
            prefab.setTag("placed", targets(world, cells(prebuiltSession)));
            prefab.setTag("sourceTileAfter", snapshot(source.tile));
            source.assertUnchanged(world);
            passed(prefab);

            creative(actor, true);
            fill(actor, new ArrayList<>());
            Object creativeSession = session(actor, source, x + 44, y, z, 1);
            NBTTagCompound free = base("decor-" + family + "-creative-empty", world, source, actor);
            free.setTag("expectedTargetTile", expectedTarget.copy());
            require(
                stock(actor) == 0 && operate(creativeSession, cells(creativeSession).get(0)),
                "creative with empty material inventory requires no fictitious shell");
            require(
                matchesExpectedTarget(source, actor, world.getTileEntity(x + 44, y, z), expectedTarget)
                    && stock(actor) == 0,
                "creative target retains actual native geometry without changing inventory");
            free.setTag("inventoryAfter", inventory(actor));
            free.setTag("placed", targets(world, cells(creativeSession)));
            free.setTag("sourceTileAfter", snapshot(source.tile));
            source.assertUnchanged(world);
            passed(free);
            creative(actor, false);
        }
        architectureThreeQuarters(world, actor);
        HologramV011AssetPrioritySmoke.run(world, actor);
        carpenterOwnedSources(world, actor);
    }

    private static void factoryIdGuards(WorldServer world, EntityPlayerMP actor, NativeSource source, int family,
        List<ItemStack> payment) throws Exception {
        for (int variant = 0; variant < 2; variant++) {
            String kind = variant == 0 ? "bogus" : "numeric";
            NBTTagCompound malformed = (NBTTagCompound) source.payload.copy();
            if (variant == 0) malformed.setString("id", "gtmb:bogus");
            else malformed.setInteger("id", 174011);
            fill(actor, payment);
            Object invalid = session(actor, source, source.x + 56 + variant, source.y, source.z, 1, malformed);
            HologramCapture.Cell cell = cells(invalid).get(0);
            NBTTagCompound receipt = base("decor-" + family + "-factory-id-" + kind, world, source, actor);
            receipt.setTag("invalidCapturedTile", malformed.copy());
            receipt.setTag("targetWorldBefore", targets(world, cells(invalid)));
            HologramV011Evidence.write(receipt.getString("scenario") + "-before.nbt", (NBTTagCompound) receipt.copy());
            invoke("refreshPlan", invalid);
            receipt.setString("quoteError", cell.resourceError);
            receipt.setString("quoteCellStatus", cell.status);
            receipt.setTag("reportedPayment", plannedPayments(invalid));
            Veto unexpectedPlacement = new Veto(cell, expectedPlacementPayload(source, actor), source);
            MinecraftForge.EVENT_BUS.register(unexpectedPlacement);
            try {
                receipt.setBoolean("operationHandled", operate(invalid, cell));
            } finally {
                MinecraftForge.EVENT_BUS.unregister(unexpectedPlacement);
                receipt.setTag("targetWorldAfter", targets(world, cells(invalid)));
                receipt.setTag("sourceTileAfter", snapshot(source.tile));
                receipt.setTag("inventoryAfter", inventory(actor));
                receipt.setString("cellStatusAfter", cell.status);
                receipt.setString("operationError", cell.resourceError);
                receipt.setInteger("placementEventCount", unexpectedPlacement.events);
                receipt.setTag("placementEvents", unexpectedPlacement.observations.copy());
                HologramV011Evidence
                    .write(receipt.getString("scenario") + "-observation.nbt", (NBTTagCompound) receipt.copy());
            }
            require(
                !receipt.getString("quoteError")
                    .isEmpty() && "unsupported".equals(cell.status),
                "Service quote rejects native factory " + kind + " ID before placement family=" + family);
            require(
                receipt.getTagList("reportedPayment", 10)
                    .tagCount() == 0 && unexpectedPlacement.events == 0
                    && world.isAirBlock(cell.x, cell.y, cell.z)
                    && world.getTileEntity(cell.x, cell.y, cell.z) == null
                    && inventory(actor).equals(receipt.getTagList("inventoryBefore", 10)),
                "invalid native factory ID leaves world and exact physical payment unchanged family=" + family);
            source.assertUnchanged(world);
            receipt.setBoolean("quoteRejected", true);
            receipt.setBoolean("placementRejected", true);
            passed(receipt);
        }
    }

    private static void carpenterOwnedSources(WorldServer world, EntityPlayerMP originalActor) throws Exception {
        Block block = registered("com.carpentersblocks.block.BlockCarpentersSlope");
        NativeSource prepared = source(world, block, 3, 1440, 88, 200);
        Class<?> protectedObject = Class.forName("com.carpentersblocks.util.protection.ProtectedObject");
        prepared.tile.getClass()
            .getMethod("setOwner", protectedObject)
            .invoke(
                prepared.tile,
                protectedObject.getConstructor(net.minecraft.entity.player.EntityPlayer.class)
                    .newInstance(originalActor));
        NativeSource source = new NativeSource(world, block, prepared.x, prepared.y, prepared.z);
        EntityPlayerMP installer = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("d328d2c4-3790-4ec9-b8ef-a330d6411740"), "GTMBNewOwner"));
        installer.inventory.mainInventory = new ItemStack[36];
        installer.inventory.currentItem = 0;
        installer.inventory.mainInventory[0] = originalActor.getHeldItem()
            .copy();
        creative(installer, false);
        String existingOwner = source.payload.getString("cbOwner"), installingOwner = nativeCarpenterOwner(installer);
        require(
            !existingOwner.isEmpty() && !existingOwner.equals(installingOwner),
            "two distinct native owner identities");

        int nativeX = 1460;
        NBTTagCompound retained = base("carpenter-existing-owner-level0-native-install", world, source, installer);
        retained.setString("fixtureKind", "native WorldServer factory lifecycle; no paid construction claimed");
        retained.setBoolean("paidPlacementClaimed", false);
        retained.setString("existingOwner", existingOwner);
        retained.setString("installingOwner", installingOwner);
        require(
            world.isAirBlock(nativeX, source.y, source.z)
                && world.setBlock(nativeX, source.y, source.z, block, source.metadata, 3),
            "native level0 destination starts empty");
        TileEntity nativeInstalled = HologramNativePlacement
            .install(world, nativeX, source.y, source.z, block, source.metadata, source.payload, installer);
        retained.setTag("installedTile", snapshot(nativeInstalled));
        retained.setTag("sourceTileAfter", snapshot(source.tile));
        retained.setTag("inventoryAfter", inventory(installer));
        require(
            snapshot(nativeInstalled).getString("cbOwner")
                .equals(existingOwner) && sameNativePersistence(snapshot(nativeInstalled), source.payload)
                && HologramNativePlacement.matchesPlaced(block, source.metadata, nativeInstalled, source.payload)
                && inventory(installer).equals(retained.getTagList("inventoryBefore", 10)),
            "level0 native install preserves nonempty original owner and all persisted skin data for a different actor");
        source.assertUnchanged(world);
        passed(retained);

        HologramDecorMaterials.Descriptor descriptor = HologramDecorMaterials
            .describe(block, source.metadata, source.payload);
        List<ItemStack> payment = new ArrayList<>();
        for (HologramDecorMaterials.Cost cost : descriptor.costs) payment.add(cost.raw.copy());
        payment.get(0)
            .setItemDamage(2);
        fill(installer, payment);
        Object processedSession = session(installer, source, 1464, source.y, source.z, 1);
        HologramPresets.Preset processed = HologramDeidentification
            .apply((HologramPresets.Preset) field(processedSession, "virtualPreset"), 1);
        set(processedSession, "virtualPreset", processed);
        HologramCapture.Cell cell = cells(processedSession).get(0);
        cell.tileNbt = processed.cells.get(0).tileNbt;
        cell.itemNbt = processed.cells.get(0).itemNbt;
        NBTTagCompound cleared = HologramBlueprintNbt.decode(cell.tileNbt);
        NBTTagCompound expectedCleared = (NBTTagCompound) source.payload.copy();
        expectedCleared.setString("cbOwner", "");
        NBTTagCompound rebound = base("carpenter-existing-owner-level1-service-refund", world, source, installer);
        rebound.setBoolean("paidPlacementClaimed", true);
        rebound.setInteger("deidentificationLevel", processed.deidentificationLevel);
        if (cleared != null) rebound.setTag("processedTile", cleared.copy());
        rebound.setTag("expectedNativeProcessedTile", expectedCleared.copy());
        rebound.setTag("nativeMaterialCosts", materialCosts(descriptor));
        rebound.setTag("targetWorldBefore", targets(world, cells(processedSession)));
        NBTTagCompound nativeCleared = null;
        try {
            NBTTagCompound placementProjection = HologramBlueprintNbt.forPlacement(
                block,
                source.metadata,
                cell.tileNbt,
                installer.getUniqueID(),
                installer.getCommandSenderName(),
                cell.x,
                cell.y,
                cell.z,
                processed.retainNbt,
                processed.retainCovers,
                processed.retainedNbtKeys);
            if (placementProjection != null) rebound.setTag("placementProjection", placementProjection.copy());
            nativeCleared = HologramBlueprintNbt.nativePayload(placementProjection);
            if (nativeCleared != null) rebound.setTag("nativeProcessedTile", nativeCleared.copy());
        } catch (Exception | Error failure) {
            rebound.setString("projectionFailure", failure.toString());
            throw failure;
        } finally {
            rebound.setTag("nativeProjectionDifferences", nativeDifferences(nativeCleared, expectedCleared));
            HologramV011Evidence
                .write(rebound.getString("scenario") + "-projection.nbt", (NBTTagCompound) rebound.copy());
        }
        require(
            nativeCleared != null && HologramDeidentification.carpenterOwner(block, nativeCleared)
                && nativeCleared.getString("cbOwner")
                    .isEmpty()
                && sameNativePersistence(nativeCleared, expectedCleared),
            "level1 production projection uses the actual native block and clears only the audited owner");
        NBTTagCompound expectedInstalled = (NBTTagCompound) nativeCleared.copy();
        expectedInstalled.setString("cbOwner", installingOwner);
        rebound.setTag("expectedTargetTile", expectedInstalled.copy());
        invoke("refreshPlan", processedSession);
        rebound.setTag("reportedPaymentBefore", plannedPayments(processedSession));
        HologramV011Evidence
            .write(rebound.getString("scenario") + "-operation-before.nbt", (NBTTagCompound) rebound.copy());
        Veto veto = new Veto(cell, expectedInstalled, source);
        MinecraftForge.EVENT_BUS.register(veto);
        try {
            rebound.setBoolean("cancelOperationHandled", operate(processedSession, cell));
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
            rebound.setInteger("placementEventCount", veto.events);
            rebound.setBoolean("eventNativeTargetComplete", veto.complete);
            rebound.setTag("placementEvents", veto.observations.copy());
            rebound.setTag("targetWorldAfterCancelled", targets(world, cells(processedSession)));
            rebound.setTag("sourceTileAfterCancelled", snapshot(source.tile));
            rebound.setTag("inventoryAfterCancelled", inventory(installer));
            rebound.setString("cancelledCellStatus", cell.status);
            HologramV011Evidence
                .write(rebound.getString("scenario") + "-cancel-observation.nbt", (NBTTagCompound) rebound.copy());
        }
        require(
            veto.events == 1 && veto.complete
                && "protected".equals(cell.status)
                && world.isAirBlock(cell.x, cell.y, cell.z)
                && world.getTileEntity(cell.x, cell.y, cell.z) == null
                && inventory(installer).equals(rebound.getTagList("inventoryBefore", 10)),
            "cancelled level1 installation refunds the exact owner-free shell, covers and dye");
        source.assertUnchanged(world);
        require(operate(processedSession, cell), "level1 projected source pays real native materials and installs");
        TileEntity installed = world.getTileEntity(cell.x, cell.y, cell.z);
        require(
            installed != null && world.getBlock(cell.x, cell.y, cell.z) == block
                && world.getBlockMetadata(cell.x, cell.y, cell.z) == source.metadata
                && snapshot(installed).getString("cbOwner")
                    .equals(installingOwner)
                && sameNativePersistence(snapshot(installed), expectedInstalled)
                && HologramNativePlacement.matchesPlaced(block, source.metadata, installed, expectedInstalled)
                && stock(installer) == 0,
            "level1 claims the native owner for the new actor while preserving all native data and paying exactly once");
        rebound.setTag("inventoryAfter", inventory(installer));
        rebound.setTag("placed", targets(world, cells(processedSession)));
        rebound.setTag("sourceTileAfter", snapshot(source.tile));
        source.assertUnchanged(world);
        passed(rebound);
    }

    private static void architectureThreeQuarters(WorldServer world, EntityPlayerMP actor) throws Exception {
        Block block = registered("gcewing.architecture.common.block.BlockShape");
        NativeSource source = source(world, block, 0, 1200, 88, 160);
        NBTTagCompound second = (NBTTagCompound) source.payload.copy();
        second.setInteger("Shape", 91);
        second.setString("Name2", "minecraft:stained_glass");
        second.setInteger("Data2", 11);
        second.setByte("Disconnected", (byte) 3);
        second.setByte("offsetX", (byte) 2);
        second.setByte("turn", (byte) 1);
        HologramNativePlacement.install(world, 1200, 88, 160, block, 0, second);
        source = new NativeSource(world, block, 1200, 88, 160);
        HologramDecorMaterials.Descriptor description = HologramDecorMaterials.describe(block, 0, source.payload);
        require(
            description.costs.get(0).numerator == 3 && description.costs.get(0).denominator == 4,
            "official AC Shape91 raw recipe is three quarters");
        List<ItemStack> raw = new ArrayList<>();
        raw.add(new ItemStack(Blocks.quartz_block, 3, 2));
        raw.add(new ItemStack(Blocks.stained_glass, 4, 11));
        fill(actor, raw);
        Object session = session(actor, source, 1216, 88, 160, 4);
        NBTTagCompound row = base("architecture-91-three-quarters-secondary", world, source, actor);
        for (HologramCapture.Cell cell : cells(session))
            require(operate(session, cell), "three-quarter multi-material actual placement");
        require(stock(actor) == 0, "four AC Shape91 pay three primary and four secondary blocks");
        row.setTag("inventoryAfter", inventory(actor));
        row.setTag("placed", targets(world, cells(session)));
        source.assertUnchanged(world);
        passed(row);
    }

    private static NativeSource source(WorldServer world, Block block, int family, int x, int y, int z)
        throws Exception {
        world.getChunkFromBlockCoords(x, z);
        require(world.isAirBlock(x, y, z), "native source position starts empty");
        if (family == 3) {
            require(world.setBlock(x, y, z, block, 1, 3), "actual non-default Carpenter world metadata");
            TileEntity tile = world.getTileEntity(x, y, z);
            Class<?> type = Class.forName("com.carpentersblocks.tileentity.TEBase");
            int shape = Class.forName("com.carpentersblocks.data.Slope")
                .getField("ID_OBL_EXT_POS_NW")
                .getByte(null);
            type.getMethod("setData", int.class)
                .invoke(tile, shape);
            type.getMethod("addAttribute", byte.class, ItemStack.class)
                .invoke(tile, (byte) 6, new ItemStack(Blocks.quartz_block, 1, 1));
            type.getMethod("addAttribute", byte.class, ItemStack.class)
                .invoke(tile, (byte) 5, new ItemStack(Blocks.planks, 1, 3));
            type.getMethod("addAttribute", byte.class, ItemStack.class)
                .invoke(tile, (byte) 13, new ItemStack(Items.dye, 1, 1));
        } else {
            NBTTagCompound tag = new NBTTagCompound();
            TileEntity prototype = block.createTileEntity(HologramResourceWorld.create(block, 0), 0);
            if (prototype != null) prototype.writeToNBT(tag);
            NBTTagCompound seed = HologramNativeCompatibilityFixture.seed(block, x, y, z);
            for (Object key : seed.func_150296_c()) tag.setTag(
                (String) key,
                seed.getTag((String) key)
                    .copy());
            if (family == 0) {
                tag.setInteger("Shape", 17);
                tag.setString("BaseName", "minecraft:quartz_block");
                tag.setInteger("BaseData", 2);
                tag.setByte("turn", (byte) 1);
            } else if (family == 1) {
                tag.setString("id", "savedMultipart");
                tag.getTagList("parts", 10)
                    .getCompoundTagAt(1)
                    .setString("material", "minecraft:stone");
                tag.getTagList("parts", 10)
                    .getCompoundTagAt(1)
                    .setByte("shape", (byte) 0x15);
            } else {
                tag.getCompoundTag("t0")
                    .setString("block", "minecraft:stone");
                tag.getCompoundTag("t1")
                    .setString("block", "minecraft:stone");
            }
            require(
                world.setBlock(x, y, z, block, 0, 3),
                "actual native source block installed family=" + block.getClass()
                    .getName() + " at=" + x + "," + y + "," + z);
            HologramNativePlacement.install(world, x, y, z, block, 0, tag);
        }
        NativeSource source = new NativeSource(world, block, x, y, z);
        require(source.sample != null && source.tile != null, "real native source yields actual sample and tile");
        return source;
    }

    private static void localJar(WorldServer world, EntityPlayerMP actor) throws Exception {
        HologramEssentiaNativeSmoke.run(world, actor);
        Block block = (Block) Block.blockRegistry.getObject("Thaumcraft:blockJar");
        require(block != null && HologramEssentia.knownAspect("aer"), "real Thaumcraft jar and aspect are installed");
        int x = 1300, y = 88, z = 160;
        world.getChunkFromBlockCoords(x, z);
        require(world.isAirBlock(x, y, z) && world.setBlock(x, y, z, block, 0, 3), "real TC source jar installed");
        TileEntity tile = world.getTileEntity(x, y, z);
        Class<?> aspectType = Class.forName("thaumcraft.api.aspects.Aspect");
        Object aer = aspectType.getMethod("getAspect", String.class)
            .invoke(null, "aer");
        tile.getClass()
            .getField("aspect")
            .set(tile, aer);
        tile.getClass()
            .getField("amount")
            .setInt(tile, 32);
        NativeSource source = new NativeSource(world, block, x, y, z);
        ItemStack filled = filledJar(aer, 64);
        require(
            HologramEssentia.jarAmount(filled, "aer") == 64,
            "native TC item API contains exactly 64 real aspect points");
        List<ItemStack> payment = new ArrayList<>();
        payment.add(new ItemStack(block));
        payment.add(filled.copy());
        fill(actor, payment);
        Object session = session(actor, source, x + 16, y, z, 1);
        ((HologramPresets.Preset) field(session, "virtualPreset")).deidentificationLevel = 1;
        NBTTagCompound row = base("tc-jar-local-source-payment-refund", world, source, actor);
        HologramCapture.Cell cell = cells(session).get(0);
        row.setTag("expectedTargetTile", source.payload.copy());
        row.setTag("sourceSample", source.sample.writeToNBT(new NBTTagCompound()));
        row.setTag("targetWorldBefore", targets(world, cells(session)));
        invoke("refreshPlan", session);
        row.setTag("reportedResourcesBefore", (NBTTagList) invoke("blueprintResourceRows", session));
        row.setInteger("reportedMissingBefore", (Integer) field(session, "planMissing"));
        row.setString("resourceErrorBefore", cell.resourceError);
        row.setString("cellStatusBefore", cell.status);
        row.setInteger("localSourceAspectBefore", HologramEssentia.jarAmount(actor.inventory.mainInventory[2], "aer"));
        HologramV011Evidence.write(row.getString("scenario") + "-operation-before.nbt", (NBTTagCompound) row.copy());
        Veto veto = new Veto(cell, source.payload, source, true);
        MinecraftForge.EVENT_BUS.register(veto);
        try {
            row.setBoolean("cancelOperationHandled", operate(session, cell));
        } catch (Exception | Error failure) {
            row.setString("operationFailure", failure.toString());
            throw failure;
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
            row.setInteger("placementEventCount", veto.events);
            row.setInteger("earlyNativeShellEventCount", veto.shellEvents);
            row.setInteger("completeServiceEventCount", veto.completeEvents);
            row.setInteger("cancelledEventCount", veto.cancelledEvents);
            row.setInteger("unexpectedEventCount", veto.unexpectedEvents);
            row.setBoolean("eventNativeTargetComplete", veto.complete);
            row.setTag("placementEvents", veto.observations.copy());
            row.setTag("sourceTileAfterCancelled", snapshot(source.tile));
            row.setTag("targetWorldAfterCancelled", targets(world, cells(session)));
            row.setTag("inventoryAfterCancelled", inventory(actor));
            row.setBoolean("inventoryRefundMatches", inventory(actor).equals(row.getTagList("inventoryBefore", 10)));
            row.setBoolean("targetAfterCancelledIsAir", world.isAirBlock(cell.x, y, z));
            row.setBoolean("targetAfterCancelledTileAbsent", world.getTileEntity(cell.x, y, z) == null);
            row.setString("cancelledCellStatus", cell.status);
            row.setString("resourceErrorAfterCancelled", cell.resourceError);
            row.setString("sessionStatusAfterCancelled", String.valueOf(field(session, "status")));
            row.setString(
                "blueprintResourceMissingAfterCancelled",
                String.valueOf(field(session, "blueprintResourceMissing")));
            row.setInteger(
                "localSourceAspectAfterCancelled",
                HologramEssentia.jarAmount(actor.inventory.mainInventory[2], "aer"));
            HologramV011Evidence
                .write(row.getString("scenario") + "-cancel-observation.nbt", (NBTTagCompound) row.copy());
        }
        require(
            veto.events == 2 && veto.shellEvents == 1
                && veto.completeEvents == 1
                && veto.cancelledEvents == 1
                && veto.unexpectedEvents == 0
                && veto.complete
                && inventory(actor).equals(row.getTagList("inventoryBefore", 10))
                && world.isAirBlock(cell.x, y, z)
                && world.getTileEntity(cell.x, y, z) == null
                && "protected".equals(cell.status),
            "local TC jar reservation restores exact original 64-point item and shell after cancellation");
        row.setTag("inventoryAfterCancelled", inventory(actor));
        row.setString("cancelledCellStatus", cell.status);
        require(operate(session, cell), "real TC jar uses empty shell plus actual source aspect points");
        require(
            world.getTileEntity(cell.x, y, z) != null
                && HologramEssentia.jarCost(snapshot(world.getTileEntity(cell.x, y, z))).amount == 32,
            "actual target jar contains exactly 32 aer");
        require(
            HologramEssentia.jarAmount(actor.inventory.mainInventory[2], "aer") == 32
                && actor.inventory.mainInventory[1] == null,
            "real source item decreased 64 to 32 and empty shell consumed once");
        row.setTag("inventoryAfter", inventory(actor));
        row.setTag("placed", targets(world, cells(session)));
        row.setTag("sourceTileAfter", snapshot(source.tile));
        row.setInteger("sourceAspectBefore", 64);
        row.setInteger("sourceAspectAfter", 32);
        row.setInteger("targetAspectAfter", 32);
        source.assertUnchanged(world);
        passed(row);

        fill(actor, java.util.Collections.singletonList(filled.copy()));
        Object noShell = session(actor, source, x + 20, y, z, 1);
        ((HologramPresets.Preset) field(noShell, "virtualPreset")).deidentificationLevel = 1;
        NBTTagCompound guard = base("tc-filled-jar-is-not-fuzzy-empty-shell", world, source, actor);
        guard.setBoolean("missingOperationHandled", operate(noShell, cells(noShell).get(0)));
        require(
            world.isAirBlock(x + 20, y, z) && world.getTileEntity(x + 20, y, z) == null
                && !"placed".equals(cells(noShell).get(0).status)
                && inventory(actor).equals(guard.getTagList("inventoryBefore", 10)),
            "filled jar cannot be consumed as fuzzy empty shell or lose its source contents");
        guard.setTag("inventoryAfter", inventory(actor));
        passed(guard);

        creative(actor, true);
        fill(actor, new ArrayList<>());
        Object emptyCreative = session(actor, source, x + 24, y, z, 1);
        NBTTagCompound absent = base("tc-creative-empty-source-does-not-copy-contents", world, source, actor);
        absent.setBoolean("missingOperationHandled", operate(emptyCreative, cells(emptyCreative).get(0)));
        require(
            world.isAirBlock(x + 24, y, z) && world.getTileEntity(x + 24, y, z) == null
                && !"placed".equals(cells(emptyCreative).get(0).status)
                && inventory(actor).equals(absent.getTagList("inventoryBefore", 10)),
            "creative physical-shell exemption cannot invent copied aspect contents");
        absent.setTag("inventoryAfter", inventory(actor));
        passed(absent);
        fill(actor, java.util.Collections.singletonList(filled.copy()));
        Object paidCreative = session(actor, source, x + 28, y, z, 1);
        NBTTagCompound paid = base("tc-creative-real-source-content-payment", world, source, actor);
        require(
            operate(paidCreative, cells(paidCreative).get(0))
                && HologramEssentia.jarAmount(actor.inventory.mainInventory[1], "aer") == 32,
            "creative jar with no empty shell still pays real 32 aspect points");
        paid.setTag("inventoryAfter", inventory(actor));
        paid.setTag("placed", targets(world, cells(paidCreative)));
        source.assertUnchanged(world);
        passed(paid);
        creative(actor, false);
        HologramEssentiaMeSmoke.run(world, actor);
    }

    private static ItemStack filledJar(Object aspect, int amount) throws Exception {
        Item item = null;
        for (Object entry : Item.itemRegistry) if (entry != null && entry.getClass()
            .getName()
            .equals("thaumcraft.common.blocks.ItemJarFilled")) item = (Item) entry;
        require(item != null, "real filled jar item registered");
        ItemStack stack = new ItemStack(item, 1, 0);
        Class<?> listType = Class.forName("thaumcraft.api.aspects.AspectList");
        Object list = listType.getConstructor()
            .newInstance();
        listType.getMethod("add", Class.forName("thaumcraft.api.aspects.Aspect"), int.class)
            .invoke(list, aspect, amount);
        item.getClass()
            .getMethod("setAspects", ItemStack.class, listType)
            .invoke(item, stack, list);
        return stack;
    }

    private static Object session(EntityPlayerMP actor, NativeSource source, int x, int y, int z, int count)
        throws Exception {
        return session(actor, source, x, y, z, count, source.payload);
    }

    private static Object session(EntityPlayerMP actor, NativeSource source, int x, int y, int z, int count,
        NBTTagCompound capturedPayload) throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramService$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(actor, x, y, z);
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.controllerType = "area";
        preset.deidentificationLevel = 0;
        set(session, "virtual", true);
        set(session, "virtualPreset", preset);
        HologramCapture capture = new HologramCapture(null, actor.getHeldItem());
        set(session, "capture", capture);
        for (int i = 0; i < count; i++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(null, x + i, y, z);
            cell.block = source.block;
            cell.meta = source.metadata;
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(capturedPayload);
            cell.itemNbt = source.sample.hasTagCompound()
                ? HologramBlueprintNbt.encodeCaptured(source.sample.getTagCompound())
                : "";
            cell.blueprintBlock = Block.blockRegistry.getNameForObject(source.block);
            cell.blueprintItem = Item.itemRegistry.getNameForObject(source.sample.getItem());
            cell.blueprintDamage = source.sample.getItemDamage();
            cell.role = "prefab";
            cell.userPosition = true;
            capture.cells.add(cell);
            String key = cell.x + "," + cell.y + "," + cell.z;
            capture.positions.put(key, cell);
            HologramPresets.Cell saved = HologramPresets.cell(source.sample, i, 0, 0, -1, false);
            saved.block = cell.blueprintBlock;
            saved.metadata = cell.meta;
            saved.tileNbt = cell.tileNbt;
            saved.itemNbt = cell.itemNbt;
            preset.cells.add(saved);
            map(session, "pins").put(i, source.sample.copy());
            map(session, "coordinatePins").put(key, source.sample.copy());
            map(session, "autoTargets").put(i, source.sample.copy());
            map(session, "frozenAutoTargets").put(key, source.sample.copy());
            actor.worldObj.getChunkFromBlockCoords(cell.x, cell.z);
            require(actor.worldObj.isAirBlock(cell.x, y, z), "each fixture destination starts empty");
        }
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = false;
        settings.containers = false;
        set(session, "materialSettings", settings);
        actor.setPosition(x + .5, y, z + .5);
        return session;
    }

    private static boolean operate(Object session, HologramCapture.Cell cell) throws Exception {
        invoke("refreshPlan", session);
        return (Boolean) invoke("operate", session, cell, cells(session).indexOf(cell));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, ItemStack> map(Object session, String name) throws Exception {
        return (Map<Object, ItemStack>) field(session, name);
    }

    private static List<HologramCapture.Cell> cells(Object session) throws Exception {
        return ((HologramCapture) field(session, "capture")).cells;
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

    private static Object field(Object owner, String name) throws Exception {
        Field value = owner.getClass()
            .getDeclaredField(name);
        value.setAccessible(true);
        return value.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field target = owner.getClass()
            .getDeclaredField(name);
        target.setAccessible(true);
        target.set(owner, value);
    }

    private static void creative(EntityPlayerMP actor, boolean value) {
        actor.theItemInWorldManager.setGameType(
            value ? net.minecraft.world.WorldSettings.GameType.CREATIVE
                : net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        actor.capabilities.isCreativeMode = value;
        actor.capabilities.allowEdit = true;
    }

    private static void fill(EntityPlayerMP actor, List<ItemStack> payment) {
        for (int i = 1; i < actor.inventory.mainInventory.length; i++) actor.inventory.mainInventory[i] = null;
        int next = 1;
        for (ItemStack stack : payment) {
            boolean merged = false;
            for (int i = 1; i < next; i++) if (actor.inventory.mainInventory[i].isItemEqual(stack)
                && ItemStack.areItemStackTagsEqual(actor.inventory.mainInventory[i], stack)) {
                    actor.inventory.mainInventory[i].stackSize += stack.stackSize;
                    merged = true;
                    break;
                }
            if (!merged) actor.inventory.mainInventory[next++] = stack.copy();
        }
    }

    private static int stock(EntityPlayerMP actor) {
        int total = 0;
        for (int i = 1; i < actor.inventory.mainInventory.length; i++)
            if (actor.inventory.mainInventory[i] != null) total += actor.inventory.mainInventory[i].stackSize;
        return total;
    }

    private static NBTTagList inventory(EntityPlayerMP actor) {
        NBTTagList rows = new NBTTagList();
        for (int i = 1; i < actor.inventory.mainInventory.length; i++) if (actor.inventory.mainInventory[i] != null) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("slot", i);
            actor.inventory.mainInventory[i].writeToNBT(row);
            rows.appendTag(row);
        }
        return rows;
    }

    private static NBTTagList targets(WorldServer world, List<HologramCapture.Cell> cells) {
        NBTTagList rows = new NBTTagList();
        for (HologramCapture.Cell cell : cells) {
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("position", new int[] { cell.x, cell.y, cell.z });
            row.setString("block", Block.blockRegistry.getNameForObject(world.getBlock(cell.x, cell.y, cell.z)));
            row.setInteger("metadata", world.getBlockMetadata(cell.x, cell.y, cell.z));
            TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            if (tile != null) row.setTag("tile", snapshot(tile));
            rows.appendTag(row);
        }
        return rows;
    }

    private static NBTTagCompound base(String name, WorldServer world, NativeSource source, EntityPlayerMP actor) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("scenario", name);
        row.setString(
            "world",
            world.getWorldInfo()
                .getWorldName());
        row.setString("fixtureKind", "native WorldServer; explicitly constructed Service fixture Session");
        row.setString("sourceBlock", Block.blockRegistry.getNameForObject(source.block));
        row.setInteger("sourceMetadata", source.metadata);
        row.setTag("sourceTileBefore", source.payload.copy());
        row.setTag("inventoryBefore", inventory(actor));
        row.setBoolean("creative", actor.capabilities.isCreativeMode);
        return row;
    }

    private static void passed(NBTTagCompound row) throws Exception {
        row.setBoolean("sourceUnchanged", true);
        row.setBoolean("passed", true);
        HologramV011Evidence.write(row.getString("scenario") + ".nbt", row);
        System.out.println("[GTMB-FOLLOWUP-WORLD] PASS " + row.getString("scenario"));
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        return tag;
    }

    private static String nativeCarpenterOwner(EntityPlayerMP actor) throws Exception {
        return Class.forName("com.carpentersblocks.util.protection.ProtectedObject")
            .getConstructor(net.minecraft.entity.player.EntityPlayer.class)
            .newInstance(actor)
            .toString();
    }

    private static boolean carpenter(NativeSource source) {
        return source.block.getClass()
            .getName()
            .startsWith("com.carpentersblocks.block.");
    }

    /** The empty native owner is claimed by the installing actor; the captured source remains untouched. */
    private static NBTTagCompound expectedPlacementPayload(NativeSource source, EntityPlayerMP actor) throws Exception {
        NBTTagCompound expected = (NBTTagCompound) source.payload.copy();
        if (carpenter(source)) {
            require(source.payload.hasKey("cbOwner", 8), "native Carpenter source has a typed persisted owner");
            if (expected.getString("cbOwner")
                .isEmpty()) expected.setString("cbOwner", nativeCarpenterOwner(actor));
            NBTTagCompound sourceWithoutOwner = (NBTTagCompound) source.payload.copy();
            NBTTagCompound expectedWithoutOwner = (NBTTagCompound) expected.copy();
            sourceWithoutOwner.removeTag("cbOwner");
            expectedWithoutOwner.removeTag("cbOwner");
            require(
                sourceWithoutOwner.equals(expectedWithoutOwner),
                "target owner projection changes no other source NBT");
        }
        return expected;
    }

    private static boolean matchesExpectedTarget(NativeSource source, EntityPlayerMP actor, TileEntity actual,
        NBTTagCompound expected) throws Exception {
        if (actual == null) return false;
        if (carpenter(source)) {
            String owner = source.payload.getString("cbOwner");
            if (owner.isEmpty()) owner = nativeCarpenterOwner(actor);
            require(
                expected.getString("cbOwner")
                    .equals(owner),
                "target expectation uses the native actor owner");
            NBTTagCompound actualTag = snapshot(actual);
            if (!actualTag.getString("cbOwner")
                .equals(owner) || !sameNativePersistence(actualTag, expected)) return false;
        }
        return HologramNativePlacement.matchesPlaced(source.block, source.metadata, actual, expected);
    }

    private static boolean sameNativePersistence(NBTTagCompound actual, NBTTagCompound expected) {
        NBTTagCompound actualData = (NBTTagCompound) actual.copy(), expectedData = (NBTTagCompound) expected.copy();
        for (String coordinate : new String[] { "x", "y", "z" }) {
            actualData.removeTag(coordinate);
            expectedData.removeTag(coordinate);
        }
        return actualData.equals(expectedData);
    }

    private static NBTTagList nativeDifferences(NBTTagCompound actual, NBTTagCompound expected) {
        NBTTagList differences = new NBTTagList();
        if (actual == null || expected == null) {
            NBTTagCompound root = new NBTTagCompound();
            root.setString("path", "$");
            root.setBoolean("actualPresent", actual != null);
            root.setBoolean("expectedPresent", expected != null);
            if (actual != null) root.setTag("actual", actual.copy());
            if (expected != null) root.setTag("expected", expected.copy());
            differences.appendTag(root);
            return differences;
        }
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (Object key : actual.func_150296_c()) keys.add((String) key);
        for (Object key : expected.func_150296_c()) keys.add((String) key);
        for (String key : keys) {
            if (key.equals("x") || key.equals("y") || key.equals("z")) continue;
            net.minecraft.nbt.NBTBase actualValue = actual.getTag(key), expectedValue = expected.getTag(key);
            if (actualValue != null && actualValue.equals(expectedValue)) continue;
            NBTTagCompound difference = new NBTTagCompound();
            difference.setString("path", key);
            difference.setBoolean("actualPresent", actualValue != null);
            difference.setBoolean("expectedPresent", expectedValue != null);
            if (actualValue != null) {
                difference.setByte("actualType", actualValue.getId());
                difference.setTag("actual", actualValue.copy());
            }
            if (expectedValue != null) {
                difference.setByte("expectedType", expectedValue.getId());
                difference.setTag("expected", expectedValue.copy());
            }
            differences.appendTag(difference);
        }
        return differences;
    }

    private static NBTTagList materialCosts(HologramDecorMaterials.Descriptor descriptor) {
        NBTTagList result = new NBTTagList();
        for (HologramDecorMaterials.Cost cost : descriptor.costs) {
            NBTTagCompound row = new NBTTagCompound();
            row.setLong("numerator", cost.numerator);
            row.setLong("denominator", cost.denominator);
            NBTTagCompound prebuilt = new NBTTagCompound(), raw = new NBTTagCompound();
            cost.prebuilt.writeToNBT(prebuilt);
            cost.raw.writeToNBT(raw);
            row.setTag("prebuilt", prebuilt);
            row.setTag("raw", raw);
            NBTTagList alternatives = new NBTTagList();
            for (ItemStack alternative : cost.alternatives) {
                NBTTagCompound item = new NBTTagCompound();
                alternative.writeToNBT(item);
                alternatives.appendTag(item);
            }
            row.setTag("alternatives", alternatives);
            result.appendTag(row);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static NBTTagList plannedPayments(Object session) throws Exception {
        NBTTagList result = new NBTTagList();
        for (ItemStack payment : (List<ItemStack>) field(session, "decorPlanItems")) {
            NBTTagCompound item = new NBTTagCompound();
            payment.writeToNBT(item);
            result.appendTag(item);
        }
        return result;
    }

    private static Block registered(String type) {
        for (Object entry : Block.blockRegistry) if (entry instanceof Block && entry.getClass()
            .getName()
            .equals(type)) return (Block) entry;
        throw new AssertionError("missing real native block " + type);
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }

    private static final class NativeSource {

        final Block block;
        final TileEntity tile;
        final ItemStack sample;
        final NBTTagCompound payload;
        final int metadata, x, y, z;

        NativeSource(WorldServer world, Block block, int x, int y, int z) {
            this.block = block;
            this.x = x;
            this.y = y;
            this.z = z;
            tile = world.getTileEntity(x, y, z);
            require(
                world.getBlock(x, y, z) == block && tile != null,
                "real native source block/tile family=" + block.getClass()
                    .getName()
                    + " at="
                    + x
                    + ","
                    + y
                    + ","
                    + z
                    + " actualBlock="
                    + Block.blockRegistry.getNameForObject(world.getBlock(x, y, z))
                    + " actualTile="
                    + (tile == null ? "null"
                        : tile.getClass()
                            .getName()));
            payload = snapshot(tile);
            metadata = world.getBlockMetadata(x, y, z);
            sample = HologramArea.serverSample(block, metadata, tile);
        }

        void assertUnchanged(WorldServer world) {
            require(
                world.getBlock(x, y, z) == block && world.getBlockMetadata(x, y, z) == metadata
                    && world.getTileEntity(x, y, z) == tile
                    && payload.equals(snapshot(tile)),
                "actual source identity and complete NBT unchanged");
        }
    }

    public static final class Veto {

        final HologramCapture.Cell cell;
        final NBTTagCompound expected;
        final NativeSource source;
        final NBTTagList observations = new NBTTagList();
        final boolean allowEarlyJarShell;
        int events;
        int shellEvents, completeEvents, cancelledEvents, unexpectedEvents;
        boolean complete;

        Veto(HologramCapture.Cell cell, NBTTagCompound expected, NativeSource source) {
            this(cell, expected, source, false);
        }

        Veto(HologramCapture.Cell cell, NBTTagCompound expected, NativeSource source, boolean allowEarlyJarShell) {
            this.cell = cell;
            this.expected = expected;
            this.source = source;
            this.allowEarlyJarShell = allowEarlyJarShell;
        }

        @SubscribeEvent
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.x != cell.x || event.y != cell.y || event.z != cell.z) return;
            events++;
            TileEntity tile = event.world.getTileEntity(event.x, event.y, event.z);
            NBTTagCompound observed = tile == null ? new NBTTagCompound() : snapshot(tile);
            boolean nativeMatch = tile != null && (HologramEssentia.isJar(expected) ? tile.getClass()
                .equals(source.tile.getClass()) && HologramEssentia.isJar(observed)
                && observed.hasKey("Amount", 2)
                && observed.getString("Aspect")
                    .equals(expected.getString("Aspect"))
                && snapshot(tile).getShort("Amount") == expected.getShort("Amount")
                : HologramNativePlacement.matchesPlaced(cell.block, cell.meta, tile, expected));
            boolean ownerMatch = !carpenter(source) || observed.hasKey("cbOwner", 8) && observed.getString("cbOwner")
                .equals(expected.getString("cbOwner"));
            boolean completePersistence = !carpenter(source) || sameNativePersistence(observed, expected);
            complete = nativeMatch && ownerMatch
                && completePersistence
                && event.world.getBlock(event.x, event.y, event.z) == cell.block
                && event.world.getBlockMetadata(event.x, event.y, event.z) == cell.meta;
            boolean cancel = true;
            String phase = complete ? "complete-service-target" : "unexpected-target";
            if (allowEarlyJarShell && !complete
                && tile != null
                && tile.getClass()
                    .equals(source.tile.getClass())
                && HologramEssentia.isJar(observed)
                && observed.hasKey("Amount", 2)
                && observed.getShort("Amount") == 0
                && observed.getString("Aspect")
                    .isEmpty()
                && event.world.getBlock(event.x, event.y, event.z) == cell.block
                && event.world.getBlockMetadata(event.x, event.y, event.z) == cell.meta
                && shellEvents == 0
                && completeEvents == 0) {
                shellEvents++;
                cancel = false;
                phase = "early-native-empty-shell";
            } else if (complete) {
                if (allowEarlyJarShell && (shellEvents != 1 || completeEvents != 0)) unexpectedEvents++;
                completeEvents++;
            } else unexpectedEvents++;
            if (cancel) cancelledEvents++;
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setIntArray("position", new int[] { event.x, event.y, event.z });
            receipt.setInteger("dimension", event.world.provider.dimensionId);
            receipt.setString(
                "world",
                event.world.getWorldInfo()
                    .getWorldName());
            receipt.setString(
                "block",
                Block.blockRegistry.getNameForObject(event.world.getBlock(event.x, event.y, event.z)));
            receipt.setInteger("metadata", event.world.getBlockMetadata(event.x, event.y, event.z));
            receipt.setTag("nativeTile", observed);
            receipt.setString(
                "nativeTileClass",
                tile == null ? "null"
                    : tile.getClass()
                        .getName());
            receipt.setTag("expectedTile", expected.copy());
            receipt.setBoolean("nativeMatch", nativeMatch);
            receipt.setBoolean("ownerMatch", ownerMatch);
            receipt.setBoolean("completePersistenceMatch", completePersistence);
            receipt.setBoolean("complete", complete);
            receipt.setString("phase", phase);
            receipt.setBoolean("cancelled", cancel);
            receipt.setString("expectedOwner", expected.getString("cbOwner"));
            receipt.setString("actualOwner", observed.getString("cbOwner"));
            receipt.setIntArray("sourcePosition", new int[] { source.x, source.y, source.z });
            TileEntity currentSource = event.world.getTileEntity(source.x, source.y, source.z);
            if (currentSource != null) receipt.setTag("sourceTileAtEvent", snapshot(currentSource));
            receipt.setBoolean("sourceIdentityUnchanged", currentSource == source.tile);
            receipt.setTag("inventoryDuring", inventory((EntityPlayerMP) event.player));
            if (allowEarlyJarShell) receipt.setInteger(
                "localSourceAspectDuring",
                HologramEssentia.jarAmount(event.player.inventory.mainInventory[2], expected.getString("Aspect")));
            receipt.setString(
                "actorUuid",
                event.player.getUniqueID()
                    .toString());
            observations.appendTag(receipt);
            if (cancel) event.setCanceled(true);
        }
    }
}
