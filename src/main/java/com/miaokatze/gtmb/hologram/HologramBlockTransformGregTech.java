package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.miaokatze.gtmb.api.CutTransforms;

import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEFluidPipe;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEItemPipe;
import gregtech.api.util.GTUtility;
import gregtech.common.tileentities.automation.MTEItemDistributor;

/** GT5U .205 world schemas. Stage all edits before committing; never load a tile or a cover's saved payload. */
final class HologramBlockTransformGregTech {

    private HologramBlockTransformGregTech() {}

    static IMetaTileEntity prototype(NBTTagCompound tag) {
        if (tag == null || !tag.hasKey("mID", 3)) return null;
        int id = tag.getInteger("mID");
        return id >= 0 && id < GregTechAPI.METATILEENTITIES.length ? GregTechAPI.METATILEENTITIES[id] : null;
    }

    static void apply(NBTTagCompound tag, CutTransforms.Transform pose) throws IOException {
        apply(tag, pose, prototype(tag));
    }

    static void apply(NBTTagCompound tag, CutTransforms.Transform pose, IMetaTileEntity prototype) throws IOException {
        if (tag == null || pose.identity()) return;
        if (prototype == null) prototype = prototype(tag);
        NBTTagCompound edits = new NBTTagCompound();
        if (tag.hasKey("mFacing", 2)) {
            int front = tag.getShort("mFacing");
            if (valid(front)) {
                boolean maintenance = prototype instanceof MTEHatchMaintenance;
                boolean frame = maintenance || prototype instanceof MTEEnhancedMultiBlockBase
                    || tag.hasKey("eRotation")
                    || tag.hasKey("eFlip");
                if (frame) frame(tag, edits, front, pose, prototype, maintenance);
                else if (prototype != null && !(prototype instanceof MTEBasicMachine)
                    && !prototype.isFacingValid(ForgeDirection.getOrientation(pose.direction(front))))
                    throw new IOException("GT machine cannot represent transformed facing");
                edits.setShort("mFacing", (short) pose.direction(front));
            }
        }
        if (prototype instanceof MTEBasicMachine && tag.hasKey("mMainFacing", 3)) {
            int main = tag.getInteger("mMainFacing");
            // .205 has no overrides of MTEBasicMachine.isValidMainFacing: only horizontal main faces are native.
            if (main >= 2 && main < 6) {
                int next = pose.direction(main);
                if (next < 2) throw new IOException("GT basic machine cannot represent a vertical main facing");
                edits.setInteger("mMainFacing", next);
            }
        }
        for (String key : new String[] { "mConnections", "mStrongRedstone" })
            if (tag.hasKey(key, 1)) edits.setByte(key, (byte) mask(tag.getByte(key) & 255, pose));
        bytes(tag, edits, "mRedstoneSided", pose);
        if (prototype instanceof MTEFluidPipe) {
            for (String key : new String[] { "mLastReceivedFrom", "mDisableInput" })
                if (tag.hasKey(key, 1)) edits.setByte(key, (byte) mask(tag.getByte(key) & 255, pose));
        } else if (prototype instanceof MTEItemPipe && tag.hasKey("mLastReceivedFrom", 1))
            edits.setByte("mLastReceivedFrom", (byte) pose.direction(tag.getByte("mLastReceivedFrom")));
        if (prototype instanceof MTEItemDistributor) {
            bytes(tag, edits, "mItemsPerSide", pose);
            if (tag.hasKey("mCurrentSide", 1))
                edits.setByte("mCurrentSide", (byte) pose.direction(tag.getByte("mCurrentSide")));
        }
        covers(tag, edits, pose);
        for (Object key : edits.func_150296_c()) tag.setTag((String) key, edits.getTag((String) key));
    }

