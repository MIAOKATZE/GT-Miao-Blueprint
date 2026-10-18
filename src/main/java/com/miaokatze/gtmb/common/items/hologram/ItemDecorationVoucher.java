package com.miaokatze.gtmb.common.items.hologram;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.register.CreativeTabManager;
import com.miaokatze.gtmb.register.DecorationVoucherRegistry;

/** A consumable authorization for one placement job; no client-side right-click behavior. */
public final class ItemDecorationVoucher extends Item {

    public ItemDecorationVoucher() {
        setUnlocalizedName(DecorationVoucherRegistry.ITEM_ID);
        setTextureName(GTMiaoBlueprint.MODID + ":" + DecorationVoucherRegistry.ITEM_ID);
        setCreativeTab(CreativeTabManager.CREATIVE_TAB);
        setMaxStackSize(64);
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> tooltip, boolean advanced) {
        tooltip.add(EnumChatFormatting.GOLD + StatCollector.translateToLocal("item.decoration_voucher.description.0"));
        tooltip.add(EnumChatFormatting.GRAY + StatCollector.translateToLocal("item.decoration_voucher.description.1"));
        tooltip.add(EnumChatFormatting.GRAY + StatCollector.translateToLocal("item.decoration_voucher.description.2"));
    }
}
