package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.api.CutTransforms;

/** Exact documented spatial fields. No inventory/owner traversal, prefix guessing or cache stripping. */
final class HologramCutTransformDirections {

    private HologramCutTransformDirections() {}

    static boolean apply(CutTransforms.State state, CutTransforms.Transform pose) throws IOException {
        NBTTagCompound tag = state.tile;
        if (HologramBlockTransformStorageDrawers.supports(state.block, tag)) {
            HologramBlockTransformStorageDrawers.apply(tag, pose);
            return true;
        }
        if (tag != null && (tag.getString("id")
            .equals("BaseMetaTileEntity")
            || tag.getString("id")
                .equals("BaseMetaPipeEntity")
            || type(state, "gregtech."))) {
            HologramBlockTransformGregTech.apply(tag, pose);
            return true;
        }
        if (tag != null && (tag.getString("id")
            .equals("savedMultipart") || type(state, "codechicken.multipart."))) {
            HologramCutTransformMultipart.apply(tag, pose);
            return true;
        }
        if (type(state, "appeng.")) {
            if (tag != null) ae(tag, pose);
            state.metadata = aeMetadata(
                state.block.getClass()
                    .getName(),
                state.metadata,
                pose);
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
        if ("BlockMolecularAssembler".equals(tag.getString("id")) && tag.hasKey("pushDirection", 3))
            tag.setInteger("pushDirection", pose.direction(tag.getInteger("pushDirection")));
        for (int side = 0; side < 6; side++) {
            NBTTagCompound extra = tag.getCompoundTag("extra:" + side);
            if (extra.hasKey("spin")) extra.setByte("spin", (byte) spin(side, extra.getByte("spin"), pose));
        }
        for (String prefix : new String[] { "def:", "extra:", "facade:" }) sideKeys(tag, prefix, pose);
        // Central cable extra:6 (including its native grid identity) is deliberately untouched.
    }

    private static int spin(int side, int spin, CutTransforms.Transform pose) {
        if (spin < 0 || spin > 3) return spin;
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

    static int aeMetadata(String type, int metadata, CutTransforms.Transform pose) {
        // All three use MetaRotation.getUp/setOrientation: the entire metadata is a ForgeDirection ordinal.
        if ("appeng.block.solids.BlockQuartzPillar".equals(type) || "appeng.block.misc.BlockQuartzTorch".equals(type)
            || "appeng.block.misc.BlockLightDetector".equals(type)) return pose.direction(metadata);
        return metadata;
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

}