    private static void frame(NBTTagCompound tag, NBTTagCompound edits, int front, CutTransforms.Transform pose,
        IMetaTileEntity prototype, boolean maintenance) throws IOException {
        String rotationKey = maintenance ? "mRotation" : "eRotation";
        if (!quarter(tag, rotationKey) || !maintenance && !quarter(tag, "eFlip"))
            throw new IOException("Unrecognized GT alignment value or tag type");
        ExtendedFacing original = ExtendedFacing.of(
            ForgeDirection.getOrientation(front),
            Rotation.byIndex(tag.getByte(rotationKey)),
            maintenance ? Flip.NONE : Flip.byIndex(tag.getByte("eFlip")));
        ExtendedFacing wanted = null;
        for (ExtendedFacing candidate : ExtendedFacing.values()) {
            if (maintenance && candidate.getFlip() != Flip.NONE) continue;
            if (candidate.getDirection()
                .ordinal() != pose.direction(front)
                || candidate.getRelativeLeftInWorld()
                    .ordinal()
                    != pose.direction(
                        original.getRelativeLeftInWorld()
                            .ordinal())
                || candidate.getRelativeDownInWorld()
                    .ordinal()
                    != pose.direction(
                        original.getRelativeDownInWorld()
                            .ordinal()))
                continue;
            if (prototype instanceof IAlignment && !((IAlignment) prototype).getAlignmentLimits()
                .isNewExtendedFacingValid(candidate)) continue;
            wanted = candidate;
            break;
        }
        if (wanted == null) throw new IOException("GT machine cannot represent transformed alignment");
        if (tag.hasKey(rotationKey, 1) || wanted.getRotation()
            .getIndex() != 0) edits.setByte(
                rotationKey,
                (byte) wanted.getRotation()
                    .getIndex());
        if (!maintenance && (tag.hasKey("eFlip", 1) || wanted.getFlip()
            .getIndex() != 0)) edits.setByte(
                "eFlip",
                (byte) wanted.getFlip()
                    .getIndex());
    }

    private static boolean quarter(NBTTagCompound tag, String key) {
        return !tag.hasKey(key) || tag.hasKey(key, 1) && tag.getByte(key) >= 0 && tag.getByte(key) < 4;
    }

    private static void covers(NBTTagCompound tag, NBTTagCompound edits, CutTransforms.Transform pose)
        throws IOException {
        if (!tag.hasKey("gt.covers", 9)) return;
        NBTTagList source = (NBTTagList) tag.getTag("gt.covers");
        if (source.tagCount() > 0 && source.func_150303_d() != 10)
            throw new IOException("Unrecognized GT cover list element type");
        NBTTagList changed = (NBTTagList) source.copy();
        for (int i = 0; i < changed.tagCount(); i++) {
            NBTTagCompound cover = changed.getCompoundTagAt(i);
            int side = cover.getByte("s");
            if (cover.hasKey("s", 1) && valid(side)) cover.setByte("s", (byte) pose.direction(side));
            if (!cover.hasKey("id", 3) || !cover.hasKey("d", 10)
                || !cover.getCompoundTag("d")
                    .hasKey("coverSide", 1))
                continue;
            // .205 registers precisely this ItemList identity to CoverFluidStorageMonitor::new. Resolve the identity
            // and registration without invoking any cover factory or reader (including third-party factories).
            if (!ItemList.Cover_FluidStorageMonitor.hasBeenSet())
                throw new IOException("GT fluid monitor identity is unavailable for spatial data");
            int monitor = GTUtility.stackToInt(ItemList.Cover_FluidStorageMonitor.get(1));
            if (cover.getInteger("id") == monitor) {
                if (!CoverRegistry.isCover(GTUtility.intToStack(monitor)))
                    throw new IOException("GT fluid monitor cover registration is unavailable");
                NBTTagCompound data = cover.getCompoundTag("d");
                data.setByte("coverSide", (byte) pose.direction(data.getByte("coverSide")));
            }
        }
        edits.setTag("gt.covers", changed);
    }

    private static void bytes(NBTTagCompound tag, NBTTagCompound edits, String key, CutTransforms.Transform pose) {
        if (!tag.hasKey(key, 7) || tag.getByteArray(key).length != 6) return;
        byte[] before = tag.getByteArray(key), after = new byte[6];
        for (int side = 0; side < 6; side++) after[pose.direction(side)] = before[side];
        edits.setByteArray(key, after);
    }

    private static int mask(int source, CutTransforms.Transform pose) {
        int result = source & ~63;
        for (int side = 0; side < 6; side++) if ((source & (1 << side)) != 0) result |= 1 << pose.direction(side);
        return result;
    }

    private static boolean valid(int side) {
        return side >= 0 && side < 6;
    }
}
