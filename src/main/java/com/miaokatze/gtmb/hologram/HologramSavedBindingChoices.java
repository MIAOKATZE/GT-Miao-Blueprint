// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Immutable, owner-checked slot choices for this one saved-version request. Group zero is explicit "all others". */
public final class HologramSavedBindingChoices {
    private HologramSavedBindingChoices() {}
    public static final int MAX_CHOICES=(HologramPrivateNativeCache.FRAME-256)/4;
    static boolean valid(int[] packed) {
        if(packed==null||packed.length>MAX_CHOICES)return false;
        int last=-1;
        for(int p:packed){int group=p>>>6;if(p<0||group<=last||group>999999||(p&63)>=36)return false;last=group;}
        return true;
    }
    static int slot(int[] packed,int group) {
        int i=Arrays.binarySearch(packed,group<<6);
        if(i<0)i=~i;
        if(i<packed.length&&(packed[i]>>>6)==group)return packed[i]&63;
        return packed.length>0&&(packed[0]>>>6)==0?packed[0]&63:-1;
    }
    /** Display only; never authorizes the item/anchor shown on the client. */
    public static boolean terminal(ItemStack stack){return HologramNetworkBindings.isTerminal(stack);}
    static int requiredGroup(NBTTagCompound payload)throws IOException{
        if(payload==null||!payload.hasKey(HologramNetworkBindings.GROUP)||payload.hasKey(HologramNetworkBindings.ORIGINAL)
            ||"preserve".equals(payload.getString(HologramNetworkBindings.MODE)))return 0;
        return Integer.parseInt(HologramNetworkBindings.group(payload).substring(1));
    }
}
