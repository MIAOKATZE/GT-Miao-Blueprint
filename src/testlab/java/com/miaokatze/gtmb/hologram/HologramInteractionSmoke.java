package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.io.FileOutputStream;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Dedicated-world fixture and independent material/world receipts for the real network GUI driver. */
public final class HologramInteractionSmoke {

    private EntityPlayerMP player;
    private final long startupBudgetNanos = Math
        .max(60L, Math.min(3600L, Long.getLong("gtmb.hologram.nativeStartupSeconds", 1800L))) * 1000000000L;
    private long startupStarted;
    private File receipts;
    private boolean failed;
    private int ticks, lastPlaced = -1, lastStock = -1;
    private boolean sawBuilt, sawUpgraded;
    private int receiptPublishRetries;
    private int mufflerId = -1, lastMufflerWorld, lastMufflerStock;
    private final java.util.Map<String, int[]> blueprintTargets = new java.util.LinkedHashMap<>();
    private boolean sufficientBlueprintJobStarted;
    private String observedStoneSession;
    private boolean previousStoneLocked, previousStoneReviewIssued;
    private int stoneWorldLocks, stoneWorldUnlocks, stoneWorldReviews;
    private boolean sawOneToOneCraftPreview;
    private int craftingEvents;
    private HologramAeInteractionServerProbe aeProbe;
    private boolean aeCancelledVerified, aeConfirmedVerified, aePaidStarted, aeCompleted, aeVirtualStarted;
    private int aePlaceEvents;
    private long aeFinishedWorldTick = -1;
    private long aeSourceCreatedWorldTick;
    private boolean aeSourcePrepared;
    private boolean aeBusyStarted, aeBusyCancelledVerified;
    private boolean aePaidJobRecorded;
    private java.util.Map<String, Integer> aeBeforePaid, aePaidCosts;

