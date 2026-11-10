package com.miaokatze.gtmb.hologram;

import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

/** Real FMP composite: two microblock materials plus a real AE cable with its original native grid owner. */
final class HologramCutMultipartNativeSmoke {

    private static int originalOwner;

    private HologramCutMultipartNativeSmoke() {}

    static void run(WorldServer world, EntityPlayerMP player) throws Exception {
        Block fmp = find("codechicken.multipart.BlockMultipart");
        Block ae = find("appeng.block.networking.BlockCableBus");
        require(world.isAirBlock(800, 200, 720) && world.isAirBlock(804, 200, 720), "fresh FMP fixture");
        NBTTagCompound raw = HologramNativeCompatibilityFixture.seed(fmp, 800, 200, 720);
        raw.setString("id", "savedMultipart");
        NBTTagCompound cable = HologramNativeCompatibilityFixture.seed(ae, 800, 200, 720);
        cable.setString("id", "ae2_cablebus");
        raw.getTagList("parts", 10)
            .appendTag(cable);
        world.setBlock(800, 200, 720, fmp, 0, 2);
        TileEntity source = HologramCutWorld.createTile(world, raw);
        source.setWorldObj(world);
        world.getChunkFromChunkCoords(800 >> 4, 720 >> 4)
            .addTileEntity(source);
        HologramCutWorld.loadMultipart(source);
        HologramCutWorld.describeMultipart(world, source);
        appeng.api.parts.IPart originalCable = cable(source);
        require(originalCable.getGridNode() != null, "source AE part joined its native grid");
        int registeredOwner = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(player.getGameProfile());
        require(registeredOwner >= 0, "real native player registry provides nonnegative original owner");
        originalCable.getGridNode()
            .setPlayerID(registeredOwner);
        originalOwner = originalCable.getGridNode()
            .getPlayerID();
        require(originalOwner == registeredOwner, "source FMP node has a real registered original owner");
        NBTTagCompound original = snapshot(source);
        require(
            original.getTagList("parts", 10)
                .tagCount() == 3,
            "source contains both materials and native AE part");
        NBTTagCompound cut = request("cut");
        cut.setInteger("x1", 800);
        cut.setInteger("x2", 800);
        cut.setInteger("y1", 200);
        cut.setInteger("y2", 200);
        cut.setInteger("z1", 720);
        cut.setInteger("z2", 720);
        NBTTagCompound captured = HologramCutService.execute(player, cut);
        require(captured.getBoolean("success"), "FMP cut: " + captured.getString("message"));
        require(
            original.equals(HologramCutNativeReceipt.storedTile(player, captured.getString("id"), 0)),
            "complete raw multipart source NBT stored without transformation");
        require(world.isAirBlock(800, 200, 720) && source.isInvalid(), "FMP old composite invalidated without break");
        require(originalCable.getGridNode() == null, "FMP old AE part left native grid");
        NBTTagCompound paste = request("paste");
        paste.setString("id", captured.getString("id"));
        paste.setInteger("originX", 804);
        paste.setInteger("originY", 200);
        paste.setInteger("originZ", 720);
        NBTTagCompound placed = HologramCutService.execute(player, paste);
        require(placed.getBoolean("success"), "FMP paste: " + placed.getString("message"));
        TileEntity restored = world.getTileEntity(804, 200, 720);
        require(
            restored != source && Class.forName("codechicken.multipart.TileMultipart")
                .isInstance(restored),
            "real generated FMP tile replaces loading carrier");
        NBTTagCompound actual = snapshot(restored);
        HologramCutNativeReceipt.record("multipartAE", original, actual);
        require(
            actual.getTagList("parts", 10)
                .tagCount() == 3,
            "all native FMP components restored");
        for (int i = 0; i < 2; i++) require(
            original.getTagList("parts", 10)
                .getCompoundTagAt(i)
                .equals(
                    actual.getTagList("parts", 10)
                        .getCompoundTagAt(i)),
            "original microblock geometry/material preserved");
        require(
            cable(restored).getGridNode() != null && cable(restored).getGridNode()
                .getPlayerID() == originalOwner,
            "FMP restored AE part joined with original native owner");
        if (restored.canUpdate())
            require(world.loadedTileEntityList.contains(restored), "FMP composite registered for natural tick");
        // Uses FMP's actual batched description packet generator, as the service does after commit.
        Class.forName("codechicken.multipart.MultipartHelper")
            .getMethod("sendDescPacket", World.class, TileEntity.class)
            .invoke(null, world, restored);
        System.out.println(
            "[GTMB-CUT-FMP-NATIVE] CORE PASS parts=3 rawStoredNBT=true nativeChangesRecorded=true oldGridDetached=true newGridJoined=true descGenerated=true originalOwner="
                + originalOwner);
    }

    static void verifyLater(WorldServer world) throws Exception {
        TileEntity restored = world.getTileEntity(804, 200, 720);
        require(restored != null && !restored.isInvalid(), "FMP tile survives normal server ticks");
        require(
            cable(restored).getGridNode() != null && cable(restored).getGridNode()
                .getPlayerID() == originalOwner,
            "FMP native AE grid survives subsequent natural ticks with original owner");
        require(
            snapshot(restored).getTagList("parts", 10)
                .tagCount() == 3,
            "all raw FMP parts survive subsequent natural ticks");
        System.out.println(
            "[GTMB-CUT-FMP-NATIVE] FINAL PASS parts=3 naturalTicks=true nativeOwnerRetained=true originalOwner="
                + originalOwner);
    }

    private static appeng.api.parts.IPart cable(TileEntity tile) throws Exception {
        List<?> parts = (List<?>) tile.getClass()
            .getMethod("jPartList")
            .invoke(tile);
        Class<?> cableType = Class.forName("appeng.fmp.CableBusPart");
        java.util.List<String> actualTypes = new java.util.ArrayList<>();
        for (Object part : parts) {
            actualTypes.add(
                part.getClass()
                    .getName());
            if (!cableType.isInstance(part)) continue;
            Object bus = part.getClass()
                .getMethod("getCableBus")
                .invoke(part);
            return (appeng.api.parts.IPart) bus.getClass()
                .getMethod("getPart", ForgeDirection.class)
                .invoke(bus, ForgeDirection.UNKNOWN);
        }
        java.util.List<String> savedIds = new java.util.ArrayList<>();
        NBTTagList savedParts = snapshot(tile).getTagList("parts", 10);
        for (int i = 0; i < savedParts.tagCount(); i++) savedIds.add(
            savedParts.getCompoundTagAt(i)
                .getString("id"));
        throw new AssertionError("FMP AE cable part missing: actualTypes=" + actualTypes + " savedPartIds=" + savedIds);
    }

    private static Block find(String name) {
        for (Object block : Block.blockRegistry) if (block != null && block.getClass()
            .getName()
            .equals(name)) return (Block) block;
        throw new AssertionError("Required native fixture block missing: " + name);
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound result = new NBTTagCompound();
        tile.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    private static NBTTagCompound request(String action) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", action);
        request.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        return request;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
