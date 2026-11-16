// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.common.items.hologram;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.common.util.BlueprintNativeTooltip;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.register.CreativeTabManager;
import com.miaokatze.gtmb.register.DecorationVoucherRegistry;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** A consumable authorization for one placement job; no client-side right-click behavior. */
public final class ItemDecorationVoucher extends Item {

    public ItemDecorationVoucher() {
        setUnlocalizedName(DecorationVoucherRegistry.ITEM_ID);
        setTextureName(GTMiaoBlueprint.MODID + ":" + DecorationVoucherRegistry.ITEM_ID);
        setCreativeTab(CreativeTabManager.CREATIVE_TAB);
        setMaxStackSize(64);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> tooltip, boolean advanced) {
        BlueprintNativeTooltip.voucher(stack, tooltip);
    }
}
