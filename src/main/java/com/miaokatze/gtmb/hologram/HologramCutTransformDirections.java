package com.miaokatze.gtmb.hologram;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.miaokatze.gtmb.api.CutTransforms;

/** Exact documented spatial fields. No inventory/owner traversal, prefix guessing or cache stripping. */
final class HologramCutTransformDirections {

    private HologramCutTransformDirections() {}

    static boolean apply(CutTransforms.State state, CutTransforms.Transform pose) {
        NBTTagCompound tag = state.tile;
        if (tag != null && (tag.getString("id")
            .equals("BaseMetaTileEntity")
            || tag.getString("id")
                .equals("BaseMetaPipeEntity")
            || type(state, "gregtech."))) {
            gt(tag, pose);
            return true;
        }
        if (tag != null && (tag.getString("id")
            .equals("savedMultipart") || type(state, "codechicken.multipart."))) {
            HologramCutTransformMultipart.apply(tag, pose);
            return true;
        }
        if (tag != null && type(state, "appeng.")) {
            ae(tag, pose);
            return true;
        }
        return HologramCutTransformMetadata.apply(state, pose);
    }

    private static boolean type(CutTransforms.State state, String prefix) {
        for (Class<?> type = state.block == null ? null : state.block.getClass(); type
            != null; type = type.getSuperclass())
            if (type.getName()
                .startsWith(prefix)) return true;
        return false;
    }

    private static void gt(NBTTagCompound tag, CutTransforms.Transform pose) {
        if (tag.hasKey("mFacing")) {
            int front = tag.getShort("mFacing");
            if (front >= 0 && front < 6 && (tag.hasKey("eRotation") || tag.hasKey("eFlip"))) {
                ExtendedFacing original = ExtendedFacing.of(
                    ForgeDirection.getOrientation(front),
                    Rotation.byIndex(tag.getByte("eRotation")),
                    Flip.byIndex(tag.getByte("eFlip")));
                for (ExtendedFacing candidate : ExtendedFacing.values()) {
                    if (candidate.getDirection()
                        .ordinal()
                        != pose.direction(
                            original.getDirection()
                                .ordinal())
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
                    tag.setByte(
                        "eRotation",
                        (byte) candidate.getRotation()
                            .getIndex());
                    tag.setByte(
                        "eFlip",
                        (byte) candidate.getFlip()
                            .getIndex());
                    break;
                }
            }
            tag.setShort("mFacing", (short) pose.direction(front));
        }
        for (String key : new String[] { "mConnections", "mStrongRedstone" })
            if (tag.hasKey(key)) tag.setByte(key, (byte) mask(tag.getByte(key) & 255, pose));
        bytes(tag, "mRedstoneSided", pose);
        NBTTagList covers = tag.getTagList("gt.covers", 10);
        for (int i = 0; i < covers.tagCount(); i++) {
            NBTTagCompound cover = covers.getCompoundTagAt(i);
            if (cover.hasKey("s")) cover.setByte("s", (byte) pose.direction(cover.getByte("s")));
        }
        int id = tag.getInteger("mID");
        Object nativeType = id >= 0 && id < gregtech.api.GregTechAPI.METATILEENTITIES.length
            ? gregtech.api.GregTechAPI.METATILEENTITIES[id]
            : null;
        if (nativeType instanceof gregtech.api.metatileentity.implementations.MTEFluidPipe) {
            for (String key : new String[] { "mLastReceivedFrom", "mDisableInput" })
                if (tag.hasKey(key)) tag.setByte(key, (byte) mask(tag.getByte(key) & 255, pose));
        } else if (nativeType instanceof gregtech.api.metatileentity.implementations.MTEItemPipe
            && tag.hasKey("mLastReceivedFrom"))
            tag.setByte("mLastReceivedFrom", (byte) pose.direction(tag.getByte("mLastReceivedFrom")));
    }

    static void ae(NBTTagCompound tag, CutTransforms.Transform pose) {
        for (String key : new String[] { "orientation_forward", "orientation_up" }) if (tag.hasKey(key, 8)) {
            try {
                tag.setString(
                    key,
                    ForgeDirection.getOrientation(
                        pose.direction(
                            ForgeDirection.valueOf(tag.getString(key))
                                .ordinal()))
                        .name());
            } catch (IllegalArgumentException unknownNativeValue) { /* Preserve unrecognized native values. */ }
        }
        for (int side = 0; side < 6; side++) {
            NBTTagCompound extra = tag.getCompoundTag("extra:" + side);
            if (extra.hasKey("spin")) extra.setByte("spin", (byte) spin(side, extra.getByte("spin"), pose));
        }
        for (String prefix : new String[] { "def:", "extra:", "facade:" }) sideKeys(tag, prefix, pose);
        // Central cable extra:6 (including its native grid identity) is deliberately untouched.
    }

    private static int spin(int side, int spin, CutTransforms.Transform pose) {
        int[] localUp = { 2, 3, 1, 1, 1, 1 }, codes = { 0, 1, 3, 2 };
        int up = localUp[side], count = 0;
        for (int i = 0; i < 4; i++) if (codes[i] == spin) count = i;
        for (int i = 0; i < count; i++) up = around(up, side);
        int wanted = pose.direction(up), next = pose.direction(side);
        up = localUp[next];
        for (int i = 0; i < 4; i++) {
            if (up == wanted) return codes[i];
            up = around(up, next);
        }
        return spin;
    }

    private static int around(int direction, int axis) {
        ForgeDirection d = ForgeDirection.getOrientation(direction), a = ForgeDirection.getOrientation(axis);
        for (ForgeDirection n : ForgeDirection.VALID_DIRECTIONS)
            if (n.offsetX == a.offsetY * d.offsetZ - a.offsetZ * d.offsetY
                && n.offsetY == a.offsetZ * d.offsetX - a.offsetX * d.offsetZ
                && n.offsetZ == a.offsetX * d.offsetY - a.offsetY * d.offsetX) return n.ordinal();
        return direction;
    }

    private static void sideKeys(NBTTagCompound tag, String prefix, CutTransforms.Transform pose) {
        NBTBase[] values = new NBTBase[6];
        for (int i = 0; i < 6; i++) {
            values[i] = tag.getTag(prefix + i);
            tag.removeTag(prefix + i);
        }
        for (int i = 0; i < 6; i++) if (values[i] != null) tag.setTag(prefix + pose.direction(i), values[i]);
    }

    private static void bytes(NBTTagCompound tag, String key, CutTransforms.Transform pose) {
        if (!tag.hasKey(key, 7) || tag.getByteArray(key).length != 6) return;
        byte[] source = tag.getByteArray(key), target = source.clone();
        for (int i = 0; i < 6; i++) target[pose.direction(i)] = source[i];
        tag.setByteArray(key, target);
    }

    private static int mask(int source, CutTransforms.Transform pose) {
        int target = source & ~63;
        for (int i = 0; i < 6; i++) if ((source & (1 << i)) != 0) target |= 1 << pose.direction(i);
        return target;
    }
}
