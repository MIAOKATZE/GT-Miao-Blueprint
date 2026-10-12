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
    private File receipts;
    private boolean failed;
    private int ticks, lastPlaced = -1, lastStock = -1;
    private boolean sawBuilt, sawUpgraded;
    private int receiptPublishRetries;
    private int mufflerId = -1, lastMufflerWorld, lastMufflerStock;
    private final java.util.Map<String, int[]> blueprintTargets = new java.util.LinkedHashMap<>();
    private boolean sufficientBlueprintJobStarted;
    private boolean sawOneToOneCraftPreview;
    private int craftingEvents;

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
            if (++ticks > 18000) throw new IllegalStateException("real interaction timeout");
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
            if (complete.isFile()) {
                if (!sawBuilt || !sawUpgraded
                    || lastPlaced != 0
                    || lastStock != 192
                    || lastMufflerWorld != 0
                    || lastMufflerStock != 1)
                    throw new AssertionError(
                        "client completion requires independent built/upgraded/recovered world observations");
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
        for (int[] target : blueprintTargets.values())
            if (world.getBlock(target[0], target[1], target[2]) == net.minecraft.init.Blocks.stone) blueprintPlaced++;
        for (ItemStack stack : player.inventory.mainInventory)
            if (stack != null && stack.getItem() == Item.getItemFromBlock(net.minecraft.init.Blocks.stone))
                blueprintStock += stack.stackSize;
        if (blueprintPlaced + blueprintStock != 2) throw new AssertionError(
            "blueprint material conservation placed=" + blueprintPlaced + " stock=" + blueprintStock);
        if ("final".equals(stage) && (blueprintPlaced != 2 || !sufficientBlueprintJobStarted)) throw new AssertionError(
            "final receipt requires single-confirm sufficient and explicitly confirmed partial blueprint construction");
        n.setInteger("blueprintPlaced", blueprintPlaced);
        n.setInteger("blueprintStock", blueprintStock);
        n.setBoolean("sufficientBlueprintJobStarted", sufficientBlueprintJobStarted);
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
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            receiptPublishRetries = 0;
        } catch (java.nio.file.FileSystemException locked) {
            String reason = String.valueOf(locked.getReason())
                .toLowerCase(java.util.Locale.ROOT);
            boolean sharingViolation = reason.contains("另一个程序") || reason.contains("another process")
                || reason.contains("being used")
                || reason.contains("sharing violation");
            if (!sharingViolation || ++receiptPublishRetries > 20) throw locked;
            if (receiptPublishRetries == 1) System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-SERVER] receipt publication pending: Windows sharing violation; bounded 20 tick retry");
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

    /** Observe authoritative locked capture coordinates; client claims never choose the receipt's world cells. */
    private void observeBlueprintSession(NBTTagCompound receipt) throws Exception {
        java.lang.reflect.Field sessions = HologramService.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        Object session = ((java.util.Map<?, ?>) sessions.get(null)).get(player.getUniqueID());
        if (session == null || !((Boolean) sessionField(session, "virtualPlacement"))
            || !((Boolean) sessionField(session, "virtualLocked"))) return;
        receipt.setIntArray(
            "blueprintAnchor",
            new int[] { (Integer) sessionField(session, "x"), (Integer) sessionField(session, "y"),
                (Integer) sessionField(session, "z") });
        HologramCapture capture = (HologramCapture) sessionField(session, "capture");
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
        java.lang.reflect.Field sessions = HologramService.class.getDeclaredField("SESSIONS");
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
