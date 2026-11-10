package com.miaokatze.gtmb.hologram;

import java.io.FileOutputStream;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.GregTechAPI;
import gregtech.api.casing.Casings;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.api.metatileentity.implementations.MTEFluidPipe;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;

/** Opt-in registered GT display fixture in the isolated smoke world, consumed by the actual client renderer. */
final class HologramNativeDisplaySmoke {

    private HologramNativeDisplaySmoke() {}

    static void run(WorldServer world) throws Exception {
        require(
            world.getWorldInfo()
                .getWorldName()
                .contains("hologram-smoke"),
            "isolated_world_guard");
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", "native-display-smoke");
        state.setInteger("x", 208);
        state.setInteger("y", 80);
        state.setInteger("z", 48);
        state.setBoolean("supported", true);
        state.setBoolean("target", true);
        state.setBoolean("captureComplete", true);
        state.setBoolean("buildable", true);
        state.setBoolean("virtual", true);
        state.setString("status", "真实 GT 管道、壳体贴图与六面覆盖板显示夹具");
        NBTTagList cells = new NBTTagList();
        Class<?>[] types = { MTEFluidPipe.class, MTEHatchInput.class, MTEHatchOutput.class };
        ItemStack coverItem = ItemList.Cover_Controller.get(1);
        require(CoverRegistry.isCover(coverItem), "real_cover_registered");
        for (int cell = 0; cell < types.length; cell++) {
            int id = registered(types[cell]);
            IMetaTileEntity prototype = GregTechAPI.METATILEENTITIES[id];
            int x = 208 + cell * 2, y = 80, z = 48;
            world.getChunkFromBlockCoords(x, z);
            require(world.isAirBlock(x, y, z), "fresh_fixture_air");
            require(
                world.setBlock(x, y, z, GregTechAPI.sBlockMachines, prototype.getTileEntityBaseType(), 3),
                "real_gt_block_created");
            TileEntity tile = world.getTileEntity(x, y, z);
            require(tile instanceof IGregTechTileEntity, "real_gt_tile_created");
            IGregTechTileEntity gt = (IGregTechTileEntity) tile;
            gt.setInitialValuesAsNBT(null, (short) id);
            gt.setFrontFacing(ForgeDirection.NORTH);
            gt.setColorization((byte) (cell == 0 ? 4 : -1));
            if (tile instanceof BaseMetaPipeEntity) {
                int connections = (1 << ForgeDirection.NORTH.ordinal()) | (1 << ForgeDirection.EAST.ordinal())
                    | (1 << ForgeDirection.UP.ordinal());
                ((BaseMetaPipeEntity) tile).mConnections = (byte) connections;
                ((MetaPipeEntity) gt.getMetaTileEntity()).mConnections = (byte) connections;
            } else {
                require(gt.getMetaTileEntity() instanceof MTEHatch, "real_hatch_created");
                ((MTEHatch) gt.getMetaTileEntity()).updateTexture(Casings.ElectrolyzerCasing.textureId);
            }
            ForgeDirection coveredSide = cell == 0 ? ForgeDirection.UP : ForgeDirection.EAST;
            gt.attachCover(CoverRegistry.buildCover(coverItem.copy(), coveredSide, gt));
            require(
                gt.getCoverAtSide(coveredSide)
                    .isValid(),
                "real_non_front_cover_attached");
            NBTTagCompound display = HologramDisplaySnapshot.capture(tile);
            require(
                display.getTagList("gt.covers", 10)
                    .tagCount() == 1,
                "native_cover_payload_captured");
            if (cell == 0) require(
                Integer.bitCount(display.getInteger("connections")) == 3,
                "native_pipe_branch_connections_captured");
            else require(
                (display.getInteger("texturePage") << 7 | display.getInteger("textureIndex"))
                    == Casings.ElectrolyzerCasing.textureId,
                "native_hatch_casing_texture_captured");
            ItemStack stack = gt.getMetaTileEntity()
                .getStackForm(1);
            require(stack != null && stack.getItem() != null, "native_machine_stack_captured");
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", x);
            row.setInteger("y", y);
            row.setInteger("z", z);
            row.setInteger("dx", cell * 2);
            row.setInteger("dy", 0);
            row.setInteger("dz", 0);
            row.setString(
                "id",
                String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(GregTechAPI.sBlockMachines)));
            row.setString("wantId", row.getString("id"));
            row.setInteger("meta", world.getBlockMetadata(x, y, z));
            row.setInteger("wantMeta", row.getInteger("meta"));
            row.setTag("actualStack", stack.writeToNBT(new NBTTagCompound()));
            row.setTag("targetStack", stack.writeToNBT(new NBTTagCompound()));
            row.setInteger(
                "actualFacing",
                gt.getFrontFacing()
                    .ordinal());
            row.setInteger(
                "targetFacing",
                gt.getFrontFacing()
                    .ordinal());
            row.setString("renderKind", "item");
            row.setString("role", cell == 0 ? "block" : "hatch");
            row.setBoolean("inScope", true);
            row.setString("status", "pending");
            row.setTag("actualDisplay", display.copy());
            row.setTag("targetDisplay", display.copy());
            cells.appendTag(row);
            require(gt.getMetaTileEntity() != prototype, "registered_prototype_remains_detached");
        }
        state.setTag("cells", cells);
        HologramPipeBlueprintSmoke.run(world, (BaseMetaPipeEntity) world.getTileEntity(208, 80, 48), coverItem);
        try (FileOutputStream stream = new FileOutputStream("hologram-native-display-client-state.nbt")) {
            CompressedStreamTools.writeCompressed(state, stream);
        }
        System.out.println(
            "[GTMB-HOLOGRAM-NATIVE-DISPLAY-SMOKE] FINAL PASS cells=3 real pipe branch; hatch casing; non-front covers");
        System.out.println("[GTMB-HOLOGRAM-NATIVE-DISPLAY-SMOKE] RECEIPT hologram-native-display-client-state.nbt");
    }

    private static int registered(Class<?> type) {
        for (int id = 1; id < GregTechAPI.METATILEENTITIES.length; id++)
            if (type.isInstance(GregTechAPI.METATILEENTITIES[id])) return id;
        throw new AssertionError("Missing registered fixture type " + type.getName());
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
