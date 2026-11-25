// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.github.bsideup.jabel.Desugar;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;

/** Scoped destructive work never turns template geometry or a material match into removal authority. */
final class HologramDeclaredNativeMutation {

    private HologramDeclaredNativeMutation() {}

    /** Exact membership read from GT's real native lists, not a forced checkMachine or a detached simulation. */
    static boolean witnessed(MTEEnhancedMultiBlockBase<?> machine, MTEHatch hatch) {
        return machine.mMachine && (contains(machine.mInputHatches, hatch) || contains(machine.mInputBusses, hatch)
            || contains(machine.mOutputHatches, hatch) || contains(machine.mOutputBusses, hatch)
            || contains(machine.mDynamoHatches, hatch) || contains(machine.mMufflerHatches, hatch)
            || contains(machine.mEnergyHatches, hatch) || contains(machine.mMaintenanceHatches, hatch)
            || contains(machine.mDualInputHatches, hatch));
    }

    private static boolean contains(List<?> members, MTEHatch hatch) {
        if (members == null || members.size() > 4096) return false;
        for (Object member : members) if (member == hatch) return true;
        return false;
    }

    @Desugar
    record Proof(EntityPlayerMP player, World world, MTEEnhancedMultiBlockBase<?> machine,
        int x, int y, int z, BaseMetaTileEntity tile, MTEHatch hatch, int meta, int id, ForgeDirection front) {

        static Proof read(EntityPlayerMP player, World world, MTEEnhancedMultiBlockBase<?> machine, int x, int y,
            int z) {
            if (!world.blockExists(x, y, z) || GregTechAPI.sBlockMachines == null
                || GregTechAPI.sBlockMachines.getClass() != gregtech.common.blocks.BlockMachines.class
                || world.getBlock(x, y, z) != GregTechAPI.sBlockMachines
                || !(world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity tile)
                || tile.getClass() != BaseMetaTileEntity.class
                || !(tile.getMetaTileEntity() instanceof MTEHatch hatch)) return null;
            var proof = new Proof(player, world, machine, x, y, z, tile, hatch, world.getBlockMetadata(x, y, z),
                tile.getMetaTileID(), tile.getFrontFacing());
            return proof.current() ? proof : null;
        }

        boolean exact(ForgeDirection expected) {
            return player.worldObj == world && world.blockExists(x, y, z)
                && world.getBlock(x, y, z) == GregTechAPI.sBlockMachines && world.getBlockMetadata(x, y, z) == meta
                && world.getTileEntity(x, y, z) == tile && tile.getWorldObj() == world && !tile.isInvalid()
                && tile.xCoord == x && tile.yCoord == y && tile.zCoord == z && tile.getMetaTileEntity() == hatch
                && hatch.getBaseMetaTileEntity() == tile && tile.getMetaTileID() == id
                && id > 0 && id < GregTechAPI.METATILEENTITIES.length
                && GregTechAPI.METATILEENTITIES[id] != null
                && GregTechAPI.METATILEENTITIES[id].getClass() == hatch.getClass()
                && tile.getFrontFacing() == expected && player.getUniqueID().equals(tile.getOwnerUuid());
        }

        boolean current() {
            return player.capabilities.allowEdit && !player.capabilities.isCreativeMode
                && !player.isDead && front != null && front != ForgeDirection.UNKNOWN
                && !machine.getBaseMetaTileEntity().isActive() && !tile.isActive()
                && exact(front) && witnessed(machine, hatch);
        }
    }

    static boolean rotate(Proof proof, int facing, BooleanSupplier authority) throws IOException {
        ForgeDirection wanted = ForgeDirection.getOrientation(facing);
        // Unknown end-user hatch callbacks may rewrite device state as well as the orientation.
        try {
            if (proof.hatch.getClass().getMethod("onFacingChange").getDeclaringClass() != MetaTileEntity.class
                || proof.tile.getClass() != BaseMetaTileEntity.class)
                throw new IOException("原生设备的朝向回调未获验证，原设备保留。");
        } catch (NoSuchMethodException invalid) {
            throw new IOException("原生设备不支持可核验的朝向修改。", invalid);
        }
        if (!authority.getAsBoolean() || !proof.current() || !proof.hatch.isFacingValid(wanted)
            || !proof.world.canMineBlock(proof.player, proof.x, proof.y, proof.z)
            || !proof.player.canPlayerEdit(proof.x, proof.y, proof.z, 1, proof.player.getHeldItem())
            || MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(proof.x, proof.y, proof.z, proof.world,
                GregTechAPI.sBlockMachines, proof.meta, proof.player))
            || !authority.getAsBoolean() || !proof.current()) return false;
        proof.tile.setFrontFacing(wanted);
        if (!proof.exact(wanted)) throw new IOException("朝向回调改变了现场或未接受目标方向，已停止。");
        proof.tile.markDirty();
        proof.world.markBlockForUpdate(proof.x, proof.y, proof.z);
        return true;
    }
}
