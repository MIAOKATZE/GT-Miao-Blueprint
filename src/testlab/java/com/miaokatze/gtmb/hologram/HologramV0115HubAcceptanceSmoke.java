package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Installed optional-mod read/write witnesses; explicitly does not claim long-fluid construction payment. */
public final class HologramV0115HubAcceptanceSmoke {

    private HologramV0115HubAcceptanceSmoke() {}

    public static NBTTagCompound run(WorldServer world, EntityPlayerMP actor) throws Exception {
        NBTTagList cases = new NBTTagList();
        int dimension = world.provider.dimensionId, y = 100, z = 1040;
        long amount = 3_000_000_123L;
        for (int family = 0; family < 2; family++) {
            boolean steam = family == 0;
            int x = 1160 + family * 16;
            String prefix = "com.miaokatze.gtsr.common.machine.";
            int hubId = registered(prefix + (steam ? "MTESteamHubArray" : "MTEWaterHubArray"));
            int nodeId = registered(prefix + "base." + (steam ? "MTESteamCacheNode" : "MTEWaterCacheNode"));
            world.theChunkProviderServer.loadChunk(x >> 4, z >> 4);
            world.theChunkProviderServer.loadChunk((x + 4) >> 4, z >> 4);
            ItemStack hubShell = new ItemStack(GregTechAPI.sBlockMachines, 1, hubId);
            IGregTechTileEntity hub = place(world, actor, hubShell, x, y, z);
            NBTTagCompound seed = HologramNativeCapture.capture((net.minecraft.tileentity.TileEntity) hub);
            String quantity = steam ? "mSteamStored" : "mWaterStored";
            FluidStack fluid = FluidRegistry.getFluidStack(steam ? "steam" : "water", 0);
            require(fluid != null, "registered native fluid");
            seed.setLong(quantity, amount);
            if (steam) seed.setTag("mStoredFluidType", fluid.writeToNBT(new NBTTagCompound()));
            else seed.setString(
                "mStoredFluidType",
                fluid.getFluid()
                    .getName());
            hub.getMetaTileEntity()
                .loadNBTData(seed);
            hub.getMetaTileEntity()
                .getClass()
                .getMethod("registerCacheNode", int.class, int.class, int.class, int.class, boolean.class)
                .invoke(hub.getMetaTileEntity(), x + 4, y, z, dimension, true);
            NBTTagCompound captured = HologramNativeCapture.capture((net.minecraft.tileentity.TileEntity) hub);
            require(captured.getLong(quantity) == amount, "actual native long read/save");
            require(
                steam ? captured.getCompoundTag("mStoredFluidType")
                    .getInteger("Amount") == 0
                    : captured.getString("mStoredFluidType")
                        .equals(
                            fluid.getFluid()
                                .getName()),
                "actual native type lock");
            HologramBlueprintResources.Plan bill = HologramBlueprintResources.plan(captured);
            require(
                bill.fluids.size() == 1 && bill.fluids.get(0).amount == amount
                    && bill.fluids.get(0).stack.isFluidEqual(fluid),
                "exact native split-fluid bill without double charge");
            NBTTagCompound isolated = new NBTTagCompound();
            HologramBlueprintNbt.includeResourceCompanions(captured, isolated, quantity);
            require(HologramBlueprintResources.plan(isolated).fluids.get(0).amount == amount, "paired root review");
            NBTTagCompound binding = (NBTTagCompound) Class
                .forName(prefix.replace("machine.", "util.") + "HubBindingUtil")
                .getMethod("createHubPosTag", int.class, int.class, int.class, int.class, String.class, boolean.class)
                .invoke(null, x, y, z, dimension, steam ? "steam" : "water", false);
            ItemStack nodeShell = new ItemStack(GregTechAPI.sBlockMachines, 1, nodeId);
            NBTTagCompound item = new NBTTagCompound();
            item.setTag("gtsr.hubPos", binding);
            nodeShell.setTagCompound(item);
            IGregTechTileEntity node = place(world, actor, nodeShell, x + 4, y, z);
            require(bound(node, x, y, z, dimension), "actual native item placement consumes binding tuple");
            NBTTagCompound nodeCaptured = HologramNativeCapture.capture((net.minecraft.tileentity.TileEntity) node);
            HologramPresets.Preset raw = new HologramPresets.Preset();
            raw.schema = 2;
            raw.controllerType = "area";
            for (int index = 0; index < 2; index++) {
                HologramPresets.Cell cell = HologramPresets
                    .cell(index == 0 ? hubShell : nodeShell, index * 4, 0, 0, -1, index == 0);
                cell.block = String
                    .valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(GregTechAPI.sBlockMachines));
                cell.metadata = world.getBlockMetadata(x + index * 4, y, z);
                cell.tileNbt = HologramBlueprintNbt.encodeCaptured(index == 0 ? captured : nodeCaptured);
                if (index == 1) cell.itemNbt = HologramBlueprintNbt.encodeCaptured(item);
                raw.cells.add(cell);
            }
            HologramBlueprintProcessing.Policy personal = policy("self");
            HologramPresets.Preset self = HologramBlueprintProcessing.process(raw, personal, dimension, x, y, z);
            node.getMetaTileEntity()
                .loadNBTData(HologramBlueprintNbt.decode(self.cells.get(1).tileNbt));
            require(bound(node, x, y, z, dimension), "personal native reader keeps original binding");
            HologramPresets.Preset shared = HologramBlueprintProcessing
                .process(raw, policy("share"), dimension, x, y, z);
            node.getMetaTileEntity()
                .loadNBTData(HologramBlueprintNbt.decode(shared.cells.get(1).tileNbt));
            require(
                !(Boolean) node.getMetaTileEntity()
                    .getClass()
                    .getMethod("isBoundToHub")
                    .invoke(node.getMetaTileEntity()) && !HologramBlueprintNbt.decode(shared.cells.get(0).tileNbt)
                        .hasKey("mBoundNodes")
                    && !HologramBlueprintNbt.decode(shared.cells.get(1).itemNbt)
                        .hasKey("gtsr.hubPos"),
                "shared native links cleared");
            for (HologramCoordinatePolicies.Relation relation : HologramCoordinatePolicies
                .inspect(raw, dimension, x, y, z)) personal.coordinateOverrides.put(relation.id, "relative");
            HologramPresets.Preset relative = HologramBlueprintProcessing.process(raw, personal, dimension, x, y, z);
            NBTTagCompound projected = HologramCoordinatePolicies.placementProjection(
                HologramBlueprintNbt.decode(relative.cells.get(1).tileNbt),
                dimension + 1,
                x + 68,
                y,
                z);
            node.getMetaTileEntity()
                .loadNBTData(HologramBlueprintNbt.nativePayload(projected));
            require(bound(node, x + 64, y, z, dimension + 1), "relative result accepted by actual native reader");
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("hubId", hubId);
            row.setInteger("nodeId", nodeId);
            row.setLong("amount", amount);
            row.setString("quantityKey", quantity);
            row.setBoolean("nativeReadSaveRoundtrip", true);
            row.setBoolean("resourceCompanion", true);
            row.setBoolean("selfShareRelative", true);
            cases.appendTag(row);
        }
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("cases", cases);
        result.setBoolean("actualLongFluidConstructionPayment", false);
        result.setString(
            "scope",
            "native placed MTEs; seeded native storage read/save; production accounting and node policy readers");
        result.setString("status", "PASS");
        System.out.println("[GTMB-V0115-HUB-ACCEPTANCE] " + result);
        return result;
    }

    private static int registered(String type) {
        for (int id = 1; id < GregTechAPI.METATILEENTITIES.length; id++)
            if (GregTechAPI.METATILEENTITIES[id] != null && GregTechAPI.METATILEENTITIES[id].getClass()
                .getName()
                .equals(type)) return id;
        throw new AssertionError("Missing registered native machine " + type);
    }

    private static IGregTechTileEntity place(WorldServer world, EntityPlayerMP actor, ItemStack sample, int x, int y,
        int z) {
        require(
            world.isAirBlock(x, y, z) && ((ItemMachines) sample.getItem())
                .placeBlockAt(sample.copy(), actor, world, x, y, z, 1, .5F, .5F, .5F, 0),
            "fresh native source placement");
        IGregTechTileEntity tile = (IGregTechTileEntity) world.getTileEntity(x, y, z);
        require(tile != null && tile.getMetaTileID() == sample.getItemDamage(), "native placed machine identity");
        return tile;
    }

    private static boolean bound(IGregTechTileEntity node, int x, int y, int z, int dimension) throws Exception {
        return (Boolean) node.getMetaTileEntity()
            .getClass()
            .getMethod("isBoundToHubAt", int.class, int.class, int.class, int.class)
            .invoke(node.getMetaTileEntity(), x, y, z, dimension);
    }

    private static HologramBlueprintProcessing.Policy policy(String tendency) throws Exception {
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults(tendency, 0);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        return policy;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("Native hub acceptance: " + message);
    }
}
