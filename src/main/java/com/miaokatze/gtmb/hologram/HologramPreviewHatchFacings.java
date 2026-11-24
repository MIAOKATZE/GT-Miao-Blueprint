// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Presentation-only facing proof aligned with the exact local candidate stacks. */
public final class HologramPreviewHatchFacings {

    private HologramPreviewHatchFacings() {}

    static int mask(int[] facings) {
        int mask = 0;
        if (facings != null) for (int facing : facings) if (facing >= 0 && facing < 6) mask |= 1 << facing;
        return mask;
    }

    static int[] directions(int mask) {
        mask &= 63;
        int[] facings = new int[Integer.bitCount(mask)];
        int index = 0;
        for (int direction = 0; direction < 6; direction++)
            if ((mask & 1 << direction) != 0) facings[index++] = direction;
        return facings;
    }

    public static int[] forCandidate(NBTTagCompound row, ItemStack target) {
        byte[] masks = row.getByteArray("candidateFacingMasks");
        NBTTagList candidates = row.getTagList("candidates", 10);
        if (target == null || masks.length != candidates.tagCount()) return new int[0];
        for (int index = 0; index < masks.length; index++) {
            if (masks[index] != 0
                && HologramValues.same(target, ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(index))))
                return directions(masks[index]);
        }
        return new int[0];
    }
}
