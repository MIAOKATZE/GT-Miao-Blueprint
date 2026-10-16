package com.miaokatze.gtmb.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.CutTransforms;

/** FMP's documented side/corner/edge/post encodings. Unknown parts remain complete and unchanged. */
final class HologramCutTransformMultipart {

    private HologramCutTransformMultipart() {}

    static void apply(NBTTagCompound tag, CutTransforms.Transform pose) {
        NBTTagList parts = tag.getTagList("parts", 10);
        for (int i = 0; i < parts.tagCount(); i++) {
            NBTTagCompound part = parts.getCompoundTagAt(i);
            String id = part.getString("id");
            if (id.equals("ae2_cablebus")) {
                HologramCutTransformDirections.ae(part, pose);
                continue;
            }
            int shape = part.getByte("shape") & 255, slot = shape & 15, next = slot;
            if (id.equals("mcr_face") || id.equals("mcr_hllw")) next = pose.direction(slot);
            else if (id.equals("mcr_cnr")) {
                int[] v = pose.vector((slot & 4) == 0 ? -1 : 1, (slot & 1) == 0 ? -1 : 1, (slot & 2) == 0 ? -1 : 1);
                next = (v[0] > 0 ? 4 : 0) | (v[1] > 0 ? 1 : 0) | (v[2] > 0 ? 2 : 0);
            } else if (id.equals("mcr_post") && slot < 3) {
                int[] axes = { 1, 3, 5 };
                int pair = pose.direction(axes[slot]) >> 1;
                next = pair == 0 ? 0 : pair == 1 ? 1 : 2;
            } else if (id.equals("mcr_edge") && slot < 12) {
                int[] v = edge(slot), wanted = pose.vector(v[0], v[1], v[2]);
                for (int n = 0; n < 12; n++) if (java.util.Arrays.equals(edge(n), wanted)) next = n;
            } else continue;
            part.setByte("shape", (byte) ((shape & 240) | next));
        }
    }

    private static int[] edge(int slot) {
        int axis = slot >> 2,
            bits = axis == 0 ? (slot & 3) << 1 : axis == 1 ? ((slot & 2) >> 1) | ((slot & 1) << 2) : slot & 3;
        return new int[] { axis == 2 ? 0 : (bits & 4) == 0 ? -1 : 1, axis == 0 ? 0 : (bits & 1) == 0 ? -1 : 1,
            axis == 1 ? 0 : (bits & 2) == 0 ? -1 : 1 };
    }
}
