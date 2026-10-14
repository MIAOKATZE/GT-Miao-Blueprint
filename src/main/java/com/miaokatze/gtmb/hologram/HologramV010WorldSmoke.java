package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Current opt-in real WorldServer transactions; fabricated Sessions are explicitly test fixtures. */
public final class HologramV010WorldSmoke {

    private static final String PREFIX = "[GTMB-V010-WORLD] ";

    private HologramV010WorldSmoke() {}

    public static void run(WorldServer world) throws Exception {
        if (!Boolean.getBoolean("gtmb.hologram.v010WorldSmoke")) return;
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-smoke-"),
            "fresh isolated smoke world");
        EntityPlayerMP player = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("9d5baa9b-e380-4d8c-83f7-cbca41561810"), "GTMBV010World"));
        player.inventory.mainInventory = new ItemStack[36];
        player.inventory.currentItem = 0;
        player.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        player.capabilities.isCreativeMode = false;
        player.capabilities.allowEdit = true;
        player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        clearObstructions(world, player);
        fuzzyChest(world, player, false);
        fuzzyChest(world, player, true);
        sealedInputBus(world, player);
        System.out.println(
            PREFIX
                + "FINAL PASS stale-review exterior BreakEvent once-only-recovery fuzzy-shell exact-internals actual-tag-refund sealed-schema2-inventory");
    }

    private static void clearObstructions(WorldServer world, EntityPlayerMP player) throws Exception {
        int x = 600, y = 88, z = 160;
        world.getChunkFromBlockCoords(x, z);
        player.setPosition(x + .5, y, z + .5);
        Block[] existing = { Blocks.dirt, Blocks.bedrock, Blocks.dirt, Blocks.stone, Blocks.dirt, Blocks.dirt };
        for (int i = 0; i < existing.length; i++) {
            require(world.isAirBlock(x + i, y, z), "fresh obstruction fixture " + i);
            require(world.setBlock(x + i, y, z, existing[i], 0, 3), "actual obstruction fixture " + i);
        }
        Object session = session(player, Blocks.stone, 0, new NBTTagCompound(), new ItemStack(Blocks.stone), x, y, z);
        HologramCapture capture = (HologramCapture) get(session, "capture");
        for (int i = 1; i < 5; i++) add(
            session,
            capture,
            cell(Blocks.stone, 0, new NBTTagCompound(), new ItemStack(Blocks.stone), x + i, y, z),
            new ItemStack(Blocks.stone));
        capture.cells.get(4).excluded = true;
        set(session, "virtualPlacement", true);
        set(session, "virtualLocked", true);
        set(session, "blueprintAwaitingConfirmation", true);
        invoke("refreshPlan", session);
        NBTTagCompound action = review(session);
        NBTTagCompound stale = (NBTTagCompound) action.copy();
        stale.setLong("placementReviewNonce", action.getLong("placementReviewNonce") - 1);
        require(rejected(session, stale), "stale review rejected before mutation");
        require(
            world.getBlock(x, y, z) == Blocks.dirt && count(player, new ItemStack(Blocks.dirt)) == 0,
            "stale review preserves world and inventory");
        BreakVeto veto = new BreakVeto(x + 2, y, z);
        MinecraftForge.EVENT_BUS.register(veto);
        try {
            invoke("removeBlueprintObstructions", session, action);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
        }
        require(
            world.isAirBlock(x, y, z) && count(player, new ItemStack(Blocks.dirt)) == 0,
            "accepted obstruction drop is committed to persisted recovery before inventory delivery");
        HologramMaterials.Context recovery = (HologramMaterials.Context) invoke("materialSource", session);
        recovery.flushRecovery();
        recovery.flushRecovery();
        require(
            world.isAirBlock(x, y, z) && count(player, new ItemStack(Blocks.dirt)) == 1,
            "clear removes only accepted obstruction and recovers one actual drop");
        require(
            veto.events == 1 && world.getBlock(x + 2, y, z) == Blocks.dirt,
            "actual Forge BreakEvent veto preserves protected obstruction");
        require(
            world.getBlock(x + 1, y, z) == Blocks.bedrock && world.getBlock(x + 3, y, z) == Blocks.stone
                && world.getBlock(x + 4, y, z) == Blocks.dirt
                && world.getBlock(x + 5, y, z) == Blocks.dirt,
            "bedrock matching target excluded cell and exterior all remain");
        require(
            rejected(session, action) && count(player, new ItemStack(Blocks.dirt)) == 1,
            "replayed review cannot clear again or duplicate recovery");
        NBTTagCompound fresh = review(session);
        MinecraftForge.EVENT_BUS.register(veto);
        try {
            invoke("removeBlueprintObstructions", session, fresh);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
        }
        recovery.flushRecovery();
        require(
            count(player, new ItemStack(Blocks.dirt)) == 1 && world.isAirBlock(x, y, z),
            "fresh retry skips cleared air and does not duplicate drop");
        player.inventory.mainInventory[1] = null;
    }

    private static void fuzzyChest(WorldServer world, EntityPlayerMP player, boolean cancel) throws Exception {
        int x = cancel ? 628 : 620, y = 88, z = 160;
        world.getChunkFromBlockCoords(x, z);
        player.setPosition(x + .5, y, z + .5);
        require(world.isAirBlock(x, y, z), "fresh fuzzy chest destination " + cancel);
        ItemStack retained = tagged(new ItemStack(Items.stick), "retained-exact");
        TileEntityChest original = new TileEntityChest();
        original.setInventorySlotContents(0, retained.copy());
        NBTTagCompound payload = new NBTTagCompound();
        original.writeToNBT(payload);
        ItemStack wanted = tagged(new ItemStack(Blocks.chest), "template-shell");
        ItemStack actual = tagged(new ItemStack(Blocks.chest), "paid-actual-shell");
        Object session = session(player, Blocks.chest, 0, payload, wanted, x, y, z);
        ((HologramPresets.Preset) get(session, "virtualPreset")).deidentificationLevel = 1;
        for (int i = 1; i < player.inventory.mainInventory.length; i++) player.inventory.mainInventory[i] = null;
        player.inventory.mainInventory[1] = actual.copy();
        player.inventory.mainInventory[2] = tagged(new ItemStack(Items.stick), "wrong-retained-variant");
        HologramCapture.Cell target = ((HologramCapture) get(session, "capture")).cells.get(0);
        invoke("refreshPlan", session);
        HologramBlueprintResources.Plan required = (HologramBlueprintResources.Plan) invoke(
            "resourcePlan",
            session,
            target);
        HologramMaterials.Context source = (HologramMaterials.Context) invoke("materialSource", session);
        require(
            required != null && required.items.stream()
                .anyMatch(stack -> ItemStack.areItemStacksEqual(stack, retained))
                && source.count(retained, 1) == 0
                && !required.missing(source)
                    .isEmpty(),
            "fuzzy shell quote still requires exact retained internal tags status=" + target.status
                + " resourceError="
                + target.resourceError
                + " resources="
                + (required == null ? -1 : required.items.size()));
        require(
            !(Boolean) invoke("operate", session, target, 0) && world.isAirBlock(x, y, z)
                && ItemStack.areItemStacksEqual(actual, player.inventory.mainInventory[1]),
            "wrong internal variant refuses actual placement without taking shell");
        player.inventory.mainInventory[2] = retained.copy();
        invoke("refreshPlan", session);
        PlaceVeto veto = new PlaceVeto(x, y, z);
        if (cancel) MinecraftForge.EVENT_BUS.register(veto);
        boolean result;
        try {
            result = (Boolean) invoke("operate", session, target, 0);
        } finally {
            if (cancel) MinecraftForge.EVENT_BUS.unregister(veto);
        }
        if (cancel) {
            require(
                !result && veto.events == 1 && world.isAirBlock(x, y, z),
                "actual PlaceEvent cancellation rolls world back");
            require(
                count(player, actual) == 1 && count(player, retained) == 1 && count(player, wanted) == 0,
                "cancel refunds actual paid shell tag and exact retained internal once");
        } else {
            require(
                result && world.getBlock(x, y, z) == Blocks.chest
                    && count(player, actual) == 0
                    && count(player, retained) == 0,
                "actual survival placement consumes fuzzy shell and exact internals");
            require(
                ItemStack
                    .areItemStacksEqual(retained, ((TileEntityChest) world.getTileEntity(x, y, z)).getStackInSlot(0)),
                "real placed chest retains exact internal tag");
        }
    }

    /** A real paid GT seal owns the assets; a different schema2 template cannot replace or recharge them. */
    private static void sealedInputBus(WorldServer world, EntityPlayerMP player) throws Exception {
        int machineId = -1;
        for (int id = 1; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity machine = GregTechAPI.METATILEENTITIES[id];
            if (machine != null && "MTEHatchInputBus".equals(
                machine.getClass()
                    .getSimpleName())) {
                machineId = id;
                break;
            }
        }
        require(machineId > 0, "sealed schema2 fixture uses registered ordinary GT input bus");
        int sourceX = 644, templateX = 648, destinationX = 652, y = 88, z = 160;
        for (int x = sourceX - 16; x <= destinationX + 16; x += 8)
            for (int zz = z - 16; zz <= z + 16; zz += 8) world.getChunkFromBlockCoords(x, zz);
        require(
            world.isAirBlock(sourceX, y, z) && world.isAirBlock(templateX, y, z)
                && world.isAirBlock(destinationX, y, z),
            "fresh sealed schema2 source template and destination");
        player.setPosition(destinationX + .5, y, z + .5);
        ItemStack plain = new ItemStack(GregTechAPI.sBlockMachines, 1, machineId);
        IInventory source = placeInputBus(world, player, plain, sourceX, y, z);
        ItemStack diamonds = tagged(new ItemStack(Items.diamond, 7), "paid-seal-diamonds");
        source.setInventorySlotContents(0, diamonds.copy());
        ItemStack paidSeal = HologramRecovery.drops(world, sourceX, y, z)
            .get(0);
        require(
            HologramRecovery.hasSeal(paidSeal) && HologramRecovery.validItem(paidSeal, GregTechAPI.sBlockMachines)
                && inventoryCount(source, diamonds) == 7,
            "real Recovery capture seals seven exact diamonds without extracting them");
        require(
            HologramRecovery.removeWithoutDrops(world, sourceX, y, z) && world.isAirBlock(sourceX, y, z),
            "fixture relocates source assets into one physical seal without source duplication");
        IInventory template = placeInputBus(world, player, plain, templateX, y, z);
        ItemStack emeralds = tagged(new ItemStack(Items.emerald, 11), "template-only-emeralds");
        template.setInventorySlotContents(0, emeralds.copy());
        ItemStack templateSeal = HologramRecovery.drops(world, templateX, y, z)
            .get(0);
        NBTTagCompound templatePayload = (NBTTagCompound) templateSeal.getTagCompound()
            .getCompoundTag(HologramRecovery.TAG)
            .getCompoundTag("tile")
            .copy();
        NBTTagCompound originalPaidTag = (NBTTagCompound) paidSeal.getTagCompound()
            .copy();
        Object session = session(
            player,
            GregTechAPI.sBlockMachines,
            world.getBlockMetadata(templateX, y, z),
            templatePayload,
            paidSeal,
            destinationX,
            y,
            z);
        ((HologramPresets.Preset) get(session, "virtualPreset")).deidentificationLevel = 1;
        for (int i = 1; i < player.inventory.mainInventory.length; i++) player.inventory.mainInventory[i] = null;
        player.inventory.mainInventory[1] = plain.copy();
        player.inventory.mainInventory[2] = emeralds.copy();
        HologramCapture.Cell target = ((HologramCapture) get(session, "capture")).cells.get(0);
        invoke("refreshPlan", session);
        List<?> plannedTargets = (List<?>) get(session, "planTargets");
        require(
            plannedTargets.size() == 1 && plannedTargets.get(0) instanceof ItemStack
                && ItemStack.areItemStacksEqual(paidSeal, (ItemStack) plannedTargets.get(0)),
            "sealed schema2 plan retains the complete paid seal at fuzzy level one");
        HologramBlueprintResources.Plan resources = (HologramBlueprintResources.Plan) invoke(
            "resourcePlan",
            session,
            target);
        require(resources.items.isEmpty(), "sealed schema2 quote does not charge template inventory again");
        require(
            !(Boolean) invoke("operate", session, target, 0) && world.isAirBlock(destinationX, y, z)
                && world.getTileEntity(destinationX, y, z) == null
                && count(player, plain) == 1
                && count(player, emeralds) == 11,
            "plain shell cannot fund sealed schema2 assets and consumes nothing at fuzzy level one status="
                + target.status
                + " resourceError="
                + target.resourceError
                + " session="
                + get(session, "status"));
        player.inventory.mainInventory[3] = player.inventory.mainInventory[1];
        player.inventory.mainInventory[1] = paidSeal.copy();
        invoke("refreshPlan", session);
        require(
            (Boolean) invoke("operate", session, target, 0)
                && world.getBlock(destinationX, y, z) == GregTechAPI.sBlockMachines,
            "one exact physical seal pays real schema2 placement status=" + target.status
                + " resourceError="
                + target.resourceError
                + " session="
                + get(session, "status"));
        require(
            world.getTileEntity(destinationX, y, z) instanceof IInventory,
            "placed sealed GT bus has real inventory");
        IInventory placed = (IInventory) world.getTileEntity(destinationX, y, z);
        require(
            inventoryCount(placed, diamonds) == 7 && inventoryCount(placed, emeralds) == 0
                && count(player, paidSeal) == 0
                && count(player, diamonds) == 0
                && count(player, plain) == 1
                && count(player, emeralds) == 11,
            "sealed schema2 consumes one seal preserves seven actual diamonds and neither adds nor charges template emeralds");
        require(
            originalPaidTag.equals(paidSeal.getTagCompound()) && inventoryCount(template, emeralds) == 11
                && ItemStack.areItemStacksEqual(
                    templateSeal,
                    HologramRecovery.drops(world, templateX, y, z)
                        .get(0)),
            "sealed schema2 placement leaves paid sample and real template source unchanged");
    }

    private static IInventory placeInputBus(WorldServer world, EntityPlayerMP player, ItemStack plain, int x, int y,
        int z) throws Exception {
        ItemStack source = plain.copy();
        require(
            source.getItem() instanceof ItemMachines
                && ((ItemMachines) source.getItem()).placeBlockAt(source, player, world, x, y, z, 1, .5F, .5F, .5F, 0)
                && HologramRecovery.supported(world.getTileEntity(x, y, z))
                && world.getTileEntity(x, y, z) instanceof IInventory,
            "native registered GT input bus inventory fixture " + x);
        return (IInventory) world.getTileEntity(x, y, z);
    }

    private static int inventoryCount(IInventory inventory, ItemStack target) {
        int result = 0;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && stack.isItemEqual(target) && ItemStack.areItemStackTagsEqual(stack, target))
                result += stack.stackSize;
        }
        return result;
    }

    private static NBTTagCompound review(Object session) throws Exception {
        set(session, "placementReviewIssued", true);
        NBTTagCompound action = new NBTTagCompound();
        action.setLong("placementReviewNonce", (Long) get(session, "placementReviewNonce"));
        action.setLong("planRevision", (Long) get(session, "planRevision"));
        return action;
    }

    private static boolean rejected(Object session, NBTTagCompound action) throws Exception {
        try {
            invoke("removeBlueprintObstructions", session, action);
            return false;
        } catch (java.io.IOException expected) {
            return true;
        }
    }

    private static HologramCapture.Cell cell(Block block, int meta, NBTTagCompound payload, ItemStack sample, int x,
        int y, int z) throws Exception {
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, y, z);
        cell.block = block;
        cell.meta = meta;
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        cell.itemNbt = sample.hasTagCompound() ? HologramBlueprintNbt.encodeCaptured(sample.getTagCompound()) : "";
        cell.blueprintBlock = Block.blockRegistry.getNameForObject(block);
        cell.blueprintItem = Item.itemRegistry.getNameForObject(sample.getItem());
        cell.blueprintDamage = sample.getItemDamage();
        cell.role = "prefab";
        cell.userPosition = true;
        return cell;
    }

    private static Object session(EntityPlayerMP player, Block block, int meta, NBTTagCompound payload,
        ItemStack sample, int x, int y, int z) throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramService$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(player, x, y, z);
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.controllerType = "area";
        set(session, "virtual", true);
        set(session, "virtualPreset", preset);
        HologramCapture capture = new HologramCapture(null, player.getHeldItem());
        set(session, "capture", capture);
        add(session, capture, cell(block, meta, payload, sample, x, y, z), sample);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = false;
        settings.containers = false;
        set(session, "materialSettings", settings);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static void add(Object session, HologramCapture capture, HologramCapture.Cell cell, ItemStack sample)
        throws Exception {
        int index = capture.cells.size();
        capture.cells.add(cell);
        String key = cell.x + "," + cell.y + "," + cell.z;
        capture.positions.put(key, cell);
        HologramPresets.Cell saved = HologramPresets.cell(sample, index, 0, 0, -1, false);
        saved.block = cell.blueprintBlock;
        saved.metadata = cell.meta;
        saved.tileNbt = cell.tileNbt;
        saved.itemNbt = cell.itemNbt;
        ((HologramPresets.Preset) get(session, "virtualPreset")).cells.add(saved);
        ((Map<Integer, ItemStack>) get(session, "pins")).put(index, sample.copy());
        ((Map<String, ItemStack>) get(session, "coordinatePins")).put(key, sample.copy());
        ((Map<Integer, ItemStack>) get(session, "autoTargets")).put(index, sample.copy());
        ((Map<String, ItemStack>) get(session, "frozenAutoTargets")).put(key, sample.copy());
    }

    private static ItemStack tagged(ItemStack stack, String value) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("v010Variant", value);
        stack.setTagCompound(tag);
        return stack;
    }

    private static int count(EntityPlayerMP player, ItemStack target) {
        int result = 0;
        for (int i = 1; i < player.inventory.mainInventory.length; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack != null && stack.isItemEqual(target) && ItemStack.areItemStackTagsEqual(stack, target))
                result += stack.stackSize;
        }
        return result;
    }

    private static Object invoke(String name, Object session, Object... arguments) throws Exception {
        Class<?>[] types = new Class<?>[arguments.length + 1];
        types[0] = session.getClass();
        Object[] values = new Object[arguments.length + 1];
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
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
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

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError("v010 world: " + description);
        System.out.println(PREFIX + "PASS " + description);
    }

    public static final class BreakVeto {

        final int x, y, z;
        int events;

        BreakVeto(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @SubscribeEvent
        public void veto(BlockEvent.BreakEvent event) {
            if (event.x == x && event.y == y && event.z == z) {
                events++;
                event.setCanceled(true);
            }
        }
    }

    public static final class PlaceVeto {

        final int x, y, z;
        int events;

        PlaceVeto(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @SubscribeEvent
        public void veto(BlockEvent.PlaceEvent event) {
            if (event.x == x && event.y == y && event.z == z) {
                events++;
                event.setCanceled(true);
            }
        }
    }
}
