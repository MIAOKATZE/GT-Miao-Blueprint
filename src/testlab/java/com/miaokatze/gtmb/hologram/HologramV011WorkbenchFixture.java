package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Actual NC advanced-panel inventory source for the independent author-workflow Socket scenario. */
public final class HologramV011WorkbenchFixture {

    private EntityPlayerMP actor;
    private TileEntity sourceTile;
    private NBTTagList sourceInventory;
    private NBTTagCompound sourceNbt;
    private int ticks;
    private int warmupTicks;
    private boolean done;

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.followupWorkbenchServer")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramV011WorkbenchFixture());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = MinecraftServer.getServer();
        try {
            require(++ticks <= 12000, "workbench scenario exceeded ten minutes");
            WorldServer world = server.worldServerForDimension(0);
            require(
                world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-followup-"),
                "isolated workbench world");
            Block panel = (Block) Block.blockRegistry.getObject("IC2NuclearControl:blockNuclearControlMain");
            require(panel != null, "real IC2NuclearControl native panel block is registered");
            if (actor == null) {
                if (server.getConfigurationManager().playerEntityList.isEmpty()) return;
                require(server.getConfigurationManager().playerEntityList.size() == 1, "one real fixture client");
                actor = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
                world.getChunkFromBlockCoords(50, 44);
                require(world.isAirBlock(50, 80, 44) && world.isAirBlock(51, 80, 44), "fresh native source positions");
                require(
                    world.setBlock(50, 80, 44, panel, 9, 3) && world.setBlock(51, 80, 44, Blocks.stone, 0, 3),
                    "real advanced panel and internal target installed");
                sourceTile = world.getTileEntity(50, 80, 44);
                require(sourceTile instanceof IInventory, "native advanced panel has real inventory");
                Class<?> registry = Class.forName("shedar.mods.ic2.nuclearcontrol.IC2NuclearControl");
                Item[] cards = { (Item) registry.getField("itemSensorLocationCard")
                    .get(null),
                    (Item) registry.getField("itemEnergySensorLocationCard")
                        .get(null),
                    (Item) registry.getField("itemMultipleSensorLocationCard")
                        .get(null) };
                for (int slot = 0; slot < cards.length; slot++) {
                    ItemStack stack = new ItemStack(cards[slot]);
                    NBTTagCompound tag = new NBTTagCompound();
                    tag.setInteger("x", slot == 0 ? 51 : 900 + slot);
                    tag.setInteger("y", 80);
                    tag.setInteger("z", 44);
                    if (slot == 0) tag.setString("followupAsset", "exact-non-coordinate-data-must-survive-relative");
                    if (slot == 1) {
                        tag.setInteger("targetType", 0);
                        tag.setDouble("energyL", 42);
                        tag.setDouble("maxStorageL", 128);
                    }
                    stack.setTagCompound(tag);
                    ((IInventory) sourceTile).setInventorySlotContents(slot, stack);
                }
                NBTTagCompound initialSource = snapshot(sourceTile);
                require(
                    initialSource.getString("id")
                        .equals("IC2NCAdvancedInfoPanel"),
                    "native NC advanced panel serializer id");
                actor.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
                actor.capabilities.allowFlying = actor.capabilities.isFlying = true;
                actor.sendPlayerAbilities();
                actor.playerNetServerHandler.setPlayerLocation(50.5, 80, 46.5, 180, 0);
                for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++)
                    actor.inventory.mainInventory[slot] = null;
                actor.inventory.mainInventory[0] = new ItemStack(
                    (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
                actor.inventory.currentItem = 0;
                actor.inventoryContainer.detectAndSendChanges();
                ItemStack[] physicalCards = new ItemStack[3];
                for (int slot = 0; slot < physicalCards.length; slot++)
                    physicalCards[slot] = ((IInventory) sourceTile).getStackInSlot(slot)
                        .copy();
                NBTTagCompound payment = HologramCoordinateServiceSmoke
                    .run(world, actor, panel, 9, initialSource, physicalCards, 50, 80, 44);
                HologramV011Evidence.write("nc-payment-world.nbt", payment);
                return;
            }
            require(
                world.getBlock(50, 80, 44) == panel && world.getTileEntity(50, 80, 44) == sourceTile
                    && world.getBlock(51, 80, 44) == Blocks.stone,
                "actual source block/tile identities unchanged");
            if (sourceNbt == null) {
                // Let the native tile initialize its card state and screen geometry before author actions.
                if (++warmupTicks < 40) return;
                sourceInventory = inventory((IInventory) sourceTile);
                sourceNbt = snapshot(sourceTile);
                NBTTagCompound ready = new NBTTagCompound();
                ready.setString(
                    "actor",
                    actor.getUniqueID()
                        .toString());
                ready.setString(
                    "world",
                    world.getWorldInfo()
                        .getWorldName());
                ready.setString(
                    "fixtureKind",
                    "real NC panel/cards and actual Netty player; no fabricated Service Session");
                ready.setTag("sourceTile", sourceNbt.copy());
                ready.setTag("sourceCards", sourceInventory.copy());
                ready.setInteger("nativeWarmupTicks", warmupTicks);
                ready.setBoolean("passed", true);
                HologramV011Evidence.write("workbench-server-ready.nbt", ready);
                return;
            }
            require(
                sourceInventory.equals(inventory((IInventory) sourceTile)),
                "all original native source card NBT/assets unchanged");
            if (HologramV011Evidence.directory()
                .resolve("workbench-client-fail.txt")
                .toFile()
                .isFile()) throw new AssertionError("actual workbench client failed");
            NBTTagCompound client = HologramV011Evidence.read("workbench-client-final.nbt");
            if (client == null) return;
            require(
                client.getBoolean("passed") && client.getBoolean("actualNettyClient"),
                "actual client completed the author workflow");
            NBTTagCompound result = new NBTTagCompound();
            result.setBoolean("passed", true);
            result.setBoolean("sourceUnchanged", true);
            result.setTag("sourceTileBefore", sourceNbt.copy());
            result.setTag("sourceTileAfter", snapshot(sourceTile));
            result.setTag("sourceCardsBefore", sourceInventory.copy());
            result.setTag("sourceCardsAfter", inventory((IInventory) sourceTile));
            result.setString(
                "fixtureKind",
                "native NC source identity and exact asset inventory observations; native cache fields recorded separately");
            HologramV011Evidence.write("workbench-server-final.nbt", result);
            HologramV011Evidence.marker(
                "workbench-server-pass.txt",
                "PASS actual native source identity and exact three-card NBT/assets unchanged");
            done = true;
            server.initiateShutdown();
        } catch (Throwable failure) {
            failure.printStackTrace();
            try {
                HologramV011Evidence.marker("workbench-server-fail.txt", "FAIL " + failure);
            } catch (Exception evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            done = true;
            server.initiateShutdown();
        }
    }

    private static NBTTagList inventory(IInventory source) {
        NBTTagList rows = new NBTTagList();
        for (int slot = 0; slot < source.getSizeInventory(); slot++) if (source.getStackInSlot(slot) != null) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("slot", slot);
            source.getStackInSlot(slot)
                .writeToNBT(row);
            rows.appendTag(row);
        }
        return rows;
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        return tag;
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }
}
