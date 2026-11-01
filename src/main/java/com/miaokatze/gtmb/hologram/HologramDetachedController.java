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
        if (HologramGTNLSemantics.isGTNLController(source)) return create(
            source.getBaseMetaTileEntity()
                .getMetaTileID(),
            source.getExtendedFacing());
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
        Object prototype = GregTechAPI.METATILEENTITIES[id];
        if (HologramGTNLSemantics.isGTNLController(prototype) && !HologramGTNLSemantics.supports(prototype))
            throw new IOException(HologramGTNLSemantics.unsupportedReason(prototype));
        HologramResourceWorld world = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        BaseMetaTileEntity tile = new BaseMetaTileEntity();
        tile.setWorldObj(world);
        tile.xCoord = 0;
        tile.yCoord = 128;
        tile.zCoord = 0;
        world.tile = tile;
        // GTNL loadNBTData can consume external machine data files. Template declarations
        // need only a fresh reviewed controller; retained physical NBT stays in the blueprint.
        NBTTagCompound owned = state == null || HologramGTNLSemantics.isGTNLController(prototype) ? null
            : (NBTTagCompound) state.copy();
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
