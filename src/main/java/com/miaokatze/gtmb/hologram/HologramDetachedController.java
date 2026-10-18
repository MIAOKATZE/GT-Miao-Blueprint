package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Native constructors run on a fresh controller and a memory world, never on the registration prototype. */
final class HologramDetachedController {

    private HologramDetachedController() {}

    static MTEEnhancedMultiBlockBase<?> create(int id, ExtendedFacing facing) throws IOException {
        return create(id, facing, null);
    }

    static MTEEnhancedMultiBlockBase<?> fromLive(MTEEnhancedMultiBlockBase<?> source) throws IOException {
        MTEEnhancedMultiBlockBase<?> detached = create(
            source.getBaseMetaTileEntity()
                .getMetaTileID(),
            source.getExtendedFacing(),
            HologramNativeCapture.capture((TileEntity) source.getBaseMetaTileEntity()));
        TileEntity tile = (TileEntity) detached.getBaseMetaTileEntity();
        tile.xCoord = source.getBaseMetaTileEntity()
            .getXCoord();
        tile.yCoord = source.getBaseMetaTileEntity()
            .getYCoord();
        tile.zCoord = source.getBaseMetaTileEntity()
            .getZCoord();
        return detached;
    }

    static MTEEnhancedMultiBlockBase<?> create(int id, ExtendedFacing facing, NBTTagCompound state) throws IOException {
        if (id < 0 || id >= GregTechAPI.METATILEENTITIES.length
            || !(GregTechAPI.METATILEENTITIES[id] instanceof MTEEnhancedMultiBlockBase))
            throw new IOException("来源控制器未注册，原蓝图已保留");
        HologramResourceWorld world = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        BaseMetaTileEntity tile = new BaseMetaTileEntity();
        tile.setWorldObj(world);
        tile.xCoord = 0;
        tile.yCoord = 128;
        tile.zCoord = 0;
        world.tile = tile;
        NBTTagCompound owned = state == null ? null : (NBTTagCompound) state.copy();
        if (owned != null) {
            owned.setInteger("x", 0);
            owned.setInteger("y", 128);
            owned.setInteger("z", 0);
        }
        tile.setInitialValuesAsNBT(owned, (short) id);
        if (!(tile.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase)
            || tile.getMetaTileEntity() == GregTechAPI.METATILEENTITIES[id]) throw new IOException("来源控制器无法创建独立声明实例");
        MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) tile.getMetaTileEntity();
        machine.setExtendedFacing(facing);
        return machine;
    }
}