    public static void registerIfEnabled() {
        if (Boolean.getBoolean("gtmb.hologram.interactionServer")) {
            HologramInteractionSmoke fixture = new HologramInteractionSmoke();
            FMLCommonHandler.instance()
                .bus()
                .register(fixture);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(fixture);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (failed || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = MinecraftServer.getServer();
        try {
            long now = System.nanoTime();
            if (player == null) {
                if (startupStarted == 0) startupStarted = now;
                if (now - startupStarted > startupBudgetNanos)
                    throw new IllegalStateException("real interaction real-client startup timeout");
            } else if (++ticks > 18000) throw new IllegalStateException("real interaction timeout");
            WorldServer world = server.worldServerForDimension(0);
            if (!world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-interaction-")) throw new IllegalStateException("isolated world guard");
            if (receipts == null) {
                receipts = new File(System.getProperty("gtmb.hologram.interactionReceiptDir", ""));
                if (!receipts.isDirectory()) throw new IllegalStateException("prepared receipt directory required");
            }
            File clientFailure = new File(receipts, "client-failed.txt");
            if (clientFailure.isFile()) throw new IllegalStateException(
                "real interaction client failed: " + new String(
                    java.nio.file.Files.readAllBytes(clientFailure.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (player == null) {
                if (server.getConfigurationManager().playerEntityList.isEmpty()) return;
                player = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
                BaseMetaTileEntity controller = createFixture(world, player, 48, 80, 48);
                mufflerId = installMuffler(world, player, controller, 48, 80, 48);
                player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
                player.capabilities.allowFlying = true;
                player.capabilities.isFlying = true;
                player.sendPlayerAbilities();
                player.playerNetServerHandler.setPlayerLocation(48.5, 80, 46.5, 0, 0);
                for (int i = 0; i < player.inventory.mainInventory.length; i++)
                    player.inventory.mainInventory[i] = null;
                player.inventory.mainInventory[0] = new ItemStack(
                    com.gtnewhorizon.structurelib.StructureLibAPI.getDefaultHologramItem());
                player.inventory.mainInventory[1] = new ItemStack(GregTechAPI.sBlockCasings1, 64, 11);
                for (int tier = 0; tier < 8; tier++)
                    player.inventory.mainInventory[tier + 2] = new ItemStack(GregTechAPI.sBlockCasings5, 16, tier);
                player.inventory.currentItem = 0;
                world.setBlock(50, 80, 44, net.minecraft.init.Blocks.stone, 0, 3);
                world.setBlock(51, 80, 44, net.minecraft.init.Blocks.stone, 0, 3);
                player.inventory.mainInventory[10] = new ItemStack(net.minecraft.init.Blocks.stone, 2);
                player.inventoryContainer.detectAndSendChanges();
                // Deliberately do not collect here: the client's first C08 must exercise the cold capture path.
                receipt(world, "fixture");
                return;
            }
            File complete = new File(receipts, "client-complete.txt");
            if (Boolean.getBoolean("gtmb.hologram.aeRealInteractionSmoke")) aeTick(world);
            if (complete.isFile()) {
                if (!sawBuilt || !sawUpgraded
                    || lastPlaced != 0
                    || lastStock != 192
                    || lastMufflerWorld != 0
                    || lastMufflerStock != 1)
                    throw new AssertionError(
                        "client completion requires independent built/upgraded/recovered world observations");
                if (Boolean.getBoolean("gtmb.hologram.aeRealInteractionSmoke") && !aeCompleted)
                    throw new AssertionError("AE completion requires native paid/source/inventory witnesses");
                if (!receipt(world, "final")) return;
                System.out.println(
                    "[GTMB-HOLOGRAM-INTERACTION-SERVER] FINAL PASS real-client completion; independent world/material receipts saved");
                failed = true;
                server.initiateShutdown();
                return;
            }
            receipt(world, "live");
        } catch (Throwable failure) {
            failed = true;
            System.err.println("[GTMB-HOLOGRAM-INTERACTION-SERVER] FINAL FAIL " + failure);
            failure.printStackTrace();
            server.initiateShutdown();
        }
    }

    private boolean receipt(WorldServer world, String stage) throws Exception {
        int placed = 0, coils = 0, casings = 0, stock = 0, highCoils = 0, mufflerWorld = 0, mufflerStock = 0;
        // EBF extends at most three blocks from its controller. This fixed box is independent of capture output.
        for (int x = 45; x <= 51; x++) for (int y = 77; y <= 83; y++) for (int z = 45; z <= 51; z++) {
            if (world.getBlock(x, y, z) == GregTechAPI.sBlockCasings5) {
                coils++;
                placed++;
                if (world.getBlockMetadata(x, y, z) > 0) highCoils++;
            }
            if (world.getBlock(x, y, z) == GregTechAPI.sBlockCasings1) {
                casings++;
                placed++;
            }
            if (world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity
                && ((BaseMetaTileEntity) world.getTileEntity(x, y, z)).getMetaTileID() == mufflerId) mufflerWorld++;
        }
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack == null) continue;
            if (stack.getItem() == Item.getItemFromBlock(GregTechAPI.sBlockCasings5)
                || stack.getItem() == Item.getItemFromBlock(GregTechAPI.sBlockCasings1)) stock += stack.stackSize;
            if (stack.getItem() == Item.getItemFromBlock(GregTechAPI.sBlockMachines)
                && stack.getItemDamage() == mufflerId) mufflerStock += stack.stackSize;
        }
        // Conservation is checked from actual server world and inventory, independent of GUI state.
        if (stock + placed != 64 + 8 * 16)
            throw new AssertionError("material conservation stock=" + stock + " placed=" + placed);
        if (mufflerWorld + mufflerStock != 1) throw new AssertionError(
            "mandatory muffler conservation world=" + mufflerWorld + " inventory=" + mufflerStock);
        if (coils == 16) sawBuilt = true;
        if (highCoils == 16) sawUpgraded = true;
        // Upgrades keep total counts constant; still publish metadata observations every tick.
        lastPlaced = placed;
        lastStock = stock;
        lastMufflerWorld = mufflerWorld;
        lastMufflerStock = mufflerStock;
        NBTTagCompound n = new NBTTagCompound();
        n.setString("stage", stage);
        n.setInteger("placed", placed);
        n.setInteger("coils", coils);
        n.setInteger("highCoils", highCoils);
        n.setInteger("casings", casings);
        n.setInteger("stock", stock);
        n.setInteger("mufflerWorld", mufflerWorld);
        n.setInteger("mufflerStock", mufflerStock);
        observeCrafting(n);
        observeBlueprintSession(n);
        int blueprintPlaced = 0, blueprintStock = 0;
        for (int x = 50; x <= 51; x++) {
            if (world.getBlock(x, 80, 44) != net.minecraft.init.Blocks.stone)
                throw new AssertionError("area blueprint capture changed its real source block");
        }
        net.minecraft.nbt.NBTTagList sourceFacts = new net.minecraft.nbt.NBTTagList();
        for (int x = 50; x <= 51; x++) sourceFacts.appendTag(actualBlockFact(world, x, 80, 44));
        n.setTag("blueprintSourceCells", sourceFacts);
        n.setBoolean("blueprintSourceIntact", true);
        net.minecraft.nbt.NBTTagList paidFacts = new net.minecraft.nbt.NBTTagList();
        for (int[] target : blueprintTargets.values()) {
            paidFacts.appendTag(actualBlockFact(world, target[0], target[1], target[2]));
            if (world.getBlock(target[0], target[1], target[2]) == net.minecraft.init.Blocks.stone) blueprintPlaced++;
        }
        n.setTag("blueprintPaidCells", paidFacts);
        for (ItemStack stack : player.inventory.mainInventory)
            if (stack != null && stack.getItem() == Item.getItemFromBlock(net.minecraft.init.Blocks.stone))
                blueprintStock += stack.stackSize;
        if (blueprintPlaced + blueprintStock != 2) throw new AssertionError(
            "blueprint material conservation placed=" + blueprintPlaced + " stock=" + blueprintStock);
        if ("final".equals(stage) && (blueprintPlaced != 2 || !sufficientBlueprintJobStarted)) throw new AssertionError(
            "final receipt requires single-confirm sufficient and explicitly confirmed partial blueprint construction");
        if ("final".equals(stage) && (stoneWorldLocks < 6 || stoneWorldUnlocks < 3 || stoneWorldReviews < 3))
            throw new AssertionError(
                "v007 final receipt requires independent first-lock/unlock/relock/RIGHT2 observations");
        n.setInteger("blueprintPlaced", blueprintPlaced);
        n.setInteger("blueprintStock", blueprintStock);
        n.setBoolean("sufficientBlueprintJobStarted", sufficientBlueprintJobStarted);
        n.setInteger("stoneWorldLocks", stoneWorldLocks);
        n.setInteger("stoneWorldUnlocks", stoneWorldUnlocks);
        n.setInteger("stoneWorldReviews", stoneWorldReviews);
        if (aeProbe != null && aeSourcePrepared) {
            n.setBoolean("aeReady", true);
            n.setBoolean("aeBusyStarted", aeBusyStarted);
            n.setBoolean("aeBusyCancelledVerified", aeBusyCancelledVerified);
            n.setBoolean("aeVirtualEpoch", aeVirtualStarted);
            n.setBoolean("aeCancelledVerified", aeCancelledVerified);
            n.setBoolean("aeConfirmedVerified", aeConfirmedVerified);
            n.setBoolean("aePaidStarted", aePaidStarted);
            n.setBoolean("aeCompleted", aeCompleted);
            n.setInteger("aePlaceEvents", aePlaceEvents);
            n.setTag("aeEvidence", aeProbe.receipt());
            n.setTag("aeFixtureInventory", player.inventory.writeToNBT(new net.minecraft.nbt.NBTTagList()));
            HologramAeBlueprintSmoke.Fixtures f = aeProbe.fixtures();
            n.setInteger("aeSourceCount", f.sourceCount);
            n.setIntArray("aeSourceX", f.sourceX.clone());
            n.setInteger("aeSourceY", f.y);
            n.setInteger("aeSourceZ", f.z);
            net.minecraft.nbt.NBTTagList sources = new net.minecraft.nbt.NBTTagList();
            for (int i = 0; i < f.sourceCount; i++) {
                if (world.getBlock(f.sourceX[i], f.y, f.z) != f.block(i))
                    throw new AssertionError("native AE source block changed at " + i);
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("x", f.sourceX[i]);
                row.setInteger("y", f.y);
                row.setInteger("z", f.z);
                row.setString(
                    "block",
                    String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(f.block(i))));
                row.setInteger("metadata", world.getBlockMetadata(f.sourceX[i], f.y, f.z));
                row.setString(
                    "tileClass",
                    f.sourceTile(i)
                        .getClass()
                        .getName());
                sources.appendTag(row);
            }
            n.setTag("aeActualSourceCells", sources);
            java.lang.reflect.Field areaMap = HologramWorkspace.class.getDeclaredField("AREAS");
            areaMap.setAccessible(true);
            HologramArea area = (HologramArea) ((java.util.Map<?, ?>) areaMap.get(null)).get(player.getUniqueID());
            if (area != null) {
                NBTTagCompound observed = new NBTTagCompound();
                observed.setBoolean("selecting", area.selecting);
                observed.setBoolean("importing", area.importing);
                observed.setBoolean("imported", area.imported);
                observed.setIntArray("points", area.points());
                observed.setIntArray(
                    "bounds",
                    new int[] { area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ });
                observed.setInteger("blueprintCells", area.blueprint == null ? 0 : area.blueprint.cells.size());
                Object actualSession = interactionSession();
                if (actualSession != null) {
                    observed.setString("session", String.valueOf(sessionField(actualSession, "id")));
                    observed.setString("status", String.valueOf(sessionField(actualSession, "status")));
                }
                n.setTag("aeActualArea", observed);
            }
            n.setTag(
                "aePatternSample",
                f.sourcePattern()
                    .writeToNBT(new NBTTagCompound()));
            n.setTag(
                "aeCardSample",
                f.sourceCard()
                    .writeToNBT(new NBTTagCompound()));
            n.setTag(
                "aeFullCellSample",
                f.sourceCell()
                    .writeToNBT(new NBTTagCompound()));
            n.setTag(
                "aeBlankPatternSample",
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .blankPattern()
                    .maybeStack(1)
                    .get()
                    .writeToNBT(new NBTTagCompound()));
            for (HologramAeBlueprint.Slot slot : HologramAeBlueprint.analyze(f.drive, 0, f.captured(0)).slots)
                if ("inv/item0".equals(slot.id)) n.setTag(
                    "aeEmptyCellMark",
                    slot.stack()
                        .writeToNBT(new NBTTagCompound()));
        }
        n.setLong("tick", world.getTotalWorldTime());
        n.setInteger(
            "facing",
            ((BaseMetaTileEntity) world.getTileEntity(48, 80, 48)).getFrontFacing()
                .ordinal());
        File temp = new File(receipts, "world-receipt.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            CompressedStreamTools.writeCompressed(n, out);
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(),
                new File(receipts, "world-receipt.nbt").toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            receiptPublishRetries = 0;
        } catch (java.nio.file.FileSystemException locked) {
            java.nio.file.Path sourceReceipt = temp.toPath()
                .toAbsolutePath()
                .normalize();
            java.nio.file.Path targetReceipt = new File(receipts, "world-receipt.nbt").toPath()
                .toAbsolutePath()
                .normalize();
            boolean receiptAccessDenied = locked instanceof java.nio.file.AccessDeniedException
                && locked.getFile() != null
                && locked.getOtherFile() != null
                && sourceReceipt.equals(
                    java.nio.file.Paths.get(locked.getFile())
                        .toAbsolutePath()
                        .normalize())
                && targetReceipt.equals(
                    java.nio.file.Paths.get(locked.getOtherFile())
                        .toAbsolutePath()
                        .normalize());
            String reason = String.valueOf(locked.getReason())
                .toLowerCase(java.util.Locale.ROOT);
            boolean sharingViolation = receiptAccessDenied || reason.contains("另一个程序")
                || reason.contains("another process")
                || reason.contains("being used")
                || reason.contains("sharing violation");
            if (!sharingViolation || ++receiptPublishRetries > 20) throw locked;
            if (receiptPublishRetries == 1) System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-SERVER] receipt publication pending: " + locked.getClass()
                    .getSimpleName() + " reason=" + locked.getReason() + "; bounded 20 tick retry");
            // Retry publication with a fresh snapshot on the next tick. World assertion failures are never retried.
            return false;
        }
        if (!"live".equals(stage) || world.getTotalWorldTime() % 40 == 0) System.out.println(
            "[GTMB-HOLOGRAM-INTERACTION-SERVER] RECEIPT stage=" + stage
                + " placed="
                + placed
                + " highCoils="
                + highCoils
                + " stock="
                + stock
                + " mufflerWorld="
                + mufflerWorld
                + " mufflerStock="
                + mufflerStock);
        return true;
    }

    private void aeTick(WorldServer world) throws Exception {
        if (aeProbe == null) {
            if (!new File(receipts, "ae-start.txt").isFile()) return;
            if (!sawBuilt || !sawUpgraded || lastPlaced != 0 || lastStock != 192)
                throw new AssertionError("AE phase must follow the complete real v004 craft/build/recovery flow");
            aeProbe = new HologramAeInteractionServerProbe(
                world,
                player,
                java.nio.file.Paths.get(".")
                    .toAbsolutePath()
                    .normalize(),
                Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"));
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(aeProbe);
            aeSourceCreatedWorldTick = world.getTotalWorldTime();
            return;
        }
        if (!aeSourcePrepared) {
            if (world.getTotalWorldTime() - aeSourceCreatedWorldTick < 2) return;
            aeProbe.completeSourcePreparation();
            for (ItemStack cost : aeProbe.initialCosts()) addAeFixtureItem(cost);
            addAeFixtureItem(
                aeProbe.fixtures()
                    .sourceCell());
            addAeFixtureItem(
                aeProbe.fixtures()
                    .sourcePattern());
            addAeFixtureItem(
                aeProbe.fixtures()
                    .sourceCard());
            addAeFixtureItem(
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .blankPattern()
                    .maybeStack(1)
                    .get());
            player.playerNetServerHandler.setPlayerLocation(650.5, 88, 189.5, 0, 0);
            player.inventoryContainer.detectAndSendChanges();
            aeSourcePrepared = true;
            return;
        }
        if (!aeBusyStarted && new File(receipts, "ae-busy-visible.txt").isFile()) {
            aeProbe.beginVirtualEpoch();
            aeBusyStarted = true;
        }
        if (aeBusyStarted && !aeBusyCancelledVerified) {
            aeProbe.assertVirtualEpoch("actual natural import busy/cancel inputs");
            if (new File(receipts, "ae-busy-check-cancel.txt").isFile()) {
                Object cancelled = interactionSession();
                java.lang.reflect.Field areas = HologramWorkspace.class.getDeclaredField("AREAS");
                areas.setAccessible(true);
                HologramArea currentArea = (HologramArea) ((java.util.Map<?, ?>) areas.get(null))
                    .get(player.getUniqueID());
                if (cancelled == null || (Boolean) sessionField(cancelled, "open")
                    || currentArea != null && (currentArea.importing || currentArea.selecting))
                    throw new AssertionError("actual E cancellation did not end the native asynchronous import");
                aeProbe.assertCancelledSave("cancelled actual busy import leaves disk/source/inventory intact");
                aeProbe.endVirtualEpoch();
                aeBusyCancelledVerified = true;
            }
        }
        if (!aeVirtualStarted && new File(receipts, "ae-virtual-begin.txt").isFile()) {
            aeProbe.beginVirtualEpoch();
            aeVirtualStarted = true;
        }
        if (aeVirtualStarted && !aePaidStarted) aeProbe.assertVirtualEpoch("actual AE virtual inputs");
        else aeProbe.assertSourceUnchanged("actual AE source inputs");
        if (!aeCancelledVerified && new File(receipts, "ae-check-cancel.txt").isFile()) {
            aeProbe.assertCancelledSave("actual warning/blocker cancellation");
            aeCancelledVerified = true;
        }
        if (!aeConfirmedVerified && new File(receipts, "ae-check-confirm.txt").isFile()) {
            aeProbe.assertConfirmedSaveChangedLibrary("actual exact nonce confirmation");
            aeConfirmedVerified = true;
        }
        Object session = interactionSession();
        if (!aePaidStarted && new File(receipts, "ae-paid-begin.txt").isFile()) {
            if (!aeCancelledVerified || !aeConfirmedVerified || session == null)
                throw new AssertionError("paid AE placement requires both independent save witnesses");
            aeProbe.endVirtualEpoch();
            aeBeforePaid = aeInventory();
            aePaidCosts = new java.util.LinkedHashMap<>();
            HologramCapture capture = (HologramCapture) sessionField(session, "capture");
            aeProbe.freezePaidReview(session, capture);
            for (HologramCapture.Cell cell : capture.cells)
                if (HologramAePolicy.handles(cell.block, cell.tileNbt)) for (ItemStack cost : HologramAeBlueprint
                    .resourcePlan(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt), null).items)
                    aePaidCosts.merge(aeItemKey(cost), cost.stackSize, Integer::sum);
            if (aePaidCosts.isEmpty()) throw new AssertionError("AE paid plan has no real components");
            aePaidStarted = true;
        }
        if (aePaidStarted && session != null
            && (Boolean) sessionField(session, "virtualLocked")
            && ((Integer) sessionField(session, "job") == 3 || (Integer) sessionField(session, "job") == 5)) {
            if (aeFinishedWorldTick < 0) aeFinishedWorldTick = world.getTotalWorldTime();
            // AE ordinary tiles ready from their queued native END event, after the service's placement tick.
            // Wait for two genuine world ticks; assertion never creates a node or invokes ready manually.
            if (world.getTotalWorldTime() - aeFinishedWorldTick < 2) return;
            HologramCapture capture = (HologramCapture) sessionField(session, "capture");
            int nativePlaced = 0;
            if (!aePaidJobRecorded) {
                aeProbe
                    .recordPaidJobState(session, capture, aeBeforePaid, aePaidCosts, aePlaceEvents, receipts.toPath());
                aePaidJobRecorded = true;
            }
            for (HologramCapture.Cell cell : capture.cells) if (HologramAePolicy.handles(cell.block, cell.tileNbt)) {
                if (world.getBlock(cell.x, cell.y, cell.z) != cell.block)
                    throw new AssertionError("actual AE job ended without installing " + cell.block);
                if (cell.x >= 648 && cell.x <= 660 && cell.y == 88 && cell.z == 192)
                    throw new AssertionError("AE placement overlaps its native source");
                NBTTagCompound reviewedExpected = aeProbe.recordPlacedComparison(cell, nativePlaced, receipts.toPath());
                HologramAeBlueprintSmoke.assertPlaced(
                    cell.block,
                    cell.meta,
                    world.getTileEntity(cell.x, cell.y, cell.z),
                    reviewedExpected,
                    player);
                nativePlaced++;
            }
            if (nativePlaced != aeProbe.fixtures().sourceCount || aePlaceEvents != nativePlaced)
                throw new AssertionError("AE native/PlaceEvent count mismatch " + nativePlaced + "/" + aePlaceEvents);
            java.util.Map<String, Integer> after = aeInventory();
            java.util.Set<String> keys = new java.util.LinkedHashSet<>(aeBeforePaid.keySet());
            keys.addAll(after.keySet());
            keys.addAll(aePaidCosts.keySet());
            for (String key : keys)
                if (aeBeforePaid.getOrDefault(key, 0) - after.getOrDefault(key, 0) != aePaidCosts.getOrDefault(key, 0))
                    throw new AssertionError("AE paid item delta differs from exact reviewed cost");
            aeProbe.assertSourceUnchanged("completed real paid AE job");
            if (!aeCompleted) System.out.println(
                "[GTMB-HOLOGRAM-AE-INTERACTION-SERVER] FINAL PASS native=" + nativePlaced
                    + " actualPlaceEvents="
                    + aePlaceEvents
                    + " exactCosts="
                    + aePaidCosts.size());
            aeCompleted = true;
        }
    }

    private void addAeFixtureItem(ItemStack source) {
        ItemStack copy = source.copy();
        if (!player.inventory.addItemStackToInventory(copy) || copy.stackSize > 0)
            throw new AssertionError("isolated AE initial fixture inventory has insufficient slots");
    }

    private Object interactionSession() throws Exception {
        java.lang.reflect.Field sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        return ((java.util.Map<?, ?>) sessions.get(null)).get(player.getUniqueID());
    }

    private java.util.Map<String, Integer> aeInventory() throws Exception {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (ItemStack item : player.inventory.mainInventory) if (item != null && item != player.getHeldItem())
            result.merge(aeItemKey(item), item.stackSize, Integer::sum);
        return result;
    }

    private static String aeItemKey(ItemStack item) throws Exception {
        NBTTagCompound tag = (NBTTagCompound) item.writeToNBT(new NBTTagCompound())
            .copy();
        tag.removeTag("Count");
        // Compound iteration order is not item identity. Reuse the pure typed wire writer without
        // encodeCanonical's registry projection; every nested field, numeric type and list order remains exact.
        java.lang.reflect.Method canonical = HologramBlueprintNbt.class.getDeclaredMethod(
            "writeCanonicalPayload",
            net.minecraft.nbt.NBTBase.class,
            java.io.DataOutput.class,
            int.class);
        canonical.setAccessible(true);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
            output.writeByte(10);
            output.writeUTF("");
            canonical.invoke(null, tag, output, 0);
        }
        return java.util.Base64.getEncoder()
            .encodeToString(bytes.toByteArray());
    }

    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST)
    public void aePlaced(net.minecraftforge.event.world.BlockEvent.PlaceEvent event) throws Exception {
        if (!aePaidStarted || event.player != player) return;
        Object session = interactionSession();
        if (session == null || (Integer) sessionField(session, "job") != 1)
            throw new AssertionError("AE native PlaceEvent occurred outside the actual reviewed server job");
        HologramCapture capture = (HologramCapture) sessionField(session, "capture");
        HologramCapture.Cell cell = capture.positions.get(event.x + "," + event.y + "," + event.z);
        if (cell != null && HologramAePolicy.handles(cell.block, cell.tileNbt)) aePlaceEvents++;
    }

    /** Derived crafting output is not an owned item until the real C0E pickup consumes its input. */
    private void observeCrafting(NBTTagCompound receipt) {
        Item official = com.gtnewhorizon.structurelib.StructureLibAPI.getDefaultHologramItem();
        Item projector = (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector");
        int officialCount = 0, projectorCount = 0, officialInput = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            officialCount += countItem(stack, official);
            projectorCount += countItem(stack, projector);
        }
        ItemStack cursor = player.inventory.getItemStack();
        officialCount += countItem(cursor, official);
        projectorCount += countItem(cursor, projector);
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack input = player.inventoryContainer.getSlot(slot)
                .getStack();
            if (input != null && input.getItem() != official)
                throw new AssertionError("projector crafting requires only its one official source item");
            officialInput += countItem(input, official);
        }
        officialCount += officialInput;
        ItemStack output = player.inventoryContainer.getSlot(0)
            .getStack();
        boolean outputReady = output != null && output.getItem() == projector && output.stackSize == 1;
        if (officialInput == 1 && outputReady) sawOneToOneCraftPreview = true;
        if (officialCount + projectorCount != 1)
            throw new AssertionError("projector conservation source=" + officialCount + " crafted=" + projectorCount);
        if (craftingEvents == 0 && projectorCount != 0)
            throw new AssertionError("GTMB projector appeared without actual server ItemCraftedEvent");
        if (craftingEvents == 1 && (officialCount != 0 || projectorCount != 1 || officialInput != 0))
            throw new AssertionError("one-to-one server crafting must consume source and keep exactly one projector");
        receipt.setInteger("craftingEvents", craftingEvents);
        receipt.setBoolean("oneToOneCraftPreview", sawOneToOneCraftPreview);
        receipt.setBoolean("craftOutputReady", outputReady);
        receipt.setInteger("officialCraftInput", officialInput);
        receipt.setInteger("officialRemaining", officialCount);
        receipt.setInteger("craftedProjectors", projectorCount);
        if ("final".equals(receipt.getString("stage")) && (!sawOneToOneCraftPreview || craftingEvents != 1))
            throw new AssertionError("final socket receipt requires actual one-to-one 2x2 crafting");
    }

    @SubscribeEvent
    public void crafted(cpw.mods.fml.common.gameevent.PlayerEvent.ItemCraftedEvent event) {
        if (event.player != player) return;
        Item projector = (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector");
        if (event.crafting == null || event.crafting.getItem() != projector || event.crafting.stackSize != 1)
            throw new AssertionError("socket player must craft exactly one registered GTMB projector");
        if (!sawOneToOneCraftPreview || ++craftingEvents != 1) throw new AssertionError(
            "actual crafting event requires the observed one-source/one-output recipe exactly once");
        System.out.println(
            "[GTMB-HOLOGRAM-INTERACTION-SERVER] PASS actual ItemCraftedEvent official source 1 -> GTMB projector 1");
    }

    private static int countItem(ItemStack stack, Item item) {
        return stack != null && stack.getItem() == item ? stack.stackSize : 0;
    }

    private static NBTTagCompound actualBlockFact(WorldServer world, int x, int y, int z) {
        NBTTagCompound fact = new NBTTagCompound();
        fact.setInteger("x", x);
        fact.setInteger("y", y);
        fact.setInteger("z", z);
        fact.setString(
            "id",
            String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(x, y, z))));
        fact.setInteger("meta", world.getBlockMetadata(x, y, z));
        return fact;
    }

    /** Observe authoritative locked capture coordinates; client claims never choose the receipt's world cells. */
    private void observeBlueprintSession(NBTTagCompound receipt) throws Exception {
        java.lang.reflect.Field sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        Object session = ((java.util.Map<?, ?>) sessions.get(null)).get(player.getUniqueID());
        if (session == null) return;
        NBTTagCompound observed = new NBTTagCompound();
        observed.setString("id", String.valueOf(sessionField(session, "id")));
        observed.setString(
            "actor",
            player.getUniqueID()
                .toString());
        observed.setString("status", String.valueOf(sessionField(session, "status")));
        observed.setString("libraryLocalId", String.valueOf(sessionField(session, "libraryLocalId")));
        HologramPresets.Preset observedPreset = (HologramPresets.Preset) sessionField(session, "virtualPreset");
        if (observedPreset != null && observedPreset.id != null && !observedPreset.id.isEmpty())
            observed.setString("presetId", observedPreset.id);
        for (String name : new String[] { "uiSequence", "planRevision", "placementReviewNonce", "actionTick",
            "positionNudgeTick" }) observed.setLong(name, ((Number) sessionField(session, name)).longValue());
        for (String name : new String[] { "job", "actionCount" })
            observed.setInteger(name, ((Number) sessionField(session, name)).intValue());
        for (String name : new String[] { "virtualPlacement", "virtualLocked", "blueprintAwaitingConfirmation",
            "placementReviewIssued", "forcePlacementReady", "open" })
            observed.setBoolean(name, (Boolean) sessionField(session, name));
        receipt.setTag("blueprintSession", observed);
        if (!((Boolean) sessionField(session, "virtualPlacement"))) return;
        HologramCapture capture = (HologramCapture) sessionField(session, "capture");
        boolean stoneCapture = capture != null && !capture.cells.isEmpty();
        if (capture != null) for (HologramCapture.Cell cell : capture.cells)
            stoneCapture &= cell.block == net.minecraft.init.Blocks.stone;
        boolean locked = (Boolean) sessionField(session, "virtualLocked");
        boolean reviewIssued = (Boolean) sessionField(session, "placementReviewIssued");
        if (stoneCapture) {
            String sessionId = String.valueOf(sessionField(session, "id"));
            boolean sameSession = sessionId.equals(observedStoneSession);
            if (locked && (!sameSession || !previousStoneLocked)) {
                if (reviewIssued) throw new AssertionError("first world lock issued placement review without RIGHT2");
                stoneWorldLocks++;
            }
            if (sameSession && previousStoneLocked && !locked) {
                if (reviewIssued || ((Boolean) sessionField(session, "blueprintAwaitingConfirmation")))
                    throw new AssertionError("world unlock retained placement review or confirmation capability");
                stoneWorldUnlocks++;
            }
            if (reviewIssued && (!sameSession || !previousStoneReviewIssued)) {
                if (!locked || !sameSession || !previousStoneLocked)
                    throw new AssertionError("RIGHT2 review was issued before an independently observed world lock");
                stoneWorldReviews++;
                System.out.println(
                    "[GTMB-HOLOGRAM-INTERACTION-SERVER] PASS v007 authoritative world lock/unlock/relock/RIGHT2 review="
                        + stoneWorldReviews
                        + " locks="
                        + stoneWorldLocks
                        + " unlocks="
                        + stoneWorldUnlocks);
            }
            observedStoneSession = sessionId;
            previousStoneLocked = locked;
            previousStoneReviewIssued = reviewIssued;
        }
        if (!locked) return;
        receipt.setIntArray(
            "blueprintAnchor",
            new int[] { (Integer) sessionField(session, "x"), (Integer) sessionField(session, "y"),
                (Integer) sessionField(session, "z") });
        if (capture == null) throw new AssertionError("locked blueprint session lacks complete capture");
        for (HologramCapture.Cell cell : capture.cells) {
            if (cell.block != net.minecraft.init.Blocks.stone) continue;
            if ((cell.x == 50 || cell.x == 51) && cell.y == 80 && cell.z == 44)
                throw new AssertionError("air placement target overlaps its captured source");
            blueprintTargets.put(cell.x + "," + cell.y + "," + cell.z, new int[] { cell.x, cell.y, cell.z });
        }
        receipt.setInteger("blueprintObservedTargetCount", blueprintTargets.size());
    }

    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST)
    public void blueprintPlaced(net.minecraftforge.event.world.BlockEvent.PlaceEvent event) throws Exception {
        if (player == null || event.player != player
            || event.world.getBlock(event.x, event.y, event.z) != net.minecraft.init.Blocks.stone) return;
        java.lang.reflect.Field sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        Object session = ((java.util.Map<?, ?>) sessions.get(null)).get(player.getUniqueID());
        if (session == null || !((Boolean) sessionField(session, "virtualPlacement"))
            || !((Boolean) sessionField(session, "virtualLocked")))
            throw new AssertionError("real socket blueprint placement lacks authoritative locked session");
        HologramCapture capture = (HologramCapture) sessionField(session, "capture");
        if (capture != null && capture.cells.size() == 1) {
            if ((Integer) sessionField(session, "job") != 1)
                throw new AssertionError("single material-confirm click did not start the server blueprint job");
            sufficientBlueprintJobStarted = true;
        }
    }

    private static Object sessionField(Object session, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = session.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(session);
    }

    private static int installMuffler(WorldServer world, EntityPlayerMP player, BaseMetaTileEntity controller, int x,
        int y, int z) {
        int id = -1;
        for (int i = 1; i < GregTechAPI.METATILEENTITIES.length; i++) {
            if (GregTechAPI.METATILEENTITIES[i] instanceof MTEHatchMuffler
                && ((MTEHatchMuffler) GregTechAPI.METATILEENTITIES[i]).mTier == 1) {
                id = i;
                break;
            }
        }
        if (id <= 0 || !world.isAirBlock(x, y + 3, z + 1))
            throw new IllegalStateException("fresh registered LV muffler fixture required");
        if (!world.setBlock(x, y + 3, z + 1, GregTechAPI.sBlockMachines, 0, 3))
            throw new IllegalStateException("muffler fixture placement failed");
        BaseMetaTileEntity base = (BaseMetaTileEntity) world.getTileEntity(x, y + 3, z + 1);
        base.setInitialValuesAsNBT(null, (short) id);
        base.setFrontFacing(ForgeDirection.UP);
        base.setOwnerName(player.getCommandSenderName());
        base.setOwnerUuid(player.getUniqueID());
        MTEElectricBlastFurnace machine = (MTEElectricBlastFurnace) controller.getMetaTileEntity();
        boolean accepted = false;
        for (IStructureElement<MTEElectricBlastFurnace> element : machine.getStructureDefinition()
            .getStructureFor("main")) {
            for (IStructureElement<?> part : HologramElementCatalog.parts(element)) {
                if (HologramHatchPolicy.declared(part)
                    .contains(HatchElement.Muffler)) {
                    accepted = HologramHatchPolicy.allowed(part, machine.getExtendedFacing(), ForgeDirection.UP)
                        && element.check(machine, world, x, y + 3, z + 1);
                    break;
                }
            }
            if (accepted) break;
        }
        machine.mMufflerHatches.clear();
        if (!accepted || !(base.getMetaTileEntity() instanceof MTEHatchMuffler) || base.getMetaTileID() != id)
            throw new AssertionError("installed real LV muffler must satisfy the actual EBF declaration");
        System.out.println(
            "[GTMB-HOLOGRAM-INTERACTION-SERVER] FIXTURE real LV muffler id=" + id
                + " at="
                + x
                + ","
                + (y + 3)
                + ","
                + (z + 1)
                + " facing="
                + base.getFrontFacing()
                + " declaredAccepted=true");
        return id;
    }

    static BaseMetaTileEntity createFixture(WorldServer world, EntityPlayerMP player, int x, int y, int z) {
        int id = -1;
        for (int i = 0; i < GregTechAPI.METATILEENTITIES.length; i++)
            if (GregTechAPI.METATILEENTITIES[i] instanceof MTEElectricBlastFurnace) {
                id = i;
                break;
            }
        if (id <= 0) throw new IllegalStateException("registered EBF required");
        for (int xx = x - 5; xx <= x + 5; xx++) for (int zz = z - 5; zz <= z + 5; zz++) {
            world.getChunkFromBlockCoords(xx, zz);
            for (int yy = y - 5; yy <= y + 5; yy++)
                if (!world.isAirBlock(xx, yy, zz)) throw new IllegalStateException("fixture must be fresh empty world");
        }
        world.setBlock(x, y, z, GregTechAPI.sBlockMachines, 0, 3);
        BaseMetaTileEntity base = (BaseMetaTileEntity) world.getTileEntity(x, y, z);
        base.setInitialValuesAsNBT(null, (short) id);
        base.setFrontFacing(ForgeDirection.NORTH);
        base.setOwnerName(player.getCommandSenderName());
        base.setOwnerUuid(player.getUniqueID());
        return base;
    }
}
