package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Opt-in real world/storage fixture. prepare/restart use the same isolated save in separate JVMs. */
public final class HologramCutNativeSmoke {

    private static final UUID OWNER = UUID.fromString("a5ad64ca-6a26-445c-bf68-5b8fa9890203");
    private static final UUID REQUEST_A = UUID.fromString("729785f2-fb06-4f9b-a44f-5184a051de50");
    private static final UUID REQUEST_B = UUID.fromString("729785f2-fb06-4f9b-a44f-5184a051de51");
    private int ticks;
    private boolean finished;
    private int fluidDue = -1;

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.cutNativeSmoke")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramCutNativeSmoke());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END || ++ticks < 60) return;
        if (fluidDue >= 0) {
            if (ticks < fluidDue) return;
            finished = true;
            try {
                WorldServer world = MinecraftServer.getServer()
                    .worldServerForDimension(0);
                require(
                    world.getBlock(780, 199, 724)
                        .getMaterial()
                        .isLiquid(),
                    "pasted flowing water runs on a later natural server tick");
                HologramCutMultipartNativeSmoke.verifyLater(world);
                HologramCutAeNativeSmoke.verifyLater(world);
                System.out.println(
                    "[GTMB-CUT-AE-NATIVE] FINAL PASS naturalTicks=true filledCell37=true slot3=true originalOwnerRetained=true");
                System.out.println(
                    "[GTMB-CUT-NATIVE] FINAL PASS phase="
                        + System.getProperty("gtmb.hologram.cutPersistencePhase", "single")
                        + " chestGTInventoryMetadataAirFIFOReplayUndo diskVerified=true fluidNaturalTick=true");
            } catch (Throwable failure) {
                System.out.println("[GTMB-CUT-NATIVE] FINAL FAIL " + failure);
                failure.printStackTrace();
            } finally {
                if (!Boolean.getBoolean("gtmb.hologram.cutNativeKeepRunning")) MinecraftServer.getServer()
                    .initiateShutdown();
            }
            return;
        }
        finished = true;
        boolean waiting = false;
        try {
            run(
                MinecraftServer.getServer()
                    .worldServerForDimension(0));
            if (!"prepare".equals(System.getProperty("gtmb.hologram.cutPersistencePhase", "single"))) {
                fluidDue = ticks + 30;
                finished = false;
                waiting = true;
            }
        } catch (Throwable failure) {
            System.out.println("[GTMB-CUT-NATIVE] FINAL FAIL " + failure);
            failure.printStackTrace();
        } finally {
            if (!waiting && !Boolean.getBoolean("gtmb.hologram.cutNativeKeepRunning")) MinecraftServer.getServer()
                .initiateShutdown();
        }
    }

    public static void run(WorldServer world) throws Exception {
        if (Boolean.getBoolean("gtmb.hologram.dynamicDefinitionNativeSmoke"))
            HologramDynamicDefinitionNativeSmoke.run(world);
        String phase = System.getProperty("gtmb.hologram.cutPersistencePhase", "single");
        EntityPlayerMP player = FakePlayerFactory.get(world, new GameProfile(OWNER, "GTMBMoveSmoke"));
        player.setPosition(720.5, 200, 720.5);
        HologramCutStorage storage = new HologramCutStorage(HologramCutService.saveRoot(), OWNER);
        if (!"restart".equals(phase)) {
            require(
                storage.load()
                    .getTagList("entries", 10)
                    .tagCount() == 0,
                "fresh isolated clipboard");
            BaseMetaTileEntity gt = HologramInteractionSmoke.createFixture(world, player, 724, 200, 720);
            gt.setOwnerName("OriginalNativeIdentity");
            gt.setOwnerUuid(UUID.fromString("7d3c5811-5c28-4897-82e9-02dd31791a20"));
            world.setBlock(720, 200, 720, Blocks.chest, 5, 2);
            TileEntityChest chest = (TileEntityChest) world.getTileEntity(720, 200, 720);
            world.setBlockMetadataWithNotify(720, 200, 720, 5, 2);
            ItemStack diamonds = new ItemStack(Items.diamond, 7);
            diamonds.setStackDisplayName("raw move inventory receipt");
            chest.setInventorySlotContents(4, diamonds);
            NBTTagCompound chestNbt = snapshot(chest);
            chestNbt.setString("CustomName", "native chest configuration");
            chest.readFromNBT(chestNbt);
            NBTTagCompound cut = request("cut", REQUEST_A);
            cut.setInteger("x1", 720);
            cut.setInteger("y1", 200);
            cut.setInteger("z1", 720);
            cut.setInteger("x2", 725);
            cut.setInteger("y2", 200);
            cut.setInteger("z2", 720);
            NBTTagCompound reply = HologramCutService.execute(player, cut);
            success(reply, "raw chest GT and air cut");
            require(world.isAirBlock(720, 200, 720) && world.isAirBlock(724, 200, 720), "original blocks cleared");
            require(
                chest.isInvalid() && gt.isInvalid() && !world.loadedTileEntityList.contains(gt),
                "old native tiles detached");
            success(HologramCutService.execute(player, cut), "duplicate request ACK");
            require(
                storage.load()
                    .getTagList("entries", 10)
                    .tagCount() == 1,
                "duplicate cut never enqueues again");
            world.setBlock(736, 200, 720, Blocks.chest, 3, 2);
            ((TileEntityChest) world.getTileEntity(736, 200, 720))
                .setInventorySlotContents(1, new ItemStack(Items.emerald, 3));
            NBTTagCompound second = request("cut", REQUEST_B);
            second.setInteger("x1", 736);
            second.setInteger("x2", 737);
            second.setInteger("y1", 200);
            second.setInteger("y2", 200);
            second.setInteger("z1", 720);
            second.setInteger("z2", 720);
            success(HologramCutService.execute(player, second), "second FIFO entry");
            require(
                storage.load()
                    .getTagList("entries", 10)
                    .tagCount() == 2,
                "multiple durable entries");
            if ("prepare".equals(phase)) {
                // Legal crash window: the cut intent is durable while its original source is still present.
                NBTTagCompound interrupted = storage.load();
                NBTTagCompound entry = interrupted.getTagList("entries", 10)
                    .getCompoundTagAt(1);
                NBTTagCompound pending = new NBTTagCompound();
                pending.setTag("entry", entry.copy());
                pending.setInteger("dimension", world.provider.dimensionId);
                pending.setInteger("x", 736);
                pending.setInteger("y", 200);
                pending.setInteger("z", 720);
                pending.setBoolean("air", true);
                interrupted.setTag("pending", pending);
                storage.save(interrupted);
                HologramCutWorld.apply(world, entry, 736, 200, 720, false);
                HologramCutWorld.saveAndVerify(world, entry, 736, 200, 720, false);
                require(
                    world.getBlock(736, 200, 720) == Blocks.chest && storage.load()
                        .hasKey("pending"),
                    "prepared real disk crash window before source removal");
                System.out.println(
                    "[GTMB-CUT-NATIVE] FINAL PASS phase=prepare queue=2 rawChestGTInventoryAir durable=true interruptedJournal=true");
                return;
            }
        }
        if ("restart".equals(phase)) {
            require(
                !storage.load()
                    .hasKey("pending"),
                "startup first tick completed pending journal before fixture runs");
            require(world.isAirBlock(736, 200, 720), "startup redo removed the still-present interrupted source");
        }
        NBTTagCompound list = HologramCutService.execute(player, request("list", UUID.randomUUID()));
        success(list, "new service/storage instance list");
        require(
            list.getTagList("entries", 10)
                .tagCount() == 2,
            "queue persists in FIFO order");
        NBTTagCompound before = storage.load();
        NBTTagCompound original = before.getTagList("entries", 10)
            .getCompoundTagAt(0);
        require(
            REQUEST_A.toString()
                .equals(original.getString("id")),
            "first in first out order");
        NBTTagCompound invalid = request("paste", UUID.randomUUID());
        invalid.setString("id", REQUEST_A.toString());
        invalid.setInteger("originY", 256);
        require(
            !HologramCutService.execute(player, invalid)
                .getBoolean("success"),
            "invalid target refused");
        require(
            storage.load()
                .equals(before),
            "refused target retains exact raw record");
        // An air cell must overwrite a real target block, and replaced inventories must not drop.
        world.setBlock(753, 200, 720, Blocks.chest, 2, 2);
        ((TileEntityChest) world.getTileEntity(753, 200, 720))
            .setInventorySlotContents(0, new ItemStack(Items.gold_ingot, 9));
        NBTTagCompound paste = request("paste", UUID.randomUUID());
        paste.setString("id", REQUEST_A.toString());
        paste.setInteger("originX", 752);
        paste.setInteger("originY", 200);
        paste.setInteger("originZ", 720);
        success(HologramCutService.execute(player, paste), "paste native snapshot");
        require(world.isAirBlock(753, 200, 720), "captured air overwrites destination inventory");
        NBTTagList raw = original.getTagList("cells", 10);
        for (int i = 0; i < raw.tagCount(); i++) {
            NBTTagCompound cell = raw.getCompoundTagAt(i);
            int x = 752 + cell.getInteger("x");
            require(world.getBlockMetadata(x, 200, 720) == cell.getInteger("meta"), "exact original metadata");
            if (cell.hasKey("tile", 10)) {
                TileEntity tile = world.getTileEntity(x, 200, 720);
                require(
                    HologramCutEntry.relocatedTile(cell, x, 200, 720)
                        .equals(snapshot(tile)),
                    "raw native tile and inventory equal after root relocation");
                if (tile.canUpdate())
                    require(world.loadedTileEntityList.contains(tile), "pasted tile registered for natural ticking");
            }
        }
        success(HologramCutService.execute(player, paste), "paste duplicate ACK");
        require(
            storage.load()
                .getTagList("entries", 10)
                .tagCount() == 1,
            "successful paste consumes exactly once");
        NBTTagCompound undo = request("undo", UUID.randomUUID());
        undo.setString("id", REQUEST_B.toString());
        success(HologramCutService.execute(player, undo), "undo to original position");
        require(
            ((TileEntityChest) world.getTileEntity(736, 200, 720)).getStackInSlot(1).stackSize == 3,
            "undo original inventory");
        require(
            storage.load()
                .getTagList("entries", 10)
                .tagCount() == 0,
            "successful undo consumes entry");
        require(
            !storage.load()
                .hasKey("pending"),
            "world disk verified before journal completion");
        require(
            world
                .getEntitiesWithinAABB(
                    net.minecraft.entity.item.EntityItem.class,
                    net.minecraft.util.AxisAlignedBB.getBoundingBox(710, 190, 710, 770, 210, 730))
                .isEmpty(),
            "no break drops or duplicated inventory item entities");
        HologramCutTransformNativeSmoke.run(world, player);
        HologramCutAeNativeSmoke.run(world, player);
        HologramCutMultipartNativeSmoke.run(world, player);
        world.setBlock(780, 200, 720, Blocks.flowing_water, 0, 2);
        NBTTagCompound liquidCut = request("cut", UUID.randomUUID());
        liquidCut.setInteger("x1", 780);
        liquidCut.setInteger("x2", 780);
        liquidCut.setInteger("y1", 200);
        liquidCut.setInteger("y2", 200);
        liquidCut.setInteger("z1", 720);
        liquidCut.setInteger("z2", 720);
        NBTTagCompound liquid = HologramCutService.execute(player, liquidCut);
        success(liquid, "flowing water cut");
        NBTTagCompound liquidPaste = request("paste", UUID.randomUUID());
        liquidPaste.setString("id", liquid.getString("id"));
        liquidPaste.setInteger("originX", 780);
        liquidPaste.setInteger("originY", 200);
        liquidPaste.setInteger("originZ", 724);
        success(HologramCutService.execute(player, liquidPaste), "flowing water paste schedules native updates");
        require(world.getBlock(780, 200, 724) == Blocks.flowing_water, "liquid restored before natural ticks");
        System.out.println("[GTMB-CUT-NATIVE] CORE PASS phase=" + phase + " waitingForNaturalFluidTick=true");
    }

    private static NBTTagCompound request(String action, UUID id) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", action);
        request.setString("requestId", id.toString());
        return request;
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound result = new NBTTagCompound();
        tile.writeToNBT(result);
        return result;
    }

    private static void success(NBTTagCompound response, String label) {
        require(response.getBoolean("success"), label + ": " + response.getString("message"));
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
