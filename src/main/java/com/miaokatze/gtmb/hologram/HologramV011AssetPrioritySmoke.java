package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** A real two-cell regression: the optional decor prefab is the later chest's mandatory exact inventory asset. */
public final class HologramV011AssetPrioritySmoke {

    private HologramV011AssetPrioritySmoke() {}

    public static void run(WorldServer world, EntityPlayerMP actor) throws Exception {
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-followup-"),
            "isolated asset-priority world");
        Block multipart = null;
        for (Object entry : Block.blockRegistry) if (entry instanceof Block && entry.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) multipart = (Block) entry;
        require(multipart != null, "real installed multipart block");
        int sourceX = 1400, targetX = 1420, y = 88, z = 200;
        world.getChunkFromBlockCoords(sourceX, z);
        world.getChunkFromBlockCoords(targetX, z);
        require(
            world.isAirBlock(sourceX, y, z) && world.isAirBlock(sourceX + 4, y, z)
                && world.isAirBlock(targetX, y, z)
                && world.isAirBlock(targetX + 1, y, z),
            "fresh two-source/two-target fixture");
        NBTTagCompound micro = new NBTTagCompound();
        micro.setString("id", "savedMultipart");
        NBTTagCompound face = new NBTTagCompound();
        face.setString("id", "mcr_face");
        face.setByte("shape", (byte) 0x10);
        face.setString("material", "minecraft:stone");
        NBTTagList parts = new NBTTagList();
        parts.appendTag(face);
        micro.setTag("parts", parts);
        require(world.setBlock(sourceX, y, z, multipart, 0, 3), "actual native multipart source block installed");
        TileEntity sourceMicro = HologramNativePlacement.install(world, sourceX, y, z, multipart, 0, micro);
        require(
            world.getBlock(sourceX, y, z) == multipart && world.getTileEntity(sourceX, y, z) == sourceMicro,
            "real multipart source block and installed tile share the native world position");
        NBTTagCompound capturedMicro = snapshot(sourceMicro);
        HologramDecorMaterials.Descriptor costs = HologramDecorMaterials.describe(multipart, 0, capturedMicro);
        require(
            costs.costs.size() == 1 && costs.costs.get(0).numerator == 1 && costs.costs.get(0).denominator == 8,
            "native face has one prefab or one-eighth raw-stone alternative");
        ItemStack prefab = costs.costs.get(0).prebuilt.copy();
        prefab.stackSize = 1;
        require(world.setBlock(sourceX + 4, y, z, Blocks.chest, 0, 3), "real source chest installed");
        TileEntityChest sourceChest = (TileEntityChest) world.getTileEntity(sourceX + 4, y, z);
        sourceChest.setInventorySlotContents(0, prefab.copy());
        NBTTagCompound capturedChest = snapshot(sourceChest);
        Object session = session(actor, targetX, y, z);
        add(session, multipart, 0, capturedMicro, prefab, targetX, y, z, 0);
        add(session, Blocks.chest, 0, capturedChest, new ItemStack(Blocks.chest), targetX + 1, y, z, 1);
        for (int slot = 1; slot < actor.inventory.mainInventory.length; slot++)
            actor.inventory.mainInventory[slot] = null;
        actor.inventory.mainInventory[1] = prefab.copy();
        actor.inventory.mainInventory[2] = new ItemStack(Blocks.stone);
        actor.inventory.mainInventory[3] = new ItemStack(Blocks.chest);
        actor.setPosition(targetX + .5, y, z + .5);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("scenario", "decor-prefab-reserved-for-later-inventory-asset");
        receipt.setString(
            "fixtureKind",
            "actual native WorldServer/Service transactions; explicitly constructed two-cell Session");
        receipt.setTag("sourceMultipartBefore", capturedMicro.copy());
        receipt.setTag("sourceChestBefore", capturedChest.copy());
        receipt.setTag("inventoryBefore", inventory(actor));
        invoke("refreshPlan", session);
        HologramCapture capture = (HologramCapture) field(session, "capture");
        Veto veto = new Veto(targetX, y, z);
        MinecraftForge.EVENT_BUS.register(veto);
        try {
            receipt.setBoolean("cancelOperationHandled", (Boolean) invoke("operate", session, capture.cells.get(0), 0));
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
        }
        require(
            veto.events == 1 && world.isAirBlock(targetX, y, z)
                && world.getTileEntity(targetX, y, z) == null
                && !"placed".equals(capture.cells.get(0).status)
                && receipt.getTagList("inventoryBefore", 10)
                    .equals(inventory(actor)),
            "cancelled prioritized decor payment refunds exact prefab, raw stone and chest inventory");
        receipt.setTag("inventoryAfterCancelled", inventory(actor));
        receipt.setString("cancelledCellStatus", capture.cells.get(0).status);
        invoke("refreshPlan", session);
        require(
            (Boolean) invoke("operate", session, capture.cells.get(0), 0),
            "decor succeeds with actual raw alternative");
        require(
            actor.inventory.mainInventory[1] != null
                && ItemStack.areItemStacksEqual(actor.inventory.mainInventory[1], prefab)
                && actor.inventory.mainInventory[2] == null
                && actor.inventory.mainInventory[3] != null,
            "optional decor construction consumed raw stone while preserving the later mandatory exact prefab asset");
        receipt.setTag("inventoryAfterDecor", inventory(actor));
        receipt.setTag("placedMultipart", snapshot(world.getTileEntity(targetX, y, z)));
        require(
            HologramNativePlacement.matchesPlaced(multipart, 0, world.getTileEntity(targetX, y, z), capturedMicro),
            "real multipart target matches native source geometry and material");
        invoke("refreshPlan", session);
        require(
            (Boolean) invoke("operate", session, capture.cells.get(1), 1),
            "later chest receives its actual exact prefab inventory asset");
        TileEntityChest installed = (TileEntityChest) world.getTileEntity(targetX + 1, y, z);
        require(
            installed != null && ItemStack.areItemStacksEqual(installed.getStackInSlot(0), prefab),
            "actual target chest holds exactly the paid native prefab stack and NBT");
        require(
            actor.inventory.mainInventory[1] == null && actor.inventory.mainInventory[2] == null
                && actor.inventory.mainInventory[3] == null,
            "both successful cells consume each of three real payments once");
        require(
            world.getTileEntity(sourceX, y, z) == sourceMicro && world.getTileEntity(sourceX + 4, y, z) == sourceChest
                && capturedMicro.equals(snapshot(sourceMicro))
                && capturedChest.equals(snapshot(sourceChest)),
            "both real source tile identities and complete asset NBT remain unchanged");
        receipt.setTag("inventoryAfter", inventory(actor));
        receipt.setTag("placedChest", snapshot(installed));
        receipt.setTag("sourceMultipartAfter", snapshot(sourceMicro));
        receipt.setTag("sourceChestAfter", snapshot(sourceChest));
        receipt.setBoolean("sourceUnchanged", true);
        receipt.setBoolean("cancelledPaymentRefunded", true);
        receipt.setBoolean("decorUsedRawAlternative", true);
        receipt.setBoolean("exactChestAssetPaid", true);
        receipt.setBoolean("passed", true);
        HologramV011Evidence.write(receipt.getString("scenario") + ".nbt", receipt);
        System.out.println("[GTMB-FOLLOWUP-WORLD] PASS " + receipt.getString("scenario"));
    }

    private static Object session(EntityPlayerMP actor, int x, int y, int z) throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
        Constructor<?> constructor = type.getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(actor, x, y, z);
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.controllerType = "area";
        set(session, "virtual", true);
        set(session, "virtualPreset", preset);
        set(session, "capture", new HologramCapture(null, actor.getHeldItem()));
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.main = true;
        settings.me = false;
        settings.containers = false;
        set(session, "materialSettings", settings);
        set(session, "materialContext", new HologramMaterials.Context(actor, settings));
        return session;
    }

    private static void add(Object session, Block block, int metadata, NBTTagCompound payload, ItemStack sample, int x,
        int y, int z, int index) throws Exception {
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, y, z);
        cell.block = block;
        cell.meta = metadata;
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        cell.itemNbt = sample.hasTagCompound() ? HologramBlueprintNbt.encodeCaptured(sample.getTagCompound()) : "";
        cell.blueprintBlock = Block.blockRegistry.getNameForObject(block);
        cell.blueprintItem = Item.itemRegistry.getNameForObject(sample.getItem());
        cell.blueprintDamage = sample.getItemDamage();
        cell.role = "prefab";
        cell.userPosition = true;
        HologramCapture capture = (HologramCapture) field(session, "capture");
        capture.cells.add(cell);
        String key = x + "," + y + "," + z;
        capture.positions.put(key, cell);
        HologramPresets.Cell saved = HologramPresets.cell(sample, index, 0, 0, -1, false);
        saved.block = cell.blueprintBlock;
        saved.metadata = metadata;
        saved.tileNbt = cell.tileNbt;
        saved.itemNbt = cell.itemNbt;
        ((HologramPresets.Preset) field(session, "virtualPreset")).cells.add(saved);
        map(session, "pins").put(index, sample.copy());
        map(session, "coordinatePins").put(key, sample.copy());
        map(session, "autoTargets").put(index, sample.copy());
        map(session, "frozenAutoTargets").put(key, sample.copy());
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, ItemStack> map(Object owner, String name) throws Exception {
        return (Map<Object, ItemStack>) field(owner, name);
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

    private static Object invoke(String name, Object session, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length + 1];
        Object[] values = new Object[args.length + 1];
        types[0] = session.getClass();
        values[0] = session;
        for (int i = 0; i < args.length; i++) {
            types[i + 1] = args[i] instanceof Integer ? int.class : args[i].getClass();
            values[i + 1] = args[i];
        }
        Method method = HologramEngineInspection.declaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, values);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound value = new NBTTagCompound();
        tile.writeToNBT(value);
        return value;
    }

    private static NBTTagList inventory(EntityPlayerMP actor) {
        NBTTagList result = new NBTTagList();
        for (int i = 1; i < actor.inventory.mainInventory.length; i++) if (actor.inventory.mainInventory[i] != null) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("slot", i);
            actor.inventory.mainInventory[i].writeToNBT(row);
            result.appendTag(row);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }

    public static final class Veto {

        final int x, y, z;
        int events;

        Veto(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @SubscribeEvent
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.x == x && event.y == y && event.z == z) {
                events++;
                event.setCanceled(true);
            }
        }
    }
}
